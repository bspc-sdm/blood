package com.lifolo.common

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Bangladesh time helpers (UTC+6, no DST). Node keeps "days" as either "YYYYMMDD" strings (dhakaDayKey)
 * or UTC-midnight millis of the Dhaka calendar date (todayDhakaUtcMidnight); here both are [LocalDate].
 */
object DhakaTime {
    val OFFSET: ZoneOffset = ZoneOffset.ofHours(6)
    private const val SIX_HOURS_MS = 6L * 60 * 60 * 1000

    /** Dhaka calendar date of an instant */
    fun day(nowMs: Long = System.currentTimeMillis()): LocalDate =
        Instant.ofEpochMilli(nowMs + SIX_HOURS_MS).atZone(ZoneOffset.UTC).toLocalDate()

    /** rules.js dhakaDayKey: "YYYYMMDD" */
    fun dayKey(nowMs: Long = System.currentTimeMillis()): String = day(nowMs).format(DateTimeFormatter.BASIC_ISO_DATE)

    /** rules.js todayDhakaUtcMidnight: UTC midnight millis of today's Dhaka date */
    fun todayUtcMidnight(nowMs: Long = System.currentTimeMillis()): Long = toUtcMidnightMs(day(nowMs))

    fun toUtcMidnightMs(d: LocalDate): Long = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    fun fromUtcMidnightMs(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()

    fun ms(i: Instant?): Long? = i?.toEpochMilli()

    /**
     * `new Date(at).toLocaleDateString(en ? 'en-GB' : 'bn-BD', {timeZone:'Asia/Dhaka', day:'numeric', month:'long', year:'numeric'})`
     * en → "18 November 2026", bn → "১৮ নভেম্বর, ২০২৬"
     */
    fun longDate(atMs: Long, english: Boolean): String {
        val d = day(atMs)
        return if (english) {
            d.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.UK))
        } else {
            Lang.bnDigits("${d.dayOfMonth} ${BN_MONTHS[d.monthValue - 1]}, ${d.year}")
        }
    }

    private val BN_MONTHS = listOf(
        "জানুয়ারী", "ফেব্রুয়ারী", "মার্চ", "এপ্রিল", "মে", "জুন", "জুলাই", "আগস্ট", "সেপ্টেম্বর", "অক্টোবর", "নভেম্বর", "ডিসেম্বর",
    )
}
