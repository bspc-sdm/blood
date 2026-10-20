package com.lifolo.users

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Account deletion with a 30-day grace period (server/lib/account.js `deletion`).
 * Phase 1 ports [cancel] (used by every login); schedule/runDue/deleteNow come with the users package in phase 2.
 */
@Service
class DeletionService(private val jdbc: JdbcTemplate) {
    /**
     * Logging in again within 30 days cancels the deletion: listings hidden for deletion get their previous status
     * back (unless an admin changed it meanwhile — then only the marker goes), a provider suspended for deletion
     * is listed again. Returns true when a pending deletion was cancelled.
     */
    @Transactional
    fun cancel(uid: String): Boolean {
        val pending = jdbc.queryForList(
            "SELECT delete_at FROM accounts WHERE uid = ? AND delete_at IS NOT NULL FOR UPDATE", uid,
        ).isNotEmpty()
        if (!pending) return false
        jdbc.update("UPDATE accounts SET delete_at = NULL, delete_requested_at = NULL WHERE uid = ?", uid)
        for ((table, owner) in LISTINGS) {
            jdbc.update(
                """
                UPDATE $table SET
                  status = CASE WHEN status = 'suspended' THEN hidden_for_deletion ELSE status END,
                  hidden_for_deletion = NULL
                WHERE $owner = ? AND hidden_for_deletion IS NOT NULL
                """.trimIndent(),
                uid,
            )
        }
        val suspendedForDeletion = jdbc.queryForList(
            "SELECT uid FROM providers WHERE uid = ? AND suspend_reason = 'deletion'", uid,
        ).isNotEmpty()
        if (suspendedForDeletion) liftProvider(uid)
        return true
    }

    /** apptcancel.js liftProvider: list again (if it was listed before the suspension) */
    fun liftProvider(uid: String): Boolean = jdbc.update(
        """
        UPDATE providers SET
          admin_suspended = FALSE,
          suspend_reason = NULL,
          listed = COALESCE(listed_before_suspend, TRUE),
          active = COALESCE(listed_before_suspend, TRUE) AND appointments,
          listed_before_suspend = NULL
        WHERE uid = ? AND admin_suspended
        """.trimIndent(),
        uid,
    ) > 0

    companion object {
        /** listing table → owner column (account.js ownListings) */
        private val LISTINGS = listOf(
            "ambulances" to "owner_uid",
            "blood_banks" to "owner_uid",
            "pharmacies" to "uid",
            "labs" to "uid",
        )
    }
}
