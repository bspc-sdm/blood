package com.lifolo.invoices

import com.fasterxml.jackson.databind.JsonNode
import com.lifolo.common.ApiException
import com.lifolo.common.Js
import com.lifolo.common.Lang
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.SecureRandom

/**
 * A receipt the desktop numbered itself: [no] = "<series>-<seq 6 digits>" in the device's own series (D1, D2 …).
 * [verifyCode]: the code the device printed in the QR (used as is; 400 `bad_verify` / 409 `verify_collision`).
 * [deviceNo]: the number the device printed when the sync renumbered it to [no] (kept as `device_no`).
 */
data class DeviceStamp(val deviceId: String, val no: String, val at: Long, val verifyCode: String? = null, val deviceNo: String? = null)

/** one line as given (money already parsed); the amount is computed */
data class LineIn(
    val name: String,
    val qty: BigDecimal,
    val price: BigDecimal,
    val disc: BigDecimal = BigDecimal.ZERO,
    val unit: String? = null,
    val code: String? = null,
)

data class FeeIn(val label: String, val amount: BigDecimal)

data class PaymentIn(val amount: BigDecimal, val method: String, val ref: String? = null)

data class Customer(val name: String? = null, val phone: String? = null)

/**
 * What to issue. [payments] null = fully paid in cash (the default of every creator); an empty list = nothing paid yet.
 * [patientUid] links the receipt to a Lifolo patient (my_invoices, push); [bizAddress] overrides the business address
 * (a doctor's chamber). [overFare]/[chartFare]: ambulance only.
 */
data class IssueCmd(
    val kind: String,
    val bizId: String,
    val lines: List<LineIn>,
    val disc: BigDecimal = BigDecimal.ZERO,
    val fees: List<FeeIn> = emptyList(),
    val payments: List<PaymentIn>? = null,
    val customer: Customer = Customer(),
    val patientUid: String? = null,
    val refType: String? = null,
    val refId: String? = null,
    val bizAddress: String? = null,
    val overFare: Boolean? = null,
    val chartFare: Int? = null,
)

/** a computed line */
data class Line(val name: String, val qty: BigDecimal, val unit: String?, val price: BigDecimal, val disc: BigDecimal, val amount: BigDecimal, val code: String?) {
    fun toMap(): Map<String, Any?> = linkedMapOf<String, Any?>("name" to name, "qty" to InvoiceMath.num(qty)).apply {
        if (unit != null) put("unit", unit)
        put("price", InvoiceMath.num(price))
        put("disc", InvoiceMath.num(disc))
        put("amount", InvoiceMath.num(amount))
        if (code != null) put("code", code)
    }
}

data class Fee(val label: String, val amount: BigDecimal) {
    fun toMap(): Map<String, Any?> = linkedMapOf("label" to label, "amount" to InvoiceMath.num(amount))
}

data class Payment(val at: Long, val amount: BigDecimal, val method: String, val ref: String?) {
    fun toMap(): Map<String, Any?> = linkedMapOf<String, Any?>("at" to at, "amount" to InvoiceMath.num(amount), "method" to method).apply {
        if (ref != null) put("ref", ref)
    }
}

/** the totals the server computes (never taken from the client) */
data class Totals(val lines: List<Line>, val subtotal: BigDecimal, val disc: BigDecimal, val fees: List<Fee>, val total: BigDecimal)

