package com.lifolo.admin.kernel

import com.fasterxml.jackson.databind.JsonNode
import com.lifolo.auth.AuthUser
import com.lifolo.common.ApiException
import com.lifolo.common.Lang
import com.lifolo.common.StoredFiles
import com.lifolo.practice.Practice
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.sql.Timestamp
import java.util.Base64

/**
 * verify.js — document photos for ambulances, pharmacies, blood banks, labs and professional badges.
 * The owner adds photos when saving; the admin looks at them in the web panel and ticks every check — approval
 * is refused without all ticks (server-side too).
 *
 * `kind`: ambulance | pharmacy | bloodbank | lab | badge. `listing` is Node-shaped ([ListingRows]): the spec reads
 * `kind` (blood bank type: govt/private/org) and `type` (badge: doctor/nurse/…); `docsHave` (stored types, a
 * list or a map like Node), `docsRequestedAt`, `verification`.
 * `docs` is the request field `docs: {type: base64 JPEG/PNG}`; photos not sent stay as they were.
 * Rows: `verify_docs (kind, listing_id, type)`: base64 `data`, or `storage_key` on R2 when it is configured (V602).
 * The actions `verify_docs_upload`, `admin_verify_docs`, `admin_request_docs` are P6 (later PR).
 */
interface VerifyDocs {
    /**
     * validateDocs: checks the photos before anything is written (so a bad photo does not use the daily quota).
     * Each given photo must be base64 JPEG/PNG ≤ 700 KB of text (`data:image/…;base64,` prefix allowed) → else 400
     * `"{label}"-এর ছবিটা পড়া যায়নি বা অনেক বড়`. When [required] (a new listing), every required photo must be
     * there → 400 code `docs_required`. Returns {type: clean base64}.
     */
    fun validate(lang: String?, kind: String, listing: Map<String, Any?>, docs: JsonNode?, required: Boolean): Map<String, String>

    /**
     * saveDocs: validates, then stores the given photos. For an existing listing ([required] = false) the listing
     * row's `docs_have` gets the new types; for a new one the caller writes `docs_have` with the returned types.
     * Returns the saved types, or null when no photo was given.
     */
    fun save(
        lang: String?,
        kind: String,
        listingId: String,
        ownerUid: String?,
        listing: Map<String, Any?>,
        docs: JsonNode?,
        required: Boolean,
    ): List<String>?

    /** deleteDocs: all photos of the listing (listing removed) */
    fun delete(kind: String, listingId: String)

    /** deleteForOwner: all photos of a user (account deletion) */
    fun deleteForOwner(uid: String)

    /** ownerInfo: {docs: [{type, label, hint, required, has}], docsMissing, docsRequested} for the owner's card */
    fun ownerInfo(kind: String, listingId: String, listing: Map<String, Any?>): Map<String, Any?>

    /** panelInfo: {docs: [{type, label, hint, required, has}], checks: [{id, label, format?, link?}], docsRequestedAt, verification} */
    fun panelInfo(kind: String, listingId: String, listing: Map<String, Any?>): Map<String, Any?>

    /**
     * assertChecks: before approving — required photos present (else 400 `docs_required` "দরকারি কাগজের ছবি নেই — …")
     * and every check id of [VerifySpec.checks] ticked in [checks] (else 400 `checks_required`).
     * Returns the record to store: {by, byEmail, at, checks} (→ `verified_by`, `verified_by_email`, `verified_at`,
     * `verification_checks`).
     */
    fun assertChecks(lang: String?, admin: AuthUser, kind: String, listing: Map<String, Any?>, checks: JsonNode?): Map<String, Any?>
}

/** verify.js DOCS / CHECKS — what to photograph and what the admin must confirm (labels shown in the app and panel) */
object VerifySpec {
    data class Doc(val type: String, val label: String, val hint: String, val required: Boolean) {
        fun toMap(): LinkedHashMap<String, Any?> = linkedMapOf("type" to type, "label" to label, "hint" to hint, "required" to required)
    }

    data class Check(val id: String, val label: String, val format: String? = null, val link: String? = null) {
        fun toMap(): LinkedHashMap<String, Any?> = linkedMapOf<String, Any?>("id" to id, "label" to label).apply {
            if (link != null) put("link", link)
            if (format != null) put("format", format)
        }
    }

