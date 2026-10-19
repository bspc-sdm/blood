package com.lifolo.common

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geohash exactly like geofire-common (server/lib/geo.js): 10 characters, `val > mid` → 1.
 * Kept identical so the app (GeoFireUtils) and the server agree on cell keys.
 */
object Geo {
    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"
    private const val EARTH_RADIUS_KM = 6371.0

    fun hash(lat: Double?, lng: Double?, precision: Int = 10): String? {
        if (lat == null || lng == null || !lat.isFinite() || !lng.isFinite()) return null
        var latMin = -90.0
        var latMax = 90.0
        var lngMin = -180.0
        var lngMax = 180.0
        val sb = StringBuilder()
        var hashVal = 0
        var bits = 0
        var even = true
        while (sb.length < precision) {
            if (even) {
                val mid = (lngMin + lngMax) / 2
                if (lng > mid) {
                    hashVal = (hashVal shl 1) + 1
                    lngMin = mid
                } else {
                    hashVal = hashVal shl 1
                    lngMax = mid
                }
            } else {
                val mid = (latMin + latMax) / 2
                if (lat > mid) {
                    hashVal = (hashVal shl 1) + 1
                    latMin = mid
                } else {
                    hashVal = hashVal shl 1
                    latMax = mid
                }
            }
            even = !even
            if (bits < 4) {
                bits++
            } else {
                bits = 0
                sb.append(BASE32[hashVal])
                hashVal = 0
            }
        }
        return sb.toString()
    }

    /** geo.js donorKey: "group|geohash", null when group or geohash missing */
    fun donorKey(bloodGroup: String?, geohash: String?): String? {
        if (bloodGroup == null || bloodGroup !in Rules.BLOOD_GROUPS || geohash.isNullOrEmpty()) return null
        return "$bloodGroup|$geohash"
    }

    /** geofire-common distanceBetween (haversine, km) */
    fun distanceKm(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val dLat = (bLat - aLat) * PI / 180
        val dLng = (bLng - aLng) * PI / 180
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(aLat * PI / 180) * cos(bLat * PI / 180) * sin(dLng / 2) * sin(dLng / 2)
        return EARTH_RADIUS_KM * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
