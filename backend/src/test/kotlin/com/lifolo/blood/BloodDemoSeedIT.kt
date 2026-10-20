package com.lifolo.blood

import com.lifolo.admin.kernel.DemoSeedContext
import com.lifolo.admin.kernel.DemoUser
import com.lifolo.admin.kernel.VerifySpec
import com.lifolo.demo.DemoWorld
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Port of server/test/demo-blood.test.js over the blood demo part. The real context (admin_demo_seed, wipe) is P6's;
 * here a small context creates the accounts, so the seeded world and the tester's usual steps can be checked.
 */
class BloodDemoSeedIT : BloodTestBase() {
    @Autowired lateinit var seed: BloodDemoSeed

    private val tracked = ArrayList<Pair<String, Map<String, Any?>>>()

    private inner class Ctx : DemoSeedContext {
        override val nowMs = System.currentTimeMillis()

        override fun user(key: String): DemoUser {
            val role = DemoWorld.ROLES.first { it.key == key }
            return DemoUser(DemoWorld.uidOf(key), DemoWorld.demoEmail(key), role.name)
        }

        override fun extraUser(key: String, profile: Map<String, Any?>): String {
            val uid = DemoWorld.uidOf("x-$key")
            identity.add(uid, DemoWorld.demoEmail("x-$key"))
            jdbc.update("INSERT INTO accounts (uid, email) VALUES (?, ?)", uid, DemoWorld.demoEmail("x-$key"))
            val cols = profile.keys.toList()
            jdbc.update(
                "INSERT INTO user_profiles (uid, ${cols.joinToString(", ")}) VALUES (?, ${cols.joinToString(", ") { "?" }})",
                *(listOf<Any?>(uid) + cols.map { profile[it] }).toTypedArray(),
            )
            jdbc.update("INSERT INTO device_tokens (token, uid) VALUES (?, ?)", "tok-$uid", uid)
            return uid
        }

        override fun track(table: String, key: Map<String, Any?>) {
            tracked.add(table to key)
        }
    }

    private fun seedWorld() {
        for (r in DemoWorld.ROLES) {
            val uid = DemoWorld.uidOf(r.key)
            identity.add(uid, DemoWorld.demoEmail(r.key))
            jdbc.update("INSERT INTO accounts (uid, email) VALUES (?, ?)", uid, DemoWorld.demoEmail(r.key))
        }
        seed.seed(Ctx())
        admin()
    }

    private val P = DemoWorld.uidOf("patient")
    private val D = DemoWorld.uidOf("donor")
    private val CG = DemoWorld.uidOf("caregiver")
    private val BB = DemoWorld.uidOf("bloodbank")
    private fun x(k: String) = DemoWorld.uidOf("x-$k")
    private val district get() = BloodDemoSeed.DISTRICT

    private fun profile(uid: String) = row("SELECT * FROM user_profiles WHERE uid = ?", uid)

    private fun asDonor(uid: String) = GeoQuery.loadDonor(jdbc, uid)!!

    @Test
    fun `seeded - profiles, donors, eligibility like the app writes them`() {
        seedWorld()
        val donor = profile(D)
        assertEquals("O+", donor["blood_group"])
        assertTrue(BloodRules.isEligibleDonor(asDonor(D)))
        assertEquals("O+|${donor["geohash"]}", donor["donor_geo"])
        assertEquals(district, donor["place_name"])
        assertEquals(true, donor["notify"])
        assertTrue(jdbc.queryForList("SELECT request_id FROM user_commitments WHERE uid = ?", String::class.java, D).contains("demo-rq-donor-confirmed"))
        assertFalse(BloodRules.isEligibleDonor(asDonor(CG)))
        val patient = profile(P)
        assertTrue(patient["name"] != null && patient["phone"] != null)
        assertTrue((patient["blood_group"] as String?).isNullOrEmpty())
        for (k in listOf("doctor", "assistant", "pharmacy", "lab", "ambulance", "bloodbank", "homecare", "caregiver")) {
            val u = profile(DemoWorld.uidOf(k))
            assertTrue(Regex("^01[3-9]\\d{8}$").matches(u["phone"] as String) && u["lat"] is Double, k)
        }
        val bg = jdbc.queryForList("SELECT uid FROM user_profiles WHERE uid LIKE 'demo-x-%' AND blood_group IS NOT NULL", String::class.java).map { asDonor(it) }
        assertTrue(bg.size in 8..15, "${bg.size}")
        assertTrue(bg.count { BloodRules.isEligibleDonor(it) } >= 5)
        assertTrue(bg.count { !BloodRules.isEligibleDonor(it) } >= 3)
        assertTrue(bg.map { it.bloodGroup }.toSet().size >= 7)
        val near = GeoQuery(jdbc).nearDonors(BloodRules.DONORS_FOR.getValue("A+"), donor["lat"] as Double, donor["lng"] as Double, 10.0)
        assertTrue(near.size >= 5, "${near.size}")
        assertTrue(tracked.any { it.first == "emergencies" } && tracked.any { it.first == "blood_banks" } && tracked.any { it.first == "sanctions" })
    }

