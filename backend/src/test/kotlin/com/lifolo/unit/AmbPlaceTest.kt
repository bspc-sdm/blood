package com.lifolo.unit

import com.lifolo.ambulance.AmbPlaces
import com.lifolo.ambulance.MapsFetch
import com.lifolo.ambulance.MapsResponse
import com.lifolo.common.Places
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URLEncoder

/** pure parts of server/test/ambulance-place.test.js: Google Maps links/text, short-link resolving (fake fetch), upazila from a point */
class AmbPlaceTest {
    private fun p(s: String) = AmbPlaces.parseMapsText(s)

    private fun pt(lat: Double, lng: Double) = AmbPlaces.Companion.Parsed(point = lat to lng)

    private class Route(val status: Int, val location: String? = null, val body: String = "")

    /** fake fetch: url → answer; the urls asked are recorded */
    private fun fake(routes: Map<String, Route>, calls: MutableList<String> = mutableListOf()) = MapsFetch { url ->
        calls.add(url)
        val r = routes[url] ?: Route(404)
        MapsResponse(r.status, r.location) { r.body }
    }

    @Test
    fun `maps links and text - real place first, q, search, @, geo, plain numbers, short links apart, never outside Bangladesh`() {
        assertEquals(
            pt(23.72564, 90.3977),
            p("https://www.google.com/maps/place/Dhaka+Medical+College+Hospital/@23.7261,90.3955,17z/data=!3m1!4b1!4m6!3m5!1s0x3755b8c1!8m2!3d23.725636!4d90.397697!16s%2Fm%2F0abc"),
        )
        assertEquals(pt(23.9999, 90.4203), p("https://maps.google.com/?q=23.9999,90.4203"))
        assertEquals(pt(24.7471, 90.4203), p("https://www.google.com/maps/search/?api=1&query=24.7471%2C90.4203"))
        assertEquals(pt(24.7471, 90.4203), p("https://www.google.com/maps/search/24.7471,+90.4203?entry=tts"))
        assertEquals(pt(24.7471, 90.4203), p("https://www.google.com/maps/@24.7471,90.4203,15z"))
        assertEquals(pt(24.7471, 90.4203), p("geo:24.7471,90.4203?q=24.7471,90.4203(Pin)"))
        assertEquals(pt(23.81033, 90.41252), p("23.810331, 90.412521"))
        assertEquals(
            AmbPlaces.Companion.Parsed(short = "https://maps.app.goo.gl/AbC12xYz?g_st=ic"),
            p("Dropped pin\nNear Trishal\nhttps://maps.app.goo.gl/AbC12xYz?g_st=ic"),
        )
        assertEquals(AmbPlaces.Companion.Parsed(short = "https://goo.gl/maps/Xy12AB"), p("See https://goo.gl/maps/Xy12AB please"))
        assertNull(p("https://maps.google.com/?q=51.5074,-0.1278"))
        assertNull(p("https://example.com/xyz"))
        assertNull(p("https://goo.gl/abc"))
        assertNull(p("   "))
    }

    @Test
    fun `short links - redirects followed to a point, only Google addresses, page text searched, time limit, no fetch for other links`() {
        val short = "https://maps.app.goo.gl/AbC12xYz"
        val place = "https://www.google.com/maps/place/Trishal/@24.58,90.39,14z/data=!4m6!3m5!8m2!3d24.5637!4d90.4104"
        var calls = mutableListOf<String>()
        assertEquals(24.5637 to 90.4104, AmbPlaces.resolveShort(short, fake(mapOf(short to Route(302, place)), calls)))
        assertEquals(listOf(short), calls)

        val consent = "https://consent.google.com/m?continue=" + URLEncoder.encode("https://www.google.com/maps/search/24.7471,+90.4203", "UTF-8").replace("+", "%20")
        assertEquals(24.7471 to 90.4203, AmbPlaces.resolveShort(short, fake(mapOf(short to Route(301, consent)))))

        val noCoords = "https://www.google.com/maps/place/Some+Clinic"
        assertEquals(
            23.72564 to 90.3977,
            AmbPlaces.resolveShort(
                short,
                fake(
                    mapOf(
                        short to Route(302, noCoords),
                        noCoords to Route(200, body = "<html>…window.APP_INITIALIZATION_STATE=[[[5000,90.3977,23.7256],[0,0,0]],[null,null,23.725636,90.397697]]…"),
                    ),
                ),
            ),
        )

        calls = mutableListOf()
        assertNull(AmbPlaces.resolveShort(short, fake(mapOf(short to Route(302, "https://evil.example/@23.7,90.4")), calls)))
        assertEquals(1, calls.size)
        assertNull(AmbPlaces.resolveShort(short, fake(mapOf(short to Route(302, "http://www.google.com/maps/@23.7,90.4,15z")))))
        calls = mutableListOf()
        val loop = MapsFetch { url ->
            calls.add(url)
            MapsResponse(302, "https://www.google.com/maps/loop") { "" }
        }
        assertNull(AmbPlaces.resolveShort(short, loop))
        assertTrue(calls.size <= 5)
        val hang = MapsFetch { _ ->
            Thread.sleep(30_000)
            MapsResponse(200, null) { "" }
        }
        val t0 = System.currentTimeMillis()
        assertNull(AmbPlaces.resolveShort(short, hang, 50))
        assertTrue(System.currentTimeMillis() - t0 < 2000)
        calls = mutableListOf()
        for (u in listOf("https://example.com/x", "http://maps.app.goo.gl/AbC", "https://goo.gl/abc", "not a url")) {
            assertNull(AmbPlaces.resolveShort(u, fake(emptyMap(), calls)))
        }
        assertEquals(0, calls.size)
    }

    @Test
    fun `upazila from a point - nearest centre in the district, not in city thanas (no centre) or far away`() {
        val mym = Places.DISTRICTS.first { it.en == "Mymensingh" }.bn
        val dhaka = Places.DISTRICTS.first { it.en == "Dhaka" }.bn
        val gazipur = Places.DISTRICTS.first { it.en == "Gazipur" }.bn
        val (tLat, tLng) = Places.UPAZILA_CENTER.getValue(463)
        assertEquals(463, AmbPlaces.nearestUpazila(mym, tLat + 0.01, tLng - 0.01))
        val (sLat, sLng) = Places.UPAZILA_CENTER.getValue(320)
        assertEquals(320, AmbPlaces.nearestUpazila(gazipur, sLat, sLng))
        assertNull(AmbPlaces.nearestUpazila(dhaka, 23.7383, 90.3955), "Dhaka city (Shahbag): thanas have no centre, the city centre is closer")
        val (vLat, vLng) = Places.UPAZILA_CENTER.getValue(365)
        assertEquals(365, AmbPlaces.nearestUpazila(dhaka, vLat, vLng))
        assertNull(AmbPlaces.nearestUpazila(mym, 22.35, 91.8))
        assertNull(AmbPlaces.nearestUpazila(mym, Double.NaN, 90.0))
    }

    @Test
    fun `decodeURIComponent like JavaScript`() {
        assertEquals("24.7471,90.4203", AmbPlaces.decodeURIComponent("24.7471%2C90.4203"))
        assertEquals("ঢাকা", AmbPlaces.decodeURIComponent("%E0%A6%A2%E0%A6%BE%E0%A6%95%E0%A6%BE"))
        assertEquals("a+b", AmbPlaces.decodeURIComponent("a+b"))
        assertTrue(runCatching { AmbPlaces.decodeURIComponent("%E0%A6") }.isFailure)
        assertTrue(runCatching { AmbPlaces.decodeURIComponent("100%") }.isFailure)
    }
}
