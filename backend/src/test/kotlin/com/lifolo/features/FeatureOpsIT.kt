package com.lifolo.features

import com.lifolo.support.IntegrationTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/** POST /api/cron/features (the "Ops: feature switches" workflow): CRON_SECRET only, the admin page's write + audit */
class FeatureOpsIT : IntegrationTest() {
    @Autowired
    lateinit var features: Features

    @Autowired
    lateinit var featurePush: FeaturePush

    private val auth = mapOf("Authorization" to "Bearer test-cron-secret")

    @BeforeEach
    fun setUp() {
        features.allOn = false
        featurePush.async = false
    }

    @AfterEach
    fun tearDown() {
        features.allOn = true
        featurePush.async = true
    }

    private fun show() = post("/api/cron/features?do=show", emptyMap<String, Any>(), auth)
    private fun set(items: List<Map<String, Any?>>) = post("/api/cron/features?do=set", mapOf("items" to items), auth)

    @Test
    fun `needs the cron secret and POST`() {
        assertEquals(401, post("/api/cron/features?do=show", emptyMap<String, Any>()).status)
        assertEquals(401, post("/api/cron/features?do=set", mapOf("items" to listOf(mapOf("key" to "chat", "mode" to "on"))), mapOf("Authorization" to "Bearer nope")).status)
        assertEquals(405, get("/api/cron/features?do=show", auth).status)
        assertFalse(features.isOn("u1", Features.CHAT))
    }

    @Test
    fun `show - every key with its mode`() {
        val r = show()
        assertEquals(200, r.status, r.toString())
        assertEquals(Features.KEYS, r.body["items"].map { it["key"].textValue() })
        assertTrue(r.body["items"].all { it["mode"].textValue() == "off" })
        assertEquals(0, r.body["unmet"].size())
    }

    @Test
    fun `set - one wrong item writes nothing`() {
        val r = set(listOf(mapOf("key" to "chat", "mode" to "on"), mapOf("key" to "nope", "mode" to "on")))
        assertEquals(400, r.status)
        assertEquals(400, set(listOf(mapOf("key" to "chat", "mode" to "half"))).status)
        assertEquals(400, set(listOf(mapOf("key" to "chat", "mode" to "pilot", "pilotUids" to listOf("bad id!")))).status)
        assertEquals(400, set(emptyList()).status)
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM feature_changes", Int::class.java))
    }

    @Test
    fun `all on, audited as ops-features, one push, then restored exactly`() {
        user("p1", mapOf("name" to "p1"))
        jdbc.update("INSERT INTO device_tokens (token, uid) VALUES ('tok_p1', 'p1')")
        assertEquals(200, call("p1", "features").status)
        // a pilot setting to come back to
        assertEquals(200, set(listOf(mapOf("key" to "chat", "mode" to "pilot", "pilotUids" to listOf("p1"), "demo" to true))).status)
        val before = show().body["items"]
        push.messages.clear()

        val on = set(Features.KEYS.reversed().map { mapOf("key" to it, "mode" to "on") })
        assertEquals(200, on.status, on.toString())
        assertEquals(Features.KEYS.size, on.body["changed"].size())
        assertEquals(0, on.body["unmet"].size())
        // needed keys first
        val order = on.body["changed"].map { it["key"].textValue() }
        assertTrue(order.indexOf("pay_manual") < order.indexOf("pay_due"))
        assertTrue(order.indexOf("calls_audio") < order.indexOf("calls_video"))
        // mode only: the pilot list and demo flag stay for later
        val chatTo = on.body["changed"].first { it["key"].textValue() == "chat" }["to"]
        assertEquals(listOf("p1"), chatTo["pilotUids"].map { it.textValue() })
        assertTrue(Features.KEYS.all { features.isOn("someone", it) }, "every key on for everyone")
        assertTrue(call("p1", "features").body["keys"].all { it.booleanValue() })
        assertEquals(1, push.messages.count { it.uid == "p1" && it.data["type"] == "features_changed" }, "one silent push")
        // audit like the admin page, actor ops-features
        assertEquals(Features.KEYS.size, jdbc.queryForObject("SELECT count(*) FROM admin_log WHERE action = 'admin_feature_set' AND by_email = 'ops-features' AND detail = 'on'", Int::class.java))
        assertEquals("on", row("SELECT detail FROM admin_log WHERE target = 'features/chat' ORDER BY at DESC LIMIT 1")["detail"])
        assertEquals("ops-features", row("SELECT by_email FROM feature_changes ORDER BY id DESC LIMIT 1")["by_email"])
        assertEquals("ops-features", row("SELECT updated_by FROM feature_flags WHERE key = 'chat'")["updated_by"])

        // again: unchanged, nothing written
        val again = set(Features.KEYS.map { mapOf("key" to it, "mode" to "on") })
        assertEquals(0, again.body["changed"].size())
        assertEquals(Features.KEYS.size, again.body["unchanged"].intValue())

        // restore the snapshot exactly
        val back = set(before.map { mapOf("key" to it["key"].textValue(), "mode" to it["mode"].textValue(), "pilotUids" to it["pilotUids"].map { p -> p.textValue() }, "demo" to it["demo"].booleanValue()) })
        assertEquals(200, back.status, back.toString())
        val after = show().body["items"]
        assertEquals(before.map { listOf(it["key"], it["mode"], it["pilotUids"], it["demo"]) }, after.map { listOf(it["key"], it["mode"], it["pilotUids"], it["demo"]) })
        assertTrue(features.isOn("p1", Features.CHAT))
        assertTrue(features.isOn("demo-patient", Features.CHAT))
        assertFalse(features.isOn("someone", Features.CHAT))
    }

    @Test
    fun `unmet dependencies are reported`() {
        val r = set(listOf(mapOf("key" to "pay_due", "mode" to "on")))
        assertEquals(200, r.status)
        assertEquals("pay_due", r.body["unmet"][0]["key"].textValue())
        assertEquals(listOf("pay_manual"), r.body["unmet"][0]["needs"].map { it.textValue() })
        assertFalse(features.isOn("u1", Features.PAY_DUE))
    }
}
