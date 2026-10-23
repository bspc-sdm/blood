package com.lifolo.features

import com.lifolo.common.ApiException
import com.lifolo.support.IntegrationTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired

/** Feature switches (docs/FEATURES.md): modes, pilot list, demo accounts, dependencies, cache, `features`, require */
class FeaturesIT : IntegrationTest() {
    @Autowired
    lateinit var features: Features

    @BeforeEach
    fun switchesReal() {
        features.allOn = false
    }

    @AfterEach
    fun backToAllOn() {
        features.allOn = true
    }

    /** a row written straight into the table (+ a history row so the version moves), cache cleared */
    private fun set(key: String, mode: String, pilots: List<String> = emptyList(), demo: Boolean = false) {
        val arr = "{" + pilots.joinToString(",") { "\"$it\"" } + "}"
        jdbc.update(
            """
            INSERT INTO feature_flags (key, mode, pilot_uids, demo) VALUES (?, ?, ?::text[], ?)
            ON CONFLICT (key) DO UPDATE SET mode = EXCLUDED.mode, pilot_uids = EXCLUDED.pilot_uids, demo = EXCLUDED.demo
            """.trimIndent(),
            key, mode, arr, demo,
        )
        jdbc.update("INSERT INTO feature_changes (key, mode, pilot_uids, demo) VALUES (?, ?, ?::text[], ?)", key, mode, arr, demo)
        features.clearCache()
    }

    @Test
    fun `every key is off by default, for users and guests`() {
        for (k in Features.KEYS) {
            assertFalse(features.isOn("u1", k), k)
            assertFalse(features.isOn(null, k), k)
            assertFalse(features.isOn("demo-patient", k), k)
        }
        assertFalse(features.isOn("u1", "no_such_key"))
    }

    @Test
    fun `the test profile has every key on`() {
        features.allOn = true
        for (k in Features.KEYS - Features.OUTSIDE_ALL_ON) assertTrue(features.isOn("anyone", k), k)
        // health_consent adds a precondition: it follows its own setting even with all-on (CONSENT.md)
        assertFalse(features.isOn("anyone", Features.HEALTH_CONSENT))
        set(Features.HEALTH_CONSENT, Features.PILOT, listOf("anyone"))
        assertTrue(features.isOn("anyone", Features.HEALTH_CONSENT))
        assertFalse(features.isOn("someone_else", Features.HEALTH_CONSENT))
        assertTrue(features.isOn(null, Features.CHAT))
        assertTrue(features.bothOn("a", "b", Features.CALLS_AUDIO))
        assertFalse(features.isOn("u1", "no_such_key"), "unknown keys stay off")
    }

    @Test
    fun `on means everyone, guests included`() {
        set(Features.CHAT, Features.ON)
        assertTrue(features.isOn("u1", Features.CHAT))
        assertTrue(features.isOn(null, Features.CHAT))
        assertTrue(features.isOn("demo-donor", Features.CHAT))
        assertFalse(features.isOn("u1", Features.CALLS_AUDIO), "other keys untouched")
    }

    @Test
    fun `pilot - only the listed uids, plus demo accounts when demo is set`() {
        set(Features.ONLINE_CHAMBER, Features.PILOT, listOf("p1", "p2"))
        assertTrue(features.isOn("p1", Features.ONLINE_CHAMBER))
        assertTrue(features.isOn("p2", Features.ONLINE_CHAMBER))
        assertFalse(features.isOn("u9", Features.ONLINE_CHAMBER))
        assertFalse(features.isOn(null, Features.ONLINE_CHAMBER), "guests are never pilots")
        assertFalse(features.isOn("demo-doctor", Features.ONLINE_CHAMBER), "demo off")

        set(Features.ONLINE_CHAMBER, Features.PILOT, listOf("p1"), demo = true)
        assertTrue(features.isOn("demo-doctor", Features.ONLINE_CHAMBER))
        assertTrue(features.isOn("demo-patient", Features.ONLINE_CHAMBER))
        assertTrue(features.isOn("p1", Features.ONLINE_CHAMBER))
        assertFalse(features.isOn("p2", Features.ONLINE_CHAMBER), "removed from the pilot list")
        assertFalse(features.isOn("u9", Features.ONLINE_CHAMBER), "demo:true is not for real accounts")
    }

    @Test
    fun `off ignores a leftover pilot list and the demo flag`() {
        set(Features.CHAT, Features.OFF, listOf("p1"), demo = true)
        assertFalse(features.isOn("p1", Features.CHAT))
        assertFalse(features.isOn("demo-patient", Features.CHAT))
    }

