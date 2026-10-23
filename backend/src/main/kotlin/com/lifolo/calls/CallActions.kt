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
class CallActions(private val calls: CallService, private val f: CallFeatures) : ActionModule {
    override fun actions(): Map<String, ActionHandler> = mapOf(
        "call_config" to ActionHandler { ctx -> own(ctx); f.requireAny(ctx, AUDIO, APPT); calls.config() },
        "call_start" to ActionHandler(::start),
        "call_accept" to ActionHandler { ctx ->
            own(ctx)
            f.require(ctx, AUDIO)
            calls.accept(ctx.lang, ctx.user.id, email(ctx), ctx.body.string("callId"), optBool(ctx, "video"))
        },
        "call_decline" to gated(AUDIO) { ctx -> calls.finish(ctx.lang, ctx.user.id, email(ctx), ctx.body.string("callId"), "decline") },
        "call_cancel" to gated(AUDIO) { ctx -> calls.finish(ctx.lang, ctx.user.id, email(ctx), ctx.body.string("callId"), "cancel") },
        "call_end" to gated(AUDIO, APPT) { ctx -> calls.finish(ctx.lang, ctx.user.id, email(ctx), ctx.body.string("callId"), "end") },
        "call_status" to gated(AUDIO, APPT) { ctx -> calls.status(ctx.lang, ctx.user.id, email(ctx), ctx.body.string("callId")) },
        "call_history" to gated(AUDIO, APPT) { ctx -> calls.history(ctx.user.id, ctx.body.int("before")) },
        "call_upgrade" to gated(VIDEO) { ctx -> calls.upgrade(ctx.lang, ctx.user.id, email(ctx), ctx.body.string("callId")) },
        "call_upgrade_accept" to gated(VIDEO) { ctx -> calls.upgradeAnswer(ctx.lang, ctx.user.id, email(ctx), ctx.body.string("callId"), true) },
        "call_upgrade_decline" to gated(VIDEO) { ctx -> calls.upgradeAnswer(ctx.lang, ctx.user.id, email(ctx), ctx.body.string("callId"), false) },
        // the block list is shared with messages
        "call_block" to gated(AUDIO, CallFeatures.CHAT) { ctx ->
            calls.block(ctx.lang, ctx.user.id, email(ctx), ctx.body.string("peerUid"), ctx.body.string("callId")?.takeIf { it.isNotEmpty() })
        },
        "call_unblock" to gated(AUDIO, CallFeatures.CHAT) { ctx -> calls.unblock(ctx.lang, ctx.user.id, ctx.body.string("peerUid")) },
        "call_blocks" to gated(AUDIO, CallFeatures.CHAT) { ctx -> calls.blockList(ctx.user.id) },
        "call_report" to gated(AUDIO, APPT) { ctx ->
            calls.report(ctx.lang, ctx.user.id, email(ctx), ctx.body.string("callId"), ctx.body.string("reason"), ctx.body.string("note"))
        },
        // no switch of its own: it answers each flag with the switches of both people
        "contact_can" to ActionHandler(::can),
        "appt_join" to gated(APPT) { ctx -> calls.apptJoin(ctx.lang, ctx.user.id, email(ctx), ctx.body.string("appointmentId")) },
    )

    /** own account + at least one of [keys] switched on for the caller (feature_off 404 otherwise), then [h] */
    private fun gated(vararg keys: String, h: (ActionContext) -> Any?): ActionHandler = ActionHandler { ctx ->
        own(ctx)
        f.requireAny(ctx, *keys)
        h(ctx)
    }

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
        f.require(ctx, AUDIO)
        val b = ctx.body
        val c = b.string("ctx")
        if (c == null || c !in CTXS) throw bad(ctx)
        val refId = b.string("refId")?.takeIf { ID_RE.matches(it) } ?: throw bad(ctx)
        val peer = if (b.isNullish("peerUid")) null else b.string("peerUid")?.takeIf { ID_RE.matches(it) } ?: throw bad(ctx)
        val video = optBool(ctx, "video")
        if (video == true) f.require(ctx, VIDEO)
        return calls.start(ctx.lang, uid, email(ctx), c, refId, peer, video)
    }

    companion object {
        val CTXS = setOf("blood", "appointment", "ambulance", "rx_order", "lab_order")
        private const val AUDIO = CallFeatures.CALLS_AUDIO
        private const val VIDEO = CallFeatures.CALLS_VIDEO
        private const val APPT = CallFeatures.APPT_VIDEO
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
