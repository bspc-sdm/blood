package com.bspc.blood

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.google.firebase.FirebaseApp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * "আমার কাজ"-এর কাজের তালিকা (mywork-review/CONTRACT.md §২): স্থির ট্যাব (ছোট ফোনেও লেখা কাটে না), সারিপ্রতি একটাই
 * মূল বাটন ("আরও"-তে বাকিটা), এক লাইনের খালি অবস্থা, ডাক্তারের এক "আজকের রোগী" সারি (সহকারীর লেখা নেই), কাউন্টারের
 * বিক্রি, অ্যাম্বুলেন্সের খালি/ট্রিপ একবারই। সুইচ বন্ধে (inv_chamber, inv_pharmacy) সংশ্লিষ্ট বাটন নেই।
 * ছবি app/build/screens/queues-*.png।
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class MyWorkQueuesScreensTest {
    @get:Rule
    val rule = createComposeRule()

    private val now = System.currentTimeMillis()

    private val allOn = Features.KEYS.associateWith { true }

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        if (FirebaseApp.getApps(ctx).isEmpty()) runCatching { FirebaseApp.initializeApp(ctx) }
        Areas.ensure(ctx)
        AppPrefs.lang.value = Lang.BN
        AppPrefs.theme.value = ThemeMode.LIGHT
        LoginGate.uid.value = "demo"
        LoginGate.profileReady.value = true
        Features.set(allOn, 1L, null)
    }

    @After
    fun tearDown() {
        Features.set(emptyMap(), 0L, null)
    }

    private fun show(content: @Composable () -> Unit) {
        rule.setContent {
            GetBloodTheme { Surface { Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() } } }
        }
        rule.waitForIdle()
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        captureScreenRoboImage("build/screens/queues-$name.png")
    }

    /** লেখা কাটা যায়নি (এক লাইনে বা দুই লাইনে পুরোটা) */
    private fun assertNotCut(text: String) {
        val nodes = rule.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("no node for $text", nodes.isNotEmpty())
        nodes.forEach { n ->
            val out = mutableListOf<TextLayoutResult>()
            n.config[SemanticsActions.GetTextLayoutResult].action?.invoke(out)
            val r = out.first()
            assertFalse("\"$text\" is cut", r.hasVisualOverflow)
            assertTrue("\"$text\" takes ${r.lineCount} lines", r.lineCount <= 2)
        }
    }

    private fun count(text: String): Int = rule.onAllNodesWithText(text).fetchSemanticsNodes().size

    // ---------------- ট্যাব ----------------

    @Test
    @Config(qualifiers = "w320dp-h640dp-xhdpi")
    fun chamberTabsFitOnASmallPhone() {
        show {
            FixedTabs(
                0,
                listOf(
                    PillTab(TodayText.tab(), R.drawable.ic_schedule, count = 3),
                    PillTab("বুকিং", R.drawable.ic_event_outline, count = 12),
                    PillTab("রোগী", R.drawable.ic_person_outline),
                    PillTab("সেটআপ", R.drawable.ic_settings_outline),
                ),
                {},
            )
            WorkSegTabs("new", mapOf("new" to 5, "active" to 2, "done" to 40), {})
        }
        listOf("আজ ৩", "বুকিং ১২", "রোগী", "সেটআপ", "নতুন ৫", "চলছে ২", "শেষ").forEach { assertNotCut(it) }
        // "শেষ"-এর পাশে সংখ্যা নয়
        assertEquals(0, count("৪০"))
        shot("chamber-tabs-320")
    }

    @Test
    fun pharmacyTabsAreThreeAndFit() {
        show {
            FixedTabs(
                0,
                listOf(
                    PillTab("অর্ডার", R.drawable.ic_medication, count = 3),
                    PillTab("বিক্রি", R.drawable.ic_payments),
                    PillTab("দোকান", R.drawable.ic_storefront),
                ),
                {},
            )
        }
        listOf("অর্ডার ৩", "বিক্রি", "দোকান").forEach { assertNotCut(it) }
        shot("pharmacy-tabs")
    }

    // ---------------- ডাক্তার: আজকের রোগী ----------------

    private val chamber = QueueChamber(
        "c1", "MMCH", "ময়মনসিংহ মেডিকেল কলেজ হাসপাতাল", 8,
        QueueState(started = true, current = 2, paused = false, finished = false, next = 3),
        listOf(
            QueueRow("a1", 1, "ডেমো রোগী সালমা খাতুন", "confirmed", "done", null),
            QueueRow("a2", 2, "ডেমো রোগী আব্দুল করিম", "confirmed", "now", null),
            QueueRow("a3", 3, "ডেমো রোগী রাশেদুল ইসলাম", "confirmed", "waiting", null),
            QueueRow("a4", 4, "ডেমো রোগী মরিয়ম নেসা", "confirmed", "waiting", LateInfo(20, "জ্যামে আটকে আছি", now, "asked")),
            QueueRow("a5", 5, "ডেমো রোগী নাসিমা বেগম", "confirmed", "waiting", null),
        ),
    )

    @Test
    fun todayQueueDoctorHasOnePrimaryPerRow() {
        show {
            Column(Modifier.padding(horizontal = 16.dp)) {
                TodayChamberCard(chamber, false, null, canWrite = true, writeLabel = TodayText.write("doctor"), invChamber = true, arrived = setOf("a3")) { _, _ -> }
            }
        }
        // যাঁকে দেখছেন: প্রেসক্রিপশন লিখুন; পরের জন: ডাকুন; দেরির অনুরোধ: ঠিক আছে — তিনটা সারিতে তিনটা মূল বাটন
        assertEquals(3, rule.onAllNodesWithTag("today_primary").fetchSemanticsNodes().size)
        assertEquals(1, count("প্রেসক্রিপশন লিখুন"))
        assertEquals(1, count("ডাকুন"))
        assertEquals(1, count("ঠিক আছে"))
        // বাকি কাজ "আরও"-তে — খোলার আগে দেখা যায় না
        assertEquals(0, count("পরে আসুন"))
        assertEquals(0, count("ফি নিন"))
        rule.onNodeWithText("অপেক্ষায় ৩ জন", substring = true).assertExists()
        shot("today-doctor")
        // মরিয়ম নেসার "আরও": পরে আসুন, এখনই ডাকুন, এসেছেন, ফি নিন, প্রেসক্রিপশন লিখুন, আগের প্রেসক্রিপশন
        rule.onAllNodesWithTag("more_actions")[3].performClick()
        rule.waitForIdle()
        assertEquals(1, count("পরে আসুন"))
        assertEquals(1, count("ফি নিন"))
        assertEquals(1, count("আগের প্রেসক্রিপশন"))
        shot("today-doctor-more")
    }

    @Test
    fun todayQueueAssistantWritesNoPrescriptionAndFeesOffHidesFee() {
        // সহকারী (লেখা নেই) আর inv_chamber বন্ধ: ফি/এসেছেন নেই
        Features.set(allOn + (Features.INV_CHAMBER to false), 2L, null)
        show {
            Column(Modifier.padding(horizontal = 16.dp)) {
                TodayChamberCard(chamber, false, null, canWrite = false, writeLabel = "", invChamber = false, arrived = emptySet()) { _, _ -> }
            }
        }
        assertEquals(0, count("প্রেসক্রিপশন লিখুন"))
        assertEquals(1, count("ডাকুন"))
        rule.onAllNodesWithTag("more_actions").fetchSemanticsNodes().forEachIndexed { i, _ ->
            rule.onAllNodesWithTag("more_actions")[i].performClick()
        }
        rule.waitForIdle()
        assertEquals(0, count("ফি নিন"))
        assertEquals(0, count("এসেছেন"))
        assertEquals(0, count("আগের প্রেসক্রিপশন"))
        shot("today-assistant-fees-off")
    }

    @Test
    fun todayQueueNotStartedShowsStartOnly() {
        val c = chamber.copy(queue = QueueState(started = false, current = 0, paused = false, finished = false, next = 1))
        show {
            Column(Modifier.padding(horizontal = 16.dp)) {
                TodayChamberCard(c, false, null, canWrite = true, writeLabel = TodayText.write("doctor"), invChamber = true, arrived = emptySet()) { _, _ -> }
            }
        }
        rule.onNodeWithTag("today_start").assertExists()
        assertEquals(0, count("ডাকুন"))
        shot("today-not-started")
    }

    @Test
    fun todayWaitingCount() {
        assertEquals(3, todayWaiting(listOf(chamber)))
    }

    // ---------------- ফার্মেসি ----------------

    private val orders = SegList(
        listOf(
            PharmOrder("o1", "DEMXRXB333", "ডেমো রোগী আব্দুল করিম", "", "sent", now - 36 * 60_000L, seg = "new"),
            PharmOrder(
                "o2", "DEMXRXC444", "ডেমো রোগী ফাতেমা বেগম", "সন্ধ্যার পরে দিলে ভালো", "sent", now - 50 * 60_000L,
                mode = "online", deliveryFee = "৩০ টাকা", address = "ডেমো বাসা ১২, রোড ৩, জয়দেবপুর", phone = "০১২০০০০০৩১২", seg = "new",
            ),
        ),
        mapOf("new" to 2, "active" to 1, "closed" to 14),
    )

    @Test
    fun pharmacyOrdersOnePrimaryAndNoNewTag() {
        show { PharmacyOrdersTab(orders, null, "new", {}, {}) }
        assertEquals(2, rule.onAllNodesWithTag("order_card").fetchSemanticsNodes().size)
        assertEquals(2, rule.onAllNodesWithTag("more_actions").fetchSemanticsNodes().size)
        // দোকানে এসেছেন: "খুলুন"; অনলাইন নতুন: "অর্ডার নিলাম"
        assertEquals(1, count("খুলুন"))
        assertEquals(1, count("অর্ডার নিলাম"))
        assertEquals(0, count("পারব না"))
        // "নতুন" ভাগের সারিতে আবার "নতুন — আপনার অপেক্ষায়" নয়
        assertEquals(0, rule.onAllNodesWithText("আপনার অপেক্ষায়", substring = true).fetchSemanticsNodes().size)
        // ফোন নম্বর ইংরেজি অঙ্কে
        rule.onNodeWithText("01200000312", substring = true).assertExists()
        shot("pharmacy-orders")
        rule.onAllNodesWithTag("more_actions").onFirst().performClick()
        rule.waitForIdle()
        assertEquals(1, count("পারব না"))
        shot("pharmacy-orders-more")
    }

    @Test
    fun pharmacyEmptySegIsOneLine() {
        show { PharmacyOrdersTab(SegList(emptyList(), emptyMap()), null, "new", {}, {}) }
        rule.onNodeWithTag("empty_line").assertExists()
        rule.onNodeWithText("এখন নতুন কোনো অর্ডার নেই").assertExists()
        shot("pharmacy-empty")
    }

    @Test
    fun counterSaleAddsAndTotals() {
        val items = listOf(
            ShopItemLite("i1", "Napa 500mg", "piece", 120.0, 1.5),
            ShopItemLite("i2", "Seclo 20", "piece", 3.0, 7.0),
            ShopItemLite("i3", "Ace Plus", "piece", null, null),
        )
        show { Column(Modifier.padding(horizontal = 16.dp)) { CounterSaleCard(items) } }
        rule.onNodeWithTag("sale_search").performTextInput("nap")
        rule.waitForIdle()
        rule.onNodeWithTag("sale_pick").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("+").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("sale_total").assertExists()
        assertTrue(rule.onAllNodesWithText("৳৩").fetchSemanticsNodes().isNotEmpty())
        shot("counter-sale")
    }

    @Test
    fun counterSaleSearchAcceptsBanglaDigits() {
        val items = listOf(ShopItemLite("i2", "Seclo 20", "piece", 3.0, 7.0), ShopItemLite("i1", "Napa 500mg", "piece", 120.0, 1.5))
        assertEquals(listOf("Seclo 20"), CounterSale.match(items, "২০").map { it.name })
        assertEquals(listOf("Napa 500mg"), CounterSale.match(items, "NAPA").map { it.name })
        assertTrue(CounterSale.match(items, " ").isEmpty())
        assertEquals(17.5, CounterSale.total(listOf(items[0] to 2.0, items[1] to 2.0)), 0.001)
    }

    // ---------------- অ্যাম্বুলেন্স ----------------

    @Test
    fun ambulanceAvailabilityOnce() {
        val a1 = Ambulance("a1", "ডেমো জীবন অ্যাম্বুলেন্স (এসি)", "01700000011", listOf("ac"), "গাজীপুর", null, "", "", true, 4.7, 9, status = "approved", availableAt = now - 9 * 60_000L)
        val a2 = Ambulance("a2", "ডেমো জীবন মিনি অ্যাম্বুলেন্স", "01700000012", listOf("general"), "গাজীপুর", null, "", "", false, 4.5, 2, status = "approved")
        val a3 = Ambulance("a3", "ডেমো জীবন আইসিইউ অ্যাম্বুলেন্স", "01700000013", listOf("icu"), "গাজীপুর", null, "", "", false, 0.0, 0, status = "pending")
        show { Column(Modifier.padding(horizontal = 16.dp)) { AmbulanceAvailabilityContent(listOf(a1, a2, a3)) } }
        // অনুমোদিত দুইটা গাড়ি — প্রতিটায় একবার খালি আছি / ট্রিপে আছি
        assertEquals(2, rule.onAllNodesWithTag("amb_set_free").fetchSemanticsNodes().size)
        assertEquals(2, rule.onAllNodesWithTag("amb_set_trip").fetchSemanticsNodes().size)
        assertNotCut("খালি আছি")
        assertNotCut("ট্রিপে আছি")
        shot("ambulance-availability")
    }

    // ---------------- ল্যাব / অঙ্ক ----------------

    @Test
    fun expiredHomeSlot() {
        val today = LabPriceText.today()
        val day = 24L * 3_600_000L
        fun home(status: String, date: Long, slot: String) = LabHome(status, date, slot, "", null, 0L, "", "", "", "", "")
        assertTrue(LabSlot.expired(home("requested", today - 2 * day, "early")))
        assertTrue(LabSlot.expired(home("expired", today + day, "early")))
        assertFalse(LabSlot.expired(home("requested", today + day, "afternoon")))
        assertFalse(LabSlot.expired(home("confirmed", today - 2 * day, "early")))
    }

    @Test
    fun phonesAndCodesStayLatin() {
        assertEquals("01712345678", phoneText("০১৭১২৩৪৫৬৭৮"))
        assertEquals("Seclo 20", latinDigits("Seclo ২০"))
    }

    @Suppress("unused")
    private fun SemanticsNodeInteraction.exists(): Boolean = runCatching { assertExists() }.isSuccess
}
