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
    const val CHANNEL_ID = "blood_requests"
    const val EXTRA_REQUEST_ID = "requestId"
    /** "request" (ডোনারের জন্য), "response" (রক্তপ্রার্থীর জন্য), "eligible" */
    const val EXTRA_TYPE = "type"
    const val EXTRA_QUESTION_ID = "questionId"
    const val EXTRA_JOB_ID = "jobId" // FEATURE:jobs
    const val EXTRA_KIND = "kind"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                tr("Blood requests", "রক্তের রিকোয়েস্ট"),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = tr("Nearby blood requests and replies", "আশেপাশের রক্তের রিকোয়েস্ট আর সাড়া") }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** অ্যাপ খোলা থাকা অবস্থায় পুশ এলে নিজে দেখানো হয় (বন্ধ থাকলে ফোন নিজেই দেখায়) */
    @SuppressLint("MissingPermission")
    fun show(context: Context, title: String, body: String, requestId: String?, type: String?, questionId: String? = null, tag: String? = null, kind: String? = null, jobId: String? = null) {
        if (!canNotify(context)) return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (requestId != null) putExtra(EXTRA_REQUEST_ID, requestId)
            if (type != null) putExtra(EXTRA_TYPE, type)
            if (questionId != null) putExtra(EXTRA_QUESTION_ID, questionId)
            if (jobId != null) putExtra(EXTRA_JOB_ID, jobId) // FEATURE:jobs
            if (kind != null) putExtra(EXTRA_KIND, kind)
        }
        val id = (requestId ?: questionId ?: tag ?: type ?: "app").hashCode()
        val pending = PendingIntent.getActivity(
            context, id, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            // অ্যাম্বুলেন্সের খবরে অ্যাম্বুলেন্সের আইকন, বাকি সব অ্যাপের রক্তের ফোঁটা
            .setSmallIcon(if (type == "services" && serviceKind(kind, title) == "ambulance") R.drawable.ic_ambulance else R.drawable.ic_stat_blood)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
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
            ?: message.data["findId"] ?: message.data["jobId"] /* FEATURE:jobs */ ?: message.messageId
        Notifier.show(this, title, body, message.data["requestId"], message.data["type"], message.data["questionId"], tag, message.data["kind"], message.data["jobId"] /* FEATURE:jobs */)
    }
}
