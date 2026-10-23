package com.bspc.blood

import org.json.JSONObject
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
 * অ্যাপে কল: অবস্থার হিসাব (CallMachine), অ্যাপয়েন্টমেন্টের সময়সীমা, সার্ভারের ভুল → বাটন লুকানো/ফোনের বিকল্প,
 * সার্ভারের উত্তর পড়া, যুক্ত হওয়ার পথ (appt_call/{id}), কলের তালিকার লেখা। মিডিয়া (LiveKit) এমুলেটরে/এখানে চলে না।
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallsTest {
    private val join = CallJoin("c1", "c_c1", "wss://example.test/rtc", "tok", CallPeer("u2", "রহিম", ""))
    private val t0 = 1_000_000L

    @Before
    fun setUp() {
        AppPrefs.lang.value = Lang.BN
        CallAvailability.clear()
        LoginGate.uid.value = "u1"
    }

    private fun run(vararg events: CallEvent, start: CallState = CallState()): CallState =
        events.foldIndexed(start) { i, s, e -> CallMachine.reduce(s, e, t0 + i * 1000L) }

    // ---------------- কল করা ----------------

    @Test
    fun outgoingAnsweredThenRemoteHangsUp() {
        var s = run(CallEvent.Dialed(join, CallCtx.BLOOD, "r1", video = false, peerName = "x"))
        assertEquals(CallPhase.OUTGOING, s.phase)
        assertTrue(s.outgoing)
        assertEquals("রহিম", s.peerName)
        assertFalse(s.camOn)
        assertFalse(s.speakerOn)
        assertTrue(s.usesMedia)
        // ঘরে যুক্ত, এখনো বাজছে
        s = CallMachine.reduce(s, CallEvent.RoomConnected, t0)
        assertEquals(CallPhase.OUTGOING, s.phase)
        // সার্ভার "accepted" বললেও অন্যজন ঘরে না আসা পর্যন্ত বাজছে
        s = CallMachine.reduce(s, CallEvent.Server("accepted"), t0)
        assertEquals(CallPhase.OUTGOING, s.phase)
        s = CallMachine.reduce(s, CallEvent.PeerJoined, t0 + 5000)
        assertEquals(CallPhase.ACTIVE, s.phase)
        assertEquals(t0 + 5000, s.connectedAt)
        s = CallMachine.reduce(s, CallEvent.PeerLeft, t0 + 65_000)
        assertEquals(CallPhase.ENDED, s.phase)
        assertEquals(EndReason.REMOTE_ENDED, s.endReason)
        assertFalse(s.usesMedia)
        // শেষ হওয়ার পর আর কিছু বদলায় না (Reset ছাড়া)
        assertEquals(s, CallMachine.reduce(s, CallEvent.PeerJoined, t0 + 70_000))
        assertEquals(CallState(), CallMachine.reduce(s, CallEvent.Reset, t0))
    }

    @Test
    fun outgoingDeclinedOrUnanswered() {
        val dialed = run(CallEvent.Dialed(join, CallCtx.APPOINTMENT, "a1", video = true, peerName = "x"))
        assertTrue(dialed.camOn)
        assertTrue(dialed.speakerOn)
        assertEquals(EndReason.DECLINED, CallMachine.reduce(dialed, CallEvent.Server("declined"), t0).endReason)
        assertEquals(EndReason.NO_ANSWER, CallMachine.reduce(dialed, CallEvent.Server("missed"), t0).endReason)
        assertEquals(CallPhase.OUTGOING, CallMachine.reduce(dialed, CallEvent.Server("ringing"), t0).phase)
        assertEquals("কেউ ধরেননি", CallMachine.endText(CallMachine.reduce(dialed, CallEvent.Server("missed"), t0)))
        // নিজে কেটে দিলে
        assertEquals(EndReason.HANGUP, CallMachine.reduce(dialed, CallEvent.Hangup, t0).endReason)
    }

    // ---------------- কল আসা ----------------

    @Test
    fun incomingAcceptAndCallerCancels() {
        val ring = CallEvent.Ringing("c9", video = true, peerName = "করিম", peerPhoto = "", ctx = CallCtx.AMBULANCE, refId = "q1")
        var s = run(ring)
        assertEquals(CallPhase.INCOMING, s.phase)
        assertFalse(s.outgoing)
        assertFalse(s.usesMedia)
        // কলকারী কেটে দিলেন
        val cancelled = CallMachine.reduce(s, CallEvent.Server("cancelled"), t0)
        assertEquals(EndReason.CANCELLED, cancelled.endReason)
        // অন্য ফোনে ধরা হলো
        assertEquals(CallPhase.ENDED, CallMachine.reduce(s, CallEvent.Server("accepted"), t0).phase)
        // সময় শেষ
        assertEquals(EndReason.MISSED, CallMachine.reduce(s, CallEvent.Server("missed"), t0).endReason)
        // ধরা → যুক্ত
        s = CallMachine.reduce(s, CallEvent.Accepting, t0)
        assertEquals(CallPhase.CONNECTING, s.phase)
        assertTrue(s.usesMedia)
        s = CallMachine.reduce(s, CallEvent.Joined(join.copy(callId = "c9")), t0)
        s = CallMachine.reduce(s, CallEvent.RoomConnected, t0)
        assertEquals(CallPhase.CONNECTING, s.phase)
        s = CallMachine.reduce(s, CallEvent.PeerJoined, t0 + 1000)
        assertEquals(CallPhase.ACTIVE, s.phase)
        // কল চলার সময় সার্ভারের "accepted"/"cancelled" কিছু বদলায় না, "ended" শেষ করে
        assertEquals(CallPhase.ACTIVE, CallMachine.reduce(s, CallEvent.Server("cancelled"), t0).phase)
        assertEquals(EndReason.REMOTE_ENDED, CallMachine.reduce(s, CallEvent.Server("ended"), t0).endReason)
    }

    @Test
    fun secondCallWhileBusyIsIgnored() {
        val active = run(CallEvent.Dialed(join, CallCtx.BLOOD, "r1", false, ""), CallEvent.PeerJoined)
        val other = CallMachine.reduce(active, CallEvent.Ringing("c2", false, "y", "", CallCtx.BLOOD, "r2"), t0)
        assertEquals(active, other)
    }

    // ---------------- অনলাইন অ্যাপয়েন্টমেন্ট: অপেক্ষার ঘর ----------------

    @Test
    fun appointmentWaitingRoom() {
        var s = run(CallEvent.JoiningAppointment("ap1", ""))
        assertEquals(CallPhase.CONNECTING, s.phase)
        assertTrue(s.isAppointment)
        assertTrue(s.video)
        s = CallMachine.reduce(s, CallEvent.Joined(join.copy(callId = "c5")), t0)
        assertEquals("c5", s.callId)
        assertEquals("রহিম", s.peerName)
        s = CallMachine.reduce(s, CallEvent.RoomConnected, t0)
        assertEquals(CallPhase.WAITING, s.phase)
        s = CallMachine.reduce(s, CallEvent.PeerJoined, t0 + 60_000)
        assertEquals(CallPhase.ACTIVE, s.phase)
        // ডাক্তার বের হয়ে গেলে কল শেষ নয় — আবার অপেক্ষার ঘর
        s = CallMachine.reduce(s, CallEvent.PeerLeft, t0 + 120_000)
        assertEquals(CallPhase.WAITING, s.phase)
        assertEquals(t0 + 60_000, s.connectedAt)
        // সার্ভারের "ended"-এও অ্যাপয়েন্টমেন্টের কল থাকে (একই কলে আবার যুক্ত হওয়া যায়)
        assertEquals(CallPhase.WAITING, CallMachine.reduce(s, CallEvent.Server("ended"), t0).phase)
        assertEquals(CallPhase.ACTIVE, CallMachine.reduce(s, CallEvent.PeerJoined, t0).phase)
    }

    @Test
    fun reconnectAndAudioFallback() {
        var s = run(CallEvent.Dialed(join, CallCtx.BLOOD, "r1", true, ""), CallEvent.PeerJoined)
        s = CallMachine.reduce(s, CallEvent.Reconnecting, t0)
        assertEquals(CallPhase.RECONNECTING, s.phase)
        assertTrue(s.usesMedia)
        s = CallMachine.reduce(s, CallEvent.Reconnected, t0)
        assertEquals(CallPhase.ACTIVE, s.phase)
        s = CallMachine.reduce(s, CallEvent.Network(true), t0)
        assertTrue(s.poorNetwork)
        s = CallMachine.reduce(s, CallEvent.AudioFallback, t0)
        assertFalse(s.camOn)
        assertTrue(s.audioFallback)
        // নিজে আবার ক্যামেরা চালু করলে
        s = CallMachine.reduce(s, CallEvent.Camera(true), t0)
        assertTrue(s.camOn)
        assertFalse(s.audioFallback)
        // ঘর থেকে বিচ্ছিন্ন
        assertEquals(EndReason.FAILED, CallMachine.reduce(s, CallEvent.Failed(EndReason.FAILED, "x"), t0).endReason)
    }

    @Test
    fun audioCallUpgradesToVideoOnlyWithConsent() {
        var s = run(CallEvent.Dialed(join, CallCtx.BLOOD, "r1", false, ""))
        assertEquals("u2", s.peerUid)
        // এখনো ধরেননি — অনুরোধ নয়
        assertEquals(s, CallMachine.reduce(s, CallEvent.UpgradeAsked, t0))
        s = CallMachine.reduce(s, CallEvent.PeerJoined, t0)
        // আমি চাইলাম → অপেক্ষা; উনি রাজি হননি → ছোট বার্তা
        s = CallMachine.reduce(s, CallEvent.UpgradeSent, t0)
        assertTrue(s.upgradePending)
        val declined = CallMachine.reduce(s, CallEvent.UpgradeDeclined, t0)
        assertFalse(declined.upgradePending)
        assertTrue(declined.upgradeDeclined)
        assertFalse(CallMachine.reduce(declined, CallEvent.UpgradeDismissed, t0).upgradeDeclined)
        // রাজি হলেন → ভিডিও, ক্যামেরা চালু, লাউডস্পিকার
        val up = CallMachine.reduce(s, CallEvent.Upgraded(camera = true), t0)
        assertTrue(up.video)
        assertTrue(up.camOn)
        assertTrue(up.speakerOn)
        assertFalse(up.upgradePending)
        // ভিডিও কলে আর অনুরোধ নয়
        assertEquals(up, CallMachine.reduce(up, CallEvent.UpgradeSent, t0))
        // অন্যজনের অনুরোধ → আমার উত্তর বাকি; না বললে সরে যায়
        val asked = CallMachine.reduce(s.copy(upgradePending = false), CallEvent.UpgradeAsked, t0)
        assertTrue(asked.upgradeAsked)
        assertFalse(CallMachine.reduce(asked, CallEvent.UpgradeDismissed, t0).upgradeAsked)
        // ভিডিও কল অডিওতে ধরা: দুজনেরই ভিডিও বন্ধ, পরে সম্মতিতে
        val videoCall = run(CallEvent.Ringing("v1", true, "x", "", CallCtx.BLOOD, "r1"), CallEvent.Accepting, CallEvent.Downgraded)
        assertFalse(videoCall.video)
        assertFalse(videoCall.camOn)
        assertEquals(CallPhase.CONNECTING, videoCall.phase)
        // শুধু অ্যাপয়েন্টমেন্ট ভিডিও দিয়ে শুরু (বাকিগুলোয় ভিডিও কল বাছাই করা যায়, ধরার সময় অডিও/ভিডিও)
        assertTrue(CallCtx.videoFirst(CallCtx.APPOINTMENT))
        listOf(CallCtx.BLOOD, CallCtx.AMBULANCE, CallCtx.RX_ORDER, CallCtx.LAB_ORDER).forEach { assertFalse(it, CallCtx.videoFirst(it)) }
    }

    @Test
    fun lockScreenShowsFirstNameOnly() {
        assertEquals("রহিম", CallMachine.firstName("  রহিম উদ্দিন  "))
        assertEquals("", CallMachine.firstName(""))
        val s = CallState(peerName = "রহিম উদ্দিন")
        assertEquals("রহিম", displayName(s, locked = true))
        assertEquals("রহিম উদ্দিন", displayName(s, locked = false))
        assertEquals("লাইফলো কল", CallNotifications.lockText())
    }

    @Test
    fun durationText() {
        assertEquals("০০:০৫", CallMachine.duration(5))
        assertEquals("০৩:২৫", CallMachine.duration(205))
        assertEquals("১:০২:০৫", CallMachine.duration(3725))
    }

    // ---------------- সময়সীমা ----------------

    @Test
    fun appointmentWindows() {
        // ২০২৬-১০-২৩ (UTC মধ্যরাত); ঢাকায় ১৭:০০ = UTC ১১:০০
        val date = 1_792_713_600_000L
        val slot = date + 11 * 3_600_000L
        assertEquals(slot, CallWindows.slotStart(date, "17:00"))
        assertNull(CallWindows.slotStart(date, ""))
        assertNull(CallWindows.slotStart(date, "25:00"))
        val min = 60_000L
        assertTrue(CallWindows.canCall("confirmed", date, "17:00", slot - 30 * min))
        assertFalse(CallWindows.canCall("confirmed", date, "17:00", slot - 31 * min))
        assertTrue(CallWindows.canCall("confirmed", date, "17:00", slot + 3 * 60 * min - 1))
        assertFalse(CallWindows.canCall("confirmed", date, "17:00", slot + 3 * 60 * min))
        assertFalse(CallWindows.canCall("pending", date, "17:00", slot))
        assertTrue(CallWindows.canJoin("confirmed", date, "17:00", slot - 15 * min))
        assertFalse(CallWindows.canJoin("confirmed", date, "17:00", slot - 16 * min))
        assertEquals(slot - 15 * min, CallWindows.joinOpensAt("confirmed", date, "17:00", slot - 60 * min))
        assertNull(CallWindows.joinOpensAt("confirmed", date, "17:00", slot))
        // সময় লেখা না থাকলে: ঢাকার পুরো দিন
        val dhakaMidnight = date - 6 * 3_600_000L
        assertTrue(CallWindows.canCall("confirmed", date, "", dhakaMidnight))
        assertFalse(CallWindows.canCall("confirmed", date, "", dhakaMidnight - 1))
    }

    // ---------------- ভুল → বাটন ----------------

    @Test
    fun failuresHideButtonsAndOfferPhone() {
        assertEquals(CallFailure.NOT_ALLOWED, CallErrors.classify("call_not_allowed"))
        assertEquals(CallFailure.OFF, CallErrors.classify("call_off"))
        assertEquals(CallFailure.BUSY, CallErrors.classify("call_busy"))
        assertEquals(CallFailure.UNAVAILABLE, CallErrors.classify("calls_unavailable"))
        assertEquals(CallFailure.OTHER, CallErrors.classify("weird"))
        assertEquals(CallFailure.BLOCKED, CallErrors.classify("call_blocked"))
        assertEquals(CallFailure.RATE_LIMIT, CallErrors.classify("call_rate"))
        assertEquals(CallFailure.GONE, CallErrors.classify("call_gone"))
        // ব্লক: কে করেছেন বলা হয় না, ফোনের বিকল্পও নয়
        assertFalse(CallErrors.offersPhone(CallFailure.BLOCKED))
        assertTrue(CallErrors.message(CallFailure.RATE_LIMIT).contains("১০ মিনিট"))
        assertEquals(CallFailure.OFF, CallErrors.of(ServerError("উনি অ্যাপে কল বন্ধ রেখেছেন — ফোনে কল করুন", "call_off")))
        assertEquals(CallFailure.NETWORK, CallErrors.of(IllegalStateException("x")))
        // call_off: সার্ভারের বার্তাই, আর ফোনের বিকল্প
        assertEquals("উনি বন্ধ রেখেছেন", CallErrors.message(CallFailure.OFF, "উনি বন্ধ রেখেছেন"))
        assertTrue(CallErrors.message(CallFailure.OFF).contains("ফোনে কল করুন"))
        assertTrue(CallErrors.offersPhone(CallFailure.OFF))
        assertFalse(CallErrors.offersPhone(CallFailure.RATE_LIMIT))

        // জানা না থাকলে বাটন নেই; চালু হলে আছে
        assertFalse(CallAvailability.shows(CallCtx.BLOOD, "r1"))
        CallAvailability.enabled.value = true
        assertTrue(CallAvailability.shows(CallCtx.BLOOD, "r1"))
        assertFalse(CallAvailability.shows(CallCtx.BLOOD, ""))
        // এই রিকোয়েস্টে সম্পর্ক নেই: শুধু এটার বাটন যায়
        CallAvailability.onFailure(CallFailure.NOT_ALLOWED, CallCtx.BLOOD, "r1")
        assertFalse(CallAvailability.shows(CallCtx.BLOOD, "r1"))
        assertTrue(CallAvailability.shows(CallCtx.BLOOD, "r2"))
        assertTrue(CallAvailability.shows(CallCtx.RX_ORDER, "r1"))
        // ব্লক: এই সূত্রের বাটন যায়
        CallAvailability.onFailure(CallFailure.BLOCKED, CallCtx.RX_ORDER, "x9")
        assertFalse(CallAvailability.shows(CallCtx.RX_ORDER, "x9"))
        // ব্যস্ত/বন্ধ: বাটন থাকে
        CallAvailability.onFailure(CallFailure.BUSY, CallCtx.RX_ORDER, "o1")
        CallAvailability.onFailure(CallFailure.OFF, CallCtx.RX_ORDER, "o1")
        assertTrue(CallAvailability.shows(CallCtx.RX_ORDER, "o1"))
        // সার্ভারে কল বন্ধ (503): সব যায়
        CallAvailability.onFailure(CallFailure.UNAVAILABLE, null, null)
        assertFalse(CallAvailability.shows(CallCtx.RX_ORDER, "o1"))
        // অতিথি: কখনো নয়
        CallAvailability.enabled.value = true
        LoginGate.uid.value = null
        assertFalse(CallAvailability.shows(CallCtx.RX_ORDER, "o1"))
    }

    // ---------------- সার্ভারের উত্তর ----------------

    @Test
    fun parsesServerReplies() {
        val j = CallsApi.parseJoin(JSONObject("""{"ok":true,"callId":"c1","room":"c_c1","url":"wss://lifolo.com/rtc","token":"t","video":false,"peer":{"uid":"u2","name":"রহিম"}}"""))
        assertEquals(false, j.video)
        assertEquals("wss://lifolo.com/rtc", j.url)
        assertEquals("রহিম", j.peer?.name)
        // call_accept-এ callId না থাকলে জানা id
        assertEquals("c7", CallsApi.parseJoin(JSONObject("""{"room":"r","url":"wss://x","token":"t"}"""), "c7").callId)
        // url/token ছাড়া উত্তর = কল চালু নেই
        val e = runCatching { CallsApi.parseJoin(JSONObject("""{"ok":true}""")) }.exceptionOrNull()
        assertEquals("calls_unavailable", (e as ServerError).code)

        // call_status: চলমান কলে নতুন টোকেন, ভিডিও চালু হয়েছে কি না
        val st = CallsApi.parseStatus(JSONObject("""{"ok":true,"callId":"c1","state":"accepted","video":true,"url":"wss://x","token":"t2","peer":{"uid":"u2","name":"রহিম","photoThumb":"abc"}}"""))
        assertTrue(st.video)
        assertEquals("t2", st.join("c1")?.token)
        assertEquals("abc", st.peer?.photoThumb)
        assertNull(CallsApi.parseStatus(JSONObject("""{"state":"ended"}""")).join("c1"))

        val h = CallsApi.parseHistory(JSONObject("""{"callId":"c1","ctx":"blood","refId":"r1","peer":{"uid":"u2","name":"রহিম"},"video":true,"state":"missed","dir":"in","startedAt":5,"durationSec":0}"""))
        assertTrue(h.incoming)
        assertTrue(h.missed)
        assertEquals("মিসড কল", CallHistoryText.stateText(h))
        assertEquals(R.drawable.ic_call_missed, CallHistoryText.icon(h))
        val out = h.copy(dir = "out", state = "ended", durationSec = 75)
        assertFalse(out.missed)
        assertEquals("০১:১৫", CallHistoryText.stateText(out))
        assertEquals(R.drawable.ic_call_made, CallHistoryText.icon(out))
        assertEquals("কেউ ধরেননি", CallHistoryText.stateText(h.copy(dir = "out")))
        assertEquals("ভিডিও · রক্তের রিকোয়েস্ট", CallHistoryText.kind(h))
    }

    @Test
    fun appointmentCallRoute() {
        assertEquals("appt_call/ap1", apptCallRoute("ap1"))
        assertEquals("ap1", apptCallRouteId("appt_call/ap1"))
        assertEquals("ap1", apptCallRouteId("/appt_call/ap1"))
        assertNull(apptCallRouteId("appt_call/"))
        assertNull(apptCallRouteId("appt_call/a b"))
        assertNull(apptCallRouteId("rx/1"))
    }

    // ---------------- পুশ ----------------

    @Test
    fun incomingPushRingsOnceAndStateClears() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        CallManager.reset()
        assertTrue(CallManager.onIncoming(ctx, "p1", true, "করিম", "", CallCtx.BLOOD, "r1"))
        assertEquals(CallPhase.INCOMING, CallManager.state.value.phase)
        // একই কল আবার (পুরো পর্দার intent) — চলছে
        assertTrue(CallManager.onIncoming(ctx, "p1", true, "করিম", "", CallCtx.BLOOD, "r1"))
        // অন্য কল বাজার সময় — নয়
        assertFalse(CallManager.onIncoming(ctx, "p2", false, "x", "", CallCtx.BLOOD, "r2"))
        // কলকারী কেটে দিলেন (call_state পুশ)
        assertTrue(CallPush.handle(ctx, mapOf("type" to "call_state", "callId" to "p1", "state" to "cancelled")))
        assertEquals(CallPhase.ENDED, CallManager.state.value.phase)
        // শেষ হওয়া কল আবার বাজে না
        assertFalse(CallManager.onIncoming(ctx, "p1", true, "করিম", "", CallCtx.BLOOD, "r1"))
        CallManager.reset()
        assertEquals(CallPhase.IDLE, CallManager.state.value.phase)
        // ভিডিওর অনুরোধের পুশ: কল না থাকলে কিছু হয় না, কিন্তু নোটিফিকেশনও নয়
        assertTrue(CallPush.handle(ctx, mapOf("type" to "call_upgrade", "callId" to "zz", "by" to "u2")))
        assertTrue(CallPush.handle(ctx, mapOf("type" to "call_state", "callId" to "zz", "state" to "upgrade_request")))
        assertEquals(CallPhase.IDLE, CallManager.state.value.phase)
        // অন্য পুশ সাধারণ নোটিফিকেশনে যায়
        assertFalse(CallPush.handle(ctx, mapOf("type" to "appt_call_ready", "appointmentId" to "a1")))
        assertFalse(CallPush.handle(ctx, mapOf("type" to "call_missed", "callId" to "p1")))
    }
}
