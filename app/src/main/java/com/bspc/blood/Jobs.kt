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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

// ------------------------------------------------------------
// চাকরি: প্রার্থী চাকরি খোঁজেন আর সিভি দিয়ে আবেদন করেন; নিয়োগকর্তা বিজ্ঞাপন দেন আর আবেদন সামলান।
// সার্ভার: lib/jobs.js। ফাইল: Jobs.kt (তালিকা, মডেল), JobDetail.kt (বিস্তারিত, আবেদন, সাড়া),
// JobEmployer.kt (নিয়োগকর্তা), JobAlarms.kt (ইন্টারভিউর মনে করানো), Cv.kt (সিভি)
// ------------------------------------------------------------

data class JobQuestion(val id: String, val text: String, val kind: String, val options: List<String>, val required: Boolean)

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
    val salaryNegotiable: Boolean = false,
    val paid: Boolean = true,
    val durationMonths: Int? = null,
    val coverLetter: String = "optional",
    val questions: List<JobQuestion> = emptyList(),
    /** বিরতি কেন: employer (নিজে) / deadline (শেষ তারিখ পার) */
    val pausedBy: String = "",
    /** তালিকায়: নিজের আবেদনের অবস্থা (না করলে খালি) */
    val myStatus: String = "",
    val saved: Boolean = false,
    val description: String = "",
    val mine: Boolean = false,
    val statusReason: String = "",
    /** নিজের বিজ্ঞাপনে: স্বয়ংক্রিয় যাচাইয়ে যা ধরা পড়েছে */
    val flags: List<String> = emptyList(),
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
    val postingBlocked: Boolean = false,
    val blockReason: String = "",
)

/** ইন্টারভিউর আমন্ত্রণ: response = pending / accepted / declined / reschedule */
data class Interview(val at: Long, val mode: String, val place: String, val link: String, val response: String, val responseNote: String)

/** চাকরির প্রস্তাব: response = pending / accepted / declined / expired */
data class Offer(val designation: String, val salary: Int, val joiningDate: Long, val expiresAt: Long, val response: String)

/** আবেদনের অবস্থার সাথে থাকা বাড়তি তথ্য */
data class AppExtras(val reason: String, val interview: Interview?, val offer: Offer?, val withdrawReason: String, val withdrawNote: String)

data class Applicant(
    val uid: String, val status: String, val note: String, val coverNote: String, val answers: Map<String, String>,
    val appliedAt: Long, val extras: AppExtras, val cv: Cv,
)

data class MyApplication(val jobId: String, val status: String, val note: String, val appliedAt: Long, val updatedAt: Long, val extras: AppExtras, val job: Job?)

data class JobDetail(val job: Job, val employer: Employer?, val myStatus: String, val myNote: String, val extras: AppExtras?)

data class JobAlert(val district: String, val upazilaId: Int?, val categories: List<String>, val type: String, val minSalary: Int, val on: Boolean)

data class UpcomingInterview(val id: String, val jobId: String, val role: String, val at: Long, val mode: String, val place: String, val link: String, val title: String, val who: String)

object JobsApi {
    private suspend fun call(action: String, body: JSONObject = JSONObject()): JSONObject =
        ServerApi.post("/api/community", body.put("action", action))

    internal fun job(o: JSONObject): Job {
        val qa = o.optJSONArray("questions") ?: JSONArray()
        val questions = (0 until qa.length()).mapNotNull { qa.optJSONObject(it) }.map {
            JobQuestion(it.str("id"), it.str("text"), it.str("kind").ifBlank { "text" }, it.optJSONArray("options").strings(), it.optBoolean("required"))
        }
        return Job(
            o.str("id"), o.str("title"), o.str("orgName"), o.optBoolean("employerVerified"), o.str("category"), o.str("type"),
            o.str("district"), o.intOrNull("upazilaId"), o.optInt("salaryMin"), o.optInt("salaryMax"), o.optInt("vacancies", 1),
            o.optInt("experienceYears"), o.str("education").ifBlank { "none" }, o.optJSONArray("skills").strings(),
            o.optLong("deadline"), o.optLong("createdAt"), o.optInt("applicants"), o.str("status"),
            o.optBoolean("salaryNegotiable"), o.optBoolean("paid", true), o.intOrNull("durationMonths"),
            o.str("coverLetter").ifBlank { "optional" }, questions, o.str("pausedBy"),
            o.str("myStatus"), o.optBoolean("saved"), o.str("description"), o.optBoolean("mine"), o.str("statusReason"),
            o.optJSONArray("flags").strings(),
        )
    }

