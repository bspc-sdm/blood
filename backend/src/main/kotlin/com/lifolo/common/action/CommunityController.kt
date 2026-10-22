package com.lifolo.common.action

import com.fasterxml.jackson.databind.JsonNode
import com.lifolo.admin.AdminLog
import com.lifolo.auth.AdminService
import com.lifolo.auth.AuthService
import com.lifolo.auth.AuthUser
import com.lifolo.auth.IdentityException
import com.lifolo.auth.IdentityProvider
import com.lifolo.auth.WebSession
import com.lifolo.care.CareAccess
import com.lifolo.common.ApiException
import com.lifolo.common.Body
import com.lifolo.common.ClientIp
import com.lifolo.common.Lang
import com.lifolo.common.LifoloProperties
import com.lifolo.common.RateLimiter
import com.lifolo.users.AccountService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * POST /api/community — the single RPC endpoint (api/community.js). `body.action` picks the handler.
 * Response: the handler's object, or `{ok: true}` when it returns null. Errors: see ApiErrorHandler.
 */
@RestController
class CommunityController(private val dispatcher: ActionDispatcher) {
    @RequestMapping("/api/community")
    fun community(
        req: HttpServletRequest,
        res: HttpServletResponse,
        @RequestBody(required = false) raw: JsonNode?,
    ): ResponseEntity<Any> {
        if (req.method != "POST") return ResponseEntity.status(405).body(mapOf("error" to "Method not allowed"))
        val body = Body.of(raw)
        req.setAttribute(Lang.ATTR, Lang.resolve(body, req))
        val result = dispatcher.dispatch(req, res, body)
        return ResponseEntity.ok(result ?: mapOf("ok" to true))
    }
}

/**
 * The order of checks is the Node order (it decides which error a caller sees):
 *  1. Bearer token → verify (401/403); else admin web-session cookie (web panel rules); else
 *  2. guest: public actions only (IP rate limit) — anything else → 401 "Please log in"
 *  3. unknown action → 400 "Unknown request. Please update the app."
 *  4. caregiver `asUid` → act as the patient (CARE_ACTIONS only)
 *  5. run (guest answers cached 1 min); log admin_* actions unless the result says `unchanged`
 */
@Service
class ActionDispatcher(
    private val registry: ActionRegistry,
    private val auth: AuthService,
    private val webSession: WebSession,
    private val admins: AdminService,
    private val identity: IdentityProvider,
    private val care: CareAccess,
    private val guestCache: GuestResponseCache,
    private val rateLimiter: RateLimiter,
    private val adminLog: AdminLog,
    private val props: LifoloProperties,
    private val hooks: ObjectProvider<ActionHook>,
    private val accounts: AccountService,
) {
    private val disabledCache = ConcurrentHashMap<String, Pair<Long, Boolean>>()

    fun dispatch(req: HttpServletRequest, res: HttpServletResponse, body: Body): Any? {
        val lang = Lang.of(req)
        val action = body.action
        var user: AuthUser? = if (auth.hasBearer(req)) auth.decodeUser(req, lang) else webUser(req, res, body, lang)
        if (user == null) {
            if (!ActionPolicy.isPublic(action)) {
                user = auth.decodeUser(req, lang)
            } else {
                checkGuestRate(req, lang)
                user = AuthUser.GUEST
            }
        }
        if (!user.guest) checkUserRate(req, user, lang)
        val handler = registry.find(action)
        hooks.orderedStream().forEach { it.beforeAction(action) }
        if (handler == null) throw ApiException(400, Lang.tr(lang, "Unknown request. Please update the app.", "অজানা অনুরোধ। অ্যাপ আপডেট করুন।"))
        // a deactivated account may only look at itself, reactivate or delete (the caller, also when acting as a caregiver)
        if (!user.guest && action !in ActionPolicy.WHILE_DEACTIVATED && user.uid?.let { accounts.isDeactivated(it) } == true) {
            throw AccountService.deactivatedError(lang)
        }
        val actor = if (user.guest) user else care.actAs(body, user, lang)
        val ctx = ActionContext(req, res, body, actor, user, action)
        val result = if (user.guest) guestCache.cached(body) { handler.handle(ctx) } else handler.handle(ctx)
        if (action.startsWith("admin_") && !isUnchanged(result)) adminLog.log(action, user, body)
        return result
    }

    private fun isUnchanged(result: Any?): Boolean = when (result) {
        is Map<*, *> -> result["unchanged"] == true
        else -> false
    }

    /** guest.js checkRate: 240 requests per minute per IP (x-forwarded-for) */
    private fun checkGuestRate(req: HttpServletRequest, lang: String?) {
        val ip = ClientIp.key(ClientIp.of(req))
        if (!rateLimiter.tryAcquire("guest:$ip", props.guestRatePerMinute, Duration.ofMinutes(1))) {
            throw ApiException(429, Lang.tr(lang, "Too many requests. Wait a minute and try again.", "অনেক বেশি অনুরোধ। এক মিনিট পর আবার চেষ্টা করুন।"))
        }
    }

    /**
     * Logged-in callers: [LifoloProperties.userRatePerMinute] calls per account and IP a minute (not in Node). Demo
     * accounts are public (one-click login), so without this anyone could script the authenticated actions freely; the
     * IP is part of the key so that people trying the demo at the same time do not share one budget.
     */
    private fun checkUserRate(req: HttpServletRequest, user: AuthUser, lang: String?) {
        val ip = ClientIp.key(ClientIp.of(req))
        if (!rateLimiter.tryAcquire("user:${user.uid}:$ip", props.userRatePerMinute, Duration.ofMinutes(1))) {
            throw ApiException(429, Lang.tr(lang, "Too many requests. Wait a minute and try again.", "অনেক বেশি অনুরোধ। এক মিনিট পর আবার চেষ্টা করুন।"))
        }
    }

    /** community.js webUser: the admin panel's cookie session may only run admin actions */
    private fun webUser(req: HttpServletRequest, res: HttpServletResponse, body: Body, lang: String?): AuthUser? {
        val r = webSession.user(req, res) ?: return null
        if (r is WebSession.Result.Error) {
            throw ApiException(401, Lang.tr(lang, "Session expired, please log in again", "লগইনের মেয়াদ শেষ, আবার লগইন করুন"), "session_expired")
        }
        val web = (r as WebSession.Result.User).user
        val action = body.action
        if (action == "web_logout") return web
        if (!action.startsWith("admin_") && action !in ActionPolicy.WEB_PANEL_READS) {
            throw ApiException(403, Lang.tr(lang, "Not available on the web panel", "ওয়েব প্যানেলে এটা চলে না"))
        }
        if (!admins.isAdmin(web) || accountDisabled(web.id)) {
            webSession.end(res)
            throw ApiException(403, Lang.tr(lang, "Admins only", "শুধু অ্যাডমিন"), "session_expired")
        }
        return web
    }

    /** Firebase account disabled (or deleted)? remembered for one minute */
    private fun accountDisabled(uid: String): Boolean {
        val now = System.currentTimeMillis()
        disabledCache[uid]?.let { (at, v) -> if (now - at < 60_000) return v }
        val v = try {
            identity.getUser(uid).disabled
        } catch (e: IdentityException) {
            e.kind == IdentityException.Kind.USER_NOT_FOUND
        } catch (e: Exception) {
            false
        }
        disabledCache[uid] = now to v
        return v
    }
}
