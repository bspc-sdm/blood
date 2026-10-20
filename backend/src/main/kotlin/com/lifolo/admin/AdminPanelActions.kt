package com.lifolo.admin

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lifolo.admin.kernel.Catalog
import com.lifolo.auth.AdminService
import com.lifolo.blood.CorrectionService
import com.lifolo.common.ApiException
import com.lifolo.common.Body
import com.lifolo.common.Js
import com.lifolo.common.Places
import com.lifolo.common.Rules
import com.lifolo.common.action.ActionContext
import com.lifolo.common.action.ActionHandler
import com.lifolo.common.action.ActionModule
import com.lifolo.common.action.ActionRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * api/community.js admin parts — extra admins (`admin_list`, `admin_add`, `admin_remove`: owner only) and the web
 * panel's start page `admin_overview` (every waiting job at once, with area names; a failing part shows its error
 * and the rest still loads) — plus catalog.js `catalog_get` / `admin_catalog_set` (the pick lists).
 */
@Component
class AdminPanelActions(
    private val jdbc: JdbcTemplate,
    private val admins: AdminService,
    private val catalog: Catalog,
    private val corrections: CorrectionService,
    private val registry: ObjectProvider<ActionRegistry>,
    private val mapper: ObjectMapper,
) : ActionModule {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun actions(): Map<String, ActionHandler> = mapOf(
        "admin_list" to ActionHandler { list(it) },
        "admin_add" to ActionHandler { add(it) },
        "admin_remove" to ActionHandler { remove(it) },
        "admin_overview" to ActionHandler { overview(it) },
        "catalog_get" to ActionHandler { linkedMapOf("lists" to catalog.lists(), "updatedAt" to catalog.updatedAt()) },
        "admin_catalog_set" to ActionHandler { catalogSet(it) },
    )

    companion object {
        private val EMAIL_RE = Regex("^[^\\s@/]+@[^\\s@/]+\\.[a-z]{2,}$")
        const val MAX_ADMINS = 20
    }

    private fun requireOwner(ctx: ActionContext) {
        if (!admins.isOwner(ctx.user)) throw ctx.err(403, "Owner only", "শুধু মালিক")
    }

    private fun bodyEmail(ctx: ActionContext): String = ctx.body.string("email")?.trim()?.lowercase() ?: ""

    fun list(ctx: ActionContext): Map<String, Any?> {
        requireOwner(ctx)
        val items = jdbc.queryForList("SELECT email FROM admin_emails ORDER BY added_at, email", String::class.java).map { linkedMapOf("email" to it) }
        return linkedMapOf("owners" to admins.adminEmails(), "items" to items)
    }

    fun add(ctx: ActionContext): Any {
        requireOwner(ctx)
        val email = bodyEmail(ctx)
        if (!EMAIL_RE.matches(email) || email.length > 200) throw ctx.err(400, "Enter a valid email", "সঠিক ইমেইল দিন")
        val n = jdbc.queryForObject("SELECT count(*) FROM admin_emails", Int::class.java) ?: 0
        if (n >= MAX_ADMINS) throw ctx.err(400, "Up to 20 admins", "সর্বোচ্চ ২০ জন অ্যাডমিন")
        jdbc.update("INSERT INTO admin_emails (email, added_by) VALUES (?, ?) ON CONFLICT (email) DO NOTHING", email, ctx.user.email.lowercase())
        admins.clearCache()
        return linkedMapOf("ok" to true)
    }

    fun remove(ctx: ActionContext): Any {
        requireOwner(ctx)
        val email = bodyEmail(ctx)
        if (email.isEmpty()) throw ctx.err(400, "Not found", "পাওয়া যায়নি")
        jdbc.update("DELETE FROM admin_emails WHERE email = ?", email)
        admins.clearCache()
        return linkedMapOf("ok" to true)
    }

    // ------------------------------------------------------------------ admin_overview

    /** union, upazila, district joined (hospital-match.js UNION / UPAZILA) */
    private fun area(district: Any?, upazilaId: Any?, unionId: Any?): String = listOf(
        (unionId as? Number)?.let { Places.UNION[it.toInt()]?.bn },
        (upazilaId as? Number)?.let { Places.UPAZILA[it.toInt()]?.bn },
        district as? String,
    ).filter { !it.isNullOrEmpty() }.joinToString(", ")

    /** runs another action's handler with the same caller (Node: `actions.admin_badges(req, user)`) */
    @Suppress("UNCHECKED_CAST")
    private fun items(ctx: ActionContext, action: String, extra: Map<String, Any?> = emptyMap()): List<LinkedHashMap<String, Any?>> {
        val h = registry.getObject().find(action) ?: error("no action $action")
        val body = if (extra.isEmpty()) {
            ctx.body
        } else {
            val node = ctx.body.node.deepCopy()
            extra.forEach { (k, v) -> node.set<ObjectNode>(k, mapper.valueToTree(v)) }
            Body(node)
        }
        val res = h.handle(ActionContext(ctx.request, ctx.response, body, ctx.user, ctx.caller, action))
        val m = mapper.convertValue(res, object : TypeReference<LinkedHashMap<String, Any?>>() {})
        return (m["items"] as? List<Map<String, Any?>>)?.map { LinkedHashMap(it) } ?: emptyList()
    }

    private fun part(ctx: ActionContext, fn: () -> List<Map<String, Any?>>): Map<String, Any?> = try {
        linkedMapOf("items" to fn())
    } catch (e: Exception) {
        log.error("overview part failed", e)
        linkedMapOf("items" to emptyList<Any>(), "error" to if (e is ApiException) e.message else ctx.tr("Could not load", "আনা যায়নি"))
    }

    private fun withArea(list: List<LinkedHashMap<String, Any?>>) = list.map { it.apply { put("areaLabel", area(it["district"], it["upazilaId"], it["unionId"])) } }

    fun overview(ctx: ActionContext): Any {
        if (!admins.isAdmin(ctx.user)) throw ctx.err(403, "Admins only", "শুধু অ্যাডমিন")
        val owner = admins.isOwner(ctx.user)
        val badges = part(ctx) { items(ctx, "admin_badges") }
        val pharmacies = part(ctx) { withArea(items(ctx, "admin_pharmacies")) }
        val ambulances = part(ctx) { withArea(items(ctx, "admin_ambulances")) }
        val bloodBanks = part(ctx) { withArea(items(ctx, "admin_bloodbanks")) }
        val labs = part(ctx) { items(ctx, "admin_labs") }
        val hospitalRequests = part(ctx) {
            items(ctx, "admin_hospital_requests").map { h ->
                h.apply {
                    put("areaLabel", area(h["district"], h["upazilaId"], h["unionId"]))
                    @Suppress("UNCHECKED_CAST")
                    put(
                        "possibleDuplicates",
                        ((h["possibleDuplicates"] as? List<Map<String, Any?>>) ?: emptyList()).map { d ->
                            LinkedHashMap(d).apply { put("areaLabel", area(null, d["upazilaId"], d["unionId"])) }
                        },
                    )
                }
            }
        }
        val reports = part(ctx) { items(ctx, "admin_reports") }
        val corr = part(ctx) { corrections.pending() }
        val adminList = if (owner) runCatching { list(ctx) }.getOrNull() else null
        val tickets = part(ctx) { items(ctx, "admin_tickets", mapOf("status" to "open")) }
        return linkedMapOf(
            "me" to linkedMapOf("email" to ctx.user.email, "isOwner" to owner),
            "badges" to badges,
            "pharmacies" to pharmacies,
            "ambulances" to ambulances,
            "bloodBanks" to bloodBanks,
            "labs" to labs,
            "hospitalRequests" to hospitalRequests,
            "reports" to reports,
            "corrections" to corr,
            "tickets" to tickets,
            "admins" to adminList,
            "at" to System.currentTimeMillis(),
        )
    }

    // ------------------------------------------------------------------ catalog.js admin_catalog_set

    /** one whole list (order, names, hidden, new ones); codes are never deleted — a dropped one stays hidden at the end */
    fun catalogSet(ctx: ActionContext): Any {
        if (!admins.isAdmin(ctx.user)) throw ctx.err(403, "Admins only", "শুধু অ্যাডমিন")
        val kind = ctx.body.string("kind")
        val type = ctx.body.string("type")
        if (kind == null || type == null || Catalog.KINDS[kind]?.contains(type) != true) throw ctx.err(400, "Unknown list", "অজানা তালিকা")
        val raw = ctx.body["items"]?.takeIf { it.isArray }?.toList() ?: emptyList()
        if (raw.isEmpty() || raw.size > Catalog.MAX_ITEMS) {
            throw ctx.err(400, "1–${Catalog.MAX_ITEMS} items", "১–${Catalog.MAX_ITEMS}টা নাম")
        }
        val now = catalog.lists()[kind]?.get(type) ?: emptyList()
        val known = now.mapNotNull { it["code"] as? String }.toSet()
        val taken = HashSet(known)
        val out = ArrayList<LinkedHashMap<String, Any?>>()
        for (item in raw) {
            val en = Rules.cleanText(if (Js.truthy(item.get("en"))) Js.toStr(item.get("en")) else "", 60)
            val bn = Rules.cleanText(if (Js.truthy(item.get("bn"))) Js.toStr(item.get("bn")) else "", 60)
            if (en == null || bn == null) throw ctx.err(400, "Write both Bangla and English names", "বাংলা আর ইংরেজি দুটো নামই লিখুন")
            var code = Js.string(item.get("code"))?.takeIf { c -> c in known }
            if (code == null) {
                code = Catalog.makeCode(en, taken)
                taken.add(code)
            }
            if (out.any { o -> o["code"] == code }) continue
            out.add(linkedMapOf<String, Any?>("code" to code, "en" to en, "bn" to bn).apply { if (item.get("hidden")?.let { h -> h.isBoolean && h.booleanValue() } == true) put("hidden", true) })
        }
        for (old in now) if (out.none { o -> o["code"] == old["code"] }) out.add(LinkedHashMap(old).apply { put("hidden", true) })
        catalog.save(kind, type, out)
        return linkedMapOf("items" to (catalog.lists()[kind]?.get(type) ?: emptyList<Any>()))
    }
}
