@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.bspc.blood

// FEATURE:jobs — পুরো ফাইলটা চাকরি ফিচারের (মুছলে FEATURES.md দেখুন)

import androidx.compose.foundation.clickable
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
// চাকরি: প্রার্থী চাকরি খোঁজেন আর সিভি দিয়ে আবেদন করেন; নিয়োগকর্তা বিজ্ঞাপন দেন আর আবেদন দেখেন।
// সার্ভার: lib/jobs.js (সিভি profiles/, নিয়োগকর্তা employers/, বিজ্ঞাপন jobs/, আবেদন applications/)
// ------------------------------------------------------------

data class Job(
    val id: String,
    val title: String,
    val orgName: String,
    val employerVerified: Boolean,
    val category: String,
    val type: String,
    val district: String,
    val upazilaId: Int?,
    val salaryMin: Int,
    val salaryMax: Int,
    val vacancies: Int,
    val experienceYears: Int,
    val education: String,
    val skills: List<String>,
    val deadline: Long,
    val createdAt: Long,
    val applicants: Int,
    val status: String,
    /** তালিকায়: নিজের আবেদনের অবস্থা (না করলে খালি) */
    val myStatus: String = "",
    val description: String = "",
    val mine: Boolean = false,
    val statusReason: String = "",
)

data class Employer(
    val uid: String,
    val name: String,
    val kind: String,
    val district: String,
    val upazilaId: Int?,
    val address: String,
    val website: String,
    val about: String,
    val verified: Boolean,
    /** শুধু নিজের */
    val phone: String = "",
    val verifyStatus: String = "none",
    val statusReason: String = "",
    val docsRequested: Boolean = false,
    val docsNote: String = "",
)

data class Applicant(val uid: String, val status: String, val note: String, val coverNote: String, val appliedAt: Long, val cv: Cv)

data class MyApplication(val jobId: String, val status: String, val note: String, val appliedAt: Long, val updatedAt: Long, val job: Job?)

data class JobDetail(val job: Job, val employer: Employer?, val myStatus: String, val myNote: String)

object JobsApi {
    private suspend fun call(action: String, body: JSONObject = JSONObject()): JSONObject =
        ServerApi.post("/api/community", body.put("action", action))

    internal fun job(o: JSONObject) = Job(
        o.str("id"), o.str("title"), o.str("orgName"), o.optBoolean("employerVerified"), o.str("category"), o.str("type"),
        o.str("district"), o.intOrNull("upazilaId"), o.optInt("salaryMin"), o.optInt("salaryMax"), o.optInt("vacancies", 1),
        o.optInt("experienceYears"), o.str("education").ifBlank { "none" }, o.optJSONArray("skills").strings(),
        o.optLong("deadline"), o.optLong("createdAt"), o.optInt("applicants"), o.str("status"),
        o.str("myStatus"), o.str("description"), o.optBoolean("mine"), o.str("statusReason"),
    )

    private fun employer(o: JSONObject) = Employer(
        o.str("uid"), o.str("name"), o.str("kind"), o.str("district"), o.intOrNull("upazilaId"), o.str("address"),
        o.str("website"), o.str("about"), o.optBoolean("verified"), o.str("phone"), o.str("verifyStatus").ifBlank { "none" },
        o.str("statusReason"), o.optBoolean("docsRequested"), o.str("docsRequestNote"),
    )

    private fun list(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { job(a.getJSONObject(it)) }

    suspend fun search(district: String, upazilaId: Int?, category: String?, type: String?, minSalary: Int?, q: String): List<Job> = list(
        call(
            "jobs",
            JSONObject().put("district", district).put("upazilaId", upazilaId ?: JSONObject.NULL)
                .put("category", category ?: JSONObject.NULL).put("type", type ?: JSONObject.NULL)
                .put("minSalary", minSalary ?: JSONObject.NULL).put("q", q.trim()),
        ).optJSONArray("items"),
    )

    suspend fun get(id: String): JobDetail {
        val r = call("job", JSONObject().put("id", id))
        val app = r.optJSONObject("myApplication")
        return JobDetail(
            job(r.getJSONObject("job")),
            r.optJSONObject("employer")?.let(::employer),
            app?.str("status") ?: "",
            app?.str("note") ?: "",
        )
    }

    suspend fun apply(id: String, coverNote: String) {
        call("job_apply", JSONObject().put("id", id).put("coverNote", coverNote.trim()))
    }

    suspend fun withdraw(id: String) {
        call("application_withdraw", JSONObject().put("id", id))
    }

    suspend fun myApplications(): List<MyApplication> {
        val a = call("my_applications").optJSONArray("items") ?: JSONArray()
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            MyApplication(o.str("jobId"), o.str("status"), o.str("note"), o.optLong("appliedAt"), o.optLong("updatedAt"), o.optJSONObject("job")?.let(::job))
        }
    }

    suspend fun myEmployer(): Employer? = call("employer_get").optJSONObject("employer")?.let(::employer)

