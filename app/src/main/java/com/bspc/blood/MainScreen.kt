@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bspc.blood

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
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

private data class NavItem(val label: String, val selectedIcon: Int, val icon: Int)

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

    val items = listOf(
        NavItem(tr("Home", "হোম"), R.drawable.ic_home_filled, R.drawable.ic_home_outline),
        NavItem(tr("Appointment", "অ্যাপয়েন্টমেন্ট"), R.drawable.ic_event_filled, R.drawable.ic_event_outline),
        NavItem(tr("Discussion", "আলোচনা"), R.drawable.ic_forum_filled, R.drawable.ic_forum_outline),
        NavItem(tr("Health Tips", "হেলথ টিপস"), R.drawable.ic_lightbulb_filled, R.drawable.ic_lightbulb_outline),
        NavItem(tr("Profile", "প্রোফাইল"), R.drawable.ic_person_filled, R.drawable.ic_person_outline),
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                items.forEachIndexed { i, item ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { onTabChange(i) },
                        icon = { AppIcon(if (tab == i) item.selectedIcon else item.icon, null) },
                        label = {
                            Text(
                                item.label,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                Tabs.HOME -> HomeTab(profile, homeRefresh, onOpenRequest, onOpenMyRequest, onNewRequest, onChangeLocation) { onTabChange(Tabs.PROFILE) }
                Tabs.APPOINTMENT -> AppointmentTab(profile, community, onOpenMyAppointments, onEditChamber)
                Tabs.DISCUSSION -> HealthDiscussionTab(communityRefresh, onOpenQuestion, onAsk)
                Tabs.TIPS -> HealthTipsTab(communityRefresh, community, onOpenTip, onPostTip)
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
    onOpenProfile: () -> Unit,
) {
    var reload by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var list by remember { mutableStateOf<List<Pair<BloodRequest, Double>>>(emptyList()) }
    var processing by remember { mutableStateOf<List<BloodRequest>>(emptyList()) }
    val groups = profile.effectiveViewGroups

    LaunchedEffect(reload, refreshKey, profile.lat, profile.lng, groups) {
        val lat = profile.lat
        val lng = profile.lng
        if (lat == null || lng == null) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        errorMsg = null
        runCatching { RequestRepository.nearbyOpen(lat, lng, groups.toSet(), AuthRepository.currentUid) }
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
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Logo()
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { reload++ }) { AppIcon(R.drawable.ic_refresh, tr("Refresh", "রিফ্রেশ")) }
                    IconButton(onClick = onOpenProfile) {
                        if (avatar != null) {
                            androidx.compose.foundation.Image(
                                avatar,
                                contentDescription = tr("Profile", "প্রোফাইল"),
                                modifier = Modifier.size(32.dp).clip(CircleShape),
                            )
                        } else {
                            AppIcon(R.drawable.ic_account_circle, tr("Profile", "প্রোফাইল"), Modifier.size(32.dp))
                        }
                    }
                }
            }

            if (processing.isNotEmpty()) {
                items(processing, key = { "p_" + it.id }) { r -> ProcessingCard(r) { onOpenRequest(r.id) } }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        tr("Nearby Requests", "আশেপাশের রিকোয়েস্ট"),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    AssistChip(
                        onClick = onChangeLocation,
                        label = { Text(Districts.label(profile.placeName), maxLines = 1) },
                        leadingIcon = { AppIcon(R.drawable.ic_location_on, null, Modifier.size(18.dp)) },
                    )
                }
            }

            // নিজের খোলা রিকোয়েস্টের অবস্থা (থাকলেই দেখায়), অন্যদের রিকোয়েস্টের উপরে
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
                            tr("No requests in the queue right now.", "এই মুহূর্তে আশেপাশে কোনো রিকোয়েস্ট নেই।"),
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
            ExtendedFloatingActionButton(
                onClick = onNewRequest,
                icon = { AppIcon(R.drawable.ic_add, null) },
                text = { Text(tr("Create Request", "রিকোয়েস্ট দিন")) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
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
                ExtendedFloatingActionButton(
                    onClick = onNewRequest,
                    icon = { AppIcon(R.drawable.ic_add, null) },
                    text = { Text(tr("Create Request", "রিকোয়েস্ট দিন")) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
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
