package com.lifolo.desktop

import com.lifolo.auth.IdentityProvider
import com.lifolo.common.ApiException
import com.lifolo.common.Ids
import com.lifolo.common.RateLimiter
import com.lifolo.common.Rules
import com.lifolo.common.Sql
import com.lifolo.common.action.ActionContext
import com.lifolo.common.ms
import com.lifolo.contracts.Badges
import com.lifolo.contracts.ProviderAssistants
import com.lifolo.demo.DemoWorld
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.time.Duration

/** the time the desktop sync code reads (tests move it) */
@Component
class DesktopClock {
    @Volatile
    var fixed: Long? = null

    fun now(): Long = fixed ?: System.currentTimeMillis()
}

object DesktopRules {
    val KINDS = setOf("pharmacy", "lab", "chamber")

    /** registered (not revoked) devices per business */
    const val MAX_DEVICES = 5

    /** series numbers ever given per business (revoked ones are never reused) */
    const val MAX_SERIES = 99

    const val NAME_MAX = 40

    /** a typed name: no control / direction characters, single spaces */
    fun clean(s: String?): String = (s ?: "").filter { !Character.isISOControl(it) && it !in '\u202A'..'\u202E' && it !in '\u2066'..'\u2069' }
        .trim().replace(Regex("\\s+"), " ")

    /** ops per offline_sync call */
    const val MAX_OPS = 100

    /** one op's `data`, serialized */
    const val MAX_OP_BYTES = 16_000

    const val DAY_MS = 24L * 3600 * 1000

    /** ops older than this are rejected as suspicious (error "too_old"): the 7-day offline limit + a day */
    const val MAX_AGE_MS = 8 * DAY_MS

    /** an op stamped further in the future than this is rejected as suspicious (error "bad_at") */
    const val MAX_AHEAD_MS = 10 * 60 * 1000L

    /** a signed request's `ts` may differ this much from the server clock */
    const val SIG_SKEW_MS = 10 * 60 * 1000L

    /** op results are kept this long (a replay after that is "too_old" anyway) */
    const val KEEP_OPS_MS = 45 * DAY_MS

    val OP_ID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    /** `<series>-<seq 6 digits>` of a desktop invoice */
    val DESKTOP_NO = Regex("^D([1-9][0-9]?)-([0-9]{6})$")

    // rate limits (Redis, per device / per user)
    const val REGISTER_PER_DAY = 10L
    const val BOOTSTRAP_PER_HOUR = 30L
    const val SYNC_PER_MINUTE = 30L
}

data class Device(
    val id: String,
    val kind: String,
    val bizId: String,
    val n: Int,
    val name: String,
    val registeredBy: String,
    val createdAt: Long,
    val lastSyncAt: Long?,
    val opsCount: Int,
    val revokedAt: Long?,
    /** salt of the device secret (null once revoked) — the secret itself is derived, never stored ([DeviceSig]) */
    val secret: String? = null,
    /** ops refused for their time (older than 8 days / ahead of the server) in the last 45 days */
    val suspicious: Int = 0,
) {
    val series: String get() = "D$n"

    val revoked: Boolean get() = revokedAt != null

    fun view(): Map<String, Any?> = linkedMapOf(
        "deviceId" to id, "kind" to kind, "bizId" to bizId, "series" to series, "name" to name, "createdAt" to createdAt,
        "lastSyncAt" to lastSyncAt, "ops" to opsCount, "revoked" to revoked, "revokedAt" to revokedAt, "suspicious" to suspicious,
    )

    companion object {
        fun of(rs: ResultSet) = Device(
            rs.getString("id"), rs.getString("kind"), rs.getString("biz_id"), rs.getInt("n"), rs.getString("name"),
            rs.getString("registered_by"), rs.ms("created_at") ?: 0L, rs.ms("last_sync_at"), rs.getInt("ops_count"), rs.ms("revoked_at"),
            rs.getString("secret_salt"), runCatching { rs.getInt("suspicious") }.getOrDefault(0),
        )
    }
}

/**
 * Who may use a business's desktop devices:
 *  - pharmacy / lab: the owner (bizId = the owner's uid);
 *  - chamber: the doctor (bizId = the doctor's uid, approved doctor badge) — "owner"; or an assistant the doctor added
 *    (verified email on rx_team) — "assistant". Assistants list devices, bootstrap and sync; only the owner registers
 *    and revokes.
 * Demo and real never mix: a demo account never reaches a real business (and the other way round).
 */
