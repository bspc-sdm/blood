package com.bspc.blood

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

// ------------------------------------------------------------
// প্যাথলজির পরীক্ষা ও দাম, আর বাসা থেকে নমুনা (সার্ভার: server/lib/labprices.js; ওয়েবের lib/labprice.ts-এর মতো)।
// দাম বসানো শুধু ওয়েবে (মালিকের নিয়ম: বসে করার কাজ ওয়েবে) — অ্যাপে রোগী দেখেন আর পাঠান, প্যাথলজি বাসার সময় নিশ্চিত করে।
// ------------------------------------------------------------

/** সবার দেখা দামের তালিকা (চালু পরীক্ষা) */
data class PriceItem(val code: String?, val name: String, val price: Int, val tat: String)
data class LabPrices(val items: List<PriceItem>, val discountPct: Int?, val homeFee: Int?)

/** অর্ডারে রাখা পাঠানোর সময়ের দাম */
data class SnapItem(val test: String, val name: String, val price: Int, val tat: String)
data class PriceSnap(val items: List<SnapItem>, val missing: List<String>, val subtotal: Int, val discountPct: Int?, val estimate: Int)

/** অর্ডারের "বাসা থেকে" অংশ — ঠিকানা-ফোন শুধু রোগী আর (অর্ডার চালু থাকলে) সেই প্যাথলজি পান */
data class LabHome(
    val status: String, val date: Long, val slot: String, val note: String, val fee: Int?, val visitDate: Long, val time: String,
    val confirmNote: String, val declineNote: String, val address: String, val phone: String,
)

/** রোগীর পাঠানো অনুরোধ (day: ০ আজ, ১ কাল, ২ পরশু) */
data class HomeRequest(val address: String, val phone: String, val day: Int, val slot: String, val note: String)

/** rx_lab_options-এর একটা প্যাথলজি */
data class LabOption(
    val lab: Lab, val suggested: Boolean, val priced: Boolean, val have: Int?, val total: Int, val missing: List<String>,
    val subtotal: Int?, val discountPct: Int?, val estimate: Int?, val homeFee: Int?, val tat: String,
)
data class LabOptions(val items: List<LabOption>, val lastAddress: String, val lastPhone: String)

object LabPriceText {
    private const val DAY = 24L * 60 * 60 * 1000
    val SLOTS = listOf("early", "morning", "afternoon")
    private val SLOT_HOURS = mapOf("early" to (7 to 9), "morning" to (9 to 12), "afternoon" to (15 to 18))

    /** ৳১,২৫০ */
    fun taka(v: Int): String = PharmacyBooks.taka(v.toDouble())

    fun slot(s: String): String = when (s) {
        "early" -> tr("7–9 AM", "সকাল ৭–৯")
        "morning" -> tr("9 AM–12 PM", "সকাল ৯–১২")
        "afternoon" -> tr("3–6 PM", "বিকেল ৩–৬")
        else -> ""
    }

    /** ঢাকার আজকের তারিখ, UTC মধ্যরাত হিসেবে (সার্ভারের dhakaToday) */
    fun today(): Long = Math.floorDiv(System.currentTimeMillis() + Rules.DHAKA_OFFSET_MS, DAY) * DAY

    fun dayWord(date: Long): String = when (Math.round((date - today()).toDouble() / DAY).toInt()) {
        0 -> tr("Today", "আজ")
        1 -> tr("Tomorrow", "কাল")
        2 -> tr("Day after", "পরশু")
        else -> Rules.formatDate(date)
    }