    val KINDS = setOf("ambulance", "pharmacy", "bloodbank", "lab", "badge")

    fun docs(kind: String, x: Map<String, Any?>?): List<Doc> = when (kind) {
        "ambulance" -> listOf(
            Doc("reg", "রেজিস্ট্রেশন সনদ বা স্মার্ট কার্ড", "BRTA-র কাগজ — গাড়ির নম্বর আর ধরন পরিষ্কার পড়া যায়", true),
            Doc("fitness", "ফিটনেস সনদ", "মেয়াদের তারিখ পড়া যায় এমন", true),
            Doc("front", "গাড়ির ছবি (নম্বর প্লেটসহ)", "সামনে বা পেছন থেকে, প্লেট পড়া যায়", true),
        )
        "pharmacy" -> listOf(
            Doc("license", "ড্রাগ লাইসেন্স", "পুরো কাগজ, নম্বর আর মেয়াদ পড়া যায়", true),
            Doc("front", "দোকানের সামনের ছবি (সাইনবোর্ডসহ)", "দোকানের নাম পড়া যায়", true),
            Doc("pharmacist", "ফার্মাসিস্টের নিবন্ধন সনদ", "থাকলে দিন", false),
        )
        "bloodbank" -> listOf(
            Doc("front", "ব্লাড ব্যাংকের ছবি (সাইনবোর্ড বা প্রবেশপথ)", "নাম পড়া যায়", true),
            Doc("license", "স্বাস্থ্য অধিদপ্তরের ব্লাড ব্যাংক লাইসেন্স", "বেসরকারি হলে লাগবে", x?.get("kind") == "private"),
            Doc("authority", "হাসপাতাল বা সংগঠনের প্যাডে প্রত্যয়ন", "সরকারি হাসপাতাল বা সংগঠন হলে দিন", false),
        )
        "lab" -> listOf(
            Doc("license", "স্বাস্থ্য অধিদপ্তরের ক্লিনিক/ডায়াগনস্টিক লাইসেন্স", "পুরো কাগজ, নম্বর আর মেয়াদ পড়া যায়", true),
            Doc("front", "সেন্টারের সামনের ছবি (সাইনবোর্ডসহ)", "নাম পড়া যায়", true),
        )
        "badge" -> when (x?.get("type")) {
            // PRACTICE.md: a homeopathic/unani/ayurvedic doctor — the registering body's certificate (no online register)
            "doctor" -> if (isAltSystem(x)) {
                listOf(
                    Doc("cert", "${bodyOf(x)}-এর নিবন্ধন সনদ", "নম্বর আর নাম পড়া যায়", true), // bn-ok
                    Doc("idcard", "কর্মস্থলের পরিচয়পত্র", "থাকলে দিন", false),
                )
            } else {
                listOf(
                    Doc("cert", "BMDC নিবন্ধন সনদ", "থাকলে দিন — নম্বর অনলাইনে মিলিয়ে দেখা হয়", false),
                    Doc("idcard", "কর্মস্থলের পরিচয়পত্র", "থাকলে দিন", false),
                )
            }
            Practice.PHYSIO -> listOf(
                Doc("cert", "ডিগ্রির সনদ (BPT / MPT)", "নাম আর প্রতিষ্ঠান পড়া যায়", true), // bn-ok
                Doc("member", "পেশাজীবী সংগঠনের সদস্যপত্র", "থাকলে দিন", false), // bn-ok
                Doc("idcard", "কর্মস্থলের পরিচয়পত্র", "থাকলে দিন", false),
            )
            Practice.PSYCHOLOGIST -> listOf(
                Doc("cert", "ডিগ্রির সনদ (ক্লিনিক্যাল বা কাউন্সেলিং সাইকোলজিতে MS / MPhil)", "নাম আর প্রতিষ্ঠান পড়া যায়", true), // bn-ok
                Doc("member", "বাংলাদেশ ক্লিনিক্যাল সাইকোলজি সোসাইটির সদস্যপত্র", "থাকলে দিন", false), // bn-ok
                Doc("idcard", "কর্মস্থলের পরিচয়পত্র", "থাকলে দিন", false),
            )
            "nurse" -> listOf(
                Doc("cert", "নার্সিং ও মিডওয়াইফারি কাউন্সিলের (BNMC) নিবন্ধন সনদ", "নম্বর আর নাম পড়া যায়", true),
                Doc("idcard", "কর্মস্থলের পরিচয়পত্র", "থাকলে দিন", false),
            )
            else -> listOf(
                Doc("cert", "পেশার সনদ বা ডিগ্রির কাগজ", "নাম আর প্রতিষ্ঠান পড়া যায়", true),
                Doc("idcard", "কর্মস্থলের পরিচয়পত্র", "থাকলে দিন", false),
            )
        }
        else -> throw IllegalArgumentException("unknown verify kind $kind")
    }

