package com.lifolo.common

import io.github.bucket4j.Bandwidth
import io.github.bucket4j.BucketConfiguration
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy
import io.github.bucket4j.distributed.proxy.ProxyManager
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.codec.ByteArrayCodec
import io.lettuce.core.codec.RedisCodec
import io.lettuce.core.codec.StringCodec
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.data.redis.RedisConnectionDetails
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Fixed-window rate limits shared by all app instances (Bucket4j on Redis).
 * Node kept these counters in process memory (guest.js checkRate: 240 requests / minute / IP).
 * If Redis is unreachable the limiter fails open (logs a warning) rather than blocking users.
 */
@Component
class RateLimiter(private val redis: RedisConnectionDetails) {
    private val log = LoggerFactory.getLogger(javaClass)

    private var client: RedisClient? = null
    private var connection: StatefulRedisConnection<String, ByteArray>? = null

    @Volatile
    private var proxyManager: ProxyManager<String>? = null

    @Synchronized
    private fun manager(): ProxyManager<String> {
        proxyManager?.let { return it }
        val s = redis.standalone
        val uri = RedisURI.Builder.redis(s.host, s.port).withDatabase(s.database)
        val password = redis.password
        if (!password.isNullOrEmpty()) {
            val username = redis.username
            if (username.isNullOrEmpty()) uri.withPassword(password.toCharArray()) else uri.withAuthentication(username, password.toCharArray())
        }
        val c = RedisClient.create(uri.build())
        client = c
        val conn = c.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE))
        connection = conn
        val pm = Bucket4jLettuce.casBasedBuilder(conn)
            .expirationAfterWrite(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofMinutes(2)))
            .build()
        proxyManager = pm
        return pm
    }

    /** true when the request is allowed: at most [limit] hits per [window] for [key] */
    fun tryAcquire(key: String, limit: Long, window: Duration): Boolean = try {
        val config = BucketConfiguration.builder()
            .addLimit(Bandwidth.builder().capacity(limit).refillIntervally(limit, window).build())
            .build()
        manager().builder().build("rl:$key") { config }.tryConsume(1)
    } catch (e: Exception) {
        log.warn("rate limiter unavailable ({}), allowing request", e.message)
        true
    }

    @PreDestroy
    fun close() {
        runCatching { connection?.close() }
        runCatching { client?.shutdown() }
    }
}
