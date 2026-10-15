@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.bspc.blood

import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

// ------------------------------------------------------------
// মডেল আর সার্ভার
// ------------------------------------------------------------

data class Pharmacy(
    val id: String,
    val name: String,
    val phone: String,
    val address: String,
    val district: String,
    val upazilaId: Int?,
    val openAt: String,
    val closeAt: String,
    val open24: Boolean,
    val openNow: Boolean,
    val delivery: Boolean,
    val deliveryNote: String,
    val discount: Int,
    val rating: Double,
    val ratingCount: Int,
    val km: Double? = null,
    /** শুধু নিজের দোকানে */
    val licenseNo: String = "",
    val status: String = "",
    val hasLocation: Boolean = false,
) {
    val hoursText: String
        get() = if (open24) tr("Open 24 hours", "২৪ ঘণ্টা খোলা")
        else if (openAt.isNotBlank()) Weekdays.time(openAt) + " – " + Weekdays.time(closeAt) else ""
}

data class MedReply(
    val pharmacyId: String,
    val name: String,
    /** all / some / none */
    val status: String,
    val total: Int,
    val fee: Int,
    val note: String,
    val altBrand: Boolean,
    val discount: Int,
    val rating: Double,
    /** "আনিয়ে দিতে পারব" — এখন নেই, eta: today / tomorrow / days */
    val arrange: Boolean = false,
    val eta: String = "",
    /** দোকানের নিজের লেখা নোট ("আনিয়ে…" ছাড়া) */
    val rawNote: String? = null,
)

data class MedOrder(
    val id: String,
    /** "patient" বা "pharmacy" */
    val role: String,
    val items: String,
    val hasPhoto: Boolean,
    val needsPrescription: Boolean,
    /** delivery / pickup */
    val mode: String,
    val address: String,
    /** open, confirmed, ready, on_the_way, done, cancelled, expired, no_show */
    val status: String,
    val createdAt: Long,
    val otherPhone: String,
    // রোগীর জন্য
    val chosen: String? = null,
    val chosenName: String = "",
    val replies: List<MedReply> = emptyList(),
    val notifiedCount: Int = 0,
    /** এখন যে দোকানে আছে */
    val currentName: String = "",
    /** অন্য দোকানে পাঠানো যায় */
    val canForward: Boolean = false,
    val remindDays: Int = 0,
    val rating: Int = 0,
    // দোকানের জন্য
    val patientName: String = "",
    val myReply: MedReply? = null,
    val km: Double? = null,
) {
    val isActive: Boolean get() = status in setOf("open", "confirmed", "ready", "on_the_way")
}

data class MedOrders(val asPatient: List<MedOrder>, val asPharmacy: List<MedOrder>, val isPharmacy: Boolean)

sealed interface MedOrderResult {
    data class Sent(val notified: Int) : MedOrderResult
    data object NoPharmacy : MedOrderResult
}

object Pharmacies {
    private suspend fun call(action: String, body: JSONObject = JSONObject()): JSONObject =
        ServerApi.post("/api/community", body.put("action", action))

    private fun pharmacy(o: JSONObject) = Pharmacy(
        o.str("id"), o.str("name"), o.str("phone"), o.str("address"), o.str("district"), o.intOrNull("upazilaId"),
        o.str("openAt"), o.str("closeAt"), o.optBoolean("open24"), o.optBoolean("openNow", true), o.optBoolean("delivery"),
        o.str("deliveryNote"), o.optInt("discount"), o.optDouble("rating", 0.0), o.optInt("ratingCount"),
        if (o.isNull("km") || !o.has("km")) null else o.optDouble("km"),
        o.str("licenseNo"), o.str("status"), o.optBoolean("hasLocation"),
    )

    private fun reply(pid: String, o: JSONObject) = MedReply(
        pid, o.str("name"), o.str("status"), o.optInt("total"), o.optInt("fee"), o.str("note"),
        o.optBoolean("altBrand"), o.optInt("discount"), o.optDouble("rating", 0.0),
        o.optBoolean("arrange"), o.str("eta"), if (o.has("rawNote")) o.str("rawNote") else null,
    )

    private fun order(o: JSONObject): MedOrder {
        val rs = o.optJSONArray("replies") ?: JSONArray()
        return MedOrder(
            o.str("id"), o.str("role"), o.str("items"), o.optBoolean("hasPhoto"), o.optBoolean("needsPrescription"),
            o.str("mode"), o.str("address"), o.str("status"), o.optLong("createdAt"), o.str("otherPhone"),
            if (o.isNull("chosen")) null else o.optString("chosen").ifBlank { null }, o.str("chosenName"),
            (0 until rs.length()).map { val r = rs.getJSONObject(it); reply(r.str("pharmacyId"), r) },
            o.optInt("notifiedCount"), o.str("currentName"), o.optBoolean("canForward"), o.optInt("remindDays"), o.optInt("rating"), o.str("patientName"),
            o.optJSONObject("myReply")?.let { reply("", it) },
            if (o.isNull("km") || !o.has("km")) null else o.optDouble("km"),
        )
    }

    suspend fun mine(): Pharmacy? = call("pharmacy_me").optJSONObject("pharmacy")?.let(::pharmacy)

    /** আবার যাচাই লাগবে কি না ফেরত দেয় */
    suspend fun save(
        name: String, licenseNo: String, phone: String, district: String, upazilaId: Int?, address: String,
        lat: Double?, lng: Double?, open24: Boolean, openAt: String, closeAt: String, delivery: Boolean, deliveryNote: String, discount: Int,
    ): Boolean = call(
        "pharmacy_save",
        JSONObject().put("name", name).put("licenseNo", licenseNo).put("phone", phone).put("district", district)
            .put("upazilaId", upazilaId ?: JSONObject.NULL).put("address", address).put("lat", lat ?: JSONObject.NULL)
            .put("lng", lng ?: JSONObject.NULL).put("open24", open24).put("openAt", openAt).put("closeAt", closeAt)
            .put("delivery", delivery).put("deliveryNote", deliveryNote).put("discount", discount).put("termsAccepted", true),
    ).optBoolean("pending")

    suspend fun near(district: String, upazilaId: Int?, lat: Double?, lng: Double?, openNow: Boolean): List<Pharmacy> {
        val a = call(
            "pharmacies",
            JSONObject().put("district", district).put("upazilaId", upazilaId ?: JSONObject.NULL)
                .put("lat", lat ?: JSONObject.NULL).put("lng", lng ?: JSONObject.NULL).put("openNow", openNow),
        ).optJSONArray("items") ?: JSONArray()
        return (0 until a.length()).map { pharmacy(a.getJSONObject(it)) }
    }

