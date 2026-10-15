@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.bspc.blood

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// ------------------------------------------------------------
// দোকানের স্টক তালিকা ("সাধারণত থাকে" / "এখন নেই"), ঐচ্ছিক দাম আর ছাড়;
// ক্রেতার খোঁজায় কোন দোকানে পাওয়া যায়; দোকান ও অ্যাম্বুলেন্সের নিজের হিসাব
// ------------------------------------------------------------

data class StockItem(val key: String, val inStock: Boolean, val manual: Boolean, val price: Int?, val unit: String, val discount: Int, val at: Long)

/** খোঁজার ফল: দোকান, কোন ওষুধগুলো সেখানে থাকে, দাম (দিয়ে থাকলে) */
data class StockShop(
    val id: String,
    val name: String,
    val phone: String,
    val address: String,
    val openNow: Boolean,
    val delivery: Boolean,
    val km: Double?,
    val rating: Double,
    val has: List<String>,
    val prices: Map<String, Triple<Int, String, Int>>,
)

data class DrugRequestItem(val id: String, val name: String, val generic: String, val strength: String, val form: String, val company: String, val status: String, val createdAt: Long)

object Stock {
    val UNITS = listOf("piece", "strip", "box", "bottle", "tube", "vial")

    fun unitLabel(u: String): String = when (u) {
        "strip" -> tr("strip", "পাতা")
        "box" -> tr("box", "বক্স")
        "bottle" -> tr("bottle", "বোতল")
        "tube" -> tr("tube", "টিউব")
        "vial" -> tr("vial/ampoule", "ভায়াল/অ্যাম্পুল")
        else -> tr("piece", "পিস")
    }

    fun priceText(price: Int?, unit: String, discount: Int): String? {
        if (price == null) return null
        return "৳" + num(price) + "/" + unitLabel(unit) + if (discount > 0) tr(", ${discount}% off", ", ${num(discount)}% ছাড়") else ""
    }

    private suspend fun call(action: String, body: JSONObject = JSONObject()): JSONObject =
        ServerApi.post("/api/community", body.put("action", action))

    suspend fun mine(): Map<String, StockItem> {
        val o = call("stock_mine").optJSONObject("items") ?: JSONObject()
        return o.keys().asSequence().associateWith { k ->
            val x = o.getJSONObject(k)
            StockItem(
                k, x.optString("s") == "in", x.optBoolean("m"),
                if (x.has("p") && !x.isNull("p")) x.optInt("p") else null,
                x.optString("u").ifBlank { "piece" }, x.optInt("d"), x.optLong("at"),
            )
        }
    }

    /** changes: key → "in" / "out" / "remove"; দাম দিলে সাথে (null = মুছে দেওয়া) */
    /** ফেরত: কয়টা যোগ হয়নি (তালিকা ভরে যাওয়ায়) */
    suspend fun set(changes: List<JSONObject>): Int =
        changes.chunked(500).sumOf { part -> call("stock_set", JSONObject().put("changes", JSONArray(part))).optInt("skipped") }

    fun change(key: String, s: String) = JSONObject().put("key", key).put("s", s)

    suspend fun find(district: String, keys: List<String>, lat: Double?, lng: Double?): List<StockShop> {
        if (keys.isEmpty()) return emptyList()
        val a = call(
            "stock_find",
            JSONObject().put("district", district).put("keys", JSONArray(keys)).put("lat", lat ?: JSONObject.NULL).put("lng", lng ?: JSONObject.NULL),
        ).optJSONArray("items") ?: JSONArray()
        return (0 until a.length()).map { i ->
            val x = a.getJSONObject(i)
            val has = x.optJSONArray("has") ?: JSONArray()
            val p = x.optJSONObject("prices") ?: JSONObject()
            StockShop(
                x.optString("id"), x.optString("name"), x.optString("phone"), x.optString("address"),
                x.optBoolean("openNow"), x.optBoolean("delivery"), if (x.isNull("km")) null else x.optDouble("km"), x.optDouble("rating"),
                (0 until has.length()).map { has.getString(it) },
                p.keys().asSequence().associateWith { k ->
                    val v = p.getJSONObject(k)
                    Triple(v.optInt("price"), v.optString("unit"), v.optInt("discount"))
                },
            )
        }
    }

    suspend fun requestDrug(name: String, generic: String, strength: String, form: String, company: String) {
        call(
            "drug_request",
            JSONObject().put("name", name).put("generic", generic).put("strength", strength).put("form", form).put("company", company),
        )
    }

