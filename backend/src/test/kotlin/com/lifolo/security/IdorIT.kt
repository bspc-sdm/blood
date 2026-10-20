package com.lifolo.security

import com.fasterxml.jackson.databind.JsonNode
import com.lifolo.rx.RxLabTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * IDOR: one person's records through another person's account — prescriptions, lab orders and reports, appointments,
 * pharmacy orders, shop books, profile/phone, family, inbox, tickets, Q&A, listings' documents. Every check is by
 * the record's id (what a curious user would try), from a stranger, another professional of the same kind and a
 * caregiver without a link.
 */
class IdorIT : RxLabTest() {
    private val docA = "idDocA"
    private val docB = "idDocB"
    private val patA = "idPatA"
    private val patB = "idPatB"
    private val cgA = "idCareA"
    private val labA = "idLabA"
    private val labB = "idLabB"
    private val phA = "idShopA"
    private val phB = "idShopB"
    private val phoneA = "01711000001"
    private val phoneB = "01711000002"

    private fun shop(uid: String) {
        person(uid, "দোকান $uid")
        jdbc.update(
            """
            INSERT INTO pharmacies (uid, name, license_no, phone, district, upazila_id, address, lat, lng, open_at, close_at, status)
            VALUES (?, ?, 'DL-1', '01711111111', ?, 466, 'চরপাড়া', 24.75, 90.4, '08:00', '22:00', 'approved')
            """.trimIndent(),
            uid, "দোকান $uid", dist,
        )
    }

    private fun seed() {
        person(docA, "ডা. এ")
        doctor(docA, "ডা. এ")
        person(docB, "ডা. বি")
        doctor(docB, "ডা. বি")
        person(patA, "রোগী এ", phoneA)
        person(patB, "রোগী বি", phoneB)
        person(cgA, "কেয়ারগিভার এ")
        careLink(patA, cgA)
        person(labA, "ল্যাব এ")
        lab(labA, "ল্যাব এ")
        person(labB, "ল্যাব বি")
        lab(labB, "ল্যাব বি")
        shop(phA)
        shop(phB)
    }

    /** docA writes for patA (paper patient → claimed by patA): id, code */
    private fun rxForA(): Pair<String, String> {
        val p = ok(docA, "rx_patient_add", mapOf("name" to "রোগী এ", "age" to 40, "gender" to "female", "phone" to phoneA))["patient"]
        val rx = ok(
            docA, "rx_create",
            mapOf(
                "rxPatientId" to p["id"].asText(), "diagnosis" to "গোপন রোগনির্ণয়",
                "medicines" to listOf(mapOf("name" to "Napa 500", "form" to "Tablet", "dose" to "1+1+1", "days" to 3)),
                "tests" to listOf("CBC"),
            ),
        )
        ok(patA, "rx_claim", mapOf("code" to rx["code"].asText(), "phone" to phoneA))
        return rx["id"].asText() to rx["code"].asText()
    }

    private fun hasId(items: JsonNode, id: String) = items.any { it["id"]?.asText() == id }

    // ------------------------------------------------------------------ prescriptions

