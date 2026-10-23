package com.lifolo.appointments

import com.lifolo.features.Features
import com.lifolo.support.setFeature
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Online chambers behind the `online_chamber` feature switch (docs/FEATURES.md): with the switch off every online
 * action answers 404 `feature_off`, the online list is empty, the online pushes and the "doctor never joined" job wait;
 * pilot users get it all; a doctor keeps an online chamber saved while it was on.
 */
class OnlineChamberSwitchIT : P2TestBase() {
    @Autowired lateinit var features: Features

    @Autowired lateinit var unserved: OnlineUnserved

    @Autowired lateinit var reminders: AppointmentReminders

    private val day = 24 * 3600 * 1000L
    private val hour = 3600 * 1000L
    private val doc = "swDoc"
    private val pat = "swPat"

    @AfterEach
    fun allOnAgain() {
        features.allOn = true
    }

    private fun online() = mapOf(
        "mode" to "online", "days" to listOf(0, 1, 2, 3, 4, 5, 6), "start" to "18:00", "end" to "21:00", "appointments" to true,
        "fee" to "৪০০ টাকা", "bkashNumber" to "01712345678",
    )

    private fun save(uid: String, chambers: List<Any?>) = call(uid, "provider_save", mapOf("chambers" to chambers, "specialties" to listOf("medicine"), "appointments" to true))

    private fun bookBody(date: Long) = mapOf("providerUid" to doc, "date" to date, "chamberIndex" to 0, "note" to "জ্বর")

    /** a doctor with only an online chamber and one booked (confirmed) appointment, made while everything was on */
    private fun seed(date: Long = today() + day): String {
        clock.fixedMs = dhaka("2026-10-19 10:00")
        person(doc, "ডা. সুইচ")
        badge(doc, "doctor", "ডা. সুইচ")
        person(pat, "রোগী সুইচ")
        assertEquals(200, save(doc, listOf(online())).status)
        val id = ok(pat, "book", bookBody(date))["id"].textValue()
        ok(doc, "appointment_update", mapOf("id" to id, "op" to "confirm"))
        return id
    }

    private fun assertOff(r: Res) {
        assertEquals(404, r.status, r.toString())
        assertEquals("feature_off", r.code)
        assertEquals("এই সুবিধা এখনো চালু হয়নি", r.error)
    }

    @Test
    fun `off - every online action is feature_off, the online list is empty, a saved chamber stays editable`() {
        val id = seed()
        features.allOn = false
        assertOff(call(pat, "book", bookBody(today() + 2 * day)))
        assertOff(call(pat, "appt_pay_claim", mapOf("appointmentId" to id, "payRef" to "AB12CD34EF")))
        assertOff(call(doc, "appt_pay_confirm", mapOf("checks" to mapOf("trxId" to true, "amount" to true, "last4" to true), "appointmentId" to id)))
        assertOff(call(doc, "appt_pay_reject", mapOf("appointmentId" to id)))
        assertOff(call(pat, "appt_report", mapOf("appointmentId" to id, "reason" to "x")))
        assertEquals("This feature is not available yet", call(pat, "appt_pay_claim", mapOf("appointmentId" to id), lang = "en").error)
        // the doctor list: no online list, an online-only doctor is not shown
        assertEquals(0, ok(null, "providers", mapOf("type" to "doctor", "online" to true, "homeVisit" to false))["items"].size())
        assertFalse(ok(pat, "providers", mapOf("type" to "doctor", "homeVisit" to false))["items"].any { it["uid"].textValue() == doc })
        // the doctor can still save (the online chamber he has stays); a new doctor cannot add one
        assertEquals(200, save(doc, listOf(online())).status)
        person("swDoc2", "ডা. নতুন")
        badge("swDoc2", "doctor", "ডা. নতুন")
        assertOff(save("swDoc2", listOf(online())))
        // old appointments are still listed (and can be cancelled)
        assertTrue(ok(pat, "my_appointments")["items"].any { it["id"].textValue() == id })
    }

    @Test
    fun `pilot - the doctor and the patient in the pilot list get everything`() {
        val id = seed()
        features.allOn = false
        setFeature(jdbc, features, Features.ONLINE_CHAMBER, Features.PILOT, listOf(doc, pat))
        assertEquals(200, call(pat, "appt_pay_claim", mapOf("appointmentId" to id, "payRef" to "AB12CD34EF")).status)
        assertEquals(200, call(doc, "appt_pay_confirm", mapOf("checks" to mapOf("trxId" to true, "amount" to true, "last4" to true), "appointmentId" to id)).status)
        assertEquals(1, ok(pat, "providers", mapOf("type" to "doctor", "online" to true, "homeVisit" to false))["items"].size())
        assertEquals(200, call(pat, "book", bookBody(today() + 2 * day)).status)
        // a patient outside the pilot: not even the list
        person("swOther", "অন্য")
        assertEquals(0, ok("swOther", "providers", mapOf("type" to "doctor", "online" to true, "homeVisit" to false))["items"].size())
        assertOff(call("swOther", "book", bookBody(today() + 2 * day)))
        // a doctor outside the pilot cannot be booked online even by a pilot patient
        setFeature(jdbc, features, Features.ONLINE_CHAMBER, Features.PILOT, listOf(pat))
        assertEquals(404, call(pat, "book", bookBody(today() + 3 * day)).status)
    }

    @Test
    fun `off - no online reminder, no confirm push, the never-joined job waits`() {
        val id = seed(date = dhaka("2026-10-19 10:00").let { today(it) })
        ok(pat, "appt_pay_claim", mapOf("appointmentId" to id, "payRef" to "AB12CD34EF"))
        ok(doc, "appt_pay_confirm", mapOf("checks" to mapOf("trxId" to true, "amount" to true, "last4" to true), "appointmentId" to id))
        features.allOn = false
        jdbc.execute("DELETE FROM inbox_items")
        reminders.dailyReminders(clock.now())
        assertTrue(pushesTo(pat, "অনলাইন").isEmpty(), "no online reminder while off")
        // the doctor never joined: nothing while off, not marked either …
        val after = today() + 21 * hour - 6 * hour + 60_000
        assertEquals(0, unserved.run(after))
        assertTrue(jdbc.queryForObject("SELECT unserved_notified_at IS NULL FROM appointments WHERE id = ?", Boolean::class.java, id)!!)
        // … and told once the switch is on for the patient
        setFeature(jdbc, features, Features.ONLINE_CHAMBER, Features.PILOT, listOf(pat))
        assertEquals(1, unserved.run(after + hour))
        // a second confirmation of another appointment while off for the patient: no "confirmed" push
        features.allOn = true
        val id2 = ok(pat, "book", bookBody(today() + 2 * day))["id"].textValue()
        features.allOn = false
        setFeature(jdbc, features, Features.ONLINE_CHAMBER, Features.PILOT, listOf(doc))
        jdbc.execute("DELETE FROM inbox_items")
        assertEquals(200, call(doc, "appointment_update", mapOf("id" to id2, "op" to "confirm")).status)
        assertTrue(pushesTo(pat, "নিশ্চিত").isEmpty())
    }
}
