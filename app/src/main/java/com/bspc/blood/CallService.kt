package com.bspc.blood

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * কলের নোটিফিকেশন:
 * - আসা কল: `cat_calls` চ্যানেল (রিংটোন, জোরালো), CallStyle (ধরুন/কাটুন), পুরো পর্দার intent (লক স্ক্রিনে CallActivity),
 *   রিংটোন বারবার (FLAG_INSISTENT), ৪৫ সেকেন্ডে নিজে সরে যায়। DND চালু থাকলে ফোনের নিয়মই মানা হয় (শব্দ/পুরো পর্দা ফোন ঠিক করে)।
 * - চলমান কল: foreground service-এর নোটিফিকেশন (শব্দ ছাড়া চ্যানেল), "কেটে দিন" বাটন।
 * - মিসড কল: সার্ভারের পুশ (call_missed) — Notifier.show, `cat_calls`-এ কিন্তু শব্দ ছাড়া।
 */
object CallNotifications {
    const val INCOMING_ID = 73_001
    const val ONGOING_ID = 73_002
    /** চলমান কলের চ্যানেল (শব্দ নেই) — নোটিফিকেশনের ধরন নয়, তাই cat_ নয় */
    const val ONGOING_CHANNEL = "calls_ongoing"

    /** cat_calls: রিংটোন আর কম্পন (অন্য ধরনের চ্যানেল Notifier.ensureChannels-এ) */
    fun callsChannel(context: Context): NotificationChannel? {
        if (Build.VERSION.SDK_INT < 26) return null
        return NotificationChannel(NotifyCats.channelId(NotifyCats.CALLS), NotifyCats.label(NotifyCats.CALLS), NotificationManager.IMPORTANCE_HIGH).apply {
            description = NotifyCats.description(NotifyCats.CALLS)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 800, 600, 800, 600)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
    }

