package com.bspc.blood

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// ------------------------------------------------------------
// এলাকা: জেলা → উপজেলা → ইউনিয়ন
// উৎস: github.com/nuhil/bangladesh-geocode (MIT License, Copyright (c) 2014 Nuhil Mehdy)
// ------------------------------------------------------------

data class Upazila(val id: Int, val district: String, val bn: String, val en: String) {
    val label: String get() = if (isBangla) bn else en
}

data class Union(val id: Int, val upazilaId: Int, val bn: String, val en: String) {
    val label: String get() = if (isBangla) bn else en
}

object Areas {
    @Volatile private var upazilas: List<Upazila> = emptyList()
    @Volatile private var unions: List<Union> = emptyList()

    /** assets/geo.json একবার পড়া (প্রায় ২৫০ KB) */
    fun ensure(context: Context) {
        if (upazilas.isNotEmpty()) return
        synchronized(this) {
            if (upazilas.isNotEmpty()) return
            val text = context.applicationContext.assets.open("geo.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val o = JSONObject(text)
            val u = o.getJSONArray("u")
            val n = o.getJSONArray("n")
            unions = (0 until n.length()).map {
                val a = n.getJSONArray(it)
                Union(a.getInt(0), a.getInt(1), a.getString(2), a.getString(3))
            }
            upazilas = (0 until u.length()).map {
                val a = u.getJSONArray(it)
                Upazila(a.getInt(0), a.getString(1), a.getString(2), a.getString(3))
            }
        }
    }

    fun upazilasOf(district: String): List<Upazila> =
        upazilas.filter { it.district == district }.sortedBy { it.label }

    fun unionsOf(upazilaId: Int): List<Union> =
        unions.filter { it.upazilaId == upazilaId }.sortedBy { it.label }

    fun upazila(id: Int?): Upazila? = id?.let { i -> upazilas.firstOrNull { it.id == i } }
    fun union(id: Int?): Union? = id?.let { i -> unions.firstOrNull { it.id == i } }

    /**
     * এক সার্চ বক্সে জেলা, উপজেলা (আর চাইলে ইউনিয়ন) — নামের শুরুতে মিললে আগে।
     * ফল: (district, upazilaId, unionId)
     */
    fun search(query: String, withUnions: Boolean, limit: Int = 40): List<Triple<String, Int?, Int?>> {
        val q = bnNorm(query.trim())
        if (q.isEmpty()) return emptyList()
        fun score(bn0: String, en: String): Int {
            val bn = bnNorm(bn0)
            return when {
                bn.startsWith(q) || en.startsWith(q, true) -> 0
                bn.contains(q) || en.contains(q, true) -> 1
                else -> -1
            }
        }
        val out = mutableListOf<Pair<Int, Triple<String, Int?, Int?>>>()
        Districts.ALL.forEach { d -> score(d.bn, d.en).takeIf { it >= 0 }?.let { out += (it * 3) to Triple(d.bn, null, null) } }
        upazilas.forEach { u -> score(u.bn, u.en).takeIf { it >= 0 }?.let { out += (it * 3 + 1) to Triple(u.district, u.id, null) } }
        if (withUnions && q.length >= 2) {
            val ups = upazilas.associateBy { it.id }
            unions.forEach { n ->
                score(n.bn, n.en).takeIf { it >= 0 }?.let { s -> ups[n.upazilaId]?.let { u -> out += (s * 3 + 2) to Triple(u.district, u.id, n.id) } }
            }
        }
        return out.sortedBy { it.first }.take(limit).map { it.second }
    }

    /** "য়/ড়/ঢ়" দুই রকম ইউনিকোডে লেখা যায় (কিবোর্ড ভেদে) — মেলানোর আগে এক রূপে */
    fun bnNorm(s: String): String =
        s.replace("\u09AF\u09BC", "\u09DF").replace("\u09A1\u09BC", "\u09DC").replace("\u09A2\u09BC", "\u09DD")

    /** "ইউনিয়ন, উপজেলা, জেলা" */
    fun label(district: String?, upazilaId: Int?, unionId: Int? = null): String =
        listOfNotNull(union(unionId)?.label, upazila(upazilaId)?.label, Districts.label(district).ifBlank { null })
            .joinToString(", ")
}

internal fun JSONObject.intOrNull(key: String): Int? = if (isNull(key) || !has(key)) null else optInt(key)
internal fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key)
internal fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }
internal fun JSONArray?.ints(): List<Int> = if (this == null) emptyList() else (0 until length()).map { optInt(it) }

private suspend fun careCall(action: String, body: JSONObject = JSONObject()): JSONObject =
    ServerApi.post("/api/community", body.put("action", action))

// ------------------------------------------------------------
// হাসপাতাল
// ------------------------------------------------------------

data class Hospital(
    val id: String,
    val nameEn: String,
    val nameBn: String,
    val type: String,
    val district: String,
    val upazilaId: Int?,
    val unionId: Int?,
    val address: String,
    val phone: String,
) {
    val label: String get() = if (isBangla && nameBn.isNotBlank()) nameBn else nameEn
}

data class HospitalRequestItem(val hospital: Hospital, val count: Int, val possibleDuplicates: List<Hospital>)

/** হাসপাতাল যোগের অনুরোধের ফল */
sealed interface HospitalRequestResult {
    data object Sent : HospitalRequestResult
    data object Merged : HospitalRequestResult
    data class Exists(val hospital: Hospital) : HospitalRequestResult
}

object Hospitals {
    val TYPES = listOf(
        "govt_mch", "govt_specialized", "govt_district", "govt_uhc", "govt_hospital", "private_hospital", "clinic", "diagnostic",
    )

    fun typeLabel(t: String): String = when (t) {
        "govt_mch" -> tr("Govt. medical college hospital", "সরকারি মেডিকেল কলেজ হাসপাতাল")
        "govt_specialized" -> tr("Govt. specialized hospital", "সরকারি বিশেষায়িত হাসপাতাল")
        "govt_district" -> tr("District / Sadar hospital", "জেলা / সদর হাসপাতাল")
        "govt_uhc" -> tr("Upazila Health Complex", "উপজেলা স্বাস্থ্য কমপ্লেক্স")
        "govt_hospital" -> tr("Other govt. hospital", "অন্যান্য সরকারি হাসপাতাল")
        "private_hospital" -> tr("Private hospital", "বেসরকারি হাসপাতাল")
        "clinic" -> tr("Clinic", "ক্লিনিক")
        else -> tr("Diagnostic centre", "ডায়াগনস্টিক সেন্টার")
    }

