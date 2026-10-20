package com.lifolo.blood

import com.lifolo.common.DhakaTime
import com.lifolo.common.Lang
import com.lifolo.common.Places
import com.lifolo.common.Rules
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A donor as the blood rules see them (Node: the `users/{uid}` document + its FCM tokens).
 * [hasTokens] = the user has at least one device token (Node: `fcmToken || fcmTokens.length`).
 */
data class Donor(
    val uid: String,
    val bloodGroup: String?,
    val lat: Double?,
    val lng: Double?,
    val notify: Boolean = true,
    val maxPushPerDay: Int? = null,
    val notifyGroups: List<String>? = null,
    val birthDateMillis: Long? = null,
    val weightKg: Double? = null,
    val lastDonationMillis: Long? = null,
    val maxKm: Int? = null,
    val fareAfterKm: Int? = null,
    val lang: String? = null,
    val hasTokens: Boolean = true,
    val name: String? = null,
    val phone: String? = null,
    val placeName: String? = null,
)

/** the request fields the rules read (rules.js validateRequest / isActive / pickDonors) */
interface RequestFacts {
    val requesterUid: String
    val patientGroup: String
    val bags: Int
    val hospital: String
    val placeName: String
    val lat: Double
    val lng: Double
    val neededByMillis: Long?
    val neededAtMillis: Long?
    val travelFare: Boolean
    val status: String
}

/** Port of server/lib/rules.js (the blood rules — the app's Rules.kt keeps the same numbers). */
object BloodRules {
    const val DAY_MS = Rules.DAY_MS
    const val ELIGIBILITY_DAYS = 120
    const val MAX_RESPONSES_PER_DAY = 5
    const val MAX_REQUESTS_PER_DAY = 3
    const val MAX_OPEN_REQUESTS = 2
    val RADII_KM = listOf(5, 10, 25, 50)
    const val ENOUGH_DONORS = 10
    val BLOOD_GROUPS = Rules.BLOOD_GROUPS
    const val MIN_AGE = 18
    const val MAX_AGE = 60
    const val MIN_WEIGHT_KG = 50
    const val AFTER_NEEDED_MS = 6L * 60 * 60 * 1000
    private const val DHAKA_OFFSET_MS = 6L * 60 * 60 * 1000

    /** patient group → who can give (red cells / whole blood) */
    val DONORS_FOR: Map<String, List<String>> = linkedMapOf(
        "O-" to listOf("O-"),
        "O+" to listOf("O+", "O-"),
        "A-" to listOf("A-", "O-"),
        "A+" to listOf("A+", "A-", "O+", "O-"),
        "B-" to listOf("B-", "O-"),
        "B+" to listOf("B+", "B-", "O+", "O-"),
        "AB-" to listOf("AB-", "A-", "B-", "O-"),
        "AB+" to BLOOD_GROUPS,
    )

    fun canDonate(donorGroup: String?, patientGroup: String?): Boolean =
        donorGroup != null && (DONORS_FOR[patientGroup] ?: emptyList()).contains(donorGroup)

    /** pushLimit: the donor's own daily push limit (settings), 3 when not set, 1..10 */
    fun pushLimit(maxPushPerDay: Int?): Int =
        if (maxPushPerDay == null) Rules.MAX_PUSH_PER_DAY else maxPushPerDay.coerceIn(1, Rules.MAX_PUSH_PER_DAY_LIMIT)

    /** wantsGroup: no choice, or no chosen group they can still give to → every group they can give to */
    fun wantsGroup(u: Donor, patientGroup: String): Boolean {
        val list = u.notifyGroups ?: return true
        val valid = list.filter { canDonate(u.bloodGroup, it) }
        return valid.isEmpty() || valid.contains(patientGroup)
    }

    fun isEligible(lastDonationMillis: Long?, now: Long = System.currentTimeMillis()): Boolean =
        lastDonationMillis == null || now >= lastDonationMillis + ELIGIBILITY_DAYS * DAY_MS

