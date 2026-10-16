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
// "সেবা" ট্যাব: অ্যাম্বুলেন্স, ফার্মেসি আর ব্লাড ব্যাংক — সবই শুধু তালিকা আর সরাসরি ফোন (অ্যাপ কিছু বিক্রি করে না)
// ব্যাজ এখানে নেই — সেটা প্রোফাইল থেকে; চেম্বার অ্যাপয়েন্টমেন্ট ট্যাবে।
// ------------------------------------------------------------

@Composable
fun ServicesTab(profile: Profile) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // নিজের অ্যাম্বুলেন্স/ফার্মেসি/ব্লাড ব্যাংকের পাতা খোলা কি না
    var mineOpen by rememberSaveable { mutableStateOf(false) }
    // নিজের আছে কি না (০ অ্যাম্বুলেন্স, ১ ফার্মেসি, ২ ব্লাড ব্যাংক): থাকলে নিচে ভাসমান বাটন, না থাকলে উপরে +
    var owns by remember { mutableStateOf(listOf(false, false, false)) }
    var ownsKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(ownsKey) {
        owns = listOf(
            runCatching { Ambulances.mine().isNotEmpty() }.getOrDefault(false),
            runCatching { Pharmacies.mine() != null }.getOrDefault(false),
            runCatching { BloodBanks.mine().isNotEmpty() }.getOrDefault(false),
        )
    }
    // নোটিফিকেশন থেকে: নিজের ওষুধ খোঁজা → ফার্মেসি; দোকানের অনুরোধ/অর্ডার → নিজের ফার্মেসির পাতা
    val target = ServicesNav.target.value
    LaunchedEffect(target) {
        when (target) {
            "med_find_mine" -> tab = 1
            "med_find", "pharmacy_mine" -> {
                tab = 1
                mineOpen = true
                ServicesNav.target.value = null
            }
        }
    }
    val mineTitle = when (tab) {
        0 -> tr("My ambulance", "আমার অ্যাম্বুলেন্স")
        1 -> tr("My pharmacy", "আমার ফার্মেসি")
        else -> tr("My blood bank", "আমার ব্লাড ব্যাংক")
    }
    if (mineOpen) {
        val close = { mineOpen = false; ownsKey++ }
        androidx.activity.compose.BackHandler(onBack = close)
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(start = 4.dp, end = 16.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.IconButton(onClick = close) { AppIcon(R.drawable.ic_arrow_back, tr("Back", "ফিরে যান")) }
                Text(mineTitle, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            when (tab) {
                0 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    MyAmbulancesSection(profile)
                }
                1 -> MyPharmacyPanel(profile)
                else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    MyBloodBanksSection(profile)
                }
            }
        }
        return
    }
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                    PillTabs(
                        tab,
                        listOf(
                            PillTab(tr("Ambulance", "অ্যাম্বুলেন্স"), R.drawable.ic_ambulance),
                            PillTab(tr("Pharmacy", "ফার্মেসি"), R.drawable.ic_medication),
                            PillTab(tr("Blood bank", "ব্লাড ব্যাংক"), R.drawable.ic_bloodtype),
                        ),
                        { tab = it },
                    )
                }
                // প্রথমবার যোগ করা: উপরে +
                if (!owns[tab]) {
                    androidx.compose.material3.IconButton(onClick = { mineOpen = true }) {
                        AppIcon(R.drawable.ic_add, tr("Add ", "যোগ করুন: ") + mineTitle)
                    }
                }
            }
            when (tab) {
                0 -> AmbulanceContent(profile, showMine = false)
                1 -> PharmacyContent(profile)
                else -> BloodBankContent(profile)
            }
        }
        // নিজের থাকলে: নিচে ভাসমান বাটন, চাপলে নিজের পাতা
        if (owns[tab]) {
            SmallFab(
                mineTitle,
                when (tab) { 0 -> R.drawable.ic_ambulance; 1 -> R.drawable.ic_storefront; else -> R.drawable.ic_bloodtype },
                { mineOpen = true },
                Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }
}
