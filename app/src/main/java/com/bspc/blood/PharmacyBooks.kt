package com.bspc.blood

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

// ফার্মেসির হিসাব-খাতা (পারভেজ, ১৮ অক্টোবর ২০২৬): পণ্য-দাম, মাল কেনা, বিক্রি, লাভ-ক্ষতি — পুরোটা শুধু ওয়েবে
// (ফোনের ব্রাউজারেও চলে), অ্যাপ হালকা থাকে। অ্যাপে শুধু এই ছোট "আজকের হিসাব" কার্ড আর "পুরো হিসাব" বাটন (ওয়েব পাতা খোলে)।
// সার্ভার: books_today (server/lib/shopbooks.js)। PharmacyRxSection-এ এক লাইন: PharmacyTodayCard()
// আর "যা দিলাম লিখে রাখুন"-এর পর "বিক্রির হিসাবে তুলবেন?" (RxSaleOffer — ওয়েবের books/rxsale.tsx-এর মতো;
// সার্ভার: books_rx_preview, books_sale_add source 'rx')। DispenseDialog-এ এক লাইন। অনলাইন অর্ডারও একই পথে সম্পন্ন হয়।

data class BooksToday(val sales: Int, val net: Double, val profit: Double, val low: Int, val expiring: Int, val priced: Boolean)

/** প্রেসক্রিপশনের একটা ওষুধ — দোকানের তালিকার মেলানো পণ্য আর দাম (না মিললে/দাম না থাকলে unitPrice null) */
data class RxSaleLine(val i: Int, val name: String, val qty: Double, val itemId: String?, val unitPrice: Double?, val unitDisc: Double) {
    /** ছাড়ের পরে প্রতিটার দাম */
    val unitNet: Double? get() = unitPrice?.let { it - unitDisc }
}

/** books_rx_preview — এই ভাগটা হিসাবে তোলার আগে দেখা; recorded = আগেই তোলা */
data class RxSalePreview(val code: String, val at: Long, val source: String, val recorded: Boolean, val lines: List<RxSaleLine>) {
    val priced: Boolean get() = lines.any { it.unitPrice != null }
    val discount: Double get() = PharmacyBooks.r2(lines.sumOf { if (it.unitPrice != null) it.unitDisc * it.qty else 0.0 })
    val total: Double get() = PharmacyBooks.r2(lines.sumOf { l -> l.unitNet?.let { it * l.qty } ?: 0.0 })
}

object PharmacyBooks {
    /** ওয়েবের হিসাবের পাতা (ওয়েবের দোকান › এনালাইটিক্স, ২.৩০.০; পুরোনো /orders/books সেখানে যায়) */
    const val WEB_PATH = "/work/pharmacy/shop/analytics"

    /** বিক্রি তোলা হলে বাড়ে — "আজকের হিসাব" কার্ড পর্দায় থাকলে নতুন করে আনে */
    val refresh = mutableIntStateOf(0)

    private suspend fun call(action: String, body: JSONObject = JSONObject()): JSONObject = ServerApi.post("/api/community", body.put("action", action))

    suspend fun today(): BooksToday {
        val o = call("books_today")
        return BooksToday(
            o.optInt("sales"), o.optDouble("net", 0.0), o.optDouble("profit", 0.0),
            o.optInt("low"), o.optInt("expiring"), o.optBoolean("priced"),
        )
    }