    suspend fun myRequests(): List<DrugRequestItem> {
        val a = call("drug_requests_mine").optJSONArray("items") ?: JSONArray()
        return (0 until a.length()).map {
            val x = a.getJSONObject(it)
            DrugRequestItem(
                x.optString("id"), x.optString("name"), x.optString("generic"), x.optString("strength"),
                x.optString("form"), x.optString("company"), x.optString("status"), x.optLong("createdAt"),
            )
        }
    }

    suspend fun shopStats(): JSONObject = call("shop_stats")
    suspend fun ambulanceStats(): JSONObject = call("ambulance_stats")
}

// ------------------------------------------------------------
// দোকান: স্টক তালিকা
// ------------------------------------------------------------

@Composable
fun StockScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<Map<String, StockItem>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<StockItem?>(null) }
    var requesting by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { runCatching { Drugs.ensure(context) } }
        Drugs.loadCustom()
        ready = true
    }
    LaunchedEffect(reload) {
        runCatching { Stock.mine() }.onSuccess { items = it; errorMsg = null }.onFailure { errorMsg = it.message }
    }

    fun apply(changes: List<JSONObject>, msg: String) {
        scope.launch {
            runCatching { Stock.set(changes) }
                .onSuccess { skipped ->
                    infoMsg = if (skipped > 0) msg + tr(" ($skipped not added — the list is full)", " (${num(skipped)}টা যোগ হয়নি — তালিকা ভরে গেছে)") else msg
                    reload++
                }
                .onFailure { errorMsg = it.message }
        }
    }

    BackHandler(enabled = adding) { adding = false }
    if (adding) {
        AddDrugsScreen(
            existing = items?.keys.orEmpty(),
            onBack = { adding = false },
            onAdd = { keys ->
                adding = false
                apply(keys.map { Stock.change(it, "in") }, tr("Added ${keys.size}", "${num(keys.size)}টা যোগ হয়েছে"))
            },
            onRequest = { adding = false; requesting = true },
        )
        return
    }

    val all = items
    val rows = remember(all, ready, filter) {
        (all?.values ?: emptyList()).map { it to Drugs.byKey(it.key) }
            .filter { (_, d) -> filter.isBlank() || (d?.name ?: "").contains(filter.trim(), ignoreCase = true) || (d?.generic ?: "").contains(filter.trim(), ignoreCase = true) }
            .sortedWith(compareBy({ !it.first.inStock }, { (it.second?.name ?: "~").lowercase() }))
    }

    Scaffold(topBar = { BackTopBar(tr("Stock list", "স্টক তালিকা"), onBack) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallNote(
                        tr(
                            "Mark what you usually keep. Customers nearby see that it's usually available here, and orders for these come to you first. Price and discount are optional. Replies to orders also update this list.",
                            "যা সাধারণত রাখেন সেগুলো যোগ করুন। কাছের ক্রেতারা দেখবেন এখানে সাধারণত পাওয়া যায়, আর এসব ওষুধের অর্ডার আগে আপনার কাছে আসবে। দাম আর ছাড় দেওয়া ঐচ্ছিক। অর্ডারের উত্তর থেকেও তালিকা নিজে হালনাগাদ হয়।",
                        ),
                    )
                    val list = all.orEmpty()
                    Text(
                        tr("${list.size} in list · ${list.values.count { it.inStock }} available", "তালিকায় ${num(list.size)}টা · থাকে ${num(list.values.count { it.inStock })}টা"),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { adding = true; infoMsg = null }) { ButtonContent(false, tr("Add medicines", "ওষুধ যোগ"), R.drawable.ic_add) }
                        OutlinedButton(onClick = { requesting = true }) { ButtonContent(false, tr("Not in list?", "তালিকায় নেই?"), R.drawable.ic_edit_note) }
                    }
                    InfoText(infoMsg)
                    ErrorText(errorMsg)
                    if ((all?.size ?: 0) > 8) {
                        OutlinedTextField(
                            filter, { filter = it.take(40) },
                            label = { Text(tr("Find in my list", "আমার তালিকায় খুঁজুন")) },
                            leadingIcon = { AppIcon(R.drawable.ic_search, null) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            when {
                all == null -> item { LoadingBox() }
                all.isEmpty() -> item {
                    EmptyState(R.drawable.ic_medication, tr("Your list is empty", "তালিকা খালি"), tr("Add by name or by company — e.g. everything you keep from Square.", "নাম দিয়ে বা কোম্পানি ধরে যোগ করুন — যেমন Square-এর যা যা রাখেন।"))
                }
                else -> items(rows, key = { it.first.key }) { (it, d) ->
                    Card(
                        Modifier.fillMaxWidth().clickable { editing = it },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(d?.name ?: tr("Unknown medicine", "অজানা ওষুধ"), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(d?.generic?.takeIf { g -> g.isNotBlank() }, d?.form?.takeIf { f -> f.isNotBlank() }).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Stock.priceText(it.price, it.unit, it.discount)?.let { p -> Text(p, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Switch(checked = it.inStock, onCheckedChange = { on ->
                                    apply(listOf(Stock.change(it.key, if (on) "in" else "out")), if (on) tr("Marked available", "\"থাকে\" করা হয়েছে") else tr("Marked out of stock", "\"এখন নেই\" করা হয়েছে"))
                                })
                                Text(if (it.inStock) tr("Have", "থাকে") else tr("Out", "এখন নেই"), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { it ->
        StockItemDialog(
            item = it,
            drug = Drugs.byKey(it.key),
            onDismiss = { editing = null },
            onSave = { body -> editing = null; apply(listOf(body), tr("Saved", "সেভ হয়েছে")) },
            onRemove = { editing = null; apply(listOf(Stock.change(it.key, "remove")), tr("Removed", "সরানো হয়েছে")) },
        )
    }
    if (requesting) {
        DrugRequestDialog(onDismiss = { requesting = false }, onSent = {
            requesting = false
            infoMsg = tr("Request sent. Once an admin approves it, it's added to your list.", "অনুরোধ পাঠানো হয়েছে। অ্যাডমিন অনুমোদন দিলে তালিকায় যোগ হবে।")
        })
    }
}

/** একটা ওষুধ: দাম, একক, ছাড় (ঐচ্ছিক) আর সরানো */
@Composable
private fun StockItemDialog(item: StockItem, drug: Drug?, onDismiss: () -> Unit, onSave: (JSONObject) -> Unit, onRemove: () -> Unit) {
    var price by remember { mutableStateOf(item.price?.toString() ?: "") }
    var unit by remember { mutableStateOf(item.unit) }
    var discount by remember { mutableStateOf(item.discount.takeIf { it > 0 }?.toString() ?: "") }
    var inStock by remember { mutableStateOf(item.inStock) }
    FormDialog(
        title = drug?.name ?: tr("Medicine", "ওষুধ"),
        onDismiss = onDismiss,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                drug?.let { Text(listOf(it.generic, it.form, it.company).filter { s -> s.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Usually in stock", "সাধারণত থাকে"), modifier = Modifier.weight(1f))
                    Switch(checked = inStock, onCheckedChange = { inStock = it })
                }
                Text(tr("Price (optional)", "দাম (ঐচ্ছিক)"), style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    price, { price = it.filter(Char::isDigit).take(5) },
                    label = { Text(tr("Price (Tk)", "দাম (টাকা)")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Stock.UNITS.forEach { u -> FilterChip(selected = unit == u, onClick = { unit = u }, label = { Text(tr("per ", "প্রতি ") + Stock.unitLabel(u)) }) }
                }
                OutlinedTextField(
                    discount, { discount = it.filter(Char::isDigit).take(2) },
                    label = { Text(tr("Discount % (optional, up to 50)", "ছাড় % (ঐচ্ছিক, ৫০ পর্যন্ত)")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = onRemove) { Text(tr("Remove from list", "তালিকা থেকে সরান"), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val p = price.toIntOrNull()?.takeIf { it > 0 }
                val d = discount.toIntOrNull()?.coerceIn(0, 50)
                onSave(
                    JSONObject().put("key", item.key).put("s", if (inStock) "in" else "out")
                        .put("p", p ?: JSONObject.NULL).put("u", unit).put("d", d ?: JSONObject.NULL),
                )
            }) { Text(tr("Save", "সেভ")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "বাতিল")) } },
    )
}

/** ওষুধ যোগ: নাম দিয়ে খুঁজে, বা কোম্পানি ধরে একসাথে */
@Composable
private fun AddDrugsScreen(existing: Set<String>, onBack: () -> Unit, onAdd: (List<String>) -> Unit, onRequest: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var chosen by remember { mutableStateOf(setOf<String>()) }
    var query by remember { mutableStateOf("") }
    var company by remember { mutableStateOf<String?>(null) }
    val results by produceState(emptyList<Drug>(), query) { value = withContext(Dispatchers.Default) { Drugs.search(query, 40) } }
    val companies by produceState(emptyList<Pair<String, Int>>()) { value = withContext(Dispatchers.Default) { Drugs.companies() } }
    val companyDrugs by produceState(emptyList<Drug>(), company) { value = company?.let { c -> withContext(Dispatchers.Default) { Drugs.ofCompany(c) } } ?: emptyList() }
    var companyFilter by remember { mutableStateOf("") }

    BackHandler(enabled = company != null) { company = null }

    Scaffold(
        topBar = { BackTopBar(tr("Add medicines", "ওষুধ যোগ"), { if (company != null) company = null else onBack() }) },
        bottomBar = {
            if (chosen.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().padding(12.dp)) {
                    Button(onClick = { onAdd(chosen.toList()) }, modifier = Modifier.fillMaxWidth()) {
                        ButtonContent(false, tr("Add ${chosen.size}", "${num(chosen.size)}টা যোগ করুন"), R.drawable.ic_add)
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SecondaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(tr("By name", "নাম দিয়ে")) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(tr("By company", "কোম্পানি ধরে")) })
            }
            val rows: List<Drug>
            if (tab == 0) {
                OutlinedTextField(
                    query, { query = it.take(40) },
                    label = { Text(tr("Medicine or generic name", "ওষুধ বা জেনেরিকের নাম")) },
                    leadingIcon = { AppIcon(R.drawable.ic_search, null) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
                rows = results
            } else if (company == null) {
                OutlinedTextField(
                    companyFilter, { companyFilter = it.take(40) },
                    label = { Text(tr("Find company", "কোম্পানি খুঁজুন")) },
                    leadingIcon = { AppIcon(R.drawable.ic_search, null) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
                LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                    items(companies.filter { companyFilter.isBlank() || it.first.contains(companyFilter.trim(), ignoreCase = true) }.take(200), key = { it.first }) { (c, n) ->
                        Row(Modifier.fillMaxWidth().clickable { company = c }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(c, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(num(n), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            AppIcon(R.drawable.ic_chevron_right, null, Modifier.size(20.dp))
                        }
                        HorizontalDivider()
                    }
                }
                return@Column
            } else {
                val fresh = companyDrugs.filter { it.key !in existing }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(company!!, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        val keys = fresh.map { it.key }.toSet()
                        chosen = if (keys.all { it in chosen }) chosen - keys else chosen + keys
                    }) { Text(tr("Select all", "সব বাছুন")) }
                }
                rows = companyDrugs
            }
            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                items(rows.distinctBy { it.key }, key = { it.key }) { d ->
                    val have = d.key in existing
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !have) { chosen = if (d.key in chosen) chosen - d.key else chosen + d.key }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = have || d.key in chosen, onCheckedChange = null, enabled = !have)
                        Column(Modifier.weight(1f)) {
                            Text(d.name + if (d.form.isNotBlank()) " · " + d.form else "", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                (if (have) tr("Already in list · ", "তালিকায় আছে · ") else "") + listOf(d.generic, d.company).filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (tab == 0) item {
                    Column(Modifier.padding(vertical = 12.dp)) {
                        if (query.trim().length >= 2 && results.isEmpty()) SmallNote(tr("Not found in the list.", "তালিকায় পাওয়া যায়নি।"))
                        TextButton(onClick = onRequest) { Text(tr("Medicine not in the list? Request it", "তালিকায় নেই? যোগের অনুরোধ করুন")) }
                    }
                }
            }
        }
    }
}

/** তালিকার বাইরের ওষুধ যোগের অনুরোধ (অ্যাডমিন যাচাই করবেন) */
@Composable
fun DrugRequestDialog(onDismiss: () -> Unit, onSent: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var generic by remember { mutableStateOf("") }
    var strength by remember { mutableStateOf("") }
    var form by remember { mutableStateOf("") }
    var company by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val mine by produceState(emptyList<DrugRequestItem>()) { value = runCatching { Stock.myRequests() }.getOrDefault(emptyList()) }
    FormDialog(
        title = tr("Request a new medicine", "নতুন ওষুধ যোগের অনুরোধ"),
        onDismiss = { if (!busy) onDismiss() },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallNote(tr("An admin checks it and adds it for everyone.", "অ্যাডমিন যাচাই করে সবার জন্য যোগ করবেন।"))
                OutlinedTextField(name, { name = it.take(80) }, label = { Text(tr("Brand name *", "ব্র্যান্ডের নাম *")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(generic, { generic = it.take(120) }, label = { Text(tr("Generic name", "জেনেরিক নাম")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(strength, { strength = it.take(40) }, label = { Text(tr("Strength (e.g. 500 mg)", "মাত্রা (যেমন 500 mg)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(form, { form = it.take(40) }, label = { Text(tr("Form (tablet, syrup…)", "ধরন (ট্যাবলেট, সিরাপ…)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(company, { company = it.take(80) }, label = { Text(tr("Company", "কোম্পানি")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                ErrorText(errorMsg)
                if (mine.isNotEmpty()) {
                    Text(tr("My requests", "আমার অনুরোধ"), style = MaterialTheme.typography.titleSmall)
                    mine.take(5).forEach { r ->
                        Text(
                            r.name + " — " + when (r.status) {
                                "approved" -> tr("added", "যোগ হয়েছে")
                                "rejected" -> tr("not approved", "অনুমোদন হয়নি")
                                else -> tr("waiting", "অপেক্ষায়")
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && name.trim().length >= 2, onClick = {
                scope.launch {
                    busy = true
                    runCatching { Stock.requestDrug(name.trim(), generic.trim(), strength.trim(), form.trim(), company.trim()) }
                        .onSuccess { onSent() }.onFailure { errorMsg = it.message }
                    busy = false
                }
            }) { ButtonContent(busy, tr("Send", "পাঠান")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancel", "বাতিল")) } },
    )
}

// ------------------------------------------------------------
// ক্রেতা: অর্ডারের ওষুধ কোন দোকানে সাধারণত থাকে
// ------------------------------------------------------------

@Composable
fun WhereAvailableCard(shops: List<StockShop>?, wanted: Int, chosenId: String?, onChoose: (StockShop) -> Unit) {
    if (wanted == 0 || shops == null) return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("Where it's usually available", "কোথায় সাধারণত পাওয়া যায়"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (shops.isEmpty()) {
                SmallNote(
                    tr(
                        "No nearby pharmacy has listed these yet. You can still order — pharmacies can tell you if they can arrange it.",
                        "কাছের কোনো দোকানের তালিকায় এখনো নেই। তবুও অর্ডার দিতে পারেন — দোকান আনিয়ে দিতে পারলে জানাবে।",
                    ),
                )
            }
            shops.take(4).forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val bits = listOfNotNull(
                            tr("${s.has.size} of $wanted", "${num(wanted)}টার মধ্যে ${num(s.has.size)}টা"),
                            s.km?.let { Rules.formatKm(it) },
                            if (s.openNow) tr("open", "খোলা") else tr("closed now", "এখন বন্ধ"),
                        )
                        Text(bits.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        s.prices.entries.take(2).forEach { (k, v) ->
                            val n = Drugs.byKey(k)?.name ?: return@forEach
                            Stock.priceText(v.first, v.second, v.third)?.let { Text("$n: $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                        }
                    }
                    if (chosenId == s.id) {
                        Text(tr("Chosen", "বাছা হয়েছে"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    } else {
                        OutlinedButton(onClick = { onChoose(s) }, enabled = s.openNow) { Text(tr("Send here", "এখানে পাঠান")) }
                    }
                }
            }
            SmallNote(tr("\"Usually available\" — the pharmacy confirms when it replies.", "\"সাধারণত থাকে\" — দোকান উত্তরে নিশ্চিত করবে।"))
        }
    }
}

// ------------------------------------------------------------
// দোকানের বিক্রির হিসাব
// ------------------------------------------------------------

@Composable
fun ShopStatsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var period by remember { mutableStateOf("30") }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { runCatching { Drugs.ensure(context) } }
        Drugs.loadCustom()
        runCatching { Stock.shopStats() }.onSuccess { data = it }.onFailure { errorMsg = it.message }
    }
    Scaffold(topBar = { BackTopBar(tr("Sales report", "বিক্রির হিসাব"), onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ErrorText(errorMsg)
            val d = data ?: run { if (errorMsg == null) LoadingBox(); return@Column }
            SmallNote(tr("Only orders that came through the app. Only you can see this.", "শুধু অ্যাপের মাধ্যমে আসা অর্ডার। এই হিসাব শুধু আপনি দেখতে পান।"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("7" to tr("7 days", "৭ দিন"), "30" to tr("30 days", "৩০ দিন"), "90" to tr("90 days", "৯০ দিন")).forEach { (k, l) ->
                    FilterChip(selected = period == k, onClick = { period = k }, label = { Text(l) })
                }
            }
            val p = d.optJSONObject("periods")?.optJSONObject(period) ?: JSONObject()
            val req = p.optInt("req")
            val done = p.optInt("done")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(tr("Orders received", "অর্ডার এসেছে"), num(req))
                StatTile(tr("You replied", "উত্তর দিয়েছেন"), num(p.optInt("replied")))
                StatTile(tr("Had it", "আছে বলেছেন"), num(p.optInt("avail")))
                StatTile(tr("Didn't have", "নেই বলেছেন"), num(p.optInt("none")))
                StatTile(tr("Customer chose you", "আপনাকে বেছেছেন"), num(p.optInt("chosen")))
                StatTile(tr("Sales completed", "বিক্রি সম্পন্ন"), num(done))
                StatTile(tr("Sales amount", "বিক্রির টাকা"), "৳" + num(p.optInt("amount")))
                StatTile(tr("Not collected", "নেননি"), num(p.optInt("noShow")))
                StatTile(tr("Cancelled", "বাতিল"), num(p.optInt("cancelled")))
            }
            if (req > 0) SmallNote(tr("${done * 100 / req}% of orders became sales.", "অর্ডারের ${num(done * 100 / req)}% বিক্রিতে পরিণত হয়েছে।"))

            Text(tr("Daily sales, last 30 days", "গত ৩০ দিনের দৈনিক বিক্রি"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            val daily = d.optJSONArray("daily") ?: JSONArray()
            MiniBars((0 until daily.length()).map { daily.getJSONObject(it).optInt("done") })

            val top = d.optJSONArray("top") ?: JSONArray()
            if (top.length() > 0) {
                Text(tr("Best-selling medicines", "বেশি বিক্রি হওয়া ওষুধ"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                (0 until top.length()).forEach {
                    val t = top.getJSONObject(it)
                    val name = Drugs.byKey(t.optString("key"))?.name ?: return@forEach
                    Row { Text(name, modifier = Modifier.weight(1f)); Text(tr("${t.optInt("n")} times", "${num(t.optInt("n"))} বার"), style = MaterialTheme.typography.labelMedium) }
                }
            }
            val st = d.optJSONObject("stock") ?: JSONObject()
            SmallNote(
                tr("Rating", "রেটিং") + ": " + (if (d.optInt("ratingCount") > 0) num(d.optDouble("rating").toString()) + " (${num(d.optInt("ratingCount"))})" else "—") +
                    " · " + tr("Stock list", "স্টক তালিকা") + ": " + num(st.optInt("inStock")) + "/" + num(st.optInt("total")),
            )
        }
    }
}

@Composable
private fun StatTile(label: String, value: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.width(150.dp).padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** ছোট বার চার্ট (একটাই সিরিজ) */
@Composable
fun MiniBars(values: List<Int>) {
    val color = MaterialTheme.colorScheme.primary
    val base = MaterialTheme.colorScheme.outlineVariant
    val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
    Column {
        Canvas(Modifier.fillMaxWidth().height(90.dp)) {
            if (values.isEmpty()) return@Canvas
            val slot = size.width / values.size
            val bw = (slot - 2.dp.toPx()).coerceAtLeast(2f)
            values.forEachIndexed { i, v ->
                if (v > 0) {
                    val h = (v.toFloat() / max) * (size.height - 4.dp.toPx())
                    drawRoundRect(color, Offset(i * slot + 1.dp.toPx(), size.height - h), Size(bw, h), CornerRadius(3.dp.toPx(), 3.dp.toPx()))
                }
            }
            drawLine(base, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
        }
        Row {
            Text(tr("30 days ago", "৩০ দিন আগে"), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            Text(tr("Today", "আজ"), style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(2.dp))
        SmallNote(tr("Highest in a day: ${values.maxOrNull() ?: 0}", "এক দিনে সর্বোচ্চ: ${num(values.maxOrNull() ?: 0)}"))
    }
}
