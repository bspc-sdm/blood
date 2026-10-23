@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bspc.blood

import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.clickable
import android.content.Context
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
import androidx.compose.ui.platform.testTag
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
    /** ছাড়ের শর্ত (২.৩২.০): কত টাকার বেশি কিনলে ছাড়, কত টাকার বেশিতে ডেলিভারি ফ্রি, ছোট নোট — ০/খালি = নেই */
    val discountMin: Int = 0,
    val freeDeliveryOver: Int = 0,
    val discountNote: String = "",
    /** অনলাইনে অর্ডার নেয় কি না আর ডেলিভারি চার্জ (দোকানের লেখা) */
    val online: Boolean = false,
    val deliveryFee: String = "",
    /** প্রেসক্রিপশন বাছা থাকলে (তালিকায় rxId): এই দোকানে কয়টা ওষুধ আছে, আনুমানিক দাম, মোট ছাড় */
    val rx: PharmacyRx? = null,
    /** তালিকার নতুন তথ্য (Listings.kt) */
    val extras: ListingExtras = ListingExtras(),
    /** মালিক "এখন খোলা/বন্ধ" হাতে দিলে (null = সময়মতো) আর কখন পর্যন্ত */
    val openOverride: Boolean? = null,
    val overrideUntil: Long = 0,
) {
    val hoursText: String
        get() = if (open24) tr("Open 24 hours", "২৪ ঘণ্টা খোলা")
        else if (openAt.isNotBlank()) Weekdays.time(openAt) + " – " + Weekdays.time(closeAt) else ""

    /** কার্ড আর বিস্তারিতে ছোট "২৪ ঘণ্টা" ট্যাগ */
    val tags24: List<String>
        get() = if (open24) listOf(tr("24 hours", "২৪ ঘণ্টা")) else emptyList()
}

/** payable/mrp/discount: পুরো টাকা — দাম না জানলে null; belowMin: "৳৫০০-এর বেশি কিনলে" ছাড়, এই প্রেসক্রিপশনে কম — ছাড় ধরা নয় */
data class PharmacyRx(
    val keepsStock: Boolean, val have: Int, val total: Int, val missing: List<String>, val priced: Int,
    val payable: Int?, val mrp: Int?, val discount: Int?, val belowMin: Boolean,
)

