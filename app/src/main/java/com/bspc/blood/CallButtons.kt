package com.bspc.blood

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

/** বাটনের গুরুত্ব: EQUAL = ফোনের বাটনের সমান; SECONDARY = ছোট (অ্যাম্বুলেন্স, জরুরি রক্ত — ফোনই বড়); ICONS = শুধু আইকন (সারির পাশে) */
enum class CallEmphasis { EQUAL, SECONDARY, ICONS }

/** কলের অনুমতি — চাওয়া হয় কল করার/ধরার মুহূর্তে, আগে বাংলায় কারণ */
object CallPermissions {
    fun granted(context: Context, p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    /** ব্লুটুথ হেডসেট লাগানো আছে কি না (অনুমতি ছাড়াই জানা যায়) — থাকলে তবেই ব্লুটুথের অনুমতি চাওয়া */
    private fun bluetoothAudio(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 23) return false
        val am = context.getSystemService(AudioManager::class.java) ?: return false
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        }
    }

    /** যেগুলো এখনো দেওয়া হয়নি */
    fun missing(context: Context, video: Boolean): List<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (video) add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 31 && bluetoothAudio(context)) add(Manifest.permission.BLUETOOTH_CONNECT)
        // কল চলার নোটিফিকেশন (Android ১৩+)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.filter { !granted(context, it) }

    /** কারণের লেখা: কোন কোন অনুমতি কেন */
    fun rationale(perms: List<String>): String = buildList {
        if (Manifest.permission.RECORD_AUDIO in perms) add(tr("Microphone — so the other person can hear you", "মাইক্রোফোন — যাতে অন্যজন আপনার কথা শুনতে পান"))
        if (Manifest.permission.CAMERA in perms) add(tr("Camera — so they can see you on video", "ক্যামেরা — ভিডিও কলে যাতে আপনাকে দেখতে পান"))
        if (Manifest.permission.BLUETOOTH_CONNECT in perms) add(tr("Nearby devices — to talk through your Bluetooth headset", "কাছের ডিভাইস — ব্লুটুথ হেডসেটে কথা বলতে"))
        if (Manifest.permission.POST_NOTIFICATIONS in perms) add(tr("Notifications — to show the call while you use other apps", "নোটিফিকেশন — অন্য অ্যাপে গেলেও কল চলছে দেখাতে"))
    }.joinToString("\n") { "• $it" }

    /** মাইকের অনুমতি একেবারে বন্ধ ("আর জিজ্ঞেস করবেন না") — অ্যাপের সেটিংস থেকে দিতে হবে */
    fun blockedForever(context: Context, p: String): Boolean {
        val a = context.findActivity() ?: return false
        return !granted(context, p) && !a.shouldShowRequestPermissionRationale(p)
    }

    fun openAppSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

