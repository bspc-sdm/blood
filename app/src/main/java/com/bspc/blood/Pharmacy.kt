@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bspc.blood

import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
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
    /** মালিক সাময়িক বন্ধ রাখলে কবে পর্যন্ত (০ = খোলা নিয়মমতো) */
    val closedUntil: Long = 0,
    /** সাপ্তাহিক বন্ধের দিন (০ = রবিবার) */
    val closedDays: List<Int> = emptyList(),
    val statusReason: String = "",
    val docsMissing: Boolean = false,
    val docsRequested: Boolean = false,
    val docsNote: String = "",
    /** রেটিং দেওয়া যায় (অ্যাপ থেকে ফোনের ১ ঘণ্টা পর, বা এই দোকান থেকে ওষুধ নেওয়ার পর; সার্ভার মনে রাখে) */
    val canRate: Boolean = false,
    /** কোন দিকে রেটিং দেওয়া যায় ("behaviour", "price", "delivery") — সার্ভার ঠিক করে */
    val rateAspects: List<String> = emptyList(),
    /** দিকভিত্তিক গড়: দিক → (গড়, কতজন) — যেগুলোতে অন্তত একটা রেটিং */
    val aspects: Map<String, Pair<Double, Int>> = emptyMap(),
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
        o.str("licenseNo"), o.str("status"), o.optBoolean("hasLocation"), o.optLong("closedUntil"),
        o.optJSONArray("closedDays").ints(), o.str("statusReason"),
        o.optBoolean("docsMissing"), o.optBoolean("docsRequested"), o.str("docsRequestNote"),
        o.optBoolean("canRate"),
        o.optJSONArray("rateAspects").strings(),
        aspectsOf(o.optJSONObject("aspects")),
    )

    private fun aspectsOf(o: JSONObject?): Map<String, Pair<Double, Int>> {
        if (o == null) return emptyMap()
        return RATE_ASPECTS.mapNotNull { k ->
            val a = o.optJSONObject(k) ?: return@mapNotNull null
            if (a.optInt("count") > 0) k to (a.optDouble("rating", 0.0) to a.optInt("count")) else null
        }.toMap()
    }

    /** until = null হলে আবার খোলা */
    suspend fun pause(until: Long?) {
        call("pharmacy_pause", JSONObject().put("until", until ?: JSONObject.NULL))
    }

    suspend fun delete() {
        call("pharmacy_delete")
    }

    suspend fun mine(): Pharmacy? = call("pharmacy_me").optJSONObject("pharmacy")?.let(::pharmacy)

    /** আবার যাচাই লাগবে কি না ফেরত দেয় */
    suspend fun save(
        name: String, licenseNo: String, phone: String, district: String, upazilaId: Int?, address: String,
        lat: Double?, lng: Double?, open24: Boolean, openAt: String, closeAt: String, delivery: Boolean, deliveryNote: String, discount: Int,
        closedDays: List<Int> = emptyList(),
        docs: Map<String, String> = emptyMap(),
    ): Boolean = call(
        "pharmacy_save",
        JSONObject().put("name", name).put("licenseNo", licenseNo).put("phone", phone).put("district", district)
            .put("upazilaId", upazilaId ?: JSONObject.NULL).put("address", address).put("lat", lat ?: JSONObject.NULL)
            .put("lng", lng ?: JSONObject.NULL).put("open24", open24).put("openAt", openAt).put("closeAt", closeAt)
            .put("delivery", delivery).put("deliveryNote", deliveryNote).put("discount", discount)
            .put("closedDays", JSONArray(closedDays)).put("termsAccepted", true).put("docs", VerifyDocs.json(docs)),
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

    /**
     * ফোন বা কেনার পর রেটিং (একজন একটা দোকানে একবার; পরে বদলানো যায়) — দোকানের সেবার, দিকে দিকে:
     * aspects = দিক → ১–৫ তারা (ব্যবহার লাগবেই; দাম/ডেলিভারি সার্ভার যেগুলো দেয়)
     */
    suspend fun rate(id: String, aspects: Map<String, Int>, comment: String) {
        val a = JSONObject()
        aspects.forEach { (k, v) -> if (v > 0) a.put(k, v) }
        call("pharmacy_rate", JSONObject().put("id", id).put("aspects", a).put("comment", comment))
    }

    /** নিজের দোকানে অ্যাপ থেকে কতবার ফোন এসেছে, রেটিং, সাম্প্রতিক মন্তব্য */
    suspend fun stats(): JSONObject = call("pharmacy_stats")

    suspend fun adminPending(): List<Pharmacy> {
        val a = call("admin_pharmacies").optJSONArray("items") ?: JSONArray()
        return (0 until a.length()).map { pharmacy(a.getJSONObject(it)) }
    }

    suspend fun resolve(id: String, approve: Boolean) {
        call("admin_pharmacy_resolve", JSONObject().put("id", id).put("approve", approve))
    }

}

