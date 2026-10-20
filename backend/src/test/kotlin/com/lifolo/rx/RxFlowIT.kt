package com.lifolo.rx

import com.fasterxml.jackson.databind.JsonNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * server/test/rxflow.test.js (the prescription, caregiver, lab-form, family-claim and admin-template parts; the
 * pharmacy steps belong to P4 and care_invite/family_set to P2 — seeded here) and labhistory.test.js.
 */
class RxFlowIT : RxLabTest() {
    private val doc = "demoDoctor"
    private val pat = "demoPatient"
    private val cg = "demoCaregiver"
    private val other = "demoStranger"
    private val labU = "demoLab"
    private val admin = "demoAdmin"

    private fun seed() {
        person(doc, "ডা. ফারহানা ইসলাম")
        doctor(doc, "ডা. ফারহানা ইসলাম", "A-12345", "MBBS, FCPS (Medicine)")
        person(pat, "রহিমা বেগম", "01711000001")
        person(cg, "সাকিব (ছেলে)")
        person(other, "অন্য কেউ")
        person(labU, "পপুলার ডায়াগনস্টিক")
        lab(labU, "পপুলার ডায়াগনস্টিক")
        person(admin, "অ্যাডমিন", email = "owner@example.com")
    }

    /** a report row from the lab's form (the lab writes only the result) */
    private fun row(f: JsonNode, key: String, result: String): Map<String, Any?> {
        val p = f["params"].first { it["key"].asText() == key }
        return linkedMapOf(
            "test" to p["name"].asText(), "result" to result, "unit" to p["unit"].asText(), "range" to p["range"].asText(),
            "group" to f["name"].asText(), "code" to f["code"].asText(), "key" to key,
            "low" to p["low"].takeIf { !it.isNull }?.doubleValue(), "high" to p["high"].takeIf { !it.isNull }?.doubleValue(), "flag" to "",
        )
    }

