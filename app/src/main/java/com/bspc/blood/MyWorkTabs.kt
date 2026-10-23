@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.bspc.blood

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject

/*
 * "আমার কাজ" → "আমার ফার্মেসি" আর "আমার প্যাথলজি": দুই ট্যাব (২.২৭.০, পারভেজ "দুটোই সাজাও", ১৯ অক্টোবর ২০২৬)।
 * আগে সব এক লম্বা পাতায় ছিল, আর আসা অর্ডারগুলো দোকানে/অনলাইনে, নতুন/পুরোনো সব একসাথে মেশানো।
 *  ফার্মেসি: "অর্ডার" (শুরুতে) — উপরে "কোড দিয়ে প্রেসক্রিপশন খুলুন", তারপর নতুন · চলছে/তৈরি · শেষ (সংখ্যাসহ)
 *            "দোকান" — দোকানের তথ্য, QR ও কোড, কয়েক দিন বন্ধ, অনলাইনে বিক্রি, স্টক, চেয়েও পাননি, ওষুধ খোঁজার অনুরোধ, একদম নিচে মুছে ফেলা
 *  ২.৩০.০ থেকে ফার্মেসিতে তিন ট্যাব: "অর্ডার", "ওষুধ খোঁজা" (রোগীদের ওষুধ খোঁজার অনুরোধ, চালু/বন্ধ, উত্তর না দেওয়ার সংখ্যা),
 *            "দোকান" (ভেতরে তথ্য · বিক্রি · স্টক · এনালাইটিক্স — শেষ তিনটা হালকা, পুরোটা ওয়েবে; PharmacyShop.kt)
 *  প্যাথলজি: "অনুরোধ" — নতুন · নমুনা/চলছে · রিপোর্ট দেওয়া · শেষ; "ল্যাব" — তথ্য, পরীক্ষা ও দাম, স্বাভাবিক মাত্রা, মুছে ফেলা
 * ভাগ সার্ভার ঠিক করে (server/lib/rxnet.js-এর pharmSeg/labSeg) — ওয়েবও একই ভাগ দেখায়।
 * নোটিফিকেশন: নতুন অর্ডার/অনুরোধ → প্রথম ট্যাবের "নতুন"; ওষুধ খোঁজার অনুরোধ → "ওষুধ খোঁজা" (২.৩০.০; WorkNav.target "finds")।
 */

/** তালিকা আর প্রতিটা ভাগে কয়টা (সার্ভারের counts — তালিকায় সব না এলেও পুরো সংখ্যা) */
data class SegList<T>(val items: List<T>, val counts: Map<String, Int>)

object WorkSegs {
    val PHARM = listOf("new", "active", "closed")
    val LAB = listOf("new", "active", "reported", "closed")

    /** সার্ভারের pharmSeg-এর মতো (পুরোনো সার্ভারে seg না এলে) */
    fun pharm(status: String) = when (status) {
        "sent" -> "new"
        "ready", "accepted", "out" -> "active"
        else -> "closed"
    }

    /** সার্ভারের labSeg-এর মতো */
    fun lab(o: LabOrder) = when (o.status) {
        "sent" -> if (o.home?.status == "confirmed") "active" else "new"
        "received" -> "active"
        "reported" -> "reported"
        else -> "closed"
    }

    fun counts(o: JSONObject?, segs: List<String>, list: List<String>): Map<String, Int> =
        segs.associateWith { s -> if (o != null && o.has(s)) o.optInt(s) else list.count { it == s } }

    fun pharmLabel(s: String) = when (s) {
        "new" -> tr("New", "নতুন")
        "active" -> tr("In progress / ready", "চলছে / তৈরি")
        else -> tr("Done", "শেষ")
    }

    fun labLabel(s: String) = when (s) {
        "new" -> tr("New", "নতুন")
        "active" -> tr("Sample / in progress", "নমুনা/চলছে")
        "reported" -> tr("Report given", "রিপোর্ট দেওয়া")
        else -> tr("Done", "শেষ")
    }

    /** দোকানের দিক থেকে অর্ডারের অবস্থা (রোগীর দিকেরটা RxNetText.orderStatus) */
    fun pharmStatus(s: String) = when (s) {
        "sent" -> tr("New — waiting for you", "নতুন — আপনার অপেক্ষায়")
        "ready" -> tr("Ready — customer will collect", "তৈরি — ক্রেতা নিতে আসবেন")
        "accepted" -> tr("Accepted", "অর্ডার নেওয়া হয়েছে")
        "out" -> tr("On the way", "পথে")
        "delivered" -> tr("Delivered", "পৌঁছে দেওয়া হয়েছে")
        "done" -> tr("Given", "দেওয়া হয়েছে")
        "cancelled" -> tr("You couldn't provide", "দিতে পারেননি")
        "withdrawn" -> tr("Customer took it back", "ক্রেতা ফিরিয়ে নিয়েছেন")
        else -> s
    }

    fun labStatus(s: String) = when (s) {
        "sent" -> tr("New", "নতুন")
        "received" -> tr("Sample taken", "নমুনা নেওয়া হয়েছে")
        "reported" -> tr("Report given", "রিপোর্ট দেওয়া হয়েছে")
        "cancelled" -> tr("Cancelled", "বাতিল")
        else -> s
    }
}

