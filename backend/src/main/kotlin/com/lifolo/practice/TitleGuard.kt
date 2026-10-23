package com.lifolo.practice

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import com.lifolo.common.ApiException
import com.lifolo.common.Lang
import com.lifolo.demo.DemoWorld
import com.lifolo.features.Features
import com.lifolo.notify.SseHub
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.stereotype.Service

/**
 * The "ডা." / "Dr." title rule (backend/docs/PRACTICE.md §6), behind the feature switch `title_guard` ("‘ডা.’ পদবির
 * নিয়ম"). Off: nothing changes. On — for whom:
 *  - **saving** a name (profile_update, badge_apply, a new listing): the caller. A non-eligible provider (anything but
 *    an allopathic doctor) whose name starts with a title → 400 `title_not_allowed`.
 *  - **showing** names and profession labels: the viewer — the caller of an action, the recipient of a push, an email
 *    or an SSE event. Public pages and guests (no uid) only when the switch is "on" for everyone.
 *
 * Existing names are never changed in the database: they are stripped where they are shown ([apply], [text]) — every
 * `/api/community` answer (ActionDispatcher), every push and bell entry (PushService), SSE events (SseHub), account
 * emails (AccountMail), the public prescription check (/rx/{code}) and the call/chat first names. Switching it off
 * shows the stored names again.
 *
 * The names to strip ([names]) are every stored spelling of a non-eligible provider's name that starts with a title —
 * the listing, the badge, the profile (chat and calls) and the copies kept on appointments, prescriptions, lab orders
 * and chamber receipts, health answers and tips. A name that an eligible doctor also has is never stripped. Kept
 * [ttlMs] in memory; a name change clears it ([clearCache]).
 */
