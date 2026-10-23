package com.lifolo.payments

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Lifolo Pay (backend/docs/PAYMENTS.md + docs/test-plan/PAYMENTS-SECURITY-REVIEW.md): payment numbers and their
 * guards, the request lifecycle for every purpose, receipts, refunds, jobs, abuse controls, switches, demo/real, blood.
 */
class PayIT : PayTestBase() {
    // ------------------------------------------------------------------ payment numbers (P4, P8)

    @Test
    fun `methods - owner only, valid BD numbers, a recent login, every change audited, pushed and emailed`() {
        shop(PH)
        person(X)
        val r = setMethods("pharmacy", PH, PH, listOf(bkash("01711-000001"), nagad("+8801811000002", "merchant")))
        assertEquals(2, r["changes"].intValue())
        val ms = r["methods"]
        assertEquals(listOf("bkash", "nagad"), ms.map { it["method"].textValue() })
        assertEquals("01711000001", ms[0]["number"].textValue(), "normalized")
        assertTrue(ms[0]["activeNumber"].isNull, "a new number does not reach patients yet")
        assertTrue(ms[0]["pendingUntil"].longValue() > System.currentTimeMillis() + 23 * HOUR)
        assertEquals(2, ok(PH, "pay_methods_get", mapOf("kind" to "pharmacy"))["methods"].size())
        // someone else
        fails(X, "pay_methods_set", mapOf("kind" to "pharmacy", "bizId" to PH, "methods" to listOf(bkash("01711000009"))), 403)
        fails(X, "pay_methods_get", mapOf("kind" to "pharmacy", "bizId" to PH), 403)
        // validation
        fails(PH, "pay_methods_set", mapOf("kind" to "pharmacy", "methods" to listOf(bkash("12345"))), 400, "number")
        fails(PH, "pay_methods_set", mapOf("kind" to "pharmacy", "methods" to listOf(bkash("01711000001"), bkash("01711000002"))), 400, "method")
        fails(PH, "pay_methods_set", mapOf("kind" to "pharmacy", "methods" to listOf(mapOf("method" to "paypal", "number" to "01711000001", "type" to "personal"))), 400, "method")
        fails(PH, "pay_methods_set", mapOf("kind" to "pharmacy", "methods" to listOf(bkash("01711000001", "gold"))), 400, "type")
        fails(PH, "pay_methods_set", mapOf("kind" to "pharmacy", "methods" to (1..5).map { bkash("0171100000$it") }), 400, "methods")
        // a login older than 10 minutes cannot change where the money goes
        identity.users[PH]!!.authAt = System.currentTimeMillis() - 11 * 60_000L
        fails(PH, "pay_methods_set", mapOf("kind" to "pharmacy", "methods" to listOf(bkash("01711000009"))), 401, "reauth_required")
        assertEquals(200, call(PH, "pay_methods_get", mapOf("kind" to "pharmacy")).status, "reading needs no fresh login")
        identity.users[PH]!!.authAt = null
        // audit (last 4 digits), push (account, always on) and email with the freeze link
        assertEquals(listOf("added", "added"), jdbc.queryForList("SELECT change FROM pay_method_log WHERE biz_id = ? ORDER BY id", String::class.java, PH))
        assertEquals("0001", jdbc.queryForList("SELECT new_last4 FROM pay_method_log WHERE biz_id = ? AND method = 'bkash'", String::class.java, PH).single())
        val pushes = payPushes(PH, "pay_methods_changed")
        assertEquals(1, pushes.size)
        assertEquals("account", pushes[0]["cat"].textValue())
        assertEquals("pay_methods_freeze", pushes[0]["action"].textValue())
        val mails = mail.sent.filter { it.to.equals("$PH@example.com", ignoreCase = true) && it.subject.contains("পেমেন্ট নম্বর") }
        assertEquals(1, mails.size)
        assertTrue(mails[0].text.contains("…001"), mails[0].text)
        assertFalse(mails[0].text.contains("01711000001"), "never the whole number")
        // setting the same again changes nothing (no push, no email)
        assertEquals(0, setMethods("pharmacy", PH, PH, listOf(bkash("01711000001"), nagad("01811000002", "merchant")))["changes"].intValue())
        assertEquals(1, payPushes(PH, "pay_methods_changed").size)
    }

    @Test
    fun `methods - demo businesses only with demo numbers, real ones never with them`() {
        shop("demo-pyph")
        fails("demo-pyph", "pay_methods_set", mapOf("kind" to "pharmacy", "methods" to listOf(bkash("01711000001"))), 400, "demo_number")
        ok("demo-pyph", "pay_methods_set", mapOf("kind" to "pharmacy", "methods" to listOf(bkash("01200000301"))))
        shop(PH)
        fails(PH, "pay_methods_set", mapOf("kind" to "pharmacy", "methods" to listOf(bkash("01200000301"))), 400, "number")
    }

