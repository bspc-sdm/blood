package com.bspc.blood

import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * সেবার পাঁচ তালিকার হিসাব (Listings.kt, ListingCards.kt, ProviderSide.kt): নতুন ঘর পড়া (না এলে null), বাংলা অঙ্ক,
 * মূল লাইন, ব্যাজ, সাজানো, খোঁজা, স্টকের "পুরনো তথ্য", আর সেবাদাতার নতুন/চলছে/শেষ।
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ListingsTest {
    @Before
    fun setUp() {
        Areas.ensure(ApplicationProvider.getApplicationContext())
        AppPrefs.lang.value = Lang.BN
        Practice.testKeys = mapOf(Practice.SYSTEMS to true, Practice.PHYSIO to false, Practice.PSYCH to false)
        OnlineGate.testKeys = mapOf(OnlineGate.CHAMBER to false)
    }

    @After
    fun tearDown() {
        Practice.testKeys = null
        OnlineGate.testKeys = null
        AppPrefs.lang.value = Lang.BN
    }

    private val now = System.currentTimeMillis()

    // ---------------- পড়া ----------------

    @Test
    fun missingFieldsAreNull() {
        val x = ListingExtras.parse(JSONObject().put("id", "p1").put("name", "দোকান"))
        assertNull(x.openNow)
        assertNull(x.distanceKm)
        assertNull(x.ratingAvg)
        assertNull(x.nextSlot)
        assertNull(x.feeAmount)
        assertNull(x.delivery)
        assertNull(x.homeCollection)
        assertTrue(x.stock.isEmpty())
        assertFalse(x.stockStale(now))
        assertEquals(ListingExtras(), ListingExtras.parse(null))
        // JSON-এর null-ও null
        val y = ListingExtras.parse(JSONObject().put("openNow", JSONObject.NULL).put("distanceKm", JSONObject.NULL).put("ratingSummary", JSONObject.NULL))
        assertNull(y.openNow)
        assertNull(y.distanceKm)
        assertNull(y.ratingAvg)
    }

    @Test
    fun ratingSummaryWinsOverOldRating() {
        val x = ListingExtras.parse(JSONObject().put("rating", 3.0).put("ratingCount", 2).put("ratingSummary", JSONObject().put("avg", 4.6).put("count", 31)))
        assertEquals(4.6, x.ratingAvg!!, 0.001)
        assertEquals(31, x.ratingCount)
        // ratingSummary নেই: আগের rating + ratingCount
        val y = ListingExtras.parse(JSONObject().put("rating", 4.2).put("ratingCount", 5))
        assertEquals(4.2, y.ratingAvg!!, 0.001)
        assertEquals(5, y.ratingCount)
        // কেউ রেটিং দেননি
        assertNull(ListingExtras.parse(JSONObject().put("rating", 0).put("ratingCount", 0)).ratingAvg)
        assertEquals("★৪.৬ (৩১)", Listing.rating(4.6, 31))
        assertNull(Listing.rating(4.6, 0))
    }

    @Test
    fun backendFinalNames() {
        val o = JSONObject()
            .put("openNow", true).put("opensAt", "09:00").put("closesAt", "22:00").put("is24h", false)
            .put("distanceKm", 2.6).put("distanceApprox", true)
            .put("feeAmount", 500).put("fee", "৫০০ টাকা")
            .put("nextSlot", JSONObject().put("date", 1_792_886_400_000L).put("label", "কাল").put("available", true).put("chamberIndex", 1))
            .put("homeCollection", true).put("delivery", false)
        val x = ListingExtras.parse(o)
        assertEquals(true, x.openNow)
        assertEquals("09:00", x.opensAt)
        assertEquals(2.6, x.distanceKm!!, 0.001)
        assertTrue(x.distanceApprox)
        assertEquals(500L, x.feeAmount)
        assertEquals("কাল", x.nextSlot!!.label)
        assertEquals(1, x.nextSlot!!.chamberIndex)
        assertEquals(true, x.homeCollection)
        assertEquals(false, x.delivery)
    }

    @Test
    fun stockAndStaleness() {
        val g = JSONObject().put("A+", "have").put("B+", "low").put("O-", "none").put("AB+", JSONObject.NULL).put("X", "have").put("A-", "lots")
        val x = ListingExtras.parse(JSONObject().put("stock", g).put("stockUpdatedAt", now - 2 * 3_600_000L))
        assertEquals(mapOf("A+" to "have", "B+" to "low", "O-" to "none"), x.stock)
        assertFalse(x.stockStale(now))
        assertEquals("হালনাগাদ: ২ ঘণ্টা আগে", Listing.stockNote(x.stockUpdatedAt, false))
        // ৪৮ ঘণ্টার বেশি পুরনো → "পুরনো তথ্য" (সার্ভারের stockStale না এলে হিসাব)
        val old = ListingExtras.parse(JSONObject().put("stock", g).put("stockUpdatedAt", now - 49 * 3_600_000L))
        assertTrue(old.stockStale(now))
        assertEquals("পুরনো তথ্য", Listing.stockNote(old.stockUpdatedAt, true))
        // সার্ভারের stockStale আগে
        assertFalse(ListingExtras.parse(JSONObject().put("stockUpdatedAt", now - 49 * 3_600_000L).put("stockStale", false)).stockStale(now))
        // কখনো দেননি: লেখা নেই
        assertNull(Listing.stockNote(0L, false))
    }

    @Test
    fun timesInAnyForm() {
        assertEquals(1_792_886_400_000L, ListingExtras.time(1_792_886_400_000L))
        assertEquals(1_792_886_400_000L, ListingExtras.time(1_792_886_400L))
        assertEquals(1_792_886_400_000L, ListingExtras.time("2026-10-25T00:00:00Z"))
        assertEquals(0L, ListingExtras.time(null))
        assertEquals(0L, ListingExtras.time("কাল"))
    }

    // ---------------- লেখা আর বাংলা অঙ্ক ----------------

    @Test
    fun banglaDigitsEverywhere() {
        assertEquals("ফি ৳৫০০", Listing.feeAmount(500))
        assertEquals("ফি ৳১,২০০", Listing.feeAmount(1200))
        assertEquals("ফি নেই", Listing.feeAmount(0))
        assertNull(Listing.feeAmount(null))
        assertEquals("ফি ৳৩০০", Listing.fee("300"))
        assertEquals("ফি ৳৫০০", Listing.fee("৫০০ টাকা"))
        assertEquals("ফি ৳১,২০০", Listing.fee("1,200 Tk"))
        // আগের "ফি: 300" → "ফি: ৩০০" (চেম্বারের লেখা)
        val c = Chamber(null, "X", "এক্স", "গাজীপুর", null, null, "", "", "300", true, "")
        assertEquals("ফি: ৩০০", c.feeText)
        assertEquals("প্রায় ৩ কিমি", Listing.km(2.6, approx = true))
        assertEquals("৩ কিমি", Listing.km(2.6))
        assertEquals("১ কিমির কম", Listing.km(0.4))
        assertNull(Listing.km(null))
        assertEquals("খোলা · ২ কিমি · ডেলিভারি আছে", Listing.keyLine("খোলা", null, "", "২ কিমি", " ডেলিভারি আছে "))
    }

    @Test
    fun englishCopy() {
        AppPrefs.lang.value = Lang.EN
        assertEquals("Fee ৳500", Listing.feeAmount(500))
        assertEquals("About 3 km", Listing.km(2.6, approx = true))
        assertEquals("★4.6 (31)", Listing.rating(4.6, 31))
        assertEquals("Next serial: Tomorrow", Listing.slotBadge(NextSlot("", "কাল", true)))
    }

    @Test
    fun slotBadges() {
        assertEquals("আজ সিরিয়াল আছে", Listing.slotBadge(NextSlot("", "আজ", true)))
        assertEquals("পরের সিরিয়াল: কাল", Listing.slotBadge(NextSlot("", "কাল", true)))
        assertEquals("পরের সিরিয়াল: ২৫ অক্টোবর", Listing.slotBadge(NextSlot("", "২৫ অক্টোবর", true)))
        // আজ ভরা (available false) → পরের দিন হিসেবে
        assertEquals("পরের সিরিয়াল: আজ", Listing.slotBadge(NextSlot("", "আজ", false)))
        assertNull(Listing.slotBadge(null))
    }

    // ---------------- সাজানো আর খোঁজা ----------------

    private data class Row(val id: String, val km: Double?, val r: Pair<Double, Int>?, val open: Boolean?)

    @Test
    fun sorting() {
        val rows = listOf(Row("a", null, 4.0 to 3, false), Row("b", 5.0, 4.8 to 10, null), Row("c", 1.0, null, true), Row("d", 3.0, 4.8 to 20, true))
        fun ids(s: ListingSort?) = Listing.sorted(rows, s, { it.km }, { it.r }, { it.open }).joinToString("") { it.id }
        assertEquals("abcd", ids(null))
        assertEquals("cdba", ids(ListingSort.NEAR))
        assertEquals("dbac", ids(ListingSort.RATING))
        assertEquals("cdba", ids(ListingSort.OPEN))
        assertEquals(ListingSort.NEAR, Listing.defaultSort(true))
        assertNull(Listing.defaultSort(false))
    }

    @Test
    fun searching() {
        assertTrue(Listing.matches("", "যেকোনো"))
        assertTrue(Listing.matches("লাইফ", "ডেমো লাইফ ফার্মেসি"))
        assertTrue(Listing.matches("popular", "Popular Diagnostic"))
        assertTrue(Listing.matches("১২", "Road 12"))
        assertFalse(Listing.matches("সন্ধানী", "ডেমো লাইফ ফার্মেসি", null))
    }

    // ---------------- কার্ড ----------------

    private val pharmacy = Pharmacy(
        "ph1", "ডেমো লাইফ ফার্মেসি", "01700000001", "শিববাড়ি মোড়", "গাজীপুর", null, "09:00", "22:00", true, true, true, "", 10, 4.5, 12, km = 1.6,
    )

    @Test
    fun pharmacyCardKeyLine() {
        var sent = false
        val c = pharmacyCard(pharmacy, {}, { sent = true }, {}, null)
        assertEquals("খোলা · ২ কিমি · ডেলিভারি আছে", c.keyLine)
        assertEquals("★৪.৫ (১২)", c.ratingText)
        assertEquals(listOf("২৪ ঘণ্টা", "১০% ছাড়"), c.badges.map { it.text })
        assertEquals("প্রেসক্রিপশন পাঠান", c.primary!!.label)
        c.primary!!.onClick()
        assertTrue(sent)
        // কার্ডে ফোন নম্বর লেখা নেই — শুধু যোগাযোগের আইকনে
        assertFalse(c.keyLine.contains("01700000001"))
        assertEquals("01700000001", c.contact!!.phone)
        // বন্ধ দোকানে "প্রেসক্রিপশন পাঠান" নেই; নতুন ঘর আগের ঘরের আগে
        val closed = pharmacy.copy(extras = ListingExtras(openNow = false, delivery = false, distanceKm = 0.4))
        val cc = pharmacyCard(closed, {}, {}, {}, null)
        assertNull(cc.primary)
        assertEquals("বন্ধ · ১ কিমির কম · ডেলিভারি নেই", cc.keyLine)
    }

    @Test
    fun ambulanceCardKeyLine() {
        val a = Ambulance("a1", "জননী অ্যাম্বুলেন্স", "01700000002", listOf("ac", "icu"), "ময়মনসিংহ", null, "চরপাড়া", "", true, 4.6, 23, km = 0.0)
        val c = ambulanceCard(a, {}, {}, null)
        assertEquals("এসি, আইসিইউ · ১ কিমির কম · ★৪.৬ (২৩)", c.keyLine)
        assertEquals(listOf("খালি আছে"), c.badges.map { it.text })
        assertEquals("ফোন করুন", c.primary!!.label)
        // ফোনই মূল কাজ — পাশে আবার ফোনের আইকন নয়
        assertNull(c.contact)
        assertEquals("ট্রিপে আছে", ambulanceBadge(a.copy(busySince = now)).text)
        assertEquals("ফোনে জেনে নিন", ambulanceBadge(a.copy(available = false)).text)
        // বিস্তারিত: "অ্যাম্বুলেন্স ডাকুন" + পাশে ফোনের আইকন; ট্রিপে থাকলে শুধু "ফোন করুন"
        val d = ambulanceDetail(a, {}, {}, null, null)
        assertEquals("অ্যাম্বুলেন্স ডাকুন", d.primary!!.label)
        assertNotNull(d.contact)
        val busy = ambulanceDetail(a.copy(busySince = now), {}, {}, null, null)
        assertEquals("ফোন করুন", busy.primary!!.label)
        assertNull(busy.contact)
    }

    @Test
    fun bloodBankCardStock() {
        val b = BloodBank(
            "b1", "সন্ধানী", "org", "01700000003", "ময়মনসিংহ", null, "মেডিকেল কলেজ", false, "09:00", "17:00", true, listOf("whole_blood"),
            extras = ListingExtras(stock = mapOf("A+" to "have", "O-" to "none"), stockUpdatedAt = now - 50 * 3_600_000L, distanceKm = 3.2, distanceApprox = true),
        )
        val c = bloodBankCard(b, {}, {}, null)
        assertEquals("প্রায় ৩ কিমি · খোলা", c.keyLine)
        assertEquals(mapOf("A+" to "have", "O-" to "none"), c.stock)
        assertEquals("পুরনো তথ্য", c.stockNote!!.text)
        assertEquals(BadgeTone.WARN, c.stockNote!!.tone)
        assertEquals("ফোন করুন", c.primary!!.label)
        // রেটিং নেই
        assertFalse(bloodBankDetail(b, {}, null).showRatings)
        // কখনো স্টক দেননি: খালি (কার্ডে "স্টকের খবর এখনো দেওয়া হয়নি")
        assertTrue(bloodBankCard(b.copy(extras = ListingExtras()), {}, {}, null).stock!!.isEmpty())
    }

    @Test
    fun labCardKeyLine() {
        val l = Lab("l1", "পপুলার ডায়াগনস্টিক", "01700000004", "চরপাড়া", "ময়মনসিংহ", null, null, true, "সকাল ৮টা – রাত ১০টা")
        val c = labCard(l, {}, {}, null, null)
        // খোলা/বন্ধ জানা নেই (পুরোনো সার্ভার) — বাদ
        assertEquals("বাসা থেকে নমুনা", c.keyLine)
        assertEquals("পরীক্ষা পাঠান", c.primary!!.label)
        val c2 = labCard(l.copy(homeCollection = false, extras = ListingExtras(openNow = true, distanceKm = 4.0)), {}, {}, null, null)
        assertEquals("খোলা · ৪ কিমি", c2.keyLine)
    }

    private val chamber = Chamber(
        "dghs_1", "Kaliakair UHC", "কালিয়াকৈর উপজেলা স্বাস্থ্য কমপ্লেক্স", "গাজীপুর", 318, null, "", "", "৪০০ টাকা", true, "01700000202",
    ).withSlots(listOf(ChamberTime(listOf(0, 1, 2, 3, 4, 5, 6), "00:00", "23:59")))
    private val doctor = Provider(
        "d1", "doctor", "ডা. ডেমো হাসান", listOf("medicine"), "MBBS", "male", true, true, listOf(chamber), null, 4.6, 12, system = "homeopathy",
    )

    @Test
    fun doctorCardKeyLine() {
        val c = providerCard(doctor, CareFilter(), {}, {})
        assertEquals(Specialties.label("medicine") + " · হোমিওপ্যাথি · ফি ৳৪০০", c.keyLine)
        assertEquals("সিরিয়াল নিন", c.primary!!.label)
        assertEquals("01700000202", c.contact!!.phone)
        // সার্ভারের ঘর আগে: feeAmount, specialtyLabel, nextSlot
        val withNew = doctor.copy(extras = ListingExtras(feeAmount = 700, specialtyLabel = "মেডিসিন বিশেষজ্ঞ", nextSlot = NextSlot("", "আজ", true, 0)))
        val c2 = providerCard(withNew, CareFilter(), {}, {})
        assertEquals("মেডিসিন বিশেষজ্ঞ · হোমিওপ্যাথি · ফি ৳৭০০", c2.keyLine)
        assertEquals("আজ সিরিয়াল আছে", c2.badges.first().text)
        // অ্যাপে অ্যাপয়েন্টমেন্ট নেন না: "সিরিয়াল নিন" নেই
        assertNull(providerCard(doctor.copy(appointments = false), CareFilter(), {}, {}).primary)
    }

    @Test
    fun doctorNextSlotFallback() {
        // প্রতিদিন বসেন: আজ (যদি আজকের সময় শেষ না হয়) বা কাল
        val s = providerNextSlot(doctor)
        assertNotNull(s)
        assertTrue(s!!.label == "আজ" || s.label == "কাল")
        // আজ বন্ধ দেওয়া: আজ নয়
        val closed = doctor.copy(chambers = listOf(chamber.copy(closedToday = true)))
        assertEquals("কাল", providerNextSlot(closed)!!.label)
        // অ্যাপে অ্যাপয়েন্টমেন্ট না নিলে: নেই
        assertNull(providerNextSlot(doctor.copy(appointments = false)))
    }

    // ---------------- সেবাদাতা ----------------

    @Test
    fun workQueueSegments() {
        assertEquals("new", WorkQueue.of("new"))
        assertEquals("active", WorkQueue.of("active"))
        assertEquals("done", WorkQueue.of("reported"))
        assertEquals("done", WorkQueue.of("closed"))
        assertEquals(mapOf("new" to 2, "active" to 1, "done" to 7), WorkQueue.counts(mapOf("new" to 2, "active" to 1, "reported" to 3, "closed" to 4)))
        assertEquals(listOf("নতুন", "চলছে", "শেষ"), WorkQueue.SEGS.map { WorkQueue.label(it) })
    }

    @Test
    fun unknownActionHidesSwitch() {
        assertTrue(ProviderSwitches.unknown(ServerError("অজানা অনুরোধ। অ্যাপ আপডেট করুন।", null)))
        assertTrue(ProviderSwitches.unknown(ServerError("Unknown request. Please update the app.", null)))
        assertFalse(ProviderSwitches.unknown(ServerError("Too many changes, try later", "rate_limited")))
        assertFalse(ProviderSwitches.unknown(IllegalStateException("network")))
    }
}
