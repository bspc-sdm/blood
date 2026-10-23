package com.lifolo.features

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lifolo.admin.AdminLog
import com.lifolo.auth.AuthUser
import com.lifolo.common.ApiException
import com.lifolo.common.Body
import com.lifolo.common.CronAuth
import com.lifolo.common.LifoloProperties
import com.lifolo.common.Rules
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.sql.Timestamp

/**
 * POST /api/cron/features?do=show|set with `Authorization: Bearer $CRON_SECRET` — the "Ops: feature switches" workflow
 * (`.github/workflows/ops-features.yml`, `deploy/ops/features.sh`) on the VPS through 127.0.0.1:8088 (nginx answers 404
 * for /api/cron/ from outside). backend/docs/FEATURES.md → "Ops: all on / all off / restore".
 *
 *  - `do=show` → `{ok, v, allOn, items: [{key, mode, pilotUids, demo, needs, updatedAt, updatedBy}], unmet}`.
 *  - `do=set` with `{items: [{key, mode, pilotUids?, demo?}]}` → every item is checked first (nothing is written when one
 *    is wrong), then saved like the admin page's `admin_feature_set` ([FeatureActions.store]: `feature_changes` row,
 *    `updated_by`) plus an `admin_log` row — the actor is [ACTOR] — needed keys first; then the cache is cleared once
 *    and ONE silent `features_changed` push / SSE event goes to everyone affected by any of the changes.
 *    Answer: `{ok, v, changed: [{key, from, to}], unchanged, unmet}`.
 *
 * `unmet`: keys that are `on` while a key they need ([Features.NEEDS]) is not `on`.
 */
