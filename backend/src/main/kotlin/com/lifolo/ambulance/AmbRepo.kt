package com.lifolo.ambulance

import com.lifolo.admin.kernel.ListingKind
import com.lifolo.admin.kernel.ListingRows
import com.lifolo.common.Geo
import com.lifolo.common.Js
import com.lifolo.common.Phones
import com.lifolo.common.Places
import com.lifolo.common.Sql
import com.lifolo.common.intOrNull
import com.lifolo.common.ms
import com.lifolo.common.stringArray
import com.lifolo.notify.PushItem
import com.lifolo.notify.PushService
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.stereotype.Component
import java.sql.ResultSet

/** the test clock of this package (Node tests move `Date.now()` with support/clock.js) */
object AmbClock {
    @Volatile
    internal var offsetMs = 0L

    fun now(): Long = System.currentTimeMillis() + offsetMs

    /** "YYYY-MM-DD HH:MM" Dhaka time → from now on the clock runs from there */
    internal fun setDhaka(s: String): Long {
        val t = java.time.OffsetDateTime.parse("${s.replace(' ', 'T')}:00+06:00").toInstant().toEpochMilli()
        offsetMs = t - System.currentTimeMillis()
        return t
    }

    internal fun advance(ms: Long) {
        offsetMs += ms
    }

    internal fun reset() {
        offsetMs = 0
    }
}

/** ambulances/{id} — the columns the ambulance code reads, plus the Node-shaped map for the kernel services */
class Amb(
    val id: String,
    val ownerUid: String,
    val name: String,
    val phone: String,
    val vehicleNo: String,
    val types: List<String>,
    val district: String,
    val upazilaId: Int?,
    val area: String,
    val fare: String,
    val lat: Double?,
    val lng: Double?,
    val available: Boolean,
    val availableAt: Long?,
    val busyAt: Long?,
    val remindAt: Long?,
    val reminderSentAt: Long?,
    val reqBusy: String?,
    val local: Int?,
    val oxygen: Int?,
    val icu: Int?,
    val night: Int?,
    val ratingSum: Int,
    val ratingCount: Int,
    val overchargeCount: Int,
    val status: String,
    val statusReason: String?,
    val createdAt: Long,
    val updatedAt: Long?,
    val reviewedAt: Long?,
    val suspendedAt: Long?,
    val docsRequestedAt: Long?,
    val docsRequestNote: String?,
    val editRejectedReason: String?,
    /** ListingRows.view — the Node document for verify/listing-review helpers */
    val node: Map<String, Any?>,
) {
    var rows: List<FareRow> = emptyList()

    /** fareChart (null when the owner never wrote one) */
    val chart: FareChart?
        get() = if (local == null && rows.isEmpty() && oxygen == null && icu == null && night == null) null else FareChart(local, rows, oxygen, icu, night)

    val veh: Veh get() = Veh(district, upazilaId)

    val isDemo: Boolean get() = ownerUid.startsWith("demo-")
}

/** queries on `ambulances` (+ fare rows) shared by the listing and the request code */
@Component
class AmbRepo(private val jdbc: JdbcTemplate, private val listingRows: ListingRows) {
    private fun map(rs: ResultSet): Amb = Amb(
        id = rs.getString("id"),
        ownerUid = rs.getString("owner_uid"),
        name = rs.getString("name"),
        phone = rs.getString("phone"),
        vehicleNo = rs.getString("vehicle_no"),
        types = rs.stringArray("types") ?: emptyList(),
        district = rs.getString("district"),
        upazilaId = rs.intOrNull("upazila_id"),
        area = rs.getString("area") ?: "",
        fare = rs.getString("fare") ?: "",
        lat = rs.getDouble("lat").let { if (rs.wasNull()) null else it },
        lng = rs.getDouble("lng").let { if (rs.wasNull()) null else it },
        available = rs.getBoolean("available"),
        availableAt = rs.ms("available_at"),
        busyAt = rs.ms("busy_at"),
        remindAt = rs.ms("remind_at"),
        reminderSentAt = rs.ms("reminder_sent_at"),
        reqBusy = rs.getString("req_busy"),
        local = rs.intOrNull("fare_local"),
        oxygen = rs.intOrNull("fare_oxygen"),
        icu = rs.intOrNull("fare_icu"),
        night = rs.intOrNull("fare_night"),
        ratingSum = rs.getInt("rating_sum"),
        ratingCount = rs.getInt("rating_count"),
        overchargeCount = rs.getInt("overcharge_count"),
        status = rs.getString("status"),
        statusReason = rs.getString("status_reason"),
        createdAt = rs.ms("created_at") ?: 0L,
        updatedAt = rs.ms("updated_at"),
        reviewedAt = rs.ms("reviewed_at"),
        suspendedAt = rs.ms("suspended_at"),
        docsRequestedAt = rs.ms("docs_requested_at"),
        docsRequestNote = rs.getString("docs_request_note"),
        editRejectedReason = rs.getString("edit_rejected_reason"),
        node = listingRows.view(ListingKind.AMBULANCE, rs),
    )

