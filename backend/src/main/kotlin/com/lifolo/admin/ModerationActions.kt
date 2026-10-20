package com.lifolo.admin

import com.lifolo.admin.kernel.ListingKind
import com.lifolo.admin.kernel.ListingReview
import com.lifolo.admin.kernel.ListingRows
import com.lifolo.admin.kernel.Moderation
import com.lifolo.admin.kernel.Sanctions
import com.lifolo.auth.AdminService
import com.lifolo.common.Js
import com.lifolo.common.Phones
import com.lifolo.common.QuotaService
import com.lifolo.common.Rules
import com.lifolo.common.Sql
import com.lifolo.common.action.ActionContext
import com.lifolo.common.action.ActionHandler
import com.lifolo.common.action.ActionModule
import com.lifolo.contracts.Chambers
import com.lifolo.contracts.ContentModeration
import com.lifolo.contracts.ProviderSuspension
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * moderation.js actions — `report` (anyone: a question, answer, tip, home-care/chamber professional, ambulance,
 * pharmacy, blood bank), `admin_reports` (open reports grouped per target), `admin_report_resolve` (remove = the
 * report is upheld: content deleted, a listing suspended with a reason, a professional suspended/blocked; keep =
 * dismissed). Rows: `mod_reports` (Node ids kept, see V600).
 */
