package com.bspc.blood

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import org.json.JSONObject

// ------------------------------------------------------------
// অ্যাপে অডিও/ভিডিও কল — সার্ভারের সাথে চুক্তি backend/docs/CALLS.md।
// শুধু যাঁদের মধ্যে আগে থেকে সম্পর্ক আছে (রক্তের রিকোয়েস্ট, অ্যাপয়েন্টমেন্ট, অ্যাম্বুলেন্স, অর্ডার); সার্ভার যাচাই করে।
// সাধারণ ফোনের বাটন থাকেই (অ্যাম্বুলেন্স আর জরুরি রক্তে সেটাই বড়)। রেকর্ড হয় না।
// এই ফাইলে শুধু হিসাব আর সার্ভারের কাজ — ফোনের কিছু ছোঁয় না (তাই টেস্ট করা যায়); কল চালানো CallManager.kt,
// পর্দা CallActivity.kt, বাটন CallButtons.kt।
// ------------------------------------------------------------

/** কোন সম্পর্কের সূত্রে কল */
object CallCtx {
    const val BLOOD = "blood"
    const val APPOINTMENT = "appointment"
    const val AMBULANCE = "ambulance"
    /** প্রেসক্রিপশনের ওষুধের অর্ডার (রোগী ↔ ফার্মেসি) */
    const val RX_ORDER = "rx_order"
    /** প্যাথলজির পরীক্ষার অর্ডার (রোগী ↔ প্যাথলজি) */
    const val LAB_ORDER = "lab_order"
    val ALL = setOf(BLOOD, APPOINTMENT, AMBULANCE, RX_ORDER, LAB_ORDER)

    /** ভিডিও দিয়ে শুরু করা যায় শুধু অ্যাপয়েন্টমেন্টে; বাকিগুলো অডিওতে শুরু, পরে "ভিডিও চালু করতে অনুরোধ" */
    fun videoFirst(ctx: String): Boolean = ctx == APPOINTMENT

    fun label(ctx: String): String = when (ctx) {
        BLOOD -> tr("Blood request", "রক্তের রিকোয়েস্ট")
        APPOINTMENT -> tr("Appointment", "অ্যাপয়েন্টমেন্ট")
        AMBULANCE -> tr("Ambulance", "অ্যাম্বুলেন্স")
        RX_ORDER -> tr("Medicine order", "ওষুধের অর্ডার")
        LAB_ORDER -> tr("Lab order", "পরীক্ষার অর্ডার")
        "order" -> tr("Order", "অর্ডার")
        else -> ""
    }
}

data class CallPeer(val uid: String, val name: String, val photoThumb: String = "")

/** call_start / call_accept / appt_join-এর উত্তর: কোন ঘরে, কোন সার্ভারে (url সবসময় সার্ভার যা দেয়), কোন টোকেনে */
data class CallJoin(
    val callId: String,
    val room: String,
    val url: String,
    val token: String,
    val peer: CallPeer? = null,
    /** সার্ভার বলেছে ভিডিও কি না (অডিওতে ধরলে false) — না বললে null */
    val video: Boolean? = null,
)

/** call_status: চলমান কলে নতুন টোকেন (১০ মিনিটের) আর ভিডিও চালু হয়েছে কি না */
data class CallStatus(
    val state: String,
    val ctx: String = "",
    val refId: String = "",
    val startedAt: Long = 0,
    val endedAt: Long = 0,
    val video: Boolean = false,
    val url: String = "",
    val token: String = "",
    val peer: CallPeer? = null,
) {
    fun join(callId: String): CallJoin? = if (url.isNotBlank() && token.isNotBlank()) CallJoin(callId, "", url, token, peer, video) else null
}

/** যোগাযোগের সারির চাবি (contact_can) */
data class ContactKey(val ctx: String, val refId: String, val peerUid: String?)

/** সার্ভারের হ্যাঁ/না: অডিও কল, ভিডিও কল, মেসেজ; blocked = আমি এঁর সাথে যোগাযোগ বন্ধ রেখেছি ("আবার চালু করুন") */
data class ContactFlags(val canCall: Boolean, val canVideo: Boolean, val canMessage: Boolean, val blocked: Boolean = false, val peerUid: String = "") {
    companion object { val NONE = ContactFlags(false, false, false) }
}

/** "যোগাযোগ বন্ধ করা" একজন (call_blocks) */
data class CallBlocked(val uid: String, val name: String, val photoThumb: String, val blockedAt: Long)

/** রিপোর্টের কারণ (call_report) */
object CallReportReason {
    val ALL = listOf("harassment", "spam", "fake", "other")
    fun label(r: String): String = when (r) {
        "harassment" -> tr("Harassment or abuse", "হয়রানি বা গালাগাল")
        "spam" -> tr("Unwanted / repeated calls", "অকারণে বা বারবার কল")
        "fake" -> tr("Fake person or request", "ভুয়া মানুষ বা অনুরোধ")
        else -> tr("Something else", "অন্য কারণ")
    }
}

