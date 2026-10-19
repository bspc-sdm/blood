package com.lifolo.homecare

import com.lifolo.appointments.P2Cache
import com.lifolo.appointments.P2TestBase
import com.lifolo.contracts.ProviderSuspension
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * homecare.js: the home-care list (GPS or area, service and female filters, badge checked), calls from the app,
 * ratings (1 hour after the first call, within 30 days), the professional's own statistics (demo-services parts).
 */
class HomecareIT : P2TestBase() {
    @Autowired lateinit var suspension: ProviderSuspension

    @Autowired lateinit var cache: P2Cache

    /** a nurse/doctor with home care on */
    private fun homeCarer(uid: String, name: String, type: String = "nurse", gender: String = "female", services: List<String> = listOf("injection", "dressing"), upazilas: List<Int> = listOf(320), phone: String = "01700000180") {
        person(uid, name)
        badge(uid, type, name)
        jdbc.update(
            """
            INSERT INTO providers (uid, type, name, gender, specialties, home_visit_set, home_visit_enabled, home_radius_km, home_upazila_ids, home_services, home_phone, home_fee, legacy_chamber)
            VALUES (?, ?, ?, ?, '{}', TRUE, TRUE, 5, ?::int[], ?::text[], ?, '৩০০', FALSE)
            """.trimIndent(),
            uid, type, name, gender, upazilas.joinToString(",", "{", "}"), services.joinToString(",", "{", "}"), phone,
        )
    }

