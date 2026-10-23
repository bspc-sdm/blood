@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.bspc.blood

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ------------------------------------------------------------
// ডাক্তারের "আজকের রোগী" (mywork-review/CONTRACT.md §২, ২৪ অক্টোবর ২০২৬): আগের দুই সারি — "আজকের সিরিয়াল"
// (পরের রোগী/বিরতি/দেরির উত্তর, Queue.kt) আর "আজকের ডেস্ক" (এসেছেন/ফি নিন, InvoiceForms.kt) — এখন একটাই।
// প্রতিটা সারি: সিরিয়াল নং, নাম, অবস্থা (অপেক্ষায় / দেখা হচ্ছে / শেষ), একটাই মূল বাটন — পরের জনের "ডাকুন",
// যাঁকে দেখছেন তাঁর "প্রেসক্রিপশন লিখুন"; দেরির অনুরোধে "ঠিক আছে"। বাকিটা "আরও"-তে (এখনই ডাকুন, এসেছেন, ফি নিন,
// আগের প্রেসক্রিপশন, পরে আসুন)। সহকারী একই সারি দেখেন, শুধু প্রেসক্রিপশন লেখা নেই (canWrite = false)।
// সার্ভার বদলায়নি: queue_today / queue_control / appointment_late_reply, chamber_checkin, assistant_chambers।
// ------------------------------------------------------------

object TodayText {
    fun tab(): String = tr("Today", "আজ")
    fun title(): String = tr("Today's patients", "আজকের রোগী")
    fun none(): String = tr("No serials today", "আজ কোনো সিরিয়াল নেই")
    fun over(): String = tr("Today's serials are over", "আজকের সিরিয়াল শেষ")
    fun waiting(): String = tr("Waiting", "অপেক্ষায়")
    fun seeing(): String = tr("Being seen", "দেখা হচ্ছে")
    fun done(): String = tr("Done", "শেষ")
    fun passed(): String = tr("Missed turn", "পালা পার হয়েছে")
    fun atEnd(): String = tr("At the end", "শেষে দেখবেন")
    fun call(): String = tr("Call", "ডাকুন")
    fun callNow(): String = tr("Call now", "এখনই ডাকুন")
    fun start(): String = tr("Start", "শুরু করুন")
    fun pause(): String = tr("Break", "বিরতি")
    fun resume(): String = tr("Resume", "আবার শুরু")
    fun arrived(): String = tr("Arrived", "এসেছেন")
    fun fee(): String = tr("Collect fee", "ফি নিন")
    fun history(): String = tr("Earlier prescriptions", "আগের প্রেসক্রিপশন")
    fun ok(): String = tr("OK", "ঠিক আছে")
    fun later(): String = tr("Come later", "পরে আসুন")

    /** ডাক্তারের ধরন অনুযায়ী লেখার বাটন (ফিজিও "পরামর্শ", সাইকোলজিস্ট "নোট") */
    fun write(type: String): String = when (type) {
        Practice.PHYSIO_TYPE -> tr("Write advice", "পরামর্শ লিখুন")
        Practice.PSYCH_TYPE -> tr("Write notes", "নোট লিখুন")
        else -> tr("Write prescription", "প্রেসক্রিপশন লিখুন")
    }

    fun state(r: QueueRow, now: Boolean): String = when {
        now -> seeing()
        r.state == "done" -> done()
        r.state == "passed" -> passed()
        r.state == "later" -> atEnd()
        else -> waiting()
    }
}

/** আজ কতজন অপেক্ষায় (ট্যাবের সংখ্যা) — যাঁকে দেখছেন তিনি বাদ */
fun todayWaiting(list: List<QueueChamber>): Int = list.sumOf { c ->
    c.waiting ?: c.rows.count { r -> r.state in setOf("waiting", "later") && !(c.queue.started && c.queue.current == r.serial) }
}

/** সারির কাজ (সার্ভারে যায় TodayPatientsTab থেকে) */
enum class TodayOp { START, PAUSE, RESUME, NEXT, SET, OK, LATER, WRITE, HISTORY, FEE, ARRIVED }

