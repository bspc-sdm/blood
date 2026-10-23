package com.lifolo.payments

import com.lifolo.admin.kernel.AccountDeletionStep
import com.lifolo.appointments.ApptPayHook
import com.lifolo.auth.AuthUser
import com.lifolo.common.Sql
import com.lifolo.demo.DemoWorld
import com.lifolo.invoices.AmbFinishPayHook
import org.jobrunr.jobs.annotations.Job
import org.jobrunr.jobs.annotations.Recurring
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/** Lifolo Pay's hooks into a booking (the online fee / the chamber advance) and a finished ambulance trip (payLater) */
@Component
class PayHooks(
    private val pay: PayService,
    private val store: PayMethodStore,
    private val gate: PayGate,
    private val jdbc: JdbcTemplate,
) : ApptPayHook, AmbFinishPayHook {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun afterBook(appointmentId: String, nowMs: Long): String? {
        val a = jdbc.queryForList("SELECT online, pay_state, provider_uid, chamber_key FROM appointments WHERE id = ?", appointmentId).firstOrNull() ?: return null
        if (a["online"] == true) return if (a["pay_state"] != null) pay.ensureChamberFee(appointmentId, nowMs)?.id else null
        val doctor = a["provider_uid"] as String
        if (store.advanceOf(doctor, (a["chamber_key"] as String?) ?: "") == null || !gate.isOn(doctor, PayGate.CHAMBER_ADVANCE)) return null
        return try {
            pay.create("bn", null, null, "appointment", appointmentId, "booking_advance", nowMs, system = true).first.id
        } catch (e: Exception) {
            // no matured number yet, frozen, paused …: the booking stands, the advance is simply not asked
            log.info("no advance request for {}: {}", appointmentId, e.message)
            null
        }
    }

    override fun afterFinish(lang: String?, driver: AuthUser, reqId: String): Map<String, Any?>? = try {
        pay.create(lang, driver, driver.uid, "ambreq", reqId, "ambulance_fare", pay.clock.now()).first.toMap()
    } catch (e: Exception) {
        log.info("no fare request for {}: {}", reqId, e.message)
        null
    }
}

/**
 * Every 15 minutes (PAYMENTS.md "Jobs", security review P11): open requests expire (per purpose), a cancelled booking's
 * open request is cancelled, a claim nobody checked for 24 h reminds the business, after 72 h it is an admin flag, and a
 * payment the business must give back (it cancelled or did not deliver after the money came) becomes `refund_due`.
 */
@Component
class PayJobs(private val jdbc: JdbcTemplate, private val pay: PayService) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** returns what it did (for tests and the logs) */
    fun run(now: Long): Map<String, Int> {
        val ts = Sql.ts(now)
        // expiry (open or rejected requests whose time is over; chamber_fee never expires)
        val expired = jdbc.queryForList(
            "UPDATE pay_requests SET status = 'expired', updated_at = ? WHERE status IN ('open', 'rejected') AND expires_at IS NOT NULL AND expires_at <= ? RETURNING id, payer_uid, status",
            ts, ts,
        )
        for (r in expired) pay.event(r["id"] as String, "expire", null, r["payer_uid"] as String?, null, "expired", null, null, now)
        // a cancelled / declined appointment: its open request goes
        val cancelled = jdbc.queryForList(
            """
            UPDATE pay_requests q SET status = 'cancelled', updated_at = ? FROM appointments a
            WHERE q.ref_type = 'appointment' AND q.ref_id = a.id AND q.status IN ('open', 'rejected') AND a.status IN ('cancelled', 'declined', 'expired')
            RETURNING q.id, q.payer_uid
            """.trimIndent(),
            ts,
        )
        for (r in cancelled) pay.event(r["id"] as String, "cancel", null, r["payer_uid"] as String?, null, "cancelled", null, "appointment closed", now)
        // reminders: claimed for 24 h, nobody checked
        val remind = pay.query("status = 'claimed' AND claimed_at <= ? AND reminded_at IS NULL ORDER BY claimed_at LIMIT 500", Sql.ts(now - Pay.REMIND_AFTER_MS))
        for (r in remind) {
            if (jdbc.update("UPDATE pay_requests SET reminded_at = ? WHERE id = ? AND reminded_at IS NULL", ts, r.id) > 0) pay.notifyBiz(r, "pay_reminder")
        }
        // admin flags: claimed for 72 h
        val flagged = jdbc.update(
            "UPDATE pay_requests SET flagged_at = ? WHERE status = 'claimed' AND claimed_at <= ? AND flagged_at IS NULL", ts, Sql.ts(now - Pay.FLAG_AFTER_MS),
        )
        val due = refundDue(now)
        return linkedMapOf("expired" to expired.size, "cancelled" to cancelled.size, "reminded" to remind.size, "flagged" to flagged, "refundDue" to due)
    }

    /**
     * P11: refund_due — the business cancelled (or declined) after the money came, a patient cancelled an advance in
     * the refundable time, an order was cancelled or not delivered within 5 days, a home collection was cancelled. The
     * owner is pushed when it starts and once a day until the refund is recorded.
     */
    private fun refundDue(now: Long): Int {
        val ts = Sql.ts(now)
        val rules = listOf(
            "business_cancelled" to """
                q.ref_type = 'appointment' AND EXISTS (SELECT 1 FROM appointments a WHERE a.id = q.ref_id
                  AND (a.status = 'declined' OR (a.status = 'cancelled' AND coalesce(a.cancelled_by, '') <> 'patient')))
            """,
            "patient_cancelled_in_time" to """
                q.ref_type = 'appointment' AND q.refundable_until IS NOT NULL AND EXISTS (SELECT 1 FROM appointments a WHERE a.id = q.ref_id
                  AND a.status = 'cancelled' AND a.cancelled_by = 'patient' AND coalesce(a.updated_at, a.created_at) <= q.refundable_until)
            """,
            "order_cancelled" to """
                q.ref_type = 'rx_order' AND EXISTS (SELECT 1 FROM rx_orders o WHERE o.id = q.ref_id AND o.status IN ('cancelled', 'withdrawn'))
            """,
            "not_delivered" to """
                q.ref_type = 'rx_order' AND q.confirmed_at < ? AND EXISTS (SELECT 1 FROM rx_orders o WHERE o.id = q.ref_id AND o.status NOT IN ('delivered', 'done'))
            """,
            "collection_cancelled" to """
                q.ref_type = 'lab_order' AND EXISTS (SELECT 1 FROM lab_orders o WHERE o.id = q.ref_id
                  AND (o.status = 'cancelled' OR o.home_status = 'declined' OR o.home_cancelled_at IS NOT NULL))
            """,
        )
        var n = 0
        for ((why, cond) in rules) {
            val args = if (why == "not_delivered") arrayOf<Any?>(ts, why, Sql.ts(now - Pay.DELIVERY_DUE_MS)) else arrayOf<Any?>(ts, why)
            n += jdbc.update(
                "UPDATE pay_requests q SET refund_due_at = ?, refund_due_why = ?, reminded_at = NULL WHERE q.status = 'confirmed' AND q.refund_due_at IS NULL AND ${cond.trimIndent()}",
                *args,
            )
        }
        // the owner: when it starts, then once a day
        val owed = pay.query("status = 'confirmed' AND refund_due_at IS NOT NULL AND (reminded_at IS NULL OR reminded_at <= ?) LIMIT 500", Sql.ts(now - Pay.DAY))
        for (r in owed) {
            if (jdbc.update("UPDATE pay_requests SET reminded_at = ? WHERE id = ?", ts, r.id) > 0) {
                if (r.remindedAt == null) pay.event(r.id, "refund_due", null, r.payerUid, "confirmed", "confirmed", null, r.refundDueWhy, now)
                pay.notifyBiz(r, "pay_refund_due", ownerOnly = true)
            }
        }
        return n
    }
}

