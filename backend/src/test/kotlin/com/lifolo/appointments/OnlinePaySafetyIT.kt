package com.lifolo.appointments

import com.lifolo.contracts.HospitalDirectory
import com.lifolo.features.Features
import com.lifolo.support.setFeature
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Online chambers — the security review (docs/test-plan/CALLS-SECURITY-REVIEW.md):
 * R6 note without phone numbers and not public, demo online chambers only with the online_chamber switch, bKash number change logged + pushed + 24 h
 * cool-off; R7 TrxID format, once per doctor, 5 claims per hour; R8 pay log, a reason to cancel a paid appointment,
 * "the doctor never joined" after the window, appt_report and the admin list.
 */
class OnlinePaySafetyIT : P2TestBase() {
    @Autowired lateinit var hospitals: HospitalDirectory

    @Autowired lateinit var features: Features

    @Autowired lateinit var unserved: OnlineUnserved

    private val day = 24 * 3600 * 1000L
    private val hour = 3600 * 1000L
    private val doc = "osDoc"
    private val pat = "osPat"
    private val pat2 = "osPat2"
    private val x = "osStranger"

    @AfterEach
    fun switchBack() {
        features.allOn = true
    }

    private fun online(extra: Map<String, Any?> = emptyMap()) = mapOf(
        "mode" to "online", "days" to listOf(0, 1, 2, 3, 4, 5, 6), "start" to "18:00", "end" to "21:00", "appointments" to true,
        "fee" to "৪০০ টাকা", "bkashNumber" to "01712345678", "note" to "রিপোর্ট তৈরি রাখুন",
    ) + extra

    private fun save(uid: String, chambers: List<Any?>) = call(uid, "provider_save", mapOf("chambers" to chambers, "specialties" to listOf("medicine"), "appointments" to true))

    private fun seed() {
        clock.fixedMs = dhaka("2026-10-19 10:00")
        person(doc, "ডা. নিরাপদ")
        badge(doc, "doctor", "ডা. নিরাপদ")
        person(pat, "রোগী এক")
        person(pat2, "রোগী দুই")
        person(x, "অন্য কেউ")
        assertEquals(200, save(doc, listOf(online())).status)
    }

    private fun book(uid: String = pat, date: Long = today() + day): String =
        ok(uid, "book", mapOf("providerUid" to doc, "date" to date, "chamberIndex" to 0, "note" to "জ্বর"))["id"].textValue()

    private fun appt(id: String) = row("SELECT * FROM appointments WHERE id = ?", id)

    @Test
    fun `R6 - note without phone numbers, never public, demo online chambers off`() {
        seed()
        for (n in listOf("ফি আগে পাঠান ০১৭১২-৩৪৫ ৬৭৮", "call 01712345678", "+880 1712 345678", "বিকাশ 01812.345.678", "1234567890")) {
            val r = save(doc, listOf(online(mapOf("note" to n))))
            assertEquals(400, r.status, "$n: $r")
            assertEquals("নোটে ফোন বা বিকাশ নম্বর লেখা যাবে না", r.error)
        }
        assertEquals(200, save(doc, listOf(online(mapOf("note" to "সকাল ১০টা – রাত ৮টা, রুম ১২")))).status)
        // the note: the doctor and booked patients, not the public list
        assertEquals("সকাল ১০টা – রাত ৮টা, রুম ১২", ok(doc, "provider_me")["provider"]["chambers"][0]["note"].textValue())
        val pub = ok(null, "providers", mapOf("type" to "doctor", "homeVisit" to false))["items"].first { it["uid"].textValue() == doc }
        assertFalse(pub["chambers"][0].has("note"))
        val id = book()
        assertEquals("সকাল ১০টা – রাত ৮টা, রুম ১২", ok(pat, "my_appointments")["items"].first { it["id"].textValue() == id }["onlineNote"].textValue())

        // demo accounts: online chambers exactly when the online_chamber switch is on for them (pilot + demo, or on)
        person("demo-osdoc", "ডা. ডেমো")
        badge("demo-osdoc", "doctor", "ডা. ডেমো")
        person("demo-ospat", "ডেমো রোগী")
        features.allOn = false
        setFeature(jdbc, features, Features.ONLINE_CHAMBER, Features.PILOT, listOf(doc))
        val r = save("demo-osdoc", listOf(online()))
        assertEquals(404, r.status)
        assertEquals("feature_off", r.code)
        assertEquals("এই সুবিধা এখনো চালু হয়নি", r.error)
        assertEquals(200, save(doc, listOf(online())).status, "a pilot doctor")
        setFeature(jdbc, features, Features.ONLINE_CHAMBER, Features.PILOT, listOf(doc), demo = true)
        assertEquals(200, save("demo-osdoc", listOf(online())).status)
        setFeature(jdbc, features, Features.ONLINE_CHAMBER, Features.PILOT, listOf(doc), demo = false)
        val b = call("demo-ospat", "book", mapOf("providerUid" to "demo-osdoc", "date" to today() + day, "chamberIndex" to 0, "note" to "x"))
        assertEquals(404, b.status)
        assertEquals("feature_off", b.code)
    }

