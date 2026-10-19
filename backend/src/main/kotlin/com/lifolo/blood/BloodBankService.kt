package com.lifolo.blood

import com.lifolo.admin.kernel.Catalog
import com.lifolo.admin.kernel.ListingKind
import com.lifolo.admin.kernel.ListingReview
import com.lifolo.admin.kernel.ListingRows
import com.lifolo.admin.kernel.ListingRules
import com.lifolo.admin.kernel.ListingStats
import com.lifolo.admin.kernel.Moderation
import com.lifolo.admin.kernel.Sanctions
import com.lifolo.admin.kernel.VerifyDocs
import com.lifolo.auth.AdminService
import com.lifolo.common.ApiException
import com.lifolo.common.Body
import com.lifolo.common.Ids
import com.lifolo.common.Js
import com.lifolo.common.Lang
import com.lifolo.common.Phones
import com.lifolo.common.Places
import com.lifolo.common.QuotaService
import com.lifolo.common.Rules
import com.lifolo.common.Sql
import com.lifolo.common.SqlSet
import com.lifolo.common.action.ActionContext
import com.lifolo.notify.PushService
import com.lifolo.notify.PushText
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.text.Collator
import java.util.Locale

/**
 * bloodbank.js — government hospital blood banks, private ones (with a licence) and volunteer organisations.
 * An owner (or an admin) adds them; after admin approval they are listed by district and people call directly.
 * Listing maps are Node-shaped ([ListingRows]) so the kernel services (review, sanctions, documents) apply as in Node.
 */
