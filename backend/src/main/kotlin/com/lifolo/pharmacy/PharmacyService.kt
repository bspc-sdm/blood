package com.lifolo.pharmacy

import com.lifolo.admin.kernel.ListingKind
import com.lifolo.admin.kernel.ListingReview
import com.lifolo.admin.kernel.ListingRows
import com.lifolo.admin.kernel.ListingRules
import com.lifolo.admin.kernel.ListingStats
import com.lifolo.admin.kernel.Moderation
import com.lifolo.admin.kernel.Sanctions
import com.lifolo.admin.kernel.VerifyDocs
import com.lifolo.auth.AdminService
import com.lifolo.common.Js
import com.lifolo.common.Phones
import com.lifolo.common.Places
import com.lifolo.common.QuotaService
import com.lifolo.common.Rules
import com.lifolo.common.SqlSet
import com.lifolo.common.action.ActionContext
import com.lifolo.common.ms
import com.lifolo.contracts.PatientHome
import com.lifolo.contracts.Prescription
import com.lifolo.contracts.Prescriptions
import com.lifolo.demo.DemoWorld
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.text.Collator
import java.util.Locale

/**
 * pharmacy.js — the pharmacy listing (save, pause, delete, area list with ratings and prescription match, calls,
 * aspect ratings, owner stats, admin list/resolve).
 */
