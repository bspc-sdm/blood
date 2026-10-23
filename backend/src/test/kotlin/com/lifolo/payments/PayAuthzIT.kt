package com.lifolo.payments

import com.lifolo.common.action.ActionRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Payments security review P6/P14: every pay action against {the payer, an active / removed caregiver, another patient,
 * the owner, another owner, the doctor's current / removed assistant, another doctor's assistant, a demo user, an
 * admin}. Ids from the body never decide whose a request is: the stored row does. Rights are read on every call.
 */
class PayAuthzIT : PayTestBase() {
    @Autowired lateinit var registry: ActionRegistry

    private val OTHER_DOC = "pyDoc2"
    private val OTHER_ASST = "pyAsst2"

    /** a chamber invoice-due request of PAT at DOC, claimed */
    private fun seed(): String {
        doctor(DOC, "asst@example.com")
        doctor(OTHER_DOC)
        jdbc.update("INSERT INTO provider_assistants (provider_uid, email, position) VALUES (?, 'asst2@example.com', 0)", OTHER_DOC)
        user(OTHER_ASST, mapOf("name" to "অন্য সহকারী"), email = "asst2@example.com")
        person(PAT)
        person(PAT2)
        careLink()
        careLink(PAT, "pyCgOld", status = "rejected")
        shop(PH2)
        person("demo-pyx")
        admin()
        ready("chamber", DOC, DOC)
        val inv = dueInvoice("chamber", DOC, DOC, 600, 100)
        val id = ok(PAT, "pay_due_start", mapOf("invoiceId" to inv.id))["request"]["id"].textValue()
        assertEquals(200, claim(PAT, id, "AZ12345678").status)
        return id
    }

    private fun status(uid: String, action: String, body: Map<String, Any?>): Int = call(uid, action, body).status

    @Test
    fun `the payer's actions - only the payer or an active caregiver`() {
        val id = seed()
        val strangers = listOf(PAT2, PH2, DOC.takeIf { false } ?: OTHER_DOC, OTHER_ASST, "demo-pyx", ADMIN)
        for (action in listOf("pay_request_get", "pay_claim", "pay_refund_ack", "pay_report")) {
            val body = mapOf("id" to id, "method" to "bkash", "trxId" to "AZ87654321", "senderLast4" to "1234", "reason" to "scam")
            for (u in strangers) assertEquals(404, status(u, action, body), "$action as $u")
            // a removed caregiver: refused before anything runs
            assertEquals(403, status("pyCgOld", action, body + ("asUid" to PAT)), "$action as a removed caregiver")
            // the same body for an id that does not exist
            assertEquals(404, status(PAT2, action, body + ("id" to "nope")))
        }
        assertEquals(200, status(CG, "pay_request_get", mapOf("id" to id, "asUid" to PAT)))
        assertEquals(200, status(PAT, "pay_request_get", mapOf("id" to id)))
        // the business side reads it too, with the history
        assertTrue(ok(ASST, "pay_request_get", mapOf("id" to id))["events"].size() >= 2)
        // a patient never starts a request on another person's receipt
        val other = dueInvoice("chamber", DOC, DOC, 300, 0, patient = PAT2)
        assertEquals(404, status(PAT, "pay_due_start", mapOf("invoiceId" to other.id)))
        assertEquals(404, status(CG, "pay_due_start", mapOf("invoiceId" to other.id, "asUid" to PAT)))
    }