    @Test
    fun `prescription to lab and caregiver - the whole path`() {
        seed()
        // ---------- 1. the doctor enters the patient and writes ----------
        val p1 = ok(doc, "rx_patient_add", mapOf("name" to "রহিমা বেগম", "age" to 52, "gender" to "female", "phone" to "01711-000001"))["patient"]
        assertEquals(false, p1["linked"].booleanValue())
        val rx = ok(
            doc, "rx_create",
            mapOf(
                "rxPatientId" to p1["id"].asText(), "diagnosis" to "Type 2 DM, Dyslipidaemia", "complaints" to "দুর্বলতা",
                "medicines" to listOf(
                    mapOf("name" to "Comet 500", "form" to "Tablet", "dose" to "1+0+1", "days" to 30, "timing" to "after"),
                    mapOf("name" to "Rosuva 10", "form" to "Tablet", "dose" to "0+0+1", "days" to 30),
                    mapOf("name" to "Napa 500", "form" to "Tablet", "dose" to "1+1+1", "days" to 3),
                ),
                "tests" to listOf("CBC", "Lipid profile", "HbA1c", "Bone scan"), "followUpDays" to 30,
            ),
        )
        val rxId = rx["id"].asText()
        val code = rx["code"].asText()
        assertTrue(Regex("^[A-HJ-NP-Z2-9]{10}$").matches(code))
        // the same phone + name again: the existing patient (age updated)
        val again = ok(doc, "rx_patient_add", mapOf("name" to "রহিমা বেগম", "age" to 53, "phone" to "+8801711000001"))
        assertEquals(true, again["existed"].booleanValue())
        assertEquals(53, again["patient"]["age"].intValue())

        // ---------- 2. the patient adds it with the code and the phone number ----------
        fails(pat, "rx_claim", mapOf("code" to code, "phone" to "01711999999"), 400)
        val claimed = ok(pat, "rx_claim", mapOf("code" to code.lowercase(), "phone" to "01711000001"))
        assertEquals(1, claimed["added"].intValue())
        assertEquals(0, ok(pat, "rx_claim", mapOf("code" to code, "phone" to "01711000001"))["added"].intValue())
        val mine = ok(pat, "rx_mine")["items"]
        assertEquals(1, mine.size())
        assertEquals(listOf("Comet 500", "Rosuva 10", "Napa 500"), mine[0]["medicines"].map { it["name"].asText() })
        assertEquals(true, mine[0]["linked"].booleanValue())
        assertEquals("A-12345", mine[0]["doctor"]["regNo"].asText())
        assertTrue(mine[0]["followUpDate"].longValue() > 0)

        // ---------- 3. the caregiver ----------
        fails(cg, "rx_mine", mapOf("asUid" to pat), 403)
        careLink(pat, cg)
        assertEquals(1, ok(cg, "rx_mine", mapOf("asUid" to pat))["items"].size())
        fails(other, "rx_mine", mapOf("asUid" to pat), 403)
        fails(cg, "rx_patient_history", mapOf("asUid" to pat, "rxPatientId" to p1["id"].asText()), 403)

        // ---------- 7. the lab form ----------
        ok(cg, "rx_send_lab", mapOf("asUid" to pat, "rxId" to rxId, "labId" to labU, "tests" to listOf("CBC", "Lipid profile", "HbA1c", "Bone scan")))
        assertEquals(cg, row("SELECT by_caretaker FROM lab_orders WHERE rx_id = ?", rxId)["by_caretaker"])
        val lo = ok(labU, "lab_orders")["items"][0]
        val forms = ok(labU, "lab_form", mapOf("id" to lo["id"].asText()))["forms"]
        val cbc = forms.first { it["code"].asText() == "cbc" }
        val hb = cbc["params"].first { it["key"].asText() == "hb" }
        assertEquals(listOf(12.0, 15.0), listOf(hb["low"].doubleValue(), hb["high"].doubleValue()))
        assertEquals("g/dL", hb["unit"].asText())
        assertTrue(forms.first { it["test"].asText() == "Bone scan" }["code"].isNull)
        ok(labU, "lab_ranges_set", mapOf("ranges" to listOf(mapOf("code" to "hba1c", "key" to "hba1c", "low" to 4.2, "high" to 5.8))))
        val forms2 = ok(labU, "lab_form", mapOf("id" to lo["id"].asText()))["forms"]
        assertEquals(5.8, forms2.first { it["code"].asText() == "hba1c" }["params"][0]["high"].doubleValue())
        val lip = forms2.first { it["code"].asText() == "lipid" }
        val a1c = forms2.first { it["code"].asText() == "hba1c" }
        ok(
            labU, "lab_report",
            mapOf(
                "id" to lo["id"].asText(), "note" to "",
                "items" to listOf(
                    row(cbc, "hb", "10.4"), row(cbc, "wbc", "7.8"), row(lip, "chol", "232"), row(lip, "hdl", "38"), row(a1c, "hba1c", "8.6"),
                    mapOf("test" to "Bone scan", "result" to "Normal study", "flag" to ""),
                ),
            ),
        )
        val rep = ok(pat, "lab_order_get", mapOf("id" to lo["id"].asText()))
        val flags = rep["item"]["report"]["items"].associate { it["test"].asText() to it["flag"].asText() }
        assertEquals(
            mapOf("Haemoglobin" to "low", "Total WBC count" to "", "Total cholesterol" to "high", "HDL cholesterol" to "low", "HbA1c" to "high", "Bone scan" to ""),
            flags,
        )
        assertTrue(lastPush(pat)!!["title"]!!.contains("রিপোর্ট"))
        assertTrue(lastPush(cg)!!["title"]!!.contains("রহিমা বেগম"))

        // a second report later — the earlier result next to it
        val rx2 = ok(doc, "rx_create", mapOf("rxPatientId" to p1["id"].asText(), "medicines" to listOf(mapOf("name" to "Comet 500", "form" to "Tablet", "dose" to "1+0+1", "days" to 30)), "tests" to listOf("HbA1c")))
        ok(pat, "rx_send_lab", mapOf("rxId" to rx2["id"].asText(), "labId" to labU))
        val lo2 = ok(labU, "lab_orders")["items"].first { it["rxId"].asText() == rx2["id"].asText() }
        val f3 = ok(labU, "lab_form", mapOf("id" to lo2["id"].asText()))["forms"][0]
        ok(labU, "lab_report", mapOf("id" to lo2["id"].asText(), "items" to listOf(row(f3, "hba1c", "7.4"))))
        val rep2 = ok(pat, "lab_order_get", mapOf("id" to lo2["id"].asText()))
        assertEquals(listOf("8.6" to "high"), rep2["history"]["hba1c.hba1c"].map { it["result"].asText() to it["flag"].asText() })
        val hist = ok(doc, "rx_patient_history", mapOf("rxPatientId" to p1["id"].asText()))
        assertEquals(setOf(rxId, rx2["id"].asText()), hist["items"].map { it["id"].asText() }.toSet())
        fails(pat, "rx_patient_history", mapOf("rxPatientId" to p1["id"].asText()), 403)

        // ---------- 8. a family member's prescription ----------
        jdbc.update(
            "INSERT INTO user_family_members (uid, member_id, position, name, relation, age, gender, phone) VALUES (?, 'm_1', 0, 'আব্দুল করিম', 'স্বামী', 60, 'male', '01811000002')",
            pat,
        )
        val p2 = ok(doc, "rx_patient_add", mapOf("name" to "আব্দুল করিম", "age" to 60, "gender" to "male", "phone" to "01811000002"))["patient"]
        val rx3 = ok(doc, "rx_create", mapOf("rxPatientId" to p2["id"].asText(), "medicines" to listOf(mapOf("name" to "Amlo 5", "form" to "Tablet", "dose" to "1+0+0", "days" to 30))))
        fails(other, "rx_claim", mapOf("code" to rx3["code"].asText(), "phone" to "01811000002"), 400)
        ok(pat, "rx_claim", mapOf("code" to rx3["code"].asText(), "phone" to "01811000002"))
        assertEquals(setOf("আব্দুল করিম", "রহিমা বেগম"), ok(pat, "rx_mine")["items"].map { it["patient"]["name"].asText() }.toSet())

        // ---------- 9. the caregiver removed: no access, no pushes ----------
        jdbc.update("DELETE FROM care_links WHERE patient_uid = ? AND caretaker_uid = ?", pat, cg)
        fails(cg, "rx_mine", mapOf("asUid" to pat), 403)
        val before = push.messages.size
        call(labU, "lab_order_status", mapOf("id" to lo2["id"].asText(), "status" to "received"))
        ok(doc, "rx_cancel", mapOf("id" to rx3["id"].asText()))
        assertFalse(push.messages.drop(before).any { it.uid == cg })
        assertEquals("cancelled", ok(pat, "rx_get", mapOf("id" to rx3["id"].asText()))["item"]["status"].asText())

        // ---------- 10. the admin adds a template ----------
        val mg = mapOf("name" to "Serum Magnesium", "aliases" to listOf("s magnesium", "magnesium"), "params" to listOf(mapOf("name" to "Magnesium", "unit" to "mg/dL", "r" to listOf(1.7, 2.2))))
        fails(doc, "admin_labtests_set", mapOf("test" to mg), 403)
        val added = ok(admin, "admin_labtests_set", mapOf("test" to mg))
        assertEquals("serum_magnesium", added["test"]["code"].asText())
        fails(admin, "admin_labtests_set", mapOf("test" to mapOf("name" to "Sugar test", "aliases" to listOf("rbs"), "params" to listOf(mapOf("name" to "x")))), 409)
        assertTrue(ok(pat, "labtests_get")["tests"].any { it["code"].asText() == "serum_magnesium" })
        val rx4 = ok(doc, "rx_create", mapOf("rxPatientId" to p1["id"].asText(), "tests" to listOf("S. Magnesium")))
        ok(pat, "rx_send_lab", mapOf("rxId" to rx4["id"].asText(), "labId" to labU))
        val lo4 = ok(labU, "lab_orders")["items"].first { it["rxId"].asText() == rx4["id"].asText() }
        val f4 = ok(labU, "lab_form", mapOf("id" to lo4["id"].asText()))["forms"][0]
        assertEquals("Serum Magnesium" to "1.7 – 2.2", f4["name"].asText() to f4["params"][0]["range"].asText())
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM admin_log WHERE action = 'admin_labtests_set'", Int::class.java))

        // every prescription push carries rxId (tapping opens that prescription, also for the caregiver)
        val rxPushes = push.messages.filter { it.data["type"] in setOf("rx", "rx_lab", "rx_review") }
        assertTrue(rxPushes.isNotEmpty())
        for (p in rxPushes) {
            assertFalse(p.data["rxId"].isNullOrEmpty(), "${p.data["type"]}: ${p.data["title"]}")
            if (p.data["type"] == "rx") assertEquals(p.data["rxId"], p.data["id"])
        }
        val carePushes = push.messages.filter { it.data["type"] == "care" }
        assertTrue(carePushes.size >= 2 && carePushes.all { it.data["asUid"] == pat && !it.data["rxId"].isNullOrEmpty() })
    }

