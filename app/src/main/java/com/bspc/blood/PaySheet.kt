@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.bspc.blood

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/*
 * PaySheet — সব পেমেন্টের একটাই পাতা: টাকা, কীসের জন্য, কার কাছে; চালু নম্বরগুলো থেকে বাছাই (bKash/Nagad/Rocket/Upay —
 * লেখায়, লোগো নয়); নম্বর কপি; পার্সোনাল হলে "সেন্ড মানি", মার্চেন্ট হলে "পেমেন্ট"-এর ধাপ; TrxID (প্রতিটা সেবার নিয়মে) আর
 * শেষ ৪ সংখ্যা, তারপর "টাকা পাঠিয়েছি"; অবস্থার টাইমলাইন; ফেরত পেয়েছি; রিপোর্ট।
 * ছোট অংশগুলো (নম্বর, ধাপ, টাইমলাইন) অনলাইন চেম্বারের প্যানেলেও (OnlineChamber.kt) ব্যবহার হয়।
 */

/** App-এর সবার উপরে (ReceiptOverlays থেকে): পেমেন্টের পাতা আর ব্যবসার "পেমেন্ট" */
@Composable
fun PayOverlays() {
    PayNav.biz.value?.let { b -> androidx.compose.runtime.key(b) { BizPaymentsDialog(b) { PayNav.biz.value = null } } }
    PayNav.show.value?.let { s -> androidx.compose.runtime.key(s.id) { PaySheetDialog(s) { PayNav.show.value = null } } }
}

/** পেমেন্টের পাতা (পুরো পর্দা) — খুললে সার্ভার থেকে নতুন অবস্থা আনে */
@Composable
fun PaySheetDialog(s: PayNav.Show, onDismiss: () -> Unit) {
    var req by remember { mutableStateOf(s.request) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(s.id) {
        runCatching { PayApi.get(s.id, s.asUid) }.onSuccess { req = it; PayStore.put(it, s.asUid); errorMsg = null }
            .onFailure { if (req == null) errorMsg = PayText.error(it) }
    }
    FullScreenDialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
            BackTopBar(tr("Payment", "পেমেন্ট"), onDismiss)
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ErrorText(errorMsg)
                val r = req
                if (r == null) {
                    if (errorMsg == null) LoadingBox()
                } else {
                    PaySheet(r, s.asUid, onChanged = { n -> req = n; PayStore.put(n, s.asUid); PayNav.bump() })
                }
            }
        }
    }
}

/**
 * পুরো পেমেন্ট (পাতার ভেতরে বসে — স্ক্রল বাইরের): মাথা, টাইমলাইন, অবস্থা অনুযায়ী পাঠানো/ফেরত/রিপোর্ট।
 * onChanged: সার্ভারের নতুন অবস্থা।
 */
