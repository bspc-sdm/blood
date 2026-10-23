@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.bspc.blood

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.delay
import livekit.org.webrtc.RendererCommon

/**
 * কলের পর্দা — আলাদা task-এ (অ্যাপের পাতা বদলালেও কল চলে), লক স্ক্রিনেও খোলে:
 * আসা কল (ধরুন/কাটুন), কল যাচ্ছে, অপেক্ষার ঘর (অনলাইন অ্যাপয়েন্টমেন্ট), কল চলছে (মাইক, স্পিকার, ক্যামেরা, ক্যামেরা ঘোরানো,
 * কেটে দিন, সময়, আবার যুক্ত হচ্ছে, দুর্বল নেটওয়ার্কে শুধু অডিও), কল শেষ। অডিও কলে কানের কাছে নিলে পর্দা বন্ধ (proximity),
 * ভিডিওতে পর্দা জ্বলে থাকে। অনুমতি (মাইক, ক্যামেরা) চাওয়া হয় ধরার/যুক্ত হওয়ার মুহূর্তে, কারণসহ।
 */
class CallActivity : ComponentActivity() {
    private var proximity: PowerManager.WakeLock? = null
    /** কী করতে হবে (intent থেকে) — অনুমতির পর */
    private val pending = mutableStateOf<CallOp?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppPrefs.init(this)
        CallManager.init(this)
        showOnLockScreen()
        if (savedInstanceState == null) handle(intent)
        setContent {
            GetBloodTheme {
                Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }, color = CallColors.bg) {
                    CallRoot(
                        pending = pending.value,
                        onPendingDone = { pending.value = null },
                        onWindow = { applyWindow(it) },
                        onClose = { finishAndRemoveTask() },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        i ?: return
        val callId = i.getStringExtra(CallIntents.EXTRA_CALL_ID).orEmpty()
        fun ring() = CallManager.onIncoming(
            this, callId, i.getBooleanExtra(CallIntents.EXTRA_VIDEO, false), i.getStringExtra(CallIntents.EXTRA_PEER_NAME).orEmpty(),
            i.getStringExtra(CallIntents.EXTRA_PEER_PHOTO).orEmpty(), i.getStringExtra(CallIntents.EXTRA_CTX).orEmpty(), i.getStringExtra(CallIntents.EXTRA_REF_ID).orEmpty(),
        )
        when (i.action) {
            CallIntents.ACTION_INCOMING -> if (callId.isNotBlank()) ring()
            CallIntents.ACTION_ACCEPT -> if (callId.isNotBlank() && ring()) pending.value = CallOp.Accept(CallManager.state.value.video)
            CallIntents.ACTION_APPT_JOIN -> i.getStringExtra(CallIntents.EXTRA_APPOINTMENT_ID)?.takeIf { it.isNotBlank() }?.let { pending.value = CallOp.Join(it) }
        }
    }

    private fun showOnLockScreen() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
    }

    /** ভিডিওতে পর্দা জ্বলে থাকে; অডিও কলে কানের স্পিকারে কথা বললে proximity-তে পর্দা বন্ধ */
    private fun applyWindow(s: CallState) {
        val videoOn = s.live && (s.camOn || s.remoteVideoOn || s.video)
        if (videoOn || s.phase == CallPhase.INCOMING) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val earpiece = !s.speakerOn && s.audioRoute != "bluetooth" && s.audioRoute != "wired"
        val wantProximity = s.usesMedia && !s.camOn && !s.remoteVideoOn && earpiece
        if (wantProximity) {
            if (proximity == null) {
                val pm = getSystemService(PowerManager::class.java)
                if (pm != null && (Build.VERSION.SDK_INT < 21 || pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK))) {
                    proximity = runCatching { pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "lifolo:call-proximity").apply { setReferenceCounted(false); acquire(3 * 60 * 60 * 1000L) } }.getOrNull()
                }
            }
        } else releaseProximity()
    }

    private fun releaseProximity() {
        proximity?.let { runCatching { if (it.isHeld) it.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY) } }
        proximity = null
    }

    override fun onDestroy() {
        releaseProximity()
        super.onDestroy()
    }
}

/** অনুমতির পর যা করা হবে */
sealed interface CallOp {
    data class Accept(val video: Boolean) : CallOp
    data class Join(val appointmentId: String) : CallOp
    data object Camera : CallOp
    /** "ভিডিও চালু করতে অনুরোধ" (ক্যামেরা লাগে) */
    data object Upgrade : CallOp
    /** অন্যজনের অনুরোধে রাজি */
    data object UpgradeAccept : CallOp
}

