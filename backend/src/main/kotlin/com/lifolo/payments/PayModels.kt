package com.lifolo.payments

import com.lifolo.common.ApiException
import com.lifolo.common.Lang
import com.lifolo.invoices.InvoiceMath
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * Lifolo Pay (backend/docs/PAYMENTS.md): the names, limits and checks shared by the payment code. Money goes directly
 * from the patient to the business's own MFS number; Lifolo keeps the request, the TrxID and the sender's last 4 digits.
 */
object Pay {
    val METHODS = listOf("bkash", "nagad", "rocket", "upay")
    const val PERSONAL = "personal"
    val TYPES = listOf(PERSONAL, "merchant")
    val PURPOSES = listOf("chamber_fee", "booking_advance", "delivery", "lab", "ambulance_fare", "invoice_due")
    val REF_TYPES = listOf("appointment", "rx_order", "lab_order", "ambreq", "invoice")
    val STATUSES = listOf("open", "claimed", "confirmed", "rejected", "expired", "refund_claimed", "refunded", "cancelled")
    val REJECT_REASONS = listOf("not_received", "wrong_amount", "wrong_number", "other")
    val REPORT_REASONS = listOf("not_confirmed", "no_refund", "wrong_number", "asked_more", "asked_other_number", "scam", "other")
    val KINDS = listOf("pharmacy", "lab", "chamber", "ambulance")

    /** a request that may still be paid (claimed again after a rejection) */
    val LIVE = setOf("open", "claimed", "rejected")

    /** which source a purpose is about */
    val REF_OF: Map<String, Set<String>> = mapOf(
        "chamber_fee" to setOf("appointment"),
        "booking_advance" to setOf("appointment"),
        "delivery" to setOf("rx_order"),
        "lab" to setOf("lab_order", "invoice"),
        "ambulance_fare" to setOf("ambreq"),
        "invoice_due" to setOf("invoice"),
    )

    const val MIN = 60_000L
    const val HOUR = 60 * MIN
    const val DAY = 24 * HOUR

    /** a changed number reaches new requests after this */
    const val COOL_MS = DAY

    const val CLAIMS_PER_REQUEST_HOUR = 5
    const val CLAIMS_PER_USER_DAY = 20

    /** this many rejected claims in 30 days → one open claim at a time + an admin flag */
    const val REJECTS_FOR_LIMIT = 3
    const val REJECT_WINDOW_MS = 30 * DAY

    /** a claim nobody confirmed: reminder to the business after 24 h, admin flag after 72 h */
    const val REMIND_AFTER_MS = DAY
    const val FLAG_AFTER_MS = 3 * DAY

    /** P3: a business may still confirm a claim it rejected by mistake within this time */
    const val RECONFIRM_MS = 7 * DAY

    /** P11: a refund owed this long stops the business from asking for new payments */
    const val REFUND_DUE_PAUSE_MS = 7 * DAY

    /** P11: a confirmed delivery prepay not delivered in this time is owed back */
    const val DELIVERY_DUE_MS = 5 * DAY

    /** P11: pay_report answer time (admin SLA flag) */
    const val REPORT_SLA_MS = 3 * DAY

    const val MAX_METHODS = 4
    const val PAGE = 30
    val MAX_AMOUNT: BigDecimal = BigDecimal("500000")

    /** how long an open request of a purpose may be paid (null = no expiry; booking_advance: the slot time) */
    fun expiryMs(purpose: String): Long? = when (purpose) {
        "delivery", "lab" -> DAY
        "ambulance_fare", "invoice_due" -> 7 * DAY
        else -> null
    }

    /** no payment of any kind in the blood context (donation is voluntary) */
    fun isBlood(refType: String?, purpose: String?): Boolean = listOf(refType, purpose).any { v ->
        val t = v?.lowercase()?.trim() ?: return@any false
        t.contains("blood") || t in setOf("donation", "donor", "emergency", "request", "bloodbank", "blood_request")
    }

    /** the TrxID as typed: spaces/dashes dropped, upper case */
    fun normTrx(raw: String?): String? = raw?.filter { !it.isWhitespace() && it != '-' }?.uppercase()?.takeIf { it.isNotEmpty() }

    /** bKash: exactly 10 letters/digits; Nagad, Rocket, Upay: 6–20 */
    fun trxOk(method: String, trx: String): Boolean =
        if (method == "bkash") Regex("^[A-Z0-9]{10}$").matches(trx) else Regex("^[A-Z0-9]{6,20}$").matches(trx)