    fun ensureOngoingChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(ONGOING_CHANNEL, tr("Ongoing call", "চলমান কল"), NotificationManager.IMPORTANCE_LOW).apply {
                description = tr("Shown while an app call is on", "অ্যাপে কল চলার সময়")
                setSound(null, null)
            },
        )
    }

    private fun canNotify(context: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Android ১৪+: লক স্ক্রিনে পুরো পর্দায় কল দেখানোর অনুমতি আছে কি না */
    fun canFullScreen(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        val nm = context.getSystemService(NotificationManager::class.java) ?: return true
        return nm.canUseFullScreenIntent()
    }

    /** সেই অনুমতির পাতা (Android ১৪+) */
    fun openFullScreenSettings(context: Context) {
        if (Build.VERSION.SDK_INT < 34) return
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { Notifier.openSystemSettings(context) }
    }

    private fun person(name: String, photo: String): Person {
        val b = Person.Builder().setName(name.ifBlank { tr("Lifolo user", "Lifolo ব্যবহারকারী") }).setImportant(true)
        if (photo.isNotBlank()) runCatching {
            val bytes = Base64.decode(photo, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { b.setIcon(IconCompat.createWithBitmap(it)) }
        }
        return b.build()
    }

    private fun activityIntent(context: Context, action: String, s: CallState?): PendingIntent {
        val i = Intent(context, CallActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (s != null) {
            i.putExtra(CallIntents.EXTRA_CALL_ID, s.callId).putExtra(CallIntents.EXTRA_VIDEO, s.video).putExtra(CallIntents.EXTRA_PEER_NAME, s.peerName)
                .putExtra(CallIntents.EXTRA_PEER_PHOTO, s.peerPhoto).putExtra(CallIntents.EXTRA_CTX, s.ctx).putExtra(CallIntents.EXTRA_REF_ID, s.refId)
        }
        return PendingIntent.getActivity(context, action.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun receiverIntent(context: Context, action: String, callId: String): PendingIntent {
        val i = Intent(context, CallActionReceiver::class.java).setAction(action).putExtra(CallIntents.EXTRA_CALL_ID, callId)
        return PendingIntent.getBroadcast(context, action.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** আসা কল */
    @SuppressLint("MissingPermission")
    fun showIncoming(context: Context, s: CallState) {
        if (!canNotify(context)) return
        val channel = NotifyCats.channelId(NotifyCats.CALLS)
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm?.getNotificationChannel(channel) == null) {
                AppPrefs.init(context)
                Notifier.ensureChannels(context)
            }
        }
        val full = activityIntent(context, CallIntents.ACTION_INCOMING, s)
        val answer = activityIntent(context, CallIntents.ACTION_ACCEPT, s)
        val decline = receiverIntent(context, CallIntents.ACTION_DECLINE, s.callId)
        val text = (if (s.video) tr("Video call in the app", "অ্যাপে ভিডিও কল") else tr("Audio call in the app", "অ্যাপে অডিও কল")) +
            CallCtx.label(s.ctx).takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle(s.peerName.ifBlank { APP_NAME })
            .setContentText(text)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person(s.peerName, s.peerPhoto), decline, answer).setIsVideo(s.video))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            // লক স্ক্রিনে শুধু প্রথম নাম + "লাইফলো কল" (কোন রিকোয়েস্ট/অ্যাপয়েন্টমেন্ট — খোলার পরে)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(lockScreenVersion(context, channel, s, full))
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(full)
            .setFullScreenIntent(full, true)
            .setTimeoutAfter(45_000)
            // Android ৭ ও আগে (চ্যানেল নেই): শব্দ এখানে
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), android.media.AudioManager.STREAM_RING)
            .setVibrate(longArrayOf(0, 800, 600, 800, 600))
            .build()
        n.flags = n.flags or Notification.FLAG_INSISTENT
        runCatching { NotificationManagerCompat.from(context).notify(INCOMING_ID, n) }
    }

    /** লক স্ক্রিনের রূপ: প্রথম নাম আর "লাইফলো কল" — আর কিছু নয় */
    private fun lockScreenVersion(context: Context, channel: String, s: CallState, tap: PendingIntent): Notification =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle(CallMachine.firstName(s.peerName).ifBlank { APP_NAME })
            .setContentText(lockText())
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(tap)
            .build()

    fun lockText() = tr("Lifolo call", "লাইফলো কল")

    fun cancelIncoming(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(INCOMING_ID) }
    }

    /** চলমান কলের নোটিফিকেশন (foreground service) */
    fun ongoing(context: Context, s: CallState): Notification {
        ensureOngoingChannel(context)
        val show = activityIntent(context, CallIntents.ACTION_SHOW, null)
        val hang = receiverIntent(context, CallIntents.ACTION_HANGUP, s.callId)
        val text = when (s.phase) {
            CallPhase.OUTGOING -> tr("Calling…", "কল যাচ্ছে…")
            CallPhase.CONNECTING -> tr("Connecting…", "যুক্ত হচ্ছে…")
            CallPhase.WAITING -> tr("Waiting for the other person", "অন্যজনের অপেক্ষায়")
            CallPhase.RECONNECTING -> tr("Reconnecting…", "আবার যুক্ত হচ্ছে…")
            else -> if (s.video) tr("Video call", "ভিডিও কল") else tr("Audio call", "অডিও কল")
        }
        val b = NotificationCompat.Builder(context, ONGOING_CHANNEL)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle(s.peerName.ifBlank { APP_NAME })
            .setContentText(text)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person(s.peerName, s.peerPhoto), hang).setIsVideo(s.video))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(show)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(lockScreenVersion(context, ONGOING_CHANNEL, s, show))
        s.connectedAt?.let { b.setWhen(it).setUsesChronometer(true).setShowWhen(true) }
        return b.build()
    }
}

/**
 * কল চলার সময়ের foreground service — অ্যাপ পেছনে গেলেও মাইক/ক্যামেরা চলে। ধরন: microphone (+ camera, ক্যামেরা চালু থাকলে)।
 * phoneCall ধরন নয়: সেটা শুধু ডিফল্ট ফোন অ্যাপ বা Telecom-এর self-managed ConnectionService-এর জন্য।
 */
