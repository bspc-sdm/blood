package com.bspc.blood

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.twilio.audioswitch.AudioDevice
import com.twilio.audioswitch.AudioDeviceChangeListener
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.ConnectionQuality
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.VideoTrackPublishDefaults
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoPreset169
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * এই ফোনে একটাই কল চলে — তার অবস্থা (CallMachine), LiveKit ঘর, অডিওর পথ, foreground service আর নোটিফিকেশন।
 * পর্দা (CallActivity) শুধু state দেখে আর এখানের কাজগুলো ডাকে। অ্যাপ বন্ধ থাকলেও (পুশ এলে) কাজ করে।
 *
 * - কল করলে (CallButtons: call_start সফল) সাথে সাথে ঘরে যুক্ত হয়; ওপাশে বাজার সময় call_status দেখে (কেটে দিলেন/ধরেননি)।
 * - কল এলে (পুশ) বাজার সময় call_status দেখে (কলকারী কেটে দিলে থামে); ধরলে call_accept → ঘরে যুক্ত।
 * - দুর্বল নেটওয়ার্ক কিছুক্ষণ থাকলে ভিডিও বন্ধ করে শুধু অডিও (adaptive stream + dynacast আগেই মান কমায়)।
 */
object CallManager {
    private const val TAG = "LifoloCall"
    private val _state = MutableStateFlow(CallState())
    val state: StateFlow<CallState> = _state

    /** দেখানোর জন্য: অন্যজনের ভিডিও আর নিজের ক্যামেরা */
    val remoteVideo = MutableStateFlow<VideoTrack?>(null)
    val localVideo = MutableStateFlow<VideoTrack?>(null)

    private var app: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var room: Room? = null
    private var roomJob: Job? = null
    private var pollJob: Job? = null
    private var tickJob: Job? = null
    private var audio: AudioSwitchHandler? = null
    private var poorSince: Long? = null
    private var pingJob: Job? = null
    private var peerGoneJob: Job? = null
    /** নতুন টোকেনে আবার যুক্ত হওয়ার চেষ্টা (টোকেন ১০ মিনিটের) */
    private var rejoinTries = 0
    /** যে ঘরে সত্যিই যুক্ত হয়েছিলাম (প্রথমবার যুক্ত হতে না পারার বিচ্ছিন্নতা আলাদা) */
    private var connectedRoom: Room? = null
    /** কোন ফোনে ঘণ্টা বাজা শুরু হলো (বাজার সময়সীমা) */
    private var ringingSince = 0L

    fun init(context: Context) {
        if (app == null) app = context.applicationContext
    }

    fun currentRoom(): Room? = room

    private fun now() = System.currentTimeMillis()

    @Synchronized
    private fun dispatch(e: CallEvent) {
        val old = _state.value
        val new = CallMachine.reduce(old, e, now())
        if (new == old) return
        _state.value = new
        onTransition(old, new)
    }

    private fun onTransition(old: CallState, new: CallState) {
        val ctx = app ?: return
        if (new.phase != old.phase) Log.i(TAG, "call ${new.callId}: ${old.phase} -> ${new.phase} ${new.endReason ?: ""}")
        if (new.phase == CallPhase.INCOMING || new.phase == CallPhase.OUTGOING) ringingSince = now()
        // মাইক/ক্যামেরা চলার সময় foreground service (অ্যাপ পেছনে গেলেও কল চলে)
        if (new.usesMedia) CallService.sync(ctx) else if (old.usesMedia) CallService.stop(ctx)
        if (old.phase == CallPhase.INCOMING && new.phase != CallPhase.INCOMING) CallNotifications.cancelIncoming(ctx)
        if (new.phase == CallPhase.ENDED && old.phase != CallPhase.ENDED) cleanup()
        if (new.phase != old.phase) {
            startPolling(new)
            startPing(new)
        }
    }

    // ------------------------------------------------------------
    // কল করা, ধরা, যুক্ত হওয়া
    // ------------------------------------------------------------

    /** CallButtons: call_start সফল হলে */
    fun startOutgoing(context: Context, join: CallJoin, ctx: String, refId: String, video: Boolean, peerName: String) {
        init(context)
        if (_state.value.live) hangup()
        dispatch(CallEvent.Dialed(join, ctx, refId, video, peerName))
        connect(join, video)
    }

