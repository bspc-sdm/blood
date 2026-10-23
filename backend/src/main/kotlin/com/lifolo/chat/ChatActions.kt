package com.lifolo.chat

import com.lifolo.admin.kernel.AccountDeletionStep
import com.lifolo.admin.kernel.DemoSeedContext
import com.lifolo.admin.kernel.DemoSeedPart
import com.lifolo.auth.AdminService
import com.lifolo.common.Ids
import com.lifolo.common.Sql
import com.lifolo.common.action.ActionContext
import com.lifolo.common.action.ActionHandler
import com.lifolo.common.action.ActionModule
import com.lifolo.jobs.DailyTask
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * In-app messages (backend/docs/CHAT.md): chat_open, chat_send, chat_messages, chat_threads, chat_read, chat_unsend,
 * chat_report, chat_unread (+ admin_chat_reports, admin_chat_report_resolve). Own account only — not for a caregiver
 * acting for a patient (not in CARE_ACTIONS, checked again here).
 */
@Component
class ChatActions(private val chat: ChatService, private val admins: AdminService) : ActionModule {
    override fun actions(): Map<String, ActionHandler> = mapOf(
        "chat_open" to ActionHandler(::open),
        "chat_send" to ActionHandler(::send),
        "chat_messages" to ActionHandler { ctx ->
            val before = if (ctx.body.isNullish("before")) null else ctx.body.string("before")?.takeIf { it.length <= 300 } ?: throw chat.bad(ctx.lang)
            chat.messages(ctx.lang, own(ctx), email(ctx), ctx.body.string("threadId"), before)
        },
        "chat_threads" to ActionHandler { ctx ->
            val cursor = if (ctx.body.isNullish("cursor")) null else ctx.body.string("cursor")?.takeIf { it.length <= 300 } ?: throw chat.bad(ctx.lang)
            chat.threads(ctx.lang, own(ctx), email(ctx), cursor)
        },
        "chat_read" to ActionHandler { ctx ->
            val uid = own(ctx)
            val upTo = ctx.body.int("upTo")?.takeIf { it in 0..Sql.MAX_MS } ?: throw chat.bad(ctx.lang)
            chat.read(ctx.lang, uid, ctx.body.string("threadId"), upTo)
        },
        "chat_unsend" to ActionHandler { ctx -> chat.unsend(ctx.lang, own(ctx), ctx.body.string("messageId")) },
        "chat_report" to ActionHandler { ctx ->
            val uid = own(ctx)
            if (!ctx.body.isNullish("note") && ctx.body.string("note") == null) throw chat.bad(ctx.lang)
            chat.report(ctx.lang, uid, ctx.body.string("messageId"), ctx.body.string("reason"), ctx.body.string("note"))
        },
        "chat_unread" to ActionHandler { ctx -> chat.unread(own(ctx)) },
        "admin_chat_reports" to ActionHandler { ctx ->
            requireAdmin(ctx)
            val status = ctx.body.string("status") ?: "open"
            if (status !in setOf("open", "resolved", "dismissed")) throw chat.bad(ctx.lang)
            chat.adminReports(status, ctx.body.int("before"))
        },
        "admin_chat_report_resolve" to ActionHandler { ctx ->
            requireAdmin(ctx)
            chat.adminResolve(ctx.lang, ctx.body.string("id"), ctx.body.string("status"))
        },
    )

    private fun requireAdmin(ctx: ActionContext) {
        if (!admins.isAdmin(ctx.user)) throw ctx.err(403, "Admins only", "শুধু অ্যাডমিন")
    }

    private fun own(ctx: ActionContext): String {
        if (ctx.user.actingBy != null || ctx.user.id != ctx.caller.id) {
            throw ctx.err(403, "A caregiver can't do this for the patient", "কেয়ারগিভার রোগীর হয়ে এটা করতে পারেন না")
        }
        return ctx.user.id
    }

    /** the verified email (the doctor's assistants are matched by it), else "" */
    private fun email(ctx: ActionContext): String = if (ctx.user.emailVerified) ctx.user.email.lowercase() else ""

