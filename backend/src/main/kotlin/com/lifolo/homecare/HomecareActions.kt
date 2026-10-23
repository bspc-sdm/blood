package com.lifolo.homecare

import com.fasterxml.jackson.core.type.TypeReference
import com.lifolo.admin.kernel.Catalog
import com.lifolo.admin.kernel.ListingStats
import com.lifolo.admin.kernel.Moderation
import com.lifolo.appointments.CareRules
import com.lifolo.appointments.HomeLoc
import com.lifolo.appointments.HomePlace
import com.lifolo.appointments.P2Cache
import com.lifolo.appointments.P2Clock
import com.lifolo.appointments.ProviderDoc
import com.lifolo.appointments.ProviderStore
import com.lifolo.common.Js
import com.lifolo.common.Phones
import com.lifolo.common.Places
import com.lifolo.common.Rules
import com.lifolo.common.Sql
import com.lifolo.common.StoredFiles
import com.lifolo.common.action.ActionContext
import com.lifolo.common.action.ActionHandler
import com.lifolo.common.action.ActionModule
import com.lifolo.demo.DemoWorld
import com.lifolo.practice.Practice
import com.lifolo.practice.PracticeGate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/** one cached home-care professional: the provider document, their location and card photo */
data class HomeEntry(val doc: ProviderDoc = ProviderDoc(), val loc: HomeLoc = HomeLoc(), val thumb: String = "")

/**
 * homecare.js — doctors and nurses who visit patients at home. No request/accept in the app: the patient sees the
 * list and calls directly; the professional sees their own call count, ratings and comments.
 * homeCalls/{callerUid}_{providerUid} → `home_calls`; homeStats → `listing_stats*` kind `home`.
 */
