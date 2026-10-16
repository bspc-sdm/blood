@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bspc.blood

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject

// ------------------------------------------------------------
// বাসায় সেবা: যে ডাক্তার আর নার্স রোগীর বাসায় যান, তাদের আলাদা তালিকা।
// অ্যাপে অনুরোধ/গ্রহণ নেই — রোগী সরাসরি ফোন করেন, ফি নিজেরা ঠিক করে নেন। অ্যাপ টাকা নেয় না।
// ------------------------------------------------------------

private val HOME_TYPES = listOf("doctor", "nurse")

@Composable
fun HomeCareTab(profile: Profile, me: CommunityMe?, onEditChamber: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        PillTabs(
            tab,
            listOf(
                PillTab(tr("Doctor at home", "বাসায় ডাক্তার"), R.drawable.ic_stethoscope),
                PillTab(tr("Nurse at home", "বাসায় নার্স"), R.drawable.ic_medical_services),
            ),
            { tab = it },
            secondary = true,
        )
        HomeCareList(HOME_TYPES[tab], profile, me, onEditChamber)
    }
}

@Composable
private fun HomeCareList(type: String, profile: Profile, me: CommunityMe?, onEditChamber: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    remember { Areas.ensure(context) }
    val hasGps = profile.locationSource == "gps" && profile.lat != null && profile.lng != null
    // GPS থাকলে শুরুতে নিজের কাছে, নইলে নিজের জেলায়
    var nearMe by remember { mutableStateOf(hasGps) }
    var district by remember { mutableStateOf(profile.placeName) }
    var upazilaId by remember { mutableStateOf<Int?>(null) }
    var service by remember(type) { mutableStateOf<String?>(null) }
    var female by remember { mutableStateOf(false) }
    var showArea by remember { mutableStateOf(false) }
    var showFilter by remember { mutableStateOf(false) }
    var list by remember { mutableStateOf<List<HomePro>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    var called by remember { mutableStateOf(setOf<String>()) }
    var rating by remember { mutableStateOf<HomePro?>(null) }
    var reporting by remember { mutableStateOf<HomePro?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(type, nearMe, district, upazilaId, service, female, reload) {
        loading = true
        errorMsg = null
        runCatching {
            // নিজের জেলাও পাঠানো: যাঁরা লোকেশন না দিয়ে উপজেলা বেছেছেন তাঁরাও যেন আসেন
            if (nearMe) HomeCare.list(type, profile.lat, profile.lng, profile.placeName?.takeIf { it.isNotBlank() }, null, service, female)
            else HomeCare.list(type, null, null, district, upazilaId, service, female)
        }
            .onSuccess { list = it }
            .onFailure { errorMsg = it.message }
        loading = false
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (me?.approvedBadge == type) item { MyHomeCareCard(onEditChamber) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterBar(
                    areaLabel = if (nearMe) tr("Near me", "আমার কাছে") else areaText(district, upazilaId),
                    onAreaClick = { showArea = true },
                    filterCount = listOfNotNull(service, if (female) "f" else null).size,
                    onFilterClick = { showFilter = true },
                    active = listOfNotNull(
                        service?.let { sv -> ActiveFilter(HomeServices.label(sv)) { service = null } },
                        if (female) ActiveFilter(tr("Female only", "শুধু মহিলা")) { female = false } else null,
                    ),
                )
                SmallNote(
                    if (type == "nurse") {
                        tr(
                            "Call and agree on the time and fee. The app takes no money. For injection or saline, keep the doctor's prescription ready.",
                            "ফোন করে সময় আর ফি ঠিক করে নিন, অ্যাপ কোনো টাকা নেয় না। ইনজেকশন বা স্যালাইনের জন্য ডাক্তারের প্রেসক্রিপশন রাখুন।",
                        )
                    } else {
                        tr("Call and agree on the time and fee. The app takes no money.", "ফোন করে সময় আর ফি ঠিক করে নিন, অ্যাপ কোনো টাকা নেয় না।")
                    },
                )
            }
        }
        item {
            InfoText(infoMsg)
            ErrorText(errorMsg)
        }
        when {
            loading -> item { LoadingBox() }
            list.isEmpty() -> item {
                Column(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyState(
                        if (type == "doctor") R.drawable.ic_stethoscope else R.drawable.ic_medical_services,
                        if (type == "doctor") tr("No doctor who visits homes here yet", "এখানে বাসায় যান এমন ডাক্তার এখনো নেই")
                        else tr("No nurse who visits homes here yet", "এখানে বাসায় যান এমন নার্স এখনো নেই"),
                        tr("Try another area or remove a filter. In an emergency call 999.", "অন্য এলাকা দিয়ে বা ফিল্টার সরিয়ে দেখুন। জরুরি হলে ৯৯৯-এ ফোন করুন।"),
                    )
                    if (service != null || female) {
                        TextButton(onClick = { service = null; female = false }) { Text(tr("Remove filters", "ফিল্টার সরান")) }
                    }
                }
            }
            else -> items(list, key = { it.uid }) { p ->
                HomeProCard(
                    p,
                    onCall = {
                        called = called + p.uid
                        scope.launch { runCatching { HomeCare.called(p.uid) } }
                        callPhone(context, p.phone)
                    },
                    // ফোনের ১ ঘণ্টা পর থেকে (সার্ভার জানায়); তালিকা আবার খুললে বাটন আসে
                    onRate = if (p.canRate) ({ rating = p }) else null,
                    onReport = { reporting = p },
                )
            }
        }
        item { EmergencyNotice() }
    }

    if (showArea) {
        AreaSheet(
            district, upazilaId, null, { d, u, _ -> district = d; upazilaId = u }, onDismiss = { showArea = false },
            nearMe = if (hasGps) nearMe else null, onNearMe = { nearMe = it },
        )
    }
    if (showFilter) {
        FilterSheet(tr("Filter", "ফিল্টার"), onDismiss = { showFilter = false }, onClear = { service = null; female = false }) {
            FilterChoice(tr("Service", "সেবা"), HomeServices.of(type), service, { HomeServices.label(it) }) { service = it }
            FilterSwitch(tr("Female only", "শুধু মহিলা"), female) { female = it }
        }
    }
    rating?.let { p ->
        StarRatingDialog(
            title = tr("Rate ", "রেটিং: ") + p.name,
            onDismiss = { rating = null },
            onSubmit = { stars, comment ->
                HomeCare.rate(p.uid, stars, comment)
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
                Moderation.report("homecare", p.uid, reason = reason)
                reporting = null
                infoMsg = tr("Reported. An admin will review it.", "রিপোর্ট করা হয়েছে। অ্যাডমিন দেখবেন।")
            },
        )
    }
}

@Composable
internal fun HomeProCard(p: HomePro, onCall: () -> Unit, onRate: (() -> Unit)?, onReport: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProviderAvatar(p.type)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (p.degrees.isNotBlank()) Text(p.degrees, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (p.specialties.isNotEmpty()) {
                        Text(p.specialties.joinToString(", ") { Specialties.label(it) }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                p.km?.let {
                    Tag(tr("$it km", "${num(it.toString())} কিমি"), MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
            Text(p.services.joinToString(" · ") { HomeServices.label(it) }, style = MaterialTheme.typography.bodyMedium)
            IconLine(R.drawable.ic_location_on, p.coverage)
            if (p.schedule.isNotBlank()) IconLine(R.drawable.ic_schedule, p.schedule)
            if (p.fee.isNotBlank()) IconLine(R.drawable.ic_info, tr("Fee: ", "ফি: ") + p.fee)
            RatingLine(p.rating, p.ratingCount)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onCall) { ButtonContent(false, tr("Call", "ফোন করুন") + " " + num(p.phone), R.drawable.ic_call) }
                Spacer(Modifier.weight(1f))
                if (onRate != null) TextButton(onClick = onRate) { Text(if (p.myRating > 0) tr("Your rating ★${p.myRating}", "আপনার রেটিং ★${num(p.myRating)}") else tr("Rate", "রেটিং")) }
                IconButton(onClick = onReport) { AppIcon(R.drawable.ic_flag, tr("Report", "রিপোর্ট")) }
            }
        }
    }
}

/** ব্যাজধারী ডাক্তার/নার্স নিজে: চালু আছে কি না, আর অ্যাপ থেকে কতবার ফোন এসেছে */
@Composable
private fun MyHomeCareCard(onEditChamber: () -> Unit) {
    var st by remember { mutableStateOf<JSONObject?>(null) }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { st = runCatching { HomeCare.stats() }.getOrNull() }
    val s = st ?: return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr("Your home care", "আপনার বাসায় সেবা"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            when {
                s.optBoolean("blocked") -> {
                    Text(
                        tr(
                            "Turned off by the Lifolo team after a report. You can appeal.",
                            "রিপোর্টের পর Lifolo টিম বন্ধ করেছে। আপিল করতে পারেন।",
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    AppealButton("homecare")
                }
                s.optBoolean("enabled") -> {
                    Text(tr("You are in the list — patients call you directly.", "আপনি তালিকায় আছেন — রোগীরা সরাসরি ফোন করবেন।"), style = MaterialTheme.typography.bodyMedium)
                    CallStatsBlock(s, expanded) { expanded = !expanded }
                    OutlinedButton(onClick = onEditChamber) { ButtonContent(false, tr("Change settings", "সেটিং বদলান"), R.drawable.ic_edit) }
                }
                else -> {
                    Text(
                        tr("Do you visit patients at home? Turn it on so patients nearby can call you.", "রোগীর বাসায় যান? চালু করুন, কাছের রোগীরা সরাসরি ফোন করবেন।"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    FilledTonalButton(onClick = onEditChamber) { ButtonContent(false, tr("Turn on home care", "বাসায় সেবা চালু করুন"), R.drawable.ic_home_health) }
                }
            }
        }
    }
}

/** বাসায় সেবার শর্তে সম্মতি (ডাক্তার/নার্স চালু করার সময়) */
@Composable
fun HomeCareTermsCheck(checked: Boolean, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Column(Modifier.weight(1f)) {
            Text(
                tr(
                    "I accept the home care terms, and agree to show this number to patients",
                    "বাসায় সেবার শর্ত মেনে নিচ্ছি, আর এই নম্বরটা রোগীদের দেখাতে রাজি",
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.clickable { onChange(!checked) },
            )
            Text(
                tr("Read the terms", "শর্তগুলো পড়ুন"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { openUrl(context, TERMS_URL) },
            )
        }
    }
}
