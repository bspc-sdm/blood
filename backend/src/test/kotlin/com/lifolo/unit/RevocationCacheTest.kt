package com.lifolo.unit

import com.lifolo.auth.AuthAccount
import com.lifolo.auth.DecodedToken
import com.lifolo.auth.IdentityException
import com.lifolo.auth.IdentityProvider
import com.lifolo.auth.RevocationCachingIdentityProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** the revocation check without a Firebase round trip per request (load test: ~800 req/s gave 401s for minutes) */
class RevocationCacheTest {
    /** a Firebase stand-in: tokens "uid@iatSeconds", account states by uid, counts getUser calls */
    private inner class Stub : IdentityProvider {
        val accounts = HashMap<String, AuthAccount>()
        var getUserCalls = 0
        var down = false
        var verifyChecked = mutableListOf<Boolean>()

        override fun verifyIdToken(token: String, checkRevoked: Boolean): DecodedToken {
            verifyChecked += checkRevoked
            val (uid, iat) = token.split('@')
            return DecodedToken(uid, "$uid@x.com", true, iat.toLong())
        }

        override fun getUser(uid: String): AuthAccount {
            getUserCalls++
            if (down) throw IdentityException(IdentityException.Kind.UNAVAILABLE, "quota exceeded")
            return accounts[uid] ?: throw IdentityException(IdentityException.Kind.USER_NOT_FOUND)
        }

        override fun getUserByEmail(email: String): AuthAccount = throw UnsupportedOperationException()
        override fun createUser(email: String?, emailVerified: Boolean, uid: String?, displayName: String?): AuthAccount = throw UnsupportedOperationException()
        override fun updateUser(uid: String, email: String?, emailVerified: Boolean?, disabled: Boolean?) {
            if (disabled != null) accounts[uid] = accounts.getValue(uid).copy(disabled = disabled)
        }

        override fun revokeRefreshTokens(uid: String) {
            accounts[uid] = accounts.getValue(uid).copy(tokensValidAfterMs = now)
        }

        override fun createCustomToken(uid: String): String = "c"
        override fun deleteUser(uid: String) {
            accounts.remove(uid)
        }
    }

    private var now = 1_000_000_000_000L

    private fun kind(block: () -> Unit): IdentityException.Kind = assertThrows(IdentityException::class.java) { block() }.kind

    @Test
    fun `one Firebase account lookup per uid per TTL; tokens are still checked locally every time`() {
        val f = Stub().apply { accounts["u1"] = AuthAccount("u1", "u1@x.com", true, false) }
        val p = RevocationCachingIdentityProvider(f, ttlMs = 90_000, clock = { now })
        repeat(500) { assertEquals("u1", p.verifyIdToken("u1@${now / 1000}", true).uid) }
        assertEquals(1, f.getUserCalls)
        assertEquals(500, f.verifyChecked.size)
        assertEquals(setOf(false), f.verifyChecked.toSet(), "the SDK's per-request revocation round trip is never used")
        now += 91_000
        p.verifyIdToken("u1@${now / 1000}", true)
        assertEquals(2, f.getUserCalls)
        // checkRevoked = false: no account lookup at all
        p.verifyIdToken("u1@1", false)
        assertEquals(2, f.getUserCalls)
    }

    @Test
    fun `sign-out-everywhere, disable and delete from this server take effect on the next request`() {
        val f = Stub().apply {
            accounts["u2"] = AuthAccount("u2", "u2@x.com", true, false)
            accounts["u3"] = AuthAccount("u3", "u3@x.com", true, false)
        }
        val p = RevocationCachingIdentityProvider(f, ttlMs = 90_000, clock = { now })
        val old = "u2@${now / 1000 - 10}"
        p.verifyIdToken(old, true)
        now += 1_000
        p.revokeRefreshTokens("u2")
        assertEquals(IdentityException.Kind.TOKEN_REVOKED, kind { p.verifyIdToken(old, true) })
        // a token issued after the revocation works
        now += 2_000
        p.verifyIdToken("u2@${now / 1000}", true)
        // disable / enable
        p.updateUser("u2", disabled = true)
        assertEquals(IdentityException.Kind.USER_DISABLED, kind { p.verifyIdToken("u2@${now / 1000}", true) })
        p.updateUser("u2", disabled = false)
        p.verifyIdToken("u2@${now / 1000}", true)
        // deleted
        p.verifyIdToken("u3@${now / 1000}", true)
        p.deleteUser("u3")
        assertEquals(IdentityException.Kind.USER_NOT_FOUND, kind { p.verifyIdToken("u3@${now / 1000}", true) })
    }

    @Test
    fun `changes made elsewhere count within the TTL`() {
        val f = Stub().apply { accounts["u4"] = AuthAccount("u4", "u4@x.com", true, false) }
        val p = RevocationCachingIdentityProvider(f, ttlMs = 90_000, clock = { now })
        p.verifyIdToken("u4@${now / 1000}", true)
        f.accounts["u4"] = f.accounts.getValue("u4").copy(disabled = true) // Firebase console
        p.verifyIdToken("u4@${now / 1000}", true)
        now += 90_000
        assertEquals(IdentityException.Kind.USER_DISABLED, kind { p.verifyIdToken("u4@${now / 1000}", true) })
    }

    @Test
    fun `Firebase failing - UNAVAILABLE (retryable), a recent state still lets the user in`() {
        val f = Stub().apply {
            accounts["u5"] = AuthAccount("u5", "u5@x.com", true, false)
            accounts["u6"] = AuthAccount("u6", "u6@x.com", true, false)
        }
        val p = RevocationCachingIdentityProvider(f, ttlMs = 90_000, staleMs = 15 * 60_000, clock = { now })
        p.verifyIdToken("u5@${now / 1000}", true)
        f.down = true
        now += 120_000
        // seen 2 minutes ago: still in
        assertEquals("u5", p.verifyIdToken("u5@${now / 1000}", true).uid)
        // never seen: 503-type failure, not "invalid token"
        assertEquals(IdentityException.Kind.UNAVAILABLE, kind { p.verifyIdToken("u6@${now / 1000}", true) })
        // too old a state: no longer trusted
        now += 15 * 60_000
        assertEquals(IdentityException.Kind.UNAVAILABLE, kind { p.verifyIdToken("u5@${now / 1000}", true) })
        f.down = false
        p.verifyIdToken("u5@${now / 1000}", true)
    }
}