@Component
class HomecareActions(
    private val jdbc: JdbcTemplate,
    private val store: ProviderStore,
    private val cache: P2Cache,
    private val clock: P2Clock,
    private val catalog: Catalog,
    private val stats: ListingStats,
    private val moderation: Moderation,
    private val files: StoredFiles,
    private val gate: PracticeGate,
    txm: PlatformTransactionManager,
) : ActionModule {
    private val tx = TransactionTemplate(txm)

    override fun actions(): Map<String, ActionHandler> = mapOf(
        "home_providers" to ActionHandler(::homeProviders),
        "home_called" to ActionHandler(::homeCalled),
        "home_rate" to ActionHandler(::homeRate),
        "home_stats" to ActionHandler(::homeStats),
    )

    companion object {
        /** + physio (PRACTICE.md; psychologists do no home visits) */
        val TYPES = listOf("doctor", "nurse", "physio")

        /** rating within 30 days of the call */
        const val RATE_WINDOW_MS = 30L * 24 * 3600 * 1000

        /** not right after pressing call: at least 1 hour after the first call */
        const val RATE_AFTER_MS = 60L * 60 * 1000
        const val CALL_DEDUPE_MS = 10L * 60 * 1000

        fun inBangladesh(lat: Double?, lng: Double?): Boolean =
            lat != null && lng != null && lat > 20.3 && lat < 26.8 && lng > 87.9 && lng < 92.8

        /** whole km rounded up, at least 2 ("within 2 km") */
        fun approxKm(km: Double?): Int? = if (km == null || !km.isFinite()) null else maxOf(2, Math.ceil(km).toInt())
    }

    private class Call(val at: Long, val firstAt: Long, val rating: Int)

    private fun call(uid: String, providerUid: String, forUpdate: Boolean = false): Call? = jdbc.query(
        "SELECT at, first_at, rating FROM home_calls WHERE uid = ? AND provider_uid = ?" + (if (forUpdate) " FOR UPDATE" else ""),
        { rs, _ -> Call(rs.getTimestamp(1).time, rs.getTimestamp(2).time, rs.getInt(3)) },
        uid, providerUid,
    ).firstOrNull()

    /** what the list shows (not the professional's location, only a rough distance) */
    private fun view(d: ProviderDoc, km: Double?, mine: Call?, thumb: String, now: Long, systemsOn: Boolean): Map<String, Any?> {
        val h = d.homeVisit
        val n = d.ratingCount
        return linkedMapOf<String, Any?>(
            "uid" to d.uid,
            "type" to d.type,
            "name" to d.name,
            "specialties" to d.specialties,
            "degrees" to d.degrees,
            "gender" to d.gender,
            "services" to (h?.services ?: emptyList<String>()),
            "radiusKm" to (h?.radiusKm ?: 5),
            "upazilaIds" to (h?.upazilaIds ?: emptyList<Int>()),
            "schedule" to (h?.schedule ?: ""),
            "times" to (h?.times?.map { it.toMap() } ?: emptyList<Any>()),
            "fee" to (h?.fee ?: ""),
            "noFee" to (h?.noFee ?: false),
            "phone" to Phones.fixPhone(h?.phone),
            // coarse (whole km, at least 2): a home cannot be found by comparing distances from a few places
            "km" to approxKm(km),
            "rating" to (if (n != 0) Js.round(d.ratingSum.toDouble() / n * 10) / 10 else 0),
            "ratingCount" to n,
            // called from the app before, and the own rating
            "called" to (mine != null),
            "myRating" to (mine?.rating ?: 0),
            // rating allowed: from 1 hour after the first call, within 30 days of the last
            "canRate" to (mine != null && now - mine.firstAt >= RATE_AFTER_MS && now - mine.at <= RATE_WINDOW_MS),
            "photo" to thumb,
        ).apply { putAll(Practice.labels(d.type, d.system, systemsOn)) }
    }

    /** the whole list of one type (location and badge checked), 2 minutes in the cache */
    private fun homeList(type: String): List<HomeEntry> =
        cache.cached(P2Cache.homeKey(type), P2Cache.TWO_MINUTES, object : TypeReference<List<HomeEntry>>() {}) {
            // earlier versions switched it on without a separate phone — not listed until they add one
            val docs = store.loadWhere(
                "p.type = ? AND p.home_visit_enabled AND NOT p.home_visit_blocked AND COALESCE(p.home_phone, '') <> '' " +
                    "AND EXISTS (SELECT 1 FROM badges b WHERE b.uid = p.uid AND b.status = 'approved' AND b.type = ?) ORDER BY p.uid LIMIT 1000",
                type, type,
            )
            val locs = HashMap<String, Pair<HomeLoc, String>>()
            val thumbKeys = HashMap<String, String>()
            for (chunk in docs.map { it.uid }.chunked(1000)) {
                jdbc.query(
                    "SELECT uid, lat, lng, location_source, place_name, photo_thumb, photo_thumb_key FROM user_profiles WHERE uid IN (${chunk.joinToString(",") { "?" }})",
                    RowCallbackHandler { rs ->
                        val lat = rs.getDouble("lat").let { if (rs.wasNull()) null else it }
                        val lng = rs.getDouble("lng").let { if (rs.wasNull()) null else it }
                        val uid = rs.getString("uid")
                        val thumb = rs.getString("photo_thumb")
                        if (thumb == null) rs.getString("photo_thumb_key")?.let { thumbKeys[uid] = it }
                        locs[uid] = HomeLoc(lat, lng, rs.getString("location_source"), rs.getString("place_name")) to (thumb ?: "")
                    },
                    *chunk.toTypedArray(),
                )
            }
            // thumbs on R2 (V602); one that cannot be read shows like no photo
            if (thumbKeys.isNotEmpty()) {
                val texts = files.texts(thumbKeys.values)
                for ((uid, key) in thumbKeys) locs[uid]?.let { (loc, _) -> locs[uid] = loc to (texts[key] ?: "") }
            }
            docs.map { d -> locs[d.uid].let { HomeEntry(d, it?.first ?: HomeLoc(), it?.second ?: "") } }
        }

    /** the list: type doctor / nurse; place: lat+lng (own GPS) or district (+ upazilaId); filters: service, female */
    fun homeProviders(ctx: ActionContext): Any {
        val b = ctx.body
        val type = b.string("type")
        if (type == null || type !in TYPES) throw ctx.err(400, "Choose doctor or nurse", "ডাক্তার বা নার্স বেছে নিন")
        gate.requireType(ctx, type)
        val systemsOn = gate.systemsOn(ctx)
        val lat = b.number("lat")
        val lng = b.number("lng")
        val gps = inBangladesh(lat, lng)
        val district = b.string("district")?.takeIf { Places.isDistrict(it) }
        val upazilaId = b.int("upazilaId")?.let { id -> Places.UPAZILA.keys.firstOrNull { it.toLong() == id } }
        val place = HomePlace(if (gps) lat else null, if (gps) lng else null, district, upazilaId)
        val service = b.string("service")?.takeIf { it in CareRules.homeServicesFor(type, catalog) }
        val female = b.isTrue("female")
        val uid = ctx.user.uid
        class Found(val e: HomeEntry, val km: Double?)
        val found = ArrayList<Found>()
        for (e in homeList(type)) {
            val x = e.doc
            if (x.uid == uid) continue
            if (service != null && service !in (x.homeVisit?.services ?: emptyList())) continue
            if (female && x.gender != "female") continue
            // practice_systems off for the viewer: non-allopathic doctors are not listed
            if (!systemsOn && Practice.freeText(x.type, x.system)) continue
            val m = CareRules.homeMatch(x, e.loc, place) ?: continue
            found.add(Found(e, m.km))
        }
        if (found.isEmpty()) return mapOf("items" to emptyList<Any>())
        // nearest first; unknown distance: more ratings first
        val sorted = found.sortedWith(
            Comparator { p, q ->
                val a = approxKm(p.km) ?: 999
                val c = approxKm(q.km) ?: 999
                if (a != c) a - c else q.e.doc.ratingCount - p.e.doc.ratingCount
            },
        )
        val top = sorted.take(100)
        // guests: no own calls/ratings
        val mine = HashMap<String, Call>()
        if (uid != null) {
            jdbc.query(
                "SELECT provider_uid, at, first_at, rating FROM home_calls WHERE uid = ? AND provider_uid IN (${top.joinToString(",") { "?" }})",
                RowCallbackHandler { rs -> mine[rs.getString(1)] = Call(rs.getTimestamp(2).time, rs.getTimestamp(3).time, rs.getInt(4)) },
                uid, *top.map { it.e.doc.uid }.toTypedArray(),
            )
        }
        val now = clock.now()
        return mapOf("items" to top.map { view(it.e.doc, it.km, mine[it.e.doc.uid], it.e.thumb, now, systemsOn) })
    }

    /** the patient pressed call in the app (for the professional's statistics; a failure never blocks the call) */
    fun homeCalled(ctx: ActionContext): Any {
        val me = ctx.user.id
        val uid = ctx.body.string("uid")
        if (!Rules.isValidId(uid) || uid == me) return mapOf("ok" to true)
        uid!!
        val p = store.load(uid)
        if (p == null || p.homeVisit?.enabled != true) return mapOf("ok" to true)
        // demo: separate — calls across worlds are not counted
        if (!DemoWorld.sameWorld(me, uid)) return mapOf("ok" to true)
        val prev = call(me, uid)
        val now = clock.now()
        jdbc.update(
            """
            INSERT INTO home_calls (uid, provider_uid, at, first_at) VALUES (?, ?, ?, ?)
            ON CONFLICT (uid, provider_uid) DO UPDATE SET at = EXCLUDED.at
            """.trimIndent(),
            me, uid, Sql.ts(now), Sql.ts(now),
        )
        // the same person again within 10 minutes is not a new call
        if (prev == null || now - prev.at > CALL_DEDUPE_MS) stats.bumpCall("home", uid, now, prev == null)
        return mapOf("ok" to true)
    }

    /** rating after a call from the app (one per person; can be changed) */
    fun homeRate(ctx: ActionContext): Any {
        val me = ctx.user.id
        val b = ctx.body
        val uid = b.string("uid")
        val rating = b.int("rating") ?: 0L
        if (!Rules.isValidId(uid) || rating < 1 || rating > 5) throw ctx.err(400, "Choose 1 to 5 stars", "১ থেকে ৫ তারা দিন")
        uid!!
        // demo: separate
        if (!DemoWorld.sameWorld(me, uid)) throw ctx.err(404, "Not found", "পাওয়া যায়নি")
        val comment = CareRules.str(b["comment"], 300)
        val now = clock.now()
        tx.executeWithoutResult {
            val c = call(me, uid, forUpdate = true)
            val exists = jdbc.queryForList("SELECT 1 FROM providers WHERE uid = ? FOR UPDATE", uid).isNotEmpty()
            if (!exists) throw ctx.err(404, "Not found", "পাওয়া যায়নি")
            if (c == null || now - c.at > RATE_WINDOW_MS) {
                throw ctx.err(403, "You can rate after calling from the app", "অ্যাপ থেকে ফোন করার পর রেটিং দিতে পারবেন")
            }
            if (now - c.firstAt < RATE_AFTER_MS) {
                throw ctx.err(403, "You can rate 1 hour after your first call", "প্রথম ফোনের ১ ঘণ্টা পর রেটিং দিতে পারবেন")
            }
            val old = c.rating
            jdbc.update(
                "UPDATE providers SET rating_sum = rating_sum + ?, rating_count = rating_count + ? WHERE uid = ?",
                rating.toInt() - old, if (old != 0) 0 else 1, uid,
            )
            jdbc.update(
                "UPDATE home_calls SET rating = ?, comment = ?, rated_at = ? WHERE uid = ? AND provider_uid = ?",
                rating.toInt(), comment, Sql.ts(now), me, uid,
            )
        }
        stats.addRecentRating("home", uid, me, rating.toInt(), comment)
        return mapOf("ok" to true)
    }

    /** the professional's own statistics */
    fun homeStats(ctx: ActionContext): Any {
        val uid = ctx.user.id
        val p = store.load(uid)
        val reports = moderation.reportCount("providers/$uid")
        val n = p?.ratingCount ?: 0
        val out = LinkedHashMap<String, Any?>()
        out["enabled"] = p?.homeVisit?.enabled == true
        out["blocked"] = p?.homeVisitBlocked == true
        out.putAll(stats.summary("home", uid, clock.now()))
        out["rating"] = if (n != 0) Js.round(p!!.ratingSum.toDouble() / n * 10) / 10 else 0
        out["ratingCount"] = n
        // who gave them is not shown (patient privacy)
        out["comments"] = stats.recentComments("home", uid)
        out["reports"] = reports
        return out
    }
}
