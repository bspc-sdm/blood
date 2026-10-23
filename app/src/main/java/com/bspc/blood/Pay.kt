package com.bspc.blood

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/*
 * লাইফলো পে — মোবাইল ব্যাংকিংয়ে (bKash/Nagad/Rocket/Upay) রোগী সরাসরি ব্যবসার নম্বরে টাকা পাঠান, ব্যবসা মিলিয়ে
 * নিশ্চিত করে (মালিক অনুমোদন দিয়েছেন ২৩ অক্টোবর ২০২৬; চুক্তি backend/docs/PAYMENTS.md, spring-p20)।
 * Lifolo কখনো অন্যের টাকা রাখে না; রক্তের কোনো কিছুতে কোনো পেমেন্ট নেই; অ্যাম্বুলেন্সে শুধু যাত্রার পরে, কখনো বাধ্যতামূলক নয়;
 * ক্যাশ সবসময় চলে। সব কিছু ফিচার সুইচে (শুরুতে বন্ধ)।
 * পাতা: PaySheet.kt (রোগীর পেমেন্টের পাতা, তালিকা, ঢোকার বাটন), PayBusiness.kt (ব্যবসার "পেমেন্ট")।
 */

/** ব্যবসার একটা গ্রহণের নম্বর (pay_methods_get / অনুরোধের ছবি) */
data class PayMethod(
    /** bkash | nagad | rocket | upay */
    val method: String,
    val number: String,
    /** personal | merchant */
    val type: String = "personal",
    val active: Boolean = true,
    /** বদলানো নম্বর কখন চালু হবে (২৪ ঘণ্টার বিরতি) — না থাকলে null */
    val pendingUntil: Long? = null,
)

/** by: কে "পাঠিয়েছি" বললেন (কেয়ারগিভার নিজের ওয়ালেট থেকে দিলে তাঁর uid) */
data class PayClaim(val method: String, val trxId: String, val senderLast4: String, val at: Long, val by: String = "")

/** নিশ্চিত হওয়া টাকা ফেরত পাওনা (ব্যবসা বাতিল করেছে, অর্ডার আসেনি…) — why: সার্ভারের কারণ */
data class PayRefundDue(val at: Long, val why: String)
data class PayRefund(val trxId: String, val amount: Double, val at: Long, val ackAt: Long)

/** একটা পেমেন্টের অনুরোধ (চুক্তির "Pay request") */
data class PayRequest(
    val id: String,
    /** pharmacy | lab | chamber | ambulance */
    val kind: String = "",
    val bizId: String = "",
    val bizName: String = "",
    val payerUid: String = "",
    /** ব্যবসার দিকে: কে পাঠালেন (সার্ভার দিলে) */
    val payerName: String = "",
    val refType: String = "",
    val refId: String = "",
    /** chamber_fee | booking_advance | delivery | lab | ambulance_fare | invoice_due */
    val purpose: String = "",
    val amount: Double = 0.0,
    /** তৈরির সময়ের নম্বরগুলো (পরে ব্যবসা বদলালেও এই অনুরোধে এগুলোই) */
    val methods: List<PayMethod> = emptyList(),
    /** open | claimed | confirmed | rejected | expired | refunded | cancelled */
    val status: String = "open",
    val claim: PayClaim? = null,
    val confirmedAt: Long = 0L,
    val confirmedBy: String = "",
    val rejectReason: String = "",
    val rejectNote: String = "",
    val refund: PayRefund? = null,
    val invoiceId: String = "",
    val expiresAt: Long = 0L,
    val createdAt: Long = 0L,
    /** অগ্রিম: এই সময়ের আগে বাতিল করলে ফেরত (সার্ভারের refundableUntil) — না থাকলে ০ */
    val refundableUntil: Long = 0L,
    /** ডেমো দুনিয়ার অনুরোধ — "আসল টাকা পাঠাবেন না" */
    val demo: Boolean = false,
    val refundDue: PayRefundDue? = null,
    /** pay_request_get-এর ফেরতের নিয়ম + "শুধু এই নম্বরে…" (সার্ভারের লেখা; না এলে অ্যাপেরটা) */
    val policy: String = "",
    val demoNote: String = "",
) {
    val activeMethods: List<PayMethod> get() = methods.filter { it.active && it.number.isNotBlank() && it.method in PayRules.METHODS }
}

/** pay_summary: আজ কত এল, কয়টা খোলা, কয়টা যাচাই বাকি */
data class PaySummary(val collected: Double, val open: Int, val pending: Int, val count: Int = 0)

/** pay_methods_get: নম্বরগুলো আর (ডাক্তার) চেম্বারপ্রতি অগ্রিম */
data class PaySettings(
    val methods: List<PayMethod>,
    val advances: List<ChamberAdvance> = emptyList(),
    val pendingUntil: Long? = null,
    /** সাম্প্রতিক বদল (pay_method_log) — "আমি করিনি" এখান থেকেও */
    val changes: List<PayMethodChange> = emptyList(),
    val frozen: Boolean = false,
    val frozenSince: Long = 0L,
)

/** একটা নম্বরের বদল: added | changed | removed | activated … (নম্বরের শুধু শেষ ৪ সংখ্যা) */
data class PayMethodChange(val method: String, val change: String, val oldLast4: String, val newLast4: String, val at: Long)

/** সামনাসামনি চেম্বারের অগ্রিম বুকিং ফি (ডাক্তার নিজে চালু করলে) */
data class ChamberAdvance(
    val chamberKey: String,
    val name: String = "",
    val nameBn: String = "",
    /** ০ = বন্ধ */
    val amount: Double = 0.0,
    /** সিরিয়ালের কত ঘণ্টা আগে পর্যন্ত বাতিল করলে ফেরত */
    val refundableUntilHoursBefore: Int = 24,
) {
    val on: Boolean get() = amount > 0.004
    val label: String get() = if (isBangla && nameBn.isNotBlank()) nameBn else name.ifBlank { chamberKey }
}

data class PayPage(val items: List<PayRequest>, val cursor: String?)

/** ফিচার সুইচ — প্রতিটা ঢোকার পথ নিজের চাবিতে; pay_manual সবার আগে লাগে, কিছুতে রসিদের চাবিও (চুক্তির "needs") */
object PayGate {
    const val MANUAL = Features.PAY_MANUAL
    const val CHAMBER_ADVANCE = Features.PAY_CHAMBER_ADVANCE
    const val DELIVERY = Features.PAY_DELIVERY
    const val LAB = Features.PAY_LAB
    const val AMBULANCE = Features.PAY_AMBULANCE
    const val DUE = Features.PAY_DUE
    const val PGW = Features.PAY_BKASH_PGW

    val ALL = listOf(MANUAL, CHAMBER_ADVANCE, DELIVERY, LAB, AMBULANCE, DUE, PGW)

    /** কোন ব্যবসার "পেমেন্ট" আছে */
    val KINDS = setOf("pharmacy", "lab", "chamber", "ambulance")