    fun parse(o: JSONObject) = Hospital(
        o.str("id"), o.str("nameEn"), o.str("nameBn"), o.str("type"), o.str("district"),
        o.intOrNull("upazilaId"), o.intOrNull("unionId"), o.str("address"), o.str("phone"),
    )

    private val cache = mutableMapOf<String, List<Hospital>>()

    /** জেলার সব হাসপাতাল (কিছুক্ষণ মনে রাখা হয়) */
    suspend fun inDistrict(district: String, fresh: Boolean = false): List<Hospital> {
        if (!fresh) cache[district]?.let { return it }
        val arr = careCall("hospitals", JSONObject().put("district", district)).optJSONArray("items") ?: JSONArray()
        val list = (0 until arr.length()).map { parse(arr.getJSONObject(it)) }
        cache[district] = list
        return list
    }

    suspend fun request(
        nameEn: String, nameBn: String, type: String, district: String, upazilaId: Int?, unionId: Int?, address: String, phone: String,
    ): HospitalRequestResult {
        val r = careCall(
            "hospital_request",
            JSONObject().put("nameEn", nameEn).put("nameBn", nameBn).put("type", type).put("district", district)
                .put("upazilaId", upazilaId ?: JSONObject.NULL).put("unionId", unionId ?: JSONObject.NULL)
                .put("address", address).put("phone", phone),
        )
        r.optJSONObject("duplicateOf")?.let { return HospitalRequestResult.Exists(parse(it)) }
        return if (r.optBoolean("merged")) HospitalRequestResult.Merged else HospitalRequestResult.Sent
    }

    /** op: "approve", "reject", "merge" (targetId সহ) */
    suspend fun resolve(id: String, op: String, targetId: String? = null) {
        careCall("admin_hospital_resolve", JSONObject().put("id", id).put("op", op).apply { if (targetId != null) put("targetId", targetId) })
        cache.clear()
    }

    // ---- নামের মিল (সার্ভারের hospital-match.js-এর মতো, শুধু পরামর্শ দেখানোর জন্য) ----
    private val STOP = setOf(
        "hospital", "hospitals", "clinic", "clinics", "ltd", "limited", "pvt", "private", "center", "centre", "and",
        "the", "of", "diagnostic", "diagnostics", "medical", "health", "complex", "general",
    )

    fun normalize(name: String): String =
        name.lowercase().replace("&", " and ").replace(Regex("[^a-z0-9\\u0980-\\u09FF]+"), " ")
            .split(" ").filter { it.isNotBlank() && it !in STOP }.joinToString(" ")

    private fun levenshtein(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            cur.copyInto(prev)
        }
        return prev[b.length]
    }

    /** ০..১: দুটো নাম কতটা কাছাকাছি */
    fun similarity(a: String, b: String): Double {
        val x = normalize(a)
        val y = normalize(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        val lev = 1.0 - levenshtein(x, y).toDouble() / maxOf(x.length, y.length)
        val wa = x.split(" ").toSet()
        val wb = y.split(" ").toSet()
        val jac = wa.intersect(wb).size.toDouble() / wa.union(wb).size
        val contains = if (x.contains(y) || y.contains(x)) 0.85 else 0.0
        return maxOf(lev, jac, contains)
    }

    /** লেখা নামের সাথে মিলে যাওয়া হাসপাতাল (বাংলা নামেও খোঁজে) */
    fun similar(query: String, list: List<Hospital>, limit: Int = 5): List<Hospital> {
        val q = query.trim()
        if (q.length < 3) return emptyList()
        return list.map { h ->
            val s = maxOf(similarity(q, h.nameEn), if (h.nameBn.contains(q)) 0.9 else 0.0)
            h to s
        }.filter { it.second >= 0.6 }.sortedByDescending { it.second }.take(limit).map { it.first }
    }
}

// ------------------------------------------------------------
// ডাক্তার/নার্স আর চেম্বার
// ------------------------------------------------------------

/** চেম্বারের এক ভাগ সময়: কোন কোন বার, কখন থেকে কখন */
data class ChamberTime(val days: List<Int> = emptyList(), val start: String = "", val end: String = "") {
    val text: String
        get() = listOf(
            Weekdays.ORDER.filter { it in days }.joinToString(", ") { Weekdays.short(it) },
            if (start.isNotBlank() && end.isNotBlank()) Weekdays.time(start) + " – " + Weekdays.time(end) else "",
        ).filter { it.isNotBlank() }.joinToString(" · ")

    fun toJson(): JSONObject = JSONObject().put("days", JSONArray(days)).put("start", start).put("end", end)
}

const val MAX_CHAMBER_TIMES = 4

internal fun parseTimes(a: JSONArray?): List<ChamberTime> =
    if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { t -> ChamberTime(t.optJSONArray("days").ints(), t.str("start"), t.str("end")) }

/** একজন একই সময়ে দুই জায়গায় বসতে পারেন না — সার্ভারের timeClash-এর মতো (সপ্তাহের মিনিটে মিলিয়ে) */
object ChamberTimes {
    private const val WEEK = 7 * 1440

    private fun min(t: String): Int? = t.split(":").takeIf { it.size == 2 }?.let { (h, m) -> h.toIntOrNull()?.let { hh -> m.toIntOrNull()?.let { hh * 60 + it } } }

    private fun intervals(t: ChamberTime): List<Pair<Int, Int>> {
        val s = min(t.start) ?: return emptyList()
        var e = min(t.end) ?: return emptyList()
        if (e <= s) e += 1440
        return t.days.flatMap { d ->
            val a = d * 1440 + s
            val b = d * 1440 + e
            if (b <= WEEK) listOf(a to b) else listOf(a to WEEK, 0 to b - WEEK)
        }
    }

    /** এক তালিকার ভাগগুলো নিজেদের মধ্যে মিলে যায় কি না (বাসায় সেবা) */
    fun clashSlots(slots: List<ChamberTime>): Boolean =
        clash(listOf(Chamber(null, "", "", "", null, null, "", "", "", false, "").withSlots(slots).copy(times = slots))) != null

