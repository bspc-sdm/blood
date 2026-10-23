package com.lifolo.appointments

import com.lifolo.common.Rules
import com.lifolo.common.Sql
import com.lifolo.common.action.ActionContext
import com.lifolo.common.action.ActionHandler
import com.lifolo.common.action.ActionModule
import com.lifolo.demo.DemoWorld
import com.lifolo.notify.NotifyCats
import com.lifolo.notify.PushService
import com.lifolo.auth.AdminService
import com.lifolo.common.Ids
import com.lifolo.common.QuotaService
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * The fee of an online (video) chamber appointment (V620, docs/CHAMBERS-ONLINE.md). No money goes through Lifolo: the
 * patient sends the fee to the doctor's own bKash number (shown after booking) and says so; the doctor or an assistant
 * marks it received. The doctor may still see an unpaid patient (his choice).
 *
 *  - `appt_pay_claim {appointmentId, payRef?}` — patient (or caregiver): unpaid/claimed → claimed (+ bKash TrxID);
 *    pushes the doctor and the assistants "রোগী ফি পাঠিয়েছেন — যাচাই করুন"
 *  - `appt_pay_confirm {appointmentId}` — doctor or assistant: unpaid/claimed → paid; pushes the patient "ডাক্তার ফি পেয়েছেন"
 *  - `appt_pay_reject {appointmentId, reason?}` — doctor or assistant: claimed → unpaid ("পাইনি"); pushes the patient
 */