    /** পুশে কল এলো। অন্য কল চললে false (সার্ভার সাধারণত আগেই call_busy বলে) */
    fun onIncoming(context: Context, callId: String, video: Boolean, peerName: String, peerPhoto: String, ctx: String, refId: String, peerUid: String = ""): Boolean {
        init(context)
        val s = _state.value
        // একই কল আবার (পুরো পর্দার intent, দুবার পুশ): চলছে কি না; শেষ হয়ে গেলে আবার বাজানো নয়
        if (s.callId == callId) return s.live
        if (s.live) return false
        dispatch(CallEvent.Ringing(callId, video, peerName, peerPhoto, ctx, refId, peerUid))
        // "ভিডিওতে ধরুন" দেখানো ফিচার সুইচ দেখে (অ্যাপ বন্ধ ছিল — সুইচ আবার আনা)
        Features.ensureInit(context)
        Features.refreshSoon()
        // পুশে শুধু প্রথম নাম (কোন সূত্রে, ছবি নেই) — পুরো তথ্য call_status থেকে; লক স্ক্রিনে পর্দা তবুও শুধু প্রথম নাম দেখায়
        scope.launch {
            runCatching { CallsApi.status(callId) }.onSuccess { st -> if (_state.value.callId == callId) dispatch(CallEvent.Details(st.ctx, st.refId, st.peer)) }
        }
        return true
    }

    /**
     * ধরা (মাইকের অনুমতি আগেই নেওয়া)। video = "ভিডিওতে ধরুন" (false = "অডিওতে ধরুন": ভিডিও কলও অডিওতে, পরে সম্মতিতে ভিডিও);
     * withCamera = ক্যামেরার অনুমতি আছে
     */
    fun accept(video: Boolean, withCamera: Boolean) {
        val s = _state.value
        if (s.phase != CallPhase.INCOMING) return
        val callId = s.callId
        val asVideo = video && s.video
        dispatch(CallEvent.Accepting)
        if (!asVideo) dispatch(CallEvent.Downgraded)
        if ((!withCamera || !asVideo) && _state.value.camOn) dispatch(CallEvent.Camera(false))
        scope.launch {
            runCatching { CallsApi.accept(callId, asVideo) }
                .onSuccess { j ->
                    if (_state.value.callId != callId || _state.value.phase != CallPhase.CONNECTING) return@onSuccess
                    dispatch(CallEvent.Joined(j))
                    if (j.video == false) dispatch(CallEvent.Downgraded)
                    connect(j, asVideo && withCamera && j.video != false)
                }
                .onFailure { e -> fail(e, ended = true) }
        }
    }

    /** না ধরা */
    fun decline() {
        val s = _state.value
        if (s.phase != CallPhase.INCOMING) return
        val id = s.callId
        dispatch(CallEvent.Hangup)
        scope.launch { runCatching { CallsApi.decline(id) } }
    }

    /** অনলাইন অ্যাপয়েন্টমেন্ট: appt_join → ঘরে (অন্যজন না থাকলে অপেক্ষার ঘর) */
    fun joinAppointment(context: Context, appointmentId: String, withCamera: Boolean, peerName: String = "") {
        init(context)
        val s = _state.value
        if (s.live && s.appointmentId == appointmentId) return
        if (s.live) hangup()
        dispatch(CallEvent.JoiningAppointment(appointmentId, peerName))
        if (!withCamera) dispatch(CallEvent.Camera(false))
        scope.launch {
            runCatching { CallsApi.apptJoin(appointmentId) }
                .onSuccess { j ->
                    if (_state.value.appointmentId != appointmentId || !_state.value.live) return@onSuccess
                    dispatch(CallEvent.Joined(j))
                    connect(j, withCamera)
                }
                .onFailure { e -> fail(e, ended = false) }
        }
    }

