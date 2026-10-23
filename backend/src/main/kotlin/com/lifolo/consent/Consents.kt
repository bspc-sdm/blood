package com.lifolo.consent

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** one scope's latest record */
data class ScopeState(
    /** `granted` (this text version), `outdated` (an older text), `withdrawn` or `none` */
    val state: String,
    val version: String?,
    val at: Long?,
    val source: String?,
)

/**
 * The `consents` table (V682): an append-only audit log — one row per grant or withdrawal of one scope, never
 * updated (a trigger refuses UPDATE). A scope's state is its latest row. Rows go with the account (ON DELETE CASCADE).
 */
@Service
class Consents(private val jdbc: JdbcTemplate) {
    /** the latest row of every scope (missing scopes → `none`) */
    fun states(uid: String, currentVersion: String): Map<String, ScopeState> {
        val latest = HashMap<String, ScopeState>()
        jdbc.query(
            "SELECT DISTINCT ON (scope) scope, version, action, source, at FROM consents WHERE uid = ? ORDER BY scope, id DESC",
            RowMapper { rs, _ ->
                val action = rs.getString("action")
                val v = rs.getString("version")
                val state = when {
                    action == WITHDRAWN -> WITHDRAWN
                    v == currentVersion -> GRANTED
                    else -> OUTDATED
                }
                latest[rs.getString("scope")] = ScopeState(state, v, rs.getTimestamp("at").time, rs.getString("source"))
            },
            uid,
        )
        return SCOPES.associateWith { latest[it] ?: ScopeState(NONE, null, null, null) }
    }

    /** the scopes [uid] has no current consent for (empty = all good) */
    fun missing(uid: String, currentVersion: String): List<String> =
        states(uid, currentVersion).filterValues { it.state != GRANTED }.keys.toList()

    /** record a grant of each scope not already granted for [version]; returns the scopes recorded */
    @Transactional
    fun grant(uid: String, scopes: List<String>, version: String, source: String): List<String> {
        lock(uid)
        val now = states(uid, version)
        val todo = scopes.filter { now[it]?.state != GRANTED }
        for (s in todo) insert(uid, s, version, GRANTED, source)
        return todo
    }

    /** record a withdrawal of each scope that is granted (current or outdated); returns the scopes recorded */
    @Transactional
    fun withdraw(uid: String, scopes: List<String>, version: String, source: String): List<String> {
        lock(uid)
        val now = states(uid, version)
        val todo = scopes.filter { now[it]?.state == GRANTED || now[it]?.state == OUTDATED }
        for (s in todo) insert(uid, s, now[s]?.version ?: version, WITHDRAWN, source)
        return todo
    }

    /** the last [limit] rows (newest first) — the audit trail */
    fun history(uid: String, limit: Int = 20): List<Map<String, Any?>> = jdbc.query(
        "SELECT scope, version, action, source, at FROM consents WHERE uid = ? ORDER BY id DESC LIMIT ?",
        RowMapper { rs, _ ->
            linkedMapOf<String, Any?>(
                "scope" to rs.getString("scope"),
                "version" to rs.getString("version"),
                "action" to rs.getString("action"),
                "source" to rs.getString("source"),
                "at" to rs.getTimestamp("at").time,
            )
        },
        uid, limit,
    )

    /** serialize grants/withdrawals per person (the accounts row exists for every authenticated uid) */
    private fun lock(uid: String) {
        jdbc.queryForList("SELECT uid FROM accounts WHERE uid = ? FOR UPDATE", String::class.java, uid)
    }

    private fun insert(uid: String, scope: String, version: String, action: String, source: String) {
        jdbc.update(
            "INSERT INTO consents (uid, scope, version, action, source) VALUES (?, ?, ?, ?, ?)",
            uid, scope, version, action, source,
        )
    }

    companion object {
        /** storing health data at all */
        const val HEALTH_DATA = "health_data"

        /** storing it on servers outside Bangladesh */
        const val DATA_ABROAD = "data_abroad"
        val SCOPES: List<String> = listOf(HEALTH_DATA, DATA_ABROAD)

        const val GRANTED = "granted"
        const val WITHDRAWN = "withdrawn"
        const val OUTDATED = "outdated"
        const val NONE = "none"

        /** where the person gave (or withdrew) it */
        val SOURCES: Set<String> = setOf("app", "web", "desktop")
    }
}
