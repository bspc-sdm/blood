package com.lifolo.calls

import com.lifolo.common.ApiException
import com.lifolo.common.Lang
import com.lifolo.contracts.ProviderAssistants
import com.lifolo.demo.DemoWorld
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Who may call whom (backend/docs/CALLS.md "Relations"): only people who already have something together right now —
 * a blood request and a donor who said "রক্ত দেব", a confirmed appointment around its time, an active ambulance call,
 * an active prescription / lab order. Everything else → 403 `call_not_allowed`.
 */
@Component
class CallRelations(private val jdbc: JdbcTemplate, private val assistants: ProviderAssistants) {
    /** the callee and whether the call defaults to video */
    data class Rel(val peer: String, val defaultVideo: Boolean)

    /** one appointment as the calls need it; [online] reads appointments.online when the column exists */
    data class ApptInfo(
        val id: String,
        val patientUid: String,
        val providerUid: String,
        val status: String,
        val date: LocalDate,
        val start: String,
        val online: Boolean,
        val providerSuspended: Boolean,
    ) {
        /** the slot in epoch ms (Dhaka time); no start time = the start of the day */
        val slotMs: Long get() = slotAt(date, start)

        /** the end of the slot's day when there is no start time (then the whole day counts) */
        val dayEndMs: Long get() = date.plusDays(1).atStartOfDay(DHAKA).toInstant().toEpochMilli()

        val hasStart: Boolean get() = TIME_RE.matches(start)

        /** [before] ms before the slot until [after] ms after it (no start time: after the end of the day) */
        fun within(nowMs: Long, before: Long, after: Long): Boolean =
            nowMs >= slotMs - before && nowMs <= (if (hasStart) slotMs else dayEndMs) + after
    }

    fun notAllowed(lang: String?): ApiException =
        ApiException(403, Lang.tr(lang, "You can't call this person in the app", "এই ব্যক্তিকে অ্যাপে কল করা যায় না"), "call_not_allowed") // bn-ok

    /** [uid]'s lower-case email when [verified], else "" (assistants are matched by verified email only) */
    fun isAssistantOf(doctorUid: String, email: String): Boolean = email.isNotEmpty() && assistants.isAssistant(doctorUid, email.lowercase())

    /** an account's stored email (for "is this peer an assistant of the doctor") */
    private fun accountEmail(uid: String): String =
        jdbc.queryForList("SELECT lower(email) FROM accounts WHERE uid = ?", String::class.java, uid).firstOrNull() ?: ""

    fun appointment(id: String): ApptInfo? = jdbc.query(
        """
        SELECT a.id, a.patient_uid, a.provider_uid, a.status, a.visit_date, coalesce(a.start_time, '') AS start_time,
          coalesce((to_jsonb(a) ->> 'online')::boolean, FALSE) AS online, coalesce(p.admin_suspended, FALSE) AS suspended
        FROM appointments a LEFT JOIN providers p ON p.uid = a.provider_uid WHERE a.id = ?
        """.trimIndent(),
        { rs, _ ->
            ApptInfo(
                rs.getString("id"), rs.getString("patient_uid"), rs.getString("provider_uid"), rs.getString("status"),
                rs.getObject("visit_date", LocalDate::class.java), rs.getString("start_time"), rs.getBoolean("online"), rs.getBoolean("suspended"),
            )
        },
        id,
    ).firstOrNull()

    /**
     * The callee for [caller] in [ctx]/[refId] (with the optional [peerUid]), or 403 `call_not_allowed`.
     * [callerEmail] = the caller's verified email ("" if not verified) for the doctor's assistants.
     */
    fun resolve(lang: String?, caller: String, callerEmail: String, ctx: String, refId: String, peerUid: String?, nowMs: Long): Rel {
        val no = { notAllowed(lang) }
        val rel = when (ctx) {
            "blood" -> blood(caller, refId, peerUid)
            "appointment" -> appointmentRel(caller, callerEmail, refId, peerUid, nowMs)
            "ambulance" -> ambulance(caller, refId, peerUid)
            "order" -> order(caller, refId, peerUid)
            else -> null
        } ?: throw no()
        if (rel.peer == caller || !DemoWorld.sameWorld(caller, rel.peer)) throw no()
        return rel
    }

    private fun blood(caller: String, id: String, peerUid: String?): Rel? {
        val r = jdbc.queryForList("SELECT requester_uid, status FROM blood_requests WHERE id = ?", id).firstOrNull() ?: return null
        if (r["status"] != "open") return null
        val requester = r["requester_uid"] as String
        fun said(donor: String) = jdbc.queryForList(
            "SELECT 1 FROM blood_responses WHERE request_id = ? AND donor_uid = ? AND status IN ('accepted', 'donated')", id, donor,
        ).isNotEmpty()
        return if (caller == requester) {
            if (peerUid == null || !said(peerUid)) null else Rel(peerUid, false)
        } else {
            if ((peerUid != null && peerUid != requester) || !said(caller)) null else Rel(requester, false)
        }
    }

