package com.lifolo.calls

import com.fasterxml.jackson.databind.JsonNode
import com.lifolo.common.LifoloProperties
import com.lifolo.jobs.DailyJobRunner
import com.lifolo.support.IntegrationTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.sql.Timestamp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** In-app calls (backend/docs/CALLS.md): relations, state machine, timeout, busy, off, tokens, appt_join, retention */
class CallsIT : IntegrationTest() {
    @Autowired lateinit var tokens: LiveKitTokens

    @Autowired lateinit var calls: CallService

    @Autowired lateinit var daily: DailyJobRunner

    private val lk = LifoloProperties.LiveKit("wss://lifolo.test/rtc", "APIkey1", "secret-secret-secret-secret-1234")
    private lateinit var saved: LifoloProperties.LiveKit
    private val dhaka = ZoneId.of("Asia/Dhaka")

    @BeforeEach
    fun liveKitOn() {
        saved = tokens.config
        tokens.config = lk
        calls.clock = { System.currentTimeMillis() }
        // appointments.online comes from the online-chambers migration (spring-p15); harmless once it exists
        jdbc.execute("ALTER TABLE appointments ADD COLUMN IF NOT EXISTS online BOOLEAN NOT NULL DEFAULT FALSE")
    }

    @AfterEach
    fun liveKitBack() {
        tokens.config = saved
        calls.clock = { System.currentTimeMillis() }
    }

    // ------------------------------------------------------------------ helpers

    private fun person(uid: String, name: String = "নাম $uid") {
        user(uid, mapOf("name" to name, "phone" to "01711000000"))
        jdbc.update("INSERT INTO device_tokens (token, uid) VALUES (?, ?) ON CONFLICT DO NOTHING", "tok-$uid", uid)
    }

    private fun ok(uid: String, action: String, body: Map<String, Any?> = emptyMap()): JsonNode {
        val r = call(uid, action, body)
        assertEquals(200, r.status, "$action $body → $r")
        return r.body
    }

    private fun start(uid: String, ctx: String, refId: String, peer: String? = null, video: Boolean? = null) =
        call(uid, "call_start", linkedMapOf<String, Any?>("ctx" to ctx, "refId" to refId).apply {
            if (peer != null) put("peerUid", peer)
            if (video != null) put("video", video)
        })

    private fun pushes(uid: String, type: String) = push.messages.filter { it.uid == uid && it.data["type"] == type }

    /** an open blood request of [requester] with responses donor → status */
    private fun bloodRequest(id: String, requester: String, responses: Map<String, String> = emptyMap(), status: String = "open") {
        jdbc.update(
            """
            INSERT INTO blood_requests (id, requester_uid, patient_group, bags, hospital, lat, lng, geohash, needed_by_ms, status)
            VALUES (?, ?, 'A+', 1, 'ঢাকা মেডিকেল', 23.7, 90.4, 'wh0r', ?, ?)
            """.trimIndent(),
            id, requester, System.currentTimeMillis() + 86_400_000L, status,
        )
        for ((d, st) in responses) {
            jdbc.update("INSERT INTO blood_responses (request_id, donor_uid, status) VALUES (?, ?, ?)", id, d, st)
        }
    }

    /** a confirmed appointment whose slot is [slotOffsetMin] minutes from now (Dhaka date + HH:MM) */
    private fun appointment(id: String, patient: String, doctor: String, slotOffsetMin: Long = -10, status: String = "confirmed", online: Boolean = false) {
        val at = Instant.now().plusSeconds(slotOffsetMin * 60).atZone(dhaka)
        jdbc.update(
            """
            INSERT INTO providers (uid, type, name, listed, active, appointments) VALUES (?, 'doctor', 'ডাক্তার', TRUE, TRUE, TRUE)
            ON CONFLICT (uid) DO NOTHING
            """.trimIndent(),
            doctor,
        )
        jdbc.update(
            """
            INSERT INTO appointments (id, patient_uid, provider_uid, patient_name, provider_name, type, visit_date, start_time, status, serial, online)
            VALUES (?, ?, ?, 'রোগী', 'ডাক্তার', 'doctor', ?, ?, ?, 1, ?)
            """.trimIndent(),
            id, patient, doctor, java.sql.Date.valueOf(at.toLocalDate()), at.format(DateTimeFormatter.ofPattern("HH:mm")), status, online,
        )
    }