internal object CallColors {
    val bg = Color(0xFF0F1417)
    val panel = Color(0x33FFFFFF)
    val text = Color.White
    val dim = Color(0xB3FFFFFF)
    val red = Color(0xFFD93025)
    val green = Color(0xFF1E8E3E)
    val warn = Color(0xFFF9AB00)
}

@Composable
private fun CallRoot(pending: CallOp?, onPendingDone: () -> Unit, onWindow: (CallState) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val s by CallManager.state.collectAsState()
    var asking by remember { mutableStateOf<Pair<CallOp, List<String>>?>(null) }
    var deniedOp by remember { mutableStateOf<CallOp?>(null) }
    var waitingFor by remember { mutableStateOf<CallOp?>(null) }

    LaunchedEffect(s.phase, s.video, s.camOn, s.remoteVideoOn, s.speakerOn, s.audioRoute) { onWindow(s) }
    // লক স্ক্রিনে: শুধু প্রথম নাম, "লাইফলো কল" — কোন রিকোয়েস্ট/অ্যাপয়েন্টমেন্ট নয় (খোলার পরে পুরো)
    val keyguard = remember { context.getSystemService(android.app.KeyguardManager::class.java) }
    var locked by remember { mutableStateOf(keyguard?.isKeyguardLocked == true) }
    LaunchedEffect(Unit) {
        while (true) {
            locked = keyguard?.isKeyguardLocked == true
            delay(1000)
        }
    }

    fun perform(op: CallOp) {
        val mic = CallPermissions.granted(context, Manifest.permission.RECORD_AUDIO)
        val cam = CallPermissions.granted(context, Manifest.permission.CAMERA)
        when (op) {
            is CallOp.Accept -> if (mic) CallManager.accept(op.video && cam) else deniedOp = op
            is CallOp.Join -> if (mic) CallManager.joinAppointment(context, op.appointmentId, cam) else deniedOp = op
            CallOp.Camera -> if (cam) CallManager.setCamera(true) else deniedOp = op
            CallOp.Upgrade -> if (cam) CallManager.requestUpgrade() else deniedOp = op
            // ক্যামেরা না দিলেও রাজি — অন্যজনকে দেখা যাবে, নিজের ক্যামেরা বন্ধ
            CallOp.UpgradeAccept -> CallManager.answerUpgrade(true, cam)
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        waitingFor?.let { perform(it) }
        waitingFor = null
    }

    fun request(op: CallOp) {
        val video = when (op) { is CallOp.Accept -> op.video; is CallOp.Join -> true; else -> true }
        val camOnly = op == CallOp.Camera || op == CallOp.Upgrade || op == CallOp.UpgradeAccept
        val need = if (camOnly) listOf(Manifest.permission.CAMERA).filter { !CallPermissions.granted(context, it) } else CallPermissions.missing(context, video)
        if (need.isEmpty()) perform(op) else asking = op to need
    }

    LaunchedEffect(pending) {
        val op = pending ?: return@LaunchedEffect
        onPendingDone()
        request(op)
    }

    // কিছু করার নেই (পুরোনো নোটিফিকেশন, কল আগেই শেষ): বন্ধ
    LaunchedEffect(s.phase, pending, asking) {
        if (s.phase == CallPhase.IDLE && pending == null && asking == null) {
            delay(400)
            if (CallManager.state.value.phase == CallPhase.IDLE) onClose()
        }
    }

    Box(Modifier.fillMaxSize().background(CallColors.bg)) {
        when (s.phase) {
            CallPhase.IDLE -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = CallColors.text) }
            CallPhase.INCOMING -> IncomingCall(
                s, locked,
                onAccept = { request(CallOp.Accept(s.video)) },
                onAcceptAudio = { request(CallOp.Accept(false)) },
                onDecline = { CallManager.decline() },
            )
            CallPhase.ENDED -> {
                var stay by remember(s.callId) { mutableStateOf(false) }
                EndedCall(s, locked, onStay = { stay = true }, onClose = { CallManager.reset(); onClose() })
                LaunchedEffect(s.callId, s.endReason, stay) {
                    if (stay) return@LaunchedEffect
                    // কথা হয়ে থাকলে ব্লক/রিপোর্টের সুযোগ — একটু বেশি সময়
                    delay(if (s.connectedAt != null) 6000 else if (s.endReason == EndReason.HANGUP) 1200 else 2600)
                    CallManager.reset()
                    onClose()
                }
            }
            else -> InCall(
                s, locked,
                onCamera = { on -> if (on) request(CallOp.Camera) else CallManager.setCamera(false) },
                onUpgrade = { request(CallOp.Upgrade) },
            )
        }
    }

    asking?.let { (op, need) ->
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text(if (op is CallOp.Accept && !op.video) tr("Allow to answer", "কল ধরতে অনুমতি দিন") else tr("Allow for the call", "কলের জন্য অনুমতি দিন")) },
            text = { Text(CallPermissions.rationale(need)) },
            confirmButton = {
                TextButton(onClick = {
                    asking = null
                    if (Manifest.permission.RECORD_AUDIO in need && wasAsked(context) && CallPermissions.blockedForever(context, Manifest.permission.RECORD_AUDIO)) {
                        deniedOp = op
                    } else {
                        markAsked(context)
                        waitingFor = op
                        launcher.launch(need.toTypedArray())
                    }
                }, modifier = Modifier.testTag("call_perm_continue")) { Text(tr("Continue", "অনুমতি দিন")) }
            },
            dismissButton = {
                TextButton(onClick = {
                    asking = null
                    // অনুমতি না দিলে: যুক্ত হওয়ার পর্দা বন্ধ; আসা কল বাজতে থাকে (কাটুন চাপা যায়)
                    if (op is CallOp.Join && !CallManager.state.value.live) onClose()
                }) { Text(tr("Cancel", "বাতিল")) }
            },
        )
    }
    // অন্যজন ভিডিও চালু করতে চাইছেন
    if (s.upgradeAsked && s.live) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(tr("Turn on video?", "ভিডিও চালু করবেন?")) },
            text = { Text(tr("${displayName(s, locked)} wants to switch this call to video.", "${displayName(s, locked)} কলটা ভিডিওতে নিতে চান।")) },
            confirmButton = { TextButton(onClick = { request(CallOp.UpgradeAccept) }, modifier = Modifier.testTag("call_upgrade_accept")) { Text(tr("Turn on video", "ভিডিও চালু করুন")) } },
            dismissButton = { TextButton(onClick = { CallManager.answerUpgrade(false, false) }, modifier = Modifier.testTag("call_upgrade_decline")) { Text(tr("Not now", "এখন না")) } },
        )
    }
    deniedOp?.let { op ->
        val camOnly = op == CallOp.Camera || op == CallOp.Upgrade || op == CallOp.UpgradeAccept
        AlertDialog(
            onDismissRequest = { deniedOp = null },
            title = { Text(if (camOnly) tr("Camera permission needed", "ক্যামেরার অনুমতি লাগবে") else tr("Microphone permission needed", "মাইক্রোফোনের অনুমতি লাগবে")) },
            text = {
                Text(
                    if (camOnly) tr("Turn on the camera permission in the app's settings to show your video.", "ভিডিও দেখাতে অ্যাপের সেটিংসে ক্যামেরার অনুমতি চালু করুন।")
                    else tr("Calls need the microphone. Turn it on in the app's settings.", "কলের জন্য মাইক্রোফোন লাগে। অ্যাপের সেটিংসে অনুমতি চালু করুন।"),
                )
            },
            confirmButton = { TextButton(onClick = { deniedOp = null; CallPermissions.openAppSettings(context) }) { Text(tr("Open settings", "সেটিংস খুলুন")) } },
            dismissButton = {
                TextButton(onClick = {
                    deniedOp = null
                    if (op is CallOp.Join && !CallManager.state.value.live) onClose()
                }) { Text(tr("Close", "বন্ধ")) }
            },
        )
    }
}

