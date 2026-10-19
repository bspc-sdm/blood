package com.lifolo.auth

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.AuthErrorCode
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.UserRecord
import com.google.firebase.messaging.FirebaseMessaging
import com.lifolo.common.LifoloProperties
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.ByteArrayInputStream
import java.util.Base64

/**
 * Firebase stays for Auth (ID-token verification, custom tokens) and FCM only.
 * FIREBASE_SERVICE_ACCOUNT holds the service-account JSON, raw or base64 (same as Node).
 * When it is missing (local dev) or `lifolo.firebase.enabled=false` (tests), an unconfigured provider is used
 * that rejects every token.
 */
@Configuration
class FirebaseConfig(private val props: LifoloProperties) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun firebaseApp(): FirebaseHolder {
        if (!props.firebase.enabled || props.firebaseServiceAccount.isBlank()) {
            log.warn("Firebase is not configured (FIREBASE_SERVICE_ACCOUNT empty or disabled) — logins will fail")
            return FirebaseHolder(null)
        }
        val json = serviceAccountJson(props.firebaseServiceAccount)
        val options = FirebaseOptions.builder()
            .setCredentials(GoogleCredentials.fromStream(ByteArrayInputStream(json.toByteArray(Charsets.UTF_8))))
            .build()
        val app = FirebaseApp.getApps().firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME } ?: FirebaseApp.initializeApp(options)
        return FirebaseHolder(app)
    }

    @Bean
    fun identityProvider(holder: FirebaseHolder): IdentityProvider =
        holder.app?.let { FirebaseIdentityProvider(FirebaseAuth.getInstance(it)) } ?: UnconfiguredIdentityProvider()

    companion object {
        fun serviceAccountJson(raw: String): String =
            if (raw.trim().startsWith("{")) raw else String(Base64.getDecoder().decode(raw.trim()), Charsets.UTF_8)
    }
}

/** Optional FirebaseApp (null when not configured). */
class FirebaseHolder(val app: FirebaseApp?) {
    fun messaging(): FirebaseMessaging? = app?.let { FirebaseMessaging.getInstance(it) }
}

class FirebaseIdentityProvider(private val auth: FirebaseAuth) : IdentityProvider {
    private fun <T> call(block: () -> T): T = try {
        block()
    } catch (e: FirebaseAuthException) {
        throw IdentityException(map(e.authErrorCode), e.message, e)
    } catch (e: IllegalArgumentException) {
        throw IdentityException(IdentityException.Kind.INVALID_TOKEN, e.message, e)
    }

    private fun map(code: AuthErrorCode?): IdentityException.Kind = when (code) {
        AuthErrorCode.USER_NOT_FOUND -> IdentityException.Kind.USER_NOT_FOUND
        AuthErrorCode.USER_DISABLED -> IdentityException.Kind.USER_DISABLED
        AuthErrorCode.REVOKED_ID_TOKEN -> IdentityException.Kind.TOKEN_REVOKED
        AuthErrorCode.EMAIL_ALREADY_EXISTS -> IdentityException.Kind.EMAIL_EXISTS
        AuthErrorCode.UID_ALREADY_EXISTS -> IdentityException.Kind.UID_EXISTS
        AuthErrorCode.EXPIRED_ID_TOKEN, AuthErrorCode.INVALID_ID_TOKEN -> IdentityException.Kind.INVALID_TOKEN
        else -> IdentityException.Kind.OTHER
    }

    private fun UserRecord.toAccount() = AuthAccount(uid, email, isEmailVerified, isDisabled)

    override fun verifyIdToken(token: String, checkRevoked: Boolean): DecodedToken = call {
        val t = auth.verifyIdToken(token, checkRevoked)
        DecodedToken(t.uid, t.email, t.isEmailVerified)
    }

    override fun getUser(uid: String): AuthAccount = call { auth.getUser(uid).toAccount() }

    override fun getUserByEmail(email: String): AuthAccount = call { auth.getUserByEmail(email).toAccount() }

    override fun createUser(email: String?, emailVerified: Boolean, uid: String?, displayName: String?): AuthAccount = call {
        val req = UserRecord.CreateRequest().setEmailVerified(emailVerified)
        if (email != null) req.setEmail(email)
        if (uid != null) req.setUid(uid)
        if (displayName != null) req.setDisplayName(displayName)
        auth.createUser(req).toAccount()
    }

    override fun updateUser(uid: String, email: String?, emailVerified: Boolean?, disabled: Boolean?) {
        call {
            val req = UserRecord.UpdateRequest(uid)
            if (email != null) req.setEmail(email)
            if (emailVerified != null) req.setEmailVerified(emailVerified)
            if (disabled != null) req.setDisabled(disabled)
            auth.updateUser(req)
        }
    }

    override fun revokeRefreshTokens(uid: String) = call { auth.revokeRefreshTokens(uid) }

    override fun createCustomToken(uid: String): String = call { auth.createCustomToken(uid) }

    override fun deleteUser(uid: String) = call { auth.deleteUser(uid) }
}

/** Used when Firebase is not configured: every token is invalid, every admin call fails. */
class UnconfiguredIdentityProvider : IdentityProvider {
    private fun fail(): Nothing = throw IdentityException(IdentityException.Kind.OTHER, "Firebase is not configured")
    override fun verifyIdToken(token: String, checkRevoked: Boolean): DecodedToken =
        throw IdentityException(IdentityException.Kind.INVALID_TOKEN, "Firebase is not configured")
    override fun getUser(uid: String): AuthAccount = fail()
    override fun getUserByEmail(email: String): AuthAccount = fail()
    override fun createUser(email: String?, emailVerified: Boolean, uid: String?, displayName: String?): AuthAccount = fail()
    override fun updateUser(uid: String, email: String?, emailVerified: Boolean?, disabled: Boolean?) = fail()
    override fun revokeRefreshTokens(uid: String) = fail()
    override fun createCustomToken(uid: String): String = fail()
    override fun deleteUser(uid: String) = fail()
}