/** তালিকা আর (প্রেসক্রিপশন বাছা থাকলে) আগের অনলাইন অর্ডারের ঠিকানা-ফোন */
data class PharmacyList(val items: List<Pharmacy>, val lastHome: LastHome?)

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
        o.optInt("discountMin"), o.optInt("freeDeliveryOver"), o.str("discountNote"),
        o.optBoolean("online"), o.str("deliveryFee"),
        o.optJSONObject("rx")?.let { r ->
            fun intN(k: String): Int? = if (r.isNull(k) || !r.has(k)) null else r.optInt(k)
            PharmacyRx(
                r.optBoolean("keepsStock"), r.optInt("have"), r.optInt("total"), r.optJSONArray("missing").strings(), r.optInt("priced"),
                intN("payable"), intN("mrp"), intN("discount"), r.optBoolean("belowMin"),
            )
        },
        extras = ListingExtras.parse(o),
        openOverride = ListingExtras.override(o).first,
        overrideUntil = ListingExtras.override(o).second,
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
        discountMin: Int = 0, freeDeliveryOver: Int = 0, discountNote: String = "",
    ): Boolean = call(
        "pharmacy_save",
        JSONObject().put("name", name).put("licenseNo", licenseNo).put("phone", phone).put("district", district)
            .put("upazilaId", upazilaId ?: JSONObject.NULL).put("address", address).put("lat", lat ?: JSONObject.NULL)
            .put("lng", lng ?: JSONObject.NULL).put("open24", open24).put("openAt", openAt).put("closeAt", closeAt)
            .put("delivery", delivery).put("deliveryNote", deliveryNote).put("discount", discount)
            .put("discountMin", discountMin).put("freeDeliveryOver", freeDeliveryOver).put("discountNote", discountNote)
            .put("closedDays", JSONArray(closedDays)).put("termsAccepted", true).put("docs", VerifyDocs.json(docs)),
    ).optBoolean("pending")

    /**
     * open24: শুধু "২৪ ঘণ্টা খোলা" দোকান (রাতে ওষুধ দরকার হলে)।
     * mode (২.৩২.০): "shop" সব দোকান, "online" শুধু অনলাইনে অর্ডার নেওয়া আর ডেলিভারির দূরত্বে; rxId — প্রতিটা দোকানে
     * প্রেসক্রিপশনের হিসাব (কেয়ারগিভার মোডে রোগীর হয়ে: asUid)
     */
    suspend fun near(
        district: String, upazilaId: Int?, lat: Double?, lng: Double?, openNow: Boolean, open24: Boolean = false,
        mode: String? = null, rxId: String? = null, sort: ListingSort? = null,
    ): PharmacyList {
        val o = call(
            "pharmacies",
            JSONObject().put("district", district).put("upazilaId", upazilaId ?: JSONObject.NULL)
                .put("lat", lat ?: JSONObject.NULL).put("lng", lng ?: JSONObject.NULL).put("openNow", openNow).put("open24", open24).apply {
                    if (mode != null) put("mode", mode)
                    if (sort != null) put("sort", sort.wire)
                    if (rxId != null) {
                        put("rxId", rxId)
                        CareMode.asUid.value?.let { put("asUid", it) }
                    }
                },
        )
        val a = o.optJSONArray("items") ?: JSONArray()
        val last = o.optJSONObject("lastHome")?.let { LastHome(it.str("address"), it.str("phone")) }?.takeIf { it.address.isNotBlank() }
        return PharmacyList((0 until a.length()).map { pharmacy(a.getJSONObject(it)) }, last)
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

/** "সেবা" ট্যাবের ফার্মেসি অংশ: খোলা দোকান খোঁজা / ওষুধ খোঁজা / আমার প্রেসক্রিপশন (আলাদা পাতা) / নিজের ফার্মেসি */
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
                // মালিক: "আমার প্রেসক্রিপশন" ফার্মেসির ভেতরে আর প্রোফাইলে (স্বাস্থ্যে নয়) — প্রোফাইলের একই পাতা খোলে
                PillTab(tr("My prescriptions", "আমার প্রেসক্রিপশন"), R.drawable.ic_list_alt),
            ),
            { if (it == 2) RxNav.open.value = "mine" else tab = it },
            secondary = true,
        )
        // নিজের ফার্মেসি: "সেবা" পাতার নিচের ভাসমান বাটন / উপরের + থেকে (ServicesTab)
        when (tab) {
            0 -> NearbyPharmacies(profile)
            else -> MedFindPanel(profile)
        }
    }
}

/**
 * নিজের ফার্মেসি: না থাকলে যোগ করার আমন্ত্রণ। অনুমোদিত হলে দুই ট্যাব (২.২৭.০ — MyWorkTabs.kt):
 * "অর্ডার" (শুরুতে; কোড দিয়ে খোলা, নতুন · চলছে/তৈরি · শেষ) আর "দোকান" (তথ্য, QR, বন্ধ রাখা, বিক্রি ও স্টক,
 * ওষুধ খোঁজার অনুরোধ, অ্যাপ থেকে ফোন, একদম নিচে মুছে ফেলা)। অনুমোদনের আগে শুধু দোকানের অংশ।
 */
