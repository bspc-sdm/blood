package com.lifolo.users

import com.lifolo.appointments.P2Cache
import com.lifolo.auth.IdentityProvider
import com.lifolo.common.Sql
import com.lifolo.common.action.ActionContext
import com.lifolo.common.action.ActionHandler
import com.lifolo.common.action.ActionModule
import com.lifolo.notify.AccountMail
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Temporary account deactivation (Oct 2026) — the person switches the account off without deleting anything, and back
 * on whenever they like. No expiry: the daily job never deletes a deactivated account (only a delete_account request
 * does, after its 30 days).
 *
 * While `accounts.deactivated_at` is set:
 *  - every action except [com.lifolo.common.action.ActionPolicy.WHILE_DEACTIVATED] answers 423 `account_deactivated`
 *    (dispatcher), and so do the legacy blood endpoints (AuthService.verifyUser);
 *  - own listings are hidden like for a deletion, under their own marker: pharmacies, labs, ambulances and blood banks
 *    get `status = 'suspended'` with the old status in `hidden_for_deactivation`; the doctor/nurse profile (and with it
 *    every chamber — no new bookings) and home care are unlisted with the old flags in `deact_listed/active/home`.
 *    Appointments already booked (as patient or as the doctor) stay;
 *  - not a donor any more for nearby requests and emergency waves (GeoQuery.nearDonors), no phone push except the
 *    `account` category (PushService.send; the bell list still records them), caregiver links paused both ways
 *    (CareAccess.actAs, PushService.pushPatient), questions/answers/tips stay but the author shows as
 *    "নিষ্ক্রিয় ব্যবহারকারী" (CommunityActions).
 *
 * Deletion supersedes deactivation: deactivating while a deletion is pending is refused (409), and delete_account on a
 * deactivated account first lifts the deactivation ([clearForDeletion]) so the deletion's own hide/cancel applies.
 */
