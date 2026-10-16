@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.bspc.blood

// FEATURE:jobs — পুরো ফাইলটা চাকরি ফিচারের: নিয়োগকর্তা (প্রতিষ্ঠান, বিজ্ঞাপন, আবেদনকারী)

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

// ------------------------------------------------------------
// "নিয়োগ দিন": প্রতিষ্ঠানের তথ্য আর বিজ্ঞাপন — চালু / বিরতি / বন্ধ আলাদা ভাগে
// ------------------------------------------------------------

@Composable
fun HirePanel(profile: Profile, refreshKey: Int, onEditJob: (String?) -> Unit, onOpenJob: (String) -> Unit, onOpenApplicants: (String) -> Unit) {
    var employer by remember { mutableStateOf<Employer?>(null) }
    var jobs by remember { mutableStateOf<List<Job>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    /** ০ চালু, ১ বিরতি ও অপেক্ষায়, ২ বন্ধ */
    var section by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(reload, refreshKey) {
        runCatching { JobsApi.myJobs() }.onSuccess { jobs = it.first; employer = it.second; errorMsg = null }.onFailure { errorMsg = it.message }
        loaded = true
    }
    if (editing) {
        androidx.activity.compose.BackHandler { editing = false }
        EmployerEditScreen(employer, profile, onBack = { editing = false }) { e ->
            editing = false
            employer = e
            infoMsg = if (e?.verifyStatus == "pending") tr("Saved. The Lifolo team will check the trade licence.", "সেভ হয়েছে। Lifolo টিম ট্রেড লাইসেন্স দেখে যাচাই করবে।") else tr("Saved", "সেভ হয়েছে")
            reload++
        }
        return
    }
    val live = jobs.filter { it.status == "open" }
    val waiting = jobs.filter { it.status in listOf("paused", "pending", "changes", "rejected") }
    val ended = jobs.filter { it.status in listOf("closed", "suspended") }
    val shown = when (section) { 0 -> live; 1 -> waiting; else -> ended }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column {
                ErrorText(errorMsg)
                InfoText(infoMsg)
            }
        }
        val e = employer
        when {
            !loaded -> item { LoadingBox() }
            e == null && errorMsg == null -> item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    EmptyState(
                        R.drawable.ic_nav_work,
                        tr("Need people for work?", "কাজের লোক দরকার?"),
                        tr(
                            "Post a job for free. People nearby apply with their CV and you call the ones you like. Never take money from job seekers.",
                            "বিনামূল্যে চাকরির বিজ্ঞাপন দিন। কাছের মানুষ সিভি দিয়ে আবেদন করবেন, পছন্দের জনকে ফোন করবেন। প্রার্থীর কাছ থেকে কোনো টাকা নেওয়া যাবে না।",
                        ),
                        Modifier.padding(top = 24.dp),
                    )
                    Button(onClick = { editing = true }, modifier = Modifier.fillMaxWidth()) {
                        ButtonContent(false, tr("Add organisation details", "প্রতিষ্ঠানের তথ্য দিন"), R.drawable.ic_add)
                    }
                }
            }
            e != null -> {
                item { EmployerCard(e, onEdit = { editing = true }) { reload++ } }
                if (!e.postingBlocked) {
                    item {
                        Button(onClick = { onEditJob(null) }, modifier = Modifier.fillMaxWidth()) { ButtonContent(false, tr("Post a job", "চাকরির বিজ্ঞাপন দিন"), R.drawable.ic_add) }
                    }
                }
                if (jobs.isNotEmpty()) item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = section == 0, onClick = { section = 0 }, label = { Text(tr("Live (${live.size})", "চালু (${num(live.size)})")) })
                        FilterChip(selected = section == 1, onClick = { section = 1 }, label = { Text(tr("Paused / waiting (${waiting.size})", "বিরতি / অপেক্ষায় (${num(waiting.size)})")) })
                        FilterChip(selected = section == 2, onClick = { section = 2 }, label = { Text(tr("Closed (${ended.size})", "বন্ধ (${num(ended.size)})")) })
                    }
                }
                if (jobs.isNotEmpty() && shown.isEmpty()) item { SmallNote(tr("Nothing here.", "এখানে কিছু নেই।")) }
                items(shown, key = { it.id }) { j -> MyJobCard(j, onOpen = { onOpenJob(j.id) }, onEdit = { onEditJob(j.id) }, onApplicants = { onOpenApplicants(j.id) }) }
            }
        }
    }
}

