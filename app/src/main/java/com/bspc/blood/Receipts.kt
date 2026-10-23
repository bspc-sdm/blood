@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.bspc.blood

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * রসিদ দেখা (সবাই): কাগজের মতো কার্ড, অঙ্ক বাংলা/ইংরেজি, A4 বা ছোট (৮০ মিমি) PDF শেয়ার/প্রিন্ট, QR।
 * দেওয়া মানুষ (manage): বাকি টাকা নেওয়া, কারণসহ বাতিল।
 * রোগী: "আমার রসিদ" আর "আমার খরচ" (মাসে, কোন সেবায় কত) — কেয়ারগিভার রোগীর হয়ে (asUid)।
 * ব্যবসা: BizInvoicesPanel — আজ/৭/৩০ দিন/এই মাস, খোঁজা, বাকি আর বাতিলের চিহ্ন।
 * রসিদের QR: lifolo.com/v/<কোড> — অ্যাপে খুললে যাচাইয়ের ছোট পাতা (VerifyDialog), লগইন লাগে না।
 */

/** App-এর সবার উপরে: খোলা রসিদ আর যাচাই (অন্য ডায়ালগ খোলা থাকলেও তার উপরে) */
@Composable
fun ReceiptOverlays() {
    InvoiceNav.show.value?.let { t -> androidx.compose.runtime.key(t) { ReceiptDialog(t) { InvoiceNav.show.value = null } } }
    InvoiceNav.verify.value?.let { c -> androidx.compose.runtime.key(c) { VerifyDialog(c) { InvoiceNav.verify.value = null } } }
}

/** রসিদের পাতা (পুরো পর্দার ডায়ালগ) */
@Composable
fun ReceiptDialog(t: InvoiceNav.Show, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var inv by remember { mutableStateOf(t.invoice) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var bn by rememberSaveable { mutableStateOf(true) }
    var narrow by rememberSaveable { mutableStateOf(false) }
    var paying by remember { mutableStateOf(false) }
    var voiding by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf(false) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(t.id, reload) {
        if (inv != null && reload == 0) return@LaunchedEffect
        runCatching { InvoiceApi.get(t.id, t.asUid) }.onSuccess { inv = it; errorMsg = null }.onFailure { errorMsg = it.message }
    }
    FullScreenDialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
            BackTopBar(tr("Receipt", "রসিদ"), onDismiss)
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ErrorText(errorMsg)
                val i = inv
                if (i == null) {
                    if (errorMsg == null) LoadingBox()
                    return@Column
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = bn, onClick = { bn = true }, label = { Text("১২৩") }, modifier = Modifier.testTag("receipt_digits_bn"))
                    FilterChip(selected = !bn, onClick = { bn = false }, label = { Text("123") }, modifier = Modifier.testTag("receipt_digits_en"))
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = !narrow, onClick = { narrow = false }, label = { Text("A4") })
                    FilterChip(selected = narrow, onClick = { narrow = true }, label = { Text(tr("Small (80 mm)", "ছোট (৮০ মিমি)")) })
                }
                ReceiptView(i, bn)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { runCatching { ReceiptPdf.share(context, i, narrow, bn) }.onFailure { errorMsg = it.message } },
                        modifier = Modifier.weight(1f).testTag("receipt_share"),
                    ) { ButtonContent(false, tr("Share PDF", "PDF শেয়ার"), R.drawable.ic_share) }
                    OutlinedButton(
                        onClick = { runCatching { ReceiptPdf.print(context, i, narrow, bn) }.onFailure { errorMsg = it.message } },
                        modifier = Modifier.weight(1f).testTag("receipt_print"),
                    ) { ButtonContent(false, tr("Print", "প্রিন্ট"), R.drawable.ic_list_alt) }
                }
                InfoText(infoMsg)
                // রোগী: অ্যাম্বুলেন্স রসিদের চেয়ে বেশি নিলে জানানো (পর্যালোচনা I8)
                if (InvoiceRules.canReport(i, t.manage)) {
                    OutlinedButton(onClick = { reporting = true }, modifier = Modifier.fillMaxWidth().testTag("receipt_report")) {
                        ButtonContent(false, tr("Charged more than the receipt", "রসিদের চেয়ে বেশি নিয়েছে"), R.drawable.ic_flag)
                    }
                }
                if ((t.manage || i.canManage) && !i.isVoid) {
                    // সহকারী শুধু নিজের রসিদ, ২ ঘণ্টার মধ্যে বাতিল করতে পারেন (পর্যালোচনা I7)
                    val canVoid = InvoiceRules.canVoid(i, AuthRepository.currentUid, t.manage)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (i.hasDue) {
                            Button(onClick = { paying = true }, modifier = Modifier.weight(1f).testTag("receipt_collect")) {
                                ButtonContent(false, tr("Collect due", "বাকি টাকা নিন"), R.drawable.ic_payments)
                            }
                        }
                        if (canVoid) {
                            TextButton(onClick = { voiding = true }, modifier = Modifier.testTag("receipt_void")) {
                                Text(tr("Void receipt", "রসিদ বাতিল"), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    SmallNote(tr("A receipt can't be changed once given — you can only collect what's due or void it with a reason.", "রসিদ দেওয়ার পর বদলানো যায় না — শুধু বাকি টাকা নেওয়া বা কারণ লিখে বাতিল করা যায়।"))
                }
            }
        }
    }
    val i = inv
    if (paying && i != null) {
        PayDueDialog(i, onDismiss = { paying = false }) { updated ->
            paying = false
            inv = updated
            InvoiceNav.changed.intValue++
        }
    }
    if (reporting && i != null) {
        OverchargeDialog(i, onDismiss = { reporting = false }) {
            reporting = false
            infoMsg = tr("Sent. The Lifolo team will look into it.", "পাঠানো হয়েছে। Lifolo টিম দেখবে।")
        }
    }
    if (voiding && i != null) {
        VoidDialog(i, onDismiss = { voiding = false }) { updated ->
            voiding = false
            inv = updated
            InvoiceNav.changed.intValue++
        }
    }
}