@Composable
fun PaySheet(r: PayRequest, asUid: String? = null, onChanged: (PayRequest) -> Unit, now: Long? = null) {
    // রক্তের কিছুতে কখনো পেমেন্ট দেখানো হয় না (সার্ভারও আটকায়)
    if (PayRules.isBlood(r.refType)) return
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    val t = now ?: System.currentTimeMillis()
    var reporting by remember { mutableStateOf<String?>(null) }
    var reported by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().testTag("pay_sheet"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ডেমোর অনুরোধ: আসল টাকা নয় (পর্যালোচনা P8)
        if (r.demo) {
            Surface(color = cs.errorContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().testTag("pay_demo")) {
                Text(PayText.DEMO, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = cs.onErrorContainer, modifier = Modifier.padding(12.dp))
            }
        }
        PayHeader(r)
        // সবসময়: শুধু এখানের নম্বরে; অন্য নম্বর দিলে রিপোর্ট (পর্যালোচনা P2)
        PayOnlyHereWarning()
        PayTimeline(r)
        // ব্যবসা "পাইনি" বলেছে: আলাদা বার্তা আর আপত্তি (রিপোর্ট) — একই টাকা আবার পাঠানোর আগে (পর্যালোচনা P3)
        if (r.status == "rejected") {
            Surface(color = cs.errorContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().testTag("pay_rejected_box")) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(PayText.who(r.kind) + tr(" says the money didn't arrive", " বলছেন টাকা পাননি") + PayText.rejectSuffix(r), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = cs.onErrorContainer)
                    r.claim?.let { c -> Text("TrxID " + c.trxId + " (" + PayText.method(c.method) + ")", fontFamily = FontFamily.Monospace, color = cs.onErrorContainer) }
                    Text(
                        tr("If you really sent it, don't send the money again — report it and Lifolo will look into it. Typed a wrong TrxID? Fix it below.", "সত্যিই পাঠিয়ে থাকলে আবার টাকা পাঠাবেন না — রিপোর্ট করুন, Lifolo দেখবে। TrxID ভুল লিখে থাকলে নিচে ঠিক করে দিন।"),
                        style = MaterialTheme.typography.bodyMedium, color = cs.onErrorContainer,
                    )
                    OutlinedButton(onClick = { reporting = "not_confirmed" }, modifier = Modifier.fillMaxWidth().testTag("pay_dispute")) {
                        ButtonContent(false, tr("I did send it — report", "আমি পাঠিয়েছি — রিপোর্ট করুন"), R.drawable.ic_flag)
                    }
                }
            }
        }
        when {
            PayRules.canClaim(r, t) -> {
                PayRefundPolicy(r)
                PayClaimSection(r, asUid, onChanged)
            }
            r.status in setOf("open", "rejected") && r.activeMethods.isEmpty() -> SmallNote(PayText.NO_NUMBERS)
            r.status == "claimed" -> r.claim?.let { PayClaimInfo(it) }
            r.status == "confirmed" -> {
                IconLine(R.drawable.ic_check_circle, tr("Confirmed. The receipt is in \"My receipts\".", "নিশ্চিত হয়েছে। রসিদ \"আমার রসিদ\"-এ আছে।"), cs.tertiary)
                if (r.invoiceId.isNotBlank()) {
                    OutlinedButton(onClick = { InvoiceNav.openId(r.invoiceId, asUid) }, modifier = Modifier.fillMaxWidth().testTag("pay_receipt")) {
                        ButtonContent(false, tr("See receipt", "রসিদ দেখুন"), R.drawable.ic_list_alt)
                    }
                }
            }
            r.status == "expired" -> SmallNote(tr("Time for this payment is over. If you still need to pay, pay in cash or ask them again.", "এই পেমেন্টের সময় শেষ। এখনো দিতে হলে ক্যাশে দিন বা আবার চাইতে বলুন।"))
        }
        r.refund?.let { f ->
            Surface(color = cs.secondaryContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().testTag("pay_refund_box")) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(tr("Refunded ", "ফেরত দেওয়া হয়েছে ") + PayText.money(f.amount.takeIf { it > 0 } ?: r.amount), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = cs.onSecondaryContainer)
                    if (f.trxId.isNotBlank()) Text("TrxID: " + f.trxId, fontFamily = FontFamily.Monospace, color = cs.onSecondaryContainer)
                    if (f.at > 0) Text(InvoiceText.dateTime(f.at, isBangla), style = MaterialTheme.typography.bodySmall, color = cs.onSecondaryContainer)
                    if (PayRules.canAckRefund(r)) {
                        // ব্যবসা বলছে ফেরত পাঠিয়েছে — রোগী নিশ্চিত না করা পর্যন্ত "ফেরত এসেছে?" (refund_claimed)
                        Text(tr("Check your own app's message first.", "আগে নিজের অ্যাপের মেসেজ দেখে নিন।"), style = MaterialTheme.typography.bodySmall, color = cs.onSecondaryContainer)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                scope.launch {
                                    busy = true
                                    errorMsg = null
                                    runCatching { PayApi.refundAck(r.id, asUid) }.onSuccess(onChanged).onFailure { errorMsg = PayText.error(it) }
                                    busy = false
                                }
                            }, enabled = !busy, modifier = Modifier.weight(1f).testTag("pay_refund_ack")) { ButtonContent(busy, tr("I got it", "ফেরত পেয়েছি"), R.drawable.ic_check_circle) }
                            OutlinedButton(onClick = { reporting = "no_refund" }, enabled = !busy, modifier = Modifier.weight(1f).testTag("pay_refund_missing")) { Text(tr("Didn't get it", "পাইনি")) }
                        }
                    } else if (f.ackAt > 0 || r.status == "refunded") {
                        Text(tr("You confirmed you got it back", "আপনি ফেরত পাওয়া নিশ্চিত করেছেন"), style = MaterialTheme.typography.bodySmall, color = cs.onSecondaryContainer)
                    }
                }
            }
        }
        ErrorText(errorMsg)
        if (PayRules.canReport(r)) {
            if (reported) SmallNote(tr("Reported. Lifolo will look into it.", "রিপোর্ট পাঠানো হয়েছে। Lifolo দেখবে।"))
            else TextButton(onClick = { reporting = if (r.status == "refund_claimed") "no_refund" else "not_confirmed" }, modifier = Modifier.testTag("pay_report")) {
                Text(tr("Problem with this payment? Report", "এই পেমেন্টে সমস্যা? রিপোর্ট করুন"), color = cs.error)
            }
        }
        SmallNote(PayText.DIRECT)
    }
    reporting?.let { why -> PayReportDialog(r, asUid, why, onDismiss = { reporting = null }) { reporting = null; reported = true } }
}

