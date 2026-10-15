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
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        fun score(bn: String, en: String): Int = when {
            bn.startsWith(q) || en.startsWith(q, true) -> 0
            bn.contains(q) || en.contains(q, true) -> 1
            else -> -1
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

    suspend fun adminRequests(): List<HospitalRequestItem> {
        val arr = careCall("admin_hospital_requests").optJSONArray("items") ?: JSONArray()
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val d = o.optJSONArray("possibleDuplicates") ?: JSONArray()
            HospitalRequestItem(parse(o), o.optInt("count", 1), (0 until d.length()).map { i -> parse(d.getJSONObject(i)) })
        }
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
) {
    val label: String get() = if (isBangla && hospitalNameBn.isNotBlank()) hospitalNameBn else hospitalName
    val area: String get() = Areas.label(district, upazilaId, unionId)

    /** "শনি, রবি, সোম · বিকাল ৫:০০ – রাত ৯:০০" (+ নোট) */
    val timeText: String
        get() {
            val parts = mutableListOf<String>()
            if (days.isNotEmpty()) parts += Weekdays.ORDER.filter { it in days }.joinToString(", ") { Weekdays.short(it) }
            if (start.isNotBlank() && end.isNotBlank()) parts += Weekdays.time(start) + " – " + Weekdays.time(end)
            if (schedule.isNotBlank()) parts += schedule
            return parts.joinToString(" · ")
        }

    /** এই তারিখে (UTC মধ্যরাত) বসেন কি না */
    fun openOn(dateUtcMidnight: Long): Boolean {
        if (days.isEmpty()) return true
        val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        c.timeInMillis = dateUtcMidnight
        return (c.get(java.util.Calendar.DAY_OF_WEEK) - 1) in days
    }

    fun toJson(): JSONObject = JSONObject().put("hospitalId", hospitalId).put("room", room).put("schedule", schedule)
        .put("fee", fee).put("appointments", appointments).put("serialPhone", serialPhone)
        .put("days", JSONArray(days)).put("start", start).put("end", end)

    companion object {
        fun parse(o: JSONObject) = Chamber(
            if (o.isNull("hospitalId")) null else o.optString("hospitalId"), o.str("hospitalName"), o.str("hospitalNameBn"),
            o.str("district"), o.intOrNull("upazilaId"), o.intOrNull("unionId"), o.str("room"), o.str("schedule"),
            o.str("fee"), o.optBoolean("appointments"), o.str("serialPhone"),
            o.optJSONArray("days").ints(), o.str("start"), o.str("end"),
        )

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
) {
    fun toJson(): JSONObject = JSONObject().put("enabled", enabled).put("termsAccepted", enabled).put("radiusKm", radiusKm)
        .put("upazilaIds", JSONArray(upazilaIds)).put("services", JSONArray(services)).put("schedule", schedule).put("fee", fee)
        .put("phone", phone)

    companion object {
        fun parse(o: JSONObject?) = if (o == null) null else HomeVisitSettings(
            o.optBoolean("enabled"), o.optInt("radiusKm", 5), o.optJSONArray("upazilaIds").ints(),
            o.optJSONArray("services").strings(), o.str("schedule"), o.str("fee"), o.str("phone"),
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
) {
    val canBook: Boolean get() = appointments && chambers.any { it.appointments }
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

    fun of(type: String) = if (type == "nurse") NURSE else DOCTOR

    fun label(code: String): String = when (code) {
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
)

object Appointments {
    private fun provider(o: JSONObject): Provider {
        val ch = o.optJSONArray("chambers") ?: JSONArray()
        return Provider(
            o.str("uid"), o.str("type"), o.str("name"), o.optJSONArray("specialties").strings(), o.str("degrees"),
            o.str("gender"), o.optBoolean("listed", true), o.optBoolean("appointments", false),
            (0 until ch.length()).map { Chamber.parse(ch.getJSONObject(it)) },
            HomeVisitSettings.parse(o.optJSONObject("homeVisit")), o.optDouble("rating", 0.0), o.optInt("ratingCount"),
        )
    }

    suspend fun providers(type: String, f: CareFilter, homeVisitOnly: Boolean = false): List<Provider> {
        val body = JSONObject().put("type", type).put("homeVisit", homeVisitOnly)
        if (f.hospital != null) {
            body.put("hospitalId", f.hospital.id)
        } else {
            f.district?.let { body.put("district", it) }
            f.upazilaId?.let { body.put("upazilaId", it) }
            f.unionId?.let { body.put("unionId", it) }
        }
        f.specialty?.let { body.put("specialty", it) }
        val arr = careCall("providers", body).optJSONArray("items") ?: JSONArray()
        return (0 until arr.length()).map { provider(arr.getJSONObject(it)) }
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
    ) {
        careCall(
            "provider_save",
            JSONObject().put("listed", listed).put("appointments", appointments).put("specialties", JSONArray(specialties))
                .put("degrees", degrees).put("gender", gender).put("chambers", JSONArray(chambers.map { it.toJson() }))
                .apply { if (homeVisit != null) put("homeVisit", homeVisit.toJson()) },
        )
    }

    suspend fun book(providerUid: String, chamberIndex: Int, date: Long, note: String) {
        careCall("book", JSONObject().put("providerUid", providerUid).put("chamberIndex", chamberIndex).put("date", date).put("note", note))
    }

    suspend fun list(): List<Appointment> {
        val arr = careCall("my_appointments").optJSONArray("items") ?: JSONArray()
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Appointment(
                o.str("id"), o.str("role"), o.str("otherName"), o.str("type"), o.str("chamber"), o.str("chamberBn"),
                o.str("room"), o.str("start"), o.optInt("serial"), o.str("schedule"), o.optLong("date"), o.str("note"), o.str("status"),
                o.str("providerNote"), o.str("otherPhone"), o.optLong("createdAt"),
            )
        }
    }

    suspend fun update(id: String, action: String, providerNote: String = "") {
        // "action" অনুরোধের নাম হিসেবে ব্যবহৃত হয়, তাই কী করতে হবে তা "op"-এ
        careCall("appointment_update", JSONObject().put("id", id).put("op", action).put("providerNote", providerNote))
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

    fun of(type: String) = if (type == "doctor") DOCTOR else NURSE

    fun label(code: String): String = when (code) {
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
) {
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
