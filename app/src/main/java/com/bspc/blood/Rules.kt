package com.bspc.blood

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** অ্যাপের নিয়মগুলো এক জায়গায়। PLAN.md-এর "নিয়ম" অংশ দেখুন। */
object Rules {
    /** শেষ রক্তদানের কত দিন পর আবার দেওয়া যাবে। চালুর আগে ডাক্তার/ব্লাড ব্যাংক থেকে নিশ্চিত হতে হবে। */
    const val ELIGIBILITY_DAYS = 120

    val BLOOD_GROUPS = listOf("A+", "A-", "B+", "B-", "O+", "O-", "AB+", "AB-")

    private const val DAY_MS = 24L * 60 * 60 * 1000

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

    /** DatePicker UTC মধ্যরাতের সময় দেয়, তাই UTC ধরে তারিখ দেখানো হয়। */
    fun formatDate(millis: Long): String {
        val f = SimpleDateFormat("d MMMM yyyy", Locale("bn", "BD"))
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(millis))
    }

    /** বাংলাদেশি মোবাইল নম্বর: 01XXXXXXXXX (১১ সংখ্যা)। +88 বা 88 থাকলে বাদ দেওয়া হয়। */
    fun normalizePhone(input: String): String? {
        var digits = input.filter { it.isDigit() }
        if (digits.startsWith("88") && digits.length == 13) digits = digits.substring(2)
        return if (digits.length == 11 && digits.startsWith("01")) digits else null
    }
}
