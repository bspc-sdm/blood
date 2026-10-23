package com.bspc.blood

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * মেসেজের পুশ (`chat_message {threadId, messageId, from (প্রথম নাম), cat:"messages"}`, শুধু data):
 * `cat_messages` চ্যানেলে, প্রতি থ্রেডে একটাই নোটিফিকেশন (সংখ্যা বাড়ে)। লক স্ক্রিনেও শুধু "লাইফলো মেসেজ — <প্রথম নাম>"
 * — মেসেজের লেখা পুশে আসেই না। চাপলে সেই থ্রেড; "উত্তর দিন" থেকে সরাসরি উত্তর (পাঠানোর সারি দিয়ে)।
 * থ্রেডটা এখন সামনে খোলা থাকলে নোটিফিকেশন নয় — পর্দা নিজেই নতুন মেসেজ আনে।
 * `chat_read {threadId, upTo, by}`: অন্যজন পড়েছেন — শুধু খোলা পর্দার ✓✓।
 */
object ChatPush {
    const val TYPE = "chat_message"
    const val TYPE_READ = "chat_read"
    const val EXTRA_THREAD_ID = "threadId"
    const val ACTION_REPLY = "com.bspc.blood.chat.REPLY"
    const val KEY_REPLY = "chat_reply_text"
    private const val PREFS = "chat_notify"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun notificationId(threadId: String) = ("chat:$threadId").hashCode()

    /** true হলে সাধারণ নোটিফিকেশন আর নয় */
    fun handle(context: Context, data: Map<String, String>): Boolean {
        val type = data["type"] ?: return false
        val threadId = data[EXTRA_THREAD_ID].orEmpty()
        return when (type) {
            TYPE -> {
                if (threadId.isBlank()) return true
                ChatEvents.emit(ChatEvents.Event.Incoming(threadId))
                scope.launch { ChatUnread.refresh(force = true) }
                if (ChatEvents.activeThread != threadId) show(context, threadId, data["from"].orEmpty())
                true
            }
            TYPE_READ -> {
                val upTo = data["upTo"]?.toLongOrNull()
                if (threadId.isNotBlank() && upTo != null) ChatEvents.emit(ChatEvents.Event.Read(threadId, upTo))
                true
            }
            else -> false
        }
    }

    private fun canNotify(context: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** এই থ্রেডে কতগুলো নতুন (নোটিফিকেশনের সংখ্যা) — থ্রেড খুললে মোছে */
    private fun bump(context: Context, threadId: String): Int {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val n = p.getInt(threadId, 0) + 1
        p.edit().putInt(threadId, n).apply()
        return n
    }

    @SuppressLint("MissingPermission")
    fun show(context: Context, threadId: String, from: String, countOverride: Int? = null, note: String? = null) {
        if (!canNotify(context)) return
        val channel = NotifyCats.channelId(NotifyCats.MESSAGES)
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm?.getNotificationChannel(channel) == null) {
                AppPrefs.init(context)
                Notifier.ensureChannels(context)
            }
        }
        val count = countOverride ?: bump(context, threadId)
        val id = notificationId(threadId)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("from:$threadId", from).apply()
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(Notifier.EXTRA_TYPE, TYPE)
            putExtra(EXTRA_THREAD_ID, threadId)
        }
        val pending = PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = ChatLogic.notifyTitle(from)
        val body = note ?: ChatLogic.notifyBody(count)
        val b = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_blood)
            .setContentTitle(title)
            .setContentText(body)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // লেখায় গোপন কিছু নেই (শুধু অ্যাপের নাম + প্রথম নাম) — লক স্ক্রিনেও একই
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setOnlyAlertOnce(note != null)
            .setContentIntent(pending)
            .setGroup("chat")
        if (note == null) {
            val replyIntent = Intent(context, ChatReplyReceiver::class.java).setAction(ACTION_REPLY).putExtra(EXTRA_THREAD_ID, threadId)
            // RemoteInput-এ লেখা বসাতে হয় — MUTABLE লাগে (Android ১২+)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val replyPending = PendingIntent.getBroadcast(context, id, replyIntent, flags)
            val input = RemoteInput.Builder(KEY_REPLY).setLabel(tr("Write a reply", "উত্তর লিখুন")).build()
            b.addAction(
                NotificationCompat.Action.Builder(R.drawable.ic_send, tr("Reply", "উত্তর দিন"), replyPending)
                    .addRemoteInput(input)
                    .setAllowGeneratedReplies(false)
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                    .setShowsUserInterface(false)
                    .build(),
            )
        }
        runCatching { NotificationManagerCompat.from(context).notify(id, b.build()) }
    }

    /** থ্রেড খোলা হলে তার নোটিফিকেশন আর সংখ্যা মোছা */
    fun clear(context: Context, threadId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(threadId).remove("from:$threadId").apply()
        runCatching { NotificationManagerCompat.from(context).cancel(notificationId(threadId)) }
    }

    internal fun fromName(context: Context, threadId: String): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("from:$threadId", "").orEmpty()
}

/** নোটিফিকেশন থেকে সরাসরি উত্তর: পাঠানোর সারিতে দিয়ে কয়েক সেকেন্ড অপেক্ষা, তারপর নোটিফিকেশন সরানো (বা "পাঠানো যায়নি") */
class ChatReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ChatPush.ACTION_REPLY) return
        val threadId = intent.getStringExtra(ChatPush.EXTRA_THREAD_ID).orEmpty()
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(ChatPush.KEY_REPLY)?.toString()?.trim().orEmpty()
        if (threadId.isBlank() || !ChatLogic.canSendText(text) || AuthRepository.currentUid == null) {
            ChatPush.clear(context, threadId)
            return
        }
        AppPrefs.init(context)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val from = ChatPush.fromName(context, threadId)
            val q = ChatQueue.enqueue(context, threadId, text = text)
            val result: String? = if (q == null) null else withTimeoutOrNull(9_000L) {
                var r: String? = null
                while (r == null) {
                    val cur = ChatQueue.items.value.firstOrNull { it.clientId == q.clientId }
                    r = when {
                        cur == null -> "sent"
                        cur.failedReason != null -> cur.failedReason
                        else -> { delay(300); null }
                    }
                }
                r
            }
            when (result) {
                "sent" -> ChatPush.clear(context, threadId)
                // ইন্টারনেট নেই: সারিতে আছে, পরে যাবে
                null -> ChatPush.show(context, threadId, from, countOverride = 1, note = tr("Will send when online", "ইন্টারনেট এলে পাঠানো হবে"))
                else -> ChatPush.show(context, threadId, from, countOverride = 1, note = result)
            }
            pending.finish()
        }
    }
}
