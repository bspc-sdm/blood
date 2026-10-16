@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bspc.blood

import androidx.compose.ui.input.nestedscroll.nestedScroll

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

/** যাঁরা "আমার চেম্বার/বাসায় সেবা" পান */
internal val PROVIDER_TYPES = listOf("doctor", "nurse", "nutritionist", "trainer")
/** চেম্বার আর অ্যাপয়েন্টমেন্ট: ডাক্তার, পুষ্টিবিদ, ফিটনেস ট্রেইনার (নার্স শুধু বাসায় সেবা) */
internal val CHAMBER_TYPES = listOf("doctor", "nutritionist", "trainer")
/** বাসায় সেবা: ডাক্তার আর নার্স */
internal val HOME_PROVIDER_TYPES = listOf("doctor", "nurse")

/** নোটিফিকেশন থেকে সরাসরি "বাসায় সেবা" অংশ খোলার জন্য */
object CareNav {
    val openHomeCare = mutableStateOf(false)
}

// ------------------------------------------------------------
// ট্যাব: ডাক্তার / পুষ্টিবিদ / ফিটনেস ট্রেইনার; উপরে ডানে "বাসায় সেবা"
// ------------------------------------------------------------

@Composable
fun AppointmentTab(
    profile: Profile,
    me: CommunityMe?,
    onEditChamber: () -> Unit,
    refreshKey: Int = 0,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showHome by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(CareNav.openHomeCare.value) {
        if (CareNav.openHomeCare.value) {
            showHome = true
            CareNav.openHomeCare.value = false
        }
    }

    // যাচাই করা পেশাজীবী: চেম্বার/প্রোফাইল আগে থেকে থাকলে নিচে ভাসমান বাটন, না থাকলে উপরে + (প্রথমবার)
    val badge = me?.approvedBadge
    val isProvider = badge in PROVIDER_TYPES
    var hasChamber by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(isProvider, refreshKey) {
        hasChamber = if (isProvider) runCatching { Appointments.mine() != null }.getOrNull() else null
    }
    if (showHome) {
        // বাসায় সেবা: উপরের বাটন থেকে খোলা পাতা
        androidx.activity.compose.BackHandler { showHome = false }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(start = 4.dp, end = 16.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { showHome = false }) { AppIcon(R.drawable.ic_arrow_back, tr("Back", "ফিরে যান")) }
                Text(tr("Home care", "বাসায় সেবা"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            HomeCareTab(profile, me, onEditChamber)
        }
        return
    }
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().nestedScroll(FabScroll.connection)) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr("Appointment", "অ্যাপয়েন্টমেন্ট"),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (isProvider && hasChamber == false) {
                IconButton(onClick = onEditChamber) {
                    AppIcon(R.drawable.ic_add, if (badge == "nurse") tr("Turn on home care", "বাসায় সেবা চালু করুন") else tr("Add my chamber", "আমার চেম্বার যোগ করুন"))
                }
            }
            // যাঁর চেম্বার/বাসায় সেবা আছে: নিচের ভাসমান বাটন "আমার চেম্বার", তাই "বাসায় সেবা" উপরে;
            // বাকিদের জন্য "বাসায় সেবা" নিচের ভাসমান বাটনে
            if (isProvider && hasChamber == true) {
                androidx.compose.material3.TextButton(onClick = { showHome = true }) {
                    ButtonContent(false, tr("Home care", "বাসায় সেবা"), R.drawable.ic_home_health)
                }
            }
        }
        PillTabs(
            tab,
            listOf(
                PillTab(tr("Doctor", "ডাক্তার"), R.drawable.ic_stethoscope),
                PillTab(tr("Nutritionist", "পুষ্টিবিদ"), R.drawable.ic_restaurant),
                PillTab(tr("Fitness trainer", "ফিটনেস ট্রেইনার"), R.drawable.ic_fitness_center),
            ),
            { tab = it },
        )
        ProviderList(CHAMBER_TYPES[tab.coerceIn(0, CHAMBER_TYPES.lastIndex)], profile)
    }
    if (isProvider && hasChamber == true) {
        SmallFab(
            if (badge == "nurse") tr("My home care", "আমার বাসায় সেবা") else tr("My chamber", "আমার চেম্বার"),
            when (badge) {
                "nurse" -> R.drawable.ic_home_health
                "nutritionist" -> R.drawable.ic_restaurant
                "trainer" -> R.drawable.ic_fitness_center
                else -> R.drawable.ic_stethoscope
            },
            onEditChamber,
            Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    } else {
        SmallFab(tr("Home care", "বাসায় সেবা"), R.drawable.ic_home_health, { showHome = true }, Modifier.align(Alignment.BottomEnd).padding(16.dp))
    }
    }
}