    @Test
    fun `methods - any number not active for 24 h waits (new, edited, removed and re-added, re-activated), requests snapshot matured numbers only`() {
        val t0 = System.currentTimeMillis()
        payClock.fixed = t0
        shop(PH)
        person(PAT)
        acceptedOrder("o1")
        setMethods("pharmacy", PH, PH, listOf(bkash("01711000001")))
        // nothing matured yet: no request (cash only)
        fails(PH, "pay_request_create", mapOf("refType" to "rx_order", "refId" to "o1", "purpose" to "delivery"), 409, "no_methods")
        payClock.fixed = t0 + DAY + HOUR
        val r1 = create(PH, "rx_order", "o1", "delivery")
        assertEquals(listOf("bkash:01711000001"), r1["methods"].map { "${it["method"].textValue()}:${it["number"].textValue()}" })
        // edited: the matured number stays in use for 24 h, the new one waits; the request keeps its snapshot
        val t1 = payClock.now()
        var ms = setMethods("pharmacy", PH, PH, listOf(bkash("01711000009")))["methods"][0]
        assertEquals(listOf("01711000009", "01711000001", (t1 + DAY).toString()), listOf(ms["number"].textValue(), ms["activeNumber"].textValue(), ms["pendingUntil"].asText()))
        acceptedOrder("o2")
        assertEquals("01711000001", create(PH, "rx_order", "o2", "delivery")["methods"][0]["number"].textValue())
        // removed and added again: a new number with nothing in use meanwhile
        setMethods("pharmacy", PH, PH, listOf(nagad("01811000002")))
        ms = setMethods("pharmacy", PH, PH, listOf(bkash("01711000009"), nagad("01811000002")))["methods"][0]
        assertTrue(ms["activeNumber"].isNull, "re-added: waits again")
        // switched off: gone at once; on again: waits again
        payClock.fixed = t1 + 3 * DAY
        assertEquals("01711000009", ok(PH, "pay_methods_get", mapOf("kind" to "pharmacy"))["methods"][0]["activeNumber"].textValue())
        ms = setMethods("pharmacy", PH, PH, listOf(bkash("01711000009", active = false), nagad("01811000002")))["methods"][0]
        assertTrue(ms["activeNumber"].isNull)
        ms = setMethods("pharmacy", PH, PH, listOf(bkash("01711000009"), nagad("01811000002")))["methods"][0]
        assertTrue(ms["activeNumber"].isNull, "re-activated: waits again")
        assertEquals((payClock.now() + DAY).toString(), ms["pendingUntil"].asText())
        // a changed type alone is only audited
        payClock.fixed = payClock.now() + DAY + HOUR
        ms = setMethods("pharmacy", PH, PH, listOf(bkash("01711000009", "merchant"), nagad("01811000002")))["methods"][0]
        assertEquals("01711000009", ms["activeNumber"].textValue())
        assertTrue(jdbc.queryForList("SELECT change FROM pay_method_log WHERE biz_id = ?", String::class.java, PH).containsAll(listOf("number", "removed", "off", "on", "type")))
        // the first request still shows the number it was made with
        assertEquals("01711000001", ok(PAT, "pay_request_get", mapOf("id" to r1["id"].textValue()))["request"]["methods"][0]["number"].textValue())
    }

    @Test
    fun `methods - "আমি করিনি" freezes every number at once (app or email link), only an admin unfreezes`() {
        shop(PH)
        person(PAT)
        admin()
        ready("pharmacy", PH, PH)
        val token = Regex("t=([A-Za-z0-9_.-]+)").find(mail.sent.last { it.to.equals("$PH@example.com", ignoreCase = true) }.text)!!.groupValues[1]
        // the email link: a page with a button (a mail scanner opening it freezes nothing), then the POST
        val page = mvc.perform(MockMvcRequestBuilders.get("/api/pay/freeze").param("t", token)).andReturn().response
        assertEquals(200, page.status)
        assertTrue(page.getContentAsString(Charsets.UTF_8).contains("<form method=\"post\""))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM pay_freezes", Int::class.java))
        val bad = mvc.perform(MockMvcRequestBuilders.post("/api/pay/freeze").param("t", token.dropLast(2) + "00")).andReturn().response
        assertEquals(200, bad.status)
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM pay_freezes", Int::class.java), "a forged token freezes nothing")
        val done = mvc.perform(MockMvcRequestBuilders.post("/api/pay/freeze").param("t", token)).andReturn().response
        assertEquals(200, done.status)
        assertEquals("email", row("SELECT via FROM pay_freezes WHERE kind = 'pharmacy' AND biz_id = ?", PH)["via"])
        // frozen: no number reaches anyone, no change, no request
        acceptedOrder("o1")
        fails(PH, "pay_request_create", mapOf("refType" to "rx_order", "refId" to "o1", "purpose" to "delivery"), 409, "pay_frozen")
        fails(PH, "pay_methods_set", mapOf("kind" to "pharmacy", "methods" to listOf(bkash("01711000002"))), 409, "pay_frozen")
        assertEquals(1, ok(ADMIN, "admin_pay_flags")["frozen"].size())
        fails(PH, "admin_pay_unfreeze", mapOf("kind" to "pharmacy", "bizId" to PH), 403)
        ok(ADMIN, "admin_pay_unfreeze", mapOf("kind" to "pharmacy", "bizId" to PH))
        assertEquals(true, create(PH, "rx_order", "o1", "delivery")["id"].isTextual)
        // the in-app action (from the push)
        ok(PH, "pay_methods_freeze", mapOf("kind" to "pharmacy"))
        fails(PH, "pay_methods_set", mapOf("kind" to "pharmacy", "methods" to listOf(bkash("01711000002"))), 409, "pay_frozen")
        fails(X.also { person(it) }, "pay_methods_freeze", mapOf("kind" to "pharmacy", "bizId" to PH), 403)
    }

    // ------------------------------------------------------------------ the blood ban

