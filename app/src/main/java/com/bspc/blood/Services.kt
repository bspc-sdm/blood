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
// "সেবা" ট্যাব: অ্যাম্বুলেন্স · ডাক্তার · ফার্মেসি · ব্লাড ব্যাংক · ল্যাব — এক সারির ছোট চিপে বদলায়, পাঁচটাই একই ছাঁচ
// (Listings.kt)। ডাক্তার "অ্যাপয়েন্টমেন্ট" ট্যাবেও আগের মতো আছে। অ্যাপ কিছু বিক্রি করে না — শুধু তালিকা আর সরাসরি যোগাযোগ।
// নিজের ব্যবসা, চেম্বার আর বাসায় সেবা "আমার কাজ" ট্যাবে; ব্যাজ প্রোফাইল থেকে।
// ------------------------------------------------------------

val SERVICE_TYPES = listOf(ListingType.AMBULANCE, ListingType.DOCTOR, ListingType.PHARMACY, ListingType.BLOODBANK, ListingType.LAB)

@Composable
fun ServicesTab(profile: Profile) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // নোটিফিকেশন থেকে: নিজের ওষুধ খোঁজার উত্তর → ফার্মেসি › "ওষুধ খুঁজুন"
    val target = ServicesNav.target.value
    LaunchedEffect(target) {
        if (target == "med_find_mine") tab = SERVICE_TYPES.indexOf(ListingType.PHARMACY)
        // Google Maps থেকে শেয়ার করা জায়গা (২.৩১.০): অ্যাম্বুলেন্স — শিট AmbulanceContent খোলে
        if (target == "amb_call") {
            tab = 0
            ServicesNav.target.value = null
        }
    }
    val type = SERVICE_TYPES[tab.coerceIn(0, SERVICE_TYPES.lastIndex)]
    Column(Modifier.fillMaxSize()) {
        ListingTypeTabs(SERVICE_TYPES, type) { tab = SERVICE_TYPES.indexOf(it) }
        when (type) {
            ListingType.AMBULANCE -> AmbulanceContent(profile, showMine = false)
            ListingType.DOCTOR -> ProviderList("doctor", profile)
            ListingType.PHARMACY -> PharmacyContent(profile)
            ListingType.BLOODBANK -> BloodBankContent(profile)
            ListingType.LAB -> LabContent(profile)
        }
    }
}