@Service
class PharmacyService(
    private val jdbc: JdbcTemplate,
    private val shops: ShopRepo,
    private val stock: StockStore,
    private val rows: ListingRows,
    private val review: ListingReview,
    private val stats: ListingStats,
    private val moderation: Moderation,
    private val sanctions: Sanctions,
    private val verify: VerifyDocs,
    private val quotas: QuotaService,
    private val admins: AdminService,
    private val prescriptions: Prescriptions,
    private val patientHome: PatientHome,
    private val orders: RxOrderContacts,
    private val clock: P4Clock,
) {
    companion object {
        val ADMIN_STATUSES = listOf("pending", "approved", "suspended", "rejected")
        const val RATE_WINDOW_MS = 7 * 24 * 3600 * 1000L
        const val RATE_AFTER_MS = 60 * 60 * 1000L
        const val BOUGHT_WINDOW_MS = 7 * 24 * 3600 * 1000L
        const val RX_LIST_MAX = 40

        /** care.js MED_RADIUS_KM */
        const val MED_RADIUS_KM = 5.0

        private val TIME = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
        private val BN_ORDER: Collator = Collator.getInstance(Locale.forLanguageTag("bn"))

        /** pharmacy.js aspectsFor */
        fun aspectsFor(bought: String?): List<String> = when (bought) {
            "online" -> ASPECTS.toList()
            "shop" -> listOf("behaviour", "price")
            else -> listOf("behaviour")
        }

        /** pharmacy.js rxCard */
        fun rxCard(p: Map<String, Any?>, m: Map<String, Any?>): Map<String, Any?> {
            @Suppress("UNCHECKED_CAST")
            val e = m["estimate"] as Map<String, Any?>?
            val out = linkedMapOf<String, Any?>(
                "keepsStock" to m["keepsStock"], "have" to m["have"], "total" to m["total"], "missing" to m["missing"],
                "priced" to (e?.get("priced") ?: 0), "payable" to null, "mrp" to null, "discount" to null, "belowMin" to false,
            )
            if (e == null) return out
            out["payable"] = e["total"]
            if (e["mrp"] != null) {
                out["mrp"] = e["mrp"]
                out["discount"] = e["discount"]
                val min = (p["discountMin"] as? Number)?.toLong() ?: 0
                if (min > 0 && (e["mrp"] as Number).toLong() < min && (e["discount"] as Number).toLong() > 0) {
                    out["belowMin"] = true
                    out["payable"] = e["mrp"]
                    out["discount"] = 0
                }
            }
            return out
        }
    }

    private fun bad(ctx: ActionContext, en: String, bn: String) = ctx.err(400, en, bn)

    private fun requireAdmin(ctx: ActionContext) {
        if (!admins.isAdmin(ctx.user)) throw ctx.err(403, "Admins only", "শুধু অ্যাডমিন")
    }

    // ------------------------------------------------------------------ owner

    @Transactional(readOnly = true)
    fun me(ctx: ActionContext): Any {
        val s = shops.get(ctx.user.id) ?: return mapOf("pharmacy" to null)
        return mapOf("pharmacy" to withDocs(s, s.view(true, clock.now())))
    }

    /** pharmacy.js withDocs: which document photos are still missing (pending/rejected, or the admin asked) */
    private fun withDocs(s: Shop, v: LinkedHashMap<String, Any?>): Map<String, Any?> {
        if (s.status != "pending" && s.status != "rejected" && (s.docsRequestedAt ?: 0) == 0L) return v
        val listing = rows.load(ListingKind.PHARMACY, s.id) ?: return v
        val info = try {
            verify.ownerInfo("pharmacy", s.id, listing)
        } catch (e: Exception) {
            emptyMap()
        }
        return LinkedHashMap(v).apply {
            putAll(info)
            put("docsRequestNote", s.docsRequestNote)
        }
    }

    @Transactional
    fun save(ctx: ActionContext): Any {
        val b = ctx.body
        val uid = ctx.user.id
        val lang = ctx.lang
        val name = Rules.cleanText(b.string("name"), 100) ?: throw bad(ctx, "Enter the pharmacy name", "ফার্মেসির নাম লিখুন")
        val licenseNo = Rules.cleanText(b.string("licenseNo"), 40) ?: throw bad(ctx, "Enter the drug licence number", "ড্রাগ লাইসেন্স নম্বর দিন")
        val phone = Phones.normalizeBd(Js.toStr(b["phone"])) ?: throw bad(ctx, "Enter a valid mobile number", "সঠিক মোবাইল নম্বর দিন")
        val district = b.string("district")
        if (!Places.isDistrict(district)) throw bad(ctx, "Choose a district", "জেলা বেছে নিন")
        val upazilaId = Places.upazilaIn(district, b.int("upazilaId"))
        val address = Rules.cleanText(b.string("address"), 200) ?: throw bad(ctx, "Write the shop address", "দোকানের ঠিকানা লিখুন")
        val s = shops.get(uid, forUpdate = true)
        com.lifolo.demo.DemoLock.checkRename(lang, uid, s != null && s.name != name)
        var lat = b.number("lat")?.takeIf { it > 20.3 && it < 26.8 }?.let { Js.round(it * 1000) / 1000 }
        var lng = b.number("lng")?.takeIf { it > 87.9 && it < 92.8 }?.let { Js.round(it * 1000) / 1000 }
        if ((lat == null || lng == null) && s?.lat != null) {
            lat = s.lat
            lng = s.lng
        }
        if (lat == null || lng == null) {
            throw bad(ctx, "Set the shop location (stand in the shop and tap \"Use current location\")", "দোকানের লোকেশন দিন (দোকানে দাঁড়িয়ে \"বর্তমান লোকেশন\" চাপুন)")
        }
        val open24 = b.isTrue("open24")
        val openAt = Js.toStr(b["openAt"])
        val closeAt = Js.toStr(b["closeAt"])
        if (!open24 && (!TIME.matches(openAt) || !TIME.matches(closeAt) || openAt == closeAt)) {
            throw bad(ctx, "Set opening and closing time", "খোলা আর বন্ধের সময় দিন")
        }
        val discount = b.int("discount")?.takeIf { it in 0..30 }?.toInt() ?: 0
        fun taka(k: String): Int = b.int(k)?.takeIf { it in 0..100000 }?.toInt() ?: 0
        if (!b.isTrue("termsAccepted")) throw bad(ctx, "Please accept the terms", "শর্তে সম্মতি দিন")
        val now = clock.now()

        // the Node `data` object (camelCase) for sanctions/review; the SQL columns next to it
        val data = linkedMapOf<String, Any?>(
            "name" to name, "licenseNo" to licenseNo, "phone" to Phones.localBd(phone), "district" to district, "upazilaId" to upazilaId,
            "address" to address, "lat" to lat, "lng" to lng, "open24" to open24,
            "openAt" to if (open24) "" else openAt, "closeAt" to if (open24) "" else closeAt,
        )
        val set = SqlSet().value("name", name).value("license_no", licenseNo).value("phone", Phones.localBd(phone))
            .value("district", district).value("upazila_id", upazilaId).value("address", address).value("lat", lat).value("lng", lng)
            .value("open24", open24).value("open_at", data["openAt"]).value("close_at", data["closeAt"])
        val closed = b["closedDays"]
        if (closed != null && closed.isArray) {
            val days = ListingRules.pickClosedDays(closed.map { n -> if (Js.isInteger(n)) Js.integer(n)!! else n.toString() })
            data["closedDays"] = days
            set.intArray("closed_days", days, "int2")
        }
        val deliveryNote = ListingRules.str(b.string("deliveryNote"), 120)
        data["delivery"] = b.isTrue("delivery")
        data["deliveryNote"] = deliveryNote
        data["discount"] = discount
        set.value("delivery", b.isTrue("delivery")).value("delivery_note", deliveryNote).value("discount", discount)
        if (b.has("discountMin")) {
            data["discountMin"] = taka("discountMin")
            set.value("discount_min", taka("discountMin"))
        }
        if (b.has("freeDeliveryOver")) {
            data["freeDeliveryOver"] = taka("freeDeliveryOver")
            set.value("free_delivery_over", taka("freeDeliveryOver"))
        }
        if (b.has("discountNote")) {
            val note = ListingRules.str(b.string("discountNote")?.replace(Regex("\\s+"), " ") ?: "", 80)
            data["discountNote"] = note
            set.value("discount_note", note)
        }
        data["updatedAt"] = now
        set.ms("updated_at", now)

        if (s != null) {
            if (s.status == "suspended") {
                throw ctx.err(
                    403,
                    "Your pharmacy is suspended. You can appeal from Profile → Help & appeals.",
                    "আপনার ফার্মেসি বন্ধ করা আছে। প্রোফাইলের \"সাহায্য ও আপিল\" থেকে আপিল করতে পারেন।",
                )
            }
            sanctions.checkListing(lang, "pharmacy", data, ctx.user.email, false)
            val current = rows.load(ListingKind.PHARMACY, uid) ?: throw ctx.notFound()
            val r = ListingRules.reviewNeeded(current, data, listOf("licenseNo", "phone", "district"))
            verify.save(lang, "pharmacy", uid, uid, current + data, b["docs"], false)
            if (r.approvedName != null) set.value("approved_name", r.approvedName)
            ListingRules.keepApproved(current, data, r.changed)?.let { set.json("last_approved", rows.toJson(it)) }
            if (r.changed) set.value("status", "pending")
            set.update(jdbc, "pharmacies", "uid = ?", uid)
            return linkedMapOf("ok" to true, "pending" to (r.changed || s.status == "pending"))
        }
        sanctions.checkListing(lang, "pharmacy", data, ctx.user.email, true)
        // document photos first — before the daily quota is used
        verify.validate(lang, "pharmacy", data, b["docs"], true)
        quotas.use(lang, uid, "pharmacy")
        val docsHave = verify.save(lang, "pharmacy", uid, uid, data, b["docs"], true) ?: emptyList()
        jdbc.update(
            "INSERT INTO pharmacies (uid, name, license_no, phone, district, status, docs_have, created_at) VALUES (?, ?, ?, ?, ?, 'pending', ?::text[], ?)",
            uid, name, licenseNo, Phones.localBd(phone), district, docsHave.joinToString(",", "{", "}"), Timestamp(now),
        )
        set.update(jdbc, "pharmacies", "uid = ?", uid)
        return linkedMapOf("ok" to true, "pending" to true)
    }

    @Transactional
    fun pause(ctx: ActionContext): Any {
        val uid = ctx.user.id
        shops.get(uid, forUpdate = true) ?: throw ctx.notFound()
        val now = clock.now()
        if (ctx.body.isNullish("until")) {
            jdbc.update("UPDATE pharmacies SET closed_until = NULL, updated_at = ? WHERE uid = ?", Timestamp(now), uid)
            return mapOf("ok" to true)
        }
        val until = ctx.body.int("until")
        if (until == null || until <= now || until > now + 60 * Rules.DAY_MS) {
            throw bad(ctx, "Pick a date within 60 days", "৬০ দিনের মধ্যে তারিখ দিন")
        }
        jdbc.update("UPDATE pharmacies SET closed_until = ?, updated_at = ? WHERE uid = ?", Timestamp(until), Timestamp(now), uid)
        return mapOf("ok" to true)
    }

    @Transactional
    fun delete(ctx: ActionContext): Any {
        val uid = ctx.user.id
        val s = shops.get(uid, forUpdate = true) ?: return mapOf("ok" to true)
        if (s.status == "suspended") {
            throw ctx.err(
                403,
                "A suspended pharmacy can't be removed. You can appeal from Profile → Help & appeals.",
                "বন্ধ করা ফার্মেসি সরানো যায় না। প্রোফাইলের \"সাহায্য ও আপিল\" থেকে আপিল করতে পারেন।",
            )
        }
        moderation.assertNoOpenReports(ctx.lang, "pharmacies/$uid")
        removeListing(uid)
        return mapOf("ok" to true)
    }

    /** the listing and what belongs to it: stats, ratings given to it, document photos, order contacts */
    fun removeListing(uid: String) {
        jdbc.update("DELETE FROM pharmacies WHERE uid = ?", uid)
        stats.delete("pharmacy", uid)
        // ratings go with the row (FK); re-adding the shop starts from zero
        jdbc.update("DELETE FROM pharmacy_ratings WHERE pharmacy_uid = ?", uid)
        verify.delete("pharmacy", uid)
        orders.stripOrderContacts(uid)
    }

    // ------------------------------------------------------------------ list

    /** pharmacy.js callBasis: 'ok' / 'wait' (first call less than 1 h ago) / null */
    private fun callBasis(at: Long?, firstAt: Long?, now: Long): String? {
        if (at == null || now - at > RATE_WINDOW_MS) return null
        return if (now - (firstAt ?: at) >= RATE_AFTER_MS) "ok" else "wait"
    }

    /** pharmacy.js boughtFrom: shops the user got medicines from in the last 7 days → 'shop' / 'online' */
    fun boughtFrom(uid: String, now: Long): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        jdbc.query(
            "SELECT pharmacy_uid, mode, status, done_at, updated_at, created_at FROM rx_orders WHERE patient_uid = ? ORDER BY created_at DESC LIMIT 200",
            RowCallbackHandler { rs ->
                val at = rs.ms("done_at") ?: rs.ms("updated_at") ?: rs.ms("created_at") ?: 0
                if (rs.getString("status") != "done" || now - at > BOUGHT_WINDOW_MS) return@RowCallbackHandler
                val id = rs.getString("pharmacy_uid")
                if (rs.getString("mode") == "online") out[id] = "online" else if (id !in out) out[id] = "shop"
            },
            uid,
        )
        jdbc.query(
            """
            SELECT d.pharmacy_uid, d.at FROM prescription_dispensings d
            JOIN prescriptions p ON p.id = d.rx_id WHERE p.patient_uid = ? ORDER BY d.at
            """.trimIndent(),
            RowCallbackHandler { rs ->
                val by = rs.getString(1)
                if (!by.isNullOrEmpty() && now - rs.getTimestamp(2).time <= BOUGHT_WINDOW_MS && by !in out) out[by] = "shop"
            },
            uid,
        )
        return out
    }

    /** pharmacy.js cannotRate: the owner, or the other world (demo ↔ real) */
    private fun cannotRate(uid: String?, id: String): Boolean = uid == null || id == uid || !DemoWorld.sameWorld(uid, id)

    /** pharmacy.js rateableFor: shop → aspects the user may rate now */
    fun rateableFor(uid: String?, ids: List<String>, now: Long): Map<String, List<String>> {
        if (uid == null || ids.isEmpty()) return emptyMap()
        val called = HashSet<String>()
        jdbc.query(
            "SELECT pharmacy_uid, at, first_at FROM pharmacy_calls WHERE uid = ? LIMIT 200",
            RowCallbackHandler { rs -> if (callBasis(rs.ms("at"), rs.ms("first_at"), now) == "ok") called.add(rs.getString(1)) },
            uid,
        )
        val bought = boughtFrom(uid, now)
        val out = LinkedHashMap<String, List<String>>()
        for (id in ids) if ((id in bought || id in called) && !cannotRate(uid, id)) out[id] = aspectsFor(bought[id])
        return out
    }

    /** rxnet.js ownRx: the caller's own prescription (caregiver: the patient's), else 403 */
    fun ownRx(ctx: ActionContext, rxId: com.fasterxml.jackson.databind.JsonNode?): Prescription {
        val id = Js.string(rxId)
        val uid = ctx.user.uid
        val rx = if (Rules.isValidId(id) && uid != null) prescriptions.get(id!!) else null
        if (rx == null || rx.patientUid != uid) throw ctx.err(403, "You can't use this prescription", "এই প্রেসক্রিপশন ব্যবহার করা যাবে না")
        return rx
    }

    @Transactional(readOnly = true)
    fun list(ctx: ActionContext): Any {
        val b = ctx.body
        val district = b.string("district")
        if (!Places.isDistrict(district)) throw bad(ctx, "Choose a district", "জেলা বেছে নিন")
        val upazilaId = b.int("upazilaId")?.toInt()
        val mode = b.string("mode")?.takeIf { it == "shop" || it == "online" }
        val uid = ctx.user.uid
        val rxNode = b["rxId"]
        val rx = if (rxNode != null && !rxNode.isNull && !(rxNode.isTextual && rxNode.textValue() == "")) ownRx(ctx, rxNode) else null
        val now = clock.now()
        val docs = shops.area(district!!, upazilaId)
        val world = if (uid != null && (mode != null || rx != null)) docs.filter { DemoWorld.sameWorld(uid, it.id) } else docs
        val lat = b.number("lat")
        val lng = b.number("lng")
        class Item(val shop: Shop, val v: LinkedHashMap<String, Any?>, val km: Double?, val openNow: Boolean)
        var items = world.map { s ->
            val v = s.view(false, now)
            val km = if (lat != null && s.lat != null && s.lng != null) km1(lat, lng ?: Double.NaN, s.lat, s.lng) else null
            v["km"] = km?.takeIf { it.isFinite() }
            Item(s, v, v["km"] as Double?, v["openNow"] == true)
        }
        if (mode == "online") {
            items = items.filter { p ->
                p.shop.onlineEnabled && if (p.km != null) p.km <= p.shop.radiusKm else upazilaId == null || p.shop.upazilaId == upazilaId
            }
        } else if (upazilaId != null) {
            items = items.filter { p -> p.shop.upazilaId == upazilaId || (p.km != null && p.km <= MED_RADIUS_KM) }
        }
        if (b.isTrue("openNow")) items = items.filter { it.openNow }
        if (b.isTrue("open24")) items = items.filter { it.shop.open24 }
        items = items.sortedWith(
            compareByDescending<Item> { if (it.openNow) 1 else 0 }
                .thenBy { it.km ?: 99.0 }
                .thenComparator { a, c -> BN_ORDER.compare(a.shop.name, c.shop.name) },
        ).take(if (rx != null) RX_LIST_MAX else 100)
        var lastHome: Map<String, String>? = null
        if (rx != null) {
            val wanted = RxMath.wantedOf(rx)
            val stocks = stock.loadAll(items.map { it.shop.id })
            for (p in items) p.v["rx"] = rxCard(p.v, RxMath.stockMatch(stocks[p.shop.id], wanted))
            @Suppress("UNCHECKED_CAST")
            fun card(p: Item) = p.v["rx"] as Map<String, Any?>
            fun avail(p: Item): Double {
                val c = card(p)
                val total = (c["total"] as Number).toInt()
                return if (c["keepsStock"] == true && total != 0) (c["have"] as Number).toDouble() / total else -1.0
            }
            items = items.sortedWith(
                compareByDescending<Item> { avail(it) }
                    .thenBy { (card(it)["payable"] as Number?)?.toDouble() ?: Double.POSITIVE_INFINITY }
                    .thenByDescending { if (it.openNow) 1 else 0 }
                    .thenBy { it.km ?: 99.0 }
                    .thenComparator { a, c -> BN_ORDER.compare(a.shop.name, c.shop.name) },
            )
            lastHome = try {
                patientHome.lastHome(uid!!)
            } catch (e: Exception) {
                null
            }
        }
        val rateable = rateableFor(uid, items.map { it.shop.id }, now)
        val out = linkedMapOf<String, Any?>(
            "items" to items.map { p ->
                LinkedHashMap(p.v).apply {
                    put("canRate", p.shop.id in rateable)
                    put("rateAspects", rateable[p.shop.id] ?: emptyList<String>())
                }
            },
        )
        if (rx != null) {
            out["rxId"] = rx.id
            out["lastHome"] = lastHome
        }
        return out
    }

    // ------------------------------------------------------------------ calls & ratings

    @Transactional
    fun called(ctx: ActionContext): Any {
        val uid = ctx.user.id
        val id = ctx.body.string("id")
        if (!Rules.isValidId(id) || id == uid) return mapOf("ok" to true)
        if (shops.get(id) == null || !DemoWorld.sameWorld(uid, id)) return mapOf("ok" to true)
        val prev = jdbc.queryForList("SELECT at FROM pharmacy_calls WHERE uid = ? AND pharmacy_uid = ? FOR UPDATE", uid, id).firstOrNull()
        val now = clock.now()
        jdbc.update(
            """
            INSERT INTO pharmacy_calls (uid, pharmacy_uid, at, first_at) VALUES (?, ?, ?, ?)
            ON CONFLICT (uid, pharmacy_uid) DO UPDATE SET at = EXCLUDED.at
            """.trimIndent(),
            uid, id, Timestamp(now), Timestamp(now),
        )
        val prevAt = (prev?.get("at") as? Timestamp)?.time ?: 0
        if (prev == null || now - prevAt > 10 * 60 * 1000) stats.bumpCall("pharmacy", id!!, now, prev == null)
        return mapOf("ok" to true)
    }

    @Transactional
    fun rate(ctx: ActionContext): Any {
        val b = ctx.body
        val uid = ctx.user.id
        val id = b.string("id")
        fun stars(n: com.fasterxml.jackson.databind.JsonNode?): Int? = Js.integer(n)?.takeIf { it in 1..5 }?.toInt()
        val aspNode = b["aspects"]
        val legacy = !(aspNode != null && aspNode.isObject)
        val given: List<Pair<String, com.fasterxml.jackson.databind.JsonNode>> = when {
            !legacy -> aspNode!!.fields().asSequence().map { it.key to it.value }.toList()
            b.has("rating") -> listOf("behaviour" to b["rating"]!!)
            else -> emptyList()
        }
        val aspects = LinkedHashMap<String, Int>()
        for ((k, v) in given) {
            if (v.isNull || (v.isNumber && v.doubleValue() == 0.0)) continue
            val s = stars(v)
            if (k !in ASPECTS || s == null) throw bad(ctx, "Choose 1 to 5 stars", "১ থেকে ৫ তারা দিন")
            aspects[k] = s
        }
        if (!Rules.isValidId(id) || aspects["behaviour"] == null) {
            throw bad(ctx, "Rate the behaviour with 1 to 5 stars", "ব্যবহারে ১ থেকে ৫ তারা দিন")
        }
        val comment = ListingRules.str(b.string("comment"), 300)
        val now = clock.now()
        val shop = shops.get(id)
        if (shop == null || !DemoWorld.sameWorld(uid, id)) throw ctx.notFound()
        if (cannotRate(uid, id!!)) throw ctx.err(403, "You can't rate your own pharmacy", "নিজের ফার্মেসিতে রেটিং দেওয়া যায় না")
        val call = jdbc.queryForList("SELECT at, first_at FROM pharmacy_calls WHERE uid = ? AND pharmacy_uid = ?", uid, id).firstOrNull()
        val byCall = callBasis((call?.get("at") as? Timestamp)?.time, (call?.get("first_at") as? Timestamp)?.time, now)
        val bought = boughtFrom(uid, now)[id]
        if (byCall != "ok" && bought == null) {
            if (byCall == "wait") throw ctx.err(403, "You can rate 1 hour after your first call", "প্রথম ফোনের ১ ঘণ্টা পর রেটিং দিতে পারবেন")
            throw ctx.err(
                403,
                "You can rate after calling this pharmacy from the app or getting medicines from it",
                "অ্যাপ থেকে ফোন করার বা এই দোকান থেকে ওষুধ নেওয়ার পর রেটিং দিতে পারবেন",
            )
        }
        val allowed = aspectsFor(bought)
        if (aspects.keys.any { it !in allowed }) {
            throw if (aspects["delivery"] != null && "price" in allowed) {
                ctx.err(403, "You can rate delivery after an online order from this shop is delivered", "এই দোকানের অনলাইন অর্ডার পৌঁছানোর পর ডেলিভারির রেটিং দেওয়া যায়")
            } else {
                ctx.err(403, "You can rate price and delivery after getting medicines from this shop", "এই দোকান থেকে ওষুধ নেওয়ার পর দাম ও ডেলিভারির রেটিং দেওয়া যায়")
            }
        }
        // the transaction: shop row + this rater's row
        shops.get(id, forUpdate = true) ?: throw ctx.notFound()
        val r = jdbc.queryForList(
            "SELECT rating, behaviour, price, delivery, first_rated_at, rated_at FROM pharmacy_ratings WHERE uid = ? AND pharmacy_uid = ? FOR UPDATE",
            uid, id,
        ).firstOrNull()
        val old = (r?.get("rating") as? Number)?.toInt() ?: 0
        val oldAspects: Map<String, Int>? = r?.let { row ->
            val m = LinkedHashMap<String, Int>()
            for (k in ASPECTS) (row[k] as? Number)?.toInt()?.let { m[k] = it }
            m.takeIf { it.isNotEmpty() }
        }
        val next: Map<String, Int> = if (legacy && oldAspects != null) {
            LinkedHashMap(oldAspects.filter { (k, v) -> k in ASPECTS && v in 1..5 }).apply { putAll(aspects) }
        } else {
            aspects
        }
        val value = Js.round(next.values.sum().toDouble() / next.size).toInt()
        val set = SqlSet().expr("rating_sum", "rating_sum + ${value - old}").expr("rating_count", "rating_count + ${if (old != 0) 0 else 1}")
        aspectDelta(oldAspects, next, set)
        set.update(jdbc, "pharmacies", "uid = ?", id)
        val firstRatedAt = (r?.get("first_rated_at") as? Timestamp)?.time ?: (r?.get("rated_at") as? Timestamp)?.time ?: now
        jdbc.update(
            """
            INSERT INTO pharmacy_ratings (uid, pharmacy_uid, rating, behaviour, price, delivery, comment, first_rated_at, rated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (uid, pharmacy_uid) DO UPDATE SET rating = EXCLUDED.rating, behaviour = EXCLUDED.behaviour,
              price = EXCLUDED.price, delivery = EXCLUDED.delivery, comment = EXCLUDED.comment,
              first_rated_at = EXCLUDED.first_rated_at, rated_at = EXCLUDED.rated_at
            """.trimIndent(),
            uid, id, value, next["behaviour"], next["price"], next["delivery"], comment, Timestamp(firstRatedAt), Timestamp(now),
        )
        stats.addRecentRating("pharmacy", id, uid, value, comment)
        return linkedMapOf("ok" to true, "rating" to value)
    }

    /** pharmacy.js aspectDelta: per-aspect sum/count changes between the old and the new rating */
    private fun aspectDelta(oldA: Map<String, Int>?, newA: Map<String, Int>?, set: SqlSet) {
        for (k in ASPECTS) {
            val o = oldA?.get(k) ?: 0
            val n = newA?.get(k) ?: 0
            if (n - o != 0) set.expr("aspect_${k}_sum", "aspect_${k}_sum + ${n - o}")
            val dc = (if (n != 0) 1 else 0) - (if (o != 0) 1 else 0)
            if (dc != 0) set.expr("aspect_${k}_count", "aspect_${k}_count + $dc")
        }
    }

    /** pharmacy.js removeRatingsBy (account deletion): the user's ratings leave the averages and the owner's recent list */
    fun removeRatingsBy(uid: String) {
        val list = jdbc.queryForList("SELECT pharmacy_uid, rating, behaviour, price, delivery FROM pharmacy_ratings WHERE uid = ? LIMIT 1000", uid)
        for (row in list) {
            val pid = row["pharmacy_uid"] as String
            val r = (row["rating"] as? Number)?.toInt()
            if (Rules.isValidId(pid) && r != null && r > 0) {
                val oldA = LinkedHashMap<String, Int>()
                for (k in ASPECTS) (row[k] as? Number)?.toInt()?.let { oldA[k] = it }
                val set = SqlSet().expr("rating_sum", "rating_sum - $r").expr("rating_count", "rating_count - 1")
                aspectDelta(oldA.takeIf { it.isNotEmpty() }, null, set)
                set.update(jdbc, "pharmacies", "uid = ?", pid)
                stats.removeRecentRating("pharmacy", pid, uid)
            }
            jdbc.update("DELETE FROM pharmacy_ratings WHERE uid = ? AND pharmacy_uid = ?", uid, pid)
        }
    }

    @Transactional(readOnly = true)
    fun stats(ctx: ActionContext): Any {
        val uid = ctx.user.id
        val p = shops.get(uid)
        val reports = try {
            moderation.reportCount("pharmacies/$uid")
        } catch (e: Exception) {
            0
        }
        val n = p?.ratingCount ?: 0
        return LinkedHashMap(stats.summary("pharmacy", uid, clock.now())).apply {
            put("rating", if (n != 0) Shop.round1(p!!.ratingSum.toDouble() / n) else 0)
            put("ratingCount", n)
            put("aspects", Shop.aspectView(p?.aspects))
            put("comments", stats.recentComments("pharmacy", uid))
            put("reports", reports)
        }
    }

    // ------------------------------------------------------------------ admin

    @Transactional(readOnly = true)
    fun adminList(ctx: ActionContext): Any {
        requireAdmin(ctx)
        val status = ctx.body.string("status")?.takeIf { it in ADMIN_STATUSES } ?: "pending"
        val now = clock.now()
        val list = jdbc.query(
            "SELECT * FROM pharmacies WHERE status = ? ORDER BY uid COLLATE \"C\" LIMIT 300",
            { rs, _ -> Shop.of(rs) to rows.view(ListingKind.PHARMACY, rs) },
            status,
        )
        return mapOf(
            "items" to list.map { (s, x) ->
                LinkedHashMap(s.view(true, now)).apply {
                    put("createdAt", s.createdAt)
                    put("reviewedAt", (if (status == "suspended") s.suspendedAt else null) ?: s.reviewedAt)
                    put("statusReason", s.statusReason)
                    put("editRejectedReason", s.editRejectedReason)
                    put("changes", ListingRules.changedFields(x).map { linkedMapOf("field" to it.field, "before" to it.before, "after" to it.after) })
                    if (status == "pending") {
                        put("duplicates", runCatching { review.possibleDuplicates("pharmacy", s.id, x) }.getOrDefault(emptyList()))
                    }
                    if (status == "pending" || status == "rejected") {
                        put("verify", runCatching { verify.panelInfo("pharmacy", s.id, x) }.getOrNull())
                    }
                    put("verification", x["verification"])
                }
            },
        )
    }

    @Transactional
    fun adminResolve(ctx: ActionContext): Any {
        requireAdmin(ctx)
        return review.resolve(ctx, "pharmacy", "pharmacies", ctx.body.string("id"))
    }

    /** account.js schedule: hide the listing while the account waits for deletion */
    fun hideForDeletion(uid: String) {
        jdbc.update(
            "UPDATE pharmacies SET hidden_for_deletion = status, status = 'suspended' WHERE uid = ? AND status <> 'suspended' AND hidden_for_deletion IS NULL",
            uid,
        )
    }
}
