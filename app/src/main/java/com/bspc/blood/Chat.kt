package com.bspc.blood

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONObject

// ------------------------------------------------------------
// অ্যাপে মেসেজ — সার্ভারের সাথে চুক্তি backend/docs/CHAT.md।
// কলের মতোই সম্পর্ক (রক্তের রিকোয়েস্ট, অ্যাপয়েন্টমেন্ট, অ্যাম্বুলেন্স, ওষুধ/পরীক্ষার অর্ডার), একই ব্লক আর রিপোর্ট।
// প্রতি জোড়া + সূত্র (ctx + refId) একটা থ্রেড; সম্পর্ক শেষ হলে শুধু পড়া যায়, ৯০ দিন পরে মুছে যায়।
// এই ফাইলে শুধু হিসাব আর সার্ভারের কাজ (টেস্ট করা যায়); পাঠানোর সারি ChatQueue.kt, পর্দা ChatScreens.kt,
// নোটিফিকেশন ChatPush.kt।
// ------------------------------------------------------------

data class ChatPeer(val uid: String, val name: String, val photoThumb: String = "")

data class ChatLocation(val lat: Double, val lng: Double)

data class ChatMessage(
    val id: String,
    val threadId: String,
    val from: String,
    val text: String = "",
    val photoUrl: String = "",
    val photoThumb: String = "",
    val location: ChatLocation? = null,
    val at: Long = 0,
    val readAt: Long? = null,
    val unsent: Boolean = false,
    /** এই ফোন থেকে পাঠানো (সারিতে থাকা) মেসেজের চিহ্ন — সার্ভার একই clientId-তে আবার নতুন মেসেজ বানায় না */
    val clientId: String = "",
    /** এখনো সার্ভারে পৌঁছায়নি (সারিতে) */
    val pending: Boolean = false,
    /** পাঠানো যায়নি, আর চেষ্টা হবে না (বন্ধ থ্রেড, অনুমতি নেই) — বার্তা */
    val failedReason: String? = null,
    /** সার্ভারের সতর্কতা (যেমন "money_number" — অন্য নম্বরে টাকা চাওয়া): মেসেজের নিচে MoneyWarnBanner */
    val warn: String = "",
) {
    val hasPhoto: Boolean get() = photoUrl.isNotBlank() || photoThumb.isNotBlank()
}

/**
 * chat_open / chat_messages-এর উত্তর: মেসেজ (পুরোনো → নতুন) আর আরও আগের পাতার চিহ্ন (না থাকলে শেষ)।
 * canSend/blocked: সার্ভার প্রতিবার আবার যাচাই করে (null = উত্তরে নেই)।
 */
data class ChatPage(val messages: List<ChatMessage>, val before: String?, val canSend: Boolean? = null, val blocked: Boolean = false)

data class ChatOpen(
    val threadId: String,
    val peer: ChatPeer,
    val canSend: Boolean,
    val page: ChatPage,
    /** সূত্র (chat_open {threadId}-এর উত্তরে সার্ভার দিলে; নইলে খালি) */
    val ctx: String = "",
    val refId: String = "",
)

/** মেসেজের তালিকার এক সারি (chat_threads) */
data class ChatThread(
    val threadId: String,
    val ctx: String,
    val refId: String,
    val peer: ChatPeer,
    /** শেষ মেসেজ: লেখা, ছবি হলে "📷", লোকেশন হলে "📍" */
    val lastText: String,
    val lastAt: Long,
    val lastMine: Boolean,
    val unread: Int,
    val canSend: Boolean,
)

/** কোন সম্পর্কের সূত্রে মেসেজ (কলের মতোই — CallCtx) */
object ChatCtx {
    val ALL = setOf(CallCtx.BLOOD, CallCtx.APPOINTMENT, CallCtx.AMBULANCE, CallCtx.RX_ORDER, CallCtx.LAB_ORDER)

