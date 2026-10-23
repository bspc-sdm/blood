package com.bspc.blood

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * অ্যাপে মেসেজ: সার্ভারের উত্তর পড়া (CHAT.md), সারির মেসেজ আর সার্ভারের মেসেজ মেলানো (clientId), ✓/✓✓, ১০ মিনিটে মুছে ফেলা,
 * পড়া জানানো, বাংলা ভুলের বার্তা আর কোনটা আবার পাঠানো হবে, তালিকার লেখা, লক স্ক্রিনে লেখা ছাড়া নোটিফিকেশন।
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatTest {
    private val me = "u1"
    private val peer = "u2"
    private val t0 = 1_760_000_000_000L

    @Before
    fun setUp() {
        AppPrefs.lang.value = Lang.BN
        LoginGate.uid.value = me
        LoginGate.profileReady.value = true
        ChatEvents.activeThread = null
    }

    @After
    fun tearDown() {
        ChatEvents.activeThread = null
        LoginGate.profileReady.value = false
        LoginGate.pending.value = null
    }

    private fun msg(id: String, from: String, at: Long, text: String = "x", readAt: Long? = null, clientId: String = "") =
        ChatMessage(id, "t1", from, text = text, at = at, readAt = readAt, clientId = clientId)

    private fun pending(clientId: String, at: Long, text: String = "x") =
        ChatMessage("", "t1", me, text = text, at = at, clientId = clientId, pending = true)

    // ---------------- সার্ভারের উত্তর ----------------

    @Test
    fun parsesOpenPageAndMessages() {
        val o = JSONObject(
            """
            {"threadId":"t1","peer":{"uid":"u2","name":"করিম মিয়া","photoThumb":"abc"},"canSend":false,
             "messages":[
               {"id":"m2","threadId":"t1","from":"u2","text":"আসছি","at":${t0 + 2000}},
               {"id":"m1","threadId":"t1","from":"u1","photoUrl":"https://r2.test/p.jpg?sig=1","photoThumb":"AAAA","at":$t0,"readAt":${t0 + 5000}},
               {"id":"m3","threadId":"t1","from":"u1","location":{"lat":23.99,"lng":90.42},"at":${t0 + 3000},"readAt":null},
               {"id":"m4","threadId":"t1","from":"u2","unsent":true,"at":${t0 + 4000}}
             ],
             "before":"cur-1"}
            """,
        )
        val open = ChatApi.parseOpen(o)
        assertEquals("t1", open.threadId)
        assertEquals(ChatPeer("u2", "করিম মিয়া", "abc"), open.peer)
        assertFalse(open.canSend)
        assertEquals("cur-1", open.page.before)
        // পুরোনো → নতুন
        assertEquals(listOf("m1", "m2", "m3", "m4"), open.page.messages.map { it.id })
        val photo = open.page.messages[0]
        assertTrue(photo.hasPhoto)
        assertEquals(t0 + 5000, photo.readAt)
        assertEquals(ChatLocation(23.99, 90.42), open.page.messages[2].location)
        assertNull(open.page.messages[2].readAt)
        assertTrue(open.page.messages[3].unsent)
        assertEquals("মেসেজ মুছে ফেলা হয়েছে", ChatLogic.bodyText(open.page.messages[3]))

        // শেষ পাতা: before null বা নেই
        assertNull(ChatApi.parsePage(JSONObject("""{"messages":[],"before":null}"""), "t1").before)
        assertNull(ChatApi.parsePage(JSONObject("""{"messages":[]}"""), "t1").before)
        assertEquals(false, open.page.canSend)
        // chat_messages আবার যাচাই করে: বন্ধ / আমি যোগাযোগ বন্ধ রেখেছি
        val again = ChatApi.parsePage(JSONObject("""{"messages":[],"before":null,"canSend":false,"blocked":true,"closed":false}"""), "t1")
        assertEquals(false, again.canSend)
        assertTrue(again.blocked)
        assertEquals(ChatFailure.TOO_LATE, ChatErrors.classify("chat_unsend_window"))
        // chat_open {threadId}-এ সূত্রও এলে
        val withCtx = ChatApi.parseOpen(JSONObject("""{"threadId":"t9","ctx":"blood","refId":"r1","peer":{"uid":"u2","name":"ক"},"canSend":true,"messages":[]}"""))
        assertEquals("blood", withCtx.ctx)
        assertEquals("r1", withCtx.refId)
    }

    @Test
    fun parsesThreads() {
        val t = ChatApi.parseThread(
            JSONObject("""{"threadId":"t1","ctx":"blood","refId":"r1","peer":{"uid":"u2","name":"করিম"},"last":{"text":"📷","at":$t0,"mine":true},"unread":3,"canSend":true}"""),
        )
        assertEquals("blood", t.ctx)
        assertEquals(3, t.unread)
        assertEquals("আপনি: 📷 ছবি", ChatLogic.preview(t))
        assertEquals("📍 লোকেশন", ChatLogic.preview(t.copy(lastText = "📍", lastMine = false)))
        assertEquals("ঠিক আছে ধন্যবাদ", ChatLogic.preview(t.copy(lastText = "ঠিক আছে\nধন্যবাদ", lastMine = false)))
        assertEquals("রক্তের অনুরোধ", ChatCtx.label("blood"))
        assertEquals("অ্যাম্বুলেন্স", ChatCtx.label("ambulance"))
        assertEquals("ওষুধের অর্ডার", ChatCtx.label("rx_order"))
        assertEquals("পরীক্ষার অর্ডার", ChatCtx.label("lab_order"))
    }

    // ---------------- সারি আর সার্ভার মেলানো ----------------

    @Test
    fun queuedMessageReplacedByServerCopyWithSameClientId() {
        val server = listOf(msg("m1", peer, t0))
        val local = listOf(pending("c1", t0 + 10_000, "আসছি"))
        val shown = ChatLogic.merge(server, local)
        assertEquals(2, shown.size)
        assertTrue(shown.last().pending)
        // সার্ভারে পৌঁছাল (একই clientId) — সারির কপি সরে যায়, দুবার দেখায় না
        val after = ChatLogic.merge(shown, listOf(msg("m2", me, t0 + 9_000, "আসছি", clientId = "c1")))
        assertEquals(listOf("m1", "m2"), after.map { it.id })
        assertFalse(after.any { it.pending })
        // আবার একই পাতা এলে বাড়ে না
        assertEquals(after, ChatLogic.merge(after, listOf(msg("m2", me, t0 + 9_000, "আসছি", clientId = "c1"))))
    }

    @Test
    fun mergeKeepsReadReceiptAndOrdersByTime() {
        val a = listOf(msg("m2", me, t0 + 2, readAt = t0 + 50), msg("m1", peer, t0 + 1))
        val merged = ChatLogic.merge(a, listOf(msg("m2", me, t0 + 2, readAt = null), msg("m0", peer, t0)))
        assertEquals(listOf("m0", "m1", "m2"), merged.map { it.id })
        assertEquals(t0 + 50, merged.last().readAt)
        // ব্যর্থ মেসেজ শেষে থাকে (আবার চেষ্টা/মুছুন)
        val failed = pending("c9", t0 - 100).copy(pending = false, failedReason = "বন্ধ")
        assertEquals("c9", ChatLogic.merge(merged, listOf(failed)).last().clientId)
    }

    @Test
    fun ticks() {
        assertEquals(ChatLogic.Tick.PENDING, ChatLogic.tick(pending("c1", t0)))
        assertEquals(ChatLogic.Tick.SENT, ChatLogic.tick(msg("m1", me, t0)))
        assertEquals(ChatLogic.Tick.READ, ChatLogic.tick(msg("m1", me, t0, readAt = t0 + 1)))
        assertEquals(ChatLogic.Tick.FAILED, ChatLogic.tick(pending("c1", t0).copy(failedReason = "x")))
        // অন্যজন পড়লেন (chat_read upTo): সেই সময় পর্যন্ত আমার মেসেজ ✓✓, পরেরটা নয়, ওঁর মেসেজ ছোঁয় না
        val l = listOf(msg("a", me, t0), msg("b", peer, t0 + 1), msg("c", me, t0 + 2), msg("d", me, t0 + 10))
        val read = ChatLogic.markReadByPeer(l, me, t0 + 2, now = t0 + 99)
        assertEquals(listOf(t0 + 99, null, t0 + 99, null), read.map { it.readAt })
    }

    @Test
    fun unsendOnlyOwnWithinTenMinutes() {
        val m = msg("m1", me, t0)
        assertTrue(ChatLogic.canUnsend(m, me, now = t0 + 9 * 60_000))
        assertTrue(ChatLogic.canUnsend(m, me, now = t0 + 10 * 60_000))
        assertFalse(ChatLogic.canUnsend(m, me, now = t0 + 10 * 60_000 + 1))
        assertFalse(ChatLogic.canUnsend(msg("m1", peer, t0), me, now = t0 + 1))
        assertFalse(ChatLogic.canUnsend(m.copy(unsent = true), me, now = t0 + 1))
        // এখনো পৌঁছায়নি (id নেই) — সারি থেকে "মুছুন"
        assertFalse(ChatLogic.canUnsend(pending("c1", t0), me, now = t0 + 1))
    }

    @Test
    fun readUpToOnlyNewerPeerMessages() {
        val l = listOf(msg("a", peer, t0), msg("b", me, t0 + 5), msg("c", peer, t0 + 3))
        assertEquals(t0 + 3, ChatLogic.readUpTo(l, me, 0))
        assertNull(ChatLogic.readUpTo(l, me, t0 + 3))
        assertNull(ChatLogic.readUpTo(listOf(msg("b", me, t0)), me, 0))
    }

    // ---------------- ভুল আর আবার চেষ্টা ----------------

    @Test
    fun banglaErrorsAndRetry() {
        assertEquals(ChatFailure.RATE_LIMIT, ChatErrors.classify("chat_rate"))
        assertEquals(ChatFailure.NOT_ALLOWED, ChatErrors.classify("chat_not_allowed"))
        assertEquals(ChatFailure.BLOCKED, ChatErrors.classify("call_blocked"))
        assertEquals(ChatFailure.CLOSED, ChatErrors.classify("chat_closed"))
        assertTrue(ChatErrors.message(ChatFailure.RATE_LIMIT).contains("এক মিনিট পরে"))
        assertTrue(ChatErrors.message(ChatFailure.CLOSED).contains("বন্ধ"))
        assertTrue(ChatErrors.message(ChatFailure.NOT_ALLOWED).contains("মেসেজ পাঠানো যাচ্ছে না"))
        // ব্লক: কে কাকে ব্লক করেছেন বলা হয় না
        assertFalse(ChatErrors.message(ChatFailure.BLOCKED).contains("ব্লক"))
        // কোনটা সারিতে থেকে আবার যাবে
        assertTrue(ChatErrors.retryable(IllegalStateException("সার্ভারে পৌঁছানো যায়নি")))
        assertTrue(ChatErrors.retryable(ServerError("busy", null)))
        assertTrue(ChatErrors.retryable(ServerError("rate", "chat_rate")))
        assertFalse(ChatErrors.retryable(ServerError("closed", "chat_closed")))
        assertFalse(ChatErrors.retryable(ServerError("no", "chat_not_allowed")))
        assertFalse(ChatErrors.retryable(ServerError("no", "call_blocked")))
        assertTrue(ChatErrors.closesThread(ChatFailure.CLOSED))
        assertFalse(ChatErrors.closesThread(ChatFailure.RATE_LIMIT))
        // ২, ৪, ৮ … সেকেন্ড, সর্বোচ্চ ১ মিনিট; 429-এ অন্তত ২০ সেকেন্ড
        assertEquals(4_000L, ChatRetry.delayMs(1))
        assertEquals(60_000L, ChatRetry.delayMs(9))
        assertEquals(20_000L, ChatRetry.delayMs(1, rateLimited = true))
    }

    @Test
    fun queuedItemSurvivesRestart() {
        val q = QueuedChat("c1", me, "t1", "হ্যালো", "c1.jpg", ChatLocation(23.9, 90.4), t0, attempts = 2)
        val back = QueuedChat.fromJson(JSONObject(q.toJson().toString()))
        assertEquals(q, back)
        val m = back.asMessage()
        assertTrue(m.pending)
        assertEquals("c1", m.clientId)
        assertEquals(QueuedChat.LOCAL_PHOTO + "c1.jpg", m.photoThumb)
        val failed = QueuedChat.fromJson(JSONObject(q.copy(failedReason = "বন্ধ").toJson().toString()))
        assertEquals("বন্ধ", failed.asMessage().failedReason)
        assertFalse(failed.asMessage().pending)
    }

    @Test
    fun textLimits() {
        assertFalse(ChatLogic.canSendText("   "))
        assertTrue(ChatLogic.canSendText("ঠিক আছে"))
        assertTrue(ChatLogic.canSendText("ক".repeat(1000)))
        assertFalse(ChatLogic.canSendText("ক".repeat(1001)))
        assertTrue(ChatLogic.newClientId() != ChatLogic.newClientId())
    }

    // ---------------- টাকা নিয়ে সতর্কতা, রিপোর্টের কারণ ----------------

    @Test
    fun moneyWarnAndReportReason() {
        val m = ChatApi.parseMessage(JSONObject("""{"id":"m1","threadId":"t1","from":"u2","text":"০১৭… নম্বরে বিকাশ করুন","at":$t0,"warn":"money_number"}"""))
        assertEquals("money_number", m.warn)
        assertEquals("", ChatApi.parseMessage(JSONObject("""{"id":"m2","from":"u2","at":$t0}""")).warn)
        assertEquals("রক্তের জন্য টাকা লেনদেন নিষেধ — কেউ টাকা চাইলে রিপোর্ট করুন", ChatLogic.moneyWarnText(blood = true))
        assertEquals("সাবধান: কেউ অন্য নম্বরে টাকা পাঠাতে বললে পাঠাবেন না — রিপোর্ট করুন", ChatLogic.moneyWarnText(blood = false))
        // "অন্য নম্বরে টাকা চেয়েছে" — "অন্য কারণ"-এর আগে, কলের কারণগুলো সব থাকে
        assertEquals("asked_other_number", ChatReportText.ALL[ChatReportText.ALL.size - 2])
        assertEquals("other", ChatReportText.ALL.last())
        assertTrue(ChatReportText.ALL.containsAll(CallReportReason.ALL))
        assertEquals("অন্য নম্বরে টাকা চেয়েছে", ChatReportText.label("asked_other_number"))
        assertEquals(ChatFailure.OTHER, ChatErrors.classify("feature_off"))
        assertFalse(ChatErrors.retryable(ServerError("এই সুবিধা শিগগির আসছে", "feature_off")))
    }

    @Test
    fun unreadIsZeroWhileChatIsOff() {
        Features.set(mapOf(Features.CHAT to false), uid = me)
        ChatUnread.total.intValue = 5
        kotlinx.coroutines.runBlocking { ChatUnread.refresh(force = true) }
        assertEquals(0, ChatUnread.total.intValue)
        Features.clear()
    }

    // ---------------- নোটিফিকেশন ----------------

    @Test
    fun lockScreenTextHasNoMessage() {
        assertEquals("লাইফলো মেসেজ — করিম", ChatLogic.notifyTitle("করিম মিয়া"))
        assertEquals("লাইফলো মেসেজ", ChatLogic.notifyTitle(""))
        assertEquals("নতুন মেসেজ", ChatLogic.notifyBody(1))
        assertEquals("৩টি নতুন মেসেজ", ChatLogic.notifyBody(3))
    }

    @Test
    fun pushShowsOneNotificationPerThreadWithReply() {
        val ctx: Context = RuntimeEnvironment.getApplication()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        Notifier.ensureChannels(ctx)
        assertNotNull(nm.getNotificationChannel("cat_messages"))
        val data = mapOf("type" to "chat_message", "threadId" to "t1", "messageId" to "m1", "from" to "করিম", "cat" to "messages")
        assertTrue(ChatPush.handle(ctx, data))
        assertTrue(ChatPush.handle(ctx, data + ("messageId" to "m2")))
        val shown = nm.activeNotifications.filter { it.id == ChatPush.notificationId("t1") }
        assertEquals(1, shown.size)
        val n = shown[0].notification
        assertEquals("cat_messages", n.channelId)
        assertEquals("লাইফলো মেসেজ — করিম", n.extras.getCharSequence(NotificationCompat.EXTRA_TITLE).toString())
        assertEquals("২টি নতুন মেসেজ", n.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString())
        assertEquals(1, n.actions.size)
        assertNotNull(n.actions[0].remoteInputs)
        // থ্রেড খুললে সরে যায়, সংখ্যা আবার ১ থেকে
        ChatPush.clear(ctx, "t1")
        assertTrue(nm.activeNotifications.none { it.id == ChatPush.notificationId("t1") })
        // সামনে খোলা থ্রেডে নোটিফিকেশন নয়
        ChatEvents.activeThread = "t1"
        assertTrue(ChatPush.handle(ctx, data))
        assertTrue(nm.activeNotifications.none { it.id == ChatPush.notificationId("t1") })
        // অন্য ধরনের পুশ ছোঁয় না
        assertFalse(ChatPush.handle(ctx, mapOf("type" to "call_missed", "callId" to "c1")))
    }

    @Test
    fun openChatNeedsRefIdAndSetsTarget() {
        ChatNav.open.value = null
        openChat(CallCtx.BLOOD, "", "u2")
        assertNull(ChatNav.open.value)
        openChat(CallCtx.BLOOD, "r1", "u2")
        assertEquals(ChatNav.Target(CallCtx.BLOOD, "r1", "u2"), ChatNav.open.value)
        ChatNav.open.value = null
        openChatThread("t1")
        assertEquals("t1", ChatNav.open.value?.threadId)
        ChatNav.open.value = null
    }
}
