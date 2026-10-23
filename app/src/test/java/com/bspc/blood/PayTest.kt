package com.bspc.blood

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * লাইফলো পে (Pay.kt) — হিসাব আর নিয়ম: TrxID প্রতিটা সেবার নিয়মে, শেষ ৪ সংখ্যা, পার্সোনাল/মার্চেন্টের ধাপ, কখন পাঠানো যায়,
 * টাইমলাইন, সার্ভারের JSON, ফিচার সুইচের "needs", পুশের গন্তব্য আর সাধারণ লেখা, রক্তের পাতায় কোনো পেমেন্ট নেই।
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PayTest {
    private val now = 1_792_900_000_000L

    private val req = PayRequest(
        id = "p1", kind = "pharmacy", bizId = "ph1", bizName = "ডেমো লাইফ ফার্মেসি", refType = "rx_order", refId = "o1",
        purpose = PayRules.DELIVERY, amount = 500.0,
        methods = listOf(PayMethod("bkash", "01711223344", "personal"), PayMethod("nagad", "01811223344", "merchant"), PayMethod("rocket", "01911223344", "personal", active = false)),
        createdAt = now - 3_600_000, expiresAt = now + 3_600_000,
    )

    @Before
    fun setUp() {
        AppPrefs.lang.value = Lang.BN
    }

    @After
    fun tearDown() {
        PayGate.testKeys = null
        OnlineGate.testKeys = null
        PayStore.clear()
    }

    @Test
    fun trxPerMethod() {
        assertTrue(PayRules.trxOk("bkash", "8N7A6B5C4D"))
        assertFalse(PayRules.trxOk("bkash", "8N7A6B5C4"))
        assertFalse(PayRules.trxOk("bkash", "8N7A6B5C4DX"))
        assertFalse(PayRules.trxOk("bkash", "8N7A6B5C4-"))
        // নগদ/রকেট/উপায়: ৬–২০
        assertTrue(PayRules.trxOk("nagad", "7XK29Q"))
        assertTrue(PayRules.trxOk("rocket", "A".repeat(20)))
        assertFalse(PayRules.trxOk("upay", "7XK29"))
        assertFalse(PayRules.trxOk("nagad", "A".repeat(21)))
        // লেখার সময় পরিষ্কার: বাংলা অঙ্ক, ছোট হাতের, চিহ্ন বাদ, দৈর্ঘ্য পর্যন্ত
        assertEquals("8N7A6B5C4D", PayRules.cleanTrx("bkash", "8n7a-6b5c4d99"))
        assertEquals("AB১২".let { PayRules.cleanTrx("nagad", it) }, "AB12")
        assertEquals(20, PayRules.cleanTrx("nagad", "x".repeat(30)).length)
        assertEquals("1234", PayRules.cleanLast4("১২৩৪৫"))
        assertTrue(PayRules.last4Ok("0042"))
        assertFalse(PayRules.last4Ok("042"))
        assertEquals(10, PayRules.trxMax("bkash"))
        assertEquals(20, PayRules.trxMax("upay"))
    }

    @Test
    fun labelsAndSteps() {
        // লোগো নয় — লেখা; "নগদ" (ক্যাশ) শব্দের সাথে যেন না গুলায়
        assertEquals(listOf("bKash", "Nagad", "Rocket", "Upay"), PayRules.METHODS.map(PayText::method))
        val personal = PayMethod("bkash", "01711223344", "personal")
        val merchant = PayMethod("nagad", "01811223344", "merchant")
        assertEquals("ডাক্তারের bKash (সেন্ড মানি)", PayText.numberLabel("chamber", personal))
        assertEquals("ফার্মেসির Nagad (পেমেন্ট — মার্চেন্ট)", PayText.numberLabel("pharmacy", merchant))
        val p = PayText.steps(personal, "৳৫০০", "ডাক্তার")
        assertEquals("bKash অ্যাপে \"সেন্ড মানি\" করুন — ৳৫০০ উপরের নম্বরে", p[0])
        assertEquals("bKash-এর মেসেজের TrxID রেখে দিন", p[1])
        assertEquals("\"টাকা পাঠিয়েছি\" চাপুন — ডাক্তার মিলিয়ে নিশ্চিত করবেন", p[2])
        val m = PayText.steps(merchant, "৳৫০০", "ফার্মেসি")
        assertEquals("Nagad অ্যাপে \"পেমেন্ট\" করুন — ৳৫০০ উপরের মার্চেন্ট নম্বরে", m[0])
        // টাকা না জানা থাকলে " — " নেই
        assertEquals("bKash অ্যাপে \"সেন্ড মানি\" করুন উপরের নম্বরে", PayText.steps(personal, "", "ডাক্তার")[0])
        assertEquals("bKash-এর TrxID ঠিক ১০ অক্ষরের (ইংরেজি অক্ষর ও সংখ্যা)", PayText.trxHint("bkash"))
        assertEquals("Rocket-এর TrxID ৬ থেকে ২০ অক্ষরের (ইংরেজি অক্ষর ও সংখ্যা)", PayText.trxHint("rocket"))
        AppPrefs.lang.value = Lang.EN
        assertEquals("Tap \"I've sent the money\" — the doctor confirms it", PayText.steps(personal, "", PayText.who("chamber"))[2])
    }

    @Test
    fun whenCanClaim() {
        assertEquals(listOf("bkash", "nagad"), req.activeMethods.map { it.method })
        assertTrue(PayRules.canClaim(req, now))
        assertTrue(PayRules.canClaim(req.copy(status = "rejected"), now))
        assertFalse(PayRules.canClaim(req.copy(status = "claimed"), now))
        assertFalse(PayRules.canClaim(req.copy(status = "confirmed"), now))
        // সময় পেরোলে নয়
        assertFalse(PayRules.canClaim(req, now + 2 * 3_600_000))
        // চালু নম্বর না থাকলে নয়
        assertFalse(PayRules.canClaim(req.copy(methods = req.methods.map { it.copy(active = false) }), now))
        // রক্তে কখনো নয়
        assertFalse(PayRules.canClaim(req.copy(refType = "blood_request"), now))
        assertTrue(PayRules.isBlood("request"))
        assertTrue(PayRules.isBlood("bloodBank"))
        assertFalse(PayRules.isBlood("ambreq"))

        val refunded = req.copy(status = "refunded", claim = PayClaim("bkash", "8N7A6B5C4D", "1234", now), refund = PayRefund("9Z8Y7X6W5V", 500.0, now, 0L))
        assertTrue(PayRules.canAckRefund(refunded))
        assertFalse(PayRules.canAckRefund(refunded.copy(refund = refunded.refund!!.copy(ackAt = now))))
        assertTrue(PayRules.canReport(refunded))
        assertFalse(PayRules.canReport(req))
        // ব্যবসা "ফেরত পাঠিয়েছি" বললে রোগী নিশ্চিত না করা পর্যন্ত refund_claimed
        assertTrue(PayRules.canAckRefund(req.copy(status = "refund_claimed", refund = PayRefund("9Z8Y7X6W5V", 500.0, now, 0L))))
        assertTrue(PayRules.canReport(req.copy(status = "rejected")))
        // অগ্রিম বাকি — বুকিং থাকে
        assertTrue(PayRules.advanceDue(req.copy(purpose = PayRules.ADVANCE)))
        assertFalse(PayRules.advanceDue(req.copy(purpose = PayRules.ADVANCE, status = "claimed")))
        assertTrue(PayRules.canDecide(req.copy(status = "claimed")))
        assertTrue(PayRules.canRefund(req.copy(status = "confirmed")))
        assertFalse(PayRules.canRefund(req.copy(status = "claimed")))
    }

    @Test
    fun rejectAndRefundInputs() {
        assertFalse(PayRules.rejectOk(null, ""))
        assertTrue(PayRules.rejectOk("not_received", ""))
        assertFalse(PayRules.rejectOk("other", "না"))
        assertTrue(PayRules.rejectOk("other", "অন্য কারণ"))
        assertTrue(PayRules.refundAmountOk("", 500.0))
        assertTrue(PayRules.refundAmountOk("২০০", 500.0))
        assertTrue(PayRules.refundAmountOk("500", 500.0))
        assertFalse(PayRules.refundAmountOk("501", 500.0))
        assertFalse(PayRules.refundAmountOk("0", 500.0))
    }

    @Test
    fun timeline() {
        fun labels(r: PayRequest) = PayRules.timeline(r).map { it.label to it.mark }
        assertEquals(PayRules.Mark.NOW, labels(req)[1].second)
        val claimed = req.copy(status = "claimed", claim = PayClaim("bkash", "8N7A6B5C4D", "1234", now))
        assertEquals(listOf("পেমেন্টের অনুরোধ", "টাকা পাঠিয়েছেন", "ফার্মেসি যাচাই করছেন"), labels(claimed).map { it.first })
        val rejected = claimed.copy(status = "rejected", rejectReason = "wrong_amount", rejectNote = "৪০০ এসেছে")
        assertEquals("ফার্মেসি পাননি: টাকার অঙ্ক মেলেনি — ৪০০ এসেছে" to PayRules.Mark.BAD, labels(rejected)[2])
        val refundClaimed = claimed.copy(status = "refund_claimed", confirmedAt = now, refund = PayRefund("9Z8Y7X6W5V", 0.0, now, 0L))
        assertEquals("ফার্মেসি বলছেন টাকা ফেরত পাঠিয়েছেন", labels(refundClaimed)[3].first)
        assertEquals("ফেরত পেয়েছেন?" to PayRules.Mark.NOW, labels(refundClaimed).last())
        assertEquals("ফেরত পেয়েছেন" to PayRules.Mark.DONE, labels(refundClaimed.copy(status = "refunded", refund = refundClaimed.refund!!.copy(ackAt = now))).last())
        assertEquals(PayRules.Mark.BAD, labels(req.copy(status = "expired")).last().second)
    }

    @Test
    fun parseServerJson() {
        val o = JSONObject(
            """{"request":{"id":"p9","kind":"lab","bizId":"lab1","bizName":"ডেমো সিটি ডায়াগনস্টিক","payerUid":"u1","payerName":"রহিম",
            "refType":"lab_order","refId":"lo1","purpose":"lab","amount":1250.5,
            "methods":[{"method":"bKash","number":"01711-223344","type":"merchant"},{"method":"upay","number":"01911223344","type":"personal","active":false}],
            "status":"claimed","claim":{"method":"bkash","trxId":"8N7A6B5C4D","senderLast4":"1234","at":"2026-10-23T10:00:00Z"},
            "refund":null,"invoiceId":"inv1","expiresAt":1792900000000,"createdAt":"2026-10-23T09:00:00.123456Z"}}""",
        )
        val r = PayApi.parse(PayApi.pick(o))
        assertEquals("p9", r.id)
        assertEquals(1250.5, r.amount, 0.001)
        assertEquals("bkash", r.methods[0].method)
        assertEquals("01711223344", r.methods[0].number)
        assertEquals("merchant", r.methods[0].type)
        assertFalse(r.methods[1].active)
        assertEquals(listOf("bkash"), r.activeMethods.map { it.method })
        assertEquals("8N7A6B5C4D", r.claim?.trxId)
        assertTrue(r.claim!!.at > 0)
        assertTrue(r.createdAt > 0)
        assertNull(r.refund)
        assertEquals("রহিম", r.payerName)

        // pay_request_get: policy, demoNote; claim.by (কেয়ারগিভার), refundDue
        val g = PayApi.withPolicy(JSONObject("""{"request":{"id":"p1","payerUid":"u1","status":"confirmed","claim":{"method":"nagad","trxId":"7XK29QF3","senderLast4":"9876","at":1,"by":"cg1"},"refundDue":{"at":1792900000000,"why":"not_delivered"}},"side":"payer","policy":"নীতি","demoNote":"ডেমো"}"""))
        assertEquals("cg1", g.claim?.by)
        assertEquals(PayRefundDue(1792900000000L, "not_delivered"), g.refundDue)
        assertEquals("নীতি", g.policy)
        assertEquals("ডেমো", g.demoNote)
        // তালিকায় রক্তের কিছু এলে বাদ
        val page = PayApi.page(JSONObject("""{"items":[{"id":"a","refType":"ambreq"},{"id":"b","refType":"blood_request"}],"cursor":"c2"}"""))
        assertEquals(listOf("a"), page.items.map { it.id })
        assertEquals("c2", page.cursor)

        val s = PayApi.summary(JSONObject("""{"collected":2500,"open":3,"pendingClaims":2}"""))
        assertEquals(PaySummary(2500.0, 3, 2, 0), s)

        val h = PayApi.settings(JSONObject("""{"methods":[],"history":[{"method":"nagad","change":"added","oldLast4":null,"newLast4":"3344","at":1792900000000}],"frozen":true}"""))
        assertEquals(PayMethodChange("nagad", "added", "", "3344", 1792900000000L), h.changes.single())
        assertTrue(h.frozen)
        val st = PayApi.settings(JSONObject("""{"methods":[{"method":"bkash","number":"01711223344","type":"personal","active":true,"pendingUntil":1792900000000},{"method":"card","number":"x"}],
            "chambers":[{"chamberKey":"dghs_1","name":"X","nameBn":"এক্স","advance":{"amount":200,"refundableUntilHoursBefore":6}}]}"""))
        assertEquals(1, st.methods.size)
        assertEquals(1792900000000L, st.methods[0].pendingUntil)
        assertEquals(ChamberAdvance("dghs_1", "X", "এক্স", 200.0, 6), st.advances.single())
        // সার্ভারের আসল রূপ: chambers আর advance আলাদা
        val real = PayApi.settings(JSONObject("""{"methods":[],"chambers":[{"chamberKey":"h1","name":"A","nameBn":"এ"},{"chamberKey":"h2","name":"B","nameBn":"বি"}],
            "advance":[{"chamberKey":"h2","amount":300,"refundableUntilHoursBefore":12}]}"""))
        assertEquals(listOf(ChamberAdvance("h1", "A", "এ"), ChamberAdvance("h2", "B", "বি", 300.0, 12)), real.advances)
    }

    @Test
    fun requestBodies() {
        val c = PayApi.claimBody("p1", "bkash", "8n7a6b5c4d", "১২৩৪")
        assertEquals("8N7A6B5C4D", c.getString("trxId"))
        assertEquals("1234", c.getString("senderLast4"))
        assertEquals("bkash", c.getString("method"))
        val m = PayApi.methodsJson(listOf(PayMethod("nagad", "০১৮১১-২২৩৩৪৪", "merchant", true)))
        assertEquals("01811223344", m.getJSONObject(0).getString("number"))
        assertEquals("merchant", m.getJSONObject(0).getString("type"))
        // অগ্রিম: পুরো তালিকা একসাথে, বন্ধগুলো বাদ
        val adv = PayApi.advanceBody("doc1", listOf(ChamberAdvance("dghs_1", amount = 200.0, refundableUntilHoursBefore = 12), ChamberAdvance("dghs_2")))
        assertEquals(1, adv.getJSONArray("advance").length())
        assertEquals(12, adv.getJSONArray("advance").getJSONObject(0).getInt("refundableUntilHoursBefore"))
        assertEquals(200L, adv.getJSONArray("advance").getJSONObject(0).getLong("amount"))
        assertEquals(0, PayApi.advanceBody("doc1", listOf(ChamberAdvance("dghs_2"))).getJSONArray("advance").length())
        // নিশ্চিত করার তিনটা মিল সার্ভারে যায়
        val cb = PayApi.confirmBody("p1", setOf("trxId", "amount")).getJSONObject("checks")
        assertTrue(cb.getBoolean("trxId") && cb.getBoolean("amount"))
        assertFalse(cb.getBoolean("last4"))
    }

    @Test
    fun confirmNeedsAllChecks() {
        val claimed = req.copy(status = "claimed", claim = PayClaim("bkash", "8N7A6B5C4D", "1234", now))
        assertEquals(listOf("trxId", "amount", "last4"), PayRules.confirmChecks(claimed))
        assertFalse(PayRules.confirmReady(claimed, setOf("trxId", "amount")))
        assertTrue(PayRules.confirmReady(claimed, setOf("trxId", "amount", "last4")))
        // পুরোনো দাবি (শেষ ৪ সংখ্যা নেই): দুটো
        assertTrue(PayRules.confirmReady(claimed.copy(claim = claimed.claim!!.copy(senderLast4 = "")), setOf("trxId", "amount")))
    }

    @Test
    fun safetyTexts() {
        assertEquals("শুধু এখানে দেখানো নম্বরে টাকা পাঠান। কেউ চ্যাটে বা ফোনে অন্য নম্বর দিলে পাঠাবেন না, রিপোর্ট করুন।", PayText.ONLY_HERE)
        assertTrue("asked_other_number" in PayText.REPORT_REASONS)
        assertEquals("অন্য নম্বরে টাকা চেয়েছে", PayText.reportReason("asked_other_number"))
        // টাকা দেওয়ার আগে ফেরতের নিয়ম; অগ্রিম না দিলেও বুকিং থাকে
        val adv = PayText.refundPolicy(req.copy(purpose = PayRules.ADVANCE, refundableUntil = 0L))
        assertTrue(adv.startsWith("ডাক্তার বাতিল করলে পুরো টাকা ফেরত"))
        assertTrue(adv.contains("অগ্রিম না দিলেও বুকিং বাতিল হয় না"))
        assertTrue(PayText.refundPolicy(req).contains("যে নম্বর থেকে দিয়েছেন সেখানে"))
        assertTrue(PayText.error(ServerError("x", "pay_ref_used")).contains("আবার টাকা পাঠাবেন না"))
        assertEquals("এই TrxID দোকান বাতিল করেছে — রিপোর্ট করুন।", PayText.error(ServerError("x", "pay_ref_rejected")))
        assertTrue(PayReauth.needed(ServerError("x", "reauth_required")))
        assertEquals(PayText.LAST4_HINT, PayText.error(ServerError("x", "sender_digits")))
        assertEquals(PayText.NO_NUMBERS, PayText.error(ServerError("x", "no_methods")))
        assertFalse(PayReauth.needed(ServerError("x", "state")))
        assertEquals("bKash বদলেছে (…1111 → …2222)", PayBiz.changeText(PayMethodChange("bkash", "changed", "1111", "2222", 0L)))
    }

    @Test
    fun methodsSettings() {
        val rows = payMethodRows(listOf(PayMethod("nagad", "01811223344", "merchant")))
        assertEquals(PayRules.METHODS, rows.map { it.method })
        assertFalse(rows[0].active)
        assertEquals("অন্তত একটা নম্বর চালু করুন", payMethodsProblem(rows.map { it.copy(active = false) }, demo = false))
        assertNull(payMethodsProblem(rows, demo = false))
        assertEquals("সঠিক bKash নম্বর দিন (01XXXXXXXXX)", payMethodsProblem(rows.toMutableList().also { it[0] = PayMethod("bkash", "0171", "personal", true) }, demo = false))
    }

    @Test
    fun featureNeeds() {
        // সব বন্ধ (প্রোডাকশনের ডিফল্ট)
        PayGate.testKeys = emptyMap()
        OnlineGate.testKeys = emptyMap()
        assertFalse(PayGate.on(PayGate.MANUAL))
        assertFalse(PayGate.patient())
        assertFalse(PayGate.biz("pharmacy"))
        // pay_manual ছাড়া কিছুই নয়
        PayGate.testKeys = mapOf(PayGate.DELIVERY to true, "inv_pharmacy" to true)
        assertFalse(PayGate.on(PayGate.DELIVERY))
        // রসিদের চাবিও লাগে
        PayGate.testKeys = mapOf(PayGate.MANUAL to true, PayGate.DELIVERY to true)
        assertFalse(PayGate.on(PayGate.DELIVERY))
        PayGate.testKeys = mapOf(PayGate.MANUAL to true, PayGate.DELIVERY to true, "inv_pharmacy" to true, PayGate.DUE to true)
        assertTrue(PayGate.on(PayGate.DELIVERY))
        assertTrue(PayGate.purposeOn(PayRules.DELIVERY))
        assertFalse(PayGate.purposeOn(PayRules.LAB))
        assertTrue(PayGate.biz("pharmacy"))
        // pay_due চালু: সব ব্যবসার "পেমেন্ট" (বাকি টাকা যেকোনো রসিদে)
        assertTrue(PayGate.biz("ambulance"))
        assertTrue(PayGate.patient())
        // অ্যাম্বুলেন্সের রসিদ বন্ধ থাকলে ভাড়া নয়
        PayGate.testKeys = mapOf(PayGate.MANUAL to true, PayGate.AMBULANCE to true)
        assertFalse(PayGate.on(PayGate.AMBULANCE))
        // অনলাইন চেম্বারের ফি নিজের চাবিতে
        assertEquals(OnlineGate.CHAMBER, PayGate.keyFor(PayRules.CHAMBER_FEE))
        PayGate.testKeys = emptyMap()
        OnlineGate.testKeys = mapOf(OnlineGate.CHAMBER to true)
        assertTrue(PayGate.purposeOn(PayRules.CHAMBER_FEE))
        assertTrue(PayGate.patient())
        assertEquals(listOf("pay_manual", "pay_chamber_advance", "pay_delivery", "pay_lab", "pay_ambulance", "pay_due", "pay_bkash_pgw"), PayGate.ALL)
    }

    @Test
    fun pushRoutesAndText() {
        // ব্যবসাকে দাবি → "পেমেন্ট"
        assertEquals(NotifyRoute.PayBiz("pharmacy", "ph1"), notifyRoute(mapOf("type" to "pay_claimed", "kind" to "pharmacy", "bizId" to "ph1")))
        assertEquals(NotifyRoute.PayBiz("chamber", null), notifyRoute(mapOf("type" to "pay_reminder", "kind" to "chamber")))
        // নম্বর বদলের খবর → সেটিংস ("আমি করিনি")
        assertEquals(NotifyRoute.PayBiz("lab", null, tab = 2), notifyRoute(mapOf("type" to "pay_methods_changed", "kind" to "lab")))
        // সার্ভারের ব্যবসার পুশ: {type, payRequestId, purpose, cat} — kind কাজ থেকে, invoice_due হলে অনুরোধ খুলে
        assertEquals(NotifyRoute.PayBiz("pharmacy", null, reqId = "p8"), notifyRoute(mapOf("type" to "pay_claimed", "payRequestId" to "p8", "purpose" to "delivery")))
        assertEquals(NotifyRoute.PayBiz("", null, reqId = "p9"), notifyRoute(mapOf("type" to "pay_reminder", "payRequestId" to "p9", "purpose" to "invoice_due")))
        assertEquals(NotifyRoute.PayBiz("chamber", null, tab = 1, reqId = "p10"), notifyRoute(mapOf("type" to "pay_refund_due", "payRequestId" to "p10", "purpose" to "booking_advance")))
        // সার্ভারের পুশ: payRequestId
        assertEquals(NotifyRoute.Pay("p7", null), notifyRoute(mapOf("type" to "pay_rejected", "payRequestId" to "p7", "kind" to "pharmacy")))
        assertEquals(NotifyRoute.Tab(Tabs.WORK), notifyRoute(mapOf("type" to "pay_claimed"), hasWork = true))
        // রোগীকে: সেই পেমেন্ট (কেয়ারগিভার asUid সহ); id না থাকলে তালিকা
        assertEquals(NotifyRoute.Pay("p1", null), notifyRoute(mapOf("type" to "pay_confirmed", "payId" to "p1")))
        assertEquals(NotifyRoute.Pay("p2", "u9"), notifyRoute(mapOf("type" to "pay_requested", "id" to "p2", "asUid" to "u9")))
        assertEquals(NotifyRoute.Pay(null, null), notifyRoute(mapOf("type" to "pay_refunded")))
        // পেমেন্টের খবরে requestId থাকলেও রক্তের পাতায় নয়
        assertEquals(NotifyRoute.Pay("p3", null), notifyRoute(mapOf("type" to "pay_rejected", "payId" to "p3", "requestId" to "r1")))
        assertTrue("payId" in Notifier.DATA_KEYS && "bizId" in Notifier.DATA_KEYS)
        // লক-স্ক্রিনে টাকা/নম্বর নয়
        assertEquals("পেমেন্ট আপডেট — লাইফলো" to "অ্যাপ খুলে দেখুন", PayPush.text("pay_confirmed", "৳৫০০ পেয়েছে", "01711223344"))
        assertEquals("শিরোনাম" to "লেখা", PayPush.text("invoice_new", "শিরোনাম", "লেখা"))
        assertEquals(NotifyCats.BUSINESS, NotifyCats.fromType("pay_claimed"))
        assertEquals(NotifyCats.BUSINESS, NotifyCats.fromType("pay_reminder"))
        assertEquals(NotifyCats.MY_REQUEST, NotifyCats.fromType("pay_confirmed"))
    }

    @Test
    fun storeFindsByRef() {
        val a = req.copy(id = "old", status = "expired", createdAt = now - 10_000)
        val b = req.copy(id = "new", status = "open", createdAt = now - 20_000)
        PayStore.set(listOf(a, b))
        // সময় পেরোনোটা নয়, চালুটা
        assertEquals("new", PayStore.forRef("o1", PayRules.DELIVERY)?.id)
        assertNull(PayStore.forRef("o1", PayRules.LAB))
        assertNull(PayStore.forRef("o1", PayRules.DELIVERY, asUid = "u9"))
        PayStore.put(req.copy(id = "care", refId = "o2"), "u9")
        assertEquals("care", PayStore.forRef("o2", asUid = "u9")?.id)
        assertEquals("care", PayStore.byId("care")?.id)
    }

    @Test
    fun bookingAnswerOpensAdvance() {
        assertNull(PayEntry.fromResponse(JSONObject().put("ok", true)))
        val r = PayEntry.fromResponse(JSONObject().put("payRequest", JSONObject().put("id", "pa1").put("purpose", "booking_advance").put("amount", 200)))
        assertNotNull(r)
        assertEquals(PayRules.ADVANCE, r!!.purpose)
        assertEquals(200.0, r.amount, 0.0)
    }

    @Test
    fun onlineChamberOnPayLayer() {
        val p = OnlinePay.parse(JSONObject().put("payState", "claimed").put("payRef", "8N7A6B5C4D").put("fee", "৫০০ টাকা").put("bkashNumber", "01711223344").put("payRequestId", "pr1"))
        assertEquals("pr1", p.requestId)
        val r = p.asRequest("a1", "ডা. ডেমো")
        assertEquals("pr1", r.id)
        assertEquals("claimed", r.status)
        assertEquals(500.0, r.amount, 0.0)
        assertEquals(PayRules.CHAMBER_FEE, r.purpose)
        assertEquals(listOf(PayMethod("bkash", "01711223344", "personal")), r.methods)
        assertEquals("8N7A6B5C4D", r.claim?.trxId)
        // পুরোনো অ্যাপয়েন্টমেন্ট (অনুরোধ ছাড়া) — আগের পথ
        assertEquals("", OnlinePay.parse(JSONObject().put("payState", "unpaid")).requestId)
        assertEquals("pr2", OnlinePay.parse(JSONObject().put("payRequest", JSONObject().put("id", "pr2"))).requestId)
        assertEquals("open", OnlinePay(state = "unpaid").asRequest("a").status)
        assertEquals("rejected", OnlinePay(state = "unpaid", rejected = true).asRequest("a").status)
        assertEquals("confirmed", OnlinePay(state = "paid").asRequest("a").status)
    }

    /** রক্তের পাতাগুলোতে কোনো পেমেন্টের অংশ নেই (মালিকের নিয়ম; সার্ভারও no_payment_blood) */
    @Test
    fun noPayInBloodScreens() {
        val dir = listOf(File("src/main/java/com/bspc/blood"), File("app/src/main/java/com/bspc/blood")).first { it.isDirectory }
        val blood = listOf("RequestScreens.kt", "MyRequestUi.kt", "BloodBank.kt", "Emergency.kt", "Repositories.kt", "Screens.kt", "Community.kt")
        val payBits = listOf("PaySheet", "PayRefButton", "PayRefAction", "PayNav", "PayApi", "PayDueButton", "AmbFarePay", "BizPaymentsButton", "PayAskAction", "PayEntry", "PayClaimSection")
        blood.map { File(dir, it) }.filter { it.exists() }.forEach { f ->
            val text = f.readText()
            payBits.forEach { b -> assertFalse("${f.name} uses $b", text.contains(b)) }
        }
        // আর পেমেন্টের API রক্তের অনুরোধ বানাতেই দেয় না
        val e = runCatching { kotlinx.coroutines.runBlocking { PayApi.create("blood_request", "r1", "delivery") } }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
    }
}