    fun label(ctx: String): String = when (ctx) {
        CallCtx.BLOOD -> tr("Blood request", "রক্তের অনুরোধ")
        CallCtx.APPOINTMENT -> tr("Appointment", "অ্যাপয়েন্টমেন্ট")
        CallCtx.AMBULANCE -> tr("Ambulance", "অ্যাম্বুলেন্স")
        CallCtx.RX_ORDER -> tr("Medicine order", "ওষুধের অর্ডার")
        CallCtx.LAB_ORDER -> tr("Lab order", "পরীক্ষার অর্ডার")
        else -> ""
    }
}

/** সার্ভারের ভুলের ধরন — পাঠানোর সারি এর ওপর ঠিক করে আবার চেষ্টা হবে কি না */
enum class ChatFailure { RATE_LIMIT, NOT_ALLOWED, BLOCKED, CLOSED, TOO_LATE, NETWORK, OTHER }

object ChatErrors {
    fun classify(code: String?): ChatFailure = when (code) {
        "chat_rate", "rate_limited", "too_many_requests", "quota" -> ChatFailure.RATE_LIMIT
        "chat_not_allowed" -> ChatFailure.NOT_ALLOWED
        "call_blocked", "chat_blocked" -> ChatFailure.BLOCKED
        "chat_closed" -> ChatFailure.CLOSED
        // ফিচার সুইচ বন্ধ (404) — সার্ভারের বার্তা "এই সুবিধা শিগগির আসছে"
        Features.OFF_CODE -> ChatFailure.OTHER
        "chat_unsend_window", "chat_unsend_late" -> ChatFailure.TOO_LATE
        else -> ChatFailure.OTHER
    }

    fun of(t: Throwable): ChatFailure = when (t) {
        is ServerError -> classify(t.code)
        is LoginRequired -> ChatFailure.OTHER
        else -> ChatFailure.NETWORK
    }

    /** সারিতে থাকা মেসেজ আবার পাঠানো হবে (ইন্টারনেট নেই, অনেক তাড়াতাড়ি, সার্ভারের সাময়িক সমস্যা) */
    fun retryable(t: Throwable): Boolean = when (t) {
        is ServerError -> t.code.isNullOrBlank() || classify(t.code) == ChatFailure.RATE_LIMIT
        is LoginRequired -> false
        else -> true
    }

    fun message(f: ChatFailure, serverText: String? = null): String = when (f) {
        ChatFailure.RATE_LIMIT -> tr(
            "You're sending too fast. Wait a minute and try again.",
            "খুব দ্রুত অনেক মেসেজ পাঠানো হয়েছে। এক মিনিট পরে আবার চেষ্টা করুন।",
        )
        ChatFailure.NOT_ALLOWED -> tr(
            "You can't message this person here now.",
            "এখন এখানে এই মানুষকে মেসেজ পাঠানো যাচ্ছে না।",
        )
        // কে কাকে ব্লক করেছেন বলা হয় না
        ChatFailure.BLOCKED -> tr("You can't message this person.", "এই মানুষকে মেসেজ পাঠানো যাবে না।")
        ChatFailure.CLOSED -> tr(
            "This conversation is closed. You can read it but not reply.",
            "এই কথোপকথন বন্ধ হয়ে গেছে। পড়া যাবে, উত্তর দেওয়া যাবে না।",
        )
        ChatFailure.TOO_LATE -> tr(
            "Messages can only be removed within 10 minutes.",
            "শুধু ১০ মিনিটের মধ্যে মেসেজ মুছে ফেলা যায়।",
        )
        ChatFailure.NETWORK -> tr("No internet. It will be sent when you're back online.", "ইন্টারনেট নেই। সংযোগ এলে পাঠানো হবে।")
        ChatFailure.OTHER -> serverText?.takeIf { it.isNotBlank() } ?: tr("Couldn't send the message", "মেসেজ পাঠানো যায়নি")
    }

    /** এই ভুলের পর থ্রেড শুধু পড়া যায় (লেখার ঘর বন্ধ) */
    fun closesThread(f: ChatFailure): Boolean = f == ChatFailure.CLOSED || f == ChatFailure.NOT_ALLOWED || f == ChatFailure.BLOCKED
}

