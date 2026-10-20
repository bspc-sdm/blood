package com.lifolo.ambulance

import com.fasterxml.jackson.databind.JsonNode
import com.lifolo.common.Js
import com.lifolo.common.Places

/**
 * A destination (ambfare.js destOf): `{kind: 'upazila', upazilaId}` | `{kind: 'district', district}` |
 * `{kind: 'hospital', facilityId?, name, upazilaId?, district?}` (with a facilityId the area comes from the list).
 */
data class Dest(
    val kind: String,
    val upazilaId: Int? = null,
    val district: String? = null,
    val facilityId: String? = null,
    val name: String? = null,
) {
    /** the Node object (key order of ambfare.js) */
    fun toMap(): LinkedHashMap<String, Any?> = when (kind) {
        "upazila" -> linkedMapOf("kind" to kind, "upazilaId" to upazilaId)
        "district" -> linkedMapOf("kind" to kind, "district" to district)
        else -> if (facilityId != null) {
            linkedMapOf("kind" to kind, "facilityId" to facilityId, "name" to name, "upazilaId" to upazilaId, "district" to district)
        } else {
            linkedMapOf("kind" to kind, "name" to name)
        }
    }
}

/** one row of a fare chart: a destination + whole taka */
data class FareRow(val dest: Dest, val amount: Int) {
    fun toMap(): LinkedHashMap<String, Any?> = dest.toMap().apply { put("amount", amount) }
}

/** ambulances/{id}.fareChart {local, rows[], oxygen, icu, night} */
data class FareChart(val local: Int?, val rows: List<FareRow>, val oxygen: Int?, val icu: Int?, val night: Int?) {
    fun toMap(): LinkedHashMap<String, Any?> = linkedMapOf(
        "local" to local, "rows" to rows.map { it.toMap() }, "oxygen" to oxygen, "icu" to icu, "night" to night,
    )

    fun extra(e: String): Int? = when (e) {
        "oxygen" -> oxygen
        "icu" -> icu
        "night" -> night
        else -> null
    }
}

/** the car's area for fare matching */
data class Veh(val district: String?, val upazilaId: Int?)

/** a hospital/clinic of the destination list (ambplace.js facility) */
data class Facility(val id: String, val nameEn: String, val nameBn: String, val type: String, val district: String, val upazilaId: Int?)

/**
 * ambfare.js — the owner's fare chart, the estimated fare for a patient's destination and the budget filter.
 * Pure (no database): [destOf] gets the hospital list lookup as a function.
 */
object AmbFare {
    const val MIN_FARE = 100L
    const val MAX_FARE = 100000L
    const val MIN_EXTRA = 50L
    const val MAX_EXTRA = 50000L
    const val MAX_ROWS = 15
    val EXTRAS = listOf("oxygen", "icu", "night")

    /** the patient's budget chips (any = null) — also the steps of "raise to ৳X" */
    val LEVELS = listOf(1000L, 2000L, 5000L, 10000L)

    private const val HOUR = 3600 * 1000L
    private const val DAY = 24 * HOUR

    /** [taka] result for "not a whole number" (JS NaN) */
    const val NAN = Long.MIN_VALUE

    /** numbers above this are only "too big" for every check — kept below overflow */
    private const val HUGE = 1_000_000_000_000_000L

    /** night (22:00–06:00 Dhaka time) */
    fun isNight(now: Long): Boolean {
        val h = Math.floorMod(now + 6 * HOUR, DAY) / HOUR
        return h >= 22 || h < 6
    }

    private const val BN_DIGITS = "০১২৩৪৫৬৭৮৯"

    /** JavaScript `\s` (white space incl. Unicode spaces) */
    const val JS_SPACE = "\\t\\n\\x0B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"
    private val SPACES = Regex("[$JS_SPACE]+")
    private val TRIM = Regex("^[$JS_SPACE]+|[$JS_SPACE]+$")
    private val TAKA_JUNK = Regex("[,$JS_SPACE৳]")

    fun jsTrim(s: String): String = TRIM.replace(s, "")

    /** ambfare.js taka: Bangla digits too ("১২০০" → 1200); null/'' → null; not a whole number → [NAN] */
    fun taka(v: JsonNode?): Long? {
        if (v == null || v.isNull || v.isMissingNode) return null
        if (v.isTextual) return takaText(v.textValue())
        if (v.isNumber) {
            if (!Js.isInteger(v)) return NAN
            val d = v.doubleValue()
            return if (Math.abs(d) >= HUGE) (if (d < 0) -HUGE else HUGE) else v.asLong()
        }
        return NAN
    }

