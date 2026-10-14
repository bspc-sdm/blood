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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

@Composable
fun MainScreen(
    profile: Profile,
    tab: Int,
    onTabChange: (Int) -> Unit,
    onOpenRequest: (String) -> Unit,
    onOpenMyRequest: (String) -> Unit,
    onNewRequest: () -> Unit,
    onEditProfile: () -> Unit,
    onChangeLocation: () -> Unit,
    onProfileChanged: (Profile) -> Unit,
    onLogout: () -> Unit,
    onOpenAdmin: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current

    // Android 13+ এ নোটিফিকেশনের অনুমতি চাওয়া
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
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

    val tabs = listOf("🩸" to "রক্ত দরকার", "📋" to "আমার রিকোয়েস্ট", "👤" to "প্রোফাইল")
    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, (icon, label) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { onTabChange(i) },
                        icon = { Text(icon) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                0 -> NearbyTab(profile, onOpenRequest, onChangeLocation, onOpenSettings)
                1 -> MyRequestsTab(onOpenMyRequest, onNewRequest)
                else -> ProfileTab(profile, onEditProfile, onChangeLocation, onLogout, onOpenAdmin, onOpenSettings)
            }
        }
    }
}

@Composable
fun NearbyTab(
    profile: Profile,
    onOpenRequest: (String) -> Unit,
    onChangeLocation: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var reload by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var list by remember { mutableStateOf<List<Pair<BloodRequest, Double>>>(emptyList()) }
    val notEligible = Rules.notEligibleReason(profile)
    val groups = profile.effectiveViewGroups

    LaunchedEffect(reload, profile.lat, profile.lng, groups) {
        val lat = profile.lat
        val lng = profile.lng
        if (lat == null || lng == null) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        errorMsg = null
        runCatching {
            RequestRepository.nearbyOpen(lat, lng, groups.toSet(), AuthRepository.currentUid)
        }.onSuccess { list = it }.onFailure { errorMsg = "লোড হয়নি: ${it.message}" }
        loading = false
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Text(
                    "আশেপাশে রক্ত দরকার",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "${profile.placeName ?: ""} এলাকা · " + if (profile.viewGroups == null) {
                        "${profile.bloodGroup} গ্রুপ দিতে পারে এমন রিকোয়েস্ট"
                    } else {
                        "${groups.joinToString(", ")} গ্রুপের রিকোয়েস্ট"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Row {
                    TextButton(onClick = { reload++ }) { Text("🔄 রিফ্রেশ") }
                    TextButton(onClick = onChangeLocation) { Text("📍 এলাকা") }
                    TextButton(onClick = onOpenSettings) { Text("⚙️ গ্রুপ বাছাই") }
                }
            }
        }
        if (notEligible != null) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))) {
                    Text(
                        "$notEligible। এখন নতুন রিকোয়েস্টের নোটিফিকেশন আসবে না, তবে তালিকা দেখতে আর পরিচিতদের জানাতে পারবেন।",
                        Modifier.padding(16.dp),
                    )
                }
            }
        }
        when {
            loading -> item { LoadingBox() }
            errorMsg != null -> item { ErrorText(errorMsg) }
            list.isEmpty() -> item {
                Text(
                    "এই মুহূর্তে আপনার আশেপাশে (২৫ কিমির মধ্যে) মেলে এমন কোনো খোলা রিকোয়েস্ট নেই।",
                    Modifier.padding(vertical = 24.dp),
                )
            }
            else -> items(list, key = { it.first.id }) { (r, d) ->
                RequestCard(r, d) { onOpenRequest(r.id) }
            }
        }
    }
}

