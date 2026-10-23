@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.bspc.blood

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/*
 * রসিদ দেওয়ার ফর্ম (ফোনে): প্যাথলজির বিল, ডাক্তারের ফি, অ্যাম্বুলেন্সের "যাত্রা শেষ", অনলাইন অর্ডার পৌঁছানো।
 * দেওয়া হলে InvoiceNav.issued — রসিদ খোলে (শেয়ার/প্রিন্ট), তালিকা নতুন করে আসে।
 * টাকা: খালি রাখলে পুরোটা দেওয়া ধরা হয় (ক্যাশ, চুক্তির ডিফল্ট); কম লিখলে বাকি থাকে, পরে "বাকি টাকা নিন"।
 */

/** ফর্মের এক লাইন (লেখা অবস্থায়) */
data class LineDraft(val name: String = "", val qty: String = "1", val price: String = "", val code: String = "")

object LineDrafts {
    /** ভরা লাইনগুলো; কোনোটা ভুল হলে null (নাম আছে কিন্তু দাম/পরিমাণ ঠিক নয়) */
    fun toLines(drafts: List<LineDraft>): List<InvLine>? {
        val out = ArrayList<InvLine>()
        for (d in drafts) {
            if (d.name.isBlank() && d.price.isBlank()) continue
            val q = InvoiceMath.parse(d.qty.ifBlank { "1" }) ?: return null
            val p = InvoiceMath.parse(d.price) ?: return null
            if (d.name.isBlank() || q <= 0.0 || p < 0.0) return null
            out += InvLine(d.name.trim(), q, "", p, 0.0, InvoiceMath.lineAmount(q, p, 0.0), d.code)
        }
        return out
    }

    /** প্যাথলজির অর্ডার থেকে: দাম জানা পরীক্ষা দামসহ, বাকি পরীক্ষা দাম ফাঁকা */
    fun fromLabOrder(o: LabOrder): List<LineDraft> {
        val snap = o.prices
        val priced = snap?.items.orEmpty().map { LineDraft(it.name.ifBlank { it.test }, "1", it.price.toString(), it.test) }
        val names = priced.map { it.name }.toSet()
        val rest = (snap?.missing ?: o.tests.filter { t -> snap?.items.orEmpty().none { it.test == t || it.name == t } })
            .filter { it !in names }.map { LineDraft(it, "1", "") }
        return (priced + rest).ifEmpty { listOf(LineDraft()) }
    }
}

/** এই ফোনে শেষবারের ফি (ধরন অনুযায়ী) — পরের বার ঘরে আগে থেকে */
private object FeeMemory {
    private const val FILE = "invoice_prefs"
    fun get(context: android.content.Context, key: String): String =
        runCatching { context.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE).getString(key, "") ?: "" }.getOrDefault("")
    fun put(context: android.content.Context, key: String, v: String) {
        runCatching { context.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE).edit().putString(key, v).apply() }
    }
}

/** লাইনের তালিকা: নাম, কয়টা, দাম (প্রতিটা) — যোগ/বাদ */
@Composable
fun LinesEditor(drafts: List<LineDraft>, onChange: (List<LineDraft>) -> Unit, bn: Boolean = isBangla) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        drafts.forEachIndexed { i, d ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        d.name, { v -> onChange(drafts.toMutableList().also { it[i] = d.copy(name = v.take(80)) }) },
                        label = { Text(tr("Item", "বিবরণ")) }, singleLine = true, modifier = Modifier.weight(1f),
                    )
                    if (drafts.size > 1) {
                        IconButton(onClick = { onChange(drafts.toMutableList().also { it.removeAt(i) }) }) { AppIcon(R.drawable.ic_close, tr("Remove", "বাদ")) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        d.qty, { v -> onChange(drafts.toMutableList().also { it[i] = d.copy(qty = v.take(6)) }) },
                        label = { Text(tr("Qty", "কয়টা")) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.width(88.dp),
                    )
                    OutlinedTextField(
                        d.price, { v -> onChange(drafts.toMutableList().also { it[i] = d.copy(price = v.take(9)) }) },
                        label = { Text(tr("Price (৳)", "দাম (৳)")) }, singleLine = true, isError = d.name.isNotBlank() && (InvoiceMath.parse(d.price) ?: -1.0) < 0,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
                    )
                    val q = InvoiceMath.parse(d.qty.ifBlank { "1" })
                    val p = InvoiceMath.parse(d.price)
                    if (q != null && p != null && q > 0 && p >= 0) Text(InvoiceText.money(InvoiceMath.lineAmount(q, p, 0.0), bn), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
            }
            if (i < drafts.lastIndex) HorizontalDivider()
        }
        SmallAction(tr("Add line", "লাইন যোগ করুন"), R.drawable.ic_add, { onChange(drafts + LineDraft()) })
    }
}