internal fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** কলের পর্দা খোলা (চলমান কল দেখানো) */
fun showCallScreen(context: Context) {
    runCatching {
        context.startActivity(Intent(context, CallActivity::class.java).setAction(CallIntents.ACTION_SHOW).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * যোগাযোগের এক সারি — সব জায়গায় একই (মালিকের সিদ্ধান্ত): ফোন · অডিও কল · ভিডিও কল · মেসেজ।
 * - ফোন: সাধারণ ফোনের ডায়াল (আগের মতো); জরুরিতে (অ্যাম্বুলেন্স, জরুরি রক্ত) PRIMARY_PHONE — ফোনই বড়।
 * - অডিও/ভিডিও কল: অ্যাপে কল। সার্ভার কল বন্ধ রাখলে (call_config) বা এই সূত্রে কল না দিলে (call_not_allowed, call_blocked)
 *   দেখায় না। চাপলে: অনুমতি (বাংলায় কারণ) → call_start → কলের পর্দা। উনি অ্যাপে কল বন্ধ রাখলে (call_off) বার্তা আর ফোনে কল।
 * - মেসেজ: onMessage দিলে তবেই (অ্যাপের মেসেজ — chat এজেন্ট openChat(ctx, refId, peerUid) দেয়)।
 *
 * @param refId রক্তের রিকোয়েস্ট / অ্যাপয়েন্টমেন্ট / অ্যাম্বুলেন্স ডাকা / অর্ডারের id
 * @param peerUid কার সাথে (একাধিক মানুষ থাকলে, যেমন রক্তপ্রার্থী → এক ডোনার); null হলে সার্ভার ঠিক করে
 * @param phone ফোনের নম্বর (খালি/null = ফোনের বাটন নেই; call_off-এর পর এটাই প্রস্তাব)
 * @param onPhone ফোনের বাটনের নিজের কাজ (যেমন অ্যাম্বুলেন্সে ফোন গোনা); null = সাধারণ ডায়াল
 * @param appAllowed অ্যাপে যোগাযোগের সম্পর্ক হয়েছে কি না (যেমন ডোনার → রক্তপ্রার্থী: নিশ্চিত হলে) — না হলে শুধু ফোন
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ContactActions(
    ctx: String,
    refId: String,
    peerUid: String? = null,
    peerName: String = "",
    phone: String? = null,
    style: ContactStyle = ContactStyle.ROW,
    modifier: Modifier = Modifier,
    onPhone: (() -> Unit)? = null,
    phoneLabel: String? = null,
    onMessage: (() -> Unit)? = null,
    appAllowed: Boolean = true,
    showPhone: Boolean = true,
) {
    LaunchedEffect(Unit) { CallAvailability.refresh() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // সার্ভারের হ্যাঁ/না (contact_can) — এক পাতার সব সারি একসাথে একবারে জিজ্ঞেস হয়
    val key = remember(ctx, refId, peerUid) { ContactKey(ctx, refId, peerUid?.ifBlank { null }) }
    val wantApp = appAllowed && refId.isNotBlank() && ctx in CallCtx.ALL && !LoginGate.isGuest
    LaunchedEffect(key, wantApp, ContactCan.version.intValue) { if (wantApp) ContactCan.request(key) }
    val flags = if (wantApp) ContactCan.flags[key] else null
    var unblocking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<Boolean?>(null) }
    var failure by remember { mutableStateOf<Pair<CallFailure, String>?>(null) }
    var asking by remember { mutableStateOf<Pair<Boolean, List<String>>?>(null) }
    var denied by remember { mutableStateOf(false) }
    var pendingVideo by remember { mutableStateOf(false) }

    // ফিচার সুইচ: বন্ধ থাকলে অ্যাপে কলের বাটন নেই (সার্ভারের contact_can-ও দুজনের সুইচ দেখে)
    val audioFeature = rememberFeatureOn(CallFeatures.AUDIO)
    val videoFeature = rememberFeatureOn(CallFeatures.VIDEO)
    val callsLive = audioFeature && CallAvailability.enabled.value != false && !CallAvailability.isBlocked(ctx, refId)
    val callsOn = flags?.canCall == true && callsLive
    val videoOn = callsOn && videoFeature && flags?.canVideo == true
    val msgOn = onMessage != null && flags?.canMessage == true
    // আমি এঁর সাথে যোগাযোগ বন্ধ রেখেছি: "আবার চালু করুন"
    val blockedByMe = flags?.blocked == true && audioFeature
    val tel = phone?.takeIf { it.isNotBlank() }
    val phoneOn = showPhone && (tel != null || onPhone != null)
    val dial: () -> Unit = { if (onPhone != null) onPhone() else if (tel != null) callPhone(context, tel) }

    fun start(video: Boolean) {
        if (busy != null) return
        // আগের কল চললে সেটাই দেখানো
        if (CallManager.state.value.live) { showCallScreen(context); return }
        busy = video
        scope.launch {
            runCatching { CallsApi.start(ctx, refId, peerUid, video) }
                .onSuccess { j ->
                    CallManager.startOutgoing(context, j, ctx, refId, j.video ?: video, peerName)
                    showCallScreen(context)
                }
                .onFailure { e ->
                    val f = CallErrors.of(e)
                    CallAvailability.onFailure(f, ctx, refId)
                    if (f == CallFailure.NOT_ALLOWED || f == CallFailure.BLOCKED) ContactCan.invalidateAll()
                    failure = f to CallErrors.message(f, e.message)
                }
            busy = null
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val micOk = res[Manifest.permission.RECORD_AUDIO] ?: CallPermissions.granted(context, Manifest.permission.RECORD_AUDIO)
        if (micOk) start(pendingVideo) else denied = true
    }

    fun tap(video: Boolean) {
        val need = CallPermissions.missing(context, video)
        if (need.isEmpty()) start(video) else asking = video to need
    }

    fun unblock() {
        val uid = flags?.peerUid?.ifBlank { null } ?: peerUid ?: return
        unblocking = true
        scope.launch {
            runCatching { CallsApi.unblock(uid) }.onSuccess { ContactCan.invalidateAll() }
            unblocking = false
        }
    }

    if (!phoneOn && !callsOn && !msgOn && !blockedByMe) return
    val audioLabel = tr("Audio call", "অডিও কল")
    val videoLabel = tr("Video call", "ভিডিও কল")
    val msgLabel = tr("Message", "মেসেজ")
    val telLabel = phoneLabel ?: tr("Call", "ফোন")
    val unblockLabel = tr("Allow contact again", "যোগাযোগ আবার চালু করুন")
    val cs = MaterialTheme.colorScheme
    when (style) {
        ContactStyle.ICONS -> Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            if (phoneOn) IconButton(onClick = dial, modifier = Modifier.testTag("contact_phone")) { AppIcon(R.drawable.ic_call, telLabel, tint = cs.primary) }
            if (callsOn) {
                IconButton(onClick = { tap(false) }, enabled = busy == null, modifier = Modifier.testTag("app_call_audio")) {
                    if (busy == false) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else AppIcon(R.drawable.ic_phone_in_talk, audioLabel, tint = cs.primary)
                }
                if (videoOn) IconButton(onClick = { tap(true) }, enabled = busy == null, modifier = Modifier.testTag("app_call_video")) {
                    if (busy == true) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else AppIcon(R.drawable.ic_videocam, videoLabel, tint = cs.primary)
                }
            }
            if (msgOn) IconButton(onClick = { onMessage?.invoke() }, modifier = Modifier.testTag("contact_message")) { AppIcon(R.drawable.ic_chat, msgLabel, tint = cs.primary) }
        }
        ContactStyle.CHIPS -> FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (phoneOn) SmallAction(telLabel, R.drawable.ic_call, dial, Modifier.testTag("contact_phone"))
            if (callsOn) {
                SmallAction(audioLabel, R.drawable.ic_phone_in_talk, { tap(false) }, Modifier.testTag("app_call_audio"), busy = busy == false, enabled = busy == null)
                if (videoOn) SmallAction(videoLabel, R.drawable.ic_videocam, { tap(true) }, Modifier.testTag("app_call_video"), busy = busy == true, enabled = busy == null)
            }
            if (msgOn) SmallAction(msgLabel, R.drawable.ic_chat, { onMessage?.invoke() }, Modifier.testTag("contact_message"))
            if (blockedByMe) SmallAction(unblockLabel, null, { unblock() }, Modifier.testTag("contact_unblock"), busy = unblocking, enabled = !unblocking)
        }
        // জরুরি: ফোনের বড় বাটন আগে, অ্যাপের যোগাযোগ নিচে ছোট
        ContactStyle.PRIMARY_PHONE -> Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (phoneOn) Button(onClick = dial, modifier = Modifier.fillMaxWidth().height(52.dp).testTag("contact_phone")) { ButtonContent(false, telLabel, R.drawable.ic_call) }
            if (callsOn || msgOn || blockedByMe) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (blockedByMe) SmallAction(unblockLabel, null, { unblock() }, Modifier.testTag("contact_unblock"), busy = unblocking, enabled = !unblocking)
                if (callsOn) {
                    SmallAction(audioLabel, R.drawable.ic_phone_in_talk, { tap(false) }, Modifier.testTag("app_call_audio"), busy = busy == false, enabled = busy == null)
                    if (videoOn) SmallAction(videoLabel, R.drawable.ic_videocam, { tap(true) }, Modifier.testTag("app_call_video"), busy = busy == true, enabled = busy == null)
                }
                if (msgOn) SmallAction(msgLabel, R.drawable.ic_chat, { onMessage?.invoke() }, Modifier.testTag("contact_message"))
            }
        }
        // সব সমান
        ContactStyle.ROW -> FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (phoneOn) Button(onClick = dial, modifier = Modifier.testTag("contact_phone")) { ButtonContent(false, telLabel, R.drawable.ic_call) }
            if (callsOn) {
                OutlinedButton(onClick = { tap(false) }, enabled = busy == null, modifier = Modifier.testTag("app_call_audio")) {
                    ButtonContent(busy == false, audioLabel, R.drawable.ic_phone_in_talk)
                }
                if (videoOn) OutlinedButton(onClick = { tap(true) }, enabled = busy == null, modifier = Modifier.testTag("app_call_video")) {
                    ButtonContent(busy == true, videoLabel, R.drawable.ic_videocam)
                }
            }
            if (msgOn) OutlinedButton(onClick = { onMessage?.invoke() }, modifier = Modifier.testTag("contact_message")) {
                ButtonContent(false, msgLabel, R.drawable.ic_chat)
            }
            if (blockedByMe) TextButton(onClick = { unblock() }, enabled = !unblocking, modifier = Modifier.testTag("contact_unblock")) {
                ButtonContent(unblocking, unblockLabel)
            }
        }
    }

    asking?.let { (video, need) ->
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text(if (video) tr("Allow for the video call", "ভিডিও কলের জন্য অনুমতি দিন") else tr("Allow for the audio call", "অডিও কলের জন্য অনুমতি দিন")) },
            text = { Text(CallPermissions.rationale(need)) },
            confirmButton = {
                TextButton(onClick = {
                    asking = null
                    pendingVideo = video
                    if (CallPermissions.blockedForever(context, Manifest.permission.RECORD_AUDIO) && Manifest.permission.RECORD_AUDIO in need && wasAsked(context)) {
                        denied = true
                    } else {
                        markAsked(context)
                        launcher.launch(need.toTypedArray())
                    }
                }) { Text(tr("Continue", "অনুমতি দিন")) }
            },
            dismissButton = { TextButton(onClick = { asking = null }) { Text(tr("Cancel", "বাতিল")) } },
        )
    }
    if (denied) {
        AlertDialog(
            onDismissRequest = { denied = false },
            title = { Text(tr("Microphone permission needed", "মাইক্রোফোনের অনুমতি লাগবে")) },
            text = { Text(tr("App calls need the microphone. Turn it on in the app's settings, or call their phone.", "অ্যাপে কলের জন্য মাইক্রোফোন লাগে। অ্যাপের সেটিংসে অনুমতি চালু করুন, অথবা ফোনে কল করুন।")) },
            confirmButton = { TextButton(onClick = { denied = false; CallPermissions.openAppSettings(context) }) { Text(tr("Open settings", "সেটিংস খুলুন")) } },
            dismissButton = {
                Row {
                    if (tel != null) TextButton(onClick = { denied = false; callPhone(context, tel) }) { Text(tr("Call phone", "ফোনে কল")) }
                    TextButton(onClick = { denied = false }) { Text(tr("Close", "বন্ধ")) }
                }
            },
        )
    }
    failure?.let { (f, msg) ->
        val offer = tel != null && CallErrors.offersPhone(f)
        AlertDialog(
            onDismissRequest = { failure = null },
            title = { Text(if (f == CallFailure.OFF) tr("App calls are off", "অ্যাপে কল বন্ধ") else if (f == CallFailure.RATE_LIMIT) tr("Too many calls", "অনেক বেশি কল") else tr("Call not placed", "কল হয়নি")) },
            text = { Text(msg) },
            confirmButton = {
                if (offer && tel != null) {
                    TextButton(onClick = { failure = null; callPhone(context, tel) }, modifier = Modifier.testTag("app_call_phone_fallback")) {
                        ButtonContent(false, tr("Call ", "ফোনে কল: ") + num(tel), R.drawable.ic_call)
                    }
                } else {
                    TextButton(onClick = { failure = null }) { Text(tr("OK", "ঠিক আছে")) }
                }
            },
            dismissButton = if (offer) ({ TextButton(onClick = { failure = null }) { Text(tr("Close", "বন্ধ")) } }) else null,
        )
    }
}

