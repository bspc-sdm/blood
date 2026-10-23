package com.lifolo.notify

import com.lifolo.auth.AuthService
import com.lifolo.common.ClientDisconnect
import com.lifolo.common.Lang
import jakarta.annotation.PreDestroy
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Server-Sent Events hub keyed by topic ("user:{uid}", "ambreq:{id}", …). Ready for live updates
 * (ambulance request status, queue position) in phase 2; nothing publishes yet.
 * Single-instance only: with several app instances, fan out through Redis pub/sub first. (Blue/green deploys run two
 * instances for ~20 s; nginx sends new streams to the new one and the old one closes its streams on shutdown —
 * the clients reconnect.)
 *
 * Keep-alive: every [keepAliveMs] (default 25 s) each open stream gets an SSE comment line (": ping"), which
 * clients ignore. Cloudflare's proxy closes a connection after 100 s without bytes, and other proxies or
 * mobile networks drop idle ones sooner; the ping also notices dead clients and frees their slot.
 */
@Component
class SseHub(@Value("\${lifolo.sse.keepalive-ms:25000}") val keepAliveMs: Long) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val topics = ConcurrentHashMap<String, CopyOnWriteArraySet<SseEmitter>>()

    /** how many keep-alive rounds have run (for tests and diagnostics) */
    val pingRounds = AtomicLong()

    /** how many streams were dropped because the client had gone (for tests and diagnostics) */
    val dropped = AtomicLong()

    /** new emitters (tests swap in emitters whose send fails like a closed socket) */
    internal var emitterFactory: (Long) -> SseEmitter = { SseEmitter(it) }

    private val pinger = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "sse-keepalive").apply { isDaemon = true }
    }.also { ex ->
        if (keepAliveMs > 0) ex.scheduleWithFixedDelay({ pingAll() }, keepAliveMs, keepAliveMs, TimeUnit.MILLISECONDS)
    }

    @PreDestroy
    fun stop() {
        pinger.shutdownNow()
    }

    /**
     * The app is shutting down (deploy: the old instance after nginx moved to the new one). Every open stream is closed
     * now, so the clients reconnect at once (to the new instance) and Tomcat's graceful shutdown does not wait for
     * 30-minute streams. Runs before the web server stops (ContextClosedEvent comes first in close()).
     */
    @EventListener(ContextClosedEvent::class)
    fun closeAll() {
        pinger.shutdownNow()
        for ((topic, set) in topics) {
            for (e in set) {
                set.remove(e)
                runCatching { e.complete() }
            }
            topics.remove(topic, set)
        }
    }

    /** one keep-alive round: a comment to every open stream; streams that fail are dropped. Returns how many got it. */
    fun pingAll(): Int {
        var n = 0
        try {
            for ((topic, set) in topics) {
                for (e in set) {
                    if (trySend(topic, set, e) { it.send(SseEmitter.event().comment("ping")) }) n++
                }
            }
        } catch (ex: Exception) {
            // never let one bad round stop the schedule
            log.warn("sse keep-alive round failed: {}", ex.message)
        } finally {
            pingRounds.incrementAndGet()
        }
        return n
    }

    /**
     * One send. A client that has gone (IOException "Broken pipe", AsyncRequestNotUsableException) or a stream that is
     * already complete (IllegalStateException) is normal life for a long-lived stream: the emitter is completed and
     * removed quietly, debug log only — never an error, never Sentry. Returns whether the send went out.
     */
    private fun trySend(topic: String, set: MutableSet<SseEmitter>, e: SseEmitter, send: (SseEmitter) -> Unit): Boolean =
        try {
            send(e)
            true
        } catch (ex: IOException) { // includes AsyncRequestNotUsableException
            drop(topic, set, e, ex)
            false
        } catch (ex: IllegalStateException) { // "ResponseBodyEmitter has already completed"
            drop(topic, set, e, ex)
            false
        } catch (ex: Exception) {
            if (ClientDisconnect.isClientDisconnect(ex)) {
                drop(topic, set, e, ex)
            } else {
                log.warn("sse send failed on {}: {}", topic, ex.toString())
                drop(topic, set, e, null)
            }
            false
        }

    private fun drop(topic: String, set: MutableSet<SseEmitter>, e: SseEmitter, ex: Exception?) {
        if (ex != null) log.debug("sse client gone on {}: {}", topic, ex.toString())
        set.remove(e)
        if (set.isEmpty()) topics.remove(topic, set)
        dropped.incrementAndGet()
        // complete() after a failed send is a no-op in Spring (the container ends the request); on a live stream it
        // closes it. Either way nothing may escape from here.
        try {
            e.complete()
        } catch (ignored: Exception) {
        }
    }

    /**
     * A new stream of [topic]. With [maxStreams], the oldest streams of the topic are closed first so that at most that
     * many stay open (one account opening streams in a loop cannot pile up connections).
     */
    fun subscribe(topic: String, timeoutMs: Long = 30 * 60 * 1000L, maxStreams: Int = Int.MAX_VALUE): SseEmitter {
        val emitter = emitterFactory(timeoutMs)
        val set = topics.computeIfAbsent(topic) { CopyOnWriteArraySet() }
        while (set.size >= maxStreams) {
            val oldest = set.firstOrNull() ?: break
            set.remove(oldest)
            runCatching { oldest.complete() }
        }
        set.add(emitter)
        val remove = Runnable {
            set.remove(emitter)
            if (set.isEmpty()) topics.remove(topic, set)
        }
        emitter.onCompletion(remove)
        // the 30-minute end of a stream is normal: complete it here, otherwise Spring raises AsyncRequestTimeoutException
        // ("unhandled error on /api/events" + Sentry for every tab open that long); the client simply reconnects
        emitter.onTimeout {
            remove.run()
            runCatching { emitter.complete() }
        }
        emitter.onError { remove.run() }
        return emitter
    }

    /**
     * Rewrites an event's data for its topic before it is sent (practice/TitleGuard: names shown to "user:<uid>"); set
     * once at start-up, null = as is.
     */
    @Volatile
    var filter: ((topic: String, data: Any) -> Any)? = null

    /** send to every subscriber of the topic; returns how many received it */
    fun publish(topic: String, event: String, data: Any): Int {
        val set = topics[topic] ?: return 0
        val out = filter?.let { f -> runCatching { f(topic, data) }.getOrDefault(data) } ?: data
        var n = 0
        for (e in set) {
            if (trySend(topic, set, e) { it.send(SseEmitter.event().name(event).data(out)) }) n++
        }
        return n
    }

    fun subscribers(topic: String): Int = topics[topic]?.size ?: 0

    /** send to every open topic starting with [prefix] (e.g. "user:" = everyone connected); returns how many received it */
    fun publishPrefix(prefix: String, event: String, data: Any): Int =
        topics.keys.filter { it.startsWith(prefix) }.sumOf { publish(it, event, data) }
}

/** GET /api/events — the caller's own stream ("user:{uid}"); needs the Bearer token */
@RestController
class SseController(private val hub: SseHub, private val auth: AuthService) {
    @GetMapping("/api/events", produces = ["text/event-stream"])
    fun events(req: HttpServletRequest): SseEmitter {
        val user = auth.decodeUser(req, Lang.resolve(null, req))
        return hub.subscribe("user:${user.id}", maxStreams = MAX_STREAMS_PER_USER)
    }

    companion object {
        /** a few phones/tabs of one account; the oldest stream closes when another one opens */
        const val MAX_STREAMS_PER_USER = 5
    }
}
