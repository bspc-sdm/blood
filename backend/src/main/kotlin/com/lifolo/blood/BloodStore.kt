package com.lifolo.blood

import com.lifolo.common.DhakaTime
import com.lifolo.common.Sql
import com.lifolo.common.localDate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/** Shared SQL of the blood package (requests, responses, per-day counters, last donation date). */
@Component
class BloodStore(val jdbc: JdbcTemplate, txm: PlatformTransactionManager) {
    /** one transaction (Node `db.runTransaction`); an exception rolls everything back */
    val tx = TransactionTemplate(txm)

    fun <T> inTx(block: () -> T): T = tx.execute { block() } as T

    fun request(id: String, forUpdate: Boolean = false): BloodRequest? =
        jdbc.query("SELECT * FROM blood_requests WHERE id = ?${if (forUpdate) " FOR UPDATE" else ""}", { rs, _ -> BloodRequest.of(rs) }, id)
            .firstOrNull()

    fun response(requestId: String, donorUid: String, forUpdate: Boolean = false): BloodResponse? =
        jdbc.query(
            "SELECT * FROM blood_responses WHERE request_id = ? AND donor_uid = ?${if (forUpdate) " FOR UPDATE" else ""}",
            { rs, _ -> BloodResponse.of(rs) },
            requestId, donorUid,
        ).firstOrNull()

    /** responses with status accepted or donated (Node `where('status', 'in', ['accepted', 'donated'])`) */
    fun activeResponses(requestId: String, forUpdate: Boolean = false): List<BloodResponse> = jdbc.query(
        "SELECT * FROM blood_responses WHERE request_id = ? AND status IN ('accepted', 'donated') ORDER BY created_at, donor_uid${if (forUpdate) " FOR UPDATE" else ""}",
        { rs, _ -> BloodResponse.of(rs) },
        requestId,
    )

    /** uids of the donors still "accepted" (Node limit 50 where it had one) */
    fun acceptedDonors(requestId: String, limit: Int = 50): List<String> = jdbc.queryForList(
        "SELECT donor_uid FROM blood_responses WHERE request_id = ? AND status = 'accepted' ORDER BY created_at, donor_uid LIMIT ?",
        String::class.java, requestId, limit,
    )

    /** the requester's open requests that are still active */
    fun activeOpenCount(uid: String, now: Long): Int = jdbc.query(
        "SELECT * FROM blood_requests WHERE requester_uid = ? AND status = 'open'",
        { rs, _ -> BloodRequest.of(rs) },
        uid,
    ).count { it.isActive(now) }

    /**
     * serverState `{kind}Day/{kind}Count` read inside a transaction (row locked): today's count, 0 on another day.
     * Kinds: create, request (notify), respond.
     */
    fun counterUsed(uid: String, kind: String, nowMs: Long): Int {
        val day = DhakaTime.day(nowMs)
        jdbc.update("INSERT INTO user_quotas (uid, kind, day, count) VALUES (?, ?, ?, 0) ON CONFLICT (uid, kind) DO NOTHING", uid, kind, Sql.date(day))
        return jdbc.query(
            "SELECT day, count FROM user_quotas WHERE uid = ? AND kind = ? FOR UPDATE",
            { rs, _ -> if (rs.localDate("day") == day) rs.getInt("count") else 0 },
            uid, kind,
        ).first()
    }

    fun counterSet(uid: String, kind: String, nowMs: Long, count: Int) {
        jdbc.update("UPDATE user_quotas SET day = ?, count = ? WHERE uid = ? AND kind = ?", Sql.date(DhakaTime.day(nowMs)), count, uid, kind)
    }

    fun lastDonation(uid: String, forUpdate: Boolean = false): Pair<Boolean, Long?> {
        val rows = jdbc.query(
            "SELECT last_donation_ms FROM user_profiles WHERE uid = ?${if (forUpdate) " FOR UPDATE" else ""}",
            { rs, _ -> rs.getLong(1).let { if (rs.wasNull()) null else it } },
            uid,
        )
        return if (rows.isEmpty()) false to null else true to rows[0]
    }

    fun setLastDonation(uid: String, ms: Long?) {
        jdbc.update("UPDATE user_profiles SET last_donation_ms = ? WHERE uid = ?", ms, uid)
    }

    fun profileExists(uid: String): Boolean = jdbc.queryForList("SELECT 1 FROM user_profiles WHERE uid = ?", uid).isNotEmpty()

    /** users/{uid}.commitments: the donor's "processing" list */
    fun addCommitment(uid: String, requestId: String, nowMs: Long) {
        jdbc.update(
            """
            INSERT INTO user_commitments (uid, request_id, committed_at) VALUES (?, ?, ?)
            ON CONFLICT (uid, request_id) DO UPDATE SET committed_at = EXCLUDED.committed_at
            """.trimIndent(),
            uid, requestId, Sql.ts(nowMs),
        )
    }
}
