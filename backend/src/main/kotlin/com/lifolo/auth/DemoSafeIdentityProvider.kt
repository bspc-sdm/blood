package com.lifolo.auth

import com.lifolo.demo.DemoWorld
import org.slf4j.LoggerFactory

/**
 * DEMO_KEEP_FIREBASE_USERS=true (the VPS while the Vercel/Node server is still live): both servers share one Firebase
 * Auth project and the same demo uids (`demo-…`). The Node demo still logs in with those accounts, so this server must
 * never delete, change or sign out a demo account — "ডেমো মুছুন" then only removes this server's own rows, and
 * "ডেমো বানান" reuses the accounts that exist (creating one only when it is missing).
 *
 * Real (non-demo) accounts are passed through unchanged. Turn the flag off after the cutover, when Node is gone.
 */
class DemoSafeIdentityProvider(private val delegate: IdentityProvider) : IdentityProvider by delegate {
    private val log = LoggerFactory.getLogger(javaClass)

    private fun kept(uid: String, what: String): Boolean {
        if (!DemoWorld.isDemoUid(uid)) return false
        log.info("DEMO_KEEP_FIREBASE_USERS: {} of Firebase user {} skipped", what, uid)
        return true
    }

    override fun deleteUser(uid: String) {
        if (kept(uid, "delete")) return
        delegate.deleteUser(uid)
    }

    override fun updateUser(uid: String, email: String?, emailVerified: Boolean?, disabled: Boolean?) {
        if (kept(uid, "update")) return
        delegate.updateUser(uid, email, emailVerified, disabled)
    }

    override fun revokeRefreshTokens(uid: String) {
        if (kept(uid, "revoke")) return
        delegate.revokeRefreshTokens(uid)
    }
}