    suspend fun order(
        items: String, photo: String?, needsPrescription: Boolean, mode: String, address: String,
        lat: Double?, lng: Double?, district: String, upazilaId: Int?, pharmacyId: String?, drugKeys: List<String> = emptyList(),
    ): MedOrderResult {
        val r = call(
            "med_order",
            JSONObject().put("items", items).put("photo", photo ?: JSONObject.NULL).put("needsPrescription", needsPrescription)
                .put("mode", mode).put("address", address).put("lat", lat ?: JSONObject.NULL).put("lng", lng ?: JSONObject.NULL)
                .put("district", district).put("upazilaId", upazilaId ?: JSONObject.NULL)
                .put("pharmacyId", pharmacyId ?: JSONObject.NULL).put("drugKeys", JSONArray(drugKeys)).put("termsAccepted", true),
        )
        return if (r.optBoolean("noPharmacy")) MedOrderResult.NoPharmacy else MedOrderResult.Sent(r.optInt("notified"))
    }

    suspend fun orders(): MedOrders {
        val r = call("med_orders")
        fun arr(k: String): List<MedOrder> {
            val a = r.optJSONArray(k) ?: JSONArray()
            return (0 until a.length()).map { order(a.getJSONObject(it)) }
        }
        return MedOrders(arr("asPatient"), arr("asPharmacy"), r.optBoolean("isPharmacy"))
    }

    suspend fun photo(id: String): String = call("med_order_photo", JSONObject().put("id", id)).optString("photo")

    suspend fun reply(id: String, status: String, total: Int, fee: Int, note: String, altBrand: Boolean, eta: String? = null) {
        call(
            "med_order_reply",
            JSONObject().put("id", id).put("status", status).put("total", total).put("fee", fee).put("note", note).put("altBrand", altBrand)
                .put("eta", eta ?: JSONObject.NULL),
        )
    }

    fun etaLabel(eta: String): String = when (eta) {
        "today" -> tr("can arrange today", "আজই আনিয়ে দিতে পারবে")
        "tomorrow" -> tr("can arrange by tomorrow", "কাল আনিয়ে দিতে পারবে")
        else -> tr("can arrange in 2-3 days", "২-৩ দিনে আনিয়ে দিতে পারবে")
    }

    /** পরের দোকানে পাঠানো; নতুন দোকানের নাম, না থাকলে null */
    suspend fun forward(id: String): String? {
        val r = call("med_order_forward", JSONObject().put("id", id))
        return if (r.optBoolean("noPharmacy")) null else r.optString("name")
    }

    suspend fun choose(id: String, pharmacyId: String) {
        call("med_order_choose", JSONObject().put("id", id).put("pharmacyId", pharmacyId))
    }

    /** op: ready, on_the_way, done, no_show, cancel, rate, remind */
    suspend fun update(id: String, op: String, rating: Int = 0, comment: String = "", days: Int = 0) {
        call("med_order_update", JSONObject().put("id", id).put("op", op).put("rating", rating).put("comment", comment).put("days", days))
    }

    suspend fun adminPending(): List<Pharmacy> {
        val a = call("admin_pharmacies").optJSONArray("items") ?: JSONArray()
        return (0 until a.length()).map { pharmacy(a.getJSONObject(it)) }
    }

    suspend fun resolve(id: String, approve: Boolean) {
        call("admin_pharmacy_resolve", JSONObject().put("id", id).put("approve", approve))
    }

    fun statusLabel(s: String, role: String): String = when (s) {
        "open" -> if (role == "patient") tr("Waiting for pharmacies", "ফার্মেসির উত্তরের অপেক্ষা") else tr("New order", "নতুন অর্ডার")
        "confirmed" -> tr("Confirmed", "নিশ্চিত")
        "ready" -> tr("Ready", "তৈরি")
        "on_the_way" -> tr("On the way", "রওনা দিয়েছে")
        "done" -> tr("Done", "সম্পন্ন")
        "expired" -> tr("Closed", "বন্ধ হয়ে গেছে")
        "no_show" -> tr("Not received", "নেওয়া হয়নি")
        else -> tr("Cancelled", "বাতিল")
    }
}

fun taka(n: Int): String = num(n) + tr(" Tk", " টাকা")

// ------------------------------------------------------------
// রোগী: ওষুধ (অর্ডার দিন / আমার অর্ডার / কাছের ফার্মেসি)
// ------------------------------------------------------------

/** startTab: ০ অর্ডার দিন, ১ আমার অর্ডার, ২ কাছের ফার্মেসি (নোটিফিকেশন থেকে খোলার পুরো পাতা) */
@Composable
fun MedicineScreen(profile: Profile, startTab: Int, onBack: () -> Unit) {
    Scaffold(topBar = { BackTopBar(tr("Medicine", "ওষুধ"), onBack) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) { MedicineContent(profile, startTab, onOpenShop = null) }
    }
}

/**
 * "সেবা" ট্যাবের ফার্মেসি অংশ: অর্ডার দিন / আমার অর্ডার / ফার্মেসি / আমার দোকান।
 * onOpenShop null হলে "আমার দোকান" দেখানো হয় না (নোটিফিকেশন থেকে খোলা পাতায়)।
 */
@Composable
fun MedicineContent(profile: Profile, startTab: Int, onOpenShop: (() -> Unit)?) {
    var tab by rememberSaveable { mutableIntStateOf(startTab) }
    // আবার অর্ডার: আগের তালিকা আর দোকান নিয়ে ফর্ম খোলে
    var reorder by remember { mutableStateOf<Pair<String, MedReply?>?>(null) }
    var reloadOrders by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        SecondaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(tr("Order", "অর্ডার দিন")) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(tr("My orders", "আমার অর্ডার")) })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text(tr("Pharmacies", "ফার্মেসি")) })
            if (onOpenShop != null) Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text(tr("My shop", "আমার দোকান")) })
        }
        when (tab) {
            0 -> MedOrderForm(profile, reorder, onSent = {
                reorder = null
                reloadOrders++
                tab = 1
            })
            1 -> MyMedOrders(reloadOrders, onReorder = { items, shop ->
                reorder = items to shop
                tab = 0
            })
            2 -> NearbyPharmacies(profile, onOrderHere = { p ->
                reorder = "" to MedReply(p.id, p.name, "all", 0, 0, "", false, p.discount, p.rating)
                tab = 0
            })
            else -> if (onOpenShop != null) MyShopPanel(onOpenShop)
        }
    }
}

/** নিজের ফার্মেসি: না থাকলে খোলার আমন্ত্রণ, থাকলে অবস্থা আর দোকানে যাওয়ার বাটন */
@Composable
private fun MyShopPanel(onOpenShop: () -> Unit) {
    var shop by remember { mutableStateOf<Pharmacy?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { Pharmacies.mine() }.onSuccess { shop = it }.onFailure { errorMsg = it.message }
        loaded = true
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ErrorText(errorMsg)
        val s = shop
        when {
            !loaded -> LoadingBox()
            s == null -> {
                EmptyState(
                    R.drawable.ic_storefront,
                    tr("Have a licensed pharmacy?", "লাইসেন্সধারী ফার্মেসি আছে?"),
                    tr(
                        "Open it in the app and get medicine orders from nearby customers. Free: no commission, and no money passes through the app.",
                        "অ্যাপে খুলুন, কাছের গ্রাহকদের ওষুধের অর্ডার পাবেন। বিনামূল্যে: কোনো কমিশন নেই, টাকাও অ্যাপ দিয়ে যায় না।",
                    ),
                    Modifier.padding(top = 24.dp),
                )
                Button(onClick = onOpenShop, modifier = Modifier.fillMaxWidth()) {
                    ButtonContent(false, tr("Open a pharmacy", "ফার্মেসি খুলুন"), R.drawable.ic_add)
                }
            }
            else -> {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(s.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(statusText(s.status), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Button(onClick = onOpenShop, modifier = Modifier.fillMaxWidth()) {
                            ButtonContent(false, if (s.status == "approved") tr("Open shop & orders", "দোকান ও অর্ডার দেখুন") else tr("Open", "খুলুন"), R.drawable.ic_storefront)
                        }
                        if (s.status == "suspended" || s.status == "rejected") AppealButton("pharmacy")
                    }
                }
            }
        }
    }
}

