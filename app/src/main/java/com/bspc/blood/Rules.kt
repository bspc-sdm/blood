package com.bspc.blood

import com.firebase.geofire.GeoFireUtils
import com.firebase.geofire.GeoLocation
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

const val DAY_MS = 24L * 60 * 60 * 1000

/** অ্যাপের নিয়মগুলো এক জায়গায়। PLAN.md-এর "নিয়ম" অংশ দেখুন। */
object Rules {
    /** শেষ রক্তদানের কত দিন পর আবার দেওয়া যাবে। চালুর আগে ডাক্তার/ব্লাড ব্যাংক থেকে নিশ্চিত হতে হবে। */
    const val ELIGIBILITY_DAYS = 120

    /** একজন ব্যবহারকারী একসাথে কয়টা খোলা রিকোয়েস্ট রাখতে পারবে */
    const val MAX_OPEN_REQUESTS = 2

    /** একজন ডোনার দিনে কয়টা রিকোয়েস্ট নোটিফিকেশন পাবে: নিজে না বদলালে ৩টা, সেটিংসে ১–১০ বেছে নেওয়া যায় */
    const val DEFAULT_PUSH_PER_DAY = 3
    const val MAX_PUSH_PER_DAY_LIMIT = 10
    val PUSH_PER_DAY_CHOICES = listOf(1, 2, 3, 5, 10)

    const val MIN_AGE = 18
    const val MAX_AGE = 60
    const val MIN_WEIGHT_KG = 50

    /** জন্ম তারিখ থেকে পূর্ণ বয়স (বাংলাদেশের তারিখ ধরে) */
    fun ageYears(birthDateMillis: Long?): Int? {
        if (birthDateMillis == null) return null
        val utc = TimeZone.getTimeZone("UTC")
        val b = Calendar.getInstance(utc).apply { timeInMillis = birthDateMillis }
        val n = Calendar.getInstance(utc).apply { timeInMillis = todayUtcMidnight() }
        var age = n.get(Calendar.YEAR) - b.get(Calendar.YEAR)
        val nm = n.get(Calendar.MONTH)
        val bm = b.get(Calendar.MONTH)
        if (nm < bm || (nm == bm && n.get(Calendar.DAY_OF_MONTH) < b.get(Calendar.DAY_OF_MONTH))) age--
        return age
    }

    /** এখন রক্ত দিতে পারবেন কি না; না পারলে কারণ, পারলে null */
    fun notEligibleReason(p: Profile): String? {
        val age = ageYears(p.birthDateMillis)
        return when {
            age == null || p.weightKg == null -> tr("Add your birth date and weight", "জন্ম তারিখ আর ওজন দিন")
            age < MIN_AGE -> tr("You must be at least ${num(MIN_AGE)} to donate", "রক্ত দিতে বয়স কমপক্ষে ${num(MIN_AGE)} বছর হতে হয়")
            age > MAX_AGE -> tr("Donation is usually not advised after ${num(MAX_AGE)}", "${num(MAX_AGE)} বছরের বেশি বয়সে রক্তদান সাধারণত করা হয় না")
            p.weightKg < MIN_WEIGHT_KG -> tr("You must weigh at least ${num(MIN_WEIGHT_KG)} kg", "ওজন কমপক্ষে ${num(MIN_WEIGHT_KG)} কেজি হতে হয়")
            !isEligible(p.lastDonationMillis) -> tr(
                "You can donate again from ${formatDate(nextEligibleMillis(p.lastDonationMillis) ?: 0L)}",
                "${formatDate(nextEligibleMillis(p.lastDonationMillis) ?: 0L)} থেকে আবার রক্ত দিতে পারবেন",
            )
            else -> null
        }
    }

    fun isEligibleDonor(p: Profile): Boolean = notEligibleReason(p) == null

    val BLOOD_GROUPS = listOf("A+", "A-", "B+", "B-", "O+", "O-", "AB+", "AB-")

    fun nextEligibleMillis(lastDonationMillis: Long?): Long? =
        lastDonationMillis?.let { it + ELIGIBILITY_DAYS * DAY_MS }

    fun isEligible(lastDonationMillis: Long?, now: Long = System.currentTimeMillis()): Boolean {
        val next = nextEligibleMillis(lastDonationMillis) ?: return true
        return now >= next
    }

    fun daysUntilEligible(lastDonationMillis: Long?, now: Long = System.currentTimeMillis()): Long {
        val next = nextEligibleMillis(lastDonationMillis) ?: return 0
        if (now >= next) return 0
        return (next - now + DAY_MS - 1) / DAY_MS
    }

    /** DatePicker UTC মধ্যরাতের সময় দেয়, তাই তারিখগুলো UTC মধ্যরাত হিসেবে রাখা হয়। */
    const val DHAKA_OFFSET_MS = 6L * 60 * 60 * 1000

    /** বাছা তারিখ (UTC মধ্যরাত) + বাংলাদেশের ঘণ্টা-মিনিট → আসল মুহূর্ত */
    fun dhakaMillis(dateUtcMidnight: Long, hour: Int, minute: Int): Long =
        dateUtcMidnight + (hour * 60L + minute) * 60_000L - DHAKA_OFFSET_MS

    /** মুহূর্ত থেকে বাংলাদেশের ঘণ্টা ও মিনিট */
    fun dhakaHourMinute(millis: Long): Pair<Int, Int> {
        val minutes = Math.floorMod((millis + DHAKA_OFFSET_MS) / 60_000L, 24L * 60).toInt()
        return minutes / 60 to minutes % 60
    }