    /** টেস্টে: null = আসল Features */
    var testKeys: Map<String, Boolean>? = null

    private fun raw(key: String): Boolean = testKeys?.get(key) ?: Features.on(key)

    /** চাবি আর তার দরকারি চাবিগুলো সব চালু কি না */
    fun on(key: String): Boolean = when (key) {
        MANUAL -> raw(MANUAL)
        CHAMBER_ADVANCE, DUE -> raw(MANUAL) && raw(key)
        DELIVERY -> raw(MANUAL) && raw(key) && raw(InvoiceFeatures.key("pharmacy"))
        LAB -> raw(MANUAL) && raw(key) && raw(InvoiceFeatures.key("lab"))
        AMBULANCE -> raw(MANUAL) && raw(key) && raw(InvoiceFeatures.key("ambulance"))
        // অনলাইন চেম্বারের ফি নিজের চাবিতেই (online_chamber) — এই স্তরে এলেও
        OnlineGate.CHAMBER -> OnlineGate.on(OnlineGate.CHAMBER)
        else -> raw(key)
    }

    /** কোন কাজের পেমেন্ট কোন চাবিতে */
    fun keyFor(purpose: String): String? = when (purpose) {
        PayRules.ADVANCE -> CHAMBER_ADVANCE
        PayRules.DELIVERY -> DELIVERY
        PayRules.LAB -> LAB
        PayRules.AMB_FARE -> AMBULANCE
        PayRules.DUE -> DUE
        PayRules.CHAMBER_FEE -> OnlineGate.CHAMBER
        else -> null
    }

    fun purposeOn(purpose: String): Boolean = keyFor(purpose)?.let(::on) ?: false

    /** ব্যবসার "পেমেন্ট" ভাগ: সেই ব্যবসার কোনো পেমেন্টের চাবি, বা রসিদের বাকি টাকা */
    fun bizKeys(kind: String): List<String> = when (kind) {
        "pharmacy" -> listOf(DELIVERY, DUE)
        "lab" -> listOf(LAB, DUE)
        "chamber" -> listOf(CHAMBER_ADVANCE, DUE)
        "ambulance" -> listOf(AMBULANCE, DUE)
        else -> emptyList()
    }

    fun biz(kind: String): Boolean = bizKeys(kind).any(::on)

    /** রোগীর "পেমেন্ট" ভাগ — যেকোনো পেমেন্ট চালু থাকলে (অনলাইন চেম্বারের ফিও এই তালিকায়) */
    fun patient(): Boolean = on(MANUAL) || OnlineGate.on(OnlineGate.CHAMBER)

    /** কম্পোজে: সুইচ বদলালে পাতা নিজে বদলায় */
    @Composable
    fun rememberOn(key: String): Boolean {
        val keys by Features.keys.collectAsState()
        return remember(keys, key, testKeys) { on(key) }
    }

    @Composable
    fun rememberBiz(kind: String): Boolean {
        val keys by Features.keys.collectAsState()
        return remember(keys, kind, testKeys) { biz(kind) }
    }

    @Composable
    fun rememberPatient(): Boolean {
        val keys by Features.keys.collectAsState()
        return remember(keys, testKeys) { patient() }
    }
}

/** নিয়ম আর হিসাব (সার্ভারও একই নিয়মে আটকায়; এখানে আগে বলে দেওয়া আর বাটন দেখানো/লুকানো) */
object PayRules {
    const val CHAMBER_FEE = "chamber_fee"
    const val ADVANCE = "booking_advance"
    const val DELIVERY = "delivery"
    const val LAB = "lab"
    const val AMB_FARE = "ambulance_fare"
    const val DUE = "invoice_due"

    val METHODS = listOf("bkash", "nagad", "rocket", "upay")
    val TYPES = listOf("personal", "merchant")
    val REJECT_REASONS = listOf("not_received", "wrong_amount", "wrong_number", "other")
    /** refund_claimed: ব্যবসা বলছে ফেরত পাঠিয়েছে — রোগী "ফেরত পেয়েছি" চাপলে তবে refunded (নিরাপত্তা পর্যালোচনা P5) */
    val STATUSES = listOf("open", "claimed", "confirmed", "rejected", "expired", "refund_claimed", "refunded", "cancelled")

    /** রক্তের কোনো কিছুতে কখনো পেমেন্ট নয় (সার্ভার 400 no_payment_blood) */
    private val BLOOD_REFS = setOf("blood", "blood_request", "request", "donation", "donor", "emergency", "blood_bank")
    fun isBlood(refType: String): Boolean = refType.lowercase() in BLOOD_REFS || refType.lowercase().startsWith("blood")

    /** বাংলা অঙ্ক → ইংরেজি */
    fun enDigits(s: String): String = s.map { c -> if (c in '০'..'৯') '0' + (c - '০') else c }.joinToString("")

    /** TrxID: bKash ঠিক ১০টা, বাকিগুলো ৬–২০টা ইংরেজি অক্ষর/সংখ্যা */
    fun trxOk(method: String, t: String): Boolean =
        if (method == "bkash") Regex("^[A-Za-z0-9]{10}$").matches(t) else Regex("^[A-Za-z0-9]{6,20}$").matches(t)

    fun trxMax(method: String): Int = if (method == "bkash") 10 else 20

    /** লেখার সময়: শুধু ইংরেজি অক্ষর/সংখ্যা, বড় হাতের, সর্বোচ্চ দৈর্ঘ্য পর্যন্ত */
    fun cleanTrx(method: String, raw: String): String =
        enDigits(raw).filter { it.isLetterOrDigit() && it.code < 128 }.uppercase().take(trxMax(method))

    /** যে নম্বর থেকে পাঠিয়েছেন তার শেষ ৪ সংখ্যা */
    fun cleanLast4(raw: String): String = enDigits(raw).filter { it.isDigit() }.take(4)
    fun last4Ok(s: String): Boolean = Regex("^\\d{4}$").matches(s)

    /** গ্রহণের নম্বর: বাংলাদেশি মোবাইল (ডেমো অ্যাকাউন্টে ডেমোর নম্বরও) */
    fun numberOk(raw: String, demo: Boolean = DemoLogin.isDemo(AuthRepository.currentUid)): Boolean = Rules.isMobile(OnlineChambers.cleanNumber(raw), demo)

    /** "টাকা পাঠিয়েছি" দেওয়া যায়: খোলা বা ব্যবসা "পাইনি" বলেছে, আর সময় পেরোয়নি */
    fun canClaim(r: PayRequest, now: Long = System.currentTimeMillis()): Boolean =
        r.status in setOf("open", "rejected") && (r.expiresAt <= 0L || now < r.expiresAt) && r.activeMethods.isNotEmpty() && !isBlood(r.refType)

    /** ফেরত পেয়েছেন জানানো বাকি (refund_claimed; পুরোনো উত্তরে refunded কিন্তু ackAt নেই) */
    fun canAckRefund(r: PayRequest): Boolean =
        r.status == "refund_claimed" || (r.status == "refunded" && r.refund != null && r.refund.ackAt <= 0L)

