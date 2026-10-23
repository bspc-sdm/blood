package com.bspc.blood

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
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
 * প্রেসক্রিপশন বাতিলের কারণ (RxCancel.kt, backend/docs/RX-STATE.md "Cancellation reason"): সার্ভারের cancelReason/cancelNote
 * পড়া, কার্ডের চিপ "বাতিল করেছেন ডাক্তার — <কারণ>", টাইমলাইনে কারণ আর নোট, ডাক্তারের ডায়ালগ (কারণ → নোট → নিশ্চিত → rx_cancel)।
 * ছবি app/build/screens/rx-cancel-*.png (৩৬০dp)।
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class RxCancelScreensTest {
    @get:Rule
    val rule = createComposeRule()

    private val day = 24 * 3600 * 1000L
    private val now = System.currentTimeMillis()
    private val profile = Profile(name = "ডেমো রোগী রহিম", phone = "01700001101", placeName = "গাজীপুর", locationSource = "district")
    private val note = "রিপোর্টে কিডনির সমস্যা — Napa-র মাত্রা কমিয়ে নতুন প্রেসক্রিপশন দেওয়া হলো"

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        if (FirebaseApp.getApps(ctx).isEmpty()) runCatching { FirebaseApp.initializeApp(ctx) }
        AppPrefs.lang.value = Lang.BN
        AppPrefs.theme.value = ThemeMode.LIGHT
        CareMode.asUid.value = null
        Practice.testKeys = mapOf(Practice.SYSTEMS to false, Practice.PHYSIO to false, Practice.PSYCH to false)
    }

    @After
    fun tearDown() {
        Practice.testKeys = null
        AppPrefs.lang.value = Lang.BN
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        captureScreenRoboImage("build/screens/$name.png")
    }

    private fun cancelledState(reason: String?, note: String?, timeline: Boolean = false) = JSONObject()
        .put("phase", "past").put("primary", "cancelled").put("tone", "muted").put("cancelled", true).put("cancelledAt", now - day)
        .put("cancelReason", reason ?: JSONObject.NULL).put("cancelNote", note ?: JSONObject.NULL)
        .put("medicine", JSONObject.NULL).put("lab", JSONObject.NULL).put("followUpDate", JSONObject.NULL).put("lastActivityAt", now - day)
        .apply {
            if (timeline) {
                put(
                    "timeline",
                    JSONArray()
                        .put(JSONObject().put("kind", "written").put("at", now - 3 * day))
                        .put(JSONObject().put("kind", "cancelled").put("at", now - day).apply { if (reason != null) put("reason", reason); if (note != null) put("note", note) }),
                )
            }
        }

    private fun rxJson(id: String, doctor: String, ago: Int, state: JSONObject, status: String = "cancelled") = JSONObject()
        .put("id", id).put("code", "ABCD2345EF").put("status", status).put("createdAt", now - ago * day).put("doctorUid", "d1")
        .put("doctor", JSONObject().put("name", doctor).put("degrees", "এমবিবিএস").put("regNo", "A-1").put("specialties", JSONArray()))
        .put("patient", JSONObject().put("name", "ডেমো রোগী রহিম").put("age", 34).put("gender", "male"))
        .put("medicines", JSONArray(listOf(RxMed("Napa", form = "Tablet", dose = "1+1+1", days = 5).toJson())))
        .put("tests", JSONArray()).put("state", state)

    private fun list(): List<Rx> = listOf(
        rxJson("c1", "ডা. কামরুল ইসলাম", 3, cancelledState("patient_reported", note)),
        rxJson("c2", "ডা. রেহানা পারভীন", 9, cancelledState(null, null)),
    ).map { Rx.parse(it) }

    // ---------------- লেখা (Compose ছাড়া) ----------------

    @Test
    fun reasonParsedAndTexts() {
        val (a, b) = list()
        assertEquals("patient_reported", a.state!!.cancelReason)
        assertEquals(note, a.state!!.cancelNote)
        assertEquals("বাতিল করেছেন ডাক্তার — রোগী জানিয়েছেন সমস্যা" to "muted", RxStateText.primary(a))
        // কারণ নেই (আগের release-এ বাতিল): শুধু প্রথম অংশ
        assertEquals("", b.state!!.cancelReason)
        assertEquals("বাতিল করেছেন ডাক্তার" to "muted", RxStateText.primary(b))
        assertEquals(
            listOf("ভুল ওষুধ/মাত্রা", "ভুল রোগী", "নতুন প্রেসক্রিপশন দিয়েছি", "রোগী জানিয়েছেন সমস্যা", "অন্য কারণ"),
            RxCancel.REASONS.map { RxCancel.reasonText(it) },
        )
        assertNull(RxCancel.reasonText("something_new"))
        // টাইমলাইনের ধাপ
        val s = RxState.parse(cancelledState("wrong_medicine", note, timeline = true))!!
        val step = s.timeline!!.last()
        assertEquals("wrong_medicine", step.reason)
        assertEquals(note, step.note)
        assertEquals("বাতিল করেছেন ডাক্তার — ভুল ওষুধ/মাত্রা", RxStateText.stepText(step))
        // নোটের নিয়ম
        assertFalse(RxCancel.noteOk(null, "abc"))
        assertFalse(RxCancel.noteOk("other", " ab "))
        assertTrue(RxCancel.noteOk("other", "abc"))
        assertFalse(RxCancel.noteOk("other", "ক".repeat(201)))
        assertTrue(RxCancel.noteOk("wrong_patient", ""))
        AppPrefs.lang.value = Lang.EN
        assertTrue(RxCancel.cancelledLine("replaced").contains("—"))
    }

    // ---------------- পর্দা ----------------

    @Test
    fun patientCardShowsReason() {
        rule.setContent { GetBloodTheme { Surface { MyPrescriptionsScreen(profile, onBack = {}, load = { list() }) } } }
        rule.waitForIdle()
        rule.onNodeWithText("পূর্বের (২)").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("পূর্বের (২)").assertIsSelected()
        rule.onNodeWithText("বাতিল করেছেন ডাক্তার — রোগী জানিয়েছেন সমস্যা", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("বাতিল করেছেন ডাক্তার", useUnmergedTree = true).assertExists()
        // কার্ডে নোট নেই — শুধু ভেতরে
        rule.onNodeWithText(note, useUnmergedTree = true).assertDoesNotExist()
        shot("rx-cancel-list-bn")
    }

    @Test
    fun timelineShowsReasonAndNote() {
        val rx = Rx.parse(rxJson("c1", "ডা. কামরুল ইসলাম", 3, cancelledState("patient_reported", note, timeline = true)))
        rule.setContent {
            GetBloodTheme { Surface { Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { RxTimelineCard(rx) } } }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("rx-cancel-note", useUnmergedTree = true).assertExists()
        rule.onNodeWithText(note, useUnmergedTree = true).assertExists()
        shot("rx-cancel-timeline-bn")
    }

    @Test
    fun doctorDialogReasonNoteConfirm() {
        val sent = mutableListOf<Triple<String, String, String?>>()
        var done = false
        rule.setContent {
            GetBloodTheme {
                Surface { RxCancelDialog("rx1", onDismiss = {}, onDone = { done = true }, send = { id, r, n -> sent += Triple(id, r, n) }) }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("কেন বাতিল করছেন?").assertExists()
        rule.onNodeWithTag("rx-cancel-next").assertIsNotEnabled()
        // "অন্য কারণ": নোট ছাড়া এগোনো যায় না
        rule.onNodeWithTag("rx-cancel-other").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("rx-cancel-other").assertIsSelected()
        rule.onNodeWithTag("rx-cancel-next").assertIsNotEnabled()
        rule.onNodeWithTag("rx-cancel-note").performTextInput("ab")
        rule.waitForIdle()
        rule.onNodeWithText("অন্তত ৩ অক্ষর", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("rx-cancel-next").assertIsNotEnabled()
        // অন্য কারণে নোট ঐচ্ছিক
        rule.onNodeWithTag("rx-cancel-patient_reported").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("rx-cancel-next").assertIsEnabled()
        rule.onNodeWithTag("rx-cancel-note").performTextInput("c — মাথা ঘোরে")
        shot("rx-cancel-dialog-bn")
        rule.onNodeWithTag("rx-cancel-next").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("rx-cancel-summary", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("রোগী জানিয়েছেন সমস্যা", useUnmergedTree = true).assertExists()
        assertTrue(sent.isEmpty())
        shot("rx-cancel-confirm-bn")
        rule.onNodeWithTag("rx-cancel-confirm").performClick()
        rule.waitForIdle()
        assertEquals(listOf(Triple("rx1", "patient_reported", "abc — মাথা ঘোরে")), sent)
        assertTrue(done)
    }

    @Test
    fun doctorDialogEnglishDark() {
        AppPrefs.lang.value = Lang.EN
        AppPrefs.theme.value = ThemeMode.DARK
        rule.setContent { GetBloodTheme { Surface { RxCancelDialog("rx1", onDismiss = {}, onDone = {}, send = { _, _, _ -> }) } } }
        rule.waitForIdle()
        rule.onNodeWithTag("rx-cancel-wrong_medicine").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("rx-cancel-next").assertIsEnabled()
        shot("rx-cancel-dialog-en-dark")
        AppPrefs.theme.value = ThemeMode.LIGHT
    }
}
