package com.lifolo.appointments

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lifolo.admin.kernel.Catalog
import com.lifolo.admin.kernel.ListingRules
import com.lifolo.common.DhakaTime
import com.lifolo.common.Js
import com.lifolo.common.Phones
import com.lifolo.common.Places
import java.text.Normalizer

/** one session of a chamber / home visit: weekdays 0–6 (Sun–Sat), "HH:MM" */
data class Slot(val days: List<Int> = emptyList(), val start: String = "", val end: String = "") {
    fun toMap(): Map<String, Any?> = linkedMapOf("days" to days, "start" to start, "end" to end)
}

/**
 * One entry of `providers.chambers[]` exactly as Node stores it. [legacy] = converted from an old single-chamber
 * document (care.js chambersOf), which has no days/start/end/noFee fields.
 */
data class Ch(
    val hospitalId: String? = null,
    val hospitalName: String = "",
    val hospitalNameBn: String = "",
    val district: String? = null,
    val upazilaId: Int? = null,
    val unionId: Int? = null,
    val custom: Boolean = false,
    val address: String? = null,
    val room: String = "",
    val days: List<Int>? = null,
    val start: String? = null,
    val end: String? = null,
    val times: List<Slot>? = null,
    val schedule: String = "",
    val noFee: Boolean? = null,
    val fee: String = "",
    val appointments: Boolean = false,
    val serialPhone: String = "",
    val patientMin: Int? = null,
    val legacy: Boolean = false,
) {
    fun toMap(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["hospitalId"] = hospitalId
        m["hospitalName"] = hospitalName
        m["hospitalNameBn"] = hospitalNameBn
        if (legacy) {
            m["district"] = district ?: ""
            m["upazilaId"] = null
            m["unionId"] = null
            m["room"] = ""
            m["schedule"] = schedule
            m["fee"] = fee
            m["appointments"] = appointments
            m["serialPhone"] = ""
            return m
        }
        m["district"] = district
        m["upazilaId"] = upazilaId
        m["unionId"] = unionId
        if (custom) {
            m["custom"] = true
            m["address"] = address ?: ""
        }
        m["room"] = room
        m["days"] = days ?: emptyList<Int>()
        m["start"] = start ?: ""
        m["end"] = end ?: ""
        if (times != null) m["times"] = times.map { it.toMap() }
        m["schedule"] = schedule
        m["noFee"] = noFee ?: false
        m["fee"] = fee
        m["appointments"] = appointments
        m["serialPhone"] = serialPhone
        if (patientMin != null) m["patientMin"] = patientMin
        return m
    }

    /** chamberKey of an appointment (`hospitalId || hospitalName`) */
    val key: String get() = hospitalId?.takeIf { it.isNotEmpty() } ?: hospitalName
}

/** providers.homeVisit — [full] false = only `{enabled}` (created by suspend/block on a provider without home care) */
data class HomeVisit(
    val enabled: Boolean = false,
    val full: Boolean = true,
    val radiusKm: Int = 5,
    val upazilaIds: List<Int> = emptyList(),
    val services: List<String> = emptyList(),
    val phone: String = "",
    val schedule: String = "",
    val times: List<Slot> = emptyList(),
    val noFee: Boolean = false,
    val fee: String = "",
) {
    fun toMap(): LinkedHashMap<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["enabled"] = enabled
        if (!full) return m
        m["radiusKm"] = radiusKm
        m["upazilaIds"] = upazilaIds
        m["services"] = services
        m["phone"] = phone
        m["schedule"] = schedule
        m["times"] = times.map { it.toMap() }
        m["noFee"] = noFee
        m["fee"] = fee
        return m
    }
}

