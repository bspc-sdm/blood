package com.lifolo.auth

/**
 * Who is calling (Node: the decoded Firebase token, the web-session user, the guest user, or — for caregivers —
 * the patient they act for).
 *  - [uid] is null only for guests.
 *  - [actingBy] is the caregiver's uid when a caregiver acts for the patient [uid] (careaccess.js actAs).
 */
data class AuthUser(
    val uid: String?,
    val email: String = "",
    val emailVerified: Boolean = false,
    val webSession: Boolean = false,
    val guest: Boolean = false,
    val actingBy: String? = null,
) {
    /** uid for handlers that never run for guests */
    val id: String get() = uid ?: error("guest has no uid")

    companion object {
        val GUEST = AuthUser(uid = null, guest = true)
    }
}
