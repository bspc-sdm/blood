package com.lifolo.rx

import com.lifolo.common.DhakaTime
import com.lifolo.common.Sql
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import java.sql.ResultSet
import java.sql.Timestamp

/** Small JDBC helpers shared by the rx and lab packages (P3). */
object P3Sql {
    /** a PostgreSQL array literal for `?::text[]` parameters */
    fun pgArray(list: Collection<String?>): String =
        list.joinToString(",", "{", "}") { v ->
            if (v == null) "NULL" else "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }

    /** UTC-midnight ms of a Dhaka date → DATE parameter */
    fun date(ms: Long?): java.sql.Date? = ms?.let { Sql.date(DhakaTime.fromUtcMidnightMs(it)) }

    fun ts(ms: Long?): Timestamp? = ms?.let { Timestamp(it) }

    /** DATE column → UTC-midnight ms */
    fun dayMs(rs: ResultSet, col: String): Long? = rs.getObject(col, java.time.LocalDate::class.java)?.let { DhakaTime.toUtcMidnightMs(it) }

    fun msOrNull(rs: ResultSet, col: String): Long? = rs.getTimestamp(col)?.time

    fun doubleOrNull(rs: ResultSet, col: String): Double? = rs.getDouble(col).let { if (rs.wasNull()) null else it }
}

/** `query(sql, RowCallbackHandler, args)` without the lambda overload ambiguity */
fun JdbcTemplate.eachRow(sql: String, vararg args: Any?, fn: (ResultSet) -> Unit) {
    query(sql, RowCallbackHandler { rs -> fn(rs) }, *args)
}

/** `query(sql, RowMapper, args)` */
fun <T> JdbcTemplate.rows(sql: String, vararg args: Any?, fn: (ResultSet) -> T): List<T> =
    query(sql, { rs, _ -> fn(rs) }, *args)