    @Test
    fun `blood - no payment of any kind in the blood context`() {
        shop(PH)
        person(PAT)
        for (body in listOf(
            mapOf("refType" to "blood", "refId" to "r1", "purpose" to "invoice_due"),
            mapOf("refType" to "blood_request", "refId" to "r1", "purpose" to "delivery"),
            mapOf("refType" to "rx_order", "refId" to "r1", "purpose" to "blood"),
        )) {
            fails(PH, "pay_request_create", body, 400, "no_payment_blood")
            fails(PAT, "pay_request_create", body, 400, "no_payment_blood")
        }
        fails(PAT, "pay_request_get", mapOf("refType" to "blood", "refId" to "r1"), 400, "no_payment_blood")
    }

    // ------------------------------------------------------------------ delivery prepay: claim, TrxID, confirm checks, receipt

    @Test
    fun `delivery prepay - the amount from the order, claim formats, TrxID once per business, claimed is never paid, confirm with checks issues the receipt`() {
        shop(PH)
        person(PAT)
        person(PAT2)
        person(X)
        ready("pharmacy", PH, PH)
        acceptedOrder("o1")
        val res = ok(PH, "pay_request_create", mapOf("refType" to "rx_order", "refId" to "o1", "purpose" to "delivery", "amount" to 1))
        assertTrue(res["created"].booleanValue())
        val r = res["request"]
        val id = r["id"].textValue()
        assertEquals(75.0, d(r["amount"]), "10 × ৳2.5 + ৳50 delivery — never the client's amount (P6)")
        assertEquals(listOf("open", "pharmacy", PH, PAT, "delivery"), listOf(r["status"], r["kind"], r["bizId"], r["payerUid"], r["purpose"]).map { it.textValue() })
        assertTrue(r["expiresAt"].longValue() in System.currentTimeMillis() + 23 * HOUR..System.currentTimeMillis() + 25 * HOUR)
        assertEquals(id, create(PH, "rx_order", "o1", "delivery")["id"].textValue(), "one live request per source")
        // the payer's push: generic, no amount, no number
        val p = payPushes(PAT, "pay_requested").single()
        assertEquals(listOf(id, "my_request"), listOf(p["payRequestId"].textValue(), p["cat"].textValue()))
        assertFalse(p.has("amount"))
        assertEquals("পেমেন্ট আপডেট — লাইফলো", inbox(PAT).last()["title"])
        // only the pharmacy of this order
        fails(X, "pay_request_create", mapOf("refType" to "rx_order", "refId" to "o1", "purpose" to "delivery"), 404)
        fails(PAT, "pay_request_create", mapOf("refType" to "rx_order", "refId" to "o1", "purpose" to "delivery"), 404)
        // the PaySheet: policy + safety line
        val got = ok(PAT, "pay_request_get", mapOf("id" to id))
        assertEquals("payer", got["side"].textValue())
        assertTrue(got["policy"].textValue().contains("লাইফলো টাকা গ্রহণ বা সংরক্ষণ করে না"))
        // claim checks
        fails(PAT, "pay_claim", mapOf("id" to id, "method" to "nagad", "trxId" to "AB12CD34EF", "senderLast4" to "1234"), 400, "method")
        assertEquals("trx_format", claim(PAT, id, "AB12CD34E").code)
        assertEquals("trx_format", claim(PAT, id, "AB12CD34EF1").code)
        val bad4 = claim(PAT, id, "AB12CD34EF", "12a4")
        assertEquals("sender_digits", bad4.code, bad4.toString())
        assertEquals(404, claim(X, id, "AB12CD34EF").status)
        val c = claim(PAT, id, " ab12 cd34-ef ", "১২৩৪")
        assertEquals(200, c.status, c.toString())
        val cl = c.body["request"]["claim"]
        assertEquals(listOf("bkash", "AB12CD34EF", "1234", PAT), listOf(cl["method"], cl["trxId"], cl["senderLast4"], cl["by"]).map { it.textValue() })
        assertEquals("claimed", req(id)["status"])
        assertEquals(1, payPushes(PH, "pay_claimed").size)
        // P1: claimed is never paid — no receipt, the order did not move
        assertNull(invoices.liveFor("rx_order", "o1"))
        assertEquals("accepted", row("SELECT status FROM rx_orders WHERE id = 'o1'")["status"])
        // confirm: only the business, only with the three checks
        fails(PH, "pay_confirm", mapOf("id" to id), 400, "checks_required")
        fails(PH, "pay_confirm", mapOf("id" to id, "checks" to mapOf("trxId" to true, "amount" to true)), 400, "checks_required")
        assertEquals(404, confirm(PAT, id).status)
        assertEquals(404, confirm(X, id).status)
        val cf = confirm(PH, id)
        assertEquals(200, cf.status, cf.toString())
        val inv = cf.body["invoice"]
        assertEquals(listOf("pharmacy", "rx_order", "o1"), listOf(inv["kind"], inv["refType"], inv["refId"]).map { it.textValue() })
        assertEquals(listOf(75.0, 75.0, 0.0), listOf(d(inv["total"]), d(inv["paid"]), d(inv["due"])))
        assertEquals(listOf("bkash", "AB12CD34EF"), listOf(inv["payments"][0]["method"].textValue(), inv["payments"][0]["ref"].textValue()))
        assertEquals(PAT, inv["patientUid"].textValue())
        assertEquals(inv["id"].textValue(), cf.body["request"]["invoiceId"].textValue())
        assertEquals(true, req(id)["invoice_issued"])
        assertEquals(1, payPushes(PAT, "pay_confirmed").size)
        // again: the same answer, no second receipt or push
        assertEquals(inv["id"].textValue(), confirm(PH, id).body["invoice"]["id"].textValue())
        assertEquals(1, payPushes(PAT, "pay_confirmed").size)
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM invoices WHERE ref_type = 'rx_order' AND ref_id = 'o1'", Int::class.java))
        fails(PH, "pay_request_create", mapOf("refType" to "rx_order", "refId" to "o1", "purpose" to "delivery"), 409, "already_paid")
        // the TrxID once per business: another patient's claim → 409 with "report", never who claimed it
        acceptedOrder("o2", patient = PAT2)
        val id2 = create(PH, "rx_order", "o2", "delivery")["id"].textValue()
        val used = claim(PAT2, id2, "AB12CD34EF")
        assertEquals(409, used.status)
        assertEquals("pay_ref_used", used.code)
        assertTrue(used.error!!.contains("রিপোর্ট"))
        assertFalse(used.body.toString().contains(PAT))
        // a different business may get the same TrxID (it is only a format)
        shop(PH2)
        ready("pharmacy", PH2, PH2, "01711000002")
        acceptedOrder("o3", patient = PAT2, pharmacy = PH2)
        assertEquals(200, claim(PAT2, create(PH2, "rx_order", "o3", "delivery")["id"].textValue(), "AB12CD34EF").status)
    }