/** কলের তালিকার এক সারি (call_history) */
data class CallHistoryItem(
    val callId: String,
    val ctx: String,
    val refId: String,
    val peer: CallPeer,
    val video: Boolean,
    /** ringing | accepted | declined | missed | ended | cancelled */
    val state: String,
    /** "in" (আমাকে কল) | "out" (আমি করেছি) */
    val dir: String,
    val startedAt: Long,
    val durationSec: Int,
) {
    val incoming: Boolean get() = dir == "in"
    /** আমার না-ধরা কল */
    val missed: Boolean get() = incoming && (state == "missed" || (state == "cancelled" && durationSec == 0))
}

/** সার্ভারের ভুলের ধরন (কোড থেকে) — বাটন লুকানো, বার্তা আর ফোনের বিকল্প এর ওপর */
enum class CallFailure { NOT_ALLOWED, BLOCKED, OFF, BUSY, GONE, UNAVAILABLE, RATE_LIMIT, NETWORK, OTHER }

object CallErrors {
    fun classify(code: String?, httpLike: Boolean = true): CallFailure = when (code) {
        "call_not_allowed" -> CallFailure.NOT_ALLOWED
        "call_blocked" -> CallFailure.BLOCKED
        "call_off" -> CallFailure.OFF
        "call_busy" -> CallFailure.BUSY
        "call_gone" -> CallFailure.GONE
        // ফিচার সুইচ বন্ধ: সার্ভারের বার্তা ("এই সুবিধা এখনো চালু হয়নি")
        "calls_unavailable", "calls_disabled", "feature_off" -> CallFailure.UNAVAILABLE
        "call_rate", "rate_limited", "too_many_requests", "quota" -> CallFailure.RATE_LIMIT
        null, "" -> if (httpLike) CallFailure.OTHER else CallFailure.NETWORK
        else -> CallFailure.OTHER
    }

    fun of(t: Throwable): CallFailure = when (t) {
        is ServerError -> classify(t.code)
        is LoginRequired -> CallFailure.OTHER
        else -> CallFailure.NETWORK
    }

    /** কল না হলে কী দেখানো হবে (সার্ভারের বাংলা বার্তা থাকলে call_off-এ সেটাই) */
    fun message(f: CallFailure, serverText: String? = null): String = when (f) {
        CallFailure.OFF -> serverText?.takeIf { it.isNotBlank() }
            ?: tr("They have turned off app calls — call their phone", "উনি অ্যাপে কল বন্ধ রেখেছেন — ফোনে কল করুন")
        CallFailure.BUSY -> tr("They are on another call. Try again a little later.", "উনি এখন অন্য কলে আছেন। একটু পরে আবার চেষ্টা করুন।")
        CallFailure.NOT_ALLOWED -> tr("An app call isn't possible here now. Call their phone instead.", "এখন এখানে অ্যাপে কল করা যাচ্ছে না। ফোনে কল করুন।")
        // শুধু আমি বন্ধ করলে আসে (উনি করলে call_not_allowed — বলা হয় না)
        CallFailure.BLOCKED -> serverText?.takeIf { it.isNotBlank() } ?: tr("You have stopped contact with this person.", "আপনি এঁর সাথে যোগাযোগ বন্ধ রেখেছেন।")
        CallFailure.GONE -> tr("This call has already ended.", "কলটা আর নেই — শেষ হয়ে গেছে।")
        CallFailure.UNAVAILABLE -> serverText?.takeIf { it.isNotBlank() } ?: tr("App calls aren't available right now. Call their phone.", "অ্যাপে কল এখন চালু নেই। ফোনে কল করুন।")
        CallFailure.RATE_LIMIT -> tr("Too many calls in a short time. Wait about 10 minutes and try again.", "অল্প সময়ে অনেক বেশি কল হয়েছে। ১০ মিনিট মতো পরে আবার চেষ্টা করুন।")
        CallFailure.NETWORK -> tr("Couldn't reach the server. Check your internet.", "সার্ভারে পৌঁছানো যায়নি। ইন্টারনেট দেখুন।")
        CallFailure.OTHER -> serverText?.takeIf { it.isNotBlank() } ?: tr("The call couldn't be made", "কল করা গেল না")
    }

    /** এই ভুলে ফোন নম্বরে কল করার বিকল্প দেখানো হয় (নম্বর জানা থাকলে) */
    fun offersPhone(f: CallFailure): Boolean = f == CallFailure.OFF || f == CallFailure.NOT_ALLOWED || f == CallFailure.UNAVAILABLE || f == CallFailure.BUSY
}