/** প্রেসক্রিপশনের QR: ভেতরে https://…/rx/{কোড} — স্ক্যান করলে কোড (দোকানের QR-এর ShopQr-এর মতো) */
object RxCodeScan {
    private val CODE = Regex("^[A-HJ-NP-Z2-9]{10}$")

    fun codeOf(raw: String): String? {
        val t = raw.trim()
        Regex("/rx/([A-Za-z0-9]{10})(?:[/?#].*)?$").find(t)?.let { m -> m.groupValues[1].uppercase().takeIf { CODE.matches(it) }?.let { return it } }
        return t.uppercase().takeIf { CODE.matches(it) }
    }

    fun scan(context: android.content.Context, onCode: (String) -> Unit, onError: (String) -> Unit) {
        val opts = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE).build()
        com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(context, opts).startScan()
            .addOnSuccessListener { b ->
                val raw = b.rawValue.orEmpty()
                val code = codeOf(raw)
                when {
                    code != null -> onCode(code)
                    raw.contains("/shop/") -> onError(tr("That's a shop QR. Scan the QR on the prescription.", "এটা দোকানের QR। প্রেসক্রিপশনের QR স্ক্যান করুন।"))
                    else -> onError(tr("This isn't a Lifolo prescription QR", "এটা Lifolo প্রেসক্রিপশনের QR নয়"))
                }
            }
            .addOnFailureListener { onError(tr("Couldn't open the scanner. Type the code instead.", "স্ক্যানার খোলা গেল না। কোডটা লিখুন।")) }
    }
}

/** দুই ট্যাবের পাতার উপরের বড় বাটন */
@Composable
fun OpenByCodeButton(onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp)) {
        ButtonContent(false, tr("Open a prescription by code", "কোড দিয়ে প্রেসক্রিপশন খুলুন"), R.drawable.ic_search)
    }
}

/** কোড লিখে বা প্রেসক্রিপশনের QR স্ক্যান করে খোলা (আগের কোডের ঘর আর "খুলুন" এখানে) */
@Composable
fun RxCodeDialog(note: String, busy: Boolean, error: String?, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var code by remember { mutableStateOf("") }
    var scanErr by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(tr("Open a prescription", "প্রেসক্রিপশন খুলুন")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SmallNote(note)
                OutlinedTextField(
                    code, { code = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(10); scanErr = null },
                    label = { Text(tr("Prescription code", "প্রেসক্রিপশনের কোড")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (!busy && code.length == 10) onOpen(code) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                SmallAction(tr("Scan the prescription QR", "প্রেসক্রিপশনের QR স্ক্যান করুন"), R.drawable.ic_photo_camera, {
                    scanErr = null
                    RxCodeScan.scan(context, { c -> code = c; onOpen(c) }, { scanErr = it })
                }, enabled = !busy)
                ErrorText(scanErr ?: error)
            }
        },
        confirmButton = { TextButton(onClick = { onOpen(code) }, enabled = !busy && code.length == 10) { ButtonContent(busy, tr("Open", "খুলুন")) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancel", "বাতিল")) } },
    )
}

/** ভাগে কিছু নেই — এক লাইন (চুক্তি: খালি ভাগে এক লাইনের লেখা, বড় ছবি নয়) */
@Composable
@Suppress("UNUSED_PARAMETER")
private fun SegEmpty(icon: Int, title: String, sub: String?) {
    OneLineEmpty(title)
}

/** তালিকায় সব আসেনি (সার্ভার শুধু সাম্প্রতিকগুলো পাঠায়) */
@Composable
private fun MoreNote(total: Int, shown: Int) {
    if (total > shown) SmallNote(tr("Showing the latest $shown of $total", "মোট ${num(total)}টার সাম্প্রতিক ${num(shown)}টা দেখানো হলো"))
}

/** দোকানে এসেছেন / অনলাইন অর্ডার; ল্যাবে আসবেন / বাসা থেকে নমুনা */
@Composable
private fun ModeTag(remote: Boolean, remoteText: String, localText: String) {
    val cs = MaterialTheme.colorScheme
    Tag(if (remote) remoteText else localText, if (remote) cs.primaryContainer else cs.tertiaryContainer, if (remote) cs.onPrimaryContainer else cs.onTertiaryContainer)
}

/** দুই ট্যাবের পাতার খোলস: উপরে ট্যাব, নিচে স্ক্রল করা অংশ */
@Composable
fun WorkTabsScaffold(tab: Int, tabs: List<PillTab>, onTab: (Int) -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        // স্থির ট্যাব (সর্বোচ্চ ৪টা, লেখা কাটে না — WorkQueues.kt)
        FixedTabs(tab, tabs, onTab)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), content = content)
    }
}

/** নোটিফিকেশন থেকে কোন ট্যাব ("orders" | "shop") — একবার পড়ে মুছে ফেলা */
@Composable
fun ConsumeWorkTarget(onTarget: (String) -> Unit) {
    val target = WorkNav.target.value
    LaunchedEffect(target) {
        if (target != null) {
            onTarget(target)
            WorkNav.target.value = null
        }
    }
}