    /** সময় মিলে যাওয়া দুই চেম্বার (একই চেম্বারের দুই ভাগ হলে দুটোই এক), নইলে null */
    fun clash(chambers: List<Chamber>): Pair<Chamber, Chamber>? {
        val all = chambers.flatMap { c -> c.slots.flatMap { t -> intervals(t).map { c to it } } }.sortedBy { it.second.first }
        for (i in 1 until all.size) for (j in i - 1 downTo 0) {
            if (all[j].second.second > all[i].second.first) return all[j].first to all[i].first
        }
        return null
    }
}

data class Chamber(
    val hospitalId: String?,
    val hospitalName: String,
    val hospitalNameBn: String,
    val district: String,
    val upazilaId: Int?,
    val unionId: Int?,
    val room: String,
    /** লেখা নোট (ঐচ্ছিক); আগের ভার্সনে এটাই ছিল সময় */
    val schedule: String,
    val fee: String,
    val appointments: Boolean,
    val serialPhone: String,
    /** বার: ০ = রবি … ৬ = শনি */
    val days: List<Int> = emptyList(),
    /** "17:00" */
    val start: String = "",
    val end: String = "",
    /** ডাক্তার/নার্স "ফি চাই না" টিক দিয়েছেন */
    val noFee: Boolean = false,
    /** নিজে লেখা জায়গা (পুষ্টিবিদ/ট্রেইনারের জিম, অফিস) — hospitalName = জায়গার নাম */
    val custom: Boolean = false,
    val address: String = "",
    /** বার অনুযায়ী আলাদা সময় (একাধিক হলে); ফাঁকা হলে উপরের days/start/end একটাই ভাগ */
    val times: List<ChamberTime> = emptyList(),
    /** রোগীপ্রতি গড় সময় (মিনিট) — সিরিয়ালের আনুমানিক সময়ে; null = ঠিক করা নেই (১০ ধরা হয়) */
    val patientMin: Int? = null,
    /** "in_person" (চেম্বারে) বা "online" (ভিডিও) — অনলাইনে হাসপাতাল/ঠিকানা নেই (OnlineChamber.kt) */
    val mode: String = "in_person",
    /** অনলাইন: ফি পাঠানোর bKash নম্বর (শুধু নিজের provider_me-তে আসে; রোগী বুকিংয়ের পরে দেখেন) */
    val bkashNumber: String = "",
    /** অনলাইন: রোগীদের জন্য নোট (≤ ২০০, ফোন নম্বর নয়; শুধু বুকিং করা রোগীরা দেখেন) */
    val note: String = "",
    /** অনলাইন: সার্ভারে সেভ করা নম্বর (বদলালে ২৪ ঘণ্টা পর চালু — সেটআপে জানানো হয়); পাঠানো হয় না */
    val bkashSaved: String = "",
    /** অনলাইন: বদলানো নম্বর যা এখনো চালু হয়নি (সার্ভার দিলে) */
    val bkashPending: String = "",
) {
    val online: Boolean get() = mode == "online"

    /** সব সময়ের ভাগ — এডিটর আর দেখানো এটাই ব্যবহার করে */
    val slots: List<ChamberTime> get() = times.ifEmpty { listOf(ChamberTime(days, start, end)) }

    /** ভাগ বদলালে উপরের days (সব বার) আর start/end (প্রথম ভাগ) মিলিয়ে রাখা — আগের অ্যাপ আর বুকিং-এর জন্য */
    fun withSlots(list: List<ChamberTime>): Chamber {
        val l = list.ifEmpty { listOf(ChamberTime()) }
        return copy(times = if (l.size > 1) l else emptyList(), days = l.flatMap { it.days }.distinct().sorted(), start = l.first().start, end = l.first().end)
    }

    /** তালিকায় দেখানোর লেখা: "ফি: ৫০০ টাকা" / "ফি নেই"; কিছু না দিলে (আগের ভার্সন) ফাঁকা */
    val feeText: String get() = if (noFee) tr("No fee", "ফি নেই") else if (fee.isNotBlank()) tr("Fee: ", "ফি: ") + fee else ""
    val label: String get() = if (isBangla && hospitalNameBn.isNotBlank()) hospitalNameBn else hospitalName
    val area: String get() = if (online) "" else listOf(address, Areas.label(district, upazilaId, unionId)).filter { it.isNotBlank() }.joinToString(", ")

    /** "শনি, রবি, সোম · বিকাল ৫:০০ – রাত ৯:০০" — প্রতিটা ভাগ আলাদা লাইনে (+ নোট) */
    val timeText: String
        get() = (slots.map { it.text } + schedule).filter { it.isNotBlank() }.joinToString("\n")

    /** এই তারিখে (UTC মধ্যরাত) বসেন কি না */
    fun openOn(dateUtcMidnight: Long): Boolean {
        val days = slots.flatMap { it.days }
        if (days.isEmpty()) return true
        val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        c.timeInMillis = dateUtcMidnight
        return (c.get(java.util.Calendar.DAY_OF_WEEK) - 1) in days
    }

    fun toJson(): JSONObject = JSONObject().put("hospitalId", hospitalId).put("room", room).put("schedule", schedule)
        .apply { if (custom) put("placeName", hospitalName).put("district", district).put("address", address).apply { upazilaId?.let { put("upazilaId", it) } } }
        .put("fee", fee).put("noFee", noFee).put("appointments", appointments).put("serialPhone", serialPhone)
        .put("days", JSONArray(days)).put("start", start).put("end", end)
        .apply { if (times.size > 1) put("times", JSONArray(times.map { it.toJson() })) }
        .put("patientMin", patientMin ?: JSONObject.NULL)
        .apply { if (online) put("mode", "online").put("hospitalId", JSONObject.NULL).put("bkashNumber", bkashNumber).put("note", note) }

    companion object {
        fun parse(o: JSONObject) = Chamber(
            if (o.isNull("hospitalId")) null else o.optString("hospitalId"), o.str("hospitalName"), o.str("hospitalNameBn"),
            o.str("district"), o.intOrNull("upazilaId"), o.intOrNull("unionId"), o.str("room"), o.str("schedule"),
            o.str("fee"), o.optBoolean("appointments"), o.str("serialPhone"),
            o.optJSONArray("days").ints(), o.str("start"), o.str("end"), o.optBoolean("noFee"),
            o.optBoolean("custom"), o.str("address"),
            parseTimes(o.optJSONArray("times")).takeIf { it.size > 1 }.orEmpty(),
            o.intOrNull("patientMin"),
            if (o.str("mode") == "online") "online" else "in_person", o.str("bkashNumber"), o.str("note"),
            bkashSaved = o.str("bkashNumber"),
            bkashPending = listOf("bkashPending", "bkashNumberPending", "pendingBkashNumber", "bkashNext").firstNotNullOfOrNull { k -> o.str(k).takeIf { it.isNotBlank() } }.orEmpty(),
        )

        /** পুষ্টিবিদ/ট্রেইনারের নতুন চেম্বার: জায়গা নিজে লিখবেন */
        fun customNew(district: String) = Chamber(null, "", "", district, null, null, "", "", "", true, "", custom = true)

        /** ডাক্তারের নতুন অনলাইন (ভিডিও) চেম্বার — হাসপাতাল/ঠিকানা নেই */
        fun onlineNew() = Chamber(null, OnlineChambers.NAME_EN, OnlineChambers.NAME_BN, "", null, null, "", "", "", true, "", mode = "online")

        fun from(h: Hospital) = Chamber(h.id, h.nameEn, h.nameBn, h.district, h.upazilaId, h.unionId, "", "", "", true, "")
    }
}