@Service
class AccountDeactivationService(
    private val jdbc: JdbcTemplate,
    private val accounts: AccountService,
    private val identity: IdentityProvider,
    private val mail: AccountMail,
    private val redis: StringRedisTemplate,
    private val events: org.springframework.context.ApplicationEventPublisher,
    txm: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(txm)

    /** `account_deactivate`: {ok, account, token} — `token` is a custom token to sign this device in again */
    fun deactivate(ctx: ActionContext): Any {
        val uid = ctx.user.id
        val now = System.currentTimeMillis()
        val changed = tx.execute {
            jdbc.update("INSERT INTO accounts (uid) VALUES (?) ON CONFLICT DO NOTHING", uid)
            val row = jdbc.queryForList("SELECT delete_at, deactivated_at FROM accounts WHERE uid = ? FOR UPDATE", uid).first()
            if (row["delete_at"] != null) {
                throw ctx.err(
                    409,
                    "Your account is waiting to be deleted, so it can't be deactivated. Log in again to cancel the deletion first.",
                    "আপনার অ্যাকাউন্ট মোছার অপেক্ষায় আছে, তাই সাময়িক বন্ধ করা যাবে না। আগে আবার লগইন করে মোছা বাতিল করুন।", // bn-ok
                    "deletion_pending",
                )
            }
            if (row["deactivated_at"] != null) return@execute false
            jdbc.update("UPDATE accounts SET deactivated_at = ? WHERE uid = ?", Sql.ts(now), uid)
            hideListings(uid)
            true
        } == true
        accounts.forgetDeactivation(uid)
        if (changed) {
            afterListingChange()
            // every other phone and browser is signed out (Firebase revokes per account, so this one too — the caller gets
            // a fresh custom token below); no device keeps getting notifications
            jdbc.update("DELETE FROM device_tokens WHERE uid = ?", uid)
            try {
                identity.revokeRefreshTokens(uid)
            } catch (e: Exception) {
                log.warn("revoke for deactivation failed: {}", e.message)
            }
            mail.deactivated(uid, ctx.user.email)
            // live in-app calls end, the call server drops the person (CallService)
            runCatching { events.publishEvent(com.lifolo.common.AccountCutOffEvent(uid, "deactivated")) }
        }
        val out = linkedMapOf<String, Any?>("ok" to true)
        if (!changed) out["unchanged"] = true
        out["account"] = accounts.state(uid)
        try {
            out["token"] = identity.createCustomToken(uid)
        } catch (e: Exception) {
            log.warn("custom token after deactivation failed: {}", e.message)
        }
        return out
    }

    /** `account_reactivate`: {ok, account} (`unchanged` when it was not deactivated) */
    fun reactivate(ctx: ActionContext): Any {
        val uid = ctx.user.id
        val changed = lift(uid)
        if (changed) mail.reactivated(uid, ctx.user.email)
        val out = linkedMapOf<String, Any?>("ok" to true)
        if (!changed) out["unchanged"] = true
        out["account"] = accounts.state(uid)
        return out
    }

    /** delete_account on a deactivated account: the deactivation ends first (no email; the deletion email follows) */
    fun clearForDeletion(uid: String): Boolean = lift(uid)

    private fun lift(uid: String): Boolean {
        val changed = tx.execute {
            val n = jdbc.update("UPDATE accounts SET deactivated_at = NULL WHERE uid = ? AND deactivated_at IS NOT NULL", uid)
            if (n > 0) restoreListings(uid)
            n > 0
        } == true
        accounts.forgetDeactivation(uid)
        if (changed) afterListingChange()
        return changed
    }

    private fun hideListings(uid: String) {
        for ((table, owner) in LISTINGS) {
            jdbc.update(
                """
                UPDATE $table SET hidden_for_deactivation = status, status = 'suspended'
                WHERE $owner = ? AND status <> 'suspended' AND hidden_for_deactivation IS NULL
                """.trimIndent(),
                uid,
            )
        }
        jdbc.update(
            """
            UPDATE providers SET deact_listed = listed, deact_active = active, deact_home = home_visit_enabled,
              listed = FALSE, active = FALSE, home_visit_enabled = FALSE
            WHERE uid = ? AND deact_listed IS NULL
            """.trimIndent(),
            uid,
        )
    }

    /**
     * Everything back as it was — unless an admin changed it meanwhile: a listing whose status is no longer `suspended`
     * keeps the admin's status; a provider the admin suspended meanwhile stays suspended (its "listed before" becomes the
     * pre-deactivation value, so lifting the suspension lists it again); a home-care block keeps home care off.
     */
    private fun restoreListings(uid: String) {
        for ((table, owner) in LISTINGS) {
            jdbc.update(
                """
                UPDATE $table SET
                  status = CASE WHEN status = 'suspended' THEN hidden_for_deactivation ELSE status END,
                  hidden_for_deactivation = NULL
                WHERE $owner = ? AND hidden_for_deactivation IS NOT NULL
                """.trimIndent(),
                uid,
            )
        }
        jdbc.update(
            """
            UPDATE providers SET
              listed = CASE WHEN admin_suspended THEN listed ELSE deact_listed END,
              active = CASE WHEN admin_suspended THEN active ELSE deact_active END,
              listed_before_suspend = CASE WHEN admin_suspended THEN deact_listed ELSE listed_before_suspend END,
              home_visit_enabled = CASE WHEN admin_suspended OR home_visit_blocked THEN home_visit_enabled ELSE COALESCE(deact_home, FALSE) END,
              deact_listed = NULL, deact_active = NULL, deact_home = NULL
            WHERE uid = ? AND deact_listed IS NOT NULL
            """.trimIndent(),
            uid,
        )
    }

    /** the cached doctor/home-care lists (2 min) and the one-minute guest answers */
    private fun afterListingChange() {
        try {
            redis.delete(P2Cache.PROVIDER_KEYS)
            val keys = redis.keys("guest:*")
            if (!keys.isNullOrEmpty()) redis.delete(keys)
        } catch (e: Exception) {
            log.warn("cache clear after deactivation failed: {}", e.message)
        }
    }

    companion object {
        /** listing table → owner column (like DeletionService.LISTINGS) */
        private val LISTINGS = listOf(
            "ambulances" to "owner_uid",
            "blood_banks" to "owner_uid",
            "pharmacies" to "uid",
            "labs" to "uid",
        )
    }
}

/** `account_deactivate`, `account_reactivate` — own account only (not in CARE_ACTIONS, so never as a caregiver) */
@Component
class AccountActions(private val deactivation: AccountDeactivationService) : ActionModule {
    override fun actions(): Map<String, ActionHandler> = mapOf(
        "account_deactivate" to ActionHandler { ctx -> deactivation.deactivate(ctx) },
        "account_reactivate" to ActionHandler { ctx -> deactivation.reactivate(ctx) },
    )
}