    fun takaText(v: String): Long? {
        if (v.isEmpty()) return null
        val s = TAKA_JUNK.replace(v.map { c -> BN_DIGITS.indexOf(c).let { if (it >= 0) '0' + it else c } }.joinToString(""), "")
        if (s.isEmpty()) return null
        if (!s.all { it in '0'..'9' }) return NAN
        return if (s.length > 15) HUGE else s.toLong()
    }

    // hospital name matching: lower case, signs and common words removed, no spaces
    private val STOP = setOf("hospital", "clinic", "ltd", "limited", "the", "হাসপাতাল", "হসপিটাল", "ক্লিনিক", "লিমিটেড", "লি")
    private val NON_WORD = Regex("[^a-z0-9ঀ-৿]+")

    fun hospKey(name: String?): String =
        NON_WORD.replace((name ?: "").lowercase(), " ").split(' ').filter { it.isNotEmpty() && it !in STOP }.joinToString("")

    fun hospMatch(a: String?, b: String?): Boolean {
        val x = hospKey(a)
        val y = hospKey(b)
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        val (s, l) = if (x.length <= y.length) x to y else y to x
        return s.length >= 4 && l.contains(s)
    }

    /** a destination key (the same destination only once in a chart) */
    fun destKey(d: Dest): String = when {
        d.kind == "upazila" -> "u:${d.upazilaId}"
        d.kind == "district" -> "d:${d.district}"
        d.facilityId != null -> "f:${d.facilityId}"
        else -> "h:${hospKey(d.name)}"
    }

    /** `Number.isInteger(v) ? v : null` as an Int (ids of upazilas/unions) */
    fun intId(v: JsonNode?): Int? = Js.integer(v)?.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()

    /** list hospital ids: "dghs_123" / "x_…" (static list) or the document id of a hospital added from the app */
    private val FACILITY_ID = Regex("^(?:dghs_\\d{1,7}|x_[a-z0-9_]{2,30}|[A-Za-z0-9]{15,40})$")

    /**
     * ambfare.js destOf: a destination from the patient or the owner, or null when invalid.
     * [facility] = ambplace.js facility(id) (the hospital list).
     */
    fun destOf(v: JsonNode?, facility: (String) -> Facility?): Dest? {
        if (v == null || !v.isObject) return null
        val kind = Js.string(v["kind"])
        if (kind == "upazila") {
            val id = intId(v["upazilaId"])
            return if (id != null && Places.UPAZILA.containsKey(id)) Dest("upazila", upazilaId = id) else null
        }
        if (kind == "district") {
            val d = Js.string(v["district"])
            return if (Places.isDistrict(d)) Dest("district", district = d) else null
        }
        if (kind == "hospital") {
            var name = Js.string(v["name"])?.let { jsTrim(SPACES.replace(it, " ")).take(80) } ?: ""
            val id = Js.string(v["facilityId"])?.takeIf { FACILITY_ID.matches(it) }
            // a hospital of the static list: area from the list (not what the patient sent); the list name when none given
            val f = id?.let { facility(it) }
            if (f != null) {
                if (name.length < 3) name = f.nameBn.ifEmpty { f.nameEn }.take(80)
                return Dest("hospital", upazilaId = f.upazilaId, district = f.district, facilityId = id, name = name)
            }
            if (name.length < 3 || hospKey(name).isEmpty()) return null
            // a hospital added from the app (database): the area is checked and kept — only for fare matching
            if (id != null && !id.startsWith("dghs_") && !id.startsWith("x_")) {
                val district = Js.string(v["district"])?.takeIf { Places.isDistrict(it) }
                val up = intId(v["upazilaId"])
                val upazilaId = if (district != null && up != null && Places.UPAZILA[up]?.district == district) up else null
                return Dest("hospital", upazilaId = upazilaId, district = district, facilityId = id, name = name)
            }
            return Dest("hospital", name = name)
        }
        return null
    }

