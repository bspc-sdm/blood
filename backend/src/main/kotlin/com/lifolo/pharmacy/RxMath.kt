package com.lifolo.pharmacy

import com.lifolo.common.DhakaTime
import com.lifolo.common.Js
import com.lifolo.contracts.Prescription
import com.lifolo.contracts.RxDispensing
import com.lifolo.contracts.RxMedicine
import java.security.MessageDigest

/**
 * Pure medicine arithmetic of rxnet.js (perDay, countable, needQty, medStatus, pharmacyEstimate, wantedOf,
 * stockMatch, refillDate) and the order helpers (pharmSeg, segCounts, recentFirstOpen, onlineOf, closeMarks).
 * Prescriptions come from the P3 contract ([Prescription]); "i" of a medicine is its position in the list.
 */
object RxMath {
    const val DAY_MS = 24L * 60 * 60 * 1000

    /** rxnet.js / shopbooks.js key(): the name key used to match stock and prescriptions */
    fun key(name: String?): String = (name ?: "").trim().lowercase().replace(Regex("\\s+"), " ")

    /** `Number(s)` for a string (JS rules: trimmed, '' → 0, decimal/exponent/hex literals); NaN when not a number */
    fun jsNumber(s: String): Double {
        val t = s.trim()
        if (t.isEmpty()) return 0.0
        if (Regex("^[+-]?Infinity$").matches(t)) return if (t.startsWith("-")) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
        Regex("^0[xX]([0-9a-fA-F]+)$").find(t)?.let { return it.groupValues[1].toBigInteger(16).toDouble() }
        Regex("^0[oO]([0-7]+)$").find(t)?.let { return it.groupValues[1].toBigInteger(8).toDouble() }
        Regex("^0[bB]([01]+)$").find(t)?.let { return it.groupValues[1].toBigInteger(2).toDouble() }
        if (!Regex("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$").matches(t)) return Double.NaN
        return t.toDouble()
    }

    /** rxnet.js perDay: "1+0+1" → 2, "½+0+½" → 1; 0 when it cannot be read */
    fun perDay(dose: String?): Double {
        val parts = (dose ?: "").replace(Regex("\\s"), "").replace("½", "0.5").split("+")
        if (parts.size < 2 || parts.size > 4) return 0.0
        var sum = 0.0
        for (p in parts) {
            val n = jsNumber(p)
            if (!n.isFinite() || n < 0) return 0.0
            sum += n
        }
        return sum
    }

    /** rxnet.js countable: tablets/capsules (or no form written) */
    fun countable(form: String?): Boolean {
        val f = (form ?: "").lowercase()
        return f.isEmpty() || f.contains("tablet") || f.contains("capsule") || f == "tab." || f == "cap."
    }

    /** rxnet.js needQty: pieces needed, null when unknown */
    fun needQty(m: RxMedicine): Int? {
        val d = perDay(m.dose)
        return if (countable(m.form) && d > 0 && m.days > 0) Math.ceil(d * m.days).toInt() else null
    }

    data class MedState(
        val i: Int,
        val name: String,
        val form: String,
        val strength: String,
        val dose: String,
        val days: Int,
        val need: Int?,
        val got: Int,
        val left: Int?,
        val done: Boolean,
    ) {
        fun toMap(): LinkedHashMap<String, Any?> = linkedMapOf(
            "i" to i, "name" to name, "form" to form, "strength" to strength, "dose" to dose, "days" to days,
            "need" to need, "got" to got, "left" to left, "done" to done,
        )
    }

    /** rxnet.js medStatus: bought and left per medicine */
    fun medStatus(medicines: List<RxMedicine>, dispensed: List<RxDispensing>): List<MedState> {
        val got = HashMap<Int, Int>()
        for (d in dispensed) for ((i, qty) in d.items) got[i] = (got[i] ?: 0) + qty
        return medicines.mapIndexed { i, m ->
            val need = needQty(m)
            val g = got[i] ?: 0
            MedState(
                i, m.name, m.form, m.strength, m.dose, m.days, need, g,
                if (need == null) null else maxOf(0, need - g),
                if (need == null) g > 0 && m.days > 0 else g >= need,
            )
        }
    }

    fun medStatus(rx: Prescription): List<MedState> = medStatus(rx.medicines, rx.dispensed)

    data class Wanted(val name: String, val k: String, val left: Int?, val countable: Boolean)

    /** rxnet.js wantedOf: medicines still to buy */
    fun wantedOf(rx: Prescription): List<Wanted> =
        medStatus(rx).filter { !it.done }.map { Wanted(it.name, key(it.name), it.left, countable(rx.medicines[it.i].form)) }