    /** কেটে দেওয়া (যেকোনো অবস্থায়): বাজার সময় কলকারী → call_cancel, ধরার আগে → call_decline, চলার সময় → call_end */
    fun hangup() {
        val s = _state.value
        if (!s.live) return
        val id = s.callId
        when {
            s.phase == CallPhase.INCOMING -> { decline(); return }
            // অনলাইন অ্যাপয়েন্টমেন্টের কল একটাই (পরে আবার যুক্ত হওয়া যায়) — শুধু বের হওয়া
            s.isAppointment -> {}
            s.outgoing && s.connectedAt == null -> scope.launch { runCatching { CallsApi.cancel(id) } }
            else -> scope.launch { runCatching { CallsApi.end(id) } }
        }
        dispatch(CallEvent.Hangup)
    }

    /** শেষের পর্দা বন্ধ হলে */
    fun reset() {
        if (_state.value.phase == CallPhase.ENDED) dispatch(CallEvent.Reset)
    }

    /** সার্ভারের call_state পুশ (কলকারী কেটে দিলেন, অন্য ফোনে ধরা হলো …) */
    fun onServerState(callId: String, state: String) {
        if (_state.value.callId == callId) dispatch(CallEvent.Server(state))
    }

    private fun fail(e: Throwable, ended: Boolean) {
        val f = CallErrors.of(e)
        val reason = when (f) {
            CallFailure.BUSY -> EndReason.BUSY
            CallFailure.OFF -> EndReason.OFF
            CallFailure.NOT_ALLOWED -> EndReason.NOT_ALLOWED
            CallFailure.BLOCKED -> EndReason.BLOCKED
            CallFailure.UNAVAILABLE -> EndReason.UNAVAILABLE
            else -> EndReason.FAILED
        }
        val s = _state.value
        CallAvailability.onFailure(f, s.ctx.ifBlank { null }, s.refId.ifBlank { null })
        // সার্ভারের বার্তা (যেমন "এখনো সময় হয়নি") সরাসরি
        val msg = if (f == CallFailure.OTHER || f == CallFailure.OFF || f == CallFailure.BLOCKED) CallErrors.message(f, e.message) else CallErrors.message(f)
        if (ended && s.callId.isNotBlank()) scope.launch { runCatching { CallsApi.end(s.callId) } }
        dispatch(CallEvent.Failed(reason, msg))
    }

    // ------------------------------------------------------------
    // LiveKit
    // ------------------------------------------------------------

    private fun has(perm: String): Boolean {
        val c = app ?: return false
        return ContextCompat.checkSelfPermission(c, perm) == PackageManager.PERMISSION_GRANTED
    }

    /** ঘরে যুক্ত হওয়া। rejoin = কল চলার মাঝে নতুন টোকেনে আবার (টোকেন ফুরোলে, ভিডিও চালু হলে) */
    private fun connect(join: CallJoin, video: Boolean, rejoin: Boolean = false) {
        val ctx = app ?: return
        releaseRoom()
        val r = runCatching {
            LiveKit.create(
                ctx,
                RoomOptions(
                    // দেখা না গেলে/ছোট হলে কম মানের ভিডিও; কেউ না দেখলে পাঠানোও বন্ধ — কম ডেটা
                    adaptiveStream = true,
                    dynacast = true,
                    videoTrackCaptureDefaults = LocalVideoTrackOptions(position = CameraPosition.FRONT, captureParams = VideoPreset169.H540.capture),
                    videoTrackPublishDefaults = VideoTrackPublishDefaults(videoEncoding = VideoPreset169.H540.encoding, simulcast = true),
                ),
            )
        }.getOrElse { e ->
            Log.w(TAG, "room create failed", e)
            dispatch(CallEvent.Failed(EndReason.FAILED, tr("The call couldn't connect", "কল যুক্ত হয়নি")))
            return
        }
        room = r
        setupAudio(r, _state.value.video || video)
        roomJob = scope.launch { r.events.collect { onRoomEvent(r, it) } }
        scope.launch {
            runCatching {
                r.connect(join.url, join.token)
                if (has(Manifest.permission.RECORD_AUDIO)) r.localParticipant.setMicrophoneEnabled(_state.value.micOn)
                if (video && has(Manifest.permission.CAMERA)) {
                    r.localParticipant.setCameraEnabled(true)
                    refreshLocalVideo(r)
                } else if (_state.value.camOn) {
                    dispatch(CallEvent.Camera(false))
                }
            }.onSuccess {
                if (room !== r) return@onSuccess
                rejoinTries = 0
                connectedRoom = r
                dispatch(CallEvent.RoomConnected)
                if (r.remoteParticipants.isNotEmpty()) {
                    dispatch(CallEvent.PeerJoined)
                    refreshRemoteVideo(r)
                }
                startTicker()
            }.onFailure { e ->
                if (room !== r) return@onFailure
                Log.w(TAG, "connect failed", e)
                if (rejoin) { rejoin(); return@onFailure }
                val s = _state.value
                if (s.live) {
                    if (s.outgoing && s.connectedAt == null && !s.isAppointment) scope.launch { runCatching { CallsApi.cancel(s.callId) } }
                    else if (!s.isAppointment) scope.launch { runCatching { CallsApi.end(s.callId) } }
                }
                dispatch(CallEvent.Failed(EndReason.FAILED, tr("Couldn't connect the call. Check your internet.", "কল যুক্ত হয়নি। ইন্টারনেট দেখুন।")))
            }
        }
    }

