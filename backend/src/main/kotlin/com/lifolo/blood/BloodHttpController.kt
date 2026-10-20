package com.lifolo.blood

import com.fasterxml.jackson.databind.JsonNode
import com.lifolo.admin.AdminLog
import com.lifolo.auth.AdminService
import com.lifolo.auth.AuthService
import com.lifolo.auth.AuthUser
import com.lifolo.auth.IdentityException
import com.lifolo.auth.IdentityProvider
import com.lifolo.auth.WebSession
import com.lifolo.common.ApiException
import com.lifolo.common.Body
import com.lifolo.common.Lang
import com.lifolo.common.Rules
import com.lifolo.common.Sql
import com.lifolo.notify.PushService
import com.lifolo.notify.PushText
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The legacy blood endpoints outside /api/community, with the Node `handler` envelope (POST only → 405, result or
 * `{ok: true}`, `{error, code?}`): /api/notify-request, /api/respond, /api/close-request, /api/edit-request
 * (firebase.js verifyUser) and /api/admin/corrections, /api/admin/resolve (admin.js verifyAdmin: Bearer or the
 * web-panel cookie).
 */
@RestController
class BloodHttpController(
    private val auth: AuthService,
    private val flows: RequestFlowService,
    private val corrections: CorrectionService,
) {
    private fun run(req: HttpServletRequest, raw: JsonNode?, fn: (Body) -> Any?): ResponseEntity<Any> {
        if (req.method != "POST") return ResponseEntity.status(405).body(mapOf("error" to "Method not allowed"))
        val b = Body.of(raw)
        req.setAttribute(Lang.ATTR, Lang.resolve(b, req))
        return ResponseEntity.ok(fn(b) ?: mapOf("ok" to true))
    }

    @RequestMapping("/api/notify-request")
    fun notifyRequest(req: HttpServletRequest, @RequestBody(required = false) raw: JsonNode?) =
        run(req, raw) { b -> flows.notify(auth.verifyUser(req, Lang.of(req)).id, b) }

    @RequestMapping("/api/respond")
    fun respond(req: HttpServletRequest, @RequestBody(required = false) raw: JsonNode?) =
        run(req, raw) { b -> flows.respond(auth.verifyUser(req, Lang.of(req)).id, b) }

    @RequestMapping("/api/close-request")
    fun closeRequest(req: HttpServletRequest, @RequestBody(required = false) raw: JsonNode?) =
        run(req, raw) { b -> flows.close(auth.verifyUser(req, Lang.of(req)).id, b) }

    @RequestMapping("/api/edit-request")
    fun editRequest(req: HttpServletRequest, @RequestBody(required = false) raw: JsonNode?) =
        run(req, raw) { b -> flows.edit(auth.verifyUser(req, Lang.of(req)).id, b) }

    @RequestMapping("/api/admin/corrections")
    fun adminCorrections(req: HttpServletRequest, res: HttpServletResponse, @RequestBody(required = false) raw: JsonNode?) =
        run(req, raw) { _ ->
            corrections.verifyAdmin(req, res)
            linkedMapOf("ok" to true, "items" to corrections.pending())
        }

    @RequestMapping("/api/admin/resolve")
    fun adminResolve(req: HttpServletRequest, res: HttpServletResponse, @RequestBody(required = false) raw: JsonNode?) =
        run(req, raw) { b -> corrections.resolve(corrections.verifyAdmin(req, res), b, req) }
}

