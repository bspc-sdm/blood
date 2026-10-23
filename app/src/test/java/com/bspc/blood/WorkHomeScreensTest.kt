package com.bspc.blood

import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsSelected
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
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
 * "আমার কাজ" → "আজকের কাজ" (WorkHome.kt), ৩৬০dp: একাধিক ভূমিকা (চিপ, অবস্থা, সংখ্যা, এখনই দেখুন), শুধু ফার্মেসি (বিরতি),
 * নতুন সেবাদাতা ("শুরু করতে যা বাকি"), কাজ নেই, পুরোনো সার্ভার (সংখ্যা/কাজ নেই), ফিচার সুইচ বন্ধ, "সেবা দিন", কঙ্কাল,
 * আর ভেতরের পাতার একই নিচের বার। ছবি app/build/screens/work-home-*.png।
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class WorkHomeScreensTest {
    @get:Rule
    val rule = createComposeRule()

    private val now = 1_761_278_400_000L // ঢাকা ১০:০০

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        if (FirebaseApp.getApps(ctx).isEmpty()) runCatching { FirebaseApp.initializeApp(ctx) }
        Areas.ensure(ctx)
        AppPrefs.lang.value = Lang.BN
        AppPrefs.theme.value = ThemeMode.LIGHT
        LoginGate.uid.value = "demo"
        LoginGate.profileReady.value = true
        Practice.testKeys = mapOf(Practice.SYSTEMS to true, Practice.PHYSIO to false, Practice.PSYCH to false)
        OnlineGate.testKeys = mapOf(OnlineGate.CHAMBER to false)
        Features.set(Features.KEYS.associateWith { true }, 1L, null)
        WorkStatusApi.forgetUnknown()
    }

    @After
    fun tearDown() {
        Practice.testKeys = null
        OnlineGate.testKeys = null
        MyWork.clear()
        Features.set(emptyMap(), 0L, null)
        AppPrefs.lang.value = Lang.BN
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        captureScreenRoboImage("build/screens/work-home-$name.png")
    }

    private val pharmacy = Pharmacy(
        "p1", "ডেমো লাইফ ফার্মেসি", "01700000021", "শিববাড়ি মোড়", "গাজীপুর", null, "09:00", "22:00", false, true, true, "", 10, 4.5, 12,
        status = "approved", extras = ListingExtras(openNow = true, delivery = true, ratingAvg = 4.5, ratingCount = 12),
    )
    private val lab = Lab("l1", "ডেমো সিটি ডায়াগনস্টিক সেন্টার", "01700000041", "শিববাড়ি", "গাজীপুর", null, null, true, "সকাল ৮টা – রাত ১০টা", status = "approved", extras = ListingExtras(openNow = true))
    private val amb = listOf(
        Ambulance("a1", "ডেমো জীবন অ্যাম্বুলেন্স (এসি)", "01700000011", listOf("ac"), "গাজীপুর", null, "", "", true, 4.7, 9, status = "approved"),
        Ambulance("a2", "ডেমো আইসিইউ অ্যাম্বুলেন্স", "01700000012", listOf("icu"), "গাজীপুর", null, "", "", false, 4.2, 3, status = "approved"),
    )
    private val bank = BloodBank("b1", "সন্ধানী, ময়মনসিংহ", "org", "01700000032", "ময়মনসিংহ", null, "মেডিকেল কলেজ ক্যাম্পাস", false, "09:00", "17:00", true, listOf("whole_blood"),
        status = "approved", extras = ListingExtras(openNow = true, stock = mapOf("A+" to "have"), stockUpdatedAt = now - 60 * 3_600_000L))

    private val multi = WorkSummary(ambulances = amb, pharmacy = pharmacy, bloodBanks = listOf(bank), lab = lab, professional = "doctor")

    private fun live(): WorkToday {
        val accept = WorkTask("pharmacy", "o1", "রহিমের প্রেসক্রিপশন", "কোড RX7K2 · ৩টা ওষুধ", now - 300_000, "accept", type = "order_new")
        val ready = WorkTask("pharmacy", "o2", "সালমার অর্ডার তৈরি করুন", "দোকানে নেবেন · ২টা ওষুধ", now - 900_000, "ready", type = "order_new")
        val call = WorkTask("doctor", "k0", "পরের রোগী: সিরিয়াল ৭", "করিম মিয়া · অপেক্ষায়", now - 120_000, "next", type = "queue_next")
        val report = WorkTask("lab", "r1", "CBC, RBS — নাসরিন", "নমুনা নেওয়া হয়েছে", now - 3_600_000, "report", type = "lab_report")
        val amb = WorkTask("ambulance", "c1", "রোগী: হাসপাতালে নিতে হবে", "শিববাড়ি → মমেক", now - 60_000, "accept", type = "call")
        return WorkToday(
            listOf(
                WorkBiz(
                    "doctor", "d1", "ডা. ডেমো হাসান", counts = mapOf("today" to 12.0, "waiting" to 4.0, "requests" to 1.0), tasks = listOf(call),
                    units = listOf(WorkUnit("chamber", "chamber:d1:k0", "পপুলার ডায়াগনস্টিক", "open", chamberIndex = 0)),
                ),
                WorkBiz(
                    "pharmacy", "p1", pharmacy.name, "approved", counts = mapOf("new" to 3.0, "active" to 1.0, "salesTodayTk" to 12_340.0, "finds" to 2.0),
                    tasks = listOf(accept, ready) + (1..3).map { WorkTask("pharmacy", "f$it", "ওষুধ খোঁজা: Napa Extra", "কাছের রোগী", now - it * 1_000_000L, "answer", type = "find") },
                    units = listOf(WorkUnit("pharmacy", "p1", pharmacy.name, "open")),
                ),
                WorkBiz(
                    "lab", "l1", lab.name, "approved", counts = mapOf("new" to 2.0, "active" to 5.0), tasks = listOf(report),
                    units = listOf(WorkUnit("lab", "l1", lab.name, "paused", now + 30 * 60_000L)),
                ),
                WorkBiz("bloodbank", "b1", bank.name, "approved", units = listOf(WorkUnit("bloodbank", "b1", bank.name, stockUpdatedAt = bank.extras.stockUpdatedAt, stockStale = true))),
                WorkBiz(
                    "ambulance", "own", "অ্যাম্বুলেন্স", counts = mapOf("calls" to 1.0), tasks = listOf(amb),
                    units = listOf(WorkUnit("ambulance", "a1", this.amb[0].name, "open"), WorkUnit("ambulance", "a2", this.amb[1].name, "closed")),
                ),
            ),
            live = true,
            tasks = listOf(amb, call, accept, ready, report),
            taskCount = 8,
        )
    }

    private var opened: Pair<String, String?>? = null
    private var setTo: Triple<String, String, Long?>? = null

    private fun home(s: WorkSummary, today: WorkToday, chip: String = WorkRoles.ALL, loading: Boolean = false, preview: List<ListingCardData>? = null) {
        MyWork.summary.value = s
        val roles = WorkRoles.owned(s, today.takeIf { it.live })
        rule.setContent {
            GetBloodTheme {
                Surface {
                    WorkHome(
                        s, today, loading, roles, chip, {},
                        preview ?: workPreviewCards(s, null, if (chip == WorkRoles.ALL) roles else listOf(chip)),
                        null, null,
                        onSetStatus = { b, st, until -> setTo = Triple(b.id, st, until) },
                        onOpen = { r, t -> opened = r to t },
                        onOpenBusiness = {}, onOpenChamber = {}, now = now,
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun multiRoleAll() {
        home(multi, live())
        rule.onNodeWithText("আজকের কাজ").assertExists()
        rule.onNodeWithTag("work_chips").assertExists()
        rule.onNodeWithTag("work_chip_all").assertIsSelected()
        rule.onNodeWithTag("work_open_chamber").assertExists()
        rule.onNodeWithText("আমার চেম্বার").assertExists()
        rule.onNodeWithText("আজ সিরিয়াল চলছে").assertExists()
        rule.onNodeWithText("আজকের সিরিয়াল").assertExists()
        rule.onNodeWithText("১২").assertExists()
        shot("multi-bn")
        // ফার্মেসির সংখ্যা চাপলে "নতুন" ভাগ
        rule.onNodeWithTag("work_tile_pharmacy_new").performClick()
        assertEquals("pharmacy" to "orders:new", opened)
        rule.onNodeWithTag("work_tile_pharmacy_salesTodayTk").performClick()
        assertEquals("pharmacy" to "sales", opened)
        rule.onNodeWithText("৳১২,৩৪০").assertExists()
    }

    @Test
    fun multiRoleNowListAndPreview() {
        home(multi, live())
        // নিচে নামিয়ে "এখনই দেখুন"
        rule.onNodeWithTag("work_home").performScrollToKey("now_title")
        rule.onNodeWithText("এখনই দেখুন").assertExists()
        rule.onAllNodesWithTag("work_task").fetchSemanticsNodes().let { assertTrue(it.isNotEmpty() && it.size <= 5) }
        // প্রতিটা সারিতে একটাই বোতাম; সার্ভারের মোট সংখ্যা
        rule.onNodeWithTag("work_home").performScrollToKey("now_more")
        rule.onNodeWithText("সব দেখুন (৮)").assertExists()
        shot("multi-now-bn")
        rule.onNodeWithTag("work_home").performScrollToKey("preview")
        rule.onNodeWithTag("work_preview_see").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("work_preview_sheet").assertExists()
        shot("preview-sheet-bn")
    }

    @Test
    fun pharmacyOnlyPausedWithPauseDialog() {
        val s = WorkSummary(pharmacy = pharmacy)
        val t = WorkToday(
            listOf(
                WorkBiz(
                    "pharmacy", "p1", pharmacy.name, "approved", counts = mapOf("new" to 0.0, "active" to 2.0, "salesTodayTk" to 850.0),
                    units = listOf(WorkUnit("pharmacy", "p1", pharmacy.name, "paused", now + 30 * 60_000L)),
                ),
            ),
            true,
        )
        home(s, t)
        // একটাই ভূমিকা: চিপ নেই
        assertEquals(0, rule.onAllNodesWithTag("work_chips").fetchSemanticsNodes().size)
        rule.onNodeWithText("বিরতি · আবার খুলবে সকাল ১০:৩০").assertExists()
        rule.onNodeWithTag("work_set_paused_p1").assertIsSelected()
        rule.onNodeWithText("এখন কোনো কাজ বাকি নেই").assertExists()
        shot("pharmacy-paused-bn")
        // বন্ধ চাপলে
        rule.onNodeWithTag("work_set_closed_p1").performClick()
        assertEquals(Triple("p1", "closed", null), setTo)
    }

    @Test
    fun pauseAsksHowLong() {
        val s = WorkSummary(pharmacy = pharmacy)
        home(s, WorkToday(listOf(WorkBiz("pharmacy", "p1", pharmacy.name, "approved", units = listOf(WorkUnit("pharmacy", "p1", pharmacy.name, "open")))), true))
        rule.onNodeWithTag("work_set_paused_p1").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("কতক্ষণের বিরতি?").assertExists()
        rule.onNodeWithText("আজকের মতো").assertExists()
        shot("pause-dialog-bn")
        rule.onNodeWithTag("work_pause_1").performClick()
        assertEquals(Triple("p1", "paused", now + 3_600_000L), setTo)
    }

    @Test
    fun newProviderSeesSetupInsteadOfNumbers() {
        val s = WorkSummary(lab = lab)
        val t = WorkToday(
            listOf(
                WorkBiz(
                    "lab", "l1", lab.name, "approved", counts = mapOf("new" to 0.0),
                    setup = listOf(WorkSetup("approved", true), WorkSetup("hours", false), WorkSetup("address", false), WorkSetup("prices", false)),
                    units = listOf(WorkUnit("lab", "l1", lab.name, "open")),
                ),
            ),
            true,
        )
        home(s, t)
        rule.onNodeWithText("শুরু করতে যা বাকি · ৪টার ১টা হয়েছে").assertExists()
        rule.onNodeWithText("হয়েছে: অনুমোদন").assertExists()
        rule.onNodeWithText("পরীক্ষার দাম").assertExists()
        rule.onNodeWithTag("work_setup_hours").assertExists()
        assertEquals(0, rule.onAllNodesWithTag("work_tiles_lab").fetchSemanticsNodes().size)
        shot("setup-bn")
        rule.onNodeWithTag("work_setup_hours").performClick()
        assertEquals("lab" to "shop", opened)
    }

    @Test
    fun nearlyDoneKeepsTheNumbers() {
        val s = WorkSummary(professional = "doctor")
        val t = WorkToday(
            listOf(
                WorkBiz(
                    "doctor", "d1", "ডা. ডেমো হাসান", counts = mapOf("today" to 5.0, "waiting" to 2.0),
                    setup = listOf(WorkSetup("photo", false), WorkSetup("chamber", true), WorkSetup("time", true), WorkSetup("address", true), WorkSetup("fee", true)),
                    units = listOf(WorkUnit("chamber", "chamber:d1:k0", "পপুলার", "open", chamberIndex = 0)),
                ),
            ),
            true,
        )
        home(s, t)
        rule.onNodeWithTag("work_setup_photo").assertExists()
        rule.onNodeWithText("হয়েছে: চেম্বার · সময় · ঠিকানা · ফি").assertExists()
        rule.onNodeWithTag("work_tiles_chamber").assertExists()
        shot("setup-nearly-done-bn")
    }

    @Test
    fun oldServerShowsStatusButNoNumbersOrTasks() {
        val s = WorkSummary(pharmacy = pharmacy, lab = lab)
        home(s, WorkFallback.build(s, null, now))
        rule.onNodeWithTag("work_set_open_p1").assertIsSelected()
        // পুরোনো সার্ভারে বিরতি নেই (খোলা/বন্ধ আছে)
        rule.onNodeWithTag("work_set_closed_p1").assertExists()
        assertEquals(0, rule.onAllNodesWithTag("work_set_paused_p1").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithTag("work_set_paused_l1").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText("এখনই দেখুন").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithTag("work_tiles_pharmacy").fetchSemanticsNodes().size)
        shot("fallback-bn")
    }

    @Test
    fun switchesOffHideTheirNumbersAndTasks() {
        Features.set(emptyMap(), 2L, null)
        val s = WorkSummary(pharmacy = pharmacy)
        val raw = WorkToday(
            listOf(
                WorkBiz(
                    "pharmacy", "p1", pharmacy.name, "approved", counts = mapOf("new" to 1.0, "invoicesToday" to 3.0),
                    tasks = listOf(WorkTask("pharmacy", "pay1", "বিকাশ পেমেন্ট মেলান", "৳৫০০", now, "pay_check"), WorkTask("pharmacy", "o1", "রহিমের প্রেসক্রিপশন", "", now - 1, "accept")),
                    units = listOf(WorkUnit("pharmacy", "p1", pharmacy.name, "open")),
                ),
            ),
            true,
            tasks = listOf(WorkTask("pharmacy", "pay1", "বিকাশ পেমেন্ট মেলান", "৳৫০০", now, "pay_check")),
            taskCount = 2,
        )
        home(s, WorkFallback.merge(raw, WorkFallback.build(s, null, now)))
        assertEquals(0, rule.onAllNodesWithText("বিকাশ পেমেন্ট মেলান").fetchSemanticsNodes().size)
        rule.onNodeWithText("রহিমের প্রেসক্রিপশন").assertExists()
        shot("switches-off-bn")
    }

    @Test
    fun ambulanceAndBloodBankRows() {
        val s = WorkSummary(ambulances = amb, bloodBanks = listOf(bank))
        home(s, WorkFallback.build(s, null, now), chip = WorkRoles.ALL)
        // গাড়ির পাতার একই "এখন অবস্থা" — গাড়িপ্রতি একবার খালি আছি / ট্রিপে আছি, বিরতি নেই
        rule.onNodeWithTag("amb_availability").assertExists()
        assertEquals(2, rule.onAllNodesWithTag("amb_set_free").fetchSemanticsNodes().size)
        assertEquals(2, rule.onAllNodesWithTag("amb_set_trip").fetchSemanticsNodes().size)
        rule.onNodeWithTag("work_stock_b1").assertExists()
        assertEquals(0, rule.onAllNodesWithTag("work_set_paused_a1").fetchSemanticsNodes().size)
        shot("ambulance-bloodbank-bn")
    }

    @Test
    fun chipNarrowsToOneRole() {
        home(multi, live(), chip = "pharmacy")
        rule.onNodeWithTag("work_chip_pharmacy").assertIsSelected()
        assertEquals(0, rule.onAllNodesWithTag("work_block_lab").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText("সিরিয়াল ৭ · করিম মিয়া").fetchSemanticsNodes().size)
        shot("chip-pharmacy-bn")
    }

    @Test
    fun englishMulti() {
        AppPrefs.lang.value = Lang.EN
        home(multi, live())
        // ইংরেজি লেখা tr()-এ শিরোনামের মতো বড় হাতের হয় — একই ফাংশন থেকে
        rule.onNodeWithText(WorkText.title()).assertExists()
        rule.onNodeWithText(WorkText.serialToday()).assertExists()
        assertTrue(WorkText.title().startsWith("Today"))
        shot("multi-en")
    }

    @Test
    fun joinPage() {
        rule.setContent { GetBloodTheme { Surface { JoinWorkPage(null, { opened = it to null }, {}) } } }
        rule.waitForIdle()
        rule.onNodeWithText("সেবা দিন").assertExists()
        rule.onNodeWithText("ডাক্তার ও স্বাস্থ্য বিশেষজ্ঞ").assertExists()
        rule.onNodeWithTag("join_lab").assertExists()
        shot("join-bn")
        rule.onNodeWithTag("join_pharmacy").performClick()
        assertEquals("pharmacy" to null, opened)
    }

    @Test
    fun skeleton() {
        rule.setContent { GetBloodTheme { Surface { WorkSkeleton() } } }
        rule.onNodeWithTag("work_skeleton").assertExists()
        shot("skeleton")
    }

    @Composable
    private fun shell() = WorkShell(onTab = {}) { Text("ভেতরের পাতা") }

    @Test
    fun innerPagesKeepTheSameBottomBar() {
        MyWork.summary.value = WorkSummary(pharmacy = pharmacy)
        rule.setContent { GetBloodTheme { shell() } }
        rule.waitForIdle()
        rule.onNodeWithText("আমার কাজ").assertIsSelected()
        rule.onNodeWithText("হোম").assertExists()
        shot("shell-bn")
    }

    @Test
    fun noRoleTabSaysGiveService() {
        MyWork.summary.value = WorkSummary()
        MyWork.loaded.value = true
        MyWork.badgeKnown.value = true
        rule.setContent { GetBloodTheme { shell() } }
        rule.waitForIdle()
        rule.onNodeWithText("সেবা দিন").assertExists()
        assertEquals(0, rule.onAllNodesWithText("আমার কাজ").fetchSemanticsNodes().size)
    }

    @Test
    fun beforeLoadingTheTabIsAlreadyThere() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.getSharedPreferences("work_home", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        MyWork.clear()
        rule.setContent { GetBloodTheme { shell() } }
        rule.waitForIdle()
        // প্রথমবার, জানার আগে: "আমার কাজ" (পরে লাফিয়ে আসে না)
        rule.onNodeWithText("আমার কাজ").assertExists()
    }
}