/** providers/{uid} (the fields the Node code reads), chambers already converted with chambersOf */
data class ProviderDoc(
    val uid: String = "",
    val type: String = "doctor",
    val name: String = "",
    val degrees: String = "",
    val gender: String = "",
    val specialties: List<String> = emptyList(),
    val specialty: String? = null,
    val listed: Boolean = false,
    val active: Boolean = false,
    val appointments: Boolean = false,
    val adminSuspended: Boolean = false,
    val suspendReason: String? = null,
    val badgeRejected: Boolean = false,
    val listedBefore: Boolean? = null,
    val activeBefore: Boolean? = null,
    val homeVisit: HomeVisit? = null,
    val homeVisitBlocked: Boolean = false,
    val ratingSum: Int = 0,
    val ratingCount: Int = 0,
    /** NULL = no chambers field, false = chambers[], true = legacy single chamber */
    val chamberMode: Boolean? = null,
    val chambers: List<Ch> = emptyList(),
    val photoThumb: String = "",
)

/** an error of the Node helpers: `{ error: [en, bn] }` */
data class Err(val en: String, val bn: String)

/** a value or an error (Node: `{ chambers }` / `{ error }`) */
class Parsed<T>(val value: T?, val error: Err?) {
    companion object {
        fun <T> ok(v: T) = Parsed(v, null)
        fun <T> fail(en: String, bn: String) = Parsed<T>(null, Err(en, bn))
        fun <T> fail(e: Err) = Parsed<T>(null, e)
    }
}

/** a place a chamber can be at: a hospital of the directory or a place the professional typed in */
data class Place(
    val id: String?,
    val nameEn: String,
    val nameBn: String,
    val district: String?,
    val upazilaId: Int?,
    val unionId: Int?,
    val custom: Boolean = false,
    val address: String = "",
)

/** doctor-list filters (appointments.js providers + care.js pickListFilters) */
data class ListFilter(
    val district: String? = null,
    val upazilaId: Long? = null,
    val unionId: Long? = null,
    val hospitalId: String? = null,
    val specialty: String? = null,
    val day: String? = null,
    val openNow: Boolean = false,
    val maxFee: Long? = null,
    val hospitalQ: String? = null,
    val gender: String? = null,
)

/** where a patient looks for home care: GPS and/or a chosen district/upazila */
data class HomePlace(val lat: Double? = null, val lng: Double? = null, val district: String? = null, val upazilaId: Int? = null)

/** the provider's own location (users/{uid}) for home-care matching */
data class HomeLoc(val lat: Double? = null, val lng: Double? = null, val locationSource: String? = null, val placeName: String? = null)

/**
 * care.js — the pure rules of chambers, schedules, list filters and home care (no database; unit-tested like the
 * Node rules tests).
 */
object CareRules {
    val DOCTOR_SPECIALTIES = listOf(
        "medicine", "pediatrics", "gynecology", "cardiology", "orthopedics", "ent", "eye", "dermatology",
        "neurology", "psychiatry", "surgery", "nephrology", "urology", "gastro", "oncology", "dental",
        "endocrinology", "chest", "physical_medicine", "general",
    )
    val NURSE_SPECIALTIES = listOf("general_nursing", "icu", "midwife", "home_care", "pediatric_nursing", "psychiatric_nursing")
    val NUTRITIONIST_SPECIALTIES = listOf("clinical_nutrition", "weight_diet", "diabetes_diet", "child_nutrition", "pregnancy_nutrition", "kidney_diet", "sports_nutrition")
    val TRAINER_SPECIALTIES = listOf("weight_loss", "strength", "yoga", "rehab_exercise", "elderly_fitness", "women_fitness", "sports_training")
    val HOME_SERVICES = listOf("injection", "saline", "cannula", "dressing", "catheter", "nebulizer", "bp_sugar", "elderly_care", "newborn_care", "post_op")
    val DOCTOR_HOME_SERVICES = listOf("checkup", "follow_up", "elderly_care", "bedridden", "child", "palliative", "post_op")
    val HOME_RADII = listOf(3, 5, 10, 20)
    const val MAX_CHAMBERS = 3
    const val MAX_CHAMBER_TIMES = 4
    const val DAY_MS = 24L * 60 * 60 * 1000
    private val TIME_RE = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
    private const val WEEK_MIN = 7 * 1440

