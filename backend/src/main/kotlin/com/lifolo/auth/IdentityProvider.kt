package com.lifolo.auth

/** A verified Firebase ID token. */
data class DecodedToken(
    val uid: String,
    val email: String?,
    val emailVerified: Boolean,
)

/** A Firebase Auth user record. */
data class AuthAccount(
    val uid: String,
    val email: String?,
    val emailVerified: Boolean,
    val disabled: Boolean,
    /** Auth metadata creationTime (ms), when known */
    val createdAtMs: Long? = null,
    /** max(lastRefreshTime, lastSignInTime) (ms), when known */
    val lastSeenAtMs: Long? = null,
)

/** one page of [IdentityProvider.listUsers] */
data class AuthUserPage(val users: List<AuthAccount>, val nextPageToken: String?)

class IdentityException(val kind: Kind, message: String? = null, cause: Throwable? = null) :
    RuntimeException(message ?: kind.name, cause) {
    enum class Kind { USER_NOT_FOUND, USER_DISABLED, TOKEN_REVOKED, EMAIL_EXISTS, UID_EXISTS, INVALID_TOKEN, OTHER }
}

/**
 * Everything the server needs from Firebase Auth (firebase-admin `admin.auth()` in Node).
 * Production: [FirebaseIdentityProvider]. Tests: a fake where the token is the uid (like server/test/support/fakefb.js).
 * All methods throw [IdentityException] for the error cases the Node code distinguishes.
 */
interface IdentityProvider {
    fun verifyIdToken(token: String, checkRevoked: Boolean = true): DecodedToken
    fun getUser(uid: String): AuthAccount
    fun getUserByEmail(email: String): AuthAccount
    fun createUser(email: String?, emailVerified: Boolean, uid: String? = null, displayName: String? = null): AuthAccount
    fun updateUser(uid: String, email: String? = null, emailVerified: Boolean? = null, disabled: Boolean? = null)
    fun revokeRefreshTokens(uid: String)
    fun createCustomToken(uid: String): String
    fun deleteUser(uid: String)

    /** admin.auth().listUsers(1000, pageToken) — the admin panel's user statistics (adminweb.js authStats) */
    fun listUsers(pageToken: String?): AuthUserPage =
        throw IdentityException(IdentityException.Kind.OTHER, "listUsers is not supported")
}