    /** রিপোর্ট: টাকা পাঠানোর পরের যেকোনো অবস্থায় (ব্যবসা "পাইনি" বললে আপত্তিও এখান দিয়েই) */
    fun canReport(r: PayRequest): Boolean = r.claim != null || r.status in setOf("claimed", "confirmed", "rejected", "refund_claimed", "refunded")

    /** ব্যবসা নিশ্চিত করার আগে তিনটা মিল (পর্যালোচনা P1): TrxID, টাকা, শেষ ৪ সংখ্যা — শেষ ৪ না থাকলে (পুরোনো দাবি) দুটো */
    val CONFIRM_CHECKS = listOf("trxId", "amount", "last4")
    fun confirmChecks(r: PayRequest): List<String> = if (r.claim?.senderLast4.isNullOrBlank()) CONFIRM_CHECKS - "last4" else CONFIRM_CHECKS
    fun confirmReady(r: PayRequest, ticked: Set<String>): Boolean = confirmChecks(r).all { it in ticked }

    /** ব্যবসা: যাচাই বাকি থাকলে নিশ্চিত/বাতিল */
    fun canDecide(r: PayRequest): Boolean = r.status == "claimed"

    /** ব্যবসা (মালিক): নিশ্চিত হওয়া টাকা ফেরত */
    fun canRefund(r: PayRequest): Boolean = r.status == "confirmed"

    /** অগ্রিম পাঠানো হয়নি — বুকিং বাতিল হয় না, শুধু "অগ্রিম বাকি" দেখায় */
    fun advanceDue(r: PayRequest): Boolean = r.purpose == ADVANCE && r.status in setOf("open", "rejected")

    /** বাতিলের কারণ ঠিক আছে কি না ("অন্যান্য" হলে লেখা লাগবেই) */
    fun rejectOk(reason: String?, note: String): Boolean = reason != null && (reason != "other" || note.trim().length >= 3)

    /** ফেরতের টাকা: খালি = পুরোটা; নইলে ০-এর বেশি আর অনুরোধের টাকার বেশি নয় */
    fun refundAmountOk(raw: String, max: Double): Boolean {
        if (raw.isBlank()) return true
        val v = InvoiceMath.parse(raw) ?: return true
        return v > 0.0 && v <= max + 0.004
    }

    /** ধাপের অবস্থা */
    enum class Mark { DONE, NOW, TODO, BAD }
    data class Step(val label: String, val at: Long, val mark: Mark)

    /** অবস্থার টাইমলাইন: অনুরোধ → পাঠিয়েছেন → যাচাই → নিশ্চিত/পাননি/সময় শেষ → ফেরত */
    fun timeline(r: PayRequest, who: String = PayText.who(r.kind)): List<Step> {
        val out = ArrayList<Step>()
        out += Step(tr("Payment requested", "পেমেন্টের অনুরোধ"), r.createdAt, Mark.DONE)
        val c = r.claim
        when (r.status) {
            "open" -> {
                out += Step(tr("You send the money", "আপনি টাকা পাঠাবেন"), 0L, Mark.NOW)
                out += Step(tr("$who checks and confirms", "$who মিলিয়ে নিশ্চিত করবেন"), 0L, Mark.TODO)
            }
            "claimed" -> {
                out += Step(tr("Money sent", "টাকা পাঠিয়েছেন"), c?.at ?: 0L, Mark.DONE)
                out += Step(tr("$who is checking", "$who যাচাই করছেন"), 0L, Mark.NOW)
            }
            "rejected" -> {
                if (c != null) out += Step(tr("Money sent", "টাকা পাঠিয়েছেন"), c.at, Mark.DONE)
                out += Step(tr("$who didn't get it", "$who পাননি") + PayText.rejectSuffix(r), 0L, Mark.BAD)
                out += Step(tr("Check and send the details again", "মিলিয়ে আবার জানান"), 0L, Mark.NOW)
            }
            "confirmed", "refund_claimed", "refunded" -> {
                if (c != null) out += Step(tr("Money sent", "টাকা পাঠিয়েছেন"), c.at, Mark.DONE)
                out += Step(tr("Confirmed — receipt given", "নিশ্চিত হয়েছে — রসিদ দেওয়া হয়েছে"), r.confirmedAt, Mark.DONE)
                val f = r.refund
                if (r.status != "confirmed") {
                    out += Step(tr("$who says the money was sent back", "$who বলছেন টাকা ফেরত পাঠিয়েছেন"), f?.at ?: 0L, Mark.DONE)
                    out += if (r.status == "refunded" || (f != null && f.ackAt > 0)) Step(tr("You got it back", "ফেরত পেয়েছেন"), f?.ackAt ?: 0L, Mark.DONE)
                    else Step(tr("Did you get it back?", "ফেরত পেয়েছেন?"), 0L, Mark.NOW)
                }
            }
            "expired" -> out += Step(tr("Time ran out — nothing was sent", "সময় শেষ — টাকা পাঠানো হয়নি"), r.expiresAt, Mark.BAD)
            "cancelled" -> out += Step(tr("Cancelled", "বাতিল হয়েছে"), 0L, Mark.BAD)
        }
        return out
    }
}

/** লেখা */
object PayText {
    /** ব্র্যান্ডের নাম লেখায় (লোগো নয়) — দুই ভাষাতেই একই, "নগদ" (ক্যাশ) শব্দের সাথে যেন না গুলায় */
    fun method(m: String): String = when (m) {
        "bkash" -> "bKash"
        "nagad" -> "Nagad"
        "rocket" -> "Rocket"
        "upay" -> "Upay"
        else -> m
    }

    fun type(t: String): String = if (t == "merchant") tr("Merchant", "মার্চেন্ট") else tr("Personal", "পার্সোনাল")

    /** কোন বাটনে: পার্সোনাল → Send Money, মার্চেন্ট → Payment */
    fun verb(type: String): String = if (type == "merchant") tr("Payment", "পেমেন্ট") else tr("Send Money", "সেন্ড মানি")

    fun purpose(p: String): String = when (p) {
        PayRules.CHAMBER_FEE -> tr("Online chamber fee", "অনলাইন চেম্বারের ফি")
        PayRules.ADVANCE -> tr("Serial advance", "সিরিয়ালের অগ্রিম")
        PayRules.DELIVERY -> tr("Medicines (home delivery)", "ওষুধের দাম (হোম ডেলিভারি)")
        PayRules.LAB -> tr("Test bill", "পরীক্ষার বিল")
        PayRules.AMB_FARE -> tr("Ambulance fare", "অ্যাম্বুলেন্স ভাড়া")
        PayRules.DUE -> tr("Receipt due", "রসিদের বাকি")
        else -> tr("Payment", "পেমেন্ট")
    }