    private fun appointmentRel(caller: String, callerEmail: String, id: String, peerUid: String?, nowMs: Long): Rel? {
        val a = appointment(id) ?: return null
        if (a.status != "confirmed" || a.providerSuspended) return null
        if (!a.within(nowMs, CALL_BEFORE_MS, AFTER_MS)) return null
        return when {
            caller == a.patientUid -> when {
                peerUid == null || peerUid == a.providerUid -> Rel(a.providerUid, a.online)
                isAssistantOf(a.providerUid, accountEmail(peerUid)) -> Rel(peerUid, a.online)
                else -> null
            }
            caller == a.providerUid || isAssistantOf(a.providerUid, callerEmail) ->
                if (peerUid != null && peerUid != a.patientUid) null else Rel(a.patientUid, a.online)
            else -> null
        }
    }

    private fun ambulance(caller: String, id: String, peerUid: String?): Rel? {
        val r = jdbc.queryForList("SELECT uid, status, active, accepted_by FROM ambulance_requests WHERE id = ?", id).firstOrNull() ?: return null
        val status = r["status"] as String
        if (r["active"] != true || status !in setOf("open", "accepted", "arrived")) return null
        val patient = r["uid"] as String
        val accepted = (r["accepted_by"] as String?)?.takeIf { status == "accepted" || status == "arrived" }
        fun offered(owner: String) = status == "open" && jdbc.queryForList(
            """
            SELECT 1 FROM ambreq_quotes q JOIN ambreq_targets t ON t.request_id = q.request_id AND t.owner_uid = q.owner_uid
            WHERE q.request_id = ? AND q.owner_uid = ? AND t.active AND NOT t.declined
            """.trimIndent(),
            id, owner,
        ).isNotEmpty()
        fun driver(owner: String) = owner == accepted || offered(owner)
        return if (caller == patient) {
            val peer = peerUid ?: accepted ?: return null
            if (driver(peer)) Rel(peer, false) else null
        } else {
            if ((peerUid != null && peerUid != patient) || !driver(caller)) null else Rel(patient, false)
        }
    }

    private fun order(caller: String, id: String, peerUid: String?): Rel? {
        val rx = jdbc.queryForList(
            """
            SELECT o.patient_uid, o.pharmacy_uid AS owner, o.status, p.status AS listing FROM rx_orders o
            LEFT JOIN pharmacies p ON p.uid = o.pharmacy_uid WHERE o.id = ?
            """.trimIndent(),
            id,
        ).firstOrNull()
        val row = if (rx != null) {
            if ((rx["status"] as String?) !in setOf("sent", "ready", "accepted", "out")) return null
            rx
        } else {
            val lab = jdbc.queryForList(
                "SELECT o.patient_uid, o.lab_uid AS owner, o.status, l.status AS listing FROM lab_orders o LEFT JOIN labs l ON l.uid = o.lab_uid WHERE o.id = ?",
                id,
            ).firstOrNull() ?: return null
            if ((lab["status"] as String?) !in setOf("sent", "received")) return null
            lab
        }
        if (row["listing"] == null || row["listing"] == "suspended") return null
        val patient = row["patient_uid"] as String? ?: return null
        val owner = row["owner"] as String
        return when (caller) {
            patient -> if (peerUid != null && peerUid != owner) null else Rel(owner, false)
            owner -> if (peerUid != null && peerUid != patient) null else Rel(patient, false)
            else -> null
        }
    }

    companion object {
        val DHAKA: ZoneId = ZoneId.of("Asia/Dhaka")
        private val TIME_RE = Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$")
        const val MINUTE_MS = 60_000L

        /** call_start for an appointment: from 30 min before the slot … */
        const val CALL_BEFORE_MS = 30 * MINUTE_MS

        /** appt_join: from 15 min before the slot … */
        const val JOIN_BEFORE_MS = 15 * MINUTE_MS

        /** … until 3 h after it */
        const val AFTER_MS = 3 * 60 * MINUTE_MS

        fun slotAt(date: LocalDate, start: String): Long {
            val t = if (TIME_RE.matches(start)) LocalTime.parse(start) else LocalTime.MIDNIGHT
            return LocalDateTime.of(date, t).atZone(DHAKA).toInstant().toEpochMilli()
        }
    }
}
