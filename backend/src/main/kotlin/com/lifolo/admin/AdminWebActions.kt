package com.lifolo.admin

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.lifolo.admin.kernel.Sanctions
import com.lifolo.auth.AdminService
import com.lifolo.auth.AuthAccount
import com.lifolo.auth.AuthUser
import com.lifolo.auth.IdentityException
import com.lifolo.auth.IdentityProvider
import com.lifolo.common.DhakaTime
import com.lifolo.common.Js
import com.lifolo.common.Lang
import com.lifolo.common.Phones
import com.lifolo.common.Rules
import com.lifolo.common.Sql
import com.lifolo.common.action.ActionContext
import com.lifolo.common.action.ActionHandler
import com.lifolo.common.action.ActionModule
import com.lifolo.contracts.Chambers
import com.lifolo.contracts.ProviderSuspension
import com.lifolo.notify.NotifyCats
import com.lifolo.notify.PushService
import com.lifolo.notify.PushText
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Duration

/**
 * adminweb.js — the web admin panel: analytics (`admin_stats`, cached 30 minutes for every admin, `fresh` rebuilds),
 * finding a user by email / phone / id (`admin_user_find`), one user's page (`admin_user`), appeal decisions
 * (`admin_user_action`) and the admin action history (`admin_log`).
 */