    // ------------------------------------------------------------------ small helpers

    fun str(v: JsonNode?, max: Int): String = ListingRules.str(Js.string(v), max)

    /** care.js phone: digits and + only, 15 characters */
    fun phone(v: JsonNode?): String = Js.string(v)?.filter { it in '0'..'9' || it == '+' }?.take(15) ?: ""

    fun specialtiesFor(type: String?, catalog: Catalog): List<String> {
        val fromCatalog = catalog.codes("specialty", if (type in listOf("nurse", "nutritionist", "trainer")) type!! else "doctor")
        if (fromCatalog.isNotEmpty()) return fromCatalog
        return when (type) {
            "nurse" -> NURSE_SPECIALTIES
            "nutritionist" -> NUTRITIONIST_SPECIALTIES
            "trainer" -> TRAINER_SPECIALTIES
            else -> DOCTOR_SPECIALTIES
        }
    }

    fun homeServicesFor(type: String?, catalog: Catalog): List<String> {
        val fromCatalog = catalog.codes("homeService", if (type == "doctor") "doctor" else "nurse")
        if (fromCatalog.isNotEmpty()) return fromCatalog
        return if (type == "doctor") DOCTOR_HOME_SERVICES else HOME_SERVICES
    }

    /** care.js pickCodes on a JSON value */
    fun pickCodes(v: JsonNode?, allowed: Collection<String>, max: Int): List<String> {
        if (v == null || !v.isArray) return emptyList()
        return v.filter { it.isTextual && it.textValue() in allowed }.map { it.textValue() }.distinct().take(max)
    }

    private fun isTime(n: JsonNode?): String? = Js.string(n)?.takeIf { TIME_RE.matches(it) }

    /** integers 0–6 of a JSON array, unique, sorted */
    private fun weekdays(n: JsonNode?): List<Int> {
        if (n == null || !n.isArray) return emptyList()
        return n.mapNotNull { Js.integer(it) }.filter { it in 0..6 }.map { it.toInt() }.distinct().sorted()
    }

    /** one raw session: days/start/end JSON values */
    class SlotInput(val days: JsonNode?, val start: JsonNode?, val end: JsonNode?)

    private fun slotInputs(raw: JsonNode): List<SlotInput> = raw.map { t ->
        val o = t as? ObjectNode
        SlotInput(o?.get("days"), o?.get("start"), o?.get("end"))
    }

    /** care.js parseTimes (raw = a JSON array; anything else → no sessions) */
    fun parseTimes(raw: JsonNode?, daysErr: Err, timeErr: Err): Parsed<List<Slot>> {
        if (raw == null || !raw.isArray) return Parsed.ok(emptyList())
        return parseSlots(slotInputs(raw), daysErr, timeErr)
    }

    fun parseSlots(raw: List<SlotInput>, daysErr: Err, timeErr: Err): Parsed<List<Slot>> {
        if (raw.size > MAX_CHAMBER_TIMES) return Parsed.fail("Up to 4 time slots", "সর্বোচ্চ ৪টা সময়")
        val times = ArrayList<Slot>()
        for (t in raw) {
            val td = weekdays(t.days)
            val ts = isTime(t.start) ?: ""
            val te = isTime(t.end) ?: ""
            if (td.isEmpty()) return Parsed.fail(daysErr)
            if (ts.isEmpty() || te.isEmpty() || te == ts) return Parsed.fail(timeErr)
            times.add(Slot(td, ts, te))
        }
        return Parsed.ok(times)
    }

    private fun toMin(t: String): Int = (t.take(2).toIntOrNull() ?: 0) * 60 + (t.drop(3).take(2).toIntOrNull() ?: 0)

