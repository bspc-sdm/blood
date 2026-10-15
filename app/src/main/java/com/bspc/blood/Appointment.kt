@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bspc.blood

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private val PROVIDER_TYPES = listOf("doctor", "nurse")

// ------------------------------------------------------------
// ট্যাব: ডাক্তার / নার্স তালিকা
// ------------------------------------------------------------

@Composable
fun AppointmentTab(
    profile: Profile,
    me: CommunityMe?,
    onOpenMyAppointments: () -> Unit,
    onEditChamber: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var list by remember { mutableStateOf<List<Provider>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var booking by remember { mutableStateOf<Provider?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    val type = PROVIDER_TYPES[tab]

    LaunchedEffect(type, reload) {
        loading = true
        errorMsg = null
        runCatching { Appointments.providers(type) }
            .onSuccess { list = it }
            .onFailure { errorMsg = it.message }
        loading = false
    }

    val q = query.trim()
    val shown = list
        .filter { it.uid != AuthRepository.currentUid }
        .filter {
            q.isEmpty() || it.name.contains(q, true) || it.specialty.contains(q, true) ||
                it.chamber.contains(q, true) || it.district.contains(q) || Districts.label(it.district).contains(q, true)
        }
        // নিজের জেলার আগে
        .sortedWith(compareBy<Provider> { it.district != profile.placeName }.thenBy { it.name })

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
                label = { Text(tr("My chamber", "আমার চেম্বার")) },
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
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(tr("Search name, specialty or area", "নাম, বিশেষজ্ঞতা বা এলাকা খুঁজুন")) },
                    leadingIcon = { AppIcon(R.drawable.ic_tune, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item { InfoText(infoMsg) }
            when {
                loading -> item { LoadingBox() }
                errorMsg != null -> item { ErrorText(errorMsg) }
                shown.isEmpty() -> item {
                    Box(Modifier.fillParentMaxHeight(0.6f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        EmptyState(
                            if (type == "doctor") R.drawable.ic_stethoscope else R.drawable.ic_medical_services,
                            if (type == "doctor") tr("No doctors yet", "এখনো কোনো ডাক্তার নেই") else tr("No nurses yet", "এখনো কোনো নার্স নেই"),
                            tr("Verified professionals will appear here.", "যাচাই করা পেশাজীবীরা এখানে দেখাবেন।"),
                        )
                    }
                }
                else -> items(shown, key = { it.uid }) { p -> ProviderCard(p) { booking = p } }
            }
        }
    }

    booking?.let { p ->
        BookDialog(
            provider = p,
            onDismiss = { booking = null },
            onBooked = {
                booking = null
                infoMsg = tr("Request sent. You'll be notified when it's confirmed.", "অনুরোধ পাঠানো হয়েছে। নিশ্চিত হলে নোটিফিকেশন পাবেন।")
            },
        )
    }
}

@Composable
private fun ProviderCard(p: Provider, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(16.dp)) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                AppIcon(Badges.icon(p.type), null, Modifier.size(26.dp), MaterialTheme.colorScheme.onTertiaryContainer)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(6.dp))
                    BadgeChip(p.type)
                }
                if (p.specialty.isNotBlank()) Text(p.specialty, style = MaterialTheme.typography.bodyMedium)
                IconLine(R.drawable.ic_local_hospital, "${p.chamber}, ${Districts.label(p.district)}")
                IconLine(R.drawable.ic_schedule, p.schedule)
                if (p.fee.isNotBlank()) IconLine(R.drawable.ic_info, tr("Fee: ", "ফি: ") + p.fee)
            }
        }
    }
}