    private fun employer(o: JSONObject) = Employer(
        o.str("uid"), o.str("name"), o.str("kind"), o.str("district"), o.intOrNull("upazilaId"), o.str("address"),
        o.str("website"), o.str("about"), o.optBoolean("verified"), o.str("phone"), o.str("verifyStatus").ifBlank { "none" },
        o.str("statusReason"), o.optBoolean("docsRequested"), o.str("docsRequestNote"), o.optBoolean("postingBlocked"), o.str("blockReason"),
    )

    internal fun extras(o: JSONObject) = AppExtras(
        o.str("reason"),
        o.optJSONObject("interview")?.let { Interview(it.optLong("at"), it.str("mode"), it.str("place"), it.str("link"), it.str("response").ifBlank { "pending" }, it.str("responseNote")) },
        o.optJSONObject("offer")?.let { Offer(it.str("designation"), it.optInt("salary"), it.optLong("joiningDate"), it.optLong("expiresAt"), it.str("response").ifBlank { "pending" }) },
        o.str("withdrawReason"), o.str("withdrawNote"),
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
            app?.let(::extras),
        )
    }

    /** ফেরত: আগেই আবেদন করা ছিল কি না */
    suspend fun apply(id: String, coverNote: String, answers: Map<String, String>): Boolean =
        call("job_apply", JSONObject().put("id", id).put("coverNote", coverNote.trim()).put("answers", JSONObject(answers))).optBoolean("already")

    suspend fun withdraw(id: String, reason: String, note: String) {
        call("application_withdraw", JSONObject().put("id", id).put("reason", reason).put("note", note.trim()))
    }

    suspend fun interviewRespond(jobId: String, response: String, note: String) {
        call("interview_respond", JSONObject().put("jobId", jobId).put("response", response).put("note", note.trim()))
    }

    suspend fun offerRespond(jobId: String, accept: Boolean) {
        call("offer_respond", JSONObject().put("jobId", jobId).put("accept", accept))
    }

    suspend fun myApplications(): List<MyApplication> {
        val a = call("my_applications").optJSONArray("items") ?: JSONArray()
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            MyApplication(o.str("jobId"), o.str("status"), o.str("note"), o.optLong("appliedAt"), o.optLong("updatedAt"), extras(o), o.optJSONObject("job")?.let(::job))
        }
    }

    suspend fun bookmark(id: String, saved: Boolean) {
        call("job_bookmark", JSONObject().put("id", id).put("saved", saved))
    }

    suspend fun saved(): List<Job> = list(call("saved_jobs").optJSONArray("items"))

    suspend fun alert(): JobAlert? = call("job_alert_get").optJSONObject("alert")?.let {
        JobAlert(it.str("district"), it.intOrNull("upazilaId"), it.optJSONArray("categories").strings(), it.str("type"), it.optInt("minSalary"), it.optBoolean("on"))
    }

    suspend fun saveAlert(a: JobAlert) {
        call(
            "job_alert_save",
            JSONObject().put("on", a.on).put("district", a.district).put("upazilaId", a.upazilaId ?: JSONObject.NULL)
                .put("categories", JSONArray(a.categories)).put("type", a.type).put("minSalary", a.minSalary),
        )
    }

    suspend fun upcomingInterviews(): List<UpcomingInterview> {
        val a = call("interviews_upcoming").optJSONArray("items") ?: JSONArray()
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            UpcomingInterview(o.str("id"), o.str("jobId"), o.str("role"), o.optLong("at"), o.str("mode"), o.str("place"), o.str("link"), o.str("title"), o.str("who"))
        }
    }

    // ---------- নিয়োগকর্তা ----------
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

    /** নতুন বা বদলানো; ফেরত: অবস্থা (open / pending / paused) আর সন্দেহ ধরা পড়েছে কি না */
    suspend fun saveJob(id: String?, body: JSONObject): Pair<String, Boolean> {
        val r = call("job_save", body.put("id", id ?: JSONObject.NULL))
        return r.str("status") to r.optBoolean("flags")
    }

    suspend fun pause(id: String, pause: Boolean) {
        call("job_pause", JSONObject().put("id", id).put("pause", pause))
    }

    suspend fun close(id: String) {
        call("job_close", JSONObject().put("id", id))
    }

    suspend fun applicants(id: String): Pair<Job, List<Applicant>> {
        val r = call("job_applicants", JSONObject().put("id", id))
        val a = r.optJSONArray("items") ?: JSONArray()
        return job(r.getJSONObject("job")) to (0 until a.length()).map {
            val o = a.getJSONObject(it)
            val ans = o.optJSONObject("answers") ?: JSONObject()
            Applicant(
                o.str("uid"), o.str("status"), o.str("note"), o.str("coverNote"),
                ans.keys().asSequence().associateWith { k -> ans.optString(k) },
                o.optLong("appliedAt"), extras(o), CvApi.parse(o.getJSONObject("cv")),
            )
        }
    }

    /** শর্টলিস্ট / ইন্টারভিউ / প্রস্তাব / বাছাই হয়নি */
    suspend fun setStatus(jobId: String, uid: String, status: String, note: String, extra: JSONObject = JSONObject()) {
        call("application_update", extra.put("jobId", jobId).put("uid", uid).put("status", status).put("note", note.trim()))
    }
}