    @Test
    fun `claims - Nagad, Rocket and Upay TrxIDs 6 to 20, per-request and per-person rate limits`() {
        shop(PH)
        person(PAT)
        setMethods("pharmacy", PH, PH, listOf(nagad("01811000002"), mapOf("method" to "rocket", "number" to "01911000003", "type" to "personal")))
        mature(PH)
        acceptedOrder("o1")
        val id = create(PH, "rx_order", "o1", "delivery")["id"].textValue()
        assertEquals("trx_format", claim(PAT, id, "AB12", method = "nagad").code)
        assertEquals("trx_format", claim(PAT, id, "A".repeat(21), method = "rocket").code)
        assertEquals(200, claim(PAT, id, "NG12345", method = "nagad").status)
        // 5 claims per request an hour (the first one counted)
        for (i in 2..5) assertEquals(200, claim(PAT, id, "NG1234$i", method = "nagad").status, "claim $i")
        val rate = claim(PAT, id, "NG123499", method = "nagad")
        assertEquals(429, rate.status)
        assertEquals("pay_claim_rate", rate.code)
        payClock.fixed = System.currentTimeMillis() + HOUR + 60_000
        assertEquals(200, claim(PAT, id, "NG123499", method = "nagad").status)
        // 20 per person a day
        for (i in 1..20) jdbc.update("INSERT INTO pay_claim_tries (request_id, payer_uid, at) VALUES ('other', ?, now())", PAT)
        payClock.fixed = null
        acceptedOrder("o2")
        assertEquals("pay_claim_rate", claim(PAT, create(PH, "rx_order", "o2", "delivery")["id"].textValue(), "NG777777", method = "nagad").code)
    }

    // ------------------------------------------------------------------ invoice due, caregiver, assistant, refunds

    @Test
    fun `invoice due - the patient (or a caregiver) starts it, caregiver claims, the assistant confirms, the owner refunds in part, the payer acknowledges`() {
        doctor(DOC, "asst@example.com")
        person(PAT)
        person(X)
        careLink()
        ready("chamber", DOC, DOC)
        val inv = dueInvoice("chamber", DOC, DOC, 800, 300)
        fails(X, "pay_due_start", mapOf("invoiceId" to inv.id), 404)
        fails(DOC, "pay_due_start", mapOf("invoiceId" to inv.id), 404)
        val r = ok(CG, "pay_due_start", mapOf("invoiceId" to inv.id, "asUid" to PAT))["request"]
        val id = r["id"].textValue()
        assertEquals(listOf(500.0), listOf(d(r["amount"])), "the due amount")
        assertEquals(listOf("invoice_due", "invoice", inv.id, PAT), listOf(r["purpose"], r["refType"], r["refId"], r["payerUid"]).map { it.textValue() })
        assertEquals(id, ok(PAT, "pay_due_start", mapOf("invoiceId" to inv.id))["request"]["id"].textValue(), "the same live one")
        // the caregiver pays from their own wallet: recorded as claim.by
        val c = claim(CG, id, "CG12345678", extra = mapOf("asUid" to PAT))
        assertEquals(200, c.status, c.toString())
        assertEquals(CG, c.body["request"]["claim"]["by"].textValue())
        assertEquals(PAT, ok(CG, "my_payments", mapOf("asUid" to PAT))["items"][0]["payerUid"].textValue())
        // a caregiver cannot confirm; the doctor's current assistant can
        assertEquals(403, call(CG, "pay_confirm", mapOf("id" to id, "checks" to CHECKS, "asUid" to PAT)).status)
        val cf = confirm(ASST, id)
        assertEquals(200, cf.status, cf.toString())
        assertEquals(listOf(800.0, 0.0), listOf(d(cf.body["invoice"]["paid"]), d(cf.body["invoice"]["due"])), "the payment added to the receipt")
        assertEquals(cf.body["invoice"]["id"].textValue(), inv.id)
        assertEquals(false, req(id)["invoice_issued"])
        assertEquals("bkash", invoices.load(inv.id)!!.payments.last().method)
        // refunds: the owner only, at most the paid amount; refund_claimed until the payer says "পেয়েছি"
        fails(ASST, "pay_refund", mapOf("id" to id, "trxId" to "RF12345678"), 403, "owner_only")
        fails(DOC, "pay_refund", mapOf("id" to id, "trxId" to "RF12345678", "amount" to 501), 400, "amount")
        fails(DOC, "pay_refund", mapOf("id" to id, "trxId" to "x"), 400, "trx_format")
        val rf = ok(DOC, "pay_refund", mapOf("id" to id, "trxId" to "rf12345678", "amount" to 200))["request"]
        assertEquals("refund_claimed", rf["status"].textValue())
        assertEquals(listOf("RF12345678", "200"), listOf(rf["refund"]["trxId"].textValue(), rf["refund"]["amount"].asText()))
        assertEquals(1, payPushes(PAT, "pay_refunded").size)
        // a refund in part: a refund line on the receipt (the receipt stays valid)
        val after = invoices.load(inv.id)!!
        assertEquals("issued", after.status)
        assertEquals(BigDecimal("200.00"), after.refunded)
        fails(DOC, "pay_refund", mapOf("id" to id, "trxId" to "RF99999999"), 409, "state")
        fails(X, "pay_refund_ack", mapOf("id" to id), 404)
        val ack = ok(CG, "pay_refund_ack", mapOf("id" to id, "asUid" to PAT))["request"]
        assertEquals("refunded", ack["status"].textValue())
        assertTrue(ack["refund"]["ackAt"].isNumber)
    }

