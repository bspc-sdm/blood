package com.lifolo.auth

import com.lifolo.common.ApiException
import com.lifolo.common.ClientIp
import com.lifolo.common.DhakaTime
import com.lifolo.common.Ids
import com.lifolo.common.Sql
import com.lifolo.common.localDate
import com.lifolo.common.ms
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * The transactional parts of api/auth/send-code.js and api/auth/verify-code.js
 * (Firestore transactions → row locks with SELECT … FOR UPDATE).
 */
@Service
class LoginCodeService(private val jdbc: JdbcTemplate, private val codes: EmailCodes) {
    data class Reserved(val code: String, val rid: String)

    private data class Counter(val key: String, val scope: String)

    private fun lockCounter(c: Counter, day: LocalDate): Int {
        jdbc.update(
            "INSERT INTO rate_counters (key, scope, day, count) VALUES (?, ?, ?, 0) ON CONFLICT (key) DO NOTHING",
            c.key, c.scope, Sql.date(day),
        )
        return jdbc.query(
            "SELECT day, count FROM rate_counters WHERE key = ? FOR UPDATE",
            RowMapper { rs, _ -> if (rs.localDate("day") == day) rs.getInt("count") else 0 },
            c.key,
        ).first()
    }

    private fun setCounter(c: Counter, day: LocalDate, count: Int, now: Long) {
        jdbc.update("UPDATE rate_counters SET day = ?, count = ?, last_at = ? WHERE key = ?", Sql.date(day), count, Sql.ts(now), c.key)
    }

    /**
     * Daily caps: existing accounts and new addresses separately (so made-up addresses can't use up the quota of
     * real users), 50 per IP, 15 per email, one per minute. Keeps up to 3 live codes.
     */
    @Transactional
    fun reserve(email: String, exists: Boolean, ip: String, now: Long): Reserved {
        val h = EmailCodes.docIdFor(email)
        val day = DhakaTime.day(now)
        val ipCounter = Counter("ip_" + EmailCodes.docIdFor(ClientIp.key(ip)).take(40), "email_ip")
        val global = if (exists) Counter("_emailGlobalExisting", "email_global_existing") else Counter("_emailGlobalNew", "email_global_new")
        val globalCap = if (exists) DAILY_CAP_EXISTING else DAILY_CAP_NEW

        jdbc.update("INSERT INTO email_codes (email_hash) VALUES (?) ON CONFLICT (email_hash) DO NOTHING", h)
        data class Row(val lastSentAt: Long?, val sendDay: LocalDate?, val sendCount: Int)
        val row = jdbc.query(
            "SELECT last_sent_at, send_day, send_count FROM email_codes WHERE email_hash = ? FOR UPDATE",
            RowMapper { rs, _ -> Row(rs.ms("last_sent_at"), rs.localDate("send_day"), rs.getInt("send_count")) },
            h,
        ).first()
        val ipToday = lockCounter(ipCounter, day)
        val globalToday = lockCounter(global, day)

        if (ipToday >= DAILY_CAP_PER_IP) throw ApiException(429, "আজ এখান থেকে অনেকবার কোড চাওয়া হয়েছে। কাল আবার চেষ্টা করুন।")
        if (globalToday >= globalCap) throw ApiException(503, "আজকের মতো কোড পাঠানোর সীমা শেষ। কাল আবার চেষ্টা করুন।")
        if (row.lastSentAt != null && row.lastSentAt != 0L && now - row.lastSentAt < EmailCodes.RESEND_GAP_MS) {
            throw ApiException(429, "একটু আগেই কোড পাঠানো হয়েছে। ১ মিনিট পর আবার চেষ্টা করুন।")
        }
        val sentToday = if (row.sendDay == day) row.sendCount else 0
        if (sentToday >= EmailCodes.MAX_SENDS_PER_DAY) {
            throw ApiException(429, "আজ এই ইমেইলে অনেকবার কোড চাওয়া হয়েছে। কাল আবার চেষ্টা করুন।")
        }

        val code = codes.newCode()
        // rid: only the phone/browser that asked knows it — wrong codes from others can't burn this code
        val rid = Ids.randomHex(16)
        jdbc.update("DELETE FROM email_code_entries WHERE email_hash = ? AND expires_at <= ?", h, Sql.ts(now))
        jdbc.update(
            "INSERT INTO email_code_entries (email_hash, code_hash, rid, expires_at, fails) VALUES (?, ?, ?, ?, 0)",
            h, codes.hashCode(email, code), rid, Sql.ts(now + EmailCodes.CODE_TTL_MS),
        )
        jdbc.update(
            """
            DELETE FROM email_code_entries WHERE email_hash = ? AND id NOT IN
              (SELECT id FROM email_code_entries WHERE email_hash = ? ORDER BY id DESC LIMIT ?)
            """.trimIndent(),
            h, h, EmailCodes.MAX_LIVE_CODES,
        )
        setCounter(ipCounter, day, ipToday + 1, now)
        setCounter(global, day, globalToday + 1, now)
        jdbc.update(
            "UPDATE email_codes SET last_sent_at = ?, send_day = ?, send_count = ? WHERE email_hash = ?",
            Sql.ts(now), Sql.date(day), sentToday + 1, h,
        )
        return Reserved(code, rid)
    }

