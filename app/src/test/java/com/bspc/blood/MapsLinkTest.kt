package com.bspc.blood

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Google Maps-এর লিংক/শেয়ারের লেখা থেকে জায়গা (২.৩১.০) — সার্ভারের test/ambulance-place.test.js-এর মতো একই নিয়ম */
class MapsLinkTest {
    private fun at(lat: Double, lng: Double) = MapsLink.Parsed.At(lat, lng)

    @Test
    fun parsesLinksAndText() {
        assertEquals(at(23.72564, 90.3977), MapsLink.parse("https://www.google.com/maps/place/DMCH/@23.7261,90.3955,17z/data=!3m1!4b1!8m2!3d23.725636!4d90.397697"))
        assertEquals(at(23.9999, 90.4203), MapsLink.parse("https://maps.google.com/?q=23.9999,90.4203"))
        assertEquals(at(24.7471, 90.4203), MapsLink.parse("https://www.google.com/maps/search/?api=1&query=24.7471%2C90.4203"))
        assertEquals(at(24.7471, 90.4203), MapsLink.parse("https://www.google.com/maps/@24.7471,90.4203,15z"))
        assertEquals(at(23.81033, 90.41252), MapsLink.parse("23.810331, 90.412521"))
        assertEquals(MapsLink.Parsed.Short("https://maps.app.goo.gl/AbC12xYz?g_st=ic"), MapsLink.parse("Dropped pin\nhttps://maps.app.goo.gl/AbC12xYz?g_st=ic"))
        assertNull(MapsLink.parse("https://maps.google.com/?q=51.5074,-0.1278"))
        assertNull(MapsLink.parse("হাসপাতালের পাশে"))
        assertEquals(true, MapsLink.LINK.containsMatchIn("বাসা ১২ https://maps.app.goo.gl/x"))
    }
}
