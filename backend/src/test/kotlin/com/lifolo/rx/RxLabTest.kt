package com.lifolo.rx

import com.fasterxml.jackson.databind.JsonNode
import com.lifolo.common.Places
import com.lifolo.notify.DeviceMessage
import com.lifolo.support.IntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals

/**
 * Helpers of the P3 integration tests — the Node tests' seed()/call()/fails()/lastPush() on the SQL schema.
 * Every person gets a profile (pushes need users/{uid}) and a device token (pushes are recorded in order).
 */
abstract class RxLabTest : IntegrationTest() {
    /** Mymensingh (the Node tests' DIST) */
    val dist: String get() = Places.DISTRICTS.first { it.en == "Mymensingh" }.bn

    /** a user with a profile (name, phone, lang bn) and a device token `tok_{uid}` */
    fun person(uid: String, name: String, phone: String = "", email: String = "$uid@example.com") {
        user(uid, mapOf("name" to name, "phone" to phone, "lang" to "bn"), email)
        jdbc.update("INSERT INTO device_tokens (token, uid) VALUES (?, ?) ON CONFLICT DO NOTHING", "tok_$uid", uid)
    }

    /** an approved doctor badge + provider row */
    fun doctor(uid: String, name: String, regNo: String = "A-12345", degrees: String = "MBBS") {
        jdbc.update(
            "INSERT INTO badges (uid, type, reg_no, workplace, name, status) VALUES (?, 'doctor', ?, 'হাসপাতাল', ?, 'approved')",
            uid, regNo, name,
        )
        jdbc.update(
            "INSERT INTO providers (uid, type, name, degrees, specialties, listed) VALUES (?, 'doctor', ?, ?, '{medicine}', TRUE)",
            uid, name, degrees,
        )
    }

    /** labs/{uid} */
    fun lab(
        uid: String,
        name: String,
        status: String = "approved",
        district: String = dist,
        upazilaId: Int? = null,
        homeCollection: Boolean = false,
        facilityId: String? = null,
    ) {
        jdbc.update(
            """
            INSERT INTO labs (uid, name, district, upazila_id, address, license_no, phone, status, home_collection, facility_id)
            VALUES (?, ?, ?, ?, 'চরপাড়া', 'DGHS-1', '01711000498', ?, ?, ?)
            """.trimIndent(),
            uid, name, district, upazilaId, status, homeCollection, facilityId,
        )
    }

    fun careLink(patient: String, caretaker: String) {
        jdbc.update("INSERT INTO care_links (patient_uid, caretaker_uid, status) VALUES (?, ?, 'active')", patient, caretaker)
    }

    /** call() that must answer 200 */
    fun ok(uid: String?, action: String, body: Map<String, Any?> = emptyMap()): JsonNode {
        val r = call(uid, action, body)
        assertEquals(200, r.status, "$action: $r")
        return r.body
    }

    /** fails(): the status and the error body */
    fun fails(uid: String?, action: String, body: Map<String, Any?>, status: Int): JsonNode {
        val r = call(uid, action, body)
        assertEquals(status, r.status, "$action should fail with $status: $r")
        return r.body
    }

    fun pushesTo(uid: String): List<DeviceMessage> = push.messages.filter { it.uid == uid }

    /** the last push to [uid]: data map with title, body and the payload */
    fun lastPush(uid: String): Map<String, String>? = pushesTo(uid).lastOrNull()?.data

    fun leaks(n: JsonNode, text: String): Boolean = n.toString().contains(text)

    fun ids(n: JsonNode): List<String> = n.map { it["id"].asText() }

    companion object {
        const val DAY = 24 * 60 * 60 * 1000L
    }
}