    /** `SELECT * FROM ambulances WHERE {where}` (+ fare rows). Firestore returns documents in id order. */
    fun query(where: String, vararg args: Any?, limit: Int = 300, lock: Boolean = false): List<Amb> {
        val list = jdbc.query(
            "SELECT * FROM ambulances WHERE $where ORDER BY id COLLATE \"C\" LIMIT $limit${if (lock) " FOR UPDATE" else ""}",
            { rs, _ -> map(rs) },
            *args,
        )
        attachRows(list)
        return list
    }

    fun get(id: String?, lock: Boolean = false): Amb? = if (id == null) null else query("id = ?", id, limit = 1, lock = lock).firstOrNull()

    /** the district's approved cars (the list query; 300 at most) */
    fun approvedIn(district: String): List<Amb> = query("district = ? AND status = 'approved'", district)

    fun ofOwner(uid: String, limit: Int): List<Amb> = query("owner_uid = ?", uid, limit = limit)

    private fun attachRows(list: List<Amb>) {
        if (list.isEmpty()) return
        val byId = list.associateBy { it.id }
        val rows = HashMap<String, ArrayList<FareRow>>()
        jdbc.query(
            "SELECT * FROM ambulance_fare_rows WHERE ambulance_id IN (${list.joinToString(",") { "?" }}) ORDER BY ambulance_id, idx",
            RowCallbackHandler { rs ->
                val kind = rs.getString("kind")
                val facility = rs.getString("facility_id")
                val dest = when (kind) {
                    "upazila" -> Dest("upazila", upazilaId = rs.intOrNull("upazila_id"))
                    "district" -> Dest("district", district = rs.getString("district"))
                    else -> if (facility != null) {
                        Dest("hospital", upazilaId = rs.intOrNull("upazila_id"), district = rs.getString("district"), facilityId = facility, name = rs.getString("name"))
                    } else {
                        Dest("hospital", name = rs.getString("name"))
                    }
                }
                rows.getOrPut(rs.getString("ambulance_id")) { ArrayList() }.add(FareRow(dest, rs.getInt("amount")))
            },
            *byId.keys.toTypedArray(),
        )
        list.forEach { it.rows = rows[it.id] ?: emptyList() }
    }

    /** writes the fare chart (null removes it) */
    fun saveChart(id: String, chart: FareChart?) {
        jdbc.update(
            "UPDATE ambulances SET fare_local = ?, fare_oxygen = ?, fare_icu = ?, fare_night = ? WHERE id = ?",
            chart?.local, chart?.oxygen, chart?.icu, chart?.night, id,
        )
        jdbc.update("DELETE FROM ambulance_fare_rows WHERE ambulance_id = ?", id)
        chart?.rows?.forEachIndexed { i, r ->
            jdbc.update(
                "INSERT INTO ambulance_fare_rows (ambulance_id, idx, kind, upazila_id, district, name, facility_id, amount) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id, i, r.dest.kind, r.dest.upazilaId, r.dest.district, r.dest.name, r.dest.facilityId, r.amount,
            )
        }
    }

    /** "available again" after a trip (ambreq.js freeVehicle) — only when the trip made it busy (reqBusy) */
    fun freeVehicle(a: Amb?, now: Long) {
        if (a == null || a.reqBusy == null) return
        jdbc.update(
            "UPDATE ambulances SET available = TRUE, available_at = ?, busy_at = NULL, updated_at = ?, req_busy = NULL, remind_at = ? WHERE id = ?",
            Sql.ts(now), Sql.ts(now), if (a.status == "approved") Sql.ts(now + AmbRules.REMIND_AT_MS) else null, a.id,
        )
    }

    /** on a trip of request [reqId] (accepted / agreed) */
    fun markBusy(id: String, reqId: String, now: Long) {
        jdbc.update(
            "UPDATE ambulances SET available = FALSE, available_at = ?, busy_at = ?, updated_at = ?, req_busy = ?, remind_at = NULL WHERE id = ?",
            Sql.ts(now), Sql.ts(now), Sql.ts(now), reqId, id,
        )
    }
}

/** ambulance.js rules (pure) */
object AmbRules {
    val TYPES = listOf("general", "ac", "icu", "freezer")
    const val MAX_PER_OWNER = 10
    const val RATE_WINDOW_MS = 7 * 24 * 3600 * 1000L

    /** no rating right after pressing call: at least 1 hour after the first call */
    const val RATE_AFTER_MS = 60 * 60 * 1000L

    /** how long "available now" lasts */
    const val AVAILABLE_MS = 12 * 3600 * 1000L
    const val REMIND_BEFORE_MS = 60 * 60 * 1000L

    /** "still available?" 11 h after going available */
    const val REMIND_AT_MS = AVAILABLE_MS - REMIND_BEFORE_MS

    /** the list-read reminder search runs at most every 10 minutes */
    const val REMIND_EVERY_MS = 10 * 60 * 1000L
    const val REMIND_PAGE = 100
    const val REMIND_MAX_PAGES = 20