    @Test
    fun `the business's actions - the owner, the doctor's current assistant (no refund, no CSV, no numbers), nobody else`() {
        val id = seed()
        val checks = mapOf("id" to id, "checks" to CHECKS, "reason" to "not_received", "trxId" to "RZ12345678")
        for (action in listOf("pay_confirm", "pay_reject", "pay_refund")) {
            for (u in listOf(PAT, PAT2, PH2, OTHER_DOC, OTHER_ASST, "demo-pyx", ADMIN)) assertEquals(404, status(u, action, checks), "$action as $u")
            assertEquals(403, status(CG, action, checks + ("asUid" to PAT)), "$action as a caregiver (not a care action)")
        }
        val biz = mapOf("kind" to "chamber", "bizId" to DOC)
        for (action in listOf("pay_list_biz", "pay_summary", "pay_export_csv", "pay_methods_get", "pay_methods_set", "pay_methods_freeze")) {
            for (u in listOf(PAT, PH2, OTHER_DOC, OTHER_ASST)) assertEquals(403, status(u, action, biz + ("methods" to listOf(bkash("01711000009")))), "$action as $u")
        }
        // the current assistant: confirm and reject, lists and summary — not refunds, numbers, freeze or the CSV
        assertEquals(200, status(ASST, "pay_list_biz", biz))
        assertEquals(200, status(ASST, "pay_summary", biz))
        assertEquals(200, status(ASST, "pay_methods_get", biz))
        for (action in listOf("pay_export_csv", "pay_methods_set", "pay_methods_freeze")) {
            val r = call(ASST, action, biz + ("methods" to listOf(bkash("01711000009"))))
            assertEquals(403, r.status, "$action: $r")
            assertEquals("owner_only", r.code)
        }
        assertEquals(200, confirm(ASST, id).status)
        assertEquals("owner_only", call(ASST, "pay_refund", mapOf("id" to id, "trxId" to "RZ12345678")).code)
        // removed from the doctor's list: no access at once
        jdbc.update("DELETE FROM provider_assistants WHERE provider_uid = ?", DOC)
        assertEquals(404, status(ASST, "pay_request_get", mapOf("id" to id)))
        assertEquals(403, status(ASST, "pay_list_biz", biz))
        // the owner
        assertEquals(200, status(DOC, "pay_refund", mapOf("id" to id, "trxId" to "RZ12345678")))
    }

    @Test
    fun `create - only the business of this very source, the amount and the business come from the stored row`() {
        seed()
        shop(PH)
        ready("pharmacy", PH, PH)
        acceptedOrder("o1")
        for (u in listOf(PAT, PAT2, PH2, DOC, ASST, "demo-pyx", ADMIN)) {
            assertEquals(404, status(u, "pay_request_create", mapOf("refType" to "rx_order", "refId" to "o1", "purpose" to "delivery")), u)
        }
        // a client amount / bizId / kind is ignored
        val r = ok(PH, "pay_request_create", mapOf("refType" to "rx_order", "refId" to "o1", "purpose" to "delivery", "amount" to 5, "bizId" to PH2, "kind" to "lab"))["request"]
        assertEquals(listOf("pharmacy", PH, "75"), listOf(r["kind"].textValue(), r["bizId"].textValue(), r["amount"].asText()))
        // the refType decides the table: an rx order id as a lab order is not found
        assertEquals(404, status(PH, "pay_request_create", mapOf("refType" to "lab_order", "refId" to "o1", "purpose" to "lab")))
        assertEquals(400, status(PH, "pay_request_create", mapOf("refType" to "rx_order", "refId" to "o1", "purpose" to "lab")))
        // ids are random, never sequential
        assertTrue(Regex("^[A-Za-z0-9_-]{16,}$").matches(r["id"].textValue()))
    }

    @Test
    fun `every pay action registered is covered by this matrix`() {
        val covered = setOf(
            "pay_methods_get", "pay_methods_set", "pay_methods_freeze", "pay_request_create", "pay_due_start", "pay_request_get", "pay_claim",
            "pay_confirm", "pay_reject", "pay_refund", "pay_refund_ack", "pay_report", "pay_list_biz", "pay_summary", "pay_export_csv",
        )
        val pay = registry.names().filter { it.startsWith("pay_") }.toSet()
        assertEquals(covered, pay, "a new pay_* action needs its row in PayAuthzIT")
        // the admin ones: 403 for everyone else
        seed()
        for (a in registry.names().filter { it.startsWith("admin_pay") || it == "admin_pgw_refund" }) {
            assertEquals(403, status(DOC, a, mapOf("id" to "x", "kind" to "chamber", "bizId" to DOC)), a)
        }
    }
}
