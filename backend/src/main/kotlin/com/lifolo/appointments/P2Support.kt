package com.lifolo.appointments

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lifolo.common.Js
import com.lifolo.common.Lang
import com.lifolo.notify.PushItem
import com.lifolo.notify.PushService
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Duration

/*
 * Small shared pieces of the P2 package (doctors, appointments, queue, home care, care, content).
 */

/**
 * `Date.now()` of the P2 code. Tests pin it (the Node tests replace Date.now / support/clock.setDhaka) — appointments,
 * queues and the doctor-list filters depend on the Dhaka time of day.
 */
@Component
class P2Clock {
    @Volatile
    var fixedMs: Long? = null

    fun now(): Long = fixedMs ?: System.currentTimeMillis()
}

/**
 * memcache.js for the doctor and home-care lists (2 minutes, cleared when a provider saves or a badge changes).
 * Stored in Redis so every instance sees the same list; any Redis/JSON problem falls back to loading.
 */
@Component
class P2Cache(private val redis: StringRedisTemplate, private val mapper: ObjectMapper) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun <T> cached(key: String, ttl: Duration, type: TypeReference<T>, load: () -> T): T {
        try {
            val hit = redis.opsForValue().get(key)
            if (hit != null) return mapper.readValue(hit, type)
        } catch (e: Exception) {
            log.warn("p2 cache read failed: {}", e.message)
        }
        val value = load()
        try {
            redis.opsForValue().set(key, mapper.writeValueAsString(value), ttl)
        } catch (e: Exception) {
            log.warn("p2 cache write failed: {}", e.message)
        }
        return value
    }

    /** memcache.clear('providers:') + clear('home:') */
    fun clearProviders() {
        try {
            redis.delete(PROVIDER_KEYS)
        } catch (e: Exception) {
            log.warn("p2 cache clear failed: {}", e.message)
        }
    }

    companion object {
        val PROVIDER_TYPES = listOf("doctor", "nurse", "nutritionist", "trainer")
        fun providersKey(type: String) = "p2:providers:$type"
        fun homeKey(type: String) = "p2:home:$type"
        val PROVIDER_KEYS: List<String> = PROVIDER_TYPES.map { providersKey(it) } + PROVIDER_TYPES.map { homeKey(it) }
        val TWO_MINUTES: Duration = Duration.ofMinutes(2)
    }
}

/** users/{uid} lookups the P2 code needs (name, language, phone, existence) */
@Component
class P2Users(private val jdbc: JdbcTemplate, private val push: PushService) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** community-db.js userName: profile name or '' */
    fun name(uid: String): String =
        jdbc.queryForList("SELECT name FROM user_profiles WHERE uid = ?", String::class.java, uid).firstOrNull() ?: ""

    fun exists(uid: String): Boolean = jdbc.queryForList("SELECT 1 FROM user_profiles WHERE uid = ?", uid).isNotEmpty()

    data class Profile(val uid: String, val name: String, val lang: String?, val phone: String)

    /** profiles of the users that have one (Node: db.getAll(users/…) filtered by exists) */
    fun profiles(uids: Collection<String?>): Map<String, Profile> {
        val ids = uids.filterNotNull().filter { it.isNotEmpty() }.distinct()
        if (ids.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, Profile>()
        for (chunk in ids.chunked(1000)) {
            jdbc.query(
                "SELECT uid, name, lang, phone FROM user_profiles WHERE uid IN (${chunk.joinToString(",") { "?" }})",
                RowCallbackHandler { rs ->
                    val uid = rs.getString("uid")
                    out[uid] = Profile(uid, rs.getString("name") ?: "", rs.getString("lang"), rs.getString("phone") ?: "")
                },
                *chunk.toTypedArray(),
            )
        }
        return out
    }

    /**
     * appointments.js push / queue.js pushProvider: one notification in the user's language, Bangla digits in the body
     * for Bangla readers; only when the user has a profile. Never throws.
     */
    fun push(uid: String, en: String, bn: String, body: String, data: Map<String, Any?>) {
        try {
            val p = profiles(listOf(uid))[uid] ?: return
            push.send(listOf(PushItem(uid, Lang.pick(p.lang, en, bn), Lang.pick(p.lang, body, Lang.bnNumbers(body)), data)))
        } catch (e: Exception) {
            log.error("appointment push failed", e)
        }
    }
}

/** JavaScript helpers the ported code needs beyond common.Js */
object P2Js {
    /** `Number(v)` for a JSON value (undefined → NaN, null → 0, "20" → 20, true → 1, [20] → 20) */
    fun number(n: JsonNode?): Double = when {
        n == null || n.isMissingNode -> Double.NaN
        n.isNull -> 0.0
        n.isNumber -> n.doubleValue()
        n.isBoolean -> if (n.booleanValue()) 1.0 else 0.0
        n.isTextual -> parseNumber(n.textValue())
        n.isArray -> when (n.size()) {
            0 -> 0.0
            1 -> number(n[0]).let { if (n[0].isArray || n[0].isObject) Double.NaN else it }
            else -> Double.NaN
        }
        else -> Double.NaN
    }

    private fun parseNumber(s: String): Double {
        val t = s.trim()
        if (t.isEmpty()) return 0.0
        return t.toDoubleOrNull()?.takeIf { !t.endsWith("d") && !t.endsWith("f") && !t.endsWith("D") && !t.endsWith("F") } ?: Double.NaN
    }

    /** `Number.isInteger(v) ? v : null` as Long */
    fun integer(n: JsonNode?): Long? = Js.integer(n)

    /** md5 hex */
    fun md5Hex(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** a PostgreSQL array literal of integers: {1,2,3} */
    fun pgInts(list: Collection<Number>): String = list.joinToString(",", "{", "}") { it.toLong().toString() }

    /** a PostgreSQL array literal of texts: {"a","b"} */
    fun pgTexts(list: Collection<String>): String =
        list.joinToString(",", "{", "}") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" }

    /** JS localeCompare for the list sorts (names, codes) */
    private val collator: java.text.Collator = java.text.Collator.getInstance(java.util.Locale.US)

    fun localeCompare(a: String, b: String): Int = synchronized(collator) { collator.compare(a, b) }
}
