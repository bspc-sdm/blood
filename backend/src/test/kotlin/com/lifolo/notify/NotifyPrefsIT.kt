package com.lifolo.notify

import com.lifolo.blood.BloodTestBase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/** notify_prefs_get / notify_prefs_set and their enforcement in PushService.send (the one choke point) */
class NotifyPrefsIT : BloodTestBase() {
    @Autowired lateinit var pushService: PushService

    private fun ok(uid: String, action: String, body: Map<String, Any?> = emptyMap()) =
        call(uid, action, body).also { assertEquals(200, it.status, "$action: $it") }.body

    private fun withToken(uid: String) {
        user(uid, mapOf("name" to "ক $uid", "lang" to "bn"))
        jdbc.update("INSERT INTO device_tokens (token, uid) VALUES (?, ?)", "tok-$uid", uid)
    }

    private fun inboxCount(uid: String): Int = jdbc.queryForObject("SELECT count(*) FROM inbox_items WHERE uid = ?", Int::class.java, uid) ?: 0

    /** one push of each category: data as the call sites build it */
    private val samples: Map<String, Map<String, Any?>> = linkedMapOf(
        "blood_nearby" to mapOf("type" to "request", "requestId" to "r1", "cat" to "blood_nearby"),
        "blood_emergency" to mapOf("type" to "emergency", "emergencyId" to "e1"),
        "my_request" to mapOf("type" to "response", "requestId" to "r1"),
        "appointments" to mapOf("type" to "appointment", "role" to "patient"),
        "rx" to mapOf("type" to "rx", "id" to "x1", "rxId" to "x1"),
        "ambulance" to mapOf("type" to "amb_req_mine", "reqId" to "a1"),
        "community" to mapOf("type" to "answer", "questionId" to "q1"),
        "business" to mapOf("type" to "services", "kind" to "pharmacy"),
        "calls" to mapOf("type" to "call_missed", "callId" to "c1"),
        "account" to mapOf("type" to "support", "ticketId" to "t1"),
    )

    @Test
    fun `get and set - defaults, merge, blood_nearby is the profile notify, validation, login`() {
        user("np1", mapOf("name" to "ক"))
        val d = ok("np1", "notify_prefs_get")
        assertTrue(d["ok"].booleanValue())
        assertTrue(d["all"].booleanValue())
        assertEquals(NotifyCats.SETTABLE, d["cats"].fieldNames().asSequence().toList())
        assertTrue(NotifyCats.SETTABLE.all { d["cats"][it].booleanValue() })

        val s = ok("np1", "notify_prefs_set", mapOf("cats" to mapOf("rx" to false, "community" to false)))
        assertFalse(s["cats"]["rx"].booleanValue())
        assertFalse(s["cats"]["community"].booleanValue())
        assertTrue(s["cats"]["ambulance"].booleanValue())
        assertTrue(s["all"].booleanValue())
        // merge: only what is sent changes
        val s2 = ok("np1", "notify_prefs_set", mapOf("all" to false, "cats" to mapOf("rx" to true)))
        assertFalse(s2["all"].booleanValue())
        assertTrue(s2["cats"]["rx"].booleanValue())
        assertFalse(s2["cats"]["community"].booleanValue())
        assertEquals(s2, ok("np1", "notify_prefs_get"))

        // blood_nearby = profile notify, both ways
        ok("np1", "notify_prefs_set", mapOf("cats" to mapOf("blood_nearby" to false)))
        assertEquals(false, row("SELECT notify FROM user_profiles WHERE uid = 'np1'")["notify"])
        assertFalse(ok("np1", "profile_get")["profile"]["notify"].booleanValue())
        ok("np1", "profile_update", mapOf("set" to mapOf("notify" to true)))
        assertTrue(ok("np1", "notify_prefs_get")["cats"]["blood_nearby"].booleanValue())

        // bad input: unknown category, "account" (always on), not a boolean, cats not an object
        for (bad in listOf(
            mapOf("cats" to mapOf("account" to false)), mapOf("cats" to mapOf("zzz" to false)), mapOf("cats" to mapOf("rx" to "no")),
            mapOf("all" to "yes"), mapOf("cats" to listOf("rx")),
        )) {
            assertEquals(400, call("np1", "notify_prefs_set", bad).status, bad.toString())
        }
        assertEquals(401, call(null, "notify_prefs_get").status)
        assertEquals(401, call(null, "notify_prefs_set", mapOf("all" to false)).status)
    }

    @Test
    fun `each category off - no phone push, the bell list still gets it, cat in the payload`() {
        withToken("c1")
        for ((cat, data) in samples) {
            push.messages.clear()
            val before = inboxCount("c1")
            pushService.send(listOf(PushItem("c1", "t-$cat", "b", data)))
            assertEquals(before + 1, inboxCount("c1"), cat)
            assertEquals(listOf(cat), push.messages.map { it.data["cat"] }, "on: $cat")
        }
        val inboxCat = jdbc.queryForList("SELECT data->>'cat' FROM inbox_items WHERE uid = 'c1'", String::class.java).toSet()
        assertEquals(samples.keys, inboxCat)
        for (cat in NotifyCats.SETTABLE) {
            ok("c1", "notify_prefs_set", mapOf("cats" to mapOf(cat to false)))
            for ((c, data) in samples) {
                push.messages.clear()
                val before = inboxCount("c1")
                pushService.send(listOf(PushItem("c1", "t-$c", "b", data)))
                assertEquals(before + 1, inboxCount("c1"), "inbox always: $c with $cat off")
                assertEquals(if (c == cat) 0 else 1, push.messages.size, "$c with $cat off")
            }
            ok("c1", "notify_prefs_set", mapOf("cats" to mapOf(cat to true)))
        }
    }

