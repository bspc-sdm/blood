@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bspc.blood

import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class Ambulance(
    val id: String,
    val name: String,
    val phone: String,
    val types: List<String>,
    val district: String,
    val upazilaId: Int?,
    val area: String,
    val fare: String,
    val available: Boolean,
    val rating: Double,
    val ratingCount: Int,
    /** শুধু নিজের অ্যাম্বুলেন্সে */
    val vehicleNo: String = "",
    val status: String = "",
    /** শেষবার "খালি আছি" জানানোর সময় (০ = নিজে "ব্যস্ত" করেছেন); ১২ ঘণ্টা পর নিজে থেকে "জানা নেই" */
    val availableAt: Long = 0,
    /** রেটিং দেওয়া যায় (অ্যাপ থেকে ফোনের ১ ঘণ্টা পর; সার্ভার মনে রাখে) */
    val canRate: Boolean = false,
    /** অ্যাডমিন বাতিল/বন্ধ করলে কারণ (শুধু নিজের) */
    val statusReason: String = "",
    /** ট্রিপে আছে — কখন থেকে */
    val busySince: Long = 0,
    /** নিজের: দরকারি কাগজের ছবি বাকি / অ্যাডমিন ছবি চেয়েছেন */
    val docsMissing: Boolean = false,
    val docsRequested: Boolean = false,
    val docsNote: String = "",
    /** দূরত্ব (পুরো কিমি, ০ = ১ কিমির কম) — শুধু আসল GPS পাঠালে (gps: true); kmApprox: গাড়ির জায়গা দেওয়া নেই, জেলার কেন্দ্র ধরে */
    val km: Double? = null,
    val kmApprox: Boolean = false,
    /** খালি থাকলে কখন পর্যন্ত (১২ ঘণ্টা; চালকের কার্ডে "আর ৪৫ মিনিট") */
    val availableUntil: Long = 0,
    /** নিজের: গাড়ির জায়গা (GPS) দেওয়া আছে */
    val hasBase: Boolean = false,
    /** ভাড়ার তালিকা (২.৩০.০, AmbFare.kt) — প্রকাশ্য; আগের লেখা fare নোট হিসেবে থাকে */
    val fareChart: FareChart? = null,
    /** তালিকার নতুন তথ্য (Listings.kt): ratingSummary, distanceKm … */
    val extras: ListingExtras = ListingExtras(),
)

/** তালিকা: খালি/অজানা, ট্রিপে, আর "সবচেয়ে কাছের খালি" গাড়ির id (না থাকলে null) */
data class AmbulanceList(val items: List<Ambulance>, val busy: List<Ambulance>, val nearestId: String?)

/**
 * পুরো কিমি ("৩ কিমি"), ১-এর কম হলে "১ কিমির কম" — চালকের জায়গা ~১ কিমির ঘরে কাটা, তাই দশমিক নয়;
 * জায়গা দেওয়া না থাকলে "প্রায় ২ কিমি"
 */
fun ambulanceKm(a: Ambulance): String? {
    val km = a.km ?: return null
    if (a.kmApprox) {
        val n = Math.round(km).coerceAtLeast(1L)
        return tr("about $n km", "প্রায় ${num(n)} কিমি")
    }
    if (km < 1) return tr("< 1 km", "১ কিমির কম")
    return num(Math.round(km)) + tr(" km", " কিমি")
}

object AmbulanceTypes {
    private val BUILT_IN = listOf("general", "ac", "icu", "freezer")
    val ALL: List<String> get() = Catalog.visible("ambulanceType") ?: BUILT_IN

    fun label(t: String): String = Catalog.label("ambulanceType", t) ?: builtInLabel(t)

    private fun builtInLabel(t: String): String = when (t) {
        "general" -> tr("General", "সাধারণ")
        "ac" -> tr("AC", "এসি")
        "icu" -> tr("ICU", "আইসিইউ")
        else -> tr("Freezer van", "ফ্রিজার ভ্যান")
    }
}

object Ambulances {
    private suspend fun call(action: String, body: JSONObject = JSONObject()): JSONObject =
        ServerApi.post("/api/community", body.put("action", action))

    private fun parse(o: JSONObject) = Ambulance(
        o.str("id"), o.str("name"), o.str("phone"), o.optJSONArray("types").strings(), o.str("district"),
        o.intOrNull("upazilaId"), o.str("area"), o.str("fare"), o.optBoolean("available", true),
        o.optDouble("rating", 0.0), o.optInt("ratingCount"), o.str("vehicleNo"), o.str("status"),
        o.optLong("availableAt"), o.optBoolean("canRate"), o.str("statusReason"), o.optLong("busySince"),
        o.optBoolean("docsMissing"), o.optBoolean("docsRequested"), o.str("docsRequestNote"),
        km = if (o.has("km") && !o.isNull("km")) o.optDouble("km") else null,
        kmApprox = o.optBoolean("kmApprox"),
        availableUntil = o.optLong("availableUntil"),
        hasBase = o.optBoolean("hasBase"),
        fareChart = FareChart.parse(o.optJSONObject("fareChart")),
        extras = ListingExtras.parse(o),
    )

    private fun list(o: JSONObject): List<Ambulance> {
        val a = o.optJSONArray("items") ?: JSONArray()
        return (0 until a.length()).map { parse(a.getJSONObject(it)) }
    }

    /**
     * খালি/অজানা গাড়ির তালিকা আর ট্রিপে থাকা গাড়ির আলাদা তালিকা।
     * lat/lng (শুধু ফোনের আসল GPS — সাথে gps: true) দিলে সার্ভার কাছের খালি গাড়ি আগে সাজায়, প্রতিটায় দূরত্ব দেয়,
     * আর "সবচেয়ে কাছের" গাড়ি বেছে দেয়। GPS না থাকলে ("এলাকা ধরে") কিছুই পাঠানো হয় না — আগের মতো নিজের উপজেলা আগে।
     */
    suspend fun near(district: String, upazilaId: Int?, type: String?, lat: Double? = null, lng: Double? = null, sort: ListingSort? = null): AmbulanceList {
        val o = call(
            "ambulances",
            JSONObject().put("district", district).put("upazilaId", upazilaId ?: JSONObject.NULL).apply {
                if (type != null) put("type", type)
                if (sort != null) put("sort", sort.wire)
                if (lat != null && lng != null) put("lat", lat).put("lng", lng).put("gps", true)
            },
        )
        val busy = o.optJSONArray("busy") ?: JSONArray()
        return AmbulanceList(list(o), (0 until busy.length()).map { parse(busy.getJSONObject(it)) }, o.str("nearestId").ifBlank { null })
    }

