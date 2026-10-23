package com.bspc.blood

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.google.firebase.FirebaseApp
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * লাইফলো পে-র পাতা (PaySheet.kt, PayBusiness.kt): রোগীর PaySheet (শুধু চালু নম্বর, পার্সোনাল/মার্চেন্টের ধাপ, TrxID আর
 * শেষ ৪ সংখ্যার যাচাই, টাইমলাইন, ফেরত পেয়েছি, রিপোর্ট), ঢোকার বাটনগুলো (চাবি বন্ধ থাকলে কিছুই নয়), ব্যবসার দাবি
 * (নিশ্চিত করার ধাপ, কারণসহ "পাইনি"), সেটিংসের ২৪ ঘণ্টার কথা, অনলাইন চেম্বারের ফি পেমেন্টের স্তরে। ছবি app/build/screens-এ।
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class PayScreensTest {
    @get:Rule
    val rule = createComposeRule()

    private val now = 1_792_900_000_000L
    private val all = mapOf(
        PayGate.MANUAL to true, PayGate.CHAMBER_ADVANCE to true, PayGate.DELIVERY to true, PayGate.LAB to true, PayGate.AMBULANCE to true,
        PayGate.DUE to true, "inv_pharmacy" to true, "inv_lab" to true, "inv_ambulance" to true, "inv_chamber" to true,
    )

    private val req = PayRequest(
        id = "p1", kind = "pharmacy", bizId = "ph1", bizName = "ডেমো লাইফ ফার্মেসি", payerName = "রহিম", refType = "rx_order", refId = "o1",
        purpose = PayRules.DELIVERY, amount = 500.0,
        methods = listOf(PayMethod("bkash", "01711223344", "personal"), PayMethod("nagad", "01811223344", "merchant"), PayMethod("rocket", "01911223344", "personal", active = false)),
        createdAt = now - 3_600_000, expiresAt = Long.MAX_VALUE / 2,
    )
    private val claim = PayClaim("bkash", "8N7A6B5C4D", "1234", now - 600_000)

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        if (FirebaseApp.getApps(ctx).isEmpty()) runCatching { FirebaseApp.initializeApp(ctx) }
        AppPrefs.lang.value = Lang.BN
        AppPrefs.theme.value = ThemeMode.LIGHT
        LoginGate.uid.value = "demo"
        LoginGate.profileReady.value = true
        PayGate.testKeys = all
        OnlineGate.testKeys = mapOf(OnlineGate.CHAMBER to true, OnlineGate.VIDEO to false)
    }

    @After
    fun tearDown() {
        PayGate.testKeys = null
        OnlineGate.testKeys = null
        PayStore.clear()
        CareMode.asUid.value = null
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

    // ---------------- রোগী: PaySheet ----------------

    @Test
    fun sheetOpenPersonalThenMerchant() {
        show { PaySheet(req, onChanged = {}) }
        rule.onNodeWithTag("pay_amount").assertExists()
        rule.onNodeWithText("৳৫০০").assertExists()
        rule.onNodeWithText("ওষুধের দাম (হোম ডেলিভারি)").assertExists()
        rule.onNodeWithText("ডেমো লাইফ ফার্মেসি-কে").assertExists()
        // শুধু চালু নম্বর (রকেট বন্ধ)
        rule.onNodeWithTag("pay_method_bkash").assertExists()
        rule.onNodeWithTag("pay_method_nagad").assertExists()
        rule.onNodeWithTag("pay_method_rocket").assertDoesNotExist()
        rule.onNodeWithText("01711223344").assertExists()
        rule.onNodeWithTag("pay_copy").assertExists()
        rule.onNodeWithText("ফার্মেসির bKash (সেন্ড মানি)").assertExists()
        rule.onNodeWithText("bKash অ্যাপে \"সেন্ড মানি\" করুন — ৳৫০০ উপরের নম্বরে").assertExists()
        rule.onNodeWithText("পাঠানো হয়নি").assertExists()
        // সবসময়ের সতর্কতা, পাঠানোর আগে ফেরতের নিয়ম, নম্বর লেখার কোনো ঘর নেই
        rule.onNodeWithTag("pay_only_here").assertExists()
        rule.onNodeWithText(PayText.ONLY_HERE).assertExists()
        rule.onNodeWithTag("pay_refund_policy").assertExists()
        rule.onNodeWithText(PayText.RECHECK).assertExists()
        shot("pay_sheet_personal_360_bn")
        // মার্চেন্ট → "পেমেন্ট"
        rule.onNodeWithTag("pay_method_nagad").performClick()
        rule.onNodeWithText("01811223344").assertExists()
        rule.onNodeWithText("Nagad অ্যাপে \"পেমেন্ট\" করুন — ৳৫০০ উপরের মার্চেন্ট নম্বরে").assertExists()
        rule.onNodeWithText("ফার্মেসির Nagad (পেমেন্ট — মার্চেন্ট)").assertExists()
        shot("pay_sheet_merchant_360_bn")
    }

    @Test
    fun claimValidatedPerMethod() {
        show { PaySheet(req, onChanged = {}) }
        rule.onNodeWithTag("pay_trxid").performTextInput("ab12")
        rule.onNodeWithTag("pay_submit").performScrollTo().performClick()
        // bKash: ঠিক ১০ অক্ষর — পাঠানোর আগেই বলা
        assertTrue(rule.onAllNodesWithText("bKash-এর TrxID ঠিক ১০ অক্ষরের (ইংরেজি অক্ষর ও সংখ্যা)", substring = true).fetchSemanticsNodes().size >= 2)
        // নগদে ৬ অক্ষরই যথেষ্ট, কিন্তু শেষ ৪ সংখ্যা লাগে
        rule.onNodeWithTag("pay_method_nagad").performClick()
        rule.onNodeWithTag("pay_trxid").performTextInput("CD34")
        rule.onNodeWithTag("pay_submit").performScrollTo().performClick()
        assertTrue(rule.onAllNodesWithText(PayText.LAST4_HINT).fetchSemanticsNodes().size >= 2)
        rule.onNodeWithTag("pay_last4").performTextInput("১২৩৪")
        rule.onNodeWithText("1234").assertExists()
        rule.onNodeWithText("টাকা পাঠিয়েছি").assertIsEnabled()
        shot("pay_sheet_claim_filled_360_bn")
    }

    @Test
    fun sheetStates() {
        show {
            PaySheet(req.copy(status = "claimed", claim = claim), onChanged = {})
            PaySheet(req.copy(id = "p2", status = "rejected", claim = claim, rejectReason = "not_received", rejectNote = "TrxID মেলেনি"), onChanged = {})
        }
        rule.onNodeWithTag("pay_claim_info").assertExists()
        rule.onNodeWithText("bKash · TrxID: 8N7A6B5C4D").assertExists()
        rule.onNodeWithText("ফার্মেসি যাচাই করছেন").assertExists()
        rule.onNodeWithText("ফার্মেসি পাননি: টাকা আসেনি — TrxID মেলেনি").assertExists()
        rule.onNodeWithText("আবার পাঠিয়েছি").assertExists()
        // "পাইনি": আলাদা বার্তা আর আপত্তি
        rule.onNodeWithTag("pay_rejected_box").assertExists()
        rule.onNodeWithText("ফার্মেসি বলছেন টাকা পাননি: টাকা আসেনি — TrxID মেলেনি").assertExists()
        rule.onNodeWithTag("pay_dispute").performScrollTo().performClick()
        rule.onNodeWithText("পেমেন্টের সমস্যা জানান").assertExists()
        shot("pay_sheet_claimed_rejected_360_bn")
    }

    @Test
    fun refundAckAndReport() {
        val r = req.copy(status = "refund_claimed", claim = claim, confirmedAt = now - 300_000, invoiceId = "inv1", refund = PayRefund("9Z8Y7X6W5V", 200.0, now, 0L))
        show { PaySheet(r, onChanged = {}) }
        rule.onNodeWithTag("pay_refund_box").assertExists()
        rule.onNodeWithText("ফেরত দেওয়া হয়েছে ৳২০০").assertExists()
        rule.onNodeWithText("ফেরত পেয়েছেন?").assertExists()
        rule.onNodeWithTag("pay_refund_ack").assertExists()
        rule.onNodeWithText("ফেরত এসেছে?").assertExists()
        rule.onNodeWithTag("pay_trxid").assertDoesNotExist()
        shot("pay_sheet_refunded_360_bn")
        rule.onNodeWithTag("pay_refund_missing").performScrollTo().performClick()
        rule.onNodeWithText("পেমেন্টের সমস্যা জানান").assertExists()
        rule.onNodeWithText("ফেরত পাইনি").assertExists()
        shot("pay_report_360_bn")
    }

    @Test
    fun confirmedAndExpired() {
        show {
            PaySheet(req.copy(status = "confirmed", claim = claim, confirmedAt = now, invoiceId = "inv1"), onChanged = {})
            PaySheet(req.copy(id = "p3", status = "expired"), onChanged = {})
        }
        rule.onNodeWithTag("pay_receipt").assertExists()
        rule.onNodeWithText("পরিশোধিত").assertExists()
        rule.onNodeWithText("সময় শেষ — টাকা পাঠানো হয়নি").assertExists()
        rule.onNodeWithTag("pay_trxid").assertDoesNotExist()
    }

    @Test
    fun demoAndNoNumbers() {
        show {
            PaySheet(req.copy(demo = true), onChanged = {})
            PaySheet(req.copy(id = "p9", methods = emptyList()), onChanged = {})
        }
        rule.onNodeWithTag("pay_demo").assertExists()
        rule.onNodeWithText("ডেমো — আসল টাকা পাঠাবেন না").assertExists()
        rule.onNodeWithText(PayText.NO_NUMBERS).assertExists()
    }

    @Test
    fun advanceDueLabel() {
        PayStore.set(listOf(req.copy(id = "pa", kind = "chamber", refType = "appointment", refId = "ap1", purpose = PayRules.ADVANCE, amount = 200.0)))
        show { PayRefButton("ap1", PayRules.ADVANCE) }
        rule.onNodeWithText("অগ্রিম বাকি · ৳২০০ পাঠান").assertExists()
    }

    @Test
    fun neverForBlood() {
        show { PaySheet(req.copy(refType = "blood_request"), onChanged = {}) }
        rule.onNodeWithTag("pay_sheet").assertDoesNotExist()
        rule.onNodeWithTag("pay_trxid").assertDoesNotExist()
    }

    // ---------------- ঢোকার পথ ----------------

    @Test
    fun entryButtonsGated() {
        PayStore.set(listOf(req, req.copy(id = "pl", refId = "lo1", purpose = PayRules.LAB, amount = 1200.0, status = "claimed", claim = claim)))
        PayGate.testKeys = all - PayGate.LAB
        show {
            PayRefButton("o1", PayRules.DELIVERY)
            PayRefButton("lo1", PayRules.LAB)
            PayRefButton("none", PayRules.DELIVERY)
        }
        rule.onNodeWithText("৳৫০০ পাঠান").assertExists()
        // pay_lab বন্ধ: পরীক্ষার পেমেন্ট দেখায় না
        rule.onNodeWithTag("pay_entry_lab").assertDoesNotExist()
        shot("pay_entry_360_bn")
    }

    @Test
    fun allOffShowsNothing() {
        PayGate.testKeys = emptyMap()
        PayStore.set(listOf(req))
        val inv = Invoice(id = "i1", no = "W-1", kind = "pharmacy", bizId = "ph1", bizName = "x", total = 100.0, paid = 70.0, due = 30.0)
        show {
            PayRefButton("o1", PayRules.DELIVERY)
            AmbFarePay("amb1", "done")
            PayDueButton(inv, null)
            BizPaymentsButton("pharmacy", "ph1")
            PayAskAction("rx_order", "o1", PayRules.DELIVERY)
        }
        rule.onNodeWithTag("pay_entry_delivery").assertDoesNotExist()
        rule.onNodeWithTag("amb_pay").assertDoesNotExist()
        rule.onNodeWithTag("receipt_pay_due").assertDoesNotExist()
        rule.onNodeWithTag("biz_payments").assertDoesNotExist()
        rule.onNodeWithTag("pay_ask").assertDoesNotExist()
    }

    @Test
    fun ambulanceOnlyAfterTripAndDue() {
        val inv = Invoice(id = "i1", no = "W-1", kind = "pharmacy", bizId = "ph1", bizName = "x", total = 100.0, paid = 70.0, due = 30.0)
        show {
            AmbFarePay("amb1", "accepted")
            AmbFarePay("amb2", "done")
            PayDueButton(inv, null)
            PayDueButton(inv.copy(id = "i2", due = 0.0, paid = 100.0), null)
        }
        // যাত্রার আগে কিছুই নয়; পরে ঐচ্ছিক
        rule.onNodeWithText("ভাড়া বিকাশ/নগদে দিন").assertExists()
        rule.onNodeWithText("চাইলে তবেই। ক্যাশে দিয়ে থাকলে কিছু করতে হবে না।").assertExists()
        rule.onNodeWithText("বাকি ৳৩০ অনলাইনে দিন").assertExists()
        assertTrue(rule.onAllNodesWithText("অনলাইনে দিন", substring = true).fetchSemanticsNodes().size == 1)
        shot("pay_amb_due_360_bn")
    }

    // ---------------- ব্যবসা ----------------

    @Test
    fun businessClaimConfirmStep() {
        show {
            PayTodayCard(PaySummary(2500.0, 3, 1))
            PayClaimCard(req.copy(status = "claimed", claim = claim), assistant = false) {}
        }
        rule.onNodeWithText("৳২,৫০০").assertExists()
        rule.onNodeWithText("রহিম").assertExists()
        rule.onNodeWithText("TrxID: 8N7A6B5C4D").assertExists()
        rule.onNodeWithText("পাঠানো নম্বরের শেষ 1234").assertExists()
        rule.onNodeWithText("bKash → 01711223344").assertExists()
        shot("pay_biz_claim_360_bn")
        // "পেয়েছি" → আগে নিশ্চিত করার ধাপ
        rule.onNodeWithTag("pay_confirm").performClick()
        rule.onNodeWithText("টাকা পেয়েছেন?").assertExists()
        // তিনটা মিলে টিক না দিলে নিশ্চিত হয় না
        rule.onNodeWithTag("pay_confirm_yes").assertIsNotEnabled()
        rule.onNodeWithText("TrxID মিলেছে").assertExists()
        rule.onNodeWithTag("pay_check_trxId").performClick()
        rule.onNodeWithTag("pay_check_amount").performClick()
        rule.onNodeWithTag("pay_confirm_yes").assertIsNotEnabled()
        rule.onNodeWithTag("pay_check_last4").performClick()
        rule.onNodeWithTag("pay_confirm_yes").assertIsEnabled()
        shot("pay_biz_confirm_360_bn")
        rule.onNodeWithText("বাতিল").performClick()
        // "পাইনি" → কারণ লাগবেই; "অন্যান্য" হলে লেখাও
        rule.onNodeWithTag("pay_reject").performClick()
        rule.onNodeWithTag("pay_reject_send").assertIsNotEnabled()
        rule.onNodeWithTag("pay_reject_other").performClick()
        rule.onNodeWithTag("pay_reject_send").assertIsNotEnabled()
        rule.onNodeWithTag("pay_reject_wrong_amount").performClick()
        rule.onNodeWithTag("pay_reject_send").assertIsEnabled()
        shot("pay_biz_reject_360_bn")
    }

    @Test
    fun businessListRowsAndRefund() {
        val r = req.copy(status = "confirmed", claim = claim, confirmedAt = now)
        show {
            PayRow(r, showPayer = true, biz = true) {}
            PayRow(req.copy(id = "p5", status = "claimed", claim = claim), showPayer = true, biz = true) {}
            PayRefundDialog(r, onDismiss = {}) {}
        }
        rule.onNodeWithText("পেয়েছেন").assertExists()
        rule.onNodeWithText("যাচাই করুন").assertExists()
        rule.onNodeWithTag("pay_refund_send").assertIsNotEnabled()
        rule.onNodeWithTag("pay_refund_to", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("pay_refund_trx").performTextInput("9z8y7x6w5v")
        rule.onNodeWithTag("pay_refund_send").assertIsEnabled()
        shot("pay_biz_refund_360_bn")
    }

    @Test
    fun settingsCoolOff() {
        show { PaySettingsPanel("chamber", "doc1") }
        rule.onNodeWithTag("pay_cooloff").assertExists()
        rule.onNodeWithText(PayText.COOL_OFF, substring = true).assertExists()
        rule.onNodeWithTag("pay_method_row_bkash").assertExists()
        rule.onNodeWithTag("pay_method_row_upay").assertExists()
        // "আমি করিনি" — নম্বর আটকে দেওয়া
        rule.onNodeWithTag("pay_freeze").assertExists()
        // চালু করে ভুল নম্বর → সেভের আগেই বলা
        rule.onNodeWithTag("pay_method_on_bkash").performClick()
        rule.onNodeWithTag("pay_method_number_bkash").performTextInput("0171")
        rule.onNodeWithTag("pay_methods_save").performScrollTo().performClick()
        rule.onNodeWithText("সঠিক bKash নম্বর দিন (01XXXXXXXXX)").assertExists()
        // ডাক্তার: চেম্বারের অগ্রিম
        rule.onNodeWithTag("pay_advance").assertExists()
        shot("pay_settings_360_bn")
    }

    @Test
    fun advanceRow() {
        show { ChamberAdvanceRow(ChamberAdvance("dghs_1", "Demo Hospital", "ডেমো হাসপাতাল", 200.0, 6), busy = false) {} }
        rule.onNodeWithText("ডেমো হাসপাতাল").assertExists()
        rule.onNodeWithText("৬ ঘণ্টা আগে").assertExists()
        shot("pay_advance_row_360_bn")
    }

    // ---------------- অনলাইন চেম্বার: পেমেন্টের স্তরে ----------------

    @Test
    fun onlineChamberUsesPaySheet() {
        val pay = OnlinePay("unpaid", "", "01711223344", "৫০০ টাকা", false, "", requestId = "pr1")
        show { PatientOnlinePanel("a1", "confirmed", 1792886400000L, "17:00", pay, onChanged = {}, now = now) }
        rule.onNodeWithTag("online_patient_panel").assertExists()
        rule.onNodeWithTag("pay_sheet").assertExists()
        rule.onNodeWithText("অনলাইন চেম্বারের ফি").assertExists()
        rule.onNodeWithText("ডাক্তারের bKash (সেন্ড মানি)").assertExists()
        rule.onNodeWithTag("pay_last4").assertExists()
        shot("online_patient_pay_layer_360_bn")
    }
}