    @Test
    fun `the doctor's view of a patient's prescription and follow-up booking`() {
        seed()
        jdbc.update(
            """
            INSERT INTO provider_chambers (provider_uid, idx, hospital_id, hospital_name, hospital_name_bn, district, days, start_time, end_time, appointments, serial_phone)
            VALUES (?, 0, 'dghs_1', 'City Hospital', 'সিটি হাসপাতাল', ?, '{5}', '17:00', '20:00', TRUE, '01711000555')
            """.trimIndent(),
            doc, dist,
        )
        jdbc.update("UPDATE providers SET appointments = TRUE WHERE uid = ?", doc)
        assertEquals(listOf("সিটি হাসপাতাল"), ok(doc, "rx_me")["chambers"].map { it.asText() })
        // an app appointment: the patient is linked at once
        jdbc.update(
            "INSERT INTO appointments (id, patient_uid, provider_uid, patient_name, type, visit_date, status, serial) VALUES ('ap1', ?, ?, 'রহিমা বেগম', 'doctor', CURRENT_DATE, 'confirmed', 2)",
            pat, doc,
        )
        val list = ok(doc, "rx_patients")
        assertEquals("doctor", list["role"].asText())
        assertEquals(listOf("ap1"), list["appointments"].map { it["id"].asText() })
        assertEquals("ap1", list["appPatients"][0]["appointmentId"].asText())
        val rx = ok(doc, "rx_create", mapOf("appointmentId" to "ap1", "advice" to "বিশ্রাম", "chamberIndex" to 0, "followUpDays" to 7, "followUpNote" to "রিপোর্ট নিয়ে আসবেন"))
        assertEquals("ডা. ফারহানা ইসলাম নতুন প্রেসক্রিপশন দিয়েছেন", lastPush(pat)!!["title"])
        val item = ok(pat, "rx_get", mapOf("id" to rx["id"].asText()))["item"]
        assertEquals("সিটি হাসপাতাল", item["doctor"]["chamber"]["name"].asText())
        assertEquals("01711000555", item["doctor"]["chamber"]["phone"].asText())
        assertEquals(dist, item["doctor"]["chamber"]["address"].asText())
        assertEquals(0, item["chamberIndex"].intValue())
        // the chamber sits on Fridays: the booking day is the first Friday on/after the follow-up day
        val bookDay = item["followUpBookDate"].longValue()
        assertTrue(bookDay >= item["followUpDate"].longValue())
        assertEquals(5, Math.floorMod(Math.floorDiv(bookDay, DAY) + 4, 7L).toInt())
        val activity = ok(pat, "rx_activity", mapOf("rxId" to rx["id"].asText()))
        assertEquals(5, Math.floorMod(Math.floorDiv(activity["nextOpen"].longValue(), DAY) + 4, 7L).toInt())
        assertEquals(0, activity["medicines"].size())
        // history by appointment; the verify page shows no diagnosis or phone
        assertEquals(listOf(rx["id"].asText()), ok(doc, "rx_patient_history", mapOf("appointmentId" to "ap1"))["items"].map { it["id"].asText() })
        fails(doc, "rx_patient_history", mapOf(), 400)
        fails(doc, "rx_create", mapOf("appointmentId" to "nope"), 404)
        fails(doc, "rx_create", mapOf("appointmentId" to "ap1"), 400)
        assertEquals(listOf(rx["id"].asText()), ok(doc, "rx_doctor_list")["items"].map { it["id"].asText() })
        fails(pat, "rx_doctor_list", mapOf(), 403)
        fails(other, "rx_get", mapOf("id" to rx["id"].asText()), 404)
        fails(other, "rx_cancel", mapOf("id" to rx["id"].asText()), 404)
    }

