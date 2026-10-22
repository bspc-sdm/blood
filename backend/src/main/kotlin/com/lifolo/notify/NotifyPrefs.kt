package com.lifolo.notify

import com.lifolo.common.Sql
import com.lifolo.common.action.ActionContext
import com.lifolo.common.action.ActionHandler
import com.lifolo.common.action.ActionModule
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** One person's notification settings: the master switch and each settable category (true = push on) */
data class NotifyPrefs(val all: Boolean, val cats: Map<String, Boolean>) {
    /** whether a push of [cat] reaches the phone ([NotifyCats.ACCOUNT] always does) */
    fun allows(cat: String): Boolean = cat == NotifyCats.ACCOUNT || (all && cats[cat] != false)

    fun toMap(): Map<String, Any?> = linkedMapOf("ok" to true, "all" to all, "cats" to LinkedHashMap(cats))

    companion object {
        val DEFAULT = NotifyPrefs(true, NotifyCats.SETTABLE.associateWith { true })
    }
}

/**
 * notify_prefs (V607) + user_profiles.notify (= the `blood_nearby` category). No notify_prefs row = everything on.
 */
@Service
class NotifyPrefsService(private val jdbc: JdbcTemplate) {
    /** the columns of notify_prefs, in [NotifyCats.SETTABLE] order (blood_nearby lives in user_profiles.notify) */
    private val columns = NotifyCats.SETTABLE.filter { it != NotifyCats.BLOOD_NEARBY }

    fun get(uid: String): NotifyPrefs = of(listOf(uid))[uid] ?: NotifyPrefs.DEFAULT

    /** settings of many people at once (the push sender); people without a profile or a row get the defaults */
    fun of(uids: Collection<String>): Map<String, NotifyPrefs> {
        val ids = uids.distinct()
        val out = HashMap<String, NotifyPrefs>()
        for (chunk in ids.chunked(500)) {
            val ph = chunk.joinToString(",") { "?" }
            val nearby = HashMap<String, Boolean>()
            jdbc.query(
                "SELECT uid, notify FROM user_profiles WHERE uid IN ($ph)",
                RowCallbackHandler { rs -> nearby[rs.getString(1)] = rs.getBoolean(2) },
                *chunk.toTypedArray(),
            )
            val rows = HashMap<String, Pair<Boolean, Map<String, Boolean>>>()
            jdbc.query(
                "SELECT uid, all_on, ${columns.joinToString(", ")} FROM notify_prefs WHERE uid IN ($ph)",
                RowCallbackHandler { rs -> rows[rs.getString("uid")] = rs.getBoolean("all_on") to columns.associateWith { rs.getBoolean(it) } },
                *chunk.toTypedArray(),
            )
            for (uid in chunk) {
                val row = rows[uid]
                val cats = LinkedHashMap<String, Boolean>()
                for (c in NotifyCats.SETTABLE) {
                    cats[c] = if (c == NotifyCats.BLOOD_NEARBY) nearby[uid] ?: true else row?.second?.get(c) ?: true
                }
                out[uid] = NotifyPrefs(row?.first ?: true, cats)
            }
        }
        return out
    }

    /**
     * Merge: only the given values change. `blood_nearby` is the profile's `notify` (needs a profile — without one it
     * stays on, like a new profile).
     */
    @Transactional
    fun set(uid: String, all: Boolean?, cats: Map<String, Boolean>): NotifyPrefs {
        val cols = cats.filterKeys { it in columns }
        if (all != null || cols.isNotEmpty()) {
            val sets = (listOfNotNull(if (all != null) "all_on" else null) + cols.keys)
            val values: List<Any?> = listOfNotNull(all) + cols.values
            jdbc.update(
                """
                INSERT INTO notify_prefs (uid, ${sets.joinToString(", ")}, updated_at) VALUES (?, ${sets.joinToString(", ") { "?" }}, ?)
                ON CONFLICT (uid) DO UPDATE SET ${sets.joinToString(", ") { "$it = EXCLUDED.$it" }}, updated_at = EXCLUDED.updated_at
                """.trimIndent(),
                *(listOf<Any?>(uid) + values + Sql.ts(System.currentTimeMillis())).toTypedArray(),
            )
        }
        cats[NotifyCats.BLOOD_NEARBY]?.let { on ->
            jdbc.update("UPDATE user_profiles SET notify = ? WHERE uid = ?", on, uid)
        }
        return get(uid)
    }
}

/**
 * `notify_prefs_get` → `{ok, all, cats: {blood_nearby, blood_emergency, my_request, appointments, rx, ambulance,
 * community, business}}`; `notify_prefs_set {all?, cats?: {<id>: Boolean}}` merges and answers the same.
 * Logged in only, own settings only (a caregiver acting for a patient is refused — not in CARE_ACTIONS, checked again here).
 */
@Component
class NotifyPrefsActions(private val prefs: NotifyPrefsService) : ActionModule {
    override fun actions(): Map<String, ActionHandler> = mapOf(
        "notify_prefs_get" to ActionHandler { ctx -> prefs.get(own(ctx)).toMap() },
        "notify_prefs_set" to ActionHandler { ctx -> set(ctx) },
    )

    private fun own(ctx: ActionContext): String {
        if (ctx.user.actingBy != null || ctx.user.id != ctx.caller.id) {
            throw ctx.err(403, "A caregiver can't do this for the patient", "কেয়ারগিভার রোগীর হয়ে এটা করতে পারেন না")
        }
        return ctx.user.id
    }

    private fun set(ctx: ActionContext): Any {
        val uid = own(ctx)
        val b = ctx.body
        val all = if (b.isNullish("all")) null else b.bool("all") ?: throw bad(ctx)
        val cats = LinkedHashMap<String, Boolean>()
        if (!b.isNullish("cats")) {
            val o = b.obj("cats") ?: throw bad(ctx)
            for ((k, v) in o.fields().asSequence()) {
                if (k !in NotifyCats.SETTABLE || !v.isBoolean) throw bad(ctx)
                cats[k] = v.booleanValue()
            }
        }
        return prefs.set(uid, all, cats).toMap()
    }

    private fun bad(ctx: ActionContext) = ctx.err(400, "Invalid settings", "সেটিংস ঠিক নেই") // bn-ok
}