@Composable
internal fun MyPharmacyPanel(profile: Profile) {
    var shop by remember { mutableStateOf<Pharmacy?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    var stats by remember { mutableStateOf<JSONObject?>(null) }
    var home by remember { mutableStateOf<PharmacyHome?>(null) }
    var orders by remember { mutableStateOf<SegList<PharmOrder>?>(null) }
    var ordersErr by remember { mutableStateOf<String?>(null) }
    var pausing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    // ২.৩০.০: তিন ট্যাব — অর্ডার (০), ওষুধ খোঁজা (১), দোকান (২; ভেতরে তথ্য/বিক্রি/স্টক/এনালাইটিক্স)
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var shopSub by rememberSaveable { mutableIntStateOf(0) }
    var seg by rememberSaveable { mutableStateOf("new") }
    var unanswered by remember { mutableIntStateOf(0) }
    // রসিদ (inv_pharmacy) — বন্ধ থাকলে "দোকান"-এর ভাগ আগের মতো চারটা
    val invPharmacy = invOn(InvoiceFeatures.key("pharmacy"))
    val scope = rememberCoroutineScope()
    // নোটিফিকেশন: ওষুধ খোঁজার অনুরোধ → "ওষুধ খোঁজা"; দোকান → "দোকান › তথ্য"; বাকি → "অর্ডার" › নতুন
    ConsumeWorkTarget { t ->
        when (t) {
            "finds" -> tab = 1
            "shop" -> { tab = 2; shopSub = 0 }
            // "দিনের শেষ" রসিদের খবর → দোকান › রসিদ
            "receipts" -> if (Features.on(InvoiceFeatures.key("pharmacy"))) { tab = 2; shopSub = 4 } else { tab = 2; shopSub = 0 }
            else -> { tab = 0; seg = "new" }
        }
    }
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
        if (shop?.status == "approved") {
            runCatching { RxNet.pharmacyOrders() }.onSuccess { orders = it; ordersErr = null }.onFailure { ordersErr = it.message }
            runCatching { RxNet.pharmacyHome() }.onSuccess { home = it }
            // "ওষুধ খোঁজা" ট্যাবের সংখ্যা: উত্তর না দেওয়া খোলা অনুরোধ (বন্ধ থাকলে ০)
            runCatching { MedFind.requests() }.onSuccess { (l, off) -> unanswered = if (off) 0 else l.count { it.answer == null } }
        }
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
                    act(tr("Removed", "মুছে ফেলা হয়েছে")) { Pharmacies.delete(); orders = null; home = null; tab = 0 }
                }) { Text(tr("Remove", "মুছে ফেলুন"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("Cancel", "বাতিল")) } },
        )
    }
    val s = shop
    when {
        !loaded -> Column(Modifier.fillMaxSize().padding(16.dp)) { LoadingBox() }
        s == null -> Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ErrorText(errorMsg)
            InfoText(infoMsg)
            if (errorMsg == null) {
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
        }
        s.status != "approved" -> Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ErrorText(errorMsg)
            InfoText(infoMsg)
            ShopTab(s, null, stats, busy, { editing = true }, { pausing = true }, { act(tr("Opened again", "আবার খোলা হয়েছে")) { Pharmacies.pause(null) } }, { confirmDelete = true }, { reload++ }) { m -> infoMsg = m; reload++ }
        }
        else -> WorkTabsScaffold(
            tab,
            listOf(
                PillTab(tr("Orders", "অর্ডার"), R.drawable.ic_medication, count = orders?.counts?.get("new") ?: 0),
                PillTab(tr("Medicine search", "ওষুধ খোঁজা"), R.drawable.ic_search, count = unanswered),
                PillTab(tr("Shop", "দোকান"), R.drawable.ic_storefront),
            ),
            { tab = it },
        ) {
            when (tab) {
                0 -> {
                    // রোগীরা যেভাবে দেখেন + এক চাপের সুইচ (খোলা/বন্ধ এখন, ডেলিভারি), তারপর অর্ডার
                    PharmacyProviderTop(s) { reload++ }
                    PharmacyOrdersTab(orders, ordersErr, seg, { seg = it }) { reload++ }
                }
                1 -> Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("pharm_finds")) {
                    PharmacyFindRequests { reload++ }
                }
                else -> Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // দোকানের ভেতরের ভাগ: তথ্য · বিক্রি · স্টক · এনালাইটিক্স (রোজকার দ্রুত কাজ অ্যাপে, ভারী হিসাব ওয়েবে)
                    PillTabs(
                        shopSub,
                        listOf(
                            PillTab(tr("Info", "তথ্য"), R.drawable.ic_storefront),
                            PillTab(tr("Sales", "বিক্রি"), R.drawable.ic_payments),
                            PillTab(tr("Stock", "স্টক"), R.drawable.ic_list_alt),
                            PillTab(tr("Analytics", "এনালাইটিক্স"), R.drawable.ic_history),
                        ) + (if (invPharmacy) listOf(PillTab(tr("Receipts", "রসিদ"), R.drawable.ic_list_alt)) else emptyList()),
                        { shopSub = it },
                        secondary = true,
                    )
                    ErrorText(errorMsg)
                    InfoText(infoMsg)
                    when (shopSub) {
                        1 -> PharmacySalesLite()
                        2 -> PharmacyStockLite()
                        3 -> PharmacyAnalyticsLite()
                        4 -> if (invPharmacy) BizInvoicesPanel("pharmacy", s.id) else ShopTab(s, home, stats, busy, { editing = true }, { pausing = true }, { act(tr("Opened again", "আবার খোলা হয়েছে")) { Pharmacies.pause(null) } }, { confirmDelete = true }, { reload++ }) { m -> infoMsg = m; reload++ }
                        else -> ShopTab(s, home, stats, busy, { editing = true }, { pausing = true }, { act(tr("Opened again", "আবার খোলা হয়েছে")) { Pharmacies.pause(null) } }, { confirmDelete = true }, { reload++ }) { m -> infoMsg = m; reload++ }
                    }
                }
            }
        }
    }
}