/** সার্ভারের কোড → লেখা (সার্ভার, প্যানেল আর শেয়ার পাতার সাথে মেলানো) */
object JobText {
    val CATEGORIES = listOf("health", "education", "sales", "accounts", "office", "it", "driving", "factory", "hospitality", "security", "construction", "domestic", "other")
    val TYPES = listOf("fulltime", "parttime", "contract", "internship", "remote")
    val EDUCATION = listOf("none", "ssc", "hsc", "diploma", "bachelor", "masters")
    val EMPLOYER_KINDS = listOf("company", "shop", "hospital", "school", "ngo", "household", "other")
    val REJECT_REASONS = listOf("experience", "skills", "education", "location", "filled", "other")
    val WITHDRAW_REASONS = listOf("got_job", "salary", "location", "other")

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
        "remote" -> tr("Remote", "দূর থেকে কাজ")
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
        "interview" -> tr("Interview", "ইন্টারভিউ")
        "offered" -> tr("Job offer", "চাকরির প্রস্তাব")
        "rejected" -> tr("Not selected", "বাছাই হননি")
        "hired" -> tr("Hired", "নিয়োগ পেয়েছেন")
        "withdrawn" -> tr("Withdrawn", "তুলে নেওয়া হয়েছে")
        else -> s
    }

    /** বাছাই না হওয়ার কারণ (প্রার্থী ভদ্র ভাষায় দেখেন) */
    fun rejectReason(r: String) = when (r) {
        "experience" -> tr("More experience was needed", "আরও অভিজ্ঞতা দরকার ছিল")
        "skills" -> tr("The required skills did not match", "দরকারি দক্ষতার সাথে মেলেনি")
        "education" -> tr("The education requirement did not match", "শিক্ষাগত যোগ্যতা মেলেনি")
        "location" -> tr("The work location did not suit", "কাজের জায়গার সাথে মেলেনি")
        "filled" -> tr("The position has been filled", "পদটা পূরণ হয়ে গেছে")
        "other" -> tr("Another candidate was chosen", "অন্য প্রার্থী বাছাই হয়েছেন")
        else -> ""
    }

    fun withdrawReason(r: String) = when (r) {
        "got_job" -> tr("Got another job", "অন্য চাকরি পেয়েছি")
        "salary" -> tr("Salary didn't match", "বেতন মেলেনি")
        "location" -> tr("Location doesn't suit", "জায়গা সুবিধার নয়")
        "other" -> tr("Other reason", "অন্য কারণ")
        else -> ""
    }

    fun jobStatus(s: String, pausedBy: String = "") = when (s) {
        "open" -> tr("Live", "চালু")
        "paused" -> if (pausedBy == "deadline") tr("Paused — deadline passed", "বিরতি — শেষ তারিখ পার") else tr("Paused — not taking applications", "বিরতি — আবেদন নেওয়া বন্ধ")
        "pending" -> tr("Waiting for approval", "অনুমোদনের অপেক্ষায়")
        "changes" -> tr("The Dorkar team asked for changes", "Dorkar টিম কিছু ঠিক করতে বলেছে")
        "closed" -> tr("Closed — position filled", "বন্ধ — লোক পাওয়া গেছে")
        "rejected" -> tr("Not approved", "অনুমোদন হয়নি")
        "suspended" -> tr("Taken down by the Dorkar team", "Dorkar টিম বন্ধ করেছে")
        else -> s
    }

    private fun money(n: Int): String = num(String.format(java.util.Locale.US, "%,d", n))

    fun taka(n: Int) = "৳" + money(n)

    fun salary(min: Int, max: Int): String =
        "৳" + (if (min == max) money(min) else money(min) + " – " + money(max)) + tr("/month", "/মাস")

    /** বিজ্ঞাপনের বেতনের লেখা: সীমা / আলোচনাসাপেক্ষ / বিনা বেতনে */
    fun salaryOf(j: Job): String = when {
        !j.paid -> tr("Unpaid", "বিনা বেতনে") + (j.durationMonths?.let { tr(" · $it months", " · ${num(it)} মাস") } ?: "")
        j.salaryNegotiable && j.salaryMax > 0 -> salary(j.salaryMin, j.salaryMax) + tr(" · negotiable", " · আলোচনাসাপেক্ষ")
        j.salaryNegotiable -> tr("Negotiable based on experience", "অভিজ্ঞতা অনুযায়ী আলোচনাসাপেক্ষ")
        else -> salary(j.salaryMin, j.salaryMax) + (j.durationMonths?.let { tr(" · $it months", " · ${num(it)} মাস") } ?: "")
    }

    fun experience(y: Int) = if (y <= 0) tr("Freshers welcome", "অভিজ্ঞতা লাগবে না") else tr("$y+ years experience", "${num(y)}+ বছরের অভিজ্ঞতা")

    /** "২০ অক্টোবর ২০২৬, বিকাল ৪:০০" */
    fun dateTime(at: Long) = Rules.formatDate(at + Rules.DHAKA_OFFSET_MS) + ", " + Rules.formatTime(at)
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
    val TYPES = setOf("job", "job_alert", "job_applicants", "job_mine")

    /** নোটিফিকেশনের ধরন → ট্যাবের অংশ আর উপরে খোলার পাতা */
    fun route(type: String?, jobId: String?): Pair<Int, JobsPage?> {
        val id = jobId ?: this.jobId.value
        this.jobId.value = null
        return when (type) {
            // নতুন চাকরির অ্যালার্ট আর সেভ করা চাকরির মনে করানো: "চাকরি খুঁজুন"; বাকিটা নিজের আবেদন
            "job_alert" -> 0 to id?.let { JobsPage.Detail(it) }
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
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val target = JobsNav.section.value
    LaunchedEffect(target) {
        if (target != null) {
            tab = target
            JobsNav.section.value = null
        }
    }
    // সামনের ইন্টারভিউর মনে করানো ফোনে বসানো (প্রার্থী আর নিয়োগকর্তা দুজনেরই)
    LaunchedEffect(refreshKey) { JobAlarms.sync(context) }
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
            0 -> JobSearch(profile, refreshKey) { open(JobsPage.Detail(it)) }
            1 -> MyApplicationsPanel(refreshKey, { open(JobsPage.Detail(it)) }, onFind = { tab = 0 })
            2 -> MyCvPanel(refreshKey) { open(JobsPage.EditCv) }
            else -> HirePanel(profile, refreshKey, onEditJob = { open(JobsPage.Edit(it)) }, onOpenJob = { open(JobsPage.Detail(it)) }, onOpenApplicants = { open(JobsPage.Applicants(it)) })
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
    /** ০ সব চাকরি, ১ সেভ করা */
    var view by rememberSaveable { mutableIntStateOf(0) }
    var list by remember { mutableStateOf<List<Job>>(emptyList()) }
    var savedList by remember { mutableStateOf<List<Job>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    var showArea by remember { mutableStateOf(false) }
    var showFilter by remember { mutableStateOf(false) }
    var showAlert by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(district, upazilaId, category, type, minSalary, q, refreshKey, view, reload) {
        if (view == 1) {
            loading = true
            runCatching { JobsApi.saved() }.onSuccess { savedList = it; errorMsg = null }.onFailure { errorMsg = it.message }
            loading = false
            return@LaunchedEffect
        }
        val d = district ?: run { loading = false; return@LaunchedEffect }
        // লেখার মাঝে প্রতি অক্ষরে সার্ভারে না গিয়ে একটু থেমে
        if (q.isNotBlank()) kotlinx.coroutines.delay(400)
        loading = true
        runCatching { JobsApi.search(d, upazilaId, category, type, minSalary, q) }
            .onSuccess { list = it; errorMsg = null }.onFailure { errorMsg = it.message }
        loading = false
    }

    fun toggleSave(j: Job) {
        scope.launch {
            runCatching { JobsApi.bookmark(j.id, !j.saved) }
                .onSuccess {
                    infoMsg = if (j.saved) tr("Removed from saved", "সেভ থেকে সরানো হয়েছে") else tr("Saved. We'll remind you 2 days before the deadline.", "সেভ হয়েছে। শেষ তারিখের ২ দিন আগে মনে করানো হবে।")
                    list = list.map { if (it.id == j.id) it.copy(saved = !j.saved) else it }
                    if (view == 1) savedList = savedList.filter { it.id != j.id }
                }
                .onFailure { errorMsg = it.message }
        }
    }

    val active = buildList {
        category?.let { add(ActiveFilter(JobText.category(it)) { category = null }) }
        type?.let { add(ActiveFilter(JobText.type(it)) { type = null }) }
        minSalary?.let { add(ActiveFilter(tr("From ", "কমপক্ষে ") + JobText.taka(it)) { minSalary = null }) }
    }
    val shown = if (view == 1) savedList else list
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (view == 0) {
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
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = view == 0, onClick = { view = 0 }, label = { Text(tr("All jobs", "সব চাকরি")) })
                    FilterChip(selected = view == 1, onClick = { view = 1 }, label = { Text(tr("Saved", "সেভ করা")) })
                    SmallAction(tr("Job alert", "চাকরির অ্যালার্ট"), R.drawable.ic_notifications, { showAlert = true })
                }
                ErrorText(errorMsg)
                InfoText(infoMsg)
            }
        }
        when {
            view == 0 && district == null -> item { EmptyState(R.drawable.ic_location_on, tr("Choose a district", "জেলা বেছে নিন"), null, Modifier.padding(top = 24.dp)) }
            loading -> item { LoadingBox() }
            shown.isEmpty() -> item {
                if (view == 1) {
                    EmptyState(R.drawable.ic_star, tr("No saved jobs", "সেভ করা কোনো চাকরি নেই"), tr("Tap the star on a job to save it for later.", "পরে দেখতে চাকরির কার্ডের তারায় চাপুন।"), Modifier.padding(top = 24.dp))
                } else {
                    EmptyState(
                        R.drawable.ic_nav_work,
                        tr("No jobs here yet", "এখানে এখনো কোনো চাকরির বিজ্ঞাপন নেই"),
                        tr("Turn on a job alert to hear when one comes.", "নতুন চাকরি এলে জানতে \"চাকরির অ্যালার্ট\" চালু করুন।"),
                        Modifier.padding(top = 24.dp),
                    )
                }
            }
            else -> items(shown, key = { it.id }) { j -> JobCard(j, onSave = { toggleSave(j) }) { onOpenJob(j.id) } }
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
                    SALARY_STEPS.map { it.toString() },
                    minSalary?.toString(),
                    { JobText.taka(it.toInt()) },
                ) { minSalary = it?.toInt() }
            }
        }
    }
    if (showAlert) {
        JobAlertSheet(profile, district, onDismiss = { showAlert = false }) { msg -> infoMsg = msg }
    }
}