/** বাসায় গিয়ে সেবা (ডাক্তার বা নার্স); phone = রোগীরা যে নম্বরে ফোন করবেন (তালিকায় সবাই দেখেন) */
data class HomeVisitSettings(
    val enabled: Boolean = false,
    val radiusKm: Int = 5,
    val upazilaIds: List<Int> = emptyList(),
    val services: List<String> = emptyList(),
    val schedule: String = "",
    val fee: String = "",
    val phone: String = "",
    val noFee: Boolean = false,
    /** কোন কোন বার কখন যান */
    val times: List<ChamberTime> = emptyList(),
) {
    /** "শুক্র · সকাল ৯:০০ – দুপুর ১:০০" — প্রতিটা ভাগ আলাদা লাইনে, তারপর নোট */
    val timeText: String get() = (times.map { it.text } + schedule).filter { it.isNotBlank() }.joinToString("\n")

    fun toJson(): JSONObject = JSONObject().put("enabled", enabled).put("termsAccepted", enabled).put("radiusKm", radiusKm)
        .put("upazilaIds", JSONArray(upazilaIds)).put("services", JSONArray(services)).put("schedule", schedule).put("fee", fee)
        .put("noFee", noFee).put("phone", phone).put("times", JSONArray(times.map { it.toJson() }))

    companion object {
        fun parse(o: JSONObject?) = if (o == null) null else HomeVisitSettings(
            o.optBoolean("enabled"), o.optInt("radiusKm", 5), o.optJSONArray("upazilaIds").ints(),
            o.optJSONArray("services").strings(), o.str("schedule"), o.str("fee"), o.str("phone"), o.optBoolean("noFee"),
            parseTimes(o.optJSONArray("times")),
        )
    }
}

data class Provider(
    val uid: String,
    val type: String,
    val name: String,
    val specialties: List<String>,
    val degrees: String,
    /** "male", "female" বা "" */
    val gender: String,
    /** তালিকায় দেখাবে */
    val listed: Boolean,
    /** অ্যাপে অ্যাপয়েন্টমেন্ট নেন */
    val appointments: Boolean,
    val chambers: List<Chamber>,
    val homeVisit: HomeVisitSettings?,
    val rating: Double,
    val ratingCount: Int,
    /** কার্ডের ছোট ছবি (base64) */
    val photo: String = "",
    /** তালিকার ছাঁকনিতে কোন চেম্বারগুলো মিলেছে (সার্ভারের matched, ২.৩৩.০); null = জানা নেই */
    val matched: List<Int>? = null,
) {
    val canBook: Boolean get() = appointments && chambers.any { it.appointments }
}

// ------------------------------------------------------------
// চেম্বারের আজকের অবস্থা (২.৩৩.০, পারভেজ) — ওয়েবের chamberState (model.ts) আর সার্ভারের care.js-এর একই নিয়ম, ঢাকার সময়ে:
// আজকের ভাগ শেষ = শুরু < শেষ আর এখন ≥ শেষ; রাত পেরোনো ভাগ (১৬:০০–০১:০০) মাঝরাতের পরেও "এখন চেম্বারে"।
// ------------------------------------------------------------

enum class ChamberStateKind { NOW, TODAY, LATER, UNKNOWN, NONE }

/** text: ছোট লেখা ("আজ বসবেন ৪টা–৯টা" / "আজ বন্ধ · আগামীকাল ১০টা"); date: পরের বসার দিন (UTC মধ্যরাত) */
data class ChamberState(val kind: ChamberStateKind, val text: String, val date: Long?, val start: String, val end: String) {
    val good: Boolean get() = kind == ChamberStateKind.NOW || kind == ChamberStateKind.TODAY
}

object ChamberClock {
    private const val DAY = 24L * 60 * 60 * 1000
    private val HM = Regex("^\\d{2}:\\d{2}$")

    fun dhakaToday(now: Long): Long = Math.floorDiv(now + Rules.DHAKA_OFFSET_MS, DAY) * DAY
    private fun hhmm(now: Long): String = Rules.dhakaHourMinute(now).let { (h, m) -> Weekdays.hhmm(h, m) }
    /** ১৯৭০-০১-০১ বৃহস্পতিবার (৪) */
    private fun weekday(date: Long): Int = Math.floorMod(Math.floorDiv(date, DAY) + 4, 7L).toInt()
    private fun timed(t: ChamberTime) = HM.matches(t.start) && HM.matches(t.end)
    private fun slotsOn(c: Chamber, date: Long) = c.slots.filter { it.days.isEmpty() || weekday(date) in it.days }.sortedBy { it.start }

    /** "16:00" → "৪টা" / "4 PM"; "16:30" → "৪:৩০" / "4:30 PM" */
    fun shortTime(hhmm: String): String {
        val p = hhmm.split(":")
        val h = p.getOrNull(0)?.toIntOrNull() ?: return hhmm
        val m = p.getOrNull(1)?.toIntOrNull() ?: 0
        val h12 = if (h % 12 == 0) 12 else h % 12
        if (isBangla) return if (m != 0) num(h12) + ":" + num(m.toString().padStart(2, '0')) else num(h12) + "টা"
        return h12.toString() + (if (m != 0) ":" + m.toString().padStart(2, '0') else "") + if (h < 12) " AM" else " PM"
    }

    private fun range(t: ChamberTime) = shortTime(t.start) + "–" + shortTime(t.end)

