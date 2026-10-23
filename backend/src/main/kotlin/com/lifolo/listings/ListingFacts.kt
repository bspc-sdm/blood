package com.lifolo.listings

import com.lifolo.admin.kernel.ListingRules
import com.lifolo.common.Body
import com.lifolo.common.Geo
import com.lifolo.common.Js
import com.lifolo.common.Places
import java.text.Normalizer

/**
 * The shared facts of the five service lists (ambulances, doctors, pharmacies, labs, blood banks) — backend/docs/LISTINGS.md.
 * Every list item gets the same additive keys, so one card template can show them:
 * `openNow` (true|false|null), `opensAt`/`closesAt` ("HH:MM"|null), `is24h`, `distanceKm` (1 decimal|null) +
 * `distanceApprox`, `ratingSummary` ({avg, count}|null). Requests may carry `sort` ("near"|"rating"|"open") and `q`.
 * Without `sort` every list keeps its old order.
 */
object ListingFacts {
    val SORTS = setOf("near", "rating", "open")
    const val Q_MAX = 60

    /** where the viewer is: a real point, or the centre of the chosen upazila ([exact] = false) */
    data class Origin(val lat: Double, val lng: Double, val exact: Boolean)

    private fun inBd(lat: Double, lng: Double) = lat in 20.3..26.8 && lng in 87.9..92.8

    /**
     * The request's point: `lat`/`lng` inside Bangladesh (exact unless `gps: false`, or — [gpsFlag] — unless `gps: true`,
     * the ambulance list's old rule), else the centre of `upazilaId`, else null.
     */
    fun origin(b: Body, gpsFlag: Boolean = false): Origin? {
        val lat = b.number("lat")
        val lng = b.number("lng")
        if (lat != null && lng != null && inBd(lat, lng)) {
            val exact = if (gpsFlag) b.isTrue("gps") else b.bool("gps") != false
            return Origin(lat, lng, exact)
        }
        val c = upazilaCentre(b.int("upazilaId")?.toInt()) ?: return null
        return Origin(c.first, c.second, false)
    }

    fun upazilaCentre(upazilaId: Int?): Pair<Double, Double>? = upazilaId?.takeIf { it != 0 }?.let { Places.UPAZILA_CENTER[it] }

    /** `Math.round(km * 10) / 10` */
    fun round1(km: Double): Double = Js.round(km * 10) / 10

    /**
     * `distanceKm` + `distanceApprox` onto [v]: from [o] to ([lat], [lng]) — [exact] = the listing's own point (false:
     * an area centre). No origin or no point → `distanceKm: null`.
     */
    fun putDistance(v: MutableMap<String, Any?>, o: Origin?, lat: Double?, lng: Double?, exact: Boolean) {
        if (o == null || lat == null || lng == null || !lat.isFinite() || !lng.isFinite()) {
            v["distanceKm"] = null
            v["distanceApprox"] = false
            return
        }
        v["distanceKm"] = round1(Geo.distanceKm(o.lat, o.lng, lat, lng))
        v["distanceApprox"] = !(o.exact && exact)
    }

    /** `ratingSummary`: {avg (1 decimal), count}, null while nobody rated */
    fun ratingSummary(sum: Int, count: Int): Map<String, Any?>? =
        if (count > 0) linkedMapOf("avg" to round1(sum.toDouble() / count), "count" to count) else null

    /** `is24h`, `opensAt`, `closesAt` of a listing with structured hours */
    fun putHours(v: MutableMap<String, Any?>, open24: Boolean, openAt: String?, closeAt: String?) {
        v["is24h"] = open24
        v["opensAt"] = if (open24) null else openAt?.takeIf { TIME_RE.matches(it) }
        v["closesAt"] = if (open24) null else closeAt?.takeIf { TIME_RE.matches(it) }
    }

    /** a list that has no opening hours (ambulances: use `available`) */
    fun putNoHours(v: MutableMap<String, Any?>) {
        v["openNow"] = null
        v["is24h"] = false
        v["opensAt"] = null
        v["closesAt"] = null
    }

    // ------------------------------------------------------------------ search

    private val SPACES = Regex("\\s+")

    /** NFC, lower case, single spaces */
    fun norm(s: String?): String = SPACES.replace(Normalizer.normalize(s ?: "", Normalizer.Form.NFC).lowercase(), " ").trim()

    /** the request's `q` (normalised, ≤ 60 characters), null when absent/blank */
    fun query(b: Body): String? = b.string("q")?.take(Q_MAX)?.let { norm(it) }?.takeIf { it.isNotEmpty() }

    /** every word of [q] occurs in one of [texts] */
    fun matches(q: String?, texts: Collection<String?>): Boolean {
        if (q == null) return true
        val hay = texts.joinToString(" ") { norm(it) }
        return q.split(' ').all { hay.contains(it) }
    }

    // ------------------------------------------------------------------ sort

    /** the request's `sort` (unknown values = none) */
    fun sort(b: Body): String? = b.string("sort")?.takeIf { it in SORTS }

    @Suppress("UNCHECKED_CAST")
    private fun avg(v: Map<String, Any?>): Double = ((v["ratingSummary"] as? Map<String, Any?>)?.get("avg") as? Number)?.toDouble() ?: -1.0

    @Suppress("UNCHECKED_CAST")
    private fun count(v: Map<String, Any?>): Int = ((v["ratingSummary"] as? Map<String, Any?>)?.get("count") as? Number)?.toInt() ?: 0

    /** open first (ambulances: available), then unknown, then closed */
    private fun openRank(v: Map<String, Any?>): Int = when {
        v["openNow"] == true || v["available"] == true -> 0
        v["openNow"] == null && v["available"] != false -> 1
        else -> 2
    }

