package com.lifolo.blood

import com.lifolo.common.Geo
import com.lifolo.common.boolOrNull
import com.lifolo.common.doubleOrNull
import com.lifolo.common.intOrNull
import com.lifolo.common.longOrNull
import com.lifolo.common.stringArray
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * geofire-common `geohashQueryBounds` (v6.0.0), ported line by line: the geohash prefix ranges that cover a circle.
 * The app (GeoFireUtils) and the Node server use the same ranges.
 */
object GeoBounds {
    private const val BITS_PER_CHAR = 5
    private const val MAXIMUM_BITS_PRECISION = 22.0 * BITS_PER_CHAR
    private const val EARTH_MERI_CIRCUMFERENCE = 40007860.0
    private const val METERS_PER_DEGREE_LATITUDE = 110574.0
    private const val EARTH_EQ_RADIUS = 6378137.0
    private const val E2 = 0.00669447819799
    private const val EPSILON = 1e-12
    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"

    private fun log2(x: Double) = ln(x) / ln(2.0)

    private fun rad(d: Double) = d * PI / 180

    fun metersToLongitudeDegrees(distance: Double, latitude: Double): Double {
        val radians = rad(latitude)
        val num = cos(radians) * EARTH_EQ_RADIUS * PI / 180
        val denom = 1 / sqrt(1 - E2 * sin(radians) * sin(radians))
        val deltaDeg = num * denom
        return if (deltaDeg < EPSILON) (if (distance > 0) 360.0 else 0.0) else minOf(360.0, distance / deltaDeg)
    }

    private fun longitudeBitsForResolution(resolution: Double, latitude: Double): Double {
        val degs = metersToLongitudeDegrees(resolution, latitude)
        return if (Math.abs(degs) > 0.000001) maxOf(1.0, log2(360 / degs)) else 1.0
    }

    private fun latitudeBitsForResolution(resolution: Double): Double =
        minOf(log2(EARTH_MERI_CIRCUMFERENCE / 2 / resolution), MAXIMUM_BITS_PRECISION)

    fun wrapLongitude(longitude: Double): Double {
        if (longitude <= 180 && longitude >= -180) return longitude
        val adjusted = longitude + 180
        return if (adjusted > 0) (adjusted % 360) - 180 else 180 - (-adjusted % 360)
    }

    private fun boundingBoxBits(lat: Double, size: Double): Double {
        val latDeltaDegrees = size / METERS_PER_DEGREE_LATITUDE
        val latitudeNorth = minOf(90.0, lat + latDeltaDegrees)
        val latitudeSouth = maxOf(-90.0, lat - latDeltaDegrees)
        val bitsLat = floor(latitudeBitsForResolution(size)) * 2
        val bitsLongNorth = floor(longitudeBitsForResolution(size, latitudeNorth)) * 2 - 1
        val bitsLongSouth = floor(longitudeBitsForResolution(size, latitudeSouth)) * 2 - 1
        return minOf(minOf(bitsLat, bitsLongNorth), minOf(bitsLongSouth, MAXIMUM_BITS_PRECISION))
    }

    private fun boundingBoxCoordinates(lat: Double, lng: Double, radius: Double): List<Pair<Double, Double>> {
        val latDegrees = radius / METERS_PER_DEGREE_LATITUDE
        val latitudeNorth = minOf(90.0, lat + latDegrees)
        val latitudeSouth = maxOf(-90.0, lat - latDegrees)
        val longDegsNorth = metersToLongitudeDegrees(radius, latitudeNorth)
        val longDegsSouth = metersToLongitudeDegrees(radius, latitudeSouth)
        val longDegs = maxOf(longDegsNorth, longDegsSouth)
        return listOf(
            lat to lng,
            lat to wrapLongitude(lng - longDegs),
            lat to wrapLongitude(lng + longDegs),
            latitudeNorth to lng,
            latitudeNorth to wrapLongitude(lng - longDegs),
            latitudeNorth to wrapLongitude(lng + longDegs),
            latitudeSouth to lng,
            latitudeSouth to wrapLongitude(lng - longDegs),
            latitudeSouth to wrapLongitude(lng + longDegs),
        )
    }

    private fun geohashQuery(geohashIn: String, bits: Double): Pair<String, String> {
        val precision = ceil(bits / BITS_PER_CHAR).toInt()
        if (geohashIn.length < precision) return geohashIn to "$geohashIn~"
        val geohash = geohashIn.substring(0, precision)
        val base = geohash.substring(0, geohash.length - 1)
        val lastValue = BASE32.indexOf(geohash[geohash.length - 1])
        val significantBits = (bits - base.length * BITS_PER_CHAR).toInt()
        val unusedBits = BITS_PER_CHAR - significantBits
        val startValue = (lastValue shr unusedBits) shl unusedBits
        val endValue = startValue + (1 shl unusedBits)
        return if (endValue > 31) base + BASE32[startValue] to "$base~" else base + BASE32[startValue] to base + BASE32[endValue]
    }

    /** geohashQueryBounds(center, radiusInM): [start, end] pairs (end inclusive), duplicates removed */
    fun queryBounds(lat: Double, lng: Double, radiusM: Double): List<Pair<String, String>> {
        val queryBits = maxOf(1.0, boundingBoxBits(lat, radiusM))
        val geohashPrecision = ceil(queryBits / BITS_PER_CHAR).toInt()
        val queries = boundingBoxCoordinates(lat, lng, radiusM).map { (la, lo) ->
            geohashQuery(Geo.hash(la, lo, geohashPrecision)!!, queryBits)
        }
        return queries.filterIndexed { index, q -> queries.subList(0, index).none { it == q } }
    }
}