/** "শুধু এখানে দেখানো নম্বরে…" — PaySheet-এ সবসময় (অনলাইন চেম্বারের পুরোনো পথেও) */
@Composable
fun PayOnlyHereWarning() {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.tertiaryContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().testTag("pay_only_here")) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            AppIcon(R.drawable.ic_warning, null, Modifier.size(20.dp), cs.onTertiaryContainer)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(PayText.ONLY_HERE, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = cs.onTertiaryContainer)
                Text(PayText.NO_PIN, style = MaterialTheme.typography.bodySmall, color = cs.onTertiaryContainer)
            }
        }
    }
}

/** টাকা দেওয়ার আগে ফেরতের নিয়ম (পর্যালোচনা P11) */
@Composable
fun PayRefundPolicy(r: PayRequest) {
    Row(Modifier.fillMaxWidth().testTag("pay_refund_policy"), verticalAlignment = Alignment.Top) {
        AppIcon(R.drawable.ic_info, null, Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(PayText.refundPolicy(r), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** টাকা (বড়), কীসের জন্য, কার কাছে, অবস্থা */
@Composable
fun PayHeader(r: PayRequest) {
    val cs = MaterialTheme.colorScheme
    Card(colors = CardDefaults.cardColors(containerColor = cs.primaryContainer), modifier = Modifier.fillMaxWidth().testTag("pay_header")) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(PayText.purpose(r.purpose), style = MaterialTheme.typography.labelLarge, color = cs.onPrimaryContainer, modifier = Modifier.weight(1f))
                PayStatusTag(r.status)
            }
            Text(PayText.money(r.amount), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = cs.onPrimaryContainer, modifier = Modifier.testTag("pay_amount"))
            if (r.bizName.isNotBlank()) Text(tr("To ", "") + r.bizName + tr("", "-কে"), style = MaterialTheme.typography.titleSmall, color = cs.onPrimaryContainer)
            if (r.status == "open" && r.expiresAt > 0) {
                Text(tr("Pay by ", "") + InvoiceText.dateTime(r.expiresAt, isBangla) + tr("", "-এর মধ্যে"), style = MaterialTheme.typography.bodySmall, color = cs.onPrimaryContainer)
            }
        }
    }
}

@Composable
fun PayStatusTag(status: String, biz: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when (status) {
        "confirmed" -> cs.tertiaryContainer to cs.onTertiaryContainer
        // "পাঠিয়েছেন" কখনো "পরিশোধিত"-এর মতো নয় — নিরপেক্ষ রং (পর্যালোচনা P1)
        "claimed" -> cs.surfaceContainerHighest to cs.onSurface
        "rejected", "expired", "cancelled" -> cs.errorContainer to cs.onErrorContainer
        "refunded", "refund_claimed" -> cs.secondaryContainer to cs.onSecondaryContainer
        else -> cs.surfaceVariant to cs.onSurfaceVariant
    }
    Tag(if (biz) PayText.bizStatus(status) else PayText.status(status), bg, fg)
}

/** অবস্থার ধাপ (বিন্দু আর লাইন) */
@Composable
fun PayTimeline(r: PayRequest) {
    val cs = MaterialTheme.colorScheme
    val steps = PayRules.timeline(r)
    Column(Modifier.fillMaxWidth().testTag("pay_timeline")) {
        steps.forEachIndexed { i, s ->
            val color = when (s.mark) {
                PayRules.Mark.DONE -> cs.tertiary
                PayRules.Mark.NOW -> cs.primary
                PayRules.Mark.BAD -> cs.error
                PayRules.Mark.TODO -> cs.outlineVariant
            }
            Row(verticalAlignment = Alignment.Top) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(20.dp)) {
                    Box(Modifier.padding(top = 4.dp).size(12.dp).background(color, CircleShape))
                    if (i < steps.lastIndex) Box(Modifier.width(2.dp).height(22.dp).background(cs.outlineVariant))
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        s.label, style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (s.mark == PayRules.Mark.NOW || s.mark == PayRules.Mark.BAD) FontWeight.SemiBold else null,
                        color = if (s.mark == PayRules.Mark.TODO) cs.onSurfaceVariant else if (s.mark == PayRules.Mark.BAD) cs.error else cs.onSurface,
                    )
                    if (s.at > 0) Text(InvoiceText.dateTime(s.at, isBangla), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
            }
        }
    }
}