    @Test
    fun `R6 - a bKash number change is logged, pushed, and reaches new bookings after 24 h`() {
        seed()
        val first = book()
        assertEquals(listOf("—", "678"), jdbc.queryForList("SELECT coalesce(old_last3, '—') AS o, new_last3 FROM bkash_number_log WHERE provider_uid = ?", doc).single().let { listOf(it["o"], it["new_last3"]) })
        val t0 = clock.now()
        assertEquals(200, save(doc, listOf(online(mapOf("bkashNumber" to "01899999111")))).status)
        val log = jdbc.queryForList("SELECT old_last3, new_last3, by_uid FROM bkash_number_log WHERE provider_uid = ? ORDER BY id", doc)
        assertEquals(listOf("678", "111", doc), log.last().let { listOf(it["old_last3"], it["new_last3"], it["by_uid"]) })
        val pushed = push.messages.filter { it.uid == doc && it.data["title"] == "আপনার বিকাশ নম্বর বদলানো হয়েছে" }
        assertEquals(2, pushed.size, "set and changed")
        assertEquals("account", pushed.last().data["cat"], "always on")
        assertEquals("…৬৭৮ → …১১১", pushed.last().data["body"], "last 3 digits only (Bangla digits for a Bangla reader)")
        // the doctor sees the new number, the one patients get now, and until when
        val oc = ok(doc, "provider_me")["provider"]["chambers"][0]
        assertEquals(listOf("01899999111", "01712345678", (t0 + day).toString()), listOf(oc["bkashNumber"].textValue(), oc["bkashActive"].textValue(), oc["bkashPendingUntil"].asText()))
        // within 24 h: new bookings get the earlier number; booked ones keep theirs
        val during = book(pat2)
        assertEquals("01712345678", appt(during)["bkash_number"])
        assertEquals("01712345678", appt(first)["bkash_number"])
        // saving again (same number) changes nothing
        assertEquals(200, save(doc, listOf(online(mapOf("bkashNumber" to "01899999111", "note" to "নতুন নোট")))).status)
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM bkash_number_log WHERE provider_uid = ?", Int::class.java, doc))
        // after 24 h: the new number
        clock.fixedMs = t0 + day + hour
        val after = book(pat, today() + day)
        assertEquals("01899999111", appt(after)["bkash_number"])
        assertEquals("01712345678", appt(first)["bkash_number"])
        val oc2 = ok(doc, "provider_me")["provider"]["chambers"][0]
        assertEquals(listOf("01899999111", "true"), listOf(oc2["bkashActive"].textValue(), oc2["bkashPendingUntil"].isNull.toString()))
    }

