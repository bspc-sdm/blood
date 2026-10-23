package com.bspc.blood

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/*
 * রসিদ (invoices) — মালিক অনুমোদন দিয়েছেন ২৩ অক্টোবর ২০২৬। চুক্তি: backend/docs/INVOICES.md (spring-p16)।
 * কে দেয়: ফার্মেসি (বিক্রি, অনলাইন অর্ডার পৌঁছানো), প্যাথলজি (বিল), ডাক্তার/সহকারী (ফি), অ্যাম্বুলেন্স (যাত্রা শেষ)।
 * রোগী দেখেন "আমার রসিদ" আর "আমার খরচ" (কেয়ারগিভার রোগীর হয়ে asUid দিয়ে)। রক্তদাতার কোনো রসিদ নেই।
 * রসিদ দেওয়ার পর বদলায় না — শুধু বাকি টাকা নেওয়া আর কারণসহ বাতিল। সব টাকার হিসাব সার্ভার নিজে করে;
 * এখানের হিসাব (InvoiceMath) শুধু ফর্মে আগে দেখানোর জন্য।
 * ফোনে অফলাইন নেই (অফলাইন শুধু ডেস্কটপে) — ডেস্কটপের রসিদ (series D1, D2…) এখানে শুধু দেখা যায়।
 * পাতা: Receipts.kt (দেখা, তালিকা, খরচ, যাচাই), ReceiptPdf.kt (PDF/প্রিন্ট), InvoiceForms.kt (দেওয়ার ফর্ম)।
 */

data class InvLine(val name: String, val qty: Double, val unit: String, val price: Double, val disc: Double, val amount: Double, val code: String = "")
data class InvFee(val label: String, val amount: Double)
data class InvPayment(val at: Long, val amount: Double, val method: String, val ref: String = "")

data class Invoice(
    val id: String,
    val no: String,
    /** pharmacy | lab | chamber | ambulance */
    val kind: String,
    val bizId: String,
    val bizName: String,
    val bizPhone: String = "",
    val bizAddress: String = "",
    val patientUid: String = "",
    val customerName: String = "",
    val customerPhone: String = "",
    /** book_sale | rx_order | lab_order | appointment | ambreq */
    val refType: String = "",
    val refId: String = "",
    val lines: List<InvLine> = emptyList(),
    val subtotal: Double = 0.0,
    val disc: Double = 0.0,
    val fees: List<InvFee> = emptyList(),
    val total: Double = 0.0,
    val paid: Double = 0.0,
    val due: Double = 0.0,
    val payments: List<InvPayment> = emptyList(),
    /** issued | void */
    val status: String = "issued",
    val voidReason: String = "",
    val issuedAt: Long = 0L,
    val issuedBy: String = "",
    val deviceId: String = "",
    val offline: Boolean = false,
    val verifyCode: String = "",
    val verifyUrl: String = "",
    /** অ্যাম্বুলেন্স: ভাড়া চার্টের বেশি (সার্ভারের overFare) */
    val overFare: Boolean = false,
    /** সার্ভার বললে: এই মানুষ টাকা নিতে/বাতিল করতে পারেন */
    val canManage: Boolean = false,
    /** ডেমো দোকানের রসিদ — "ডেমো" জলছাপ (নিরাপত্তা পর্যালোচনা I9) */
    val demo: Boolean = false,
    /** অ্যাম্বুলেন্স: সেই যাত্রার চার্টের ভাড়া */
    val chartFare: Int? = null,
    /** সার্ভার বললে বাতিল করা যায় কি না (না বললে InvoiceRules.canVoid আন্দাজ করে) */
    val canVoid: Boolean? = null,
    /** বাতিলের সাথে লেখা (voidReason = তালিকার কোড) */
    val voidNote: String = "",
    val voidedAt: Long = 0L,
    /** সার্ভারের নিচের লাইন ("টাকা প্রাপ্তির রসিদ — মূসক চালানপত্র নয়") — না এলে অ্যাপেরটা */
    val footer: String = "",
) {
    /** "ভুল দাম — নোট" */
    val voidText: String get() = listOf(if (voidReason.isNotBlank()) InvoiceKinds.voidReason(voidReason, kind) else "", voidNote).filter { it.isNotBlank() }.joinToString(" — ")
    val isVoid: Boolean get() = status == "void"
    val hasDue: Boolean get() = !isVoid && due > 0.004
    /** লিংক না এলে কোড থেকে বানানো */
    val link: String get() = verifyUrl.ifBlank { if (verifyCode.isNotBlank()) InvoiceText.VERIFY_BASE + verifyCode else "" }
}

