package com.lifolo.payments

import com.lifolo.ambulance.AmbClock
import com.lifolo.ambulance.AmbTestBase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Lifolo Pay for ambulances (PAYMENTS.md): the fare only after ambreq_finish, never before, never required */
class PayAmbulanceIT : AmbTestBase() {
    private val P = "pa-patient"
    private val LOCAL = mapOf("kind" to "upazila", "upazilaId" to SADAR)
    private val base get() = mapOf("district" to MYM, "upazilaId" to SADAR, "lat" to at(0.0, 0.0).first, "lng" to at(0.0, 0.0).second, "gps" to true)

    private fun seed() {
        AmbClock.setDhaka("2026-10-19 12:00")
        person(P)
        amb("a1", "d1", base = at(1.0, 0.0), chart = fc(800, listOf(toDhaka(6000)), oxygen = 300))
        ok("d1", "pay_methods_set", mapOf("kind" to "ambulance", "bizId" to "a1", "methods" to listOf(mapOf("method" to "bkash", "number" to "01711000001", "type" to "personal"))))
        jdbc.update("UPDATE pay_methods SET active_since = active_since - interval '2 days' WHERE biz_id = 'a1'")
    }

    private fun trip(): String {
        val id = call(P, "ambreq_create", base + mapOf("pickup" to "চরপাড়া", "phone" to "01811000000", "dest" to LOCAL, "maxFare" to 2000)).body["id"].textValue()
        ok("d1", "ambreq_accept", mapOf("id" to id))
        ok(P, "ambreq_agree", mapOf("id" to id, "ambulanceId" to "a1"))
        return id
    }

    private fun create(id: String) = call("d1", "pay_request_create", mapOf("refType" to "ambreq", "refId" to id, "purpose" to "ambulance_fare"))

    @Test
    fun `the fare - not before ambreq_finish, payLater issues the receipt unpaid with the fare request, paid by bKash later`() {
        seed()
        val id = trip()
        // during the trip: no payment request (cash always works; nothing is a precondition for the ride)
        val early = create(id)
        assertEquals(409, early.status, early.toString())
        assertEquals("not_finished", early.code)
        // finished with "pay later": the receipt is due, the patient gets the fare request at once
        val fin = ok("d1", "ambreq_finish", mapOf("reqId" to id, "fare" to 800, "payLater" to true))
        assertEquals(true, fin["payLater"].booleanValue())
        assertEquals(800.0, fin["invoice"]["due"].asDouble())
        val r = fin["payRequest"]
        assertEquals(listOf("ambulance_fare", "ambreq", id, "a1", P), listOf(r["purpose"], r["refType"], r["refId"], r["bizId"], r["payerUid"]).map { it.textValue() })
        assertEquals(800.0, r["amount"].asDouble())
        assertTrue(r["expiresAt"].longValue() > System.currentTimeMillis() + 6 * DAY)
        assertEquals(r["id"].textValue(), create(id).body["request"]["id"].textValue(), "the same live request")
        // the patient pays by bKash, the driver checks his wallet and confirms: the receipt is paid
        assertEquals(200, call(P, "pay_claim", mapOf("id" to r["id"].textValue(), "method" to "bkash", "trxId" to "AM12345678", "senderLast4" to "5678")).status)
        val cf = ok("d1", "pay_confirm", mapOf("id" to r["id"].textValue(), "checks" to mapOf("trxId" to true, "amount" to true, "last4" to true)))
        assertEquals(listOf(800.0, 0.0), listOf(cf["invoice"]["paid"].asDouble(), cf["invoice"]["due"].asDouble()))
        assertEquals("bkash", cf["invoice"]["payments"][0]["method"].textValue())
    }

    @Test
    fun `the fare - a trip paid in cash at the end has nothing due, a trip closed without a receipt is not finished for payments`() {
        seed()
        val id = trip()
        ok("d1", "ambreq_finish", mapOf("reqId" to id, "fare" to 800, "method" to "cash"))
        assertEquals("nothing_due", create(id).code)
        val id2 = trip()
        ok("d1", "ambreq_step", mapOf("id" to id2, "step" to "done"))
        assertEquals("not_finished", create(id2).code)
        // the patient never creates one; another driver neither
        assertEquals(404, call(P, "pay_request_create", mapOf("refType" to "ambreq", "refId" to id, "purpose" to "ambulance_fare")).status)
    }
}
