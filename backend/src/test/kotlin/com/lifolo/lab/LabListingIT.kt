package com.lifolo.lab

import com.lifolo.jobs.DailyJobRunner
import com.lifolo.rx.RxLabTest
import com.lifolo.rx.RxRules
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.sql.Timestamp

/**
 * The lab listing (lab_save → admin review → lab_me, lab_delete; no Node unit test — e2e covered it), the area list,
 * privacy-gaps.test.js 1) and 3), and the daily follow-up/refill reminders.
 */
class LabListingIT : RxLabTest() {
    @Autowired lateinit var daily: DailyJobRunner

    private val jpeg = "/9j/4AAQSkZJRgABAQ"

    @Test
    fun `a lab registers, the admin approves, it edits and goes away`() {
        person("lab1", "মালিক")
        person("adm", "অ্যাডমিন", email = "owner@example.com")
        val body = mapOf(
            "name" to "সিটি ডায়াগনস্টিক", "district" to dist, "upazilaId" to 466, "address" to "চরপাড়া মোড়", "licenseNo" to "DGHS-77",
            "phone" to "01711-000777", "termsAccepted" to true, "hours" to "সকাল ৮টা – রাত ১০টা", "homeCollection" to true,
        )
        assertTrue(ok("lab1", "lab_me")["lab"].isNull)
        fails("lab1", "lab_save", body - "termsAccepted", 400)
        fails("lab1", "lab_save", body + ("district" to "কোথাও না"), 400)
        fails("lab1", "lab_save", body + ("phone" to "123"), 400)
        fails("lab1", "lab_save", body + ("facilityId" to "dghs_nope"), 400)
        assertEquals("docs_required", fails("lab1", "lab_save", body, 400)["code"].asText())
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM user_quotas WHERE uid = 'lab1' AND kind = 'lab'", Int::class.java))
        val docs = mapOf("license" to jpeg, "front" to jpeg)
        assertEquals("true", ok("lab1", "lab_save", body + ("docs" to docs))["pending"].asText())
        val me = ok("lab1", "lab_me")["lab"]
        assertEquals(listOf("pending", "01711000777", "466", "true"), listOf(me["status"].asText(), me["phone"].asText(), me["upazilaId"].asText(), me["homeCollection"].asText()))
        assertEquals(false, me["docsMissing"].booleanValue())
        assertEquals("", me["docsRequestNote"].asText())
        // not listed while pending; the owner cannot use lab actions yet
        assertFalse(ok(null, "labs", mapOf("district" to dist))["items"].any { it["id"].asText() == "lab1" })
        fails("lab1", "lab_orders", mapOf(), 403)

        // the admin panel
        fails("lab1", "admin_labs", mapOf(), 403)
        val pending = ok("adm", "admin_labs", mapOf("status" to "pending"))["items"]
        assertEquals(listOf("lab1"), ids(pending))
        assertTrue(pending[0]["areaLabel"].asText().endsWith(dist))
        assertEquals(listOf("license", "front"), pending[0]["verify"]["docs"].map { it["type"].asText() })
        assertEquals("checks_required", fails("adm", "admin_lab_resolve", mapOf("id" to "lab1", "status" to "approved"), 400)["code"].asText())
        val checks = listOf("license_no", "license_name", "license_valid", "license_type", "signboard", "phone")
        ok("adm", "admin_lab_resolve", mapOf("id" to "lab1", "status" to "approved", "checks" to checks))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM admin_log WHERE action = 'admin_lab_resolve'", Int::class.java))
        assertEquals("approved", ok("lab1", "lab_me")["lab"]["status"].asText())
        assertEquals(listOf("lab1"), ids(ok("adm", "admin_labs", mapOf("status" to "approved"))["items"]))
        val listed = ok(null, "labs", mapOf("district" to dist))["items"]
        assertEquals("সিটি ডায়াগনস্টিক", listed[0]["name"].asText())
        assertNull(listed[0]["licenseNo"])

        // a new licence number goes back to review (Node: any edit of a lab without a DGHS facility does — the
        // facilityId check compares '' with null; kept for parity)
        assertEquals(true, ok("lab1", "lab_save", body + ("licenseNo" to "DGHS-78"))["pending"].booleanValue())
        assertEquals("pending", ok("lab1", "lab_me")["lab"]["status"].asText())
        val changes = ok("adm", "admin_labs", mapOf("status" to "pending"))["items"][0]["changes"]
        assertTrue(changes.any { it["field"].asText() == "licenseNo" && it["before"].asText() == "DGHS-77" && it["after"].asText() == "DGHS-78" })
        // rejecting the edit brings the approved data back
        assertEquals(true, ok("adm", "admin_lab_resolve", mapOf("id" to "lab1", "status" to "rejected", "reason" to "লাইসেন্স মেলেনি"))["restored"].booleanValue())
        assertEquals("DGHS-77", ok("lab1", "lab_me")["lab"]["licenseNo"].asText())

        // suspended: no edit, no removal
        ok("adm", "admin_lab_resolve", mapOf("id" to "lab1", "status" to "suspended", "reason" to "x"))
        fails("lab1", "lab_save", body, 403)
        fails("lab1", "lab_delete", mapOf(), 403)
        ok("adm", "admin_lab_resolve", mapOf("id" to "lab1", "status" to "approved"))
        ok("lab1", "lab_delete")
        assertTrue(ok("lab1", "lab_me")["lab"].isNull)
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM verify_docs WHERE kind = 'lab' AND listing_id = 'lab1'", Int::class.java))
        ok("lab1", "lab_delete")
    }