/** যাচাইয়ের পাতা — রোগী/ক্রেতার কিছু নেই */
data class InvoiceCheck(
    val bizName: String, val kind: String, val no: String, val issuedAt: Long, val total: Double, val paid: Double, val due: Double, val status: String,
    /** কয়টা লাইন (নাম নয়) — কাগজে লাইন বাড়ানো ধরা যায় */
    val lineCount: Int? = null,
    val demo: Boolean = false,
    val voidedAt: Long = 0L,
    /** প্রতিষ্ঠান এখন Lifolo-তে স্থগিত */
    val sanctioned: Boolean = false,
) {
    val isVoid: Boolean get() = status == "void"
}

/** পুরো সময়ের যোগফল (বাতিল বাদ) — invoice_list_biz-এর sum */
data class BizSum(val count: Int, val total: Double, val paid: Double, val due: Double)

/** "দিনের শেষ" — সার্ভার দিলে (বাতিল, বড় ছাড়, নষ্ট দেখানো স্টক, স্টক কম) */
data class DayEnd(val voids: Int, val bigDiscounts: Int, val writeOffs: Int, val stockShort: Int, val note: String)

data class InvoicePage(val items: List<Invoice>, val cursor: String?, val sum: BizSum? = null, val dayEnd: DayEnd? = null)

/** একটা মাসের খরচ (my_expenses) — byKind: pharmacy, lab, chamber, ambulance */
data class Expenses(val month: String, val total: Double, val byKind: Map<String, Double>, val count: Int)

object InvoiceKinds {
    val ALL = listOf("pharmacy", "lab", "chamber", "ambulance")
    val METHODS = listOf("cash", "bkash", "nagad", "card", "other")
    val VISITS = listOf("new", "followup", "report")
    val PERIODS = listOf("today", "7d", "30d", "month")
    /** বাতিলের কারণ — নির্দিষ্ট তালিকা থেকে, সাথে লেখা (নিরাপত্তা পর্যালোচনা I2) */
    val VOID_REASONS = listOf("wrong_item", "returned", "wrong_price", "duplicate", "other")

    fun voidReason(code: String, kind: String = ""): String = when (code) {
        "wrong_item" -> if (kind == "pharmacy") tr("Wrong item", "ভুল পণ্য") else tr("Wrong item / service", "ভুল পণ্য/সেবা")
        "returned" -> tr("Customer returned it", "ক্রেতা ফেরত দিয়েছেন")
        "wrong_price" -> tr("Wrong price", "ভুল দাম")
        "duplicate" -> tr("Same receipt twice", "একই রসিদ দুবার")
        "other" -> tr("Other", "অন্যান্য")
        // পুরোনো রসিদে লেখা কারণ (কোড নয়) — যেমন আছে
        else -> code
    }

    fun label(kind: String): String = when (kind) {
        "pharmacy" -> tr("Pharmacy", "ফার্মেসি")
        "lab" -> tr("Lab", "প্যাথলজি")
        "chamber" -> tr("Doctor's fee", "ডাক্তারের ফি")
        "ambulance" -> tr("Ambulance", "অ্যাম্বুলেন্স")
        else -> kind
    }

    fun icon(kind: String): Int = when (kind) {
        "pharmacy" -> R.drawable.ic_medication
        "lab" -> R.drawable.ic_local_hospital
        "chamber" -> R.drawable.ic_stethoscope
        "ambulance" -> R.drawable.ic_ambulance
        else -> R.drawable.ic_payments
    }

    // "নগদ" এখানে মোবাইল ব্যাংকিংয়ের নাম — হাতে দেওয়া টাকা "ক্যাশ", যাতে গুলিয়ে না যায়
    fun method(m: String): String = when (m) {
        "cash" -> tr("Cash", "ক্যাশ")
        "bkash" -> tr("bKash", "বিকাশ")
        "nagad" -> tr("Nagad", "নগদ")
        "card" -> tr("Card", "কার্ড")
        else -> tr("Other", "অন্যান্য")
    }