// ------------------------------------------------------------
// ফার্মেসি — "অর্ডার" ট্যাব
// ------------------------------------------------------------

@Composable
fun PharmacyOrdersTab(data: SegList<PharmOrder>?, loadError: String?, seg: String, onSeg: (String) -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    var codeOpen by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf(false) }
    var codeErr by remember { mutableStateOf<String?>(null) }
    var opened by remember { mutableStateOf<PharmRx?>(null) }
    var accepting by remember { mutableStateOf<PharmOrder?>(null) }
    var delivering by remember { mutableStateOf<PharmOrder?>(null) }
    val invPharmacy = invOn(InvoiceFeatures.key("pharmacy"))
    var busyId by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    fun open(c: String, fromDialog: Boolean) {
        scope.launch {
            opening = true
            codeErr = null
            errorMsg = null
            runCatching { RxNet.pharmacyOpen(c) }
                .onSuccess { opened = it; codeOpen = false }
                .onFailure { if (fromDialog) codeErr = it.message else errorMsg = it.message }
            opening = false
        }
    }
    fun update(o: PharmOrder, status: String, reason: String = "", price: String = "", eta: String = "") {
        scope.launch {
            busyId = o.id
            errorMsg = null
            infoMsg = null
            runCatching { RxNet.orderUpdate(o.id, status, reason, price, eta) }
                .onSuccess { r ->
                    infoMsg = tr("The patient was told", "রোগীকে জানানো হয়েছে")
                    // "পাঠিয়ে দিয়েছি": লাইন রাখা থাকলে সার্ভার রসিদ দেয় (বাকি — ক্যাশ অন ডেলিভারি)
                    if (invPharmacy) InvoiceApi.invoiceIn(r)?.let { InvoiceNav.issued(it) }
                }.onFailure { errorMsg = it.message }
            busyId = null
            onChanged()
        }
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OpenByCodeButton { codeErr = null; codeOpen = true }
        SmallNote(tr("Open with the code under the QR and note how many you give — the rest can be given by another pharmacy.", "QR-এর নিচের কোড দিয়ে খুলে কয়টা দিলেন লিখুন — বাকিটা অন্য ফার্মেসি দিতে পারবে।"))
    }
    WorkSegTabs(seg, WorkQueue.counts(data?.counts), onSeg)
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ErrorText(errorMsg ?: loadError)
        InfoText(infoMsg)
        val list = data?.items?.filter { WorkQueue.of(it.seg) == seg }
        when {
            list == null -> if (loadError == null) LoadingBox()
            list.isEmpty() -> when (seg) {
                "new" -> SegEmpty(R.drawable.ic_check_circle, tr("No new orders now", "এখন নতুন কোনো অর্ডার নেই"), tr("When a customer scans your shop QR or sends a prescription, it comes here.", "ক্রেতা দোকানের QR স্ক্যান করলে বা প্রেসক্রিপশন পাঠালে এখানে আসবে।"))
                "active" -> SegEmpty(R.drawable.ic_local_shipping, tr("Nothing in progress", "চলমান কোনো অর্ডার নেই"), tr("Orders you've accepted or made ready show here until the medicines are given.", "যে অর্ডার নিয়েছেন বা তৈরি করেছেন, ওষুধ দেওয়া পর্যন্ত এখানে থাকবে।"))
                else -> SegEmpty(R.drawable.ic_history, tr("Nothing finished yet", "এখনো শেষ হওয়া কোনো অর্ডার নেই"), null)
            }
            else -> {
                list.sortedByDescending { it.createdAt }.forEach { o ->
                    val online = o.mode == "online"
                    val open = o.status in setOf("sent", "ready", "accepted", "out")
                    Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow), modifier = Modifier.testTag("order_card")) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(o.patientName.ifBlank { "-" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                ModeTag(online, tr("Online order", "অনলাইন অর্ডার"), tr("In the shop", "দোকানে এসেছেন"))
                            }
                            // "নতুন" ভাগের ভেতরে আবার "নতুন" লেখা নয় — শুধু কতক্ষণ আগে; অন্য অবস্থায় অবস্থার নাম
                            Text(
                                if (o.status == "sent") timeAgo(o.createdAt) else WorkSegs.pharmStatus(o.status) + " · " + timeAgo(o.createdAt),
                                style = MaterialTheme.typography.bodySmall, color = if (o.status == "sent") cs.primary else cs.onSurfaceVariant,
                            )
                            if (online && (o.price.isNotBlank() || o.eta.isNotBlank())) IconLine(R.drawable.ic_payments, listOf(o.price, o.eta).filter { it.isNotBlank() }.joinToString(" · "))
                            if (online && o.address.isNotBlank()) IconLine(R.drawable.ic_location_on, o.address)
                            // ফোন নম্বর ইংরেজি অঙ্কে (চুক্তি)
                            if (online && o.phone.isNotBlank()) IconLine(R.drawable.ic_call, phoneText(o.phone))
                            if (online && open && o.deliveryFee.isNotBlank()) IconLine(R.drawable.ic_local_shipping, tr("Delivery charge: ", "ডেলিভারি চার্জ: ") + o.deliveryFee)
                            if (o.note.isNotBlank()) IconLine(R.drawable.ic_edit_note, o.note)
                            if (o.missed.isNotEmpty()) Text(tr("Not available: ", "নেই: ") + o.missed.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = cs.error)
                            if (open) {
                                val busy = busyId == o.id
                                val free = busyId == null
                                val openLabel = if (online) tr("Open & note medicines", "খুলে ওষুধ লিখুন") else tr("Open", "খুলুন")
                                val deliver = invPharmacy && online && o.status in setOf("accepted", "out")
                                // একটাই মূল কাজ (পরের ধাপ), বাকিটা "আরও"-তে
                                val primary: String = when {
                                    !online -> "open"
                                    o.status == "sent" -> "accept"
                                    o.status == "accepted" -> "out"
                                    deliver -> "delivered"
                                    else -> "open"
                                }
                                ActionRow(
                                    key = o.id,
                                    primary = {
                                        when (primary) {
                                            "accept" -> PrimaryAction(tr("Accept", "অর্ডার নিলাম"), R.drawable.ic_check_circle, { accepting = o }, enabled = free)
                                            "out" -> PrimaryAction(tr("Sent out", "পাঠিয়ে দিয়েছি"), R.drawable.ic_local_shipping, { update(o, "out") }, busy = busy, enabled = free)
                                            "delivered" -> PrimaryAction(tr("Delivered", "পৌঁছে দিয়েছি"), R.drawable.ic_payments, { delivering = o }, enabled = free)
                                            else -> PrimaryAction(openLabel, R.drawable.ic_medication, { open(o.code, false) }, enabled = !opening)
                                        }
                                    },
                                    more = {
                                        if (primary != "open") SmallAction(openLabel, R.drawable.ic_medication, { open(o.code, false) }, enabled = !opening)
                                        if (!online && o.status == "sent") SmallAction(tr("Ready", "তৈরি"), R.drawable.ic_check_circle, { update(o, "ready") }, busy = busy, enabled = free)
                                        if (online && o.status == "sent") SmallAction(tr("Sent out", "পাঠিয়ে দিয়েছি"), R.drawable.ic_local_shipping, { update(o, "out") }, enabled = free)
                                        // পৌঁছে দিলে লাইনসহ রসিদ (InvoiceForms.kt; inv_pharmacy)
                                        if (deliver && primary != "delivered") SmallAction(tr("Delivered", "পৌঁছে দিয়েছি"), R.drawable.ic_payments, { delivering = o }, enabled = free)
                                        if (invPharmacy && o.invoiceId.isNotBlank()) BizReceiptAction(o.invoiceId)
                                        // হোম ডেলিভারির দাম আগে (লাইফলো পে, pay_delivery)
                                        if (online && o.status in setOf("accepted", "out")) PayAskAction("rx_order", o.id, PayRules.DELIVERY)
                                        // কল (ফোন কল · অ্যাপ কল · ভিডিও কল) · মেসেজ
                                        ContactActions(CallCtx.RX_ORDER, o.id, peerName = o.patientName, phone = o.phone.takeIf { online && it.isNotBlank() }, style = ContactStyle.CHIPS,
                                            onMessage = { openChat(CallCtx.RX_ORDER, o.id) })
                                        SmallAction(tr("Can't", "পারব না"), R.drawable.ic_close, { update(o, "cancelled", tr("Medicines not available", "ওষুধ নেই")) }, enabled = free)
                                    },
                                )
                            }
                        }
                    }
                }
                MoreNote(WorkQueue.counts(data?.counts)[seg] ?: 0, list.size)
            }
        }
    }
    if (codeOpen) {
        RxCodeDialog(
            tr("Enter the code under the prescription's QR, or scan the QR.", "প্রেসক্রিপশনের QR-এর নিচের কোড দিন, অথবা QR স্ক্যান করুন।"),
            opening, codeErr, { open(it, true) }, { codeOpen = false },
        )
    }
    opened?.let { rx -> DispenseDialog(rx, onDismiss = { opened = null; onChanged() }) }
    delivering?.let { o -> RxDeliveredDialog(o, onDismiss = { delivering = null }) { msg -> delivering = null; if (msg != null) infoMsg = msg; onChanged() } }
    accepting?.let { o ->
        AcceptOrderDialog(o, onDismiss = { accepting = null }) { price, eta ->
            accepting = null
            update(o, "accepted", price = price, eta = eta)
        }
    }
}