    /** minutes of the week a session covers; past midnight (end ≤ start) it runs into the next day */
    fun weekIntervals(t: Slot): List<IntArray> {
        val out = ArrayList<IntArray>()
        val s = toMin(t.start)
        var e = toMin(t.end)
        if (e <= s) e += 1440
        for (d in t.days) {
            val a = d * 1440 + s
            val b = d * 1440 + e
            if (b <= WEEK_MIN) {
                out.add(intArrayOf(a, b))
            } else {
                out.add(intArrayOf(a, WEEK_MIN))
                out.add(intArrayOf(0, b - WEEK_MIN))
            }
        }
        return out
    }

    /** care.js timeClash: the two chambers whose sessions overlap (same object twice = inside one chamber), else null */
    fun timeClash(chambers: List<Ch>): Pair<Ch, Ch>? {
        class Piece(val c: Ch, val iv: IntArray)
        val all = ArrayList<Piece>()
        for (c in chambers) for (t in chamberTimes(c)) for (iv in weekIntervals(t)) all.add(Piece(c, iv))
        val sorted = all.sortedBy { it.iv[0] }
        for (i in 1 until sorted.size) {
            for (j in i - 1 downTo 0) {
                if (sorted[j].iv[1] > sorted[i].iv[0]) return sorted[j].c to sorted[i].c
            }
        }
        return null
    }

    /** care.js customPlace: a place the professional typed (name, district, address) */
    fun customPlace(c: ObjectNode?): Parsed<Place> {
        val name = str(c?.get("placeName"), 100)
        val address = str(c?.get("address"), 150)
        val district = Js.string(c?.get("district"))
        if (name.length < 3) return Parsed.fail("Write the name of the place", "জায়গার নাম লিখুন")
        if (!Places.isDistrict(district)) return Parsed.fail("Choose the district", "জেলা বেছে নিন")
        if (address.length < 5) return Parsed.fail("Write the address", "ঠিকানা লিখুন")
        val u = Js.integer(c?.get("upazilaId"))?.let { id -> if (id in Int.MIN_VALUE..Int.MAX_VALUE) Places.UPAZILA[id.toInt()] else null }
        return Parsed.ok(
            Place(
                id = null, nameEn = name, nameBn = name, district = district,
                upazilaId = if (u != null && u.district == district) u.id else null, unionId = null, custom = true, address = address,
            ),
        )
    }

