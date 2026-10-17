@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bspc.blood

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

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
// নিজের ব্যবসা, চেম্বার আর বাসায় সেবা "আমার কাজ" ট্যাবে; ব্যাজ প্রোফাইল থেকে।
// ------------------------------------------------------------

@Composable
fun ServicesTab(profile: Profile) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // নিজের অ্যাম্বুলেন্স/ফার্মেসি/ব্লাড ব্যাংক/প্যাথলজি এখানে নেই — সেগুলো "আমার কাজ" ট্যাবে (২.২০.০)
    // নোটিফিকেশন থেকে: নিজের ওষুধ খোঁজার উত্তর → ফার্মেসি › "ওষুধ খুঁজুন"
    val target = ServicesNav.target.value
    LaunchedEffect(target) {
        if (target == "med_find_mine") tab = 1
    }
    Column(Modifier.fillMaxSize()) {
        PillTabs(
            tab,
            listOf(
                PillTab(tr("Ambulance", "অ্যাম্বুলেন্স"), R.drawable.ic_ambulance),
                PillTab(tr("Pharmacy", "ফার্মেসি"), R.drawable.ic_medication),
                PillTab(tr("Blood bank", "ব্লাড ব্যাংক"), R.drawable.ic_bloodtype),
                PillTab(tr("Lab", "প্যাথলজি"), R.drawable.ic_local_hospital),
            ),
            { tab = it },
        )
        when (tab) {
            0 -> AmbulanceContent(profile, showMine = false)
            1 -> PharmacyContent(profile)
            2 -> BloodBankContent(profile)
            else -> LabContent(profile)
        }
    }
}
