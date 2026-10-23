package com.lifolo.features

import com.fasterxml.jackson.databind.ObjectMapper
import com.lifolo.common.ApiException
import com.lifolo.common.Lang
import com.lifolo.common.action.ActionContext
import com.lifolo.demo.DemoWorld
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.stereotype.Service
import java.time.Duration

/** One key's setting (`feature_flags` row; no row = off) */
data class FeatureConfig(
    val mode: String = Features.OFF,
    val pilotUids: List<String> = emptyList(),
    val demo: Boolean = false,
)

/** All settings + the version (`max(feature_changes.id)`), the thing cached in Redis */
data class FeatureState(val v: Long = 0, val flags: Map<String, FeatureConfig> = emptyMap())

/**
 * Feature switches (backend/docs/FEATURES.md): every new feature ships OFF and the owner turns it on step by step
 * (off → pilot users/demo accounts → everyone) from the admin page.
 *
 * Use in actions:
 *  - `features.require(ctx, Features.CHAT)` first thing in every action of a gated feature (404 `feature_off`);
 *  - `features.isOn(uid, key)` before sending pushes/inbox entries of a gated feature;
 *  - `features.bothOn(a, b, key)` for two-party features (calls, chat, appt_video).
 *
 * The settings are cached in Redis for 30 s ([TTL]); an admin change clears the cache. `lifolo.features.all-on`
 * (FEATURES_ALL_ON; the test profile and the e2e server) turns every key on.
 */