    /** donationRisk: 'backward' (moved back or "never"), 'short_gap' (< 120 days after the previous), else null */
    fun donationRisk(before: Long?, after: Long?): String? {
        if (before == null) return null
        if (after == null || after < before) return "backward"
        if (after > before && after - before < ELIGIBILITY_DAYS * DAY_MS) return "short_gap"
        return null
    }

    /** full years from the birth date (Dhaka calendar today) */
    fun ageYears(birthDateMillis: Long?, now: Long = System.currentTimeMillis()): Int? {
        if (birthDateMillis == null) return null
        val b = Instant.ofEpochMilli(birthDateMillis).atZone(ZoneOffset.UTC)
        val n = Instant.ofEpochMilli(now + DHAKA_OFFSET_MS).atZone(ZoneOffset.UTC)
        var age = n.year - b.year
        val beforeBirthday = n.monthValue < b.monthValue || (n.monthValue == b.monthValue && n.dayOfMonth < b.dayOfMonth)
        if (beforeBirthday) age--
        return age
    }

    /** age 18–60, weight ≥ 50 kg and 120 days since the last donation */
    fun isEligibleDonor(u: Donor?, now: Long = System.currentTimeMillis()): Boolean {
        if (u == null) return false
        val age = ageYears(u.birthDateMillis, now) ?: return false
        val w = u.weightKg ?: return false
        return age in MIN_AGE..MAX_AGE && w >= MIN_WEIGHT_KG && isEligible(u.lastDonationMillis, now)
    }

    /** a set time → 6 h after it; else the end of the needed day */
    fun activeUntil(neededByMillis: Long?, neededAtMillis: Long?): Long =
        if (neededAtMillis != null) neededAtMillis + AFTER_NEEDED_MS else (neededByMillis ?: 0) + DAY_MS

    fun isActive(status: String?, neededByMillis: Long?, neededAtMillis: Long?, now: Long = System.currentTimeMillis()): Boolean =
        status == "open" && activeUntil(neededByMillis, neededAtMillis) > now

    fun isActive(r: RequestFacts, now: Long = System.currentTimeMillis()): Boolean = isActive(r.status, r.neededByMillis, r.neededAtMillis, now)

    /** bags still needed: minus confirmed/donated donors or bags received, whichever is more */
    fun bagsLeft(bags: Int, confirmedCount: Int, bagsReceived: Int): Int {
        val done = maxOf(confirmedCount, bagsReceived)
        return maxOf((if (bags == 0) 1 else bags) - done, 0)
    }

    /** rules.js distanceKm (haversine with asin — pickDonors, emergency waves, respond) */
    fun distanceKm(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val r = 6371.0
        fun rad(d: Double) = d * PI / 180
        val dLat = rad(bLat - aLat)
        val dLng = rad(bLng - aLng)
        val h = Math.pow(sin(dLat / 2), 2.0) + cos(rad(aLat)) * cos(rad(bLat)) * Math.pow(sin(dLng / 2), 2.0)
        return 2 * r * asin(sqrt(h))
    }

    /** the Bangla names of the districts whose centre is within 70 km (≤ 30), else the nearest one */
    fun nearDistricts(lat: Double, lng: Double): List<String> {
        val list = Places.DISTRICTS.map { it to distanceKm(lat, lng, it.lat, it.lng) }.sortedBy { it.second }
        val near = list.filter { it.second <= 70 }.take(30).map { it.first.bn }
        return near.ifEmpty { listOf(list[0].first.bn) }
    }

    fun todayDhakaUtcMidnight(now: Long = System.currentTimeMillis()): Long = DhakaTime.todayUtcMidnight(now)

    fun dhakaDayKey(now: Long = System.currentTimeMillis()): String = DhakaTime.dayKey(now)

    /** the donor's travel limit (settings), 10 when not one of the choices */
    fun donorRadius(maxKm: Int?): Int = if (maxKm != null && maxKm in Rules.RADIUS_CHOICES) maxKm else Rules.DEFAULT_RADIUS_KM

    /** a donor who wants travel costs beyond `fareAfterKm` is not told about farther requests without travel fare */
    fun fareOk(u: Donor, travelFare: Boolean, km: Double): Boolean {
        if (travelFare) return true
        val after = u.fareAfterKm ?: return true
        return km <= after
    }

