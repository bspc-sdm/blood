package com.lifolo.contact

import com.lifolo.common.ApiException
import com.lifolo.common.Lang
import com.lifolo.contracts.ProviderAssistants
import com.lifolo.demo.DemoWorld
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * "Has [from] already contacted [to] about this record?" — for the blood donor rule (a donor may contact the requester
 * only after being confirmed or after the requester contacted them first). Calls implement it (call_sessions); messages
 * add their own bean. Any implementation saying yes is enough.
 */
fun interface ContactHistory {
    fun contacted(from: String, to: String, ctx: String, refId: String): Boolean
}

/**
 * Person-to-person blocks ("যোগাযোগ বন্ধ"): one block stops calls AND messages between the pair, in every context.
 * Table call_blocks (V609). [isBlocked] (a blocked b) is directional; [eitherWay] is what contact checks use.
 */
@Service
class BlockService(private val jdbc: JdbcTemplate) {
    fun isBlocked(blocker: String, blocked: String): Boolean =
        jdbc.queryForList("SELECT 1 FROM call_blocks WHERE blocker_uid = ? AND blocked_uid = ?", blocker, blocked).isNotEmpty()

    fun eitherWay(a: String, b: String): Boolean = jdbc.queryForList(
        "SELECT 1 FROM call_blocks WHERE (blocker_uid = ? AND blocked_uid = ?) OR (blocker_uid = ? AND blocked_uid = ?) LIMIT 1", a, b, b, a,
    ).isNotEmpty()

    /** true when this created the block; an unknown [blocked] account is ignored (false) */
    fun block(blocker: String, blocked: String, atMs: Long = System.currentTimeMillis()): Boolean {
        if (blocker == blocked || jdbc.queryForList("SELECT 1 FROM accounts WHERE uid = ?", blocked).isEmpty()) return false
        return jdbc.update(
            "INSERT INTO call_blocks (blocker_uid, blocked_uid, created_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
            blocker, blocked, java.sql.Timestamp(atMs),
        ) > 0
    }

    fun unblock(blocker: String, blocked: String): Boolean =
        jdbc.update("DELETE FROM call_blocks WHERE blocker_uid = ? AND blocked_uid = ?", blocker, blocked) > 0

    /** whom [blocker] blocked, newest first: (uid, blockedAt ms) */
    fun list(blocker: String, limit: Int = 200): List<Pair<String, Long>> = jdbc.query(
        "SELECT blocked_uid, created_at FROM call_blocks WHERE blocker_uid = ? ORDER BY created_at DESC LIMIT ?",
        { rs, _ -> rs.getString(1) to rs.getTimestamp(2).time },
        blocker, limit,
    )
}

/**
 * Who may contact whom — in-app calls and messages share this (backend/docs/CALLS.md "ContactPolicy"): only people who
 * already have something together right now — a blood request and a donor who said "রক্ত দেব" (the donor: once confirmed
 * or contacted first), a confirmed appointment around its time (doctor + assistants), an active ambulance call, an active
 * prescription order or lab order. Never demo ↔ real, never with a deactivated / deleting / disabled account, never
 * across a block. The caller's role always comes from the stored record, never from the request.
 */
