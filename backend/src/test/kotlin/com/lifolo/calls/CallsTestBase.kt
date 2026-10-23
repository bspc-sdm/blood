package com.lifolo.calls

import com.fasterxml.jackson.databind.JsonNode
import com.lifolo.common.LifoloProperties
import com.lifolo.support.IntegrationTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import java.sql.Timestamp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/** fixtures for the in-app call tests: the call server "configured", RoomService calls recorded, relation records */
abstract class CallsTestBase : IntegrationTest() {
    @Autowired lateinit var tokens: LiveKitTokens

    @Autowired lateinit var rooms: LiveKitRooms

    @Autowired lateinit var calls: CallService

    @Autowired lateinit var features: CallFeatures

    val lk = LifoloProperties.LiveKit("wss://lifolo.test/rtc", "APIkey1", "secret-secret-secret-secret-1234")
    private lateinit var saved: LifoloProperties.LiveKit
    private val dhaka = ZoneId.of("Asia/Dhaka")

    /** RoomService requests: "DeleteRoom c_…" / "RemoveParticipant c_… uid" */
    val roomOps = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun liveKitOn() {
        saved = tokens.config
        tokens.config = lk
        roomOps.clear()
        rooms.recorder = { op, room, id -> roomOps.add(listOfNotNull(op, room, id).joinToString(" ")) }
        calls.clock = { System.currentTimeMillis() }
        features.override = null
    }

    @AfterEach
    fun liveKitBack() {
        tokens.config = saved
        rooms.recorder = null
        features.override = null
        calls.clock = { System.currentTimeMillis() }
    }

    fun person(uid: String, name: String = "নাম $uid") {
        user(uid, mapOf("name" to name, "phone" to "01711000000"))
        jdbc.update("INSERT INTO device_tokens (token, uid) VALUES (?, ?) ON CONFLICT DO NOTHING", "tok-$uid", uid)
    }

    fun ok(uid: String, action: String, body: Map<String, Any?> = emptyMap()): JsonNode {
        val r = call(uid, action, body)
        assertEquals(200, r.status, "$action $body → $r")
        return r.body
    }

    fun start(uid: String, ctx: String, refId: String, peer: String? = null, video: Boolean? = null) =
        call(
            uid, "call_start",
            linkedMapOf<String, Any?>("ctx" to ctx, "refId" to refId).apply {
                if (peer != null) put("peerUid", peer)
                if (video != null) put("video", video)
            },
        )

    fun startOk(uid: String, ctx: String, refId: String, peer: String? = null, video: Boolean? = null): String {
        val r = start(uid, ctx, refId, peer, video)
        assertEquals(200, r.status, "call_start $ctx $refId $peer → $r")
        return r.body["callId"].textValue()
    }

    fun pushes(uid: String, type: String) = push.messages.filter { it.uid == uid && it.data["type"] == type }

    fun state(id: String): String = jdbc.queryForObject("SELECT state FROM call_sessions WHERE id = ?", String::class.java, id)!!

    /** an hour passes for the pair/callee limits (every call row moves 61 minutes back) */
    fun hourPasses() {
        jdbc.update(
            """
            UPDATE call_sessions SET created_at = created_at - interval '61 minutes', accepted_at = accepted_at - interval '61 minutes',
              ended_at = ended_at - interval '61 minutes'
            """.trimIndent(),
        )
    }

    /** every live call ends (between steps of a test) */
    fun endAll() {
        jdbc.update("UPDATE call_sessions SET state = 'ended', ended_at = now() WHERE state IN ('ringing', 'accepted')")
    }

    fun insertCall(id: String, caller: String, callee: String, state: String, createdAt: Timestamp, ctx: String = "blood", ref: String = "r") {
        val longId = id.padEnd(32, 'x')
        jdbc.update(
            "INSERT INTO call_sessions (id, room, ctx, ref_id, caller_uid, callee_uid, state, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            longId, "c_$longId", ctx, ref, caller, callee, state, createdAt,
        )
    }

