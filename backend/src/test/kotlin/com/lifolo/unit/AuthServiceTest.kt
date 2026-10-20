package com.lifolo.unit

import com.fasterxml.jackson.databind.ObjectMapper
import com.lifolo.auth.AuthAccount
import com.lifolo.auth.AuthFailure
import com.lifolo.auth.AuthService
import com.lifolo.auth.DecodedToken
import com.lifolo.auth.FirebaseTokenFilter
import com.lifolo.auth.IdentityException
import com.lifolo.auth.IdentityProvider
import com.lifolo.common.ApiException
import com.lifolo.users.AccountService
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import java.time.Instant
import java.util.Base64

/** firebase.js decodeToken / verifyUser behaviour with a mocked Firebase (MockK). */
class AuthServiceTest {
    private val identity = mockk<IdentityProvider>()
    private val accounts = mockk<AccountService>()
    private val auth = AuthService(identity, accounts, ObjectMapper())

    private fun jwt(uid: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        return "${enc.encodeToString("{}".toByteArray())}.${enc.encodeToString("{\"sub\":\"$uid\"}".toByteArray())}.x"
    }

    private fun req(token: String?) = MockHttpServletRequest().apply { if (token != null) addHeader("Authorization", "Bearer $token") }

    @Test
    fun `valid token registers the account and returns the user`() {
        every { identity.verifyIdToken("t1", true) } returns DecodedToken("u1", "A@B.com", true)
        justRun { accounts.ensure("u1", "A@B.com", true) }
        val u = auth.decodeUser(req("t1"), "bn")
        assertEquals("u1", u.uid)
        assertEquals("A@B.com", u.email)
        verify(exactly = 1) { accounts.ensure("u1", "A@B.com", true) }
    }

    @Test
    fun `the filter's result is reused (no second Firebase call)`() {
        val r = req("t2")
        r.setAttribute(FirebaseTokenFilter.ATTR_TOKEN, DecodedToken("u2", null, false))
        justRun { accounts.ensure("u2", null, false) }
        assertEquals("u2", auth.decodeUser(r, null).uid)
        verify(exactly = 0) { identity.verifyIdToken(any(), any()) }
    }

    @Test
    fun `missing token`() {
        val e = assertThrows(ApiException::class.java) { auth.decodeUser(req(null), "bn") }
        assertEquals(401, e.status)
        assertEquals("লগইন দরকার", e.message)
        assertEquals("Please log in", assertThrows(ApiException::class.java) { auth.decodeUser(req(null), null) }.message)
        // legacy endpoints always say it in Bangla (translated later by the error handler)
        assertEquals("লগইন দরকার", assertThrows(ApiException::class.java) { auth.verifyUser(req(null), "en") }.message)
    }

    @Test
    fun `disabled account - reason and appeal address`() {
        val token = jwt("dis")
        val r = req(token)
        r.setAttribute(FirebaseTokenFilter.ATTR_FAILURE, AuthFailure(IdentityException.Kind.USER_DISABLED))
        every { identity.getUser("dis") } returns AuthAccount("dis", "d@x.com", true, disabled = true)
        every { accounts.disabledReason("dis") } returns ""
        val e = assertThrows(ApiException::class.java) { auth.decodeUser(r, "en") }
        assertEquals(403, e.status)
        assertEquals("account_disabled", e.code)
        assertEquals("Your account was suspended by the Lifolo team. To appeal, email ekapkbd@gmail.com", e.message)
    }

    @Test
    fun `revoked because of a pending deletion - the date`() {
        val r = req(jwt("del"))
        r.setAttribute(FirebaseTokenFilter.ATTR_FAILURE, AuthFailure(IdentityException.Kind.TOKEN_REVOKED))
        every { identity.getUser("del") } returns AuthAccount("del", "d@x.com", true, disabled = false)
        every { accounts.deleteAt("del") } returns Instant.ofEpochMilli(1795000000000)
        val e = assertThrows(ApiException::class.java) { auth.decodeUser(r, "bn") }
        assertEquals(403, e.status)
        assertEquals("আপনার অ্যাকাউন্ট ১৮ নভেম্বর, ২০২৬ মুছে যাবে। এর আগে আবার লগইন করলে মোছা বাতিল হবে।", e.message)
    }

    @Test
    fun `revoked without deletion, or garbage - session expired`() {
        val r = req(jwt("rev"))
        r.setAttribute(FirebaseTokenFilter.ATTR_FAILURE, AuthFailure(IdentityException.Kind.TOKEN_REVOKED))
        every { identity.getUser("rev") } returns AuthAccount("rev", null, true, disabled = false)
        every { accounts.deleteAt("rev") } returns null
        assertEquals("Session expired, please log in again", assertThrows(ApiException::class.java) { auth.decodeUser(r, "en") }.message)

        every { identity.verifyIdToken("junk", true) } throws IdentityException(IdentityException.Kind.INVALID_TOKEN)
        val e = assertThrows(ApiException::class.java) { auth.decodeUser(req("junk"), "bn") }
        assertEquals(401, e.status)
        assertEquals("লগইনের মেয়াদ শেষ, আবার লগইন করুন", e.message)
    }
}