    @Test
    fun `prescriptions - only the patient (and caregiver) and the writing doctor`() {
        seed()
        val (rxId, code) = rxForA()
        val body = mapOf("id" to rxId, "rxId" to rxId)
        // the owner, the caregiver, the writer
        ok(patA, "rx_get", body)
        ok(cgA, "rx_get", body + mapOf("asUid" to patA))
        ok(docA, "rx_get", body)
        // anyone else: not found (no hint that it exists)
        for (u in listOf(patB, docB, labA, phA, cgA)) {
            fails(u, "rx_get", body, 404)
            fails(u, "rx_activity", body, 404)
        }
        for (u in listOf(patB, docB)) {
            fails(u, "rx_pharmacy_options", body + mapOf("district" to dist), 404)
            fails(u, "rx_send_pharmacy", body + mapOf("pharmacyId" to phA), 404)
            fails(u, "rx_lab_options", body + mapOf("district" to dist), 404)
            fails(u, "rx_send_lab", body + mapOf("labId" to labA), 404)
            assertFalse(hasId(ok(u, "rx_mine")["items"], rxId), u)
        }
        fails(docB, "rx_cancel", body, 404)
        assertFalse(hasId(ok(docB, "rx_doctor_list")["items"], rxId))
        val pid = row("SELECT rx_patient_id FROM prescriptions WHERE id = ?", rxId)["rx_patient_id"] as String
        fails(docB, "rx_patient_history", mapOf("rxPatientId" to pid), 404)
        fails(docB, "rx_patients", mapOf("doctorUid" to docA), 403)
        fails(docB, "rx_patient_add", mapOf("doctorUid" to docA, "name" to "X", "phone" to "01711000009"), 403)
        fails(patA, "rx_patients", mapOf("doctorUid" to docA), 403)
        // the caregiver acts as the patient, never as the doctor
        fails(cgA, "rx_patient_history", mapOf("asUid" to patA, "rxPatientId" to pid), 403)
        // claiming with the code needs the patient's own phone; already claimed → never moves to another account
        fails(patB, "rx_claim", mapOf("code" to code, "phone" to phoneB), 400)
        ok(patB, "family_set", mapOf("family" to listOf(mapOf("name" to "রোগী এ", "phone" to phoneA))))
        fails(patB, "rx_claim", mapOf("code" to code, "phone" to phoneA), 409)
        assertEquals(patA, row("SELECT patient_uid FROM prescriptions WHERE id = ?", rxId)["patient_uid"])
        // a pharmacy opening it by code sees what it needs to dispense — not the patient's phone or the diagnosis
        val view = ok(phB, "rx_pharmacy_open", mapOf("code" to code))
        assertFalse(leaks(view, phoneA), view.toString())
        assertFalse(leaks(view, "গোপন রোগনির্ণয়"), view.toString())
        // not a pharmacy / not approved: no
        fails(patB, "rx_pharmacy_open", mapOf("code" to code), 403)
        jdbc.update("UPDATE pharmacies SET status = 'pending' WHERE uid = ?", phB)
        fails(phB, "rx_pharmacy_open", mapOf("code" to code), 403)
        fails(phB, "rx_dispense", mapOf("code" to code, "items" to listOf(mapOf("i" to 0, "qty" to 1))), 403)
    }

    @Test
    fun `claiming by code + phone brings that one prescription, not the patient's history with the doctor`() {
        seed()
        val p = ok(docA, "rx_patient_add", mapOf("name" to "রোগী এ", "phone" to phoneA))["patient"]["id"].asText()
        val older = ok(docA, "rx_create", mapOf("rxPatientId" to p, "diagnosis" to "পুরোনো গোপন", "advice" to "x"))["id"].asText()
        val seen = ok(docA, "rx_create", mapOf("rxPatientId" to p, "advice" to "এই কাগজটা"))
        // someone who saw this paper and knows the phone number (typed into their own profile / family list)
        ok(patB, "family_set", mapOf("family" to listOf(mapOf("name" to "রোগী এ", "phone" to phoneA))))
        val r = ok(patB, "rx_claim", mapOf("code" to seen["code"].asText(), "phone" to phoneA))
        assertEquals(1, r["added"].intValue())
        assertEquals(listOf(seen["id"].asText()), ok(patB, "rx_mine")["items"].map { it["id"].asText() })
        assertEquals(null, row("SELECT patient_uid FROM prescriptions WHERE id = ?", older)["patient_uid"], "the earlier one stays with the doctor")
        fails(patB, "rx_get", mapOf("id" to older), 404)
        // the real patient: their own code still works for a prescription nobody claimed (the paper patient is never linked)
        val code = row("SELECT code FROM prescriptions WHERE id = ?", older)["code"] as String
        assertEquals(1, ok(patA, "rx_claim", mapOf("code" to code, "phone" to phoneA))["added"].intValue())
        // a later prescription for the same paper patient goes to nobody's account until someone adds it by its code
        val later = ok(docA, "rx_create", mapOf("rxPatientId" to p, "diagnosis" to "নতুন গোপন", "advice" to "y"))
        assertEquals(null, row("SELECT patient_uid FROM prescriptions WHERE id = ?", later["id"].asText())["patient_uid"])
        assertFalse(hasId(ok(patB, "rx_mine")["items"], later["id"].asText()))
        assertFalse(hasId(ok(patA, "rx_mine")["items"], later["id"].asText()))
        fails(patB, "rx_get", mapOf("id" to later["id"].asText()), 404)
        assertEquals(1, ok(patA, "rx_claim", mapOf("code" to later["code"].asText(), "phone" to phoneA))["added"].intValue())
        fails(patB, "rx_claim", mapOf("code" to later["code"].asText(), "phone" to phoneA), 409)
        // a link left from before the change (V604 clears them; a stray one is ignored): the next one still stays with the doctor
        jdbc.update("UPDATE rx_patients SET linked_uid = ? WHERE id = ?", patA, p)
        val afterOld = ok(docA, "rx_create", mapOf("rxPatientId" to p, "advice" to "z"))["id"].asText()
        assertEquals(null, row("SELECT patient_uid FROM prescriptions WHERE id = ?", afterOld)["patient_uid"])
    }