    // ------------------------------------------------------------------ booking advance

    @Test
    fun `booking advance - asked at booking, expires at the slot, a refund in full voids the receipt, refund_due when the doctor cancels`() {
        doctor(DOC)
        person(PAT)
        admin()
        ready("chamber", DOC, DOC)
        ok(DOC, "pay_methods_set", mapOf("kind" to "chamber", "advance" to listOf(mapOf("chamberKey" to "h1", "amount" to 200, "refundableUntilHoursBefore" to 6))))
        fails(DOC, "pay_methods_set", mapOf("kind" to "chamber", "advance" to listOf(mapOf("chamberKey" to "nope", "amount" to 200))), 400, "advance")
        assertEquals(1, ok(DOC, "pay_methods_get", mapOf("kind" to "chamber"))["advance"].size())
        val date = LocalDate.now().plusDays(3)
        appt("a1", date = date)
        val rid = hooks.afterBook("a1", System.currentTimeMillis())!!
        val r = ok(PAT, "pay_request_get", mapOf("refType" to "appointment", "refId" to "a1", "purpose" to "booking_advance"))["request"]
        assertEquals(listOf(rid, "booking_advance", "open"), listOf(r["id"], r["purpose"], r["status"]).map { it.textValue() })
        assertEquals(200.0, d(r["amount"]))
        val slot = PayService.slotMs(date, "18:00")
        assertEquals(slot, r["expiresAt"].longValue(), "expires at the slot")
        assertEquals(slot - 6 * HOUR, r["refundableUntil"].longValue())
        assertTrue(ok(PAT, "pay_request_get", mapOf("id" to rid))["policy"].textValue().contains("৬ ঘণ্টা"))
        // paid, confirmed: the advance receipt
        assertEquals(200, claim(PAT, rid, "AD12345678").status)
        val inv = confirm(DOC, rid).body["invoice"]
        assertEquals("chamber", inv["kind"].textValue())
        assertTrue(inv["lines"][0]["name"].textValue().startsWith("অগ্রিম বুকিং ফি"))
        assertEquals(true, req(rid)["invoice_issued"])
        // a refund in full voids it (reason `refunded`)
        ok(DOC, "pay_refund", mapOf("id" to rid, "trxId" to "RA12345678"))
        val void = invoices.load(inv["id"].textValue())!!
        assertEquals(listOf("void", "refunded"), listOf(void.status, void.voidReason))
        // the doctor declines a paid booking: refund_due, the doctor is told (once a day); after 7 days no new requests
        appt("a2", date = date.plusDays(1))
        val r2 = hooks.afterBook("a2", System.currentTimeMillis())!!
        assertEquals(200, claim(PAT, r2, "AE12345678").status)
        assertEquals(200, confirm(DOC, r2).status)
        jdbc.update("UPDATE appointments SET status = 'declined' WHERE id = 'a2'")
        val now = System.currentTimeMillis()
        assertEquals(1, jobs.run(now)["refundDue"])
        assertEquals("business_cancelled", req(r2)["refund_due_why"])
        assertEquals(1, payPushes(DOC, "pay_refund_due").size)
        assertEquals("business_cancelled", ok(PAT, "pay_request_get", mapOf("id" to r2))["request"]["refundDue"]["why"].textValue())
        jobs.run(now + HOUR)
        assertEquals(1, payPushes(DOC, "pay_refund_due").size, "once a day")
        payClock.fixed = now + 8 * DAY
        jobs.run(now + 8 * DAY)
        appt("a3", date = date.plusDays(10))
        fails(DOC, "pay_request_create", mapOf("refType" to "appointment", "refId" to "a3", "purpose" to "booking_advance"), 409, "pay_paused")
        assertEquals(listOf(r2), ok(ADMIN, "admin_pay_flags")["refundsOverdue"].map { it["id"].textValue() })
        // an unpaid advance never cancels the booking (M-PAY-34); it expires at the slot time
        appt("a4", date = date)
        payClock.fixed = null
        jdbc.update("UPDATE pay_requests SET refund_due_at = NULL WHERE id = ?", r2)
        val r4 = hooks.afterBook("a4", System.currentTimeMillis())!!
        jobs.run(slot + 60_000)
        assertEquals("expired", req(r4)["status"])
        assertEquals("confirmed", row("SELECT status FROM appointments WHERE id = 'a4'")["status"])
        assertEquals(409, claim(PAT, r4, "AF12345678").status)
    }