    private fun open(ctx: ActionContext): Any {
        val uid = own(ctx)
        val b = ctx.body
        val bad = { chat.bad(ctx.lang) }
        if (!b.isNullish("threadId")) {
            val id = b.string("threadId")?.takeIf { ChatService.ID_RE.matches(it) } ?: throw bad()
            return chat.openById(ctx.lang, uid, email(ctx), id)
        }
        val c = b.string("ctx")
        if (c == null || c !in ChatService.CTXS) throw bad()
        val refId = b.string("refId")?.takeIf { ChatService.ID_RE.matches(it) } ?: throw bad()
        val peer = if (b.isNullish("peerUid")) null else b.string("peerUid")?.takeIf { ChatService.ID_RE.matches(it) } ?: throw bad()
        return chat.open(ctx.lang, uid, email(ctx), c, refId, peer)
    }

    private fun send(ctx: ActionContext): Any {
        val uid = own(ctx)
        val b = ctx.body
        val bad = { chat.bad(ctx.lang) }
        val clientId = b.string("clientId")?.takeIf { ChatService.CLIENT_ID_RE.matches(it) } ?: throw bad()
        val text = if (b.isNullish("text")) {
            null
        } else {
            val t = (b.string("text") ?: throw bad()).trim()
            if (t.length > ChatService.TEXT_MAX) throw bad()
            t.ifEmpty { null }
        }
        val photo = if (b.isNullish("photo")) null else b.string("photo")?.takeIf { it.isNotEmpty() && it.length <= 2_000_000 } ?: throw bad()
        var lat: Double? = null
        var lng: Double? = null
        if (!b.isNullish("location")) {
            val o = b.obj("location") ?: throw bad()
            val la = o.get("lat")?.takeIf { it.isNumber }?.doubleValue()
            val ln = o.get("lng")?.takeIf { it.isNumber }?.doubleValue()
            if (la == null || ln == null || !la.isFinite() || !ln.isFinite() || la !in -90.0..90.0 || ln !in -180.0..180.0) throw bad()
            lat = Math.round(la * 1e6) / 1e6
            lng = Math.round(ln * 1e6) / 1e6
        }
        // exactly one kind: text, photo (optionally captioned) or location
        if (lat != null && (photo != null || text != null)) throw bad()
        if (lat == null && photo == null && text == null) throw bad()
        return chat.send(ctx.lang, uid, email(ctx), b.string("threadId"), clientId, ChatService.Content(text, photo, lat, lng))
    }
}

/** daily step 16 (`chatDeleted`): close threads whose relation ended, delete threads closed 90+ days ago */
@Component
class ChatCleanupTask(private val chat: ChatService) : DailyTask {
    override val name = "chatDeleted"
    override val order = 16

    override fun run(nowMs: Long): Any = chat.daily(nowMs)
}

/**
 * account deletion: while the deletion waits (30 days) the peers see the threads read-only (ContactPolicy: a deleting
 * account is unreachable); when it runs, every thread of the person goes with all messages and photos
 */
@Component
class ChatDeletionStep(private val chat: ChatService) : AccountDeletionStep {
    override val order = 660

    override fun deleteNow(uid: String, email: String?) {
        chat.deleteAllOf(uid)
    }
}

/**
 * The demo world's conversations (after the blood and prescription parts): the demo donor with requester রেহানা
 * (blood), the demo patient with the demo lab (lab order), a patient with the demo pharmacy (prescription order).
 * Owned by demo accounts, so the demo wipe (account deletion) removes them.
 */
@Component
class ChatDemoSeed(private val jdbc: JdbcTemplate) : DemoSeedPart {
    override val step = 5
    override val label = "মেসেজ" // bn-ok
    override val order = 50

    private class Msg(val fromFirst: Boolean, val agoMin: Long, val text: String? = null, val lat: Double? = null, val lng: Double? = null, val read: Boolean = true)

