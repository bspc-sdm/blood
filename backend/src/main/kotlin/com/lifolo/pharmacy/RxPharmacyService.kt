package com.lifolo.pharmacy

import com.lifolo.common.Js
import com.lifolo.common.Phones
import com.lifolo.common.Places
import com.lifolo.common.QuotaService
import com.lifolo.common.Rules
import com.lifolo.common.action.ActionContext
import com.lifolo.contracts.Prescription
import com.lifolo.contracts.Prescriptions
import com.lifolo.contracts.RxDispensing
import com.lifolo.demo.DemoWorld
import com.lifolo.notify.PushService
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.sql.Timestamp

/**
 * rxnet.js, pharmacy side: open a prescription by code and record what was given (dispensing), the patient's
 * pharmacy options and orders (shop / online), the pharmacy's incoming orders, shop code, online settings,
 * "could not give" list, and the stock name list.
 */
@Service
class RxPharmacyService(
    private val jdbc: JdbcTemplate,
    private val shops: ShopRepo,
    private val stock: StockStore,
    private val prescriptions: Prescriptions,
    private val quotas: QuotaService,
    private val push: PushService,
    private val userPush: UserPush,
    private val contacts: RxOrderContacts,
    private val clock: P4Clock,
) {
    companion object {
        const val SHOP_CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val SHOP_CODE_RE = Regex("^[A-HJ-NP-Z2-9]{6}$")
        private val random = SecureRandom()

        /** rxnet.js str: \r\n → \n, trimmed, cut */
        fun str(v: String?, n: Int): String = if (v == null) "" else v.replace("\r\n", "\n").trim().take(n)
    }

    private fun notFound(ctx: ActionContext) = ctx.err(404, "Not found", "পাওয়া যায়নি")

    /** rxnet.js myPharmacy: the caller's approved pharmacy, else 403 */
    fun myPharmacy(ctx: ActionContext, forUpdate: Boolean = false): Shop {
        val s = shops.get(ctx.user.uid, forUpdate)
        if (s == null || s.status != "approved") {
            throw ctx.err(403, "Only approved pharmacies can do this", "শুধু অনুমোদিত ফার্মেসি এটা করতে পারে")
        }
        return s
    }

    /** rxnet.js pharmacyRxView: doctor, patient name/age, date, medicines (bought/left), earlier dispensings */
    fun pharmacyRxView(rx: Prescription): Map<String, Any?> = linkedMapOf(
        "id" to rx.id,
        "code" to rx.code,
        "status" to rx.status,
        "createdAt" to rx.createdAt,
        "doctor" to linkedMapOf(
            "name" to (rx.doctor["name"] ?: ""), "degrees" to (rx.doctor["degrees"] ?: ""), "regNo" to (rx.doctor["regNo"] ?: ""),
        ),
        "patient" to linkedMapOf("name" to (rx.patient["name"] ?: ""), "age" to rx.patient["age"]),
        "medicines" to RxMath.medStatus(rx).map { s ->
            s.toMap().apply {
                put("timing", rx.medicines[s.i].timing)
                put("note", rx.medicines[s.i].note)
            }
        },
        "dispensed" to rx.dispensed.map { linkedMapOf("name" to it.name, "at" to it.at, "items" to it.itemsJson()) },
    )

    @Transactional
    fun open(ctx: ActionContext): Any {
        myPharmacy(ctx)
        quotas.use(ctx.lang, ctx.user.id, "rxPharm")
        val rx = prescriptions.byCode(ctx.lang, Js.toStr(ctx.body["code"]), ctx.user.uid)
        return mapOf("item" to pharmacyRxView(rx))
    }

    private fun loadOrders(where: String, vararg args: Any?): List<RxOrder> =
        jdbc.query("SELECT * FROM rx_orders WHERE $where", { rs, _ -> RxOrder.of(rs) }, *args)

    @Transactional
    fun dispense(ctx: ActionContext): Any {
        val shop = myPharmacy(ctx)
        val uid = ctx.user.id
        val b = ctx.body
        val rx0 = prescriptions.byCode(ctx.lang, Js.toStr(b["code"]), uid)
        val raw = b["items"]?.takeIf { it.isArray }?.toList() ?: emptyList()
        val now = clock.now()
        // the Firestore transaction: lock the prescription and read it again
        jdbc.queryForList("SELECT id FROM prescriptions WHERE id = ? FOR UPDATE", rx0.id)
        val x = prescriptions.get(rx0.id) ?: throw notFound(ctx)
        if (x.status != "active") {
            throw ctx.err(409, "This prescription was cancelled. Do not dispense.", "এই প্রেসক্রিপশন বাতিল। ওষুধ দেবেন না।")
        }
        val st = RxMath.medStatus(x)
        val items = ArrayList<Pair<Int, Int>>()
        for (n in raw) {
            val i = Js.integer(n.get("i"))?.toInt() ?: continue
            if (i < 0 || i >= st.size) continue
            val qty = Js.integer(n.get("qty")) ?: 0
            if (qty <= 0) continue
            if (qty > 1000) throw ctx.err(400, "Check the quantity", "পরিমাণ দেখে নিন")
            val m = st[i]
            if (m.left != null && qty > m.left) {
                throw ctx.err(409, "${m.name}: only ${m.left} left on this prescription", "${m.name}: এই প্রেসক্রিপশনে আর ${m.left}টা বাকি")
            }
            if (m.left == null && m.done) throw ctx.err(409, "${m.name} was already given", "${m.name} আগেই দেওয়া হয়েছে")
            items.add(i to qty.toInt())
        }
        val missingNode = b["missing"]
        val marked = missingNode != null && missingNode.isArray &&
            missingNode.any { n -> Js.integer(n)?.let { it >= 0 && it < st.size } == true }
        if (items.isEmpty() && !marked) throw ctx.err(400, "Enter how many you gave", "কয়টা দিলেন লিখুন")
        val none = items.isEmpty()
        var next = x
        if (!none) {
            val id = jdbc.queryForObject(
                "INSERT INTO prescription_dispensings (rx_id, pharmacy_uid, pharmacy_name, at) VALUES (?, ?, ?, ?) RETURNING id",
                Long::class.java, x.id, uid, shop.name, Timestamp(now),
            )!!
            // the same medicine twice in one request: Node keeps both entries; the table has one row per medicine
            val merged = LinkedHashMap<Int, Int>()
            for ((i, q) in items) merged[i] = (merged[i] ?: 0) + q
            jdbc.batchUpdate(
                "INSERT INTO prescription_dispensed_items (dispensing_id, med_idx, qty) VALUES (?, ?, ?)",
                merged.map { (i, q) -> arrayOf<Any?>(id, i, q) },
            )
            // dispensed[] keeps the last 30
            jdbc.update(
                """
                DELETE FROM prescription_dispensings WHERE rx_id = ? AND id NOT IN (
                  SELECT id FROM prescription_dispensings WHERE rx_id = ? ORDER BY at DESC, id DESC LIMIT 30)
                """.trimIndent(),
                x.id, x.id,
            )
            next = prescriptions.get(x.id) ?: x.copy(dispensed = (x.dispensed + RxDispensing(uid, shop.name, now, items)).takeLast(30))
            prescriptions.setRefillAt(x.id, RxMath.refillDate(next.medicines, next.dispensed, now))
        }
        // medicines marked "not available" go to the shop's missed list
        val missed = missingNames(x, missingNode)
        var logged = false
        for (o in loadOrders("rx_id = ? ORDER BY created_at LIMIT 50", x.id)) {
            if (o.pharmacyUid != uid || o.status !in RxMath.OPEN_ORDER) continue
            val added = missed.filter { it !in o.missed }
            val set = com.lifolo.common.SqlSet()
            if (!none) {
                set.value("status", "done").value("done_at", Timestamp(now))
                contacts.closeMarks(o, now).forEach { (c, v) -> set.value(c, v) }
            }
            if (added.isNotEmpty()) set.textArray("missed", (o.missed + added).take(30))
            set.update(jdbc, "rx_orders", "id = ?", o.id)
            if (!logged) addMissed(uid, added)
            logged = true
        }
        if (!logged) addMissed(uid, missed)
        val patient = x.patientUid
        if (patient != null && none && missed.isNotEmpty()) {
            push.pushPatient(
                patient, "${shop.name} doesn't have some medicines", "${shop.name}-এ কিছু ওষুধ নেই", missed.joinToString(", "),
                mapOf("type" to "rx", "id" to x.id, "rxId" to x.id),
            )
        } else if (patient != null && !none) {
            push.pushPatient(
                patient, "Medicines given from your prescription", "প্রেসক্রিপশনের ওষুধ দেওয়া হয়েছে", shop.name,
                mapOf("type" to "rx", "id" to x.id, "rxId" to x.id),
            )
        }
        return mapOf("item" to pharmacyRxView(next))
    }

    /** rxnet.js missingNames: medicine indexes → names (unique) */
    private fun missingNames(rx: Prescription?, missing: com.fasterxml.jackson.databind.JsonNode?): List<String> {
        if (rx == null || missing == null || !missing.isArray) return emptyList()
        val idx = missing.mapNotNull { n -> Js.integer(n)?.takeIf { it in 0..59 }?.toInt() }
        return idx.mapNotNull { i -> rx.medicines.getOrNull(i)?.name?.takeIf { it.isNotEmpty() } }.distinct()
    }

    /** rxnet.js addMissed: count + last time of medicines the shop could not give (at most 500 kept) */
    fun addMissed(pharmacyId: String, names: List<String>) {
        val list = names.filter { it.isNotEmpty() }.distinct()
        if (list.isEmpty() || !Rules.isValidId(pharmacyId)) return
        val now = Timestamp(clock.now())
        try {
            for (n in list) {
                jdbc.update(
                    """
                    INSERT INTO pharmacy_missed (pharmacy_uid, item_key, name, count, last_at) VALUES (?, ?, ?, 1, ?)
                    ON CONFLICT (pharmacy_uid, item_key) DO UPDATE SET count = pharmacy_missed.count + 1, last_at = EXCLUDED.last_at
                    """.trimIndent(),
                    pharmacyId, RxMath.missKey(n), n, now,
                )
            }
            jdbc.update(
                """
                DELETE FROM pharmacy_missed WHERE pharmacy_uid = ? AND item_key NOT IN (
                  SELECT item_key FROM pharmacy_missed WHERE pharmacy_uid = ? ORDER BY last_at DESC LIMIT 500)
                """.trimIndent(),
                pharmacyId, pharmacyId,
            )
        } catch (e: Exception) {
            org.slf4j.LoggerFactory.getLogger(javaClass).error("missed log failed: {}", e.message)
        }
    }

    // ------------------------------------------------------------------ patient side

    @Transactional(readOnly = true)
    fun options(ctx: ActionContext): Any {
        val b = ctx.body
        val uid = ctx.user.id
        val rxId = b.string("rxId")
        val rx = if (Rules.isValidId(rxId)) prescriptions.get(rxId!!) else null
        if (rx == null || rx.patientUid != uid) throw notFound(ctx)
        val district = b.string("district")
        if (!Places.isDistrict(district)) throw ctx.err(400, "Choose a district", "জেলা বেছে নিন")
        val mode = b.string("mode")?.takeIf { it == "shop" || it == "online" }
        val q = b.string("q")?.trim()?.lowercase()?.take(60) ?: ""
        val wanted = RxMath.wantedOf(rx)
        val upazilaId = b.int("upazilaId")?.toInt()
        val lat = b.number("lat")
        val lng = b.number("lng")
        val hasLoc = lat != null && lng != null
        val placeId = b.string("placeId")?.takeIf { Rules.isValidId(it) }
        val docs = (
            if (placeId != null) {
                listOfNotNull(shops.get(placeId)?.takeIf { it.status == "approved" && it.district == district })
            } else {
                shops.area(district!!, upazilaId)
            }
            ).filter { DemoWorld.sameWorld(uid, it.id) }
        val now = clock.now()
        data class Near(val s: Shop, val km: Double?)
        val near = docs.map { s -> Near(s, if (hasLoc && s.lat != null && s.lng != null) km1(lat!!, lng!!, s.lat, s.lng) else null) }
            .filter { p ->
                when {
                    placeId != null -> true
                    q.isNotEmpty() -> p.s.name.lowercase().contains(q) && (mode != "online" || p.s.onlineEnabled)
                    mode == "online" -> p.s.onlineEnabled && if (p.km != null) p.km <= p.s.radiusKm else upazilaId == null || p.s.upazilaId == upazilaId
                    else -> upazilaId == null || p.s.upazilaId == upazilaId || (p.km != null && p.km <= PharmacyService.MED_RADIUS_KM)
                }
            }
            .sortedBy { it.km ?: 99.0 }
            .take(40)
        val stocks = stock.loadAll(near.map { it.s.id })
        var items = near.map { p ->
            val m = RxMath.stockMatch(stocks[p.s.id], wanted)
            linkedMapOf<String, Any?>(
                "id" to p.s.id,
                "name" to p.s.name,
                "phone" to Phones.fixPhone(p.s.phone),
                "address" to p.s.address,
                "km" to p.km,
                "openNow" to (!((p.s.closedUntil ?: 0) > now) && p.s.openNow(now)),
                "keepsStock" to m["keepsStock"],
                "have" to m["have"],
                "total" to m["total"],
                "missing" to m["missing"],
                "online" to p.s.onlineEnabled,
                "deliveryFee" to p.s.onlineFee,
                "onlineNote" to p.s.onlineNote,
                "estimate" to m["estimate"],
            )
        }
        fun km(x: Map<String, Any?>) = (x["km"] as Double?) ?: 99.0
        items = if (mode == "shop") {
            items.sortedBy { km(it) }
        } else {
            items.sortedWith(
                compareByDescending<LinkedHashMap<String, Any?>> { it["have"] as Int }
                    .thenByDescending { if (it["openNow"] == true) 1 else 0 }
                    .thenBy { km(it) },
            )
        }
        return mapOf("items" to items.take(30))
    }

    @Transactional
    fun shopFind(ctx: ActionContext): Any {
        val c = Js.toStr(ctx.body["code"]).trim().uppercase().replace(Regex("[\\s-]"), "")
        if (!SHOP_CODE_RE.matches(c)) throw ctx.err(400, "Check the shop code", "দোকানের কোডটা দেখে লিখুন")
        quotas.use(ctx.lang, ctx.user.id, "medTry")
        val d = jdbc.query("SELECT * FROM pharmacies WHERE shop_code = ? LIMIT 1", { rs, _ -> Shop.of(rs) }, c).firstOrNull()
        if (d == null || d.status != "approved" || !DemoWorld.sameWorld(ctx.user.id, d.id)) {
            throw ctx.err(404, "No pharmacy with this code", "এই কোডের কোনো ফার্মেসি নেই")
        }
        return mapOf(
            "item" to linkedMapOf(
                "id" to d.id, "name" to d.name, "address" to d.address, "phone" to Phones.fixPhone(d.phone),
                "online" to d.onlineEnabled, "deliveryFee" to d.onlineFee,
            ),
        )
    }

    @Transactional
    fun send(ctx: ActionContext): Any {
        val b = ctx.body
        val uid = ctx.user.id
        val rxId = b.string("rxId")
        val pharmacyId = b.string("pharmacyId")
        if (!Rules.isValidId(rxId) || !Rules.isValidId(pharmacyId)) throw notFound(ctx)
        val mode = if (b.string("mode") == "online") "online" else "shop"
        val rx = prescriptions.get(rxId!!)
        val p = shops.get(pharmacyId)
        if (rx == null || rx.patientUid != uid) throw notFound(ctx)
        if (rx.status != "active") throw ctx.err(409, "This prescription was cancelled", "এই প্রেসক্রিপশন বাতিল")
        if (p == null || p.status != "approved" || !DemoWorld.sameWorld(uid, pharmacyId)) {
            throw ctx.err(404, "This pharmacy is not available", "এই ফার্মেসি এখন নেই")
        }
        if (RxMath.medStatus(rx).none { !it.done }) {
            throw ctx.err(409, "All medicines on this prescription were already bought", "এই প্রেসক্রিপশনের সব ওষুধ কেনা হয়ে গেছে")
        }
        var address: String? = null
        var phone: String? = null
        var fee: String? = null
        if (mode == "online") {
            if (!p.onlineEnabled) throw ctx.err(409, "This pharmacy doesn't take online orders", "এই ফার্মেসি অনলাইনে অর্ডার নেয় না")
            address = Rules.cleanText(b.string("address"), 200) ?: throw ctx.err(400, "Write the delivery address", "কোথায় পৌঁছাবে, ঠিকানা লিখুন")
            val ph = Phones.normalizeBd(Js.toStr(b["phone"])) ?: throw ctx.err(400, "Enter a mobile number for the delivery", "ডেলিভারির জন্য মোবাইল নম্বর দিন")
            phone = Phones.localBd(ph)
            fee = p.onlineFee
        }
        val open = loadOrders("rx_id = ? LIMIT 50", rxId)
        if (open.any { it.pharmacyUid == pharmacyId && it.status in RxMath.OPEN_ORDER }) {
            throw ctx.err(409, "Already sent to this pharmacy", "এই ফার্মেসিতে আগেই পাঠানো আছে")
        }
        quotas.use(ctx.lang, uid, "rxSend")
        val id = com.lifolo.common.Ids.newId()
        val patientName = (rx.patient["name"] as? String) ?: ""
        jdbc.update(
            """
            INSERT INTO rx_orders (id, rx_id, code, pharmacy_uid, pharmacy_name, patient_uid, patient_name, status, mode,
              address, phone, delivery_fee, note, created_at, by_caretaker)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'sent', ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            id, rxId, rx.code, pharmacyId, p.name, uid, patientName, mode, address, phone, fee,
            str(b.string("note"), 200), Timestamp(clock.now()), ctx.user.actingBy,
        )
        // the next online order (and lab home collection) starts with this address/phone (labprices.js rememberHome)
        if (mode == "online") {
            jdbc.update(
                """
                INSERT INTO user_state (uid, lab_home_address, lab_home_phone, lab_home_at) VALUES (?, ?, ?, ?)
                ON CONFLICT (uid) DO UPDATE SET lab_home_address = EXCLUDED.lab_home_address, lab_home_phone = EXCLUDED.lab_home_phone,
                  lab_home_at = EXCLUDED.lab_home_at
                """.trimIndent(),
                uid, address, phone, Timestamp(clock.now()),
            )
        }
        userPush.send(
            pharmacyId,
            if (mode == "online") "New online order" else "A customer sent a prescription to your shop",
            if (mode == "online") "নতুন অনলাইন অর্ডার" else "দোকানে একজন প্রেসক্রিপশন পাঠিয়েছেন",
            patientName,
            mapOf("type" to "rx_pharmacy", "id" to id, "rxId" to rxId),
        )
        return mapOf("id" to id)
    }

    @Transactional
    fun withdraw(ctx: ActionContext): Any {
        val uid = ctx.user.id
        val id = ctx.body.string("id")
        if (!Rules.isValidId(id)) throw notFound(ctx)
        val s = loadOrders("id = ? FOR UPDATE", id).firstOrNull()
        if (s == null || s.patientUid != uid) throw notFound(ctx)
        if (s.status !in listOf("sent", "ready", "accepted")) throw ctx.err(409, "This can't be taken back now", "এখন আর ফিরিয়ে নেওয়া যায় না")
        val notAvailable = ctx.body.string("reason") == "not_available"
        var added = emptyList<String>()
        if (notAvailable) {
            val rx = prescriptions.get(s.rxId)
            val left = rx?.let { r -> RxMath.medStatus(r).filter { !it.done }.map { it.name } } ?: emptyList()
            val already = s.missed.toSet()
            added = left.filter { it !in already }
        }
        val now = clock.now()
        val set = com.lifolo.common.SqlSet().value("status", "withdrawn").value("withdraw_reason", if (notAvailable) "not_available" else "other")
        if (added.isNotEmpty()) set.textArray("missed", (s.missed + added).take(30))
        set.value("updated_at", Timestamp(now))
        contacts.closeMarks(s, now).forEach { (c, v) -> set.value(c, v) }
        set.update(jdbc, "rx_orders", "id = ?", id)
        addMissed(s.pharmacyUid, added)
        userPush.send(
            s.pharmacyUid, "The customer took back the prescription", "ক্রেতা প্রেসক্রিপশন ফিরিয়ে নিয়েছেন", s.patientName,
            mapOf("type" to "rx_pharmacy", "id" to id, "rxId" to s.rxId),
        )
        return mapOf("ok" to true)
    }

    // ------------------------------------------------------------------ pharmacy side

    @Transactional(readOnly = true)
    fun ordersForPharmacy(ctx: ActionContext): Any {
        myPharmacy(ctx)
        val all = loadOrders("pharmacy_uid = ? ORDER BY created_at DESC LIMIT 300", ctx.user.id)
        val items = RxMath.recentFirstOpen(all, { it.createdAt }, { it.status in RxMath.OPEN_ORDER }, 100).map { o ->
            linkedMapOf<String, Any?>(
                "id" to o.id, "rxId" to o.rxId, "code" to o.code, "patientName" to o.patientName, "note" to (o.note ?: ""),
                "status" to o.status, "createdAt" to o.createdAt, "mode" to o.mode, "price" to (o.price ?: ""), "eta" to (o.eta ?: ""),
                "deliveryFee" to (o.deliveryFee ?: ""), "missed" to o.missed,
            ).apply {
                if (o.mode == "online" && o.status in RxMath.OPEN_ORDER) {
                    put("address", o.address ?: "")
                    put("phone", o.phone ?: "")
                }
                put("seg", RxMath.pharmSeg(o.status))
            }
        }
        return linkedMapOf("items" to items, "counts" to RxMath.segCounts(RxMath.PHARM_SEGS, all.map { RxMath.pharmSeg(it.status) }))
    }

    @Transactional
    fun orderUpdate(ctx: ActionContext): Any {
        myPharmacy(ctx)
        val b = ctx.body
        val id = b.string("id")
        val status = b.string("status")
        if (!Rules.isValidId(id) || status !in listOf("ready", "accepted", "out", "delivered", "cancelled")) throw notFound(ctx)
        val s = loadOrders("id = ? FOR UPDATE", id).firstOrNull()
        if (s == null || s.pharmacyUid != ctx.user.id) throw notFound(ctx)
        val mode = s.mode
        val cur = s.status
        val allowed = when (status) {
            "ready" -> mode == "shop" && cur == "sent"
            "accepted" -> mode == "online" && cur == "sent"
            "out" -> mode == "online" && (cur == "sent" || cur == "accepted")
            "delivered" -> mode == "online" && (cur == "accepted" || cur == "out")
            else -> cur in RxMath.OPEN_ORDER
        }
        if (!allowed) throw ctx.err(409, "This can't be changed now", "এখন এটা বদলানো যায় না")
        val reason = str(b.string("reason"), 200)
        val missedNames = missingNames(prescriptions.get(s.rxId), b["missing"])
        val now = clock.now()
        val set = com.lifolo.common.SqlSet().value("status", status).value("updated_at", Timestamp(now))
        var price = ""
        var eta = ""
        if (status == "cancelled") {
            set.value("reason", reason)
            contacts.closeMarks(s, now).forEach { (c, v) -> set.value(c, v) }
        }
        // the medicines reached the patient (the receipt is issued with it — InvoiceCreators.rxOrderUpdate)
        if (status == "delivered") contacts.closeMarks(s, now).forEach { (c, v) -> set.value(c, v) }
        if (status == "accepted") {
            price = str(b.string("price"), 40)
            eta = str(b.string("eta"), 60)
            set.value("price", price).value("eta", eta)
            // the priced lines of the receipt (docs/INVOICES.md)
            com.lifolo.invoices.InvoiceMath.lines(ctx.lang, b["lines"])?.let { set.json("invoice_lines", com.lifolo.invoices.InvoiceMath.linesJson(it)) }
        }
        val added = missedNames.filter { it !in s.missed }
        if (added.isNotEmpty()) set.textArray("missed", (s.missed + added).take(30))
        set.update(jdbc, "rx_orders", "id = ?", id)
        addMissed(ctx.user.id, added)
        val shop = s.pharmacyName
        val (en, bn) = when (status) {
            "ready" -> "$shop: your medicines are ready" to "$shop: আপনার ওষুধ তৈরি"
            "accepted" -> "$shop accepted your order" to "$shop অর্ডার নিয়েছে"
            "out" -> "$shop: your medicines are on the way" to "$shop: ওষুধ পথে আছে"
            "delivered" -> "$shop: your medicines were delivered" to "$shop: ওষুধ পৌঁছে দেওয়া হয়েছে" // bn-ok
            else -> "$shop can't provide your medicines" to "$shop ওষুধ দিতে পারবে না"
        }
        val body = when (status) {
            "accepted" -> listOf(price, eta).filter { it.isNotEmpty() }.joinToString(" · ")
            "cancelled" -> listOf(reason, if (added.isNotEmpty()) "নেই: ${added.joinToString(", ")}" else "").filter { it.isNotEmpty() }.joinToString(" · ")
            else -> ""
        }
        push.pushPatient(s.patientUid, en, bn, body, mapOf("type" to "rx", "id" to s.rxId, "rxId" to s.rxId))
        return mapOf("ok" to true)
    }

    private fun uniqueShopCode(): String {
        repeat(6) {
            val bytes = ByteArray(6).also { random.nextBytes(it) }
            val c = bytes.joinToString("") { SHOP_CODE_CHARS[(it.toInt() and 0xff) % SHOP_CODE_CHARS.length].toString() }
            if (jdbc.queryForList("SELECT 1 FROM pharmacies WHERE shop_code = ? LIMIT 1", c).isEmpty()) return c
        }
        throw IllegalStateException("shop code collision")
    }

    @Transactional
    fun home(ctx: ActionContext): Any {
        val shop = myPharmacy(ctx, forUpdate = true)
        var code = shop.shopCode
        if (code.isNullOrEmpty()) {
            code = uniqueShopCode()
            jdbc.update("UPDATE pharmacies SET shop_code = ? WHERE uid = ?", code, shop.id)
        }
        val missed = jdbc.query(
            "SELECT item_key, name, count, last_at FROM pharmacy_missed WHERE pharmacy_uid = ? ORDER BY count DESC, last_at DESC LIMIT 200",
            { rs, _ -> linkedMapOf<String, Any?>("k" to rs.getString(1), "name" to rs.getString(2), "count" to rs.getInt(3), "lastAt" to rs.getTimestamp(4).time) },
            shop.id,
        )
        return linkedMapOf("shopCode" to code, "online" to shop.online(), "hasLocation" to (shop.lat != null), "missed" to missed)
    }

    @Transactional
    fun onlineSet(ctx: ActionContext): Any {
        val shop = myPharmacy(ctx, forUpdate = true)
        val b = ctx.body
        val enabled = b.isTrue("enabled")
        if (enabled && shop.lat == null) {
            throw ctx.err(400, "Set the shop location first (edit your pharmacy)", "আগে দোকানের লোকেশন দিন (ফার্মেসির তথ্য বদলে)")
        }
        val radiusKm = b.int("radiusKm")?.takeIf { it in 1..30 }?.toInt() ?: 3
        val fee = str(b.string("fee"), 40)
        val note = str(b.string("note"), 120)
        jdbc.update(
            "UPDATE pharmacies SET online_enabled = ?, online_radius_km = ?, online_fee = ?, online_note = ?, online_updated_at = ? WHERE uid = ?",
            enabled, radiusKm, fee, note, Timestamp(clock.now()), shop.id,
        )
        return mapOf("online" to linkedMapOf("enabled" to enabled, "radiusKm" to radiusKm, "fee" to fee, "note" to note))
    }

    @Transactional
    fun missedClear(ctx: ActionContext): Any {
        myPharmacy(ctx)
        val k = ctx.body.string("k")
        if (k.isNullOrEmpty()) {
            jdbc.update("DELETE FROM pharmacy_missed WHERE pharmacy_uid = ?", ctx.user.id)
        } else {
            jdbc.update("DELETE FROM pharmacy_missed WHERE pharmacy_uid = ? AND item_key = ?", ctx.user.id, k)
        }
        return mapOf("ok" to true)
    }

    // ------------------------------------------------------------------ stock list

    @Transactional(readOnly = true)
    fun stockGet(ctx: ActionContext): Any {
        myPharmacy(ctx)
        val s = stock.load(ctx.user.id)
        return linkedMapOf("items" to s.names, "updatedAt" to if (s.exists) s.updatedAt else 0)
    }

    @Transactional
    fun stockSet(ctx: ActionContext): Any {
        myPharmacy(ctx, forUpdate = true)
        val uid = ctx.user.id
        val b = ctx.body
        val add = (b["add"]?.takeIf { it.isArray }?.toList() ?: emptyList()).map { str(Js.string(it), 100) }.filter { it.isNotEmpty() }.take(3000)
        val remove = (b["remove"]?.takeIf { it.isArray }?.toList() ?: emptyList()).map { RxMath.key(Js.toStr(it)) }.toSet()
        val s = stock.load(uid)
        val map = LinkedHashMap<String, String>()
        s.names.forEach { map[RxMath.key(it)] = it }
        for (n in add) if (RxMath.key(n) !in map) map[RxMath.key(n)] = n
        for (k in remove) map.remove(k)
        if (map.size > StockStore.MAX_ITEMS) throw ctx.err(400, "Up to 3000 medicines", "সর্বোচ্চ ৩০০০টা ওষুধ")
        val list = map.values.sortedWith(StockSnap.NAME_ORDER)
        // the shop-books prices stay as they are
        stock.save(uid, s, list, s.prices, s.mrp, s.exists && s.pricesAll, s.exists && s.mrpAll)
        return mapOf("items" to list)
    }
}
