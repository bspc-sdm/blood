package com.bspc.blood

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
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
 * আমার প্রেসক্রিপশন — এখন কোন অবস্থায় (RxStatus.kt, backend/docs/RX-STATE.md): সার্ভারের state পড়া, চাবি → লেখা (বাংলা/ইংরেজি),
 * চিপের এক লাইন আর ছোট লাইন, ট্যাব চলমান/পূর্বের/সব (গুনতি, খালি চলমান), পুরোনো সার্ভার (state নেই), টাইমলাইন।
 * ছবি app/build/screens/rx-status-*.png (৩৬০dp, বাংলা আর ইংরেজি)।
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class RxStatusScreensTest {
    @get:Rule
    val rule = createComposeRule()

    private val day = 24 * 3600 * 1000L
    private val now = System.currentTimeMillis()
    private val today = Rules.todayUtcMidnight()
    private val profile = Profile(name = "ডেমো রোগী রহিম", phone = "01700001101", placeName = "গাজীপুর", locationSource = "district")

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

    // ---------------- নকল সার্ভারের তথ্য ----------------

    private fun med(state: String, bought: Int = 0, total: Int = 3) =
        JSONObject().put("state", state).put("bought", bought).put("total", total).put("pharmacyName", JSONObject.NULL).put("mode", JSONObject.NULL)

    private fun lab(state: String, orders: Int = 1, reported: Int = 0, reviewed: Boolean = false) =
        JSONObject().put("state", state).put("orders", orders).put("reported", reported).put("labName", "সিটি ল্যাব").put("reviewed", reviewed)

    private fun state(primary: String, tone: String, phase: String = "active", medicine: JSONObject? = null, lab: JSONObject? = null, followUp: Long? = null, cancelled: Boolean = false) =
        JSONObject().put("phase", phase).put("primary", primary).put("tone", tone).put("cancelled", cancelled).put("cancelledAt", if (cancelled) now - day else JSONObject.NULL)
            .put("medicine", medicine ?: JSONObject.NULL).put("lab", lab ?: JSONObject.NULL).put("followUpDate", followUp ?: JSONObject.NULL).put("lastActivityAt", now)

    private fun rxJson(id: String, doctor: String, ago: Int, state: JSONObject?, status: String = "active", meds: Boolean = true) = JSONObject()
        .put("id", id).put("code", "ABCD2345EF").put("status", status).put("createdAt", now - ago * day).put("doctorUid", "d1")
        .put("doctor", JSONObject().put("name", doctor).put("degrees", "এমবিবিএস").put("regNo", "A-1").put("specialties", JSONArray()))
        .put("patient", JSONObject().put("name", "ডেমো রোগী রহিম").put("age", 34).put("gender", "male"))
        .put("medicines", JSONArray(if (meds) listOf(RxMed("Napa", form = "Tablet", dose = "1+0+1", days = 5).toJson(), RxMed("Seclo", form = "Capsule", dose = "1+0+1", days = 14).toJson()) else emptyList()))
        .put("tests", JSONArray(listOf("CBC")))
        .apply { if (state != null) put("state", state) }

    private fun list(withState: Boolean = true): List<Rx> = listOf(
        rxJson("a", "ডা. সাবরিনা হক", 30, state("all_bought", "good", "past", med("all_bought", 3, 3))),
        rxJson("b", "ডা. ফারহানা ইয়াসমিন", 1, state("sent", "primary", medicine = med("sent"), lab = lab("pending", 0), followUp = today + 14 * day)),
        rxJson("c", "ডা. তানভীর রহমান", 6, state("partial", "info", medicine = med("partial", 3, 5), lab = lab("received"))),
        rxJson("d", "ডা. মাহমুদুল হাসান", 12, state("running_out", "error", medicine = med("running_out", 2, 3))),
        rxJson("e", "ডা. নুসরাত জাহান", 3, state("out", "primary", medicine = med("out"), lab = lab("reported", reported = 1, reviewed = true))),
        rxJson("f", "ডা. কামরুল ইসলাম", 60, state("cancelled", "muted", "past", med("not_bought"), cancelled = true), status = "cancelled"),
        rxJson("g", "ডা. রেহানা পারভীন", 40, state("lab_reported", "good", "past", lab = lab("reported", reported = 1)), meds = false),
    ).map { Rx.parse(if (withState) it else it.apply { remove("state") }) }

    /** একটা লেখার উপরের কিনারা (ক্রম মেলাতে) */
    private fun top(text: String): Float = rule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top

    private fun screen(items: List<Rx>) {
        rule.setContent { GetBloodTheme { Surface { MyPrescriptionsScreen(profile, onBack = {}, load = { items }) } } }
        rule.waitForIdle()
    }

    // ---------------- লেখা (Compose ছাড়া) ----------------

    @Test
    fun stateParsedAndTexts() {
        val rx = list().first { it.id == "c" }
        val s = rx.state!!
        assertEquals("active", s.phase)
        assertEquals(RxStateMed("partial", 3, 5, "", ""), s.medicine)
        assertEquals("received", s.lab!!.state)
        assertEquals("আংশিক কেনা (৩/৫)" to "info", RxStateText.primary(rx))
        assertEquals(listOf(R.drawable.ic_list_alt to "নমুনা নেওয়া হয়েছে"), RxStateText.secondary(rx))
        // ওষুধের প্রতিটা অবস্থা
        val texts = mapOf(
            "not_bought" to "ওষুধ কেনা হয়নি", "sent" to "ফার্মেসিতে পাঠানো — উত্তরের অপেক্ষা", "accepted" to "ফার্মেসি গ্রহণ করেছে",
            "ready" to "ওষুধ তৈরি — দোকান থেকে নিন", "out" to "ডেলিভারির পথে", "all_bought" to "সব ওষুধ কেনা হয়েছে",
            "running_out" to "ওষুধ শেষ হয়ে আসছে", "declined" to "ফার্মেসি দিতে পারেনি — অন্য ফার্মেসিতে পাঠান",
        )
        for ((k, t) in texts) assertEquals(k, t, RxStateText.medText(RxStateMed(k, 0, 3, "", "")))
        assertEquals("ল্যাবে পাঠানো · ১/৩টা রিপোর্ট এসেছে", RxStateText.labText(RxStateLab("sent", 3, 1, "", false)))
        assertEquals("রিপোর্ট এসেছে · ডাক্তার দেখেছেন", RxStateText.labText(RxStateLab("reported", 1, 1, "", true)))
        assertEquals("পরীক্ষা বাকি", RxStateText.labText(RxStateLab("pending", 0, 0, "", false)))
        // বাতিল: ধূসর চিপ, ছোট লাইন নেই
        val f = list().first { it.id == "f" }
        assertEquals("বাতিল করেছেন ডাক্তার" to "muted", RxStateText.primary(f))
        assertTrue(RxStateText.secondary(f).isEmpty())
        // ওষুধ নেই: পরীক্ষাই মূল লাইন
        assertEquals("রিপোর্ট এসেছে" to "good", RxStateText.primary(list().first { it.id == "g" }))
        // ট্যাব
        assertEquals("চলমান (৩)", RxStateText.tabLabel(RxTab.ACTIVE, 3))
        assertEquals("পূর্বের (১২)", RxStateText.tabLabel(RxTab.PAST, 12))
        assertEquals(listOf(4, 3, 7), RxTab.entries.map { t -> list().count { RxStateText.inTab(it, t) } })
        AppPrefs.lang.value = Lang.EN
        assertEquals("Ongoing (3)", RxStateText.tabLabel(RxTab.ACTIVE, 3))
        assertEquals("Previous (1)", RxStateText.tabLabel(RxTab.PAST, 1))
        assertEquals("All (7)", RxStateText.tabLabel(RxTab.ALL, 7))
    }

    @Test
    fun oldServerWithoutState() {
        val old = list(withState = false)
        assertTrue(old.all { it.state == null })
        // বাতিল = পূর্বের, বাকিগুলো চলমান; চিপ শুধু বাতিলে
        assertEquals(listOf("f"), old.filter { RxStateText.past(it) }.map { it.id })
        assertNull(RxStateText.primary(old.first { it.id == "b" }))
        assertEquals("বাতিল করেছেন ডাক্তার" to "muted", RxStateText.primary(old.first { it.id == "f" }))
    }

    @Test
    fun timelineParsedAndTexts() {
        val st = JSONObject(state("sent", "primary", medicine = med("sent")).toString()).put(
            "timeline",
            JSONArray()
                .put(JSONObject().put("kind", "written").put("at", now - day))
                .put(JSONObject().put("kind", "pharmacy_sent").put("at", now - 3600_000).put("name", "লাইফ কেয়ার ফার্মেসি").put("mode", "shop"))
                .put(JSONObject().put("kind", "bought").put("at", now).put("name", "লাইফ কেয়ার ফার্মেসি").put("count", 2))
                .put(JSONObject().put("kind", "follow_up").put("at", today + 14 * day).put("ahead", true)),
        )
        val s = RxState.parse(st)!!
        assertEquals(listOf("written", "pharmacy_sent", "bought", "follow_up"), s.timeline!!.map { it.kind })
        assertEquals("ফার্মেসিতে পাঠানো — লাইফ কেয়ার ফার্মেসি", RxStateText.stepText(s.timeline!![1]))
        assertEquals("২টা ওষুধ কেনা হয়েছে — লাইফ কেয়ার ফার্মেসি", RxStateText.stepText(s.timeline!![2]))
        assertEquals("ফলো-আপে দেখানোর দিন", RxStateText.stepText(s.timeline!![3]))
        assertEquals(Rules.formatDate(today + 14 * day), RxStateText.stepDate(s.timeline!![3]))
        assertFalse(s.timeline!![0].ahead)
    }

    // ---------------- পর্দা ----------------

    @Test
    fun listTabsBangla() {
        screen(list())
        // চলমান শুরুতে, গুনতি বাংলা অঙ্কে
        rule.onNodeWithText("চলমান (৪)").assertIsSelected()
        rule.onNodeWithText("পূর্বের (৩)").assertExists()
        rule.onNodeWithText("সব (৭)").assertExists()
        // নতুনটা আগে: কালকেরটা (ফারহানা) পরশুর (নুসরাত) উপরে
        assertTrue(top("ডা. ফারহানা ইয়াসমিন") < top("ডা. নুসরাত জাহান"))
        rule.onNodeWithText("ফার্মেসিতে পাঠানো — উত্তরের অপেক্ষা", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("পরীক্ষা বাকি", useUnmergedTree = true).assertExists()
        rule.onAllNodesWithText("বাতিল করেছেন ডাক্তার", useUnmergedTree = true).assertCountEquals(0)
        shot("rx-status-list-bn")
        // পূর্বের: শেষ আর বাতিল
        rule.onNodeWithText("পূর্বের (৩)").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("পূর্বের (৩)").assertIsSelected()
        rule.onNodeWithText("বাতিল করেছেন ডাক্তার", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("সব ওষুধ কেনা হয়েছে", useUnmergedTree = true).assertExists()
        shot("rx-status-past-bn")
        rule.onNodeWithText("সব (৭)").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("সব (৭)").assertIsSelected()
        assertTrue(top("ডা. ফারহানা ইয়াসমিন") < top("ডা. নুসরাত জাহান"))
    }

    @Test
    fun listTabsEnglish() {
        AppPrefs.lang.value = Lang.EN
        screen(list())
        rule.onNodeWithText("Ongoing (4)").assertIsSelected()
        rule.onNodeWithText("Previous (3)").assertExists()
        rule.onNodeWithText("All (7)").assertExists()
        rule.onNodeWithText("(3/5)", substring = true, useUnmergedTree = true).assertExists()
        shot("rx-status-list-en")
    }

    @Test
    fun emptyOngoingShowsLinkToPrevious() {
        screen(list().filter { RxStateText.past(it) })
        rule.onNodeWithText("চলমান (০)").assertIsSelected()
        rule.onNodeWithTag("rx-tab-empty").assertExists()
        rule.onNodeWithText("এখন চলমান কোনো প্রেসক্রিপশন নেই।").assertExists()
        shot("rx-status-empty-bn")
        rule.onNodeWithText("পূর্বেরগুলো দেখুন (৩)", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("পূর্বের (৩)").assertIsSelected()
    }

    @Test
    fun darkThemeList() {
        AppPrefs.theme.value = ThemeMode.DARK
        screen(list())
        rule.onNodeWithText("চলমান (৪)").assertIsSelected()
        shot("rx-status-list-bn-dark")
        AppPrefs.theme.value = ThemeMode.LIGHT
    }

    private fun timelineRx(): Rx {
        val st = state("sent", "primary", medicine = med("sent"), lab = lab("sent", 2, 1), followUp = today + 14 * day).put(
            "timeline",
            JSONArray()
                .put(JSONObject().put("kind", "written").put("at", now - 2 * day))
                .put(JSONObject().put("kind", "pharmacy_sent").put("at", now - 2 * day + 3600_000).put("name", "লাইফ কেয়ার ফার্মেসি").put("mode", "shop"))
                .put(JSONObject().put("kind", "pharmacy_cancelled").put("at", now - 2 * day + 7200_000).put("name", "লাইফ কেয়ার ফার্মেসি").put("mode", "shop"))
                .put(JSONObject().put("kind", "lab_sent").put("at", now - day).put("name", "সিটি ডায়াগনস্টিক সেন্টার"))
                .put(JSONObject().put("kind", "lab_reported").put("at", now - 3600_000).put("name", "সিটি ডায়াগনস্টিক সেন্টার"))
                .put(JSONObject().put("kind", "pharmacy_sent").put("at", now - 1800_000).put("name", "মেডিপ্লাস ফার্মেসি").put("mode", "online"))
                .put(JSONObject().put("kind", "follow_up").put("at", today + 14 * day).put("ahead", true)),
        )
        return Rx.parse(rxJson("b", "ডা. ফারহানা ইয়াসমিন", 2, st))
    }

    @Test
    fun timelineCard() {
        rule.setContent {
            GetBloodTheme { Surface { Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { RxTimelineCard(timelineRx()) } } }
        }
        rule.waitForIdle()
        rule.onAllNodesWithTag("rx-step", useUnmergedTree = true).assertCountEquals(7)
        rule.onNodeWithText("ফার্মেসি দিতে পারেনি — লাইফ কেয়ার ফার্মেসি", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("ল্যাবে পাঠানো · ১/২টা রিপোর্ট এসেছে", useUnmergedTree = true).assertExists()
        shot("rx-status-timeline-bn")
    }

    @Test
    fun timelineCardEnglish() {
        AppPrefs.lang.value = Lang.EN
        rule.setContent {
            GetBloodTheme { Surface { Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { RxTimelineCard(timelineRx()) } } }
        }
        rule.waitForIdle()
        rule.onNodeWithText("where it is now", substring = true, ignoreCase = true, useUnmergedTree = true).assertExists()
        shot("rx-status-timeline-en")
    }
}
