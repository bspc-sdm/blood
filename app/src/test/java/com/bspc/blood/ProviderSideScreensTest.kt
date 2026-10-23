package com.bspc.blood

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
 * "আমার কাজ" — সেবাদাতার দিক, পাঁচ ধরনেই একই গঠন (ProviderSide.kt), ৩৬০dp: "রোগীরা আপনাকে যেভাবে দেখেন" (সবার তালিকার
 * একই কার্ড, ভাঁজ করা যায় আর মনে থাকে), এক চাপের সুইচ, আর নতুন / চলছে / শেষ। ছবি app/build/screens/provider-*.png।
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class ProviderSideScreensTest {
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
        Practice.testKeys = mapOf(Practice.SYSTEMS to true, Practice.PHYSIO to false, Practice.PSYCH to false)
        OnlineGate.testKeys = mapOf(OnlineGate.CHAMBER to false)
        ListingType.values().forEach { PreviewPrefs.set(ctx, it, false) }
    }

    @After
    fun tearDown() {
        Practice.testKeys = null
        OnlineGate.testKeys = null
    }

    private fun show(content: @Composable () -> Unit) {
        rule.setContent {
            GetBloodTheme { Surface { Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() } } }
        }
        rule.waitForIdle()
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        captureScreenRoboImage("build/screens/provider-$name.png")
    }

    private val pharmacy = Pharmacy(
        "p1", "ডেমো লাইফ ফার্মেসি", "01700000021", "শিববাড়ি মোড়", "গাজীপুর", null, "09:00", "22:00", false, true, true, "", 10, 4.5, 12,
        status = "approved", extras = ListingExtras(openNow = true, delivery = true, ratingAvg = 4.5, ratingCount = 12),
    )

    @Test
    fun pharmacyPreviewAndSwitches() {
        show {
            PharmacyProviderTop(pharmacy) {}
            WorkSegTabs("new", WorkQueue.counts(mapOf("new" to 2, "active" to 1, "closed" to 14)), {})
        }
        rule.onNodeWithText("রোগীরা আপনাকে যেভাবে দেখেন").assertExists()
        // এক লাইনে নাম আর সবার তালিকার একই মূল লাইন; পুরো কার্ড শুধু "দেখুন" চাপলে (চুক্তি §১.৬)
        rule.onNodeWithText(Listing.keyLine("খোলা", "ডেলিভারি আছে"), substring = true).assertExists()
        assertEquals(0, rule.onAllNodesWithTag("listing_card").fetchSemanticsNodes().size)
        rule.onNodeWithTag("sw_open").assertExists()
        rule.onNodeWithTag("sw_delivery").assertExists()
        // সংখ্যা লেখার সাথেই ("নতুন ২")
        rule.onNodeWithText("নতুন ২").assertExists()
        rule.onNodeWithText("চলছে ১").assertExists()
        rule.onNodeWithText("শেষ").assertExists()
        shot("pharmacy-bn")
    }

    @Test
    fun previewIsOneLineAndOpensTheCard() {
        show { PharmacyProviderTop(pharmacy) {} }
        // এক লাইন — তালিকার কার্ড নেই যতক্ষণ না "দেখুন"
        assertEquals(0, rule.onAllNodesWithTag("listing_card").fetchSemanticsNodes().size)
        shot("pharmacy-preview-line-bn")
        rule.onNodeWithTag("patient_preview").performClick()
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithTag("listing_card").fetchSemanticsNodes().isNotEmpty())
        shot("pharmacy-preview-open-bn")
    }

    @Test
    fun labPreviewAndSwitches() {
        val lab = Lab("l1", "ডেমো সিটি ডায়াগনস্টিক সেন্টার", "01700000041", "শিববাড়ি", "গাজীপুর", null, null, true, "সকাল ৮টা – রাত ১০টা", status = "approved",
            extras = ListingExtras(openNow = false))
        show {
            LabProviderTop(lab) {}
            WorkSegTabs("active", mapOf("new" to 0, "active" to 3, "done" to 9), {})
        }
        rule.onNodeWithText(Listing.keyLine("বন্ধ", "বাসা থেকে নমুনা"), substring = true).assertExists()
        rule.onNodeWithTag("sw_home").assertExists()
        shot("lab-bn")
    }

    @Test
    fun bloodBankStockEditor() {
        val b = BloodBank("b1", "সন্ধানী, ময়মনসিংহ", "org", "01700000032", "ময়মনসিংহ", null, "মেডিকেল কলেজ ক্যাম্পাস", false, "09:00", "17:00", true, listOf("whole_blood"),
            status = "approved", extras = ListingExtras(openNow = true, stock = mapOf("A+" to "have", "B+" to "low", "O-" to "none"), stockUpdatedAt = now - 60 * 3_600_000L))
        show {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PatientViewPreview(bloodBankCard(b, {}, {}, null))
                StockEditor(b) {}
            }
        }
        // পুরনো (৪৮ ঘণ্টার বেশি) — কার্ডে আর সম্পাদকে
        assertTrue(rule.onAllNodesWithTag("stock_editor").fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithTag("stock_refresh").assertExists()
        shot("bloodbank-bn")
        // একটা গ্রুপ বদলালে "স্টক সেভ করুন"
        rule.onNodeWithTag("stock_AB+_have").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("stock_save").assertExists()
        shot("bloodbank-changed-bn")
    }

    @Test
    fun ambulancePreviewAndSwitch() {
        val a = Ambulance("a1", "ডেমো জীবন অ্যাম্বুলেন্স (এসি)", "01700000011", listOf("ac", "general"), "গাজীপুর", null, "তাজউদ্দীন মেডিকেল গেট", "", true, 4.7, 9,
            status = "approved", availableAt = now - 9 * 60_000L)
        show {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PatientViewPreview(ambulanceCard(a, {}, {}, null))
                QuickSwitch(ProviderText.available(), tr("Turn off when you go on a trip.", "ট্রিপে গেলে বন্ধ করুন।"), true, {}, tag = "amb_available")
            }
            WorkSegTabs("new", mapOf("new" to 1, "active" to 0), {})
        }
        rule.onNodeWithText("খালি আছে", substring = true).assertExists()
        shot("ambulance-bn")
    }

    @Test
    fun doctorPreviewAndTodaySwitches() {
        val all = listOf(6, 0, 1, 2, 3, 4, 5)
        val c1 = Chamber("dghs_1", "MMCH", "ময়মনসিংহ মেডিকেল কলেজ হাসপাতাল", "ময়মনসিংহ", null, null, "", "", "৫০০ টাকা", true, "01700000202")
            .withSlots(listOf(ChamberTime(all, "00:00", "23:59")))
        val c2 = Chamber("dghs_2", "Popular", "পপুলার ডায়াগনস্টিক সেন্টার", "ময়মনসিংহ", null, null, "", "", "৮০০", true, "")
            .withSlots(listOf(ChamberTime(all, "00:00", "23:59"))).copy(closedToday = true)
        val p = Provider("d1", "doctor", "ডা. ডেমো হাসান", listOf("medicine"), "MBBS", "male", true, true, listOf(c1, c2), null, 4.6, 12)
        show {
            Column(Modifier.padding(horizontal = 16.dp)) { DoctorProviderTopContent(p) {} }
            WorkSegTabs("new", mapOf("new" to 3, "active" to 5, "done" to 40), {})
        }
        rule.onNodeWithText("আজ সিরিয়াল চলছে").assertExists()
        rule.onNodeWithText("আজ বন্ধ").assertExists()
        rule.onNodeWithText("আজ সিরিয়াল আছে").assertExists()
        shot("doctor-bn")
    }
}