    fun visit(v: String): String = when (v) {
        "new" -> tr("New patient", "নতুন রোগী")
        "followup" -> tr("Follow-up", "আবার দেখানো")
        "report" -> tr("Showing reports", "রিপোর্ট দেখানো")
        else -> v
    }

    fun period(p: String): String = when (p) {
        "today" -> tr("Today", "আজ")
        "7d" -> tr("7 days", "৭ দিন")
        "30d" -> tr("30 days", "৩০ দিন")
        else -> tr("This month", "এই মাস")
    }
}

/** লেখা: অঙ্ক (বাংলা বা ইংরেজি — রসিদে আলাদা বাছাই), টাকা, তারিখ, যাচাইয়ের লিংক */
object InvoiceText {
    const val VERIFY_BASE = "https://lifolo.com/v/"
    private val DHAKA: TimeZone = TimeZone.getTimeZone("Asia/Dhaka")

    /** bn = বাংলা অঙ্ক (০-৯); না হলে বাংলা অঙ্কও ইংরেজিতে */
    fun digits(s: String, bn: Boolean): String =
        if (bn) s.map { if (it in '0'..'9') '০' + (it - '0') else it }.joinToString("")
        else s.map { if (it in '০'..'৯') '0' + (it - '০') else it }.joinToString("")

    /** ২ ঘর দশমিকে গোল (চুক্তি: টাকা ২ ঘর পর্যন্ত) */
    fun r2(v: Double): Double = Math.round(v * 100) / 100.0

    /** ৳১,২৩৪ / ৳১,২৩৪.৫০ (দশমিক থাকলে সবসময় ২ ঘর) */
    fun money(v: Double, bn: Boolean): String {
        val a = Math.abs(r2(v))
        val s = if (a == Math.floor(a)) String.format(Locale.US, "%,d", a.toLong()) else String.format(Locale.US, "%,.2f", a)
        return (if (v < -0.004) "−" else "") + "৳" + digits(s, bn)
    }

    /** ১০ / ২.৫ */
    fun qty(v: Double, bn: Boolean): String =
        digits(if (v == Math.floor(v)) v.toLong().toString() else String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.'), bn)

    /** "২৩ অক্টোবর ২০২৬, বিকাল ৪:০৫" ধরনের — ঢাকার সময়; মাসের নাম অ্যাপের ভাষায়, অঙ্ক bn অনুযায়ী */
    fun dateTime(millis: Long, bn: Boolean, withTime: Boolean = true): String {
        if (millis <= 0L) return ""
        val f = if (isBangla) SimpleDateFormat(if (withTime) "d MMMM yyyy, h:mm a" else "d MMMM yyyy", Locale("bn", "BD"))
        else SimpleDateFormat(if (withTime) "d MMM yyyy, h:mm a" else "d MMM yyyy", Locale.ENGLISH)
        f.timeZone = DHAKA
        return digits(f.format(Date(millis)), bn)
    }

    /** "অক্টোবর ২০২৬" — month "2026-10" */
    fun monthLabel(month: String, bn: Boolean = isBangla): String {
        val (y, m) = InvoiceMonths.parts(month) ?: return month
        val c = Calendar.getInstance(DHAKA).apply { clear(); set(y, m - 1, 1) }
        val f = if (isBangla) SimpleDateFormat("MMMM yyyy", Locale("bn", "BD")) else SimpleDateFormat("MMMM yyyy", Locale.ENGLISH)
        f.timeZone = DHAKA
        return digits(f.format(c.time), bn)
    }

    /** রসিদের QR বা লিংক থেকে যাচাইয়ের কোড: https://lifolo.com/v/{code} (অন্য host-ও), বা শুধু কোড */
    fun codeOf(raw: String): String? {
        val t = raw.trim()
        Regex("/v/([A-Za-z0-9]{6,16})(?:[/?#].*)?$").find(t)?.let { return it.groupValues[1] }
        return t.takeIf { Regex("^[A-Za-z0-9]{10}$").matches(it) }
    }

    /** লিংক থেকে কোড, শুধু আমাদের host-এ (MainActivity-র ইনটেন্ট) */
    fun codeFromLink(scheme: String?, host: String?, path: String?, ourHost: String): String? {
        if (scheme != "https" || path == null) return null
        if (host != ourHost && host != "lifolo.com" && host != "www.lifolo.com") return null
        return Regex("^/v/([A-Za-z0-9]{6,16})/?$").find(path)?.groupValues?.get(1)
    }

    /** রসিদের নিচে (নিরাপত্তা পর্যালোচনা I15): এটা মূসক চালানপত্র নয় */
    fun footerNote(): String = tr("Money receipt — not a VAT (Mushak) invoice", "টাকা প্রাপ্তির রসিদ — মূসক চালানপত্র নয়")

    /** ফোন নম্বরের মাঝখান ঢাকা: 017•••••234 */
    fun maskPhone(p: String): String = if (p.length < 7) p else p.take(3) + "•".repeat(p.length - 6) + p.takeLast(3)
}

/** "আমার খরচ"-এর মাস: "YYYY-MM" (ঢাকার সময়) */
object InvoiceMonths {
    fun parts(month: String): Pair<Int, Int>? {
        val m = Regex("^(\\d{4})-(\\d{2})$").find(month) ?: return null
        val y = m.groupValues[1].toInt()
        val mo = m.groupValues[2].toInt()
        return if (mo in 1..12) y to mo else null
    }

