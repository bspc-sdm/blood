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
    const val ORDER = "order"
    val ALL = setOf(BLOOD, APPOINTMENT, AMBULANCE, ORDER)

    fun label(ctx: String): String = when (ctx) {
        BLOOD -> tr("Blood request", "রক্তের রিকোয়েস্ট")
        APPOINTMENT -> tr("Appointment", "অ্যাপয়েন্টমেন্ট")
        AMBULANCE -> tr("Ambulance", "অ্যাম্বুলেন্স")
        ORDER -> tr("Order", "অর্ডার")
        else -> ""
    }
}

data class CallPeer(val uid: String, val name: String, val photoThumb: String = "")

/** call_start / call_accept / appt_join-এর উত্তর: কোন ঘরে, কোন সার্ভারে (url সবসময় সার্ভার যা দেয়), কোন টোকেনে */
data class CallJoin(val callId: String, val room: String, val url: String, val token: String, val peer: CallPeer? = null)

data class CallStatus(val state: String, val startedAt: Long = 0, val endedAt: Long = 0)

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
enum class CallFailure { NOT_ALLOWED, OFF, BUSY, UNAVAILABLE, RATE_LIMIT, NETWORK, OTHER }

object CallErrors {
    fun classify(code: String?, httpLike: Boolean = true): CallFailure = when (code) {
        "call_not_allowed" -> CallFailure.NOT_ALLOWED
        "call_off" -> CallFailure.OFF
        "call_busy" -> CallFailure.BUSY
        "calls_unavailable", "calls_disabled" -> CallFailure.UNAVAILABLE
        "rate_limited", "too_many_requests", "quota" -> CallFailure.RATE_LIMIT
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
        CallFailure.UNAVAILABLE -> tr("App calls aren't available right now. Call their phone.", "অ্যাপে কল এখন চালু নেই। ফোনে কল করুন।")
        CallFailure.RATE_LIMIT -> tr("Too many calls in a short time. Wait a few minutes.", "অল্প সময়ে অনেক কল হয়েছে। কয়েক মিনিট পরে চেষ্টা করুন।")
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
            CallFailure.NOT_ALLOWED -> if (ctx != null && refId != null) blocked[key(ctx, refId)] = true
            else -> {}
        }
    }

    /** লগআউট/অ্যাকাউন্ট বদল */
    fun clear() {
        enabled.value = null
        checkedAt = 0
        blocked.clear()
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

    suspend fun accept(callId: String): CallJoin = parseJoin(call("call_accept", JSONObject().put("callId", callId)), callId)
    suspend fun decline(callId: String) { call("call_decline", JSONObject().put("callId", callId)) }
    suspend fun cancel(callId: String) { call("call_cancel", JSONObject().put("callId", callId)) }
    suspend fun end(callId: String) { call("call_end", JSONObject().put("callId", callId)) }

    suspend fun status(callId: String): CallStatus {
        val o = call("call_status", JSONObject().put("callId", callId))
        return CallStatus(o.str("state"), o.optLong("startedAt"), o.optLong("endedAt"))
    }

    suspend fun history(before: Long? = null): List<CallHistoryItem> {
        val o = call("call_history", JSONObject().apply { if (before != null) put("before", before) })
        val arr = o.optJSONArray("items") ?: o.optJSONArray("calls") ?: org.json.JSONArray()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map(::parseHistory)
    }

    /** অনলাইন চেম্বারের অ্যাপয়েন্টমেন্টে ভিডিও কলে যুক্ত হওয়া (রোগী বা ডাক্তার/সহকারী) */
    suspend fun apptJoin(appointmentId: String): CallJoin = parseJoin(call("appt_join", JSONObject().put("appointmentId", appointmentId)))

    internal fun parsePeer(o: JSONObject?): CallPeer? = o?.let { CallPeer(it.str("uid"), it.str("name"), it.str("photoThumb")) }

    internal fun parseJoin(o: JSONObject, fallbackCallId: String = ""): CallJoin {
        val join = CallJoin(o.str("callId").ifBlank { fallbackCallId }, o.str("room"), o.str("url"), o.str("token"), parsePeer(o.optJSONObject("peer")))
        if (join.url.isBlank() || join.token.isBlank()) throw ServerError(tr("The call couldn't be made", "কল করা গেল না"), "calls_unavailable")
        return join
    }

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

enum class EndReason { HANGUP, REMOTE_ENDED, DECLINED, NO_ANSWER, MISSED, CANCELLED, BUSY, FAILED, NOT_ALLOWED, OFF, UNAVAILABLE }

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
    data class Ringing(val callId: String, val video: Boolean, val peerName: String, val peerPhoto: String, val ctx: String, val refId: String) : CallEvent
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
                camOn = e.video, speakerOn = e.video,
            )
            is CallEvent.Ringing -> if (s.live && s.callId != e.callId) s else CallState(
                phase = CallPhase.INCOMING, callId = e.callId, video = e.video, outgoing = false, ctx = e.ctx, refId = e.refId,
                peerName = e.peerName, peerPhoto = e.peerPhoto, camOn = e.video, speakerOn = e.video,
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
            CallEvent.Reset -> CallState()
        }
    }

    /** শেষের পর্দার লেখা */
    fun endText(s: CallState): String = when (s.endReason) {
        EndReason.DECLINED -> tr("They declined the call", "উনি কল ধরেননি (কেটে দিয়েছেন)")
        EndReason.NO_ANSWER -> tr("No answer", "কেউ ধরেননি")
        EndReason.MISSED -> tr("Missed call", "মিসড কল")
        EndReason.CANCELLED -> tr("The call ended", "কল শেষ হয়েছে")
        EndReason.BUSY, EndReason.OFF, EndReason.NOT_ALLOWED, EndReason.UNAVAILABLE, EndReason.FAILED ->
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
}
