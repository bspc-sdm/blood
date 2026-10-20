package com.lifolo.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.lifolo.common.Ids
import com.lifolo.common.toHex
import com.lifolo.common.LifoloProperties
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Email login codes (server/lib/email-code.js): 6 digits, only an HMAC is stored, 10 minutes ([CODE_TTL_MS]),
 * at most 3 live codes, 5 wrong tries per code, 15 sends and 10 failures per email per day.
 */
@Component
class EmailCodes(private val props: LifoloProperties, private val mapper: ObjectMapper) {
    private val random = SecureRandom()

    /** HMAC key: EMAIL_CODE_SECRET, else derived from the service account's private_key (Node behaviour) */
    private val hmacKey: ByteArray by lazy {
        val secret = props.emailCodeSecret.ifBlank {
            val raw = props.firebaseServiceAccount
            try {
                val json = FirebaseConfig.serviceAccountJson(raw)
                mapper.readTree(json).get("private_key")?.textValue().orEmpty()
            } catch (e: Exception) {
                ""
            }
        }
        check(secret.isNotEmpty()) { "কোড হ্যাশ করার চাবি পাওয়া যায়নি" }
        MessageDigest.getInstance("SHA-256").digest("email-code-v1:$secret".toByteArray(Charsets.UTF_8))
    }

    fun newCode(): String = random.nextInt(1_000_000).toString().padStart(6, '0')

    fun hashCode(email: String, code: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hmacKey, "HmacSHA256"))
        return mac.doFinal("$email:$code".toByteArray(Charsets.UTF_8)).toHex()
    }

    companion object {
        const val CODE_TTL_MS = 10L * 60 * 1000
        const val MAX_ATTEMPTS = 5
        const val RESEND_GAP_MS = 60L * 1000
        const val MAX_SENDS_PER_DAY = 15
        const val MAX_FAILS_PER_DAY = 10
        const val MAX_LIVE_CODES = 3

        private val EMAIL_RE = Regex("^[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,24}$")

        /** plain ASCII addresses only, lower-cased; null when invalid */
        fun normalizeEmail(raw: String?): String? {
            val email = (raw ?: "").trim().lowercase()
            return if (EMAIL_RE.matches(email) && email.length <= 254) email else null
        }

        /** document key: sha256("email:" + email) — the address itself is never stored in this table */
        fun docIdFor(email: String): String = Ids.sha256Hex("email:$email")

        fun codesMatch(a: String, b: String): Boolean {
            val x = hexToBytes(a) ?: return false
            val y = hexToBytes(b) ?: return false
            return x.size == y.size && MessageDigest.isEqual(x, y)
        }

        private fun hexToBytes(h: String): ByteArray? {
            if (h.length % 2 != 0) return null
            return try {
                ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            } catch (e: NumberFormatException) {
                null
            }
        }
    }
}
