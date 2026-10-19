package com.lifolo.admin.kernel

import com.lifolo.common.Geo
import com.lifolo.demo.DemoWorld

/**
 * account.js schedule / runDue / deleteNow, split per package. Every package adds one bean for its own data;
 * P6 runs them (in [order]) when an account deletion is scheduled (`delete_account`) and when it is due
 * (daily job, 30 days later). Steps must not throw for missing data; P6 logs a failing step and continues.
 */
interface AccountDeletionStep {
    /** run order (lower first); use 100·k + i for package k */
    val order: Int

    /**
     * At `delete_account` (30-day grace starts): hide what others can see — listings get `status = 'suspended'`
     * with the old status in `hidden_for_deletion` (DeletionService.cancel restores it on the next login).
     */
    fun hide(uid: String) {}

    /**
     * When the grace period is over: notify counterparties first (donors of open requests, patients with
     * appointments, …), then delete or anonymise the package's rows. The `accounts` row is deleted by P6 afterwards
     * (FK cascades). [email] is the account email (sanctions are kept by email — [Sanctions.keepForEmail]).
     */
    fun deleteNow(uid: String, email: String?) {}
}

/** a login-able demo account (demo/index.js ROLES): uid `demo-{key}`, email `{key}@lifolo-demo.test` */
data class DemoUser(val uid: String, val email: String, val name: String)

/** a demo place: [lat]/[lng] rounded to 5 decimals + the 10-char geohash */
data class DemoPoint(val lat: Double, val lng: Double, val geohash: String)

/**
 * What a [DemoSeedPart] gets (demo/index.js makeCtx). P6 implements it (admin_demo_seed); the parts insert their
 * rows directly with their own services/SQL.
 */
interface DemoSeedContext {
    /** Date.now() at the start of the seed run (ms) */
    val nowMs: Long

    val dayMs: Long get() = 86_400_000L

    /** ctx.u.<key> — patient, donor, doctor, assistant, pharmacy, lab, ambulance, bloodbank, homecare, caregiver */
    fun user(key: String): DemoUser

    /**
     * ctx.extraUser — a background person without login (e.g. donors around the patient): uid `demo-x-{key}`,
     * an `accounts` row (+ Auth account) and a `user_profiles` row from [profile] (column → value). Returns the uid.
     */
    fun extraUser(key: String, profile: Map<String, Any?> = emptyMap()): String

    /** ctx.near(dx, dy) — [DemoSeed.near] */
    fun near(dxKm: Double = 0.0, dyKm: Double = 0.0): DemoPoint = DemoSeed.near(dxKm, dyKm)

    /**
     * A seeded row that is **not owned by a demo account** (e.g. a hospital, an emergency created by an admin uid):
     * recorded in `demo_manifest_rows` so the wipe deletes it. [key] = primary key columns → values.
     * Rows owned by `demo-…` accounts need no tracking (deleting the demo accounts removes them).
     */
    fun track(table: String, key: Map<String, Any?>)
}

/**
 * One part of the demo world (demo/blood.js, services.js, books.js, rx.js, health.js). [step] orders the parts
 * (admin_demo_seed runs one step per request); [label] is shown in the panel while it runs.
 */
interface DemoSeedPart {
    val step: Int
    val label: String

    fun seed(ctx: DemoSeedContext)
}

object DemoSeed {
    /** demo/index.js CENTER: Gazipur Sadar (Joydebpur) */
    const val CENTER_LAT = 23.999
    const val CENTER_LNG = 90.42

    /** demo/index.js near(dxKm east, dyKm north): rounded like `+(…).toFixed(5)` */
    fun near(dxKm: Double = 0.0, dyKm: Double = 0.0): DemoPoint {
        val lat = round5(CENTER_LAT + dyKm / 111)
        val lng = round5(CENTER_LNG + dxKm / (111 * Math.cos(CENTER_LAT * Math.PI / 180)))
        return DemoPoint(lat, lng, Geo.hash(lat, lng)!!)
    }

    private fun round5(v: Double): Double = java.math.BigDecimal(v).setScale(5, java.math.RoundingMode.HALF_UP).toDouble()

    fun uidOf(key: String): String = DemoWorld.uidOf(key)
}