    /** "08:30" → "সকাল ৮:৩০" */
    fun hhmm(t: String): String {
        val p = t.split(":")
        val h = p.getOrNull(0)?.toIntOrNull() ?: return t
        return Rules.formatTime(h, p.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    fun wanted(h: LabHome): String = listOf(dayWord(h.date), slot(h.slot)).filter { it.isNotBlank() }.joinToString(" · ")
    fun visit(h: LabHome): String = if (h.time.isBlank()) "" else dayWord(if (h.visitDate > 0) h.visitDate else h.date) + " " + hhmm(h.time)

    /** রোগীর দিক থেকে অবস্থা */
    fun homeStatusPatient(h: LabHome): String = when (h.status) {
        "confirmed" -> tr("Coming ${visit(h)}", "${visit(h)} আসবে")
        "declined" -> tr("Can't come home — give the sample at the lab", "বাসায় আসবে না — প্যাথলজিতে গিয়ে নমুনা দিন")
        else -> tr("Sample from home · ${wanted(h)} — waiting for the time", "বাসা থেকে নমুনা · ${wanted(h)} — সময় জানাবে")
    }

    /** প্যাথলজির দিক থেকে ছোট লাইন */
    fun homeLineLab(h: LabHome): String = when (h.status) {
        "confirmed" -> tr("From home · ${visit(h)}", "বাসা থেকে · ${visit(h)}")
        "declined" -> tr("Home visit: no", "বাসায় যাওয়া: না")
        else -> tr("From home · ${wanted(h)} — confirm the time", "বাসা থেকে · ${wanted(h)} — সময় জানান")
    }

    fun cover(have: Int, total: Int) = tr("$have of $total tests", "${num(total)}টার ${num(have)}টা পরীক্ষা")
    fun estimate(v: Int) = tr("About ${taka(v)}", "আনুমানিক ${taka(v)}")
    fun discount(d: Int?) = if (d == null || d == 0) "" else tr("${d}% off all tests", "সব পরীক্ষায় ${num(d)}% ছাড়")
    fun homeFee(fee: Int?) = when (fee) {
        null -> ""
        0 -> tr("Sample from home: free", "বাসা থেকে নমুনা: ফ্রি")
        else -> tr("Sample from home: ${taka(fee)}", "বাসা থেকে নমুনা: ${taka(fee)}")
    }
    fun noPrice() = tr("Price not known — ask on the phone", "দাম জানা নেই — ফোনে জেনে নিন")

    /** অর্ডারে রাখা দামের এক লাইন: "আনুমানিক ৳২,৩৪০ (১০% ছাড়) + বাসা থেকে নমুনা: ৳১৫০" */
    fun snapLine(p: PriceSnap, home: LabHome?): String =
        estimate(p.estimate) + (if (p.discountPct != null && p.discountPct > 0) " (" + tr("${p.discountPct}% off", "${num(p.discountPct)}% ছাড়") + ")" else "") +
            (if (home?.fee != null && home.status != "declined") " + " + homeFee(home.fee) else "")

    /** আজকের পেরিয়ে যাওয়া সময় */
    fun slotPassed(day: Int, s: String): Boolean {
        if (day != 0) return false
        val (h, m) = Rules.dhakaHourMinute(System.currentTimeMillis())
        return h * 60 + m >= (SLOT_HOURS[s]?.second ?: 24) * 60
    }

    /** নিশ্চিত করার সময়: ভাগের ভেতরে আধা ঘণ্টা পরপর */
    fun slotTimes(s: String): List<String> {
        val (a, b) = SLOT_HOURS[s] ?: return emptyList()
        return (a * 60 until b * 60 step 30).map { String.format(java.util.Locale.US, "%02d:%02d", it / 60, it % 60) }
    }
}

/**
 * রোগী (বা কেয়ারগিভার): বাসা থেকে নমুনা নেয় এমন প্যাথলজিতে পাঠানো — প্যাথলজিতে গিয়ে, অথবা বাসা থেকে
 * (ঠিকানা আগের বারেরটা, ফোন প্রোফাইলের; দিন আজ/কাল/পরশু; সময়ের ভাগ; ছোট নোট)।
 */
@Composable
fun LabSendDialog(
    opt: LabOption, lastAddress: String, lastPhone: String, profilePhone: String,
    onDismiss: () -> Unit, onSend: suspend (HomeRequest?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var home by remember { mutableStateOf(true) }
    var address by remember { mutableStateOf(lastAddress) }
    var phone by remember { mutableStateOf(lastPhone.ifBlank { profilePhone }) }
    var day by remember { mutableIntStateOf(1) }
    var slot by remember { mutableStateOf("early") }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val slots = LabPriceText.SLOTS.filter { !LabPriceText.slotPassed(day, it) }
    val pickedSlot = if (slot in slots) slot else slots.firstOrNull()
    FormDialog(
        title = opt.lab.name,
        onDismiss = { if (!busy) onDismiss() },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (opt.priced) LabPriceText.cover(opt.have ?: 0, opt.total) + " · " + LabPriceText.estimate(opt.estimate ?: 0) else LabPriceText.noPrice(),
                    style = MaterialTheme.typography.bodyMedium, fontWeight = if (opt.priced) FontWeight.SemiBold else FontWeight.Normal,
                )
                ChipRows(listOf("home", "lab"), setOf(if (home) "home" else "lab"), perRow = 1, label = {
                    if (it == "home") tr("Come and take the sample from home", "বাসা থেকে নমুনা নিতে আসবে") else tr("I'll go to the lab", "প্যাথলজিতে গিয়ে দেব")
                }) { home = it == "home" }
                if (home) {
                    if (opt.homeFee != null) IconLine(R.drawable.ic_payments, LabPriceText.homeFee(opt.homeFee))
                    OutlinedTextField(address, { address = it.take(200) }, label = { Text(tr("Home address", "বাসার ঠিকানা")) }, minLines = 2, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        phone, { phone = it.take(20) }, label = { Text(tr("Mobile number", "মোবাইল নম্বর")) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth(),
                    )
                    Text(tr("When", "কবে"), style = MaterialTheme.typography.labelLarge)
                    ChipRows(listOf("0", "1", "2"), setOf(day.toString()), perRow = 3, label = { LabPriceText.dayWord(LabPriceText.today() + it.toInt() * 86_400_000L) }) { day = it.toInt() }
                    if (slots.isEmpty()) SmallNote(tr("No time left today — choose tomorrow", "আজ আর সময় নেই — কাল বেছে নিন"))
                    else ChipRows(slots, setOfNotNull(pickedSlot), perRow = 3, label = { LabPriceText.slot(it) }) { slot = it }
                    OutlinedTextField(note, { note = it.take(120) }, label = { Text(tr("Note (optional)", "নোট (ঐচ্ছিক)")) }, placeholder = { Text(tr("e.g. I will be fasting", "যেমন: খালি পেটে থাকব")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    SmallNote(tr("The lab will confirm the time.", "প্যাথলজি সময় নিশ্চিত করে জানাবে।"))
                }
                ErrorText(errorMsg)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    busy = true
                    errorMsg = null
                    runCatching { onSend(if (home) HomeRequest(address.trim(), phone.trim(), day, pickedSlot ?: "early", note.trim()) else null) }
                        .onFailure { errorMsg = it.message }
                    busy = false
                }
            }, enabled = !busy && (!home || (address.isNotBlank() && phone.isNotBlank() && pickedSlot != null))) { ButtonContent(busy, tr("Send", "পাঠান"), R.drawable.ic_send) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancel", "বাতিল")) } },
    )
}

/** প্যাথলজি: বাসা থেকে নমুনার সময় নিশ্চিত — দিন, সময়ের ভাগ, ঠিক সময়, নোট; রোগী সাথে সাথে জানেন */
@Composable
fun HomeConfirmDialog(order: LabOrder, onDismiss: () -> Unit, onDone: () -> Unit) {
    val h = order.home ?: return
    val scope = rememberCoroutineScope()
    val today = remember { LabPriceText.today() }
    val days = (0..2).map { today + it * 86_400_000L }
    val startDay = if (h.status == "confirmed" && h.visitDate >= today) h.visitDate else if (h.date in days) h.date else today + 86_400_000L
    var date by remember { mutableLongStateOf(startDay) }
    val startSlot = LabPriceText.SLOTS.firstOrNull { h.time.isNotBlank() && h.time in LabPriceText.slotTimes(it) } ?: h.slot.ifBlank { "morning" }
    var slot by remember { mutableStateOf(startSlot) }
    var time by remember { mutableStateOf(if (h.time in LabPriceText.slotTimes(startSlot)) h.time else LabPriceText.slotTimes(startSlot).firstOrNull().orEmpty()) }
    var note by remember { mutableStateOf(h.confirmNote) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = tr("Confirm the time", "সময় নিশ্চিত করুন"),
        onDismiss = { if (!busy) onDismiss() },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SmallNote(tr("Wants: ", "চেয়েছেন: ") + LabPriceText.wanted(h))
                Text(tr("Day", "দিন"), style = MaterialTheme.typography.labelLarge)
                ChipRows(days.map { it.toString() }, setOf(date.toString()), perRow = 3, label = { LabPriceText.dayWord(it.toLong()) }) { date = it.toLong() }
                Text(tr("Time", "সময়"), style = MaterialTheme.typography.labelLarge)
                ChipRows(LabPriceText.SLOTS, setOf(slot), perRow = 3, label = { LabPriceText.slot(it) }) {
                    slot = it
                    time = LabPriceText.slotTimes(it).firstOrNull().orEmpty()
                }
                ChipRows(LabPriceText.slotTimes(slot), setOf(time), perRow = 4, label = { LabPriceText.hhmm(it) }) { time = it }
                OutlinedTextField(note, { note = it.take(120) }, label = { Text(tr("Note for the patient (optional)", "রোগীর জন্য নোট (ঐচ্ছিক)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(LabPriceText.dayWord(date) + " " + LabPriceText.hhmm(time), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                ErrorText(errorMsg)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    busy = true
                    errorMsg = null
                    runCatching { RxNet.homeConfirm(order.id, date, time, note.trim()) }.onSuccess { onDone() }.onFailure { errorMsg = it.message }
                    busy = false
                }
            }, enabled = !busy && time.isNotBlank()) { ButtonContent(busy, tr("Tell the patient", "রোগীকে জানান"), R.drawable.ic_send) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancel", "বাতিল")) } },
    )
}

/** প্যাথলজি: বাসায় যেতে পারব না — অর্ডার থাকে (রোগী এসে নমুনা দেবেন), রোগী জানেন */
@Composable
fun HomeDeclineDialog(order: LabOrder, onDismiss: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = tr("Can't go to the home?", "বাসায় যেতে পারবেন না?"),
        onDismiss = { if (!busy) onDismiss() },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SmallNote(tr("The request stays — the patient can come to the lab.", "অনুরোধ থাকবে — রোগী প্যাথলজিতে এসে নমুনা দেবেন।"))
                OutlinedTextField(note, { note = it.take(120) }, label = { Text(tr("Reason (optional)", "কারণ (ঐচ্ছিক)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                ErrorText(errorMsg)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    busy = true
                    errorMsg = null
                    runCatching { RxNet.homeDecline(order.id, note.trim()) }.onSuccess { onDone() }.onFailure { errorMsg = it.message }
                    busy = false
                }
            }, enabled = !busy) { ButtonContent(busy, tr("Can't go", "যেতে পারব না")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Back", "ফিরে যান")) } },
    )
}
