package com.lifolo.features

import com.lifolo.notify.SseHub
import com.lifolo.support.IntegrationTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/** admin_features_get / admin_feature_set: admin only, validation, audit, cache cleared, features_changed push + SSE */
class FeatureAdminIT : IntegrationTest() {
    @Autowired
    lateinit var features: Features

    @Autowired
    lateinit var featurePush: FeaturePush

    @Autowired
    lateinit var sse: SseHub

    private val owner = "fOwner"

    @BeforeEach
    fun setUp() {
        features.allOn = false
        featurePush.async = false
        user(owner, mapOf("name" to "মালিক"), email = "owner@example.com")
    }

    @AfterEach
    fun tearDown() {
        features.allOn = true
        featurePush.async = true
    }

    /** an account whose app knows `features` and has a phone */
    private fun app(uid: String) {
        user(uid, mapOf("name" to uid))
        jdbc.update("INSERT INTO device_tokens (token, uid) VALUES (?, ?)", "tok_$uid", uid)
        assertEquals(200, call(uid, "features").status)
    }

    private fun set(body: Map<String, Any?>) = call(owner, "admin_feature_set", body)

    private fun silentPushesTo(uid: String) = push.messages.filter { it.uid == uid && it.data["type"] == "features_changed" }

    @Test
    fun `admins only`() {
        user("plain", mapOf("name" to "সাধারণ"))
        assertEquals(403, call("plain", "admin_features_get").status)
        assertEquals(403, call("plain", "admin_feature_set", mapOf("key" to "chat", "mode" to "on")).status)
        assertFalse(features.isOn("plain", Features.CHAT))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM feature_changes", Int::class.java))
    }

    @Test
    fun `get - every key off by default, with dependencies and two-party marks`() {
        val r = call(owner, "admin_features_get")
        assertEquals(200, r.status, r.toString())
        val items = r.body["items"]
        assertEquals(Features.KEYS, items.map { it["key"].textValue() })
        assertTrue(items.all { it["mode"].textValue() == "off" && !it["demo"].booleanValue() && it["pilotUids"].size() == 0 })
        val video = items.first { it["key"].textValue() == "calls_video" }
        assertEquals(listOf("calls_audio"), video["needs"].map { it.textValue() })
        assertTrue(video["twoParty"].booleanValue())
        assertFalse(items.first { it["key"].textValue() == "inv_lab" }["twoParty"].booleanValue())
        assertEquals(0, r.body["history"].size())
        assertEquals(0L, r.body["v"].longValue())
    }

    @Test
    fun `set - validation`() {
        assertEquals(400, set(mapOf("key" to "nope", "mode" to "on")).status)
        assertEquals(400, set(mapOf("key" to "chat", "mode" to "half")).status)
        assertEquals(400, set(mapOf("key" to "chat", "mode" to "pilot", "pilotUids" to "u1")).status)
        assertEquals(400, set(mapOf("key" to "chat", "mode" to "pilot", "pilotUids" to listOf("bad id!"))).status)
        val unknown = set(mapOf("key" to "chat", "mode" to "pilot", "pilotUids" to listOf("ghost")))
        assertEquals(400, unknown.status)
        assertTrue(unknown.error!!.contains("ghost"))
        assertEquals(400, set(mapOf("key" to "chat", "mode" to "pilot", "demo" to "yes")).status)
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM feature_changes", Int::class.java))
    }

    @Test
    fun `set pilot - saved, audited, cache cleared at once, only the pilots' phones get the silent push`() {
        app("p1")
        app("p2")
        app("other")
        user("noapp", mapOf("name" to "পুরোনো অ্যাপ"))
        jdbc.update("INSERT INTO device_tokens (token, uid) VALUES ('tok_noapp', 'noapp')")
        assertFalse(features.isOn("p1", Features.CHAT)) // now cached
        val r = set(mapOf("key" to "chat", "mode" to "pilot", "pilotUids" to listOf("p1", "noapp", "p1")))
        assertEquals(200, r.status, r.toString())
        assertEquals(listOf("p1", "noapp"), r.body["pilotUids"].map { it.textValue() })
        val v = r.body["v"].longValue()
        assertTrue(v > 0)
        // the cache was cleared: on right away
        assertTrue(features.isOn("p1", Features.CHAT))
        assertTrue(features.isOn("noapp", Features.CHAT))
        assertFalse(features.isOn("p2", Features.CHAT))
        assertTrue(call("p1", "features").body["keys"]["chat"].booleanValue())
        // the silent push: pilots whose app knows `features` (an old app would show an empty notification)
        val sent = silentPushesTo("p1").single()
        assertEquals(v.toString(), sent.data["v"])
        assertNull(sent.data["title"], "no notification text")
        assertTrue(silentPushesTo("noapp").isEmpty())
        assertTrue(silentPushesTo("p2").isEmpty() && silentPushesTo("other").isEmpty())
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM inbox_items", Int::class.java), "nothing in the bell list")
        // audit: the history row + the admin log
        val h = row("SELECT * FROM feature_changes ORDER BY id DESC LIMIT 1")
        assertEquals(listOf("chat", "pilot", "off", "owner@example.com"), listOf(h["key"], h["mode"], h["prev_mode"], h["by_email"]))
        val log = row("SELECT * FROM admin_log WHERE action = 'admin_feature_set'")
        assertEquals("features/chat", log["target"])
        assertEquals("pilot pilots=3", log["detail"])
        // get shows the pilots with names and the last change
        val item = call(owner, "admin_features_get").body["items"].first { it["key"].textValue() == "chat" }
        assertEquals("pilot", item["mode"].textValue())
        assertEquals(listOf("p1", "noapp"), item["pilots"].map { it["uid"].textValue() })
        assertEquals("p1@example.com", item["pilots"][0]["email"].textValue())
        assertEquals("p1", item["pilots"][0]["name"].textValue())
        assertEquals("pilot", item["last"]["mode"].textValue())
        assertEquals("owner@example.com", item["updatedBy"].textValue())
    }