@Component
class DesktopAccess(
    private val jdbc: JdbcTemplate,
    private val badges: Badges,
    private val assistants: ProviderAssistants,
) {
    fun bizErr(ctx: ActionContext): ApiException = ctx.err(403, "This is not your business", "এটা আপনার প্রতিষ্ঠান নয়") // bn-ok

    /** "owner" or "assistant"; [register] = registering a device (approved listing / badge needed) */
    fun role(ctx: ActionContext, kind: String, bizId: String, register: Boolean = false): String {
        val u = ctx.user
        if (u.guest || !Rules.isValidId(u.uid)) throw ctx.err(401, "Please log in", "লগইন করুন")
        // a caregiver acting for a patient never runs a business's desk
        if (u.patientSideOnly) throw bizErr(ctx)
        if (!Rules.isValidId(bizId) || !DemoWorld.sameWorld(u.uid, bizId)) throw bizErr(ctx)
        val uid = u.id
        when (kind) {
            "pharmacy", "lab" -> {
                if (uid != bizId) throw bizErr(ctx)
                val table = if (kind == "pharmacy") "pharmacies" else "labs"
                val status = jdbc.queryForList("SELECT status FROM $table WHERE uid = ?", String::class.java, bizId).firstOrNull()
                    ?: throw ctx.err(403, "Add your business first", "আগে আপনার প্রতিষ্ঠান যোগ করুন") // bn-ok
                if (register && status != "approved") {
                    throw ctx.err(403, "Only approved businesses can use the desktop app", "শুধু অনুমোদিত প্রতিষ্ঠান ডেস্কটপ অ্যাপ ব্যবহার করতে পারে") // bn-ok
                }
                return "owner"
            }
            "chamber" -> {
                if (badges.approvedBadge(bizId) != "doctor") {
                    throw ctx.err(403, "This doctor is not verified now", "এই ডাক্তার এখন যাচাই করা নেই")
                }
                if (uid == bizId) return "owner"
                val email = if (u.emailVerified) u.email.lowercase() else ""
                if (email.isEmpty() || !assistants.isAssistant(bizId, email)) throw bizErr(ctx)
                return "assistant"
            }
            else -> throw ctx.err(400, "Choose the business type", "প্রতিষ্ঠানের ধরন বেছে নিন") // bn-ok
        }
    }
}