/**
 * contact_can-এর ফল (ctx+refId+peerUid → অডিও/ভিডিও/মেসেজ হ্যাঁ/না) — এক পাতার সব সারি ১২০ ms ধরে জমিয়ে একটা অনুরোধে
 * (≤ ৫০ করে)। ১ মিনিট মনে থাকে; কল/ব্লকে বদল হলে invalidateAll (সারিগুলো আবার জিজ্ঞেস করে)।
 */
object ContactCan {
    private const val TTL_MS = 60_000L
    val flags = androidx.compose.runtime.mutableStateMapOf<ContactKey, ContactFlags>()
    /** বাড়লে সব সারি আবার জিজ্ঞেস করে */
    val version = androidx.compose.runtime.mutableIntStateOf(0)
    private val fetchedAt = HashMap<ContactKey, Long>()
    private val pending = LinkedHashSet<ContactKey>()
    private var job: kotlinx.coroutines.Job? = null
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)

    fun request(k: ContactKey, now: Long = System.currentTimeMillis()) {
        val at = fetchedAt[k]
        if (at != null && now - at < TTL_MS) return
        pending += k
        if (job?.isActive == true) return
        job = scope.launch {
            kotlinx.coroutines.delay(120)
            while (pending.isNotEmpty()) {
                val batch = pending.take(50)
                pending.removeAll(batch.toSet())
                val t = System.currentTimeMillis()
                runCatching { CallsApi.contactCan(batch) }
                    .onSuccess { res -> batch.zip(res).forEach { (key, f) -> flags[key] = f; fetchedAt[key] = t } }
                    // পুরোনো সার্ভার (কাজটা চেনে না) বা না: অ্যাপের বাটন নয়; ইন্টারনেট না থাকলে পরে আবার
                    .onFailure { e -> if (e is ServerError) batch.forEach { key -> flags[key] = ContactFlags.NONE; fetchedAt[key] = t } }
            }
        }
    }

    fun invalidateAll() {
        fetchedAt.clear()
        version.intValue++
    }

    fun clear() {
        fetchedAt.clear()
        pending.clear()
        flags.clear()
        version.intValue++
    }
}

