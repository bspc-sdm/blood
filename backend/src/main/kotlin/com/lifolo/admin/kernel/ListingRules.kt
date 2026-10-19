package com.lifolo.admin.kernel

import com.fasterxml.jackson.databind.ObjectMapper
import com.lifolo.common.Phones

/**
 * Pure listing rules shared by every package that owns a public listing (ambulance, pharmacy, blood bank, lab):
 * listingreview.js `snapshot`/`changedFields` and care.js `str`, `pickCodes`, `pickClosedDays`, `dhakaHHMM`,
 * `pharmacyOpenNow`, `smallNameEdit`, `reviewNeeded`, `keepApproved`.
 *
 * Listings are passed as **Node-shaped maps** (camelCase Firestore field names, times as epoch-ms numbers) —
 * [ListingRows.load] builds one from a table row.
 */
object ListingRules {
    private val mapper = ObjectMapper()

    /** listingreview.js META: state, counters and times — not "listing information" (never in snapshot / changedFields) */
    val META: Set<String> = setOf(
        "status", "ownerUid", "createdAt", "updatedAt", "reviewedAt", "reviewedBy", "suspendedAt", "approvedName", "lastApproved",
        "statusReason", "ratingSum", "ratingCount", "available", "availableAt", "busyAt", "reminderSentAt", "remindAt", "closedUntil", "editRejectedAt",
        "findDay", "findCount", "findOff", "editedByAdmin", "addedByAdmin", "editRejectedReason",
        "verification", "docsRequestedAt", "docsRequestNote", "docsSentAt", "docsHave",
        "aspects", "online", "demo",
        "lat", "lng",
        "fareChart", "overchargeCount",
    )