/** এখন কত পেলেন (খালি = পুরোটা) আর কীভাবে */
@Composable
fun PaidNowFields(total: Double, paid: String, onPaid: (String) -> Unit, method: String, onMethod: (String) -> Unit, ref: String? = null, onRef: (String) -> Unit = {}) {
    val p = InvoiceMath.parse(paid)
    val bad = paid.isNotBlank() && (p == null || p < 0 || p > total + 0.004)
    MoneyField(
        paid, onPaid, tr("Paid now (৳) — empty = full", "এখন দিলেন (৳) — খালি = পুরোটা"), isError = bad,
        supporting = if (p != null && p >= 0 && p < total - 0.004) tr("Due: ", "বাকি থাকবে: ") + InvoiceText.money(total - p, isBangla) else null,
    )
    Text(tr("How was it paid?", "কীভাবে পেলেন?"), style = MaterialTheme.typography.labelLarge)
    PayMethodChips(method, onMethod)
    if (ref != null && method != "cash") {
        OutlinedTextField(ref, { onRef(it.take(40)) }, label = { Text(tr("Transaction ID (optional)", "লেনদেনের নম্বর (ঐচ্ছিক)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
}

/** খালি = পুরোটা (null); ভুল হলে -1 */
private fun paidAmount(paid: String, total: Double): Double? {
    if (paid.isBlank()) return null
    val p = InvoiceMath.parse(paid) ?: return null
    return if (p < 0 || p > total + 0.004) -1.0 else p
}

// ------------------------------------------------------------
// প্যাথলজি: বিল (অর্ডার থেকে, বা সরাসরি আসা রোগী)
// ------------------------------------------------------------

@Composable
fun LabBillDialog(order: LabOrder?, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var drafts by remember { mutableStateOf(order?.let { LineDrafts.fromLabOrder(it) } ?: listOf(LineDraft())) }
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    val homeFee = order?.home?.takeIf { it.status != "declined" }?.fee
    var fee by remember { mutableStateOf(homeFee?.toString() ?: "") }
    var disc by remember { mutableStateOf("") }
    var paid by remember { mutableStateOf("") }
    var method by remember { mutableStateOf("cash") }
    var ref by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val lines = LineDrafts.toLines(drafts)
    // অর্ডারের ছাড় (%) থাকলে প্রথমবার বিলের ছাড়ে
    LaunchedEffect(Unit) {
        val pct = order?.prices?.discountPct
        if (pct != null && pct > 0 && lines != null) disc = Math.round(InvoiceMath.subtotal(lines) * pct / 100.0).toString()
    }
    val feeV = InvoiceMath.parse(fee)?.takeIf { it > 0 }
    val fees = if (feeV != null) listOf(InvFee(tr("Home collection", "বাসা থেকে নমুনা"), feeV)) else emptyList()
    val discV = InvoiceMath.parse(disc)?.takeIf { it > 0 } ?: 0.0
    val total = if (lines != null) InvoiceMath.total(lines, discV, fees) else 0.0
    val pay = paidAmount(paid, total)
    val ok = lines != null && lines.isNotEmpty() && (order != null || name.isNotBlank()) && (pay == null || pay >= 0) &&
        (fee.isBlank() || (InvoiceMath.parse(fee) ?: -1.0) >= 0) && (disc.isBlank() || (InvoiceMath.parse(disc) ?: -1.0) in 0.0..(lines.sumOf { it.amount } + fees.sumOf { it.amount }))
    FormDialog(
        title = if (order != null) tr("Bill — ", "বিল — ") + order.patientName.ifBlank { tr("patient", "রোগী") } else tr("New bill (walk-in)", "নতুন বিল (সরাসরি আসা রোগী)"),
        onDismiss = { if (!busy) onDismiss() },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (order == null) {
                    OutlinedTextField(name, { name = it.take(60) }, label = { Text(tr("Patient's name", "রোগীর নাম")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        phone, { phone = it.filter { c -> c.isDigit() || c in '০'..'৯' || c == '+' }.take(14) }, label = { Text(tr("Mobile (optional)", "মোবাইল (ঐচ্ছিক)")) },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    SmallNote(tr("Prices are from your price list when the test was sent — change them if needed.", "দাম পরীক্ষা পাঠানোর সময়ের তালিকা থেকে — দরকার হলে বদলান।"))
                }
                LinesEditor(drafts, { drafts = it })
                MoneyField(fee, { fee = it }, tr("Home collection fee (৳, optional)", "বাসা থেকে নমুনার ফি (৳, ঐচ্ছিক)"))
                MoneyField(disc, { disc = it }, tr("Discount on the bill (৳, optional)", "বিলে ছাড় (৳, ঐচ্ছিক)"))
                Text(tr("Total: ", "মোট: ") + InvoiceText.money(total, isBangla), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                PaidNowFields(total, paid, { paid = it }, method, { method = it }, ref, { ref = it })
                ErrorText(errorMsg)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val l = lines ?: return@TextButton
                scope.launch {
                    busy = true
                    errorMsg = null
                    runCatching { InvoiceApi.labBill(order?.id, l, name, phone, fees, discV, pay, method, ref) }
                        .onSuccess { onDismiss(); InvoiceNav.issued(it) }
                        .onFailure { errorMsg = it.message }
                    busy = false
                }
            }, enabled = !busy && ok, modifier = Modifier.testTag("lab_bill_issue")) { ButtonContent(busy, tr("Give receipt", "রসিদ দিন")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancel", "বাতিল")) } },
    )
}

/** প্যাথলজির "বিল" ট্যাব: সরাসরি আসা রোগীর নতুন বিল আর রসিদের তালিকা */
@Composable
fun LabBillsTab(labId: String) {
    var newBill by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = { newBill = true }, modifier = Modifier.fillMaxWidth().testTag("lab_bill_new")) {
            ButtonContent(false, tr("New bill (walk-in)", "নতুন বিল (সরাসরি আসা রোগী)"), R.drawable.ic_add)
        }
        SmallNote(tr("For tests sent in the app, use \"Bill\" on the request.", "অ্যাপে পাঠানো পরীক্ষার বিল অনুরোধের \"বিল\" থেকে দিন।"))
        BizInvoicesPanel("lab", labId)
    }
    if (newBill) LabBillDialog(null) { newBill = false }
}

// ------------------------------------------------------------
// ডাক্তার/সহকারী: ফি নেওয়া
// ------------------------------------------------------------

/**
 * appointmentId থাকলে সেই অ্যাপয়েন্টমেন্টের ফি; না থাকলে সরাসরি আসা রোগী (নাম, ফোন; chamberKey + date)।
 * ফি নিজে লিখবেন — এই ফোনে শেষবারেরটা (ধরন অনুযায়ী) আগে থেকে বসে।
 */
@Composable
fun ChamberFeeDialog(appointmentId: String?, patientName: String, chamberKey: String = "", date: Long = 0L, doctorUid: String? = null, defaultFee: String = "", onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var visit by remember { mutableStateOf("new") }
    var amount by remember { mutableStateOf(FeeMemory.get(context, "fee_new").ifBlank { defaultFee }) }
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var method by remember { mutableStateOf("cash") }
    var ref by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val a = InvoiceMath.parse(amount)
    val ok = a != null && a > 0 && (appointmentId != null || name.isNotBlank())
    FormDialog(
        title = tr("Collect fee", "ফি নিন") + if (appointmentId != null && patientName.isNotBlank()) " — $patientName" else "",
        onDismiss = { if (!busy) onDismiss() },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (appointmentId == null) {
                    OutlinedTextField(name, { name = it.take(60) }, label = { Text(tr("Patient's name", "রোগীর নাম")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        phone, { phone = it.filter { c -> c.isDigit() || c in '০'..'৯' || c == '+' }.take(14) }, label = { Text(tr("Mobile (optional)", "মোবাইল (ঐচ্ছিক)")) },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(tr("Visit", "কীসের জন্য"), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    InvoiceKinds.VISITS.forEach { v ->
                        FilterChip(selected = visit == v, onClick = {
                            visit = v
                            FeeMemory.get(context, "fee_$v").takeIf { it.isNotBlank() }?.let { amount = it }
                        }, label = { Text(InvoiceKinds.visit(v)) })
                    }
                }
                MoneyField(amount, { amount = it }, tr("Fee (৳)", "ফি (৳)"), isError = amount.isNotBlank() && (a == null || a <= 0), modifier = Modifier.testTag("chamber_fee_amount"))
                Text(tr("How was it paid?", "কীভাবে পেলেন?"), style = MaterialTheme.typography.labelLarge)
                PayMethodChips(method) { method = it }
                if (method != "cash") {
                    OutlinedTextField(ref, { ref = it.take(40) }, label = { Text(tr("Transaction ID (optional)", "লেনদেনের নম্বর (ঐচ্ছিক)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                ErrorText(errorMsg)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val v = a ?: return@TextButton
                scope.launch {
                    busy = true
                    errorMsg = null
                    runCatching { InvoiceApi.chamberFee(appointmentId, name, phone, chamberKey, date, visit, v, method, ref, doctorUid) }
                        .onSuccess {
                            FeeMemory.put(context, "fee_$visit", amount)
                            onDismiss()
                            InvoiceNav.issued(it)
                        }
                        .onFailure { errorMsg = it.message }
                    busy = false
                }
            }, enabled = !busy && ok, modifier = Modifier.testTag("chamber_fee_issue")) { ButtonContent(busy, tr("Give receipt", "রসিদ দিন")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancel", "বাতিল")) } },
    )
}

/**
 * ডাক্তারের অ্যাপয়েন্টমেন্টের কার্ডে (inv_chamber চালু থাকলে): রসিদ থাকলে "রসিদ", না থাকলে নিশ্চিত/সম্পন্ন সামনাসামনি
 * অ্যাপয়েন্টমেন্টে "ফি নিন"। অনলাইনের ফির রসিদ bKash নিশ্চিত করলে সার্ভার নিজে দেয়।
 */
@Composable
fun ChamberFeeAction(a: Appointment) {
    if (!invOn(InvoiceFeatures.key("chamber"))) return
    if (a.invoiceId.isNotBlank()) {
        BizReceiptAction(a.invoiceId)
        return
    }
    if (a.online || (a.status != "confirmed" && a.status != "completed")) return
    var open by remember { mutableStateOf(false) }
    SmallAction(tr("Collect fee", "ফি নিন"), R.drawable.ic_payments, { open = true }, modifier = Modifier.testTag("fee_collect"))
    if (open) ChamberFeeDialog(a.id, a.otherName) { open = false }
}

/** ফির ঘরে বসানো: কার (অ্যাপয়েন্টমেন্ট, না থাকলে সরাসরি আসা), কোন চেম্বার, কোন দিন, চেম্বারের ফি */
private data class FeeTarget(val appointmentId: String?, val name: String, val chamberKey: String, val date: Long, val fee: String = "")

/**
 * আজকের সিরিয়াল থেকে ফি: প্রতিটা রোগীর পাশে "এসেছেন" (chamber_checkin) আর "ফি নিন"; উপরে প্রতিটা সামনাসামনি
 * চেম্বারের "সরাসরি আসা রোগী" (assistant_chambers — সিরিয়াল না থাকলেও); নিচে চেম্বারের রসিদ।
 * doctorUid: সহকারী কার হয়ে (ডাক্তার নিজে হলে null)।
 */
@Composable
fun ChamberFeePanel(doctorUid: String?) {
    val scope = rememberCoroutineScope()
    var queues by remember(doctorUid) { mutableStateOf<List<QueueChamber>?>(null) }
    var places by remember(doctorUid) { mutableStateOf<List<FeeChamber>>(emptyList()) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var fee by remember { mutableStateOf<FeeTarget?>(null) }
    var arrived by remember { mutableStateOf(setOf<String>()) }
    val bump = InvoiceNav.changed.intValue
    LaunchedEffect(doctorUid, bump) {
        runCatching { InvoiceApi.chambers(doctorUid) }.onSuccess { places = it }
        runCatching { QueueApi.today(doctorUid) }.onSuccess { queues = it; errorMsg = null }.onFailure { errorMsg = it.message; queues = emptyList() }
    }
    val cs = MaterialTheme.colorScheme
    val bizId = doctorUid ?: AuthRepository.currentUid.orEmpty()
    fun feeOf(key: String) = places.firstOrNull { it.chamberKey == key }?.takeIf { !it.noFee }?.fee?.filter { it.isDigit() || it in '০'..'৯' }.orEmpty()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ErrorText(errorMsg)
        if (places.isNotEmpty()) {
            Text(tr("Walk-in patient", "সরাসরি আসা রোগী"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                places.forEach { p ->
                    SmallAction(p.label, R.drawable.ic_add, { fee = FeeTarget(null, "", p.chamberKey, Rules.todayUtcMidnight(), feeOf(p.chamberKey)) }, modifier = Modifier.testTag("walkin_fee"))
                }
            }
        }
        val list = queues
        when {
            list == null -> LoadingBox()
            list.isEmpty() -> SmallNote(tr("No serials today.", "আজ কোনো সিরিয়াল নেই।"))
            else -> list.forEach { c ->
                Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(c.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        c.rows.filter { it.status != "cancelled" }.forEach { r ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(num(r.serial) + ". " + r.name.ifBlank { "-" }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                if (r.id in arrived) {
                                    Text(tr("Arrived", "এসেছেন"), style = MaterialTheme.typography.bodySmall, color = cs.primary)
                                } else {
                                    SmallAction(tr("Arrived", "এসেছেন"), R.drawable.ic_check_circle, {
                                        scope.launch {
                                            runCatching { InvoiceApi.checkin(r.id, doctorUid) }.onSuccess { arrived = arrived + r.id }.onFailure { errorMsg = it.message }
                                        }
                                    })
                                }
                                SmallAction(tr("Collect fee", "ফি নিন"), R.drawable.ic_payments, { fee = FeeTarget(r.id, r.name, c.chamberKey, c.date, feeOf(c.chamberKey)) })
                            }
                        }
                    }
                }
            }
        }
        Text(tr("Receipts", "রসিদ"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (bizId.isNotBlank()) BizInvoicesPanel("chamber", bizId)
    }
    fee?.let { t -> ChamberFeeDialog(t.appointmentId, t.name, t.chamberKey, t.date, doctorUid, t.fee) { fee = null } }
}

/** ডাক্তারের "বুকিং"-এর উপরে: "আজকের ফি ও রসিদ" — পুরো পর্দায় ChamberFeePanel */
@Composable
fun ChamberFeesButton(doctorUid: String? = null) {
    // inv_chamber বন্ধ: কিছুই নয় (পাতা আগের মতো)
    if (!invOn(InvoiceFeatures.key("chamber"))) return
    var open by remember { mutableStateOf(false) }
    // "দিনের শেষ" নোটিফিকেশন থেকে এলে সরাসরি খোলা
    val want = InvoiceNav.openFees.value
    LaunchedEffect(want) { if (want) { open = true; InvoiceNav.openFees.value = false } }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().testTag("chamber_fees")) {
        ButtonContent(false, tr("Fees & receipts", "ফি ও রসিদ"), R.drawable.ic_payments)
    }
    if (open) {
        FullScreenDialog(onDismissRequest = { open = false }) {
            Column(Modifier.fillMaxSize()) {
                BackTopBar(tr("Fees & receipts", "ফি ও রসিদ"), { open = false })
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) { ChamberFeePanel(doctorUid) }
            }
        }
    }
}

// ------------------------------------------------------------
// অ্যাম্বুলেন্স: "যাত্রা শেষ" — ভাড়া + অতিরিক্ত + কীভাবে পেলেন → রসিদ
// ------------------------------------------------------------

@Composable
fun AmbFinishDialog(r: AmbDriverReq, onDismiss: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val chart = r.suggestedFare
    var fare by remember { mutableStateOf((r.agreedFare ?: chart)?.toString() ?: "") }
    // অতিরিক্ত: কোড (oxygen/icu/night/other) → টাকা; সার্ভার প্রতিটা চার্টের মান পর্যন্ত নেয়, "other" সর্বোচ্চ ৳৫০০
    var extras by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    var method by remember { mutableStateOf("cash") }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    // লাইফলো পে (pay_ambulance): রোগী পরে বিকাশ/নগদে দেবেন — রসিদ বাকি থাকে, রোগী পেমেন্টের অনুরোধ পান
    val payOn = PayGate.rememberOn(PayGate.AMBULANCE)
    var payLater by remember { mutableStateOf(false) }
    val f = InvoiceMath.parse(fare)
    val ex = extras.mapNotNull { (code, a) -> InvoiceMath.parse(a)?.takeIf { it > 0 }?.let { InvFee(code, it) } }
    fun badExtra(code: String, a: String): Boolean {
        val v = InvoiceMath.parse(a) ?: return true
        return v <= 0 || (code == "other" && v > InvoiceKinds.AMB_OTHER_MAX)
    }
    val exBad = extras.any { (code, a) -> badExtra(code, a) }
    // সার্ভারের মতোই: ভাড়া + অতিরিক্ত, ঠিক হওয়া ভাড়ার (না থাকলে চার্টের) সাথে
    val asked = f?.let { it + ex.sumOf { e -> e.amount } }
    val limit = r.agreedFare ?: chart
    val over = InvoiceMath.overChart(asked, limit)
    val ok = f != null && f > 0 && !exBad
    FormDialog(
        title = tr("Finish trip", "যাত্রা শেষ"),
        onDismiss = { if (!busy) onDismiss() },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (r.patientName.isNotBlank()) IconLine(R.drawable.ic_person_outline, r.patientName)
                if (r.destLabel.isNotBlank()) IconLine(R.drawable.ic_local_hospital, tr("To ", "গন্তব্য: ") + r.destLabel)
                // ঠিক হওয়া ভাড়া বড় করে — রসিদের ভাড়া এর সাথে মেলানো হয়, রোগী বেশি দেখলে জানাতে পারেন (পর্যালোচনা I8)
                r.agreedFare?.let { af ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.testTag("amb_finish_agreed")) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text(tr("Agreed fare", "ঠিক হওয়া ভাড়া"), style = MaterialTheme.typography.labelLarge)
                            Text(taka(af), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text(tr("The receipt is checked against this. The patient can report if charged more.", "রসিদের ভাড়া এর সাথে মেলানো হয়। বেশি নিলে রোগী জানাতে পারবেন।"), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                // ভাড়ার চার্ট (নিজের ভাড়ার তালিকা) আর ঠিক হওয়া ভাড়া
                SmallNote(
                    listOfNotNull(
                        chart?.let { tr("Fare chart: ", "ভাড়ার চার্ট: ") + taka(it) } ?: tr("Not in your fare chart", "আপনার ভাড়ার চার্টে নেই"),
                        r.agreedFare?.let { tr("agreed ", "ঠিক হয়েছিল ") + taka(it) },
                        if (r.night) tr("night", "রাত") else null,
                    ).joinToString(" · "),
                )
                MoneyField(fare, { fare = it }, tr("Fare (৳)", "ভাড়া (৳)"), isError = fare.isNotBlank() && (f == null || f <= 0), modifier = Modifier.testTag("amb_finish_fare"))
                if (over) {
                    Text(
                        if (r.agreedFare != null) tr("Fare + extras are more than the agreed fare (${taka(limit)}). It is marked on the receipt and the patient sees it.", "ভাড়া + অতিরিক্ত ঠিক হওয়া ভাড়ার (${taka(limit)}) চেয়ে বেশি। রসিদে চিহ্ন থাকবে, রোগী দেখবেন।")
                        else tr("Fare + extras are more than your fare chart (${taka(limit)}). It is marked on the receipt and the patient sees it.", "ভাড়া + অতিরিক্ত আপনার ভাড়ার চার্টের (${taka(limit)}) চেয়ে বেশি। রসিদে চিহ্ন থাকবে, রোগী দেখবেন।"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("amb_finish_over"),
                    )
                }
                Text(tr("Extras (from your fare chart)", "অতিরিক্ত (আপনার ভাড়ার চার্ট থেকে)"), style = MaterialTheme.typography.labelLarge)
                extras.forEachIndexed { i, (code, a) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(InvoiceKinds.feeLabel(code), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        OutlinedTextField(
                            a, { v -> extras = extras.toMutableList().also { it[i] = code to v.take(7) } }, label = { Text("৳") }, singleLine = true,
                            isError = a.isNotBlank() && badExtra(code, a),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.width(110.dp),
                        )
                        IconButton(onClick = { extras = extras.toMutableList().also { it.removeAt(i) } }) { AppIcon(R.drawable.ic_close, tr("Remove", "বাদ")) }
                    }
                }
                if (extras.any { it.first == "other" }) SmallNote(tr("\"Other\" is at most ৳500 and is shown to the patient separately.", "\"অন্যান্য\" সর্বোচ্চ ৳৫০০, রোগী আলাদা করে দেখবেন।"))
                val left = InvoiceKinds.AMB_EXTRAS.filter { c -> extras.none { it.first == c } }
                if (left.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        left.forEach { c -> SmallAction(InvoiceKinds.feeLabel(c), R.drawable.ic_add, { extras = extras + (c to "") }) }
                    }
                }
                Text(
                    tr("Total: ", "মোট: ") + InvoiceText.money((f?.takeIf { it > 0 } ?: 0.0) + ex.sumOf { it.amount }, isBangla),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                )
                Text(tr("How was it paid?", "কীভাবে পেলেন?"), style = MaterialTheme.typography.labelLarge)
                if (!payLater) PayMethodChips(method) { method = it }
                if (payOn) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        androidx.compose.material3.Checkbox(payLater, { payLater = it }, modifier = Modifier.testTag("amb_pay_later"))
                        Text(tr("Patient will pay later by bKash/Nagad", "রোগী পরে বিকাশ/নগদে দেবেন"), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (payLater) SmallNote(tr("The receipt stays due until you confirm the money in your app.", "নিজের অ্যাপে টাকা মিলিয়ে নিশ্চিত না করা পর্যন্ত রসিদে বাকি থাকবে।"))
                }
                ErrorText(errorMsg)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val v = f ?: return@TextButton
                scope.launch {
                    busy = true
                    errorMsg = null
                    runCatching { InvoiceApi.ambFinish(r.id, v, ex, method, payLater = payOn && payLater) }
                        .onSuccess { inv ->
                            onDone()
                            InvoiceNav.issued(inv)
                        }
                        .onFailure { errorMsg = it.message }
                    busy = false
                }
            }, enabled = !busy && ok, modifier = Modifier.testTag("amb_finish_submit")) { ButtonContent(busy, tr("Finish & give receipt", "শেষ করে রসিদ দিন")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancel", "বাতিল")) } },
    )
}

// ------------------------------------------------------------
// ফার্মেসি: অনলাইন অর্ডার পৌঁছে দেওয়া (লাইনসহ) → রসিদ
// ------------------------------------------------------------

@Composable
fun RxDeliveredDialog(o: PharmOrder, onDismiss: () -> Unit, onDone: (String?) -> Unit) {
    val scope = rememberCoroutineScope()
    var drafts by remember { mutableStateOf(listOf(LineDraft())) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val lines = LineDrafts.toLines(drafts)
    FormDialog(
        title = tr("Delivered — ", "পৌঁছে দিয়েছি — ") + o.patientName.ifBlank { tr("customer", "ক্রেতা") },
        onDismiss = { if (!busy) onDismiss() },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (o.price.isNotBlank()) IconLine(R.drawable.ic_payments, tr("Price you gave: ", "আপনার বলা দাম: ") + o.price)
                if (o.deliveryFee.isNotBlank()) IconLine(R.drawable.ic_local_shipping, tr("Delivery charge: ", "ডেলিভারি চার্জ: ") + o.deliveryFee)
                SmallNote(tr("Write what you gave (the delivery charge is added). Leave empty to use the lines from \"Accept\".", "যা দিলেন লিখুন (ডেলিভারি চার্জ নিজে যোগ হয়)। খালি রাখলে \"অর্ডার নিলাম\"-এর লাইনই ধরা হবে।"))
                LinesEditor(drafts, { drafts = it })
                if (!lines.isNullOrEmpty()) Text(tr("Medicines: ", "ওষুধ: ") + InvoiceText.money(InvoiceMath.subtotal(lines), isBangla), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                SmallNote(tr("Delivered means the customer paid in cash.", "পৌঁছানো মানে ক্রেতা ক্যাশে টাকা দিয়েছেন।"))
                ErrorText(errorMsg)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val l = lines ?: return@TextButton
                scope.launch {
                    busy = true
                    errorMsg = null
                    runCatching { InvoiceApi.rxDelivered(o.id, l) }
                        .onSuccess { inv ->
                            onDone(if (inv == null) tr("Marked delivered — the patient was told", "পৌঁছানো হয়েছে — রোগীকে জানানো হয়েছে") else null)
                            InvoiceNav.issued(inv)
                        }
                        .onFailure { errorMsg = it.message }
                    busy = false
                }
            }, enabled = !busy && lines != null, modifier = Modifier.testTag("rx_delivered_submit")) { ButtonContent(busy, tr("Delivered — give receipt", "পৌঁছেছে — রসিদ দিন")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancel", "বাতিল")) } },
    )
}
