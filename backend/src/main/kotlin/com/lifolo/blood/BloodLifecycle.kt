package com.lifolo.blood

import com.lifolo.admin.kernel.AccountDeletionStep
import com.lifolo.admin.kernel.ListingStats
import com.lifolo.admin.kernel.VerifyDocs
import com.lifolo.common.Lang
import com.lifolo.common.Rules
import com.lifolo.common.Sql
import com.lifolo.jobs.DailyTask
import com.lifolo.notify.PushItem
import com.lifolo.notify.PushService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** daily step 1 (api/cron/daily.js): open requests past their time → closed/expired; accepted donors are told */
@Component
class ExpireRequestsTask(private val store: BloodStore, private val ends: RequestEndService) : DailyTask {
    private val log = LoggerFactory.getLogger(javaClass)
    override val name = "closed"
    override val order = 1

    override fun run(nowMs: Long): Any? {
        val open = store.jdbc.query("SELECT * FROM blood_requests WHERE status = 'open'", { rs, _ -> BloodRequest.of(rs) })
        val expired = open.filter { !it.isActive(nowMs) }
        for (chunk in expired.chunked(400)) {
            store.jdbc.update(
                "UPDATE blood_requests SET status = 'closed', closed_at = ?, closed_by = 'expired' WHERE status = 'open' AND id IN (${chunk.joinToString(",") { "?" }})",
                *(listOf<Any>(Sql.ts(nowMs)) + chunk.map { it.id }).toTypedArray(),
            )
        }
        // donors still "accepted" on an expired request are told (so nobody goes for nothing, and they report a donation)
        try {
            for (r in expired.take(200)) ends.notifyEnded(r, "expired")
        } catch (e: Exception) {
            log.error("expiry notify failed", e)
        }
        return expired.size
    }
}

/** daily step 2: donors whose last donation was exactly 120 days ago (within the last day) can donate again */
@Component
class EligibleAgainTask(private val store: BloodStore, private val push: PushService) : DailyTask {
    override val name = "eligibleReminders"
    override val order = 2

    override fun run(nowMs: Long): Any? {
        val windowEnd = nowMs - BloodRules.ELIGIBILITY_DAYS * BloodRules.DAY_MS
        val windowStart = windowEnd - BloodRules.DAY_MS
        val items = store.jdbc.query(
            "SELECT uid, lang FROM user_profiles WHERE last_donation_ms > ? AND last_donation_ms <= ? AND notify",
            { rs, _ ->
                val lang = rs.getString("lang")
                PushItem(
                    rs.getString("uid"),
                    Lang.pick(lang, "You can donate blood again", "আপনি আবার রক্ত দিতে পারবেন"),
                    Lang.pick(lang, "Open the app to see if anyone nearby needs blood.", "অ্যাপ খুলে দেখুন আপনার আশেপাশে কারো রক্ত লাগবে কি না।"),
                    mapOf("type" to "eligible"),
                )
            },
            windowStart, windowEnd,
        )
        return push.send(items)
    }
}

/** daily step 4: emergency appeals past their time are closed and the "coming" donors told */
@Component
class CloseEmergenciesTask(private val emergencies: EmergencyService) : DailyTask {
    override val name = "emergenciesClosed"
    override val order = 4

    override fun run(nowMs: Long): Any? = emergencies.closeExpired(nowMs)
}

/**
 * account.js — the blood parts of an account deletion. [hide] (at `delete_account`): own blood banks are hidden
 * (`suspended`, previous status in `hidden_for_deletion`). [deleteNow] (30 days later): the donors still accepted
 * on own open requests are told (`removed`), own requests are deleted, own responses on others' requests are
 * withdrawn (donorLeft: counts fixed, requester told), correction requests, request reports, blood-bank calls,
 * emergency answers and own blood banks (+ statistics, documents) go.
 */
@Component
class BloodDeletionStep(
    private val store: BloodStore,
    private val ends: RequestEndService,
    private val stats: ListingStats,
    private val verify: VerifyDocs,
) : AccountDeletionStep {
    private val log = LoggerFactory.getLogger(javaClass)
    private val jdbc get() = store.jdbc
    override val order = 100

    override fun hide(uid: String) {
        jdbc.update(
            """
            UPDATE blood_banks SET hidden_for_deletion = status, status = 'suspended'
            WHERE owner_uid = ? AND status <> 'suspended' AND hidden_for_deletion IS NULL
            """.trimIndent(),
            uid,
        )
    }

    override fun deleteNow(uid: String, email: String?) {
        val mine = jdbc.query("SELECT * FROM blood_requests WHERE requester_uid = ?", { rs, _ -> BloodRequest.of(rs) }, uid)
        // accepted donors of own open requests: read before deleting, told afterwards
        val endedNotices = mine.filter { it.status == "open" }.map { it to store.acceptedDonors(it.id, 50) }
        val responded = (
            jdbc.queryForList("SELECT request_id FROM blood_responses WHERE donor_uid = ?", String::class.java, uid) +
                jdbc.queryForList("SELECT request_id FROM user_commitments WHERE uid = ?", String::class.java, uid)
            ).distinct().filter { Rules.isValidId(it) }
        jdbc.update("DELETE FROM blood_requests WHERE requester_uid = ?", uid)
        for (rid in responded) {
            try {
                ends.donorLeft(rid, uid)
            } catch (e: Exception) {
                log.error("donorLeft failed", e)
            }
        }
        jdbc.update("DELETE FROM correction_requests WHERE uid = ?", uid)
        jdbc.update("DELETE FROM blood_request_reports WHERE reporter_uid = ?", uid)
        jdbc.update("DELETE FROM blood_bank_calls WHERE uid = ?", uid)
        val banks = jdbc.queryForList("SELECT id FROM blood_banks WHERE owner_uid = ?", String::class.java, uid)
        jdbc.update("DELETE FROM blood_banks WHERE owner_uid = ?", uid)
        for (id in banks) {
            runCatching { stats.delete("bloodbank", id) }
            runCatching { verify.delete("bloodbank", id) }
        }
        for ((r, ids) in endedNotices) if (ids.isNotEmpty()) ends.notifyEnded(r, "removed", ids)
        jdbc.update("DELETE FROM emergency_donors WHERE uid = ?", uid)
    }
}