    fun of(y: Int, m: Int): String = String.format(Locale.US, "%04d-%02d", y, m)

    fun current(now: Long = System.currentTimeMillis()): String {
        val c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Dhaka")).apply { timeInMillis = now }
        return of(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
    }

    fun shift(month: String, by: Int): String {
        val (y, m) = parts(month) ?: return month
        val idx = y * 12 + (m - 1) + by
        return of(Math.floorDiv(idx, 12), Math.floorMod(idx, 12) + 1)
    }
}

/** ফর্মে আগে দেখানোর হিসাব (সার্ভার নিজে আবার হিসাব করে — চুক্তি) */
object InvoiceMath {
    fun lineAmount(qty: Double, price: Double, disc: Double): Double = InvoiceText.r2((qty * price - disc).coerceAtLeast(0.0))
    fun subtotal(lines: List<InvLine>): Double = InvoiceText.r2(lines.sumOf { lineAmount(it.qty, it.price, it.disc) })
    fun total(lines: List<InvLine>, billDisc: Double, fees: List<InvFee>): Double =
        InvoiceText.r2((subtotal(lines) - billDisc + fees.sumOf { it.amount }).coerceAtLeast(0.0))

    /** বাংলা অঙ্ক/কমা/৳ সহ লেখা → টাকা (খালি = null, ভুল = -1.0) */
    fun parse(s: String): Double? {
        val t = s.map { c -> if (c in '০'..'৯') ('0' + (c - '০')) else c }.joinToString("").replace(Regex("[,\\s৳]"), "")
        if (t.isEmpty()) return null
        if (!Regex("^\\d{1,7}(\\.\\d{1,2})?$").matches(t)) return -1.0
        return t.toDouble()
    }

    /** অ্যাম্বুলেন্স: ভাড়া চার্টের চেয়ে বেশি কি না (চার্ট না থাকলে কখনো নয়) */
    fun overChart(fare: Double?, chart: Int?): Boolean = fare != null && chart != null && chart > 0 && fare > chart + 0.004
}

/** কে কী করতে পারেন (নিরাপত্তা পর্যালোচনা I7) — সার্ভারও একই নিয়মে আটকায়; এখানে শুধু বাটন দেখানো/লুকানো */
object InvoiceRules {
    /** সহকারী নিজের দেওয়া রসিদ বাতিল করতে পারেন শুধু ২ ঘণ্টার মধ্যে */
    const val ASSISTANT_VOID_MS = 2L * 60 * 60 * 1000

    /**
     * manage: ব্যবসার তালিকা থেকে খোলা (মালিক/ডাক্তার/সহকারী)। চেম্বারে bizId = ডাক্তারের uid — ডাক্তার সব পারেন,
     * সহকারী শুধু নিজের, ২ ঘণ্টার মধ্যে। অন্য ব্যবসায় শুধু মালিক (manage)। সার্ভার canVoid বললে সেটাই।
     */
    fun canVoid(inv: Invoice, myUid: String?, manage: Boolean, now: Long = System.currentTimeMillis()): Boolean {
        if (inv.isVoid) return false
        inv.canVoid?.let { return it }
        if (!manage && !inv.canManage) return false
        if (inv.kind != "chamber") return true
        if (myUid != null && myUid == inv.bizId) return true
        return myUid != null && inv.issuedBy == myUid && now - inv.issuedAt in 0 until ASSISTANT_VOID_MS
    }