/** কাগজের মতো রসিদ (সবসময় সাদা কাগজ, কালো লেখা) */
@Composable
fun ReceiptView(inv: Invoice, bn: Boolean) {
    val ink = Color(0xFF1B1F20)
    val grey = Color(0xFF5A6466)
    val teal = BrandTeal
    val red = Color(0xFFC62828)
    fun m(v: Double) = InvoiceText.money(v, bn)
    fun d(s: String) = InvoiceText.digits(s, bn)
    Surface(shape = RoundedCornerShape(6.dp), color = Color.White, shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth().testTag("receipt_view")) {
        Box {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(inv.bizName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = ink)
                        if (inv.bizAddress.isNotBlank()) Text(inv.bizAddress, style = MaterialTheme.typography.bodySmall, color = grey)
                        if (inv.bizPhone.isNotBlank()) Text(tr("Phone: ", "ফোন: ") + d(inv.bizPhone), style = MaterialTheme.typography.bodySmall, color = grey)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(R.drawable.ic_water_drop, null, Modifier.size(12.dp), teal)
                        Text("Lifolo", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = teal)
                    }
                }
                HorizontalDivider(color = teal)
                Text(tr("RECEIPT", "রসিদ") + " · " + InvoiceKinds.label(inv.kind), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = teal)
                Row {
                    Text(tr("No. ", "নং ") + d(inv.no), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = ink, modifier = Modifier.weight(1f).testTag("receipt_no"))
                    Text(InvoiceText.dateTime(inv.issuedAt, bn), style = MaterialTheme.typography.bodySmall, color = grey)
                }
                // ক্রেতার ফোন রসিদে ছাপা হয় না (পর্যালোচনা I6) — পর্দায়ও মাঝখান ঢাকা
                val who = listOf(inv.customerName, if (inv.customerPhone.isNotBlank()) d(InvoiceText.maskPhone(inv.customerPhone)) else "").filter { it.isNotBlank() }
                if (who.isNotEmpty()) Text(tr("Customer: ", "গ্রাহক: ") + who.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = ink)
                if (inv.offline) Text(tr("Offline receipt — it can be checked once online", "অফলাইন রসিদ — ইন্টারনেট এলে যাচাই হবে") + if (inv.deviceId.isNotBlank()) " · ${inv.deviceId}" else "", style = MaterialTheme.typography.bodySmall, color = grey)
                if (inv.isVoid) {
                    Text(
                        tr("VOID", "বাতিল") + if (inv.voidText.isNotBlank()) " — " + tr("reason: ", "কারণ: ") + inv.voidText else "",
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = red,
                    )
                }
                HorizontalDivider(color = Color(0xFFDDE3E4))
                inv.lines.forEachIndexed { idx, l ->
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(d("${idx + 1}. ") + l.name, style = MaterialTheme.typography.bodyMedium, color = ink)
                            Text(
                                InvoiceText.qty(l.qty, bn) + (if (l.unit.isNotBlank()) " " + l.unit else "") + " × " + m(l.price) +
                                    if (l.disc > 0.004) " − " + tr("disc ", "ছাড় ") + m(l.disc) else "",
                                style = MaterialTheme.typography.bodySmall, color = grey,
                            )
                        }
                        Text(m(l.amount), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = ink)
                    }
                }
                HorizontalDivider(color = Color(0xFFDDE3E4))
                TotalRow(tr("Subtotal", "উপমোট"), m(inv.subtotal), color = ink)
                if (inv.disc > 0.004) TotalRow(tr("Discount", "ছাড়"), "− " + m(inv.disc), color = ink)
                inv.fees.forEach { f -> TotalRow(InvoiceKinds.feeLabel(f.label), m(f.amount), color = ink) }
                Box(Modifier.testTag("receipt_total")) { TotalRow(tr("Total", "মোট"), m(inv.total), strong = true, color = ink) }
                TotalRow(tr("Paid", "দেওয়া হয়েছে"), m(inv.paid), color = ink)
                if (inv.due > 0.004) TotalRow(tr("Due", "বাকি"), m(inv.due), strong = true, color = red)
                inv.payments.forEach { p ->
                    Text(
                        InvoiceKinds.method(p.method) + " " + m(p.amount) + (if (p.ref.isNotBlank()) " · " + d(p.ref) else "") +
                            if (p.at > 0) " · " + InvoiceText.dateTime(p.at, bn) else "",
                        style = MaterialTheme.typography.bodySmall, color = grey,
                    )
                }
                if (inv.overFare) Text(tr("Fare is above the fare chart", "ভাড়া চার্টের চেয়ে বেশি"), style = MaterialTheme.typography.bodySmall, color = red)
                HorizontalDivider(color = Color(0xFFDDE3E4))
                val link = inv.link
                if (link.isNotBlank()) {
                    val qr = remember(link) { Qr.bitmap(link, 360)?.asImageBitmap() }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (qr != null) {
                            Image(qr, tr("Receipt QR", "রসিদের QR"), Modifier.size(84.dp))
                            Spacer(Modifier.width(12.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                "lifolo.com/v/" + inv.verifyCode.ifBlank { link.substringAfterLast('/') } + " — " + tr("check this receipt", "রসিদ যাচাই করুন"),
                                style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = ink,
                            )
                            Text(tr("Issued on Lifolo. The app takes no money.", "Lifolo-তে দেওয়া রসিদ। অ্যাপ কোনো টাকা নেয় না।"), style = MaterialTheme.typography.labelSmall, color = grey)
                        }
                    }
                }
                Text(inv.footer.ifBlank { InvoiceText.footerNote() }, style = MaterialTheme.typography.labelSmall, color = grey, modifier = Modifier.testTag("receipt_footer"))
            }
            if (inv.demo) {
                Text(
                    tr("DEMO", "ডেমো"), fontSize = 72.sp, fontWeight = FontWeight.Bold, color = Color(0xFF7A7A7A),
                    modifier = Modifier.align(Alignment.Center).rotate(-30f).alpha(0.22f).testTag("receipt_demo"),
                )
            }
            if (inv.isVoid) {
                Text(
                    tr("VOID", "বাতিল"), fontSize = 64.sp, fontWeight = FontWeight.Bold, color = red,
                    modifier = Modifier.align(Alignment.Center).rotate(-18f).alpha(0.35f).testTag("receipt_void_stamp"),
                )
            }
        }
    }
}

