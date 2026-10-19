package com.lifolo.common

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementCreator
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.time.Instant

/**
 * A dynamic `UPDATE … SET` (the Firestore `ref.update({...})` with optional fields), with typed parameters:
 * strings, numbers, booleans, instants, `NULL`, `now()`, JSONB and PostgreSQL arrays.
 *
 * ```
 * SqlSet().value("status", "approved").now("reviewed_at").nul("status_reason").update(jdbc, "ambulances", "id = ?", id)
 * ```
 */
class SqlSet {
    private class Part(val sql: String, val bind: ((Connection, PreparedStatement, Int) -> Unit)?)

    // column → expression; setting a column again replaces it (keeps its first position)
    private val parts = LinkedHashMap<String, Part>()

    val isEmpty: Boolean get() = parts.isEmpty()

    fun has(col: String): Boolean = col in parts

    private fun bind(col: String, sql: String, b: (Connection, PreparedStatement, Int) -> Unit): SqlSet {
        parts[col] = Part(sql, b)
        return this
    }

    private fun literal(col: String, sql: String): SqlSet {
        parts[col] = Part(sql, null)
        return this
    }

    /** String, Int, Long, Double, Boolean, Instant, Timestamp, java.sql.Date or null (→ NULL) */
    fun value(col: String, v: Any?): SqlSet = when (v) {
        null -> nul(col)
        is Instant -> bind(col, "?") { _, ps, i -> ps.setTimestamp(i, Timestamp.from(v)) }
        else -> bind(col, "?") { _, ps, i -> ps.setObject(i, v) }
    }

    /** epoch millis → TIMESTAMPTZ (null → NULL) */
    fun ms(col: String, ms: Long?): SqlSet = if (ms == null) nul(col) else bind(col, "?") { _, ps, i -> ps.setTimestamp(i, Timestamp(ms)) }

    fun nul(col: String): SqlSet = literal(col, "NULL")

    fun now(col: String): SqlSet = literal(col, "now()")

    /** raw SQL expression (no parameters), e.g. `count + 1` */
    fun expr(col: String, sql: String): SqlSet = literal(col, sql)

    fun json(col: String, json: String?): SqlSet =
        if (json == null) nul(col) else bind(col, "?::jsonb") { _, ps, i -> ps.setString(i, json) }

    fun textArray(col: String, list: Collection<String>?): SqlSet =
        if (list == null) nul(col) else bind(col, "?") { c, ps, i -> ps.setArray(i, c.createArrayOf("text", list.toTypedArray())) }

    /** SMALLINT[] / INTEGER[] columns */
    fun intArray(col: String, list: Collection<Int>?, pgType: String = "int4"): SqlSet =
        if (list == null) nul(col) else bind(col, "?::$pgType[]") { c, ps, i -> ps.setArray(i, c.createArrayOf("int4", list.toTypedArray())) }

    /** `UPDATE table SET … WHERE where`; returns the number of rows changed (0 when nothing to set) */
    fun update(jdbc: JdbcTemplate, table: String, where: String, vararg whereArgs: Any?): Int {
        if (parts.isEmpty()) return 0
        val sql = "UPDATE $table SET ${parts.entries.joinToString(", ") { (c, p) -> "$c = ${p.sql}" }} WHERE $where"
        return jdbc.update(
            PreparedStatementCreator { con ->
                val ps = con.prepareStatement(sql)
                var i = 1
                for (p in parts.values) {
                    val b = p.bind ?: continue
                    b(con, ps, i)
                    i++
                }
                for (a in whereArgs) ps.setObject(i++, a)
                ps
            },
        )
    }
}
