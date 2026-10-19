package com.bspc.blood

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.google.firebase.FirebaseApp
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * "অ্যাম্বুলেন্স ডাকুন"-এর নিশ্চিত করার ঘর (২.২৯.০): গাড়ির ধরনের চিপ, এলাকা (জেলা › উপজেলা › ইউনিয়ন), চেনা জায়গা,
 * "আমার এখনকার জায়গা" — ৩৬০dp আর ৪১২dp চওড়া ফোনে (app/build/screens-এ ছবি)।
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class AmbRequestScreensTest {
    @get:Rule
    val rule = createComposeRule()

    private val profile = Profile(name = "ডেমো রোগী রহিম", phone = "01700001101", placeName = "গাজীপুর", locationSource = "district")

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

    /** গাজীপুর সদর আগে থেকে, ধরন "যেকোনো", এখনকার জায়গা নেই */
    @Test
    @Config(qualifiers = "w360dp-h740dp-xxhdpi")
    fun requestSheet360() {
        show { AmbulanceRequestDialog(profile, "গাজীপুর", 320, null, null, null, {}, {}) }
        rule.onNodeWithText("গাড়ির ধরন").assertExists()
        rule.onNodeWithText("ফ্রিজার (মরদেহ)").assertExists()
        rule.onNodeWithText("আমার এখনকার জায়গা (ঐচ্ছিক)").assertExists()
        shot("amb_request_360_bn")
    }

    /** তালিকায় "এসি" ছাঁকা ছিল, এখনকার জায়গা নেওয়া হয়েছে */
    @Test
    fun requestSheet412() {
        show { AmbulanceRequestDialog(profile, "গাজীপুর", 320, "ac", 23.999 to 90.42, null, {}, {}) }
        rule.onNodeWithText("এখনকার জায়গা নেওয়া হয়েছে ✓").assertExists()
        shot("amb_request_412_bn")
    }

    /** উপজেলা বাছা নেই আর চেনা জায়গা খালি → "পাঠান" চাপলে লাল লেখা */
    @Test
    @Config(qualifiers = "w360dp-h740dp-xxhdpi")
    fun requestSheetErrors360() {
        show { AmbulanceRequestDialog(profile, "গাজীপুর", null, "freezer", null, null, {}, {}) }
        rule.onNodeWithText("পাঠান").performClick()
        rule.waitForIdle()
        shot("amb_request_errors_360_bn")
    }
}