    @Test
    fun `assistants - the doctor's team, entering patients for the doctor`() {
        seed()
        person("asst1", "সহকারী", email = "Asst@Example.com")
        fails(doc, "rx_team_set", mapOf("emails" to listOf("a@b.co", "x")), 400)
        fails(doc, "rx_team_set", mapOf("emails" to listOf("demoDoctor@example.com")), 400)
        fails(doc, "rx_team_set", mapOf("emails" to (1..6).map { "a$it@b.co" }), 400)
        assertEquals(listOf("asst@example.com"), ok(doc, "rx_team_set", mapOf("emails" to listOf(" Asst@Example.com ", "asst@example.com")))["emails"].map { it.asText() })
        assertEquals(listOf("asst@example.com"), ok(doc, "rx_team")["emails"].map { it.asText() })
        assertEquals(listOf(doc), ok("asst1", "rx_assisting")["items"].map { it["doctorUid"].asText() })
        assertEquals("assistant", ok("asst1", "rx_patients", mapOf("doctorUid" to doc))["role"].asText())
        val p = ok("asst1", "rx_patient_add", mapOf("doctorUid" to doc, "name" to "করিম", "phone" to "01711000002"))["patient"]
        assertEquals(doc, row("SELECT doctor_uid FROM rx_patients WHERE id = ?", p["id"].asText())["doctor_uid"])
        fails("asst1", "rx_create", mapOf("rxPatientId" to p["id"].asText(), "advice" to "x"), 403)
        fails(other, "rx_patients", mapOf("doctorUid" to doc), 403)
        fails("asst1", "rx_patients", mapOf(), 403)
        // a doctor not verified now: the assistant cannot act for them
        jdbc.update("UPDATE badges SET status = 'pending' WHERE uid = ?", doc)
        fails("asst1", "rx_patients", mapOf("doctorUid" to doc), 403)
        assertEquals(0, ok("asst1", "rx_assisting")["items"].size())
    }