@Composable
private fun ProviderList(type: String, profile: Profile) {
    val context = LocalContext.current
    remember { Areas.ensure(context) }
    // শুরুতে নিজের জেলা
    var filter by remember(type) { mutableStateOf(CareFilter(district = profile.placeName)) }
    var query by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var list by remember { mutableStateOf<List<Provider>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var pickHospital by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Provider?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    var showArea by remember { mutableStateOf(false) }
    var showFilter by remember { mutableStateOf(false) }

    LaunchedEffect(type, filter, reload) {
        loading = true
        errorMsg = null
        runCatching { Appointments.providers(type, filter) }
            .onSuccess { list = it }
            .onFailure { errorMsg = it.message }
        loading = false
    }

    val q = query.trim()
    val shown = list
        .filter { it.uid != AuthRepository.currentUid }
        .filter { q.isEmpty() || it.name.contains(q, true) || it.degrees.contains(q, true) || it.chambers.any { c -> c.label.contains(q, true) } }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            // এক সারি: এলাকা · ফিল্টার (হাসপাতাল, বিশেষজ্ঞতা) · নাম খোঁজা
            FilterBar(
                areaLabel = areaText(filter.district, filter.upazilaId, filter.unionId),
                onAreaClick = { showArea = true },
                filterCount = listOfNotNull(filter.hospital, filter.specialty).size,
                onFilterClick = { showFilter = true },
                search = query,
                onSearch = { query = it },
                active = listOfNotNull(
                    filter.hospital?.let { h -> ActiveFilter(h.label) { filter = filter.copy(hospital = null) } },
                    filter.specialty?.let { sp -> ActiveFilter(Specialties.label(sp)) { filter = filter.copy(specialty = null) } },
                ),
            )
        }
        item { InfoText(infoMsg) }
        when {
            loading -> item { LoadingBox() }
            errorMsg != null -> item { ErrorText(errorMsg) }
            shown.isEmpty() -> item {
                Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyState(
                        Badges.icon(type),
                        when (type) {
                            "nutritionist" -> tr("No nutritionists found", "কোনো পুষ্টিবিদ পাওয়া যায়নি")
                            "trainer" -> tr("No fitness trainers found", "কোনো ফিটনেস ট্রেইনার পাওয়া যায়নি")
                            else -> tr("No doctors found", "কোনো ডাক্তার পাওয়া যায়নি")
                        },
                        tr("Try a wider area or remove a filter.", "বড় এলাকা দিয়ে বা কোনো ফিল্টার সরিয়ে দেখুন।"),
                    )
                    if (filter != CareFilter()) {
                        TextButton(onClick = { filter = CareFilter() }) { Text(tr("Search everywhere", "সব জায়গায় খুঁজুন")) }
                    }
                }
            }
            else -> items(shown, key = { it.uid }) { p -> ProviderCard(p, filter) { detail = p } }
        }
    }

    if (showArea) {
        AreaSheet(
            filter.district, filter.upazilaId, filter.unionId,
            // এলাকা বদলালে আগের হাসপাতাল আর মেলে না
            { d, u, n -> filter = filter.copy(district = d, upazilaId = u, unionId = n, hospital = null) },
            onDismiss = { showArea = false },
            allowAllDistricts = true,
            showUnion = true,
        )
    }
    if (showFilter) {
        FilterSheet(tr("Filter", "ফিল্টার"), onDismiss = { showFilter = false }, onClear = { filter = filter.copy(hospital = null, specialty = null) }) {
            Text(tr("Hospital", "হাসপাতাল"), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { pickHospital = true }) {
                    ButtonContent(false, filter.hospital?.label ?: tr("Choose hospital", "হাসপাতাল বেছে নিন"), R.drawable.ic_local_hospital)
                }
                if (filter.hospital != null) TextButton(onClick = { filter = filter.copy(hospital = null) }) { Text(tr("Remove", "সরান")) }
            }
            FilterChoice(tr("Specialty", "বিশেষজ্ঞতা"), Specialties.of(type), filter.specialty, { Specialties.label(it) }) {
                filter = filter.copy(specialty = it)
            }
        }
    }
    if (pickHospital) {
        HospitalPickerDialog(
            initialDistrict = filter.district ?: profile.placeName,
            initialUpazilaId = filter.upazilaId,
            onPick = {
                filter = filter.copy(hospital = it, district = it.district)
                pickHospital = false
            },
            onDismiss = { pickHospital = false },
        )
    }
    detail?.let { p ->
        ProviderDetailDialog(
            p,
            profile,
            onDismiss = { detail = null },
            onBooked = {
                detail = null
                infoMsg = tr("Request sent. You'll be notified when it's confirmed.", "অনুরোধ পাঠানো হয়েছে। নিশ্চিত হলে নোটিফিকেশন পাবেন।")
            },
        )
    }
}

@Composable
fun RatingLine(rating: Double, count: Int) {
    if (count <= 0) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppIcon(R.drawable.ic_star_fill, null, Modifier.size(16.dp), MaterialTheme.colorScheme.tertiary)
        Spacer(Modifier.width(4.dp))
        Text(
            num(String.format(java.util.Locale.US, "%.1f", rating)) + " (" + num(count) + ")",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun ProviderAvatar(type: String) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(Badges.icon(type), null, Modifier.size(26.dp), MaterialTheme.colorScheme.onTertiaryContainer)
    }
}

@Composable
internal fun ProviderCard(p: Provider, filter: CareFilter, onClick: () -> Unit) {
    // ফিল্টারের সাথে মেলা চেম্বার আগে
    val chamber = p.chambers.firstOrNull { c ->
        (filter.hospital == null || c.hospitalId == filter.hospital.id) &&
            (filter.district == null || c.district == filter.district) &&
            (filter.upazilaId == null || c.upazilaId == filter.upazilaId)
    } ?: p.chambers.firstOrNull()
    DirectoryCard(
        title = p.name,
        icon = Badges.icon(p.type),
        photo = p.photo,
        subtitle = listOf(p.degrees, p.specialties.joinToString(", ") { Specialties.label(it) }).filter { it.isNotBlank() }.joinToString(" · "),
        rating = p.rating,
        ratingCount = p.ratingCount,
        details = if (chamber == null) emptyList() else listOf(
            R.drawable.ic_local_hospital to chamber.label,
            R.drawable.ic_schedule to chamber.timeText,
            R.drawable.ic_payments to chamber.feeText,
        ),
        tags = listOfNotNull(
            if (p.canBook) tr("Appointment in app", "অ্যাপে অ্যাপয়েন্টমেন্ট") else null,
            if (p.homeVisit?.enabled == true) tr("Home visit", "বাসায় যান") else null,
        ),
        note = if (p.chambers.size > 1) tr("+${p.chambers.size - 1} more chamber(s)", "আরও ${num(p.chambers.size - 1)}টা চেম্বার") else null,
        onClick = onClick,
    )
}

