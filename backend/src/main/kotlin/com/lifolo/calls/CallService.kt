package com.lifolo.calls

import com.fasterxml.jackson.databind.ObjectMapper
import com.lifolo.common.AccountCutOffEvent
import com.lifolo.common.ApiException
import com.lifolo.common.Ids
import com.lifolo.common.Lang
import com.lifolo.common.RateLimiter
import com.lifolo.common.Sql
import com.lifolo.common.StoredFiles
import com.lifolo.contact.BlockService
import com.lifolo.contact.ContactHistory
import com.lifolo.contact.ContactPolicy
import com.lifolo.notify.NotifyCats
import com.lifolo.notify.NotifyPrefsService
import com.lifolo.notify.PushItem
import com.lifolo.notify.PushService
import com.lifolo.notify.PushText
import com.lifolo.notify.SseHub
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.time.Duration

/** one row of call_sessions */
data class CallRow(
    val id: String,
    val room: String,
    val kind: String,
    val ctx: String,
    val refId: String,
    val caller: String,
    val callee: String,
    val video: Boolean,
    val state: String,
    val createdAt: Long,
    val acceptedAt: Long?,
    val endedAt: Long?,
    val endReason: String?,
    val callerSeenAt: Long?,
    val calleeSeenAt: Long?,
    val upgradeBy: String?,
    val upgradeAt: Long?,
    val readyPushedAt: Long?,
    val hostUid: String? = null,
) {
    val live: Boolean get() = state == "ringing" || state == "accepted"
    fun other(uid: String): String = if (uid == caller) callee else caller
    val durationSec: Long get() = if (acceptedAt != null && endedAt != null) maxOf(0L, (endedAt - acceptedAt) / 1000) else 0L

    companion object {
        fun of(rs: ResultSet) = CallRow(
            rs.getString("id"), rs.getString("room"), rs.getString("kind"), rs.getString("ctx"), rs.getString("ref_id"),
            rs.getString("caller_uid"), rs.getString("callee_uid"), rs.getBoolean("video"), rs.getString("state"),
            rs.getTimestamp("created_at").time, rs.getTimestamp("accepted_at")?.time, rs.getTimestamp("ended_at")?.time,
            rs.getString("end_reason"), rs.getTimestamp("caller_seen_at")?.time, rs.getTimestamp("callee_seen_at")?.time,
            rs.getString("upgrade_by"), rs.getTimestamp("upgrade_at")?.time, rs.getTimestamp("ready_pushed_at")?.time,
            rs.getString("host_uid"),
        )
    }
}

/** for the blood donor rule: the requester called this donor about this request (ContactPolicy) */
@Component
class CallContactHistory(private val jdbc: JdbcTemplate) : ContactHistory {
    override fun contacted(from: String, to: String, ctx: String, refId: String): Boolean = jdbc.queryForList(
        "SELECT 1 FROM call_sessions WHERE ctx = ? AND ref_id = ? AND caller_uid = ? AND callee_uid = ? LIMIT 1", ctx, refId, from, to,
    ).isNotEmpty()
}

/**
 * In-app calls (backend/docs/CALLS.md, security review docs/test-plan/CALLS-SECURITY-REVIEW.md): the call_sessions state
 * machine, contact / block / busy / off / rate checks, LiveKit tokens and rooms, pushes and SSE events. Nothing about the
 * conversation itself is stored — only who, when, how long.
 */
