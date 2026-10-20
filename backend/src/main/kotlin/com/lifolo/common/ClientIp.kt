package com.lifolo.common

import jakarta.servlet.http.HttpServletRequest

object ClientIp {
    /**
     * Node: `String(req.headers['x-forwarded-for'] || '').split(',')[0].trim() || 'unknown'`.
     * nginx on the VPS must set X-Forwarded-For (it overwrites any client value: `proxy_set_header X-Forwarded-For $remote_addr`).
     */
    fun of(req: HttpServletRequest): String =
        (req.getHeader("X-Forwarded-For") ?: "").split(',')[0].trim().ifEmpty { "unknown" }

    /** email-code.js ipKey: IPv6 → first 4 groups (one user has many addresses) */
    fun key(ip: String): String = if (ip.contains(':')) ip.split(':').take(4).joinToString(":") else ip
}
