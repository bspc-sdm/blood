@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.bspc.blood

// FEATURE:jobs — পুরো ফাইলটা চাকরি ফিচারের: বিস্তারিত, আবেদন, ইন্টারভিউ/প্রস্তাবে সাড়া, তুলে নেওয়া

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun JobDetailScreen(id: String, onBack: () -> Unit, onEditCv: () -> Unit, onEditJob: (String) -> Unit, onOpenApplicants: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var d by remember { mutableStateOf<JobDetail?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    var cvMissing by remember { mutableStateOf(false) }
    var applying by remember { mutableStateOf(false) }
    var withdrawing by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(id, reload) {
        runCatching { JobsApi.get(id) }.onSuccess { d = it; errorMsg = null }.onFailure { errorMsg = it.message }
    }
    fun act(done: String, block: suspend () -> Unit) {
        scope.launch {
            busy = true
            errorMsg = null
            runCatching { block() }.onSuccess { infoMsg = done; reload++ }.onFailure { errorMsg = it.message }
            busy = false
        }
    }
    Scaffold(topBar = { BackTopBar(tr("Job", "চাকরি"), onBack) }) { padding ->
        val x = d
        if (x == null) {
            Column(Modifier.padding(padding).padding(16.dp)) {
                ErrorText(errorMsg)
                if (errorMsg == null) LoadingBox()
            }
            return@Scaffold
        }
        val j = x.job
        val cs = MaterialTheme.colorScheme
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(j.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(j.orgName, style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant)
                        if (j.employerVerified) {
                            Spacer(Modifier.size(6.dp))
                            AppIcon(R.drawable.ic_verified, null, Modifier.size(18.dp), cs.primary)
                            Text(tr(" Verified", " যাচাই করা"), style = MaterialTheme.typography.labelMedium, color = cs.primary)
                        }
                    }
                }
                if (!j.mine && j.status in listOf("open", "paused")) {
                    androidx.compose.material3.IconButton(onClick = {
                        act(if (j.saved) tr("Removed from saved", "সেভ থেকে সরানো হয়েছে") else tr("Saved", "সেভ হয়েছে")) { JobsApi.bookmark(j.id, !j.saved) }
                    }) {
                        AppIcon(if (j.saved) R.drawable.ic_star_fill else R.drawable.ic_star, if (j.saved) tr("Remove from saved", "সেভ থেকে সরান") else tr("Save", "সেভ করুন"), Modifier.size(24.dp), if (j.saved) cs.primary else cs.onSurfaceVariant)
                    }
                }
            }
            if (j.mine || j.status != "open") Tag(JobText.jobStatus(j.status, j.pausedBy), cs.surfaceVariant, cs.onSurfaceVariant)
            if (j.mine) {
                if (j.statusReason.isNotBlank() && j.status in listOf("rejected", "suspended", "changes")) IconLine(R.drawable.ic_info, tr("Reason: ", "কারণ: ") + j.statusReason)
                if (j.status == "pending" && j.flags.isNotEmpty()) {
                    SmallNote(tr("The Lifolo team will check this before it goes live: ", "চালুর আগে Lifolo টিম দেখবে: ") + j.flags.joinToString("; "))
                }
            }
            Text(JobText.salaryOf(j), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = cs.primary)
            IconLine(R.drawable.ic_location_on, (if (j.type == "remote") tr("Remote · ", "দূর থেকে · ") else "") + areaText(j.district, j.upazilaId))
            IconLine(R.drawable.ic_nav_work, JobText.type(j.type) + " · " + JobText.category(j.category) + " · " + tr("${j.vacancies} post(s)", "${num(j.vacancies)} জন"))
            IconLine(R.drawable.ic_history, JobText.experience(j.experienceYears))
            IconLine(R.drawable.ic_badge, tr("Education: ", "শিক্ষা: ") + JobText.education(j.education))
            IconLine(R.drawable.ic_schedule, tr("Apply by ", "আবেদনের শেষ তারিখ ") + Rules.formatDate(j.deadline))
            if (j.skills.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    j.skills.forEach { Tag(it, cs.secondaryContainer, cs.onSecondaryContainer) }
                }
            }
            SectionTitle(tr("About the work", "কাজের বিবরণ"))
            Text(j.description, style = MaterialTheme.typography.bodyLarge)
            x.employer?.let { e ->
                SectionTitle(tr("About the employer", "নিয়োগকর্তা সম্পর্কে"))
                Text(e.name + " · " + JobText.employerKind(e.kind), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                if (e.address.isNotBlank()) IconLine(R.drawable.ic_location_on, e.address + ", " + areaText(e.district, e.upazilaId))
                if (e.website.isNotBlank()) {
                    Text(
                        e.website, style = MaterialTheme.typography.bodyMedium, color = cs.primary,
                        modifier = Modifier.clickable { openUrl(context, if (e.website.startsWith("http")) e.website else "https://" + e.website) },
                    )
                }
                if (e.about.isNotBlank()) Text(e.about, style = MaterialTheme.typography.bodyMedium)
            }
            if (!j.mine) {
                // প্রতারণা থেকে সাবধান: চাকরির জন্য টাকা চাইলে আবেদন না করে রিপোর্ট
                Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerHigh)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(R.drawable.ic_warning, null, Modifier.size(20.dp), cs.error)
                        Spacer(Modifier.size(8.dp))
                        Text(
                            tr("Never pay anyone to get a job. If this employer asks for a fee or deposit, report it.", "চাকরির জন্য কাউকে টাকা দেবেন না। নিয়োগকর্তা ফি বা জামানত চাইলে রিপোর্ট করুন।"),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            ErrorText(errorMsg)
            InfoText(infoMsg)
            if (cvMissing) {
                OutlinedButton(onClick = onEditCv, modifier = Modifier.fillMaxWidth()) { ButtonContent(false, tr("Complete my CV", "সিভি পূরণ করুন"), R.drawable.ic_edit) }
            }
            if (j.mine) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallAction(tr("Applicants (${j.applicants})", "আবেদনকারী (${num(j.applicants)})"), R.drawable.ic_group_round, { onOpenApplicants(j.id) }, primary = true)
                    if (j.status in listOf("open", "paused", "pending", "rejected", "changes")) SmallAction(tr("Edit", "বদলান"), R.drawable.ic_edit, { onEditJob(j.id) })
                    if (j.status == "open") SmallAction(tr("Pause", "বিরতি দিন"), R.drawable.ic_schedule, { act(tr("Paused. No new applications until you resume.", "বিরতি দেওয়া হয়েছে। আবার চালু না করা পর্যন্ত নতুন আবেদন আসবে না।")) { JobsApi.pause(j.id, true) } }, busy = busy)
                    if (j.status == "paused") SmallAction(tr("Resume", "আবার চালু"), R.drawable.ic_refresh, { act(tr("Live again", "আবার চালু হয়েছে")) { JobsApi.pause(j.id, false) } }, busy = busy)
                    if (j.status in listOf("open", "paused", "pending")) SmallAction(tr("Position filled", "লোক পাওয়া গেছে"), R.drawable.ic_check_circle, { closing = true }, enabled = !busy)
                }
                if (j.status == "paused" && j.pausedBy == "deadline") SmallNote(tr("The deadline passed. Edit the job to set a new deadline, then resume.", "শেষ তারিখ পার হয়েছে। বদলান চেপে নতুন শেষ তারিখ দিন, তারপর আবার চালু করুন।"))
            } else {
                CandidateActions(
                    j, x, busy,
                    onApply = { applying = true },
                    onWithdraw = { withdrawing = true },
                    act = ::act,
                )
                TextButton(onClick = { reporting = true }) { Text(tr("Report this job", "বিজ্ঞাপনটা রিপোর্ট করুন"), color = cs.onSurfaceVariant) }
            }
        }
    }
    val x = d
    if (applying && x != null) {
        ApplyDialog(
            x.job,
            onDismiss = { applying = false },
        ) { note, answers ->
            applying = false
            scope.launch {
                busy = true
                errorMsg = null
                cvMissing = false
                runCatching { JobsApi.apply(id, note, answers) }
                    .onSuccess { already ->
                        infoMsg = if (already) tr("You have already applied to this job.", "এই চাকরিতে আগেই আবেদন করেছেন।")
                        else tr("Applied. You'll get a notification when the employer responds.", "আবেদন হয়েছে। নিয়োগকর্তা সাড়া দিলে নোটিফিকেশন পাবেন।")
                        reload++
                    }
                    .onFailure { errorMsg = it.message; cvMissing = (it as? ServerError)?.code == "cv_incomplete" }
                busy = false
            }
        }
    }
    if (withdrawing) {
        WithdrawDialog(onDismiss = { withdrawing = false }) { reason, note ->
            withdrawing = false
            act(tr("Withdrawn", "তুলে নেওয়া হয়েছে")) { JobsApi.withdraw(id, reason, note) }
        }
    }
    if (closing) {
        AlertDialog(
            onDismissRequest = { closing = false },
            title = { Text(tr("Position filled?", "লোক পাওয়া গেছে?")) },
            text = { Text(tr("The job closes for good. Applicants still waiting get a notification.", "বিজ্ঞাপন পুরোপুরি বন্ধ হবে। যাঁদের সিদ্ধান্ত বাকি, তাঁরা নোটিফিকেশন পাবেন।")) },
            confirmButton = {
                TextButton(onClick = {
                    closing = false
                    act(tr("Hiring closed", "নিয়োগ বন্ধ হয়েছে")) { JobsApi.close(id) }
                }) { Text(tr("Close hiring", "নিয়োগ বন্ধ করুন"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { closing = false }) { Text(tr("Cancel", "বাতিল")) } },
        )
    }
    if (reporting) {
        ReasonReportDialog(
            onDismiss = { reporting = false },
            onSubmit = { reason ->
                Moderation.report("job", id, reason = reason)
                reporting = false
                infoMsg = tr("Reported. The Lifolo team will check it.", "রিপোর্ট করা হয়েছে। Lifolo টিম দেখবে।")
            },
        )
    }
}

/** প্রার্থীর অংশ: আবেদন, অবস্থা, ইন্টারভিউ আর প্রস্তাবে সাড়া, তুলে নেওয়া */
@Composable
private fun CandidateActions(j: Job, x: JobDetail, busy: Boolean, onApply: () -> Unit, onWithdraw: () -> Unit, act: (String, suspend () -> Unit) -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val st = x.myStatus
    val ex = x.extras
    var replying by remember { mutableStateOf<String?>(null) }
    var confirmOffer by remember { mutableStateOf<Boolean?>(null) }
    when {
        st.isNotBlank() && st != "withdrawn" && ex != null -> {
            Card(colors = CardDefaults.cardColors(containerColor = cs.secondaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("Your application", "আপনার আবেদন"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    ApplicationStatusTag(st)
                    if (x.myNote.isNotBlank()) IconLine(R.drawable.ic_info, tr("Employer's note: ", "নিয়োগকর্তার কথা: ") + x.myNote)
                    // ইন্টারভিউ
                    val iv = ex.interview
                    if (st == "interview" && iv != null) {
                        IconLine(R.drawable.ic_calendar_month, JobText.dateTime(iv.at))
                        if (iv.mode == "online") {
                            IconLine(R.drawable.ic_language, tr("Online", "অনলাইনে"))
                            Text(iv.link, style = MaterialTheme.typography.bodyMedium, color = cs.primary, modifier = Modifier.clickable { openUrl(context, iv.link) })
                        } else {
                            IconLine(R.drawable.ic_location_on, iv.place)
                        }
                        when (iv.response) {
                            "accepted" -> IconLine(R.drawable.ic_check_circle, tr("You said you'll come. We'll remind you a day and an hour before.", "আপনি আসবেন বলেছেন। এক দিন আর এক ঘণ্টা আগে মনে করানো হবে।"), cs.primary)
                            "declined" -> IconLine(R.drawable.ic_info, tr("You said you can't come", "আপনি আসতে পারবেন না বলেছেন"))
                            "reschedule" -> IconLine(R.drawable.ic_info, tr("You asked for another time: ", "অন্য সময় চেয়েছেন: ") + iv.responseNote)
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (iv.response != "accepted") SmallAction(tr("I'll come", "আসব"), R.drawable.ic_check_circle, {
                                act(tr("The employer knows you'll come", "নিয়োগকর্তা জানলেন আপনি আসবেন")) {
                                    JobsApi.interviewRespond(j.id, "accepted", "")
                                    JobAlarms.sync(context)
                                }
                            }, primary = true, busy = busy)
                            if (iv.response != "reschedule") SmallAction(tr("Ask another time", "অন্য সময় চাই"), R.drawable.ic_schedule, { replying = "reschedule" }, enabled = !busy)
                            if (iv.response != "declined") SmallAction(tr("Can't come", "আসতে পারব না"), R.drawable.ic_close, { replying = "declined" }, enabled = !busy)
                        }
                    }
                    // চাকরির প্রস্তাব
                    val o = ex.offer
                    if (st in listOf("offered", "hired") && o != null) {
                        SectionTitle(tr("Job offer", "চাকরির প্রস্তাব"))
                        IconLine(R.drawable.ic_workspace_premium, o.designation)
                        IconLine(R.drawable.ic_info, tr("Salary ", "বেতন ") + JobText.taka(o.salary) + tr("/month", "/মাস"))
                        IconLine(R.drawable.ic_calendar_month, tr("Joining ", "যোগদান ") + Rules.formatDate(o.joiningDate))
                        when (o.response) {
                            "pending" -> {
                                IconLine(R.drawable.ic_schedule, tr("Reply by ", "উত্তর দিন ") + Rules.formatDate(o.expiresAt) + tr("", "-এর মধ্যে"), cs.primary)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SmallAction(tr("Accept offer", "প্রস্তাব নিলাম"), R.drawable.ic_check_circle, { confirmOffer = true }, primary = true, enabled = !busy)
                                    SmallAction(tr("Decline", "ফিরিয়ে দিন"), R.drawable.ic_close, { confirmOffer = false }, enabled = !busy)
                                }
                            }
                            "accepted" -> IconLine(R.drawable.ic_check_circle, tr("You accepted. It's added to your CV.", "আপনি গ্রহণ করেছেন। সিভিতে যোগ হয়েছে।"), cs.primary)
                            "declined" -> IconLine(R.drawable.ic_info, tr("You declined the offer", "আপনি প্রস্তাব ফিরিয়ে দিয়েছেন"))
                            "expired" -> IconLine(R.drawable.ic_info, tr("The offer expired", "প্রস্তাবের মেয়াদ শেষ"))
                        }
                    }
                    if (st == "rejected" && ex.reason.isNotBlank()) IconLine(R.drawable.ic_info, JobText.rejectReason(ex.reason))
                    if (st !in listOf("hired", "rejected")) {
                        TextButton(onClick = onWithdraw, enabled = !busy) { Text(tr("Withdraw application", "আবেদন তুলে নিন"), color = cs.error) }
                    }
                }
            }
        }
        j.status == "open" -> {
            if (st == "withdrawn") SmallNote(tr("You withdrew earlier. You can apply again.", "আগে আবেদন তুলে নিয়েছিলেন। আবার আবেদন করা যাবে।"))
            Button(onClick = onApply, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                ButtonContent(busy, tr("Apply with my CV", "সিভি দিয়ে আবেদন করুন"), R.drawable.ic_send)
            }
        }
        else -> SmallNote(tr("This job is not taking applications now.", "এই চাকরিতে এখন আবেদন নেওয়া হচ্ছে না।"))
    }
    replying?.let { kind ->
        var note by remember(kind) { mutableStateOf("") }
        FormDialog(
            title = if (kind == "reschedule") tr("Ask for another time", "অন্য সময় চান") else tr("Can't come?", "আসতে পারবেন না?"),
            onDismiss = { replying = null },
            content = {
                OutlinedTextField(
                    note, { note = it.take(300) },
                    label = { Text(if (kind == "reschedule") tr("Which days and times suit you?", "কোন দিন আর সময় সুবিধা?") else tr("Reason (optional)", "কারণ (ঐচ্ছিক)")) },
                    minLines = 2, modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(enabled = kind != "reschedule" || note.isNotBlank(), onClick = {
                    replying = null
                    act(tr("Sent to the employer", "নিয়োগকর্তাকে জানানো হয়েছে")) {
                        JobsApi.interviewRespond(j.id, kind, note)
                        JobAlarms.sync(context)
                    }
                }) { Text(tr("Send", "পাঠান")) }
            },
            dismissButton = { TextButton(onClick = { replying = null }) { Text(tr("Cancel", "বাতিল")) } },
        )
    }
    confirmOffer?.let { accept ->
        AlertDialog(
            onDismissRequest = { confirmOffer = null },
            title = { Text(if (accept) tr("Accept this offer?", "প্রস্তাবটা নেবেন?") else tr("Decline this offer?", "প্রস্তাব ফিরিয়ে দেবেন?")) },
            text = {
                Text(
                    if (accept) tr("The employer will be told, and this job is added to your CV as your current workplace.", "নিয়োগকর্তা জানবেন, আর এই চাকরি আপনার সিভিতে বর্তমান কর্মস্থল হিসেবে যোগ হবে।")
                    else tr("The employer will be told. This can't be undone.", "নিয়োগকর্তা জানবেন। পরে আর বদলানো যাবে না।"),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmOffer = null
                    act(if (accept) tr("Congratulations! The employer has been told.", "অভিনন্দন! নিয়োগকর্তাকে জানানো হয়েছে।") else tr("The employer has been told", "নিয়োগকর্তাকে জানানো হয়েছে")) { JobsApi.offerRespond(j.id, accept) }
                }) { Text(if (accept) tr("Accept", "নিলাম") else tr("Decline", "ফিরিয়ে দিন"), color = if (accept) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmOffer = null }) { Text(tr("Cancel", "বাতিল")) } },
        )
    }
}

/** আবেদন: নিয়োগকর্তার প্রশ্নের উত্তর আর কভার লেটার (চাকরি অনুযায়ী লাগবে / ঐচ্ছিক / বন্ধ) */
@Composable
private fun ApplyDialog(job: Job, onDismiss: () -> Unit, onSend: (String, Map<String, String>) -> Unit) {
    var note by remember { mutableStateOf("") }
    var answers by remember { mutableStateOf(mapOf<String, String>()) }
    var err by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = tr("Apply", "আবেদন"),
        onDismiss = onDismiss,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SmallNote(tr("The employer will see your CV and phone number (as set in your CV).", "নিয়োগকর্তা আপনার সিভি আর (সিভিতে যেমন ঠিক করেছেন) ফোন নম্বর দেখবেন।"))
                job.questions.forEach { q ->
                    Text(q.text + if (q.required) " *" else "", style = MaterialTheme.typography.labelLarge)
                    when (q.kind) {
                        "yesno" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("yes" to tr("Yes", "হ্যাঁ"), "no" to tr("No", "না")).forEach { (k, l) ->
                                FilterChip(selected = answers[q.id] == k, onClick = { answers = answers + (q.id to k) }, label = { Text(l) })
                            }
                        }
                        "choice" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            q.options.forEach { o -> FilterChip(selected = answers[q.id] == o, onClick = { answers = answers + (q.id to o) }, label = { Text(o) }) }
                        }
                        else -> OutlinedTextField(answers[q.id] ?: "", { answers = answers + (q.id to it.take(500)) }, minLines = 2, modifier = Modifier.fillMaxWidth())
                    }
                }
                if (job.coverLetter != "off") {
                    OutlinedTextField(
                        note, { note = it.take(1500) },
                        label = { Text(if (job.coverLetter == "required") tr("Cover letter *", "কভার লেটার *") else tr("Cover letter (optional)", "কভার লেটার (ঐচ্ছিক)")) },
                        minLines = 4, modifier = Modifier.fillMaxWidth(),
                    )
                }
                ErrorText(err)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val missing = job.questions.firstOrNull { it.required && answers[it.id].isNullOrBlank() }
                err = when {
                    missing != null -> tr("Answer: ", "উত্তর দিন: ") + missing.text
                    job.coverLetter == "required" && note.trim().length < 20 -> tr("Write a cover letter (at least 20 characters)", "কভার লেটার লিখুন (অন্তত ২০ অক্ষর)")
                    else -> null
                }
                if (err == null) onSend(if (job.coverLetter == "off") "" else note, answers.filterValues { it.isNotBlank() })
            }) { Text(tr("Send", "পাঠান")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "বাতিল")) } },
    )
}

