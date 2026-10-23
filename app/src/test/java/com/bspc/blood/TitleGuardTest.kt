package com.bspc.blood

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertCountEquals
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.FirebaseApp
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * "ডা." পদবির নিয়ম (TitleGuard.kt, backend/docs/PRACTICE.md §৬): কারা লিখতে পারেন (শুধু এলোপ্যাথি ডাক্তার), প্রতিটা ধরন,
 * পদবি বাদ দেওয়া, নামের ঘরের ভুল আর ছোট লেখা (সুইচ বন্ধে কিছু নয়), পেশার নাম (সার্ভারের typeLabel আগে), প্রেসক্রিপশনের
 * মাথা (পর্দা আর PDF একই), প্রোফাইল এডিটে সেভ আটকানো।
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class TitleGuardTest {
    @get:Rule
    val rule = createComposeRule()

    private val err = "MBBS/BDS ছাড়া নামের আগে ‘ডা.’ বা ‘Dr.’ লেখা যাবে না"
    private val hint = "নামের আগে ‘ডা.’ লিখবেন না — পেশা নিজে থেকেই দেখানো হবে"
    private val on = mapOf(TitleGuard.KEY to true, Practice.SYSTEMS to true)

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        if (FirebaseApp.getApps(ctx).isEmpty()) runCatching { FirebaseApp.initializeApp(ctx) }
        AppPrefs.lang.value = Lang.BN
        AppPrefs.theme.value = ThemeMode.LIGHT
        Practice.testKeys = on
    }

    @After
    fun tearDown() {
        Practice.testKeys = null
    }

    private fun show(content: @Composable () -> Unit) {
        rule.setContent { GetBloodTheme { Surface { Column { content() } } } }
        rule.waitForIdle()
    }

    @Test
    fun onlyAllopathicDoctorsMayCarryTheTitle() {
        assertTrue(TitleGuard.eligible("doctor", "allopathy"))
        assertTrue(TitleGuard.eligible("doctor", ""))
        for ((t, s) in listOf("doctor" to "homeopathy", "doctor" to "unani", "doctor" to "ayurveda", "nurse" to "", "nutritionist" to "", "trainer" to "", "physio" to "", "psychologist" to "", "expert" to "")) {
            assertFalse("$t $s", TitleGuard.eligible(t, s))
        }
    }

    @Test
    fun everyVariantIsFoundAndStripped() {
        for (v in listOf("ডা. করিম", "ডাঃ করিম", "ডা করিম", "ডাক্তার করিম", "ডা: করিম", "ডা.করিম", "Dr. Karim", "Dr Karim", "Dr: Karim", "DR. Karim", "Doctor Karim", "dr.Karim", "DOCTOR Karim")) {
            assertTrue(v, TitleGuard.hasTitle(v))
            assertTrue(v, TitleGuard.strip(v) in setOf("করিম", "Karim"))
        }
        for (n in listOf("ডালিয়া আক্তার", "Drabir Hasan", "Doctorate Ali", "করিম ডা.", "Dr.", "ডা.", "")) assertFalse(n, TitleGuard.hasTitle(n))
        assertEquals("করিম", TitleGuard.strip("ডা. ডা. করিম"))
    }

    @Test
    fun nameFieldErrorAndHintOnlyWhenOn() {
        assertEquals(err, TitleGuard.nameError("ডা. করিম", "doctor", "homeopathy"))
        assertEquals(err, TitleGuard.nameError("Dr. Rahima", "nurse", ""))
        assertNull(TitleGuard.nameError("ডা. করিম", "doctor", "allopathy"))
        assertNull(TitleGuard.nameError("ডা. করিম", null, null))
        assertNull(TitleGuard.nameError("করিম", "physio", ""))
        assertEquals(hint, TitleGuard.nameHint("psychologist", ""))
        assertNull(TitleGuard.nameHint("doctor", ""))
        Practice.testKeys = mapOf(TitleGuard.KEY to false)
        assertNull(TitleGuard.nameError("ডা. করিম", "doctor", "homeopathy"))
        assertNull(TitleGuard.nameHint("nurse", ""))
    }

    @Test
    fun professionLabels() {
        assertEquals("হোমিওপ্যাথিক চিকিৎসক", TitleGuard.professionLabel("doctor", "homeopathy"))
        assertEquals("ইউনানি চিকিৎসক", TitleGuard.professionLabel("doctor", "unani"))
        assertEquals("আয়ুর্বেদিক চিকিৎসক", TitleGuard.professionLabel("doctor", "ayurveda"))
        assertEquals("ডাক্তার", TitleGuard.professionLabel("doctor", "allopathy"))
        assertEquals("ফিজিওথেরাপিস্ট", TitleGuard.professionLabel("physio", ""))
        // সুইচ বন্ধ: আগের মতো, তবে সার্ভারের typeLabel থাকলে সেটাই
        Practice.testKeys = mapOf(TitleGuard.KEY to false)
        assertEquals("ডাক্তার", TitleGuard.professionLabel("doctor", "homeopathy"))
        assertEquals("ইউনানি চিকিৎসক", TitleGuard.professionLabel("doctor", "", "ইউনানি চিকিৎসক", "Unani practitioner"))
    }

    @Test
    fun prescriptionHeaderAndPdfUseThePractitionerLabel() {
        val json = JSONObject()
            .put("id", "rx1").put("code", "ABCD2345EF").put("status", "active").put("createdAt", 1_792_886_400_000L).put("doctorUid", "d1")
            .put(
                "doctor",
                JSONObject().put("name", "আনোয়ার হোসেন").put("degrees", "DHMS").put("regNo", "H-1").put("specialties", JSONArray())
                    .put("system", "homeopathy").put("type", "doctor").put("typeLabel", "হোমিওপ্যাথিক চিকিৎসক").put("typeLabelEn", "Homeopathic practitioner"),
            )
            .put("patient", JSONObject().put("name", "রহিম"))
            .put("medicines", JSONArray()).put("tests", JSONArray())
        val rx = Rx.parse(json)
        assertEquals("হোমিওপ্যাথিক চিকিৎসক", rx.typeLabel)
        assertEquals("হোমিওপ্যাথিক চিকিৎসক", Practice.rxHeaderTag(rx))
        // পুরোনো সার্ভার (typeLabel নেই), সুইচ বন্ধ: আগের মতো পদ্ধতি
        Practice.testKeys = mapOf(Practice.SYSTEMS to true, TitleGuard.KEY to false)
        assertEquals("হোমিওপ্যাথি", Practice.rxHeaderTag(rx.copy(typeLabel = "", typeLabelEn = "")))
    }

    @Test
    fun profileEditBlocksATitleForAHomeopath() {
        var saved: Profile? = null
        val profile = Profile(name = "ডা. করিম উদ্দিন", phone = "01712345678", placeName = "ময়মনসিংহ", lat = 24.75, lng = 90.4, locationSource = "district")
        show {
            ProfileEditScreen(
                existing = profile, busy = false, errorMsg = null, onSave = { saved = it }, onCancel = {}, mode = ProfileMode.BASIC,
                pro = BadgeState("doctor", "approved", system = "homeopathy"),
            )
        }
        rule.onAllNodesWithText(err).assertCountEquals(1)
        rule.onNodeWithText("সেভ করুন").performScrollTo().performClick()
        rule.waitForIdle()
        assertNull(saved)
        rule.onNodeWithText("ডা. করিম উদ্দিন").performTextReplacement("করিম উদ্দিন")
        rule.waitForIdle()
        rule.onNodeWithText(hint).assertExists()
        rule.onNodeWithText("সেভ করুন").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals("করিম উদ্দিন", saved?.name)
    }

    @Test
    fun profileEditAllowsATitleForAnMbbsDoctor() {
        var saved: Profile? = null
        val profile = Profile(name = "ডা. করিম উদ্দিন", phone = "01712345678", placeName = "ময়মনসিংহ", lat = 24.75, lng = 90.4, locationSource = "district")
        show { ProfileEditScreen(existing = profile, busy = false, errorMsg = null, onSave = { saved = it }, onCancel = {}, mode = ProfileMode.BASIC, pro = BadgeState("doctor", "approved")) }
        rule.onAllNodesWithText(err).assertCountEquals(0)
        rule.onAllNodesWithText(hint).assertCountEquals(0)
        rule.onNodeWithText("সেভ করুন").performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(saved?.name.orEmpty().startsWith("ডা."))
    }
}