/**
 * "দোকান › তথ্য": তথ্য → QR ও কোড, পোস্টার → কয়েক দিন বন্ধ → অনলাইনে বিক্রি, চেয়েও পাননি → ফোনের হিসাব →
 * (আলাদা করে, লাল) মুছে ফেলা। স্টক/বিক্রি/হিসাব আলাদা ভাগে (PharmacyShop.kt), ওষুধ খোঁজা আলাদা ট্যাবে (২.৩০.০)।
 */
@Composable
private fun ShopTab(
    s: Pharmacy,
    home: PharmacyHome?,
    stats: JSONObject?,
    busy: Boolean,
    onEdit: () -> Unit,
    onPause: () -> Unit,
    onReopen: () -> Unit,
    onDelete: () -> Unit,
    onDocs: () -> Unit,
    onChanged: (String?) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(s.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(statusText(s.status), style = MaterialTheme.typography.labelLarge, color = cs.primary)
            StatusReasonLine(s.status, s.statusReason)
            DocsNeededNotice("pharmacy", s.id, VerifyDocs.pharmacy(), s.docsMissing, s.docsRequested, s.docsNote, onDocs)
            if (s.hoursText.isNotBlank()) IconLine(R.drawable.ic_schedule, listOf(s.hoursText, closedDaysText(s.closedDays)).filter { it.isNotBlank() }.joinToString(" · "))
            IconLine(R.drawable.ic_call, num(s.phone))
            if (s.status == "pending") SmallNote(tr("You'll appear in the list after approval.", "অনুমোদনের পর তালিকায় দেখাবে।"))
            OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) { ButtonContent(false, tr("Edit details", "তথ্য বদলান"), R.drawable.ic_edit) }
            if (s.status == "suspended" || s.status == "rejected") AppealButton("pharmacy")
        }
    }
    home?.let { PharmacyQrCard(it) }
    // ঈদ/সংস্কারে কয়েক দিন বন্ধ: তালিকায় "বন্ধ" দেখাবে, তারিখ পার হলে নিজে খুলবে
    if (s.status != "suspended") {
        Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (s.closedUntil > 0) {
                    IconLine(R.drawable.ic_info, tr("Closed until ", "বন্ধ থাকবে ") + Rules.formatDate(s.closedUntil) + tr("", " পর্যন্ত"))
                    SmallAction(tr("Open again", "আবার খুলুন"), R.drawable.ic_storefront, onReopen, busy = busy)
                } else {
                    SmallAction(tr("Closed for some days", "কয়েক দিন বন্ধ থাকবে"), R.drawable.ic_schedule, onPause, enabled = !busy)
                    SmallNote(tr("For Eid or repairs: the list shows \"closed\" until then, and it opens again by itself.", "ঈদ বা সংস্কারে: ততদিন তালিকায় \"বন্ধ\" দেখাবে, তারিখ পার হলে নিজে খুলবে।"))
                }
            }
        }
    }
    if (s.status == "approved") {
        home?.let { PharmacyToolsCard(it, onChanged) }
    }
    stats?.let { st ->
        Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
    if (s.status != "suspended") DangerZone(tr("Remove my pharmacy", "ফার্মেসি মুছে ফেলুন"), !busy, onDelete)
}

