package com.bspc.blood

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

object Notifier {
    const val EXTRA_REQUEST_ID = "requestId"
    /** "request" (ডোনারের জন্য), "response" (রক্তপ্রার্থীর জন্য), "eligible" */
    const val EXTRA_TYPE = "type"
    const val EXTRA_QUESTION_ID = "questionId"
    /** "services" নোটিফিকেশনে কোন ব্যবসা */
    const val EXTRA_KIND = "kind"
    /** প্রেসক্রিপশনের নোটিফিকেশনে কোন প্রেসক্রিপশন */
    const val EXTRA_RX_ID = "rxId"
    /** কেয়ারগিভারকে এলে কোন রোগীর হয়ে */
    const val EXTRA_AS_UID = "asUid"
    /** অ্যাম্বুলেন্স ডাকার অনুরোধ (রোগী: অপেক্ষার পাতা) */
    const val EXTRA_REQ_ID = "reqId"

    /**
     * প্রতিটা নোটিফিকেশনের ধরনের (NotifyCats) আলাদা চ্যানেল — ফোনের সেটিংসেও আলাদা করে বন্ধ/শব্দ বদলানো যায়।
     * আবার ডাকলে শুধু নাম/বিবরণ বদলায় (ভাষা বদলালে); ব্যবহারকারীর বদলানো শব্দ/গুরুত্ব থাকে। আগের একমাত্র চ্যানেল
     * ("blood_requests") মুছে ফেলা হয়।
     */
    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannels(
            NotifyCats.ALL.map { cat ->
                NotificationChannel(
                    NotifyCats.channelId(cat),
                    NotifyCats.label(cat),
                    if (cat in NotifyCats.HIGH) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = NotifyCats.description(cat) }
            },
        )
        if (nm.getNotificationChannel(NotifyCats.OLD_CHANNEL_ID) != null) nm.deleteNotificationChannel(NotifyCats.OLD_CHANNEL_ID)
    }

    /** পুশ এলে তার চ্যানেল না থাকলে (অ্যাপ হালনাগাদের পর এখনো খোলা হয়নি) বানানো — ভাষা এই ফোনের পছন্দমতো */
    private fun ensureChannelFor(context: Context, channelId: String) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(channelId) == null) {
            AppPrefs.init(context)
            ensureChannels(context)
        }
    }

    /** ফোনের সেটিংসে এই অ্যাপের নোটিফিকেশনের পাতা (চ্যানেলগুলো); পুরোনো ফোনে অ্যাপের তথ্যের পাতা */
    fun openSystemSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= 26) {
            Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))
        }
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure {
                runCatching {
                    context.startActivity(
                        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
    }

    private fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * নোটিফিকেশন চাপলে কোন পাতা — সার্ভারের data-র এই চাবিগুলো intent-এ যায় (App-এর openFromData পড়ে)।
     * আগে শুধু কয়েকটা যেত (role যেত না — ডাক্তারের অ্যাপয়েন্টমেন্টের খবর রোগীর পাতা খুলত)।
     */
    val DATA_KEYS = listOf(
        EXTRA_TYPE, EXTRA_REQUEST_ID, EXTRA_QUESTION_ID, EXTRA_KIND, EXTRA_RX_ID, EXTRA_AS_UID, EXTRA_REQ_ID,
        "role", "sub", "ticketId", "emergencyId", "id", "labOrderId", "orderId", "findId", "visitId",
        // নতুন রসিদ (invoice_new) — সরাসরি সেই রসিদ খোলে
        "invoiceId",
    )

    /** পুশের data থেকে নোটিফিকেশনের intent-এর extras (title/body/forUid বাদ, খালি মান বাদ) */
    fun extrasOf(data: Map<String, String?>): Map<String, String> =
        DATA_KEYS.mapNotNull { k -> data[k]?.takeIf { it.isNotBlank() }?.let { k to it } }.toMap()

    /** intent-এ নোটিফিকেশনের data থাকলে সেটা (type, requestId বা questionId না থাকলে null — সাধারণ খোলা) */
    fun dataFrom(intent: Intent?): Map<String, String>? {
        if (intent == null) return null
        val m = DATA_KEYS.mapNotNull { k -> intent.getStringExtra(k)?.takeIf { it.isNotBlank() }?.let { k to it } }.toMap()
        return m.takeIf { EXTRA_TYPE in it || EXTRA_REQUEST_ID in it || EXTRA_QUESTION_ID in it }
    }

    /** অ্যাপ খোলা থাকা অবস্থায় পুশ এলে নিজে দেখানো হয় (বন্ধ থাকলে ফোন নিজেই দেখায়) */
    @SuppressLint("MissingPermission")
    fun show(context: Context, title: String, body: String, data: Map<String, String>, tag: String? = null) {
        if (!canNotify(context)) return
        val extras = extrasOf(data)
        val type = extras[EXTRA_TYPE]
        val kind = extras[EXTRA_KIND]
        // কোন ধরনের খবর (সার্ভারের `cat`; পুরোনো সার্ভারে type থেকে) → সেই ধরনের চ্যানেল
        val cat = NotifyCats.catOf(data)
        val channelId = NotifyCats.channelId(cat)
        ensureChannelFor(context, channelId)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            extras.forEach { (k, v) -> putExtra(k, v) }
        }
        val id = (extras[EXTRA_REQUEST_ID] ?: extras[EXTRA_QUESTION_ID] ?: tag ?: type ?: "app").hashCode()
        val pending = PendingIntent.getActivity(
            context, id, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val ambulanceIcon = cat == NotifyCats.AMBULANCE || (type == "services" && serviceKind(kind, title) == "ambulance") || type == "amb_req_mine"
        val n = NotificationCompat.Builder(context, channelId)
            // অ্যাম্বুলেন্সের খবরে অ্যাম্বুলেন্সের আইকন, বাকি সব অ্যাপের রক্তের ফোঁটা
            .setSmallIcon(if (ambulanceIcon) R.drawable.ic_ambulance else R.drawable.ic_stat_blood)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            // Android ৭ ও আগের ফোনে চ্যানেল নেই — গুরুত্ব এখানে
            .setPriority(if (cat in NotifyCats.HIGH) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
    }

    /** এই ফোনের পুশ টোকেন প্রোফাইলে রাখা, যাতে সার্ভার এখানে নোটিফিকেশন পাঠাতে পারে */
    suspend fun syncToken() {
        val uid = AuthRepository.currentUid ?: return
        val token = FirebaseMessaging.getInstance().token.await()
        ProfileRepository.saveFcmToken(uid, token)
    }
}

/** সার্ভার থেকে আসা পুশ নোটিফিকেশন গ্রহণ */
class BloodMessagingService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        val uid = AuthRepository.currentUid ?: return
        scope.launch { runCatching { ProfileRepository.saveFcmToken(uid, token) } }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // লগআউট করা ফোনে, বা এই ফোনে এখন অন্য অ্যাকাউন্ট থাকলে, আগের অ্যাকাউন্টের নোটিফিকেশন দেখানো হয় না
        val me = AuthRepository.currentUid ?: return
        val to = message.data["forUid"]
        if (!to.isNullOrBlank() && to != me) return
        val title = message.notification?.title ?: message.data["title"] ?: APP_NAME
        val body = message.notification?.body ?: message.data["body"] ?: ""
        // প্রতিটা বার্তা আলাদা নোটিফিকেশন; একই রিকোয়েস্ট/আহ্বান/প্রশ্নের বার্তা আগেরটার জায়গায়
        val tag = message.data["emergencyId"] ?: message.data["ticketId"] ?: message.data["visitId"] ?: message.data["orderId"]
            ?: message.data["findId"] ?: message.data["reqId"] ?: message.messageId
        // চাপলে যে পাতা খোলে তার সব তথ্য (type, requestId, role, rxId, asUid, reqId …) intent-এ যায়
        Notifier.show(this, title, body, message.data, tag)
    }
}