/**
 * অ্যাপে কল চালু কি না (call_config) আর কোন সূত্রে সার্ভার "না" বলেছে — বাটন দেখানো এর ওপর।
 * enabled null = এখনো জানা নেই (বাটন লুকানো থাকে)। call_config অচেনা বা enabled=false, বা কোনো কাজে 503 calls_unavailable:
 * সব বাটন লুকানো (১০ মিনিট পর আবার জিজ্ঞেস)। 403 call_not_allowed: শুধু সেই রিকোয়েস্ট/অ্যাপয়েন্টমেন্ট/অর্ডারের বাটন লুকানো।
 */
object CallAvailability {
    val enabled = mutableStateOf<Boolean?>(null)
    private var checkedAt = 0L
    private const val TTL_MS = 10 * 60 * 1000L
    /** "ctx:refId" → সার্ভার এই সূত্রে কল দেয় না */
    val blocked = mutableStateMapOf<String, Boolean>()

    fun key(ctx: String, refId: String) = "$ctx:$refId"
    fun isBlocked(ctx: String, refId: String) = blocked[key(ctx, refId)] == true

    /** বাটন দেখাবে কি না (লগইন না থাকলে কখনো নয়) */
    fun shows(ctx: String, refId: String): Boolean =
        enabled.value == true && refId.isNotBlank() && !isBlocked(ctx, refId) && !LoginGate.isGuest

    /** দরকার হলে সার্ভারকে জিজ্ঞেস (১০ মিনিটে একবার); ইন্টারনেট না থাকলে আগের অবস্থা থাকে */
    suspend fun refresh(force: Boolean = false, now: Long = System.currentTimeMillis()) {
        if (LoginGate.isGuest) return
        // ফিচার সুইচ (মিনিটে একবারের বেশি নয়)
        Features.refreshSoon()
        if (!force && enabled.value != null && now - checkedAt < TTL_MS) return
        checkedAt = now
        runCatching { CallsApi.config() }
            .onSuccess { enabled.value = it }
            .onFailure { e -> if (e is ServerError) enabled.value = false }
    }

    /** কোনো কাজের ভুল থেকে: সার্ভার বন্ধ বললে সব বাটন, সম্পর্ক না থাকলে এই সূত্রের বাটন লুকানো */
    fun onFailure(f: CallFailure, ctx: String?, refId: String?, now: Long = System.currentTimeMillis()) {
        when (f) {
            CallFailure.UNAVAILABLE -> { enabled.value = false; checkedAt = now }
            CallFailure.NOT_ALLOWED, CallFailure.BLOCKED -> if (ctx != null && refId != null) blocked[key(ctx, refId)] = true
            else -> {}
        }
    }

    /** লগআউট/অ্যাকাউন্ট বদল */
    fun clear() {
        enabled.value = null
        checkedAt = 0
        blocked.clear()
        ContactCan.clear()
    }
}

/** সার্ভারের কাজ (/api/community) */
object CallsApi {
    private suspend fun call(action: String, body: JSONObject = JSONObject()): JSONObject =
        ServerApi.post("/api/community", body.put("action", action))

    /** অ্যাপে কল চালু কি না */
    suspend fun config(): Boolean = call("call_config").optBoolean("enabled", false)

    suspend fun start(ctx: String, refId: String, peerUid: String?, video: Boolean): CallJoin =
        parseJoin(call("call_start", JSONObject().put("ctx", ctx).put("refId", refId).put("video", video).apply { if (!peerUid.isNullOrBlank()) put("peerUid", peerUid) }))

    /** ধরা — video=false: ভিডিও কলও অডিওতে ধরা (দুজনেই ভিডিও ছাড়া, পরে সম্মতিতে ভিডিও) */
    suspend fun accept(callId: String, video: Boolean): CallJoin = parseJoin(call("call_accept", JSONObject().put("callId", callId).put("video", video)), callId)
    suspend fun decline(callId: String) { call("call_decline", JSONObject().put("callId", callId)) }
    suspend fun cancel(callId: String) { call("call_cancel", JSONObject().put("callId", callId)) }
    suspend fun end(callId: String) { call("call_end", JSONObject().put("callId", callId)) }

    suspend fun status(callId: String): CallStatus = parseStatus(call("call_status", JSONObject().put("callId", callId)))

    /** কলের তালিকা (নতুন আগে) আর পরের পাতার চিহ্ন (না থাকলে শেষ) */
    suspend fun history(before: Long? = null): Pair<List<CallHistoryItem>, Long?> {
        val o = call("call_history", JSONObject().apply { if (before != null) put("before", before) })
        val arr = o.optJSONArray("items") ?: o.optJSONArray("calls") ?: org.json.JSONArray()
        val next = if (o.has("next") && !o.isNull("next")) o.optLong("next").takeIf { it > 0 } else null
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map(::parseHistory) to next
    }