/** আবেদন তুলে নেওয়া: কারণ ঐচ্ছিক (নিয়োগকর্তা দেখেন) */
@Composable
private fun WithdrawDialog(onDismiss: () -> Unit, onDone: (String, String) -> Unit) {
    var reason by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    FormDialog(
        title = tr("Withdraw your application?", "আবেদন তুলে নেবেন?"),
        onDismiss = onDismiss,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallNote(tr("The employer will see it as withdrawn. You can apply again while the job is live.", "নিয়োগকর্তা \"তুলে নিয়েছেন\" দেখবেন। বিজ্ঞাপন চালু থাকলে আবার আবেদন করা যাবে।"))
                Text(tr("Why? (optional)", "কেন? (ঐচ্ছিক)"), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    JobText.WITHDRAW_REASONS.forEach { r ->
                        FilterChip(selected = reason == r, onClick = { reason = if (reason == r) "" else r }, label = { Text(JobText.withdrawReason(r)) })
                    }
                }
                OutlinedTextField(note, { note = it.take(300) }, label = { Text(tr("Anything to add (optional)", "আর কিছু বলার থাকলে (ঐচ্ছিক)")) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onDone(reason, note) }) { Text(tr("Withdraw", "তুলে নিন"), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "বাতিল")) } },
    )
}