/** "সেবা" ট্যাবের ফার্মেসি অংশ: খোলা দোকান খোঁজা / ওষুধ খোঁজা / নিজের ফার্মেসি */
@Composable
fun PharmacyContent(profile: Profile) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // নোটিফিকেশন থেকে: নিজের খোঁজের উত্তর → "ওষুধ খুঁজুন", দোকানের অনুরোধ → "আমার ফার্মেসি"
    val target = ServicesNav.target.value
    LaunchedEffect(target) {
        if (target == "med_find_mine") {
            tab = 1
            ServicesNav.target.value = null
        }
    }
    Column(Modifier.fillMaxSize()) {
        PillTabs(
            tab,
            listOf(
                PillTab(tr("Pharmacies", "ফার্মেসি"), R.drawable.ic_storefront),
                PillTab(tr("Find a medicine", "ওষুধ খুঁজুন"), R.drawable.ic_search),
            ),
            { tab = it },
            secondary = true,
        )
        // নিজের ফার্মেসি: "সেবা" পাতার নিচের ভাসমান বাটন / উপরের + থেকে (ServicesTab)
        when (tab) {
            0 -> NearbyPharmacies(profile)
            else -> MedFindPanel(profile)
        }
    }
}

/** নিজের ফার্মেসি: না থাকলে যোগ করার আমন্ত্রণ, থাকলে অবস্থা, বদলানো আর অ্যাপ থেকে কতবার ফোন এসেছে */
@Composable
internal fun MyPharmacyPanel(profile: Profile) {
    var shop by remember { mutableStateOf<Pharmacy?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    var stats by remember { mutableStateOf<JSONObject?>(null) }
    var pausing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun act(done: String? = null, block: suspend () -> Unit) {
        scope.launch {
            busy = true
            errorMsg = null
            runCatching { block() }.onSuccess { infoMsg = done; reload++ }.onFailure { errorMsg = it.message }
            busy = false
        }
    }
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
    if (pausing) {
        AlertDialog(
            onDismissRequest = { pausing = false },
            title = { Text(tr("Closed for how long?", "কত দিন বন্ধ থাকবে?")) },
            text = {
                Column {
                    listOf(1, 3, 7, 15, 30).forEach { d ->
                        TextButton(onClick = {
                            pausing = false
                            // বাংলাদেশ সময়ে শেষ দিনের রাত পর্যন্ত
                            val until = Rules.todayUtcMidnight() + (d + 1) * DAY_MS - Rules.DHAKA_OFFSET_MS
                            act(tr("People will see it as closed until then", "ততদিন তালিকায় \"বন্ধ\" দেখাবে")) { Pharmacies.pause(until) }
                        }) { Text(tr("$d day(s)", "${num(d)} দিন")) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pausing = false }) { Text(tr("Cancel", "বাতিল")) } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(tr("Remove your pharmacy?", "ফার্মেসি মুছে ফেলবেন?")) },
            text = { Text(tr("It will disappear from the list. You can add it again later (it will need approval again).", "তালিকা থেকে সরে যাবে। পরে আবার যোগ করা যাবে (আবার অনুমোদন লাগবে)।")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    act(tr("Removed", "মুছে ফেলা হয়েছে")) { Pharmacies.delete() }
                }) { Text(tr("Remove", "মুছে ফেলুন"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("Cancel", "বাতিল")) } },
        )
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
                        StatusReasonLine(s.status, s.statusReason)
                        DocsNeededNotice("pharmacy", s.id, VerifyDocs.pharmacy(), s.docsMissing, s.docsRequested, s.docsNote) { reload++ }
                        if (s.hoursText.isNotBlank()) IconLine(R.drawable.ic_schedule, listOf(s.hoursText, closedDaysText(s.closedDays)).filter { it.isNotBlank() }.joinToString(" · "))
                        IconLine(R.drawable.ic_call, num(s.phone))
                        if (s.status == "pending") SmallNote(tr("You'll appear in the list after approval.", "অনুমোদনের পর তালিকায় দেখাবে।"))
                        OutlinedButton(onClick = { editing = true }, modifier = Modifier.fillMaxWidth()) { ButtonContent(false, tr("Edit details", "তথ্য বদলান"), R.drawable.ic_edit) }
                        // ঈদ/সংস্কারে কয়েক দিন বন্ধ: তালিকায় "বন্ধ" দেখাবে, তারিখ পার হলে নিজে খুলবে
                        if (s.status != "suspended") {
                            if (s.closedUntil > 0) {
                                IconLine(R.drawable.ic_info, tr("Closed until ", "বন্ধ থাকবে ") + Rules.formatDate(s.closedUntil) + tr("", " পর্যন্ত"))
                                SmallAction(tr("Open again", "আবার খুলুন"), R.drawable.ic_storefront, { act(tr("Opened again", "আবার খোলা হয়েছে")) { Pharmacies.pause(null) } }, busy = busy)
                            } else {
                                SmallAction(tr("Closed for some days", "কয়েক দিন বন্ধ থাকবে"), R.drawable.ic_schedule, { pausing = true }, enabled = !busy)
                            }
                            TextButton(onClick = { confirmDelete = true }, enabled = !busy) {
                                Text(tr("Remove my pharmacy", "ফার্মেসি মুছে ফেলুন"), color = MaterialTheme.colorScheme.error)
                            }
                        }
                        if (s.status == "suspended" || s.status == "rejected") AppealButton("pharmacy")
                    }
                }
                if (s.status == "approved") {
                    PharmacyRxSection()
                    PharmacyFindRequests()
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
    var rating by remember { mutableStateOf<Pharmacy?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    var showArea by remember { mutableStateOf(false) }
    var showFilter by remember { mutableStateOf(false) }
    // রেটিংয়ের পর আবার আনা — কার্ডে নতুন গড়
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(district, upazilaId, openNow, reload) {
        val d = district ?: return@LaunchedEffect
        loading = true
        runCatching { Pharmacies.near(d, upazilaId, if (gps) profile.lat else null, if (gps) profile.lng else null, openNow) }
            .onSuccess { list = it; errorMsg = null }.onFailure { errorMsg = it.message }
        loading = false
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            FilterBar(
                areaLabel = areaText(district, upazilaId),
                onAreaClick = { showArea = true },
                filterCount = if (openNow) 1 else 0,
                onFilterClick = { showFilter = true },
                active = if (openNow) listOf(ActiveFilter(tr("Open now", "এখন খোলা")) { openNow = false }) else emptyList(),
            )
            ErrorText(errorMsg)
            InfoText(infoMsg)
        }
        when {
            loading -> item { LoadingBox() }
            list.isEmpty() -> item {
                EmptyState(
                    R.drawable.ic_storefront,
                    tr("No pharmacy in the app here yet", "এখানে এখনো অ্যাপে কোনো ফার্মেসি নেই"),
                    tr("Know a pharmacy? Ask them to join with + at the top.", "কোনো ফার্মেসি চিনলে উপরের + দিয়ে যোগ হতে বলুন।"),
                    Modifier.padding(top = 24.dp),
                )
            }
            else -> items(list, key = { it.id }) { p ->
                DirectoryCard(
                    title = p.name,
                    icon = R.drawable.ic_storefront,
                    status = if (p.openNow) CardStatus(tr("Open", "খোলা"), true) else CardStatus(tr("Closed", "বন্ধ"), false),
                    rating = p.rating,
                    ratingCount = p.ratingCount,
                    meta = p.km?.let { Rules.formatKm(it) },
                    details = listOf(
                        R.drawable.ic_location_on to p.address,
                        R.drawable.ic_schedule to if (p.closedUntil > 0) tr("Closed until ", "বন্ধ থাকবে ") + Rules.formatDate(p.closedUntil) + tr("", " পর্যন্ত")
                            else listOf(p.hoursText, closedDaysText(p.closedDays)).filter { it.isNotBlank() }.joinToString(" · "),
                        R.drawable.ic_payments to if (p.discount > 0) tr("${p.discount}% off MRP", "MRP থেকে ${num(p.discount)}% ছাড়") else "",
                        R.drawable.ic_local_shipping to if (p.delivery) tr("Home delivery", "বাসায় ডেলিভারি") + (if (p.deliveryNote.isNotBlank()) " · " + p.deliveryNote else "") else "",
                    ),
                    primary = CardAction(tr("Call", "ফোন করুন"), R.drawable.ic_call) {
                        scope.launch { runCatching { Pharmacies.called(p.id) } }
                        callPhone(context, p.phone)
                    },
                    secondary = if (p.canRate) CardAction(tr("Rate", "রেটিং দিন"), null) { rating = p } else null,
                    onReport = { reporting = p },
                    sections = listOf(
                        DetailSection(
                            tr("Address", "ঠিকানা"),
                            listOf(
                                R.drawable.ic_location_on to listOf(p.address, Areas.label(p.district, p.upazilaId)).filter { it.isNotBlank() }.joinToString(", "),
                            ),
                        ),
                        DetailSection(
                            tr("Open hours", "খোলার সময়"),
                            listOf(
                                R.drawable.ic_schedule to if (p.closedUntil > 0) tr("Closed until ", "বন্ধ থাকবে ") + Rules.formatDate(p.closedUntil) + tr("", " পর্যন্ত") else p.hoursText,
                                R.drawable.ic_calendar_month to closedDaysText(p.closedDays),
                            ),
                        ),
                        DetailSection(
                            tr("Discount and delivery", "ছাড় ও ডেলিভারি"),
                            listOf(
                                R.drawable.ic_payments to if (p.discount > 0) tr("${p.discount}% off MRP", "MRP থেকে ${num(p.discount)}% ছাড়") else tr("No discount", "ছাড় নেই"),
                                R.drawable.ic_local_shipping to if (p.delivery) tr("Home delivery", "বাসায় ডেলিভারি") + (if (p.deliveryNote.isNotBlank()) " · " + p.deliveryNote else "") else tr("No home delivery", "বাসায় ডেলিভারি নেই"),
                            ),
                        ),
                        // দোকানের সেবার দিকভিত্তিক রেটিং (কোনো রেটিং না থাকলে লাইন খালি — ভাগটা দেখায় না)
                        DetailSection(tr("Service rating", "সেবার রেটিং"), listOf(R.drawable.ic_star to aspectsText(p.aspects))),
                    ),
                )
            }
        }
    }
    if (showArea) {
        AreaSheet(district, upazilaId, null, { d, u, _ -> district = d; upazilaId = u }, onDismiss = { showArea = false })
    }
    if (showFilter) {
        FilterSheet(tr("Filter", "ফিল্টার"), onDismiss = { showFilter = false }, onClear = { openNow = false }) {
            FilterSwitch(tr("Open now only", "শুধু এখন খোলা"), openNow) { openNow = it }
        }
    }
    rating?.let { p ->
        PharmacyRatingDialog(
            title = tr("Rate ", "রেটিং: ") + p.name,
            aspects = p.rateAspects,
            onDismiss = { rating = null },
            onSubmit = { stars, comment ->
                Pharmacies.rate(p.id, stars, comment)
                rating = null
                infoMsg = tr("Thanks for the rating!", "রেটিং দেওয়ার জন্য ধন্যবাদ!")
                reload++
            },
        )
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

// ---------- রেটিং: দোকানের সেবার (ওষুধ কেমন কাজ করল তার নয়) ----------
/** দিকগুলো: ব্যবহার (সবসময়), ঠিক ওষুধ ও ন্যায্য দাম (কিনলে), ডেলিভারির দ্রুততা (অনলাইন অর্ডার পৌঁছালে) */
internal val RATE_ASPECTS = listOf("behaviour", "price", "delivery")

/** রেটিংয়ের জানালার পুরো নাম */
private fun aspectLabel(a: String): String = when (a) {
    "behaviour" -> tr("Behaviour", "ব্যবহার")
    "price" -> tr("Right medicine & fair price", "ঠিক ওষুধ ও ন্যায্য দাম")
    else -> tr("Delivery speed", "ডেলিভারির দ্রুততা")
}

/** বিস্তারিতের ছোট লাইন: "ব্যবহার ★৪.৬ · ডেলিভারি ★৪.২" */
private fun aspectsText(aspects: Map<String, Pair<Double, Int>>): String = RATE_ASPECTS.mapNotNull { a ->
    val v = aspects[a] ?: return@mapNotNull null
    val short = when (a) {
        "behaviour" -> tr("Behaviour", "ব্যবহার")
        "price" -> tr("Medicine & price", "ঠিক ওষুধ ও দাম")
        else -> tr("Delivery", "ডেলিভারি")
    }
    short + " ★" + num(String.format(java.util.Locale.US, "%.1f", v.first))
}.joinToString(" · ")

/** দিকে দিকে তারা আর মন্তব্য (অ্যাম্বুলেন্সের StarRatingDialog-এর মতো) — ব্যবহার না দিলে জমা হয় না */
@Composable
private fun PharmacyRatingDialog(title: String, aspects: List<String>, onDismiss: () -> Unit, onSubmit: suspend (Map<String, Int>, String) -> Unit) {
    val scope = rememberCoroutineScope()
    // ব্যবহার সবসময়; বাকিগুলো সার্ভার যা দিয়েছে
    val shown = listOf("behaviour") + RATE_ASPECTS.filter { it != "behaviour" && it in aspects }
    var stars by remember { mutableStateOf(mapOf<String, Int>()) }
    var comment by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = title,
        onDismiss = { if (!busy) onDismiss() },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    tr("About the shop's service — not how well the medicine worked", "দোকানের সেবা নিয়ে — ওষুধ কেমন কাজ করল তা নয়"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                shown.forEach { a ->
                    Column {
                        Text(aspectLabel(a), style = MaterialTheme.typography.labelLarge)
                        if (a == "price") {
                            Text(
                                tr("Gave what the prescription said, charged a fair price", "প্রেসক্রিপশনে যা লেখা তা-ই দিয়েছে, দাম ঠিক নিয়েছে"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row {
                            val cur = stars[a] ?: 0
                            (1..5).forEach { i ->
                                IconButton(onClick = { stars = stars + (a to i) }) {
                                    AppIcon(if (i <= cur) R.drawable.ic_star_fill else R.drawable.ic_star, aspectLabel(a) + " " + num(i), Modifier.size(28.dp), MaterialTheme.colorScheme.tertiary)
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it.take(300) },
                    label = { Text(tr("Comment (optional)", "মন্তব্য (ঐচ্ছিক)")) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                ErrorText(errorMsg)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && (stars["behaviour"] ?: 0) > 0, onClick = {
                scope.launch {
                    busy = true
                    runCatching { onSubmit(stars.filterKeys { it in shown }, comment.trim()) }.onFailure { errorMsg = it.message }
                    busy = false
                }
            }) { ButtonContent(busy, tr("Submit", "জমা দিন")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Later", "পরে")) } },
    )
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
    var closedDays by remember { mutableStateOf(existing?.closedDays?.toSet() ?: emptySet()) }
    var docs by remember { mutableStateOf(mapOf<String, String>()) }
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
            OutlinedTextField(name, { name = it.take(100) }, label = { Text(tr("Pharmacy name", "ফার্মেসির নাম")) }, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words), singleLine = true, modifier = Modifier.fillMaxWidth())
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
                SmallNote(if (hadLocation) tr("Update only if the shop moved.", "দোকান সরালে তবেই আবার দিন।") else tr("Stand inside the shop and tap the button.", "দোকানের ভেতরে দাঁড়িয়ে বাটন চাপুন।"))
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
            Text(tr("Weekly closed days (if any)", "সাপ্তাহিক বন্ধ (থাকলে)"), style = MaterialTheme.typography.labelLarge)
            ChipRows(Weekdays.ORDER.map { it.toString() }, closedDays.map { it.toString() }.toSet(), perRow = 4, label = { Weekdays.short(it.toInt()) }) {
                val d = it.toInt()
                closedDays = if (d in closedDays) closedDays - d else if (closedDays.size < 6) closedDays + d else closedDays
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
            DocPhotosField(VerifyDocs.pharmacy(), docs, { docs = it },
                if (existing != null) tr("Add new photos only if the papers changed.", "কাগজ বদলালে তবেই নতুন ছবি দিন।") else null)
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
                        existing == null && VerifyDocs.missing(VerifyDocs.pharmacy(), docs) != null -> VerifyDocs.missing(VerifyDocs.pharmacy(), docs)
                        !agreed -> tr("Please accept the terms", "শর্তে সম্মতি দিন")
                        else -> null
                    }
                    if (errorMsg != null || d == null) return@Button
                    scope.launch {
                        busy = true
                        runCatching {
                            Pharmacies.save(
                                TitleCase.name(name), licenseNo.trim(), phone, d, upazilaId, address.trim(), lat, lng,
                                open24, openAt, closeAt, delivery, deliveryNote.trim(), discount.toIntOrNull() ?: 0,
                                closedDays.sorted(), docs,
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