    /** অডিও কলে "ভিডিও চালু করতে অনুরোধ" — true = আগেই ভিডিও */
    suspend fun upgrade(callId: String): Boolean = call("call_upgrade", JSONObject().put("callId", callId)).optBoolean("video")

    /** এক পাতার সব যোগাযোগের সারির জন্য একবারে: কোনটায় অডিও/ভিডিও কল আর মেসেজ দেওয়া যায় (contact_can, ≤ ৫০) */
    suspend fun contactCan(keys: List<ContactKey>): List<ContactFlags> {
        val arr = org.json.JSONArray()
        keys.forEach { k -> arr.put(JSONObject().put("ctx", k.ctx).put("refId", k.refId).apply { if (!k.peerUid.isNullOrBlank()) put("peerUid", k.peerUid) }) }
        val out = call("contact_can", JSONObject().put("items", arr)).optJSONArray("items") ?: org.json.JSONArray()
        val items = (0 until out.length()).mapNotNull { out.optJSONObject(it) }
        return keys.mapIndexed { i, k ->
            // উত্তর একই ক্রমে; না মিললে ctx+refId (+peerUid) দিয়ে খোঁজা
            val o = items.getOrNull(i)?.takeIf { it.str("ctx") == k.ctx && it.str("refId") == k.refId }
                ?: items.firstOrNull { it.str("ctx") == k.ctx && it.str("refId") == k.refId && (k.peerUid.isNullOrBlank() || it.str("peerUid") == k.peerUid) }
            if (o == null) ContactFlags.NONE
            else ContactFlags(o.optBoolean("canCall"), o.optBoolean("canVideo"), o.optBoolean("canMessage"), o.optBoolean("blocked"), o.str("peerUid"))
        }
    }
    /** অনুরোধে রাজি: ভিডিওর নতুন টোকেন */
    suspend fun upgradeAccept(callId: String): CallJoin = parseJoin(call("call_upgrade_accept", JSONObject().put("callId", callId)), callId)
    suspend fun upgradeDecline(callId: String) { call("call_upgrade_decline", JSONObject().put("callId", callId)) }

    /** অ্যাপে কলে ব্লক (ফোনের নম্বর নয়) */
    suspend fun block(peerUid: String, callId: String?) {
        call("call_block", JSONObject().put("peerUid", peerUid).apply { if (!callId.isNullOrBlank()) put("callId", callId) })
    }
    suspend fun unblock(peerUid: String) { call("call_unblock", JSONObject().put("peerUid", peerUid)) }
    suspend fun blocks(): List<CallBlocked> {
        val arr = call("call_blocks").optJSONArray("items") ?: org.json.JSONArray()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { CallBlocked(it.str("uid"), it.str("name"), it.str("photoThumb"), it.optLong("blockedAt")) }
    }
    suspend fun report(callId: String, reason: String, note: String) {
        call("call_report", JSONObject().put("callId", callId).put("reason", reason).apply { if (note.isNotBlank()) put("note", note.take(300)) })
    }

    /** অনলাইন চেম্বারের অ্যাপয়েন্টমেন্টে ভিডিও কলে যুক্ত হওয়া (রোগী বা ডাক্তার/সহকারী) */
    suspend fun apptJoin(appointmentId: String): CallJoin = parseJoin(call("appt_join", JSONObject().put("appointmentId", appointmentId)))

    internal fun parsePeer(o: JSONObject?): CallPeer? = o?.let { CallPeer(it.str("uid"), it.str("name"), it.str("photoThumb")) }

    internal fun parseJoin(o: JSONObject, fallbackCallId: String = ""): CallJoin {
        val join = CallJoin(
            o.str("callId").ifBlank { fallbackCallId }, o.str("room"), o.str("url"), o.str("token"), parsePeer(o.optJSONObject("peer")),
            if (o.has("video") && !o.isNull("video")) o.optBoolean("video") else null,
        )
        if (join.url.isBlank() || join.token.isBlank()) throw ServerError(tr("The call couldn't be made", "কল করা গেল না"), "calls_unavailable")
        return join
    }

    internal fun parseStatus(o: JSONObject) = CallStatus(
        o.str("state"), o.str("ctx"), o.str("refId"), o.optLong("startedAt"), o.optLong("endedAt"), o.optBoolean("video"), o.str("url"), o.str("token"), parsePeer(o.optJSONObject("peer")),
    )

    internal fun parseHistory(o: JSONObject) = CallHistoryItem(
        o.str("callId"), o.str("ctx"), o.str("refId"),
        parsePeer(o.optJSONObject("peer")) ?: CallPeer(o.str("peerUid"), o.str("peerName")),
        o.optBoolean("video"), o.str("state"), o.str("dir"), o.optLong("startedAt"), o.optInt("durationSec"),
    )
}