    // ------------------------------------------------------------------ lab orders and reports

    @Test
    fun `lab orders and reports - the patient, that lab, and the doctor only after the review is paid`() {
        seed()
        val (rxId, _) = rxForA()
        ok(patA, "rx_send_lab", mapOf("rxId" to rxId, "labId" to labA))
        val orderId = ok(labA, "lab_orders")["items"][0]["id"].asText()
        val id = mapOf("id" to orderId)
        // the other lab never sees or touches it
        assertFalse(hasId(ok(labB, "lab_orders")["items"], orderId))
        fails(labB, "lab_form", id, 404)
        fails(labB, "lab_order_status", id + mapOf("status" to "received"), 404)
        fails(labB, "lab_report", id + mapOf("items" to listOf(mapOf("test" to "CBC", "result" to "ভুয়া"))), 404)
        fails(labB, "lab_home_confirm", id + mapOf("date" to 0, "time" to "10:00"), 404)
        fails(labB, "lab_home_decline", id, 404)
        // the real lab reports
        ok(labA, "lab_report", id + mapOf("items" to listOf(mapOf("test" to "CBC", "result" to "Hb 9.1 গোপন"))))
        assertTrue(leaks(ok(patA, "lab_order_get", id), "গোপন"))
        assertTrue(leaks(ok(cgA, "lab_order_get", id + mapOf("asUid" to patA)), "গোপন"))
        assertTrue(leaks(ok(labA, "lab_order_get", id), "গোপন"))
        for (u in listOf(patB, labB, docB, phA)) fails(u, "lab_order_get", id, 404)
        // the writing doctor: only through a paid review (no review set up → 402, never the report)
        val doc = call(docA, "lab_order_get", id)
        assertEquals(402, doc.status, doc.toString())
        assertFalse(leaks(doc.body, "গোপন"))
        // home collection changes and the review payment: the patient only
        fails(patB, "lab_home_cancel", id, 404)
        fails(patB, "lab_home_change", id + mapOf("home" to mapOf("address" to "x")), 404)
        fails(patB, "review_pay", id + mapOf("trxId" to "ABC123456"), 404)
        fails(docB, "reviews_doctor", mapOf("doctorUid" to docA), 403)
        fails(docB, "review_confirm", id + mapOf("ok" to true), 404)
        fails(docB, "review_done", id + mapOf("comment" to "x"), 404)
        fails(patA, "review_settings", mapOf("set" to true, "enabled" to true, "noFee" to true), 403)
        // a lab's own price list and ranges: only an approved lab
        fails(patB, "lab_prices_get", emptyMap(), 403)
        fails(patB, "lab_ranges_set", mapOf("ranges" to emptyList<Any>()), 403)
    }

