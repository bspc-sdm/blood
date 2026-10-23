package com.lifolo.calls

import com.fasterxml.jackson.databind.ObjectMapper
import com.lifolo.common.LifoloProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * LiveKit access tokens (backend/docs/CALLS.md): an HS256 JWT signed with LIVEKIT_API_SECRET —
 * `iss` = API key, `sub` = identity (the uid), `name`, `nbf`, `exp` (10 min — it only gates joining; the apps fetch a
 * fresh one through call_status / appt_join), and the video grant `{room, roomJoin, canPublish, canSubscribe,
 * canPublishSources, canPublishData:false, canUpdateOwnMetadata:false}`: an audio call may publish only the
 * microphone, a video call camera + microphone; never screen share, data, admin, create, list, record or ingress.
 * Calls are never recorded.
 */
@Component
class LiveKitTokens(props: LifoloProperties, private val mapper: ObjectMapper) {
    /** the settings in use; tests swap them (empty values = calls unavailable) */
    @Volatile
    var config: LifoloProperties.LiveKit = props.livekit

    val available: Boolean get() = config.configured

    /** the signalling URL the apps connect to (LIVEKIT_URL as configured) */
    val url: String get() = config.url.trim()

    /** the server API base (LIVEKIT_API_URL, else LIVEKIT_URL with ws→http / wss→https) */
    val apiBase: String
        get() {
            val explicit = config.apiUrl.trim()
            val u = explicit.ifEmpty { url.replaceFirst(Regex("^ws://"), "http://").replaceFirst(Regex("^wss://"), "https://") }
            return u.trimEnd('/')
        }

    fun token(identity: String, name: String, room: String, video: Boolean, nowMs: Long = System.currentTimeMillis()): String =
        sign(
            identity, name, nowMs, TTL_SECONDS,
            linkedMapOf(
                "room" to room,
                "roomJoin" to true,
                "canPublish" to true,
                "canSubscribe" to true,
                "canPublishSources" to (if (video) listOf("camera", "microphone") else listOf("microphone")),
                "canPublishData" to false,
                "canUpdateOwnMetadata" to false,
            ),
        )

    /** a short server token for RoomService calls on one room (DeleteRoom / RemoveParticipant) */
    fun adminToken(room: String, nowMs: Long = System.currentTimeMillis()): String =
        sign("lifolo-server", "", nowMs, 60, linkedMapOf("room" to room, "roomAdmin" to true, "roomCreate" to true))

    private fun sign(identity: String, name: String, nowMs: Long, ttl: Long, grant: Map<String, Any?>): String {
        val c = config
        check(c.configured) { "LiveKit is not configured" }
        val now = nowMs / 1000
        val header = linkedMapOf("alg" to "HS256", "typ" to "JWT")
        val claims = linkedMapOf<String, Any?>("iss" to c.apiKey.trim(), "sub" to identity)
        if (name.isNotEmpty()) claims["name"] = name
        claims["nbf"] = now
        claims["exp"] = now + ttl
        claims["video"] = grant
        val enc = Base64.getUrlEncoder().withoutPadding()
        val signingInput = enc.encodeToString(mapper.writeValueAsBytes(header)) + "." + enc.encodeToString(mapper.writeValueAsBytes(claims))
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(c.apiSecret.trim().toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return signingInput + "." + enc.encodeToString(mac.doFinal(signingInput.toByteArray(Charsets.US_ASCII)))
    }

    companion object {
        /** token lifetime: 10 minutes (security review R3) */
        const val TTL_SECONDS = 10L * 60
    }
}

/**
 * LiveKit RoomService (Twirp over HTTP): when a call reaches a final state its room is deleted, so nobody — not even
 * with a still-valid token — stays in or re-enters it; a person who lost the right is removed. Best effort, in the
 * background: a failure is logged (the room also closes by itself: empty_timeout 60 s, departure_timeout 20 s on the
 * call server).
 */
@Component
class LiveKitRooms(private val tokens: LiveKitTokens, private val mapper: ObjectMapper) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val http: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build() }

    /** tests: every request goes here (op, room, identity) instead of over HTTP */
    @Volatile
    var recorder: ((String, String, String?) -> Unit)? = null

    fun deleteRoom(room: String) = send("DeleteRoom", room, null)

    fun removeParticipant(room: String, identity: String) = send("RemoveParticipant", room, identity)

    private fun send(op: String, room: String, identity: String?) {
        recorder?.let {
            it(op, room, identity)
            return
        }
        if (!tokens.available) return
        try {
            val body = linkedMapOf<String, Any?>("room" to room).apply { if (identity != null) put("identity", identity) }
            val req = HttpRequest.newBuilder(URI.create("${tokens.apiBase}/twirp/livekit.RoomService/$op"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer ${tokens.adminToken(room)}")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body)))
                .build()
            http.sendAsync(req, HttpResponse.BodyHandlers.discarding()).whenComplete { r, e ->
                // 404: the room is already gone (nobody joined, or it closed by itself) — fine
                if (e != null) {
                    log.warn("livekit {} {} failed: {}", op, room, e.message)
                } else if (r.statusCode() !in 200..299 && r.statusCode() != 404) {
                    log.warn("livekit {} {} answered {}", op, room, r.statusCode())
                }
            }
        } catch (e: Exception) {
            log.warn("livekit {} {} failed: {}", op, room, e.message)
        }
    }
}
