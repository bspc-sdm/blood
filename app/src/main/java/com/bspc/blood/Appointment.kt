@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bspc.blood

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

private val PROVIDER_TYPES = listOf("doctor", "nurse")

/** নোটিফিকেশন থেকে সরাসরি হোম নার্স ট্যাব খোলার জন্য */
object CareNav {
    val openHomeNurse = mutableStateOf(false)
}

// ------------------------------------------------------------
// ট্যাব: ডাক্তার / নার্স / হোম নার্স
// ------------------------------------------------------------

@Composable
fun AppointmentTab(
    profile: Profile,
    me: CommunityMe?,
    onOpenMyAppointments: () -> Unit,
    onEditChamber: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(CareNav.openHomeNurse.value) {
        if (CareNav.openHomeNurse.value) {
            tab = 2
            CareNav.openHomeNurse.value = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr("Appointment", "অ্যাপয়েন্টমেন্ট"),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onOpenMyAppointments) {
                AppIcon(R.drawable.ic_calendar_month, tr("My appointments", "আমার অ্যাপয়েন্টমেন্ট"))
            }
        }
        if (me?.approvedBadge in PROVIDER_TYPES) {
            AssistChip(
                onClick = onEditChamber,
                label = { Text(tr("My chambers & profile", "আমার চেম্বার ও প্রোফাইল")) },
                leadingIcon = { AppIcon(R.drawable.ic_edit, null, Modifier.size(18.dp)) },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        TabRow(selectedTabIndex = tab) {
            Tab(
                selected = tab == 0,
                onClick = { tab = 0 },
                text = { Text(tr("Doctor", "ডাক্তার")) },
                icon = { AppIcon(R.drawable.ic_stethoscope, null, Modifier.size(22.dp)) },
            )
            Tab(
                selected = tab == 1,
                onClick = { tab = 1 },
                text = { Text(tr("Nurse", "নার্স")) },
                icon = { AppIcon(R.drawable.ic_medical_services, null, Modifier.size(22.dp)) },
            )
            Tab(
                selected = tab == 2,
                onClick = { tab = 2 },
                text = { Text(tr("Home Nurse", "হোম নার্স")) },
                icon = { AppIcon(R.drawable.ic_home_health, null, Modifier.size(22.dp)) },
            )
        }
        when (tab) {
            0, 1 -> ProviderList(PROVIDER_TYPES[tab], profile)
            else -> HomeNurseTab(profile, me)
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
    var pickSpecialty by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Provider?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }

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
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (filter.hospital == null) {
                    AreaChips(filter.district, filter.upazilaId, filter.unionId, { d, u, n ->
                        filter = filter.copy(district = d, upazilaId = u, unionId = n)
                    })
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = filter.hospital != null,
                        onClick = { if (filter.hospital != null) filter = filter.copy(hospital = null) else pickHospital = true },
                        label = { Text(filter.hospital?.label ?: tr("Hospital", "হাসপাতাল"), maxLines = 1) },
                        leadingIcon = { AppIcon(R.drawable.ic_local_hospital, null, Modifier.size(18.dp)) },
                        trailingIcon = if (filter.hospital != null) ({ AppIcon(R.drawable.ic_close, null, Modifier.size(16.dp)) }) else null,
                    )
                    FilterChip(
                        selected = filter.specialty != null,
                        onClick = { pickSpecialty = true },
                        label = { Text(filter.specialty?.let { Specialties.label(it) } ?: tr("Specialty", "বিশেষজ্ঞতা"), maxLines = 1) },
                        leadingIcon = { AppIcon(R.drawable.ic_tune, null, Modifier.size(18.dp)) },
                    )
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(tr("Search by name", "নাম দিয়ে খুঁজুন")) },
                    leadingIcon = { AppIcon(R.drawable.ic_search, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item { InfoText(infoMsg) }
        when {
            loading -> item { LoadingBox() }
            errorMsg != null -> item { ErrorText(errorMsg) }
            shown.isEmpty() -> item {
                Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyState(
                        if (type == "doctor") R.drawable.ic_stethoscope else R.drawable.ic_medical_services,
                        if (type == "doctor") tr("No doctors found", "কোনো ডাক্তার পাওয়া যায়নি") else tr("No nurses found", "কোনো নার্স পাওয়া যায়নি"),
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
    if (pickSpecialty) {
        SearchPickDialog(
            title = tr("Specialty", "বিশেষজ্ঞতা"),
            items = Specialties.of(type),
            label = { Specialties.label(it) },
            noneLabel = tr("All", "সব"),
            onPick = {
                filter = filter.copy(specialty = it)
                pickSpecialty = false
            },
            onDismiss = { pickSpecialty = false },
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
private fun ProviderAvatar(type: String) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(Badges.icon(type), null, Modifier.size(26.dp), MaterialTheme.colorScheme.onTertiaryContainer)
    }
}

@Composable
private fun ProviderCard(p: Provider, filter: CareFilter, onClick: () -> Unit) {
    // ফিল্টারের সাথে মেলা চেম্বার আগে
    val chamber = p.chambers.firstOrNull { c ->
        (filter.hospital == null || c.hospitalId == filter.hospital.id) &&
            (filter.district == null || c.district == filter.district) &&
            (filter.upazilaId == null || c.upazilaId == filter.upazilaId)
    } ?: p.chambers.firstOrNull()
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(16.dp)) {
            ProviderAvatar(p.type)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(p.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (p.degrees.isNotBlank()) Text(p.degrees, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (p.specialties.isNotEmpty()) {
                    Text(p.specialties.joinToString(", ") { Specialties.label(it) }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
                RatingLine(p.rating, p.ratingCount)
                if (chamber != null) {
                    IconLine(R.drawable.ic_local_hospital, chamber.label)
                    IconLine(R.drawable.ic_schedule, chamber.schedule)
                }
                if (p.chambers.size > 1) {
                    Text(
                        tr("+${p.chambers.size - 1} more chamber(s)", "আরও ${num(p.chambers.size - 1)}টা চেম্বার"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (p.canBook) Tag(tr("Appointment in app", "অ্যাপে অ্যাপয়েন্টমেন্ট"), MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                    if (p.homeVisit?.enabled == true) Tag(tr("Home visit", "বাসায় যান"), MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
        }
    }
}

/** ডাক্তার/নার্সের বিস্তারিত: সব চেম্বার, সিরিয়ালের ফোন, অ্যাপয়েন্টমেন্ট, হোম ভিজিট */
@Composable
fun ProviderDetailDialog(p: Provider, profile: Profile, onDismiss: () -> Unit, onBooked: () -> Unit) {
    val context = LocalContext.current
    var bookingIndex by remember { mutableStateOf<Int?>(null) }
    var homeRequest by remember { mutableStateOf(false) }
    var sentMsg by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(modifier = Modifier.fillMaxSize(), topBar = { BackTopBar(Badges.label(p.type), onDismiss) }) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProviderAvatar(p.type)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        if (p.degrees.isNotBlank()) Text(p.degrees, style = MaterialTheme.typography.bodyMedium)
                        RatingLine(p.rating, p.ratingCount)
                    }
                    BadgeChip(p.type)
                }
                if (p.specialties.isNotEmpty()) {
                    Text(p.specialties.joinToString(" · ") { Specialties.label(it) }, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge)
                }
                InfoText(sentMsg)
                SectionTitle(tr("Chambers", "চেম্বার"))
                p.chambers.forEachIndexed { i, c ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(c.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            if (c.area.isNotBlank()) IconLine(R.drawable.ic_location_on, c.area)
                            if (c.room.isNotBlank()) IconLine(R.drawable.ic_info, tr("Room: ", "রুম: ") + c.room)
                            IconLine(R.drawable.ic_schedule, c.schedule)
                            if (c.fee.isNotBlank()) IconLine(R.drawable.ic_info, tr("Fee: ", "ফি: ") + c.fee)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                                if (p.appointments && c.appointments) {
                                    Button(onClick = { bookingIndex = i }) { ButtonContent(false, tr("Request appointment", "অ্যাপয়েন্টমেন্ট চাই"), R.drawable.ic_calendar_month) }
                                }
                                if (c.serialPhone.isNotBlank()) {
                                    OutlinedButton(onClick = { callPhone(context, c.serialPhone) }) {
                                        ButtonContent(false, tr("Call for serial", "সিরিয়ালের জন্য ফোন"), R.drawable.ic_call)
                                    }
                                }
                            }
                        }
                    }
                }
                val hv = p.homeVisit
                if (p.type == "nurse" && hv != null && hv.enabled) {
                    SectionTitle(tr("Home visit", "বাসায় গিয়ে সেবা"))
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(hv.services.joinToString(", ") { HomeServices.label(it) }, color = MaterialTheme.colorScheme.onTertiaryContainer)
                            Text(
                                tr("Within ${hv.radiusKm} km", "${num(hv.radiusKm)} কিমির মধ্যে") +
                                    if (hv.upazilaIds.isNotEmpty()) " · " + hv.upazilaIds.mapNotNull { Areas.upazila(it)?.label }.joinToString(", ") else "",
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (hv.schedule.isNotBlank()) Text(hv.schedule, color = MaterialTheme.colorScheme.onTertiaryContainer, style = MaterialTheme.typography.bodySmall)
                            if (hv.fee.isNotBlank()) Text(tr("Fee: ", "ফি: ") + hv.fee, color = MaterialTheme.colorScheme.onTertiaryContainer, style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { homeRequest = true }, modifier = Modifier.padding(top = 4.dp)) {
                                ButtonContent(false, tr("Request a home visit", "বাসায় আসার অনুরোধ"), R.drawable.ic_home_health)
                            }
                        }
                    }
                }
                Disclaimer()
            }
        }
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
    if (homeRequest) {
        HomeVisitRequestDialog(
            profile = profile,
            nurse = p,
            onDismiss = { homeRequest = false },
            onSent = {
                homeRequest = false
                sentMsg = it
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

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(provider.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                IconLine(R.drawable.ic_local_hospital, chamber.label)
                IconLine(R.drawable.ic_schedule, chamber.schedule)
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
                        note.isBlank() -> tr("Write the reason for the visit", "কী সমস্যা, সংক্ষেপে লিখুন")
                        else -> null
                    }
                    if (errorMsg == null && d != null) {
                        scope.launch {
                            busy = true
                            runCatching { Appointments.book(provider.uid, chamberIndex, d, note.trim()) }
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
    else -> tr("Cancelled", "বাতিল")
}

@Composable
fun MyAppointmentsScreen(isProvider: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var reload by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var list by remember { mutableStateOf<List<Appointment>>(emptyList()) }
    var confirming by remember { mutableStateOf<Appointment?>(null) }

    LaunchedEffect(reload) {
        loading = true
        runCatching { Appointments.list() }
            .onSuccess { list = it; errorMsg = null }
            .onFailure { errorMsg = it.message }
        loading = false
    }

    fun act(id: String, action: String, note: String = "") {
        scope.launch {
            busyId = id
            runCatching { Appointments.update(id, action, note) }
                .onSuccess { reload++ }
                .onFailure { errorMsg = it.message }
            busyId = null
        }
    }

    val role = if (isProvider && tab == 1) "provider" else "patient"
    val shown = list.filter { it.role == role }
        .sortedWith(compareBy<Appointment> { it.status !in listOf("pending", "confirmed") }.thenByDescending { it.date })

    Scaffold(
        topBar = {
            BackTopBar(tr("My appointments", "আমার অ্যাপয়েন্টমেন্ট"), onBack) {
                IconButton(onClick = { reload++ }) { AppIcon(R.drawable.ic_refresh, tr("Refresh", "রিফ্রেশ")) }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (isProvider) {
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(tr("My bookings", "আমার বুকিং")) })
                    Tab(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        text = {
                            val n = list.count { it.role == "provider" && it.status == "pending" }
                            Text(tr("Patients", "রোগীদের অনুরোধ") + if (n > 0) " (${num(n)})" else "")
                        },
                    )
                }
            }
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
                                IconLine(R.drawable.ic_calendar_month, Rules.formatDate(a.date))
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
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(tr("Confirm appointment", "অ্যাপয়েন্টমেন্ট নিশ্চিত")) },
            text = {
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(200) },
                    label = { Text(tr("Note for patient (optional)", "রোগীর জন্য নোট (ঐচ্ছিক)")) },
                    placeholder = { Text(tr("e.g. Come at 6 PM, serial 12", "যেমন: সন্ধ্যা ৬টায় আসবেন, সিরিয়াল ১২")) },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    act(a.id, "confirm", note.trim())
                }) { Text(tr("Confirm", "নিশ্চিত করুন")) }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text(tr("Cancel", "বাতিল")) } },
        )
    }
}

// ------------------------------------------------------------
// আমার চেম্বার ও প্রোফাইল (ব্যাজধারী ডাক্তার/নার্স)
// ------------------------------------------------------------

@Composable
fun ChamberEditScreen(type: String, profile: Profile, onBack: () -> Unit) {
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
                    p.homeVisit?.let { home = it }
                }
            }
            .onFailure { errorMsg = it.message }
        loading = false
    }

    Scaffold(topBar = { BackTopBar(tr("My chambers & profile", "আমার চেম্বার ও প্রোফাইল"), onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (loading) {
                LoadingBox()
                return@Column
            }
            SwitchRow(tr("Show me in the list", "তালিকায় আমাকে দেখাও"), tr("Patients can find you by area, hospital and specialty.", "রোগীরা এলাকা, হাসপাতাল আর বিশেষজ্ঞতা দিয়ে খুঁজে পাবেন।"), listed) { listed = it }
            SwitchRow(
                tr("Take appointments through the app", "অ্যাপে অ্যাপয়েন্টমেন্ট নেব"),
                tr("If off, patients see your chamber info and serial phone only.", "বন্ধ থাকলে রোগীরা শুধু চেম্বারের তথ্য আর সিরিয়ালের ফোন দেখবেন।"),
                takes,
            ) { takes = it }
            HorizontalDivider()

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
            if (type == "nurse") {
                SmallNote(tr("Patients who ask for a female nurse only notify female nurses.", "রোগী মহিলা নার্স চাইলে শুধু মহিলা নার্সরা নোটিফিকেশন পান।"))
            }
            HorizontalDivider()

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
                OutlinedButton(onClick = { pickFor = -1 }, modifier = Modifier.fillMaxWidth()) {
                    ButtonContent(false, tr("Add chamber", "চেম্বার যোগ করুন"), R.drawable.ic_add)
                }
            }

            if (type == "nurse") {
                HorizontalDivider()
                SectionTitle(tr("Home visit", "বাসায় গিয়ে সেবা"))
                SwitchRow(
                    tr("I go to patients' homes", "আমি রোগীর বাসায় যাই"),
                    tr("Nearby patients can send you requests. Fee is paid in cash; the app takes no money.", "কাছের রোগীরা অনুরোধ পাঠাতে পারবেন। ফি নগদে; অ্যাপ কোনো টাকা নেয় না।"),
                    home.enabled,
                ) { home = home.copy(enabled = it) }
                if (home.enabled) {
                    Text(tr("Services", "কী কী সেবা দেন"), style = MaterialTheme.typography.labelLarge)
                    ChipRows(HomeServices.ALL, home.services.toSet(), perRow = 2, label = { HomeServices.label(it) }) {
                        home = home.copy(services = if (it in home.services) home.services - it else home.services + it)
                    }
                    Text(tr("How far from your location", "আপনার লোকেশন থেকে কত দূর"), style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HomeServices.RADII.forEach { r ->
                            FilterChip(selected = home.radiusKm == r, onClick = { home = home.copy(radiusKm = r) }, label = { Text(tr("$r km", "${num(r)} কিমি")) })
                        }
                    }
                    SmallNote(
                        tr("Your location: ", "আপনার লোকেশন: ") + Districts.label(profile.placeName) +
                            tr(" (change it in your profile)", " (প্রোফাইল থেকে বদলাতে পারেন)"),
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
                        label = { Text(tr("Fee (optional)", "ফি (ঐচ্ছিক)")) },
                        placeholder = { Text(tr("e.g. 300 Tk + transport", "যেমন: ৩০০ টাকা + যাতায়াত")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            ErrorText(errorMsg)
            Button(
                onClick = {
                    errorMsg = when {
                        specialties.isEmpty() -> tr("Choose at least one specialty", "অন্তত একটা বিশেষজ্ঞতা বেছে নিন")
                        chambers.isEmpty() -> tr("Add at least one chamber", "অন্তত একটা চেম্বার যোগ করুন")
                        chambers.any { it.schedule.isBlank() } -> tr("Enter days and time for each chamber", "প্রতিটা চেম্বারে কোন দিন ও কখন বসেন লিখুন")
                        home.enabled && home.services.isEmpty() -> tr("Choose the services you provide at home", "বাসায় কোন কোন সেবা দেন বেছে নিন")
                        else -> null
                    }
                    if (errorMsg == null) {
                        scope.launch {
                            busy = true
                            runCatching {
                                Appointments.save(
                                    listed, takes, specialties.toList(), degrees.trim(), gender, chambers.toList(),
                                    if (type == "nurse") home else null,
                                )
                            }
                                .onSuccess { onBack() }
                                .onFailure { errorMsg = it.message }
                            busy = false
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { ButtonContent(busy, tr("Save", "সেভ করুন")) }
        }
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
        SearchPickDialog(
            title = tr("Upazila", "উপজেলা"),
            items = Areas.upazilasOf(profile.placeName.orEmpty()),
            label = { it.label },
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
private fun ChamberEditor(c: Chamber, onChange: (Chamber) -> Unit, onChangeHospital: () -> Unit, onRemove: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (c.area.isNotBlank()) SmallNote(c.area)
                }
                IconButton(onClick = onChangeHospital) { AppIcon(R.drawable.ic_edit, tr("Change hospital", "হাসপাতাল বদলান")) }
                IconButton(onClick = onRemove) { AppIcon(R.drawable.ic_delete, tr("Remove", "সরান")) }
            }
            OutlinedTextField(
                value = c.schedule,
                onValueChange = { onChange(c.copy(schedule = it.take(120))) },
                label = { Text(tr("Days and time", "কোন দিন, কখন")) },
                placeholder = { Text(tr("e.g. Sat–Thu, 5–9 PM", "যেমন: শনি–বৃহস্পতি, বিকাল ৫–রাত ৯টা")) },
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
                    label = { Text(tr("Fee (optional)", "ফি (ঐচ্ছিক)")) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
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
