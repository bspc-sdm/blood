package com.lifolo.common

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.json.JsonMapper

/**
 * Bangladesh places bundled with the app (same files as server/lib: districts.js → data/districts.json, geo.json,
 * upazila-centers.json). Districts are identified by their Bangla name everywhere in the API (the app's Districts.kt).
 */
object Places {
    data class District(val bn: String, val en: String, val lat: Double, val lng: Double)
    data class Upazila(val id: Int, val district: String, val bn: String, val en: String)
    data class Union(val id: Int, val upazilaId: Int, val bn: String, val en: String)

    private val mapper: ObjectMapper = JsonMapper()

    private fun res(name: String) = Places::class.java.getResourceAsStream("/data/$name") ?: error("missing data/$name")

    val DISTRICTS: List<District> by lazy {
        mapper.readTree(res("districts.json")).map { District(it["bn"].textValue(), it["en"].textValue(), it["lat"].doubleValue(), it["lng"].doubleValue()) }
    }

    private val geo by lazy { mapper.readTree(res("geo.json")) }

    /** geo.json "u": [id, district(bn), bn, en] */
    val UPAZILA: Map<Int, Upazila> by lazy {
        geo["u"].associate { a -> a[0].intValue() to Upazila(a[0].intValue(), a[1].textValue(), a[2].textValue(), a[3].textValue()) }
    }

    /** geo.json "n": [id, upazilaId, bn, en] */
    val UNION: Map<Int, Union> by lazy {
        geo["n"].associate { a -> a[0].intValue() to Union(a[0].intValue(), a[1].intValue(), a[2].textValue(), a[3].textValue()) }
    }

    /** upazila-centers.json "c": {upazilaId: [lat, lng]} */
    val UPAZILA_CENTER: Map<Int, Pair<Double, Double>> by lazy {
        val c = mapper.readTree(res("upazila-centers.json"))["c"]
        c.fieldNames().asSequence().associate { k -> k.toInt() to (c[k][0].doubleValue() to c[k][1].doubleValue()) }
    }

    fun isDistrict(bn: String?): Boolean = bn != null && DISTRICTS.any { it.bn == bn }

    fun district(bn: String?): District? = DISTRICTS.firstOrNull { it.bn == bn }

    /** an upazila id that belongs to the district, else null */
    fun upazilaIn(district: String?, upazilaId: Long?): Int? =
        upazilaId?.toInt()?.takeIf { UPAZILA[it]?.district == district }
}

/** Bangladeshi phone numbers (server/lib/care.js normalizeBd, localBd, fixPhone, contactPhone, normId). */
object Phones {
    private val MOBILE = Regex("^01[3-9]\\d{8}$")

    /** any mobile format → "8801XXXXXXXXX", else null */
    fun normalizeBd(raw: String?): String? {
        val d = (raw ?: "").filter { it in '0'..'9' }
        val local = when {
            d.startsWith("880") -> d.substring(2)
            d.startsWith("0") -> d
            else -> "0$d"
        }
        return if (MOBILE.matches(local)) "88$local" else null
    }

    /** "8801712345678" → "01712345678" */
    fun localBd(normalized: String?): String = normalized?.substring(2) ?: ""

    /** old documents stored "001…" by mistake */
    fun fixPhone(p: String?): String = if (p != null && Regex("^001[3-9]\\d{8}$").matches(p)) p.substring(1) else p ?: ""

    /** mobile (local form), 5-digit hotline (1xxxx) or landline (0[2-9] + 7–9 digits); else null */
    fun contactPhone(raw: String?): String? {
        normalizeBd(raw)?.let { return localBd(it) }
        val d = (raw ?: "").filter { it in '0'..'9' }
        if (Regex("^1\\d{4}$").matches(d)) return d
        val local = if (d.startsWith("880")) "0" + d.substring(3) else d
        return if (Regex("^0[2-9]\\d{7,9}$").matches(local)) local else null
    }

    /** licence/vehicle numbers for comparison: Bangla digits → ASCII, upper case, only letters/digits/Bangla */
    fun normId(v: String?): String {
        val bnDigits = "০১২৩৪৫৬৭৮৯"
        val ascii = (v ?: "").map { c -> val i = bnDigits.indexOf(c); if (i >= 0) ('0' + i) else c }.joinToString("")
        return ascii.uppercase().filter { it in '0'..'9' || it in 'A'..'Z' || it in 'ঀ'..'৿' }
    }
}