@Composable
private fun MedOrderForm(profile: Profile, reorder: Pair<String, MedReply?>?, onSent: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    remember { Areas.ensure(context) }
    var items by remember(reorder) { mutableStateOf(reorder?.first ?: "") }
    // কোন দোকানে যাবে: আগের দোকান/ফার্মেসি ট্যাব থেকে, বা নিচের "কোথায় পাওয়া যায়" থেকে বাছা
    var shop by remember(reorder) { mutableStateOf(reorder?.second) }
    // তালিকা থেকে বাছা ওষুধ (লেখায় এখনো থাকলে তবেই পাঠানো হয়)
    var picked by remember(reorder) { mutableStateOf(listOf<Drug>()) }
    var photo by remember(reorder) { mutableStateOf<String?>(null) }
    var photoBusy by remember { mutableStateOf(false) }
    var needsRx by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf("delivery") }
    var address by remember { mutableStateOf("") }
    var lat by remember { mutableStateOf(if (profile.locationSource == "gps") profile.lat else null) }
    var lng by remember { mutableStateOf(if (profile.locationSource == "gps") profile.lng else null) }
    var district by remember { mutableStateOf(profile.placeName) }
    var upazilaId by remember { mutableStateOf<Int?>(null) }
    var agreed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var verifyPhone by remember { mutableStateOf(false) }
    val pickedKeys = picked.filter { items.contains(it.orderLine) }.map { it.key }.distinct()
    val where by androidx.compose.runtime.produceState<List<StockShop>?>(null, pickedKeys, district, lat) {
        val d = district
        value = if (pickedKeys.isEmpty() || d == null) null else runCatching { Stock.find(d, pickedKeys, lat, lng) }.getOrNull()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                photoBusy = true
                runCatching { Photo.toDocumentBase64(context, uri) }.onSuccess { photo = it }.onFailure { errorMsg = it.message }
                photoBusy = false
            }
        }
    }

    fun submit() {
        val d = district
        errorMsg = when {
            items.isBlank() && photo == null -> tr("Add the prescription photo or write the medicines", "প্রেসক্রিপশনের ছবি দিন বা ওষুধের নাম লিখুন")
            needsRx && photo == null -> tr("Prescription medicines need the prescription photo", "প্রেসক্রিপশনের ওষুধে প্রেসক্রিপশনের ছবি লাগবে")
            mode == "delivery" && address.isBlank() -> tr("Write the delivery address", "ডেলিভারির ঠিকানা লিখুন")
            d == null -> tr("Choose a district", "জেলা বেছে নিন")
            shop == null && lat == null && upazilaId == null -> tr("Use your current location or choose the upazila", "বর্তমান লোকেশন দিন অথবা উপজেলা বেছে নিন")
            !agreed -> tr("Please accept the terms", "শর্তে সম্মতি দিন")
            else -> null
        }
        if (errorMsg != null || d == null) return
        scope.launch {
            busy = true
            runCatching { Pharmacies.order(items.trim(), photo, needsRx, mode, address.trim(), lat, lng, d, upazilaId, shop?.pharmacyId, pickedKeys) }
                .onSuccess { r ->
                    when (r) {
                        is MedOrderResult.Sent -> onSent()
                        MedOrderResult.NoPharmacy -> errorMsg = tr(
                            "No open pharmacy nearby in the app right now. Choose a wider area (upazila) or see the Pharmacies tab.",
                            "এখন অ্যাপে কাছে কোনো খোলা ফার্মেসি নেই। উপজেলা বেছে দেখুন, অথবা \"ফার্মেসি\" ট্যাব দেখুন।",
                        )
                    }
                }
                .onFailure { if ((it as? ServerError)?.code == "phone_unverified") verifyPhone = true else errorMsg = it.message }
            busy = false
        }
    }

    if (verifyPhone) {
        PhoneVerifyDialog(onDismiss = { verifyPhone = false }, onVerified = { verifyPhone = false; submit() })
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val target = shop
        if (target != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                InfoText(tr("This order goes to ", "এই অর্ডার যাবে: ") + target.name)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { shop = null }) { Text(tr("Change", "বদলান")) }
            }
        } else {
            SmallNote(
                tr(
                    "Your order goes to the best open pharmacy near you. If they don't have it, it moves to the next one. To pick a shop yourself, use the Pharmacies tab. Pay the pharmacy directly.",
                    "অর্ডার যাবে আপনার কাছের সবচেয়ে ভালো খোলা দোকানে; না থাকলে নিজে থেকেই পরের দোকানে। নিজে দোকান বাছতে \"ফার্মেসি\" ট্যাব দেখুন। টাকা সরাসরি দোকানকে।",
                ),
            )
        }
        // প্রেসক্রিপশনের ছবি
        OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !photoBusy) {
            ButtonContent(photoBusy, if (photo == null) tr("Add prescription photo", "প্রেসক্রিপশনের ছবি দিন") else tr("Change photo", "ছবি বদলান"), R.drawable.ic_add_photo_alternate)
        }
        photo?.let { p ->
            val img = remember(p) { Photo.decodeImage(p) }
            if (img != null) {
                Image(img, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp))
                TextButton(onClick = { photo = null }) { Text(tr("Remove photo", "ছবি সরান")) }
            }
        }
        DrugSearch(onPick = { d ->
            items = (items.trimEnd().let { if (it.isEmpty()) it else it + "\n" } + d.orderLine + " × ").take(1000)
            picked = picked + d
        })
        OutlinedTextField(
            value = items,
            onValueChange = { items = it.take(1000) },
            label = { Text(tr("Medicines (name, strength, quantity)", "ওষুধ (নাম, মাত্রা, কয়টা)")) },
            placeholder = { Text(tr("e.g. Napa 500mg – 10 tablets", "যেমন: নাপা ৫০০ মিগ্রা – ১০টা")) },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        WhereAvailableCard(where, pickedKeys.size, shop?.pharmacyId) { s ->
            shop = MedReply(s.id, s.name, "all", 0, 0, "", false, 0, s.rating)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = needsRx, onCheckedChange = { needsRx = it })
            Text(tr("Includes medicines that need a prescription (antibiotics etc.)", "প্রেসক্রিপশন লাগে এমন ওষুধ আছে (অ্যান্টিবায়োটিক ইত্যাদি)"), style = MaterialTheme.typography.bodyMedium)
        }
        Text(tr("How will you get it", "কীভাবে নেবেন"), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = mode == "delivery", onClick = { mode = "delivery" }, label = { Text(tr("Home delivery", "বাসায় ডেলিভারি")) }, leadingIcon = { AppIcon(R.drawable.ic_local_shipping, null, Modifier.size(18.dp)) })
            FilterChip(selected = mode == "pickup", onClick = { mode = "pickup" }, label = { Text(tr("I'll pick up", "দোকান থেকে নেব")) }, leadingIcon = { AppIcon(R.drawable.ic_shopping_bag, null, Modifier.size(18.dp)) })
        }
        if (mode == "delivery") {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it.take(200) },
                label = { Text(tr("Delivery address", "ডেলিভারির ঠিকানা")) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            SmallNote(tr("Only the pharmacy you choose sees the address and your phone.", "শুধু যে ফার্মেসি বেছে নেবেন তারাই ঠিকানা আর ফোন দেখবে।"))
        }
        if (shop == null) {
            Text(tr("Where", "কোথায়"), style = MaterialTheme.typography.titleSmall)
            AreaChips(district, upazilaId, null, { d, u, _ -> district = d; upazilaId = u }, allowAllDistricts = false, showUnion = false)
            if (lat == null) {
                GpsButton(tr("Use current location", "বর্তমান লোকেশন দিন"), onLocated = { la, ln, d ->
                    lat = la
                    lng = ln
                    if (d != district) upazilaId = null
                    district = d
                })
            } else {
                SmallNote(tr("Using your current location — nearest pharmacies first.", "আপনার বর্তমান লোকেশন ধরা হয়েছে — কাছের ফার্মেসি আগে।"))
            }
        }
        ServiceOrderTermsCheck(agreed) { agreed = it }
        ErrorText(errorMsg)
        Button(onClick = { submit() }, enabled = !busy && !photoBusy, modifier = Modifier.fillMaxWidth()) {
            ButtonContent(busy, tr("Send order", "অর্ডার পাঠান"), R.drawable.ic_send)
        }
    }
}

