package com.lifolo.payments

import com.fasterxml.jackson.databind.JsonNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * `payRequestId` + `payStatus` on the source views (PAYMENTS.md "Where clients find the request"), payRequestId in
 * the pushes, and appt_pay_confirm needing the three checks once the appointment has a pay request.
 */
class PayLinksIT : PayTestBase() {
    private fun pair(n: JsonNode): List<String?> = listOf(n["payRequestId"], n["payStatus"]).map { if (it == null || it.isNull) null else it.textValue() }

    @Test
    fun `appointments - my_appointments (both roles) and rx_patients carry the latest request, appt_pay_confirm needs the checks`() {
        doctor(DOC, "asst@example.com")
        person(PAT)
        ready("chamber", DOC, DOC)
        appt("on1", online = true, date = LocalDate.now().plusDays(1))
        appt("ip1", date = LocalDate.now().plusDays(1), start = "19:00")
        fun mine(uid: String, id: String) = ok(uid, "my_appointments")["items"].first { it["id"].textValue() == id }
        fun desk(id: String) = ok(DOC, "rx_patients")["appointments"].first { it["id"].textValue() == id }
        // no request yet: both fields present and null
        assertEquals(listOf(null, null), pair(mine(PAT, "ip1")))
        assertTrue(mine(PAT, "ip1").has("payRequestId") && mine(PAT, "ip1").has("payStatus"))
        // the old claim makes the chamber_fee request: every view shows it
        ok(PAT, "appt_pay_claim", mapOf("appointmentId" to "on1", "payRef" to "LK12345678"))
        val id = ok(PAT, "pay_request_get", mapOf("refType" to "appointment", "refId" to "on1"))["request"]["id"].textValue()
        assertEquals(listOf(id, "claimed"), pair(mine(PAT, "on1")))
        assertEquals(listOf(id, "claimed"), pair(mine(DOC, "on1")))
        assertEquals(listOf(id, "claimed"), pair(desk("on1")))
        assertEquals(listOf(null, null), pair(desk("ip1")))
        // the doctor's push carries it
        val pushed = inbox(DOC).map { mapper.readTree(it["data"] as String) }.last { it["type"]?.textValue() == "appointment" }
        assertEquals(id, pushed["payRequestId"].textValue())
        // the old confirm: the three checks, like pay_confirm
        fails(DOC, "appt_pay_confirm", mapOf("appointmentId" to "on1"), 400, "checks_required")
        fails(DOC, "appt_pay_confirm", mapOf("appointmentId" to "on1", "checks" to mapOf("trxId" to true, "amount" to true, "last4" to false)), 400, "checks_required")
        fails(ASST, "appt_pay_confirm", mapOf("appointmentId" to "on1", "doctorUid" to DOC, "checks" to mapOf("trxId" to true, "amount" to true)), 400, "checks_required")
        assertEquals(listOf("claimed", "claimed"), listOf(req(id)["status"], row("SELECT pay_state FROM appointments WHERE id = 'on1'")["pay_state"]))
        assertEquals("paid", ok(DOC, "appt_pay_confirm", mapOf("appointmentId" to "on1", "checks" to CHECKS))["payState"].textValue())
        assertEquals(listOf(id, "confirmed"), pair(mine(PAT, "on1")))
        assertEquals(listOf(id, "confirmed"), pair(desk("on1")))
        val told = inbox(PAT).map { mapper.readTree(it["data"] as String) }.last { it["type"]?.textValue() == "appointment" }
        assertEquals(id, told["payRequestId"].textValue())
    }

    @Test
    fun `orders and lab orders - rx_activity, rx_orders_pharmacy, lab_orders and lab_order_get carry the latest request`() {
        shop(PH)
        person(PAT)
        lab(LAB)
        ready("pharmacy", PH, PH)
        ready("lab", LAB, LAB, "01711000002")
        acceptedOrder("o1")
        labOrder("lo1")
        jdbc.update("UPDATE lab_orders SET rx_id = 'rx-o1' WHERE id = 'lo1'")
        fun activity() = ok(PAT, "rx_activity", mapOf("rxId" to "rx-o1"))
        fun pharmacy() = ok(PH, "rx_orders_pharmacy")["items"].first { it["id"].textValue() == "o1" }
        fun labList() = ok(LAB, "lab_orders")["items"].first { it["id"].textValue() == "lo1" }
        assertEquals(listOf(null, null), pair(pharmacy()))
        assertEquals(listOf(null, null), pair(activity()["orders"].first { it["id"].textValue() == "o1" }))
        assertEquals(listOf(null, null), pair(labList()))
        val d = create(PH, "rx_order", "o1", "delivery")["id"].textValue()
        val l = create(LAB, "lab_order", "lo1", "lab")["id"].textValue()
        assertEquals(listOf(d, "open"), pair(pharmacy()))
        assertEquals(listOf(d, "open"), pair(activity()["orders"].first { it["id"].textValue() == "o1" }))
        assertEquals(listOf(l, "open"), pair(activity()["labOrders"].first { it["id"].textValue() == "lo1" }))
        assertEquals(listOf(l, "open"), pair(labList()))
        assertEquals(listOf(l, "open"), pair(ok(PAT, "lab_order_get", mapOf("id" to "lo1"))["item"]))
        assertEquals(200, claim(PAT, d, "OR12345678").status)
        assertEquals(listOf(d, "claimed"), pair(pharmacy()))
        assertEquals(listOf(d, "claimed"), pair(activity()["orders"].first { it["id"].textValue() == "o1" }))
        // every pay_* push names the request
        assertEquals(listOf(d), payPushes(PH, "pay_claimed").map { it["payRequestId"].textValue() })
        assertEquals(listOf(d, l), payPushes(PAT, "pay_requested").map { it["payRequestId"].textValue() })
    }
}
