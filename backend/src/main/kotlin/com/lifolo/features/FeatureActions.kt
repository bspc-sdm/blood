package com.lifolo.features

import com.lifolo.auth.AdminService
import com.lifolo.common.Rules
import com.lifolo.common.action.ActionContext
import com.lifolo.common.action.ActionHandler
import com.lifolo.common.action.ActionModule
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp

/**
 * Feature switch actions (docs/FEATURES.md):
 *  - `features` (guest allowed, never cached): which keys are on for the caller → `{keys: {calls_audio: true, …}, v}`.
 *    The apps ask on start, login, resume (at most once a minute) and on the `features_changed` push/SSE event.
 *  - `admin_features_get` (admin): every key with mode / pilot list (with names) / demo / last change, the history, v.
 *  - `admin_feature_set {key, mode, pilotUids?, demo?}` (admin, logged in admin_log + feature_changes): saves, clears
 *    the cache, sends the silent `features_changed` push and the SSE event to the affected accounts.
 */
@Component
class FeatureActions(
    private val features: Features,
    private val jdbc: JdbcTemplate,
    private val admins: AdminService,
    private val push: FeaturePush,
    txm: PlatformTransactionManager,
) : ActionModule {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(txm)

    override fun actions(): Map<String, ActionHandler> = mapOf(
        "features" to ActionHandler { mine(it) },
        "admin_features_get" to ActionHandler { adminGet(it) },
        "admin_feature_set" to ActionHandler { adminSet(it) },
    )

    companion object {
        const val MAX_PILOTS = 500
        const val HISTORY = 100
    }

    // ------------------------------------------------------------------ features

    fun mine(ctx: ActionContext): Any {
        // the person using the app (a caregiver acting for a patient sees their own switches)
        val uid = if (ctx.caller.guest) null else ctx.caller.uid ?: ctx.user.uid
        if (uid != null) remember(uid)
        return mapOf("keys" to features.keysFor(uid), "v" to features.version())
    }

    /** this account's app knows `features` → it may get the silent features_changed push (at most one write a day) */
    private fun remember(uid: String) {
        try {
            jdbc.update(
                """
                INSERT INTO feature_clients (uid, seen_at) SELECT ?, now() WHERE EXISTS (SELECT 1 FROM accounts WHERE uid = ?)
                ON CONFLICT (uid) DO UPDATE SET seen_at = now() WHERE feature_clients.seen_at < now() - interval '1 day'
                """.trimIndent(),
                uid, uid,
            )
        } catch (e: Exception) {
            log.warn("feature client write failed: {}", e.message)
        }
    }

    // ------------------------------------------------------------------ admin

    private fun requireAdmin(ctx: ActionContext) {
        if (!admins.isAdmin(ctx.user)) throw ctx.err(403, "Admins only", "শুধু অ্যাডমিন")
    }

    private fun ms(v: Any?): Long? = (v as? Timestamp)?.time

    /** a PostgreSQL text[] literal (the uids are checked ids: letters, digits, '-', '_') */
    private fun pgArray(uids: List<String>): String = "{" + uids.joinToString(",") { "\"$it\"" } + "}"

    @Suppress("UNCHECKED_CAST")
    private fun arr(v: Any?): List<String> = ((v as? java.sql.Array)?.array as? Array<String>)?.toList() ?: emptyList()

    /** name / email / phone of the pilot accounts (the admin page shows them) */
    private fun people(uids: Collection<String>): Map<String, Map<String, Any?>> {
        if (uids.isEmpty()) return emptyMap()
        val out = HashMap<String, Map<String, Any?>>()
        for (chunk in uids.distinct().chunked(500)) {
            jdbc.query(
                """
                SELECT a.uid, a.email, p.name, p.phone FROM accounts a LEFT JOIN user_profiles p ON p.uid = a.uid
                WHERE a.uid IN (${chunk.joinToString(",") { "?" }})
                """.trimIndent(),
                RowCallbackHandler { rs ->
                    out[rs.getString("uid")] = linkedMapOf(
                        "uid" to rs.getString("uid"),
                        "email" to (rs.getString("email") ?: ""),
                        "name" to (rs.getString("name") ?: ""),
                        "phone" to (rs.getString("phone") ?: ""),
                    )
                },
                *chunk.toTypedArray(),
            )
        }
        return out
    }

    private fun changeOut(r: Map<String, Any?>): Map<String, Any?> = linkedMapOf(
        "id" to (r["id"] as Number).toLong(),
        "at" to ms(r["at"]),
        "key" to r["key"],
        "mode" to r["mode"],
        "prevMode" to r["prev_mode"],
        "demo" to r["demo"],
        "prevDemo" to r["prev_demo"],
        "pilotCount" to arr(r["pilot_uids"]).size,
        "prevPilotCount" to r["prev_pilot"]?.let { arr(it).size },
        "byEmail" to (r["by_email"] ?: ""),
    )

    fun adminGet(ctx: ActionContext): Any {
        requireAdmin(ctx)
        val s = features.load()
        val rows = jdbc.queryForList("SELECT key, updated_at, updated_by FROM feature_flags").associateBy { it["key"] as String }
        val last = jdbc.queryForList(
            "SELECT DISTINCT ON (key) * FROM feature_changes ORDER BY key, id DESC",
        ).associateBy { it["key"] as String }
        val who = people(s.flags.values.flatMap { it.pilotUids })
        val items = Features.KEYS.map { k ->
            val c = s.flags[k] ?: FeatureConfig()
            linkedMapOf(
                "key" to k,
                "mode" to c.mode,
                "pilotUids" to c.pilotUids,
                "pilots" to c.pilotUids.map { uid -> who[uid] ?: linkedMapOf("uid" to uid, "email" to "", "name" to "", "phone" to "", "missing" to true) },
                "demo" to c.demo,
                "needs" to Features.NEEDS[k].orEmpty(),
                "twoParty" to (k in Features.TWO_PARTY),
                "updatedAt" to ms(rows[k]?.get("updated_at")),
                "updatedBy" to (rows[k]?.get("updated_by") ?: ""),
                "last" to last[k]?.let { changeOut(it) },
            )
        }
        val history = jdbc.queryForList("SELECT * FROM feature_changes ORDER BY id DESC LIMIT $HISTORY").map { changeOut(it) }
        return linkedMapOf("items" to items, "history" to history, "v" to s.v, "allOn" to features.allOn)
    }

    fun adminSet(ctx: ActionContext): Any {
        requireAdmin(ctx)
        val b = ctx.body
        val key = b.string("key")
        if (key == null || key !in Features.KEYS) throw ctx.err(400, "Unknown feature", "অজানা ফিচার") // bn-ok
        val mode = b.string("mode")
        if (mode == null || mode !in Features.MODES) throw ctx.err(400, "Choose off, pilot or everyone", "বন্ধ, পাইলট বা সবার জন্য বেছে নিন") // bn-ok
        val pilotNode = b["pilotUids"]
        val pilotsIn: List<String>? = when {
            pilotNode == null || pilotNode.isNull -> null
            !pilotNode.isArray -> throw ctx.err(400, "Invalid pilot list", "পাইলট তালিকা ঠিক নেই") // bn-ok
            else -> pilotNode.map { n ->
                val id = if (n.isTextual) n.textValue().trim() else null
                if (!Rules.isValidId(id)) throw ctx.err(400, "Invalid pilot list", "পাইলট তালিকা ঠিক নেই") // bn-ok
                id!!
            }.distinct()
        }
        if (pilotsIn != null && pilotsIn.size > MAX_PILOTS) {
            throw ctx.err(400, "At most $MAX_PILOTS pilot users", "পাইলট সর্বোচ্চ $MAX_PILOTS জন") // bn-ok
        }
        val demoIn = b["demo"]?.let {
            if (it.isNull) null else if (it.isBoolean) it.booleanValue() else throw ctx.err(400, "Invalid demo value", "ডেমোর মান ঠিক নেই") // bn-ok
        }
        if (pilotsIn != null && pilotsIn.isNotEmpty()) {
            val known = HashSet(
                jdbc.queryForList(
                    "SELECT uid FROM accounts WHERE uid IN (${pilotsIn.joinToString(",") { "?" }})",
                    String::class.java, *pilotsIn.toTypedArray(),
                ),
            )
            val unknown = pilotsIn.filter { it !in known }
            if (unknown.isNotEmpty()) throw ctx.err(400, "No such user: ${unknown.first()}", "এমন ব্যবহারকারী নেই: ${unknown.first()}") // bn-ok
        }
        val by = ctx.caller
        var old = FeatureConfig()
        var new = FeatureConfig()
        var changed = false
        tx.executeWithoutResult {
            // one change at a time per key (the row lock); a key without a row is off
            jdbc.update("INSERT INTO feature_flags (key) VALUES (?) ON CONFLICT (key) DO NOTHING", key)
            val cur = jdbc.queryForList("SELECT mode, pilot_uids, demo FROM feature_flags WHERE key = ? FOR UPDATE", key).first()
            old = FeatureConfig(cur["mode"] as String, arr(cur["pilot_uids"]), cur["demo"] as Boolean)
            new = FeatureConfig(mode, pilotsIn ?: old.pilotUids, demoIn ?: old.demo)
            if (new == old) return@executeWithoutResult
            changed = true
            val pilotsArr = pgArray(new.pilotUids)
            val prevArr = pgArray(old.pilotUids)
            jdbc.update(
                "UPDATE feature_flags SET mode = ?, pilot_uids = ?::text[], demo = ?, updated_at = now(), updated_by = ? WHERE key = ?",
                new.mode, pilotsArr, new.demo, by.email.lowercase(), key,
            )
            jdbc.update(
                """
                INSERT INTO feature_changes (key, mode, pilot_uids, demo, prev_mode, prev_demo, prev_pilot, by_uid, by_email)
                VALUES (?, ?, ?::text[], ?, ?, ?, ?::text[], ?, ?)
                """.trimIndent(),
                key, new.mode, pilotsArr, new.demo, old.mode, old.demo, prevArr, by.uid, by.email.lowercase(),
            )
        }
        if (!changed) return linkedMapOf("ok" to true, "unchanged" to true, "v" to features.load().v)
        features.clearCache()
        val v = features.load().v
        push.changed(v, push.audience(old, new))
        return linkedMapOf("ok" to true, "v" to v, "key" to key, "mode" to new.mode, "pilotUids" to new.pilotUids, "demo" to new.demo)
    }
}