    /** 4 digits (Bangla digits too) → ASCII, else null */
    fun last4(raw: String?): String? {
        val t = raw?.trim()?.map { c -> if (c in '০'..'৯') '0' + (c - '০') else c }?.joinToString("") ?: return null
        return t.takeIf { Regex("^[0-9]{4}$").matches(it) }
    }

    /** the receipt's payment method for an MFS method (receipts know bkash/nagad; Rocket and Upay are "other") */
    fun invoiceMethod(method: String?): String = when (method) {
        "bkash", "nagad" -> method
        null -> "other"
        else -> "other"
    }

    /** the receipt's payment ref: the TrxID (Rocket/Upay prefixed with the method) */
    fun invoiceRef(method: String?, trx: String?): String? = when {
        trx == null -> null
        method == "rocket" || method == "upay" -> "$method:$trx".take(40)
        else -> trx.take(40)
    }

    fun num(v: BigDecimal?): Number? = v?.let { InvoiceMath.num(it) }

    fun err(lang: String?, status: Int, en: String, bn: String, code: String? = null) = ApiException(status, Lang.tr(lang, en, bn), code)

    fun notFound(lang: String?) = err(lang, 404, "Not found", "পাওয়া যায়নি")

    fun bad(lang: String?, en: String, bn: String, code: String? = null) = err(lang, 400, en, bn, code)

    fun state(lang: String?) = err(lang, 409, "This can't be changed now", "এখন এটা বদলানো যায় না", "state")

    fun blood(lang: String?) = err(lang, 400, "No payments for blood — donation is voluntary", "রক্তের জন্য কোনো পেমেন্ট নেই — রক্তদান স্বেচ্ছায়", "no_payment_blood") // bn-ok
}

/** P11: the refund policy shown before paying (pay_request_get `policy`) */
object PayPolicy {
    fun text(r: PayReq, lang: String?): String {
        val base = Lang.pick(
            lang,
            "The money goes straight to ${r.bizName}'s own number; Lifolo does not take or keep money. Send only to the number shown here. Lifolo never asks for your PIN, OTP or money.",
            "টাকা সরাসরি ${r.bizName}-এর নিজের নম্বরে যায়; লাইফলো টাকা গ্রহণ বা সংরক্ষণ করে না। এখানে দেখানো নম্বর ছাড়া অন্য কোথাও টাকা পাঠাবেন না। লাইফলো কখনো PIN, OTP বা টাকা চায় না।", // bn-ok
        )
        val refund = when (r.purpose) {
            "booking_advance" -> {
                val h = r.refundableUntil?.let { until -> r.expiresAt?.let { ((it - until) / Pay.HOUR).toInt() } } ?: 0
                Lang.pick(
                    lang,
                    "If the doctor cancels, the full amount is refunded; if you cancel at least $h hours before, it is refunded too.",
                    "ডাক্তার বাতিল করলে পুরো টাকা ফেরত; আপনি অন্তত $h ঘণ্টা আগে বাতিল করলেও ফেরত।", // bn-ok
                )
            }
            "delivery", "lab" -> Lang.pick(
                lang,
                "If the order is cancelled or not delivered, the business must send the money back.",
                "অর্ডার বাতিল হলে বা পৌঁছানো না হলে প্রতিষ্ঠানকে টাকা ফেরত দিতে হবে।", // bn-ok
            )
            "chamber_fee" -> Lang.pick(
                lang,
                "If the doctor cancels or does not join, the doctor must refund the fee.",
                "ডাক্তার বাতিল করলে বা যোগ না দিলে ফি ফেরত দিতে হবে।", // bn-ok
            )
            else -> ""
        }
        val text = if (refund.isEmpty()) base else "$base $refund"
        return if (lang == "en") text else Lang.bnNumbers(text)
    }
}

/** the push text (lock screen: no amounts, numbers or names — PAYMENTS.md) */
object PayTexts {
    const val TITLE_EN = "Payment update — Lifolo"
    const val TITLE_BN = "পেমেন্ট আপডেট — লাইফলো" // bn-ok

