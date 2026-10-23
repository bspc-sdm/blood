package com.lifolo.lab

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lifolo.listings.ListingFacts
import com.lifolo.admin.kernel.ListingKind
import com.lifolo.admin.kernel.ListingReview
import com.lifolo.admin.kernel.ListingRows
import com.lifolo.admin.kernel.ListingRules
import com.lifolo.admin.kernel.VerifyDocs
import com.lifolo.auth.AdminService
import com.lifolo.common.Js
import com.lifolo.common.Lang
import com.lifolo.common.Phones
import com.lifolo.common.Places
import com.lifolo.common.QuotaService
import com.lifolo.common.Rules
import com.lifolo.common.SqlSet
import com.lifolo.common.action.ActionContext
import com.lifolo.common.action.ActionHandler
import com.lifolo.common.action.ActionModule
import com.lifolo.contracts.Badges
import com.lifolo.contracts.HospitalDirectory
import com.lifolo.contracts.Prescription
import com.lifolo.contracts.ProviderAssistants
import com.lifolo.demo.DemoWorld
import com.lifolo.rx.P3Sql
import com.lifolo.rx.PrescriptionStore
import com.lifolo.rx.RxPush
import com.lifolo.rx.RxRules
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * Labs (pathology): listing (lab_me, lab_save, lab_delete, labs, admin_labs, admin_lab_resolve), test catalogue
 * (labtests_get, admin_labtests_set), price lists (lab_prices_*), orders from prescriptions with home sample
 * collection (rx_lab_options, rx_send_lab, lab_orders, lab_open_code, lab_order_status, lab_home_*), reports
 * (lab_report, lab_order_get, lab_form, lab_ranges_set) and the doctor's paid report review (review_*).
 * Node: rxnet.js, labprices.js, labtests.js.
 */