@Service
class BloodBankService(
    private val store: BloodStore,
    private val rows: ListingRows,
    private val review: ListingReview,
    private val sanctions: Sanctions,
    private val verify: VerifyDocs,
    private val stats: ListingStats,
    private val moderation: Moderation,
    private val catalog: Catalog,
    private val quotas: QuotaService,
    private val admins: AdminService,
    private val push: PushService,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val jdbc get() = store.jdbc
    private val kind = ListingKind.BLOODBANK

    private fun list(where: String, vararg args: Any?): List<Map<String, Any?>> =
        jdbc.query("SELECT * FROM blood_banks WHERE $where", { rs, _ -> rows.view(kind, rs).apply { put("id", rs.getString("id")) } }, *args)

    private fun load(id: String): Map<String, Any?>? = rows.load(kind, id)?.let { LinkedHashMap(it).apply { put("id", id) } }

    private fun str(x: Map<String, Any?>, k: String): String = (x[k] as? String) ?: ""

    /** bloodbank.js view (+ licence and state for the owner) */
    private fun view(x: Map<String, Any?>, mine: Boolean = false): LinkedHashMap<String, Any?> {
        val v = linkedMapOf<String, Any?>(
            "id" to x["id"],
            "name" to x["name"],
            "kind" to x["kind"],
            "phone" to Phones.fixPhone(x["phone"] as? String),
            "district" to x["district"],
            "upazilaId" to ((x["upazilaId"] as? Number)?.toInt()?.takeIf { it != 0 }),
            "address" to str(x, "address"),
            "open24" to (x["open24"] == true),
            "openAt" to str(x, "openAt"),
            "closeAt" to str(x, "closeAt"),
            "openNow" to ListingRules.openNow(x),
            "services" to (x["services"] ?: emptyList<String>()),
            "closedDays" to (x["closedDays"] ?: emptyList<Int>()),
        )
        if (mine) {
            v["licenseNo"] = str(x, "licenseNo")
            v["status"] = x["status"]
            v["statusReason"] = str(x, "statusReason")
        }
        return v
    }

    private fun requireAdmin(ctx: ActionContext) {
        if (!admins.isAdmin(ctx.user)) throw ctx.err(403, "Admins only", "শুধু অ্যাডমিন")
    }

    /**
     * bloodbank.js parse: the data sent by the app or the panel, checked. Node-shaped (licenseNo null when empty,
     * like Node); `closedDays` only when the app sent a list (older apps keep the old value).
     */
    private fun parse(ctx: ActionContext, b: Body): LinkedHashMap<String, Any?> {
        fun err(en: String, bn: String) = ApiException(400, ctx.tr(en, bn))
        val name = Rules.cleanText(b.string("name"), 100) ?: throw err("Enter the blood bank name", "ব্লাড ব্যাংকের নাম লিখুন")
        val k = Js.string(b["kind"])?.takeIf { it in KINDS } ?: throw err("Choose the type", "ধরন বেছে নিন")
        // hospital blood banks often have only a landline or a hotline
        val phone = Phones.contactPhone(Js.toStr(b["phone"])) ?: throw err("Enter a valid phone number", "সঠিক ফোন নম্বর দিন")
        val district = b.string("district")
        if (!Places.isDistrict(district)) throw err("Choose a district", "জেলা বেছে নিন")
        val upazilaId = Places.upazilaIn(district, b.int("upazilaId"))
        val address = Rules.cleanText(b.string("address"), 200) ?: throw err("Write the address", "ঠিকানা লিখুন")
        val licenseNo = Rules.cleanText(b.string("licenseNo"), 60)
        // a private blood bank needs a government licence
        if (k == "private" && licenseNo == null) throw err("Enter the blood bank licence number", "ব্লাড ব্যাংকের লাইসেন্স নম্বর দিন")
        val open24 = b.isTrue("open24")
        val openAt = b.string("openAt")?.takeIf { TIME_RE.matches(it) } ?: ""
        val closeAt = b.string("closeAt")?.takeIf { TIME_RE.matches(it) } ?: ""
        if (!open24 && (openAt.isEmpty() || closeAt.isEmpty() || openAt == closeAt)) {
            throw err("Set the opening hours or choose 24 hours", "খোলার সময় দিন অথবা ২৪ ঘণ্টা বেছে নিন")
        }
        val all = catalog.codes("bloodBankService")
        val services = ListingRules.pickCodes(jsList(b["services"]), all, all.size)
        if (services.isEmpty()) throw err("Choose the services", "কী কী সেবা দেন বেছে নিন")
        val data = linkedMapOf<String, Any?>(
            "name" to name, "kind" to k, "phone" to phone, "district" to district, "upazilaId" to upazilaId, "address" to address,
            "licenseNo" to licenseNo, "open24" to open24, "openAt" to (if (open24) "" else openAt), "closeAt" to (if (open24) "" else closeAt),
            "services" to services, "updatedAt" to System.currentTimeMillis(),
        )
        if (b["closedDays"]?.isArray == true) data["closedDays"] = ListingRules.pickClosedDays(jsList(b["closedDays"]))
        return data
    }

    /** the data fields onto an UPDATE/INSERT (Node `ref.update({...data})`) */
    private fun setData(set: SqlSet, data: Map<String, Any?>) {
        val services = (data["services"] as List<*>).map { it.toString() }
        set.value("name", data["name"]).value("kind", data["kind"]).value("phone", data["phone"]).value("district", data["district"])
            .value("upazila_id", data["upazilaId"]).value("address", data["address"]).value("license_no", (data["licenseNo"] as? String) ?: "")
            .value("open24", data["open24"]).value("open_at", data["openAt"]).value("close_at", data["closeAt"])
            .textArray("services", services)
            .ms("updated_at", data["updatedAt"] as Long)
        val closed = (data["closedDays"] as? List<*>)?.map { (it as Number).toInt() }
        if (closed != null) set.intArray("closed_days", closed, "int2")
    }

    /** the stored form of a data map (licence '' instead of null) — what [ListingRows] reads back */
    private fun stored(data: Map<String, Any?>): Map<String, Any?> = LinkedHashMap(data).apply { put("licenseNo", (data["licenseNo"] as? String) ?: "") }

    private fun insert(id: String, data: Map<String, Any?>, extra: (SqlSet) -> Unit) {
        // INSERT the NOT NULL columns, then the rest through one UPDATE (same transaction)
        jdbc.update(
            "INSERT INTO blood_banks (id, name, kind, phone, district) VALUES (?, ?, ?, ?, ?)",
            id, data["name"], data["kind"], data["phone"], data["district"],
        )
        val set = SqlSet()
        setData(set, data)
        extra(set)
        set.update(jdbc, "blood_banks", "id = ?", id)
    }

    // ---------------------------------------------------------------- public / owner

    /** approved blood banks of a district: open first, own upazila first, government first */
    fun banks(ctx: ActionContext): Any {
        val b = ctx.body
        val district = b.string("district")
        if (!Places.isDistrict(district)) throw ctx.err(400, "Choose a district", "জেলা বেছে নিন")
        val upazilaId = b.int("upazilaId")?.toInt()
        var items = list("district = ? AND status = 'approved' ORDER BY created_at, id LIMIT 300", district).map { view(it) }
        if (b.isTrue("open24")) items = items.filter { it["open24"] == true }
        fun rank(x: Map<String, Any?>) = (if (x["openNow"] == true) 4 else 0) +
            (if (upazilaId != null && upazilaId != 0 && x["upazilaId"] == upazilaId) 2 else 0) + (if (x["kind"] == "govt") 1 else 0)
        val collator = Collator.getInstance(Locale.ROOT)
        items = items.sortedWith(compareByDescending<Map<String, Any?>> { rank(it) }.thenComparator { x, y -> collator.compare(x["name"].toString(), y["name"].toString()) })
        return linkedMapOf("items" to items)
    }

    /** the owner's card: which document photos are still missing (pending/rejected, or asked for by an admin) */
    private fun withDocs(x: Map<String, Any?>, v: LinkedHashMap<String, Any?>): Map<String, Any?> {
        if (x["status"] !in setOf("pending", "rejected") && x["docsRequestedAt"] == null) return v
        val info = try {
            verify.ownerInfo("bloodbank", x["id"] as String, x)
        } catch (e: Exception) {
            emptyMap()
        }
        return LinkedHashMap(v).apply {
            putAll(info)
            put("docsRequestNote", str(x, "docsRequestNote"))
        }
    }

    fun mine(ctx: ActionContext): Any =
        linkedMapOf("items" to list("owner_uid = ? ORDER BY created_at, id LIMIT $MAX_PER_OWNER", ctx.user.id).map { withDocs(it, view(it, true)) })

    /** add or edit; a changed name (beyond a spelling fix), phone, licence, type, district or address is reviewed again */
    fun save(ctx: ActionContext): Any {
        val b = ctx.body
        val uid = ctx.user.id
        if (!b.isTrue("termsAccepted")) throw ctx.err(400, "Please accept the terms", "শর্তে সম্মতি দিন")
        val data = parse(ctx, b)
        if (Js.truthy(b["id"])) {
            val id = b.string("id")
            if (!Rules.isValidId(id)) throw ctx.notFound()
            val s = load(id!!)
            if (s == null || s["ownerUid"] != uid) throw ctx.notFound()
            if (s["status"] == "suspended") {
                throw ctx.err(
                    403,
                    "This blood bank is suspended. You can appeal from Profile → Help & appeals.",
                    "এই ব্লাড ব্যাংক বন্ধ করা আছে। প্রোফাইলের \"সাহায্য ও আপিল\" থেকে আপিল করতে পারেন।",
                )
            }
            sanctions.checkListing(ctx.lang, "bloodbank", data, ctx.user.email, false)
            val r = ListingRules.reviewNeeded(s, data, listOf("phone", "licenseNo", "kind", "district", "address"))
            verify.save(ctx.lang, "bloodbank", id, uid, LinkedHashMap(s).apply { putAll(data) }, b["docs"], false)
            val set = SqlSet()
            setData(set, data)
            if (r.approvedName != null) set.value("approved_name", r.approvedName)
            ListingRules.keepApproved(s, stored(data), r.changed)?.let { set.json("last_approved", rows.toJson(it)) }
            if (r.changed) set.value("status", "pending")
            set.update(jdbc, "blood_banks", "id = ?", id)
            return linkedMapOf("ok" to true, "pending" to (r.changed || s["status"] == "pending"))
        }
        val count = jdbc.queryForObject("SELECT count(*) FROM blood_banks WHERE owner_uid = ?", Int::class.java, uid) ?: 0
        if (count >= MAX_PER_OWNER) throw ctx.err(400, "Up to 3 blood banks per account", "একটা অ্যাকাউন্টে সর্বোচ্চ ৩টা ব্লাড ব্যাংক")
        sanctions.checkListing(ctx.lang, "bloodbank", data, ctx.user.email, true)
        verify.validate(ctx.lang, "bloodbank", data, b["docs"], true)
        quotas.use(ctx.lang, uid, "bloodbank")
        val id = Ids.newId()
        val docsHave = verify.save(ctx.lang, "bloodbank", id, uid, data, b["docs"], true)
        store.inTx {
            insert(id, data) { set ->
                set.value("owner_uid", uid).value("status", "pending").value("terms_accepted", true)
                    .textArray("docs_have", docsHave ?: emptyList()).ms("created_at", System.currentTimeMillis())
            }
        }
        return linkedMapOf("ok" to true, "pending" to true, "id" to id)
    }

    fun delete(ctx: ActionContext): Any {
        val id = ctx.body.string("id")
        if (!Rules.isValidId(id)) return linkedMapOf("ok" to true)
        val s = load(id!!)
        if (s == null || s["ownerUid"] != ctx.user.id) return linkedMapOf("ok" to true)
        if (s["status"] == "suspended") {
            throw ctx.err(
                403,
                "A suspended blood bank can't be removed. You can appeal from Profile → Help & appeals.",
                "বন্ধ করা ব্লাড ব্যাংক সরানো যায় না। প্রোফাইলের \"সাহায্য ও আপিল\" থেকে আপিল করতে পারেন।",
            )
        }
        moderation.assertNoOpenReports(ctx.lang, "bloodBanks/$id")
        jdbc.update("DELETE FROM blood_banks WHERE id = ?", id)
        stats.delete("bloodbank", id)
        runCatching { verify.delete("bloodbank", id) }
        return linkedMapOf("ok" to true)
    }

    /** the call button (owner's statistics; the same person once per 10 minutes) */
    fun called(ctx: ActionContext): Any {
        val id = ctx.body.string("id")
        if (!Rules.isValidId(id)) return linkedMapOf("ok" to true)
        val owner = jdbc.queryForList("SELECT owner_uid FROM blood_banks WHERE id = ?", id).firstOrNull() ?: return linkedMapOf("ok" to true)
        val uid = ctx.user.id
        if (owner["owner_uid"] == uid) return linkedMapOf("ok" to true)
        val now = System.currentTimeMillis()
        val prev = jdbc.queryForList("SELECT at FROM blood_bank_calls WHERE uid = ? AND bank_id = ?", java.sql.Timestamp::class.java, uid, id).firstOrNull()
        jdbc.update(
            "INSERT INTO blood_bank_calls (uid, bank_id, at) VALUES (?, ?, ?) ON CONFLICT (uid, bank_id) DO UPDATE SET at = EXCLUDED.at",
            uid, id, Sql.ts(now),
        )
        if (prev == null || now - prev.time > CALL_DEDUPE_MS) stats.bumpCall("bloodbank", id!!, now, prev == null)
        return linkedMapOf("ok" to true)
    }

    /** the owner's own statistics */
    fun ownStats(ctx: ActionContext): Any {
        val items = jdbc.queryForList(
            "SELECT id, name, status FROM blood_banks WHERE owner_uid = ? ORDER BY created_at, id LIMIT $MAX_PER_OWNER", ctx.user.id,
        ).map { r ->
            val id = r["id"] as String
            linkedMapOf<String, Any?>("id" to id, "name" to r["name"], "status" to r["status"])
                .apply { putAll(stats.summary("bloodbank", id)) }
                .apply { put("reports", moderation.reportCount("bloodBanks/$id")) }
        }
        return linkedMapOf("items" to items)
    }

    // ---------------------------------------------------------------- admin

    fun adminList(ctx: ActionContext): Any {
        requireAdmin(ctx)
        val status = Js.string(ctx.body["status"])?.takeIf { it in setOf("pending", "approved", "suspended", "rejected") } ?: "pending"
        val items = list("status = ? ORDER BY created_at, id LIMIT 300", status).map { x ->
            val id = x["id"] as String
            view(x, true).apply {
                put("ownerUid", x["ownerUid"])
                put("addedByAdmin", x["addedByAdmin"] == true)
                put("createdAt", x["createdAt"])
                put("reviewedAt", (if (status == "suspended") x["suspendedAt"] else null) ?: x["reviewedAt"])
                put("openAt", str(x, "openAt"))
                put("closeAt", str(x, "closeAt"))
                // what changed since approval, the reason, possible duplicates (pending), documents and checks
                put("statusReason", str(x, "statusReason"))
                put("editRejectedReason", str(x, "editRejectedReason"))
                put("changes", ListingRules.changedFields(x).map { c -> linkedMapOf("field" to c.field, "before" to c.before, "after" to c.after) })
                if (status == "pending") put("duplicates", runCatching { review.possibleDuplicates("bloodbank", id, x) }.getOrDefault(emptyList()))
                if (status == "pending" || status == "rejected") put("verify", runCatching { verify.panelInfo("bloodbank", id, x) }.getOrNull())
                put("verification", x["verification"])
            }
        }
        return linkedMapOf("items" to items)
    }

    fun adminResolve(ctx: ActionContext): Any {
        requireAdmin(ctx)
        return review.resolve(ctx, "bloodbank", "blood_banks", Js.string(ctx.body["id"]))
    }

    /** district names for the panel forms */
    fun adminDistricts(ctx: ActionContext): Any {
        requireAdmin(ctx)
        val collator = Collator.getInstance(Locale.forLanguageTag("bn"))
        return linkedMapOf("items" to Places.DISTRICTS.map { it.bn }.sortedWith(collator))
    }

    /** an admin adds a known blood bank (listed at once, no owner) */
    fun adminAdd(ctx: ActionContext): Any {
        requireAdmin(ctx)
        val data = parse(ctx, ctx.body)
        val id = Ids.newId()
        val now = System.currentTimeMillis()
        store.inTx {
            insert(id, data) { set ->
                set.nul("owner_uid").value("added_by_admin", true).value("status", "approved").ms("created_at", now)
                    .ms("reviewed_at", now).value("reviewed_by", ctx.user.uid)
            }
        }
        return linkedMapOf("ok" to true, "id" to id)
    }

    /** an admin fixes any blood bank (wrong number, hours); the owner, if any, is told */
    fun adminEdit(ctx: ActionContext): Any {
        requireAdmin(ctx)
        val id = ctx.body.string("id")
        if (!Rules.isValidId(id)) throw ctx.err(400, "Not found", "পাওয়া যায়নি")
        val s = load(id!!) ?: throw ctx.notFound()
        val data = parse(ctx, ctx.body)
        // the panel form has no upazila — keep the old one when not sent
        if (!ctx.body.has("upazilaId")) data["upazilaId"] = (s["upazilaId"] as? Number)?.toInt()?.takeIf { it != 0 }
        val set = SqlSet()
        setData(set, data)
        set.now("edited_by_admin_at")
        // the admin's data is now the approved state
        if (s["status"] == "approved") {
            set.value("approved_name", data["name"])
            set.json("last_approved", rows.toJson(ListingRules.snapshot(LinkedHashMap(s).apply { putAll(stored(data)) })))
        }
        set.update(jdbc, "blood_banks", "id = ?", id)
        val owner = s["ownerUid"] as? String
        if (owner != null) {
            push.pushUsers(listOf(owner)) { _, lang ->
                PushText(
                    Lang.pick(lang, "The Lifolo team updated your blood bank details", "Lifolo টিম আপনার ব্লাড ব্যাংকের তথ্য ঠিক করেছে"),
                    data["name"] as String,
                    mapOf("type" to "services", "kind" to "bloodbank"),
                )
            }
        }
        return linkedMapOf("ok" to true)
    }

    /** an admin removes one (closed, added twice) — removed, not suspended, so no sanction stays */
    fun adminDelete(ctx: ActionContext): Any {
        requireAdmin(ctx)
        val id = ctx.body.string("id")
        if (!Rules.isValidId(id)) throw ctx.err(400, "Not found", "পাওয়া যায়নি")
        val s = load(id!!) ?: return linkedMapOf("ok" to true)
        jdbc.update("DELETE FROM blood_banks WHERE id = ?", id)
        runCatching { stats.delete("bloodbank", id) }
        runCatching { verify.delete("bloodbank", id) }
        // its open reports are not needed any more
        jdbc.update(
            """
            UPDATE mod_reports SET status = 'dismissed', resolved_at = now()
            WHERE id IN (SELECT id FROM mod_reports WHERE target_path = ? AND status = 'open' LIMIT 200)
            """.trimIndent(),
            "bloodBanks/$id",
        )
        val owner = s["ownerUid"] as? String
        if (owner != null) {
            val rawReason = ctx.body.string("reason")
            val hasReason = Rules.cleanText(rawReason, 200) != null
            push.pushUsers(listOf(owner)) { _, lang ->
                val reason = rawReason?.let { ListingRules.jsTrim(it) } ?: ""
                PushText(
                    Lang.pick(lang, "Your blood bank was removed from the list", "আপনার ব্লাড ব্যাংক তালিকা থেকে সরানো হয়েছে"),
                    listOf(str(s, "name"), if (hasReason) Lang.pick(lang, "Reason: $reason", "কারণ: $reason") else "")
                        .filter { it.isNotEmpty() }.joinToString(" · "),
                    mapOf("type" to "services", "kind" to "bloodbank"),
                )
            }
        }
        return linkedMapOf("ok" to true)
    }

    companion object {
        val KINDS = listOf("govt", "private", "org")
        const val MAX_PER_OWNER = 3
        const val CALL_DEDUPE_MS = 10L * 60 * 1000
        private val TIME_RE = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")

        /** a JSON array as a list of plain values (strings, numbers, booleans), null when not an array */
        fun jsList(n: com.fasterxml.jackson.databind.JsonNode?): List<Any?>? =
            n?.takeIf { it.isArray }?.map { RequestService.jsValue(it) }
    }
}
