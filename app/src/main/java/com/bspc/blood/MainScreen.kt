@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bspc.blood

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.background
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.core.content.ContextCompat

internal data class NavItem(val label: String, val selectedIcon: Int, val icon: Int)

@Composable
fun MainScreen(
    profile: Profile,
    tab: Int,
    onTabChange: (Int) -> Unit,
    onOpenRequest: (String) -> Unit,
    onOpenMyRequest: (String) -> Unit,
    onOpenMyRequests: () -> Unit,
    onNewRequest: () -> Unit,
    onEditProfile: () -> Unit,
    onChangeLocation: () -> Unit,
    onProfileChanged: (Profile) -> Unit,
    onLogout: () -> Unit,
    onOpenAdmin: () -> Unit,
    onOpenSettings: () -> Unit,
    community: CommunityMe?,
    homeRefresh: Int,
    communityRefresh: Int,
    onCommunityChanged: (CommunityMe) -> Unit,
    onOpenQuestion: (String) -> Unit,
    onAsk: () -> Unit,
    onOpenTip: (Tip) -> Unit,
    onPostTip: () -> Unit,
    onOpenMyAppointments: () -> Unit,
    onEditChamber: () -> Unit,
    onAccountDeleted: () -> Unit,
    onOpenInbox: () -> Unit = {},
    onOpenAmbulance: () -> Unit = {},
    onOpenMedicine: () -> Unit = {},
    onOpenShop: () -> Unit = {},
    onOpenJobs: (JobsPage) -> Unit = {}, // FEATURE:jobs
) {
    val context = LocalContext.current

    // Android 13+ এ নোটিফিকেশনের অনুমতি চাওয়া
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // এই ফোনের পুশ টোকেন সার্ভারের জন্য প্রোফাইলে রাখা
    LaunchedEffect(Unit) { runCatching { Notifier.syncToken() } }
    LaunchedEffect(Unit) { JobAlarms.sync(context) } // FEATURE:jobs — সামনের ইন্টারভিউর মনে করানো

    // সার্ভার যেন নোটিফিকেশন এই ভাষায় পাঠায়
    val lang = AppPrefs.lang.value
    LaunchedEffect(lang) {
        val uid = AuthRepository.currentUid ?: return@LaunchedEffect
        runCatching { ProfileRepository.saveLang(uid, lang.code) }
    }

    // GPS দিয়ে এলাকা ঠিক করা থাকলে, অ্যাপ খুললে চুপচাপ আপডেট
    LaunchedEffect(Unit) {
        val uid = AuthRepository.currentUid ?: return@LaunchedEffect
        if (profile.locationSource == "gps" && LocationHelper.hasPermission(context)) {
            runCatching {
                val loc = LocationHelper.current(context)
                if (loc != null) {
                    val place = Districts.nearest(loc.first, loc.second).bn
                    ProfileRepository.saveLocation(uid, loc.first, loc.second, place, "gps")
                    onProfileChanged(
                        profile.copy(lat = Geo.round(loc.first), lng = Geo.round(loc.second), placeName = place)
                    )
                }
            }
        }
    }

    // প্রোফাইল এখন নিচের বারে নয়, উপরের ছবিতে চাপলে (Tabs.PROFILE)
    val items = listOf(
        NavItem(tr("Home", "হোম"), R.drawable.ic_nav_home_fill, R.drawable.ic_nav_home),
        NavItem(tr("Appointment", "অ্যাপয়েন্টমেন্ট"), R.drawable.ic_nav_event_fill, R.drawable.ic_nav_event),
        NavItem(tr("Health", "স্বাস্থ্য"), R.drawable.ic_nav_health_fill, R.drawable.ic_nav_health),
        NavItem(tr("Services", "সেবা"), R.drawable.ic_nav_services_fill, R.drawable.ic_nav_services),
        NavItem(tr("Jobs", "চাকরি"), R.drawable.ic_nav_work_fill, R.drawable.ic_nav_work), // FEATURE:jobs
    )
    val avatar = remember(profile.photo) { Photo.decodeAvatar(profile.photo) }

    Scaffold(
        topBar = {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Logo(iconSize = 30.dp)
                Spacer(Modifier.weight(1f))
                BellButton(community?.unread ?: 0, onOpenInbox)
                IconButton(onClick = { onTabChange(Tabs.PROFILE) }) { MenuAvatar(avatar) }
            }
        },
        bottomBar = { ModernNavBar(items, tab, onTabChange) },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                Tabs.HOME -> HomeTab(
                    profile, homeRefresh, onOpenRequest, onOpenMyRequest, onNewRequest, onChangeLocation,
                    onOpenAmbulance = onOpenAmbulance,
                    onOpenMedicine = onOpenMedicine,
                ) { onTabChange(Tabs.PROFILE) }
                Tabs.APPOINTMENT -> AppointmentTab(profile, community, onOpenMyAppointments, onEditChamber)
                Tabs.HEALTH -> HealthTab(communityRefresh, community, onOpenQuestion, onAsk, onOpenTip, onPostTip)
                Tabs.SERVICES -> ServicesTab(profile)
                Tabs.JOBS -> JobsTab(profile, homeRefresh, onOpenJobs) // FEATURE:jobs
                else -> ProfileTab(
                    profile = profile,
                    community = community,
                    onCommunityChanged = onCommunityChanged,
                    onEditProfile = onEditProfile,
                    onOpenMyRequests = onOpenMyRequests,
                    onOpenSettings = onOpenSettings,
                    onProfileChanged = onProfileChanged,
                    onLogout = onLogout,
                    onOpenAdmin = onOpenAdmin,
                    onAccountDeleted = onAccountDeleted,
                )
            }
        }
    }
}

