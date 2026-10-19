package com.lifolo.users

import com.lifolo.common.Geo
import com.lifolo.support.IntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Port of the profile parts of server/test/clientdata.test.js (firestore.rules → server checks). */
class ProfileIT : IntegrationTest() {
    private fun ok(uid: String, action: String, body: Map<String, Any?> = emptyMap()) =
        call(uid, action, body, lang = "en").also { assertEquals(200, it.status, "$action: $it") }.body

    private fun data(uid: String) = row("SELECT * FROM user_profiles WHERE uid = ?", uid)

    @Test
    fun `profile - only your own; not for guests`() {
        user("pA", mapOf("phone" to "01711111111", "blood_group" to "O+", "photo" to "AAAA"))
        user("pB", mapOf("phone" to "01822222222"))
        val a = ok("pA", "profile_get")
        assertEquals("01711111111", a["profile"]["phone"].textValue())
        assertFalse(a["profile"]["hasPhotoThumb"].booleanValue())
        // server fields never leave (family, tokens, donor geo key)
        for (k in listOf("family", "fcmTokens", "respondedTo", "donorGeo")) assertFalse(a["profile"].has(k), k)
        // another uid in the body still returns your own
        assertEquals("01711111111", ok("pA", "profile_get", mapOf("uid" to "pB"))["profile"]["phone"].textValue())
        // a caregiver can't read/write the patient's profile
        assertEquals(403, call("pA", "profile_get", mapOf("asUid" to "pB"), lang = "en").status)
        assertEquals(403, call("pA", "profile_update", mapOf("asUid" to "pB", "set" to mapOf("name" to "হ্যাক")), lang = "en").status)
        // no profile yet
        user("pNone")
        assertTrue(ok("pNone", "profile_get")["profile"].isNull)
        // guest
        for (action in listOf("profile_get", "profile_update")) {
            assertEquals(401, call(null, action, mapOf("set" to mapOf("name" to "x")), lang = "en").status, action)
        }
    }