    // ------------------------------------------------------------------ labhistory.test.js

    private val d1 = "lhDoctor"
    private val d2 = "lhDoctor2"
    private val asst = "lhAssistant"
    private val lp = "lhPatient"
    private val ll = "lhLab"

    private fun seedHistory() {
        person(d1, "ডা. প্রথম")
        doctor(d1, "ডা. প্রথম", "A-8")
        person(d2, "ডা. দ্বিতীয়")
        doctor(d2, "ডা. দ্বিতীয়", "A-9")
        person(asst, "সহকারী", email = "asst@lh.test")
        jdbc.update("INSERT INTO provider_assistants (provider_uid, email) VALUES (?, 'asst@lh.test')", d1)
        person(lp, "রোগী রহিম", "01711000301")
        person(ll, "পরীক্ষা ল্যাব")
        lab(ll, "পরীক্ষা ল্যাব")
        person(other, "অন্য কেউ")
    }

    private data class Sent(val rxId: String, val loId: String)

    private fun rxWithLab(doctor: String, rxPatientId: String, tests: List<String>): Sent {
        val rx = ok(doctor, "rx_create", mapOf("rxPatientId" to rxPatientId, "tests" to tests, "advice" to "রিপোর্ট নিয়ে আসবেন"))
        ok(lp, "rx_send_lab", mapOf("rxId" to rx["id"].asText(), "labId" to ll))
        val lo = ok(ll, "lab_orders")["items"].first { it["rxId"].asText() == rx["id"].asText() }
        return Sent(rx["id"].asText(), lo["id"].asText())
    }

    private fun report(id: String, value: String) = ok(
        ll, "lab_report",
        mapOf("id" to id, "items" to listOf(mapOf("test" to "Haemoglobin", "result" to value, "unit" to "g/dL", "range" to "13 – 17", "flag" to "")), "note" to "নোট-$value"),
    )

    private fun labsOf(doctor: String, rxPatientId: String) = ok(doctor, "rx_patient_history", mapOf("rxPatientId" to rxPatientId))["labOrders"]

