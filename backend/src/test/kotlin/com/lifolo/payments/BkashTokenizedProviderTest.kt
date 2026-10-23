package com.lifolo.payments

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** BkashTokenizedProvider against a mocked bKash server: grant → create → execute → query → refund */
class BkashTokenizedProviderTest {
    private val mock = BkashMock()

    @AfterEach
    fun stop() = mock.close()

    @Test
    fun `not configured without every credential (then the checkout answers 503)`() {
        assertFalse(BkashTokenizedProvider("", "k", "s", "u", "p").configured)
        assertFalse(BkashTokenizedProvider("https://tokenized.sandbox.bka.sh/v1.2.0-beta", "", "s", "u", "p").configured)
        assertFalse(BkashTokenizedProvider("http://evil.example/v1", "k", "s", "u", "p").configured, "https only (localhost for tests)")
        assertTrue(BkashTokenizedProvider("https://tokenized.sandbox.bka.sh/v1.2.0-beta", "k", "s", "u", "p").configured)
        assertThrows(PgwException::class.java) { BkashTokenizedProvider("", "", "", "", "").execute("p1") }
    }

    @Test
    fun `grant token once (cached), create with the official fields, execute, query, refund`() {
        val p = mock.provider()
        mock.answers["/tokenized/checkout/create"] = """{"statusCode":"0000","statusMessage":"Successful","paymentID":"PAY1","bkashURL":"https://sandbox.payment.bkash.com/?paymentId=PAY1","transactionStatus":"Initiated"}"""
        val co = p.createCheckout("inv-1", BigDecimal("299"), "u1", "https://lifolo.com/api/pay/bkash/callback")
        assertEquals("PAY1", co.paymentId)
        assertEquals("https://sandbox.payment.bkash.com/?paymentId=PAY1", co.redirectUrl)
        val grant = mock.hits.first { it.path == "/tokenized/checkout/token/grant" }
        assertEquals(listOf("user", "pass"), listOf(grant.headers["username"], grant.headers["password"]))
        assertEquals(listOf("app-key", "app-secret"), listOf(grant.body["app_key"].textValue(), grant.body["app_secret"].textValue()))
        val create = mock.hits.first { it.path == "/tokenized/checkout/create" }
        assertEquals(listOf("tok-1", "app-key"), listOf(create.headers["authorization"], create.headers["x-app-key"]))
        assertEquals(
            listOf("0011", "299.00", "BDT", "sale", "inv-1", "u1", "https://lifolo.com/api/pay/bkash/callback"),
            listOf("mode", "amount", "currency", "intent", "merchantInvoiceNumber", "payerReference", "callbackURL").map { create.body[it].textValue() },
        )
        mock.answers["/tokenized/checkout/execute"] = mock.payment("PAY1", "inv-1", "299.00")
        val ex = p.execute("PAY1")
        assertTrue(ex.ok)
        assertEquals(listOf("Completed", "TRXPAY1", "inv-1", "BDT", "sale"), listOf(ex.transactionStatus, ex.trxId, ex.merchantInvoiceNumber, ex.currency, ex.intent))
        assertEquals(0, BigDecimal("299").compareTo(ex.amount))
        assertEquals("PAY1", mock.hits.last().body["paymentID"].textValue())
        mock.answers["/tokenized/checkout/payment/status"] = mock.payment("PAY1", "inv-1", "299.00", status = "Initiated", trx = null)
        val q = p.query("PAY1")
        assertEquals(listOf("Initiated", null), listOf(q.transactionStatus, q.trxId))
        mock.answers["/tokenized/checkout/payment/refund"] = """{"originalTrxID":"TRXPAY1","refundTrxID":"RF1","transactionStatus":"Completed","amount":"299.00","currency":"BDT"}"""
        val rf = p.refund("PAY1", "TRXPAY1", BigDecimal("299"), "test")
        assertEquals(listOf("RF1", "Completed"), listOf(rf.refundTrxId, rf.transactionStatus))
        val rb = mock.hits.last().body
        assertEquals(listOf("PAY1", "TRXPAY1", "299.00"), listOf(rb["paymentID"].textValue(), rb["trxID"].textValue(), rb["amount"].textValue()))
        assertEquals(1, mock.count("/tokenized/checkout/token/grant"), "the grant token is cached")
    }

    @Test
    fun `errors - a refused create, an HTTP error, not JSON`() {
        val p = mock.provider()
        mock.answers["/tokenized/checkout/create"] = """{"statusCode":"2001","statusMessage":"Invalid App Key"}"""
        assertThrows(PgwException::class.java) { p.createCheckout("inv-2", BigDecimal.TEN, "u1", "https://x/cb") }
        mock.answers["/tokenized/checkout/execute"] = "!500"
        assertThrows(PgwException::class.java) { p.execute("PAY2") }
        mock.answers["/tokenized/checkout/payment/status"] = "not json"
        assertThrows(PgwException::class.java) { p.query("PAY2") }
        // a failed execute answers (statusCode ≠ 0000) without an exception: the caller decides
        mock.answers["/tokenized/checkout/execute"] = """{"statusCode":"2056","statusMessage":"Invalid Payment State"}"""
        assertFalse(p.execute("PAY2").ok)
    }
}