class CallService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watch: Job? = null
    private var types = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        CallManager.init(this)
        val s = CallManager.state.value
        if (!s.usesMedia || !promote(s)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (watch == null) watch = scope.launch {
            CallManager.state.collect { st ->
                if (!st.usesMedia) {
                    ServiceCompat.stopForeground(this@CallService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    promote(st)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    /** foreground-এ তোলা / নোটিফিকেশন হালনাগাদ; ধরন বদলালে (ক্যামেরা চালু/বন্ধ) আবার startForeground */
    private fun promote(s: CallState): Boolean {
        if (!granted(Manifest.permission.RECORD_AUDIO)) return false
        var t = 0
        if (Build.VERSION.SDK_INT >= 30) {
            t = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (s.camOn && granted(Manifest.permission.CAMERA)) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        val n = CallNotifications.ongoing(this, s)
        return runCatching {
            if (t != types) {
                ServiceCompat.startForeground(this, CallNotifications.ONGOING_ID, n, t)
                types = t
            } else {
                NotificationManagerCompat.from(this).notify(CallNotifications.ONGOING_ID, n)
            }
            true
        }.getOrElse { false }
    }

    override fun onDestroy() {
        watch?.cancel()
        running = false
        super.onDestroy()
    }

    companion object {
        @Volatile private var running = false

        fun sync(context: Context) {
            if (running) return
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
            running = runCatching { ContextCompat.startForegroundService(context, Intent(context, CallService::class.java)); true }.getOrDefault(false)
        }

        fun stop(context: Context) {
            if (!running) return
            running = false
            runCatching { context.stopService(Intent(context, CallService::class.java)) }
        }
    }
}

/** নোটিফিকেশনের "কাটুন" (আসা কল) আর "কেটে দিন" (চলমান কল) */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        CallManager.init(context)
        val id = intent.getStringExtra(CallIntents.EXTRA_CALL_ID).orEmpty()
        val s = CallManager.state.value
        when (intent.action) {
            CallIntents.ACTION_DECLINE -> {
                CallNotifications.cancelIncoming(context)
                if (s.callId == id && s.phase == CallPhase.INCOMING) {
                    CallManager.decline()
                } else if (id.isNotBlank()) {
                    // প্রসেস নতুন (অ্যাপ বন্ধ ছিল): সরাসরি সার্ভারে
                    val pending = goAsync()
                    CoroutineScope(Dispatchers.IO).launch {
                        runCatching { CallsApi.decline(id) }
                        pending.finish()
                    }
                }
            }
            CallIntents.ACTION_HANGUP -> if (s.callId == id || id.isBlank()) CallManager.hangup()
        }
    }
}

/** পুশে আসা কলের খবর (BloodMessagingService) — true হলে সাধারণ নোটিফিকেশন আর নয় */
object CallPush {
    fun handle(context: Context, data: Map<String, String>): Boolean {
        val type = data["type"] ?: return false
        val callId = data["callId"].orEmpty()
        return when (type) {
            "call_incoming" -> {
                if (callId.isBlank()) return true
                AppPrefs.init(context)
                val video = data["video"].toBool()
                val name = data["caller"] ?: data["callerName"] ?: data["peerName"] ?: ""
                val photo = data["callerPhoto"] ?: data["photoThumb"] ?: ""
                val ok = CallManager.onIncoming(context, callId, video, name, photo, data["ctx"].orEmpty(), data["refId"].orEmpty(), data["callerUid"].orEmpty())
                if (ok) CallNotifications.showIncoming(context, CallManager.state.value)
                true
            }
            // অডিও কল → ভিডিও: অন্যজন চাইলেন / রাজি হলেন / রাজি হননি
            "call_upgrade" -> { CallManager.init(context); CallManager.onUpgradeAsked(callId); true }
            "call_upgraded" -> { CallManager.init(context); CallManager.onUpgraded(callId); true }
            "call_upgrade_declined" -> { CallManager.init(context); CallManager.onUpgradeDeclined(callId); true }
            "call_state", "call_cancelled" -> {
                val st = data["state"] ?: if (type == "call_cancelled") "cancelled" else ""
                CallManager.init(context)
                when (st) {
                    "upgrade_request" -> { CallManager.onUpgradeAsked(callId); return true }
                    "upgraded" -> { CallManager.onUpgraded(callId); return true }
                    "upgrade_declined" -> { CallManager.onUpgradeDeclined(callId); return true }
                }
                CallManager.onServerState(callId, st)
                // এই ফোনে আর বাজছে না (বা প্রসেস নতুন — অবস্থা জানা নেই): ঘণ্টার নোটিফিকেশন সরানো
                if (st != "ringing" && CallManager.state.value.phase != CallPhase.INCOMING) CallNotifications.cancelIncoming(context)
                true
            }
            "call_missed" -> {
                CallManager.init(context)
                CallManager.onServerState(callId, "missed")
                CallNotifications.cancelIncoming(context)
                // মিসড কলের সাধারণ নোটিফিকেশন (চাপলে কলের তালিকা) — Notifier.show দেখায়
                false
            }
            else -> false
        }
    }

    private fun String?.toBool() = this == "true" || this == "1"
}