    suspend fun mine(): List<Ambulance> = list(call("ambulance_mine"))

    /** সেভের ফল: আবার যাচাই লাগবে কি না; গাড়ির জায়গা জেলার বাইরে হওয়ায় বাদ গেছে কি না (বাকিটা সেভ হয়) */
    data class SaveResult(val pending: Boolean, val baseIgnored: Boolean)

    /** clearBase: "জায়গা মুছুন" — আগে দেওয়া গাড়ির জায়গা মোছে (জেলার কেন্দ্র ধরা হয়) */
    suspend fun save(id: String?, name: String, phone: String, vehicleNo: String, types: List<String>, district: String, upazilaId: Int?, area: String, fare: String, docs: Map<String, String> = emptyMap(), lat: Double? = null, lng: Double? = null, clearBase: Boolean = false, fareChart: FareChart? = null): SaveResult {
        val o = call(
            "ambulance_save",
            JSONObject().put("id", id ?: JSONObject.NULL).put("name", name).put("phone", phone).put("vehicleNo", vehicleNo)
                .put("types", JSONArray(types)).put("district", district).put("upazilaId", upazilaId ?: JSONObject.NULL)
                .put("area", area).put("fare", fare).put("termsAccepted", true).put("docs", VerifyDocs.json(docs))
                // ভাড়ার তালিকা: খালি হলে null (সার্ভারে মোছে)
                .put("fareChart", fareChart?.json() ?: JSONObject.NULL)
                // গাড়ির জায়গা (ঐচ্ছিক) — না দিলে আগেরটা থাকে; সার্ভার ~১ কিমির ঘরে কেটে রাখে
                .apply {
                    if (lat != null && lng != null) put("lat", lat).put("lng", lng)
                    else if (clearBase) put("clearBase", true)
                },
        )
        return SaveResult(o.optBoolean("pending"), o.optBoolean("baseIgnored"))
    }

    suspend fun setAvailable(id: String, available: Boolean) {
        call("ambulance_available", JSONObject().put("id", id).put("available", available))
    }

    suspend fun delete(id: String) {
        call("ambulance_delete", JSONObject().put("id", id))
    }

    /** reqId: চালু "অ্যাম্বুলেন্স ডাকুন" অনুরোধের সময় ফোন — অনুরোধেও ওঠে (চালক "রোগী আপনাকে ফোন করেছেন" দেখেন) */
    suspend fun called(id: String, reqId: String? = null) {
        call("ambulance_called", JSONObject().put("id", id).apply { if (reqId != null) put("reqId", reqId) })
    }

    /** overcharged: "বলা ভাড়ার চেয়ে বেশি নিয়েছে" (শুধু অ্যাপে ভাড়া ঠিক হওয়া ট্রিপে; null = পাঠানো নয়) */
    suspend fun rate(id: String, rating: Int, comment: String, overcharged: Boolean? = null) {
        call("ambulance_rate", JSONObject().put("id", id).put("rating", rating).put("comment", comment).apply { if (overcharged != null) put("overcharged", overcharged) })
    }

    /** নিজের অ্যাম্বুলেন্সে অ্যাপ থেকে কতবার ফোন, রেটিং, মন্তব্য */
    suspend fun stats(): JSONObject = call("ambulance_stats")

    suspend fun adminPending(): List<Ambulance> = list(call("admin_ambulances"))

    suspend fun resolve(id: String, approve: Boolean) {
        call("admin_ambulance_resolve", JSONObject().put("id", id).put("approve", approve))
    }
}

/** অ্যাডমিন বাতিল/বন্ধ করলে কারণ (মালিকের নিজের কার্ডে) */
@Composable
internal fun StatusReasonLine(status: String, reason: String) {
    if (reason.isNotBlank() && status in listOf("rejected", "suspended")) {
        IconLine(R.drawable.ic_info, tr("Reason: ", "কারণ: ") + reason)
    }
}

/** মোবাইল, ল্যান্ডলাইন (02…, 031…) বা হটলাইন (16XXX) — সার্ভারের contactPhone-এর মতো */
fun isContactPhone(raw: String): Boolean {
    val d = raw.filter { it.isDigit() }
    val local = if (d.startsWith("880")) "0" + d.drop(3) else d
    return Regex("^0?1[3-9]\\d{8}$").matches(local) || Regex("^1\\d{4}$").matches(d) || Regex("^0[2-9]\\d{7,9}$").matches(local) ||
        // ডেমোর নম্বর (0120000xxxx) শুধু ডেমো অ্যাকাউন্টে
        (DemoLogin.isDemo(AuthRepository.currentUid) && Rules.DEMO_MOBILE.matches(local))
}

/** সাপ্তাহিক বন্ধের দিন, যেমন "প্রতি শুক্র বন্ধ" */
fun closedDaysText(days: List<Int>): String =
    if (days.isEmpty()) "" else tr("Closed every ", "প্রতি ") + Weekdays.ORDER.filter { it in days }.joinToString(", ") { Weekdays.short(it) } + tr("", " বন্ধ")

fun statusText(s: String): String = when (s) {
    "approved" -> tr("Listed", "তালিকায় আছে")
    "pending" -> tr("Waiting for approval", "অনুমোদনের অপেক্ষায়")
    "rejected" -> tr("Not approved", "অনুমোদন হয়নি")
    "suspended" -> tr("Suspended by the Lifolo team", "Lifolo টিম বন্ধ করেছে")
    else -> s
}

