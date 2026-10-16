package com.bspc.blood

// FEATURE:jobs — পুরো ফাইলটা চাকরি ফিচারের: ইন্টারভিউর ২৪ ঘণ্টা আর ১ ঘণ্টা আগে ফোনে মনে করানো
// (বিনামূল্যের সার্ভারে নির্ধারিত কাজ দিনে একবারই চলে, তাই ফোন নিজেই অ্যালার্ম বসায়)

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

object JobAlarms {
    private const val PREFS = "job_alarms"
    private const val KEY = "codes"
    private val OFFSETS = listOf(24 * 60 * 60_000L, 60 * 60_000L)

    /** সার্ভার থেকে সামনের ইন্টারভিউ এনে অ্যালার্ম নতুন করে বসানো */
    suspend fun sync(context: Context) {
        val uid = AuthRepository.currentUid ?: return
        val list = runCatching { JobsApi.upcomingInterviews() }.getOrNull() ?: return
        schedule(context.applicationContext, uid, list)
    }

    fun schedule(context: Context, uid: String, list: List<UpcomingInterview>) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // আগের সব অ্যালার্ম বাতিল (বাতিল/বদলানো ইন্টারভিউর পুরোনো মনে করানো যেন না আসে)
        prefs.getStringSet(KEY, emptySet()).orEmpty().forEach { code ->
            code.toIntOrNull()?.let { am.cancel(pending(context, it, Intent(context, JobAlarmReceiver::class.java))) }
        }
        val now = System.currentTimeMillis()
        val codes = mutableSetOf<String>()
        for (iv in list) {
            for (off in OFFSETS) {
                val t = iv.at - off
                if (t <= now) continue
                val code = (iv.id + off).hashCode()
                val intent = Intent(context, JobAlarmReceiver::class.java).apply {
                    putExtra("uid", uid)
                    putExtra("jobId", iv.jobId)
                    putExtra("role", iv.role)
                    putExtra("title", if (off > 2 * 60 * 60_000L) tr("Interview tomorrow", "আগামীকাল ইন্টারভিউ") else tr("Interview in 1 hour", "১ ঘণ্টা পর ইন্টারভিউ"))
                    putExtra("body", listOf(iv.title, iv.who, JobText.dateTime(iv.at), if (iv.mode == "online") tr("Online", "অনলাইনে") else iv.place).filter { it.isNotBlank() }.joinToString(" · "))
                }
                runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pending(context, code, intent)) }
                codes += code.toString()
            }
        }
        prefs.edit().putStringSet(KEY, codes).apply()
    }

    private fun pending(context: Context, code: Int, intent: Intent): PendingIntent =
        PendingIntent.getBroadcast(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}

class JobAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // লগআউট করলে বা অন্য অ্যাকাউন্টে থাকলে আগের অ্যাকাউন্টের মনে করানো দেখানো হয় না
        AppPrefs.init(context)
        val uid = intent.getStringExtra("uid")
        if (uid == null || uid != AuthRepository.currentUid) return
        val jobId = intent.getStringExtra("jobId") ?: return
        val type = if (intent.getStringExtra("role") == "employer") "job_applicants" else "job"
        Notifier.show(
            context,
            intent.getStringExtra("title") ?: "",
            intent.getStringExtra("body") ?: "",
            null, type, null, "interview_$jobId", null, jobId,
        )
    }
}
