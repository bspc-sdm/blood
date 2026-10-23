package com.lifolo.rx

import com.fasterxml.jackson.databind.JsonNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.Timestamp

/**
 * rx_mine / rx_get `state` (backend/docs/RX-STATE.md) from real rows: pharmacy orders, dispensings, lab orders, report
 * reviews, the doctor's cancellation, the follow-up. The old fields stay as they were; the caregiver sees the same; a
 * psychologist's notes stay hidden from the caregiver; the doctor's own view has no state.
 */
class RxStateIT : RxLabTest() {
    private val doc = "stDoctor"
    private val pat = "stPatient"
    private val cg = "stCaregiver"
    private val ph = "stPharmacy"
    private val labU = "stLab"

    private fun seed() {
        person(doc, "ডা. ফারহানা ইসলাম")
        doctor(doc, "ডা. ফারহানা ইসলাম")
        person(pat, "রহিমা বেগম", "01711000001")
        person(cg, "সাকিব (ছেলে)")
        person(ph, "লাইফ ফার্মেসি")
        jdbc.update("INSERT INTO pharmacies (uid, name, license_no, phone, district, status) VALUES (?, 'লাইফ ফার্মেসি', 'L-9', '01722222222', ?, 'approved')", ph, dist)
        person(labU, "পপুলার ডায়াগনস্টিক")
        lab(labU, "পপুলার ডায়াগনস্টিক")
        careLink(pat, cg)
    }

    /** the doctor writes for a paper patient, the patient adds it by code; the id */
    private fun write(body: Map<String, Any?>): String {
        val p = ok(doc, "rx_patient_add", mapOf("name" to "রহিমা বেগম", "age" to 52, "gender" to "female", "phone" to "01711000001"))["patient"]
        val rx = ok(doc, "rx_create", body + mapOf("rxPatientId" to p["id"].asText()))
        ok(pat, "rx_claim", mapOf("code" to rx["code"].asText(), "phone" to "01711000001"))
        return rx["id"].asText()
    }

    private val meds = listOf(
        mapOf("name" to "Comet 500", "form" to "Tablet", "dose" to "1+0+1", "days" to 10),
        mapOf("name" to "Rosuva 10", "form" to "Tablet", "dose" to "0+0+1", "days" to 10),
        mapOf("name" to "Napa 500", "form" to "Tablet", "dose" to "1+1+1", "days" to 3),
    )

    private fun order(id: String, rxId: String, status: String, mode: String = "shop", updated: Long = System.currentTimeMillis()) {
        jdbc.update(
            "INSERT INTO rx_orders (id, rx_id, code, patient_uid, pharmacy_uid, pharmacy_name, mode, status, created_at, updated_at) VALUES (?, ?, 'ABCD2345EF', ?, ?, 'লাইফ ফার্মেসি', ?, ?, ?, ?)",
            id, rxId, pat, ph, mode, status, Timestamp(updated), Timestamp(updated),
        )
    }

    private fun setStatus(id: String, status: String) = jdbc.update("UPDATE rx_orders SET status = ?, updated_at = now() WHERE id = ?", status, id)

    private fun dispense(rxId: String, vararg items: Pair<Int, Int>) {
        val id = jdbc.queryForObject(
            "INSERT INTO prescription_dispensings (rx_id, pharmacy_uid, pharmacy_name, at) VALUES (?, ?, 'লাইফ ফার্মেসি', now()) RETURNING id",
            Long::class.java, rxId, ph,
        )!!
        for ((i, q) in items) jdbc.update("INSERT INTO prescription_dispensed_items (dispensing_id, med_idx, qty) VALUES (?, ?, ?)", id, i, q)
    }

    private fun mine(uid: String = pat, body: Map<String, Any?> = emptyMap()): List<JsonNode> = ok(uid, "rx_mine", body)["items"].toList()

    private fun stateOf(rxId: String, uid: String = pat, body: Map<String, Any?> = emptyMap()): JsonNode = mine(uid, body).first { it["id"].asText() == rxId }["state"]

