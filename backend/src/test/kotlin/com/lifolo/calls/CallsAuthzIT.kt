package com.lifolo.calls

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Security review R5: every call action against everyone who is not entitled — an unrelated user, the counterpart on
 * another record, a demo user, a caregiver acting for a party, the assistant of another doctor — plus the stored-role
 * rules (accept/decline only the callee, cancel only the caller) and rx/lab order id collisions.
 */
class CallsAuthzIT : CallsTestBase() {
    private val byCallId = listOf(
        "call_accept", "call_decline", "call_cancel", "call_end", "call_status", "call_upgrade", "call_upgrade_accept",
        "call_upgrade_decline",
    )

    @Test
    fun `call actions by callId - only the parties, the same 404 for everyone else`() {
        for (u in listOf("req", "don", "req2", "don2", "stranger", "demo-x", "cg")) person(u)
        bloodRequest("r1", "req", mapOf("don" to "accepted"))
        bloodRequest("r2", "req2", mapOf("don2" to "accepted"))
        jdbc.update("INSERT INTO care_links (patient_uid, caretaker_uid, status) VALUES ('req', 'cg', 'active')")
        val id = startOk("req", "blood", "r1", "don")
        for (who in listOf("stranger", "demo-x", "req2", "don2")) {
            for (a in byCallId) {
                val r = call(who, a, mapOf("callId" to id))
                assertEquals(404, r.status, "$who $a → $r")
            }
            assertEquals(404, call(who, "call_report", mapOf("callId" to id, "reason" to "spam")).status, who)
            assertEquals(404, call(who, "call_block", mapOf("peerUid" to "req", "callId" to id)).status, who)
        }
        // a caregiver of a party: never via asUid
        for (a in byCallId + "call_history" + "call_blocks") {
            assertEquals(403, call("cg", a, mapOf("asUid" to "req", "callId" to id)).status, "cg $a")
        }
        // stored roles: the caller cannot accept or decline, the callee cannot cancel
        assertEquals(404, call("req", "call_accept", mapOf("callId" to id)).status)
        assertEquals(404, call("req", "call_decline", mapOf("callId" to id)).status)
        assertEquals(404, call("don", "call_cancel", mapOf("callId" to id)).status)
        assertEquals("ringing", state(id))
        // unknown / malformed ids: the same 404
        for (bad in listOf("nope", "x".repeat(200), "../../etc", "")) assertEquals(404, call("req", "call_status", mapOf("callId" to bad)).status, bad)
        // nothing leaked into history
        for (who in listOf("stranger", "req2", "don2")) assertEquals(0, ok(who, "call_history")["items"].size(), who)
    }

    @Test
    fun `call_start - the counterpart on another record, demo, caregiver, another doctor's assistant`() {
        for (u in listOf("req", "don", "req2", "don2", "demo-d", "pat", "doc", "doc2", "asst2", "pat2")) person(u)
        bloodRequest("r1", "req", mapOf("don" to "accepted"), confirmed = setOf("don"))
        bloodRequest("r2", "req2", mapOf("don2" to "accepted", "demo-d" to "accepted"), confirmed = setOf("don2", "demo-d"))
        // the donor of r2 using r1, the requester of r1 naming r2's donor
        assertEquals(403, start("don2", "blood", "r1").status)
        assertEquals(403, start("req", "blood", "r1", "don2").status)
        assertEquals(403, start("req", "blood", "r2", "don").status)
        // demo world never reaches the real one
        assertEquals(403, start("demo-d", "blood", "r2").status)
        assertEquals(403, start("req2", "blood", "r2", "demo-d").status)
        // appointments: another doctor's assistant, another patient
        doctor("doc")
        doctor("doc2", "asst2@example.com")
        appointment("ap1", "pat", "doc")
        appointment("ap2", "pat2", "doc2")
        assertEquals(403, start("asst2", "appointment", "ap1").status)
        assertEquals(403, start("pat", "appointment", "ap1", "asst2").status)
        assertEquals(403, start("pat2", "appointment", "ap1").status)
        assertEquals(403, start("doc2", "appointment", "ap1").status)
        assertEquals(403, start("doc", "appointment", "ap2").status)
        // appt_join: the same people get the same 404
        jdbc.update("UPDATE appointments SET online = TRUE")
        for (who in listOf("asst2", "pat2", "doc2", "don")) assertEquals(404, call(who, "appt_join", mapOf("appointmentId" to "ap1")).status, who)
        // the peer named on the request never changes who the record says it is
        val r = start("pat", "appointment", "ap1", "doc")
        assertEquals(200, r.status)
        assertEquals("doc", r.body["peer"]["uid"].textValue())
    }

    @Test
    fun `rx and lab order ids that collide - each context looks only at its own table and owner`() {
        for (u in listOf("pat", "pat2", "shop", "labo", "doc")) person(u)
        pharmacy("shop")
        lab("labo")
        rxOrder("same1", "pat", "shop", "doc")
        labOrder("same1", "pat2", "labo")
        assertEquals("shop", start("pat", "rx_order", "same1").body["peer"]["uid"].textValue())
        endAll()
        assertEquals("labo", start("pat2", "lab_order", "same1").body["peer"]["uid"].textValue())
        endAll()
        assertEquals(403, start("pat", "lab_order", "same1").status)
        assertEquals(403, start("pat2", "rx_order", "same1").status)
        assertEquals(403, start("shop", "lab_order", "same1").status)
        assertEquals(403, start("labo", "rx_order", "same1").status)
        assertEquals(403, start("shop", "rx_order", "same1", "pat2").status)
    }
}