    fun state(c: Chamber, now: Long = System.currentTimeMillis()): ChamberState {
        val t0 = dhakaToday(now)
        val t = hhmm(now)
        val all = c.slots
        if (all.none { timed(it) } && all.none { it.days.isNotEmpty() }) return ChamberState(ChamberStateKind.UNKNOWN, "", null, "", "")
        // এখন বসছেন: আজকের ভাগ, বা গতকালের রাত পেরোনো ভাগ এখনো চলছে
        val nowSlot = if (c.openOn(t0)) slotsOn(c, t0).firstOrNull { timed(it) && (if (it.start < it.end) t >= it.start && t < it.end else t >= it.start) } else null
        val lastNight = if (c.openOn(t0 - DAY)) slotsOn(c, t0 - DAY).firstOrNull { timed(it) && it.start >= it.end && t < it.end } else null
        val cur = nowSlot ?: lastNight
        if (cur != null) {
            return ChamberState(ChamberStateKind.NOW, (if (c.online) tr("Online now · until ", "এখন অনলাইনে · ") else tr("In chamber now · until ", "এখন চেম্বারে · ")) + shortTime(cur.end) + tr("", " পর্যন্ত"), if (nowSlot != null) t0 else t0 - DAY, cur.start, cur.end)
        }
        val todayOpen = c.openOn(t0)
        if (todayOpen) {
            val next = slotsOn(c, t0).firstOrNull { !timed(it) || it.start > t }
            if (next != null) {
                return ChamberState(ChamberStateKind.TODAY, if (timed(next)) tr("Today ", "আজ বসবেন ") + range(next) else tr("Sits today", "আজ বসবেন"), t0, next.start, next.end)
            }
        }
        for (i in 1..7) {
            val d = t0 + i * DAY
            if (!c.openOn(d)) continue
            val first = slotsOn(c, d).firstOrNull()
            val at = if (first != null && timed(first)) " " + shortTime(first.start) else ""
            val whenText = (if (i == 1) tr("Tomorrow", "আগামীকাল") else Weekdays.short(weekday(d))) + at
            val pre = if (todayOpen) tr("Done today", "আজ শেষ") else tr("Closed today", "আজ বন্ধ")
            return ChamberState(ChamberStateKind.LATER, "$pre · $whenText", d, first?.start.orEmpty(), first?.end.orEmpty())
        }
        return ChamberState(ChamberStateKind.NONE, tr("No upcoming days", "সামনে বসার দিন নেই"), null, "", "")
    }

    /** "কোন চেম্বারে?"-এ পরের সময়: "আজ (১৯ অক্টোবর), বিকাল ৪:০০ – রাত ১:০০" */
    fun nextSlotText(s: ChamberState, now: Long = System.currentTimeMillis()): String {
        val d = s.date ?: return s.text
        val t0 = dhakaToday(now)
        val time = if (s.start.isNotBlank() && s.end.isNotBlank()) Weekdays.time(s.start) + " – " + Weekdays.time(s.end) else ""
        if (s.kind == ChamberStateKind.NOW) return listOf(tr("In chamber now", "এখন চেম্বারে"), time).filter { it.isNotBlank() }.joinToString(" · ")
        val day = when (d) {
            t0 -> tr("Today", "আজ")
            t0 + DAY -> tr("Tomorrow", "আগামীকাল")
            else -> Weekdays.short(weekday(d))
        }
        val dm = Rules.formatDate(d).substringBeforeLast(' ')
        return "$day ($dm)" + if (time.isNotBlank()) ", $time" else ""
    }

    /** বুকিংয়ে আগে থেকে বাছা দিন: আজ (আজকের সময় শেষ না হলে) বা পরের বসার দিন — ৬০ দিনের মধ্যে */
    fun firstBookableDay(c: Chamber, now: Long = System.currentTimeMillis()): Long? {
        val t0 = dhakaToday(now)
        val t = hhmm(now)
        for (i in 0..60) {
            val d = t0 + i * DAY
            if (!c.openOn(d)) continue
            if (i == 0) {
                val sl = slotsOn(c, d)
                if (sl.isNotEmpty() && sl.all { timed(it) && it.start < it.end && t >= it.end }) continue
            }
            return d
        }
        return null
    }

    /** ছোট ফি: "৳৫০০" / "ফি নেই"; বোঝা না গেলে লেখাটাই */
    fun feeShort(c: Chamber): String {
        if (c.noFee) return tr("No fee", "ফি নেই")
        val ascii = c.fee.map { ch -> if (ch in '০'..'৯') ('0' + (ch - '০')) else ch }.joinToString("").replace(Regex("(\\d),(?=\\d{3})"), "$1")
        val n = Regex("\\d+").find(ascii)?.value?.toLongOrNull() ?: return c.fee
        return "৳" + num(String.format(java.util.Locale.US, "%,d", n))
    }

    /** তালিকা/শিটের ছোট নাম (বন্ধনীর লেখা বাদ) আর উপজেলা (না থাকলে জেলা) */
    fun shortName(c: Chamber): String = c.label.replace(Regex("\\s*\\([^)]*\\)\\s*"), " ").trim().ifBlank { c.label }
    fun place(c: Chamber): String = if (c.online) tr("Online (video)", "অনলাইন (ভিডিও)") else Areas.upazila(c.upazilaId)?.label ?: Districts.label(c.district).ifBlank { c.address }
}

object Weekdays {
    /** বাংলাদেশে সপ্তাহ শুরু শনিবার */
    val ORDER = listOf(6, 0, 1, 2, 3, 4, 5)

    fun short(d: Int): String = when (d) {
        0 -> tr("Sun", "রবি")
        1 -> tr("Mon", "সোম")
        2 -> tr("Tue", "মঙ্গল")
        3 -> tr("Wed", "বুধ")
        4 -> tr("Thu", "বৃহস্পতি")
        5 -> tr("Fri", "শুক্র")
        else -> tr("Sat", "শনি")
    }

    /** "17:00" → "বিকাল ৫:০০" */
    fun time(hhmm: String): String {
        val p = hhmm.split(":")
        val h = p.getOrNull(0)?.toIntOrNull() ?: return hhmm
        val m = p.getOrNull(1)?.toIntOrNull() ?: 0
        return Rules.formatTime(h, m)
    }

    fun hhmm(h: Int, m: Int): String = "%02d:%02d".format(java.util.Locale.US, h, m)
}