@Composable
private fun EmployerCard(e: Employer, onEdit: () -> Unit, onChanged: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                if (e.verified) {
                    Spacer(Modifier.size(6.dp))
                    AppIcon(R.drawable.ic_verified, null, Modifier.size(18.dp), cs.primary)
                }
            }
            Text(
                when (e.verifyStatus) {
                    "verified" -> tr("Verified employer — your jobs go live right away", "যাচাই করা নিয়োগকর্তা — বিজ্ঞাপন সাথে সাথে চালু হয়")
                    "pending" -> tr("Trade licence being checked", "ট্রেড লাইসেন্স যাচাই হচ্ছে")
                    "rejected" -> tr("Not verified", "যাচাই হয়নি")
                    else -> tr("Not verified — each job is checked before it goes live", "যাচাই করা নয় — প্রতিটা বিজ্ঞাপন দেখে চালু করা হয়")
                },
                style = MaterialTheme.typography.labelLarge, color = cs.primary,
            )
            if (e.postingBlocked) {
                IconLine(R.drawable.ic_warning, tr("The Lifolo team has stopped job posting for you. Reason: ", "Lifolo টিম আপনার বিজ্ঞাপন দেওয়া বন্ধ রেখেছে। কারণ: ") + e.blockReason, cs.error)
                AppealButton("other")
            }
            if (e.verifyStatus == "rejected" && e.statusReason.isNotBlank()) IconLine(R.drawable.ic_info, tr("Reason: ", "কারণ: ") + e.statusReason)
            DocsNeededNotice("employer", e.uid, VerifyDocs.employer(), missing = false, requested = e.docsRequested, note = e.docsNote, onDone = onChanged)
            if (e.verifyStatus in listOf("none", "rejected") && !e.docsRequested) {
                DocsNeededNotice(
                    "employer", e.uid, VerifyDocs.employer(), missing = true, requested = false,
                    note = tr("Add a trade licence to get the verified mark; then your jobs go live without waiting.", "ট্রেড লাইসেন্স দিলে \"যাচাই করা\" চিহ্ন পাবেন; তখন বিজ্ঞাপন অপেক্ষা ছাড়াই চালু হবে।"),
                    onDone = onChanged,
                )
            }
            IconLine(R.drawable.ic_call, num(e.phone))
            OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) { ButtonContent(false, tr("Edit details", "তথ্য বদলান"), R.drawable.ic_edit) }
        }
    }
}

@Composable
private fun MyJobCard(j: Job, onOpen: () -> Unit, onEdit: () -> Unit, onApplicants: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow), onClick = onOpen) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(j.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(JobText.jobStatus(j.status, j.pausedBy), style = MaterialTheme.typography.labelLarge, color = cs.primary)
            if (j.statusReason.isNotBlank() && j.status in listOf("rejected", "suspended", "changes")) IconLine(R.drawable.ic_info, tr("Reason: ", "কারণ: ") + j.statusReason)
            IconLine(R.drawable.ic_schedule, tr("Apply by ", "আবেদনের শেষ তারিখ ") + Rules.formatDate(j.deadline))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallAction(tr("Applicants (${j.applicants})", "আবেদনকারী (${num(j.applicants)})"), R.drawable.ic_group_round, onApplicants, primary = j.applicants > 0)
                if (j.status in listOf("open", "paused", "pending", "rejected", "changes")) SmallAction(tr("Edit", "বদলান"), R.drawable.ic_edit, onEdit)
            }
        }
    }
}

