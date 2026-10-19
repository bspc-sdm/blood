package com.lifolo.ambulance

import com.lifolo.common.Places
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** port of the action parts of server/test/ambulance-place.test.js */
class AmbPlaceIT : AmbTestBase() {
    private val MMCH = "dghs_392"
    private val TRISHAL_HOSP = "dghs_19935"
    private val DMCH = "dghs_31"
    private val veh = Veh(MYM, SADAR)

    private fun dest(m: Map<String, Any?>) = AmbFare.destOf(json(m), places::facility)

    @Test
    fun `maps_link_resolve - long links directly, short links through the fetch; Bangla error; login; 6 a minute`() {
        val calls = mutableListOf<String>()
        val short = "https://maps.app.goo.gl/Zz99"
        val real = places.fetch
        places.fetch = MapsFetch { url ->
            calls.add(url)
            if (url == short) MapsResponse(302, "https://www.google.com/maps/@24.5637,90.4104,15z") { "" } else MapsResponse(404, null) { "" }
        }
        try {
            person("link-user")
            assertEquals(json(mapOf("lat" to 24.7471, "lng" to 90.4203)), ok("link-user", "maps_link_resolve", mapOf("url" to "https://maps.google.com/?q=24.7471,90.4203")))
            assertEquals(0, calls.size)
            assertEquals(json(mapOf("lat" to 24.5637, "lng" to 90.4104)), ok("link-user", "maps_link_resolve", mapOf("url" to "জায়গা: $short")))
            assertEquals(listOf(short), calls)
            val bad = call("link-user", "maps_link_resolve", mapOf("url" to "https://maps.app.goo.gl/none"))
            assertEquals(400, bad.status)
            assertTrue(bad.error!!.contains("চেপে ধরে"))
            assertEquals(400, call("link-user", "maps_link_resolve", mapOf("url" to "হাসপাতালের পাশে")).status)
            assertEquals(401, call(null, "maps_link_resolve", mapOf("url" to short)).status)
            person("link-busy")
            repeat(6) { assertEquals(200, call("link-busy", "maps_link_resolve", mapOf("url" to short)).status) }
            assertEquals(429, call("link-busy", "maps_link_resolve", mapOf("url" to short)).status)
            assertEquals(200, call("link-busy", "maps_link_resolve", mapOf("url" to "https://maps.google.com/?q=24.7471,90.4203")).status, "long links are not limited")
        } finally {
            places.fetch = real
        }
    }

    @Test
    fun `calling with a point - upazila found from it (also when counting), written place optional; no exact place before accepting`() {
        AmbClock.setDhaka("2026-10-19 12:00")
        val (tLat, tLng) = Places.UPAZILA_CENTER.getValue(TRISHAL)
        person("place-p")
        amb(
            "pa1", "place-d", upazilaId = TRISHAL, base = tLat to tLng, exactBase = true,
            chart = fc(800, listOf(FareRow(Dest("district", district = DHAKA), 5000))),
        )
        val where = mapOf("district" to MYM, "lat" to tLat + 0.005, "lng" to tLng, "gps" to true)
        val d = mapOf("kind" to "hospital", "facilityId" to TRISHAL_HOSP, "name" to "ব্র্যাক ত্রিশাল")
        val c = ok("place-p", "ambreq_count", where + mapOf("dest" to d, "maxFare" to 1000))
        assertEquals(TRISHAL, c["upazilaId"].intValue())
        assertEquals(1, c["count"].intValue())
        assertTrue(!ok("place-p", "ambreq_count", where + mapOf("upazilaId" to SADAR, "dest" to d)).has("upazilaId"), "a chosen upazila stays")
        assertEquals(400, call("place-p", "ambreq_create", mapOf("district" to MYM, "upazilaId" to TRISHAL, "pickup" to "", "phone" to "01811000000", "dest" to d)).status)
        val r = ok("place-p", "ambreq_create", where + mapOf("pickup" to "", "phone" to "01811000000", "dest" to d, "maxFare" to 1000))
        val id = r["id"].textValue()
        val x = reqRow(id)
        assertEquals(TRISHAL, x["upazila_id"])
        assertEquals(true, x["upazila_auto"])
        assertEquals("", x["pickup"])
        assertEquals(
            json(mapOf("kind" to "hospital", "facilityId" to TRISHAL_HOSP, "name" to "ব্র্যাক ত্রিশাল", "upazilaId" to TRISHAL, "district" to MYM)),
            r["request"]["dest"],
        )
        val dv = ok("place-d", "ambreq_driver")["items"].first { it["id"].textValue() == id }
        assertEquals("ব্র্যাক ত্রিশাল", dv["destLabel"].textValue())
        assertEquals(800, dv["suggestedFare"].intValue())
        assertNull(dv["lat"])
    }

