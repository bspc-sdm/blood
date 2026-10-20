package com.lifolo.users

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * The `accounts` registry: one row per Firebase uid that ever acted. Every user-owned table references it,
 * so the row is created on the first authenticated request (or at login) — cheap upsert, remembered per process.
 */
@Service
class AccountService(private val jdbc: JdbcTemplate) {
    private val seen = ConcurrentHashMap<String, Long>()

    fun ensure(uid: String, email: String?) {
        val now = System.currentTimeMillis()
        val last = seen[uid]
        if (last != null && now - last < 60 * 60 * 1000) return
        jdbc.update(
            """
            INSERT INTO accounts (uid, email, last_seen_at) VALUES (?, ?, now())
            ON CONFLICT (uid) DO UPDATE SET last_seen_at = now(),
              email = COALESCE(EXCLUDED.email, accounts.email)
            """.trimIndent(),
            uid, email?.takeIf { it.isNotBlank() }?.lowercase(),
        )
        seen[uid] = now
    }

    /** forget the cache (tests truncate tables between cases) */
    fun forgetAll() = seen.clear()

    fun disabledReason(uid: String): String =
        jdbc.query("SELECT disabled_reason FROM accounts WHERE uid = ?", RowMapper { rs, _ -> rs.getString(1) }, uid)
            .firstOrNull().orEmpty()

    fun deleteAt(uid: String): Instant? =
        jdbc.query("SELECT delete_at FROM accounts WHERE uid = ?", RowMapper { rs, _ -> rs.getTimestamp(1)?.toInstant() }, uid)
            .firstOrNull()
}
