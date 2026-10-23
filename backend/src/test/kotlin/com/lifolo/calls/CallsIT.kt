package com.lifolo.calls

import com.lifolo.common.LifoloProperties
import com.lifolo.jobs.DailyJobRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.sql.Timestamp
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** In-app calls (backend/docs/CALLS.md): relations, state machine, timeouts, limits, tokens, upgrade, block, appt_join */
class CallsIT : CallsTestBase() {
    @Autowired lateinit var daily: DailyJobRunner

    private fun can(uid: String, vararg items: Map<String, Any?>) = ok(uid, "contact_can", mapOf("items" to items.toList()))["items"]

    // ------------------------------------------------------------------ unavailable / config / token

    @Test
    fun `without the LiveKit env - config off, contact_can no calls, call actions 503 calls_unavailable`() {
        person("u1")
        person("u2")
        bloodRequest("r1", "u1", mapOf("u2" to "accepted"))
        tokens.config = LifoloProperties.LiveKit()
        val c = ok("u1", "call_config")
        assertFalse(c["enabled"].booleanValue())
        assertNull(c["url"])
        val r = start("u1", "blood", "r1", "u2")
        assertEquals(503, r.status)
        assertEquals("calls_unavailable", r.code)
        assertEquals("অ্যাপে কল এখন চালু নেই — ফোনে কল করুন", r.error)
        val x = can("u1", mapOf("ctx" to "blood", "refId" to "r1", "peerUid" to "u2"))[0]
        assertFalse(x["canCall"].booleanValue())
        assertFalse(x["canVideo"].booleanValue())
        assertTrue(x["canMessage"].booleanValue())
        assertEquals(0, ok("u1", "call_history")["items"].size())
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM call_sessions", Int::class.java))
    }

