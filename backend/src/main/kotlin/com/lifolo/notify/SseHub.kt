package com.lifolo.notify

import com.lifolo.auth.AuthService
import com.lifolo.common.Lang
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Server-Sent Events hub keyed by topic ("user:{uid}", "ambreq:{id}", …). Ready for live updates
 * (ambulance request status, queue position) in phase 2; nothing publishes yet.
 * Single-instance only: with several app instances, fan out through Redis pub/sub first.
 */
@Component
class SseHub {
    private val log = LoggerFactory.getLogger(javaClass)
    private val topics = ConcurrentHashMap<String, CopyOnWriteArraySet<SseEmitter>>()

    fun subscribe(topic: String, timeoutMs: Long = 30 * 60 * 1000L): SseEmitter {
        val emitter = SseEmitter(timeoutMs)
        val set = topics.computeIfAbsent(topic) { CopyOnWriteArraySet() }
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
        return hub.subscribe("user:${user.id}")
    }
}