    /** a base location further than this from the district centre is ignored */
    const val BASE_MAX_KM = 130.0

    /** cars without a base location may be anywhere in the district — sorted as this much further */
    const val APPROX_EXTRA_KM = 5.0

    /** a call record (ambulanceCalls): can the caller rate? 7 days, 1 hour after the first call, or a finished in-app trip */
    fun rateable(at: Long?, firstAt: Long?, tripAt: Long?, now: Long): Boolean {
        if (now - (at ?: 0) > RATE_WINDOW_MS) return false
        if (tripAt != null && tripAt != 0L && now - tripAt <= RATE_WINDOW_MS) return true
        return now - (firstAt?.takeIf { it != 0L } ?: at ?: 0) >= RATE_AFTER_MS
    }

    /** a point inside Bangladesh, 2 decimals (≈ 1 km cell — the driver's real GPS is never kept), else null */
    fun point(lat: Double?, lng: Double?): Pair<Double, Double>? {
        if (lat == null || lng == null || !lat.isFinite() || !lng.isFinite()) return null
        if (lat < 20.3 || lat > 26.8 || lng < 87.9 || lng > 92.8) return null
        return Js.round(lat * 100) / 100 to Js.round(lng * 100) / 100
    }

    /** whole km; under 1 km → 0 */
    fun wholeKm(km: Double): Int = if (km < 1) 0 else Js.round(km).toInt()

    data class Base(val lat: Double, val lng: Double, val exact: Boolean)

    /** where the car waits: the driver's base (2 decimals) or the district centre (exact = false) */
    fun baseOf(a: Amb): Base? {
        val p = point(a.lat, a.lng)
        if (p != null) return Base(p.first, p.second, true)
        val d = Places.district(a.district) ?: return null
        return Base(d.lat, d.lng, false)
    }

    /** available — 12 h after the last "available" it becomes "unknown" */
    fun availableNow(a: Amb, now: Long): Boolean {
        if (!a.available) return false
        val at = a.availableAt ?: a.updatedAt ?: a.createdAt
        return now - at <= AVAILABLE_MS
    }

    /** on a trip (the driver set "not available") — hidden from the list for 12 h */
    fun busyNow(a: Amb, now: Long): Boolean {
        if (a.available) return false
        val at = a.busyAt ?: a.availableAt ?: 0
        return at > 0 && now - at < AVAILABLE_MS
    }

    fun rating(sum: Int, n: Int): Double = if (n > 0) Js.round(sum.toDouble() / n * 10) / 10 else 0.0

    /** ambulance.js view(d, mine) */
    fun view(a: Amb, now: Long, mine: Boolean = false): LinkedHashMap<String, Any?> {
        val avail = availableNow(a, now)
        val since = a.availableAt ?: a.updatedAt ?: a.createdAt
        val v = linkedMapOf<String, Any?>(
            "id" to a.id,
            "name" to a.name,
            "phone" to Phones.fixPhone(a.phone),
            "types" to a.types,
            "district" to a.district,
            "upazilaId" to a.upazilaId?.takeIf { it != 0 },
            "area" to a.area,
            "fare" to a.fare,
            "fareChart" to a.chart?.toMap(),
            "available" to avail,
            "availableAt" to if (busyNow(a, now)) 0L else since.takeIf { it != 0L } ?: 1L,
            "availableUntil" to if (avail) since + AVAILABLE_MS else 0L,
            "rating" to rating(a.ratingSum, a.ratingCount),
            "ratingCount" to a.ratingCount,
        )
        if (mine) {
            v["vehicleNo"] = a.vehicleNo
            v["status"] = a.status
            v["statusReason"] = a.statusReason ?: ""
            v["hasBase"] = a.lat != null
        }
        return v
    }

    fun distanceKm(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double = Geo.distanceKm(aLat, aLng, bLat, bLng)
}

/** pushes of the ambulance code (firebase.js sendPush to users with a profile, text in their language) */
@Component
class AmbPush(private val jdbc: JdbcTemplate, private val push: PushService) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** uid → profile lang, only users with a profile (Node: users/{uid} exists) */
    fun langs(uids: Collection<String>): Map<String, String?> {
        val ids = uids.distinct()
        if (ids.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String?>()
        jdbc.query(
            "SELECT uid, lang FROM user_profiles WHERE uid IN (${ids.joinToString(",") { "?" }})",
            RowCallbackHandler { rs -> out[rs.getString(1)] = rs.getString(2) },
            *ids.toTypedArray(),
        )
        return out
    }

    /** items as they are (each with its uid) */
    fun sendItems(items: List<PushItem>) {
        push.send(items)
    }

    /** one push per item, in [uids] order; [make] builds title/body/data in the user's language. Never throws. */
    fun send(uids: List<String>, make: (uid: String, lang: String?) -> PushItem?) {
        try {
            val l = langs(uids)
            val items = uids.filter { it in l }.mapNotNull { make(it, l[it]) }
            if (items.isNotEmpty()) push.send(items)
        } catch (e: Exception) {
            log.error("ambulance push failed: {}", e.message)
        }
    }
}