    // ------------------------------------------------------------------ reject, re-confirm, 3 strikes, disputes

    @Test
    fun `reject - the business may re-confirm within 7 days, 3 rejections limit the payer to one open claim, disputed ones do not count, pay_ref_rejected`() {
        lab(LAB)
        person(PAT)
        admin()
        ready("lab", LAB, LAB)
        val ids = (1..7).map { i -> create(LAB, "invoice", dueInvoice("lab", LAB, LAB, 500, 0).id, "lab")["id"].textValue() }
        assertEquals("lab", req(ids[0])["purpose"])
        // r1: claimed, rejected by mistake, confirmed again within 7 days (audited)
        assertEquals(200, claim(PAT, ids[0], "TA00000001").status)
        fails(LAB, "pay_reject", mapOf("id" to ids[0], "reason" to "because"), 400, "reason")
        fails(LAB, "pay_reject", mapOf("id" to ids[0], "reason" to "other"), 400, "reason")
        val rj = ok(LAB, "pay_reject", mapOf("id" to ids[0], "reason" to "not_received", "note" to "দেখছি না"))["request"]
        assertEquals(listOf("rejected", "not_received", "দেখছি না"), listOf(rj["status"], rj["rejectReason"], rj["rejectNote"]).map { it.textValue() })
        assertEquals("TA00000001", rj["claim"]["trxId"].textValue(), "the claim stays for a report")
        assertEquals(1, payPushes(PAT, "pay_rejected").size)
        assertEquals(200, confirm(LAB, ids[0]).status)
        assertTrue(ok(LAB, "pay_request_get", mapOf("id" to ids[0]))["events"].any { it["action"].textValue() == "reconfirm" })
        // after 7 days a rejection is final for the business
        assertEquals(200, claim(PAT, ids[1], "TA00000002").status)
        ok(LAB, "pay_reject", mapOf("id" to ids[1], "reason" to "wrong_amount"))
        payClock.fixed = System.currentTimeMillis() + 8 * DAY
        assertEquals("state", confirm(LAB, ids[1]).code)
        payClock.fixed = null
        // P3: the same payer's rejected TrxID on another request: "report it"
        assertEquals("pay_ref_rejected", claim(PAT, ids[2], "TA00000002").code)
        // three counting rejections → one open claim at a time + an admin flag
        for ((i, rid) in listOf(ids[2], ids[3]).withIndex()) {
            assertEquals(200, claim(PAT, rid, "TB0000000$i").status)
            ok(LAB, "pay_reject", mapOf("id" to rid, "reason" to "not_received"))
        }
        assertEquals(200, claim(PAT, ids[4], "TC00000001").status)
        assertEquals("pay_claim_limit", claim(PAT, ids[5], "TC00000002").code)
        assertEquals(listOf(PAT), ok(ADMIN, "admin_pay_flags")["users"].map { it["uid"].textValue() })
        // a rejection the payer disputes (pay_report) no longer counts
        val rep = ok(PAT, "pay_report", mapOf("id" to ids[1], "reason" to "not_confirmed", "note" to "টাকা পাঠিয়েছি"))
        assertEquals(true, row("SELECT disputes_reject FROM pay_reports WHERE id = ?", rep["id"].textValue())["disputes_reject"])
        assertEquals(200, claim(PAT, ids[5], "TC00000002").status)
        fails(PAT, "pay_report", mapOf("id" to ids[1], "reason" to "nonsense"), 400, "reason")
        fails(X.also { person(it) }, "pay_report", mapOf("id" to ids[1], "reason" to "scam"), 404)
        ok(PAT, "pay_report", mapOf("id" to ids[3], "reason" to "asked_other_number"))
        // the admins: open reports (SLA flag after 72 h), resolve with who was at fault
        val reports = ok(ADMIN, "admin_pay_reports")["items"]
        assertEquals(2, reports.size())
        assertFalse(reports[0]["slaBreached"].booleanValue())
        ok(ADMIN, "admin_pay_report_set", mapOf("id" to rep["id"].textValue(), "status" to "resolved", "outcome" to "payer_fault"))
        assertEquals("payer_fault", row("SELECT outcome FROM pay_reports WHERE id = ?", rep["id"].textValue())["outcome"])
        fails(PAT, "admin_pay_reports", emptyMap(), 403)
    }

    // ------------------------------------------------------------------ jobs

