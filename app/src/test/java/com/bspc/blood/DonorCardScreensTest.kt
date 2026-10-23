package com.bspc.blood

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.google.firebase.FirebaseApp
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * রক্তপ্রার্থীর দিকে রাজি হওয়া ডোনারের সারি (DonorRow) — কল/মেসেজ চালু থাকলে (calls_audio, calls_video, chat),
 * ৩৬০dp ফোনে, লম্বা বাংলা নাম, বাংলা আর ইংরেজিতে। RC3-এ ৪টা যোগাযোগের আইকন নামের সারিতেই ছিল: নামের কলাম ০dp পেয়ে
 * লেখা এক অক্ষর করে ভাঙত (বিশাল খালি ফাঁক, নাম দেখা যেত না)। RC4: শুধু দুই আইকন — কল (মেনু: ফোন কল · অ্যাপ কল ·
 * ভিডিও কল) আর মেসেজ; নাম, ফোন, দূরত্ব, অবস্থা পুরো পড়া যায়।
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class DonorCardScreensTest {
    @get:Rule
    val rule = createComposeRule()

    private val longName = "মোহাম্মদ আব্দুল্লাহ আল মামুন চৌধুরী"
    private val shortName = "ডেমো ডোনার"
    private val request = BloodRequest(
        id = "r1", patientGroup = "O+", bags = 2, hospital = "ময়মনসিংহ মেডিকেল কলেজ হাসপাতাল", placeName = "ময়মনসিংহ",
        neededByMillis = System.currentTimeMillis() + 86_400_000L, urgent = false, notified = true, notifiedCount = 23,
    )
    private val donors = listOf(
        DonorResponse("d1", longName, "01200001202", "O+", "accepted", confirmed = true, distanceKm = 2),
        DonorResponse("d2", shortName, "01200001203", "A+", "accepted", distanceKm = 2, fareAfterKm = 1),
    )

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        if (FirebaseApp.getApps(ctx).isEmpty()) runCatching { FirebaseApp.initializeApp(ctx) }
        AppPrefs.theme.value = ThemeMode.LIGHT
        LoginGate.uid.value = "demo"
        LoginGate.profileReady.value = true
        // ডেমো অ্যাকাউন্টে যেমন: অ্যাপে অডিও/ভিডিও কল আর মেসেজ চালু, সার্ভার এই দুই ডোনারের সাথে সব দেয়
        Features.set(mapOf(Features.CALLS_AUDIO to true, Features.CALLS_VIDEO to true, Features.CHAT to true), 1L, null)
        CallAvailability.enabled.value = true
        listOf("r1", "r2").forEach { r ->
            donors.forEach { d -> ContactCan.flags[ContactKey(CallCtx.BLOOD, r, d.donorUid)] = ContactFlags(canCall = true, canVideo = true, canMessage = true) }
        }
    }

    private fun show(content: @Composable () -> Unit) {
        rule.setContent { GetBloodTheme { Surface { content() } } }
        rule.waitForIdle()
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        captureScreenRoboImage("build/screens/$name.png")
    }

    /** হোমের "আমার রিকোয়েস্ট" কার্ড (মালিকের ফোনের ছবির পাতা) */
    private fun homeCard() = show {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MyRequestCard(request, donors, null, {}, {}, {}, {}, {})
        }
    }

    /** রিকোয়েস্টের বিস্তারিত পাতার মতো: প্রতিটা ডোনার নিজের কার্ডে; দ্বিতীয় রিকোয়েস্ট জরুরি */
    private fun detailCards() = show {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(request, request.copy(id = "r2", urgent = true)).forEach { r ->
                donors.forEach { d ->
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Column(Modifier.padding(12.dp)) {
                            DonorRow(d, active = true, busy = false, onConfirm = {}, onRemove = {}, requestId = r.id)
                        }
                    }
                }
            }
        }
    }

    /**
     * প্রতিটা ডোনারের নাম আর ফোন/দূরত্ব পুরো দেখা যায় (চওড়া আছে, ২ লাইনের বেশি লম্বা নয়), আর
     * কল · মেসেজ — দুই আইকন পর্দার ভেতরে [rows] বার; আগের আলাদা চার বাটন আর নেই (মেনু বন্ধ)।
     */
    private fun assertReadable(rows: Int) {
        val d = rule.density
        val screenW = with(d) { 360.dp.toPx() }
        // লেখার কলাম (নাম, ফোন · দূরত্ব, অবস্থা) — প্রতিটা সারিতে অন্তত ১২০dp চওড়া (RC3-এ ০dp/৩৮dp ছিল)
        val infos = rule.onAllNodes(hasTestTag("donor_info"), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("donor rows ${infos.size}", infos.size == rows)
        infos.forEach { n -> assertTrue("text column width ${n.boundsInRoot.width}px", n.boundsInRoot.width >= with(d) { 120.dp.toPx() }) }
        donors.forEach { donor ->
            val names = rule.onAllNodes(hasText(donor.donorName), useUnmergedTree = true).fetchSemanticsNodes()
            assertTrue("name ${donor.donorName} shown", names.isNotEmpty())
            names.forEach { n ->
                val b = n.boundsInRoot
                assertTrue("name ${donor.donorName} height ${b.height}px (≤ 2 lines)", b.height <= with(d) { 64.dp.toPx() })
            }
            val details = rule.onAllNodes(hasText(num(donor.donorPhone), substring = true), useUnmergedTree = true).fetchSemanticsNodes()
            assertTrue("phone ${donor.donorPhone} shown", details.isNotEmpty())
            details.forEach { n ->
                val b = n.boundsInRoot
                assertTrue("details height ${b.height}px (no one-letter-per-line wrap)", b.height <= with(d) { 64.dp.toPx() })
            }
        }
        for (tag in listOf("contact_phone", "app_call_audio", "app_call_video")) rule.onAllNodes(hasTestTag(tag)).assertCountEquals(0)
        for (tag in listOf("contact_call", "contact_message")) {
            rule.onAllNodes(hasTestTag(tag)).assertCountEquals(rows)
            rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().forEach { n ->
                val b = n.boundsInRoot
                assertTrue("$tag inside the screen: $b", b.left >= 0f && b.right <= screenW + 1f && b.width > 0f && b.height > 0f)
            }
        }
    }

    @Test
    fun donorCardHome360Bn() {
        AppPrefs.lang.value = Lang.BN
        homeCard()
        shot("donor_card_home_360_bn")
        assertReadable(rows = 2)
    }

    @Test
    fun donorCardHome360En() {
        AppPrefs.lang.value = Lang.EN
        homeCard()
        shot("donor_card_home_360_en")
        assertReadable(rows = 2)
    }

    /** কলের আইকন → মেনু: ঠিক এই তিন লেখা, এই ক্রমে */
    private fun assertMenu(phone: String, app: String, video: String) {
        rule.onAllNodes(hasTestTag("contact_call"))[0].performClick()
        rule.waitForIdle()
        for ((tag, text) in listOf("contact_phone" to phone, "app_call_audio" to app, "app_call_video" to video)) {
            rule.onNode(hasTestTag(tag)).assertIsDisplayed()
            rule.onNode(hasTestTag(tag).and(hasText(text))).assertExists()
        }
        val tops = listOf("contact_phone", "app_call_audio", "app_call_video").map { rule.onNode(hasTestTag(it)).fetchSemanticsNode().boundsInRoot.top }
        assertTrue("menu order $tops", tops == tops.sorted())
    }

    @Test
    fun donorCallMenu360Bn() {
        AppPrefs.lang.value = Lang.BN
        homeCard()
        assertMenu("ফোন কল", "অ্যাপ কল", "ভিডিও কল")
        shot("donor_call_menu_360_bn")
    }

    @Test
    fun donorCallMenu360En() {
        AppPrefs.lang.value = Lang.EN
        homeCard()
        assertMenu("Phone Call", "App Call", "Video Call")
        shot("donor_call_menu_360_en")
    }

    /** শুধু ফোন চলে (সার্ভার অ্যাপে কল/মেসেজ দেয় না): কলের আইকন সরাসরি ফোনের ডায়াল — মেনু নয়; মেসেজের আইকন নেই */
    @Test
    fun donorPhoneOnly360Bn() {
        AppPrefs.lang.value = Lang.BN
        donors.forEach { d -> ContactCan.flags[ContactKey(CallCtx.BLOOD, "r1", d.donorUid)] = ContactFlags.NONE }
        homeCard()
        shot("donor_card_phone_only_360_bn")
        rule.onAllNodes(hasTestTag("contact_message")).assertCountEquals(0)
        rule.onAllNodes(hasTestTag("contact_call")).assertCountEquals(2)
        rule.onAllNodes(hasTestTag("contact_call"))[0].assertContentDescriptionContains("ফোন কল").performClick()
        rule.waitForIdle()
        rule.onAllNodes(hasTestTag("contact_phone")).assertCountEquals(0)
        rule.onAllNodes(hasTestTag("app_call_audio")).assertCountEquals(0)
    }

    @Test
    fun donorCardDetail360Bn() {
        AppPrefs.lang.value = Lang.BN
        detailCards()
        shot("donor_card_detail_360_bn")
        assertReadable(rows = 4)
    }

    @Test
    fun donorCardDetail360En() {
        AppPrefs.lang.value = Lang.EN
        detailCards()
        shot("donor_card_detail_360_en")
        assertReadable(rows = 4)
    }
}