    @Test
    fun `guest - nearby requests without name or phone, no demo appeals, blood banks`() {
        seedWorld()
        val c = com.lifolo.common.Places.district(district)!!
        val g = call(null, "guest_requests", mapOf("lat" to c.lat, "lng" to c.lng, "maxKm" to 10), ip = "9.1.1.1")
        assertEquals(200, g.status, g.toString())
        val ids = g.body["items"].map { it["id"].textValue() }
        for (id in listOf("demo-rq-patient-open", "demo-rq-urgent", "demo-rq-fare", "demo-rq-oplus", "demo-rq-thal", "demo-rq-oneg", "demo-rq-donor-confirmed")) {
            assertTrue(id in ids, id)
        }
        assertFalse("demo-rq-far" in ids)
        assertFalse("demo-rq-patient-done" in ids)
        assertEquals("demo-rq-urgent", ids[0])
        assertTrue(g.body["items"].all { !it.has("requesterName") && !it.toString().contains("0170000") })
        assertTrue(g.body["items"].first { it["id"].textValue() == "demo-rq-fare" }["travelFare"].booleanValue())
        assertEquals(0, call(null, "emergencies_active", mapOf("district" to district), ip = "9.1.1.1").body["items"].size())
        val banks = call(null, "bloodbanks", mapOf("district" to district), ip = "9.1.1.1").body["items"].map { it["id"].textValue() }
        assertTrue(listOf("demo-bb-mine", "demo-bb-govt", "demo-bb-club").all { it in banks }, banks.toString())
        assertFalse("demo-bb-pending" in banks)
    }

    @Test
    fun `donor - matching requests nearby, confirmed request with the number, appeal coming and the ended one`() {
        seedWorld()
        val me = asDonor(D)
        val view = BloodRules.BLOOD_GROUPS.filter { BloodRules.canDonate("O+", it) }
        fun near(km: Int) = call(D, "blood_requests_near", mapOf("lat" to me.lat, "lng" to me.lng, "maxKm" to km, "groups" to view))
            .body["items"].map { it["id"].textValue() }
        assertEquals(
            listOf("demo-rq-donor-confirmed", "demo-rq-fare", "demo-rq-oplus", "demo-rq-patient-open", "demo-rq-thal", "demo-rq-urgent"),
            near(10).sorted(),
        )
        assertTrue("demo-rq-far" in near(25))
        val resp = call(D, "blood_response_get", mapOf("requestId" to "demo-rq-donor-confirmed")).body["response"]
        assertEquals("accepted", resp["status"].textValue())
        assertEquals(true, resp["confirmed"].booleanValue())
        val contact = call(D, "blood_contact_get", mapOf("requestId" to "demo-rq-donor-confirmed")).body["contact"]
        assertTrue(Regex("^01700001\\d{3}$").matches(contact["phone"].textValue()) && contact["name"].textValue().contains("ডেমো"))
        assertTrue(call(D, "blood_response_get", mapOf("requestId" to "demo-rq-patient-open")).body["response"].isNull)

        val r = call(D, "emergencies_active")
        assertEquals(200, r.status, r.toString())
        val e = r.body["items"].first { it["id"].textValue() == "demo-em-live" }
        assertTrue(e["canCome"].booleanValue())
        assertEquals("O+", e["myGroup"].textValue())
        assertEquals(listOf("demo-em-ended"), r.body["ask"].map { it["id"].textValue() })
        assertEquals(200, call(D, "emergency_respond", mapOf("id" to "demo-em-live", "op" to "coming")).status)
        assertEquals(2, row("SELECT coming FROM emergency_groups WHERE emergency_id = 'demo-em-live' AND blood_group = 'O+'")["coming"])
        val before = lastDonation(D)
        assertEquals(200, call(D, "emergency_donated", mapOf("id" to "demo-em-ended", "donated" to false)).status)
        assertEquals(before, lastDonation(D))
        assertFalse(call(CG, "emergencies_active").body["items"][0]["canCome"].booleanValue())
    }

