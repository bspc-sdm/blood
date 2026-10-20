package com.lifolo.auth

import com.fasterxml.jackson.databind.JsonNode
import com.lifolo.common.ApiException
import com.lifolo.common.Body
import com.lifolo.common.ClientIp
import com.lifolo.common.Js
import com.lifolo.common.Lang
import com.lifolo.demo.DemoLoginService
import com.lifolo.notify.LoginCodeMail
import com.lifolo.notify.MailSender
import com.lifolo.users.AccountService
import com.lifolo.users.DeletionService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Email-code login (api/auth/send-code.js, api/auth/verify-code.js). No token needed, so strict limits.
 * Error texts are the Node Bangla strings; the error handler translates them when `lang == "en"`.
 */
@RestController
class AuthController(
    private val identity: IdentityProvider,
    private val loginCodes: LoginCodeService,
    private val mail: MailSender,
    private val admins: AdminService,
    private val webSession: WebSession,
    private val auth: AuthService,
    private val accounts: AccountService,
    private val deletion: DeletionService,
    private val demo: DemoLoginService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private fun begin(req: HttpServletRequest, raw: JsonNode?): Body {
        val b = Body.of(raw)
        req.setAttribute(Lang.ATTR, Lang.resolve(b, req))
        return b
    }

    private fun notPost(req: HttpServletRequest): ResponseEntity<Any>? =
        if (req.method != "POST") ResponseEntity.status(405).body(mapOf("error" to "Method not allowed")) else null

    @RequestMapping("/api/auth/send-code")
    fun sendCode(req: HttpServletRequest, @RequestBody(required = false) raw: JsonNode?): ResponseEntity<Any> {
        notPost(req)?.let { return it }
        val b = begin(req, raw)
        val email = EmailCodes.normalizeEmail(b.string("email") ?: Js.toStr(b["email"]))
            ?: throw ApiException(400, "সঠিক ইমেইল দিন")
        val exists = accountExists(email)
        val reserved = loginCodes.reserve(email, exists, ClientIp.of(req), System.currentTimeMillis())
        try {
            mail.send(email, LoginCodeMail.subject(reserved.code), LoginCodeMail.text(reserved.code), LoginCodeMail.html(reserved.code))
        } catch (e: Exception) {
            log.error("email send failed", e)
            runCatching { loginCodes.releaseResendGap(email) }
            throw ApiException(502, "ইমেইল পাঠানো যায়নি। একটু পর আবার চেষ্টা করুন।")
        }
        return ResponseEntity.ok(linkedMapOf("ok" to true, "rid" to reserved.rid))
    }

    @RequestMapping("/api/auth/verify-code")
    fun verifyCode(
        req: HttpServletRequest,
        res: HttpServletResponse,
        @RequestBody(required = false) raw: JsonNode?,
    ): ResponseEntity<Any> {
        notPost(req)?.let { return it }
        val b = begin(req, raw)
        val lang = Lang.of(req)
        // demo (testing): role list and one-click login
        if (b.isTrue("demoRoles")) return ResponseEntity.ok(demo.roles())
        b.string("demo")?.let { return ResponseEntity.ok(demo.login(it)) }

        val email = EmailCodes.normalizeEmail(b.string("email") ?: Js.toStr(b["email"]))
        val code = Js.toStr(b["code"]).trim()
        if (email == null) throw ApiException(400, "সঠিক ইমেইল দিন")
        if (!CODE_RE.matches(code)) throw ApiException(400, "৬ সংখ্যার কোড দিন")
        val rids = (b["rids"]?.takeIf { it.isArray }?.toList() ?: emptyList())
            .mapNotNull { Js.string(it) }
            .filter { RID_RE.matches(it) }
            .take(5)
        val ok = loginCodes.check(email, code, rids, System.currentTimeMillis())
        if (!ok) throw ApiException(400, "কোড মেলেনি। আবার দেখে লিখুন।")

        // web admin panel: httpOnly cookie instead of a token, admins only
        if (b.isTrue("web")) {
            if (!admins.isAdmin(AuthUser(uid = null, email = email, emailVerified = true))) {
                throw ApiException(403, "এই ইমেইল অ্যাডমিন হিসেবে যোগ করা নেই")
            }
            val user = getOrCreateUser(email)
            if (user.disabled) throw ApiException(403, "এই অ্যাকাউন্ট বন্ধ করা আছে")
            accounts.ensure(user.uid, email)
            keepAccount(user.uid)
            webSession.start(res, user.uid, email)
            return ResponseEntity.ok(linkedMapOf("ok" to true))
        }

        val user = getOrCreateUser(email)
        if (user.disabled) throw auth.accountDisabledError(lang, user.uid)
        accounts.ensure(user.uid, email)
        val restored = keepAccount(user.uid)
        val token = identity.createCustomToken(user.uid)
        return ResponseEntity.ok(linkedMapOf("ok" to true, "token" to token, "restored" to restored))
    }

    private fun accountExists(email: String): Boolean = try {
        identity.getUserByEmail(email)
        true
    } catch (e: IdentityException) {
        if (e.kind == IdentityException.Kind.USER_NOT_FOUND) false else throw e
    }

    /** a pending (30-day) deletion is cancelled by logging in again */
    private fun keepAccount(uid: String): Boolean = try {
        deletion.cancel(uid)
    } catch (e: Exception) {
        log.error("cancel deletion failed", e)
        false
    }

    private fun getOrCreateUser(email: String): AuthAccount {
        try {
            val user = identity.getUserByEmail(email)
            if (!user.emailVerified) {
                // someone opened this address without verifying it before: end all their sessions
                identity.revokeRefreshTokens(user.uid)
                identity.updateUser(user.uid, emailVerified = true)
            }
            return user
        } catch (e: IdentityException) {
            if (e.kind != IdentityException.Kind.USER_NOT_FOUND) throw e
        }
        return try {
            identity.createUser(email, emailVerified = true)
        } catch (e: IdentityException) {
            // created by a parallel request
            if (e.kind == IdentityException.Kind.EMAIL_EXISTS) identity.getUserByEmail(email) else throw e
        }
    }

    companion object {
        private val CODE_RE = Regex("^\\d{6}$")
        private val RID_RE = Regex("^[0-9a-f]{32}$")
    }
}
