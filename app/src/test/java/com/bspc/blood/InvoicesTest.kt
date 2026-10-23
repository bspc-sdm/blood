package com.bspc.blood

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * রসিদ (Invoices.kt): টাকা/অঙ্কের লেখা (বাংলা ও ইংরেজি অঙ্ক), মাস, যাচাইয়ের কোড, ফর্মের হিসাব, সার্ভারের JSON পড়া
 * (চুক্তি: backend/docs/INVOICES.md), নোটিফিকেশন invoice_new আর QR লিংকের গন্তব্য।
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InvoicesTest {
    @Before
    fun setUp() {
        AppPrefs.lang.value = Lang.BN
    }

    @Test
    fun moneyAndDigits() {
        assertEquals("৳১,২৩৪", InvoiceText.money(1234.0, true))
        assertEquals("৳1,234", InvoiceText.money(1234.0, false))
        assertEquals("৳১,২৩৪.৫০", InvoiceText.money(1234.5, true))
        assertEquals("৳0.05", InvoiceText.money(0.049, false))
        assertEquals("−৳20", InvoiceText.money(-20.0, false))
        assertEquals("৳0", InvoiceText.money(-0.001, false))
        assertEquals("W-০০০১২৩", InvoiceText.digits("W-000123", true))
        assertEquals("W-000123", InvoiceText.digits("W-০০০১২৩", false))
        assertEquals("২.৫", InvoiceText.qty(2.5, true))
        assertEquals("10", InvoiceText.qty(10.0, false))
    }

    @Test
    fun dateDigitsFollowToggle() {
        val t = 1_792_730_700_000L // 2026-10-22
        val bn = InvoiceText.dateTime(t, true)
        val en = InvoiceText.dateTime(t, false)
        assertTrue(bn.isNotBlank() && bn.none { it in '0'..'9' })
        assertTrue(en.isNotBlank() && en.none { it in '০'..'৯' })
        assertEquals("", InvoiceText.dateTime(0L, true))
    }

    @Test
    fun months() {
        assertEquals("2026-09", InvoiceMonths.shift("2026-10", -1))
        assertEquals("2027-01", InvoiceMonths.shift("2026-12", 1))
        assertEquals("2025-12", InvoiceMonths.shift("2026-01", -1))
        assertEquals("2026-10", InvoiceMonths.shift("2026-10", 0))
        assertNull(InvoiceMonths.parts("2026-13"))
        // ঢাকার সময়: ৩১ অক্টোবর রাত ২০:৩০ UTC = ১ নভেম্বর ০২:৩০ ঢাকা
        assertEquals("2026-11", InvoiceMonths.current(1_793_478_600_000L))
    }

    @Test
    fun verifyCodes() {
        assertEquals("Ab12Cd34Ef", InvoiceText.codeOf("https://lifolo.com/v/Ab12Cd34Ef"))
        assertEquals("Ab12Cd34Ef", InvoiceText.codeOf(" https://api.lifolo.com/v/Ab12Cd34Ef?x=1 "))
        assertEquals("AB12CD34EF", InvoiceText.codeOf("AB12CD34EF"))
        assertNull(InvoiceText.codeOf("https://lifolo.com/rx/AB12CD34EF"))
        assertNull(InvoiceText.codeOf("hello"))
        assertEquals("Ab12Cd34Ef", InvoiceText.codeFromLink("https", "lifolo.com", "/v/Ab12Cd34Ef", "lifolo.com"))
        assertEquals("Ab12Cd34Ef", InvoiceText.codeFromLink("https", "api.lifolo.com", "/v/Ab12Cd34Ef/", "api.lifolo.com"))
        assertNull(InvoiceText.codeFromLink("https", "evil.com", "/v/Ab12Cd34Ef", "lifolo.com"))
        assertNull(InvoiceText.codeFromLink("http", "lifolo.com", "/v/Ab12Cd34Ef", "lifolo.com"))
        assertNull(InvoiceText.codeFromLink("https", "lifolo.com", "/r/abc", "lifolo.com"))
        assertEquals("017•••••234", InvoiceText.maskPhone("01712345234"))
    }

    @Test
    fun formMath() {
        assertEquals(120.5, InvoiceMath.parse("১২০.৫")!!, 0.0)
        assertEquals(1500.0, InvoiceMath.parse("৳1,500")!!, 0.0)
        assertNull(InvoiceMath.parse(" "))
        assertEquals(-1.0, InvoiceMath.parse("12a")!!, 0.0)
        assertEquals(-1.0, InvoiceMath.parse("1.234")!!, 0.0)
        val lines = listOf(InvLine("CBC", 1.0, "", 400.0, 0.0, 0.0), InvLine("Napa", 10.0, "", 1.2, 2.0, 0.0))
        assertEquals(410.0, InvoiceMath.subtotal(lines), 0.001)
        assertEquals(460.0, InvoiceMath.total(lines, 50.0, listOf(InvFee("Home", 100.0))), 0.001)
        assertEquals(0.0, InvoiceMath.total(lines, 9999.0, emptyList()), 0.0)
        assertTrue(InvoiceMath.overChart(1600.0, 1500))
        assertFalse(InvoiceMath.overChart(1500.0, 1500))
        assertFalse(InvoiceMath.overChart(5000.0, null))
        assertFalse(InvoiceMath.overChart(null, 1500))
    }

    @Test
    fun lineDrafts() {
        assertEquals(emptyList<InvLine>(), LineDrafts.toLines(listOf(LineDraft())))
        assertNull(LineDrafts.toLines(listOf(LineDraft("CBC", "1", ""))))
        assertNull(LineDrafts.toLines(listOf(LineDraft("", "1", "400"))))
        assertNull(LineDrafts.toLines(listOf(LineDraft("CBC", "0", "400"))))
        val ok = LineDrafts.toLines(listOf(LineDraft("CBC", "", "৪০০"), LineDraft("ESR", "2", "150.5")))!!
        assertEquals(2, ok.size)
        assertEquals(1.0, ok[0].qty, 0.0)
        assertEquals(301.0, ok[1].amount, 0.001)
        val order = LabOrder(
            "o1", "rx1", "C", "Lab", "রহিম", 40, "m", "Dr", listOf("cbc", "esr", "xray"), "received", 0L, null,
            prices = PriceSnap(listOf(SnapItem("cbc", "CBC", 400, ""), SnapItem("esr", "ESR", 150, "")), listOf("X-ray"), 550, 10, 495),
        )
        val d = LineDrafts.fromLabOrder(order)
        assertEquals(listOf("CBC", "ESR", "X-ray"), d.map { it.name })
        assertEquals(listOf("400", "150", ""), d.map { it.price })
    }

    private fun sample(): JSONObject = JSONObject(
        """
        {"id":"inv1","no":"W-000123","kind":"pharmacy","bizId":"ph1","bizName":"ডেমো লাইফ ফার্মেসি","bizPhone":"01700000000",
         "patientUid":"u1","customer":{"name":"রহিম","phone":"01711111111"},"refType":"rx_order","refId":"ord1",
         "lines":[{"name":"Napa 500","qty":10,"unit":"pcs","price":1.2,"disc":0,"amount":12},{"name":"Seclo","qty":1,"price":70,"disc":5,"amount":65}],
         "subtotal":77,"disc":2,"fees":[{"label":"ডেলিভারি","amount":30}],"total":105,"paid":100,"due":5,
         "payments":[{"at":1792730700000,"amount":100,"method":"bkash","ref":"TRX1"}],
         "status":"issued","issuedAt":"2026-10-22T10:05:00.123456Z","issuedBy":"owner1","offline":false,
         "verifyCode":"Ab12Cd34Ef","verifyUrl":"https://lifolo.com/v/Ab12Cd34Ef"}
        """.trimIndent(),
    )

    @Test
    fun parseInvoice() {
        val i = InvoiceApi.parse(sample())
        assertEquals("W-000123", i.no)
        assertEquals("রহিম", i.customerName)
        assertEquals(2, i.lines.size)
        assertEquals("pcs", i.lines[0].unit)
        assertEquals(65.0, i.lines[1].amount, 0.0)
        assertEquals(30.0, i.fees.single().amount, 0.0)
        assertEquals("bkash", i.payments.single().method)
        assertEquals(1792730700000L, i.payments.single().at)
        assertEquals(1792663500123L, i.issuedAt)
        assertTrue(i.hasDue)
        assertFalse(i.isVoid)
        assertEquals("https://lifolo.com/v/Ab12Cd34Ef", i.link)
        val v = InvoiceApi.parse(sample().put("status", "void").put("voidReason", "ভুল").put("verifyUrl", JSONObject.NULL))
        assertTrue(v.isVoid)
        assertFalse(v.hasDue)
        assertEquals("https://lifolo.com/v/Ab12Cd34Ef", v.link)
        // উত্তর যেভাবেই আসুক
        assertEquals("inv1", InvoiceApi.parse(InvoiceApi.pick(JSONObject().put("invoice", sample()))).id)
        assertEquals("inv1", InvoiceApi.invoiceIn(JSONObject().put("ok", true).put("invoice", sample()))?.id)
        assertNull(InvoiceApi.invoiceIn(JSONObject().put("ok", true)))
    }

    @Test
    fun pagesChecksExpenses() {
        val p = InvoiceApi.page(JSONObject().put("items", JSONArray().put(sample())).put("cursor", "c2"))
        assertEquals(1, p.items.size)
        assertEquals("c2", p.cursor)
        assertNull(InvoiceApi.page(JSONObject().put("items", JSONArray()).put("cursor", JSONObject.NULL)).cursor)
        assertEquals("n3", InvoiceApi.page(JSONObject().put("invoices", JSONArray()).put("next", "n3")).cursor)
        val c = InvoiceApi.check(JSONObject().put("bizName", "Lab").put("kind", "lab").put("no", "D1-000004").put("issuedAt", 5L).put("total", 500).put("paid", 500).put("due", 0).put("status", "void"))
        assertTrue(c.isVoid)
        assertEquals(5L, c.issuedAt)
        val e = InvoiceApi.expenses(JSONObject().put("total", 1500.5).put("count", 3).put("byKind", JSONObject().put("pharmacy", 500.5).put("ambulance", 1000)), "2026-10")
        assertEquals(1500.5, e.total, 0.0)
        assertEquals(0.0, e.byKind["lab"]!!, 0.0)
        assertEquals(1000.0, e.byKind["ambulance"]!!, 0.0)
        assertEquals(3, e.count)
    }

    @Test
    fun requestBodies() {
        val l = InvoiceApi.linesJson(listOf(InvLine("CBC", 1.0, "", 400.0, 0.0, 400.0, "cbc")))
        assertEquals("cbc", l.getJSONObject(0).getString("code"))
        assertFalse(l.getJSONObject(0).has("unit"))
        val full = InvoiceApi.paymentsJson(null, "cash")
        assertFalse(full.getJSONObject(0).has("amount"))
        assertEquals(200.0, InvoiceApi.paymentsJson(200.0, "nagad", "X1").getJSONObject(0).getDouble("amount"), 0.0)
        assertEquals(0, InvoiceApi.customerJson(" ", "").length())
    }

    @Test
    fun notificationsAndLinks() {
        assertEquals(NotifyRoute.Invoice("inv1"), notifyRoute(mapOf("type" to "invoice_new", "invoiceId" to "inv1", "cat" to "my_request")))
        assertEquals(NotifyRoute.Invoice("inv1", "p1"), notifyRoute(mapOf("type" to "invoice_new", "invoiceId" to "inv1", "asUid" to "p1")))
        assertEquals(NotifyRoute.Open(Screen.MyReceipts()), notifyRoute(mapOf("type" to "invoice_new")))
        assertEquals(NotifyRoute.VerifyInvoice("Ab12Cd34Ef"), notifyRoute(mapOf("type" to "invoice_verify", "code" to "Ab12Cd34Ef")))
        assertEquals(NotifyCats.MY_REQUEST, NotifyCats.fromType("invoice_new"))
        // লক স্ক্রিনে টাকা, রসিদ নম্বর বা দোকানের নাম নয় — খোলার পরে পুরো লেখা
        val lock = Notifier.lockScreenText("invoice_new")!!
        assertTrue(lock.first.isNotBlank() && lock.second.isNotBlank())
        assertFalse(Regex("[0-9০-৯৳]").containsMatchIn(lock.first + lock.second))
        assertNull(Notifier.lockScreenText("rx"))
        assertEquals(NotifyRoute.Invoice("inv2"), notifyRoute(Notifier.extrasOf(mapOf("type" to "invoice_new", "invoiceId" to "inv2", "cat" to "my_request"))))
        assertTrue("invoiceId" in Notifier.DATA_KEYS)
        assertTrue("invoice_verify" in GuestApi.PUBLIC_ACTIONS)
    }

    @Test
    fun reviewRules() {
        val now = 1_800_000_000_000L
        val base = InvoiceApi.parse(sample()).copy(kind = "chamber", bizId = "doc1", issuedBy = "asst1", issuedAt = now - 60_000)
        // ডাক্তার সব পারেন; সহকারী শুধু নিজের, ২ ঘণ্টার মধ্যে; তালিকা ছাড়া (রোগী) কেউ নয়
        assertTrue(InvoiceRules.canVoid(base, "doc1", true, now))
        assertTrue(InvoiceRules.canVoid(base, "asst1", true, now))
        assertFalse(InvoiceRules.canVoid(base.copy(issuedAt = now - 3 * 3600_000L), "asst1", true, now))
        assertFalse(InvoiceRules.canVoid(base, "asst2", true, now))
        assertFalse(InvoiceRules.canVoid(base, "doc1", false, now))
        assertFalse(InvoiceRules.canVoid(base.copy(status = "void"), "doc1", true, now))
        assertTrue(InvoiceRules.canVoid(base.copy(kind = "pharmacy"), "owner1", true, now))
        assertFalse(InvoiceRules.canVoid(base.copy(canVoid = false), "doc1", true, now))
        assertTrue(InvoiceRules.canReport(base.copy(kind = "ambulance"), false))
        assertFalse(InvoiceRules.canReport(base.copy(kind = "ambulance"), true))
        assertFalse(InvoiceRules.canReport(base, false))
        // বাতিল: কোড + নোট আলাদা যায়; দেখানোর সময় "ভুল দাম — নোট"
        val vb = InvoiceApi.voidBody("inv1", "wrong_price", " ৫০০ লিখতে ৫০০০ ")
        assertEquals("wrong_price", vb.getString("reason"))
        assertEquals("৫০০ লিখতে ৫০০০", vb.getString("note"))
        assertFalse(InvoiceApi.voidBody("inv1", "duplicate", " ").has("note"))
        assertEquals("ভুল দাম — ৫০০ লিখতে ৫০০০", InvoiceApi.parse(sample().put("status", "void").put("voidReason", "wrong_price").put("voidNote", "৫০০ লিখতে ৫০০০")).voidText)
        assertEquals("আগের লেখা কারণ", InvoiceApi.parse(sample().put("voidReason", "আগের লেখা কারণ")).voidText)
        assertEquals("ভুল পণ্য", InvoiceKinds.voidReason("wrong_item", "pharmacy"))
        assertEquals("টাকা প্রাপ্তির রসিদ — মূসক চালানপত্র নয়", InvoiceText.footerNote())
        assertTrue(InvoiceApi.parse(sample().put("isDemo", true)).demo)
        assertEquals(1500, InvoiceApi.parse(sample().put("kind", "ambulance").put("chartFare", 1500)).chartFare)
        // যাচাই: সার্ভারের {invoice:{… status:"valid", date}} আর পরে আসা lineCount/demo
        val v = InvoiceApi.check(JSONObject().put("invoice", JSONObject().put("bizName", "X").put("no", "W-000001").put("date", 7L).put("total", 10).put("status", "valid").put("lines", 3).put("demo", true).put("sanctioned", true)))
        assertFalse(v.isVoid)
        assertEquals(7L, v.issuedAt)
        assertEquals(3, v.lineCount)
        assertTrue(v.demo)
        assertTrue(v.sanctioned)
        val p = InvoiceApi.page(JSONObject().put("items", JSONArray()).put("sum", JSONObject().put("count", 4).put("total", 900).put("due", 50)).put("dayEnd", JSONObject().put("voids", 2).put("writeOffs", 1)))
        assertEquals(BizSum(4, 900.0, 0.0, 50.0), p.sum)
        assertEquals(2, p.dayEnd?.voids)
        assertEquals(NotifyRoute.Open(Screen.MyBusiness("pharmacy"), "receipts"), notifyRoute(mapOf("type" to "invoice_summary", "kind" to "pharmacy")))
        assertEquals(NotifyRoute.Open(Screen.ChamberEdit, "fees"), notifyRoute(mapOf("type" to "invoice_day_end", "kind" to "chamber")))
        assertEquals(NotifyRoute.Tab(Tabs.WORK), notifyRoute(mapOf("type" to "invoice_summary", "day" to "2026-10-23"), null, true))
        assertEquals(NotifyRoute.Invoice("inv9", manage = true), notifyRoute(mapOf("type" to "invoice_void", "invoiceId" to "inv9")))
        assertEquals(NotifyCats.BUSINESS, NotifyCats.fromType("invoice_summary"))
        assertEquals(NotifyCats.BUSINESS, NotifyCats.fromType("invoice_void"))
    }

    @Test
    fun featuresAndApiUpdates() {
        // মালিকের নিয়ম: সব নতুন সুবিধা শুরুতে বন্ধ (স্টাবে সব বন্ধ)
        assertEquals("inv_pharmacy", InvoiceFeatures.key("pharmacy"))
        assertFalse(InvoiceFeatures.biz("lab"))
        assertFalse(InvoiceFeatures.patient())
        assertEquals("এই সুবিধা শিগগির আসছে", InvoiceFeatures.soon())
        assertEquals("অক্সিজেন", InvoiceKinds.feeLabel("oxygen"))
        assertEquals("টোল", InvoiceKinds.feeLabel("টোল"))
        assertEquals("একই রসিদ দুবার", InvoiceKinds.voidReason("duplicate"))
        assertTrue("duplicate" in InvoiceKinds.VOID_REASONS)
        val d = InvoiceApi.dayEnd(JSONObject().put("voids", JSONArray().put(JSONObject()).put(JSONObject())).put("bigDiscounts", JSONArray().put(JSONObject()))
            .put("writeOffs", JSONObject().put("count", 3)).put("belowPrice", JSONArray()))
        assertEquals(DayEnd(2, 1, 3, 0, ""), d)
        assertEquals(0, InvoiceApi.dayEnd(JSONObject().put("writeOffs", JSONObject.NULL)).writeOffs)
        assertEquals(NotifyRoute.Open(Screen.MyBusiness("lab"), "receipts"), notifyRoute(mapOf("type" to "invoice_summary", "day" to "2026-10-23", "kind" to "lab", "bizId" to "l1")))
        assertEquals(NotifyRoute.Invoice("i1", manage = true), notifyRoute(mapOf("type" to "invoice_void", "invoiceId" to "i1", "kind" to "chamber", "bizId" to "d1")))
    }

    @Test
    fun labels() {
        assertEquals("ক্যাশ", InvoiceKinds.method("cash"))
        assertEquals("নগদ", InvoiceKinds.method("nagad"))
        assertEquals("ফার্মেসি", InvoiceKinds.label("pharmacy"))
        assertEquals("আবার দেখানো", InvoiceKinds.visit("followup"))
        AppPrefs.lang.value = Lang.EN
        assertEquals("Cash", InvoiceKinds.method("cash"))
        AppPrefs.lang.value = Lang.BN
    }
}
