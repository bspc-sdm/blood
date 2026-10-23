package com.lifolo.calls

import com.fasterxml.jackson.databind.ObjectMapper
import com.lifolo.auth.AdminService
import com.lifolo.common.action.ActionContext
import com.lifolo.common.action.ActionHandler
import com.lifolo.common.action.ActionModule
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.stereotype.Component
import java.sql.Timestamp

/**
 * Admin moderation of call reports (`call_reports`, the "রিপোর্ট" of a call — docs/CALLS.md):
 *  - `admin_call_reports {status?: open|resolved|dismissed}` → `{items: [{id, callId, reason, note, status, createdAt,
 *    reporter: {uid, name, email}, reported: {uid, name, email, openReports, blockedBy}, call: {ctx, refId, video, state,
 *    startedAt, answeredAt, durationSec, dir}}]}` (newest first, ≤ 200)
 *  - `admin_call_report_set {id, status}` → `{ok}` (logged)
 * What to do with the reported person is on the user page (`admin_user_action`: account / listing sanctions).
 */
@Component
class CallReportsAdmin(
    private val jdbc: JdbcTemplate,
    private val admins: AdminService,
    private val mapper: ObjectMapper,
) : ActionModule {
    override fun actions(): Map<String, ActionHandler> = mapOf(
        "admin_call_reports" to ActionHandler(::list),
        "admin_call_report_set" to ActionHandler(::set),
    )

    private val statuses = setOf("open", "resolved", "dismissed")

    private fun requireAdmin(ctx: ActionContext) {
        if (!admins.isAdmin(ctx.user)) throw ctx.err(403, "Admins only", "শুধু অ্যাডমিন")
    }

    private fun people(uids: Collection<String>): Map<String, Map<String, Any?>> {
        val ids = uids.distinct()
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<String, Map<String, Any?>>()
        jdbc.query(
            """
            SELECT a.uid, a.email, p.name FROM accounts a LEFT JOIN user_profiles p ON p.uid = a.uid
            WHERE a.uid IN (${ids.joinToString(",") { "?" }})
            """.trimIndent(),
            RowCallbackHandler { rs -> out[rs.getString(1)] = linkedMapOf("uid" to rs.getString(1), "email" to (rs.getString(2) ?: ""), "name" to (rs.getString(3) ?: "")) },
            *ids.toTypedArray(),
        )
        return out
    }

    fun list(ctx: ActionContext): Any {
        requireAdmin(ctx)
        val status = ctx.body.string("status")?.takeIf { it in statuses } ?: "open"
        val rows = jdbc.queryForList("SELECT * FROM call_reports WHERE status = ? ORDER BY created_at DESC, id LIMIT 200", status)
        val reported = rows.map { it["reported_uid"] as String }.distinct()
        val who = people(rows.map { it["reporter_uid"] as String } + reported)
        val openCount = HashMap<String, Int>()
        val blockedBy = HashMap<String, Int>()
        if (reported.isNotEmpty()) {
            val q = reported.joinToString(",") { "?" }
            jdbc.query(
                "SELECT reported_uid, count(*) FROM call_reports WHERE status = 'open' AND reported_uid IN ($q) GROUP BY 1",
                RowCallbackHandler { rs -> openCount[rs.getString(1)] = rs.getInt(2) }, *reported.toTypedArray(),
            )
            jdbc.query(
                "SELECT blocked_uid, count(*) FROM call_blocks WHERE blocked_uid IN ($q) GROUP BY 1",
                RowCallbackHandler { rs -> blockedBy[rs.getString(1)] = rs.getInt(2) }, *reported.toTypedArray(),
            )
        }
        val items = rows.map { r ->
            val rep = r["reported_uid"] as String
            val meta = runCatching { mapper.readValue(r["call_meta"]?.toString() ?: "{}", Map::class.java) }.getOrNull() ?: emptyMap<String, Any?>()
            linkedMapOf(
                "id" to r["id"],
                "callId" to r["call_id"],
                "reason" to r["reason"],
                "note" to r["note"],
                "status" to r["status"],
                "createdAt" to (r["created_at"] as? Timestamp)?.time,
                "reporter" to (who[r["reporter_uid"] as String] ?: linkedMapOf("uid" to r["reporter_uid"], "email" to "", "name" to "")),
                "reported" to LinkedHashMap(who[rep] ?: linkedMapOf("uid" to rep, "email" to "", "name" to "")).apply {
                    put("openReports", openCount[rep] ?: 0)
                    put("blockedBy", blockedBy[rep] ?: 0)
                },
                "call" to meta,
            )
        }
        return linkedMapOf("items" to items)
    }

    fun set(ctx: ActionContext): Any {
        requireAdmin(ctx)
        val id = ctx.body.string("id")
        val status = ctx.body.string("status")
        if (id.isNullOrEmpty() || status == null || status !in statuses) throw ctx.err(400, "Invalid request", "অনুরোধ ঠিক নেই") // bn-ok
        val n = jdbc.update("UPDATE call_reports SET status = ? WHERE id = ?", status, id)
        if (n == 0) throw ctx.notFound()
        return linkedMapOf("ok" to true)
    }
}