/** যোগাযোগের সারির রূপ */
enum class ContactStyle {
    /** সব সমান: ফোন (ভরাট) · অডিও কল · ভিডিও কল · মেসেজ */
    ROW,
    /** জরুরি (অ্যাম্বুলেন্স, জরুরি রক্ত): ফোনের বড় পুরো-চওড়া বাটন, নিচে ছোট অ্যাপের যোগাযোগ */
    PRIMARY_PHONE,
    /** ছোট চিপ (কাজের তালিকা, অর্ডারের সারি) */
    CHIPS,
    /** শুধু আইকন (নামের সারির ডানে) */
    ICONS,
}

/** শুধু অ্যাপে কল (ফোন নম্বর ছাড়া — যেমন কলের তালিকা থেকে ফিরতি কল) */
@Composable
fun AppCallButtons(
    ctx: String,
    refId: String,
    peerUid: String? = null,
    peerName: String = "",
    phone: String? = null,
    emphasis: CallEmphasis = CallEmphasis.EQUAL,
    modifier: Modifier = Modifier,
) = ContactActions(
    ctx, refId, peerUid, peerName, phone,
    when (emphasis) { CallEmphasis.EQUAL -> ContactStyle.ROW; CallEmphasis.SECONDARY -> ContactStyle.CHIPS; CallEmphasis.ICONS -> ContactStyle.ICONS },
    modifier, showPhone = false,
)

