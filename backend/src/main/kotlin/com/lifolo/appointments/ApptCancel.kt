package com.lifolo.appointments

import com.lifolo.common.DhakaTime
import com.lifolo.common.Lang
import com.lifolo.common.Rules
import com.lifolo.common.Sql
import com.lifolo.contracts.ProviderSuspension
import com.lifolo.notify.PushItem
import com.lifolo.notify.PushService
import com.lifolo.users.DeletionService
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneOffset

/** appointments/{id} reads shared by the appointment, queue and cancel code */
@Component
class ApptRepo(private val jdbc: JdbcTemplate) {
    fun get(id: String, forUpdate: Boolean = false): Appt? =
        jdbc.query("SELECT * FROM appointments WHERE id = ?" + (if (forUpdate) " FOR UPDATE" else ""), { rs, _ -> Appt.of(rs) }, id).firstOrNull()

    /** queue.js todayAppts: a provider's appointments of one day (limit 500) */
    fun ofProviderOn(providerUid: String, date: Long): List<Appt> = jdbc.query(
        "SELECT * FROM appointments WHERE provider_uid = ? AND visit_date = ? ORDER BY created_at, id LIMIT 500",
        { rs, _ -> Appt.of(rs) },
        providerUid, Sql.date(DhakaTime.fromUtcMidnightMs(date)),
    )

    /** a provider's appointments in one status (apptcancel.js, limit 500) */
    fun ofProviderWithStatus(providerUid: String, status: String, limit: Int): List<Appt> = jdbc.query(
        "SELECT * FROM appointments WHERE provider_uid = ? AND status = ? ORDER BY created_at, id LIMIT ?",
        { rs, _ -> Appt.of(rs) },
        providerUid, status, limit,
    )

    companion object {
        /** appointments.js / apptcancel.js dateLabel: "19/10/2026" */
        fun dateLabel(ms: Long): String {
            val d = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
            return "${d.dayOfMonth}/${d.monthValue}/${d.year}"
        }
    }
}

/**
 * apptcancel.js (+ homecare.js blockHomeCare): when something changes on the doctor's side (chamber removed, days
 * changed, listing off, badge rejected, account suspended or deleted) the future appointments that no longer hold are
 * cancelled and the patients told — otherwise a patient would travel to an empty chamber.
 */
@Component
class ApptCancel(
    private val jdbc: JdbcTemplate,
    private val appts: ApptRepo,
    private val store: ProviderStore,
    private val users: P2Users,
    private val push: PushService,
    private val clock: P2Clock,
    private val deletion: DeletionService,
) : ProviderSuspension {
    private val log = LoggerFactory.getLogger(javaClass)

    /** stillValid: the appointment's chamber is still there, takes appointments and sits that day */
    fun stillValid(a: Appt, chambers: List<Ch>): Boolean {
        val key = a.key
        val ch = chambers.firstOrNull { it.key == key || it.hospitalName == a.chamber }
        return ch != null && ch.appointments && CareRules.chamberOpenOn(ch, a.date)
    }

    /**
     * cancelStale(providerUid, keep, reason): future pending/confirmed appointments with keep(a) false are cancelled
     * (`cancelled_by = 'system'`, the Bangla reason as provider note) and each patient is told. Returns how many.
     */
    fun cancelStale(providerUid: String, keep: (Appt) -> Boolean, reasonEn: String, reasonBn: String): Int {
        val today = DhakaTime.todayUtcMidnight(clock.now())
        val stale = listOf("pending", "confirmed").flatMap { appts.ofProviderWithStatus(providerUid, it, 500) }
            .filter { it.date >= today && !keep(it) }
        if (stale.isEmpty()) return 0
        val now = clock.now()
        for (chunk in stale.chunked(400)) {
            jdbc.update(
                "UPDATE appointments SET status = 'cancelled', cancelled_by = 'system', provider_note = ?, updated_at = ? WHERE id IN (${chunk.joinToString(",") { "?" }})",
                reasonBn, Sql.ts(now), *chunk.map { it.id }.toTypedArray(),
            )
        }
        try {
            val profiles = users.profiles(stale.map { it.patientUid })
            push.send(
                stale.filter { it.patientUid in profiles }.map { a ->
                    val lang = profiles[a.patientUid]!!.lang
                    PushItem(
                        a.patientUid,
                        Lang.pick(lang, "Appointment cancelled", "অ্যাপয়েন্টমেন্ট বাতিল হয়েছে"),
                        "${a.providerName} · ${ApptRepo.dateLabel(a.date)} · ${Lang.pick(lang, reasonEn, reasonBn)}",
                        mapOf("type" to "appointment", "role" to "patient") + com.lifolo.practice.Practice.privateTag(a.type),
                    )
                },
            )
        } catch (e: Exception) {
            log.error("stale appointment push failed", e)
        }
        return stale.size
    }

    override fun cancelStale(providerUid: String, reasonEn: String, reasonBn: String): Int =
        cancelStale(providerUid, { false }, reasonEn, reasonBn)

    override fun suspendProvider(uid: String, reason: String): Boolean {
        val p = store.load(uid) ?: return false
        if (p.adminSuspended) {
            // suspended for the account before, now a report was upheld too: lifting the account must not list again
            if (reason == "report" && p.suspendReason != "report") jdbc.update("UPDATE providers SET suspend_reason = 'report' WHERE uid = ?", uid)
            return false
        }
        jdbc.update(
            """
            UPDATE providers SET admin_suspended = TRUE, suspend_reason = ?, listed_before_suspend = ?, listed = FALSE, active = FALSE,
              home_visit_enabled = FALSE, home_visit_set = TRUE
            WHERE uid = ?
            """.trimIndent(),
            reason.ifEmpty { "admin" }, p.listed, uid,
        )
        cancelStale(uid, { false }, "The doctor/nurse is not available now", "ডাক্তার/নার্স এখন পাওয়া যাচ্ছে না")
        return true
    }

    override fun liftProvider(uid: String): Boolean = deletion.liftProvider(uid)

    override fun blockHomeCare(uid: String) {
        if (!Rules.isValidId(uid)) return
        jdbc.update("UPDATE providers SET home_visit_enabled = FALSE, home_visit_blocked = TRUE, home_visit_set = TRUE WHERE uid = ?", uid)
    }
}