/** কীভাবে কিনবেন — "shop" (দোকানে গিয়ে) / "online" (বাসায় ডেলিভারি); শেষ বাছাই এই ফোনে মনে থাকে (২.৩২.০) */
object BuyModePref {
    private const val FILE = "pharmacy_prefs"
    fun get(context: Context): String? =
        runCatching { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("buyMode", null) }.getOrNull()?.takeIf { it == "shop" || it == "online" }
    fun set(context: Context, mode: String) {
        runCatching { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("buyMode", mode).apply() }
    }
}

/** ফার্মেসির তালিকার লেখা (২.৩২.০) — ওয়েবের services/data.ts-এর একই */
object PharmacyText {
    private fun tk(v: Int) = LabPriceText.taka(v)
    /** "MRP থেকে ৫% ছাড়" (দোকানের সাধারণ ছাড়) */
    fun flatDiscount(p: Pharmacy) = if (p.discount > 0) tr("${p.discount}% off MRP", "MRP থেকে ${num(p.discount)}% ছাড়") else ""
    /** "৳৫০০-এর বেশি কিনলে ছাড় · শুধু নগদে" */
    fun terms(p: Pharmacy) = listOf(
        if (p.discountMin > 0) tr("discount on purchases over ${tk(p.discountMin)}", "${tk(p.discountMin)}-এর বেশি কিনলে ছাড়") else "",
        p.discountNote,
    ).filter { it.isNotBlank() }.joinToString(" · ")
    /** অনলাইনে: "ডেলিভারি ৩০ টাকা, ৳১,০০০-এর বেশিতে ফ্রি"; দোকানের নিজের ডেলিভারি থাকলে আগের লেখা */
    fun delivery(p: Pharmacy, online: Boolean): String {
        val free = if (p.freeDeliveryOver > 0) tr("free over ${tk(p.freeDeliveryOver)}", "${tk(p.freeDeliveryOver)}-এর বেশিতে ফ্রি") else ""
        if (online || (p.online && !p.delivery)) {
            if (!p.online) return ""
            return listOf(tr("Delivery", "ডেলিভারি") + (if (p.deliveryFee.isNotBlank()) " " + p.deliveryFee else ""), free).filter { it.isNotBlank() }.joinToString(", ")
        }
        if (!p.delivery) return ""
        return listOf(tr("Home delivery", "বাসায় ডেলিভারি") + (if (p.deliveryNote.isNotBlank()) " · " + p.deliveryNote else ""), free).filter { it.isNotBlank() }.joinToString(", ")
    }
    /** "আপনার প্রেসক্রিপশনের ৮০% ওষুধ আছে (৪/৫)" / "স্টকের তালিকা নেই" */
    fun have(r: PharmacyRx): String = when {
        r.total == 0 -> tr("All medicines on this prescription are bought", "এই প্রেসক্রিপশনের সব ওষুধ কেনা হয়ে গেছে")
        !r.keepsStock -> tr("No stock list", "স্টকের তালিকা নেই")
        else -> {
            val pct = Math.round(r.have * 100.0 / r.total).toInt()
            tr("Has $pct% of your prescription's medicines (${r.have}/${r.total})", "আপনার প্রেসক্রিপশনের ${num(pct)}% ওষুধ আছে (${num(r.have)}/${num(r.total)})")
        }
    }
    /** "এই প্রেসক্রিপশনে মোট ছাড় ≈ ৳৬৫"; দাম না জানলে দোকানের "MRP থেকে ৫% ছাড়" */
    fun discount(p: Pharmacy, r: PharmacyRx): String = when {
        r.belowMin && p.discountMin > 0 -> tr("No discount on this one — only over ${tk(p.discountMin)}", "এই প্রেসক্রিপশনে ছাড় নেই — ${tk(p.discountMin)}-এর বেশি কিনলে")
        (r.discount ?: 0) > 0 -> tr("Total discount on this prescription ≈ ${tk(r.discount!!)}", "এই প্রেসক্রিপশনে মোট ছাড় ≈ ${tk(r.discount!!)}")
        else -> flatDiscount(p)
    }
    /** "আনুমানিক ৳৪৮০" (কয়টার দাম জানা) / "দাম দোকানে জেনে নিন" */
    fun payable(r: PharmacyRx): String {
        val v = r.payable ?: return if (r.total > 0) tr("Ask the shop for the price", "দাম দোকানে জেনে নিন") else ""
        val part = if (r.priced < r.total) tr(" (price known for ${r.priced} of ${r.total})", " (${num(r.total)}টার মধ্যে ${num(r.priced)}টার দাম জানা)") else ""
        return tr("About ${tk(v)} to pay", "আনুমানিক ${tk(v)}") + part
    }
}

