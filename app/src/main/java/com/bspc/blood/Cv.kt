@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.bspc.blood

// প্রোফাইল = সিভি: অ্যাপের মূল অংশ (চাকরি ফিচার মুছলেও থাকে)

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// ------------------------------------------------------------
// সিভি (LinkedIn-এর মতো প্রোফাইল): নিচের বারের "প্রোফাইল"-এ দেখায়, চাকরির আবেদনে যায়,
// PDF করে রাখা যায়, চাইলে লিংক/QR দিয়ে শেয়ার। সার্ভার: lib/cv.js
// ------------------------------------------------------------

/** কাজের ক্ষেত্র আর শিক্ষার স্তরের লেখা (সিভি আর চাকরি দুই জায়গাতেই) */
object WorkText {
    val CATEGORIES = listOf("health", "education", "sales", "accounts", "office", "it", "driving", "factory", "hospitality", "security", "construction", "domestic", "other")
    val EDUCATION = listOf("none", "ssc", "hsc", "diploma", "bachelor", "masters")

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

    fun education(e: String) = when (e) {
        "ssc" -> tr("SSC", "এসএসসি")
        "hsc" -> tr("HSC", "এইচএসসি")
        "diploma" -> tr("Diploma", "ডিপ্লোমা")
        "bachelor" -> tr("Bachelor's", "স্নাতক")
        "masters" -> tr("Master's", "স্নাতকোত্তর")
        else -> tr("Not required", "লাগবে না")
    }
}

/** verified: চাকরির প্রস্তাব নিয়ে যোগ হওয়া কর্মস্থল (নিয়োগকর্তা নিশ্চিত করেছেন) */
data class CvExp(val title: String, val org: String, val from: String, val to: String, val current: Boolean, val details: String, val verified: Boolean = false)
/** level: এসএসসি/এইচএসসি… (নিয়োগকর্তার ছাঁকার জন্য; খালি = বলা নেই) */
data class CvEdu(val degree: String, val institute: String, val year: Int?, val result: String, val level: String = "")
data class CvCert(val name: String, val issuer: String, val year: Int?, val link: String = "")

data class Cv(
    val uid: String = "",
    val name: String = "",
    val photo: String = "",
    val district: String = "",
    val headline: String = "",
    val about: String = "",
    val experience: List<CvExp> = emptyList(),
    val education: List<CvEdu> = emptyList(),
    val skills: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val certs: List<CvCert> = emptyList(),
    val openToWork: Boolean = false,
    val wantCategories: List<String> = emptyList(),
    /** none: কাউকে না, employers: যেখানে আবেদন করেছেন, all: শেয়ার লিংকেও */
    val contactVisibility: String = "employers",
    val public: Boolean = false,
    val complete: Boolean = false,
    val phone: String = "",
    val email: String = "",
    /** সিভি কতটা পূরণ (%), মোট অভিজ্ঞতা (বছর), সর্বোচ্চ শিক্ষার স্তর — সার্ভার হিসাব করে */
    val completeness: Int = 0,
    val experienceYears: Int = 0,
    val eduLevel: String = "none",
    /** বাইরের লিংক (LinkedIn, Facebook, ওয়েবসাইট) */
    val links: List<String> = emptyList(),
    /** শেয়ার লিংকে ডোনার ব্যাজ (রক্তের গ্রুপ) দেখাবে কি না */
    val showDonor: Boolean = false,
    val bloodGroup: String = "",
    val donationCount: Int = 0,
    /** পেশাদার ব্যাজ (doctor/nurse…) আর যাচাই করা নিয়োগকর্তা */
    val badge: String = "",
    val employerVerified: Boolean = false,
    /** নিজের সিভিতে কোন অংশ বাকি: photo, headline, about, experience, education, skills, languages */
    val missing: List<String> = emptyList(),
)

object CvApi {
    private suspend fun call(action: String, body: JSONObject = JSONObject()): JSONObject =
        ServerApi.post("/api/community", body.put("action", action))

    private fun JSONObject.yearOrNull(k: String): Int? = if (isNull(k) || !has(k)) null else optInt(k).takeIf { it > 0 }