    @Test
    fun `respond, confirm, close, ineligible donors stopped, date change, new request found nearby`() {
        seedWorld()
        assertEquals(400, http("/api/respond", D, mapOf("requestId" to "demo-rq-oneg")).status)
        val cgr = http("/api/respond", CG, mapOf("requestId" to "demo-rq-fare"))
        assertEquals(400, cgr.status)
        assertTrue(cgr.error!!.contains("যোগ্য নন"))
        assertTrue(http("/api/respond", P, mapOf("requestId" to "demo-rq-urgent")).error!!.contains("প্রোফাইল"))

        val ok = http("/api/respond", D, mapOf("requestId" to "demo-rq-patient-open"))
        assertEquals(200, ok.status, ok.toString())
        assertEquals("01200001102", row("SELECT donor_phone FROM blood_responses WHERE request_id = 'demo-rq-patient-open' AND donor_uid = ?", D)["donor_phone"])
        assertEquals(3, row("SELECT accepted_count FROM blood_requests WHERE id = 'demo-rq-patient-open'")["accepted_count"])
        assertEquals("01200001101", row("SELECT phone FROM blood_request_contacts WHERE request_id = 'demo-rq-patient-open'")["phone"])
        assertTrue(inbox(P).any { it.contains("রাজি") })

        assertEquals(200, http("/api/respond", D, mapOf("requestId" to "demo-rq-fare")).status)
        assertEquals(200, call(D, "response_update", mapOf("requestId" to "demo-rq-fare", "op" to "cancel")).status)
        assertEquals("cancelled", row("SELECT status FROM blood_responses WHERE request_id = 'demo-rq-fare' AND donor_uid = ?", D)["status"])

        assertEquals(200, http("/api/edit-request", P, mapOf("requestId" to "demo-rq-patient-open", "note" to "ডেমো: সকাল ১০টার মধ্যে আসুন")).status)
        assertEquals(200, call(P, "response_update", mapOf("requestId" to "demo-rq-patient-open", "op" to "remove", "donorUid" to x("nadia"))).status)
        val cf = call(P, "confirm_donor", mapOf("requestId" to "demo-rq-patient-open", "donorUid" to D))
        assertEquals(200, cf.status, cf.toString())
        val after = row("SELECT confirmed_count, fulfilled FROM blood_requests WHERE id = 'demo-rq-patient-open'")
        assertEquals(2, after["confirmed_count"])
        assertEquals(true, after["fulfilled"])

        val cl = http("/api/close-request", P, mapOf("requestId" to "demo-rq-patient-open", "donatedUids" to listOf(D, x("sakib")), "bagsReceived" to 2))
        assertEquals(200, cl.status, cl.toString())
        assertEquals("closed", row("SELECT status FROM blood_requests WHERE id = 'demo-rq-patient-open'")["status"])
        assertEquals(today(), lastDonation(D))
        assertFalse(BloodRules.isEligibleDonor(asDonor(D)))
        assertEquals("cancelled", row("SELECT status FROM blood_responses WHERE request_id = 'demo-rq-donor-confirmed' AND donor_uid = ?", D)["status"])

        assertEquals(409, call(CG, "donation_date_set", mapOf("millis" to today() - 200 * DAY)).status)
        assertEquals(200, call(CG, "donation_date_set", mapOf("millis" to today() - 200 * DAY, "confirmed" to true)).status)
        assertTrue(BloodRules.isEligibleDonor(asDonor(CG)))

        val c = call(
            P, "request_create",
            mapOf(
                "requesterName" to "ডেমো রোগী রহিম", "requesterPhone" to "01200001101", "patientGroup" to "O+", "bags" to 1,
                "hospital" to "ডেমো জয়দেবপুর জেনারেল হাসপাতাল", "placeName" to district, "lat" to 24.00, "lng" to 90.43,
                "neededByMillis" to today() + DAY, "note" to "ডেমো",
            ),
        )
        assertEquals(200, c.status, c.toString())
        val rafiq = asDonor(x("rafiq"))
        val seen = call(x("rafiq"), "blood_requests_near", mapOf("lat" to rafiq.lat, "lng" to rafiq.lng, "maxKm" to 10)).body["items"].map { it["id"].textValue() }
        assertTrue(c.body["id"].textValue() in seen)
    }