    /** email could not be sent: allow an immediate retry (Node sets lastSentAt = 0) */
    fun releaseResendGap(email: String) {
        jdbc.update("UPDATE email_codes SET last_sent_at = NULL WHERE email_hash = ?", EmailCodes.docIdFor(email))
    }

    /**
     * Checks a code. Returns false for a wrong code (the failure counters are committed), throws for
     * expired/blocked. With `rids` (new apps) failures only count against the caller's own codes.
     */
    @Transactional
    fun check(email: String, code: String, rids: List<String>, now: Long): Boolean {
        val h = EmailCodes.docIdFor(email)
        val day = DhakaTime.day(now)
        val hash = codes.hashCode(email, code)
        data class Row(val failDay: LocalDate?, val failCount: Int)
        val row = jdbc.query(
            "SELECT fail_day, fail_count FROM email_codes WHERE email_hash = ? FOR UPDATE",
            RowMapper { rs, _ -> Row(rs.localDate("fail_day"), rs.getInt("fail_count")) },
            h,
        ).firstOrNull()
        data class Entry(val id: Long, val codeHash: String, val rid: String?, val fails: Int)
        val live = if (row == null) {
            emptyList()
        } else {
            jdbc.query(
                "SELECT id, code_hash, rid, fails FROM email_code_entries WHERE email_hash = ? AND expires_at > ? ORDER BY id",
                RowMapper { rs, _ -> Entry(rs.getLong("id"), rs.getString("code_hash"), rs.getString("rid"), rs.getInt("fails")) },
                h, Sql.ts(now),
            )
        }
        if (row == null || live.isEmpty()) throw ApiException(400, "কোডের মেয়াদ শেষ বা কোড চাওয়া হয়নি। নতুন কোড চান।")

        if (rids.isNotEmpty()) {
            val mine = live.filter { it.rid != null && it.rid in rids && it.fails < EmailCodes.MAX_ATTEMPTS }
            if (mine.isEmpty()) throw ApiException(400, "কোডের মেয়াদ শেষ বা অনেকবার ভুল হয়েছে। নতুন কোড চান।")
            if (mine.any { EmailCodes.codesMatch(it.codeHash, hash) }) {
                jdbc.update("DELETE FROM email_code_entries WHERE email_hash = ?", h)
                return true
            }
            // wrong: count only on this person's codes (5 wrong → that code is dead); drop expired ones
            jdbc.update("DELETE FROM email_code_entries WHERE email_hash = ? AND expires_at <= ?", h, Sql.ts(now))
            for (e in mine) jdbc.update("UPDATE email_code_entries SET fails = fails + 1 WHERE id = ?", e.id)
            return false
        }

        // old apps without rid: 10 wrong codes per email per day
        val failsToday = if (row.failDay == day) row.failCount else 0
        if (failsToday >= EmailCodes.MAX_FAILS_PER_DAY) throw ApiException(429, "আজ অনেকবার ভুল কোড দেওয়া হয়েছে। কাল আবার চেষ্টা করুন।")
        if (live.none { EmailCodes.codesMatch(it.codeHash, hash) }) {
            jdbc.update("UPDATE email_codes SET fail_day = ?, fail_count = ? WHERE email_hash = ?", Sql.date(day), failsToday + 1, h)
            return false
        }
        // success: every live code is spent
        jdbc.update("DELETE FROM email_code_entries WHERE email_hash = ?", h)
        return true
    }

    companion object {
        const val DAILY_CAP_EXISTING = 300
        const val DAILY_CAP_NEW = 120
        const val DAILY_CAP_PER_IP = 50
    }
}
