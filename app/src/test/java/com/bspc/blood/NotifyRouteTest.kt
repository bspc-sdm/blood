package com.bspc.blood

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * নোটিফিকেশন চাপলে কোন পাতা — সার্ভারের ২১ ধরনের পুশের data (backend; e2e/web/journeys/11-notifications.mjs-এর
 * PUSH-* ঘটনাগুলোর data হুবহু) থেকে। আর runCatching কোরুটিন বাতিলকে ভুল ধরে না (হোমের "রিকোয়েস্ট লোড হয়নি")।
 */
class NotifyRouteTest {
    private fun r(vararg kv: Pair<String, String>, badge: String? = null, work: Boolean = false) = notifyRoute(kv.toMap(), badge, work)
    private fun open(s: Screen, w: String? = null) = NotifyRoute.Open(s, w)
    private fun tab(t: Int, svc: String? = null, homeCare: Boolean = false) = NotifyRoute.Tab(t, svc, homeCare)

    @Test
    fun everyServerPushType() {
        // রক্ত
        assertEquals(open(Screen.RequestDetail("r1")), r("type" to "request", "requestId" to "r1"))
        assertEquals(open(Screen.MyRequestDetail("r1")), r("type" to "response", "requestId" to "r1"))
        assertEquals(tab(Tabs.HOME), r("type" to "request_removed"))
        assertEquals(tab(Tabs.HOME), r("type" to "emergency", "emergencyId" to "e1"))
        assertEquals(tab(Tabs.HOME), r("type" to "eligible"))
        assertEquals(tab(Tabs.PROFILE), r("type" to "correction"))
        // কেয়ারগিভার
        assertEquals(open(Screen.Care), r("type" to "care"))
        assertEquals(open(Screen.Care), r("type" to "care", "asUid" to "p1"))
        assertEquals(NotifyRoute.CareRx("p1", "x1"), r("type" to "care", "asUid" to "p1", "rxId" to "x1"))
        // সাহায্য, হাসপাতাল, ব্যাজ
        assertEquals(open(Screen.Help()), r("type" to "support", "ticketId" to "t1"))
        assertEquals(tab(Tabs.HOME), r("type" to "hospital"))
        assertEquals(tab(Tabs.PROFILE), r("type" to "badge"))
        // ব্যবসা (অনুমোদন/কাগজ চাওয়া, অ্যাম্বুলেন্স চালককে ডাক)
        assertEquals(open(Screen.MyBusiness("ambulance")), r("type" to "services", "kind" to "ambulance"))
        assertEquals(open(Screen.MyBusiness("ambulance")), r("type" to "services", "kind" to "ambulance", "sub" to "amb_req", "reqId" to "a1"))
        assertEquals(open(Screen.MyBusiness("lab")), r("type" to "services", "kind" to "lab"))
        assertEquals(tab(Tabs.WORK), r("type" to "services", work = true))
        assertEquals(tab(Tabs.HOME), r("type" to "services"))
        assertEquals(open(Screen.AmbulanceRequest("a1")), r("type" to "amb_req_mine", "reqId" to "a1"))
        assertEquals(tab(Tabs.SERVICES), r("type" to "amb_req_mine"))
        // স্বাস্থ্য
        assertEquals(open(Screen.QuestionDetail("q1")), r("type" to "answer", "questionId" to "q1"))
        assertEquals(open(Screen.ChamberEdit), r("type" to "appointment", "role" to "provider"))
        assertEquals(open(Screen.MyAppointments), r("type" to "appointment", "role" to "patient"))
        // ডাক্তার নিজে রোগী হিসেবে অ্যাপয়েন্টমেন্ট নিলে: role=patient → নিজের অ্যাপয়েন্টমেন্ট, চেম্বার নয়
        assertEquals(open(Screen.MyAppointments), r("type" to "appointment", "role" to "patient", badge = "doctor"))
        assertEquals(open(Screen.ChamberEdit), r("type" to "appointment", badge = "doctor"))
        assertEquals(open(Screen.MyAppointments), r("type" to "appointment", badge = "nurse"))
        assertEquals(tab(Tabs.APPOINTMENT, homeCare = true), r("type" to "homecare"))
        assertEquals(tab(Tabs.APPOINTMENT, homeCare = true), r("type" to "homevisit"))
        // প্রেসক্রিপশন
        assertEquals(NotifyRoute.Rx("x1"), r("type" to "rx", "id" to "x1", "rxId" to "x1"))
        assertEquals(NotifyRoute.Rx("x1"), r("type" to "rx", "id" to "x1"))
        assertEquals(open(Screen.MyPrescriptions), r("type" to "rx"))
        assertEquals(open(Screen.RxReviews), r("type" to "rx_review", "rxId" to "x1", "labOrderId" to "l1"))
        assertEquals(open(Screen.MyBusiness("pharmacy"), "orders"), r("type" to "rx_pharmacy"))
        assertEquals(open(Screen.MyBusiness("lab"), "orders"), r("type" to "rx_lab"))
        assertEquals(open(Screen.MyBusiness("pharmacy"), "finds"), r("type" to "med_find"))
        assertEquals(tab(Tabs.SERVICES, "med_find_mine"), r("type" to "med_find_mine"))
        // পুরোনো ধরন, অচেনা ধরন
        assertEquals(open(Screen.MyBusiness("pharmacy"), "orders"), r("type" to "pharmacy_order"))
        assertEquals(tab(Tabs.HOME), r("type" to "something_new"))
        assertEquals(tab(Tabs.SERVICES, "amb_call"), r("type" to "amb_call"))
        // শেয়ার করা লিংক
        assertEquals(open(Screen.RequestDetail("r9")), r("requestId" to "r9", SHARED_LINK to "1"))
    }

    @Test
    fun notificationCarriesAllRoutingData() {
        val data = mapOf(
            "type" to "appointment", "role" to "provider", "title" to "t", "body" to "b", "forUid" to "u",
            "rxId" to "", "ticketId" to "t1",
        )
        assertEquals(mapOf("type" to "appointment", "role" to "provider", "ticketId" to "t1"), Notifier.extrasOf(data))
        val amb = mapOf("type" to "services", "kind" to "ambulance", "sub" to "amb_req", "reqId" to "a1")
        assertEquals(amb, Notifier.extrasOf(amb))
    }

    @Test
    fun cancellationIsNotAFailure() {
        try {
            runCatching { throw CancellationException("left the page") }
            fail("cancellation was swallowed")
        } catch (e: CancellationException) {
            assertEquals("left the page", e.message)
        }
        assertTrue(runCatching { error("boom") }.isFailure)
        // সময় পেরোনো (withTimeout) আগের মতোই ব্যর্থতা
        val r = runBlocking { runCatching { withTimeout(10) { delay(1000) } } }
        assertTrue(r.exceptionOrNull() is TimeoutCancellationException)
    }

    @Test
    fun demoPhonesOnlyForDemoAccounts() {
        assertTrue(Rules.isMobile("01712345678", demo = false))
        assertTrue(Rules.isMobile("+8801712345678", demo = false))
        // ডেমোর নার্স (0120000xxxx): ডেমো অ্যাকাউন্টে চলে, আসল ব্যবহারকারীর জন্য নয়
        assertTrue(Rules.isMobile("01200001109", demo = true))
        assertTrue(!Rules.isMobile("01200001109", demo = false))
        // ডেমোতেও অন্য 012… নয়
        assertTrue(!Rules.isMobile("01212345678", demo = true))
        assertTrue(!Rules.isMobile("0171234567", demo = true))
    }
}