@Composable
fun MyRequestsTab(onOpenMyRequest: (String) -> Unit, onNewRequest: () -> Unit) {
    var reload by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var list by remember { mutableStateOf<List<BloodRequest>>(emptyList()) }

    LaunchedEffect(reload) {
        val uid = AuthRepository.currentUid ?: return@LaunchedEffect
        loading = true
        errorMsg = null
        runCatching { RequestRepository.mine(uid) }
            .onSuccess { list = it }
            .onFailure { errorMsg = "লোড হয়নি: ${it.message}" }
        loading = false
    }
    val activeCount = list.count { it.isActive }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "আমার রিকোয়েস্ট",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Button(
                    onClick = onNewRequest,
                    enabled = !loading && activeCount < Rules.MAX_OPEN_REQUESTS,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("➕ নতুন রক্তের রিকোয়েস্ট দিন") }
                if (activeCount >= Rules.MAX_OPEN_REQUESTS) {
                    Text(
                        "একসাথে সর্বোচ্চ ${Rules.MAX_OPEN_REQUESTS}টা খোলা রিকোয়েস্ট রাখা যায়। আগেরটা বন্ধ করে নতুনটা দিন।",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = { reload++ }) { Text("🔄 রিফ্রেশ") }
            }
        }
        when {
            loading -> item { LoadingBox() }
            errorMsg != null -> item { ErrorText(errorMsg) }
            list.isEmpty() -> item {
                Text("আপনি এখনো কোনো রিকোয়েস্ট দেননি।", Modifier.padding(vertical = 24.dp))
            }
            else -> items(list, key = { it.id }) { r ->
                RequestCard(r, null) { onOpenMyRequest(r.id) }
            }
        }
    }
}

@Composable
fun ProfileTab(
    profile: Profile,
    onEditProfile: () -> Unit,
    onChangeLocation: () -> Unit,
    onLogout: () -> Unit,
    onOpenAdmin: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val notEligible = Rules.notEligibleReason(profile)
    val eligible = notEligible == null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "স্বাগতম, ${profile.name}",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("আপনার রক্তের গ্রুপ", style = MaterialTheme.typography.bodyMedium)
                Text(
                    profile.bloodGroup,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = BloodRed,
                )
                Text(
                    profile.lastDonationMillis?.let { "শেষ রক্তদান: ${Rules.formatDate(it)}" }
                        ?: "আগে কখনো রক্ত দেননি",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    listOfNotNull(
                        Rules.ageYears(profile.birthDateMillis)?.let { "বয়স $it বছর" },
                        profile.weightKg?.let { "ওজন $it কেজি" },
                        profile.phone.ifBlank { null },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (eligible) Color(0xFFE8F5E9) else Color(0xFFFFF3E0),
            ),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (eligible) {
                    Text(
                        "✅ আপনি এখন রক্ত দিতে পারবেন",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                } else {
                    Text(
                        "⏳ এখন রক্ত দিতে পারবেন না",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(notEligible ?: "")
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("এলাকা", style = MaterialTheme.typography.labelMedium)
                    Text(
                        (profile.placeName ?: "ঠিক করা হয়নি") +
                            if (profile.locationSource == "gps") " (বর্তমান লোকেশন)" else "",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                TextButton(onClick = onChangeLocation) { Text("বদলান") }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("⚙️ নোটিফিকেশন ও তালিকা", style = MaterialTheme.typography.titleMedium)
                    Text(settingsSummary(profile), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onOpenSettings) { Text("সেটিংস") }
            }
        }

        CorrectionCard(profile)

        OutlinedButton(onClick = onEditProfile, modifier = Modifier.fillMaxWidth()) {
            Text("প্রোফাইল এডিট")
        }
        // শুধু অ্যাডমিনের অ্যাকাউন্টে দেখায়; আসল যাচাই সার্ভারে
        if (AuthRepository.currentEmail.equals(SUPPORT_EMAIL, ignoreCase = true)) {
            OutlinedButton(onClick = onOpenAdmin, modifier = Modifier.fillMaxWidth()) {
                Text("🛠 অ্যাডমিন: তারিখ সংশোধনের অনুরোধ")
            }
        }
        Disclaimer()
        Row {
            TextButton(onClick = { openUrl(context, PRIVACY_URL) }) { Text("প্রাইভেসি পলিসি") }
            TextButton(onClick = onLogout) { Text("লগআউট") }
        }
    }
}