    @Test
    fun `the area list - upazila first, then the DGHS centre asked for`() {
        for (u in listOf("la", "lb", "lc")) person(u, u)
        lab("la", "এ ল্যাব", upazilaId = 463)
        lab("lb", "বি ল্যাব", upazilaId = 466, facilityId = "dghs_9")
        lab("lc", "সি ল্যাব", upazilaId = 466)
        person("ld", "ld")
        lab("ld", "ডি ল্যাব", district = "ঢাকা")
        assertEquals(listOf("la", "lb", "lc"), ids(ok(null, "labs", mapOf("district" to dist))["items"]))
        assertEquals(listOf("lb", "lc", "la"), ids(ok(null, "labs", mapOf("district" to dist, "upazilaId" to 466))["items"]))
        assertEquals(listOf("lb", "la", "lc"), ids(ok(null, "labs", mapOf("district" to dist, "facilityId" to "dghs_9"))["items"]))
        fails(null, "labs", mapOf("district" to "x"), 400)
        fails(null, "lab_me", mapOf(), 401)
    }

    // ------------------------------------------------------------------ privacy-gaps.test.js

    private fun reported(id: String, lab: String, at: Long, result: String, patient: String, doctor: String) {
        jdbc.update(
            """
            INSERT INTO lab_orders (id, rx_id, code, lab_uid, lab_name, patient_uid, patient_name, doctor_uid, doctor_name, tests, status, created_at, reported_at, report_note)
            VALUES (?, NULL, 'PGRXQQ5555', ?, ?, ?, 'রোগী', ?, 'ডা.', '{CBC}', 'reported', ?, ?, '')
            """.trimIndent(),
            id, lab, "ল্যাব $lab", patient, doctor, Timestamp(at), Timestamp(at),
        )
        jdbc.update(
            "INSERT INTO lab_report_items (order_id, idx, test, result, unit, flag, code, param_key) VALUES (?, 0, 'Hb', ?, 'g/dL', '', 'cbc', 'hb')",
            id, result,
        )
    }

    @Test
    fun `privacy 1 - a lab sees only its own earlier results, the patient and the doctor see all labs`() {
        val pp = "pgPatient01"
        val l1 = "pgLabOne001"
        val l2 = "pgLabTwo001"
        val l3 = "pgLabNew001"
        val dd = "pgDoctor001"
        for (u in listOf(pp, l1, l2, l3, dd)) person(u, "নাম $u")
        for (l in listOf(l1, l2, l3)) lab(l, "ল্যাব $l")
        val now = System.currentTimeMillis()
        reported("pgOld1", l1, now - 30 * DAY, "10.1", pp, dd)
        reported("pgOld2", l2, now - 20 * DAY, "10.9", pp, dd)
        reported("pgCur1", l1, now - DAY, "11.5", pp, dd)
        reported("pgCur3", l3, now - DAY / 2, "12.0", pp, dd)
        jdbc.update("INSERT INTO rx_reviews (lab_order_id, doctor_uid, patient_uid, status) VALUES ('pgCur1', ?, ?, 'paid')", dd, pp)

        val labView = ok(l1, "lab_order_get", mapOf("id" to "pgCur1"))
        assertEquals(listOf("10.1"), labView["history"]["cbc.hb"].map { it["result"].asText() })
        assertFalse(leaks(labView, "10.9") || leaks(labView, "ল্যাব $l2"))
        val fresh = ok(l3, "lab_order_get", mapOf("id" to "pgCur3"))
        assertEquals("{}", fresh["history"].toString())
        assertEquals("12.0", fresh["item"]["report"]["items"][0]["result"].asText())
        assertEquals(listOf("10.9", "10.1"), ok(pp, "lab_order_get", mapOf("id" to "pgCur1"))["history"]["cbc.hb"].map { it["result"].asText() })
        assertEquals(listOf("10.9", "10.1"), ok(dd, "lab_order_get", mapOf("id" to "pgCur1"))["history"]["cbc.hb"].map { it["result"].asText() })
        fails(dd, "lab_order_get", mapOf("id" to "pgCur3"), 402)
    }

