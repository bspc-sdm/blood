package com.lifolo.payments

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lifolo.auth.AuthUser
import com.lifolo.common.Body
import com.lifolo.common.Ids
import com.lifolo.common.Lang
import com.lifolo.common.Rules
import com.lifolo.common.Sql
import com.lifolo.contact.ContactPolicy
import com.lifolo.contracts.ProviderAssistants
import com.lifolo.demo.DemoWorld
import com.lifolo.invoices.Customer
import com.lifolo.invoices.FeeIn
import com.lifolo.invoices.Invoice
import com.lifolo.invoices.InvoiceCreators
import com.lifolo.invoices.InvoiceFeatures
import com.lifolo.invoices.InvoiceMath
import com.lifolo.invoices.InvoiceService
import com.lifolo.invoices.IssueCmd
import com.lifolo.invoices.LineIn
import com.lifolo.invoices.PaymentIn
import com.lifolo.notify.NotifyCats
import com.lifolo.notify.PushService
import com.lifolo.notify.PushText
import com.lifolo.notify.SseHub
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.LocalDate
import java.time.ZoneId

/**
 * Lifolo Pay (backend/docs/PAYMENTS.md): the pay request lifecycle open → claimed → confirmed (→ refund_claimed →
 * refunded), rejected (claim again, or the business re-confirms within 7 days), expired, cancelled. A request belongs to
 * one business (kind + bizId) and one payer; the methods are a snapshot of the business's matured numbers when it was
 * made; the amount always comes from the stored source. Confirming issues the receipt (InvoiceService) or adds the
 * payment to the existing one. The online chamber fee (chamber_fee) also keeps the appointment's own pay columns in
 * step, so the old `appt_pay_*` actions and every appointment view work as before. "claimed" never counts as paid.
 */
