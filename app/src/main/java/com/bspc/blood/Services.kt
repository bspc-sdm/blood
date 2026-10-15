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
// "সেবা" ট্যাব: অ্যাম্বুলেন্স আর ফার্মেসি — দুটোই শুধু তালিকা আর সরাসরি ফোন (অ্যাপ কিছু বিক্রি করে না)
// ব্যাজ এখানে নেই — সেটা প্রোফাইল থেকে; চেম্বার অ্যাপয়েন্টমেন্ট ট্যাবে।
// ------------------------------------------------------------

@Composable
fun ServicesTab(profile: Profile) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // ওষুধ খোঁজার নোটিফিকেশন থেকে এলে ফার্মেসি অংশ
    val target = ServicesNav.target.value
    LaunchedEffect(target) { if (target == "med_find" || target == "med_find_mine") tab = 1 }
    Column(Modifier.fillMaxSize()) {
        PillTabs(
            tab,
            listOf(PillTab(tr("Ambulance", "অ্যাম্বুলেন্স"), R.drawable.ic_ambulance), PillTab(tr("Pharmacy", "ফার্মেসি"), R.drawable.ic_medication)),
            { tab = it },
        )
        if (tab == 0) AmbulanceContent(profile) else PharmacyContent(profile)
    }
}