/** অ্যাপয়েন্টমেন্টের কলের সময়সীমা (সার্ভারের নিয়মের মতো; শেষ কথা সার্ভারের) — ঢাকার সময়ে */
object CallWindows {
    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN
    private const val DAY = 24 * HOUR

    /**
     * অ্যাপয়েন্টমেন্টের সময় (epoch ms): date = সেই দিনের UTC মধ্যরাত (অ্যাপের নিয়ম), start = "HH:MM" ঢাকার সময়।
     * start না থাকলে null (তখন পুরো দিন)।
     */
    fun slotStart(date: Long, start: String): Long? {
        val p = start.split(":")
        val h = p.getOrNull(0)?.toIntOrNull() ?: return null
        val m = p.getOrNull(1)?.toIntOrNull() ?: 0
        if (h !in 0..23 || m !in 0..59) return null
        return date + h * HOUR + m * MIN - Rules.DHAKA_OFFSET_MS
    }

    /** [from, to) — start থাকলে আগে beforeMin মিনিট থেকে পরে afterHours ঘণ্টা; না থাকলে সেই দিন (ঢাকা) পুরোটা + afterHours */
    fun window(date: Long, start: String, beforeMin: Int, afterHours: Int): LongRange {
        val s = slotStart(date, start)
        return if (s != null) (s - beforeMin * MIN) until (s + afterHours * HOUR)
        else (date - Rules.DHAKA_OFFSET_MS) until (date - Rules.DHAKA_OFFSET_MS + DAY + afterHours * HOUR)
    }

    /** অ্যাপয়েন্টমেন্টে অ্যাপ-কল: নিশ্চিত, সময়ের ৩০ মিনিট আগে থেকে ৩ ঘণ্টা পর পর্যন্ত */
    fun canCall(status: String, date: Long, start: String, now: Long = System.currentTimeMillis()): Boolean =
        status == "confirmed" && now in window(date, start, 30, 3)

    /** অনলাইন চেম্বারে "ভিডিও কলে যুক্ত হোন": নিশ্চিত, ১৫ মিনিট আগে থেকে ৩ ঘণ্টা পর পর্যন্ত */
    fun canJoin(status: String, date: Long, start: String, now: Long = System.currentTimeMillis()): Boolean =
        status == "confirmed" && now in window(date, start, 15, 3)

    /** যুক্ত হওয়ার সময় এখনো আসেনি: কখন থেকে (epoch ms), নইলে null */
    fun joinOpensAt(status: String, date: Long, start: String, now: Long = System.currentTimeMillis()): Long? {
        if (status != "confirmed") return null
        val w = window(date, start, 15, 3)
        return if (now < w.first) w.first else null
    }
}

// ------------------------------------------------------------
// কলের অবস্থা — শুধু হিসাব (CallManager ঘটনা পাঠায়, পর্দা অবস্থা দেখে)
// ------------------------------------------------------------

enum class CallPhase {
    IDLE,
    /** আমি কল করেছি, ওপাশে বাজছে */
    OUTGOING,
    /** আমাকে কল আসছে */
    INCOMING,
    /** ধরেছি/যুক্ত হচ্ছি */
    CONNECTING,
    /** অনলাইন অ্যাপয়েন্টমেন্ট: ঘরে আছি, অন্যজন এখনো আসেননি (অপেক্ষার ঘর) */
    WAITING,
    ACTIVE,
    RECONNECTING,
    ENDED,
}

enum class EndReason { HANGUP, REMOTE_ENDED, DECLINED, NO_ANSWER, MISSED, CANCELLED, BUSY, FAILED, NOT_ALLOWED, OFF, UNAVAILABLE, BLOCKED }