    /** an open blood request of [requester] with responses donor → status (confirmed donors in [confirmed]) */
    fun bloodRequest(id: String, requester: String, responses: Map<String, String> = emptyMap(), confirmed: Set<String> = emptySet(), status: String = "open") {
        jdbc.update(
            """
            INSERT INTO blood_requests (id, requester_uid, patient_group, bags, hospital, lat, lng, geohash, needed_by_ms, status)
            VALUES (?, ?, 'A+', 1, 'ঢাকা মেডিকেল', 23.7, 90.4, 'wh0r', ?, ?)
            """.trimIndent(),
            id, requester, System.currentTimeMillis() + 86_400_000L, status,
        )
        for ((d, st) in responses) {
            jdbc.update("INSERT INTO blood_responses (request_id, donor_uid, status, confirmed) VALUES (?, ?, ?, ?)", id, d, st, d in confirmed)
        }
    }

    fun doctor(uid: String, assistantEmail: String? = null) {
        jdbc.update(
            "INSERT INTO providers (uid, type, name, listed, active, appointments) VALUES (?, 'doctor', 'ডাক্তার', TRUE, TRUE, TRUE) ON CONFLICT (uid) DO NOTHING",
            uid,
        )
        if (assistantEmail != null) {
            jdbc.update("INSERT INTO provider_assistants (provider_uid, email, position) VALUES (?, ?, 0) ON CONFLICT DO NOTHING", uid, assistantEmail)
        }
    }

    /** an appointment whose slot is [slotOffsetMin] minutes from now (Dhaka date + HH:MM) */
    fun appointment(id: String, patient: String, doc: String, slotOffsetMin: Long = -10, status: String = "confirmed", online: Boolean = false) {
        doctor(doc)
        val at = Instant.now().plusSeconds(slotOffsetMin * 60).atZone(dhaka)
        jdbc.update(
            """
            INSERT INTO appointments (id, patient_uid, provider_uid, patient_name, provider_name, type, visit_date, start_time, status, serial, online)
            VALUES (?, ?, ?, 'রোগী', 'ডাক্তার', 'doctor', ?, ?, ?, 1, ?)
            """.trimIndent(),
            id, patient, doc, java.sql.Date.valueOf(at.toLocalDate()), at.format(DateTimeFormatter.ofPattern("HH:mm")), status, online,
        )
    }

    fun ambulance(id: String, patient: String, status: String = "accepted", acceptedBy: String? = null) {
        jdbc.update(
            "INSERT INTO ambulance_requests (id, uid, district, status, active, accepted_by) VALUES (?, ?, 'ঢাকা', ?, TRUE, ?)",
            id, patient, status, acceptedBy,
        )
    }

    fun offer(reqId: String, owner: String, declined: Boolean = false) {
        jdbc.update("INSERT INTO ambreq_targets (request_id, owner_uid, ambulance_id, declined) VALUES (?, ?, ?, ?)", reqId, owner, "car-$owner", declined)
        jdbc.update("INSERT INTO ambreq_quotes (request_id, owner_uid, ambulance_id, fare) VALUES (?, ?, ?, 1500)", reqId, owner, "car-$owner")
    }

    fun pharmacy(uid: String, status: String = "approved") {
        jdbc.update(
            "INSERT INTO pharmacies (uid, name, license_no, phone, district, status) VALUES (?, 'দোকান', ?, '01711111111', 'ঢাকা', ?)",
            uid, "L-$uid", status,
        )
    }

    fun lab(uid: String, status: String = "approved") {
        jdbc.update(
            "INSERT INTO labs (uid, name, district, address, license_no, phone, status) VALUES (?, 'ল্যাব', 'ঢাকা', 'ঠিকানা', ?, '01711000498', ?)",
            uid, "D-$uid", status,
        )
    }

    private var codeN = 0

    fun rxOrder(id: String, patient: String, shop: String, doc: String, status: String = "accepted") {
        val code = "AB23CD45" + (10 + codeN++).toString()
        jdbc.update("INSERT INTO prescriptions (id, code, doctor_uid, patient_uid, status) VALUES (?, ?, ?, ?, 'active')", "rx-$id", code, doc, patient)
        jdbc.update(
            "INSERT INTO rx_orders (id, rx_id, code, patient_uid, pharmacy_uid, mode, status) VALUES (?, ?, ?, ?, ?, 'online', ?)",
            id, "rx-$id", code, patient, shop, status,
        )
    }

    fun labOrder(id: String, patient: String, labUid: String, status: String = "received") {
        jdbc.update("INSERT INTO lab_orders (id, lab_uid, patient_uid, status) VALUES (?, ?, ?, ?)", id, labUid, patient, status)
    }

    fun claims(token: String): JsonNode = mapper.readTree(Base64.getUrlDecoder().decode(token.split('.')[1]))
}