    private val PHONE_CHECK = Check(
        "phone",
        "অ্যাপে দেওয়া নম্বরে ফোন করে কথা হয়েছে",
        "মোবাইল ১১ অঙ্ক (০১…); ল্যান্ডলাইন এলাকার কোড দিয়ে (০২…, ০৯১…); হটলাইন ৫ অঙ্ক (১৬…)",
    )

    fun checks(kind: String, x: Map<String, Any?>?): List<Check> = when (kind) {
        "ambulance" -> listOf(
            Check(
                "reg_no", "রেজিস্ট্রেশন সনদের নম্বর আর অ্যাপে লেখা গাড়ির নম্বর হুবহু এক",
                "জেলা বা মেট্রো – শ্রেণির অক্ষর – দুই অঙ্ক – চার অঙ্ক। যেমন: ঢাকা মেট্রো-ছ ১১-২২৩৩ বা ময়মনসিংহ-ছ ১১-০০৪৫",
            ),
            Check(
                "reg_type", "রেজিস্ট্রেশনে গাড়ির ধরন \"অ্যাম্বুলেন্স\" লেখা",
                "মাইক্রোবাস বা প্রাইভেটকার লেখা থাকলে বাতিল করুন — অনেক মাইক্রোবাস অ্যাম্বুলেন্স সেজে চলে",
            ),
            Check("plate", "গাড়ির ছবির নম্বর প্লেটে একই নম্বর", "প্লেটের প্রতিটা অক্ষর আর অঙ্ক রেজিস্ট্রেশনের সাথে মিলিয়ে দেখুন"),
            Check("fitness", "ফিটনেস সনদ এই গাড়িরই, আর মেয়াদ শেষ হয়নি", "সনদের \"মেয়াদ\" বা \"Valid up to\" তারিখ আজকের পরে"),
            PHONE_CHECK,
            Check("details", "নাম, এলাকা আর ভাড়ার তথ্য স্বাভাবিক (বিজ্ঞাপন বা ভুয়া নয়)", "নামে শুধু সার্ভিস বা চালকের নাম; ফোন নম্বর বা লিংক নয়"),
        )
        "pharmacy" -> listOf(
            Check("license_no", "লাইসেন্সের নম্বর আর অ্যাপে লেখা নম্বর হুবহু এক", "ছবিতে যেভাবে লেখা ঠিক সেভাবে — প্রতিটা অক্ষর, অঙ্ক, হাইফেন মিলবে"),
            Check("license_name", "লাইসেন্সে দোকানের নাম আর অ্যাপের নাম এক", "বানানের ছোট পার্থক্য চলবে, অন্য নাম নয়"),
            Check("license_addr", "লাইসেন্সের ঠিকানা আর অ্যাপের জেলা ও ঠিকানা এক"),
            Check("license_valid", "লাইসেন্সের মেয়াদ শেষ হয়নি", "লাইসেন্সে লেখা মেয়াদ বা নবায়নের শেষ তারিখ আজকের পরে"),
            Check("license_seal", "ঔষধ প্রশাসন অধিদপ্তরের সিল আর কর্মকর্তার স্বাক্ষর আছে"),
            Check("signboard", "দোকানের ছবির সাইনবোর্ডে একই নাম"),
            PHONE_CHECK,
        )
        "bloodbank" -> listOf(
            Check("name", "নাম ছবির সাইনবোর্ড বা কাগজের সাথে মেলে"),
            Check("kind", "ধরন ঠিক (সরকারি হাসপাতাল / বেসরকারি / স্বেচ্ছাসেবী সংগঠন)"),
            Check("address", "ঠিকানা আর জেলা মেলে"),
        ) + (
            if (x?.get("kind") == "private") {
                listOf(
                    Check("license_no", "লাইসেন্সের নম্বর আর অ্যাপে লেখা নম্বর হুবহু এক", "ছবিতে যেভাবে লেখা ঠিক সেভাবে"),
                    Check("license_name", "লাইসেন্সে একই প্রতিষ্ঠানের নাম"),
                    Check("license_valid", "লাইসেন্সের মেয়াদ শেষ হয়নি", "মেয়াদের শেষ তারিখ আজকের পরে"),
                )
            } else {
                emptyList()
            }
            ) + PHONE_CHECK.copy(label = "হাসপাতাল বা ব্লাড ব্যাংকের নম্বরে ফোন করে নিশ্চিত হয়েছেন")
        "lab" -> listOf(
            Check("license_no", "লাইসেন্সের নম্বর আর অ্যাপে লেখা নম্বর হুবহু এক", "ছবিতে যেভাবে লেখা ঠিক সেভাবে"),
            Check("license_name", "লাইসেন্সে সেন্টারের নাম আর অ্যাপের নাম এক"),
            Check("license_valid", "লাইসেন্সের মেয়াদ শেষ হয়নি", "মেয়াদের শেষ তারিখ আজকের পরে"),
            Check("license_type", "লাইসেন্স ডায়াগনস্টিক/প্যাথলজির জন্য (শুধু ক্লিনিক নয়)"),
            Check("signboard", "ছবির সাইনবোর্ডে একই নাম"),
            PHONE_CHECK,
        )
        "badge" -> when (x?.get("type")) {
            "doctor" -> if (isAltSystem(x)) {
                listOf(
                    Check("cert_no", "সনদের নিবন্ধন নম্বর আর অ্যাপে লেখা নম্বর এক", "সনদে যেভাবে লেখা ঠিক সেভাবে"),
                    Check("cert_body", "সনদটা ${bodyOf(x)}-এর, ডিগ্রি ${degreesOf(x)}", "অন্য বোর্ড বা অন্য পদ্ধতির সনদ হলে বাতিল"), // bn-ok
                    Check("cert_name", "সনদে নাম অ্যাপের নামের সাথে মেলে"),
                    Check("workplace", "কর্মস্থল বাস্তব", "হাসপাতাল বা ক্লিনিকে ফোন করে মিলিয়েছেন"),
                )
            } else {
                listOf(
                    Check(
                        "bmdc_found", "BMDC সাইটে নম্বর দিয়ে ডাক্তার পাওয়া গেছে",
                        "MBBS হলে A- এর পরে অঙ্ক (যেমন A-১২৩৪৫), BDS হলে D- এর পরে অঙ্ক",
                        "https://www.bmdc.org.bd/view-doctor",
                    ),
                    Check("bmdc_name", "BMDC-তে নাম অ্যাপের নামের সাথে মেলে", "বানানের ছোট পার্থক্য চলবে; ভিন্ন মানুষ হলে বাতিল"),
                    Check("bmdc_active", "নিবন্ধন বৈধ (বাতিল বা মেয়াদোত্তীর্ণ নয়)"),
                    Check("workplace", "কর্মস্থল বাস্তব", "হাসপাতাল বা ক্লিনিকে ফোন করে বা তাদের ওয়েবসাইটে মিলিয়েছেন"),
                )
            }
            Practice.PHYSIO -> listOf(
                Check("cert_name", "সনদে নাম অ্যাপের নামের সাথে মেলে"),
                Check("cert_degree", "সনদে ফিজিওথেরাপিতে BPT বা MPT ডিগ্রি", "ডিপ্লোমা বা অন্য বিষয়ের ডিগ্রি হলে বাতিল"), // bn-ok
                Check("workplace", "কর্মস্থল বা কাজের জায়গা বাস্তব"),
            )
            Practice.PSYCHOLOGIST -> listOf(
                Check("cert_name", "সনদে নাম অ্যাপের নামের সাথে মেলে"),
                Check("cert_degree", "সনদে ক্লিনিক্যাল বা কাউন্সেলিং সাইকোলজিতে MS বা MPhil", "শুধু BSc/BA বা অন্য বিষয় হলে বাতিল"), // bn-ok
                Check("workplace", "কর্মস্থল বা কাজের জায়গা বাস্তব"),
            )
            "nurse" -> listOf(
                Check("cert_no", "সনদের নিবন্ধন নম্বর আর অ্যাপে লেখা নম্বর এক", "সনদে যেভাবে লেখা ঠিক সেভাবে"),
                Check("cert_name", "সনদে নাম অ্যাপের নামের সাথে মেলে"),
                Check("cert_valid", "নিবন্ধনের মেয়াদ শেষ হয়নি", "সনদে নবায়নের তারিখ থাকলে তা আজকের পরে"),
                Check("workplace", "কর্মস্থল বাস্তব", "হাসপাতাল বা ক্লিনিকে ফোন করে মিলিয়েছেন"),
            )
            else -> listOf(
                Check("cert_name", "সনদে নাম অ্যাপের নামের সাথে মেলে"),
                Check("cert_relevant", "সনদটা এই পেশার (পুষ্টিবিদ, প্রশিক্ষক ইত্যাদি) সাথে সম্পর্কিত"),
                Check("workplace", "কর্মস্থল বা কাজের জায়গা বাস্তব"),
            )
        }
        else -> throw IllegalArgumentException("unknown verify kind $kind")
    }