/** আগে একবার অনুমতি চাওয়া হয়েছে কি না (না চাইলে shouldShowRationale false আসে — তখন সেটিংসে পাঠানো ভুল); CallActivity-ও */
internal fun wasAsked(context: Context) = context.getSharedPreferences("calls", Context.MODE_PRIVATE).getBoolean("asked_mic", false)
internal fun markAsked(context: Context) = context.getSharedPreferences("calls", Context.MODE_PRIVATE).edit().putBoolean("asked_mic", true).apply()

/**
 * নোটিফিকেশনের সেটিংসে "অ্যাপে কল"-এর নিচে: Android ১৪+ এ লক স্ক্রিনে পুরো পর্দায় কল দেখানোর অনুমতি না থাকলে
 * সেটিংসে যাওয়ার সারি (না দিলে কল এলে শুধু উপরে নোটিফিকেশন ভাসে)।
 */
@Composable
fun CallsFullScreenRow() {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(CallNotifications.canFullScreen(context)) }
    // সেটিংস থেকে ফিরলে আবার দেখা
    @Suppress("DEPRECATION")
    val owner = LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) allowed = CallNotifications.canFullScreen(context)
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    if (allowed) return
    ListItem(
        headlineContent = { Text(tr("Show calls on the lock screen", "লক স্ক্রিনে কল দেখান")) },
        supportingContent = { Text(tr("Allow full-screen calls so you don't miss them", "পুরো পর্দায় কল দেখানোর অনুমতি দিন, যাতে কল মিস না হয়")) },
        leadingContent = { AppIcon(R.drawable.ic_warning, null, tint = MaterialTheme.colorScheme.error) },
        trailingContent = { AppIcon(R.drawable.ic_chevron_right, null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { CallNotifications.openFullScreenSettings(context) }.testTag("calls_fullscreen_permission"),
    )
}

/** প্রোফাইল থেকে "কল" (কলের তালিকা) — শুধু কল চালু থাকলে */
@Composable
fun CallsEntryVisible(): Boolean {
    LaunchedEffect(Unit) { CallAvailability.refresh() }
    return rememberFeatureOn(CallFeatures.AUDIO) && CallAvailability.enabled.value == true
}
