package com.bspc.blood

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** অ্যাপয়েন্টমেন্ট: ডাক্তার আর নার্স আলাদা ট্যাবে (বুকিং ব্যবস্থা পরে যোগ হবে) */
@Composable
fun AppointmentTab() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Text(
            tr("Appointment", "অ্যাপয়েন্টমেন্ট"),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
        )
        TabRow(selectedTabIndex = tab) {
            Tab(
                selected = tab == 0,
                onClick = { tab = 0 },
                text = { Text(tr("Doctor", "ডাক্তার")) },
                icon = { AppIcon(R.drawable.ic_stethoscope, null, Modifier.size(22.dp)) },
            )
            Tab(
                selected = tab == 1,
                onClick = { tab = 1 },
                text = { Text(tr("Nurse", "নার্স")) },
                icon = { AppIcon(R.drawable.ic_medical_services, null, Modifier.size(22.dp)) },
            )
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (tab == 0) {
                EmptyState(
                    R.drawable.ic_stethoscope,
                    tr("Doctor appointments", "ডাক্তারের অ্যাপয়েন্টমেন্ট"),
                    tr("Coming soon", "শিগগিরই আসছে"),
                )
            } else {
                EmptyState(
                    R.drawable.ic_medical_services,
                    tr("Nurse appointments", "নার্সের অ্যাপয়েন্টমেন্ট"),
                    tr("Coming soon", "শিগগিরই আসছে"),
                )
            }
        }
    }
}