    /** care.js parseChambers: the chambers the app sent, checked; [hospitals] = id → hospital (read before) */
    fun parseChambers(list: JsonNode?, hospitals: Map<String, Place>, allowCustom: Boolean = false): Parsed<List<Ch>> {
        if (list == null || !list.isArray || list.size() == 0) return Parsed.fail("Add at least one chamber", "অন্তত একটা চেম্বার যোগ করুন")
        if (list.size() > MAX_CHAMBERS) return Parsed.fail("Up to 3 chambers", "সর্বোচ্চ ৩টা চেম্বার")
        val out = ArrayList<Ch>()
        for (c in list) {
            val co = c as? ObjectNode
            val truthy = Js.truthy(c)
            var h: Place? = if (truthy) Js.string(co?.get("hospitalId"))?.let { hospitals[it] } else null
            if (h == null && truthy && !Js.truthy(co?.get("hospitalId")) && allowCustom) {
                val place = customPlace(co)
                if (place.error != null) return Parsed.fail(place.error)
                h = place.value
            }
            if (h == null) return Parsed.fail("Choose the hospital from the list", "তালিকা থেকে হাসপাতাল বেছে নিন")
            val timesNode = co?.get("times")
            val raw = if (timesNode != null && timesNode.isArray && timesNode.size() > 0) {
                slotInputs(timesNode)
            } else {
                listOf(SlotInput(co?.get("days"), co?.get("start"), co?.get("end")))
            }
            val parsedTimes = parseSlots(
                raw,
                Err("Choose the days for each chamber", "প্রতিটা চেম্বারে কোন কোন বার বসেন বেছে নিন"),
                Err("Set the start and end time for each chamber", "প্রতিটা চেম্বারে শুরু আর শেষের সময় দিন"),
            )
            if (parsedTimes.error != null) return Parsed.fail(parsedTimes.error)
            val times = parsedTimes.value!!
            val days = times.flatMap { it.days }.distinct().sorted()
            val noFee = co?.get("noFee")?.let { it.isBoolean && it.booleanValue() } ?: false
            val pm = Js.integer(co?.get("patientMin"))?.takeIf { it in 1..120 }?.toInt()
            out.add(
                Ch(
                    hospitalId = h.id,
                    hospitalName = h.nameEn,
                    hospitalNameBn = h.nameBn,
                    district = h.district,
                    upazilaId = h.upazilaId?.takeIf { it != 0 },
                    unionId = h.unionId?.takeIf { it != 0 },
                    custom = h.custom,
                    address = if (h.custom) h.address else null,
                    room = str(co?.get("room"), 60),
                    days = days,
                    start = times[0].start,
                    end = times[0].end,
                    times = if (times.size > 1) times else null,
                    schedule = str(co?.get("schedule"), 120),
                    noFee = noFee,
                    fee = if (noFee) "" else str(co?.get("fee"), 40),
                    appointments = co?.get("appointments")?.let { it.isBoolean && it.booleanValue() } ?: false,
                    serialPhone = phone(co?.get("serialPhone")),
                    patientMin = pm,
                ),
            )
        }
        val clash = timeClash(out)
        if (clash != null) {
            val (a, b) = clash
            val an = a.hospitalNameBn.ifEmpty { a.hospitalName }
            val bnName = b.hospitalNameBn.ifEmpty { b.hospitalName }
            return if (a === b) {
                Parsed.fail("Two times in the same chamber overlap on the same day", "\"$an\" চেম্বারে একই বারে দুটো সময় মিলে যাচ্ছে")
            } else {
                Parsed.fail(
                    "${a.hospitalName} and ${b.hospitalName} overlap on the same day and time",
                    "\"$an\" আর \"$bnName\" একই বারে একই সময়ে পড়েছে — একসাথে দুই জায়গায় বসা যায় না",
                )
            }
        }
        return Parsed.ok(out)
    }

    /** care.js chamberTimes: the sessions (`times[]`, else one from days/start/end) */
    fun chamberTimes(c: Ch?): List<Slot> {
        if (c == null) return emptyList()
        if (!c.times.isNullOrEmpty()) return c.times
        return listOf(Slot(c.days ?: emptyList(), c.start ?: "", c.end ?: ""))
    }

    /** weekday (0 = Sunday) of a UTC-midnight date */
    fun weekday(dateUtcMidnight: Long): Int = Math.floorMod(Math.floorDiv(dateUtcMidnight, DAY_MS) + 4, 7L).toInt()

    fun chamberTimesOn(c: Ch?, dateUtcMidnight: Long): List<Slot> {
        val day = weekday(dateUtcMidnight)
        return chamberTimes(c).filter { it.days.isEmpty() || day in it.days }
    }

    fun chamberOpenOn(c: Ch?, dateUtcMidnight: Long): Boolean = c != null && chamberTimesOn(c, dateUtcMidnight).isNotEmpty()

    private val DAY_EN = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    private val DAY_ORDER = listOf(6, 0, 1, 2, 3, 4, 5)

    /** care.js scheduleText: "Sat, Sun · 17:00–21:00 · note" for old apps */
    fun scheduleText(c: Ch?): String {
        if (c == null) return ""
        fun slot(t: Slot): String {
            val parts = ArrayList<String>()
            if (t.days.isNotEmpty()) parts.add(DAY_ORDER.filter { it in t.days }.joinToString(", ") { DAY_EN[it] })
            if (t.start.isNotEmpty() && t.end.isNotEmpty()) parts.add("${t.start}–${t.end}")
            return parts.joinToString(" · ")
        }
        val parts = listOf(chamberTimes(c).map { slot(it) }.filter { it.isNotEmpty() }.joinToString("; ")).filter { it.isNotEmpty() }.toMutableList()
        if (c.schedule.isNotEmpty()) parts.add(c.schedule)
        return parts.joinToString(" · ")
    }