    @Test
    fun `tokens - signed, 10 minutes, room and identity, microphone only for audio, camera too for video, nothing else`() {
        person("u1", "রহিম উদ্দিন")
        person("u2")
        bloodRequest("r1", "u1", mapOf("u2" to "accepted"))
        val c = ok("u1", "call_config")
        assertTrue(c["enabled"].booleanValue())
        assertEquals("wss://lifolo.test/rtc", c["url"].textValue())
        val s = ok("u1", "call_start", mapOf("ctx" to "blood", "refId" to "r1", "peerUid" to "u2"))
        val callId = s["callId"].textValue()
        assertTrue(callId.length >= 32, "callId is long random ($callId)")
        assertEquals("c_$callId", s["room"].textValue())
        assertEquals("wss://lifolo.test/rtc", s["url"].textValue())
        assertFalse(s["video"].booleanValue())
        assertEquals("u2", s["peer"]["uid"].textValue())
        assertNull(s["peer"]["phone"])
        val parts = s["token"].textValue().split('.')
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(lk.apiSecret.toByteArray(), "HmacSHA256")) }
        assertEquals(parts[2], Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal("${parts[0]}.${parts[1]}".toByteArray())))
        val cl = claims(s["token"].textValue())
        assertEquals("APIkey1", cl["iss"].textValue())
        assertEquals("u1", cl["sub"].textValue())
        assertEquals("রহিম উদ্দিন", cl["name"].textValue())
        assertEquals(600, cl["exp"].longValue() - cl["nbf"].longValue())
        val v = cl["video"]
        assertEquals("c_$callId", v["room"].textValue())
        assertTrue(v["roomJoin"].booleanValue() && v["canPublish"].booleanValue() && v["canSubscribe"].booleanValue())
        assertEquals(listOf("microphone"), v["canPublishSources"].map { it.textValue() })
        assertFalse(v["canPublishData"].booleanValue())
        assertFalse(v["canUpdateOwnMetadata"].booleanValue())
        for (k in listOf("roomRecord", "roomAdmin", "roomCreate", "roomList", "ingressAdmin", "recorder", "hidden", "agent")) assertNull(v[k], k)
        val a = ok("u2", "call_accept", mapOf("callId" to callId))
        assertEquals("u2", claims(a["token"].textValue())["sub"].textValue())
        endAll()
        val sv = ok("u1", "call_start", mapOf("ctx" to "blood", "refId" to "r1", "peerUid" to "u2", "video" to true))
        assertTrue(sv["video"].booleanValue())
        assertEquals(listOf("camera", "microphone"), claims(sv["token"].textValue())["video"]["canPublishSources"].map { it.textValue() })
    }

    // ------------------------------------------------------------------ relations

    @Test
    fun `blood - requester calls donors who said they will donate, a donor calls back only once confirmed or called first`() {
        for (u in listOf("req", "don", "conf", "gone", "stranger")) person(u)
        bloodRequest("r1", "req", mapOf("don" to "accepted", "conf" to "accepted", "gone" to "cancelled"), confirmed = setOf("conf"))
        assertEquals("call_not_allowed", start("req", "blood", "r1").code) // must name the donor
        assertEquals(403, start("req", "blood", "r1", "gone").status)
        assertEquals(403, start("req", "blood", "r1", "stranger").status)
        assertEquals(403, start("stranger", "blood", "r1").status)
        assertEquals(403, start("gone", "blood", "r1").status)
        assertEquals(403, start("don", "blood", "nope").status)
        // R1: just tapping "রক্ত দেব" is not enough to ring the requester
        assertEquals("call_not_allowed", start("don", "blood", "r1").code)
        val c1 = startOk("conf", "blood", "r1")
        ok("conf", "call_cancel", mapOf("callId" to c1))
        // the requester calls the donor once → the donor may call back
        val c2 = startOk("req", "blood", "r1", "don")
        ok("req", "call_cancel", mapOf("callId" to c2))
        startOk("don", "blood", "r1")
        assertEquals(403, start("don", "blood", "r1", "conf").status)
        endAll()
        jdbc.update("UPDATE blood_requests SET status = 'closed'")
        assertEquals(403, start("req", "blood", "r1", "don").status)
        assertEquals(403, start("conf", "blood", "r1").status)
    }

    @Test
    fun `appointment - patient, doctor and assistant around a confirmed slot`() {
        for (u in listOf("pat", "doc", "asst", "other")) person(u)
        doctor("doc", "asst@example.com")
        appointment("ap1", "pat", "doc", slotOffsetMin = -10)
        appointment("ap2", "pat", "doc", slotOffsetMin = 120)
        appointment("ap3", "pat", "doc", slotOffsetMin = -10, status = "pending")
        appointment("ap4", "pat", "doc", slotOffsetMin = -200)
        val s = start("pat", "appointment", "ap1")
        assertEquals(200, s.status, s.toString())
        assertEquals("doc", s.body["peer"]["uid"].textValue())
        assertFalse(s.body["video"].booleanValue())
        ok("pat", "call_cancel", mapOf("callId" to s.body["callId"].textValue()))
        for (id in listOf("ap2", "ap3", "ap4")) assertEquals(403, start("pat", "appointment", id).status, id)
        startOk("doc", "appointment", "ap1")
        endAll()
        assertEquals("pat", start("asst", "appointment", "ap1").body["peer"]["uid"].textValue())
        endAll()
        startOk("pat", "appointment", "ap1", "asst")
        endAll()
        assertEquals(403, start("pat", "appointment", "ap1", "other").status)
        assertEquals(403, start("other", "appointment", "ap1").status)
        hourPasses()
        jdbc.update("UPDATE appointments SET online = TRUE WHERE id = 'ap1'")
        assertTrue(start("pat", "appointment", "ap1").body["video"].booleanValue())
        endAll()
        jdbc.update("UPDATE providers SET admin_suspended = TRUE WHERE uid = 'doc'")
        assertEquals(403, start("pat", "appointment", "ap1").status)
    }

    @Test
    fun `ambulance - patient and the accepting driver, or a driver with a live offer while open`() {
        for (u in listOf("pat", "drv", "drv2", "drv3")) person(u)
        ambulance("am1", "pat", "accepted", "drv")
        assertEquals("drv", start("pat", "ambulance", "am1").body["peer"]["uid"].textValue())
        endAll()
        startOk("drv", "ambulance", "am1")
        endAll()
        assertEquals(403, start("drv2", "ambulance", "am1").status)
        assertEquals(403, start("pat", "ambulance", "am1", "drv2").status)
        ambulance("am2", "pat", "open")
        offer("am2", "drv2")
        offer("am2", "drv3", declined = true)
        assertEquals(403, start("pat", "ambulance", "am2").status) // nobody accepted: name the driver
        startOk("pat", "ambulance", "am2", "drv2")
        endAll()
        startOk("drv2", "ambulance", "am2")
        endAll()
        assertEquals(403, start("pat", "ambulance", "am2", "drv3").status)
        assertEquals(403, start("drv3", "ambulance", "am2").status)
        jdbc.update("UPDATE ambulance_requests SET status = 'done', active = FALSE WHERE id = 'am1'")
        assertEquals(403, start("pat", "ambulance", "am1").status)
    }

    @Test
    fun `rx_order and lab_order - each only its own table, active orders, listing not suspended`() {
        for (u in listOf("pat", "shop", "labo", "doc", "other")) person(u)
        pharmacy("shop")
        lab("labo")
        rxOrder("o1", "pat", "shop", "doc")
        labOrder("l1", "pat", "labo")
        assertEquals("shop", start("pat", "rx_order", "o1").body["peer"]["uid"].textValue())
        endAll()
        startOk("shop", "rx_order", "o1")
        endAll()
        assertEquals("labo", start("pat", "lab_order", "l1").body["peer"]["uid"].textValue())
        endAll()
        startOk("labo", "lab_order", "l1")
        endAll()
        // the wrong table never matches (R5)
        assertEquals(403, start("pat", "rx_order", "l1").status)
        assertEquals(403, start("pat", "lab_order", "o1").status)
        assertEquals(400, start("pat", "order", "o1").status)
        assertEquals(403, start("other", "rx_order", "o1").status)
        assertEquals(403, start("pat", "rx_order", "o1", "labo").status)
        jdbc.update("UPDATE lab_orders SET status = 'reported'")
        assertEquals(403, start("pat", "lab_order", "l1").status)
        jdbc.update("UPDATE pharmacies SET status = 'suspended'")
        assertEquals(403, start("pat", "rx_order", "o1").status)
        jdbc.update("UPDATE pharmacies SET status = 'approved'")
        jdbc.update("UPDATE rx_orders SET status = 'done'")
        assertEquals(403, start("pat", "rx_order", "o1").status)
    }

    @Test
    fun `never demo with real, deactivated, deleting or disabled, bad input 400, caregiver refused`() {
        person("req")
        person("demo-d1")
        person("don")
        bloodRequest("r1", "req", mapOf("demo-d1" to "accepted", "don" to "accepted"))
        assertEquals(403, start("req", "blood", "r1", "demo-d1").status)
        assertEquals(403, start("demo-d1", "blood", "r1").status)
        jdbc.update("UPDATE accounts SET deactivated_at = now() WHERE uid = 'don'")
        assertEquals("call_not_allowed", start("req", "blood", "r1", "don").code)
        jdbc.update("UPDATE accounts SET deactivated_at = NULL, delete_at = now() + interval '30 days' WHERE uid = 'don'")
        assertEquals(403, start("req", "blood", "r1", "don").status)
        jdbc.update("UPDATE accounts SET delete_at = NULL, disabled_reason = 'x' WHERE uid = 'don'")
        assertEquals(403, start("req", "blood", "r1", "don").status)
        assertEquals(400, call("req", "call_start", mapOf("ctx" to "pizza", "refId" to "r1")).status)
        assertEquals(400, call("req", "call_start", mapOf("ctx" to "blood")).status)
        assertEquals(400, call("req", "call_start", mapOf("ctx" to "blood", "refId" to "r1", "video" to "yes")).status)
        person("cg")
        jdbc.update("INSERT INTO care_links (patient_uid, caretaker_uid, status) VALUES ('req', 'cg', 'active')")
        assertEquals(403, call("cg", "call_start", mapOf("asUid" to "req", "ctx" to "blood", "refId" to "r1", "peerUid" to "don")).status)
        assertEquals(401, call(null, "call_config").status)
    }

    // ------------------------------------------------------------------ state machine, pushes

    @Test
    fun `ring, accept, end - generic lock-screen push, call_state to both, room deleted, history with dir`() {
        person("a", "মোঃ আলী হোসেন")
        person("b", "বাবু")
        bloodRequest("r1", "a", mapOf("b" to "accepted"))
        val id = startOk("a", "blood", "r1", "b")
        val inc = pushes("b", "call_incoming").single()
        assertEquals("calls", inc.data["cat"])
        assertEquals(id, inc.data["callId"])
        assertEquals("false", inc.data["video"])
        assertEquals("আলী", inc.data["caller"]) // first name only (R9)
        assertEquals("লাইফলো কল", inc.data["title"])
        assertEquals("আলী", inc.data["body"])
        assertNull(inc.data["ctx"])
        assertNull(inc.data["refId"])
        assertEquals(45L, inc.ttlSeconds)
        assertFalse(inc.data.values.any { it.contains("01711000000") || it.contains("হোসেন") })
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM inbox_items", Int::class.java))
        val st = ok("b", "call_status", mapOf("callId" to id))
        assertEquals("ringing", st["state"].textValue())
        assertEquals("in", st["dir"].textValue())
        assertEquals("blood", st["ctx"].textValue())
        assertNull(st["token"]) // no token before answering
        push.messages.clear()
        val acc = ok("b", "call_accept", mapOf("callId" to id))
        assertEquals("a", acc["peer"]["uid"].textValue())
        assertEquals(listOf("accepted"), pushes("a", "call_state").map { it.data["state"] })
        assertEquals(listOf("accepted"), pushes("b", "call_state").map { it.data["state"] })
        // the heartbeat answers a fresh token
        val hb = ok("a", "call_status", mapOf("callId" to id))
        assertEquals("accepted", hb["state"].textValue())
        assertEquals("c_$id", claims(hb["token"].textValue())["video"]["room"].textValue())
        assertEquals("wss://lifolo.test/rtc", hb["url"].textValue())
        assertEquals(id, ok("b", "call_accept", mapOf("callId" to id))["callId"].textValue())
        jdbc.update("UPDATE call_sessions SET accepted_at = accepted_at - interval '50 seconds' WHERE id = ?", id)
        push.messages.clear()
        assertEquals("ended", ok("a", "call_end", mapOf("callId" to id))["state"].textValue())
        assertEquals(listOf("DeleteRoom c_$id"), roomOps)
        assertEquals(listOf("ended"), pushes("b", "call_state").map { it.data["state"] })
        assertEquals("ended", ok("b", "call_end", mapOf("callId" to id))["state"].textValue())
        assertEquals("ended", ok("b", "call_decline", mapOf("callId" to id))["state"].textValue())
        assertEquals("call_gone", call("b", "call_accept", mapOf("callId" to id)).code)
        val fin = ok("a", "call_status", mapOf("callId" to id))
        assertTrue(fin["durationSec"].longValue() in 49..80, fin.toString())
        assertNotNull(fin["answeredAt"])
        assertNull(fin["token"])
        assertEquals("out", ok("a", "call_history")["items"][0]["dir"].textValue())
        assertEquals("in", ok("b", "call_history")["items"][0]["dir"].textValue())
    }

    @Test
    fun `video call answered with audio - both microphone only, upgrade asked and accepted gives video tokens`() {
        person("a")
        person("b")
        bloodRequest("r1", "a", mapOf("b" to "accepted"))
        val id = startOk("a", "blood", "r1", "b", video = true)
        assertEquals("true", pushes("b", "call_incoming").single().data["video"])
        val acc = ok("b", "call_accept", mapOf("callId" to id, "video" to false))
        assertFalse(acc["video"].booleanValue())
        assertEquals(listOf("microphone"), claims(acc["token"].textValue())["video"]["canPublishSources"].map { it.textValue() })
        val sa0 = ok("a", "call_status", mapOf("callId" to id))
        assertEquals(listOf("microphone"), claims(sa0["token"].textValue())["video"]["canPublishSources"].map { it.textValue() })
        assertEquals("no_upgrade", call("b", "call_upgrade_accept", mapOf("callId" to id)).code)
        push.messages.clear()
        assertFalse(ok("a", "call_upgrade", mapOf("callId" to id))["video"].booleanValue())
        assertEquals("a", pushes("b", "call_upgrade").single().data["by"])
        assertEquals("a", ok("b", "call_status", mapOf("callId" to id))["upgrade"]["by"].textValue())
        assertEquals("no_upgrade", call("a", "call_upgrade_accept", mapOf("callId" to id)).code)
        assertFalse(ok("b", "call_upgrade_decline", mapOf("callId" to id))["video"].booleanValue())
        assertEquals(1, pushes("a", "call_upgrade_declined").size)
        ok("a", "call_upgrade", mapOf("callId" to id))
        val up = ok("b", "call_upgrade_accept", mapOf("callId" to id))
        assertTrue(up["video"].booleanValue())
        assertEquals(listOf("camera", "microphone"), claims(up["token"].textValue())["video"]["canPublishSources"].map { it.textValue() })
        assertEquals(1, pushes("a", "call_upgraded").size)
        val sa = ok("a", "call_status", mapOf("callId" to id))
        assertTrue(sa["video"].booleanValue())
        assertEquals(listOf("camera", "microphone"), claims(sa["token"].textValue())["video"]["canPublishSources"].map { it.textValue() })
        person("x")
        for (act in listOf("call_upgrade", "call_upgrade_accept", "call_upgrade_decline")) assertEquals(404, call("x", act, mapOf("callId" to id)).status, act)
        // an audio call answered with video:true stays audio
        endAll()
        hourPasses()
        val id2 = startOk("a", "blood", "r1", "b")
        assertFalse(ok("b", "call_accept", mapOf("callId" to id2, "video" to true))["video"].booleanValue())
    }

    @Test
    fun `45 s without an answer - missed, one collapsed call_missed per caller, caller sees missed`() {
        person("a", "আলী খান")
        person("b")
        bloodRequest("r1", "a", mapOf("b" to "accepted"))
        val id = startOk("a", "blood", "r1", "b")
        assertEquals(0, calls.sweep())
        push.messages.clear()
        calls.clock = { System.currentTimeMillis() + 46_000L }
        assertEquals(1, calls.sweep())
        assertEquals(0, calls.sweep())
        calls.clock = { System.currentTimeMillis() }
        val m = pushes("b", "call_missed").single()
        assertEquals("calls", m.data["cat"])
        assertEquals("লাইফলো: মিসড কল", m.data["title"])
        assertEquals("আলী কল করেছিলেন", m.data["body"])
        assertEquals("1", m.data["missedCount"])
        assertEquals("missed_a", m.collapseKey)
        assertEquals(listOf("missed"), pushes("a", "call_state").map { it.data["state"] })
        assertTrue(roomOps.contains("DeleteRoom c_$id"))
        assertEquals("missed", ok("a", "call_status", mapOf("callId" to id))["state"].textValue())
        assertEquals("call_gone", call("b", "call_accept", mapOf("callId" to id)).code)
        // a second missed call from the same caller: one bell entry, "২টা মিসড কল"
        val id2 = startOk("a", "blood", "r1", "b")
        jdbc.update("UPDATE call_sessions SET created_at = created_at - interval '50 seconds' WHERE id = ?", id2)
        push.messages.clear()
        assertEquals("missed", ok("a", "call_status", mapOf("callId" to id2))["state"].textValue())
        val m2 = pushes("b", "call_missed").single()
        assertEquals("লাইফলো: ২টা মিসড কল", m2.data["title"])
        assertEquals("2", m2.data["missedCount"])
        val inbox = jdbc.queryForList("SELECT data->>'callId' AS call FROM inbox_items WHERE uid = 'b' AND data->>'type' = 'call_missed'")
        assertEquals(listOf(id2), inbox.map { it["call"] })
    }

    @Test
    fun `busy - ringing or answered with live heartbeats, stale heartbeats end the call and free the person`() {
        for (u in listOf("a", "b", "c")) person(u)
        bloodRequest("r1", "a", mapOf("b" to "accepted"))
        bloodRequest("r2", "c", mapOf("b" to "accepted"))
        val id = startOk("a", "blood", "r1", "b")
        val r = start("c", "blood", "r2", "b")
        assertEquals(409, r.status)
        assertEquals("call_busy", r.code)
        ok("b", "call_accept", mapOf("callId" to id))
        assertEquals("call_busy", start("c", "blood", "r2", "b").code)
        // both apps died: no heartbeat for 100 s → not busy, and the job ends the call
        jdbc.update(
            "UPDATE call_sessions SET caller_seen_at = now() - interval '100 seconds', callee_seen_at = now() - interval '100 seconds' WHERE id = ?", id,
        )
        val c2 = startOk("c", "blood", "r2", "b")
        ok("c", "call_cancel", mapOf("callId" to c2))
        assertEquals(1, calls.recheckLive())
        assertEquals("ended", state(id))
        assertEquals("heartbeat", row("SELECT end_reason FROM call_sessions WHERE id = ?", id)["end_reason"])
        assertTrue(roomOps.contains("DeleteRoom c_$id"))
        // one side keeps pinging, the other stopped → ended on the next ping
        val id3 = startOk("a", "blood", "r1", "b")
        ok("b", "call_accept", mapOf("callId" to id3))
        jdbc.update("UPDATE call_sessions SET callee_seen_at = now() - interval '100 seconds' WHERE id = ?", id3)
        assertEquals("ended", ok("a", "call_status", mapOf("callId" to id3))["state"].textValue())
    }

    @Test
    fun `the relation ends - the call ends at the next check and its room goes, account deactivation ends calls at once`() {
        for (u in listOf("a", "b")) person(u)
        bloodRequest("r1", "a", mapOf("b" to "accepted"))
        val id = startOk("a", "blood", "r1", "b")
        ok("b", "call_accept", mapOf("callId" to id))
        jdbc.update("UPDATE blood_responses SET status = 'cancelled'")
        assertEquals("ended", ok("a", "call_status", mapOf("callId" to id))["state"].textValue())
        assertEquals("relation", row("SELECT end_reason FROM call_sessions WHERE id = ?", id)["end_reason"])
        assertTrue(roomOps.contains("DeleteRoom c_$id"))
        // the 15 s job does the same without anyone asking
        jdbc.update("UPDATE blood_responses SET status = 'accepted'")
        val id2 = startOk("a", "blood", "r1", "b")
        ok("b", "call_accept", mapOf("callId" to id2))
        assertEquals(0, calls.recheckLive())
        jdbc.update("UPDATE blood_requests SET status = 'closed'")
        assertEquals(1, calls.recheckLive())
        assertEquals("ended", state(id2))
        // deactivation: at once
        jdbc.update("UPDATE blood_requests SET status = 'open'")
        hourPasses()
        val id3 = startOk("a", "blood", "r1", "b")
        ok("b", "call_accept", mapOf("callId" to id3))
        roomOps.clear()
        ok("b", "account_deactivate")
        assertEquals("ended", state(id3))
        assertEquals("account", row("SELECT end_reason FROM call_sessions WHERE id = ?", id3)["end_reason"])
        assertEquals(listOf("DeleteRoom c_$id3"), roomOps)
    }

    @Test
    fun `limits - 3 unanswered per pair an hour, 2 declines pause 30 min, 20 rings an hour per callee, 10 starts per caller`() {
        person("a")
        person("b")
        bloodRequest("r1", "a", mapOf("b" to "accepted"))
        repeat(3) { ok("a", "call_cancel", mapOf("callId" to startOk("a", "blood", "r1", "b"))) }
        assertEquals("call_rate", start("a", "blood", "r1", "b").code)
        hourPasses()
        ok("b", "call_decline", mapOf("callId" to startOk("a", "blood", "r1", "b")))
        ok("b", "call_decline", mapOf("callId" to startOk("a", "blood", "r1", "b")))
        assertEquals(429, start("a", "blood", "r1", "b").status)
        jdbc.update("UPDATE call_sessions SET created_at = created_at - interval '31 minutes', ended_at = ended_at - interval '31 minutes'")
        val id = startOk("a", "blood", "r1", "b")
        ok("b", "call_accept", mapOf("callId" to id))
        ok("b", "call_end", mapOf("callId" to id))
        hourPasses()
        // per callee: 20 rings an hour from everybody together
        val donors = (1..21).map { "d$it" }
        donors.forEach { person(it) }
        bloodRequest("r2", "b", donors.associateWith { "accepted" }, confirmed = donors.toSet())
        for (d in donors.take(20)) ok(d, "call_cancel", mapOf("callId" to startOk(d, "blood", "r2")))
        assertEquals("call_rate", start("d21", "blood", "r2").code)
        hourPasses()
        // per caller: 10 starts / 10 minutes, whoever is called
        person("z")
        val many = (1..11).map { "y$it" }
        many.forEach { person(it) }
        bloodRequest("r3", "z", many.associateWith { "accepted" })
        for (y in many.take(10)) ok("z", "call_cancel", mapOf("callId" to startOk("z", "blood", "r3", y)))
        assertEquals("call_rate", start("z", "blood", "r3", "y11").code)
    }

    @Test
    fun `calls category or master switch off - 409 call_off, nothing rings`() {
        person("a")
        person("b")
        bloodRequest("r1", "a", mapOf("b" to "accepted"))
        assertTrue(ok("b", "notify_prefs_get")["cats"]["calls"].booleanValue())
        ok("b", "notify_prefs_set", mapOf("cats" to mapOf("calls" to false)))
        val r = start("a", "blood", "r1", "b")
        assertEquals(409, r.status)
        assertEquals("call_off", r.code)
        assertEquals("উনি অ্যাপে কল বন্ধ রেখেছেন — ফোনে কল করুন", r.error)
        ok("b", "notify_prefs_set", mapOf("all" to false, "cats" to mapOf("calls" to true)))
        assertEquals("call_off", start("a", "blood", "r1", "b").code)
        assertTrue(pushes("b", "call_incoming").isEmpty())
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM call_sessions", Int::class.java))
        ok("b", "notify_prefs_set", mapOf("all" to true))
        startOk("a", "blood", "r1", "b")
    }

    // ------------------------------------------------------------------ block, report, contact_can

    @Test
    fun `block - ends live calls, blocker gets call_blocked, the blocked side a plain not allowed, list, unblock, contact_can`() {
        person("a")
        person("b", "বাবু")
        bloodRequest("r1", "a", mapOf("b" to "accepted"), confirmed = setOf("b"))
        val id = startOk("a", "blood", "r1", "b")
        ok("b", "call_accept", mapOf("callId" to id))
        ok("b", "call_block", mapOf("peerUid" to "a", "callId" to id))
        assertEquals("ended", state(id))
        assertEquals("blocked", row("SELECT end_reason FROM call_sessions WHERE id = ?", id)["end_reason"])
        assertEquals("call_not_allowed", start("a", "blood", "r1", "b").code)
        val mine = start("b", "blood", "r1")
        assertEquals(403, mine.status)
        assertEquals("call_blocked", mine.code)
        assertEquals("আপনি এঁর সাথে যোগাযোগ বন্ধ রেখেছেন", mine.error)
        val list = ok("b", "call_blocks")["items"]
        assertEquals("a", list[0]["uid"].textValue())
        assertNotNull(list[0]["blockedAt"])
        assertEquals(0, ok("a", "call_blocks")["items"].size())
        val canB = can("b", mapOf("ctx" to "blood", "refId" to "r1"))[0]
        assertFalse(canB["canCall"].booleanValue())
        assertFalse(canB["canMessage"].booleanValue())
        assertTrue(canB["blocked"].booleanValue())
        val canA = can("a", mapOf("ctx" to "blood", "refId" to "r1", "peerUid" to "b"))[0]
        assertFalse(canA["canCall"].booleanValue())
        assertNull(canA["blocked"])
        ok("b", "call_unblock", mapOf("peerUid" to "a"))
        val id2 = startOk("a", "blood", "r1", "b")
        ok("a", "call_block", mapOf("peerUid" to "b"))
        assertEquals("declined", state(id2))
        ok("a", "call_unblock", mapOf("peerUid" to "b"))
        assertTrue(can("a", mapOf("ctx" to "blood", "refId" to "r1", "peerUid" to "b"))[0]["canCall"].booleanValue())
        assertEquals(400, call("a", "call_block", mapOf("peerUid" to "a")).status)
        assertEquals(400, call("a", "call_block", emptyMap()).status)
        ok("a", "call_block", mapOf("peerUid" to "nobody123"))
        person("x")
        assertEquals(404, call("x", "call_block", mapOf("peerUid" to "a", "callId" to id)).status)
        assertEquals(404, call("a", "call_block", mapOf("peerUid" to "x", "callId" to id)).status)
    }

    @Test
    fun `report - parties only, reasons, metadata copied, one per call and reporter`() {
        person("a")
        person("b")
        person("x")
        bloodRequest("r1", "a", mapOf("b" to "accepted"))
        val id = startOk("a", "blood", "r1", "b")
        ok("b", "call_decline", mapOf("callId" to id))
        ok("b", "call_report", mapOf("callId" to id, "reason" to "harassment", "note" to "বারবার কল"))
        ok("b", "call_report", mapOf("callId" to id, "reason" to "spam"))
        val r = row("SELECT reporter_uid, reported_uid, reason, call_meta->>'ctx' AS ctx, call_meta->>'state' AS state FROM call_reports")
        assertEquals("b", r["reporter_uid"])
        assertEquals("a", r["reported_uid"])
        assertEquals("spam", r["reason"])
        assertEquals("blood", r["ctx"])
        assertEquals("declined", r["state"])
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM call_reports", Int::class.java))
        assertEquals(400, call("b", "call_report", mapOf("callId" to id, "reason" to "bad")).status)
        assertEquals(400, call("b", "call_report", mapOf("callId" to id, "reason" to "other", "note" to "x".repeat(501))).status)
        assertEquals(404, call("x", "call_report", mapOf("callId" to id, "reason" to "spam")).status)
    }

    @Test
    fun `contact_can - the same rule as call_start for each item`() {
        for (u in listOf("pat", "shop", "don", "req")) person(u)
        pharmacy("shop")
        rxOrder("o1", "pat", "shop", "req")
        bloodRequest("r1", "req", mapOf("don" to "accepted"))
        val items = can(
            "pat",
            mapOf("ctx" to "rx_order", "refId" to "o1"),
            mapOf("ctx" to "lab_order", "refId" to "o1"),
            mapOf("ctx" to "blood", "refId" to "r1", "peerUid" to "don"),
        )
        assertTrue(items[0]["canCall"].booleanValue() && items[0]["canVideo"].booleanValue() && items[0]["canMessage"].booleanValue())
        assertEquals("shop", items[0]["peerUid"].textValue())
        assertFalse(items[1]["canCall"].booleanValue())
        assertFalse(items[2]["canMessage"].booleanValue())
        assertFalse(can("don", mapOf("ctx" to "blood", "refId" to "r1"))[0]["canCall"].booleanValue())
        assertTrue(can("req", mapOf("ctx" to "blood", "refId" to "r1", "peerUid" to "don"))[0]["canCall"].booleanValue())
        assertEquals(400, call("pat", "contact_can", mapOf("items" to listOf(mapOf("ctx" to "x", "refId" to "o1")))).status)
        assertEquals(400, call("pat", "contact_can", mapOf("items" to (1..51).map { mapOf("ctx" to "rx_order", "refId" to "o1") })).status)
        assertEquals(400, call("pat", "contact_can").status)
    }

    // ------------------------------------------------------------------ appt_join

    @Test
    fun `appt_join - one live room per appointment, a new room after the end, window, appt_call_ready`() {
        for (u in listOf("pat", "doc", "asst", "other")) person(u)
        doctor("doc", "asst@example.com")
        appointment("ap1", "pat", "doc", slotOffsetMin = 10, online = true)
        appointment("ap2", "pat", "doc", slotOffsetMin = 60, online = true)
        appointment("ap3", "pat", "doc", slotOffsetMin = 0, online = false)
        appointment("ap4", "pat", "doc", slotOffsetMin = 0, online = true, status = "pending")
        val p = ok("pat", "appt_join", mapOf("appointmentId" to "ap1"))
        val callId = p["callId"].textValue()
        assertEquals("c_$callId", p["room"].textValue())
        assertTrue(p["video"].booleanValue())
        val pc = claims(p["token"].textValue())
        assertEquals("pat", pc["sub"].textValue())
        assertEquals(listOf("camera", "microphone"), pc["video"]["canPublishSources"].map { it.textValue() })
        assertTrue(pushes("pat", "appt_call_ready").isEmpty())
        assertEquals(callId, ok("doc", "appt_join", mapOf("appointmentId" to "ap1"))["callId"].textValue())
        val ready = pushes("pat", "appt_call_ready").single()
        assertEquals("appointments", ready.data["cat"])
        assertEquals("ap1", ready.data["appointmentId"])
        assertEquals("লাইফলো: ভিডিও কলে যুক্ত হোন", ready.data["title"])
        assertEquals(callId, ok("asst", "appt_join", mapOf("appointmentId" to "ap1"))["callId"].textValue())
        assertEquals(1, pushes("pat", "appt_call_ready").size)
        val sa = ok("asst", "call_status", mapOf("callId" to callId))
        assertEquals("accepted", sa["state"].textValue())
        assertNotNull(sa["token"])
        // ended → the next join opens a NEW room (old tokens never lead back in)
        assertEquals("ended", ok("doc", "call_end", mapOf("callId" to callId))["state"].textValue())
        assertTrue(roomOps.contains("DeleteRoom c_$callId"))
        val again = ok("pat", "appt_join", mapOf("appointmentId" to "ap1"))["callId"].textValue()
        assertFalse(again == callId)
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM call_sessions WHERE ref_id = 'ap1' AND state = 'accepted'", Int::class.java))
        // nobody pings the room for 90 s → it ends
        jdbc.update(
            "UPDATE call_sessions SET caller_seen_at = now() - interval '100 seconds', callee_seen_at = NULL, accepted_at = now() - interval '100 seconds' WHERE id = ?",
            again,
        )
        assertEquals(1, calls.recheckLive())
        assertEquals("ended", state(again))
        // rooms do not make the doctor busy for ring calls
        ok("doc", "appt_join", mapOf("appointmentId" to "ap1"))
        startOk("pat", "appointment", "ap3")
        assertEquals(404, call("other", "appt_join", mapOf("appointmentId" to "ap1")).status)
        assertEquals("appt_join_window", call("pat", "appt_join", mapOf("appointmentId" to "ap2")).code)
        assertEquals("not_online", call("pat", "appt_join", mapOf("appointmentId" to "ap3")).code)
        assertEquals("state", call("pat", "appt_join", mapOf("appointmentId" to "ap4")).code)
        assertEquals(404, call("pat", "appt_join", mapOf("appointmentId" to "nope")).status)
        // the appointment cancelled → the live room ends
        jdbc.update("UPDATE appointments SET status = 'cancelled' WHERE id = 'ap1'")
        assertTrue(calls.recheckLive() >= 1)
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM call_sessions WHERE ref_id = 'ap1' AND state = 'accepted'", Int::class.java))
    }

    // ------------------------------------------------------------------ history paging, retention, deletion

    @Test
    fun `history pages of 30, retention 180 days in the daily job, account deletion removes the records`() {
        person("a")
        person("b")
        for (i in 0 until 35) insertCall("h$i", "b", "a", "missed", Timestamp(System.currentTimeMillis() - 3_600_000L - i * 1000L))
        val p1 = ok("a", "call_history")
        assertEquals(30, p1["items"].size())
        val p2 = ok("a", "call_history", mapOf("before" to p1["next"].longValue()))
        assertEquals(5, p2["items"].size())
        assertNull(p2["next"])
        assertEquals("in", p2["items"][0]["dir"].textValue())
        jdbc.update("DELETE FROM call_sessions")
        insertCall("old", "a", "b", "missed", Timestamp(System.currentTimeMillis() - 181L * 86_400_000L))
        insertCall("new", "a", "b", "missed", Timestamp(System.currentTimeMillis() - 179L * 86_400_000L))
        val out = daily.runAll(System.currentTimeMillis())
        assertEquals(1, out["callsDeleted"])
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM call_sessions", Int::class.java))
        insertCall("live", "b", "a", "ringing", Timestamp(System.currentTimeMillis()))
        val step = CallsDeletionStep(calls)
        step.hide("a")
        assertEquals("cancelled", state("live".padEnd(32, 'x')))
        step.deleteNow("a", null)
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM call_sessions", Int::class.java))
    }
}