data class CallState(
    val phase: CallPhase = CallPhase.IDLE,
    val callId: String = "",
    val video: Boolean = false,
    val outgoing: Boolean = false,
    val ctx: String = "",
    val refId: String = "",
    /** অনলাইন অ্যাপয়েন্টমেন্টের কল (appt_join) */
    val appointmentId: String = "",
    val peerName: String = "",
    val peerPhoto: String = "",
    /** অন্যজনের uid (ব্লক করতে) */
    val peerUid: String = "",
    /** আমি ভিডিও চালু করতে অনুরোধ পাঠিয়েছি — উত্তরের অপেক্ষা */
    val upgradePending: Boolean = false,
    /** অন্যজন ভিডিও চালু করতে চাইছেন — আমার উত্তর বাকি */
    val upgradeAsked: Boolean = false,
    /** অন্যজন ভিডিওতে রাজি হননি (ছোট বার্তা) */
    val upgradeDeclined: Boolean = false,
    /** অন্যজন এখন ঘরে আছেন */
    val peerPresent: Boolean = false,
    /** প্রথমবার দুজন যুক্ত হওয়ার মুহূর্ত (সময় গোনা) */
    val connectedAt: Long? = null,
    val endReason: EndReason? = null,
    val endMessage: String = "",
    val micOn: Boolean = true,
    val camOn: Boolean = false,
    val speakerOn: Boolean = false,
    val frontCamera: Boolean = true,
    val remoteVideoOn: Boolean = false,
    /** নেটওয়ার্ক দুর্বল */
    val poorNetwork: Boolean = false,
    /** দুর্বল নেটওয়ার্কে নিজে থেকে ক্যামেরা বন্ধ (শুধু অডিও) */
    val audioFallback: Boolean = false,
    /** ব্লুটুথ/হেডসেট লাগানো (স্পিকার বোতামের বদলে কোথায় শব্দ) */
    val audioRoute: String = "",
) {
    val isAppointment: Boolean get() = appointmentId.isNotBlank()
    val live: Boolean get() = phase != CallPhase.IDLE && phase != CallPhase.ENDED
    /** মাইক/ক্যামেরা চালু থাকা অবস্থা (foreground service লাগে) */
    val usesMedia: Boolean get() = phase in setOf(CallPhase.OUTGOING, CallPhase.CONNECTING, CallPhase.WAITING, CallPhase.ACTIVE, CallPhase.RECONNECTING)
}

sealed interface CallEvent {
    /** আমি কল করলাম (call_start সফল) */
    data class Dialed(val join: CallJoin, val ctx: String, val refId: String, val video: Boolean, val peerName: String) : CallEvent
    /** কল আসছে (পুশ) */
    data class Ringing(val callId: String, val video: Boolean, val peerName: String, val peerPhoto: String, val ctx: String, val refId: String, val peerUid: String = "") : CallEvent
    /** ধরলাম (call_accept চলছে) */
    data object Accepting : CallEvent
    /** অনলাইন অ্যাপয়েন্টমেন্টে যুক্ত হচ্ছি (appt_join চলছে) */
    data class JoiningAppointment(val appointmentId: String, val peerName: String) : CallEvent
    /** সার্ভার টোকেন দিয়েছে (accept / appt_join) */
    data class Joined(val join: CallJoin) : CallEvent
    /** LiveKit ঘরে যুক্ত */
    data object RoomConnected : CallEvent
    data object PeerJoined : CallEvent
    data object PeerLeft : CallEvent
    data object Reconnecting : CallEvent
    data object Reconnected : CallEvent
    /** সার্ভারের call_status / call_state পুশ */
    data class Server(val state: String) : CallEvent
    data class Failed(val reason: EndReason, val message: String = "") : CallEvent
    data object Hangup : CallEvent
    data class Mic(val on: Boolean) : CallEvent
    data class Camera(val on: Boolean) : CallEvent
    data class Speaker(val on: Boolean) : CallEvent
    data class FrontCamera(val front: Boolean) : CallEvent
    data class RemoteVideo(val on: Boolean) : CallEvent
    data class Network(val poor: Boolean) : CallEvent
    /** দুর্বল নেটওয়ার্কে ক্যামেরা বন্ধ করা হলো */
    data object AudioFallback : CallEvent
    data class AudioRoute(val route: String) : CallEvent
    /** আমি "ভিডিও চালু করতে অনুরোধ" পাঠালাম */
    data object UpgradeSent : CallEvent
    /** অন্যজন অনুরোধ করেছেন */
    data object UpgradeAsked : CallEvent
    /** অন্যজন রাজি হননি / আমি রাজি হইনি / ছোট বার্তা সরানো */
    data object UpgradeDeclined : CallEvent
    data object UpgradeDismissed : CallEvent
    /** ভিডিও চালু (নতুন টোকেনে ঘরে আবার যুক্ত) — camera: নিজের ক্যামেরা চালু */
    data class Upgraded(val camera: Boolean) : CallEvent
    /** ভিডিও কল অডিওতে ধরা হলো — দুজনেরই ক্যামেরা/ভিডিও বন্ধ (পরে সম্মতিতে আবার) */
    data object Downgraded : CallEvent
    /** ছবি (call_status-এর peer.photoThumb — পুশে ছবি থাকে না) */
    data class PeerInfo(val peer: CallPeer) : CallEvent
    /** call_status থেকে পুরো তথ্য (পুশে শুধু প্রথম নাম, কোন সূত্রে তা নেই) */
    data class Details(val ctx: String, val refId: String, val peer: CallPeer?) : CallEvent
    /** শেষের পর্দা সরানো */
    data object Reset : CallEvent
}