// ------------------------------------------------------------
// ফার্মেসি — "দোকান" ট্যাবের অংশ (তথ্যের কার্ড, বন্ধ রাখা আর মুছে ফেলা Pharmacy.kt-এর MyPharmacyPanel-এ)
// ------------------------------------------------------------

/** দোকানের QR ও কোড, পোস্টার */
@Composable
fun PharmacyQrCard(h: PharmacyHome) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val qr = remember(h.shopCode) { Qr.bitmap(ShopQr.url(h.shopCode), 360)?.asImageBitmap() }
                // QR-এর নিচেই কোড (প্রেসক্রিপশনের QR-এর মতো) — QR না থাকলে পাশে বড় করে
                if (qr != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(qr, tr("Shop QR", "দোকানের QR"), Modifier.size(96.dp).clip(RoundedCornerShape(8.dp)))
                        QrCodeText(h.shopCode)
                    }
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(tr("Shop QR & code", "দোকানের QR ও কোড"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (qr == null) Text(h.shopCode, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = cs.primary)
                    SmallNote(tr("Customers scan it, or type your shop's name or this code, to send you their prescription.", "ক্রেতা QR স্ক্যান করলে, দোকানের নাম বা এই কোড লিখলে প্রেসক্রিপশন আপনার কাছে চলে আসবে।"))
                }
            }
            SmallAction(tr("Open poster to print", "ছাপার পোস্টার খুলুন"), R.drawable.ic_share, { openUrl(context, ShopQr.url(h.shopCode)) })
        }
    }
}