/** অ্যাম্বুলেন্সের তালিকায় এক লাইন: বাঁয়ে সবচেয়ে কাছের খালি গাড়ি (বা "জরুরি হলে ৯৯৯"), ডানে "৯৯৯" — ৯৯৯ একবারই */
@Composable
fun Emergency999Line(nearestSub: String?) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().testTag("amb_999")) {
        Text(
            if (nearestSub != null) tr("Call: nearest free — ", "ফোন: সবচেয়ে কাছের খালি — ") + nearestSub else tr("Emergency? 999 is free, 24 hours", "জরুরি হলে ৯৯৯ — বিনামূল্যে, ২৪ ঘণ্টা"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
        )
        SmallAction(num("999"), R.drawable.ic_call, { callPhone(context, "999") })
    }
}

/** ৯৯৯ কার্ড: সবচেয়ে উপরে */
@Composable
fun EmergencyCallCard(key: String = "ambulance") {
    // ছোট এক লাইন: বড় কার্ড জায়গা নষ্ট করে, তবু ৯৯৯ এক চাপে থাকা দরকার; ✕ চাপলে এই পাতায় আর আসে না
    val context = LocalContext.current
    var hidden by remember { mutableStateOf(Dismissed.isOn(context, "999_$key")) }
    if (hidden) return
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            tr("Emergency? 999 is free, 24 hours", "জরুরি হলে ৯৯৯ — বিনামূল্যে, ২৪ ঘণ্টা"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        SmallAction(num("999"), R.drawable.ic_call, { callPhone(context, "999") })
        IconButton(onClick = { Dismissed.set(context, "999_$key"); hidden = true }, modifier = Modifier.size(36.dp)) {
            AppIcon(R.drawable.ic_close, tr("Hide", "সরান"), Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "সেবা" ট্যাবের অ্যাম্বুলেন্স অংশ: খুঁজুন / আমার অ্যাম্বুলেন্স (যোগ করা) */
@Composable
fun AmbulanceContent(profile: Profile, showMine: Boolean = true) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        if (showMine) {
            PillTabs(
                tab,
                listOf(PillTab(tr("Find", "অ্যাম্বুলেন্স খুঁজুন"), R.drawable.ic_search), PillTab(tr("My ambulance", "আমার অ্যাম্বুলেন্স"), R.drawable.ic_person_outline)),
                { tab = it },
                secondary = true,
            )
        }
        if (tab == 0 || !showMine) AmbulanceFinder(profile)
        else Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MyAmbulancesSection(profile)
        }
    }
}

@Composable
private fun AmbulanceFinder(profile: Profile) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    remember { Areas.ensure(context) }
    var district by remember { mutableStateOf(profile.placeName) }
    var upazilaId by remember { mutableStateOf<Int?>(null) }
    var type by remember { mutableStateOf<String?>(null) }
    var list by remember { mutableStateOf<List<Ambulance>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    var called by remember { mutableStateOf(setOf<String>()) }
    var rating by remember { mutableStateOf<Ambulance?>(null) }
    var reporting by remember { mutableStateOf<Ambulance?>(null) }
    var showArea by remember { mutableStateOf(false) }
    var busyList by remember { mutableStateOf<List<Ambulance>>(emptyList()) }
    var busyOpen by remember { mutableStateOf(false) }
    val busyCount = busyList.size
    var nearestId by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var availableOnly by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf<ListingSort?>(null) }
    var openAmb by remember { mutableStateOf<Ambulance?>(null) }
    // অ্যাম্বুলেন্স ডাকুন: নিশ্চিত করার ঘর (target = শিট থেকে একটা গাড়ি), নিজের চালু অনুরোধ
    var requesting by remember { mutableStateOf(false) }
    var requestTarget by remember { mutableStateOf<Ambulance?>(null) }
    var activeReq by remember { mutableStateOf<AmbReq?>(null) }

    // দূরত্ব কোথা থেকে: লোকেশনের অনুমতি আগেই দেওয়া থাকলে এখনকার জায়গা (অনুমতি চেয়ে আটকানো হয় না — প্রতিটা সেকেন্ড দামি);
    // সর্বোচ্চ ২.৫ সেকেন্ড অপেক্ষা, তারপর একবার ঠিক হলে আর বদলায় না (বাটনের গাড়ি হঠাৎ বদলে যেন ভুল জায়গায় চাপ না পড়ে)
    var gpsPoint by remember { mutableStateOf<Triple<Double, Double, String>?>(null) }
    var gpsReady by remember { mutableStateOf(!LocationHelper.hasPermission(context)) }
    LaunchedEffect(Unit) {
        if (!gpsReady) {
            val loc = kotlinx.coroutines.withTimeoutOrNull(2500) { runCatching { LocationHelper.current(context) }.getOrNull() }
            gpsPoint = loc?.let { Triple(it.first, it.second, Districts.at(context, it.first, it.second).bn) }
            gpsReady = true
        }
    }
    // এখনকার জায়গা বেছে নেওয়া জেলার ভেতরে হলে সেটা, নইলে প্রোফাইলের GPS জায়গা (একই জেলা), নইলে null = "এলাকা ধরে"
    // (তখন স্থানাঙ্ক পাঠানো হয় না — জেলার কেন্দ্র থেকে দূরত্ব অর্থহীন, আর নিজের উপজেলার গাড়ি আগে আসা নষ্ট করে)
    fun origin(d: String): Pair<Double, Double>? {
        val g = gpsPoint
        val pLat = profile.lat
        val pLng = profile.lng
        return when {
            g != null && g.third == d -> g.first to g.second
            profile.locationSource == "gps" && pLat != null && pLng != null && profile.placeName == d -> pLat to pLng
            else -> null
        }
    }
    var byArea by remember { mutableStateOf(false) }

    LaunchedEffect(district, upazilaId, type, gpsReady, sort) {
        val d = district ?: return@LaunchedEffect
        if (!gpsReady) return@LaunchedEffect
        loading = true
        errorMsg = null
        val o = origin(d)
        // ২ দশমিক (≈১ কিমি) — সার্ভারও এভাবেই কাটে
        val r2 = { v: Double -> Math.round(v * 100) / 100.0 }
        runCatching { Ambulances.near(d, upazilaId, type, o?.first?.let(r2), o?.second?.let(r2), sort) }
            .onSuccess { list = it.items; busyList = it.busy; nearestId = it.nearestId; byArea = o == null }
            .onFailure { errorMsg = it.message }
        loading = false
    }

    // চালু অনুরোধ (অ্যাপ বন্ধ করে খুললেও): উপরে ব্যানার; ফোন করলে অনুরোধে ওঠে, ফিরে এলে অপেক্ষার পাতায় "ফোনে পেয়েছেন?"
    @Suppress("DEPRECATION")
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var resumeTick by remember { mutableIntStateOf(0) }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumeTick++ }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(resumeTick, LoginGate.uid.value) {
        if (LoginGate.isGuest) {
            activeReq = null
            return@LaunchedEffect
        }
        val r = runCatching { AmbRequests.status(null) }.getOrNull()
        activeReq = r?.takeIf { it.active }
        AmbReqNav.active.value = activeReq?.id
        // অনুরোধ চলার সময় এখান থেকে ফোন করে ফিরলেন: অপেক্ষার পাতায় (সেখানে একবার জিজ্ঞেস)
        val ask = AmbReqNav.askAfterCall.value
        if (ask != null && ask == activeReq?.id) AmbReqNav.open.value = ask
    }

    fun callAmbulance(a: Ambulance) {
        called = called + a.id
        val reqId = activeReq?.id
        if (reqId != null) AmbReqNav.askAfterCall.value = reqId
        scope.launch { runCatching { Ambulances.called(a.id, reqId) } }
        callPhone(context, a.phone)
    }

    /** "অ্যাম্বুলেন্স ডাকুন": লগইন লাগে (ফোন অতিথিও পারেন); চালু অনুরোধ থাকলে সেটার পাতা */
    fun requestAmbulance(target: Ambulance?) {
        val running = activeReq
        if (running != null) {
            AmbReqNav.open.value = running.id
            return
        }
        LoginGate.require(tr("Log in to call an ambulance", "অ্যাম্বুলেন্স ডাকতে লগইন করুন")) {
            requestTarget = target
            requesting = true
        }
    }

    // Google Maps থেকে শেয়ার করা জায়গা (২.৩১.০): শিট খোলা না থাকলে খোলা (জায়গাটা শিটের LocationInput নেয়)
    val shared = SharedPlace.text.value
    LaunchedEffect(shared, district) {
        if (shared != null && district != null && !requesting) {
            // চালু অনুরোধ থাকলে নতুন জায়গা খাটে না — সেটার পাতা
            if (activeReq != null) SharedPlace.text.value = null
            requestAmbulance(null)
        }
    }

    // জায়গা জানা থাকলে শুরুতে "নিকটতম" (সার্ভারও কাছেরটা আগে দেয়)
    LaunchedEffect(gpsReady) { if (gpsReady && sort == null && district?.let { origin(it) } != null) sort = ListingSort.NEAR }
    val shown = Listing.sorted(
        list.filter { (!availableOnly || it.available) && Listing.matches(query, it.name, it.area, Areas.label(it.district, it.upazilaId)) },
        sort, { it.extras.distanceKm ?: it.km }, { ambulanceRating(it) }, { if (it.available) true else null },
    )
    val filtered = type != null || availableOnly || query.isNotBlank()
    val clear = { type = null; availableOnly = false; query = "" }
    ListingList(
        controls = {
            ListingControls(
                ListingType.AMBULANCE, query, { query = it }, areaText(district, upazilaId), { showArea = true },
                sort, listOf(ListingSort.NEAR, ListingSort.RATING), { sort = it },
                chips = listOf(ListingChip(ListingText.available(), availableOnly, { availableOnly = !availableOnly }, "chip_available")) +
                    AmbulanceTypes.ALL.map { t -> ListingChip(AmbulanceTypes.label(t), type == t, { type = if (type == t) null else t }, "chip_type_$t") },
                onClear = if (filtered) clear else null,
            )
        },
        loading = loading,
        items = shown.map { a -> ambulanceCard(a, { openAmb = a }, { callAmbulance(a) }, { reporting = a }) },
        empty = {
            when {
                filtered -> ListingEmpty(ListingText.noMatch(), ListingText.clear(), clear)
                busyCount > 0 -> ListingEmpty(tr("All ambulances here are on a trip now", "এই এলাকার সব অ্যাম্বুলেন্স এখন ট্রিপে আছে"), ListingText.changeArea(), { showArea = true }, R.drawable.ic_ambulance)
                else -> ListingEmpty(ListingText.emptyLine(ListingType.AMBULANCE), ListingText.changeArea(), { showArea = true }, R.drawable.ic_ambulance)
            }
        },
        // জরুরি আগে: চালু অনুরোধ, "অ্যাম্বুলেন্স ডাকুন", তারপর ৯৯৯ (একবারই, ছোট লাইন) — তারপর খোঁজা আর তালিকা
        header = {
            activeReq?.let { r -> item(key = "active_req") { Column(Modifier.padding(horizontal = LISTING_GUTTER)) { ActiveRequestBanner(r) { AmbReqNav.open.value = r.id } } } }
            if (!loading && district != null && list.any { it.available }) {
                val near = list.firstOrNull { it.id == nearestId }
                item(key = "nearest") {
                    Column(Modifier.padding(horizontal = LISTING_GUTTER)) {
                        AmbulanceCallBar(near, null, onRequest = { requestAmbulance(null) }, onCallNearest = near?.let { a -> { callAmbulance(a) } })
                    }
                }
            }
            // এক লাইনে: "ফোন: সবচেয়ে কাছের খালি — …" (থাকলে) আর ৯৯৯ (একবারই, ছোট চিপ)
            val nearSub = list.firstOrNull { it.id == nearestId }?.takeIf { !loading && it.available }?.let { nearestSub(it, byArea) }
            item(key = "999") { Column(Modifier.padding(horizontal = LISTING_GUTTER)) { Emergency999Line(nearSub) } }
        },
        messages = {
            InfoText(infoMsg)
            ErrorText(errorMsg)
        },
        footer = {
            // ট্রিপে থাকা গাড়ি: আলাদা ভাঁজ করা অংশ; খালি গাড়ি না থাকলে নিজে থেকে খোলা
            if (!loading && busyList.isNotEmpty()) {
                val open = busyOpen || list.isEmpty()
                item(key = "busy_head") {
                    Column(Modifier.padding(horizontal = LISTING_GUTTER)) {
                        Row(
                            Modifier.fillMaxWidth().clickable { busyOpen = !open }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                tr("On a trip now (${busyList.size})", "এখন ট্রিপে আছে (${num(busyList.size)})"),
                                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                            )
                            AppIcon(R.drawable.ic_expand_more_round, if (open) tr("Hide", "লুকান") else tr("Show", "দেখুন"), Modifier.rotate(if (open) 180f else 0f))
                        }
                        if (open) SmallNote(tr("They're busy — call only if you can wait, to ask when they'll be free.", "এরা এখন ব্যস্ত — অপেক্ষা করতে পারলে ফোন করে জেনে নিন কখন খালি হবে।"))
                    }
                }
                if (open) items(busyList, key = { "busy_" + it.id }) { a ->
                    ListingCard(ambulanceCard(a, { openAmb = a }, { callAmbulance(a) }, { reporting = a }), Modifier.padding(horizontal = LISTING_GUTTER))
                }
            }
        },
    )
    openAmb?.let { a ->
        ListingDetail(
            ambulanceDetail(
                a, onCall = { callAmbulance(a) },
                onRequest = { openAmb = null; requestAmbulance(a) },
                onRate = if (a.canRate) ({ openAmb = null; rating = a }) else null,
                onReport = { openAmb = null; reporting = a },
            ),
            onDismiss = { openAmb = null },
        )
    }

    if (requesting) {
        district?.let { d ->
            AmbulanceRequestDialog(
                profile = profile, district = d, upazilaId = upazilaId, type = type, gps = origin(d), target = requestTarget,
                onDismiss = { requesting = false },
                onSent = { r ->
                    requesting = false
                    activeReq = r.takeIf { it.active }
                    AmbReqNav.active.value = activeReq?.id
                    AmbReqNav.open.value = r.id
                },
            )
        }
    }
    if (showArea) {
        AreaSheet(district, upazilaId, null, { d, u, _ -> district = d; upazilaId = u }, onDismiss = { showArea = false })
    }
    rating?.let { a ->
        StarRatingDialog(
            title = tr("Rate ", "রেটিং: ") + a.name,
            onDismiss = { rating = null },
            onSubmit = { stars, comment ->
                Ambulances.rate(a.id, stars, comment)
                rating = null
                infoMsg = tr("Thanks for the rating!", "রেটিং দেওয়ার জন্য ধন্যবাদ!")
            },
        )
    }
    reporting?.let { a ->
        ReasonReportDialog(
            onDismiss = { reporting = null },
            onSubmit = { reason ->
                Moderation.report("ambulance", a.id, reason = reason)
                reporting = null
                infoMsg = tr("Reported. An admin will review it.", "রিপোর্ট করা হয়েছে। অ্যাডমিন দেখবেন।")
            },
        )
    }
}