    // ------------------------------------------------------------------ appointments

    private fun appointment(id: String, patient: String, provider: String, status: String, serial: Int? = null) {
        jdbc.update(
            """
            INSERT INTO appointments (id, patient_uid, provider_uid, patient_name, provider_name, type, chamber, visit_date, note, status, serial)
            VALUES (?, ?, ?, 'রোগীর নাম', 'ডা.', 'doctor', 'সিটি হাসপাতাল', CURRENT_DATE, 'গোপন সমস্যা', ?, ?)
            """.trimIndent(),
            id, patient, provider, status, serial,
        )
    }

    @Test
    fun `appointments - only its patient and doctor, phones only once confirmed`() {
        seed()
        appointment("apA", patA, docA, "confirmed", 1)
        appointment("apB", patB, docA, "pending")
        val idA = mapOf("id" to "apA")
        for (u in listOf(patB, docB, cgA)) {
            val r = call(u, "appointment_update", idA + mapOf("op" to "cancel"))
            assertTrue(r.status in 400..404, "$u: $r")
            fails(u, "appointment_queue", idA, 404)
            fails(u, "appointment_late", idA + mapOf("minutes" to 10), 404)
            assertFalse(hasId(ok(u, "my_appointments")["items"], "apA"), u)
        }
        assertEquals("confirmed", row("SELECT status FROM appointments WHERE id = 'apA'")["status"])
        // the doctor's queue: the doctor or their assistant
        fails(docB, "queue_today", mapOf("doctorUid" to docA), 403)
        fails(docB, "queue_control", mapOf("doctorUid" to docA, "op" to "next"), 403)
        fails(patA, "queue_today", emptyMap(), 403)
        fails(docB, "appointment_late_reply", idA + mapOf("op" to "ok"), 403)
        // the caregiver of patA sees patA's appointment
        assertTrue(hasId(ok(cgA, "my_appointments", mapOf("asUid" to patA))["items"], "apA"))
        // phones: the confirmed patient's number to the doctor, never a pending one's
        val items = ok(docA, "my_appointments")["items"]
        assertEquals(phoneA, items.first { it["id"].asText() == "apA" }["otherPhone"].asText())
        assertEquals("", items.first { it["id"].asText() == "apB" }["otherPhone"].asText())
        assertFalse(leaks(items, patA) || leaks(items, patB), "the doctor gets a short key, not the uid")
        // the patient may cancel, never confirm their own request
        val selfConfirm = call(patB, "appointment_update", mapOf("id" to "apB", "op" to "confirm"))
        assertEquals(400, selfConfirm.status, selfConfirm.toString())
        assertEquals("pending", row("SELECT status FROM appointments WHERE id = 'apB'")["status"])
    }