@Composable
private fun BookDialog(provider: Provider, onDismiss: () -> Unit, onBooked: () -> Unit) {
    val scope = rememberCoroutineScope()
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
                IconLine(R.drawable.ic_schedule, provider.schedule)
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
                            runCatching { Appointments.book(provider.uid, d, note.trim()) }
                                .onSuccess { onBooked() }
                                .onFailure { errorMsg = it.message }
                            busy = false
                        }
                    }
                },
                enabled = !busy,
            ) { Text(tr("Send request", "অনুরোধ পাঠান")) }
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
                                    Text(a.otherName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
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
                                if (a.role == "patient") IconLine(R.drawable.ic_local_hospital, a.chamber)
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
// আমার চেম্বার (ব্যাজধারী ডাক্তার/নার্স)
// ------------------------------------------------------------

@Composable
fun ChamberEditScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var specialty by remember { mutableStateOf("") }
    var chamber by remember { mutableStateOf("") }
    var district by remember { mutableStateOf("") }
    var schedule by remember { mutableStateOf("") }
    var fee by remember { mutableStateOf("") }
    var active by remember { mutableStateOf(true) }
    var picking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching { Appointments.mine() }
            .onSuccess { p ->
                if (p != null) {
                    specialty = p.specialty
                    chamber = p.chamber
                    district = p.district
                    schedule = p.schedule
                    fee = p.fee
                    active = p.active
                }
            }
            .onFailure { errorMsg = it.message }
        loading = false
    }

    BackHandler(enabled = picking) { picking = false }
    if (picking) {
        LocationPicker(
            title = tr("Chamber district", "চেম্বারের জেলা"),
            busy = false,
            errorMsg = null,
            onPicked = { _, _, place, _ ->
                district = place
                picking = false
            },
            onCancel = { picking = false },
        )
        return
    }

    Scaffold(topBar = { BackTopBar(tr("My chamber", "আমার চেম্বার"), onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (loading) {
                LoadingBox()
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Taking appointments", "অ্যাপয়েন্টমেন্ট নিচ্ছি"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Switch(checked = active, onCheckedChange = { active = it })
            }
            OutlinedTextField(
                value = specialty,
                onValueChange = { specialty = it.take(80) },
                label = { Text(tr("Specialty (optional)", "বিশেষজ্ঞতা (ঐচ্ছিক)")) },
                placeholder = { Text(tr("e.g. Medicine, Child specialist", "যেমন: মেডিসিন, শিশু বিশেষজ্ঞ")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = chamber,
                onValueChange = { chamber = it.take(120) },
                label = { Text(tr("Chamber / hospital", "চেম্বার / হাসপাতাল")) },
                leadingIcon = { AppIcon(R.drawable.ic_local_hospital, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
                AppIcon(R.drawable.ic_location_on, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (district.isBlank()) tr("Choose district", "জেলা বেছে নিন") else Districts.label(district))
            }
            OutlinedTextField(
                value = schedule,
                onValueChange = { schedule = it.take(120) },
                label = { Text(tr("Days and time", "কোন দিন, কখন")) },
                placeholder = { Text(tr("e.g. Sat–Thu, 5–9 PM", "যেমন: শনি–বৃহস্পতি, বিকাল ৫–রাত ৯টা")) },
                leadingIcon = { AppIcon(R.drawable.ic_schedule, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = fee,
                onValueChange = { fee = it.take(40) },
                label = { Text(tr("Fee (optional)", "ফি (ঐচ্ছিক)")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ErrorText(errorMsg)
            Button(
                onClick = {
                    errorMsg = when {
                        chamber.isBlank() -> tr("Enter your chamber / hospital", "চেম্বার / হাসপাতালের নাম দিন")
                        district.isBlank() -> tr("Choose a district", "জেলা বেছে নিন")
                        schedule.isBlank() -> tr("Enter your days and time", "কোন দিন ও কখন বসেন লিখুন")
                        else -> null
                    }
                    if (errorMsg == null) {
                        scope.launch {
                            busy = true
                            runCatching { Appointments.save(specialty.trim(), chamber.trim(), district, schedule.trim(), fee.trim(), active) }
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
}