/** an invoice as stored; [toMap] is the contract's Invoice object */
data class Invoice(
    val id: String,
    val no: String,
    val kind: String,
    val bizId: String,
    val ownerUid: String?,
    val bizName: String,
    val bizPhone: String?,
    val bizAddress: String?,
    val patientUid: String?,
    val customer: Customer,
    val refType: String?,
    val refId: String?,
    val lines: List<Line>,
    val subtotal: BigDecimal,
    val disc: BigDecimal,
    val fees: List<Fee>,
    val total: BigDecimal,
    val paid: BigDecimal,
    val due: BigDecimal,
    val payments: List<Payment>,
    val status: String,
    val voidReason: String?,
    val voidNote: String?,
    val voidedAt: Long?,
    val issuedAt: Long,
    val issuedBy: String?,
    val deviceId: String?,
    val deviceNo: String?,
    val offline: Boolean,
    val verifyCode: String,
    val overFare: Boolean?,
    val chartFare: Int?,
    val isDemo: Boolean,
    /** Lifolo Pay refunds in part (a full refund voids the receipt — reason `refunded`) */
    val refunded: BigDecimal = BigDecimal.ZERO,
) {
    val verifyUrl: String get() = InvoiceMath.VERIFY_BASE + verifyCode

    /** [maskPhone]: business lists after 30 days */
    fun toMap(maskPhone: Boolean = false): LinkedHashMap<String, Any?> {
        val m = linkedMapOf<String, Any?>("id" to id, "no" to no, "kind" to kind, "bizId" to bizId, "bizName" to bizName)
        if (bizPhone != null) m["bizPhone"] = bizPhone
        if (bizAddress != null) m["bizAddress"] = bizAddress
        if (patientUid != null) m["patientUid"] = patientUid
        m["customer"] = linkedMapOf<String, Any?>().apply {
            if (customer.name != null) put("name", customer.name)
            if (customer.phone != null) put("phone", if (maskPhone) InvoiceMath.maskPhone(customer.phone) else customer.phone)
        }
        if (refType != null) m["refType"] = refType
        if (refId != null) m["refId"] = refId
        m["lines"] = lines.map { it.toMap() }
        m["subtotal"] = InvoiceMath.num(subtotal)
        m["disc"] = InvoiceMath.num(disc)
        m["fees"] = fees.map { it.toMap() }
        m["total"] = InvoiceMath.num(total)
        m["paid"] = InvoiceMath.num(paid)
        m["due"] = InvoiceMath.num(due)
        m["payments"] = payments.map { it.toMap() }
        if (refunded.signum() > 0) m["refunded"] = InvoiceMath.num(refunded)
        m["status"] = status
        if (voidReason != null) m["voidReason"] = voidReason
        if (voidNote != null) m["voidNote"] = voidNote
        if (voidedAt != null) m["voidedAt"] = voidedAt
        m["issuedAt"] = issuedAt
        m["issuedBy"] = issuedBy
        if (deviceId != null) m["deviceId"] = deviceId
        if (deviceNo != null) m["deviceNo"] = deviceNo
        m["offline"] = offline
        m["verifyCode"] = verifyCode
        m["verifyUrl"] = verifyUrl
        if (kind == "ambulance") {
            m["overFare"] = overFare ?: false
            if (chartFare != null) m["chartFare"] = chartFare
        }
        if (isDemo) m["demo"] = true
        // a payment receipt, never a VAT invoice (security review I15) — every client prints this line
        m["footer"] = InvoiceMath.FOOTER
        return m
    }

    /**
     * invoice_verify: only what the public page shows (security review I1, I9, I14): business, kind, no, date, total,
     * paid/due, the number of lines (not their names), valid/void (+ when), demo, and whether the business is suspended
     * now. Never the patient/customer, the lines or who issued it.
     */
    fun publicView(sanctioned: Boolean): Map<String, Any?> = linkedMapOf<String, Any?>(
        "bizName" to bizName, "kind" to kind, "no" to no, "date" to issuedAt, "total" to InvoiceMath.num(total),
        "paid" to InvoiceMath.num(paid), "due" to InvoiceMath.num(due), "lines" to lines.size,
        "status" to if (status == "void") "void" else "valid",
    ).apply {
        if (voidedAt != null) put("voidedAt", voidedAt)
        put("demo", isDemo)
        if (sanctioned) put("sanctioned", true)
        put("footer", InvoiceMath.FOOTER)
    }
}

/** money and number rules of the receipts (BDT, 2 decimals; the server recomputes every total) */
object InvoiceMath {
    const val VERIFY_BASE = "https://lifolo.com/v/"

    /** printed on every receipt: a money receipt, not a VAT (Mushak) invoice */
    const val FOOTER = "টাকা প্রাপ্তির রসিদ — মূসক চালানপত্র নয়" // bn-ok

    /** invoice_void reasons (security review I2); "other" needs a note */
    val VOID_REASONS = linkedMapOf("wrong_item" to "ভুল পণ্য", "returned" to "ক্রেতা ফেরত দিয়েছেন", "wrong_price" to "ভুল দাম", "duplicate" to "একই রসিদ দুবার", "other" to "অন্যান্য") // bn-ok
    val KINDS = listOf("pharmacy", "lab", "chamber", "ambulance")
    val METHODS = listOf("cash", "bkash", "nagad", "card", "other")
    val REF_TYPES = listOf("book_sale", "rx_order", "lab_order", "appointment", "ambreq")
    const val MAX_LINES = 100
    const val MAX_FEES = 5
    const val MAX_PAYMENTS = 5
    val MAX_MONEY: BigDecimal = BigDecimal("10000000")
    val MAX_QTY: BigDecimal = BigDecimal("100000")

    /** one receipt at most ৳100 crore (the columns hold 10^10) */
    val MAX_TOTAL: BigDecimal = BigDecimal("1000000000")
    val SERIES_RE = Regex("^(W|D[1-9][0-9]{0,2})$")
    val NO_RE = Regex("^(W|D[1-9][0-9]{0,2})-([0-9]{6,9})$")
    private const val CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"
    private val random = SecureRandom()

