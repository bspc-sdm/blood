package com.lifolo.appointments

import com.lifolo.common.DhakaTime
import com.lifolo.common.Sql
import org.springframework.jdbc.core.JdbcTemplate

/**
 * serials/{providerUid_date_key}: one number sequence per chamber per day, shared by the app's confirmed appointments
 * (appointment_update confirm) and the desktop's walk-ins (offline sync "walkin"). Call inside a transaction: the
 * counter row is locked until it ends, so no two patients get the same next number.
 */
object SerialCounters {
    /**
     * The serial for a new patient of [providerUid]'s chamber [chamberKey] on [date] (UTC-midnight ms of the Dhaka day):
     * [own] (the doctor's register number, 1..999) when given, else the next number. The counter never goes back.
     */
    fun next(jdbc: JdbcTemplate, providerUid: String, date: Long, chamberKey: String, own: Int?, now: Long): Int {
        val key = QueueRules.keyOf(chamberKey)
        val day = Sql.date(DhakaTime.fromUtcMidnightMs(date))
        jdbc.update(
            "INSERT INTO serial_counters (provider_uid, visit_date, queue_key, n) VALUES (?, ?, ?, 0) ON CONFLICT DO NOTHING",
            providerUid, day, key,
        )
        val last = jdbc.queryForObject(
            "SELECT n FROM serial_counters WHERE provider_uid = ? AND visit_date = ? AND queue_key = ? FOR UPDATE",
            Int::class.java, providerUid, day, key,
        ) ?: 0
        val s = own ?: (last + 1)
        jdbc.update(
            "UPDATE serial_counters SET n = ?, at = ? WHERE provider_uid = ? AND visit_date = ? AND queue_key = ?",
            maxOf(last, s), Sql.ts(now), providerUid, day, key,
        )
        return s
    }
}