@Composable
private fun EmployerEditScreen(existing: Employer?, profile: Profile, onBack: () -> Unit, onSaved: (Employer?) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    remember { Areas.ensure(context) }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var kind by remember { mutableStateOf(existing?.kind ?: "") }
    var district by remember { mutableStateOf(existing?.district ?: profile.placeName) }
    var upazilaId by remember { mutableStateOf(existing?.upazilaId) }
    var address by remember { mutableStateOf(existing?.address ?: "") }
    var phone by remember { mutableStateOf(existing?.phone ?: profile.phone) }
    var website by remember { mutableStateOf(existing?.website ?: "") }
    var about by remember { mutableStateOf(existing?.about ?: "") }
    var docs by remember { mutableStateOf(mapOf<String, String>()) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    Scaffold(topBar = { BackTopBar(tr("Organisation details", "প্রতিষ্ঠানের তথ্য"), onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SmallNote(tr("Hiring for your home? Write your own name and choose \"Household\".", "বাসার কাজের জন্য হলে নিজের নাম লিখে \"বাসাবাড়ি\" বেছে নিন।"))
            OutlinedTextField(name, { name = it.take(100) }, label = { Text(tr("Organisation name", "প্রতিষ্ঠানের নাম")) }, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words), singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(tr("Type", "ধরন"), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                JobText.EMPLOYER_KINDS.forEach { k -> FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(JobText.employerKind(k)) }) }
            }
            OutlinedTextField(
                phone, { phone = it.filter { c -> c.isDigit() || c == '+' }.take(15) },
                label = { Text(tr("Phone for applicants", "প্রার্থীদের জন্য ফোন")) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            AreaChips(district, upazilaId, null, { d, u, _ -> district = d; upazilaId = u }, allowAllDistricts = false, showUnion = false)
            OutlinedTextField(address, { address = it.take(200) }, label = { Text(tr("Address", "ঠিকানা")) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(website, { website = it.trim().take(100) }, label = { Text(tr("Website or Facebook page (optional)", "ওয়েবসাইট বা ফেসবুক পেজ (ঐচ্ছিক)")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(about, { about = it.take(600) }, label = { Text(tr("About the organisation (optional)", "প্রতিষ্ঠান সম্পর্কে (ঐচ্ছিক)")) }, minLines = 3, modifier = Modifier.fillMaxWidth())
            if (existing?.verified != true) {
                DocPhotosField(
                    VerifyDocs.employer(), docs, { docs = it },
                    tr(
                        "Optional. With a trade licence you get the \"verified\" mark and your jobs go live without waiting.",
                        "ঐচ্ছিক। ট্রেড লাইসেন্স দিলে \"যাচাই করা\" চিহ্ন পাবেন, বিজ্ঞাপন অপেক্ষা ছাড়াই চালু হবে।",
                    ),
                )
            }
            ErrorText(errorMsg)
            Button(
                onClick = {
                    val d = district
                    errorMsg = when {
                        name.isBlank() -> tr("Write the organisation name", "প্রতিষ্ঠানের নাম লিখুন")
                        kind.isBlank() -> tr("Choose the type", "ধরন বেছে নিন")
                        !isContactPhone(phone) -> tr("Enter a valid phone number", "সঠিক ফোন নম্বর দিন")
                        d == null -> tr("Choose a district", "জেলা বেছে নিন")
                        docs.isNotEmpty() && existing == null && VerifyDocs.missing(VerifyDocs.employer(), docs) != null -> VerifyDocs.missing(VerifyDocs.employer(), docs)
                        else -> null
                    }
                    if (errorMsg != null || d == null) return@Button
                    scope.launch {
                        busy = true
                        runCatching { JobsApi.saveEmployer(TitleCase.name(name), kind, d, upazilaId, address.trim(), phone, website, about.trim(), docs) }
                            .onSuccess { onSaved(it) }.onFailure { errorMsg = it.message }
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { ButtonContent(busy, tr("Save", "সেভ করুন")) }
        }
    }
}

// ------------------------------------------------------------
// বিজ্ঞাপন দেওয়া / বদলানো
// ------------------------------------------------------------

/** ফর্মে আবেদনের একটা প্রশ্ন */
private data class QDraft(val id: String, val text: String, val kind: String, val options: String, val required: Boolean)

@Composable
fun JobEditScreen(id: String?, profile: Profile, onBack: () -> Unit, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    remember { Areas.ensure(context) }
    var loaded by remember { mutableStateOf(id == null) }
    var title by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("fulltime") }
    var district by remember { mutableStateOf(profile.placeName) }
    var upazilaId by remember { mutableStateOf<Int?>(null) }
    var salaryMin by remember { mutableStateOf("") }
    var salaryMax by remember { mutableStateOf("") }
    var negotiable by remember { mutableStateOf(false) }
    var paid by remember { mutableStateOf(true) }
    var months by remember { mutableStateOf("3") }
    var vacancies by remember { mutableStateOf("1") }
    var experience by remember { mutableStateOf("0") }
    var education by remember { mutableStateOf("none") }
    var skills by remember { mutableStateOf(listOf<String>()) }
    var description by remember { mutableStateOf("") }
    var deadline by remember { mutableStateOf(Rules.todayUtcMidnight() + 14 * DAY_MS) }
    var cover by remember { mutableStateOf("optional") }
    var questions by remember { mutableStateOf(listOf<QDraft>()) }
    var pickDate by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var doneMsg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(id) {
        if (id == null) return@LaunchedEffect
        runCatching { JobsApi.get(id).job }.onSuccess { j ->
            title = j.title; category = j.category; type = j.type; district = j.district; upazilaId = j.upazilaId
            salaryMin = if (j.salaryMin > 0) j.salaryMin.toString() else ""; salaryMax = if (j.salaryMax > 0) j.salaryMax.toString() else ""
            negotiable = j.salaryNegotiable; paid = j.paid; months = (j.durationMonths ?: 3).toString()
            vacancies = j.vacancies.toString(); experience = j.experienceYears.toString(); education = j.education
            skills = j.skills; description = j.description; cover = j.coverLetter
            questions = j.questions.map { QDraft(it.id, it.text, it.kind, it.options.joinToString(", "), it.required) }
            deadline = maxOf(j.deadline, Rules.todayUtcMidnight())
            loaded = true
        }.onFailure { errorMsg = it.message }
    }
    val intern = type == "internship"
    Scaffold(topBar = { BackTopBar(if (id == null) tr("Post a job", "চাকরির বিজ্ঞাপন দিন") else tr("Edit job", "বিজ্ঞাপন বদলান"), onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!loaded) {
                ErrorText(errorMsg)
                if (errorMsg == null) LoadingBox()
                return@Column
            }
            doneMsg?.let { msg ->
                InfoText(msg)
                Button(onClick = onSaved, modifier = Modifier.fillMaxWidth()) { Text(tr("Done", "ঠিক আছে")) }
                return@Column
            }
            OutlinedTextField(title, { title = it.take(100) }, label = { Text(tr("Job title (e.g. Salesman, Nurse)", "পদের নাম (যেমন সেলসম্যান, নার্স)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(tr("Field", "কাজের ক্ষেত্র"), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                JobText.CATEGORIES.forEach { c -> FilterChip(selected = category == c, onClick = { category = c }, label = { Text(JobText.category(c)) }) }
            }
            Text(tr("Type", "ধরন"), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                JobText.TYPES.forEach { t -> FilterChip(selected = type == t, onClick = { type = t }, label = { Text(JobText.type(t)) }) }
            }
            if (intern) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Paid internship", "বেতনসহ ইন্টার্নশিপ"), modifier = Modifier.weight(1f))
                    Switch(checked = paid, onCheckedChange = { paid = it })
                }
                OutlinedTextField(months, { months = it.filter(Char::isDigit).take(1) }, label = { Text(tr("Length (months, up to 6)", "মেয়াদ (মাস, সর্বোচ্চ ৬)")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            Text(if (type == "remote") tr("Where is the organisation?", "প্রতিষ্ঠান কোথায়?") else tr("Where is the work?", "কাজের জায়গা"), style = MaterialTheme.typography.labelLarge)
            AreaChips(district, upazilaId, null, { d, u, _ -> district = d; upazilaId = u }, allowAllDistricts = false, showUnion = false)
            if (!intern || paid) {
                Text(tr("Monthly salary (taka)", "মাসিক বেতন (টাকা)"), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(salaryMin, { salaryMin = it.filter(Char::isDigit).take(7) }, label = { Text(tr("From", "সর্বনিম্ন")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(salaryMax, { salaryMax = it.filter(Char::isDigit).take(7) }, label = { Text(tr("To", "সর্বোচ্চ")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Negotiable based on experience", "অভিজ্ঞতা অনুযায়ী আলোচনাসাপেক্ষ"), modifier = Modifier.weight(1f))
                    Switch(checked = negotiable, onCheckedChange = { negotiable = it })
                }
                SmallNote(tr("The salary can't be hidden: give a range, or mark it negotiable.", "বেতন লুকানো যায় না: সীমা দিন, অথবা \"আলোচনাসাপেক্ষ\" চালু করুন।"))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(vacancies, { vacancies = it.filter(Char::isDigit).take(3) }, label = { Text(tr("Posts", "কতজন")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(experience, { experience = it.filter(Char::isDigit).take(2) }, label = { Text(tr("Experience (years)", "অভিজ্ঞতা (বছর)")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.weight(1f))
            }
            Text(tr("Minimum education", "ন্যূনতম শিক্ষা"), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                JobText.EDUCATION.forEach { e -> FilterChip(selected = education == e, onClick = { education = e }, label = { Text(JobText.education(e)) }) }
            }
            WordsField(tr("Skills wanted (optional)", "যে দক্ষতা লাগবে (ঐচ্ছিক)"), skills, max = 15) { skills = it }
            OutlinedTextField(description, { description = it.take(3000) }, label = { Text(tr("Work, hours, benefits", "কাজ, সময়, সুবিধা")) }, minLines = 5, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { pickDate = true }) { ButtonContent(false, tr("Apply by ", "আবেদনের শেষ তারিখ ") + Rules.formatDate(deadline), R.drawable.ic_calendar_month) }

            SectionTitle(tr("Application form", "আবেদনের ফর্ম"))
            Text(tr("Cover letter", "কভার লেটার"), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("optional" to tr("Optional", "ঐচ্ছিক"), "required" to tr("Required", "লাগবেই"), "off" to tr("Off", "লাগবে না")).forEach { (k, l) ->
                    FilterChip(selected = cover == k, onClick = { cover = k }, label = { Text(l) })
                }
            }
            questions.forEachIndexed { i, q -> QuestionEditor(q, onChange = { nq -> questions = questions.mapIndexed { k, x -> if (k == i) nq else x } }) { questions = questions.filterIndexed { k, _ -> k != i } } }
            if (questions.size < 5) {
                TextButton(onClick = { questions = questions + QDraft("", "", "yesno", "", true) }) { ButtonContent(false, tr("Add a question", "প্রশ্ন যোগ করুন"), R.drawable.ic_add) }
            }
            SmallNote(tr("Asking applicants for any fee or deposit is not allowed; such jobs are taken down.", "প্রার্থীর কাছে কোনো ফি বা জামানত চাওয়া যাবে না; চাইলে বিজ্ঞাপন বন্ধ করা হয়।"))
            ErrorText(errorMsg)
            Button(
                onClick = {
                    val d = district
                    val needSalary = !intern || paid
                    val min = salaryMin.toIntOrNull() ?: 0
                    val max = salaryMax.toIntOrNull()?.takeIf { it > 0 } ?: min
                    val m = months.toIntOrNull() ?: 0
                    errorMsg = when {
                        title.isBlank() -> tr("Write the job title", "পদের নাম লিখুন")
                        category.isBlank() -> tr("Choose the field", "কাজের ক্ষেত্র বেছে নিন")
                        d == null -> tr("Choose a district", "জেলা বেছে নিন")
                        intern && m !in 1..6 -> tr("Internship length: 1 to 6 months", "ইন্টার্নশিপের মেয়াদ ১ থেকে ৬ মাস")
                        needSalary && !negotiable && (min < 500 || max < min) -> tr("Enter the monthly salary range, or mark it negotiable", "মাসিক বেতনের সীমা দিন, অথবা \"আলোচনাসাপেক্ষ\" চালু করুন")
                        needSalary && negotiable && min > 0 && (min < 500 || max < min) -> tr("Check the salary range", "বেতনের সীমা ঠিক করে লিখুন")
                        description.trim().length < 30 -> tr("Describe the work (at least 30 characters)", "কাজের বর্ণনা লিখুন (অন্তত ৩০ অক্ষর)")
                        questions.any { it.text.isBlank() } -> tr("Write each question or remove it", "প্রতিটা প্রশ্ন লিখুন, নইলে সরান")
                        questions.any { it.kind == "choice" && optionsOf(it).size < 2 } -> tr("Give at least 2 choices, separated by commas", "কমা দিয়ে অন্তত ২টা উত্তর বিকল্প দিন")
                        else -> null
                    }
                    if (errorMsg != null || d == null) return@Button
                    val body = JSONObject()
                        .put("title", title.trim()).put("category", category).put("type", type).put("district", d)
                        .put("upazilaId", upazilaId ?: JSONObject.NULL)
                        .put("salaryMin", if (needSalary) min else 0).put("salaryMax", if (needSalary) max else 0)
                        .put("salaryNegotiable", needSalary && negotiable).put("paid", !intern || paid)
                        .put("durationMonths", if (intern) m else JSONObject.NULL)
                        .put("vacancies", vacancies.toIntOrNull()?.coerceIn(1, 500) ?: 1)
                        .put("experienceYears", experience.toIntOrNull()?.coerceIn(0, 40) ?: 0)
                        .put("education", education).put("skills", JSONArray(skills)).put("description", description.trim())
                        .put("deadline", deadline).put("coverLetter", cover)
                        .put("questions", JSONArray(questions.map { q ->
                            JSONObject().put("id", q.id).put("text", q.text.trim()).put("kind", q.kind).put("required", q.required)
                                .put("options", JSONArray(if (q.kind == "choice") optionsOf(q) else emptyList()))
                        }))
                    scope.launch {
                        busy = true
                        runCatching { JobsApi.saveJob(id, body) }.onSuccess { (st, flagged) ->
                            doneMsg = when {
                                st == "open" -> tr("Your job is live.", "বিজ্ঞাপন চালু হয়েছে।")
                                st == "paused" -> tr("Saved. The job is paused — resume it from the job page.", "সেভ হয়েছে। বিজ্ঞাপনে বিরতি আছে — বিজ্ঞাপনের পাতা থেকে আবার চালু করুন।")
                                flagged -> tr("Sent for checking. Something in the post needs a look by the Lifolo team before it goes live.", "যাচাইয়ের জন্য পাঠানো হয়েছে। বিজ্ঞাপনের কিছু অংশ চালুর আগে Lifolo টিম দেখবে।")
                                else -> tr("Sent for checking. It goes live after the Lifolo team approves (usually within a day).", "যাচাইয়ের জন্য পাঠানো হয়েছে। Lifolo টিম অনুমোদন দিলে চালু হবে (সাধারণত এক দিনের মধ্যে)।")
                            }
                        }.onFailure { errorMsg = it.message }
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { ButtonContent(busy, if (id == null) tr("Post", "বিজ্ঞাপন দিন") else tr("Save", "সেভ করুন")) }
        }
    }
    if (pickDate) {
        val today = Rules.todayUtcMidnight()
        DatePickerPopup(deadline, { it?.let { v -> deadline = v } }, { pickDate = false }, minDate = today, maxDate = today + 89 * DAY_MS)
    }
}

private fun optionsOf(q: QDraft) = q.options.split(',', '،').map { it.trim().take(60) }.filter { it.isNotBlank() }.distinct().take(6)

@Composable
private fun QuestionEditor(q: QDraft, onChange: (QDraft) -> Unit, onRemove: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(q.text, { onChange(q.copy(text = it.take(200))) }, label = { Text(tr("Question", "প্রশ্ন")) }, modifier = Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("yesno" to tr("Yes / No", "হ্যাঁ / না"), "choice" to tr("Choices", "বাছাই"), "text" to tr("Written", "লিখে উত্তর")).forEach { (k, l) ->
                    FilterChip(selected = q.kind == k, onClick = { onChange(q.copy(kind = k)) }, label = { Text(l) })
                }
            }
            if (q.kind == "choice") {
                OutlinedTextField(q.options, { onChange(q.copy(options = it.take(400))) }, label = { Text(tr("Choices, separated by commas", "উত্তরগুলো, কমা দিয়ে আলাদা")) }, modifier = Modifier.fillMaxWidth())
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Must answer", "উত্তর দিতেই হবে"), modifier = Modifier.weight(1f))
                Switch(checked = q.required, onCheckedChange = { onChange(q.copy(required = it)) })
                TextButton(onClick = onRemove) { Text(tr("Remove", "সরান"), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

// ------------------------------------------------------------
// আবেদনকারী: ছাঁকা, সিভি, ফোন, আর ধাপে ধাপে সিদ্ধান্ত
// ------------------------------------------------------------

@Composable
fun JobApplicantsScreen(id: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var list by remember { mutableStateOf<List<Applicant>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<String?>(null) }
    var deciding by remember { mutableStateOf<Pair<Applicant, String>?>(null) }
    var showFilter by remember { mutableStateOf(false) }
    var fEdu by rememberSaveable { mutableStateOf<String?>(null) }
    var fExp by rememberSaveable { mutableStateOf<String?>(null) }
    var fSkill by rememberSaveable { mutableStateOf("") }
    var fArea by rememberSaveable { mutableStateOf<String?>(null) }
    var fStatus by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(id, reload) {
        runCatching { JobsApi.applicants(id) }.onSuccess { job = it.first; list = it.second; errorMsg = null }.onFailure { errorMsg = it.message }
    }
    val edu = fEdu
    val exp = fExp?.toIntOrNull()
    val skill = fSkill.trim()
    val area = fArea
    val status = fStatus
    val shown = list.filter { a ->
        (edu == null || JobText.EDUCATION.indexOf(a.cv.eduLevel) >= JobText.EDUCATION.indexOf(edu)) &&
            (exp == null || a.cv.experienceYears >= exp) &&
            (skill.isBlank() || a.cv.skills.any { it.contains(skill, ignoreCase = true) }) &&
            (area == null || a.cv.district == area) &&
            (status == null || a.status == status)
    }
    val active = buildList {
        fEdu?.let { add(ActiveFilter(tr("At least ", "কমপক্ষে ") + JobText.education(it)) { fEdu = null }) }
        fExp?.let { add(ActiveFilter(tr("$it+ years", "${num(it.toInt())}+ বছর")) { fExp = null }) }
        if (fSkill.isNotBlank()) add(ActiveFilter(fSkill) { fSkill = "" })
        fArea?.let { add(ActiveFilter(Districts.label(it)) { fArea = null }) }
        fStatus?.let { add(ActiveFilter(JobText.application(it)) { fStatus = null }) }
    }
    Scaffold(topBar = { BackTopBar(job?.title ?: tr("Applicants", "আবেদনকারী"), onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (list.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tr("${shown.size} of ${list.size}", "${num(list.size)} জনের মধ্যে ${num(shown.size)} জন"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            SmallAction(tr("Filter", "ছাঁকুন"), R.drawable.ic_tune, { showFilter = true })
                        }
                        if (active.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                active.forEach { f ->
                                    androidx.compose.material3.InputChip(selected = true, onClick = f.onRemove, label = { Text(f.label) },
                                        trailingIcon = { AppIcon(R.drawable.ic_close, tr("Remove", "সরান"), Modifier.size(16.dp)) })
                                }
                            }
                        }
                    }
                    ErrorText(errorMsg)
                    InfoText(infoMsg)
                }
            }
            when {
                job == null && errorMsg == null -> item { LoadingBox() }
                job != null && list.isEmpty() -> item {
                    EmptyState(R.drawable.ic_group_round, tr("No applications yet", "এখনো কেউ আবেদন করেননি"), tr("You'll get a notification when someone applies.", "কেউ আবেদন করলে নোটিফিকেশন পাবেন।"), Modifier.padding(top = 24.dp))
                }
                else -> items(shown, key = { it.uid }) { a ->
                    ApplicantCard(job, a, open == a.uid, onToggle = { open = if (open == a.uid) null else a.uid }, onCall = { callPhone(context, a.cv.phone) }, onEmail = { sendEmail(context, a.cv.email) }) { s -> deciding = a to s }
                }
            }
        }
    }
    if (showFilter) {
        FilterSheet(tr("Filter applicants", "আবেদনকারী ছাঁকুন"), onDismiss = { showFilter = false }, onClear = { fEdu = null; fExp = null; fSkill = ""; fArea = null; fStatus = null }) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                FilterChoice(tr("Education at least", "শিক্ষা কমপক্ষে"), JobText.EDUCATION.drop(1), fEdu, JobText::education) { fEdu = it }
                FilterChoice(tr("Experience at least", "অভিজ্ঞতা কমপক্ষে"), listOf("1", "2", "3", "5", "10"), fExp, { tr("$it+ years", "${num(it.toInt())}+ বছর") }) { fExp = it }
                OutlinedTextField(fSkill, { fSkill = it.take(40) }, label = { Text(tr("Has the skill", "এই দক্ষতা আছে")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                val areas = list.map { it.cv.district }.filter { it.isNotBlank() }.distinct()
                if (areas.size > 1) FilterChoice(tr("Lives in", "যে জেলায় থাকেন"), areas, fArea, { Districts.label(it) }) { fArea = it }
                FilterChoice(tr("Status", "অবস্থা"), list.map { it.status }.distinct(), fStatus, JobText::application) { fStatus = it }
            }
        }
    }
    deciding?.let { (a, s) ->
        DecisionDialog(job, a, s, onDismiss = { deciding = null }) { note, extra ->
            scope.launch {
                runCatching { JobsApi.setStatus(id, a.uid, s, note, extra) }
                    .onSuccess { deciding = null; infoMsg = tr("Sent to ", "জানানো হয়েছে: ") + a.cv.name; reload++; if (s == "interview") JobAlarms.sync(context) }
                    .onFailure { errorMsg = it.message; deciding = null }
            }
        }
    }
}

@Composable
private fun ApplicantCard(job: Job?, a: Applicant, expanded: Boolean, onToggle: () -> Unit, onCall: () -> Unit, onEmail: () -> Unit, onDecide: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CvHeader(a.cv, small = true)
            Text(
                tr("CV ${a.cv.completeness}% complete · applied ", "সিভি ${num(a.cv.completeness)}% পূরণ · আবেদন ") + Rules.formatDate(a.appliedAt + Rules.DHAKA_OFFSET_MS),
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
            )
            ApplicationStatusTag(a.status)
            if (a.status == "withdrawn") {
                val why = listOf(JobText.withdrawReason(a.extras.withdrawReason), a.extras.withdrawNote).filter { it.isNotBlank() }.joinToString(" · ")
                if (why.isNotBlank()) IconLine(R.drawable.ic_info, tr("Reason: ", "কারণ: ") + why)
            }
            a.extras.interview?.let { iv ->
                if (a.status == "interview") {
                    IconLine(R.drawable.ic_calendar_month, JobText.dateTime(iv.at) + " · " + if (iv.mode == "online") tr("Online", "অনলাইনে") else iv.place)
                    IconLine(
                        R.drawable.ic_info,
                        when (iv.response) {
                            "accepted" -> tr("Will come", "আসবেন")
                            "declined" -> tr("Can't come", "আসতে পারবেন না") + if (iv.responseNote.isNotBlank()) " · " + iv.responseNote else ""
                            "reschedule" -> tr("Asked for another time: ", "অন্য সময় চেয়েছেন: ") + iv.responseNote
                            else -> tr("Hasn't replied yet", "এখনো উত্তর দেননি")
                        },
                        if (iv.response == "accepted") cs.primary else cs.onSurfaceVariant,
                    )
                }
            }
            a.extras.offer?.let { o ->
                if (a.status in listOf("offered", "hired")) {
                    IconLine(R.drawable.ic_workspace_premium, o.designation + " · " + JobText.taka(o.salary) + " · " + tr("joining ", "যোগদান ") + Rules.formatDate(o.joiningDate))
                    IconLine(R.drawable.ic_info, when (o.response) {
                        "accepted" -> tr("Accepted the offer", "প্রস্তাব নিয়েছেন")
                        "declined" -> tr("Declined the offer", "প্রস্তাব ফিরিয়ে দিয়েছেন")
                        "expired" -> tr("The offer expired", "প্রস্তাবের মেয়াদ শেষ")
                        else -> tr("Reply due by ", "উত্তর দেবেন ") + Rules.formatDate(o.expiresAt) + tr("", "-এর মধ্যে")
                    })
                }
            }
            if (a.status == "rejected" && a.extras.reason.isNotBlank()) IconLine(R.drawable.ic_info, tr("Told: ", "জানানো হয়েছে: ") + JobText.rejectReason(a.extras.reason))
            if (a.coverNote.isNotBlank()) Text("“" + a.coverNote + "”", style = MaterialTheme.typography.bodyMedium)
            // প্রশ্নের উত্তর
            job?.questions?.forEach { q ->
                a.answers[q.id]?.let { ans ->
                    Text(q.text, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    Text(if (q.kind == "yesno") (if (ans == "yes") tr("Yes", "হ্যাঁ") else tr("No", "না")) else ans, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (a.note.isNotBlank()) IconLine(R.drawable.ic_info, tr("Your note: ", "আপনার কথা: ") + a.note)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallAction(if (expanded) tr("Hide CV", "সিভি লুকান") else tr("View CV", "সিভি দেখুন"), R.drawable.ic_badge, onToggle)
                if (a.cv.phone.isNotBlank()) SmallAction(tr("Call", "ফোন করুন"), R.drawable.ic_call, onCall, primary = true)
                if (a.cv.email.isNotBlank()) SmallAction(tr("Email", "ইমেইল"), R.drawable.ic_mail, onEmail)
            }
            if (a.cv.phone.isBlank() && a.status != "withdrawn") SmallNote(tr("This person keeps the phone number private. Shortlist or invite them — they get a notification.", "ইনি ফোন নম্বর গোপন রেখেছেন। শর্টলিস্ট বা আমন্ত্রণ পাঠালে নোটিফিকেশন পাবেন।"))
            if (expanded) CvBody(a.cv)
            // পরের ধাপ
            val next = when (a.status) {
                "seen", "applied" -> listOf("shortlisted", "interview", "rejected")
                "shortlisted" -> listOf("interview", "offered", "rejected")
                "interview" -> listOf("offered", "interview", "rejected")
                "offered" -> if (a.extras.offer?.response in listOf("declined", "expired")) listOf("offered", "rejected") else listOf("rejected")
                else -> emptyList()
            }
            if (next.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    next.forEach { s -> FilterChip(selected = false, onClick = { onDecide(s) }, label = { Text(decisionLabel(s, a.status)) }) }
                }
            }
        }
    }
}

private fun decisionLabel(s: String, current: String) = when (s) {
    "shortlisted" -> tr("Shortlist", "শর্টলিস্ট")
    "interview" -> if (current == "interview") tr("Change interview time", "ইন্টারভিউর সময় বদলান") else tr("Invite to interview", "ইন্টারভিউয়ে ডাকুন")
    "offered" -> if (current == "offered") tr("Send a new offer", "নতুন প্রস্তাব দিন") else tr("Send job offer", "চাকরির প্রস্তাব দিন")
    "rejected" -> tr("Not selected", "বাছাই হয়নি")
    else -> s
}

/** সিদ্ধান্তের ফর্ম: ইন্টারভিউ (তারিখ, সময়, ধরন, জায়গা/লিংক), প্রস্তাব (পদ, বেতন, যোগদান, মেয়াদ), বাছাই হয়নি (কারণ) */
@Composable
private fun DecisionDialog(job: Job?, a: Applicant, s: String, onDismiss: () -> Unit, onSend: (String, JSONObject) -> Unit) {
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    // ইন্টারভিউ
    var date by remember { mutableStateOf<Long?>(null) }
    var hour by remember { mutableStateOf<Int?>(null) }
    var minute by remember { mutableIntStateOf(0) }
    var mode by remember { mutableStateOf("inperson") }
    var place by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var pickDate by remember { mutableIntStateOf(0) }
    var pickTime by remember { mutableStateOf(false) }
    // প্রস্তাব
    var designation by remember { mutableStateOf(job?.title ?: "") }
    var salary by remember { mutableStateOf(job?.salaryMax?.takeIf { it > 0 }?.toString() ?: "") }
    var joining by remember { mutableStateOf<Long?>(null) }
    var expires by remember { mutableStateOf<Long?>(Rules.todayUtcMidnight() + 3 * DAY_MS) }
    // বাছাই হয়নি
    var reason by remember { mutableStateOf("") }
    val today = Rules.todayUtcMidnight()
    FormDialog(
        title = decisionLabel(s, a.status) + " — " + a.cv.name,
        onDismiss = onDismiss,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (s) {
                    "interview" -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { pickDate = 1 }) { Text(date?.let { Rules.formatDate(it) } ?: tr("Date", "তারিখ")) }
                            OutlinedButton(onClick = { pickTime = true }) { Text(hour?.let { Rules.formatTime(it, minute) } ?: tr("Time", "সময়")) }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = mode == "inperson", onClick = { mode = "inperson" }, label = { Text(tr("In person", "সামনাসামনি")) })
                            FilterChip(selected = mode == "online", onClick = { mode = "online" }, label = { Text(tr("Online", "অনলাইনে")) })
                        }
                        if (mode == "inperson") OutlinedTextField(place, { place = it.take(200) }, label = { Text(tr("Place", "জায়গা")) }, modifier = Modifier.fillMaxWidth())
                        else OutlinedTextField(link, { link = it.trim().take(300) }, label = { Text(tr("Meeting link (https://…)", "মিটিংয়ের লিংক (https://…)")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    "offered" -> {
                        OutlinedTextField(designation, { designation = it.take(80) }, label = { Text(tr("Designation", "পদ")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(salary, { salary = it.filter(Char::isDigit).take(7) }, label = { Text(tr("Monthly salary (taka)", "মাসিক বেতন (টাকা)")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { pickDate = 2 }) { Text(tr("Joining ", "যোগদান ") + (joining?.let { Rules.formatDate(it) } ?: "—")) }
                            OutlinedButton(onClick = { pickDate = 3 }) { Text(tr("Reply by ", "উত্তর ") + (expires?.let { Rules.formatDate(it) } ?: "—")) }
                        }
                        SmallNote(tr("If accepted, the job is added to their CV as current workplace.", "প্রার্থী নিলে তাঁর সিভিতে এই চাকরি বর্তমান কর্মস্থল হিসেবে যোগ হবে।"))
                    }
                    "rejected" -> {
                        Text(tr("Reason (the applicant sees a polite message)", "কারণ (প্রার্থী ভদ্র ভাষায় দেখবেন)"), style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            JobText.REJECT_REASONS.forEach { r -> FilterChip(selected = reason == r, onClick = { reason = r }, label = { Text(JobText.rejectReason(r)) }) }
                        }
                    }
                    else -> SmallNote(tr("The applicant gets a notification.", "প্রার্থী নোটিফিকেশন পাবেন।"))
                }
                OutlinedTextField(note, { note = it.take(300) }, label = { Text(tr("Note (optional)", "কিছু বলার থাকলে (ঐচ্ছিক)")) }, minLines = 2, modifier = Modifier.fillMaxWidth())
                ErrorText(err)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                val extra = JSONObject()
                err = when (s) {
                    "interview" -> {
                        val d = date
                        val h = hour
                        when {
                            d == null || h == null -> tr("Choose the date and time", "তারিখ আর সময় দিন")
                            Rules.dhakaMillis(d, h, minute) < System.currentTimeMillis() + 30 * 60_000L -> tr("Choose a time in the future", "সামনের কোনো সময় দিন")
                            mode == "inperson" && place.isBlank() -> tr("Write the place", "জায়গা লিখুন")
                            mode == "online" && !link.startsWith("http") -> tr("Give the meeting link", "মিটিংয়ের লিংক দিন")
                            else -> {
                                extra.put("interview", JSONObject().put("at", Rules.dhakaMillis(d, h, minute)).put("mode", mode).put("place", place.trim()).put("link", link.trim()))
                                null
                            }
                        }
                    }
                    "offered" -> {
                        val j = joining
                        val e = expires
                        when {
                            designation.isBlank() -> tr("Write the designation", "পদ লিখুন")
                            salary.isBlank() -> tr("Write the monthly salary", "মাসিক বেতন লিখুন")
                            j == null -> tr("Choose the joining date", "যোগদানের তারিখ দিন")
                            e == null -> tr("Choose the reply date", "উত্তর দেওয়ার শেষ তারিখ দিন")
                            else -> {
                                extra.put("offer", JSONObject().put("designation", designation.trim()).put("salary", salary.toIntOrNull() ?: 0).put("joiningDate", j).put("expiresAt", e))
                                null
                            }
                        }
                    }
                    "rejected" -> if (reason.isBlank()) tr("Choose a reason", "একটা কারণ বেছে নিন") else { extra.put("reason", reason); null }
                    else -> null
                }
                if (err == null) {
                    busy = true
                    onSend(note, extra)
                }
            }) { Text(tr("Send", "পাঠান")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "বাতিল")) } },
    )
    if (pickDate != 0) {
        val which = pickDate
        DatePickerPopup(
            when (which) { 1 -> date; 2 -> joining; else -> expires },
            { v -> if (v != null) when (which) { 1 -> date = v; 2 -> joining = v; else -> expires = v } },
            { pickDate = 0 },
            minDate = today,
            maxDate = today + (when (which) { 1 -> 89; 2 -> 180; else -> 30 }) * DAY_MS,
        )
    }
    if (pickTime) {
        TimePickerPopup(hour, minute, onPicked = { h, m -> hour = h; minute = m }, onDismiss = { pickTime = false }, title = tr("Interview time", "ইন্টারভিউর সময়"))
    }
}

/** কয়েকটা শব্দ (দক্ষতা, ভাষা): লিখে "যোগ" চাপলে চিপ, চিপে চাপলে বাদ */
@Composable
fun WordsField(label: String, words: List<String>, max: Int, maxLen: Int = 40, onChange: (List<String>) -> Unit) {
    var text by remember { mutableStateOf("") }
    fun add() {
        val add = text.split(',', '،', ';').map { it.trim().take(maxLen) }.filter { it.isNotBlank() }
        if (add.isNotEmpty()) onChange((words + add).distinctBy { it.lowercase() }.take(max))
        text = ""
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it.take(maxLen * 2) }, label = { Text(label) }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(onClick = { add() }, enabled = text.isNotBlank() && words.size < max) { Text(tr("Add", "যোগ")) }
        }
        if (words.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                words.forEach { w ->
                    androidx.compose.material3.InputChip(
                        selected = false,
                        onClick = { onChange(words - w) },
                        label = { Text(w) },
                        trailingIcon = { AppIcon(R.drawable.ic_close, tr("Remove", "সরান"), Modifier.size(16.dp)) },
                    )
                }
            }
        }
    }
}
