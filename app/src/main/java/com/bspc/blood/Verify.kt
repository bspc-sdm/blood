package com.bspc.blood

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * কাগজের ছবি দিয়ে যাচাই: অ্যাম্বুলেন্স, ফার্মেসি, ব্লাড ব্যাংক, পেশাদার ব্যাজ।
 * কোন কাগজ লাগে তা সার্ভারের lib/verify.js-এর DOCS-এর সাথে মেলানো।
 */
data class DocSpec(val type: String, val label: String, val hint: String, val required: Boolean)

object VerifyDocs {
    fun ambulance() = listOf(
        DocSpec("reg", tr("Registration certificate or smart card", "রেজিস্ট্রেশন সনদ বা স্মার্ট কার্ড"), tr("BRTA paper — vehicle number and type readable", "BRTA-র কাগজ — গাড়ির নম্বর আর ধরন পরিষ্কার পড়া যায়"), true),
        DocSpec("fitness", tr("Fitness certificate", "ফিটনেস সনদ"), tr("Expiry date readable", "মেয়াদের তারিখ পড়া যায় এমন"), true),
        DocSpec("front", tr("Photo of the vehicle (with number plate)", "গাড়ির ছবি (নম্বর প্লেটসহ)"), tr("From front or back, plate readable", "সামনে বা পেছন থেকে, প্লেট পড়া যায়"), true),
    )

    fun pharmacy() = listOf(
        DocSpec("license", tr("Drug licence", "ড্রাগ লাইসেন্স"), tr("Whole paper, number and expiry readable", "পুরো কাগজ, নম্বর আর মেয়াদ পড়া যায়"), true),
        DocSpec("front", tr("Shop front (with signboard)", "দোকানের সামনের ছবি (সাইনবোর্ডসহ)"), tr("Shop name readable", "দোকানের নাম পড়া যায়"), true),
        DocSpec("pharmacist", tr("Pharmacist registration certificate", "ফার্মাসিস্টের নিবন্ধন সনদ"), tr("If you have it", "থাকলে দিন"), false),
    )

    fun bloodBank(kind: String) = listOf(
        DocSpec("front", tr("Photo of the blood bank (signboard or entrance)", "ব্লাড ব্যাংকের ছবি (সাইনবোর্ড বা প্রবেশপথ)"), tr("Name readable", "নাম পড়া যায়"), true),
        DocSpec("license", tr("DGHS blood bank licence", "স্বাস্থ্য অধিদপ্তরের ব্লাড ব্যাংক লাইসেন্স"), tr("Needed for private blood banks", "বেসরকারি হলে লাগবে"), kind == "private"),
        DocSpec("authority", tr("Letter on the hospital's or organisation's pad", "হাসপাতাল বা সংগঠনের প্যাডে প্রত্যয়ন"), tr("For government hospitals or organisations", "সরকারি হাসপাতাল বা সংগঠন হলে দিন"), false),
    )

    fun badge(type: String) = when (type) {
        "doctor" -> listOf(
            DocSpec("cert", tr("BMDC registration certificate", "BMDC নিবন্ধন সনদ"), tr("Optional — the number is checked online", "থাকলে দিন — নম্বর অনলাইনে মিলিয়ে দেখা হয়"), false),
            DocSpec("idcard", tr("Workplace ID card", "কর্মস্থলের পরিচয়পত্র"), tr("If you have it", "থাকলে দিন"), false),
        )
        "nurse" -> listOf(
            DocSpec("cert", tr("BNMC registration certificate", "নার্সিং ও মিডওয়াইফারি কাউন্সিলের (BNMC) নিবন্ধন সনদ"), tr("Number and name readable", "নম্বর আর নাম পড়া যায়"), true),
            DocSpec("idcard", tr("Workplace ID card", "কর্মস্থলের পরিচয়পত্র"), tr("If you have it", "থাকলে দিন"), false),
        )
        else -> listOf(
            DocSpec("cert", tr("Certificate or degree", "পেশার সনদ বা ডিগ্রির কাগজ"), tr("Name and institution readable", "নাম আর প্রতিষ্ঠান পড়া যায়"), true),
            DocSpec("idcard", tr("Workplace ID card", "কর্মস্থলের পরিচয়পত্র"), tr("If you have it", "থাকলে দিন"), false),
        )
    }

