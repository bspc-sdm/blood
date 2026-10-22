package com.bspc.blood

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** বদল সেভ হওয়া পাতা ছেড়ে গেলেও শেষ হয় (চাপ দিয়েই "ফিরে যান" চাপলে বদল হারায় না) */
private val notifySaveScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

/**
 * সেটিংসের "নোটিফিকেশন" কার্ডের ভেতর: "সব নোটিফিকেশন" (মূল সুইচ), তারপর প্রতিটা ধরনের সুইচ (NotifyCats)।
 * "আশেপাশে রক্ত লাগলে"-র নিচে তার আগের পছন্দগুলো (nearbyExtras: দিনে সর্বোচ্চ, গ্রুপ)। বদল সাথে সাথে দেখায়, সার্ভারে
 * সেভ না হলে আগের অবস্থায় ফেরে। জরুরি আহ্বান/অ্যাম্বুলেন্স বন্ধের আগে নিশ্চিত করা হয়। "আমার ব্যবসা" শুধু যাঁর ব্যবসা,
 * চেম্বার বা বাসায় সেবা আছে; অ্যাকাউন্ট সবসময় চালু (বদলানো যায় না)। শেষে ফোনের সেটিংসের নোটিফিকেশন পাতা।
 */
@Composable
fun NotifySettingsSection(
    profile: Profile,
    onProfileChanged: (Profile) -> Unit,
    nearbyExtras: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val latestProfile by rememberUpdatedState(profile)
    val latestOnChanged by rememberUpdatedState(onProfileChanged)
    var prefs by remember { mutableStateOf<NotifyPrefs?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var opSeq by remember { mutableIntStateOf(0) }
    var confirmOff by remember { mutableStateOf<String?>(null) }

    /** blood_nearby = profile.notify: প্রোফাইলের কপিও মিলিয়ে রাখা (হোম, আর নিচের পছন্দগুলো দেখানো) */
    fun syncNearby(p: NotifyPrefs) {
        val on = p.isOn(NotifyCats.BLOOD_NEARBY)
        if (latestProfile.notify != on) latestOnChanged(latestProfile.copy(notify = on))
    }

    LaunchedEffect(reload) {
        loadError = null
        runCatching { NotifyPrefsApi.get() }
            .onSuccess { prefs = it; syncNearby(it) }
            .onFailure { loadError = it.message ?: tr("Couldn't load. Check your internet.", "লোড হয়নি, ইন্টারনেট দেখুন।") }
    }

    /** all বা একটা ধরন বদল: আগে দেখানো, তারপর সার্ভারে; না হলে শুধু সেই বদলটা ফেরানো (পরে অন্য বদল হয়ে থাকলে সেটা থাকে) */
    fun change(all: Boolean? = null, cat: String? = null, on: Boolean = false) {
        val before = prefs ?: return
        val optimistic = if (all != null) before.copy(all = all) else before.with(cat!!, on)
        prefs = optimistic
        syncNearby(optimistic)
        val seq = ++opSeq
        notifySaveScope.launch {
            runCatching { NotifyPrefsApi.set(all, if (cat != null) mapOf(cat to on) else emptyMap()) }
                .onSuccess { r ->
                    saveError = null
                    // পরে আরেকটা বদল চলতে থাকলে তার উত্তরই শেষ কথা
                    if (seq == opSeq) { prefs = r; syncNearby(r) }
                }
                .onFailure {
                    val cur = prefs ?: return@onFailure
                    val back = when {
                        all != null -> if (cur.all == all) cur.copy(all = before.all) else cur
                        else -> if (cur.isOn(cat!!) == on) cur.with(cat, before.isOn(cat)) else cur
                    }
                    prefs = back
                    syncNearby(back)
                    saveError = tr("Couldn't save. Check your internet.", "সেভ হয়নি, ইন্টারনেট দেখুন।")
                }
        }
    }

    val p = prefs
    val master = p?.all ?: true
    SwitchRow(
        title = tr("All notifications", "সব নোটিফিকেশন"),
        subtitle = if (master) tr("Choose below what you want to hear about", "নিচে বেছে নিন কোন খবর পাবেন")
        else tr("Off: only account and security messages", "বন্ধ: শুধু অ্যাকাউন্ট আর নিরাপত্তার খবর আসবে"),
        icon = R.drawable.ic_notifications,
        checked = master,
        enabled = p != null,
        onChange = { change(all = it) },
    )
    when {
        p == null && loadError != null -> Column(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ErrorText(loadError)
            TextButton(onClick = { reload++ }) { Text(tr("Try again", "আবার চেষ্টা করুন")) }
        }
        p == null -> Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.Center) {
            CircularProgressIndicator(Modifier.size(24.dp))
        }
        else -> {
            if (saveError != null) Column(Modifier.padding(horizontal = 16.dp)) { ErrorText(saveError) }
            HorizontalDivider()
            val showBusiness = MyWork.has
            NotifyCats.SETTABLE.filter { it != NotifyCats.BUSINESS || showBusiness }.forEach { cat ->
                val on = p.isOn(cat)
                SwitchRow(
                    title = NotifyCats.label(cat),
                    subtitle = NotifyCats.description(cat),
                    checked = on,
                    enabled = master,
                    onChange = { v ->
                        if (!v && cat in NotifyCats.CONFIRM_OFF) confirmOff = cat else change(cat = cat, on = v)
                    },
                )
                if (cat == NotifyCats.BLOOD_NEARBY && on && master) nearbyExtras()
            }
            SwitchRow(
                title = NotifyCats.label(NotifyCats.ACCOUNT),
                subtitle = NotifyCats.description(NotifyCats.ACCOUNT),
                checked = true,
                enabled = false,
                onChange = {},
                // বদলানো যায় না, তবে চালু — ঝাপসা নয়
                dim = false,
            )
        }
    }
    HorizontalDivider()
    ListItem(
        headlineContent = { Text(tr("Notifications in phone settings", "ফোনের সেটিংসে নোটিফিকেশন")) },
        supportingContent = { Text(tr("Sound, vibration and pop-up for each type", "প্রতিটা ধরনের শব্দ, কম্পন আর ভেসে ওঠা")) },
        leadingContent = { AppIcon(R.drawable.ic_settings_outline, null) },
        trailingContent = { AppIcon(R.drawable.ic_chevron_right, null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { Notifier.openSystemSettings(context) },
    )

    confirmOff?.let { cat ->
        AlertDialog(
            onDismissRequest = { confirmOff = null },
            title = { Text(tr("You won't get urgent news. Sure?", "জরুরি খবর পাবেন না — নিশ্চিত?")) },
            text = { Text(NotifyCats.label(cat) + ": " + NotifyCats.description(cat)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmOff = null
                    change(cat = cat, on = false)
                }) { Text(tr("Turn off", "বন্ধ করুন")) }
            },
            dismissButton = { TextButton(onClick = { confirmOff = null }) { Text(tr("Cancel", "বাতিল")) } },
        )
    }
}

/** পুরো সারি চাপলেই সুইচ বদলায় (লেখায় চাপলেও) — টকব্যাক আর Maestro সারিটাকে একটা সুইচ হিসেবে পায় */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
    icon: Int? = null,
    dim: Boolean = !enabled,
) {
    val lead: (@Composable () -> Unit)? = icon?.let { i -> @Composable { AppIcon(i, null) } }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = lead,
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .alpha(if (dim) 0.6f else 1f),
    )
}