/** ডাক্তার/নার্সের বিস্তারিত: সব চেম্বার, সিরিয়ালের ফোন, অ্যাপয়েন্টমেন্ট, হোম ভিজিট */
@Composable
fun ProviderDetailDialog(p: Provider, profile: Profile, onDismiss: () -> Unit, onBooked: () -> Unit) {
    val context = LocalContext.current
    var bookingIndex by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    var reporting by remember { mutableStateOf(false) }
    var reported by remember { mutableStateOf(false) }

    // একই বিস্তারিত শিট (Cards.kt): প্রতিটা চেম্বার আলাদা ভাগে, তারপর বাসায় সেবা, পরিচিতি
    val hv = p.homeVisit?.takeIf { it.enabled && it.phone.isNotBlank() }
    val sections = buildList {
        p.chambers.forEachIndexed { i, c ->
            add(
                DetailSection(
                    c.label,
                    listOf(
                        R.drawable.ic_location_on to c.area,
                        R.drawable.ic_info to if (c.room.isNotBlank()) tr("Room: ", "রুম: ") + c.room else "",
                        R.drawable.ic_schedule to c.timeText,
                        R.drawable.ic_payments to c.feeText,
                        R.drawable.ic_call to if (c.serialPhone.isNotBlank()) tr("Serial by phone: ", "সিরিয়াল ফোনে: ") + num(c.serialPhone) else "",
                    ),
                    listOfNotNull(
                        if (c.serialPhone.isNotBlank()) CardAction(tr("Call for serial", "সিরিয়ালের জন্য ফোন"), R.drawable.ic_call) { callPhone(context, c.serialPhone) } else null,
                        if (p.appointments && c.appointments) CardAction(tr("Request appointment", "অ্যাপয়েন্টমেন্ট চাই"), R.drawable.ic_calendar_month) { bookingIndex = i } else null,
                    ),
                ),
            )
        }
        if (hv != null) {
            add(
                DetailSection(
                    tr("Home visit", "বাসায় গিয়ে সেবা"),
                    listOf(
                        R.drawable.ic_medical_services to hv.services.joinToString(" · ") { HomeServices.label(it) },
                        R.drawable.ic_location_on to (listOf(tr("Within ${hv.radiusKm} km", "${num(hv.radiusKm)} কিমির মধ্যে")) + hv.upazilaIds.mapNotNull { Areas.upazila(it)?.label }).joinToString(" · "),
                        R.drawable.ic_schedule to hv.schedule,
                        R.drawable.ic_payments to if (hv.noFee) tr("No fee", "ফি নেই") else if (hv.fee.isNotBlank()) tr("Fee: ", "ফি: ") + hv.fee else "",
                    ),
                    listOf(
                        CardAction(tr("Call to visit home", "বাসায় আসতে ফোন"), R.drawable.ic_call) {
                            scope.launch { runCatching { HomeCare.called(p.uid) } }
                            callPhone(context, hv.phone)
                        },
                    ),
                ),
            )
        }
        add(
            DetailSection(
                tr("About", "পরিচিতি"),
                listOf(
                    R.drawable.ic_school to p.degrees,
                    R.drawable.ic_badge to Badges.label(p.type),
                ),
            ),
        )
    }
    DetailSheet(
        title = p.name,
        icon = Badges.icon(p.type),
        photo = p.photo,
        iconTint = MaterialTheme.colorScheme.primary,
        subtitle = p.specialties.joinToString(" · ") { Specialties.label(it) },
        status = null,
        rating = p.rating,
        ratingCount = p.ratingCount,
        meta = null,
        sections = sections,
        primary = null,
        secondary = null,
        // ভুয়া বা খারাপ আচরণের ডাক্তার/নার্স — অ্যাডমিন দেখে তালিকা বন্ধ করতে পারেন
        onReport = if (p.uid != AuthRepository.currentUid && !reported) ({ reporting = true }) else null,
        onDismiss = onDismiss,
        footer = {
            if (reported) InfoText(tr("Reported. An admin will review it.", "রিপোর্ট করা হয়েছে। অ্যাডমিন দেখবেন।"))
            EmergencyNotice("doctor")
        },
    )

    if (reporting) {
        ReasonReportDialog(
            onDismiss = { reporting = false },
            onSubmit = { reason ->
                Moderation.report("provider", p.uid, reason = reason)
                reporting = false
                reported = true
            },
        )
    }

    bookingIndex?.let { i ->
        BookDialog(
            provider = p,
            chamberIndex = i,
            onDismiss = { bookingIndex = null },
            onBooked = {
                bookingIndex = null
                onBooked()
            },
        )
    }
}