    private fun decode(part: String): JsonNode = mapper.readTree(Base64.getUrlDecoder().decode(part))

    // ------------------------------------------------------------------ unavailable / config / token

    @Test
    fun `without the LiveKit env - config off, call actions 503 calls_unavailable`() {
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
        assertEquals(503, call("u1", "call_accept", mapOf("callId" to "x")).status)
        assertEquals(503, call("u1", "appt_join", mapOf("appointmentId" to "x")).status)
        // history works without it
        assertEquals(0, ok("u1", "call_history")["items"].size())
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM call_sessions", Int::class.java))
    }

    @Test
    fun `config on - url from the env, token claims - room, identity, 2 h, publish and subscribe only, signed`() {
        person("u1", "রহিম")
        person("u2")
        bloodRequest("r1", "u1", mapOf("u2" to "accepted"))
        val c = ok("u1", "call_config")
        assertTrue(c["enabled"].booleanValue())
        assertEquals("wss://lifolo.test/rtc", c["url"].textValue())
        val s = ok("u1", "call_start", mapOf("ctx" to "blood", "refId" to "r1", "peerUid" to "u2", "video" to true))
        val callId = s["callId"].textValue()
        assertEquals("c_$callId", s["room"].textValue())
        assertEquals("wss://lifolo.test/rtc", s["url"].textValue())
        assertTrue(s["video"].booleanValue())
        assertEquals("u2", s["peer"]["uid"].textValue())
        assertEquals("নাম u2", s["peer"]["name"].textValue())
        assertNull(s["peer"]["phone"])
        val parts = s["token"].textValue().split('.')
        assertEquals(3, parts.size)
        assertEquals("HS256", decode(parts[0])["alg"].textValue())
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(lk.apiSecret.toByteArray(), "HmacSHA256")) }
        assertEquals(parts[2], Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal("${parts[0]}.${parts[1]}".toByteArray())))
        val cl = decode(parts[1])
        assertEquals("APIkey1", cl["iss"].textValue())
        assertEquals("u1", cl["sub"].textValue())
        assertEquals("রহিম", cl["name"].textValue())
        assertEquals(7200, cl["exp"].longValue() - cl["nbf"].longValue())
        val v = cl["video"]
        assertEquals("c_$callId", v["room"].textValue())
        assertTrue(v["roomJoin"].booleanValue())
        assertTrue(v["canPublish"].booleanValue())
        assertTrue(v["canSubscribe"].booleanValue())
        for (k in listOf("roomRecord", "roomAdmin", "roomCreate", "roomList", "ingressAdmin", "recorder", "hidden", "agent")) {
            assertNull(v[k], "grant $k must not be there")
        }
        // the callee's token: identity = callee, same room
        val a = ok("u2", "call_accept", mapOf("callId" to callId))
        val cl2 = decode(a["token"].textValue().split('.')[1])
        assertEquals("u2", cl2["sub"].textValue())
        assertEquals("c_$callId", cl2["video"]["room"].textValue())
        assertEquals("u1", a["peer"]["uid"].textValue())
    }

    // ------------------------------------------------------------------ relations

    @Test
    fun `blood - requester and donors who said they will donate, while open`() {
        for (u in listOf("req", "don", "don2", "gone", "stranger")) person(u)
        bloodRequest("r1", "req", mapOf("don" to "accepted", "don2" to "donated", "gone" to "cancelled"))
        // requester must name the donor
        assertEquals("call_not_allowed", start("req", "blood", "r1").code)
        assertEquals(403, start("req", "blood", "r1", "gone").status)
        assertEquals(403, start("req", "blood", "r1", "stranger").status)
        assertEquals(403, start("stranger", "blood", "r1").status)
        assertEquals(403, start("gone", "blood", "r1").status)
        assertEquals(403, start("don", "blood", "r1", "don2").status)
        assertEquals(403, start("don", "blood", "nope").status)
        val s1 = start("req", "blood", "r1", "don")
        assertEquals(200, s1.status, s1.toString())
        ok("req", "call_cancel", mapOf("callId" to s1.body["callId"].textValue()))
        assertEquals(200, start("don2", "blood", "r1").status)
        // closed request: nobody
        jdbc.update("UPDATE call_sessions SET state = 'ended'")
        jdbc.update("UPDATE blood_requests SET status = 'closed'")
        assertEquals(403, start("don", "blood", "r1").status)
    }

    @Test
    fun `appointment - patient, doctor and assistant around a confirmed slot`() {
        for (u in listOf("pat", "doc", "asst", "other")) person(u)
        jdbc.update("INSERT INTO provider_assistants (provider_uid, email, position) VALUES ('doc', 'asst@example.com', 0)")
        appointment("ap1", "pat", "doc", slotOffsetMin = -10)
        appointment("ap2", "pat", "doc", slotOffsetMin = 120)
        appointment("ap3", "pat", "doc", slotOffsetMin = -10, status = "pending")
        appointment("ap4", "pat", "doc", slotOffsetMin = -200)
        val s = start("pat", "appointment", "ap1")
        assertEquals(200, s.status, s.toString())
        assertEquals("doc", s.body["peer"]["uid"].textValue())
        assertFalse(s.body["video"].booleanValue())
        ok("pat", "call_cancel", mapOf("callId" to s.body["callId"].textValue()))
        // too early (slot in 2 h), pending, more than 3 h after the slot
        for (id in listOf("ap2", "ap3", "ap4")) assertEquals(403, start("pat", "appointment", id).status, id)
        // doctor → patient; assistant → patient; patient → assistant
        assertEquals(200, start("doc", "appointment", "ap1").status)
        jdbc.update("UPDATE call_sessions SET state = 'ended'")
        val a = start("asst", "appointment", "ap1")
        assertEquals(200, a.status, a.toString())
        assertEquals("pat", a.body["peer"]["uid"].textValue())
        jdbc.update("UPDATE call_sessions SET state = 'ended'")
        assertEquals(200, start("pat", "appointment", "ap1", "asst").status)
        jdbc.update("UPDATE call_sessions SET state = 'ended'")
        // not an assistant, not a party
        assertEquals(403, start("pat", "appointment", "ap1", "other").status)
        assertEquals(403, start("other", "appointment", "ap1").status)
        // online appointment: video by default
        jdbc.update("UPDATE appointments SET online = TRUE WHERE id = 'ap1'")
        assertTrue(start("pat", "appointment", "ap1").body["video"].booleanValue())
        jdbc.update("UPDATE call_sessions SET state = 'ended'")
        // doctor suspended by the team
        jdbc.update("UPDATE providers SET admin_suspended = TRUE WHERE uid = 'doc'")
        assertEquals(403, start("pat", "appointment", "ap1").status)
    }

    @Test
    fun `ambulance - patient and the accepting driver, or an offering driver while open`() {
        for (u in listOf("pat", "drv", "drv2", "drv3")) person(u)
        jdbc.update(
            "INSERT INTO ambulance_requests (id, uid, district, status, active, accepted_by) VALUES ('am1', 'pat', 'ঢাকা', 'accepted', TRUE, 'drv')",
        )
        val s = start("pat", "ambulance", "am1")
        assertEquals(200, s.status, s.toString())
        assertEquals("drv", s.body["peer"]["uid"].textValue())
        ok("pat", "call_cancel", mapOf("callId" to s.body["callId"].textValue()))
        assertEquals(200, start("drv", "ambulance", "am1").status)
        jdbc.update("UPDATE call_sessions SET state = 'ended'")
        assertEquals(403, start("drv2", "ambulance", "am1").status)
        assertEquals(403, start("pat", "ambulance", "am1", "drv2").status)
        // open request with an offer from drv2 (drv3 declined)
        jdbc.update("INSERT INTO ambulance_requests (id, uid, district, status, active) VALUES ('am2', 'pat', 'ঢাকা', 'open', TRUE)")
        jdbc.update("INSERT INTO ambreq_targets (request_id, owner_uid, ambulance_id) VALUES ('am2', 'drv2', 'car2'), ('am2', 'drv3', 'car3')")
        jdbc.update("UPDATE ambreq_targets SET declined = TRUE WHERE owner_uid = 'drv3'")
        jdbc.update("INSERT INTO ambreq_quotes (request_id, owner_uid, ambulance_id, fare) VALUES ('am2', 'drv2', 'car2', 1500), ('am2', 'drv3', 'car3', 1200)")
        assertEquals(403, start("pat", "ambulance", "am2").status) // nobody accepted: name the driver
        assertEquals(200, start("pat", "ambulance", "am2", "drv2").status)
        jdbc.update("UPDATE call_sessions SET state = 'ended'")
        assertEquals(200, start("drv2", "ambulance", "am2").status)
        jdbc.update("UPDATE call_sessions SET state = 'ended'")
        assertEquals(403, start("pat", "ambulance", "am2", "drv3").status)
        assertEquals(403, start("drv3", "ambulance", "am2").status)
        // finished trip
        jdbc.update("UPDATE ambulance_requests SET status = 'done', active = FALSE WHERE id = 'am1'")
        assertEquals(403, start("pat", "ambulance", "am1").status)
    }

    @Test
    fun `order - patient and the pharmacy or lab of an active order`() {
        for (u in listOf("pat", "shop", "lab", "doc", "other")) person(u)
        jdbc.update("INSERT INTO pharmacies (uid, name, license_no, phone, district, status) VALUES ('shop', 'দোকান', 'L-1', '01711111111', 'ঢাকা', 'approved')")
        jdbc.update("INSERT INTO labs (uid, name, district, address, license_no, phone, status) VALUES ('lab', 'ল্যাব', 'ঢাকা', 'ঠিকানা', 'D-1', '01711000498', 'approved')")
        jdbc.update("INSERT INTO prescriptions (id, code, doctor_uid, patient_uid, status) VALUES ('rx1', 'AB23CD45EF', 'doc', 'pat', 'active')")
        jdbc.update("INSERT INTO rx_orders (id, rx_id, code, patient_uid, pharmacy_uid, mode, status) VALUES ('o1', 'rx1', 'AB23CD45EF', 'pat', 'shop', 'online', 'accepted')")
        jdbc.update("INSERT INTO lab_orders (id, lab_uid, patient_uid, status) VALUES ('l1', 'lab', 'pat', 'received')")
        val s = start("pat", "order", "o1")
        assertEquals(200, s.status, s.toString())
        assertEquals("shop", s.body["peer"]["uid"].textValue())
        ok("pat", "call_cancel", mapOf("callId" to s.body["callId"].textValue()))
        assertEquals(200, start("shop", "order", "o1").status)
        jdbc.update("UPDATE call_sessions SET state = 'ended'")
        assertEquals("lab", start("pat", "order", "l1").body["peer"]["uid"].textValue())
        jdbc.update("UPDATE call_sessions SET state = 'ended'")
        assertEquals(200, start("lab", "order", "l1").status)
        jdbc.update("UPDATE call_sessions SET state = 'ended'")
        assertEquals(403, start("other", "order", "o1").status)
        assertEquals(403, start("pat", "order", "o1", "lab").status)
        // done / reported orders, suspended shop
        jdbc.update("UPDATE lab_orders SET status = 'reported'")
        assertEquals(403, start("pat", "order", "l1").status)
        jdbc.update("UPDATE pharmacies SET status = 'suspended'")
        assertEquals(403, start("pat", "order", "o1").status)
        jdbc.update("UPDATE pharmacies SET status = 'approved'")
        jdbc.update("UPDATE rx_orders SET status = 'done'")
        assertEquals(403, start("pat", "order", "o1").status)
    }

    @Test
    fun `never demo with real, deactivated, deleting or disabled; bad input 400; caregiver refused`() {
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
    fun `ring, accept, end - pushes without phone numbers, call_state to both, history with dir`() {
        person("a", "আলী")
        person("b", "বাবু")
        bloodRequest("r1", "a", mapOf("b" to "accepted"))
        val s = ok("a", "call_start", mapOf("ctx" to "blood", "refId" to "r1", "peerUid" to "b"))
        val id = s["callId"].textValue()
        val inc = pushes("b", "call_incoming").single()
        assertEquals("calls", inc.data["cat"])
        assertEquals(id, inc.data["callId"])
        assertEquals("false", inc.data["video"])
        assertEquals("আলী", inc.data["caller"])
        assertEquals("a", inc.data["callerUid"])
        assertEquals("blood", inc.data["ctx"])
        assertEquals("r1", inc.data["refId"])
        assertEquals(45L, inc.ttlSeconds)
        assertFalse(inc.data.values.any { it.contains("01711000000") }, "no phone number in the push")
        // ringing never goes to the bell list
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM inbox_items", Int::class.java))
        assertEquals("ringing", ok("a", "call_status", mapOf("callId" to id))["state"].textValue())
        val st = ok("b", "call_status", mapOf("callId" to id))
        assertEquals("in", st["dir"].textValue())
        assertEquals("a", st["peer"]["uid"].textValue())
        // only the callee accepts; strangers see nothing
        person("x")
        assertEquals(404, call("a", "call_accept", mapOf("callId" to id)).status)
        assertEquals(404, call("x", "call_status", mapOf("callId" to id)).status)
        assertEquals(404, call("x", "call_end", mapOf("callId" to id)).status)
        push.messages.clear()
        ok("b", "call_accept", mapOf("callId" to id))
        assertEquals(listOf("accepted"), pushes("a", "call_state").map { it.data["state"] })
        assertEquals(listOf("accepted"), pushes("b", "call_state").map { it.data["state"] })
        assertEquals("calls", pushes("a", "call_state").single().data["cat"])
        // a second device accepting gets the token again
        assertEquals(id, ok("b", "call_accept", mapOf("callId" to id))["callId"].textValue())
        jdbc.update("UPDATE call_sessions SET accepted_at = accepted_at - interval '95 seconds' WHERE id = ?", id)
        push.messages.clear()
        assertEquals("ended", ok("a", "call_end", mapOf("callId" to id))["state"].textValue())
        assertEquals(listOf("ended"), pushes("b", "call_state").map { it.data["state"] })
        // idempotent, and no more accepting
        assertEquals("ended", ok("b", "call_end", mapOf("callId" to id))["state"].textValue())
        assertEquals("ended", ok("b", "call_decline", mapOf("callId" to id))["state"].textValue())
        assertEquals("call_gone", call("b", "call_accept", mapOf("callId" to id)).code)
        val fin = ok("a", "call_status", mapOf("callId" to id))
        assertEquals("ended", fin["state"].textValue())
        assertTrue(fin["durationSec"].longValue() in 94..120, fin.toString())
        assertNotNull(fin["answeredAt"])
        val ha = ok("a", "call_history")["items"]
        assertEquals("out", ha[0]["dir"].textValue())
        assertEquals("b", ha[0]["peer"]["uid"].textValue())
        assertEquals("blood", ha[0]["ctx"].textValue())
        assertEquals("in", ok("b", "call_history")["items"][0]["dir"].textValue())
        assertEquals(0, ok("x", "call_history")["items"].size())
    }

    @Test
    fun `decline and cancel - states, call_state to the callee so ringing stops, history paging`() {
        person("a")
        person("b")
        bloodRequest("r1", "a", mapOf("b" to "accepted"))
        val id1 = ok("a", "call_start", mapOf("ctx" to "blood", "refId" to "r1", "peerUid" to "b"))["callId"].textValue()
        assertEquals(404, call("a", "call_decline", mapOf("callId" to id1)).status)
        assertEquals("declined", ok("b", "call_decline", mapOf("callId" to id1))["state"].textValue())
        assertEquals("declined", ok("a", "call_status", mapOf("callId" to id1))["state"].textValue())
        val id2 = ok("a", "call_start", mapOf("ctx" to "blood", "refId" to "r1", "peerUid" to "b"))["callId"].textValue()
        assertEquals(404, call("b", "call_cancel", mapOf("callId" to id2)).status)
        push.messages.clear()
        assertEquals("cancelled", ok("a", "call_cancel", mapOf("callId" to id2))["state"].textValue())
        assertEquals(listOf("cancelled"), pushes("b", "call_state").map { it.data["state"] })
        assertTrue(pushes("b", "call_state").all { it.ttlSeconds == 60L })
        assertEquals("call_gone", call("b", "call_accept", mapOf("callId" to id2)).code)
        // redial: the earlier ringing call is cancelled
        val id3 = ok("a", "call_start", mapOf("ctx" to "blood", "refId" to "r1", "peerUid" to "b"))["callId"].textValue()
        val id4 = ok("a", "call_start", mapOf("ctx" to "blood", "refId" to "r1", "peerUid" to "b"))["callId"].textValue()
        assertEquals("cancelled", ok("a", "call_status", mapOf("callId" to id3))["state"].textValue())
        assertEquals("ringing", ok("a", "call_status", mapOf("callId" to id4))["state"].textValue())
        // paging: 35 old rows → 30 + next
        for (i in 0 until 35) {
            jdbc.update(
                "INSERT INTO call_sessions (id, ctx, ref_id, caller_uid, callee_uid, state, created_at) VALUES (?, 'blood', 'r1', 'b', 'a', 'missed', ?)",
                "old$i", Timestamp(System.currentTimeMillis() - 3_600_000L - i * 1000L),
            )
        }
        val p1 = ok("a", "call_history")
        assertEquals(30, p1["items"].size())
        val p2 = ok("a", "call_history", mapOf("before" to p1["next"].longValue()))
        assertEquals(39 - 30, p2["items"].size())
        assertNull(p2["next"])
        assertEquals("in", p2["items"][0]["dir"].textValue())
    }

    @Test
    fun `45 s without an answer - missed, call_missed push and a bell entry for the callee, caller sees missed`() {
        person("a", "আলী")
        person("b")
        bloodRequest("r1", "a", mapOf("b" to "accepted"))
        val id = ok("a", "call_start", mapOf("ctx" to "blood", "refId" to "r1", "peerUid" to "b"))["callId"].textValue()
        assertEquals(0, calls.sweep())
        push.messages.clear()
        calls.clock = { System.currentTimeMillis() + 46_000L }
        assertEquals(1, calls.sweep())
        assertEquals(0, calls.sweep())
        calls.clock = { System.currentTimeMillis() }
        val m = pushes("b", "call_missed").single()
        assertEquals("calls", m.data["cat"])
        assertEquals(id, m.data["callId"])
        assertEquals("মিসড কল", m.data["title"])
        assertEquals(listOf("missed"), pushes("a", "call_state").map { it.data["state"] })
        assertEquals(listOf("missed"), pushes("b", "call_state").map { it.data["state"] })
        val inbox = jdbc.queryForList("SELECT title, data->>'type' AS type, data->>'callId' AS call FROM inbox_items WHERE uid = 'b'")
        assertEquals(1, inbox.size)
        assertEquals("call_missed", inbox[0]["type"])
        assertEquals(id, inbox[0]["call"])
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM inbox_items WHERE uid = 'a'", Int::class.java))
        assertEquals("missed", ok("a", "call_status", mapOf("callId" to id))["state"].textValue())
        assertEquals("call_gone", call("b", "call_accept", mapOf("callId" to id)).code)
        // lazily too: a ringing row older than 45 s is missed on the next read
        val id2 = ok("a", "call_start", mapOf("ctx" to "blood", "refId" to "r1", "peerUid" to "b"))["callId"].textValue()
        jdbc.update("UPDATE call_sessions SET created_at = created_at - interval '50 seconds' WHERE id = ?", id2)
        assertEquals("missed", ok("a", "call_status", mapOf("callId" to id2))["state"].textValue())
        // an accepted call nobody ended closes 2 h after the answer
        val id3 = ok("a", "call_start", mapOf("ctx" to "blood", "refId" to "r1", "peerUid" to "b"))["callId"].textValue()
        ok("b", "call_accept", mapOf("callId" to id3))
        jdbc.update("UPDATE call_sessions SET accepted_at = now() - interval '121 minutes' WHERE id = ?", id3)
        val s3 = ok("a", "call_status", mapOf("callId" to id3))
        assertEquals("ended", s3["state"].textValue())
        assertEquals(7200, s3["durationSec"].longValue())
    }

    @Test
    fun `busy - callee ringing or in a call, 409 call_busy; free again after the end`() {
        for (u in listOf("a", "b", "c")) person(u)
        bloodRequest("r1", "a", mapOf("b" to "accepted"))
        bloodRequest("r2", "c", mapOf("b" to "accepted"))
        val id = ok("a", "call_start", mapOf("ctx" to "blood", "refId" to "r1", "peerUid" to "b"))["callId"].textValue()
        val r = start("c", "blood", "r2", "b")
        assertEquals(409, r.status)
        assertEquals("call_busy", r.code)
        ok("b", "call_accept", mapOf("callId" to id))
        assertEquals("call_busy", start("c", "blood", "r2", "b").code)
        ok("b", "call_end", mapOf("callId" to id))
        assertEquals(200, start("c", "blood", "r2", "b").status)
        // a timed-out ringing call does not make anyone busy
        jdbc.update("UPDATE call_sessions SET created_at = created_at - interval '50 seconds' WHERE state = 'ringing'")
        assertEquals(200, start("a", "blood", "r1", "b").status)
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
        assertEquals(200, start("a", "blood", "r1", "b").status)
    }

    @Test
    fun `rate limit - 10 starts in 10 minutes`() {
        person("a")
        person("b")
        bloodRequest("r1", "a", mapOf("b" to "accepted"))
        repeat(10) { assertEquals(200, start("a", "blood", "r1", "b").status, "start $it") }
        val r = start("a", "blood", "r1", "b")
        assertEquals(429, r.status)
        assertEquals("call_rate", r.code)
    }

    // ------------------------------------------------------------------ appt_join

    @Test
    fun `appt_join - one room per appointment, window, doctor joining pushes appt_call_ready`() {
        for (u in listOf("pat", "doc", "asst", "other")) person(u)
        jdbc.update("INSERT INTO provider_assistants (provider_uid, email, position) VALUES ('doc', 'asst@example.com', 0)")
        appointment("ap1", "pat", "doc", slotOffsetMin = 10, online = true)
        appointment("ap2", "pat", "doc", slotOffsetMin = 60, online = true)
        appointment("ap3", "pat", "doc", slotOffsetMin = 0, online = false)
        appointment("ap4", "pat", "doc", slotOffsetMin = 0, online = true, status = "pending")
        val p = ok("pat", "appt_join", mapOf("appointmentId" to "ap1"))
        val callId = p["callId"].textValue()
        assertEquals("c_$callId", p["room"].textValue())
        assertTrue(p["video"].booleanValue())
        assertEquals("pat", decode(p["token"].textValue().split('.')[1])["sub"].textValue())
        assertTrue(pushes("pat", "appt_call_ready").isEmpty())
        val d = ok("doc", "appt_join", mapOf("appointmentId" to "ap1"))
        assertEquals(callId, d["callId"].textValue())
        assertEquals("doc", decode(d["token"].textValue().split('.')[1])["sub"].textValue())
        val ready = pushes("pat", "appt_call_ready").single()
        assertEquals("appointments", ready.data["cat"])
        assertEquals("ap1", ready.data["appointmentId"])
        assertEquals("ডাক্তার ভিডিও কলে আছেন — যুক্ত হোন", ready.data["title"])
        // the assistant joins the same room; no second push within 2 minutes
        assertEquals(callId, ok("asst", "appt_join", mapOf("appointmentId" to "ap1"))["callId"].textValue())
        assertEquals(1, pushes("pat", "appt_call_ready").size)
        assertEquals("accepted", ok("asst", "call_status", mapOf("callId" to callId))["state"].textValue())
        // the doctor ends it; joining again re-opens the same room
        assertEquals("ended", ok("doc", "call_end", mapOf("callId" to callId))["state"].textValue())
        assertEquals(callId, ok("pat", "appt_join", mapOf("appointmentId" to "ap1"))["callId"].textValue())
        assertEquals("accepted", ok("pat", "call_status", mapOf("callId" to callId))["state"].textValue())
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM call_sessions WHERE ref_id = 'ap1'", Int::class.java))
        // rooms do not make the doctor busy for ring calls
        assertEquals(200, start("pat", "appointment", "ap3").status)
        // errors
        assertEquals(404, call("other", "appt_join", mapOf("appointmentId" to "ap1")).status)
        assertEquals("appt_join_window", call("pat", "appt_join", mapOf("appointmentId" to "ap2")).code)
        assertEquals("not_online", call("pat", "appt_join", mapOf("appointmentId" to "ap3")).code)
        assertEquals("state", call("pat", "appt_join", mapOf("appointmentId" to "ap4")).code)
        assertEquals(404, call("pat", "appt_join", mapOf("appointmentId" to "nope")).status)
    }

    // ------------------------------------------------------------------ retention, deletion

    @Test
    fun `retention 180 days in the daily job, account deletion removes the records`() {
        person("a")
        person("b")
        val old = Timestamp(System.currentTimeMillis() - 181L * 86_400_000L)
        val recent = Timestamp(System.currentTimeMillis() - 179L * 86_400_000L)
        jdbc.update("INSERT INTO call_sessions (id, ctx, ref_id, caller_uid, callee_uid, state, created_at) VALUES ('o1', 'blood', 'r', 'a', 'b', 'missed', ?)", old)
        jdbc.update("INSERT INTO call_sessions (id, ctx, ref_id, caller_uid, callee_uid, state, created_at) VALUES ('n1', 'blood', 'r', 'a', 'b', 'missed', ?)", recent)
        val out = daily.runAll(System.currentTimeMillis())
        assertEquals(1, out["callsDeleted"])
        assertEquals(listOf("n1"), jdbc.queryForList("SELECT id FROM call_sessions", String::class.java))
        jdbc.update("INSERT INTO call_sessions (id, ctx, ref_id, caller_uid, callee_uid, state) VALUES ('live', 'blood', 'r', 'b', 'a', 'ringing')")
        val step = CallsDeletionStep(calls)
        step.hide("a")
        assertEquals("cancelled", row("SELECT state FROM call_sessions WHERE id = 'live'")["state"])
        step.deleteNow("a", null)
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM call_sessions", Int::class.java))
    }
}
