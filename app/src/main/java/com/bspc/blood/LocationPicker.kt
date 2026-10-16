@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bspc.blood

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * বর্তমান লোকেশন (আনুমানিক) অথবা জেলা বেছে নেওয়ার স্ক্রিন।
 * onPicked(lat, lng, এলাকার বাংলা নাম, "gps" বা "district")
 */
@Composable
fun LocationPicker(
    title: String,
    busy: Boolean,
    errorMsg: String?,
    onPicked: (Double, Double, String, String) -> Unit,
    onCancel: (() -> Unit)?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var gpsBusy by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }

    fun useGps() {
        scope.launch {
            gpsBusy = true
            localError = null
            val loc = runCatching { LocationHelper.current(context) }.getOrNull()
            gpsBusy = false
            if (loc == null) {
                localError = tr("Couldn't get your location. Turn on GPS or pick a district.", "লোকেশন পাওয়া যায়নি। GPS চালু করুন বা জেলা বেছে নিন।")
            } else {
                val district = Districts.at(context, loc.first, loc.second)
                onPicked(loc.first, loc.second, district.bn, "gps")
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) useGps() else localError = tr("Location permission denied. Pick a district.", "লোকেশনের অনুমতি দেওয়া হয়নি। জেলা বেছে নিন।")
    }

    Scaffold(
        topBar = {
            if (onCancel != null) BackTopBar(title, onCancel) else TopAppBar(title = { Text(title) })
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = {
                    if (LocationHelper.hasPermission(context)) useGps()
                    else permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                },
                enabled = !busy && !gpsBusy,
                modifier = Modifier.fillMaxWidth(),
            ) { ButtonContent(busy || gpsBusy, tr("Use my current location", "বর্তমান লোকেশন ব্যবহার করুন"), R.drawable.ic_location_on) }
            Text(
                tr("Only your approximate area (~1 km) is saved.", "শুধু আনুমানিক এলাকা (≈১ কিমি) রাখা হয়।"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ErrorText(localError ?: errorMsg)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(tr("Search district", "জেলা খুঁজুন")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            val q = query.trim()
            val list = Districts.ALL
                .filter { q.isEmpty() || it.bn.contains(q) || it.en.contains(q, ignoreCase = true) }
                .sortedBy { if (isBangla) it.bn else it.en }
            LazyColumn(Modifier.weight(1f)) {
                items(list, key = { it.en }) { d ->
                    ListItem(
                        headlineContent = { Text(if (isBangla) d.bn else d.en) },
                        supportingContent = { Text(if (isBangla) d.en else d.bn) },
                        modifier = Modifier.clickable(enabled = !busy) { onPicked(d.lat, d.lng, d.bn, "district") },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/** "বর্তমান লোকেশন" বাটন: অনুমতি চেয়ে GPS থেকে আনুমানিক লোকেশন নেয় */
@Composable
fun GpsButton(text: String, onLocated: (Double, Double, String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun locate() {
        scope.launch {
            busy = true
            error = null
            val loc = runCatching { LocationHelper.current(context) }.getOrNull()
            busy = false
            if (loc == null) {
                error = tr("Couldn't get your location. Turn on GPS and try again.", "লোকেশন পাওয়া যায়নি। GPS চালু করে আবার চেষ্টা করুন।")
            } else {
                onLocated(loc.first, loc.second, Districts.at(context, loc.first, loc.second).bn)
            }
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) locate() else error = tr("Location permission denied.", "লোকেশনের অনুমতি দেওয়া হয়নি।")
    }
    Column(modifier) {
        OutlinedButton(
            onClick = { if (LocationHelper.hasPermission(context)) locate() else launcher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) },
            enabled = !busy,
        ) { ButtonContent(busy, text, R.drawable.ic_location_on) }
        ErrorText(error)
    }
}
