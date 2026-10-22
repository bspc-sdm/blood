package com.bspc.blood

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.google.firebase.FirebaseApp
import org.json.JSONObject
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
 * অনলাইন (ভিডিও) চেম্বার (OnlineChamber.kt): ডাক্তারের সেটআপ (ধরন, bKash যাচাই), রোগীর ফি পাঠানো (নম্বর কপি, ধাপ,
 * "টাকা পাঠিয়েছি" + TrxID), ডাক্তার/সহকারীর "ফি পেয়েছি"/"পাইনি" (নিশ্চিত করার ধাপ), আর "ভিডিও কলে যুক্ত হোন" শুধু সময়ের মধ্যে।
 * ছবি app/build/screens-এ।
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class OnlineChamberScreensTest {
    @get:Rule
    val rule = createComposeRule()

    /** ২৫ অক্টোবর ২০২৬ (UTC মধ্যরাত); সময় ১৭:০০ ঢাকা = ১১:০০ UTC */
    private val date = 1792886400000L
    private val slot = date + 11 * 3_600_000L
    private val min = 60_000L

    private val unpaid = OnlinePay("unpaid", "", "01711223344", "৫০০ টাকা", false, "আগের রিপোর্ট হাতের কাছে রাখবেন")

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
        rule.setContent {
            GetBloodTheme { Surface { Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { content() } } }
        }
        rule.waitForIdle()
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        captureScreenRoboImage("build/screens/$name.png")
    }

    // ---------------- হিসাব ----------------

    @Test
    fun bkashValidation() {
        assertEquals("01711223344", OnlineChambers.cleanNumber("০১৭১১-২২৩ ৩৪৪"))
        assertTrue(OnlineChambers.bkashOk("01711223344", demo = false))
        assertTrue(OnlineChambers.bkashOk("+8801711223344", demo = false))
        assertFalse(OnlineChambers.bkashOk("0171122334", demo = false))
        assertFalse(OnlineChambers.bkashOk("01211223344", demo = false))
        // ডেমোর নম্বর শুধু ডেমো অ্যাকাউন্টে
        assertTrue(OnlineChambers.bkashOk("01200001234", demo = true))
        assertFalse(OnlineChambers.bkashOk("01200001234", demo = false))

        val c = Chamber.onlineNew().copy(fee = "500")
        assertEquals("রোগীরা যে bKash নম্বরে ফি পাঠাবেন, সেটা দিন", OnlineChambers.problem(c, demo = false))
        assertEquals("সঠিক bKash নম্বর দিন (01XXXXXXXXX)", OnlineChambers.problem(c.copy(bkashNumber = "0171"), demo = false))
        assertNull(OnlineChambers.problem(c.copy(bkashNumber = "01711223344"), demo = false))
        // ফি না নিলে নম্বর লাগে না
        assertNull(OnlineChambers.problem(c.copy(noFee = true, fee = ""), demo = false))
        // চেম্বারে (হাসপাতালে) bKash দেখা হয় না
        assertNull(OnlineChambers.problem(Chamber.onlineNew().let(OnlineChambers::toInPerson), demo = false))
    }

    @Test
    fun chamberJson() {
        val c = OnlineChambers.toOnline(
            Chamber("dghs_1", "X", "এক্স", "গাজীপুর", 1, 2, "রুম ৫", "", "500", false, "01700000000"),
        ).copy(bkashNumber = "01711223344", note = "নোট")
        assertTrue(c.online)
        assertTrue(c.appointments)
        assertEquals("", c.area)
        assertEquals("", c.room)
        val o = c.toJson()
        assertEquals("online", o.getString("mode"))
        assertTrue(o.isNull("hospitalId"))
        assertEquals("01711223344", o.getString("bkashNumber"))
        assertEquals("নোট", o.getString("note"))
        val back = Chamber.parse(JSONObject(o.toString()).put("hospitalName", OnlineChambers.NAME_EN).put("hospitalNameBn", OnlineChambers.NAME_BN))
        assertTrue(back.online)
        assertEquals("01711223344", back.bkashNumber)
        assertEquals(OnlineChambers.NAME_BN, back.label)
        assertEquals("অনলাইন (ভিডিও)", ChamberClock.place(back))
        // পুরোনো চেম্বার (mode নেই) = চেম্বারে, আর তার JSON-এ mode যায় না
        val old = Chamber.parse(JSONObject().put("hospitalId", "dghs_1").put("hospitalName", "X"))
        assertFalse(old.online)
        assertFalse(old.toJson().has("mode"))
    }

    @Test
    fun payParse() {
        val o = JSONObject().put("online", true).put("payState", "claimed").put("payRef", "8N7A6B").put("fee", "500").put("bkashNumber", "01711223344")
        val p = OnlinePay.parse(o)
        assertEquals(PayView.CLAIMED, p.view)
        assertEquals("8N7A6B", p.ref)
        assertEquals("৳৫০০", p.feeShort)
        assertEquals(PayView.UNPAID, OnlinePay.parse(JSONObject()).view)
        assertEquals(PayView.PAID, OnlinePay.parse(JSONObject().put("payState", "paid")).view)
        assertEquals(PayView.NO_FEE, OnlinePay.parse(JSONObject().put("payState", "unpaid").put("noFee", true)).view)
        val r = OnlinePay.parse(JSONObject().put("payState", "unpaid").put("payRejectReason", "TrxID মেলেনি"))
        assertEquals(PayView.REJECTED, r.view)
        assertEquals("TrxID মেলেনি", r.rejectReason)
        assertEquals(PayView.REJECTED, OnlinePay.parse(JSONObject().put("payState", "rejected")).view)
        assertEquals("পাঠানো হয়নি", OnlineText.payLabel(PayView.UNPAID))
        assertEquals("যাচাই হচ্ছে", OnlineText.payLabel(PayView.CLAIMED))
        assertEquals("ফি পরিশোধিত", OnlineText.payLabel(PayView.PAID))
        assertEquals("ডাক্তার পাননি", OnlineText.payLabel(PayView.REJECTED))
    }

    // ---------------- ডাক্তারের সেটআপ ----------------

    @Test
    fun setupOnlineChamber() {
        show {
            var c by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(Chamber.onlineNew().copy(fee = "৫০০ টাকা")) }
            ChamberEditor(c, onChange = { c = it }, onChangeHospital = {}, onRemove = {}, onMode = {}, onlineAllowed = true)
        }
        rule.onNodeWithText("চেম্বারে").assertExists()
        rule.onNodeWithText("অনলাইন (ভিডিও)").assertExists()
        rule.onNodeWithText("অনলাইন (ভিডিও) চেম্বার").assertExists()
        // হাসপাতাল/রুম/সিরিয়ালের ফোন নেই, bKash আর নোট আছে
        rule.onNodeWithText("রুম (ঐচ্ছিক)").assertDoesNotExist()
        rule.onNodeWithText("সিরিয়ালের ফোন (ঐচ্ছিক)").assertDoesNotExist()
        rule.onNodeWithTag("online_bkash").assertExists()
        rule.onNodeWithTag("online_note").assertExists()
        shot("online_chamber_setup_360_bn")
    }

    @Test
    fun setupBadBkash() {
        show {
            ChamberEditor(Chamber.onlineNew().copy(fee = "500", bkashNumber = "01712"), onChange = {}, onChangeHospital = {}, onRemove = {}, onMode = {})
        }
        rule.onNodeWithText("সঠিক নম্বর দিন: 01XXXXXXXXX").assertExists()
    }

    @Test
    fun setupTypeChooser() {
        show { ChamberTypeDialog(onlineAllowed = false, onInPerson = {}, onOnline = {}, onDismiss = {}) }
        rule.onNodeWithText("চেম্বারের ধরন").assertExists()
        rule.onNodeWithText("অনলাইন চেম্বার একটাই রাখা যায় — আগেরটা আছে").assertExists()
        shot("online_chamber_type_360_bn")
    }

    // ---------------- রোগী ----------------

    @Test
    fun patientUnpaid() {
        show { PatientOnlinePanel("a1", "confirmed", date, "17:00", unpaid, onChanged = {}, now = slot - 60 * min) }
        rule.onNodeWithText("পাঠানো হয়নি").assertExists()
        rule.onNodeWithText("01711223344").assertExists()
        rule.onNodeWithTag("copy_bkash").assertExists()
        rule.onNodeWithText("ফি: ৳৫০০").assertExists()
        rule.onNodeWithText("bKash-এর মেসেজের TrxID রেখে দিন").assertExists()
        // সময়ের এক ঘণ্টা আগে: বোতাম নয়, "সময় হলে এখানে বোতাম আসবে"
        rule.onNodeWithText("সময় হলে এখানে বোতাম আসবে").assertExists()
        rule.onNodeWithTag("appt_join").assertDoesNotExist()
        shot("online_patient_unpaid_360_bn")
        rule.onNodeWithText("টাকা পাঠিয়েছি").performClick()
        rule.onNodeWithTag("pay_trxid").assertExists()
        rule.onNodeWithText("ডাক্তারকে জানান").assertIsEnabled()
        shot("online_patient_claim_dialog_360_bn")
    }

    @Test
    fun patientStates() {
        show {
            PatientOnlinePanel("a1", "confirmed", date, "17:00", unpaid.copy(state = "claimed", ref = "8N7A6B5C4D"), onChanged = {}, now = slot)
            PatientOnlinePanel("a2", "confirmed", date, "17:00", unpaid.copy(state = "paid", ref = "8N7A6B5C4D"), onChanged = {}, now = slot)
            PatientOnlinePanel("a3", "confirmed", date, "17:00", unpaid.copy(rejected = true, rejectReason = "TrxID মেলেনি"), onChanged = {}, now = slot)
        }
        rule.onNodeWithText("যাচাই হচ্ছে").assertExists()
        rule.onNodeWithText("ফি পরিশোধিত").assertExists()
        rule.onNodeWithText("ডাক্তার পাননি").assertExists()
        rule.onNodeWithText("ডাক্তার টাকা পাননি: TrxID মেলেনি").assertExists()
        rule.onNodeWithText("আবার পাঠিয়েছি").assertExists()
        shot("online_patient_states_360_bn")
    }

    // ---------------- ডাক্তার/সহকারী ----------------

    @Test
    fun staffConfirm() {
        show { StaffOnlinePanel("a1", "confirmed", date, "17:00", unpaid.copy(state = "claimed", ref = "8N7A6B5C4D", bkashNumber = ""), onChanged = {}, now = slot) }
        rule.onNodeWithText("ফি যাচাই করুন").assertExists()
        rule.onNodeWithText("TrxID: 8N7A6B5C4D").assertExists()
        rule.onNodeWithText("পাইনি").assertExists()
        rule.onNodeWithTag("appt_join").assertExists()
        rule.onNodeWithText("ভিডিও কলে যুক্ত হোন").assertExists()
        shot("online_staff_claimed_360_bn")
        // "ফি পেয়েছি" → আগে নিশ্চিত করার ধাপ
        rule.onNodeWithText("ফি পেয়েছি").performClick()
        rule.onNodeWithText("ফি পেয়েছেন?").assertExists()
        rule.onNodeWithText("হ্যাঁ, পেয়েছি").assertExists()
        shot("online_staff_confirm_360_bn")
        rule.onNodeWithText("বাতিল").performClick()
        rule.onNodeWithText("পাইনি").performClick()
        rule.onNodeWithText("ফি পাননি?").assertExists()
        rule.onNodeWithText("কারণ (ঐচ্ছিক)").assertExists()
    }

    @Test
    fun staffPaidAndUnpaid() {
        show {
            StaffOnlinePanel("a1", "confirmed", date, "17:00", unpaid.copy(state = "paid", ref = "X1"), onChanged = {}, now = slot)
            StaffOnlinePanel("a2", "confirmed", date, "17:00", unpaid.copy(bkashNumber = ""), onChanged = {}, now = slot)
        }
        rule.onNodeWithText("ফি পরিশোধিত").assertExists()
        // পাঠানো না হলে "পাইনি" নেই, "ফি পেয়েছি" আছে (হাতে পেলে)
        rule.onNodeWithText("ফি পাঠানো হয়নি").assertExists()
        rule.onNodeWithText("পাইনি").assertDoesNotExist()
        rule.onNodeWithText("ফি পেয়েছি").assertExists()
    }

    // ---------------- ভিডিও কলের সময় ----------------

    @Test
    fun joinWindow() {
        assertTrue(CallWindows.canJoin("confirmed", date, "17:00", slot - 15 * min))
        assertFalse(CallWindows.canJoin("confirmed", date, "17:00", slot - 16 * min))
        assertTrue(CallWindows.canJoin("confirmed", date, "17:00", slot + 179 * min))
        assertFalse(CallWindows.canJoin("confirmed", date, "17:00", slot + 180 * min))
        assertFalse(CallWindows.canJoin("pending", date, "17:00", slot))
        assertEquals("আজ, বিকাল ৪:৪৫", joinOpensText(slot - 15 * min, slot - 60 * min))
    }

    @Test
    fun joinBox() {
        show {
            VideoJoinBox("a1", "confirmed", date, "17:00", now = slot - 60 * min)
            VideoJoinBox("a2", "confirmed", date, "17:00", now = slot + 4 * 60 * min)
            VideoJoinBox("a3", "pending", date, "17:00", now = slot)
        }
        rule.onNodeWithText("সময় হলে এখানে বোতাম আসবে").assertExists()
        rule.onNodeWithText("আজ, বিকাল ৪:৪৫ থেকে").assertExists()
        rule.onNodeWithText("ভিডিও কলের সময় পেরিয়ে গেছে").assertExists()
        rule.onNodeWithText("ডাক্তার নিশ্চিত করলে ভিডিও কলের বোতাম আসবে।").assertExists()
        rule.onNodeWithTag("appt_join").assertDoesNotExist()
    }

    // ---------------- তালিকা ----------------

    @Test
    fun listBadge() {
        val online = Chamber.onlineNew().copy(fee = "৫০০ টাকা").withSlots(listOf(ChamberTime(listOf(6, 0, 1, 2, 3, 4, 5), "20:00", "22:00")))
        val doc = Provider("d1", "doctor", "ডা. ডেমো হাসান", listOf("medicine"), "এমবিবিএস", "male", true, true, listOf(online), null, 4.5, 3)
        show { ProviderCard(doc, CareFilter(online = true)) {} }
        rule.onNodeWithText("অনলাইন").assertExists()
        rule.onNodeWithText(OnlineChambers.NAME_BN).assertExists()
        shot("online_doctor_card_360_bn")
    }

    @Test
    fun modeChipsLocked() {
        show { ChamberModeChips(online = false, onlineAllowed = false) {} }
        rule.onNodeWithText("অনলাইন (ভিডিও)").assertIsNotEnabled()
    }
}
