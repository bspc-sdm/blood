package com.lifolo.ambulance

import com.lifolo.admin.kernel.Catalog
import com.lifolo.admin.kernel.ListingStats
import com.lifolo.common.ApiException
import com.lifolo.common.Body
import com.lifolo.common.Ids
import com.lifolo.common.Js
import com.lifolo.common.Lang
import com.lifolo.common.Phones
import com.lifolo.common.Places
import com.lifolo.common.Rules
import com.lifolo.common.Sql
import com.lifolo.common.SqlSet
import com.lifolo.common.doubleOrNull
import com.lifolo.common.intOrNull
import com.lifolo.common.ms
import com.lifolo.common.stringArray
import com.lifolo.demo.DemoWorld
import com.lifolo.notify.PushItem
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet

/** one driver (owner) a request was sent to — ambulanceRequests sentTo{} / activeOwners[] / declined[] / lapsed[] / km{} / est{} */
class Target(val owner: String, val ambulanceId: String, val active: Boolean, val declined: Boolean, val lapsed: Boolean, val km: Int?, val est: Int?)

/** a driver's fare offer (quotes{owner}) */
class Quote(val owner: String, val ambulanceId: String, val name: String, val types: List<String>, val fare: Int, val rating: Double, val ratingCount: Int, val at: Long)

/** a car the patient phoned while waiting (calledIds[] + calledInfo{}) */
class Called(val ambulanceId: String, val name: String, val phone: String)

/** the phone buttons after the first wave (fallback[]) */
class Fallback(val id: String, val name: String, val phone: String, val km: Int?, val kmApprox: Boolean) {
    fun toMap(): LinkedHashMap<String, Any?> =
        linkedMapOf<String, Any?>("id" to id, "name" to name, "phone" to phone, "km" to km).apply { if (kmApprox) put("kmApprox", true) }
}

/** ambulanceRequests/{id} with its child rows, read like the Node document */
class Req(
    val id: String,
    val uid: String,
    val patientName: String,
    val phone: String?,
    val district: String,
    val upazilaId: Int?,
    val unionId: Int?,
    val lat: Double?,
    val lng: Double?,
    val gps: Boolean,
    val pickup: String?,
    val note: String?,
    val type: String?,
    val status: String,
    val wave: Int,
    val quote: Boolean,
    val maxFare: Long?,
    val raiseTo: Int?,
    val includeUnknown: Boolean,
    val unknownFree: Int?,
    val oxygen: Boolean,
    val night: Boolean,
    val dest: Dest?,
    val acceptedBy: String?,
    val ambulanceId: String?,
    val ambulanceName: String?,
    val driverPhone: String?,
    val bookedOwner: String?,
    val agreedFare: Int?,
    val cancelledBy: String?,
    val cancelReason: String?,
    val closedBy: String?,
    val createdAt: Long,
    val openedAt: Long?,
    val acceptedAt: Long?,
    val arrivedAt: Long?,
    val closedAt: Long?,
) {
    var targets: List<Target> = emptyList()
    var quotes: List<Quote> = emptyList()
    var calls: List<Called> = emptyList()
    var fallback: List<Fallback> = emptyList()

    val sentTo: Map<String, String> get() = targets.associate { it.owner to it.ambulanceId }
    val activeOwners: List<String> get() = targets.filter { it.active }.map { it.owner }
    val declined: List<String> get() = targets.filter { it.declined }.map { it.owner }
    val lapsed: List<String> get() = targets.filter { it.lapsed }.map { it.owner }
    val calledIds: List<String> get() = calls.map { it.ambulanceId }

    fun km(owner: String?): Int? = targets.firstOrNull { it.owner == owner }?.km
    fun est(owner: String?): Int? = targets.firstOrNull { it.owner == owner }?.est
    fun quoteOf(owner: String): Quote? = quotes.firstOrNull { it.owner == owner }

    /** when the request (re)opened */
    val opened: Long get() = openedAt ?: createdAt

    val crit: AmbReqService.Crit
        get() = AmbReqService.Crit(uid, gps, lat, lng, upazilaId, type, dest, oxygen, night, quote, maxFare, includeUnknown)
}

/**
 * ambreq.js — "call an ambulance" in one tap (Uber-like, with the phone fallback). The request goes to the 5 nearest
 * free cars (fitting type, within the fare budget); drivers answer "Coming" (with a fare in fare mode), the patient
 * agrees; after 60 s the circle widens once and the phone buttons appear; after 3 minutes it expires.
 * Timing is advanced lazily on every read/write ([advance], like Node) — plus a minute job ([AmbulanceJobs]).
 * Every state change runs in a transaction on the locked request row (Node: Firestore transactions).
 */
