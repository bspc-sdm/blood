@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.bspc.blood

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import java.io.File

/*
 * ব্যবসার "পেমেন্ট": আজ কত এল; যাচাই বাকি (নিশ্চিত — আগে একবার জিজ্ঞেস; না পেলে কারণসহ ফেরত); তালিকা আর মাসের CSV;
 * নিশ্চিত হওয়া টাকা ফেরত (TrxID সহ); সেটিংস — নম্বরগুলো (২৪ ঘণ্টার বিরতি) আর ডাক্তারের চেম্বারপ্রতি অগ্রিম।
 * ডাক্তারের সহকারী শুধু চেম্বারের পেমেন্ট যাচাই/নিশ্চিত করতে পারেন (সেটিংস, ফেরত, CSV মালিকের)।
 */

/** ব্যবসার পাতায় "পেমেন্ট" বাটন (যাচাই বাকির সংখ্যাসহ) — সেই ব্যবসার কোনো পেমেন্টের চাবি চালু থাকলে তবেই */
@Composable
fun BizPaymentsButton(kind: String, bizId: String?, assistant: Boolean = false) {
    if (!PayGate.rememberBiz(kind)) return
    var pending by remember { mutableIntStateOf(0) }
    val bump = PayNav.changed.intValue
    LaunchedEffect(kind, bizId, bump) {
        val id = bizId ?: PayBiz.defaultBizId(kind) ?: return@LaunchedEffect
        runCatching { PayApi.summary(kind, id) }.onSuccess { pending = it.pending }
    }
    OutlinedButton(onClick = { PayNav.openBiz(kind, bizId, assistant) }, modifier = Modifier.fillMaxWidth().testTag("biz_payments")) {
        ButtonContent(false, tr("Payments", "পেমেন্ট") + if (pending > 0) tr(" — $pending to check", " — যাচাই বাকি ${num(pending)}") else "", R.drawable.ic_wallet)
    }
}

object PayBiz {
    /** bizId না জানা থাকলে (পুশে না এলে) নিজের ব্যবসা থেকে */
    /** সার্ভারে প্রতিটা ব্যবসার bizId = মালিকের uid (চেম্বারে ডাক্তারের) */
    @Suppress("UNUSED_PARAMETER")
    fun defaultBizId(kind: String): String? = AuthRepository.currentUid

    /** CSV ফাইল লিখে শেয়ার (অ্যাপের cache/rx — FileProvider-এর জায়গা) */
    fun shareCsv(context: android.content.Context, kind: String, month: String, text: String) {
        val dir = File(context.cacheDir, "rx").apply { mkdirs() }
        val f = File(dir, "lifolo-payments-$kind-$month.csv")
        f.writeText(text, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, tr("Share CSV", "CSV শেয়ার")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    val STATUS_FILTERS = listOf("all", "claimed", "open", "confirmed", "rejected", "refund_claimed", "refunded")

    fun changeText(c: PayMethodChange): String {
        if (c.method == "all" || c.change == "frozen") return tr("All numbers frozen", "সব নম্বর আটকে দেওয়া হয়েছে") + (if (c.at > 0) " · " + InvoiceText.dateTime(c.at, isBangla) else "")
        val what = when (c.change) {
            "added" -> tr("added", "যোগ হয়েছে")
            "removed" -> tr("removed", "সরানো হয়েছে")
            "activated" -> tr("turned on", "চালু হয়েছে")
            "deactivated" -> tr("turned off", "বন্ধ হয়েছে")
            else -> tr("changed", "বদলেছে")
        }
        val nums = listOfNotNull(c.oldLast4.takeIf { it.isNotBlank() }?.let { "…$it" }, c.newLast4.takeIf { it.isNotBlank() }?.let { "…$it" }).joinToString(" → ")
        return PayText.method(c.method) + " " + what + (if (nums.isNotBlank()) " ($nums)" else "") + (if (c.at > 0) " · " + InvoiceText.dateTime(c.at, isBangla) else "")
    }

    fun checkLabel(k: String): String = when (k) {
        "trxId" -> tr("TrxID matches", "TrxID মিলেছে")
        "amount" -> tr("Amount matches", "টাকার অঙ্ক মিলেছে")
        else -> tr("Last 4 digits match", "শেষ ৪ সংখ্যা মিলেছে")
    }
    fun filterLabel(s: String): String = if (s == "all") tr("All", "সব") else PayText.bizStatus(s)
}

@Composable
fun BizPaymentsDialog(b: PayNav.Biz, onDismiss: () -> Unit) {
    var bizId by remember { mutableStateOf(b.bizId) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(b) {
        if (bizId == null) bizId = PayBiz.defaultBizId(b.kind)
        if (bizId == null) errorMsg = tr("Couldn't find your business", "আপনার ব্যবসা পাওয়া যায়নি")
    }
    FullScreenDialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
            BackTopBar(tr("Payments", "পেমেন্ট") + " · " + InvoiceKinds.label(b.kind), onDismiss)
            val id = bizId
            if (id == null) {
                Column(Modifier.padding(16.dp)) { if (errorMsg != null) ErrorText(errorMsg) else LoadingBox() }
            } else {
                BizPaymentsPanel(b.kind, id, b.assistant, b.tab)
            }
        }
    }
}

/** পুরো "পেমেন্ট" ভাগ: উপরে আজকের হিসাব, তারপর যাচাই বাকি · তালিকা · সেটিংস */
@Composable
fun BizPaymentsPanel(kind: String, bizId: String, assistant: Boolean = false, startTab: Int = 0) {
    var tab by rememberSaveable { mutableIntStateOf(startTab) }
    var summary by remember { mutableStateOf<PaySummary?>(null) }
    val bump = PayNav.changed.intValue
    LaunchedEffect(kind, bizId, bump) { runCatching { PayApi.summary(kind, bizId) }.onSuccess { summary = it } }
    val tabs = listOf(
        PillTab(tr("To check", "যাচাই বাকি"), R.drawable.ic_check_circle, count = summary?.pending ?: 0),
        PillTab(tr("List", "তালিকা"), R.drawable.ic_list_alt),
    ) + if (assistant) emptyList() else listOf(PillTab(tr("Settings", "সেটিংস"), R.drawable.ic_settings_outline))
    Column(Modifier.fillMaxSize()) {
        PayTodayCard(summary)
        PillTabs(tab.coerceAtMost(tabs.lastIndex), tabs, { tab = it }, secondary = true)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (tab.coerceAtMost(tabs.lastIndex)) {
                0 -> PayInbox(kind, bizId, assistant)
                1 -> PayBizList(kind, bizId, assistant)
                else -> PaySettingsPanel(kind, bizId)
            }
        }
    }
}

