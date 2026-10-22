package com.lifolo.calls

import com.lifolo.admin.kernel.AccountDeletionStep
import com.lifolo.common.action.ActionContext
import com.lifolo.common.action.ActionHandler
import com.lifolo.common.action.ActionModule
import com.lifolo.jobs.DailyTask
import org.jobrunr.jobs.annotations.Job
import org.jobrunr.jobs.annotations.Recurring
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * In-app calls (backend/docs/CALLS.md): call_config, call_start, call_accept, call_decline, call_cancel, call_end,
 * call_status, call_history, appt_join. Own account only — not for a caregiver acting for a patient (not in CARE_ACTIONS,
 * checked again here).
 */
@Component
class CallActions(private val calls: CallService) : ActionModule {
    override fun actions(): Map<String, ActionHandler> = mapOf(
        "call_config" to ActionHandler { ctx -> own(ctx); calls.config() },
        "call_start" to ActionHandler(::start),
        "call_accept" to ActionHandler { ctx -> calls.accept(ctx.lang, own(ctx), email(ctx), ctx.body.string("callId")) },
        "call_decline" to ActionHandler { ctx -> calls.finish(ctx.lang, own(ctx), email(ctx), ctx.body.string("callId"), "decline") },
        "call_cancel" to ActionHandler { ctx -> calls.finish(ctx.lang, own(ctx), email(ctx), ctx.body.string("callId"), "cancel") },
        "call_end" to ActionHandler { ctx -> calls.finish(ctx.lang, own(ctx), email(ctx), ctx.body.string("callId"), "end") },
        "call_status" to ActionHandler { ctx -> calls.status(ctx.lang, own(ctx), email(ctx), ctx.body.string("callId")) },
        "call_history" to ActionHandler { ctx -> calls.history(own(ctx), ctx.body.int("before")) },
        "appt_join" to ActionHandler { ctx -> calls.apptJoin(ctx.lang, own(ctx), email(ctx), ctx.body.string("appointmentId")) },
    )

    private fun own(ctx: ActionContext): String {
        if (ctx.user.actingBy != null || ctx.user.id != ctx.caller.id) {
            throw ctx.err(403, "A caregiver can't do this for the patient", "কেয়ারগিভার রোগীর হয়ে এটা করতে পারেন না")
        }
        return ctx.user.id
    }

    /** the verified email (assistants are matched by it), else "" */
    private fun email(ctx: ActionContext): String = if (ctx.user.emailVerified) ctx.user.email.lowercase() else ""

    private fun start(ctx: ActionContext): Any {
        val uid = own(ctx)
        val b = ctx.body
        val bad = { ctx.err(400, "Invalid request", "অনুরোধ ঠিক নেই", "bad_request") } // bn-ok
        val c = b.string("ctx")
        if (c == null || c !in CTXS) throw bad()
        val refId = b.string("refId")?.takeIf { ID_RE.matches(it) } ?: throw bad()
        val peer = if (b.isNullish("peerUid")) null else b.string("peerUid")?.takeIf { ID_RE.matches(it) } ?: throw bad()
        val video = if (b.isNullish("video")) null else b.bool("video") ?: throw bad()
        return calls.start(ctx.lang, uid, email(ctx), c, refId, peer, video)
    }

    companion object {
        val CTXS = setOf("blood", "appointment", "ambulance", "order")
        private val ID_RE = Regex("^[A-Za-z0-9_-]{1,128}$")
    }
}

/** the 45 s ring timeout: JobRunr every 15 s (every call action also sweeps first) */
@Component
class CallJobs(private val calls: CallService) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Recurring(id = "call-timeouts", interval = "PT15S")
    @Job(name = "Call ring timeouts")
    fun sweep() {
        try {
            calls.sweep()
        } catch (e: Exception) {
            log.error("call sweep failed", e)
        }
    }
}

/** daily step 15 (`callsDeleted`): call metadata older than 180 days */
@Component
class CallsCleanupTask(private val calls: CallService) : DailyTask {
    override val name = "callsDeleted"
    override val order = 15

    override fun run(nowMs: Long): Any = calls.deleteOld(nowMs)
}

/** account deletion: live calls stop at the request; every call record of the person goes at the deletion */
@Component
class CallsDeletionStep(private val calls: CallService) : AccountDeletionStep {
    override val order = 650

    override fun hide(uid: String) {
        calls.endAllOf(uid)
    }

    override fun deleteNow(uid: String, email: String?) {
        calls.deleteAllOf(uid)
    }
}
