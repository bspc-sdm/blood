package com.lifolo.ambulance

import com.lifolo.admin.kernel.AccountDeletionStep
import com.lifolo.admin.kernel.ListingReview
import com.lifolo.admin.kernel.ListingStats
import com.lifolo.auth.AdminService
import com.lifolo.common.Js
import com.lifolo.common.Places
import com.lifolo.common.action.ActionContext
import com.lifolo.common.action.ActionHandler
import com.lifolo.common.action.ActionModule
import com.lifolo.jobs.DailyTask
import org.jobrunr.jobs.annotations.Job
import org.jobrunr.jobs.annotations.Recurring
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * The 24 ambulance actions (ambulance.js, ambreq.js, ambplace.js, sellerstats.js ambulance_stats).
 * `ambulances` is public (guests); everything else needs a login (the dispatcher answers 401 before).
 */
@Component
class AmbulanceActions(
    private val ambulances: AmbulanceService,
    private val reqs: AmbReqService,
    private val places: AmbPlaces,
    private val review: ListingReview,
    private val admins: AdminService,
) : ActionModule {
    override fun actions(): Map<String, ActionHandler> = mapOf(
        "ambulances" to ActionHandler { ctx -> ambulances.list(ctx.lang, ctx.body, ctx.user.uid) },
        "ambulance_mine" to ActionHandler { ctx -> ambulances.mine(ctx.user.id) },
        "ambulance_save" to ActionHandler { ctx -> ambulances.save(ctx.lang, ctx.body, ctx.user.id, ctx.user.email) },
        "ambulance_available" to ActionHandler { ctx -> ambulances.available(ctx.lang, ctx.body, ctx.user.id) },
        "ambulance_delete" to ActionHandler { ctx -> ambulances.delete(ctx.lang, ctx.body, ctx.user.id) },
        "ambulance_called" to ActionHandler { ctx -> ambulances.called(ctx.body, ctx.user.id) },
        "ambulance_rate" to ActionHandler { ctx -> ambulances.rate(ctx.lang, ctx.body, ctx.user.id) },
        "ambulance_stats" to ActionHandler { ctx -> ambulances.ownerStats(ctx.user.id) },
        "admin_ambulances" to ActionHandler { ctx ->
            requireAdmin(ctx)
            ambulances.adminList(ctx.body)
        },
        "admin_ambulance_resolve" to ActionHandler { ctx ->
            requireAdmin(ctx)
            // app: approve; web panel: status (also suspend / reinstate) with a reason
            review.resolve(ctx, "ambulance", "ambulances", ctx.body.string("id"))
        },
        "ambreq_create" to ActionHandler { ctx -> reqs.create(ctx.lang, ctx.body, ctx.user.id) },
        "ambreq_count" to ActionHandler { ctx -> reqs.count(ctx.lang, ctx.body, ctx.user.id) },
        "ambreq_status" to ActionHandler { ctx -> reqs.status(ctx.lang, ctx.body, ctx.user.id) },
        "ambreq_cancel" to ActionHandler { ctx -> reqs.cancel(ctx.lang, ctx.body, ctx.user.id) },
        "ambreq_driver" to ActionHandler { ctx -> reqs.driverList(ctx.body, ctx.user.id) },
        "ambreq_accept" to ActionHandler { ctx -> reqs.accept(ctx.lang, ctx.body, ctx.user.id) },
        "ambreq_agree" to ActionHandler { ctx -> reqs.agree(ctx.lang, ctx.body, ctx.user.id) },
        "ambreq_budget" to ActionHandler { ctx -> reqs.budget(ctx.lang, ctx.body, ctx.user.id) },
        "ambreq_decline" to ActionHandler { ctx -> reqs.decline(ctx.lang, ctx.body, ctx.user.id) },
        "ambreq_step" to ActionHandler { ctx -> reqs.step(ctx.lang, ctx.body, ctx.user.id) },
        "ambreq_byphone" to ActionHandler { ctx -> reqs.byPhoneAction(ctx.lang, ctx.body, ctx.user.id) },
        "ambreq_replace" to ActionHandler { ctx -> reqs.replace(ctx.lang, ctx.body, ctx.user.id) },
        "amb_facilities" to ActionHandler(::facilities),
        "maps_link_resolve" to ActionHandler(::mapsLinkResolve),
    )

    private fun requireAdmin(ctx: ActionContext) {
        if (!admins.isAdmin(ctx.user)) throw ctx.err(403, "Admins only", "শুধু অ্যাডমিন")
    }

    /**
     * amb_facilities — "where to go" (patient) and the hospital rows of an owner's fare chart. body: district, q (search,
     * whole country), lat/lng (patient's place — nearest first).
     */
    private fun facilities(ctx: ActionContext): Any {
        val b = ctx.body
        val district = b.string("district")
        if (!Places.isDistrict(district)) throw ctx.err(400, "Choose a district", "জেলা বেছে নিন")
        val lat = b.number("lat")
        val lng = b.number("lng")
        val q = b["q"]
        if (!Js.truthy(q) && (lat == null || lng == null)) return places.districtFacilities(district!!)
        return places.facilities(district, b.string("q") ?: "", lat, lng)
    }

    /**
     * maps_link_resolve — a Google Maps link (or shared text) → {lat, lng}. Long links are read here; short links only
     * maps.app.goo.gl / goo.gl/maps, following Google redirects (per-user limit).
     */
    private fun mapsLinkResolve(ctx: ActionContext): Any {
        val text = ctx.body.string("url") ?: ""
        val bad = {
            ctx.err(
                400, "Couldn't find a place in this link — in Google Maps, press and hold the place, then Share.",
                "এই লিংকে জায়গা পাওয়া যায়নি — Google ম্যাপে জায়গাটা চেপে ধরে \"শেয়ার\" চাপুন।",
            )
        }
        val p = AmbPlaces.parseMapsText(text) ?: throw bad()
        p.point?.let { return linkedMapOf("lat" to it.first, "lng" to it.second) }
        if (!places.checkLimit(ctx.user.id)) throw ctx.err(429, "Too many links. Wait a minute and try again.", "অনেকগুলো লিংক। এক মিনিট পর আবার চেষ্টা করুন।")
        val q = places.resolveShort(p.short!!) ?: throw bad()
        return linkedMapOf("lat" to q.first, "lng" to q.second)
    }
}