private val SALARY_STEPS = listOf(8000, 12000, 20000, 30000, 50000)

/** চাকরির অ্যালার্ট: এলাকা, ক্ষেত্র, ধরন আর ন্যূনতম বেতন — নতুন মিলে যাওয়া চাকরি এলে নোটিফিকেশন */
@Composable
private fun JobAlertSheet(profile: Profile, currentDistrict: String?, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var on by remember { mutableStateOf(true) }
    var district by remember { mutableStateOf(currentDistrict ?: profile.placeName) }
    var upazilaId by remember { mutableStateOf<Int?>(null) }
    var cats by remember { mutableStateOf(setOf<String>()) }
    var type by remember { mutableStateOf<String?>(null) }
    var minSalary by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var pickArea by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching { JobsApi.alert() }.onSuccess { a ->
            if (a != null) {
                on = a.on; district = a.district; upazilaId = a.upazilaId; cats = a.categories.toSet()
                type = a.type.ifBlank { null }; minSalary = a.minSalary.takeIf { it > 0 }
            }
        }
        loaded = true
    }
    FilterSheet(tr("Job alert", "চাকরির অ্যালার্ট"), onDismiss = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!loaded) {
                LoadingBox()
                return@Column
            }
            SmallNote(tr("Get a notification when a matching job is posted.", "মিলে যাওয়া নতুন চাকরি এলে নোটিফিকেশন পাবেন।"))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Alert on", "অ্যালার্ট চালু"), modifier = Modifier.weight(1f))
                Switch(checked = on, onCheckedChange = { on = it })
            }
            if (on) {
                OutlinedButton(onClick = { pickArea = true }) { ButtonContent(false, areaText(district, upazilaId), R.drawable.ic_location_on) }
                Text(tr("Fields (none = all, up to 5)", "কাজের ক্ষেত্র (না বাছলে সব, ৫টা পর্যন্ত)"), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    JobText.CATEGORIES.forEach { c ->
                        FilterChip(selected = c in cats, onClick = { cats = if (c in cats) cats - c else if (cats.size < 5) cats + c else cats }, label = { Text(JobText.category(c)) })
                    }
                }
                FilterChoice(tr("Type", "ধরন"), JobText.TYPES, type, JobText::type) { type = it }
                FilterChoice(tr("Monthly salary at least", "মাসিক বেতন কমপক্ষে"), SALARY_STEPS.map { it.toString() }, minSalary?.toString(), { JobText.taka(it.toInt()) }) { minSalary = it?.toInt() }
            }
            ErrorText(errorMsg)
            Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                val d = district
                if (on && d == null) {
                    errorMsg = tr("Choose a district", "জেলা বেছে নিন")
                    return@Button
                }
                scope.launch {
                    busy = true
                    runCatching { JobsApi.saveAlert(JobAlert(d ?: "", upazilaId, cats.toList(), type ?: "", minSalary ?: 0, on)) }
                        .onSuccess { onDone(if (on) tr("Job alert saved", "চাকরির অ্যালার্ট সেভ হয়েছে") else tr("Job alert turned off", "চাকরির অ্যালার্ট বন্ধ হয়েছে")); onDismiss() }
                        .onFailure { errorMsg = it.message }
                    busy = false
                }
            }) { ButtonContent(busy, tr("Save", "সেভ করুন")) }
        }
    }
    if (pickArea) {
        AreaSheet(district, upazilaId, null, { d, u, _ -> district = d; upazilaId = u }, onDismiss = { pickArea = false })
    }
}

