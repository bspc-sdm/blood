@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bspc.blood

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * সেটিংস (প্রোফাইল থেকে খোলে): ভাষা, থিম, নোটিফিকেশন, তালিকায় কোন গ্রুপ, এলাকা, অ্যাপ সম্পর্কে।
 * প্রতিটা বদল সাথে সাথে সেভ হয়।
 */
@Composable
fun SettingsScreen(
    profile: Profile,
    onProfileChanged: (Profile) -> Unit,
    onChangeLocation: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val compatible = profile.compatibleGroups

    fun save(updated: Profile) {
        val uid = AuthRepository.currentUid ?: return
        onProfileChanged(updated)
        scope.launch {
            runCatching { ProfileRepository.saveSettings(uid, updated) }
                .onSuccess { errorMsg = null }
                .onFailure { errorMsg = tr("Couldn't save. Check your internet.", "সেভ হয়নি, ইন্টারনেট দেখুন।") }
        }
    }

    /** সব মানানসই গ্রুপ বাছা থাকলে null রাখা হয়, যাতে রক্তের গ্রুপ বদলালে নিজে থেকে মিলে যায় */
    fun normalize(set: Set<String>, default: List<String>): List<String>? =
        if (set == default.toSet()) null else Rules.BLOOD_GROUPS.filter { it in set }

    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
    }

    Scaffold(topBar = { BackTopBar(tr("App settings", "অ্যাপ সেটিংস"), onBack) }) { padding ->
    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ErrorText(errorMsg)

        // ---- ভাষা ও থিম ----
        SettingsCard {
            SettingLabel(R.drawable.ic_language, tr("Language", "ভাষা"))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                listOf(Lang.EN to "English", Lang.BN to "বাংলা").forEachIndexed { i, (l, label) ->
                    SegmentedButton(
                        selected = AppPrefs.lang.value == l,
                        onClick = { AppPrefs.setLang(context, l) },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                    ) { Text(label) }
                }
            }
            HorizontalDivider(Modifier.padding(top = 16.dp))
            SettingLabel(R.drawable.ic_dark_mode, tr("Theme", "থিম"))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                val modes = listOf(
                    ThemeMode.SYSTEM to tr("System", "ফোনের মতো"),
                    ThemeMode.LIGHT to tr("Light", "লাইট"),
                    ThemeMode.DARK to tr("Dark", "ডার্ক"),
                )
                modes.forEachIndexed { i, (m, label) ->
                    SegmentedButton(
                        selected = AppPrefs.theme.value == m,
                        onClick = { AppPrefs.setTheme(context, m) },
                        shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                    ) { Text(label, maxLines = 1) }
                }
            }
        }

        // ---- নোটিফিকেশন ----
        SettingsCard {
            ListItem(
                headlineContent = { Text(tr("Notifications", "নোটিফিকেশন")) },
                supportingContent = { Text(tr("When someone nearby needs blood", "আশেপাশে কারো রক্ত লাগলে")) },
                leadingContent = { AppIcon(R.drawable.ic_notifications, null) },
                trailingContent = {
                    Switch(checked = profile.notify, onCheckedChange = { save(profile.copy(notify = it)) })
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            if (profile.notify) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("Maximum per day", "দিনে সর্বোচ্চ"), style = MaterialTheme.typography.labelLarge)
                    ChipRows(Rules.PUSH_PER_DAY_CHOICES.map { it.toString() }, setOf(profile.maxPushPerDay.toString()), perRow = 5, label = { num(it) }) {
                        save(profile.copy(maxPushPerDay = it.toInt()))
                    }
                    Text(tr("For patient blood groups", "রোগীর যে গ্রুপগুলোর জন্য"), style = MaterialTheme.typography.labelLarge)
                    val selected = profile.effectiveNotifyGroups.toSet()
                    ChipRows(compatible, selected) { g ->
                        val next = if (g in selected) selected - g else selected + g
                        if (next.isNotEmpty()) save(profile.copy(notifyGroups = normalize(next, compatible)))
                    }
                    Text(
                        tr(
                            "Only groups you (${profile.bloodGroup}) can donate to.",
                            "শুধু যাদের আপনি (${profile.bloodGroup}) রক্ত দিতে পারেন।",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ---- কতদূর যেতে রাজি ও যাতায়াত খরচ ----
        SettingsCard {
            SettingLabel(R.drawable.ic_location_on, tr("How far can you go to donate?", "রক্ত দিতে কতদূর যেতে পারবেন?"))
            Column(Modifier.padding(start = 16.dp, end = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChipRows(
                    Rules.RADIUS_CHOICES.map { it.toString() },
                    setOf(profile.maxKm.toString()),
                    perRow = 4,
                    label = { num(it) + tr(" km", " কিমি") },
                ) { save(profile.copy(maxKm = it.toInt())) }
                Text(
                    tr("You'll be notified only for requests within this distance.", "এই দূরত্বের মধ্যে রিকোয়েস্ট হলেই নোটিফিকেশন পাবেন।"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(Modifier.padding(top = 16.dp))
            SettingLabel(R.drawable.ic_directions_run, tr("Travel cost", "যাতায়াত খরচ"))
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChipRows(
                    listOf("none") + Rules.FARE_AFTER_CHOICES.map { it.toString() },
                    setOf(profile.fareAfterKm?.toString() ?: "none"),
                    perRow = 2,
                    label = {
                        if (it == "none") tr("Not needed", "লাগবে না")
                        else tr("Beyond ${num(it)} km", "${num(it)} কিমির বেশি হলে")
                    },
                ) { save(profile.copy(fareAfterKm = it.toIntOrNull())) }
                Text(
                    tr(
                        "If a request doesn't cover travel cost and it's farther than this, you won't be notified.",
                        "রিকোয়েস্টে যাতায়াত খরচ না থাকলে আর এর চেয়ে দূরে হলে নোটিফিকেশন আসবে না।",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---- তালিকা ও এলাকা ----
        SettingsCard {
            SettingLabel(R.drawable.ic_tune, tr("Show requests for", "যে গ্রুপের রিকোয়েস্ট দেখাবে"))
            Column(Modifier.padding(start = 16.dp, end = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val selected = profile.effectiveViewGroups.toSet()
                ChipRows(Rules.BLOOD_GROUPS, selected) { g ->
                    val next = if (g in selected) selected - g else selected + g
                    if (next.isNotEmpty()) save(profile.copy(viewGroups = normalize(next, compatible)))
                }
            }
            HorizontalDivider(Modifier.padding(top = 16.dp))
            ListItem(
                headlineContent = { Text(tr("Area", "এলাকা")) },
                supportingContent = { Text(Districts.label(profile.placeName)) },
                leadingContent = { AppIcon(R.drawable.ic_location_on, null) },
                trailingContent = { AppIcon(R.drawable.ic_chevron_right, null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable(onClick = onChangeLocation),
            )
        }

        // ---- অ্যাপ সম্পর্কে ----
        SettingsCard {
            ListItem(
                headlineContent = { Text(tr("Privacy policy", "প্রাইভেসি পলিসি")) },
                leadingContent = { AppIcon(R.drawable.ic_privacy_tip, null) },
                trailingContent = { AppIcon(R.drawable.ic_chevron_right, null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { openUrl(context, PRIVACY_URL) },
            )
            ListItem(
                headlineContent = { Text(tr("Terms of use", "ব্যবহারের শর্ত")) },
                leadingContent = { AppIcon(R.drawable.ic_list_alt, null) },
                trailingContent = { AppIcon(R.drawable.ic_chevron_right, null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { openUrl(context, TERMS_URL) },
            )
            ListItem(
                headlineContent = { Text(tr("Contact support", "সাপোর্টে যোগাযোগ")) },
                supportingContent = { Text(SUPPORT_EMAIL) },
                leadingContent = { AppIcon(R.drawable.ic_mail, null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { sendEmail(context, SUPPORT_EMAIL) },
            )
            ListItem(
                headlineContent = { Text(tr("Version", "ভার্সন")) },
                supportingContent = { Text(num(version)) },
                leadingContent = { AppIcon(R.drawable.ic_info, null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) { Column { content() } }
}

@Composable
private fun SettingLabel(icon: Int, text: String) {
    ListItem(
        headlineContent = { Text(text) },
        leadingContent = { AppIcon(icon, null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** চিপের সারি (একাধিক বাছাই) */
@Composable
fun ChipRows(
    options: List<String>,
    selected: Set<String>,
    perRow: Int = 4,
    label: (String) -> String = { it },
    onToggle: (String) -> Unit,
) {
    // জায়গা না থাকলে চিপ নিজে থেকেই পরের লাইনে যায় (লম্বা লেখা কাটা পড়ে না); perRow এখন শুধু পুরোনো ডাকের জন্য
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { g ->
            FilterChip(
                selected = g in selected,
                onClick = { onToggle(g) },
                label = { Text(label(g), fontWeight = FontWeight.Bold) },
            )
        }
    }
}
