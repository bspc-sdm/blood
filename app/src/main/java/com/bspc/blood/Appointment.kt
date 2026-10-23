@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bspc.blood


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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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
    Column(Modifier.fillMaxSize()) {
        // শিরোনামের পাশে "বাসায় সেবা" বাটন (আগে নিচে ভাসমান ছিল; ২.২০.১)
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr("Appointment", "অ্যাপয়েন্টমেন্ট"),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            // ছোট টিল বাটন (অ্যাপের মূল রং)
            androidx.compose.material3.Button(
                onClick = { showHome = true },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier.height(32.dp),
            ) {
                AppIcon(R.drawable.ic_home_health, null, Modifier.size(16.dp), MaterialTheme.colorScheme.onPrimary)
                Text(
                    tr("Home care", "বাসায় সেবা"),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 6.dp),
                )
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
    // নিজের চেম্বার বা বাসায় সেবা এখানে নেই — "আমার কাজ" ট্যাবে (২.২০.০)
}

@Composable
private fun ProviderList(type: String, profile: Profile) {
    val context = LocalContext.current
    remember { Areas.ensure(context) }
    // শুরুতে নিজের জেলা; আজ/আগামীকাল, এখন চেম্বারে, বিশেষজ্ঞতা, ফি, হাসপাতালের নাম, লিঙ্গ — শেষবারেরটা (এই ফোনে, ধরনপ্রতি)
    var filter by remember(type) { mutableStateOf(DoctorFilterPrefs.load(context, type, CareFilter(district = profile.placeName))) }
    fun setFilter(f: CareFilter) {
        filter = f
        DoctorFilterPrefs.save(context, type, f)
    }
    var query by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var list by remember { mutableStateOf<List<Provider>>(emptyList()) }
    var facets by remember(type) { mutableStateOf<ProviderFacets?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var pickHospital by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Provider?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    var showArea by remember { mutableStateOf(false) }
    var showFilter by remember { mutableStateOf(false) }
    // হাসপাতালের নাম লিখতে লিখতে প্রতিটা অক্ষরে সার্ভারে নয় — থামার পরে
    var hqText by remember(type) { mutableStateOf(filter.hospitalQ) }
    LaunchedEffect(hqText) {
        if (hqText != filter.hospitalQ) {
            kotlinx.coroutines.delay(400)
            setFilter(filter.copy(hospitalQ = hqText))
        }
    }

    LaunchedEffect(type, filter, reload) {
        loading = true
        errorMsg = null
        runCatching { Appointments.providersPage(type, if (OnlineGate.on(OnlineGate.CHAMBER)) filter else filter.copy(online = false)) }
            .onSuccess { list = it.items; if (it.facets != null) facets = it.facets }
            .onFailure { errorMsg = it.message }
        loading = false
    }

    val q = query.trim()
    val shown = list
        .filter { it.uid != AuthRepository.currentUid }
        .filter { q.isEmpty() || it.name.contains(q, true) || it.degrees.contains(q, true) || it.chambers.any { c -> c.label.contains(q, true) } }

    val feeLabel = { n: Int -> tr("Up to ৳" + String.format(java.util.Locale.US, "%,d", n), "৳" + num(String.format(java.util.Locale.US, "%,d", n)) + " পর্যন্ত") }
    val dayLabel = { d: String -> if (d == "tomorrow") tr("Tomorrow", "আগামীকাল") else tr("Sitting today", "আজ বসছেন") }
    val genderLabel = { g: String -> if (g == "female") tr("Female doctor", "নারী ডাক্তার") else tr("Male doctor", "পুরুষ ডাক্তার") }
    // অনলাইন (ভিডিও) চেম্বার: ফিচার সুইচ online_chamber (বন্ধ থাকলে ছাঁকনি আর ব্যাজ নেই, আগের মতো)
    val onlineOn = OnlineGate.chamber()
    val active = listOfNotNull(
        filter.day?.let { d -> ActiveFilter(dayLabel(d)) { setFilter(filter.copy(day = null)) } },
        if (filter.openNow) ActiveFilter(tr("In chamber now", "এখন চেম্বারে")) { setFilter(filter.copy(openNow = false)) } else null,
        filter.specialty?.let { sp -> ActiveFilter(Specialties.label(sp)) { setFilter(filter.copy(specialty = null)) } },
        filter.maxFee?.let { n -> ActiveFilter(feeLabel(n)) { setFilter(filter.copy(maxFee = null)) } },
        filter.hospital?.let { h -> ActiveFilter(h.label) { setFilter(filter.copy(hospital = null)) } },
        filter.hospitalQ.trim().takeIf { it.isNotEmpty() }?.let { t -> ActiveFilter(tr("Hospital: ", "হাসপাতাল: ") + t) { hqText = ""; setFilter(filter.copy(hospitalQ = "")) } },
        filter.gender?.let { g -> ActiveFilter(genderLabel(g)) { setFilter(filter.copy(gender = null)) } },
        if (filter.online && onlineOn) ActiveFilter(tr("Online (video)", "অনলাইন (ভিডিও)")) { setFilter(filter.copy(online = false)) } else null,
    )
    // এক চাপের ছাঁকনি (চালু না থাকলে): আজ · আগামীকাল · এখন চেম্বারে · ৳৫০০ পর্যন্ত · বেশি থাকা ৩টা বিশেষজ্ঞতা
    val quick: List<Pair<String, () -> Unit>> = buildList {
        if (filter.day == null) {
            add(dayLabel("today") to { setFilter(filter.copy(day = "today")) })
            add(dayLabel("tomorrow") to { setFilter(filter.copy(day = "tomorrow")) })
        }
        if (!filter.openNow) add(tr("In chamber now", "এখন চেম্বারে") to { setFilter(filter.copy(openNow = true)) })
        if (!filter.online && type == "doctor" && onlineOn) add(tr("Online (video)", "অনলাইন (ভিডিও)") to { setFilter(filter.copy(online = true)) })
        if (filter.maxFee == null) add(feeLabel(500) to { setFilter(filter.copy(maxFee = 500)) })
        if (filter.specialty == null) facets?.specialties?.take(3)?.forEach { (code, _) -> add(Specialties.label(code) to { setFilter(filter.copy(specialty = code)) }) }
    }
    val clearAll = {
        hqText = ""
        setFilter(filter.copy(hospital = null, specialty = null, day = null, openNow = false, maxFee = null, hospitalQ = "", gender = null, online = false))
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            // এক সারি: এলাকা · ফিল্টার · নাম খোঁজা; নিচে এক সারিতে চালু ছাঁকনি (✕) আর এক চাপের ছাঁকনি
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterBar(
                    areaLabel = areaText(filter.district, filter.upazilaId, filter.unionId),
                    onAreaClick = { showArea = true },
                    filterCount = active.size,
                    onFilterClick = { showFilter = true },
                    search = query,
                    onSearch = { query = it },
                )
                if (active.isNotEmpty() || quick.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        active.forEach { f ->
                            androidx.compose.material3.InputChip(
                                selected = true,
                                onClick = f.onRemove,
                                label = { Text(f.label, maxLines = 1) },
                                trailingIcon = { AppIcon(R.drawable.ic_close, tr("Remove", "সরান"), Modifier.size(16.dp)) },
                            )
                        }
                        quick.forEach { (label, on) ->
                            FilterChip(selected = false, onClick = on, label = { Text(label, maxLines = 1) })
                        }
                    }
                }
            }
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
                    if (active.isNotEmpty()) {
                        TextButton(onClick = clearAll) { Text(tr("Remove all filters", "সব ছাঁকনি সরান")) }
                    } else if (filter != CareFilter()) {
                        TextButton(onClick = { setFilter(CareFilter()) }) { Text(tr("Search everywhere", "সব জায়গায় খুঁজুন")) }
                    }
                }
            }
            else -> items(shown, key = { it.uid }) { p -> ProviderCard(p, if (onlineOn) filter else filter.copy(online = false)) { detail = p } }
        }
    }

    if (showArea) {
        AreaSheet(
            filter.district, filter.upazilaId, filter.unionId,
            // এলাকা বদলালে আগের হাসপাতাল আর মেলে না
            { d, u, n -> setFilter(filter.copy(district = d, upazilaId = u, unionId = n, hospital = null)) },
            onDismiss = { showArea = false },
            allowAllDistricts = true,
            showUnion = true,
        )
    }
    if (showFilter) {
        FilterSheet(tr("Filter", "ফিল্টার"), onDismiss = { showFilter = false }, onClear = if (active.isNotEmpty()) clearAll else null) {
            FilterChoice(tr("Day", "দিন"), listOf("today", "tomorrow"), filter.day, dayLabel) { setFilter(filter.copy(day = it)) }
            FilterSwitch(tr("In chamber now", "এখন চেম্বারে"), filter.openNow) { setFilter(filter.copy(openNow = it)) }
            if (type == "doctor" && onlineOn) FilterSwitch(tr("Online (video) only", "শুধু অনলাইন (ভিডিও)"), filter.online) { setFilter(filter.copy(online = it)) }
            SpecialtyChoice(
                options = facets?.specialties?.map { it.first } ?: Specialties.of(type),
                count = { code -> facets?.specialties?.firstOrNull { it.first == code }?.second },
                selected = filter.specialty,
            ) { setFilter(filter.copy(specialty = it)) }
            Text(tr("Hospital / chamber", "হাসপাতাল / চেম্বার"), style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = hqText,
                onValueChange = { hqText = it.take(60) },
                placeholder = { Text(tr("Type a hospital name", "হাসপাতালের নাম লিখুন")) },
                leadingIcon = { AppIcon(R.drawable.ic_search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { pickHospital = true }) {
                    ButtonContent(false, filter.hospital?.label ?: tr("Choose from list", "তালিকা থেকে বেছে নিন"), R.drawable.ic_local_hospital)
                }
                if (filter.hospital != null) TextButton(onClick = { setFilter(filter.copy(hospital = null)) }) { Text(tr("Remove", "সরান")) }
            }
            FilterChoice(tr("Fee", "ফি"), DoctorFilterPrefs.FEE_STEPS.map { it.toString() }, filter.maxFee?.toString(), { feeLabel(it.toInt()) }) {
                setFilter(filter.copy(maxFee = it?.toIntOrNull()))
            }
            // লিঙ্গ: তালিকায় লেখা থাকলে তবেই
            if (facets?.gender == true || filter.gender != null) {
                FilterChoice(tr("Doctor", "ডাক্তার"), listOf("female", "male"), filter.gender, genderLabel) { setFilter(filter.copy(gender = it)) }
            }
        }
    }
    if (pickHospital) {
        HospitalPickerDialog(
            initialDistrict = filter.district ?: profile.placeName,
            initialUpazilaId = filter.upazilaId,
            onPick = {
                setFilter(filter.copy(hospital = it, district = it.district))
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

/** বিশেষজ্ঞতা: তালিকায় যেগুলো আছে (সংখ্যাসহ), অনেক হলে খোঁজার ঘর */
@Composable
private fun SpecialtyChoice(options: List<String>, count: (String) -> Int?, selected: String?, onSelect: (String?) -> Unit) {
    var q by remember { mutableStateOf("") }
    val all = if (selected != null && selected !in options) listOf(selected) + options else options
    val shown = all.filter { q.isBlank() || Specialties.label(it).contains(q.trim(), true) || it.contains(q.trim(), true) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(tr("Specialty", "বিশেষজ্ঞতা"), style = MaterialTheme.typography.titleSmall)
        if (options.size > 8) {
            OutlinedTextField(
                value = q,
                onValueChange = { q = it.take(40) },
                placeholder = { Text(tr("Search specialty", "বিশেষজ্ঞতা খুঁজুন")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            shown.forEach { o ->
                val n = count(o)
                FilterChip(
                    selected = selected == o,
                    onClick = { onSelect(if (selected == o) null else o) },
                    label = { Text(Specialties.label(o) + if (n != null) " (" + num(n) + ")" else "") },
                )
            }
        }
        if (shown.isEmpty()) Text(tr("No match", "মেলেনি"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ProviderCard(p: Provider, filter: CareFilter, onClick: () -> Unit) {
    // ছাঁকনিতে মেলা চেম্বার (সার্ভারের matched) আগে, নইলে এলাকার সাথে মেলা চেম্বার
    val chamber = (if (filter.online) p.chambers.firstOrNull { it.online } else null) ?: p.matched?.firstOrNull()?.let { p.chambers.getOrNull(it) } ?: p.chambers.firstOrNull { c ->
        (filter.hospital == null || c.hospitalId == filter.hospital.id) &&
            (filter.district == null || c.district == filter.district) &&
            (filter.upazilaId == null || c.upazilaId == filter.upazilaId)
    } ?: p.chambers.firstOrNull()
    // তালিকার কার্ডে: এখন চেম্বারে / আজ ৪টা (সবুজ) — অন্য দিনে কিছু নয়
    val st = chamber?.let { ChamberClock.state(it) }
    DirectoryCard(
        title = p.name,
        icon = Badges.icon(p.type),
        photo = p.photo,
        subtitle = listOf(p.degrees, p.specialties.joinToString(", ") { Specialties.label(it) }).filter { it.isNotBlank() }.joinToString(" · "),
        status = when (st?.kind) {
            ChamberStateKind.NOW -> CardStatus(tr("In chamber now", "এখন চেম্বারে"), true)
            ChamberStateKind.TODAY -> CardStatus(tr("Today", "আজ") + if (st.start.isNotBlank()) " " + ChamberClock.shortTime(st.start) else "", true)
            else -> null
        },
        rating = p.rating,
        ratingCount = p.ratingCount,
        details = if (chamber == null) emptyList() else listOf(
            (if (chamber.online) R.drawable.ic_online_video else R.drawable.ic_local_hospital) to chamber.label,
            R.drawable.ic_schedule to chamber.timeText,
            R.drawable.ic_payments to chamber.feeText,
        ),
        tags = listOfNotNull(
            // অনলাইন (ভিডিও) চেম্বার থাকলে "অনলাইন" ব্যাজ
            if (OnlineGate.chamber() && p.chambers.any { it.online }) tr("Online", "অনলাইন") else null,
            if (p.canBook) tr("Appointment in app", "অ্যাপে অ্যাপয়েন্টমেন্ট") else null,
            if (p.homeVisit?.enabled == true) tr("Home visit", "বাসায় যান") else null,
        ),
        note = if (p.chambers.size > 1) tr("+${p.chambers.size - 1} more chamber(s)", "আরও ${num(p.chambers.size - 1)}টা চেম্বার") else null,
        onClick = onClick,
    )
}

/** ডাক্তার/নার্সের বিস্তারিত: সব চেম্বার, সিরিয়ালের ফোন, অ্যাপয়েন্টমেন্ট, হোম ভিজিট */
@Composable
fun ProviderDetailDialog(p: Provider, profile: Profile, onDismiss: () -> Unit, onBooked: () -> Unit, preview: Boolean = false) {
    val context = LocalContext.current
    var bookingIndex by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    var reporting by remember { mutableStateOf(false) }
    var reported by remember { mutableStateOf(false) }

    // একই বিস্তারিত শিট (Cards.kt): প্রতিটা চেম্বার আলাদা ভাগে, তারপর বাসায় সেবা, পরিচিতি
    val hv = p.homeVisit?.takeIf { it.enabled && it.phone.isNotBlank() }
    // ২.২৭.০ নিচের বার: অ্যাপয়েন্টমেন্ট নেওয়া যায় এমন চেম্বার একটা হলে সেটাই সরাসরি (ভরাট), কয়েকটা হলে কোন চেম্বার জিজ্ঞেস করে;
    // সিরিয়ালের ফোন সব চেম্বারে একই হলে পাশে "ফোন" — তখন ভাগের ভেতরে সেই বাটন আর আলাদা নেই
    val bookable = if (p.appointments) p.chambers.indices.filter { p.chambers[it].appointments } else emptyList()
    val serialPhones = p.chambers.map { it.serialPhone }.filter { it.isNotBlank() }.distinct()
    val pinnedCall = serialPhones.singleOrNull()
    var choosing by remember { mutableStateOf(false) }
    val book: (Int) -> Unit = { i -> if (!preview) LoginGate.require(tr("Log in to request an appointment", "অ্যাপয়েন্টমেন্ট চাইতে লগইন করুন")) { bookingIndex = i } }
    val sections = buildList {
        p.chambers.forEachIndexed { i, c ->
            add(
                DetailSection(
                    c.label,
                    if (c.online) listOf(
                        // অনলাইন: ঠিকানা নেই — ভিডিও কলে; ফির bKash নম্বর বুকিংয়ের পরে
                        R.drawable.ic_online_video to tr("Video call in the app — no need to travel", "অ্যাপে ভিডিও কলে — কোথাও যেতে হবে না"),
                        R.drawable.ic_schedule to c.timeText,
                        R.drawable.ic_payments to if (c.noFee) c.feeText else listOf(c.feeText, tr("by bKash after booking", "বুকিংয়ের পরে bKash-এ")).filter { it.isNotBlank() }.joinToString(" · "),
                        // নোট শুধু বুকিং করা রোগীরা দেখেন (সার্ভারের নিয়ম) — এখানে নয়
                    ) else listOf(
                        R.drawable.ic_location_on to c.area,
                        R.drawable.ic_info to if (c.room.isNotBlank()) tr("Room: ", "রুম: ") + c.room else "",
                        R.drawable.ic_schedule to c.timeText,
                        R.drawable.ic_payments to c.feeText,
                        R.drawable.ic_call to if (c.serialPhone.isNotBlank()) tr("Serial by phone: ", "সিরিয়াল ফোনে: ") + num(c.serialPhone) else "",
                    ),
                    listOfNotNull(
                        // নিজের দেখা (preview): বাটন দেখায়, কাজ করে না
                        if (c.serialPhone.isNotBlank() && pinnedCall == null) CardAction(tr("Call for serial", "সিরিয়ালের জন্য ফোন"), R.drawable.ic_call) { if (!preview) callPhone(context, c.serialPhone) } else null,
                        if (i in bookable && bookable.size > 1) CardAction(tr("Appointment at this chamber", "এই চেম্বারে অ্যাপয়েন্টমেন্ট"), R.drawable.ic_calendar_month) { book(i) } else null,
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
                        R.drawable.ic_schedule to hv.timeText,
                        R.drawable.ic_payments to if (hv.noFee) tr("No fee", "ফি নেই") else if (hv.fee.isNotBlank()) tr("Fee: ", "ফি: ") + hv.fee else "",
                    ),
                    listOf(
                        CardAction(tr("Call to visit home", "বাসায় আসতে ফোন"), R.drawable.ic_call) {
                            if (!preview) {
                                scope.launch { runCatching { HomeCare.called(p.uid) } }
                                callPhone(context, hv.phone)
                            }
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
        primary = pinnedCall?.let { ph -> CardAction(tr("Call for serial", "সিরিয়ালের জন্য ফোন"), R.drawable.ic_call) { if (!preview) callPhone(context, ph) } },
        secondary = null,
        main = when {
            bookable.size == 1 -> CardAction(tr("Appointment", "অ্যাপয়েন্টমেন্ট"), R.drawable.ic_calendar_month) { book(bookable[0]) }
            bookable.size > 1 -> CardAction(tr("Appointment", "অ্যাপয়েন্টমেন্ট"), R.drawable.ic_calendar_month) { if (!preview) choosing = true }
            else -> null
        },
        // ভুয়া বা খারাপ আচরণের ডাক্তার/নার্স — অ্যাডমিন দেখে তালিকা বন্ধ করতে পারেন
        onReport = if (p.uid != AuthRepository.currentUid && !reported) ({ reporting = true }) else null,
        onDismiss = onDismiss,
        // ২.৩৩.০: নামের নিচেই চেম্বার বাছাই — চাপলে সেই চেম্বারেই অ্যাপয়েন্টমেন্ট ("কোন চেম্বারে?" ছাড়া)
        header = { ChamberPicker(p, bookable, onBook = book, onCall = { ph -> if (!preview) callPhone(context, ph) }) },
        footer = {
            if (preview) SmallNote(tr("This is how patients see you. Save to update it.", "রোগীরা আপনাকে এভাবেই দেখবেন। বদল দেখাতে সেভ করুন।"))
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

    if (choosing) {
        // "কোন চেম্বারে?" (২.৩৩.০): প্রতিটা চেম্বার স্পষ্ট চাপযোগ্য কার্ড (চাপলে ঢেউ) — নাম · উপজেলা, পরের বসার দিন-সময়, ফি,
        // ডানে "বেছে নিন ›"; সামনে বসার দিন না থাকলে ধূসর আর চাপা যায় না
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text(tr("Which chamber?", "কোন চেম্বারে?")) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    bookable.forEach { i ->
                        val c = p.chambers[i]
                        val st = remember(c) { ChamberClock.state(c) }
                        val off = st.kind == ChamberStateKind.NONE || ChamberClock.firstBookableDay(c) == null
                        val cs = MaterialTheme.colorScheme
                        Surface(
                            onClick = { choosing = false; book(i) },
                            enabled = !off,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                            color = cs.surfaceContainerHigh,
                            border = androidx.compose.foundation.BorderStroke(1.dp, cs.outlineVariant),
                            modifier = Modifier.fillMaxWidth().then(if (off) Modifier.alpha(0.5f) else Modifier),
                        ) {
                            Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(c.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    Text(ChamberClock.place(c), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                                    Text(
                                        if (off) tr("No upcoming days", "সামনে বসার দিন নেই")
                                        else (if (st.kind == ChamberStateKind.NOW) "" else tr("Next: ", "পরের সময়: ")) + ChamberClock.nextSlotText(st),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (off) FontWeight.Normal else FontWeight.SemiBold,
                                        color = if (!off && st.good) cs.tertiary else cs.onSurfaceVariant,
                                    )
                                    if (c.feeText.isNotBlank()) Text(c.feeText, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                                }
                                if (!off) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(tr("Choose", "বেছে নিন"), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = cs.primary)
                                    AppIcon(R.drawable.ic_chevron_right, null, Modifier.size(20.dp), cs.primary)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choosing = false }) { Text(tr("Cancel", "বাতিল")) } },
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

/**
 * নামের নিচে চেম্বার বাছাই (২.৩৩.০, পারভেজ): প্রতিটা চেম্বার ছোট চাপযোগ্য কার্ড — ছোট নাম, আজকের অবস্থা
 * ("আজ বসবেন ৪টা–৯টা" / "আজ বন্ধ · আগামীকাল ১০টা"), উপজেলা · ফি। অ্যাপয়েন্টমেন্ট নেন এমন চেম্বারে চাপলে সরাসরি বুকিং;
 * না নিলে সিরিয়ালের ফোন (থাকলে)। নিচের বিস্তারিত ভাগগুলো তথ্যের জন্য থাকে।
 */
@Composable
private fun ChamberPicker(p: Provider, bookable: List<Int>, onBook: (Int) -> Unit, onCall: (String) -> Unit) {
    if (p.chambers.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (bookable.isNotEmpty()) tr("Choose a chamber to book", "চেম্বার বেছে অ্যাপয়েন্টমেন্ট নিন") else tr("Chambers", "চেম্বার"),
            style = MaterialTheme.typography.labelLarge,
            color = cs.onSurfaceVariant,
        )
        p.chambers.forEachIndexed { i, c ->
            val st = remember(c) { ChamberClock.state(c) }
            val can = i in bookable && st.kind != ChamberStateKind.NONE
            val tel = c.serialPhone.takeIf { i !in bookable && it.isNotBlank() }
            val extra = when {
                i in bookable -> null
                tel != null -> tr("Serial by phone", "সিরিয়াল ফোনে")
                else -> tr("No appointment in app", "অ্যাপে অ্যাপয়েন্টমেন্ট নেই")
            }
            val content: @Composable () -> Unit = {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp), color = cs.primaryContainer, modifier = Modifier.size(36.dp)) {
                        Box(contentAlignment = Alignment.Center) { AppIcon(if (c.online) R.drawable.ic_online_video else R.drawable.ic_local_hospital, null, Modifier.size(20.dp), cs.onPrimaryContainer) }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(ChamberClock.shortName(c), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        if (st.text.isNotBlank()) {
                            Text(st.text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = if (st.good) cs.tertiary else cs.onSurfaceVariant)
                        }
                        Text(
                            listOfNotNull(ChamberClock.place(c).takeIf { it.isNotBlank() }, ChamberClock.feeShort(c).takeIf { it.isNotBlank() }, extra).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                    if (can || tel != null) {
                        Spacer(Modifier.width(8.dp))
                        Surface(shape = androidx.compose.foundation.shape.CircleShape, color = cs.primary, modifier = Modifier.size(36.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                AppIcon(if (can) R.drawable.ic_calendar_month else R.drawable.ic_call, null, Modifier.size(18.dp), cs.onPrimary)
                            }
                        }
                    }
                }
            }
            val shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
            val border = androidx.compose.foundation.BorderStroke(1.dp, cs.outlineVariant)
            when {
                can -> Surface(onClick = { onBook(i) }, shape = shape, color = cs.surfaceContainerLowest, border = border, modifier = Modifier.fillMaxWidth()) { content() }
                tel != null -> Surface(onClick = { onCall(tel) }, shape = shape, color = cs.surfaceContainerLowest, border = border, modifier = Modifier.fillMaxWidth()) { content() }
                else -> Surface(shape = shape, color = cs.surfaceContainerLowest, border = border, modifier = Modifier.fillMaxWidth()) { content() }
            }
        }
    }
}

@Composable
private fun BookDialog(provider: Provider, chamberIndex: Int, onDismiss: () -> Unit, onBooked: () -> Unit) {
    val scope = rememberCoroutineScope()
    val chamber = provider.chambers[chamberIndex]
    // চেম্বার বেছে এলে পরের বসার দিন আগে থেকে বাছা (বদলানো যায়) — এক চাপ কম
    var date by remember { mutableStateOf<Long?>(ChamberClock.firstBookableDay(chamber)) }
    var note by remember { mutableStateOf("") }
    var showDate by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    // কার জন্য: নিজে, অথবা পরিবারের সদস্য (যাঁর নিজের অ্যাকাউন্ট নেই)
    var family by remember { mutableStateOf<List<FamilyMember>>(emptyList()) }
    var memberId by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { runCatching { CareApi.family() }.onSuccess { family = it } }

    FormDialog(
        title = provider.name,
        onDismiss = { if (!busy) onDismiss() },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (chamber.online) {
                    // অনলাইন: জায়গা নেই — ভিডিও কলে; ফি পরে bKash-এ
                    IconLine(R.drawable.ic_online_video, tr("Online (video) — no address needed", "অনলাইন (ভিডিও) — ঠিকানা লাগবে না"))
                } else {
                    IconLine(R.drawable.ic_local_hospital, chamber.label)
                }
                IconLine(R.drawable.ic_schedule, chamber.timeText)
                // লাইফলো পে: এই চেম্বারে সিরিয়ালের অগ্রিম (pay_chamber_advance)
                if (!chamber.online && chamber.advance > 0 && PayGate.rememberOn(PayGate.CHAMBER_ADVANCE)) {
                    IconLine(R.drawable.ic_payments, tr("Advance for the serial: ", "সিরিয়ালের অগ্রিম: ") + PayText.money(chamber.advance) + tr(" — pay by bKash/Nagad after booking", " — বুকিংয়ের পর বিকাশ/নগদে"))
                }
                if (chamber.online) {
                    SmallNote(
                        tr(
                            "Once confirmed you'll see the doctor's bKash number to send the fee. At the time, join the video call from the app.",
                            "নিশ্চিত হলে ফি পাঠানোর জন্য ডাক্তারের bKash নম্বর দেখবেন। সময় হলে অ্যাপ থেকেই ভিডিও কলে যুক্ত হবেন।",
                        ),
                    )
                }
                if (family.isNotEmpty()) {
                    Text(tr("For whom", "কার জন্য"), style = MaterialTheme.typography.labelLarge)
                    ChipRows(listOf("") + family.map { it.id }, setOf(memberId), label = { id ->
                        if (id.isBlank()) (CareMode.name.value.ifBlank { tr("Me", "আমি") }) else family.firstOrNull { it.id == id }?.name.orEmpty()
                    }) { memberId = it }
                }
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
                            runCatching { Appointments.book(provider.uid, chamberIndex, d, note.trim(), if (chamber.online) "online" else chamber.hospitalId?.takeIf { it.isNotBlank() } ?: chamber.hospitalName, memberId.ifBlank { null }) }
                                .onSuccess { r -> PayEntry.afterBooking(r, chamber.online); onBooked() }
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
    // অনলাইনে ফি পাঠানো/দেওয়া অ্যাপয়েন্টমেন্ট বাতিল/না করতে কারণ লাগে (সার্ভার: reason_required) — (id, কাজ)
    var needReason by remember { mutableStateOf<Pair<String, String>?>(null) }

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
                .onFailure { if ((it as? ServerError)?.code == "reason_required") needReason = id to action else errorMsg = it.message }
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
                // ডাক্তার: আজকের সিরিয়াল চালানো — শুরু, পরের রোগী, বিরতি, দেরির উত্তর (Queue.kt; আজ সিরিয়াল না থাকলে কিছু নয়)
                if (role == "provider") item { QueuePanel(null, refreshKey = reload) }
                // ফি নেওয়া আর রসিদ (InvoiceForms.kt)
                if (role == "provider") item { ChamberFeesButton() }
                if (role == "provider") item { BizPaymentsButton("chamber", null) }
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
                                    if (a.online) {
                                        Spacer(Modifier.width(6.dp))
                                        OnlineTag()
                                    }
                                    if (a.pay.advanceDue) {
                                        Spacer(Modifier.width(6.dp))
                                        AdvanceDueTag(a.pay)
                                    }
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
                                // আজকের নিশ্চিত সিরিয়াল: এখন কত চলছে, আগে কতজন, কখন আসবেন, "দেরি হবে" (Queue.kt)
                                if (a.role == "patient") PatientQueueCard(a)
                                Text(a.note, style = MaterialTheme.typography.bodyMedium)
                                if (a.providerNote.isNotBlank()) {
                                    Text(tr("Note: ", "নোট: ") + a.providerNote, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
                                }
                                // ফি ও রসিদ: ডাক্তার "ফি নিন"/"রসিদ" (inv_chamber; অনলাইনের রসিদ bKash নিশ্চিত হলে নিজেই), রোগী "রসিদ" (my_expenses)
                                if (a.role == "provider") ChamberFeeAction(a) else ReceiptLink(a.invoiceId)
                                // সিরিয়ালের অগ্রিম (লাইফলো পে) — অনলাইনের ফি নিচের প্যানেলে
                                if (a.role == "patient" && !a.online) PayRefButton(a.id, PayRules.ADVANCE)
                                // ফোন · অডিও কল · ভিডিও কল · মেসেজ (CallButtons.kt) — অ্যাপের যোগাযোগ নিশ্চিত অ্যাপয়েন্টমেন্টে,
                                // সময়ের ৩০ মিনিট আগে থেকে ৩ ঘণ্টা পর পর্যন্ত
                                ContactActions(
                                    CallCtx.APPOINTMENT, a.id, peerName = a.otherName, phone = a.otherPhone.ifBlank { null },
                                    phoneLabel = num(a.otherPhone), appAllowed = CallWindows.canCall(a.status, a.date, a.start),
                                    onMessage = { openChat(CallCtx.APPOINTMENT, a.id) },
                                )
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
                                // অনলাইন (ভিডিও): ফি (রোগী পাঠান, ডাক্তার মেলান) আর "ভিডিও কলে যুক্ত হোন" — OnlineChamber.kt
                                if (a.online) {
                                    if (a.role == "patient") PatientOnlinePanel(a.id, a.status, a.date, a.start, a.pay, onChanged = { reload++ })
                                    else StaffOnlinePanel(a.id, a.status, a.date, a.start, a.pay, onChanged = { reload++ })
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    needReason?.let { (id, action) ->
        OnlineCancelReasonDialog(onDismiss = { needReason = null }) { reason -> needReason = null; act(id, action, reason) }
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
                ) + (if (type == "doctor") listOf(PillTab(tr("Prescriptions", "প্রেসক্রিপশন"), R.drawable.ic_medication)) else emptyList()) +
                    PillTab(tr("Setup", "সেটআপ"), R.drawable.ic_settings_outline),
                { tab = it },
            )
            // ডাক্তারের ট্যাব: বুকিং, রোগী, প্রেসক্রিপশন, সেটআপ; পুষ্টিবিদ/ট্রেইনারের প্রেসক্রিপশন নেই
            val rxTab = if (type == "doctor") 2 else -1
            when (tab) {
                0 -> AppointmentsList(role = "provider", title = "", onBack = null, onPendingCount = { pending = it })
                // ডাক্তার: সব রোগী এক জায়গায়, নামের পাশে প্রেসক্রিপশন লেখা; অন্যদের আগের মতো
                1 -> if (type == "doctor") DoctorPatientsTab() else PatientsList()
                rxTab -> RxDoctorTab()
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
    // অনলাইন চেম্বারের bKash নম্বর বদলাতে সাম্প্রতিক লগইন লাগে (401 reauth_required — PAYMENTS.md)
    var reauth by remember { mutableStateOf(false) }
    // হোম ভিজিটের দূরত্ব হিসাবের জন্য GPS লোকেশন লাগে (শুধু জেলা বেছে নিলে জেলার মাঝখান ধরা হয়)
    var gpsPlace by remember { mutableStateOf(if (profile.locationSource == "gps") profile.placeName else null) }
    var preview by remember { mutableStateOf(false) }
    // ডাক্তার: নতুন চেম্বারের ধরন (চেম্বারে / অনলাইন) — শুধু online_chamber চালু থাকলে
    var choosingType by remember { mutableStateOf(false) }
    val onlineOn = OnlineGate.chamber()

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
                    chambers.addAll(p.chambers.filter { it.hospitalId != null || it.online })
                    p.chambers.firstOrNull { it.hospitalId == null && !it.online }?.let { old ->
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
            // রোগীরা তালিকায় চাপলে যেমন দেখবেন (সেভ না করেও, এখনকার বদলসহ)
            OutlinedButton(onClick = { preview = true }, modifier = Modifier.fillMaxWidth()) {
                ButtonContent(false, tr("See how patients see it", "রোগীরা যেমন দেখবেন"), R.drawable.ic_info)
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
                    // ডাক্তার: "চেম্বারে" / "অনলাইন (ভিডিও)"; চেম্বারে ফিরলে হাসপাতাল বেছে নিতে হয়
                    onMode = if (type == "doctor" && onlineOn) ({ on -> if (on) chambers[i] = OnlineChambers.toOnline(c) else pickFor = i }) else null,
                    onlineAllowed = chambers.none { it.online && it !== c },
                )
            }
            // জায়গায় সর্বোচ্চ ৩টা, তার বাইরে অনলাইন একটা (ডাক্তার, online_chamber চালু থাকলে)
            if (chambers.count { !it.online } < 3 || (type == "doctor" && onlineOn && chambers.none { it.online })) {
                // ডাক্তার: হাসপাতালের তালিকা থেকে; পুষ্টিবিদ/ট্রেইনার: নিজের জায়গা লিখে (হাসপাতালে বসেন না)
                OutlinedButton(onClick = { if (type == "doctor" && onlineOn) choosingType = true else if (type == "doctor") pickFor = -1 else if (chambers.size < 3) chambers.add(Chamber.customNew(profile.placeName ?: "")) }, modifier = Modifier.fillMaxWidth()) {
                    ButtonContent(false, tr("Add chamber", "চেম্বার যোগ করুন"), R.drawable.ic_add)
                }
            }
            }

            if (homeCapable) run {
                if (!nurse) HorizontalDivider()
                SectionTitle(tr("Home care", "বাসায় সেবা"))
                // রিপোর্টের পর বন্ধ হলে কারণ আর আপিল, চালু থাকলে ফোনের হিসাব
                MyHomeCareCard(null)
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
                    Text(tr("When you visit", "কখন যেতে পারেন"), style = MaterialTheme.typography.titleSmall)
                    TimeSlotsEditor(home.times.ifEmpty { listOf(ChamberTime()) }) { home = home.copy(times = it) }
                    OutlinedTextField(
                        value = home.schedule,
                        onValueChange = { home = home.copy(schedule = it.take(120)) },
                        label = { Text(tr("Note (optional)", "নোট (ঐচ্ছিক)")) },
                        placeholder = { Text(tr("e.g. Call an hour before", "যেমন: এক ঘণ্টা আগে ফোন দিন")) },
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
                        chambers.count { it.online } > 1 -> tr("Keep only one online chamber", "অনলাইন চেম্বার একটাই রাখা যায়")
                        chambers.any { ch -> ch.slots.any { it.days.isEmpty() } } -> tr("Choose the days for each chamber", "প্রতিটা চেম্বারে কোন কোন বার বসেন বেছে নিন")
                        chambers.any { ch -> ch.slots.any { it.start.isBlank() || it.end.isBlank() || it.end == it.start } } ->
                            tr("Set the start and end time for each chamber", "প্রতিটা চেম্বারে শুরু আর শেষের সময় দিন")
                        !nurse && ChamberTimes.clash(chambers) != null -> ChamberTimes.clash(chambers)!!.let { (x, y) ->
                            if (x === y) tr("Two times in \"${x.label}\" overlap on the same day", "\"${x.label}\" চেম্বারে একই বারে দুটো সময় মিলে যাচ্ছে")
                            else tr("\"${x.label}\" and \"${y.label}\" overlap on the same day and time", "\"${x.label}\" আর \"${y.label}\" একই বারে একই সময়ে পড়েছে — একসাথে দুই জায়গায় বসা যায় না")
                        }
                        chambers.any { it.fee.isBlank() && !it.noFee } ->
                            tr("Enter the fee for each chamber, or tick \"No fee\"", "প্রতিটা চেম্বারে ফি দিন, অথবা \"ফি চাই না\" টিক দিন")
                        chambers.any { OnlineChambers.problem(it) != null } -> chambers.firstNotNullOf { OnlineChambers.problem(it) }
                        home.enabled && home.services.isEmpty() -> tr("Choose the services you provide at home", "বাসায় কোন কোন সেবা দেন বেছে নিন")
                        home.enabled && home.times.ifEmpty { listOf(ChamberTime()) }.any { it.days.isEmpty() } -> tr("Choose the days you visit homes", "কোন কোন বার বাসায় যান বেছে নিন")
                        home.enabled && home.times.any { it.start.isBlank() || it.end.isBlank() || it.end == it.start } ->
                            tr("Set the start and end time for home visits", "বাসায় যাওয়ার শুরু আর শেষের সময় দিন")
                        home.enabled && ChamberTimes.clashSlots(home.times) -> tr("Two home-visit times overlap on the same day", "বাসায় যাওয়ার দুটো সময় একই বারে মিলে যাচ্ছে")
                        home.enabled && home.fee.isBlank() && !home.noFee ->
                            tr("Enter the home care fee, or tick \"No fee\"", "বাসায় সেবার ফি দিন, অথবা \"ফি চাই না\" টিক দিন")
                        // ডেমোর নার্সের নম্বর 0120000xxxx শুধু ডেমো অ্যাকাউন্টে চলে
                        home.enabled && !Rules.isMobile(home.phone) ->
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
                                    if ((it as? ServerError)?.code == "phone_unverified") verifyPhone = true
                                    else if (PayReauth.needed(it)) reauth = true
                                    else errorMsg = it.message
                                }
                            busy = false
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { ButtonContent(busy, tr("Save", "সেভ করুন")) }
            if (reauth) PayReauthDialog(onDismiss = { reauth = false }) {
                reauth = false
                errorMsg = tr("Logged in again — tap Save once more.", "আবার লগইন হয়েছে — আরেকবার \"সেভ করুন\" চাপুন।")
            }
        }
    }

    if (preview) {
        ProviderDetailDialog(
            Provider(
                AuthRepository.currentUid ?: "", type, profile.name, specialties.toList(), degrees.trim(), gender, listed, takes,
                if (nurse) emptyList() else chambers.toList(), if (homeCapable) home.copy(phone = home.phone.ifBlank { " " }) else null, 0.0, 0,
                photo = Photo.thumbFromAvatar(profile.photo) ?: "",
            ),
            profile,
            onDismiss = { preview = false },
            onBooked = {},
            preview = true,
        )
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
                    // অনলাইন থেকে চেম্বারে ফিরলে bKash/নোট বাদ
                    chambers[idx] = OnlineChambers.toInPerson(cur).copy(
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
    if (choosingType) {
        ChamberTypeDialog(
            onlineAllowed = chambers.none { it.online },
            inPersonAllowed = chambers.count { !it.online } < 3,
            onInPerson = { choosingType = false; pickFor = -1 },
            onOnline = { choosingType = false; chambers.add(Chamber.onlineNew().copy(appointments = true)) },
            onDismiss = { choosingType = false },
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
internal fun ChamberEditor(
    c: Chamber,
    onChange: (Chamber) -> Unit,
    onChangeHospital: () -> Unit,
    onRemove: () -> Unit,
    /** ডাক্তার: চেম্বারের ধরন বদল (true = অনলাইন); null = ধরন বাছাই নেই (পুষ্টিবিদ/ট্রেইনার) */
    onMode: ((Boolean) -> Unit)? = null,
    onlineAllowed: Boolean = true,
) {

    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onMode != null) ChamberModeChips(c.online, onlineAllowed, onMode)
            if (c.online) {
                // অনলাইন (ভিডিও): হাসপাতাল, ঠিকানা, রুম, সিরিয়ালের ফোন নেই
                OnlineChamberHeader(onRemove)
            } else if (c.custom) {
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
            TimeSlotsEditor(c.slots) { onChange(c.withSlots(it)) }
            // অনলাইনে নোট আলাদা (রোগীদের জন্য নোট, OnlineChamberFields)
            if (!c.online) OutlinedTextField(
                value = c.schedule,
                onValueChange = { onChange(c.copy(schedule = it.take(120))) },
                label = { Text(tr("Note (optional)", "নোট (ঐচ্ছিক)")) },
                placeholder = { Text(tr("e.g. Closed on public holidays", "যেমন: সরকারি ছুটির দিন বন্ধ")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (c.online) {
                // ফি, bKash নম্বর, নোট (OnlineChamber.kt)
                OnlineChamberFields(c, onChange)
            } else {
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
            // রোগীপ্রতি গড় সময় — সিরিয়ালের আনুমানিক সময়ে (আগের হিসাব কম থাকলে); খালি = ১০ মিনিট
            if (c.appointments) {
                OutlinedTextField(
                    value = c.patientMin?.let { num(it) } ?: "",
                    onValueChange = { v ->
                        val n = v.map { ch -> if (ch in '০'..'৯') '0' + (ch - '০') else ch }.filter { it.isDigit() }.joinToString("").take(3).toIntOrNull()
                        onChange(c.copy(patientMin = n?.takeIf { it > 0 }?.coerceAtMost(120)))
                    },
                    label = { Text(tr("Avg. time per patient (min)", "রোগীপ্রতি গড় সময় (মিনিট)")) },
                    placeholder = { Text(num(10)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
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

/** বার আর শুরু–শেষের ভাগ (চেম্বার আর বাসায় সেবা): প্রতিটা ভাগে বার বাছা, সময়, আর "অন্য বারে আলাদা সময়" */
@Composable
internal fun TimeSlotsEditor(slots: List<ChamberTime>, onChange: (List<ChamberTime>) -> Unit) {
    // কোন ভাগের কোন সময় বাছা হচ্ছে: (ভাগের ক্রম, ১ শুরু / ২ শেষ)
    var pickTime by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    pickTime?.let { (i, which) ->
        val slot = slots.getOrNull(i) ?: return@let
        val cur = (if (which == 1) slot.start else slot.end).split(":")
        TimePickerPopup(
            cur.getOrNull(0)?.toIntOrNull() ?: if (which == 1) 17 else 21,
            cur.getOrNull(1)?.toIntOrNull() ?: 0,
            onPicked = { h, m ->
                val t = Weekdays.hhmm(h, m)
                onChange(slots.mapIndexed { j, x -> if (j != i) x else if (which == 1) x.copy(start = t) else x.copy(end = t) })
            },
            onDismiss = { pickTime = null },
            title = if (which == 1) tr("Starts at", "শুরু") else tr("Ends at", "শেষ"),
        )
    }
    // বার অনুযায়ী আলাদা সময়: প্রতিটা ভাগে বার আর শুরু–শেষ; "আরেকটা সময়" দিয়ে নতুন ভাগ
    slots.forEachIndexed { i, slot ->
        if (i > 0) HorizontalDivider(Modifier.padding(vertical = 2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (slots.size > 1) tr("Time ${i + 1}: days", "সময় ${num(i + 1)}: কোন কোন বার") else tr("Days", "কোন কোন বার"),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            if (slots.size > 1) {
                IconButton(onClick = { onChange(slots.filterIndexed { j, _ -> j != i }) }, modifier = Modifier.size(32.dp)) {
                    AppIcon(R.drawable.ic_close, tr("Remove this time", "এই সময় সরান"), Modifier.size(18.dp))
                }
            }
        }
        ChipRows(Weekdays.ORDER.map { it.toString() }, slot.days.map { it.toString() }.toSet(), perRow = 4, label = { Weekdays.short(it.toInt()) }) {
            val d = it.toInt()
            val next = slot.copy(days = if (d in slot.days) slot.days - d else slot.days + d)
            onChange(slots.mapIndexed { j, x -> if (j == i) next else x })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { pickTime = i to 1 }) {
                Text(if (slot.start.isBlank()) tr("Start time", "শুরুর সময়") else Weekdays.time(slot.start))
            }
            Text("–")
            OutlinedButton(onClick = { pickTime = i to 2 }) {
                Text(if (slot.end.isBlank()) tr("End time", "শেষের সময়") else Weekdays.time(slot.end))
            }
        }
    }
    if (slots.size < MAX_CHAMBER_TIMES) {
        TextButton(onClick = { onChange(slots + ChamberTime()) }) {
            ButtonContent(false, tr("Different time on other days", "অন্য বারে আলাদা সময়"), R.drawable.ic_add)
        }
    }
}