    fun parse(o: JSONObject): Cv {
        fun <T> arr(k: String, f: (JSONObject) -> T): List<T> {
            val a = o.optJSONArray(k) ?: return emptyList()
            return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(f) }
        }
        return Cv(
            o.str("uid"), o.str("name"), o.str("photo"), o.str("district"), o.str("headline"), o.str("about"),
            arr("experience") { CvExp(it.str("title"), it.str("org"), it.str("from"), it.str("to"), it.optBoolean("current"), it.str("details"), it.optBoolean("verified")) },
            arr("education") { CvEdu(it.str("degree"), it.str("institute"), it.yearOrNull("year"), it.str("result"), it.str("level")) },
            o.optJSONArray("skills").strings(), o.optJSONArray("languages").strings(),
            arr("certs") { CvCert(it.str("name"), it.str("issuer"), it.yearOrNull("year"), it.str("link")) },
            o.optBoolean("openToWork"), o.optJSONArray("wantCategories").strings(),
            o.str("contactVisibility").ifBlank { "employers" }, o.optBoolean("public"), o.optBoolean("complete"),
            o.str("phone"), o.str("email"),
            o.optInt("completeness"), o.optInt("experienceYears"), o.str("eduLevel").ifBlank { "none" },
            o.optJSONArray("links").strings(), o.optBoolean("showDonor"), o.str("bloodGroup"), o.optInt("donationCount"),
            o.str("badge"), o.optBoolean("employerVerified"), o.optJSONArray("missing").strings(),
        )
    }

    suspend fun mine(): Cv = parse(call("cv_get").getJSONObject("cv"))

    suspend fun save(cv: Cv): Cv {
        val b = JSONObject()
            .put("headline", cv.headline).put("about", cv.about)
            .put("experience", JSONArray(cv.experience.map {
                JSONObject().put("title", it.title).put("org", it.org).put("from", it.from).put("to", if (it.current) "" else it.to)
                    .put("current", it.current).put("details", it.details)
            }))
            .put("education", JSONArray(cv.education.map {
                JSONObject().put("degree", it.degree).put("institute", it.institute).put("year", it.year ?: JSONObject.NULL).put("result", it.result).put("level", it.level)
            }))
            .put("certs", JSONArray(cv.certs.map { JSONObject().put("name", it.name).put("issuer", it.issuer).put("year", it.year ?: JSONObject.NULL).put("link", it.link) }))
            .put("links", JSONArray(cv.links)).put("showDonor", cv.showDonor)
            .put("skills", JSONArray(cv.skills)).put("languages", JSONArray(cv.languages))
            .put("contactVisibility", cv.contactVisibility).put("public", cv.public).put("openToWork", cv.openToWork)
            .put("wantCategories", JSONArray(cv.wantCategories))
        return parse(call("cv_save", b).getJSONObject("cv"))
    }

    fun shareUrl(uid: String) = ServerApi.BASE_URL + "/cv/" + uid

    /** "2021-03" → "মার্চ ২০২১" */
    fun ym(v: String): String {
        val p = v.split("-")
        val y = p.getOrNull(0)?.toIntOrNull() ?: return ""
        val m = p.getOrNull(1)?.toIntOrNull() ?: return num(y)
        return monthName(m) + " " + num(y)
    }

    private val MONTHS_BN = listOf("জানুয়ারি", "ফেব্রুয়ারি", "মার্চ", "এপ্রিল", "মে", "জুন", "জুলাই", "আগস্ট", "সেপ্টেম্বর", "অক্টোবর", "নভেম্বর", "ডিসেম্বর")
    private val MONTHS_EN = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    fun monthName(m: Int): String = if (m in 1..12) tr(MONTHS_EN[m - 1], MONTHS_BN[m - 1]) else ""

    /** সার্ভারের রূপ "2021-03" (অঙ্ক সবসময় ইংরেজি) */
    fun ymCode(year: Int, month: Int): String = String.format(java.util.Locale.US, "%04d-%02d", year, month)

    fun period(e: CvExp): String {
        val from = ym(e.from)
        val to = if (e.current) tr("Present", "এখন পর্যন্ত") else ym(e.to)
        return listOf(from, to).filter { it.isNotBlank() }.joinToString(" – ")
    }
}

// ---------------- দেখানো ----------------