    /** অ্যাম্বুলেন্সের রসিদে রোগী "রসিদের চেয়ে বেশি নিয়েছে" জানাতে পারেন */
    fun canReport(inv: Invoice, manage: Boolean): Boolean = inv.kind == "ambulance" && !manage && !inv.isVoid && !inv.canManage
}

/** রসিদের অবস্থা আর কোথায় খুলবে (MainActivity আর Receipts.kt-এর ReceiptOverlays) */
object InvoiceNav {
    /** id: খোলার রসিদ; invoice: হাতে থাকলে (এইমাত্র দেওয়া) আর আনতে হয় না; asUid: কেয়ারগিভার; manage: টাকা নেওয়া/বাতিলের বাটন */
    data class Show(val id: String, val invoice: Invoice? = null, val asUid: String? = null, val manage: Boolean = false)

    val show = mutableStateOf<Show?>(null)
    /** যাচাইয়ের কোড (QR / lifolo.com/v/… লিংক) */
    val verify = mutableStateOf<String?>(null)
    /** "আমার রসিদ" পাতা খোলা: tab ০ রসিদ, ১ খরচ */
    val list = mutableStateOf<Int?>(null)
    /** টাকা নেওয়া/বাতিল/নতুন রসিদ হলে বাড়ে — তালিকাগুলো নতুন করে আনে */
    val changed = mutableIntStateOf(0)
    /** "দিনের শেষ" নোটিফিকেশন থেকে ডাক্তারের "ফি ও রসিদ" খোলা */
    val openFees = mutableStateOf(false)

    fun open(inv: Invoice, manage: Boolean = false, asUid: String? = null) {
        show.value = Show(inv.id, inv, asUid, manage)
    }

    fun openId(id: String, asUid: String? = null, manage: Boolean = false) {
        show.value = Show(id, null, asUid, manage)
    }

    /** নতুন রসিদ দেওয়া হলো: তালিকা নতুন করে আর রসিদ খোলা */
    fun issued(inv: Invoice?) {
        changed.intValue++
        if (inv != null) open(inv, manage = true)
    }
}

object InvoiceApi {
    private suspend fun call(action: String, body: JSONObject = JSONObject()): JSONObject = ServerApi.post("/api/community", body.put("action", action))