/** আজ কত এল, কয়টা খোলা, কয়টা যাচাই বাকি */
@Composable
fun PayTodayCard(s: PaySummary?) {
    val cs = MaterialTheme.colorScheme
    Card(colors = CardDefaults.cardColors(containerColor = cs.secondaryContainer), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("pay_today")) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1.4f)) {
                Text(tr("Received today", "আজ পেয়েছেন"), style = MaterialTheme.typography.labelMedium, color = cs.onSecondaryContainer)
                Text(if (s == null) "—" else PayText.money(s.collected), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = cs.onSecondaryContainer, modifier = Modifier.testTag("pay_today_total"))
            }
            Column(Modifier.weight(1f)) {
                Text(tr("To check", "যাচাই বাকি"), style = MaterialTheme.typography.labelMedium, color = cs.onSecondaryContainer)
                Text(if (s == null) "—" else num(s.pending), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = if ((s?.pending ?: 0) > 0) cs.error else cs.onSecondaryContainer)
            }
            Column(Modifier.weight(1f)) {
                Text(tr("Open", "খোলা"), style = MaterialTheme.typography.labelMedium, color = cs.onSecondaryContainer)
                Text(if (s == null) "—" else num(s.open), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = cs.onSecondaryContainer)
            }
        }
    }
}

// ------------------------------------------------------------
// যাচাই বাকি: নিশ্চিত (আগে জিজ্ঞেস) / পাইনি (কারণ)
// ------------------------------------------------------------

