package com.bspc.blood

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
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

/**
 * "বিক্রি" ট্যাব (mywork-review/CONTRACT.md §২ — ফার্মেসির ওপরের স্তর অর্ডার · বিক্রি · দোকান; "আমার কাজ" থেকে এক চাপে,
 * WorkNav.target "sales"): ওপরে কাউন্টারের নতুন বিক্রি (CounterSaleCard), তারপর আজ/এই সপ্তাহের বিক্রি আর সাম্প্রতিক
 * বিক্রি, নিচে রসিদ (inv_pharmacy) আর দোকানের হিসাব — দুটোই পুরো পর্দায় খোলে (ভেতরে আর ট্যাব নয়)।
 */
@Composable
fun PharmacySalesTab(invPharmacy: Boolean, onReceipts: () -> Unit) {
    var analytics by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("pharm_sales")) {
        CounterSaleCard()
        PharmacySalesLite()
        if (invPharmacy) {
            OutlinedButton(onClick = onReceipts, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("sales_receipts")) {
                ButtonContent(false, tr("Receipts", "রসিদ"), R.drawable.ic_list_alt)
            }
        }
        OutlinedButton(onClick = { analytics = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("sales_analytics")) {
            ButtonContent(false, tr("Shop accounts", "দোকানের হিসাব"), R.drawable.ic_history)
        }
        WebDetailLink(ShopBooksLite.WEB_SALES, "pharm_sales_web")
    }
    if (analytics) {
        FullScreenDialog(onDismissRequest = { analytics = false }) {
            Column(Modifier.fillMaxSize()) {
                BackTopBar(tr("Shop accounts", "দোকানের হিসাব"), { analytics = false })
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) { PharmacyAnalyticsLite() }
            }
        }
    }
}

