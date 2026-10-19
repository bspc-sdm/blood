package com.lifolo.admin.kernel

import com.lifolo.common.DhakaTime
import com.lifolo.common.Ids
import com.lifolo.common.Sql
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDate

/**
 * sellerstats.js — the owner's own call statistics of a listing (only the owner sees them): calls per Dhaka day,
 * total, distinct callers, and the 10 most recent ratings/comments (without who gave them).
 * `kind`: ambulance | pharmacy | bloodbank | home (home = a home-care provider, id = provider uid).
 * Tables: `listing_stats`, `listing_stats_daily`, `listing_recent_ratings`.
 *
 * Writes never fail the calling action (Node catches and logs); they run in their own transaction.
 */
interface ListingStats {
    /**
     * bumpCalls: one call from the app — +1 on today's (Dhaka) counter and the total; +1 distinct caller when
     * [newCaller] (the caller's first call to this listing — the package knows from its calls table).
     * Callers de-duplicate repeated calls themselves (Node: 10 minutes) before bumping.
     */
    fun bumpCall(kind: String, listingId: String, nowMs: Long = System.currentTimeMillis(), newCaller: Boolean = false)

    /**
     * addRecentRating: keeps the newest 10 {rating, comment, at}; the same rater replaces their earlier entry
     * (identified only by `sha256("{collection}:{id}:{uid}")[0:16]`, the Node rater key).
     */
    fun addRecentRating(kind: String, listingId: String, callerUid: String, rating: Int, comment: String)

    /** removeRecentRating: account deletion — the rater's entry disappears from the owner's list */
    fun removeRecentRating(kind: String, listingId: String, callerUid: String)

    /**
     * callSummary: `{calls: {"7", "30", "90", "total"}, callers, daily: [30 numbers, oldest first]}` (Dhaka days,
     * today included).
     */
    fun summary(kind: String, listingId: String, nowMs: Long = System.currentTimeMillis()): Map<String, Any?>

    /** the recent ratings for the owner, newest first: [{rating, comment, at}] (Node: `recent` reversed, `who` removed) */
    fun recentComments(kind: String, listingId: String): List<Map<String, Any?>>

    /** the listing was removed: its statistics go too */
    fun delete(kind: String, listingId: String)

    companion object {
        val KINDS = setOf("ambulance", "pharmacy", "bloodbank", "home")

        /** Node collection of the stats document (part of the rater key) */
        fun collection(kind: String): String = when (kind) {
            "ambulance" -> "ambulanceStats"
            "pharmacy" -> "pharmacyStats"
            "bloodbank" -> "bloodBankStats"
            "home" -> "homeStats"
            else -> throw IllegalArgumentException("unknown stats kind $kind")
        }

        /** sellerstats.js raterKey */
        fun raterKey(kind: String, listingId: String, raterUid: String): String =
            Ids.sha256Hex("${collection(kind)}:$listingId:$raterUid").take(16)

        const val MAX_RECENT = 10
    }
}

