package com.lifolo.admin

import com.lifolo.auth.AdminService
import com.lifolo.common.Ids
import com.lifolo.common.Js
import com.lifolo.common.Lang
import com.lifolo.common.QuotaService
import com.lifolo.common.Rules
import com.lifolo.common.Sql
import com.lifolo.common.action.ActionContext
import com.lifolo.common.action.ActionHandler
import com.lifolo.common.action.ActionModule
import com.lifolo.notify.PushItem
import com.lifolo.notify.PushService
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

/**
 * support.js — help & appeals: the user writes, the admin answers from the web panel. `tickets` + `ticket_messages`
 * (the app never reads them directly). At most 3 open tickets per user, 40 messages per conversation.
 */
@Component
class SupportActions(
    private val jdbc: JdbcTemplate,
    private val admins: AdminService,
    private val quotas: QuotaService,
    private val push: PushService,
    private val tx: TransactionTemplate,
) : ActionModule {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun actions(): Map<String, ActionHandler> = mapOf(
        "ticket_create" to ActionHandler { create(it) },
        "tickets_mine" to ActionHandler { mine(it) },
        "ticket_reply" to ActionHandler { reply(it) },
        "admin_tickets" to ActionHandler { adminList(it) },
        "admin_ticket" to ActionHandler { adminOne(it) },
        "admin_ticket_reply" to ActionHandler { adminReply(it) },
    )

    companion object {
        val CATEGORIES = listOf("account", "badge", "pharmacy", "ambulance", "bloodbank", "homevisit", "homecare", "medorder", "appointment", "blood", "other")
        val STATUSES = listOf("open", "answered", "closed")
        const val MAX_OPEN = 3
        const val MAX_MESSAGES = 40
        const val TEXT_MAX = 1500
    }

    private fun requireAdmin(ctx: ActionContext) {
        if (!admins.isAdmin(ctx.user)) throw ctx.err(403, "Admins only", "শুধু অ্যাডমিন")
    }

    private fun messages(ids: Collection<String>): Map<String, List<Map<String, Any?>>> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<String, MutableList<Map<String, Any?>>>()
        jdbc.query(
            "SELECT ticket_id, sender, text, at FROM ticket_messages WHERE ticket_id = ANY(?) ORDER BY ticket_id, idx",
            RowCallbackHandler { rs ->
                out.getOrPut(rs.getString(1)) { ArrayList() }
                    .add(linkedMapOf("from" to rs.getString(2), "text" to rs.getString(3), "at" to rs.getTimestamp(4).time))
            },
            ids.toTypedArray(),
        )
        return out
    }

    /** support.js view (+ the admin fields) */
    private fun views(rows: List<Map<String, Any?>>, admin: Boolean): List<LinkedHashMap<String, Any?>> {
        val msgs = messages(rows.map { it["id"] as String })
        return rows.map { r ->
            val id = r["id"] as String
            linkedMapOf<String, Any?>(
                "id" to id, "category" to r["category"], "status" to r["status"], "messages" to (msgs[id] ?: emptyList<Any>()),
                "createdAt" to (r["created_at"] as java.sql.Timestamp).time, "updatedAt" to (r["updated_at"] as java.sql.Timestamp).time,
            ).apply {
                if (admin) {
                    put("uid", r["uid"])
                    put("email", r["email"] ?: "")
                    put("name", r["name"] ?: "")
                    put("lastFrom", r["last_from"])
                }
            }
        }
    }

    fun create(ctx: ActionContext): Any {
        val uid = ctx.user.id
        val category = ctx.body.string("category")?.takeIf { it in CATEGORIES }
            ?: throw ctx.err(400, "Choose what it is about", "কী নিয়ে, বেছে নিন")
        val text = Rules.cleanText(ctx.body.string("text"), TEXT_MAX)
        if (text == null || text.length < 10) {
            throw ctx.err(400, "Please describe the problem (at least 10 letters)", "সমস্যাটা একটু খুলে লিখুন (অন্তত ১০ অক্ষর)")
        }
        val open = jdbc.queryForObject("SELECT count(*) FROM tickets WHERE uid = ? AND status <> 'closed'", Int::class.java, uid) ?: 0
        if (open >= MAX_OPEN) {
            throw ctx.err(
                429,
                "You already have 3 open requests. Please wait for a reply or add to an existing one.",
                "আপনার ৩টা অনুরোধ খোলা আছে। উত্তরের অপেক্ষা করুন বা আগেরটাতেই লিখুন।",
            )
        }
        quotas.use(ctx.lang, uid, "ticket")
        val now = Sql.ts(System.currentTimeMillis())
        val id = Ids.newId()
        val name = jdbc.queryForList("SELECT name FROM user_profiles WHERE uid = ?", String::class.java, uid).firstOrNull() ?: ""
        tx.executeWithoutResult {
            jdbc.update(
                "INSERT INTO tickets (id, uid, email, name, category, status, last_from, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'open', 'user', ?, ?)",
                id, uid, ctx.user.email.lowercase(), name, category, now, now,
            )
            jdbc.update("INSERT INTO ticket_messages (ticket_id, idx, sender, text, at) VALUES (?, 0, 'user', ?, ?)", id, text, now)
        }
        return linkedMapOf("ok" to true, "id" to id)
    }

    fun mine(ctx: ActionContext): Any {
        val rows = jdbc.queryForList("SELECT * FROM tickets WHERE uid = ? ORDER BY updated_at DESC LIMIT 20", ctx.user.id)
        return linkedMapOf("items" to views(rows, false))
    }

    /** more text on an earlier ticket (a closed one opens again) */
    fun reply(ctx: ActionContext): Any {
        val id = ctx.body.string("id")
        if (!Rules.isValidId(id)) throw ctx.err(400, "Not found", "পাওয়া যায়নি")
        val text = Rules.cleanText(ctx.body.string("text"), TEXT_MAX) ?: throw ctx.err(400, "Write something", "কিছু লিখুন")
        tx.executeWithoutResult {
            val owner = jdbc.queryForList("SELECT uid FROM tickets WHERE id = ? FOR UPDATE", String::class.java, id).firstOrNull()
            if (owner == null || owner != ctx.user.id) throw ctx.err(404, "Not found", "পাওয়া যায়নি")
            val n = jdbc.queryForObject("SELECT count(*) FROM ticket_messages WHERE ticket_id = ?", Int::class.java, id) ?: 0
            if (n >= MAX_MESSAGES) throw ctx.err(400, "This conversation is full. Please start a new one.", "এই কথোপকথন ভরে গেছে। নতুন করে লিখুন।")
            val now = Sql.ts(System.currentTimeMillis())
            jdbc.update("INSERT INTO ticket_messages (ticket_id, idx, sender, text, at) VALUES (?, ?, 'user', ?, ?)", id, n, text, now)
            jdbc.update("UPDATE tickets SET status = 'open', last_from = 'user', updated_at = ? WHERE id = ?", now, id)
        }
        return linkedMapOf("ok" to true)
    }

    fun adminList(ctx: ActionContext): Any {
        requireAdmin(ctx)
        val status = ctx.body.string("status")?.takeIf { it in STATUSES } ?: "open"
        // open: the one waiting longest first; the rest: newest first
        val rows = jdbc.queryForList(
            "SELECT * FROM tickets WHERE status = ? ORDER BY updated_at ${if (status == "open") "ASC" else "DESC"} LIMIT 200", status,
        )
        return linkedMapOf("items" to views(rows, true))
    }

    fun adminOne(ctx: ActionContext): Any {
        requireAdmin(ctx)
        val id = ctx.body.string("id")
        if (!Rules.isValidId(id)) throw ctx.err(400, "Not found", "পাওয়া যায়নি")
        val row = jdbc.queryForList("SELECT * FROM tickets WHERE id = ?", id).firstOrNull() ?: throw ctx.err(404, "Not found", "পাওয়া যায়নি")
        return linkedMapOf("item" to views(listOf(row), true).first())
    }

    /** a reply, optionally closing it (closing alone needs no text) */
    fun adminReply(ctx: ActionContext): Any {
        requireAdmin(ctx)
        val id = ctx.body.string("id")
        val close = ctx.body.isTrue("close")
        if (!Rules.isValidId(id)) throw ctx.err(400, "Not found", "পাওয়া যায়নি")
        val text = if (Js.truthy(ctx.body["text"])) Rules.cleanText(ctx.body.string("text"), TEXT_MAX) else null
        if (text == null && !close) throw ctx.err(400, "Write a reply", "উত্তর লিখুন")
        val uid = tx.execute {
            val owner = jdbc.queryForList("SELECT uid FROM tickets WHERE id = ? FOR UPDATE", String::class.java, id).firstOrNull()
                ?: throw ctx.err(404, "Not found", "পাওয়া যায়নি")
            val n = jdbc.queryForObject("SELECT count(*) FROM ticket_messages WHERE ticket_id = ?", Int::class.java, id) ?: 0
            if (text != null && n >= MAX_MESSAGES) {
                throw ctx.err(
                    400,
                    "This conversation is full. Close it; the user can start a new one.",
                    "এই কথোপকথন ভরে গেছে। বন্ধ করুন, ব্যবহারকারী নতুন করে লিখতে পারবেন।",
                )
            }
            val now = Sql.ts(System.currentTimeMillis())
            if (text != null) {
                jdbc.update(
                    "INSERT INTO ticket_messages (ticket_id, idx, sender, text, at, by_email) VALUES (?, ?, 'admin', ?, ?, ?)",
                    id, n, text, now, ctx.user.email,
                )
                jdbc.update("UPDATE tickets SET last_from = 'admin' WHERE id = ?", id)
            }
            jdbc.update("UPDATE tickets SET status = ?, updated_at = ? WHERE id = ?", if (close) "closed" else "answered", now, id)
            owner
        }!!
        if (text != null) notifyUser(uid, id!!)
        return linkedMapOf("ok" to true)
    }

    /** support.js notifyUser: "Reply from the Lifolo team" (bell list too, even without a device) */
    private fun notifyUser(uid: String, ticketId: String) {
        try {
            val lang = jdbc.queryForList("SELECT lang FROM user_profiles WHERE uid = ?", String::class.java, uid).firstOrNull()
            push.send(
                listOf(
                    PushItem(
                        uid,
                        Lang.pick(lang, "Reply from the Lifolo team", "Lifolo টিমের উত্তর এসেছে"),
                        Lang.pick(lang, "Open Help & appeals to read it", "\"সাহায্য ও আপিল\" খুলে দেখুন"),
                        mapOf("type" to "support", "ticketId" to ticketId),
                    ),
                ),
            )
        } catch (e: Exception) {
            log.error("ticket push failed", e)
        }
    }
}