    @Test
    fun `caregiver of a doctor or lab - the patient side only, never the professional side`() {
        seed()
        val cgD = "idCareDoc"
        val cgL = "idCareLab"
        person(cgD, "ডাক্তারের কেয়ারগিভার")
        person(cgL, "ল্যাবের কেয়ারগিভার")
        careLink(docA, cgD)
        careLink(labA, cgL)
        // docA as a doctor: patA confirmed (phone visible to docA), patB pending; docA as a patient of docB
        appointment("apP1", patA, docA, "confirmed", 1)
        appointment("apP2", patB, docA, "pending")
        appointment("apSelf", docA, docB, "confirmed", 3)
        val asDoc = mapOf("asUid" to docA)
        // the doctor still sees both sides
        assertTrue(listOf("apP1", "apP2", "apSelf").all { id -> hasId(ok(docA, "my_appointments")["items"], id) })
        // the caregiver: only docA's own appointment as a patient — no patients, phones or their notes
        val items = ok(cgD, "my_appointments", asDoc)["items"]
        assertEquals(listOf("apSelf"), items.map { it["id"].asText() })
        assertTrue(items.all { it["role"].asText() == "patient" })
        assertFalse(leaks(items, phoneA) || leaks(items, phoneB), items.toString())
        // cannot confirm, decline or cancel the doctor's appointments (nothing changes, no push to the patients)
        val before = push.messages.size
        for ((id, op) in listOf("apP2" to "confirm", "apP2" to "decline", "apP2" to "cancel", "apP1" to "cancel")) {
            fails(cgD, "appointment_update", asDoc + mapOf("id" to id, "op" to op), 404)
        }
        assertEquals("confirmed", row("SELECT status FROM appointments WHERE id = 'apP1'")["status"])
        assertEquals("pending", row("SELECT status FROM appointments WHERE id = 'apP2'")["status"])
        assertFalse(push.messages.drop(before).any { it.uid == patA || it.uid == patB })
        fails(cgD, "appointment_queue", asDoc + mapOf("id" to "apP1"), 404)
        fails(cgD, "appointment_late", asDoc + mapOf("id" to "apP1", "minutes" to 10), 404)
        // the doctor's queue and work lists are not caregiver actions at all
        fails(cgD, "queue_today", asDoc, 403)
        fails(cgD, "appointment_late_reply", asDoc + mapOf("id" to "apP1", "op" to "ok"), 403)
        fails(cgD, "rx_patients", asDoc, 403)
        // prescriptions docA wrote for patients: never; docA's own prescription as a patient: yes
        val (rxId, _) = rxForA()
        fails(cgD, "rx_get", asDoc + mapOf("id" to rxId), 404)
        fails(cgD, "rx_activity", asDoc + mapOf("rxId" to rxId), 404)
        assertFalse(hasId(ok(cgD, "rx_mine", asDoc)["items"], rxId))
        val own = ok(docB, "rx_create", mapOf("appointmentId" to "apSelf", "advice" to "নিজের প্রেসক্রিপশন"))["id"].asText()
        ok(cgD, "rx_get", asDoc + mapOf("id" to own))
        ok(cgD, "rx_activity", asDoc + mapOf("rxId" to own))
        // lab work: the lab's caregiver never sees the lab's orders; the doctor's caregiver never sees reports as the doctor
        ok(patA, "rx_send_lab", mapOf("rxId" to rxId, "labId" to labA))
        val orderId = ok(labA, "lab_orders")["items"][0]["id"].asText()
        ok(labA, "lab_report", mapOf("id" to orderId, "items" to listOf(mapOf("test" to "CBC", "result" to "Hb 9.1 গোপন"))))
        fails(cgL, "lab_order_get", mapOf("asUid" to labA, "id" to orderId), 404)
        fails(cgD, "lab_order_get", asDoc + mapOf("id" to orderId), 404)
        assertTrue(leaks(ok(cgA, "lab_order_get", mapOf("asUid" to patA, "id" to orderId)), "গোপন"))
        // the patient side still works for the caregiver: cancel docA's own appointment
        ok(cgD, "appointment_update", asDoc + mapOf("id" to "apSelf", "op" to "cancel"))
        assertEquals("cancelled", row("SELECT status FROM appointments WHERE id = 'apSelf'")["status"])
    }

    // ------------------------------------------------------------------ pharmacy orders and shop books

    @Test
    fun `pharmacy orders - the patient and that pharmacy only`() {
        seed()
        val (rxId, _) = rxForA()
        val orderId = ok(patA, "rx_send_pharmacy", mapOf("rxId" to rxId, "pharmacyId" to phA))["id"].asText()
        assertFalse(hasId(ok(phB, "rx_orders_pharmacy")["items"], orderId))
        fails(phB, "rx_order_update", mapOf("id" to orderId, "status" to "ready"), 404)
        fails(patB, "rx_order_withdraw", mapOf("id" to orderId), 404)
        fails(patB, "rx_order_update", mapOf("id" to orderId, "status" to "ready"), 403)
        assertEquals("sent", row("SELECT status FROM rx_orders WHERE id = ?", orderId)["status"])
        assertTrue(hasId(ok(phA, "rx_orders_pharmacy")["items"], orderId))
    }