@Service
class AmbReqService(
    private val jdbc: JdbcTemplate,
    private val repo: AmbRepo,
    private val places: AmbPlaces,
    private val catalog: Catalog,
    private val stats: ListingStats,
    private val pushes: AmbPush,
    txm: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val txt = TransactionTemplate(txm)

    @Suppress("UNCHECKED_CAST")
    private fun <T> tx(block: () -> T): T = txt.execute(TransactionCallback { block() }) as T

    companion object {
        const val SEC = 1000L
        const val MIN = 60 * SEC
        const val HOUR = 60 * MIN

        /** owners pushed per wave */
        const val WAVE = 5

        /** nobody accepted → widen + phone buttons */
        const val WIDEN_MS = 60 * SEC

        /** nobody accepted → expired */
        const val EXPIRE_MS = 3 * MIN

        /** an accepted trip the driver never finished closes by itself */
        const val ACCEPTED_MAX_MS = 12 * HOUR

        /** phone buttons on the waiting page */
        const val FALLBACK = 3

        /** requests per hour */
        const val PER_HOUR = 5

        /** requests per account in 24 hours (fake calls from one account; not in Node) */
        const val PER_DAY = 12

        /** requests per contact number per hour, over all accounts (one prankster, many new accounts; not in Node) */
        const val PER_PHONE_HOUR = 5

        /** exact place, phone, address, note, name removed this long after closing */
        const val PURGE_AFTER_MS = 24 * HOUR

        /** cars further than this from the patient are not asked */
        const val MAX_KM = 130.0
        const val APPROX_EXTRA_KM = 5.0

        /** a driver's fare offer lapses when the patient does not agree within this */
        const val QUOTE_MS = 2 * MIN

        val ACTIVE = listOf("open", "accepted", "arrived")
        val PATIENT_CANCEL = listOf("wrong", "found_other", "not_needed", "too_slow", "other")
        val DRIVER_CANCEL = listOf("vehicle_problem", "cant_reach", "patient_unreachable", "other")

        fun sameWorld(a: String?, b: String?) = DemoWorld.sameWorld(a, b)

        /** rules.js formatKm */
        fun formatKm(km: Int, lang: String?): String {
            if (lang == "en") return if (km < 1) "< 1 km away" else "about $km km away"
            return if (km < 1) "১ কিমির কম দূরে" else "প্রায় ${Lang.bnDigits(km.toString())} কিমি দূরে"
        }

        /** the patient's exact point (inside Bangladesh), 5 decimals — only for the accepted driver */
        fun exactPoint(lat: Double?, lng: Double?): Pair<Double, Double>? {
            if (lat == null || lng == null || !lat.isFinite() || !lng.isFinite()) return null
            if (lat < 20.3 || lat > 26.8 || lng < 87.9 || lng > 92.8) return null
            return Js.round(lat * 1e5) / 1e5 to Js.round(lng * 1e5) / 1e5
        }

        /** km{} values: negative = "about" (the car has no base) */
        fun kmOf(v: Int?): Map<String, Any?> = when {
            v == null -> linkedMapOf("km" to null)
            v < 0 -> linkedMapOf("km" to -1 - v, "kmApprox" to true)
            else -> linkedMapOf("km" to v)
        }

        /**
         * typeOk: null = any (never a freezer van); a freezer van only when the patient chose "freezer" — a hearse never
         * goes to a living patient even when it lists another type too.
         */
        fun typeOk(types: List<String>, type: String?): Boolean {
            if (type == "freezer") return types.contains("freezer")
            if (types.contains("freezer")) return false
            return if (type != null) types.contains(type) else types.isNotEmpty()
        }
    }

    /** who asks and how (the request document or the create/count body) */
    data class Crit(
        val uid: String,
        val gps: Boolean,
        val lat: Double?,
        val lng: Double?,
        val upazilaId: Int?,
        val type: String?,
        val dest: Dest?,
        val oxygen: Boolean,
        val night: Boolean,
        val quote: Boolean,
        val maxFare: Long?,
        val includeUnknown: Boolean,
    )

    /** a car that can be asked */
    class Cand(
        val id: String,
        val owner: String,
        val name: String,
        val phone: String,
        val km: Int?,
        val approx: Boolean,
        val dist: Double?,
        val avail: Boolean,
        val same: Int,
        val rating: Double,
        val est: Int?,
    )

    // ------------------------------------------------------------------ loading

    private fun mapReq(rs: ResultSet): Req {
        val destKind = rs.getString("dest_kind")
        val dest = when (destKind) {
            null -> null
            "upazila" -> Dest("upazila", upazilaId = rs.intOrNull("dest_upazila_id"))
            "district" -> Dest("district", district = rs.getString("dest_district"))
            else -> rs.getString("dest_facility_id")?.let {
                Dest("hospital", upazilaId = rs.intOrNull("dest_upazila_id"), district = rs.getString("dest_district"), facilityId = it, name = rs.getString("dest_name"))
            } ?: Dest("hospital", name = rs.getString("dest_name"))
        }
        return Req(
            id = rs.getString("id"),
            uid = rs.getString("uid"),
            patientName = rs.getString("patient_name") ?: "",
            phone = rs.getString("phone"),
            district = rs.getString("district"),
            upazilaId = rs.intOrNull("upazila_id"),
            unionId = rs.intOrNull("union_id"),
            lat = rs.doubleOrNull("lat"),
            lng = rs.doubleOrNull("lng"),
            gps = rs.getBoolean("gps"),
            pickup = rs.getString("pickup"),
            note = rs.getString("note"),
            type = rs.getString("type"),
            status = rs.getString("status"),
            wave = rs.getInt("wave"),
            quote = rs.getBoolean("quote"),
            maxFare = rs.intOrNull("max_fare")?.toLong(),
            raiseTo = rs.intOrNull("raise_to"),
            includeUnknown = rs.getBoolean("include_unknown"),
            unknownFree = rs.intOrNull("unknown_free"),
            oxygen = rs.getBoolean("oxygen"),
            night = rs.getBoolean("night"),
            dest = dest,
            acceptedBy = rs.getString("accepted_by"),
            ambulanceId = rs.getString("ambulance_id"),
            ambulanceName = rs.getString("ambulance_name"),
            driverPhone = rs.getString("driver_phone"),
            bookedOwner = rs.getString("booked_owner"),
            agreedFare = rs.intOrNull("agreed_fare"),
            cancelledBy = rs.getString("cancelled_by"),
            cancelReason = rs.getString("cancel_reason"),
            closedBy = rs.getString("closed_by"),
            createdAt = rs.ms("created_at") ?: 0L,
            openedAt = rs.ms("opened_at"),
            acceptedAt = rs.ms("accepted_at"),
            arrivedAt = rs.ms("arrived_at"),
            closedAt = rs.ms("closed_at"),
        )
    }

    /** the request with its child rows; [lock] = `FOR UPDATE` (inside a transaction) */
    fun load(id: String, lock: Boolean = false): Req? {
        val x = jdbc.query(
            "SELECT * FROM ambulance_requests WHERE id = ?${if (lock) " FOR UPDATE" else ""}", { rs, _ -> mapReq(rs) }, id,
        ).firstOrNull() ?: return null
        x.targets = jdbc.query(
            "SELECT * FROM ambreq_targets WHERE request_id = ? ORDER BY seq",
            { rs, _ ->
                Target(
                    rs.getString("owner_uid"), rs.getString("ambulance_id"), rs.getBoolean("active"), rs.getBoolean("declined"),
                    rs.getBoolean("lapsed"), rs.doubleOrNull("km")?.toInt(), rs.intOrNull("est_fare"),
                )
            },
            id,
        )
        x.quotes = jdbc.query(
            "SELECT * FROM ambreq_quotes WHERE request_id = ?",
            { rs, _ ->
                Quote(
                    rs.getString("owner_uid"), rs.getString("ambulance_id"), rs.getString("name"), rs.stringArray("types") ?: emptyList(),
                    rs.getInt("fare"), rs.getDouble("rating"), rs.getInt("rating_count"), rs.ms("at") ?: 0L,
                )
            },
            id,
        )
        x.calls = jdbc.query(
            "SELECT * FROM ambreq_calls WHERE request_id = ? ORDER BY seq",
            { rs, _ -> Called(rs.getString("ambulance_id"), rs.getString("name"), rs.getString("phone")) },
            id,
        )
        x.fallback = jdbc.query(
            "SELECT * FROM ambreq_fallback WHERE request_id = ? ORDER BY idx",
            { rs, _ -> Fallback(rs.getString("ambulance_id"), rs.getString("name"), rs.getString("phone"), rs.doubleOrNull("km")?.toInt(), rs.getBoolean("km_approx")) },
            id,
        )
        return x
    }

    // ------------------------------------------------------------------ choosing cars

    /**
     * ambreq.js candidates: approved, not on a trip, available, fitting type, not the patient's own, same world,
     * within 130 km. Available ones first, then nearest (GPS), own upazila, rating. One car per owner (the nearest).
     * With a destination each gets `est` (estimated fare, null = unknown).
     */
    fun candidates(docs: List<Amb>, r: Crit, now: Long): List<Cand> {
        val from = if (r.gps && r.lat != null && r.lng != null) r.lat to r.lng else null
        val rows = ArrayList<Cand>()
        for (x in docs) {
            val owner = x.ownerUid
            if (x.status != "approved" || owner.isEmpty() || owner == r.uid) continue
            if (!sameWorld(r.uid, owner)) continue
            if (AmbRules.busyNow(x, now) || !x.available) continue
            if (!typeOk(x.types, r.type)) continue
            val base = AmbRules.baseOf(x)
            var dist: Double? = null
            var km: Int? = null
            var approx = false
            if (from != null && base != null) {
                val real = AmbRules.distanceKm(from.first, from.second, base.lat, base.lng)
                if (real > MAX_KM) continue
                dist = real + if (base.exact) 0.0 else APPROX_EXTRA_KM
                km = AmbRules.wholeKm(real)
                approx = !base.exact
            }
            rows.add(
                Cand(
                    x.id, owner, x.name, Phones.fixPhone(x.phone), km, approx, dist, AmbRules.availableNow(x, now),
                    if (r.upazilaId != null && r.upazilaId != 0 && x.upazilaId == r.upazilaId) 1 else 0,
                    if (x.ratingCount > 0) x.ratingSum.toDouble() / x.ratingCount else 0.0,
                    if (r.dest != null) AmbFare.estimate(x.chart, x.veh, r.dest, r.type, r.oxygen, r.night) else null,
                ),
            )
        }
        val sorted = rows.sortedWith { a, b ->
            val av = (if (b.avail) 1 else 0) - (if (a.avail) 1 else 0)
            if (av != 0) return@sortedWith av
            if (a.dist != null && b.dist != null) {
                val d = a.dist.compareTo(b.dist)
                if (d != 0) return@sortedWith d
            }
            val s = b.same - a.same
            if (s != 0) return@sortedWith s
            b.rating.compareTo(a.rating)
        }
        val seen = HashSet<String>()
        return sorted.filter { seen.add(it.owner) }
    }

    /** fare requests: only cars within the budget (unknown fares only with includeUnknown); old requests: all */
    fun affordable(list: List<Cand>, r: Crit): List<Cand> =
        if (r.quote) list.filter { AmbFare.fits(it.est, r.maxFare, r.includeUnknown) } else list

    private fun fallbackOf(list: List<Cand>): List<Fallback> =
        list.filter { it.avail }.take(FALLBACK).map { Fallback(it.id, it.name, it.phone, it.km, it.approx) }

    /** km{} of a wave (negative = "about") */
    private fun kmCode(c: Cand): Int? = c.km?.let { if (c.approx) -1 - it else it }

    // ------------------------------------------------------------------ pushes

    /** drivers: no address/note in the push (the bell keeps it 21 days) — only the distance */
    private fun pushDrivers(id: String, km: Map<String, Int?>, quote: Boolean, owners: List<String>) {
        if (owners.isEmpty()) return
        pushes.send(owners) { uid, lang ->
            val k = km[uid]?.let { if (it < 0) -1 - it else it }
            val where = if (k == null) Lang.pick(lang, "nearby", "কাছেই") else formatKm(k, lang)
            PushItem(
                uid,
                Lang.pick(lang, "Ambulance needed — $where", Lang.bnNumbers("অ্যাম্বুলেন্স দরকার — $where")),
                if (quote) {
                    Lang.pick(lang, "Open and tap \"Coming\" with your fare — confirmed when the patient agrees.", "খুলে ভাড়াসহ \"আসছি\" চাপুন — রোগী রাজি হলে নিশ্চিত।")
                } else {
                    Lang.pick(lang, "Open and tap \"Coming\" — the first to accept gets it.", "খুলে \"আসছি\" চাপুন — আগে যিনি রাজি হবেন তিনিই পাবেন।")
                },
                linkedMapOf("type" to "services", "kind" to "ambulance", "sub" to "amb_req", "reqId" to id),
            )
        }
    }

    /** the patient (opens the waiting page) */
    private fun pushPatient(id: String, uid: String, en: String, bn: String, enBody: String = "", bnBody: String = "") {
        pushes.send(listOf(uid)) { u, lang -> PushItem(u, Lang.pick(lang, en, bn), Lang.pick(lang, enBody, bnBody), linkedMapOf("type" to "amb_req_mine", "reqId" to id)) }
    }

    /** one driver */
    private fun pushDriver(id: String, uid: String, en: String, bn: String) {
        pushes.send(listOf(uid)) { u, lang -> PushItem(u, Lang.pick(lang, en, bn), "", linkedMapOf("type" to "services", "kind" to "ambulance", "sub" to "amb_req", "reqId" to id)) }
    }

    private fun pushOwners(id: String, owners: List<String>, en: String, bn: String) {
        if (owners.isEmpty()) return
        pushes.send(owners) { u, lang -> PushItem(u, Lang.pick(lang, en, bn), "", linkedMapOf("type" to "services", "kind" to "ambulance", "sub" to "amb_req", "reqId" to id)) }
    }

    /** the patient took another offer */
    private fun pushReleased(id: String, owners: List<String>) =
        pushOwners(id, owners, "The patient took another ambulance", "রোগী অন্য গাড়ি নিয়েছেন")

    /** the patient got a car by phone */
    private fun pushNoLonger(id: String, owners: List<String>) =
        pushOwners(id, owners, "The patient got an ambulance — no longer needed", "রোগী গাড়ি পেয়ে গেছেন — আর দরকার নেই")

    /** politely: the patient found another car by phone */
    private fun politeCancel(id: String, driver: String) = pushDriver(
        id, driver, "Sorry — the patient found another ambulance by phone. Thank you for accepting!",
        "দুঃখিত — রোগী ফোনে অন্য গাড়ি পেয়ে গেছেন। রাজি হওয়ার জন্য ধন্যবাদ!",
    )

    // ------------------------------------------------------------------ writes

    /** closeFields: off every driver's list, purge date */
    private fun closeSet(now: Long, status: String): SqlSet =
        SqlSet().value("status", status).value("active", false).ms("closed_at", now).ms("purge_at", now + PURGE_AFTER_MS)

    private fun updateReq(id: String, set: SqlSet) {
        set.update(jdbc, "ambulance_requests", "id = ?", id)
    }

    private fun deactivateAll(id: String) {
        jdbc.update("UPDATE ambreq_targets SET active = FALSE WHERE request_id = ?", id)
    }

    private fun insertTarget(id: String, c: Cand, wave: Int, now: Long) {
        jdbc.update(
            "INSERT INTO ambreq_targets (request_id, owner_uid, ambulance_id, wave, active, est_fare, km, sent_at) VALUES (?, ?, ?, ?, TRUE, ?, ?, ?)",
            id, c.owner, c.id, wave, c.est, kmCode(c)?.toDouble(), Sql.ts(now),
        )
    }

    private fun replaceFallback(id: String, list: List<Fallback>) {
        jdbc.update("DELETE FROM ambreq_fallback WHERE request_id = ?", id)
        list.forEachIndexed { i, f ->
            jdbc.update(
                "INSERT INTO ambreq_fallback (request_id, idx, ambulance_id, name, phone, km, km_approx) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, i, f.id, f.name, f.phone, f.km?.toDouble(), f.kmApprox,
            )
        }
    }

    private fun addReplaced(id: String, owner: String, ambulanceId: String, now: Long) {
        jdbc.update(
            """
            INSERT INTO ambreq_replaced (request_id, idx, owner_uid, ambulance_id, reason, at)
            VALUES (?, (SELECT coalesce(max(idx) + 1, 0) FROM ambreq_replaced WHERE request_id = ?), ?, ?, 'patient_found_other', ?)
            """.trimIndent(),
            id, id, owner, ambulanceId, Sql.ts(now),
        )
    }

    /** lapsed fare offers (2 minutes without the patient's answer) */
    private fun lapsedOwners(x: Req, now: Long): List<String> = x.quotes.filter { now - it.at >= QUOTE_MS }.map { it.owner }

    /** the open request's deadline: 3 minutes after opening, or the last pending offer's expiry (whichever is later) */
    fun deadlineOf(x: Req): Long = (listOf(x.opened + EXPIRE_MS) + x.quotes.map { it.at + QUOTE_MS }).max()

    // ------------------------------------------------------------------ the state machine

    /**
     * ambreq.js advance — run on every read (patient status, driver list, daily job), idempotent:
     *  - quotes without an answer for 2 min → the driver is released (push)
     *  - open past the deadline → expired (push the patient, unless much later)
     *  - open 60 s (or everyone declined), still wave 1 → next 5 cars + phone buttons
     *  - accepted/arrived 12 h → closed by itself
     */
    fun advance(id: String, now: Long): Req? {
        var x = load(id) ?: return null
        if (x.status == "open" && lapsedOwners(x, now).isNotEmpty()) {
            val gone = tx {
                val c = load(id, true) ?: return@tx emptyList<String>()
                val list = if (c.status == "open") lapsedOwners(c, now) else emptyList()
                if (list.isEmpty()) return@tx emptyList<String>()
                for (o in list) {
                    jdbc.update("DELETE FROM ambreq_quotes WHERE request_id = ? AND owner_uid = ?", id, o)
                    jdbc.update("UPDATE ambreq_targets SET declined = TRUE, lapsed = TRUE, active = FALSE WHERE request_id = ? AND owner_uid = ?", id, o)
                }
                list.filter { o -> now - (c.quoteOf(o)?.at ?: 0) < QUOTE_MS + 10 * MIN }
            }
            for (o in gone) {
                pushDriver(id, o, "The patient didn't answer your fare — you are free for other calls", "রোগী আপনার ভাড়ায় উত্তর দেননি — আপনি অন্য ডাক নিতে পারেন")
            }
            x = load(id) ?: return null
        }
        if (x.status == "open" && now >= deadlineOf(x)) {
            val done = tx {
                val c = load(id, true) ?: return@tx false
                if (c.status != "open" || now < deadlineOf(c)) return@tx false
                updateReq(id, closeSet(now, "expired"))
                deactivateAll(id)
                true
            }
            // much later from the daily job: no push (the patient sorted it out long ago)
            if (done && now - deadlineOf(x) < 10 * MIN) {
                val offered = x.lapsed.isNotEmpty()
                pushPatient(
                    id, x.uid, if (offered) "The fare offers expired" else "No ambulance accepted",
                    if (offered) "ভাড়ার প্রস্তাবের সময় শেষ" else "কোনো অ্যাম্বুলেন্স রাজি হয়নি",
                    "Please call one directly from the list.", "তালিকা থেকে সরাসরি ফোন করুন।",
                )
            }
            return load(id)
        }
        if (x.status == "open" && x.wave == 1) {
            val owners = x.sentTo.keys.toList()
            val declined = x.declined
            val allDeclined = owners.isNotEmpty() && owners.all { it in declined }
            if (now - x.opened >= WIDEN_MS || allDeclined) {
                val r = x.crit
                val all = candidates(repo.approvedIn(x.district), r, now)
                val list = affordable(all, r)
                val called = x.calledIds
                val next = list.filter { it.owner !in owners && it.id !in called }.take(WAVE)
                val listIds = list.map { it.id }.toSet()
                val left = all.filter { it.owner !in owners && it.id !in called && it.id !in listIds }
                val fresh = ArrayList<Cand>()
                val won = tx {
                    val c = load(id, true) ?: return@tx false
                    if (c.status != "open" || c.wave != 1) return@tx false
                    // another server may have added some meanwhile (phoned cars too)
                    val nowSent = c.sentTo
                    fresh.addAll(next.filter { nowSent[it.owner] == null && it.id !in c.calledIds })
                    val set = SqlSet().value("wave", 2).ms("widened_at", now)
                    if (x.quote) {
                        set.value("raise_to", AmbFare.raiseTo(left.map { it.est }, x.maxFare)?.toInt())
                            .value("unknown_free", if (x.includeUnknown) 0 else left.count { it.est == null && it.avail })
                    }
                    updateReq(id, set)
                    fresh.forEach { insertTarget(id, it, 2, now) }
                    // phone buttons: cars within the budget first, then the other nearby ones (fare by phone)
                    replaceFallback(id, fallbackOf(list + all.filter { it.id !in listIds }))
                    true
                }
                if (won) pushDrivers(id, fresh.associate { it.owner to kmCode(it) }, x.quote, fresh.map { it.owner })
                return load(id)
            }
        }
        if ((x.status == "accepted" || x.status == "arrived") && now - (x.acceptedAt ?: x.createdAt) >= ACCEPTED_MAX_MS) {
            tx {
                val c = load(id, true) ?: return@tx
                if (c.status != "accepted" && c.status != "arrived") return@tx
                val amb = c.ambulanceId?.let { repo.get(it, lock = true) }
                updateReq(id, closeSet(now, "done").value("closed_by", "auto"))
                deactivateAll(id)
                repo.freeVehicle(amb, now)
            }
            return load(id)
        }
        return x
    }

    // ------------------------------------------------------------------ views

    /** the fare part both sides see */
    private fun fareCommon(x: Req, lang: String): Map<String, Any?> = linkedMapOf(
        "quote" to true,
        "dest" to x.dest?.toMap(),
        "destLabel" to AmbFare.destLabel(x.dest, lang),
        "maxFare" to x.maxFare,
        "oxygen" to x.oxygen,
        "night" to x.night,
        "agreedFare" to x.agreedFare,
    )

    /** the patient's offers: lowest fare first, then nearest; by car id (no driver uid) */
    private fun quotesOf(x: Req, now: Long): List<Map<String, Any?>> = x.quotes.filter { now - it.at < QUOTE_MS }
        .map { q ->
            linkedMapOf<String, Any?>("id" to q.ambulanceId, "name" to q.name, "types" to q.types).apply {
                putAll(kmOf(x.km(q.owner)))
                put("fare", q.fare)
                put("rating", q.rating)
                put("ratingCount", q.ratingCount)
                put("at", q.at)
                put("expiresAt", q.at + QUOTE_MS)
            }
        }
        .sortedWith(
            compareBy<Map<String, Any?>> { it["fare"] as Int }
                .thenBy { (it["km"] as Int?) ?: 9999 }
                .thenBy { it["at"] as Long },
        )

    /** ambreq.js patientView */
    fun patientView(x: Req, now: Long, lang: String = "bn"): Map<String, Any?> {
        val accepted = x.status in listOf("accepted", "arrived", "done", "byPhone") || (x.status == "cancelled" && !x.ambulanceId.isNullOrEmpty())
        val showFallback = x.wave > 1 || x.status == "expired" || x.status == "cancelled"
        val v = linkedMapOf<String, Any?>(
            "id" to x.id,
            "status" to x.status,
            "createdAt" to x.createdAt,
            "widenAt" to x.opened + WIDEN_MS,
            "expiresAt" to deadlineOf(x),
            "wave" to x.wave,
            "notified" to x.targets.size,
            "type" to x.type,
            "pickup" to (x.pickup ?: ""),
            "note" to (x.note ?: ""),
            "district" to x.district,
            "upazilaId" to x.upazilaId?.takeIf { it != 0 },
            "unionId" to x.unionId?.takeIf { it != 0 },
            "gps" to x.gps,
        )
        if (accepted) {
            v["ambulance"] = linkedMapOf<String, Any?>("id" to x.ambulanceId, "name" to (x.ambulanceName ?: ""), "phone" to (x.driverPhone ?: ""))
                .apply { putAll(kmOf(x.km(x.acceptedBy ?: x.bookedOwner))) }
        }
        v["acceptedAt"] = x.acceptedAt ?: 0L
        v["arrivedAt"] = x.arrivedAt ?: 0L
        v["closedAt"] = x.closedAt ?: 0L
        v["cancelledBy"] = x.cancelledBy
        v["cancelReason"] = x.cancelReason
        v["cancelText"] = ""
        if (x.quote) {
            v.putAll(fareCommon(x, lang))
            v["includeUnknown"] = x.includeUnknown
            v["quotes"] = if (x.status == "open") quotesOf(x, now) else emptyList()
            v["raiseTo"] = x.raiseTo
            v["unknownFree"] = x.unknownFree ?: 0
        }
        v["fallback"] = if (showFallback) x.fallback.map { it.toMap() } else emptyList<Any>()
        v["called"] = x.calls.map { linkedMapOf("id" to it.ambulanceId, "name" to it.name, "phone" to it.phone) }
        v["canRate"] = x.status == "done" && x.closedBy != "auto" && now - (x.closedAt ?: 0) <= 7 * 24 * HOUR
        return v
    }

    /** ambreq.js driverView: before accepting no exact place, phone or name */
    fun driverView(x: Req, uid: String, lang: String = "bn"): Map<String, Any?> {
        val mine = x.acceptedBy == uid
        val q = x.quoteOf(uid)
        val v = linkedMapOf<String, Any?>(
            "id" to x.id,
            "status" to if (mine) x.status else "open",
            "createdAt" to x.createdAt,
            "expiresAt" to x.opened + EXPIRE_MS,
            "called" to x.calledIds.contains(if (mine) x.ambulanceId else x.sentTo[uid]),
            "ambulanceId" to if (mine) x.ambulanceId else x.sentTo[uid],
        )
        v.putAll(kmOf(x.km(uid)))
        v["type"] = x.type
        v["district"] = x.district
        v["upazilaId"] = x.upazilaId?.takeIf { it != 0 }
        v["unionId"] = x.unionId?.takeIf { it != 0 }
        v["pickup"] = x.pickup ?: ""
        v["note"] = x.note ?: ""
        if (x.quote) {
            v.putAll(fareCommon(x, lang))
            v["suggestedFare"] = x.est(uid)
            v["myQuote"] = if (!mine && q != null) linkedMapOf("fare" to q.fare, "at" to q.at, "expiresAt" to q.at + QUOTE_MS) else null
        }
        if (mine) {
            v["patientName"] = x.patientName
            v["phone"] = x.phone ?: ""
            if (x.lat != null) {
                v["lat"] = x.lat
                v["lng"] = x.lng
            }
            v["acceptedAt"] = x.acceptedAt ?: 0L
            v["arrivedAt"] = x.arrivedAt ?: 0L
        }
        return v
    }

    // ------------------------------------------------------------------ helpers of the actions

    /** the request language of views (Node: body.lang === 'bn' ? 'bn' : 'en') */
    private fun langOf(b: Body): String = if (b.lang == "bn") "bn" else "en"

    private fun err(lang: String?, status: Int, en: String, bn: String, code: String? = null) = ApiException(status, Lang.tr(lang, en, bn), code)

    private fun notFound(lang: String?) = err(lang, 404, "Not found", "পাওয়া যায়নি")

    /** loadMine: the patient's request (field uid) or one sent to this driver (owner) */
    private fun loadMine(lang: String?, id: String?, owner: Boolean, uid: String): Req {
        if (id == null || !Rules.isValidId(id)) throw notFound(lang)
        val x = load(id) ?: throw notFound(lang)
        if (if (owner) !x.sentTo.containsKey(uid) else x.uid != uid) throw notFound(lang)
        return x
    }

    /** the request's area (district, upazila — from the point when not chosen —, exact point unless far from the district) */
    private data class Place(val upazilaId: Int?, val p: Pair<Double, Double>?, val upazilaAuto: Boolean)

    private fun placeOf(b: Body): Place {
        val district = b.string("district")
        var upazilaId = AmbFare.intId(b["upazilaId"])?.takeIf { Places.UPAZILA[it]?.district == district }
        var p = if (b.isTrue("gps")) exactPoint(b.number("lat"), b.number("lng")) else null
        val dc = Places.district(district)!!
        if (p != null && AmbRules.distanceKm(p.first, p.second, dc.lat, dc.lng) > MAX_KM) p = null
        var auto = false
        if (upazilaId == null && p != null) {
            upazilaId = AmbPlaces.nearestUpazila(district, p.first, p.second)
            auto = upazilaId != null
        }
        return Place(upazilaId, p, auto)
    }

    private class FareParams(val quote: Boolean, val dest: Dest? = null, val maxFare: Long? = null, val includeUnknown: Boolean = false, val oxygen: Boolean = false, val night: Boolean = false)

    /** the patient's fare fields (create and count): no dest → old app (quote: false) */
    private fun fareParams(lang: String?, b: Body, now: Long): FareParams {
        if (b.isNullish("dest")) return FareParams(false)
        val dest = AmbFare.destOf(b["dest"], places::facility) ?: throw err(lang, 400, "Choose where to go", "\"কোথায় যাবেন\" বেছে নিন")
        val maxFare = AmbFare.budgetOf(b["maxFare"])
        if (maxFare == AmbFare.NAN) {
            throw err(lang, 400, "Write the fare limit in whole taka (৳100 or more)", "সর্বোচ্চ ভাড়া পুরো টাকায় লিখুন (৳১০০ বা বেশি)")
        }
        return FareParams(true, dest, maxFare, b.isTrue("includeUnknown"), b.isTrue("oxygen"), AmbFare.isNight(now))
    }

    private fun typeOf(b: Body): String? = b.string("type")?.takeIf { it in catalog.codes("ambulanceType") }

    private fun crit(uid: String, place: Place, type: String?, f: FareParams): Crit =
        Crit(uid, place.p != null, place.p?.first, place.p?.second, place.upazilaId, type, f.dest, f.oxygen, f.night, f.quote, f.maxFare, f.includeUnknown)

    // ------------------------------------------------------------------ actions

    /**
     * ambreq_create — "call an ambulance". body: district, upazilaId, lat/lng + gps:true, type, pickup, note, phone,
     * ambulanceId (chosen in the sheet — first), dest, maxFare, includeUnknown, oxygen.
     */
    fun create(lang: String?, b: Body, uid: String): Map<String, Any?> {
        val now = AmbClock.now()
        val district = b.string("district")
        if (!Places.isDistrict(district)) throw err(lang, 400, "Choose a district", "জেলা বেছে নিন")
        val place = placeOf(b)
        // union: only for the driver to know (with the address) — the choice is by upazila
        val unionId = if (place.upazilaId != null && !place.upazilaAuto) {
            AmbFare.intId(b["unionId"])?.takeIf { Places.UNION[it]?.upazilaId == place.upazilaId }
        } else {
            null
        }
        val type = typeOf(b)
        val phone = Phones.normalizeBd(Js.toStr(b["phone"]))
            ?: throw err(lang, 400, "Enter a mobile number the driver can call", "চালক ফোন করতে পারেন এমন মোবাইল নম্বর দিন")
        // with a point (GPS/map) the written place is optional
        val pickup = ListingRulesStr.str(b["pickup"], 160)
        if (pickup.isEmpty() && place.p == null) {
            throw err(lang, 400, "Write where to pick up (address or landmark)", "কোথা থেকে নেবে লিখুন (ঠিকানা বা চেনা জায়গা)")
        }
        val note = ListingRulesStr.str(b["note"], 200)
        val fp = fareParams(lang, b, now)
        val r = crit(uid, place, type, fp)

        val all = candidates(repo.approvedIn(district!!), r, now)
        val list = affordable(all, r)
        // a car chosen in the sheet goes first (even when "unknown"; not when on a trip)
        val chosenId = b.string("ambulanceId")?.takeIf { Rules.isValidId(it) }
        var first = chosenId?.let { cid -> list.firstOrNull { it.id == cid } }
        if (first == null && chosenId != null) {
            val a = repo.get(chosenId)
            if (a != null && a.district == district) first = affordable(candidates(listOf(a), r, now), r).firstOrNull()
        }
        val wave1 = (listOfNotNull(first) + list.filter { first == null || it.owner != first.owner }).take(WAVE)
        if (wave1.isEmpty() && r.quote && all.isNotEmpty()) {
            throw err(lang, 409, "No ambulance within this fare — raise the fare", "এই ভাড়ায় কোনো গাড়ি নেই — ভাড়া বাড়ান", "no_fare_match")
        }
        if (wave1.isEmpty()) {
            throw err(
                lang, 409, "No free ambulance nearby right now — please call one from the list",
                "এখন কাছে কোনো খালি অ্যাম্বুলেন্স নেই — তালিকা থেকে ফোন করুন", "no_ambulance",
            )
        }
        val name = jdbc.queryForList("SELECT name FROM user_profiles WHERE uid = ?", String::class.java, uid).firstOrNull() ?: ""
        val id = Ids.newId()
        // one active request at a time, 5 an hour — in a transaction, so two taps do not make two
        tx {
            jdbc.update("INSERT INTO ambreq_patient_state (uid) VALUES (?) ON CONFLICT (uid) DO NOTHING", uid)
            val activeId = jdbc.queryForList("SELECT active_request_id FROM ambreq_patient_state WHERE uid = ? FOR UPDATE", String::class.java, uid).firstOrNull()
            val times = jdbc.queryForObject(
                "SELECT count(*) FROM ambreq_create_log WHERE uid = ? AND created_at > ?", Int::class.java, uid, Sql.ts(now - HOUR),
            ) ?: 0
            val today = jdbc.queryForObject(
                "SELECT count(*) FROM ambreq_create_log WHERE uid = ? AND created_at > ?", Int::class.java, uid, Sql.ts(now - 24 * HOUR),
            ) ?: 0
            if (activeId != null) {
                val a = jdbc.query(
                    "SELECT status, coalesce(opened_at, created_at) AS opened FROM ambulance_requests WHERE id = ?",
                    { rs, _ -> rs.getString("status") to (rs.ms("opened") ?: 0L) },
                    activeId,
                ).firstOrNull()
                val stale = a?.first == "open" && now - a.second >= EXPIRE_MS
                if (a != null && a.first in ACTIVE && !stale) {
                    throw err(lang, 409, "You already have an ambulance request running", "আপনার একটা অ্যাম্বুলেন্সের অনুরোধ এখনো চালু আছে", "amb_req_active")
                }
            }
            if (times >= PER_HOUR) {
                throw err(lang, 429, "Up to 5 ambulance requests an hour. Please call from the list.", "ঘণ্টায় সর্বোচ্চ ৫ বার ডাকা যায়। তালিকা থেকে ফোন করুন।")
            }
            if (today >= PER_DAY) {
                throw err(lang, 429, "Too many ambulance requests today. Please call from the list.", "আজ অনেকবার অ্যাম্বুলেন্স ডাকা হয়েছে। তালিকা থেকে ফোন করুন।") // bn-ok
            }
            // the same contact number from many accounts
            val byPhone = jdbc.queryForObject(
                "SELECT count(*) FROM ambulance_requests WHERE phone = ? AND created_at > ?", Int::class.java, Phones.localBd(phone), Sql.ts(now - HOUR),
            ) ?: 0
            if (byPhone >= PER_PHONE_HOUR) {
                throw err(lang, 429, "Too many requests with this phone number. Please call from the list.", "এই নম্বর দিয়ে অনেকবার ডাকা হয়েছে। তালিকা থেকে ফোন করুন।") // bn-ok
            }
            val dest = fp.dest
            jdbc.update(
                """
                INSERT INTO ambulance_requests (id, uid, patient_name, phone, district, upazila_id, upazila_auto, union_id, lat, lng, gps,
                  pickup, note, type, status, active, wave, quote, max_fare, include_unknown, oxygen, night,
                  dest_kind, dest_upazila_id, dest_district, dest_facility_id, dest_name, chosen_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'open', TRUE, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                id, uid, name.take(80), Phones.localBd(phone), district, place.upazilaId, place.upazilaAuto, unionId,
                place.p?.first, place.p?.second, place.p != null, pickup, note, type,
                fp.quote, fp.maxFare?.toInt(), fp.includeUnknown, fp.oxygen, fp.night,
                dest?.kind, dest?.upazilaId, dest?.district, dest?.facilityId, dest?.name, first?.id, Sql.ts(now),
            )
            wave1.forEach { insertTarget(id, it, 1, now) }
            jdbc.update("UPDATE ambreq_patient_state SET active_request_id = ?, at = ? WHERE uid = ?", id, Sql.ts(now), uid)
            jdbc.update("DELETE FROM ambreq_create_log WHERE uid = ? AND created_at <= ?", uid, Sql.ts(now - 24 * HOUR))
            jdbc.update("INSERT INTO ambreq_create_log (uid, created_at) VALUES (?, ?) ON CONFLICT DO NOTHING", uid, Sql.ts(now))
        }
        pushDrivers(id, wave1.associate { it.owner to kmCode(it) }, r.quote, wave1.map { it.owner })
        return linkedMapOf("id" to id, "notified" to wave1.size, "request" to patientView(load(id)!!, now, langOf(b)))
    }

    /**
     * ambreq_count — "N free cars within this fare" in the sheet: the same choice and fare rules as create, only
     * counting (no writes, no pushes). Node keeps the district's cars 10 s in memory; this reads them each time.
     */
    fun count(lang: String?, b: Body, uid: String): Map<String, Any?> {
        val now = AmbClock.now()
        val district = b.string("district")
        if (!Places.isDistrict(district)) throw err(lang, 400, "Choose a district", "জেলা বেছে নিন")
        val place = placeOf(b)
        val type = typeOf(b)
        val fp = fareParams(lang, b, now)
        val r = crit(uid, place, type, fp)
        val docs = repo.approvedIn(district!!)
        val all = candidates(docs, r, now)
        val count = affordable(all, r).size
        val out = linkedMapOf<String, Any?>()
        if (!r.quote) {
            out.putAll(
                linkedMapOf(
                    "count" to count, "total" to all.size, "unknown" to 0, "levels" to linkedMapOf<String, Any?>(), "raiseTo" to null,
                    "hospitals" to emptyList<String>(), "night" to AmbFare.isNight(now),
                ),
            )
            if (place.upazilaAuto) out["upazilaId"] = place.upazilaId
            return out
        }
        val levels = linkedMapOf<String, Any?>()
        AmbFare.LEVELS.forEach { l -> levels[l.toString()] = all.count { AmbFare.fits(it.est, l, r.includeUnknown) } }
        // hospitals in the nearby cars' charts (names only — not which car)
        val ids = all.map { it.id }.toSet()
        val seen = HashSet<String>()
        val hospitals = ArrayList<String>()
        for (d in docs) {
            if (d.id !in ids) continue
            for (row in d.rows) {
                if (row.dest.kind != "hospital") continue
                val n = row.dest.name ?: ""
                val k = n.lowercase()
                if (k.isNotEmpty() && k !in seen && hospitals.size < 30) {
                    seen.add(k)
                    hospitals.add(n)
                }
            }
        }
        out["count"] = count
        out["total"] = all.size
        out["unknown"] = all.count { it.est == null }
        out["levels"] = levels
        out["raiseTo"] = if (count > 0) null else AmbFare.raiseTo(all.map { it.est }, r.maxFare)
        out["hospitals"] = hospitals
        out["night"] = r.night
        if (place.upazilaAuto) out["upazilaId"] = place.upazilaId
        return out
    }

    /** ambreq_status — the waiting page: by id, or the patient's active one (comes back after reopening the app) */
    fun status(lang: String?, b: Body, uid: String): Map<String, Any?> {
        val now = AmbClock.now()
        val idNode = b["id"]
        val hasId = idNode != null && !idNode.isNull
        val id: String = if (hasId) {
            loadMine(lang, b.string("id"), false, uid).id
        } else {
            jdbc.queryForList("SELECT active_request_id FROM ambreq_patient_state WHERE uid = ?", String::class.java, uid).firstOrNull()
                ?: return linkedMapOf("request" to null)
        }
        val x = advance(id, now)
        if (x == null || x.uid != uid) return linkedMapOf("request" to null)
        // without id: only active (or closed within 10 minutes, to show "expired"/"done")
        if (!hasId && x.status !in ACTIVE && now - (x.closedAt ?: 0) > 10 * MIN) return linkedMapOf("request" to null)
        return linkedMapOf("request" to patientView(x, now, langOf(b)))
    }

    /** who to tell after the patient cancels: the accepted driver, and drivers whose fare is still waiting for an answer */
    private class Cancelled(val accepted: String?, val quoted: List<String>)

    /** cancel by the patient (open or after accepting) */
    private fun cancelByPatient(id: String, reason: String, now: Long): Cancelled? = tx {
        val c = load(id, true) ?: return@tx null
        if (c.status !in ACTIVE) return@tx null
        val amb = c.ambulanceId?.let { repo.get(it, lock = true) }
        updateReq(id, closeSet(now, "cancelled").value("cancelled_by", "patient").value("cancel_reason", reason))
        deactivateAll(id)
        repo.freeVehicle(amb, now)
        // a quote past its 2 minutes was already released ("you are free") by the sweep
        Cancelled(c.acceptedBy, c.quotes.filter { now - it.at < QUOTE_MS && it.owner != c.acceptedBy }.map { it.owner }.distinct())
    }

    fun cancel(lang: String?, b: Body, uid: String): Map<String, Any?> {
        val x = loadMine(lang, b.string("id"), false, uid)
        val reason = b.string("reason")?.takeIf { it in PATIENT_CANCEL } ?: "other"
        val done = cancelByPatient(x.id, reason, AmbClock.now())
        val driver = done?.accepted
        if (driver != null) pushDriver(x.id, driver, "The patient cancelled the ambulance request", "রোগী অ্যাম্বুলেন্সের অনুরোধ বাতিল করেছেন")
        // drivers who sent a fare and were waiting for the patient's answer: free for other calls now (push + bell)
        pushOwners(
            x.id, done?.quoted.orEmpty(),
            "The patient cancelled the ambulance request — you are free for other calls",
            "রোগী অ্যাম্বুলেন্সের অনুরোধ বাতিল করেছেন — আপনি অন্য ডাক নিতে পারেন", // bn-ok
        )
        return linkedMapOf("ok" to true)
    }

    /** ambreq_driver — the driver's calls: open requests sent to me and the one I took */
    fun driverList(b: Body, uid: String): Map<String, Any?> {
        val now = AmbClock.now()
        val ids = jdbc.queryForList(
            "SELECT request_id FROM ambreq_targets WHERE owner_uid = ? AND active ORDER BY request_id COLLATE \"C\" LIMIT 30", String::class.java, uid,
        )
        val items = ids.mapNotNull { advance(it, now) }
            .filter { x ->
                x.status in ACTIVE && uid in x.activeOwners && uid !in x.declined && (x.status == "open" || x.acceptedBy == uid) && sameWorld(uid, x.uid)
            }
            .map { driverView(it, uid, langOf(b)) }
            .sortedWith(compareBy<Map<String, Any?>> { if (it["status"] == "open") 1 else 0 }.thenByDescending { it["createdAt"] as Long })
        return linkedMapOf("items" to items)
    }

    private sealed interface AcceptResult

    private object Already : AcceptResult

    private class Quoted(val patient: String, val name: String, val fare: Int) : AcceptResult

    private class Accepted(val patient: String, val name: String) : AcceptResult

    /**
     * ambreq_accept — "Coming". Fare requests: body.fare (whole taka, not above the budget) → an offer the patient
     * agrees to (ambreq_agree); without fare (old app) the estimate. Old requests: the first to accept gets it.
     */
    fun accept(lang: String?, b: Body, uid: String): Map<String, Any?> {
        val x0 = loadMine(lang, b.string("id"), true, uid)
        val id = x0.id
        val now = AmbClock.now()
        advance(id, now)
        val res: AcceptResult = tx {
            val x = load(id, true)!!
            if (x.status != "open") {
                if (x.acceptedBy == uid) return@tx Already
                val (en, bn) = when (x.status) {
                    "expired" -> "This request has expired" to "এই অনুরোধের সময় শেষ"
                    "cancelled" -> "The patient cancelled this request" to "রোগী অনুরোধটা বাতিল করেছেন"
                    "byPhone" -> "The patient already got an ambulance by phone — no longer needed" to "রোগী ফোনে অন্য গাড়ি পেয়ে গেছেন — আর দরকার নেই"
                    else -> "Another driver already took this request" to "অন্য একজন চালক আগেই নিয়ে নিয়েছেন"
                }
                throw err(lang, 409, en, bn, "taken")
            }
            if (uid in x.declined || uid !in x.activeOwners) throw notFound(lang)
            // time ran out between advance and this transaction: no
            if (now - x.opened >= EXPIRE_MS) throw err(lang, 409, "This request has expired", "এই অনুরোধের সময় শেষ", "taken")
            val ambulanceId = x.sentTo.getValue(uid)
            val amb = repo.get(ambulanceId, lock = true)
            if (amb == null || amb.ownerUid != uid || amb.status != "approved") {
                throw err(lang, 409, "This ambulance is not listed now", "এই অ্যাম্বুলেন্স এখন তালিকায় নেই")
            }
            if (x.quote) {
                // a car on another patient's trip cannot offer
                if (amb.reqBusy != null && amb.reqBusy != id) throw err(lang, 409, "Your ambulance is on another trip now", "আপনার গাড়ি এখন অন্য ট্রিপে", "busy")
                var fare = AmbFare.taka(b["fare"])
                if (fare == null) fare = x.est(uid)?.toLong()
                if (fare == null) throw err(lang, 400, "Write your fare", "ভাড়া লিখুন", "fare_needed")
                if (fare == AmbFare.NAN || fare < AmbFare.MIN_FARE || fare > AmbFare.MAX_FARE) {
                    throw err(lang, 400, "Write the fare in whole taka (৳100 or more)", "ভাড়া পুরো টাকায় লিখুন (৳১০০ বা বেশি)")
                }
                val max = x.maxFare
                if (max != null && fare > max) {
                    throw err(
                        lang, 409, "The patient's limit is ৳$max — you can't offer more",
                        "রোগীর সর্বোচ্চ ভাড়া ৳${Lang.bnNumbers(max.toString())} — এর বেশি দেওয়া যায় না", "over_budget",
                    )
                }
                jdbc.update(
                    """
                    INSERT INTO ambreq_quotes (request_id, owner_uid, ambulance_id, name, types, fare, rating, rating_count, at)
                    VALUES (?, ?, ?, ?, ?::text[], ?, ?, ?, ?)
                    ON CONFLICT (request_id, owner_uid) DO UPDATE SET ambulance_id = EXCLUDED.ambulance_id, name = EXCLUDED.name,
                      types = EXCLUDED.types, fare = EXCLUDED.fare, rating = EXCLUDED.rating, rating_count = EXCLUDED.rating_count, at = EXCLUDED.at
                    """.trimIndent(),
                    id, uid, ambulanceId, amb.name, textArray(amb.types), fare.toInt(), AmbRules.rating(amb.ratingSum, amb.ratingCount),
                    amb.ratingCount, Sql.ts(now),
                )
                return@tx Quoted(x.uid, amb.name, fare.toInt())
            }
            updateReq(
                id,
                SqlSet().value("status", "accepted").value("accepted_by", uid).value("ambulance_id", ambulanceId).value("ambulance_name", amb.name)
                    .value("driver_phone", Phones.fixPhone(amb.phone)).ms("accepted_at", now),
            )
            jdbc.update("UPDATE ambreq_targets SET active = (owner_uid = ?) WHERE request_id = ?", uid, id)
            // on a trip (list: "busy"); free again at the end — reqBusy tells it was this request
            repo.markBusy(ambulanceId, id, now)
            Accepted(x.uid, amb.name)
        }
        when (res) {
            is Quoted -> pushPatient(
                id, res.patient, "${res.name} can come for ৳${res.fare}", Lang.bnNumbers("${res.name} ৳${res.fare}-তে আসতে রাজি"),
                "Open and tap \"Agree\" to confirm.", "নিশ্চিত করতে খুলে \"রাজি\" চাপুন।",
            )
            is Accepted -> pushPatient(id, res.patient, "${res.name} is coming", "${res.name} আসছে", "Open to see the driver's phone.", "চালকের ফোন নম্বর দেখতে খুলুন।")
            else -> {}
        }
        return linkedMapOf("ok" to true, "request" to driverView(load(id)!!, uid, langOf(b)))
    }

    private fun textArray(list: List<String>): String = list.joinToString(",", "{", "}") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" }

    private sealed interface AgreeResult

    private object Same : AgreeResult

    private class Busy(val owner: String) : AgreeResult

    private class Agreed(val owner: String, val fare: Int, val others: List<String>) : AgreeResult

    /**
     * ambreq_agree — the patient agrees to an offer (body.ambulanceId): still open, offer within 2 minutes, the driver
     * still there, the car approved and not on another trip → accepted with agreedFare. Everyone else is released;
     * the driver's offers on other requests are withdrawn.
     */
    fun agree(lang: String?, b: Body, uid: String): Map<String, Any?> {
        val x0 = loadMine(lang, b.string("id"), false, uid)
        val id = x0.id
        val target = b.string("ambulanceId")
        if (!Rules.isValidId(target)) throw err(lang, 400, "Choose an offer", "একটা প্রস্তাব বেছে নিন")
        val now = AmbClock.now()
        advance(id, now)
        fun gone() = err(lang, 409, "This offer is no longer available — choose another", "এই প্রস্তাব আর নেই — অন্যটা বেছে নিন", "quote_gone")
        val res: AgreeResult = tx {
            val x = load(id, true)!!
            if (x.status != "open") {
                if ((x.status == "accepted" || x.status == "arrived") && x.ambulanceId == target) return@tx Same
                throw err(lang, 409, "This request is no longer open", "এই অনুরোধ আর খোলা নেই", "state")
            }
            val q = x.quotes.firstOrNull { it.ambulanceId == target }
            if (q == null || now - q.at >= QUOTE_MS) throw gone()
            val owner = q.owner
            if (owner !in x.activeOwners || owner in x.declined) throw gone()
            val amb = repo.get(target, lock = true)
            if (amb == null || amb.ownerUid != owner || amb.status != "approved" || (amb.reqBusy != null && amb.reqBusy != id)) {
                return@tx Busy(owner)
            }
            updateReq(
                id,
                SqlSet().value("status", "accepted").value("accepted_by", owner).value("ambulance_id", target).value("ambulance_name", amb.name)
                    .value("driver_phone", Phones.fixPhone(amb.phone)).ms("accepted_at", now).value("agreed_fare", q.fare),
            )
            jdbc.update("UPDATE ambreq_targets SET active = (owner_uid = ?) WHERE request_id = ?", owner, id)
            repo.markBusy(target!!, id, now)
            Agreed(owner, q.fare, x.activeOwners.filter { it != owner })
        }
        if (res is Busy) {
            // the car went on another trip / off the list meanwhile — drop the offer, the patient chooses another
            runCatching { jdbc.update("DELETE FROM ambreq_quotes WHERE request_id = ? AND owner_uid = ?", id, res.owner) }
            throw gone()
        }
        if (res is Agreed) {
            pushDriver(id, res.owner, "The patient agreed — ৳${res.fare}. Please go now.", Lang.bnNumbers("রোগী রাজি — ৳${res.fare}। এখনই রওনা দিন।"))
            pushReleased(id, res.others)
            withdrawElsewhere(id, res.owner)
        }
        return linkedMapOf("ok" to true, "request" to patientView(load(id)!!, now, langOf(b)))
    }

    /**
     * the driver is confirmed on one request: withdraw their offers on other open requests and take those off their
     * list (best effort; agree also checks the car is busy).
     */
    private fun withdrawElsewhere(id: String, owner: String) {
        val others = try {
            jdbc.queryForList(
                "SELECT request_id FROM ambreq_targets WHERE owner_uid = ? AND active AND request_id <> ? ORDER BY request_id COLLATE \"C\" LIMIT 30",
                String::class.java, owner, id,
            )
        } catch (e: Exception) {
            emptyList()
        }
        for (rid in others) {
            try {
                tx {
                    val c = load(rid, true) ?: return@tx
                    if (c.status != "open") return@tx
                    jdbc.update("DELETE FROM ambreq_quotes WHERE request_id = ? AND owner_uid = ?", rid, owner)
                    jdbc.update("UPDATE ambreq_targets SET active = FALSE WHERE request_id = ? AND owner_uid = ?", rid, owner)
                    Unit
                }
            } catch (e: Exception) {
                log.warn("amb req withdraw failed: {}", e.message)
            }
        }
    }

    /**
     * ambreq_budget — "raise the fare" (while waiting, or when nothing matched): a higher budget (or any) and/or
     * includeUnknown. Up to 5 newly matching nearby cars are asked; the 3 minutes start again. Never lower.
     */
    fun budget(lang: String?, b: Body, uid: String): Map<String, Any?> {
        val x00 = loadMine(lang, b.string("id"), false, uid)
        val id = x00.id
        val now = AmbClock.now()
        val x0 = advance(id, now)!!
        if (!x0.quote || x0.status != "open") throw err(lang, 409, "This request is no longer open", "এই অনুরোধ আর খোলা নেই", "state")
        val maxFare = AmbFare.budgetOf(b["maxFare"])
        if (maxFare == AmbFare.NAN) {
            throw err(lang, 400, "Write the fare limit in whole taka (৳100 or more)", "সর্বোচ্চ ভাড়া পুরো টাকায় লিখুন (৳১০০ বা বেশি)")
        }
        val lower = if (x0.maxFare == null) maxFare != null else maxFare != null && maxFare < x0.maxFare
        if (lower) throw err(lang, 400, "The fare limit can only be raised", "সর্বোচ্চ ভাড়া শুধু বাড়ানো যায়")
        val includeUnknown = b.isTrue("includeUnknown") || x0.includeUnknown
        val r = x0.crit.copy(maxFare = maxFare, includeUnknown = includeUnknown)
        val all = candidates(repo.approvedIn(x0.district), r, now)
        val sent0 = x0.sentTo.keys
        val called0 = x0.calledIds
        fun notYet(c: Cand) = c.owner !in sent0 && c.id !in called0
        val ok = affordable(all, r)
        val okIds = ok.map { it.id }.toSet()
        val next = ok.filter { notYet(it) }.take(WAVE)
        val left = all.filter { notYet(it) && it.id !in okIds }
        val fresh = tx {
            val x = load(id, true)!!
            if (x.status != "open") throw err(lang, 409, "This request is no longer open", "এই অনুরোধ আর খোলা নেই", "state")
            val f = next.filter { x.sentTo[it.owner] == null && it.id !in x.calledIds }
            updateReq(
                id,
                SqlSet().value("max_fare", maxFare?.toInt()).value("include_unknown", includeUnknown).ms("opened_at", now).ms("raised_at", now)
                    .value("raise_to", AmbFare.raiseTo(left.map { it.est }, maxFare)?.toInt())
                    .value("unknown_free", if (includeUnknown) 0 else left.count { it.est == null && it.avail }),
            )
            f.forEach { insertTarget(id, it, x.wave, now) }
            f
        }
        pushDrivers(id, fresh.associate { it.owner to kmCode(it) }, true, fresh.map { it.owner })
        return linkedMapOf("ok" to true, "added" to fresh.size, "request" to patientView(load(id)!!, now, langOf(b)))
    }

    /** ambreq_decline — "can't": off this driver's list; when everyone declined the circle widens early */
    fun decline(lang: String?, b: Body, uid: String): Map<String, Any?> {
        val x = loadMine(lang, b.string("id"), true, uid)
        tx {
            val c = load(x.id, true) ?: return@tx
            if (c.status != "open") return@tx
            jdbc.update("UPDATE ambreq_targets SET declined = TRUE, active = FALSE WHERE request_id = ? AND owner_uid = ?", x.id, uid)
            Unit
        }
        try {
            advance(x.id, AmbClock.now())
        } catch (e: Exception) {
            log.warn("amb req advance failed: {}", e.message)
        }
        return linkedMapOf("ok" to true)
    }

    private class StepOut(val x: Req)

    /** the driver's step on the request they took: arrived / done / cancel(reason) */
    private fun driverStep(lang: String?, id: String, uid: String, step: String, reason: String, now: Long) {
        val out: StepOut? = tx {
            val x = load(id, true)!!
            if (x.acceptedBy != uid || (x.status != "accepted" && x.status != "arrived")) {
                throw err(lang, 409, "This request is already closed", "এই অনুরোধ আগেই বন্ধ হয়েছে")
            }
            if (step == "arrived") {
                if (x.status == "arrived") return@tx null
                updateReq(id, SqlSet().value("status", "arrived").ms("arrived_at", now))
                return@tx StepOut(x)
            }
            val amb = x.ambulanceId?.let { repo.get(it, lock = true) }
            updateReq(
                id,
                if (step == "done") {
                    closeSet(now, "done").ms("done_at", now)
                } else {
                    closeSet(now, "cancelled").value("cancelled_by", "driver").value("cancel_reason", reason)
                },
            )
            deactivateAll(id)
            repo.freeVehicle(amb, now)
            StepOut(x)
        }
        if (out == null) return
        val x = out.x
        if (step == "done") recordTrip(x.uid, x.ambulanceId!!, now, x.agreedFare)
        val name = x.ambulanceName ?: ""
        when (step) {
            "arrived" -> pushPatient(id, x.uid, "$name has arrived", "$name পৌঁছেছে")
            "done" -> pushPatient(id, x.uid, "Trip finished — rate the ambulance", "ট্রিপ শেষ — অ্যাম্বুলেন্সকে রেটিং দিন")
            else -> pushPatient(id, x.uid, "$name cancelled", "$name বাতিল করেছে", "Please call another ambulance from the list.", "তালিকা থেকে অন্য অ্যাম্বুলেন্সে ফোন করুন।")
        }
    }

    /** ambreq_step — body.step arrived | done | cancel (+ reason) */
    fun step(lang: String?, b: Body, uid: String): Map<String, Any?> {
        val x = loadMine(lang, b.string("id"), true, uid)
        val step = b.string("step")
        if (step != "arrived" && step != "done" && step != "cancel") throw err(lang, 400, "Not found", "পাওয়া যায়নি")
        val reason = b.string("reason")?.takeIf { it in DRIVER_CANCEL } ?: "other"
        driverStep(lang, x.id, uid, step, reason, AmbClock.now())
        return linkedMapOf("ok" to true)
    }

    /**
     * a finished request counts like a call for rating (ambulanceCalls, with tripAt — no 1-hour wait); the agreed fare
     * allows "charged more than agreed".
     */
    private fun recordTrip(uid: String, ambulanceId: String, now: Long, agreedFare: Int?) {
        try {
            val prev = jdbc.query(
                "SELECT at, agreed_fare FROM ambulance_calls WHERE uid = ? AND ambulance_id = ?",
                { rs, _ -> (rs.ms("at") ?: 0L) to rs.intOrNull("agreed_fare") },
                uid, ambulanceId,
            ).firstOrNull()
            // the agreed fare (2.30.0): "charged more than agreed" only for such trips; a trip without one clears it
            jdbc.update(
                """
                INSERT INTO ambulance_calls (uid, ambulance_id, at, first_at, trip_at, agreed_fare) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (uid, ambulance_id) DO UPDATE SET at = EXCLUDED.at, trip_at = EXCLUDED.trip_at, agreed_fare = EXCLUDED.agreed_fare
                """.trimIndent(),
                uid, ambulanceId, Sql.ts(now), Sql.ts(now), Sql.ts(now), agreedFare,
            )
            if (prev == null || now - prev.first > 10 * MIN) stats.bumpCall("ambulance", ambulanceId, now, prev == null)
        } catch (e: Exception) {
            log.warn("amb trip record failed: {}", e.message)
        }
    }

    private class ByPhoneOut(val same: Boolean, val others: List<String> = emptyList(), val driver: String? = null)

    /**
     * byPhone close: [ambulanceId] must be a car phoned during this request. [replacing] = drop the accepted driver
     * (rule 5), else only from open. One accepted car at a time — everything checks the status in the transaction.
     */
    private fun byPhone(lang: String?, b: Body, id: String, uid: String, ambulanceId: String?, replacing: Boolean): Map<String, Any?> {
        val now = AmbClock.now()
        if (!Rules.isValidId(ambulanceId)) throw err(lang, 400, "Choose the ambulance you got", "যে গাড়ি পেয়েছেন সেটা বেছে নিন")
        val out: ByPhoneOut = tx {
            val x = load(id, true)!!
            if (ambulanceId !in x.calledIds) throw err(lang, 400, "Choose an ambulance you called", "যে গাড়িতে ফোন করেছেন সেটা বেছে নিন")
            val acceptedNow = x.status == "accepted" || x.status == "arrived"
            val ok = if (replacing) acceptedNow else x.status == "open"
            if (!ok) {
                // the same car accepted in the app — nothing else to do
                if (acceptedNow && x.ambulanceId == ambulanceId) return@tx ByPhoneOut(true)
                throw err(
                    lang, 409, if (x.status == "open") "Nobody has accepted yet" else "This request is already closed",
                    if (x.status == "open") "এখনো কেউ রাজি হননি" else "এই অনুরোধ আগেই বন্ধ হয়েছে", "state",
                )
            }
            if (replacing && x.ambulanceId == ambulanceId) return@tx ByPhoneOut(true)
            val a = repo.get(ambulanceId, lock = true)
            val acc = if (replacing) x.ambulanceId?.let { repo.get(it, lock = true) } else null
            val info = x.calls.firstOrNull { it.ambulanceId == ambulanceId }
            val set = closeSet(now, "byPhone").value("by_phone_id", ambulanceId)
            if (replacing) {
                addReplaced(id, x.acceptedBy ?: "", x.ambulanceId ?: "", now)
                set.nul("accepted_by")
            }
            set.value("ambulance_id", ambulanceId)
                .value("ambulance_name", a?.name?.takeIf { it.isNotEmpty() } ?: info?.name ?: "")
                .value("driver_phone", a?.let { Phones.fixPhone(it.phone) }?.takeIf { it.isNotEmpty() } ?: info?.phone ?: "")
                .value("booked_owner", a?.ownerUid)
            updateReq(id, set)
            deactivateAll(id)
            if (replacing) repo.freeVehicle(acc, now)
            val others = (if (replacing) emptyList() else x.activeOwners).filter { a == null || it != a.ownerUid }
            ByPhoneOut(false, others, if (replacing) x.acceptedBy else null)
        }
        if (!out.same) {
            // rating eligibility: like a phone call (1 hour later), linked to this request
            try {
                jdbc.update(
                    """
                    INSERT INTO ambulance_calls (uid, ambulance_id, at, first_at, by_phone_req) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (uid, ambulance_id) DO UPDATE SET at = EXCLUDED.at, by_phone_req = EXCLUDED.by_phone_req
                    """.trimIndent(),
                    uid, ambulanceId, Sql.ts(now), Sql.ts(now), id,
                )
            } catch (e: Exception) {
                log.warn("amb byPhone call record failed: {}", e.message)
            }
            out.driver?.let { politeCancel(id, it) }
            pushNoLonger(id, out.others)
        }
        return linkedMapOf("ok" to true, "request" to patientView(load(id)!!, now, langOf(b)))
    }

    /** ambreq_byphone — "got a car by phone?" → this car (phoned during this request) */
    fun byPhoneAction(lang: String?, b: Body, uid: String): Map<String, Any?> {
        val x = loadMine(lang, b.string("id"), false, uid)
        return byPhone(lang, b, x.id, uid, b.string("ambulanceId"), false)
    }

    /**
     * ambreq_replace — someone accepted but the patient got another car by phone: that driver is dropped
     * (patient_found_other, polite push). With ambulanceId (a phoned car) → byPhone; without → open again.
     */
    fun replace(lang: String?, b: Body, uid: String): Map<String, Any?> {
        val x0 = loadMine(lang, b.string("id"), false, uid)
        val id = x0.id
        if (!b.isNullish("ambulanceId")) return byPhone(lang, b, id, uid, b.string("ambulanceId"), true)
        val now = AmbClock.now()
        class Out(val driver: String, val owners: List<String>, val km: Map<String, Int?>, val quote: Boolean)
        val out: Out = tx {
            val x = load(id, true)!!
            if (x.status != "accepted" && x.status != "arrived") throw err(lang, 409, "Nothing to change now", "এখন বদলানোর কিছু নেই")
            val amb = x.ambulanceId?.let { repo.get(it, lock = true) }
            val driver = x.acceptedBy ?: ""
            val declined = (x.declined + driver).toSet()
            val owners = x.sentTo.keys.filter { it !in declined }
            val set = SqlSet().value("status", "open").ms("opened_at", now).value("active", true)
                .nul("accepted_by").nul("ambulance_id").nul("ambulance_name").nul("driver_phone").nul("accepted_at").nul("arrived_at")
            if (x.quote) set.nul("agreed_fare")
            updateReq(id, set)
            jdbc.update("UPDATE ambreq_targets SET declined = TRUE WHERE request_id = ? AND owner_uid = ?", id, driver)
            jdbc.update(
                "UPDATE ambreq_targets SET active = (NOT declined) WHERE request_id = ?",
                id,
            )
            addReplaced(id, driver, x.ambulanceId ?: "", now)
            // fare requests: earlier offers and the agreed fare go — the others offer again
            if (x.quote) jdbc.update("DELETE FROM ambreq_quotes WHERE request_id = ?", id)
            repo.freeVehicle(amb, now)
            Out(driver, owners, x.targets.associate { it.owner to it.km }, x.quote)
        }
        politeCancel(id, out.driver)
        pushDrivers(id, out.km, out.quote, out.owners)
        return linkedMapOf("ok" to true, "request" to patientView(load(id)!!, now, langOf(b)))
    }

    /**
     * attachCall: the patient phoned a car while their request runs (ambulance_called + reqId) → calledIds. While open,
     * that owner gets the request in their list ("the patient called you"), even when not sent before or declined;
     * pushed on the first call.
     */
    fun attachCall(uid: String, reqId: Any?, ambulanceId: String?): Boolean {
        val rid = reqId as? String
        if (!Rules.isValidId(rid) || !Rules.isValidId(ambulanceId)) return false
        val now = AmbClock.now()
        class Out(val owner: String, val push: Boolean)
        val out: Out? = tx {
            val c = load(rid!!, true)
            val a = repo.get(ambulanceId)
            if (c == null || c.uid != uid || c.status !in ACTIVE || a == null) return@tx null
            val owner = a.ownerUid
            if (owner.isEmpty() || owner == uid || !sameWorld(uid, owner)) return@tx null
            val first = ambulanceId !in c.calledIds
            jdbc.update(
                """
                INSERT INTO ambreq_calls (request_id, ambulance_id, name, phone, at) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (request_id, ambulance_id) DO UPDATE SET name = EXCLUDED.name, phone = EXCLUDED.phone
                """.trimIndent(),
                rid, ambulanceId, a.name, Phones.fixPhone(a.phone), Sql.ts(now),
            )
            var added = false
            if (c.status == "open" && a.status == "approved") {
                val t = c.targets.firstOrNull { it.owner == owner }
                // the owner's other car was sent before: the phoned car counts as theirs
                if (t == null || t.ambulanceId != ambulanceId) {
                    val base = AmbRules.baseOf(a)
                    val km: Int? = if (c.gps && c.lat != null && c.lng != null && base != null) {
                        val real = AmbRules.distanceKm(c.lat, c.lng, base.lat, base.lng)
                        if (base.exact) AmbRules.wholeKm(real) else -1 - AmbRules.wholeKm(real)
                    } else {
                        null
                    }
                    // fare requests: the phoned car's estimate (to offer; the budget rule is the same)
                    val est: Int? = if (c.quote) AmbFare.estimate(a.chart, a.veh, c.dest, c.type, c.oxygen, c.night) else t?.est
                    if (t == null) {
                        jdbc.update(
                            "INSERT INTO ambreq_targets (request_id, owner_uid, ambulance_id, wave, active, est_fare, km, sent_at) VALUES (?, ?, ?, ?, FALSE, ?, ?, ?)",
                            rid, owner, ambulanceId, c.wave, est, km?.toDouble(), Sql.ts(now),
                        )
                    } else {
                        jdbc.update(
                            "UPDATE ambreq_targets SET ambulance_id = ?, km = ?, est_fare = ? WHERE request_id = ? AND owner_uid = ?",
                            ambulanceId, km?.toDouble(), est, rid, owner,
                        )
                    }
                }
                if (t == null || !t.active || t.declined) {
                    jdbc.update("UPDATE ambreq_targets SET active = TRUE, declined = FALSE WHERE request_id = ? AND owner_uid = ?", rid, owner)
                    added = true
                }
            }
            Out(owner, c.status == "open" && (first || added))
        }
        if (out != null && out.push) {
            pushDriver(rid!!, out.owner, "The patient called you — tap \"Coming\" in the app", "রোগী আপনাকে ফোন করেছেন — অ্যাপে \"আসছি\" চাপুন")
        }
        return out != null
    }

    // ------------------------------------------------------------------ jobs

    /**
     * daily job: (1) advance/close lingering active requests, (2) 24 h after closing remove the exact place, phone,
     * address, note, name and destination.
     */
    fun dailyCleanup(now: Long): Map<String, Any?> {
        val live = jdbc.queryForList("SELECT id FROM ambulance_requests WHERE active ORDER BY id COLLATE \"C\" LIMIT 300", String::class.java)
        for (id in live) {
            try {
                advance(id, now)
            } catch (e: Exception) {
                log.warn("amb req advance failed: {}", e.message)
            }
        }
        var purged = 0
        for (page in 0 until 20) {
            val n = jdbc.update(
                """
                UPDATE ambulance_requests SET lat = NULL, lng = NULL, phone = NULL, pickup = NULL, note = NULL, patient_name = '',
                  dest_kind = NULL, dest_upazila_id = NULL, dest_district = NULL, dest_facility_id = NULL, dest_name = NULL,
                  purge_at = NULL, purged_at = ?
                WHERE id IN (SELECT id FROM ambulance_requests WHERE purge_at <= ? LIMIT 300)
                """.trimIndent(),
                Sql.ts(now), Sql.ts(now),
            )
            purged += n
            if (n < 300) break
        }
        return linkedMapOf("advanced" to live.size, "purged" to purged)
    }

    /** the minute job: open requests whose timers ran out (pushes on time even when nobody is reading) */
    fun advanceOpen(now: Long): Int {
        val ids = jdbc.queryForList("SELECT id FROM ambulance_requests WHERE active AND status = 'open' ORDER BY created_at LIMIT 300", String::class.java)
        for (id in ids) {
            try {
                advance(id, now)
            } catch (e: Exception) {
                log.warn("amb req advance failed: {}", e.message)
            }
        }
        return ids.size
    }

    /**
     * account deletion: as a patient, active requests are cancelled (the driver is told); [full] deletes all own
     * requests. As a driver: the accepted request is cancelled (the patient is told), open ones drop the driver.
     */
    fun closeForUser(uid: String, full: Boolean): Int {
        val now = AmbClock.now()
        val mine = jdbc.query(
            "SELECT id, status FROM ambulance_requests WHERE uid = ? LIMIT 500", { rs, _ -> rs.getString(1) to rs.getString(2) }, uid,
        )
        for ((id, status) in mine) {
            if (status in ACTIVE) {
                try {
                    val driver = cancelByPatient(id, "other", now)
                    if (driver != null) pushDriver(id, driver, "The patient cancelled the ambulance request", "রোগী অ্যাম্বুলেন্সের অনুরোধ বাতিল করেছেন")
                } catch (e: Exception) {
                    log.warn("amb req cancel for deletion failed: {}", e.message)
                }
            }
            if (full) runCatching { jdbc.update("DELETE FROM ambulance_requests WHERE id = ?", id) }
        }
        val driving = jdbc.queryForList(
            "SELECT request_id FROM ambreq_targets WHERE owner_uid = ? AND active LIMIT 50", String::class.java, uid,
        )
        for (rid in driving) {
            val x = load(rid) ?: continue
            try {
                if (x.acceptedBy == uid) {
                    driverStep(null, rid, uid, "cancel", "other", now)
                } else if (x.status == "open") {
                    jdbc.update("UPDATE ambreq_targets SET active = FALSE WHERE request_id = ? AND owner_uid = ?", rid, uid)
                    jdbc.update("DELETE FROM ambreq_quotes WHERE request_id = ? AND owner_uid = ?", rid, uid)
                }
            } catch (e: Exception) {
                log.warn("amb req driver close for deletion failed: {}", e.message)
            }
        }
        if (full) {
            jdbc.update("DELETE FROM ambreq_patient_state WHERE uid = ?", uid)
            jdbc.update("DELETE FROM ambreq_create_log WHERE uid = ?", uid)
        }
        return mine.size
    }
}

/** care.js str for JSON body values */
internal object ListingRulesStr {
    fun str(v: com.fasterxml.jackson.databind.JsonNode?, max: Int): String =
        com.lifolo.admin.kernel.ListingRules.str(Js.string(v), max)
}