/** তালিকার কার্ড; onSave থাকলে কোণে তারা (সেভ) */
@Composable
fun JobCard(j: Job, onSave: (() -> Unit)? = null, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(j.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(j.orgName, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                        if (j.employerVerified) {
                            Spacer(Modifier.size(4.dp))
                            AppIcon(R.drawable.ic_verified, tr("Verified employer", "যাচাই করা নিয়োগকর্তা"), Modifier.size(16.dp), cs.primary)
                        }
                    }
                }
                if (onSave != null) {
                    androidx.compose.material3.IconButton(onClick = onSave) {
                        AppIcon(
                            if (j.saved) R.drawable.ic_star_fill else R.drawable.ic_star,
                            if (j.saved) tr("Remove from saved", "সেভ থেকে সরান") else tr("Save", "সেভ করুন"),
                            Modifier.size(22.dp), if (j.saved) cs.primary else cs.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(JobText.salaryOf(j), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = cs.primary)
            IconLine(R.drawable.ic_location_on, if (j.type == "remote") tr("Remote · ", "দূর থেকে · ") + areaText(j.district, j.upazilaId) else areaText(j.district, j.upazilaId))
            IconLine(R.drawable.ic_nav_work, JobText.type(j.type) + " · " + JobText.category(j.category))
            IconLine(R.drawable.ic_schedule, tr("Apply by ", "আবেদনের শেষ তারিখ ") + Rules.formatDate(j.deadline))
            if (j.status != "open") Tag(JobText.jobStatus(j.status, j.pausedBy), cs.surfaceVariant, cs.onSurfaceVariant)
            if (j.myStatus.isNotBlank() && j.myStatus != "withdrawn") Tag(tr("Already applied · ", "আগেই আবেদন করেছেন · ") + JobText.application(j.myStatus), cs.secondaryContainer, cs.onSecondaryContainer)
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
                        ApplicationStatusTag(a.status)
                        ApplicationNextStep(a.status, a.extras)
                        if (a.job != null && a.job.status != "open" && a.status !in listOf("hired", "rejected", "withdrawn")) {
                            IconLine(R.drawable.ic_info, JobText.jobStatus(a.job.status, a.job.pausedBy))
                        }
                        Text(tr("Applied ", "আবেদন ") + Rules.formatDate(a.appliedAt + Rules.DHAKA_OFFSET_MS), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun ApplicationStatusTag(status: String) {
    val cs = MaterialTheme.colorScheme
    val good = status in listOf("shortlisted", "interview", "offered", "hired")
    Tag(JobText.application(status), if (good) cs.tertiaryContainer else cs.surfaceVariant, if (good) cs.onTertiaryContainer else cs.onSurfaceVariant)
}

/** অবস্থার সাথে এক লাইনে যা করার আছে / যা জানানো হয়েছে */
@Composable
fun ApplicationNextStep(status: String, x: AppExtras) {
    when {
        status == "interview" && x.interview != null -> {
            val iv = x.interview
            IconLine(R.drawable.ic_calendar_month, JobText.dateTime(iv.at) + " · " + if (iv.mode == "online") tr("Online", "অনলাইনে") else iv.place)
            if (iv.response == "pending") IconLine(R.drawable.ic_info, tr("Open to reply: will you come?", "খুলে জানান: আসবেন কি না"), MaterialTheme.colorScheme.primary)
        }
        status == "offered" && x.offer != null -> {
            val o = x.offer
            IconLine(R.drawable.ic_workspace_premium, o.designation + " · " + JobText.taka(o.salary) + tr("/month", "/মাস"))
            when (o.response) {
                "pending" -> IconLine(R.drawable.ic_info, tr("Reply by ", "উত্তর দিন ") + Rules.formatDate(o.expiresAt) + tr("", "-এর মধ্যে"), MaterialTheme.colorScheme.primary)
                "declined" -> IconLine(R.drawable.ic_info, tr("You declined the offer", "আপনি প্রস্তাব ফিরিয়ে দিয়েছেন"))
                "expired" -> IconLine(R.drawable.ic_info, tr("The offer expired", "প্রস্তাবের মেয়াদ শেষ"))
            }
        }
        status == "rejected" && x.reason.isNotBlank() -> IconLine(R.drawable.ic_info, JobText.rejectReason(x.reason))
    }
}