object Specialties {
    val DOCTOR = listOf(
        "medicine", "pediatrics", "gynecology", "cardiology", "orthopedics", "ent", "eye", "dermatology", "neurology",
        "psychiatry", "surgery", "nephrology", "urology", "gastro", "oncology", "dental", "endocrinology", "chest",
        "physical_medicine", "general",
    )
    val NURSE = listOf("general_nursing", "icu", "midwife", "home_care", "pediatric_nursing", "psychiatric_nursing")
    val NUTRITIONIST = listOf("clinical_nutrition", "weight_diet", "diabetes_diet", "child_nutrition", "pregnancy_nutrition", "kidney_diet", "sports_nutrition")
    val TRAINER = listOf("weight_loss", "strength", "yoga", "rehab_exercise", "elderly_fitness", "women_fitness", "sports_training")

    /** প্যানেলের তালিকা থাকলে সেটা (নতুন যোগ করা সহ), নইলে অ্যাপের ভেতরেরটা */
    fun of(type: String) = Catalog.visible("specialty", if (type in listOf("nurse", "nutritionist", "trainer")) type else "doctor") ?: builtIn(type)

    private fun builtIn(type: String) = when (type) {
        "nurse" -> NURSE
        "nutritionist" -> NUTRITIONIST
        "trainer" -> TRAINER
        else -> DOCTOR
    }

    fun label(code: String): String = Catalog.label("specialty", code) ?: builtInLabel(code)

    private fun builtInLabel(code: String): String = when (code) {
        "medicine" -> tr("Medicine", "মেডিসিন")
        "pediatrics" -> tr("Child (Pediatrics)", "শিশু রোগ")
        "gynecology" -> tr("Gynae & Obstetrics", "গাইনি ও প্রসূতি")
        "cardiology" -> tr("Heart (Cardiology)", "হৃদরোগ")
        "orthopedics" -> tr("Bone (Orthopedics)", "হাড় ও জোড়া")
        "ent" -> tr("ENT", "নাক-কান-গলা")
        "eye" -> tr("Eye", "চক্ষু")
        "dermatology" -> tr("Skin (Dermatology)", "চর্ম ও যৌন")
        "neurology" -> tr("Neurology", "স্নায়ুরোগ")
        "psychiatry" -> tr("Psychiatry", "মানসিক রোগ")
        "surgery" -> tr("Surgery", "সার্জারি")
        "nephrology" -> tr("Kidney (Nephrology)", "কিডনি রোগ")
        "urology" -> tr("Urology", "মূত্ররোগ")
        "gastro" -> tr("Gastroenterology", "পরিপাকতন্ত্র ও লিভার")
        "oncology" -> tr("Cancer (Oncology)", "ক্যান্সার")
        "dental" -> tr("Dental", "দন্ত")
        "endocrinology" -> tr("Diabetes & Hormone", "ডায়াবেটিস ও হরমোন")
        "chest" -> tr("Chest & Lung", "বক্ষব্যাধি")
        "physical_medicine" -> tr("Physical medicine", "ফিজিক্যাল মেডিসিন")
        "general" -> tr("General practitioner", "সাধারণ চিকিৎসক")
        "general_nursing" -> tr("General nursing", "সাধারণ নার্সিং")
        "icu" -> tr("ICU / Critical care", "আইসিইউ")
        "midwife" -> tr("Midwife", "মিডওয়াইফ")
        "home_care" -> tr("Home care", "হোম কেয়ার")
        "pediatric_nursing" -> tr("Child nursing", "শিশু নার্সিং")
        "psychiatric_nursing" -> tr("Mental health nursing", "মানসিক স্বাস্থ্য নার্সিং")
        "clinical_nutrition" -> tr("Clinical nutrition", "ক্লিনিক্যাল পুষ্টি")
        "weight_diet" -> tr("Weight control diet", "ওজন নিয়ন্ত্রণের খাদ্য")
        "diabetes_diet" -> tr("Diabetes diet", "ডায়াবেটিসের খাদ্য")
        "child_nutrition" -> tr("Child nutrition", "শিশুর পুষ্টি")
        "pregnancy_nutrition" -> tr("Pregnancy nutrition", "গর্ভকালীন পুষ্টি")
        "kidney_diet" -> tr("Kidney diet", "কিডনি রোগের খাদ্য")
        "sports_nutrition" -> tr("Sports nutrition", "খেলাধুলার পুষ্টি")
        "weight_loss" -> tr("Weight loss", "ওজন কমানো")
        "strength" -> tr("Strength training", "শক্তি বাড়ানো")
        "yoga" -> tr("Yoga", "যোগব্যায়াম")
        "rehab_exercise" -> tr("Rehab exercise", "রিহ্যাব ব্যায়াম")
        "elderly_fitness" -> tr("Elderly fitness", "বয়স্কদের ব্যায়াম")
        "women_fitness" -> tr("Women's fitness", "নারীদের ফিটনেস")
        "sports_training" -> tr("Sports training", "খেলাধুলার প্রশিক্ষণ")
        else -> code
    }
}

data class Appointment(
    val id: String,
    /** "patient" (আমি রোগী) বা "provider" (আমি ডাক্তার/নার্স) */
    val role: String,
    val otherName: String,
    val type: String,
    val chamber: String,
    val chamberBn: String,
    val room: String,
    val start: String,
    val serial: Int,
    val schedule: String,
    val date: Long,
    val note: String,
    /** "pending", "confirmed", "declined", "cancelled" */
    val status: String,
    val providerNote: String,
    /** নিশ্চিত হলে অপরজনের ফোন */
    val otherPhone: String,
    val createdAt: Long,
    /** একই রোগী চেনার জন্য (সার্ভারের সংকেত; নাম নয়) */
    val otherKey: String = "",
    /** অনলাইন (ভিডিও) চেম্বারের অ্যাপয়েন্টমেন্ট আর তার ফি — OnlineChamber.kt */
    val online: Boolean = false,
    val pay: OnlinePay = OnlinePay(),
) {
    val chamberLabel: String get() = if (isBangla && chamberBn.isNotBlank()) chamberBn else chamber
}

/** খোঁজার ফিল্টার */
data class CareFilter(
    val district: String? = null,
    val upazilaId: Int? = null,
    val unionId: Int? = null,
    val hospital: Hospital? = null,
    val specialty: String? = null,
    // ২.৩৩.০: "today" / "tomorrow" / null, এখন চেম্বারে, ফি (টাকা পর্যন্ত), হাসপাতালের নাম, লিঙ্গ ("male"/"female")
    val day: String? = null,
    val openNow: Boolean = false,
    val maxFee: Int? = null,
    val hospitalQ: String = "",
    val gender: String? = null,
    /** শুধু অনলাইনে (ভিডিও) দেখেন এমন ডাক্তার */
    val online: Boolean = false,
)

