@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.bspc.blood

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

// ------------------------------------------------------------
// সিরিয়াল চলছে (সার্ভারের server/lib/queue.js; ওয়েবের appointment/queue.tsx)
// - রোগী (বা কেয়ারগিভার): আজকের নিশ্চিত সিরিয়ালে এখন কত চলছে, আগে কতজন, কখন আসবেন, "দেরি হবে"
// - ডাক্তার/সহকারী: "শুরু করুন", "পরের রোগী", সিরিয়াল চেপে "এখন দেখছি", "বিরতি", দেরির উত্তর
// পাতা খোলা আর অ্যাপ সামনে থাকলে ৩০ সেকেন্ডে একবার নতুন করে আনে (রিয়েলটাইম নয়)।
// ------------------------------------------------------------

private const val QUEUE_REFRESH_MS = 30_000L
val LATE_MINUTES = listOf(10, 20, 30, 60)

data class LateInfo(val min: Int, val note: String, val at: Long, val status: String)

data class PatientQueueInfo(
    val live: Boolean,
    val serial: Int = 0,
    val started: Boolean = false,
    val paused: Boolean = false,
    val current: Int = 0,
    /** "waiting", "now", "done", "passed", "finished" */
    val state: String = "",
    val ahead: Int = 0,
    val later: Boolean = false,
    val avgMin: Int = 10,
    val eta: Long? = null,
    val arriveBy: Long? = null,
    val late: LateInfo? = null,
    val canLate: Boolean = false,
)

data class QueueRow(val id: String, val serial: Int, val name: String, val status: String, val state: String, val late: LateInfo?)
data class QueueState(val started: Boolean, val current: Int, val paused: Boolean, val finished: Boolean, val next: Int?)
data class QueueChamber(val chamberKey: String, val name: String, val nameBn: String, val avgMin: Int, val queue: QueueState, val rows: List<QueueRow>) {
    val label: String get() = if (isBangla && nameBn.isNotBlank()) nameBn else name
}

object QueueApi {
    private suspend fun call(action: String, body: JSONObject = JSONObject()): JSONObject = ServerApi.post("/api/community", body.put("action", action))
    private fun JSONArray?.objs(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    private fun late(o: JSONObject?): LateInfo? = o?.let { LateInfo(it.optInt("min"), it.str("note"), it.optLong("at"), it.str("status")) }
    private fun longOrNull(o: JSONObject, k: String): Long? = if (o.isNull(k) || !o.has(k)) null else o.optLong(k)

    /** রোগী: একটা অ্যাপয়েন্টমেন্টের সারির তথ্য (কেয়ারগিভার মোডে CareMode নিজে asUid দেয়) */
    suspend fun patient(id: String): PatientQueueInfo {
        val o = call("appointment_queue", JSONObject().put("id", id))
        if (!o.optBoolean("live")) return PatientQueueInfo(false)
        return PatientQueueInfo(
            true, o.optInt("serial"), o.optBoolean("started"), o.optBoolean("paused"), o.optInt("current"), o.str("state"),
            o.optInt("ahead"), o.optBoolean("later"), o.optInt("avgMin", 10), longOrNull(o, "eta"), longOrNull(o, "arriveBy"),
            late(o.optJSONObject("late")), o.optBoolean("canLate"),
        )
    }

    suspend fun askLate(id: String, minutes: Int, note: String) {
        call("appointment_late", JSONObject().put("id", id).put("minutes", minutes).put("note", note))
    }

    /** ডাক্তার/সহকারী: আজকের চেম্বারগুলোর সারি (doctorUid = সহকারী কার হয়ে) */
    suspend fun today(doctorUid: String?): List<QueueChamber> =
        call("queue_today", JSONObject().apply { if (doctorUid != null) put("doctorUid", doctorUid) }).optJSONArray("chambers").objs().map { c ->
            val q = c.optJSONObject("queue") ?: JSONObject()
            QueueChamber(
                c.str("chamberKey"), c.str("name"), c.str("nameBn"), c.optInt("avgMin", 10),
                QueueState(q.optBoolean("started"), q.optInt("current"), q.optBoolean("paused"), q.optBoolean("finished"), q.optInt("next").takeIf { it > 0 }),
                c.optJSONArray("rows").objs().map { r -> QueueRow(r.str("id"), r.optInt("serial"), r.str("name"), r.str("status"), r.str("state"), late(r.optJSONObject("late"))) },
            )
        }

    suspend fun control(doctorUid: String?, chamberKey: String, op: String, appointmentId: String? = null) {
        call(
            "queue_control",
            JSONObject().put("chamberKey", chamberKey).put("op", op)
                .apply { if (doctorUid != null) put("doctorUid", doctorUid) }
                .apply { if (appointmentId != null) put("appointmentId", appointmentId) },
        )
    }

    suspend fun reply(id: String, op: String) {
        call("appointment_late_reply", JSONObject().put("id", id).put("op", op))
    }
}

/** অ্যাপ সামনে আছে কি না (পেছনে গেলে বারবার আনা বন্ধ) */
@Composable
private fun rememberResumed(): Boolean {
    @Suppress("DEPRECATION")
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var resumed by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) }
    DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ ->
            resumed = owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return resumed
}