    fun body(type: String, lang: String?): String = when (type) {
        "pay_requested" -> Lang.pick(lang, "A new payment request", "নতুন পেমেন্টের অনুরোধ এসেছে") // bn-ok
        "pay_claimed" -> Lang.pick(lang, "A patient says the money was sent — please check", "রোগী টাকা পাঠিয়েছেন বলে জানিয়েছেন — যাচাই করুন") // bn-ok
        "pay_confirmed" -> Lang.pick(lang, "The payment was received", "টাকা পাওয়া নিশ্চিত হয়েছে") // bn-ok
        "pay_rejected" -> Lang.pick(lang, "The business has not received the money", "টাকা পাওয়া যায়নি বলে জানানো হয়েছে") // bn-ok
        "pay_refunded" -> Lang.pick(lang, "Your money was sent back", "টাকা ফেরত পাঠানো হয়েছে") // bn-ok
        "pay_reminder" -> Lang.pick(lang, "A payment is waiting for your check", "যাচাইয়ের অপেক্ষায় পেমেন্ট আছে") // bn-ok
        "pay_refund_due" -> Lang.pick(lang, "A refund is owed — please send the money back", "টাকা ফেরত পাওনা আছে — ফেরত পাঠান") // bn-ok
        "pay_methods_changed" -> Lang.pick(lang, "Your payment numbers were changed", "আপনার পেমেন্ট নম্বর বদলানো হয়েছে") // bn-ok
        else -> ""
    }
}

/** the payments' clock (tests move it) */
@Component
class PayClock {
    @Volatile
    var fixed: Long? = null

    fun now(): Long = fixed ?: System.currentTimeMillis()
}

/** one pay request (pay_requests row) */
data class PayReq(
    val id: String,
    val kind: String,
    val bizId: String,
    val ownerUid: String?,
    val bizName: String,
    val payerUid: String?,
    val refType: String,
    val refId: String,
    val purpose: String,
    val amount: BigDecimal,
    val methods: List<Map<String, String>>,
    val status: String,
    val claimMethod: String?,
    val claimTrx: String?,
    val claimLast4: String?,
    val claimedAt: Long?,
    val claimedBy: String?,
    val confirmedAt: Long?,
    val confirmedBy: String?,
    val rejectReason: String?,
    val rejectNote: String?,
    val rejectedAt: Long?,
    val refundTrx: String?,
    val refundAmount: BigDecimal?,
    val refundedAt: Long?,
    val refundAckAt: Long?,
    val refundDueAt: Long?,
    val refundDueWhy: String?,
    val invoiceId: String?,
    val invoiceIssued: Boolean,
    val refundableUntil: Long?,
    val expiresAt: Long?,
    val createdAt: Long,
    val remindedAt: Long?,
    val flaggedAt: Long?,
    val isDemo: Boolean,
) {
    /** the contract object (PAYMENTS.md "Pay request") */
    fun toMap(): LinkedHashMap<String, Any?> {
        val m = linkedMapOf<String, Any?>(
            "id" to id, "kind" to kind, "bizId" to bizId, "bizName" to bizName, "payerUid" to payerUid,
            "refType" to refType, "refId" to refId, "purpose" to purpose, "amount" to Pay.num(amount),
            "methods" to methods, "status" to status,
        )
        if (claimedAt != null) {
            // `by` = who said "পাঠিয়েছি" (a caregiver pays from their own wallet — refunds go back to that sender)
            m["claim"] = linkedMapOf("method" to claimMethod, "trxId" to claimTrx, "senderLast4" to claimLast4, "at" to claimedAt, "by" to claimedBy)
        }
        if (confirmedAt != null) {
            m["confirmedAt"] = confirmedAt
            m["confirmedBy"] = confirmedBy
        }
        if (rejectReason != null) {
            m["rejectReason"] = rejectReason
            m["rejectNote"] = rejectNote
            m["rejectedAt"] = rejectedAt
        }
        if (refundedAt != null) {
            m["refund"] = linkedMapOf("trxId" to refundTrx, "amount" to Pay.num(refundAmount), "at" to refundedAt, "ackAt" to refundAckAt)
        }
        if (refundDueAt != null && status == "confirmed") m["refundDue"] = linkedMapOf("at" to refundDueAt, "why" to refundDueWhy)
        if (invoiceId != null) m["invoiceId"] = invoiceId
        if (refundableUntil != null) m["refundableUntil"] = refundableUntil
        m["expiresAt"] = expiresAt
        m["createdAt"] = createdAt
        if (isDemo) m["demo"] = true
        return m
    }
}