/** "ফোন" বাটনের নিচের লাইন: সবচেয়ে কাছের খালি গাড়ির নাম, দূরত্ব (এলাকা ধরে হলে "এলাকা ধরে"), ধরন */
private fun nearestSub(a: Ambulance, byArea: Boolean): String {
    val where = if (byArea) tr("By area", "এলাকা ধরে") else ambulanceKm(a)
    return listOfNotNull(a.name, where, a.types.joinToString(", ") { AmbulanceTypes.label(it) }.ifBlank { null }).joinToString(" · ")
}

/** তারা আর মন্তব্য দিয়ে রেটিং (অ্যাম্বুলেন্স, ফার্মেসি) */
@Composable
fun StarRatingDialog(title: String, onDismiss: () -> Unit, extra: (@Composable () -> Unit)? = null, onSubmit: suspend (Int, String) -> Unit) {
    val scope = rememberCoroutineScope()
    var stars by remember { mutableIntStateOf(0) }
    var comment by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = title,
        onDismiss = { if (!busy) onDismiss() },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row {
                    (1..5).forEach { i ->
                        IconButton(onClick = { stars = i }) {
                            AppIcon(if (i <= stars) R.drawable.ic_star_fill else R.drawable.ic_star, num(i), Modifier.size(32.dp), MaterialTheme.colorScheme.tertiary)
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
                extra?.invoke()
                ErrorText(errorMsg)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && stars > 0, onClick = {
                scope.launch {
                    busy = true
                    runCatching { onSubmit(stars, comment.trim()) }.onFailure { errorMsg = it.message }
                    busy = false
                }
            }) { ButtonContent(busy, tr("Submit", "জমা দিন")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Later", "পরে")) } },
    )
}

/** কারণ লিখে রিপোর্ট */
@Composable
fun ReasonReportDialog(onDismiss: () -> Unit, onSubmit: suspend (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var reason by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = tr("Report a problem", "সমস্যা জানান"),
        onDismiss = { if (!busy) onDismiss() },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Wrong number, overcharging, didn't come…", "ভুল নম্বর, বেশি টাকা চাওয়া, আসেনি…"), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it.take(300) },
                    label = { Text(tr("What happened", "কী হয়েছে")) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                ErrorText(errorMsg)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && reason.isNotBlank(), onClick = {
                scope.launch {
                    busy = true
                    runCatching { onSubmit(reason.trim()) }.onFailure { errorMsg = it.message }
                    busy = false
                }
            }) { ButtonContent(busy, tr("Report", "রিপোর্ট")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancel", "বাতিল")) } },
    )
}

// ------------------------------------------------------------
// "সেবা" ট্যাব: নিজের অ্যাম্বুলেন্স
// ------------------------------------------------------------

@Composable
fun MyAmbulancesSection(profile: Profile, refresh: Int = 0) {
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<Ambulance>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Ambulance?>(null) }
    var adding by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }

    // মালিকের হিসাব: অ্যাপ থেকে কতবার ফোন, রেটিং, মন্তব্য, রিপোর্ট
    var stats by remember { mutableStateOf<Map<String, org.json.JSONObject>>(emptyMap()) }
    var openStats by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(reload, refresh) {
        runCatching { Ambulances.mine() }.onSuccess { list = it; errorMsg = null }.onFailure { errorMsg = it.message }
        runCatching { Ambulances.stats() }.onSuccess { o ->
            val a = o.optJSONArray("items") ?: org.json.JSONArray()
            stats = (0 until a.length()).associate { a.getJSONObject(it).let { x -> x.optString("id") to x } }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InfoText(infoMsg)
        ErrorText(errorMsg)
        list.forEach { a ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(a.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            SmallNote(a.vehicleNo + " · " + statusText(a.status))
                            StatusReasonLine(a.status, a.statusReason)
                        }
                        IconButton(onClick = { editing = a }) { AppIcon(R.drawable.ic_edit, tr("Edit", "বদলান")) }
                    }
                    DocsNeededNotice("ambulance", a.id, VerifyDocs.ambulance(), a.docsMissing, a.docsRequested, a.docsNote) { reload++ }
                    if (a.status == "suspended" || a.status == "rejected") {
                        AppealButton("ambulance")
                    }
                    stats[a.id]?.let { st -> CallStatsBlock(st, expanded = openStats == a.id) { openStats = if (openStats == a.id) null else a.id } }
                    if (a.status == "approved") {
                        // রোগীরা যেভাবে দেখেন (তালিকার একই কার্ড) — "খালি আছি / ট্রিপে আছি" বদলালেই সাথে সাথে
                        var cur by remember(a) { mutableStateOf(a) }
                        var detail by remember { mutableStateOf(false) }
                        PatientViewPreview(ambulanceCard(cur, { detail = true }, {}, null))
                        if (detail) ListingDetail(ambulanceDetail(cur, {}, null, null, null), { detail = false })
                        QuickSwitch(
                            if (cur.available) ProviderText.available() else ProviderText.onTrip(),
                            tr("Turn off when you go on a trip.", "ট্রিপে গেলে বন্ধ করুন।"),
                            cur.available,
                            { on ->
                                cur = cur.copy(available = on, availableAt = if (on) System.currentTimeMillis() else 0L)
                                scope.launch {
                                    runCatching { Ambulances.setAvailable(a.id, on) }.onSuccess { reload++ }.onFailure { cur = a; errorMsg = it.message }
                                }
                            },
                            tag = "amb_available",
                        )
                        StillAvailableRow(a) {
                            scope.launch {
                                runCatching { Ambulances.setAvailable(a.id, true) }.onSuccess { reload++ }.onFailure { errorMsg = it.message }
                            }
                        }
                    }
                }
            }
        }
        OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) {
            ButtonContent(false, tr("Add ambulance", "অ্যাম্বুলেন্স যোগ করুন"), R.drawable.ic_add)
        }
    }

    if (adding || editing != null) {
        AmbulanceEditDialog(
            existing = editing,
            profile = profile,
            onDismiss = { adding = false; editing = null },
            onSaved = { r ->
                adding = false
                editing = null
                infoMsg = (if (r.pending) tr("Sent for approval. It will be listed after an admin checks it.", "অনুমোদনের জন্য পাঠানো হয়েছে। অ্যাডমিন যাচাই করলে তালিকায় আসবে।") else tr("Saved", "সেভ হয়েছে")) +
                    // জায়গা জেলার বাইরে হলে সার্ভার শুধু জায়গাটা বাদ দেয়, বাকিটা সেভ হয়
                    (if (r.baseIgnored) tr(" — vehicle location was outside the district, not saved", " — গাড়ির জায়গা জেলার বাইরে, তাই নেওয়া হয়নি") else "")
                reload++
            },
            onDeleted = {
                editing = null
                reload++
            },
        )
    }
}