/** তালিকার সাথে সার্ভারের বিশেষজ্ঞতার চিপ (এই এলাকায় যেগুলো আছে, সংখ্যাসহ) আর লিঙ্গ লেখা আছে কি না */
data class ProviderFacets(val specialties: List<Pair<String, Int>>, val gender: Boolean)
data class ProviderPage(val items: List<Provider>, val facets: ProviderFacets?)

/** শেষবারের ছাঁকনি (ধরনপ্রতি, এই ফোনে) — এলাকা আর হাসপাতাল ছাড়া (এলাকা প্রোফাইল থেকে শুরু হয়, আগের মতো) */
object DoctorFilterPrefs {
    private const val FILE = "doctor_filters"
    val FEE_STEPS = listOf(300, 500, 1000)

    fun load(context: android.content.Context, type: String, base: CareFilter): CareFilter = runCatching {
        val o = JSONObject(context.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE).getString(type, null) ?: return base)
        base.copy(
            specialty = o.optString("specialty").takeIf { it.isNotBlank() },
            day = o.optString("day").takeIf { it == "today" || it == "tomorrow" },
            openNow = o.optBoolean("openNow"),
            maxFee = o.optInt("maxFee").takeIf { it in FEE_STEPS },
            hospitalQ = o.optString("hospitalQ").take(60),
            gender = o.optString("gender").takeIf { it == "male" || it == "female" },
            online = o.optBoolean("online"),
        )
    }.getOrDefault(base)

    fun save(context: android.content.Context, type: String, f: CareFilter) {
        runCatching {
            val o = JSONObject().put("specialty", f.specialty ?: "").put("day", f.day ?: "").put("openNow", f.openNow)
                .put("maxFee", f.maxFee ?: 0).put("hospitalQ", f.hospitalQ).put("gender", f.gender ?: "").put("online", f.online)
            context.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE).edit().putString(type, o.toString()).apply()
        }
    }
}

object Appointments {
    private fun provider(o: JSONObject): Provider {
        val ch = o.optJSONArray("chambers") ?: JSONArray()
        return Provider(
            o.str("uid"), o.str("type"), o.str("name"), o.optJSONArray("specialties").strings(), o.str("degrees"),
            o.str("gender"), o.optBoolean("listed", true), o.optBoolean("appointments", false),
            (0 until ch.length()).map { Chamber.parse(ch.getJSONObject(it)) },
            HomeVisitSettings.parse(o.optJSONObject("homeVisit")), o.optDouble("rating", 0.0), o.optInt("ratingCount"),
            o.str("photo"),
            o.optJSONArray("matched")?.ints(),
        )
    }

    suspend fun providers(type: String, f: CareFilter, homeVisitOnly: Boolean = false): List<Provider> = providersPage(type, f, homeVisitOnly).items

    /** তালিকা + বিশেষজ্ঞতার চিপ (২.৩৩.০) */
    suspend fun providersPage(type: String, f: CareFilter, homeVisitOnly: Boolean = false): ProviderPage {
        val body = JSONObject().put("type", type).put("homeVisit", homeVisitOnly)
        if (f.hospital != null) {
            body.put("hospitalId", f.hospital.id)
        } else {
            f.district?.let { body.put("district", it) }
            f.upazilaId?.let { body.put("upazilaId", it) }
            f.unionId?.let { body.put("unionId", it) }
        }
        f.specialty?.let { body.put("specialty", it) }
        f.day?.let { body.put("day", it) }
        if (f.openNow) body.put("openNow", true)
        f.maxFee?.let { body.put("maxFee", it) }
        f.hospitalQ.trim().takeIf { it.length >= 2 }?.let { body.put("hospitalQ", it) }
        f.gender?.let { body.put("gender", it) }
        if (f.online) body.put("online", true)
        val res = careCall("providers", body)
        val arr = res.optJSONArray("items") ?: JSONArray()
        val fo = res.optJSONObject("facets")
        val facets = fo?.let { x ->
            val sp = x.optJSONArray("specialties") ?: JSONArray()
            ProviderFacets((0 until sp.length()).mapNotNull { sp.optJSONObject(it) }.map { it.str("code") to it.optInt("n") }.filter { it.first.isNotBlank() }, x.optBoolean("gender"))
        }
        return ProviderPage((0 until arr.length()).map { provider(arr.getJSONObject(it)) }, facets)
    }

    suspend fun mine(): Provider? = careCall("provider_me").optJSONObject("provider")?.let(::provider)

    suspend fun save(
        listed: Boolean,
        appointments: Boolean,
        specialties: List<String>,
        degrees: String,
        gender: String,
        chambers: List<Chamber>,
        homeVisit: HomeVisitSettings?,
    ): Int {
        // ফেরত: চেম্বার/বার বদলে সামনের কয়টা অ্যাপয়েন্টমেন্ট বাতিল হলো
        return careCall(
            "provider_save",
            JSONObject().put("listed", listed).put("appointments", appointments).put("specialties", JSONArray(specialties))
                .put("degrees", degrees).put("gender", gender).put("chambers", JSONArray(chambers.map { it.toJson() }))
                .apply { if (homeVisit != null) put("homeVisit", homeVisit.toJson()) },
        ).optInt("cancelled")
    }

    /** chamberKey: যে হাসপাতালের চেম্বার দেখে বুক করছেন — সার্ভার মিলিয়ে নেয় (চেম্বার বদলালে ভুল বুকিং হয় না) */
    suspend fun book(providerUid: String, chamberIndex: Int, date: Long, note: String, chamberKey: String? = null, memberId: String? = null) {
        careCall(
            "book",
            JSONObject().put("providerUid", providerUid).put("chamberIndex", chamberIndex).put("date", date).put("note", note)
                .apply { if (chamberKey != null) put("chamberKey", chamberKey) }
                .apply { if (memberId != null) put("memberId", memberId) },
        )
    }