// ------------------------------------------------------------
// হোম
// ------------------------------------------------------------

@Composable
fun HomeTab(
    profile: Profile,
    refreshKey: Int,
    onOpenRequest: (String) -> Unit,
    onOpenMyRequest: (String) -> Unit,
    onNewRequest: () -> Unit,
    onChangeLocation: () -> Unit,
    onOpenAmbulance: () -> Unit = {},
    onOpenMedicine: () -> Unit = {},
    onOpenProfile: () -> Unit,
) {
    var reload by remember { mutableIntStateOf(0) }
    val homeScope = androidx.compose.runtime.rememberCoroutineScope()
    var processingBusy by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var list by remember { mutableStateOf<List<Pair<BloodRequest, Double>>>(emptyList()) }
    var processing by remember { mutableStateOf<List<BloodRequest>>(emptyList()) }
    val groups = profile.effectiveViewGroups

    LaunchedEffect(reload, refreshKey, profile.lat, profile.lng, groups, profile.maxKm) {
        val lat = profile.lat
        val lng = profile.lng
        if (lat == null || lng == null) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        errorMsg = null
        runCatching { RequestRepository.nearbyOpen(lat, lng, groups.toSet(), AuthRepository.currentUid, profile.maxKm) }
            .onSuccess { list = it }
            .onFailure { errorMsg = tr("Couldn't load requests", "রিকোয়েস্ট লোড হয়নি") }
        loading = false
    }

    // যেসব রিকোয়েস্টে রক্ত দিতে রাজি হয়েছেন (প্রসেসিং), কাউন্টডাউন সহ উপরে দেখায়
    LaunchedEffect(reload, refreshKey, profile.commitments) {
        val uid = AuthRepository.currentUid ?: return@LaunchedEffect
        processing = if (profile.commitments.isEmpty()) {
            emptyList()
        } else {
            runCatching { RequestRepository.processing(uid, profile.commitments) }.getOrDefault(processing)
        }
    }
    val processingIds = processing.map { it.id }.toSet()
    val others = list.filter { it.first.id !in processingIds }
    val avatar = remember(profile.photo) { Photo.decodeAvatar(profile.photo) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (processing.isNotEmpty()) {
                items(processing, key = { "p_" + it.id }) { r ->
                    // শেষ হয়ে যাওয়া রিকোয়েস্টে: রক্ত দিয়েছি / দিইনি — সার্ভার তারিখ আর অন্য রিকোয়েস্ট সামলায়
                    fun answer(op: String) {
                        homeScope.launch {
                            processingBusy = r.id
                            runCatching {
                                ServerApi.updateResponse(r.id, op)
                                AuthRepository.currentUid?.let { ProfileRepository.removeCommitments(it, listOf(r.id)) }
                            }.onFailure { errorMsg = it.message }
                            processingBusy = null
                            reload++
                        }
                    }
                    ProcessingCard(
                        r,
                        onClick = { onOpenRequest(r.id) },
                        onDonated = { answer("donated") },
                        onNotDonated = { answer("cancel") },
                        busy = processingBusy == r.id,
                    )
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        tr("Nearby Requests", "আশেপাশের রিকোয়েস্ট"),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { reload++ }) { AppIcon(R.drawable.ic_refresh, tr("Refresh", "রিফ্রেশ")) }
                    AssistChip(
                        onClick = onChangeLocation,
                        label = { Text(Districts.label(profile.placeName), maxLines = 1) },
                        leadingIcon = { AppIcon(R.drawable.ic_location_on, null, Modifier.size(18.dp)) },
                    )
                }
            }

            // নিজের খোলা রিকোয়েস্টের অবস্থা (থাকলেই দেখায়), অন্যদের রিকোয়েস্টের উপরে
            item(key = "update") { UpdateBanner() }
            // জরুরি আহ্বান (Dorkar টিম) সবার উপরে
            item(key = "emergency") { EmergencyBanner(refreshKey * 1000 + reload) }
            item(key = "my_request") { MyRequestSection(refreshKey * 1000 + reload, onOpenMyRequest) }

            when {
                loading && others.isEmpty() -> item { LoadingBox() }
                errorMsg != null -> item {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        ErrorText(errorMsg)
                        TextButton(onClick = { reload++ }) { Text(tr("Try again", "আবার চেষ্টা করুন")) }
                    }
                }
                others.isEmpty() -> item {
                    Column(
                        Modifier.fillParentMaxHeight(0.75f).fillMaxWidth(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        EmptyState(
                            R.drawable.ic_volunteer_activism,
                            tr("Good news!", "সুখবর!"),
                            tr(
                                "No requests within ${num(profile.maxKm)} km right now.",
                                "এই মুহূর্তে ${num(profile.maxKm)} কিমির মধ্যে কোনো রিকোয়েস্ট নেই।",
                            ),
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = onNewRequest) {
                            ButtonContent(false, tr("Create Request", "রিকোয়েস্ট দিন"), R.drawable.ic_add)
                        }
                    }
                }
                else -> items(others, key = { it.first.id }) { (r, d) -> RequestCard(r, d) { onOpenRequest(r.id) } }
            }
        }

        if (others.isNotEmpty()) {
            SmallFab(tr("Request", "রিকোয়েস্ট"), R.drawable.ic_add, onNewRequest, Modifier.align(Alignment.BottomEnd).padding(16.dp))
        }
    }
}