    @Suppress("UNCHECKED_CAST")
    private fun slotDate(v: Map<String, Any?>): Long {
        val s = v["nextSlot"] as? Map<String, Any?> ?: return Long.MAX_VALUE
        return ((s["date"] as? Number)?.toLong() ?: Long.MAX_VALUE) + if (s["available"] == true) 0 else 1
    }

    private fun km(v: Map<String, Any?>): Double = (v["distanceKm"] as? Number)?.toDouble() ?: Double.POSITIVE_INFINITY

    /**
     * Stable sort of [items] by [sort] (ties keep the list's own order): near = distance (unknown last), rating = average
     * then count (unrated last), open = open now / available first, then the earliest free serial (doctors), then near.
     */
    fun <T> sorted(items: List<T>, sort: String?, view: (T) -> Map<String, Any?>): List<T> = when (sort) {
        "near" -> items.sortedWith(compareBy { km(view(it)) })
        "rating" -> items.sortedWith(compareByDescending<T> { avg(view(it)) }.thenByDescending { count(view(it)) })
        "open" -> items.sortedWith(compareBy<T> { openRank(view(it)) }.thenBy { slotDate(view(it)) }.thenBy { km(view(it)) })
        else -> items
    }

    fun sortedMaps(items: List<Map<String, Any?>>, sort: String?): List<Map<String, Any?>> = sorted(items, sort) { it }

    // ------------------------------------------------------------------ free-text hours (labs)

    private val TIME_RE = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
    private const val BN_DIGITS = "০১২৩৪৫৬৭৮৯"

    /** parsed opening hours: open24, or "HH:MM" open/close */
    data class Hours(val open24: Boolean, val openAt: String, val closeAt: String) {
        fun asMap(): Map<String, Any?> = mapOf("open24" to open24, "openAt" to openAt, "closeAt" to closeAt)
    }

    private val H24 = Regex("(24\\s*(ঘণ্টা|ঘন্টা|hours?|hrs?|h\\b)|24\\s*/\\s*7)", RegexOption.IGNORE_CASE) // bn-ok
    private val CLOCK = Regex("(?<![\\d:.])([01]?\\d|2[0-3])[:.]([0-5]\\d)(?!\\s*(টা|am|pm|a\\.m|p\\.m))", RegexOption.IGNORE_CASE) // bn-ok
    private val AMPM = Regex("(?<![\\d:.])(1[0-2]|0?[1-9])(?:[:.]([0-5]\\d))?\\s*(am|pm|a\\.m\\.?|p\\.m\\.?)", RegexOption.IGNORE_CASE)
    private val BANGLA = Regex("(ভোর|সকাল|দুপুর|বিকাল|বিকেল|সন্ধ্যা|রাত)\\s*(1[0-2]|0?[1-9])(?:[:.]([0-5]\\d))?") // bn-ok

    private fun hhmm(h: Int, m: Int) = "%02d:%02d".format(h % 24, m)

    private fun banglaHour(period: String, h: Int): Int = when (period) {
        "ভোর", "সকাল" -> h // bn-ok
        "দুপুর" -> if (h == 12 || h == 11) h else h + 12 // bn-ok
        "বিকাল", "বিকেল", "সন্ধ্যা" -> if (h == 12) 12 else h + 12 // bn-ok
        else -> when { // রাত
            h == 12 -> 0
            h >= 6 -> h + 12
            else -> h
        }
    }

    /**
     * The lab's free-text hours ("সকাল ৮টা – রাত ১০টা", "08:00-22:00", "8am–10pm", "২৪ ঘণ্টা") as structured hours: the
     * first two times in the text. Null when it cannot tell (then the lab's openNow is null, never a guess).
     */
    fun parseHours(text: String?): Hours? {
        if (text.isNullOrBlank()) return null
        val t = text.map { c -> BN_DIGITS.indexOf(c).let { if (it >= 0) '0' + it else c } }.joinToString("")
        if (H24.containsMatchIn(t)) return Hours(true, "", "")
        val found = ArrayList<Pair<Int, String>>()
        BANGLA.findAll(t).forEach { m ->
            val h = m.groupValues[2].toInt()
            found.add(m.range.first to hhmm(banglaHour(m.groupValues[1], h), m.groupValues[3].ifEmpty { "0" }.toInt()))
        }
        AMPM.findAll(t).forEach { m ->
            val h = m.groupValues[1].toInt() % 12 + if (m.groupValues[3].lowercase().startsWith("p")) 12 else 0
            found.add(m.range.first to hhmm(h, m.groupValues[2].ifEmpty { "0" }.toInt()))
        }
        // a plain "HH:MM" that is part of a Bangla/am-pm time found above ("সকাল ৯:৩০") is not a second time
        val worded = found.map { it.first }
        CLOCK.findAll(t).forEach { m ->
            if (worded.none { m.range.first in it..(it + 12) }) found.add(m.range.first to hhmm(m.groupValues[1].toInt(), m.groupValues[2].toInt()))
        }
        val two = found.sortedBy { it.first }.distinctBy { it.first }.take(2)
        if (two.size < 2 || two[0].second == two[1].second) return null
        return Hours(false, two[0].second, two[1].second)
    }

    /** a lab's `openNow`/`is24h`/`opensAt`/`closesAt` from its free-text hours (null facts when not understood) */
    fun putTextHours(v: MutableMap<String, Any?>, text: String?, now: Long) {
        val h = parseHours(text)
        if (h == null) {
            putNoHours(v)
            return
        }
        v["openNow"] = ListingRules.openNow(h.asMap(), now)
        putHours(v, h.open24, h.openAt, h.closeAt)
    }
}