/** সার্ভারের কাজ (/api/community) */
object ChatApi {
    private suspend fun call(action: String, body: JSONObject = JSONObject()): JSONObject =
        ServerApi.post("/api/community", body.put("action", action))

    suspend fun open(ctx: String, refId: String, peerUid: String?): ChatOpen =
        parseOpen(call("chat_open", JSONObject().put("ctx", ctx).put("refId", refId).apply { if (!peerUid.isNullOrBlank()) put("peerUid", peerUid) }))

    /** থ্রেডের id জানা (নোটিফিকেশন / তালিকা থেকে) */
    suspend fun openThread(threadId: String): ChatOpen = parseOpen(call("chat_open", JSONObject().put("threadId", threadId)), threadId)

    suspend fun send(threadId: String, clientId: String, text: String?, photoBase64: String?, location: ChatLocation?): ChatMessage {
        val b = JSONObject().put("threadId", threadId).put("clientId", clientId)
        if (!text.isNullOrBlank()) b.put("text", text.take(MAX_TEXT))
        if (!photoBase64.isNullOrBlank()) b.put("photo", photoBase64)
        if (location != null) b.put("location", JSONObject().put("lat", location.lat).put("lng", location.lng))
        val o = call("chat_send", b)
        return parseMessage(o.optJSONObject("message") ?: o, threadId)
    }

    suspend fun messages(threadId: String, before: String? = null): ChatPage =
        parsePage(call("chat_messages", JSONObject().put("threadId", threadId).apply { if (!before.isNullOrBlank()) put("before", before) }), threadId)

    /** মেসেজের তালিকা আর পরের পাতার চিহ্ন */
    suspend fun threads(cursor: String? = null): Pair<List<ChatThread>, String?> {
        val o = call("chat_threads", JSONObject().apply { if (!cursor.isNullOrBlank()) put("cursor", cursor) })
        val arr = o.optJSONArray("items") ?: org.json.JSONArray()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map(::parseThread) to o.cursorOrNull("next", "cursor")
    }

    /** upTo = দেখা সবচেয়ে নতুন মেসেজের সময় (epoch ms) */
    suspend fun read(threadId: String, upTo: Long) { call("chat_read", JSONObject().put("threadId", threadId).put("upTo", upTo)) }
    suspend fun unsend(messageId: String) { call("chat_unsend", JSONObject().put("messageId", messageId)) }
    suspend fun report(messageId: String, reason: String, note: String) {
        call("chat_report", JSONObject().put("messageId", messageId).put("reason", reason).apply { if (note.isNotBlank()) put("note", note.take(300)) })
    }
    suspend fun unread(): Int = call("chat_unread").optInt("total", 0)

    const val MAX_TEXT = 1000

    private fun JSONObject.cursorOrNull(vararg keys: String): String? {
        for (k in keys) if (has(k) && !isNull(k)) opt(k)?.toString()?.takeIf { it.isNotBlank() && it != "null" }?.let { return it }
        return null
    }

    internal fun parsePeer(o: JSONObject?): ChatPeer = ChatPeer(o?.str("uid").orEmpty(), o?.str("name").orEmpty(), o?.str("photoThumb").orEmpty())

    internal fun parseMessage(o: JSONObject, threadId: String = ""): ChatMessage {
        val loc = o.optJSONObject("location")?.let { l ->
            if (l.has("lat") && l.has("lng")) ChatLocation(l.optDouble("lat"), l.optDouble("lng")) else null
        }
        return ChatMessage(
            id = o.str("id"),
            threadId = o.str("threadId").ifBlank { threadId },
            from = o.str("from"),
            text = o.str("text"),
            photoUrl = o.str("photoUrl"),
            photoThumb = o.str("photoThumb"),
            location = loc,
            at = o.optLong("at"),
            readAt = if (o.has("readAt") && !o.isNull("readAt")) o.optLong("readAt").takeIf { it > 0 } else null,
            unsent = o.optBoolean("unsent"),
            clientId = o.str("clientId"),
            warn = o.str("warn"),
        )
    }