    data class Picked(val donor: Donor, val km: Double)

    /**
     * pickDonors: matching group, eligible, notifications on, own travel limit; widening 5 → 10 → 25 → 50 km until
     * [ENOUGH_DONORS] are found; nearest first.
     */
    fun pickDonors(request: RequestFacts, users: List<Donor>, now: Long = System.currentTimeMillis()): List<Picked> {
        val candidates = users
            .filter { u ->
                u.uid != request.requesterUid && u.hasTokens && u.notify && u.lat != null && u.lng != null &&
                    canDonate(u.bloodGroup, request.patientGroup) && wantsGroup(u, request.patientGroup) && isEligibleDonor(u, now)
            }
            .map { Picked(it, distanceKm(request.lat, request.lng, it.lat!!, it.lng!!)) }
            .filter { it.km <= donorRadius(it.donor.maxKm) && fareOk(it.donor, request.travelFare, it.km) }
        var chosen: List<Picked> = emptyList()
        for (radius in RADII_KM) {
            chosen = candidates.filter { it.km <= radius }
            if (chosen.size >= ENOUGH_DONORS) break
        }
        return chosen.sortedBy { it.km }
    }

    /**
     * validateRequest: null when the request data is fine, else the (Bangla) message — `patientGroup`, `bags`
     * are the raw values (any JSON type in request_create).
     */
    fun validateRequest(
        patientGroup: Any?,
        bags: Any?,
        hospital: Any?,
        placeName: Any?,
        lat: Double?,
        lng: Double?,
        neededByMillis: Long?,
        neededAtMillis: Long?,
        now: Long = System.currentTimeMillis(),
    ): String? {
        if (patientGroup !is String || patientGroup !in BLOOD_GROUPS) return "রক্তের গ্রুপ ঠিক নেই"
        val b = (bags as? Number)?.toLong()?.takeIf { bags.toDouble() == it.toDouble() }
        if (b == null || b < 1 || b > 10) return "ব্যাগের সংখ্যা ঠিক নেই"
        if (hospital !is String || hospital.trim().isEmpty() || hospital.length > 120) return "হাসপাতালের নাম ঠিক নেই"
        if (placeName !is String || !Places.isDistrict(placeName)) return "এলাকা ঠিক নেই"
        if (lat == null || lat < 20.5 || lat > 26.7) return "লোকেশন ঠিক নেই"
        if (lng == null || lng < 88 || lng > 92.7) return "লোকেশন ঠিক নেই"
        if (neededByMillis == null || neededByMillis > now + 31 * DAY_MS) return "তারিখ ঠিক নেই"
        if (neededAtMillis != null && !neededAtMatchesDate(neededAtMillis, neededByMillis)) return "সময় ঠিক নেই"
        return null
    }

    fun validateRequest(r: RequestFacts, now: Long = System.currentTimeMillis()): String? =
        validateRequest(r.patientGroup, r.bags, r.hospital, r.placeName, r.lat, r.lng, r.neededByMillis, r.neededAtMillis, now)

    /** the set moment lies on the chosen date (UTC midnight), Dhaka time 00:00 to before the next 00:00 */
    fun neededAtMatchesDate(neededAtMillis: Long, neededByMillis: Long): Boolean {
        val start = neededByMillis - DHAKA_OFFSET_MS
        return neededAtMillis >= start && neededAtMillis < start + DAY_MS
    }

    /** "বিকাল ৪:৩০" */
    fun formatTimeBn(millis: Long): String {
        val d = Instant.ofEpochMilli(millis + DHAKA_OFFSET_MS).atZone(ZoneOffset.UTC)
        val h = d.hour
        val m = d.minute
        val part = when {
            h < 5 -> "রাত"
            h < 12 -> "সকাল"
            h < 15 -> "দুপুর"
            h < 18 -> "বিকাল"
            h < 20 -> "সন্ধ্যা"
            else -> "রাত"
        }
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "$part ${Lang.bnDigits(h12.toString())}:${Lang.bnDigits(m.toString().padStart(2, '0'))}"
    }