    /** অডিও কলে কানের স্পিকার আগে, ভিডিও কলে লাউডস্পিকার; ব্লুটুথ/তারের হেডসেট থাকলে সেটাই */
    private fun setupAudio(r: Room, video: Boolean) {
        val h = r.audioHandler as? AudioSwitchHandler ?: return
        audio = h
        h.preferredDeviceList = if (video) {
            listOf(AudioDevice.BluetoothHeadset::class.java, AudioDevice.WiredHeadset::class.java, AudioDevice.Speakerphone::class.java, AudioDevice.Earpiece::class.java)
        } else {
            listOf(AudioDevice.BluetoothHeadset::class.java, AudioDevice.WiredHeadset::class.java, AudioDevice.Earpiece::class.java, AudioDevice.Speakerphone::class.java)
        }
        h.registerAudioDeviceChangeListener(object : AudioDeviceChangeListener {
            override fun invoke(audioDevices: List<AudioDevice>, selectedAudioDevice: AudioDevice?) {
                scope.launch {
                    dispatch(CallEvent.Speaker(selectedAudioDevice is AudioDevice.Speakerphone))
                    dispatch(CallEvent.AudioRoute(routeOf(selectedAudioDevice)))
                }
            }
        })
    }

    private fun routeOf(d: AudioDevice?): String = when (d) {
        is AudioDevice.BluetoothHeadset -> "bluetooth"
        is AudioDevice.WiredHeadset -> "wired"
        is AudioDevice.Speakerphone -> "speaker"
        is AudioDevice.Earpiece -> "earpiece"
        else -> ""
    }

    private suspend fun onRoomEvent(r: Room, e: RoomEvent) {
        if (room !== r) return
        when (e) {
            is RoomEvent.ParticipantConnected -> {
                peerGoneJob?.cancel()
                dispatch(CallEvent.PeerJoined)
                refreshRemoteVideo(r)
            }
            is RoomEvent.ParticipantDisconnected -> if (r.remoteParticipants.isEmpty()) {
                remoteVideo.value = null
                dispatch(CallEvent.RemoteVideo(false))
                val s = _state.value
                if (s.isAppointment || s.connectedAt == null) {
                    dispatch(CallEvent.PeerLeft)
                } else {
                    // অন্যজন নতুন টোকেনে/নেটওয়ার্ক বদলে আবার আসতে পারেন — একটু অপেক্ষা, তারপর কল শেষ (সার্ভারেও)
                    peerGoneJob?.cancel()
                    peerGoneJob = scope.launch {
                        delay(CallMachine.PEER_GRACE_MS)
                        val cur = _state.value
                        if (room === r && r.remoteParticipants.isEmpty() && cur.live && cur.callId == s.callId) {
                            dispatch(CallEvent.PeerLeft)
                            runCatching { CallsApi.end(s.callId) }
                        }
                    }
                }
            }
            is RoomEvent.TrackSubscribed -> if (e.track is VideoTrack) refreshRemoteVideo(r)
            is RoomEvent.TrackUnsubscribed -> if (e.track is VideoTrack) refreshRemoteVideo(r)
            is RoomEvent.TrackMuted -> if (e.participant !is LocalParticipant) refreshRemoteVideo(r)
            is RoomEvent.TrackUnmuted -> if (e.participant !is LocalParticipant) refreshRemoteVideo(r)
            is RoomEvent.Reconnecting -> dispatch(CallEvent.Reconnecting)
            is RoomEvent.Reconnected -> dispatch(CallEvent.Reconnected)
            // LiveKit নিজে আবার যুক্ত হতে পারেনি (টোকেন ফুরিয়েছে বা নেটওয়ার্ক): নতুন টোকেন নিয়ে আবার
            is RoomEvent.Disconnected -> if (_state.value.live && connectedRoom === r) {
                connectedRoom = null
                rejoin()
            }
            is RoomEvent.ConnectionQualityChanged -> if (e.participant is LocalParticipant) {
                val poor = e.quality == ConnectionQuality.POOR || e.quality == ConnectionQuality.LOST
                if (poor) { if (poorSince == null) poorSince = now() } else poorSince = null
                dispatch(CallEvent.Network(poor))
            }
            else -> {}
        }
    }