    /** a badge listing of a non-allopathic doctor (PRACTICE.md) */
    private fun isAltSystem(x: Map<String, Any?>?): Boolean = Practice.freeText("doctor", x?.get("system") as? String)

    private fun bodyOf(x: Map<String, Any?>?): String = Practice.SYSTEM_INFO[x?.get("system") as? String]?.bodyBn ?: ""

    private fun degreesOf(x: Map<String, Any?>?): String = Practice.SYSTEM_INFO[x?.get("system") as? String]?.degrees ?: ""

    /** the stored document types of a Node-shaped listing (`docsHave` as a map like Node or a list like [ListingRows]) */
    fun haveTypes(x: Map<String, Any?>?): Set<String> = when (val d = x?.get("docsHave")) {
        is Map<*, *> -> d.keys.map { it.toString() }.toSet()
        is Collection<*> -> d.map { it.toString() }.toSet()
        else -> emptySet()
    }

    const val MAX_B64 = 700 * 1024
    private val PREFIX = Regex("^data:image/[a-z]+;base64,")
    private val WS = Regex("\\s+")
    private val B64 = Regex("^[A-Za-z0-9+/]+=*$")

    /** verify.js cleanB64: base64 JPEG or PNG (first bytes), else null */
    fun cleanB64(v: Any?): String? {
        if (v !is String) return null
        val s = WS.replace(PREFIX.replace(v, ""), "")
        if (s.isEmpty() || s.length > MAX_B64 || !B64.matches(s)) return null
        val head = s.take(16).trimEnd('=').let { it.take(it.length / 4 * 4) }
        val bytes = try {
            Base64.getDecoder().decode(head)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val jpeg = bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte()
        val png = bytes.size >= 2 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()
        return if (jpeg || png) s else null
    }
}

@Service
class VerifyDocsService(private val jdbc: JdbcTemplate, private val files: StoredFiles) : VerifyDocs {
    override fun validate(lang: String?, kind: String, listing: Map<String, Any?>, docs: JsonNode?, required: Boolean): Map<String, String> {
        val list = VerifySpec.docs(kind, listing)
        val given = docs?.takeIf { it.isObject }
        val clean = LinkedHashMap<String, String>()
        for (d in list) {
            val node = given?.get(d.type)
            if (node == null || node.isNull) continue
            val b = VerifySpec.cleanB64(if (node.isTextual) node.textValue() else null)
                ?: throw ApiException(
                    400,
                    Lang.tr(lang, "The photo \"${d.label}\" could not be read or is too large", "\"${d.label}\"-এর ছবিটা পড়া যায়নি বা অনেক বড়"),
                )
            clean[d.type] = b
        }
        if (required) {
            val missing = list.filter { it.required && it.type !in clean }
            if (missing.isNotEmpty()) {
                val names = missing.joinToString(", ") { it.label }
                throw ApiException(
                    400,
                    Lang.tr(
                        lang,
                        "Add photos of: $names. If you don't see this option, update the app.",
                        "এই কাগজের ছবি দিন: $names। ছবি দেওয়ার জায়গা না দেখলে অ্যাপ হালনাগাদ করুন।",
                    ),
                    "docs_required",
                )
            }
        }
        return clean
    }