    @Test
    fun `medicine line follows the pharmacy order and the purchases, then the prescription moves to past`() {
        seed()
        val id = write(mapOf("medicines" to meds))
        stateOf(id).let {
            assertEquals("not_bought", it["primary"].asText())
            assertEquals("active", it["phase"].asText())
            assertEquals(3, it["medicine"]["total"].intValue())
            assertTrue(it["lab"].isNull)
        }
        // the old fields are all still there, unchanged
        val item = mine().first()
        for (k in listOf("id", "code", "status", "createdAt", "doctorUid", "doctor", "patient", "medicines", "tests", "followUpDate", "linked", "doctorType", "pharmacy", "rxRules")) {
            assertTrue(item.has(k), k)
        }
        assertEquals("active", item["status"].asText())

        order("o1", id, "sent")
        assertEquals("sent", stateOf(id)["primary"].asText())
        assertEquals("লাইফ ফার্মেসি", stateOf(id)["medicine"]["pharmacyName"].asText())
        setStatus("o1", "ready")
        assertEquals("ready", stateOf(id)["primary"].asText())
        // the shop could not give them
        setStatus("o1", "cancelled")
        assertEquals("declined", stateOf(id)["primary"].asText())
        assertEquals("error", stateOf(id)["tone"].asText())

        // online: accepted → on the way
        order("o2", id, "accepted", mode = "online")
        assertEquals("accepted", stateOf(id)["primary"].asText())
        setStatus("o2", "out")
        assertEquals("out", stateOf(id)["primary"].asText())
        // the pharmacy gives 2 of 3
        dispense(id, 0 to 20, 1 to 10)
        setStatus("o2", "done")
        stateOf(id).let {
            assertEquals("partial", it["primary"].asText())
            assertEquals(2, it["medicine"]["bought"].intValue())
            assertEquals("active", it["phase"].asText())
        }
        // the refill reminder day has come
        jdbc.update("UPDATE prescriptions SET refill_at = CURRENT_DATE WHERE id = ?", id)
        assertEquals("running_out", stateOf(id)["primary"].asText())
        jdbc.update("UPDATE prescriptions SET refill_at = NULL WHERE id = ?", id)
        // the rest: all bought → past (no tests, no follow-up)
        dispense(id, 2 to 9)
        stateOf(id).let {
            assertEquals("all_bought", it["primary"].asText())
            assertEquals("good", it["tone"].asText())
            assertEquals("past", it["phase"].asText())
        }

        // the timeline on rx_get (patient only)
        val tl = ok(pat, "rx_get", mapOf("id" to id))["item"]["state"]["timeline"].map { it["kind"].asText() }
        assertEquals("written", tl.first())
        for (k in listOf("pharmacy_sent", "pharmacy_cancelled", "bought")) assertTrue(k in tl, "$k in $tl")
        assertEquals(2, tl.count { it == "bought" })
        // the list carries no timeline (kept light)
        assertFalse(stateOf(id).has("timeline"))
    }

    @Test
    fun `tests line, follow-up, cancellation, caregiver and the doctor's view`() {
        seed()
        val id = write(mapOf("tests" to listOf("CBC", "HbA1c"), "followUpDays" to 7))
        stateOf(id).let {
            assertEquals("lab_pending", it["primary"].asText())
            assertEquals("pending", it["lab"]["state"].asText())
            assertTrue(it["followUpDate"].longValue() > 0)
            assertEquals("active", it["phase"].asText())
        }
        ok(pat, "rx_send_lab", mapOf("rxId" to id, "labId" to labU, "tests" to listOf("CBC", "HbA1c")))
        assertEquals("lab_sent", stateOf(id)["primary"].asText())
        val lo = ok(labU, "lab_orders")["items"][0]["id"].asText()
        ok(labU, "lab_order_status", mapOf("id" to lo, "status" to "received"))
        assertEquals("lab_received", stateOf(id)["primary"].asText())
        jdbc.update("UPDATE lab_orders SET status = 'reported', reported_at = now() WHERE id = ?", lo)
        stateOf(id).let {
            assertEquals("lab_reported", it["primary"].asText())
            // the follow-up is still ahead: active until that day
            assertEquals("active", it["phase"].asText())
        }
        jdbc.update("UPDATE prescriptions SET follow_up_date = CURRENT_DATE - 1 WHERE id = ?", id)
        stateOf(id).let {
            assertEquals("past", it["phase"].asText())
            assertTrue(it["followUpDate"].isNull)
        }
        val tl = ok(pat, "rx_get", mapOf("id" to id))["item"]["state"]["timeline"].map { it["kind"].asText() }
        assertEquals(setOf("written", "lab_sent", "lab_reported", "follow_up"), tl.toSet())
        assertEquals(4, tl.size)

        // the caregiver acting for the patient sees the same state
        val asP = mapOf("asUid" to pat)
        assertEquals(stateOf(id).toString(), stateOf(id, cg, asP).toString())
        assertTrue(ok(cg, "rx_get", asP + mapOf("id" to id))["item"]["state"]["timeline"].size() > 0)

        // the doctor cancels: past, muted, with the time; the doctor's own view has no state
        ok(doc, "rx_cancel", mapOf("id" to id))
        stateOf(id).let {
            assertEquals("cancelled", it["primary"].asText())
            assertEquals("past", it["phase"].asText())
            assertTrue(it["cancelledAt"].longValue() > 0)
        }
        assertEquals("cancelled", ok(pat, "rx_get", mapOf("id" to id))["item"]["state"]["timeline"].last()["kind"].asText())
        assertNull(ok(doc, "rx_get", mapOf("id" to id))["item"]["state"])
        assertNull(ok(doc, "rx_doctor_list")["items"][0]["state"])
    }

