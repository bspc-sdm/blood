package com.lifolo.chat

import com.fasterxml.jackson.databind.ObjectMapper
import com.lifolo.calls.CallService
import com.lifolo.common.ApiException
import com.lifolo.common.DhakaTime
import com.lifolo.common.Ids
import com.lifolo.common.Lang
import com.lifolo.common.ObjectStorage
import com.lifolo.common.RateLimiter
import com.lifolo.common.Sql
import com.lifolo.common.StoredFiles
import com.lifolo.contact.BlockService
import com.lifolo.contact.ContactHistory
import com.lifolo.contact.ContactPolicy
import com.lifolo.notify.NotifyCats
import com.lifolo.notify.PushItem
import com.lifolo.notify.PushService
import com.lifolo.notify.SseHub
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.time.Duration
import java.time.ZoneId
import java.util.Base64

/** one row of chat_threads (a < b) */
data class ThreadRow(
    val id: String,
    val ctx: String,
    val refId: String,
    val a: String,
    val b: String,
    val lastAt: Long?,
    val closedAt: Long?,
    val runUid: String?,
    val runCount: Int,
) {
    fun has(uid: String) = uid == a || uid == b
    fun other(uid: String) = if (uid == a) b else a

    companion object {
        fun of(rs: ResultSet) = ThreadRow(
            rs.getString("id"), rs.getString("ctx"), rs.getString("ref_id"), rs.getString("uid_a"), rs.getString("uid_b"),
            rs.getTimestamp("last_at")?.time, rs.getTimestamp("closed_at")?.time, rs.getString("run_uid"), rs.getInt("run_count"),
        )
    }
}

/** one row of chat_messages */
data class MessageRow(
    val id: String,
    val threadId: String,
    val from: String,
    val clientId: String,
    val kind: String,
    val text: String?,
    val photoKey: String?,
    val photoData: String?,
    val thumb: String?,
    val lat: Double?,
    val lng: Double?,
    val at: Long,
    val readAt: Long?,
    val unsentAt: Long?,
) {
    val hasPhoto: Boolean get() = photoKey != null || photoData != null

    companion object {
        fun of(rs: ResultSet) = MessageRow(
            rs.getString("id"), rs.getString("thread_id"), rs.getString("from_uid"), rs.getString("client_id"), rs.getString("kind"),
            rs.getString("text"), rs.getString("photo_key"), rs.getString("photo_data"), rs.getString("thumb"),
            rs.getDouble("lat").takeUnless { rs.wasNull() }, rs.getDouble("lng").takeUnless { rs.wasNull() },
            rs.getTimestamp("at").time, rs.getTimestamp("read_at")?.time, rs.getTimestamp("unsent_at")?.time,
        )
    }
}

/**
 * In-app messages (backend/docs/CHAT.md). Who may write to whom is [ContactPolicy] (shared with the calls: relation,
 * donor rule, blocks, demo/real, deactivated / deleting / disabled accounts). Threads are per pair + ctx + refId; a
 * thread whose relation ended is read-only and deleted 90 days later.
 */
