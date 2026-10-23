package com.bspc.blood

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.google.firebase.FirebaseApp
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ডাক্তারের বিস্তারিত শিট (২.৩৩.০): নামের নিচে চেম্বার বাছাই, চাপযোগ্য "কোন চেম্বারে?", চেম্বার চাপলে সরাসরি বুকিং —
 * ৩৬০dp আর ৪১২dp চওড়া ফোনে (app/build/screens-এ ছবি)। আর চেম্বারের আজকের অবস্থার হিসাব (ঢাকার নির্দিষ্ট সময়ে)।
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class DoctorSheetScreensTest {
    @get:Rule
    val rule = createComposeRule()

    private val profile = Profile(name = "ডেমো রোগী রহিম", phone = "01700001101", placeName = "গাজীপুর", locationSource = "district")
    private val all = listOf(6, 0, 1, 2, 3, 4, 5)
    private val ch1 = Chamber(
        "dghs_22839", "Shaheed Taj Uddin Ahmad Medical College Hospital", "শহীদ তাজ উদ্দীন আহমদ মেডিকেল কলেজ হাসপাতাল", "গাজীপুর", 320, null,
        "বহির্বিভাগ ৩১২", "প্রতিদিন খোলা", "৫০০ টাকা", true, "01700000202",
    ).withSlots(listOf(ChamberTime(all, "10:00", "13:00"), ChamberTime(all, "16:00", "01:00")))
    private val ch2 = Chamber(
        "dghs_137", "Kaliakair Upazila Health Complex", "কালিয়াকৈর উপজেলা স্বাস্থ্য কমপ্লেক্স", "গাজীপুর", 318, null,
        "রুম ৫", "শুক্রবার শুধু ফলো-আপ", "৪০০ টাকা", true, "",
    ).withSlots(listOf(ChamberTime(listOf(5), "14:00", "15:30")))
    private val doc = Provider(
        "demo-doctor", "doctor", "ডা. ডেমো হাসান", listOf("medicine", "endocrinology"), "এমবিবিএস, এফসিপিএস (মেডিসিন)", "male", true, true,
        listOf(ch1, ch2), null, 4.6, 12,
    )

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        if (FirebaseApp.getApps(ctx).isEmpty()) runCatching { FirebaseApp.initializeApp(ctx) }
        Areas.ensure(ctx)
        AppPrefs.lang.value = Lang.BN
        AppPrefs.theme.value = ThemeMode.LIGHT
        LoginGate.uid.value = "demo"
        LoginGate.profileReady.value = true
    }

    private fun show(content: @Composable () -> Unit) {
        rule.setContent { GetBloodTheme { Surface { content() } } }
        rule.waitForIdle()
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        captureScreenRoboImage("build/screens/$name.png")
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun sheet360() {
        show { ProviderDetailDialog(doc, profile, {}, {}) }
        rule.onNodeWithText("চেম্বার বেছে অ্যাপয়েন্টমেন্ট নিন").assertExists()
        // চেম্বার কার্ডের নাম আর ভাগের শিরোনাম — দুটোই
        assertEquals(2, rule.onAllNodesWithText("শহীদ তাজ উদ্দীন আহমদ মেডিকেল কলেজ হাসপাতাল").fetchSemanticsNodes().size)
        rule.onAllNodesWithText("এই চেম্বারে অ্যাপয়েন্টমেন্ট").fetchSemanticsNodes().let { assertEquals(2, it.size) }
        shot("doctor_sheet_360_bn")
        // নিচের বারের "সিরিয়াল নিন" → "কোন চেম্বারে?" (চাপযোগ্য কার্ড, "বেছে নিন")
        rule.onNodeWithText("সিরিয়াল নিন").performClick()
        rule.onNodeWithText("কোন চেম্বারে?").assertExists()
        rule.onAllNodesWithText("বেছে নিন").fetchSemanticsNodes().let { assertEquals(2, it.size) }
        shot("doctor_which_chamber_360_bn")
        rule.onAllNodesWithText("বেছে নিন")[1].performClick()
        rule.onNodeWithText("অনুরোধ পাঠান").assertExists()
        shot("doctor_book_from_dialog_360_bn")
    }

    @Test
    fun sheet412() {
        show { ProviderDetailDialog(doc, profile, {}, {}) }
        shot("doctor_sheet_412_bn")
        // নামের নিচের চেম্বার কার্ড চাপলে সরাসরি বুকিং ("কোন চেম্বারে?" ছাড়া), পরের বসার দিন আগে থেকে বাছা
        rule.onAllNodesWithText("কালিয়াকৈর উপজেলা স্বাস্থ্য কমপ্লেক্স")[0].performClick()
        rule.onNodeWithText("অনুরোধ পাঠান").assertExists()
        rule.onNodeWithText("তারিখ বেছে নিন").assertDoesNotExist()
        shot("doctor_book_from_chip_412_bn")
    }

    /** একটাই চেম্বার: "কোন চেম্বারে?" নেই — নিচের বার সরাসরি বুকিং */
    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun singleChamber360() {
        show { ProviderDetailDialog(doc.copy(chambers = listOf(ch1)), profile, {}, {}) }
        rule.onNodeWithText("এই চেম্বারে অ্যাপয়েন্টমেন্ট").assertDoesNotExist()
        rule.onNodeWithText("সিরিয়াল নিন").performClick()
        rule.onNodeWithText("কোন চেম্বারে?").assertDoesNotExist()
        rule.onNodeWithText("অনুরোধ পাঠান").assertExists()
        shot("doctor_single_book_360_bn")
    }

    /** তালিকার কার্ড: ছাঁকনিতে মেলা চেম্বার (matched) আগে */
    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun card360() {
        show { ProviderCard(doc.copy(matched = listOf(1)), CareFilter(district = "গাজীপুর")) {} }
        // কার্ডে মেলা চেম্বারের নাম (আরও চেম্বার থাকলে "+১")
        rule.onNodeWithText("কালিয়াকৈর উপজেলা স্বাস্থ্য কমপ্লেক্স", substring = true).assertExists()
        shot("doctor_card_360_bn")
    }

    /** ঢাকার সময় (UTC+6) "YYYY-MM-DDTHH:MM" → মিলিসেকেন্ড */
    private fun dhaka(s: String): Long = java.time.OffsetDateTime.parse("$s:00+06:00").toInstant().toEpochMilli()

    @Test
    fun chamberStateAtDhakaTimes() {
        // ১৯ অক্টোবর ২০২৬ সোমবার
        var s = ChamberClock.state(ch1, dhaka("2026-10-19T11:00"))
        assertEquals(ChamberStateKind.NOW, s.kind)
        assertEquals("এখন চেম্বারে · ১টা পর্যন্ত", s.text)
        s = ChamberClock.state(ch1, dhaka("2026-10-19T14:00"))
        assertEquals("আজ বসবেন ৪টা–১টা", s.text)
        s = ChamberClock.state(ch1, dhaka("2026-10-20T00:30")) // সোমবারের রাতের ভাগ চলছে
        assertEquals(ChamberStateKind.NOW, s.kind)
        s = ChamberClock.state(ch1, dhaka("2026-10-20T01:00"))
        assertEquals("আজ বসবেন ১০টা–১টা", s.text)
        s = ChamberClock.state(ch2, dhaka("2026-10-19T11:00"))
        assertEquals("আজ বন্ধ · শুক্র ২টা", s.text)
        s = ChamberClock.state(ch2, dhaka("2026-10-22T20:00")) // বৃহস্পতি
        assertEquals("আজ বন্ধ · আগামীকাল ২টা", s.text)
        s = ChamberClock.state(ch2, dhaka("2026-10-23T16:00")) // শুক্র, শেষ
        assertEquals("আজ শেষ · শুক্র ২টা", s.text)
        assertEquals("৳৫০০", ChamberClock.feeShort(ch1))
        assertEquals("৳১,২০০", ChamberClock.feeShort(ch1.copy(fee = "1,200 টাকা")))
        assertEquals("ফি নেই", ChamberClock.feeShort(ch1.copy(noFee = true)))
        // বুকিংয়ে আগে থেকে বাছা দিন: শুক্রবারের চেম্বার সোমবারে → শুক্রবার
        assertEquals(java.time.LocalDate.parse("2026-10-23").toEpochDay() * 86400000L, ChamberClock.firstBookableDay(ch2, dhaka("2026-10-19T11:00")))
        // শুক্রবার বিকালে সময় শেষ → পরের শুক্রবার
        assertEquals(java.time.LocalDate.parse("2026-10-30").toEpochDay() * 86400000L, ChamberClock.firstBookableDay(ch2, dhaka("2026-10-23T16:00")))
    }
}
