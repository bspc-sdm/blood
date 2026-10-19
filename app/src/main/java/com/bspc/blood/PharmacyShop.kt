package com.bspc.blood

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

/*
 * ফার্মেসির "দোকান" ট্যাবের ভেতরের হালকা ভাগ (২.৩০.০, পারভেজের নিয়ম: রোজকার দ্রুত কাজ অ্যাপে, ভারী হিসাব ওয়েবে):
 * বিক্রি / স্টক / এনালাইটিক্স — আজ আর এই সপ্তাহের মোট, বেশি বিক্রির পণ্য, সাম্প্রতিক কয়েকটা লেখা, আর
 * "বিস্তারিত ওয়েবে দেখুন" (ওয়েবের মিলে যাওয়া পাতা: /work/pharmacy/shop/sales|stock|analytics)।
 * সার্ভার: books_today, books_report (7d), books_sales (7d), books_purchases — server/lib/shopbooks.js (বদলায়নি)।
 */

/** সাম্প্রতিক একটা লেখা (বিক্রি বা কেনা) */
data class BookEntry(val at: Long, val title: String, val amount: Double?, val sub: String)

/** সপ্তাহের হিসাব: মোট বিক্রি, লাভ, বিক্রির সংখ্যা, বেশি বিক্রির পণ্য (নাম, টাকা) */
data class WeekReport(val net: Double, val profit: Double, val netProfit: Double, val sales: Int, val bought: Double, val top: List<Pair<String, Double>>, val daily: List<Double>)

object ShopBooksLite {
    /** ওয়েবের পাতা (দোকান › বিক্রি / স্টক / এনালাইটিক্স) */
    const val WEB_SALES = "/work/pharmacy/shop/sales"
    const val WEB_STOCK = "/work/pharmacy/shop/stock"
    const val WEB_ANALYTICS = "/work/pharmacy/shop/analytics"

    private suspend fun call(action: String, body: JSONObject = JSONObject()): JSONObject = ServerApi.post("/api/community", body.put("action", action))

    private fun objs(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }

    private fun names(lines: JSONArray?): String {
        val l = objs(lines).map { it.str("name") }.filter { it.isNotBlank() }
        return l.take(2).joinToString(", ") + if (l.size > 2) " +${num(l.size - 2)}" else ""
    }

    suspend fun week(): WeekReport {
        val o = call("books_report", JSONObject().put("period", "7d"))
        val t = o.optJSONObject("totals") ?: JSONObject()
        return WeekReport(
            t.optDouble("net", 0.0), t.optDouble("profit", 0.0), t.optDouble("netProfit", 0.0), t.optInt("sales"), t.optDouble("bought", 0.0),
            objs(o.optJSONArray("topSales")).take(5).map { it.str("name") to it.optDouble("net", 0.0) },
            objs(o.optJSONArray("daily")).map { it.optDouble("net", 0.0) },
        )
    }

    suspend fun recentSales(n: Int = 5): List<BookEntry> =
        objs(call("books_sales", JSONObject().put("period", "7d")).optJSONArray("items")).take(n).map {
            BookEntry(it.optLong("at"), names(it.optJSONArray("lines")), it.optJSONObject("totals")?.optDouble("net"), PharmacyBooks.sourceText(it.str("source")))
        }

    suspend fun recentPurchases(n: Int = 5): List<BookEntry> =
        objs(call("books_purchases").optJSONArray("items")).take(n).map {
            BookEntry(it.optLong("at"), names(it.optJSONArray("lines")), it.optDouble("total", 0.0), it.str("supplier"))
        }
}

/** "বিস্তারিত ওয়েবে দেখুন" — মিলে যাওয়া ওয়েবের পাতা ব্রাউজারে */
@Composable
fun WebDetailLink(path: String, tag: String) {
    val context = LocalContext.current
    OutlinedButton(onClick = { openUrl(context, ServerApi.BASE_URL + path) }, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        ButtonContent(false, tr("See details on the web", "বিস্তারিত ওয়েবে দেখুন"), R.drawable.ic_share)
    }
}