    suspend fun saveEmployer(
        name: String, kind: String, district: String, upazilaId: Int?, address: String, phone: String, website: String, about: String,
        docs: Map<String, String>,
    ): Employer? = call(
        "employer_save",
        JSONObject().put("name", name).put("kind", kind).put("district", district).put("upazilaId", upazilaId ?: JSONObject.NULL)
            .put("address", address).put("phone", phone).put("website", website).put("about", about).put("docs", VerifyDocs.json(docs)),
    ).optJSONObject("employer")?.let(::employer)

    suspend fun myJobs(): Pair<List<Job>, Employer?> {
        val r = call("my_jobs")
        return list(r.optJSONArray("items")) to r.optJSONObject("employer")?.let(::employer)
    }

    /** নতুন বা বদলানো; ফেরত: অবস্থা (open / pending) */
    suspend fun saveJob(
        id: String?, title: String, category: String, type: String, district: String, upazilaId: Int?, salaryMin: Int, salaryMax: Int,
        vacancies: Int, experienceYears: Int, education: String, skills: List<String>, description: String, deadline: Long,
    ): String = call(
        "job_save",
        JSONObject().put("id", id ?: JSONObject.NULL).put("title", title).put("category", category).put("type", type)
            .put("district", district).put("upazilaId", upazilaId ?: JSONObject.NULL).put("salaryMin", salaryMin).put("salaryMax", salaryMax)
            .put("vacancies", vacancies).put("experienceYears", experienceYears).put("education", education)
            .put("skills", JSONArray(skills)).put("description", description).put("deadline", deadline),
    ).str("status")

    suspend fun close(id: String) {
        call("job_close", JSONObject().put("id", id))
    }

    suspend fun applicants(id: String): Pair<Job, List<Applicant>> {
        val r = call("job_applicants", JSONObject().put("id", id))
        val a = r.optJSONArray("items") ?: JSONArray()
        return job(r.getJSONObject("job")) to (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Applicant(o.str("uid"), o.str("status"), o.str("note"), o.str("coverNote"), o.optLong("appliedAt"), CvApi.parse(o.getJSONObject("cv")))
        }
    }

    suspend fun setStatus(jobId: String, uid: String, status: String, note: String) {
        call("application_update", JSONObject().put("jobId", jobId).put("uid", uid).put("status", status).put("note", note.trim()))
    }
}

/** সার্ভারের কোড → লেখা (সার্ভার, প্যানেল আর শেয়ার পাতার সাথে মেলানো) */
object JobText {
    val CATEGORIES = listOf("health", "education", "sales", "accounts", "office", "it", "driving", "factory", "hospitality", "security", "construction", "domestic", "other")
    val TYPES = listOf("fulltime", "parttime", "contract", "internship")
    val EDUCATION = listOf("none", "ssc", "hsc", "diploma", "bachelor", "masters")
    val EMPLOYER_KINDS = listOf("company", "shop", "hospital", "school", "ngo", "household", "other")
    val DECISIONS = listOf("shortlisted", "interview", "rejected", "hired")

    fun category(c: String) = when (c) {
        "health" -> tr("Healthcare", "স্বাস্থ্যসেবা")
        "education" -> tr("Education", "শিক্ষা")
        "sales" -> tr("Sales & marketing", "বিক্রয় ও মার্কেটিং")
        "accounts" -> tr("Accounts & finance", "হিসাব ও অর্থ")
        "office" -> tr("Office & admin", "অফিস ও প্রশাসন")
        "it" -> tr("IT", "আইটি")
        "driving" -> tr("Driving & delivery", "গাড়ি চালনা ও ডেলিভারি")
        "factory" -> tr("Factory", "কারখানা")
        "hospitality" -> tr("Hotel & restaurant", "হোটেল ও রেস্টুরেন্ট")
        "security" -> tr("Security", "নিরাপত্তা")
        "construction" -> tr("Construction & repair", "নির্মাণ ও মেরামত")
        "domestic" -> tr("Household work", "গৃহস্থালি কাজ")
        else -> tr("Other", "অন্যান্য")
    }

    fun type(t: String) = when (t) {
        "fulltime" -> tr("Full-time", "ফুলটাইম")
        "parttime" -> tr("Part-time", "পার্টটাইম")
        "contract" -> tr("Contract", "চুক্তিভিত্তিক")
        "internship" -> tr("Internship", "ইন্টার্নশিপ")
        else -> t
    }

    fun education(e: String) = when (e) {
        "ssc" -> tr("SSC", "এসএসসি")
        "hsc" -> tr("HSC", "এইচএসসি")
        "diploma" -> tr("Diploma", "ডিপ্লোমা")
        "bachelor" -> tr("Bachelor's", "স্নাতক")
        "masters" -> tr("Master's", "স্নাতকোত্তর")
        else -> tr("Not required", "লাগবে না")
    }

    fun employerKind(k: String) = when (k) {
        "company" -> tr("Company", "কোম্পানি")
        "shop" -> tr("Shop / business", "দোকান/ব্যবসা")
        "hospital" -> tr("Hospital / clinic", "হাসপাতাল/ক্লিনিক")
        "school" -> tr("School / college", "শিক্ষাপ্রতিষ্ঠান")
        "ngo" -> tr("NGO / organisation", "এনজিও/সংগঠন")
        "household" -> tr("Household", "বাসাবাড়ি")
        else -> tr("Other", "অন্যান্য")
    }

