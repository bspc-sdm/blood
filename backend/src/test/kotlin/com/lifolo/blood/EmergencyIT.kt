package com.lifolo.blood

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** emergency.js: admin appeals (create/waves/groups/close/list), donors coming/cancel/donated, closing expired ones */
class EmergencyIT : BloodTestBase() {
    private fun group(id: String, g: String) = row("SELECT * FROM emergency_groups WHERE emergency_id = ? AND blood_group = ?", id, g)

    private fun create(body: Map<String, Any?> = emptyMap()) = call(
        ADMIN, "admin_emergency_create",
        mapOf(
            "confirmed" to true, "district" to DIST, "hospitalName" to "মমেক জরুরি বিভাগ", "groups" to mapOf("A+" to 1, "O+" to 2),
            "radiusKm" to 10, "hours" to 6, "contactPhone" to "01712345678", "lat" to LAT, "lng" to LNG, "note" to "দ্রুত আসুন",
        ) + body,
    )

    @Test
    fun `admin creates an appeal, the first wave reaches nearby eligible donors of the open groups`() {
        admin()
        user("plain", emptyMap())
        fun north(km: Double) = LAT + km / 111
        donor("a1", "A+", lat = north(1.0))
        donor("a2", "A+", lat = north(2.0), extra = mapOf("lang" to "en"))
        donor("o1", "O+", lat = north(1.0))
        donor("bp", "B+", lat = north(1.0))
        donor("farA", "A+", lat = north(30.0))
        donor("recent", "O+", lat = north(1.0), extra = mapOf("last_donation_ms" to System.currentTimeMillis() - 5 * DAY))
        donor("demo-a", "A+", lat = north(1.0))
        assertEquals(403, call("plain", "admin_emergency_create", emptyMap()).status)
        assertEquals(400, call(ADMIN, "admin_emergency_create", mapOf("confirmed" to false)).status)
        assertEquals("অন্তত একটা গ্রুপে কত ব্যাগ লাগবে দিন", create(mapOf("groups" to mapOf("A+" to 0))).error)
        assertEquals("হাসপাতালের যোগাযোগের মোবাইল নম্বর দিন", create(mapOf("contactPhone" to "123")).error)
        val c = create()
        assertEquals(200, c.status, c.toString())
        val id = c.body["id"].textValue()
        val e = row("SELECT * FROM emergencies WHERE id = ?", id)
        assertEquals("active", e["status"])
        assertEquals(10, e["radius_km"])
        assertEquals("01712345678", e["contact_phone"])
        assertEquals(true, e["exact_location"])
        assertEquals("owner@example.com", e["created_by"])
        assertEquals(1, e["waves"])
        assertEquals(3, c.body["notified"].intValue())
        assertEquals(3, c.body["sent"].intValue())
        val told = jdbc.queryForList("SELECT uid FROM emergency_donors WHERE emergency_id = ? ORDER BY uid", String::class.java, id)
        assertEquals(listOf("a1", "a2", "o1"), told)
        assertEquals(2, group(id, "A+")["notified"])
        assertEquals(1, group(id, "O+")["notified"])
        val en = push.messages.last { it.uid == "a2" }.data
        assertEquals("Emergency: A+ blood needed", en["title"])
        assertEquals("মমেক জরুরি বিভাগ · about 2 km away · Lifolo team appeal — open to say you're coming", en["body"])
        assertEquals("emergency", en["type"])
        assertEquals(id, en["emergencyId"])
        // next wave: nobody new
        val more = call(ADMIN, "admin_emergency_more", mapOf("id" to id))
        assertEquals(0, more.body["sent"].intValue())
        // the panel list
        val list = call(ADMIN, "admin_emergencies")
        val item = list.body["items"].first { it["id"].textValue() == id }
        assertEquals(true, item["live"].booleanValue())
        assertEquals(1, item["groups"]["A+"]["need"].intValue())
        assertFalse(item["groups"]["A+"].has("adminClosed"))
        assertEquals(1L, row("SELECT count(*) AS n FROM admin_log WHERE action = 'admin_emergency_create' AND target = 'emergencies'")["n"])
    }

