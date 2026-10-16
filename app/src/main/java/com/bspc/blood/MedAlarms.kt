package com.bspc.blood

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * ওষুধ খাওয়ার অ্যালার্ম — পুরোটা ফোনেই (সার্ভারে কিছু যায় না)।
 * প্রতিটা প্রেসক্রিপশনের জন্য: কোন ওষুধ কোন বেলায় (১+০+১ → সকাল আর রাত), কত দিন, আর বেলাগুলোর সময় (সকাল ৮টা…)।
 * একবারে শুধু পরের অ্যালার্মটা সেট থাকে; বাজলে সেই বেলার ওষুধের তালিকা দেখিয়ে পরেরটা সেট হয়। ফোন চালু হলে আবার সেট।
 */
object MedAlarms {
    private const val PREFS = "med_alarms"
    const val CHANNEL = "med_reminders"
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    /** "চলবে" ওষুধে সর্বোচ্চ কত দিন মনে করানো */
    private const val CONTINUE_DAYS = 90

    /** বেলা: ০ সকাল, ১ দুপুর, ২ বিকাল/সন্ধ্যা, ৩ রাত */
    val DEFAULT_TIMES = listOf("08:00", "14:00", "18:00", "21:00")

    fun slotLabel(i: Int): String = when (i) {
        0 -> tr("Morning", "সকাল")
        1 -> tr("Noon", "দুপুর")
        2 -> tr("Evening", "সন্ধ্যা")
        else -> tr("Night", "রাত")
    }

    /** "1+0+1" → [সকাল, রাত]; "1+1+1" → [সকাল, দুপুর, রাত]; "1+1+1+1" → চার বেলা; বুঝতে না পারলে খালি */
    fun slotsOf(dose: String): List<Int> {
        val parts = dose.replace(" ", "").replace("½", "0.5").split("+")
        val nums = parts.map { it.toDoubleOrNull() ?: return emptyList() }
        val map = when (nums.size) {
            2 -> listOf(0, 3)
            3 -> listOf(0, 1, 3)
            4 -> listOf(0, 1, 2, 3)
            else -> return emptyList()
        }
        return nums.indices.filter { nums[it] > 0 }.map { map[it] }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun plans(context: Context): JSONObject = runCatching { JSONObject(prefs(context).getString("plans", "{}") ?: "{}") }.getOrDefault(JSONObject())

    private fun savePlans(context: Context, o: JSONObject) {
        prefs(context).edit().putString("plans", o.toString()).apply()
    }

    fun isOn(context: Context, rxId: String): Boolean = plans(context).has(rxId)

    /** times: বেলার সময় ("08:00"); শুরু আজ থেকে */
    fun turnOn(context: Context, rx: Rx, times: List<String>) {
        val start = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val meds = JSONArray()
        rx.medicines.forEach { m ->
            val slots = slotsOf(m.dose)
            if (slots.isEmpty()) return@forEach
            meds.put(
                JSONObject().put("name", m.title).put("dose", m.dose).put("timing", m.timing)
                    .put("slots", JSONArray(slots)).put("days", if (m.days > 0) m.days else CONTINUE_DAYS),
            )
        }
        val o = plans(context)
        o.put(rx.id, JSONObject().put("start", start).put("doctor", rx.doctorName).put("times", JSONArray(times)).put("meds", meds))
        savePlans(context, o)
        schedule(context)
    }

    fun turnOff(context: Context, rxId: String) {
        val o = plans(context)
        o.remove(rxId)
        savePlans(context, o)
        schedule(context)
    }

    private fun at(dayStart: Long, hhmm: String): Long {
        val (h, m) = hhmm.split(":").map { it.toIntOrNull() ?: 0 }
        val c = Calendar.getInstance().apply { timeInMillis = dayStart; set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m) }
        return c.timeInMillis
    }

