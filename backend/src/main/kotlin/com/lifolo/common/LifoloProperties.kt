package com.lifolo.common

import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * All app settings (application.yml → `lifolo.*`, filled from env vars).
 * Node equivalents: process.env.ADMIN_EMAILS, CRON_SECRET, WEB_SESSION_SECRET, FIREBASE_SERVICE_ACCOUNT,
 * SMS_URL_TEMPLATE, GMAIL_* (→ SES here).
 */
@ConfigurationProperties(prefix = "lifolo")
@Validated
data class LifoloProperties(
    /** comma-separated owner emails; empty → DEFAULT_OWNERS (admin.js DEFAULT_ADMINS) */
    val adminEmails: String = "",
    val cronSecret: String = "",
    val webSessionSecret: String = "",
    /** service-account JSON or base64(JSON) */
    val firebaseServiceAccount: String = "",
    /** HMAC secret for login codes; empty → service account private_key (Node behaviour) */
    val emailCodeSecret: String = "",
    val smsUrlTemplate: String = "",
    /** public origin used in share links / QR codes */
    val publicBaseUrl: String = "https://blood-server-ashy.vercel.app",
    @field:Min(1)
    val guestRatePerMinute: Long = 240,
    val firebase: Firebase = Firebase(),
    val push: Push = Push(),
    val ses: Ses = Ses(),
    val r2: R2 = R2(),
) {
    data class Firebase(val enabled: Boolean = true)

    /**
     * PUSH_MODE: `fcm` (default; falls back to `log` without a service account), `log` (push off: nothing is sent,
     * only logged; the bell list is still written) or `fake` (e2e only: nothing is sent, every message counts as
     * delivered — like the Node e2e stack's fake messaging).
     */
    data class Push(val mode: String = "fcm")

    data class Ses(
        val region: String = "",
        val accessKeyId: String = "",
        val secretAccessKey: String = "",
        val from: String = "",
    ) {
        val configured: Boolean get() = region.isNotBlank() && from.isNotBlank()
    }

    data class R2(
        val endpoint: String = "",
        val accountId: String = "",
        val accessKeyId: String = "",
        val secretAccessKey: String = "",
        val bucket: String = "",
    ) {
        val resolvedEndpoint: String
            get() = endpoint.ifBlank { if (accountId.isNotBlank()) "https://$accountId.r2.cloudflarestorage.com" else "" }
        val configured: Boolean
            get() = resolvedEndpoint.isNotBlank() && accessKeyId.isNotBlank() && secretAccessKey.isNotBlank() && bucket.isNotBlank()
    }

    companion object {
        val DEFAULT_OWNERS = listOf("ekapkbd@gmail.com")
        const val SUPPORT_EMAIL = "ekapkbd@gmail.com"
    }
}