    @Test
    fun `jobs - a claim unchecked for 24 h reminds the business, 72 h is an admin flag, open requests expire per purpose`() {
        lab(LAB)
        person(PAT)
        admin()
        ready("lab", LAB, LAB)
        labOrder("lo1")
        val r = create(LAB, "lab_order", "lo1", "lab")
        assertEquals(695.0, d(r["amount"]), "the order's prices − 10 % + the home fee")
        val id = r["id"].textValue()
        assertEquals(200, claim(PAT, id, "LB12345678").status)
        val t = System.currentTimeMillis()
        assertEquals(0, jobs.run(t + 23 * HOUR)["reminded"])
        assertEquals(1, jobs.run(t + 25 * HOUR)["reminded"])
        assertEquals(0, jobs.run(t + 26 * HOUR)["reminded"], "once")
        assertEquals(1, payPushes(LAB, "pay_reminder").size)
        assertEquals(0, jobs.run(t + 71 * HOUR)["flagged"])
        assertEquals(1, jobs.run(t + 73 * HOUR)["flagged"])
        payClock.fixed = t + 73 * HOUR
        assertEquals(listOf(id), ok(ADMIN, "admin_pay_flags")["claims"].map { it["id"].textValue() })
        assertEquals(LAB, ok(ADMIN, "admin_pay_flags")["businesses"][0]["bizId"].textValue())
        payClock.fixed = null
        // confirmed: the lab bill from the order, paid
        val inv = confirm(LAB, id).body["invoice"]
        assertEquals(listOf("lab_order", "lo1"), listOf(inv["refType"].textValue(), inv["refId"].textValue()))
        assertEquals(listOf(695.0, 0.0), listOf(d(inv["paid"]), d(inv["due"])))
        // expiry: lab 24 h, invoice due 7 days
        labOrder("lo2")
        val open = create(LAB, "lab_order", "lo2", "lab")["id"].textValue()
        val due = ok(PAT, "pay_due_start", mapOf("invoiceId" to dueInvoice("lab", LAB, LAB, 300, 0).id))["request"]["id"].textValue()
        val now = System.currentTimeMillis()
        assertEquals(1, jobs.run(now + 25 * HOUR)["expired"])
        assertEquals(listOf("expired", "open"), listOf(req(open)["status"], req(due)["status"]))
        assertEquals(1, jobs.run(now + 7 * DAY + HOUR)["expired"])
        assertEquals("expired", req(due)["status"])
        assertEquals("state", claim(PAT, open, "LB99999999").code)
    }

    // ------------------------------------------------------------------ switches (P13)

    @Test
    fun `feature switches - the business's pilot decides, off stops new money but never money already sent`() {
        shop(PH)
        shop(PH2)
        person(PAT)
        ready("pharmacy", PH, PH)
        ready("pharmacy", PH2, PH2, "01711000002")
        acceptedOrder("o1")
        acceptedOrder("o2", pharmacy = PH2)
        onlyOn(
            "pay_manual", "pay_delivery", "inv_pharmacy",
            pilots = mapOf("pay_manual" to listOf(PH), "pay_delivery" to listOf(PH), "inv_pharmacy" to listOf(PH)),
        )
        // a pilot business; its patient is not in the pilot and still pays
        val id = create(PH, "rx_order", "o1", "delivery")["id"].textValue()
        assertEquals(200, claim(PAT, id, "FS12345678").status)
        fails(PH2, "pay_request_create", mapOf("refType" to "rx_order", "refId" to "o2", "purpose" to "delivery"), 404, "feature_off")
        // switched off: nothing new — but the claim can be confirmed and refunded, the lists still work
        onlyOn()
        acceptedOrder("o3")
        fails(PH, "pay_request_create", mapOf("refType" to "rx_order", "refId" to "o3", "purpose" to "delivery"), 404, "feature_off")
        fails(PH, "pay_methods_set", mapOf("kind" to "pharmacy", "methods" to listOf(bkash("01711000005"))), 404, "feature_off")
        assertEquals(200, confirm(PH, id).status)
        assertEquals(200, call(PH, "pay_refund", mapOf("id" to id, "trxId" to "FR12345678")).status)
        assertEquals(1, ok(PAT, "my_payments")["items"].size())
        assertEquals(1, ok(PH, "pay_list_biz", mapOf("kind" to "pharmacy"))["items"].size())
        assertEquals(200, call(PAT, "pay_refund_ack", mapOf("id" to id)).status)
        fails(PAT, "pgw_checkout", mapOf("purpose" to "lifolo_subscription"), 404, "feature_off")
    }

    // ------------------------------------------------------------------ demo and real

    @Test
    fun `demo and real never mix`() {
        shop("demo-pyph")
        person("demo-pypat")
        person(PAT)
        ok("demo-pyph", "pay_methods_set", mapOf("kind" to "pharmacy", "methods" to listOf(bkash("01200000301"))))
        mature("demo-pyph")
        // a real patient's order at a demo pharmacy: no request
        acceptedOrder("o1", patient = PAT, pharmacy = "demo-pyph")
        fails("demo-pyph", "pay_request_create", mapOf("refType" to "rx_order", "refId" to "o1", "purpose" to "delivery"), 404)
        acceptedOrder("o2", patient = "demo-pypat", pharmacy = "demo-pyph")
        val r = create("demo-pyph", "rx_order", "o2", "delivery")
        assertEquals(true, r["demo"].booleanValue())
        assertEquals("ডেমো — আসল টাকা পাঠাবেন না", ok("demo-pypat", "pay_request_get", mapOf("id" to r["id"].textValue()))["demoNote"].textValue())
        fails(PAT, "pay_request_get", mapOf("id" to r["id"].textValue()), 404)
        assertEquals(404, claim(PAT, r["id"].textValue(), "DM12345678").status)
    }

    // ------------------------------------------------------------------ the online chamber fee on this layer