/**
 * "খালি আছি"-র মেয়াদের শেষ ঘণ্টায়: "খালি আছে — আর ৪৫ মিনিট" আর এক চাপে আবার ১২ ঘণ্টা
 * ("এখনো খালি আছেন?" পুশ এখানেই আনে)। মিনিট প্রতি ৩০ সেকেন্ডে নতুন করে গোনা।
 */
@Composable
private fun StillAvailableRow(a: Ambulance, onRenew: () -> Unit) {
    if (a.status != "approved" || !a.available) return
    val until = if (a.availableUntil > 0) a.availableUntil else if (a.availableAt > 1) a.availableAt + 12 * 3600_000L else 0L
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(until) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    val left = until - now
    if (until <= 0 || left <= 0 || left >= 3600_000L) return
    val minutes = ((left + 59_999) / 60_000).coerceAtLeast(1)
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(cs.tertiaryContainer).padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            tr("Available — $minutes min left", "খালি আছে — আর ${num(minutes)} মিনিট"),
            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = cs.onTertiaryContainer, modifier = Modifier.weight(1f),
        )
        Button(onClick = onRenew) { Text(tr("Keep available", "আবার চালু রাখুন")) }
    }
}

@Composable
private fun AmbulanceEditDialog(existing: Ambulance?, profile: Profile, onDismiss: () -> Unit, onSaved: (Ambulances.SaveResult) -> Unit, onDeleted: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var phone by remember { mutableStateOf(existing?.phone ?: profile.phone) }
    var vehicleNo by remember { mutableStateOf(existing?.vehicleNo ?: "") }
    var types by remember { mutableStateOf(existing?.types?.toSet() ?: setOf("general")) }
    var district by remember { mutableStateOf(existing?.district ?: profile.placeName) }
    var upazilaId by remember { mutableStateOf(existing?.upazilaId) }
    var area by remember { mutableStateOf(existing?.area ?: "") }
    var fare by remember { mutableStateOf(existing?.fare ?: "") }
    var chart by remember { mutableStateOf(ChartDraft.of(existing?.fareChart)) }
    // গাড়ি যেখানে অপেক্ষা করে (ঐচ্ছিক): "সবচেয়ে কাছের" খুঁজতে; না দিলে জেলার কেন্দ্র ধরা হয়। clearBase: "জায়গা মুছুন"
    var baseLat by remember { mutableStateOf<Double?>(null) }
    var baseLng by remember { mutableStateOf<Double?>(null) }
    var clearBase by remember { mutableStateOf(false) }
    var docs by remember { mutableStateOf(mapOf<String, String>()) }
    var agreed by remember { mutableStateOf(existing != null) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    FormDialog(
        title = if (existing == null) tr("Add ambulance", "অ্যাম্বুলেন্স যোগ") else tr("Edit ambulance", "অ্যাম্বুলেন্স বদলান"),
        onDismiss = { if (!busy) onDismiss() },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it.take(80) }, label = { Text(tr("Service / driver name", "সার্ভিস বা চালকের নাম")) }, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words), singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    phone, { phone = it.filter { c -> c.isDigit() || c == '+' }.take(15) },
                    label = { Text(tr("Phone for calls", "যে নম্বরে ফোন আসবে")) },
                    supportingText = { Text(tr("Mobile, landline or hotline", "মোবাইল, ল্যান্ডলাইন বা হটলাইন")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(vehicleNo, { vehicleNo = it.take(40) }, label = { Text(tr("Vehicle registration no.", "গাড়ির রেজিস্ট্রেশন নম্বর")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(tr("Type", "ধরন"), style = MaterialTheme.typography.labelLarge)
                ChipRows(AmbulanceTypes.ALL, types, label = { AmbulanceTypes.label(it) }) { t -> types = if (t in types) types - t else types + t }
                Text(tr("Base area", "কোথা থেকে চলে"), style = MaterialTheme.typography.labelLarge)
                AreaChips(district, upazilaId, null, { d, u, _ -> district = d; upazilaId = u }, allowAllDistricts = false, showUnion = false)
                BaseLocationRow(
                    hasBase = existing?.hasBase == true,
                    picked = baseLat != null,
                    cleared = clearBase,
                    onPicked = { la, ln -> baseLat = la; baseLng = ln; clearBase = false },
                    onClear = { baseLat = null; baseLng = null; clearBase = existing?.hasBase == true },
                )
                OutlinedTextField(area, { area = it.take(120) }, label = { Text(tr("Where you go (optional)", "কোন কোন এলাকায় যান (ঐচ্ছিক)")) }, modifier = Modifier.fillMaxWidth())
                FareChartEditor(district, upazilaId, chart) { chart = it }
                OutlinedTextField(fare, { fare = it.take(120) }, label = { Text(tr("Fare note (optional)", "ভাড়া নিয়ে নোট (ঐচ্ছিক)")) }, placeholder = { Text(tr("e.g. Within city 800 Tk, per km 40 Tk", "যেমন: শহরের মধ্যে ৮০০ টাকা, প্রতি কিমি ৪০ টাকা")) }, modifier = Modifier.fillMaxWidth())
                // কাগজের ছবি: নতুন হলে দরকারি; বদলের সময় চাইলে নতুন ছবি
                DocPhotosField(VerifyDocs.ambulance(), docs, { docs = it },
                    if (existing != null) tr("Add new photos only if the papers changed.", "কাগজ বদলালে তবেই নতুন ছবি দিন।") else null)
                ServiceTermsCheck(agreed) { agreed = it }
                if (existing != null) {
                    TextButton(onClick = {
                        scope.launch {
                            busy = true
                            runCatching { Ambulances.delete(existing.id) }.onSuccess { onDeleted() }.onFailure { errorMsg = it.message }
                            busy = false
                        }
                    }, enabled = !busy) { Text(tr("Remove this ambulance", "এই অ্যাম্বুলেন্স সরিয়ে দিন"), color = MaterialTheme.colorScheme.error) }
                }
                ErrorText(errorMsg)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                val d = district
                errorMsg = when {
                    name.isBlank() -> tr("Enter the service or driver name", "সার্ভিস বা চালকের নাম লিখুন")
                    !isContactPhone(phone) -> tr("Enter a valid phone number", "সঠিক ফোন নম্বর দিন")
                    vehicleNo.isBlank() -> tr("Enter the vehicle registration number", "গাড়ির রেজিস্ট্রেশন নম্বর দিন")
                    types.isEmpty() -> tr("Choose the ambulance type", "অ্যাম্বুলেন্সের ধরন বেছে নিন")
                    d == null -> tr("Choose a district", "জেলা বেছে নিন")
                    existing == null && VerifyDocs.missing(VerifyDocs.ambulance(), docs) != null -> VerifyDocs.missing(VerifyDocs.ambulance(), docs)
                    !agreed -> tr("Please accept the terms", "শর্তে সম্মতি দিন")
                    else -> chart.build().second
                }
                if (errorMsg != null || d == null) return@TextButton
                val fc = chart.build().first
                scope.launch {
                    busy = true
                    runCatching { Ambulances.save(existing?.id, TitleCase.name(name), phone, vehicleNo.trim(), types.toList(), d, upazilaId, area.trim(), fare.trim(), docs, baseLat, baseLng, clearBase, fc) }
                        .onSuccess { onSaved(it) }
                        .onFailure { errorMsg = it.message }
                    busy = false
                }
            }) { ButtonContent(busy, tr("Save", "সেভ করুন")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancel", "বাতিল")) } },
    )
}

// গাড়ির জায়গার GPS এর চেয়ে খারাপ (মিটার) হলে নেওয়া হয় না
private const val BASE_MAX_ACCURACY_M = 200f

/**
 * গাড়ি যেখানে অপেক্ষা করে (স্ট্যান্ড/হাসপাতাল গেট) — বাসা নয়। শুধু ঠিক GPS (high accuracy, ২০০ মিটারের ভেতরে) নেওয়া হয়;
 * ভুল বা না পেলে এই সারিতেই লেখা — ফর্ম সেভ আটকায় না। সঠিক লোকেশনের অনুমতি শুধু এখানে চাওয়া হয়।
 * "জায়গা মুছুন": নতুন নেওয়াটা বাদ, আগে দেওয়া থাকলে সেভ করলে সার্ভারেও মোছে।
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun BaseLocationRow(hasBase: Boolean, picked: Boolean, cleared: Boolean, onPicked: (Double, Double) -> Unit, onClear: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun locate() {
        scope.launch {
            busy = true
            error = null
            val loc = runCatching { kotlinx.coroutines.withTimeoutOrNull(25_000) { LocationHelper.precise(context) } }.getOrNull()
            busy = false
            when {
                loc == null -> error = tr("Couldn't get your location. Turn on GPS and try again.", "লোকেশন পাওয়া যায়নি। GPS চালু করে আবার চেষ্টা করুন।")
                loc.third > BASE_MAX_ACCURACY_M -> error = tr("Couldn't get an accurate location — go outside and try again.", "লোকেশন ঠিকমতো পাওয়া যায়নি — বাইরে গিয়ে আবার চেষ্টা করুন")
                else -> onPicked(loc.first, loc.second)
            }
        }
    }

    // অ্যান্ড্রয়েড ১২+: সঠিক আর আনুমানিক একসাথে চাইতে হয়; কেউ শুধু আনুমানিক দিলে accuracy খারাপ আসে → উপরের লেখা
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) locate() else error = tr("Location permission denied.", "লোকেশনের অনুমতি দেওয়া হয়নি।")
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(tr("Where the vehicle waits (stand / hospital gate) — not your home", "গাড়ি যেখানে অপেক্ষা করে (স্ট্যান্ড/হাসপাতাল গেট) — বাসা নয়"), style = MaterialTheme.typography.labelLarge)
        when {
            picked -> InfoText(tr("Vehicle location set", "গাড়ির জায়গা নেওয়া হয়েছে"))
            cleared -> SmallNote(tr("Location will be removed when you save.", "সেভ করলে জায়গা মুছে যাবে।"))
            hasBase -> SmallNote(tr("Location given. Update only if the waiting place changed.", "জায়গা দেওয়া আছে। অপেক্ষার জায়গা বদলালে তবেই আবার দিন।"))
            else -> SmallNote(tr("Optional — stand there and tap. Nearby patients find you first; only the area (about 1 km) is kept.", "ঐচ্ছিক — সেখানে দাঁড়িয়ে চাপুন। কাছের রোগী আগে খুঁজে পাবেন; শুধু এলাকা (প্রায় ১ কিমি) রাখা হয়।"))
        }
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!picked) {
                OutlinedButton(
                    onClick = {
                        if (LocationHelper.hasFine(context)) locate()
                        else launcher.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
                    },
                    enabled = !busy,
                ) { ButtonContent(busy, tr("Use current location", "বর্তমান লোকেশন ধরুন"), R.drawable.ic_location_on) }
            }
            if (picked || (hasBase && !cleared)) {
                TextButton(onClick = { error = null; onClear() }, enabled = !busy) { ButtonContent(false, tr("Remove location", "জায়গা মুছুন"), R.drawable.ic_close) }
            }
        }
        ErrorText(error)
    }
}

/** অ্যাম্বুলেন্স/ফার্মেসির শর্তে সম্মতি */
@Composable
fun ServiceTermsCheck(checked: Boolean, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Checkbox(checked = checked, onCheckedChange = onChange)
        Column(Modifier.weight(1f)) {
            Text(tr("I accept the terms for service providers", "সেবাদাতাদের শর্ত মেনে নিচ্ছি"), style = MaterialTheme.typography.bodyMedium)
            Text(
                tr("Read the terms", "শর্তগুলো পড়ুন"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp).clickable { openUrl(context, TERMS_URL) },
            )
        }
    }
}

/** নিজের হিসাব: অ্যাপ থেকে ফোন, রেটিং, মন্তব্য (অ্যাম্বুলেন্স, বাসায় সেবা; শুধু নিজেই দেখেন) */
@Composable
internal fun CallStatsBlock(st: org.json.JSONObject, expanded: Boolean, onToggle: () -> Unit) {
    val calls = st.optJSONObject("calls") ?: org.json.JSONObject()
    val rc = st.optInt("ratingCount")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            tr("Calls from the app: ", "অ্যাপ থেকে ফোন: ") +
                tr("${calls.optInt("7")} in 7 days · ${calls.optInt("30")} in 30 days", "৭ দিনে ${num(calls.optInt("7"))} · ৩০ দিনে ${num(calls.optInt("30"))}"),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            tr("Rating: ", "রেটিং: ") + (if (rc > 0) num(st.optDouble("rating").toString()) + " (${num(rc)})" else "—") +
                if (st.optInt("reports") > 0) tr(" · ${st.optInt("reports")} report(s)", " · রিপোর্ট ${num(st.optInt("reports"))}টি") else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onToggle) { Text(if (expanded) tr("Hide details", "বিস্তারিত লুকান") else tr("Details", "বিস্তারিত")) }
        if (expanded) {
            val d = st.optJSONArray("daily") ?: org.json.JSONArray()
            Text(tr("Calls per day, last 30 days", "গত ৩০ দিনে দিনে কতবার ফোন"), style = MaterialTheme.typography.titleSmall)
            MiniBars((0 until d.length()).map { d.optInt(it) })
            SmallNote(tr("${st.optInt("callers")} different people called from the app in total.", "মোট ${num(st.optInt("callers"))} জন আলাদা মানুষ অ্যাপ থেকে ফোন করেছেন।"))
            val cm = st.optJSONArray("comments") ?: org.json.JSONArray()
            if (cm.length() > 0) {
                Text(tr("Recent ratings", "সাম্প্রতিক রেটিং"), style = MaterialTheme.typography.titleSmall)
                (0 until cm.length()).forEach {
                    val c = cm.getJSONObject(it)
                    Text("★".repeat(c.optInt("rating").coerceIn(0, 5)) + if (c.optString("comment").isNotBlank()) "  " + c.optString("comment") else "", style = MaterialTheme.typography.bodySmall)
                }
            }
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
        androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(90.dp)) {
            if (values.isEmpty()) return@Canvas
            val slot = size.width / values.size
            val bw = (slot - 2.dp.toPx()).coerceAtLeast(2f)
            values.forEachIndexed { i, v ->
                if (v > 0) {
                    val h = (v.toFloat() / max) * (size.height - 4.dp.toPx())
                    drawRoundRect(color, androidx.compose.ui.geometry.Offset(i * slot + 1.dp.toPx(), size.height - h), androidx.compose.ui.geometry.Size(bw, h), androidx.compose.ui.geometry.CornerRadius(3.dp.toPx(), 3.dp.toPx()))
                }
            }
            drawLine(base, androidx.compose.ui.geometry.Offset(0f, size.height), androidx.compose.ui.geometry.Offset(size.width, size.height), 1.dp.toPx())
        }
        Row {
            Text(tr("30 days ago", "৩০ দিন আগে"), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            Text(tr("Today", "আজ"), style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(2.dp))
        SmallNote(tr("Highest in a day: ${values.maxOrNull() ?: 0}", "এক দিনে সর্বোচ্চ: ${num(values.maxOrNull() ?: 0)}"))
    }
}