    @Test
    fun `list, calls, ratings, own statistics`() {
        val gzp = district("Gazipur")
        homeCarer("n1", "ডেমো নার্স নাসরিন")
        homeCarer("n2", "ডেমো নার্স শাহীন", gender = "male", services = listOf("injection"))
        homeCarer("n3", "ডেমো নার্স তানিয়া")
        badge("n3", "nurse", "ডেমো নার্স তানিয়া", status = "pending")
        homeCarer("n4", "পুরোনো নার্স", phone = "")
        homeCarer("dr", "ডা. ডেমো রফিক", type = "doctor", gender = "male", services = listOf("checkup"))
        person("pt", "রোগী")

        fun names(body: Map<String, Any?>, uid: String? = null) = ok(uid, "home_providers", body)["items"].map { it["name"].textValue() }
        val nurses = names(mapOf("type" to "nurse", "district" to gzp))
        assertTrue("ডেমো নার্স নাসরিন" in nurses && "ডেমো নার্স শাহীন" in nurses)
        assertFalse("ডেমো নার্স তানিয়া" in nurses, "badge pending — not listed")
        assertFalse("পুরোনো নার্স" in nurses, "no separate phone yet — not listed")
        assertFalse("ডেমো নার্স শাহীন" in names(mapOf("type" to "nurse", "district" to gzp, "female" to true)))
        assertFalse("ডেমো নার্স শাহীন" in names(mapOf("type" to "nurse", "district" to gzp, "service" to "dressing")))
        assertEquals(listOf("ডা. ডেমো রফিক"), names(mapOf("type" to "doctor", "district" to gzp)))
        assertEquals("ডাক্তার বা নার্স বেছে নিন", call(null, "home_providers", mapOf("type" to "x")).error)
        assertEquals(0, ok(null, "home_providers", mapOf("type" to "nurse", "district" to district("Dhaka")))["items"].size())
        val item = ok("pt", "home_providers", mapOf("type" to "nurse", "district" to gzp))["items"].first { it["uid"].textValue() == "n1" }
        assertEquals(listOf("01700000180", "৩০০", "false", "false"), listOf(item["phone"].textValue(), item["fee"].textValue(), item["called"].asText(), item["canRate"].asText()))
        assertTrue(item["km"].isNull, "area search: distance unknown")
        // the professional is not listed for themselves
        assertFalse(names(mapOf("type" to "nurse", "district" to gzp), "n1").contains("ডেমো নার্স নাসরিন"))

        // GPS: within the radius with a rough distance
        jdbc.update("UPDATE user_profiles SET lat = 23.999, lng = 90.42, location_source = 'gps' WHERE uid = 'n1'")
        jdbc.update("UPDATE providers SET home_upazila_ids = '{}' WHERE uid = 'n1'")
        cache.clearProviders() // the list is kept 2 minutes (memcache home:)
        val near = ok("pt", "home_providers", mapOf("type" to "nurse", "lat" to 24.0, "lng" to 90.43))["items"].first { it["uid"].textValue() == "n1" }
        assertEquals(2, near["km"].intValue(), "at least 2 km, whole km")

        // calls: counted once per 10 minutes; ratings 1 hour after the first call
        val t0 = dhaka("2026-10-19 10:00")
        clock.fixedMs = t0
        ok("pt", "home_called", mapOf("uid" to "n1"))
        ok("pt", "home_called", mapOf("uid" to "n1"))
        ok("pt", "home_called", mapOf("uid" to "pt")) // own: ignored
        assertEquals("অ্যাপ থেকে ফোন করার পর রেটিং দিতে পারবেন", call("pt", "home_rate", mapOf("uid" to "n2", "rating" to 5)).error)
        assertEquals("প্রথম ফোনের ১ ঘণ্টা পর রেটিং দিতে পারবেন", call("pt", "home_rate", mapOf("uid" to "n1", "rating" to 5)).error)
        assertEquals("১ থেকে ৫ তারা দিন", call("pt", "home_rate", mapOf("uid" to "n1", "rating" to 6)).error)
        assertEquals(404, call("pt", "home_rate", mapOf("uid" to "nobody", "rating" to 5)).status)
        clock.fixedMs = t0 + 61 * 60_000L
        ok("pt", "home_called", mapOf("uid" to "n1"))
        ok("pt", "home_rate", mapOf("uid" to "n1", "rating" to 4, "comment" to "ভালো"))
        // a changed rating replaces the old one
        ok("pt", "home_rate", mapOf("uid" to "n1", "rating" to 5, "comment" to "খুব যত্নশীল"))
        val p = row("SELECT rating_sum, rating_count FROM providers WHERE uid = 'n1'")
        assertEquals(listOf(5, 1), listOf(p["rating_sum"], p["rating_count"]))
        val seen = ok("pt", "home_providers", mapOf("type" to "nurse", "district" to gzp))
        // (the list is cached for 2 minutes, but the own call/rating is read fresh)
        val mine = seen["items"].firstOrNull { it["uid"].textValue() == "n1" }
        if (mine != null) {
            assertEquals(listOf("true", "5", "true"), listOf(mine["called"].asText(), mine["myRating"].asText(), mine["canRate"].asText()))
        }
        val hs = ok("n1", "home_stats")
        assertEquals(true, hs["enabled"].booleanValue())
        assertEquals(false, hs["blocked"].booleanValue())
        assertEquals(2, hs["calls"]["total"].intValue(), "the second press within 10 minutes is not a new call")
        assertEquals(1, hs["callers"].intValue())
        assertEquals(5, hs["rating"].intValue())
        assertEquals(1, hs["ratingCount"].intValue())
        assertEquals(listOf("খুব যত্নশীল"), hs["comments"].map { it["comment"].textValue() })
        assertFalse(hs["comments"][0].has("who"), "who rated is not shown")
        assertEquals(0, hs["reports"].intValue())
        // demo and real do not mix
        person("demo-pt")
        ok("demo-pt", "home_called", mapOf("uid" to "n1"))
        assertEquals(2, ok("n1", "home_stats")["calls"]["total"].intValue())
        assertEquals(404, call("demo-pt", "home_rate", mapOf("uid" to "n1", "rating" to 5)).status)

        // blocked after a report: out of the list, cannot be switched on again
        suspension.blockHomeCare("n2")
        val hs2 = ok("n2", "home_stats")
        assertEquals(listOf(false, true), listOf(hs2["enabled"].booleanValue(), hs2["blocked"].booleanValue()))
    }
}