@Component
class OnlinePay(
    private val jdbc: JdbcTemplate,
    private val appts: ApptRepo,
    private val badges: BadgeLookup,
    private val assistants: ProviderAssistantsStore,
    private val users: P2Users,
    private val push: PushService,
    private val clock: P2Clock,
    private val payLog: ApptPayLog,
    private val quotas: QuotaService,
    private val admins: AdminService,
    private val onlineSwitch: OnlineSwitch,
    private val privacy: com.lifolo.practice.PracticePrivacy,
    private val pay: com.lifolo.payments.PayService,
    txm: PlatformTransactionManager,
) : ActionModule {
    private val tx = TransactionTemplate(txm)

    override fun actions(): Map<String, ActionHandler> = mapOf(
        "appt_pay_claim" to ActionHandler(::claim),
        "appt_pay_confirm" to ActionHandler(::confirm),
        "appt_pay_reject" to ActionHandler(::reject),
        "appt_report" to ActionHandler(::report),
        "admin_appt_reports" to ActionHandler(::adminReports),
        "admin_appt_report_set" to ActionHandler(::adminReportSet),
    )

    companion object {
        const val HOUR = 60L * 60 * 1000
        const val MAX_CLAIMS_PER_HOUR = 5
    }

    private fun notFound(ctx: ActionContext) = ctx.err(404, "Not found", "পাওয়া যায়নি")

    private fun load(ctx: ActionContext): Appt {
        val id = ctx.body.string("appointmentId")
        if (!Rules.isValidId(id)) throw notFound(ctx)
        val a = appts.get(id!!) ?: throw notFound(ctx)
        // demo and real accounts never meet (an appointment never mixes them; a stray id answers like a missing one)
        if (!DemoWorld.sameWorld(ctx.user.id, a.providerUid)) throw notFound(ctx)
        // a caregiver: not a psychologist appointment the patient did not share (backend/docs/PRACTICE.md)
        if (privacy.hidden(ctx.user, a.type)) throw notFound(ctx)
        return a
    }

    /** online, and a fee to pay */
    private fun requirePayable(ctx: ActionContext, a: Appt) {
        if (!a.online) throw ctx.err(409, "This is not an online appointment", "এটা অনলাইন অ্যাপয়েন্টমেন্ট নয়", "not_online") // bn-ok
        if (a.payState == null) throw ctx.err(409, "No fee for this appointment", "এই অ্যাপয়েন্টমেন্টে ফি লাগে না", "no_fee") // bn-ok
    }

    private fun stateErr(ctx: ActionContext) = ctx.err(409, "This can't be changed now", "এখন এটা বদলানো যায় না", "state")

    /** the doctor of the appointment or one of the doctor's assistants (verified email); else 404 */
    private fun requireDoctorSide(ctx: ActionContext, a: Appt): String {
        val user = ctx.user
        // a caregiver acts on the patient side only
        if (user.patientSideOnly) throw notFound(ctx)
        if (a.providerUid == user.id) return "doctor"
        val email = if (user.emailVerified) user.email.lowercase() else ""
        if (email.isEmpty() || !assistants.isAssistant(a.providerUid, email)) throw notFound(ctx)
        if (badges.approvedBadge(a.providerUid) !in com.lifolo.practice.Practice.ASSISTED_TYPES) throw ctx.err(403, "This doctor is not verified now", "এই ডাক্তার এখন যাচাই করা নেই")
        return "assistant"
    }

    /** the uids of the doctor's assistants (accounts with an assistant email, same world), not the doctor */
    private fun assistantUids(doctorUid: String): List<String> {
        val emails = assistants.emails(doctorUid).map { it.lowercase() }.distinct()
        if (emails.isEmpty()) return emptyList()
        return jdbc.queryForList(
            "SELECT uid FROM accounts WHERE lower(email) IN (${emails.joinToString(",") { "?" }}) AND deactivated_at IS NULL ORDER BY uid LIMIT 20",
            String::class.java, *emails.toTypedArray(),
        ).filter { it != doctorUid && DemoWorld.sameWorld(it, doctorUid) }
    }

    /** current pay state under a row lock (null when the appointment is gone) */
    private fun lockState(id: String): Pair<String, String?>? =
        jdbc.query("SELECT status, pay_state FROM appointments WHERE id = ? FOR UPDATE", { rs, _ -> rs.getString(1) to rs.getString(2) }, id).firstOrNull()

    // ------------------------------------------------------------------ patient: "টাকা পাঠিয়েছি"

    fun claim(ctx: ActionContext): Any {
        onlineSwitch.require(ctx)
        val a = load(ctx)
        if (a.patientUid != ctx.user.id) throw notFound(ctx)
        requirePayable(ctx, a)
        val ref = CareRules.payRef(ctx.body.string("payRef"))
        if (ref.error != null) throw ctx.err(400, ref.error.en, ref.error.bn)
        // Lifolo Pay: the chamber_fee request and the appointment change together (rate limits, TrxID once per doctor)
        val (changed, payRef) = pay.legacyClaim(ctx.lang, a.id, ctx.user.id, ctx.caller.id, ref.value, clock.now())
        if (changed) {
            val body = "${a.patientName} · ${ApptRepo.dateLabel(a.date)}${if (payRef != null) " · TrxID $payRef" else ""}"
            val data = mapOf("type" to "appointment", "role" to "provider", "id" to a.id, "cat" to NotifyCats.APPOINTMENTS, "payRequestId" to pay.latestFor("appointment", a.id, "chamber_fee")?.id)
            // only people the online_chamber switch is on for (FEATURES.md)
            for (to in (listOf(a.providerUid) + assistantUids(a.providerUid)).filter { onlineSwitch.allowed(it) }) {
                users.push(to, "Patient sent the fee — please check", "রোগী ফি পাঠিয়েছেন — যাচাই করুন", body, data) // bn-ok
            }
        }
        return linkedMapOf("ok" to true, "payState" to "claimed", "payRef" to payRef)
    }

    // ------------------------------------------------------------------ doctor/assistant: "ফি পেয়েছি" / "পাইনি"

    fun confirm(ctx: ActionContext): Any {
        onlineSwitch.require(ctx)
        val a = load(ctx)
        requireDoctorSide(ctx, a)
        requirePayable(ctx, a)
        // Lifolo Pay: the request is confirmed with the appointment; the receipt follows (a receipt problem never undoes it)
        val (changed, invoice) = pay.legacyConfirm(ctx.lang, ctx.user, a.id, ctx.body["checks"], clock.now())
        if (changed && onlineSwitch.allowed(a.patientUid)) {
            push.pushPatient(
                a.patientUid, "The doctor received the fee", "ডাক্তার ফি পেয়েছেন", // bn-ok
                "${a.providerName} · ${ApptRepo.dateLabel(a.date)}",
                mapOf("type" to "appointment", "role" to "patient", "id" to a.id, "cat" to NotifyCats.APPOINTMENTS, "payRequestId" to pay.latestFor("appointment", a.id, "chamber_fee")?.id),
            )
        }
        return linkedMapOf("ok" to true, "payState" to "paid", "invoice" to invoice?.toMap())
    }

    fun reject(ctx: ActionContext): Any {
        onlineSwitch.require(ctx)
        val a = load(ctx)
        requireDoctorSide(ctx, a)
        requirePayable(ctx, a)
        val reason = Rules.cleanText(ctx.body.string("reason"), 200)
        pay.legacyReject(ctx.lang, ctx.user, a.id, reason, clock.now())
        if (onlineSwitch.allowed(a.patientUid)) push.pushPatient(
            a.patientUid, "The doctor has not received the fee yet", "ডাক্তার এখনো ফি পাননি — বিকাশ লেনদেনটা আবার দেখুন", // bn-ok
            "${a.providerName} · ${ApptRepo.dateLabel(a.date)}${if (reason != null) " · $reason" else ""}",
            mapOf("type" to "appointment", "role" to "patient", "id" to a.id, "cat" to NotifyCats.APPOINTMENTS, "payRequestId" to pay.latestFor("appointment", a.id, "chamber_fee")?.id),
        )
        return linkedMapOf("ok" to true, "payState" to "unpaid", "payNote" to reason)
    }

    // ------------------------------------------------------------------ R8: appt_report (patient or doctor side) → admins

    fun report(ctx: ActionContext): Any {
        onlineSwitch.require(ctx)
        val a = load(ctx)
        val user = ctx.user
        val role = when {
            a.patientUid == user.id -> "patient"
            user.patientSideOnly -> throw notFound(ctx)
            else -> requireDoctorSide(ctx, a)
        }
        val reason = Rules.cleanText(ctx.body.string("reason"), 300) ?: throw ctx.err(400, "Write what happened", "কী হয়েছে লিখুন")
        quotas.use(ctx.lang, user.id, "report")
        // one open report per person per appointment (a new one replaces the text)
        val id = "ar_" + Ids.sha256Hex("${a.id}|${user.id}").take(20)
        jdbc.update(
            """
            INSERT INTO appt_reports (id, appointment_id, reporter_uid, reporter_role, reason, status, created_at)
            VALUES (?, ?, ?, ?, ?, 'open', ?)
            ON CONFLICT (id) DO UPDATE SET reason = EXCLUDED.reason, status = 'open', created_at = EXCLUDED.created_at,
              resolved_at = NULL, resolved_by = NULL
            """.trimIndent(),
            id, a.id, user.id, role, reason, Sql.ts(clock.now()),
        )
        return linkedMapOf("ok" to true, "id" to id)
    }

    private fun requireAdmin(ctx: ActionContext) {
        if (!admins.isAdmin(ctx.user)) throw ctx.err(403, "Admins only", "শুধু অ্যাডমিন")
    }

    /** admins: appointment reports (open by default) with the appointment, its pay log and the doctor's bKash changes */
    fun adminReports(ctx: ActionContext): Any {
        requireAdmin(ctx)
        val status = ctx.body.string("status")?.takeIf { it in setOf("open", "resolved", "dismissed") } ?: "open"
        val rows = jdbc.queryForList(
            "SELECT * FROM appt_reports WHERE status = ? ORDER BY created_at DESC, id LIMIT 200", status,
        )
        val items = rows.map { r ->
            val apptId = r["appointment_id"] as String
            val a = appts.get(apptId)
            linkedMapOf<String, Any?>(
                "id" to r["id"],
                "reporterUid" to r["reporter_uid"],
                "reporterRole" to r["reporter_role"],
                "reason" to r["reason"],
                "status" to r["status"],
                "createdAt" to (r["created_at"] as? java.sql.Timestamp)?.time,
                "resolvedAt" to (r["resolved_at"] as? java.sql.Timestamp)?.time,
                "appointment" to a?.let {
                    linkedMapOf(
                        "id" to it.id, "patientUid" to it.patientUid, "patientName" to it.patientName, "providerUid" to it.providerUid,
                        "providerName" to it.providerName, "date" to it.date, "start" to it.start, "status" to it.status, "online" to it.online,
                        "payState" to it.payState, "payRef" to it.payRef, "fee" to it.fee, "bkashNumber" to it.bkashNumber,
                    )
                },
                "payLog" to payLog.of(apptId),
                "bkashChanges" to (a?.let { payLog.bkashChanges(it.providerUid) } ?: emptyList()),
            )
        }
        return linkedMapOf("items" to items)
    }

    /** admins: close a report (resolved | dismissed) or open it again */
    fun adminReportSet(ctx: ActionContext): Any {
        requireAdmin(ctx)
        val id = ctx.body.string("id")
        val status = ctx.body.string("status")
        if (id.isNullOrEmpty() || status !in setOf("open", "resolved", "dismissed")) throw ctx.err(400, "Not found", "পাওয়া যায়নি")
        val n = jdbc.update(
            "UPDATE appt_reports SET status = ?, resolved_at = ?, resolved_by = ? WHERE id = ?",
            status, if (status == "open") null else Sql.ts(clock.now()), if (status == "open") null else ctx.user.id, id,
        )
        if (n == 0) throw notFound(ctx)
        return linkedMapOf("ok" to true)
    }
}

