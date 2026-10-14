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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** লগইন/সাইন আপ: ইমেইল দিন → ইমেইলে আসা ৬ সংখ্যার কোড দিন। প্রথমবার হলে নতুন অ্যাকাউন্ট তৈরি হয়। */
@Composable
fun LoginScreen(errorMsg: String?, onLoggedIn: () -> Unit) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var codeSent by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var resendAt by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // আবার কোড চাওয়ার কাউন্টডাউন
    LaunchedEffect(resendAt) {
        while (System.currentTimeMillis() < resendAt) {
            now = System.currentTimeMillis()
            delay(1000)
        }
        now = System.currentTimeMillis()
    }
    val waitSec = ((resendAt - now) / 1000).coerceAtLeast(0)

    fun sendCode() {
        val e = email.trim()
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(e).matches()) {
            localError = "সঠিক ইমেইল দিন"
            return
        }
        scope.launch {
            busy = true
            localError = null
            runCatching { ServerApi.sendLoginCode(e) }
                .onSuccess {
                    codeSent = true
                    code = ""
                    info = "$e ঠিকানায় ৬ সংখ্যার কোড পাঠানো হয়েছে। ইনবক্সে না পেলে Spam ফোল্ডার দেখুন।"
                    resendAt = System.currentTimeMillis() + 60_000
                }
                .onFailure { localError = it.message }
            busy = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
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

        OutlinedTextField(
            value = email,
            onValueChange = { email = it; if (codeSent) { codeSent = false; info = null } },
            label = { Text("ইমেইল") },
            placeholder = { Text("example@gmail.com") },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        if (!codeSent) {
            Button(onClick = { sendCode() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                ButtonContent(busy, "কোড পাঠান")
            }
            Text(
                "নতুন হলে এই ইমেইল দিয়েই অ্যাকাউন্ট খুলে যাবে। কোনো পাসওয়ার্ড লাগবে না।",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            InfoText(info)
            OutlinedTextField(
                value = code,
                onValueChange = { v -> code = v.filter { it.isDigit() }.take(6) },
                label = { Text("৬ সংখ্যার কোড") },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        localError = null
                        runCatching { AuthRepository.signInWithEmailCode(email.trim(), code) }
                            .onSuccess { onLoggedIn() }
                            .onFailure { localError = it.message }
                        busy = false
                    }
                },
                enabled = !busy && code.length == 6,
                modifier = Modifier.fillMaxWidth(),
            ) { ButtonContent(busy, "লগইন") }
            TextButton(onClick = { sendCode() }, enabled = !busy && waitSec == 0L) {
                Text(if (waitSec > 0) "আবার কোড চাইতে পারবেন $waitSec সেকেন্ড পর" else "আবার কোড পাঠান")
            }
        }
        ErrorText(localError ?: errorMsg)
    }
}

/** নাম, ফোন, রক্তের গ্রুপ, জন্ম তারিখ, ওজন, শেষ রক্তদান। লোকেশন আলাদাভাবে সেভ হয়। */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    existing: Profile?,
    defaultName: String?,
    busy: Boolean,
    errorMsg: String?,
    onSave: (Profile) -> Unit,
    onCancel: (() -> Unit)?,
) {
    var name by remember { mutableStateOf(existing?.name ?: defaultName.orEmpty()) }
    var phone by remember { mutableStateOf(existing?.phone ?: "") }
    var bloodGroup by remember { mutableStateOf(existing?.bloodGroup ?: "") }
    var birthDate by remember { mutableStateOf(existing?.birthDateMillis) }
    var weight by remember { mutableStateOf(existing?.weightKg?.toString() ?: "") }
    var showBirthPicker by remember { mutableStateOf(false) }
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

        Text("জন্ম তারিখ", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { showBirthPicker = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                birthDate?.let { "${Rules.formatDate(it)} (বয়স ${Rules.ageYears(it)} বছর)" }
                    ?: "জন্ম তারিখ বেছে নিন"
            )
        }

        OutlinedTextField(
            value = weight,
            onValueChange = { v -> weight = v.filter { it.isDigit() }.take(3) },
            label = { Text("ওজন (কেজি)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = { Text("রক্ত দিতে বয়স ${Rules.MIN_AGE}–${Rules.MAX_AGE} বছর আর ওজন কমপক্ষে ${Rules.MIN_WEIGHT_KG} কেজি লাগে") },
            modifier = Modifier.fillMaxWidth(),
        )

        Text("শেষ রক্তদানের তারিখ", style = MaterialTheme.typography.titleMedium)
        // একবার তারিখ দিলে সেটা শুধু সামনে এগোনো যায় (নিরাপত্তার নিয়মেও তাই)
        val savedDonation = existing?.lastDonationMillis
        if (savedDonation == null) {
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
        } else {
            Text(
                "আগে দেওয়া তারিখের চেয়ে পুরোনো তারিখ দেওয়া বা তারিখ মুছে ফেলা যায় না। ভুল হলে প্রোফাইল পাতা থেকে \"সংশোধনের অনুরোধ\" পাঠান।",
                style = MaterialTheme.typography.bodySmall,
            )
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
                    birthDate == null -> "জন্ম তারিখ দিন"
                    (Rules.ageYears(birthDate) ?: 0) < 10 || (Rules.ageYears(birthDate) ?: 0) > 100 -> "জন্ম তারিখ ঠিক নেই"
                    (weight.toIntOrNull() ?: 0) !in 20..250 -> "সঠিক ওজন দিন (কেজি)"
                    !neverDonated && lastDonation == null ->
                        "শেষ রক্তদানের তারিখ দিন, অথবা \"কখনো রক্ত দিইনি\" টিক দিন"
                    (lastDonation ?: 0L) > Rules.todayUtcMidnight() -> "ভবিষ্যতের তারিখ দেওয়া যাবে না"
                    savedDonation != null && (lastDonation ?: 0L) < savedDonation ->
                        "আগে দেওয়া তারিখের (${Rules.formatDate(savedDonation)}) চেয়ে পুরোনো তারিখ দেওয়া যায় না"
                    else -> null
                }
                if (formError == null && cleanPhone != null) {
                    onSave(
                        Profile(
                            name = name.trim(),
                            phone = cleanPhone,
                            bloodGroup = bloodGroup,
                            birthDateMillis = birthDate,
                            weightKg = weight.toIntOrNull(),
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

    if (showBirthPicker) {
        DatePickerPopup(
            initial = birthDate ?: Rules.todayUtcMidnight() - 25L * 365 * DAY_MS,
            onPicked = { birthDate = it },
            onDismiss = { showBirthPicker = false },
        )
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