@RestController
class FeatureOpsController(
    private val props: LifoloProperties,
    private val features: Features,
    private val actions: FeatureActions,
    private val push: FeaturePush,
    private val adminLog: AdminLog,
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
) {
    companion object {
        const val ACTOR = "ops-features"
        private const val MAX_BODY = 512 * 1024
    }

    @RequestMapping("/api/cron/features")
    fun ops(req: HttpServletRequest): ResponseEntity<Any> {
        if (req.method != "POST") return ResponseEntity.status(405).body(mapOf("error" to "Method not allowed"))
        if (!CronAuth.authorized(req, props.cronSecret)) throw ApiException(401, "অনুমতি নেই")
        return when (req.getParameter("do") ?: "show") {
            "show" -> ResponseEntity.ok(show())
            "set" -> set(req)
            else -> ResponseEntity.badRequest().body(mapOf("ok" to false, "error" to "do=show|set"))
        }
    }

    private fun show(): Map<String, Any?> {
        val s = features.load()
        val rows = jdbc.queryForList("SELECT key, updated_at, updated_by FROM feature_flags").associateBy { it["key"] as String }
        val items = Features.KEYS.map { k ->
            val c = s.flags[k] ?: FeatureConfig()
            linkedMapOf(
                "key" to k,
                "mode" to c.mode,
                "pilotUids" to c.pilotUids,
                "demo" to c.demo,
                "needs" to Features.NEEDS[k].orEmpty(),
                "updatedAt" to (rows[k]?.get("updated_at") as? Timestamp)?.time,
                "updatedBy" to (rows[k]?.get("updated_by") ?: ""),
            )
        }
        return linkedMapOf("ok" to true, "v" to s.v, "allOn" to features.allOn, "items" to items, "unmet" to unmet(s))
    }

    /** `on` keys whose needed keys are not `on` */
    private fun unmet(s: FeatureState): List<Map<String, Any>> = Features.KEYS.mapNotNull { k ->
        if ((s.flags[k]?.mode ?: Features.OFF) != Features.ON) return@mapNotNull null
        val missing = Features.NEEDS[k].orEmpty().filter { (s.flags[it]?.mode ?: Features.OFF) != Features.ON }
        if (missing.isEmpty()) null else mapOf("key" to k, "needs" to missing)
    }

    private data class Item(val key: String, val mode: String, val pilots: List<String>?, val demo: Boolean?)

    private fun bad(msg: String): ResponseEntity<Any> = ResponseEntity.badRequest().body(mapOf("ok" to false, "error" to msg))

    /** how deep a key sits in [Features.NEEDS] (needed keys are saved first) */
    private fun depth(k: String, seen: Int = 0): Int =
        if (seen > Features.KEYS.size) 0 else (Features.NEEDS[k].orEmpty().maxOfOrNull { depth(it, seen + 1) + 1 } ?: 0)

    private fun set(req: HttpServletRequest): ResponseEntity<Any> {
        val raw = req.inputStream.readNBytes(MAX_BODY + 1)
        if (raw.size > MAX_BODY) return bad("body too large")
        val root: JsonNode = try {
            mapper.readTree(raw) ?: return bad("items[] needed")
        } catch (e: Exception) {
            return bad("invalid JSON")
        }
        val list = root["items"]
        if (list == null || !list.isArray || list.isEmpty) return bad("items[] needed")
        val items = ArrayList<Item>()
        for (n in list) {
            val key = n["key"]?.takeIf { it.isTextual }?.textValue()
            if (key == null || key !in Features.KEYS) return bad("unknown key: $key")
            val mode = n["mode"]?.takeIf { it.isTextual }?.textValue()
            if (mode == null || mode !in Features.MODES) return bad("$key: mode must be off|pilot|on")
            val pn = n["pilotUids"]
            val pilots = when {
                pn == null || pn.isNull -> null
                !pn.isArray -> return bad("$key: pilotUids must be a list")
                else -> pn.map { p ->
                    val id = if (p.isTextual) p.textValue().trim() else null
                    if (!Rules.isValidId(id)) return bad("$key: invalid pilot uid")
                    id!!
                }.distinct()
            }
            if (pilots != null && pilots.size > FeatureActions.MAX_PILOTS) return bad("$key: too many pilots")
            val dn = n["demo"]
            val demo = when {
                dn == null || dn.isNull -> null
                dn.isBoolean -> dn.booleanValue()
                else -> return bad("$key: demo must be true/false")
            }
            if (items.any { it.key == key }) return bad("$key twice")
            items += Item(key, mode, pilots, demo)
        }
        items.sortWith(compareBy<Item> { depth(it.key) }.thenBy { Features.KEYS.indexOf(it.key) })

        val actor = AuthUser(uid = null, email = ACTOR, webSession = true)
        val changed = ArrayList<Map<String, Any?>>()
        var everyone = false
        val uids = HashSet<String>()
        var demo = false
        for (item in items) {
            val r = actions.store(item.key, item.mode, item.pilots, item.demo, null, ACTOR)
            if (!r.changed) continue
            // the admin page's audit line (AdminLog.LOGGED["admin_feature_set"]), with the ops actor
            val body = mapper.createObjectNode().put("key", item.key).put("mode", item.mode)
            if (item.pilots != null) body.set<JsonNode>("pilotUids", mapper.valueToTree(item.pilots))
            if (item.demo != null) body.put("demo", item.demo)
            adminLog.log("admin_feature_set", actor, Body.of(body))
            val a = push.audience(r.old, r.new)
            if (a.everyone) everyone = true
            uids += a.uids
            if (a.demo) demo = true
            changed += linkedMapOf("key" to item.key, "from" to cfg(r.old), "to" to cfg(r.new))
        }
        val s: FeatureState
        if (changed.isNotEmpty()) {
            features.clearCache()
            s = features.load()
            push.changed(s.v, FeaturePush.Audience(everyone, if (everyone) emptySet() else uids, !everyone && demo))
        } else {
            s = features.load()
        }
        return ResponseEntity.ok(
            linkedMapOf(
                "ok" to true, "v" to s.v, "changed" to changed, "unchanged" to items.size - changed.size, "unmet" to unmet(s),
            ),
        )
    }

    private fun cfg(c: FeatureConfig): Map<String, Any> = linkedMapOf("mode" to c.mode, "pilotUids" to c.pilotUids, "demo" to c.demo)
}