/** অনলাইনে বিক্রি আর চেয়েও পাননি ("দোকান › তথ্য"; স্টক আর হিসাব ২.৩০.০ থেকে নিজের ভাগে — PharmacyShop.kt) */
@Composable
fun PharmacyToolsCard(h: PharmacyHome, onChanged: (String?) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var onlineOpen by remember { mutableStateOf(false) }
    var missedOpen by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Selling online", "অনলাইনে বিক্রি"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Tag(
                    if (h.online.enabled) tr("Selling online", "অনলাইনে বিক্রি চালু") else tr("In-shop only", "শুধু দোকানে"),
                    if (h.online.enabled) cs.tertiaryContainer else cs.surfaceVariant,
                    if (h.online.enabled) cs.onTertiaryContainer else cs.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = { onlineOpen = true }, modifier = Modifier.fillMaxWidth()) { ButtonContent(false, tr("Selling online", "অনলাইনে বিক্রি"), R.drawable.ic_local_shipping) }
            OutlinedButton(onClick = { missedOpen = true }, modifier = Modifier.fillMaxWidth()) {
                ButtonContent(false, tr("Asked for but not available", "চেয়েও পাননি এমন ওষুধ") + (h.missed.size.takeIf { it > 0 }?.let { " (${num(it)})" } ?: ""), R.drawable.ic_list_alt)
            }
        }
    }
    if (onlineOpen) OnlineSettingsDialog(h, onDismiss = { onlineOpen = false }) { onlineOpen = false; onChanged(tr("Saved", "সেভ হয়েছে")) }
    if (missedOpen) MissedDialog(h.missed, onDismiss = { missedOpen = false; onChanged(null) })
}

/** ওষুধ খোঁজার অনুরোধ (চালু/বন্ধ আর উত্তর) — কার্ডে */
@Composable
fun PharmacyFindCard() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) { PharmacyFindRequests() }
    }
}

/** একদম নিচে, আলাদা করে: মুছে ফেলা */
@Composable
fun DangerZone(text: String, enabled: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        // লাল (error) রঙে — ২.২৭.০-এ অ্যাপে primary রঙে দেখাত (ফার্মেসি আর ল্যাব দুটোতেই ঠিক, ২.৩০.০)
        TextButton(
            onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("danger_zone"),
            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            ButtonContent(false, text, R.drawable.ic_delete)
        }
    }
}

// ------------------------------------------------------------
// প্যাথলজি
// ------------------------------------------------------------