object CallMachine {
    private fun ended(s: CallState, r: EndReason, msg: String = "") =
        s.copy(phase = CallPhase.ENDED, endReason = r, endMessage = msg, peerPresent = false, poorNetwork = false)

    /** একটা ঘটনার পর নতুন অবস্থা; now = এখনকার সময় (টেস্টে বাইরে থেকে) */
    fun reduce(s: CallState, e: CallEvent, now: Long): CallState {
        // শেষ হওয়া কলে শুধু নতুন কল বা Reset
        if (s.phase == CallPhase.ENDED && e !is CallEvent.Dialed && e !is CallEvent.Ringing && e !is CallEvent.JoiningAppointment && e !is CallEvent.Reset) return s
        return when (e) {
            is CallEvent.Dialed -> CallState(
                phase = CallPhase.OUTGOING, callId = e.join.callId, video = e.video, outgoing = true, ctx = e.ctx, refId = e.refId,
                peerName = e.join.peer?.name?.ifBlank { null } ?: e.peerName, peerPhoto = e.join.peer?.photoThumb.orEmpty(),
                peerUid = e.join.peer?.uid.orEmpty(), camOn = e.video, speakerOn = e.video,
            )
            is CallEvent.Ringing -> if (s.live && s.callId != e.callId) s else CallState(
                phase = CallPhase.INCOMING, callId = e.callId, video = e.video, outgoing = false, ctx = e.ctx, refId = e.refId,
                peerName = e.peerName, peerPhoto = e.peerPhoto, peerUid = e.peerUid, camOn = e.video, speakerOn = e.video,
            )
            CallEvent.Accepting -> if (s.phase == CallPhase.INCOMING) s.copy(phase = CallPhase.CONNECTING) else s
            is CallEvent.JoiningAppointment -> CallState(
                phase = CallPhase.CONNECTING, video = true, outgoing = false, ctx = CallCtx.APPOINTMENT, refId = e.appointmentId,
                appointmentId = e.appointmentId, peerName = e.peerName, camOn = true, speakerOn = true,
            )
            is CallEvent.Joined -> s.copy(
                callId = e.join.callId.ifBlank { s.callId },
                peerName = s.peerName.ifBlank { e.join.peer?.name.orEmpty() },
                peerPhoto = s.peerPhoto.ifBlank { e.join.peer?.photoThumb.orEmpty() },
                peerUid = s.peerUid.ifBlank { e.join.peer?.uid.orEmpty() },
            )
            CallEvent.RoomConnected -> when {
                s.phase == CallPhase.CONNECTING && s.isAppointment && !s.peerPresent -> s.copy(phase = CallPhase.WAITING)
                s.phase == CallPhase.RECONNECTING -> s.copy(phase = if (s.peerPresent) CallPhase.ACTIVE else if (s.isAppointment) CallPhase.WAITING else if (s.connectedAt == null && s.outgoing) CallPhase.OUTGOING else CallPhase.CONNECTING)
                else -> s
            }
            CallEvent.PeerJoined -> when (s.phase) {
                CallPhase.OUTGOING, CallPhase.CONNECTING, CallPhase.WAITING, CallPhase.RECONNECTING, CallPhase.ACTIVE ->
                    s.copy(phase = CallPhase.ACTIVE, peerPresent = true, connectedAt = s.connectedAt ?: now)
                else -> s
            }
            CallEvent.PeerLeft -> when {
                !s.live -> s
                // অনলাইন অ্যাপয়েন্টমেন্ট: অন্যজন আবার আসতে পারেন — অপেক্ষার ঘরে
                s.isAppointment -> s.copy(phase = CallPhase.WAITING, peerPresent = false, remoteVideoOn = false)
                s.connectedAt != null -> ended(s, EndReason.REMOTE_ENDED)
                else -> s.copy(peerPresent = false)
            }
            CallEvent.Reconnecting -> if (s.phase in setOf(CallPhase.ACTIVE, CallPhase.WAITING, CallPhase.OUTGOING, CallPhase.CONNECTING)) s.copy(phase = CallPhase.RECONNECTING) else s
            CallEvent.Reconnected -> if (s.phase == CallPhase.RECONNECTING) reduce(s, CallEvent.RoomConnected, now) else s
            is CallEvent.Server -> when (e.state) {
                "declined" -> if (s.outgoing && s.connectedAt == null) ended(s, EndReason.DECLINED) else s
                "missed" -> if (s.connectedAt != null) s else if (s.outgoing) ended(s, EndReason.NO_ANSWER) else ended(s, EndReason.MISSED)
                "cancelled" -> if (!s.outgoing && s.connectedAt == null && s.phase in setOf(CallPhase.INCOMING, CallPhase.CONNECTING)) ended(s, EndReason.CANCELLED) else s
                "ended" -> if (s.isAppointment) s else ended(s, EndReason.REMOTE_ENDED)
                // ধরা হয়েছে, কিন্তু অন্য ফোনে (আমি এখনো এখানে বাজছি)
                "accepted" -> if (s.phase == CallPhase.INCOMING) ended(s, EndReason.CANCELLED) else s
                else -> s
            }
            is CallEvent.Failed -> ended(s, e.reason, e.message)
            CallEvent.Hangup -> if (s.live) ended(s, EndReason.HANGUP) else s
            is CallEvent.Mic -> s.copy(micOn = e.on)
            is CallEvent.Camera -> s.copy(camOn = e.on, audioFallback = if (e.on) false else s.audioFallback)
            is CallEvent.Speaker -> s.copy(speakerOn = e.on)
            is CallEvent.FrontCamera -> s.copy(frontCamera = e.front)
            is CallEvent.RemoteVideo -> s.copy(remoteVideoOn = e.on)
            is CallEvent.Network -> s.copy(poorNetwork = e.poor)
            CallEvent.AudioFallback -> s.copy(camOn = false, audioFallback = true)
            is CallEvent.AudioRoute -> s.copy(audioRoute = e.route)
            CallEvent.UpgradeSent -> if (s.live && !s.video) s.copy(upgradePending = true, upgradeDeclined = false) else s
            CallEvent.UpgradeAsked -> if (s.live && !s.video && s.connectedAt != null) s.copy(upgradeAsked = true) else s
            CallEvent.UpgradeDeclined -> s.copy(upgradePending = false, upgradeDeclined = s.upgradePending)
            CallEvent.UpgradeDismissed -> s.copy(upgradeAsked = false, upgradeDeclined = false)
            is CallEvent.Upgraded -> if (s.live) s.copy(video = true, camOn = e.camera, speakerOn = true, upgradePending = false, upgradeAsked = false, upgradeDeclined = false, audioFallback = false) else s
            is CallEvent.Details -> s.copy(
                ctx = s.ctx.ifBlank { e.ctx },
                refId = s.refId.ifBlank { e.refId },
                peerName = e.peer?.name?.ifBlank { null } ?: s.peerName,
                peerPhoto = e.peer?.photoThumb?.ifBlank { null } ?: s.peerPhoto,
                peerUid = s.peerUid.ifBlank { e.peer?.uid.orEmpty() },
            )
            CallEvent.Downgraded -> if (s.live && s.video) s.copy(video = false, camOn = false, remoteVideoOn = false, speakerOn = false) else s
            is CallEvent.PeerInfo -> s.copy(
                peerName = s.peerName.ifBlank { e.peer.name },
                peerPhoto = s.peerPhoto.ifBlank { e.peer.photoThumb },
                peerUid = s.peerUid.ifBlank { e.peer.uid },
            )
            CallEvent.Reset -> CallState()
        }
    }

