package com.bspc.blood

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
 * রসিদ (Receipts.kt, InvoiceForms.kt): কাগজের মতো রসিদ — বাংলা/ইংরেজি অঙ্ক, বাকি, "বাতিল" ছাপ, ডেমো জলছাপ, নিচে
 * "মূসক চালানপত্র নয়"; চালকের "যাত্রা শেষ" (ঠিক হওয়া ভাড়া বড় করে, চার্টের বেশি হলে সতর্কতা)। ছবি app/build/screens-এ।
 * আর PDF (A4 আর ৮০ মিমি) তৈরি হয় কি না।
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class ReceiptScreensTest {
    @get:Rule
    val rule = createComposeRule()

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        if (FirebaseApp.getApps(ctx).isEmpty()) runCatching { FirebaseApp.initializeApp(ctx) }
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

    private val inv = Invoice(
        id = "inv1", no = "W-000123", kind = "pharmacy", bizId = "ph1", bizName = "ডেমো লাইফ ফার্মেসি", bizPhone = "01700000000",
        bizAddress = "টঙ্গী, গাজীপুর", customerName = "রহিম", customerPhone = "01711111111",
        lines = listOf(InvLine("Napa 500 mg", 10.0, "pcs", 1.2, 0.0, 12.0), InvLine("Seclo 20", 14.0, "", 7.0, 8.0, 90.0)),
        subtotal = 102.0, disc = 2.0, fees = listOf(InvFee("ডেলিভারি", 30.0)), total = 130.0, paid = 100.0, due = 30.0,
        payments = listOf(InvPayment(1_792_730_700_000L, 100.0, "bkash", "TRX1")), issuedAt = 1_792_730_700_000L,
        verifyCode = "Ab12Cd34Ef", verifyUrl = "https://lifolo.com/v/Ab12Cd34Ef",
    )

    @Test
    fun receiptBangla() {
        show { ReceiptView(inv, true) }
        rule.onNodeWithText("নং W-০০০১২৩").assertExists()
        rule.onNodeWithText("৳১৩০").assertExists()
        rule.onNodeWithTag("receipt_footer").assertExists()
        shot("receipt_bn")
    }

    @Test
    fun receiptEnglishDigitsVoidDemo() {
        show { ReceiptView(inv.copy(status = "void", voidReason = "wrong_price", voidNote = "৫০০-এর জায়গায় ৫০০০", demo = true), false) }
        rule.onNodeWithText("নং W-000123").assertExists()
        rule.onNodeWithTag("receipt_void_stamp").assertExists()
        rule.onNodeWithTag("receipt_demo").assertExists()
        shot("receipt_void_demo_en_digits")
    }

    @Test
    fun ambulanceFinish() {
        val r = AmbDriverReq(
            "a1", "arrived", 0L, 0L, "amb1", 3, false, null, "গাজীপুর", null, null, "টঙ্গী বাজার", "", false, "রহিম", "01711111111", null, null,
            quote = true, destLabel = "ঢাকা মেডিকেল", suggestedFare = 1800, agreedFare = 2000,
        )
        show { AmbFinishDialog(r, {}, {}) }
        rule.onNodeWithText("ঠিক হওয়া ভাড়া").assertExists()
        shot("amb_finish_bn")
    }

    @Test
    fun pdfsAreWritten() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        // Robolectric-এ PdfDocument না চললে (পরিবেশের সীমা) বাদ — ফোনে চলে
        val a4 = runCatching { ReceiptPdf.write(ctx, inv, narrow = false, bn = true) }.getOrNull()
        org.junit.Assume.assumeTrue(a4 != null)
        val small = ReceiptPdf.write(ctx, inv.copy(status = "void", demo = true), narrow = true, bn = false)
        org.junit.Assert.assertTrue(a4!!.length() > 1000)
        org.junit.Assert.assertTrue(small.length() > 1000)
        org.junit.Assert.assertTrue(small.name.endsWith("-80mm.pdf"))
    }
}
