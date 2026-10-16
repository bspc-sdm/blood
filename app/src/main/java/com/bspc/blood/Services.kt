@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bspc.blood

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.Surface
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
        PillTabs(
            tab,
            listOf(
                PillTab(tr("Discussion", "আলোচনা"), R.drawable.ic_forum_outline, R.drawable.ic_forum_filled),
                PillTab(tr("Tips", "টিপস"), R.drawable.ic_lightbulb_outline, R.drawable.ic_lightbulb_filled),
            ),
            { tab = it },
        )
        if (tab == 0) HealthDiscussionTab(refreshKey, onOpenQuestion, onAsk)
        else HealthTipsTab(refreshKey, community, onOpenTip, onPostTip)
    }
}

// ------------------------------------------------------------
// নিচের বারের "সার্ভিস": অ্যাপের সব সেবা টাইল আকারে; চাপলে সেই সেবার পাতা (Screen.Service)।
// নতুন সেবা (বাড়ি ভাড়া, পার্কিং…) এলে SERVICES তালিকায় একটা লাইন আর ServiceScreen-এ একটা শাখা।
// ------------------------------------------------------------

data class ServiceTile(val key: String, val title: () -> String, val subtitle: () -> String, @androidx.annotation.DrawableRes val icon: Int)

object Services {
    val ALL = listOf(
        ServiceTile("doctor", { tr("Doctor & appointment", "ডাক্তার ও অ্যাপয়েন্টমেন্ট") }, { tr("Chambers, serials, home care", "চেম্বার, সিরিয়াল, বাসায় সেবা") }, R.drawable.ic_stethoscope),
        ServiceTile("health", { tr("Health talk", "স্বাস্থ্য আলোচনা") }, { tr("Ask questions, read tips", "প্রশ্ন করুন, টিপস পড়ুন") }, R.drawable.ic_forum_outline),
        ServiceTile("ambulance", { tr("Ambulance", "অ্যাম্বুলেন্স") }, { tr("Find nearby and call", "কাছের অ্যাম্বুলেন্সে ফোন") }, R.drawable.ic_ambulance),
        ServiceTile("pharmacy", { tr("Pharmacy", "ফার্মেসি") }, { tr("Open shops, find a medicine", "খোলা দোকান, ওষুধ খোঁজা") }, R.drawable.ic_medication),
        ServiceTile("bloodbank", { tr("Blood bank", "ব্লাড ব্যাংক") }, { tr("Blood banks near you", "কাছের ব্লাড ব্যাংক") }, R.drawable.ic_bloodtype),
        ServiceTile("jobs", { tr("Jobs", "চাকরি") }, { tr("Find work or hire", "চাকরি খুঁজুন বা নিয়োগ দিন") }, R.drawable.ic_nav_work), // FEATURE:jobs
    )

    fun title(key: String) = ALL.firstOrNull { it.key == key }?.title?.invoke() ?: tr("Services", "সার্ভিস")

    /** পুরোনো "services" নোটিফিকেশনের kind → সেবার পাতা */
    fun keyForKind(kind: String?) = when (kind?.ifBlank { null }) {
        "pharmacy" -> "pharmacy"
        "bloodbank" -> "bloodbank"
        else -> "ambulance"
    }
}

@Composable
fun ServicesTab(onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(tr("Services", "সার্ভিস"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Services.ALL.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { t ->
                    Card(
                        onClick = { onOpen(t.key) },
                        modifier = Modifier.weight(1f).heightIn(min = 128.dp),
                        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Surface(shape = androidx.compose.foundation.shape.CircleShape, color = cs.primaryContainer, modifier = Modifier.size(44.dp)) {
                                androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                                    AppIcon(t.icon, null, Modifier.size(24.dp), cs.onPrimaryContainer)
                                }
                            }
                            Text(t.title(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(t.subtitle(), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** একটা সেবার পাতা (উপরে নাম আর ফেরার বাটন) */
@Composable
fun ServiceScreen(
    key: String,
    profile: Profile,
    community: CommunityMe?,
    refreshKey: Int,
    onBack: () -> Unit,
    onOpenMyAppointments: () -> Unit,
    onEditChamber: () -> Unit,
    onOpenQuestion: (String) -> Unit,
    onAsk: () -> Unit,
    onOpenTip: (Tip) -> Unit,
    onPostTip: () -> Unit,
    onOpenJobs: (JobsPage) -> Unit, // FEATURE:jobs
) {
    Scaffold(
        topBar = {
            BackTopBar(Services.title(key), onBack) {
                if (key == "doctor") {
                    androidx.compose.material3.IconButton(onClick = onOpenMyAppointments) {
                        AppIcon(R.drawable.ic_calendar_month, tr("My appointments", "আমার অ্যাপয়েন্টমেন্ট"))
                    }
                }
            }
        },
    ) { padding ->
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(padding)) {
            when (key) {
                "doctor" -> AppointmentTab(profile, community, onOpenMyAppointments, onEditChamber, showTitle = false)
                "health" -> HealthTab(refreshKey, community, onOpenQuestion, onAsk, onOpenTip, onPostTip)
                "pharmacy" -> PharmacyContent(profile)
                "bloodbank" -> BloodBankContent(profile)
                "jobs" -> JobsTab(profile, refreshKey, onOpenJobs) // FEATURE:jobs
                else -> AmbulanceContent(profile)
            }
        }
    }
}