    fun isListed(p: ProviderDoc): Boolean = p.listed

    fun takesAppointments(p: ProviderDoc): Boolean = p.appointments

    // ------------------------------------------------------------------ list filters (2.33.0)

    private fun chamberMatches(c: Ch, f: ListFilter): Boolean {
        if (!f.hospitalId.isNullOrEmpty()) return c.hospitalId == f.hospitalId
        if (!f.district.isNullOrEmpty() && c.district != f.district) return false
        if (f.upazilaId != null && f.upazilaId != 0L && c.upazilaId?.toLong() != f.upazilaId) return false
        if (f.unionId != null && f.unionId != 0L && c.unionId?.toLong() != f.unionId) return false
        return true
    }

    private const val BN_DIGITS = "০১২৩৪৫৬৭৮৯"
    private val THOUSANDS = Regex("(\\d),(?=\\d{3})")
    private val DIGITS = Regex("\\d+")

    /** care.js feeAmount: "৫০০ টাকা" → 500, "৳1,000" → 1000, "৩০০–৫০০" → 300; no fee → 0; unknown → null */
    fun feeAmount(c: Ch?): Double? {
        if (c == null) return null
        if (c.noFee == true) return 0.0
        val ascii = c.fee.map { ch -> BN_DIGITS.indexOf(ch).let { if (it >= 0) ('0' + it) else ch } }.joinToString("")
        val t = THOUSANDS.replace(ascii, "$1")
        val m = DIGITS.find(t) ?: return null
        return m.value.toDouble()
    }

    private fun slotOver(t: Slot, hhmm: String): Boolean =
        t.start.isNotEmpty() && t.end.isNotEmpty() && t.start < t.end && hhmm >= t.end

    fun dhakaHHMM(now: Long): String = ListingRules.dhakaHHMM(now)

    /** care.js chamberNowOpen: inside today's session, or yesterday's past-midnight session still running */
    fun chamberNowOpen(c: Ch, now: Long): Boolean {
        val today = DhakaTime.todayUtcMidnight(now)
        val t = dhakaHHMM(now)
        fun ok(x: Slot) = TIME_RE.matches(x.start) && TIME_RE.matches(x.end)
        for (x in chamberTimesOn(c, today)) {
            if (!ok(x)) continue
            if (if (x.start < x.end) t >= x.start && t < x.end else t >= x.start) return true
        }
        for (x in chamberTimesOn(c, today - DAY_MS)) {
            if (ok(x) && x.start >= x.end && t < x.end) return true
        }
        return false
    }

    fun chamberToday(c: Ch, now: Long): Boolean {
        val today = DhakaTime.todayUtcMidnight(now)
        if (chamberOpenOn(c, today)) {
            val slots = chamberTimesOn(c, today)
            val t = dhakaHHMM(now)
            if (slots.isEmpty() || !slots.all { slotOver(it, t) }) return true
        }
        return chamberNowOpen(c, now)
    }

    fun chamberTomorrow(c: Ch, now: Long): Boolean = chamberOpenOn(c, DhakaTime.todayUtcMidnight(now) + DAY_MS)

    private const val JS_SPACE = "\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"
    private val PUNCT = Regex("[()\\[\\]{},.;:'\"\\-–—/]")
    private val SPACES = Regex("[$JS_SPACE]+")

    /** care.js nameNorm: NFC, lower case, punctuation → space, single spaces */
    fun nameNorm(v: String?): String {
        val s = Normalizer.normalize(v ?: "", Normalizer.Form.NFC).lowercase()
        return ListingRules.jsTrim(SPACES.replace(PUNCT.replace(s, " "), " "))
    }

    fun chamberPasses(c: Ch, f: ListFilter, now: Long): Boolean {
        if (!chamberMatches(c, f)) return false
        val q = f.hospitalQ
        if (!q.isNullOrEmpty() && listOf(c.hospitalName, c.hospitalNameBn, c.address).none { nameNorm(it).contains(q) }) return false
        if (f.day == "today" && !chamberToday(c, now)) return false
        if (f.day == "tomorrow" && !chamberTomorrow(c, now)) return false
        if (f.openNow && !chamberNowOpen(c, now)) return false
        if (f.maxFee != null) {
            val a = feeAmount(c)
            if (a == null || a > f.maxFee) return false
        }
        return true
    }

