package com.lifolo.support

import com.lifolo.auth.AuthAccount
import com.lifolo.auth.DecodedToken
import com.lifolo.auth.IdentityException
import com.lifolo.auth.IdentityProvider
import com.lifolo.notify.DeliveryResult
import com.lifolo.notify.DeviceMessage
import com.lifolo.notify.MailSender
import com.lifolo.notify.PushTransport
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class KPostgres : PostgreSQLContainer<KPostgres>(DockerImageName.parse("postgres:16-alpine"))

class KRedis : GenericContainer<KRedis>(DockerImageName.parse("redis:7-alpine")) {
    init {
        withExposedPorts(6379)
    }
}

/**
 * Shared test infrastructure: PostgreSQL 16 + Redis 7 containers (Spring Boot @ServiceConnection),
 * and fakes for Firebase Auth, SES and FCM — like server/test/support/fakefb.js.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestInfra {
    @Bean
    @ServiceConnection
    fun postgresContainer(): KPostgres = KPostgres()

    @Bean
    @ServiceConnection(name = "redis")
    fun redisContainer(): KRedis = KRedis()

    @Bean
    @Primary
    fun fakeIdentity(): FakeIdentityProvider = FakeIdentityProvider()

    @Bean
    @Primary
    fun fakeMail(): FakeMailSender = FakeMailSender()

    @Bean
    @Primary
    fun fakePush(): RecordingPushTransport = RecordingPushTransport()
}

/**
 * Firebase Auth fake. A token is either the uid itself (like the Node fake) or a JWT-shaped string whose
 * payload `sub` is the uid (to test the disabled/revoked messages, which read the uid from the token).
 */
class FakeIdentityProvider : IdentityProvider {
    data class User(
        val uid: String,
        var email: String?,
        var emailVerified: Boolean = true,
        var disabled: Boolean = false,
        var revoked: Boolean = false,
        var displayName: String? = null,
        var createdAt: Long = System.currentTimeMillis(),
        var lastSeenAt: Long? = null,
    )

    val users = ConcurrentHashMap<String, User>()
    private val ids = AtomicInteger()

    /** Firebase down / throttling: every token check fails with UNAVAILABLE */
    @Volatile
    var unavailable = false

    fun reset() {
        users.clear()
        unavailable = false
    }

    fun add(uid: String, email: String? = "$uid@example.com", emailVerified: Boolean = true): User =
        User(uid, email, emailVerified).also { users[uid] = it }

    /** a JWT-shaped token carrying the uid in `sub` */
    fun jwtFor(uid: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        return "${enc.encodeToString("{\"alg\":\"none\"}".toByteArray())}.${enc.encodeToString("{\"sub\":\"$uid\"}".toByteArray())}.sig"
    }

    private fun uidOf(token: String): String {
        val parts = token.split('.')
        if (parts.size == 3) {
            val json = String(Base64.getUrlDecoder().decode(parts[1]))
            return Regex("\"sub\":\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: token
        }
        return token
    }

    override fun verifyIdToken(token: String, checkRevoked: Boolean): DecodedToken {
        if (unavailable) throw IdentityException(IdentityException.Kind.UNAVAILABLE, "firebase down")
        val u = users[uidOf(token)] ?: throw IdentityException(IdentityException.Kind.INVALID_TOKEN, "bad token")
        if (u.disabled) throw IdentityException(IdentityException.Kind.USER_DISABLED)
        if (checkRevoked && u.revoked) throw IdentityException(IdentityException.Kind.TOKEN_REVOKED)
        return DecodedToken(u.uid, u.email, u.emailVerified)
    }

    private fun User.account() = AuthAccount(uid, email, emailVerified, disabled, createdAt, lastSeenAt)

    override fun getUser(uid: String): AuthAccount =
        users[uid]?.account() ?: throw IdentityException(IdentityException.Kind.USER_NOT_FOUND)

    override fun getUserByEmail(email: String): AuthAccount =
        users.values.firstOrNull { it.email == email }?.account() ?: throw IdentityException(IdentityException.Kind.USER_NOT_FOUND)

    override fun createUser(email: String?, emailVerified: Boolean, uid: String?, displayName: String?): AuthAccount {
        val id = uid ?: "u" + ids.incrementAndGet().toString().padStart(8, '0')
        if (users.containsKey(id)) throw IdentityException(IdentityException.Kind.UID_EXISTS)
        if (email != null && users.values.any { it.email == email }) throw IdentityException(IdentityException.Kind.EMAIL_EXISTS)
        val u = User(id, email, emailVerified, displayName = displayName)
        users[id] = u
        return u.account()
    }

    override fun updateUser(uid: String, email: String?, emailVerified: Boolean?, disabled: Boolean?) {
        val u = users[uid] ?: throw IdentityException(IdentityException.Kind.USER_NOT_FOUND)
        if (email != null) u.email = email
        if (emailVerified != null) u.emailVerified = emailVerified
        if (disabled != null) u.disabled = disabled
    }

    val revokedCalls = CopyOnWriteArrayList<String>()

    override fun revokeRefreshTokens(uid: String) {
        revokedCalls.add(uid)
    }

    override fun createCustomToken(uid: String): String = "ctok_$uid"

    override fun deleteUser(uid: String) {
        users.remove(uid)
    }

    override fun listUsers(pageToken: String?): com.lifolo.auth.AuthUserPage =
        com.lifolo.auth.AuthUserPage(users.values.sortedBy { it.uid }.map { it.account() }, null)
}

class FakeMailSender : MailSender {
    data class Mail(val to: String, val subject: String, val text: String)

    val sent = CopyOnWriteArrayList<Mail>()

    @Volatile
    var fail = false

    override fun send(to: String, subject: String, text: String, html: String) {
        if (fail) throw IllegalStateException("smtp down")
        sent.add(Mail(to, subject, text))
    }

    /** the 6-digit code of the last mail to [to] */
    fun lastCode(to: String): String =
        Regex("(\\d{6})").find(sent.last { it.to == to && Regex("\\d{6}").containsMatchIn(it.subject) }.subject)!!.groupValues[1]
}

class RecordingPushTransport : PushTransport {
    val messages = CopyOnWriteArrayList<DeviceMessage>()
    val dead = ConcurrentHashMap.newKeySet<String>()

    override fun send(messages: List<DeviceMessage>): List<DeliveryResult> {
        this.messages.addAll(messages)
        return messages.map { if (it.token in dead) DeliveryResult.DEAD_TOKEN else DeliveryResult.OK }
    }
}