    @Test
    fun `shop books - one shop never reads or changes another shop's books`() {
        seed()
        val item = ok(phA, "books_item_save", mapOf("name" to "Napa 500", "qty" to 50, "cost" to 1, "price" to 2))["item"]["id"].asText()
        ok(phA, "books_purchase_add", mapOf("supplier" to "ডিপো", "lines" to listOf(mapOf("itemId" to item, "qty" to 10, "unitCost" to 1))))
        ok(phA, "books_sale_add", mapOf("lines" to listOf(mapOf("itemId" to item, "qty" to 2))))
        val purchase = ok(phA, "books_purchases")["items"][0]["id"].asText()
        val sale = ok(phA, "books_sales")["items"][0]["id"].asText()
        val qty = { (row("SELECT qty FROM shop_items WHERE shop_uid = ? AND id = ?", phA, item)["qty"] as Number).toDouble() }
        val before = qty()
        assertEquals(58.0, before)
        // phB with phA's ids
        assertTrue(ok(phB, "books_items")["items"].size() == 0)
        assertTrue(ok(phB, "books_sales")["items"].size() == 0)
        assertTrue(ok(phB, "books_purchases")["items"].size() == 0)
        call(phB, "books_item_delete", mapOf("id" to item))
        fails(phB, "books_item_stock", mapOf("id" to item, "on" to false), 404)
        fails(phB, "books_sale_delete", mapOf("id" to sale), 404)
        fails(phB, "books_purchase_delete", mapOf("id" to purchase), 404)
        call(phB, "books_item_save", mapOf("id" to item, "name" to "Napa 500", "qty" to 0))
        call(phB, "books_sale_add", mapOf("lines" to listOf(mapOf("itemId" to item, "qty" to 5))))
        call(phB, "books_adjust", mapOf("kind" to "writeoff", "itemId" to item, "qty" to 5))
        assertEquals(before, qty())
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM shop_sales WHERE shop_uid = ?", Long::class.java, phA))
        // a person without a pharmacy has no books
        fails(patB, "books_items", emptyMap(), 403)
        fails(patB, "books_today", emptyMap(), 403)
    }

    // ------------------------------------------------------------------ account data

    @Test
    fun `profile, phone, family and inbox - always the caller's own`() {
        seed()
        ok(patA, "family_set", mapOf("family" to listOf(mapOf("name" to "মা", "phone" to "01811000003", "age" to 70))))
        jdbc.update("INSERT INTO inbox_items (id, uid, title, body) VALUES ('ibA1', ?, 'গোপন বার্তা', '')", patA)
        // whatever ids are sent, the profile is the caller's
        val p = ok(patB, "profile_get", mapOf("uid" to patA, "id" to patA))["profile"]
        assertEquals("রোগী বি", p["name"].asText())
        assertFalse(leaks(p, phoneA))
        // only the app's own fields can be written
        for (f in listOf("uid", "verifiedPhone", "disabled", "isAdmin", "email", "family", "donorGeo")) {
            fails(patB, "profile_update", mapOf("set" to mapOf(f to "x")), 403)
        }
        assertEquals("রোগী বি", row("SELECT name FROM user_profiles WHERE uid = ?", patB)["name"])
        // family: own only; a caregiver through the link
        assertTrue(ok(patB, "family_get", mapOf("uid" to patA))["family"].none { it["name"].asText() == "মা" })
        fails(patB, "family_get", mapOf("asUid" to patA), 403)
        assertEquals("মা", ok(cgA, "family_get", mapOf("asUid" to patA))["family"][0]["name"].asText())
        fails(cgA, "family_set", mapOf("asUid" to patA, "family" to emptyList<Any>()), 403)
        // inbox
        assertTrue(ok(patA, "inbox")["items"].size() > 0)
        assertTrue(ok(patB, "inbox", mapOf("uid" to patA))["items"].size() == 0)
        fails(cgA, "inbox", mapOf("asUid" to patA), 403)
        ok(patB, "inbox_read", mapOf("uid" to patA))
        assertTrue(ok(patA, "inbox")["items"].any { !it["read"].booleanValue() })
        // a device token can only be removed from one's own account
        jdbc.update("INSERT INTO device_tokens (token, uid) VALUES ('tok-of-patA-xxxxxxxxxxxx', ?)", patA)
        ok(patB, "device_unregister", mapOf("token" to "tok-of-patA-xxxxxxxxxxxx"))
        assertEquals(patA, row("SELECT uid FROM device_tokens WHERE token = 'tok-of-patA-xxxxxxxxxxxx'")["uid"])
        // phone verification codes are per account
        fails(patB, "phone_verify_code", mapOf("code" to "123456", "uid" to patA), 400)
    }