    internal fun parsePage(o: JSONObject, threadId: String): ChatPage {
        val arr = o.optJSONArray("messages") ?: org.json.JSONArray()
        val list = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { parseMessage(it, threadId) }
        return ChatPage(
            list.sortedBy { it.at }, o.cursorOrNull("before"),
            if (o.has("canSend") && !o.isNull("canSend")) o.optBoolean("canSend") else null,
            o.optBoolean("blocked"),
        )
    }

    internal fun parseOpen(o: JSONObject, fallbackThreadId: String = ""): ChatOpen {
        val id = o.str("threadId").ifBlank { fallbackThreadId }
        return ChatOpen(id, parsePeer(o.optJSONObject("peer")), o.optBoolean("canSend", true), parsePage(o, id), o.str("ctx"), o.str("refId"))
    }

    internal fun parseThread(o: JSONObject): ChatThread {
        val last = o.optJSONObject("last")
        return ChatThread(
            threadId = o.str("threadId"),
            ctx = o.str("ctx"),
            refId = o.str("refId"),
            peer = parsePeer(o.optJSONObject("peer")),
            lastText = last?.str("text").orEmpty(),
            lastAt = last?.optLong("at") ?: 0,
            lastMine = last?.optBoolean("mine") ?: false,
            unread = o.optInt("unread"),
            canSend = o.optBoolean("canSend", true),
        )
    }
}

/** মেসেজের হিসাব (পর্দা ছাড়া — টেস্ট করা যায়) */
object ChatLogic {
    /** নিজের মেসেজ এতক্ষণের মধ্যে মুছে ফেলা যায় */
    const val UNSEND_MS = 10 * 60 * 1000L

    /** পর্দা খোলা থাকলে এতক্ষণ পরপর নতুন মেসেজ আনা (পুশ এলে সাথে সাথে) */
    const val POLL_MS = 8_000L

    fun canUnsend(m: ChatMessage, me: String, now: Long = System.currentTimeMillis()): Boolean =
        m.from == me && !m.unsent && !m.pending && m.id.isNotBlank() && now - m.at in 0..UNSEND_MS

    /**
     * সার্ভারের মেসেজ আর এই ফোনের সারিতে থাকা মেসেজ মেলানো: id ধরে নতুনটা, একই clientId-র সারির মেসেজ সার্ভারেরটা
     * এলে সরে যায়, সব সময়ের ক্রমে (সারিতে থাকাগুলো শেষে — এখনো পৌঁছায়নি)।
     */
    fun merge(current: List<ChatMessage>, incoming: List<ChatMessage>): List<ChatMessage> {
        val byId = LinkedHashMap<String, ChatMessage>()
        current.filter { !it.pending && it.failedReason == null && it.id.isNotBlank() }.forEach { byId[it.id] = it }
        incoming.filter { !it.pending && it.failedReason == null && it.id.isNotBlank() }.forEach { m ->
            val old = byId[m.id]
            // পড়ার সময় একবার এলে আর হারায় না (পুরোনো পাতা আবার এলেও)
            byId[m.id] = if (old != null && m.readAt == null && old.readAt != null) m.copy(readAt = old.readAt) else m
        }
        val serverClientIds = byId.values.mapNotNull { it.clientId.takeIf { c -> c.isNotBlank() } }.toSet()
        val local = (current + incoming).filter { (it.pending || it.failedReason != null) && it.clientId !in serverClientIds }
            .distinctBy { it.clientId }
        return byId.values.sortedBy { it.at } + local.sortedBy { it.at }
    }

    /** অন্যজন এই সময় পর্যন্ত পড়েছেন (chat_read পুশ/SSE): আমার সেই পর্যন্ত মেসেজে ✓✓ */
    fun markReadByPeer(list: List<ChatMessage>, me: String, upTo: Long, now: Long = System.currentTimeMillis()): List<ChatMessage> =
        list.map { if (it.from == me && !it.pending && it.readAt == null && it.at in 1..upTo) it.copy(readAt = now) else it }