    /** কে মিলিয়ে নেবেন */
    fun who(kind: String): String = when (kind) {
        "chamber" -> tr("The doctor", "ডাক্তার")
        "pharmacy" -> tr("The pharmacy", "ফার্মেসি")
        "lab" -> tr("The lab", "প্যাথলজি")
        "ambulance" -> tr("The driver", "চালক")
        else -> tr("They", "প্রতিষ্ঠান")
    }

    /** কার নম্বর (সম্বন্ধ পদ) */
    fun whose(kind: String): String = when (kind) {
        "chamber" -> tr("Doctor's", "ডাক্তারের")
        "pharmacy" -> tr("Pharmacy's", "ফার্মেসির")
        "lab" -> tr("Lab's", "প্যাথলজির")
        "ambulance" -> tr("Driver's", "চালকের")
        else -> ""
    }

    /** "ডাক্তারের bKash (সেন্ড মানি)" / "ফার্মেসির Nagad (পেমেন্ট — মার্চেন্ট)" */
    fun numberLabel(kind: String, m: PayMethod): String =
        listOf(whose(kind), method(m.method)).filter { it.isNotBlank() }.joinToString(" ") +
            " (" + verb(m.type) + (if (m.type == "merchant") " — " + type("merchant") else "") + ")"

    /**
     * টাকা পাঠানোর ধাপ: ১) অ্যাপে Send Money/Payment — টাকা — উপরের নম্বরে ২) মেসেজের TrxID রাখুন ৩) "টাকা পাঠিয়েছি" — কে মেলাবেন।
     * পার্সোনাল আর মার্চেন্টে আলাদা।
     */
    fun steps(m: PayMethod, amountText: String, who: String): List<String> {
        val name = method(m.method)
        val v = verb(m.type)
        return listOf(
            tr("Open $name and $v", "$name অ্যাপে \"$v\" করুন") + (if (amountText.isNotBlank()) " — $amountText" else "") +
                if (m.type == "merchant") tr(" to the merchant number above", " উপরের মার্চেন্ট নম্বরে") else tr(" to the number above", " উপরের নম্বরে"),
            tr("Keep the TrxID from the $name message", "$name-এর মেসেজের TrxID রেখে দিন"),
            tr("Tap \"I've sent the money\" — ${who.lowercase()} confirms it", "\"টাকা পাঠিয়েছি\" চাপুন — $who মিলিয়ে নিশ্চিত করবেন"),
        )
    }

    fun trxHint(method: String): String =
        if (method == "bkash") tr("A bKash TrxID has exactly 10 English letters/digits", "bKash-এর TrxID ঠিক ১০ অক্ষরের (ইংরেজি অক্ষর ও সংখ্যা)")
        else tr("A ${method(method)} TrxID has 6–20 English letters/digits", "${method(method)}-এর TrxID ৬ থেকে ২০ অক্ষরের (ইংরেজি অক্ষর ও সংখ্যা)")

    /** refundDue.why */
    fun dueWhy(w: String): String = when (w) {
        "declined", "biz_cancelled", "cancelled_by_business" -> tr("they cancelled", "তাঁরা বাতিল করেছেন")
        "patient_cancelled", "cancelled_in_time" -> tr("you cancelled in time", "আপনি সময়মতো বাতিল করেছেন")
        "not_delivered" -> tr("not delivered", "পৌঁছায়নি")
        "order_cancelled", "withdrawn" -> tr("order cancelled", "অর্ডার বাতিল")
        "collection_cancelled" -> tr("collection cancelled", "নমুনা নেওয়া বাতিল")
        else -> w.replace('_', ' ')
    }

    val LAST4_HINT: String get() = tr("The last 4 digits of the number you sent from", "যে নম্বর থেকে পাঠিয়েছেন, তার শেষ ৪ সংখ্যা")

    /** রোগীর দিক থেকে অবস্থা */
    fun status(s: String): String = when (s) {
        "open" -> tr("Not sent", "পাঠানো হয়নি")
        "claimed" -> tr("Being checked", "যাচাই হচ্ছে")
        "confirmed" -> tr("Paid", "পরিশোধিত")
        "rejected" -> tr("Not received", "পাননি")
        "expired" -> tr("Time over", "সময় শেষ")
        "refund_claimed" -> tr("Refund sent?", "ফেরত এসেছে?")
        "refunded" -> tr("Refunded", "ফেরত পেয়েছেন")
        "cancelled" -> tr("Cancelled", "বাতিল")
        else -> s
    }

    /** ব্যবসার দিক থেকে অবস্থা */
    fun bizStatus(s: String): String = when (s) {
        "open" -> tr("Waiting for patient", "রোগীর অপেক্ষায়")
        "claimed" -> tr("Check it", "যাচাই করুন")
        "confirmed" -> tr("Received", "পেয়েছেন")
        "rejected" -> tr("Not received", "পাননি")
        "refund_claimed" -> tr("Refund — waiting for patient", "ফেরত — রোগীর অপেক্ষায়")
        "refunded" -> tr("Refunded", "ফেরত দেওয়া হয়েছে")
        else -> status(s)
    }

    fun rejectReason(r: String): String = when (r) {
        "not_received" -> tr("Money didn't arrive", "টাকা আসেনি")
        "wrong_amount" -> tr("Wrong amount", "টাকার অঙ্ক মেলেনি")
        "wrong_number" -> tr("Sent to a wrong number", "ভুল নম্বরে পাঠানো")
        "other" -> tr("Other", "অন্যান্য")
        else -> r
    }

    /** ": টাকা আসেনি — নোট" (না থাকলে খালি) */
    fun rejectSuffix(r: PayRequest): String {
        val t = listOf(if (r.rejectReason.isNotBlank()) rejectReason(r.rejectReason) else "", r.rejectNote).filter { it.isNotBlank() }.joinToString(" — ")
        return if (t.isBlank()) "" else ": $t"
    }

    /** রিপোর্টের কারণ */
    val REPORT_REASONS = listOf("not_confirmed", "asked_other_number", "no_refund", "asked_more", "wrong_number", "scam", "other")
    fun reportReason(r: String): String = when (r) {
        "not_confirmed" -> tr("I paid but they say they didn't get it", "টাকা দিয়েছি, কিন্তু বলছে পায়নি")
        "asked_other_number" -> tr("Asked me to pay to another number", "অন্য নম্বরে টাকা চেয়েছে")
        "no_refund" -> tr("Refund not received", "ফেরত পাইনি")
        "asked_more" -> tr("Asked for more than agreed", "কথার চেয়ে বেশি চেয়েছে")
        "wrong_number" -> tr("The number here is wrong / someone else's", "এখানের নম্বরটা ভুল / অন্য কারও")
        "scam" -> tr("Looks like a scam", "প্রতারণা মনে হচ্ছে")
        else -> tr("Other", "অন্যান্য")
    }