    /** the destination's name (driver card, not pushes) */
    fun destLabel(d: Dest?, lang: String = "bn"): String {
        if (d == null) return ""
        val en = lang == "en"
        if (d.kind == "upazila") {
            val u = d.upazilaId?.let { Places.UPAZILA[it] } ?: return ""
            val dist = if (en) Places.district(u.district)?.en ?: u.district else u.district
            return if (en) "${u.en}, $dist" else "${u.bn}, $dist"
        }
        if (d.kind == "district") return if (en) Places.district(d.district)?.en ?: (d.district ?: "") else d.district ?: ""
        return d.name ?: ""
    }

    /** normalizeChart result: the chart (null = no chart) or an error [en, bn] */
    data class ChartResult(val chart: FareChart?, val error: Pair<String, String>? = null)

    /**
     * ambfare.js normalizeChart: the owner's fare chart. [veh] = the car's district/upazila.
     * Input null/undefined → no chart.
     */
    fun normalizeChart(input: JsonNode?, veh: Veh, facility: (String) -> Facility?): ChartResult {
        if (input == null || input.isNull || input.isMissingNode) return ChartResult(null)
        if (!input.isObject) return ChartResult(null, "Fare list is not valid" to "ভাড়ার তালিকা ঠিক নেই")
        fun bad(en: String, bn: String) = ChartResult(null, en to bn)
        fun fareOk(n: Long) = n in MIN_FARE..MAX_FARE
        val local = taka(input["local"])
        if (local == NAN) return bad("Write fares in whole taka", "ভাড়া পুরো টাকায় লিখুন")
        if (local != null && !fareOk(local)) return bad("Fares must be between ৳100 and ৳1,00,000", "ভাড়া ৳১০০ থেকে ৳১,০০,০০০-এর মধ্যে লিখুন")
        val rowsIn: List<JsonNode> = input["rows"]?.takeIf { it.isArray }?.toList() ?: emptyList()
        if (rowsIn.size > MAX_ROWS) return bad("Up to 15 destinations", "সর্বোচ্চ ১৫টা গন্তব্য")
        val rows = ArrayList<FareRow>()
        val seen = HashSet<String>()
        for (r in rowsIn) {
            val d = destOf(r, facility) ?: return bad("Choose each destination", "প্রতিটা গন্তব্য বেছে নিন")
            if (d.kind == "upazila") {
                val u = Places.UPAZILA.getValue(d.upazilaId!!)
                if (u.district != veh.district) return bad("Choose an upazila of your district", "নিজের জেলার উপজেলা বেছে নিন")
                if (d.upazilaId == veh.upazilaId) {
                    return bad("Write your own upazila's fare in \"Within own upazila\"", "নিজের উপজেলার ভাড়া \"নিজের উপজেলার ভেতরে\" ঘরে লিখুন")
                }
            }
            val amount = taka(r["amount"]) ?: return bad("Write the fare for each destination", "প্রতিটা গন্তব্যের ভাড়া লিখুন")
            if (amount == NAN) return bad("Write fares in whole taka", "ভাড়া পুরো টাকায় লিখুন")
            if (!fareOk(amount)) return bad("Fares must be between ৳100 and ৳1,00,000", "ভাড়া ৳১০০ থেকে ৳১,০০,০০০-এর মধ্যে লিখুন")
            val k = destKey(d)
            if (!seen.add(k)) return bad("A destination is listed twice", "একই গন্তব্য দুবার আছে")
            rows.add(FareRow(d, amount.toInt()))
        }
        val extras = HashMap<String, Int?>()
        for (e in EXTRAS) {
            val n = taka(input[e])
            if (n == NAN) return bad("Write extras in whole taka", "বাড়তি ভাড়া পুরো টাকায় লিখুন")
            if (n != null && (n < MIN_EXTRA || n > MAX_EXTRA)) return bad("Extras must be between ৳50 and ৳50,000", "বাড়তি ভাড়া ৳৫০ থেকে ৳৫০,০০০-এর মধ্যে লিখুন")
            extras[e] = n?.toInt()
        }
        if (local == null && rows.isEmpty()) {
            // extras alone give no destination fare
            if (EXTRAS.any { extras[it] != null }) {
                return bad("Write at least one fare (local or a destination)", "অন্তত একটা ভাড়া লিখুন (নিজের উপজেলা বা কোনো গন্তব্য)")
            }
            return ChartResult(null)
        }
        return ChartResult(FareChart(local?.toInt(), rows, extras["oxygen"], extras["icu"], extras["night"]))
    }