@Component
class AdminWebActions(
    private val jdbc: JdbcTemplate,
    private val admins: AdminService,
    private val identity: IdentityProvider,
    private val suspension: ProviderSuspension,
    private val chambers: Chambers,
    private val push: PushService,
    private val redis: StringRedisTemplate,
    private val mapper: ObjectMapper,
) : ActionModule {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun actions(): Map<String, ActionHandler> = mapOf(
        "admin_stats" to ActionHandler { stats(it) },
        "admin_user_find" to ActionHandler { find(it) },
        "admin_user" to ActionHandler { user(it) },
        "admin_user_action" to ActionHandler { userAction(it) },
        "admin_log" to ActionHandler { adminLog(it) },
    )

    companion object {
        const val DAY = 24L * 3600 * 1000
        private const val DHAKA = 6L * 3600 * 1000
        private const val STATS_TTL = 30L * 60 * 1000
        private const val STATS_KEY = "lifolo:adminStats"

        /** Dhaka midnight of today, as UTC ms */
        fun dhakaMidnight(now: Long): Long = Math.floorDiv(now + DHAKA, DAY) * DAY - DHAKA

        /** adminweb.js phoneVariants: 01…, +8801…, 8801… */
        fun phoneVariants(raw: String?): List<String> {
            val p = Phones.normalizeBd(raw) ?: return emptyList()
            return listOf(Phones.localBd(p), "+$p", p)
        }
    }

    private fun requireAdmin(ctx: ActionContext) {
        if (!admins.isAdmin(ctx.user)) throw ctx.err(403, "Admins only", "শুধু অ্যাডমিন")
    }

    private fun count(sql: String, vararg args: Any?): Int? = try {
        jdbc.queryForObject(sql, Int::class.java, *args)
    } catch (e: Exception) {
        log.error("count failed: {}", e.message)
        null
    }

    // ------------------------------------------------------------------ admin_stats

    fun stats(ctx: ActionContext): Any {
        requireAdmin(ctx)
        val now = System.currentTimeMillis()
        if (!ctx.body.isTrue("fresh")) {
            try {
                redis.opsForValue().get(STATS_KEY)?.let { cached ->
                    val m = mapper.readValue(cached, object : TypeReference<LinkedHashMap<String, Any?>>() {})
                    if (now - ((m["at"] as? Number)?.toLong() ?: 0L) < STATS_TTL) return m
                }
            } catch (e: Exception) {
                log.warn("stats cache read failed: {}", e.message)
            }
        }
        val data = buildStats(now)
        try {
            redis.opsForValue().set(STATS_KEY, mapper.writeValueAsString(data), Duration.ofMillis(STATS_TTL))
        } catch (e: Exception) {
            log.error("stats cache write failed: {}", e.message)
        }
        return data
    }

    /** per-day counts of the last 30 Dhaka days ([days]) by a timestamp column */
    private fun daily(table: String, col: String, start30: Long): List<Int?> = try {
        val counts = IntArray(30)
        jdbc.query(
            "SELECT floor((extract(epoch FROM $col) * 1000 - ?) / ?)::int AS i, count(*) AS n FROM $table WHERE $col >= ? AND $col < ? GROUP BY 1",
            RowCallbackHandler { rs ->
                val i = rs.getInt("i")
                if (i in 0 until 30) counts[i] = rs.getInt("n")
            },
            start30, DAY, Sql.ts(start30), Sql.ts(start30 + 30 * DAY),
        )
        counts.toList()
    } catch (e: Exception) {
        log.error("count failed: {}", e.message)
        List(30) { null }
    }

    /** adminweb.js authStats — from Firebase Auth (logins, new accounts) */
    private fun authStats(now: Long, start30: Long): LinkedHashMap<String, Any?> {
        val newPerDay = IntArray(30)
        var total = 0
        var disabled = 0
        var new7 = 0
        var new30 = 0
        var active7 = 0
        var active30 = 0
        var token: String? = null
        for (page in 0 until 50) {
            val res = identity.listUsers(token)
            for (u in res.users) {
                total++
                if (u.disabled) disabled++
                val created = u.createdAtMs ?: 0L
                val seen = u.lastSeenAtMs ?: 0L
                if (now - created < 7 * DAY) new7++
                if (now - created < 30 * DAY) new30++
                if (now - seen < 7 * DAY) active7++
                if (now - seen < 30 * DAY) active30++
                val idx = Math.floorDiv(created - start30, DAY)
                if (idx in 0..29) newPerDay[idx.toInt()]++
            }
            token = res.nextPageToken
            if (token == null) break
        }
        return linkedMapOf(
            "total" to total, "disabled" to disabled, "new7" to new7, "new30" to new30, "active7" to active7, "active30" to active30,
            "newPerDay" to newPerDay.toList(),
        )
    }

    fun buildStats(now: Long): LinkedHashMap<String, Any?> {
        val today = dhakaMidnight(now)
        val start30 = today - 29 * DAY
        val days = (0 until 30).map { start30 + it * DAY }
        val series = linkedMapOf<String, Any?>(
            "requests" to daily("blood_requests", "created_at", start30),
            // home care: new patient ↔ professional contacts (day of the first call)
            "homeCalls" to daily("home_calls", "first_at", start30),
            "appointments" to daily("appointments", "created_at", start30),
        )
        val byStatus = linkedMapOf<String, Any?>(
            "appointments" to linkedMapOf<String, Any?>().apply {
                for (s in listOf("pending", "confirmed", "declined", "cancelled", "expired", "completed")) {
                    put(s, count("SELECT count(*) FROM appointments WHERE status = ?", s))
                }
            },
        )
        val badge = "SELECT count(*) FROM badges WHERE type = ? AND status = 'approved'"
        val home = "SELECT count(*) FROM providers WHERE type = ? AND home_visit_enabled"
        val totals = linkedMapOf<String, Any?>(
            "usersWithProfile" to count("SELECT count(*) FROM user_profiles"),
            "openRequests" to count("SELECT count(*) FROM blood_requests WHERE status = 'open'"),
            "allRequests" to count("SELECT count(*) FROM blood_requests"),
            "doctors" to count(badge, "doctor"),
            "nurses" to count(badge, "nurse"),
            "nutritionists" to count(badge, "nutritionist"),
            "trainers" to count(badge, "trainer"),
            "homeDoctors" to count(home, "doctor"),
            "homeNurses" to count(home, "nurse"),
            "pharmacies" to count("SELECT count(*) FROM pharmacies WHERE status = 'approved'"),
            "ambulances" to count("SELECT count(*) FROM ambulances WHERE status = 'approved'"),
            "bloodBanks" to count("SELECT count(*) FROM blood_banks WHERE status = 'approved'"),
            "questions" to count("SELECT count(*) FROM questions"),
            "tips" to count("SELECT count(*) FROM tips"),
            "openTickets" to count("SELECT count(*) FROM tickets WHERE status = 'open'"),
        )
        val bloodGroups = linkedMapOf<String, Any?>()
        for (g in Rules.BLOOD_GROUPS) bloodGroups[g] = count("SELECT count(*) FROM user_profiles WHERE blood_group = ?", g)
        val users = try {
            authStats(now, start30)
        } catch (e: Exception) {
            log.error("auth stats failed: {}", e.message)
            null
        }
        if (users != null) series["newUsers"] = users.remove("newPerDay")
        return linkedMapOf(
            "at" to now, "days" to days, "series" to series, "byStatus" to byStatus, "totals" to totals, "bloodGroups" to bloodGroups, "users" to users,
        )
    }

    // ------------------------------------------------------------------ users

    private fun authUser(uid: String): AuthAccount? = try {
        identity.getUser(uid)
    } catch (e: IdentityException) {
        if (e.kind == IdentityException.Kind.USER_NOT_FOUND) null else throw e
    }

    private fun profile(uid: String): Map<String, Any?>? = jdbc.queryForList("SELECT * FROM user_profiles WHERE uid = ?", uid).firstOrNull()

    /** adminweb.js brief */
    private fun brief(uid: String, u: AuthAccount?, doc: Map<String, Any?>?): LinkedHashMap<String, Any?> = linkedMapOf(
        "uid" to (u?.uid ?: uid),
        "email" to (u?.email ?: ""),
        "name" to ((doc?.get("name") as? String)?.takeIf { it.isNotEmpty() } ?: ""),
        "phone" to ((doc?.get("phone") as? String)?.takeIf { it.isNotEmpty() } ?: ""),
        "bloodGroup" to ((doc?.get("blood_group") as? String)?.takeIf { it.isNotEmpty() } ?: ""),
        "district" to ((doc?.get("place_name") as? String)?.takeIf { it.isNotEmpty() } ?: ""),
        "disabled" to (u?.disabled == true),
    )

    fun find(ctx: ActionContext): Any {
        requireAdmin(ctx)
        val q = (if (Js.truthy(ctx.body["q"])) Js.toStr(ctx.body["q"]) else "").trim()
        if (q.length < 3 || q.length > 200) {
            throw ctx.err(400, "Enter an email, phone number or user ID", "ইমেইল, ফোন নম্বর বা ইউজার আইডি দিন")
        }
        val found = LinkedHashMap<String, AuthAccount?>()
        if ('@' in q) {
            try {
                val u = identity.getUserByEmail(q.lowercase())
                found[u.uid] = u
            } catch (e: IdentityException) {
                if (e.kind != IdentityException.Kind.USER_NOT_FOUND && e.kind != IdentityException.Kind.INVALID_TOKEN) throw e
            }
        } else if (phoneVariants(q).isNotEmpty()) {
            val v = phoneVariants(q)
            jdbc.queryForList("SELECT uid FROM user_profiles WHERE phone IN (?, ?, ?) LIMIT 10", String::class.java, v[0], v[1], v[2])
                .forEach { found[it] = null }
            // pharmacy and ambulance numbers too (with the old wrong "001…" form)
            val local = Phones.localBd(Phones.normalizeBd(q))
            jdbc.queryForList("SELECT uid FROM pharmacies WHERE phone IN (?, ?) LIMIT 10", String::class.java, local, "0$local")
                .forEach { found[it] = null }
            jdbc.queryForList("SELECT owner_uid FROM ambulances WHERE phone IN (?, ?) LIMIT 10", String::class.java, local, "0$local")
                .forEach { if (it != null) found[it] = null }
        } else if (Phones.contactPhone(q) != null) {
            // landline / hotline: ambulance and blood bank numbers
            val c = Phones.contactPhone(q)
            for (t in listOf("ambulances", "blood_banks")) {
                jdbc.queryForList("SELECT owner_uid FROM $t WHERE phone = ? LIMIT 10", String::class.java, c)
                    .forEach { if (!it.isNullOrEmpty()) found[it] = null }
            }
        } else if (Rules.isValidId(q)) {
            found[q] = null
        }
        val items = ArrayList<Map<String, Any?>>()
        for ((uid, u0) in found.entries.take(10)) {
            if (!Rules.isValidId(uid)) continue
            val u = u0 ?: authUser(uid)
            val doc = profile(uid)
            if (u != null || doc != null) items.add(brief(uid, u, doc))
        }
        return linkedMapOf("items" to items)
    }

    fun user(ctx: ActionContext): Any {
        requireAdmin(ctx)
        val uid = ctx.body.string("uid")
        if (!Rules.isValidId(uid)) throw ctx.err(400, "Not found", "পাওয়া যায়নি")
        val v = userView(uid!!) ?: throw ctx.err(404, "User not found", "ব্যবহারকারী পাওয়া যায়নি")
        return linkedMapOf("user" to v)
    }

    private fun ms(v: Any?): Long? = (v as? Timestamp)?.time

    private fun str(v: Any?): String = (v as? String) ?: ""

    /** adminweb.js userView */
    private fun userView(uid: String): Map<String, Any?>? {
        val u = authUser(uid)
        val doc = profile(uid)
        if (u == null && doc == null) return null
        val state = jdbc.queryForList("SELECT * FROM user_state WHERE uid = ?", uid).firstOrNull()
        val badge = jdbc.queryForList("SELECT type, status, reg_no, workplace FROM badges WHERE uid = ?", uid).firstOrNull()
        val prov = jdbc.queryForList("SELECT * FROM providers WHERE uid = ?", uid).firstOrNull()
        val shop = jdbc.queryForList("SELECT uid, name, status, phone, district FROM pharmacies WHERE uid = ?", uid).firstOrNull()
        val ambs = jdbc.queryForList("SELECT id, name, status, vehicle_no, phone FROM ambulances WHERE owner_uid = ? ORDER BY created_at LIMIT 10", uid)
        val banks = jdbc.queryForList("SELECT id, name, status, phone, district FROM blood_banks WHERE owner_uid = ? ORDER BY created_at LIMIT 5", uid)
        val email = (u?.email ?: "").lowercase()
        val out = brief(uid, u, doc)
        out["createdAt"] = u?.createdAtMs
        out["lastSeenAt"] = u?.lastSeenAtMs
        out["lastDonationMillis"] = doc?.get("last_donation_ms")
        out["notify"] = if (doc != null) doc["notify"] != false else null
        out["phoneVerified"] = !(state?.get("verified_phone") as? String).isNullOrEmpty()
        // the donation-date changes, newest first
        out["donationDateLog"] = jdbc.query(
            "SELECT from_ms, to_ms, at, risk FROM donation_date_log WHERE uid = ? ORDER BY at DESC, id DESC",
            { rs, _ ->
                linkedMapOf(
                    "from" to rs.getLong(1).let { if (rs.wasNull()) null else it }, "to" to rs.getLong(2).let { if (rs.wasNull()) null else it },
                    "at" to rs.getTimestamp(3).time, "risk" to (rs.getString(4) ?: ""),
                )
            },
            uid,
        )
        out["isAdmin"] = if (email.isNotEmpty()) admins.isAdmin(AuthUser(uid = uid, email = email, emailVerified = true)) else false
        out["isOwner"] = if (email.isNotEmpty()) admins.adminEmails().contains(email) else false
        out["badge"] = badge?.let { linkedMapOf("type" to it["type"], "status" to it["status"], "regNo" to str(it["reg_no"]), "workplace" to str(it["workplace"])) }
        out["provider"] = prov?.let { p ->
            linkedMapOf(
                "listed" to (p["listed"] == true),
                "homeVisit" to (p["home_visit_enabled"] == true),
                "homeVisitBlocked" to (p["home_visit_blocked"] == true),
                "homePhone" to Phones.fixPhone(p["home_phone"] as? String),
                "homeCalls" to (count("SELECT coalesce(max(total), 0) FROM listing_stats WHERE kind = 'home' AND listing_id = ?", uid) ?: 0),
                "chambers" to chambers.chambersOf(uid).size,
                "suspended" to (p["admin_suspended"] == true),
                "suspendReason" to str(p["suspend_reason"]),
            )
        }
        out["pharmacy"] = shop?.let { linkedMapOf("id" to it["uid"], "name" to it["name"], "status" to it["status"], "phone" to Phones.fixPhone(it["phone"] as? String), "district" to str(it["district"])) }
        out["bloodBanks"] = banks.map { linkedMapOf("id" to it["id"], "name" to it["name"], "status" to it["status"], "phone" to Phones.fixPhone(it["phone"] as? String), "district" to str(it["district"])) }
        out["ambulances"] = ambs.map { linkedMapOf("id" to it["id"], "name" to it["name"], "status" to it["status"], "vehicleNo" to str(it["vehicle_no"]), "phone" to Phones.fixPhone(it["phone"] as? String)) }
        out["blocks"] = linkedMapOf(
            // serverState.homeVisitBanned belonged to the old home-visit requests (nothing sets it any more)
            "homeVisitBanned" to false,
            "homeVisitBlocked" to (prov?.get("home_visit_blocked") == true),
        )
        val reqs = jdbc.queryForList(
            "SELECT id, created_at, status, patient_group, hospital, place_name FROM blood_requests WHERE requester_uid = ? ORDER BY created_at DESC LIMIT 10", uid,
        )
        val appts = jdbc.queryForList(
            """
            SELECT id, created_at, status, provider_uid, visit_date FROM appointments
            WHERE patient_uid = ? OR provider_uid = ? ORDER BY created_at DESC LIMIT 10
            """.trimIndent(),
            uid, uid,
        )
        val tickets = jdbc.queryForList("SELECT id, updated_at, status, category FROM tickets WHERE uid = ? ORDER BY updated_at DESC LIMIT 20", uid)
        out["activity"] = linkedMapOf(
            "requests" to reqs.map {
                linkedMapOf(
                    "id" to it["id"], "at" to ms(it["created_at"]), "status" to it["status"], "group" to it["patient_group"],
                    "hospital" to str(it["hospital"]), "place" to str(it["place_name"]),
                )
            },
            "appointments" to appts.map {
                linkedMapOf(
                    "id" to it["id"], "at" to ms(it["created_at"]), "status" to it["status"],
                    "role" to if (it["provider_uid"] == uid) "provider" else "patient",
                    "date" to (it["visit_date"] as? java.sql.Date)?.let { d -> DhakaTime.toUtcMidnightMs(d.toLocalDate()) },
                )
            },
            "tickets" to tickets.map { linkedMapOf("id" to it["id"], "at" to ms(it["updated_at"]), "status" to it["status"], "category" to it["category"]) },
            "reportsAgainst" to (count("SELECT count(*) FROM (SELECT 1 FROM mod_reports WHERE reported_uid = ? LIMIT 50) r", uid) ?: 0),
        )
        return out
    }

    // ------------------------------------------------------------------ admin_user_action

    /** appeals: lift or set bans, suspend or enable an account */
    fun userAction(ctx: ActionContext): Any {
        requireAdmin(ctx)
        val b = ctx.body
        val uid = b.string("uid")
        val op = b.string("op")
        if (!Rules.isValidId(uid)) throw ctx.err(400, "Not found", "পাওয়া যায়নি")
        uid!!
        // on an admin or the owner: only the owner, and nobody on the owner
        val target = authUser(uid)
        val targetEmail = (target?.email ?: "").lowercase()
        if (targetEmail.isNotEmpty() && admins.adminEmails().contains(targetEmail) && uid != ctx.user.id) {
            throw ctx.err(403, "Actions on the owner's account aren't allowed", "মালিকের অ্যাকাউন্টে এই কাজ করা যায় না")
        }
        if (targetEmail.isNotEmpty() && !admins.isOwner(ctx.user) && admins.isAdmin(AuthUser(uid = uid, email = targetEmail, emailVerified = true))) {
            throw ctx.err(403, "Only the owner can act on an admin", "অ্যাডমিনের ওপর শুধু মালিক ব্যবস্থা নিতে পারেন")
        }
        val reason = (if (Js.truthy(b["reason"])) Js.toStr(b["reason"]) else "").trim().take(200)
        when (op) {
            "homevisit_unban" -> {
                jdbc.update("UPDATE providers SET home_visit_blocked = FALSE WHERE uid = ?", uid)
                // the home-care ban kept from an earlier account (by email) goes too
                Sanctions.emailKey(targetEmail)?.let { jdbc.update("UPDATE sanctions SET home_care = FALSE WHERE key = ?", it) }
            }
            // a professional suspended after a report is listed again (appeal granted)
            "provider_unsuspend" -> suspension.liftProvider(uid)
            // temporary: chambers and home care unlisted, future appointments cancelled; "list again" brings it back
            "provider_suspend" -> suspension.suspendProvider(uid, "admin")
            // wrong/fake chambers: all chambers go, future appointments cancelled; home care stays
            "provider_chambers_remove" -> {
                if (jdbc.queryForList("SELECT 1 FROM providers WHERE uid = ?", uid).isNotEmpty()) {
                    jdbc.update("DELETE FROM provider_chambers WHERE provider_uid = ?", uid)
                    jdbc.update("UPDATE providers SET legacy_chamber = FALSE, listed = FALSE, appointments = FALSE, active = FALSE WHERE uid = ?", uid)
                    suspension.cancelStale(uid, "The chamber was removed", "চেম্বারটা সরানো হয়েছে")
                    push.pushUsers(listOf(uid)) { _, lang ->
                        PushText(
                            Lang.pick(lang, "Your chambers were removed by the Lifolo team", "Lifolo টিম আপনার চেম্বার সরিয়ে দিয়েছে"),
                            reason,
                            // a listing change by the team: the "business" category (like listing approved/suspended)
                            mapOf("type" to "appointment", "role" to "provider", "cat" to NotifyCats.BUSINESS),
                        )
                    }
                }
            }
            "homecare_ban", "homevisit_ban_nurse" -> suspension.blockHomeCare(uid)
            "disable", "enable" -> {
                if (target == null) throw ctx.err(404, "User not found", "ব্যবহারকারী পাওয়া যায়নি")
                if (target.uid == ctx.user.id) throw ctx.err(400, "You can't change your own account", "নিজের অ্যাকাউন্টে এটা করা যায় না")
                if (op == "disable") {
                    identity.updateUser(uid, disabled = true)
                    // the reason: shown on the next login attempt and in the app
                    jdbc.update(
                        "INSERT INTO accounts (uid, email, disabled_reason) VALUES (?, ?, ?) ON CONFLICT (uid) DO UPDATE SET disabled_reason = EXCLUDED.disabled_reason",
                        uid, targetEmail.ifEmpty { null }, reason,
                    )
                    // logins end: no new token, the current one at most 1 hour
                    identity.revokeRefreshTokens(uid)
                    jdbc.update("DELETE FROM device_tokens WHERE uid = ?", uid)
                    // a doctor/nurse: unlisted, home care off, future appointments cancelled with a notice
                    suspension.suspendProvider(uid, "account")
                } else {
                    identity.updateUser(uid, disabled = false)
                    jdbc.update("UPDATE accounts SET disabled_reason = NULL WHERE uid = ?", uid)
                    // the chamber listing as before (home care is switched on by the professional); a report suspension stays
                    val reasonNow = jdbc.queryForList("SELECT suspend_reason FROM providers WHERE uid = ?", String::class.java, uid).firstOrNull()
                    if (reasonNow == "account") suspension.liftProvider(uid)
                }
            }
            else -> throw ctx.err(400, "Unknown action", "অজানা কাজ")
        }
        return linkedMapOf("ok" to true)
    }

    // ------------------------------------------------------------------ admin_log

    /** which admin did what and when (newest first, 100 per page, cursor `before`) */
    fun adminLog(ctx: ActionContext): Any {
        requireAdmin(ctx)
        val before = ctx.body.int("before")?.takeIf { it > 0 && it < Sql.MAX_MS }
        val rows = jdbc.queryForList(
            "SELECT * FROM admin_log" + (if (before != null) " WHERE at < ?" else "") + " ORDER BY at DESC LIMIT 100",
            *(if (before != null) arrayOf<Any?>(Sql.ts(before)) else emptyArray()),
        )
        return linkedMapOf(
            "items" to rows.map {
                linkedMapOf(
                    "id" to it["id"], "at" to ms(it["at"]), "action" to it["action"], "by" to it["by_email"], "byUid" to it["by_uid"],
                    "via" to it["via"], "target" to it["target"], "detail" to it["detail"],
                )
            },
        )
    }
}
