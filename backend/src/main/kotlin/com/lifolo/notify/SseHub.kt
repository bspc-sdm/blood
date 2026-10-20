package com.lifolo.notify

import com.lifolo.auth.AuthService
import com.lifolo.common.Lang
import jakarta.annotation.PreDestroy
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Server-Sent Events hub keyed by topic ("user:{uid}", "ambreq:{id}", …). Ready for live updates
 * (ambulance request status, queue position) in phase 2; nothing publishes yet.
 * Single-instance only: with several app instances, fan out through Redis pub/sub first.
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

    private val pinger = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "sse-keepalive").apply { isDaemon = true }
    }.also { ex ->
        if (keepAliveMs > 0) ex.scheduleWithFixedDelay({ pingAll() }, keepAliveMs, keepAliveMs, TimeUnit.MILLISECONDS)
    }

    @PreDestroy
    fun stop() {
        pinger.shutdownNow()
    }

    /** one keep-alive round: a comment to every open stream; streams that fail are dropped. Returns how many got it. */
    fun pingAll(): Int {
        var n = 0
        try {
            for ((topic, set) in topics) {
                for (e in set) {
                    try {
                        e.send(SseEmitter.event().comment("ping"))
                        n++
                    } catch (ex: Exception) {
                        log.debug("sse ping failed on {}: {}", topic, ex.message)
                        set.remove(e)
                        if (set.isEmpty()) topics.remove(topic, set)
                    }
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
     * A new stream of [topic]. With [maxStreams], the oldest streams of the topic are closed first so that at most that
     * many stay open (one account opening streams in a loop cannot pile up connections).
     */
    fun subscribe(topic: String, timeoutMs: Long = 30 * 60 * 1000L, maxStreams: Int = Int.MAX_VALUE): SseEmitter {
        val emitter = SseEmitter(timeoutMs)
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
        emitter.onTimeout(remove)
        emitter.onError { remove.run() }
        return emitter
    }

    /** send to every subscriber of the topic; returns how many received it */
    fun publish(topic: String, event: String, data: Any): Int {
        val set = topics[topic] ?: return 0
        var n = 0
        for (e in set) {
            try {
                e.send(SseEmitter.event().name(event).data(data))
                n++
            } catch (ex: Exception) {
                log.debug("sse send failed on {}: {}", topic, ex.message)
                set.remove(e)
            }
        }
        return n
    }

    fun subscribers(topic: String): Int = topics[topic]?.size ?: 0
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