    override fun save(
        lang: String?,
        kind: String,
        listingId: String,
        ownerUid: String?,
        listing: Map<String, Any?>,
        docs: JsonNode?,
        required: Boolean,
    ): List<String>? {
        val clean = validate(lang, kind, listing, docs, required)
        if (clean.isEmpty()) return null
        val now = Timestamp(System.currentTimeMillis())
        for ((type, data) in clean) {
            // R2 configured: the bytes there, the key here (a replaced key is queued for deletion by a trigger)
            val key = if (files.enabled) files.put("verify", data) else null
            jdbc.update(
                """
                INSERT INTO verify_docs (kind, listing_id, type, owner_uid, data, storage_key, size, at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (kind, listing_id, type) DO UPDATE SET owner_uid = EXCLUDED.owner_uid, data = EXCLUDED.data,
                  storage_key = EXCLUDED.storage_key, size = EXCLUDED.size, at = EXCLUDED.at
                """.trimIndent(),
                kind, listingId, type, ownerUid, if (key == null) data else null, key, data.length, now,
            )
        }
        val types = clean.keys.toList()
        if (!required) {
            // an existing listing/badge: which photos it has, in the same step (a new one is written by the caller)
            val k = ListingKind.of(kind) ?: error("unknown verify kind $kind")
            jdbc.update(
                "UPDATE ${k.table} SET docs_have = ARRAY(SELECT DISTINCT unnest(docs_have || ?::text[])) WHERE ${k.idColumn} = ?",
                types.joinToString(",", "{", "}"), listingId,
            )
        }
        return types
    }

