package com.lifolo.rx

import com.fasterxml.jackson.databind.JsonNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders

/**
 * rx_cancel {id, reason, note?} (RxCancel, backend/docs/RX-STATE.md "Cancellation reason"): the reason is stored with
 * the cancellation and shown to the patient and the caregiver (state + timeline); the reason is required (no old app
 * versions in use); the public /rx/{code} page, pharmacies, labs and pushes never carry the reason or the note.
 */
class RxCancelIT : RxLabTest() {
    private val doc = "rcDoctor"
    private val pat = "rcPatient"
    private val cg = "rcCaregiver"
    private val ph = "rcPharmacy"
    private val labU = "rcLab"
    private val asst = "rcAssistant"

    /** medical details the note may hold — must never leave the patient's own view */
    private val secret = "Napa-র মাত্রা ভুল ছিল, কিডনির সমস্যা"

    private fun seed() {
        person(doc, "ডা. ফারহানা ইসলাম")
        doctor(doc, "ডা. ফারহানা ইসলাম")
        person(pat, "রহিমা বেগম", "01711000001")
        person(cg, "সাকিব (ছেলে)")
        person(ph, "লাইফ ফার্মেসি")
        jdbc.update("INSERT INTO pharmacies (uid, name, license_no, phone, district, status) VALUES (?, 'লাইফ ফার্মেসি', 'L-9', '01722222222', ?, 'approved')", ph, dist)
        person(labU, "পপুলার ডায়াগনস্টিক")
        lab(labU, "পপুলার ডায়াগনস্টিক")
        person(asst, "সহকারী", email = "rc.asst@example.com")
        careLink(pat, cg)
    }

    private val meds = listOf(mapOf("name" to "Napa 500", "form" to "Tablet", "dose" to "1+1+1", "days" to 3))

    /** the doctor writes for a paper patient, the patient adds it by code; (id, code) */
    private fun write(): Pair<String, String> {
        val p = ok(doc, "rx_patient_add", mapOf("name" to "রহিমা বেগম", "age" to 52, "gender" to "female", "phone" to "01711000001"))["patient"]
        val rx = ok(doc, "rx_create", mapOf("medicines" to meds, "tests" to listOf("CBC"), "rxPatientId" to p["id"].asText()))
        ok(pat, "rx_claim", mapOf("code" to rx["code"].asText(), "phone" to "01711000001"))
        return rx["id"].asText() to rx["code"].asText()
    }

    private fun stored(id: String): Map<String, Any?> = jdbc.queryForMap("SELECT status, cancel_reason, cancel_note FROM prescriptions WHERE id = ?", id)

    private fun state(id: String, uid: String = pat, body: Map<String, Any?> = emptyMap()): JsonNode =
        ok(uid, "rx_mine", body)["items"].first { it["id"].asText() == id }["state"]

    private fun cancelStep(id: String, uid: String = pat, body: Map<String, Any?> = emptyMap()): JsonNode =
        ok(uid, "rx_get", body + mapOf("id" to id))["item"]["state"]["timeline"].first { it["kind"].asText() == "cancelled" }

    private fun page(path: String): String =
        mvc.perform(MockMvcRequestBuilders.get(path)).andReturn().response.getContentAsString(Charsets.UTF_8)

    @Test
    fun `the reason and the note are stored and shown to the patient and the caregiver`() {
        seed()
        val (id, _) = write()
        // before: nothing about a cancellation
        state(id).let {
            assertFalse(it["cancelled"].booleanValue())
            assertTrue(it["cancelReason"].isNull)
            assertTrue(it["cancelNote"].isNull)
        }
        val before = push.messages.size
        ok(doc, "rx_cancel", mapOf("id" to id, "reason" to "wrong_medicine", "note" to "  $secret  "))
        assertEquals(mapOf("status" to "cancelled", "cancel_reason" to "wrong_medicine", "cancel_note" to secret), stored(id))

        state(id).let {
            assertEquals("cancelled", it["primary"].asText())
            assertEquals("past", it["phase"].asText())
            assertEquals("wrong_medicine", it["cancelReason"].asText())
            assertEquals(secret, it["cancelNote"].asText())
        }
        cancelStep(id).let {
            assertEquals("wrong_medicine", it["reason"].asText())
            assertEquals(secret, it["note"].asText())
        }
        // the caregiver acting for the patient sees the same
        val asP = mapOf("asUid" to pat)
        assertEquals(state(id).toString(), state(id, cg, asP).toString())
        assertEquals(secret, cancelStep(id, cg, asP)["note"].asText())

        // the first cancellation wins: a second call keeps the reason
        ok(doc, "rx_cancel", mapOf("id" to id, "reason" to "other", "note" to "আরেকটা কারণ"))
        assertEquals("wrong_medicine", stored(id)["cancel_reason"])

        // no push carries the reason or the note (the patient's push stays as it was: none for a cancellation)
        val sent = push.messages.drop(before)
        assertFalse(sent.any { m -> m.data.values.any { secret in it || "wrong_medicine" in it } }, "$sent")
        assertFalse(sent.any { it.uid == pat || it.uid == cg }, "$sent")

        // the writing doctor's own view is unchanged (no state)
        assertNull(ok(doc, "rx_get", mapOf("id" to id))["item"]["state"])
    }