    @Test
    fun `R7 - TrxID format, once per doctor, 5 claims an hour`() {
        seed()
        val id = book()
        val id2 = book(pat2)
        assertEquals(400, call(pat, "appt_pay_claim", mapOf("appointmentId" to id, "payRef" to "AB12CD34E")).status, "9 characters")
        assertEquals(400, call(pat, "appt_pay_claim", mapOf("appointmentId" to id, "payRef" to "AB12CD34EF1")).status, "11 characters")
        ok(pat, "appt_pay_claim", mapOf("appointmentId" to id, "payRef" to "ab12cd34ef"))
        val used = call(pat2, "appt_pay_claim", mapOf("appointmentId" to id2, "payRef" to "AB12CD34EF"))
        assertEquals(409, used.status)
        assertEquals("pay_ref_used", used.code)
        assertEquals("unpaid", appt(id2)["pay_state"])
        assertEquals("AB12CD34EF", ok(pat, "appt_pay_claim", mapOf("appointmentId" to id, "payRef" to "AB12CD34EF"))["payRef"].textValue(), "the same appointment again is fine")
        // the doctor sees the claimed TrxID and when it was claimed; nothing is marked paid by itself
        val d = ok(doc, "my_appointments")["items"].first { it["id"].textValue() == id }
        assertEquals(listOf("claimed", "AB12CD34EF", clock.now().toString()), listOf(d["payState"].textValue(), d["payRef"].textValue(), d["payClaimedAt"].asText()))

        // 5 claims per appointment per hour (id2 used 1 above)
        for (i in 2..5) assertEquals(200, call(pat2, "appt_pay_claim", mapOf("appointmentId" to id2)).status, "claim $i")
        val rate = call(pat2, "appt_pay_claim", mapOf("appointmentId" to id2, "payRef" to "ZZ12CD34EF"))
        assertEquals(429, rate.status)
        assertEquals("pay_claim_rate", rate.code)
        assertNull(appt(id2)["pay_ref"])
        clock.fixedMs = clock.now() + hour + 60_000
        assertEquals("ZZ12CD34EF", ok(pat2, "appt_pay_claim", mapOf("appointmentId" to id2, "payRef" to "ZZ12CD34EF"))["payRef"].textValue())
    }