@Service
class ChatService(
    private val jdbc: JdbcTemplate,
    private val policy: ContactPolicy,
    private val blocks: BlockService,
    private val calls: CallService,
    private val push: PushService,
    private val sse: SseHub,
    private val rate: RateLimiter,
    private val files: StoredFiles,
    private val storage: ObjectStorage,
    private val photos: ChatPhotos,
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

    private fun e(lang: String?, status: Int, en: String, bn: String, code: String? = null) = ApiException(status, Lang.tr(lang, en, bn), code)

    fun bad(lang: String?) = e(lang, 400, "Invalid request", "অনুরোধ ঠিক নেই", "bad_request") // bn-ok

    fun notFound(lang: String?) = e(lang, 404, "Not found", "পাওয়া যায়নি")

    fun notAllowed(lang: String?) =
        e(lang, 403, "You can't message this person in the app", "এই ব্যক্তিকে অ্যাপে মেসেজ পাঠানো যায় না", "chat_not_allowed") // bn-ok

    private fun closed(lang: String?) =
        e(lang, 409, "This conversation has ended — you can still read it", "এই কথোপকথন শেষ হয়েছে — শুধু পড়া যাবে", "chat_closed") // bn-ok

    private fun tooFast(lang: String?) =
        e(lang, 429, "Too many messages — try again in a minute", "অনেক মেসেজ পাঠিয়েছেন — এক মিনিট পরে চেষ্টা করুন", "chat_rate") // bn-ok

    private fun tooManyPhotos(lang: String?) =
        e(lang, 429, "You can send 20 photos a day", "দিনে ২০টা ছবি পাঠানো যায়", "chat_rate") // bn-ok

    private fun waitReply(lang: String?) =
        e(lang, 429, "Please wait for a reply before sending more", "আরও পাঠানোর আগে উত্তরের অপেক্ষা করুন", "chat_rate") // bn-ok

    private fun unsendLate(lang: String?) =
        e(lang, 409, "A message can be deleted only within 10 minutes", "মেসেজ ১০ মিনিটের মধ্যেই মোছা যায়", "chat_unsend_window") // bn-ok

    private fun badPhoto(lang: String?) =
        e(lang, 400, "Send a JPEG photo of up to 1 MB", "১ MB পর্যন্ত JPEG ছবি পাঠান", "bad_photo") // bn-ok

    private fun refusal(lang: String?, r: ContactPolicy.Refusal?): ApiException =
        if (r == ContactPolicy.Refusal.BLOCKED_BY_ME) calls.blockedByMe(lang) else notAllowed(lang)

    // ------------------------------------------------------------------ rows

    private fun thread(id: String, forUpdate: Boolean = false): ThreadRow? =
        jdbc.query("SELECT * FROM chat_threads WHERE id = ?" + (if (forUpdate) " FOR UPDATE" else ""), { rs, _ -> ThreadRow.of(rs) }, id).firstOrNull()

    private fun threadOf(x: String, y: String, ctx: String, refId: String): ThreadRow? {
        val (a, b) = pair(x, y)
        return jdbc.query(
            "SELECT * FROM chat_threads WHERE uid_a = ? AND uid_b = ? AND ctx = ? AND ref_id = ?", { rs, _ -> ThreadRow.of(rs) }, a, b, ctx, refId,
        ).firstOrNull()
    }

    private fun message(id: String): MessageRow? =
        jdbc.query("SELECT * FROM chat_messages WHERE id = ?", { rs, _ -> MessageRow.of(rs) }, id).firstOrNull()

    /** a thread of [uid] (member) or 404 */
    private fun mine(lang: String?, uid: String, threadId: String?): ThreadRow {
        if (threadId == null || !ID_RE.matches(threadId)) throw notFound(lang)
        val t = thread(threadId) ?: throw notFound(lang)
        if (!t.has(uid)) throw notFound(lang)
        return t
    }

    // ------------------------------------------------------------------ access (who may send in a thread now)

    data class Access(val canSend: Boolean, val closed: Boolean, val blocked: Boolean, val refusal: ContactPolicy.Refusal?)

    /** does [uid] have the relation with [peer] about the thread's record (either side's view counts for "still open")? */
    private fun relationOf(uid: String, email: String, t: ThreadRow, nowMs: Long): ContactPolicy.Decision {
        val d = policy.decide(uid, email, t.ctx, t.refId, t.other(uid), nowMs)
        return if (d.rel != null && d.rel.peer != t.other(uid)) ContactPolicy.Decision(null, ContactPolicy.Refusal.NO_RELATION) else d
    }

    private fun peerHasRelation(t: ThreadRow, uid: String, nowMs: Long): Boolean {
        val peer = t.other(uid)
        val rel = policy.check(peer, policy.accountEmail(peer), t.ctx, t.refId, uid, nowMs)
        return rel != null && rel.peer == uid
    }

    /**
     * May [uid] send in [t] now? No relation from either side → the thread is closed (read-only, kept 90 days); the
     * relation active again → re-opened. Blocks and unreachable accounts make it read-only without closing it.
     */
    fun access(uid: String, email: String, t: ThreadRow, nowMs: Long = now()): Access {
        val d = relationOf(uid, email, t, nowMs)
        if (d.rel == null) {
            if (peerHasRelation(t, uid, nowMs)) {
                reopen(t)
                return Access(false, false, false, ContactPolicy.Refusal.NO_RELATION)
            }
            if (t.closedAt == null) jdbc.update("UPDATE chat_threads SET closed_at = ? WHERE id = ? AND closed_at IS NULL", Sql.ts(nowMs), t.id)
            return Access(false, true, false, ContactPolicy.Refusal.NO_RELATION)
        }
        reopen(t)
        return Access(d.allowed, false, d.refusal == ContactPolicy.Refusal.BLOCKED_BY_ME, d.refusal)
    }

    private fun reopen(t: ThreadRow) {
        if (t.closedAt != null) jdbc.update("UPDATE chat_threads SET closed_at = NULL WHERE id = ?", t.id)
    }

    // ------------------------------------------------------------------ JSON

    fun messageMap(m: MessageRow, nowMs: Long = now()): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>("id" to m.id, "threadId" to m.threadId, "from" to m.from)
        if (m.unsentAt != null) {
            out["at"] = m.at
            if (m.readAt != null) out["readAt"] = m.readAt
            out["unsent"] = true
            return out
        }
        if (m.text != null) out["text"] = m.text
        if (m.hasPhoto) out["photoUrl"] = photos.url(m.id, nowMs)
        if (m.thumb != null) out["photoThumb"] = m.thumb
        if (m.lat != null && m.lng != null) out["location"] = linkedMapOf("lat" to m.lat, "lng" to m.lng)
        out["at"] = m.at
        if (m.readAt != null) out["readAt"] = m.readAt
        return out
    }

    private fun encodeCursor(at: Long, id: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString("$at:$id".toByteArray())

    private fun decodeCursor(lang: String?, c: String): Pair<Long, String> {
        val s = try {
            String(Base64.getUrlDecoder().decode(c))
        } catch (e: IllegalArgumentException) {
            throw bad(lang)
        }
        val at = s.substringBefore(':').toLongOrNull()
        val id = s.substringAfter(':', "")
        if (at == null || at < 0 || at > Sql.MAX_MS || !ID_RE.matches(id)) throw bad(lang)
        return at to id
    }

    /** a page of 50, oldest → newest, and the cursor for the older page (null when there is none) */
    private fun page(lang: String?, t: ThreadRow, before: String?): Pair<List<Map<String, Any?>>, String?> {
        val cur = before?.let { decodeCursor(lang, it) }
        val rows = if (cur == null) {
            jdbc.query(
                "SELECT * FROM chat_messages WHERE thread_id = ? ORDER BY at DESC, id DESC LIMIT ${PAGE + 1}", { rs, _ -> MessageRow.of(rs) }, t.id,
            )
        } else {
            jdbc.query(
                "SELECT * FROM chat_messages WHERE thread_id = ? AND (at, id) < (?, ?) ORDER BY at DESC, id DESC LIMIT ${PAGE + 1}",
                { rs, _ -> MessageRow.of(rs) },
                t.id, Sql.ts(cur.first), cur.second,
            )
        }
        val pageRows = rows.take(PAGE)
        val next = if (rows.size > PAGE) pageRows.last().let { encodeCursor(it.at, it.id) } else null
        val nowMs = now()
        return pageRows.reversed().map { messageMap(it, nowMs) } to next
    }

    private fun threadHead(uid: String, t: ThreadRow, a: Access): LinkedHashMap<String, Any?> {
        val peer = calls.people(listOf(t.other(uid)))[t.other(uid)]!!
        return linkedMapOf<String, Any?>(
            "ok" to true, "threadId" to t.id, "ctx" to t.ctx, "refId" to t.refId, "peer" to calls.peerMap(peer), "canSend" to a.canSend,
        ).apply {
            if (a.blocked) put("blocked", true)
            if (a.closed) put("closed", true)
        }
    }

    // ------------------------------------------------------------------ chat_open

    fun open(lang: String?, uid: String, email: String, ctx: String, refId: String, peerUid: String?): Map<String, Any?> {
        val nowMs = now()
        val d = policy.decide(uid, email, ctx, refId, peerUid, nowMs)
        val t: ThreadRow = if (d.rel != null) {
            val peer = d.rel.peer
            if (d.allowed) {
                val (a, b) = pair(uid, peer)
                jdbc.update(
                    """
                    INSERT INTO chat_threads (id, ctx, ref_id, uid_a, uid_b, created_at) VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (uid_a, uid_b, ctx, ref_id) DO NOTHING
                    """.trimIndent(),
                    Ids.newId(), ctx, refId, a, b, Sql.ts(nowMs),
                )
                threadOf(uid, peer, ctx, refId)!!
            } else {
                threadOf(uid, peer, ctx, refId) ?: throw refusal(lang, d.refusal)
            }
        } else if (peerUid != null) {
            threadOf(uid, peerUid, ctx, refId) ?: throw notAllowed(lang)
        } else {
            jdbc.query(
                "SELECT * FROM chat_threads WHERE (uid_a = ? OR uid_b = ?) AND ctx = ? AND ref_id = ? ORDER BY last_at DESC NULLS LAST, id LIMIT 1",
                { rs, _ -> ThreadRow.of(rs) },
                uid, uid, ctx, refId,
            ).firstOrNull() ?: throw notAllowed(lang)
        }
        return openThread(lang, uid, email, t, nowMs)
    }

    /** chat_open {threadId}: members only (anyone else → 403 chat_not_allowed) */
    fun openById(lang: String?, uid: String, email: String, threadId: String): Map<String, Any?> {
        val t = thread(threadId)?.takeIf { it.has(uid) } ?: throw notAllowed(lang)
        return openThread(lang, uid, email, t, now())
    }

    private fun openThread(lang: String?, uid: String, email: String, t: ThreadRow, nowMs: Long): Map<String, Any?> {
        val a = access(uid, email, t, nowMs)
        val (msgs, before) = page(lang, t, null)
        return threadHead(uid, t, a).apply {
            put("messages", msgs)
            put("before", before)
        }
    }

    // ------------------------------------------------------------------ chat_messages

    fun messages(lang: String?, uid: String, email: String, threadId: String?, before: String?): Map<String, Any?> {
        val t = mine(lang, uid, threadId)
        val (msgs, next) = page(lang, t, before)
        return threadHead(uid, t, access(uid, email, t)).apply {
            put("messages", msgs)
            put("before", next)
        }
    }

    // ------------------------------------------------------------------ chat_send

    class Content(val text: String?, val photo: String?, val lat: Double?, val lng: Double?)

    fun send(lang: String?, uid: String, email: String, threadId: String?, clientId: String, c: Content): Map<String, Any?> {
        val t0 = mine(lang, uid, threadId)
        existing(t0.id, uid, clientId)?.let { return linkedMapOf("ok" to true, "message" to messageMap(it)) }
        val nowMs = now()
        val a = access(uid, email, t0, nowMs)
        if (a.closed) throw closed(lang)
        if (!a.canSend) throw refusal(lang, a.refusal)
        if (!rate.tryAcquire("chat_send:$uid", SEND_PER_MINUTE, Duration.ofMinutes(1))) throw tooFast(lang)
        val processed: ChatPhotos.Processed? = if (c.photo == null) null else {
            val dayStart = DhakaTime.day(nowMs).atStartOfDay(DHAKA).toInstant().toEpochMilli()
            val today = jdbc.queryForObject(
                "SELECT count(*) FROM chat_messages WHERE from_uid = ? AND kind = 'photo' AND at >= ?", Int::class.java, uid, Sql.ts(dayStart),
            ) ?: 0
            if (today >= PHOTOS_PER_DAY) throw tooManyPhotos(lang)
            try {
                photos.process(c.photo)
            } catch (e: ChatPhotos.BadPhoto) {
                throw badPhoto(lang)
            }
        }
        var created = false
        val m = tx.execute {
            val t = thread(t0.id, forUpdate = true) ?: throw notFound(lang)
            existing(t.id, uid, clientId)?.let { return@execute it }
            if (t.runUid == uid && t.runCount >= RUN_LIMIT) throw waitReply(lang)
            val id = Ids.newId()
            val kind = when {
                processed != null -> "photo"
                c.lat != null -> "location"
                else -> "text"
            }
            var key: String? = null
            var data: String? = null
            if (processed != null) {
                val b64 = Base64.getEncoder().encodeToString(processed.jpeg)
                if (files.enabled) key = files.put("chat", b64) else data = b64
            }
            jdbc.update(
                """
                INSERT INTO chat_messages (id, thread_id, from_uid, client_id, kind, text, photo_key, photo_data, thumb, lat, lng, at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                id, t.id, uid, clientId, kind, c.text, key, data, processed?.thumbB64, c.lat, c.lng, Sql.ts(nowMs),
            )
            jdbc.update(
                "UPDATE chat_threads SET last_at = ?, closed_at = NULL, run_uid = ?, run_count = ? WHERE id = ?",
                Sql.ts(nowMs), uid, if (t.runUid == uid) t.runCount + 1 else 1, t.id,
            )
            created = true
            message(id)!!
        }!!
        if (created) {
            try {
                notifyMessage(t0, m)
            } catch (ex: Exception) {
                log.error("chat_message notice failed", ex)
            }
        }
        return linkedMapOf("ok" to true, "message" to messageMap(m))
    }

    private fun existing(threadId: String, uid: String, clientId: String): MessageRow? = jdbc.query(
        "SELECT * FROM chat_messages WHERE thread_id = ? AND from_uid = ? AND client_id = ?", { rs, _ -> MessageRow.of(rs) }, threadId, uid, clientId,
    ).firstOrNull()

    // ------------------------------------------------------------------ notices

    private fun publish(uid: String, event: String, data: Map<String, Any?>) {
        try {
            sse.publish("user:$uid", event, data)
        } catch (ex: Exception) {
            log.debug("sse {} failed: {}", event, ex.message)
        }
    }

    /** FCM to the peer (generic lock-screen text, collapsed per thread) + SSE to both */
    private fun notifyMessage(t: ThreadRow, m: MessageRow) {
        val peer = t.other(m.from)
        val ps = calls.people(listOf(m.from, peer))
        val first = CallService.firstName(ps[m.from]!!.name)
        val lang = ps[peer]!!.lang
        val data = linkedMapOf<String, Any?>(
            "type" to "chat_message", "cat" to NotifyCats.MESSAGES, "threadId" to t.id, "messageId" to m.id, "from" to first,
        )
        push.send(
            listOf(
                PushItem(
                    peer, Lang.pick(lang, "Lifolo message — $first", "লাইফলো মেসেজ — $first"), "", data, // bn-ok
                    inbox = false, ttlSeconds = 24 * 3600, collapseKey = "chat_${t.id}",
                ),
            ),
        )
        sseMessage(t, m)
    }

    private fun sseMessage(t: ThreadRow, m: MessageRow) {
        val first = CallService.firstName(calls.people(listOf(m.from))[m.from]!!.name)
        val nowMs = now()
        for (u in listOf(t.a, t.b)) {
            publish(u, "chat_message", linkedMapOf("threadId" to t.id, "messageId" to m.id, "from" to first, "message" to messageMap(m, nowMs)))
        }
    }

    // ------------------------------------------------------------------ chat_threads / chat_unread

    fun threads(lang: String?, uid: String, email: String, cursor: String?): Map<String, Any?> {
        val cur = cursor?.let { decodeCursor(lang, it) }
        val args = ArrayList<Any?>(listOf(uid, uid, uid))
        val where = if (cur != null) {
            args += Sql.ts(cur.first)
            args += cur.second
            " AND (t.last_at, t.id) < (?, ?)"
        } else {
            ""
        }
        data class Item(val t: ThreadRow, val from: String?, val kind: String?, val text: String?, val at: Long?, val unsent: Boolean, val unread: Int)
        val rows = jdbc.query(
            """
            SELECT t.*, m.from_uid AS m_from, m.kind AS m_kind, m.text AS m_text, m.at AS m_at, m.unsent_at AS m_unsent,
              (SELECT count(*) FROM chat_messages u WHERE u.thread_id = t.id AND u.from_uid <> ? AND u.read_at IS NULL AND u.unsent_at IS NULL) AS unread
            FROM chat_threads t
            LEFT JOIN LATERAL (SELECT * FROM chat_messages x WHERE x.thread_id = t.id ORDER BY x.at DESC, x.id DESC LIMIT 1) m ON TRUE
            WHERE (t.uid_a = ? OR t.uid_b = ?) AND t.last_at IS NOT NULL$where
            ORDER BY t.last_at DESC, t.id DESC LIMIT ${THREAD_PAGE + 1}
            """.trimIndent(),
            { rs, _ ->
                Item(
                    ThreadRow.of(rs), rs.getString("m_from"), rs.getString("m_kind"), rs.getString("m_text"), rs.getTimestamp("m_at")?.time,
                    rs.getTimestamp("m_unsent") != null, rs.getInt("unread"),
                )
            },
            *args.toTypedArray(),
        )
        val list = rows.take(THREAD_PAGE)
        val ps = calls.people(list.map { it.t.other(uid) })
        val nowMs = now()
        val items = list.map { row ->
            val a = access(uid, email, row.t, nowMs)
            val preview = when {
                row.unsent -> Lang.tr(lang, "Message deleted", "মেসেজ মুছে ফেলা হয়েছে") // bn-ok
                row.kind == "photo" -> "📷"
                row.kind == "location" -> "📍"
                else -> (row.text ?: "").take(PREVIEW)
            }
            linkedMapOf<String, Any?>(
                "threadId" to row.t.id, "ctx" to row.t.ctx, "refId" to row.t.refId, "peer" to calls.peerMap(ps[row.t.other(uid)]!!),
                "last" to linkedMapOf("text" to preview, "at" to (row.at ?: row.t.lastAt), "mine" to (row.from == uid)),
                "unread" to row.unread, "canSend" to a.canSend,
            )
        }
        val next = if (rows.size > THREAD_PAGE) list.last().t.let { encodeCursor(it.lastAt!!, it.id) } else null
        return linkedMapOf("ok" to true, "items" to items, "cursor" to next)
    }

    fun unread(uid: String): Map<String, Any?> {
        val n = jdbc.queryForObject(
            """
            SELECT count(*) FROM chat_messages m JOIN chat_threads t ON t.id = m.thread_id
            WHERE (t.uid_a = ? OR t.uid_b = ?) AND m.from_uid <> ? AND m.read_at IS NULL AND m.unsent_at IS NULL
            """.trimIndent(),
            Long::class.java, uid, uid, uid,
        ) ?: 0L
        return linkedMapOf("ok" to true, "total" to n)
    }

    // ------------------------------------------------------------------ chat_read

    fun read(lang: String?, uid: String, threadId: String?, upTo: Long): Map<String, Any?> {
        val t = mine(lang, uid, threadId)
        val nowMs = now()
        val n = jdbc.update(
            "UPDATE chat_messages SET read_at = ? WHERE thread_id = ? AND from_uid <> ? AND read_at IS NULL AND at <= ?",
            Sql.ts(nowMs), t.id, uid, Sql.ts(upTo),
        )
        if (n > 0) {
            val peer = t.other(uid)
            val ev = linkedMapOf<String, Any?>("threadId" to t.id, "upTo" to upTo, "by" to uid)
            publish(uid, "chat_read", ev)
            // a block: no read receipts to the other side
            if (!blocks.eitherWay(uid, peer)) {
                publish(peer, "chat_read", ev)
                try {
                    push.send(
                        listOf(
                            PushItem(
                                peer, "", "",
                                linkedMapOf("type" to "chat_read", "cat" to NotifyCats.MESSAGES, "threadId" to t.id, "upTo" to upTo.toString(), "by" to uid),
                                inbox = false, ttlSeconds = 3600, collapseKey = "chat_read_${t.id}",
                            ),
                        ),
                    )
                } catch (ex: Exception) {
                    log.error("chat_read push failed", ex)
                }
            }
        }
        return linkedMapOf("ok" to true, "read" to n)
    }

    // ------------------------------------------------------------------ chat_unsend

    fun unsend(lang: String?, uid: String, messageId: String?): Map<String, Any?> {
        if (messageId == null || !ID_RE.matches(messageId)) throw notFound(lang)
        val m0 = message(messageId) ?: throw notFound(lang)
        val t = thread(m0.threadId) ?: throw notFound(lang)
        if (m0.from != uid || !t.has(uid)) throw notFound(lang)
        if (m0.unsentAt != null) return linkedMapOf("ok" to true, "message" to messageMap(m0))
        val nowMs = now()
        if (nowMs - m0.at > UNSEND_MS) throw unsendLate(lang)
        jdbc.update(
            """
            UPDATE chat_messages SET text = NULL, photo_key = NULL, photo_data = NULL, thumb = NULL, lat = NULL, lng = NULL, unsent_at = ?
            WHERE id = ? AND unsent_at IS NULL
            """.trimIndent(),
            Sql.ts(nowMs), m0.id,
        )
        m0.photoKey?.let { files.forget(it) }
        val m = message(m0.id)!!
        try {
            sseMessage(t, m)
        } catch (ex: Exception) {
            log.debug("unsend notice failed: {}", ex.message)
        }
        return linkedMapOf("ok" to true, "message" to messageMap(m))
    }

    // ------------------------------------------------------------------ chat_report

    fun report(lang: String?, uid: String, messageId: String?, reason: String?, note: String?): Map<String, Any?> {
        if (reason == null || reason !in CallService.REPORT_REASONS) throw bad(lang)
        val text = (note ?: "").trim()
        if (text.length > 500) throw bad(lang)
        if (messageId == null || !ID_RE.matches(messageId)) throw notFound(lang)
        val m = message(messageId) ?: throw notFound(lang)
        val t = thread(m.threadId) ?: throw notFound(lang)
        if (!t.has(uid) || m.from == uid) throw notFound(lang)
        val meta = linkedMapOf<String, Any?>("ctx" to t.ctx, "refId" to t.refId, "kind" to m.kind, "at" to m.at)
        if (m.text != null) meta["text"] = m.text
        if (m.lat != null) meta["location"] = linkedMapOf("lat" to m.lat, "lng" to m.lng)
        if (m.unsentAt != null) meta["unsent"] = true
        val already = jdbc.queryForList("SELECT photo_key, photo_data FROM chat_reports WHERE message_id = ? AND reporter_uid = ?", m.id, uid).firstOrNull()
        var key: String? = null
        var data: String? = null
        if (already == null && m.hasPhoto) {
            // a copy, so unsend / retention cannot take the evidence away
            val b64 = m.photoData ?: m.photoKey?.let { k -> runCatching { storage.get(k) }.getOrNull()?.let { Base64.getEncoder().encodeToString(it) } }
            if (b64 != null) {
                if (files.enabled) key = files.put("chat-report", b64) else data = b64
            }
        }
        tx.execute {
            jdbc.update(
                """
                INSERT INTO chat_reports (id, message_id, thread_id, reporter_uid, reported_uid, reason, note, message_meta, photo_key, photo_data, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                ON CONFLICT (message_id, reporter_uid) DO UPDATE SET reason = EXCLUDED.reason, note = EXCLUDED.note
                """.trimIndent(),
                Ids.newId(), m.id, t.id, uid, m.from, reason, text, mapper.writeValueAsString(meta), key, data, Sql.ts(now()),
            )
        }
        return linkedMapOf("ok" to true)
    }

    // ------------------------------------------------------------------ admin: reports

    fun adminReports(status: String, before: Long?): Map<String, Any?> {
        val b = before?.takeIf { it in 1 until Sql.MAX_MS } ?: Sql.MAX_MS
        val rows = jdbc.queryForList(
            "SELECT * FROM chat_reports WHERE status = ? AND created_at < ? ORDER BY created_at DESC LIMIT ${ADMIN_PAGE + 1}", status, Sql.ts(b),
        )
        val page = rows.take(ADMIN_PAGE)
        val ps = calls.people(page.flatMap { listOf(it["reporter_uid"] as String, it["reported_uid"] as String) })
        val keys = page.mapNotNull { it["photo_key"] as String? }
        val texts = if (keys.isEmpty()) emptyMap() else runCatching { files.texts(keys) }.getOrDefault(emptyMap())
        val items = page.map { r ->
            val created = (r["created_at"] as java.sql.Timestamp).time
            linkedMapOf<String, Any?>(
                "id" to r["id"], "messageId" to r["message_id"], "threadId" to r["thread_id"],
                "reporter" to calls.peerMap(ps[r["reporter_uid"] as String]!!), "reported" to calls.peerMap(ps[r["reported_uid"] as String]!!),
                "reason" to r["reason"], "note" to r["note"], "message" to mapper.readTree(r["message_meta"].toString()),
                "photo" to ((r["photo_data"] as String?) ?: (r["photo_key"] as String?)?.let { texts[it] }),
                "status" to r["status"], "createdAt" to created,
            )
        }
        val out = linkedMapOf<String, Any?>("ok" to true, "items" to items)
        if (rows.size > ADMIN_PAGE) out["next"] = items.last()["createdAt"]
        return out
    }

    fun adminResolve(lang: String?, id: String?, status: String?): Map<String, Any?> {
        if (id == null || !ID_RE.matches(id) || status !in setOf("resolved", "dismissed")) throw bad(lang)
        val n = jdbc.update("UPDATE chat_reports SET status = ?, resolved_at = ? WHERE id = ?", status, Sql.ts(now()), id)
        if (n == 0) throw notFound(lang)
        return linkedMapOf("ok" to true)
    }

    // ------------------------------------------------------------------ retention / account deletion

    /**
     * Daily: open threads whose relation ended (from both sides) are closed; threads closed more than 90 days ago are
     * deleted with every message and photo; handled reports older than 90 days go too.
     */
    fun daily(nowMs: Long = now()): Map<String, Any?> {
        var closedN = 0
        var after = ""
        while (true) {
            val open = jdbc.query(
                "SELECT * FROM chat_threads WHERE closed_at IS NULL AND id > ? ORDER BY id LIMIT 500", { rs, _ -> ThreadRow.of(rs) }, after,
            )
            for (t in open) {
                try {
                    val aSide = policy.check(t.a, policy.accountEmail(t.a), t.ctx, t.refId, t.b, nowMs)?.takeIf { it.peer == t.b }
                    val bSide = aSide ?: policy.check(t.b, policy.accountEmail(t.b), t.ctx, t.refId, t.a, nowMs)?.takeIf { it.peer == t.a }
                    if (bSide == null) closedN += jdbc.update("UPDATE chat_threads SET closed_at = ? WHERE id = ? AND closed_at IS NULL", Sql.ts(nowMs), t.id)
                } catch (ex: Exception) {
                    log.error("chat close check failed for {}", t.id, ex)
                }
            }
            if (open.size < 500) break
            after = open.last().id
        }
        var deleted = 0
        while (true) {
            val n = jdbc.update(
                "DELETE FROM chat_threads WHERE id IN (SELECT id FROM chat_threads WHERE closed_at < ? LIMIT 500)",
                Sql.ts(nowMs - RETENTION_DAYS * DAY_MS),
            )
            deleted += n
            if (n < 500) break
        }
        val reports = jdbc.update(
            "DELETE FROM chat_reports WHERE status <> 'open' AND resolved_at < ?", Sql.ts(nowMs - RETENTION_DAYS * DAY_MS),
        )
        return linkedMapOf("closed" to closedN, "deleted" to deleted, "reportsDeleted" to reports)
    }

    /** account deletion: every thread of the person, with both sides' messages and photos */
    fun deleteAllOf(uid: String): Int = jdbc.update("DELETE FROM chat_threads WHERE uid_a = ? OR uid_b = ?", uid, uid)

    companion object {
        val DHAKA: ZoneId = ZoneId.of("Asia/Dhaka")
        val ID_RE = Regex("^[A-Za-z0-9_-]{1,128}$")
        val CLIENT_ID_RE = Regex("^[A-Za-z0-9_-]{1,64}$")
        val CTXS = setOf("blood", "appointment", "ambulance", "rx_order", "lab_order")
        const val PAGE = 50
        const val THREAD_PAGE = 30
        const val ADMIN_PAGE = 50
        const val PREVIEW = 100
        const val TEXT_MAX = 1000
        const val SEND_PER_MINUTE = 30L
        const val PHOTOS_PER_DAY = 20
        const val RUN_LIMIT = 20
        const val UNSEND_MS = 10 * 60 * 1000L
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val RETENTION_DAYS = 90L

        fun pair(x: String, y: String): Pair<String, String> = if (x < y) x to y else y to x
    }
}

/** the donor rule: a requester who MESSAGED a donor about the request counts as "contacted first" (like a call) */
@Component
class ChatContactHistory(private val jdbc: JdbcTemplate) : ContactHistory {
    override fun contacted(from: String, to: String, ctx: String, refId: String): Boolean {
        val (a, b) = ChatService.pair(from, to)
        return jdbc.queryForList(
            """
            SELECT 1 FROM chat_messages m JOIN chat_threads t ON t.id = m.thread_id
            WHERE t.uid_a = ? AND t.uid_b = ? AND t.ctx = ? AND t.ref_id = ? AND m.from_uid = ? LIMIT 1
            """.trimIndent(),
            a, b, ctx, refId, from,
        ).isNotEmpty()
    }
}
