package com.bspc.blood

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/*
 * অ্যাম্বুলেন্স ডাকার "কোথা থেকে নেবে" (২.৩১.০, পারভেজ, ১৯ অক্টোবর ২০২৬) — ওয়েবের LocationInput.tsx-এর অ্যাপ রূপ।
 * তিনভাবে, সবই বিনামূল্যে (অ্যাপের ভেতরে ম্যাপ নেই — MapsLink.kt):
 *   ১) "এখনকার জায়গা" (প্রধান): ফোনের এককালীন GPS। নেওয়ার পর "Google ম্যাপে দেখুন" — ফোনের Google Maps অ্যাপে বিন্দুটা
 *      (শুধু মিলিয়ে দেখা, কিছু ফেরত আসে না)।
 *   ২) "Google ম্যাপ থেকে জায়গা দিন": Google Maps খোলে → জায়গা চেপে ধরে "শেয়ার" → Lifolo (ShareActivity), অথবা লিংক
 *      লেখার ঘরে পেস্ট। ছোট লিংক সার্ভারে খোলা (maps_link_resolve)।
 *   ৩) "লোকেশন লিখুন": এলাকা / রাস্তা / চেনা জায়গা (জায়গা না দিলে বাধ্যতামূলক)।
 * জায়গা (১ বা ২) থাকলে জেলা সেই জায়গার, উপজেলা আপনা থেকে (কাছের উপজেলা-কেন্দ্র); না পারলে উপজেলার চিপ থাকে।
 * জায়গা না থাকলে আগের মতো জেলা › উপজেলা (বাধ্যতামূলক) — নিজের উপজেলার গাড়ি আগে (server/lib/ambreq.js)।
 * ঠিক জায়গা শুধু রাজি হওয়া চালক পান, অনুরোধ বন্ধের ২৪ ঘণ্টা পর মোছে (সার্ভারের নিয়ম)।
 */

object AmbLocationConfig {
    /**
     * অ্যাপের ভেতরে ম্যাপ (পরে): Google Maps SDK for Android-এ ম্যাপ দেখানো বিনামূল্যে, কিন্তু Google Cloud-এ বিলিং অ্যাকাউন্ট
     * (কার্ড) লাগে — এখন নেই। তখন LocationInput-এর ভেতরে একটা ম্যাপ বসবে যা শুধু AmbLocation.point ভরে (pointFrom = "map");
     * ডাকার বাকি পথ (AmbulanceRequestDialog → AmbRequests.create) একই থাকে। ততদিন Google Maps অ্যাপের শেয়ার/লিংক।
     */
    const val IN_APP_MAP = false
}

/**
 * ডাকার জায়গা: এলাকা + লেখা; point = এখনকার জায়গা (pointFrom "gps") বা Google ম্যাপ থেকে ("map") — না দিলে null (উপজেলা ধরে);
 * upazilaAuto: উপজেলা জায়গা থেকে আপনা থেকে বসানো।
 */
data class AmbLocation(
    val district: String,
    val upazilaId: Int?,
    val unionId: Int?,
    val landmark: String,
    val point: Pair<Double, Double>?,
    val pointFrom: String? = if (point != null) "gps" else null,
    val upazilaAuto: Boolean = false,
) {
    /** জায়গা না থাকলে উপজেলা আর লেখা দুটোই লাগে; জায়গা থাকলে কিছুই বাধ্যতামূলক নয় */
    fun error(): String? = when {
        point != null -> null
        upazilaId == null -> tr("Choose the upazila, or give your current location", "উপজেলা বেছে নিন, অথবা এখনকার জায়গা দিন")
        landmark.isBlank() -> tr("Write the area, road or a landmark", "এলাকা / রাস্তা / চেনা জায়গা লিখুন")
        else -> null
    }
}

/** জায়গা থেকে জেলা আর উপজেলা (আপনা থেকে, না পারলে একই জেলায় আগেরটা) */
fun AmbLocation.withPoint(context: android.content.Context, lat: Double, lng: Double, from: String): AmbLocation {
    val d = Districts.at(context, lat, lng).bn
    val auto = MapsLink.nearestUpazila(context, d, lat, lng)
    val keep = if (d == district && !upazilaAuto) upazilaId else null
    return copy(
        district = d, point = lat to lng, pointFrom = from,
        upazilaId = auto ?: keep, upazilaAuto = auto != null, unionId = if (auto == null && keep != null) unionId else null,
    )
}