    /** এখনকার পরের বেলা: (সময়, সেই বেলায় খাওয়ার ওষুধ) */
    fun next(context: Context, now: Long = System.currentTimeMillis()): Pair<Long, List<String>>? {
        val o = plans(context)
        var best: Long? = null
        val due = mutableListOf<String>()
        val expired = mutableListOf<String>()
        o.keys().forEach { id ->
            val p = o.getJSONObject(id)
            val start = p.optLong("start")
            val times = p.optJSONArray("times").strings().ifEmpty { DEFAULT_TIMES }
            val meds = p.optJSONArray("meds") ?: JSONArray()
            var alive = false
            for (k in 0 until meds.length()) {
                val m = meds.getJSONObject(k)
                val days = m.optInt("days")
                val slots = m.optJSONArray("slots") ?: JSONArray()
                for (d in 0 until days) {
                    val dayStart = start + d * DAY_MS
                    if (dayStart + DAY_MS < now) continue
                    for (s in 0 until slots.length()) {
                        val t = at(dayStart, times.getOrElse(slots.getInt(s)) { DEFAULT_TIMES[slots.getInt(s)] })
                        if (t <= now) continue
                        alive = true
                        val label = m.optString("name") + " — " + num(m.optString("dose")) + RxText.timing(m.optString("timing")).let { if (it.isBlank()) "" else " · $it" }
                        when {
                            best == null || t < best!! -> { best = t; due.clear(); due += label }
                            t == best -> due += label
                        }
                    }
                }
            }
            if (!alive) expired += id
        }
        if (expired.isNotEmpty()) {
            expired.forEach { o.remove(it) }
            savePlans(context, o)
        }
        return best?.let { it to due.toList() }
    }

    @SuppressLint("ScheduleExactAlarm")
    fun schedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(context, 7101, Intent(context, MedAlarmReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        am.cancel(pi)
        val n = next(context) ?: return
        // ঠিক সময়ে বাজানোর অনুমতি থাকলে ঠিক সময়ে, নইলে কয়েক মিনিট এদিক-ওদিক (ফোনের ব্যাটারি বাঁচানোর নিয়ম)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, n.first, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, n.first, pi)
    }

    @SuppressLint("MissingPermission")
    fun notifyDue(context: Context) {
        val now = System.currentTimeMillis()
        // এইমাত্র বাজা বেলার ওষুধ: আধ ঘণ্টা আগে থেকে খুঁজলে (অ্যালার্ম দেরিতে বাজলেও) এই বেলাটাই আসে
        val n = next(context, now - 30 * 60 * 1000) ?: return
        if (n.first > now + 60 * 1000) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (Build.VERSION.SDK_INT >= 26) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, tr("Medicine reminders", "ওষুধ খাওয়ার অ্যালার্ম"), NotificationManager.IMPORTANCE_HIGH),
            )
        }
        val open = PendingIntent.getActivity(
            context, 7102,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(Notifier.EXTRA_TYPE, "rx")
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val body = n.second.joinToString("\n")
        val notif = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_medication)
            .setContentTitle(tr("Time for your medicine", "ওষুধ খাওয়ার সময়"))
            .setContentText(n.second.firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        NotificationManagerCompat.from(context).notify((n.first / 60000).toInt(), notif)
    }
}

class MedAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching { MedAlarms.notifyDue(context) }
        // পরের বেলা (এই বেলাটা পেরিয়ে)
        runCatching { MedAlarms.schedule(context) }
    }
}

class MedAlarmBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching { MedAlarms.schedule(context) }
    }
}

/** অ্যালার্ম চালুর আগে বেলাগুলোর সময় দেখে/বদলে নেওয়া */
@Composable
fun MedAlarmDialog(rx: Rx, onDismiss: () -> Unit, onOn: () -> Unit) {
    val context = LocalContext.current
    val used = remember(rx.id) { rx.medicines.flatMap { MedAlarms.slotsOf(it.dose) }.distinct().sorted() }
    val times = remember { mutableStateListOf(*MedAlarms.DEFAULT_TIMES.toTypedArray()) }
    var pick by remember { mutableStateOf<Int?>(null) }
    FormDialog(
        title = tr("Medicine alarm", "ওষুধের অ্যালার্ম"),
        onDismiss = onDismiss,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    tr("The phone will remind you at these times, for as many days as the doctor wrote.", "ডাক্তার যত দিন লিখেছেন, তত দিন এই সময়গুলোতে ফোন মনে করিয়ে দেবে।"),
                    style = MaterialTheme.typography.bodyMedium,
                )
                used.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(MedAlarms.slotLabel(s), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { pick = s }) { Text(Weekdays.time(times[s])) }
                    }
                }
                Text(
                    rx.medicines.filter { MedAlarms.slotsOf(it.dose).isNotEmpty() }.joinToString("\n") { "• " + it.title + " — " + it.howTo },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                MedAlarms.turnOn(context, rx, times.toList())
                onOn()
            }) { Text(tr("Turn on", "চালু করুন")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "বাতিল")) } },
    )
    pick?.let { s ->
        val (h, m) = times[s].split(":").map { it.toIntOrNull() ?: 0 }
        TimePickerPopup(h, m, onPicked = { hh, mm -> times[s] = Weekdays.hhmm(hh, mm) }, onDismiss = { pick = null }, title = MedAlarms.slotLabel(s))
    }
}
