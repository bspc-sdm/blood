package com.lifolo.common.action

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lifolo.common.Body
import com.lifolo.common.Ids
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Guest (no-login) answers are cached for one minute per identical request (community.js: memcache
 * `guest:${cacheKey(body)}`). Guests only call read-only actions without personal data, so sharing is safe.
 * Stored in Redis so every instance shares it; on Redis errors the action simply runs.
 */
@Component
class GuestResponseCache(private val redis: StringRedisTemplate, private val mapper: ObjectMapper) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** guest.js cacheKey: body keys sorted, JSON, first 500 chars */
    fun cacheKey(body: Body): String {
        val keys = body.node.fieldNames().asSequence().sorted().toList()
        val arr = mapper.createArrayNode()
        for (k in keys) arr.add(mapper.createArrayNode().add(k).add(body.node.get(k)))
        return mapper.writeValueAsString(arr).take(500)
    }

    fun cached(body: Body, load: () -> Any?): Any? {
        val key = "guest:" + Ids.sha256Hex(cacheKey(body))
        try {
            redis.opsForValue().get(key)?.let { return mapper.readTree(it) as JsonNode }
        } catch (e: Exception) {
            log.warn("guest cache read failed: {}", e.message)
        }
        val value = load()
        try {
            redis.opsForValue().set(key, mapper.writeValueAsString(value ?: mapOf("ok" to true)), TTL)
        } catch (e: Exception) {
            log.warn("guest cache write failed: {}", e.message)
        }
        return value
    }

    companion object {
        val TTL: Duration = Duration.ofMinutes(1)
    }
}