/** daily step 12 — "still available?" reminders (with the backfill of old rows) */
@Component
class AmbulanceRemindersTask(private val ambulances: AmbulanceService) : DailyTask {
    override val name = "ambulanceReminders"
    override val order = 12

    override fun run(nowMs: Long): Any = ambulances.remindAvailable(nowMs, backfill = true)
}

/** daily step 13 — lingering requests advanced/closed, exact places etc. removed 24 h after closing */
@Component
class AmbulanceRequestsTask(private val reqs: AmbReqService) : DailyTask {
    override val name = "ambulanceRequests"
    override val order = 13

    override fun run(nowMs: Long): Any = reqs.dailyCleanup(nowMs)
}

/**
 * Timers on time (PHASE2-PLAN P5): Node advances requests only when someone reads them (the patient's app polls).
 * This JobRunr job also advances open requests every minute — widen/expiry/lapsed-offer pushes go out even when
 * nobody is reading — and runs the throttled "still available?" search (Node: on list reads).
 */
@Component
class AmbulanceJobs(private val reqs: AmbReqService, private val ambulances: AmbulanceService) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Recurring(id = "ambulance-timers", cron = "* * * * *", zoneId = "UTC")
    @Job(name = "Ambulance request timers")
    fun minute() {
        try {
            reqs.advanceOpen(AmbClock.now())
        } catch (e: Exception) {
            log.error("ambulance timers failed", e)
        }
        ambulances.remindInBackground()
    }
}

/**
 * Account deletion (account.js schedule / deleteNow, ambulance parts):
 *  - hide: own ambulances suspended (old status kept for a cancelled deletion), active requests closed (patient or driver)
 *  - deleteNow: own ratings taken out of the averages and the owners' recent comments, call records, own ambulances
 *    (+ their statistics and photos), all own requests
 */
@Component
class AmbulanceDeletionStep(
    private val jdbc: JdbcTemplate,
    private val reqs: AmbReqService,
    private val stats: ListingStats,
    private val verify: com.lifolo.admin.kernel.VerifyDocs,
) : AccountDeletionStep {
    private val log = LoggerFactory.getLogger(javaClass)

    override val order = 500

    override fun hide(uid: String) {
        jdbc.update(
            """
            UPDATE ambulances SET hidden_for_deletion = coalesce(status, 'pending'), status = 'suspended'
            WHERE owner_uid = ? AND status <> 'suspended' AND hidden_for_deletion IS NULL
            """.trimIndent(),
            uid,
        )
        try {
            reqs.closeForUser(uid, full = false)
        } catch (e: Exception) {
            log.error("amb req close for deletion failed: {}", e.message)
        }
    }

    override fun deleteNow(uid: String, email: String?) {
        // own ratings out of the averages and out of the owners' "recent comments"
        val rated = jdbc.query(
            "SELECT ambulance_id, rating FROM ambulance_calls WHERE uid = ? AND rating > 0 LIMIT 1000",
            { rs, _ -> rs.getString(1) to rs.getInt(2) },
            uid,
        )
        for ((id, rating) in rated) {
            runCatching { jdbc.update("UPDATE ambulances SET rating_sum = rating_sum - ?, rating_count = rating_count - 1 WHERE id = ?", rating, id) }
            stats.removeRecentRating("ambulance", id, uid)
        }
        jdbc.update("DELETE FROM ambulance_calls WHERE uid = ?", uid)
        val mine = jdbc.queryForList("SELECT id FROM ambulances WHERE owner_uid = ?", String::class.java, uid)
        for (id in mine) {
            stats.delete("ambulance", id)
            runCatching { verify.delete("ambulance", id) }
        }
        jdbc.update("DELETE FROM ambulances WHERE owner_uid = ?", uid)
        try {
            reqs.closeForUser(uid, full = true)
        } catch (e: Exception) {
            log.error("amb req cleanup failed: {}", e.message)
        }
    }
}
