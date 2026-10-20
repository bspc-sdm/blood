package com.lifolo.common

import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * Rate limits shared by all app instances: one Redis counter per key and window, counted by an atomic Lua script
 * (INCR, and on the first hit PEXPIRE = the window starts with the first request). Node kept these counters in
 * process memory (guest.js checkRate: 240 requests / minute / IP).
 *
 * Before (Oct 2026) this was Bucket4j's compare-and-swap proxy on Lettuce: many requests at once on the same key (a
 * burst from one address — exactly what the limit is for) made the CAS retry over and over; slow or failed attempts
 * ended in the fail-open path, so the load test's 300 fast guest requests from one IP all passed (earlier 240/60).
 * One INCR per request never retries and stays exact under any concurrency.
 *
 * If Redis is unreachable the limiter fails open (logs a warning, counted in [failOpen]) rather than blocking users.
 */
@Component
class RateLimiter(private val redis: StringRedisTemplate) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** requests let through because Redis failed (since start) */
    val failOpen = AtomicLong()

    private val script = DefaultRedisScript(
        """
        local n = redis.call('INCR', KEYS[1])
        if n == 1 or redis.call('PTTL', KEYS[1]) < 0 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
        return n
        """.trimIndent(),
        Long::class.javaObjectType,
    )

    /** the hits counted so far for [key] in its current window (after this one) */
    fun hit(key: String, window: Duration): Long =
        redis.execute(script, listOf("rl:$key"), window.toMillis().toString()) ?: 0L

    /** true when the request is allowed: at most [limit] hits per [window] for [key] */
    fun tryAcquire(key: String, limit: Long, window: Duration): Boolean = try {
        hit(key, window) <= limit
    } catch (e: Exception) {
        failOpen.incrementAndGet()
        log.warn("rate limiter unavailable ({}), allowing request", e.message)
        true
    }
}