/** আজকের নিশ্চিত সিরিয়াল কি না (সারির তথ্য তখনই) */
fun Appointment.queueLive(): Boolean = role == "patient" && status == "confirmed" && serial > 0 && date == Rules.todayUtcMidnight()

private fun lateLabel(min: Int) = tr("$min min late", "${num(min)} মিনিট দেরি হবে")

// ------------------------------------------------------------
// রোগী
// ------------------------------------------------------------

/** আমার অ্যাপয়েন্টমেন্টের কার্ডের ভেতরে: আজকের নিশ্চিত সিরিয়ালে সারির তথ্য আর "দেরি হবে" */
@Composable
fun PatientQueueCard(a: Appointment) {
    if (!a.queueLive()) return
    val resumed = rememberResumed()
    var info by remember(a.id) { mutableStateOf<PatientQueueInfo?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var asking by remember { mutableStateOf(false) }
    LaunchedEffect(a.id, resumed, reload) {
        if (!resumed) return@LaunchedEffect
        while (true) {
            runCatching { QueueApi.patient(a.id) }.onSuccess { info = it }
            delay(QUEUE_REFRESH_MS)
        }
    }
    val q = info ?: return
    if (!q.live) return
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.surfaceContainerHighest, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("Now", "এখন চলছে"), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    Text(if (q.started && q.current > 0) num(q.current) else "—", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f)) {
                    Text(tr("Your serial", "আপনার সিরিয়াল"), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    Text(num(q.serial), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = cs.primary)
                }
            }
            if (q.paused) Tag(tr("Doctor is on a short break", "ডাক্তার একটু বিরতিতে"), cs.primaryContainer, cs.onPrimaryContainer)
            val stateText = when (q.state) {
                "now" -> tr("It's your turn now", "এখন আপনার পালা")
                "done" -> tr("Seen", "দেখা হয়ে গেছে")
                "passed" -> tr("Your serial has passed — tell the chamber", "আপনার সিরিয়াল পার হয়ে গেছে — চেম্বারে জানান")
                "finished" -> tr("Today's serials are over", "আজকের সিরিয়াল শেষ")
                else -> null
            }
            if (stateText != null) {
                Text(stateText, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = if (q.state == "now") cs.primary else cs.onSurface)
            } else {
                if (!q.started) Text(tr("Not started yet", "এখনো শুরু হয়নি"), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                Text(tr("${q.ahead} before you", "আপনার আগে ${num(q.ahead)} জন"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (q.later) Text(tr("You will be seen at the end", "আপনাকে সবার শেষে দেখা হবে"), style = MaterialTheme.typography.bodyMedium, color = cs.tertiary)
                Text(tr("Doctor takes ~${q.avgMin} min each", "ডাক্তার গড়ে ${num(q.avgMin)} মিনিট নেন"), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                q.eta?.let { eta ->
                    val arrive = q.arriveBy?.let { if (it <= System.currentTimeMillis()) tr("Come now", "এখনই চলে আসুন") else tr("Be there by ${Rules.formatTime(it)}", "${Rules.formatTime(it)}-এর মধ্যে উপস্থিত থাকুন") }
                    Surface(color = cs.primaryContainer, contentColor = cs.onPrimaryContainer, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(R.drawable.ic_schedule, null, Modifier.size(18.dp))
                            Text(
                                tr("Estimated ${Rules.formatTime(eta)}", "আনুমানিক সময় ${Rules.formatTime(eta)}") + (arrive?.let { " — $it" } ?: ""),
                                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            }
            q.late?.let { l ->
                val reply = when (l.status) {
                    "ok" -> tr("Doctor: OK", "ডাক্তার: ঠিক আছে")
                    "later" -> tr("Doctor: come later", "ডাক্তার: পরে আসুন")
                    else -> tr("Waiting for reply", "উত্তরের অপেক্ষায়")
                }
                Text(
                    tr("Asked for ${l.min} min", "সময় চেয়েছেন: ${num(l.min)} মিনিট") + " · " + reply,
                    style = MaterialTheme.typography.bodyMedium, color = if (l.status == "asked") cs.onSurfaceVariant else cs.tertiary,
                )
            }
            if (q.canLate) {
                OutlinedButton(onClick = { asking = true }) { ButtonContent(false, tr("I will be late", "দেরি হবে"), R.drawable.ic_directions_run) }
            }
        }
    }
    if (asking) LateDialog(a.id, q.late, onDismiss = { asking = false }) { asking = false; reload++ }
}

@Composable
private fun LateDialog(id: String, current: LateInfo?, onDismiss: () -> Unit, onSent: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var min by remember { mutableStateOf(current?.min?.takeIf { it in LATE_MINUTES }) }
    var note by remember { mutableStateOf(current?.note.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = tr("I will be late", "দেরি হবে"),
        onDismiss = { if (!busy) onDismiss() },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LATE_MINUTES.forEach { m ->
                        FilterChip(selected = min == m, onClick = { min = m }, label = { Text(tr("$m min", "${num(m)} মিনিট")) })
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(100) },
                    label = { Text(tr("Note (optional)", "নোট (ঐচ্ছিক)")) },
                    placeholder = { Text(tr("e.g. Stuck in traffic", "যেমন: জ্যামে আছি")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ErrorText(errorMsg)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                val m = min
                if (m == null) {
                    errorMsg = tr("Choose how late", "কত দেরি হবে বেছে নিন")
                } else scope.launch {
                    busy = true
                    errorMsg = null
                    runCatching { QueueApi.askLate(id, m, note.trim()) }
                        .onSuccess {
                            android.widget.Toast.makeText(context, tr("Sent to the doctor", "ডাক্তারকে পাঠানো হয়েছে"), android.widget.Toast.LENGTH_SHORT).show()
                            onSent()
                        }
                        .onFailure { errorMsg = it.message }
                    busy = false
                }
            }) { ButtonContent(busy, tr("Send", "পাঠান")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancel", "বাতিল")) } },
    )
}

// ------------------------------------------------------------
// ডাক্তার / সহকারী
// ------------------------------------------------------------

/** আজকের সিরিয়ালের সারি চালানো; doctorUid = সহকারী কার হয়ে (ডাক্তার নিজে হলে null); refreshKey বদলালে নতুন করে আনে। আজ কোনো সিরিয়াল না থাকলে কিছুই দেখায় না। */
@Composable
fun QueuePanel(doctorUid: String?, refreshKey: Int = 0) {
    val resumed = rememberResumed()
    val scope = rememberCoroutineScope()
    var list by remember(doctorUid) { mutableStateOf<List<QueueChamber>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(doctorUid, resumed, reload, refreshKey) {
        if (!resumed) return@LaunchedEffect
        while (true) {
            runCatching { QueueApi.today(doctorUid) }.onSuccess { list = it }
            delay(QUEUE_REFRESH_MS)
        }
    }
    fun act(key: String, block: suspend () -> Unit) {
        scope.launch {
            busy = key
            errorMsg = null
            runCatching { block() }.onSuccess { reload++ }.onFailure { errorMsg = it.message }
            busy = null
        }
    }
    if (list.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        list.forEach { c -> QueueChamberCard(c, list.size > 1, busy, errorMsg, doctorUid, ::act) }
    }
}

@Composable
private fun QueueChamberCard(
    c: QueueChamber,
    many: Boolean,
    busy: String?,
    errorMsg: String?,
    doctorUid: String?,
    act: (String, suspend () -> Unit) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val q = c.queue
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("Today's serial", "আজকের সিরিয়াল"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (many) Text(c.label, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                val status = when {
                    q.paused -> tr("Break", "বিরতি")
                    q.finished -> tr("Done", "শেষ")
                    q.started -> tr("Running", "চলছে")
                    else -> tr("Not started", "শুরু হয়নি")
                }
                Tag(
                    status,
                    if (q.started && !q.finished && !q.paused) cs.tertiaryContainer else if (q.paused) cs.primaryContainer else cs.surfaceVariant,
                    if (q.started && !q.finished && !q.paused) cs.onTertiaryContainer else if (q.paused) cs.onPrimaryContainer else cs.onSurfaceVariant,
                )
            }
            Row {
                QueueStat(tr("Seeing now", "এখন দেখছি"), if (q.started && q.current > 0) num(q.current) else "—", true)
                QueueStat(tr("Next", "পরের"), q.next?.let { num(it) } ?: "—", false)
                QueueStat(tr("Avg.", "গড়"), tr("${c.avgMin}m", "${num(c.avgMin)} মি."), false)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!q.started) {
                    Button(onClick = { act("start") { QueueApi.control(doctorUid, c.chamberKey, "start") } }, enabled = busy == null) {
                        ButtonContent(busy == "start", tr("Start", "শুরু করুন"), R.drawable.ic_chevron_right)
                    }
                } else {
                    Button(onClick = { act("next") { QueueApi.control(doctorUid, c.chamberKey, "next") } }, enabled = busy == null && !(q.finished && q.next == null)) {
                        ButtonContent(busy == "next", tr("Next patient", "পরের রোগী"), R.drawable.ic_chevron_right)
                    }
                    if (q.paused) {
                        OutlinedButton(onClick = { act("resume") { QueueApi.control(doctorUid, c.chamberKey, "resume") } }, enabled = busy == null) {
                            ButtonContent(busy == "resume", tr("Resume", "আবার শুরু"), R.drawable.ic_sync)
                        }
                    } else {
                        OutlinedButton(onClick = { act("pause") { QueueApi.control(doctorUid, c.chamberKey, "pause") } }, enabled = busy == null && !q.finished) {
                            ButtonContent(busy == "pause", tr("Break", "বিরতি"), R.drawable.ic_schedule)
                        }
                    }
                }
            }
            ErrorText(errorMsg)
            c.rows.filter { it.state != "cancelled" }.forEach { r ->
                val now = q.started && q.current == r.serial
                val asked = r.late?.status == "asked" && r.state in listOf("waiting", "later")
                val dim = r.state == "done" || r.state == "passed"
                Surface(
                    color = if (now) cs.tertiaryContainer else cs.surfaceContainerHighest,
                    contentColor = if (now) cs.onTertiaryContainer else cs.onSurface,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        // সিরিয়াল চাপলে "এখন দেখছি"
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = busy == null && !now) {
                                act("set" + r.id) { QueueApi.control(doctorUid, c.chamberKey, "set", r.id) }
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(color = cs.primaryContainer, contentColor = cs.onPrimaryContainer, shape = RoundedCornerShape(10.dp)) {
                                Text(num(r.serial), fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                            }
                            Column(Modifier.weight(1f).padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    r.name.ifBlank { tr("Deleted account", "মুছে ফেলা অ্যাকাউন্ট") },
                                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = if (dim) cs.onSurfaceVariant else androidx.compose.ui.graphics.Color.Unspecified,
                                )
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (now) Tag(tr("Seeing now", "এখন দেখছি"), cs.tertiary, cs.onTertiary)
                                    if (r.state == "done") Tag(tr("Seen", "দেখা হয়েছে"), cs.surfaceVariant, cs.onSurfaceVariant)
                                    if (r.state == "later") Tag(tr("At the end", "শেষে"), cs.surfaceVariant, cs.onSurfaceVariant)
                                    val l = r.late
                                    if (l != null && r.state != "done" && (asked || l.status != "asked")) {
                                        Tag(lateLabel(l.min), if (asked) cs.errorContainer else cs.secondaryContainer, if (asked) cs.onErrorContainer else cs.onSecondaryContainer)
                                    }
                                }
                                if (asked && !r.late?.note.isNullOrBlank()) {
                                    Text(r.late?.note.orEmpty(), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                                }
                            }
                        }
                        if (asked) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                                SmallAction(tr("OK", "ঠিক আছে"), null, { act("ok" + r.id) { QueueApi.reply(r.id, "ok") } }, primary = true, enabled = busy == null, busy = busy == "ok" + r.id)
                                SmallAction(tr("Come later", "পরে আসুন"), null, { act("later" + r.id) { QueueApi.reply(r.id, "later") } }, enabled = busy == null, busy = busy == "later" + r.id)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.QueueStat(label: String, value: String, strong: Boolean) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.weight(1f)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Text(value, fontSize = if (strong) 26.sp else 20.sp, fontWeight = FontWeight.Bold, color = if (strong) cs.onSurface else cs.onSurfaceVariant)
    }
}
