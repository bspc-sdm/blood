package com.lifolo.demo

import com.lifolo.auth.AuthService
import com.lifolo.auth.IdentityProvider
import com.lifolo.common.ApiException
import com.lifolo.users.AccountService
import com.lifolo.users.DeletionService
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

/**
 * Demo one-click login (verify-code with `{demoRoles:true}` → role list, `{demo:"<key>"}` → custom token).
 * Works only while a complete demo exists (demo_state.partial = false). Seeding/wiping is phase 2.
 */
@Service
class DemoLoginService(
    private val jdbc: JdbcTemplate,
    private val identity: IdentityProvider,
    private val auth: AuthService,
    private val accounts: AccountService,
    private val deletion: DeletionService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Node `{partial}` of the manifest, or null when there is no demo */
    private fun partial(): Boolean? =
        jdbc.queryForList("SELECT partial FROM demo_state", Boolean::class.javaObjectType).firstOrNull()

    fun roles(): Map<String, Any?> {
        val p = partial()
        if (p == null || p) return linkedMapOf("active" to false, "roles" to emptyList<Any>())
        return linkedMapOf(
            "active" to true,
            "roles" to DemoWorld.ROLES.map { linkedMapOf("key" to it.key, "name" to it.name, "role" to it.bn) },
        )
    }

    fun login(key: String): Map<String, Any?> {
        val role = DemoWorld.ROLES.firstOrNull { it.key == key }
        val exists = partial() != null
        if (role == null || !exists) throw ApiException(404, "ডেমো চালু নেই")
        val email = DemoWorld.demoEmail(role.key)
        val uid = jdbc.queryForList("SELECT uid FROM demo_accounts WHERE email = ?", String::class.java, email).firstOrNull()
        if (uid == null || !DemoWorld.isDemoUid(uid)) throw ApiException(404, "ডেমো চালু নেই")
        // like a normal login: a disabled account gets the reason (Node passes an empty body → Bangla)
        val au = runCatching { identity.getUser(uid) }.getOrNull()
        if (au != null && au.disabled) throw auth.accountDisabledError(null, uid)
        accounts.ensure(uid, email)
        val restored = try {
            deletion.cancel(uid)
        } catch (e: Exception) {
            log.error("demo cancel deletion failed", e)
            false
        }
        return linkedMapOf(
            "ok" to true, "token" to identity.createCustomToken(uid), "restored" to restored, "demo" to true,
            "account" to runCatching { accounts.state(uid) }.getOrElse { linkedMapOf("state" to "active") },
        )
    }
}