    fun m2(v: BigDecimal): BigDecimal = v.setScale(2, RoundingMode.HALF_UP)

    /** JSON number: 12.5, 100 (no trailing zeros) */
    fun num(v: BigDecimal): Number {
        val s = v.stripTrailingZeros()
        return if (s.scale() <= 0) s.toLong() else s.toDouble()
    }

    fun no(series: String, seq: Int): String = "$series-${seq.toString().padStart(6, '0')}"

    /** 10 random letters/digits (no 0/O/1/I/l look-alikes) */
    fun verifyCode(): String = buildString(10) { repeat(10) { append(CODE_CHARS[random.nextInt(CODE_CHARS.length)]) } }

    fun maskPhone(p: String): String = if (p.length <= 5) "•".repeat(p.length) else p.take(3) + "•".repeat(p.length - 5) + p.takeLast(2)

    /** Lines → amounts, subtotal, bill discount, fees, total. Throws 400 on discounts above the amount. */
    fun compute(lang: String?, lines: List<LineIn>, disc: BigDecimal, fees: List<FeeIn>): Totals {
        val out = lines.map { l ->
            val gross = m2(l.qty.multiply(l.price))
            val d = m2(l.disc)
            if (d > gross) throw bad(lang, "Discount is more than the price", "ছাড় দামের চেয়ে বেশি")
            Line(l.name, l.qty.stripTrailingZeros(), l.unit, m2(l.price), d, m2(gross - d), l.code)
        }
        val subtotal = m2(out.fold(BigDecimal.ZERO) { a, l -> a + l.amount })
        val bd = m2(disc)
        if (bd > subtotal) throw bad(lang, "Discount is more than the price", "ছাড় দামের চেয়ে বেশি")
        val fs = fees.map { Fee(it.label, m2(it.amount)) }
        val total = m2(subtotal - bd + fs.fold(BigDecimal.ZERO) { a, f -> a + f.amount })
        if (subtotal > MAX_TOTAL || total > MAX_TOTAL) throw bad(lang, "Check the amount", "টাকার পরিমাণ দেখে নিন") // bn-ok
        return Totals(out, subtotal, bd, fs, total)
    }

    fun bad(lang: String?, en: String, bn: String, code: String? = null) = ApiException(400, Lang.tr(lang, en, bn), code)

    // ------------------------------------------------------------------ parsing request JSON (never trusts totals)

    /** money/quantity: a number or a numeric string ("1,200.50"); empty → null; negative/too big/NaN → 400 */
    fun money(lang: String?, v: JsonNode?, en: String = "amount", bn: String = "টাকার পরিমাণ", max: BigDecimal = MAX_MONEY): BigDecimal? { // bn-ok
        if (v == null || v.isNull || v.isMissingNode || (v.isTextual && v.textValue().isBlank())) return null
        val d: BigDecimal? = when {
            v.isNumber -> v.doubleValue().takeIf { it.isFinite() }?.let { BigDecimal.valueOf(it) }
            v.isTextual -> v.textValue().replace(",", "").trim().let { t -> if (Regex("^[0-9]+(\\.[0-9]+)?$").matches(t)) BigDecimal(t) else null }
            else -> null
        }
        if (d == null || d.signum() < 0 || d > max) throw bad(lang, "Check the $en", "$bn দেখে নিন")
        return m2(d)
    }