@Composable
private fun Avatar(name: String, photo: String, size: Int) {
    val img = remember(photo) { Photo.decodeImage(photo) }
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(CallColors.panel),
        contentAlignment = Alignment.Center,
    ) {
        if (img != null) {
            Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text(name.trim().take(1).ifBlank { "?" }, style = MaterialTheme.typography.displaySmall, color = CallColors.text, fontWeight = FontWeight.Bold)
        }
    }
}

/** দেখানোর নাম: লক স্ক্রিনে শুধু প্রথম নাম */
internal fun displayName(s: CallState, locked: Boolean): String {
    val n = if (locked) CallMachine.firstName(s.peerName) else s.peerName
    return n.ifBlank { tr("Lifolo user", "Lifolo ব্যবহারকারী") }
}

@Composable
private fun PeerHeader(s: CallState, status: String, avatar: Boolean, locked: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(top = 48.dp, start = 24.dp, end = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (avatar) {
            // লক স্ক্রিনে ছবিও নয়
            Avatar(displayName(s, locked), if (locked) "" else s.peerPhoto, 112)
            Spacer(Modifier.height(18.dp))
        }
        Text(
            displayName(s, locked),
            style = MaterialTheme.typography.headlineSmall, color = CallColors.text, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(status, style = MaterialTheme.typography.bodyLarge, color = CallColors.dim, textAlign = TextAlign.Center, modifier = Modifier.testTag("call_status"))
    }
}

@Composable
private fun RoundButton(icon: Int, label: String, bg: Color, onClick: () -> Unit, tag: String, big: Boolean = false, active: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(if (big) 84.dp else 68.dp)) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = if (active) Color.White else bg,
            modifier = Modifier.size(if (big) 68.dp else 56.dp).testTag(tag),
        ) {
            Box(contentAlignment = Alignment.Center) {
                AppIcon(icon, label, Modifier.size(if (big) 32.dp else 26.dp), if (active) Color.Black else Color.White)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = CallColors.dim, textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Composable
private fun IncomingCall(s: CallState, locked: Boolean, onAccept: () -> Unit, onAcceptAudio: () -> Unit, onDecline: () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        PeerHeader(
            s,
            if (locked) CallNotifications.lockText()
            else (if (s.video) tr("Video call in Lifolo", "Lifolo-তে ভিডিও কল") else tr("Audio call in Lifolo", "Lifolo-তে অডিও কল")) +
                CallCtx.label(s.ctx).takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty(),
            avatar = true,
            locked = locked,
        )
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth().padding(bottom = 56.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
            RoundButton(R.drawable.ic_call_end, tr("Decline", "কাটুন"), CallColors.red, onDecline, "call_decline", big = true)
            if (s.video) RoundButton(R.drawable.ic_phone_in_talk, tr("Audio only", "শুধু অডিও"), CallColors.panel, onAcceptAudio, "call_accept_audio", big = true)
            RoundButton(if (s.video) R.drawable.ic_videocam else R.drawable.ic_call, tr("Answer", "ধরুন"), CallColors.green, onAccept, "call_accept", big = true)
        }
    }
}

@Composable
private fun EndedCall(s: CallState, locked: Boolean, onStay: () -> Unit, onClose: () -> Unit) {
    var blocking by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<String?>(null) }
    val endedAt = remember(s.callId) { System.currentTimeMillis() }
    Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        PeerHeader(s, CallMachine.endText(s), avatar = true, locked = locked)
        val dur = s.connectedAt?.let { (endedAt - it) / 1000 }
        if (dur != null && s.endReason in setOf(EndReason.HANGUP, EndReason.REMOTE_ENDED)) {
            Spacer(Modifier.height(8.dp))
            Text(CallMachine.duration(dur), color = CallColors.dim, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.weight(1f))
        done?.let {
            Text(it, color = CallColors.text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(16.dp))
        }
        // কলকারী/কল ধরা মানুষ পরিচিত (ভুল মানুষ, হয়রানি): ব্লক/রিপোর্ট — লক স্ক্রিনে নয়
        if (!locked && s.callId.isNotBlank() && done == null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                RoundButton(R.drawable.ic_close, tr("Block", "ব্লক করুন"), CallColors.panel, { onStay(); blocking = true }, "call_block")
                RoundButton(R.drawable.ic_flag, tr("Report", "রিপোর্ট করুন"), CallColors.panel, { onStay(); reporting = true }, "call_report")
                RoundButton(R.drawable.ic_check_circle, tr("Close", "বন্ধ"), CallColors.panel, onClose, "call_close")
            }
        } else if (done != null) {
            RoundButton(R.drawable.ic_check_circle, tr("Close", "বন্ধ"), CallColors.panel, onClose, "call_close")
        }
        Spacer(Modifier.height(40.dp))
    }
    if (blocking) CallBlockDialog(s.peerUid, s.peerName, s.callId, onDismiss = { blocking = false }) {
        blocking = false
        done = tr("Blocked. They can't call you in the app.", "ব্লক করা হয়েছে। উনি আর অ্যাপে কল করতে পারবেন না।")
    }
    if (reporting) CallReportDialog(s.callId, onDismiss = { reporting = false }) {
        reporting = false
        done = tr("Reported. The team will look into it.", "রিপোর্ট পাঠানো হয়েছে। টিম দেখবে।")
    }
}

