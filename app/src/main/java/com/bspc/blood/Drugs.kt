package com.bspc.blood

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * ঔষধ প্রশাসন অধিদপ্তরের (DGDA) নিবন্ধিত ওষুধ (মানুষের), assets/drugs.json থেকে।
 * উৎস: স্বাস্থ্য অধিদপ্তরের টার্মিনোলজি সার্ভার (api.tr.ocl.dghs.gov.bd)। নতুন করে বানাতে:
 * Actions → "Fetch DGDA drug list" → data-drugs শাখা → scripts/build-drug-list.py
 *
 * এর বাইরে অ্যাডমিনের অনুমোদন দেওয়া ওষুধ (দোকানের অনুরোধে) সার্ভার থেকে আসে (key "c_…")।
 */
data class Drug(val name: String, val generic: String, val form: String, val company: String, val customKey: String? = null) {
    /** অর্ডারের তালিকায় যেভাবে লেখা হয়: "Napa (Paracetamol 500 mg, Tablet)" */
    val orderLine: String
        get() = name + " (" + listOf(generic, form).filter { it.isNotBlank() }.joinToString(", ") + ")"

    /**
     * স্থায়ী চাবি (দোকানের স্টক তালিকা আর খোঁজায়): নাম|জেনেরিক|ধরন|কোম্পানির ৬৪-বিট FNV-1a হ্যাশ।
     * তালিকা নতুন করে বানালেও একই ওষুধের চাবি একই থাকে।
     */
    val key: String by lazy { customKey ?: Drugs.keyOf(name, generic, form, company) }
}

object Drugs {
    @Volatile private var list: List<Drug> = emptyList()
    @Volatile private var lowerNames: List<String> = emptyList()
    @Volatile private var lowerGenerics: List<String> = emptyList()
    @Volatile private var custom: List<Drug> = emptyList()
    @Volatile private var byKeyMap: Map<String, Drug>? = null

    fun keyOf(name: String, generic: String, form: String, company: String): String {
        var h = -0x340d631b7bdddcdbL // FNV offset basis 0xcbf29ce484222325
        val s = (name.trim() + "|" + generic.trim() + "|" + form.trim() + "|" + company.trim()).lowercase()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toLong() and 0xff)
            h *= 0x100000001b3L
        }
        // Long.toUnsignedString Android 8-এর আগে নেই, তাই BigInteger দিয়ে (অচিহ্নিত ৬৪-বিট → base36)
        val u = java.math.BigInteger.valueOf(h).let { if (h < 0) it.add(java.math.BigInteger.ONE.shiftLeft(64)) else it }
        return u.toString(36)
    }

    /** একবারই পড়া (প্রায় ২ MB) — আলাদা থ্রেড থেকে ডাকুন */
    fun ensure(context: Context) {
        if (list.isNotEmpty()) return
        synchronized(this) {
            if (list.isNotEmpty()) return
            val text = context.applicationContext.assets.open("drugs.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val o = JSONObject(text)
            val forms = o.getJSONArray("f")
            val companies = o.getJSONArray("c")
            val d = o.getJSONArray("d")
            val out = ArrayList<Drug>(d.length())
            for (i in 0 until d.length()) {
                val r = d.getJSONArray(i)
                out.add(Drug(r.getString(0), r.getString(1), forms.optString(r.getInt(2)), companies.optString(r.getInt(3))))
            }
            lowerNames = out.map { it.name.lowercase() }
            lowerGenerics = out.map { it.generic.lowercase() }
            list = out
        }
    }

    /** অ্যাডমিনের যোগ করা ওষুধ সার্ভার থেকে (অ্যাপ চালুর পর একবার) */
    @Volatile private var customLoaded = false

    suspend fun loadCustom() {
        if (customLoaded) return
        runCatching {
            val a = ServerApi.post("/api/community", JSONObject().put("action", "drugs_custom")).optJSONArray("items") ?: JSONArray()
            custom = (0 until a.length()).map {
                val x = a.getJSONObject(it)
                Drug(x.optString("name"), x.optString("generic"), x.optString("form"), x.optString("company"), x.optString("key"))
            }
            byKeyMap = null
            customLoaded = true
        }
    }

    /** চাবি থেকে ওষুধ (দোকানের তালিকা দেখানোর জন্য) */
    fun byKey(key: String): Drug? {
        val m = byKeyMap ?: HashMap<String, Drug>(list.size + custom.size).also { map ->
            list.forEach { map[it.key] = it }
            custom.forEach { map[it.key] = it }
            if (list.isNotEmpty()) byKeyMap = map
        }
        return m[key]
    }

    /** কোম্পানির নাম (বেশি ওষুধ আগে) */
    fun companies(): List<Pair<String, Int>> =
        (list + custom).filter { it.company.isNotBlank() }.groupingBy { it.company }.eachCount().entries
            .sortedByDescending { it.value }.map { it.key to it.value }

    fun ofCompany(company: String): List<Drug> = (list + custom).filter { it.company == company }.distinctBy { it.key }.sortedBy { it.name.lowercase() }

    /** নাম দিয়ে খোঁজা: ব্র্যান্ডের শুরু মিললে আগে, তারপর ব্র্যান্ডের ভেতরে, তারপর জেনেরিক */
    fun search(query: String, limit: Int = 12): List<Drug> {
        val q = query.trim().lowercase()
        if (q.length < 2 || list.isEmpty()) return emptyList()
        val starts = ArrayList<Drug>()
        val contains = ArrayList<Drug>()
        val generic = ArrayList<Drug>()
        custom.forEach { d ->
            val n = d.name.lowercase()
            when {
                n.startsWith(q) -> starts.add(d)
                n.contains(q) -> contains.add(d)
                d.generic.lowercase().contains(q) -> generic.add(d)
            }
        }
        for (i in list.indices) {
            val n = lowerNames[i]
            when {
                n.startsWith(q) -> starts.add(list[i])
                contains.size < limit && n.contains(q) -> contains.add(list[i])
                generic.size < limit && lowerGenerics[i].contains(q) -> generic.add(list[i])
            }
            if (starts.size >= limit * 3) break
        }
        return (starts.sortedBy { it.name.length } + contains + generic).distinctBy { it.key }.take(limit)
    }
}