@Service
class ContactPolicy(
    private val jdbc: JdbcTemplate,
    private val assistants: ProviderAssistants,
    private val blocks: BlockService,
    private val histories: org.springframework.beans.factory.ObjectProvider<ContactHistory>,
) {
    /** why contact is refused */
    enum class Refusal { NO_RELATION, BLOCKED_BY_ME, BLOCKED_BY_PEER, UNREACHABLE }

    /** the outcome of [decide]: the peer when allowed, else why not */
    data class Decision(val rel: Rel?, val refusal: Refusal?) {
        val allowed: Boolean get() = rel != null && refusal == null
    }

    /** the peer and whether a call defaults to video */
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

    /** an account that can take part: exists, not deactivated / being deleted / disabled (admin sanction) */
    fun reachable(uid: String): Boolean = jdbc.queryForList(
        "SELECT 1 FROM accounts WHERE uid = ? AND deactivated_at IS NULL AND delete_at IS NULL AND disabled_reason IS NULL", uid,
    ).isNotEmpty()

    /**
     * The full rule for [uid] contacting someone about [ctx]/[refId] (with the optional [peerUid]): relation (the
     * peer comes from the record), same world, both accounts reachable, no block either way.
     * [uidEmail] = [uid]'s verified email, lower case ("" if not verified) — the doctor's assistants are matched by it.
     */
    fun decide(uid: String, uidEmail: String, ctx: String, refId: String, peerUid: String?, nowMs: Long = System.currentTimeMillis()): Decision {
        val rel = check(uid, uidEmail, ctx, refId, peerUid, nowMs) ?: return Decision(null, Refusal.NO_RELATION)
        if (blocks.isBlocked(uid, rel.peer)) return Decision(rel, Refusal.BLOCKED_BY_ME)
        if (blocks.isBlocked(rel.peer, uid)) return Decision(rel, Refusal.BLOCKED_BY_PEER)
        if (!reachable(uid) || !reachable(rel.peer)) return Decision(rel, Refusal.UNREACHABLE)
        return Decision(rel, null)
    }

    /** shorthand: may [a] contact [b] about [ctx]/[refId] right now? */
    fun canContact(ctx: String, refId: String, a: String, b: String, aEmail: String = accountEmail(a), nowMs: Long = System.currentTimeMillis()): Boolean {
        val d = decide(a, aEmail, ctx, refId, b, nowMs)
        return d.allowed && d.rel!!.peer == b
    }

    /** the stored account email, lower case ("" when none) */
    fun accountEmail(uid: String): String =
        jdbc.queryForList("SELECT lower(coalesce(email, '')) FROM accounts WHERE uid = ?", String::class.java, uid).firstOrNull() ?: ""

    /** [uid]'s lower-case email when [verified], else "" (assistants are matched by verified email only) */
    fun isAssistantOf(doctorUid: String, email: String): Boolean = email.isNotEmpty() && assistants.isAssistant(doctorUid, email.lowercase())

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
    fun resolve(lang: String?, caller: String, callerEmail: String, ctx: String, refId: String, peerUid: String?, nowMs: Long): Rel =
        check(caller, callerEmail, ctx, refId, peerUid, nowMs) ?: throw notAllowed(lang)

    /**
     * The relation alone (no block / account checks — see [decide]). The caller's role always comes from the stored
     * record, never from the request.
     */
    fun check(caller: String, callerEmail: String, ctx: String, refId: String, peerUid: String?, nowMs: Long): Rel? {
        val rel = when (ctx) {
            "blood" -> blood(caller, refId, peerUid)
            "appointment" -> appointmentRel(caller, callerEmail, refId, peerUid, nowMs)
            "ambulance" -> ambulance(caller, refId, peerUid)
            "rx_order" -> rxOrder(caller, refId, peerUid)
            "lab_order" -> labOrder(caller, refId, peerUid)
            else -> null
        } ?: return null
        if (rel.peer == caller || !DemoWorld.sameWorld(caller, rel.peer)) return null
        return rel
    }

    /**
     * The requester may call any donor who said "রক্ত দেব" (accepted / donated). A donor may call the requester only
     * after the requester confirmed them, or after the requester called them once about this request (review R1).
     */
    private fun blood(caller: String, id: String, peerUid: String?): Rel? {
        val r = jdbc.queryForList("SELECT requester_uid, status FROM blood_requests WHERE id = ?", id).firstOrNull() ?: return null
        if (r["status"] != "open") return null
        val requester = r["requester_uid"] as String
        fun response(donor: String) = jdbc.queryForList(
            "SELECT confirmed FROM blood_responses WHERE request_id = ? AND donor_uid = ? AND status IN ('accepted', 'donated')", id, donor,
        ).firstOrNull()
        if (caller == requester) {
            return if (peerUid == null || response(peerUid) == null) null else Rel(peerUid, false)
        }
        if (peerUid != null && peerUid != requester) return null
        val resp = response(caller) ?: return null
        val allowed = resp["confirmed"] == true || histories.orderedStream().anyMatch { it.contacted(requester, caller, "blood", id) }
        return if (allowed) Rel(requester, false) else null
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

    /** a prescription order: the patient and the pharmacy owner, while sent / ready / accepted / out (review R5: own table) */
    private fun rxOrder(caller: String, id: String, peerUid: String?): Rel? {
        val row = jdbc.queryForList(
            """
            SELECT o.patient_uid, o.pharmacy_uid AS owner, o.status, p.status AS listing FROM rx_orders o
            LEFT JOIN pharmacies p ON p.uid = o.pharmacy_uid WHERE o.id = ?
            """.trimIndent(),
            id,
        ).firstOrNull() ?: return null
        if ((row["status"] as String?) !in setOf("sent", "ready", "accepted", "out")) return null
        return orderPeer(caller, row, peerUid)
    }

    /** a lab order: the patient and the lab owner, while sent / received */
    private fun labOrder(caller: String, id: String, peerUid: String?): Rel? {
        val row = jdbc.queryForList(
            "SELECT o.patient_uid, o.lab_uid AS owner, o.status, l.status AS listing FROM lab_orders o LEFT JOIN labs l ON l.uid = o.lab_uid WHERE o.id = ?",
            id,
        ).firstOrNull() ?: return null
        if ((row["status"] as String?) !in setOf("sent", "received")) return null
        return orderPeer(caller, row, peerUid)
    }

    private fun orderPeer(caller: String, row: Map<String, Any?>, peerUid: String?): Rel? {
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