    @Test
    fun `R8 - pay log, a reason to cancel after the fee, the doctor never joined, appt_report and the admins`() {
        seed()
        val id = book()
        ok(doc, "appointment_update", mapOf("id" to id, "op" to "confirm"))
        ok(pat, "appt_pay_claim", mapOf("appointmentId" to id, "payRef" to "AB12CD34EF"))
        ok(doc, "appt_pay_reject", mapOf("appointmentId" to id, "reason" to "পাইনি"))
        ok(pat, "appt_pay_claim", mapOf("appointmentId" to id, "payRef" to "AB12CD34EF"))
        ok(doc, "appt_pay_confirm", mapOf("appointmentId" to id))
        val log = jdbc.queryForList("SELECT by_uid, from_state, to_state, pay_ref, note FROM appt_pay_log WHERE appointment_id = ? ORDER BY id", id)
        assertEquals(
            listOf("$pat unpaid→claimed AB12CD34EF", "$doc claimed→unpaid AB12CD34EF", "$pat unpaid→claimed AB12CD34EF", "$doc claimed→paid AB12CD34EF"),
            log.map { "${it["by_uid"]} ${it["from_state"]}→${it["to_state"]} ${it["pay_ref"]}" },
        )
        assertEquals("পাইনি", log[1]["note"])

        // the window (18:00 + 3 h) passes, the doctor never joined: the patient is told once, the admins get a report
        val visit = today() + day
        assertEquals(0, unserved.run(visit + 20 * hour - 6 * hour), "20:00 — still inside the window")
        val after = visit + 21 * hour - 6 * hour + 60_000
        assertEquals(1, unserved.run(after))
        assertEquals(0, unserved.run(after + hour), "once")
        assertEquals(1, pushesTo(pat, "ডাক্তার যোগ দেননি").size)
        assertEquals(listOf("system"), jdbc.queryForList("SELECT reporter_role FROM appt_reports WHERE appointment_id = ?", String::class.java, id))
        // a doctor who joined: nothing
        val id2 = book(pat2)
        ok(doc, "appointment_update", mapOf("id" to id2, "op" to "confirm"))
        ok(doc, "appt_pay_confirm", mapOf("appointmentId" to id2))
        jdbc.update("UPDATE appointments SET doctor_joined_at = now() WHERE id = ?", id2)
        assertEquals(0, unserved.run(after + 2 * hour))

        // cancelling an appointment the patient paid / claimed: a reason, the patient told how to get the fee back
        val id3 = book(date = today() + 2 * day)
        ok(pat, "appt_pay_claim", mapOf("appointmentId" to id3, "payRef" to "CC12CD34EF"))
        val noReason = call(doc, "appointment_update", mapOf("id" to id3, "op" to "decline"))
        assertEquals(400, noReason.status)
        assertEquals("reason_required", noReason.code)
        assertEquals("pending", appt(id3)["status"])
        ok(doc, "appointment_update", mapOf("id" to id3, "op" to "decline", "providerNote" to "সেদিন থাকব না"))
        assertEquals(1, pushesTo(pat, "টাকা ফেরতের জন্য ডাক্তারের সাথে যোগাযোগ করুন").size)
        assertTrue(jdbc.queryForList("SELECT reason FROM appt_reports WHERE appointment_id = ? AND reporter_role = 'system'", String::class.java, id3).single().contains("সেদিন থাকব না"))
        assertTrue(jdbc.queryForList("SELECT note FROM appt_pay_log WHERE appointment_id = ? ORDER BY id DESC LIMIT 1", String::class.java, id3).single().startsWith("declined"))
        // the patient cancelling needs no reason
        val id4 = book(pat2, today() + 3 * day)
        ok(pat2, "appt_pay_claim", mapOf("appointmentId" to id4, "payRef" to "DD12CD34EF"))
        ok(pat2, "appointment_update", mapOf("id" to id4, "op" to "cancel"))

        // appt_report: the patient or the doctor side; one per person per appointment
        assertEquals(404, call(x, "appt_report", mapOf("appointmentId" to id, "reason" to "x")).status)
        assertEquals(400, call(pat, "appt_report", mapOf("appointmentId" to id)).status)
        val rep = ok(pat, "appt_report", mapOf("appointmentId" to id, "reason" to "ফি দিয়েছি, ডাক্তার আসেননি"))
        ok(pat, "appt_report", mapOf("appointmentId" to id, "reason" to "ফি দিয়েছি, ডাক্তার আসেননি (আবার)"))
        ok(doc, "appt_report", mapOf("appointmentId" to id, "reason" to "রোগী ফোন ধরেননি"))
        assertEquals(listOf("doctor", "patient", "system"), jdbc.queryForList("SELECT reporter_role FROM appt_reports WHERE appointment_id = ? ORDER BY reporter_role", String::class.java, id))

        // the admins: open reports with the appointment, its pay log and the doctor's bKash changes
        assertEquals(403, call(pat, "admin_appt_reports").status)
        user("owner1", emptyMap(), email = "owner@example.com")
        val items = ok("owner1", "admin_appt_reports")["items"]
        val mine = items.first { it["id"].textValue() == rep["id"].textValue() }
        assertEquals(listOf("patient", "ফি দিয়েছি, ডাক্তার আসেননি (আবার)", "paid", "AB12CD34EF"), listOf(mine["reporterRole"].textValue(), mine["reason"].textValue(), mine["appointment"]["payState"].textValue(), mine["appointment"]["payRef"].textValue()))
        assertEquals(4, mine["payLog"].size())
        assertEquals(1, mine["bkashChanges"].size())
        ok("owner1", "admin_appt_report_set", mapOf("id" to rep["id"].textValue(), "status" to "resolved"))
        assertTrue(ok("owner1", "admin_appt_reports")["items"].none { it["id"].textValue() == rep["id"].textValue() })
        assertEquals(1, ok("owner1", "admin_appt_reports", mapOf("status" to "resolved"))["items"].size())
        assertEquals(404, call("owner1", "admin_appt_report_set", mapOf("id" to "nope", "status" to "resolved")).status)
    }
}
