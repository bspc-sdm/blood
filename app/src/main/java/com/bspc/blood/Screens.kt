@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bspc.blood

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun LoginScreen(busy: Boolean, errorMsg: String?, onLogin: () -> Unit) {
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
            ButtonContent(busy, "Google দিয়ে লগইন করুন")
        }
        ErrorText(errorMsg)
    }
}

/** নাম, ফোন, রক্তের গ্রুপ, শেষ রক্তদান। onSave শুধু এই চারটা দেয়। */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    existing: Profile?,
    googleName: String?,
    busy: Boolean,
    errorMsg: String?,
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
        BloodGroupChips(selected = bloodGroup, onSelect = { bloodGroup = it })

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

        ErrorText(formError ?: errorMsg)

        Button(
            onClick = {
                val cleanPhone = Rules.normalizePhone(phone)
                formError = when {
                    name.isBlank() -> "নাম লিখুন"
                    cleanPhone == null -> "সঠিক মোবাইল নম্বর দিন (01 দিয়ে শুরু, ১১ সংখ্যা)"
                    bloodGroup.isEmpty() -> "রক্তের গ্রুপ বেছে নিন"
                    !neverDonated && lastDonation == null ->
                        "শেষ রক্তদানের তারিখ দিন, অথবা \"কখনো রক্ত দিইনি\" টিক দিন"
                    (lastDonation ?: 0L) > Rules.todayUtcMidnight() -> "ভবিষ্যতের তারিখ দেওয়া যাবে না"
                    else -> null
                }
                if (formError == null && cleanPhone != null) {
                    onSave(
                        Profile(
                            name = name.trim(),
                            phone = cleanPhone,
                            bloodGroup = bloodGroup,
                            lastDonationMillis = if (neverDonated) null else lastDonation,
                            notify = existing?.notify ?: true,
                        )
                    )
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ButtonContent(busy, "সেভ করুন")
        }
        if (onCancel != null) {
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("বাতিল") }
        }
    }

    if (showPicker) {
        DatePickerPopup(
            initial = lastDonation,
            onPicked = { lastDonation = it },
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
fun BloodGroupChips(selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Rules.BLOOD_GROUPS.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { group ->
                    FilterChip(
                        selected = selected == group,
                        onClick = { onSelect(group) },
                        label = { Text(group, fontWeight = FontWeight.Bold) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerPopup(initial: Long?, onPicked: (Long?) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onPicked(state.selectedDateMillis)
                onDismiss()
            }) { Text("ঠিক আছে") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("বাতিল") }
        },
    ) {
        DatePicker(state = state)
    }
}