    @Test
    fun `privacy 3 - the lab cannot come home - address and phone go at once, day-time-status stay`() {
        val pp = "pgPatient03"
        val ll = "pgLabHome01"
        person(pp, "রোগী তিন")
        person(ll, "বাসার ল্যাব")
        lab(ll, "বাসার ল্যাব")
        jdbc.update(
            """
            INSERT INTO lab_orders (id, code, lab_uid, lab_name, patient_uid, patient_name, doctor_uid, tests, status, home_status, home_date, home_slot,
              home_note, home_fee, home_address, home_phone)
            VALUES ('pgHome1', 'PGRXQQ3333', ?, 'বাসার ল্যাব', ?, 'রোগী তিন', 'docX', '{CBC}', 'sent', 'requested', CURRENT_DATE + 1, 'morning', '', 100,
              'বাসা ৭, গাঙ্গিনারপাড়', '01711000666')
            """.trimIndent(),
            ll, pp,
        )
        ok(ll, "lab_home_decline", mapOf("id" to "pgHome1", "note" to "লোক নেই"))
        val h = row("SELECT home_address, home_phone, home_status, home_slot, home_date, home_declined_at, home_decline_note FROM lab_orders WHERE id = 'pgHome1'")
        assertEquals(listOf(null, null, "declined", "morning", "লোক নেই"), listOf(h["home_address"], h["home_phone"], h["home_status"], h["home_slot"], h["home_decline_note"]))
        assertTrue(h["home_date"] != null && h["home_declined_at"] != null)
        assertEquals("declined", ok(pp, "lab_order_get", mapOf("id" to "pgHome1"))["item"]["home"]["status"].asText())
        val lv = ok(ll, "lab_orders")["items"].first { it["id"].asText() == "pgHome1" }
        assertFalse(leaks(lv, "গাঙ্গিনারপাড়") || leaks(lv, "01711000666"))
        assertEquals("new", lv["seg"].asText())
    }

    // ------------------------------------------------------------------ daily steps 8 and 9

    @Test
    fun `daily - follow-up reminders 2 days and 1 day before, refill reminder once`() {
        person("rp", "রোগী", "01711000123")
        person("rc", "কেয়ারগিভার")
        careLink("rp", "rc")
        user("rd")
        val today = RxRules.dhakaToday()
        fun rx(id: String, code: String, followUp: Long?, status: String = "active", patient: String? = "rp") = jdbc.update(
            "INSERT INTO prescriptions (id, code, doctor_uid, doctor_name, patient_uid, status, follow_up_date, follow_up_note) VALUES (?, ?, 'rd', 'ডা. রহিম', ?, ?, ?, 'রিপোর্ট নিয়ে আসবেন')",
            id, code, patient, status, followUp?.let { java.sql.Date.valueOf(java.time.LocalDate.ofEpochDay(it / DAY)) },
        )
        rx("f2", "FFFFFFFF22", today + 2 * DAY)
        rx("f1", "FFFFFFFF33", today + DAY)
        rx("f3", "FFFFFFFF44", today + 3 * DAY)
        rx("fc", "FFFFFFFF55", today + DAY, status = "cancelled")
        rx("fp", "FFFFFFFF66", today + DAY, patient = null)
        // refill: Napa 1+0+1 for 10 days, 6 bought → reminder today
        rx("r1", "RRRRRRRR22", null)
        jdbc.update("UPDATE prescriptions SET refill_at = ? WHERE id = 'r1'", java.sql.Date.valueOf(java.time.LocalDate.ofEpochDay(today / DAY)))
        jdbc.update("INSERT INTO prescription_medicines (rx_id, idx, name, form, dose, days) VALUES ('r1', 0, 'Napa', 'Tablet', '1+0+1', 10)")
        val disp = jdbc.queryForObject("INSERT INTO prescription_dispensings (rx_id, pharmacy_uid, pharmacy_name) VALUES ('r1', 'ph', 'দোকান') RETURNING id", Long::class.java)
        jdbc.update("INSERT INTO prescription_dispensed_items (dispensing_id, med_idx, qty) VALUES (?, 0, 6)", disp)

        val out = daily.runAll(System.currentTimeMillis())
        assertEquals(mapOf("sent" to 2), out["rxReminders"])
        assertEquals(mapOf("sent" to 1), out["refill"])
        val titles = pushesTo("rp").map { it.data["title"] }
        assertTrue(titles.contains("২ দিন পর ডা. রহিম-এর কাছে আবার দেখানোর দিন"))
        assertTrue(titles.contains("কাল ডা. রহিম-এর কাছে আবার দেখানোর দিন"))
        assertTrue(titles.contains("২ দিন পর ওষুধ শেষ হবে — কিনে নিন"))
        assertEquals("Napa", pushesTo("rp").last().data["body"])
        assertTrue(pushesTo("rc").all { it.data["type"] == "care" && it.data["asUid"] == "rp" && it.data["rxId"] != null })
        assertEquals(3, pushesTo("rc").size)
        // the same day again: the refill reminder is not sent twice
        assertEquals(mapOf("sent" to 0), daily.runAll(System.currentTimeMillis())["refill"])
    }
}
