package com.lifolo.ambulance

import com.fasterxml.jackson.databind.JsonNode
import com.lifolo.admin.kernel.Catalog
import com.lifolo.admin.kernel.ListingReview
import com.lifolo.admin.kernel.ListingRules
import com.lifolo.admin.kernel.ListingStats
import com.lifolo.admin.kernel.Moderation
import com.lifolo.admin.kernel.Sanctions
import com.lifolo.admin.kernel.VerifyDocs
import com.lifolo.common.ApiException
import com.lifolo.common.Body
import com.lifolo.common.Ids
import com.lifolo.common.Js
import com.lifolo.common.Lang
import com.lifolo.common.Phones
import com.lifolo.common.Places
import com.lifolo.common.QuotaService
import com.lifolo.common.Rules
import com.lifolo.common.Sql
import com.lifolo.common.SqlSet
import com.lifolo.common.intOrNull
import com.lifolo.common.ms
import com.lifolo.demo.DemoWorld
import com.lifolo.notify.PushItem
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * ambulance.js (+ the ambulance part of sellerstats.js) — owners/drivers add their ambulances, an admin verifies and
 * approves, patients' people find them by area and call directly. "Available now" lasts 12 hours; one hour before
 * it ends the driver is asked "still available?".
 */
@Service
class AmbulanceService(
    private val jdbc: JdbcTemplate,
    private val repo: AmbRepo,
    private val reqs: AmbReqService,
    private val places: AmbPlaces,
    private val catalog: Catalog,
    private val verify: VerifyDocs,
    private val sanctions: Sanctions,
    private val moderation: Moderation,
    private val stats: ListingStats,
    private val quotas: QuotaService,
    private val pushes: AmbPush,
    private val redis: StringRedisTemplate,
    private val review: ListingReview,
    txm: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val txt = TransactionTemplate(txm)
    private val background = Executors.newVirtualThreadPerTaskExecutor()

    @Suppress("UNCHECKED_CAST")
    private fun <T> tx(block: () -> T): T = txt.execute(TransactionCallback { block() }) as T

    /** the last background reminder search (tests wait for it — Node remindIdle) */
    @Volatile
    internal var lastRemind: Future<*> = CompletableFuture.completedFuture(0)

    private fun err(lang: String?, status: Int, en: String, bn: String, code: String? = null) = ApiException(status, Lang.tr(lang, en, bn), code)

    private fun notFound(lang: String?, status: Int = 404) = err(lang, status, "Not found", "পাওয়া যায়নি")

    // ------------------------------------------------------------------ "still available?"

    /**
     * Old rows (available before remindAt existed): approved, available cars inside the 12 h get remindAt. Daily job only.
     */
    private fun backfillRemindAt(now: Long): Int = jdbc.update(
        """
        UPDATE ambulances SET remind_at = available_at + ? * interval '1 millisecond'
        WHERE id IN (SELECT id FROM ambulances WHERE available_at >= ? ORDER BY id LIMIT 1000)
          AND status = 'approved' AND available AND remind_at IS NULL
          AND coalesce(reminder_sent_at, 'epoch'::timestamptz) < available_at
        """.trimIndent(),
        AmbRules.REMIND_AT_MS, Sql.ts(now - AmbRules.AVAILABLE_MS),
    )

    /**
     * ambulance.js remindAvailable: one push to the owner of every car in the last hour of its 12 h "available".
     * Every row read loses its remindAt (sent or not), so it is never read twice; a row lock makes only one server
     * send. Returns how many cars it was sent for. [backfill]: the daily job first sets remindAt on old rows.
     */
    fun remindAvailable(now: Long, backfill: Boolean = false): Int {
        if (backfill) backfillRemindAt(now)
        val claimed = ArrayList<Amb>()
        var lastAt: Long? = null
        var lastId = ""
        for (page in 0 until AmbRules.REMIND_MAX_PAGES) {
            val ids = if (lastAt == null) {
                jdbc.query(
                    "SELECT id, remind_at FROM ambulances WHERE remind_at <= ? ORDER BY remind_at, id COLLATE \"C\" LIMIT ${AmbRules.REMIND_PAGE}",
                    { rs, _ -> rs.getString(1) to (rs.ms("remind_at") ?: 0L) },
                    Sql.ts(now),
                )
            } else {
                jdbc.query(
                    """
                    SELECT id, remind_at FROM ambulances WHERE remind_at <= ? AND (remind_at > ? OR (remind_at = ? AND id COLLATE "C" > ?))
                    ORDER BY remind_at, id COLLATE "C" LIMIT ${AmbRules.REMIND_PAGE}
                    """.trimIndent(),
                    { rs, _ -> rs.getString(1) to (rs.ms("remind_at") ?: 0L) },
                    Sql.ts(now), Sql.ts(lastAt), Sql.ts(lastAt), lastId,
                )
            }
            if (ids.isEmpty()) break
            lastAt = ids.last().second
            lastId = ids.last().first
            for ((id, _) in ids) {
                try {
                    val sent: Amb? = tx {
                        val a = repo.get(id, lock = true) ?: return@tx null
                        if (a.remindAt == null || a.remindAt > now) return@tx null
                        val send = a.status == "approved" && a.available && AmbRules.availableNow(a, now)
                        jdbc.update(
                            "UPDATE ambulances SET remind_at = NULL${if (send) ", reminder_sent_at = ?" else ""} WHERE id = ?",
                            *(if (send) arrayOf<Any?>(Sql.ts(now), id) else arrayOf<Any?>(id)),
                        )
                        if (send) a else null
                    }
                    if (sent != null) claimed.add(sent)
                } catch (e: Exception) {
                    log.warn("ambulance remind failed: {}", e.message)
                }
            }
            if (ids.size < AmbRules.REMIND_PAGE) break
        }
        if (claimed.isEmpty()) return 0
        val langs = pushes.langs(claimed.map { it.ownerUid })
        val items = claimed.filter { it.ownerUid in langs }.map { a ->
            val lang = langs[a.ownerUid]
            PushItem(
                a.ownerUid,
                Lang.pick(lang, "${a.name} — still available?", "${a.name} — এখনো খালি আছেন?"),
                Lang.pick(lang, "If yes, open and tap \"Keep available\".", "হ্যাঁ হলে খুলে \"আবার চালু রাখুন\" চাপুন।"),
                // app/web: "My work" › my ambulance
                linkedMapOf("type" to "services", "kind" to "ambulance", "ambulanceId" to a.id),
            )
        }
        if (items.isNotEmpty()) {
            try {
                pushes.sendItems(items)
            } catch (e: Exception) {
                log.error("ambulance remind push failed: {}", e.message)
            }
        }
        return claimed.size
    }

    /**
     * Reading the list also looks for due reminders — in the background, the list does not wait (every second counts
     * in an emergency). At most every 10 minutes (Node: per server in memory; here one Redis key for all servers).
     */
    fun remindInBackground() {
        val first = try {
            redis.opsForValue().setIfAbsent(REMIND_KEY, "1", Duration.ofMillis(AmbRules.REMIND_EVERY_MS)) == true
        } catch (e: Exception) {
            log.warn("remind throttle unavailable: {}", e.message)
            false
        }
        if (!first) return
        lastRemind = background.submit(Runnable {
            try {
                remindAvailable(AmbClock.now())
            } catch (e: Exception) {
                log.error("ambulance remind failed: {}", e.message)
            }
        })
    }

    // ------------------------------------------------------------------ the public list

    /** the caller's call records: ambulanceId → (at, firstAt, tripAt) */
    private class CallRec(val ambulanceId: String, val at: Long?, val firstAt: Long?, val tripAt: Long?)

    private fun canRateIds(uid: String?, now: Long): Set<String> {
        if (uid == null) return emptySet()
        return jdbc.query(
            "SELECT ambulance_id, at, first_at, trip_at FROM ambulance_calls WHERE uid = ? LIMIT 200",
            { rs, _ -> CallRec(rs.getString(1), rs.ms("at"), rs.ms("first_at"), rs.ms("trip_at")) },
            uid,
        ).filter { AmbRules.rateable(it.at, it.firstAt, it.tripAt, now) }.map { it.ambulanceId }.toSet()
    }

    /**
     * ambulances — a district's approved ambulances: available first, then nearest (lat/lng), own upazila, rating.
     * lat/lng are cut to 2 decimals (privacy); gps: true = a real GPS fix (only then `km`); nearestId = the car of the
     * "call the nearest free ambulance" button (same world, not a freezer van unless asked, not one's own).
     */
    fun list(lang: String?, b: Body, uid: String?): Map<String, Any?> {
        val district = b.string("district")
        if (!Places.isDistrict(district)) throw err(lang, 400, "Choose a district", "জেলা বেছে নিন")
        remindInBackground()
        var docs = repo.approvedIn(district!!)
        val upazilaId = AmbFare.intId(b["upazilaId"])
        val now = AmbClock.now()
        val canRate = canRateIds(uid, now)
        val type = b.string("type")
        if (type != null && type in catalog.codes("ambulanceType")) docs = docs.filter { type in it.types }
        // cars on a trip are listed apart (folded in the app) — call to ask when they are back
        fun sameUp(v: Int?) = if (upazilaId != null && upazilaId != 0 && v == upazilaId) 1 else 0
        val busy = docs.filter { AmbRules.busyNow(it, now) }
            .map { a ->
                AmbRules.view(a, now).apply {
                    put("canRate", a.id in canRate)
                    put("busy", true)
                    put("busySince", a.busyAt ?: a.availableAt ?: 0L)
                }
            }
            .sortedWith { a, c ->
                val s = sameUp(c["upazilaId"] as Int?) - sameUp(a["upazilaId"] as Int?)
                if (s != 0) s else (a["busySince"] as Long).compareTo(c["busySince"] as Long)
            }
        val from = AmbRules.point(b.number("lat"), b.number("lng"))
        // not a real GPS (area centre etc.): approximate — own upazila first, no km
        val exactFrom = from != null && b.isTrue("gps")

        class Row(val v: LinkedHashMap<String, Any?>, val dist: Double, val owner: String, val avail: Boolean, val rating: Double, val ratingCount: Int, val up: Int?)
        val rows = docs.filter { !AmbRules.busyNow(it, now) }.map { a ->
            val v = AmbRules.view(a, now)
            v["canRate"] = a.id in canRate
            var dist = 9999.0
            val base = if (from != null) AmbRules.baseOf(a) else null
            if (base != null) {
                val km = AmbRules.distanceKm(from!!.first, from.second, base.lat, base.lng)
                // no base location given — district centre, so "about" and a little behind
                dist = km + if (base.exact) 0.0 else AmbRules.APPROX_EXTRA_KM
                if (exactFrom) {
                    v["km"] = AmbRules.wholeKm(km)
                    if (!base.exact) v["kmApprox"] = true
                }
            }
            Row(v, dist, a.ownerUid, v["available"] == true, v["rating"] as Double, a.ratingCount, a.upazilaId?.takeIf { it != 0 })
        }
        val sorted = rows.sortedWith { a, c ->
            var r = (if (c.avail) 1 else 0) - (if (a.avail) 1 else 0)
            if (r == 0 && from != null && !exactFrom) r = sameUp(c.up) - sameUp(a.up)
            if (r == 0 && from != null) r = a.dist.compareTo(c.dist)
            if (r == 0) r = sameUp(c.up) - sameUp(a.up)
            if (r == 0) r = c.rating.compareTo(a.rating)
            if (r == 0) r = c.ratingCount - a.ratingCount
            r
        }
        // only a freezer van (for the dead) is not for the patient's emergency call — unless filtering by "freezer"
        @Suppress("UNCHECKED_CAST")
        fun forPatient(v: Map<String, Any?>): Boolean {
            val t = v["types"] as List<String>
            return type == "freezer" || (t.isNotEmpty() && "freezer" !in t)
        }
        val nearest = sorted.firstOrNull { it.avail && forPatient(it.v) && it.owner != uid && DemoWorld.sameWorld(uid, it.owner) }
        return linkedMapOf(
            "items" to sorted.map { it.v },
            "busyCount" to busy.size,
            "busy" to busy.take(50),
            "nearestId" to nearest?.v?.get("id"),
        )
    }

    // ------------------------------------------------------------------ owner

    /** the owner's card: which document photos are missing (pending/rejected or asked by an admin) */
    private fun withDocs(a: Amb, v: LinkedHashMap<String, Any?>): LinkedHashMap<String, Any?> {
        if (a.status != "pending" && a.status != "rejected" && a.docsRequestedAt == null) return v
        try {
            v.putAll(verify.ownerInfo("ambulance", a.id, a.node))
        } catch (e: Exception) {
            log.warn("owner docs info failed: {}", e.message)
        }
        v["docsRequestNote"] = a.docsRequestNote ?: ""
        return v
    }

    fun mine(uid: String): Map<String, Any?> {
        val now = AmbClock.now()
        return linkedMapOf("items" to repo.ofOwner(uid, AmbRules.MAX_PER_OWNER).map { withDocs(it, AmbRules.view(it, now, true)) })
    }

    private fun codesOf(n: JsonNode?): List<Any?>? = n?.takeIf { it.isArray }?.map { if (it.isTextual) it.textValue() else it }

    /** ambulance_save — add or edit; a new review when the name (beyond a spelling fix), phone, vehicle number or district changes */
    fun save(lang: String?, b: Body, uid: String, email: String): Map<String, Any?> {
        fun bad(en: String, bn: String) = err(lang, 400, en, bn)
        val name = Rules.cleanText(b.string("name"), 80) ?: throw bad("Enter the service or driver name", "সার্ভিস বা চালকের নাম লিখুন")
        // mobile, landline or hotline (the service office's number)
        val phone = Phones.contactPhone(Js.toStr(b["phone"])) ?: throw bad("Enter a valid phone number", "সঠিক ফোন নম্বর দিন")
        val vehicleNo = Rules.cleanText(b.string("vehicleNo"), 40) ?: throw bad("Enter the vehicle registration number", "গাড়ির রেজিস্ট্রেশন নম্বর দিন")
        val allTypes = catalog.codes("ambulanceType")
        val types = ListingRules.pickCodes(codesOf(b["types"]), allTypes, allTypes.size)
        if (types.isEmpty()) throw bad("Choose the ambulance type", "অ্যাম্বুলেন্সের ধরন বেছে নিন")
        val district = b.string("district")
        if (!Places.isDistrict(district)) throw bad("Choose a district", "জেলা বেছে নিন")
        val upazilaId = AmbFare.intId(b["upazilaId"])?.takeIf { Places.UPAZILA[it]?.district == district }
        if (!b.isTrue("termsAccepted")) throw bad("Please accept the terms", "শর্তে সম্মতি দিন")
        // where the car waits (optional, phone GPS) — for "nearest"; 2 decimals (≈ 1 km cell), never the real GPS.
        // Outside the district only the place is dropped, the rest is saved (baseIgnored)
        var base = if (b.isTrue("clearBase")) null else AmbRules.point(b.number("lat"), b.number("lng"))
        val dc = Places.district(district)!!
        val hasChart = b.has("fareChart")
        var chart: FareChart? = null
        if (hasChart) {
            val fc = AmbFare.normalizeChart(b["fareChart"], Veh(district, upazilaId), places::facility)
            fc.error?.let { throw bad(it.first, it.second) }
            chart = fc.chart
        }
        val baseIgnored = base != null && AmbRules.distanceKm(base.first, base.second, dc.lat, dc.lng) > AmbRules.BASE_MAX_KM
        if (baseIgnored) base = null
        val area = ListingRules.str(b.string("area"), 120)
        val fare = ListingRules.str(b.string("fare"), 120)
        val now = AmbClock.now()
        val data = linkedMapOf<String, Any?>(
            "name" to name, "phone" to phone, "vehicleNo" to vehicleNo, "types" to types, "district" to district,
            "upazilaId" to upazilaId, "area" to area, "fare" to fare,
        )
        fun dataSet(set: SqlSet): SqlSet {
            set.value("name", name).value("phone", phone).value("vehicle_no", vehicleNo).textArray("types", types).value("district", district)
                .value("upazila_id", upazilaId).value("area", area).value("fare", fare).ms("updated_at", now)
            if (base != null) set.value("lat", base!!.first).value("lng", base!!.second)
            return set
        }
        val out = linkedMapOf<String, Any?>("ok" to true)
        if (Js.truthy(b["id"])) {
            val id = b.string("id")
            if (!Rules.isValidId(id)) throw notFound(lang, 400)
            val cur = repo.get(id) ?: throw notFound(lang)
            if (cur.ownerUid != uid) throw notFound(lang)
            com.lifolo.demo.DemoLock.checkRename(lang, uid, cur.name != name)
            if (cur.status == "suspended") {
                throw err(
                    lang, 403, "This ambulance is suspended. You can appeal from Profile → Help & appeals.",
                    "এই অ্যাম্বুলেন্স বন্ধ করা আছে। প্রোফাইলের \"সাহায্য ও আপিল\" থেকে আপিল করতে পারেন।",
                )
            }
            sanctions.checkListing(lang, "ambulance", data, email, false)
            // a small spelling fix of the name: no review; phone/vehicle/district or a bigger change: review again
            val rv = ListingRules.reviewNeeded(cur.node, data, listOf("phone", "vehicleNo", "district"))
            // new document photos (the old ones stay when none are given)
            verify.save(lang, "ambulance", id!!, uid, cur.node + data, b["docs"], false)
            // no new place: the old one stays; "clear place", or another district (the old place no longer fits): district centre
            val dropBase = base == null && cur.lat != null && (b.isTrue("clearBase") || cur.district != district)
            val set = dataSet(SqlSet())
            rv.approvedName?.let { set.value("approved_name", it) }
            ListingRules.keepApproved(cur.node, data, rv.changed)?.let { set.json("last_approved", ListingRules.json(it)) }
            // back to review: off the list — no reminder either
            if (rv.changed) set.value("status", "pending").nul("remind_at")
            if (dropBase) set.nul("lat").nul("lng")
            tx {
                set.update(jdbc, "ambulances", "id = ?", id)
                if (hasChart) repo.saveChart(id, chart)
            }
            out["pending"] = rv.changed || cur.status == "pending"
            if (baseIgnored) out["baseIgnored"] = true
            return out
        }
        // a car suspended after a report cannot be deleted and added again
        val suspendedVehicle = jdbc.queryForList("SELECT 1 FROM ambulances WHERE vehicle_no = ? AND status = 'suspended' LIMIT 1", vehicleNo).isNotEmpty()
        if (suspendedVehicle) {
            throw err(
                lang, 403, "This vehicle is suspended. You can appeal from Profile → Help & appeals.",
                "এই গাড়ি বন্ধ করা আছে। প্রোফাইলের \"সাহায্য ও আপিল\" থেকে আপিল করতে পারেন।",
            )
        }
        sanctions.checkListing(lang, "ambulance", data, email, true)
        val count = jdbc.queryForObject("SELECT count(*) FROM ambulances WHERE owner_uid = ?", Int::class.java, uid) ?: 0
        if (count >= AmbRules.MAX_PER_OWNER) throw bad("Up to 10 ambulances per account", "একটা অ্যাকাউন্টে সর্বোচ্চ ১০টা অ্যাম্বুলেন্স")
        // document photos (registration, fitness, car) first — without them no listing and no quota used
        verify.validate(lang, "ambulance", data, b["docs"], true)
        quotas.use(lang, uid, "ambulance")
        val id = Ids.newId()
        val docsHave = verify.save(lang, "ambulance", id, uid, data, b["docs"], true) ?: emptyList()
        tx {
            jdbc.update(
                """
                INSERT INTO ambulances (id, owner_uid, name, phone, vehicle_no, types, district, upazila_id, area, fare, lat, lng,
                  available, terms_accepted, status, docs_have, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?::text[], ?, ?, ?, ?, ?, ?, FALSE, TRUE, 'pending', ?::text[], ?, ?)
                """.trimIndent(),
                id, uid, name, phone, vehicleNo, types.joinToString(",", "{", "}") { quote(it) }, district, upazilaId, area, fare,
                base?.first, base?.second, docsHave.joinToString(",", "{", "}") { quote(it) }, Sql.ts(now), Sql.ts(now),
            )
            if (hasChart) repo.saveChart(id, chart)
        }
        out["pending"] = true
        out["id"] = id
        if (baseIgnored) out["baseIgnored"] = true
        return out
    }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** ambulance_available — "available now" on/off (availableAt; reminder 11 h later when approved) */
    fun available(lang: String?, b: Body, uid: String): Map<String, Any?> {
        val id = b.string("id")
        if (!Rules.isValidId(id)) throw notFound(lang, 400)
        val a = repo.get(id) ?: throw notFound(lang)
        if (a.ownerUid != uid) throw notFound(lang)
        val on = b.isTrue("available")
        val now = AmbClock.now()
        // reqBusy: busy for a "call an ambulance" trip — the driver's own choice wins, the trip end changes nothing then
        jdbc.update(
            "UPDATE ambulances SET available = ?, available_at = ?, busy_at = ?, updated_at = ?, req_busy = NULL, remind_at = ? WHERE id = ?",
            on, Sql.ts(now), if (on) null else Sql.ts(now), Sql.ts(now),
            if (on && a.status == "approved") Sql.ts(now + AmbRules.REMIND_AT_MS) else null, id,
        )
        return linkedMapOf("ok" to true)
    }

    /** ambulance_delete — not while suspended or with an open report */
    fun delete(lang: String?, b: Body, uid: String): Map<String, Any?> {
        val id = b.string("id")
        if (!Rules.isValidId(id)) throw notFound(lang, 400)
        val a = repo.get(id)
        if (a == null || a.ownerUid != uid) return linkedMapOf("ok" to true)
        if (a.status == "suspended") {
            throw err(
                lang, 403, "A suspended ambulance can't be removed. You can appeal from Profile → Help & appeals.",
                "বন্ধ করা অ্যাম্বুলেন্স সরানো যায় না। প্রোফাইলের \"সাহায্য ও আপিল\" থেকে আপিল করতে পারেন।",
            )
        }
        moderation.assertNoOpenReports(lang, "ambulances/$id")
        jdbc.update("DELETE FROM ambulances WHERE id = ?", id)
        try {
            verify.delete("ambulance", id!!)
        } catch (e: Exception) {
            log.warn("verify docs delete failed: {}", e.message)
        }
        return linkedMapOf("ok" to true)
    }

    /**
     * ambulance_called — a call from the app (only callers may rate/report). reqId: a call while a "call an ambulance"
     * request runs — added to the request too (attachCall).
     */
    fun called(b: Body, uid: String): Map<String, Any?> {
        val id = b.string("id")
        if (Rules.isValidId(id) && !b.isNullish("reqId")) {
            try {
                reqs.attachCall(uid, b.string("reqId"), id)
            } catch (e: Exception) {
                log.error("amb req call attach: {}", e.message)
            }
        }
        if (!Rules.isValidId(id)) return linkedMapOf("ok" to true)
        val a = repo.get(id) ?: return linkedMapOf("ok" to true)
        if (a.ownerUid == uid) return linkedMapOf("ok" to true)
        // demo: separate — calls from the other world are not counted
        if (!DemoWorld.sameWorld(uid, a.ownerUid)) return linkedMapOf("ok" to true)
        val now = AmbClock.now()
        val prevAt = jdbc.query(
            "SELECT at FROM ambulance_calls WHERE uid = ? AND ambulance_id = ?", { rs, _ -> rs.ms("at") ?: 0L }, uid, id,
        ).firstOrNull()
        jdbc.update(
            """
            INSERT INTO ambulance_calls (uid, ambulance_id, at, first_at) VALUES (?, ?, ?, ?)
            ON CONFLICT (uid, ambulance_id) DO UPDATE SET at = EXCLUDED.at
            """.trimIndent(),
            uid, id, Sql.ts(now), Sql.ts(now),
        )
        // the owner's count: the same person again within 10 minutes is not a new call
        if (prevAt == null || now - prevAt > 10 * 60 * 1000L) stats.bumpCall("ambulance", id!!, now, prevAt == null)
        return linkedMapOf("ok" to true)
    }

    private class CallRow(val at: Long?, val firstAt: Long?, val tripAt: Long?, val rating: Int?, val overcharged: Boolean?, val agreedFare: Int?)

    /**
     * ambulance_rate — 1–5 stars within 7 days of a call; overcharged = "took more than the agreed fare" (only trips
     * with an in-app agreed fare); a new rating replaces the old one (not counted twice).
     */
    fun rate(lang: String?, b: Body, uid: String): Map<String, Any?> {
        val id = b.string("id")
        val rating = b.int("rating")?.toInt() ?: 0
        if (!Rules.isValidId(id) || rating < 1 || rating > 5) throw err(lang, 400, "Choose 1 to 5 stars", "১ থেকে ৫ তারা দিন")
        val comment = ListingRules.str(b.string("comment"), 300)
        val now = AmbClock.now()
        tx {
            val c = jdbc.query(
                "SELECT at, first_at, trip_at, rating, overcharged, agreed_fare FROM ambulance_calls WHERE uid = ? AND ambulance_id = ? FOR UPDATE",
                { rs, _ ->
                    CallRow(
                        rs.ms("at"), rs.ms("first_at"), rs.ms("trip_at"), rs.intOrNull("rating"),
                        rs.getBoolean("overcharged").let { if (rs.wasNull()) null else it }, rs.intOrNull("agreed_fare"),
                    )
                },
                uid, id,
            ).firstOrNull()
            val a = repo.get(id, lock = true)
            // demo: separate
            if (a == null || !DemoWorld.sameWorld(uid, a.ownerUid)) throw notFound(lang)
            if (c == null || now - (c.at ?: 0) > AmbRules.RATE_WINDOW_MS) {
                throw err(lang, 403, "You can rate after calling this ambulance from the app", "অ্যাপ থেকে ফোন করার পর রেটিং দিতে পারবেন")
            }
            if (!AmbRules.rateable(c.at, c.firstAt, c.tripAt, now)) {
                throw err(lang, 403, "You can rate 1 hour after your first call", "প্রথম ফোনের ১ ঘণ্টা পর রেটিং দিতে পারবেন")
            }
            val old = c.rating ?: 0
            val wasOver = c.overcharged == true
            val over = if (c.agreedFare != null) b.isTrue("overcharged") else wasOver
            val overDelta = if (over != wasOver) (if (over) 1 else -1) else 0
            jdbc.update(
                "UPDATE ambulances SET rating_sum = rating_sum + ?, rating_count = rating_count + ?, overcharge_count = overcharge_count + ? WHERE id = ?",
                rating - old, if (old != 0) 0 else 1, overDelta, id,
            )
            jdbc.update(
                "UPDATE ambulance_calls SET rating = ?, comment = ?, rated_at = ?${if (over != wasOver) ", overcharged = ?" else ""} WHERE uid = ? AND ambulance_id = ?",
                *(listOf<Any?>(rating, comment, Sql.ts(now)) + (if (over != wasOver) listOf<Any?>(over) else emptyList()) + listOf(uid, id)).toTypedArray(),
            )
        }
        // the owner's statistics: the 10 latest ratings (without who gave them)
        stats.addRecentRating("ambulance", id!!, uid, rating, comment)
        return linkedMapOf("ok" to true)
    }

    // ------------------------------------------------------------------ admin

    /** admin_ambulances — by status (default pending) */
    fun adminList(b: Body): Map<String, Any?> {
        val status = b.string("status")?.takeIf { it in listOf("pending", "approved", "suspended", "rejected") } ?: "pending"
        val now = AmbClock.now()
        val items = repo.query("status = ?", status).map { a ->
            AmbRules.view(a, now, true).apply {
                put("ownerUid", a.ownerUid)
                // "took more than the agreed fare" — from patients' ratings (2.30.0)
                put("overchargeCount", a.overchargeCount)
                put("fareText", AmbFare.chartText(a.chart))
                put("createdAt", a.createdAt.takeIf { it != 0L })
                put("reviewedAt", (if (status == "suspended") a.suspendedAt else null) ?: a.reviewedAt)
                // what changed since approval, the reason, possible duplicates of a pending one
                put("statusReason", a.statusReason ?: "")
                put("editRejectedReason", a.editRejectedReason ?: "")
                put("changes", review.changedFields(a.node).map { linkedMapOf("field" to it.field, "before" to it.before, "after" to it.after) })
                if (status == "pending") {
                    put("duplicates", runCatching { review.possibleDuplicates("ambulance", a.id, a.node) }.getOrElse { emptyList() })
                }
                // the document photos and the ticks (pending/rejected — before approving) and the verification at approval
                if (status == "pending" || status == "rejected") {
                    put("verify", runCatching { verify.panelInfo("ambulance", a.id, a.node) }.getOrNull())
                }
                put("verification", a.node["verification"])
            }
        }
        return linkedMapOf("items" to items)
    }

    // ------------------------------------------------------------------ owner statistics (sellerstats.js)

    /** ambulance_stats — calls per day, rating, recent comments, reports, per own ambulance */
    fun ownerStats(uid: String): Map<String, Any?> {
        val now = AmbClock.now()
        val items = repo.ofOwner(uid, 10).map { a ->
            linkedMapOf<String, Any?>("id" to a.id, "name" to a.name, "status" to a.status).apply {
                putAll(stats.summary("ambulance", a.id, now))
                put("rating", AmbRules.rating(a.ratingSum, a.ratingCount))
                put("ratingCount", a.ratingCount)
                // who gave them is not shown (patients' privacy)
                put("comments", stats.recentComments("ambulance", a.id))
                put("reports", moderation.reportCount("ambulances/${a.id}"))
            }
        }
        return linkedMapOf("items" to items)
    }

    companion object {
        const val REMIND_KEY = "amb:remind"
    }
}
