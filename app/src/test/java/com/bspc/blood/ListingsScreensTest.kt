package com.bspc.blood

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.google.firebase.FirebaseApp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * সেবার পাঁচ তালিকা আর বিস্তারিত — একই ছাঁচ (Listings.kt), ৩৬০dp ফোনে, বাংলা আর ইংরেজি। ছবি app/build/screens/listing-*.png।
 * পাতার ওপরে অ্যাপের বার (৫৬dp) আর নিচে নেভিগেশন (৮০dp)-এর জায়গা রেখে: প্রথম পর্দায় অন্তত ৩টা কার্ড, প্রথম চিপ কাটা নয়।
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class ListingsScreensTest {
    @get:Rule
    val rule = createComposeRule()

    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        if (FirebaseApp.getApps(ctx).isEmpty()) runCatching { FirebaseApp.initializeApp(ctx) }
        Areas.ensure(ctx)
        AppPrefs.lang.value = Lang.BN
        AppPrefs.theme.value = ThemeMode.LIGHT
        LoginGate.uid.value = "demo"
        LoginGate.profileReady.value = true
        Practice.testKeys = mapOf(Practice.SYSTEMS to true, Practice.PHYSIO to true, Practice.PSYCH to true)
        OnlineGate.testKeys = mapOf(OnlineGate.CHAMBER to true, OnlineGate.VIDEO to true)
    }

    @After
    fun tearDown() {
        Practice.testKeys = null
        OnlineGate.testKeys = null
        AppPrefs.lang.value = Lang.BN
    }

    // ---------------- নমুনা ----------------

    private val ambulances get() = listOf(
        Ambulance("a1", "জননী অ্যাম্বুলেন্স সার্ভিস", "01700000011", listOf("ac", "icu"), "ময়মনসিংহ", null, "চরপাড়া মোড়", "শহরে ৮০০", true, 4.6, 23, availableAt = now - 40 * 60_000L, km = 0.0),
        Ambulance("a2", "মমতা অ্যাম্বুলেন্স", "01700000012", listOf("general"), "ময়মনসিংহ", null, "ত্রিশাল বাসস্ট্যান্ড", "আলোচনা সাপেক্ষে", true, 4.1, 9, availableAt = now - 3 * 3_600_000L, km = 19.0),
        Ambulance("a3", "সেবা ফ্রিজার ভ্যান", "01700000013", listOf("freezer"), "ময়মনসিংহ", null, "গাঙ্গিনারপাড়", "", false, 0.0, 0, availableAt = now - 20 * 3_600_000L, km = 4.0),
        Ambulance("a4", "আল-আমিন অ্যাম্বুলেন্স", "01700000014", listOf("ac"), "ময়মনসিংহ", null, "মাসকান্দা", "", true, 4.8, 41, km = 6.0),
    )

    private val pharmacies get() = listOf(
        Pharmacy("p1", "ডেমো লাইফ ফার্মেসি", "01700000021", "শিববাড়ি মোড়, হাসপাতাল রোড", "গাজীপুর", null, "", "", true, true, true, "", 10, 4.5, 12, km = 1.2,
            extras = ListingExtras(ratingAvg = 4.5, ratingCount = 12, openNow = true, delivery = true, distanceKm = 1.2)),
        Pharmacy("p2", "লাজ ফার্মা — জয়দেবপুর চৌরাস্তা শাখা (সরকারি হাসপাতালের বিপরীতে)", "01700000022", "চৌরাস্তা", "গাজীপুর", null, "09:00", "23:00", false, true, false, "", 0, 4.8, 31, km = 2.6),
        Pharmacy("p3", "নিউ মা মেডিকেল হল", "01700000023", "টঙ্গী বাজার", "গাজীপুর", null, "08:00", "22:00", false, false, true, "", 5, 0.0, 0, km = 5.4),
        Pharmacy("p4", "সেবা ড্রাগ হাউস", "01700000024", "বোর্ডবাজার", "গাজীপুর", null, "09:00", "21:00", false, true, false, "", 0, 3.9, 4, km = 8.0),
    )

    private val banks get() = listOf(
        BloodBank("b1", "ময়মনসিংহ মেডিকেল কলেজ হাসপাতাল ব্লাড ব্যাংক", "govt", "01700000031", "ময়মনসিংহ", null, "চরপাড়া", true, "", "", true, listOf("whole_blood", "components"),
            extras = ListingExtras(openNow = true, is24h = true, distanceKm = 1.0, distanceApprox = true,
                stock = mapOf("A+" to "have", "A-" to "none", "B+" to "low", "B-" to "none", "O+" to "have", "O-" to "low", "AB+" to "have", "AB-" to "none"), stockUpdatedAt = now - 2 * 3_600_000L)),
        BloodBank("b2", "সন্ধানী, ময়মনসিংহ", "org", "01700000032", "ময়মনসিংহ", null, "মেডিকেল কলেজ ক্যাম্পাস", false, "09:00", "17:00", false, listOf("whole_blood", "donate_here"),
            extras = ListingExtras(openNow = false, distanceKm = 1.4, distanceApprox = true, stock = mapOf("A+" to "have", "B+" to "have", "O+" to "low"), stockUpdatedAt = now - 60 * 3_600_000L)),
        BloodBank("b3", "লাইফ কেয়ার ব্লাড ব্যাংক", "private", "01700000033", "ময়মনসিংহ", null, "নতুন বাজার", false, "08:00", "20:00", true, listOf("whole_blood")),
    )

    private val labs get() = listOf(
        Lab("l1", "ডেমো সিটি ডায়াগনস্টিক সেন্টার", "01700000041", "শিববাড়ি", "গাজীপুর", null, null, true, "সকাল ৮টা – রাত ১০টা",
            prices = PriceSummary(8, 100, 10, 200), extras = ListingExtras(openNow = true, distanceKm = 1.5)),
        Lab("l2", "পপুলার ডায়াগনস্টিক সেন্টার", "01700000042", "চৌরাস্তা", "গাজীপুর", null, null, false, "২৪ ঘণ্টা", extras = ListingExtras(openNow = true, distanceKm = 3.1, distanceApprox = true)),
        Lab("l3", "ইবনে সিনা ডায়াগনস্টিক", "01700000043", "টঙ্গী", "গাজীপুর", null, null, true, "সকাল ৭টা – রাত ৯টা", extras = ListingExtras(openNow = false, distanceKm = 7.0)),
    )

    private val all = listOf(6, 0, 1, 2, 3, 4, 5)
    private val ch1 = Chamber(
        "dghs_1", "Mymensingh Medical College Hospital", "ময়মনসিংহ মেডিকেল কলেজ হাসপাতাল", "ময়মনসিংহ", null, null, "রুম ২০৪", "", "৫০০ টাকা", true, "01700000202",
    ).withSlots(listOf(ChamberTime(all, "00:00", "23:59")))
    private val ch2 = Chamber(
        "dghs_2", "Popular Diagnostic", "পপুলার ডায়াগনস্টিক সেন্টার", "ময়মনসিংহ", null, null, "", "", "৮০০", true, "01700000203",
    ).withSlots(listOf(ChamberTime(listOf(5), "14:00", "18:00")))
    private val doctors get() = listOf(
        Provider("d1", "doctor", "ডা. ফারহানা ইয়াসমিন", listOf("gynecology"), "এমবিবিএস, এফসিপিএস", "female", true, true, listOf(ch1, ch2), null, 4.6, 31),
        Provider("d2", "doctor", "ডা. মাহমুদুল হাসান চৌধুরী (সহযোগী অধ্যাপক, মেডিসিন)", listOf("medicine", "cardiology"), "এমবিবিএস, এমডি", "male", true, true, listOf(ch2), null, 4.2, 8),
        Provider("d3", "doctor", "ডেমো হোমিও চিকিৎসক", listOf("alt_general"), "DHMS", "male", true, false, listOf(ch1.copy(fee = "300", serialPhone = "01700000204")), null, 0.0, 0, system = "homeopathy"),
        Provider("d4", "doctor", "ডা. নাসরিন আক্তার", listOf("pediatrics"), "এমবিবিএস", "female", true, true, listOf(ch1.copy(fee = "", noFee = true)), null, 4.9, 54,
            extras = ListingExtras(nextSlot = NextSlot("", "কাল", true, 0))),
    )

    // ---------------- আঁকা ----------------

    /** অ্যাপের ওপরের বার (৫৬dp) আর নিচের নেভিগেশন (৮০dp) — মাঝখানে "সেবা"-র পাতা */
    private fun screen(type: ListingType, body: @Composable () -> Unit) {
        rule.setContent {
            GetBloodTheme {
                Surface {
                    Column(Modifier.fillMaxSize()) {
                        Spacer(Modifier.fillMaxWidth().height(56.dp).background(MaterialTheme.colorScheme.surfaceContainer))
                        Box(Modifier.weight(1f).testTag("page")) {
                            Column {
                                ListingTypeTabs(SERVICE_TYPES, type) {}
                                body()
                            }
                        }
                        Spacer(Modifier.fillMaxWidth().height(80.dp).background(MaterialTheme.colorScheme.surfaceContainer))
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun list(type: ListingType, cards: List<ListingCardData>, chips: List<ListingChip> = emptyList(), loading: Boolean = false, header: LazyListScope.() -> Unit = {}, sort: ListingSort? = ListingSort.NEAR) {
        screen(type) {
            ListingList(
                controls = {
                    ListingControls(type, "", {}, areaText("ময়মনসিংহ", null), {}, sort, listOf(ListingSort.NEAR, ListingSort.RATING, ListingSort.OPEN), {}, chips, onClear = null)
                },
                loading = loading,
                items = cards,
                empty = { ListingEmpty(ListingText.emptyLine(type), ListingText.changeArea(), {}, listingIcon(type)) },
                header = header,
            )
        }
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        captureScreenRoboImage("build/screens/listing-$name.png")
    }

    /** প্রথম পর্দায় (পাতার নিচ পর্যন্ত) কয়টা কার্ড দেখা যায় (কিছুটা হলেও) */
    private fun cardsAboveFold(): Int {
        val page = rule.onNodeWithTag("page").fetchSemanticsNode().boundsInRoot
        return rule.onAllNodesWithTag("listing_card").fetchSemanticsNodes().count { it.boundsInRoot.top < page.bottom - 24 }
    }

    /** প্রথম চিপ (এলাকা) বাঁয়ে কাটা নয় — অন্তত ১৬dp ফাঁক */
    private fun firstChipNotCut() {
        val left = rule.onNodeWithTag("listing_area").fetchSemanticsNode().boundsInRoot.left
        val density = ApplicationProvider.getApplicationContext<android.content.Context>().resources.displayMetrics.density
        assertTrue("area chip left = $left", left >= 15 * density)
    }

    private fun lang(en: Boolean) { AppPrefs.lang.value = if (en) Lang.EN else Lang.BN }
    private fun sfx(en: Boolean) = if (en) "en" else "bn"

    // ---------------- পাঁচ তালিকা × দুই ভাষা ----------------

    private fun ambulanceList(en: Boolean) {
        lang(en)
        list(ListingType.AMBULANCE, ambulances.map { ambulanceCard(it, {}, {}, {}) }, header = {
            item { Column(Modifier.padding(horizontal = LISTING_GUTTER)) { AmbulanceCallBar(ambulances[0], null, {}, {}) } }
            item { Column(Modifier.padding(horizontal = LISTING_GUTTER)) { Emergency999Line(ambulances[0].name + " · ১ কিমির কম") } }
        })
        shot("ambulance-list-${sfx(en)}")
        firstChipNotCut()
        // ৯৯৯ একবারই
        assertEquals(1, rule.onAllNodesWithText(num("999")).fetchSemanticsNodes().size)
        assertTrue("cards above the fold: ${cardsAboveFold()}", cardsAboveFold() >= 2)
        // জরুরি আগে: "অ্যাম্বুলেন্স ডাকুন" ওপরে, ৯৯৯ একবারই
        assertEquals(1, rule.onAllNodesWithText(if (en) "Call an Ambulance" else "অ্যাম্বুলেন্স ডাকুন").fetchSemanticsNodes().size)
    }

    @Test fun ambulanceListBn() = ambulanceList(false)
    @Test fun ambulanceListEn() = ambulanceList(true)

    private fun doctorList(en: Boolean) {
        lang(en)
        list(
            ListingType.DOCTOR, doctors.map { providerCard(it, CareFilter(), {}, {}) },
            chips = listOf(ListingChip(ListingText.filter(), false, {}, icon = R.drawable.ic_tune)) +
                Practice.SYSTEM_LIST.map { ListingChip(Practice.systemLabel(it), false, {}) } +
                listOf(ListingChip(tr("Sitting today", "আজ বসছেন"), true, {})),
        )
        shot("doctor-list-${sfx(en)}")
        firstChipNotCut()
        assertTrue("cards above the fold: ${cardsAboveFold()}", cardsAboveFold() >= 3)
        // বাংলা অঙ্ক: "ফি ৳৫০০", কোথাও "300" নয়
        if (!en) {
            assertTrue(rule.onAllNodesWithText("ফি\u00A0৳৫০০", substring = true).fetchSemanticsNodes().isNotEmpty())
            assertTrue(rule.onAllNodesWithText("300", substring = true).fetchSemanticsNodes().isEmpty())
        }
    }

    @Test fun doctorListBn() = doctorList(false)
    @Test fun doctorListEn() = doctorList(true)

    private fun pharmacyList(en: Boolean) {
        lang(en)
        list(
            ListingType.PHARMACY, pharmacies.map { pharmacyCard(it, {}, {}, {}, {}) },
            chips = listOf(
                ListingChip(tr("At the shop", "দোকানে গিয়ে"), true, {}), ListingChip(tr("Order online", "অনলাইন অর্ডার"), false, {}),
                ListingChip(tr("Open now", "এখন খোলা"), false, {}), ListingChip(ListingText.hours24(), false, {}),
            ),
        )
        shot("pharmacy-list-${sfx(en)}")
        firstChipNotCut()
        assertTrue("cards above the fold: ${cardsAboveFold()}", cardsAboveFold() >= 3)
    }

    @Test fun pharmacyListBn() = pharmacyList(false)
    @Test fun pharmacyListEn() = pharmacyList(true)

    private fun bloodBankList(en: Boolean) {
        lang(en)
        list(ListingType.BLOODBANK, banks.map { bloodBankCard(it, {}, {}, {}) }, chips = listOf(ListingChip(tr("Open now", "এখন খোলা"), false, {}), ListingChip(ListingText.hours24(), false, {})))
        shot("bloodbank-list-${sfx(en)}")
        firstChipNotCut()
        assertTrue("cards above the fold: ${cardsAboveFold()}", cardsAboveFold() >= 3)
        rule.onNodeWithText(if (en) "Old Info" else "পুরনো তথ্য", substring = true).assertExists()
    }

    @Test fun bloodBankListBn() = bloodBankList(false)
    @Test fun bloodBankListEn() = bloodBankList(true)

    private fun labList(en: Boolean) {
        lang(en)
        list(ListingType.LAB, labs.map { labCard(it, {}, {}, null, null) }, chips = listOf(ListingChip(tr("Open now", "এখন খোলা"), false, {}), ListingChip(ListingText.homeSample(), true, {})))
        shot("lab-list-${sfx(en)}")
        firstChipNotCut()
        assertTrue("cards above the fold: ${cardsAboveFold()}", cardsAboveFold() >= 3)
    }

    @Test fun labListBn() = labList(false)
    @Test fun labListEn() = labList(true)

    // ---------------- আসছে আর খালি ----------------

    @Test
    fun loadingSkeletonBn() {
        list(ListingType.PHARMACY, emptyList(), loading = true)
        assertTrue(rule.onAllNodesWithTag("listing_skeleton").fetchSemanticsNodes().size >= 3)
        shot("pharmacy-loading-bn")
    }

    @Test
    fun emptyStateBn() {
        list(ListingType.LAB, emptyList())
        rule.onNodeWithText("এই এলাকায় এখনো কোনো ল্যাব নেই").assertExists()
        rule.onNodeWithText("এলাকা বদলান").assertExists()
        shot("lab-empty-bn")
    }

    @Test
    fun emptyStateEn() {
        lang(true)
        list(ListingType.AMBULANCE, emptyList())
        shot("ambulance-empty-en")
    }

    // ---------------- বিস্তারিত × দুই ভাষা ----------------

    private fun detail(name: String, en: Boolean, d: () -> ListingDetailData) {
        lang(en)
        rule.setContent { GetBloodTheme { Surface { ListingDetailBody(d(), {}) } } }
        rule.waitForIdle()
        // নিচের আটকানো বার
        rule.onNodeWithTag("listing_detail_bar").assertExists()
        shot("$name-detail-${sfx(en)}")
    }

    @Test fun ambulanceDetailBn() = detail("ambulance", false) { ambulanceDetail(ambulances[0], {}, {}, {}, {}) }
    @Test fun ambulanceDetailEn() = detail("ambulance", true) { ambulanceDetail(ambulances[0], {}, {}, {}, {}) }
    @Test fun pharmacyDetailBn() = detail("pharmacy", false) { pharmacyDetail(pharmacies[0], false, {}, {}, {}, {}) }
    @Test fun pharmacyDetailEn() = detail("pharmacy", true) { pharmacyDetail(pharmacies[0], false, {}, {}, {}, {}) }
    @Test fun bloodBankDetailBn() = detail("bloodbank", false) { bloodBankDetail(banks[0], {}, {}) }
    @Test fun bloodBankDetailEn() = detail("bloodbank", true) { bloodBankDetail(banks[0], {}, {}) }
    @Test fun labDetailBn() = detail("lab", false) { labDetail(labs[0], Result.success(LabPrices(listOf(PriceItem("cbc", "CBC", 400, "একই দিন"), PriceItem("rbs", "RBS", 150, "")), 10, 200)), {}, null, null) }
    @Test fun labDetailEn() = detail("lab", true) { labDetail(labs[0], Result.success(null), {}, null, null) }

    private fun doctorDetail(en: Boolean) {
        lang(en)
        rule.setContent { GetBloodTheme { Surface { ProviderDetailDialog(doctors[0], Profile(name = "ডেমো রোগী"), {}, {}) } } }
        rule.waitForIdle()
        rule.onNodeWithTag("listing_detail_bar").assertExists()
        rule.onNodeWithText(if (en) "Book Serial" else "সিরিয়াল নিন").assertExists()
        shot("doctor-detail-${sfx(en)}")
    }

    @Test fun doctorDetailBn() = doctorDetail(false)
    @Test fun doctorDetailEn() = doctorDetail(true)

    /** কার্ড চাপলে বিস্তারিত (একই তথ্য), ⋮-এ একই কাজ */
    @Test
    fun bloodBankCardOpensDetail() {
        rule.setContent { GetBloodTheme { Surface { Column(Modifier.padding(16.dp)) { BloodBankCard(banks[0], {}, {}) } } } }
        rule.onNodeWithText(banks[0].name).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("stock_grid").assertExists()
        shot("bloodbank-card-to-detail-bn")
    }
}