@Composable
private fun InCall(s: CallState, locked: Boolean, onCamera: (Boolean) -> Unit, onUpgrade: () -> Unit) {
    val remote by CallManager.remoteVideo.collectAsState()
    val local by CallManager.localVideo.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s.connectedAt) {
        while (s.connectedAt != null) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    // অপেক্ষার ঘরে রোগীর সিরিয়াল ("আপনার আগে ২ জন") — রোগী না হলে (ডাক্তার) সার্ভার দেয় না, কিছু দেখায় না
    var queueText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(s.phase == CallPhase.WAITING, s.appointmentId) {
        if (s.phase != CallPhase.WAITING || !s.isAppointment) { queueText = null; return@LaunchedEffect }
        while (true) {
            runCatching { QueueApi.patient(s.appointmentId) }.onSuccess { q ->
                queueText = if (q.live && q.state == "waiting" && q.ahead > 0) tr("${q.ahead} before you", "আপনার আগে ${num(q.ahead)} জন")
                else if (q.live && q.state == "now") tr("It's your turn", "এখন আপনার পালা") else null
            }.onFailure { queueText = null }
            delay(20_000)
        }
    }

    val bigRemote = remote != null && s.remoteVideoOn
    val bigLocal = !bigRemote && local != null && s.camOn
    Box(Modifier.fillMaxSize()) {
        // বড় ছবি: অন্যজনের ভিডিও, না থাকলে নিজের ক্যামেরা, না থাকলে নাম-ছবি
        val room = CallManager.currentRoom()
        if (bigRemote && room != null) key(room, remote) { VideoView(remote!!, mirror = false, Modifier.fillMaxSize()) }
        else if (bigLocal && room != null) key(room, local) { VideoView(local!!, mirror = s.frontCamera, Modifier.fillMaxSize()) }

        val status = when (s.phase) {
            CallPhase.OUTGOING -> if (s.connectedAt == null) tr("Ringing…", "রিং হচ্ছে…") else tr("Connecting…", "যুক্ত হচ্ছে…")
            CallPhase.CONNECTING -> tr("Connecting…", "যুক্ত হচ্ছে…")
            CallPhase.WAITING -> if (s.isAppointment) {
                if (s.peerName.isNotBlank()) tr("Waiting for ${s.peerName} to join", "${s.peerName} এখনো যুক্ত হননি — অপেক্ষা করুন")
                else tr("Waiting for the other person to join", "অন্যজন এখনো যুক্ত হননি — অপেক্ষা করুন")
            } else tr("Waiting…", "অপেক্ষা…")
            CallPhase.RECONNECTING -> tr("Reconnecting…", "আবার যুক্ত হচ্ছে…")
            else -> s.connectedAt?.let { CallMachine.duration((now - it).coerceAtLeast(0) / 1000) } ?: ""
        }
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            PeerHeader(s, status, avatar = !bigRemote && !bigLocal, locked = locked)
            if (s.phase == CallPhase.WAITING) {
                Spacer(Modifier.height(16.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = CallColors.text, strokeWidth = 3.dp, modifier = Modifier.size(32.dp)) }
                queueText?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = CallColors.text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                }
            }
            // দুর্বল নেটওয়ার্ক / শুধু অডিও / আবার যুক্ত হচ্ছে
            val upErr by CallManager.upgradeError.collectAsState()
            LaunchedEffect(s.upgradeDeclined, upErr) {
                if (s.upgradeDeclined || upErr != null) {
                    delay(5000)
                    CallManager.upgradeError.value = null
                    if (CallManager.state.value.upgradeDeclined) CallManager.dismissUpgradeNote()
                }
            }
            val banner = when {
                s.phase == CallPhase.RECONNECTING -> tr("Connection lost — reconnecting…", "সংযোগ কেটে গেছে — আবার যুক্ত হচ্ছে…")
                upErr != null -> upErr
                s.upgradeDeclined -> tr("They didn't turn on video", "উনি ভিডিও চালু করেননি")
                s.upgradePending -> tr("Asked to turn on video — waiting", "ভিডিও চালু করতে অনুরোধ পাঠানো হয়েছে — অপেক্ষা")
                s.audioFallback -> tr("Weak network — video turned off, audio only", "দুর্বল নেটওয়ার্ক — ভিডিও বন্ধ, শুধু অডিও")
                s.poorNetwork -> tr("Weak network", "দুর্বল নেটওয়ার্ক")
                else -> null
            }
            if (banner != null) {
                Spacer(Modifier.height(12.dp))
                Surface(color = CallColors.warn, shape = RoundedCornerShape(12.dp), modifier = Modifier.align(Alignment.CenterHorizontally).testTag("call_banner")) {
                    Text(banner, color = Color.Black, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            Controls(s, onCamera, onUpgrade)
        }
        // ছোট ছবি: নিজের ক্যামেরা (অন্যজনের ভিডিও বড় দেখালে)
        if (bigRemote && local != null && s.camOn && room != null) {
            Box(
                Modifier.safeDrawingPadding().padding(top = 16.dp, end = 16.dp).align(Alignment.TopEnd)
                    .size(width = 104.dp, height = 156.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, CallColors.dim, RoundedCornerShape(14.dp)),
            ) {
                key(room, local) { VideoView(local!!, mirror = s.frontCamera, Modifier.fillMaxSize()) }
            }
        }
    }
}

@Composable
private fun Controls(s: CallState, onCamera: (Boolean) -> Unit, onUpgrade: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
            RoundButton(
                if (s.micOn) R.drawable.ic_mic else R.drawable.ic_mic_off,
                if (s.micOn) tr("Mute", "মিউট") else tr("Unmute", "মিউট বন্ধ"),
                CallColors.panel, { CallManager.setMic(!s.micOn) }, "call_mute", active = !s.micOn,
            )
            val routeIcon = when (s.audioRoute) { "bluetooth", "wired" -> R.drawable.ic_headset; else -> R.drawable.ic_volume_up }
            val routeLabel = when (s.audioRoute) {
                "bluetooth" -> tr("Bluetooth", "ব্লুটুথ")
                "wired" -> tr("Headset", "হেডসেট")
                else -> tr("Speaker", "স্পিকার")
            }
            RoundButton(routeIcon, routeLabel, CallColors.panel, { CallManager.setSpeaker(!s.speakerOn) }, "call_speaker", active = s.speakerOn)
            if (s.video) {
                RoundButton(
                    if (s.camOn) R.drawable.ic_videocam else R.drawable.ic_videocam_off,
                    if (s.camOn) tr("Camera off", "ক্যামেরা বন্ধ") else tr("Camera", "ক্যামেরা"),
                    CallColors.panel, { onCamera(!s.camOn) }, "call_camera", active = s.camOn,
                )
            } else if (s.phase == CallPhase.ACTIVE) {
                // অডিও কল: ভিডিও শুধু দুজনের সম্মতিতে (অন্যজন রাজি হলে দুজনেরই নতুন টোকেন)
                RoundButton(
                    R.drawable.ic_videocam,
                    if (s.upgradePending) tr("Video asked", "অনুরোধ গেছে") else tr("Ask for video", "ভিডিও চালু করতে অনুরোধ"),
                    CallColors.panel, { if (!s.upgradePending) onUpgrade() }, "call_upgrade", active = s.upgradePending,
                )
            }
            if (s.camOn) RoundButton(R.drawable.ic_sync, tr("Flip", "ক্যামেরা ঘোরান"), CallColors.panel, { CallManager.switchCamera() }, "call_flip")
        }
        Spacer(Modifier.height(22.dp))
        RoundButton(R.drawable.ic_call_end, if (s.isAppointment) tr("Leave", "বের হোন") else tr("End", "কেটে দিন"), CallColors.red, { CallManager.hangup() }, "call_hangup", big = true)
    }
}

/** LiveKit-এর ভিডিও (TextureView — ছোট ছবি বড়টার উপরে ঠিকমতো বসে) */
@Composable
private fun VideoView(track: VideoTrack, mirror: Boolean, modifier: Modifier) {
    val room = CallManager.currentRoom() ?: return
    val holder = remember { mutableStateOf<TextureViewRenderer?>(null) }
    AndroidView(
        factory = { c ->
            TextureViewRenderer(c).also { v ->
                room.initVideoRenderer(v)
                v.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                v.setMirror(mirror)
                holder.value = v
            }
        },
        update = { v -> v.setMirror(mirror) },
        modifier = modifier,
    )
    val v = holder.value
    DisposableEffect(track, v) {
        if (v != null) runCatching { track.addRenderer(v) }
        onDispose { if (v != null) runCatching { track.removeRenderer(v) } }
    }
    DisposableEffect(Unit) {
        onDispose { holder.value?.let { runCatching { it.release() } } }
    }
}
