package com.lifolo.demo

import com.lifolo.ambulance.AmbRules
import com.lifolo.common.DhakaTime
import com.lifolo.common.Sql
import org.jobrunr.jobs.annotations.Job
import org.jobrunr.jobs.annotations.Recurring
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service

/**
 * Keeps a long-lived demo world current. The seed writes everything relative to the moment it ran: "today's"
 * serials and queue are on the seed day, the demo ambulances went "available" 30 min – 11.5 h before it (that lasts
 * [AmbRules.AVAILABLE_MS]). The production demo is seeded once and kept (deploy `--demo keep`), so after 12 hours no demo
 * car was available any more (no "অ্যাম্বুলেন্স ডাকুন") and from the next day the demo doctor had no "আজকের
 * সিরিয়াল" — the demo looked broken to reviewers and the Android journeys 2 and 12 failed (22 Oct 2026).
 *
 *  - a new Dhaka day: the demo is built again (wipe + every step — what the owner's "ডেমো বানান" does), so the
 *    day's serials, requests and dates belong to today; whatever testers changed in the demo goes, like on a re-seed.
 *  - in between: the demo cars' clock moves with real time (available/busy/reminder times shift by the time since the
 *    last run, never past now), so "available 30 minutes ago" stays 30 minutes ago and "on a trip" stays on a trip.
 *
 * No demo (never built, or wiped before a release) → nothing; a build in progress (partial) is left alone unless it
 * stopped over an hour ago (then built again).
 */
@Service
class DemoRefresh(private val jdbc: JdbcTemplate, private val seeder: DemoSeeder) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** one run: "none" (no demo), "busy" (being built), "rebuilt", or "clock" (cars moved; `shiftMs`) */
    fun refresh(nowMs: Long): Map<String, Any?> {
        val st = state() ?: return mapOf("done" to "none")
        val (partial, at) = st
        if (partial) {
            if (nowMs - at < STALE_BUILD_MS) return mapOf("done" to "busy")
            return rebuild(nowMs)
        }
        if (DhakaTime.day(at) < DhakaTime.day(nowMs)) return rebuild(nowMs)
        return mapOf("done" to "clock", "shiftMs" to shiftCars(nowMs))
    }

    private fun state(): Pair<Boolean, Long>? =
        jdbc.query("SELECT partial, at FROM demo_state") { rs, _ -> rs.getBoolean(1) to rs.getTimestamp(2).time }.firstOrNull()

    private fun rebuild(nowMs: Long): Map<String, Any?> {
        log.info("demo refresh: building the demo again for {}", DhakaTime.day(nowMs))
        seeder.seedAll()
        setMark(System.currentTimeMillis())
        return mapOf("done" to "rebuilt")
    }

    /**
     * Moves the demo cars' times forward by the time since the last move (or since the seed, if later). A time set after
     * that (a tester pressing "available") is not pushed past now.
     */
    fun shiftCars(nowMs: Long): Long {
        val seededAt = state()?.second ?: return 0
        val base = maxOf(mark() ?: 0L, seededAt)
        val delta = nowMs - base
        if (delta <= 0) return 0
        val now = Sql.ts(nowMs)
        jdbc.update(
            """
            UPDATE ambulances SET
              available_at = LEAST(available_at + ? * interval '1 millisecond', ?),
              busy_at = LEAST(busy_at + ? * interval '1 millisecond', ?),
              remind_at = LEAST(remind_at + ? * interval '1 millisecond', ?)
            WHERE owner_uid LIKE 'demo-%'
            """.trimIndent(),
            delta, now, delta, now, delta, Sql.ts(nowMs + AmbRules.REMIND_AT_MS),
        )
        setMark(nowMs)
        return delta
    }

    private fun mark(): Long? =
        jdbc.query("SELECT cursor FROM job_markers WHERE key = ?", { rs, _ -> rs.getString(1)?.toLongOrNull() }, MARK).firstOrNull()

    private fun setMark(ms: Long) {
        jdbc.update(
            "INSERT INTO job_markers (key, done, done_at, cursor) VALUES (?, TRUE, now(), ?) ON CONFLICT (key) DO UPDATE SET done_at = now(), cursor = EXCLUDED.cursor",
            MARK, ms.toString(),
        )
    }

    companion object {
        const val MARK = "demo-clock"
        const val STALE_BUILD_MS = 60 * 60 * 1000L
    }
}

/** every 10 minutes (the first run after Dhaka midnight builds the new day); `lifolo.demo-refresh=false` turns it off */
@Component
class DemoRefreshJob(private val refresh: DemoRefresh, @Value("\${lifolo.demo-refresh:true}") private val enabled: Boolean) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Recurring(id = "demo-refresh", cron = "*/10 * * * *", zoneId = "UTC")
    @Job(name = "Demo refresh")
    fun run() {
        if (!enabled) return
        try {
            refresh.refresh(System.currentTimeMillis())
        } catch (e: Exception) {
            log.error("demo refresh failed", e)
        }
    }
}