    @Test
    fun `reason checks - one of the keys, other needs 3 to 200 characters`() {
        seed()
        val (id, _) = write()
        assertEquals("cancel_reason", fails(doc, "rx_cancel", mapOf("id" to id, "reason" to "bored"), 400)["code"].asText())
        assertEquals("cancel_reason", fails(doc, "rx_cancel", mapOf("id" to id, "reason" to 3), 400)["code"].asText())
        // a client that sends no reason (or an empty one, or the label instead of the key) gets a plain 400
        assertEquals("cancel_reason", fails(doc, "rx_cancel", mapOf("id" to id), 400)["code"].asText())
        fails(doc, "rx_cancel", mapOf("id" to id, "reason" to ""), 400)
        fails(doc, "rx_cancel", mapOf("id" to id, "reason" to "অন্য কারণ", "note" to "abc"), 400)
        assertEquals("cancel_note", fails(doc, "rx_cancel", mapOf("id" to id, "reason" to "other"), 400)["code"].asText())
        fails(doc, "rx_cancel", mapOf("id" to id, "reason" to "other", "note" to "  ab  "), 400)
        fails(doc, "rx_cancel", mapOf("id" to id, "reason" to "replaced", "note" to "ক".repeat(201)), 400)
        fails(doc, "rx_cancel", mapOf("id" to id, "reason" to "replaced", "note" to 5), 400)
        // nothing changed by the refused calls
        assertEquals("active", stored(id)["status"])
        // "other" with exactly 3 characters
        ok(doc, "rx_cancel", mapOf("id" to id, "reason" to "other", "note" to "abc"))
        assertEquals(mapOf("status" to "cancelled", "cancel_reason" to "other", "cancel_note" to "abc"), stored(id))

        // an optional note may be left out; 200 characters is the limit
        val (id2, _) = write()
        ok(doc, "rx_cancel", mapOf("id" to id2, "reason" to "wrong_patient"))
        assertEquals(mapOf("status" to "cancelled", "cancel_reason" to "wrong_patient", "cancel_note" to null), stored(id2))
        assertTrue(state(id2)["cancelNote"].isNull)
        assertFalse(cancelStep(id2).has("note"))
        val (id3, _) = write()
        ok(doc, "rx_cancel", mapOf("id" to id3, "reason" to "patient_reported", "note" to "ক".repeat(200)))
        assertEquals(200, (stored(id3)["cancel_note"] as String).length)
    }

    @Test
    fun `a cancellation by the previous release (blue-green switch) has no reason - shown as cancelled without one`() {
        seed()
        val (id, _) = write()
        jdbc.update("UPDATE prescriptions SET status = 'cancelled', cancelled_at = now() WHERE id = ?", id)
        state(id).let {
            assertEquals("cancelled", it["primary"].asText())
            assertTrue(it["cancelReason"].isNull)
            assertTrue(it["cancelNote"].isNull)
        }
        assertFalse(cancelStep(id).has("reason"))
        // the doctor can't overwrite it afterwards: still answered ok, nothing stored
        ok(doc, "rx_cancel", mapOf("id" to id, "reason" to "replaced"))
        assertNull(stored(id)["cancel_reason"])
    }

