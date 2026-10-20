package com.lifolo.unit

import com.lifolo.auth.AuthAccount
import com.lifolo.auth.DemoSafeIdentityProvider
import com.lifolo.auth.IdentityProvider
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** DEMO_KEEP_FIREBASE_USERS: demo accounts (shared with the Node demo) are never deleted, changed or signed out */
class DemoSafeIdentityTest {
    private val base = mockk<IdentityProvider>()
    private val id = DemoSafeIdentityProvider(base)

    @Test
    fun `demo accounts are left alone`() {
        id.deleteUser("demo-patient")
        id.updateUser("demo-doctor", email = "doctor@lifolo-demo.test", emailVerified = true, disabled = false)
        id.revokeRefreshTokens("demo-x-donor1")
        verify(exactly = 0) { base.deleteUser(any()) }
        verify(exactly = 0) { base.updateUser(any(), any(), any(), any()) }
        verify(exactly = 0) { base.revokeRefreshTokens(any()) }
    }

    @Test
    fun `real accounts pass through`() {
        justRun { base.deleteUser("u1") }
        justRun { base.updateUser("u1", null, null, true) }
        justRun { base.revokeRefreshTokens("u1") }
        id.deleteUser("u1")
        id.updateUser("u1", disabled = true)
        id.revokeRefreshTokens("u1")
        verify(exactly = 1) { base.deleteUser("u1") }
        verify(exactly = 1) { base.updateUser("u1", null, null, true) }
        verify(exactly = 1) { base.revokeRefreshTokens("u1") }
    }

    @Test
    fun `reads, creates and tokens are unchanged`() {
        val acc = AuthAccount("demo-patient", "patient@lifolo-demo.test", true, false)
        every { base.getUser("demo-patient") } returns acc
        every { base.createUser(null, false, "demo-x-a", "A") } returns acc
        every { base.createCustomToken("demo-patient") } returns "tok"
        assertEquals(acc, id.getUser("demo-patient"))
        assertEquals(acc, id.createUser(null, false, "demo-x-a", "A"))
        assertEquals("tok", id.createCustomToken("demo-patient"))
    }
}