    /** the destination fare (no extras), null when unknown */
    fun baseFare(chart: FareChart?, veh: Veh, dest: Dest?): Int? {
        if (chart == null || dest == null) return null
        val rows = chart.rows
        fun districtRow(district: String?) = rows.firstOrNull { it.dest.kind == "district" && it.dest.district == district }
        if (dest.kind == "upazila") {
            if (veh.upazilaId != null && dest.upazilaId == veh.upazilaId && chart.local != null) return chart.local
            rows.firstOrNull { it.dest.kind == "upazila" && it.dest.upazilaId == dest.upazilaId }?.let { return it.amount }
            val u = dest.upazilaId?.let { Places.UPAZILA[it] }
            return u?.let { districtRow(it.district) }?.amount
        }
        if (dest.kind == "district") return districtRow(dest.district)?.amount
        // hospital: same list id → name match → the hospital's upazila/district (when picked from the list)
        val h = (if (dest.facilityId != null) rows.firstOrNull { it.dest.kind == "hospital" && it.dest.facilityId == dest.facilityId } else null)
            ?: rows.firstOrNull { it.dest.kind == "hospital" && hospMatch(it.dest.name, dest.name) }
        if (h != null) return h.amount
        if (dest.upazilaId != null && dest.upazilaId != 0 && Places.UPAZILA.containsKey(dest.upazilaId)) {
            return baseFare(chart, veh, Dest("upazila", upazilaId = dest.upazilaId))
        }
        if (!dest.district.isNullOrEmpty()) return baseFare(chart, veh, Dest("district", district = dest.district))
        return null
    }

    /**
     * the estimated fare: destination + extras (ICU type, oxygen, night); unknown = null. An extra missing from the
     * chart counts as included in the fare (0).
     */
    fun estimate(chart: FareChart?, veh: Veh, dest: Dest?, type: String?, oxygen: Boolean, night: Boolean): Int? {
        val base = baseFare(chart, veh, dest) ?: return null
        return base +
            (if (type == "icu") chart!!.icu ?: 0 else 0) +
            (if (oxygen) chart!!.oxygen ?: 0 else 0) +
            (if (night) chart!!.night ?: 0 else 0)
    }

    /** within the budget: maxFare null = any; unknown fares only with includeUnknown */
    fun fits(est: Int?, maxFare: Long?, includeUnknown: Boolean): Boolean {
        if (est == null) return includeUnknown
        return maxFare == null || est <= maxFare
    }

    /** the patient's budget: null (any) or whole taka MIN_FARE–MAX_FARE; invalid → [NAN] */
    fun budgetOf(v: JsonNode?): Long? {
        if (v == null || v.isNull || v.isMissingNode) return null
        if (v.isNumber && v.doubleValue() == 0.0) return null
        if (v.isTextual && v.textValue().isEmpty()) return null
        val n = taka(v) ?: return null
        if (n == NAN || n < MIN_FARE || n > MAX_FARE) return NAN
        return n
    }

    /**
     * "raise to ৳X" when nothing matched: the next chip above the budget that has a car; above the chips the lowest
     * fare rounded up to thousands. null when nothing is known.
     */
    fun raiseTo(ests: List<Int?>, maxFare: Long?): Long? {
        val known = ests.filterNotNull().filter { maxFare == null || it > maxFare }
        if (known.isEmpty() || maxFare == null) return null
        val lo = known.min().toLong()
        return LEVELS.firstOrNull { it > maxFare && it >= lo } ?: (Math.ceil(lo / 1000.0).toLong() * 1000)
    }

    /** one line for the admin panel: "নিজের উপজেলা ৳৮০০; ঢাকা ৳৫০০০; অক্সিজেন +৳৩০০" */
    fun chartText(chart: FareChart?): String {
        if (chart == null) return ""
        fun bn(n: Int) = "৳" + n.toString().map { c -> if (c in '0'..'9') BN_DIGITS[c - '0'] else c }.joinToString("")
        val parts = ArrayList<String>()
        if (chart.local != null) parts.add("নিজের উপজেলা ${bn(chart.local)}")
        chart.rows.forEach { parts.add("${destLabel(it.dest, "bn")} ${bn(it.amount)}") }
        listOf("oxygen" to "অক্সিজেন", "icu" to "আইসিইউ যন্ত্র", "night" to "রাতে").forEach { (k, l) ->
            chart.extra(k)?.let { parts.add("$l +${bn(it)}") }
        }
        return parts.joinToString("; ")
    }
}