@Component
class PayJob(private val jobs: PayJobs, private val pgw: PgwService) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Recurring(id = "pay-jobs", cron = "*/15 * * * *", zoneId = "UTC")
    @Job(name = "Lifolo Pay: expiry, reminders, flags, refunds due, gateway reconcile")
    fun run() {
        try {
            jobs.run(System.currentTimeMillis())
        } catch (e: Exception) {
            log.error("pay jobs failed", e)
        }
        try {
            pgw.reconcile(System.currentTimeMillis())
        } catch (e: Exception) {
            log.error("pgw reconcile failed", e)
        }
    }
}

/**
 * Account deletion: demo accounts' payment rows go; real ones keep the business's record (TrxID, method, amount,
 * dates) with the person removed (payer, who claimed, sender digits on unconfirmed ones). A business's numbers go.
 */
@Component
class PayDeletionStep(private val jdbc: JdbcTemplate) : AccountDeletionStep {
    override val order = 455

    override fun deleteNow(uid: String, email: String?) {
        if (DemoWorld.isDemoUid(uid)) {
            jdbc.update("DELETE FROM pay_requests WHERE payer_uid = ? OR owner_uid = ? OR biz_id = ?", uid, uid, uid)
            jdbc.update("DELETE FROM pgw_payments WHERE uid = ?", uid)
        } else {
            jdbc.update(
                """
                UPDATE pay_requests SET payer_uid = NULL, claimed_by = CASE WHEN claimed_by = ? THEN NULL ELSE claimed_by END,
                  claim_last4 = CASE WHEN status IN ('confirmed', 'refund_claimed', 'refunded') THEN claim_last4 END, anonymised_at = now()
                WHERE payer_uid = ?
                """.trimIndent(),
                uid, uid,
            )
            jdbc.update("UPDATE pay_requests SET claimed_by = NULL WHERE claimed_by = ?", uid)
            jdbc.update("UPDATE pay_requests SET owner_uid = NULL, anonymised_at = now() WHERE owner_uid = ?", uid)
            jdbc.update("UPDATE pay_events SET by_uid = NULL WHERE by_uid = ?", uid)
            jdbc.update("UPDATE pay_events SET payer_uid = NULL WHERE payer_uid = ?", uid)
            jdbc.update("UPDATE pay_reports SET reporter_uid = NULL WHERE reporter_uid = ?", uid)
            jdbc.update("UPDATE pay_reports SET payer_uid = NULL WHERE payer_uid = ?", uid)
            jdbc.update("UPDATE pgw_payments SET uid = NULL WHERE uid = ?", uid)
        }
        jdbc.update("DELETE FROM pay_method_log WHERE biz_id = ? OR biz_id IN (SELECT id FROM ambulances WHERE owner_uid = ?) OR by_uid = ?", uid, uid, uid)
        jdbc.update("DELETE FROM pay_methods WHERE biz_id IN (SELECT id FROM ambulances WHERE owner_uid = ?)", uid)
        jdbc.update("DELETE FROM pay_claim_tries WHERE payer_uid = ?", uid)
        jdbc.update("DELETE FROM pay_methods WHERE biz_id = ? OR owner_uid = ?", uid, uid)
        jdbc.update("DELETE FROM pay_chamber_advance WHERE doctor_uid = ?", uid)
        jdbc.update("DELETE FROM pay_freezes WHERE biz_id = ?", uid)
    }
}