    private fun refreshRemoteVideo(r: Room) {
        val pub = r.remoteParticipants.values.firstNotNullOfOrNull { it.getTrackPublication(Track.Source.CAMERA) }
        // অডিও কলে (অডিওতে ধরা, বা ভিডিও চালু হয়নি) অন্যজনের ভিডিও নেওয়াই হয় না — ডেটা বাঁচে, সম্মতি ছাড়া দেখা নয়
        if (!_state.value.video) {
            (pub as? io.livekit.android.room.track.RemoteTrackPublication)?.let { p -> runCatching { p.setSubscribed(false) } }
            remoteVideo.value = null
            dispatch(CallEvent.RemoteVideo(false))
            return
        }
        val track = pub?.takeIf { !it.muted }?.track as? VideoTrack
        remoteVideo.value = track
        dispatch(CallEvent.RemoteVideo(track != null))
    }

    private fun refreshLocalVideo(r: Room) {
        val pub = r.localParticipant.getTrackPublication(Track.Source.CAMERA)
        localVideo.value = pub?.takeIf { !it.muted }?.track as? VideoTrack
    }

    /** প্রতি সেকেন্ডে: দুর্বল নেটওয়ার্ক অনেকক্ষণ → শুধু অডিও */
    private fun startTicker() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive) {
                delay(1000)
                val s = _state.value
                if (!s.live) break
                val since = poorSince
                if (since != null && s.camOn && now() - since >= CallMachine.POOR_FALLBACK_MS) {
                    room?.let { r ->
                        runCatching { r.localParticipant.setCameraEnabled(false) }
                        localVideo.value = null
                    }
                    dispatch(CallEvent.AudioFallback)
                }
            }
        }
    }

    // ------------------------------------------------------------
    // বাজার সময় সার্ভারের অবস্থা দেখা
    // ------------------------------------------------------------

    private fun startPolling(s: CallState) {
        pollJob?.cancel()
        val ringing = s.phase == CallPhase.INCOMING || (s.phase == CallPhase.OUTGOING && s.connectedAt == null)
        if (!ringing || s.callId.isBlank()) return
        val id = s.callId
        pollJob = scope.launch {
            while (isActive) {
                delay(if (s.phase == CallPhase.OUTGOING) 2000 else 3000)
                val cur = _state.value
                if (cur.callId != id || cur.phase != s.phase) break
                // সার্ভারের ৪৫ সেকেন্ডের পরও কিছু না এলে নিজেই শেষ
                if (now() - ringingSince > 55_000) {
                    if (cur.outgoing) { runCatching { CallsApi.cancel(id) }; dispatch(CallEvent.Server("missed")) }
                    else dispatch(CallEvent.Server("missed"))
                    break
                }
                runCatching { CallsApi.status(id) }.onSuccess { st ->
                    if (_state.value.callId == id) {
                        dispatch(CallEvent.Server(st.state))
                        applyVideoFlag(st)
                    }
                }
            }
        }
    }

    /** নতুন টোকেন (চলমান কল: call_status; অ্যাপয়েন্টমেন্ট: appt_join) নিয়ে আবার যুক্ত হওয়া — ৩ বার পর্যন্ত */
    private fun rejoin() {
        val s = _state.value
        if (!s.live) return
        if (rejoinTries >= 3) {
            if (!s.isAppointment) scope.launch { runCatching { CallsApi.end(s.callId) } }
            dispatch(CallEvent.Failed(EndReason.FAILED, tr("The call was cut off", "কল কেটে গেছে")))
            return
        }
        rejoinTries++
        dispatch(CallEvent.Reconnecting)
        val tries = rejoinTries
        scope.launch {
            delay(1000L * tries)
            val j = runCatching { freshJoin(s) }.getOrNull()
            val cur = _state.value
            if (!cur.live || cur.callId != s.callId) return@launch
            if (j == null) rejoin() else connect(j, cur.camOn, rejoin = true)
        }
    }

    private suspend fun freshJoin(s: CallState): CallJoin? {
        if (s.isAppointment) return CallsApi.apptJoin(s.appointmentId)
        val st = CallsApi.status(s.callId)
        if (st.state in setOf("ended", "cancelled", "declined", "missed")) {
            dispatch(CallEvent.Server(st.state))
            return null
        }
        return st.join(s.callId)
    }

    /** কল চলার সময় ৩০ সেকেন্ড পরপর call_status: শেষ হলো কি না, নতুন টোকেন, ভিডিও চালু হলো কি না (পুশ না এলেও) */
    private fun startPing(s: CallState) {
        val inCall = s.phase in setOf(CallPhase.ACTIVE, CallPhase.WAITING, CallPhase.RECONNECTING) && s.callId.isNotBlank()
        if (!inCall) { pingJob?.cancel(); pingJob = null; return }
        if (pingJob?.isActive == true) return
        val id = s.callId
        pingJob = scope.launch {
            while (isActive) {
                delay(CallMachine.PING_MS)
                val cur = _state.value
                if (!cur.live || cur.callId != id) break
                val st = runCatching { CallsApi.status(id) }.getOrNull() ?: continue
                if (_state.value.callId != id) break
                dispatch(CallEvent.Server(st.state))
                st.peer?.let { dispatch(CallEvent.PeerInfo(it)) }
                applyVideoFlag(st)
                val after = _state.value
                if (after.live && !after.video && st.video) st.join(id)?.let { applyUpgrade(it, after.upgradePending && has(Manifest.permission.CAMERA)) }
            }
        }
    }

    // ------------------------------------------------------------
    // অডিও কল → ভিডিও (দুজনের সম্মতিতে)
    // ------------------------------------------------------------

    /** "ভিডিও চালু করতে অনুরোধ" (ক্যামেরার অনুমতি আগেই নেওয়া) */
    fun requestUpgrade() {
        val s = _state.value
        if (!s.live || s.video || s.upgradePending || s.connectedAt == null) return
        scope.launch {
            runCatching { CallsApi.upgrade(s.callId) }
                .onSuccess { already ->
                    if (_state.value.callId != s.callId) return@onSuccess
                    if (already) onUpgraded(s.callId) else dispatch(CallEvent.UpgradeSent)
                }
                .onFailure { e -> upgradeError.value = CallErrors.message(CallErrors.of(e), e.message) }
        }
    }

    /** অনুরোধ পাঠানো/উত্তর দেওয়া না গেলে ছোট বার্তা (পর্দা দেখায়, পরে সরায়) */
    val upgradeError = MutableStateFlow<String?>(null)

    /** অন্যজনের অনুরোধ (পুশ call_upgrade) */
    fun onUpgradeAsked(callId: String) {
        if (_state.value.callId == callId) dispatch(CallEvent.UpgradeAsked)
    }

    /** অনুরোধের উত্তর; withCamera = ক্যামেরার অনুমতি আছে */
    fun answerUpgrade(accept: Boolean, withCamera: Boolean) {
        val s = _state.value
        dispatch(CallEvent.UpgradeDismissed)
        if (!s.live) return
        scope.launch {
            if (accept) {
                runCatching { CallsApi.upgradeAccept(s.callId) }
                    .onSuccess { j -> if (_state.value.callId == s.callId) applyUpgrade(j, withCamera) }
                    .onFailure { e -> upgradeError.value = CallErrors.message(CallErrors.of(e), e.message) }
            } else {
                runCatching { CallsApi.upgradeDecline(s.callId) }
            }
        }
    }

    /** অন্যজন রাজি হয়েছেন (পুশ call_upgraded): call_status-এ ভিডিওর টোকেন */
    fun onUpgraded(callId: String) {
        val s = _state.value
        if (s.callId != callId || !s.live || s.video) return
        scope.launch {
            val st = runCatching { CallsApi.status(callId) }.getOrNull() ?: return@launch
            val j = st.join(callId) ?: return@launch
            if (st.video && _state.value.callId == callId && !_state.value.video) applyUpgrade(j, has(Manifest.permission.CAMERA))
        }
    }

    fun onUpgradeDeclined(callId: String) {
        if (_state.value.callId == callId) dispatch(CallEvent.UpgradeDeclined)
    }

    fun dismissUpgradeNote() = dispatch(CallEvent.UpgradeDismissed)

    /** ধরা কলে সার্ভার বলছে ভিডিও নয় (অন্যজন অডিওতে ধরেছেন): নিজের ক্যামেরা বন্ধ, অন্যজনের ভিডিও আর নেওয়া হয় না */
    private fun applyVideoFlag(st: CallStatus) {
        val s = _state.value
        if (st.state == "accepted" && s.video && !st.video && !s.isAppointment) {
            dispatch(CallEvent.Downgraded)
            room?.let { r -> scope.launch { runCatching { r.localParticipant.setCameraEnabled(false) }; localVideo.value = null } }
            remoteVideo.value = null
            // নতুন (অডিওর) টোকেন থাকলে সেটাতে আবার — অন্যজনের ভিডিও আর আসে না
            st.join(s.callId)?.let { connect(it, false, rejoin = true) }
        }
    }

    private fun applyUpgrade(j: CallJoin, camera: Boolean) {
        dispatch(CallEvent.Upgraded(camera))
        connect(j, camera, rejoin = true)
    }

    // ------------------------------------------------------------
    // বোতাম
    // ------------------------------------------------------------

    fun setMic(on: Boolean) {
        val r = room
        dispatch(CallEvent.Mic(on))
        if (r != null) scope.launch { runCatching { r.localParticipant.setMicrophoneEnabled(on) } }
    }

    /** ক্যামেরা চালু/বন্ধ (চালুর আগে অনুমতি পর্দা নেয়) */
    fun setCamera(on: Boolean) {
        val r = room
        dispatch(CallEvent.Camera(on))
        if (on) poorSince = null
        if (r != null) scope.launch {
            runCatching { r.localParticipant.setCameraEnabled(on) }
            refreshLocalVideo(r)
        }
    }

    fun switchCamera() {
        val t = localVideo.value as? LocalVideoTrack ?: return
        val front = !_state.value.frontCamera
        runCatching { t.switchCamera(position = if (front) CameraPosition.FRONT else CameraPosition.BACK) }
            .onSuccess { dispatch(CallEvent.FrontCamera(front)) }
    }

    /** লাউডস্পিকার চালু/বন্ধ (বন্ধ: হেডসেট থাকলে সেটা, নইলে কানের স্পিকার) */
    fun setSpeaker(on: Boolean) {
        val h = audio ?: return
        val devices = h.availableAudioDevices
        val target = if (on) devices.firstOrNull { it is AudioDevice.Speakerphone }
        else devices.firstOrNull { it is AudioDevice.BluetoothHeadset } ?: devices.firstOrNull { it is AudioDevice.WiredHeadset } ?: devices.firstOrNull { it is AudioDevice.Earpiece }
        if (target != null) h.selectDevice(target)
        dispatch(CallEvent.Speaker(on))
    }

    // ------------------------------------------------------------

    private fun releaseRoom() {
        roomJob?.cancel()
        roomJob = null
        tickJob?.cancel()
        audio = null
        val r = room
        room = null
        remoteVideo.value = null
        localVideo.value = null
        poorSince = null
        if (r != null) runCatching { r.release() }
    }

    private fun cleanup() {
        pollJob?.cancel()
        pingJob?.cancel()
        peerGoneJob?.cancel()
        rejoinTries = 0
        releaseRoom()
        app?.let { CallNotifications.cancelIncoming(it); CallService.stop(it) }
    }
}
