@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bspc.blood

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// ------------------------------------------------------------
// "স্বাস্থ্য" ট্যাব: আলোচনা + টিপস
// ------------------------------------------------------------

@Composable
fun HealthTab(
    refreshKey: Int,
    community: CommunityMe?,
    onOpenQuestion: (String) -> Unit,
    onAsk: () -> Unit,
    onOpenTip: (Tip) -> Unit,
    onPostTip: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(
                selected = tab == 0,
                onClick = { tab = 0 },
                text = { Text(tr("Discussion", "আলোচনা")) },
                icon = { AppIcon(if (tab == 0) R.drawable.ic_forum_filled else R.drawable.ic_forum_outline, null, Modifier.size(22.dp)) },
            )
            Tab(
                selected = tab == 1,
                onClick = { tab = 1 },
                text = { Text(tr("Tips", "টিপস")) },
                icon = { AppIcon(if (tab == 1) R.drawable.ic_lightbulb_filled else R.drawable.ic_lightbulb_outline, null, Modifier.size(22.dp)) },
            )
        }
        if (tab == 0) HealthDiscussionTab(refreshKey, onOpenQuestion, onAsk)
        else HealthTipsTab(refreshKey, community, onOpenTip, onPostTip)
    }
}

// ------------------------------------------------------------
// "সেবা দিন" ট্যাব: নিজের ফার্মেসি, অ্যাম্বুলেন্স, চেম্বার
// ------------------------------------------------------------

@Composable
fun ServicesTab(
    profile: Profile,
    community: CommunityMe?,
    refreshKey: Int,
    onOpenShop: () -> Unit,
    onEditChamber: () -> Unit,
    onCommunityChanged: (CommunityMe) -> Unit,
) {
    var shop by remember { mutableStateOf<Pharmacy?>(null) }
    var shopLoaded by remember { mutableStateOf(false) }
    var applying by remember { mutableStateOf(false) }
    var infoMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refreshKey) {
        runCatching { Pharmacies.mine() }.onSuccess { shop = it }
        shopLoaded = true
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(tr("Offer a service", "সেবা দিন"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        SmallNote(
            tr(
                "Run your pharmacy or ambulance through the app. It's free: the app takes no commission and no money passes through it.",
                "অ্যাপের মাধ্যমে নিজের ফার্মেসি বা অ্যাম্বুলেন্স চালান। বিনামূল্যে: অ্যাপ কোনো কমিশন নেয় না, টাকাও অ্যাপ দিয়ে যায় না।",
            ),
        )
        InfoText(infoMsg)

        // ফার্মেসি
        ServiceCard(
            icon = R.drawable.ic_storefront,
            title = tr("My pharmacy", "আমার ফার্মেসি"),
            subtitle = when {
                !shopLoaded -> ""
                shop == null -> tr("Have a licensed pharmacy? Get medicine orders from nearby customers.", "লাইসেন্সধারী ফার্মেসি আছে? কাছের গ্রাহকদের ওষুধের অর্ডার পান।")
                else -> shop!!.name + " · " + statusText(shop!!.status)
            },
            button = if (shop == null) tr("Open a pharmacy", "ফার্মেসি খুলুন") else tr("Open", "খুলুন"),
            onClick = onOpenShop,
        )

        // অ্যাম্বুলেন্স
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ServiceIcon(R.drawable.ic_ambulance)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(tr("My ambulance", "আমার অ্যাম্বুলেন্স"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        SmallNote(tr("People in your area call you directly.", "আপনার এলাকার মানুষ সরাসরি ফোন করবেন।"))
                    }
                }
                MyAmbulancesSection(profile)
            }
        }

        // ডাক্তার / নার্স
        val badge = community?.approvedBadge
        if (badge == "doctor" || badge == "nurse") {
            ServiceCard(
                icon = Badges.icon(badge),
                title = tr("My chambers & home visits", "আমার চেম্বার ও হোম ভিজিট"),
                subtitle = tr("Chambers, appointments and home nurse settings.", "চেম্বার, অ্যাপয়েন্টমেন্ট আর হোম নার্সের সেটিং।"),
                button = tr("Open", "খুলুন"),
                onClick = onEditChamber,
            )
        } else {
            ServiceCard(
                icon = R.drawable.ic_stethoscope,
                title = tr("Doctor or nurse?", "ডাক্তার বা নার্স?"),
                subtitle = if (community?.badge?.status == "pending") tr("Your badge application is under review.", "আপনার ব্যাজের আবেদন যাচাই চলছে।")
                else tr("Get a verified badge to add chambers and take home visits.", "যাচাই করা ব্যাজ নিয়ে চেম্বার যোগ করুন আর হোম ভিজিট নিন।"),
                button = if (community?.badge?.status == "pending") null else tr("Apply for badge", "ব্যাজের আবেদন"),
                onClick = { applying = true },
            )
        }
    }

    if (applying) {
        BadgeApplyDialog(
            onDismiss = { applying = false },
            onApplied = { type ->
                applying = false
                onCommunityChanged((community ?: CommunityMe(null, false)).copy(badge = BadgeState(type, "pending")))
                infoMsg = tr("Application sent. An admin will check it.", "আবেদন পাঠানো হয়েছে। অ্যাডমিন যাচাই করবেন।")
            },
        )
    }
}

@Composable
private fun ServiceIcon(icon: Int) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(44.dp)) {
        androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
            AppIcon(icon, null, Modifier.size(26.dp), MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun ServiceCard(icon: Int, title: String, subtitle: String, button: String?, onClick: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ServiceIcon(icon)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (subtitle.isNotBlank()) SmallNote(subtitle)
                }
            }
            if (button != null) OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(button) }
        }
    }
}

// ------------------------------------------------------------
// হোমের দ্রুত বাটন
// ------------------------------------------------------------

@Composable
fun QuickActions(onAmbulance: () -> Unit, onMedicine: () -> Unit, onHomeNurse: () -> Unit, onBlood: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QuickAction(R.drawable.ic_ambulance, tr("Ambulance", "অ্যাম্বুলেন্স"), Modifier.weight(1f), onAmbulance)
        QuickAction(R.drawable.ic_medication, tr("Medicine", "ওষুধ"), Modifier.weight(1f), onMedicine)
        QuickAction(R.drawable.ic_home_health, tr("Home nurse", "হোম নার্স"), Modifier.weight(1f), onHomeNurse)
        QuickAction(R.drawable.ic_bloodtype, tr("Need blood", "রক্ত চাই"), Modifier.weight(1f), onBlood)
    }
}

@Composable
private fun QuickAction(icon: Int, label: String, modifier: Modifier, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AppIcon(icon, null, Modifier.size(28.dp), MaterialTheme.colorScheme.onSecondaryContainer)
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
