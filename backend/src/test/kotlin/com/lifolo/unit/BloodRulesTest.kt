package com.lifolo.unit

import com.fasterxml.jackson.databind.ObjectMapper
import com.lifolo.blood.BloodRules
import com.lifolo.blood.Donor
import com.lifolo.blood.EmergencyRules
import com.lifolo.blood.GeoBounds
import com.lifolo.blood.RequestFacts
import com.lifolo.blood.ShareController
import com.lifolo.common.Geo
import com.lifolo.common.I18n
import com.lifolo.common.Places
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** Ports of server/test/rules.test.js (blood parts), gaps.test.js (isActive, bagsLeft), emergency.test.js, geo.test.js */
class BloodRulesTest {
    private val DAY = BloodRules.DAY_MS

    private fun utc(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0): Long =
        LocalDate.of(y, m, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + (h * 60L + min) * 60_000

    private val mym = Places.DISTRICTS.first { it.en == "Mymensingh" }

    private data class Req(
        override val requesterUid: String = "me",
        override val patientGroup: String = "A+",
        override val bags: Int = 2,
        override val hospital: String = "মমেক",
        override val placeName: String = Places.DISTRICTS.first { it.en == "Mymensingh" }.bn,
        override val lat: Double = 24.75,
        override val lng: Double = 90.4,
        override val neededByMillis: Long? = null,
        override val neededAtMillis: Long? = null,
        override val travelFare: Boolean = false,
        override val status: String = "open",
    ) : RequestFacts

    @Test
    fun `blood group matching`() {
        assertTrue(BloodRules.canDonate("O-", "AB+"))
        assertTrue(BloodRules.canDonate("O-", "O-"))
        assertFalse(BloodRules.canDonate("O+", "O-"))
        assertFalse(BloodRules.canDonate("A+", "B+"))
        assertTrue(BloodRules.canDonate("B-", "AB-"))
        assertFalse(BloodRules.canDonate("AB+", "A+"))
    }

    @Test
    fun `120-day eligibility`() {
        val now = utc(2026, 10, 15)
        assertTrue(BloodRules.isEligible(null, now))
        assertFalse(BloodRules.isEligible(now - 119 * DAY, now))
        assertTrue(BloodRules.isEligible(now - 120 * DAY, now))
    }

    @Test
    fun `distance Dhaka to Mymensingh about 110 km, near districts`() {
        val dhaka = Places.DISTRICTS.first { it.en == "Dhaka" }
        val km = BloodRules.distanceKm(dhaka.lat, dhaka.lng, mym.lat, mym.lng)
        assertTrue(km > 100 && km < 125, km.toString())
        val near = BloodRules.nearDistricts(24.7466, 90.4072)
        assertEquals(mym.bn, near[0])
        assertTrue(near.size in 1..30)
    }

    @Test
    fun `Dhaka date at 1 am is already the next day`() {
        val dhaka1am = utc(2026, 10, 14, 19, 0)
        assertEquals(utc(2026, 10, 15), BloodRules.todayDhakaUtcMidnight(dhaka1am))
        assertEquals("20261015", BloodRules.dhakaDayKey(dhaka1am))
    }

    private val base = Donor(uid = "", bloodGroup = null, lat = null, lng = null, notify = true, birthDateMillis = utc(1995, 1, 1), weightKg = 60.0)

    @Test
    fun `pickDonors - group, eligibility, self excluded, widening radius`() {
        val now = utc(2026, 10, 15)
        val users = listOf(
            base.copy(uid = "me", bloodGroup = "A+", lat = 24.75, lng = 90.4),
            base.copy(uid = "near", bloodGroup = "O-", lat = 24.76, lng = 90.4),
            base.copy(uid = "wrongGroup", bloodGroup = "B+", lat = 24.75, lng = 90.41),
            base.copy(uid = "notEligible", bloodGroup = "A+", lat = 24.75, lng = 90.41, lastDonationMillis = now - 10 * DAY),
            base.copy(uid = "off", bloodGroup = "A+", lat = 24.75, lng = 90.41, notify = false),
            base.copy(uid = "noToken", bloodGroup = "A+", lat = 24.75, lng = 90.41, hasTokens = false),
            base.copy(uid = "far20", bloodGroup = "A-", lat = 24.93, lng = 90.4, maxKm = 25),
            base.copy(uid = "far60", bloodGroup = "A-", lat = 25.29, lng = 90.4),
        )
        assertEquals(listOf("near", "far20"), BloodRules.pickDonors(Req(), users, now).map { it.donor.uid })
    }

    @Test
    fun `pickDonors - notification group settings`() {
        val now = utc(2026, 10, 15)
        val b = base.copy(bloodGroup = "O-", lat = 24.75, lng = 90.41)
        val users = listOf(
            b.copy(uid = "all"),
            b.copy(uid = "onlyAplus", notifyGroups = listOf("A+", "O-")),
            b.copy(uid = "onlyB", notifyGroups = listOf("B+", "B-")),
            b.copy(uid = "stale", bloodGroup = "A+", notifyGroups = listOf("AB-")),
        )
        assertEquals(listOf("all", "onlyAplus", "stale"), BloodRules.pickDonors(Req(), users, now).map { it.donor.uid }.sorted())
    }

    @Test
    fun `pickDonors - own travel limit and travel cost`() {
        val now = utc(2026, 10, 15)
        val b = base.copy(bloodGroup = "O-", lng = 90.4)
        val users = listOf(
            b.copy(uid = "r5_at3", lat = 24.777, maxKm = 5),
            b.copy(uid = "r5_at20", lat = 24.93, maxKm = 5),
            b.copy(uid = "r50_at40", lat = 25.11, maxKm = 50),
            b.copy(uid = "default_at20", lat = 24.93),
            b.copy(uid = "fare5_at20", lat = 24.93, maxKm = 25, fareAfterKm = 5),
        )
        fun ids(r: Req) = BloodRules.pickDonors(r, users, now).map { it.donor.uid }.sorted()
        assertEquals(listOf("r50_at40", "r5_at3"), ids(Req()))
        assertEquals(listOf("fare5_at20", "r50_at40", "r5_at3"), ids(Req(travelFare = true)))
        assertEquals(10, BloodRules.donorRadius(7))
    }

    @Test
    fun `validateRequest - good data passes, bad data is caught`() {
        val now = utc(2026, 10, 15)
        val good = Req(neededByMillis = now)
        assertNull(BloodRules.validateRequest(good, now))
        assertNotNull(BloodRules.validateRequest(good.copy(patientGroup = "Z+"), now))
        assertNotNull(BloodRules.validateRequest(good.copy(bags = 50), now))
        assertNotNull(BloodRules.validateRequest(good.copy(placeName = "লন্ডন"), now))
        assertNotNull(BloodRules.validateRequest(good.copy(lat = 51.5), now))
        assertNotNull(BloodRules.validateRequest(good.copy(neededByMillis = now + 90 * DAY), now))
        assertNotNull(BloodRules.validateRequest(good.copy(hospital = "x".repeat(200)), now))
        // raw JSON values as request_create gets them
        assertEquals("ব্যাগের সংখ্যা ঠিক নেই", BloodRules.validateRequest("A+", 1.5, "h", mym.bn, 24.75, 90.4, now, null, now))
        assertEquals("রক্তের গ্রুপ ঠিক নেই", BloodRules.validateRequest(5L, 1L, "h", mym.bn, 24.75, 90.4, now, null, now))
        assertEquals("তারিখ ঠিক নেই", BloodRules.validateRequest("A+", 1L, "h", mym.bn, 24.75, 90.4, null, null, now))
        assertEquals("Invalid number of bags", I18n.toEnglish("ব্যাগের সংখ্যা ঠিক নেই"))
    }

    @Test
    fun `age and weight eligibility`() {
        val now = utc(2026, 10, 15)
        val ok = base
        assertTrue(BloodRules.isEligibleDonor(ok, now))
        assertFalse(BloodRules.isEligibleDonor(ok.copy(weightKg = 45.0), now))
        assertFalse(BloodRules.isEligibleDonor(ok.copy(birthDateMillis = utc(2010, 1, 1)), now))
        assertFalse(BloodRules.isEligibleDonor(ok.copy(birthDateMillis = utc(1960, 1, 1)), now))
        assertFalse(BloodRules.isEligibleDonor(ok.copy(birthDateMillis = null), now))
        assertEquals(17, BloodRules.ageYears(utc(2008, 10, 16), now))
        assertEquals(18, BloodRules.ageYears(utc(2008, 10, 15), now))
    }

    @Test
    fun `isActive - until the day after the date, 6 h after a set time`() {
        val needed = utc(2026, 10, 15)
        assertTrue(BloodRules.isActive("open", needed, null, needed + 23 * 3600_000L))
        assertFalse(BloodRules.isActive("open", needed, null, needed + DAY + 1))
        assertFalse(BloodRules.isActive("closed", needed, null, needed))
        val day = utc(2026, 10, 20)
        val at = day - 6 * 3600_000L + 9 * 3600_000L
        assertTrue(BloodRules.isActive("open", day, at, at + 5 * 3600_000L))
        assertFalse(BloodRules.isActive("open", day, at, at + 7 * 3600_000L))
        assertTrue(BloodRules.isActive("open", day, null, day + 20 * 3600_000L))
        assertFalse(BloodRules.isActive("open", day, null, day + 25 * 3600_000L))
    }

    @Test
    fun `bags left counts confirmed donors or bags received, whichever is more`() {
        assertEquals(2, BloodRules.bagsLeft(3, 1, 0))
        assertEquals(1, BloodRules.bagsLeft(3, 1, 2))
        assertEquals(0, BloodRules.bagsLeft(2, 3, 0))
    }

    @Test
    fun `daily push limit setting`() {
        assertEquals(3, BloodRules.pushLimit(null))
        assertEquals(1, BloodRules.pushLimit(1))
        assertEquals(10, BloodRules.pushLimit(10))
        assertEquals(10, BloodRules.pushLimit(500))
        assertEquals(1, BloodRules.pushLimit(0))
    }

    @Test
    fun `needed time lies on the chosen date (Dhaka time), time texts`() {
        val date = utc(2026, 10, 20)
        fun at(h: Int, m: Int) = date + (h * 60L + m) * 60_000 - 6 * 3600_000L
        assertTrue(BloodRules.neededAtMatchesDate(at(0, 0), date))
        assertTrue(BloodRules.neededAtMatchesDate(at(16, 30), date))
        assertTrue(BloodRules.neededAtMatchesDate(at(23, 59), date))
        assertFalse(BloodRules.neededAtMatchesDate(at(24, 0), date))
        assertFalse(BloodRules.neededAtMatchesDate(at(-1, 0), date))
        assertEquals("বিকাল ৪:০৫", BloodRules.formatTimeBn(at(16, 5)))
        assertEquals("রাত ১২:৩০", BloodRules.formatTimeBn(at(0, 30)))
        assertEquals("দুপুর ১২:০০", BloodRules.formatTimeBn(at(12, 0)))
        assertEquals("4:05 PM", BloodRules.formatTimeEn(at(16, 5)))
        val now = utc(2026, 10, 15)
        val good = Req(neededByMillis = date)
        assertNull(BloodRules.validateRequest(good.copy(neededAtMillis = at(10, 0)), now))
        assertNotNull(BloodRules.validateRequest(good.copy(neededAtMillis = at(30, 0)), now))
    }

    @Test
    fun `km texts and district labels`() {
        assertEquals("১ কিমির কম দূরে", BloodRules.formatKm(0.5, "bn"))
        assertEquals("about 13 km away", BloodRules.formatKm(12.5, "en"))
        assertEquals("প্রায় ২ কিমি দূরে", BloodRules.formatKm(2.4, "bn"))
        assertEquals("Mymensingh", BloodRules.districtLabel(mym.bn, "en"))
        assertEquals(mym.bn, BloodRules.districtLabel(mym.bn, "bn"))
    }

    @Test
    fun `donation date change risk`() {
        val D = DAY
        assertNull(BloodRules.donationRisk(null, 100 * D))
        assertEquals("backward", BloodRules.donationRisk(500 * D, 400 * D))
        assertEquals("backward", BloodRules.donationRisk(500 * D, null))
        assertEquals("short_gap", BloodRules.donationRisk(500 * D, 560 * D))
        assertNull(BloodRules.donationRisk(500 * D, 620 * D))
        assertNull(BloodRules.donationRisk(500 * D, 500 * D))
    }

    // ---------------------------------------------------------------- emergency-rules (emergency.test.js)

    private val NOW = utc(2026, 10, 15)
    private fun u(uid: String, group: String, lat: Double, f: (Donor) -> Donor = { it }) =
        f(base.copy(uid = uid, bloodGroup = group, lat = lat, lng = 90.4))

    @Test
    fun `emergency - groups and bags are checked`() {
        val g = EmergencyRules.parseGroups(ObjectMapper().readTree("""{"A+": 2, "B-": 0, "X+": 5, "O+": 999, "AB-": 1.5}"""))
        assertEquals(listOf("A+", "O+"), g.keys.sorted())
        assertEquals(EmergencyRules.MAX_NEED, g["O+"]!!.need)
        assertEquals(0, g["A+"]!!.coming)
        assertEquals(5, EmergencyRules.waveSize(1))
        assertEquals(12, EmergencyRules.waveSize(4))
    }

    @Test
    fun `emergency - nearest eligible first, full or closed groups and earlier ones skipped`() {
        val users = listOf(
            u("a1", "A+", 23.81), u("a2", "A+", 23.9), u("a3", "A+", 23.85),
            u("far", "A+", 25.0),
            // emergency appeals have their own switch (notify_prefs blood_emergency); the nearby switch does not matter
            u("off", "A+", 23.8) { it.copy(notifyEmergency = false) },
            u("nearbyoff", "A+", 23.82) { it.copy(notify = false) },
            u("recent", "A+", 23.8) { it.copy(lastDonationMillis = NOW - 10 * 86400000L) },
            u("notoken", "A+", 23.8) { it.copy(hasTokens = false) },
            u("b1", "B+", 23.8),
            u("o1", "O+", 23.8),
        )
        val groups = EmergencyRules.parseGroups(ObjectMapper().readTree("""{"A+": 1, "B+": 1, "O+": 1}"""))
        groups["O+"]!!.closed = true
        val wave = EmergencyRules.pickWave(users, 23.8, 90.4, groups, 25, setOf("a3"), NOW)
        assertEquals(listOf("a1", "nearbyoff", "a2"), wave.filter { it.group == "A+" }.map { it.uid })
        assertEquals(listOf("b1"), wave.filter { it.group == "B+" }.map { it.uid })
        assertFalse(wave.any { it.group == "O+" })
        groups["A+"]!!.coming = 1
        assertFalse(EmergencyRules.pickWave(users, 23.8, 90.4, groups, 25, emptySet(), NOW).any { it.group == "A+" })
    }

    @Test
    fun `emergency - live`() {
        assertTrue(EmergencyRules.isLive("active", NOW + 1, NOW))
        assertFalse(EmergencyRules.isLive("active", NOW - 1, NOW))
        assertFalse(EmergencyRules.isLive("closed", NOW + 1, NOW))
    }

    // ---------------------------------------------------------------- geo.test.js + geofire-common bounds

    @Test
    fun `geohash and donor key`() {
        assertEquals("wh2kc1e70w", Geo.hash(24.75, 90.4))
        assertNull(Geo.hash(null, 90.0))
        assertEquals("O+|wh2kc1e70w", Geo.donorKey("O+", "wh2kc1e70w"))
        assertNull(Geo.donorKey("X+", "wh2kc1e70w"))
        assertNull(Geo.donorKey("A+", ""))
    }

    @Test
    fun `query bounds are the geofire-common ones`() {
        // expected values from geofire-common 6.0.0 geohashQueryBounds (node)
        assertEquals(listOf("wh2h" to "wh2n", "wh2n" to "wh2s", "wh24" to "wh28"), GeoBounds.queryBounds(24.75, 90.4, 25000.0))
        assertEquals(listOf("wh228" to "wh22h", "wh220" to "wh228"), GeoBounds.queryBounds(23.999, 90.42, 5000.0))
        assertEquals(listOf("wh20" to "wh2h", "tur0" to "turh", "wh0h" to "wh0~", "tuph" to "tup~"), GeoBounds.queryBounds(24.0, 90.43, 50000.0))
        assertEquals(listOf("wh0r" to "wh0s", "wh0p" to "wh0q", "wh0q" to "wh0r", "wh0n" to "wh0p"), GeoBounds.queryBounds(23.81, 90.41, 10000.0))
        assertEquals(listOf("wh228" to "wh22b", "wh222" to "wh224"), GeoBounds.queryBounds(24.0, 90.4, 4000.0))
    }

    @Test
    fun `share helpers - escaping, encodeURIComponent, QR svg`() {
        assertEquals("&lt;a href=&quot;x&quot;&gt;&amp;&#39;", ShareController.esc("<a href=\"x\">&'"))
        assertEquals("https%3A%2F%2Fa.b%2Fc-d.apk", ShareController.encodeURIComponent("https://a.b/c-d.apk"))
        val svg = ShareController.qrSvgText("https://blood-server-ashy.vercel.app/rx/ABCDEFGH23")
        assertTrue(svg.startsWith("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 "), svg)
        assertTrue(svg.contains("<path stroke=\"#000000\" d=\"M1 1.5h7"), svg)
    }
}
