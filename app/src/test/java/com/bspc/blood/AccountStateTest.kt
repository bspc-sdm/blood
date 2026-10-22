package com.bspc.blood

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * অ্যাকাউন্টের অবস্থা (Account.kt): সার্ভারের `account` পড়া (profile_get / লগইন), 423 `account_deactivated`-এ আটকানো,
 * আবার চালুর পর পুরোনো 423 বাদ, লগআউটে মোছা, মোছার তারিখ (৩০ দিন, ঢাকার দিন), care_paused-এর বার্তা।
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccountStateTest {
    @Before
    fun setUp() {
        AppPrefs.lang.value = Lang.BN
        AccountState.clear()
        AccountState.reactivatedAt = 0L
    }

    @After
    fun tearDown() {
        AccountState.clear()
        AccountState.reactivatedAt = 0L
    }

    @Test
    fun parsesTheServerAccountObject() {
        val a = AccountInfo.parse(JSONObject("""{"state":"deactivated","deactivatedAt":1761100000000}"""))!!
        assertEquals(AccountInfo.DEACTIVATED, a.state)
        assertEquals(1761100000000L, a.deactivatedAt)
        assertTrue(a.deactivated)
        val d = AccountInfo.parse(JSONObject("""{"state":"deleting","deletionRequestedAt":1761100000000,"deletesAt":"1763692000000"}"""))!!
        assertEquals(AccountInfo.DELETING, d.state)
        assertEquals(1761100000000L, d.deletionRequestedAt)
        assertEquals(1763692000000L, d.deletesAt)
        assertFalse(d.deactivated)
        assertEquals(AccountInfo(AccountInfo.ACTIVE), AccountInfo.parse(JSONObject("""{"state":"active","deactivatedAt":null}""")))
    }

    @Test
    fun unknownOrMissingAccountIsNull() {
        assertNull(AccountInfo.parse(null))
        assertNull(AccountInfo.parse(JSONObject("{}")))
        assertNull(AccountInfo.parse(JSONObject("""{"state":"paused"}""")))
        assertNull(AccountInfo.parse(JSONObject("""{"state":5}""")))
        // বাজে সময়: বাদ, অবস্থা থাকে
        assertEquals(AccountInfo(AccountInfo.DEACTIVATED), AccountInfo.parse(JSONObject("""{"state":"deactivated","deactivatedAt":"x"}""")))
        assertEquals(AccountInfo(AccountInfo.DEACTIVATED), AccountInfo.parse(JSONObject("""{"state":"deactivated","deactivatedAt":-4}""")))
    }

    @Test
    fun profileOrLoginAnswerSetsTheState_oldServerKeepsIt() {
        AccountState.fromServer(JSONObject("""{"state":"deactivated","deactivatedAt":1761100000000}"""))
        assertTrue(AccountState.blocked(loggedIn = true, AccountState.info.value))
        // পুরোনো সার্ভার (account নেই): যা ছিল তাই
        AccountState.fromServer(null)
        assertTrue(AccountState.info.value!!.deactivated)
        AccountState.fromServer(JSONObject("""{"state":"active"}"""))
        assertFalse(AccountState.blocked(loggedIn = true, AccountState.info.value))
    }

    @Test
    fun blockedOnlyWhenLoggedInAndDeactivated() {
        val off = AccountInfo(AccountInfo.DEACTIVATED)
        assertTrue(AccountState.blocked(true, off))
        assertFalse(AccountState.blocked(false, off))
        assertFalse(AccountState.blocked(true, null))
        assertFalse(AccountState.blocked(true, AccountInfo(AccountInfo.ACTIVE)))
        assertFalse(AccountState.blocked(true, AccountInfo(AccountInfo.DELETING)))
    }

    @Test
    fun error423BlocksTheApp() {
        AccountState.fromServer(JSONObject("""{"state":"active"}"""))
        AccountState.onDeactivatedError(startedAt = 1_000L, loggedIn = true)
        assertTrue(AccountState.blocked(true, AccountState.info.value))
        // জানা না থাকলেও (প্রোফাইল আসার আগে)
        AccountState.clear()
        AccountState.onDeactivatedError(startedAt = 1_000L, loggedIn = true)
        assertTrue(AccountState.info.value!!.deactivated)
    }

    @Test
    fun error423AfterLogoutIsIgnored() {
        AccountState.onDeactivatedError(startedAt = 1_000L, loggedIn = false)
        assertNull(AccountState.info.value)
    }

    @Test
    fun staleError423FromBeforeReactivationIsIgnored() {
        AccountState.fromServer(JSONObject("""{"state":"deactivated"}"""))
        AccountState.afterReactivate(AccountInfo.parse(JSONObject("""{"state":"active"}""")), now = 5_000L)
        assertFalse(AccountState.blocked(true, AccountState.info.value))
        // চালু করার আগে পাঠানো অনুরোধের 423 — আটকায় না
        AccountState.onDeactivatedError(startedAt = 4_999L, loggedIn = true)
        assertFalse(AccountState.blocked(true, AccountState.info.value))
        // পরে পাঠানো অনুরোধের 423 (অন্য ফোন থেকে আবার বন্ধ) — আটকায়
        AccountState.onDeactivatedError(startedAt = 5_001L, loggedIn = true)
        assertTrue(AccountState.blocked(true, AccountState.info.value))
    }

    @Test
    fun reactivateCountsAsOnEvenIfTheAnswerHasNoState() {
        AccountState.fromServer(JSONObject("""{"state":"deactivated"}"""))
        AccountState.afterReactivate(null, now = 10L)
        assertEquals(AccountInfo(AccountInfo.ACTIVE), AccountState.info.value)
        AccountState.fromServer(JSONObject("""{"state":"deactivated"}"""))
        AccountState.afterReactivate(AccountInfo(AccountInfo.DEACTIVATED), now = 20L)
        assertEquals(AccountInfo.ACTIVE, AccountState.info.value!!.state)
        assertEquals(20L, AccountState.reactivatedAt)
    }

    @Test
    fun logoutClearsTheState() {
        AccountState.fromServer(JSONObject("""{"state":"deactivated"}"""))
        AccountState.clear()
        assertNull(AccountState.info.value)
    }

    @Test
    fun deleteDateIsThirtyDaysOnTheDhakaCalendar() {
        val now = 1_761_100_000_000L // 2025-10-22 02:26 UTC = 08:26 ঢাকা
        assertEquals(now + 30L * 24 * 3600 * 1000, AccountState.deleteDateFrom(now))
        assertEquals("21 Nov 2025", AccountState.formatDay(AccountState.deleteDateFrom(now), bangla = false))
        // ঢাকার মধ্যরাতের পরে কিন্তু UTC-তে আগের দিন: ঢাকার দিনই
        val lateUtc = 1_761_157_800_000L // 2025-10-22 18:30 UTC = 2025-10-23 00:30 ঢাকা
        assertEquals("23 Oct 2025", AccountState.formatDay(lateUtc, bangla = false))
        assertTrue(AccountState.formatDay(lateUtc, bangla = true).contains("অক্টোবর"))
    }

    @Test
    fun carePausedHasAFullMessage_othersKeepTheServerText() {
        assertTrue(errorMessage("care_paused", "এই মানুষের অ্যাকাউন্ট এখন সাময়িক বন্ধ আছে", 403).contains("আবার চালু করলে"))
        assertEquals("আপনার অ্যাকাউন্ট মোছার অপেক্ষায় আছে", errorMessage("deletion_pending", "আপনার অ্যাকাউন্ট মোছার অপেক্ষায় আছে", 409))
        assertEquals("সার্ভারে সমস্যা (500)", errorMessage("", "", 500))
    }
}