    /** rxnet.js pharmacyEstimate → {total, priced, of, mrp, discount} or null */
    fun pharmacyEstimate(wanted: List<Wanted>, keys: Set<String>, prices: Map<String, Double>?, mrps: Map<String, Double>? = null): Map<String, Any?>? {
        if (prices == null) return null
        var sum = 0.0
        var mrpSum = 0.0
        var priced = 0
        for (w in wanted) {
            if (w.k !in keys) continue
            val p = prices[w.k] ?: continue
            if (!(p > 0)) continue
            val q: Double = when {
                w.left != null -> w.left.toDouble()
                w.countable -> continue
                else -> 1.0
            }
            if (!(q > 0)) continue
            sum += p * q
            val m = mrps?.get(w.k)?.takeIf { it > p } ?: p
            mrpSum += m * q
            priced++
        }
        if (priced == 0) return null
        val total = Js.round(sum).toLong()
        if (mrps == null) return linkedMapOf("total" to total, "priced" to priced, "of" to wanted.size, "mrp" to null, "discount" to null)
        val mrp = Js.round(mrpSum).toLong()
        return linkedMapOf("total" to total, "priced" to priced, "of" to wanted.size, "mrp" to mrp, "discount" to maxOf(0L, mrp - total))
    }

    /** rxnet.js stockMatch over a shop's stock list */
    fun stockMatch(st: StockSnap?, wanted: List<Wanted>): Map<String, Any?> {
        val keys = if (st != null && st.exists) st.keys else emptySet()
        val prices = if (st != null && st.exists) st.pricesOrNull else null
        val mrps = if (st != null && st.exists && st.mrpAll) st.mrp else null
        return linkedMapOf(
            "keepsStock" to (st != null && st.exists && keys.isNotEmpty()),
            "have" to wanted.count { it.k in keys },
            "total" to wanted.size,
            "missing" to wanted.filter { it.k !in keys }.map { it.name },
            "estimate" to pharmacyEstimate(wanted, keys, prices, mrps),
        )
    }

    /**
     * rxnet.js refillDate: 2 days before the bought (countable) medicines run out — not when the whole course was
     * bought; tomorrow at the earliest. UTC-midnight ms of a Dhaka date, or null.
     */
    fun refillDate(medicines: List<RxMedicine>, dispensed: List<RxDispensing>, now: Long = System.currentTimeMillis()): Long? {
        val first = HashMap<Int, Long>()
        for (d in dispensed) for ((i, _) in d.items) if (i !in first) first[i] = d.at
        var best: Long? = null
        for (s in medStatus(medicines, dispensed)) {
            val m = medicines[s.i]
            val pd = perDay(m.dose)
            if (!countable(m.form) || pd <= 0 || s.got <= 0) continue
            if (s.need != null && s.got >= s.need) continue
            val runOut = DhakaTime.todayUtcMidnight(first[s.i] ?: 0L) + Math.floor(s.got / pd).toLong() * DAY_MS
            val at = maxOf(runOut - 2 * DAY_MS, DhakaTime.todayUtcMidnight(now) + DAY_MS)
            if (best == null || at < best) best = at
        }
        return best
    }

    // ------------------------------------------------------------------ orders

    /** rxnet.js OPEN_ORDER */
    val OPEN_ORDER = listOf("sent", "ready", "accepted", "out")
    val PHARM_SEGS = listOf("new", "active", "closed")

    fun pharmSeg(status: String?): String = when {
        status == "sent" -> "new"
        status in OPEN_ORDER -> "active"
        else -> "closed"
    }

    fun segCounts(segs: List<String>, list: List<String>): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        segs.forEach { out[it] = 0 }
        list.forEach { out[it] = (out[it] ?: 0) + 1 }
        return out
    }

    /** rxnet.js recentFirstOpen: newest first, at most [max] — open ones get their place first */
    fun <T> recentFirstOpen(list: List<T>, createdAt: (T) -> Long, isOpen: (T) -> Boolean, max: Int): List<T> {
        val sorted = list.sortedByDescending(createdAt)
        val open = sorted.filter(isOpen).take(max)
        val rest = sorted.filter { !isOpen(it) }.take(maxOf(0, max - open.size))
        return (open + rest).sortedByDescending(createdAt)
    }

    /** rxnet.js CONTACT_KEEP_MS: delivery address/phone of a closed order are kept 7 days */
    const val CONTACT_KEEP_MS = 7 * DAY_MS

    fun sha1Hex(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** rxnet.js missKey */
    fun missKey(name: String): String = "k_" + sha1Hex(key(name)).take(16)
}