    @Test
    fun `the doctor's history shows partner lab reports under the payment rule`() {
        seedHistory()
        val pt = ok(d1, "rx_patient_add", mapOf("name" to "রোগী রহিম", "age" to 50, "gender" to "male", "phone" to "01711000301"))["patient"]["id"].asText()
        val first = ok(d1, "rx_create", mapOf("rxPatientId" to pt, "advice" to "বিশ্রাম"))
        ok(lp, "rx_claim", mapOf("code" to first["code"].asText(), "phone" to "01711000301"))

        // ---------- 1. "no fee": the doctor sees results as soon as they come ----------
        ok(d1, "review_settings", mapOf("set" to true, "enabled" to true, "noFee" to true))
        val a = rxWithLab(d1, pt, listOf("CBC"))
        var labs = labsOf(d1, pt)
        assertEquals(listOf(listOf(a.loId, "sent", "false", "null")), labs.map { listOf(it["id"].asText(), it["status"].asText(), it["open"].asText(), it["report"].toString()) })
        report(a.loId, "11.1")
        assertEquals("একটা রিপোর্ট দেখার অপেক্ষায়", lastPush(d1)!!["title"])
        labs = labsOf(d1, pt)
        val fa = labs.first { it["id"].asText() == a.loId }
        assertEquals(listOf("reported", "paid", "true"), listOf(fa["status"].asText(), fa["review"].asText(), fa["open"].asText()))
        assertEquals("11.1", fa["report"]["items"][0]["result"].asText())
        assertEquals("নোট-11.1", fa["report"]["note"].asText())
        assertNull(fa["report"]["photos"])
        assertEquals(a.rxId, fa["rxId"].asText())
        val got = ok(d1, "rx_get", mapOf("id" to a.rxId))
        assertEquals("11.1", got["labOrders"][0]["report"]["items"][0]["result"].asText())
        assertEquals("11.1", ok(d1, "lab_order_get", mapOf("id" to a.loId))["item"]["report"]["items"][0]["result"].asText())

        // ---------- 2. a fee, not paid: the status, not the values ----------
        fails(d1, "review_settings", mapOf("set" to true, "enabled" to true, "noFee" to false, "fee" to "৩০০ টাকা"), 400)
        val st = ok(d1, "review_settings", mapOf("set" to true, "enabled" to true, "noFee" to false, "fee" to "৩০০ টাকা", "payNumber" to "01711000355", "payMethods" to listOf("bkash", "paypal")))
        assertEquals(listOf("bkash"), st["payMethods"].map { it.asText() })
        assertEquals("01711000355", ok(d1, "review_settings")["payNumber"].asText())
        val b = rxWithLab(d1, pt, listOf("CBC"))
        ok(ll, "lab_order_status", mapOf("id" to b.loId, "status" to "received"))
        labs = labsOf(d1, pt)
        assertEquals(listOf("received", "false", "null"), labs.filter { it["id"].asText() == b.loId }.map { listOf(it["status"].asText(), it["open"].asText(), it["review"].toString()) }.single())
        report(b.loId, "9.7")
        val hist = ok(d1, "rx_patient_history", mapOf("rxPatientId" to pt))
        val fb = hist["labOrders"].first { it["id"].asText() == b.loId }
        assertEquals(listOf("reported", "unpaid", "false", "null", ""), listOf(fb["status"].asText(), fb["review"].asText(), fb["open"].asText(), fb["report"].toString(), fb["comment"].asText()))
        assertTrue(fb["reportedAt"].longValue() > 0)
        assertFalse(leaks(hist, "9.7") || leaks(hist, "নোট-9.7"))
        val gb = ok(d1, "rx_get", mapOf("id" to b.rxId))
        assertFalse(leaks(gb, "9.7"))
        assertEquals(false, gb["labOrders"][0]["open"].booleanValue())
        fails(d1, "lab_order_get", mapOf("id" to b.loId), 402)
        assertEquals(listOf(b.loId, a.loId), ids(hist["labOrders"]))

        // ---------- 3. the patient pays, the assistant confirms → the doctor sees ----------
        fails(lp, "review_pay", mapOf("id" to b.loId, "method" to "bkash", "trxId" to "12"), 400)
        ok(lp, "review_pay", mapOf("id" to b.loId, "method" to "bkash", "trxId" to "LH12 345678"))
        assertEquals("রোগী রহিম · bkash · LH12345678", lastPush(d1)!!["body"])
        fails(lp, "review_pay", mapOf("id" to b.loId, "method" to "bkash", "trxId" to "LH12345678"), 409)
        labs = labsOf(d1, pt)
        assertEquals(listOf("claimed", "false", "null"), labs.filter { it["id"].asText() == b.loId }.map { listOf(it["review"].asText(), it["open"].asText(), it["report"].toString()) }.single())
        assertFalse(leaks(labs, "LH12345678"))
        // the assistant sees the TrxID (to match the money), never the report
        val asList = ok(asst, "reviews_doctor", mapOf("doctorUid" to d1))
        assertEquals("LH12345678", asList["items"].first { it["id"].asText() == b.loId }["trxId"].asText())
        ok(asst, "review_confirm", mapOf("doctorUid" to d1, "id" to b.loId, "ok" to true))
        assertEquals("ডাক্তার টাকা পেয়েছেন, রিপোর্ট দেখবেন", lastPush(lp)!!["title"])
        labs = labsOf(d1, pt)
        val fb2 = labs.first { it["id"].asText() == b.loId }
        assertEquals(listOf("paid", "true", "9.7"), listOf(fb2["review"].asText(), fb2["open"].asText(), fb2["report"]["items"][0]["result"].asText()))
        fails(d1, "review_done", mapOf("id" to b.loId, "comment" to ""), 400)
        ok(d1, "review_done", mapOf("id" to b.loId, "comment" to "রক্তস্বল্পতা — আয়রন খান"))
        assertEquals("রক্তস্বল্পতা — আয়রন খান", labsOf(d1, pt).first { it["id"].asText() == b.loId }["comment"].asText())
        assertEquals("done", ok(lp, "rx_activity", mapOf("rxId" to b.rxId))["reviews"][0]["status"].asText())

        // ---------- 4. online review off: never the values; a cancelled order shows its status ----------
        ok(d1, "review_settings", mapOf("set" to true, "enabled" to false))
        val c = rxWithLab(d1, pt, listOf("RBS"))
        report(c.loId, "6.6")
        val dd = rxWithLab(d1, pt, listOf("ECG"))
        ok(ll, "lab_order_status", mapOf("id" to dd.loId, "status" to "cancelled"))
        val h4 = ok(d1, "rx_patient_history", mapOf("rxPatientId" to pt))
        assertEquals(listOf("off", "false", "null"), h4["labOrders"].filter { it["id"].asText() == c.loId }.map { listOf(it["review"].asText(), it["open"].asText(), it["report"].toString()) }.single())
        assertFalse(leaks(h4, "6.6"))
        assertEquals(listOf("cancelled", "false"), h4["labOrders"].filter { it["id"].asText() == dd.loId }.map { listOf(it["status"].asText(), it["open"].asText()) }.single())

        // ---------- 5. another doctor: nothing of this patient/prescription/report ----------
        fails(d2, "rx_patient_history", mapOf("rxPatientId" to pt), 404)
        fails(d2, "rx_get", mapOf("id" to a.rxId), 404)
        fails(d2, "lab_order_get", mapOf("id" to a.loId), 404)
        ok(d2, "review_settings", mapOf("set" to true, "enabled" to true, "noFee" to true))
        val pt2 = ok(d2, "rx_patient_add", mapOf("name" to "রোগী রহিম", "age" to 50, "gender" to "male", "phone" to "01711000301"))["patient"]["id"].asText()
        val f2 = ok(d2, "rx_create", mapOf("rxPatientId" to pt2, "advice" to "x"))
        ok(lp, "rx_claim", mapOf("code" to f2["code"].asText(), "phone" to "01711000301"))
        val e = rxWithLab(d2, pt2, listOf("CBC"))
        report(e.loId, "12.4")
        val mine2 = ok(d2, "rx_patient_history", mapOf("rxPatientId" to pt2))
        assertEquals(listOf(e.loId to true), mine2["labOrders"].map { it["id"].asText() to it["open"].booleanValue() })
        assertFalse(leaks(mine2, "11.1") || leaks(mine2, "9.7"))
        val h5 = ok(d1, "rx_patient_history", mapOf("rxPatientId" to pt))
        assertFalse(h5["labOrders"].any { it["id"].asText() == e.loId } || leaks(h5, "12.4"))

        // ---------- 6. the assistant: no history/prescription/report; only payment data in "reviews" ----------
        fails(asst, "rx_patient_history", mapOf("doctorUid" to d1, "rxPatientId" to pt), 403)
        fails(asst, "rx_get", mapOf("id" to a.rxId), 404)
        fails(asst, "lab_order_get", mapOf("id" to a.loId), 404)
        val asList2 = ok(asst, "reviews_doctor", mapOf("doctorUid" to d1))
        assertTrue(asList2["items"].size() >= 2)
        assertFalse(leaks(asList2, "11.1") || leaks(asList2, "9.7"))
        assertTrue(asList2["items"].all { it["report"] == null && it["status"].asText() != "off" })
        fails(other, "reviews_doctor", mapOf("doctorUid" to d1), 403)

        // ---------- 7. the patient: every report as before, no history ----------
        fails(lp, "rx_patient_history", mapOf("rxPatientId" to pt), 403)
        assertNull(ok(lp, "rx_get", mapOf("id" to b.rxId))["labOrders"])
        assertEquals("6.6", ok(lp, "lab_order_get", mapOf("id" to c.loId))["item"]["report"]["items"][0]["result"].asText())
    }
}