@Composable
fun LocationInput(value: AmbLocation, onChange: (AmbLocation) -> Unit, showErrors: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var asking by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val cs = MaterialTheme.colorScheme
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)

    // আগে থেকে পাওয়া জায়গা (তালিকার GPS) — উপজেলা আপনা থেকে
    LaunchedEffect(Unit) {
        val p = current.point
        if (p != null && current.upazilaId == null) change(current.withPoint(context, p.first, p.second, current.pointFrom ?: "gps"))
    }

    fun locate() {
        scope.launch {
            busy = "gps"
            errorMsg = null
            val loc = kotlinx.coroutines.withTimeoutOrNull(15_000) {
                runCatching { LocationHelper.precise(context)?.let { it.first to it.second } ?: LocationHelper.current(context) }.getOrNull()
            }
            busy = null
            if (loc == null) {
                errorMsg = tr("Couldn't get your location — choose the area, or give a place from Google Maps.", "এখনকার জায়গা পাওয়া যায়নি — এলাকা বেছে নিন, অথবা Google ম্যাপ থেকে জায়গা দিন।")
            } else change(current.withPoint(context, loc.first, loc.second, "gps"))
        }
    }

    /** Google ম্যাপের লিংক/শেয়ারের লেখা → জায়গা; লেখার ঘর থেকে লিংকটা সরানো */
    fun fromLink(text: String, fromField: Boolean) {
        val rest = if (fromField) text.replace(MapsLink.LINK, "").replace(Regex("\\s+"), " ").trim().take(160) else current.landmark
        val parsed = MapsLink.parse(text)
        errorMsg = null
        if (parsed == null) {
            // লেখা যেমন আছে থাকে (মুছে দিলে বা ঠিক করলে আবার দেখা হয়)
            errorMsg = tr("Couldn't find a place in this link — in Google Maps, press and hold the place, then Share.", "এই লিংকে জায়গা পাওয়া যায়নি — Google ম্যাপে জায়গাটা চেপে ধরে \"শেয়ার\" চাপুন।")
            return
        }
        scope.launch {
            busy = "map"
            val p = when (parsed) {
                is MapsLink.Parsed.At -> Result.success(parsed.lat to parsed.lng)
                is MapsLink.Parsed.Short -> runCatching { MapsLink.resolve(parsed.url) }
            }
            busy = null
            p.onSuccess { (lat, lng) -> change(current.copy(landmark = rest).withPoint(context, lat, lng, "map")) }
                .onFailure { e -> errorMsg = e.message }
        }
    }

    // Google Maps থেকে "শেয়ার" → Lifolo: শিট খোলা থাকলে এখানেই আসে
    DisposableEffect(Unit) {
        SharedPlace.sheetOpen.value = true
        onDispose { SharedPlace.sheetOpen.value = false }
    }
    // লেখার ঘরে লিংক: লেখা থামার ০.৭ সেকেন্ড পরে পড়া — অক্ষর ধরে লিখলে (বা কিবোর্ড ধাপে ধাপে বসালে) অর্ধেক লিংক
    // ("…?q=24.08,90") থেকে ভুল জায়গা না নেয়; পেস্টও একই পথে
    LaunchedEffect(value.landmark) {
        if (MapsLink.LINK.containsMatchIn(value.landmark)) {
            kotlinx.coroutines.delay(700)
            fromLink(value.landmark, fromField = true)
        }
    }
    val shared = SharedPlace.text.value
    LaunchedEffect(shared) {
        if (shared != null) {
            SharedPlace.text.value = null
            fromLink(shared, fromField = false)
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) locate()
        else errorMsg = tr(
            "Location permission not given — choose the area, or give a place from Google Maps. You can allow it in the phone's settings.",
            "লোকেশনের অনুমতি দেওয়া হয়নি — এলাকা বেছে নিন, অথবা Google ম্যাপ থেকে জায়গা দিন। ফোনের সেটিংস থেকে অনুমতি দিতে পারেন।",
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("amb_location")) {
        Text(tr("Pick-up from", "কোথা থেকে নেবে"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        val point = value.point
        if (point != null) {
            Surface(shape = RoundedCornerShape(12.dp), color = cs.primaryContainer, modifier = Modifier.fillMaxWidth().testTag(if (value.pointFrom == "map") "amb_map_ok" else "amb_gps_ok")) {
                Row(Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (value.pointFrom == "map") tr("Place from the map taken ✓", "ম্যাপের জায়গা নেওয়া হয়েছে ✓")
                            else tr("Current location taken ✓", "এখনকার জায়গা নেওয়া হয়েছে ✓"),
                            style = MaterialTheme.typography.bodyMedium, color = cs.onPrimaryContainer,
                        )
                        // শুধু মিলিয়ে দেখা: ফোনের Google Maps অ্যাপে বিন্দুটা
                        Text(
                            tr("See on Google Maps", "Google ম্যাপে দেখুন"),
                            style = MaterialTheme.typography.labelLarge, color = cs.primary, textDecoration = TextDecoration.Underline,
                            modifier = Modifier.padding(top = 2.dp).clickable { MapsLink.open(context, point.first, point.second) }.testTag("amb_gmaps"),
                        )
                    }
                    TextButton(onClick = {
                        change(value.copy(point = null, pointFrom = null, upazilaId = if (value.upazilaAuto) null else value.upazilaId, upazilaAuto = false))
                    }) { Text(tr("Remove", "বাদ দিন")) }
                }
            }
        } else {
            Button(
                onClick = {
                    errorMsg = null
                    if (LocationHelper.hasPermission(context)) locate() else asking = true
                },
                enabled = busy == null, modifier = Modifier.fillMaxWidth().height(48.dp).testTag("amb_gps"),
            ) { ButtonContent(busy == "gps", tr("Current location", "এখনকার জায়গা"), R.drawable.ic_location_on) }
            OutlinedButton(
                onClick = {
                    errorMsg = null
                    // Google Maps অ্যাপ বেছে নেওয়া জেলায় খোলে — সেখানে জায়গা চেপে ধরে "শেয়ার" → Lifolo
                    val d = Districts.ALL.firstOrNull { it.bn == value.district }
                    if (d != null) MapsLink.open(context, d.lat, d.lng, pin = false)
                },
                enabled = busy == null, modifier = Modifier.fillMaxWidth().testTag("amb_map"),
            ) { ButtonContent(busy == "map", tr("Give a place from Google Maps", "Google ম্যাপ থেকে জায়গা দিন"), R.drawable.ic_map) }
            SmallNote(tr("In Google Maps, press and hold the place → Share → Lifolo. Or paste the link below.", "Google ম্যাপে জায়গাটা চেপে ধরুন → শেয়ার → Lifolo। অথবা লিংক নিচের ঘরে পেস্ট করুন।"))
        }
        OutlinedTextField(
            value.landmark,
            { t ->
                errorMsg = null
                change(value.copy(landmark = t.take(if (MapsLink.LINK.containsMatchIn(t)) 600 else 160)))
            },
            label = { Text(if (point != null) tr("Write the location (optional)", "লোকেশন লিখুন (ঐচ্ছিক)") else tr("Write the location", "লোকেশন লিখুন")) },
            placeholder = { Text(tr("Area / road / landmark — e.g. house 12, near the school gate", "এলাকা / রাস্তা / চেনা জায়গা — যেমন: বাসা ১২, স্কুল গেটের পাশে")) },
            isError = showErrors && point == null && value.landmark.isBlank(),
            modifier = Modifier.fillMaxWidth().testTag("amb_pickup"),
        )
        // জেলা › উপজেলা › ইউনিয়ন (একটা বদলালে নিচেরগুলো মুছে যায়); জেলা বদলালে আগের জায়গা আর খাটে না
        AreaChips(
            value.district, value.upazilaId, value.unionId,
            onChange = { d, u, n ->
                val dist = d ?: value.district
                val same = dist == value.district
                change(value.copy(district = dist, upazilaId = u, unionId = n, upazilaAuto = false, point = if (same) value.point else null, pointFrom = if (same) value.pointFrom else null))
            },
            allowAllDistricts = false,
        )
        if (value.upazilaAuto) SmallNote(tr("Upazila taken from the location — change it if wrong.", "উপজেলা জায়গা থেকে নেওয়া — ভুল হলে বদলান।"))
        else if (showErrors && point == null && value.upazilaId == null) ErrorText(tr("Choose the upazila", "উপজেলা বেছে নিন"))
        ErrorText(errorMsg)
    }

    // অনুমতি চাওয়ার আগে কেন (তারপর ফোনের নিজের প্রশ্ন)
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(tr("Share your current location?", "এখনকার জায়গা দেবেন?")) },
            text = {
                Text(
                    tr(
                        "It helps the nearest ambulance get your call first. Only the driver who accepts sees it, and it is deleted 24 hours after the request ends. You can skip it — choose the area or give a place from Google Maps.",
                        "এখনকার জায়গা দিলে সবচেয়ে কাছের অ্যাম্বুলেন্স আগে খবর পায়। শুধু রাজি হওয়া চালক দেখবেন, অনুরোধ শেষ হওয়ার ২৪ ঘণ্টা পর মুছে যায়। না দিলেও চলবে — এলাকা বেছে নিন বা Google ম্যাপ থেকে জায়গা দিন।",
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    asking = false
                    launcher.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
                }) { Text(tr("Allow", "অনুমতি দিন")) }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(tr("Not now", "এখন না")) } },
        )
    }
}
