package com.lifolo.appointments

import com.lifolo.common.DhakaTime
import com.lifolo.common.Sql
import com.lifolo.notify.NotifyCats
import com.lifolo.notify.PushService
import org.jobrunr.jobs.annotations.Job
import org.jobrunr.jobs.annotations.Recurring
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * R8 (security review): a paid online appointment whose video window (slot + 3 h, like appt_join) passed while the
 * doctor never joined (`doctor_joined_at`, set by appt_join) and wrote no prescription for it: the patient is told how
 * to ask for the fee back / report, and the admins get a report. Once per appointment.
 */
@Component
class OnlineUnserved(
    private val jdbc: JdbcTemplate,
    private val push: PushService,
    private val payLog: ApptPayLog,
) {
    /** slot start (epoch ms) of an appointment: visit day + start "HH:MM" in Dhaka; no start = the end of that day */
    private fun windowEnd(a: Appt): Long {
        val m = Regex("^(\\d{2}):(\\d{2})$").find(a.start)
        if (m == null) return a.date + DAY - DHAKA_OFFSET
        val (h, mi) = m.destructured
        return a.date + (h.toLong() * 60 + mi.toLong()) * 60_000 - DHAKA_OFFSET + WINDOW_AFTER
    }

    /** returns how many patients were told */
    fun run(now: Long): Int {
        val today = DhakaTime.todayUtcMidnight(now)
        val due = jdbc.query(
            """
            SELECT a.* FROM appointments a
            WHERE a.online AND a.pay_state = 'paid' AND a.status IN ('confirmed', 'completed')
              AND a.doctor_joined_at IS NULL AND a.unserved_notified_at IS NULL
              AND a.visit_date BETWEEN ? AND ?
              AND NOT EXISTS (SELECT 1 FROM prescriptions p WHERE p.appointment_id = a.id)
            ORDER BY a.visit_date, a.id LIMIT 500
            """.trimIndent(),
            { rs, _ -> Appt.of(rs) },
            Sql.date(DhakaTime.fromUtcMidnightMs(today - 7 * DAY)), Sql.date(DhakaTime.fromUtcMidnightMs(today)),
        ).filter { windowEnd(it) <= now }
        var sent = 0
        for (a in due) {
            val mine = jdbc.update(
                "UPDATE appointments SET unserved_notified_at = ? WHERE id = ? AND unserved_notified_at IS NULL AND doctor_joined_at IS NULL",
                Sql.ts(now), a.id,
            ) > 0
            if (!mine) continue
            payLog.systemReport(a.id, "unserved: paid, the window passed and the doctor never joined")
            push.pushPatient(
                a.patientUid,
                "The doctor did not join — contact the doctor for a refund or report",
                "ডাক্তার যোগ দেননি — ফি ফেরত চাইতে ডাক্তারের সাথে যোগাযোগ করুন / রিপোর্ট করুন", // bn-ok
                "${a.providerName} · ${ApptRepo.dateLabel(a.date)}",
                mapOf("type" to "appointment", "role" to "patient", "id" to a.id, "sub" to "unserved", "cat" to NotifyCats.APPOINTMENTS),
            )
            sent++
        }
        return sent
    }

    companion object {
        const val DAY = 24L * 60 * 60 * 1000
        const val DHAKA_OFFSET = 6L * 60 * 60 * 1000
        const val WINDOW_AFTER = 3L * 60 * 60 * 1000
    }
}

/** every 15 minutes */
@Component
class OnlineUnservedJob(private val unserved: OnlineUnserved) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Recurring(id = "online-unserved", cron = "*/15 * * * *", zoneId = "UTC")
    @Job(name = "Online appointments: paid but the doctor never joined")
    fun run() {
        try {
            unserved.run(System.currentTimeMillis())
        } catch (e: Exception) {
            log.error("online unserved check failed", e)
        }
    }
}
