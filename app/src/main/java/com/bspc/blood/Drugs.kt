package com.bspc.blood

import android.content.Context
import org.json.JSONObject

/**
 * ঔষধ প্রশাসন অধিদপ্তরের (DGDA) নিবন্ধিত ওষুধ (মানুষের), assets/drugs.json থেকে।
 * উৎস: স্বাস্থ্য অধিদপ্তরের টার্মিনোলজি সার্ভার (api.tr.ocl.dghs.gov.bd)। নতুন করে বানাতে:
 * Actions → "Fetch DGDA drug list" → data-drugs শাখা → scripts/build-drug-list.py
 */
data class Drug(val name: String, val generic: String, val form: String, val company: String) {
    /** অর্ডারের তালিকায় যেভাবে লেখা হয়: "Napa (Paracetamol 500 mg, Tablet)" */
    val orderLine: String
        get() = name + " (" + listOf(generic, form).filter { it.isNotBlank() }.joinToString(", ") + ")"
}

object Drugs {
    @Volatile private var list: List<Drug> = emptyList()
    @Volatile private var lowerNames: List<String> = emptyList()
    @Volatile private var lowerGenerics: List<String> = emptyList()

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

    /** নাম দিয়ে খোঁজা: ব্র্যান্ডের শুরু মিললে আগে, তারপর ব্র্যান্ডের ভেতরে, তারপর জেনেরিক */
    fun search(query: String, limit: Int = 12): List<Drug> {
        val q = query.trim().lowercase()
        if (q.length < 2 || list.isEmpty()) return emptyList()
        val starts = ArrayList<Drug>()
        val contains = ArrayList<Drug>()
        val generic = ArrayList<Drug>()
        for (i in list.indices) {
            val n = lowerNames[i]
            when {
                n.startsWith(q) -> starts.add(list[i])
                contains.size < limit && n.contains(q) -> contains.add(list[i])
                generic.size < limit && lowerGenerics[i].contains(q) -> generic.add(list[i])
            }
            if (starts.size >= limit * 3) break
        }
        return (starts.sortedBy { it.name.length } + contains + generic).distinct().take(limit)
    }
}
