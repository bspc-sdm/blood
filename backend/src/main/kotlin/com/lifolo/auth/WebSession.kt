package com.lifolo.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.lifolo.common.LifoloProperties
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Web admin panel session (server/lib/websession.js): a signed httpOnly cookie instead of a Firebase token
 * in the browser. Cookie `__Host-lf_admin` = base64url(JSON{u,e,i,s}) + "." + base64url(HMAC-SHA256).
 * Every cookie request must carry `x-lifolo-admin: 1` (CSRF). 2 h idle, 12 h absolute, refreshed at most once a minute.
 */
@Component
class WebSession(private val props: LifoloProperties, private val mapper: ObjectMapper) {
    data class Session(val u: String, val e: String, val i: Long, val s: Long)

    sealed interface Result {
        data class User(val user: AuthUser) : Result
        data class Error(val kind: String) : Result
    }

    private val key: ByteArray by lazy {
        val base = props.webSessionSecret.ifBlank { props.firebaseServiceAccount }
        check(base.isNotBlank()) { "সেশনের সিক্রেট নেই" }
        MessageDigest.getInstance("SHA-256").digest("lifolo-admin-web-v1\n$base".toByteArray(Charsets.UTF_8))
    }

    private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun sign(payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return b64(mac.doFinal(payload.toByteArray(Charsets.UTF_8)))
    }

    fun encode(s: Session): String {
        val json = mapper.writeValueAsString(linkedMapOf("u" to s.u, "e" to s.e, "i" to s.i, "s" to s.s))
        val payload = b64(json.toByteArray(Charsets.UTF_8))
        return "$payload.${sign(payload)}"
    }

    /** null when forged, malformed or expired */
    fun decode(token: String?, now: Long = System.currentTimeMillis()): Session? {
        if (token == null || token.length > 2000) return null
        val parts = token.split('.')
        if (parts.size != 2) return null
        val (payload, sig) = parts
        if (payload.isEmpty() || sig.isEmpty()) return null
        val want = sign(payload).toByteArray(Charsets.UTF_8)
        val got = sig.toByteArray(Charsets.UTF_8)
        if (!MessageDigest.isEqual(want, got)) return null
        val node = try {
            mapper.readTree(String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8))
        } catch (e: Exception) {
            return null
        }
        val u = node?.get("u")?.takeIf { it.isTextual }?.textValue() ?: return null
        val e = node.get("e")?.takeIf { it.isTextual }?.textValue() ?: return null
        val i = node.get("i")?.takeIf { it.isNumber && it.doubleValue().isFinite() }?.longValue() ?: return null
        val s = node.get("s")?.takeIf { it.isNumber && it.doubleValue().isFinite() }?.longValue() ?: return null
        if (now - s > IDLE_MS || now - i > MAX_MS || s > now + 60_000) return null
        return Session(u, e, i, s)
    }

    private fun cookieValue(req: HttpServletRequest): String? {
        val raw = req.getHeader("Cookie") ?: return null
        for (part in raw.split(';')) {
            val i = part.indexOf('=')
            if (i > 0 && part.substring(0, i).trim() == COOKIE) return part.substring(i + 1).trim()
        }
        return null
    }

    private fun setCookie(res: HttpServletResponse?, value: String, maxAgeSec: Long) {
        res?.setHeader("Set-Cookie", "$COOKIE=$value; Path=/; Max-Age=$maxAgeSec; HttpOnly; Secure; SameSite=Strict")
    }

    fun start(res: HttpServletResponse?, uid: String, email: String, now: Long = System.currentTimeMillis()) {
        setCookie(res, encode(Session(uid, email, now, now)), MAX_MS / 1000)
    }

    fun end(res: HttpServletResponse?) = setCookie(res, "", 0)

    fun present(req: HttpServletRequest): Boolean = cookieValue(req) != null

    /** null when there is no cookie; Error("csrf"|"expired") when the cookie is unusable */
    fun user(req: HttpServletRequest, res: HttpServletResponse?, now: Long = System.currentTimeMillis()): Result? {
        val token = cookieValue(req) ?: return null
        if (req.getHeader(CSRF_HEADER) != "1") return Result.Error("csrf")
        val s = decode(token, now)
        if (s == null) {
            end(res)
            return Result.Error("expired")
        }
        if (now - s.s > REFRESH_MS) setCookie(res, encode(s.copy(s = now)), MAX_MS / 1000)
        return Result.User(AuthUser(uid = s.u, email = s.e, emailVerified = true, webSession = true))
    }

    companion object {
        const val COOKIE = "__Host-lf_admin"
        const val IDLE_MS = 2L * 3600 * 1000
        const val MAX_MS = 12L * 3600 * 1000
        const val REFRESH_MS = 60L * 1000
        const val CSRF_HEADER = "x-lifolo-admin"
    }
}
