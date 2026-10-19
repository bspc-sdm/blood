package com.lifolo.ambulance

import com.lifolo.admin.kernel.DemoSeedContext
import com.lifolo.admin.kernel.DemoUser
import com.lifolo.admin.kernel.VerifySpec
import com.lifolo.demo.DemoWorld
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.concurrent.TimeUnit

/**
 * The ambulance demo part with the real actions (ports of demo-ambulance.test.js and the demo cases of the
 * ambulance-fare/near/request tests). The demo context here is a small stand-in for P6's admin_demo_seed.
 */
class AmbulanceDemoIT : AmbTestBase() {
    @Autowired lateinit var demo: AmbulanceDemo

    private val P = "demo-patient"
    private val DA = "demo-ambulance"

    private inner class Ctx(override val nowMs: Long) : DemoSeedContext {
        override fun user(key: String): DemoUser {
            val role = DemoWorld.ROLES.first { it.key == key }
            val uid = DemoWorld.uidOf(key)
            person(uid, name = role.name, email = DemoWorld.demoEmail(key))
            return DemoUser(uid, DemoWorld.demoEmail(key), role.name)
        }

        override fun extraUser(key: String, profile: Map<String, Any?>): String {
            val uid = "demo-x-$key"
            user(uid, profile, "$key@${DemoWorld.DOMAIN}")
            return uid
        }

        override fun track(table: String, key: Map<String, Any?>) {}
    }

    private fun seed() {
        person(P, name = "ডেমো রোগী রহিম", phone = "01700000101")
        demo.seed(Ctx(now()))
        clearPushes()
    }

    private fun all(uid: String?, body: Map<String, Any?>): List<com.fasterxml.jackson.databind.JsonNode> {
        val r = ok(uid, "ambulances", body)
        return r["items"].toList() + r["busy"].toList()
    }

    @Test
    fun `ambulances - finding, calling, rating, the driver's work, a new car, approval - with the real actions`() {
        seed()
        val all = all(null, mapOf("district" to GAZIPUR))
        assertTrue(all.any { it["name"].textValue().contains("ডেমো জীবন অ্যাম্বুলেন্স (এসি)") && it["available"].booleanValue() }, "AC available")
        assertTrue(all.any { it["name"].textValue().contains("নন-এসি") && it.has("busy") }, "non-AC on a trip")
        assertFalse(all.any { it["id"].textValue() == "demo-svc-amb-icu-new" }, "the pending ICU car is not listed")
        assertTrue(all.none { it["canRate"].booleanValue() }, "guests cannot rate")
        val icu = all(null, mapOf("district" to GAZIPUR, "type" to "icu"))
        assertTrue(icu.isNotEmpty() && icu.all { t -> t["types"].any { it.textValue() == "icu" } })

        val pl = all(P, mapOf("district" to GAZIPUR))
        assertTrue(pl.filter { it["canRate"].booleanValue() }.any { it["id"].textValue() == "demo-svc-amb-icu" })
        val fresh = pl.first { !it["canRate"].booleanValue() && !it.has("busy") }
        ok(P, "ambulance_called", mapOf("id" to fresh["id"].textValue()))
        assertEquals(403, call(P, "ambulance_rate", mapOf("id" to fresh["id"].textValue(), "rating" to 5)).status, "not within 1 hour of the first call")
        val before = all(P, mapOf("district" to GAZIPUR)).first { it["id"].textValue() == "demo-svc-amb-icu" }
        ok(P, "ambulance_rate", mapOf("id" to "demo-svc-amb-icu", "rating" to 4, "comment" to "সময়মতো এসেছে"))
        val after = all(P, mapOf("district" to GAZIPUR)).first { it["id"].textValue() == "demo-svc-amb-icu" }
        assertEquals(before["ratingCount"].intValue() + 1, after["ratingCount"].intValue())

        val mine = ok(DA, "ambulance_mine")["items"].toList()
        assertTrue(mine.size >= 3)
        ok(DA, "ambulance_available", mapOf("id" to "demo-svc-amb-nonac", "available" to true))
        ok(DA, "ambulance_available", mapOf("id" to "demo-svc-amb-ac", "available" to false))
        val l2 = all(null, mapOf("district" to GAZIPUR, "x" to 1))
        assertTrue(l2.first { it["id"].textValue() == "demo-svc-amb-nonac" }["available"].booleanValue())
        assertTrue(l2.first { it["id"].textValue() == "demo-svc-amb-ac" }.has("busy"))
        val st = ok(DA, "ambulance_stats")["items"]
        val acStats = st.first { it["id"].textValue() == "demo-svc-amb-ac" }
        assertTrue(acStats["calls"]["total"].intValue() > 15)
        assertTrue(acStats["callers"].intValue() > 0)
        assertEquals(30, acStats["daily"].size())
        assertEquals(2, acStats["comments"].size())

        val ac = mine.first { it["id"].textValue() == "demo-svc-amb-ac" }
        val base = mapOf(
            "id" to ac["id"].textValue(), "name" to ac["name"].textValue(), "phone" to ac["phone"].textValue(), "vehicleNo" to ac["vehicleNo"].textValue(),
            "types" to ac["types"].map { it.textValue() }, "district" to ac["district"].textValue(), "upazilaId" to ac["upazilaId"].intValue(),
            "area" to ac["area"].textValue(), "termsAccepted" to true,
        )
        assertFalse(ok(DA, "ambulance_save", base + mapOf("fare" to "ঢাকা ৭০০০ টাকা"))["pending"].booleanValue(), "a fare change needs no review")
        assertTrue(ok(DA, "ambulance_save", base + mapOf("phone" to "01700000188"))["pending"].booleanValue())

        val nw = call(
            DA, "ambulance_save",
            mapOf(
                "name" to "ডেমো জীবন ফ্রিজার ভ্যান", "phone" to "01700000189", "vehicleNo" to "গাজীপুর-ছ ১১-২২৩৩", "types" to listOf("freezer"),
                "district" to GAZIPUR, "upazilaId" to 320, "area" to "শিববাড়ি", "fare" to "ফোনে জেনে নিন", "termsAccepted" to true,
            ),
        )
        assertEquals("docs_required", nw.code, "no new car without document photos")
        person(OWNER, email = "owner@example.com")
        val pend = ok(OWNER, "admin_ambulances", mapOf("status" to "pending"))["items"].toList()
        val icuNew = pend.first { it["id"].textValue() == "demo-svc-amb-icu-new" }
        assertEquals(3, icuNew["verify"]["docs"].count { it["has"].booleanValue() })
        assertEquals(400, call(OWNER, "admin_ambulance_resolve", mapOf("id" to "demo-svc-amb-icu-new", "approve" to true)).status, "not without the ticks")
        ok(OWNER, "admin_ambulance_resolve", mapOf("id" to "demo-svc-amb-icu-new", "status" to "approved", "checks" to VerifySpec.checks("ambulance", null).map { it.id }))
        assertTrue(all(P, mapOf("district" to GAZIPUR, "y" to 2)).any { it["id"].textValue() == "demo-svc-amb-icu-new" })
        assertEquals(403, call(DA, "admin_ambulances").status, "admins only")

        // demo and real are separate
        val real = "real-user-0003"
        person(real, name = "আসল", phone = "01811000002")
        ok(real, "ambulance_called", mapOf("id" to "demo-svc-amb-ac"))
        assertTrue(row("SELECT 1 AS x FROM ambulance_calls WHERE uid = ? AND ambulance_id = 'demo-svc-amb-ac'", real).isEmpty())
        jdbc.update(
            "INSERT INTO ambulance_calls (uid, ambulance_id, at, first_at) VALUES (?, 'demo-svc-amb-icu', ?, ?)",
            real, com.lifolo.common.Sql.ts(now() - 3 * HOUR), com.lifolo.common.Sql.ts(now() - 3 * HOUR),
        )
        assertEquals(404, call(real, "ambulance_rate", mapOf("id" to "demo-svc-amb-icu", "rating" to 1)).status, "a real person cannot rate a demo car")
    }