    fun text(v: JsonNode?, max: Int): String? = Js.string(v)?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }?.take(max)

    /**
     * lines [{name, qty, price, disc?, unit?, code?}] — null when the key is missing. [priceOf]: a line with a `code` and
     * no price takes that price (a lab's list price).
     */
    fun lines(lang: String?, v: JsonNode?, priceOf: ((String) -> BigDecimal?)? = null): List<LineIn>? {
        if (v == null || v.isNull || v.isMissingNode) return null
        if (!v.isArray) throw bad(lang, "Add at least one item", "অন্তত একটা আইটেম দিন") // bn-ok
        if (v.size() > MAX_LINES) throw bad(lang, "Up to $MAX_LINES lines", "সর্বোচ্চ ${MAX_LINES}টা লাইন")
        return v.map { l ->
            if (!l.isObject) throw bad(lang, "Add at least one item", "অন্তত একটা আইটেম দিন") // bn-ok
            val name = text(l.get("name"), 100) ?: throw bad(lang, "Write the item name", "আইটেমের নাম লিখুন") // bn-ok
            val qty = money(lang, l.get("qty"), "quantity", "পরিমাণ", MAX_QTY) ?: BigDecimal.ONE
            if (qty.signum() == 0) throw bad(lang, "Check the quantity", "পরিমাণ দেখে নিন")
            val code = text(l.get("code"), 40)
            val price = money(lang, l.get("price"), "price", "দাম")
                ?: code?.let { c -> priceOf?.invoke(c) }
                ?: throw bad(lang, "Check the price", "দাম দেখে নিন")
            LineIn(name, qty, price, money(lang, l.get("disc"), "discount", "ছাড়") ?: BigDecimal.ZERO, text(l.get("unit"), 20), code)
        }
    }

    private val json = com.fasterxml.jackson.databind.ObjectMapper()

    /** lines kept for later (rx_orders.invoice_lines) */
    fun linesJson(lines: List<LineIn>): String = json.writeValueAsString(
        lines.map { l ->
            linkedMapOf<String, Any?>("name" to l.name, "qty" to num(l.qty), "price" to num(l.price), "disc" to num(l.disc)).apply {
                if (l.unit != null) put("unit", l.unit)
                if (l.code != null) put("code", l.code)
            }
        },
    )

    fun linesFromJson(text: String): List<LineIn>? = runCatching {
        json.readTree(text).map { l ->
            LineIn(
                l.get("name").asText(), BigDecimal(l.get("qty").asText()), BigDecimal(l.get("price").asText()), BigDecimal(l.get("disc").asText()),
                l.get("unit")?.asText(), l.get("code")?.asText(),
            )
        }
    }.getOrNull()

    /** fees [{label, amount}] */
    fun fees(lang: String?, v: JsonNode?): List<FeeIn>? {
        if (v == null || v.isNull || v.isMissingNode) return null
        if (!v.isArray || v.size() > MAX_FEES) throw bad(lang, "Check the fees", "ফি দেখে নিন") // bn-ok
        return v.mapNotNull { f ->
            if (!f.isObject) throw bad(lang, "Check the fees", "ফি দেখে নিন") // bn-ok
            val amount = money(lang, f.get("amount"), "fees", "ফি") ?: return@mapNotNull null // bn-ok
            if (amount.signum() == 0) return@mapNotNull null
            FeeIn(text(f.get("label"), 40) ?: throw bad(lang, "Check the fees", "ফি দেখে নিন"), amount) // bn-ok
        }
    }

    fun method(lang: String?, v: JsonNode?, default: String? = null): String {
        val m = Js.string(v)?.takeIf { it.isNotEmpty() } ?: default
        if (m == null || m !in METHODS) throw bad(lang, "Choose how it was paid", "কীভাবে টাকা দিলেন বেছে নিন", "method") // bn-ok
        return m
    }

    fun payment(lang: String?, v: JsonNode?): PaymentIn {
        if (v == null || !v.isObject) throw bad(lang, "Check the payment", "টাকা দেওয়ার তথ্য দেখে নিন") // bn-ok
        val amount = money(lang, v.get("amount"))
        if (amount == null || amount.signum() == 0) throw bad(lang, "Check the amount", "টাকার পরিমাণ দেখে নিন") // bn-ok
        return PaymentIn(amount, method(lang, v.get("method"), "cash"), text(v.get("ref"), 40))
    }

    /** payments [{amount, method, ref?}] — null when the key is missing (= fully paid in cash) */
    fun payments(lang: String?, v: JsonNode?): List<PaymentIn>? {
        if (v == null || v.isNull || v.isMissingNode) return null
        if (!v.isArray || v.size() > MAX_PAYMENTS) throw bad(lang, "Check the payment", "টাকা দেওয়ার তথ্য দেখে নিন") // bn-ok
        return v.map { payment(lang, it) }
    }

    /** customer {name?, phone?} — the old books_sale_add sends a plain name */
    fun customer(lang: String?, v: JsonNode?): Customer {
        if (v == null || v.isNull || v.isMissingNode) return Customer()
        if (v.isTextual) return Customer(text(v, 60))
        if (!v.isObject) return Customer()
        val phone = Js.string(v.get("phone"))?.replace(Regex("[\\s-]"), "")?.takeIf { it.isNotEmpty() }
        if (phone != null && !Regex("^\\+?[0-9]{5,15}$").matches(phone)) throw bad(lang, "Check the phone number", "ফোন নম্বর দেখে নিন") // bn-ok
        return Customer(text(v.get("name"), 60), phone)
    }

    /** a free-text fee like "৫০০ টাকা", "500/-", "Tk 300" → 500; null when there is no number */
    fun takaOf(text: String?): BigDecimal? {
        if (text.isNullOrBlank()) return null
        val ascii = text.map { c -> if (c in '\u09E6'..'\u09EF') '0' + (c - '\u09E6') else c }.joinToString("").replace(",", "")
        val m = Regex("[0-9]+(\\.[0-9]+)?").find(ascii) ?: return null
        val v = BigDecimal(m.value)
        return if (v.signum() > 0 && v <= MAX_MONEY) m2(v) else null
    }
}