    /** সবসময় দেখানো সতর্কতা (পর্যালোচনা P2) */
    val ONLY_HERE: String get() = tr(
        "Send money only to the number shown here. If anyone gives another number in chat or on the phone, don't send — report it.",
        "শুধু এখানে দেখানো নম্বরে টাকা পাঠান। কেউ চ্যাটে বা ফোনে অন্য নম্বর দিলে পাঠাবেন না, রিপোর্ট করুন।",
    )
    val NO_PIN: String get() = tr("Lifolo never asks for your PIN, OTP or money.", "Lifolo কখনো PIN, OTP বা টাকা চায় না।")
    val RECHECK: String get() = tr("Check the number again before you send", "পাঠানোর আগে নম্বরটা আবার মিলিয়ে নিন")
    val DEMO: String get() = tr("DEMO — don't send real money", "ডেমো — আসল টাকা পাঠাবেন না")
    val NO_NUMBERS: String get() = tr("They aren't taking online payments yet — pay in cash.", "এই প্রতিষ্ঠান এখনো অনলাইন পেমেন্ট নিচ্ছে না — নগদে দিন।")

    /** টাকা দেওয়ার আগে ফেরতের নিয়ম (পর্যালোচনা P11) */
    fun refundPolicy(r: PayRequest): String = when (r.purpose) {
        PayRules.ADVANCE -> tr("If the doctor cancels, you get it all back", "ডাক্তার বাতিল করলে পুরো টাকা ফেরত") +
            (if (r.refundableUntil > 0) tr("; cancel before ", "; আপনি ") + InvoiceText.dateTime(r.refundableUntil, isBangla) + tr(" and you get it back too", "-এর আগে বাতিল করলেও ফেরত") else "") +
            tr(". Not paying the advance doesn't cancel the booking.", "। অগ্রিম না দিলেও বুকিং বাতিল হয় না।")
        PayRules.CHAMBER_FEE -> tr("If the doctor cancels or doesn't join, the fee is yours to get back — report it if it doesn't come.", "ডাক্তার বাতিল করলে বা কলে না এলে ফি ফেরত পাওনা — না পেলে রিপোর্ট করুন।")
        PayRules.DELIVERY -> tr("If the medicines don't come or the order is cancelled, you get it all back.", "ওষুধ না এলে বা অর্ডার বাতিল হলে পুরো টাকা ফেরত পাওনা।")
        PayRules.LAB -> tr("If the sample isn't taken or the test is cancelled, you get it all back.", "নমুনা না নিলে বা পরীক্ষা বাতিল হলে পুরো টাকা ফেরত পাওনা।")
        PayRules.AMB_FARE -> tr("Pay only the fare on your receipt. Asked for more? Report it.", "শুধু রসিদের ভাড়াটুকু দিন। বেশি চাইলে রিপোর্ট করুন।")
        else -> tr("Pay only what's due on your receipt.", "শুধু রসিদের বাকিটুকু দিন।")
    } + " " + tr("Refunds come from them, to the number you paid from.", "ফেরত দেবেন তাঁরাই, যে নম্বর থেকে দিয়েছেন সেখানে।")

    val COOL_OFF: String get() = tr(
        "A new or changed number starts after 24 hours. Requests already sent keep the old number.",
        "নতুন বা বদলানো নম্বর ২৪ ঘণ্টা পর চালু হবে। আগে পাঠানো অনুরোধে আগের নম্বরই থাকবে।",
    )

    val DIRECT: String get() = tr(
        "The money goes straight to them. Lifolo doesn't hold or take any of it. You can always pay in cash instead.",
        "টাকা সরাসরি তাঁদের কাছে যায়। Lifolo টাকা রাখে না, কিছু কাটে না। চাইলে ক্যাশেও দিতে পারেন।",
    )

    /** লক-স্ক্রিনের লেখা — টাকা বা নম্বর কিছু নয় (চুক্তি) */
    val PUSH_TITLE: String get() = tr("Payment update — Lifolo", "পেমেন্ট আপডেট — লাইফলো")
    val PUSH_BODY: String get() = tr("Open the app to see it", "অ্যাপ খুলে দেখুন")

    fun money(v: Double): String = InvoiceText.money(v, isBangla)

    /** ভুলের বার্তা বাংলায় */
    fun error(e: Throwable): String {
        val code = (e as? ServerError)?.code.orEmpty()
        return when (code) {
            "pay_ref_used" -> tr(
                "This TrxID was already claimed here. If you sent it yourself, report it — don't send money again.",
                "এই TrxID দিয়ে এখানে আগে দাবি হয়েছে। আপনি নিজে পাঠিয়ে থাকলে রিপোর্ট করুন — আবার টাকা পাঠাবেন না।",
            )
            "pay_ref_rejected" -> tr("They said they didn't get this TrxID — report it.", "এই TrxID দোকান বাতিল করেছে — রিপোর্ট করুন।")
            "reauth_required" -> tr("Log in again to change payment numbers.", "পেমেন্টের নম্বর বদলাতে আবার লগইন করুন।")
            "pay_frozen" -> tr("Payment numbers are frozen — Lifolo is checking.", "পেমেন্টের নম্বর আটকে রাখা আছে — Lifolo দেখছে।")
            "demo_number" -> tr("Demo accounts use demo numbers (0120000XXXX) only.", "ডেমো অ্যাকাউন্টে শুধু ডেমো নম্বর (0120000XXXX) চলে।")
            "sender_digits" -> LAST4_HINT
            "trx_format" -> tr("Check the TrxID — copy it from the message.", "TrxID মিলিয়ে দেখুন — মেসেজ থেকে হুবহু লিখুন।")
            "method" -> tr("Choose one of the numbers shown here.", "এখানে দেখানো নম্বরগুলোর একটা বেছে নিন।")
            "no_methods" -> NO_NUMBERS
            "pay_paused" -> tr("Online payments are paused for this business — pay in cash.", "এই প্রতিষ্ঠানের অনলাইন পেমেন্ট এখন বন্ধ — নগদে দিন।")
            "already_paid" -> tr("This is already paid.", "এটা আগেই পরিশোধ হয়েছে।")
            "nothing_due" -> tr("Nothing is due.", "কোনো বাকি নেই।")
            "not_finished" -> tr("The fare can be paid after the trip is finished.", "যাত্রা শেষ হওয়ার পরেই ভাড়া দেওয়া যায়।")
            "no_amount" -> tr("There's no amount yet — write the bill first.", "এখনো টাকার অঙ্ক নেই — আগে বিল/ওষুধ লিখুন।")
            "checks_required" -> tr("Tick all three checks first.", "আগে তিনটা মিলেই টিক দিন।")
            "owner_only" -> tr("Only the owner can do this.", "এটা শুধু মালিক করতে পারেন।")
            "feature_off" -> tr("This is coming soon", "এই সুবিধা শিগগির আসছে")
            "pay_claim_rate", "rate_limited", "too_many" -> tr("Too many tries — try again later.", "অনেকবার চেষ্টা হয়েছে — কিছুক্ষণ পরে আবার চেষ্টা করুন।")
            "pay_one_open", "pay_claim_limit" -> tr("Finish your other payment that's being checked first.", "যাচাই চলা আগের পেমেন্টটা আগে শেষ হোক।")
            "no_payment_blood" -> tr("There's no payment for blood.", "রক্তের জন্য কোনো পেমেন্ট নেই।")
            "pay_expired", "expired" -> tr("Time for this payment is over.", "এই পেমেন্টের সময় শেষ।")
            "state", "pay_state" -> tr("Can't be done now — refresh and check.", "এই অবস্থায় করা যায় না — রিফ্রেশ করে দেখুন।")
            "pay_no_methods" -> tr("They haven't set a payment number yet. Pay in cash.", "তাঁরা এখনো পেমেন্টের নম্বর দেননি। ক্যাশে দিন।")
            "invalid_trx", "pay_trx" -> tr("Check the TrxID.", "TrxID মিলিয়ে দেখুন।")
            "pgw_unavailable" -> tr("Online payment isn't available now.", "অনলাইন পেমেন্ট এখন চালু নেই।")
            else -> e.message ?: tr("Something went wrong", "সমস্যা হয়েছে")
        }
    }
}

