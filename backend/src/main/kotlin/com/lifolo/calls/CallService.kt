package com.lifolo.calls

import com.lifolo.common.ApiException
import com.lifolo.common.Ids
import com.lifolo.common.Lang
import com.lifolo.common.RateLimiter
import com.lifolo.common.Sql
import com.lifolo.common.StoredFiles
import com.lifolo.notify.NotifyCats
import com.lifolo.notify.NotifyPrefsService
import com.lifolo.notify.PushItem
import com.lifolo.notify.PushService
import com.lifolo.notify.PushText
import com.lifolo.notify.SseHub
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.time.Duration

/** one row of call_sessions */
data class CallRow(
    val id: String,
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
    val readyPushedAt: Long?,
) {
    val room: String get() = "c_$id"
    val live: Boolean get() = state == "ringing" || state == "accepted"
    fun other(uid: String): String = if (uid == caller) callee else caller
    val durationSec: Long get() = if (acceptedAt != null && endedAt != null) maxOf(0L, (endedAt - acceptedAt) / 1000) else 0L

    companion object {
        fun of(rs: ResultSet) = CallRow(
            rs.getString("id"), rs.getString("kind"), rs.getString("ctx"), rs.getString("ref_id"), rs.getString("caller_uid"),
            rs.getString("callee_uid"), rs.getBoolean("video"), rs.getString("state"), rs.getTimestamp("created_at").time,
            rs.getTimestamp("accepted_at")?.time, rs.getTimestamp("ended_at")?.time, rs.getTimestamp("ready_pushed_at")?.time,
        )
    }
}

/**
 * In-app calls (backend/docs/CALLS.md): the call_sessions state machine, busy/off checks, LiveKit tokens, pushes and
 * SSE events. Nothing about the conversation itself is stored — only who, when, how long.
 */
