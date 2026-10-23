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
 * call_status, call_history, call_upgrade(_accept/_decline), call_block, call_unblock, call_blocks, call_report,
 * contact_can, appt_join. Own account only — not for a caregiver acting for a patient (not in CARE_ACTIONS,
 * checked again here).
 */
@Component
class CallActions(private val calls: CallService) : ActionModule {
    override fun actions(): Map<String, ActionHandler> = mapOf(
        "call_config" to ActionHandler { ctx -> own(ctx); calls.config() },
        "call_start" to ActionHandler(::start),
        "call_accept" to ActionHandler { ctx ->
            calls.accept(ctx.lang, own(ctx), email(ctx), ctx.body.string("callId"), optBool(ctx, "video"))
        },
        "call_decline" to ActionHandler { ctx -> calls.finish(ctx.lang, own(ctx), email(ctx), ctx.body.string("callId"), "decline") },
        "call_cancel" to ActionHandler { ctx -> calls.finish(ctx.lang, own(ctx), email(ctx), ctx.body.string("callId"), "cancel") },
        "call_end" to ActionHandler { ctx -> calls.finish(ctx.lang, own(ctx), email(ctx), ctx.body.string("callId"), "end") },
        "call_status" to ActionHandler { ctx -> calls.status(ctx.lang, own(ctx), email(ctx), ctx.body.string("callId")) },
        "call_history" to ActionHandler { ctx -> calls.history(own(ctx), ctx.body.int("before")) },
        "call_upgrade" to ActionHandler { ctx -> calls.upgrade(ctx.lang, own(ctx), email(ctx), ctx.body.string("callId")) },
        "call_upgrade_accept" to ActionHandler { ctx -> calls.upgradeAnswer(ctx.lang, own(ctx), email(ctx), ctx.body.string("callId"), true) },
        "call_upgrade_decline" to ActionHandler { ctx -> calls.upgradeAnswer(ctx.lang, own(ctx), email(ctx), ctx.body.string("callId"), false) },
        "call_block" to ActionHandler { ctx ->
            calls.block(ctx.lang, own(ctx), email(ctx), ctx.body.string("peerUid"), ctx.body.string("callId")?.takeIf { it.isNotEmpty() })
        },
        "call_unblock" to ActionHandler { ctx -> calls.unblock(ctx.lang, own(ctx), ctx.body.string("peerUid")) },
        "call_blocks" to ActionHandler { ctx -> calls.blockList(own(ctx)) },
        "call_report" to ActionHandler { ctx ->
            calls.report(ctx.lang, own(ctx), email(ctx), ctx.body.string("callId"), ctx.body.string("reason"), ctx.body.string("note"))
        },
        "contact_can" to ActionHandler(::can),
        "appt_join" to ActionHandler { ctx -> calls.apptJoin(ctx.lang, own(ctx), email(ctx), ctx.body.string("appointmentId")) },
    )

    private fun bad(ctx: ActionContext) = ctx.err(400, "Invalid request", "অনুরোধ ঠিক নেই", "bad_request") // bn-ok

    private fun optBool(ctx: ActionContext, key: String): Boolean? =
        if (ctx.body.isNullish(key)) null else ctx.body.bool(key) ?: throw bad(ctx)

    /** contact_can {items:[{ctx, refId, peerUid?}]} (≤ 50) */
    private fun can(ctx: ActionContext): Any {
        val uid = own(ctx)
        val arr = ctx.body["items"]
        if (arr == null || !arr.isArray || arr.size() > 50) throw bad(ctx)
        val items = arr.map { n ->
            val c = n?.get("ctx")?.takeIf { it.isTextual }?.textValue()
            val r = n?.get("refId")?.takeIf { it.isTextual }?.textValue()
            val pNode = n?.get("peerUid")
            val p = if (pNode == null || pNode.isNull) null else pNode.takeIf { it.isTextual }?.textValue() ?: throw bad(ctx)
            if (c == null || c !in CTXS || r == null || !ID_RE.matches(r) || (p != null && !ID_RE.matches(p))) throw bad(ctx)
            Triple(c, r, p)
        }
        return calls.can(uid, email(ctx), items)
    }

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
        val c = b.string("ctx")
        if (c == null || c !in CTXS) throw bad(ctx)
        val refId = b.string("refId")?.takeIf { ID_RE.matches(it) } ?: throw bad(ctx)
        val peer = if (b.isNullish("peerUid")) null else b.string("peerUid")?.takeIf { ID_RE.matches(it) } ?: throw bad(ctx)
        return calls.start(ctx.lang, uid, email(ctx), c, refId, peer, optBool(ctx, "video"))
    }

    companion object {
        val CTXS = setOf("blood", "appointment", "ambulance", "rx_order", "lab_order")
        private val ID_RE = Regex("^[A-Za-z0-9_-]{1,128}$")
    }
}

/**
 * Every 15 s (JobRunr): the 45 s ring timeout (every call action also sweeps first), and every live call checked again —
 * relation ended, block, account cut off, heartbeats stopped → ended and its room deleted (security review R3, R11).
 */
@Component
class CallJobs(private val calls: CallService) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Recurring(id = "call-timeouts", interval = "PT15S")
    @Job(name = "Call timeouts and re-checks")
    fun sweep() {
        try {
            calls.sweep()
            calls.recheckLive()
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
    override val order = 550

    override fun hide(uid: String) {
        calls.revokeFor(uid, "account")
    }

    override fun deleteNow(uid: String, email: String?) {
        calls.deleteAllOf(uid)
    }
}