    @Test
    fun `donors - see the appeal of their district, coming fills a group, cancel reopens it, ended appeal asks did you give`() {
        admin()
        donor("d1", "O+")
        donor("d2", "O+")
        donor("bNeg", "B-")
        donor("young", "O+", extra = mapOf("birth_date_ms" to birth(2015)))
        val id = create(mapOf("groups" to mapOf("O+" to 1, "B-" to 1))).body["id"].textValue()
        val act = call("d1", "emergencies_active")
        assertEquals(200, act.status, act.toString())
        val v = act.body["items"].single()
        assertEquals(id, v["id"].textValue())
        assertEquals("O+", v["myGroup"].textValue())
        assertEquals("notified", v["myStatus"].textValue())
        assertEquals(true, v["canCome"].booleanValue())
        assertEquals(false, v["groups"]["O+"]["full"].booleanValue())
        // guest: the district's appeals (no own state)
        val g = call(null, "emergencies_active", mapOf("district" to DIST), ip = "3.3.3.3")
        assertEquals(id, g.body["items"][0]["id"].textValue())
        assertEquals("", g.body["items"][0]["myGroup"].textValue())
        assertEquals(0, call(null, "emergencies_active", mapOf("district" to "x"), ip = "3.3.3.3").body["items"].size())

        assertEquals("এখন আপনি রক্ত দিতে পারবেন না (বয়স, ওজন বা শেষ রক্তদানের সময়)", call("young", "emergency_respond", mapOf("id" to id, "op" to "coming")).error)
        assertEquals(200, call("d1", "emergency_respond", mapOf("id" to id, "op" to "coming")).status)
        assertEquals(1, group(id, "O+")["coming"])
        assertEquals(true, group(id, "O+")["closed"])
        assertEquals(200, call("d1", "emergency_respond", mapOf("id" to id, "op" to "coming")).status)
        assertEquals(1, group(id, "O+")["coming"])
        val full = call("d2", "emergency_respond", mapOf("id" to id, "op" to "coming"))
        assertEquals(409, full.status)
        assertEquals("আপনার গ্রুপের যথেষ্ট ডোনার আসছেন — ধন্যবাদ!", full.error)
        user("noGroup", emptyMap())
        assertEquals("এখানে আপনার গ্রুপের রক্ত দরকার নেই", call("noGroup", "emergency_respond", mapOf("id" to id, "op" to "coming")).error)
        assertEquals(200, call("young", "emergency_respond", mapOf("id" to id, "op" to "cancel")).status) // not coming: nothing
        // the B- donor comes
        assertEquals(200, call("bNeg", "emergency_respond", mapOf("id" to id, "op" to "coming")).status)
        assertEquals("coming", row("SELECT status FROM emergency_donors WHERE emergency_id = ? AND uid = 'bNeg'", id)["status"])
        // while live, "did not give" is refused
        assertEquals("\"আসতে পারছি না\" চাপুন", call("d1", "emergency_donated", mapOf("id" to id, "donated" to false)).error)
        // cancel: the group opens again
        assertEquals(200, call("d1", "emergency_respond", mapOf("id" to id, "op" to "cancel")).status)
        assertEquals(0, group(id, "O+")["coming"])
        assertEquals(false, group(id, "O+")["closed"])
        assertEquals("cancelled", row("SELECT status FROM emergency_donors WHERE emergency_id = ? AND uid = 'd1'", id)["status"])
        // admin closes a group: it stays closed when someone cancels
        assertEquals(200, call(ADMIN, "admin_emergency_group", mapOf("id" to id, "group" to "B-", "closed" to true)).status)
        assertEquals(true, group(id, "B-")["admin_closed"])
        assertEquals(200, call("bNeg", "emergency_respond", mapOf("id" to id, "op" to "cancel")).status)
        assertEquals(true, group(id, "B-")["closed"])
        assertEquals(true, call(ADMIN, "admin_emergencies").body["items"][0]["groups"]["B-"]["adminClosed"].booleanValue())
        assertEquals(400, call(ADMIN, "admin_emergency_group", mapOf("id" to id, "group" to "AB+")).status)
        assertEquals(404, call(ADMIN, "admin_emergency_group", mapOf("id" to "nope", "group" to "AB+")).status)

        // d2 comes, admin ends the appeal → d2 told; then asked "did you give?"
        assertEquals(200, call("d2", "emergency_respond", mapOf("id" to id, "op" to "coming")).status)
        val close = call(ADMIN, "admin_emergency_close", mapOf("id" to id))
        assertEquals(200, close.status)
        assertEquals(true, call(ADMIN, "admin_emergency_close", mapOf("id" to id)).body["unchanged"].booleanValue())
        assertTrue(inbox("d2").contains("জরুরি আহ্বান শেষ"))
        assertEquals("এই আহ্বান শেষ হয়ে গেছে", call("d1", "emergency_respond", mapOf("id" to id, "op" to "coming")).error)
        val ask = call("d2", "emergencies_active").body
        assertEquals(0, ask["items"].size())
        assertEquals(id, ask["ask"][0]["id"].textValue())
        // gave: the date is the appeal's day; a second answer changes nothing
        assertEquals(200, call("d2", "emergency_donated", mapOf("id" to id, "donated" to true)).status)
        assertEquals("donated", row("SELECT status FROM emergency_donors WHERE emergency_id = ? AND uid = 'd2'", id)["status"])
        assertEquals(today(), lastDonation("d2"))
        assertEquals(0, call("d2", "emergencies_active").body["ask"].size())
        assertEquals(200, call("d2", "emergency_donated", mapOf("id" to id, "donated" to true)).status)
        assertEquals("এই আহ্বানে আপনি \"আসছি\" বলেননি", call("d1", "emergency_donated", mapOf("id" to id, "donated" to true)).error)
    }

