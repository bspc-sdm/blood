package com.lifolo.demo

import com.lifolo.admin.kernel.DemoSeedContext
import com.lifolo.admin.kernel.DemoSeedPart
import com.lifolo.admin.kernel.DemoUser
import com.lifolo.auth.AdminService
import com.lifolo.auth.IdentityException
import com.lifolo.auth.IdentityProvider
import com.lifolo.common.ApiException
import com.lifolo.common.action.ActionContext
import com.lifolo.common.action.ActionHandler
import com.lifolo.common.action.ActionModule
import com.lifolo.users.AccountDeletionService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

/**
 * demo/index.js — the demo world (owner only): `admin_demo_seed` wipes the old demo and builds it again step by step
 * (step 0: wipe + the login accounts, then one request per part: blood, services, shop books, doctors &
 * prescriptions, health & caregivers — Gazipur Sadar), `admin_demo_wipe` deletes every demo account through the real
 * account deletion (so whatever testers created goes too) and the tracked rows, `admin_demo_status`.
 *
 * Manifest: `demo_state` (partial while building), `demo_accounts` (login roles + background people `demo-x-…`),
 * `demo_manifest_rows` (rows not owned by a demo account, [DemoSeedContext.track]).
 */
@Service
class DemoSeeder(
    private val jdbc: JdbcTemplate,
    private val identity: IdentityProvider,
    private val deletion: AccountDeletionService,
    private val partsProvider: ObjectProvider<DemoSeedPart>,
    private val tx: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** the parts per step, in step order (each step: parts by [DemoSeedPart.order]) */
    private fun steps(): List<List<DemoSeedPart>> =
        partsProvider.orderedStream().toList().groupBy { it.step }.toSortedMap().values.map { list -> list.sortedBy { it.order } }

    /** 0: wipe + accounts, then one per part */
    fun stepCount(): Int = 1 + steps().size

    private fun hasDemo(): Boolean = jdbc.queryForList("SELECT 1 FROM demo_state").isNotEmpty()

    // ------------------------------------------------------------------ wipe

    /** demo/index.js sweepDoctor: prescriptions a demo doctor wrote and patients entered (deletion only covers the patient side) */
    private fun sweepDoctor(uid: String) {
        jdbc.update(
            "DELETE FROM lab_orders WHERE rx_id IN (SELECT id FROM prescriptions WHERE doctor_uid = ?)",
            uid,
        )
        jdbc.update("DELETE FROM rx_orders WHERE rx_id IN (SELECT id FROM prescriptions WHERE doctor_uid = ?)", uid)
        jdbc.update("DELETE FROM prescriptions WHERE doctor_uid = ?", uid)
        jdbc.update("DELETE FROM rx_patients WHERE doctor_uid = ?", uid)
    }

    /**
     * Everything of the demo. Takes at most [BUDGET_MS] per request unless [all] — then `{more: true, left}` and the panel
     * calls again.
     */
    fun wipe(all: Boolean = false): Map<String, Any?> {
        val t0 = System.currentTimeMillis()
        if (!hasDemo()) return linkedMapOf("users" to 0, "docs" to 0)
        val emails = HashMap<String, String?>()
        jdbc.query("SELECT uid, email FROM demo_accounts", RowCallbackHandler { rs -> emails[rs.getString(1)] = rs.getString(2) })
        var uids = emails.keys.filter { DemoWorld.isDemoUid(it) }.sorted()
        val total = uids.size
        var errors = 0
        // 1) every demo account's data — through the real account deletion (what testers made goes too)
        while (uids.isNotEmpty()) {
            val chunk = uids.take(10)
            val failed = ArrayList<String>()
            for (uid in chunk) {
                try {
                    sweepDoctor(uid)
                    deletion.deleteNow(uid, emails[uid])
                    try {
                        identity.deleteUser(uid)
                    } catch (e: Exception) {
                        // already gone
                    }
                    jdbc.update("DELETE FROM demo_accounts WHERE uid = ?", uid)
                } catch (e: Exception) {
                    errors++
                    failed.add(uid)
                    log.error("demo wipe user {}: {}", uid, e.message)
                }
            }
            uids = uids.drop(10) + failed
            if (errors > 30) break
            if (!all && uids.isNotEmpty() && System.currentTimeMillis() - t0 > BUDGET_MS) return linkedMapOf("more" to true, "left" to uids.size)
        }
        if (uids.isNotEmpty()) {
            throw ApiException(500, "${uids.size}টা ডেমো অ্যাকাউন্ট পুরো মোছা যায়নি — আবার \"সব ডেমো মুছুন\" চাপুন")
        }
        // 2) the rows that belong to nobody's account
        val tracked = jdbc.query("SELECT id, table_name, row_key::text AS k FROM demo_manifest_rows ORDER BY id", { rs, _ -> Triple(rs.getLong(1), rs.getString(2), rs.getString(3)) })
        for ((_, table, key) in tracked) {
            try {
                deleteTracked(table, key)
            } catch (e: Exception) {
                log.error("demo wipe row {} {}: {}", table, key, e.message)
            }
        }
        jdbc.update("DELETE FROM demo_manifest_rows")
        jdbc.update("DELETE FROM demo_accounts")
        jdbc.update("DELETE FROM demo_state")
        return linkedMapOf("users" to total, "docs" to tracked.size)
    }

    private val ident = Regex("^[a-z_][a-z0-9_]*$")

    private fun deleteTracked(table: String, keyJson: String) {
        require(ident.matches(table)) { "bad table $table" }
        val key = com.fasterxml.jackson.databind.ObjectMapper().readTree(keyJson)
        val cols = key.fieldNames().asSequence().toList()
        require(cols.isNotEmpty() && cols.all { ident.matches(it) }) { "bad key $keyJson" }
        val args: List<Any?> = cols.map { c ->
            val v = key[c]
            when {
                v.isNull -> null
                v.isIntegralNumber -> v.longValue()
                v.isNumber -> v.doubleValue()
                v.isBoolean -> v.booleanValue()
                else -> v.asText()
            }
        }
        jdbc.update("DELETE FROM $table WHERE ${cols.joinToString(" AND ") { "$it = ?" }}", *args.toTypedArray())
    }

    // ------------------------------------------------------------------ seed

    /** the login account of a role: an existing one with this address must be the demo uid, else 409 */
    private fun ensureUser(key: String, email: String, name: String): String {
        val uid = DemoWorld.uidOf(key)
        try {
            val u = identity.getUserByEmail(email)
            // someone put this address on their own account: never the demo there
            if (u.uid != uid) throw ApiException(409, "ডেমো ঠিকানা $email অন্য অ্যাকাউন্টে আছে — ডেমো বানানো বন্ধ")
            return u.uid
        } catch (e: IdentityException) {
            if (e.kind != IdentityException.Kind.USER_NOT_FOUND) throw e
        }
        try {
            identity.createUser(email, true, uid, name)
        } catch (e: IdentityException) {
            if (e.kind != IdentityException.Kind.UID_EXISTS) throw e
            identity.updateUser(uid, email = email, emailVerified = true, disabled = false)
        }
        return uid
    }

    private fun users(): Map<String, DemoUser> {
        val out = LinkedHashMap<String, DemoUser>()
        val byKey = HashMap<String, Pair<String, String?>>()
        jdbc.query("SELECT uid, email, role_key FROM demo_accounts WHERE role_key IS NOT NULL", RowCallbackHandler { rs -> byKey[rs.getString(3)] = rs.getString(1) to rs.getString(2) })
        for (r in DemoWorld.ROLES) {
            val (uid, email) = byKey[r.key] ?: continue
            out[r.key] = DemoUser(uid, email ?: DemoWorld.demoEmail(r.key), r.name)
        }
        return out
    }

    /** one step. 0: wipe the old demo (`{next: 0}` while it takes long) + the login accounts; 1..: one part each */
    fun seedStep(step: Int): Map<String, Any?> {
        val steps = steps()
        if (step == 0) {
            val w = wipe()
            if (w["more"] == true) return linkedMapOf("next" to 0, "label" to "আগের ডেমো মোছা হচ্ছে (বাকি ${w["left"]})")
            val list = DemoWorld.ROLES.map { r -> r to ensureUser(r.key, DemoWorld.demoEmail(r.key), r.name) }
            jdbc.update("INSERT INTO demo_state (id, partial, at) VALUES (TRUE, TRUE, now()) ON CONFLICT (id) DO UPDATE SET partial = TRUE, at = now()")
            for ((r, uid) in list) {
                val email = DemoWorld.demoEmail(r.key)
                jdbc.update(
                    "INSERT INTO accounts (uid, email) VALUES (?, ?) ON CONFLICT (uid) DO UPDATE SET email = EXCLUDED.email",
                    uid, email,
                )
                jdbc.update(
                    "INSERT INTO demo_accounts (uid, email, role_key) VALUES (?, ?, ?) ON CONFLICT (uid) DO UPDATE SET email = EXCLUDED.email, role_key = EXCLUDED.role_key",
                    uid, email, r.key,
                )
            }
            return linkedMapOf("next" to 1, "label" to steps.firstOrNull()?.firstOrNull()?.label)
        }
        if (!hasDemo()) throw ApiException(409, "আগে প্রথম ধাপ চালান")
        val parts = steps[step - 1]
        val name = parts.first().label
        val ctx = Ctx(System.currentTimeMillis(), users())
        try {
            tx.executeWithoutResult { for (p in parts) p.seed(ctx) }
        } catch (e: Exception) {
            log.error("demo seed {}", name, e)
            // background people already have an Auth account: keep them in the list, so "wipe" finds them
            for (uid in ctx.extraUids) {
                jdbc.update("INSERT INTO demo_accounts (uid, email, role_key) VALUES (?, NULL, NULL) ON CONFLICT (uid) DO NOTHING", uid)
            }
            throw ApiException(400, "ডেমো বানানো \"$name\" ধাপে থেমেছে: ${(e.message ?: e.toString()).take(300)}")
        }
        val last = step == steps.size
        jdbc.update("UPDATE demo_state SET at = now(), partial = ?", !last)
        return if (last) linkedMapOf("next" to null) else linkedMapOf("next" to step + 1, "label" to steps[step].first().label)
    }

    /** the old call without a step (tests): every step at once */
    fun seedAll(): Map<String, Any?> {
        wipe(all = true)
        var step: Int? = 0
        while (step != null) step = seedStep(step)["next"] as Int?
        return linkedMapOf(
            "users" to DemoWorld.ROLES.map { linkedMapOf("key" to it.key, "role" to it.bn, "email" to DemoWorld.demoEmail(it.key)) },
            "docs" to docCount(),
        )
    }

    /**
     * How many rows the demo has (Node: the number of seeded document paths): rows of the demo world in every table
     * with an `is_demo` column, plus the tracked rows.
     */
    fun docCount(): Int {
        val tables = jdbc.queryForList(
            "SELECT table_name FROM information_schema.columns WHERE table_schema = 'public' AND column_name = 'is_demo' ORDER BY table_name",
            String::class.java,
        )
        var n = jdbc.queryForObject("SELECT count(*) FROM demo_manifest_rows", Int::class.java) ?: 0
        for (t in tables) {
            if (!ident.matches(t)) continue
            n += jdbc.queryForObject("SELECT count(*) FROM $t WHERE is_demo", Int::class.java) ?: 0
        }
        return n
    }

    fun status(): Map<String, Any?> {
        val roles = DemoWorld.ROLES.map { linkedMapOf("key" to it.key, "role" to it.bn, "email" to DemoWorld.demoEmail(it.key)) }
        val st = jdbc.queryForList("SELECT partial, at FROM demo_state").firstOrNull() ?: return linkedMapOf("active" to false, "roles" to roles)
        return linkedMapOf(
            "active" to true, "partial" to (st["partial"] == true), "at" to (st["at"] as java.sql.Timestamp).time, "docs" to docCount(), "roles" to roles,
        )
    }

    /** demo/index.js makeCtx */
    private inner class Ctx(override val nowMs: Long, private val users: Map<String, DemoUser>) : DemoSeedContext {
        val extraUids = LinkedHashSet<String>()

        override fun user(key: String): DemoUser = users[key] ?: throw IllegalStateException("demo user $key missing")

        override fun extraUser(key: String, profile: Map<String, Any?>): String {
            val uid = DemoWorld.uidOf("x-$key")
            if (extraUids.add(uid)) {
                try {
                    identity.createUser(null, false, uid, (profile["name"] as? String) ?: key)
                } catch (e: IdentityException) {
                    if (e.kind != IdentityException.Kind.UID_EXISTS) throw e
                }
            }
            jdbc.update("INSERT INTO accounts (uid) VALUES (?) ON CONFLICT (uid) DO NOTHING", uid)
            jdbc.update("INSERT INTO demo_accounts (uid, email, role_key) VALUES (?, NULL, NULL) ON CONFLICT (uid) DO NOTHING", uid)
            // users/{uid} set: the profile is replaced
            jdbc.update("DELETE FROM user_profiles WHERE uid = ?", uid)
            val cols = profile.keys.toList()
            require(cols.all { ident.matches(it) }) { "bad profile column" }
            jdbc.update(
                "INSERT INTO user_profiles (uid${cols.joinToString("") { ", $it" }}) VALUES (?${cols.joinToString("") { ", ?" }})",
                *(listOf<Any?>(uid) + cols.map { profile[it] }).toTypedArray(),
            )
            return uid
        }

        override fun track(table: String, key: Map<String, Any?>) {
            require(ident.matches(table)) { "bad table $table" }
            jdbc.update(
                "INSERT INTO demo_manifest_rows (table_name, row_key) VALUES (?, ?::jsonb)",
                table, com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(key),
            )
        }
    }

    companion object {
        const val BUDGET_MS = 35_000L
    }
}

/** `admin_demo_seed` (body.step; none = everything at once), `admin_demo_wipe`, `admin_demo_status` — owner only */
@Component
class DemoActions(private val seeder: DemoSeeder, private val admins: AdminService) : ActionModule {
    override fun actions(): Map<String, ActionHandler> = mapOf(
        "admin_demo_seed" to ActionHandler { ctx ->
            owner(ctx)
            val step = ctx.body.int("step")
            if (step == null) {
                seeder.seedAll()
            } else {
                val steps = seeder.stepCount()
                if (step < 0 || step >= steps) throw ApiException(400, "ধাপ ঠিক নেই")
                LinkedHashMap(seeder.seedStep(step.toInt())).apply { put("steps", steps) }
            }
        },
        "admin_demo_wipe" to ActionHandler { ctx ->
            owner(ctx)
            seeder.wipe()
        },
        "admin_demo_status" to ActionHandler { ctx ->
            owner(ctx)
            seeder.status()
        },
    )

    private fun owner(ctx: ActionContext) {
        if (!admins.isOwner(ctx.user)) throw ApiException(403, "শুধু মালিক")
    }
}
