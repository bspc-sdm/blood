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
// "সেবা" ট্যাব: ফার্মেসি (ওষুধ অর্ডার + নিজের দোকান) আর অ্যাম্বুলেন্স (খোঁজা + নিজেরটা যোগ)
// ব্যাজ এখানে নেই — সেটা প্রোফাইল থেকে; চেম্বার অ্যাপয়েন্টমেন্ট ট্যাবে।
// ------------------------------------------------------------

@Composable
fun ServicesTab(profile: Profile, onOpenShop: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(
                selected = tab == 0,
                onClick = { tab = 0 },
                text = { Text(tr("Pharmacy", "ফার্মেসি")) },
                icon = { AppIcon(R.drawable.ic_medication, null, Modifier.size(22.dp)) },
            )
            Tab(
                selected = tab == 1,
                onClick = { tab = 1 },
                text = { Text(tr("Ambulance", "অ্যাম্বুলেন্স")) },
                icon = { AppIcon(R.drawable.ic_ambulance, null, Modifier.size(22.dp)) },
            )
        }
        if (tab == 0) MedicineContent(profile, 0, onOpenShop) else AmbulanceContent(profile)
    }
}