    /** আবেদনের অবস্থা */
    fun application(s: String) = when (s) {
        "applied" -> tr("Applied", "আবেদন করা হয়েছে")
        "seen" -> tr("Seen by employer", "নিয়োগকর্তা দেখেছেন")
        "shortlisted" -> tr("Shortlisted", "শর্টলিস্টে আছেন")
        "interview" -> tr("Interview call", "ইন্টারভিউয়ের ডাক")
        "rejected" -> tr("Not selected", "বাছাই হননি")
        "hired" -> tr("Hired", "নিয়োগ পেয়েছেন")
        "withdrawn" -> tr("Withdrawn", "তুলে নেওয়া হয়েছে")
        else -> s
    }

    /** নিয়োগকর্তার বোতামে */
    fun decision(s: String) = when (s) {
        "shortlisted" -> tr("Shortlist", "শর্টলিস্ট")
        "interview" -> tr("Call for interview", "ইন্টারভিউয়ে ডাকুন")
        "rejected" -> tr("Not selected", "বাছাই হয়নি")
        "hired" -> tr("Hired", "নিয়োগ দিয়েছি")
        else -> s
    }

    fun jobStatus(s: String) = when (s) {
        "open" -> tr("Live", "চালু")
        "pending" -> tr("Waiting for approval", "অনুমোদনের অপেক্ষায়")
        "closed" -> tr("Closed", "বন্ধ")
        "rejected" -> tr("Not approved", "অনুমোদন হয়নি")
        "suspended" -> tr("Taken down by the Lifolo team", "Lifolo টিম বন্ধ করেছে")
        else -> s
    }

    private fun money(n: Int): String = num(String.format(java.util.Locale.US, "%,d", n))

    fun salary(min: Int, max: Int): String =
        "৳" + (if (min == max) money(min) else money(min) + " – " + money(max)) + tr("/month", "/মাস")

    fun experience(y: Int) = if (y <= 0) tr("Freshers welcome", "অভিজ্ঞতা লাগবে না") else tr("$y+ years experience", "${num(y)}+ বছরের অভিজ্ঞতা")
}

/** চাকরির পাতাগুলো (মূল অ্যাপের স্ট্যাকে একটাই Screen.Jobs দিয়ে খোলে, যাতে ফিচারটা আলাদা থাকে) */
sealed interface JobsPage {
    data class Detail(val id: String) : JobsPage
    data class Edit(val id: String?) : JobsPage
    data class Applicants(val id: String) : JobsPage
    data object EditCv : JobsPage
}

/** নোটিফিকেশন থেকে চাকরি ট্যাবের কোন অংশ খুলবে (০ খুঁজুন, ১ আমার আবেদন, ২ সিভি, ৩ নিয়োগ দিন) */
object JobsNav {
    val section = mutableStateOf<Int?>(null)
    /** পুশে চাপ দিলে কোন বিজ্ঞাপন (MainActivity intent থেকে বসায়) */
    val jobId = mutableStateOf<String?>(null)
    val TYPES = setOf("job", "job_applicants", "job_mine")

    /** নোটিফিকেশনের ধরন → ট্যাবের অংশ আর উপরে খোলার পাতা */
    fun route(type: String?, jobId: String?): Pair<Int, JobsPage?> {
        val id = jobId ?: this.jobId.value
        this.jobId.value = null
        return when (type) {
            "job" -> 1 to id?.let { JobsPage.Detail(it) }
            "job_applicants" -> 3 to id?.let { JobsPage.Applicants(it) }
            else -> 3 to null
        }
    }
}

/** স্ট্যাকের উপরে খোলা চাকরির পাতা */
@Composable
fun JobsPageScreen(page: JobsPage, profile: Profile, open: (JobsPage) -> Unit, replace: (JobsPage) -> Unit, back: () -> Unit) {
    when (page) {
        is JobsPage.Detail -> JobDetailScreen(
            page.id, onBack = back,
            onEditCv = { open(JobsPage.EditCv) },
            onEditJob = { replace(JobsPage.Edit(it)) },
            onOpenApplicants = { replace(JobsPage.Applicants(it)) },
        )
        is JobsPage.Edit -> JobEditScreen(page.id, profile, onBack = back, onSaved = back)
        is JobsPage.Applicants -> JobApplicantsScreen(page.id, onBack = back)
        JobsPage.EditCv -> CvEditScreen(onBack = back, onSaved = back)
    }
}

