package com.lifolo.blood

import com.lifolo.common.Geo
import com.lifolo.common.Places
import com.lifolo.support.IntegrationTest
import java.sql.Timestamp
import java.time.LocalDate
import java.time.ZoneOffset

/** helpers shared by the blood ITs (the Node tests' `request(...)`, donors with devices, admin) */
abstract class BloodTestBase : IntegrationTest() {
    val DAY = 86_400_000L
    val LAT = 24.75
    val LNG = 90.40
    val DIST: String = Places.DISTRICTS.first { it.en == "Mymensingh" }.bn
    val ADMIN = "adm1"

    fun admin() = user(ADMIN, emptyMap(), email = "owner@example.com")

    fun birth(y: Int, m: Int = 1, d: Int = 1): Long = LocalDate.of(y, m, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** a donor with a profile at lat/lng (donor_geo set like profile_update) and a device token */
    fun donor(
        uid: String,
        group: String,
        lat: Double = LAT,
        lng: Double = LNG,
        extra: Map<String, Any?> = emptyMap(),
        token: Boolean = true,
        phone: String = "01811111111",
    ) {
        val gh = Geo.hash(lat, lng)
        user(
            uid,
            linkedMapOf<String, Any?>(
                "name" to "Donor $uid", "phone" to phone, "blood_group" to group, "lat" to lat, "lng" to lng, "geohash" to gh,
                "donor_geo" to Geo.donorKey(group, gh), "birth_date_ms" to birth(1995), "weight_kg" to 60.0, "place_name" to DIST, "lang" to "bn",
            ) + extra,
        )
        if (token) jdbc.update("INSERT INTO device_tokens (token, uid) VALUES (?, ?)", "tok-$uid", uid)
    }

    /** clientdata.test.js request(id, by, f) */
    fun request(
        id: String,
        by: String,
        group: String = "O+",
        lat: Double = LAT,
        lng: Double = LNG,
        status: String = "open",
        neededBy: Long = System.currentTimeMillis() + DAY,
        neededAt: Long? = null,
        urgent: Boolean = false,
        createdAt: Long = System.currentTimeMillis(),
        bags: Int = 2,
        accepted: Int = 1,
        hospital: String = "MCH",
    ) {
        jdbc.update(
            """
            INSERT INTO blood_requests (id, requester_uid, requester_name, patient_group, bags, hospital, place_name, lat, lng, geohash,
              needed_by_ms, needed_at_ms, urgent, note, status, created_at, accepted_count)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'secret note', ?, ?, ?)
            """.trimIndent(),
            id, by, "Requester $by", group, bags, hospital, DIST, lat, lng, Geo.hash(lat, lng), neededBy, neededAt, urgent, status,
            Timestamp(createdAt), accepted,
        )
    }

    fun response(requestId: String, uid: String, status: String = "accepted", createdAt: Long = System.currentTimeMillis(), confirmed: Boolean = false) {
        jdbc.update(
            """
            INSERT INTO blood_responses (request_id, donor_uid, donor_name, donor_phone, donor_group, status, confirmed, created_at)
            VALUES (?, ?, ?, '0180000', 'O+', ?, ?, ?)
            """.trimIndent(),
            requestId, uid, "D $uid", status, confirmed, Timestamp(createdAt),
        )
    }

    fun contact(requestId: String, name: String, phone: String) {
        jdbc.update("INSERT INTO blood_request_contacts (request_id, name, phone) VALUES (?, ?, ?)", requestId, name, phone)
    }

    fun http(path: String, uid: String?, body: Map<String, Any?>, lang: String = "bn"): Res =
        post(path, linkedMapOf<String, Any?>("lang" to lang) + body, if (uid != null) mapOf("Authorization" to "Bearer $uid") else emptyMap())

    /** inbox titles of a user (every push is also written there, with or without a device) */
    fun inbox(uid: String): List<String> =
        jdbc.queryForList("SELECT title FROM inbox_items WHERE uid = ? ORDER BY created_at, id", String::class.java, uid)

    fun pushedTo(uid: String): List<Map<String, String>> = push.messages.filter { it.uid == uid }.map { it.data }

    fun lastDonation(uid: String): Long? =
        jdbc.queryForList("SELECT last_donation_ms FROM user_profiles WHERE uid = ?", uid).firstOrNull()?.get("last_donation_ms") as Long?

    fun today(): Long = BloodRules.todayDhakaUtcMidnight()

    /** a GET whose answer is not JSON (HTML pages, SVG) */
    fun page(path: String): org.springframework.mock.web.MockHttpServletResponse =
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)).andReturn().response
}