    @Test
    fun `a psychologist's notes stay hidden from the caregiver - state only on what is shown`() {
        seed()
        val id = write(mapOf("medicines" to meds))
        jdbc.update(
            "INSERT INTO prescriptions (id, code, doctor_uid, patient_uid, doctor_name, doctor_type, doctor_system, advice) VALUES ('psyrx', 'PSYC2345EF', ?, ?, 'কাউন্সেলর', 'psychologist', NULL, 'শ্বাসের ব্যায়াম')",
            doc, pat,
        )
        val own = mine()
        assertEquals(setOf(id, "psyrx"), own.map { it["id"].asText() }.toSet())
        assertEquals("written", own.first { it["id"].asText() == "psyrx" }["state"]["primary"].asText())
        val asP = mapOf("asUid" to pat)
        assertEquals(listOf(id), mine(cg, asP).map { it["id"].asText() })
        assertEquals(404, call(cg, "rx_get", asP + mapOf("id" to "psyrx")).status)
    }

    private fun rxAgo(id: String, days: Int) = jdbc.update("UPDATE prescriptions SET created_at = now() - make_interval(days => ?) WHERE id = ?", days, id)

    @Test
    fun `superseded by the same doctor for the same person - not by another doctor, not while an order is open`() {
        seed()
        val old = write(mapOf("medicines" to meds))
        rxAgo(old, 2)
        val newer = write(mapOf("medicines" to meds.take(1)))
        stateOf(old).let {
            assertEquals("past", it["phase"].asText())
            assertEquals("superseded", it["pastReason"].asText())
            assertEquals(newer, it["supersededBy"]["id"].asText())
        }
        assertEquals("active", stateOf(newer)["phase"].asText())
        assertTrue("superseded" in ok(pat, "rx_get", mapOf("id" to old))["item"]["state"]["timeline"].map { it["kind"].asText() })
        // an open order keeps the older one until it closes
        order("os", old, "sent")
        assertEquals("active", stateOf(old)["phase"].asText())
        setStatus("os", "withdrawn")
        assertEquals("superseded", stateOf(old)["pastReason"].asText())
        // a cancelled newer one does not supersede
        ok(doc, "rx_cancel", mapOf("id" to newer))
        assertEquals("active", stateOf(old)["phase"].asText())

        // another doctor's newer prescription for the same patient: nothing changes
        person("stDoctor2", "ডা. তানভীর রহমান")
        doctor("stDoctor2", "ডা. তানভীর রহমান", "A-777")
        val p2 = ok("stDoctor2", "rx_patient_add", mapOf("name" to "রহিমা বেগম", "phone" to "01711000001"))["patient"]
        val other = ok("stDoctor2", "rx_create", mapOf("rxPatientId" to p2["id"].asText(), "medicines" to meds))
        ok(pat, "rx_claim", mapOf("code" to other["code"].asText(), "phone" to "01711000001"))
        assertEquals("active", stateOf(old)["phase"].asText())

        // the same doctor, a family member on the same account: a different person
        jdbc.update(
            "INSERT INTO user_family_members (uid, member_id, position, name, relation, age, gender, phone) VALUES (?, 'm_1', 0, 'আব্দুল করিম', 'স্বামী', 60, 'male', '01811000002')",
            pat,
        )
        val pf = ok(doc, "rx_patient_add", mapOf("name" to "আব্দুল করিম", "phone" to "01811000002"))["patient"]
        val fam = ok(doc, "rx_create", mapOf("rxPatientId" to pf["id"].asText(), "medicines" to meds))
        ok(pat, "rx_claim", mapOf("code" to fam["code"].asText(), "phone" to "01811000002"))
        assertEquals("active", stateOf(old)["phase"].asText())
        // the family member's own newer one supersedes only theirs
        rxAgo(fam["id"].asText(), 1)
        val fam2 = ok(doc, "rx_create", mapOf("rxPatientId" to pf["id"].asText(), "tests" to listOf("CBC")))
        ok(pat, "rx_claim", mapOf("code" to fam2["code"].asText(), "phone" to "01811000002"))
        assertEquals(fam2["id"].asText(), stateOf(fam["id"].asText())["supersededBy"]["id"].asText())
        assertEquals("active", stateOf(old)["phase"].asText())
    }