@Composable
private fun TotalRow(label: String, v: String, strong: Boolean = false, color: Color) {
    val style = if (strong) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium
    Row {
        Text(label, style = style, fontWeight = if (strong) FontWeight.Bold else null, color = color, modifier = Modifier.weight(1f))
        Text(v, style = style, fontWeight = if (strong) FontWeight.Bold else null, color = color)
    }
}

@Composable
private fun VerifyLine(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
    }
}

/** কীভাবে পেলেন: ক্যাশ · বিকাশ · নগদ · কার্ড · অন্যান্য */
@Composable
fun PayMethodChips(selected: String, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        InvoiceKinds.METHODS.forEach { m ->
            FilterChip(selected = selected == m, onClick = { onSelect(m) }, label = { Text(InvoiceKinds.method(m)) })
        }
    }
}

/** টাকার ঘর (বাংলা অঙ্কও চলে) */
@Composable
fun MoneyField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, isError: Boolean = false, supporting: String? = null) {
    OutlinedTextField(
        value, { onChange(it.take(10)) }, label = { Text(label) }, singleLine = true, isError = isError,
        supportingText = supporting?.let { s -> { Text(s) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun PayDueDialog(inv: Invoice, onDismiss: () -> Unit, onDone: (Invoice) -> Unit) {
    val scope = rememberCoroutineScope()
    var amount by remember { mutableStateOf(InvoiceText.digits(InvoiceText.r2(inv.due).let { if (it == Math.floor(it)) it.toLong().toString() else it.toString() }, isBangla)) }
    var method by remember { mutableStateOf("cash") }
    var ref by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val a = InvoiceMath.parse(amount)
    val bad = a == null || a <= 0.0 || a > inv.due + 0.004
    FormDialog(
        title = tr("Collect due", "বাকি টাকা নিন"),
        onDismiss = { if (!busy) onDismiss() },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SmallNote(tr("Due: ", "বাকি: ") + InvoiceText.money(inv.due, isBangla))
                MoneyField(amount, { amount = it }, tr("Amount (৳)", "টাকা (৳)"), isError = amount.isNotBlank() && bad)
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
                    runCatching { InvoiceApi.pay(inv.id, v, method, ref) }.onSuccess(onDone).onFailure { errorMsg = it.message }
                    busy = false
                }
            }, enabled = !busy && !bad) { ButtonContent(busy, tr("Collect", "নিলাম")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancel", "বাতিল")) } },
    )
}

@Composable
private fun VoidDialog(inv: Invoice, onDismiss: () -> Unit, onDone: (Invoice) -> Unit) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    // তালিকা থেকে একটা কারণ, সাথে লেখা — "অন্যান্য" হলে লেখা লাগবেই (পর্যালোচনা I2)
    val ok = code != null && (code != "other" || note.trim().length >= 3)
    FormDialog(
        title = tr("Void this receipt?", "রসিদটা বাতিল করবেন?"),
        onDismiss = { if (!busy) onDismiss() },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    tr("It stays in the records with a \"VOID\" stamp. This can't be undone.", "রসিদটা \"বাতিল\" ছাপসহ হিসাবে থেকে যাবে। আর ফেরানো যাবে না।") +
                        if (inv.kind == "pharmacy") tr(" The medicines go back into stock.", " ওষুধগুলো স্টকে ফেরত যাবে।") else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(tr("Reason", "কারণ"), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    InvoiceKinds.VOID_REASONS.forEach { c ->
                        FilterChip(selected = code == c, onClick = { code = c }, label = { Text(InvoiceKinds.voidReason(c, inv.kind)) })
                    }
                }
                OutlinedTextField(
                    note, { note = it.take(150) }, label = { Text(if (code == "other") tr("Write the reason", "কারণ লিখুন") else tr("Note (what happened)", "নোট (কী হয়েছিল)")) },
                    modifier = Modifier.fillMaxWidth(),
                )
                SmallNote(tr("The owner sees every void in the day-end summary.", "প্রতিটা বাতিল মালিক \"দিনের শেষ\" হিসাবে দেখবেন।"))
                ErrorText(errorMsg)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val c = code ?: return@TextButton
                scope.launch {
                    busy = true
                    errorMsg = null
                    runCatching { InvoiceApi.void(inv.id, c, note) }.onSuccess(onDone).onFailure { errorMsg = it.message }
                    busy = false
                }
            }, enabled = !busy && ok) { ButtonContent(busy, tr("Void", "বাতিল করুন")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Keep", "রাখুন")) } },
    )
}

/** রোগী: অ্যাম্বুলেন্স রসিদের চেয়ে বেশি নিয়েছে — কত দিয়েছেন আর কী হয়েছিল */
@Composable
private fun OverchargeDialog(inv: Invoice, onDismiss: () -> Unit, onSent: () -> Unit) {
    val scope = rememberCoroutineScope()
    var paid by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val p = InvoiceMath.parse(paid)
    val bad = paid.isNotBlank() && (p == null || p <= inv.total)
    FormDialog(
        title = tr("Charged more than the receipt", "রসিদের চেয়ে বেশি নিয়েছে"),
        onDismiss = { if (!busy) onDismiss() },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("Receipt total: ", "রসিদে মোট: ") + InvoiceText.money(inv.total, isBangla), style = MaterialTheme.typography.bodyMedium)
                MoneyField(paid, { paid = it }, tr("What you actually paid (৳)", "আসলে কত দিয়েছেন (৳)"), isError = bad,
                    supporting = if (bad) tr("Write more than the receipt total", "রসিদের মোটের চেয়ে বেশি লিখুন") else null)
                OutlinedTextField(note, { note = it.take(300) }, label = { Text(tr("What happened (optional)", "কী হয়েছিল (ঐচ্ছিক)")) }, modifier = Modifier.fillMaxWidth())
                SmallNote(tr("The Lifolo team checks it with the driver. Your name isn't shown to other patients.", "Lifolo টিম চালকের সাথে মিলিয়ে দেখবে। অন্য রোগীরা আপনার নাম দেখবেন না।"))
                ErrorText(errorMsg)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    busy = true
                    errorMsg = null
                    runCatching { InvoiceApi.reportOvercharge(inv, p?.takeIf { it > 0 }, note) }.onSuccess { onSent() }.onFailure { errorMsg = it.message }
                    busy = false
                }
            }, enabled = !busy && !bad && (paid.isNotBlank() || note.isNotBlank())) { ButtonContent(busy, tr("Send", "পাঠান")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancel", "বাতিল")) } },
    )
}