/** কোথায় খুলবে আর কখন তালিকা নতুন করে আনবে */
object PayNav {
    /** খোলা পেমেন্টের পাতা: id, হাতে থাকলে request, কেয়ারগিভার হলে asUid */
    data class Show(val id: String, val request: PayRequest? = null, val asUid: String? = null)

    /** ব্যবসার "পেমেন্ট" পাতা — bizId না থাকলে পাতা নিজে খুঁজে নেয়; assistant: ডাক্তারের সহকারী (শুধু যাচাই) */
    data class Biz(val kind: String, val bizId: String? = null, val assistant: Boolean = false, val tab: Int = 0, val reqId: String? = null)

    val show = mutableStateOf<Show?>(null)
    val biz = mutableStateOf<Biz?>(null)
    /** নিশ্চিত/দাবি/ফেরত হলে বাড়ে — তালিকাগুলো নতুন করে আনে */
    val changed = mutableIntStateOf(0)

    fun open(r: PayRequest, asUid: String? = null) {
        PayStore.put(r)
        show.value = Show(r.id, r, asUid)
    }

    fun openId(id: String, asUid: String? = null) {
        show.value = Show(id, PayStore.byId(id), asUid)
    }

    fun openBiz(kind: String, bizId: String? = null, assistant: Boolean = false, tab: Int = 0, reqId: String? = null) {
        biz.value = Biz(kind, bizId, assistant, tab, reqId)
    }

    fun bump() {
        changed.intValue++
    }
}

/**
 * রোগীর পেমেন্টগুলো (my_payments) হাতে রাখা — অর্ডার/অ্যাপয়েন্টমেন্ট/অ্যাম্বুলেন্সের কার্ডে "টাকা পাঠান" বাটন
 * refId দিয়ে নিজের অনুরোধ খুঁজে নেয় (প্রতিটা কার্ডে আলাদা ডাক নয়)। asUid: কেয়ারগিভারের রোগী।
 */
object PayStore {
    private val state = MutableStateFlow<Map<String, List<PayRequest>>>(emptyMap())
    val all: StateFlow<Map<String, List<PayRequest>>> = state
    private val loadedAt = HashMap<String, Long>()

    private fun key(asUid: String?) = asUid ?: ""

    fun forRef(refId: String, purpose: String? = null, asUid: String? = null): PayRequest? =
        state.value[key(asUid)].orEmpty().filter { it.refId == refId && (purpose == null || it.purpose == purpose) }
            .sortedByDescending { it.createdAt }.let { l -> l.firstOrNull { it.status !in setOf("expired", "cancelled") } ?: l.firstOrNull() }

    fun byId(id: String): PayRequest? = state.value.values.flatten().firstOrNull { it.id == id }

    fun put(r: PayRequest, asUid: String? = null) {
        val k = key(asUid)
        val cur = state.value[k].orEmpty().filter { it.id != r.id }
        state.value = state.value + (k to (listOf(r) + cur))
    }

    fun set(list: List<PayRequest>, asUid: String? = null) {
        state.value = state.value + (key(asUid) to list)
        loadedAt[key(asUid)] = System.currentTimeMillis()
    }

    private val loadedBump = HashMap<String, Int>()

    /**
     * ৩০ সেকেন্ডের মধ্যে আনা থাকলে আবার নয়; bump (PayNav.changed) বদলালে একবার নতুন করে — অনেক কার্ড একসাথে
     * ডাকলেও একটাই ডাক।
     */
    suspend fun refresh(asUid: String? = null, bump: Int = 0) {
        val k = key(asUid)
        val at = loadedAt[k] ?: 0L
        if (loadedBump[k] == bump && System.currentTimeMillis() - at < 30_000) return
        loadedAt[k] = System.currentTimeMillis()
        loadedBump[k] = bump
        runCatching { PayApi.mine(asUid) }.onSuccess { set(it.items, asUid) }
    }

    fun clear() {
        state.value = emptyMap()
        loadedAt.clear()
        loadedBump.clear()
    }
}

object PayApi {
    private suspend fun call(action: String, body: JSONObject = JSONObject()): JSONObject = ServerApi.post("/api/community", body.put("action", action))

