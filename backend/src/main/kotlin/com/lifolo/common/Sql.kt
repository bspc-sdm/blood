package com.lifolo.common

import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate

/** Small JDBC helpers (timestamps as epoch-ms like the Node API, nullable getters). */
object Sql {
    /** 3000-01-01 UTC: epoch millis beyond this are not valid timestamps here (cursors, dates from the body) */
    const val MAX_MS = 32_503_680_000_000L

    fun ts(ms: Long): Timestamp = Timestamp(ms)

    fun ts(i: Instant?): Timestamp? = i?.let { Timestamp.from(it) }

    fun date(d: LocalDate?): java.sql.Date? = d?.let { java.sql.Date.valueOf(it) }
}

fun ResultSet.instant(col: String): Instant? = getTimestamp(col)?.toInstant()

fun ResultSet.ms(col: String): Long? = getTimestamp(col)?.time

fun ResultSet.localDate(col: String): LocalDate? = getObject(col, LocalDate::class.java)

fun ResultSet.longOrNull(col: String): Long? = getLong(col).let { if (wasNull()) null else it }

fun ResultSet.intOrNull(col: String): Int? = getInt(col).let { if (wasNull()) null else it }

fun ResultSet.doubleOrNull(col: String): Double? = getDouble(col).let { if (wasNull()) null else it }

fun ResultSet.boolOrNull(col: String): Boolean? = getBoolean(col).let { if (wasNull()) null else it }

@Suppress("UNCHECKED_CAST")
fun ResultSet.stringArray(col: String): List<String>? = (getArray(col)?.array as? Array<Any?>)?.map { it.toString() }
