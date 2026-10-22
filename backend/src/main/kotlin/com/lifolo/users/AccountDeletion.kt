package com.lifolo.users

import com.lifolo.admin.kernel.AccountDeletionStep
import com.lifolo.admin.kernel.VerifyDocs
import com.lifolo.auth.EmailCodes
import com.lifolo.auth.IdentityException
import com.lifolo.auth.IdentityProvider
import com.lifolo.common.Sql
import com.lifolo.jobs.DailyTask
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service

/**
 * account.js — account deletion with a 30-day grace period (Google Play: deletion must be possible in the app).
 *
 *  - [schedule] (`delete_account`): the date 30 days ahead (an earlier request keeps its date), every package hides what
 *    others see ([AccountDeletionStep.hide] in `order`: blood banks 100, the doctor/nurse 200, the lab 300, the pharmacy
 *    400, ambulances + "call an ambulance" requests 500), the devices are dropped and every login is revoked.
 *  - [DeletionService.cancel] (any login within the 30 days): everything comes back (phase 1, unchanged).
 *  - [deleteNow] (daily job, [runDue]): every package's [AccountDeletionStep.deleteNow] in order — they notify the
 *    counterparties first — then [PlatformDeletionStep] (600), the `accounts` row (FK cascades remove whatever is
 *    left) and last the Firebase Auth user, so a run that stops half way is finished by the next one.
 */
@Service
class AccountDeletionService(
    private val jdbc: JdbcTemplate,
    private val steps: ObjectProvider<AccountDeletionStep>,
    private val identity: IdentityProvider,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private fun ordered(): List<AccountDeletionStep> = steps.orderedStream().toList().sortedBy { it.order }

    /** account.js schedule: {ok, deleteAt} */
    fun schedule(uid: String, nowMs: Long = System.currentTimeMillis()): Map<String, Any?> {
        val earlier = jdbc.query("SELECT delete_at FROM accounts WHERE uid = ?", { rs, _ -> rs.getTimestamp(1)?.time }, uid).firstOrNull()
        val deleteAt = earlier ?: (nowMs + GRACE_MS)
        jdbc.update(
            """
            INSERT INTO accounts (uid, delete_at, delete_requested_at) VALUES (?, ?, ?)
            ON CONFLICT (uid) DO UPDATE SET delete_at = EXCLUDED.delete_at, delete_requested_at = EXCLUDED.delete_requested_at
            """.trimIndent(),
            uid, Sql.ts(deleteAt), Sql.ts(nowMs),
        )
        for (s in ordered()) {
            try {
                s.hide(uid)
            } catch (e: Exception) {
                log.error("deletion hide step {} failed", s.order, e)
            }
        }
        // no more notifications on any phone, every login revoked
        jdbc.update("DELETE FROM device_tokens WHERE uid = ?", uid)
        try {
            identity.revokeRefreshTokens(uid)
        } catch (e: Exception) {
            log.warn("revoke for deletion failed: {}", e.message)
        }
        return linkedMapOf("ok" to true, "deleteAt" to deleteAt)
    }

    /**
     * account.js deleteNow: everything of the user, then the login. A failing package step is logged and the rest
     * continues (Node catches per part); the `accounts` row cascade removes what a failed step left behind.
     */
    fun deleteNow(uid: String, email: String?) {
        for (s in ordered()) {
            try {
                s.deleteNow(uid, email)
            } catch (e: Exception) {
                log.error("deletion step {} failed for {}", s.order, uid, e)
            }
        }
        jdbc.update("DELETE FROM accounts WHERE uid = ?", uid)
        try {
            identity.deleteUser(uid)
        } catch (e: IdentityException) {
            if (e.kind != IdentityException.Kind.USER_NOT_FOUND) throw e
        }
    }

    /** account.js runDue: accounts whose 30 days are over, [max] per run (the rest the next day) */
    fun runDue(nowMs: Long = System.currentTimeMillis(), max: Int = 5): Int {
        val due = jdbc.queryForList(
            "SELECT uid FROM accounts WHERE delete_at IS NOT NULL AND delete_at <= ? ORDER BY delete_at LIMIT ?",
            String::class.java, Sql.ts(nowMs), max,
        )
        var n = 0
        for (uid in due) {
            try {
                val email = try {
                    identity.getUser(uid).email
                } catch (e: IdentityException) {
                    if (e.kind != IdentityException.Kind.USER_NOT_FOUND) throw e
                    null
                }
                deleteNow(uid, email ?: "")
                n += 1
            } catch (e: Exception) {
                log.error("scheduled delete failed {}", uid, e)
            }
        }
        return n
    }

    companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val GRACE_MS = 30 * DAY_MS
    }
}

/**
 * account.js deleteNow, the platform part (after the packages): moderation reports the user made, support tickets,
 * the bell list, document photos, the server state (phone code, quotas, push counters), the login-code record of the
 * email and the profile itself.
 */
@Component
class PlatformDeletionStep(private val jdbc: JdbcTemplate, private val verify: VerifyDocs) : AccountDeletionStep {
    override val order = 600

    override fun deleteNow(uid: String, email: String?) {
        jdbc.update("DELETE FROM mod_reports WHERE reporter_uid = ?", uid)
        jdbc.update("DELETE FROM tickets WHERE uid = ?", uid)
        jdbc.update("DELETE FROM inbox_items WHERE uid = ?", uid)
        jdbc.update("DELETE FROM device_tokens WHERE uid = ?", uid)
        runCatching { verify.deleteForOwner(uid) }
        jdbc.update("DELETE FROM user_quotas WHERE uid = ?", uid)
        jdbc.update("DELETE FROM user_state WHERE uid = ?", uid)
        jdbc.update("DELETE FROM notify_prefs WHERE uid = ?", uid)
        jdbc.update("DELETE FROM user_commitments WHERE uid = ?", uid)
        jdbc.update("DELETE FROM user_family_members WHERE uid = ?", uid)
        jdbc.update("DELETE FROM donation_date_log WHERE uid = ?", uid)
        EmailCodes.normalizeEmail(email)?.let { jdbc.update("DELETE FROM email_codes WHERE email_hash = ?", EmailCodes.docIdFor(it)) }
        jdbc.update("DELETE FROM user_profiles WHERE uid = ?", uid)
    }
}

/** daily step 10 (`accountsDeleted`): accounts whose 30-day grace is over */
@Component
class AccountsDeletedTask(private val deletion: AccountDeletionService) : DailyTask {
    override val name = "accountsDeleted"
    override val order = 10

    override fun run(nowMs: Long): Any = deletion.runDue(nowMs)
}