@Service
class TitleGuard(
    private val jdbc: JdbcTemplate,
    private val features: Features,
    private val mapper: ObjectMapper,
    private val sse: SseHub,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var cached: Pair<Long, Map<String, String>>? = null

    /** how long [names] is kept (tests: 0 — every answer reads the names again) */
    @Volatile
    var ttlMs: Long = TTL_MS

    @PostConstruct
    fun register() {
        // SSE events go to "user:<uid>" — that uid is the viewer
        sse.filter = { topic, data -> if (topic.startsWith("user:")) apply(data, topic.removePrefix("user:")) ?: data else data }
    }

    /** is the rule on for [uid] (null = a guest / public page: only when on for everyone)? */
    fun on(uid: String?): Boolean = features.isOn(uid, Features.TITLE_GUARD)

    // ------------------------------------------------------------------ saving a name

    /** the profession of [uid]: the badge (type, system) when there is one, else the listing; null = not a provider */
    fun professionOf(uid: String): Pair<String, String?>? {
        val b = jdbc.queryForList("SELECT type, system FROM badges WHERE uid = ?", uid).firstOrNull()
        if (b != null && b["type"] != null) return (b["type"] as String) to (b["system"] as String?)
        val p = jdbc.queryForList("SELECT type, system FROM providers WHERE uid = ?", uid).firstOrNull() ?: return null
        return (p["type"] as String) to (p["system"] as String?)
    }

    /**
     * 400 `title_not_allowed` "MBBS/BDS ছাড়া নামের আগে ‘ডা.’ বা ‘Dr.’ লেখা যাবে না" when the rule is on for [caller],
     * the profession ([type], [system]) may not carry the title and [name] starts with one.
     */
    fun requireName(caller: String?, name: String?, type: String?, system: String?, lang: String?) {
        if (type == null || TitleRules.eligible(type, system) || !TitleRules.hasTitle(name)) return
        if (!on(caller)) return
        throw error(lang)
    }

    /** the same for a provider's own name change (profile_update): the profession is looked up */
    fun requireOwnName(caller: String?, uid: String, name: String?, lang: String?) {
        if (!TitleRules.hasTitle(name)) return
        val (type, system) = professionOf(uid) ?: return
        requireName(caller, name, type, system, lang)
    }

    // ------------------------------------------------------------------ showing names

    /** strip the titles in an action's answer for [viewer]; the value unchanged when the rule is off or nothing to strip */
    fun apply(value: Any?, viewer: String?): Any? {
        if (value == null || !on(viewer)) return value
        val names = names()
        if (names.isEmpty()) return value
        return try {
            val tree: JsonNode = if (value is JsonNode) value.deepCopy() else mapper.valueToTree(value)
            rewrite(tree, names)
        } catch (e: Exception) {
            log.warn("title strip failed: {}", e.message)
            value
        }
    }

    /** a notification/email text for [viewer]: every stored titled name in it stripped */
    fun text(s: String, viewer: String?): String {
        if (s.isEmpty() || !maybeTitled(s) || !on(viewer)) return s
        return replaceIn(s, names())
    }

    /** a person's name shown to [viewer] (call screens, LiveKit) */
    fun name(name: String, viewer: String?): String {
        if (!maybeTitled(name) || !on(viewer)) return name
        return names()[name] ?: name
    }

    private fun rewrite(node: JsonNode, names: Map<String, String>): JsonNode {
        when (node) {
            is ObjectNode -> node.fields().forEach { e ->
                val v = e.value
                if (v.isTextual) {
                    val s = v.textValue()
                    val r = replaceField(e.key, s, names)
                    if (r !== s) e.setValue(TextNode(r))
                } else if (v.isContainerNode) {
                    rewrite(v, names)
                }
            }
            is ArrayNode -> for (i in 0 until node.size()) {
                val v = node[i]
                if (v.isTextual) {
                    val s = v.textValue()
                    val r = replaceField(null, s, names)
                    if (r !== s) node.set(i, TextNode(r))
                } else if (v.isContainerNode) {
                    rewrite(v, names)
                }
            }
        }
        return node
    }

    /**
     * A field that is exactly a stored name gets the stripped one. Notification texts (`title`, `body` of bell entries)
     * also have the name inside ("ডা. করিম · ১২ নভেম্বর") — there the name is replaced where it stands as a whole name.
     */
    private fun replaceField(key: String?, s: String, names: Map<String, String>): String {
        if (!maybeTitled(s)) return s
        names[s]?.let { return it }
        if (key == "title" || key == "body") return replaceIn(s, names)
        return s
    }

    private fun replaceIn(s: String, names: Map<String, String>): String {
        names[s]?.let { return it }
        var out = s
        for ((from, to) in names) {
            var i = out.indexOf(from)
            while (i >= 0) {
                val end = i + from.length
                // a whole name: not part of a longer name ("ডা. করিম" inside "ডা. করিম আহমেদ")
                val before = i == 0 || !out[i - 1].isLetterOrDigit()
                val after = end >= out.length || NAME_END.contains(out[end])
                if (before && after) {
                    out = out.substring(0, i) + to + out.substring(end)
                    i = out.indexOf(from, i + to.length)
                } else {
                    i = out.indexOf(from, i + 1)
                }
            }
        }
        return out
    }

    /** stored name → name without the title, longest first */
    fun names(): Map<String, String> {
        val now = System.currentTimeMillis()
        cached?.let { (at, m) -> if (now - at in 0 until ttlMs) return m }
        val m = try {
            load()
        } catch (e: Exception) {
            log.warn("title names load failed: {}", e.message)
            emptyMap()
        }
        cached = now to m
        return m
    }

    fun clearCache() {
        cached = null
    }

    private fun load(): Map<String, String> {
        val strip = HashMap<String, String>()
        val keep = HashSet<String>()
        jdbc.query(
            NAMES_SQL,
            RowCallbackHandler { rs ->
                val name = rs.getString("name")
                if (name != null && TitleRules.hasTitle(name)) {
                    if (TitleRules.eligible(rs.getString("type"), rs.getString("system"))) keep.add(name) else strip[name] = TitleRules.strip(name)
                }
            },
        )
        keep.forEach { strip.remove(it) }
        return strip.entries.sortedByDescending { it.key.length }.associateTo(LinkedHashMap()) { it.key to it.value }
    }

    // ------------------------------------------------------------------ admin list

    /**
     * Providers whose current name (listing, badge, a pending badge correction or profile) starts with a title they
     * may not carry — whether the switch is on or not, so the owner can ask them to fix it first.
     */
    fun affected(): List<Map<String, Any?>> {
        val rows = jdbc.queryForList(AFFECTED_SQL)
        val out = LinkedHashMap<String, LinkedHashMap<String, Any?>>()
        for (r in rows) {
            val uid = r["uid"] as String
            val type = r["type"] as String?
            val system = r["system"] as String?
            if (TitleRules.eligible(type, system)) continue
            val names = linkedMapOf(
                "listing" to r["listing_name"] as String?,
                "badge" to r["badge_name"] as String?,
                "correction" to r["change_name"] as String?,
                "profile" to r["profile_name"] as String?,
            ).filterValues { TitleRules.hasTitle(it) }
            if (names.isEmpty()) continue
            val shown = names["listing"] ?: names["badge"] ?: names["correction"] ?: names["profile"]!!
            out[uid] = linkedMapOf(
                "uid" to uid,
                "name" to shown,
                "stripped" to TitleRules.strip(shown),
                "where" to names.keys.toList(),
                "names" to names,
                "type" to type,
                "system" to Practice.systemOf(type, system),
                "typeLabel" to TitleRules.typeLabel(type, system, true),
                "email" to r["email"],
                "phone" to r["phone"],
                "listed" to (r["listed"] == true),
                "badgeStatus" to r["badge_status"],
                "demo" to DemoWorld.isDemoUid(uid),
            )
        }
        return out.values.toList()
    }

    companion object {
        const val TTL_MS = 30_000L

        /**
         * Answers shown as stored: the admin list itself, and one's own profile (the edit form shows the stored name, so
         * the provider sees the title and removes it — the server never changes it for them).
         */
        val RAW_ACTIONS: Set<String> = setOf("admin_title_names", "profile_get")

        /** text that could hold a title at all (a cheap check before the map lookups) */
        fun maybeTitled(s: String): Boolean =
            s.contains("ডা") || s.contains("dr", ignoreCase = true) || s.contains("doctor", ignoreCase = true) // bn-ok

        /** what may follow a whole name in a notification text */
        private const val NAME_END = " \t\n·,.:;!?()-–—/|'\"’”।"

        fun error(lang: String?): ApiException = ApiException(400, Lang.tr(lang, TitleRules.ERROR_EN, TitleRules.ERROR_BN), TitleRules.CODE)

        /** a loose Postgres filter for names that might start with a title (TitleRules.TITLE decides) */
        private const val LOOKS = "~* '^\\s*(ডা|dr|doctor)'" // bn-ok

        /** the profession of every provider (badge first, else the listing) and every stored spelling of their name */
        private val NAMES_SQL = """
            WITH pro AS (
              SELECT coalesce(b.uid, p.uid) AS uid, coalesce(b.type, p.type) AS type,
                     CASE WHEN b.uid IS NOT NULL THEN b.system ELSE p.system END AS system
              FROM providers p FULL JOIN badges b ON b.uid = p.uid
            )
            SELECT pro.type, pro.system, n.name FROM pro JOIN (
              SELECT uid, name FROM providers WHERE name $LOOKS
              UNION SELECT uid, name FROM badges WHERE name $LOOKS
              UNION SELECT uid, change_name FROM badges WHERE change_name $LOOKS
              UNION SELECT uid, name FROM user_profiles WHERE name $LOOKS
              UNION SELECT provider_uid, provider_name FROM appointments WHERE provider_name $LOOKS
              UNION SELECT doctor_uid, doctor_name FROM prescriptions WHERE doctor_name $LOOKS
              UNION SELECT doctor_uid, doctor_name FROM lab_orders WHERE doctor_name $LOOKS
              UNION SELECT doctor_uid, doctor_name FROM rx_reviews WHERE doctor_name $LOOKS
              UNION SELECT biz_id, biz_name FROM invoices WHERE kind = 'chamber' AND biz_name $LOOKS
              UNION SELECT uid, author_name FROM answers WHERE author_name $LOOKS
              UNION SELECT uid, author_name FROM tips WHERE author_name $LOOKS
            ) n ON n.uid = pro.uid
        """.trimIndent()

        private val AFFECTED_SQL = """
            SELECT coalesce(b.uid, p.uid) AS uid, coalesce(b.type, p.type) AS type,
                   CASE WHEN b.uid IS NOT NULL THEN b.system ELSE p.system END AS system,
                   p.name AS listing_name, b.name AS badge_name, b.change_name, up.name AS profile_name,
                   a.email, up.phone, p.listed, b.status AS badge_status
            FROM providers p FULL JOIN badges b ON b.uid = p.uid
            LEFT JOIN user_profiles up ON up.uid = coalesce(b.uid, p.uid)
            LEFT JOIN accounts a ON a.uid = coalesce(b.uid, p.uid)
            WHERE p.name $LOOKS OR b.name $LOOKS OR b.change_name $LOOKS OR up.name $LOOKS
            ORDER BY coalesce(b.uid, p.uid)
        """.trimIndent()

        // ---- the profession label (Practice.labels) follows the viewer of the answer being built ----
        private val RENDER = ThreadLocal<Boolean>()

        /** is the rule on for the viewer of the answer being built on this thread? (false outside [rendering]) */
        fun renderOn(): Boolean = RENDER.get() == true

        fun <T> rendering(on: Boolean, block: () -> T): T {
            val before = RENDER.get()
            RENDER.set(on)
            try {
                return block()
            } finally {
                if (before == null) RENDER.remove() else RENDER.set(before)
            }
        }
    }
}
