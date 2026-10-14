package com.bspc.blood

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun LoginScreen(busy: Boolean, error: String?, onLogin: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("🩸", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            "রক্তদান",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = BloodRed,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "আশেপাশের রক্তদাতা আর রক্তপ্রার্থীদের মধ্যে দ্রুত যোগাযোগ",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onLogin, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text("Google দিয়ে লগইন করুন")
            }
        }
        ErrorText(error)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    existing: Profile?,
    googleName: String?,
    busy: Boolean,
    error: String?,
    onSave: (Profile) -> Unit,
    onCancel: (() -> Unit)?,
) {
    var name by remember { mutableStateOf(existing?.name ?: googleName.orEmpty()) }
    var phone by remember { mutableStateOf(existing?.phone ?: "") }
    var bloodGroup by remember { mutableStateOf(existing?.bloodGroup ?: "") }
    var neverDonated by remember {
        mutableStateOf(existing != null && existing.lastDonationMillis == null)
    }
    var lastDonation by remember { mutableStateOf(existing?.lastDonationMillis) }
    var showPicker by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            if (existing == null) "আপনার প্রোফাইল" else "প্রোফাইল এডিট",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("নাম") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it },
            label = { Text("মোবাইল নম্বর") },
            placeholder = { Text("01XXXXXXXXX") },
            supportingText = { Text("শুধু \"রক্ত দেব\" চাপার পর অন্যজন নম্বরটা দেখতে পাবে") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth(),
        )

        Text("রক্তের গ্রুপ", style = MaterialTheme.typography.titleMedium)
        Rules.BLOOD_GROUPS.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { group ->
                    FilterChip(
                        selected = bloodGroup == group,
                        onClick = { bloodGroup = group },
                        label = { Text(group, fontWeight = FontWeight.Bold) },
                    )
                }
            }
        }

        Text("শেষ রক্তদানের তারিখ", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = neverDonated,
                onCheckedChange = {
                    neverDonated = it
                    if (it) lastDonation = null
                },
            )
            Text("কখনো রক্ত দিইনি")
        }
        if (!neverDonated) {
            OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth()) {
                Text(lastDonation?.let { Rules.formatDate(it) } ?: "তারিখ বেছে নিন")
            }
        }

        ErrorText(formError ?: error)

        Button(
            onClick = {
                val cleanPhone = Rules.normalizePhone(phone)
                formError = when {
                    name.isBlank() -> "নাম লিখুন"
                    cleanPhone == null -> "সঠিক মোবাইল নম্বর দিন (01 দিয়ে শুরু, ১১ সংখ্যা)"
                    bloodGroup.isEmpty() -> "রক্তের গ্রুপ বেছে নিন"
                    !neverDonated && lastDonation == null -> "শেষ রক্তদানের তারিখ দিন, অথবা \"কখনো রক্ত দিইনি\" টিক দিন"
                    (lastDonation ?: 0L) > System.currentTimeMillis() -> "ভবিষ্যতের তারিখ দেওয়া যাবে না"
                    else -> null
                }
                if (formError == null && cleanPhone != null) {
                    onSave(
                        Profile(
                            name = name.trim(),
                            phone = cleanPhone,
                            bloodGroup = bloodGroup,
                            lastDonationMillis = if (neverDonated) null else lastDonation,
                        )
                    )
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text("সেভ করুন")
            }
        }
        if (onCancel != null) {
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("বাতিল") }
        }
    }

    if (showPicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = lastDonation)
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    lastDonation = state.selectedDateMillis
                    showPicker = false
                }) { Text("ঠিক আছে") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("বাতিল") }
            },
        ) {
            DatePicker(state = state)
        }
    }
}

@Composable
fun HomeScreen(profile: Profile, onEdit: () -> Unit, onLogout: () -> Unit) {
    val eligible = Rules.isEligible(profile.lastDonationMillis)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "স্বাগতম, ${profile.name}",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("আপনার রক্তের গ্রুপ", style = MaterialTheme.typography.bodyMedium)
                Text(
                    profile.bloodGroup,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = BloodRed,
                )
                Text(
                    profile.lastDonationMillis?.let { "শেষ রক্তদান: ${Rules.formatDate(it)}" }
                        ?: "আগে কখনো রক্ত দেননি",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (eligible) Color(0xFFE8F5E9) else Color(0xFFFFF3E0),
            ),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (eligible) {
                    Text(
                        "✅ আপনি এখন রক্ত দিতে পারবেন",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                } else {
                    val next = Rules.nextEligibleMillis(profile.lastDonationMillis)!!
                    Text(
                        "⏳ আবার রক্ত দিতে পারবেন ${Rules.formatDate(next)} থেকে",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text("আর ${Rules.daysUntilEligible(profile.lastDonationMillis)} দিন বাকি")
                }
            }
        }

        Text(
            "রক্তের রিকোয়েস্ট দেওয়া আর আশেপাশের নোটিফিকেশন পরের আপডেটে আসছে।",
            style = MaterialTheme.typography.bodySmall,
        )

        OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
            Text("প্রোফাইল এডিট")
        }
        TextButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
            Text("লগআউট")
        }
    }
}

@Composable
private fun ErrorText(message: String?) {
    if (message != null) {
        Text(
            message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}
