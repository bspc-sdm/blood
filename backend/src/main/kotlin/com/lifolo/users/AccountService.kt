package com.lifolo.users

import com.lifolo.demo.DemoWorld
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

    /** [emailVerified]: the email is proven (login code / verified token) — only then may it take up waiting invitations */
    fun ensure(uid: String, email: String?, emailVerified: Boolean = false) {
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
        if (emailVerified) email?.takeIf { it.isNotBlank() }?.let { claimCareInvites(uid, it.lowercase()) }
    }

    /**
     * caregiver invitations sent to this email before it had an account (care_invite answers the same either way)
     * become normal invitations to accept or decline. Never across the demo/real worlds.
     */
    private fun claimCareInvites(uid: String, email: String) {
        val waiting = jdbc.queryForList("SELECT patient_uid, patient_name, created_at FROM care_email_invites WHERE email = ?", email)
        for (w in waiting) {
            val patient = w["patient_uid"] as String
            if (patient != uid && DemoWorld.sameWorld(patient, uid)) {
                jdbc.update(
                    """
                    INSERT INTO care_links (patient_uid, caretaker_uid, patient_name, caretaker_name, caretaker_email, status, created_at)
                    VALUES (?, ?, ?, '', ?, 'invited', ?) ON CONFLICT DO NOTHING
                    """.trimIndent(),
                    patient, uid, w["patient_name"] ?: "", email, w["created_at"],
                )
            }
            if (DemoWorld.sameWorld(patient, uid)) jdbc.update("DELETE FROM care_email_invites WHERE patient_uid = ? AND email = ?", patient, email)
        }
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
