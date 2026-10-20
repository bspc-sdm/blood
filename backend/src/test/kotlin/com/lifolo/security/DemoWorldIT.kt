package com.lifolo.security

import com.lifolo.common.Geo
import com.lifolo.rx.RxLabTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.Timestamp

/**
 * Demo accounts (`demo-…`, `…@lifolo-demo.test`) are public: anyone logs in with one click. So they must never reach
 * real people's data — and a real person must never hand their data to one by mistake.
 */
class DemoWorldIT : RxLabTest() {
    private fun demo(key: String, name: String = "ডেমো $key", phone: String = "") =
        person("demo-$key", name, phone, email = "$key@lifolo-demo.test")

    @Test
    fun `caregivers - no link between a demo and a real account, in either direction`() {
        person("realPat", "আসল রোগী")
        person("realcg", "আসল কেয়ারগিভার", email = "realcg@example.com")
        demo("caregiver")
        demo("patient")
        // a real patient adding the demo caregiver's address: answered like any address, never a link
        val r = call("realPat", "care_invite", mapOf("email" to "caregiver@lifolo-demo.test"))
        assertEquals(200, r.status, r.toString())
        // a demo account inviting a real one (spam with a made-up name): the same answer, nothing stored, no push
        assertEquals(200, call("demo-patient", "care_invite", mapOf("email" to "realcg@example.com")).status)
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM care_links", Long::class.java))
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM care_email_invites WHERE patient_uid = 'demo-patient'", Long::class.java))
        assertTrue(pushesTo("realcg").isEmpty())
        // the demo caregiver signing in never takes up the real patient's invitation
        accounts.forgetAll()
        assertEquals(0, ok("demo-caregiver", "care_list")["caringFor"].size())
        ok("realPat", "care_remove", mapOf("caretakerUid" to ok("realPat", "care_list")["caretakers"][0]["caretakerUid"].textValue()))
        // real ↔ real still works
        assertEquals(200, call("realPat", "care_invite", mapOf("email" to "realcg@example.com")).status)
        // a cross-world link that exists anyway (older data) gives no access
        careLink("realPat", "demo-caregiver")
        val acting = call("demo-caregiver", "rx_mine", mapOf("asUid" to "realPat"))
        assertEquals(403, acting.status, acting.toString())
        assertEquals("care_revoked", acting.code)
    }

    @Test
    fun `prescriptions - a demo account cannot claim a real prescription even with its code and phone`() {
        person("realDoc", "ডা. আসল")
        doctor("realDoc", "ডা. আসল")
        demo("patient", phone = "01711000077")
        person("realPat", "আসল রোগী", "01711000077")
        val p = ok("realDoc", "rx_patient_add", mapOf("name" to "আসল রোগী", "phone" to "01711000077"))["patient"]
        val rx = ok("realDoc", "rx_create", mapOf("rxPatientId" to p["id"].asText(), "advice" to "গোপন পরামর্শ"))
        val code = rx["code"].asText()
        val r = call("demo-patient", "rx_claim", mapOf("code" to code, "phone" to "01711000077"))
        assertEquals(400, r.status, r.toString())
        assertEquals(null, row("SELECT patient_uid FROM prescriptions WHERE code = ?", code)["patient_uid"])
        assertEquals(null, row("SELECT linked_uid FROM rx_patients WHERE id = ?", p["id"].asText())["linked_uid"])
        assertTrue(ok("demo-patient", "rx_mine")["items"].size() == 0)
        // a demo pharmacy / lab cannot open it by code either
        demo("pharmacy")
        jdbc.update(
            """
            INSERT INTO pharmacies (uid, name, license_no, phone, district, address, status)
            VALUES ('demo-pharmacy', 'ডেমো ফার্মেসি', 'DL-1', '01711111111', ?, 'চরপাড়া', 'approved')
            """.trimIndent(),
            dist,
        )
        assertEquals(404, call("demo-pharmacy", "rx_pharmacy_open", mapOf("code" to code)).status)
        demo("lab")
        lab("demo-lab", "ডেমো ল্যাব")
        assertEquals(404, call("demo-lab", "lab_open_code", mapOf("code" to code)).status)
        // the real patient can
        assertEquals(1, ok("realPat", "rx_claim", mapOf("code" to code, "phone" to "01711000077"))["added"].intValue())
    }