    /** "4:05 PM" */
    fun formatTimeEn(millis: Long): String {
        val d = Instant.ofEpochMilli(millis + DHAKA_OFFSET_MS).atZone(ZoneOffset.UTC)
        val h = d.hour
        val m = d.minute.toString().padStart(2, '0')
        return "${if (h % 12 == 0) 12 else h % 12}:$m ${if (h < 12) "AM" else "PM"}"
    }

    /** JS Math.round */
    fun jsRound(v: Double): Long = Math.floor(v + 0.5).toLong()

    fun formatKm(km: Double, lang: String?): String {
        if (lang == "en") return if (km < 1) "< 1 km away" else "about ${jsRound(km)} km away"
        return if (km < 1) "১ কিমির কম দূরে" else "প্রায় ${Lang.bnDigits(jsRound(km).toString())} কিমি দূরে"
    }

    /** district name (stored in Bangla) in the reader's language */
    fun districtLabel(bn: String, lang: String?): String {
        if (lang != "en") return bn
        return Places.district(bn)?.en ?: bn
    }

    /** `${d.getUTCDate()}/${d.getUTCMonth() + 1}/${d.getUTCFullYear()}` */
    fun utcDmy(ms: Long): String {
        val d = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC)
        return "${d.dayOfMonth}/${d.monthValue}/${d.year}"
    }
}

/** Port of server/lib/emergency-rules.js */
object EmergencyRules {
    val RADII = listOf(10, 25, 50)
    val HOURS = listOf(6, 12, 24, 48)
    const val MAX_NEED = 200

    /** one emergency group: need, coming, notified, closed (+ adminClosed once an admin closed/opened it) */
    data class Group(var need: Int, var coming: Int = 0, var notified: Int = 0, var closed: Boolean = false, var adminClosed: Boolean? = null) {
        fun toMap(): LinkedHashMap<String, Any?> = linkedMapOf<String, Any?>("need" to need, "coming" to coming, "notified" to notified, "closed" to closed)
            .apply { if (adminClosed != null) put("adminClosed", adminClosed) }
    }

    /** per group in one wave: 3 × still needed, at least 5 */
    fun waveSize(need: Int): Int = maxOf(need * 3, 5)

    /** admin's {"A+": 10, …} → groups (positive integers, at most [MAX_NEED]) */
    fun parseGroups(raw: com.fasterxml.jackson.databind.JsonNode?): LinkedHashMap<String, Group> {
        val out = LinkedHashMap<String, Group>()
        if (raw == null || !raw.isObject) return out
        for (g in BloodRules.BLOOD_GROUPS) {
            val n = com.lifolo.common.Js.integer(raw.get(g)) ?: continue
            if (n > 0) out[g] = Group(need = minOf(n, MAX_NEED.toLong()).toInt())
        }
        return out
    }

    data class WaveDonor(val uid: String, val group: String, val km: Double, val lang: String?)

    /** who gets this wave: per open group the nearest eligible donors not told before */
    fun pickWave(
        users: List<Donor>,
        centerLat: Double,
        centerLng: Double,
        groups: Map<String, Group>,
        radiusKm: Int,
        already: Set<String>,
        now: Long = System.currentTimeMillis(),
    ): List<WaveDonor> {
        val out = ArrayList<WaveDonor>()
        for ((g, x) in groups) {
            if (x.closed || x.coming >= x.need) continue
            out += users
                .filter { u ->
                    u.bloodGroup == g && u.hasTokens && u.notify && u.uid !in already && u.lat != null && u.lng != null &&
                        BloodRules.isEligibleDonor(u, now)
                }
                .map { WaveDonor(it.uid, g, BloodRules.distanceKm(centerLat, centerLng, it.lat!!, it.lng!!), it.lang) }
                .filter { it.km <= radiusKm }
                .sortedBy { it.km }
                .take(waveSize(x.need - x.coming))
        }
        return out
    }

    fun isLive(status: String?, endsAtMs: Long?, now: Long = System.currentTimeMillis()): Boolean =
        status == "active" && (endsAtMs ?: 0) > now
}