@Service
class CallService(
    private val jdbc: JdbcTemplate,
    private val relations: CallRelations,
    private val tokens: LiveKitTokens,
    private val prefs: NotifyPrefsService,
    private val push: PushService,
    private val sse: SseHub,
    private val rate: RateLimiter,
    private val files: StoredFiles,
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

    private fun notFound(lang: String?) = ApiException(404, Lang.tr(lang, "Not found", "পাওয়া যায়নি"))

    fun requireAvailable(lang: String?) {
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

    fun peerMap(p: Person): Map<String, Any?> = linkedMapOf<String, Any?>("uid" to p.uid, "name" to p.name).apply {
        if (p.photoThumb != null) put("photoThumb", p.photoThumb)
    }

    private fun tokenFor(uid: String, name: String, call: CallRow): String = tokens.token(uid, name.ifEmpty { "Lifolo" }, call.room, now())

    private fun join(uid: String, call: CallRow, peer: Person): Map<String, Any?> {
        val me = people(listOf(uid))[uid]!!
        return linkedMapOf(
            "ok" to true, "callId" to call.id, "room" to call.room, "url" to tokens.url, "token" to tokenFor(uid, me.name, call),
            "video" to call.video, "peer" to peerMap(peer),
        )
    }

    // ------------------------------------------------------------------ rows

    private val selectCall = "SELECT * FROM call_sessions"

    fun load(id: String, forUpdate: Boolean = false): CallRow? =
        jdbc.query("$selectCall WHERE id = ?" + (if (forUpdate) " FOR UPDATE" else ""), { rs, _ -> CallRow.of(rs) }, id).firstOrNull()

    /** an account that can take part: exists, not deactivated / being deleted / disabled */
    private fun reachable(uid: String): Boolean = jdbc.queryForList(
        "SELECT 1 FROM accounts WHERE uid = ? AND deactivated_at IS NULL AND delete_at IS NULL AND disabled_reason IS NULL", uid,
    ).isNotEmpty()

    /** someone with a ringing (not yet timed out) or accepted (answered < 2 h ago) ring call; rooms do not count */
    private fun inCall(uid: String, nowMs: Long): Boolean = jdbc.queryForList(
        """
        SELECT 1 FROM call_sessions WHERE kind = 'ring' AND (caller_uid = ? OR callee_uid = ?)
          AND ((state = 'ringing' AND created_at > ?) OR (state = 'accepted' AND accepted_at > ?)) LIMIT 1
        """.trimIndent(),
        uid, uid, Sql.ts(nowMs - RING_MS), Sql.ts(nowMs - TALK_MS),
    ).isNotEmpty()

    // ------------------------------------------------------------------ sweep (45 s ring timeout, 2 h talk limit)

    /**
     * Ringing calls older than 45 s → `missed` (callee: push `call_missed` + bell entry; both: `call_state`);
     * accepted calls answered more than 2 h ago → `ended` (the token has expired). Safe to run anywhere, any time:
     * each row is moved by exactly one UPDATE. Returns how many calls changed.
     */
    fun sweep(nowMs: Long = now()): Int {
        val missed = jdbc.query(
            """
            UPDATE call_sessions SET state = 'missed', ended_at = ?
            WHERE id IN (SELECT id FROM call_sessions WHERE state = 'ringing' AND created_at <= ? ORDER BY created_at LIMIT 500 FOR UPDATE SKIP LOCKED)
            RETURNING *
            """.trimIndent(),
            { rs, _ -> CallRow.of(rs) },
            Sql.ts(nowMs), Sql.ts(nowMs - RING_MS),
        )
        val stale = jdbc.update(
            """
            UPDATE call_sessions SET state = 'ended', ended_at = accepted_at + interval '2 hours'
            WHERE id IN (SELECT id FROM call_sessions WHERE state = 'accepted' AND accepted_at <= ? LIMIT 500 FOR UPDATE SKIP LOCKED)
            """.trimIndent(),
            Sql.ts(nowMs - TALK_MS),
        )
        for (c in missed) {
            try {
                notifyMissed(c)
                notifyState(c)
            } catch (ex: Exception) {
                log.error("missed call notice failed", ex)
            }
        }
        return missed.size + stale
    }

    // ------------------------------------------------------------------ notices

    private fun publish(uid: String, event: String, data: Map<String, Any?>) {
        try {
            sse.publish("user:$uid", event, data)
        } catch (ex: Exception) {
            log.debug("sse {} failed: {}", event, ex.message)
        }
    }

    /** `call_state` to both sides: FCM (no bell entry, 60 s) + SSE — ringing stops on every device */
    private fun notifyState(c: CallRow) {
        val data = linkedMapOf<String, Any?>("type" to "call_state", "cat" to NotifyCats.CALLS, "callId" to c.id, "state" to c.state)
        push.send(listOf(c.caller, c.callee).distinct().map { PushItem(it, "", "", data, inbox = false, ttlSeconds = 60) })
        for (u in listOf(c.caller, c.callee).distinct()) publish(u, "call_state", linkedMapOf("callId" to c.id, "state" to c.state))
    }

    private fun ctxData(c: CallRow, caller: Person): LinkedHashMap<String, Any?> = linkedMapOf(
        "callId" to c.id, "video" to c.video.toString(), "caller" to caller.name, "callerUid" to caller.uid, "ctx" to c.ctx, "refId" to c.refId,
    )

    private fun notifyIncoming(c: CallRow) {
        val ps = people(listOf(c.caller, c.callee))
        val caller = ps[c.caller]!!
        val lang = ps[c.callee]!!.lang
        val name = caller.name.ifEmpty { "Lifolo" }
        val title = if (c.video) {
            Lang.pick(lang, "Video call: $name", "ভিডিও কল: $name") // bn-ok
        } else {
            Lang.pick(lang, "Call: $name", "অ্যাপে কল: $name") // bn-ok
        }
        val data = linkedMapOf<String, Any?>("type" to "call_incoming", "cat" to NotifyCats.CALLS) + ctxData(c, caller)
        push.send(listOf(PushItem(c.callee, title, Lang.pick(lang, "Tap to answer", "ধরতে চাপুন"), data, inbox = false, ttlSeconds = RING_MS / 1000))) // bn-ok
        publish(
            c.callee, "call_incoming",
            linkedMapOf("callId" to c.id, "video" to c.video, "ctx" to c.ctx, "refId" to c.refId, "caller" to peerMap(caller)),
        )
    }

    private fun notifyMissed(c: CallRow) {
        val ps = people(listOf(c.caller, c.callee))
        val caller = ps[c.caller]!!
        val lang = ps[c.callee]!!.lang
        val name = caller.name.ifEmpty { "Lifolo" }
        val data = linkedMapOf<String, Any?>("type" to "call_missed", "cat" to NotifyCats.CALLS) + ctxData(c, caller)
        push.send(
            listOf(
                PushItem(
                    c.callee, Lang.pick(lang, "Missed call", "মিসড কল"), // bn-ok
                    Lang.pick(lang, "$name called you in the app", "$name আপনাকে অ্যাপে কল করেছিলেন"), data, // bn-ok
                ),
            ),
        )
    }

    // ------------------------------------------------------------------ call_start

    fun start(lang: String?, caller: String, callerEmail: String, ctx: String, refId: String, peerUid: String?, video: Boolean?): Map<String, Any?> {
        requireAvailable(lang)
        if (!rate.tryAcquire("call_start:$caller", START_LIMIT, START_WINDOW)) {
            throw e(lang, 429, "Too many calls — try again in a few minutes", "অনেকবার কল করেছেন — কয়েক মিনিট পরে চেষ্টা করুন", "call_rate") // bn-ok
        }
        val nowMs = now()
        sweep(nowMs)
        val rel = relations.resolve(lang, caller, callerEmail, ctx, refId, peerUid, nowMs)
        val callee = rel.peer
        if (!reachable(callee) || !reachable(caller)) throw relations.notAllowed(lang)
        val p = prefs.get(callee)
        if (!p.all || p.cats[NotifyCats.CALLS] == false) throw callOff(lang)
        val v = video ?: rel.defaultVideo
        val cancelled = ArrayList<CallRow>()
        val call = tx.execute {
            // one decision at a time per person (both sides, fixed order — no deadlock)
            for (u in listOf(caller, callee).sorted()) jdbc.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext(?))) x", Int::class.java, "call:$u")
            // the caller's own earlier ringing calls (redial) stop ringing
            cancelled += jdbc.query(
                "UPDATE call_sessions SET state = 'cancelled', ended_at = ? WHERE caller_uid = ? AND state = 'ringing' RETURNING *",
                { rs, _ -> CallRow.of(rs) },
                Sql.ts(nowMs), caller,
            )
            if (inCall(callee, nowMs)) throw busy(lang)
            val id = Ids.newId()
            jdbc.update(
                "INSERT INTO call_sessions (id, kind, ctx, ref_id, caller_uid, callee_uid, video, state, created_at) VALUES (?, 'ring', ?, ?, ?, ?, ?, 'ringing', ?)",
                id, ctx, refId, caller, callee, v, Sql.ts(nowMs),
            )
            load(id)!!
        }!!
        cancelled.forEach { runCatching { notifyState(it) } }
        try {
            notifyIncoming(call)
        } catch (ex: Exception) {
            log.error("call_incoming push failed", ex)
        }
        return join(caller, call, people(listOf(callee))[callee]!!)
    }

    // ------------------------------------------------------------------ the other actions

    /** who may see / end the call: the two people, and for an appointment room the doctor's assistants */
    private fun party(uid: String, email: String, c: CallRow): Boolean {
        if (uid == c.caller || uid == c.callee) return true
        if (c.ctx != "appointment" || c.kind != "room") return false
        val a = relations.appointment(c.refId) ?: return false
        return relations.isAssistantOf(a.providerUid, email)
    }

    private fun mine(lang: String?, uid: String, email: String, callId: String?, forUpdate: Boolean = false): CallRow {
        if (callId == null || !Regex("^[A-Za-z0-9_-]{1,64}$").matches(callId)) throw notFound(lang)
        val c = load(callId, forUpdate) ?: throw notFound(lang)
        if (!party(uid, email, c)) throw notFound(lang)
        return c
    }

    fun accept(lang: String?, uid: String, email: String, callId: String?): Map<String, Any?> {
        requireAvailable(lang)
        sweep()
        var changed = false
        val c = tx.execute {
            val c = mine(lang, uid, email, callId, forUpdate = true)
            if (uid != c.callee || c.kind != "ring") throw notFound(lang)
            when (c.state) {
                "accepted" -> c
                "ringing" -> {
                    jdbc.update("UPDATE call_sessions SET state = 'accepted', accepted_at = ? WHERE id = ?", Sql.ts(now()), c.id)
                    changed = true
                    load(c.id)!!
                }
                else -> throw gone(lang)
            }
        }!!
        if (changed) runCatching { notifyState(c) }
        return join(uid, c, people(listOf(c.caller))[c.caller]!!)
    }

    /**
     * decline (callee), cancel (caller), end (either). Ringing: declined / cancelled (end: by who asks); accepted → ended;
     * a finished call just answers its state.
     */
    fun finish(lang: String?, uid: String, email: String, callId: String?, op: String): Map<String, Any?> {
        sweep()
        var changed = false
        val c = tx.execute {
            val c = mine(lang, uid, email, callId, forUpdate = true)
            when (op) {
                "decline" -> if (uid != c.callee) throw notFound(lang)
                "cancel" -> if (uid != c.caller) throw notFound(lang)
            }
            val next = when (c.state) {
                "ringing" -> if (uid == c.caller) "cancelled" else "declined"
                "accepted" -> "ended"
                else -> null
            }
            if (next == null) {
                c
            } else {
                jdbc.update("UPDATE call_sessions SET state = ?, ended_at = ? WHERE id = ?", next, Sql.ts(now()), c.id)
                changed = true
                load(c.id)!!
            }
        }!!
        if (changed) runCatching { notifyState(c) }
        return linkedMapOf("ok" to true, "state" to c.state)
    }

    fun status(lang: String?, uid: String, email: String, callId: String?): Map<String, Any?> {
        sweep()
        val c = mine(lang, uid, email, callId)
        val other = if (uid == c.caller || uid == c.callee) c.other(uid) else c.caller
        return linkedMapOf<String, Any?>("ok" to true).apply { putAll(item(uid, c, people(listOf(other))[other]!!)) }
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

    // ------------------------------------------------------------------ appt_join

    fun apptJoin(lang: String?, uid: String, email: String, appointmentId: String?): Map<String, Any?> {
        requireAvailable(lang)
        if (!rate.tryAcquire("appt_join:$uid", JOIN_LIMIT, START_WINDOW)) {
            throw e(lang, 429, "Too many tries — wait a few minutes", "অনেকবার চেষ্টা করেছেন — কয়েক মিনিট পরে চেষ্টা করুন", "call_rate") // bn-ok
        }
        if (appointmentId == null || !Regex("^[A-Za-z0-9_-]{1,64}$").matches(appointmentId)) throw notFound(lang)
        val a = relations.appointment(appointmentId) ?: throw notFound(lang)
        val doctorSide = uid == a.providerUid || (uid != a.patientUid && relations.isAssistantOf(a.providerUid, email))
        if (uid != a.patientUid && !doctorSide) throw notFound(lang)
        if (!a.online) throw e(lang, 409, "This is not an online appointment", "এটা অনলাইন অ্যাপয়েন্টমেন্ট নয়", "not_online") // bn-ok
        if (a.status != "confirmed" || a.providerSuspended) {
            throw e(lang, 409, "The appointment is not confirmed", "অ্যাপয়েন্টমেন্ট নিশ্চিত হয়নি", "state") // bn-ok
        }
        val nowMs = now()
        if (!a.within(nowMs, CallRelations.JOIN_BEFORE_MS, CallRelations.AFTER_MS)) {
            throw e(
                lang, 409, "You can join from 15 minutes before the appointment until 3 hours after",
                "অ্যাপয়েন্টমেন্টের ১৫ মিনিট আগে থেকে ৩ ঘণ্টা পর পর্যন্ত ভিডিও কলে যুক্ত হওয়া যায়", "appt_join_window", // bn-ok
            )
        }
        if (!reachable(uid)) throw relations.notAllowed(lang)
        var pushReady = false
        val call = tx.execute {
            jdbc.update(
                """
                INSERT INTO call_sessions (id, kind, ctx, ref_id, caller_uid, callee_uid, video, state, created_at, accepted_at)
                VALUES (?, 'room', 'appointment', ?, ?, ?, TRUE, 'accepted', ?, ?) ON CONFLICT (ref_id) WHERE kind = 'room' DO NOTHING
                """.trimIndent(),
                Ids.newId(), a.id, a.patientUid, a.providerUid, Sql.ts(nowMs), Sql.ts(nowMs),
            )
            val c = jdbc.query("$selectCall WHERE kind = 'room' AND ref_id = ? FOR UPDATE", { rs, _ -> CallRow.of(rs) }, a.id).first()
            // joining again re-opens the room (dropped connection, the doctor comes back)
            if (c.state != "accepted") {
                jdbc.update("UPDATE call_sessions SET state = 'accepted', accepted_at = ?, ended_at = NULL WHERE id = ?", Sql.ts(nowMs), c.id)
            }
            if (doctorSide && (c.readyPushedAt == null || nowMs - c.readyPushedAt >= READY_EVERY_MS)) {
                jdbc.update("UPDATE call_sessions SET ready_pushed_at = ? WHERE id = ?", Sql.ts(nowMs), c.id)
                pushReady = true
            }
            load(c.id)!!
        }!!
        if (pushReady) {
            try {
                push.pushUsers(listOf(a.patientUid)) { _, l ->
                    PushText(
                        Lang.pick(l, "The doctor is in the video call — join now", "ডাক্তার ভিডিও কলে আছেন — যুক্ত হোন"), // bn-ok
                        "",
                        mapOf("type" to "appt_call_ready", "cat" to NotifyCats.APPOINTMENTS, "appointmentId" to a.id, "callId" to call.id),
                    )
                }
                publish(a.patientUid, "appt_call_ready", linkedMapOf("appointmentId" to a.id, "callId" to call.id))
            } catch (ex: Exception) {
                log.error("appt_call_ready push failed", ex)
            }
        }
        val me = people(listOf(uid))[uid]!!
        return linkedMapOf(
            "ok" to true, "callId" to call.id, "room" to call.room, "url" to tokens.url, "token" to tokenFor(uid, me.name, call), "video" to true,
        )
    }

    // ------------------------------------------------------------------ retention / account deletion

    /** metadata older than [RETENTION_DAYS] days is deleted (daily job) */
    fun deleteOld(nowMs: Long): Int {
        var total = 0
        while (true) {
            val n = jdbc.update(
                "DELETE FROM call_sessions WHERE id IN (SELECT id FROM call_sessions WHERE created_at < ? LIMIT 2000)",
                Sql.ts(nowMs - RETENTION_DAYS * 24 * 60 * 60 * 1000L),
            )
            total += n
            if (n < 2000) return total
        }
    }

    /** account deletion requested: live calls of the person stop */
    fun endAllOf(uid: String) {
        val changed = jdbc.query(
            """
            UPDATE call_sessions SET state = CASE WHEN state = 'ringing' THEN 'cancelled' ELSE 'ended' END, ended_at = ?
            WHERE (caller_uid = ? OR callee_uid = ?) AND state IN ('ringing', 'accepted') RETURNING *
            """.trimIndent(),
            { rs, _ -> CallRow.of(rs) },
            Sql.ts(now()), uid, uid,
        )
        changed.forEach { runCatching { notifyState(it) } }
    }

    fun deleteAllOf(uid: String): Int = jdbc.update("DELETE FROM call_sessions WHERE caller_uid = ? OR callee_uid = ?", uid, uid)

    companion object {
        const val RING_MS = 45_000L
        const val TALK_MS = 2L * 60 * 60 * 1000
        const val READY_EVERY_MS = 2L * 60 * 1000
        const val START_LIMIT = 10L
        const val JOIN_LIMIT = 30L
        val START_WINDOW: Duration = Duration.ofMinutes(10)
        const val PAGE = 30
        const val RETENTION_DAYS = 180L
    }
}
