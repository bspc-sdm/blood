package com.lifolo.auth

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * `verifyIdToken(token, checkRevoked = true)` without a Firebase call on every request.
 *
 * The Admin SDK's revocation check is a `getUser` round trip per request; under load (~800 req/s) Google throttles
 * those and every logged-in user got 401 "session expired" for minutes. Here:
 *  - the token is verified locally every time (signature against Google's cached public keys, expiry, audience);
 *  - the account state — disabled, tokens valid after (revocation) — is fetched at most once per uid every [ttlMs]
 *    and then checked exactly like the SDK does (disabled → USER_DISABLED, `iat` before tokensValidAfter → TOKEN_REVOKED);
 *  - this server's own "sign out everywhere" and disable/enable/delete ([revokeRefreshTokens], [updateUser],
 *    [deleteUser]) drop the uid's entry at once, so they take effect on the next request; changes made elsewhere
 *    (Firebase console) take effect within [ttlMs];
 *  - when Firebase itself fails (keys not fetched, network, timeout, throttling) the caller gets
 *    [IdentityException.Kind.UNAVAILABLE] (→ 503, retryable), never "invalid token"; a recent state is reused for up to
 *    [staleMs] meanwhile so a short outage does not log anyone out.
 *
 * In-memory (one app instance). With several instances each keeps its own entries; the TTL bounds the difference.
 */
class RevocationCachingIdentityProvider(
    private val delegate: IdentityProvider,
    private val ttlMs: Long = TTL_MS,
    private val staleMs: Long = STALE_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) : IdentityProvider by delegate {
    private val log = LoggerFactory.getLogger(javaClass)

    /** the account state as the revocation check needs it; [exists] = false for a deleted account */
    private class State(val exists: Boolean, val disabled: Boolean, val validAfterMs: Long?, val at: Long)

    private val states = ConcurrentHashMap<String, State>()

    override fun verifyIdToken(token: String, checkRevoked: Boolean): DecodedToken {
        val t = delegate.verifyIdToken(token, false)
        if (!checkRevoked) return t
        val s = state(t.uid)
        if (!s.exists) throw IdentityException(IdentityException.Kind.USER_NOT_FOUND, "user not found")
        if (s.disabled) throw IdentityException(IdentityException.Kind.USER_DISABLED, "user disabled")
        val iatMs = t.issuedAtSec?.let { it * 1000 }
        if (s.validAfterMs != null && iatMs != null && iatMs < s.validAfterMs) {
            throw IdentityException(IdentityException.Kind.TOKEN_REVOKED, "token revoked")
        }
        return t
    }

    private fun state(uid: String): State {
        val now = clock()
        val cached = states[uid]
        if (cached != null && now - cached.at < ttlMs) return cached
        val fresh = try {
            val a = delegate.getUser(uid)
            State(true, a.disabled, a.tokensValidAfterMs, now)
        } catch (e: IdentityException) {
            when (e.kind) {
                IdentityException.Kind.USER_NOT_FOUND -> State(false, false, null, now)
                else -> return stale(uid, cached, now, e)
            }
        } catch (e: Exception) {
            return stale(uid, cached, now, e)
        }
        if (states.size >= MAX_ENTRIES) states.entries.removeIf { now - it.value.at >= ttlMs }
        if (states.size >= MAX_ENTRIES) states.clear()
        states[uid] = fresh
        return fresh
    }

    /** Firebase could not answer: a state seen within [staleMs] still counts; otherwise 503 (not the user's fault) */
    private fun stale(uid: String, cached: State?, now: Long, e: Exception): State {
        if (cached != null && now - cached.at < staleMs) {
            log.warn("Firebase account check for {} failed ({}), using the state from {} s ago", uid, e.message, (now - cached.at) / 1000)
            return cached
        }
        log.error("Firebase account check for {} failed: {}", uid, e.message)
        throw IdentityException(IdentityException.Kind.UNAVAILABLE, "account check unavailable: ${e.message}", e)
    }

    /** forget a uid's state: the next request asks Firebase again */
    fun invalidate(uid: String) {
        states.remove(uid)
    }

    override fun revokeRefreshTokens(uid: String) {
        try {
            delegate.revokeRefreshTokens(uid)
        } finally {
            invalidate(uid)
        }
    }

    override fun updateUser(uid: String, email: String?, emailVerified: Boolean?, disabled: Boolean?) {
        try {
            delegate.updateUser(uid, email, emailVerified, disabled)
        } finally {
            invalidate(uid)
        }
    }

    override fun deleteUser(uid: String) {
        try {
            delegate.deleteUser(uid)
        } finally {
            invalidate(uid)
        }
    }

    companion object {
        /** how long an account state is trusted (disable/revoke from outside this server takes effect within this) */
        const val TTL_MS = 90_000L

        /** while Firebase is failing, a state this recent still lets the user in */
        const val STALE_MS = 15 * 60_000L

        const val MAX_ENTRIES = 200_000
    }
}