    override fun seed(ctx: DemoSeedContext) {
        val now = ctx.nowMs
        val rq = jdbc.queryForList("SELECT requester_uid, lat, lng FROM blood_requests WHERE id = 'demo-rq-donor-confirmed'").firstOrNull()
        if (rq != null) {
            thread(
                "blood", "demo-rq-donor-confirmed", rq["requester_uid"] as String, ctx.user("donor").uid, now,
                listOf(
                    Msg(true, 7 * 60, "আসসালামু আলাইকুম, আপনি কখন হাসপাতালে আসতে পারবেন?"), // bn-ok
                    Msg(false, 6 * 60 + 50, "বিকেল ৩টার মধ্যে চলে আসব ইনশাআল্লাহ।"), // bn-ok
                    Msg(true, 6 * 60 + 45, lat = (rq["lat"] as Number).toDouble(), lng = (rq["lng"] as Number).toDouble()),
                    Msg(true, 6 * 60 + 44, "এটাই হাসপাতালের লোকেশন। গেটে এসে মেসেজ দিবেন।", read = false), // bn-ok
                ),
            )
        }
        val lab = jdbc.queryForList("SELECT patient_uid, lab_uid FROM lab_orders WHERE id = 'demo-rx-lab-me-cbc'").firstOrNull()
        if (lab?.get("patient_uid") != null) {
            thread(
                "lab_order", "demo-rx-lab-me-cbc", lab["patient_uid"] as String, lab["lab_uid"] as String, now,
                listOf(
                    Msg(true, 20 * 60, "রিপোর্ট কখন পাওয়া যাবে?"), // bn-ok
                    Msg(false, 19 * 60, "আজ সন্ধ্যা ৬টার পর রিপোর্ট দেওয়া হবে।"), // bn-ok
                    Msg(false, 60, "রিপোর্ট প্রায় তৈরি, একটু পরে অ্যাপে দেখতে পাবেন।", read = false), // bn-ok
                ),
            )
        }
        val rx = jdbc.queryForList("SELECT patient_uid, pharmacy_uid FROM rx_orders WHERE id = 'demo-rx-order-nasima'").firstOrNull()
        if (rx?.get("patient_uid") != null) {
            thread(
                "rx_order", "demo-rx-order-nasima", rx["patient_uid"] as String, rx["pharmacy_uid"] as String, now,
                listOf(Msg(true, 35, "বাকি ওষুধগুলো কাল নিলে হবে?", read = false)), // bn-ok
            )
        }
    }

    /** [first] wrote the messages marked fromFirst; times are minutes before [now] */
    private fun thread(ctx: String, refId: String, first: String, second: String, now: Long, msgs: List<Msg>) {
        val (a, b) = ChatService.pair(first, second)
        val id = Ids.newId()
        val lastAt = now - msgs.minOf { it.agoMin } * 60_000
        val last = msgs.minByOrNull { it.agoMin }!!
        val runUid = if (last.fromFirst) first else second
        val run = msgs.sortedBy { -it.agoMin }.takeLastWhile { it.fromFirst == last.fromFirst }.size
        val n = jdbc.update(
            """
            INSERT INTO chat_threads (id, ctx, ref_id, uid_a, uid_b, created_at, last_at, run_uid, run_count) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (uid_a, uid_b, ctx, ref_id) DO NOTHING
            """.trimIndent(),
            id, ctx, refId, a, b, Sql.ts(now - msgs.maxOf { it.agoMin } * 60_000), Sql.ts(lastAt), runUid, run,
        )
        if (n == 0) return
        msgs.forEachIndexed { i, m ->
            val at = now - m.agoMin * 60_000
            jdbc.update(
                """
                INSERT INTO chat_messages (id, thread_id, from_uid, client_id, kind, text, lat, lng, at, read_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                Ids.newId(), id, if (m.fromFirst) first else second, "demo-$i", if (m.lat != null) "location" else "text", m.text, m.lat, m.lng,
                Sql.ts(at), if (m.read) Sql.ts(at + 5 * 60_000) else null,
            )
        }
    }
}
