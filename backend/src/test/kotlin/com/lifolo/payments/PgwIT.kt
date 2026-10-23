package com.lifolo.payments

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders

/**
 * bKash Tokenized Checkout for Lifolo's own income (PAYMENTS.md, security review P7) with a mocked bKash: the server's
 * price, success only from execute/query matching our row, an idempotent callback, the reconcile job, fixed redirects.
 */
class PgwIT : PayTestBase() {
    @Autowired lateinit var pgw: PgwService

    private lateinit var mock: BkashMock
    private lateinit var real: PaymentProvider

    @BeforeEach
    fun gateway() {
        mock = BkashMock()
        real = pgw.provider
        pgw.provider = mock.provider()
        mock.answers["/tokenized/checkout/create"] = """{"statusCode":"0000","paymentID":"PAY{n}","bkashURL":"https://sandbox.payment.bkash.com/?paymentId=PAY"}"""
    }

    @AfterEach
    fun back() {
        pgw.provider = real
        mock.close()
    }

    private fun checkout(uid: String, client: String = "app"): Pair<String, String> {
        val r = ok(uid, "pgw_checkout", mapOf("purpose" to "lifolo_subscription", "amount" to 1, "client" to client))
        return r["id"].textValue() to r["paymentID"].textValue()
    }

    private fun callback(query: String): Res {
        val res = mvc.perform(MockMvcRequestBuilders.get("/api/pay/bkash/callback?$query")).andReturn().response
        return Res(res.status, mapper.nullNode(), res)
    }

    private fun status(id: String) = row("SELECT * FROM pgw_payments WHERE id = ?", id)

    @Test
    fun `checkout - the server's price only, Lifolo's own purpose only, 503 without credentials, never for demo`() {
        person(PAT)
        val (id, pid) = checkout(PAT)
        assertEquals("PAY1", pid)
        val create = mock.hits.first { it.path == "/tokenized/checkout/create" }.body
        assertEquals(listOf("299.00", id), listOf(create["amount"].textValue(), create["merchantInvoiceNumber"].textValue()), "never the client's amount")
        assertTrue(create["callbackURL"].textValue().endsWith("/api/pay/bkash/callback"))
        assertEquals("created", status(id)["status"])
        fails(PAT, "pgw_checkout", mapOf("purpose" to "delivery"), 400, "pgw_purpose")
        fails(PAT, "pgw_checkout", mapOf("purpose" to "chamber_fee", "amount" to 400), 400, "pgw_purpose")
        person("demo-pypat")
        fails("demo-pypat", "pgw_checkout", mapOf("purpose" to "lifolo_subscription"), 403, "demo_locked")
        pgw.provider = BkashTokenizedProvider("", "", "", "", "")
        fails(PAT, "pgw_checkout", mapOf("purpose" to "lifolo_subscription"), 503, "pgw_unavailable")
    }

    @Test
    fun `callback - a forged status=success is never trusted, an amount mismatch is not paid, a real one once (replay = the same answer)`() {
        person(PAT)
        val (id, pid) = checkout(PAT)
        // forged: the browser says success, bKash says the payment is not done
        mock.answers["/tokenized/checkout/execute"] = """{"statusCode":"2056","statusMessage":"Invalid Payment State"}"""
        mock.answers["/tokenized/checkout/payment/status"] = mock.payment(pid, id, "299.00", status = "Initiated", trx = null)
        val forged = callback("paymentID=$pid&status=success&return=https://evil.example")
        assertEquals(302, forged.status)
        assertEquals("lifolo://pay/result?id=$id", forged.response.getHeader("Location"), "a fixed redirect, nothing from the request")
        assertEquals("created", status(id)["status"])
        // bKash says completed but for another amount / another invoice: not paid
        mock.answers["/tokenized/checkout/execute"] = mock.payment(pid, id, "1.00")
        mock.answers["/tokenized/checkout/payment/status"] = mock.payment(pid, "someone-else", "299.00")
        callback("paymentID=$pid&status=success")
        assertEquals("created", status(id)["status"])
        // the real one
        mock.answers["/tokenized/checkout/execute"] = mock.payment(pid, id, "299.00")
        callback("paymentID=$pid&status=success")
        assertEquals(listOf("completed", "TRX$pid"), status(id).let { listOf(it["status"], it["trx_id"]) })
        val executes = mock.count("/tokenized/checkout/execute")
        val again = callback("paymentID=$pid&status=success")
        assertEquals("lifolo://pay/result?id=$id", again.response.getHeader("Location"))
        assertEquals(executes, mock.count("/tokenized/checkout/execute"), "a replay never asks bKash again")
        // unknown paymentID → 404; the status for the owner only
        assertEquals(404, callback("paymentID=NOPE&status=success").status)
        assertEquals("completed", ok(PAT, "pgw_status", mapOf("id" to id))["status"].textValue())
        fails(X.also { person(it) }, "pgw_status", mapOf("id" to id), 404)
    }

    @Test
    fun `callback - execute fails or times out but the payment completed (query), cancel, a trxID never granted twice, the web redirect`() {
        person(PAT)
        val (id, pid) = checkout(PAT, client = "web")
        mock.answers["/tokenized/checkout/execute"] = "!504"
        mock.answers["/tokenized/checkout/payment/status"] = mock.payment(pid, id, "299.00")
        val r = callback("paymentID=$pid&status=success")
        assertEquals("https://lifolo.com/pay/result/$id", r.response.getHeader("Location"))
        assertEquals("completed", status(id)["status"])
        // cancelled in the browser and at bKash
        val (id2, pid2) = checkout(PAT)
        mock.answers["/tokenized/checkout/payment/status"] = mock.payment(pid2, id2, "299.00", status = "Cancelled", trx = null)
        callback("paymentID=$pid2&status=cancel")
        assertEquals("cancelled", status(id2)["status"])
        // a trxID already granted to another checkout: refused
        val (id3, pid3) = checkout(PAT)
        mock.answers["/tokenized/checkout/execute"] = mock.payment(pid3, id3, "299.00", trx = "TRX$pid")
        callback("paymentID=$pid3&status=success")
        assertEquals(listOf("failed", null), status(id3).let { listOf(it["status"], it["trx_id"]) })
    }

    @Test
    fun `reconcile - pending checkouts are queried after 2 minutes, given up after 24 h`() {
        person(PAT)
        val (id, pid) = checkout(PAT)
        val (id2, _) = checkout(PAT)
        mock.answers["/tokenized/checkout/payment/status"] = mock.payment(pid, id, "299.00")
        val now = System.currentTimeMillis()
        assertEquals(0, pgw.reconcile(now), "not before 2 minutes")
        assertEquals(1, pgw.reconcile(now + 3 * 60_000L))
        assertEquals("completed", status(id)["status"])
        assertEquals("created", status(id2)["status"], "the other one did not match (another invoice)")
        pgw.reconcile(now + 25 * HOUR)
        assertEquals("failed", status(id2)["status"])
        assertNull(status(id2)["trx_id"])
    }
}