@Component
class ModerationActions(
    private val jdbc: JdbcTemplate,
    private val admins: AdminService,
    private val quotas: QuotaService,
    private val rows: ListingRows,
    private val sanctions: Sanctions,
    private val review: ListingReview,
    private val suspension: ProviderSuspension,
    private val content: ContentModeration,
    private val chambers: Chambers,
) : ActionModule {
    override fun actions(): Map<String, ActionHandler> = mapOf(
        "report" to ActionHandler { report(it) },
        "admin_reports" to ActionHandler { adminReports(it) },
        "admin_report_resolve" to ActionHandler { adminResolve(it) },
    )

    companion object {
        val KINDS = listOf("question", "answer", "tip", "homecare", "provider", "ambulance", "pharmacy", "bloodbank")
        val PATH_RE = Regex(
            "^(questions/[A-Za-z0-9_-]+(/answers/[A-Za-z0-9_-]+)?|tips/[A-Za-z0-9_-]+|homeVisits/[A-Za-z0-9_-]+|providers/[A-Za-z0-9_-]+|" +
                "ambulances/[A-Za-z0-9_-]+|pharmacies/[A-Za-z0-9_-]+|bloodBanks/[A-Za-z0-9_-]+)$",
        )
        private const val DEFAULT_REASON = "ব্যবহারকারীর রিপোর্ট যাচাই করে সত্য পাওয়া গেছে"
    }

    private fun requireAdmin(ctx: ActionContext) {
        if (!admins.isAdmin(ctx.user)) throw ctx.err(403, "Admins only", "শুধু অ্যাডমিন")
    }

    /** what a report points at (the Node document fields the moderation code reads), null when gone */
    private fun target(path: String): Map<String, Any?>? {
        val p = path.split('/')
        fun one(sql: String, vararg args: Any?): Map<String, Any?>? = jdbc.queryForList(sql, *args).firstOrNull()
        return when (p[0]) {
            "questions" -> if (p.size == 4) {
                one("SELECT text, author_name AS \"authorName\", edited_at FROM answers WHERE id = ? AND question_id = ?", p[3], p[1])
            } else {
                one("SELECT text, anonymous, author_name AS \"authorName\", edited_at FROM questions WHERE id = ?", p[1])
            }
            "tips" -> one("SELECT title, body, author_name AS \"authorName\", edited_at FROM tips WHERE id = ?", p[1])
            "providers" -> one(
                "SELECT uid, name, type, home_phone AS \"homePhone\", home_visit_enabled AS \"homeEnabled\" FROM providers WHERE uid = ?", p[1],
            )
            "ambulances" -> one("SELECT name, phone, district, status FROM ambulances WHERE id = ?", p[1])
            "pharmacies" -> one("SELECT name, phone, district, status FROM pharmacies WHERE uid = ?", p[1])
            "bloodBanks" -> one("SELECT name, phone, district, status FROM blood_banks WHERE id = ?", p[1])
            else -> null
        }
    }

    /** [title, text || body].filter(Boolean).join('\n') */
    private fun textOf(t: Map<String, Any?>): String {
        val text = (t["text"] as? String)?.takeIf { it.isNotEmpty() } ?: (t["body"] as? String)
        return listOf(t["title"] as? String, text).filter { !it.isNullOrEmpty() }.joinToString("\n")
    }

    private fun editedAt(t: Map<String, Any?>): Long = (t["edited_at"] as? java.sql.Timestamp)?.time ?: 0L

    // ------------------------------------------------------------------ report

    fun report(ctx: ActionContext): Any {
        val b = ctx.body
        val uid = ctx.user.id
        val kind = b.string("kind")
        val id = b.string("id")
        val answerId = b.string("answerId")
        if (kind == null || kind !in KINDS || !Rules.isValidId(id) || (kind == "answer" && !Rules.isValidId(answerId))) {
            throw ctx.err(400, "Not found", "পাওয়া যায়নি")
        }
        val path = Moderation.targetPath(kind, id!!, answerId)
        val t = target(path) ?: throw ctx.err(404, "Not found", "পাওয়া যায়নি")
        var reason = ""
        if (kind == "homecare" && (id == uid || t["homeEnabled"] != true)) throw ctx.err(404, "Not found", "পাওয়া যায়নি")
        if (kind == "provider" && id == uid) throw ctx.err(404, "Not found", "পাওয়া যায়নি")
        // ambulance/pharmacy/blood bank/home care: only someone who called from the app may report (no fake competitor reports)
        val called = when (kind) {
            "ambulance" -> "SELECT 1 FROM ambulance_calls WHERE uid = ? AND ambulance_id = ?"
            "pharmacy" -> "SELECT 1 FROM pharmacy_calls WHERE uid = ? AND pharmacy_uid = ?"
            "bloodbank" -> "SELECT 1 FROM blood_bank_calls WHERE uid = ? AND bank_id = ?"
            "homecare" -> "SELECT 1 FROM home_calls WHERE uid = ? AND provider_uid = ?"
            else -> null
        }
        if (called != null && jdbc.queryForList(called, uid, id).isEmpty() && !answeredFind(kind, uid, id)) {
            throw ctx.err(403, "You can report after calling from the app", "অ্যাপ থেকে ফোন করার পর রিপোর্ট করতে পারবেন")
        }
        if (kind in setOf("ambulance", "pharmacy", "homecare", "bloodbank", "provider")) {
            reason = Rules.cleanText(b.string("reason"), 300) ?: throw ctx.err(400, "Write what happened", "কী হয়েছে লিখুন")
        }
        quotas.use(ctx.lang, uid, "report")
        // one report per person per thing (after an admin dismissed it, only an edit by the author allows a new one)
        val docId = "${path.replace('/', '_')}__$uid${if (kind == "provider") "__provider" else ""}".take(1400)
        val prev = jdbc.queryForList("SELECT status, created_at FROM mod_reports WHERE id = ?", docId).firstOrNull()
        if (prev != null) {
            val prevAt = (prev["created_at"] as? java.sql.Timestamp)?.time ?: 0L
            if (!(prev["status"] == "dismissed" && editedAt(t) > prevAt)) return linkedMapOf("ok" to true)
        }
        val snapshot = if (kind in setOf("question", "answer", "tip")) textOf(t).take(3000) else ""
        jdbc.update(
            """
            INSERT INTO mod_reports (id, target_path, kind, reporter_uid, reported_uid, reason, snapshot, status, created_at, resolved_at,
              reopened_at, reopen_reason, reported_role, reported_name)
            VALUES (?, ?, ?, ?, NULL, ?, ?, 'open', ?, NULL, NULL, NULL, NULL, NULL)
            ON CONFLICT (id) DO UPDATE SET target_path = EXCLUDED.target_path, kind = EXCLUDED.kind, reporter_uid = EXCLUDED.reporter_uid,
              reported_uid = NULL, reason = EXCLUDED.reason, snapshot = EXCLUDED.snapshot, status = 'open', created_at = EXCLUDED.created_at,
              resolved_at = NULL, reopened_at = NULL, reopen_reason = NULL, reported_role = NULL, reported_name = NULL
            """.trimIndent(),
            docId, path, kind, uid, reason, snapshot, Sql.ts(System.currentTimeMillis()),
        )
        return linkedMapOf("ok" to true)
    }

    /** "find a medicine": the shop answered this user's find (said it has it, then did not) */
    private fun answeredFind(kind: String, uid: String, shop: String): Boolean = kind == "pharmacy" && jdbc.queryForList(
        """
        SELECT 1 FROM med_find_targets t JOIN med_finds f ON f.id = t.find_id
        WHERE f.uid = ? AND t.pharmacy_uid = ? AND (t.answered_at IS NOT NULL OR t.have IS NOT NULL) LIMIT 1
        """.trimIndent(),
        uid, shop,
    ).isNotEmpty()

    // ------------------------------------------------------------------ admin_reports

    private class Group(
        val targetPath: String,
        val kind: String,
        val reportedUid: String?,
        val reportedRole: String?,
        val reportedName: String,
        val snapshot: String,
    ) {
        var count = 0
        val reasons = ArrayList<String>()
    }

    fun adminReports(ctx: ActionContext): Any {
        requireAdmin(ctx)
        val open = jdbc.queryForList("SELECT * FROM mod_reports WHERE status = 'open' ORDER BY created_at, id LIMIT 200")
        // a doctor's chamber reports only in the web panel (the old app's "remove" would have done the wrong thing)
        val panel = ctx.body.isTrue("panel")
        val groups = LinkedHashMap<String, Group>()
        for (d in open) {
            val p = d["target_path"] as? String ?: continue
            if (!PATH_RE.matches(p)) continue
            val kind = d["kind"] as String
            if (kind == "provider" && !panel) continue
            val reported = d["reported_uid"] as? String
            val key = (if (!reported.isNullOrEmpty()) "$p|$reported" else p) + if (kind == "provider") "|provider" else ""
            val g = groups.getOrPut(key) {
                Group(p, kind, reported?.takeIf { it.isNotEmpty() }, (d["reported_role"] as? String)?.takeIf { it.isNotEmpty() }, d["reported_name"] as? String ?: "", d["snapshot"] as? String ?: "")
            }
            g.count++
            (d["reason"] as? String)?.takeIf { it.isNotEmpty() }?.let { g.reasons.add(it) }
        }
        val items = ArrayList<Map<String, Any?>>()
        val gone = LinkedHashSet<String>()
        for (g in groups.values) {
            val t = target(g.targetPath)
            if (t == null) {
                gone.add(g.targetPath)
                continue
            }
            val text = when (g.kind) {
                "ambulance", "pharmacy", "bloodbank" ->
                    (listOf("${t["name"] ?: ""} · ${Phones.fixPhone(t["phone"] as? String)} · ${t["district"] ?: ""}") + g.reasons).joinToString("\n")
                "provider" -> {
                    val names = chambers.chambersOf(g.targetPath.substringAfter('/')).joinToString(", ") { it.hospitalName }
                    (listOf("${if (t["type"] == "doctor") "ডাক্তার" else "নার্স"} (চেম্বার): ${t["name"] ?: ""} · $names") + g.reasons).joinToString("\n")
                }
                "homecare" ->
                    (listOf("${if (t["type"] == "doctor") "ডাক্তার" else "নার্স"}: ${t["name"] ?: ""} · ${Phones.fixPhone(t["homePhone"] as? String)}") + g.reasons).joinToString("\n")
                else -> textOf(t)
            }
            val authorName = if (t["anonymous"] == true) {
                "বেনামি"
            } else {
                (t["authorName"] as? String)?.takeIf { it.isNotEmpty() } ?: (t["name"] as? String) ?: ""
            }
            val item = linkedMapOf<String, Any?>(
                "targetPath" to g.targetPath, "kind" to g.kind, "count" to g.count, "text" to text,
                // an anonymous question's author is hidden from admins too
                "authorName" to authorName,
            )
            // the text changed after the report: the text at report time
            if (g.snapshot.isNotEmpty() && g.snapshot != textOf(t).take(3000)) item["snapshot"] = g.snapshot
            item["reportedUid"] = g.reportedUid
            item["reportedRole"] = g.reportedRole
            item["reasons"] = g.reasons.take(10)
            items.add(item)
        }
        // reports on deleted posts are closed, so the list does not fill up
        for (p in gone) jdbc.update("UPDATE mod_reports SET status = 'dismissed' WHERE target_path = ? AND status = 'open'", p)
        return linkedMapOf("items" to items)
    }

    // ------------------------------------------------------------------ admin_report_resolve

    fun adminResolve(ctx: ActionContext): Any {
        requireAdmin(ctx)
        val b = ctx.body
        val path = b.string("targetPath")
        if (path == null || !PATH_RE.matches(path)) throw ctx.err(400, "Not found", "পাওয়া যায়নি")
        val reportedUid = b.string("reportedUid")?.takeIf { Rules.isValidId(it) }
        var docs = jdbc.queryForList("SELECT id, kind, reported_uid, reported_role FROM mod_reports WHERE target_path = ?", path)
        // home visits: two people of one visit may be reported separately — only this person's reports
        if (reportedUid != null) docs = docs.filter { it["reported_uid"] == reportedUid }
        // doctors/nurses: chamber reports and home-care reports are resolved separately
        if (path.startsWith("providers/")) {
            val wantProvider = b.string("kind") == "provider"
            docs = docs.filter { (it["kind"] == "provider") == wantProvider }
        }
        val remove = b.isTrue("remove")
        if (remove) {
            val r = docs.firstOrNull { !(it["reported_uid"] as? String).isNullOrEmpty() }
            // the reason the owner sees: the admin's text, else a general one — never the reporter's own words
            val reason = (if (Js.truthy(b["reason"])) Js.toStr(b["reason"]) else "").trim().take(200).ifEmpty { DEFAULT_REASON }
            removeTarget(path, r?.let { (it["reported_uid"] as String) to (it["reported_role"] as? String) }, b.string("kind"), reason)
        }
        val now = Sql.ts(System.currentTimeMillis())
        for (d in docs) jdbc.update("UPDATE mod_reports SET status = ?, resolved_at = ? WHERE id = ?", if (remove) "removed" else "dismissed", now, d["id"])
        return linkedMapOf("ok" to true)
    }

    /** moderation.js removeTarget */
    private fun removeTarget(path: String, reported: Pair<String, String?>?, kind: String?, reason: String) {
        val p = path.split('/')
        if (p[0] == "providers" && kind == "provider") {
            suspension.suspendProvider(p[1], "report")
            return
        }
        if (p[0] == "ambulances" || p[0] == "pharmacies" || p[0] == "bloodBanks") {
            // not deleted: suspended — no longer listed, and the owner cannot switch it back on
            val k = ListingKind.ofTable(p[0])!!
            val x = rows.load(k, p[1]) ?: return
            val prev = x["status"] as String?
            if (prev == "suspended") return
            jdbc.update(
                "UPDATE ${k.table} SET status = 'suspended', suspended_at = ?, status_reason = ? WHERE ${k.idColumn} = ?",
                Sql.ts(System.currentTimeMillis()), reason, p[1],
            )
            val listing = LinkedHashMap(x)
            if (listing["ownerUid"] == null && k == ListingKind.PHARMACY) listing["ownerUid"] = p[1]
            sanctions.onStatusChange(k.kind, listing, prev, "suspended")
            review.tellSuspendedByReport(k.kind, listing, reason)
            return
        }
        if (p[0] == "providers") {
            // home care off and blocked (the professional cannot switch it on again)
            suspension.blockHomeCare(p[1])
            return
        }
        if (p[0] == "homeVisits") {
            if (reported != null && reported.second == "nurse") suspension.blockHomeCare(reported.first)
            return
        }
        when {
            p[0] == "questions" && p.size == 2 -> content.removeQuestion(p[1])
            p[0] == "questions" && p.size == 4 -> content.removeAnswer(p[1], p[3])
            p[0] == "tips" -> content.removeTip(p[1])
        }
    }
}
