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
import androidx.compose.foundation.layout.Row
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
 * ফোনের বাটনের পাশে "অ্যাপে কল" আর "ভিডিও কল"। সার্ভার অ্যাপে কল বন্ধ রাখলে বা এই সূত্রে কল না দিলে (call_not_allowed)
 * কিছুই দেখায় না। চাপলে: অনুমতি (কারণসহ) → call_start → কলের পর্দা। উনি অ্যাপে কল বন্ধ রাখলে (call_off) বার্তা আর ফোন নম্বরে কল।
 *
 * @param refId রক্তের রিকোয়েস্ট / অ্যাপয়েন্টমেন্ট / অ্যাম্বুলেন্স ডাকা / অর্ডারের id
 * @param peerUid কাকে (একাধিক মানুষ থাকলে, যেমন রক্তপ্রার্থী → এক ডোনার); null হলে সার্ভার ঠিক করে
 * @param phone call_off-এর পর ফোনে কল করার নম্বর (জানা থাকলে)
 */
@Composable
fun AppCallButtons(
    ctx: String,
    refId: String,
    peerUid: String? = null,
    peerName: String = "",
    phone: String? = null,
    emphasis: CallEmphasis = CallEmphasis.EQUAL,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { CallAvailability.refresh() }
    if (!CallAvailability.shows(ctx, refId)) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<Boolean?>(null) }
    var failure by remember { mutableStateOf<Pair<CallFailure, String>?>(null) }
    var asking by remember { mutableStateOf<Pair<Boolean, List<String>>?>(null) }
    var denied by remember { mutableStateOf(false) }
    var pendingVideo by remember { mutableStateOf(false) }

    fun start(video: Boolean) {
        if (busy != null) return
        // আগের কল চললে সেটাই দেখানো
        if (CallManager.state.value.live) { showCallScreen(context); return }
        busy = video
        scope.launch {
            runCatching { CallsApi.start(ctx, refId, peerUid, video) }
                .onSuccess { j ->
                    CallManager.startOutgoing(context, j, ctx, refId, video, peerName)
                    showCallScreen(context)
                }
                .onFailure { e ->
                    val f = CallErrors.of(e)
                    CallAvailability.onFailure(f, ctx, refId)
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

    val audioLabel = tr("App call", "অ্যাপে কল")
    val videoLabel = tr("Video call", "ভিডিও কল")
    when (emphasis) {
        CallEmphasis.ICONS -> Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { tap(false) }, enabled = busy == null, modifier = Modifier.testTag("app_call_audio")) {
                if (busy == false) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else AppIcon(R.drawable.ic_phone_in_talk, audioLabel, tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = { tap(true) }, enabled = busy == null, modifier = Modifier.testTag("app_call_video")) {
                if (busy == true) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else AppIcon(R.drawable.ic_videocam, videoLabel, tint = MaterialTheme.colorScheme.primary)
            }
        }
        CallEmphasis.SECONDARY -> Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            SmallAction(audioLabel, R.drawable.ic_phone_in_talk, { tap(false) }, Modifier.testTag("app_call_audio"), busy = busy == false, enabled = busy == null)
            SmallAction(videoLabel, R.drawable.ic_videocam, { tap(true) }, Modifier.testTag("app_call_video"), busy = busy == true, enabled = busy == null)
        }
        CallEmphasis.EQUAL -> Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { tap(false) }, enabled = busy == null, modifier = Modifier.testTag("app_call_audio")) {
                ButtonContent(busy == false, audioLabel, R.drawable.ic_phone_in_talk)
            }
            OutlinedButton(onClick = { tap(true) }, enabled = busy == null, modifier = Modifier.testTag("app_call_video")) {
                ButtonContent(busy == true, videoLabel, R.drawable.ic_videocam)
            }
        }
    }

    asking?.let { (video, need) ->
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text(if (video) tr("Allow for the video call", "ভিডিও কলের জন্য অনুমতি দিন") else tr("Allow for the app call", "অ্যাপে কলের জন্য অনুমতি দিন")) },
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
                    if (!phone.isNullOrBlank()) TextButton(onClick = { denied = false; callPhone(context, phone) }) { Text(tr("Call phone", "ফোনে কল")) }
                    TextButton(onClick = { denied = false }) { Text(tr("Close", "বন্ধ")) }
                }
            },
        )
    }
    failure?.let { (f, msg) ->
        AlertDialog(
            onDismissRequest = { failure = null },
            title = { Text(if (f == CallFailure.OFF) tr("App calls are off", "অ্যাপে কল বন্ধ") else tr("Call not placed", "কল হয়নি")) },
            text = { Text(msg) },
            confirmButton = {
                if (!phone.isNullOrBlank() && CallErrors.offersPhone(f)) {
                    TextButton(onClick = { failure = null; callPhone(context, phone) }, modifier = Modifier.testTag("app_call_phone_fallback")) {
                        ButtonContent(false, tr("Call ", "ফোনে কল: ") + num(phone), R.drawable.ic_call)
                    }
                } else {
                    TextButton(onClick = { failure = null }) { Text(tr("OK", "ঠিক আছে")) }
                }
            },
            dismissButton = if (!phone.isNullOrBlank() && CallErrors.offersPhone(f)) ({ TextButton(onClick = { failure = null }) { Text(tr("Close", "বন্ধ")) } }) else null,
        )
    }
}

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
    return CallAvailability.enabled.value == true
}