/**
 * "আজ" ট্যাব: ৩০ সেকেন্ডে একবার নতুন করে আনে (অ্যাপ সামনে থাকলে)। doctorUid = সহকারী কার হয়ে (ডাক্তার নিজে হলে null)।
 * header: ট্যাবের ওপরে (সহকারীর বেলায় ডাক্তারের নাম আর আজ চলছে/বন্ধ)।
 */
@Composable
fun TodayPatientsTab(
    doctorUid: String?,
    canWrite: Boolean,
    writeLabel: String,
    onCount: (Int) -> Unit = {},
    header: @Composable () -> Unit = {},
) {
    val resumed = rememberResumed()
    val scope = rememberCoroutineScope()
    var list by remember(doctorUid) { mutableStateOf<List<QueueChamber>?>(null) }
    var places by remember(doctorUid) { mutableStateOf<List<FeeChamber>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var arrived by remember(doctorUid) { mutableStateOf(setOf<String>()) }
    var writing by remember { mutableStateOf<RxTarget?>(null) }
    var history by remember { mutableStateOf<RxTarget?>(null) }
    var fee by remember { mutableStateOf<Pair<QueueChamber, QueueRow>?>(null) }
    val invChamber = invOn(InvoiceFeatures.key("chamber"))
    LaunchedEffect(doctorUid, resumed, reload) {
        if (!resumed) return@LaunchedEffect
        while (true) {
            runCatching { QueueApi.today(doctorUid) }
                .onSuccess { list = it; errorMsg = null; onCount(todayWaiting(it)) }
                .onFailure { e -> if (e is kotlinx.coroutines.CancellationException) throw e; if (list == null) list = emptyList(); errorMsg = e.message }
            delay(30_000L)
        }
    }
    LaunchedEffect(doctorUid, invChamber) {
        if (invChamber) runCatching { InvoiceApi.chambers(doctorUid) }.onSuccess { places = it }
    }
    fun exec(key: String, block: suspend () -> Unit) {
        scope.launch {
            busy = key
            errorMsg = null
            runCatching { block() }.onSuccess { reload++ }.onFailure { e -> if (e is kotlinx.coroutines.CancellationException) throw e; errorMsg = e.message }
            busy = null
        }
    }
    fun onOp(op: TodayOp, c: QueueChamber, r: QueueRow?) {
        when (op) {
            TodayOp.START -> exec("start" + c.chamberKey) { QueueApi.control(doctorUid, c, "start") }
            TodayOp.PAUSE -> exec("pause" + c.chamberKey) { QueueApi.control(doctorUid, c, "pause") }
            TodayOp.RESUME -> exec("resume" + c.chamberKey) { QueueApi.control(doctorUid, c, "resume") }
            TodayOp.NEXT -> exec("call" + r?.id) { QueueApi.control(doctorUid, c, "next") }
            TodayOp.SET -> if (r != null) exec("call" + r.id) { QueueApi.control(doctorUid, c, "set", r.id) }
            TodayOp.OK -> if (r != null) exec("ok" + r.id) { QueueApi.reply(r.id, "ok") }
            TodayOp.LATER -> if (r != null) exec("later" + r.id) { QueueApi.reply(r.id, "later") }
            TodayOp.WRITE -> if (r != null) writing = RxTarget(appointmentId = r.id, name = r.name)
            TodayOp.HISTORY -> if (r != null) history = RxTarget(appointmentId = r.id, name = r.name)
            TodayOp.FEE -> if (r != null) fee = c to r
            TodayOp.ARRIVED -> if (r != null) scope.launch {
                runCatching { InvoiceApi.checkin(r.id, doctorUid) }.onSuccess { arrived = arrived + r.id }.onFailure { e -> if (e is kotlinx.coroutines.CancellationException) throw e; errorMsg = e.message }
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("today_patients"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        header()
        ErrorText(errorMsg)
        val l = list
        when {
            l == null -> LoadingBox()
            l.isEmpty() -> OneLineEmpty(TodayText.none())
            else -> l.forEach { c ->
                TodayChamberCard(c, l.size > 1, busy, canWrite, writeLabel, invChamber, arrived) { op, r -> onOp(op, c, r) }
            }
        }
        // সরাসরি আসা রোগীর ফি আর আজকের রসিদ (inv_chamber) — সারির "ফি নিন" ওপরেই, তাই এখানে আর সারি নয়
        ChamberFeesButton(doctorUid, withQueue = false)
        // অনলাইনের ফি মেলানো (লাইফলো পে)
        BizPaymentsButton("chamber", doctorUid, assistant = doctorUid != null)
    }
    writing?.let { t -> RxWriteFor(t) { writing = null; reload++ } }
    history?.let { t -> PatientHistoryFor(t, canWrite) { history = null } }
    fee?.let { (c, r) ->
        val defaultFee = places.firstOrNull { it.chamberKey == c.chamberKey }?.takeIf { !it.noFee }?.fee?.filter { it.isDigit() || it in '০'..'৯' }.orEmpty()
        ChamberFeeDialog(r.id, r.name, c.chamberKey, c.date, doctorUid, defaultFee) { fee = null; reload++ }
    }
}

/** একটা চেম্বারের আজকের সারি — কার্ড (ডেটা আর কাজ বাইরে থেকে, টেস্টে সরাসরি আঁকা যায়) */
@Composable
fun TodayChamberCard(
    c: QueueChamber,
    many: Boolean,
    busy: String?,
    canWrite: Boolean,
    writeLabel: String,
    invChamber: Boolean,
    arrived: Set<String>,
    onOp: (TodayOp, QueueRow?) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val q = c.queue
    val rows = c.rows.filter { it.state != "cancelled" }
    val waiting = rows.count { r -> r.state in setOf("waiting", "later") && !(q.started && q.current == r.serial) }
    Card(Modifier.fillMaxWidth().testTag("today_chamber"), colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (many) c.label else TodayText.title(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    // এক লাইনে: এখন কত নম্বর, কতজন অপেক্ষায়, গড় সময় (আগের তিনটা বড় সংখ্যা সারিতেই দেখা যায়)
                    Text(
                        listOfNotNull(
                            if (q.started && q.current > 0) tr("Now: #${q.current}", "এখন: ${num(q.current)} নং") else null,
                            if (waiting > 0) tr("$waiting waiting", "অপেক্ষায় ${num(waiting)} জন") else null,
                            tr("~${c.avgMin} min each", "গড় ${num(c.avgMin)} মিনিট"),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    )
                }
                val status = when {
                    q.paused -> TodayText.pause()
                    q.finished -> TodayText.done()
                    q.started -> tr("Running", "চলছে")
                    else -> tr("Not started", "শুরু হয়নি")
                }
                Tag(
                    status,
                    if (q.paused) cs.secondaryContainer else if (q.started && !q.finished) cs.tertiaryContainer else cs.surfaceVariant,
                    if (q.paused) cs.onSecondaryContainer else if (q.started && !q.finished) cs.onTertiaryContainer else cs.onSurfaceVariant,
                )
            }
            // চেম্বারের কাজ: শুরু (একবার), তারপর শুধু বিরতি/আবার শুরু — রোগী ডাকা সারিতেই
            if (!q.started) {
                if (rows.isNotEmpty()) PrimaryAction(TodayText.start(), R.drawable.ic_chevron_right, { onOp(TodayOp.START, null) }, Modifier.fillMaxWidth().testTag("today_start"), busy = busy == "start" + c.chamberKey, enabled = busy == null)
            } else if (!q.finished || q.next != null) {
                if (q.paused) {
                    OutlinedButton(onClick = { onOp(TodayOp.RESUME, null) }, enabled = busy == null, modifier = Modifier.heightIn(min = 48.dp)) {
                        ButtonContent(busy == "resume" + c.chamberKey, TodayText.resume(), R.drawable.ic_sync)
                    }
                } else {
                    OutlinedButton(onClick = { onOp(TodayOp.PAUSE, null) }, enabled = busy == null, modifier = Modifier.heightIn(min = 48.dp)) {
                        ButtonContent(busy == "pause" + c.chamberKey, TodayText.pause(), R.drawable.ic_schedule)
                    }
                }
            }
            rows.forEach { r -> TodayRow(c, r, busy, canWrite, writeLabel, invChamber, r.id in arrived) { op -> onOp(op, r) } }
            if (q.started && q.next == null && rows.none { q.current == it.serial && it.state != "done" }) OneLineEmpty(TodayText.over())
        }
    }
}

@Composable
private fun TodayRow(
    c: QueueChamber,
    r: QueueRow,
    busy: String?,
    canWrite: Boolean,
    writeLabel: String,
    invChamber: Boolean,
    isArrived: Boolean,
    onOp: (TodayOp) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val q = c.queue
    val now = q.started && q.current == r.serial
    val waiting = r.state in setOf("waiting", "later") && !now
    val asked = r.late?.status == "asked" && waiting
    val isNext = q.started && waiting && q.next == r.serial
    val dim = r.state == "done" || r.state == "passed"
    val free = busy == null
    Surface(
        color = if (now) cs.tertiaryContainer else cs.surfaceContainerHighest,
        contentColor = if (now) cs.onTertiaryContainer else cs.onSurface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().testTag("today_row"),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = cs.primaryContainer, contentColor = cs.onPrimaryContainer, shape = RoundedCornerShape(10.dp)) {
                    Text(num(r.serial), fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        r.name.ifBlank { tr("Deleted account", "মুছে ফেলা অ্যাকাউন্ট") },
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (dim) cs.onSurfaceVariant else androidx.compose.ui.graphics.Color.Unspecified,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Tag(
                            TodayText.state(r, now),
                            if (now) cs.tertiary else if (waiting) cs.secondaryContainer else cs.surfaceVariant,
                            if (now) cs.onTertiary else if (waiting) cs.onSecondaryContainer else cs.onSurfaceVariant,
                        )
                        if (isArrived) Tag(TodayText.arrived(), cs.primaryContainer, cs.onPrimaryContainer)
                        val l = r.late
                        if (l != null && r.state != "done" && (asked || l.status != "asked")) {
                            Tag(tr("${l.min} min late", "${num(l.min)} মিনিট দেরি হবে"), if (asked) cs.errorContainer else cs.secondaryContainer, if (asked) cs.onErrorContainer else cs.onSecondaryContainer)
                        }
                    }
                    if (asked && !r.late?.note.isNullOrBlank()) Text(r.late?.note.orEmpty(), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
            // একটাই মূল কাজ
            val primary: Pair<String, TodayOp>? = when {
                asked -> TodayText.ok() to TodayOp.OK
                now && canWrite -> writeLabel to TodayOp.WRITE
                isNext -> TodayText.call() to TodayOp.NEXT
                else -> null
            }
            val callNow = q.started && waiting && !isNext
            val hasMore = asked || callNow || invChamber || canWrite
            ActionRow(
                key = c.chamberKey + r.id,
                showPrimary = primary != null,
                showMore = hasMore,
                primary = {
                    primary?.let { (label, op) ->
                        PrimaryAction(
                            label, if (op == TodayOp.WRITE) R.drawable.ic_edit_note else if (op == TodayOp.OK) R.drawable.ic_check_circle else R.drawable.ic_chevron_right,
                            { onOp(op) }, Modifier.testTag("today_primary"),
                            busy = busy == "call" + r.id || busy == "ok" + r.id, enabled = free,
                        )
                    }
                },
                more = {
                    if (asked) SmallAction(TodayText.later(), R.drawable.ic_schedule, { onOp(TodayOp.LATER) }, enabled = free)
                    if (callNow) SmallAction(TodayText.callNow(), R.drawable.ic_chevron_right, { onOp(TodayOp.SET) }, enabled = free)
                    if (invChamber && !isArrived && r.state != "done") SmallAction(TodayText.arrived(), R.drawable.ic_check_circle, { onOp(TodayOp.ARRIVED) })
                    if (invChamber) SmallAction(TodayText.fee(), R.drawable.ic_payments, { onOp(TodayOp.FEE) }, modifier = Modifier.testTag("fee_collect"))
                    if (canWrite && !(now && primary?.second == TodayOp.WRITE)) SmallAction(writeLabel, R.drawable.ic_edit_note, { onOp(TodayOp.WRITE) })
                    if (canWrite) SmallAction(TodayText.history(), R.drawable.ic_history, { onOp(TodayOp.HISTORY) })
                },
            )
        }
    }
}