@Composable
private fun LiteCard(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

/** দুটো সংখ্যা পাশাপাশি (আজ / এই সপ্তাহ) */
@Composable
private fun TwoFigures(a: Pair<String, String>, b: Pair<String, String>) {
    val cs = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        listOf(a, b).forEach { (label, value) ->
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun EntryRows(rows: List<BookEntry>, empty: String) {
    val cs = MaterialTheme.colorScheme
    if (rows.isEmpty()) {
        SmallNote(empty)
        return
    }
    rows.forEachIndexed { i, e ->
        if (i > 0) HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(e.title.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOf(timeAgo(e.at), e.sub).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            e.amount?.let { Text(PharmacyBooks.taka(it), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** "বিক্রি": আজ আর এই সপ্তাহের বিক্রি, সাম্প্রতিক ৫টা বিক্রি; বিক্রি লেখা আর পুরো তালিকা ওয়েবে */
@Composable
fun PharmacySalesLite() {
    var today by remember { mutableStateOf<BooksToday?>(null) }
    var week by remember { mutableStateOf<WeekReport?>(null) }
    var rows by remember { mutableStateOf<List<BookEntry>?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val bump = PharmacyBooks.refresh.intValue
    LaunchedEffect(bump) {
        runCatching { PharmacyBooks.today() }.onSuccess { today = it }.onFailure { errorMsg = it.message }
        runCatching { ShopBooksLite.week() }.onSuccess { week = it }
        runCatching { ShopBooksLite.recentSales() }.onSuccess { rows = it }.onFailure { errorMsg = it.message; rows = emptyList() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("pharm_sales")) {
        ErrorText(errorMsg)
        LiteCard(tr("Sales", "বিক্রি")) {
            TwoFigures(
                tr("Today", "আজ") to (today?.let { PharmacyBooks.taka(it.net) } ?: "—"),
                tr("This week", "এই সপ্তাহে") to (week?.let { PharmacyBooks.taka(it.net) } ?: "—"),
            )
            today?.let { SmallNote(tr("${it.sales} sales today", "আজ ${num(it.sales)}টা বিক্রি") + (week?.let { w -> tr(" · ${w.sales} this week", " · এই সপ্তাহে ${num(w.sales)}টা") } ?: "")) }
        }
        LiteCard(tr("Latest sales", "সাম্প্রতিক বিক্রি")) {
            val r = rows
            if (r == null) LoadingBox() else EntryRows(r, tr("No sales in the last 7 days.", "গত ৭ দিনে কোনো বিক্রি নেই।"))
            SmallNote(tr("Sales from prescriptions are added after \"Note what I gave\". Record counter sales on the web.", "প্রেসক্রিপশনের বিক্রি \"যা দিলাম লিখে রাখুন\"-এর পর ওঠে। কাউন্টারের বিক্রি ওয়েবে লিখুন।"))
        }
        WebDetailLink(ShopBooksLite.WEB_SALES, "pharm_sales_web")
    }
}

/** "স্টক": কম স্টক আর মেয়াদ শেষের পথে, স্টকের তালিকা (আগের মতো), সাম্প্রতিক মাল কেনা */
@Composable
fun PharmacyStockLite() {
    var today by remember { mutableStateOf<BooksToday?>(null) }
    var rows by remember { mutableStateOf<List<BookEntry>?>(null) }
    var stockOpen by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { PharmacyBooks.today() }.onSuccess { today = it }.onFailure { errorMsg = it.message }
        runCatching { ShopBooksLite.recentPurchases() }.onSuccess { rows = it }.onFailure { errorMsg = it.message; rows = emptyList() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("pharm_stock")) {
        ErrorText(errorMsg)
        LiteCard(tr("Stock", "স্টক")) {
            TwoFigures(
                tr("Low on stock", "কম স্টক") to (today?.let { num(it.low) } ?: "—"),
                tr("Expiring in 60 days", "৬০ দিনে মেয়াদ শেষ") to (today?.let { num(it.expiring) } ?: "—"),
            )
            OutlinedButton(onClick = { stockOpen = true }, modifier = Modifier.fillMaxWidth()) { ButtonContent(false, tr("My stock list", "আমার স্টকের তালিকা"), R.drawable.ic_list_alt) }
            SmallNote(tr("Keep a stock list so patients can see which medicines you have.", "স্টকের তালিকা রাখলে রোগীরা দেখতে পাবেন আপনার কাছে কোন ওষুধ আছে।"))
        }
        LiteCard(tr("Latest purchases", "সাম্প্রতিক মাল কেনা")) {
            val r = rows
            if (r == null) LoadingBox() else EntryRows(r, tr("No purchases in the last 90 days.", "গত ৯০ দিনে কোনো কেনা নেই।"))
        }
        WebDetailLink(ShopBooksLite.WEB_STOCK, "pharm_stock_web")
    }
    if (stockOpen) StockDialog { stockOpen = false }
}

/** "এনালাইটিক্স": আজকের হিসাব, সপ্তাহের বিক্রি-লাভ, দিনে দিনে বিক্রি, বেশি বিক্রির ৫টা পণ্য */
@Composable
fun PharmacyAnalyticsLite() {
    var week by remember { mutableStateOf<WeekReport?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val bump = PharmacyBooks.refresh.intValue
    LaunchedEffect(bump) { runCatching { ShopBooksLite.week() }.onSuccess { week = it }.onFailure { errorMsg = it.message } }
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("pharm_analytics")) {
        ErrorText(errorMsg)
        PharmacyTodayCard()
        val w = week
        LiteCard(tr("Last 7 days", "গত ৭ দিন")) {
            if (w == null) {
                if (errorMsg == null) LoadingBox()
            } else {
                TwoFigures(tr("Sales", "বিক্রি") to PharmacyBooks.taka(w.net), tr("Profit", "লাভ") to PharmacyBooks.taka(w.profit))
                if (w.netProfit != w.profit) SmallNote(tr("After losses/expired: ", "নষ্ট/মেয়াদ শেষ বাদে: ") + PharmacyBooks.taka(w.netProfit))
                if (w.daily.any { it > 0 }) MiniBars(w.daily.map { Math.round(it).toInt() })
            }
        }
        if (w != null) {
            LiteCard(tr("Top selling", "বেশি বিক্রি")) {
                if (w.top.isEmpty()) SmallNote(tr("No sales in the last 7 days.", "গত ৭ দিনে কোনো বিক্রি নেই।"))
                w.top.forEachIndexed { i, (name, net) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(num(i + 1) + ". " + name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(PharmacyBooks.taka(net), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = cs.primary)
                    }
                }
            }
        }
        WebDetailLink(ShopBooksLite.WEB_ANALYTICS, "pharm_analytics_web")
    }
}