/**
 * The geohash queries of server/lib/geo.js over SQL ranges (`text_pattern_ops` indexes, byte order like Firestore):
 * open requests near a point (Node: field `geoOpen` = only while open → here `status = 'open'`), and donors by
 * `donor_geo = "group|geohash"` (geo.js nearDonors). Results are within the radius (geofire distance), nearest first.
 */
@Component
class GeoQuery(private val jdbc: JdbcTemplate) {
    data class Hit<T>(val item: T, val km: Double)

    /** geo.near over open blood requests (limitPerBound per range, like the Firestore `.limit()`) */
    fun openRequestsNear(lat: Double, lng: Double, radiusKm: Double, limitPerBound: Int): List<Hit<BloodRequest>> {
        val seen = HashSet<String>()
        val out = ArrayList<Hit<BloodRequest>>()
        for ((start, end) in GeoBounds.queryBounds(lat, lng, radiusKm * 1000)) {
            val rows = jdbc.query(
                "SELECT * FROM blood_requests WHERE status = 'open' AND geohash ~>=~ ? AND geohash ~<=~ ? ORDER BY geohash USING ~<~ LIMIT ?",
                { rs, _ -> BloodRequest.of(rs) },
                start, end, limitPerBound,
            )
            for (r in rows) {
                if (!seen.add(r.id)) continue
                val km = Geo.distanceKm(lat, lng, r.lat, r.lng)
                if (km <= radiusKm) out.add(Hit(r, km))
            }
        }
        return out.sortedBy { it.km }
    }

    /** geo.nearDonors: profiles of [groups] within [radiusKm] (with their device-token presence), nearest first */
    fun nearDonors(groups: Collection<String>, lat: Double, lng: Double, radiusKm: Double, limitPerBound: Int = 2000): List<Hit<Donor>> {
        val bounds = GeoBounds.queryBounds(lat, lng, radiusKm * 1000)
        val list = groups.distinct().filter { it in BloodRules.BLOOD_GROUPS }
        val seen = HashSet<String>()
        val out = ArrayList<Hit<Donor>>()
        for (g in list) {
            for ((start, end) in bounds) {
                val rows = jdbc.query(
                    "$DONOR_SELECT WHERE p.donor_geo ~>=~ ? AND p.donor_geo ~<=~ ? AND $NOT_DEACTIVATED ORDER BY p.donor_geo USING ~<~ LIMIT ?",
                    { rs, _ -> donor(rs) },
                    "$g|$start", "$g|$end", limitPerBound,
                )
                for (d in rows) {
                    if (!seen.add(d.uid)) continue
                    val la = d.lat ?: continue
                    val lo = d.lng ?: continue
                    val km = Geo.distanceKm(lat, lng, la, lo)
                    if (km <= radiusKm) out.add(Hit(d, km))
                }
            }
        }
        return out.sortedBy { it.km }
    }

    companion object {
        /**
         * the donor fields of a profile (+ whether any device token exists). `notify` (nearby requests) and
         * `notify_emergency` (emergency appeals) already include the master switch of notify_prefs, so donors who turned
         * them off are not picked at all (no wave place, no daily push count spent on a push that would not go).
         */
        const val DONOR_SELECT = """
            SELECT p.uid, p.name, p.phone, p.blood_group, p.lat, p.lng, p.max_push_per_day, p.notify_groups,
                   (p.notify AND COALESCE(np.all_on, TRUE)) AS notify,
                   (COALESCE(np.all_on, TRUE) AND COALESCE(np.blood_emergency, TRUE)) AS notify_emergency,
                   p.birth_date_ms, p.weight_kg, p.last_donation_ms, p.max_km, p.fare_after_km, p.lang, p.place_name,
                   EXISTS (SELECT 1 FROM device_tokens t WHERE t.uid = p.uid) AS has_tokens
            FROM user_profiles p LEFT JOIN notify_prefs np ON np.uid = p.uid"""

        /** a deactivated account (account_deactivate) is nobody's donor: no nearby request, no emergency wave */
        const val NOT_DEACTIVATED = "NOT EXISTS (SELECT 1 FROM accounts a WHERE a.uid = p.uid AND a.deactivated_at IS NOT NULL)"

        fun donor(rs: ResultSet): Donor = Donor(
            uid = rs.getString("uid"),
            bloodGroup = rs.getString("blood_group"),
            lat = rs.doubleOrNull("lat"),
            lng = rs.doubleOrNull("lng"),
            notify = rs.boolOrNull("notify") ?: true,
            notifyEmergency = rs.boolOrNull("notify_emergency") ?: true,
            maxPushPerDay = rs.intOrNull("max_push_per_day"),
            notifyGroups = rs.stringArray("notify_groups"),
            birthDateMillis = rs.longOrNull("birth_date_ms"),
            weightKg = rs.doubleOrNull("weight_kg"),
            lastDonationMillis = rs.longOrNull("last_donation_ms"),
            maxKm = rs.intOrNull("max_km"),
            fareAfterKm = rs.intOrNull("fare_after_km"),
            lang = rs.getString("lang"),
            hasTokens = rs.getBoolean("has_tokens"),
            name = rs.getString("name"),
            phone = rs.getString("phone"),
            placeName = rs.getString("place_name"),
        )

        /** one profile as a [Donor] (null when the user has no profile) */
        fun loadDonor(jdbc: JdbcTemplate, uid: String, forUpdate: Boolean = false): Donor? =
            jdbc.query("$DONOR_SELECT WHERE p.uid = ?${if (forUpdate) " FOR UPDATE OF p" else ""}", { rs, _ -> donor(rs) }, uid).firstOrNull()
    }
}