    @Test
    fun `demo in Gazipur - demo ambulances there (Sadar and Sreepur), none in Mymensingh`() {
        seed()
        val g = ok(P, "ambulances", mapOf("district" to GAZIPUR))["items"].toList()
        assertTrue(g.count { it["id"].textValue().startsWith("demo-svc-amb") } >= 4)
        assertTrue(g.count { it["upazilaId"].intValue() == 320 } >= 3)
        assertTrue(g.any { it["upazilaId"].intValue() == 321 })
        val m = ok(P, "ambulances", mapOf("district" to MYM))
        assertEquals(0, (m["items"].toList() + m["busy"].toList()).count { it["id"].textValue().startsWith("demo-") })
    }

    @Test
    fun `demo distances and the reminder - different distances, nearest first; the mini's driver is reminded; 30 minutes left`() {
        seed()
        val gz = com.lifolo.common.Places.DISTRICTS.first { it.en == "Gazipur" }
        redis.delete(AmbulanceService.REMIND_KEY)
        val r = ok(P, "ambulances", mapOf("district" to GAZIPUR, "lat" to gz.lat, "lng" to gz.lng, "gps" to true))
        ambs.lastRemind.get(20, TimeUnit.SECONDS)
        val items = r["items"].toList()
        val km = items.filter { it["available"].booleanValue() }.map { it["km"].intValue() }
        assertTrue(km.toSet().size >= 4, "different distances: $km")
        val exact = items.filter { it["available"].booleanValue() && !it.has("kmApprox") }.map { it["km"].intValue() }
        assertEquals(exact.sorted(), exact, "nearest first")
        val near = items.first { it["id"].textValue() == r["nearestId"].textValue() }
        assertTrue(near["available"].booleanValue() && !near["types"].all { it.textValue() == "freezer" })
        assertTrue(items.first { it["id"].textValue() == "demo-svc-amb-freezer" }["kmApprox"].booleanValue())
        assertEquals(listOf(DA to "demo-svc-amb-mini"), pushes().map { it["uid"] as String to mapper.readTree(it["data"] as String)["ambulanceId"].textValue() })
        val mini = ok(DA, "ambulance_mine")["items"].first { it["id"].textValue() == "demo-svc-amb-mini" }
        val left = Math.round((mini["availableUntil"].longValue() - now()) / MIN.toDouble())
        assertTrue(left in 26..30, "$left minutes left")
    }

