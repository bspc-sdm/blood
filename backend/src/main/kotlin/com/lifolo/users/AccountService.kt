package com.lifolo.users

import com.lifolo.common.ApiException
import com.lifolo.common.Lang
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
    fun forgetAll() {
        seen.clear()
        deactivated.clear()
    }

    /** uid → (checked at, deactivated?) — the dispatcher asks on every call; [DEACTIVATION_TTL_MS] across instances */
    private val deactivated = ConcurrentHashMap<String, Pair<Long, Boolean>>()

    /** is the account switched off (account_deactivate)? remembered a few seconds; this instance's changes at once */
    fun isDeactivated(uid: String): Boolean {
        val now = System.currentTimeMillis()
        deactivated[uid]?.let { (at, v) -> if (now - at < DEACTIVATION_TTL_MS) return v }
        val v = deactivatedAt(uid) != null
        if (deactivated.size > 100_000) deactivated.clear()
        deactivated[uid] = now to v
        return v
    }

    fun forgetDeactivation(uid: String) {
        deactivated.remove(uid)
    }

    fun disabledReason(uid: String): String =
        jdbc.query("SELECT disabled_reason FROM accounts WHERE uid = ?", RowMapper { rs, _ -> rs.getString(1) }, uid)
            .firstOrNull().orEmpty()

    /** when the account was switched off (account_deactivate), or null while it is active */
    fun deactivatedAt(uid: String): Instant? =
        jdbc.query("SELECT deactivated_at FROM accounts WHERE uid = ?", RowMapper { rs, _ -> rs.getTimestamp(1)?.toInstant() }, uid)
            .firstOrNull()

    /** which of [uids] are deactivated right now (the others are active or unknown) */
    fun deactivatedAmong(uids: Collection<String?>): Set<String> {
        val ids = uids.filterNotNull().filter { it.isNotEmpty() }.distinct()
        if (ids.isEmpty()) return emptySet()
        val out = HashSet<String>()
        for (chunk in ids.chunked(1000)) {
            out += jdbc.queryForList(
                "SELECT uid FROM accounts WHERE deactivated_at IS NOT NULL AND uid IN (${chunk.joinToString(",") { "?" }})",
                String::class.java, *chunk.toTypedArray(),
            )
        }
        return out
    }

    /**
     * The account's state for the apps (profile_get, the login response): `{state: "active"|"deactivated"|"deleting",
     * deactivatedAt?, deletionRequestedAt?, deletesAt?}` (epoch ms). A pending deletion wins over a deactivation (the two
     * never coexist: delete_account clears the deactivation first).
     */
    fun state(uid: String): Map<String, Any?> {
        val row = jdbc.query(
            "SELECT deactivated_at, delete_requested_at, delete_at FROM accounts WHERE uid = ?",
            RowMapper { rs, _ -> Triple(rs.getTimestamp(1)?.time, rs.getTimestamp(2)?.time, rs.getTimestamp(3)?.time) },
            uid,
        ).firstOrNull()
        val out = linkedMapOf<String, Any?>()
        when {
            row?.third != null -> {
                out["state"] = "deleting"
                out["deletionRequestedAt"] = row.second ?: (row.third!! - AccountDeletionService.GRACE_MS)
                out["deletesAt"] = row.third
            }
            row?.first != null -> {
                out["state"] = "deactivated"
                out["deactivatedAt"] = row.first
            }
            else -> out["state"] = "active"
        }
        return out
    }

    fun deleteAt(uid: String): Instant? =
        jdbc.query("SELECT delete_at FROM accounts WHERE uid = ?", RowMapper { rs, _ -> rs.getTimestamp(1)?.toInstant() }, uid)
            .firstOrNull()

    companion object {
        const val DEACTIVATION_TTL_MS = 5_000L

        /** 423 for every action but the few a deactivated account may still call (Bangla unless lang == "en") */
        fun deactivatedError(lang: String?): ApiException = ApiException(
            423,
            Lang.pick(
                lang,
                "Your account is temporarily deactivated. Tap \"Turn on again\" to reactivate it.",
                "আপনার অ্যাকাউন্ট সাময়িক বন্ধ আছে। চালু করতে \"আবার চালু করুন\" চাপুন।", // bn-ok
            ),
            "account_deactivated",
        )
    }
}