// ------------------------------------------------------------
// তালিকার এক সারি
// ------------------------------------------------------------

@Composable
fun InvoiceRow(inv: Invoice, showBiz: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(InvoiceKinds.icon(inv.kind), null, Modifier.size(22.dp), cs.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (showBiz) inv.bizName else inv.customerName.ifBlank { tr("Customer", "গ্রাহক") },
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    num(inv.no) + " · " + InvoiceText.dateTime(inv.issuedAt, isBangla) + if (showBiz) " · " + InvoiceKinds.label(inv.kind) else "",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    InvoiceText.money(inv.total, isBangla), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                    color = if (inv.isVoid) cs.onSurfaceVariant else cs.onSurface,
                )
                when {
                    inv.isVoid -> Tag(tr("Void", "বাতিল"), cs.errorContainer, cs.onErrorContainer)
                    inv.hasDue -> Tag(tr("Due ", "বাকি ") + InvoiceText.money(inv.due, isBangla), cs.tertiaryContainer, cs.onTertiaryContainer)
                }
            }
        }
    }
}

// ------------------------------------------------------------
// রোগী: আমার রসিদ / আমার খরচ
// ------------------------------------------------------------

/** প্রোফাইল → "আমার রসিদ ও খরচ" (asUid: কেয়ারগিভার হিসেবে রোগীর) */
@Composable
fun MyReceiptsScreen(asUid: String?, name: String, tab: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    var scanErr by remember { mutableStateOf<String?>(null) }
    Scaffold(
        topBar = {
            BackTopBar(if (asUid != null && name.isNotBlank()) tr("Receipts — $name", "রসিদ — $name") else tr("My receipts", "আমার রসিদ"), onBack) {
                IconButton(onClick = { scanErr = null; ReceiptScan.scan(context, { InvoiceNav.verify.value = it }, { scanErr = it }) }) {
                    AppIcon(R.drawable.ic_photo_camera, tr("Check a receipt", "রসিদ যাচাই"))
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (scanErr != null) Column(Modifier.padding(horizontal = 16.dp)) { ErrorText(scanErr) }
            ReceiptsAndExpenses(asUid, tab)
        }
    }
}

/** দুই ভাগ: রসিদ · খরচ (কেয়ারগিভার মোডের ট্যাবেও এটাই) */
@Composable
fun ReceiptsAndExpenses(asUid: String?, startTab: Int = 0) {
    var tab by rememberSaveable { mutableIntStateOf(startTab) }
    Column(Modifier.fillMaxSize()) {
        PillTabs(tab, listOf(PillTab(tr("Receipts", "রসিদ"), R.drawable.ic_list_alt), PillTab(tr("Expenses", "খরচ"), R.drawable.ic_payments)), { tab = it }, secondary = true)
        Box(Modifier.weight(1f)) {
            if (tab == 0) MyReceiptsList(asUid) else ExpensesView(asUid)
        }
    }
}

@Composable
fun MyReceiptsList(asUid: String?) {
    val scope = rememberCoroutineScope()
    var items by remember(asUid) { mutableStateOf<List<Invoice>?>(null) }
    var cursor by remember(asUid) { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var more by remember { mutableStateOf(false) }
    val bump = InvoiceNav.changed.intValue
    LaunchedEffect(asUid, bump) {
        runCatching { InvoiceApi.mine(asUid) }.onSuccess { items = it.items; cursor = it.cursor; errorMsg = null }.onFailure { errorMsg = it.message; if (items == null) items = emptyList() }
    }
    val list = items
    LazyColumn(Modifier.fillMaxSize().testTag("my_receipts"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { ErrorText(errorMsg) }
        when {
            list == null -> item { LoadingBox() }
            list.isEmpty() -> item {
                EmptyState(
                    R.drawable.ic_list_alt, tr("No receipts yet", "এখনো কোনো রসিদ নেই"),
                    tr("Receipts from pharmacies, labs, doctors and ambulances on Lifolo show here.", "Lifolo-তে ফার্মেসি, প্যাথলজি, ডাক্তার আর অ্যাম্বুলেন্সের রসিদ এখানে আসবে।"),
                    Modifier.fillMaxWidth().padding(top = 32.dp),
                )
            }
            else -> {
                items(list, key = { it.id }) { inv -> InvoiceRow(inv, showBiz = true) { InvoiceNav.open(inv, asUid = asUid) } }
                val c = cursor
                if (c != null) item {
                    OutlinedButton(onClick = {
                        scope.launch {
                            more = true
                            runCatching { InvoiceApi.mine(asUid, c) }.onSuccess { p -> items = list + p.items.filter { n -> list.none { it.id == n.id } }; cursor = p.cursor }.onFailure { errorMsg = it.message }
                            more = false
                        }
                    }, enabled = !more, modifier = Modifier.fillMaxWidth()) { ButtonContent(more, tr("Show more", "আরও দেখুন")) }
                }
            }
        }
    }
}

/** "আমার খরচ": মাস বদলানো, মোট, কোন সেবায় কত (দাগসহ) */
@Composable
fun ExpensesView(asUid: String?) {
    var month by rememberSaveable { mutableStateOf(InvoiceMonths.current()) }
    var data by remember { mutableStateOf<Expenses?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val bump = InvoiceNav.changed.intValue
    LaunchedEffect(asUid, month, bump) {
        data = null
        runCatching { InvoiceApi.expenses(month, asUid) }.onSuccess { data = it; errorMsg = null }.onFailure { errorMsg = it.message }
    }
    val cs = MaterialTheme.colorScheme
    val thisMonth = InvoiceMonths.current()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("my_expenses"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { month = InvoiceMonths.shift(month, -1) }) { Text("‹ " + tr("Previous", "আগের মাস")) }
            Text(InvoiceText.monthLabel(month), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            TextButton(onClick = { month = InvoiceMonths.shift(month, 1) }, enabled = month < thisMonth) { Text(tr("Next", "পরের মাস") + " ›") }
        }
        ErrorText(errorMsg)
        val d = data
        if (d == null) {
            if (errorMsg == null) LoadingBox()
            return@Column
        }
        Card(colors = CardDefaults.cardColors(containerColor = cs.primaryContainer)) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(tr("Spent this month", "এই মাসে খরচ"), style = MaterialTheme.typography.labelLarge, color = cs.onPrimaryContainer)
                Text(InvoiceText.money(d.total, isBangla), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = cs.onPrimaryContainer, modifier = Modifier.testTag("expenses_total"))
                Text(tr("${d.count} receipt(s)", "${num(d.count)}টা রসিদ"), style = MaterialTheme.typography.bodySmall, color = cs.onPrimaryContainer)
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val max = d.byKind.values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
                InvoiceKinds.ALL.forEach { k ->
                    val v = d.byKind[k] ?: 0.0
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(InvoiceKinds.icon(k), null, Modifier.size(18.dp), cs.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(InvoiceKinds.label(k), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text(InvoiceText.money(v, isBangla), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        }
                        Box(Modifier.fillMaxWidth().height(6.dp).background(cs.surfaceVariant, RoundedCornerShape(3.dp))) {
                            if (v > 0) Box(Modifier.fillMaxWidth((v / max).toFloat().coerceIn(0.02f, 1f)).fillMaxHeight().background(cs.primary, RoundedCornerShape(3.dp)))
                        }
                    }
                }
            }
        }
        SmallNote(tr("Only receipts given on Lifolo are counted; voided ones are left out.", "শুধু Lifolo-তে দেওয়া রসিদ ধরা হয়; বাতিল রসিদ বাদ।"))
    }
}

// ------------------------------------------------------------
// ব্যবসা: রসিদের তালিকা (বাতিল, বাকি টাকা নেওয়া রসিদ খুলে)
// ------------------------------------------------------------

/** ফার্মেসি/প্যাথলজি/চেম্বারের রসিদ — স্ক্রল করা পাতার ভেতরে বসে (LazyColumn নয়) */
@Composable
fun BizInvoicesPanel(kind: String, bizId: String, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var period by rememberSaveable { mutableStateOf("today") }
    var q by rememberSaveable { mutableStateOf("") }
    var items by remember { mutableStateOf<List<Invoice>?>(null) }
    var cursor by remember { mutableStateOf<String?>(null) }
    var sum by remember { mutableStateOf<BizSum?>(null) }
    var dayEnd by remember { mutableStateOf<DayEnd?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var more by remember { mutableStateOf(false) }
    val bump = InvoiceNav.changed.intValue
    LaunchedEffect(kind, bizId, period, q, bump) {
        if (bizId.isBlank()) return@LaunchedEffect
        // লেখার সময় প্রতিটা অক্ষরে নয়
        if (q.isNotBlank()) delay(400)
        runCatching { InvoiceApi.listBiz(kind, bizId, period, q) }.onSuccess { items = it.items; cursor = it.cursor; sum = it.sum; dayEnd = it.dayEnd; errorMsg = null }.onFailure { errorMsg = it.message; if (items == null) items = emptyList() }
        // আজকের: "দিনের শেষ"-এর বিস্তারিত (বাতিল, বড় ছাড়, নষ্ট, কম দামে) — শুধু মালিক পান, বাকিদের চুপচাপ নেই
        if (period == "today" && q.isBlank()) InvoiceApi.daySummary(kind, bizId)?.let { dayEnd = it }
    }
    val cs = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().testTag("biz_invoices"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            InvoiceKinds.PERIODS.forEach { p -> FilterChip(selected = period == p, onClick = { period = p }, label = { Text(InvoiceKinds.period(p)) }) }
        }
        OutlinedTextField(
            q, { q = it.take(40) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text(tr("Search number, name or phone", "নম্বর, নাম বা ফোন দিয়ে খুঁজুন")) },
        )
        ErrorText(errorMsg)
        val list = items
        when {
            list == null -> LoadingBox()
            list.isEmpty() -> SmallNote(tr("No receipts in this period.", "এই সময়ে কোনো রসিদ নেই।"))
            else -> {
                val live = list.filter { !it.isVoid }
                // সার্ভারের যোগফল পুরো সময়ের (সব পাতা); না এলে দেখানো রসিদগুলোর
                val s = sum ?: BizSum(live.size, live.sumOf { it.total }, live.sumOf { it.paid }, live.sumOf { it.due })
                val voids = dayEnd?.voids ?: (list.size - live.size)
                Card(colors = CardDefaults.cardColors(containerColor = cs.secondaryContainer), modifier = Modifier.testTag("biz_invoices_sum")) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (period == "today") Text(tr("Day-end", "দিনের শেষ"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = cs.onSecondaryContainer)
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(tr("Total", "মোট"), style = MaterialTheme.typography.labelMedium, color = cs.onSecondaryContainer)
                                Text(InvoiceText.money(s.total, isBangla), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(tr("${s.count} receipt(s)", "${num(s.count)}টা রসিদ"), style = MaterialTheme.typography.bodySmall, color = cs.onSecondaryContainer)
                            }
                            Column(Modifier.weight(1f)) {
                                Text(tr("Due", "বাকি"), style = MaterialTheme.typography.labelMedium, color = cs.onSecondaryContainer)
                                Text(InvoiceText.money(s.due, isBangla), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                if (voids > 0) Text(tr("$voids void", "বাতিল ${num(voids)}টা"), style = MaterialTheme.typography.bodySmall, color = cs.error)
                            }
                        }
                        // বড় ছাড়, নষ্ট দেখানো স্টক, স্টক কম (সার্ভার দিলে — পর্যালোচনা I2)
                        dayEnd?.let { e ->
                            val parts = listOfNotNull(
                                if (e.bigDiscounts > 0) tr("${e.bigDiscounts} big discount(s)", "বড় ছাড় ${num(e.bigDiscounts)}টা") else null,
                                if (e.writeOffs > 0) tr("${e.writeOffs} stock write-off(s)", "স্টক নষ্ট দেখানো ${num(e.writeOffs)}টা") else null,
                                if (e.belowPrice > 0) tr("${e.belowPrice} sold below the item price", "দামের চেয়ে কমে বিক্রি ${num(e.belowPrice)}টা") else null,
                            )
                            if (parts.isNotEmpty()) Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = cs.error)
                            if (e.note.isNotBlank()) Text(e.note, style = MaterialTheme.typography.bodySmall, color = cs.onSecondaryContainer)
                        }
                    }
                }
                list.forEach { inv -> InvoiceRow(inv, showBiz = false) { InvoiceNav.open(inv, manage = true) } }
                val c = cursor
                if (c != null) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            more = true
                            runCatching { InvoiceApi.listBiz(kind, bizId, period, q, c) }
                                .onSuccess { p -> items = list + p.items.filter { n -> list.none { it.id == n.id } }; cursor = p.cursor }
                                .onFailure { errorMsg = it.message }
                            more = false
                        }
                    }, enabled = !more, modifier = Modifier.fillMaxWidth()) { ButtonContent(more, tr("Show more", "আরও দেখুন")) }
                }
            }
        }
    }
}