/** নিজের প্যাথলজি: না থাকলে নিবন্ধন; থাকলে "অনুরোধ" (শুরুতে) আর "ল্যাব" — অনুমোদনের আগে শুধু ল্যাবের তথ্য */
@Composable
fun MyLabPanel(profile: Profile) {
    val scope = rememberCoroutineScope()
    var lab by remember { mutableStateOf<Lab?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    var orders by remember { mutableStateOf<SegList<LabOrder>?>(null) }
    var ordersErr by remember { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var seg by rememberSaveable { mutableStateOf("new") }
    var confirmDelete by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    // বিল ও রসিদ (inv_lab) — বন্ধ থাকলে দুই ট্যাব আগের মতো
    val invLab = invOn(InvoiceFeatures.key("lab"))
    ConsumeWorkTarget { t -> if (t == "shop") tab = 1 else if (t == "receipts" && Features.on(InvoiceFeatures.key("lab"))) tab = 2 else { tab = 0; seg = "new" } }
    LaunchedEffect(reload) {
        runCatching { RxNet.labMine() }.onSuccess { lab = it; errorMsg = null }.onFailure { errorMsg = it.message }
        loaded = true
        if (lab?.status == "approved") runCatching { RxNet.labOrders() }.onSuccess { orders = it; ordersErr = null }.onFailure { ordersErr = it.message }
    }
    if (editing) {
        androidx.activity.compose.BackHandler { editing = false }
        LabEditScreen(lab, profile, onBack = { editing = false }) { pending ->
            editing = false
            infoMsg = if (pending) tr("Sent for approval. An admin will check the licence.", "অনুমোদনের জন্য পাঠানো হয়েছে। অ্যাডমিন লাইসেন্স যাচাই করবেন।") else tr("Saved", "সেভ হয়েছে")
            reload++
        }
        return
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(tr("Remove your lab?", "প্যাথলজি মুছে ফেলবেন?")) },
            text = { Text(tr("It will disappear from the list. You can add it again later (it will need approval again).", "তালিকা থেকে সরে যাবে। পরে আবার যোগ করা যাবে (আবার অনুমোদন লাগবে)।")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        busy = true
                        errorMsg = null
                        runCatching { RxNet.labDelete() }.onSuccess { infoMsg = tr("Removed", "মুছে ফেলা হয়েছে"); orders = null; tab = 0; reload++ }.onFailure { errorMsg = it.message }
                        busy = false
                    }
                }) { Text(tr("Remove", "মুছে ফেলুন"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("Cancel", "বাতিল")) } },
        )
    }
    val l = lab
    when {
        !loaded -> Column(Modifier.fillMaxSize().padding(16.dp)) { LoadingBox() }
        l == null -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ErrorText(errorMsg)
            InfoText(infoMsg)
            if (errorMsg == null) {
                EmptyState(R.drawable.ic_local_hospital, tr("Run a diagnostic centre?", "ডায়াগনস্টিক সেন্টার চালান?"),
                    tr("Join so patients can send you tests from their prescriptions, and give reports in the app. Free.", "যোগ দিন — রোগীরা প্রেসক্রিপশন থেকে আপনার কাছে পরীক্ষা পাঠাবেন, রিপোর্ট অ্যাপেই দেবেন। বিনামূল্যে।"),
                    Modifier.padding(top = 24.dp))
                Button(onClick = { editing = true }, modifier = Modifier.fillMaxWidth()) { ButtonContent(false, tr("Add my lab", "আমার প্যাথলজি যোগ করুন"), R.drawable.ic_add) }
            }
        }
        l.status != "approved" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ErrorText(errorMsg)
            InfoText(infoMsg)
            LabInfoTab(l, busy, onEdit = { editing = true }, onDelete = { confirmDelete = true }, onDocs = { reload++ })
        }
        else -> WorkTabsScaffold(
            tab,
            listOf(
                PillTab(tr("Requests", "অনুরোধ"), R.drawable.ic_list_alt, count = orders?.counts?.get("new") ?: 0),
                PillTab(tr("Lab", "ল্যাব"), R.drawable.ic_local_hospital),
            ) + (if (invLab) listOf(PillTab(tr("Bills", "বিল"), R.drawable.ic_payments)) else emptyList()),
            { tab = it },
        ) {
            if (tab == 0) {
                // কাজের তালিকা একদম ওপরে; খোলা/বন্ধ আর বাসা থেকে নমুনার সুইচ "ল্যাব" ট্যাবে (আজকের অবস্থা "আমার কাজ"-এর প্রথম পাতায়)
                LabRequestsTab(orders, ordersErr, seg, { seg = it }) { reload++ }
            } else if (tab == 2 && invLab) {
                LabBillsTab(l.id)
            } else {
                LabProviderTop(l) { reload++ }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ErrorText(errorMsg)
                    InfoText(infoMsg)
                    LabInfoTab(l, busy, onEdit = { editing = true }, onDelete = { confirmDelete = true }, onDocs = { reload++ })
                }
            }
        }
    }
}

/** "ল্যাব" ট্যাব: তথ্য, পরীক্ষা ও দাম, স্বাভাবিক মাত্রা (দুটোই ওয়েবে — বসে করার কাজ), একদম নিচে মুছে ফেলা */
@Composable
private fun LabInfoTab(l: Lab, busy: Boolean, onEdit: () -> Unit, onDelete: () -> Unit, onDocs: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(l.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(statusText(l.status), style = MaterialTheme.typography.labelLarge, color = cs.primary)
            StatusReasonLine(l.status, l.statusReason)
            DocsNeededNotice("lab", l.id, VerifyDocs.lab(), l.docsMissing, l.docsRequested, l.docsNote, onDocs)
            IconLine(R.drawable.ic_call, num(l.phone))
            if (l.address.isNotBlank()) IconLine(R.drawable.ic_location_on, l.address)
            if (l.hours.isNotBlank()) IconLine(R.drawable.ic_schedule, l.hours)
            if (l.homeCollection) IconLine(R.drawable.ic_home_health, tr("Collects samples from home", "বাসা থেকে নমুনা নেয়"))
            if (l.status == "pending") SmallNote(tr("You'll appear in the list after approval.", "অনুমোদনের পর তালিকায় দেখাবে।"))
            OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) { ButtonContent(false, tr("Edit details", "তথ্য বদলান"), R.drawable.ic_edit) }
            if (l.status == "suspended" || l.status == "rejected") AppealButton("other")
        }
    }
    if (l.status == "approved") {
        Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("Tests & reports", "পরীক্ষা ও রিপোর্ট"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                l.prices?.let { p -> if (p.count > 0) IconLine(R.drawable.ic_payments, tr("${p.count} tests priced · from ${LabPriceText.taka(p.from)}", "${num(p.count)}টা পরীক্ষার দাম বসানো · ${LabPriceText.taka(p.from)} থেকে")) }
                SmallNote(tr("Prices and your machine's normal ranges are set on the web (easier at a desk).", "পরীক্ষার দাম আর আপনার মেশিনের স্বাভাবিক মাত্রা ওয়েবে বসান (বসে করা সহজ)।"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallAction(tr("Tests & prices", "পরীক্ষা ও দাম"), R.drawable.ic_payments, { openUrl(context, ServerApi.BASE_URL + "/work/lab/orders/prices") })
                    SmallAction(tr("Normal ranges", "স্বাভাবিক মাত্রা"), R.drawable.ic_tune, { openUrl(context, ServerApi.BASE_URL + "/work/lab/orders/ranges") })
                }
            }
        }
    }
    if (l.status != "suspended") DangerZone(tr("Remove my lab", "প্যাথলজি মুছে ফেলুন"), !busy, onDelete)
}

