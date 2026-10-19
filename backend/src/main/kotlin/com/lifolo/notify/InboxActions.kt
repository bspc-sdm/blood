package com.lifolo.notify

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.lifolo.common.Js
import com.lifolo.common.Sql
import com.lifolo.common.action.ActionContext
import com.lifolo.common.action.ActionHandler
import com.lifolo.common.action.ActionModule
import com.lifolo.jobs.DailyTask
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * inbox.js — the in-app bell list. "Read" is one timestamp per user (`user_state.inbox_seen_at`); everything newer
 * is unread. Ids are `{uid}_{reverse time}_{rand}`, so id order is newest first (like the Firestore document order).
 * devices.js — one push token per phone (≤ 5 per account); a phone moves to the account that registers it last.
 */
@Service
class InboxService(private val jdbc: JdbcTemplate, private val mapper: ObjectMapper) {
    private fun seenAt(uid: String): Long =
        jdbc.queryForList("SELECT inbox_seen_at FROM user_state WHERE uid = ?", java.sql.Timestamp::class.java, uid).firstOrNull()?.time ?: 0L

    /** the newest 50 */
    fun inbox(uid: String): Map<String, Any?> {
        val seen = seenAt(uid)
        val items = jdbc.query(
            "SELECT id, title, body, data, created_at FROM inbox_items WHERE uid = ? ORDER BY id LIMIT 50",
            { rs, _ ->
                val at = rs.getTimestamp("created_at").time
                linkedMapOf<String, Any?>(
                    "id" to rs.getString("id"),
                    "title" to rs.getString("title"),
                    "body" to (rs.getString("body") ?: ""),
                    "data" to (rs.getString("data")?.let { mapper.readValue(it, object : TypeReference<LinkedHashMap<String, Any?>>() {}) } ?: linkedMapOf<String, Any?>()),
                    "read" to (at <= seen),
                    "createdAt" to at,
                )
            },
            uid,
        )
        return linkedMapOf("items" to items)
    }

    /** everything read: one write */
    fun markRead(uid: String): Map<String, Any?> {
        jdbc.update(
            "INSERT INTO user_state (uid, inbox_seen_at) VALUES (?, ?) ON CONFLICT (uid) DO UPDATE SET inbox_seen_at = EXCLUDED.inbox_seen_at",
            uid, Sql.ts(System.currentTimeMillis()),
        )
        return linkedMapOf("ok" to true)
    }

    /** daily step 11: older than 21 days (≤ 4000 per run) */
    fun cleanup(nowMs: Long): Int = jdbc.update(
        "DELETE FROM inbox_items WHERE id IN (SELECT id FROM inbox_items WHERE created_at < ? LIMIT 4000)",
        Sql.ts(nowMs - 21L * 24 * 3600 * 1000),
    )

    // ------------------------------------------------------------------ devices.js

    /** opening the app / a new token: this phone belongs to this account now (other accounts lose it) */
    @Transactional
    fun register(uid: String, token: String) {
        jdbc.update("DELETE FROM device_tokens WHERE token = ? AND uid <> ?", token, uid)
        jdbc.update(
            """
            INSERT INTO device_tokens (token, uid, created_at, updated_at) VALUES (?, ?, clock_timestamp(), clock_timestamp())
            ON CONFLICT (token) DO UPDATE SET uid = EXCLUDED.uid, updated_at = clock_timestamp()
            """.trimIndent(),
            token, uid,
        )
        // the newest MAX_DEVICES phones stay
        jdbc.update(
            """
            DELETE FROM device_tokens WHERE uid = ? AND token NOT IN
              (SELECT token FROM device_tokens WHERE uid = ? ORDER BY updated_at DESC LIMIT $MAX_DEVICES)
            """.trimIndent(),
            uid, uid,
        )
    }

    /** logout on one phone: only that phone's token */
    fun unregister(uid: String, token: String) {
        jdbc.update("DELETE FROM device_tokens WHERE uid = ? AND token = ?", uid, token)
    }

    companion object {
        const val MAX_DEVICES = 5

        /** devices.js validToken */
        fun validToken(t: String?): Boolean = t != null && t.length in 20..4096 && t.none { it.isWhitespace() }
    }
}

/** `inbox`, `inbox_read`, `device_register`, `device_unregister` */
@Component
class InboxActions(private val inbox: InboxService) : ActionModule {
    override fun actions(): Map<String, ActionHandler> = mapOf(
        "inbox" to ActionHandler { ctx -> inbox.inbox(ctx.user.id) },
        "inbox_read" to ActionHandler { ctx -> inbox.markRead(ctx.user.id) },
        "device_register" to ActionHandler { ctx -> register(ctx) },
        "device_unregister" to ActionHandler { ctx ->
            val token = ctx.body.string("token")
            if (InboxService.validToken(token)) inbox.unregister(ctx.user.id, token!!)
            linkedMapOf("ok" to true)
        },
    )

    private fun register(ctx: ActionContext): Any {
        val token = Js.string(ctx.body["token"])
        if (!InboxService.validToken(token)) throw ctx.err(400, "Invalid device", "ফোন চেনা যায়নি")
        inbox.register(ctx.user.id, token!!)
        return linkedMapOf("ok" to true)
    }
}

/** daily step 11 (`inboxDeleted`) */
@Component
class InboxCleanupTask(private val inbox: InboxService) : DailyTask {
    override val name = "inboxDeleted"
    override val order = 11

    override fun run(nowMs: Long): Any = inbox.cleanup(nowMs)
}