    @Test
    fun `profile update - only the app's own fields`() {
        user("pC", mapOf("phone" to "017", "blood_group" to "A+"))
        val forbidden = listOf(
            mapOf("badge" to "doctor"), mapOf("approved" to true), mapOf("status" to "approved"), mapOf("family" to emptyList<Any>()),
            mapOf("fcmTokens" to listOf("x")), mapOf("donorGeo" to "O+|x"), mapOf("geohash" to "wh2"), mapOf("respondedTo" to emptyMap<String, Any>()),
            mapOf("emergencyComing" to emptyMap<String, Any>()), mapOf("commitments" to mapOf("r" to 1)), mapOf("disabled" to false), mapOf("updatedAt" to 1),
        )
        for (bad in forbidden) assertEquals(403, call("pC", "profile_update", mapOf("set" to bad), lang = "en").status, bad.toString())
        val invalid = listOf(
            mapOf("bloodGroup" to "Z+"), mapOf("name" to 5), mapOf("name" to "x".repeat(101)), mapOf("maxKm" to 7), mapOf("notify" to "yes"),
            mapOf("notifyGroups" to listOf("Q")), mapOf("lat" to 24.7), mapOf("lat" to "a", "lng" to 90), mapOf("lang" to "fr"),
            mapOf("photo" to "<script>"), mapOf("weightKg" to -1),
        )
        for (bad in invalid) assertEquals(400, call("pC", "profile_update", mapOf("set" to bad), lang = "en").status, bad.toString())
        assertEquals("017", data("pC")["phone"], "nothing changed")

        ok("pC", "profile_update", mapOf("set" to mapOf("name" to "নতুন", "phone" to "01733333333", "bloodGroup" to "B+", "birthDateMillis" to 788918400000L, "weightKg" to 60, "notify" to true)))
        var d = data("pC")
        assertEquals("নতুন", d["name"])
        assertNotNull(d["updated_at"], "updatedAt")
        assertNull(d["donor_geo"], "no location → no donor key")

        // location: rounded, geohash + donor key set by the server
        ok("pC", "profile_update", mapOf("set" to mapOf("lat" to 24.75432, "lng" to 90.40789, "placeName" to "ময়মনসিংহ", "locationSource" to "gps")))
        d = data("pC")
        assertEquals(24.75, d["lat"])
        assertEquals(90.41, d["lng"])
        assertEquals(Geo.hash(24.75, 90.41), d["geohash"])
        assertEquals("B+|${Geo.hash(24.75, 90.41)}", d["donor_geo"])
        assertNotNull(d["location_updated_at"])
        // empty group → donor key removed
        ok("pC", "profile_update", mapOf("set" to mapOf("bloodGroup" to "")))
        assertNull(data("pC")["donor_geo"])

        // settings: clamped, deduped; null deletes
        ok("pC", "profile_update", mapOf("set" to mapOf("maxPushPerDay" to 99, "notifyGroups" to listOf("O+", "O+"), "viewGroups" to null, "maxKm" to 25, "fareAfterKm" to 5, "lang" to "en")))
        val p = ok("pC", "profile_get")["profile"]
        assertEquals(10, p["maxPushPerDay"].intValue())
        assertEquals(listOf("O+"), p["notifyGroups"].map { it.textValue() })
        assertEquals(5, p["fareAfterKm"].intValue())
        assertEquals(60, p["weightKg"].intValue())
        ok("pC", "profile_update", mapOf("set" to mapOf("fareAfterKm" to null, "photo" to "QUJD", "photoThumb" to "QQ==")))
        assertNull(data("pC")["fare_after_km"])
        assertTrue(ok("pC", "profile_get")["profile"]["hasPhotoThumb"].booleanValue())
        ok("pC", "profile_update", mapOf("set" to mapOf("photo" to null, "photoThumb" to null)))
        d = data("pC")
        assertNull(d["photo"])
        assertNull(d["photo_thumb"])

        // processing list (commitments)
        ok("pC", "profile_update", mapOf("commitAdd" to "rq1"))
        ok("pC", "profile_update", mapOf("commitAdd" to "rq2"))
        assertEquals(listOf("rq1", "rq2"), ok("pC", "profile_get")["profile"]["commitments"].fieldNames().asSequence().sorted().toList())
        ok("pC", "profile_update", mapOf("commitRemove" to listOf("rq1", "nope")))
        assertEquals(listOf("rq2"), ok("pC", "profile_get")["profile"]["commitments"].fieldNames().asSequence().toList())
        assertEquals(400, call("pC", "profile_update", mapOf("commitAdd" to "bad/id"), lang = "en").status)
        assertEquals(400, call("pC", "profile_update", mapOf("commitRemove" to (0..100).map { "r$it" }), lang = "en").status)
        // nothing to do
        assertTrue(ok("pC", "profile_update", emptyMap())["unchanged"].booleanValue())
    }

    @Test
    fun `last donation date only moves forward`() {
        user("pD", mapOf("name" to "ডি"))
        val t0 = 1767225600000L // 2026-01-01
        val day = 86400000L
        ok("pD", "profile_update", mapOf("set" to mapOf("lastDonationMillis" to t0)))
        assertEquals(403, call("pD", "profile_update", mapOf("set" to mapOf("lastDonationMillis" to t0 - day)), lang = "en").status)
        assertEquals(403, call("pD", "profile_update", mapOf("set" to mapOf("lastDonationMillis" to null)), lang = "en").status)
        assertEquals(t0, data("pD")["last_donation_ms"])
        ok("pD", "profile_update", mapOf("set" to mapOf("lastDonationMillis" to t0)))
        ok("pD", "profile_update", mapOf("set" to mapOf("lastDonationMillis" to t0 + 10 * day)))
        assertEquals(t0 + 10 * day, data("pD")["last_donation_ms"])
        ok("pD", "profile_update", mapOf("set" to mapOf("name" to "ডি২")))
        assertEquals(t0 + 10 * day, data("pD")["last_donation_ms"])
        // no profile yet: any date on the first write
        user("pE")
        ok("pE", "profile_update", mapOf("set" to mapOf("lastDonationMillis" to t0)))
        assertNull(data("pE")["name"])
    }

    @Test
    fun `bilingual errors`() {
        user("pF", mapOf("name" to "x"))
        assertEquals("এটা অ্যাপ থেকে বদলানো যায় না", call("pF", "profile_update", mapOf("set" to mapOf("badge" to "doctor"))).error)
        assertEquals("This can't be changed from the app", call("pF", "profile_update", mapOf("set" to mapOf("badge" to "doctor")), lang = "en").error)
        // no lang → English (community.js tr)
        assertEquals("Invalid profile data", call("pF", "profile_update", mapOf("set" to mapOf("name" to 1)), lang = null).error)
    }
}