    @Test
    fun `master switch off - only account pushes reach the phone, others only the bell, others in the batch unaffected`() {
        withToken("m1")
        withToken("m2")
        ok("m1", "notify_prefs_set", mapOf("all" to false))
        push.messages.clear()
        // distinct titles: the same text to the same phone goes once (PushService dedupe)
        pushService.send(samples.entries.flatMap { (c, d) -> listOf(PushItem("m1", "x-$c", "", d), PushItem("m2", "x-$c", "", d)) })
        assertEquals(listOf("account"), push.messages.filter { it.uid == "m1" }.map { it.data["cat"] })
        assertEquals(samples.size, push.messages.count { it.uid == "m2" })
        assertEquals(samples.size, inboxCount("m1"))
        // account always, even with the category list all off
        ok("m1", "notify_prefs_set", mapOf("all" to true, "cats" to NotifyCats.SETTABLE.associateWith { false }))
        push.messages.clear()
        pushService.send(samples.entries.map { (c, d) -> PushItem("m1", "y-$c", "", d) })
        assertEquals(listOf("account"), push.messages.map { it.data["cat"] })
    }

    @Test
    fun `caregiver - can't change the patient's settings, their copy of patient news follows their own settings`() {
        withToken("pat")
        withToken("cg")
        jdbc.update("INSERT INTO care_links (patient_uid, caretaker_uid, status) VALUES ('pat', 'cg', 'active')")
        for (action in listOf("notify_prefs_set", "notify_prefs_get")) {
            assertEquals(403, call("cg", action, mapOf("asUid" to "pat", "all" to false)).status, action)
        }
        assertTrue(ok("pat", "notify_prefs_get")["all"].booleanValue())
        assertTrue(ok("cg", "notify_prefs_get")["all"].booleanValue())

        ok("cg", "notify_prefs_set", mapOf("cats" to mapOf("rx" to false)))
        push.messages.clear()
        pushService.pushPatient("pat", "New prescription", "নতুন প্রেসক্রিপশন", "", mapOf("type" to "rx", "id" to "x9", "rxId" to "x9"))
        assertEquals(listOf("pat"), push.messages.map { it.uid })
        val bell = jdbc.queryForList("SELECT data->>'type' AS t, data->>'cat' AS c FROM inbox_items WHERE uid = 'cg'").single()
        assertEquals("care", bell["t"])
        assertEquals("rx", bell["c"])
        // appointments news for the patient is not rx: the caregiver gets it
        push.messages.clear()
        pushService.pushPatient("pat", "2 before you", "আপনার আগে ২ জন", "", mapOf("type" to "appointment", "role" to "patient", "id" to "a1"))
        assertEquals(setOf("pat", "cg"), push.messages.map { it.uid }.toSet())
        assertEquals("appointments", push.messages.single { it.uid == "cg" }.data["cat"])
    }

    @Test
    fun `nearby requests - the old switch (profile notify) and the new settings really stop them`() {
        user("req", mapOf("name" to "রোগী", "phone" to "01711111111"))
        fun north(km: Double) = LAT + km / 111
        donor("on", "A+", lat = north(1.0))
        donor("oldoff", "A+", lat = north(1.0), extra = mapOf("notify" to false))
        donor("catoff", "A+", lat = north(1.2))
        donor("alloff", "A+", lat = north(1.3))
        donor("emoff", "A+", lat = north(1.4))
        ok("catoff", "notify_prefs_set", mapOf("cats" to mapOf("blood_nearby" to false)))
        ok("alloff", "notify_prefs_set", mapOf("all" to false))
        ok("emoff", "notify_prefs_set", mapOf("cats" to mapOf("blood_emergency" to false)))
        request("r1", "req", group = "A+", accepted = 0)
        val n = http("/api/notify-request", "req", mapOf("requestId" to "r1"))
        assertEquals(200, n.status, n.toString())
        assertEquals(setOf("on", "emoff"), push.messages.mapNotNull { it.uid }.toSet())
        assertEquals("blood_nearby", pushedTo("on").single()["cat"])
        // the switched-off donors are not even picked (no "notified" row, no daily count spent)
        assertEquals(
            listOf("emoff", "on"),
            jdbc.queryForList("SELECT uid FROM blood_request_notified WHERE request_id = 'r1' ORDER BY uid", String::class.java),
        )

        // emergency appeals: their own switch; the nearby switch does not matter any more
        admin()
        push.messages.clear()
        val c = call(
            ADMIN, "admin_emergency_create",
            mapOf(
                "confirmed" to true, "district" to DIST, "hospitalName" to "মমেক জরুরি বিভাগ", "groups" to mapOf("A+" to 3),
                "radiusKm" to 10, "hours" to 6, "contactPhone" to "01712345678", "lat" to LAT, "lng" to LNG,
            ),
        )
        assertEquals(200, c.status, c.toString())
        assertEquals(setOf("on", "oldoff", "catoff"), push.messages.mapNotNull { it.uid }.toSet())
        assertTrue(push.messages.all { it.data["cat"] == "blood_emergency" })
    }
}
