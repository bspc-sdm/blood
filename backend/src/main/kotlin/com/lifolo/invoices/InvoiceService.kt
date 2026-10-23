package com.lifolo.invoices

import com.fasterxml.jackson.databind.ObjectMapper
import com.lifolo.admin.kernel.Sanctions
import com.lifolo.auth.AuthUser
import com.lifolo.common.ApiException
import com.lifolo.common.DhakaTime
import com.lifolo.common.Ids
import com.lifolo.common.Lang
import com.lifolo.common.RateLimiter
import com.lifolo.common.Rules
import com.lifolo.common.Sql
import com.lifolo.common.ms
import com.lifolo.contracts.ProviderAssistants
import com.lifolo.demo.DemoWorld
import com.lifolo.notify.NotifyCats
import com.lifolo.notify.PushService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.Duration
import java.time.LocalDate
import java.time.YearMonth

/** a business that issues receipts (pharmacy/lab owner, doctor, ambulance) as it is now */
data class Biz(
    val kind: String,
    val id: String,
    val ownerUid: String,
    val name: String,
    val phone: String?,
    val address: String?,
    /** approved | pending | suspended | rejected */
    val status: String,
    /** the Node-shaped listing for the sanction keys (licenseNo, phone, vehicleNo) — empty for doctors */
    val listing: Map<String, Any?>,
)

/** something that must happen when an invoice is voided (pharmacy: the sale's stock goes back) */
interface InvoiceVoidHook {
    fun onVoid(lang: String?, inv: Invoice)
}

/**
 * Receipts (docs/INVOICES.md): issue (any kind — the creators build the lines), pay, void, read, lists, the public verify
 * data. Every write runs in the caller's transaction (REQUIRED) on locked rows; the patient's push goes after commit.
 */