    fun matchedChambers(p: ProviderDoc, f: ListFilter, now: Long): List<Int> =
        p.chambers.mapIndexedNotNull { i, c -> if (chamberPasses(c, f, now)) i else null }

    private fun hasChamberFilter(f: ListFilter): Boolean =
        !f.hospitalId.isNullOrEmpty() || !f.district.isNullOrEmpty() || (f.upazilaId ?: 0L) != 0L || (f.unionId ?: 0L) != 0L ||
            !f.hospitalQ.isNullOrEmpty() || !f.day.isNullOrEmpty() || f.openNow || f.maxFee != null

    fun matchesFilter(p: ProviderDoc, f: ListFilter, now: Long): Boolean {
        if (!f.specialty.isNullOrEmpty() && f.specialty !in p.specialties) return false
        if (!f.gender.isNullOrEmpty() && p.gender != f.gender) return false
        if (!hasChamberFilter(f)) return true
        return matchedChambers(p, f, now).isNotEmpty()
    }

    /** care.js pickListFilters: day, openNow, maxFee, hospitalQ, gender (bad values = not set) */
    fun pickListFilters(b: ObjectNode, base: ListFilter): ListFilter {
        val rawQ = Js.string(b.get("hospitalQ"))?.take(60) ?: ""
        val q = nameNorm(rawQ)
        val day = Js.string(b.get("day"))?.takeIf { it == "today" || it == "tomorrow" }
        val maxFee = Js.integer(b.get("maxFee"))?.takeIf { it in 1..100000 }
        val gender = Js.string(b.get("gender"))?.takeIf { it == "male" || it == "female" }
        return base.copy(
            day = day,
            openNow = b.get("openNow")?.let { it.isBoolean && it.booleanValue() } ?: false,
            maxFee = maxFee,
            hospitalQ = if (q.length >= 2) q else null,
            gender = gender,
        )
    }

    // ------------------------------------------------------------------ home care

    /** care.js parseHomeVisit: the home-care settings (the phone is shown to everyone, so it is required when on) */
    fun parseHomeVisit(raw: JsonNode?, type: String?, catalog: Catalog): Parsed<HomeVisit> {
        val h = raw as? ObjectNode
        val enabled = h?.get("enabled")?.let { it.isBoolean && it.booleanValue() } ?: false
        val services = pickCodes(h?.get("services"), homeServicesFor(type, catalog), 10)
        if (enabled && services.isEmpty()) return Parsed.fail("Choose the services you provide at home", "বাসায় কোন কোন সেবা দেন বেছে নিন")
        val phoneNode = h?.get("phone")
        val norm = Phones.normalizeBd(if (phoneNode == null) "" else Js.toStr(phoneNode))
        if (enabled && norm == null) return Parsed.fail("Enter a mobile number patients can call", "রোগীরা যে নম্বরে ফোন করবেন, সেই মোবাইল নম্বর দিন")
        val r = h?.get("radiusKm")
        val radiusKm = if (r != null && r.isNumber && Js.isInteger(r) && r.asInt() in HOME_RADII && r.asDouble() == r.asInt().toDouble()) r.asInt() else 5
        val upNode = h?.get("upazilaIds")
        val upazilaIds = if (upNode != null && upNode.isArray) {
            upNode.mapNotNull { Js.integer(it) }.distinct().take(10).map { it.toInt() }
        } else {
            emptyList()
        }
        val timesNode = h?.get("times")
        val pt = parseTimes(
            timesNode,
            Err("Choose the days you visit homes", "কোন কোন বার বাসায় যান বেছে নিন"),
            Err("Set the start and end time for home visits", "বাসায় যাওয়ার শুরু আর শেষের সময় দিন"),
        )
        if (pt.error != null) return Parsed.fail(pt.error)
        val times = pt.value!!
        if (enabled && timesNode != null && timesNode.isArray && times.isEmpty()) {
            return Parsed.fail("Set the days and time you visit homes", "কোন বার কখন বাসায় যান দিন")
        }
        if (timeClash(listOf(Ch(times = times))) != null) return Parsed.fail("Two home-visit times overlap on the same day", "বাসায় যাওয়ার দুটো সময় একই বারে মিলে যাচ্ছে")
        val noFee = h?.get("noFee")?.let { it.isBoolean && it.booleanValue() } ?: false
        return Parsed.ok(
            HomeVisit(
                enabled = enabled,
                full = true,
                radiusKm = radiusKm,
                upazilaIds = upazilaIds,
                services = services,
                phone = if (norm != null) Phones.localBd(norm) else "",
                schedule = str(h?.get("schedule"), 120),
                times = times,
                noFee = noFee,
                fee = if (noFee) "" else str(h?.get("fee"), 60),
            ),
        )
    }