    @Test
    fun `set - the same setting again is unchanged (no history, no push, no log)`() {
        app("p1")
        assertEquals(200, set(mapOf("key" to "chat", "mode" to "pilot", "pilotUids" to listOf("p1"))).status)
        push.messages.clear()
        val again = set(mapOf("key" to "chat", "mode" to "pilot"))
        assertEquals(true, again.body["unchanged"]?.booleanValue(), again.toString())
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM feature_changes", Int::class.java))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM admin_log WHERE action = 'admin_feature_set'", Int::class.java))
        assertTrue(push.messages.isEmpty())
        // pilotUids / demo left out keep what was there
        assertEquals(200, set(mapOf("key" to "chat", "mode" to "pilot", "demo" to true)).status)
        assertTrue(features.isOn("p1", Features.CHAT))
        assertTrue(features.isOn("demo-patient", Features.CHAT))
    }

    @Test
    fun `on and off reach everyone, the demo flag reaches the demo accounts`() {
        app("p1")
        app("u2")
        app("demo-patient")
        assertEquals(200, set(mapOf("key" to "inv_lab", "mode" to "pilot", "demo" to true)).status)
        assertEquals(listOf("demo-patient"), push.messages.mapNotNull { it.uid }.distinct())
        push.messages.clear()
        assertEquals(200, set(mapOf("key" to "inv_lab", "mode" to "on")).status)
        assertEquals(setOf("p1", "u2", "demo-patient"), push.messages.filter { it.data["type"] == "features_changed" }.map { it.uid }.toSet())
        assertTrue(features.isOn(null, Features.INV_LAB), "guests too")
        push.messages.clear()
        assertEquals(200, set(mapOf("key" to "inv_lab", "mode" to "off")).status)
        assertEquals(3, push.messages.size, "turning off reaches everyone too")
        assertFalse(features.isOn("u2", Features.INV_LAB))
        // history newest first
        val h = call(owner, "admin_features_get").body["history"]
        assertEquals(listOf("off", "on", "pilot"), h.map { it["mode"].textValue() })
        assertEquals(listOf("on", "pilot", "off"), h.map { it["prevMode"].textValue() })
    }

    @Test
    fun `dependencies follow the change - calls_video works only once calls_audio is on`() {
        app("p1")
        assertEquals(200, set(mapOf("key" to "calls_video", "mode" to "pilot", "pilotUids" to listOf("p1"))).status)
        assertFalse(call("p1", "features").body["keys"]["calls_video"].booleanValue())
        assertEquals(200, set(mapOf("key" to "calls_audio", "mode" to "on")).status)
        assertTrue(call("p1", "features").body["keys"]["calls_video"].booleanValue())
    }

    @Test
    fun `the SSE event reaches the open streams`() {
        app("p1")
        sse.subscribe("user:p1")
        sse.subscribe("user:u9")
        // nothing fails while streams are open; the event goes out with the version
        assertEquals(200, set(mapOf("key" to "chat", "mode" to "pilot", "pilotUids" to listOf("p1"))).status)
        assertEquals(1, sse.publish("user:p1", FeaturePush.EVENT, mapOf("v" to 1)))
        assertTrue(sse.publishPrefix("user:", FeaturePush.EVENT, mapOf("v" to 1)) >= 2)
    }

    @Test
    fun `audience - who a change reaches`() {
        val off = FeatureConfig()
        val pilot = FeatureConfig(Features.PILOT, listOf("a", "b"))
        assertTrue(featurePush.audience(off, FeatureConfig(Features.ON)).everyone)
        assertTrue(featurePush.audience(FeatureConfig(Features.ON), off).everyone)
        val a = featurePush.audience(pilot, FeatureConfig(Features.PILOT, listOf("b", "c"), demo = true))
        assertFalse(a.everyone)
        assertEquals(setOf("a", "b", "c"), a.uids)
        assertTrue(a.demo)
        val b = featurePush.audience(off, FeatureConfig(Features.OFF, listOf("x"), demo = true))
        assertEquals(emptySet<String>(), b.uids, "off to off reaches nobody")
        assertFalse(b.demo)
    }
}