// ------------------------------------------------------------
// আমার রিকোয়েস্ট (প্রোফাইল থেকে)
// ------------------------------------------------------------

@Composable
fun MyRequestsScreen(onOpenMyRequest: (String) -> Unit, onNewRequest: () -> Unit, onBack: () -> Unit) {
    var loading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var list by remember { mutableStateOf<List<BloodRequest>>(emptyList()) }

    LaunchedEffect(Unit) {
        val uid = AuthRepository.currentUid ?: return@LaunchedEffect
        runCatching { RequestRepository.mine(uid) }
            .onSuccess { l -> list = l.sortedWith(compareByDescending<BloodRequest> { it.isActive }.thenByDescending { it.serverCreatedMillis }) }
            .onFailure { errorMsg = tr("Couldn't load", "লোড হয়নি") }
        loading = false
    }
    val activeCount = list.count { it.isActive }

    Scaffold(
        topBar = { BackTopBar(tr("My Requests", "আমার রিকোয়েস্ট"), onBack) },
        floatingActionButton = {
            if (!loading && activeCount < Rules.MAX_OPEN_REQUESTS) {
                SmallFab(tr("Request", "রিকোয়েস্ট"), R.drawable.ic_add, onNewRequest)
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (activeCount >= Rules.MAX_OPEN_REQUESTS) {
                item {
                    Text(
                        tr(
                            "You can keep up to ${num(Rules.MAX_OPEN_REQUESTS)} open requests.",
                            "একসাথে সর্বোচ্চ ${num(Rules.MAX_OPEN_REQUESTS)}টা খোলা রিকোয়েস্ট রাখা যায়।",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when {
                loading -> item { LoadingBox() }
                errorMsg != null -> item { ErrorText(errorMsg) }
                list.isEmpty() -> item {
                    Box(Modifier.fillParentMaxHeight(0.7f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        EmptyState(R.drawable.ic_list_alt, tr("No requests yet", "এখনো কোনো রিকোয়েস্ট দেননি"), null)
                    }
                }
                else -> items(list, key = { it.id }) { r -> RequestCard(r, null) { onOpenMyRequest(r.id) } }
            }
        }
    }
}

/**
 * নিচের বার — Facebook/Uber-এর মতো হালকা: সাদা পটভূমি, উপরে পাতলা রেখা, পিল নেই।
 * বাছা ট্যাব: ভরাট আইকন + অ্যাপের রঙ + উপরে ছোট দাগ; বাকিগুলো রেখার আইকন, ধূসর। লেখা ছোট করে থাকে।
 */
@Composable
internal fun ModernNavBar(items: List<NavItem>, tab: Int, onTabChange: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    androidx.compose.material3.Surface(color = cs.surface) {
        Column {
            androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = cs.outlineVariant)
            Row(Modifier.fillMaxWidth().height(60.dp).selectableGroup()) {
                items.forEachIndexed { i, item ->
                    val sel = tab == i
                    val color by androidx.compose.animation.animateColorAsState(if (sel) cs.primary else cs.onSurfaceVariant, label = "nav")
                    Column(
                        Modifier.weight(1f).fillMaxHeight()
                            .selectable(selected = sel, role = androidx.compose.ui.semantics.Role.Tab, onClick = { onTabChange(i) }),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // উপরে ছোট দাগ (বাছা ট্যাবে)
                        Box(
                            Modifier.width(32.dp).height(3.dp)
                                .clip(RoundedCornerShape(bottomStart = 3.dp, bottomEnd = 3.dp))
                                .background(if (sel) cs.primary else androidx.compose.ui.graphics.Color.Transparent),
                        )
                        Spacer(Modifier.weight(1f))
                        AppIcon(if (sel) item.selectedIcon else item.icon, null, Modifier.size(24.dp), color)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            item.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                            color = color,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * উপরের ডান কোণের ছবি = অ্যাপের মূল মেনু (Facebook-এর মতো): নিজের ছবি, কোণে ছোট ☰ চিহ্ন।
 * চাপলে "সেটিংস" পাতা (প্রোফাইল, আমার রিকোয়েস্ট, সেটিংস, লগআউট…)।
 */
@Composable
internal fun MenuAvatar(avatar: androidx.compose.ui.graphics.ImageBitmap?) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.size(36.dp)) {
        val pic = Modifier.size(32.dp).align(Alignment.TopStart).clip(CircleShape)
        if (avatar != null) {
            androidx.compose.foundation.Image(
                avatar,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = pic,
            )
        } else {
            Box(pic.background(cs.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                AppIcon(R.drawable.ic_person_filled, null, Modifier.size(22.dp), cs.onSurfaceVariant)
            }
        }
        // কোণের ☰: চারপাশে পটভূমির রঙের সরু রিং, যাতে ছবির উপর আলাদা করে চোখে পড়ে
        Box(
            Modifier
                .size(17.dp)
                .align(Alignment.BottomEnd)
                .clip(CircleShape)
                .background(cs.background)
                .padding(1.5.dp)
                .clip(CircleShape)
                .background(cs.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            AppIcon(R.drawable.ic_menu_round, null, Modifier.size(11.dp), cs.onSurface)
        }
        // স্ক্রিন রিডারের জন্য একটাই নাম
        Box(Modifier.matchParentSize().semantics { contentDescription = tr("Settings and profile", "সেটিংস ও প্রোফাইল") })
    }
}