@Composable
fun CvHeader(cv: Cv, small: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        val bmp = remember(cv.photo) { Photo.decodeAvatar(cv.photo) }
        val sz = if (small) 44.dp else 64.dp
        if (bmp != null) {
            Image(bmp, null, Modifier.size(sz).clip(CircleShape), contentScale = ContentScale.Crop)
        } else {
            Box(Modifier.size(sz).clip(CircleShape).background(cs.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                AppIcon(R.drawable.ic_person_filled, null, Modifier.size(sz / 2), cs.onSurfaceVariant)
            }
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(cv.name, style = if (small) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (cv.headline.isNotBlank()) Text(cv.headline, style = MaterialTheme.typography.bodyMedium)
            if (cv.district.isNotBlank()) Text(Districts.label(cv.district), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            if (cv.openToWork && !small) Tag(tr("Open to work", "কাজ খুঁজছেন"), cs.tertiaryContainer, cs.onTertiaryContainer)
        }
    }
}

/** সিভির ভেতরের অংশ (নিজের সিভি আর নিয়োগকর্তার দেখা — দুই জায়গাতেই) */
@Composable
fun CvBody(cv: Cv) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (cv.about.isNotBlank()) {
            SectionTitle(tr("About", "নিজের সম্পর্কে"))
            Text(cv.about, style = MaterialTheme.typography.bodyMedium)
        }
        if (cv.experience.isNotEmpty()) {
            SectionTitle(tr("Experience", "অভিজ্ঞতা"))
            cv.experience.forEach { e ->
                Column {
                    Text(e.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(e.org, style = MaterialTheme.typography.bodyMedium)
                        if (e.verified) {
                            Spacer(Modifier.size(6.dp))
                            AppIcon(R.drawable.ic_verified, null, Modifier.size(16.dp), cs.primary)
                            Text(tr(" Verified", " যাচাই করা"), style = MaterialTheme.typography.labelSmall, color = cs.primary)
                        }
                    }
                    val p = CvApi.period(e)
                    if (p.isNotBlank()) Text(p, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    if (e.details.isNotBlank()) Text(e.details, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (cv.education.isNotEmpty()) {
            SectionTitle(tr("Education", "শিক্ষা"))
            cv.education.forEach { e ->
                Column {
                    Text(e.degree, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text(e.institute, style = MaterialTheme.typography.bodyMedium)
                    val line = listOfNotNull(e.year?.let { num(it) }, e.result.ifBlank { null }).joinToString(" · ")
                    if (line.isNotBlank()) Text(line, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
        }
        if (cv.skills.isNotEmpty()) {
            SectionTitle(tr("Skills", "দক্ষতা"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                cv.skills.forEach { Tag(it, cs.secondaryContainer, cs.onSecondaryContainer) }
            }
        }
        if (cv.languages.isNotEmpty()) {
            SectionTitle(tr("Languages", "ভাষা"))
            Text(cv.languages.joinToString(", "), style = MaterialTheme.typography.bodyMedium)
        }
        if (cv.certs.isNotEmpty()) {
            SectionTitle(tr("Training & certificates", "প্রশিক্ষণ ও সনদ"))
            cv.certs.forEach { c ->
                Text(listOfNotNull(c.name, c.issuer.ifBlank { null }, c.year?.let { num(it) }).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                if (c.link.isNotBlank()) LinkText(c.link)
            }
        }
        if (cv.links.isNotEmpty()) {
            SectionTitle(tr("Links", "লিংক"))
            cv.links.forEach { LinkText(it) }
        }
        if (cv.wantCategories.isNotEmpty()) {
            SectionTitle(tr("Looking for", "যে কাজ খুঁজছেন"))
            Text(cv.wantCategories.joinToString(", ") { WorkText.category(it) }, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** লিংক (চাপলে ব্রাউজারে খোলে); দেখায় https:// ছাড়া */
@Composable
fun LinkText(url: String) {
    val context = LocalContext.current
    Text(
        url.removePrefix("https://").removePrefix("http://").removePrefix("www."),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable { openUrl(context, url) },
    )
}

/** সিভির PDF সেভ আর শেয়ার (লিংক + QR) — প্রোফাইল পাতায় */
@Composable
fun CvShareActions(cv: Cv, onMakePublic: () -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pdfBusy by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) {
            scope.launch {
                pdfBusy = true
                runCatching { withContext(Dispatchers.IO) { CvPdf.write(context, uri, cv) } }
                    .onSuccess { onMessage(tr("PDF saved", "PDF সেভ হয়েছে")) }
                    .onFailure { onMessage(tr("Couldn't save the PDF", "PDF সেভ হয়নি")) }
                pdfBusy = false
            }
        }
    }
    SmallAction(tr("Share", "শেয়ার"), R.drawable.ic_share, { sharing = true })
    SmallAction(tr("PDF", "PDF"), R.drawable.ic_list_alt, {
        savePdf.launch("CV-" + cv.name.replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').ifBlank { "Dorkar" } + ".pdf")
    }, busy = pdfBusy)
    if (sharing) {
        val url = CvApi.shareUrl(cv.uid)
        val qr = remember(url) { Qr.bitmap(url, 640) }
        AlertDialog(
            onDismissRequest = { sharing = false },
            title = { Text(tr("Share your profile", "প্রোফাইল শেয়ার করুন")) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    if (!cv.public) {
                        SmallNote(tr("Your profile link is off. Turn it on so anyone with the link or QR can see your CV.", "প্রোফাইলের লিংক এখন বন্ধ। চালু করলে লিংক বা QR যাঁর কাছে থাকবে তিনিই সিভি দেখতে পারবেন।"))
                        Button(onClick = { sharing = false; onMakePublic() }) { Text(tr("Turn on link", "লিংক চালু করুন")) }
                    } else {
                        qr?.let { Image(it, tr("QR code of your profile", "প্রোফাইলের QR কোড"), Modifier.size(220.dp)) }
                        Text(url.removePrefix("https://"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SmallNote(tr("Others can scan the QR with their phone camera.", "অন্যরা ফোনের ক্যামেরায় QR স্ক্যান করে দেখতে পারবেন।"))
                    }
                }
            },
            confirmButton = {
                if (cv.public) {
                    TextButton(onClick = {
                        sharing = false
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, cv.name + tr(" — profile on Dorkar: ", " — Dorkar-এ প্রোফাইল: ") + url)
                        }
                        runCatching { context.startActivity(Intent.createChooser(send, tr("Share profile", "প্রোফাইল শেয়ার করুন"))) }
                    }) { Text(tr("Share link", "লিংক পাঠান")) }
                }
            },
            dismissButton = { TextButton(onClick = { sharing = false }) { Text(tr("Close", "বন্ধ")) } },
        )
    }
}

/** QR কোড ছবি (zxing) */
object Qr {
    fun bitmap(text: String, size: Int): androidx.compose.ui.graphics.ImageBitmap? = runCatching {
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size, mapOf(com.google.zxing.EncodeHintType.MARGIN to 1))
        val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
    }.getOrNull()
}

// ---------------- বদলানো ----------------

@Composable
fun CvEditScreen(onBack: () -> Unit, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    var base by remember { mutableStateOf<Cv?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var headline by remember { mutableStateOf("") }
    var about by remember { mutableStateOf("") }
    var exp by remember { mutableStateOf(listOf<CvExp>()) }
    var edu by remember { mutableStateOf(listOf<CvEdu>()) }
    var certs by remember { mutableStateOf(listOf<CvCert>()) }
    var skills by remember { mutableStateOf(listOf<String>()) }
    var langs by remember { mutableStateOf(listOf<String>()) }
    var openToWork by remember { mutableStateOf(true) }
    var want by remember { mutableStateOf(setOf<String>()) }
    var visibility by remember { mutableStateOf("employers") }
    var public by remember { mutableStateOf(false) }
    /** খোলা ফর্ম: -1 নেই, নইলে তালিকার স্থান (size = নতুন) */
    var editExp by remember { mutableIntStateOf(-1) }
    var editEdu by remember { mutableIntStateOf(-1) }
    var editCert by remember { mutableIntStateOf(-1) }
    LaunchedEffect(Unit) {
        runCatching { CvApi.mine() }.onSuccess { c ->
            base = c
            headline = c.headline; about = c.about; exp = c.experience; edu = c.education; certs = c.certs
            skills = c.skills; langs = c.languages; openToWork = c.openToWork || c.headline.isBlank()
            want = c.wantCategories.toSet(); visibility = c.contactVisibility; public = c.public
        }.onFailure { errorMsg = it.message }
    }
    Scaffold(topBar = { BackTopBar(tr("My CV", "আমার সিভি"), onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (base == null) {
                ErrorText(errorMsg)
                if (errorMsg == null) LoadingBox()
                return@Column
            }
            SmallNote(tr("Name, photo and phone come from your profile.", "নাম, ছবি আর ফোন প্রোফাইল থেকে নেওয়া হয়।"))
            OutlinedTextField(headline, { headline = it.take(100) }, label = { Text(tr("Headline (e.g. Accountant, 3 years experience)", "শিরোনাম (যেমন হিসাবরক্ষক, ৩ বছরের অভিজ্ঞতা)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(about, { about = it.take(1000) }, label = { Text(tr("About you (optional)", "নিজের সম্পর্কে (ঐচ্ছিক)")) }, minLines = 3, modifier = Modifier.fillMaxWidth())

            CvSection(tr("Experience", "অভিজ্ঞতা"), tr("Add experience", "অভিজ্ঞতা যোগ করুন"), exp.size < 15, { editExp = exp.size }) {
                exp.forEachIndexed { i, e -> CvRow(e.title + " · " + e.org, CvApi.period(e), { editExp = i }) { exp = exp.filterIndexed { k, _ -> k != i } } }
            }
            CvSection(tr("Education", "শিক্ষা"), tr("Add education", "শিক্ষা যোগ করুন"), edu.size < 10, { editEdu = edu.size }) {
                edu.forEachIndexed { i, e -> CvRow(e.degree + " · " + e.institute, listOfNotNull(e.year?.let { num(it) }, e.result.ifBlank { null }).joinToString(" · "), { editEdu = i }) { edu = edu.filterIndexed { k, _ -> k != i } } }
            }
            WordsField(tr("Skills (e.g. MS Excel, driving)", "দক্ষতা (যেমন এমএস এক্সেল, গাড়ি চালানো)"), skills, max = 30) { skills = it }
            WordsField(tr("Languages", "ভাষা"), langs, max = 10, maxLen = 30) { langs = it }
            CvSection(tr("Training & certificates", "প্রশিক্ষণ ও সনদ"), tr("Add", "যোগ করুন"), certs.size < 20, { editCert = certs.size }) {
                certs.forEachIndexed { i, c -> CvRow(c.name, listOfNotNull(c.issuer.ifBlank { null }, c.year?.let { num(it) }).joinToString(" · "), { editCert = i }) { certs = certs.filterIndexed { k, _ -> k != i } } }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("I'm looking for work", "আমি কাজ খুঁজছি"), modifier = Modifier.weight(1f))
                Switch(checked = openToWork, onCheckedChange = { openToWork = it })
            }
            if (openToWork) {
                Text(tr("Fields you want (up to 5)", "যে ধরনের কাজ চান (৫টা পর্যন্ত)"), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WorkText.CATEGORIES.forEach { c ->
                        FilterChip(
                            selected = c in want,
                            onClick = { want = if (c in want) want - c else if (want.size < 5) want + c else want },
                            label = { Text(WorkText.category(c)) },
                        )
                    }
                }
            }

            Text(tr("Who sees your phone and email", "ফোন আর ইমেইল কে দেখবে"), style = MaterialTheme.typography.labelLarge)
            listOf(
                "employers" to tr("Only employers I apply to", "শুধু যেখানে আবেদন করি সেই নিয়োগকর্তা"),
                "all" to tr("Also on my share link", "শেয়ার লিংকেও"),
                "none" to tr("Nobody (employers send a notification instead)", "কেউ না (নিয়োগকর্তা নোটিফিকেশনে জানাবেন)"),
            ).forEach { (k, label) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = visibility == k, onClick = { visibility = k })
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("Anyone with the link", "লিংক দিয়ে সবাই দেখবে"))
                    SmallNote(tr("Turn on to share your CV as a web link.", "চালু করলে সিভি ওয়েব লিংক হিসেবে শেয়ার করা যাবে।"))
                }
                Switch(checked = public, onCheckedChange = { public = it })
            }
            ErrorText(errorMsg)
            Button(
                onClick = {
                    errorMsg = when {
                        headline.isBlank() -> tr("Write a headline", "শিরোনাম লিখুন")
                        exp.isEmpty() && edu.isEmpty() -> tr("Add your experience or education", "অভিজ্ঞতা বা শিক্ষা যোগ করুন")
                        else -> null
                    }
                    if (errorMsg != null) return@Button
                    scope.launch {
                        busy = true
                        runCatching {
                            CvApi.save(
                                Cv(
                                    headline = headline.trim(), about = about.trim(), experience = exp, education = edu, skills = skills, languages = langs,
                                    certs = certs, openToWork = openToWork, wantCategories = if (openToWork) want.toList() else emptyList(),
                                    contactVisibility = visibility, public = public,
                                ),
                            )
                        }.onSuccess { onSaved() }.onFailure { errorMsg = it.message }
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { ButtonContent(busy, tr("Save CV", "সিভি সেভ করুন")) }
        }
    }

    if (editExp >= 0) {
        val cur = exp.getOrNull(editExp)
        ExpDialog(cur, onDismiss = { editExp = -1 }) { e ->
            exp = if (cur == null) exp + e else exp.mapIndexed { k, x -> if (k == editExp) e else x }
            editExp = -1
        }
    }
    if (editEdu >= 0) {
        val cur = edu.getOrNull(editEdu)
        EduDialog(cur, onDismiss = { editEdu = -1 }) { e ->
            edu = if (cur == null) edu + e else edu.mapIndexed { k, x -> if (k == editEdu) e else x }
            editEdu = -1
        }
    }
    if (editCert >= 0) {
        val cur = certs.getOrNull(editCert)
        CertDialog(cur, onDismiss = { editCert = -1 }) { e ->
            certs = if (cur == null) certs + e else certs.mapIndexed { k, x -> if (k == editCert) e else x }
            editCert = -1
        }
    }
}

@Composable
private fun CvSection(title: String, addLabel: String, canAdd: Boolean, onAdd: () -> Unit, rows: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        rows()
        if (canAdd) TextButton(onClick = onAdd) { ButtonContent(false, addLabel, R.drawable.ic_add) }
    }
}

@Composable
private fun CvRow(title: String, sub: String, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        androidx.compose.material3.IconButton(onClick = onEdit) { AppIcon(R.drawable.ic_edit, tr("Edit", "বদলান"), Modifier.size(20.dp)) }
        androidx.compose.material3.IconButton(onClick = onDelete) { AppIcon(R.drawable.ic_delete, tr("Remove", "সরান"), Modifier.size(20.dp)) }
    }
}

private fun yearOf(s: String): Int? = s.toIntOrNull()?.takeIf { it in 1950..(java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) + 6) }

@Composable
private fun ExpDialog(cur: CvExp?, onDismiss: () -> Unit, onDone: (CvExp) -> Unit) {
    var title by remember { mutableStateOf(cur?.title ?: "") }
    var org by remember { mutableStateOf(cur?.org ?: "") }
    var from by remember { mutableStateOf(cur?.from ?: "") }
    var to by remember { mutableStateOf(cur?.to ?: "") }
    var current by remember { mutableStateOf(cur?.current ?: false) }
    var details by remember { mutableStateOf(cur?.details ?: "") }
    var pick by remember { mutableIntStateOf(0) }
    var err by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = tr("Experience", "অভিজ্ঞতা"),
        onDismiss = onDismiss,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it.take(80) }, label = { Text(tr("Position", "পদ")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(org, { org = it.take(100) }, label = { Text(tr("Organisation", "প্রতিষ্ঠান")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pick = 1 }) { Text(tr("From ", "শুরু ") + (CvApi.ym(from).ifBlank { "—" })) }
                    if (!current) OutlinedButton(onClick = { pick = 2 }) { Text(tr("To ", "শেষ ") + (CvApi.ym(to).ifBlank { "—" })) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("I work here now", "এখনো এখানে কাজ করছি"), modifier = Modifier.weight(1f))
                    Switch(checked = current, onCheckedChange = { current = it })
                }
                OutlinedTextField(details, { details = it.take(500) }, label = { Text(tr("What you did (optional)", "কী কাজ করতেন (ঐচ্ছিক)")) }, minLines = 2, modifier = Modifier.fillMaxWidth())
                ErrorText(err)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                err = when {
                    title.isBlank() || org.isBlank() -> tr("Write the position and organisation", "পদ আর প্রতিষ্ঠান লিখুন")
                    !current && from.isNotBlank() && to.isNotBlank() && to < from -> tr("End is before start", "শেষের তারিখ শুরুর আগে")
                    else -> null
                }
                if (err == null) onDone(CvExp(title.trim(), org.trim(), from, if (current) "" else to, current, details.trim()))
            }) { Text(tr("OK", "ঠিক আছে")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "বাতিল")) } },
    )
    if (pick != 0) {
        MonthYearDialog(if (pick == 1) from else to, onDismiss = { pick = 0 }) { v ->
            if (pick == 1) from = v else to = v
            pick = 0
        }
    }
}

/** মাস আর বছর বাছা ("2021-03") */
@Composable
private fun MonthYearDialog(cur: String, onDismiss: () -> Unit, onPicked: (String) -> Unit) {
    val now = java.util.Calendar.getInstance()
    val thisYear = now.get(java.util.Calendar.YEAR)
    var year by remember { mutableIntStateOf(cur.take(4).toIntOrNull() ?: thisYear) }
    FormDialog(
        title = tr("Month and year", "মাস আর বছর"),
        onDismiss = onDismiss,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { if (year > 1960) year-- }) { Text("‹", style = MaterialTheme.typography.titleLarge) }
                    Text(num(year), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { if (year < thisYear) year++ }) { Text("›", style = MaterialTheme.typography.titleLarge) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..12).forEach { m ->
                        val future = year == thisYear && m > now.get(java.util.Calendar.MONTH) + 1
                        FilterChip(
                            selected = cur == CvApi.ymCode(year, m),
                            enabled = !future,
                            onClick = { onPicked(CvApi.ymCode(year, m)) },
                            label = { Text(CvApi.monthName(m)) },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            Row {
                if (cur.isNotBlank()) TextButton(onClick = { onPicked("") }) { Text(tr("Clear", "মুছুন")) }
                TextButton(onClick = onDismiss) { Text(tr("Cancel", "বাতিল")) }
            }
        },
    )
}

@Composable
private fun EduDialog(cur: CvEdu?, onDismiss: () -> Unit, onDone: (CvEdu) -> Unit) {
    var degree by remember { mutableStateOf(cur?.degree ?: "") }
    var institute by remember { mutableStateOf(cur?.institute ?: "") }
    var year by remember { mutableStateOf(cur?.year?.toString() ?: "") }
    var result by remember { mutableStateOf(cur?.result ?: "") }
    var level by remember { mutableStateOf(cur?.level ?: "") }
    var err by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = tr("Education", "শিক্ষা"),
        onDismiss = onDismiss,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(degree, { degree = it.take(100) }, label = { Text(tr("Degree (e.g. HSC, BBA)", "পরীক্ষা/ডিগ্রি (যেমন এইচএসসি, বিবিএ)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(institute, { institute = it.take(120) }, label = { Text(tr("Institution", "প্রতিষ্ঠান")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(year, { year = it.filter(Char::isDigit).take(4) }, label = { Text(tr("Year", "সাল")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(result, { result = it.take(40) }, label = { Text(tr("Result", "ফলাফল")) }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Text(tr("Level", "স্তর"), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WorkText.EDUCATION.drop(1).forEach { l -> FilterChip(selected = level == l, onClick = { level = if (level == l) "" else l }, label = { Text(WorkText.education(l)) }) }
                }
                ErrorText(err)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                err = when {
                    degree.isBlank() || institute.isBlank() -> tr("Write the degree and institution", "ডিগ্রি আর প্রতিষ্ঠান লিখুন")
                    year.isNotBlank() && yearOf(year) == null -> tr("Write a correct year", "সঠিক সাল লিখুন")
                    else -> null
                }
                if (err == null) onDone(CvEdu(degree.trim(), institute.trim(), yearOf(year), result.trim(), level))
            }) { Text(tr("OK", "ঠিক আছে")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "বাতিল")) } },
    )
}

@Composable
private fun CertDialog(cur: CvCert?, onDismiss: () -> Unit, onDone: (CvCert) -> Unit) {
    var name by remember { mutableStateOf(cur?.name ?: "") }
    var issuer by remember { mutableStateOf(cur?.issuer ?: "") }
    var year by remember { mutableStateOf(cur?.year?.toString() ?: "") }
    var err by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = tr("Training or certificate", "প্রশিক্ষণ বা সনদ"),
        onDismiss = onDismiss,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(100) }, label = { Text(tr("Name", "নাম")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(issuer, { issuer = it.take(100) }, label = { Text(tr("Given by (optional)", "যে প্রতিষ্ঠান দিয়েছে (ঐচ্ছিক)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(year, { year = it.filter(Char::isDigit).take(4) }, label = { Text(tr("Year (optional)", "সাল (ঐচ্ছিক)")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                ErrorText(err)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                err = when {
                    name.isBlank() -> tr("Write the name", "নাম লিখুন")
                    year.isNotBlank() && yearOf(year) == null -> tr("Write a correct year", "সঠিক সাল লিখুন")
                    else -> null
                }
                if (err == null) onDone(CvCert(name.trim(), issuer.trim(), yearOf(year)))
            }) { Text(tr("OK", "ঠিক আছে")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "বাতিল")) } },
    )
}

// ---------------- PDF ----------------

/** A4 পাতায় সিভি (বাংলা লেখাও ঠিকমতো আসে — ফোনের নিজের ফন্টে আঁকা) */
object CvPdf {
    private const val W = 595
    private const val H = 842
    private const val M = 44f

    fun write(context: Context, uri: Uri, cv: Cv) {
        val doc = PdfDocument()
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f
        val inner = (W - 2 * M).toInt()
        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            y = M
        }
        fun paint(size: Float, bold: Boolean = false, color: Int = 0xFF1B1B1F.toInt()) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
        fun layout(text: String, p: TextPaint, width: Int = inner): StaticLayout =
            StaticLayout.Builder.obtain(text, 0, text.length, p, width).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(2f, 1f).build()
        /** লেখা লাইন ধরে আঁকা; পাতা শেষ হলে পরের পাতায় */
        fun text(t: String, p: TextPaint, gapAfter: Float = 4f, x: Float = M, width: Int = inner) {
            if (t.isBlank()) return
            val l = layout(t, p, width)
            for (i in 0 until l.lineCount) {
                val top = l.getLineTop(i)
                val bottom = l.getLineBottom(i)
                if (y + (bottom - top) > H - M) newPage()
                val c = page!!.canvas
                c.save()
                c.translate(x, y - top)
                c.clipRect(0, top, width, bottom)
                l.draw(c)
                c.restore()
                y += (bottom - top)
            }
            y += gapAfter
        }
        val accent = 0xFF006874.toInt()
        val grey = 0xFF5F6368.toInt()
        fun heading(t: String) {
            if (y + 40 > H - M) newPage()
            y += 10f
            text(t, paint(13f, true, accent), 2f)
            val c = page!!.canvas
            c.drawLine(M, y, W - M, y, Paint().apply { color = 0xFFCFD8DC.toInt(); strokeWidth = 0.8f })
            y += 6f
        }

        newPage()
        // উপরে: ছবি, নাম, শিরোনাম, যোগাযোগ
        val photo = runCatching {
            if (cv.photo.isBlank()) null else Base64.decode(cv.photo, Base64.DEFAULT).let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        }.getOrNull()
        val textX = if (photo != null) M + 84f else M
        val textW = (W - M - textX).toInt()
        val startY = y
        if (photo != null) drawRound(page!!.canvas, photo, RectF(M, y, M + 70f, y + 70f))
        text(cv.name, paint(22f, true), 2f, textX, textW)
        text(cv.headline, paint(12.5f, false, accent), 2f, textX, textW)
        val contact = listOf(cv.phone, cv.email, Districts.label(cv.district).takeIf { cv.district.isNotBlank() } ?: "").filter { it.isNotBlank() }.joinToString("  ·  ")
        text(contact, paint(10.5f, false, grey), 0f, textX, textW)
        if (photo != null) y = maxOf(y, startY + 74f)

        val body = paint(10.5f)
        val bold = paint(11f, true)
        val small = paint(9.5f, false, grey)
        if (cv.about.isNotBlank()) {
            heading(tr("About", "নিজের সম্পর্কে"))
            text(cv.about, body)
        }
        if (cv.experience.isNotEmpty()) {
            heading(tr("Experience", "অভিজ্ঞতা"))
            cv.experience.forEach { e ->
                text(e.title + " — " + e.org, bold, 1f)
                text(CvApi.period(e), small, 1f)
                text(e.details, body)
                y += 4f
            }
        }
        if (cv.education.isNotEmpty()) {
            heading(tr("Education", "শিক্ষা"))
            cv.education.forEach { e ->
                text(e.degree + " — " + e.institute, bold, 1f)
                text(listOfNotNull(e.year?.let { num(it) }, e.result.ifBlank { null }).joinToString(" · "), small)
                y += 3f
            }
        }
        if (cv.skills.isNotEmpty()) {
            heading(tr("Skills", "দক্ষতা"))
            text(cv.skills.joinToString("  ·  "), body)
        }
        if (cv.languages.isNotEmpty()) {
            heading(tr("Languages", "ভাষা"))
            text(cv.languages.joinToString(", "), body)
        }
        if (cv.certs.isNotEmpty()) {
            heading(tr("Training & certificates", "প্রশিক্ষণ ও সনদ"))
            cv.certs.forEach { c -> text(listOfNotNull(c.name, c.issuer.ifBlank { null }, c.year?.let { num(it) }).joinToString(" · "), body, 2f) }
        }
        if (cv.wantCategories.isNotEmpty()) {
            heading(tr("Looking for", "যে কাজ খুঁজছেন"))
            text(cv.wantCategories.joinToString(", ") { WorkText.category(it) }, body)
        }
        page?.let { doc.finishPage(it) }
        try {
            context.contentResolver.openOutputStream(uri)?.use { doc.writeTo(it) } ?: error("no stream")
        } finally {
            doc.close()
        }
    }

    private fun drawRound(c: Canvas, bmp: Bitmap, r: RectF) {
        val shader = android.graphics.BitmapShader(bmp, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
        val scale = maxOf(r.width() / bmp.width, r.height() / bmp.height)
        val m = android.graphics.Matrix()
        m.setScale(scale, scale)
        m.postTranslate(r.left + (r.width() - bmp.width * scale) / 2, r.top + (r.height() - bmp.height * scale) / 2)
        shader.setLocalMatrix(m)
        c.drawOval(r, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })
    }
}