    override fun delete(kind: String, listingId: String) {
        jdbc.update("DELETE FROM verify_docs WHERE kind = ? AND listing_id = ?", kind, listingId)
    }

    override fun deleteForOwner(uid: String) {
        jdbc.update("DELETE FROM verify_docs WHERE owner_uid = ?", uid)
    }

    override fun ownerInfo(kind: String, listingId: String, listing: Map<String, Any?>): Map<String, Any?> {
        val has = VerifySpec.haveTypes(listing)
        val list = VerifySpec.docs(kind, listing)
        return linkedMapOf(
            "docs" to list.map { d -> d.toMap().apply { put("has", d.type in has) } },
            "docsMissing" to list.any { it.required && it.type !in has },
            "docsRequested" to ((listing["docsRequestedAt"] as? Number)?.toLong() ?: 0L).let { it != 0L },
        )
    }

    override fun panelInfo(kind: String, listingId: String, listing: Map<String, Any?>): Map<String, Any?> {
        val has = VerifySpec.haveTypes(listing)
        return linkedMapOf(
            "docs" to VerifySpec.docs(kind, listing).map { d -> d.toMap().apply { put("has", d.type in has) } },
            "checks" to VerifySpec.checks(kind, listing).map { it.toMap() },
            "docsRequestedAt" to ((listing["docsRequestedAt"] as? Number)?.toLong() ?: 0L),
            "verification" to listing["verification"],
        )
    }

    override fun assertChecks(lang: String?, admin: AuthUser, kind: String, listing: Map<String, Any?>, checks: JsonNode?): Map<String, Any?> {
        val have = VerifySpec.haveTypes(listing)
        val noPhoto = VerifySpec.docs(kind, listing).filter { it.required && it.type !in have }
        if (noPhoto.isNotEmpty()) {
            throw ApiException(
                400,
                Lang.tr(
                    lang,
                    "Required document photos are missing — ask the owner for them first.",
                    "দরকারি কাগজের ছবি নেই — আগে মালিকের কাছে ছবি চান।",
                ),
                "docs_required",
            )
        }
        val need = VerifySpec.checks(kind, listing).map { it.id }
        val got = if (checks != null && checks.isArray) checks.filter { it.isTextual }.map { it.textValue() }.toSet() else emptySet()
        if (need.any { it !in got }) {
            throw ApiException(
                400,
                Lang.tr(
                    lang,
                    "Tick every check in the web admin panel after looking at the photos, then approve.",
                    "ওয়েব অ্যাডমিন প্যানেলে ছবি দেখে সব বিষয় টিক দিয়ে তারপর অনুমোদন দিন।",
                ),
                "checks_required",
            )
        }
        return linkedMapOf("by" to admin.uid, "byEmail" to admin.email.lowercase(), "at" to System.currentTimeMillis(), "checks" to need)
    }
}
