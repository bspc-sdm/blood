package com.lifolo.calls

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * POST /api/livekit/webhook — the call server tells us when a room finished or someone left (security review R11), so a
 * call whose apps died ends at once instead of after the 90 s heartbeat gap. Verified like LiveKit's WebhookReceiver:
 * the `Authorization` header is an HS256 JWT signed with LIVEKIT_API_SECRET, `iss` = LIVEKIT_API_KEY, and its `sha256`
 * claim is base64(SHA-256(raw body)). Anything else → 401. The call server's config: `webhook: {api_key, urls}`
 * (deploy/setup-call-server.sh).
 */
@RestController
class LiveKitWebhookController(
    private val tokens: LiveKitTokens,
    private val calls: CallService,
    private val mapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/api/livekit/webhook", consumes = ["*/*"])
    fun webhook(req: HttpServletRequest, @RequestBody(required = false) raw: ByteArray?): ResponseEntity<Any> {
        val body = raw ?: ByteArray(0)
        val auth = req.getHeader("Authorization")?.trim()?.removePrefix("Bearer ")?.trim()
        if (!tokens.available || auth.isNullOrEmpty() || !verify(auth, body, System.currentTimeMillis())) {
            return ResponseEntity.status(401).body(mapOf("error" to "unauthorized"))
        }
        val ev = try {
            mapper.readTree(body)
        } catch (e: Exception) {
            return ResponseEntity.status(400).body(mapOf("error" to "bad event"))
        }
        val event = ev.path("event").asText("")
        val room = ev.path("room").path("name").asText("").ifEmpty { null }
        val identity = ev.path("participant").path("identity").asText("").ifEmpty { null }
        val result = try {
            calls.onWebhook(event, room, identity)
        } catch (e: Exception) {
            log.error("livekit webhook {} failed", event, e)
            "failed"
        }
        return ResponseEntity.ok(mapOf("ok" to true, "result" to result))
    }

    /** LiveKit's WebhookReceiver.receive: signature, issuer, time window, body hash */
    fun verify(jwt: String, body: ByteArray, nowMs: Long): Boolean {
        val parts = jwt.split('.')
        if (parts.size != 3) return false
        val cfg = tokens.config
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(cfg.apiSecret.trim().toByteArray(Charsets.UTF_8), "HmacSHA256"))
            val expected = mac.doFinal("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
            val given = Base64.getUrlDecoder().decode(parts[2])
            if (!MessageDigest.isEqual(expected, given)) return false
            val header = mapper.readTree(Base64.getUrlDecoder().decode(parts[0]))
            if (header.path("alg").asText() != "HS256") return false
            val claims = mapper.readTree(Base64.getUrlDecoder().decode(parts[1]))
            if (claims.path("iss").asText() != cfg.apiKey.trim()) return false
            val now = nowMs / 1000
            if (claims.has("exp") && claims.path("exp").asLong() + LEEWAY_S < now) return false
            if (claims.has("nbf") && claims.path("nbf").asLong() - LEEWAY_S > now) return false
            val hash = MessageDigest.getInstance("SHA-256").digest(body)
            val sha = claims.path("sha256").asText("")
            // LiveKit puts standard base64; accept the URL-safe spelling too
            sha.isNotEmpty() && (sha == Base64.getEncoder().encodeToString(hash) || sha.trimEnd('=') == Base64.getUrlEncoder().withoutPadding().encodeToString(hash))
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        const val LEEWAY_S = 60L
    }
}