/**
 * সবার ফার্মেসির তালিকা (একই ছাঁচ, Listings.kt): তালিকা সাথে সাথে দেখায়; "দোকানে গিয়ে / অনলাইন অর্ডার" এক জোড়া চিপ
 * (তালিকা আটকায় না); প্রেসক্রিপশন বাছা "প্রেসক্রিপশন পাঠান"-এর ভেতরে (RxSendFlow)।
 */
@Composable
private fun NearbyPharmacies(profile: Profile) {
    val context = LocalContext.current
    remember { Areas.ensure(context) }
    var district by remember { mutableStateOf(profile.placeName) }
    var upazilaId by remember { mutableStateOf<Int?>(null) }
    var openNow by remember { mutableStateOf(false) }
    var open24 by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val gps = profile.locationSource == "gps" && profile.lat != null && profile.lng != null
    var sort by remember { mutableStateOf(Listing.defaultSort(gps)) }
    var list by remember { mutableStateOf<List<Pharmacy>>(emptyList()) }
    var lastHome by remember { mutableStateOf<LastHome?>(null) }
    var loading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var reporting by remember { mutableStateOf<Pharmacy?>(null) }
    var rating by remember { mutableStateOf<Pharmacy?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    var showArea by remember { mutableStateOf(false) }
    // রেটিংয়ের পর আবার আনা — কার্ডে নতুন গড়
    var reload by remember { mutableIntStateOf(0) }
    var openShop by remember { mutableStateOf<Pharmacy?>(null) }
    var sendTo by remember { mutableStateOf<Pharmacy?>(null) }
    var sent by remember { mutableStateOf<RxSent?>(null) }
    // দোকানে গিয়ে ("shop", শুরুতে) / অনলাইন অর্ডার ("online") — শেষবারেরটা মনে থাকে
    var buyMode by remember { mutableStateOf(BuyModePref.get(context) ?: "shop") }

    LaunchedEffect(district, upazilaId, openNow, open24, reload, buyMode, sort) {
        val d = district ?: return@LaunchedEffect
        loading = true
        runCatching { Pharmacies.near(d, upazilaId, if (gps) profile.lat else null, if (gps) profile.lng else null, openNow, open24, buyMode, null, sort) }
            .onSuccess { list = it.items; lastHome = it.lastHome; errorMsg = null }.onFailure { errorMsg = it.message }
        loading = false
    }
    val phone: (Pharmacy) -> Unit = { p ->
        scope.launch { runCatching { Pharmacies.called(p.id) } }
        callPhone(context, p.phone)
    }
    val send: (Pharmacy) -> Unit = { p -> rxSendGate(close = { openShop = null }) { sendTo = p } }
    val shown = Listing.sorted(
        list.filter { Listing.matches(query, it.name, it.address, Areas.label(it.district, it.upazilaId)) },
        sort, { it.extras.distanceKm ?: it.km }, { pharmacyRating(it) }, { pharmacyOpen(it) },
    )
    fun setMode(m: String) { buyMode = m; BuyModePref.set(context, m) }
    val filtered = openNow || open24 || buyMode == "online" || query.isNotBlank()
    val clear = { openNow = false; open24 = false; query = ""; setMode("shop") }

    ListingList(
        controls = {
            ListingControls(
                ListingType.PHARMACY, query, { query = it }, areaText(district, upazilaId), { showArea = true },
                sort, listOf(ListingSort.NEAR, ListingSort.RATING, ListingSort.OPEN), { sort = it },
                chips = listOf(
                    ListingChip(tr("At the shop", "দোকানে গিয়ে"), buyMode == "shop", { setMode("shop") }, "ph-mode-shop"),
                    ListingChip(tr("Order online", "অনলাইন অর্ডার"), buyMode == "online", { setMode(if (buyMode == "online") "shop" else "online") }, "ph-mode-online"),
                    ListingChip(tr("Open now", "এখন খোলা"), openNow, { openNow = !openNow }, "chip_open_now"),
                    ListingChip(ListingText.hours24(), open24, { open24 = !open24 }, "chip_24h"),
                ),
                onClear = if (filtered) clear else null,
            )
        },
        loading = loading,
        items = shown.map { p -> pharmacyCard(p, { openShop = p }, { send(p) }, { phone(p) }, { reporting = p }) },
        empty = {
            when {
                query.isNotBlank() || openNow || open24 -> ListingEmpty(ListingText.noMatch(), ListingText.clear(), clear)
                buyMode == "online" -> ListingEmpty(
                    tr("No pharmacy delivers to your area yet", "আপনার এলাকায় এখনো কোনো ফার্মেসি অনলাইনে ডেলিভারি দেয় না"),
                    tr("At the shop", "দোকানে গিয়ে"), { setMode("shop") }, R.drawable.ic_local_shipping,
                )
                else -> ListingEmpty(ListingText.emptyLine(ListingType.PHARMACY), ListingText.changeArea(), { showArea = true }, R.drawable.ic_storefront)
            }
        },
        messages = {
            ErrorText(errorMsg)
            InfoText(infoMsg)
        },
    )
    openShop?.let { p ->
        ListingDetail(
            pharmacyDetail(
                p, buyMode == "online", onSend = { send(p) }, onPhone = { phone(p) },
                onRate = if (p.canRate) ({ openShop = null; rating = p }) else null,
                onReport = { openShop = null; reporting = p },
            ),
            onDismiss = { openShop = null },
        )
    }
    // বিস্তারিতের ওপরে (পরে আঁকা ডায়ালগ ওপরে থাকে) — উপায় উপরের চিপ থেকে, প্রেসক্রিপশন এখানে বাছা
    sendTo?.let { p ->
        RxSendFlow(lab = null, pharmacy = p, profile = profile, onDismiss = { sendTo = null }, mode = buyMode, presetRx = null, last = lastHome) {
            sendTo = null
            sent = it
        }
    }
    // "প্রেসক্রিপশন খুলুন": ডায়ালগ আর বিস্তারিত বন্ধ, তারপর সেই প্রেসক্রিপশন (সেখানে অবস্থা আর "ফিরিয়ে নিন")
    sent?.let { s -> RxSentDialog(s, onOpen = { sent = null; openShop = null; RxNav.openRx(s.rxId) }, onDismiss = { sent = null }) }
    if (showArea) {
        AreaSheet(district, upazilaId, null, { d, u, _ -> district = d; upazilaId = u }, onDismiss = { showArea = false })
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
internal fun pharmacyAspectsText(aspects: Map<String, Pair<Double, Int>>): String = RATE_ASPECTS.mapNotNull { a ->
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
    // ছাড়ের শর্ত (২.৩২.০, ঐচ্ছিক) — ফার্মেসির তালিকার কার্ডে দেখায়
    var discountMin by remember { mutableStateOf(existing?.discountMin?.takeIf { it > 0 }?.toString() ?: "") }
    var freeOver by remember { mutableStateOf(existing?.freeDeliveryOver?.takeIf { it > 0 }?.toString() ?: "") }
    var discountNote by remember { mutableStateOf(existing?.discountNote ?: "") }
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
            OutlinedTextField(
                discountMin, { discountMin = it.filter(Char::isDigit).take(6) },
                label = { Text(tr("Discount only above (Tk, optional)", "ছাড় কত টাকার বেশি কিনলে (৳, ঐচ্ছিক)")) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                freeOver, { freeOver = it.filter(Char::isDigit).take(6) },
                label = { Text(tr("Free delivery above (Tk, optional)", "কত টাকার বেশিতে ডেলিভারি ফ্রি (৳, ঐচ্ছিক)")) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                discountNote, { discountNote = it.replace('\n', ' ').take(80) },
                label = { Text(tr("Discount conditions (optional)", "ছাড়ের শর্ত (ঐচ্ছিক)")) },
                supportingText = { Text(tr("e.g. cash only — shown on your card in the pharmacy list", "যেমন: শুধু নগদে — ফার্মেসির তালিকায় আপনার কার্ডে দেখাবে")) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
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
                                (discountMin.toIntOrNull() ?: 0).coerceIn(0, 100000), (freeOver.toIntOrNull() ?: 0).coerceIn(0, 100000), discountNote.trim(),
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

