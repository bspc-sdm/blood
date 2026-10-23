package com.lifolo.desktop

import com.lifolo.common.LifoloProperties
import com.lifolo.common.action.ActionContext
import com.lifolo.common.toHex
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The desktop's request signature (I5): `X-Lifolo-Device-Sig: t=<unix seconds>,v1=<hex HMAC-SHA256(key, "<t>.<deviceId>.<raw body>")>`,
 * key = the UTF-8 bytes of the `secret` desktop_register returned. Required on offline_sync and offline_bootstrap.
 *
 * The secret is never stored: it is HMAC(server key, deviceId + a random per-device salt) — the table keeps only the salt
 * (removed on revoke), so a copy of the database alone cannot sign. Server key = WEB_SESSION_SECRET (else the service
 * account), like the admin web session; changing it means every desk registers again.
 */
@Component
class DeviceSig(private val props: LifoloProperties) {
    companion object {
        const val HEADER = "X-Lifolo-Device-Sig"
        const val RAW_ATTR = "lifolo.rawBody"

        fun hmacHex(key: ByteArray, data: ByteArray): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data).toHex()
        }

        private val HEADER_RE = Regex("^t=(\\d{1,12}),v1=([0-9a-fA-F]{64})$")
    }

    private val serverKey: ByteArray by lazy {
        val base = props.webSessionSecret.ifBlank { props.firebaseServiceAccount }
        check(base.isNotBlank()) { "desktop device key: no WEB_SESSION_SECRET / service account" }
        MessageDigest.getInstance("SHA-256").digest("lifolo-desktop-v1\n$base".toByteArray(Charsets.UTF_8))
    }

    /** the device's secret (what desktop_register shows once) */
    fun secretOf(deviceId: String, salt: String): String =
        hmacHex(serverKey, "device-secret\n$deviceId\n$salt".toByteArray(Charsets.UTF_8))

    /** 401 `bad_device_sig` unless the request carries a valid, fresh signature of [d] */
    fun check(ctx: ActionContext, d: Device, nowMs: Long) {
        val bad = ctx.err(401, "This computer's signature is not valid. Check the clock or register it again.", "এই কম্পিউটারের স্বাক্ষর ঠিক নেই। ঘড়ি দেখুন বা আবার নিবন্ধন করুন।", "bad_device_sig") // bn-ok
        val salt = d.secret ?: throw bad
        val m = HEADER_RE.matchEntire(ctx.request.getHeader(HEADER)?.trim() ?: "") ?: throw bad
        val t = m.groupValues[1].toLong()
        if (Math.abs(t * 1000 - nowMs) > DesktopRules.SIG_SKEW_MS) throw bad
        val raw = ctx.request.getAttribute(RAW_ATTR) as? ByteArray ?: throw bad
        val signed = "$t.${d.id}.".toByteArray(Charsets.UTF_8) + raw
        val want = hmacHex(secretOf(d.id, salt).toByteArray(Charsets.UTF_8), signed)
        if (!MessageDigest.isEqual(want.toByteArray(), m.groupValues[2].lowercase().toByteArray())) throw bad
    }
}

/**
 * Keeps the exact request bytes of a signed /api/community call (header [DeviceSig.HEADER]) for the signature check:
 * JSON parsing and re-serializing would not give the same bytes.
 */
@Component
class DeviceSigBodyFilter : OncePerRequestFilter() {
    private val maxBytes = 8 * 1024 * 1024

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.getHeader(DeviceSig.HEADER) == null || request.method != "POST" || !request.requestURI.startsWith("/api/community")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val bytes = request.inputStream.readNBytes(maxBytes)
        request.setAttribute(DeviceSig.RAW_ATTR, bytes)
        chain.doFilter(Cached(request, bytes), response)
    }

    private class Cached(req: HttpServletRequest, private val bytes: ByteArray) : HttpServletRequestWrapper(req) {
        override fun getInputStream(): ServletInputStream {
            val input = ByteArrayInputStream(bytes)
            return object : ServletInputStream() {
                override fun read(): Int = input.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int = input.read(b, off, len)
                override fun isFinished(): Boolean = input.available() == 0
                override fun isReady(): Boolean = true
                override fun setReadListener(listener: ReadListener?) {}
            }
        }

        override fun getReader(): BufferedReader = BufferedReader(InputStreamReader(inputStream, characterEncoding ?: "UTF-8"))

        override fun getContentLength(): Int = bytes.size

        override fun getContentLengthLong(): Long = bytes.size.toLong()
    }
}
