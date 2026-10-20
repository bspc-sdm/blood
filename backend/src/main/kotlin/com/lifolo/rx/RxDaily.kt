package com.lifolo.rx

import com.lifolo.admin.kernel.AccountDeletionStep
import com.lifolo.admin.kernel.VerifyDocs
import com.lifolo.jobs.DailyTask
import com.lifolo.lab.LabStore
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/** daily step 8 (`rxReminders`) — prescriptions.js followUpReminders: 2 days before and the day before the follow-up */
@Component
class FollowUpReminders(private val jdbc: JdbcTemplate, private val push: RxPush) : DailyTask {
    override val name = "rxReminders"
    override val order = 8

    override fun run(nowMs: Long): Any {
        val today = RxRules.dhakaToday(nowMs)
        var sent = 0
        for (days in listOf(2, 1)) {
            val rows = jdbc.rows(
                "SELECT id, status, patient_uid, doctor_name, follow_up_note FROM prescriptions WHERE follow_up_date = ? ORDER BY id COLLATE \"C\" LIMIT 500",
                P3Sql.date(today + days * RxRules.DAY_MS),
            ) { rs -> listOf(rs.getString("id"), rs.getString("status"), rs.getString("patient_uid"), rs.getString("doctor_name") ?: "", rs.getString("follow_up_note") ?: "") }
            for ((id, status, patientUid, name, note) in rows) {
                if (status != "active" || patientUid.isNullOrEmpty()) continue
                push.patient(
                    patientUid,
                    if (days == 1) "Tomorrow: see $name again" else "In 2 days: see $name again",
                    if (days == 1) "কাল $name-এর কাছে আবার দেখানোর দিন" else "২ দিন পর $name-এর কাছে আবার দেখানোর দিন",
                    note ?: "",
                    linkedMapOf("type" to "rx", "id" to id, "rxId" to id),
                )
                sent++
            }
        }
        return linkedMapOf("sent" to sent)
    }
}

/** daily step 9 (`refill`) — rxnet.js refillReminders: bought medicines run out in 2 days */
@Component
class RefillReminders(private val jdbc: JdbcTemplate, private val store: PrescriptionStore, private val push: RxPush) : DailyTask {
    override val name = "refill"
    override val order = 9

    override fun run(nowMs: Long): Any {
        val today = RxRules.dhakaToday(nowMs)
        val day = P3Sql.date(today)
        var sent = 0
        val notified = jdbc.rows("SELECT id FROM prescriptions WHERE refill_at = ? AND refill_notified_at = ?", day, day) { it.getString("id") }.toSet()
        for (rx in store.load("refill_at = ? ORDER BY id COLLATE \"C\" LIMIT 500", day)) {
            if (rx.status != "active" || rx.patientUid.isNullOrEmpty() || rx.id in notified) continue
            val low = RxRules.medStatus(rx.medicines, rx.dispensed).filter { it.got > 0 && !(it.need != null && it.got >= it.need) }.map { it.name }
            push.patient(rx.patientUid, "Your medicines will run out in 2 days", "২ দিন পর ওষুধ শেষ হবে — কিনে নিন", low.joinToString(", "), linkedMapOf("type" to "rx", "id" to rx.id, "rxId" to rx.id))
            jdbc.update("UPDATE prescriptions SET refill_notified_at = ? WHERE id = ?", day, rx.id)
            sent++
        }
        return linkedMapOf("sent" to sent)
    }
}

/**
 * account.js for P3's data. hide: the own lab is suspended while the deletion waits (the old status in
 * `hidden_for_deletion`; DeletionService.cancel restores it). deleteNow: prescriptions/paper patients leave the
 * patient's account (the doctor's record stays), the patient's lab orders and review requests go, the own lab with its
 * price list and ranges goes (its orders stay with the patients, without home addresses), a doctor's own patient list goes.
 */
@Component
class RxLabDeletionStep(private val jdbc: JdbcTemplate, private val labs: LabStore, private val verify: VerifyDocs) : AccountDeletionStep {
    private val log = LoggerFactory.getLogger(javaClass)

    override val order = 300

    override fun hide(uid: String) {
        jdbc.update(
            "UPDATE labs SET hidden_for_deletion = COALESCE(status, 'pending'), status = 'suspended' WHERE uid = ? AND status <> 'suspended' AND hidden_for_deletion IS NULL",
            uid,
        )
    }

    override fun deleteNow(uid: String, email: String?) {
        try {
            jdbc.update("UPDATE prescriptions SET patient_uid = NULL WHERE patient_uid = ?", uid)
            jdbc.update("UPDATE rx_patients SET linked_uid = NULL WHERE linked_uid = ?", uid)
            jdbc.update("DELETE FROM rx_reviews WHERE patient_uid = ?", uid)
            jdbc.update("DELETE FROM lab_orders WHERE patient_uid = ?", uid)
            labs.stripHomes(uid)
            jdbc.update("DELETE FROM labs WHERE uid = ?", uid)
            runCatching { verify.delete("lab", uid) }
            jdbc.update("DELETE FROM rx_patients WHERE doctor_uid = ?", uid)
            jdbc.update("UPDATE user_state SET lab_home_address = NULL, lab_home_phone = NULL, lab_home_at = NULL WHERE uid = ?", uid)
        } catch (e: Exception) {
            log.error("rx cleanup failed", e)
        }
    }
}