    /** দরকারি ছবির কোনটা বাকি থাকলে বার্তা, নইলে null */
    fun missing(specs: List<DocSpec>, photos: Map<String, String>): String? {
        val miss = specs.filter { it.required && photos[it.type].isNullOrBlank() }
        return if (miss.isEmpty()) null else tr("Add photos of: ", "এই কাগজের ছবি দিন: ") + miss.joinToString(", ") { it.label }
    }

    fun json(photos: Map<String, String>): JSONObject = JSONObject().apply { photos.forEach { (k, v) -> if (v.isNotBlank()) put(k, v) } }

    /** পরে ছবি দেওয়া (অ্যাডমিন চাইলে) */
    suspend fun upload(kind: String, id: String, photos: Map<String, String>) {
        ServerApi.post("/api/community", JSONObject().put("action", "verify_docs_upload").put("kind", kind).put("id", id).put("docs", json(photos)))
    }
}

/**
 * কাগজের ছবি বাছাই: প্রতিটা কাগজের পাশে "ছবি দিন" বাটন আর ছোট ছবি।
 * photos: type → JPEG base64 (ছোট করা, ~২৮০ KB পর্যন্ত)
 */
@Composable
fun DocPhotosField(specs: List<DocSpec>, photos: Map<String, String>, onChange: (Map<String, String>) -> Unit, optionalNote: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val type = picking
        picking = null
        if (uri != null && type != null) {
            scope.launch {
                busy = type
                runCatching { Photo.toDocumentBase64(context, uri) }
                    .onSuccess { onChange(photos + (type to it)); errorMsg = null }
                    .onFailure { errorMsg = it.message }
                busy = null
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("Document photos", "কাগজের ছবি"), style = MaterialTheme.typography.labelLarge)
        SmallNote(optionalNote ?: tr("The Lifolo team checks these before listing. Take a clear photo of the whole paper.", "তালিকায় আসার আগে Lifolo টিম এগুলো দেখে মিলিয়ে নেয়। পুরো কাগজের পরিষ্কার ছবি দিন।"))
        specs.forEach { d ->
            val img = photos[d.type]
            Row(verticalAlignment = Alignment.CenterVertically) {
                val bmp = remember(img) { Photo.decodeImage(img) }
                if (bmp != null) {
                    Image(bmp, null, Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(d.label + if (d.required) " *" else "", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(d.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(
                    onClick = { picking = d.type; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    enabled = busy == null,
                ) { Text(if (busy == d.type) "…" else if (img == null) tr("Add photo", "ছবি দিন") else tr("Change", "বদলান")) }
            }
        }
        ErrorText(errorMsg)
    }
}

/**
 * মালিকের কার্ডে: অ্যাডমিন ছবি চাইলে বা দরকারি ছবি বাকি থাকলে — কারণসহ "ছবি দিন"।
 */
@Composable
fun DocsNeededNotice(kind: String, id: String, specs: List<DocSpec>, missing: Boolean, requested: Boolean, note: String, onDone: () -> Unit) {
    if (!missing && !requested) return
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var photos by remember { mutableStateOf(mapOf<String, String>()) }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (requested) tr("The Lifolo team asked for document photos", "Lifolo টিম কাগজের ছবি চেয়েছে")
                else tr("Document photos are needed for checking", "যাচাইয়ের জন্য কাগজের ছবি লাগবে"),
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            )
            if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.bodyMedium)
            if (!open) {
                OutlinedButton(onClick = { open = true }) { Text(tr("Add photos", "ছবি দিন")) }
            } else {
                DocPhotosField(specs, photos, { photos = it })
                ErrorText(errorMsg)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !busy && photos.isNotEmpty(), onClick = {
                        scope.launch {
                            busy = true
                            runCatching { VerifyDocs.upload(kind, id, photos) }
                                .onSuccess { open = false; photos = emptyMap(); onDone() }
                                .onFailure { errorMsg = it.message }
                            busy = false
                        }
                    }) { ButtonContent(busy, tr("Send", "পাঠান")) }
                    TextButton(onClick = { open = false }) { Text(tr("Cancel", "বাতিল")) }
                }
            }
        }
    }
}