    /** "4:05 PM" / "বিকাল ৪:০৫" */
    fun formatTime(hour: Int, minute: Int): String {
        val h12 = if (hour % 12 == 0) 12 else hour % 12
        val mm = minute.toString().padStart(2, '0')
        if (!isBangla) return "$h12:$mm ${if (hour < 12) "AM" else "PM"}"
        val part = when {
            hour < 5 -> "রাত"
            hour < 12 -> "সকাল"
            hour < 15 -> "দুপুর"
            hour < 18 -> "বিকাল"
            hour < 20 -> "সন্ধ্যা"
            else -> "রাত"
        }
        return "$part ${num(h12)}:${num(mm)}"
    }

    fun formatTime(millis: Long): String {
        val (h, m) = dhakaHourMinute(millis)
        return formatTime(h, m)
    }

    /** "২০ অক্টোবর ২০২৬, বিকাল ৪:০০" (সময় না থাকলে শুধু তারিখ) */
    fun formatNeeded(r: BloodRequest): String =
        formatDate(r.neededByMillis) + (r.neededAtMillis?.let { ", ${formatTime(it)}" } ?: "")

    /** বাকি সময়: "1d 02h 05m 30s" / "১ দিন ০২ ঘণ্টা ০৫ মিনিট ৩০ সেকেন্ড" */
    fun formatDuration(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val d = total / 86400
        val h = (total % 86400) / 3600
        val m = (total % 3600) / 60
        val sec = total % 60
        fun two(v: Long) = num(v.toString().padStart(2, '0'))
        val parts = mutableListOf<String>()
        if (d > 0) parts += num(d) + tr("d", " দিন")
        if (d > 0 || h > 0) parts += two(h) + tr("h", " ঘণ্টা")
        parts += two(m) + tr("m", " মিনিট")
        parts += two(sec) + tr("s", " সেকেন্ড")
        return parts.joinToString(" ")
    }

    fun formatDate(millis: Long): String {
        val f = if (isBangla) SimpleDateFormat("d MMMM yyyy", Locale("bn", "BD"))
        else SimpleDateFormat("d MMM yyyy", Locale.ENGLISH)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(millis))
    }

    /** ফোনের আজকের তারিখ, UTC মধ্যরাত হিসেবে (DatePicker-এর সাথে মেলানোর জন্য) */
    fun todayUtcMidnight(): Long {
        val local = Calendar.getInstance()
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        utc.clear()
        utc.set(
            local.get(Calendar.YEAR),
            local.get(Calendar.MONTH),
            local.get(Calendar.DAY_OF_MONTH),
        )
        return utc.timeInMillis
    }

    fun dayKey(millis: Long): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(millis))

    fun formatKm(km: Double): String =
        if (km < 1) tr("< 1 km", "১ কিমির কম") else num(Math.round(km)) + tr(" km", " কিমি")

    /** বাংলাদেশি মোবাইল নম্বর: 01XXXXXXXXX (১১ সংখ্যা)। +88 বা 88 থাকলে বাদ দেওয়া হয়। */
    fun normalizePhone(input: String): String? {
        var digits = input.filter { it.isDigit() }
        if (digits.startsWith("88") && digits.length == 13) digits = digits.substring(2)
        return if (digits.length == 11 && digits.startsWith("01")) digits else null
    }
}

/** রোগীর গ্রুপ অনুযায়ী কোন গ্রুপের ডোনার রক্ত দিতে পারবে (লোহিত কণিকা / whole blood) */
object BloodCompat {
    private val donorsFor: Map<String, Set<String>> = mapOf(
        "O-" to setOf("O-"),
        "O+" to setOf("O+", "O-"),
        "A-" to setOf("A-", "O-"),
        "A+" to setOf("A+", "A-", "O+", "O-"),
        "B-" to setOf("B-", "O-"),
        "B+" to setOf("B+", "B-", "O+", "O-"),
        "AB-" to setOf("AB-", "A-", "B-", "O-"),
        "AB+" to Rules.BLOOD_GROUPS.toSet(),
    )

    fun canDonate(donorGroup: String, patientGroup: String): Boolean =
        donorsFor[patientGroup]?.contains(donorGroup) == true

    /** এই গ্রুপের ডোনার যেসব রোগীর গ্রুপকে দিতে পারেন */
    fun patientsFor(donorGroup: String): List<String> =
        Rules.BLOOD_GROUPS.filter { canDonate(donorGroup, it) }
}

object Geo {
    /** ধাপে ধাপে খোঁজার দূরত্ব (কিমি) */
    val RADII_KM = listOf(5.0, 10.0, 25.0)

    /** এতগুলো পেলে আর দূরে খোঁজা হবে না */
    const val ENOUGH_RESULTS = 5

    /** প্রাইভেসির জন্য স্থানাঙ্ক গোল করা হয় (২ দশমিক ঘর ≈ ১ কিমি) */
    fun round(value: Double): Double = Math.round(value * 100) / 100.0

    fun hash(lat: Double, lng: Double): String =
        GeoFireUtils.getGeoHashForLocation(GeoLocation(lat, lng))

    fun distanceKm(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double =
        GeoFireUtils.getDistanceBetween(GeoLocation(aLat, aLng), GeoLocation(bLat, bLng)) / 1000.0
}