@Service
class DesktopDevices(
    private val jdbc: JdbcTemplate,
    private val identity: IdentityProvider,
    private val sig: DeviceSig,
    private val access: DesktopAccess,
    private val limiter: RateLimiter,
    private val clock: DesktopClock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun find(id: String): Device? =
        jdbc.query("SELECT * FROM desktop_devices WHERE id = ?", { rs, _ -> Device.of(rs) }, id).firstOrNull()

    private fun notFound(ctx: ActionContext) = ctx.err(404, "Device not found", "ডিভাইস পাওয়া যায়নি") // bn-ok

    fun revokedErr(ctx: ActionContext) =
        ctx.err(403, "This computer was removed from your business. Register it again.", "এই কম্পিউটারটি আপনার প্রতিষ্ঠান থেকে সরানো হয়েছে। আবার নিবন্ধন করুন।", "device_revoked") // bn-ok

    fun tooMany(ctx: ActionContext) = ctx.err(429, "Too many requests. Wait a minute and try again.", "অনেক বেশি অনুরোধ। এক মিনিট পর আবার চেষ্টা করুন।")

    /**
     * The device of `deviceId` for bootstrap / sync: the caller must work for its business (else 404, so nobody learns
     * about others' devices); a revoked device → 403 `device_revoked`; then the request must be signed with the device
     * secret (401 `bad_device_sig`).
     */
    fun usable(ctx: ActionContext, deviceId: String?): Pair<Device, String> {
        if (deviceId == null || !Rules.isValidId(deviceId)) throw notFound(ctx)
        val d = find(deviceId) ?: throw notFound(ctx)
        val role = try {
            access.role(ctx, d.kind, d.bizId)
        } catch (e: ApiException) {
            if (e.status == 403 && ctx.user.uid != null && ctx.user.uid != d.bizId) throw notFound(ctx)
            throw e
        }
        if (d.revoked) throw revokedErr(ctx)
        // I5: only the desk itself (its secret) can bootstrap or sync — not any session of the owner
        sig.check(ctx, d, clock.now())
        return d to role
    }

    private fun kindOf(ctx: ActionContext): String =
        ctx.body.string("kind")?.takeIf { it in DesktopRules.KINDS } ?: throw ctx.err(400, "Choose the business type", "প্রতিষ্ঠানের ধরন বেছে নিন") // bn-ok

    /** desktop_register {kind, bizId, name} → {deviceId, series} (the owner; at most 5 devices at a time) */
    @Transactional
    fun register(ctx: ActionContext): Any {
        val kind = kindOf(ctx)
        val bizId = ctx.body.string("bizId")?.takeIf { it.isNotEmpty() } ?: ctx.user.uid ?: ""
        val role = access.role(ctx, kind, bizId, register = true)
        if (role != "owner") throw ctx.err(403, "Only the owner can add a computer", "শুধু মালিক কম্পিউটার যোগ করতে পারেন") // bn-ok
        val name = DesktopRules.clean(ctx.body.string("name")).take(DesktopRules.NAME_MAX)
        if (name.isEmpty()) throw ctx.err(400, "Give this computer a name", "কম্পিউটারের একটা নাম দিন") // bn-ok
        if (!limiter.tryAcquire("desk-reg:${ctx.user.id}", DesktopRules.REGISTER_PER_DAY, Duration.ofDays(1))) throw tooMany(ctx)
        // one registration at a time per business: the count and the next series number stay exact
        jdbc.queryForObject("SELECT count(*) FROM (SELECT pg_advisory_xact_lock(hashtext(?))) x", Int::class.java, "desktop:$kind:$bizId")
        val active = jdbc.queryForObject(
            "SELECT count(*) FROM desktop_devices WHERE kind = ? AND biz_id = ? AND revoked_at IS NULL", Int::class.java, kind, bizId,
        ) ?: 0
        if (active >= DesktopRules.MAX_DEVICES) {
            throw ctx.err(
                409, "Up to ${DesktopRules.MAX_DEVICES} computers. Remove one first.", "সর্বোচ্চ ৫টা কম্পিউটার। আগে একটা সরান।", "device_limit", // bn-ok
            )
        }
        val n = (jdbc.queryForObject("SELECT max(n) FROM desktop_devices WHERE kind = ? AND biz_id = ?", Int::class.java, kind, bizId) ?: 0) + 1
        if (n > DesktopRules.MAX_SERIES) throw ctx.err(409, "No more computers can be added", "আর কম্পিউটার যোগ করা যাবে না", "device_limit") // bn-ok
        val id = Ids.newId()
        val salt = Ids.randomHex(32)
        jdbc.update(
            "INSERT INTO desktop_devices (id, kind, biz_id, n, name, registered_by, secret_salt, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            id, kind, bizId, n, name, ctx.user.id, salt, Sql.ts(clock.now()),
        )
        val secret = sig.secretOf(id, salt)
        // the secret is shown this once: the desk keeps it (Windows DPAPI) and signs every offline_sync / offline_bootstrap
        return linkedMapOf("deviceId" to id, "series" to "D$n", "kind" to kind, "bizId" to bizId, "name" to name, "secret" to secret)
    }

    /** desktop_devices {kind, bizId} → {items} (owner or chamber assistant; revoked ones too, newest first) */
    @Transactional(readOnly = true)
    fun list(ctx: ActionContext): Any {
        val kind = kindOf(ctx)
        val bizId = ctx.body.string("bizId")?.takeIf { it.isNotEmpty() } ?: ctx.user.uid ?: ""
        val role = access.role(ctx, kind, bizId)
        val items = jdbc.query(
            """
            SELECT d.*, (SELECT count(*) FROM offline_ops o WHERE o.device_id = d.id AND o.suspicious) AS suspicious
            FROM desktop_devices d WHERE d.kind = ? AND d.biz_id = ? ORDER BY (d.revoked_at IS NULL) DESC, d.n DESC LIMIT 100
            """.trimIndent(),
            { rs, _ -> Device.of(rs) }, kind, bizId,
        )
        return linkedMapOf(
            "items" to items.map { it.view() }, "role" to role, "max" to DesktopRules.MAX_DEVICES,
            "active" to items.count { !it.revoked },
        )
    }

    /** desktop_revoke {deviceId}: the owner; the device stops syncing at once (403 device_revoked) */
    @Transactional
    fun revoke(ctx: ActionContext): Any {
        val id = ctx.body.string("deviceId")
        if (id == null || !Rules.isValidId(id)) throw notFound(ctx)
        val d = find(id) ?: throw notFound(ctx)
        val role = try {
            access.role(ctx, d.kind, d.bizId)
        } catch (e: ApiException) {
            if (e.status == 403 && ctx.user.uid != d.bizId) throw notFound(ctx)
            throw e
        }
        if (role != "owner") throw ctx.err(403, "Only the owner can remove a computer", "শুধু মালিক কম্পিউটার সরাতে পারেন") // bn-ok
        if (!d.revoked) {
            jdbc.update(
                "UPDATE desktop_devices SET revoked_at = ?, revoked_by = ?, secret_salt = NULL WHERE id = ? AND revoked_at IS NULL",
                Sql.ts(clock.now()), ctx.user.id, id,
            )
        }
        // I4: the desk's WebView keeps a Firebase session — end every session of the person who set it up, so a lost or
        // stolen computer is locked out of the online account too (their phone/web need a fresh login as well)
        val signedOut = try {
            identity.revokeRefreshTokens(d.registeredBy)
            true
        } catch (e: Exception) {
            log.warn("desktop_revoke: could not end the sessions of {}: {}", d.registeredBy, e.message)
            false
        }
        return linkedMapOf(
            "ok" to true, "revoked" to true, "signedOut" to signedOut, "device" to find(id)!!.view(),
            "note" to ctx.tr(
                "That computer needs a new login. Other phones and computers of the same account must log in again too.",
                "ওই কম্পিউটারে আবার লগইন লাগবে। একই অ্যাকাউন্টের অন্য ফোন ও কম্পিউটারেও আবার লগইন করতে হবে।", // bn-ok
            ),
        )
    }

    fun touch(d: Device, uid: String, ops: Int) {
        jdbc.update(
            "UPDATE desktop_devices SET last_sync_at = ?, last_sync_by = ?, ops_count = ops_count + ? WHERE id = ?",
            Sql.ts(clock.now()), uid, ops, d.id,
        )
    }
}