    @Test
    fun `only the writer cancels - not an assistant, not the caregiver, not another doctor`() {
        seed()
        ok(doc, "rx_team_set", mapOf("emails" to listOf("rc.asst@example.com")))
        val (id, _) = write()
        fails(asst, "rx_cancel", mapOf("id" to id, "doctorUid" to doc, "reason" to "wrong_patient"), 404)
        fails(cg, "rx_cancel", mapOf("id" to id, "asUid" to pat, "reason" to "wrong_patient"), 403)
        fails(pat, "rx_cancel", mapOf("id" to id, "reason" to "wrong_patient"), 404)
        person("rcOther", "ডা. অন্য")
        doctor("rcOther", "ডা. অন্য", regNo = "A-99999")
        fails("rcOther", "rx_cancel", mapOf("id" to id, "reason" to "wrong_patient"), 404)
        assertEquals("active", stored(id)["status"])
    }

    @Test
    fun `public verify page shows only বাতিল - never the reason or the note`() {
        seed()
        val (id, code) = write()
        ok(doc, "rx_cancel", mapOf("id" to id, "reason" to "patient_reported", "note" to secret))
        val bn = page("/rx/$code")
        assertTrue(bn.contains(">বাতিল</span>"), bn)
        val en = page("/rx/$code?lang=en")
        assertTrue(en.contains(">Cancelled</span>"), en)
        for (html in listOf(bn, en)) {
            assertFalse(html.contains(secret), "the note leaked")
            for (label in listOf("patient_reported", "রোগী জানিয়েছেন সমস্যা", "Patient reported a problem")) assertFalse(html.contains(label), "leaked: $label")
        }
        // the verify data itself: status, date, doctor — no cancellation fields
        val v = store.verify(code)!!
        assertEquals(setOf("status", "createdAt", "doctor"), v.keys)
        assertEquals("cancelled", v["status"])
    }

    @Test
    fun `a pharmacy or lab opening a cancelled code sees বাতিল with no reason`() {
        seed()
        val (id, code) = write()
        ok(doc, "rx_cancel", mapOf("id" to id, "reason" to "wrong_medicine", "note" to secret))
        val item = ok(ph, "rx_pharmacy_open", mapOf("code" to code))["item"]
        assertEquals("cancelled", item["status"].asText())
        val text = item.toString()
        assertFalse(text.contains(secret))
        for (k in listOf("cancelReason", "cancelNote", "state", "wrong_medicine")) assertFalse(text.contains(k), k)
        val refused = fails(ph, "rx_dispense", mapOf("code" to code, "items" to listOf(mapOf("i" to 0, "qty" to 9))), 409)
        assertEquals("এই প্রেসক্রিপশন বাতিল। ওষুধ দেবেন না।", refused["error"].asText())
        assertFalse(refused.toString().contains(secret))
        val lab = fails(labU, "lab_open_code", mapOf("code" to code), 409)
        assertEquals("এই প্রেসক্রিপশন বাতিল", lab["error"].asText())
        assertFalse(lab.toString().contains(secret))
    }

    @Test
    fun `a psychologist's or physio's notes follow the same flow - the caregiver still never sees psychologist notes`() {
        seed()
        jdbc.update(
            "INSERT INTO prescriptions (id, code, doctor_uid, patient_uid, doctor_name, doctor_type, doctor_system, advice) VALUES ('rcpsy', 'PSYC2345RC', ?, ?, 'কাউন্সেলর', 'psychologist', NULL, 'শ্বাসের ব্যায়াম')",
            doc, pat,
        )
        jdbc.update(
            "INSERT INTO prescriptions (id, code, doctor_uid, patient_uid, doctor_name, doctor_type, doctor_system, advice) VALUES ('rcphy', 'PHYS2345RC', ?, ?, 'ফিজিও', 'physio', NULL, 'হাঁটুর ব্যায়াম')",
            doc, pat,
        )
        ok(doc, "rx_cancel", mapOf("id" to "rcpsy", "reason" to "other", "note" to secret))
        ok(doc, "rx_cancel", mapOf("id" to "rcphy", "reason" to "replaced"))
        assertEquals("other", state("rcpsy")["cancelReason"].asText())
        assertEquals(secret, state("rcpsy")["cancelNote"].asText())
        assertEquals("replaced", state("rcphy")["cancelReason"].asText())
        val asP = mapOf("asUid" to pat)
        val cgItems = ok(cg, "rx_mine", asP)["items"]
        assertFalse(cgItems.any { it["id"].asText() == "rcpsy" })
        assertFalse(cgItems.toString().contains(secret))
        assertEquals(404, call(cg, "rx_get", asP + mapOf("id" to "rcpsy")).status)
        assertEquals("replaced", state("rcphy", cg, asP)["cancelReason"].asText())
    }

    @org.springframework.beans.factory.annotation.Autowired
    lateinit var store: PrescriptionStore
}