@Service
class CallService(
    private val jdbc: JdbcTemplate,
    private val policy: ContactPolicy,
    private val blocks: BlockService,
    private val tokens: LiveKitTokens,
    private val rooms: LiveKitRooms,
    private val prefs: NotifyPrefsService,
    private val push: PushService,
    private val sse: SseHub,
    private val rate: RateLimiter,
    private val files: StoredFiles,
    private val mapper: ObjectMapper,
    txm: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(txm)

    /** the clock (tests move it) */
    @Volatile
    var clock: () -> Long = { System.currentTimeMillis() }

    private fun now(): Long = clock()

    // ------------------------------------------------------------------ errors

    private fun e(lang: String?, status: Int, en: String, bn: String, code: String) = ApiException(status, Lang.tr(lang, en, bn), code)

    fun unavailable(lang: String?) =
        e(lang, 503, "In-app calls are not available now — please phone instead", "অ্যাপে কল এখন চালু নেই — ফোনে কল করুন", "calls_unavailable") // bn-ok

    private fun callOff(lang: String?) =
        e(lang, 409, "They have turned in-app calls off — please phone instead", "উনি অ্যাপে কল বন্ধ রেখেছেন — ফোনে কল করুন", "call_off") // bn-ok

    private fun busy(lang: String?) = e(lang, 409, "They are on another call now", "উনি এখন অন্য কলে আছেন", "call_busy") // bn-ok

    private fun gone(lang: String?) = e(lang, 409, "This call has ended", "কলটা শেষ হয়ে গেছে", "call_gone") // bn-ok

    private fun tooMany(lang: String?) =
        e(lang, 429, "Too many calls — try again later", "অনেকবার কল করেছেন — কিছুক্ষণ পরে চেষ্টা করুন", "call_rate") // bn-ok

    /** shared with messages: the person asking blocked the other one */
    fun blockedByMe(lang: String?) =
        e(lang, 403, "You have blocked contact with this person", "আপনি এঁর সাথে যোগাযোগ বন্ধ রেখেছেন", "call_blocked") // bn-ok

    private fun badRequest(lang: String?) = e(lang, 400, "Invalid request", "অনুরোধ ঠিক নেই", "bad_request") // bn-ok

    private fun notFound(lang: String?) = ApiException(404, Lang.tr(lang, "Not found", "পাওয়া যায়নি"))

    private fun requireAvailable(lang: String?) {
        if (!tokens.available) throw unavailable(lang)
    }

    // ------------------------------------------------------------------ people

    data class Person(val uid: String, val name: String, val photoThumb: String?, val lang: String?)

    /** profile name + card photo + language of each uid (missing profile → empty name) */
    fun people(uids: Collection<String>): Map<String, Person> {
        val ids = uids.distinct()
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<String, Person>()
        val keys = HashMap<String, String>()
        for (chunk in ids.chunked(500)) {
            jdbc.query(
                "SELECT uid, name, lang, photo_thumb, photo_thumb_key FROM user_profiles WHERE uid IN (${chunk.joinToString(",") { "?" }})",
                RowCallbackHandler { rs ->
                    val uid = rs.getString("uid")
                    out[uid] = Person(uid, rs.getString("name") ?: "", rs.getString("photo_thumb")?.takeIf { it.isNotEmpty() }, rs.getString("lang"))
                    if (out[uid]!!.photoThumb == null) rs.getString("photo_thumb_key")?.let { keys[uid] = it }
                },
                *chunk.toTypedArray(),
            )
        }
        if (keys.isNotEmpty()) {
            val texts = runCatching { files.texts(keys.values) }.getOrDefault(emptyMap())
            for ((uid, key) in keys) texts[key]?.let { out[uid] = out[uid]!!.copy(photoThumb = it) }
        }
        return ids.associateWith { out[it] ?: Person(it, "", null, null) }
    }

    private fun person(uid: String): Person = people(listOf(uid))[uid]!!

    fun peerMap(p: Person): Map<String, Any?> = linkedMapOf<String, Any?>("uid" to p.uid, "name" to p.name).apply {
        if (p.photoThumb != null) put("photoThumb", p.photoThumb)
    }

    private fun tokenFor(uid: String, call: CallRow): String = tokens.token(uid, person(uid).name.ifEmpty { "Lifolo" }, call.room, call.video, now())

    private fun join(uid: String, call: CallRow): Map<String, Any?> {
        val peer = if (uid == call.caller || uid == call.callee) call.other(uid) else call.caller
        return linkedMapOf(
            "ok" to true, "callId" to call.id, "room" to call.room, "url" to tokens.url, "token" to tokenFor(uid, call),
            "video" to call.video, "peer" to peerMap(person(peer)),
        )
    }

    // ------------------------------------------------------------------ rows

    private val selectCall = "SELECT * FROM call_sessions"

    fun load(id: String, forUpdate: Boolean = false): CallRow? =
        jdbc.query("$selectCall WHERE id = ?" + (if (forUpdate) " FOR UPDATE" else ""), { rs, _ -> CallRow.of(rs) }, id).firstOrNull()

    /**
     * Someone with a ringing (not yet timed out) ring call, or an accepted ring call whose both sides still send
     * heartbeats (call_status every 30 s; 90 s of silence = gone — review R11). Rooms never make anyone busy.
     */
    private fun inCall(uid: String, nowMs: Long): Boolean = jdbc.queryForList(
        """
        SELECT 1 FROM call_sessions WHERE kind = 'ring' AND (caller_uid = ? OR callee_uid = ?)
          AND ((state = 'ringing' AND created_at > ?)
            OR (state = 'accepted' AND least(coalesce(caller_seen_at, accepted_at), coalesce(callee_seen_at, accepted_at)) > ?)) LIMIT 1
        """.trimIndent(),
        uid, uid, Sql.ts(nowMs - RING_MS), Sql.ts(nowMs - HEARTBEAT_GONE_MS),
    ).isNotEmpty()

    // ------------------------------------------------------------------ ending calls

    /**
     * Ends [id] if it is still live (ringing → [ringingTo], accepted → ended) and does everything a final state needs:
     * the call server deletes the room (nobody stays or re-enters with an old token — review R3), both sides get
     * `call_state`. Returns the row when this call changed it.
     */
    private fun end(id: String, reason: String?, ringingTo: String = "cancelled"): CallRow? {
        val c = jdbc.query(
            """
            UPDATE call_sessions SET state = CASE WHEN state = 'ringing' THEN ? ELSE 'ended' END, ended_at = ?, end_reason = ?,
              upgrade_by = NULL, upgrade_at = NULL
            WHERE id = ? AND state IN ('ringing', 'accepted') RETURNING *
            """.trimIndent(),
            { rs, _ -> CallRow.of(rs) },
            ringingTo, Sql.ts(now()), reason, id,
        ).firstOrNull() ?: return null
        afterEnd(c)
        return c
    }

    private fun afterEnd(c: CallRow) {
        try {
            rooms.deleteRoom(c.room)
        } catch (ex: Exception) {
            log.warn("delete room failed: {}", ex.message)
        }
        try {
            notifyState(c)
        } catch (ex: Exception) {
            log.error("call_state notice failed", ex)
        }
    }

    /** every live call of [uid] ends now (deactivated, disabled, deletion requested) — review R3 */
    fun revokeFor(uid: String, reason: String = "account"): Int {
        val ids = jdbc.queryForList(
            "SELECT id FROM call_sessions WHERE (caller_uid = ? OR callee_uid = ?) AND state IN ('ringing', 'accepted')", String::class.java, uid, uid,
        )
        return ids.count { end(it, reason) != null }
    }

    /** every live call about one record ends now (the relation is gone) */
    fun revokeFor(ctx: String, refId: String, reason: String = "relation"): Int {
        val ids = jdbc.queryForList(
            "SELECT id FROM call_sessions WHERE ctx = ? AND ref_id = ? AND state IN ('ringing', 'accepted')", String::class.java, ctx, refId,
        )
        return ids.count { end(it, reason) != null }
    }

    /** every live call between two people ends now (a block) */
    fun revokeBetween(a: String, b: String, reason: String = "blocked"): Int {
        val ids = jdbc.queryForList(
            """
            SELECT id FROM call_sessions WHERE state IN ('ringing', 'accepted')
              AND ((caller_uid = ? AND callee_uid = ?) OR (caller_uid = ? AND callee_uid = ?))
            """.trimIndent(),
            String::class.java, a, b, b, a,
        )
        return ids.count { end(it, reason, "declined") != null }
    }

    @EventListener
    fun onCutOff(ev: AccountCutOffEvent) {
        try {
            revokeFor(ev.uid, "account")
        } catch (ex: Exception) {
            log.error("call revoke failed", ex)
        }
    }

    // ------------------------------------------------------------------ sweep (45 s ring timeout) and re-checks

    /**
     * Ringing calls older than 45 s → `missed` (callee: one collapsed `call_missed` push + bell entry per caller; both:
     * `call_state`). Safe to run anywhere, any time: each row is moved by exactly one UPDATE. Returns how many.
     */
    fun sweep(nowMs: Long = now()): Int {
        val missed = jdbc.query(
            """
            UPDATE call_sessions SET state = 'missed', ended_at = ?, end_reason = 'timeout'
            WHERE id IN (SELECT id FROM call_sessions WHERE state = 'ringing' AND created_at <= ? ORDER BY created_at LIMIT 500 FOR UPDATE SKIP LOCKED)
            RETURNING *
            """.trimIndent(),
            { rs, _ -> CallRow.of(rs) },
            Sql.ts(nowMs), Sql.ts(nowMs - RING_MS),
        )
        for (c in missed) {
            try {
                notifyMissed(c)
            } catch (ex: Exception) {
                log.error("missed call notice failed", ex)
            }
            afterEnd(c)
        }
        return missed.size
    }

    /** why a live call must end now, or null: heartbeats stopped, the relation ended, an account or a block (R1, R3, R11) */
    private fun mustEnd(c: CallRow, nowMs: Long): String? {
        if (!policy.reachable(c.caller) || !policy.reachable(c.callee)) return "account"
        if (c.kind == "room") {
            val seen = maxOf(c.callerSeenAt ?: 0L, c.calleeSeenAt ?: 0L, c.acceptedAt ?: 0L)
            if (seen < nowMs - HEARTBEAT_GONE_MS) return "heartbeat"
            val a = policy.appointment(c.refId) ?: return "relation"
            if (a.status != "confirmed" || !a.online || a.providerSuspended || !a.within(nowMs, ContactPolicy.JOIN_BEFORE_MS, ContactPolicy.AFTER_MS)) {
                return "relation"
            }
            return null
        }
        if (blocks.eitherWay(c.caller, c.callee)) return "blocked"
        if (c.state == "accepted") {
            val a = c.acceptedAt ?: c.createdAt
            if (minOf(c.callerSeenAt ?: a, c.calleeSeenAt ?: a) < nowMs - HEARTBEAT_GONE_MS) return "heartbeat"
        }
        val rel = policy.check(c.caller, policy.accountEmail(c.caller), c.ctx, c.refId, c.callee, nowMs)
        return if (rel == null || rel.peer != c.callee) "relation" else null
    }

    /** the 15-second job: every live call is checked again ([mustEnd]); returns how many ended */
    fun recheckLive(nowMs: Long = now()): Int {
        val live = jdbc.query("$selectCall WHERE state IN ('ringing', 'accepted') ORDER BY created_at LIMIT 2000", { rs, _ -> CallRow.of(rs) })
        var n = 0
        for (c in live) {
            val why = try {
                mustEnd(c, nowMs)
            } catch (ex: Exception) {
                log.warn("call recheck failed: {}", ex.message)
                null
            }
            if (why != null && end(c.id, why) != null) n++
        }
        return n
    }

    // ------------------------------------------------------------------ notices

    private fun publish(uid: String, event: String, data: Map<String, Any?>) {
        try {
            sse.publish("user:$uid", event, data)
        } catch (ex: Exception) {
            log.debug("sse {} failed: {}", event, ex.message)
        }
    }

    /** a data-only push to the phones, never in the bell list */
    private fun signal(uids: List<String>, data: Map<String, Any?>, ttl: Long = 60) {
        push.send(uids.distinct().map { PushItem(it, "", "", data, inbox = false, ttlSeconds = ttl) })
    }

    /** `call_state` to both sides: FCM (no bell entry, 60 s) + SSE — ringing stops on every device */
    private fun notifyState(c: CallRow) {
        signal(listOf(c.caller, c.callee), linkedMapOf("type" to "call_state", "cat" to NotifyCats.CALLS, "callId" to c.id, "state" to c.state))
        for (u in listOf(c.caller, c.callee).distinct()) publish(u, "call_state", linkedMapOf("callId" to c.id, "state" to c.state))
    }

    /** the lock screen shows only a first name and "লাইফলো কল" — no context, no health detail (review R9) */
    private fun notifyIncoming(c: CallRow) {
        val ps = people(listOf(c.caller, c.callee))
        val caller = ps[c.caller]!!
        val lang = ps[c.callee]!!.lang
        val first = firstName(caller.name)
        val data = linkedMapOf<String, Any?>(
            "type" to "call_incoming", "cat" to NotifyCats.CALLS, "callId" to c.id, "video" to c.video.toString(), "caller" to first,
            "callerUid" to c.caller,
        )
        push.send(listOf(PushItem(c.callee, Lang.pick(lang, "Lifolo call", "লাইফলো কল"), first, data, inbox = false, ttlSeconds = RING_MS / 1000))) // bn-ok
        publish(
            c.callee, "call_incoming",
            linkedMapOf("callId" to c.id, "video" to c.video, "ctx" to c.ctx, "refId" to c.refId, "caller" to peerMap(caller)),
        )
    }

    /** one notification per caller, updated in place ("৩টা মিসড কল"): the earlier bell entries of that caller go (R2) */
    private fun notifyMissed(c: CallRow) {
        val ps = people(listOf(c.caller, c.callee))
        val lang = ps[c.callee]!!.lang
        val first = firstName(ps[c.caller]!!.name)
        val count = jdbc.queryForObject(
            "SELECT count(*) FROM call_sessions WHERE caller_uid = ? AND callee_uid = ? AND state = 'missed' AND created_at > ?",
            Int::class.java, c.caller, c.callee, Sql.ts(now() - DAY_MS),
        ) ?: 1
        jdbc.update("DELETE FROM inbox_items WHERE uid = ? AND data->>'type' = 'call_missed' AND data->>'callerUid' = ?", c.callee, c.caller)
        val title = if (count > 1) {
            Lang.pick(lang, "Lifolo: $count missed calls", Lang.bnNumbers("লাইফলো: ${count}টা মিসড কল")) // bn-ok
        } else {
            Lang.pick(lang, "Lifolo: missed call", "লাইফলো: মিসড কল") // bn-ok
        }
        val data = linkedMapOf<String, Any?>(
            "type" to "call_missed", "cat" to NotifyCats.CALLS, "callId" to c.id, "video" to c.video.toString(), "caller" to first,
            "callerUid" to c.caller, "missedCount" to count.toString(),
        )
        val body = Lang.pick(lang, "$first called you", "$first কল করেছিলেন") // bn-ok
        push.send(listOf(PushItem(c.callee, title, body, data, collapseKey = "missed_${c.caller}".take(60))))
    }

    // ------------------------------------------------------------------ call_start

    fun start(lang: String?, caller: String, callerEmail: String, ctx: String, refId: String, peerUid: String?, video: Boolean?): Map<String, Any?> {
        requireAvailable(lang)
        if (!rate.tryAcquire("call_start:$caller", START_LIMIT, WINDOW_10_MIN)) throw tooMany(lang)
        val nowMs = now()
        sweep(nowMs)
        val d = policy.decide(caller, callerEmail, ctx, refId, peerUid, nowMs)
        when (d.refusal) {
            null -> {}
            ContactPolicy.Refusal.BLOCKED_BY_ME -> throw blockedByMe(lang)
            // blocked by the other side: the same answer as "no relation" (the block is not revealed)
            else -> throw policy.notAllowed(lang)
        }
        val rel = d.rel!!
        val callee = rel.peer
        val p = prefs.get(callee)
        if (!p.all || p.cats[NotifyCats.CALLS] == false) throw callOff(lang)
        // the caller chooses audio or video (owner, 23 Oct); appointments default to the chamber's kind
        val v = video ?: rel.defaultVideo
        val cancelled = ArrayList<String>()
        val call = tx.execute {
            // one decision at a time per person (both sides, fixed order — no deadlock)
            for (u in listOf(caller, callee).sorted()) {
                jdbc.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext(?))) x", Int::class.java, "call:$u")
            }
            // the caller's own earlier ringing calls (redial) stop ringing
            cancelled += jdbc.queryForList("SELECT id FROM call_sessions WHERE caller_uid = ? AND state = 'ringing'", String::class.java, caller)
            val hour = Sql.ts(nowMs - 60 * 60 * 1000L)
            // per pair: at most 3 unanswered calls an hour (a ringing one being replaced counts too) — review R2 …
            val unanswered = jdbc.queryForObject(
                """
                SELECT count(*) FROM call_sessions WHERE caller_uid = ? AND callee_uid = ? AND kind = 'ring' AND created_at > ?
                  AND state IN ('declined', 'missed', 'cancelled', 'ringing')
                """.trimIndent(),
                Int::class.java, caller, callee, hour,
            ) ?: 0
            if (unanswered >= PAIR_UNANSWERED_PER_HOUR) throw tooMany(lang)
            // … after 2 declines in a row, 30 minutes of quiet …
            val last2 = jdbc.query(
                "SELECT state, coalesce(ended_at, created_at) AS at FROM call_sessions WHERE caller_uid = ? AND callee_uid = ? AND kind = 'ring' ORDER BY created_at DESC LIMIT 2",
                { rs, _ -> rs.getString(1) to rs.getTimestamp(2).time },
                caller, callee,
            )
            if (last2.size == 2 && last2.all { it.first == "declined" } && last2[0].second > nowMs - DECLINE_PAUSE_MS) throw tooMany(lang)
            // … and per callee at most 20 rings an hour from everybody together
            val rings = jdbc.queryForObject(
                "SELECT count(*) FROM call_sessions WHERE callee_uid = ? AND kind = 'ring' AND created_at > ?", Int::class.java, callee, hour,
            ) ?: 0
            if (rings >= CALLEE_RINGS_PER_HOUR) throw tooMany(lang)
            if (inCall(callee, nowMs)) throw busy(lang)
            for (id in cancelled) {
                jdbc.update(
                    "UPDATE call_sessions SET state = 'cancelled', ended_at = ?, end_reason = 'redial' WHERE id = ? AND state = 'ringing'", Sql.ts(nowMs), id,
                )
            }
            val id = newCallId()
            jdbc.update(
                """
                INSERT INTO call_sessions (id, room, kind, ctx, ref_id, caller_uid, callee_uid, video, state, created_at)
                VALUES (?, ?, 'ring', ?, ?, ?, ?, ?, 'ringing', ?)
                """.trimIndent(),
                id, "c_$id", ctx, refId, caller, callee, v, Sql.ts(nowMs),
            )
            load(id)!!
        }!!
        for (id in cancelled) load(id)?.let { afterEnd(it) }
        try {
            notifyIncoming(call)
        } catch (ex: Exception) {
            log.error("call_incoming push failed", ex)
        }
        return join(caller, call)
    }

    // ------------------------------------------------------------------ the other actions

    /** who may see / end the call: the two people, and for an appointment room the doctor's current assistants */
    private fun party(uid: String, email: String, c: CallRow): Boolean {
        if (uid == c.caller || uid == c.callee) return true
        if (c.kind != "room") return false
        val a = policy.appointment(c.refId) ?: return false
        return policy.isAssistantOf(a.providerUid, email)
    }

    private fun mine(lang: String?, uid: String, email: String, callId: String?): CallRow {
        if (callId == null || !ID_RE.matches(callId)) throw notFound(lang)
        val c = load(callId) ?: throw notFound(lang)
        if (!party(uid, email, c)) throw notFound(lang)
        return c
    }

    private fun seenColumn(uid: String, c: CallRow): String = if (uid == c.caller) "caller_seen_at" else "callee_seen_at"

    /**
     * call_accept {callId, video?}: only the stored callee (review R5). `video:false` on a video call answers with audio —
     * then both tokens carry only the microphone, and video needs call_upgrade; `video:true` never turns an audio call
     * into video.
     */
    fun accept(lang: String?, uid: String, email: String, callId: String?, video: Boolean?): Map<String, Any?> {
        val c0 = mine(lang, uid, email, callId)
        if (uid != c0.callee || c0.kind != "ring") throw notFound(lang)
        requireAvailable(lang)
        sweep()
        var changed = false
        val c = tx.execute {
            val c = load(c0.id, forUpdate = true)!!
            when (c.state) {
                "accepted" -> {
                    jdbc.update("UPDATE call_sessions SET callee_seen_at = ? WHERE id = ?", Sql.ts(now()), c.id)
                    c
                }
                "ringing" -> {
                    val t = Sql.ts(now())
                    jdbc.update(
                        "UPDATE call_sessions SET state = 'accepted', accepted_at = ?, caller_seen_at = ?, callee_seen_at = ?, video = (video AND ?) WHERE id = ?",
                        t, t, t, video != false, c.id,
                    )
                    changed = true
                    load(c.id)!!
                }
                else -> throw gone(lang)
            }
        }!!
        if (changed) runCatching { notifyState(c) }
        return join(uid, c)
    }

    /**
     * decline (the stored callee), cancel (the stored caller), end (either; a room: patient, doctor or assistant).
     * Ringing: declined / cancelled (end: by who asks); accepted → ended; a finished call just answers its state.
     */
    fun finish(lang: String?, uid: String, email: String, callId: String?, op: String): Map<String, Any?> {
        sweep()
        val c = mine(lang, uid, email, callId)
        when (op) {
            "decline" -> if (uid != c.callee || c.kind != "ring") throw notFound(lang)
            "cancel" -> if (uid != c.caller || c.kind != "ring") throw notFound(lang)
        }
        val ended = end(c.id, null, if (uid == c.caller) "cancelled" else "declined")
        return linkedMapOf("ok" to true, "state" to (ended ?: load(c.id)!!).state)
    }

    /**
     * call_status — also the heartbeat (every 30 s while in a call, review R11) and the token refresh (10-minute tokens,
     * review R3): an accepted call answers a fresh `token` + `url` + `room` to its parties. A call whose relation ended
     * is ended here at once.
     */
    fun status(lang: String?, uid: String, email: String, callId: String?): Map<String, Any?> {
        sweep()
        var c = mine(lang, uid, email, callId)
        val nowMs = now()
        if (c.live) {
            val pinged = if (uid == c.caller) c.copy(callerSeenAt = nowMs) else c.copy(calleeSeenAt = nowMs)
            val why = mustEnd(pinged, nowMs)
            if (why != null) {
                c = end(c.id, why) ?: load(c.id)!!
            } else if (c.state == "accepted") {
                jdbc.update("UPDATE call_sessions SET ${seenColumn(uid, c)} = ? WHERE id = ? AND state = 'accepted'", Sql.ts(nowMs), c.id)
            }
        }
        val other = if (uid == c.caller || uid == c.callee) c.other(uid) else c.caller
        val out = linkedMapOf<String, Any?>("ok" to true)
        out.putAll(item(uid, c, person(other)))
        if (c.state == "accepted" && tokens.available) {
            out["url"] = tokens.url
            out["room"] = c.room
            out["token"] = tokenFor(uid, c)
        }
        if (c.state == "accepted" && c.upgradeBy != null) out["upgrade"] = linkedMapOf("by" to c.upgradeBy, "at" to c.upgradeAt)
        return out
    }

    private fun item(uid: String, c: CallRow, peer: Person): LinkedHashMap<String, Any?> = linkedMapOf<String, Any?>(
        "callId" to c.id, "state" to c.state, "ctx" to c.ctx, "refId" to c.refId, "dir" to (if (uid == c.caller) "out" else "in"),
        "video" to c.video, "peer" to peerMap(peer), "startedAt" to c.createdAt,
    ).apply {
        if (c.acceptedAt != null) put("answeredAt", c.acceptedAt)
        if (c.endedAt != null) put("endedAt", c.endedAt)
        put("durationSec", c.durationSec)
    }

    fun history(uid: String, before: Long?): Map<String, Any?> {
        sweep()
        val b = before?.takeIf { it > 0 && it < Sql.MAX_MS } ?: Sql.MAX_MS
        val rows = jdbc.query(
            """
            ($selectCall WHERE caller_uid = ? AND created_at < ? ORDER BY created_at DESC LIMIT ${PAGE + 1})
            UNION ALL ($selectCall WHERE callee_uid = ? AND created_at < ? ORDER BY created_at DESC LIMIT ${PAGE + 1})
            """.trimIndent(),
            { rs, _ -> CallRow.of(rs) },
            uid, Sql.ts(b), uid, Sql.ts(b),
        ).distinctBy { it.id }.sortedWith(compareByDescending<CallRow> { it.createdAt }.thenBy { it.id })
        val page = rows.take(PAGE)
        val ps = people(page.map { it.other(uid) })
        val out = linkedMapOf<String, Any?>("ok" to true, "items" to page.map { c -> item(uid, c, ps[c.other(uid)]!!).apply { remove("endedAt") } })
        if (rows.size > PAGE) out["next"] = page.last().createdAt
        return out
    }

    fun config(): Map<String, Any?> = linkedMapOf<String, Any?>("ok" to true, "enabled" to tokens.available).apply {
        if (tokens.available) put("url", tokens.url)
    }

    // ------------------------------------------------------------------ audio → video (review R4)

    /** call_upgrade: a party of an accepted audio call asks; the other side gets push/SSE `call_upgrade` {callId, by} */
    fun upgrade(lang: String?, uid: String, email: String, callId: String?): Map<String, Any?> {
        val c = mine(lang, uid, email, callId)
        if (c.kind != "ring" || (uid != c.caller && uid != c.callee)) throw notFound(lang)
        requireAvailable(lang)
        if (c.state != "accepted") throw gone(lang)
        if (c.video) return linkedMapOf("ok" to true, "video" to true)
        if (!rate.tryAcquire("call_upgrade:${c.id}:$uid", 5, WINDOW_10_MIN)) throw tooMany(lang)
        jdbc.update("UPDATE call_sessions SET upgrade_by = ?, upgrade_at = ? WHERE id = ? AND state = 'accepted'", uid, Sql.ts(now()), c.id)
        val other = c.other(uid)
        signal(listOf(other), linkedMapOf("type" to "call_upgrade", "cat" to NotifyCats.CALLS, "callId" to c.id, "by" to uid))
        publish(other, "call_upgrade", linkedMapOf("callId" to c.id, "by" to uid))
        return linkedMapOf("ok" to true, "video" to false)
    }

    /** the other side answers: yes = the call is video now, new tokens (camera + microphone) via this / call_status */
    fun upgradeAnswer(lang: String?, uid: String, email: String, callId: String?, yes: Boolean): Map<String, Any?> {
        val c = mine(lang, uid, email, callId)
        if (c.kind != "ring" || (uid != c.caller && uid != c.callee)) throw notFound(lang)
        if (yes) requireAvailable(lang)
        if (c.state != "accepted") throw gone(lang)
        val by = c.upgradeBy
        if (by == null || by == uid) {
            if (yes && c.video) return linkedMapOf("ok" to true, "callId" to c.id, "url" to tokens.url, "token" to tokenFor(uid, c), "video" to true)
            throw e(lang, 409, "Nothing to answer", "উত্তর দেওয়ার কিছু নেই", "no_upgrade") // bn-ok
        }
        val n = jdbc.update(
            "UPDATE call_sessions SET video = (video OR ?), upgrade_by = NULL, upgrade_at = NULL WHERE id = ? AND state = 'accepted' AND upgrade_by = ?",
            yes, c.id, by,
        )
        if (n == 0) throw gone(lang)
        val event = if (yes) "call_upgraded" else "call_upgrade_declined"
        signal(listOf(by), linkedMapOf("type" to event, "cat" to NotifyCats.CALLS, "callId" to c.id))
        publish(by, event, linkedMapOf("callId" to c.id))
        if (!yes) return linkedMapOf("ok" to true, "video" to false)
        val upgraded = load(c.id)!!
        return linkedMapOf("ok" to true, "callId" to c.id, "url" to tokens.url, "token" to tokenFor(uid, upgraded), "video" to true)
    }

    // ------------------------------------------------------------------ block / report (review R1) — calls AND messages

    fun block(lang: String?, uid: String, email: String, peerUid: String?, callId: String?): Map<String, Any?> {
        if (peerUid == null || !UID_RE.matches(peerUid) || peerUid == uid) throw badRequest(lang)
        if (callId != null) {
            val c = mine(lang, uid, email, callId)
            if ((uid != c.caller && uid != c.callee) || c.other(uid) != peerUid) throw notFound(lang)
        }
        // an unknown uid answers ok too (nothing to learn about who has an account)
        if (blocks.block(uid, peerUid, now()) || blocks.isBlocked(uid, peerUid)) revokeBetween(uid, peerUid)
        return linkedMapOf("ok" to true)
    }

    fun unblock(lang: String?, uid: String, peerUid: String?): Map<String, Any?> {
        if (peerUid == null || !UID_RE.matches(peerUid)) throw badRequest(lang)
        blocks.unblock(uid, peerUid)
        return linkedMapOf("ok" to true)
    }

    fun blockList(uid: String): Map<String, Any?> {
        val rows = blocks.list(uid)
        val ps = people(rows.map { it.first })
        return linkedMapOf(
            "ok" to true,
            "items" to rows.map { (u, at) -> LinkedHashMap(peerMap(ps[u]!!)).apply { put("blockedAt", at) } },
        )
    }

    fun report(lang: String?, uid: String, email: String, callId: String?, reason: String?, note: String?): Map<String, Any?> {
        if (reason == null || reason !in REPORT_REASONS) throw badRequest(lang)
        val text = (note ?: "").trim()
        if (text.length > 500) throw badRequest(lang)
        val c = mine(lang, uid, email, callId)
        if (uid != c.caller && uid != c.callee) throw notFound(lang)
        val meta = item(uid, c, Person(c.other(uid), "", null, null)).apply {
            remove("peer")
            remove("callId")
            put("kind", c.kind)
            put("endReason", c.endReason)
        }
        jdbc.update(
            """
            INSERT INTO call_reports (id, call_id, reporter_uid, reported_uid, reason, note, call_meta, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
            ON CONFLICT (call_id, reporter_uid) DO UPDATE SET reason = EXCLUDED.reason, note = EXCLUDED.note, created_at = EXCLUDED.created_at
            """.trimIndent(),
            Ids.newId(), c.id, uid, c.other(uid), reason, text, mapper.writeValueAsString(meta), Sql.ts(now()),
        )
        return linkedMapOf("ok" to true)
    }

    // ------------------------------------------------------------------ contact_can (the apps' call / message buttons)

    /**
     * canCall / canVideo / canMessage for each {ctx, refId, peerUid?} — [ContactPolicy.decide], the rule call_start and
     * the messages use. canCall/canVideo also need the call server (LIVEKIT_* set). Notification settings and busy are
     * not looked at (no presence leak); call_start still answers call_off / call_busy.
     */
    fun can(uid: String, email: String, items: List<Triple<String, String, String?>>): Map<String, Any?> {
        val nowMs = now()
        val on = tokens.available
        val out = items.map { (ctx, refId, peer) ->
            val d = policy.decide(uid, email, ctx, refId, peer, nowMs)
            linkedMapOf<String, Any?>(
                "ctx" to ctx, "refId" to refId, "peerUid" to (d.rel?.peer ?: peer),
                "canCall" to (d.allowed && on), "canVideo" to (d.allowed && on), "canMessage" to d.allowed,
            ).apply { if (d.refusal == ContactPolicy.Refusal.BLOCKED_BY_ME) put("blocked", true) }
        }
        return linkedMapOf("ok" to true, "items" to out)
    }

    // ------------------------------------------------------------------ appt_join

    fun apptJoin(lang: String?, uid: String, email: String, appointmentId: String?): Map<String, Any?> {
        if (appointmentId == null || !UID_RE.matches(appointmentId)) throw notFound(lang)
        val a = policy.appointment(appointmentId) ?: throw notFound(lang)
        val doctorSide = uid == a.providerUid || (uid != a.patientUid && policy.isAssistantOf(a.providerUid, email))
        if (uid != a.patientUid && !doctorSide) throw notFound(lang)
        requireAvailable(lang)
        if (!rate.tryAcquire("appt_join:$uid", JOIN_LIMIT, WINDOW_10_MIN)) throw tooMany(lang)
        if (!a.online) throw e(lang, 409, "This is not an online appointment", "এটা অনলাইন অ্যাপয়েন্টমেন্ট নয়", "not_online") // bn-ok
        if (a.status != "confirmed" || a.providerSuspended) {
            throw e(lang, 409, "The appointment is not confirmed", "অ্যাপয়েন্টমেন্ট নিশ্চিত হয়নি", "state") // bn-ok
        }
        val nowMs = now()
        if (!a.within(nowMs, ContactPolicy.JOIN_BEFORE_MS, ContactPolicy.AFTER_MS)) {
            throw e(
                lang, 409, "You can join from 15 minutes before the appointment until 3 hours after",
                "অ্যাপয়েন্টমেন্টের ১৫ মিনিট আগে থেকে ৩ ঘণ্টা পর পর্যন্ত ভিডিও কলে যুক্ত হওয়া যায়", "appt_join_window", // bn-ok
            )
        }
        if (!policy.reachable(uid) || !policy.reachable(a.patientUid) || !policy.reachable(a.providerUid)) throw policy.notAllowed(lang)
        val seen = if (doctorSide) "callee_seen_at" else "caller_seen_at"
        var pushReady = false
        val call = tx.execute {
            // one live room per appointment; after it ended, a join opens a new one (new room name — old tokens never
            // lead back in, review R3)
            val id = newCallId()
            jdbc.update(
                """
                INSERT INTO call_sessions (id, room, kind, ctx, ref_id, caller_uid, callee_uid, video, state, created_at, accepted_at)
                VALUES (?, ?, 'room', 'appointment', ?, ?, ?, TRUE, 'accepted', ?, ?)
                ON CONFLICT (ref_id) WHERE kind = 'room' AND state = 'accepted' DO NOTHING
                """.trimIndent(),
                id, "c_$id", a.id, a.patientUid, a.providerUid, Sql.ts(nowMs), Sql.ts(nowMs),
            )
            val c = jdbc.query(
                "$selectCall WHERE kind = 'room' AND ref_id = ? AND state = 'accepted' FOR UPDATE", { rs, _ -> CallRow.of(rs) }, a.id,
            ).first()
            if (doctorSide) {
                // the call server takes 2 per room: the patient and ONE of doctor / assistant (the one who is there now)
                val hostHere = c.hostUid != null && c.hostUid != uid && (c.calleeSeenAt ?: 0L) > nowMs - HEARTBEAT_GONE_MS
                if (hostHere) {
                    throw e(
                        lang, 409, "Someone from the doctor's side is already in this video call",
                        "ডাক্তারের পক্ষ থেকে একজন আগেই এই ভিডিও কলে আছেন — একসাথে দুজন যুক্ত হওয়া যায় না", "room_full", // bn-ok
                    )
                }
                jdbc.update("UPDATE call_sessions SET host_uid = ? WHERE id = ?", uid, c.id)
            }
            jdbc.update("UPDATE call_sessions SET $seen = ? WHERE id = ?", Sql.ts(nowMs), c.id)
            if (doctorSide && (c.readyPushedAt == null || nowMs - c.readyPushedAt >= READY_EVERY_MS)) {
                jdbc.update("UPDATE call_sessions SET ready_pushed_at = ? WHERE id = ?", Sql.ts(nowMs), c.id)
                pushReady = true
            }
            load(c.id)!!
        }!!
        if (doctorSide) markDoctorJoined(a.id)
        if (pushReady) {
            try {
                push.pushUsers(listOf(a.patientUid)) { _, l ->
                    PushText(
                        Lang.pick(l, "Lifolo: join the video call", "লাইফলো: ভিডিও কলে যুক্ত হোন"), // bn-ok
                        "",
                        mapOf("type" to "appt_call_ready", "cat" to NotifyCats.APPOINTMENTS, "appointmentId" to a.id, "callId" to call.id),
                    )
                }
                publish(a.patientUid, "appt_call_ready", linkedMapOf("appointmentId" to a.id, "callId" to call.id))
            } catch (ex: Exception) {
                log.error("appt_call_ready push failed", ex)
            }
        }
        return linkedMapOf(
            "ok" to true, "callId" to call.id, "room" to call.room, "url" to tokens.url, "token" to tokenFor(uid, call), "video" to true,
        )
    }

    @Volatile
    private var doctorJoinedColumn: Boolean? = null

    /** appointments.doctor_joined_at (V621, online-chamber follow-up): first time the doctor's side joined — when the column exists */
    private fun markDoctorJoined(appointmentId: String) {
        try {
            val has = doctorJoinedColumn ?: (
                jdbc.queryForList(
                    "SELECT 1 FROM information_schema.columns WHERE table_name = 'appointments' AND column_name = 'doctor_joined_at'",
                ).isNotEmpty()
                ).also { if (it) doctorJoinedColumn = true }
            if (has) jdbc.update("UPDATE appointments SET doctor_joined_at = COALESCE(doctor_joined_at, now()) WHERE id = ?", appointmentId)
        } catch (ex: Exception) {
            log.warn("doctor_joined_at failed: {}", ex.message)
        }
    }

    // ------------------------------------------------------------------ LiveKit webhooks (review R11)

    /**
     * A verified webhook from the call server: `room_finished` ends the call of that room; `participant_left` ends a
     * ring call (one side gone — 1:1), cancels a ringing one when the caller left, and frees the doctor-side seat of an
     * appointment room (the room itself stays for a rejoin until room_finished / heartbeats). Returns what it did.
     */
    fun onWebhook(event: String, room: String?, identity: String?): String {
        if (room.isNullOrEmpty()) return "ignored"
        val c = jdbc.query("$selectCall WHERE room = ? ORDER BY created_at DESC LIMIT 1", { rs, _ -> CallRow.of(rs) }, room).firstOrNull()
            ?: return "unknown"
        if (!c.live) return "done"
        return when (event) {
            "room_finished" -> if (end(c.id, "room_finished") != null) "ended" else "done"
            "participant_left" -> when {
                c.kind == "room" -> {
                    if (identity != null && identity == c.hostUid) jdbc.update("UPDATE call_sessions SET host_uid = NULL WHERE id = ? AND host_uid = ?", c.id, identity)
                    "left"
                }
                c.state == "accepted" && (identity == c.caller || identity == c.callee) -> if (end(c.id, "left") != null) "ended" else "done"
                c.state == "ringing" && identity == c.caller -> if (end(c.id, "left", "cancelled") != null) "ended" else "done"
                else -> "ignored"
            }
            else -> "ignored"
        }
    }

    // ------------------------------------------------------------------ retention / account deletion

    /** call metadata older than [RETENTION_DAYS] days is deleted (daily job); handled call reports after a year */
    fun deleteOld(nowMs: Long): Int {
        var total = 0
        while (true) {
            val n = jdbc.update(
                "DELETE FROM call_sessions WHERE id IN (SELECT id FROM call_sessions WHERE created_at < ? LIMIT 2000)",
                Sql.ts(nowMs - RETENTION_DAYS * DAY_MS),
            )
            total += n
            if (n < 2000) break
        }
        jdbc.update("DELETE FROM call_reports WHERE created_at < ? AND status <> 'open'", Sql.ts(nowMs - 365 * DAY_MS))
        return total
    }

    fun deleteAllOf(uid: String): Int {
        jdbc.update("DELETE FROM call_reports WHERE reporter_uid = ?", uid)
        return jdbc.update("DELETE FROM call_sessions WHERE caller_uid = ? OR callee_uid = ?", uid, uid)
    }

    companion object {
        const val RING_MS = 45_000L

        /** 3 heartbeats (30 s apart) missed */
        const val HEARTBEAT_GONE_MS = 90_000L
        const val READY_EVERY_MS = 2L * 60 * 1000
        const val START_LIMIT = 10L
        const val JOIN_LIMIT = 30L
        const val PAIR_UNANSWERED_PER_HOUR = 3
        const val DECLINE_PAUSE_MS = 30L * 60 * 1000
        const val CALLEE_RINGS_PER_HOUR = 20
        val WINDOW_10_MIN: Duration = Duration.ofMinutes(10)
        const val PAGE = 30
        const val RETENTION_DAYS = 180L
        const val DAY_MS = 24L * 60 * 60 * 1000
        val REPORT_REASONS = setOf("harassment", "spam", "fake", "other")
        val ID_RE = Regex("^[A-Za-z0-9_-]{1,64}$")
        val UID_RE = Regex("^[A-Za-z0-9_-]{1,128}$")

        /** 32 characters of 62 (~190 bits): the call id is also the room name (review R5/R13) */
        fun newCallId(): String = Ids.newId(32)

        private val TITLES = setOf("মোঃ", "মো.", "মোহাম্মদ", "মুহাম্মদ", "ডা.", "ডাঃ", "md", "md.", "mohammad", "muhammad", "dr", "dr.") // bn-ok

        /** the first name only (lock screen, review R9): "মোঃ রহিম উদ্দিন" → "রহিম" */
        fun firstName(name: String): String {
            val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            return parts.firstOrNull { it.lowercase() !in TITLES } ?: parts.firstOrNull() ?: "Lifolo"
        }
    }
}