@Component
class LabActions(
    private val jdbc: JdbcTemplate,
    private val store: LabStore,
    private val labs: LabService,
    private val catalog: LabCatalog,
    private val rx: PrescriptionStore,
    private val listing: ListingRows,
    private val review: ListingReview,
    private val verify: VerifyDocs,
    private val hospitals: HospitalDirectory,
    private val quotas: QuotaService,
    private val push: RxPush,
    private val admins: AdminService,
    private val badges: Badges,
    private val assistants: ProviderAssistants,
    private val switches: com.lifolo.listings.ListingSwitches,
) : ActionModule {
    override fun actions(): Map<String, ActionHandler> = mapOf(
        "labtests_get" to ActionHandler(::labtestsGet),
        "admin_labtests_set" to ActionHandler(::adminLabtestsSet),
        "admin_labs" to ActionHandler(::adminLabs),
        "admin_lab_resolve" to ActionHandler(::adminLabResolve),
        "lab_me" to ActionHandler(::labMe),
        "lab_save" to ActionHandler(::labSave),
        "lab_delete" to ActionHandler(::labDelete),
        "labs" to ActionHandler(::labsList),
        "lab_prices_get" to ActionHandler(::labPricesGet),
        "lab_prices_set" to ActionHandler(::labPricesSet),
        "lab_prices_public" to ActionHandler(::labPricesPublic),
        "rx_lab_options" to ActionHandler(::rxLabOptions),
        "rx_send_lab" to ActionHandler(::rxSendLab),
        "lab_orders" to ActionHandler(::labOrders),
        "lab_open_code" to ActionHandler(::labOpenCode),
        "lab_order_status" to ActionHandler(::labOrderStatus),
        "lab_home_confirm" to ActionHandler(::labHomeConfirm),
        "lab_home_decline" to ActionHandler(::labHomeDecline),
        "lab_home_cancel" to ActionHandler(::labHomeCancel),
        "lab_home_change" to ActionHandler(::labHomeChange),
        "lab_report" to ActionHandler(::labReport),
        "lab_order_get" to ActionHandler(::labOrderGet),
        "lab_form" to ActionHandler(::labForm),
        "lab_ranges_set" to ActionHandler(::labRangesSet),
        "review_settings" to ActionHandler(::reviewSettings),
        "review_pay" to ActionHandler(::reviewPay),
        "reviews_doctor" to ActionHandler(::reviewsDoctor),
        "review_confirm" to ActionHandler(::reviewConfirm),
        "review_done" to ActionHandler(::reviewDone),
    )

    // ------------------------------------------------------------------ helpers

    private fun ok(): Map<String, Any?> = linkedMapOf("ok" to true)

    private fun notFound(ctx: ActionContext) = ctx.err(404, "Not found", "পাওয়া যায়নি")

    private fun str(v: JsonNode?, n: Int) = RxRules.str(v, n)

    private fun id(ctx: ActionContext, key: String = "id"): String {
        val v = ctx.body.string(key)
        if (!Rules.isValidId(v)) throw notFound(ctx)
        return v!!
    }

    /** myLab: own lab, approved */
    private fun myLab(ctx: ActionContext): Map<String, Any?> {
        val x = labs.lab(ctx.user.id)
        if (x == null || x["status"] != "approved") throw ctx.err(403, "Only approved labs can do this", "শুধু অনুমোদিত প্যাথলজি এটা করতে পারে")
        return x
    }

    private fun homeCollection(x: Map<String, Any?>?): Boolean = x?.get("homeCollection") == true

    private fun requireAdmin(ctx: ActionContext) {
        if (!admins.isAdmin(ctx.user)) throw ctx.err(403, "Admins only", "শুধু অ্যাডমিন")
    }

    private fun isDistrict(v: JsonNode?): String? = Js.string(v)?.takeIf { Places.isDistrict(it) }

    private fun rxData(id: String): Map<String, Any?> = linkedMapOf("type" to "rx", "id" to id, "rxId" to id)

    // ------------------------------------------------------------------ catalogue (labtests.js)

    fun labtestsGet(ctx: ActionContext): Any = linkedMapOf("tests" to catalog.tests())

    fun adminLabtestsSet(ctx: ActionContext): Any {
        requireAdmin(ctx)
        val list = catalog.tests()
        val t: JsonNode = ctx.body["test"]?.takeIf { Js.truthy(it) } ?: ctx.body.node.objectNode()
        val askedCode = Js.string(t.get("code"))
        val exists = askedCode != null && list.any { LabTests.code(it) == askedCode }
        if (!exists && list.size >= LabTests.MAX_TESTS) {
            throw ctx.err(400, "Up to ${LabTests.MAX_TESTS} tests", "সর্বোচ্চ ${LabTests.MAX_TESTS}টা পরীক্ষা")
        }
        val input: JsonNode = if (exists) t else (if (t is ObjectNode) t.deepCopy() else ctx.body.node.objectNode()).apply { putNull("code") }
        val clean = LabTests.cleanTest(ctx.lang, input, list.map { LabTests.code(it) }.toSet())
        val cleanKeys = (listOf(LabTests.name(clean)) + LabTests.aliases(clean)).map { LabTests.norm(it) }
        val clash = list.firstOrNull { x ->
            LabTests.code(x) != LabTests.code(clean) && !LabTests.hidden(x) && !LabTests.hidden(clean) &&
                (listOf(LabTests.name(x)) + LabTests.aliases(x)).map { LabTests.norm(it) }.any { it in cleanKeys }
        }
        if (clash != null) {
            val n = LabTests.name(clash)
            throw ctx.err(409, "This name is already used by \"$n\"", "এই নাম আগেই \"$n\"-এ আছে")
        }
        val next = if (exists) list.map { if (LabTests.code(it) == LabTests.code(clean)) clean else it } else list + listOf(clean)
        catalog.save(next)
        return linkedMapOf("test" to clean, "tests" to next)
    }

    // ------------------------------------------------------------------ admin panel

    fun adminLabs(ctx: ActionContext): Any {
        requireAdmin(ctx)
        val asked = ctx.body.string("status")
        val status = if (asked in listOf("pending", "approved", "suspended", "rejected")) asked!! else "pending"
        val rowsList = jdbc.query(
            "SELECT * FROM labs WHERE status = ? ORDER BY uid COLLATE \"C\" LIMIT 300",
            { rs, _ -> rs.getString("uid") to listing.view(ListingKind.LAB, rs) },
            status,
        )
        val items = rowsList.map { (id, x) ->
            val upazila = (x["upazilaId"] as? Number)?.toInt()?.let { Places.UPAZILA[it]?.bn }
            LabStore.labView(id, x, true).apply {
                put("areaLabel", listOfNotNull(upazila, x["district"] as? String).filter { it.isNotEmpty() }.joinToString(", "))
                put("ownerUid", id)
                put("createdAt", x["createdAt"]?.takeIf { it != 0L })
                put("reviewedAt", x["reviewedAt"]?.takeIf { it != 0L })
                put("changes", ListingRules.changedFields(x).map { c -> linkedMapOf("field" to c.field, "before" to c.before, "after" to c.after) })
                if (status == "pending" || status == "rejected") {
                    put("verify", runCatching { verify.panelInfo("lab", id, x) }.getOrNull())
                }
                put("verification", x["verification"])
            }
        }
        return linkedMapOf("items" to items)
    }

    fun adminLabResolve(ctx: ActionContext): Any {
        requireAdmin(ctx)
        return review.resolve(ctx, "lab", "labs", ctx.body.string("id"))
    }

    // ------------------------------------------------------------------ the lab's own listing

    fun labMe(ctx: ActionContext): Any {
        val uid = ctx.user.id
        val x = labs.lab(uid) ?: return linkedMapOf("lab" to null)
        val v = LabStore.labView(uid, x, true)
        val st = x["status"]
        val docs: Map<String, Any?> = if (st == "pending" || st == "rejected" || ListingRules.jsTruthy(x["docsRequestedAt"])) {
            runCatching { verify.ownerInfo("lab", uid, x) }.getOrElse { emptyMap() }
        } else {
            emptyMap()
        }
        v.putAll(docs)
        v["docsRequestNote"] = (x["docsRequestNote"] as? String) ?: ""
        // LISTINGS.md "রোগীরা আপনাকে যেভাবে দেখেন": the card facts of the public list + the open-now override
        val now = System.currentTimeMillis()
        ListingFacts.putTextHours(v, x["hours"] as? String, now)
        val ov = switches.labOverrides(listOf(uid), now)[uid]
        if (ov != null) v["openNow"] = ov["open"]
        v["openOverride"] = ov
        v["ratingSummary"] = null
        ListingFacts.putDistance(v, null, null, null, false)
        return linkedMapOf("lab" to v)
    }

    /** register/edit: DGHS centre (facilityId) or own name, licence, hours, home collection, document photos */
    fun labSave(ctx: ActionContext): Any {
        val b = ctx.body
        val uid = ctx.user.id
        val cur = labs.lab(uid)
        val facId = b.string("facilityId")?.takeIf { it.isNotEmpty() }
        val fac = facId?.let { hospitals.findHospital(it) ?: throw ctx.err(400, "Choose the centre from the list again", "তালিকা থেকে সেন্টারটা আবার বেছে নিন") }
        val name = Rules.cleanText(if (fac != null) fac.nameBn.ifEmpty { fac.nameEn } else b.string("name"), 120)
            ?: throw ctx.err(400, "Write the centre name", "সেন্টারের নাম লিখুন")
        val district = if (fac != null) fac.district else b.string("district")
        if (!Places.isDistrict(district)) throw ctx.err(400, "Choose a district", "জেলা বেছে নিন")
        val upazilaId = if (fac != null) fac.upazilaId?.takeIf { it != 0 } else Places.upazilaIn(district, b.int("upazilaId"))
        val addressRaw: String? = if (Js.truthy(b["address"])) b.string("address") else fac?.address ?: ""
        val address = Rules.cleanText(addressRaw, 200) ?: throw ctx.err(400, "Write the address", "ঠিকানা লিখুন")
        val licenseNo = Rules.cleanText(b.string("licenseNo"), 60)
            ?: throw ctx.err(400, "Enter the DGHS licence number", "স্বাস্থ্য অধিদপ্তরের লাইসেন্স নম্বর দিন")
        val phone = Phones.normalizeBd(Js.toStr(b["phone"])) ?: throw ctx.err(400, "Enter a valid mobile number", "সঠিক মোবাইল নম্বর দিন")
        if (!b.isTrue("termsAccepted")) throw ctx.err(400, "Please accept the terms", "শর্তে সম্মতি দিন")
        val now = System.currentTimeMillis()
        val data = linkedMapOf<String, Any?>(
            "name" to name, "facilityId" to fac?.id, "district" to district, "upazilaId" to upazilaId, "address" to address,
            "licenseNo" to licenseNo, "phone" to Phones.localBd(phone), "hours" to str(b["hours"], 80),
            "homeCollection" to b.isTrue("homeCollection"), "updatedAt" to now,
        )
        val set = SqlSet()
            .value("name", name).value("facility_id", fac?.id).value("district", district).value("upazila_id", upazilaId)
            .value("address", address).value("license_no", licenseNo).value("phone", Phones.localBd(phone))
            .value("hours", data["hours"]).value("home_collection", data["homeCollection"]).ms("updated_at", now)
        com.lifolo.demo.DemoLock.checkRename(ctx.lang, uid, cur != null && cur["name"] != name)
        if (cur != null) {
            if (cur["status"] == "suspended") {
                throw ctx.err(403, "Your lab is suspended. You can appeal from Profile → Help & appeals.", "আপনার প্যাথলজি বন্ধ করা আছে। প্রোফাইলের \"সাহায্য ও আপিল\" থেকে আপিল করুন।")
            }
            val rv = ListingRules.reviewNeeded(cur, data, listOf("licenseNo", "phone", "district", "facilityId"))
            verify.save(ctx.lang, "lab", uid, uid, cur + data, b["docs"], false)
            if (rv.approvedName != null) set.value("approved_name", rv.approvedName)
            ListingRules.keepApproved(cur, data, rv.changed)?.let { set.json("last_approved", listing.toJson(it)) }
            if (rv.changed) set.value("status", "pending")
            set.update(jdbc, "labs", "uid = ?", uid)
            return linkedMapOf("ok" to true, "pending" to (rv.changed || cur["status"] == "pending"))
        }
        verify.validate(ctx.lang, "lab", data, b["docs"], true)
        quotas.use(ctx.lang, uid, "lab")
        val docsHave = verify.save(ctx.lang, "lab", uid, uid, data, b["docs"], true)
        jdbc.update(
            """
            INSERT INTO labs (uid, name, facility_id, district, upazila_id, address, license_no, phone, hours, home_collection, terms_accepted,
              status, docs_have, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, 'pending', ?::text[], ?, ?)
            """.trimIndent(),
            uid, name, fac?.id, district, upazilaId, address, licenseNo, Phones.localBd(phone), data["hours"], data["homeCollection"],
            P3Sql.pgArray(docsHave.orEmpty()), P3Sql.ts(now), P3Sql.ts(now),
        )
        return linkedMapOf("ok" to true, "pending" to true)
    }

    fun labDelete(ctx: ActionContext): Any {
        val uid = ctx.user.id
        val x = labs.lab(uid) ?: return ok()
        if (x["status"] == "suspended") throw ctx.err(403, "A suspended lab can't be removed", "বন্ধ করা প্যাথলজি সরানো যায় না")
        jdbc.update("DELETE FROM labs WHERE uid = ?", uid)
        runCatching { verify.delete("lab", uid) }
        // the price list goes with the lab row; the patients' home address/phone leaves the open orders
        store.stripHomes(uid, openOnly = true)
        labs.clearGuestCache()
        return ok()
    }

    // ------------------------------------------------------------------ public lists and prices

    /** approved labs of a district (services tab) with a price summary */
    fun labsList(ctx: ActionContext): Any {
        val b = ctx.body
        val district = isDistrict(b["district"]) ?: throw ctx.err(400, "Choose a district", "জেলা বেছে নিন")
        val upazilaId = b.int("upazilaId")
        val area = labs.areaLabs(district)
        val list = catalog.tests()
        val hidden = LabPrices.hiddenCodes(list)
        val pm = store.pricesOf(area.map { it.first })
        // LISTINGS.md: shared facts — hours parsed from the free text, distance to the lab's upazila centre (labs have
        // no coordinates), no ratings; q, filters, sort
        val now = System.currentTimeMillis()
        val origin = ListingFacts.origin(b)
        val q = ListingFacts.query(b)
        // the owner's "খোলা/বন্ধ এখন" while it lasts wins over the hours
        val overrides = switches.labOverrides(area.map { it.first }, now)
        var items = area.filter { (_, x) -> ListingFacts.matches(q, listOf(x["name"] as? String, x["address"] as? String)) }.map { (id, x) ->
            LabStore.labView(id, x).apply {
                put("prices", LabPrices.publicSummary(pm[id], homeCollection(x), hidden))
                ListingFacts.putTextHours(this, x["hours"] as? String, now)
                overrides[id]?.let { put("openNow", it["open"]) }
                put("ratingSummary", null)
                val c = ListingFacts.upazilaCentre((x["upazilaId"] as? Number)?.toInt())
                ListingFacts.putDistance(this, origin, c?.first, c?.second, false)
            }
        }
        if (b.isTrue("homeCollection")) items = items.filter { it["homeCollection"] == true }
        if (b.isTrue("openNow")) items = items.filter { it["openNow"] == true }
        if (upazilaId != null && upazilaId != 0L) {
            items = items.filter { (it["upazilaId"] as? Int)?.toLong() == upazilaId } + items.filter { (it["upazilaId"] as? Int)?.toLong() != upazilaId }
        }
        val fac = b["facilityId"]
        if (Js.truthy(fac)) {
            val f = Js.string(fac)
            items = items.sortedByDescending { f != null && it["facilityId"] == f }
        }
        return linkedMapOf("items" to ListingFacts.sorted(items, ListingFacts.sort(b)) { it }.take(100))
    }

    fun labPricesGet(ctx: ActionContext): Any {
        val lab = myLab(ctx)
        val hidden = LabPrices.hiddenCodes(catalog.tests())
        return LabPrices.ownerView(store.prices(ctx.user.id), homeCollection(lab), hidden)
    }

    /** the whole list at once (what is sent stays); all empty → the list is removed */
    fun labPricesSet(ctx: ActionContext): Any {
        val lab = myLab(ctx)
        val b = ctx.body
        val raw = b["items"]?.takeIf { it.isArray } ?: throw ctx.err(400, "Nothing to save", "সেভ করার কিছু নেই")
        if (raw.size() > LabPrices.MAX_ITEMS) {
            throw ctx.err(400, "Up to ${LabPrices.MAX_ITEMS} tests", "সর্বোচ্চ ${Lang.bnDigits(LabPrices.MAX_ITEMS.toString())}টা পরীক্ষা")
        }
        val list = catalog.tests()
        val cleaned = LabPrices.cleanItems(ctx.lang, raw.toList(), list)
        val d = LabPrices.toInt(b["discountPct"])
        if (d != null && (d.isNaN() || d < 0 || d > 90)) throw ctx.err(400, "Discount must be 0–90%", "ছাড় ০–৯০% এর মধ্যে দিন")
        val fee = LabPrices.toInt(b["homeFee"])
        if (fee != null && (fee.isNaN() || fee < 0 || fee > LabPrices.MAX_HOME_FEE)) {
            throw ctx.err(400, "Check the home collection fee", "বাসা থেকে নমুনার চার্জ দেখে নিন")
        }
        val doc = PriceList(
            cleaned.items, d?.toInt()?.takeIf { it != 0 }, if (homeCollection(lab) && fee != null) fee.toInt() else null, System.currentTimeMillis(),
        )
        val keep = doc.items.isNotEmpty() || doc.discountPct != null || doc.homeFee != null
        store.savePrices(ctx.user.id, if (keep) doc else null)
        labs.clearGuestCache()
        val hidden = LabPrices.hiddenCodes(list)
        return LabPrices.ownerView(if (keep) doc else null, homeCollection(lab), hidden).apply { put("kept", cleaned.kept) }
    }

    /** everyone (guests too): one approved lab's full price list */
    fun labPricesPublic(ctx: ActionContext): Any {
        val labId = ctx.body.string("labId")
        if (!Rules.isValidId(labId)) throw notFound(ctx)
        val list = catalog.tests()
        val x = labs.lab(labId!!)
        if (x == null || x["status"] != "approved") throw notFound(ctx)
        return linkedMapOf("prices" to LabPrices.publicView(store.prices(labId), homeCollection(x), LabPrices.hiddenCodes(list)))
    }

    // ------------------------------------------------------------------ the patient sends tests

    /**
     * rx_lab_options: which labs do the prescription's tests (picked ones, else all), cost, the doctor's suggested
     * place first, then most tests, lowest cost, same upazila, distance; lastHome for the form.
     */
    fun rxLabOptions(ctx: ActionContext): Any {
        val b = ctx.body
        val uid = ctx.user.id
        val rxId = b.string("rxId")
        if (!Rules.isValidId(rxId)) throw notFound(ctx)
        val s = rx.get(rxId!!)
        if (s == null || s.patientUid != uid) throw notFound(ctx)
        val district = isDistrict(b["district"]) ?: throw ctx.err(400, "Choose a district", "জেলা বেছে নিন")
        quotas.use(ctx.lang, uid, "rxPharm")
        val all = s.tests
        val asked = b["tests"]
        val picked = (if (asked != null && asked.isArray) asked.mapNotNull { Js.string(it) } else all).filter { it in all }
        val wanted = picked.ifEmpty { all }
        val upazilaId = b.int("upazilaId")
        val place = s.testPlaceId?.takeIf { it.isNotEmpty() }
        val list = catalog.tests()
        val total = LabPrices.countTests(wanted, list)
        val placeId = b.string("placeId")?.takeIf { Rules.isValidId(it) }
        val area = if (placeId != null) labs.onePlace(placeId, district) else labs.areaLabs(district)
        val pm = store.pricesOf(area.map { it.first })
        val items = area.filter { DemoWorld.sameWorld(uid, it.first) }.map { (id, x) ->
            val px = pm[id]
            val e = LabPrices.estimate(wanted, px, homeCollection(x), list)
            LabStore.labView(id, x).apply {
                put("suggested", place != null && x["facilityId"] == place)
                put("near", upazilaId != null && upazilaId != 0L && (x["upazilaId"] as? Number)?.toLong() == upazilaId)
                put("km", null)
                put("priced", e != null)
                put("have", e?.have)
                put("total", e?.total ?: total)
                put("missing", e?.missing ?: emptyList<String>())
                put("items", e?.items?.map { it.toMap() } ?: emptyList<Any>())
                put("subtotal", e?.subtotal)
                put("discountPct", e?.discountPct)
                put("estimate", e?.estimate)
                put("homeFee", if (homeCollection(x) && px != null) px.homeFee else null)
                put("tat", e?.tat ?: "")
            }
        }
        fun rank(o: Map<String, Any?>): Int = if (o["priced"] == true) (o["have"] as Int) * 2 else 1
        val sorted = items.sortedWith(
            compareByDescending<LinkedHashMap<String, Any?>> { it["suggested"] == true }
                .thenByDescending { rank(it) }
                .thenBy { (it["estimate"] as? Int)?.toDouble() ?: Double.POSITIVE_INFINITY }
                .thenByDescending { it["near"] == true }
                .thenBy { (it["km"] as? Number)?.toDouble() ?: 999.0 },
        )
        return linkedMapOf("items" to sorted.take(60), "lastHome" to labs.lastHome(uid))
    }

    /** the patient sends the prescription's tests to a lab (home: optional sample collection from home) */
    fun rxSendLab(ctx: ActionContext): Any {
        val b = ctx.body
        val uid = ctx.user.id
        val rxId = b.string("rxId")
        val labId = b.string("labId")
        if (!Rules.isValidId(rxId) || !Rules.isValidId(labId)) throw notFound(ctx)
        val s = rx.get(rxId!!)
        val l = labs.lab(labId!!)
        if (s == null || s.patientUid != uid) throw notFound(ctx)
        if (s.status != "active") throw ctx.err(409, "This prescription was cancelled", "এই প্রেসক্রিপশন বাতিল")
        if (l == null || l["status"] != "approved" || !DemoWorld.sameWorld(uid, labId)) throw ctx.err(404, "This lab is not available", "এই প্যাথলজি এখন নেই")
        val asked = b["tests"]
        val tests = (if (asked != null && asked.isArray) asked.mapNotNull { Js.string(it) } else s.tests).filter { it in s.tests }
        if (tests.isEmpty()) throw ctx.err(400, "Choose the tests", "পরীক্ষা বেছে নিন")
        val home = LabPrices.cleanHome(ctx.lang, b["home"], homeCollection(l))
        val prev = store.ordersBare("rx_id = ? AND lab_uid = ? AND status IN ('sent', 'received') LIMIT 1", rxId, labId)
        if (prev.isNotEmpty()) throw ctx.err(409, "Already sent to this lab", "এই প্যাথলজিতে আগেই পাঠানো আছে")
        quotas.use(ctx.lang, uid, "rxSend")
        val px = store.prices(labId)
        val est = LabPrices.estimate(tests, px, homeCollection(l), catalog.tests())
        val now = System.currentTimeMillis()
        val id = store.insertOrder(
            LabStore.NewOrder(
                rxId = rxId, code = s.code, labId = labId, labName = (l["name"] as? String) ?: "", patientUid = uid,
                patientName = s.patient["name"] as? String ?: "", patientAge = (s.patient["age"] as? Number)?.toInt(),
                patientGender = s.patient["gender"] as? String ?: "", doctorUid = s.doctorUid, doctorName = s.doctor["name"] as? String ?: "",
                tests = tests, status = "sent", createdAt = now, walkIn = false, byCaretaker = ctx.user.actingBy,
                prices = LabPrices.snapshot(est, now), home = home?.copy(fee = px?.homeFee),
            ),
        )
        if (home != null) labs.rememberHome(uid, home)
        val head = "${s.patient["name"] as? String ?: ""} · ${tests.joinToString(", ")}"
        val line = LabPrices.homePushLine(home)
        push.one(
            labId,
            if (home != null) "New test request — sample from home" else "New test request",
            if (home != null) "নতুন পরীক্ষা — বাসা থেকে নমুনা" else "নতুন পরীক্ষার অনুরোধ",
            if (line != null) "${line.first} · $head" else head,
            if (line != null) "${line.second} · $head" else head,
            linkedMapOf("type" to "rx_lab", "id" to id, "rxId" to rxId),
        )
        return linkedMapOf("id" to id)
    }

    // ------------------------------------------------------------------ the lab's work

    /** the lab's incoming orders (no photos), segments and counts */
    fun labOrders(ctx: ActionContext): Any {
        myLab(ctx)
        val all = store.ordersBare("lab_uid = ? ORDER BY created_at DESC, id COLLATE \"C\" LIMIT 400", ctx.user.id)
        val picked = LabViews.recentFirstOpen(all, { it.createdAt }, { LabViews.labSeg(it) in setOf("new", "active") }, 150)
        val now = System.currentTimeMillis()
        val items = store.withChildren(picked).map { o -> LabViews.orderView(o, false, "lab", now).apply { put("seg", LabViews.labSeg(o)) } }
        return linkedMapOf("items" to items, "counts" to LabViews.segCounts(LabViews.LAB_SEGS, all.map { LabViews.labSeg(it) }))
    }

    /** the lab opens a paper prescription by code → a walk-in order (sample taken) */
    fun labOpenCode(ctx: ActionContext): Any {
        val lab = myLab(ctx)
        val uid = ctx.user.id
        quotas.use(ctx.lang, uid, "rxPharm")
        val d: Prescription = rx.byCode(ctx.lang, Js.toStr(ctx.body["code"]), uid)
        if (d.status != "active") throw ctx.err(409, "This prescription was cancelled", "এই প্রেসক্রিপশন বাতিল")
        if (d.tests.isEmpty()) throw ctx.err(400, "No tests on this prescription", "এই প্রেসক্রিপশনে কোনো পরীক্ষা নেই")
        val mine = store.orders("rx_id = ? AND lab_uid = ? AND status <> 'cancelled' ORDER BY id COLLATE \"C\" LIMIT 1", d.id, uid).firstOrNull()
        if (mine != null) return linkedMapOf("item" to LabViews.orderView(mine, false, "lab"))
        val est = LabPrices.estimate(d.tests, store.prices(uid), homeCollection(lab), catalog.tests())
        val now = System.currentTimeMillis()
        val id = store.insertOrder(
            LabStore.NewOrder(
                rxId = d.id, code = d.code, labId = uid, labName = (lab["name"] as? String) ?: "", patientUid = d.patientUid,
                patientName = d.patient["name"] as? String ?: "", patientAge = (d.patient["age"] as? Number)?.toInt(),
                patientGender = d.patient["gender"] as? String ?: "", doctorUid = d.doctorUid, doctorName = d.doctor["name"] as? String ?: "",
                tests = d.tests, status = "received", createdAt = now, walkIn = true, byCaretaker = null,
                prices = LabPrices.snapshot(est, now), home = null,
            ),
        )
        return linkedMapOf("item" to LabViews.orderView(store.order(id)!!, false, "lab"))
    }

    /** sent → received (sample taken); sent/received → cancelled (a cancelled one never reopens) */
    fun labOrderStatus(ctx: ActionContext): Any {
        myLab(ctx)
        val id = ctx.body.string("id")
        val status = ctx.body.string("status")
        if (!Rules.isValidId(id) || (status != "received" && status != "cancelled")) throw notFound(ctx)
        val o = store.order(id!!)
        if (o == null || o.labId != ctx.user.id || o.status == "reported") throw notFound(ctx)
        val allowed = (status == "received" && o.status == "sent") || (status == "cancelled" && (o.status == "sent" || o.status == "received"))
        if (!allowed) throw ctx.err(409, "This request can't be changed now", "এই অনুরোধ এখন আর বদলানো যায় না")
        jdbc.update("UPDATE lab_orders SET status = ?, updated_at = now() WHERE id = ?", status, id)
        if (status == "cancelled") {
            push.patient(o.patientUid, "${o.labName} can't do your tests", "${o.labName} পরীক্ষা করতে পারবে না", "", rxData(o.rxId ?: ""))
        }
        return ok()
    }

    /** the order of this lab with a home part (lab_home_confirm / decline) */
    private fun homeOrderOfLab(ctx: ActionContext): LabOrder {
        myLab(ctx)
        val id = id(ctx)
        val o = store.order(id)
        if (o == null || o.labId != ctx.user.id || o.home == null) throw notFound(ctx)
        if (o.status != "sent" || o.home.status == "declined") {
            throw ctx.err(409, "The home visit can't be changed now", "এখন আর বাসায় যাওয়ার সময় বদলানো যায় না")
        }
        return o
    }

    /** the lab confirms the home visit: date (UTC midnight), time "HH:MM", note — patient and caregivers are told */
    fun labHomeConfirm(ctx: ActionContext): Any {
        val o = homeOrderOfLab(ctx)
        val v = LabPrices.cleanVisit(ctx.lang, ctx.body["date"], ctx.body["time"])
        val note = str(ctx.body["note"], 120)
        val now = System.currentTimeMillis()
        jdbc.update(
            """
            UPDATE lab_orders SET home_status = 'confirmed', home_visit_date = ?, home_time = ?, home_at = ?, home_confirm_note = ?,
              home_confirmed_at = ?, updated_at = ? WHERE id = ?
            """.trimIndent(),
            P3Sql.date(v.visitDate), v.time, P3Sql.ts(v.at), note, P3Sql.ts(now), P3Sql.ts(now), o.id,
        )
        val (wEn, wBn) = LabPrices.visitWords(v.visitDate, v.time, now)
        val lab = o.labName
        push.patient(o.patientUid, "$lab will come $wEn", "$lab $wBn আসবে", note, rxData(o.rxId ?: ""))
        return linkedMapOf("ok" to true, "at" to v.at)
    }

    /** the lab can't come home: the order stays (the patient comes to the lab); the address/phone go now */
    fun labHomeDecline(ctx: ActionContext): Any {
        val o = homeOrderOfLab(ctx)
        val note = str(ctx.body["note"], 120)
        val now = System.currentTimeMillis()
        jdbc.update(
            """
            UPDATE lab_orders SET home_status = 'declined', home_decline_note = ?, home_declined_at = ?, home_address = NULL, home_phone = NULL,
              updated_at = ? WHERE id = ?
            """.trimIndent(),
            note, P3Sql.ts(now), P3Sql.ts(now), o.id,
        )
        val lab = o.labName
        push.patient(
            o.patientUid, "$lab can't come to your home — give the sample at the lab", "$lab বাসায় আসতে পারবে না — প্যাথলজিতে গিয়ে নমুনা দিন", note,
            rxData(o.rxId ?: ""),
        )
        return ok()
    }

    /** the patient's own home request that the lab has not answered yet */
    private fun myHomeRequest(ctx: ActionContext): LabOrder {
        val id = id(ctx)
        val o = store.order(id)
        if (o == null || o.patientUid != ctx.user.id || o.home == null) throw notFound(ctx)
        if (o.status != "sent" || o.home.status != "requested") {
            throw ctx.err(409, "The lab has already answered — call the lab to change", "প্যাথলজি এর মধ্যে জানিয়েছে — বদলাতে প্যাথলজিতে ফোন করুন")
        }
        return o
    }

    /** the patient (or caregiver) cancels the home visit before the lab answered: a normal order again */
    fun labHomeCancel(ctx: ActionContext): Any {
        val o = myHomeRequest(ctx)
        val now = System.currentTimeMillis()
        store.setHome(o.id, null, now)
        jdbc.update("UPDATE lab_orders SET home_cancelled_at = ? WHERE id = ?", P3Sql.ts(now), o.id)
        push.one(
            o.labId, "No home visit — the patient will come to the lab", "বাসায় যেতে হবে না — রোগী প্যাথলজিতে এসে নমুনা দেবেন",
            "${o.patientName} · ${o.tests.joinToString(", ")}", linkedMapOf("type" to "rx_lab", "id" to o.id, "rxId" to o.rxId),
        )
        return ok()
    }

    /** the patient (or caregiver) changes day/time/address before the lab answered */
    fun labHomeChange(ctx: ActionContext): Any {
        val o = myHomeRequest(ctx)
        val l = labs.lab(o.labId)
        if (l == null || l["status"] != "approved") throw ctx.err(404, "This lab is not available", "এই প্যাথলজি এখন নেই")
        val now = System.currentTimeMillis()
        val home = LabPrices.cleanHome(ctx.lang, ctx.body["home"], homeCollection(l), now)
            ?: throw ctx.err(400, "Choose a time", "কোন সময়ে আসবে বেছে নিন")
        store.setHome(o.id, home.copy(fee = o.home?.fee), now)
        labs.rememberHome(ctx.user.id, home)
        val line = LabPrices.homePushLine(home, now)!!
        val head = "${o.patientName} · "
        push.one(
            o.labId, "The patient changed the home visit", "রোগী বাসা থেকে নমুনার সময় বদলেছেন", head + line.first, head + line.second,
            linkedMapOf("type" to "rx_lab", "id" to o.id, "rxId" to o.rxId),
        )
        return ok()
    }

    private val PHOTO_RE = Regex("^[A-Za-z0-9+/=\\s]+$")
    private val CODE_RE = Regex("^[a-z0-9_]{1,40}$")
    private val KEY_RE = Regex("^[a-z0-9_]{1,30}$")

    /** results (items, note, ≤ 2 small photos) → reported; the patient is told; a review request for the doctor */
    fun labReport(ctx: ActionContext): Any {
        myLab(ctx)
        val id = id(ctx)
        val o = store.order(id)
        if (o == null || o.labId != ctx.user.id || o.status == "cancelled") throw notFound(ctx)
        val rawItems = ctx.body["items"]?.takeIf { it.isArray }?.toList()?.take(120) ?: emptyList()
        fun n(v: JsonNode?): Double? = v?.takeIf { it.isNumber }?.doubleValue()?.takeIf { it.isFinite() }
        val items = rawItems.map { r ->
            val low = n(r?.get("low"))
            val high = n(r?.get("high"))
            val result = str(r?.get("result"), if (Js.truthy(r?.get("long"))) 2000 else 60)
            val auto = if (low != null || high != null) LabTests.flagOf(result, low, high) else null
            val askedFlag = Js.string(r?.get("flag"))?.takeIf { it == "high" || it == "low" || it == "abnormal" } ?: ""
            val flag = if (auto != null && RxRules.jsNumber(result.replace(",", "")).isFinite()) auto else askedFlag
            val group = Js.string(r?.get("group"))?.takeIf { it.isNotEmpty() }?.let { RxRules.str(it, 80) }
            ReportItem(
                test = str(r?.get("test"), 80), result = result, unit = str(r?.get("unit"), 30), range = str(r?.get("range"), 60), flag = flag,
                group = group, code = Js.string(r?.get("code"))?.takeIf { CODE_RE.matches(it) }, key = Js.string(r?.get("key"))?.takeIf { KEY_RE.matches(it) },
                low = low, high = high, long = r?.get("long")?.let { it.isBoolean && it.booleanValue() } ?: false,
            )
        }.filter { it.test.isNotEmpty() && it.result.isNotEmpty() }
        val photos = (ctx.body["photos"]?.takeIf { it.isArray }?.toList() ?: emptyList())
            .mapNotNull { Js.string(it) }.filter { it.length > 100 }.take(2)
        if (photos.any { it.length > MAX_PHOTO_B64 || !PHOTO_RE.matches(it) }) throw ctx.err(400, "Photos are too large", "ছবি অনেক বড়")
        if (items.isEmpty() && photos.isEmpty()) throw ctx.err(400, "Enter at least one result or a photo of the report", "অন্তত একটা ফল বা রিপোর্টের ছবি দিন")
        val now = System.currentTimeMillis()
        store.saveReport(id, items, str(ctx.body["note"], 500), photos, now)
        if (o.patientUid != null) {
            push.patient(
                o.patientUid, "Your test report is ready", "আপনার পরীক্ষার রিপোর্ট এসেছে", o.labName,
                linkedMapOf("type" to "rx", "id" to o.rxId, "rxId" to o.rxId, "labOrderId" to id),
            )
        }
        // the doctor's review list
        val st = labs.reviewSettings(o.doctorUid)
        val status = if (!st.enabled) "off" else if (st.noFee) "paid" else "unpaid"
        if (o.doctorUid != null) {
            store.upsertReview(o, status, st.fee, st.noFee, st.payNumber, st.payMethods, now)
            if (status == "paid") {
                push.one(o.doctorUid, "A report is waiting for you", "একটা রিপোর্ট দেখার অপেক্ষায়", o.patientName, linkedMapOf("type" to "rx_review", "rxId" to o.rxId, "labOrderId" to id))
            }
        }
        return ok()
    }

    /** reading a report: the patient (own), the lab (own), the doctor (paid or no fee — else 402) */
    fun labOrderGet(ctx: ActionContext): Any {
        val id = id(ctx)
        val uid = ctx.user.id
        val o = store.order(id, photos = true) ?: throw notFound(ctx)
        // a caregiver: the patient's own orders only — not the patient's lab work as a lab, or reports as a doctor
        if (ctx.user.patientSideOnly && o.patientUid != uid) throw notFound(ctx)
        var allowed = o.patientUid == uid || o.labId == uid
        if (!allowed && o.doctorUid == uid) {
            val r = store.review(id)
            allowed = r != null && r.status in LabViews.DOCTOR_SEES
            if (!allowed) throw ctx.err(402, "The patient hasn't paid for the report review yet", "রোগী এখনো রিপোর্ট দেখার টাকা দেননি")
        }
        if (!allowed) throw notFound(ctx)
        val who = if (o.patientUid == uid) "patient" else if (o.labId == uid) "lab" else null
        val history = store.reportHistory(o.patientUid, id, o.reportedAt ?: System.currentTimeMillis(), if (who == "lab") o.labId else null)
        return linkedMapOf("item" to LabViews.orderView(o, true, who), "history" to history)
    }

    /** the report form of each test of the order (the lab's own ranges first) */
    fun labForm(ctx: ActionContext): Any {
        myLab(ctx)
        val id = id(ctx)
        val o = store.ordersBare("id = ?", id).firstOrNull()
        if (o == null || o.labId != ctx.user.id) throw notFound(ctx)
        val list = catalog.tests()
        val ranges = store.ranges(ctx.user.id)
        return linkedMapOf("forms" to o.tests.map { LabTests.formFor(it, o.patientGender, ranges, list) })
    }

    /** the lab's own normal ranges: [{code, key, low, high}] (both null = back to the template) */
    fun labRangesSet(ctx: ActionContext): Any {
        myLab(ctx)
        val uid = ctx.user.id
        val raw = ctx.body["ranges"]?.takeIf { it.isArray }?.toList()?.take(100) ?: emptyList()
        val cur = LinkedHashMap(store.ranges(uid))
        fun n(v: JsonNode?): Double? = v?.takeIf { it.isNumber }?.doubleValue()?.takeIf { it.isFinite() }
        for (r in raw) {
            val code = Js.string(r?.get("code"))
            val key = Js.string(r?.get("key"))
            if (code == null || !CODE_RE.matches(code) || key == null || !KEY_RE.matches(key)) continue
            val low = n(r.get("low"))
            val high = n(r.get("high"))
            val k = "$code.$key"
            if (low == null && high == null) {
                cur.remove(k)
            } else if (low != null && high != null && low > high) {
                throw ctx.err(400, "The low value is bigger than the high value", "নিচের মান ওপরের মানের চেয়ে বড়")
            } else {
                cur[k] = low to high
            }
        }
        if (cur.size > 500) throw ctx.err(400, "Too many ranges", "অনেক বেশি মাত্রা")
        store.saveRanges(uid, cur)
        return linkedMapOf("ok" to true, "count" to cur.size)
    }

    // ------------------------------------------------------------------ report review (paid, online)

    /** the doctor's settings: enabled, fee or no fee, payment number, bKash/Nagad/Rocket */
    fun reviewSettings(ctx: ActionContext): Any {
        val uid = ctx.user.id
        if (badges.approvedBadge(uid) != "doctor") throw ctx.err(403, "Doctors only", "শুধু ডাক্তার")
        val b = ctx.body
        if (b.isTrue("set")) {
            val enabled = b.isTrue("enabled")
            val noFee = b.isTrue("noFee")
            val fee = if (noFee) "" else str(b["fee"], 40)
            val payMethods = (b["payMethods"]?.takeIf { it.isArray }?.toList() ?: emptyList())
                .mapNotNull { Js.string(it) }.filter { it == "bkash" || it == "nagad" || it == "rocket" }
            val pn = Phones.normalizeBd(Js.toStr(b["payNumber"]))
            if (enabled && !noFee) {
                if (fee.isEmpty()) throw ctx.err(400, "Enter the fee, or tick \"No fee\"", "ফি দিন, অথবা \"ফি চাই না\" টিক দিন")
                if (pn == null) throw ctx.err(400, "Enter the mobile number for payment", "টাকা পাঠানোর মোবাইল নম্বর দিন")
                if (payMethods.isEmpty()) throw ctx.err(400, "Choose bKash, Nagad or Rocket", "bKash, Nagad বা Rocket বেছে নিন")
            }
            // Node set({reportReview}, {merge: true}) also creates the provider document
            jdbc.update("INSERT INTO providers (uid, type) VALUES (?, 'doctor') ON CONFLICT (uid) DO NOTHING", uid)
            jdbc.update(
                "UPDATE providers SET review_enabled = ?, review_no_fee = ?, review_fee = ?, review_pay_number = ?, review_pay_methods = ?::text[] WHERE uid = ?",
                enabled, noFee, fee, if (pn != null) Phones.localBd(pn) else "", P3Sql.pgArray(payMethods), uid,
            )
        }
        return labs.reviewSettings(uid).toMap()
    }

    /** the patient sends the TrxID */
    fun reviewPay(ctx: ActionContext): Any {
        val id = id(ctx)
        val r = store.review(id)
        if (r == null || r.patientUid != ctx.user.id) throw notFound(ctx)
        if (r.status != "unpaid") throw ctx.err(409, "Already sent", "আগেই পাঠানো হয়েছে")
        val trxId = RxRules.noSpaces(str(ctx.body["trxId"], 40))
        if (!Regex("^[A-Za-z0-9]{6,30}$").matches(trxId)) {
            throw ctx.err(400, "Enter the transaction ID from the payment SMS", "টাকা পাঠানোর SMS-এর TrxID দিন")
        }
        val asked = ctx.body.string("method")
        val method = if (asked != null && asked in r.payMethods) asked else r.payMethods.firstOrNull() ?: ""
        jdbc.update(
            "UPDATE rx_reviews SET status = 'claimed', trx_id = ?, method = ?, reject_note = NULL, updated_at = now() WHERE lab_order_id = ?",
            trxId, method, id,
        )
        push.one(
            r.doctorUid, "Check a report-review payment", "রিপোর্ট দেখার টাকা মিলিয়ে নিন", "${r.patientName} · $method · $trxId",
            linkedMapOf("type" to "rx_review", "rxId" to r.rxId, "labOrderId" to id),
        )
        return ok()
    }

    /** doctorOrAssistant: the doctor, or an assistant email of the doctor */
    private fun doctorOrAssistant(ctx: ActionContext): String {
        val uid = ctx.user.id
        val d = ctx.body.string("doctorUid")?.takeIf { it.isNotEmpty() } ?: uid
        if (d == uid) {
            if (badges.approvedBadge(uid) != "doctor") throw ctx.err(403, "Doctors only", "শুধু ডাক্তার")
            return d
        }
        if (!Rules.isValidId(d)) throw notFound(ctx)
        val email = if (ctx.user.emailVerified) ctx.user.email.lowercase() else ""
        if (email.isEmpty() || !assistants.isAssistant(d, email)) {
            throw ctx.err(403, "You are not this doctor's assistant", "আপনি এই ডাক্তারের সহকারী নন")
        }
        return d
    }

    /** the doctor/assistant: review requests with TrxIDs (no report) */
    fun reviewsDoctor(ctx: ActionContext): Any {
        val doctorUid = doctorOrAssistant(ctx)
        val items = store.reviews("doctor_uid = ? AND status <> 'off' ORDER BY updated_at DESC, lab_order_id COLLATE \"C\" LIMIT 150", doctorUid)
        return linkedMapOf("items" to items.map { it.view(true) })
    }

    /** the doctor/assistant: payment found (ok) or not */
    fun reviewConfirm(ctx: ActionContext): Any {
        val doctorUid = doctorOrAssistant(ctx)
        val id = id(ctx)
        val r = store.review(id)
        if (r == null || r.doctorUid != doctorUid || r.status != "claimed") throw notFound(ctx)
        val okPaid = ctx.body.isTrue("ok")
        val note = str(ctx.body["note"], 200)
        if (okPaid) {
            jdbc.update("UPDATE rx_reviews SET status = 'paid', paid_at = now(), updated_at = now() WHERE lab_order_id = ?", id)
        } else {
            jdbc.update("UPDATE rx_reviews SET status = 'unpaid', reject_note = ?, updated_at = now() WHERE lab_order_id = ?", note.ifEmpty { "TrxID মেলেনি" }, id)
        }
        push.patient(
            r.patientUid,
            if (okPaid) "The doctor received your payment and will check the report" else "The doctor couldn't find your payment",
            if (okPaid) "ডাক্তার টাকা পেয়েছেন, রিপোর্ট দেখবেন" else "ডাক্তার আপনার টাকা খুঁজে পাননি",
            if (okPaid) "" else note,
            rxData(r.rxId ?: ""),
        )
        return ok()
    }

    /** the doctor comments on the report (optionally a new prescription id) */
    fun reviewDone(ctx: ActionContext): Any {
        val uid = ctx.user.id
        if (badges.approvedBadge(uid) != "doctor") throw ctx.err(403, "Doctors only", "শুধু ডাক্তার")
        val id = id(ctx)
        val r = store.review(id)
        if (r == null || r.doctorUid != uid || r.status != "paid") throw notFound(ctx)
        val comment = str(ctx.body["comment"], 1000)
        if (comment.isEmpty()) throw ctx.err(400, "Write what the patient should know", "রোগীকে কী জানাবেন লিখুন")
        val newRxId = ctx.body.string("newRxId")?.takeIf { Rules.isValidId(it) }
        jdbc.update(
            "UPDATE rx_reviews SET status = 'done', comment = ?, new_rx_id = ?, done_at = now(), updated_at = now() WHERE lab_order_id = ?",
            comment, newRxId, id,
        )
        push.patient(r.patientUid, "The doctor checked your report", "ডাক্তার আপনার রিপোর্ট দেখেছেন", comment.take(120), rxData(r.rxId ?: ""))
        return ok()
    }

    companion object {
        const val MAX_PHOTO_B64 = 300 * 1024
    }
}
