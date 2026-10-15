@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bspc.blood

import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** লগইন/সাইন আপ: ইমেইল দিন → ইমেইলে আসা ৬ সংখ্যার কোড দিন। প্রথমবার হলে নতুন অ্যাকাউন্ট তৈরি হয়। */
@Composable
fun LoginScreen(errorMsg: String?, onLoggedIn: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var codeSent by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
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
            localError = tr("Enter a valid email", "সঠিক ইমেইল দিন")
            return
        }
        scope.launch {
            busy = true
            localError = null
            runCatching { ServerApi.sendLoginCode(e) }
                .onSuccess {
                    codeSent = true
                    code = ""
                    resendAt = System.currentTimeMillis() + 60_000
                }
                .onFailure { localError = it.message }
            busy = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ভাষা বদলানোর সুযোগ লগইনের আগেই
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            SingleChoiceSegmentedButtonRow {
                listOf(Lang.EN to "EN", Lang.BN to "বাং").forEachIndexed { i, (l, label) ->
                    SegmentedButton(
                        selected = AppPrefs.lang.value == l,
                        onClick = { AppPrefs.setLang(context, l) },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                        icon = {},
                    ) { Text(label) }
                }
            }
        }
        Spacer(Modifier.height(64.dp))
        Logo(iconSize = 56.dp)
        Spacer(Modifier.height(48.dp))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it; if (codeSent) codeSent = false },
            label = { Text(tr("Email", "ইমেইল")) },
            leadingIcon = { AppIcon(R.drawable.ic_mail, null) },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        if (!codeSent) {
            Button(onClick = { sendCode() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                ButtonContent(busy, tr("Send code", "কোড পাঠান"))
            }
        } else {
            Text(
                tr("We sent a 6-digit code. Check Spam too.", "৬ সংখ্যার কোড পাঠানো হয়েছে। Spam ফোল্ডারও দেখুন।"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
            OutlinedTextField(
                value = code,
                onValueChange = { v -> code = v.filter { it.isDigit() }.take(6) },
                label = { Text(tr("6-digit code", "৬ সংখ্যার কোড")) },
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
            ) { ButtonContent(busy, tr("Log in", "লগইন")) }
            TextButton(onClick = { sendCode() }, enabled = !busy && waitSec == 0L) {
                Text(
                    if (waitSec > 0) tr("Resend in ${num(waitSec)}s", "${num(waitSec)} সেকেন্ড পর আবার পাঠান")
                    else tr("Resend code", "আবার কোড পাঠান")
                )
            }
        }
        ErrorText(localError ?: errorMsg)
    }
}

/** নাম, ফোন, রক্তের গ্রুপ, জন্ম তারিখ, ওজন, শেষ রক্তদান। লোকেশন আলাদাভাবে সেভ হয়। */
@Composable
fun ProfileEditScreen(
    existing: Profile?,
    busy: Boolean,
    errorMsg: String?,
    onSave: (Profile) -> Unit,
    onCancel: (() -> Unit)?,
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var phone by remember { mutableStateOf(existing?.phone ?: "") }
    var bloodGroup by remember { mutableStateOf(existing?.bloodGroup ?: "") }
    var birthDate by remember { mutableStateOf(existing?.birthDateMillis) }
    var weight by remember { mutableStateOf(existing?.weightKg?.toString() ?: "") }
    var showBirthPicker by remember { mutableStateOf(false) }
    var neverDonated by remember { mutableStateOf(existing != null && existing.lastDonationMillis == null) }
    var lastDonation by remember { mutableStateOf(existing?.lastDonationMillis) }
    var showPicker by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }
    val savedDonation = existing?.lastDonationMillis

    Scaffold(
        topBar = {
            if (onCancel != null) {
                BackTopBar(tr("Edit profile", "প্রোফাইল এডিট"), onCancel)
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (onCancel == null) {
                Text(tr("Your profile", "আপনার প্রোফাইল"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(80) },
                label = { Text(tr("Name", "নাম")) },
                leadingIcon = { AppIcon(R.drawable.ic_person_outline, null) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                label = { Text(tr("Mobile number", "মোবাইল নম্বর")) },
                leadingIcon = { AppIcon(R.drawable.ic_call, null) },
                placeholder = { Text("01XXXXXXXXX") },
                supportingText = { Text(tr("Shown only after you agree to donate", "শুধু \"রক্ত দেব\" চাপার পর অন্যজন দেখবেন")) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )

            Text(tr("Blood group", "রক্তের গ্রুপ"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            BloodGroupChips(selected = bloodGroup, onSelect = { bloodGroup = it }, allowUnknown = true)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showBirthPicker = true }, modifier = Modifier.weight(1f)) {
                    AppIcon(R.drawable.ic_calendar_month, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(birthDate?.let { Rules.formatDate(it) } ?: tr("Birth date", "জন্ম তারিখ"), maxLines = 1)
                }
                OutlinedTextField(
                    value = weight,
                    onValueChange = { v -> weight = v.filter { it.isDigit() }.take(3) },
                    label = { Text(tr("Weight (kg)", "ওজন (কেজি)")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }

            Text(tr("Last donation", "শেষ রক্তদান"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            // একবার তারিখ দিলে সেটা শুধু সামনে এগোনো যায় (নিরাপত্তার নিয়মেও তাই)
            if (savedDonation == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = neverDonated,
                        onCheckedChange = {
                            neverDonated = it
                            if (it) lastDonation = null
                        },
                    )
                    Text(tr("I have never donated", "কখনো রক্ত দিইনি"))
                }
            }
            if (savedDonation != null) {
                // আগে দেওয়া তারিখ এখানে নয়, প্রোফাইলের "শেষ রক্তদানের তারিখ বদলান" থেকে (সতর্কতা সহ)
                Text(Rules.formatDate(savedDonation), style = MaterialTheme.typography.bodyLarge)
                Text(
                    tr("To change it, use \"Change last donation date\" in Profile.", "বদলাতে প্রোফাইলের \"শেষ রক্তদানের তারিখ বদলান\" চাপুন।"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (!neverDonated) {
                OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth()) {
                    AppIcon(R.drawable.ic_history, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(lastDonation?.let { Rules.formatDate(it) } ?: tr("Pick date", "তারিখ বেছে নিন"))
                }
            }

            ErrorText(formError ?: errorMsg)

            Button(
                onClick = {
                    val cleanPhone = Rules.normalizePhone(phone)
                    val age = Rules.ageYears(birthDate) ?: 0
                    formError = when {
                        name.isBlank() -> tr("Enter your name", "নাম লিখুন")
                        cleanPhone == null -> tr("Enter a valid mobile number (01…, 11 digits)", "সঠিক মোবাইল নম্বর দিন (01 দিয়ে শুরু, ১১ সংখ্যা)")
                        bloodGroup.isEmpty() -> tr("Choose your blood group", "রক্তের গ্রুপ বেছে নিন")
                        birthDate == null -> tr("Add your birth date", "জন্ম তারিখ দিন")
                        age < 10 || age > 100 -> tr("Check your birth date", "জন্ম তারিখ ঠিক নেই")
                        (weight.toIntOrNull() ?: 0) !in 20..250 -> tr("Enter a valid weight (kg)", "সঠিক ওজন দিন (কেজি)")
                        !neverDonated && lastDonation == null ->
                            tr("Add your last donation date, or tick \"never donated\"", "শেষ রক্তদানের তারিখ দিন, অথবা \"কখনো রক্ত দিইনি\" টিক দিন")
                        (lastDonation ?: 0L) > Rules.todayUtcMidnight() -> tr("Date can't be in the future", "ভবিষ্যতের তারিখ দেওয়া যাবে না")
                        else -> null
                    }
                    if (formError == null && cleanPhone != null) {
                        onSave(
                            Profile(
                                name = TitleCase.name(name),
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
            ) { ButtonContent(busy, tr("Save", "সেভ করুন")) }
        }
    }

    if (showBirthPicker) {
        DatePickerPopup(
            initial = birthDate ?: (Rules.todayUtcMidnight() - 25L * 365 * DAY_MS),
            onPicked = { birthDate = it },
            onDismiss = { showBirthPicker = false },
        )
    }
    if (showPicker) {
        DatePickerPopup(initial = lastDonation, onPicked = { lastDonation = it }, onDismiss = { showPicker = false })
    }
}

@Composable
fun BloodGroupChips(selected: String, onSelect: (String) -> Unit, allowUnknown: Boolean = false) {
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
        // আন্দাজে ভুল গ্রুপ না দিয়ে "জানি না" — তাহলে ভুল রিকোয়েস্টে ডাক পড়ে না
        if (allowUnknown) {
            FilterChip(
                selected = selected == Rules.UNKNOWN_GROUP,
                onClick = { onSelect(Rules.UNKNOWN_GROUP) },
                label = { Text(tr("I don't know", "জানি না")) },
            )
            if (selected == Rules.UNKNOWN_GROUP) {
                Text(
                    tr(
                        "You won't get donation requests until you add your group. Get it tested at a hospital or blood bank, then update your profile.",
                        "গ্রুপ না দেওয়া পর্যন্ত রক্তদানের নোটিফিকেশন আসবে না। হাসপাতাল বা ব্লাড ব্যাংকে পরীক্ষা করে জেনে প্রোফাইলে দিন।",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** রক্ত লাগার সময় বাছাই (১২ ঘণ্টার ঘড়ি)। AlertDialog ছোট পর্দায় ঘড়ি কেটে দিতে পারে, তাই নিজস্ব Dialog। */
@Composable
fun TimePickerPopup(
    hour: Int?,
    minute: Int?,
    onPicked: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
    title: String = tr("When is blood needed?", "কখন রক্ত লাগবে?"),
) {
    val state = rememberTimePickerState(initialHour = hour ?: 12, initialMinute = minute ?: 0, is24Hour = false)
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
                )
                TimePicker(state = state)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(tr("Cancel", "বাতিল")) }
                    TextButton(onClick = {
                        onPicked(state.hour, state.minute)
                        onDismiss()
                    }) { Text(tr("OK", "ঠিক আছে")) }
                }
            }
        }
    }
}

@Composable
fun DatePickerPopup(initial: Long?, onPicked: (Long?) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onPicked(state.selectedDateMillis)
                onDismiss()
            }) { Text(tr("OK", "ঠিক আছে")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "বাতিল")) } },
    ) {
        DatePicker(state = state)
    }
}