@Composable
private fun BookDialog(provider: Provider, chamberIndex: Int, onDismiss: () -> Unit, onBooked: () -> Unit) {
    val scope = rememberCoroutineScope()
    val chamber = provider.chambers[chamberIndex]
    var date by remember { mutableStateOf<Long?>(null) }
    var note by remember { mutableStateOf("") }
    var showDate by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    FormDialog(
        title = provider.name,
        onDismiss = { if (!busy) onDismiss() },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                IconLine(R.drawable.ic_local_hospital, chamber.label)
                IconLine(R.drawable.ic_schedule, chamber.timeText)
                OutlinedButton(onClick = { showDate = true }, modifier = Modifier.fillMaxWidth()) {
                    AppIcon(R.drawable.ic_calendar_month, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(date?.let { Rules.formatDate(it) } ?: tr("Pick a date", "তারিখ বেছে নিন"))
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(300) },
                    label = { Text(tr("Reason for visit", "কী সমস্যা")) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                ErrorText(errorMsg)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val d = date
                    errorMsg = when {
                        d == null -> tr("Pick a date", "তারিখ বেছে নিন")
                        d < Rules.todayUtcMidnight() -> tr("Pick today or a later date", "আজ বা পরের কোনো তারিখ দিন")
                        !chamber.openOn(d) -> tr("The chamber is closed that day. Pick one of its days.", "সেদিন চেম্বার বন্ধ। চেম্বারের বারগুলোর একটা বেছে নিন।")
                        note.isBlank() -> tr("Write the reason for the visit", "কী সমস্যা, সংক্ষেপে লিখুন")
                        else -> null
                    }
                    if (errorMsg == null && d != null) {
                        scope.launch {
                            busy = true
                            runCatching { Appointments.book(provider.uid, chamberIndex, d, note.trim(), chamber.hospitalId?.takeIf { it.isNotBlank() } ?: chamber.hospitalName) }
                                .onSuccess { onBooked() }
                                .onFailure { errorMsg = it.message }
                            busy = false
                        }
                    }
                },
                enabled = !busy,
            ) { ButtonContent(busy, tr("Send request", "অনুরোধ পাঠান")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancel", "বাতিল")) } },
    )

    if (showDate) {
        DatePickerPopup(initial = date ?: Rules.todayUtcMidnight(), onPicked = { date = it }, onDismiss = { showDate = false })
    }
}

// ------------------------------------------------------------
// আমার অ্যাপয়েন্টমেন্ট
// ------------------------------------------------------------

private fun statusLabel(s: String): String = when (s) {
    "pending" -> tr("Waiting", "অপেক্ষায়")
    "confirmed" -> tr("Confirmed", "নিশ্চিত")
    "declined" -> tr("Not accepted", "নেওয়া হয়নি")
    "expired" -> tr("No reply", "উত্তর আসেনি")
    "completed" -> tr("Done", "সম্পন্ন")
    else -> tr("Cancelled", "বাতিল")
}

/** রোগী হিসেবে নিজের নেওয়া অ্যাপয়েন্টমেন্ট (প্রোফাইল → আমার অ্যাপয়েন্টমেন্ট) */
@Composable
fun MyAppointmentsScreen(onBack: () -> Unit) {
    AppointmentsList(role = "patient", title = tr("My appointments", "আমার অ্যাপয়েন্টমেন্ট"), onBack = onBack)
}

/**
 * অ্যাপয়েন্টমেন্টের তালিকা। role: "patient" (নিজে নেওয়া) বা "provider" (নিজের চেম্বারে আসা বুকিং)।
 * onBack null হলে উপরের বার ছাড়া (আমার চেম্বারের ভেতরে)।
 */
@Composable
fun AppointmentsList(role: String, title: String, onBack: (() -> Unit)?, onPendingCount: (Int) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var list by remember { mutableStateOf<List<Appointment>>(emptyList()) }
    var confirming by remember { mutableStateOf<Appointment?>(null) }

    LaunchedEffect(reload) {
        loading = true
        runCatching { Appointments.list() }
            .onSuccess { list = it; errorMsg = null; onPendingCount(it.count { a -> a.role == "provider" && a.status == "pending" }) }
            .onFailure { errorMsg = it.message }
        loading = false
    }

    fun act(id: String, action: String, note: String = "", serial: Int? = null) {
        scope.launch {
            busyId = id
            runCatching { Appointments.update(id, action, note, serial) }
                .onSuccess { reload++ }
                .onFailure { errorMsg = it.message }
            busyId = null
        }
    }

    val shown = list.filter { it.role == role }
        .sortedWith(compareBy<Appointment> { it.status !in listOf("pending", "confirmed") }.thenByDescending { it.date })

    Scaffold(
        topBar = {
            if (onBack != null) {
                BackTopBar(title, onBack) {
                    IconButton(onClick = { reload++ }) { AppIcon(R.drawable.ic_refresh, tr("Refresh", "রিফ্রেশ")) }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { ErrorText(errorMsg) }
                when {
                    loading && list.isEmpty() -> item { LoadingBox() }
                    shown.isEmpty() -> item {
                        Box(Modifier.fillParentMaxHeight(0.7f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            EmptyState(R.drawable.ic_calendar_month, tr("No appointments", "কোনো অ্যাপয়েন্টমেন্ট নেই"), null)
                        }
                    }
                    else -> items(shown, key = { it.id }) { a ->
                        Card(
                            Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        a.otherName.ifBlank { tr("Deleted account", "মুছে ফেলা অ্যাকাউন্ট") },
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f),
                                    )
                                    val active = a.status == "pending" || a.status == "confirmed"
                                    Tag(
                                        statusLabel(a.status),
                                        if (a.status == "confirmed") MaterialTheme.colorScheme.tertiaryContainer
                                        else if (active) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                        if (a.status == "confirmed") MaterialTheme.colorScheme.onTertiaryContainer
                                        else if (active) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconLine(
                                    R.drawable.ic_calendar_month,
                                    Rules.formatDate(a.date) + (if (a.start.isNotBlank()) ", " + tr("from ", "") + Weekdays.time(a.start) + tr("", " থেকে") else ""),
                                )
                                if (a.serial > 0) {
                                    Text(
                                        tr("Serial #", "সিরিয়াল নং ") + num(a.serial),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                if (a.chamberLabel.isNotBlank()) IconLine(R.drawable.ic_local_hospital, a.chamberLabel + if (a.room.isNotBlank()) " · " + a.room else "")
                                Text(a.note, style = MaterialTheme.typography.bodyMedium)
                                if (a.providerNote.isNotBlank()) {
                                    Text(tr("Note: ", "নোট: ") + a.providerNote, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
                                }
                                if (a.otherPhone.isNotBlank()) {
                                    Button(onClick = { callPhone(context, a.otherPhone) }) {
                                        ButtonContent(false, num(a.otherPhone), R.drawable.ic_call)
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (a.role == "provider" && a.status == "pending") {
                                        Button(onClick = { confirming = a }, enabled = busyId == null) {
                                            ButtonContent(busyId == a.id, tr("Confirm", "নিশ্চিত করুন"))
                                        }
                                        TextButton(onClick = { act(a.id, "decline") }, enabled = busyId == null) {
                                            Text(tr("Decline", "না করুন"))
                                        }
                                    } else if (a.status == "pending" || a.status == "confirmed") {
                                        TextButton(onClick = { act(a.id, "cancel") }, enabled = busyId == null) {
                                            Text(tr("Cancel appointment", "অ্যাপয়েন্টমেন্ট বাতিল"))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    confirming?.let { a ->
        var note by remember(a.id) { mutableStateOf("") }
        // নিজের খাতার সিরিয়াল (ফোনে/সরাসরি নেওয়া রোগীসহ); খালি রাখলে অ্যাপ নিজে পরের নম্বর দেয়
        var serial by remember(a.id) { mutableStateOf("") }
        FormDialog(
        title = tr("Confirm appointment", "অ্যাপয়েন্টমেন্ট নিশ্চিত"),
        onDismiss = { confirming = null },
        content = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = serial,
                        onValueChange = { v -> serial = v.filter { it.isDigit() || it in '০'..'৯' }.take(3) },
                        label = { Text(tr("Serial no. (optional)", "সিরিয়াল নম্বর (ঐচ্ছিক)")) },
                        supportingText = { Text(tr("Leave empty and the app gives the next number", "খালি রাখলে অ্যাপ পরের নম্বর দেবে")) },
                        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it.take(200) },
                        label = { Text(tr("Note for patient (optional)", "রোগীর জন্য নোট (ঐচ্ছিক)")) },
                        placeholder = { Text(tr("e.g. Come at 6 PM", "যেমন: সন্ধ্যা ৬টায় আসবেন")) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
        confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    val n = serial.map { c -> if (c in '০'..'৯') '0' + (c - '০') else c }.joinToString("").toIntOrNull()
                    act(a.id, "confirm", note.trim(), n)
                }) { Text(tr("Confirm", "নিশ্চিত করুন")) }
            },
        dismissButton = { TextButton(onClick = { confirming = null }) { Text(tr("Cancel", "বাতিল")) } },
    )
    }
}

// ------------------------------------------------------------
// আমার চেম্বার ও প্রোফাইল (ব্যাজধারী ডাক্তার/নার্স)
// ------------------------------------------------------------

/**
 * "আমার চেম্বার": বুকিং · রোগী · সেটআপ (ডাক্তার, পুষ্টিবিদ, ফিটনেস ট্রেইনার)।
 * নার্সের চেম্বার নেই — শুধু প্রোফাইল আর বাসায় সেবার সেটআপ।
 */
@Composable
fun ChamberHubScreen(type: String, profile: Profile, onBack: () -> Unit) {
    if (type !in CHAMBER_TYPES) {
        ChamberEditScreen(type, profile, onBack)
        return
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var pending by remember { mutableIntStateOf(0) }
    Scaffold(topBar = { BackTopBar(tr("My chamber", "আমার চেম্বার"), onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PillTabs(
                tab,
                listOf(
                    PillTab(tr("Bookings", "বুকিং"), R.drawable.ic_event_outline, R.drawable.ic_event_filled, count = pending),
                    PillTab(tr("Patients", "রোগী"), R.drawable.ic_person_outline),
                    PillTab(tr("Setup", "সেটআপ"), R.drawable.ic_settings_outline),
                ),
                { tab = it },
            )
            when (tab) {
                0 -> AppointmentsList(role = "provider", title = "", onBack = null, onPendingCount = { pending = it })
                1 -> PatientsList()
                else -> ChamberEditScreen(type, profile, onBack, embedded = true)
            }
        }
    }
}

/** নিজের চেম্বারে আসা রোগীরা (একজন একবার): কতবার এসেছেন, শেষ কবে, নিশ্চিত থাকলে ফোন */
@Composable
private fun PatientsList() {
    val context = LocalContext.current
    var list by remember { mutableStateOf<List<Appointment>?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { Appointments.list() }.onSuccess { list = it }.onFailure { errorMsg = it.message }
    }
    data class Pat(val name: String, val count: Int, val last: Long, val phone: String)
    val pats = remember(list) {
        (list ?: emptyList()).filter { it.role == "provider" && it.status in listOf("confirmed", "completed") }
            .groupBy { it.otherKey.ifBlank { it.otherName } }
            .map { (_, a) -> Pat(a.first().otherName, a.size, a.maxOf { it.date }, a.firstOrNull { it.otherPhone.isNotBlank() }?.otherPhone.orEmpty()) }
            .sortedByDescending { it.last }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ErrorText(errorMsg) }
        when {
            list == null && errorMsg == null -> item { LoadingBox() }
            pats.isEmpty() -> item {
                Box(Modifier.fillParentMaxHeight(0.7f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    EmptyState(R.drawable.ic_person_outline, tr("No patients yet", "এখনো কোনো রোগী নেই"), null)
                }
            }
            else -> items(pats) { p ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name.ifBlank { tr("Deleted account", "মুছে ফেলা অ্যাকাউন্ট") }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                tr("${p.count} visit(s) · last ", "${num(p.count)} বার · শেষ ") + Rules.formatDate(p.last),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (p.phone.isNotBlank()) {
                            IconButton(onClick = { callPhone(context, p.phone) }) { AppIcon(R.drawable.ic_call, tr("Call", "ফোন")) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChamberEditScreen(type: String, profile: Profile, onBack: () -> Unit, embedded: Boolean = false) {
    val context = LocalContext.current
    remember { Areas.ensure(context) }
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var listed by remember { mutableStateOf(true) }
    var takes by remember { mutableStateOf(true) }
    val specialties = remember { mutableStateListOf<String>() }
    var degrees by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf("") }
    val chambers = remember { mutableStateListOf<Chamber>() }
    var home by remember { mutableStateOf(HomeVisitSettings()) }
    var pickFor by remember { mutableStateOf<Int?>(null) } // -1 = নতুন চেম্বার
    var pickUpazila by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var oldChamber by remember { mutableStateOf<String?>(null) }
    var homeAgreed by remember { mutableStateOf(false) }
    var verifyPhone by remember { mutableStateOf(false) }
    // হোম ভিজিটের দূরত্ব হিসাবের জন্য GPS লোকেশন লাগে (শুধু জেলা বেছে নিলে জেলার মাঝখান ধরা হয়)
    var gpsPlace by remember { mutableStateOf(if (profile.locationSource == "gps") profile.placeName else null) }

    LaunchedEffect(Unit) {
        runCatching { Appointments.mine() }
            .onSuccess { p ->
                if (p != null) {
                    listed = p.listed
                    takes = p.appointments
                    specialties.addAll(p.specialties)
                    degrees = p.degrees
                    gender = p.gender
                    // আগের ভার্সনের লেখা চেম্বার (হাসপাতালের সাথে যুক্ত নয়) আবার বেছে নিতে হবে
                    chambers.addAll(p.chambers.filter { it.hospitalId != null })
                    p.chambers.firstOrNull { it.hospitalId == null }?.let { old ->
                        oldChamber = old.hospitalName
                    }
                    p.homeVisit?.let {
                        home = it
                        homeAgreed = it.enabled && it.phone.isNotBlank() // নতুন শর্তে (নম্বর দেখানো) আগে সম্মতি দিয়ে থাকলে
                    }
                }
            }
            .onFailure { errorMsg = it.message }
        loading = false
    }

    // নার্সের চেম্বার নেই — শুধু প্রোফাইল আর বাসায় সেবা; পুষ্টিবিদ/ট্রেইনারের বাসায় সেবা নেই — শুধু চেম্বার
    val nurse = type == "nurse"
    val homeCapable = type in HOME_PROVIDER_TYPES
    Scaffold(topBar = {
        if (!embedded) BackTopBar(if (nurse) tr("My home care & profile", "আমার বাসায় সেবা ও প্রোফাইল") else tr("My chambers & profile", "আমার চেম্বার ও প্রোফাইল"), onBack)
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (loading) {
                LoadingBox()
                return@Column
            }
            if (!nurse) {
                SwitchRow(tr("Show me in the list", "তালিকায় আমাকে দেখাও"), null, listed) { listed = it }
                SwitchRow(tr("Take appointments through the app", "অ্যাপে অ্যাপয়েন্টমেন্ট নেব"), null, takes) { takes = it }
                HorizontalDivider()
            }

            SectionTitle(tr("Specialty", "বিশেষজ্ঞতা"))
            ChipRows(Specialties.of(type), specialties.toSet(), perRow = 2, label = { Specialties.label(it) }) {
                if (it in specialties) specialties.remove(it) else if (specialties.size < 5) specialties.add(it)
            }
            OutlinedTextField(
                value = degrees,
                onValueChange = { degrees = it.take(120) },
                label = { Text(tr("Degrees (optional)", "ডিগ্রি (ঐচ্ছিক)")) },
                placeholder = { Text(tr("e.g. MBBS, FCPS (Medicine)", "যেমন: MBBS, FCPS (Medicine)")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(tr("Gender (optional)", "লিঙ্গ (ঐচ্ছিক)"), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("" to tr("Not set", "দেখাব না"), "female" to tr("Female", "মহিলা"), "male" to tr("Male", "পুরুষ")).forEach { (k, l) ->
                    FilterChip(selected = gender == k, onClick = { gender = k }, label = { Text(l) })
                }
            }
            HorizontalDivider()

            if (!nurse) {
            SectionTitle(tr("Chambers (up to 3)", "চেম্বার (সর্বোচ্চ ৩টা)"))
            oldChamber?.let {
                InfoText(
                    tr(
                        "Your earlier chamber \"$it\" now needs to be chosen from the hospital list. Tap Add chamber.",
                        "আগের চেম্বার \"$it\" এখন হাসপাতালের তালিকা থেকে বেছে নিতে হবে। \"চেম্বার যোগ করুন\" চাপুন।",
                    ),
                )
            }
            chambers.forEachIndexed { i, c ->
                ChamberEditor(
                    c,
                    onChange = { chambers[i] = it },
                    onChangeHospital = { pickFor = i },
                    onRemove = { chambers.removeAt(i) },
                )
            }
            if (chambers.size < 3) {
                // ডাক্তার: হাসপাতালের তালিকা থেকে; পুষ্টিবিদ/ট্রেইনার: নিজের জায়গা লিখে (হাসপাতালে বসেন না)
                OutlinedButton(onClick = { if (type == "doctor") pickFor = -1 else chambers.add(Chamber.customNew(profile.placeName ?: "")) }, modifier = Modifier.fillMaxWidth()) {
                    ButtonContent(false, tr("Add chamber", "চেম্বার যোগ করুন"), R.drawable.ic_add)
                }
            }
            }

            if (homeCapable) run {
                if (!nurse) HorizontalDivider()
                SectionTitle(tr("Home care", "বাসায় সেবা"))
                SwitchRow(tr("I visit patients at home", "আমি রোগীর বাসায় গিয়ে সেবা দিই"), null, home.enabled) { on -> home = home.copy(enabled = on, phone = home.phone.ifBlank { profile.phone }) }
                if (home.enabled) {
                    OutlinedTextField(
                        value = home.phone,
                        onValueChange = { home = home.copy(phone = it.filter { c -> c.isDigit() || c == '+' }.take(14)) },
                        label = { Text(tr("Phone patients will call", "রোগীরা যে নম্বরে ফোন করবেন")) },
                        supportingText = { Text(tr("Shown to everyone in the list", "তালিকায় সবাই দেখবেন")) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(tr("Services", "কী কী সেবা দেন"), style = MaterialTheme.typography.labelLarge)
                    ChipRows(HomeServices.of(type), home.services.toSet(), perRow = 2, label = { HomeServices.label(it) }) {
                        home = home.copy(services = if (it in home.services) home.services - it else home.services + it)
                    }
                    Text(tr("How far from your location", "আপনার লোকেশন থেকে কত দূর"), style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HomeServices.RADII.forEach { r ->
                            FilterChip(selected = home.radiusKm == r, onClick = { home = home.copy(radiusKm = r) }, label = { Text(tr("$r km", "${num(r)} কিমি")) })
                        }
                    }
                    if (gpsPlace != null) {
                        SmallNote(tr("Location: ", "লোকেশন: ") + Districts.label(gpsPlace))
                    }
                    GpsButton(
                        if (gpsPlace == null) tr("Use my current location", "বর্তমান লোকেশন দিন") else tr("Update location", "লোকেশন আপডেট"),
                        onLocated = { la, ln, d ->
                            scope.launch {
                                val uid = AuthRepository.currentUid ?: return@launch
                                runCatching { ProfileRepository.saveLocation(uid, la, ln, d, "gps") }
                                    .onSuccess { gpsPlace = d }
                                    .onFailure { errorMsg = it.message }
                            }
                        },
                    )
                    Text(tr("Also these upazilas (optional)", "এই উপজেলাগুলোতেও (ঐচ্ছিক)"), style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        home.upazilaIds.forEach { id ->
                            FilterChip(
                                selected = true,
                                onClick = { home = home.copy(upazilaIds = home.upazilaIds - id) },
                                label = { Text(Areas.upazila(id)?.label ?: "") },
                                trailingIcon = { AppIcon(R.drawable.ic_close, null, Modifier.size(16.dp)) },
                            )
                        }
                        if (home.upazilaIds.size < 10) {
                            AssistChip(onClick = { pickUpazila = true }, label = { Text(tr("Add", "যোগ")) }, leadingIcon = { AppIcon(R.drawable.ic_add, null, Modifier.size(16.dp)) })
                        }
                    }
                    OutlinedTextField(
                        value = home.schedule,
                        onValueChange = { home = home.copy(schedule = it.take(120)) },
                        label = { Text(tr("When you're available", "কখন যেতে পারেন")) },
                        placeholder = { Text(tr("e.g. Every day 8 AM – 10 PM", "যেমন: প্রতিদিন সকাল ৮টা – রাত ১০টা")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = home.fee,
                        onValueChange = { home = home.copy(fee = it.take(60)) },
                        label = { Text(tr("Fee", "ফি")) },
                        placeholder = { Text(tr("e.g. 300 Tk + transport", "যেমন: ৩০০ টাকা + যাতায়াত")) },
                        enabled = !home.noFee,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    NoFeeCheck(home.noFee) { home = home.copy(noFee = it, fee = if (it) "" else home.fee) }
                    HomeCareTermsCheck(homeAgreed) { homeAgreed = it }
                }
            }

            ErrorText(errorMsg)
            Button(
                onClick = {
                    errorMsg = when {
                        specialties.isEmpty() -> tr("Choose at least one specialty", "অন্তত একটা বিশেষজ্ঞতা বেছে নিন")
                        nurse && !home.enabled -> tr("Turn on home care", "বাসায় সেবা চালু করুন")
                        !homeCapable && chambers.isEmpty() -> tr("Add a chamber", "অন্তত একটা চেম্বার যোগ করুন")
                        !nurse && chambers.isEmpty() && !home.enabled ->
                            tr("Add a chamber, or turn on home care", "অন্তত একটা চেম্বার যোগ করুন, অথবা বাসায় সেবা চালু করুন")
                        chambers.any { it.custom && (it.hospitalName.trim().length < 3 || it.district.isBlank() || it.address.trim().length < 5) } ->
                            tr("Write the place name, district and address", "জায়গার নাম, জেলা আর ঠিকানা লিখুন")
                        chambers.any { it.days.isEmpty() } -> tr("Choose the days for each chamber", "প্রতিটা চেম্বারে কোন কোন বার বসেন বেছে নিন")
                        chambers.any { it.start.isBlank() || it.end.isBlank() || it.end == it.start } ->
                            tr("Set the start and end time for each chamber", "প্রতিটা চেম্বারে শুরু আর শেষের সময় দিন")
                        chambers.any { it.fee.isBlank() && !it.noFee } ->
                            tr("Enter the fee for each chamber, or tick \"No fee\"", "প্রতিটা চেম্বারে ফি দিন, অথবা \"ফি চাই না\" টিক দিন")
                        home.enabled && home.services.isEmpty() -> tr("Choose the services you provide at home", "বাসায় কোন কোন সেবা দেন বেছে নিন")
                        home.enabled && home.fee.isBlank() && !home.noFee ->
                            tr("Enter the home care fee, or tick \"No fee\"", "বাসায় সেবার ফি দিন, অথবা \"ফি চাই না\" টিক দিন")
                        home.enabled && !Regex("^(\\+?88)?01[3-9]\\d{8}$").matches(home.phone) ->
                            tr("Enter a valid mobile number for home care", "বাসায় সেবার জন্য সঠিক মোবাইল নম্বর দিন")
                        home.enabled && !homeAgreed -> tr("Please accept the home care terms", "বাসায় সেবার শর্তে সম্মতি দিন")
                        home.enabled && gpsPlace == null && home.upazilaIds.isEmpty() ->
                            tr("Set your current location or choose upazilas for home care", "বাসায় সেবার জন্য বর্তমান লোকেশন দিন অথবা উপজেলা বেছে নিন")
                        else -> null
                    }
                    if (errorMsg == null) {
                        scope.launch {
                            busy = true
                            runCatching {
                                Appointments.save(
                                    listed && !nurse, takes && !nurse, specialties.toList(), degrees.trim(), gender,
                                    if (nurse) emptyList() else chambers.toList(), if (homeCapable) home else HomeVisitSettings(),
                                )
                            }
                                .onSuccess { n ->
                                    if (n > 0) {
                                        android.widget.Toast.makeText(
                                            context,
                                            tr("${num(n)} upcoming appointment(s) no longer fit and were cancelled. Patients were told.",
                                                "সামনের ${num(n)}টা অ্যাপয়েন্টমেন্ট আর মেলে না, তাই বাতিল হয়েছে। রোগীদের জানানো হয়েছে।"),
                                            android.widget.Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                    onBack()
                                }
                                .onFailure {
                                    if ((it as? ServerError)?.code == "phone_unverified") verifyPhone = true else errorMsg = it.message
                                }
                            busy = false
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { ButtonContent(busy, tr("Save", "সেভ করুন")) }
        }
    }

    if (verifyPhone) {
        PhoneVerifyDialog(onDismiss = { verifyPhone = false }, onVerified = {
            verifyPhone = false
            errorMsg = tr("Phone verified. Tap Save again.", "ফোন যাচাই হয়েছে। আবার সেভ চাপুন।")
        })
    }

    pickFor?.let { idx ->
        val cur = chambers.getOrNull(idx)
        HospitalPickerDialog(
            initialDistrict = cur?.district ?: profile.placeName,
            initialUpazilaId = cur?.upazilaId,
            onPick = { h ->
                val fresh = Chamber.from(h)
                if (idx >= 0 && cur != null) {
                    chambers[idx] = cur.copy(
                        hospitalId = fresh.hospitalId, hospitalName = fresh.hospitalName, hospitalNameBn = fresh.hospitalNameBn,
                        district = fresh.district, upazilaId = fresh.upazilaId, unionId = fresh.unionId,
                    )
                } else {
                    chambers.add(fresh.copy(appointments = takes))
                }
                pickFor = null
            },
            onDismiss = { pickFor = null },
        )
    }
    if (pickUpazila) {
        // নিজের জেলা আগে, তারপর কাছের জেলাগুলো (সীমানার কাছে থাকলে পাশের জেলায়ও যান)
        val own = profile.placeName.orEmpty()
        val districts = (listOf(own) + (profile.lat?.let { la -> profile.lng?.let { ln -> Districts.near(la, ln).take(6) } } ?: emptyList()))
            .filter { it.isNotBlank() }.distinct()
        SearchPickDialog(
            title = tr("Upazila", "উপজেলা"),
            items = districts.flatMap { Areas.upazilasOf(it) },
            label = { u -> if (u.district == own) u.label else u.label + " (" + Districts.label(u.district) + ")" },
            onPick = { u ->
                if (u != null && u.id !in home.upazilaIds) home = home.copy(upazilaIds = home.upazilaIds + u.id)
                pickUpazila = false
            },
            onDismiss = { pickUpazila = false },
        )
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) SmallNote(subtitle)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
internal fun ChamberEditor(c: Chamber, onChange: (Chamber) -> Unit, onChangeHospital: () -> Unit, onRemove: () -> Unit) {
    var pickTime by remember { mutableIntStateOf(0) } // ১ শুরু, ২ শেষ
    if (pickTime != 0) {
        val cur = (if (pickTime == 1) c.start else c.end).split(":")
        TimePickerPopup(
            cur.getOrNull(0)?.toIntOrNull() ?: if (pickTime == 1) 17 else 21,
            cur.getOrNull(1)?.toIntOrNull() ?: 0,
            onPicked = { h, m ->
                val t = Weekdays.hhmm(h, m)
                onChange(if (pickTime == 1) c.copy(start = t) else c.copy(end = t))
            },
            onDismiss = { pickTime = 0 },
            title = if (pickTime == 1) tr("Chamber starts at", "চেম্বার শুরু") else tr("Chamber ends at", "চেম্বার শেষ"),
        )
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (c.custom) {
                // নিজের জায়গা: নাম, জেলা, ঠিকানা (অনুমোদন লাগে না; সমস্যা হলে অ্যাডমিন সরান)
                var pickDistrict by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = c.hospitalName,
                        onValueChange = { onChange(c.copy(hospitalName = it.take(100), hospitalNameBn = it.take(100))) },
                        label = { Text(tr("Place name", "জায়গার নাম")) },
                        placeholder = { Text(tr("e.g. Fit Life Gym", "যেমন: ফিট লাইফ জিম")) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onRemove) { AppIcon(R.drawable.ic_delete, tr("Remove", "সরান")) }
                }
                OutlinedButton(onClick = { pickDistrict = true }, modifier = Modifier.fillMaxWidth()) {
                    ButtonContent(false, if (c.district.isBlank()) tr("Choose district", "জেলা বেছে নিন") else Districts.label(c.district), R.drawable.ic_location_on)
                }
                OutlinedTextField(
                    value = c.address,
                    onValueChange = { onChange(c.copy(address = it.take(150))) },
                    label = { Text(tr("Address", "ঠিকানা")) },
                    placeholder = { Text(tr("House, road, area", "বাড়ি, রোড, এলাকা")) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (pickDistrict) {
                    SearchPickDialog(
                        title = tr("District", "জেলা"),
                        items = Districts.ALL,
                        label = { Districts.label(it.bn) },
                        onPick = { d -> if (d != null) onChange(c.copy(district = d.bn, upazilaId = null)); pickDistrict = false },
                        onDismiss = { pickDistrict = false },
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        if (c.area.isNotBlank()) SmallNote(c.area)
                    }
                    IconButton(onClick = onChangeHospital) { AppIcon(R.drawable.ic_edit, tr("Change hospital", "হাসপাতাল বদলান")) }
                    IconButton(onClick = onRemove) { AppIcon(R.drawable.ic_delete, tr("Remove", "সরান")) }
                }
            }
            Text(tr("Days", "কোন কোন বার"), style = MaterialTheme.typography.labelLarge)
            ChipRows(Weekdays.ORDER.map { it.toString() }, c.days.map { it.toString() }.toSet(), perRow = 4, label = { Weekdays.short(it.toInt()) }) {
                val d = it.toInt()
                onChange(c.copy(days = if (d in c.days) c.days - d else c.days + d))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { pickTime = 1 }) {
                    Text(if (c.start.isBlank()) tr("Start time", "শুরুর সময়") else Weekdays.time(c.start))
                }
                Text("–")
                OutlinedButton(onClick = { pickTime = 2 }) {
                    Text(if (c.end.isBlank()) tr("End time", "শেষের সময়") else Weekdays.time(c.end))
                }
            }
            OutlinedTextField(
                value = c.schedule,
                onValueChange = { onChange(c.copy(schedule = it.take(120))) },
                label = { Text(tr("Note (optional)", "নোট (ঐচ্ছিক)")) },
                placeholder = { Text(tr("e.g. Closed on public holidays", "যেমন: সরকারি ছুটির দিন বন্ধ")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = c.room,
                    onValueChange = { onChange(c.copy(room = it.take(60))) },
                    label = { Text(tr("Room (optional)", "রুম (ঐচ্ছিক)")) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = c.fee,
                    onValueChange = { onChange(c.copy(fee = it.take(40))) },
                    label = { Text(tr("Fee", "ফি")) },
                    placeholder = { Text(tr("e.g. 500 Tk", "যেমন: ৫০০ টাকা")) },
                    enabled = !c.noFee,
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            NoFeeCheck(c.noFee) { onChange(c.copy(noFee = it, fee = if (it) "" else c.fee)) }
            OutlinedTextField(
                value = c.serialPhone,
                onValueChange = { onChange(c.copy(serialPhone = it.filter { ch -> ch.isDigit() || ch == '+' }.take(15))) },
                label = { Text(tr("Serial phone (optional)", "সিরিয়ালের ফোন (ঐচ্ছিক)")) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                leadingIcon = { AppIcon(R.drawable.ic_call, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Appointments in app at this chamber", "এই চেম্বারে অ্যাপে অ্যাপয়েন্টমেন্ট"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = c.appointments, onCheckedChange = { onChange(c.copy(appointments = it)) })
            }
        }
    }
}

/** "ফি চাই না" — ফি না নিলে টিক দিতে হয় (ফাঁকা রেখে যাওয়া যায় না) */
@Composable
internal fun NoFeeCheck(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Checkbox(checked = checked, onCheckedChange = onChange)
        Text(tr("No fee", "ফি চাই না"), style = MaterialTheme.typography.bodyMedium)
    }
}
