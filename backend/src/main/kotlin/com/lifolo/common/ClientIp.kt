package com.lifolo.common

import jakarta.servlet.http.HttpServletRequest

object ClientIp {
    /**
     * Proxies whose X-Forwarded-For is believed: this machine (nginx → 127.0.0.1) and the addresses in
     * LIFOLO_TRUSTED_PROXIES (comma separated, exact addresses; empty by default).
     */
    private val trusted: Set<String> = setOf("127.0.0.1", "0:0:0:0:0:0:0:1", "::1") +
        (System.getenv("LIFOLO_TRUSTED_PROXIES") ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * The real client address, for the guest / login-code / demo-login limits.
     *
     * Behind the VPS's nginx: Cloudflare's CF-Connecting-IP is turned into nginx's `$remote_addr` by the realip module
     * — only for requests that come from Cloudflare's own addresses (deploy/tune-vps.sh) — and nginx sends exactly
     * that as X-Forwarded-For (`proxy_set_header X-Forwarded-For $remote_addr`, deploy-backend.sh). The app never reads
     * CF-Connecting-IP itself (anyone reaching the origin could send one).
     *
     * X-Forwarded-For is used only when the request comes from a trusted proxy, and then its LAST entry — the one the
     * proxy itself added. (Node took the first entry; with an appending proxy config — `$proxy_add_x_forwarded_for`,
     * setup-base.sh's fallback — the first entry is whatever the client wrote, so random values gave every request
     * its own limit.) Anything else: the TCP peer address.
     */
    fun of(req: HttpServletRequest): String {
        val peer = req.remoteAddr ?: ""
        if (peer !in trusted) return peer.ifEmpty { "unknown" }
        val last = (req.getHeader("X-Forwarded-For") ?: "").split(',').map { it.trim() }.lastOrNull { it.isNotEmpty() }
        return last ?: peer.ifEmpty { "unknown" }
    }

    /** email-code.js ipKey: IPv6 → first 4 groups (one user has many addresses) */
    fun key(ip: String): String = if (ip.contains(':')) ip.split(':').take(4).joinToString(":") else ip
}
