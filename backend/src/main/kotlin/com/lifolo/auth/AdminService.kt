package com.lifolo.auth

import com.lifolo.common.LifoloProperties
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service

/**
 * admin.js: owners come from ADMIN_EMAILS (default ekapkbd@gmail.com); extra admins live in `admin_emails`
 * (Firestore config/admins). Both require a verified email.
 */
@Service
class AdminService(private val props: LifoloProperties, private val store: AdminEmailStore) {
    fun adminEmails(): List<String> {
        val env = props.adminEmails.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        return env.ifEmpty { LifoloProperties.DEFAULT_OWNERS }
    }

    fun isOwner(u: AuthUser): Boolean = u.emailVerified && u.email.isNotEmpty() && adminEmails().contains(u.email.lowercase())

    fun isAdmin(u: AuthUser): Boolean {
        if (isOwner(u)) return true
        val email = u.email.lowercase()
        return u.emailVerified && email.isNotEmpty() && store.extraAdmins().contains(email)
    }

    fun clearCache() = store.evict()
}

/** config/admins.emails — cached 10 minutes in Redis (cache "admins"), evicted on add/remove */
@Component
class AdminEmailStore(private val jdbc: JdbcTemplate) {
    @Cacheable(cacheNames = ["admins"], key = "'extra'")
    fun extraAdmins(): HashSet<String> =
        HashSet(jdbc.queryForList("SELECT email FROM admin_emails", String::class.java))

    @CacheEvict(cacheNames = ["admins"], allEntries = true)
    fun evict() {
    }
}