    private fun JSONArray?.objs(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONObject.dbl(k: String): Double {
        if (!has(k) || isNull(k)) return 0.0
        val v = opt(k)
        if (v is Number) return v.toDouble()
        return InvoiceMath.parse(v?.toString().orEmpty())?.takeIf { it >= 0 } ?: 0.0
    }

    /** কেয়ারগিভার মোডে রোগীর হয়ে (না দিলে চালু থাকা কেয়ারগিভার মোড) */
    private fun JSONObject.asUid(asUid: String?): JSONObject = apply {
        val u = asUid ?: CareMode.asUid.value
        if (!u.isNullOrBlank()) put("asUid", u)
    }

    /** উত্তরে অনুরোধ যেভাবেই আসুক: {request:{…}}, {item:{…}}, {pay:{…}}, অথবা সরাসরি */
    internal fun pick(o: JSONObject): JSONObject = o.optJSONObject("request") ?: o.optJSONObject("payRequest") ?: o.optJSONObject("item") ?: o.optJSONObject("pay") ?: o

    internal fun method(o: JSONObject): PayMethod = PayMethod(
        o.str("method").lowercase(), OnlineChambers.cleanNumber(o.str("number")), o.str("type").ifBlank { "personal" },
        if (o.has("active")) o.optBoolean("active", true) else true,
        listOf("pendingUntil", "activeFrom", "coolOffUntil").firstNotNullOfOrNull { k -> InvoiceApi.time(o, k).takeIf { it > 0 } },
    )

    internal fun parse(o: JSONObject): PayRequest {
        val c = o.optJSONObject("claim")
        val f = o.optJSONObject("refund")
        return PayRequest(
            id = o.str("id").ifBlank { o.str("payId") }, kind = o.str("kind"), bizId = o.str("bizId"), bizName = o.str("bizName"),
            payerUid = o.str("payerUid"), payerName = o.str("payerName").ifBlank { o.str("patientName") },
            refType = o.str("refType"), refId = o.str("refId"), purpose = o.str("purpose"), amount = o.dbl("amount"),
            methods = o.optJSONArray("methods").objs().map(::method),
            status = o.str("status").ifBlank { "open" },
            claim = c?.let { PayClaim(it.str("method").lowercase(), it.str("trxId"), it.str("senderLast4"), InvoiceApi.time(it, "at"), it.str("by")) },
            confirmedAt = InvoiceApi.time(o, "confirmedAt"), confirmedBy = o.str("confirmedBy"),
            rejectReason = o.str("rejectReason"), rejectNote = o.str("rejectNote"),
            refund = f?.let { PayRefund(it.str("trxId"), it.dbl("amount"), InvoiceApi.time(it, "at"), InvoiceApi.time(it, "ackAt")) },
            invoiceId = o.str("invoiceId"), expiresAt = InvoiceApi.time(o, "expiresAt"), createdAt = InvoiceApi.time(o, "createdAt"),
            refundableUntil = InvoiceApi.time(o, "refundableUntil"), demo = o.optBoolean("demo") || o.optBoolean("isDemo"),
            refundDue = o.optJSONObject("refundDue")?.let { PayRefundDue(InvoiceApi.time(it, "at"), it.str("why")) },
        )
    }

    /** pay_request_get-এর উত্তর: {request, side, policy, demoNote} */
    internal fun withPolicy(o: JSONObject): PayRequest =
        parse(pick(o)).copy(policy = o.str("policy"), demoNote = o.str("demoNote"))

    internal fun page(o: JSONObject): PayPage {
        val arr = o.optJSONArray("items") ?: o.optJSONArray("payments") ?: o.optJSONArray("requests")
        val cur = listOf("cursor", "next", "nextCursor").firstNotNullOfOrNull { k -> o.str(k).takeIf { it.isNotBlank() && it != "null" } }
        return PayPage(arr.objs().map(::parse).filter { it.id.isNotBlank() && !PayRules.isBlood(it.refType) }, cur)
    }

    internal fun summary(o: JSONObject): PaySummary {
        val s = o.optJSONObject("summary") ?: o
        fun int(vararg ks: String): Int = ks.firstNotNullOfOrNull { k -> if (s.has(k) && !s.isNull(k)) (s.opt(k) as? Number)?.toInt() ?: (s.opt(k) as? JSONArray)?.length() else null } ?: 0
        return PaySummary(
            listOf("collected", "collectedAmount", "total", "amount").firstNotNullOfOrNull { k -> if (s.has(k)) s.dbl(k) else null } ?: 0.0,
            int("open", "openCount"), int("pending", "pendingClaims", "claimed", "pendingCount"), int("count", "confirmedCount"),
        )
    }

    internal fun settings(o: JSONObject): PaySettings {
        // সার্ভার: chambers [{chamberKey, name, nameBn}] আর advance [{chamberKey, amount, refundableUntilHoursBefore}] আলাদা
        val set = o.optJSONArray("advance").objs().associateBy { it.str("chamberKey") }
        val chambers = o.optJSONArray("chambers").objs()
        val adv = (chambers.map { c ->
            val a = set[c.str("chamberKey")] ?: c.optJSONObject("advance")
            ChamberAdvance(c.str("chamberKey"), c.str("name"), c.str("nameBn"), a?.dbl("amount") ?: 0.0, a?.optInt("refundableUntilHoursBefore", 24) ?: 24)
        } + set.values.filter { a -> chambers.none { it.str("chamberKey") == a.str("chamberKey") } }.map { a ->
            ChamberAdvance(a.str("chamberKey"), amount = a.dbl("amount"), refundableUntilHoursBefore = a.optInt("refundableUntilHoursBefore", 24))
        }).filter { it.chamberKey.isNotBlank() }
        val changes = (o.optJSONArray("history") ?: o.optJSONArray("changes")).objs().map {
            PayMethodChange(it.str("method"), it.str("change"), it.str("oldLast4"), it.str("newLast4"), InvoiceApi.time(it, "at"))
        }
        return PaySettings(
            o.optJSONArray("methods").objs().map(::method).filter { it.method in PayRules.METHODS }, adv, InvoiceApi.time(o, "pendingUntil").takeIf { it > 0 },
            changes, o.optBoolean("frozen"), InvoiceApi.time(o, "frozenSince"),
        )
    }

    internal fun methodsJson(list: List<PayMethod>): JSONArray = JSONArray(list.map {
        JSONObject().put("method", it.method).put("number", OnlineChambers.cleanNumber(it.number)).put("type", it.type).put("active", it.active)
    })

    internal fun claimBody(id: String, method: String, trxId: String, last4: String): JSONObject =
        JSONObject().put("id", id).put("method", method).put("trxId", trxId.trim().uppercase()).put("senderLast4", PayRules.cleanLast4(last4))

    // ---------- রোগী (বা কেয়ারগিভার asUid দিয়ে) ----------

    suspend fun get(id: String, asUid: String? = null): PayRequest = withPolicy(call("pay_request_get", JSONObject().put("id", id).asUid(asUid)))

    suspend fun claim(id: String, method: String, trxId: String, last4: String, asUid: String? = null): PayRequest =
        parse(pick(call("pay_claim", claimBody(id, method, trxId, last4).asUid(asUid))))

    suspend fun refundAck(id: String, asUid: String? = null): PayRequest = parse(pick(call("pay_refund_ack", JSONObject().put("id", id).asUid(asUid))))

    suspend fun report(id: String, reason: String, note: String, asUid: String? = null) {
        call("pay_report", JSONObject().put("id", id).put("reason", reason).apply { if (note.isNotBlank()) put("note", note.trim().take(300)) }.asUid(asUid))
    }

    suspend fun mine(asUid: String? = null, cursor: String? = null): PayPage =
        page(call("my_payments", JSONObject().asUid(asUid).apply { if (cursor != null) put("cursor", cursor) }))

    /** রসিদের বাকি টাকা অনলাইনে (রোগী শুরু করেন) */
    suspend fun dueStart(invoiceId: String, asUid: String? = null): PayRequest = parse(pick(call("pay_due_start", JSONObject().put("invoiceId", invoiceId).asUid(asUid))))

    /** ব্যবসা (সেই অর্ডার/পরীক্ষার) — টাকা সবসময় সার্ভার উৎস থেকে নেয় (অর্ডারের লাইন, বিল, বাকি…), অ্যাপ পাঠায় না */
    suspend fun create(refType: String, refId: String, purpose: String): PayRequest {
        require(!PayRules.isBlood(refType)) { "no_payment_blood" }
        return parse(pick(call("pay_request_create", JSONObject().put("refType", refType).put("refId", refId).put("purpose", purpose))))
    }

    // ---------- ব্যবসা (মালিক; চেম্বারের অনুরোধে ডাক্তারের সহকারীও) ----------

    suspend fun methodsGet(kind: String, bizId: String): PaySettings = settings(call("pay_methods_get", JSONObject().put("kind", kind).put("bizId", bizId)))

    suspend fun methodsSet(kind: String, bizId: String, methods: List<PayMethod>): PaySettings =
        settings(call("pay_methods_set", JSONObject().put("kind", kind).put("bizId", bizId).put("methods", methodsJson(methods))))

    /** ডাক্তার: সামনাসামনি চেম্বারগুলোর অগ্রিম — সার্ভার পুরো তালিকা বদলায়, তাই চালু থাকা সবগুলো (বন্ধগুলো বাদ) */
    internal fun advanceBody(bizId: String, all: List<ChamberAdvance>): JSONObject =
        JSONObject().put("kind", "chamber").put("bizId", bizId).put(
            "advance",
            JSONArray(all.filter { it.on }.map { JSONObject().put("chamberKey", it.chamberKey).put("amount", Math.round(it.amount)).put("refundableUntilHoursBefore", it.refundableUntilHoursBefore) }),
        )

    suspend fun advancesSet(bizId: String, all: List<ChamberAdvance>): PaySettings = settings(call("pay_methods_set", advanceBody(bizId, all)))

    /** {refType, refId, purpose} দিয়ে (অনলাইন চেম্বারের অ্যাপয়েন্টমেন্ট — সার্ভার দরকারে অনুরোধ বানিয়ে দেয়) */
    suspend fun getByRef(refType: String, refId: String, purpose: String, asUid: String? = null): PayRequest =
        withPolicy(call("pay_request_get", JSONObject().put("refType", refType).put("refId", refId).put("purpose", purpose).asUid(asUid)))

    /** "পেয়েছি" — রসিদ দেয় বা আগের রসিদে টাকা যোগ করে */
    internal fun confirmBody(id: String, checks: Set<String>): JSONObject =
        JSONObject().put("id", id).put("checks", JSONObject().apply { PayRules.CONFIRM_CHECKS.forEach { put(it, it in checks) } })

    suspend fun confirm(id: String, checks: Set<String>): Invoice? {
        val o = call("pay_confirm", confirmBody(id, checks))
        return InvoiceApi.invoiceIn(o)
    }

    /** "আমি করিনি": নম্বরগুলো সাথে সাথে আটকে দেওয়া (নতুন অনুরোধ নয়), অন্য লগইন বন্ধ, অ্যাডমিনের কাছে টিকিট (পর্যালোচনা P4) */
    suspend fun freeze(kind: String, bizId: String) {
        call("pay_methods_freeze", JSONObject().put("kind", kind).put("bizId", bizId))
    }

    suspend fun reject(id: String, reason: String, note: String) {
        call("pay_reject", JSONObject().put("id", id).put("reason", reason).apply { if (note.isNotBlank()) put("note", note.trim().take(200)) })
    }

    suspend fun refund(id: String, trxId: String, amount: Double?): PayRequest =
        parse(pick(call("pay_refund", JSONObject().put("id", id).put("trxId", trxId.trim().uppercase()).apply { if (amount != null) put("amount", amount) })))

    suspend fun listBiz(kind: String, bizId: String, status: String? = null, period: String? = null): List<PayRequest> =
        page(call("pay_list_biz", JSONObject().put("kind", kind).put("bizId", bizId).apply { if (status != null) put("status", status); if (period != null) put("period", period) })).items

    suspend fun summary(kind: String, bizId: String, day: String? = null): PaySummary =
        summary(call("pay_summary", JSONObject().put("kind", kind).put("bizId", bizId).apply { if (day != null) put("day", day) }))

    /** মাসের CSV: {filename, mime, count, csv} → (লেখা, ফাইলের নাম) */
    suspend fun exportCsv(kind: String, bizId: String, month: String): Pair<String?, String?> {
        val o = call("pay_export_csv", JSONObject().put("kind", kind).put("bizId", bizId).put("month", month))
        val text = listOf("csv", "text", "data").firstNotNullOfOrNull { k -> o.str(k).takeIf { it.isNotBlank() } }
        return text to o.str("filename").takeIf { it.isNotBlank() }
    }
}

/** পুশ: pay_* — কোথায় খুলবে আর লক-স্ক্রিনের লেখা (টাকা/নম্বর ছাড়া) */
object PayPush {
    val TYPES = setOf("pay_requested", "pay_claimed", "pay_confirmed", "pay_rejected", "pay_refunded", "pay_reminder", "pay_refund_due", "pay_methods_changed")
    /** ব্যবসার কাছে যায় */
    val BIZ_TYPES = setOf("pay_claimed", "pay_reminder", "pay_refund_due", "pay_methods_changed")