    /** অন্যজনের সবচেয়ে নতুন মেসেজের সময় (আমার পড়া জানাতে) — আগে জানানোর চেয়ে নতুন হলে তবেই */
    fun readUpTo(list: List<ChatMessage>, me: String, alreadySent: Long): Long? =
        list.filter { it.from != me && !it.pending && it.at > 0 }.maxOfOrNull { it.at }?.takeIf { it > alreadySent }

    /** নিজের মেসেজের চিহ্ন: সারিতে ⏱, পৌঁছেছে ✓, পড়েছেন ✓✓, পাঠানো যায়নি ! */
    enum class Tick { PENDING, SENT, READ, FAILED }

    fun tick(m: ChatMessage): Tick = when {
        m.failedReason != null -> Tick.FAILED
        m.pending -> Tick.PENDING
        m.readAt != null -> Tick.READ
        else -> Tick.SENT
    }

    /** তালিকায় শেষ মেসেজের লেখা */
    fun preview(t: ChatThread): String {
        val body = when (t.lastText) {
            "📷" -> "📷 " + tr("Photo", "ছবি")
            "📍" -> "📍 " + tr("Location", "লোকেশন")
            else -> t.lastText.replace('\n', ' ')
        }
        if (body.isBlank()) return ""
        return if (t.lastMine) tr("You: ", "আপনি: ") + body else body
    }

    /** মেসেজের লেখা (মুছে ফেলা হলে সেটাই) */
    fun bodyText(m: ChatMessage): String = when {
        m.unsent -> tr("Message removed", "মেসেজ মুছে ফেলা হয়েছে")
        else -> m.text
    }

    /** ঢাকার সময়ে দিন (মেসেজের মাঝে তারিখের দাগ) */
    fun dayKey(at: Long): Long = Math.floorDiv(at + Rules.DHAKA_OFFSET_MS, 86_400_000L)

    fun dayLabel(at: Long, now: Long = System.currentTimeMillis()): String {
        val d = dayKey(at)
        val today = dayKey(now)
        return when (d) {
            today -> tr("Today", "আজ")
            today - 1 -> tr("Yesterday", "গতকাল")
            else -> Rules.formatDate(d * 86_400_000L)
        }
    }

    /** তালিকার সময়: আজ হলে ঘড়ির সময়, নইলে তারিখ */
    fun listTime(at: Long, now: Long = System.currentTimeMillis()): String =
        if (at <= 0) "" else if (dayKey(at) == dayKey(now)) Rules.formatTime(at) else dayLabel(at, now)

    /** নোটিফিকেশন (লক স্ক্রিনেও): শুধু "লাইফলো মেসেজ — <প্রথম নাম>", মেসেজের লেখা কখনো নয় */
    fun notifyTitle(from: String): String {
        val first = CallMachine.firstName(from)
        val app = tr("Lifolo message", "লাইফলো মেসেজ")
        return if (first.isBlank()) app else "$app — $first"
    }

    fun notifyBody(count: Int): String =
        if (count <= 1) tr("New message", "নতুন মেসেজ") else tr("${num(count)} new messages", "${num(count)}টি নতুন মেসেজ")

    /** লেখা পাঠানো যায় কি না (খালি নয়, ১০০০ অক্ষরের মধ্যে) */
    fun canSendText(text: String): Boolean = text.isNotBlank() && text.trim().length <= ChatApi.MAX_TEXT

    fun newClientId(): String = java.util.UUID.randomUUID().toString()