/** নিচের "চাকরি" ট্যাব */
@Composable
fun JobsTab(profile: Profile, refreshKey: Int, open: (JobsPage) -> Unit) {
    val onOpenJob: (String) -> Unit = { open(JobsPage.Detail(it)) }
    val onEditJob: (String?) -> Unit = { open(JobsPage.Edit(it)) }
    val onOpenApplicants: (String) -> Unit = { open(JobsPage.Applicants(it)) }
    val onEditCv: () -> Unit = { open(JobsPage.EditCv) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val target = JobsNav.section.value
    LaunchedEffect(target) {
        if (target != null) {
            tab = target
            JobsNav.section.value = null
        }
    }
    Column(Modifier.fillMaxSize()) {
        PillTabs(
            tab,
            listOf(
                PillTab(tr("Find jobs", "চাকরি খুঁজুন"), R.drawable.ic_search),
                PillTab(tr("My applications", "আমার আবেদন"), R.drawable.ic_send),
                PillTab(tr("My CV", "আমার সিভি"), R.drawable.ic_badge),
                PillTab(tr("Hire", "নিয়োগ দিন"), R.drawable.ic_nav_work, R.drawable.ic_nav_work_fill),
            ),
            { tab = it },
        )
        when (tab) {
            0 -> JobSearch(profile, refreshKey, onOpenJob)
            1 -> MyApplicationsPanel(refreshKey, onOpenJob, onFind = { tab = 0 })
            2 -> MyCvPanel(refreshKey, onEditCv)
            else -> HirePanel(profile, refreshKey, onEditJob, onOpenApplicants)
        }
    }
}

@Composable
private fun JobSearch(profile: Profile, refreshKey: Int, onOpenJob: (String) -> Unit) {
    val context = LocalContext.current
    remember { Areas.ensure(context) }
    var district by rememberSaveable { mutableStateOf(profile.placeName) }
    var upazilaId by rememberSaveable { mutableStateOf<Int?>(null) }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var type by rememberSaveable { mutableStateOf<String?>(null) }
    var minSalary by rememberSaveable { mutableStateOf<Int?>(null) }
    var q by rememberSaveable { mutableStateOf("") }
    var list by remember { mutableStateOf<List<Job>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var showArea by remember { mutableStateOf(false) }
    var showFilter by remember { mutableStateOf(false) }

    LaunchedEffect(district, upazilaId, category, type, minSalary, q, refreshKey) {
        val d = district ?: run { loading = false; return@LaunchedEffect }
        // লেখার মাঝে প্রতি অক্ষরে সার্ভারে না গিয়ে একটু থেমে
        if (q.isNotBlank()) kotlinx.coroutines.delay(400)
        loading = true
        runCatching { JobsApi.search(d, upazilaId, category, type, minSalary, q) }
            .onSuccess { list = it; errorMsg = null }.onFailure { errorMsg = it.message }
        loading = false
    }

    val active = buildList {
        category?.let { add(ActiveFilter(JobText.category(it)) { category = null }) }
        type?.let { add(ActiveFilter(JobText.type(it)) { type = null }) }
        minSalary?.let { add(ActiveFilter(tr("From ", "কমপক্ষে ") + "৳" + num(it)) { minSalary = null }) }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
          Column {
            FilterBar(
                areaLabel = areaText(district, upazilaId),
                onAreaClick = { showArea = true },
                filterCount = active.size,
                onFilterClick = { showFilter = true },
                search = q,
                onSearch = { q = it.take(60) },
                searchHint = tr("Job title, organisation or skill", "পদ, প্রতিষ্ঠান বা দক্ষতা"),
                active = active,
            )
            ErrorText(errorMsg)
          }
        }
        when {
            district == null -> item { EmptyState(R.drawable.ic_location_on, tr("Choose a district", "জেলা বেছে নিন"), null, Modifier.padding(top = 24.dp)) }
            loading -> item { LoadingBox() }
            list.isEmpty() -> item {
                EmptyState(
                    R.drawable.ic_nav_work,
                    tr("No jobs here yet", "এখানে এখনো কোনো চাকরির বিজ্ঞাপন নেই"),
                    tr("Try another area or fewer filters. Keep your CV ready in \"My CV\".", "অন্য এলাকা বা কম ফিল্টার দিয়ে দেখুন। \"আমার সিভি\"-তে সিভি তৈরি রাখুন।"),
                    Modifier.padding(top = 24.dp),
                )
            }
            else -> items(list, key = { it.id }) { j -> JobCard(j) { onOpenJob(j.id) } }
        }
    }
    if (showArea) {
        AreaSheet(district, upazilaId, null, { d, u, _ -> district = d; upazilaId = u }, onDismiss = { showArea = false })
    }
    if (showFilter) {
        FilterSheet(tr("Filter", "ফিল্টার"), onDismiss = { showFilter = false }, onClear = { category = null; type = null; minSalary = null }) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                FilterChoice(tr("Field", "কাজের ক্ষেত্র"), JobText.CATEGORIES, category, JobText::category) { category = it }
                FilterChoice(tr("Type", "ধরন"), JobText.TYPES, type, JobText::type) { type = it }
                FilterChoice(
                    tr("Monthly salary at least", "মাসিক বেতন কমপক্ষে"),
                    listOf("8000", "12000", "20000", "30000", "50000"),
                    minSalary?.toString(),
                    { "৳" + num(it.toInt()) },
                ) { minSalary = it?.toInt() }
            }
        }
    }
}

