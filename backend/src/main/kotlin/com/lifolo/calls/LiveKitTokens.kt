package com.lifolo.calls

import com.fasterxml.jackson.databind.ObjectMapper
import com.lifolo.common.LifoloProperties
import org.springframework.stereotype.Component
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * LiveKit access tokens (backend/docs/CALLS.md): an HS256 JWT signed with LIVEKIT_API_SECRET —
 * `iss` = API key, `sub` = identity (the uid), `name`, `nbf`, `exp` (2 h), and the video grant
 * `{room, roomJoin, canPublish, canSubscribe}` only: no admin/create/list/record/egress/ingress, no data channel.
 * Calls are never recorded.
 */
@Component
class LiveKitTokens(props: LifoloProperties, private val mapper: ObjectMapper) {
    /** the settings in use; tests swap them (null-safe: empty values = calls unavailable) */
    @Volatile
    var config: LifoloProperties.LiveKit = props.livekit

    val available: Boolean get() = config.configured

    /** the signalling URL the apps connect to (LIVEKIT_URL as configured) */
    val url: String get() = config.url.trim()

    fun token(identity: String, name: String, room: String, nowMs: Long = System.currentTimeMillis(), ttlSeconds: Long = TTL_SECONDS): String {
        val c = config
        check(c.configured) { "LiveKit is not configured" }
        val now = nowMs / 1000
        val header = linkedMapOf("alg" to "HS256", "typ" to "JWT")
        val claims = linkedMapOf<String, Any?>(
            "iss" to c.apiKey.trim(),
            "sub" to identity,
            "name" to name,
            "nbf" to now,
            "exp" to now + ttlSeconds,
            "video" to linkedMapOf(
                "room" to room,
                "roomJoin" to true,
                "canPublish" to true,
                "canSubscribe" to true,
                "canPublishData" to false,
            ),
        )
        val enc = Base64.getUrlEncoder().withoutPadding()
        val signingInput = enc.encodeToString(mapper.writeValueAsBytes(header)) + "." + enc.encodeToString(mapper.writeValueAsBytes(claims))
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(c.apiSecret.trim().toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return signingInput + "." + enc.encodeToString(mac.doFinal(signingInput.toByteArray(Charsets.US_ASCII)))
    }

    companion object {
        /** token lifetime: 2 hours */
        const val TTL_SECONDS = 2L * 60 * 60
    }
}