/** corrections.js + api/admin/resolve.js: last-donation-date correction requests (old flow, admin decides) */
@Service
class CorrectionService(
    private val store: BloodStore,
    private val auth: AuthService,
    private val webSession: WebSession,
    private val admins: AdminService,
    private val push: PushService,
    private val adminLog: AdminLog,
    private val identity: IdentityProvider,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val jdbc get() = store.jdbc

    /** admin.js verifyAdmin: Bearer token, else the admin panel cookie */
    fun verifyAdmin(req: HttpServletRequest, res: HttpServletResponse): AuthUser {
        val header = req.getHeader("Authorization") ?: ""
        val token = if (header.startsWith("Bearer ")) header.substring(7) else null
        val user = if (!token.isNullOrEmpty()) {
            auth.decodeToken(req, token, Lang.of(req))
        } else {
            when (val web = webSession.user(req, res)) {
                null -> throw ApiException(401, "লগইন দরকার")
                is WebSession.Result.Error -> throw ApiException(401, "লগইনের মেয়াদ শেষ, আবার লগইন করুন", "session_expired")
                is WebSession.Result.User -> web.user
            }
        }
        if (!admins.isAdmin(user)) throw ApiException(403, "শুধু অ্যাডমিন এটা করতে পারবেন")
        // the cookie lives up to 12 h: an admin account disabled (or deleted) meanwhile is out at once
        if (user.webSession && disabled(user.id)) {
            webSession.end(res)
            throw ApiException(403, "শুধু অ্যাডমিন এটা করতে পারবেন")
        }
        return user
    }

    /** like the /api/community web panel: disabled or deleted → out; Firebase unreachable → not held against the admin */
    private fun disabled(uid: String): Boolean = try {
        identity.getUser(uid).disabled
    } catch (e: IdentityException) {
        e.kind == IdentityException.Kind.USER_NOT_FOUND
    } catch (e: Exception) {
        false
    }

    /** corrections.js pendingCorrections: oldest first, at most 100 */
    fun pending(): List<Map<String, Any?>> = jdbc.query(
        "SELECT * FROM correction_requests WHERE status = 'pending' ORDER BY created_at, id LIMIT 100",
        { rs, _ ->
            linkedMapOf<String, Any?>(
                "id" to rs.getString("id"),
                "uid" to rs.getString("uid"),
                "name" to (rs.getString("name") ?: ""),
                "currentMillis" to rs.getLong("current_ms").let { if (rs.wasNull()) null else it },
                "requestedMillis" to rs.getLong("requested_ms").let { if (rs.wasNull()) null else it },
                "reason" to (rs.getString("reason") ?: ""),
                "createdAtMillis" to (rs.getTimestamp("created_at")?.time ?: 0L),
            )
        },
    )

    private data class Resolved(val uid: String, val already: Boolean)

    /** approve (sets the user's date) or reject; the user is told; logged as correction_resolve */
    fun resolve(admin: AuthUser, b: Body, req: HttpServletRequest): Any {
        val id = b.string("id")
        val approve = b.isTrue("approve")
        if (!Rules.isValidId(id)) throw ApiException(400, "id ঠিক নেই")
        val result = store.inTx {
            val c = jdbc.queryForList("SELECT * FROM correction_requests WHERE id = ? FOR UPDATE", id).firstOrNull()
                ?: throw ApiException(404, "অনুরোধ পাওয়া যায়নি")
            val uid = c["uid"] as String
            if (c["status"] != "pending") return@inTx Resolved(uid, true)
            if (!Rules.isValidId(uid)) throw ApiException(400, "অনুরোধে সমস্যা আছে")
            if (approve) {
                val requested = (c["requested_ms"] as? Number)?.toLong()
                if (requested != null && requested > BloodRules.todayDhakaUtcMidnight()) throw ApiException(400, "চাওয়া তারিখটা ঠিক নেই")
                jdbc.update(
                    "INSERT INTO user_profiles (uid, last_donation_ms) VALUES (?, ?) ON CONFLICT (uid) DO UPDATE SET last_donation_ms = EXCLUDED.last_donation_ms",
                    uid, requested,
                )
            }
            jdbc.update(
                "UPDATE correction_requests SET status = ?, resolved_at = ?, resolved_by = ? WHERE id = ?",
                if (approve) "approved" else "rejected", Sql.ts(System.currentTimeMillis()), admin.uid, id,
            )
            Resolved(uid, false)
        }
        if (!result.already) {
            try {
                push.pushUsers(listOf(result.uid)) { _, lang ->
                    if (approve) {
                        PushText(
                            Lang.pick(lang, "Date corrected", "তারিখ সংশোধন হয়েছে"),
                            Lang.pick(lang, "Your last donation date has been fixed.", "আপনার শেষ রক্তদানের তারিখ ঠিক করে দেওয়া হয়েছে।"),
                            mapOf("type" to "correction"),
                        )
                    } else {
                        PushText(
                            Lang.pick(lang, "Date correction not approved", "তারিখ সংশোধনের অনুরোধ বাতিল হয়েছে"),
                            Lang.pick(lang, "See your profile in the app for details.", "অ্যাপের প্রোফাইল পাতায় বিস্তারিত দেখুন।"),
                            mapOf("type" to "correction"),
                        )
                    }
                }
            } catch (e: Exception) {
                log.error("correction push failed", e)
            }
            adminLog.log("correction_resolve", admin.copy(webSession = req.getHeader("Authorization") == null), b)
            return linkedMapOf("ok" to true, "uid" to result.uid)
        }
        return linkedMapOf("ok" to true, "already" to true, "uid" to result.uid)
    }
}