// ------------------------------------------------------------
// রোগীর অর্ডার/অ্যাপয়েন্টমেন্ট/অ্যাম্বুলেন্স থেকে "রসিদ"
// ------------------------------------------------------------

/**
 * "রসিদ" বাটন (রোগীর অর্ডার/পরীক্ষা/অ্যাপয়েন্টমেন্ট/অ্যাম্বুলেন্সে): সার্ভারের invoiceId থাকলে আর my_expenses চালু থাকলে তবেই।
 * কেয়ারগিভার মোডে রোগীর হয়ে (asUid)।
 */
@Composable
fun ReceiptLink(invoiceId: String) {
    if (invoiceId.isBlank() || !invOn(InvoiceFeatures.MY_EXPENSES)) return
    SmallAction(tr("Receipt", "রসিদ"), R.drawable.ic_list_alt, { InvoiceNav.openId(invoiceId, CareMode.asUid.value) }, modifier = Modifier.testTag("receipt_link"))
}

/** ব্যবসার দিকের "রসিদ" (দেওয়া রসিদ খোলা — টাকা নেওয়া/বাতিল) */
@Composable
fun BizReceiptAction(invoiceId: String) {
    SmallAction(tr("Receipt", "রসিদ"), R.drawable.ic_list_alt, { InvoiceNav.openId(invoiceId, manage = true) }, modifier = Modifier.testTag("biz_receipt"))
}