@Service
class PayService(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
    private val methods: PayMethodStore,
    private val gate: PayGate,
    private val invoices: InvoiceService,
    private val creators: InvoiceCreators,
    private val invFeatures: InvoiceFeatures,
    private val push: PushService,
    private val sse: SseHub,
    private val contact: ContactPolicy,
    private val assistants: ProviderAssistants,
    val clock: PayClock,
    txm: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(txm)
    private val methodsType = object : TypeReference<List<Map<String, String>>>() {}

    companion object {
        val DHAKA: ZoneId = ZoneId.of("Asia/Dhaka")

        /** the slot of an appointment (visit day + "HH:MM", Dhaka); no start time = the end of that day */
        fun slotMs(date: LocalDate, start: String?): Long {
            val m = Regex("^(\\d{2}):(\\d{2})$").find(start ?: "")
            return if (m == null) {
                date.plusDays(1).atStartOfDay(DHAKA).toInstant().toEpochMilli()
            } else {
                date.atTime(m.groupValues[1].toInt().coerceIn(0, 23), m.groupValues[2].toInt().coerceIn(0, 59)).atZone(DHAKA).toInstant().toEpochMilli()
            }
        }
    }

    // ------------------------------------------------------------------ read

    private fun map(rs: ResultSet): PayReq = PayReq(
        id = rs.getString("id"), kind = rs.getString("kind"), bizId = rs.getString("biz_id"), ownerUid = rs.getString("owner_uid"),
        bizName = rs.getString("biz_name"), payerUid = rs.getString("payer_uid"), refType = rs.getString("ref_type"), refId = rs.getString("ref_id"),
        purpose = rs.getString("purpose"), amount = rs.getBigDecimal("amount"),
        methods = runCatching { mapper.readValue(rs.getString("methods"), methodsType) }.getOrDefault(emptyList()),
        status = rs.getString("status"), claimMethod = rs.getString("claim_method"), claimTrx = rs.getString("claim_trx"),
        claimLast4 = rs.getString("claim_last4"), claimedAt = rs.getTimestamp("claimed_at")?.time, claimedBy = rs.getString("claimed_by"),
        confirmedAt = rs.getTimestamp("confirmed_at")?.time, confirmedBy = rs.getString("confirmed_by"), rejectReason = rs.getString("reject_reason"),
        rejectNote = rs.getString("reject_note"), rejectedAt = rs.getTimestamp("rejected_at")?.time, refundTrx = rs.getString("refund_trx"),
        refundAmount = rs.getBigDecimal("refund_amount"), refundedAt = rs.getTimestamp("refunded_at")?.time,
        refundAckAt = rs.getTimestamp("refund_ack_at")?.time, refundDueAt = rs.getTimestamp("refund_due_at")?.time,
        refundDueWhy = rs.getString("refund_due_why"), invoiceId = rs.getString("invoice_id"),
        invoiceIssued = rs.getBoolean("invoice_issued"), refundableUntil = rs.getTimestamp("refundable_until")?.time,
        expiresAt = rs.getTimestamp("expires_at")?.time, createdAt = rs.getTimestamp("created_at").time,
        remindedAt = rs.getTimestamp("reminded_at")?.time, flaggedAt = rs.getTimestamp("flagged_at")?.time, isDemo = rs.getBoolean("is_demo"),
    )

    fun query(where: String, vararg args: Any?): List<PayReq> = jdbc.query("SELECT * FROM pay_requests WHERE $where", { rs, _ -> map(rs) }, *args)

    fun get(id: String?): PayReq? = if (id == null || !Rules.isValidId(id)) null else query("id = ?", id).firstOrNull()

    private fun lock(id: String): PayReq? = query("id = ? FOR UPDATE", id).firstOrNull()

    fun load(lang: String?, id: String?): PayReq = get(id) ?: throw Pay.notFound(lang)

    /** the latest request of a source (and purpose) */
    fun latestFor(refType: String, refId: String, purpose: String? = null): PayReq? =
        if (purpose == null) {
            query("ref_type = ? AND ref_id = ? ORDER BY created_at DESC, id DESC LIMIT 1", refType, refId).firstOrNull()
        } else {
            query("ref_type = ? AND ref_id = ? AND purpose = ? ORDER BY created_at DESC, id DESC LIMIT 1", refType, refId, purpose).firstOrNull()
        }

    /**
     * "payer" (the patient, or an active caregiver acting for them — CareAccess checks the link on every call),
     * "owner"/"assistant" (the business, read from the stored row; a doctor's CURRENT assistant), or null. Demo and
     * real never meet.
     */
    fun sideOf(user: AuthUser, r: PayReq): String? {
        val uid = user.uid ?: return null
        val world = r.payerUid ?: r.ownerUid
        if (world != null && !DemoWorld.sameWorld(uid, world)) return null
        if (r.payerUid != null && r.payerUid == uid) return "payer"
        if (user.patientSideOnly) return null
        return invoices.roleOf(user, r.kind, r.bizId, r.ownerUid)
    }

    fun requirePayer(lang: String?, user: AuthUser, r: PayReq) {
        if (sideOf(user, r) != "payer") throw Pay.notFound(lang)
    }

    fun requireBiz(lang: String?, user: AuthUser, r: PayReq): String {
        val side = sideOf(user, r)
        if (side != "owner" && side != "assistant") throw Pay.notFound(lang)
        return side
    }

    // ------------------------------------------------------------------ create

    /** where a request comes from: the business, the payer, the amount (from the stored source), the expiry */
    private data class Src(
        val kind: String,
        val bizId: String,
        val payer: String?,
        val amount: BigDecimal?,
        val expiresAt: Long?,
        val refundableUntil: Long? = null,
        /** chamber_fee: the number the booking was made with (not the business's current list) */
        val methods: List<Map<String, String>>? = null,
    )

    private fun one(sql: String, vararg args: Any?): Map<String, Any?>? = jdbc.queryForList(sql, *args).firstOrNull()

    private fun lines(text: Any?): BigDecimal? {
        val ls = text?.toString()?.let { InvoiceMath.linesFromJson(it) } ?: return null
        if (ls.isEmpty()) return null
        return InvoiceMath.m2(ls.fold(BigDecimal.ZERO) { a, l -> a + InvoiceMath.m2(l.qty.multiply(l.price)) - l.disc })
    }

    private fun nothingDue(lang: String?) = Pay.err(lang, 409, "Nothing is due", "কোনো বাকি নেই", "nothing_due") // bn-ok

    private fun resolve(lang: String?, refType: String, refId: String, purpose: String, now: Long): Src {
        when (refType) {
            "appointment" -> {
                val a = one("SELECT * FROM appointments WHERE id = ?", refId) ?: throw Pay.notFound(lang)
                val doctor = a["provider_uid"] as String
                val status = a["status"] as String
                if (purpose == "chamber_fee") {
                    if (a["online"] != true || a["pay_state"] == null) throw Pay.err(lang, 409, "No fee for this appointment", "এই অ্যাপয়েন্টমেন্টে ফি লাগে না", "no_fee") // bn-ok
                    if (status !in setOf("pending", "confirmed")) throw Pay.state(lang)
                    val amount = InvoiceMath.takaOf(a["fee"] as String?) ?: throw Pay.err(lang, 409, "No fee for this appointment", "এই অ্যাপয়েন্টমেন্টে ফি লাগে না", "no_fee") // bn-ok
                    val no = (a["bkash_number"] as String?)?.takeIf { Regex("^01[0-9]{9}$").matches(it) }
                    return Src("chamber", doctor, a["patient_uid"] as String, amount, null, methods = listOfNotNull(no?.let { linkedMapOf("method" to "bkash", "number" to it, "type" to Pay.PERSONAL) }))
                }
                // booking_advance: an in-person chamber with the doctor's advance fee on
                if (a["online"] == true) throw Pay.err(lang, 409, "Online appointments pay the fee instead", "অনলাইন অ্যাপয়েন্টমেন্টে ফি আলাদা দিতে হয়", "online") // bn-ok
                if (status !in setOf("pending", "confirmed")) throw Pay.state(lang)
                val adv = methods.advanceOf(doctor, (a["chamber_key"] as String?) ?: "")
                    ?: throw Pay.err(lang, 409, "This chamber takes no advance", "এই চেম্বারে অগ্রিম ফি নেই", "no_advance") // bn-ok
                val slot = slotMs((a["visit_date"] as java.sql.Date).toLocalDate(), a["start_time"] as String?)
                if (slot <= now) throw Pay.err(lang, 409, "The appointment time has passed", "অ্যাপয়েন্টমেন্টের সময় পার হয়ে গেছে", "too_late") // bn-ok
                return Src("chamber", doctor, a["patient_uid"] as String, BigDecimal(adv.first), slot, slot - adv.second * Pay.HOUR)
            }
            "rx_order" -> {
                val o = one("SELECT * FROM rx_orders WHERE id = ?", refId) ?: throw Pay.notFound(lang)
                if (o["mode"] != "online") throw Pay.err(lang, 409, "Only home delivery orders are paid ahead", "শুধু হোম ডেলিভারির অর্ডারে আগে টাকা দেওয়া যায়", "not_delivery") // bn-ok
                if (o["status"] !in setOf("accepted", "out")) throw Pay.state(lang)
                val live = invoices.liveFor("rx_order", refId)
                val fee = InvoiceMath.takaOf(o["delivery_fee"] as String?) ?: BigDecimal.ZERO
                val total = live?.due ?: lines(o["invoice_lines"])?.let { InvoiceMath.m2(it + fee) }
                return Src("pharmacy", o["pharmacy_uid"] as String, o["patient_uid"] as String, total, now + Pay.DAY)
            }
            "lab_order" -> {
                val o = one("SELECT * FROM lab_orders WHERE id = ?", refId) ?: throw Pay.notFound(lang)
                if (o["status"] == "cancelled" || o["status"] == "reported") throw Pay.state(lang)
                if (o["home_status"] != "confirmed") throw Pay.err(lang, 409, "Confirm the home collection first", "আগে হোম কালেকশন নিশ্চিত করুন", "not_confirmed") // bn-ok
                val live = invoices.liveFor("lab_order", refId)
                val total = live?.due ?: run {
                    val (sum, n) = jdbc.queryForObject(
                        "SELECT coalesce(sum(price), 0)::int, count(*)::int FROM lab_order_price_items WHERE order_id = ? AND price IS NOT NULL",
                        { rs, _ -> rs.getInt(1) to rs.getInt(2) }, refId,
                    )!!
                    if (n == 0) {
                        null
                    } else {
                        val sub = BigDecimal(sum)
                        val pct = (o["price_discount_pct"] as Number?)?.toInt()?.takeIf { it in 1..100 } ?: 0
                        val disc = InvoiceMath.m2(sub.multiply(BigDecimal(pct)).divide(BigDecimal(100)))
                        InvoiceMath.m2(sub - disc + BigDecimal((o["home_fee"] as Number?)?.toInt() ?: 0))
                    }
                }
                return Src("lab", o["lab_uid"] as String, o["patient_uid"] as String?, total, now + Pay.DAY)
            }
            "invoice" -> {
                val inv = invoices.load(refId) ?: throw Pay.notFound(lang)
                if (purpose == "lab" && inv.kind != "lab") throw Pay.bad(lang, "Check the request", "অনুরোধ দেখে নিন", "ref_type") // bn-ok
                if (inv.status != "issued") throw Pay.state(lang)
                if (inv.due.signum() <= 0) throw nothingDue(lang)
                return Src(inv.kind, inv.bizId, inv.patientUid, inv.due, now + (Pay.expiryMs(purpose) ?: Pay.DAY))
            }
            "ambreq" -> {
                val x = one("SELECT uid, status FROM ambulance_requests WHERE id = ?", refId) ?: throw Pay.notFound(lang)
                // the fare only after the trip is finished (ambreq_finish gave the receipt) — never before, never required
                val inv = invoices.liveFor("ambreq", refId)
                if (x["status"] != "done" || inv == null) {
                    throw Pay.err(lang, 409, "The fare can be paid after the trip is finished", "যাত্রা শেষ হওয়ার পরেই ভাড়া দেওয়া যায়", "not_finished") // bn-ok
                }
                if (inv.due.signum() <= 0) throw nothingDue(lang)
                return Src("ambulance", inv.bizId, x["uid"] as String, inv.due, now + 7 * Pay.DAY)
            }
        }
        throw Pay.notFound(lang)
    }

    /** the business (kind, bizId) and the payer of a source, from the stored row; null = no such source */
    private fun refParties(refType: String, refId: String): Triple<String, String, String?>? = when (refType) {
        "appointment" -> one("SELECT provider_uid, patient_uid FROM appointments WHERE id = ?", refId)?.let { Triple("chamber", it["provider_uid"] as String, it["patient_uid"] as String?) }
        "rx_order" -> one("SELECT pharmacy_uid, patient_uid FROM rx_orders WHERE id = ?", refId)?.let { Triple("pharmacy", it["pharmacy_uid"] as String, it["patient_uid"] as String?) }
        "lab_order" -> one("SELECT lab_uid, patient_uid FROM lab_orders WHERE id = ?", refId)?.let { Triple("lab", it["lab_uid"] as String, it["patient_uid"] as String?) }
        "invoice" -> invoices.load(refId)?.let { Triple(it.kind, it.bizId, it.patientUid) }
        "ambreq" -> one("SELECT ambulance_id, uid FROM ambulance_requests WHERE id = ?", refId)?.let { r -> (r["ambulance_id"] as String?)?.let { Triple("ambulance", it, r["uid"] as String?) } }
        else -> null
    }

    /** P11: a confirmed payment owed back for over 7 days */
    fun refundOverdue(kind: String, bizId: String, now: Long): Boolean = jdbc.queryForList(
        "SELECT 1 FROM pay_requests WHERE kind = ? AND biz_id = ? AND status = 'confirmed' AND refund_due_at IS NOT NULL AND refund_due_at < ? LIMIT 1",
        kind, bizId, Sql.ts(now - Pay.REFUND_DUE_PAUSE_MS),
    ).isNotEmpty()

    /**
     * pay_request_create {refType, refId, purpose} — by the business ([actor] owner, or a doctor's assistant), by the
     * payer ([payerStart]: pay_due_start), or by the server ([system]: a booking, a finished trip). The amount comes from
     * the source. Returns the request and whether it is new (an open one for the same source and purpose is returned).
     */
    fun create(
        lang: String?,
        actor: AuthUser?,
        callerUid: String?,
        refType: String?,
        refId: String?,
        purpose: String?,
        now: Long,
        system: Boolean = false,
        payerStart: Boolean = false,
    ): Pair<PayReq, Boolean> {
        if (Pay.isBlood(refType, purpose)) throw Pay.blood(lang)
        if (purpose == null || purpose !in Pay.PURPOSES) throw Pay.bad(lang, "Choose what the payment is for", "কিসের পেমেন্ট বেছে নিন", "purpose") // bn-ok
        if (refType == null || refType !in (Pay.REF_OF[purpose] ?: emptySet())) throw Pay.bad(lang, "Check the request", "অনুরোধ দেখে নিন", "ref_type") // bn-ok
        if (refId == null || !Rules.isValidId(refId)) throw Pay.notFound(lang)
        // P6: who may ask comes first (from the stored source) — a stranger learns nothing about its state
        if (!system) {
            val a = actor ?: throw Pay.notFound(lang)
            val (kind, bizId, payerOf) = refParties(refType, refId) ?: throw Pay.notFound(lang)
            if (payerStart) {
                if (payerOf == null || a.uid != payerOf) throw Pay.notFound(lang)
            } else {
                val b = invoices.biz(kind, bizId) ?: throw Pay.notFound(lang)
                if (a.patientSideOnly || invoices.roleOf(a, b.kind, b.id, b.ownerUid) == null) throw Pay.notFound(lang)
            }
        }
        val src = resolve(lang, refType, refId, purpose, now)
        val biz = invoices.biz(src.kind, src.bizId) ?: throw Pay.notFound(lang)
        // P13: the switch of the BUSINESS decides (a pilot business's patients can pay whoever they are)
        gate.requirePurpose(lang, biz.ownerUid, purpose)
        // a sanctioned (or no longer approved) business cannot ask for money
        invoices.requireCanIssue(lang, biz)
        val payer = src.payer ?: throw Pay.err(lang, 409, "This has no Lifolo patient to pay", "এর সাথে কোনো লাইফলো রোগী নেই", "no_payer") // bn-ok
        // P8: demo and real never mix; nobody deactivated, being deleted or disabled is asked to pay
        if (!DemoWorld.sameWorld(payer, biz.ownerUid) || !contact.reachable(payer)) throw Pay.notFound(lang)
        query("ref_type = ? AND ref_id = ? AND purpose = ? AND status IN ('open', 'claimed', 'rejected') LIMIT 1", refType, refId, purpose)
            .firstOrNull()?.let { return it to false }
        if (methods.frozen(biz.kind, biz.id)) {
            throw Pay.err(lang, 409, "Payment numbers are frozen — the Lifolo team will contact you", "পেমেন্ট নম্বর বন্ধ রাখা হয়েছে — লাইফলো টিম যোগাযোগ করবে", "pay_frozen") // bn-ok
        }
        // P11: a refund owed for over 7 days stops new payment requests until it is sent (or an admin resolves it)
        if (refundOverdue(biz.kind, biz.id, now)) throw Pay.err(lang, 409, "Send the refunds you owe first", "আগে পাওনা টাকা ফেরত দিন", "pay_paused") // bn-ok
        // a source paid once (a booking advance, a prepaid order) is not asked again; a receipt's due may be paid in parts
        if (refType != "invoice" && refType != "ambreq" &&
            query("ref_type = ? AND ref_id = ? AND purpose = ? AND status IN ('confirmed', 'refund_claimed') LIMIT 1", refType, refId, purpose).isNotEmpty()
        ) {
            throw Pay.err(lang, 409, "This is already paid", "এটা আগেই পরিশোধ হয়েছে", "already_paid") // bn-ok
        }
        val snap = src.methods ?: methods.snapshot(biz.kind, biz.id, now)
        if (snap.isEmpty() && purpose != "chamber_fee") {
            throw Pay.err(lang, 409, "This business takes no online payment yet — pay in cash", "এই প্রতিষ্ঠান এখনো অনলাইন পেমেন্ট নিচ্ছে না — নগদে দিন", "no_methods") // bn-ok
        }
        // P6: the amount always comes from the stored source (order, bill, fare, fee, advance) — never from a client
        val amount = src.amount ?: throw Pay.err(lang, 409, "Add the priced items first", "আগে দামসহ তালিকা দিন", "no_amount") // bn-ok
        if (amount.signum() <= 0) throw nothingDue(lang)
        if (amount > Pay.MAX_AMOUNT) throw Pay.err(lang, 409, "Too much for an online payment — take it in cash", "অনলাইন পেমেন্টের জন্য অনেক বেশি — নগদে নিন", "amount_cap") // bn-ok
        val id = Ids.newId()
        try {
            jdbc.update(
                """
                INSERT INTO pay_requests (id, kind, biz_id, owner_uid, biz_name, payer_uid, ref_type, ref_id, purpose, amount, methods, status,
                  refundable_until, expires_at, created_by, created_at, updated_at, is_demo)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, 'open', ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                id, biz.kind, biz.id, biz.ownerUid, biz.name.take(100), payer, refType, refId, purpose, InvoiceMath.m2(amount),
                mapper.writeValueAsString(snap), src.refundableUntil?.let { Sql.ts(it) }, src.expiresAt?.let { Sql.ts(it) },
                if (system) null else actor?.uid, Sql.ts(now), Sql.ts(now), DemoWorld.isDemoUid(biz.ownerUid),
            )
        } catch (e: DuplicateKeyException) {
            // two at once: the other one won
            return (query("ref_type = ? AND ref_id = ? AND purpose = ? AND status IN ('open', 'claimed', 'rejected') LIMIT 1", refType, refId, purpose).firstOrNull()
                ?: throw Pay.state(lang)) to false
        }
        event(id, "create", if (system) null else callerUid, payer, null, "open", null, null, now)
        val r = get(id)!!
        notifyPayer(r, "pay_requested")
        return r to true
    }

    /**
     * The chamber_fee request of an online appointment, made from the appointment when it has none yet (bookings from
     * before this layer, or one the migration could not convert). Null when the appointment has nothing to pay (or no
     * fee in numbers) — the old pay columns then work alone.
     */
    fun ensureChamberFee(appointmentId: String, now: Long): PayReq? {
        try {
            latestFor("appointment", appointmentId, "chamber_fee")?.takeIf { it.status != "cancelled" && it.status != "expired" }?.let { return it }
            val a = one("SELECT * FROM appointments WHERE id = ?", appointmentId) ?: return null
            if (a["online"] != true) return null
            val pay = a["pay_state"] as String? ?: return null
            val amount = InvoiceMath.takaOf(a["fee"] as String?) ?: return null
            val doctor = a["provider_uid"] as String
            val no = (a["bkash_number"] as String?)?.takeIf { Regex("^01[0-9]{9}$").matches(it) }
            val snap = listOfNotNull(no?.let { linkedMapOf("method" to "bkash", "number" to it, "type" to Pay.PERSONAL) })
            val ref = (a["pay_ref"] as String?)?.takeIf { Regex("^[A-Z0-9]{6,20}$").matches(it) }
            val status = when (pay) {
                "claimed" -> "claimed"
                "paid" -> "confirmed"
                else -> "open"
            }
            val name = invoices.biz("chamber", doctor)?.name ?: (a["provider_name"] as String? ?: "")
            val id = Ids.newId()
            val n = jdbc.update(
                """
                INSERT INTO pay_requests (id, kind, biz_id, owner_uid, biz_name, payer_uid, ref_type, ref_id, purpose, amount, methods, status,
                  claim_method, claim_trx, claimed_at, confirmed_at, confirmed_by, created_at, updated_at, is_demo)
                VALUES (?, 'chamber', ?, ?, ?, ?, 'appointment', ?, 'chamber_fee', ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """.trimIndent(),
                id, doctor, doctor, name.take(100), a["patient_uid"], appointmentId, amount, mapper.writeValueAsString(snap), status,
                if (ref != null) "bkash" else null, ref,
                if (status == "claimed") (a["pay_claimed_at"] as java.sql.Timestamp? ?: Sql.ts(now)) else a["pay_claimed_at"],
                if (status == "confirmed") (a["pay_paid_at"] as java.sql.Timestamp? ?: Sql.ts(now)) else null,
                if (status == "confirmed") a["pay_paid_by"] else null,
                Sql.ts(now), Sql.ts(now), DemoWorld.isDemoUid(doctor),
            )
            if (n > 0) event(id, "create", null, a["patient_uid"] as String?, null, status, ref, null, now)
            return latestFor("appointment", appointmentId, "chamber_fee")
        } catch (e: Exception) {
            log.warn("chamber fee request for {} failed: {}", appointmentId, e.message)
            return null
        }
    }

    // ------------------------------------------------------------------ claim

    private fun rate(lang: String?) =
        Pay.err(lang, 429, "Too many tries — try again in an hour", "অনেকবার চেষ্টা হয়েছে — এক ঘণ্টা পরে আবার চেষ্টা করুন", "pay_claim_rate") // bn-ok

    private fun refUsed(lang: String?) =
        Pay.err(lang, 409, "This TrxID was already used", "এই TrxID আগেই ব্যবহার হয়েছে — বিকাশ মেসেজ থেকে ঠিক TrxID দিন", "pay_ref_used") // bn-ok

    /** P1: someone else's claim already has this TrxID here — never say whose; the payer can report */
    private fun refUsedReport(lang: String?) = Pay.err(
        lang, 409, "This TrxID was already claimed at this business. If you sent it yourself, report it — do not send the money again.",
        "এই TrxID দিয়ে এই প্রতিষ্ঠানে আগে দাবি হয়েছে। আপনি নিজে পাঠিয়ে থাকলে \"রিপোর্ট\" করুন — আবার টাকা পাঠাবেন না", "pay_ref_used", // bn-ok
    )

    /** P3: the business rejected this TrxID of yours — report it, never claim it again elsewhere */
    private fun refRejected(lang: String?) =
        Pay.err(lang, 409, "The business rejected this TrxID — report it", "এই TrxID প্রতিষ্ঠান বাতিল করেছে — রিপোর্ট করুন", "pay_ref_rejected") // bn-ok

    private fun alreadyPaid(lang: String?) =
        Pay.err(lang, 409, "The payment was already marked as received", "টাকা আগেই পাওয়া গেছে বলে জানানো হয়েছে", "state") // bn-ok

    /** rejected claims of a payer in the last 30 days that still count (P3: not disputed, not taken back) */
    fun rejects(payer: String, now: Long): Int = jdbc.queryForObject(
        """
        SELECT count(*) FROM pay_events e
        WHERE e.payer_uid = ? AND e.action = 'reject' AND e.at > ?
          AND NOT EXISTS (SELECT 1 FROM pay_reports r WHERE r.request_id = e.request_id AND r.disputes_reject AND coalesce(r.outcome, '') <> 'payer_fault')
          AND NOT EXISTS (SELECT 1 FROM pay_requests q WHERE q.id = e.request_id AND q.status IN ('confirmed', 'refund_claimed', 'refunded'))
        """.trimIndent(),
        Int::class.java, payer, Sql.ts(now - Pay.REJECT_WINDOW_MS),
    ) ?: 0

    /** rate limits + the repeated-rejections rule, then the try is counted */
    private fun claimLimits(lang: String?, key: String, reqId: String?, payer: String, now: Long) {
        val perReq = jdbc.queryForObject("SELECT count(*) FROM pay_claim_tries WHERE request_id = ? AND at > ?", Int::class.java, key, Sql.ts(now - Pay.HOUR)) ?: 0
        if (perReq >= Pay.CLAIMS_PER_REQUEST_HOUR) throw rate(lang)
        val perUser = jdbc.queryForObject("SELECT count(*) FROM pay_claim_tries WHERE payer_uid = ? AND at > ?", Int::class.java, payer, Sql.ts(now - Pay.DAY)) ?: 0
        if (perUser >= Pay.CLAIMS_PER_USER_DAY) throw rate(lang)
        jdbc.update("INSERT INTO pay_claim_tries (request_id, payer_uid, at) VALUES (?, ?, ?)", key, payer, Sql.ts(now))
        if (rejects(payer, now) >= Pay.REJECTS_FOR_LIMIT) {
            val other = jdbc.queryForList("SELECT 1 FROM pay_requests WHERE payer_uid = ? AND status = 'claimed' AND id <> ? LIMIT 1", payer, reqId ?: "")
            if (other.isNotEmpty()) {
                throw Pay.err(lang, 409, "Wait until your other payment is checked", "আপনার আগের পেমেন্টটা যাচাই হওয়া পর্যন্ত অপেক্ষা করুন", "pay_claim_limit") // bn-ok
            }
        }
    }

    /** the appointment's side of a chamber_fee claim (the old flow's rules, row locked); returns (changed, TrxID, old state) */
    private fun apptClaim(lang: String?, apptId: String, trx: String?, keepOld: Boolean): Triple<Boolean, String?, String?> {
        val a = jdbc.query(
            "SELECT status, pay_state, pay_ref, provider_uid FROM appointments WHERE id = ? FOR UPDATE",
            { rs, _ -> listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)) }, apptId,
        ).firstOrNull() ?: throw Pay.notFound(lang)
        val status = a[0]
        val pay = a[1]
        val oldRef = a[2]
        val doctor = a[3]
        if (status != "pending" && status != "confirmed") throw Pay.state(lang)
        if (pay == "paid") throw Pay.err(lang, 409, "The doctor already marked the fee as received", "ডাক্তার আগেই ফি পেয়েছেন বলে জানিয়েছেন", "state") // bn-ok
        if (pay != "unpaid" && pay != "claimed") throw Pay.state(lang)
        // a second claim without a TrxID keeps the earlier one
        val ref = trx ?: if (keepOld && pay == "claimed") oldRef else null
        val changed = pay != "claimed" || ref != oldRef
        if (ref != null && jdbc.queryForList("SELECT 1 FROM appointments WHERE provider_uid = ? AND pay_ref = ? AND id <> ? LIMIT 1", doctor, ref, apptId).isNotEmpty()) {
            throw refUsed(lang)
        }
        return Triple(changed, ref, pay)
    }

    private fun apptLog(apptId: String, by: String?, from: String?, to: String?, ref: String?, note: String?) {
        jdbc.update(
            "INSERT INTO appt_pay_log (appointment_id, by_uid, from_state, to_state, pay_ref, note) VALUES (?, ?, ?, ?, ?, ?)",
            apptId, by, from, to, ref, note?.take(300),
        )
    }

    /**
     * The claim itself (both the new pay_claim and the old appt_pay_claim): limits, then on the locked rows the state,
     * the TrxID once per business, the new state. [trx] null = no TrxID (old app); [legacyAppt] = the appointment when
     * the old action runs (the request may be missing then). Returns (changed, the TrxID now on it).
     */
    private fun claimCore(lang: String?, r: PayReq?, legacyAppt: String?, payer: String, caller: String, method: String?, trx: String?, last4: String?, now: Long): Pair<Boolean, String?> {
        val apptId = legacyAppt ?: r?.takeIf { it.purpose == "chamber_fee" }?.refId
        claimLimits(lang, r?.id ?: "appt:$apptId", r?.id, payer, now)
        return try {
            tx.execute {
                var changed = false
                var ref: String? = trx
                var apptFrom: String? = null
                if (apptId != null) {
                    val (c, rf, from) = apptClaim(lang, apptId, trx, legacyAppt != null)
                    changed = c
                    ref = rf
                    apptFrom = from
                }
                if (r != null) {
                    val cur = lock(r.id) ?: throw Pay.notFound(lang)
                    if (cur.status in setOf("confirmed", "refund_claimed", "refunded")) throw alreadyPaid(lang)
                    if (cur.status !in Pay.LIVE) throw Pay.state(lang)
                    if (cur.expiresAt != null && cur.expiresAt <= now) throw Pay.err(lang, 409, "This payment request has expired", "এই পেমেন্টের অনুরোধের মেয়াদ শেষ", "expired") // bn-ok
                    if (apptId == null) {
                        ref = trx ?: if (cur.status == "claimed") cur.claimTrx else null
                        changed = cur.status != "claimed" || ref != cur.claimTrx
                    }
                    if (ref != null) {
                        val other = jdbc.queryForList(
                            "SELECT status, payer_uid FROM pay_requests WHERE kind = ? AND biz_id = ? AND claim_trx = ? AND id <> ? LIMIT 1", cur.kind, cur.bizId, ref, cur.id,
                        ).firstOrNull()
                        if (other != null) {
                            if (other["status"] == "rejected" && other["payer_uid"] == payer) throw refRejected(lang)
                            throw if (legacyAppt != null) refUsed(lang) else refUsedReport(lang)
                        }
                    }
                    val m = method ?: cur.claimMethod ?: cur.methods.firstOrNull()?.get("method") ?: "bkash"
                    jdbc.update(
                        """
                        UPDATE pay_requests SET status = 'claimed', claim_method = ?, claim_trx = ?, claim_last4 = coalesce(?, claim_last4), claimed_at = ?,
                          claimed_by = ?, reminded_at = NULL, flagged_at = NULL, updated_at = ?
                        WHERE id = ?
                        """.trimIndent(),
                        m, ref, last4, Sql.ts(now), caller, Sql.ts(now), cur.id,
                    )
                    if (changed || cur.status != "claimed") event(cur.id, "claim", caller, payer, cur.status, "claimed", ref, null, now)
                }
                if (apptId != null) {
                    jdbc.update(
                        "UPDATE appointments SET pay_state = 'claimed', pay_ref = ?, pay_note = NULL, pay_claimed_at = ?, updated_at = ? WHERE id = ?",
                        ref, Sql.ts(now), Sql.ts(now), apptId,
                    )
                    if (changed) apptLog(apptId, caller, apptFrom, "claimed", ref, null)
                }
                changed to ref
            }!!
        } catch (e: DuplicateKeyException) {
            throw if (legacyAppt != null) refUsed(lang) else refUsedReport(lang)
        }
    }

    /** pay_claim {id, method, trxId, senderLast4} — the payer (or a caregiver via asUid; recorded as `claim.by`) */
    fun claim(lang: String?, user: AuthUser, callerUid: String, id: String?, methodRaw: String?, trxRaw: String?, last4Raw: String?): PayReq {
        val r = load(lang, id)
        requirePayer(lang, user, r)
        // P13: a new claim needs the business's switch on (a switch going off stops new money, never money already sent)
        gate.requirePurpose(lang, r.ownerUid, r.purpose)
        val method = methodRaw?.takeIf { it in Pay.METHODS && r.methods.any { m -> m["method"] == it } }
            ?: throw Pay.bad(lang, "Choose how you paid", "কীভাবে টাকা পাঠিয়েছেন বেছে নিন", "method") // bn-ok
        val trx = Pay.normTrx(trxRaw)?.takeIf { Pay.trxOk(method, it) }
            ?: throw Pay.bad(
                lang,
                if (method == "bkash") "Write the TrxID from the bKash message (10 letters and numbers)" else "Write the TrxID from the message (6–20 letters and numbers)",
                if (method == "bkash") "বিকাশ মেসেজের TrxID লিখুন (১০টি ইংরেজি অক্ষর ও সংখ্যা)" else "মেসেজের TrxID লিখুন (৬–২০টি ইংরেজি অক্ষর ও সংখ্যা)", // bn-ok
                "trx_format",
            )
        val last4 = Pay.last4(last4Raw)
            ?: throw Pay.bad(lang, "Write the last 4 digits of the number you sent from", "যে নম্বর থেকে পাঠিয়েছেন তার শেষ ৪ সংখ্যা লিখুন", "sender_digits") // bn-ok
        val now = clock.now()
        val (changed, _) = claimCore(lang, r, null, user.id, callerUid, method, trx, last4, now)
        val after = get(r.id)!!
        if (changed) notifyBiz(after, "pay_claimed")
        return after
    }

    /** appt_pay_claim (compatibility): the online chamber fee, TrxID optional (old apps). Returns (changed, TrxID). */
    fun legacyClaim(lang: String?, appointmentId: String, payer: String, caller: String, trx: String?, now: Long): Pair<Boolean, String?> {
        val r = ensureChamberFee(appointmentId, now)
        return claimCore(lang, r, appointmentId, payer, caller, if (trx != null) "bkash" else null, trx, null, now)
    }

    // ------------------------------------------------------------------ confirm / reject

    /**
     * The business confirms. [strict] (pay_confirm): only a claim, or a claim it rejected within 7 days (P3); the old
     * appt_pay_confirm also confirms money that came without a claim. Returns whether it changed.
     */
    private fun confirmCore(lang: String?, r: PayReq?, legacyAppt: String?, by: String, strict: Boolean, now: Long): Boolean {
        val apptId = legacyAppt ?: r?.takeIf { it.purpose == "chamber_fee" }?.refId
        return tx.execute {
            var changed = false
            if (r != null && strict) {
                val cur = query("id = ?", r.id).firstOrNull() ?: throw Pay.notFound(lang)
                if (cur.status == "confirmed") return@execute false
                val reconfirm = cur.status == "rejected" && cur.rejectedAt != null && cur.rejectedAt > now - Pay.RECONFIRM_MS
                if (cur.status != "claimed" && !reconfirm) throw Pay.state(lang)
            }
            if (apptId != null) {
                val a = jdbc.query(
                    "SELECT status, pay_state, pay_ref FROM appointments WHERE id = ? FOR UPDATE",
                    { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getString(3)) }, apptId,
                ).firstOrNull() ?: throw Pay.notFound(lang)
                if (a.second != "paid") {
                    if (a.first !in setOf("pending", "confirmed", "completed")) throw Pay.state(lang)
                    jdbc.update(
                        "UPDATE appointments SET pay_state = 'paid', pay_note = NULL, pay_paid_at = ?, pay_paid_by = ?, updated_at = ? WHERE id = ?",
                        Sql.ts(now), by, Sql.ts(now), apptId,
                    )
                    apptLog(apptId, by, a.second, "paid", a.third, null)
                    changed = true
                }
            }
            if (r != null) {
                val cur = lock(r.id) ?: throw Pay.notFound(lang)
                if (cur.status in Pay.LIVE) {
                    jdbc.update(
                        "UPDATE pay_requests SET status = 'confirmed', confirmed_at = ?, confirmed_by = ?, updated_at = ? WHERE id = ?",
                        Sql.ts(now), by, Sql.ts(now), cur.id,
                    )
                    event(cur.id, if (cur.status == "rejected") "reconfirm" else "confirm", by, cur.payerUid, cur.status, "confirmed", cur.claimTrx, null, now)
                    if (apptId == null || strict) changed = true
                } else if (cur.status != "confirmed" && apptId == null) {
                    throw Pay.state(lang)
                }
            }
            changed
        } ?: false
    }

    /**
     * pay_confirm {id, checks:{trxId:true, amount:true, last4:true}} — the business (owner, or a doctor's current
     * assistant) found the money in its own wallet (P1: the three checks are required). The receipt is issued (or the
     * payment added to the existing one). Works whatever the switches say (P13: money already sent is never stuck).
     */
    fun confirm(lang: String?, user: AuthUser, id: String?, checks: JsonNode?): Pair<PayReq, Invoice?> {
        val r = load(lang, id)
        requireBiz(lang, user, r)
        val ok = checks != null && checks.isObject && listOf("trxId", "amount", "last4").all { k -> checks.get(k)?.let { it.isBoolean && it.booleanValue() } == true }
        if (!ok) {
            throw Pay.bad(
                lang, "Check the TrxID, the amount and the last 4 digits in your own wallet first",
                "আগে নিজের অ্যাপে TrxID, টাকার পরিমাণ আর শেষ ৪ সংখ্যা মিলিয়ে নিন", "checks_required", // bn-ok
            )
        }
        val now = clock.now()
        val changed = confirmCore(lang, r, null, user.id, strict = true, now = now)
        val invoice = if (changed) receipt(lang, user, get(r.id)!!) else get(r.id)?.invoiceId?.let { invoices.load(it) }
        val after = get(r.id)!!
        if (changed) notifyPayer(after, "pay_confirmed")
        return after to invoice
    }

    /** appt_pay_confirm (compatibility): returns (changed, the receipt) */
    fun legacyConfirm(lang: String?, actor: AuthUser, appointmentId: String, now: Long): Pair<Boolean, Invoice?> {
        val r = ensureChamberFee(appointmentId, now)
        val changed = confirmCore(lang, r, appointmentId, actor.id, strict = false, now = now)
        if (!changed) return false to null
        val invoice = if (r != null) {
            receipt(lang, actor, get(r.id)!!)
        } else {
            try {
                creators.onlineFeePaid(lang, actor, appointmentId)
            } catch (e: Exception) {
                log.warn("online fee receipt failed: {}", e.message)
                null
            }
        }
        return true to invoice
    }

    private fun rejectCore(lang: String?, r: PayReq?, legacyAppt: String?, by: String, reason: String, note: String?, now: Long) {
        val apptId = legacyAppt ?: r?.takeIf { it.purpose == "chamber_fee" }?.refId
        tx.executeWithoutResult {
            if (apptId != null) {
                val a = jdbc.query(
                    "SELECT pay_state, pay_ref FROM appointments WHERE id = ? FOR UPDATE", { rs, _ -> rs.getString(1) to rs.getString(2) }, apptId,
                ).firstOrNull() ?: throw Pay.notFound(lang)
                if (a.first != "claimed") throw Pay.state(lang)
                jdbc.update("UPDATE appointments SET pay_state = 'unpaid', pay_note = ?, updated_at = ? WHERE id = ?", note?.take(200), Sql.ts(now), apptId)
                apptLog(apptId, by, "claimed", "unpaid", a.second, note)
            }
            if (r != null) {
                val cur = lock(r.id) ?: throw Pay.notFound(lang)
                if (cur.status != "claimed") {
                    if (apptId == null) throw Pay.state(lang)
                } else {
                    // the claim stays on the request (TrxID, amount, method, times): the patient can still report it (P3)
                    jdbc.update(
                        "UPDATE pay_requests SET status = 'rejected', reject_reason = ?, reject_note = ?, rejected_at = ?, updated_at = ? WHERE id = ?",
                        reason, note?.take(200), Sql.ts(now), Sql.ts(now), cur.id,
                    )
                    event(cur.id, "reject", by, cur.payerUid, "claimed", "rejected", cur.claimTrx, listOfNotNull(reason, note).joinToString(": "), now)
                }
            }
        }
    }

    /** pay_reject {id, reason, note?} — the business has not received it (owner or a doctor's current assistant) */
    fun reject(lang: String?, user: AuthUser, id: String?, reasonRaw: String?, noteRaw: String?): PayReq {
        val r = load(lang, id)
        requireBiz(lang, user, r)
        val reason = reasonRaw?.takeIf { it in Pay.REJECT_REASONS } ?: throw Pay.bad(lang, "Choose a reason", "কারণ বেছে নিন", "reason") // bn-ok
        val note = Rules.cleanText(noteRaw, 200)
        if (reason == "other" && note == null) throw Pay.bad(lang, "Write the reason", "কারণ লিখুন", "reason") // bn-ok
        rejectCore(lang, r, null, user.id, reason, note, clock.now())
        val after = get(r.id)!!
        notifyPayer(after, "pay_rejected")
        return after
    }

    /** appt_pay_reject (compatibility): claimed → unpaid with the reason as the note */
    fun legacyReject(lang: String?, actor: AuthUser, appointmentId: String, note: String?, now: Long) {
        val r = ensureChamberFee(appointmentId, now)
        rejectCore(lang, r, appointmentId, actor.id, "not_received", note, now)
    }

    // ------------------------------------------------------------------ refunds

    /**
     * pay_refund {id, trxId, amount?} — the OWNER only (never an assistant, P5/P14): the business says it sent money
     * back to the sender → `refund_claimed`; `refunded` only when the payer acknowledges. At most the paid amount. The
     * receipt follows: in full it is voided (reason `refunded`), in part it gets a refund line.
     */
    fun refund(lang: String?, user: AuthUser, id: String?, trxRaw: String?, amountNode: JsonNode?): PayReq {
        val r = load(lang, id)
        val side = requireBiz(lang, user, r)
        if (side != "owner") throw Pay.err(lang, 403, "Only the owner can refund", "শুধু মালিক টাকা ফেরত দিতে পারেন", "owner_only") // bn-ok
        val method = r.claimMethod ?: "nagad"
        val trx = Pay.normTrx(trxRaw)?.takeIf { Pay.trxOk(method, it) || Pay.trxOk("nagad", it) }
            ?: throw Pay.bad(lang, "Write the TrxID of the refund", "ফেরতের TrxID লিখুন", "trx_format") // bn-ok
        val amount = InvoiceMath.money(lang, amountNode, "amount", "টাকার পরিমাণ", Pay.MAX_AMOUNT)?.takeIf { it.signum() > 0 } ?: r.amount // bn-ok
        if (amount > r.amount) throw Pay.bad(lang, "The refund is more than the payment", "ফেরত পেমেন্টের চেয়ে বেশি", "amount") // bn-ok
        val now = clock.now()
        tx.executeWithoutResult {
            val cur = lock(r.id) ?: throw Pay.notFound(lang)
            if (cur.status != "confirmed") throw Pay.state(lang)
            jdbc.update(
                "UPDATE pay_requests SET status = 'refund_claimed', refund_trx = ?, refund_amount = ?, refunded_at = ?, refunded_by = ?, updated_at = ? WHERE id = ?",
                trx, InvoiceMath.m2(amount), Sql.ts(now), user.id, Sql.ts(now), cur.id,
            )
            event(cur.id, "refund", user.id, cur.payerUid, "confirmed", "refund_claimed", trx, InvoiceMath.num(amount).toString(), now)
        }
        val after = get(r.id)!!
        // the receipt: voided in full (only one this request made), a refund line in part — best effort, the refund stands
        val invId = after.invoiceId
        if (invId != null) {
            try {
                tx.executeWithoutResult {
                    if (after.invoiceIssued && amount.compareTo(after.amount) == 0) {
                        invoices.void(lang, user, invId, "refunded", "TrxID $trx", refund = true)
                    } else {
                        invoices.refundLine(lang, user, invId, amount, Pay.invoiceRef(after.claimMethod, trx))
                    }
                }
            } catch (e: Exception) {
                log.info("refund: receipt {} not changed: {}", invId, e.message)
            }
        }
        notifyPayer(after, "pay_refunded")
        return after
    }

    /** pay_refund_ack {id} — the payer got the refund: refund_claimed → refunded */
    fun refundAck(lang: String?, user: AuthUser, id: String?): PayReq {
        val r = load(lang, id)
        requirePayer(lang, user, r)
        if (r.status == "refunded") return r
        if (r.status != "refund_claimed") throw Pay.state(lang)
        val now = clock.now()
        val n = jdbc.update(
            "UPDATE pay_requests SET status = 'refunded', refund_ack_at = ?, updated_at = ? WHERE id = ? AND status = 'refund_claimed'",
            Sql.ts(now), Sql.ts(now), r.id,
        )
        if (n > 0) event(r.id, "refund_ack", user.id, r.payerUid, "refund_claimed", "refunded", null, null, now)
        return get(r.id)!!
    }

    // ------------------------------------------------------------------ the receipt

    /**
     * Confirmed: the receipt (InvoiceService) in its own transaction after the confirmation, so a receipt problem never
     * undoes it — the online chamber's receipt, a new one for an advance / a prepaid delivery or lab, else the payment
     * added to the existing receipt (delivery, lab bill, ambulance fare, a receipt's due). Links it to the request.
     */
    private fun receipt(lang: String?, actor: AuthUser, r: PayReq): Invoice? {
        val m = Pay.invoiceMethod(r.claimMethod ?: r.methods.firstOrNull()?.get("method"))
        val ref = Pay.invoiceRef(r.claimMethod, r.claimTrx)
        var issued = false
        val inv: Invoice? = try {
            tx.execute<Invoice?> {
                fun addTo(existing: Invoice?): Invoice? {
                    if (existing == null || existing.status != "issued") return existing
                    if (existing.due.signum() <= 0) return existing
                    return invoices.pay(lang, actor, existing.id, PaymentIn(r.amount.min(existing.due), m, ref))
                }
                when (r.purpose) {
                    "chamber_fee" -> creators.onlineFeePaid(lang, actor, r.refId)?.also { issued = true } ?: invoices.liveFor("appointment", r.refId)
                    "booking_advance" -> {
                        if (!invFeatures.kindOn(r.ownerUid, "chamber")) return@execute null
                        val a = one("SELECT patient_name, visit_date, chamber, chamber_bn FROM appointments WHERE id = ?", r.refId)
                        val date = (a?.get("visit_date") as java.sql.Date?)?.toLocalDate()
                        invoices.issue(
                            lang, actor,
                            IssueCmd(
                                "chamber", r.bizId, listOf(LineIn("অগ্রিম বুকিং ফি${if (date != null) " ($date)" else ""}", BigDecimal.ONE, r.amount)), // bn-ok
                                payments = listOf(PaymentIn(r.amount, m, ref)),
                                customer = Customer((a?.get("patient_name") as String?)?.ifEmpty { null }?.take(60)), patientUid = r.payerUid,
                                bizAddress = ((a?.get("chamber_bn") as String?)?.ifEmpty { null } ?: (a?.get("chamber") as String?))?.ifEmpty { null },
                            ),
                        ).also { issued = true }
                    }
                    "delivery" -> {
                        val live = invoices.liveFor("rx_order", r.refId)
                        if (live != null) {
                            addTo(live)
                        } else {
                            val o = one("SELECT * FROM rx_orders WHERE id = ?", r.refId) ?: return@execute null
                            val ls = (o["invoice_lines"]?.toString()?.let { InvoiceMath.linesFromJson(it) })?.takeIf { it.isNotEmpty() }
                                ?: listOf(LineIn("ওষুধ — অনলাইন অর্ডার", BigDecimal.ONE, r.amount)) // bn-ok
                            val fee = if (o["invoice_lines"] != null) InvoiceMath.takaOf(o["delivery_fee"] as String?) else null
                            invoices.issue(
                                lang, actor,
                                IssueCmd(
                                    "pharmacy", r.bizId, ls, fees = if (fee != null) listOf(FeeIn("ডেলিভারি চার্জ", fee)) else emptyList(), // bn-ok
                                    payments = listOf(PaymentIn(r.amount, m, ref)),
                                    customer = Customer((o["patient_name"] as String?)?.ifEmpty { null }?.take(60), (o["phone"] as String?)?.take(20)),
                                    patientUid = r.payerUid, refType = "rx_order", refId = r.refId,
                                ),
                            ).also { issued = true }
                        }
                    }
                    "lab" -> if (r.refType == "invoice") {
                        addTo(invoices.load(r.refId))
                    } else {
                        val live = invoices.liveFor("lab_order", r.refId)
                        if (live != null) {
                            addTo(live)
                        } else {
                            val body = mapper.createObjectNode()
                            body.put("labOrderId", r.refId)
                            val p = body.putArray("payments").addObject()
                            p.put("amount", r.amount)
                            p.put("method", m)
                            if (ref != null) p.put("ref", ref)
                            creators.labBill(lang, actor, Body(body))
                            issued = true
                            invoices.liveFor("lab_order", r.refId)
                        }
                    }
                    "ambulance_fare" -> addTo(invoices.liveFor("ambreq", r.refId))
                    "invoice_due" -> addTo(invoices.load(r.refId))
                    else -> null
                }
            }
        } catch (e: Exception) {
            log.warn("pay receipt for {} failed: {}", r.id, e.message)
            null
        }
        if (inv != null) {
            jdbc.update("UPDATE pay_requests SET invoice_id = ?, invoice_issued = ? WHERE id = ?", inv.id, issued, r.id)
        }
        return inv
    }

    // ------------------------------------------------------------------ history, pushes

    fun event(reqId: String, action: String, by: String?, payer: String?, from: String?, to: String?, trx: String?, note: String?, now: Long) {
        jdbc.update(
            "INSERT INTO pay_events (request_id, action, by_uid, payer_uid, from_status, to_status, trx, note, at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            reqId, action, by, payer, from, to, trx, note?.take(300), Sql.ts(now),
        )
    }

    fun events(reqId: String): List<Map<String, Any?>> = jdbc.query(
        "SELECT action, by_uid, from_status, to_status, trx, note, at FROM pay_events WHERE request_id = ? ORDER BY id",
        { rs, _ ->
            linkedMapOf<String, Any?>(
                "action" to rs.getString(1), "by" to rs.getString(2), "from" to rs.getString(3), "to" to rs.getString(4),
                "trxId" to rs.getString(5), "note" to rs.getString(6), "at" to rs.getTimestamp(7).time,
            )
        },
        reqId,
    )

    /** the push data: only the request id and what it is about (P15 — never an amount, number or name) */
    private fun data(type: String, r: PayReq, cat: String): Map<String, Any?> =
        linkedMapOf("type" to type, "payRequestId" to r.id, "purpose" to r.purpose, "cat" to cat)

    /** pay_requested / pay_confirmed / pay_rejected / pay_refunded → the payer (+ caregivers): push + SSE */
    fun notifyPayer(r: PayReq, type: String) {
        val payer = r.payerUid ?: return
        // P13: the business's switch; when it went off, the news of money already sent still goes out
        if (type == "pay_requested" && !gate.purposeOn(r.ownerUid, r.purpose)) return
        val cat = if (r.purpose == "chamber_fee" || r.purpose == "booking_advance") NotifyCats.APPOINTMENTS else NotifyCats.MY_REQUEST
        try {
            val lang = jdbc.queryForList("SELECT lang FROM user_profiles WHERE uid = ?", String::class.java, payer).firstOrNull()
            push.pushPatient(payer, PayTexts.TITLE_EN, PayTexts.TITLE_BN, PayTexts.body(type, lang ?: "bn"), data(type, r, cat))
            sse.publish("user:$payer", type, linkedMapOf("id" to r.id, "status" to r.status, "purpose" to r.purpose))
        } catch (e: Exception) {
            log.warn("pay push failed: {}", e.message)
        }
    }

    /** the business's people: the owner, and a doctor's current assistants for a chamber */
    fun bizUids(r: PayReq): List<String> {
        val owner = r.ownerUid ?: return emptyList()
        if (r.kind != "chamber") return listOf(owner)
        val emails = assistants.emails(owner).map { it.lowercase() }.distinct()
        if (emails.isEmpty()) return listOf(owner)
        val asst = jdbc.queryForList(
            "SELECT uid FROM accounts WHERE lower(email) IN (${emails.joinToString(",") { "?" }}) AND deactivated_at IS NULL ORDER BY uid LIMIT 20",
            String::class.java, *emails.toTypedArray(),
        ).filter { it != owner && DemoWorld.sameWorld(it, owner) }
        return listOf(owner) + asst
    }

    /** pay_claimed / pay_reminder / pay_refund_due → the business (cat business): push + SSE */
    fun notifyBiz(r: PayReq, type: String, ownerOnly: Boolean = false) {
        val owner = r.ownerUid ?: return
        try {
            val to = if (ownerOnly) listOf(owner) else bizUids(r)
            push.pushUsers(to) { _, lang -> PushText(Lang.pick(lang, PayTexts.TITLE_EN, PayTexts.TITLE_BN), PayTexts.body(type, lang), data(type, r, NotifyCats.BUSINESS)) }
            for (uid in to) sse.publish("user:$uid", type, linkedMapOf("id" to r.id, "status" to r.status, "purpose" to r.purpose))
        } catch (e: Exception) {
            log.warn("pay push failed: {}", e.message)
        }
    }
}