    /** at: যে ভাগটা (rx_dispense-এর লেখা dispensed[].at) */
    suspend fun rxPreview(code: String, at: Long): RxSalePreview {
        val o = call("books_rx_preview", JSONObject().put("code", code).put("at", at))
        val a = o.optJSONArray("lines") ?: JSONArray()
        val lines = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
            RxSaleLine(
                it.optInt("i"), it.str("itemName").ifBlank { it.str("name") }, it.optDouble("qty", 0.0), it.str("itemId").ifBlank { null },
                if (it.isNull("unitPrice")) null else it.optDouble("unitPrice").takeIf { v -> !v.isNaN() }, it.optDouble("unitDisc", 0.0).takeIf { v -> !v.isNaN() } ?: 0.0,
            )
        }
        return RxSalePreview(o.str("code").ifBlank { code }, o.optLong("at", at), o.str("source").ifBlank { "rx" }, o.optBoolean("recorded"), lines)
    }

    /**
     * দাম পাঠানো হয় না — সার্ভার পণ্যের বিক্রি দাম আর ছাড় বসায় (ওয়েবে "দাম না বদলালে"-র মতোই)।
     * উত্তরে রসিদ (চুক্তির ডিফল্ট: পুরো টাকা ক্যাশে) — পুরোনো সার্ভারে null।
     */
    suspend fun rxRecord(p: RxSalePreview): Invoice? {
        val lines = JSONArray(p.lines.map { l -> JSONObject().put("i", l.i).apply { if (l.itemId != null) put("itemId", l.itemId) } })
        return InvoiceApi.invoiceIn(call("books_sale_add", JSONObject().put("source", "rx").put("rx", JSONObject().put("code", p.code).put("at", p.at)).put("lines", lines)))
    }

    fun r2(v: Double): Double = Math.round(v * 100) / 100.0

    /** ৳১,৯৫৯ / ৳৩৯৫.১৫ (বাংলায় বাংলা অঙ্ক) */
    fun taka(v: Double): String {
        val a = Math.abs(v)
        val s = if (a == Math.floor(a)) String.format(Locale.US, "%,d", a.toLong())
        else String.format(Locale.US, "%,.2f", a).trimEnd('0').trimEnd('.')
        return (if (v < 0) "−" else "") + "৳" + num(s)
    }

    /** ১০ / ২.৫ */
    fun qty(v: Double): String = num(if (v == Math.floor(v)) v.toLong().toString() else String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.'))

    fun sourceText(s: String): String = when (s) {
        "online" -> tr("Online", "অনলাইন")
        "rx" -> tr("Prescription", "প্রেসক্রিপশন")
        else -> tr("Counter", "কাউন্টার")
    }
}

/** দোকানের মালিকের কাজের পাতায়: আজকের বিক্রি, লাভ, কম স্টক, মেয়াদ শেষের পথে; "পুরো হিসাব" ওয়েবে */
@Composable
fun PharmacyTodayCard() {
    val context = LocalContext.current
    var t by remember { mutableStateOf<BooksToday?>(null) }
    val bump = PharmacyBooks.refresh.intValue
    LaunchedEffect(bump) { runCatching { PharmacyBooks.today() }.onSuccess { t = it } }
    val d = t ?: return
    val cs = MaterialTheme.colorScheme
    Card(colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr("Today's accounts", "আজকের হিসাব"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (!d.priced && d.sales == 0) {
                SmallNote(tr("Open on the web to add prices and stock", "দাম আর স্টক বসাতে ওয়েবে খুলুন"))
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Sales", "বিক্রি"), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                        Text(PharmacyBooks.taka(d.net), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(tr("${d.sales} sales", "${num(d.sales)}টা বিক্রি"), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(tr("Profit", "লাভ"), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                        Text(
                            PharmacyBooks.taka(d.profit), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                            color = if (d.profit < 0) cs.error else cs.primary,
                        )
                    }
                }
                if (d.low > 0 || d.expiring > 0) {
                    SmallNote(
                        listOfNotNull(
                            if (d.low > 0) tr("${d.low} low on stock", "কম স্টক ${num(d.low)}টা") else null,
                            if (d.expiring > 0) tr("${d.expiring} expiring soon", "মেয়াদ শেষের পথে ${num(d.expiring)}টা") else null,
                        ).joinToString(" · "),
                    )
                }
            }
            OutlinedButton(onClick = { openUrl(context, ServerApi.BASE_URL + PharmacyBooks.WEB_PATH) }, modifier = Modifier.fillMaxWidth()) {
                ButtonContent(false, tr("Full accounts", "পুরো হিসাব"), R.drawable.ic_payments)
            }
        }
    }
}

/**
 * "যা দিলাম লিখে রাখুন"-এর পর: এই ভাগের ওষুধগুলো বিক্রির হিসাবে তোলার প্রস্তাব (দাম পণ্যের তালিকা থেকে)।
 * "এখন না" — ওষুধ দেওয়া আটকায় না। দোকানে কোনো পণ্যের দামই না থাকলে শুধু এক লাইন আর "পুরো হিসাব"।
 * একই ভাগ দুবার ওঠে না (সার্ভারের নির্দিষ্ট id) — আগেই তোলা থাকলে "আগেই হিসাবে তোলা"।
 */