/** "অনুরোধ" ট্যাব: কোড দিয়ে খোলা, তারপর নতুন · নমুনা/চলছে · রিপোর্ট দেওয়া · শেষ */
@Composable
private fun LabRequestsTab(data: SegList<LabOrder>?, loadError: String?, seg: String, onSeg: (String) -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    var codeOpen by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf(false) }
    var codeErr by remember { mutableStateOf<String?>(null) }
    var entering by remember { mutableStateOf<LabOrder?>(null) }
    var viewing by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf<LabOrder?>(null) }
    var declining by remember { mutableStateOf<LabOrder?>(null) }
    var billing by remember { mutableStateOf<LabOrder?>(null) }
    val invLab = invOn(InvoiceFeatures.key("lab"))
    var busyId by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OpenByCodeButton { codeErr = null; codeOpen = true }
        SmallNote(tr("Patient came with a paper prescription? Open it with the code — the tests are added here.", "রোগী কাগজের প্রেসক্রিপশন নিয়ে এলে কোড দিয়ে খুলুন — পরীক্ষাগুলো এখানে যোগ হবে।"))
    }
    WorkSegTabs(seg, WorkQueue.counts(data?.counts), onSeg)
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ErrorText(errorMsg ?: loadError)
        InfoText(infoMsg)
        val list = data?.items?.filter { WorkQueue.of(it.seg) == seg }
        when {
            list == null -> if (loadError == null) LoadingBox()
            list.isEmpty() -> when (seg) {
                "new" -> SegEmpty(R.drawable.ic_check_circle, tr("No new requests now", "এখন নতুন কোনো অনুরোধ নেই"), tr("Tests patients send from their prescriptions come here.", "রোগীরা প্রেসক্রিপশন থেকে পরীক্ষা পাঠালে এখানে আসবে।"))
                "active" -> SegEmpty(R.drawable.ic_schedule, tr("No samples waiting", "এখন কোনো নমুনা বা চলমান পরীক্ষা নেই"), tr("After you take a sample (or confirm a home visit), it waits here for the report.", "নমুনা নিলে (বা বাসায় যাওয়ার সময় নিশ্চিত করলে) রিপোর্ট দেওয়া পর্যন্ত এখানে থাকবে।"))
                "reported" -> SegEmpty(R.drawable.ic_list_alt, tr("No reports given yet", "এখনো কোনো রিপোর্ট দেওয়া হয়নি"), null)
                else -> SegEmpty(R.drawable.ic_history, tr("Nothing finished yet", "এখনো শেষ হওয়া কোনো অনুরোধ নেই"), null)
            }
            else -> {
                list.sortedByDescending { it.createdAt }.forEach { o ->
                    val h = o.home
                    val fromHome = h != null && h.status != "declined"
                    val open = o.status in setOf("sent", "received")
                    Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow), modifier = Modifier.testTag("lab_card")) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(o.patientName.ifBlank { "-" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                ModeTag(fromHome, tr("Sample from home", "বাসা থেকে নমুনা"), tr("Will come to the lab", "ল্যাবে আসবেন"))
                            }
                            Text(o.tests.joinToString(", "), style = MaterialTheme.typography.bodyMedium)
                            // "নতুন" ভাগে আবার "নতুন" নয় — শুধু কতক্ষণ আগে
                            Text(
                                if (o.status == "sent") timeAgo(o.createdAt) else WorkSegs.labStatus(o.status) + " · " + timeAgo(o.createdAt),
                                style = MaterialTheme.typography.bodySmall, color = if (o.status == "sent") cs.primary else cs.onSurfaceVariant,
                            )
                            // বাসা থেকে নমুনা: দিন-সময়, ঠিকানা, ফোন (চালু থাকা পর্যন্ত — সার্ভার তারপর আর পাঠায় না)
                            if (h != null && open) {
                                // সময় পেরিয়ে গেলে লাল (সবুজ/মূল রঙে নয়), সাথে লেখাও "সময় পেরিয়ে গেছে"
                                val expired = LabSlot.expired(h)
                                IconLine(
                                    R.drawable.ic_home_health,
                                    if (expired && h.status == "requested") LabPriceText.homeLineLab(h.copy(status = "expired")) else LabPriceText.homeLineLab(h),
                                    when {
                                        expired -> cs.error
                                        h.status == "requested" -> cs.primary
                                        else -> cs.onSurfaceVariant
                                    },
                                )
                                if (h.status != "declined") {
                                    if (h.address.isNotBlank()) IconLine(R.drawable.ic_location_on, h.address)
                                    if (h.phone.isNotBlank()) IconLine(R.drawable.ic_call, phoneText(h.phone))
                                    if (h.note.isNotBlank()) IconLine(R.drawable.ic_edit_note, h.note)
                                }
                            }
                            if (o.prices != null && open) SmallNote(LabPriceText.snapLine(o.prices, o.home))
                            val busy = busyId == o.id
                            val homeAsk = o.status == "sent" && h != null && h.status != "declined"
                            fun sampleTaken() {
                                scope.launch {
                                    busyId = o.id
                                    errorMsg = null
                                    runCatching { RxNet.labStatus(o.id, "received") }.onSuccess { infoMsg = tr("Moved to \"In progress\"", "\"চলছে\"-তে গেল") }.onFailure { errorMsg = it.message }
                                    busyId = null
                                    onChanged()
                                }
                            }
                            // একটাই মূল কাজ: সময় নিশ্চিত করুন (বাসা থেকে, এখনো জানানো হয়নি) → নমুনা নিয়েছি → রিপোর্ট দিন → দেখুন
                            val primary = when {
                                homeAsk && h?.status != "confirmed" -> "confirm"
                                o.status == "sent" -> "sample"
                                o.status == "received" -> "report"
                                o.status == "reported" -> "view"
                                else -> ""
                            }
                            val homePhone = h?.phone?.takeIf { fromHome && open && it.isNotBlank() }
                            ActionRow(
                                key = o.id,
                                showPrimary = primary.isNotEmpty(),
                                primary = {
                                    when (primary) {
                                        "confirm" -> PrimaryAction(tr("Confirm time", "সময় নিশ্চিত করুন"), R.drawable.ic_check_circle, { confirming = o })
                                        "sample" -> PrimaryAction(tr("Sample taken", "নমুনা নিয়েছি"), R.drawable.ic_check_circle, { sampleTaken() }, busy = busy, enabled = busyId == null)
                                        "report" -> PrimaryAction(tr("Give report", "রিপোর্ট দিন"), R.drawable.ic_edit_note, { entering = o })
                                        "view" -> PrimaryAction(tr("View", "দেখুন"), R.drawable.ic_list_alt, { viewing = o.id })
                                    }
                                },
                                more = {
                                    if (o.status == "sent" && primary != "sample") SmallAction(tr("Sample taken", "নমুনা নিয়েছি"), R.drawable.ic_check_circle, { sampleTaken() }, busy = busy, enabled = busyId == null)
                                    if (homeAsk && h?.status == "confirmed") SmallAction(tr("Change time", "সময় বদলান"), R.drawable.ic_schedule, { confirming = o })
                                    if (homeAsk) SmallAction(tr("Can't go", "যেতে পারব না"), R.drawable.ic_close, { declining = o })
                                    // বিল ও রসিদ (InvoiceForms.kt; inv_lab): রসিদ থাকলে সেটা, নইলে "বিল"
                                    if (invLab && o.invoiceId.isNotBlank()) BizReceiptAction(o.invoiceId)
                                    else if (invLab && o.status != "cancelled") SmallAction(tr("Bill", "বিল"), R.drawable.ic_payments, { billing = o })
                                    // পরীক্ষার টাকা আগে (লাইফলো পে, pay_lab)
                                    if (o.status in setOf("sent", "received") && o.home?.status == "confirmed") PayAskAction("lab_order", o.id, PayRules.LAB)
                                    // কল (ফোন কল — বাসা থেকে নমুনা · অ্যাপ কল · ভিডিও কল) · মেসেজ
                                    ContactActions(CallCtx.LAB_ORDER, o.id, peerName = o.patientName, phone = homePhone, style = ContactStyle.CHIPS, appAllowed = open,
                                        onMessage = { openChat(CallCtx.LAB_ORDER, o.id) })
                                },
                            )
                        }
                    }
                }
                MoreNote(WorkQueue.counts(data?.counts)[seg] ?: 0, list.size)
            }
        }
    }
    if (codeOpen) {
        RxCodeDialog(
            tr("Enter the code under the prescription's QR, or scan the QR.", "প্রেসক্রিপশনের QR-এর নিচের কোড দিন, অথবা QR স্ক্যান করুন।"),
            opening, codeErr,
            { c ->
                scope.launch {
                    opening = true
                    codeErr = null
                    runCatching { RxNet.labOpenCode(c) }.onSuccess { codeOpen = false; entering = it; onChanged() }.onFailure { codeErr = it.message }
                    opening = false
                }
            },
            { codeOpen = false },
        )
    }
    entering?.let { o -> LabReportEntryDialog(o, onDismiss = { entering = null }) { entering = null; infoMsg = tr("Report sent to the patient", "রোগীর কাছে রিপোর্ট গেছে"); onChanged() } }
    confirming?.let { o -> HomeConfirmDialog(o, onDismiss = { confirming = null }) { confirming = null; infoMsg = tr("The patient was told the time", "রোগীকে সময় জানানো হয়েছে"); onChanged() } }
    declining?.let { o -> HomeDeclineDialog(o, onDismiss = { declining = null }) { declining = null; infoMsg = tr("The patient was told", "রোগীকে জানানো হয়েছে"); onChanged() } }
    viewing?.let { id -> LabReportDialog(id) { viewing = null } }
    billing?.let { o -> LabBillDialog(o) { billing = null } }
}
