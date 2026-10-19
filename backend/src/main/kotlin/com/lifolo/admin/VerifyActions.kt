package com.lifolo.admin

import com.lifolo.admin.kernel.ListingKind
import com.lifolo.admin.kernel.ListingRows
import com.lifolo.admin.kernel.VerifyDocs
import com.lifolo.admin.kernel.VerifySpec
import com.lifolo.auth.AdminService
import com.lifolo.common.Lang
import com.lifolo.common.QuotaService
import com.lifolo.common.Rules
import com.lifolo.common.Sql
import com.lifolo.common.action.ActionContext
import com.lifolo.common.action.ActionHandler
import com.lifolo.common.action.ActionModule
import com.lifolo.notify.PushService
import com.lifolo.notify.PushText
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.stereotype.Component

/**
 * verify.js actions: the owner sends document photos later (`verify_docs_upload` — when an admin asked, or for a
 * listing from an older app version), the admin looks at the photos (`admin_verify_docs`, only when the panel asks)
 * and asks the owner for photos (`admin_request_docs` — push + "add photos" in the app).
 */
@Component
class VerifyActions(
    private val jdbc: JdbcTemplate,
    private val admins: AdminService,
    private val quotas: QuotaService,
    private val rows: ListingRows,
    private val verify: VerifyDocs,
    private val push: PushService,
) : ActionModule {
    override fun actions(): Map<String, ActionHandler> = mapOf(
        "verify_docs_upload" to ActionHandler { upload(it) },
        "admin_verify_docs" to ActionHandler { adminDocs(it) },
        "admin_request_docs" to ActionHandler { requestDocs(it) },
    )

    private class Target(val kind: ListingKind, val id: String, val x: Map<String, Any?>, val owner: String?)

    /** verify.js loadTarget */
    private fun load(ctx: ActionContext): Target {
        val kind = ListingKind.of(ctx.body.string("kind"))
        val id = ctx.body.string("id")
        if (kind == null || !Rules.isValidId(id)) throw ctx.err(400, "Not found", "পাওয়া যায়নি")
        val x = rows.load(kind, id!!) ?: throw ctx.err(404, "Not found", "পাওয়া যায়নি")
        val owner = if (kind == ListingKind.BADGE || kind == ListingKind.PHARMACY || kind == ListingKind.LAB) id else x["ownerUid"] as String?
        return Target(kind, id, x, owner)
    }

    private fun requireAdmin(ctx: ActionContext) {
        if (!admins.isAdmin(ctx.user)) throw ctx.err(403, "Admins only", "শুধু অ্যাডমিন")
    }

    fun upload(ctx: ActionContext): Any {
        val t = load(ctx)
        if (t.owner != ctx.user.id) throw ctx.err(404, "Not found", "পাওয়া যায়নি")
        val docs = ctx.body["docs"]
        verify.validate(ctx.lang, t.kind.kind, t.x, docs, false)
        quotas.use(ctx.lang, ctx.user.id, "docs")
        val have = verify.save(ctx.lang, t.kind.kind, t.id, ctx.user.id, t.x, docs, false)
        if (have != null) {
            jdbc.update(
                "UPDATE ${t.kind.table} SET docs_requested_at = NULL, docs_request_note = NULL, docs_sent_at = ? WHERE ${t.kind.idColumn} = ?",
                Sql.ts(System.currentTimeMillis()), t.id,
            )
        }
        val after = LinkedHashMap(t.x)
        after["docsHave"] = (VerifySpec.haveTypes(t.x) + have.orEmpty()).toList()
        after["docsRequestedAt"] = 0L
        val out = linkedMapOf<String, Any?>("ok" to true, "saved" to (have?.size ?: 0))
        out.putAll(verify.ownerInfo(t.kind.kind, t.id, after))
        return out
    }

    fun adminDocs(ctx: ActionContext): Any {
        requireAdmin(ctx)
        val t = load(ctx)
        val list = VerifySpec.docs(t.kind.kind, t.x)
        val stored = HashMap<String, Pair<String?, Long>>()
        jdbc.query(
            "SELECT type, data, at FROM verify_docs WHERE kind = ? AND listing_id = ?",
            RowCallbackHandler { rs -> stored[rs.getString(1)] = rs.getString(2) to rs.getTimestamp(3).time },
            t.kind.kind, t.id,
        )
        // the count comes from the real photos (correct even when the listing's docs_have is old)
        val x = LinkedHashMap(t.x)
        x["docsHave"] = list.filter { it.type in stored }.map { it.type }
        val out = LinkedHashMap(verify.panelInfo(t.kind.kind, t.id, x))
        out["photos"] = list.map { d ->
            val s = stored[d.type]
            linkedMapOf("type" to d.type, "label" to d.label, "data" to s?.first, "at" to (s?.second ?: 0L))
        }
        return out
    }

    fun requestDocs(ctx: ActionContext): Any {
        requireAdmin(ctx)
        val t = load(ctx)
        val owner = t.owner ?: throw ctx.err(400, "This was added by an admin and has no owner", "এটা অ্যাডমিন যোগ করেছেন, কোনো মালিক নেই")
        val note = Rules.cleanText(ctx.body.string("note"), 200) ?: ""
        jdbc.update(
            "UPDATE ${t.kind.table} SET docs_requested_at = ?, docs_request_note = ? WHERE ${t.kind.idColumn} = ?",
            Sql.ts(System.currentTimeMillis()), note, t.id,
        )
        val k = t.kind
        val name = t.x["name"] as? String ?: ""
        push.pushUsers(listOf(owner)) { _, lang ->
            PushText(
                title = Lang.pick(lang, "Add document photos for your ${k.labelEn}", "আপনার ${k.labelBn}-এর কাগজের ছবি দিন"),
                body = listOf(name, note).filter { it.isNotEmpty() }.joinToString(" · ")
                    .ifEmpty { Lang.pick(lang, "Open the app to add them. It will be checked after that.", "অ্যাপ খুলে ছবি দিন। এরপর যাচাই হবে।") },
                data = if (k == ListingKind.BADGE) mapOf("type" to "badge") else mapOf("type" to "services", "kind" to k.kind),
            )
        }
        return linkedMapOf("ok" to true)
    }
}
