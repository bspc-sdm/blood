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
    /**
     * logged-in calls of /api/community per account and IP address per minute (an app makes a few a minute; this only
     * stops scripts — the demo accounts are public, so a token costs nothing)
     */
    @field:Min(1)
    val userRatePerMinute: Long = 600,
    /** one-click demo logins (`verify-code` with `demo`) per IP address per minute */
    @field:Min(1)
    val demoLoginRatePerMinute: Long = 30,
    val firebase: Firebase = Firebase(),
    val push: Push = Push(),
    val ses: Ses = Ses(),
    val r2: R2 = R2(),
    val demo: Demo = Demo(),
) {
    /**
     * DEMO_KEEP_FIREBASE_USERS: while another server (Vercel/Node) shares the Firebase project and its demo, the demo
     * Firebase accounts (`demo-…`) are left alone (see [com.lifolo.auth.DemoSafeIdentityProvider]).
     */
    data class Demo(val keepFirebaseUsers: Boolean = false)

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
        /** R2_REGION: "auto" for R2 (default), us-east-1 for MinIO */
        val region: String = "",
        /** R2_CACHE_MB: in-memory cache of photos read from R2 (base64 text, per server process) */
        val cacheMb: Int = 64,
    ) {
        /**
         * R2_ENDPOINT (preferred) or the account's default endpoint. The Cloudflare dashboard shows the S3 URL with the
         * bucket at the end (`https://<id>.r2.cloudflarestorage.com/<bucket>`); that path is dropped (requests are
         * path-style and add the bucket themselves).
         */
        val resolvedEndpoint: String
            get() {
                val e = endpoint.trim().trimEnd('/')
                if (e.isEmpty()) return if (accountId.isNotBlank()) "https://${accountId.trim()}.r2.cloudflarestorage.com" else ""
                return if (bucket.isNotBlank() && e.endsWith("/${bucket.trim()}")) e.removeSuffix("/${bucket.trim()}") else e
            }
        val configured: Boolean
            get() = resolvedEndpoint.isNotBlank() && accessKeyId.isNotBlank() && secretAccessKey.isNotBlank() && bucket.isNotBlank()
    }

    companion object {
        val DEFAULT_OWNERS = listOf("ekapkbd@gmail.com")
        const val SUPPORT_EMAIL = "ekapkbd@gmail.com"
    }
}