    @Test
    fun `care links - only the two people of a link change it`() {
        seed()
        person("idCareB", "অন্য")
        fails("idCareB", "care_respond", mapOf("patientUid" to patA, "accept" to true), 404)
        ok(patB, "care_remove", mapOf("caretakerUid" to cgA))
        ok("idCareB", "care_remove", mapOf("patientUid" to patA))
        assertEquals("active", row("SELECT status FROM care_links WHERE patient_uid = ? AND caretaker_uid = ?", patA, cgA)["status"])
        val list = ok(patB, "care_list")
        assertTrue(list["caretakers"].size() == 0 && list["caringFor"].size() == 0)
        // the caregiver cannot add more caregivers, accept or remove for the patient
        fails(cgA, "care_invite", mapOf("asUid" to patA, "email" to "x@example.com"), 403)
        fails(cgA, "care_list", mapOf("asUid" to patA), 403)
    }

    // ------------------------------------------------------------------ tickets, Q&A, listings

    @Test
    fun `tickets, questions and listing documents - owner only`() {
        seed()
        val t = ok(patA, "ticket_create", mapOf("category" to "other", "text" to "আমার গোপন সমস্যা নিয়ে লিখছি"))["id"].asText()
        fails(patB, "ticket_reply", mapOf("id" to t, "text" to "অন্য কেউ"), 404)
        assertFalse(leaks(ok(patB, "tickets_mine"), "গোপন সমস্যা"))
        val q = ok(patA, "ask", mapOf("text" to "আমার প্রশ্ন", "anonymous" to true))["id"].asText()
        fails(patB, "edit_question", mapOf("id" to q, "text" to "বদলানো"), 403)
        fails(patB, "delete_question", mapOf("id" to q), 403)
        assertEquals("আমার প্রশ্ন", row("SELECT text FROM questions WHERE id = ?", q)["text"])
        // the anonymous asker's name is not shown to others (guest or logged in)
        val asGuest = call(null, "question", mapOf("id" to q))
        assertFalse(leaks(asGuest.body, "রোগী এ"), asGuest.toString())
        assertFalse(leaks(ok(patB, "question", mapOf("id" to q)), "রোগী এ"))
        assertFalse(leaks(ok(patB, "questions"), patA))
        // a listing's documents: its owner only
        fails(patB, "verify_docs_upload", mapOf("kind" to "pharmacy", "id" to phA, "docs" to emptyMap<String, Any>()), 404)
        fails(patB, "verify_docs_upload", mapOf("kind" to "lab", "id" to labA, "docs" to emptyMap<String, Any>()), 404)
        // someone else's listing cannot be deleted or paused
        call(patB, "pharmacy_delete", mapOf("id" to phA, "uid" to phA))
        call(patB, "lab_delete", mapOf("id" to labA, "uid" to labA))
        call(patB, "pharmacy_pause", mapOf("id" to phA, "until" to System.currentTimeMillis() + DAY))
        assertEquals("approved", row("SELECT status FROM pharmacies WHERE uid = ?", phA)["status"])
        assertEquals(null, row("SELECT closed_until FROM pharmacies WHERE uid = ?", phA)["closed_until"])
        assertEquals("approved", row("SELECT status FROM labs WHERE uid = ?", labA)["status"])
    }
}