    @Test
    fun `course end - 3 days after the longest medicine, a continuing medicine falls back to 30 days`() {
        seed()
        // the longest medicine is 10 days: written 12 days ago → active, 13 days ago → past
        val id = write(mapOf("medicines" to meds))
        rxAgo(id, 12)
        assertEquals("active", stateOf(id)["phase"].asText())
        rxAgo(id, 13)
        stateOf(id).let {
            assertEquals("past", it["phase"].asText())
            assertEquals("course_end", it["pastReason"].asText())
            assertTrue(it["courseEnd"].longValue() > 0)
        }
        // an open order keeps it
        order("oc", id, "accepted", mode = "online")
        assertEquals("active", stateOf(id)["phase"].asText())
        setStatus("oc", "cancelled")
        // (cancelled: it must not supersede the one below once that is moved back in time)
        ok(doc, "rx_cancel", mapOf("id" to id))

        // "চলবে" (days 0): no course end — 30 days after writing or the last activity
        val cont = write(mapOf("medicines" to listOf(mapOf("name" to "Rosuva 10", "form" to "Tablet", "dose" to "0+0+1", "days" to 0))))
        rxAgo(cont, 29)
        stateOf(cont).let {
            assertEquals("active", it["phase"].asText())
            assertTrue(it["courseEnd"].isNull)
        }
        rxAgo(cont, 31)
        assertEquals("stale", stateOf(cont)["pastReason"].asText())
        dispense(cont, 0 to 30)
        assertEquals("active", stateOf(cont)["phase"].asText())
    }

    @Test
    fun `rx_mark_done - the patient or a caregiver, never the doctor, and ongoing again`() {
        seed()
        val id = write(mapOf("medicines" to meds, "followUpDays" to 10))
        val done = ok(pat, "rx_mark_done", mapOf("id" to id, "done" to true))["item"]["state"]
        assertEquals("past", done["phase"].asText())
        assertEquals("done", done["pastReason"].asText())
        assertEquals("done", stateOf(id)["pastReason"].asText())
        assertTrue(done["timeline"].any { it["kind"].asText() == "marked_done" })
        // ongoing again
        val again = ok(pat, "rx_mark_done", mapOf("id" to id, "done" to false))["item"]["state"]
        assertEquals("active", again["phase"].asText())
        assertTrue(again["reopenedAt"].longValue() > 0)
        assertTrue(again["doneAt"].isNull)
        // the caregiver acting for the patient
        val asP = mapOf("asUid" to pat)
        assertEquals("done", ok(cg, "rx_mark_done", asP + mapOf("id" to id, "done" to true))["item"]["state"]["pastReason"].asText())
        assertEquals("done", stateOf(id, cg, asP)["pastReason"].asText())
        // never the writing doctor, nobody else, not a caregiver without a link
        fails(doc, "rx_mark_done", mapOf("id" to id, "done" to false), 404)
        person("stOther", "অন্য কেউ")
        fails("stOther", "rx_mark_done", mapOf("id" to id, "done" to false), 404)
        fails("stOther", "rx_mark_done", mapOf("asUid" to pat, "id" to id, "done" to false), 403)
        fails(pat, "rx_mark_done", mapOf("id" to "nope!", "done" to true), 404)
        assertEquals("done", stateOf(id)["pastReason"].asText())
        // the old fields are untouched
        assertEquals("active", mine().first { it["id"].asText() == id }["status"].asText())
        // the doctor's own view: no state
        assertNull(ok(doc, "rx_get", mapOf("id" to id))["item"]["state"])
    }

    @Test
    fun `rx_mark_done - a psychologist's notes stay out of the caregiver's reach`() {
        seed()
        jdbc.update(
            "INSERT INTO prescriptions (id, code, doctor_uid, patient_uid, doctor_name, doctor_type, doctor_system, advice) VALUES ('psyrx2', 'PSYD2345EF', ?, ?, 'কাউন্সেলর', 'psychologist', NULL, 'শ্বাসের ব্যায়াম')",
            doc, pat,
        )
        fails(cg, "rx_mark_done", mapOf("asUid" to pat, "id" to "psyrx2", "done" to true), 404)
        assertTrue(row("SELECT patient_done_at FROM prescriptions WHERE id = 'psyrx2'")["patient_done_at"] == null)
        assertEquals("done", ok(pat, "rx_mark_done", mapOf("id" to "psyrx2", "done" to true))["item"]["state"]["pastReason"].asText())
    }
}