/** চালু নম্বরগুলো থেকে বাছাই — একটা হলে বাছাই দেখায় না */
@Composable
fun PayMethodPicker(methods: List<PayMethod>, selected: String, onSelect: (String) -> Unit) {
    if (methods.size < 2) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(tr("Pay with", "কীভাবে দেবেন"), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            methods.forEach { m ->
                FilterChip(
                    selected = selected == m.method, onClick = { onSelect(m.method) },
                    label = { Text(PayText.method(m.method), fontWeight = FontWeight.SemiBold) },
                    modifier = Modifier.testTag("pay_method_" + m.method),
                )
            }
        }
    }
}

/** নম্বর (বড়, মোনোস্পেস) আর কপি */
@Composable
fun PayNumberRow(label: String, number: String, copyTag: String = "pay_copy") {
    val context = LocalContext.current
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            Text(number, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.testTag("pay_number"))
        }
        androidx.compose.material3.FilledTonalIconButton(
            onClick = {
                clip.setText(androidx.compose.ui.text.AnnotatedString(number))
                android.widget.Toast.makeText(context, tr("Number copied", "নম্বর কপি হয়েছে"), android.widget.Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.testTag(copyTag),
        ) { AppIcon(R.drawable.ic_content_copy, tr("Copy number", "নম্বর কপি করুন"), Modifier.size(20.dp)) }
    }
    Text(PayText.RECHECK, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
}

/** ১. ২. ৩. ধাপ */
@Composable
fun PayStepList(steps: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("pay_steps")) {
        steps.forEachIndexed { i, s ->
            Row {
                Text(num(i + 1) + ".", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(22.dp))
                Text(s, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** পাঠানোর অংশ: নম্বর বাছাই, নম্বর, ধাপ, TrxID + শেষ ৪ সংখ্যা, "টাকা পাঠিয়েছি" */
@Composable
fun PayClaimSection(r: PayRequest, asUid: String?, onChanged: (PayRequest) -> Unit) {
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    val methods = r.activeMethods
    var method by rememberSaveable(r.id) { mutableStateOf(r.claim?.method?.takeIf { c -> methods.any { it.method == c } } ?: methods.first().method) }
    val m = methods.firstOrNull { it.method == method } ?: methods.first()
    var trx by rememberSaveable(r.id) { mutableStateOf("") }
    var last4 by rememberSaveable(r.id) { mutableStateOf("") }
    var tried by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val trxBad = !PayRules.trxOk(m.method, trx)
    val last4Bad = !PayRules.last4Ok(last4)
    Surface(color = cs.surfaceContainerHigh, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().testTag("pay_claim")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PayMethodPicker(methods, m.method) { method = it; trx = PayRules.cleanTrx(it, trx) }
            PayNumberRow(PayText.numberLabel(r.kind, m), m.number)
            PayStepList(PayText.steps(m, PayText.money(r.amount), PayText.who(r.kind)))
            OutlinedTextField(
                trx, { trx = PayRules.cleanTrx(m.method, it) },
                label = { Text("TrxID") },
                placeholder = { Text(if (m.method == "bkash") "8N7A6B5C4D" else "7XK29QF3") },
                supportingText = { Text(PayText.trxHint(m.method) + " · " + num(trx.length) + "/" + num(PayRules.trxMax(m.method))) },
                isError = (tried || trx.length >= PayRules.trxMax(m.method)) && trxBad,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("pay_trxid"),
            )
            OutlinedTextField(
                last4, { last4 = PayRules.cleanLast4(it) },
                label = { Text(tr("Sender's last 4 digits", "পাঠানো নম্বরের শেষ ৪ সংখ্যা")) },
                supportingText = { Text(PayText.LAST4_HINT) },
                isError = tried && last4Bad,
                placeholder = { Text("1234") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("pay_last4"),
            )
            ErrorText(errorMsg)
            Button(onClick = {
                tried = true
                errorMsg = when {
                    trxBad -> PayText.trxHint(m.method)
                    last4Bad -> PayText.LAST4_HINT
                    else -> null
                }
                if (errorMsg == null) scope.launch {
                    busy = true
                    runCatching { PayApi.claim(r.id, m.method, trx, last4, asUid) }.onSuccess(onChanged).onFailure { errorMsg = PayText.error(it) }
                    busy = false
                }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("pay_submit")) {
                ButtonContent(busy, if (r.status == "rejected") tr("I've sent it again", "আবার পাঠিয়েছি") else tr("I've sent the money", "টাকা পাঠিয়েছি"), R.drawable.ic_send)
            }
        }
    }
}

/** পাঠানোর পরে: কোন সেবায়, TrxID, শেষ ৪ সংখ্যা */
@Composable
fun PayClaimInfo(c: PayClaim) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.testTag("pay_claim_info")) {
        Text(PayText.method(c.method) + " · TrxID: " + c.trxId, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
        if (c.senderLast4.isNotBlank()) Text(tr("From a number ending ", "যে নম্বর থেকে: …") + c.senderLast4, style = MaterialTheme.typography.bodyMedium)
        SmallNote(tr("They'll check it in their app and confirm. You'll get a notification.", "তাঁরা নিজের অ্যাপে মিলিয়ে নিশ্চিত করবেন। নোটিফিকেশন পাবেন।"))
    }
}

/** রোগী: পেমেন্টে সমস্যা — কারণ বেছে আর লিখে */
@Composable
fun PayReportDialog(r: PayRequest, asUid: String?, initial: String = "not_confirmed", onDismiss: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var pick by remember { mutableStateOf(initial) }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = tr("Report a payment problem", "পেমেন্টের সমস্যা জানান"),
        onDismiss = { if (!busy) onDismiss() },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("Lifolo will look into it with ", "Lifolo বিষয়টা দেখবে — ") + r.bizName.ifBlank { PayText.who(r.kind) } + tr(".", "-এর সাথে কথা বলে।"), style = MaterialTheme.typography.bodyMedium)
                ChipRows(PayText.REPORT_REASONS, setOf(pick), perRow = 1, label = { PayText.reportReason(it) }) { pick = it }
                OutlinedTextField(note, { note = it.take(300) }, label = { Text(tr("What happened", "কী হয়েছিল")) }, minLines = 2, modifier = Modifier.fillMaxWidth().testTag("pay_report_note"))
                ErrorText(errorMsg)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    busy = true
                    errorMsg = null
                    runCatching { PayApi.report(r.id, pick, note, asUid) }.onSuccess { onDone() }.onFailure { errorMsg = PayText.error(it) }
                    busy = false
                }
            }, enabled = !busy && (pick != "other" || note.trim().length >= 3)) { ButtonContent(busy, tr("Send report", "রিপোর্ট পাঠান")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancel", "বাতিল")) } },
    )
}

// ------------------------------------------------------------
// ঢোকার পথ (রোগী): অর্ডার/পরীক্ষা/অ্যাপয়েন্টমেন্ট/অ্যাম্বুলেন্স/রসিদ
// ------------------------------------------------------------

/**
 * রোগীর কার্ডে: এই কাজের (refId) পেমেন্টের অনুরোধ থাকলে "টাকা পাঠান"/অবস্থা — চাপলে PaySheet।
 * অনুরোধ নেই বা চাবি বন্ধ: কিছুই নয়। কেয়ারগিভার মোডে রোগীর পেমেন্ট।
 */
@Composable
fun PayRefButton(refId: String, purpose: String) {
    if (refId.isBlank() || !PayGate.rememberOn(PayGate.keyFor(purpose) ?: return)) return
    val asUid = CareMode.asUid.value
    val all by PayStore.all.collectAsState()
    val bump = PayNav.changed.intValue
    LaunchedEffect(asUid, bump) { PayStore.refresh(asUid, bump) }
    val r = remember(all, refId, purpose, asUid) { PayStore.forRef(refId, purpose, asUid) } ?: return
    PayRefAction(r, asUid)
}

@Composable
fun PayRefAction(r: PayRequest, asUid: String?) {
    val text = when {
        // অগ্রিম না দিলেও বুকিং থাকে — শুধু "অগ্রিম বাকি"
        PayRules.advanceDue(r) -> tr("Advance due · pay ", "অগ্রিম বাকি · ") + PayText.money(r.amount) + tr("", " পাঠান")
        else -> when (r.status) {
            "open" -> tr("Pay ", "") + PayText.money(r.amount) + tr("", " পাঠান")
            "rejected" -> tr("Not received — check", "পাননি — দেখুন")
            "claimed" -> PayText.status("claimed")
            "refund_claimed", "refunded" -> if (PayRules.canAckRefund(r)) tr("Refund — confirm", "ফেরত — নিশ্চিত করুন") else PayText.status("refunded")
            else -> PayText.status(r.status)
        }
    }
    SmallAction(text, R.drawable.ic_payments, { PayNav.open(r, asUid) }, primary = r.status in setOf("open", "rejected"), modifier = Modifier.testTag("pay_entry_" + r.purpose))
}

/**
 * অ্যাম্বুলেন্স: যাত্রা শেষ হলে (তবেই) ভাড়া মোবাইল ব্যাংকিংয়ে — কখনো বাধ্যতামূলক নয়; ক্যাশে দিলে কিছু করতে হবে না।
 * অনুরোধ না থাকলে রোগী নিজে শুরু করেন (pay_request_create, ambulance_fare)।
 */
@Composable
fun AmbFarePay(reqId: String, status: String) {
    if (status != "done" || reqId.isBlank() || !PayGate.rememberOn(PayGate.AMBULANCE)) return
    val scope = rememberCoroutineScope()
    val asUid = CareMode.asUid.value
    val all by PayStore.all.collectAsState()
    val bump = PayNav.changed.intValue
    LaunchedEffect(asUid, bump) { PayStore.refresh(asUid, bump) }
    val r = remember(all, reqId, asUid) { PayStore.forRef(reqId, PayRules.AMB_FARE, asUid) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("amb_pay")) {
        if (r != null) {
            PayRefAction(r, asUid)
        } else {
            SmallAction(tr("Pay the fare by bKash/Nagad", "ভাড়া বিকাশ/নগদে দিন"), R.drawable.ic_payments, {
                scope.launch {
                    busy = true
                    errorMsg = null
                    runCatching { PayApi.create("ambreq", reqId, PayRules.AMB_FARE, asUid = asUid) }
                        .onSuccess { PayStore.put(it, asUid); PayNav.open(it, asUid) }.onFailure { errorMsg = PayText.error(it) }
                    busy = false
                }
            }, busy = busy, modifier = Modifier.testTag("amb_pay_start"))
            SmallNote(tr("Only if you want to. Paid in cash? Nothing to do.", "চাইলে তবেই। ক্যাশে দিয়ে থাকলে কিছু করতে হবে না।"))
        }
        ErrorText(errorMsg)
    }
}

/** রসিদে বাকি থাকলে রোগী: "বাকি টাকা অনলাইনে দিন" (pay_due_start) */
@Composable
fun PayDueButton(inv: Invoice, asUid: String?) {
    if (!inv.hasDue || !PayGate.rememberOn(PayGate.DUE)) return
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    Button(onClick = {
        scope.launch {
            busy = true
            errorMsg = null
            runCatching { PayApi.dueStart(inv.id, asUid) }.onSuccess { PayNav.open(it, asUid) }.onFailure { errorMsg = PayText.error(it) }
            busy = false
        }
    }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("receipt_pay_due")) {
        ButtonContent(busy, tr("Pay the due ", "বাকি ") + InvoiceText.money(inv.due, isBangla) + tr(" online", " অনলাইনে দিন"), R.drawable.ic_payments)
    }
    ErrorText(errorMsg)
}

/** বুকিংয়ের উত্তরে অগ্রিমের অনুরোধ থাকলে (pay_chamber_advance চালু) সাথে সাথে PaySheet */
object PayEntry {
    fun fromResponse(o: org.json.JSONObject?): PayRequest? {
        if (o == null) return null
        val p = o.optJSONObject("payRequest") ?: o.optJSONObject("pay") ?: return null
        return PayApi.parse(p).takeIf { it.id.isNotBlank() }
    }

    fun afterBooking(o: org.json.JSONObject?) {
        val r = fromResponse(o) ?: return
        if (r.purpose == PayRules.ADVANCE && PayGate.on(PayGate.CHAMBER_ADVANCE)) PayNav.open(r, CareMode.asUid.value)
    }
}

// ------------------------------------------------------------
// রোগী: "আমার রসিদ ও খরচ" › পেমেন্ট (কেয়ারগিভার মোডেও, asUid)
// ------------------------------------------------------------

@Composable
fun MyPaymentsList(asUid: String?) {
    val scope = rememberCoroutineScope()
    var items by remember(asUid) { mutableStateOf<List<PayRequest>?>(null) }
    var cursor by remember(asUid) { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var more by remember { mutableStateOf(false) }
    val bump = PayNav.changed.intValue
    LaunchedEffect(asUid, bump) {
        runCatching { PayApi.mine(asUid) }.onSuccess { items = it.items; cursor = it.cursor; PayStore.set(it.items, asUid); errorMsg = null }
            .onFailure { errorMsg = PayText.error(it); if (items == null) items = emptyList() }
    }
    val list = items
    LazyColumn(Modifier.fillMaxSize().testTag("my_payments"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { ErrorText(errorMsg) }
        when {
            list == null -> item { LoadingBox() }
            list.isEmpty() -> item {
                EmptyState(
                    R.drawable.ic_wallet, tr("No payments yet", "এখনো কোনো পেমেন্ট নেই"),
                    tr("When a doctor, pharmacy, lab or ambulance asks you to pay by bKash/Nagad, it shows here.", "ডাক্তার, ফার্মেসি, প্যাথলজি বা অ্যাম্বুলেন্স বিকাশ/নগদে টাকা চাইলে এখানে দেখাবে।"),
                    Modifier.fillMaxWidth().padding(top = 32.dp),
                )
            }
            else -> {
                // আগে যেগুলোতে কিছু করার আছে
                val sorted = list.sortedWith(compareByDescending<PayRequest> { PayRules.canClaim(it) || PayRules.canAckRefund(it) }.thenByDescending { it.createdAt })
                items(sorted, key = { it.id }) { r -> PayRow(r, showPayer = false) { PayNav.open(r, asUid) } }
                val c = cursor
                if (c != null) item {
                    OutlinedButton(onClick = {
                        scope.launch {
                            more = true
                            runCatching { PayApi.mine(asUid, c) }.onSuccess { p -> items = list + p.items.filter { n -> list.none { it.id == n.id } }; cursor = p.cursor }.onFailure { errorMsg = PayText.error(it) }
                            more = false
                        }
                    }, enabled = !more, modifier = Modifier.fillMaxWidth()) { ButtonContent(more, tr("Show more", "আরও দেখুন")) }
                }
            }
        }
        item { SmallNote(PayText.DIRECT) }
    }
}

/** তালিকার এক সারি (রোগী: কার কাছে; ব্যবসা: কে পাঠালেন) */
@Composable
fun PayRow(r: PayRequest, showPayer: Boolean, biz: Boolean = false, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow), modifier = Modifier.fillMaxWidth().testTag("pay_row")) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(InvoiceKinds.icon(r.kind), null, Modifier.size(22.dp), cs.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (showPayer) r.payerName.ifBlank { tr("Patient", "রোগী") } else r.bizName.ifBlank { PayText.who(r.kind) },
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    PayText.purpose(r.purpose) + (r.createdAt.takeIf { it > 0 }?.let { " · " + InvoiceText.dateTime(it, isBangla) } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                r.claim?.let { c -> Text(PayText.method(c.method) + " · " + c.trxId, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = cs.onSurfaceVariant) }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(PayText.money(r.amount), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                PayStatusTag(r.status, biz)
            }
        }
    }
}
