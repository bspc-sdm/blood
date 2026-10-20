package com.lifolo.auth

import com.lifolo.common.ApiException
import com.lifolo.common.DhakaTime
import com.lifolo.common.Lang
import com.lifolo.common.LifoloProperties
import com.lifolo.users.AccountService
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Service
import java.util.Base64

/**
 * Login checks shared by every endpoint (Node: firebase.js decodeToken / verifyUser / accountDisabledError,
 * community.js decodeUser).
 */
@Service
class AuthService(
    private val identity: IdentityProvider,
    private val accounts: AccountService,
    private val mapper: ObjectMapper,
) {
    fun hasBearer(req: HttpServletRequest): Boolean = (req.getHeader("Authorization") ?: "").startsWith("Bearer ")

    private fun bearer(req: HttpServletRequest): String? {
        val h = req.getHeader("Authorization") ?: ""
        return if (h.startsWith("Bearer ")) h.substring(7) else null
    }

    /** community.js decodeUser: no token → 401 "Please log in" (in the request language) */
    fun decodeUser(req: HttpServletRequest, lang: String?): AuthUser {
        val token = bearer(req)
        if (token.isNullOrEmpty()) throw ApiException(401, Lang.tr(lang, "Please log in", "লগইন দরকার"))
        return decodeToken(req, token, lang)
    }

    /** firebase.js verifyUser (blood request endpoints): Bangla message, translated by the error handler */
    fun verifyUser(req: HttpServletRequest, lang: String?): AuthUser {
        val token = bearer(req)
        if (token.isNullOrEmpty()) throw ApiException(401, "লগইন দরকার")
        return decodeToken(req, token, lang)
    }

    /**
     * firebase.js decodeToken: a disabled or revoked account is caught immediately (checkRevoked);
     * disabled → 403 account_disabled with the reason; revoked because of a pending deletion → 403 with the date.
     */
    fun decodeToken(req: HttpServletRequest, token: String, lang: String?): AuthUser {
        var decoded = req.getAttribute(FirebaseTokenFilter.ATTR_TOKEN) as? DecodedToken
        var failure = req.getAttribute(FirebaseTokenFilter.ATTR_FAILURE) as? AuthFailure
        if (decoded == null && failure == null) {
            // the filter did not run (e.g. called outside the web stack)
            try {
                decoded = identity.verifyIdToken(token, true)
            } catch (e: IdentityException) {
                failure = AuthFailure(e.kind)
            } catch (e: Exception) {
                failure = AuthFailure(IdentityException.Kind.UNAVAILABLE)
            }
        }
        if (decoded != null) {
            accounts.ensure(decoded.uid, decoded.email)
            return AuthUser(uid = decoded.uid, email = decoded.email ?: "", emailVerified = decoded.emailVerified)
        }
        val kind = failure?.kind
        // Firebase could not check the login (outage, throttling): retryable, the session itself is fine
        if (kind == IdentityException.Kind.UNAVAILABLE) {
            throw ApiException(
                503,
                if (lang == "en") "Couldn't check your login right now. Please try again in a moment." else "এখন লগইন যাচাই করা যাচ্ছে না। একটু পরে আবার চেষ্টা করুন।", // bn-ok
                "auth_unavailable",
            )
        }
        if (kind == IdentityException.Kind.USER_DISABLED || kind == IdentityException.Kind.TOKEN_REVOKED) {
            val uid = uidFromToken(token)
            val u = uid?.let { runCatching { identity.getUser(it) }.getOrNull() }
            if (u != null && u.disabled) throw accountDisabledError(lang, uid)
            if (u != null && kind == IdentityException.Kind.TOKEN_REVOKED) {
                val at = runCatching { accounts.deleteAt(u.uid) }.getOrNull()
                if (at != null) throw ApiException(403, deletionPendingMessage(lang, at.toEpochMilli()), "account_disabled")
            }
        }
        throw ApiException(401, if (lang == "en") "Session expired, please log in again" else "লগইনের মেয়াদ শেষ, আবার লগইন করুন")
    }

    /** firebase.js accountDisabledError — English only when lang == "en" */
    fun accountDisabledError(lang: String?, uid: String?): ApiException {
        val reason = uid?.let { runCatching { accounts.disabledReason(it) }.getOrDefault("") }.orEmpty()
        val support = LifoloProperties.SUPPORT_EMAIL
        val msg = if (lang == "en") {
            "Your account was suspended by the Lifolo team.${if (reason.isNotEmpty()) " Reason: $reason." else ""} To appeal, email $support"
        } else {
            "আপনার অ্যাকাউন্ট Lifolo টিম বন্ধ করেছে।${if (reason.isNotEmpty()) " কারণ: $reason।" else ""} আপিল করতে ইমেইল করুন: $support"
        }
        return ApiException(403, msg, "account_disabled")
    }

    /** firebase.js deletionPendingMessage */
    fun deletionPendingMessage(lang: String?, atMs: Long): String {
        val en = lang == "en"
        val day = DhakaTime.longDate(atMs, en)
        return if (en) {
            "Your account will be deleted on $day. Log in again before then to keep it."
        } else {
            "আপনার অ্যাকাউন্ট $day মুছে যাবে। এর আগে আবার লগইন করলে মোছা বাতিল হবে।"
        }
    }

    /** uid from the JWT payload without verification (only to word the disabled/deleted message) */
    fun uidFromToken(token: String): String? = try {
        val payload = token.split('.')[1]
        val json = String(Base64.getUrlDecoder().decode(payload.trimEnd('=')), Charsets.UTF_8)
        mapper.readTree(json).get("sub")?.takeIf { it.isTextual }?.textValue()
    } catch (e: Exception) {
        null
    }
}
