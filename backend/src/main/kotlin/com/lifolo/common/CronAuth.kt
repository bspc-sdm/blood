package com.lifolo.common

import jakarta.servlet.http.HttpServletRequest
import java.security.MessageDigest

/**
 * The ops endpoints under /api/cron/ (`Authorization: Bearer $CRON_SECRET`). No secret configured → nobody gets in.
 * Compared in constant time. nginx does not route /api/cron/ from outside at all (deploy-backend.sh); the callers
 * (r2-migrate, sentry-test) use 127.0.0.1:8080 on the VPS.
 */
object CronAuth {
    fun authorized(req: HttpServletRequest, secret: String): Boolean {
        if (secret.isBlank()) return false
        val got = req.getHeader("Authorization") ?: return false
        return MessageDigest.isEqual(got.toByteArray(Charsets.UTF_8), "Bearer $secret".toByteArray(Charsets.UTF_8))
    }
}
