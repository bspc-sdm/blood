@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bspc.blood

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

// ------------------------------------------------------------
// ফার্মেসি: শুধু তালিকা আর সরাসরি ফোন (অ্যাম্বুলেন্সের মতো)।
// অ্যাপ ওষুধ বিক্রি করে না, অর্ডার নেয় না — খোলা দোকান খুঁজে সরাসরি ফোন।
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

    /** অ্যাপ থেকে ফোন করলে (মালিকের হিসাবের জন্য) */
    suspend fun called(id: String) {
        call("pharmacy_called", JSONObject().put("id", id))
    }

    /** নিজের দোকানে অ্যাপ থেকে কতবার ফোন এসেছে */
    suspend fun stats(): JSONObject = call("pharmacy_stats")

    suspend fun adminPending(): List<Pharmacy> {
        val a = call("admin_pharmacies").optJSONArray("items") ?: JSONArray()
        return (0 until a.length()).map { pharmacy(a.getJSONObject(it)) }
    }

    suspend fun resolve(id: String, approve: Boolean) {
        call("admin_pharmacy_resolve", JSONObject().put("id", id).put("approve", approve))
    }

}

/** "সেবা" ট্যাবের ফার্মেসি অংশ: খোলা দোকান খোঁজা / নিজের ফার্মেসি */
@Composable
fun PharmacyContent(profile: Profile) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        SecondaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(tr("Find a pharmacy", "ফার্মেসি খুঁজুন")) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(tr("My pharmacy", "আমার ফার্মেসি")) })
        }
        if (tab == 0) NearbyPharmacies(profile) else MyPharmacyPanel(profile)
    }
}

/** নিজের ফার্মেসি: না থাকলে যোগ করার আমন্ত্রণ, থাকলে অবস্থা, বদলানো আর অ্যাপ থেকে কতবার ফোন এসেছে */
@Composable
private fun MyPharmacyPanel(profile: Profile) {
    var shop by remember { mutableStateOf<Pharmacy?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    var stats by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(reload) {
        runCatching { Pharmacies.mine() }.onSuccess { shop = it; errorMsg = null }.onFailure { errorMsg = it.message }
        loaded = true
        if (shop != null) runCatching { Pharmacies.stats() }.onSuccess { stats = it }
    }
    if (editing) {
        androidx.activity.compose.BackHandler { editing = false }
        ShopEditScreen(
            existing = shop,
            profile = profile,
            onBack = { editing = false },
            onSaved = { pending ->
                editing = false
                infoMsg = if (pending) tr("Sent for approval. An admin will check the licence.", "অনুমোদনের জন্য পাঠানো হয়েছে। অ্যাডমিন লাইসেন্স যাচাই করবেন।") else tr("Saved", "সেভ হয়েছে")
                reload++
            },
        )
        return
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ErrorText(errorMsg)
        InfoText(infoMsg)
        val s = shop
        when {
            !loaded -> LoadingBox()
            s == null && errorMsg == null -> {
                EmptyState(
                    R.drawable.ic_storefront,
                    tr("Have a licensed pharmacy?", "লাইসেন্সধারী ফার্মেসি আছে?"),
                    tr(
                        "Add it so people nearby can find it — especially at night — and call you directly. Free: no commission, the app sells nothing.",
                        "যোগ করুন, যাতে কাছের মানুষ (বিশেষ করে রাতে) খুঁজে পেয়ে সরাসরি ফোন করতে পারেন। বিনামূল্যে: কোনো কমিশন নেই, অ্যাপ কিছু বিক্রি করে না।",
                    ),
                    Modifier.padding(top = 24.dp),
                )
                Button(onClick = { editing = true }, modifier = Modifier.fillMaxWidth()) {
                    ButtonContent(false, tr("Add my pharmacy", "আমার ফার্মেসি যোগ করুন"), R.drawable.ic_add)
                }
            }
            s != null -> {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(s.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(statusText(s.status), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        if (s.hoursText.isNotBlank()) IconLine(R.drawable.ic_schedule, s.hoursText)
                        IconLine(R.drawable.ic_call, num(s.phone))
                        if (s.status == "pending") SmallNote(tr("You'll appear in the list after approval.", "অনুমোদনের পর তালিকায় দেখাবে।"))
                        OutlinedButton(onClick = { editing = true }, modifier = Modifier.fillMaxWidth()) { ButtonContent(false, tr("Edit details", "তথ্য বদলান"), R.drawable.ic_edit) }
                        if (s.status == "suspended" || s.status == "rejected") AppealButton("pharmacy")
                    }
                }
                stats?.let { st ->
                    val calls = st.optJSONObject("calls") ?: JSONObject()
                    Text(tr("Calls from the app", "অ্যাপ থেকে ফোন"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        tr("${calls.optInt("7")} in 7 days · ${calls.optInt("30")} in 30 days", "৭ দিনে ${num(calls.optInt("7"))} · ৩০ দিনে ${num(calls.optInt("30"))}"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val d = st.optJSONArray("daily") ?: org.json.JSONArray()
                    MiniBars((0 until d.length()).map { d.optInt(it) })
                    SmallNote(tr("Only you can see this.", "এই হিসাব শুধু আপনি দেখতে পান।"))
                }
            }
        }
    }
}

@Composable
private fun NearbyPharmacies(profile: Profile) {
    val context = LocalContext.current
    remember { Areas.ensure(context) }
    var district by remember { mutableStateOf(profile.placeName) }
    var upazilaId by remember { mutableStateOf<Int?>(null) }
    var openNow by remember { mutableStateOf(false) }
    var list by remember { mutableStateOf<List<Pharmacy>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val gps = profile.locationSource == "gps"
    val scope = rememberCoroutineScope()
    var reporting by remember { mutableStateOf<Pharmacy?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }

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
            InfoText(infoMsg)
        }
        when {
            loading -> item { LoadingBox() }
            list.isEmpty() -> item {
                EmptyState(
                    R.drawable.ic_storefront,
                    tr("No pharmacy in the app here yet", "এখানে এখনো অ্যাপে কোনো ফার্মেসি নেই"),
                    tr("Know a pharmacy? Ask them to join from \"My pharmacy\".", "কোনো ফার্মেসি চিনলে \"আমার ফার্মেসি\" থেকে যোগ হতে বলুন।"),
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
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = {
                                scope.launch { runCatching { Pharmacies.called(p.id) } }
                                callPhone(context, p.phone)
                            }) { ButtonContent(false, tr("Call", "ফোন করুন"), R.drawable.ic_call) }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { reporting = p }) { Text(tr("Report", "রিপোর্ট"), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        }
    }
    reporting?.let { p ->
        ReasonReportDialog(
            onDismiss = { reporting = null },
            onSubmit = { reason ->
                Moderation.report("pharmacy", p.id, reason = reason)
                reporting = null
                infoMsg = tr("Reported. An admin will review it.", "রিপোর্ট করা হয়েছে। অ্যাডমিন দেখবেন।")
            },
        )
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
                    "Free. People nearby find your pharmacy in the app and call you directly. The app sells nothing and takes no commission.",
                    "বিনামূল্যে। কাছের মানুষ অ্যাপে আপনার ফার্মেসি খুঁজে সরাসরি ফোন করবেন। অ্যাপ কিছু বিক্রি করে না, কোনো কমিশনও নেয় না।",
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
                    else tr("Stand inside the shop and tap the button so people nearby find you.", "দোকানের ভেতরে দাঁড়িয়ে বাটন চাপুন, যাতে কাছের মানুষ খুঁজে পান।"),
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