    @Test
    fun `chamber fee - the new actions keep the appointment in step, the old appt_pay actions keep working on the same request`() {
        doctor(DOC, "asst@example.com")
        person(PAT)
        ready("chamber", DOC, DOC)
        appt("on1", online = true)
        val r = ok(PAT, "pay_request_get", mapOf("refType" to "appointment", "refId" to "on1"))["request"]
        assertEquals(listOf("chamber_fee", "open", "01711000001"), listOf(r["purpose"].textValue(), r["status"].textValue(), r["methods"][0]["number"].textValue()))
        assertEquals(500.0, d(r["amount"]))
        val id = r["id"].textValue()
        assertEquals(200, claim(PAT, id, "CF12345678").status)
        assertEquals(listOf("claimed", "CF12345678"), row("SELECT pay_state, pay_ref FROM appointments WHERE id = 'on1'").let { listOf(it["pay_state"], it["pay_ref"]) })
        val cf = confirm(ASST, id)
        assertEquals(200, cf.status, cf.toString())
        assertEquals("paid", row("SELECT pay_state FROM appointments WHERE id = 'on1'")["pay_state"])
        assertEquals(listOf("appointment", "on1", "CF12345678"), listOf(cf.body["invoice"]["refType"], cf.body["invoice"]["refId"], cf.body["invoice"]["payments"][0]["ref"]).map { it.textValue() })
        assertEquals(listOf("unpaid→claimed", "claimed→paid"), jdbc.queryForList("SELECT from_state || '→' || to_state FROM appt_pay_log WHERE appointment_id = 'on1' ORDER BY id", String::class.java))
        // the old actions on another appointment: the same request moves with them
        appt("on2", online = true, date = LocalDate.now().plusDays(4))
        ok(PAT, "appt_pay_claim", mapOf("appointmentId" to "on2", "payRef" to "OL12345678"))
        val r2 = ok(PAT, "pay_request_get", mapOf("refType" to "appointment", "refId" to "on2"))["request"]
        assertEquals(listOf("claimed", "OL12345678"), listOf(r2["status"].textValue(), r2["claim"]["trxId"].textValue()))
        // the TrxID once per doctor across both layers
        assertEquals("pay_ref_used", call(PAT, "appt_pay_claim", mapOf("appointmentId" to "on2", "payRef" to "CF12345678")).code)
        ok(DOC, "appt_pay_reject", mapOf("appointmentId" to "on2", "reason" to "পাইনি"))
        assertEquals(listOf("rejected", "পাইনি"), req(r2["id"].textValue()).let { listOf(it["status"], it["reject_note"]) })
        ok(PAT, "appt_pay_claim", mapOf("appointmentId" to "on2", "payRef" to "OL12345678"))
        val legacy = ok(DOC, "appt_pay_confirm", mapOf("checks" to mapOf("trxId" to true, "amount" to true, "last4" to true), "appointmentId" to "on2"))
        assertEquals("paid", legacy["payState"].textValue())
        assertNotNull(legacy["invoice"]["id"].textValue())
        assertEquals(listOf("confirmed", legacy["invoice"]["id"].textValue()), req(r2["id"].textValue()).let { listOf(it["status"], it["invoice_id"]) })
    }

    // ------------------------------------------------------------------ lists, summary, CSV, deletion

    @Test
    fun `business lists, day summary, CSV for the owner, my_payments, account deletion keeps the business's record`() {
        doctor(DOC, "asst@example.com")
        person(PAT)
        ready("chamber", DOC, DOC)
        val inv = dueInvoice("chamber", DOC, DOC, 500, 0)
        val id = ok(PAT, "pay_due_start", mapOf("invoiceId" to inv.id))["request"]["id"].textValue()
        assertEquals(200, claim(PAT, id, "LS12345678").status)
        assertEquals(1, ok(ASST, "pay_list_biz", mapOf("kind" to "chamber", "bizId" to DOC, "status" to "claimed"))["sum"]["pendingClaims"].intValue())
        assertEquals(200, confirm(DOC, id).status)
        val s = ok(ASST, "pay_summary", mapOf("kind" to "chamber", "bizId" to DOC))
        assertEquals(listOf(500.0, 1.0, 0.0, 0.0), listOf(d(s["collected"]), d(s["confirmedCount"]), d(s["openCount"]), d(s["pendingClaims"])))
        fails(ASST, "pay_export_csv", mapOf("kind" to "chamber", "bizId" to DOC), 403, "owner_only")
        val csv = ok(DOC, "pay_export_csv", mapOf("kind" to "chamber"))
        val text = csv["csv"].textValue()
        assertTrue(text.startsWith("﻿id,created,purpose,amount,status,method,trx_id,sender_last4,confirmed,refund_amount,refund_trx,receipt_no"))
        assertTrue(text.contains("LS12345678") && text.contains(inv.no))
        assertFalse(text.contains("01711000001"), "no phone numbers")
        assertEquals(1, csv["count"].intValue())
        assertEquals(listOf(id), ok(PAT, "my_payments")["items"].map { it["id"].textValue() })
        // the patient's account is deleted: the doctor keeps the record without the person
        deletionStep().deleteNow(PAT, null)
        val row = req(id)
        assertEquals(listOf(null, "LS12345678"), listOf(row["payer_uid"], row["claim_trx"]))
        assertNotNull(row["anonymised_at"])
    }

    @org.springframework.beans.factory.annotation.Autowired
    lateinit var deletion: PayDeletionStep

    private fun deletionStep() = deletion
}
