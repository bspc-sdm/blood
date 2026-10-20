package com.lifolo.common

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Daily per-user limits (community-db.js useQuota; serverState/{uid}.{kind}Day/{kind}Count → user_quotas).
 * Runs in its own transaction like the Node Firestore transaction: a used quota stays used even if the action
 * fails later.
 */
@Service
class QuotaService(private val jdbc: JdbcTemplate) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun use(lang: String?, uid: String, kind: String, limit: Int = Rules.LIMITS[kind] ?: error("unknown quota $kind")) {
        val day = DhakaTime.day()
        jdbc.update(
            "INSERT INTO user_quotas (uid, kind, day, count) VALUES (?, ?, ?, 0) ON CONFLICT (uid, kind) DO NOTHING",
            uid, kind, Sql.date(day),
        )
        val used = jdbc.query(
            "SELECT day, count FROM user_quotas WHERE uid = ? AND kind = ? FOR UPDATE",
            RowMapper { rs, _ -> if (rs.localDate("day") == day) rs.getInt("count") else 0 },
            uid, kind,
        ).first()
        if (used >= limit) {
            throw ApiException(429, Lang.tr(lang, "Daily limit reached. Try again tomorrow.", "আজকের সীমা শেষ। কাল আবার চেষ্টা করুন।"))
        }
        jdbc.update("UPDATE user_quotas SET day = ?, count = ? WHERE uid = ? AND kind = ?", Sql.date(day), used + 1, uid, kind)
    }
}
