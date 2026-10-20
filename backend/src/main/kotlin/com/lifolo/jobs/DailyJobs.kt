package com.lifolo.jobs

import com.lifolo.common.ApiException
import com.lifolo.common.CronAuth
import com.lifolo.common.LifoloProperties
import jakarta.servlet.http.HttpServletRequest
import org.jobrunr.jobs.annotations.Job
import org.jobrunr.jobs.annotations.Recurring
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * One step of the daily maintenance (api/cron/daily.js runs ~15 of them in a fixed order, each in its own
 * try/catch). Phase 2 packages add their steps as beans; [order] keeps the Node order (see PHASE2-PLAN.md).
 */
interface DailyTask {
    /** key in the cron response, e.g. "closed", "eligibleReminders", "inboxDeleted" */
    val name: String
    val order: Int
    fun run(nowMs: Long): Any?
}

@Service
class DailyJobRunner(private val tasks: ObjectProvider<DailyTask>) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** runs every step; a failing step is logged and does not stop the others (Node behaviour) */
    fun runAll(nowMs: Long): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>("ok" to true)
        for (t in tasks.orderedStream().toList().sortedBy { it.order }) {
            out[t.name] = try {
                t.run(nowMs)
            } catch (e: Exception) {
                log.error("daily task {} failed", t.name, e)
                null
            }
        }
        return out
    }
}

/** JobRunr schedules the daily run (the Node server relied on the Vercel cron at 03:00 UTC = 09:00 Dhaka). */
@Component
class DailyJobScheduler(private val runner: DailyJobRunner) {
    @Recurring(id = "daily-maintenance", cron = "0 3 * * *", zoneId = "UTC")
    @Job(name = "Daily maintenance")
    fun daily() {
        runner.runAll(System.currentTimeMillis())
    }
}

/**
 * GET /api/cron/daily with `Authorization: Bearer $CRON_SECRET` — manual/external trigger, same response shape as Node.
 */
@RestController
class CronController(private val runner: DailyJobRunner, private val props: LifoloProperties) {
    @RequestMapping("/api/cron/daily")
    fun daily(req: HttpServletRequest): ResponseEntity<Any> {
        if (req.method != "GET") return ResponseEntity.status(405).body(mapOf("error" to "Method not allowed"))
        if (!CronAuth.authorized(req, props.cronSecret)) throw ApiException(401, "অনুমতি নেই")
        return ResponseEntity.ok(runner.runAll(System.currentTimeMillis()))
    }
}