    /** ব্যবসার পুশে kind না থাকলে কাজ থেকে (invoice_due হলে অনুরোধ খুলে জানা যায়) */
    fun kindOf(purpose: String?): String? = when (purpose) {
        PayRules.CHAMBER_FEE, PayRules.ADVANCE -> "chamber"
        PayRules.DELIVERY -> "pharmacy"
        PayRules.LAB -> "lab"
        PayRules.AMB_FARE -> "ambulance"
        else -> null
    }

    fun isPay(type: String?): Boolean = type != null && type.startsWith("pay_")

    /** পুরোনো সার্ভারের পুশে cat না থাকলে */
    fun cat(type: String?): String = if (type in BIZ_TYPES) NotifyCats.BUSINESS else NotifyCats.MY_REQUEST

    /** নোটিফিকেশনের শিরোনাম/লেখা: পেমেন্টের হলে সবসময় সাধারণ লেখা */
    fun text(type: String?, title: String, body: String): Pair<String, String> =
        if (isPay(type)) PayText.PUSH_TITLE to PayText.PUSH_BODY else title to body

    fun route(data: Map<String, String>, hasWork: Boolean): NotifyRoute {
        val type = data["type"]
        val id = listOf("payId", "payRequestId", "id").firstNotNullOfOrNull { k -> data[k]?.takeIf { it.isNotBlank() } }
        if (type in BIZ_TYPES) {
            val kind = data["kind"]?.takeIf { it in PayGate.KINDS } ?: kindOf(data["purpose"])
            val tab = when (type) {
                // নম্বর বদলের খবর → সেটিংস (সেখানে "আমি করিনি"); ফেরত পাওনা → তালিকা
                "pay_methods_changed" -> 2
                "pay_refund_due" -> 1
                else -> 0
            }
            return when {
                kind != null -> NotifyRoute.PayBiz(kind, data["bizId"]?.takeIf { it.isNotBlank() }, tab = tab, reqId = id)
                // invoice_due: কোন ব্যবসা, অনুরোধ খুলে জানা যায়
                id != null -> NotifyRoute.PayBiz("", null, tab = tab, reqId = id)
                else -> NotifyRoute.Tab(if (hasWork) Tabs.WORK else Tabs.HOME)
            }
        }
        return NotifyRoute.Pay(id, data["asUid"]?.takeIf { it.isNotBlank() })
    }
}