    private fun JSONArray?.objs(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    private fun JSONObject.dbl(k: String): Double = if (isNull(k) || !has(k)) 0.0 else optDouble(k, 0.0).takeIf { !it.isNaN() } ?: 0.0

    /** সময়: মিলিসেকেন্ড (সংখ্যা) বা ISO লেখা — দুটোই চলে */
    internal fun time(o: JSONObject, k: String): Long {
        if (!o.has(k) || o.isNull(k)) return 0L
        val v = o.opt(k)
        if (v is Number) return v.toLong()
        val s = v?.toString()?.trim().orEmpty()
        s.toLongOrNull()?.let { return it }
        for (p in listOf("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSS", "yyyy-MM-dd'T'HH:mm:ss")) {
            val f = SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val d = runCatching { f.parse(s.replace(Regex("(\\.\\d{3})\\d+"), "$1")) }.getOrNull()
            if (d != null) return d.time
        }
        return 0L
    }

    /** উত্তরে রসিদ যেভাবেই আসুক: {invoice:{…}}, {item:{…}}, অথবা সরাসরি */
    internal fun pick(o: JSONObject): JSONObject = o.optJSONObject("invoice") ?: o.optJSONObject("item") ?: o

    fun parse(o: JSONObject): Invoice {
        val c = o.optJSONObject("customer") ?: JSONObject()
        return Invoice(
            id = o.str("id"), no = o.str("no"), kind = o.str("kind"), bizId = o.str("bizId"), bizName = o.str("bizName"),
            bizPhone = o.str("bizPhone"), bizAddress = o.str("bizAddress"), patientUid = o.str("patientUid"),
            customerName = c.str("name"), customerPhone = c.str("phone"), refType = o.str("refType"), refId = o.str("refId"),
            lines = o.optJSONArray("lines").objs().map {
                InvLine(it.str("name"), it.optDouble("qty", 1.0).takeIf { q -> !q.isNaN() } ?: 1.0, it.str("unit"), it.dbl("price"), it.dbl("disc"), it.dbl("amount"), it.str("code"))
            },
            subtotal = o.dbl("subtotal"), disc = o.dbl("disc"),
            fees = o.optJSONArray("fees").objs().map { InvFee(it.str("label"), it.dbl("amount")) },
            total = o.dbl("total"), paid = o.dbl("paid"), due = o.dbl("due"),
            payments = o.optJSONArray("payments").objs().map { InvPayment(time(it, "at"), it.dbl("amount"), it.str("method").ifBlank { "cash" }, it.str("ref")) },
            status = o.str("status").ifBlank { "issued" }, voidReason = o.str("voidReason"), issuedAt = time(o, "issuedAt"),
            issuedBy = o.str("issuedBy"), deviceId = o.str("deviceId"), offline = o.optBoolean("offline"),
            verifyCode = o.str("verifyCode"), verifyUrl = o.str("verifyUrl"), overFare = o.optBoolean("overFare"),
            canManage = o.optBoolean("canManage"), demo = o.optBoolean("demo") || o.optBoolean("isDemo"),
            chartFare = if (o.has("chartFare") && !o.isNull("chartFare")) o.optInt("chartFare") else null,
            canVoid = if (o.has("canVoid") && !o.isNull("canVoid")) o.optBoolean("canVoid") else null,
            voidNote = o.str("voidNote"), voidedAt = time(o, "voidedAt"), footer = o.str("footer"),
        )
    }

    internal fun page(o: JSONObject): InvoicePage {
        val arr = o.optJSONArray("items") ?: o.optJSONArray("invoices")
        val cur = listOf("cursor", "next", "nextCursor").firstNotNullOfOrNull { k -> o.str(k).takeIf { it.isNotBlank() && it != "null" } }
        val s = o.optJSONObject("sum")
        val d = o.optJSONObject("dayEnd") ?: o.optJSONObject("summary")
        return InvoicePage(
            arr.objs().map(::parse), cur,
            s?.let { BizSum(it.optInt("count"), it.dbl("total"), it.dbl("paid"), it.dbl("due")) },
            d?.let { DayEnd(it.optInt("voids"), it.optInt("bigDiscounts"), it.optInt("writeOffs"), it.optInt("stockShort"), it.str("note")) },
        )
    }

    internal fun check(o: JSONObject): InvoiceCheck {
        val i = pick(o)
        return InvoiceCheck(
            i.str("bizName"), i.str("kind"), i.str("no"), time(i, "issuedAt").takeIf { it > 0 } ?: time(i, "date"),
            i.dbl("total"), i.dbl("paid"), i.dbl("due"), i.str("status").ifBlank { "issued" },
            // সার্ভার "lines" দেয় সংখ্যা হিসেবে (লাইনের নাম নয়)
            lineCount = listOf("lineCount", "lines").firstNotNullOfOrNull { k -> i.opt(k).let { v -> (v as? Number)?.toInt() ?: (v as? JSONArray)?.length() } },
            demo = i.optBoolean("demo") || i.optBoolean("isDemo"), voidedAt = time(i, "voidedAt"), sanctioned = i.optBoolean("sanctioned"),
        )
    }

    internal fun expenses(o: JSONObject, month: String): Expenses {
        val b = o.optJSONObject("byKind") ?: JSONObject()
        return Expenses(month, o.dbl("total"), InvoiceKinds.ALL.associateWith { b.dbl(it) }, o.optInt("count"))
    }

    private fun JSONObject.asUid(asUid: String?): JSONObject = apply { if (!asUid.isNullOrBlank()) put("asUid", asUid) }

    internal fun linesJson(lines: List<InvLine>): JSONArray = JSONArray(lines.map {
        JSONObject().put("name", it.name).put("qty", it.qty).put("price", it.price).put("disc", it.disc)
            .apply { if (it.unit.isNotBlank()) put("unit", it.unit); if (it.code.isNotBlank()) put("code", it.code) }
    })

    internal fun feesJson(fees: List<InvFee>): JSONArray = JSONArray(fees.map { JSONObject().put("label", it.label).put("amount", it.amount) })

    /** একটা টাকা নেওয়া (দেওয়ার সময়) — amount null হলে পুরোটা (সার্ভার মোট ধরে) */
    internal fun paymentsJson(amount: Double?, method: String, ref: String = ""): JSONArray =
        JSONArray(listOf(JSONObject().put("method", method).apply { if (amount != null) put("amount", amount); if (ref.isNotBlank()) put("ref", ref) }))

    internal fun customerJson(name: String, phone: String): JSONObject =
        JSONObject().apply { if (name.isNotBlank()) put("name", name.trim()); if (phone.isNotBlank()) put("phone", phone.trim()) }

    suspend fun get(id: String, asUid: String? = null): Invoice = parse(pick(call("invoice_get", JSONObject().put("id", id).asUid(asUid))))

    /** লগইন ছাড়াও (অতিথি) */
    suspend fun verify(code: String): InvoiceCheck = check(call("invoice_verify", JSONObject().put("code", code)))

    suspend fun listBiz(kind: String, bizId: String, period: String, q: String = "", cursor: String? = null): InvoicePage =
        page(call("invoice_list_biz", JSONObject().put("kind", kind).put("bizId", bizId).put("period", period)
            .apply { if (q.isNotBlank()) put("q", q.trim()); if (cursor != null) put("cursor", cursor) }))

    suspend fun pay(id: String, amount: Double, method: String, ref: String = ""): Invoice =
        parse(pick(call("invoice_pay", JSONObject().put("id", id).put("amount", amount).put("method", method).apply { if (ref.isNotBlank()) put("ref", ref.trim()) })))

    /** বাতিল: reason = তালিকার কোড (wrong_item…), note = নিজের লেখা ("অন্যান্য" হলে লাগবেই) */
    internal fun voidBody(id: String, reasonCode: String, note: String): JSONObject =
        JSONObject().put("id", id).put("reason", reasonCode).apply { if (note.isNotBlank()) put("note", note.trim()) }

    suspend fun void(id: String, reasonCode: String, note: String): Invoice = parse(pick(call("invoice_void", voidBody(id, reasonCode, note))))

    /**
     * রোগী: অ্যাম্বুলেন্স রসিদের চেয়ে বেশি নিয়েছে (নিরাপত্তা পর্যালোচনা I8)। `invoice_report` সার্ভারে না থাকলে
     * সাহায্যের টিকিট (অ্যাম্বুলেন্স) — রসিদের নম্বর আর টাকাসহ, যাতে অ্যাডমিন দেখেন।
     */
    suspend fun reportOvercharge(inv: Invoice, paid: Double?, note: String) {
        val r = runCatching {
            call("invoice_report", JSONObject().put("id", inv.id).apply { if (note.isNotBlank()) put("note", note.trim()); if (paid != null) put("paid", paid) })
        }
        if (r.isSuccess) return
        val e = r.exceptionOrNull()
        if (e is ServerError && e.code !in setOf(null, "unknown_action", "not_found")) throw e
        Tickets.create(
            "ambulance",
            "রসিদের চেয়ে বেশি নিয়েছে — রসিদ ${inv.no} (${inv.bizName}), রসিদে ${InvoiceText.money(inv.total, false)}" + // bn-ok
                (paid?.let { ", দিয়েছি ${InvoiceText.money(it, false)}" } ?: "") + note.trim().takeIf { it.isNotEmpty() }?.let { "। $it" }.orEmpty(), // bn-ok
        )
    }

    suspend fun mine(asUid: String? = null, cursor: String? = null): InvoicePage =
        page(call("my_invoices", JSONObject().asUid(asUid).apply { if (cursor != null) put("cursor", cursor) }))

    suspend fun expenses(month: String, asUid: String? = null): Expenses = expenses(call("my_expenses", JSONObject().put("month", month).asUid(asUid)), month)

    /** উত্তরে রসিদ থাকলে (books_sale_add, rx_order_update…) — না থাকলে null */
    fun invoiceIn(o: JSONObject): Invoice? = o.optJSONObject("invoice")?.let(::parse)?.takeIf { it.id.isNotBlank() }

    /** প্যাথলজির বিল: অর্ডার থেকে (লাইন সার্ভার দামের ছবি থেকে বসায়, দিলে সেটাই) অথবা সরাসরি আসা রোগী */
    suspend fun labBill(
        labOrderId: String?, lines: List<InvLine>, customerName: String, customerPhone: String, fees: List<InvFee>, disc: Double,
        payAmount: Double?, method: String, ref: String = "",
    ): Invoice {
        val b = JSONObject()
        if (labOrderId != null) b.put("labOrderId", labOrderId)
        if (lines.isNotEmpty()) b.put("lines", linesJson(lines))
        customerJson(customerName, customerPhone).takeIf { it.length() > 0 }?.let { b.put("customer", it) }
        if (fees.isNotEmpty()) b.put("fees", feesJson(fees))
        if (disc > 0) b.put("disc", disc)
        b.put("payments", if (payAmount != null && payAmount <= 0.0) JSONArray() else paymentsJson(payAmount, method, ref))
        return parse(pick(call("lab_bill_create", b)))
    }

    /** ডাক্তারের ফি: অ্যাপয়েন্টমেন্ট থেকে, অথবা সরাসরি আসা রোগী (walkIn) */
    suspend fun chamberFee(
        appointmentId: String?, walkInName: String, walkInPhone: String, chamberKey: String, date: Long,
        visitType: String, amount: Double, method: String, ref: String = "", doctorUid: String? = null,
    ): Invoice {
        val b = JSONObject().put("visitType", visitType).put("amount", amount).put("method", method)
        // সহকারী: কোন ডাক্তারের হয়ে
        if (!doctorUid.isNullOrBlank()) b.put("doctorUid", doctorUid)
        if (ref.isNotBlank()) b.put("ref", ref.trim())
        if (appointmentId != null) b.put("appointmentId", appointmentId)
        else b.put("walkIn", JSONObject().put("name", walkInName.trim()).put("chamberKey", chamberKey).put("date", date).apply { if (walkInPhone.isNotBlank()) put("phone", walkInPhone.trim()) })
        return parse(pick(call("chamber_fee_collect", b)))
    }

    /** "যাত্রা শেষ": ভাড়া + অতিরিক্ত (টোল, অপেক্ষা…) + কীভাবে পেলেন → রসিদ (রোগী জানেন) */
    suspend fun ambFinish(reqId: String, fare: Double, extras: List<InvFee>, method: String): Invoice? {
        val o = call("ambreq_finish", JSONObject().put("reqId", reqId).put("fare", fare).put("method", method).apply { if (extras.isNotEmpty()) put("extras", feesJson(extras)) })
        return invoiceIn(o) ?: o.takeIf { it.has("no") && it.has("id") }?.let(::parse)
    }

    /**
     * অনলাইন অর্ডার পৌঁছে দেওয়া (delivered) — লাইন দিলে সেটাই, না দিলে "অর্ডার নিলাম"-এ রাখা লাইন; ডেলিভারি চার্জ
     * অর্ডারেরটা। সার্ভার পৌঁছানোয় বাকিটা ক্যাশে পাওয়া ধরে। লাইন কোথাও না থাকলে রসিদ হয় না (null)।
     */
    suspend fun rxDelivered(orderId: String, lines: List<InvLine>): Invoice? {
        val b = JSONObject().put("id", orderId).put("status", "delivered")
        if (lines.isNotEmpty()) b.put("lines", linesJson(lines))
        return invoiceIn(call("rx_order_update", b))
    }

    /**
     * অর্ডার/অ্যাপয়েন্টমেন্ট/অ্যাম্বুলেন্সের রসিদ খোঁজা (রোগীর দিক) — নিজের রসিদের তালিকায় refType+refId মিলিয়ে,
     * সর্বোচ্চ কয়েক পাতা। না পেলে null।
     */
    suspend fun findByRef(refType: String, refId: String, asUid: String? = null, maxPages: Int = 4): Invoice? {
        var cursor: String? = null
        repeat(maxPages) {
            val p = mine(asUid, cursor)
            p.items.firstOrNull { it.refType == refType && it.refId == refId }?.let { return it }
            cursor = p.cursor ?: return null
        }
        return null
    }
}