@Service
class InvoiceService(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
    private val assistants: ProviderAssistants,
    private val push: PushService,
    private val rate: RateLimiter,
    private val voidHooks: ObjectProvider<InvoiceVoidHook>,
    private val features: InvoiceFeatures,
    private val privacy: com.lifolo.practice.PracticePrivacy,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** the chamber of a psychologist (PRACTICE.md privacy) */
    private fun psychologist(bizId: String): Boolean =
        jdbc.queryForList("SELECT 1 FROM providers WHERE uid = ? AND type = 'psychologist'", bizId).isNotEmpty()

    companion object {
        /** SQL: not a psychologist's chamber receipt (caregivers without sharing — backend/docs/PRACTICE.md) */
        const val NOT_PSYCH = "NOT (kind = 'chamber' AND biz_id IN (SELECT uid FROM providers WHERE type = 'psychologist'))"
        const val PAGE = 30
        const val BIZ_PAGE = 50
        const val MASK_AFTER_MS = 30L * 24 * 3600 * 1000
        val PERIODS = listOf("today", "7d", "30d", "month", "365d")

        /** an assistant voids their own receipt within this time */
        const val ASSISTANT_VOID_MS = 2L * 3600 * 1000

        /** issues per business a minute (online; the desktop sync is not limited here) */
        const val ISSUE_PER_MINUTE = 60L

        fun err(lang: String?, status: Int, en: String, bn: String, code: String? = null) = ApiException(status, Lang.tr(lang, en, bn), code)

        fun notFound(lang: String?) = err(lang, 404, "Not found", "পাওয়া যায়নি")
    }

    // ------------------------------------------------------------------ the business

    fun biz(kind: String, bizId: String?): Biz? {
        if (bizId == null || !Rules.isValidId(bizId)) return null
        return when (kind) {
            "pharmacy" -> jdbc.query(
                "SELECT uid, name, phone, address, status, license_no FROM pharmacies WHERE uid = ?",
                { rs, _ -> Biz(kind, rs.getString(1), rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), mapOf("licenseNo" to rs.getString(6), "phone" to rs.getString(3))) },
                bizId,
            ).firstOrNull()
            "lab" -> jdbc.query(
                "SELECT uid, name, phone, address, status, license_no FROM labs WHERE uid = ?",
                { rs, _ -> Biz(kind, rs.getString(1), rs.getString(1), rs.getString(2), rs.getString(3)?.ifEmpty { null }, rs.getString(4)?.ifEmpty { null }, rs.getString(5), mapOf("licenseNo" to rs.getString(6), "phone" to rs.getString(3))) },
                bizId,
            ).firstOrNull()
            "ambulance" -> jdbc.query(
                "SELECT id, owner_uid, name, phone, area, status, vehicle_no FROM ambulances WHERE id = ?",
                { rs, _ -> Biz(kind, rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)?.ifEmpty { null }, rs.getString(6), mapOf("vehicleNo" to rs.getString(7))) },
                bizId,
            ).firstOrNull()
            "chamber" -> jdbc.query(
                """
                SELECT p.uid, coalesce(nullif(b.name, ''), p.name) AS name, p.admin_suspended, b.status AS badge
                FROM providers p LEFT JOIN badges b ON b.uid = p.uid AND b.type IN ('doctor', 'physio', 'psychologist') WHERE p.uid = ?
                """.trimIndent(),
                { rs, _ ->
                    val status = when {
                        rs.getBoolean("admin_suspended") -> "suspended"
                        rs.getString("badge") == "approved" -> "approved"
                        else -> "pending"
                    }
                    Biz(kind, rs.getString(1), rs.getString(1), rs.getString(2), null, null, status, emptyMap())
                },
                bizId,
            ).firstOrNull()
            else -> null
        }
    }

    /** "owner" or "assistant" (a doctor's assistant by verified email); null = not this business */
    fun roleOf(actor: AuthUser, kind: String, bizId: String, ownerUid: String?): String? {
        if (actor.guest || actor.patientSideOnly) return null
        val uid = actor.uid ?: return null
        if (ownerUid != null && uid == ownerUid) return "owner"
        if (kind == "chamber" && ownerUid != null) {
            val email = if (actor.emailVerified) actor.email.lowercase() else ""
            if (email.isNotEmpty() && assistants.isAssistant(bizId, email)) return "assistant"
        }
        return null
    }

    private fun banned(b: Biz): Boolean {
        val keys = Sanctions.listingKeys(b.kind, b.listing)
        return keys.isNotEmpty() && jdbc.queryForList(
            "SELECT 1 FROM sanctions WHERE listing AND key IN (${keys.joinToString(",") { "?" }}) LIMIT 1", *keys.toTypedArray(),
        ).isNotEmpty()
    }

    /** the business may issue receipts: approved and not under a sanction */
    fun canIssue(b: Biz): Boolean = b.status == "approved" && !banned(b)

    /** [canIssue] or 403 `biz_sanctioned` / `biz_not_approved` */
    fun requireCanIssue(lang: String?, b: Biz) {
        if (b.status == "suspended" || banned(b)) {
            throw err(lang, 403, "This business is suspended — it can't issue receipts", "এই প্রতিষ্ঠান এখন স্থগিত — রসিদ দেওয়া যাবে না", "biz_sanctioned") // bn-ok
        }
        if (b.status != "approved") {
            throw err(lang, 403, "Only approved businesses can issue receipts", "শুধু অনুমোদিত প্রতিষ্ঠান রসিদ দিতে পারে", "biz_not_approved") // bn-ok
        }
    }

    /** the issuer side of [kind]/[bizId] for the actor (403 otherwise); returns the business */
    fun requireIssuer(lang: String?, actor: AuthUser, kind: String?, bizId: String?): Biz {
        if (kind == null || kind !in InvoiceMath.KINDS) throw notFound(lang)
        val b = biz(kind, bizId) ?: throw notFound(lang)
        if (roleOf(actor, kind, b.id, b.ownerUid) == null) {
            throw err(lang, 403, "Only the business can do this", "শুধু প্রতিষ্ঠান নিজে এটা করতে পারে", "not_issuer") // bn-ok
        }
        return b
    }

    /**
     * The business owner only — not a doctor's assistant (security review I7): desktop_register / desktop_revoke,
     * invoice_export_csv. 403 `owner_only` for an assistant.
     */
    fun requireOwner(lang: String?, actor: AuthUser, kind: String?, bizId: String?): Biz {
        val b = requireIssuer(lang, actor, kind, bizId)
        if (roleOf(actor, b.kind, b.id, b.ownerUid) != "owner") {
            throw err(lang, 403, "Only the owner can do this", "শুধু মালিক এটা করতে পারেন", "owner_only") // bn-ok
        }
        return b
    }

    // ------------------------------------------------------------------ issue

    @Transactional
    fun issue(lang: String?, actor: AuthUser, cmd: IssueCmd, stamp: DeviceStamp? = null): Invoice {
        val b = requireIssuer(lang, actor, cmd.kind, cmd.bizId)
        features.requireKind(lang, b.ownerUid, b.kind)
        requireCanIssue(lang, b)
        if (cmd.lines.isEmpty()) throw InvoiceMath.bad(lang, "Add at least one item", "অন্তত একটা আইটেম দিন") // bn-ok
        if (cmd.refType != null && cmd.refType !in InvoiceMath.REF_TYPES) throw notFound(lang)
        if (stamp == null && !rate.tryAcquire("inv-issue:${b.kind}:${b.id}", ISSUE_PER_MINUTE, Duration.ofMinutes(1))) {
            throw err(lang, 429, "Too many requests. Wait a minute and try again.", "অনেক বেশি অনুরোধ। এক মিনিট পর আবার চেষ্টা করুন।")
        }
        val t = InvoiceMath.compute(lang, cmd.lines, cmd.disc, cmd.fees)
        val pays = cmd.payments ?: if (t.total.signum() > 0) listOf(PaymentIn(t.total, "cash")) else emptyList()
        val paid = InvoiceMath.m2(pays.fold(BigDecimal.ZERO) { a, p -> a + p.amount })
        if (paid > t.total) throw InvoiceMath.bad(lang, "Payment is more than the bill", "পরিশোধ বিলের চেয়ে বেশি", "overpaid") // bn-ok
        if (cmd.refType != null && cmd.refId != null && liveFor(cmd.refType, cmd.refId) != null) {
            throw err(lang, 409, "A receipt was already issued for this", "এর রসিদ আগেই দেওয়া হয়েছে", "already_issued") // bn-ok
        }
        val owner = b.ownerUid
        // demo and real never meet: a patient of the other world is not linked
        val patient = cmd.patientUid?.takeIf { Rules.isValidId(it) && DemoWorld.sameWorld(it, owner) }
        val now = System.currentTimeMillis()
        val at = stamp?.at ?: now
        val (series, seq) = number(lang, b, stamp)
        val code = verifyCodeFor(lang, stamp)
        val deviceNo = stamp?.deviceNo?.takeIf { it != stamp.no }?.take(30)
        val id = Ids.newId()
        val no = InvoiceMath.no(series, seq)
        jdbc.update(
            """
            INSERT INTO invoices (id, kind, biz_id, owner_uid, no, series, seq, biz_name, biz_phone, biz_address, patient_uid, customer_name,
              customer_phone, ref_type, ref_id, lines, subtotal, disc, fees, total, paid, due, status, issued_at, issued_by, device_id, device_no,
              offline, verify_code, over_fare, chart_fare, is_demo)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?::jsonb, ?, ?, ?, 'issued', ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            id, b.kind, b.id, owner, no, series, seq, b.name.take(100), b.phone, (cmd.bizAddress ?: b.address)?.take(200), patient,
            cmd.customer.name, cmd.customer.phone, cmd.refType, cmd.refId, mapper.writeValueAsString(t.lines.map { it.toMap() }),
            t.subtotal, t.disc, mapper.writeValueAsString(t.fees.map { it.toMap() }), t.total, paid, t.total - paid, Sql.ts(at), actor.id,
            stamp?.deviceId, deviceNo, stamp != null, code, if (b.kind == "ambulance") (cmd.overFare ?: false) else null, cmd.chartFare,
            DemoWorld.isDemoUid(owner),
        )
        for (p in pays) insertPayment(id, at, p, actor.id, stamp?.deviceId)
        event(id, "issue", actor.id, stamp?.deviceId, mapOf("no" to no, "total" to InvoiceMath.num(t.total), "paid" to InvoiceMath.num(paid)))
        val inv = load(id)!!
        if (patient != null) afterCommit { notifyPatient(inv) }
        return inv
    }

    /** a new random code, or the one the device printed (format + not used yet) */
    private fun verifyCodeFor(lang: String?, stamp: DeviceStamp?): String {
        val given = stamp?.verifyCode
        if (given != null) {
            if (!Regex("^[A-Za-z0-9]{10}$").matches(given)) throw InvoiceMath.bad(lang, "Check the verify code", "যাচাই কোড দেখে নিন", "bad_verify") // bn-ok
            if (jdbc.queryForList("SELECT 1 FROM invoices WHERE verify_code = ?", given).isNotEmpty()) {
                throw err(lang, 409, "This verify code is already used", "এই যাচাই কোড আগেই ব্যবহার হয়েছে", "verify_collision") // bn-ok
            }
            return given
        }
        var code = InvoiceMath.verifyCode()
        while (jdbc.queryForList("SELECT 1 FROM invoices WHERE verify_code = ?", code).isNotEmpty()) code = InvoiceMath.verifyCode()
        return code
    }

    /** the next number: W from the counter (row lock), or the device's own number (accepted as is when free) */
    private fun number(lang: String?, b: Biz, stamp: DeviceStamp?): Pair<String, Int> {
        if (stamp == null) {
            val seq = jdbc.queryForObject(
                """
                INSERT INTO invoice_series (kind, biz_id, series, last_seq) VALUES (?, ?, 'W', 1)
                ON CONFLICT (kind, biz_id, series) DO UPDATE SET last_seq = invoice_series.last_seq + 1
                RETURNING last_seq
                """.trimIndent(),
                Int::class.java, b.kind, b.id,
            )!!
            return "W" to seq
        }
        val m = InvoiceMath.NO_RE.matchEntire(stamp.no)
        if (m == null || m.groupValues[1] == "W" || !Rules.isValidId(stamp.deviceId)) {
            throw InvoiceMath.bad(lang, "Check the receipt number", "রসিদ নম্বর দেখে নিন", "bad_no") // bn-ok
        }
        val series = m.groupValues[1]
        val seq = m.groupValues[2].toInt()
        if (seq <= 0) throw InvoiceMath.bad(lang, "Check the receipt number", "রসিদ নম্বর দেখে নিন", "bad_no") // bn-ok
        jdbc.update(
            """
            INSERT INTO invoice_series (kind, biz_id, series, last_seq) VALUES (?, ?, ?, ?)
            ON CONFLICT (kind, biz_id, series) DO UPDATE SET last_seq = GREATEST(invoice_series.last_seq, EXCLUDED.last_seq)
            """.trimIndent(),
            b.kind, b.id, series, seq,
        )
        if (jdbc.queryForList("SELECT 1 FROM invoices WHERE kind = ? AND biz_id = ? AND no = ?", b.kind, b.id, InvoiceMath.no(series, seq)).isNotEmpty()) {
            throw err(lang, 409, "This receipt number is already used", "এই রসিদ নম্বর আগেই ব্যবহার হয়েছে", "no_taken") // bn-ok
        }
        return series to seq
    }

    private fun insertPayment(invoiceId: String, at: Long, p: PaymentIn, by: String?, deviceId: String?) {
        jdbc.update(
            "INSERT INTO invoice_payments (invoice_id, at, amount, method, ref, by_uid, device_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
            invoiceId, Sql.ts(at), InvoiceMath.m2(p.amount), p.method, p.ref, by, deviceId,
        )
    }

    private fun event(invoiceId: String, action: String, by: String?, deviceId: String?, detail: Map<String, Any?>) {
        jdbc.update(
            "INSERT INTO invoice_events (invoice_id, action, by_uid, device_id, detail) VALUES (?, ?, ?, ?, ?::jsonb)",
            invoiceId, action, by, deviceId, mapper.writeValueAsString(detail),
        )
    }

    private fun afterCommit(block: () -> Unit) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() = block()
            })
        } else {
            block()
        }
    }

    /** invoice_new to the patient (and their caregivers — pushPatient) */
    private fun notifyPatient(inv: Invoice) {
        // no push for a feature that is off for the patient (my_expenses + this kind)
        if (!features.isOn(inv.patientUid, InvoiceFeatures.MY_EXPENSES) || !features.kindOn(inv.patientUid, inv.kind)) return
        try {
            val total = InvoiceMath.num(inv.total).toString()
            // an ambulance fare above what was agreed says so (security review I8)
            val over = if (inv.kind == "ambulance" && inv.overFare == true && inv.chartFare != null) " — রাজি হয়েছিলেন ৳${inv.chartFare}-এ" else "" // bn-ok
            push.pushPatient(
                inv.patientUid, "New receipt from ${inv.bizName}", "${inv.bizName} থেকে নতুন রসিদ", // bn-ok
                "${inv.no} · ৳$total$over", // bn-ok
                mapOf("type" to "invoice_new", "cat" to NotifyCats.MY_REQUEST, "invoiceId" to inv.id),
            )
        } catch (e: Exception) {
            log.warn("invoice push failed: {}", e.message)
        }
    }

    // ------------------------------------------------------------------ read

    private fun mapRow(rs: ResultSet, payments: List<Payment>): Invoice {
        val lines = mapper.readTree(rs.getString("lines")).map { l ->
            Line(
                l.get("name").asText(), BigDecimal(l.get("qty").asText()), l.get("unit")?.asText(), BigDecimal(l.get("price").asText()),
                BigDecimal(l.get("disc").asText()), BigDecimal(l.get("amount").asText()), l.get("code")?.asText(),
            )
        }
        val fees = mapper.readTree(rs.getString("fees")).map { f -> Fee(f.get("label").asText(), BigDecimal(f.get("amount").asText())) }
        return Invoice(
            id = rs.getString("id"), no = rs.getString("no"), kind = rs.getString("kind"), bizId = rs.getString("biz_id"),
            ownerUid = rs.getString("owner_uid"), bizName = rs.getString("biz_name"), bizPhone = rs.getString("biz_phone"),
            bizAddress = rs.getString("biz_address"), patientUid = rs.getString("patient_uid"),
            customer = Customer(rs.getString("customer_name"), rs.getString("customer_phone")),
            refType = rs.getString("ref_type"), refId = rs.getString("ref_id"), lines = lines,
            subtotal = rs.getBigDecimal("subtotal"), disc = rs.getBigDecimal("disc"), fees = fees, total = rs.getBigDecimal("total"),
            paid = rs.getBigDecimal("paid"), due = rs.getBigDecimal("due"), payments = payments, status = rs.getString("status"),
            voidReason = rs.getString("void_reason"), voidNote = rs.getString("void_note"), voidedAt = rs.ms("voided_at"), issuedAt = rs.ms("issued_at")!!, issuedBy = rs.getString("issued_by"),
            deviceId = rs.getString("device_id"), deviceNo = rs.getString("device_no"), offline = rs.getBoolean("offline"), verifyCode = rs.getString("verify_code"),
            overFare = rs.getObject("over_fare") as Boolean?, chartFare = rs.getObject("chart_fare") as Int?, isDemo = rs.getBoolean("is_demo"),
        )
    }

    private fun paymentsOf(ids: List<String>): Map<String, List<Payment>> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<String, MutableList<Payment>>()
        jdbc.query(
            "SELECT invoice_id, at, amount, method, ref FROM invoice_payments WHERE invoice_id IN (${ids.joinToString(",") { "?" }}) ORDER BY at, id",
            RowCallbackHandler { rs ->
                out.getOrPut(rs.getString(1)) { ArrayList() }
                    .add(Payment(rs.ms("at")!!, rs.getBigDecimal("amount"), rs.getString("method"), rs.getString("ref")))
            },
            *ids.toTypedArray(),
        )
        return out
    }

    /** rows of a query on `invoices` (with their payments) */
    fun query(where: String, vararg args: Any?): List<Invoice> {
        val rows = jdbc.query("SELECT * FROM invoices WHERE $where", { rs, _ -> mapRow(rs, emptyList()) }, *args)
        val pays = paymentsOf(rows.map { it.id })
        return rows.map { it.copy(payments = pays[it.id] ?: emptyList()) }
    }

    fun load(id: String?, lock: Boolean = false): Invoice? {
        if (id == null || !Rules.isValidId(id)) return null
        return query("id = ?${if (lock) " FOR UPDATE" else ""}", id).firstOrNull()
    }

    /** by number: the server number first, else the number the device printed (renumbered receipts) */
    fun findByNo(kind: String, bizId: String, no: String): Invoice? =
        query("kind = ? AND biz_id = ? AND (no = ? OR device_no = ?) ORDER BY (no = ?) DESC LIMIT 1", kind, bizId, no, no, no).firstOrNull()

    /** the live (not void) invoice of a source, e.g. a lab order */
    fun liveFor(refType: String, refId: String): Invoice? = query("ref_type = ? AND ref_id = ? AND status = 'issued'", refType, refId).firstOrNull()

    // ------------------------------------------------------------------ pay / void

    /** the issuer of an invoice: the business account (or a doctor's assistant) */
    private fun requireIssuerOf(lang: String?, actor: AuthUser, inv: Invoice) {
        if (roleOf(actor, inv.kind, inv.bizId, inv.ownerUid) == null) throw notFound(lang)
    }

    @Transactional
    fun pay(lang: String?, actor: AuthUser, invoiceId: String, payment: PaymentIn, stamp: DeviceStamp? = null): Invoice {
        val inv = load(invoiceId, lock = true) ?: throw notFound(lang)
        requireIssuerOf(lang, actor, inv)
        features.requireKind(lang, inv.ownerUid, inv.kind)
        if (inv.status == "void") throw err(lang, 409, "This receipt is void", "এই রসিদ বাতিল", "void") // bn-ok
        if (inv.due.signum() == 0) throw err(lang, 409, "Nothing is due on this receipt", "এই রসিদে কোনো বাকি নেই", "nothing_due") // bn-ok
        val amount = InvoiceMath.m2(payment.amount)
        if (amount.signum() <= 0) throw InvoiceMath.bad(lang, "Check the amount", "টাকার পরিমাণ দেখে নিন") // bn-ok
        if (amount > inv.due) throw InvoiceMath.bad(lang, "Payment is more than the due", "পরিশোধ বাকির চেয়ে বেশি", "overpaid") // bn-ok
        val at = stamp?.at ?: System.currentTimeMillis()
        jdbc.update("UPDATE invoices SET paid = paid + ?, due = due - ? WHERE id = ?", amount, amount, inv.id)
        insertPayment(inv.id, at, payment.copy(amount = amount), actor.id, stamp?.deviceId)
        event(inv.id, "pay", actor.id, stamp?.deviceId, mapOf("amount" to InvoiceMath.num(amount), "method" to payment.method))
        return load(inv.id)!!
    }

    /**
     * invoice_void: a reason from [InvoiceMath.VOID_REASONS] (+ a note; "other" needs one). The owner (doctor) may void
     * any receipt; a doctor's assistant only their own and within 2 hours — and the doctor is told (security review I7).
     */
    @Transactional
    fun void(lang: String?, actor: AuthUser, invoiceId: String, reason: String, note: String? = null, stamp: DeviceStamp? = null): Invoice {
        val inv = load(invoiceId, lock = true) ?: throw notFound(lang)
        val role = roleOf(actor, inv.kind, inv.bizId, inv.ownerUid) ?: throw notFound(lang)
        features.requireKind(lang, inv.ownerUid, inv.kind)
        if (reason !in InvoiceMath.VOID_REASONS) throw InvoiceMath.bad(lang, "Choose why it is void", "বাতিলের কারণ বেছে নিন", "reason") // bn-ok
        val text = Rules.cleanText(note, 200)
        if (reason == "other" && text == null) throw InvoiceMath.bad(lang, "Write why it is void", "বাতিলের কারণ লিখুন", "reason") // bn-ok
        if (inv.status == "void") throw err(lang, 409, "This receipt is already void", "এই রসিদ আগেই বাতিল", "already_void") // bn-ok
        val at = stamp?.at ?: System.currentTimeMillis()
        if (role == "assistant" && (inv.issuedBy != actor.uid || at - inv.issuedAt > ASSISTANT_VOID_MS)) {
            throw err(
                lang, 403, "An assistant can void only their own receipts, within 2 hours — ask the doctor",
                "সহকারী শুধু নিজের দেওয়া রসিদ ২ ঘণ্টার মধ্যে বাতিল করতে পারেন — ডাক্তারকে বলুন", "assistant_void", // bn-ok
            )
        }
        voidHooks.orderedStream().forEach { it.onVoid(lang, inv) }
        jdbc.update(
            "UPDATE invoices SET status = 'void', void_reason = ?, void_note = ?, voided_at = ?, voided_by = ? WHERE id = ?",
            reason, text, Sql.ts(at), actor.id, inv.id,
        )
        event(inv.id, "void", actor.id, stamp?.deviceId, mapOf("reason" to reason, "note" to text, "role" to role))
        if (role == "assistant" && inv.ownerUid != null) {
            val label = InvoiceMath.VOID_REASONS[reason]
            afterCommit {
                push.pushUsers(listOf(inv.ownerUid)) { _, l ->
                    com.lifolo.notify.PushText(
                        Lang.pick(l, "Your assistant voided receipt ${inv.no}", "সহকারী রসিদ ${inv.no} বাতিল করেছেন"), // bn-ok
                        "৳${InvoiceMath.num(inv.total)} · $label${if (text != null) " · $text" else ""}", // bn-ok
                        mapOf("type" to "invoice_void", "invoiceId" to inv.id, "kind" to inv.kind, "bizId" to inv.bizId),
                    )
                }
            }
        }
        return load(inv.id)!!
    }

    // ------------------------------------------------------------------ actions' reads

    /** invoice_get: the issuer, the linked patient, or that patient's caregiver (asUid → the actor is the patient) */
    fun get(lang: String?, actor: AuthUser, id: String?): Invoice {
        val inv = load(id) ?: throw notFound(lang)
        val patient = inv.patientUid != null && inv.patientUid == actor.uid
        val issuer = roleOf(actor, inv.kind, inv.bizId, inv.ownerUid) != null
        if (!patient && !issuer) throw notFound(lang)
        // a caregiver: not a psychologist's receipt unless the patient shares those (backend/docs/PRACTICE.md)
        if (!issuer && inv.kind == "chamber" && privacy.hidesPrivate(actor) && psychologist(inv.bizId)) throw notFound(lang)
        if (issuer) features.requireKind(lang, inv.ownerUid, inv.kind)
        if (!issuer) {
            features.require(lang, actor.uid, InvoiceFeatures.MY_EXPENSES)
            features.requireKind(lang, actor.uid, inv.kind)
        }
        return inv
    }

    fun verify(lang: String?, code: String?, ipKey: String): Map<String, Any?> {
        if (!rate.tryAcquire("inv-verify:$ipKey", 30, Duration.ofMinutes(1))) {
            throw err(lang, 429, "Too many requests. Wait a minute and try again.", "অনেক বেশি অনুরোধ। এক মিনিট পর আবার চেষ্টা করুন।")
        }
        if (code == null || !Regex("^[A-Za-z0-9]{10}$").matches(code)) throw notFound(lang)
        val inv = query("verify_code = ?", code).firstOrNull()
        if (inv == null) {
            // purged after the 6 years: the page still answers (never "not found" for a real code)
            return jdbc.query(
                "SELECT * FROM invoice_tombstones WHERE verify_code = ?",
                { rs, _ ->
                    linkedMapOf<String, Any?>(
                        "bizName" to rs.getString("biz_name"), "kind" to rs.getString("kind"), "no" to rs.getString("no"),
                        "date" to rs.ms("issued_at"), "total" to InvoiceMath.num(rs.getBigDecimal("total")), "lines" to rs.getInt("line_count"),
                        "status" to rs.getString("status"), "archived" to true, "demo" to false, "footer" to InvoiceMath.FOOTER,
                    )
                },
                code,
            ).firstOrNull() ?: throw notFound(lang)
        }
        val b = if (inv.ownerUid != null) biz(inv.kind, inv.bizId) else null
        return inv.publicView(sanctioned = b != null && (b.status == "suspended" || banned(b)))
    }

    private fun periodStart(period: String, now: Long): Long {
        val today = DhakaTime.day(now)
        val start: LocalDate = when (period) {
            "today" -> today
            "30d" -> today.minusDays(29)
            "month" -> today.withDayOfMonth(1)
            "365d" -> today.minusDays(364)
            else -> today.minusDays(6)
        }
        return start.atStartOfDay(DhakaTime.OFFSET).toInstant().toEpochMilli()
    }

    private fun cursorOf(inv: Invoice) = "${inv.issuedAt}_${inv.id}"

    private fun cursorArgs(cursor: String?): Pair<Long, String>? {
        val m = Regex("^([0-9]{1,15})_([A-Za-z0-9_-]{1,64})$").matchEntire(cursor ?: return null) ?: return null
        return m.groupValues[1].toLong() to m.groupValues[2]
    }

    /** invoice_list_biz: the business's receipts of a period, newest first, 50 a page; sums over the whole period (not void) */
    fun listBiz(lang: String?, actor: AuthUser, kind: String?, bizId: String?, period: String?, q: String?, cursor: String?): Map<String, Any?> {
        val b = requireIssuer(lang, actor, kind, bizId)
        features.requireKind(lang, b.ownerUid, b.kind)
        val now = System.currentTimeMillis()
        val p = period?.takeIf { it in PERIODS } ?: "7d"
        val where = StringBuilder("kind = ? AND biz_id = ? AND issued_at >= ?")
        val args = arrayListOf<Any?>(b.kind, b.id, Sql.ts(periodStart(p, now)))
        val text = q?.trim()?.take(40)?.takeIf { it.isNotEmpty() }
        if (text != null) {
            val like = "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            // a phone finds only receipts of the last 30 days (after that the number is masked — no confirmation oracle)
            where.append(" AND (no ILIKE ? OR customer_name ILIKE ? OR (customer_phone LIKE ? AND issued_at > ?))")
            args.addAll(listOf(like, like, like, Sql.ts(now - MASK_AFTER_MS)))
        }
        val sums = jdbc.queryForMap(
            "SELECT count(*) AS n, coalesce(sum(total), 0) AS total, coalesce(sum(paid), 0) AS paid, coalesce(sum(due), 0) AS due FROM invoices WHERE $where AND status = 'issued'",
            *args.toTypedArray(),
        )
        val c = cursorArgs(cursor)
        if (c != null) {
            where.append(" AND (issued_at, id) < (?, ?)")
            args.addAll(listOf(Sql.ts(c.first), c.second))
        }
        val rows = query("$where ORDER BY issued_at DESC, id DESC LIMIT ${BIZ_PAGE + 1}", *args.toTypedArray())
        val page = rows.take(BIZ_PAGE)
        return linkedMapOf(
            "items" to page.map { it.toMap(maskPhone = now - it.issuedAt > MASK_AFTER_MS) },
            "cursor" to if (rows.size > BIZ_PAGE) cursorOf(page.last()) else null,
            "sum" to linkedMapOf(
                "count" to (sums["n"] as Number).toInt(), "total" to InvoiceMath.num(sums["total"] as BigDecimal),
                "paid" to InvoiceMath.num(sums["paid"] as BigDecimal), "due" to InvoiceMath.num(sums["due"] as BigDecimal),
            ),
        )
    }

    /** my_invoices: the patient's receipts (the actor is the patient — or a caregiver acting through asUid) */
    fun mine(lang: String?, actor: AuthUser, cursor: String?): Map<String, Any?> {
        val uid = actor.id
        features.require(lang, uid, InvoiceFeatures.MY_EXPENSES)
        val kinds = kindsOn(uid)
        val c = cursorArgs(cursor)
        val hide = if (privacy.hidesPrivate(actor)) " AND $NOT_PSYCH" else ""
        val rows = if (c == null) {
            query("patient_uid = ? AND kind = ANY (?::text[])$hide ORDER BY issued_at DESC, id DESC LIMIT ${PAGE + 1}", uid, kinds)
        } else {
            query("patient_uid = ? AND kind = ANY (?::text[])$hide AND (issued_at, id) < (?, ?) ORDER BY issued_at DESC, id DESC LIMIT ${PAGE + 1}", uid, kinds, Sql.ts(c.first), c.second)
        }
        val page = rows.take(PAGE)
        return linkedMapOf("items" to page.map { it.toMap() }, "cursor" to if (rows.size > PAGE) cursorOf(page.last()) else null)
    }

    /** the receipt kinds switched on for [uid], as a PostgreSQL text[] literal */
    private fun kindsOn(uid: String?): String = InvoiceMath.KINDS.filter { features.kindOn(uid, it) }.joinToString(",", "{", "}")

    /** my_expenses: a Dhaka month's receipts (not void) of the kinds that are on → {month, total, byKind, count} */
    fun expenses(lang: String?, actor: AuthUser, month: String?): Map<String, Any?> {
        features.require(lang, actor.uid, InvoiceFeatures.MY_EXPENSES)
        val ym = monthOf(lang, month)
        val from = ym.atDay(1).atStartOfDay(DhakaTime.OFFSET).toInstant().toEpochMilli()
        val to = ym.plusMonths(1).atDay(1).atStartOfDay(DhakaTime.OFFSET).toInstant().toEpochMilli()
        val byKind = linkedMapOf<String, Any?>()
        InvoiceMath.KINDS.filter { features.kindOn(actor.uid, it) }.forEach { byKind[it] = 0 }
        var total = BigDecimal.ZERO
        var count = 0
        jdbc.query(
            """
            SELECT kind, count(*) AS n, sum(total) AS t FROM invoices
            WHERE patient_uid = ? AND status = 'issued' AND issued_at >= ? AND issued_at < ? AND kind = ANY (?::text[])
            ${if (privacy.hidesPrivate(actor)) "AND $NOT_PSYCH" else ""} GROUP BY kind
            """.trimIndent(),
            RowCallbackHandler { rs ->
                val t = rs.getBigDecimal("t")
                byKind[rs.getString("kind")] = InvoiceMath.num(t)
                total += t
                count += rs.getInt("n")
            },
            actor.id, Sql.ts(from), Sql.ts(to), kindsOn(actor.uid),
        )
        return linkedMapOf("month" to ym.toString(), "total" to InvoiceMath.num(total), "byKind" to byKind, "count" to count)
    }

    /**
     * invoice_export_csv {kind, bizId, month}: the owner's receipts of a Dhaka month for the accountant (security review
     * I15) — no customer phone, no VAT columns. UTF-8 with a BOM (Excel).
     */
    fun exportCsv(lang: String?, actor: AuthUser, kind: String?, bizId: String?, month: String?): Map<String, Any?> {
        val b = requireOwner(lang, actor, kind, bizId)
        features.requireKind(lang, b.ownerUid, b.kind)
        val ym = monthOf(lang, month)
        val from = ym.atDay(1).atStartOfDay(DhakaTime.OFFSET).toInstant().toEpochMilli()
        val to = ym.plusMonths(1).atDay(1).atStartOfDay(DhakaTime.OFFSET).toInstant().toEpochMilli()
        val rows = query("kind = ? AND biz_id = ? AND issued_at >= ? AND issued_at < ? ORDER BY issued_at, id LIMIT 20000", b.kind, b.id, Sql.ts(from), Sql.ts(to))
        fun cell(v: Any?): String {
            val t = v?.toString() ?: ""
            // no formula injection in spreadsheets; quotes doubled
            val safe = if (t.isNotEmpty() && t[0] in "=+-@\t") "'$t" else t
            return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
        }
        val sb = StringBuilder("\uFEFF")
        sb.append("no,date,status,customer,lines,subtotal,discount,fees,total,paid,due,methods,void_reason,device\r\n")
        for (inv in rows) {
            val date = java.time.Instant.ofEpochMilli(inv.issuedAt).atOffset(DhakaTime.OFFSET).toLocalDateTime().toString().take(16).replace('T', ' ')
            sb.append(
                listOf(
                    inv.no, date, inv.status, inv.customer.name, inv.lines.size, InvoiceMath.num(inv.subtotal), InvoiceMath.num(inv.disc),
                    InvoiceMath.num(inv.fees.fold(BigDecimal.ZERO) { a, f -> a + f.amount }), InvoiceMath.num(inv.total), InvoiceMath.num(inv.paid),
                    InvoiceMath.num(inv.due), inv.payments.map { it.method }.distinct().joinToString("+"), inv.voidReason, inv.deviceNo ?: inv.deviceId,
                ).joinToString(",") { cell(it) },
            )
            sb.append("\r\n")
        }
        return linkedMapOf("filename" to "lifolo-${b.kind}-$ym.csv", "mime" to "text/csv", "count" to rows.size, "csv" to sb.toString())
    }

    private fun monthOf(lang: String?, month: String?): YearMonth {
        if (month.isNullOrEmpty()) return YearMonth.from(DhakaTime.day())
        if (!Regex("^20[0-9]{2}-(0[1-9]|1[0-2])$").matches(month)) throw InvoiceMath.bad(lang, "Check the month", "মাস দেখে নিন") // bn-ok
        return YearMonth.parse(month)
    }

    /**
     * invoice_report {id, paid?, note?}: the patient says the business took more than the receipt shows ("রসিদের চেয়ে বেশি
     * নিয়েছে", security review I8). Once per receipt, within 30 days; an ambulance's overcharge count goes up (the
     * listing review sees it).
     */
    @Transactional
    fun report(lang: String?, actor: AuthUser, id: String?, paidNode: com.fasterxml.jackson.databind.JsonNode?, note: String?): Map<String, Any?> {
        features.require(lang, actor.uid, InvoiceFeatures.MY_EXPENSES)
        val inv = load(id, lock = true) ?: throw notFound(lang)
        if (inv.patientUid == null || inv.patientUid != actor.uid) throw notFound(lang)
        if (System.currentTimeMillis() - inv.issuedAt > 30L * 24 * 3600 * 1000) {
            throw err(lang, 409, "This receipt is too old to report", "এই রসিদ অনেক পুরোনো — এখন জানানো যায় না", "too_old") // bn-ok
        }
        val paid = InvoiceMath.money(lang, paidNode, "amount", "টাকার পরিমাণ") // bn-ok
        if (paid != null && paid <= inv.total) throw InvoiceMath.bad(lang, "Write how much you really paid", "আসলে কত দিয়েছেন লিখুন", "paid") // bn-ok
        val n = jdbc.update(
            "INSERT INTO invoice_reports (invoice_id, uid, paid, note) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
            inv.id, actor.id, paid, Rules.cleanText(note, 300),
        )
        if (n == 0) throw err(lang, 409, "You already reported this receipt", "এই রসিদ নিয়ে আগেই জানিয়েছেন", "already_reported") // bn-ok
        if (inv.kind == "ambulance") jdbc.update("UPDATE ambulances SET overcharge_count = overcharge_count + 1 WHERE id = ?", inv.bizId)
        event(inv.id, "report", actor.actingBy ?: actor.id, null, mapOf("paid" to paid?.let { InvoiceMath.num(it) }))
        return linkedMapOf("ok" to true)
    }

    // ------------------------------------------------------------------ retention (account deletion)

    /**
     * The account is deleted: demo accounts' receipts go; real ones are kept 6 years with the personal fields removed —
     * as the patient (link, customer name/phone, payment refs) and as the business (owner link, who issued, the business
     * phone; the customer fields too, nobody can open them any more).
     */
    @Transactional
    fun forget(uid: String, nowMs: Long = System.currentTimeMillis()): Int {
        if (DemoWorld.isDemoUid(uid)) {
            // its numbering too (every receipt of its businesses goes with it); runs before the ambulances are removed
            jdbc.update(
                "DELETE FROM invoice_series WHERE biz_id = ? OR biz_id IN (SELECT id FROM ambulances WHERE owner_uid = ?) OR biz_id IN (SELECT biz_id FROM invoices WHERE owner_uid = ?)",
                uid, uid, uid,
            )
            return jdbc.update("DELETE FROM invoices WHERE patient_uid = ? OR owner_uid = ? OR issued_by = ?", uid, uid, uid)
        }
        val keep = Sql.ts(nowMs + RETAIN_MS)
        val ids = jdbc.queryForList(
            "SELECT id FROM invoices WHERE patient_uid = ? OR owner_uid = ? OR issued_by = ? OR voided_by = ? FOR UPDATE",
            String::class.java, uid, uid, uid, uid,
        )
        if (ids.isEmpty()) return 0
        val marks = ids.joinToString(",") { "?" }
        jdbc.update(
            """
            UPDATE invoices SET patient_uid = NULL, customer_name = NULL, customer_phone = NULL, void_note = NULL,
              anonymised_at = coalesce(anonymised_at, now()), purge_after = GREATEST(coalesce(purge_after, ?), ?)
            WHERE id IN ($marks) AND patient_uid = ?
            """.trimIndent(),
            keep, keep, *ids.toTypedArray(), uid,
        )
        jdbc.update(
            """
            UPDATE invoices SET owner_uid = NULL, issued_by = NULL, biz_phone = NULL, customer_name = NULL, customer_phone = NULL,
              voided_by = NULL, void_note = NULL, anonymised_at = coalesce(anonymised_at, now()), purge_after = GREATEST(coalesce(purge_after, ?), ?)
            WHERE id IN ($marks) AND owner_uid = ?
            """.trimIndent(),
            keep, keep, *ids.toTypedArray(), uid,
        )
        jdbc.update("UPDATE invoices SET issued_by = NULL WHERE id IN ($marks) AND issued_by = ?", *ids.toTypedArray(), uid)
        jdbc.update("UPDATE invoices SET voided_by = NULL WHERE id IN ($marks) AND voided_by = ?", *ids.toTypedArray(), uid)
        jdbc.update("UPDATE invoice_payments SET ref = NULL, by_uid = NULL WHERE invoice_id IN ($marks)", *ids.toTypedArray())
        jdbc.update("UPDATE invoice_events SET by_uid = NULL WHERE by_uid = ? AND invoice_id IN ($marks)", uid, *ids.toTypedArray())
        jdbc.update(
            "UPDATE invoices SET anonymised_at = coalesce(anonymised_at, now()), purge_after = GREATEST(coalesce(purge_after, ?), ?) WHERE id IN ($marks)",
            keep, keep, *ids.toTypedArray(),
        )
        for (id in ids) event(id, "anonymise", null, null, emptyMap())
        return ids.size
    }

    /** the 6 years are over */
    @Transactional
    fun purge(nowMs: Long): Int {
        jdbc.update(
            """
            INSERT INTO invoice_tombstones (verify_code, kind, biz_name, no, issued_at, total, line_count, status)
            SELECT verify_code, kind, biz_name, no, issued_at, total, jsonb_array_length(lines), CASE WHEN status = 'void' THEN 'void' ELSE 'valid' END
            FROM invoices WHERE purge_after IS NOT NULL AND purge_after <= ? AND NOT is_demo
            ON CONFLICT (verify_code) DO NOTHING
            """.trimIndent(),
            Sql.ts(nowMs),
        )
        return jdbc.update("DELETE FROM invoices WHERE purge_after IS NOT NULL AND purge_after <= ?", Sql.ts(nowMs))
    }

    private val RETAIN_MS: Long get() = RETAIN_YEARS * 365L * 24 * 3600 * 1000 + 2L * 24 * 3600 * 1000
}

/** 6 years (accounting), counted from the account deletion */
const val RETAIN_YEARS = 6L