/** R8: the append-only pay log, server-made appointment reports, the bKash number history */
@Component
class ApptPayLog(private val jdbc: JdbcTemplate) {
    fun log(appointmentId: String, byUid: String?, from: String?, to: String?, payRef: String?, note: String?) {
        jdbc.update(
            "INSERT INTO appt_pay_log (appointment_id, by_uid, from_state, to_state, pay_ref, note) VALUES (?, ?, ?, ?, ?, ?)",
            appointmentId, byUid, from, to, payRef, note?.take(300),
        )
    }

    fun of(appointmentId: String): List<Map<String, Any?>> = jdbc.query(
        "SELECT by_uid, from_state, to_state, pay_ref, note, at FROM appt_pay_log WHERE appointment_id = ? ORDER BY id",
        { rs, _ ->
            linkedMapOf<String, Any?>(
                "by" to rs.getString(1), "from" to rs.getString(2), "to" to rs.getString(3), "payRef" to rs.getString(4),
                "note" to rs.getString(5), "at" to rs.getTimestamp(6).time,
            )
        },
        appointmentId,
    )

    fun bkashChanges(providerUid: String): List<Map<String, Any?>> = jdbc.query(
        "SELECT old_last3, new_last3, by_uid, at FROM bkash_number_log WHERE provider_uid = ? ORDER BY at DESC, id DESC LIMIT 20",
        { rs, _ -> linkedMapOf<String, Any?>("oldLast3" to rs.getString(1), "newLast3" to rs.getString(2), "by" to rs.getString(3), "at" to rs.getTimestamp(4).time) },
        providerUid,
    )

    /** a report by the server (reporter NULL), once per appointment and kind of reason */
    fun systemReport(appointmentId: String, reason: String) {
        jdbc.update(
            """
            INSERT INTO appt_reports (id, appointment_id, reporter_uid, reporter_role, reason, status)
            VALUES (?, ?, NULL, 'system', ?, 'open') ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
            "ars_" + Ids.sha256Hex("$appointmentId|${reason.substringBefore(':')}").take(20), appointmentId, reason.take(300),
        )
    }
}