    @Test
    fun `dependencies - a key is on only when what it needs is on for the same user`() {
        set(Features.CALLS_VIDEO, Features.ON)
        assertFalse(features.isOn("u1", Features.CALLS_VIDEO), "calls_audio is off")
        set(Features.CALLS_AUDIO, Features.PILOT, listOf("u1"))
        assertTrue(features.isOn("u1", Features.CALLS_VIDEO))
        assertFalse(features.isOn("u2", Features.CALLS_VIDEO), "audio not on for u2")

        set(Features.CHAT_MEDIA, Features.PILOT, listOf("u1", "u2"))
        set(Features.CHAT, Features.PILOT, listOf("u2"))
        assertFalse(features.isOn("u1", Features.CHAT_MEDIA))
        assertTrue(features.isOn("u2", Features.CHAT_MEDIA))

        set(Features.APPT_VIDEO, Features.ON)
        assertFalse(features.isOn("u1", Features.APPT_VIDEO), "needs online_chamber")
        set(Features.ONLINE_CHAMBER, Features.PILOT, demo = true)
        assertTrue(features.isOn("demo-doctor", Features.APPT_VIDEO))
        assertFalse(features.isOn("u1", Features.APPT_VIDEO))
    }

    @Test
    fun `two-party - bothOn needs the feature for both people`() {
        set(Features.CHAT, Features.PILOT, listOf("a"))
        assertFalse(features.bothOn("a", "b", Features.CHAT))
        assertFalse(features.bothOn("b", "a", Features.CHAT))
        set(Features.CHAT, Features.PILOT, listOf("a", "b"))
        assertTrue(features.bothOn("a", "b", Features.CHAT))
        assertFalse(features.bothOn("a", null, Features.CHAT))
        assertTrue(Features.CHAT in Features.TWO_PARTY && Features.APPT_VIDEO in Features.TWO_PARTY)
    }

    @Test
    fun `the settings are cached in Redis for 30 s and clearCache drops them`() {
        assertFalse(features.isOn("u1", Features.CHAT))
        assertNotNull(redis.opsForValue().get(Features.CACHE_KEY), "cached after the first read")
        val ttl = redis.getExpire(Features.CACHE_KEY)
        assertTrue(ttl in 1..30, "ttl $ttl")
        // a change behind the cache's back is not seen …
        jdbc.update("INSERT INTO feature_flags (key, mode) VALUES ('chat', 'on')")
        assertFalse(features.isOn("u1", Features.CHAT))
        // … until the cache is cleared (the admin change does this)
        features.clearCache()
        assertTrue(features.isOn("u1", Features.CHAT))
    }

    @Test
    fun `without Redis data the database answers`() {
        set(Features.CHAT, Features.ON)
        redis.opsForValue().set(Features.CACHE_KEY, "not json")
        assertTrue(features.isOn("u1", Features.CHAT))
    }

    @Test
    fun `require - 404 feature_off with the Bangla text, English for lang en`() {
        val e = assertThrows<ApiException> { features.require("u1", Features.CHAT, "bn") }
        assertEquals(404, e.status)
        assertEquals("feature_off", e.code)
        assertEquals("এই সুবিধা এখনো চালু হয়নি", e.message)
        assertEquals("This feature is not available yet", assertThrows<ApiException> { features.require("u1", Features.CHAT, "en") }.message)
        set(Features.CHAT, Features.PILOT, listOf("u1"))
        features.require("u1", Features.CHAT, "bn")
        assertThrows<ApiException> { features.require("u2", Features.CHAT, "bn") }
    }

    @Test
    fun `features action - the caller's keys and the version, guests get the global ones uncached`() {
        user("p1", mapOf("name" to "পাইলট"))
        user("u2", mapOf("name" to "সাধারণ"))
        set(Features.CHAT, Features.PILOT, listOf("p1"))
        val mine = call("p1", "features")
        assertEquals(200, mine.status, mine.toString())
        assertEquals(Features.KEYS.toSet(), mine.body["keys"].fieldNames().asSequence().toSet())
        assertTrue(mine.body["keys"]["chat"].booleanValue())
        assertFalse(mine.body["keys"]["calls_audio"].booleanValue())
        val v1 = mine.body["v"].longValue()
        assertTrue(v1 > 0)
        assertFalse(call("u2", "features").body["keys"]["chat"].booleanValue())
        // the app of p1 asked: it may get the silent push
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM feature_clients WHERE uid = 'p1'", Int::class.java))

        val g1 = call(null, "features")
        assertEquals(200, g1.status, g1.toString())
        assertFalse(g1.body["keys"]["chat"].booleanValue())
        set(Features.CHAT, Features.ON)
        val g2 = call(null, "features")
        assertTrue(g2.body["keys"]["chat"].booleanValue(), "not served from the 1-minute guest cache")
        assertTrue(g2.body["v"].longValue() > v1, "version moves with every change")
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM feature_clients WHERE uid LIKE 'guest%'", Int::class.java))
    }

    @Test
    fun `features action with every key on (test profile)`() {
        features.allOn = true
        user("u1", mapOf("name" to "ক"))
        val r = call("u1", "features")
        assertTrue((Features.KEYS - Features.OUTSIDE_ALL_ON).all { r.body["keys"][it].booleanValue() })
        assertFalse(r.body["keys"][Features.HEALTH_CONSENT].booleanValue(), "health_consent follows its own setting")
    }
}
