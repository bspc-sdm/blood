package com.lifolo.calls

import com.lifolo.support.IntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** POST /api/livekit/webhook (security review R11): signed like LiveKit; room_finished / participant_left end stuck calls */
class CallsWebhookIT : CallsTestBase() {
    private val enc = Base64.getUrlEncoder().withoutPadding()

    private fun jwt(body: String, secret: String = lk.apiSecret, iss: String = lk.apiKey, sha: String? = null, expOffset: Long = 300): String {
        val now = System.currentTimeMillis() / 1000
        val hash = sha ?: Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(body.toByteArray()))
        val h = enc.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val c = enc.encodeToString("""{"iss":"$iss","nbf":${now - 5},"exp":${now + expOffset},"sha256":"$hash"}""".toByteArray())
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
        return "$h.$c.${enc.encodeToString(mac.doFinal("$h.$c".toByteArray()))}"
    }

    private fun hook(body: String, auth: String?): IntegrationTest.Res {
        val req = MockMvcRequestBuilders.post("/api/livekit/webhook").contentType(MediaType.valueOf("application/webhook+json")).content(body)
        if (auth != null) req.header("Authorization", auth)
        val r = mvc.perform(req).andReturn().response
        val text = r.getContentAsString(Charsets.UTF_8)
        return IntegrationTest.Res(r.status, if (text.isBlank()) mapper.nullNode() else mapper.readTree(text), r)
    }

    private fun event(ev: String, room: String, identity: String? = null) =
        """{"event":"$ev","room":{"name":"$room","sid":"RM_x"}${if (identity != null) ""","participant":{"identity":"$identity","sid":"PA_x"}""" else ""},"id":"EV_1","createdAt":1}"""

    @Test
    fun `signature, issuer, body hash and expiry are checked`() {
        person("a")
        person("b")
        bloodRequest("r1", "a", mapOf("b" to "accepted"))
        val id = startOk("a", "blood", "r1", "b")
        ok("b", "call_accept", mapOf("callId" to id))
        val body = event("room_finished", "c_$id")
        assertEquals(401, hook(body, null).status)
        assertEquals(401, hook(body, jwt(body, secret = "wrong-secret-wrong-secret-12345678")).status)
        assertEquals(401, hook(body, jwt(body, iss = "other")).status)
        assertEquals(401, hook(body, jwt(body, sha = "AAAA")).status)
        assertEquals(401, hook(body.replace("RM_x", "RM_y"), jwt(body)).status) // body changed after signing
        assertEquals(401, hook(body, jwt(body, expOffset = -600)).status)
        assertEquals("accepted", state(id))
        // LiveKit sends the token without "Bearer"; both work
        val ok = hook(body, jwt(body))
        assertEquals(200, ok.status, ok.toString())
        assertEquals("ended", ok.body["result"].textValue())
        assertEquals("ended", state(id))
        assertEquals("room_finished", row("SELECT end_reason FROM call_sessions WHERE id = ?", id)["end_reason"])
        assertEquals("done", hook(body, "Bearer " + jwt(body)).body["result"].textValue())
        // calls not configured → nothing is accepted
        tokens.config = com.lifolo.common.LifoloProperties.LiveKit()
        assertEquals(401, hook(body, jwt(body)).status)
    }

    @Test
    fun `participant_left - ends an answered 1-1 call, cancels a ringing one when the caller left, frees an appointment seat`() {
        for (u in listOf("a", "b", "pat", "doc", "asst")) person(u)
        bloodRequest("r1", "a", mapOf("b" to "accepted"))
        val id = startOk("a", "blood", "r1", "b")
        ok("b", "call_accept", mapOf("callId" to id))
        val left = event("participant_left", "c_$id", "b")
        assertEquals("ended", hook(left, jwt(left)).body["result"].textValue())
        assertEquals("left", row("SELECT end_reason FROM call_sessions WHERE id = ?", id)["end_reason"])
        // someone not in the call leaving changes nothing
        hourPasses()
        val id2 = startOk("a", "blood", "r1", "b")
        val stranger = event("participant_left", "c_$id2", "zzz")
        assertEquals("ignored", hook(stranger, jwt(stranger)).body["result"].textValue())
        assertEquals("ringing", state(id2))
        val callerLeft = event("participant_left", "c_$id2", "a")
        assertEquals("ended", hook(callerLeft, jwt(callerLeft)).body["result"].textValue())
        assertEquals("cancelled", state(id2))
        // unknown room
        val unknown = event("room_finished", "c_nope")
        assertEquals("unknown", hook(unknown, jwt(unknown)).body["result"].textValue())
        // appointment room: the doctor leaves → the seat is free for the assistant at once, the room stays
        doctor("doc", "asst@example.com")
        appointment("ap1", "pat", "doc", slotOffsetMin = 0, online = true)
        val room = ok("doc", "appt_join", mapOf("appointmentId" to "ap1"))
        assertEquals("room_full", call("asst", "appt_join", mapOf("appointmentId" to "ap1")).code)
        val docLeft = event("participant_left", room["room"].textValue(), "doc")
        assertEquals("left", hook(docLeft, jwt(docLeft)).body["result"].textValue())
        assertNull(row("SELECT host_uid FROM call_sessions WHERE id = ?", room["callId"].textValue())["host_uid"])
        assertEquals(room["callId"].textValue(), ok("asst", "appt_join", mapOf("appointmentId" to "ap1"))["callId"].textValue())
        val fin = event("room_finished", room["room"].textValue())
        assertEquals("ended", hook(fin, jwt(fin)).body["result"].textValue())
    }
}