    @Test
    fun `blood bank owner, statistics, saving hours, admin approves the pending one, admin lists`() {
        seedWorld()
        val mine = call(BB, "bloodbank_mine")
        assertEquals(200, mine.status, mine.toString())
        val st = mine.body["items"].associate { it["id"].textValue() to it["status"].textValue() }
        assertEquals(mapOf("demo-bb-mine" to "approved", "demo-bb-pending" to "pending"), st)
        assertTrue(mine.body["items"].first { it["id"].textValue() == "demo-bb-pending" }["docs"].any { it["type"].textValue() == "front" && it["has"].booleanValue() })
        assertTrue((row("SELECT total FROM listing_stats WHERE kind = 'bloodbank' AND listing_id = 'demo-bb-mine'")["total"] as Int) > 0)

        val b = mapper.convertValue(mine.body["items"].first { it["id"].textValue() == "demo-bb-mine" }, Map::class.java) as Map<*, *>
        @Suppress("UNCHECKED_CAST")
        val body = (b as Map<String, Any?>) + mapOf("open24" to false, "openAt" to "08:00", "closeAt" to "22:00", "termsAccepted" to true)
        val save = call(BB, "bloodbank_save", body)
        assertEquals(200, save.status, save.toString())
        assertEquals(false, save.body["pending"].booleanValue())
        assertEquals("approved", row("SELECT status FROM blood_banks WHERE id = 'demo-bb-mine'")["status"])

        assertEquals(200, call(P, "bloodbank_called", mapOf("id" to "demo-bb-govt")).status)

        val pend = call(ADMIN, "admin_bloodbanks", mapOf("status" to "pending"))
        val item = pend.body["items"].first { it["id"].textValue() == "demo-bb-pending" }
        assertTrue(item["verify"]["docs"].first { it["type"].textValue() == "front" }["has"].booleanValue())
        val checks = VerifySpec.checks("bloodbank", mapOf("kind" to item["kind"].textValue())).map { it.id }
        val ap = call(ADMIN, "admin_bloodbank_resolve", mapOf("id" to "demo-bb-pending", "status" to "approved", "checks" to checks))
        assertEquals(200, ap.status, ap.toString())
        assertTrue(call(P, "bloodbanks", mapOf("district" to district)).body["items"].any { it["id"].textValue() == "demo-bb-pending" })

        val corr = http("/api/admin/corrections", ADMIN, emptyMap())
        assertTrue(corr.body["items"].any { it["id"].textValue() == "demo-corr-farhana" })
        val ems = call(ADMIN, "admin_emergencies").body["items"].filter { it["id"].textValue().startsWith("demo-") }
            .map { it["id"].textValue() to it["live"].booleanValue() }.sortedBy { it.first }
        assertEquals(listOf("demo-em-ended" to false, "demo-em-live" to true), ems)
    }
}
