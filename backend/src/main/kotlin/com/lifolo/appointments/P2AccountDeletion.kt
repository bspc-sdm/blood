package com.lifolo.appointments

import com.lifolo.admin.kernel.AccountDeletionStep
import com.lifolo.admin.kernel.ListingStats
import com.lifolo.admin.kernel.Sanctions
import com.lifolo.care.CareActions
import com.lifolo.common.Lang
import com.lifolo.content.CommunityActions
import com.lifolo.content.HospitalActions
import com.lifolo.notify.PushItem
import com.lifolo.notify.PushService
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * account.js, the P2 parts:
 *  - schedule (`delete_account`): the doctor/nurse is suspended for the deletion (lists and home care off, future
 *    appointments cancelled with a notice);
 *  - deleteNow (30 days later): home-care block kept for the email, own home-care ratings taken out of the averages,
 *    questions (with answers), tips and own answers on others' questions deleted, open appointments cancelled with a
 *    notice to the other party and every appointment deleted, hospital requests left, caregiver links, queues, badge,
 *    provider document and home-care statistics deleted.
 */
@Component
class P2AccountDeletion(
    private val jdbc: JdbcTemplate,
    private val cancel: ApptCancel,
    private val sanctions: Sanctions,
    private val stats: ListingStats,
    private val community: CommunityActions,
    private val hospitals: HospitalActions,
    private val care: CareActions,
    private val queue: QueueActions,
    private val users: P2Users,
    private val push: PushService,
) : AccountDeletionStep {
    private val log = LoggerFactory.getLogger(javaClass)

    override val order: Int = 200

    override fun hide(uid: String) {
        try {
            cancel.suspendProvider(uid, "deletion")
        } catch (e: Exception) {
            log.error("suspend for deletion failed", e)
        }
    }

    /** account.js notify: a title-only notice (also without devices: the bell list) */
    private fun notify(uid: String, en: String, bn: String) {
        try {
            val p = users.profiles(listOf(uid))[uid] ?: return
            push.send(listOf(PushItem(uid, Lang.pick(p.lang, en, bn), "", mapOf("type" to "appointment"))))
        } catch (e: Exception) {
            log.error("delete-account push failed", e)
        }
    }

    override fun deleteNow(uid: String, email: String?) {
        // sanctions survive the account (a new account with the same email gets them again): the home-care block
        try {
            val blocked = jdbc.queryForList("SELECT home_visit_blocked FROM providers WHERE uid = ?", Boolean::class.java, uid).firstOrNull() == true
            sanctions.keepForEmail(email, blocked)
        } catch (e: Exception) {
            log.error("keep sanctions failed", e)
        }
        // own home-care ratings out of the averages and the owner's recent comments
        val rated = jdbc.query(
            "SELECT provider_uid, rating FROM home_calls WHERE uid = ? AND rating IS NOT NULL AND rating > 0 LIMIT 1000",
            { rs, _ -> rs.getString(1) to rs.getInt(2) },
            uid,
        )
        for ((provider, rating) in rated) {
            try {
                jdbc.update("UPDATE providers SET rating_sum = rating_sum - ?, rating_count = rating_count - 1 WHERE uid = ?", rating, provider)
            } catch (e: Exception) {
                log.warn("home rating cleanup failed: {}", e.message)
            }
            stats.removeRecentRating("home", provider, uid)
        }
        // questions (with their answers), tips; own answers on others' questions
        jdbc.update("DELETE FROM questions WHERE uid = ?", uid)
        jdbc.update("DELETE FROM tips WHERE uid = ?", uid)
        community.deleteAnswersBy(uid)
        // appointments: tell the other party of open ones, then delete every record
        val asPatient = jdbc.query("SELECT * FROM appointments WHERE patient_uid = ?", { rs, _ -> Appt.of(rs) }, uid)
        val asProvider = jdbc.query("SELECT * FROM appointments WHERE provider_uid = ?", { rs, _ -> Appt.of(rs) }, uid)
        for (a in asPatient) {
            if (a.status == "pending" || a.status == "confirmed") notify(a.providerUid, "Appointment cancelled", "অ্যাপয়েন্টমেন্ট বাতিল হয়েছে")
        }
        for (a in asProvider) {
            if (a.status == "pending" || a.status == "confirmed") notify(a.patientUid, "Appointment cancelled", "অ্যাপয়েন্টমেন্ট বাতিল হয়েছে")
        }
        jdbc.update("DELETE FROM appointments WHERE patient_uid = ? OR provider_uid = ?", uid, uid)
        // hospital requests: only this user leaves (others may have asked too)
        hospitals.forgetRequester(uid)
        // home-care calls made and received
        jdbc.update("DELETE FROM home_calls WHERE uid = ? OR provider_uid = ?", uid, uid)
        care.deleteFor(uid)
        queue.deleteFor(uid)
        jdbc.update("DELETE FROM badges WHERE uid = ?", uid)
        jdbc.update("DELETE FROM providers WHERE uid = ?", uid)
        stats.delete("home", uid)
    }
}