@Service
class ListingStatsService(private val jdbc: JdbcTemplate, txm: PlatformTransactionManager) : ListingStats {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(txm).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }

    private fun ensure(kind: String, id: String) {
        jdbc.update("INSERT INTO listing_stats (kind, listing_id) VALUES (?, ?) ON CONFLICT DO NOTHING", kind, id)
    }

    override fun bumpCall(kind: String, listingId: String, nowMs: Long, newCaller: Boolean) {
        ListingStats.collection(kind)
        try {
            tx.executeWithoutResult {
                ensure(kind, listingId)
                jdbc.update(
                    "UPDATE listing_stats SET total = total + 1, callers = callers + ? WHERE kind = ? AND listing_id = ?",
                    if (newCaller) 1 else 0, kind, listingId,
                )
                jdbc.update(
                    """
                    INSERT INTO listing_stats_daily (kind, listing_id, day, calls) VALUES (?, ?, ?, 1)
                    ON CONFLICT (kind, listing_id, day) DO UPDATE SET calls = listing_stats_daily.calls + 1
                    """.trimIndent(),
                    kind, listingId, Sql.date(DhakaTime.day(nowMs)),
                )
            }
        } catch (e: Exception) {
            log.error("call stats failed: {}", e.message)
        }
    }

    override fun addRecentRating(kind: String, listingId: String, callerUid: String, rating: Int, comment: String) {
        val who = ListingStats.raterKey(kind, listingId, callerUid)
        try {
            tx.executeWithoutResult {
                ensure(kind, listingId)
                jdbc.queryForList("SELECT 1 FROM listing_stats WHERE kind = ? AND listing_id = ? FOR UPDATE", kind, listingId)
                jdbc.update("DELETE FROM listing_recent_ratings WHERE kind = ? AND listing_id = ? AND who = ?", kind, listingId, who)
                // keep the newest 9 others, then add this one (Node: filter(who).slice(-9) + new)
                jdbc.update(
                    """
                    DELETE FROM listing_recent_ratings WHERE kind = ? AND listing_id = ? AND who NOT IN (
                      SELECT who FROM listing_recent_ratings WHERE kind = ? AND listing_id = ? ORDER BY at DESC LIMIT ?)
                    """.trimIndent(),
                    kind, listingId, kind, listingId, ListingStats.MAX_RECENT - 1,
                )
                jdbc.update(
                    "INSERT INTO listing_recent_ratings (kind, listing_id, who, rating, comment, at) VALUES (?, ?, ?, ?, ?, clock_timestamp())",
                    kind, listingId, who, rating, comment,
                )
            }
        } catch (e: Exception) {
            log.error("rating stats failed: {}", e.message)
        }
    }

    override fun removeRecentRating(kind: String, listingId: String, callerUid: String) {
        val who = ListingStats.raterKey(kind, listingId, callerUid)
        try {
            tx.executeWithoutResult {
                jdbc.update("DELETE FROM listing_recent_ratings WHERE kind = ? AND listing_id = ? AND who = ?", kind, listingId, who)
            }
        } catch (e: Exception) {
            log.error("remove rating failed: {}", e.message)
        }
    }

    override fun summary(kind: String, listingId: String, nowMs: Long): Map<String, Any?> {
        val today = DhakaTime.day(nowMs)
        val days = HashMap<LocalDate, Int>()
        jdbc.query(
            "SELECT day, calls FROM listing_stats_daily WHERE kind = ? AND listing_id = ? AND day > ? AND day <= ?",
            RowCallbackHandler { rs -> days[rs.getObject("day", LocalDate::class.java)] = rs.getInt("calls") },
            kind, listingId, Sql.date(today.minusDays(90)), Sql.date(today),
        )
        val head = jdbc.queryForList("SELECT total, callers FROM listing_stats WHERE kind = ? AND listing_id = ?", kind, listingId).firstOrNull()
        fun sum(n: Long) = (0 until n).sumOf { days[today.minusDays(it)] ?: 0 }
        return linkedMapOf(
            "calls" to linkedMapOf(
                "7" to sum(7),
                "30" to sum(30),
                "90" to sum(90),
                "total" to ((head?.get("total") as? Number)?.toInt() ?: 0),
            ),
            "callers" to ((head?.get("callers") as? Number)?.toInt() ?: 0),
            "daily" to (29 downTo 0).map { days[today.minusDays(it.toLong())] ?: 0 },
        )
    }

    override fun recentComments(kind: String, listingId: String): List<Map<String, Any?>> = jdbc.query(
        "SELECT rating, comment, at FROM listing_recent_ratings WHERE kind = ? AND listing_id = ? ORDER BY at DESC, who LIMIT ?",
        { rs, _ -> linkedMapOf<String, Any?>("rating" to rs.getInt("rating"), "comment" to rs.getString("comment"), "at" to rs.getTimestamp("at").time) },
        kind, listingId, ListingStats.MAX_RECENT,
    )

    override fun delete(kind: String, listingId: String) {
        jdbc.update("DELETE FROM listing_stats WHERE kind = ? AND listing_id = ?", kind, listingId)
    }
}