    @Test
    fun `demo fares - valid charts; the demo patient finds cars to Dhaka within 5000, the demo driver offers, agreed`() {
        AmbClock.setDhaka("2026-10-19 12:00")
        seed()
        val cars = repo.approvedIn(GAZIPUR) + repo.query("status = 'pending'")
        val charts = cars.filter { it.id.startsWith("demo-") && it.chart != null }
        assertTrue(charts.size >= 6)
        for (a in charts) {
            val n = AmbFare.normalizeChart(json(a.chart!!.toMap()), Veh(GAZIPUR, a.upazilaId), places::facility)
            assertNull(n.error, a.id)
            assertEquals(a.chart, n.chart, "${a.id} unchanged by validation")
        }
        val c0 = com.lifolo.admin.kernel.DemoSeed
        val body = mapOf(
            "district" to GAZIPUR, "upazilaId" to 320, "lat" to c0.CENTER_LAT, "lng" to c0.CENTER_LNG, "gps" to true,
            "dest" to mapOf("kind" to "district", "district" to DHAKA), "maxFare" to 5000,
        )
        val c = ok(P, "ambreq_count", body)
        assertTrue(c["count"].intValue() >= 1, c.toString())
        assertTrue(c["hospitals"].any { it.textValue().contains("তাজ উদ্দীন") })
        val r = ok(P, "ambreq_create", body + mapOf("pickup" to "ডেমো: শিববাড়ি মোড়", "phone" to "01700000101"))
        val id = r["id"].textValue()
        assertTrue(sentTo(id).all { it.startsWith("demo-") })
        val card = driverList(DA).first { it["id"].textValue() == id }
        val fare = card["suggestedFare"].intValue()
        assertTrue(fare in 4000..5000, fare.toString())
        ok(DA, "ambreq_accept", mapOf("id" to id, "fare" to fare))
        val q = ok(P, "ambreq_status", mapOf("id" to id))["request"]["quotes"]
        ok(P, "ambreq_agree", mapOf("id" to id, "ambulanceId" to q[0]["id"].textValue()))
        val v = ok(P, "ambreq_status", mapOf("id" to id))["request"]
        assertEquals("accepted", v["status"].textValue())
        assertEquals(fare, v["agreedFare"].intValue())
    }

    @Test
    fun `demo request - the patient calls, the demo driver comes, done, rating`() {
        seed()
        val c0 = com.lifolo.admin.kernel.DemoSeed
        val r = ok(
            P, "ambreq_create",
            mapOf(
                "district" to GAZIPUR, "upazilaId" to 320, "lat" to c0.CENTER_LAT, "lng" to c0.CENTER_LNG, "gps" to true,
                "pickup" to "ডেমো: শিববাড়ি মোড়, জয়দেবপুর", "phone" to "01700000101",
            ),
        )
        val id = r["id"].textValue()
        assertTrue(sentTo(id).contains(DA))
        assertTrue(sentTo(id).all { it.startsWith("demo-") })
        assertEquals(1, driverList(DA).size)
        ok(DA, "ambreq_accept", mapOf("id" to id))
        val v = ok(P, "ambreq_status")["request"]
        assertEquals("accepted", v["status"].textValue())
        assertTrue(v["ambulance"]["name"].textValue().contains("ডেমো"))
        ok(DA, "ambreq_step", mapOf("id" to id, "step" to "done"))
        assertTrue(ok(P, "ambreq_status", mapOf("id" to id))["request"]["canRate"].booleanValue())
    }

    @Test
    fun `account deletion step - hide suspends the cars and closes requests; deleteNow removes cars, calls and ratings`() {
        seed()
        val step = deletionStep
        step.hide(DA)
        assertTrue(repo.ofOwner(DA, 10).all { it.status == "suspended" })
        assertEquals("approved", ambRow("demo-svc-amb-ac")["hidden_for_deletion"])
        val sumBefore = ambRow("demo-svc-amb-ac")["rating_sum"] as Int
        step.deleteNow(P, "patient@lifolo-demo.test")
        assertEquals(sumBefore - 5, ambRow("demo-svc-amb-ac")["rating_sum"], "the patient's rating taken out")
        assertTrue(row("SELECT 1 AS x FROM ambulance_calls WHERE uid = ?", P).isEmpty())
        step.deleteNow(DA, "ambulance@lifolo-demo.test")
        assertTrue(repo.ofOwner(DA, 10).isEmpty())
        assertNotNull(step)
    }

    @Autowired lateinit var deletionStep: AmbulanceDeletionStep
}
