package com.lifolo.appointments

import com.lifolo.common.DhakaTime
import com.lifolo.common.Lang
import com.lifolo.common.Sql
import com.lifolo.jobs.DailyTask
import com.lifolo.notify.PushItem
import com.lifolo.notify.PushService
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * reminders.js dailyReminders — daily cron step 3 (`care`), 9 am Dhaka: requests nobody answered expire (date passed or
 * 48 h without a reply), confirmed appointments 3 days after the date become completed (phones no longer shown),
 * today's appointments are announced to patients and providers, providers hear about requests waiting > 12 h.
 */
@Component
class AppointmentReminders(
    private val jdbc: JdbcTemplate,
    private val users: P2Users,
    private val push: PushService,
) : DailyTask {
    override val name = "care"
    override val order = 3

    override fun run(nowMs: Long): Any? = dailyReminders(nowMs)

    private fun list(sql: String, vararg args: Any?): List<Appt> = jdbc.query(sql, { rs, _ -> Appt.of(rs) }, *args)

    fun dailyReminders(now: Long): Map<String, Any?> {
        val today = DhakaTime.todayUtcMidnight(now)
        val todayDay = Sql.date(DhakaTime.fromUtcMidnightMs(today))
        val confirmed = list("SELECT * FROM appointments WHERE visit_date = ? AND status = 'confirmed' ORDER BY created_at, id LIMIT 1000", todayDay)
        val pending = list("SELECT * FROM appointments WHERE status = 'pending' ORDER BY created_at, id LIMIT 1000")
        val allConfirmed = list("SELECT * FROM appointments WHERE status = 'confirmed' ORDER BY created_at, id LIMIT 2000")
        // unanswered: date passed, or no reply in 48 h — the patient knows and can ask another doctor
        val stale = pending.filter { it.date < today || now - (if (it.createdAt != 0L) it.createdAt else now) > 48L * 60 * 60 * 1000 }
        for (chunk in stale.chunked(400)) {
            jdbc.update(
                "UPDATE appointments SET status = 'expired', updated_at = ? WHERE id IN (${chunk.joinToString(",") { "?" }})",
                Sql.ts(now), *chunk.map { it.id }.toTypedArray(),
            )
        }
        // 3 days after the date a confirmed appointment is "completed" — phones are no longer shown
        val done = allConfirmed.filter { it.date < today - 3 * CareRules.DAY_MS }
        for (chunk in done.chunked(400)) {
            jdbc.update(
                "UPDATE appointments SET status = 'completed', updated_at = ? WHERE id IN (${chunk.joinToString(",") { "?" }})",
                Sql.ts(now), *chunk.map { it.id }.toTypedArray(),
            )
        }
        val staleIds = stale.map { it.id }.toSet()
        val waiting = pending.filter { it.id !in staleIds && now - (if (it.createdAt != 0L) it.createdAt else now) > 12L * 60 * 60 * 1000 }
        val u = users.profiles(
            confirmed.flatMap { listOf(it.patientUid, it.providerUid) } + waiting.map { it.providerUid } + stale.map { it.patientUid },
        )
        val out = ArrayList<PushItem>()
        fun msg(uid: String, en: String, bn: String, bodyEn: String, bodyBn: String, role: String) {
            // without a profile nothing; without tokens it still goes to the bell list
            val p = u[uid] ?: return
            out.add(PushItem(uid, Lang.pick(p.lang, en, bn), Lang.pick(p.lang, bodyEn, bodyBn), mapOf("type" to "appointment", "role" to role)))
        }
        // patient: the doctor did not answer
        for (d in stale) {
            msg(
                d.patientUid, "No reply to your appointment request", "অ্যাপয়েন্টমেন্টের অনুরোধে সাড়া আসেনি",
                "${d.providerName} didn't reply. You can request another doctor.",
                "${d.providerName} সাড়া দেননি। অন্য ডাক্তারের কাছে অনুরোধ করতে পারেন।", "patient",
            )
        }
        // patient: an appointment today
        for (d in confirmed) {
            val serial = if ((d.serial ?: 0) != 0) " · #${d.serial}" else ""
            msg(
                d.patientUid, "Appointment today", "আজ অ্যাপয়েন্টমেন্ট আছে",
                "${d.providerName} · ${d.chamber}$serial",
                "${d.providerName} · ${d.chamberBn.ifEmpty { d.chamber }}${Lang.bnNumbers(serial)}", "patient",
            )
        }
        // provider: how many today, and how many requests wait
        fun countBy(list: List<Appt>): LinkedHashMap<String, Int> {
            val m = LinkedHashMap<String, Int>()
            list.forEach { m[it.providerUid] = (m[it.providerUid] ?: 0) + 1 }
            return m
        }
        for ((uid, n) in countBy(confirmed)) {
            msg(uid, "Your appointments today", "আজকের অ্যাপয়েন্টমেন্ট", "$n confirmed", "${Lang.bnDigits(n.toString())}টি নিশ্চিত", "provider")
        }
        for ((uid, n) in countBy(waiting)) {
            msg(
                uid, "Appointment requests waiting", "অ্যাপয়েন্টমেন্টের অনুরোধ অপেক্ষায়",
                "$n patient(s) are waiting for your reply", "${Lang.bnDigits(n.toString())} জন রোগী উত্তরের অপেক্ষায়", "provider",
            )
        }
        val sent = if (out.isNotEmpty()) push.send(out) else 0
        return linkedMapOf("appointmentsToday" to confirmed.size, "waiting" to waiting.size, "sent" to sent)
    }
}