@Composable
private fun ServiceOrderTermsCheck(checked: Boolean, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Column(Modifier.weight(1f)) {
            Text(tr("I accept the terms (the app only connects; the pharmacy sells)", "শর্ত মেনে নিচ্ছি (অ্যাপ শুধু যোগাযোগ করায়, বিক্রি করে ফার্মেসি)"), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { openUrl(context, TERMS_URL) }, contentPadding = PaddingValues(0.dp)) {
                Text(tr("Read the terms", "শর্তগুলো পড়ুন"), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun MyMedOrders(reloadKey: Int, onReorder: (String, MedReply?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var list by remember { mutableStateOf<List<MedOrder>>(emptyList()) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var rating by remember { mutableStateOf<MedOrder?>(null) }
    var reporting by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reload, reloadKey) {
        loading = true
        runCatching { Pharmacies.orders() }.onSuccess { list = it.asPatient; errorMsg = null }.onFailure { errorMsg = it.message }
        loading = false
    }

    fun act(o: MedOrder, block: suspend () -> Unit) {
        scope.launch {
            busyId = o.id
            runCatching { block() }.onSuccess { reload++ }.onFailure { errorMsg = it.message; reload++ }
            busyId = null
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { reload++ }) { AppIcon(R.drawable.ic_refresh, tr("Refresh", "রিফ্রেশ")) }
            }
            InfoText(infoMsg)
            ErrorText(errorMsg)
        }
        when {
            loading && list.isEmpty() -> item { LoadingBox() }
            list.isEmpty() -> item { EmptyState(R.drawable.ic_medication, tr("No orders yet", "এখনো কোনো অর্ডার নেই"), null, Modifier.padding(top = 32.dp)) }
            else -> items(list, key = { it.id }) { o ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (o.isActive) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(timeAgo(o.createdAt), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                            Tag(Pharmacies.statusLabel(o.status, "patient"), MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                        }
                        if (o.items.isNotBlank()) Text(o.items, style = MaterialTheme.typography.bodyMedium)
                        if (o.hasPhoto) SmallNote(tr("Prescription photo attached", "প্রেসক্রিপশনের ছবি দেওয়া আছে"))
                        IconLine(if (o.mode == "pickup") R.drawable.ic_shopping_bag else R.drawable.ic_local_shipping, if (o.mode == "pickup") tr("Pickup", "দোকান থেকে নেবেন") else tr("Delivery", "ডেলিভারি"))

                        if (o.status == "open") {
                            if (o.currentName.isNotBlank()) IconLine(R.drawable.ic_storefront, tr("Now with: ", "এখন আছে: ") + o.currentName)
                            val quotes = o.replies.filter { it.status != "none" }.sortedBy { it.total + it.fee }
                            if (quotes.isEmpty()) {
                                SmallNote(tr("Waiting for the pharmacy to reply.", "দোকানের উত্তরের অপেক্ষা।"))
                            } else {
                                Text(tr("Replies", "উত্তর"), style = MaterialTheme.typography.titleSmall)
                                quotes.forEach { r ->
                                    HorizontalDivider()
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(r.name, fontWeight = FontWeight.SemiBold)
                                            Text(
                                                (if (r.arrange) Pharmacies.etaLabel(r.eta) else if (r.status == "all") tr("Has all", "সব আছে") else tr("Has some", "কিছু আছে")) + " · " + taka(r.total) +
                                                    (if (r.fee > 0) " + " + tr("delivery ", "ডেলিভারি ") + taka(r.fee) else ""),
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                            if (r.discount > 0) SmallNote(tr("${r.discount}% off MRP", "MRP থেকে ${num(r.discount)}% ছাড়"))
                                            if (r.altBrand) SmallNote(tr("Offers another brand of the same medicine — ask your doctor first", "একই ওষুধের অন্য ব্র্যান্ড দিতে চায় — আগে ডাক্তারকে জিজ্ঞেস করুন"))
                                            // "আনিয়ে দেব"-র লেখা নোটের শুরুতে থাকে (পুরোনো অ্যাপের জন্য) — এখানে আলাদা দেখানো হয়েছে, তাই বাদ
                                            val note = if (r.arrange) r.note.substringAfter(" · ", "").ifBlank { if (r.note.contains("আনিয়ে")) "" else r.note } else r.note
                                            if (note.isNotBlank()) SmallNote(note)
                                            RatingLine(r.rating, if (r.rating > 0) 1 else 0)
                                        }
                                        Button(onClick = { act(o) { Pharmacies.choose(o.id, r.pharmacyId) } }, enabled = busyId == null) {
                                            ButtonContent(busyId == o.id, tr("Choose", "বেছে নিন"))
                                        }
                                    }
                                }
                            }
                        }
                        if (o.canForward) {
                            OutlinedButton(onClick = {
                                act(o) {
                                    val next = Pharmacies.forward(o.id)
                                    infoMsg = if (next != null) tr("Sent to ", "পাঠানো হলো: ") + next else tr("No other open pharmacy nearby.", "কাছে আর কোনো খোলা দোকান নেই।")
                                }
                            }, enabled = busyId == null) { ButtonContent(busyId == o.id, tr("Send to another pharmacy", "অন্য দোকানে পাঠান")) }
                        }
                        if (o.chosenName.isNotBlank()) IconLine(R.drawable.ic_storefront, o.chosenName)
                        if (o.otherPhone.isNotBlank()) {
                            Button(onClick = { callPhone(context, o.otherPhone) }) { ButtonContent(false, tr("Call pharmacy", "ফার্মেসিতে ফোন"), R.drawable.ic_call) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (o.status in setOf("confirmed", "ready", "on_the_way")) {
                                TextButton(onClick = { act(o) { Pharmacies.update(o.id, "done") } }, enabled = busyId == null) { Text(tr("Received", "পেয়েছি")) }
                            }
                            if (o.status == "done" && o.rating == 0 && o.chosen != null) {
                                TextButton(onClick = { rating = o }) { Text(tr("Rate", "রেটিং দিন")) }
                            }
                            if (o.status == "done" && o.chosen != null) {
                                TextButton(onClick = { onReorder(o.items, o.replies.firstOrNull { it.pharmacyId == o.chosen } ?: MedReply(o.chosen, o.chosenName, "all", 0, 0, "", false, 0, 0.0)) }) {
                                    Text(tr("Order again", "আবার অর্ডার"))
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            if (o.isActive) TextButton(onClick = { act(o) { Pharmacies.update(o.id, "cancel") } }, enabled = busyId == null) { Text(tr("Cancel", "বাতিল")) }
                            if (o.chosen != null) IconButton(onClick = { reporting = o.chosen }) { AppIcon(R.drawable.ic_flag, tr("Report", "রিপোর্ট")) }
                        }
                        // মাসিক ওষুধ
                        if (o.chosen != null && o.status in setOf("done", "confirmed", "ready", "on_the_way")) {
                            Text(tr("Remind me to reorder", "আবার অর্ডারের কথা মনে করাও"), style = MaterialTheme.typography.labelLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(0, 15, 30, 60).forEach { d ->
                                    FilterChip(
                                        selected = o.remindDays == d,
                                        onClick = {
                                            act(o) { Pharmacies.update(o.id, "remind", days = d) }
                                            infoMsg = if (d == 0) null else tr("We'll remind you 3 days before.", "৩ দিন আগে মনে করিয়ে দেওয়া হবে।")
                                        },
                                        label = { Text(if (d == 0) tr("Off", "বন্ধ") else tr("$d days", "${num(d)} দিন")) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    rating?.let { o ->
        StarRatingDialog(
            title = tr("Rate ", "রেটিং: ") + o.chosenName,
            onDismiss = { rating = null },
            onSubmit = { stars, comment ->
                Pharmacies.update(o.id, "rate", stars, comment)
                rating = null
                reload++
            },
        )
    }
    reporting?.let { pid ->
        ReasonReportDialog(onDismiss = { reporting = null }, onSubmit = { reason ->
            Moderation.report("pharmacy", pid, reason = reason)
            reporting = null
            infoMsg = tr("Reported. An admin will review it.", "রিপোর্ট করা হয়েছে। অ্যাডমিন দেখবেন।")
        })
    }
}

@Composable
private fun NearbyPharmacies(profile: Profile, onOrderHere: (Pharmacy) -> Unit) {
    val context = LocalContext.current
    remember { Areas.ensure(context) }
    var district by remember { mutableStateOf(profile.placeName) }
    var upazilaId by remember { mutableStateOf<Int?>(null) }
    var openNow by remember { mutableStateOf(false) }
    var list by remember { mutableStateOf<List<Pharmacy>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val gps = profile.locationSource == "gps"

    LaunchedEffect(district, upazilaId, openNow) {
        val d = district ?: return@LaunchedEffect
        loading = true
        runCatching { Pharmacies.near(d, upazilaId, if (gps) profile.lat else null, if (gps) profile.lng else null, openNow) }
            .onSuccess { list = it; errorMsg = null }.onFailure { errorMsg = it.message }
        loading = false
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AreaChips(district, upazilaId, null, { d, u, _ -> district = d; upazilaId = u }, allowAllDistricts = false, showUnion = false)
                FilterChip(selected = openNow, onClick = { openNow = !openNow }, label = { Text(tr("Open now", "এখন খোলা")) })
            }
            ErrorText(errorMsg)
        }
        when {
            loading -> item { LoadingBox() }
            list.isEmpty() -> item {
                EmptyState(
                    R.drawable.ic_storefront,
                    tr("No pharmacy in the app here yet", "এখানে এখনো অ্যাপে কোনো ফার্মেসি নেই"),
                    tr("Know a pharmacy? Ask them to join from \"My shop\".", "কোনো ফার্মেসি চিনলে \"আমার দোকান\" থেকে যোগ হতে বলুন।"),
                    Modifier.padding(top = 24.dp),
                )
            }
            else -> items(list, key = { it.id }) { p ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            if (p.openNow) Tag(tr("Open", "খোলা"), MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                            else Tag(tr("Closed", "বন্ধ"), MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconLine(R.drawable.ic_location_on, p.address + (p.km?.let { " · " + Rules.formatKm(it) } ?: ""))
                        if (p.hoursText.isNotBlank()) IconLine(R.drawable.ic_schedule, p.hoursText)
                        if (p.discount > 0) IconLine(R.drawable.ic_info, tr("${p.discount}% off MRP", "MRP থেকে ${num(p.discount)}% ছাড়"))
                        if (p.delivery) IconLine(R.drawable.ic_local_shipping, tr("Home delivery", "বাসায় ডেলিভারি") + if (p.deliveryNote.isNotBlank()) " · " + p.deliveryNote else "")
                        RatingLine(p.rating, p.ratingCount)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onOrderHere(p) }, enabled = p.openNow) { ButtonContent(false, tr("Order here", "এখানে অর্ডার"), R.drawable.ic_medication) }
                            OutlinedButton(onClick = { callPhone(context, p.phone) }) { ButtonContent(false, tr("Call", "ফোন"), R.drawable.ic_call) }
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------
// ফার্মেসি মালিক: আমার ফার্মেসি
// ------------------------------------------------------------

@Composable
fun ShopScreen(profile: Profile, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var shop by remember { mutableStateOf<Pharmacy?>(null) }
    var orders by remember { mutableStateOf<List<MedOrder>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    var replying by remember { mutableStateOf<MedOrder?>(null) }
    var photoOf by remember { mutableStateOf<String?>(null) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    // দোকানের ভেতরের পাতা: "stock" স্টক তালিকা, "stats" বিক্রির হিসাব
    var inner by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reload) {
        loading = true
        loadFailed = false
        // লোড না হলে ফর্ম নয়, ভুলের বার্তা — নইলে আগের দোকান ফাঁকা ফর্মে আবার লেখা হয়ে যেত
        runCatching { Pharmacies.mine() }.onSuccess { shop = it; errorMsg = null }.onFailure { errorMsg = it.message; loadFailed = true }
        // বন্ধ থাকলেও চলমান অর্ডার শেষ করা যায়
        if (shop != null) {
            runCatching { Pharmacies.orders() }.onSuccess { orders = it.asPharmacy }.onFailure { errorMsg = it.message }
        }
        loading = false
    }

    fun act(o: MedOrder, op: String) {
        scope.launch {
            busyId = o.id
            runCatching { Pharmacies.update(o.id, op) }.onSuccess { reload++ }.onFailure { errorMsg = it.message; reload++ }
            busyId = null
        }
    }

    androidx.activity.compose.BackHandler(enabled = inner != null) { inner = null }
    when (inner) {
        "stock" -> { StockScreen(onBack = { inner = null }); return }
        "stats" -> { ShopStatsScreen(onBack = { inner = null }); return }
    }

    if (editing || (!loading && !loadFailed && shop == null)) {
        ShopEditScreen(
            existing = shop,
            profile = profile,
            onBack = { if (shop == null) onBack() else editing = false },
            onSaved = { pending ->
                editing = false
                infoMsg = if (pending) tr("Sent for approval. An admin will check the licence.", "অনুমোদনের জন্য পাঠানো হয়েছে। অ্যাডমিন লাইসেন্স যাচাই করবেন।") else tr("Saved", "সেভ হয়েছে")
                reload++
            },
        )
        return
    }

    Scaffold(
        topBar = {
            BackTopBar(shop?.name ?: tr("My pharmacy", "আমার ফার্মেসি"), onBack) {
                IconButton(onClick = { reload++ }) { AppIcon(R.drawable.ic_refresh, tr("Refresh", "রিফ্রেশ")) }
                IconButton(onClick = { editing = true }) { AppIcon(R.drawable.ic_edit, tr("Edit", "বদলান")) }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                InfoText(infoMsg)
                ErrorText(errorMsg)
            }
            val s = shop
            if (loading && s == null) item { LoadingBox() }
            if (s != null) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(statusText(s.status), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                            Text(tr("Licence: ", "লাইসেন্স: ") + s.licenseNo, style = MaterialTheme.typography.bodySmall)
                            if (s.hoursText.isNotBlank()) IconLine(R.drawable.ic_schedule, s.hoursText + if (s.openNow) tr(" · open now", " · এখন খোলা") else tr(" · closed now", " · এখন বন্ধ"))
                            RatingLine(s.rating, s.ratingCount)
                            if (s.status == "pending") SmallNote(tr("Orders will start coming after approval.", "অনুমোদনের পর অর্ডার আসা শুরু হবে।"))
                            if (s.status == "suspended" || s.status == "rejected") AppealButton("pharmacy")
                            if (s.status == "approved" || s.status == "pending") {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                                    OutlinedButton(onClick = { inner = "stock" }) { ButtonContent(false, tr("Stock list", "স্টক তালিকা"), R.drawable.ic_list_alt) }
                                    OutlinedButton(onClick = { inner = "stats" }) { ButtonContent(false, tr("Sales report", "বিক্রির হিসাব"), R.drawable.ic_history) }
                                }
                            }
                        }
                    }
                }
                if (s.status == "approved") {
                    val newOnes = orders.filter { it.status == "open" && it.myReply == null }
                    val replied = orders.filter { it.status == "open" && it.myReply != null }
                    val mine = orders.filter { it.status != "open" }
                    item { SectionTitle(tr("New orders", "নতুন অর্ডার") + if (newOnes.isNotEmpty()) " (${num(newOnes.size)})" else "") }
                    if (newOnes.isEmpty()) item { SmallNote(tr("New orders from nearby customers appear here, with a notification.", "কাছের গ্রাহকদের নতুন অর্ডার এখানে আসবে, নোটিফিকেশনও পাবেন।")) }
                    items(newOnes, key = { "n" + it.id }) { o -> ShopOrderCard(o, busyId, onPhoto = { photoOf = o.id }, onReply = { replying = o }, onAct = { op -> act(o, op) }) }
                    if (replied.isNotEmpty()) {
                        item { SectionTitle(tr("Waiting for customer", "গ্রাহকের সিদ্ধান্তের অপেক্ষা")) }
                        items(replied, key = { "r" + it.id }) { o -> ShopOrderCard(o, busyId, onPhoto = { photoOf = o.id }, onReply = { replying = o }, onAct = { op -> act(o, op) }) }
                    }
                    if (mine.isNotEmpty()) {
                        item { SectionTitle(tr("My orders", "আমার অর্ডার")) }
                        items(mine, key = { "m" + it.id }) { o -> ShopOrderCard(o, busyId, onPhoto = { photoOf = o.id }, onReply = {}, onAct = { op -> act(o, op) }) }
                    }
                }
            }
        }
    }

    replying?.let { o ->
        QuickReplyDialog(o, onDismiss = { replying = null }, onSent = {
            replying = null
            reload++
        })
    }
    photoOf?.let { id -> PrescriptionPhotoDialog(id) { photoOf = null } }
}

@Composable
private fun ShopOrderCard(o: MedOrder, busyId: String?, onPhoto: () -> Unit, onReply: () -> Unit, onAct: (String) -> Unit) {
    val context = LocalContext.current
    val live = o.status in setOf("confirmed", "ready", "on_the_way")
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (live) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.patientName.ifBlank { tr("Customer", "গ্রাহক") }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Tag(Pharmacies.statusLabel(o.status, "pharmacy"), MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
            }
            SmallNote(timeAgo(o.createdAt) + (o.km?.let { " · " + Rules.formatKm(it) } ?: ""))
            if (o.items.isNotBlank()) Text(o.items, style = MaterialTheme.typography.bodyLarge)
            if (o.needsPrescription) SmallNote(tr("Includes prescription medicines — check the prescription", "প্রেসক্রিপশনের ওষুধ আছে — প্রেসক্রিপশন দেখে নিন"))
            if (o.hasPhoto) OutlinedButton(onClick = onPhoto) { ButtonContent(false, tr("See prescription", "প্রেসক্রিপশন দেখুন"), R.drawable.ic_add_photo_alternate) }
            IconLine(if (o.mode == "pickup") R.drawable.ic_shopping_bag else R.drawable.ic_local_shipping, if (o.mode == "pickup") tr("Customer will pick up", "গ্রাহক দোকান থেকে নেবেন") else tr("Home delivery", "বাসায় ডেলিভারি"))
            o.myReply?.let { r ->
                SmallNote(tr("Your reply: ", "আপনার উত্তর: ") + (if (r.arrange) Pharmacies.etaLabel(r.eta) else if (r.status == "all") tr("all", "সব আছে") else if (r.status == "some") tr("some", "কিছু আছে") else tr("none", "নেই")) + if (r.total > 0) " · " + taka(r.total) + (if (r.fee > 0) " + " + taka(r.fee) else "") else "")
            }
            if (o.address.isNotBlank()) IconLine(R.drawable.ic_home_outline, o.address, MaterialTheme.colorScheme.onSurface)
            if (o.otherPhone.isNotBlank()) Button(onClick = { callPhone(context, o.otherPhone) }) { ButtonContent(false, tr("Call customer", "গ্রাহককে ফোন"), R.drawable.ic_call) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                val enabled = busyId == null
                when (o.status) {
                    "open" -> Button(onClick = onReply, enabled = enabled) { Text(if (o.myReply == null) tr("Reply", "উত্তর দিন") else tr("Change reply", "উত্তর বদলান")) }
                    "confirmed" -> {
                        Button(onClick = { onAct(if (o.mode == "pickup") "ready" else "on_the_way") }, enabled = enabled) {
                            ButtonContent(busyId == o.id, if (o.mode == "pickup") tr("Ready for pickup", "তৈরি, নিয়ে যান") else tr("Sent for delivery", "ডেলিভারিতে পাঠালাম"))
                        }
                    }
                    "ready", "on_the_way" -> {
                        Button(onClick = { onAct("done") }, enabled = enabled) { ButtonContent(busyId == o.id, tr("Delivered", "দেওয়া হয়েছে")) }
                        TextButton(onClick = { onAct("no_show") }, enabled = enabled) { Text(tr("Not received", "নেননি")) }
                    }
                }
            }
        }
    }
}

/** দোকানের দ্রুত উত্তর: সব আছে / কিছু আছে / নেই, মোট দাম, ডেলিভারি চার্জ */
@Composable
private fun QuickReplyDialog(o: MedOrder, onDismiss: () -> Unit, onSent: () -> Unit) {
    val scope = rememberCoroutineScope()
    // "arrange" অ্যাপের ভেতরের বাছাই; সার্ভারে যায় status "all" + eta
    var status by remember { mutableStateOf(if (o.myReply?.arrange == true) "arrange" else o.myReply?.status ?: "all") }
    var eta by remember { mutableStateOf(o.myReply?.eta?.ifBlank { null } ?: "tomorrow") }
    var total by remember { mutableStateOf(o.myReply?.total?.takeIf { it > 0 }?.toString() ?: "") }
    var fee by remember { mutableStateOf(o.myReply?.fee?.takeIf { it > 0 }?.toString() ?: "") }
    var note by remember { mutableStateOf(o.myReply?.let { it.rawNote ?: it.note } ?: "") }
    var altBrand by remember { mutableStateOf(o.myReply?.altBrand ?: false) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    FormDialog(
        title = tr("Reply to order", "অর্ডারের উত্তর"),
        onDismiss = { if (!busy) onDismiss() },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "all" to tr("Have all", "সব আছে"), "some" to tr("Have some", "কিছু আছে"),
                        "arrange" to tr("Can arrange", "আনিয়ে দিতে পারব"), "none" to tr("Don't have", "নেই"),
                    ).forEach { (k, l) ->
                        FilterChip(selected = status == k, onClick = { status = k }, label = { Text(l) })
                    }
                }
                if (status == "arrange") {
                    Text(tr("By when", "কবে দিতে পারবেন"), style = MaterialTheme.typography.titleSmall)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("today" to tr("Today", "আজই"), "tomorrow" to tr("Tomorrow", "কাল"), "days" to tr("2-3 days", "২-৩ দিনে")).forEach { (k, l) ->
                            FilterChip(selected = eta == k, onClick = { eta = k }, label = { Text(l) })
                        }
                    }
                }
                if (status != "none") {
                    OutlinedTextField(
                        total, { total = it.filter(Char::isDigit).take(7) },
                        label = { Text(tr("Total price (Tk, after discount)", "মোট দাম (টাকা, ছাড়ের পরে)")) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    if (o.mode == "delivery") {
                        OutlinedTextField(
                            fee, { fee = it.filter(Char::isDigit).take(4) },
                            label = { Text(tr("Delivery charge (Tk)", "ডেলিভারি চার্জ (টাকা)")) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = altBrand, onCheckedChange = { altBrand = it })
                        Text(tr("Some are another brand of the same medicine", "কিছু ওষুধ একই জেনেরিকের অন্য ব্র্যান্ডের"), style = MaterialTheme.typography.bodyMedium)
                    }
                    OutlinedTextField(note, { note = it.take(200) }, label = { Text(tr("Note (optional)", "নোট (ঐচ্ছিক)")) }, placeholder = { Text(tr("e.g. Delivery within 1 hour", "যেমন: ১ ঘণ্টার মধ্যে ডেলিভারি")) }, modifier = Modifier.fillMaxWidth())
                }
                ErrorText(errorMsg)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                val t = total.toIntOrNull() ?: 0
                errorMsg = if (status != "none" && t <= 0) tr("Enter the total price", "মোট দাম লিখুন") else null
                if (errorMsg != null) return@TextButton
                scope.launch {
                    busy = true
                    val arrange = status == "arrange"
                    runCatching { Pharmacies.reply(o.id, if (arrange) "all" else status, t, fee.toIntOrNull() ?: 0, note.trim(), altBrand, if (arrange) eta else null) }
                        .onSuccess { onSent() }.onFailure { errorMsg = it.message }
                    busy = false
                }
            }) { ButtonContent(busy, tr("Send", "পাঠান")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancel", "বাতিল")) } },
    )
}

@Composable
private fun PrescriptionPhotoDialog(orderId: String, onDismiss: () -> Unit) {
    var img by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(orderId) {
        runCatching { Pharmacies.photo(orderId) }.onSuccess { img = Photo.decodeImage(it) }.onFailure { errorMsg = it.message }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
            Column {
                BackTopBar(tr("Prescription", "প্রেসক্রিপশন"), onDismiss)
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp), contentAlignment = Alignment.TopCenter) {
                    val i = img
                    when {
                        i != null -> Image(i, contentDescription = null, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
                        errorMsg != null -> ErrorText(errorMsg)
                        else -> LoadingBox()
                    }
                }
            }
        }
    }
}

@Composable
private fun ShopEditScreen(existing: Pharmacy?, profile: Profile, onBack: () -> Unit, onSaved: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    remember { Areas.ensure(context) }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var licenseNo by remember { mutableStateOf(existing?.licenseNo ?: "") }
    var phone by remember { mutableStateOf(existing?.phone ?: profile.phone) }
    var district by remember { mutableStateOf(existing?.district ?: profile.placeName) }
    var upazilaId by remember { mutableStateOf(existing?.upazilaId) }
    var address by remember { mutableStateOf(existing?.address ?: "") }
    var lat by remember { mutableStateOf<Double?>(null) }
    var lng by remember { mutableStateOf<Double?>(null) }
    var open24 by remember { mutableStateOf(existing?.open24 ?: false) }
    var openAt by remember { mutableStateOf(existing?.openAt?.ifBlank { null } ?: "09:00") }
    var closeAt by remember { mutableStateOf(existing?.closeAt?.ifBlank { null } ?: "23:00") }
    var delivery by remember { mutableStateOf(existing?.delivery ?: true) }
    var deliveryNote by remember { mutableStateOf(existing?.deliveryNote ?: "") }
    var discount by remember { mutableStateOf(existing?.discount?.toString() ?: "0") }
    var agreed by remember { mutableStateOf(existing != null) }
    var pickTime by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val hadLocation = existing?.hasLocation == true

    Scaffold(topBar = { BackTopBar(if (existing == null) tr("Open a pharmacy", "ফার্মেসি খুলুন") else tr("Edit pharmacy", "ফার্মেসি বদলান"), onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SmallNote(
                tr(
                    "Free — the app takes no commission. You don't list your stock: customers send orders, you reply with price in a few taps.",
                    "বিনামূল্যে — অ্যাপ কোনো কমিশন নেয় না। স্টকের তালিকা দিতে হবে না: গ্রাহক অর্ডার পাঠাবেন, আপনি কয়েক ট্যাপে দাম জানাবেন।",
                ),
            )
            OutlinedTextField(name, { name = it.take(100) }, label = { Text(tr("Pharmacy name", "ফার্মেসির নাম")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(licenseNo, { licenseNo = it.take(40) }, label = { Text(tr("Drug licence number", "ড্রাগ লাইসেন্স নম্বর")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                phone, { phone = it.filter { c -> c.isDigit() || c == '+' }.take(15) },
                label = { Text(tr("Shop phone", "দোকানের ফোন")) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            AreaChips(district, upazilaId, null, { d, u, _ -> district = d; upazilaId = u }, allowAllDistricts = false, showUnion = false)
            OutlinedTextField(address, { address = it.take(200) }, label = { Text(tr("Shop address", "দোকানের ঠিকানা")) }, modifier = Modifier.fillMaxWidth())
            if (lat != null) {
                InfoText(tr("Shop location set", "দোকানের লোকেশন নেওয়া হয়েছে"))
            } else {
                SmallNote(
                    if (hadLocation) tr("Location already set. Update only if you moved.", "লোকেশন আগেই দেওয়া আছে। দোকান সরালে আবার দিন।")
                    else tr("Stand inside the shop and tap the button so nearby customers find you.", "দোকানের ভেতরে দাঁড়িয়ে বাটন চাপুন, যাতে কাছের গ্রাহকরা পান।"),
                )
                GpsButton(tr("Use current location as shop location", "বর্তমান লোকেশনকে দোকানের লোকেশন ধরুন"), onLocated = { la, ln, _ -> lat = la; lng = ln })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Open 24 hours", "২৪ ঘণ্টা খোলা"), modifier = Modifier.weight(1f))
                Switch(checked = open24, onCheckedChange = { open24 = it })
            }
            if (!open24) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { pickTime = 1 }) { Text(tr("Opens ", "খোলে ") + Weekdays.time(openAt)) }
                    OutlinedButton(onClick = { pickTime = 2 }) { Text(tr("Closes ", "বন্ধ ") + Weekdays.time(closeAt)) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Home delivery", "বাসায় ডেলিভারি দিই"), modifier = Modifier.weight(1f))
                Switch(checked = delivery, onCheckedChange = { delivery = it })
            }
            if (delivery) {
                OutlinedTextField(deliveryNote, { deliveryNote = it.take(120) }, label = { Text(tr("Delivery area / charge (optional)", "ডেলিভারির এলাকা / চার্জ (ঐচ্ছিক)")) }, modifier = Modifier.fillMaxWidth())
            }
            OutlinedTextField(
                discount, { discount = it.filter(Char::isDigit).take(2) },
                label = { Text(tr("Usual discount on MRP (%)", "সাধারণত MRP থেকে কত % ছাড়")) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            ServiceTermsCheck(agreed) { agreed = it }
            ErrorText(errorMsg)
            Button(
                onClick = {
                    val d = district
                    errorMsg = when {
                        name.isBlank() -> tr("Enter the pharmacy name", "ফার্মেসির নাম লিখুন")
                        licenseNo.isBlank() -> tr("Enter the drug licence number", "ড্রাগ লাইসেন্স নম্বর দিন")
                        phone.length < 11 -> tr("Enter a valid mobile number", "সঠিক মোবাইল নম্বর দিন")
                        d == null -> tr("Choose a district", "জেলা বেছে নিন")
                        address.isBlank() -> tr("Write the shop address", "দোকানের ঠিকানা লিখুন")
                        lat == null && !hadLocation -> tr("Set the shop location", "দোকানের লোকেশন দিন")
                        (discount.toIntOrNull() ?: 0) > 30 -> tr("Discount can be up to 30%", "ছাড় সর্বোচ্চ ৩০%")
                        !agreed -> tr("Please accept the terms", "শর্তে সম্মতি দিন")
                        else -> null
                    }
                    if (errorMsg != null || d == null) return@Button
                    scope.launch {
                        busy = true
                        runCatching {
                            Pharmacies.save(
                                name.trim(), licenseNo.trim(), phone, d, upazilaId, address.trim(), lat, lng,
                                open24, openAt, closeAt, delivery, deliveryNote.trim(), discount.toIntOrNull() ?: 0,
                            )
                        }.onSuccess { onSaved(it) }.onFailure { errorMsg = it.message }
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { ButtonContent(busy, tr("Save", "সেভ করুন")) }
        }
    }

    if (pickTime != 0) {
        val cur = (if (pickTime == 1) openAt else closeAt).split(":")
        TimePickerPopup(
            cur.getOrNull(0)?.toIntOrNull(), cur.getOrNull(1)?.toIntOrNull(),
            onPicked = { h, m -> if (pickTime == 1) openAt = Weekdays.hhmm(h, m) else closeAt = Weekdays.hhmm(h, m) },
            onDismiss = { pickTime = 0 },
            title = if (pickTime == 1) tr("Opens at", "খোলে") else tr("Closes at", "বন্ধ হয়"),
        )
    }
}

/** ওষুধের নাম খুঁজে তালিকায় যোগ (DGDA-র নিবন্ধিত ওষুধ) */
@Composable
private fun DrugSearch(onPick: (Drug) -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { Drugs.ensure(context) } }
        ready = true
        Drugs.loadCustom()
    }
    val results by androidx.compose.runtime.produceState(initialValue = emptyList<Drug>(), query, ready) {
        value = if (!ready) emptyList() else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { Drugs.search(query) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it.take(40) },
            label = { Text(tr("Search medicine name", "ওষুধের নাম খুঁজুন")) },
            placeholder = { Text(tr("e.g. Napa, Seclo, Paracetamol", "যেমন: Napa, Seclo, Paracetamol")) },
            leadingIcon = { AppIcon(R.drawable.ic_search, null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        results.forEach { d ->
            androidx.compose.material3.Surface(
                onClick = {
                    onPick(d)
                    query = ""
                },
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(d.name + if (d.form.isNotBlank()) " · " + d.form else "", fontWeight = FontWeight.SemiBold)
                    Text(listOf(d.generic, d.company).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (query.trim().length >= 2 && ready && results.isEmpty()) SmallNote(tr("Not found — write it below.", "পাওয়া যায়নি — নিচে নিজে লিখুন।"))
    }
}
