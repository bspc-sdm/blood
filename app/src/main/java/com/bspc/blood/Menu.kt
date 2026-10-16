package com.bspc.blood

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

// ------------------------------------------------------------
// উপরের ডান কোণের ☰ মেনু: আগে প্রোফাইলের ছবিতে চাপলে যে "সেটিংস" পাতা আসত, তার সেটিংসের অংশ।
// প্রোফাইল (ছবি, সিভি, রক্তদান, ব্যাজ) এখন নিচের বারের "প্রোফাইল" ট্যাবে।
// ------------------------------------------------------------

@Composable
fun MenuScreen(
    community: CommunityMe?,
    onBack: () -> Unit,
    onOpenMyRequests: () -> Unit,
    onOpenMyAppointments: () -> Unit,
    onOpenSettings: () -> Unit,
    onEditProfile: () -> Unit,
    onOpenAdmin: () -> Unit,
    onLogout: () -> Unit,
    onAccountDeleted: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    Scaffold(topBar = { BackTopBar(tr("Menu", "মেনু"), onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ErrorText(errorMsg)
            MenuCard {
                MenuRow(R.drawable.ic_list_alt, tr("My blood requests", "আমার রক্তের রিকোয়েস্ট"), null, onOpenMyRequests)
                HorizontalDivider()
                MenuRow(R.drawable.ic_calendar_month, tr("My appointments", "আমার অ্যাপয়েন্টমেন্ট"), null, onOpenMyAppointments)
            }
            MenuCard {
                MenuRow(R.drawable.ic_edit, tr("Personal details", "ব্যক্তিগত তথ্য"), tr("Name, phone, blood group, birth date, weight", "নাম, ফোন, রক্তের গ্রুপ, জন্মতারিখ, ওজন"), onEditProfile)
                HorizontalDivider()
                MenuRow(
                    R.drawable.ic_settings_outline,
                    tr("App settings", "অ্যাপ সেটিংস"),
                    tr("Language, theme, notifications, area, privacy", "ভাষা, থিম, নোটিফিকেশন, এলাকা, প্রাইভেসি"),
                    onOpenSettings,
                )
                HorizontalDivider()
                MenuRow(
                    R.drawable.ic_help,
                    tr("Help & appeals", "সাহায্য ও আপিল"),
                    tr("A problem, or something of yours was suspended? Write to us.", "কোনো সমস্যা, বা কিছু বন্ধ হয়ে গেছে? আমাদের লিখুন।"),
                    { HelpNav.open.value = "" },
                )
                if (community?.isAdmin == true || AuthRepository.currentEmail.equals(SUPPORT_EMAIL, ignoreCase = true)) {
                    HorizontalDivider()
                    MenuRow(R.drawable.ic_admin_panel_settings, tr("Admin", "অ্যাডমিন"), null, onOpenAdmin)
                }
            }
            MenuCard {
                MenuRow(R.drawable.ic_logout, tr("Log out", "লগআউট"), AuthRepository.currentEmail, onLogout)
                HorizontalDivider()
                MenuRow(R.drawable.ic_delete, tr("Delete account", "অ্যাকাউন্ট মুছুন"), null, { confirmDelete = true }, MaterialTheme.colorScheme.error)
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { if (!deleting) confirmDelete = false },
            title = { Text(tr("Delete your account?", "অ্যাকাউন্ট মুছবেন?")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        tr(
                            "Your profile, CV, photo, phone number, requests, questions, answers, tips, appointments, job applications and badge will be deleted permanently. This can't be undone.",
                            "আপনার প্রোফাইল, সিভি, ছবি, ফোন নম্বর, রিকোয়েস্ট, প্রশ্ন, উত্তর, টিপস, অ্যাপয়েন্টমেন্ট, চাকরির আবেদন আর ব্যাজ চিরতরে মুছে যাবে। এটা আর ফেরানো যাবে না।",
                        ),
                    )
                    if (deleting) CircularProgressIndicator(Modifier.size(24.dp))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            deleting = true
                            runCatching { Moderation.deleteAccount() }
                                .onSuccess {
                                    confirmDelete = false
                                    onAccountDeleted()
                                }
                                .onFailure {
                                    errorMsg = it.message
                                    confirmDelete = false
                                }
                            deleting = false
                        }
                    },
                    enabled = !deleting,
                ) { Text(tr("Delete permanently", "চিরতরে মুছুন"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }, enabled = !deleting) { Text(tr("Cancel", "বাতিল")) } },
        )
    }
}

@Composable
private fun MenuCard(content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        content()
    }
}

@Composable
internal fun MenuRow(icon: Int, title: String, subtitle: String?, onClick: (() -> Unit)?, color: Color = Color.Unspecified) {
    ListItem(
        headlineContent = { Text(title, color = color) },
        supportingContent = if (subtitle != null) ({ Text(subtitle) }) else null,
        leadingContent = { AppIcon(icon, null, tint = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else color) },
        trailingContent = if (onClick != null) ({ AppIcon(R.drawable.ic_chevron_right, null) }) else null,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    )
}
