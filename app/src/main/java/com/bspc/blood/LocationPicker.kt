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
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * বর্তমান লোকেশন (আনুমানিক) অথবা জেলা বেছে নেওয়ার স্ক্রিন।
 * onPicked(lat, lng, এলাকার নাম, "gps" বা "district")
 */
@Composable
fun LocationPicker(
    title: String,
    subtitle: String,
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
                localError = "লোকেশন পাওয়া যায়নি। ফোনের লোকেশন (GPS) চালু করে আবার চেষ্টা করুন, অথবা নিচ থেকে জেলা বেছে নিন।"
            } else {
                val district = Districts.nearest(loc.first, loc.second)
                onPicked(loc.first, loc.second, district.bn, "gps")
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) useGps() else localError = "লোকেশনের অনুমতি দেওয়া হয়নি। নিচ থেকে জেলা বেছে নিন।"
    }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium)
        Button(
            onClick = {
                if (LocationHelper.hasPermission(context)) {
                    useGps()
                } else {
                    permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                }
            },
            enabled = !busy && !gpsBusy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ButtonContent(busy || gpsBusy, "📍 বর্তমান লোকেশন ব্যবহার করুন")
        }
        Text(
            "আপনার সঠিক ঠিকানা কাউকে দেখানো হয় না, শুধু আনুমানিক এলাকা (≈১ কিমি) রাখা হয়।",
            style = MaterialTheme.typography.bodySmall,
        )
        ErrorText(localError ?: errorMsg)
        Text("অথবা জেলা বেছে নিন", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("জেলার নাম খুঁজুন") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val q = query.trim()
        val list = Districts.ALL.filter {
            q.isEmpty() || it.bn.contains(q) || it.en.contains(q, ignoreCase = true)
        }
        LazyColumn(Modifier.weight(1f)) {
            items(list, key = { it.en }) { d ->
                Text(
                    "${d.bn} (${d.en})",
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !busy) { onPicked(d.lat, d.lng, d.bn, "district") }
                        .padding(vertical = 14.dp),
                )
                HorizontalDivider()
            }
        }
        if (onCancel != null) {
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("বাতিল") }
        }
    }
}