/** আজ আর এই সপ্তাহের বিক্রি, সাম্প্রতিক ৫টা বিক্রি (বিক্রি লেখা ওপরের CounterSaleCard-এ; পুরো তালিকা ওয়েবে) */
@Composable
fun PharmacySalesLite() {
    var today by remember { mutableStateOf<BooksToday?>(null) }
    var week by remember { mutableStateOf<WeekReport?>(null) }
    var rows by remember { mutableStateOf<List<BookEntry>?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val bump = PharmacyBooks.refresh.intValue
    LaunchedEffect(bump) {
        runCatching { PharmacyBooks.today() }.onSuccess { today = it; errorMsg = null }.onFailure { errorMsg = it.message }
        runCatching { ShopBooksLite.week() }.onSuccess { week = it }
        runCatching { ShopBooksLite.recentSales() }.onSuccess { rows = it }.onFailure { errorMsg = it.message; rows = emptyList() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            SmallNote(tr("Sales from prescriptions are added after \"Note what I gave\".", "প্রেসক্রিপশনের বিক্রি \"যা দিলাম লিখে রাখুন\"-এর পর ওঠে।"))
        }
    }
}

/** দোকানের তালিকার একটা পণ্য (books_items) — বিক্রির জন্য যতটুকু লাগে */
data class ShopItemLite(val id: String, val name: String, val unit: String, val qty: Double?, val price: Double?)

/** কাউন্টারের বিক্রি: পণ্যের তালিকা আর বিক্রি লেখা (books_items, books_sale_add — source counter; ওয়েবের বিক্রির ঘরের মতো) */
object CounterSale {
    private suspend fun call(action: String, body: JSONObject = JSONObject()): JSONObject = ServerApi.post("/api/community", body.put("action", action))
    private fun objs(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    private fun dbl(o: JSONObject, k: String): Double? = if (!o.has(k) || o.isNull(k)) null else o.optDouble(k).takeIf { !it.isNaN() }

    fun parseItems(o: JSONObject): List<ShopItemLite> = objs(o.optJSONArray("items")).map {
        ShopItemLite(it.str("id"), it.str("name"), it.str("unit"), dbl(it, "qty"), dbl(it, "salePrice") ?: dbl(it, "price"))
    }.filter { it.id.isNotBlank() && it.name.isNotBlank() }

    suspend fun items(): List<ShopItemLite> = parseItems(call("books_items"))

    /** দাম পাঠানো হয় না — সার্ভার পণ্যের বিক্রি দাম আর ছাড় বসায়; উত্তরে রসিদ (inv_pharmacy চালু থাকলে) */
    suspend fun sell(lines: List<Pair<ShopItemLite, Double>>): Invoice? =
        InvoiceApi.invoiceIn(call("books_sale_add", JSONObject().put("source", "counter").put("lines", JSONArray(lines.map { (i, q) -> JSONObject().put("itemId", i.id).put("qty", q) }))))

    /** খোঁজা: বাংলা বা ইংরেজি অঙ্ক দুটোই চলে; নামের শুরুতে মিললে আগে */
    fun match(items: List<ShopItemLite>, q: String): List<ShopItemLite> {
        val k = latinDigits(q).trim().lowercase()
        if (k.isBlank()) return emptyList()
        return items.filter { it.name.lowercase().contains(k) }.sortedBy { !it.name.lowercase().startsWith(k) }.take(6)
    }

    fun total(lines: List<Pair<ShopItemLite, Double>>): Double = PharmacyBooks.r2(lines.sumOf { (i, q) -> (i.price ?: 0.0) * q })
}

/** "নতুন বিক্রি" — নাম লিখে পণ্য বাছা, পরিমাণ − / +, মোট, "বিক্রি লিখুন" (স্টক কমে, রসিদ দেখায়) */
@Composable
fun CounterSaleCard(initialItems: List<ShopItemLite>? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    var items by remember { mutableStateOf(initialItems) }
    var reload by remember { mutableIntStateOf(0) }
    var q by remember { mutableStateOf("") }
    var lines by remember { mutableStateOf(listOf<Pair<ShopItemLite, Double>>()) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(reload) {
        if (initialItems != null && reload == 0) return@LaunchedEffect
        runCatching { CounterSale.items() }.onSuccess { items = it }.onFailure { if (items == null) items = emptyList(); errorMsg = it.message }
    }
    fun add(i: ShopItemLite) {
        val k = lines.indexOfFirst { it.first.id == i.id }
        lines = if (k >= 0) lines.toMutableList().also { it[k] = i to it[k].second + 1 } else lines + (i to 1.0)
        q = ""
    }
    fun setQty(k: Int, v: Double) {
        lines = if (v <= 0) lines.toMutableList().also { it.removeAt(k) } else lines.toMutableList().also { it[k] = it[k].first to v }
    }
    fun sell() {
        scope.launch {
            busy = true
            errorMsg = null
            runCatching { CounterSale.sell(lines) }
                .onSuccess { inv ->
                    lines = emptyList()
                    android.widget.Toast.makeText(context, tr("Sale recorded", "বিক্রি লেখা হয়েছে"), android.widget.Toast.LENGTH_SHORT).show()
                    PharmacyBooks.refresh.intValue++
                    reload++
                    if (Features.on(InvoiceFeatures.key("pharmacy"))) InvoiceNav.issued(inv)
                }
                .onFailure { errorMsg = it.message }
            busy = false
        }
    }
    Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow), modifier = Modifier.testTag("counter_sale")) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("New sale", "নতুন বিক্রি"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val all = items
            when {
                all == null -> LoadingBox()
                all.isEmpty() -> {
                    ErrorText(errorMsg)
                    SmallNote(tr("Your stock list is empty — add products in \"Shop › My stock list\" or on the web.", "স্টকের তালিকায় কোনো পণ্য নেই — \"দোকান › আমার স্টকের তালিকা\" বা ওয়েবে পণ্য যোগ করুন।"))
                }
                else -> {
                    OutlinedTextField(
                        q, { q = it.take(40) },
                        label = { Text(tr("Type the medicine name", "ওষুধের নাম লিখুন")) },
                        singleLine = true,
                        leadingIcon = { AppIcon(R.drawable.ic_search, null, Modifier.size(20.dp)) },
                        modifier = Modifier.fillMaxWidth().testTag("sale_search"),
                    )
                    val found = CounterSale.match(all, q)
                    if (q.isNotBlank() && found.isEmpty()) SmallNote(tr("Not in your stock list", "স্টকের তালিকায় নেই"))
                    found.forEach { i ->
                        Surface(onClick = { add(i) }, shape = RoundedCornerShape(10.dp), color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("sale_pick")) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(i.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    i.qty?.let { Text(tr("In stock: ", "স্টকে: ") + PharmacyBooks.qty(it), style = MaterialTheme.typography.bodySmall, color = if (it <= 0) cs.error else cs.onSurfaceVariant) }
                                }
                                Text(i.price?.let { PharmacyBooks.taka(it) } ?: tr("No price", "দাম নেই"), style = MaterialTheme.typography.bodyMedium)
                                Text(tr("Add", "যোগ"), style = MaterialTheme.typography.labelLarge, color = cs.primary)
                            }
                        }
                    }
                    lines.forEachIndexed { k, (i, qty) ->
                        if (k == 0) androidx.compose.material3.HorizontalDivider()
                        Column(Modifier.fillMaxWidth().testTag("sale_line")) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(i.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(i.price?.let { PharmacyBooks.taka(PharmacyBooks.r2(it * qty)) } ?: tr("No price", "দাম নেই"), style = MaterialTheme.typography.bodyMedium)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(i.price?.let { PharmacyBooks.taka(it) + " ×" } ?: "", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                                TextButton(onClick = { setQty(k, qty - 1) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("−", style = MaterialTheme.typography.titleMedium) }
                                Text(PharmacyBooks.qty(qty), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                TextButton(onClick = { setQty(k, qty + 1) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("+", style = MaterialTheme.typography.titleMedium) }
                                TextButton(onClick = { setQty(k, 0.0) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr("Remove", "বাদ"), color = cs.error) }
                            }
                        }
                    }
                    if (lines.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tr("Total", "মোট"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text(PharmacyBooks.taka(CounterSale.total(lines)), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("sale_total"))
                        }
                        if (lines.any { it.first.price == null }) SmallNote(tr("No money is counted for products without a price.", "দাম নেই এমন পণ্যে টাকা ধরা হবে না।"))
                    }
                    ErrorText(errorMsg)
                    PrimaryAction(
                        tr("Record sale", "বিক্রি লিখুন"), R.drawable.ic_payments, { sell() },
                        modifier = Modifier.fillMaxWidth().testTag("sale_submit"), busy = busy, enabled = lines.isNotEmpty(),
                    )
                }
            }
        }
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
    LaunchedEffect(bump) { runCatching { ShopBooksLite.week() }.onSuccess { week = it; errorMsg = null }.onFailure { errorMsg = it.message } }
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