    /** টাকা নিয়ে সতর্কতার লেখা (MoneyWarnBanner) — রক্তে কড়া কথা */
    fun moneyWarnText(blood: Boolean): String = if (blood) {
        tr("Paying money for blood is not allowed — if anyone asks for money, report it", "রক্তের জন্য টাকা লেনদেন নিষেধ — কেউ টাকা চাইলে রিপোর্ট করুন")
    } else {
        tr("Careful: if someone asks you to send money to another number, don't — report it", "সাবধান: কেউ অন্য নম্বরে টাকা পাঠাতে বললে পাঠাবেন না — রিপোর্ট করুন")
    }
}

/** না-পড়া মেসেজের মোট সংখ্যা (উপরের বার আর প্রোফাইলের "মেসেজ"-এর ব্যাজ) */
object ChatUnread {
    val total = mutableIntStateOf(0)
    private var checkedAt = 0L

    suspend fun refresh(force: Boolean = false, now: Long = System.currentTimeMillis()) {
        if (LoginGate.isGuest || !Features.on(Features.CHAT)) { total.intValue = 0; return }
        if (!force && now - checkedAt < 30_000) return
        checkedAt = now
        runCatching { ChatApi.unread() }.onSuccess { total.intValue = it.coerceAtLeast(0) }
    }

    fun clear() {
        total.intValue = 0
        checkedAt = 0
    }
}

/** নতুন মেসেজ/পড়ার খবর (পুশ বা সারি থেকে) — খোলা পর্দা নতুন করে আনে */
object ChatEvents {
    sealed interface Event {
        /** এই থ্রেডে নতুন মেসেজ এসেছে (পুশ) */
        data class Incoming(val threadId: String) : Event
        /** অন্যজন পড়েছেন */
        data class Read(val threadId: String, val upTo: Long) : Event
        /** সারি থেকে সার্ভারে পৌঁছাল */
        data class Sent(val threadId: String, val message: ChatMessage) : Event
        /** অনেক তাড়াতাড়ি (429) — সারিতে আছে, একটু পরে আবার */
        data class Delayed(val threadId: String, val text: String) : Event
        /** সারির মেসেজ আর পাঠানো যাবে না */
        data class Failed(val threadId: String, val clientId: String, val failure: ChatFailure, val text: String) : Event
    }

    private val flow = MutableSharedFlow<Event>(extraBufferCapacity = 64)
    val events: SharedFlow<Event> = flow.asSharedFlow()
    fun emit(e: Event) { flow.tryEmit(e) }

    /** এখন সামনে খোলা থ্রেড (এর নোটিফিকেশন দেখানো হয় না) */
    @Volatile var activeThread: String? = null
}

/**
 * অন্য পাতা থেকে মেসেজ খোলা (App খোলে): সূত্র + রেফারেন্স (+ কাকে), অথবা থ্রেডের id (নোটিফিকেশন/তালিকা)।
 * ContactActions-এর "মেসেজ" বাটন এটাই ডাকে।
 */
object ChatNav {
    data class Target(
        val ctx: String = "",
        val refId: String = "",
        val peerUid: String? = null,
        val threadId: String = "",
        /** তালিকা থেকে খুললে আগেই জানা (উপরের বার সাথে সাথে) */
        val peerName: String = "",
        val peerPhoto: String = "",
    )

    val open = mutableStateOf<Target?>(null)
    /** প্রোফাইল / উপরের বার → মেসেজের তালিকা */
    val inbox = mutableStateOf(false)
}

/** মেসেজের পাতা খোলা — ctx: সম্পর্কের সূত্র (CallCtx), refId: রিকোয়েস্ট/অ্যাপয়েন্টমেন্ট/ডাকা/অর্ডারের id, peerUid: কাকে (একাধিক থাকলে) */
fun openChat(ctx: String, refId: String, peerUid: String? = null) {
    if (refId.isBlank()) return
    LoginGate.require(tr("Log in to send messages", "মেসেজ পাঠাতে লগইন করুন")) {
        ChatNav.open.value = ChatNav.Target(ctx, refId, peerUid?.ifBlank { null })
    }
}

fun openChatThread(threadId: String) {
    if (threadId.isBlank()) return
    ChatNav.open.value = ChatNav.Target(threadId = threadId)
}
