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
        if (new.phase != old.phase) startPolling(new)
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
    fun onIncoming(context: Context, callId: String, video: Boolean, peerName: String, peerPhoto: String, ctx: String, refId: String): Boolean {
        init(context)
        val s = _state.value
        // একই কল আবার (পুরো পর্দার intent, দুবার পুশ): চলছে কি না; শেষ হয়ে গেলে আবার বাজানো নয়
        if (s.callId == callId) return s.live
        if (s.live) return false
        dispatch(CallEvent.Ringing(callId, video, peerName, peerPhoto, ctx, refId))
        return true
    }

    /** ধরা (মাইকের অনুমতি আগেই নেওয়া); withCamera = ভিডিও কলে ক্যামেরা চালু করে ধরা */
    fun accept(withCamera: Boolean) {
        val s = _state.value
        if (s.phase != CallPhase.INCOMING) return
        val callId = s.callId
        dispatch(CallEvent.Accepting)
        if (!withCamera && s.camOn) dispatch(CallEvent.Camera(false))
        scope.launch {
            runCatching { CallsApi.accept(callId) }
                .onSuccess { j ->
                    if (_state.value.callId != callId || _state.value.phase != CallPhase.CONNECTING) return@onSuccess
                    dispatch(CallEvent.Joined(j))
                    connect(j, withCamera)
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
            CallFailure.UNAVAILABLE -> EndReason.UNAVAILABLE
            else -> EndReason.FAILED
        }
        val s = _state.value
        CallAvailability.onFailure(f, s.ctx.ifBlank { null }, s.refId.ifBlank { null })
        // সার্ভারের বার্তা (যেমন "এখনো সময় হয়নি") সরাসরি
        val msg = if (f == CallFailure.OTHER || f == CallFailure.OFF) CallErrors.message(f, e.message) else CallErrors.message(f)
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

    private fun connect(join: CallJoin, video: Boolean) {
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
        setupAudio(r, video)
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
                dispatch(CallEvent.RoomConnected)
                if (r.remoteParticipants.isNotEmpty()) {
                    dispatch(CallEvent.PeerJoined)
                    refreshRemoteVideo(r)
                }
                startTicker()
            }.onFailure { e ->
                if (room !== r) return@onFailure
                Log.w(TAG, "connect failed", e)
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
                dispatch(CallEvent.PeerJoined)
                refreshRemoteVideo(r)
            }
            is RoomEvent.ParticipantDisconnected -> if (r.remoteParticipants.isEmpty()) {
                remoteVideo.value = null
                val s = _state.value
                dispatch(CallEvent.PeerLeft)
                // দুজনের কলে অন্যজন চলে গেলে সার্ভারেও শেষ (অন্যজন ইতিমধ্যে call_end পাঠিয়ে থাকতে পারেন — দুবার ক্ষতি নেই)
                if (!s.isAppointment && s.connectedAt != null) scope.launch { runCatching { CallsApi.end(s.callId) } }
            }
            is RoomEvent.TrackSubscribed -> if (e.track is VideoTrack) refreshRemoteVideo(r)
            is RoomEvent.TrackUnsubscribed -> if (e.track is VideoTrack) refreshRemoteVideo(r)
            is RoomEvent.TrackMuted -> if (e.participant !is LocalParticipant) refreshRemoteVideo(r)
            is RoomEvent.TrackUnmuted -> if (e.participant !is LocalParticipant) refreshRemoteVideo(r)
            is RoomEvent.Reconnecting -> dispatch(CallEvent.Reconnecting)
            is RoomEvent.Reconnected -> dispatch(CallEvent.Reconnected)
            is RoomEvent.Disconnected -> if (_state.value.live) {
                dispatch(CallEvent.Failed(EndReason.FAILED, tr("The call was cut off", "কল কেটে গেছে")))
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
                runCatching { CallsApi.status(id) }.onSuccess { st -> if (_state.value.callId == id) dispatch(CallEvent.Server(st.state)) }
            }
        }
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
        releaseRoom()
        app?.let { CallNotifications.cancelIncoming(it); CallService.stop(it) }
    }
}
