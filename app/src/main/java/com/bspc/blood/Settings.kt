package com.bspc.blood

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** ইংরেজি অঙ্ক → বাংলা অঙ্ক */
fun bnDigits(n: Int): String = n.toString().map { if (it.isDigit()) '০' + (it - '0') else it }.joinToString("")

/**
 * সেটিংস: নোটিফিকেশন চালু/বন্ধ, দিনে কয়টা, কোন রোগীর গ্রুপের নোটিফিকেশন,
 * আর "রক্ত দরকার" তালিকায় কোন গ্রুপের রিকোয়েস্ট দেখাবে।
 */
@Composable
fun SettingsScreen(profile: Profile, onSaved: (Profile) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val compatible = profile.compatibleGroups

    var notify by remember { mutableStateOf(profile.notify) }
    var maxPush by remember { mutableIntStateOf(profile.maxPushPerDay) }
    var notifyGroups by remember { mutableStateOf(profile.effectiveNotifyGroups.toSet()) }
    var viewGroups by remember { mutableStateOf(profile.effectiveViewGroups.toSet()) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = onBack) { Text("← ফিরে যান") }
        Text("⚙️ সেটিংস", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        // ---- নোটিফিকেশন ----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("নোটিফিকেশন", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "আশেপাশে রক্ত দরকার হলে সাথে সাথে জানাবে",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = notify, onCheckedChange = { notify = it })
                }

                if (notify) {
                    Text("দিনে সর্বোচ্চ কয়টা নোটিফিকেশন চান", fontWeight = FontWeight.SemiBold)
                    // ছোট ফোনেও যেন জায়গা হয়, প্রতি সারিতে ৩টা
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Rules.PUSH_PER_DAY_CHOICES.chunked(3).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { n ->
                                    FilterChip(
                                        selected = maxPush == n,
                                        onClick = { maxPush = n },
                                        label = { Text("${bnDigits(n)}টা") },
                                    )
                                }
                            }
                        }
                    }

                    Text("কোন গ্রুপের রোগীর জন্য নোটিফিকেশন চান", fontWeight = FontWeight.SemiBold)
                    GroupMultiChips(compatible, notifyGroups) { g ->
                        if (g in notifyGroups && notifyGroups.size == 1) {
                            hint = "কমপক্ষে একটা গ্রুপ রাখতে হবে। কোনো নোটিফিকেশন না চাইলে উপরের সুইচ বন্ধ করুন।"
                        } else {
                            hint = null
                            notifyGroups = if (g in notifyGroups) notifyGroups - g else notifyGroups + g
                        }
                    }
                    Text(
                        "আপনার গ্রুপ (${profile.bloodGroup}) থেকে শুধু এই গ্রুপগুলোর রোগীকে রক্ত দেওয়া যায়, তাই নোটিফিকেশনের জন্য এগুলোই দেখানো হচ্ছে।",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // ---- তালিকায় কোন গ্রুপ ----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("\"রক্ত দরকার\" তালিকায় কোন গ্রুপের রিকোয়েস্ট দেখবেন", style = MaterialTheme.typography.titleMedium)
                GroupMultiChips(Rules.BLOOD_GROUPS, viewGroups) { g ->
                    if (g in viewGroups && viewGroups.size == 1) {
                        hint = "তালিকার জন্য কমপক্ষে একটা গ্রুপ রাখতে হবে।"
                    } else {
                        hint = null
                        viewGroups = if (g in viewGroups) viewGroups - g else viewGroups + g
                    }
                }
                Text(
                    "যাদের আপনি রক্ত দিতে পারেন না, তাদের রিকোয়েস্টও বেছে নিয়ে দেখতে পারেন — পরিচিতদের জানানোর জন্য। এগুলোর নোটিফিকেশন আসবে না।",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (viewGroups != compatible.toSet()) {
                    TextButton(onClick = { viewGroups = compatible.toSet() }) {
                        Text("শুধু যাদের রক্ত দিতে পারি")
                    }
                }
            }
        }

        InfoText(hint)
        ErrorText(errorMsg)

        Button(
            onClick = {
                val uid = AuthRepository.currentUid ?: return@Button
                // যাদের দিতে পারেন তাদের সবাই বাছা থাকলে null রাখা হয়, যাতে রক্তের গ্রুপ বদলালে নিজে থেকে মিলে যায়
                val updated = profile.copy(
                    notify = notify,
                    maxPushPerDay = maxPush,
                    notifyGroups = notifyGroups.takeIf { it != compatible.toSet() }
                        ?.let { s -> Rules.BLOOD_GROUPS.filter { it in s } },
                    viewGroups = viewGroups.takeIf { it != compatible.toSet() }
                        ?.let { s -> Rules.BLOOD_GROUPS.filter { it in s } },
                )
                scope.launch {
                    busy = true
                    errorMsg = null
                    runCatching { ProfileRepository.saveSettings(uid, updated) }
                        .onSuccess { onSaved(updated) }
                        .onFailure { errorMsg = "সেভ হয়নি: ${it.message}" }
                    busy = false
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { ButtonContent(busy, "সেভ করুন") }
    }
}

/** একাধিক রক্তের গ্রুপ বাছাইয়ের চিপ (প্রতি সারিতে ৪টা) */
@Composable
fun GroupMultiChips(options: List<String>, selected: Set<String>, onToggle: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { g ->
                    FilterChip(
                        selected = g in selected,
                        onClick = { onToggle(g) },
                        label = { Text(g, fontWeight = FontWeight.Bold) },
                    )
                }
            }
        }
    }
}

/** প্রোফাইল ট্যাবে সেটিংসের সংক্ষিপ্ত বিবরণ */
fun settingsSummary(p: Profile): String {
    val notif = if (!p.notify) {
        "নোটিফিকেশন বন্ধ"
    } else {
        "দিনে সর্বোচ্চ ${bnDigits(p.maxPushPerDay)}টা নোটিফিকেশন · ${p.effectiveNotifyGroups.joinToString(", ")}"
    }
    return "$notif\nতালিকায়: ${p.effectiveViewGroups.joinToString(", ")}"
}
