package com.lifolo.blood

import com.lifolo.common.boolOrNull
import com.lifolo.common.intOrNull
import com.lifolo.common.longOrNull
import com.lifolo.common.ms
import java.sql.ResultSet

/** one `blood_requests` row (Node `requests/{id}`) */
data class BloodRequest(
    val id: String,
    override val requesterUid: String,
    val requesterName: String,
    override val patientGroup: String,
    override val bags: Int,
    override val hospital: String,
    override val placeName: String,
    override val lat: Double,
    override val lng: Double,
    val geohash: String,
    override val neededByMillis: Long?,
    override val neededAtMillis: Long?,
    val urgent: Boolean,
    override val travelFare: Boolean,
    val note: String,
    override val status: String,
    val createdAt: Long,
    val notifiedAt: Long?,
    val notifiedCount: Int,
    val notifyRounds: Int?,
    val lastNotifiedAt: Long?,
    val notifyingUntil: Long?,
    val acceptedCount: Int,
    val confirmedCount: Int,
    val fulfilled: Boolean,
    val bagsReceived: Int,
    val bagsReceivedBefore: Int?,
    val editCount: Int,
    val closedAt: Long?,
    val closedBy: String?,
) : RequestFacts {
    fun isActive(now: Long = System.currentTimeMillis()): Boolean = BloodRules.isActive(this, now)

    fun bagsLeft(): Int = BloodRules.bagsLeft(bags, confirmedCount, bagsReceived)

    /** clientdata.js requestOut — the app's BloodRequest fields (times in ms) */
    fun out(): LinkedHashMap<String, Any?> = linkedMapOf(
        "id" to id,
        "requesterUid" to requesterUid,
        "requesterName" to requesterName,
        "patientGroup" to patientGroup,
        "bags" to bags,
        "hospital" to hospital,
        "placeName" to placeName,
        "lat" to lat,
        "lng" to lng,
        "neededByMillis" to (neededByMillis ?: 0L),
        "neededAtMillis" to neededAtMillis,
        "urgent" to urgent,
        "travelFare" to travelFare,
        "note" to note,
        "status" to status.ifEmpty { "open" },
        "createdAtMillis" to createdAt,
        "createdAt" to createdAt,
        // the app only checks whether it is set
        "notifiedAt" to notifiedAt,
        "notifiedCount" to notifiedCount,
        "fulfilled" to fulfilled,
        "acceptedCount" to acceptedCount,
        "bagsReceived" to bagsReceived,
        "confirmedCount" to confirmedCount,
        "closedAt" to (closedAt ?: 0L),
        "closedBy" to (closedBy ?: ""),
        "notifyRounds" to notifyRounds,
        "lastNotifiedAt" to lastNotifiedAt,
    )

    /** guest.js requestView: no name, phone, note, uid or exact place — distance instead */
    fun guestView(km: Double): LinkedHashMap<String, Any?> = linkedMapOf(
        "id" to id,
        "patientGroup" to patientGroup,
        "bags" to bags,
        "hospital" to hospital,
        "placeName" to placeName,
        "neededByMillis" to (neededByMillis ?: 0L),
        "neededAtMillis" to neededAtMillis,
        "urgent" to urgent,
        "travelFare" to travelFare,
        "status" to status.ifEmpty { "open" },
        "acceptedCount" to maxOf(acceptedCount, 0),
        "confirmedCount" to maxOf(confirmedCount, 0),
        "bagsReceived" to maxOf(bagsReceived, 0),
        "km" to roundKm1(km),
    )

    companion object {
        fun roundKm1(km: Double): Double = Math.floor(km * 10 + 0.5) / 10

        fun of(rs: ResultSet): BloodRequest = BloodRequest(
            id = rs.getString("id"),
            requesterUid = rs.getString("requester_uid"),
            requesterName = rs.getString("requester_name") ?: "",
            patientGroup = rs.getString("patient_group"),
            bags = rs.getInt("bags"),
            hospital = rs.getString("hospital") ?: "",
            placeName = rs.getString("place_name") ?: "",
            lat = rs.getDouble("lat"),
            lng = rs.getDouble("lng"),
            geohash = rs.getString("geohash") ?: "",
            neededByMillis = rs.longOrNull("needed_by_ms"),
            neededAtMillis = rs.longOrNull("needed_at_ms"),
            urgent = rs.getBoolean("urgent"),
            travelFare = rs.getBoolean("travel_fare"),
            note = rs.getString("note") ?: "",
            status = rs.getString("status") ?: "open",
            createdAt = rs.ms("created_at") ?: 0L,
            notifiedAt = rs.ms("notified_at"),
            notifiedCount = rs.getInt("notified_count"),
            notifyRounds = rs.intOrNull("notify_rounds"),
            lastNotifiedAt = rs.ms("last_notified_at"),
            notifyingUntil = rs.ms("notifying_until"),
            acceptedCount = rs.getInt("accepted_count"),
            confirmedCount = rs.getInt("confirmed_count"),
            fulfilled = rs.boolOrNull("fulfilled") ?: false,
            bagsReceived = rs.getInt("bags_received"),
            bagsReceivedBefore = rs.intOrNull("bags_received_before"),
            editCount = rs.getInt("edit_count"),
            closedAt = rs.ms("closed_at"),
            closedBy = rs.getString("closed_by"),
        )
    }
}

/** one `blood_responses` row (Node `requests/{id}/responses/{donorUid}`) */
data class BloodResponse(
    val requestId: String,
    val donorUid: String,
    val donorName: String,
    val donorPhone: String,
    val donorGroup: String,
    val status: String,
    val confirmed: Boolean,
    val distanceKm: Int?,
    val fareAfterKm: Int?,
    val removedBy: String?,
    val removedAt: Long?,
    val createdAt: Long,
) {
    /** clientdata.js responseOut */
    fun out(): LinkedHashMap<String, Any?> = linkedMapOf(
        "donorUid" to donorUid,
        "donorName" to donorName,
        "donorPhone" to donorPhone,
        "donorGroup" to donorGroup,
        "status" to status.ifEmpty { "accepted" },
        "confirmed" to confirmed,
        "distanceKm" to distanceKm,
        "fareAfterKm" to fareAfterKm,
        "createdAtMillis" to createdAt,
    )

    companion object {
        fun of(rs: ResultSet): BloodResponse = BloodResponse(
            requestId = rs.getString("request_id"),
            donorUid = rs.getString("donor_uid"),
            donorName = rs.getString("donor_name") ?: "",
            donorPhone = rs.getString("donor_phone") ?: "",
            donorGroup = rs.getString("donor_group") ?: "",
            status = rs.getString("status") ?: "accepted",
            confirmed = rs.getBoolean("confirmed"),
            distanceKm = rs.intOrNull("distance_km"),
            fareAfterKm = rs.intOrNull("fare_after_km"),
            removedBy = rs.getString("removed_by"),
            removedAt = rs.ms("removed_at"),
            createdAt = rs.ms("created_at") ?: 0L,
        )
    }
}
