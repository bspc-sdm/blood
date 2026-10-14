@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bspc.blood

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

const val PRIVACY_URL = "https://github.com/bspc-sdm/blood/blob/main/PRIVACY.md"
const val SUPPORT_EMAIL = "ekapkbd@gmail.com"

@Composable
fun ErrorText(message: String?) {
    if (message != null) {
        Text(
            message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
}

@Composable
fun InfoText(message: String?) {
    if (message != null) {
        Text(
            message,
            color = Color(0xFF2E7D32),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
}

@Composable
fun LoadingBox() {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** বাটনের ভেতরে: কাজ চলার সময় ঘুরন্ত চিহ্ন, নইলে লেখা */
@Composable
fun ButtonContent(busy: Boolean, text: String) {
    if (busy) {
        CircularProgressIndicator(
            Modifier.size(20.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    } else {
        Text(text)
    }
}

@Composable
fun RequestCard(r: BloodRequest, distanceKm: Double?, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                r.patientGroup,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = BloodRed,
                modifier = Modifier.width(76.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (r.urgent && r.isActive) {
                    Text(
                        "জরুরি",
                        color = BloodRed,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Text("${r.bags} ব্যাগ · ${r.hospital}", fontWeight = FontWeight.SemiBold)
                Text(
                    listOfNotNull(r.placeName, distanceKm?.let { Rules.formatKm(it) }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("লাগবে: ${Rules.formatNeeded(r)}", style = MaterialTheme.typography.bodySmall)
                if (!r.isActive) {
                    Text("বন্ধ", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                }
            }
        }
    }
}

@Composable
fun RequestInfo(r: BloodRequest, distanceKm: Double?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    r.patientGroup,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = BloodRed,
                )
                Spacer(Modifier.width(16.dp))
                Column {
                    if (r.urgent && r.isActive) {
                        Text("জরুরি", color = BloodRed, fontWeight = FontWeight.Bold)
                    }
                    Text("${r.bags} ব্যাগ রক্ত দরকার", style = MaterialTheme.typography.titleMedium)
                }
            }
            InfoLine("হাসপাতাল", r.hospital)
            InfoLine(
                "এলাকা",
                listOfNotNull(r.placeName, distanceKm?.let { Rules.formatKm(it) }).joinToString(" · "),
            )
            InfoLine("কবে লাগবে", Rules.formatNeeded(r))
            if (r.note.isNotBlank()) InfoLine("অতিরিক্ত তথ্য", r.note)
            InfoLine("রিকোয়েস্ট দিয়েছেন", r.requesterName)
            if (!r.isActive) {
                Text("এই রিকোয়েস্টটা বন্ধ হয়ে গেছে", color = Color.Gray, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun InfoLine(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun Disclaimer() {
    Text(
        "অ্যাপ শুধু সম্ভাব্য ডোনার খুঁজে দেয়। রক্ত দেওয়া যাবে কি না, তা ব্লাড ব্যাংক ক্রসম্যাচ করে ঠিক করবে।",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

fun callPhone(context: Context, phone: String) {
    context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
}

fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

/** রক্ত লাগার সময় পর্যন্ত প্রতি সেকেন্ডে কমতে থাকা কাউন্টডাউন */
@Composable
fun Countdown(targetMillis: Long, style: TextStyle = MaterialTheme.typography.titleMedium) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(targetMillis) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val left = targetMillis - now
    if (left > 0) {
        Text("⏳ রক্ত লাগবে আর ${Rules.formatDuration(left)} পরে", style = style, fontWeight = FontWeight.Bold, color = BloodRed)
    } else {
        Text("⌛ রক্ত লাগার সময় হয়ে গেছে", style = style, fontWeight = FontWeight.Bold, color = BloodRed)
    }
}

/** ডোনার যে রিকোয়েস্টে রাজি হয়েছেন: "প্রসেসিং" কার্ড, কাউন্টডাউন সহ */
@Composable
fun ProcessingCard(r: BloodRequest, onClick: (() -> Unit)?) {
    val content: @Composable () -> Unit = {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("🔄 প্রসেসিং — আপনি রক্ত দিতে রাজি হয়েছেন", fontWeight = FontWeight.Bold)
            Text("${r.patientGroup} · ${r.bags} ব্যাগ · ${r.hospital}", fontWeight = FontWeight.SemiBold)
            Text("লাগবে: ${Rules.formatNeeded(r)}", style = MaterialTheme.typography.bodySmall)
            Countdown(r.deadlineMillis)
        }
    }
    val colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1))
    if (onClick != null) {
        Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = colors) { content() }
    } else {
        Card(Modifier.fillMaxWidth(), colors = colors) { content() }
    }
}