    @Test
    fun `expired appeals close when someone looks and by the daily job, coming donors told`() {
        admin()
        donor("d1", "O+")
        val id = create(mapOf("groups" to mapOf("O+" to 2))).body["id"].textValue()
        assertEquals(200, call("d1", "emergency_respond", mapOf("id" to id, "op" to "coming")).status)
        jdbc.update("UPDATE emergencies SET ends_at = now() - interval '1 minute' WHERE id = ?", id)
        val act = call("d1", "emergencies_active")
        assertEquals(0, act.body["items"].size())
        assertEquals(id, act.body["ask"][0]["id"].textValue())
        val e = row("SELECT status, closed_by FROM emergencies WHERE id = ?", id)
        assertEquals("closed", e["status"])
        assertEquals("expired", e["closed_by"])
        assertTrue(inbox("d1").contains("জরুরি আহ্বান শেষ"))
        // daily job: another one
        val id2 = create(mapOf("groups" to mapOf("O+" to 2))).body["id"].textValue()
        jdbc.update("UPDATE emergencies SET ends_at = now() - interval '1 minute' WHERE id = ?", id2)
        val r = get("/api/cron/daily", mapOf("Authorization" to "Bearer test-cron-secret"))
        assertEquals(200, r.status, r.toString())
        assertEquals(1, r.body["emergenciesClosed"].intValue())
        // demo appeals are hidden from real users and guests
        jdbc.update("UPDATE emergencies SET status = 'active', ends_at = now() + interval '1 hour', is_demo = TRUE WHERE id = ?", id2)
        assertEquals(0, call("d1", "emergencies_active").body["items"].size())
        assertEquals(0, call(null, "emergencies_active", mapOf("district" to DIST), ip = "4.4.4.4").body["items"].size())
        assertEquals(403, call("d1", "emergency_respond", mapOf("id" to id2, "op" to "coming")).status)
    }
}