    /** শেষের পর্দার লেখা */
    fun endText(s: CallState): String = when (s.endReason) {
        EndReason.DECLINED -> tr("They declined the call", "উনি কল ধরেননি (কেটে দিয়েছেন)")
        EndReason.NO_ANSWER -> tr("No answer", "কেউ ধরেননি")
        EndReason.MISSED -> tr("Missed call", "মিসড কল")
        EndReason.CANCELLED -> tr("The call ended", "কল শেষ হয়েছে")
        EndReason.BUSY, EndReason.OFF, EndReason.NOT_ALLOWED, EndReason.UNAVAILABLE, EndReason.FAILED, EndReason.BLOCKED ->
            s.endMessage.ifBlank { tr("The call couldn't connect", "কল যুক্ত হয়নি") }
        else -> tr("Call ended", "কল শেষ")
    }

    /** "০৩:২৫" / "১:০২:০৫" */
    fun duration(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val x = sec % 60
        val t = if (h > 0) "%d:%02d:%02d".format(java.util.Locale.US, h, m, x) else "%02d:%02d".format(java.util.Locale.US, m, x)
        return num(t)
    }

    /** কম ভালো নেটওয়ার্ক এতক্ষণ থাকলে ভিডিও বন্ধ করে শুধু অডিও */
    const val POOR_FALLBACK_MS = 8_000L

    /** অন্যজন ঘর ছাড়লে এতক্ষণ অপেক্ষা (নতুন টোকেনে/নেটওয়ার্ক বদলে আবার আসতে পারেন) — তারপর কল শেষ */
    const val PEER_GRACE_MS = 12_000L

    /** কল চলার সময় call_status (নতুন টোকেন, শেষ হলো কি না) */
    const val PING_MS = 30_000L

    /** লক স্ক্রিনে: শুধু প্রথম নাম (পুরো নাম/প্রসঙ্গ খোলার পরে) */
    fun firstName(name: String): String = name.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
}