/** তালিকার কার্ড */
@Composable
fun JobCard(j: Job, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(j.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(j.orgName, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                if (j.employerVerified) {
                    Spacer(Modifier.size(4.dp))
                    AppIcon(R.drawable.ic_verified, tr("Verified employer", "যাচাই করা নিয়োগকর্তা"), Modifier.size(16.dp), cs.primary)
                }
            }
            Text(JobText.salary(j.salaryMin, j.salaryMax), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = cs.primary)
            IconLine(R.drawable.ic_location_on, areaText(j.district, j.upazilaId))
            IconLine(R.drawable.ic_nav_work, JobText.type(j.type) + " · " + JobText.category(j.category))
            IconLine(R.drawable.ic_schedule, tr("Apply by ", "আবেদনের শেষ তারিখ ") + Rules.formatDate(j.deadline))
            if (j.myStatus.isNotBlank()) {
                Tag(JobText.application(j.myStatus), cs.secondaryContainer, cs.onSecondaryContainer)
            }
        }
    }
}

@Composable
private fun MyApplicationsPanel(refreshKey: Int, onOpenJob: (String) -> Unit, onFind: () -> Unit) {
    var list by remember { mutableStateOf<List<MyApplication>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(refreshKey) {
        runCatching { JobsApi.myApplications() }.onSuccess { list = it; errorMsg = null }.onFailure { errorMsg = it.message }
        loading = false
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ErrorText(errorMsg) }
        when {
            loading -> item { LoadingBox() }
            list.isEmpty() -> item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    EmptyState(R.drawable.ic_send, tr("No applications yet", "এখনো কোনো আবেদন করেননি"), tr("Find a job and apply with your CV.", "চাকরি খুঁজে সিভি দিয়ে আবেদন করুন।"), Modifier.padding(top = 24.dp))
                    OutlinedButton(onClick = onFind, modifier = Modifier.fillMaxWidth()) { ButtonContent(false, tr("Find jobs", "চাকরি খুঁজুন"), R.drawable.ic_search) }
                }
            }
            else -> items(list, key = { it.jobId }) { a ->
                val cs = MaterialTheme.colorScheme
                Card(
                    Modifier.fillMaxWidth().clickable(enabled = a.job != null) { onOpenJob(a.jobId) },
                    colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(a.job?.title ?: tr("Job removed", "বিজ্ঞাপনটা আর নেই"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        a.job?.let { Text(it.orgName, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant) }
                        val good = a.status in listOf("shortlisted", "interview", "hired")
                        Tag(
                            JobText.application(a.status),
                            if (good) cs.tertiaryContainer else cs.surfaceVariant,
                            if (good) cs.onTertiaryContainer else cs.onSurfaceVariant,
                        )
                        if (a.note.isNotBlank()) IconLine(R.drawable.ic_info, tr("Employer's note: ", "নিয়োগকর্তার কথা: ") + a.note)
                        if (a.job != null && a.job.status != "open" && a.status !in listOf("hired", "rejected", "withdrawn")) {
                            IconLine(R.drawable.ic_info, tr("Hiring has closed", "নিয়োগ বন্ধ হয়েছে"))
                        }
                        Text(tr("Applied ", "আবেদন ") + Rules.formatDate(a.appliedAt + Rules.DHAKA_OFFSET_MS), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------
// চাকরির বিস্তারিত ও আবেদন
// ------------------------------------------------------------

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
    var confirmWithdraw by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(id, reload) {
        runCatching { JobsApi.get(id) }.onSuccess { d = it; errorMsg = null }.onFailure { errorMsg = it.message }
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
            Text(j.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(j.orgName, style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant)
                if (j.employerVerified) {
                    Spacer(Modifier.size(6.dp))
                    AppIcon(R.drawable.ic_verified, null, Modifier.size(18.dp), cs.primary)
                    Text(tr(" Verified", " যাচাই করা"), style = MaterialTheme.typography.labelMedium, color = cs.primary)
                }
            }
            if (j.mine || j.status != "open") Tag(JobText.jobStatus(j.status), cs.surfaceVariant, cs.onSurfaceVariant)
            if (j.mine) StatusReasonLine(j.status, j.statusReason)
            Text(JobText.salary(j.salaryMin, j.salaryMax), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = cs.primary)
            IconLine(R.drawable.ic_location_on, areaText(j.district, j.upazilaId))
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
            ErrorText(errorMsg)
            InfoText(infoMsg)
            if (cvMissing) {
                OutlinedButton(onClick = onEditCv, modifier = Modifier.fillMaxWidth()) { ButtonContent(false, tr("Complete my CV", "সিভি পূরণ করুন"), R.drawable.ic_edit) }
            }
            if (j.mine) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (j.status in listOf("open", "pending", "rejected")) {
                        OutlinedButton(onClick = { onEditJob(j.id) }) { ButtonContent(false, tr("Edit", "বদলান"), R.drawable.ic_edit) }
                    }
                    Button(onClick = { onOpenApplicants(j.id) }) { ButtonContent(false, tr("Applicants (${j.applicants})", "আবেদনকারী (${num(j.applicants)})"), R.drawable.ic_group_round) }
                }
            } else {
                val st = x.myStatus
                when {
                    st.isNotBlank() && st != "withdrawn" -> {
                        Tag(JobText.application(st), cs.secondaryContainer, cs.onSecondaryContainer)
                        if (x.myNote.isNotBlank()) IconLine(R.drawable.ic_info, tr("Employer's note: ", "নিয়োগকর্তার কথা: ") + x.myNote)
                        if (st !in listOf("hired", "rejected")) {
                            TextButton(onClick = { confirmWithdraw = true }, enabled = !busy) { Text(tr("Withdraw application", "আবেদন তুলে নিন"), color = cs.error) }
                        }
                    }
                    j.status == "open" -> Button(onClick = { applying = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        ButtonContent(busy, tr("Apply with my CV", "সিভি দিয়ে আবেদন করুন"), R.drawable.ic_send)
                    }
                    else -> SmallNote(tr("This job is no longer taking applications.", "এই চাকরিতে আর আবেদন নেওয়া হচ্ছে না।"))
                }
                TextButton(onClick = { reporting = true }) { Text(tr("Report this job", "বিজ্ঞাপনটা রিপোর্ট করুন"), color = cs.onSurfaceVariant) }
            }
        }
    }
    if (applying) {
        var note by remember { mutableStateOf("") }
        FormDialog(
            title = tr("Apply", "আবেদন"),
            onDismiss = { applying = false },
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallNote(tr("The employer will see your CV and phone number (as set in your CV).", "নিয়োগকর্তা আপনার সিভি আর (সিভিতে যেমন ঠিক করেছেন) ফোন নম্বর দেখবেন।"))
                    OutlinedTextField(note, { note = it.take(500) }, label = { Text(tr("A short note (optional)", "ছোট করে কিছু লিখুন (ঐচ্ছিক)")) }, minLines = 3, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    applying = false
                    scope.launch {
                        busy = true
                        errorMsg = null
                        cvMissing = false
                        runCatching { JobsApi.apply(id, note) }
                            .onSuccess { infoMsg = tr("Applied. You'll get a notification when the employer responds.", "আবেদন হয়েছে। নিয়োগকর্তা সাড়া দিলে নোটিফিকেশন পাবেন।"); reload++ }
                            .onFailure { errorMsg = it.message; cvMissing = (it as? ServerError)?.code == "cv_incomplete" }
                        busy = false
                    }
                }) { Text(tr("Send", "পাঠান")) }
            },
            dismissButton = { TextButton(onClick = { applying = false }) { Text(tr("Cancel", "বাতিল")) } },
        )
    }
    if (confirmWithdraw) {
        AlertDialog(
            onDismissRequest = { confirmWithdraw = false },
            title = { Text(tr("Withdraw your application?", "আবেদন তুলে নেবেন?")) },
            text = { Text(tr("The employer will no longer see it. You can apply again while the job is open.", "নিয়োগকর্তা আর দেখবেন না। বিজ্ঞাপন চালু থাকলে আবার আবেদন করা যাবে।")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmWithdraw = false
                    scope.launch {
                        busy = true
                        runCatching { JobsApi.withdraw(id) }.onSuccess { infoMsg = tr("Withdrawn", "তুলে নেওয়া হয়েছে"); reload++ }.onFailure { errorMsg = it.message }
                        busy = false
                    }
                }) { Text(tr("Withdraw", "তুলে নিন"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmWithdraw = false }) { Text(tr("Cancel", "বাতিল")) } },
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

// ------------------------------------------------------------
// নিয়োগকর্তা: প্রতিষ্ঠানের তথ্য, বিজ্ঞাপন, আবেদনকারী
// ------------------------------------------------------------

@Composable
private fun HirePanel(profile: Profile, refreshKey: Int, onEditJob: (String?) -> Unit, onOpenApplicants: (String) -> Unit) {
    var employer by remember { mutableStateOf<Employer?>(null) }
    var jobs by remember { mutableStateOf<List<Job>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    var closing by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
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
                item {
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
                            if (e.verifyStatus == "rejected" && e.statusReason.isNotBlank()) IconLine(R.drawable.ic_info, tr("Reason: ", "কারণ: ") + e.statusReason)
                            DocsNeededNotice("employer", e.uid, VerifyDocs.employer(), missing = false, requested = e.docsRequested, note = e.docsNote) { reload++ }
                            if (e.verifyStatus in listOf("none", "rejected") && !e.docsRequested) {
                                DocsNeededNotice(
                                    "employer", e.uid, VerifyDocs.employer(), missing = true, requested = false,
                                    note = tr("Add a trade licence to get the verified mark; then your jobs go live without waiting.", "ট্রেড লাইসেন্স দিলে \"যাচাই করা\" চিহ্ন পাবেন; তখন বিজ্ঞাপন অপেক্ষা ছাড়াই চালু হবে।"),
                                ) { reload++ }
                            }
                            IconLine(R.drawable.ic_call, num(e.phone))
                            OutlinedButton(onClick = { editing = true }, modifier = Modifier.fillMaxWidth()) { ButtonContent(false, tr("Edit details", "তথ্য বদলান"), R.drawable.ic_edit) }
                        }
                    }
                }
                item {
                    Button(onClick = { onEditJob(null) }, modifier = Modifier.fillMaxWidth()) { ButtonContent(false, tr("Post a job", "চাকরির বিজ্ঞাপন দিন"), R.drawable.ic_add) }
                }
                if (jobs.isNotEmpty()) item { SectionTitle(tr("My job posts", "আমার বিজ্ঞাপন")) }
                items(jobs, key = { it.id }) { j ->
                    val cs = MaterialTheme.colorScheme
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(j.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(JobText.jobStatus(j.status), style = MaterialTheme.typography.labelLarge, color = cs.primary)
                            StatusReasonLine(j.status, j.statusReason)
                            IconLine(R.drawable.ic_schedule, tr("Apply by ", "আবেদনের শেষ তারিখ ") + Rules.formatDate(j.deadline))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                SmallAction(tr("Applicants (${j.applicants})", "আবেদনকারী (${num(j.applicants)})"), R.drawable.ic_group_round, { onOpenApplicants(j.id) }, primary = j.applicants > 0)
                                if (j.status in listOf("open", "pending", "rejected")) SmallAction(tr("Edit", "বদলান"), R.drawable.ic_edit, { onEditJob(j.id) })
                                if (j.status in listOf("open", "pending")) SmallAction(tr("Close hiring", "নিয়োগ বন্ধ"), R.drawable.ic_close, { closing = j })
                            }
                        }
                    }
                }
            }
        }
    }
    closing?.let { j ->
        AlertDialog(
            onDismissRequest = { closing = null },
            title = { Text(tr("Close hiring?", "নিয়োগ বন্ধ করবেন?")) },
            text = { Text(tr("The job leaves the list. Applicants still waiting get a notification.", "বিজ্ঞাপন তালিকা থেকে সরে যাবে। যাঁদের সিদ্ধান্ত বাকি, তাঁরা নোটিফিকেশন পাবেন।")) },
            confirmButton = {
                TextButton(onClick = {
                    closing = null
                    scope.launch {
                        runCatching { JobsApi.close(j.id) }.onSuccess { infoMsg = tr("Hiring closed", "নিয়োগ বন্ধ হয়েছে"); reload++ }.onFailure { errorMsg = it.message }
                    }
                }) { Text(tr("Close", "বন্ধ করুন"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { closing = null }) { Text(tr("Cancel", "বাতিল")) } },
        )
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

/** নতুন বিজ্ঞাপন বা বদলানো (id থাকলে) */
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
    var vacancies by remember { mutableStateOf("1") }
    var experience by remember { mutableStateOf("0") }
    var education by remember { mutableStateOf("none") }
    var skills by remember { mutableStateOf(listOf<String>()) }
    var description by remember { mutableStateOf("") }
    var deadline by remember { mutableStateOf(Rules.todayUtcMidnight() + 14 * DAY_MS) }
    var pickDate by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var doneMsg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(id) {
        if (id == null) return@LaunchedEffect
        runCatching { JobsApi.get(id).job }.onSuccess { j ->
            title = j.title; category = j.category; type = j.type; district = j.district; upazilaId = j.upazilaId
            salaryMin = j.salaryMin.toString(); salaryMax = j.salaryMax.toString(); vacancies = j.vacancies.toString()
            experience = j.experienceYears.toString(); education = j.education; skills = j.skills; description = j.description
            deadline = maxOf(j.deadline, Rules.todayUtcMidnight())
            loaded = true
        }.onFailure { errorMsg = it.message }
    }
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
            Text(tr("Where is the work?", "কাজের জায়গা"), style = MaterialTheme.typography.labelLarge)
            AreaChips(district, upazilaId, null, { d, u, _ -> district = d; upazilaId = u }, allowAllDistricts = false, showUnion = false)
            Text(tr("Monthly salary (taka)", "মাসিক বেতন (টাকা)"), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(salaryMin, { salaryMin = it.filter(Char::isDigit).take(7) }, label = { Text(tr("From", "সর্বনিম্ন")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(salaryMax, { salaryMax = it.filter(Char::isDigit).take(7) }, label = { Text(tr("To", "সর্বোচ্চ")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.weight(1f))
            }
            SmallNote(tr("Jobs show the salary — people apply more when they know it.", "বেতন দেখানো বাধ্যতামূলক — জানলে মানুষ বেশি আবেদন করেন।"))
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
            SmallNote(tr("Asking applicants for any fee or deposit is not allowed; such jobs are taken down.", "প্রার্থীর কাছে কোনো ফি বা জামানত চাওয়া যাবে না; চাইলে বিজ্ঞাপন বন্ধ করা হয়।"))
            ErrorText(errorMsg)
            Button(
                onClick = {
                    val d = district
                    val min = salaryMin.toIntOrNull() ?: 0
                    val max = salaryMax.toIntOrNull()?.takeIf { it > 0 } ?: min
                    errorMsg = when {
                        title.isBlank() -> tr("Write the job title", "পদের নাম লিখুন")
                        category.isBlank() -> tr("Choose the field", "কাজের ক্ষেত্র বেছে নিন")
                        d == null -> tr("Choose a district", "জেলা বেছে নিন")
                        min < 500 || max < min -> tr("Enter the monthly salary range", "মাসিক বেতনের সীমা দিন")
                        description.trim().length < 30 -> tr("Describe the work (at least 30 characters)", "কাজের বর্ণনা লিখুন (অন্তত ৩০ অক্ষর)")
                        else -> null
                    }
                    if (errorMsg != null || d == null) return@Button
                    scope.launch {
                        busy = true
                        runCatching {
                            JobsApi.saveJob(
                                id, title.trim(), category, type, d, upazilaId, min, max, vacancies.toIntOrNull()?.coerceIn(1, 500) ?: 1,
                                experience.toIntOrNull()?.coerceIn(0, 40) ?: 0, education, skills, description.trim(), deadline,
                            )
                        }.onSuccess { st ->
                            doneMsg = if (st == "open") tr("Your job is live.", "বিজ্ঞাপন চালু হয়েছে।")
                            else tr("Sent for checking. It goes live after the Lifolo team approves (usually within a day).", "যাচাইয়ের জন্য পাঠানো হয়েছে। Lifolo টিম অনুমোদন দিলে চালু হবে (সাধারণত এক দিনের মধ্যে)।")
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

/** নিয়োগকর্তা: একটা বিজ্ঞাপনের আবেদনকারীরা — সিভি দেখা, ফোন, আর সিদ্ধান্ত */
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
    LaunchedEffect(id, reload) {
        runCatching { JobsApi.applicants(id) }.onSuccess { job = it.first; list = it.second; errorMsg = null }.onFailure { errorMsg = it.message }
    }
    Scaffold(topBar = { BackTopBar(job?.title ?: tr("Applicants", "আবেদনকারী"), onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column {
                    ErrorText(errorMsg)
                    InfoText(infoMsg)
                }
            }
            when {
                job == null && errorMsg == null -> item { LoadingBox() }
                job != null && list.isEmpty() -> item {
                    EmptyState(R.drawable.ic_group_round, tr("No applications yet", "এখনো কেউ আবেদন করেননি"), tr("You'll get a notification when someone applies.", "কেউ আবেদন করলে নোটিফিকেশন পাবেন।"), Modifier.padding(top = 24.dp))
                }
                else -> items(list, key = { it.uid }) { a ->
                    val cs = MaterialTheme.colorScheme
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            CvHeader(a.cv, small = true)
                            val good = a.status in listOf("shortlisted", "interview", "hired")
                            Tag(JobText.application(a.status), if (good) cs.tertiaryContainer else cs.surfaceVariant, if (good) cs.onTertiaryContainer else cs.onSurfaceVariant)
                            if (a.coverNote.isNotBlank()) Text("“" + a.coverNote + "”", style = MaterialTheme.typography.bodyMedium)
                            if (a.note.isNotBlank()) IconLine(R.drawable.ic_info, tr("Your note: ", "আপনার কথা: ") + a.note)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                SmallAction(if (open == a.uid) tr("Hide CV", "সিভি লুকান") else tr("View CV", "সিভি দেখুন"), R.drawable.ic_badge, { open = if (open == a.uid) null else a.uid })
                                if (a.cv.phone.isNotBlank()) SmallAction(tr("Call", "ফোন করুন"), R.drawable.ic_call, { callPhone(context, a.cv.phone) }, primary = true)
                                if (a.cv.email.isNotBlank()) SmallAction(tr("Email", "ইমেইল"), R.drawable.ic_mail, { sendEmail(context, a.cv.email) })
                            }
                            if (a.cv.phone.isBlank()) SmallNote(tr("This person keeps the phone number private. Shortlist or call for interview — they get a notification.", "ইনি ফোন নম্বর গোপন রেখেছেন। শর্টলিস্ট বা ইন্টারভিউয়ে ডাকলে নোটিফিকেশন পাবেন।"))
                            if (open == a.uid) CvBody(a.cv)
                            if (a.status != "hired") {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    JobText.DECISIONS.filter { it != a.status }.forEach { s ->
                                        FilterChip(selected = false, onClick = { deciding = a to s }, label = { Text(JobText.decision(s)) })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    deciding?.let { (a, s) ->
        var note by remember(a.uid, s) { mutableStateOf("") }
        var busy by remember(a.uid, s) { mutableStateOf(false) }
        FormDialog(
            title = JobText.decision(s) + " — " + a.cv.name,
            onDismiss = { deciding = null },
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallNote(tr("The applicant gets a notification with your note.", "প্রার্থী আপনার কথাসহ নোটিফিকেশন পাবেন।"))
                    OutlinedTextField(
                        note, { note = it.take(300) },
                        label = { Text(if (s == "interview") tr("Date, time and place", "তারিখ, সময় আর জায়গা") else tr("Note (optional)", "কিছু বলার থাকলে (ঐচ্ছিক)")) },
                        minLines = 2, modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        runCatching { JobsApi.setStatus(id, a.uid, s, note) }
                            .onSuccess { deciding = null; infoMsg = tr("Sent to ", "জানানো হয়েছে: ") + a.cv.name; reload++ }
                            .onFailure { deciding = null; errorMsg = it.message }
                        busy = false
                    }
                }) { Text(tr("Send", "পাঠান")) }
            },
            dismissButton = { TextButton(onClick = { deciding = null }) { Text(tr("Cancel", "বাতিল")) } },
        )
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
