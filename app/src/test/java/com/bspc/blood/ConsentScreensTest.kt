package com.bspc.blood

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
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
import java.io.File

/**
 * স্বাস্থ্য তথ্যের সম্মতি (Consent.kt, backend/docs/CONSENT.md): সার্ভারের আসল লেখা (একটাই ভার্সন করা ফাইল) পড়া, পর্দায় ছয়টা অংশ +
 * দুটো সম্মতি + "রাজি আছি"/"এখন না", শুধু-পড়ার রূপ, সেটিংস › গোপনীয়তা (অবস্থা, তুলে নেওয়ার পর্দা), 428-এর উত্তর।
 * ছবি app/build/screens-এ।
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class ConsentScreensTest {
    @get:Rule
    val rule = createComposeRule()

    /** সার্ভারের ফাইল (consent_status-এর text এটাই) */
    private val doc: JSONObject by lazy {
        val f = listOf("../backend/src/main/resources/consent/health-consent.json", "backend/src/main/resources/consent/health-consent.json")
            .map(::File).first { it.exists() }
        JSONObject(f.readText())
    }

    private fun status(state: String = "none", current: Boolean = false, at: Long? = null): JSONObject {
        val scopes = JSONObject()
        for (k in HealthConsent.SCOPES) {
            scopes.put(k, JSONObject().put("state", state).put("version", if (state == "none") JSONObject.NULL else doc.getString("version")).put("at", at ?: JSONObject.NULL).put("source", "app"))
        }
        return JSONObject().put("on", true).put("version", doc.getString("version")).put("current", current).put("ask", !current)
            .put("needed", org.json.JSONArray(if (current) emptyList() else HealthConsent.SCOPES)).put("scopes", scopes).put("text", doc.getJSONObject("bn"))
    }

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        if (FirebaseApp.getApps(ctx).isEmpty()) runCatching { FirebaseApp.initializeApp(ctx) }
        AppPrefs.lang.value = Lang.BN
        AppPrefs.theme.value = ThemeMode.LIGHT
        LoginGate.uid.value = "demo"
        CareMode.asUid.value = null
    }

    @After
    fun tearDown() {
        HealthConsent.status.value = null
        HealthConsent.prompt.value = null
        CareMode.asUid.value = null
    }

    private fun show(content: @Composable () -> Unit) {
        rule.setContent { GetBloodTheme { Surface { Column { content() } } } }
        rule.waitForIdle()
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        captureScreenRoboImage("build/screens/$name.png")
    }

    @Test
    fun readsTheServerText() {
        val s = HealthConsent.parse(status())
        assertEquals(doc.getString("version"), s.version)
        assertTrue(s.ask)
        assertEquals(HealthConsent.SCOPES, s.needed)
        assertEquals("রাজি আছি", s.text.agree)
        assertEquals("এখন না", s.text.later)
        assertEquals(listOf("what", "why", "where", "who", "how_long", "withdraw"), s.text.sections.map { it.key })
        assertTrue(s.text.sections.first { it.key == "how_long" }.linkUrl!!.startsWith("https://lifolo.com/privacy#"))
        assertEquals("রোগীকে নিজে একবার সম্মতি দিতে হবে", s.text.caregiver)
        assertEquals("none", s.scopes.getValue("health_data").state)
        assertNull(s.scopes.getValue("health_data").at)
    }

    @Test
    fun consentScreen() {
        val s = HealthConsent.parse(status())
        var later = 0
        show { HealthConsentDialog(s, readOnly = false, onAgree = {}, onLater = { later++ }) }
        rule.onNodeWithText(s.text.title).assertExists()
        for (sec in s.text.sections) rule.onNodeWithText(sec.heading).performScrollTo().assertExists()
        rule.onNodeWithText(s.text.scopes.getValue("health_data")).performScrollTo().assertExists()
        rule.onNodeWithText(s.text.scopes.getValue("data_abroad")).performScrollTo().assertExists()
        rule.onNodeWithTag("consent_agree").assertExists()
        shot("consent_screen")
        rule.onNodeWithTag("consent_later").performClick()
        assertEquals(1, later)
    }

    @Test
    fun fullTextIsReadOnlyWhenGiven() {
        val s = HealthConsent.parse(status("granted", current = true, at = 1_792_740_000_000L))
        show { HealthConsentDialog(s, readOnly = true, onAgree = {}, onLater = {}) }
        rule.onNodeWithTag("consent_agree").assertDoesNotExist()
        rule.onNodeWithText("বন্ধ").assertExists()
    }

    @Test
    fun privacySettingsAndWithdraw() {
        val s = HealthConsent.parse(status("granted", current = true, at = 1_792_740_000_000L))
        HealthConsent.status.value = s
        show { HealthConsentSettings() }
        rule.onNodeWithText("গোপনীয়তা").assertExists()
        rule.onNodeWithTag("consent_state_health_data").assertExists()
        rule.onNodeWithText("দেওয়া আছে · " + HealthConsent.dateOf(1_792_740_000_000L)).assertExists()
        rule.onNodeWithText("পুরো লেখা দেখুন").assertExists()
        shot("consent_settings")
        rule.onNodeWithTag("consent_withdraw").performClick()
        rule.onNodeWithText(s.text.withdrawBody).assertExists()
        rule.onNodeWithText(s.text.deleteAccountLabel).assertExists()
        rule.onNodeWithTag("consent_withdraw_confirm").assertExists()
        shot("consent_withdraw")
    }

    @Test
    fun notGivenSettingsOffersConsent() {
        HealthConsent.status.value = HealthConsent.parse(status("withdrawn", at = 1_792_740_000_000L))
        show { HealthConsentSettings() }
        rule.onNodeWithText("সম্মতি দিন").assertExists()
        rule.onNodeWithText("তুলে নিয়েছেন · " + HealthConsent.dateOf(1_792_740_000_000L)).assertExists()
        rule.onNodeWithTag("consent_withdraw").assertDoesNotExist()
    }

    @Test
    fun askWaitsForTheAnswer() = runBlocking {
        val r = async { HealthConsent.ask("gate") }
        while (HealthConsent.prompt.value == null) kotlinx.coroutines.yield()
        assertEquals("gate", HealthConsent.prompt.value!!.reason)
        HealthConsent.answer(true)
        assertTrue(r.await())
        assertNull(HealthConsent.prompt.value)
        // a caregiver never sees the screen
        assertFalse(HealthConsent.onRequired(JSONObject().put("caregiver", true)))
        assertNull(HealthConsent.prompt.value)
    }

    @Test
    fun serverErrorCarriesTheConsentFields() {
        val e = ServerError("x", HealthConsent.REQUIRED_CODE, JSONObject().put("scopes", org.json.JSONArray(HealthConsent.SCOPES)).put("caregiver", false))
        assertEquals(2, e.data!!.getJSONArray("scopes").length())
        assertEquals("সেভ হয়নি। এর জন্য স্বাস্থ্য তথ্যের সম্মতি লাগে।", HealthConsent.declinedText())
        assertTrue(Features.HEALTH_CONSENT in Features.KEYS)
    }
}