    /** listingreview.js snapshot: the listing without [META] fields (what an admin approved) */
    fun snapshot(x: Map<String, Any?>?): LinkedHashMap<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        x?.forEach { (k, v) -> if (k !in META) out[k] = v }
        return out
    }

    data class Change(val field: String, val before: Any?, val after: Any?)

    /** listingreview.js changedFields: differences from `lastApproved` (panel "before: …"); JSON.stringify comparison */
    fun changedFields(x: Map<String, Any?>?): List<Change> {
        @Suppress("UNCHECKED_CAST")
        val before = x?.get("lastApproved") as? Map<String, Any?> ?: return emptyList()
        val now = snapshot(x)
        val keys = LinkedHashSet<String>().apply {
            addAll(before.keys)
            addAll(now.keys)
        }
        return keys.filter { it !in META }
            .filter { json(before[it]) != json(now[it]) }
            .map { Change(it, before[it], now[it]) }
    }

    /** JSON.stringify(v ?? null) with JS number formatting (1.0 → "1") */
    fun json(v: Any?): String = mapper.writeValueAsString(jsNumbers(v))

    private fun jsNumbers(v: Any?): Any? = when (v) {
        is Double -> if (v.isFinite() && v == Math.rint(v) && Math.abs(v) < 1e15) v.toLong() else v
        is Float -> jsNumbers(v.toDouble())
        is Number -> v.toLong()
        is Map<*, *> -> LinkedHashMap<Any?, Any?>().apply { v.forEach { (k, x) -> put(k, jsNumbers(x)) } }
        is Collection<*> -> v.map { jsNumbers(it) }
        is Array<*> -> v.map { jsNumbers(it) }
        else -> v
    }

    // ---------------------------------------------------------------- care.js helpers

    private val JS_SPACE = "\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"
    private val TRIM_RE = Regex("^[$JS_SPACE]+|[$JS_SPACE]+$")
    private val SPACES_RE = Regex("[$JS_SPACE]+")

    /** String.prototype.trim (Unicode white space, like JS) */
    fun jsTrim(s: String): String = TRIM_RE.replace(s, "")

    /** care.js str: trimmed string cut to [max] UTF-16 units, '' for non-strings */
    fun str(v: Any?, max: Int): String = if (v is String) jsTrim(v).take(max) else ""

    /** care.js pickCodes: only allowed, unique codes (first occurrence order), at most [max] */
    fun pickCodes(v: List<Any?>?, allowed: Collection<String>, max: Int): List<String> {
        if (v == null) return emptyList()
        return v.filterIsInstance<String>().filter { it in allowed }.distinct().take(max)
    }

    /** care.js pickClosedDays: weekdays 0–6 (Sun–Sat, Dhaka), sorted, unique, at most 6 (open at least one day) */
    fun pickClosedDays(v: List<Any?>?): List<Int> {
        if (v == null) return emptyList()
        val days = v.mapNotNull { d ->
            when (d) {
                is Int -> d
                is Long -> d.toInt().takeIf { it.toLong() == d }
                is Double -> d.toInt().takeIf { it.toDouble() == d }
                else -> null
            }
        }.filter { it in 0..6 }.distinct().sorted()
        return if (days.size >= 7) days.take(6) else days
    }

    private val TIME_RE = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
    private const val SIX_HOURS = 6 * 3600 * 1000L
    private const val DAY = 24 * 3600 * 1000L

    /** care.js dhakaHHMM: Bangladesh time "HH:MM" */
    fun dhakaHHMM(now: Long = System.currentTimeMillis()): String {
        val minutes = Math.floorMod(now + SIX_HOURS, DAY) / 60_000
        return "%02d:%02d".format(minutes / 60, minutes % 60)
    }

    /** weekday 0 = Sunday, in Dhaka time */
    fun dhakaWeekday(ms: Long): Int = Math.floorMod(Math.floorDiv(ms + SIX_HOURS, DAY) + 4, 7L).toInt()

    /**
     * care.js pharmacyOpenNow — any listing with opening hours (pharmacies, blood banks): paused (`closedUntil` in the
     * future) → closed; weekly `closedDays`; `open24`; `openAt`–`closeAt` also across midnight (the part after
     * midnight belongs to the previous day's shift). Missing/invalid times count as open.
     */
    fun openNow(p: Map<String, Any?>, now: Long = System.currentTimeMillis()): Boolean {
        val closedUntil = (p["closedUntil"] as? Number)?.toLong() ?: 0
        if (closedUntil > now) return false
        val off = (p["closedDays"] as? Collection<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList()
        if (p["open24"] == true) return dhakaWeekday(now) !in off
        val o = p["openAt"] as? String ?: ""
        val c = p["closeAt"] as? String ?: ""
        if (!TIME_RE.matches(o) || !TIME_RE.matches(c)) return dhakaWeekday(now) !in off
        val t = dhakaHHMM(now)
        if (o < c) return t >= o && t < c && dhakaWeekday(now) !in off
        if (t >= o) return dhakaWeekday(now) !in off
        if (t < c) return dhakaWeekday(now - DAY) !in off
        return false
    }

    private val BENGALI = Regex("[\\u0980-\\u09FF]")

    /**
     * care.js smallNameEdit: a spelling fix of the name (case/space only, or Levenshtein ≤ 2; ≤ 4 when Bangla is
     * involved, because one Bangla fix changes several code units) — such edits skip re-review.
     */
    fun smallNameEdit(base: String?, next: String?): Boolean {
        if (base.isNullOrEmpty() || next.isNullOrEmpty()) return false
        fun norm(v: String) = jsTrim(SPACES_RE.replace(v.lowercase(), " "))
        val a = norm(base)
        val b = norm(next)
        if (a == b) return true
        val max = if (BENGALI.containsMatchIn(a + b)) 4 else 2
        if (Math.abs(a.length - b.length) > max) return false
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length] <= max
    }

    data class Review(val changed: Boolean, val approvedName: String?)

    /**
     * care.js reviewNeeded: does an edit of an approved listing need a new review? — the name changed beyond a
     * spelling fix of the last approved name, or one of [keys] changed (`phone` compared after fixPhone), or the
     * listing was rejected. [approvedName] is the name to keep as "last approved".
     * [current] = the stored listing, [data] = the new values (Node-shaped maps).
     */
    fun reviewNeeded(current: Map<String, Any?>, data: Map<String, Any?>, keys: List<String>): Review {
        val status = current["status"]
        val base = (current["approvedName"] as? String)?.takeIf { it.isNotEmpty() }
            ?: if (status == "approved") current["name"] as? String else null
        val newName = data["name"] as? String
        val nameChanged = ((current["name"] as? String) ?: "") != newName && !smallNameEdit(base, newName)
        val other = keys.any { k ->
            val cur: Any? = if (k == "phone") Phones.fixPhone(current[k] as? String) else current[k].takeIf { jsTruthy(it) } ?: ""
            !strictEquals(cur, data[k])
        }
        return Review(nameChanged || other || status == "rejected", base?.takeIf { it.isNotEmpty() })
    }

    /**
     * care.js keepApproved: the `lastApproved` to write with an edit of an **approved** listing, or null = leave it.
     * Not changed (no review) → the new data is the approved state; going to review → keep the old snapshot (set it
     * from the current listing if an older version never stored one).
     */
    fun keepApproved(current: Map<String, Any?>, data: Map<String, Any?>, changed: Boolean): Map<String, Any?>? {
        if (current["status"] != "approved") return null
        if (!changed) return snapshot(current + data)
        return if (current["lastApproved"] != null) null else snapshot(current)
    }

    /** JS truthiness of a plain value */
    fun jsTruthy(v: Any?): Boolean = when (v) {
        null -> false
        is Boolean -> v
        is String -> v.isNotEmpty()
        is Number -> v.toDouble().let { it != 0.0 && !it.isNaN() }
        else -> true
    }

    /** JS `===` for plain values (numbers compared by value whatever their Kotlin type) */
    fun strictEquals(a: Any?, b: Any?): Boolean =
        if (a is Number && b is Number) a.toDouble() == b.toDouble() else a == b
}