    /** rules.js distanceKm */
    fun distanceKm(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        fun rad(d: Double) = d * Math.PI / 180
        val dLat = rad(bLat - aLat)
        val dLng = rad(bLng - aLng)
        val h = Math.pow(Math.sin(dLat / 2), 2.0) + Math.cos(rad(aLat)) * Math.cos(rad(bLat)) * Math.pow(Math.sin(dLng / 2), 2.0)
        return 2 * 6371 * Math.asin(Math.sqrt(h))
    }

    /** the result of homeMatch: matched, with the distance when known */
    data class HomeHit(val km: Double?)

    /** care.js homeMatch: does this professional visit the searched place? */
    fun homeMatch(p: ProviderDoc, loc: HomeLoc, place: HomePlace): HomeHit? {
        val h = p.homeVisit
        if (h == null || !h.enabled || p.homeVisitBlocked) return null
        val gps = loc.locationSource == "gps" && loc.lat != null && loc.lng != null
        val ups = h.upazilaIds
        if (place.lat != null && place.lng != null) {
            val km = if (gps) distanceKm(place.lat, place.lng, loc.lat, loc.lng) else null
            if (km != null && km <= (if (h.radiusKm != 0) h.radiusKm else 5)) return HomeHit(km)
            if (place.upazilaId != null && place.upazilaId != 0 && place.upazilaId in ups) return HomeHit(km)
            if (!gps && !place.district.isNullOrEmpty() && ups.isNotEmpty()) {
                if (ups.any { Places.UPAZILA[it]?.district == place.district }) return HomeHit(null)
            }
            return null
        }
        if (place.upazilaId != null && place.upazilaId != 0) {
            if (place.upazilaId in ups) return HomeHit(null)
            return if (gps && !place.district.isNullOrEmpty() && loc.placeName == place.district) HomeHit(null) else null
        }
        if (!place.district.isNullOrEmpty()) {
            if (ups.any { Places.UPAZILA[it]?.district == place.district }) return HomeHit(null)
            return if (gps && loc.placeName == place.district) HomeHit(null) else null
        }
        return HomeHit(null)
    }

    /** care.js chambersOf for a contracts.Chamber */
    fun toContract(index: Int, c: Ch): com.lifolo.contracts.Chamber = com.lifolo.contracts.Chamber(
        index = index,
        hospitalId = c.hospitalId,
        hospitalName = c.hospitalName,
        hospitalNameBn = c.hospitalNameBn,
        district = c.district ?: "",
        upazilaId = c.upazilaId,
        unionId = c.unionId,
        room = c.room,
        schedule = c.schedule,
        noFee = c.noFee ?: false,
        fee = c.fee,
        appointments = c.appointments,
        serialPhone = c.serialPhone,
        times = chamberTimes(c).map { com.lifolo.contracts.ChamberTime(it.days, it.start, it.end) },
    )
}