    @Test
    fun `destination hospitals and fares - list id first, then name, then the hospital's upazila or district; area from the list`() {
        val mmch = dest(mapOf("kind" to "hospital", "facilityId" to MMCH, "name" to "মমেক", "upazilaId" to TRISHAL, "district" to DHAKA))
        assertEquals(Dest("hospital", upazilaId = SADAR, district = MYM, facilityId = MMCH, name = "মমেক"), mmch)
        assertEquals("ঢাকা মেডিকেল কলেজ হাসপাতাল", dest(mapOf("kind" to "hospital", "facilityId" to DMCH))!!.name)
        assertEquals(Dest("hospital", name = "চরপাড়া ক্লিনিক"), dest(mapOf("kind" to "hospital", "name" to "চরপাড়া ক্লিনিক")))
        assertEquals(Dest("hospital", name = "চরপাড়া ক্লিনিক"), dest(mapOf("kind" to "hospital", "facilityId" to "../x", "name" to "চরপাড়া ক্লিনিক")))
        assertNull(dest(mapOf("kind" to "hospital", "facilityId" to "dghs_0")))

        val byId = fc(800, listOf(FareRow(Dest("hospital", facilityId = MMCH, name = "মেডিকেল"), 650), toDhaka(5000)))
        assertEquals(650, AmbFare.baseFare(byId, veh, mmch))
        val byName = fc(800, listOf(FareRow(Dest("hospital", name = "ময়মনসিংহ মেডিকেল কলেজ হাসপাতাল"), 700)))
        assertEquals(700, AmbFare.baseFare(byName, veh, dest(mapOf("kind" to "hospital", "facilityId" to TRISHAL_HOSP, "name" to "ময়মনসিংহ মেডিকেল কলেজ হাসপাতাল"))))
        val areas = fc(800, listOf(FareRow(Dest("upazila", upazilaId = TRISHAL), 1200), toDhaka(5000)))
        assertEquals(800, AmbFare.baseFare(areas, veh, dest(mapOf("kind" to "hospital", "facilityId" to MMCH, "name" to "মমেক"))))
        assertEquals(1200, AmbFare.baseFare(areas, veh, dest(mapOf("kind" to "hospital", "facilityId" to TRISHAL_HOSP, "name" to "ব্র্যাক"))))
        assertEquals(5000, AmbFare.baseFare(areas, veh, dest(mapOf("kind" to "hospital", "facilityId" to DMCH, "name" to "ঢামেক"))))
        assertNull(AmbFare.baseFare(areas, veh, dest(mapOf("kind" to "hospital", "name" to "অন্য কোথাও"))))

        val n = AmbFare.normalizeChart(
            json(mapOf("local" to 800, "rows" to listOf(mapOf("kind" to "hospital", "facilityId" to DMCH, "name" to "ঢামেক", "amount" to 5500), mapOf("kind" to "hospital", "name" to "ঢাকা মেডিকেল", "amount" to 5600)))),
            veh, places::facility,
        )
        assertEquals(json(mapOf("kind" to "hospital", "facilityId" to DMCH, "name" to "ঢামেক", "upazilaId" to 1039, "district" to DHAKA, "amount" to 5500)), json(n.chart!!.rows[0].toMap()))
        val dup = AmbFare.normalizeChart(
            json(mapOf("local" to 800, "rows" to listOf(mapOf("kind" to "hospital", "facilityId" to DMCH, "name" to "ঢামেক", "amount" to 5500), mapOf("kind" to "hospital", "facilityId" to DMCH, "name" to "ঢাকা মেডিকেল", "amount" to 5600)))),
            veh, places::facility,
        )
        assertTrue(dup.error!!.second.contains("দুবার"))
        // a saved hospital row keeps its list id
        person("fac-owner")
        amb("fa1", "fac-owner", chart = n.chart)
        assertEquals(n.chart, repo.get("fa1")!!.chart)
    }

    @Test
    fun `hospital list - the district's (no diagnostics, big ones first; nearest first with a point), the country's big ones, search in Bangla and English`() {
        person("fac-user")
        val f = ok("fac-user", "amb_facilities", mapOf("district" to MYM))
        assertTrue(f["items"].size() > 100)
        assertTrue(f["items"].all { it[4].textValue() == MYM && it[3].textValue() != "diagnostic" })
        assertEquals(MMCH, f["items"][0][0].textValue())
        val top = f["top"].map { it[0].textValue() }
        for (id in listOf(DMCH, "dghs_7", "x_bsmmu", "dghs_22839")) assertTrue(top.contains(id), id)
        val (tLat, tLng) = Places.UPAZILA_CENTER.getValue(TRISHAL)
        val near = ok("fac-user", "amb_facilities", mapOf("district" to MYM, "lat" to tLat, "lng" to tLng))
        assertEquals(TRISHAL, near["items"][0][5].intValue())
        val yaA = "ম\u09AF\u09BCমনসিংহ মেডিকেল" // য + ়
        val yaB = "ময়মনসিংহ মেডিকেল" // য়
        for (q in listOf(yaA, yaB, "mymensingh medical")) {
            assertEquals(MMCH, ok("fac-user", "amb_facilities", mapOf("district" to MYM, "q" to q))["items"][0][0].textValue(), q)
        }
        assertTrue(ok("fac-user", "amb_facilities", mapOf("district" to MYM, "q" to "dhaka medical college"))["items"].any { it[0].textValue() == DMCH })
        assertEquals("x_bsmmu", ok("fac-user", "amb_facilities", mapOf("district" to MYM, "q" to "পিজি"))["items"][0][0].textValue())
        assertEquals(400, call("fac-user", "amb_facilities", mapOf("district" to "x")).status)
    }
}
