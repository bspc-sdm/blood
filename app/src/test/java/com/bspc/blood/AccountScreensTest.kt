package com.bspc.blood

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.google.firebase.FirebaseApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * অ্যাকাউন্ট মোছার পুরো পাতা ("আমি বুঝেছি" টিক না দিলে লাল বোতাম চাপা যায় না, তারপর এরপর কী হবে), সাময়িক বন্ধের পাতা,
 * আটকানো পর্দা ("আবার চালু করুন" / "লগআউট") আর "মোছা বাতিল হয়েছে" ব্যানার — সার্ভারের বদলে নকল কাজ (app/build/screens-এ ছবি)।
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class AccountScreensTest {
    @get:Rule
    val rule = createComposeRule()

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        if (FirebaseApp.getApps(ctx).isEmpty()) runCatching { FirebaseApp.initializeApp(ctx) }
        AppPrefs.lang.value = Lang.BN
        AppPrefs.theme.value = ThemeMode.LIGHT
        AccountState.clear()
        AccountState.reactivatedAt = 0L
    }

    private fun show(content: @Composable () -> Unit) {
        rule.setContent { GetBloodTheme { Surface { content() } } }
    }

    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    private fun shot(name: String) {
        captureScreenRoboImage("build/screens/account_$name.png")
    }

    @Test
    fun deleteNeedsTheTickThenExplainsWhatHappensNext() {
        var calls = 0
        var signedOut = 0
        var finished = 0
        var pause = 0
        show {
            DeleteAccountScreen(
                email = "rahim@example.com",
                onBack = {},
                onPauseInstead = { pause++ },
                onFinished = { finished++ },
                doDelete = { calls++; 1_763_692_000_000L },
                afterDelete = { signedOut++ },
            )
        }
        // ওপরে বিকল্প
        rule.onNodeWithText("মুছে না ফেলে সাময়িক বন্ধ রাখুন").assertExists()
        rule.onNodeWithText("শুধু কিছুদিনের বিরতি চান?").assertExists()
        rule.onNodeWithText("৩০ দিনের মধ্যে আবার লগইন করলে মোছা বাতিল হবে, সব আগের মতো ফিরবে। ৩০ দিন পর চিরতরে মুছে যাবে — আর ফেরানো যাবে না।").assertExists()
        rule.onNodeWithTag("delete_date").assertExists()
        shot("delete_top_bn")
        // টিক ছাড়া চাপা যায় না
        rule.onNodeWithTag("delete_confirm").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("delete_confirm").performClick()
        rule.waitForIdle()
        assertEquals(0, calls)
        shot("delete_confirm_off_bn")
        // টিক → চালু; আবার টিক তুললে বন্ধ
        rule.onNodeWithTag("delete_ack_off").performScrollTo().performClick()
        rule.onNodeWithTag("delete_ack_on").assertExists()
        rule.onNodeWithTag("delete_confirm").assertIsEnabled()
        rule.onNodeWithTag("delete_ack_on").performClick()
        rule.onNodeWithTag("delete_confirm").assertIsNotEnabled()
        rule.onNodeWithTag("delete_ack_off").performClick()
        shot("delete_confirm_on_bn")
        rule.onNodeWithTag("delete_confirm").performClick()
        rule.waitForIdle()
        assertEquals(1, calls)
        assertEquals(1, signedOut)
        // এরপর কী হবে: সার্ভারের তারিখ, ইমেইল, লগআউট
        val day = AccountState.formatDay(1_763_692_000_000L)
        rule.onNodeWithText("আপনার অ্যাকাউন্ট $day তারিখে মুছে যাবে।").assertExists()
        rule.onNodeWithText("rahim@example.com ঠিকানায় একটা ইমেইল পাঠানো হয়েছে: কোন তারিখে মুছবে, কী কী মুছবে আর কীভাবে বাতিল করবেন।").assertExists()
        shot("delete_done_bn")
        rule.onNodeWithTag("delete_home").performClick()
        assertEquals(1, finished)
        // বিকল্পের বোতাম
        assertEquals(0, pause)
    }

    @Test
    fun deleteFailureShowsTheServerMessageAndStaysLoggedIn() {
        var signedOut = 0
        show {
            DeleteAccountScreen(
                email = null, onBack = {}, onPauseInstead = {}, onFinished = {},
                doDelete = { throw ServerError("সার্ভারে সমস্যা", null) },
                afterDelete = { signedOut++ },
            )
        }
        rule.onNodeWithTag("delete_ack_off").performScrollTo().performClick()
        // বোতাম পর্দার বাইরে থাকলে চাপ লাগে না — আগে সেখানে
        rule.onNodeWithTag("delete_confirm").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("সার্ভারে সমস্যা").assertExists()
        assertEquals(0, signedOut)
        rule.onNodeWithTag("delete_done").assertDoesNotExist()
    }

    @Test
    fun deleteFromThePausedScreenHasNoPauseOption() {
        show { DeleteAccountScreen(email = null, onBack = {}, onPauseInstead = {}, onFinished = {}, paused = true, doDelete = { null }, afterDelete = {}) }
        rule.onNodeWithTag("pause_instead").assertDoesNotExist()
    }

    @Test
    fun deleteScreenEnglish() {
        AppPrefs.lang.value = Lang.EN
        show { DeleteAccountScreen(email = "rahim@example.com", onBack = {}, onPauseInstead = {}, onFinished = {}, doDelete = { null }, afterDelete = {}) }
        rule.onNodeWithText("Pause Instead of Deleting", ignoreCase = true).assertExists()
        shot("delete_top_en")
    }

    @Test
    fun deactivateExplainsThenPausesAndLogsOut() {
        var calls = 0
        var signedOut = 0
        var finished = 0
        show {
            DeactivateAccountScreen(
                email = "rahim@example.com", onBack = {}, onDeleteInstead = {}, onFinished = { finished++ },
                doDeactivate = { calls++ },
                afterDeactivate = { signedOut++ },
            )
        }
        rule.onNodeWithText("কিছুই মুছবে না — সব তথ্য যেমন আছে তেমনই থাকবে।").assertExists()
        rule.onNodeWithText("সময়ের কোনো সীমা নেই — যতদিন খুশি বন্ধ রাখুন।").assertExists()
        rule.onNodeWithText("আপনার প্রোফাইল কেউ দেখবে না, রক্তদাতার তালিকাতেও থাকবেন না।").assertExists()
        shot("pause_bn")
        rule.onNodeWithTag("pause_confirm").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(1, calls)
        assertEquals(1, signedOut)
        rule.onNodeWithText("লগআউট হয়েছে। কিছুই মোছা হয়নি। আবার চালু করতে যেকোনো সময় লগইন করুন।").assertExists()
        shot("pause_done_bn")
        rule.onNodeWithTag("pause_home").performClick()
        assertEquals(1, finished)
    }

    @Test
    fun deactivateWhileDeletionPendingShowsTheReason() {
        var signedOut = 0
        show {
            DeactivateAccountScreen(
                email = null, onBack = {}, onDeleteInstead = {}, onFinished = {},
                doDeactivate = { throw ServerError("আপনার অ্যাকাউন্ট মোছার অপেক্ষায় আছে, তাই সাময়িক বন্ধ করা যাবে না।", "deletion_pending") },
                afterDeactivate = { signedOut++ },
            )
        }
        rule.onNodeWithTag("pause_confirm").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("আপনার অ্যাকাউন্ট মোছার অপেক্ষায় আছে, তাই সাময়িক বন্ধ করা যাবে না।").assertExists()
        assertEquals(0, signedOut)
        rule.onNodeWithTag("pause_done").assertDoesNotExist()
    }

    @Test
    fun pausedScreenReactivates() {
        AccountState.fromServer(org.json.JSONObject("""{"state":"deactivated","deactivatedAt":1761100000000}"""))
        var reactivated = 0
        var logout = 0
        var delete = 0
        show {
            AccountPausedScreen(
                email = "rahim@example.com",
                account = AccountState.info.value,
                onReactivated = { reactivated++ },
                onLogout = { logout++ },
                onDelete = { delete++ },
                doReactivate = { AccountInfo(AccountInfo.ACTIVE) },
            )
        }
        rule.onNodeWithText("আপনার অ্যাকাউন্ট সাময়িক বন্ধ আছে").assertExists()
        rule.onNodeWithText("লগআউট").assertExists()
        shot("paused_bn")
        rule.onNodeWithTag("reactivate").performClick()
        rule.waitForIdle()
        assertEquals(1, reactivated)
        assertEquals(AccountInfo.ACTIVE, AccountState.info.value!!.state)
        assertTrue(AccountState.reactivatedAt > 0)
        rule.onNodeWithTag("paused_logout").performClick()
        assertEquals(1, logout)
        rule.onNodeWithText("অ্যাকাউন্ট মুছুন").performClick()
        assertEquals(1, delete)
    }

    @Test
    fun pausedScreenReactivateFailureStaysBlocked() {
        AccountState.fromServer(org.json.JSONObject("""{"state":"deactivated"}"""))
        var reactivated = 0
        show {
            AccountPausedScreen(
                email = null, account = AccountState.info.value, onReactivated = { reactivated++ }, onLogout = {}, onDelete = {},
                doReactivate = { throw IllegalStateException("সার্ভারে পৌঁছানো যায়নি।") },
            )
        }
        rule.onNodeWithTag("reactivate").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("সার্ভারে পৌঁছানো যায়নি।").assertExists()
        assertEquals(0, reactivated)
        assertTrue(AccountState.info.value!!.deactivated)
    }

    @Test
    fun deletionCancelledBanner() {
        var closed = 0
        show { DeletionCancelledBanner(onClose = { closed++ }) }
        rule.onNodeWithText("অ্যাকাউন্ট মোছা বাতিল হয়েছে").assertExists()
        shot("deletion_cancelled_bn")
        rule.onNodeWithTag("deletion_cancelled").assertExists()
        rule.onNodeWithText("সব আগের মতো আছে।").assertExists()
        rule.onNodeWithTag("deletion_cancelled").assertExists()
        rule.onNodeWithText("অ্যাকাউন্ট মোছা বাতিল হয়েছে").assertExists()
        rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("বন্ধ করুন"))[0].performClick()
        assertEquals(1, closed)
    }
}