/** ফিচার সুইচ (Features.kt) — বদলালে পাতা নতুন করে আঁকে */
@Composable
fun invOn(key: String): Boolean {
    val keys by Features.keys.collectAsState()
    return remember(keys, key) { Features.on(key) }
}

// ------------------------------------------------------------
// যাচাই (QR / lifolo.com/v/<কোড>) — লগইন লাগে না
// ------------------------------------------------------------

object ReceiptScan {
    fun scan(context: android.content.Context, onCode: (String) -> Unit, onError: (String) -> Unit) {
        val opts = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE).build()
        com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(context, opts).startScan()
            .addOnSuccessListener { b ->
                val raw = b.rawValue.orEmpty()
                val code = InvoiceText.codeOf(raw)
                if (code != null) onCode(code) else onError(tr("This isn't a Lifolo receipt QR", "এটা Lifolo রসিদের QR নয়"))
            }
            .addOnFailureListener { onError(tr("Couldn't open the scanner", "স্ক্যানার খোলা গেল না")) }
    }
}

@Composable
fun VerifyDialog(code: String, onDismiss: () -> Unit) {
    var data by remember { mutableStateOf<InvoiceCheck?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var notFound by remember { mutableStateOf(false) }
    LaunchedEffect(code) {
        runCatching { InvoiceApi.verify(code) }.onSuccess { data = it; errorMsg = null }.onFailure {
            // পাওয়া যায়নি: অফলাইন রসিদ হয়তো এখনো আসেনি (পর্যালোচনা I1)
            if (it is ServerError && (it.code == "not_found" || it.message?.contains("404") == true)) notFound = true else errorMsg = it.message
        }
    }
    val cs = MaterialTheme.colorScheme
    CardDialog(onDismissRequest = onDismiss, tag = "receipt_verify") {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tr("Check receipt", "রসিদ যাচাই"), style = MaterialTheme.typography.headlineSmall)
            Text(tr("Code: ", "কোড: ") + code, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            ErrorText(errorMsg)
            if (notFound) {
                Text(
                    tr(
                        "Not found. If the shop had no internet, check again a little later; if it still isn't found after 24 hours, the receipt is suspicious.",
                        "পাওয়া যায়নি। দোকানে ইন্টারনেট না থাকলে কিছুক্ষণ পরে আবার দেখুন; ২৪ ঘণ্টা পরেও না পেলে রসিদটা সন্দেহজনক।",
                    ),
                    style = MaterialTheme.typography.bodyMedium, color = cs.error,
                )
            }
            val d = data
            if (d == null) {
                if (errorMsg == null && !notFound) LoadingBox()
            } else {
                // ডেমো দোকানের রসিদ কখনো "বৈধ" নয় (পর্যালোচনা I9)
                if (d.demo) {
                    Surface(shape = RoundedCornerShape(12.dp), color = cs.errorContainer, modifier = Modifier.fillMaxWidth().testTag("verify_demo")) {
                        Text(
                            tr("DEMO — not a real receipt", "ডেমো — আসল রসিদ নয়"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                            color = cs.onErrorContainer, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(14.dp),
                        )
                    }
                } else {
                    Surface(shape = RoundedCornerShape(12.dp), color = if (d.isVoid) cs.errorContainer else cs.tertiaryContainer, modifier = Modifier.fillMaxWidth().testTag("verify_status")) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(if (d.isVoid) R.drawable.ic_warning else R.drawable.ic_verified, null, Modifier.size(28.dp), if (d.isVoid) cs.onErrorContainer else cs.onTertiaryContainer)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    if (d.isVoid) tr("VOID", "বাতিল") else tr("VALID", "বৈধ"),
                                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                                    color = if (d.isVoid) cs.onErrorContainer else cs.onTertiaryContainer,
                                )
                                if (d.isVoid && d.voidedAt > 0) Text(tr("Voided ", "বাতিল হয়েছে ") + InvoiceText.dateTime(d.voidedAt, isBangla), style = MaterialTheme.typography.bodySmall, color = cs.onErrorContainer)
                            }
                        }
                    }
                }
                if (d.sanctioned) Text(tr("This business is suspended on Lifolo now", "প্রতিষ্ঠানটি এখন Lifolo-তে স্থগিত"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = cs.error)
                // নাম, নম্বর, তারিখ আর মোট বড় করে — কাগজের সাথে মেলানোর জন্য
                Text(d.bizName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(InvoiceKinds.label(d.kind), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                BigLine(tr("Receipt no.", "রসিদ নং"), num(d.no))
                BigLine(tr("Date", "তারিখ"), InvoiceText.dateTime(d.issuedAt, isBangla))
                BigLine(tr("Total", "মোট"), InvoiceText.money(d.total, isBangla), tag = "verify_total")
                d.lineCount?.let { VerifyLine(tr("Lines", "লাইন"), tr("$it", "${num(it)}টা")) }
                VerifyLine(tr("Paid", "দেওয়া হয়েছে"), InvoiceText.money(d.paid, isBangla))
                if (d.due > 0.004) VerifyLine(tr("Due", "বাকি"), InvoiceText.money(d.due, isBangla))
                Surface(shape = RoundedCornerShape(10.dp), color = cs.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        tr("Match the number, date and total with the paper. Only the lifolo.com address is genuine.", "কাগজের সাথে নম্বর, তারিখ আর মোট মিলিয়ে নিন। শুধু lifolo.com ঠিকানাই আসল।"),
                        style = MaterialTheme.typography.bodyMedium, color = cs.onSecondaryContainer, modifier = Modifier.padding(10.dp),
                    )
                }
                SmallNote(tr("The check page never shows the patient's or customer's details.", "যাচাইয়ের পাতায় রোগী বা ক্রেতার কোনো তথ্য দেখানো হয় না।"))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(tr("Close", "বন্ধ")) }
            }
        }
    }
}

@Composable
private fun BigLine(label: String, value: String, tag: String? = null) {
    Column(if (tag != null) Modifier.testTag(tag) else Modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    }
}
