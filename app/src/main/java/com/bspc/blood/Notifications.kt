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
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

object Notifier {
    const val CHANNEL_ID = "blood_requests"
    const val EXTRA_REQUEST_ID = "requestId"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "রক্তের রিকোয়েস্ট",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "আশেপাশে রক্ত দরকার হলে এবং আবার রক্ত দেওয়ার সময় হলে জানায়" }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun openAppIntent(context: Context, requestId: String?, code: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (requestId != null) putExtra(EXTRA_REQUEST_ID, requestId)
        }
        return PendingIntent.getActivity(
            context, code, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    @SuppressLint("MissingPermission")
    fun showRequest(context: Context, r: BloodRequest, distanceKm: Double) {
        if (!canNotify(context)) return
        val title = "🩸 ${r.patientGroup} রক্ত দরকার" + if (r.urgent) " (জরুরি)" else ""
        val body = "${r.bags} ব্যাগ · ${r.hospital}, ${r.placeName} · ${Rules.formatKm(distanceKm)}"
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_blood)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, r.id, r.id.hashCode()))
            .build()
        NotificationManagerCompat.from(context).notify(r.id.hashCode(), n)
    }

    @SuppressLint("MissingPermission")
    fun showEligible(context: Context, nearbyCount: Int) {
        if (!canNotify(context)) return
        val body = if (nearbyCount > 0) {
            "আপনার আশেপাশে ${nearbyCount}টি খোলা রিকোয়েস্ট আছে।"
        } else {
            "কারো রক্ত লাগলে আমরা আপনাকে জানাব।"
        }
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_blood)
            .setContentTitle("আপনি আবার রক্ত দিতে পারবেন ❤️")
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, null, 1))
            .build()
        NotificationManagerCompat.from(context).notify(1, n)
    }
}

/**
 * সার্ভার ছাড়া নোটিফিকেশন: প্রতি ১৫ মিনিটে (ফোন অনুমতি দিলে) ফোন নিজেই
 * আশেপাশের নতুন রিকোয়েস্ট খোঁজে, আর আবার রক্ত দেওয়ার সময় হলে জানায়।
 * পরে Firebase Blaze প্ল্যান চালু হলে Cloud Functions + FCM দিয়ে তাৎক্ষণিক করা যাবে।
 */
class CheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val uid = AuthRepository.currentUid ?: return Result.success()
        val profile = runCatching { ProfileRepository.load(uid) }.getOrNull() ?: return Result.success()
        if (!profile.notify) return Result.success()

        val ctx = applicationContext
        val prefs = ctx.getSharedPreferences("notify_state", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val eligible = Rules.isEligible(profile.lastDonationMillis, now)

        val lat = profile.lat
        val lng = profile.lng
        val nearby = if (eligible && lat != null && lng != null) {
            runCatching { RequestRepository.nearbyOpen(lat, lng, profile.bloodGroup, uid) }
                .getOrDefault(emptyList())
        } else {
            emptyList()
        }

        // আবার যোগ্য হওয়ার রিমাইন্ডার (প্রতিটা রক্তদানের পর একবার)
        val last = profile.lastDonationMillis
        if (last != null && eligible && prefs.getLong("eligibleNotifiedFor", -1L) != last) {
            Notifier.showEligible(ctx, nearby.size)
            prefs.edit().putLong("eligibleNotifiedFor", last).apply()
        }

        // নতুন রিকোয়েস্ট, দিনে সর্বোচ্চ ৩টা
        val lastSeen = prefs.getLong("lastSeenMillis", now - 6 * 60 * 60 * 1000L)
        val today = Rules.dayKey(now)
        var count = if (prefs.getString("dayKey", "") == today) prefs.getInt("dayCount", 0) else 0
        for ((r, distance) in nearby.filter { it.first.createdAtMillis > lastSeen }) {
            if (count >= Rules.MAX_NOTIFICATIONS_PER_DAY) break
            Notifier.showRequest(ctx, r, distance)
            count++
        }
        prefs.edit()
            .putLong("lastSeenMillis", now)
            .putString("dayKey", today)
            .putInt("dayCount", count)
            .apply()
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CheckWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "check_requests",
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