    @Test
    fun `assistants - a demo address never works for a real doctor (and the reverse)`() {
        person("realDoc", "ডা. আসল")
        doctor("realDoc", "ডা. আসল")
        demo("assistant")
        person("realAsst", "আসল সহকারী", email = "asst@example.com")
        ok("realDoc", "rx_team_set", mapOf("emails" to listOf("assistant@lifolo-demo.test", "asst@example.com")))
        ok("realDoc", "rx_patient_add", mapOf("name" to "গোপন রোগী", "phone" to "01711000088"))
        val body = mapOf("doctorUid" to "realDoc")
        for (a in listOf("rx_patients", "queue_today", "reviews_doctor")) {
            val r = call("demo-assistant", a, body)
            assertEquals(403, r.status, "$a: $r")
            assertFalse(leaks(r.body, "গোপন রোগী"))
        }
        assertEquals(403, call("demo-assistant", "rx_patient_add", body + mapOf("name" to "X", "phone" to "01711000089")).status)
        assertTrue(ok("demo-assistant", "rx_assisting")["items"].size() == 0)
        // the real assistant works
        assertTrue(leaks(ok("realAsst", "rx_patients", body), "গোপন রোগী"))
        // a demo doctor adding a real person's address: that person gets nothing of the demo doctor's
        demo("doctor")
        doctor("demo-doctor", "ডা. ডেমো")
        ok("demo-doctor", "rx_team_set", mapOf("emails" to listOf("asst@example.com")))
        assertEquals(403, call("realAsst", "rx_patients", mapOf("doctorUid" to "demo-doctor")).status)
        assertEquals(listOf("realDoc"), ok("realAsst", "rx_assisting")["items"].map { it["doctorUid"].asText() })
    }

    @Test
    fun `health Q&A - a demo professional cannot answer a real person's question`() {
        person("realPat", "আসল রোগী")
        person("realDoc", "ডা. আসল")
        doctor("realDoc", "ডা. আসল")
        demo("doctor")
        doctor("demo-doctor", "ডা. ডেমো")
        val q = ok("realPat", "ask", mapOf("text" to "আমার জ্বর, কী করব?"))["id"].asText()
        val r = call("demo-doctor", "answer", mapOf("id" to q, "text" to "দোকান থেকে অ্যান্টিবায়োটিক কিনে খান"))
        assertEquals(404, r.status, r.toString())
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM answers WHERE question_id = ?", Long::class.java, q))
        assertTrue(pushesTo("realPat").isEmpty())
        // nor report it (or a real listing); real people can
        assertEquals(404, call("demo-doctor", "report", mapOf("kind" to "question", "id" to q)).status)
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM mod_reports", Long::class.java))
        ok("realDoc", "report", mapOf("kind" to "question", "id" to q))
        // a real doctor can; a demo doctor still answers demo questions
        ok("realDoc", "answer", mapOf("id" to q, "text" to "ডাক্তার দেখান"))
        demo("patient")
        val dq = ok("demo-patient", "ask", mapOf("text" to "ডেমো প্রশ্ন"))["id"].asText()
        ok("demo-doctor", "answer", mapOf("id" to dq, "text" to "ডেমো উত্তর"))
        ok("demo-doctor", "report", mapOf("kind" to "question", "id" to dq))
    }

    @Test
    fun `listings, bookings and blood requests stay in their world`() {
        person("bbOwner", "ব্যাংকের মালিক")
        jdbc.update(
            "INSERT INTO blood_banks (id, owner_uid, name, kind, phone, district, status) VALUES ('bbReal', 'bbOwner', 'ব্যাংক', 'org', '01711111111', ?, 'approved')",
            dist,
        )
        demo("patient")
        person("realPat", "আসল রোগী")
        ok("demo-patient", "bloodbank_called", mapOf("id" to "bbReal"))
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM blood_bank_calls WHERE bank_id = 'bbReal'", Long::class.java))
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM listing_stats WHERE listing_id = 'bbReal'", Long::class.java))
        ok("realPat", "bloodbank_called", mapOf("id" to "bbReal"))
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM blood_bank_calls WHERE bank_id = 'bbReal'", Long::class.java))
        // booking a real doctor
        person("realDoc", "ডা. আসল")
        doctor("realDoc", "ডা. আসল")
        assertEquals(404, call("demo-patient", "book", mapOf("providerUid" to "realDoc", "date" to 0, "note" to "x")).status)
        // a real blood request: not visible, no answer
        jdbc.update(
            """
            INSERT INTO blood_requests (id, requester_uid, requester_name, patient_group, bags, hospital, place_name, lat, lng, geohash,
              needed_by_ms, urgent, note, status, created_at)
            VALUES ('brReal', 'realPat', 'আসল রোগী', 'O+', 1, 'MCH', ?, 24.75, 90.4, ?, ?, FALSE, 'গোপন', 'open', ?)
            """.trimIndent(),
            dist, Geo.hash(24.75, 90.4), System.currentTimeMillis() + DAY, Timestamp(System.currentTimeMillis()),
        )
        assertTrue(ok("demo-patient", "blood_request_get", mapOf("requestId" to "brReal"))["request"].isNull)
        val r = post("/api/respond", mapOf("requestId" to "brReal"), mapOf("Authorization" to "Bearer demo-patient"))
        assertEquals(403, r.status, r.toString())
        assertEquals(403, call("demo-patient", "blood_contact_get", mapOf("requestId" to "brReal")).status)
    }
}
