package com.lifolo.appointments

import com.lifolo.common.Rules
import com.lifolo.common.Sql
import com.lifolo.common.action.ActionContext
import com.lifolo.common.action.ActionHandler
import com.lifolo.common.action.ActionModule
import com.lifolo.demo.DemoWorld
import com.lifolo.notify.NotifyCats
import com.lifolo.notify.PushService
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
    txm: PlatformTransactionManager,
) : ActionModule {
    private val tx = TransactionTemplate(txm)

    override fun actions(): Map<String, ActionHandler> = mapOf(
        "appt_pay_claim" to ActionHandler(::claim),
        "appt_pay_confirm" to ActionHandler(::confirm),
        "appt_pay_reject" to ActionHandler(::reject),
    )

    private fun notFound(ctx: ActionContext) = ctx.err(404, "Not found", "পাওয়া যায়নি")

    private fun load(ctx: ActionContext): Appt {
        val id = ctx.body.string("appointmentId")
        if (!Rules.isValidId(id)) throw notFound(ctx)
        val a = appts.get(id!!) ?: throw notFound(ctx)
        // demo and real accounts never meet (an appointment never mixes them; a stray id answers like a missing one)
        if (!DemoWorld.sameWorld(ctx.user.id, a.providerUid)) throw notFound(ctx)
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
        if (badges.approvedBadge(a.providerUid) != "doctor") throw ctx.err(403, "This doctor is not verified now", "এই ডাক্তার এখন যাচাই করা নেই")
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
        val a = load(ctx)
        if (a.patientUid != ctx.user.id) throw notFound(ctx)
        requirePayable(ctx, a)
        val ref = CareRules.payRef(ctx.body.string("payRef"))
        if (ref.error != null) throw ctx.err(400, ref.error.en, ref.error.bn)
        val now = clock.now()
        var changed = false
        var payRef: String? = null
        tx.executeWithoutResult {
            val (status, pay) = lockState(a.id) ?: throw notFound(ctx)
            if (status != "pending" && status != "confirmed") throw stateErr(ctx)
            if (pay == "paid") throw ctx.err(409, "The doctor already marked the fee as received", "ডাক্তার আগেই ফি পেয়েছেন বলে জানিয়েছেন", "state") // bn-ok
            if (pay != "unpaid" && pay != "claimed") throw stateErr(ctx)
            // a second claim without a TrxID keeps the earlier one
            payRef = ref.value ?: if (pay == "claimed") a.payRef else null
            changed = pay != "claimed" || payRef != a.payRef
            jdbc.update(
                "UPDATE appointments SET pay_state = 'claimed', pay_ref = ?, pay_note = NULL, pay_claimed_at = ?, updated_at = ? WHERE id = ?",
                payRef, Sql.ts(now), Sql.ts(now), a.id,
            )
        }
        if (changed) {
            val body = "${a.patientName} · ${ApptRepo.dateLabel(a.date)}${if (payRef != null) " · TrxID $payRef" else ""}"
            val data = mapOf("type" to "appointment", "role" to "provider", "id" to a.id, "cat" to NotifyCats.APPOINTMENTS)
            for (to in listOf(a.providerUid) + assistantUids(a.providerUid)) {
                users.push(to, "Patient sent the fee — please check", "রোগী ফি পাঠিয়েছেন — যাচাই করুন", body, data) // bn-ok
            }
        }
        return linkedMapOf("ok" to true, "payState" to "claimed", "payRef" to payRef)
    }

    // ------------------------------------------------------------------ doctor/assistant: "ফি পেয়েছি" / "পাইনি"

    fun confirm(ctx: ActionContext): Any {
        val a = load(ctx)
        requireDoctorSide(ctx, a)
        requirePayable(ctx, a)
        val now = clock.now()
        var changed = false
        tx.executeWithoutResult {
            val (status, pay) = lockState(a.id) ?: throw notFound(ctx)
            if (pay == "paid") return@executeWithoutResult
            if (status !in setOf("pending", "confirmed", "completed")) throw stateErr(ctx)
            jdbc.update(
                "UPDATE appointments SET pay_state = 'paid', pay_note = NULL, pay_paid_at = ?, pay_paid_by = ?, updated_at = ? WHERE id = ?",
                Sql.ts(now), ctx.user.id, Sql.ts(now), a.id,
            )
            changed = true
        }
        if (changed) {
            push.pushPatient(
                a.patientUid, "The doctor received the fee", "ডাক্তার ফি পেয়েছেন", // bn-ok
                "${a.providerName} · ${ApptRepo.dateLabel(a.date)}",
                mapOf("type" to "appointment", "role" to "patient", "id" to a.id, "cat" to NotifyCats.APPOINTMENTS),
            )
        }
        return linkedMapOf("ok" to true, "payState" to "paid")
    }

    fun reject(ctx: ActionContext): Any {
        val a = load(ctx)
        requireDoctorSide(ctx, a)
        requirePayable(ctx, a)
        val reason = Rules.cleanText(ctx.body.string("reason"), 200)
        val now = clock.now()
        tx.executeWithoutResult {
            val (_, pay) = lockState(a.id) ?: throw notFound(ctx)
            if (pay != "claimed") throw stateErr(ctx)
            jdbc.update(
                "UPDATE appointments SET pay_state = 'unpaid', pay_note = ?, updated_at = ? WHERE id = ?",
                reason, Sql.ts(now), a.id,
            )
        }
        push.pushPatient(
            a.patientUid, "The doctor has not received the fee yet", "ডাক্তার এখনো ফি পাননি — বিকাশ লেনদেনটা আবার দেখুন", // bn-ok
            "${a.providerName} · ${ApptRepo.dateLabel(a.date)}${if (reason != null) " · $reason" else ""}",
            mapOf("type" to "appointment", "role" to "patient", "id" to a.id, "cat" to NotifyCats.APPOINTMENTS),
        )
        return linkedMapOf("ok" to true, "payState" to "unpaid", "payNote" to reason)
    }
}