@Service
class Features(
    private val jdbc: JdbcTemplate,
    private val redis: StringRedisTemplate,
    private val mapper: ObjectMapper,
    @Value("\${lifolo.features.all-on:false}") allOn: Boolean,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** every key on for everyone (test profile / e2e). Tests of the switches themselves set it to false. */
    @Volatile
    var allOn: Boolean = allOn
        set(v) {
            field = v
            local = null
        }

    /** the last Redis read, kept [LOCAL_MS] in this process (a doctor list checks hundreds of uids) */
    @Volatile
    private var local: Pair<Long, FeatureState>? = null

    /** the current settings (Redis, 30 s; the database when Redis fails) */
    fun state(): FeatureState {
        val now = System.currentTimeMillis()
        local?.let { (at, s) -> if (now - at in 0 until LOCAL_MS) return s }
        return fromRedis().also { local = now to it }
    }

    private fun fromRedis(): FeatureState {
        try {
            redis.opsForValue().get(CACHE_KEY)?.let { return mapper.readValue(it, FeatureState::class.java) }
        } catch (e: Exception) {
            log.warn("features cache read failed: {}", e.message)
        }
        val s = load()
        try {
            redis.opsForValue().set(CACHE_KEY, mapper.writeValueAsString(s), TTL)
        } catch (e: Exception) {
            log.warn("features cache write failed: {}", e.message)
        }
        return s
    }

    /** read from the database (no cache) */
    fun load(): FeatureState {
        val flags = LinkedHashMap<String, FeatureConfig>()
        jdbc.query("SELECT key, mode, pilot_uids, demo FROM feature_flags", RowCallbackHandler { rs ->
            @Suppress("UNCHECKED_CAST")
            val pilots = (rs.getArray("pilot_uids")?.array as? Array<String>)?.toList() ?: emptyList()
            flags[rs.getString("key")] = FeatureConfig(rs.getString("mode"), pilots, rs.getBoolean("demo"))
        })
        val v = jdbc.queryForObject("SELECT coalesce(max(id), 0) FROM feature_changes", Long::class.java) ?: 0L
        return FeatureState(v, flags)
    }

    /** forget the cached settings (after an admin change) */
    fun clearCache() {
        local = null
        try {
            redis.delete(CACHE_KEY)
        } catch (e: Exception) {
            log.warn("features cache clear failed: {}", e.message)
        }
    }

    /** is [key] on for [uid] (null = guest: only keys that are on for everyone)? Unknown keys are off. */
    fun isOn(uid: String?, key: String): Boolean = isOn(uid, key, if (allOn) null else state())

    private fun isOn(uid: String?, key: String, s: FeatureState?, depth: Int = 0): Boolean {
        if (key !in KEYS) return false
        if (s == null) return true
        if (depth > KEYS.size) return false
        if (!ownOn(uid, s.flags[key] ?: FeatureConfig())) return false
        return NEEDS[key].orEmpty().all { isOn(uid, it, s, depth + 1) }
    }

    private fun ownOn(uid: String?, c: FeatureConfig): Boolean = when (c.mode) {
        ON -> true
        PILOT -> uid != null && (uid in c.pilotUids || (c.demo && DemoWorld.isDemoUid(uid)))
        else -> false
    }

    /** two-party features (calls, chat, appt_video): on only when on for both people */
    fun bothOn(a: String?, b: String?, key: String): Boolean {
        val s = if (allOn) null else state()
        return isOn(a, key, s) && isOn(b, key, s)
    }

    /** every key → on/off for [uid] (the `features` action) */
    fun keysFor(uid: String?): Map<String, Boolean> {
        val s = if (allOn) null else state()
        return KEYS.associateWith { isOn(uid, it, s) }
    }

    /** the version clients compare (changes with every admin change) */
    fun version(): Long = if (allOn) 0 else state().v

    /**
     * First line of every action of a gated feature: 404 `feature_off` "এই সুবিধা এখনো চালু হয়নি" when [key] is off for
     * the caller (the person using the app — also when a caregiver acts for a patient).
     */
    fun require(ctx: ActionContext, key: String) {
        val uid = ctx.caller.uid ?: ctx.user.uid
        if (!isOn(uid, key)) throw offError(ctx.lang)
    }

    /** the same check for code without an [ActionContext] (HTTP endpoints, jobs) */
    fun require(uid: String?, key: String, lang: String? = null) {
        if (!isOn(uid, key)) throw offError(lang)
    }

    companion object {
        const val OFF = "off"
        const val PILOT = "pilot"
        const val ON = "on"
        val MODES = setOf(OFF, PILOT, ON)

        const val CALLS_AUDIO = "calls_audio"
        const val CALLS_VIDEO = "calls_video"
        const val APPT_VIDEO = "appt_video"
        const val CHAT = "chat"
        const val CHAT_MEDIA = "chat_media"
        const val ONLINE_CHAMBER = "online_chamber"
        const val INV_PHARMACY = "inv_pharmacy"
        const val INV_LAB = "inv_lab"
        const val INV_CHAMBER = "inv_chamber"
        const val INV_AMBULANCE = "inv_ambulance"
        const val MY_EXPENSES = "my_expenses"
        const val DESKTOP = "desktop"

        /** the keys, in admin page order */
        val KEYS: List<String> = listOf(
            CALLS_AUDIO, CALLS_VIDEO, APPT_VIDEO, CHAT, CHAT_MEDIA, ONLINE_CHAMBER,
            INV_PHARMACY, INV_LAB, INV_CHAMBER, INV_AMBULANCE, MY_EXPENSES, DESKTOP,
        )

        /** a key counts as on only when every key it needs is on too (for that user) */
        val NEEDS: Map<String, List<String>> = mapOf(
            CALLS_VIDEO to listOf(CALLS_AUDIO),
            APPT_VIDEO to listOf(ONLINE_CHAMBER),
            CHAT_MEDIA to listOf(CHAT),
        )

        /** features between two people: must be on for both ([bothOn]) */
        val TWO_PARTY: Set<String> = setOf(CALLS_AUDIO, CALLS_VIDEO, APPT_VIDEO, CHAT, CHAT_MEDIA)

        const val CACHE_KEY = "lifolo:features"
        val TTL: Duration = Duration.ofSeconds(30)
        const val LOCAL_MS = 2_000L

        const val OFF_CODE = "feature_off"
        const val OFF_BN = "এই সুবিধা এখনো চালু হয়নি" // bn-ok
        const val OFF_EN = "This feature is not available yet"

        fun offError(lang: String?): ApiException = ApiException(404, Lang.tr(lang, OFF_EN, OFF_BN), OFF_CODE)
    }
}