    suspend fun list(): List<Appointment> {
        val arr = careCall("my_appointments").optJSONArray("items") ?: JSONArray()
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Appointment(
                o.str("id"), o.str("role"), o.str("otherName"), o.str("type"), o.str("chamber"), o.str("chamberBn"),
                o.str("room"), o.str("start"), o.optInt("serial"), o.str("schedule"), o.optLong("date"), o.str("note"), o.str("status"),
                o.str("providerNote"), o.str("otherPhone"), o.optLong("createdAt"), o.str("otherKey"),
                o.optBoolean("online"), OnlinePay.parse(o),
            )
        }
    }

    suspend fun update(id: String, action: String, providerNote: String = "", serial: Int? = null) {
        // "action" অনুরোধের নাম হিসেবে ব্যবহৃত হয়, তাই কী করতে হবে তা "op"-এ
        careCall(
            "appointment_update",
            JSONObject().put("id", id).put("op", action).put("providerNote", providerNote).apply { if (serial != null) put("serial", serial) },
        )
    }
}

// ------------------------------------------------------------
// বাসায় সেবা: যে ডাক্তার/নার্স রোগীর বাসায় যান — তালিকা আর সরাসরি ফোন
// ------------------------------------------------------------

object HomeServices {
    val NURSE = listOf(
        "injection", "saline", "cannula", "dressing", "catheter", "nebulizer", "bp_sugar", "elderly_care", "newborn_care", "post_op",
    )
    val DOCTOR = listOf("checkup", "follow_up", "elderly_care", "bedridden", "child", "palliative", "post_op")
    val RADII = listOf(3, 5, 10, 20)

    fun of(type: String) = Catalog.visible("homeService", if (type == "doctor") "doctor" else "nurse") ?: if (type == "doctor") DOCTOR else NURSE

    fun label(code: String): String = Catalog.label("homeService", code) ?: builtInLabel(code)

    private fun builtInLabel(code: String): String = when (code) {
        "injection" -> tr("Injection", "ইনজেকশন")
        "saline" -> tr("Saline", "স্যালাইন")
        "cannula" -> tr("Cannula", "ক্যানুলা")
        "dressing" -> tr("Wound dressing", "ক্ষত ড্রেসিং")
        "catheter" -> tr("Catheter", "ক্যাথেটার")
        "nebulizer" -> tr("Nebulizer", "নেবুলাইজার")
        "bp_sugar" -> tr("BP / sugar check", "প্রেসার / সুগার মাপা")
        "elderly_care" -> tr("Elderly care", "বয়স্ক রোগী")
        "newborn_care" -> tr("Newborn care", "নবজাতকের যত্ন")
        "post_op" -> tr("After surgery", "অপারেশনের পরে")
        "checkup" -> tr("Check-up at home", "বাসায় রোগী দেখা")
        "follow_up" -> tr("Follow-up", "ফলো-আপ")
        "bedridden" -> tr("Bedridden patient", "শয্যাশায়ী রোগী")
        "child" -> tr("Child patient", "শিশু রোগী")
        "palliative" -> tr("Palliative care", "প্যালিয়েটিভ কেয়ার")
        else -> code
    }
}

/** তালিকার একজন ডাক্তার/নার্স (নিজের লোকেশন নয়, শুধু দূরত্ব) */
data class HomePro(
    val uid: String,
    val type: String,
    val name: String,
    val specialties: List<String>,
    val degrees: String,
    val gender: String,
    val services: List<String>,
    val radiusKm: Int,
    val upazilaIds: List<Int>,
    val schedule: String,
    val fee: String,
    val phone: String,
    val km: Double?,
    val rating: Double,
    val ratingCount: Int,
    /** আগে অ্যাপ থেকে ফোন করেছি (রেটিং দেওয়া যায়) */
    val called: Boolean,
    val myRating: Int,
    /** রেটিং এখন দেওয়া যায় (প্রথম ফোনের ১ ঘণ্টা পর) */
    val canRate: Boolean = false,
    val noFee: Boolean = false,
    val photo: String = "",
    val times: List<ChamberTime> = emptyList(),
) {
    /** বার আর সময় (প্রতিটা ভাগ আলাদা লাইনে), তারপর নোট */
    val timeText: String get() = (times.map { it.text } + schedule).filter { it.isNotBlank() }.joinToString("\n")

    val feeText: String get() = if (noFee) tr("No fee", "ফি নেই") else if (fee.isNotBlank()) tr("Fee: ", "ফি: ") + fee else ""

    /** কোথায় যান: "৫ কিমির মধ্যে · দেবিদ্বার, বরুড়া" */
    val coverage: String
        get() {
            val ups = upazilaIds.mapNotNull { Areas.upazila(it)?.label }
            return (listOf(tr("Within $radiusKm km", "${num(radiusKm)} কিমির মধ্যে")) + ups).joinToString(" · ")
        }
}

object HomeCare {
    private fun parse(o: JSONObject) = HomePro(
        o.str("uid"), o.str("type"), o.str("name"), o.optJSONArray("specialties").strings(), o.str("degrees"), o.str("gender"),
        o.optJSONArray("services").strings(), o.optInt("radiusKm", 5), o.optJSONArray("upazilaIds").ints(), o.str("schedule"),
        o.str("fee"), o.str("phone"), if (o.isNull("km") || !o.has("km")) null else o.optDouble("km"),
        o.optDouble("rating", 0.0), o.optInt("ratingCount"), o.optBoolean("called"), o.optInt("myRating"),
        o.optBoolean("canRate"), o.optBoolean("noFee"), o.str("photo"), parseTimes(o.optJSONArray("times")),
    )

    /** lat/lng দিলে নিজের কাছে; নইলে বেছে নেওয়া জেলা/উপজেলায় */
    suspend fun list(type: String, lat: Double?, lng: Double?, district: String?, upazilaId: Int?, service: String?, female: Boolean): List<HomePro> {
        val body = JSONObject().put("type", type).put("female", female)
        if (lat != null && lng != null) body.put("lat", lat).put("lng", lng)
        district?.let { body.put("district", it) }
        upazilaId?.let { body.put("upazilaId", it) }
        service?.let { body.put("service", it) }
        val arr = careCall("home_providers", body).optJSONArray("items") ?: JSONArray()
        return (0 until arr.length()).map { parse(arr.getJSONObject(it)) }
    }

    suspend fun called(uid: String) {
        careCall("home_called", JSONObject().put("uid", uid))
    }

    suspend fun rate(uid: String, rating: Int, comment: String) {
        careCall("home_rate", JSONObject().put("uid", uid).put("rating", rating).put("comment", comment))
    }

    /** নিজের হিসাব: ফোন, রেটিং, মন্তব্য, রিপোর্ট */
    suspend fun stats(): JSONObject = careCall("home_stats")
}
