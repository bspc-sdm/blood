package com.lifolo.jobs

import com.lifolo.common.ApiException
import com.lifolo.common.CronAuth
import com.lifolo.common.LifoloProperties
import jakarta.servlet.http.HttpServletRequest
import org.jobrunr.jobs.filters.RetryFilter
import org.jobrunr.server.BackgroundJobServer
import org.jobrunr.server.BackgroundJobServerConfiguration
import org.jobrunr.server.JobActivator
import org.jobrunr.spring.autoconfigure.JobRunrProperties
import org.jobrunr.storage.StorageProvider
import org.jobrunr.utils.mapper.JsonMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Exactly one app instance runs the JobRunr background jobs (the @Recurring sweeps: ambulance timers, call timeouts,
 * storage trash, pay jobs, the daily run …), also during a blue/green deploy (deploy/deploy-backend.sh), when two
 * instances run side by side for ~20 s:
 *
 *  1. the new instance starts with `LIFOLO_JOBS_HOLD=true` (lifolo.jobs.hold): its BackgroundJobServer is created but
 *     not started — no polling, no master election, no job runs; it serves HTTP normally
 *  2. after nginx moved to the new instance, the deploy calls `POST /api/cron/jobs?do=stop` on the old instance (its
 *     server stops gracefully and leaves the servers table) and then `POST /api/cron/jobs?do=start` on the new one
 *  3. the old instance is stopped after the drain
 *
 * Why not rely on JobRunr's own database locking (several servers may share one database and a job still runs once)?
 * It would be safe for double runs, but during the overlap the OLD release (old code) could pick up a job the NEW release
 * enqueued, or stay master and schedule the recurring jobs from the old definitions; and a job taken by the old
 * instance just before it stops is cut off and only retried after JobRunr's orphan timeout. The hand-over keeps it
 * simple: one instance, the current release, runs jobs at any time; the gap is a few seconds (the sweeps run every
 * 15 s – 1 min and catch up).
 *
 * Without the hold flag (a normal start, a reboot, systemd restarting a crashed instance) the server starts at
 * ApplicationReady exactly as JobRunr's starter does. Tests disable the server (application-test.yml) — no bean here.
 */
@Configuration
@ConditionalOnProperty(prefix = "jobrunr.background-job-server", name = ["enabled"], havingValue = "true")
class JobsServerConfig {
    /** replaces JobRunr's own bean (@ConditionalOnMissingBean there); same construction as JobRunrAutoConfiguration */
    @Bean
    fun backgroundJobServer(
        storageProvider: StorageProvider,
        jobRunrJsonMapper: JsonMapper,
        jobActivator: JobActivator,
        backgroundJobServerConfiguration: BackgroundJobServerConfiguration,
        properties: JobRunrProperties,
        @Value("\${lifolo.jobs.hold:false}") hold: Boolean,
    ): BackgroundJobServer {
        val server = HeldBackgroundJobServer(storageProvider, jobRunrJsonMapper, jobActivator, backgroundJobServerConfiguration, hold)
        server.setJobFilters(listOf(RetryFilter(properties.jobs.defaultNumberOfRetries, properties.jobs.retryBackOffTimeSeed)))
        return server
    }
}

/** a BackgroundJobServer whose start (JobRunrStarter, at ApplicationReady) can be held until the deploy releases it */
class HeldBackgroundJobServer(
    storageProvider: StorageProvider,
    jsonMapper: JsonMapper,
    jobActivator: JobActivator,
    configuration: BackgroundJobServerConfiguration,
    hold: Boolean,
) : BackgroundJobServer(storageProvider, jsonMapper, jobActivator, configuration) {
    private val log = LoggerFactory.getLogger(HeldBackgroundJobServer::class.java)
    private val gate = JobsGateState(hold)

    val held: Boolean get() = gate.held
    val handedOver: Boolean get() = gate.handedOver

    /** running | held | stopped | handed_over */
    fun state(): String = gate.state(isRunning)

    override fun start() {
        if (!gate.mayStart()) {
            log.info("JobRunr: background jobs held (LIFOLO_JOBS_HOLD) — the deploy starts them after the switch")
            return
        }
        super.start()
    }

    /** the deploy's "start": release the hold and start (no-op when already running) */
    @Synchronized
    fun release() {
        gate.release()
        if (!isRunning) super.start()
        log.info("JobRunr: background jobs started (deploy hand-over)")
    }

    /** the deploy's "stop" on the old instance: stop for good (a later start is refused) */
    @Synchronized
    fun handOver() {
        gate.handOver()
        stop()
        log.info("JobRunr: background jobs stopped (handed over to the new instance)")
    }
}

/** the hold/hand-over rules, apart from JobRunr (unit-tested) */
class JobsGateState(hold: Boolean) {
    @Volatile var held: Boolean = hold
        private set

    @Volatile var handedOver: Boolean = false
        private set

    /** JobRunr's own start at ApplicationReady: only without the hold and before a hand-over */
    fun mayStart(): Boolean = !held && !handedOver

    fun release() {
        if (handedOver) throw IllegalStateException("jobs were handed over to another instance")
        held = false
    }

    fun handOver() {
        handedOver = true
        held = false
    }

    fun state(running: Boolean): String = when {
        running -> "running"
        handedOver -> "handed_over"
        held -> "held"
        else -> "stopped"
    }
}

/**
 * POST /api/cron/jobs?do=status|start|stop with `Authorization: Bearer $CRON_SECRET` — the blue/green deploy's
 * hand-over (127.0.0.1 only: nginx answers 404 for /api/cron/ from outside).
 * Answer: `{ ok, server: running|held|stopped|handed_over|off, master }`.
 */
@RestController
class JobsGateController(private val props: LifoloProperties, private val server: ObjectProvider<BackgroundJobServer>) {
    @RequestMapping("/api/cron/jobs")
    fun jobs(req: HttpServletRequest): ResponseEntity<Any> {
        if (req.method != "POST") return ResponseEntity.status(405).body(mapOf("error" to "Method not allowed"))
        if (!CronAuth.authorized(req, props.cronSecret)) throw ApiException(401, "অনুমতি নেই")
        val s = server.ifAvailable ?: return ResponseEntity.ok(mapOf("ok" to true, "server" to "off", "master" to false))
        val held = s as? HeldBackgroundJobServer
        when (req.getParameter("do") ?: "status") {
            "status" -> {}
            "start" -> {
                if (held?.handedOver == true) return ResponseEntity.status(409).body(mapOf("ok" to false, "error" to "handed_over"))
                if (held != null) held.release() else if (!s.isRunning) s.start()
            }
            "stop" -> if (held != null) held.handOver() else s.stop()
            else -> return ResponseEntity.badRequest().body(mapOf("ok" to false, "error" to "do=status|start|stop"))
        }
        val running = s.isRunning
        val state = held?.state() ?: if (running) "running" else "stopped"
        return ResponseEntity.ok(mapOf("ok" to true, "server" to state, "master" to (running && runCatching { s.isMaster }.getOrDefault(false))))
    }
}