@Composable
fun PayInbox(kind: String, bizId: String, assistant: Boolean) {
    var items by remember { mutableStateOf<List<PayRequest>?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    val bump = PayNav.changed.intValue
    LaunchedEffect(kind, bizId, bump) {
        runCatching { PayApi.listBiz(kind, bizId, status = "claimed") }.onSuccess { items = it.filter { r -> r.status == "claimed" }; errorMsg = null }
            .onFailure { errorMsg = PayText.error(it); if (items == null) items = emptyList() }
    }
    Column(Modifier.fillMaxWidth().testTag("pay_inbox"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ErrorText(errorMsg)
        InfoText(infoMsg)
        val list = items
        when {
            list == null -> LoadingBox()
            list.isEmpty() -> EmptyState(
                R.drawable.ic_check_circle, tr("Nothing to check", "যাচাই করার কিছু নেই"),
                tr("When a patient says they've sent money, it shows here. Check it in your app, then confirm.", "রোগী টাকা পাঠিয়েছেন জানালে এখানে আসবে। নিজের অ্যাপে মিলিয়ে তারপর নিশ্চিত করুন।"),
                Modifier.fillMaxWidth().padding(top = 16.dp),
            )
            else -> list.sortedBy { it.claim?.at ?: it.createdAt }.forEach { r ->
                PayClaimCard(r, assistant, onDone = { msg -> infoMsg = msg; PayNav.bump() })
            }
        }
        SmallNote(tr("Confirm only after you see the money in your own app. Confirming gives the patient a receipt.", "নিজের অ্যাপে টাকা দেখার পরেই নিশ্চিত করুন। নিশ্চিত করলে রোগী রসিদ পাবেন।"))
    }
}

/** একটা দাবি: কে, কত, কোন নম্বরে, TrxID, শেষ ৪ সংখ্যা — "পেয়েছি" / "পাইনি" */
@Composable
fun PayClaimCard(r: PayRequest, assistant: Boolean, onDone: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var confirming by remember { mutableStateOf(false) }
    var rejecting by remember { mutableStateOf(false) }
    val c = r.claim
    val to = r.methods.firstOrNull { it.method == c?.method }
    Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow), modifier = Modifier.fillMaxWidth().testTag("pay_claim_card")) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.payerName.ifBlank { tr("Patient", "রোগী") }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(PayText.purpose(r.purpose), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                Text(PayText.money(r.amount), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            if (c != null) {
                Text(PayText.method(c.method) + (to?.let { " → " + it.number } ?: ""), style = MaterialTheme.typography.bodyMedium)
                Text("TrxID: " + c.trxId, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.testTag("pay_claim_trx"))
                if (c.senderLast4.isNotBlank()) Text(tr("Sender number ends ", "পাঠানো নম্বরের শেষ ") + c.senderLast4, style = MaterialTheme.typography.bodyMedium)
                if (c.at > 0) Text(InvoiceText.dateTime(c.at, isBangla), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Text(tr("The patient says they sent it — check it in your own app", "রোগী বলছেন পাঠিয়েছেন — আপনার অ্যাপে মিলিয়ে নিন"), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                // ২৪ ঘণ্টার বেশি পড়ে আছে
                if (c.at > 0 && System.currentTimeMillis() - c.at > 24L * 3_600_000) Text(tr("Waiting over 24 hours — please check it", "২৪ ঘণ্টার বেশি অপেক্ষায় — দেখে নিন"), style = MaterialTheme.typography.bodySmall, color = cs.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallAction(tr("Received", "পেয়েছি"), R.drawable.ic_check_circle, { confirming = true }, primary = true, modifier = Modifier.testTag("pay_confirm"))
                SmallAction(tr("Not received", "পাইনি"), null, { rejecting = true }, modifier = Modifier.testTag("pay_reject"))
            }
        }
    }
    if (confirming) PayConfirmDialog(r, onDismiss = { confirming = false }) { inv ->
        confirming = false
        onDone(tr("Confirmed. The patient got a receipt.", "নিশ্চিত হয়েছে। রোগী রসিদ পেয়েছেন।"))
        if (inv != null) InvoiceNav.issued(inv)
    }
    if (rejecting) PayRejectDialog(r, onDismiss = { rejecting = false }) {
        rejecting = false
        onDone(tr("The patient was told.", "রোগীকে জানানো হয়েছে।"))
    }
}

/** নিশ্চিত করার ধাপ: TrxID আর টাকা আবার দেখিয়ে "হ্যাঁ, পেয়েছি" */
@Composable
fun PayConfirmDialog(r: PayRequest, onDismiss: () -> Unit, onDone: (Invoice?) -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var ticked by remember { mutableStateOf(setOf<String>()) }
    val c = r.claim
    FormDialog(
        title = tr("Got the money?", "টাকা পেয়েছেন?"),
        onDismiss = { if (!busy) onDismiss() },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // টাকা, TrxID, কোন সেবা, শেষ ৪ সংখ্যা, কখন — বড় করে (পর্যালোচনা P1)
                Text(PayText.money(r.amount), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                if (c != null) {
                    Text("TrxID: " + c.trxId, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Text(PayText.method(c.method) + (if (c.senderLast4.isNotBlank()) tr(" · from …", " · যে নম্বর থেকে: …") + c.senderLast4 else ""), style = MaterialTheme.typography.titleMedium)
                    if (c.at > 0) Text(InvoiceText.dateTime(c.at, isBangla), style = MaterialTheme.typography.bodySmall)
                }
                Text(tr("Open your own app and tick each one:", "নিজের অ্যাপ খুলে প্রতিটা মিলিয়ে টিক দিন:"), style = MaterialTheme.typography.bodyMedium)
                PayRules.confirmChecks(r).forEach { k ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        androidx.compose.material3.Checkbox(
                            checked = k in ticked, onCheckedChange = { on -> ticked = if (on) ticked + k else ticked - k },
                            modifier = Modifier.testTag("pay_check_$k"),
                        )
                        Text(PayBiz.checkLabel(k), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                SmallNote(tr("The patient gets a receipt once you confirm.", "নিশ্চিত করলে রোগী রসিদ পাবেন।"))
                ErrorText(errorMsg)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    busy = true
                    errorMsg = null
                    runCatching { PayApi.confirm(r.id, ticked) }.onSuccess(onDone).onFailure { errorMsg = PayText.error(it) }
                    busy = false
                }
            }, enabled = !busy && PayRules.confirmReady(r, ticked), modifier = Modifier.testTag("pay_confirm_yes")) { ButtonContent(busy, tr("Yes, received", "হ্যাঁ, পেয়েছি")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancel", "বাতিল")) } },
    )
}

/** "পাইনি": কারণ বাছাই (লাগবেই) + নোট — রোগী আবার পাঠাতে পারবেন */
@Composable
fun PayRejectDialog(r: PayRequest, onDismiss: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var reason by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = tr("Didn't get it?", "টাকা পাননি?"),
        onDismiss = { if (!busy) onDismiss() },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("The patient will be told why and can send the details again.", "রোগীকে কারণ জানানো হবে, উনি আবার জানাতে পারবেন।"), style = MaterialTheme.typography.bodyMedium)
                Text(tr("Reason", "কারণ"), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PayRules.REJECT_REASONS.forEach { c ->
                        FilterChip(selected = reason == c, onClick = { reason = c }, label = { Text(PayText.rejectReason(c)) }, modifier = Modifier.testTag("pay_reject_$c"))
                    }
                }
                OutlinedTextField(
                    note, { note = it.take(200) },
                    label = { Text(if (reason == "other") tr("Write the reason", "কারণ লিখুন") else tr("Note (optional)", "নোট (ঐচ্ছিক)")) },
                    placeholder = { Text(tr("e.g. This TrxID didn't match", "যেমন: এই TrxID মেলেনি")) },
                    modifier = Modifier.fillMaxWidth(),
                )
                ErrorText(errorMsg)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val rs = reason ?: return@TextButton
                scope.launch {
                    busy = true
                    errorMsg = null
                    runCatching { PayApi.reject(r.id, rs, note) }.onSuccess { onDone() }.onFailure { errorMsg = PayText.error(it) }
                    busy = false
                }
            }, enabled = !busy && PayRules.rejectOk(reason, note), modifier = Modifier.testTag("pay_reject_send")) { ButtonContent(busy, tr("Tell the patient", "রোগীকে জানান")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancel", "বাতিল")) } },
    )
}

// ------------------------------------------------------------
// তালিকা, ফেরত, CSV
// ------------------------------------------------------------

@Composable
fun PayBizList(kind: String, bizId: String, assistant: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by rememberSaveable { mutableStateOf("all") }
    var period by rememberSaveable { mutableStateOf("7d") }
    var items by remember { mutableStateOf<List<PayRequest>?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<PayRequest?>(null) }
    var month by rememberSaveable { mutableStateOf(InvoiceMonths.current()) }
    var exporting by remember { mutableStateOf(false) }
    val bump = PayNav.changed.intValue
    LaunchedEffect(kind, bizId, status, period, bump) {
        items = null
        runCatching { PayApi.listBiz(kind, bizId, status.takeIf { it != "all" }, period) }.onSuccess { items = it; errorMsg = null }
            .onFailure { errorMsg = PayText.error(it); items = emptyList() }
    }
    Column(Modifier.fillMaxWidth().testTag("pay_biz_list"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PayBiz.STATUS_FILTERS.forEach { s -> FilterChip(selected = status == s, onClick = { status = s }, label = { Text(PayBiz.filterLabel(s)) }) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            InvoiceKinds.PERIODS.forEach { p -> FilterChip(selected = period == p, onClick = { period = p }, label = { Text(InvoiceKinds.period(p)) }) }
        }
        ErrorText(errorMsg)
        val list = items
        when {
            list == null -> LoadingBox()
            list.isEmpty() -> SmallNote(tr("No payments in this period.", "এই সময়ে কোনো পেমেন্ট নেই।"))
            else -> {
                val got = list.filter { it.status == "confirmed" }.sumOf { it.amount }
                Text(tr("Received: ", "পেয়েছেন: ") + PayText.money(got) + " · " + tr("${list.size} payment(s)", "${num(list.size)}টা পেমেন্ট"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                list.sortedByDescending { it.createdAt }.forEach { r -> PayRow(r, showPayer = true, biz = true) { open = r } }
            }
        }
        // মাসের CSV (মালিক)
        if (!assistant) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { month = InvoiceMonths.shift(month, -1) }) { Text("‹") }
                        Text(InvoiceText.monthLabel(month), style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                        TextButton(onClick = { month = InvoiceMonths.shift(month, 1) }, enabled = month < InvoiceMonths.current()) { Text("›") }
                    }
                    OutlinedButton(onClick = {
                        scope.launch {
                            exporting = true
                            errorMsg = null
                            runCatching { PayApi.exportCsv(kind, bizId, month) }.onSuccess { (text, url) ->
                                when {
                                    text != null -> runCatching { PayBiz.shareCsv(context, kind, month, text) }.onFailure { errorMsg = it.message }
                                    url != null -> openUrl(context, url)
                                    else -> errorMsg = tr("Nothing to export", "দেওয়ার মতো কিছু নেই")
                                }
                            }.onFailure { errorMsg = PayText.error(it) }
                            exporting = false
                        }
                    }, enabled = !exporting, modifier = Modifier.fillMaxWidth().testTag("pay_csv")) { ButtonContent(exporting, tr("Share CSV", "CSV শেয়ার"), R.drawable.ic_share) }
                }
            }
        }
    }
    open?.let { r -> PayBizDetailDialog(r, assistant, onDismiss = { open = null }) { open = null; PayNav.bump() } }
}

/** ব্যবসার দিক থেকে একটা পেমেন্ট: টাইমলাইন, দাবি, নিশ্চিত/পাইনি (যাচাই বাকি), টাকা ফেরত (নিশ্চিত হলে, মালিক) */
@Composable
fun PayBizDetailDialog(r: PayRequest, assistant: Boolean, onDismiss: () -> Unit, onChanged: () -> Unit) {
    var refunding by remember { mutableStateOf(false) }
    CardDialog(onDismissRequest = onDismiss, tag = "pay_biz_detail") {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.payerName.ifBlank { tr("Patient", "রোগী") }, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                PayStatusTag(r.status, biz = true)
            }
            Text(PayText.purpose(r.purpose) + " · " + PayText.money(r.amount), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            PayTimeline(r)
            r.claim?.let { c ->
                Text(PayText.method(c.method) + " · TrxID: " + c.trxId, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                if (c.senderLast4.isNotBlank()) Text(tr("Sender number ends ", "পাঠানো নম্বরের শেষ ") + c.senderLast4)
            }
            r.refund?.let { f -> Text(tr("Refunded ", "ফেরত ") + PayText.money(f.amount.takeIf { it > 0 } ?: r.amount) + " · TrxID: " + f.trxId, fontFamily = FontFamily.Monospace) }
            if (r.invoiceId.isNotBlank()) BizReceiptAction(r.invoiceId)
            if (PayRules.canDecide(r)) PayClaimCard(r, assistant) { onChanged() }
            if (!assistant && PayRules.canRefund(r)) {
                OutlinedButton(onClick = { refunding = true }, modifier = Modifier.fillMaxWidth().testTag("pay_refund")) { ButtonContent(false, tr("Refund", "টাকা ফেরত দিন"), R.drawable.ic_history) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = onDismiss) { Text(tr("Close", "বন্ধ")) } }
        }
    }
    if (refunding) PayRefundDialog(r, onDismiss = { refunding = false }) { refunding = false; onChanged() }
}

/** টাকা ফেরত: নিজের অ্যাপে ফেরত পাঠিয়ে সেই TrxID (লাগবেই) আর কত (খালি = পুরোটা) */
@Composable
fun PayRefundDialog(r: PayRequest, onDismiss: () -> Unit, onDone: (PayRequest) -> Unit) {
    val scope = rememberCoroutineScope()
    val method = r.claim?.method ?: "bkash"
    var trx by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val amountOk = PayRules.refundAmountOk(amount, r.amount)
    val trxOk = PayRules.trxOk(method, trx)
    FormDialog(
        title = tr("Refund", "টাকা ফেরত"),
        onDismiss = { if (!busy) onDismiss() },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    tr("First send the money back from your ${PayText.method(method)} app, then write that TrxID here. Lifolo doesn't move any money.", "আগে নিজের ${PayText.method(method)} অ্যাপ থেকে টাকা ফেরত পাঠান, তারপর সেই TrxID এখানে লিখুন। Lifolo কোনো টাকা পাঠায় না।"),
                    style = MaterialTheme.typography.bodyMedium,
                )
                // শুধু যে নম্বর থেকে এসেছিল সেখানে (পর্যালোচনা P5) — অন্য নম্বরের ঘর নেই
                r.claim?.senderLast4?.takeIf { it.isNotBlank() }?.let { l4 ->
                    Text(tr("Refund only to the number it came from (ends $l4)", "যে নম্বর থেকে টাকা এসেছিল (শেষ ৪: ${num(l4)}) সেখানেই ফেরত দিন"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("pay_refund_to"))
                }
                SmallNote(tr("The patient confirms they got it back.", "রোগী ফেরত পাওয়া নিশ্চিত করবেন।"))
                OutlinedTextField(
                    trx, { trx = PayRules.cleanTrx(method, it) }, label = { Text(tr("Refund TrxID", "ফেরতের TrxID")) },
                    supportingText = { Text(PayText.trxHint(method)) }, isError = trx.isNotEmpty() && !trxOk, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("pay_refund_trx"),
                )
                MoneyField(
                    amount, { amount = it }, tr("Amount (blank = all ", "টাকা (খালি = পুরো ") + PayText.money(r.amount) + ")",
                    isError = !amountOk, supporting = if (!amountOk) tr("Up to ", "সর্বোচ্চ ") + PayText.money(r.amount) else null,
                )
                ErrorText(errorMsg)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    busy = true
                    errorMsg = null
                    runCatching { PayApi.refund(r.id, trx, InvoiceMath.parse(amount)?.takeIf { it > 0 }) }.onSuccess(onDone).onFailure { errorMsg = PayText.error(it) }
                    busy = false
                }
            }, enabled = !busy && trxOk && amountOk, modifier = Modifier.testTag("pay_refund_send")) { ButtonContent(busy, tr("Save refund", "ফেরত লিখুন")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancel", "বাতিল")) } },
    )
}

// ------------------------------------------------------------
// সেটিংস: নম্বরগুলো আর ডাক্তারের অগ্রিম
// ------------------------------------------------------------

/** চার সেবার ঘর — সার্ভারে যা আছে, বাকিগুলো ফাঁকা (বন্ধ) */
internal fun payMethodRows(saved: List<PayMethod>): List<PayMethod> =
    PayRules.METHODS.map { m -> saved.firstOrNull { it.method == m } ?: PayMethod(m, "", "personal", active = false) }

/** সেভের আগে ভুল: চালু ঘরে সঠিক নম্বর লাগবে; অন্তত একটা চালু */
internal fun payMethodsProblem(rows: List<PayMethod>, demo: Boolean = DemoLogin.isDemo(AuthRepository.currentUid)): String? {
    val bad = rows.firstOrNull { (it.active || it.number.isNotBlank()) && !PayRules.numberOk(it.number, demo) }
    return when {
        bad != null -> tr("Enter a valid ${PayText.method(bad.method)} number (01XXXXXXXXX)", "সঠিক ${PayText.method(bad.method)} নম্বর দিন (01XXXXXXXXX)")
        rows.none { it.active } -> tr("Turn on at least one number", "অন্তত একটা নম্বর চালু করুন")
        else -> null
    }
}

@Composable
fun PaySettingsPanel(kind: String, bizId: String) {
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    var saved by remember { mutableStateOf<PaySettings?>(null) }
    var rows by remember { mutableStateOf(payMethodRows(emptyList())) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    // নম্বর বদলাতে সাম্প্রতিক লগইন লাগে (401 reauth_required) — আবার লগইন করে একই সেভ আবার
    var reauth by remember { mutableStateOf(false) }
    LaunchedEffect(kind, bizId) {
        runCatching { PayApi.methodsGet(kind, bizId) }.onSuccess { saved = it; rows = payMethodRows(it.methods) }.onFailure { errorMsg = PayText.error(it); saved = PaySettings(emptyList()) }
    }
    fun save() {
        scope.launch {
            busy = true
            infoMsg = null
            errorMsg = null
            // ফাঁকা আর বন্ধ ঘর পাঠানো হয় না
            runCatching { PayApi.methodsSet(kind, bizId, rows.filter { it.number.isNotBlank() }) }
                .onSuccess { saved = it; rows = payMethodRows(it.methods); infoMsg = tr("Saved. Changed numbers start after 24 hours.", "সেভ হয়েছে। বদলানো নম্বর ২৪ ঘণ্টা পর চালু হবে।") }
                .onFailure { e -> if (PayReauth.needed(e)) reauth = true else errorMsg = PayText.error(e) }
            busy = false
        }
    }
    Column(Modifier.fillMaxWidth().testTag("pay_settings"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(tr("Numbers patients pay to", "রোগীরা যে নম্বরে টাকা দেবেন"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Surface(color = cs.tertiaryContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().testTag("pay_cooloff")) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                AppIcon(R.drawable.ic_schedule, null, Modifier.size(20.dp), cs.onTertiaryContainer)
                Spacer(Modifier.width(8.dp))
                Text(PayText.COOL_OFF + " " + tr("You get a notification for every change.", "প্রতিটা বদলে আপনি নোটিফিকেশন পাবেন।"), style = MaterialTheme.typography.bodyMedium, color = cs.onTertiaryContainer)
            }
        }
        // সাম্প্রতিক বদল আর "আমি করিনি" (নম্বর আটকে দেওয়া) — পর্যালোচনা P4
        saved?.let { st -> PayChangesBox(kind, bizId, st) { saved = st.copy(frozen = true) } }
        if (saved == null) {
            LoadingBox()
        } else {
            rows.forEachIndexed { i, m ->
                PayMethodEditor(m, saved?.methods?.firstOrNull { it.method == m.method }) { n -> rows = rows.toMutableList().also { it[i] = n } }
            }
            ErrorText(errorMsg)
            InfoText(infoMsg)
            Button(onClick = {
                errorMsg = payMethodsProblem(rows)
                if (errorMsg == null) save()
            }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("pay_methods_save")) { ButtonContent(busy, tr("Save numbers", "নম্বর সেভ করুন")) }
        }
        // ডাক্তার: চেম্বারপ্রতি অগ্রিম বুকিং ফি (চাইলে)
        if (kind == "chamber" && PayGate.rememberOn(PayGate.CHAMBER_ADVANCE)) {
            androidx.compose.material3.HorizontalDivider()
            ChamberAdvanceSettings(bizId, saved?.advances.orEmpty())
        }
    }
    if (reauth) PayReauthDialog(onDismiss = { reauth = false }) { reauth = false; save() }
}

/** নম্বরের সাম্প্রতিক বদল (শুধু শেষ ৪ সংখ্যা) আর "আমি করিনি" — চাপলে সাথে সাথে নম্বর আটকে যায় */
@Composable
fun PayChangesBox(kind: String, bizId: String, st: PaySettings, onFrozen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var asking by remember { mutableStateOf(false) }
    val recent = st.changes.filter { it.at <= 0 || System.currentTimeMillis() - it.at < 7L * 24 * 3_600_000 }.take(5)
    if (st.frozen) {
        Surface(color = cs.errorContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().testTag("pay_frozen")) {
            Text(
                tr("Payment numbers are frozen. Patients can't get new requests until Lifolo checks it.", "পেমেন্টের নম্বর আটকে রাখা আছে। Lifolo যাচাই না করা পর্যন্ত রোগীরা নতুন অনুরোধ পাবেন না।"),
                style = MaterialTheme.typography.bodyMedium, color = cs.onErrorContainer, modifier = Modifier.padding(12.dp),
            )
        }
        return
    }
    Surface(color = cs.surfaceContainerHigh, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().testTag("pay_changes")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (recent.isNotEmpty()) {
                Text(tr("Recent changes", "সাম্প্রতিক বদল"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                recent.forEach { c -> Text(PayBiz.changeText(c), style = MaterialTheme.typography.bodySmall) }
            }
            Text(tr("Didn't make a change? Freeze the numbers at once.", "কোনো বদল আপনি করেননি? সাথে সাথে নম্বর আটকে দিন।"), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            OutlinedButton(onClick = { asking = true }, modifier = Modifier.fillMaxWidth().testTag("pay_freeze")) {
                Text(tr("I didn't do this — freeze", "আমি করিনি — আটকে দিন"), color = cs.error)
            }
        }
    }
    if (asking) PayFreezeDialog(kind, bizId, onDismiss = { asking = false }) { asking = false; onFrozen() }
}

/** "আমি করিনি": নিশ্চিত করে pay_methods_freeze */
@Composable
fun PayFreezeDialog(kind: String, bizId: String, onDismiss: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = tr("Freeze payment numbers?", "পেমেন্টের নম্বর আটকে দেবেন?"),
        onDismiss = { if (!busy) onDismiss() },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    tr("No new payment requests go out, other logins are signed out, and Lifolo checks your account. Change your email password too.", "নতুন কোনো পেমেন্টের অনুরোধ যাবে না, অন্য সব লগইন বন্ধ হবে, আর Lifolo আপনার অ্যাকাউন্ট দেখবে। নিজের ইমেইলের পাসওয়ার্ডও বদলে নিন।"),
                    style = MaterialTheme.typography.bodyMedium,
                )
                ErrorText(errorMsg)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    busy = true
                    errorMsg = null
                    runCatching { PayApi.freeze(kind, bizId) }.onSuccess { onDone() }.onFailure { errorMsg = PayText.error(it) }
                    busy = false
                }
            }, enabled = !busy, modifier = Modifier.testTag("pay_freeze_yes")) { ButtonContent(busy, tr("Freeze", "আটকে দিন")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancel", "বাতিল")) } },
    )
}

/** নম্বর বদলাতে আবার লগইন (ইমেইলের কোড → নতুন Firebase লগইন), তারপর আগের কাজ আবার */
object PayReauth {
    fun needed(e: Throwable): Boolean = (e as? ServerError)?.code == "reauth_required"
}

@Composable
fun PayReauthDialog(onDismiss: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val email = AuthRepository.currentEmail.orEmpty()
    var sent by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = tr("Log in again", "আবার লগইন করুন"),
        onDismiss = { if (!busy) onDismiss() },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("For safety, changing payment numbers needs a fresh login.", "নিরাপত্তার জন্য পেমেন্টের নম্বর বদলাতে নতুন করে লগইন লাগে।"), style = MaterialTheme.typography.bodyMedium)
                if (email.isBlank()) {
                    Text(tr("Log out and log in again, then try.", "লগআউট করে আবার লগইন করুন, তারপর চেষ্টা করুন।"), style = MaterialTheme.typography.bodyMedium)
                } else if (sent) {
                    Text(tr("We sent a code to ", "কোড পাঠানো হয়েছে: ") + email, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        code, { code = PayRules.enDigits(it).filter { c -> c.isDigit() }.take(8) }, label = { Text(tr("Code", "কোড")) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("pay_reauth_code"),
                    )
                } else {
                    Text(email, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
                ErrorText(errorMsg)
            }
        },
        confirmButton = {
            if (email.isNotBlank()) TextButton(onClick = {
                scope.launch {
                    busy = true
                    errorMsg = null
                    if (!sent) runCatching { ServerApi.sendLoginCode(email) }.onSuccess { sent = true }.onFailure { errorMsg = it.message }
                    else runCatching { AuthRepository.signInWithEmailCode(email, code) }.onSuccess { onDone() }.onFailure { errorMsg = it.message }
                    busy = false
                }
            }, enabled = !busy && (!sent || code.length >= 4), modifier = Modifier.testTag("pay_reauth_go")) {
                ButtonContent(busy, if (sent) tr("Log in and save", "লগইন করে সেভ") else tr("Send code", "কোড পাঠান"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancel", "বাতিল")) } },
    )
}

/** একটা সেবার ঘর: চালু/বন্ধ, নম্বর (যাচাই), পার্সোনাল/মার্চেন্ট, নতুন নম্বর কবে চালু */
@Composable
fun PayMethodEditor(m: PayMethod, saved: PayMethod?, onChange: (PayMethod) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bad = m.number.isNotBlank() && !PayRules.numberOk(m.number)
    val changed = saved != null && saved.number.isNotBlank() && OnlineChambers.cleanNumber(saved.number) != OnlineChambers.cleanNumber(m.number)
    Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow), modifier = Modifier.fillMaxWidth().testTag("pay_method_row_" + m.method)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(PayText.method(m.method), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Switch(checked = m.active, onCheckedChange = { onChange(m.copy(active = it)) }, modifier = Modifier.testTag("pay_method_on_" + m.method))
            }
            if (m.active || m.number.isNotBlank()) {
                OutlinedTextField(
                    m.number, { onChange(m.copy(number = OnlineChambers.cleanNumber(it))) },
                    label = { Text(PayText.method(m.method) + tr(" number", " নম্বর")) }, placeholder = { Text("01XXXXXXXXX") },
                    isError = bad, supportingText = (if (bad) tr("Enter a valid number: 01XXXXXXXXX", "সঠিক নম্বর দিন: 01XXXXXXXXX") else null)?.let { t -> { Text(t) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("pay_method_number_" + m.method),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PayRules.TYPES.forEach { t ->
                        FilterChip(selected = m.type == t, onClick = { onChange(m.copy(type = t)) }, label = { Text(PayText.type(t)) })
                    }
                }
                SmallNote(
                    if (m.type == "merchant") tr("Patients use \"Payment\" to your merchant number.", "রোগীরা আপনার মার্চেন্ট নম্বরে \"পেমেন্ট\" করবেন।")
                    else tr("Patients use \"Send Money\" to this number. Your provider may limit a personal account used for business — a merchant number is safer.", "রোগীরা এই নম্বরে \"সেন্ড মানি\" করবেন। ব্যবসায় পার্সোনাল অ্যাকাউন্ট ব্যবহার করলে প্রতিষ্ঠান সীমা দিতে পারে — মার্চেন্ট নম্বর নিরাপদ।"),
                )
                val until = saved?.pendingUntil?.takeIf { it > System.currentTimeMillis() }
                if (changed) IconLine(R.drawable.ic_schedule, tr("The new number starts after 24 hours", OnlineText.BKASH_24H), cs.tertiary)
                else if (until != null) IconLine(R.drawable.ic_schedule, tr("The new number starts ", "নতুন নম্বর চালু হবে ") + joinOpensText(until, System.currentTimeMillis()) + tr("", " থেকে"), cs.tertiary)
            }
        }
    }
}

/** ডাক্তার: প্রতিটা সামনাসামনি চেম্বারে অগ্রিম (চালু/বন্ধ, কত, কত ঘণ্টা আগে বাতিল করলে ফেরত) */
@Composable
fun ChamberAdvanceSettings(bizId: String, fromServer: List<ChamberAdvance>) {
    val scope = rememberCoroutineScope()
    var places by remember { mutableStateOf<List<ChamberAdvance>?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    var busyKey by remember { mutableStateOf<String?>(null) }
    var reauthFor by remember { mutableStateOf<List<ChamberAdvance>?>(null) }
    LaunchedEffect(bizId, fromServer) {
        // সার্ভার pay_methods_get-এ সামনাসামনি চেম্বারগুলো দেয় (অগ্রিমসহ); না দিলে assistant_chambers থেকে
        places = fromServer.ifEmpty {
            runCatching { InvoiceApi.chambers(null) }.getOrDefault(emptyList()).map { c -> ChamberAdvance(c.chamberKey, c.name, c.nameBn) }
        }
    }
    // সার্ভার পুরো তালিকা একসাথে বদলায় — চালু থাকা সবগুলো পাঠানো হয়
    fun send(next: List<ChamberAdvance>, label: String, on: Boolean) {
        scope.launch {
            errorMsg = null
            infoMsg = null
            runCatching { PayApi.advancesSet(bizId, next) }
                .onSuccess { places = next; infoMsg = if (on) tr("Advance on for ", "অগ্রিম চালু: ") + label else tr("Advance off for ", "অগ্রিম বন্ধ: ") + label }
                .onFailure { e -> if (PayReauth.needed(e)) reauthFor = next else errorMsg = PayText.error(e) }
            busyKey = null
        }
    }
    Column(Modifier.fillMaxWidth().testTag("pay_advance"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("Advance for serials (optional)", "সিরিয়ালের অগ্রিম (ঐচ্ছিক)"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        SmallNote(tr("Patients pay a small advance when booking at this chamber. Cancel before the time you set and they get it back.", "এই চেম্বারে বুকিংয়ের সময় রোগী অল্প অগ্রিম দেবেন। আপনার দেওয়া সময়ের আগে বাতিল করলে ফেরত পাবেন।"))
        ErrorText(errorMsg)
        InfoText(infoMsg)
        val list = places
        when {
            list == null -> LoadingBox()
            list.isEmpty() -> SmallNote(tr("Add an in-person chamber first.", "আগে একটা সামনাসামনি চেম্বার যোগ করুন।"))
            else -> list.forEachIndexed { i, a ->
                ChamberAdvanceRow(a, busy = busyKey == a.chamberKey) { n ->
                    busyKey = a.chamberKey
                    send(list.toMutableList().also { it[i] = n }, n.label, n.on)
                }
            }
        }
    }
    reauthFor?.let { next -> PayReauthDialog(onDismiss = { reauthFor = null }) { reauthFor = null; send(next, "", next.any { it.on }) } }
}

val ADVANCE_HOURS = listOf(2, 6, 12, 24)

@Composable
fun ChamberAdvanceRow(a: ChamberAdvance, busy: Boolean, onSave: (ChamberAdvance) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var on by remember(a) { mutableStateOf(a.on) }
    var amount by remember(a) { mutableStateOf(if (a.on) InvoiceText.digits(a.amount.toLong().toString(), isBangla) else "") }
    var hours by remember(a) { mutableIntStateOf(a.refundableUntilHoursBefore.takeIf { it in ADVANCE_HOURS } ?: 24) }
    val v = InvoiceMath.parse(amount)
    val bad = on && (v == null || v <= 0.0 || v > 5000.0)
    Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow), modifier = Modifier.fillMaxWidth().testTag("pay_advance_row")) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(a.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Switch(checked = on, onCheckedChange = { on = it })
            }
            if (on) {
                MoneyField(amount, { amount = it }, tr("Advance (৳)", "অগ্রিম (৳)"), isError = amount.isNotBlank() && bad,
                    supporting = if (amount.isNotBlank() && bad) tr("Between ৳1 and ৳5,000", "৳১ থেকে ৳৫,০০০-এর মধ্যে") else null)
                Text(tr("Refund if cancelled at least", "ফেরত পাবেন যদি অন্তত"), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ADVANCE_HOURS.forEach { h -> FilterChip(selected = hours == h, onClick = { hours = h }, label = { Text(tr("$h h before", "${num(h)} ঘণ্টা আগে")) }) }
                }
            }
            SmallAction(tr("Save", "সেভ"), null, {
                onSave(if (on) a.copy(amount = v ?: 0.0, refundableUntilHoursBefore = hours) else a.copy(amount = 0.0))
            }, primary = true, enabled = !bad && (on != a.on || on), busy = busy, modifier = Modifier.testTag("pay_advance_save"))
        }
    }
}

// ------------------------------------------------------------
// ব্যবসা: কাজের কার্ড থেকে "অনলাইনে টাকা চান" (ওষুধের হোম ডেলিভারি, পরীক্ষা)
// ------------------------------------------------------------

/**
 * ফার্মেসি (অর্ডার নেওয়ার পর) / প্যাথলজি (সময় নিশ্চিত বা বিলের সময়): রোগীর কাছে টাকার অনুরোধ (pay_request_create)।
 * চাবি বন্ধ থাকলে কিছুই নয়। রোগী চাইলে ক্যাশেও দিতে পারেন।
 */
@Composable
fun PayAskAction(refType: String, refId: String, purpose: String, suggested: String = "") {
    if (!PayGate.rememberOn(PayGate.keyFor(purpose) ?: return)) return
    var asking by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<PayRequest?>(null) }
    val d = done
    if (d != null) {
        Tag(tr("Asked ", "চাওয়া হয়েছে ") + PayText.money(d.amount), MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        return
    }
    SmallAction(tr("Ask to pay online", "অনলাইনে টাকা চান"), R.drawable.ic_payments, { asking = true }, modifier = Modifier.testTag("pay_ask"))
    if (asking) {
        val scope = rememberCoroutineScope()
        var amount by remember { mutableStateOf(suggested.filter { it.isDigit() || it in '০'..'৯' || it == '.' }) }
        var busy by remember { mutableStateOf(false) }
        var errorMsg by remember { mutableStateOf<String?>(null) }
        val v = InvoiceMath.parse(amount)
        val bad = v == null || v <= 0.0
        FormDialog(
            title = tr("Ask to pay online", "অনলাইনে টাকা চান"),
            onDismiss = { if (!busy) asking = false },
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(PayText.purpose(purpose), style = MaterialTheme.typography.titleSmall)
                    MoneyField(amount, { amount = it }, tr("Amount (৳)", "টাকা (৳)"), isError = amount.isNotBlank() && bad)
                    SmallNote(tr("The patient sees your active numbers and sends the money straight to you. They can still pay in cash.", "রোগী আপনার চালু নম্বরগুলো দেখে সরাসরি আপনাকে পাঠাবেন। চাইলে ক্যাশেও দিতে পারবেন।"))
                    ErrorText(errorMsg)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        busy = true
                        errorMsg = null
                        runCatching { PayApi.create(refType, refId, purpose, v) }.onSuccess { done = it; asking = false; PayNav.bump() }.onFailure { errorMsg = PayText.error(it) }
                        busy = false
                    }
                }, enabled = !busy && !bad, modifier = Modifier.testTag("pay_ask_send")) { ButtonContent(busy, tr("Send", "পাঠান")) }
            },
            dismissButton = { TextButton(onClick = { asking = false }, enabled = !busy) { Text(tr("Cancel", "বাতিল")) } },
        )
    }
}