@Composable
fun RxSaleOffer(code: String, at: Long, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // load → offer | nopriced | done; gone = কিছুই দেখানো নয় (খোলা গেল না / ওষুধ নেই)
    var state by remember(at) { mutableStateOf("load") }
    var pv by remember(at) { mutableStateOf<RxSalePreview?>(null) }
    var busy by remember(at) { mutableStateOf(false) }
    var errorMsg by remember(at) { mutableStateOf<String?>(null) }
    LaunchedEffect(code, at) {
        val p = runCatching { PharmacyBooks.rxPreview(code, at) }.getOrNull()
        pv = p
        state = when {
            p == null || p.lines.isEmpty() -> "gone"
            p.recorded -> "done"
            p.priced -> "offer"
            // এই ওষুধগুলোর দাম নেই — দোকানের অন্য পণ্যে দাম থাকলে তবুও প্রস্তাব (স্টক কমে), একটাতেও না থাকলে এক লাইন
            runCatching { PharmacyBooks.today().priced }.getOrDefault(false) -> "offer"
            else -> "nopriced"
        }
    }
    val cs = MaterialTheme.colorScheme
    when (state) {
        "load", "gone" -> return
        "done" -> {
            SmallNote(tr("Already in your sales", "আগেই হিসাবে তোলা"))
            return
        }
        "nopriced" -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    tr("Add prices to keep a sales record", "দাম বসালে বিক্রির হিসাব রাখা যায়"),
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f),
                )
                SmallAction(tr("Full accounts", "পুরো হিসাব"), R.drawable.ic_payments, { openUrl(context, ServerApi.BASE_URL + PharmacyBooks.WEB_PATH) })
            }
            return
        }
    }
    val p = pv ?: return
    fun record() {
        scope.launch {
            busy = true
            errorMsg = null
            val r = runCatching { PharmacyBooks.rxRecord(p) }
            if (r.isSuccess) {
                android.widget.Toast.makeText(context, tr("Added to sales", "বিক্রির হিসাবে উঠেছে"), android.widget.Toast.LENGTH_SHORT).show()
                PharmacyBooks.refresh.intValue++
                onClose()
                // বিক্রির রসিদ — দেখানো, শেয়ার/প্রিন্ট (Receipts.kt)
                if (Features.on(InvoiceFeatures.key("pharmacy"))) InvoiceNav.issued(r.getOrNull())
            } else if (runCatching { PharmacyBooks.rxPreview(code, at).recorded }.getOrDefault(false)) {
                // অন্য কোথাও (ওয়েবে/আরেক ফোনে) এর মধ্যে তোলা হয়ে গেছে
                state = "done"
                PharmacyBooks.refresh.intValue++
            } else {
                errorMsg = r.exceptionOrNull()?.message
            }
            busy = false
        }
    }
    Card(colors = CardDefaults.cardColors(containerColor = cs.secondaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Add to sales?", "বিক্রির হিসাবে তুলবেন?"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(PharmacyBooks.sourceText(p.source), style = MaterialTheme.typography.bodySmall, color = cs.onSecondaryContainer)
            }
            p.lines.forEach { l ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(l.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            PharmacyBooks.qty(l.qty) + (l.unitNet?.let { " × " + PharmacyBooks.taka(it) } ?: ""),
                            style = MaterialTheme.typography.bodySmall, color = cs.onSecondaryContainer,
                        )
                    }
                    val net = l.unitNet
                    if (net != null) Text(PharmacyBooks.taka(PharmacyBooks.r2(net * l.qty)), style = MaterialTheme.typography.bodyMedium)
                    else Text(tr("No price", "দাম নেই"), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
            if (p.discount > 0) {
                Row {
                    Text(tr("Discount", "ছাড়"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Text(PharmacyBooks.taka(-p.discount), style = MaterialTheme.typography.bodySmall)
                }
            }
            Row {
                Text(tr("Total", "মোট"), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(PharmacyBooks.taka(p.total), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            }
            if (p.lines.any { it.unitPrice == null }) {
                SmallNote(tr("No money is counted for medicines without a price (stock still goes down if it's in your list).", "দাম নেই এমন ওষুধে টাকা ধরা হবে না (তালিকায় থাকলে স্টক কমবে)।"))
            }
            ErrorText(errorMsg)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClose, enabled = !busy) { Text(tr("Not now", "এখন না")) }
                SmallAction(tr("Add to sales", "হিসাবে তুলুন"), R.drawable.ic_payments, { record() }, primary = true, busy = busy)
            }
        }
    }
}
