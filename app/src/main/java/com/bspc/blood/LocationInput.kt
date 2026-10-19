package com.bspc.blood

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/*
 * অ্যাম্বুলেন্স ডাকার "কোথা থেকে নেবে" (২.২৯.০, পারভেজ, ১৯ অক্টোবর ২০২৬) — ওয়েবের LocationInput.tsx-এর অ্যাপ রূপ।
 * ম্যাপ নেই (খরচ/ঝুঁকি নয়): এলাকা (জেলা › উপজেলা › ইউনিয়ন — উপজেলা পর্যন্ত বাধ্যতামূলক) + "এলাকা / রাস্তা / চেনা জায়গা"
 * লেখা (আগের বারের লেখা আগে থেকে) + ঐচ্ছিক "আমার এখনকার জায়গা" (ফোনের এককালীন GPS — বিনামূল্যে, ম্যাপ লাগে না)।
 * GPS পেলে পাঠানো দূরত্ব ধরে (আগের মতো); না পেলে/না দিলে উপজেলা ধরে — নিজের উপজেলার গাড়ি আগে (server/lib/ambreq.js)।
 * ঠিক জায়গা শুধু রাজি হওয়া চালক পান, অনুরোধ বন্ধের ২৪ ঘণ্টা পর মোছে (সার্ভারের নিয়ম, বদলায়নি)।
 */

object AmbLocationConfig {
    /**
     * ভবিষ্যতে ম্যাপ (বিনিয়োগ পেলে) — এখানে যোগ হবে।
     * true করলে LocationInput-এর ভেতরে একটা MapPicker বসবে (শুধু AmbLocation.point বদলায়); ডাকার বাকি পথ
     * (AmbulanceRequestDialog → AmbRequests.create) একই থাকে। টাইলের খরচ/নিয়ম ঠিক না হওয়া পর্যন্ত false।
     */
    const val MAP_PICKER_ENABLED = false
}

/** ডাকার জায়গা: এলাকা + চেনা জায়গা; point = "আমার এখনকার জায়গা" (না দিলে null — উপজেলা ধরে) */
data class AmbLocation(
    val district: String,
    val upazilaId: Int?,
    val unionId: Int?,
    val landmark: String,
    val point: Pair<Double, Double>?,
) {
    /** উপজেলা আর চেনা জায়গা ছাড়া পাঠানো যায় না */
    fun error(): String? = when {
        upazilaId == null -> tr("Choose the upazila", "উপজেলা বেছে নিন")
        landmark.isBlank() -> tr("Write the area, road or a landmark", "এলাকা / রাস্তা / চেনা জায়গা লিখুন")
        else -> null
    }
}

@Composable
fun LocationInput(value: AmbLocation, onChange: (AmbLocation) -> Unit, showErrors: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val cs = MaterialTheme.colorScheme

    fun locate() {
        scope.launch {
            busy = true
            errorMsg = null
            val loc = kotlinx.coroutines.withTimeoutOrNull(15_000) {
                runCatching { LocationHelper.precise(context)?.let { it.first to it.second } ?: LocationHelper.current(context) }.getOrNull()
            }
            busy = false
            when {
                loc == null -> errorMsg = tr("Couldn't get your location — the chosen area will be used.", "এখনকার জায়গা পাওয়া যায়নি — বেছে নেওয়া এলাকা ধরে পাঠানো হবে।")
                Districts.at(context, loc.first, loc.second).bn != value.district -> errorMsg = tr(
                    "You are outside the chosen district — change the area if needed. The area will be used.",
                    "আপনি এখন বেছে নেওয়া জেলার বাইরে — দরকার হলে এলাকা বদলান। এলাকা ধরে পাঠানো হবে।",
                )
                else -> onChange(value.copy(point = loc))
            }
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) locate()
        else errorMsg = tr(
            "Location permission not given — the chosen area will be used. You can allow it in the phone's settings.",
            "লোকেশনের অনুমতি দেওয়া হয়নি — বেছে নেওয়া এলাকা ধরে পাঠানো হবে। ফোনের সেটিংস থেকে অনুমতি দিতে পারেন।",
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("amb_location")) {
        Text(tr("Area", "এলাকা"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        // জেলা › উপজেলা › ইউনিয়ন (একটা বদলালে নিচেরগুলো মুছে যায়); জেলা বদলালে আগের GPS আর খাটে না
        AreaChips(
            value.district, value.upazilaId, value.unionId,
            onChange = { d, u, n ->
                val dist = d ?: value.district
                onChange(value.copy(district = dist, upazilaId = u, unionId = n, point = if (dist == value.district) value.point else null))
            },
            allowAllDistricts = false,
        )
        if (showErrors && value.upazilaId == null) ErrorText(tr("Choose the upazila", "উপজেলা বেছে নিন"))
        OutlinedTextField(
            value.landmark, { onChange(value.copy(landmark = it.take(160))) },
            label = { Text(tr("Area / road / landmark", "এলাকা / রাস্তা / চেনা জায়গা")) },
            placeholder = { Text(tr("e.g. house 12, near the school gate", "যেমন: বাসা ১২, স্কুল গেটের পাশে")) },
            isError = showErrors && value.landmark.isBlank(),
            modifier = Modifier.fillMaxWidth().testTag("amb_pickup"),
        )
        val point = value.point
        if (point != null) {
            Surface(shape = RoundedCornerShape(12.dp), color = cs.primaryContainer, modifier = Modifier.fillMaxWidth().testTag("amb_gps_ok")) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Current location taken ✓", "এখনকার জায়গা নেওয়া হয়েছে ✓"), style = MaterialTheme.typography.bodyMedium, color = cs.onPrimaryContainer, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onChange(value.copy(point = null)) }) { Text(tr("Remove", "বাদ দিন")) }
                }
            }
        } else {
            OutlinedButton(
                onClick = {
                    errorMsg = null
                    if (LocationHelper.hasPermission(context)) locate() else asking = true
                },
                enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("amb_gps"),
            ) { ButtonContent(busy, tr("My current location (optional)", "আমার এখনকার জায়গা (ঐচ্ছিক)"), R.drawable.ic_location_on) }
            SmallNote(tr("Without it, ambulances of the chosen upazila get it first.", "না দিলে বেছে নেওয়া উপজেলার গাড়ি আগে খবর পায়।"))
        }
        ErrorText(errorMsg)
        // AmbLocationConfig.MAP_PICKER_ENABLED হলে এখানে ম্যাপ — এখন নেই
    }

    // অনুমতি চাওয়ার আগে কেন (তারপর ফোনের নিজের প্রশ্ন)
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(tr("Share your current location?", "এখনকার জায়গা দেবেন?")) },
            text = {
                Text(
                    tr(
                        "It helps the nearest ambulance get your call first. Only the driver who accepts sees it, and it is deleted 24 hours after the request ends. You can skip it — the chosen area will be used.",
                        "এখনকার জায়গা দিলে সবচেয়ে কাছের অ্যাম্বুলেন্স আগে খবর পায়। শুধু রাজি হওয়া চালক দেখবেন, অনুরোধ শেষ হওয়ার ২৪ ঘণ্টা পর মুছে যায়। না দিলেও চলবে — বেছে নেওয়া এলাকা ধরে পাঠানো হবে।",
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
