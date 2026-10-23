package com.lifolo.demo

import com.lifolo.admin.kernel.DemoPoint
import com.lifolo.admin.kernel.DemoSeedContext
import com.lifolo.admin.kernel.DemoSeedPart
import com.lifolo.admin.kernel.ListingRules
import com.lifolo.appointments.HomeVisit
import com.lifolo.appointments.ProviderStore
import com.lifolo.appointments.Slot
import com.lifolo.books.DemoBooks
import com.lifolo.common.Phones
import com.lifolo.demo.DemoSql.Companion.DAY
import com.lifolo.demo.DemoSql.Companion.HOUR
import com.lifolo.demo.DemoSql.Companion.MIN
import com.lifolo.demo.DemoSql.Companion.r2
import com.lifolo.demo.DemoSql.Companion.r3
import com.lifolo.pharmacy.DrugList
import com.lifolo.pharmacy.RxMath
import com.lifolo.pharmacy.StockSnap
import com.lifolo.pharmacy.StockStore
import org.springframework.stereotype.Component

/**
 * demo/services.js without its ambulance part (that is P5's `AmbulanceDemo`, same step): pharmacies (the demo
 * "লাইফ ফার্মেসি" 24 h, five others, one waiting for approval) with stock, prices, the missed list, document photos and
 * call statistics; four prescriptions of the demo patient with online orders; "find a medicine" requests; labs with
 * test prices and ranges; home-care nurses and a doctor; the reports the owner sees in the panel. Same names, numbers
 * and hours as Node. Runs before the ambulances (Node order).
 */
@Component
class DemoServices(private val sql: DemoSql, private val providers: ProviderStore, private val stock: StockStore) : DemoSeedPart {
    override val step = 2
    override val label = "সেবা"
    override val order = -10

    private val jdbc get() = sql.jdbc

    private val docImg: String by lazy { DemoSql.resource("/com/lifolo/ambulance/demo-doc.b64") }

    companion object {
        const val SADAR = 320 // Gazipur Sadar
        const val KALIAKAIR = 318
        const val SREEPUR = 321

        /** demo/services.js drug(name, generic, form): the bundled list entry, else the given text */
        fun drug(name: String, generic: String, form: String): Map<String, String> {
            val r = DrugList.rows.firstOrNull { it.name == name && it.generic.contains(generic) && it.form == form }
            return if (r != null) mapOf("name" to r.name, "generic" to r.generic, "form" to r.form, "company" to r.company)
            else mapOf("name" to name, "generic" to generic, "form" to form, "company" to "")
        }

        fun at3(p: DemoPoint): Pair<Double, Double> = r3(p.lat) to r3(p.lng)

        val STOCK = listOf(
            "Napa", "Ace", "Seclo", "Losectil", "Maxpro", "Sergel", "Pantonix", "Fexo", "Alatrol", "Tofen", "Montene", "Monas",
            "Ceevit", "Calbo", "Xinc", "Comet", "Rosuva", "Ecosprin", "Bislol", "Amdocal", "Azithrocin", "Cef-3", "Flagyl", "Orsaline-N",
        )
    }

    private class Shop(
        val name: String,
        val licenseNo: String,
        val phone: String,
        val loc: Pair<Double, Double>,
        val address: String,
        val status: String,
        val upazilaId: Int = SADAR,
        val open24: Boolean = false,
        val openAt: String = "",
        val closeAt: String = "",
        val closedDays: List<Int> = emptyList(),
        val delivery: Boolean = false,
        val deliveryNote: String = "",
        val discount: Int = 0,
        val discountMin: Int = 0,
        val freeDeliveryOver: Int = 0,
        val discountNote: String = "",
        val docTypes: List<String> = listOf("license", "front"),
        val createdAt: Long? = null,
        val ratingSum: Int = 0,
        val ratingCount: Int = 0,
        val callsPerDay: Int = 1,
        /** behaviour, price, delivery → (sum, count) */
        val aspects: Map<String, Pair<Int, Int>>? = null,
        val closedUntil: Long? = null,
        val online: Online? = null,
        val shopCode: String? = null,
        val stock: List<String>? = null,
        val pricePct: Int? = null,
    )

    private class Online(val radiusKm: Int, val fee: String, val note: String)

    private fun verification(checks: List<String>, at: Long): Map<String, Any?> =
        linkedMapOf("verified_by" to "demo", "verified_by_email" to "demo@lifolo-demo.test", "verified_at" to sql.ts(at), "verification_checks" to sql.texts(checks))

    private fun pharmacy(ctx: DemoSeedContext, id: String, x: Shop) {
        val now = ctx.nowMs
        val created = x.createdAt ?: (now - 60 * DAY)
        val approved = x.status == "approved"
        val have = sql.docs("pharmacy", id, id, x.docTypes, created, docImg)
        val data = linkedMapOf<String, Any?>(
            "name" to x.name, "licenseNo" to x.licenseNo, "phone" to x.phone, "district" to DemoSql.GAZIPUR, "upazilaId" to x.upazilaId,
            "address" to x.address, "open24" to x.open24, "openAt" to (if (x.open24) "" else x.openAt), "closeAt" to (if (x.open24) "" else x.closeAt),
            "closedDays" to x.closedDays, "delivery" to x.delivery, "deliveryNote" to x.deliveryNote, "discount" to x.discount,
            // the discount conditions (19 Oct 2026) — per shop, shown on the list card
            "discountMin" to x.discountMin, "freeDeliveryOver" to x.freeDeliveryOver, "discountNote" to x.discountNote,
        )
        val row = linkedMapOf<String, Any?>(
            "uid" to id, "name" to x.name, "license_no" to x.licenseNo, "phone" to x.phone, "district" to DemoSql.GAZIPUR,
            "upazila_id" to x.upazilaId, "address" to x.address, "lat" to x.loc.first, "lng" to x.loc.second, "open24" to x.open24,
            "open_at" to data["openAt"], "close_at" to data["closeAt"], "closed_days" to sql.ints(x.closedDays, "smallint"),
            "delivery" to x.delivery, "delivery_note" to x.deliveryNote, "discount" to x.discount, "discount_min" to x.discountMin,
            "free_delivery_over" to x.freeDeliveryOver, "discount_note" to x.discountNote, "status" to x.status,
            "docs_have" to sql.texts(have), "created_at" to sql.ts(created), "updated_at" to sql.ts(created),
        )
        if (approved) {
            row["reviewed_at"] = sql.ts(created + DAY)
            row["reviewed_by"] = "demo"
            row["approved_name"] = x.name
            row["last_approved"] = PgJson(ListingRules.json(ListingRules.snapshot(data)))
            row.putAll(verification(listOf("license_no", "license_name", "license_addr", "license_valid", "license_seal", "signboard", "phone"), created + DAY))
            row["rating_sum"] = x.ratingSum
            row["rating_count"] = x.ratingCount
            // per aspect (behaviour / right medicine & fair price / delivery speed) — separate lines in the details
            x.aspects?.forEach { (k, v) ->
                row["aspect_${k}_sum"] = v.first
                row["aspect_${k}_count"] = v.second
            }
        }
        if (x.closedUntil != null) row["closed_until"] = sql.ts(x.closedUntil)
        if (x.online != null) {
            row["online_enabled"] = true
            row["online_radius_km"] = x.online.radiusKm
            row["online_fee"] = x.online.fee
            row["online_note"] = x.online.note
            row["online_updated_at"] = sql.ts(created + 2 * DAY)
        }
        if (x.shopCode != null) row["shop_code"] = x.shopCode
        sql.insert("pharmacies", row)
        if (x.stock != null) {
            // prices like a shop that keeps books: the MRP of the books demo, minus the shop's % — estimate and discount
            val priceOf = if (x.pricePct == null) null else DemoBooks.ITEMS.associate { RxMath.key(it.name) to it.price }
            val prices = LinkedHashMap<String, Double>()
            val mrp = LinkedHashMap<String, Double>()
            if (priceOf != null) {
                for (n in x.stock) {
                    val m = priceOf[RxMath.key(n)] ?: continue
                    prices[RxMath.key(n)] = com.lifolo.common.Js.round(m * (100 - x.pricePct!!)) / 100
                    if (x.pricePct > 0) mrp[RxMath.key(n)] = m
                }
            }
            stock.save(id, StockSnap.EMPTY, x.stock, prices, mrp, priceOf != null, priceOf != null)
            jdbc.update("UPDATE pharmacy_stock SET updated_at = ? WHERE pharmacy_uid = ?", sql.ts(now - 3 * DAY), id)
        }
        if (approved) sql.stats("pharmacy", id, now, x.callsPerDay, 20, 0.7)
    }

    override fun seed(ctx: DemoSeedContext) {
        val now = ctx.nowMs
        val p = ctx.user("patient").uid
        val gzp = DemoSql.GAZIPUR
        fun loc(dx: Double, dy: Double) = at3(ctx.near(dx, dy))

        // =====================================================================
        // pharmacies
        // =====================================================================
        val pharmacyUser = ctx.user("pharmacy")
        val ph = pharmacyUser.uid
        sql.profileDefaults(ph, pharmacyUser.name, "01200000150", gzp)
        // the demo pharmacy (login) — approved, online sales, stock; open 24 h like the rx part (night tests of the guide)
        pharmacy(
            ctx, ph,
            Shop(
                "ডেমো লাইফ ফার্মেসি", "DEMO-DL-1501", "01200000150", loc(0.3, 0.2), "শিববাড়ি মোড়, হাসপাতাল রোড, জয়দেবপুর, গাজীপুর", "approved",
                open24 = true, delivery = true, deliveryNote = "শিববাড়ি, জয়দেবপুর, চান্দনা চৌরাস্তা — ৩০ টাকা", discount = 10,
                freeDeliveryOver = 1000, discountNote = "নগদ বা বিকাশে একই ছাড়",
                docTypes = listOf("license", "front", "pharmacist"), createdAt = now - 90 * DAY, ratingSum = 54, ratingCount = 12, callsPerDay = 3,
                aspects = linkedMapOf("behaviour" to (56 to 12), "price" to (44 to 10), "delivery" to (29 to 7)),
                online = Online(10, "৩০ টাকা", "সকাল ৮টা – রাত ১১টা ডেলিভারি"), shopCode = "DEMQKP", stock = STOCK,
            ),
        )
        // the "could not give" list (buyers asked, the shop did not have it)
        for ((n, c, d) in listOf(Triple("Mixtard 30", 4, 2), Triple("Thyrox 50", 2, 5), Triple("Nexum 20", 1, 9))) {
            sql.insert("pharmacy_missed", linkedMapOf("pharmacy_uid" to ph, "item_key" to RxMath.missKey(n), "name" to n, "count" to c, "last_at" to sql.ts(now - d * DAY)))
        }

        // other pharmacies (owners without login)
        val ph24 = ctx.extraUser("svc-ph-24h", mapOf("name" to "ডেমো ২৪ ঘণ্টা ফার্মেসির মালিক", "phone" to "01200000151", "place_name" to gzp))
        pharmacy(
            ctx, ph24,
            Shop(
                "ডেমো ২৪ ঘণ্টা ফার্মেসি", "DEMO-DL-1502", "01200000151", loc(0.8, -0.4), "তাজউদ্দীন মেডিকেল কলেজ হাসপাতাল গেট, জয়দেবপুর, গাজীপুর", "approved",
                open24 = true, delivery = true, deliveryNote = "রাতেও ডেলিভারি, ৫০ টাকা", discount = 5,
                discountMin = 300, discountNote = "রাত ১২টার পর ছাড় নেই", pricePct = 5,
                ratingSum = 38, ratingCount = 9, callsPerDay = 4,
                aspects = linkedMapOf("behaviour" to (37 to 9), "price" to (26 to 6), "delivery" to (17 to 4)),
                online = Online(5, "৫০ টাকা", "২৪ ঘণ্টা"),
                stock = listOf("Napa", "Ace", "Seclo", "Maxpro", "Fexo", "Alatrol", "Montene", "Ceevit", "Comet", "Ecosprin", "Orsaline-N", "Flagyl"),
            ),
        )
        val phModel = ctx.extraUser("svc-ph-model", mapOf("name" to "ডেমো নিউ মডেল ফার্মেসির মালিক", "phone" to "01200000152", "place_name" to gzp))
        pharmacy(
            ctx, phModel,
            Shop(
                "ডেমো নিউ মডেল ফার্মেসি", "DEMO-DL-1503", "01200000152", loc(-0.6, 0.5), "জয়দেবপুর বাজার, গাজীপুর", "approved",
                openAt = "09:00", closeAt = "22:00", closedDays = listOf(5), discount = 12,
                discountMin = 500, discountNote = "শুধু নগদে", pricePct = 12,
                ratingSum = 21, ratingCount = 6, callsPerDay = 2,
                aspects = linkedMapOf("behaviour" to (22 to 6), "price" to (13 to 4)),
                stock = listOf("Napa", "Seclo", "Sergel", "Alatrol", "Monas", "Calbo", "Bislol", "Amdocal", "Rosuva"),
            ),
        )
        val phSeba = ctx.extraUser("svc-ph-seba", mapOf("name" to "ডেমো সেবা ড্রাগ হাউসের মালিক", "phone" to "01200000153", "place_name" to gzp))
        pharmacy(
            ctx, phSeba,
            Shop(
                "ডেমো সেবা ড্রাগ হাউস", "DEMO-DL-1504", "01200000153", loc(1.2, 0.9), "ছায়াবীথি মোড়, জয়দেবপুর, গাজীপুর", "approved",
                openAt = "08:00", closeAt = "23:00", delivery = true, deliveryNote = "কাছাকাছি ফ্রি", discount = 3, freeDeliveryOver = 500,
                // closed for a while (renovation) — always "closed"
                closedUntil = now + 5 * DAY, ratingSum = 12, ratingCount = 3,
            ),
        )
        val phKali = ctx.extraUser("svc-ph-kaliakair", mapOf("name" to "ডেমো কালিয়াকৈর ফার্মেসির মালিক", "phone" to "01200000154", "place_name" to gzp))
        pharmacy(
            ctx, phKali,
            Shop(
                "ডেমো কালিয়াকৈর মেডিসিন কর্নার", "DEMO-DL-1505", "01200000154", loc(-19.5, 8.0), "কালিয়াকৈর বাজার, গাজীপুর", "approved",
                upazilaId = KALIAKAIR, openAt = "08:00", closeAt = "21:00",
            ),
        )
        // waiting for approval (the owner looks at the papers in the web panel)
        val phNew = ctx.extraUser("svc-ph-new", mapOf("name" to "ডেমো আরোগ্য ফার্মেসির মালিক", "phone" to "01200000155", "place_name" to gzp))
        pharmacy(
            ctx, phNew,
            Shop(
                "ডেমো আরোগ্য ফার্মেসি", "DEMO-DL-1506", "01200000155", loc(0.2, -0.9), "জয়দেবপুর রেলগেট, গাজীপুর", "pending",
                openAt = "08:30", closeAt = "22:30", delivery = true, discount = 8, createdAt = now - 1 * DAY,
            ),
        )

        // ---------- online orders: prescriptions (patient: the demo patient, doctor: the demo doctor) ----------
        val doctor = ctx.user("doctor")
        val patientName = ctx.user("patient").name
        fun med(name: String, form: String, strength: String, dose: String, days: Int, timing: String = "after", note: String = "") =
            listOf<Any?>(name, form, strength, dose, timing, days, note)

        fun rx(id: String, code: String, createdAt: Long, complaints: String, diagnosis: String, medicines: List<List<Any?>>, advice: String = "", bp: String? = null) {
            sql.insert(
                "prescriptions",
                linkedMapOf(
                    "id" to id, "code" to code, "status" to "active", "created_at" to sql.ts(createdAt), "doctor_uid" to doctor.uid,
                    "doctor_name" to doctor.name, "doctor_degrees" to "এমবিবিএস, এফসিপিএস (মেডিসিন) — ডেমো", "doctor_specialties" to sql.texts(listOf("medicine")),
                    "doctor_reg_no" to "DEMO-A-10001", "doctor_chamber" to null, "patient_name" to patientName, "patient_age" to 45, "patient_gender" to "male",
                    "patient_uid" to p, "rx_patient_id" to null, "appointment_id" to null, "complaints" to complaints,
                    "finding_bp" to (bp ?: "১৩০/৮৫"), "finding_pulse" to "৮০/মিনিট", "finding_temp" to "", "finding_weight" to "৭০ কেজি",
                    "finding_spo2" to "", "finding_other" to "", "diagnosis" to diagnosis, "tests" to sql.texts(emptyList()), "test_place" to "",
                    "test_place_id" to null, "advice" to advice, "follow_up_date" to null, "follow_up_book_date" to null, "follow_up_note" to "",
                    "chamber_index" to null,
                ),
            )
            medicines.forEachIndexed { i, m ->
                sql.insert(
                    "prescription_medicines",
                    linkedMapOf(
                        "rx_id" to id, "idx" to i, "name" to m[0], "form" to m[1], "strength" to m[2], "dose" to m[3], "timing" to m[4],
                        "days" to m[5], "note" to m[6],
                    ),
                )
            }
        }
        val rxA = "demo-svc-rx-a"
        val rxB = "demo-svc-rx-b"
        val rxC = "demo-svc-rx-c"
        val rxD = "demo-svc-rx-d"
        rx(
            rxA, "DEMSVCRXA2", now - 1 * DAY, "জ্বর ৩ দিন, গলা ব্যথা, হাঁচি", "ভাইরাল জ্বর, অ্যালার্জিক রাইনাইটিস",
            listOf(med("Napa", "Tablet", "500 mg", "1+1+1", 5), med("Seclo", "Capsule", "20 mg", "1+0+1", 14, "before"), med("Fexo", "Tablet", "120 mg", "0+0+1", 7)),
            "প্রচুর পানি ও তরল খাবেন। জ্বর ১০২°-এর বেশি হলে যোগাযোগ করবেন।",
        )
        rx(
            rxB, "DEMSVCRXB3", now - 2 * DAY, "রাতে শ্বাসকষ্ট, কাশি", "অ্যাজমা (হালকা)",
            listOf(med("Montene", "Tablet", "10 mg", "0+0+1", 30), med("Alatrol", "Tablet", "10 mg", "0+0+1", 10)),
            "ধুলাবালি এড়িয়ে চলবেন।",
        )
        rx(
            rxC, "DEMSVCRXC4", now - (2.5 * DAY).toLong(), "দুর্বলতা, মাড়ি থেকে রক্ত", "ভিটামিন সি-র ঘাটতি",
            listOf(med("Ceevit", "Tablet", "250 mg", "1+0+0", 30)), bp = "১২০/৮০",
        )
        // dispensed yesterday by the demo pharmacy
        val dispId = jdbc.queryForObject(
            "INSERT INTO prescription_dispensings (rx_id, pharmacy_uid, pharmacy_name, at) VALUES (?, ?, ?, ?) RETURNING id",
            Long::class.java, rxC, ph, "ডেমো লাইফ ফার্মেসি", sql.ts(now - 1 * DAY),
        )
        sql.insert("prescription_dispensed_items", linkedMapOf("dispensing_id" to dispId, "med_idx" to 0, "qty" to 30))
        rx(
            rxD, "DEMSVCRXD5", now - 3 * HOUR, "ডায়াবেটিস ও কোলেস্টেরলের নিয়মিত চেকআপ", "টাইপ-২ ডায়াবেটিস, ডিসলিপিডেমিয়া",
            listOf(med("Comet", "Tablet", "500 mg", "1+0+1", 30), med("Rosuva", "Tablet", "10 mg", "0+0+1", 30), med("Ecosprin", "Tablet", "75 mg", "0+1+0", 0, "after", "চলবে")),
            "মিষ্টি ও তেল-চর্বি কম। রোজ ৩০ মিনিট হাঁটবেন।", bp = "১৪০/৯০",
        )
        fun order(id: String, rxId: String, code: String, pharmacyId: String, pharmacyName: String, status: String, createdAt: Long, extra: Map<String, Any?> = emptyMap(), deliveryFee: String = "৩০ টাকা", note: String = "") {
            val row = linkedMapOf<String, Any?>(
                "id" to id, "rx_id" to rxId, "code" to code, "pharmacy_uid" to pharmacyId, "pharmacy_name" to pharmacyName, "patient_uid" to p,
                "patient_name" to patientName, "status" to status, "mode" to "online", "address" to "বাসা ১২, রোড ৩, শিববাড়ি, জয়দেবপুর, গাজীপুর",
                "phone" to "01200000159", "delivery_fee" to deliveryFee, "note" to note, "created_at" to sql.ts(createdAt),
            )
            row.putAll(extra)
            sql.insert("rx_orders", row)
        }
        // 1) new — the demo pharmacy presses "take the order"
        order("demo-svc-ord-a", rxA, "DEMSVCRXA2", ph, "ডেমো লাইফ ফার্মেসি", "sent", now - 25 * MIN, note = "গেটের দারোয়ানের কাছে দিলেও চলবে")
        // 2) another shop could not → the demo pharmacy took it and sent it (on the way)
        order(
            "demo-svc-ord-b0", rxB, "DEMSVCRXB3", ph24, "ডেমো ২৪ ঘণ্টা ফার্মেসি", "cancelled", now - 5 * HOUR,
            mapOf("reason" to "ওষুধ নেই", "missed" to sql.texts(listOf("Montene")), "updated_at" to sql.ts(now - (4.5 * HOUR).toLong())),
            deliveryFee = "৫০ টাকা",
        )
        order(
            "demo-svc-ord-b", rxB, "DEMSVCRXB3", ph, "ডেমো লাইফ ফার্মেসি", "out", now - 4 * HOUR,
            mapOf("price" to "৪৮০ টাকা", "eta" to "আজ সন্ধ্যা ৭টার মধ্যে", "updated_at" to sql.ts(now - 1 * HOUR)),
        )
        // 3) an earlier order — delivered yesterday (online, within 7 days → the patient may rate all three aspects)
        order(
            "demo-svc-ord-c", rxC, "DEMSVCRXC4", ph, "ডেমো লাইফ ফার্মেসি", "done", now - (1.2 * DAY).toLong(),
            mapOf("price" to "১৮০ টাকা", "eta" to "এক ঘণ্টার মধ্যে", "done_at" to sql.ts(now - 1 * DAY), "updated_at" to sql.ts(now - 1 * DAY)),
        )

        // ---------- find a medicine ----------
        val buyer = ctx.extraUser("svc-buyer", mapOf("name" to "ডেমো ক্রেতা সালমা", "phone" to "01200000158", "place_name" to gzp))
        val centre = r2(com.lifolo.admin.kernel.DemoSeed.CENTER_LAT) to r2(com.lifolo.admin.kernel.DemoSeed.CENTER_LNG)
        // qty/unit: how much the buyer needs (MedQty); a target's Triple = (has it, how many it has, when) — the unit is
        // the find's; have FALSE = ignored (RC3 "নেই"), the buyer sees nothing
        fun find(
            id: String, uid: String, d: Map<String, String>, at: Pair<Double, Double>, status: String, created: Long, expires: Long, closedAt: Long?,
            qty: Pair<Int, String>?, targets: List<Pair<String, Triple<Boolean, Int?, Long>?>>,
        ) {
            sql.insert(
                "med_finds",
                linkedMapOf(
                    "id" to id, "uid" to uid, "district" to gzp, "upazila_id" to SADAR, "lat" to at.first, "lng" to at.second,
                    "drug_name" to d["name"], "drug_generic" to d["generic"], "drug_form" to d["form"], "drug_company" to d["company"],
                    "qty" to qty?.first, "unit" to qty?.second,
                    "status" to status, "created_at" to sql.ts(created), "expires_at" to sql.ts(expires), "closed_at" to sql.ts(closedAt),
                ),
            )
            for ((shop, ans) in targets) {
                sql.insert(
                    "med_find_targets",
                    linkedMapOf(
                        "find_id" to id, "pharmacy_uid" to shop, "have" to ans?.first, "have_qty" to ans?.second,
                        "have_unit" to ans?.second?.let { qty?.second }, "answered_at" to sql.ts(ans?.third),
                    ),
                )
            }
        }
        // the patient's open find (3 strips): one shop has 5 strips (all of it), one only 1 strip, the demo pharmacy has
        // not answered yet (really open for 6 hours; in the demo for days, so it can still be seen later)
        find(
            "demo-svc-find-1", p, drug("Montene", "10 mg", "Tablet"), centre, "open", now - 40 * MIN, now + 3 * DAY, null, 3 to "strip",
            listOf(ph to null, ph24 to Triple(true, 5, now - 20 * MIN), phModel to Triple(true, 1, now - 15 * MIN)),
        )
        // an earlier (closed) find — 2 strips wanted, the demo pharmacy had only 1
        find(
            "demo-svc-find-2", p, drug("Rosuva", "10 mg", "Tablet"), centre, "closed", now - 1 * DAY, now + 2 * DAY, now - 22 * HOUR, 2 to "strip",
            listOf(ph to Triple(true, 1, now - 23 * HOUR), phModel to null),
        )
        // someone else's find (14 pieces) — the demo pharmacy answers how many it has (or leaves it)
        val bp = ctx.near(0.5, -0.5)
        find(
            "demo-svc-find-3", buyer, drug("Sergel", "20 mg", "Capsule"), r2(bp.lat) to r2(bp.lng), "open", now - 10 * MIN, now + 3 * DAY, null, 14 to "pcs",
            listOf(ph to null, ph24 to null),
        )

        // =====================================================================
        // labs
        // =====================================================================
        fun lab(id: String, name: String, address: String, licenseNo: String, phone: String, hours: String, status: String, homeCollection: Boolean = false, upazilaId: Int = SADAR, createdAt: Long? = null) {
            val created = createdAt ?: (now - 60 * DAY)
            val have = sql.docs("lab", id, id, listOf("license", "front"), created, docImg)
            val row = linkedMapOf<String, Any?>(
                "uid" to id, "name" to name, "facility_id" to null, "district" to gzp, "upazila_id" to upazilaId, "address" to address,
                "license_no" to licenseNo, "phone" to phone, "hours" to hours, "home_collection" to homeCollection, "updated_at" to sql.ts(created),
                "status" to status, "docs_have" to sql.texts(have), "created_at" to sql.ts(created),
            )
            if (status == "approved") {
                row["reviewed_at"] = sql.ts(created + DAY)
                row["reviewed_by"] = "demo"
                row["approved_name"] = name
                row.putAll(verification(listOf("license_no", "license_name", "license_valid", "license_type", "signboard", "phone"), created + DAY))
            }
            sql.insert("labs", row)
        }
        val labUser = ctx.user("lab")
        val labId = labUser.uid
        sql.profileDefaults(labId, labUser.name, "01200000160", gzp)
        lab(
            labId, "ডেমো সিটি ডায়াগনস্টিক সেন্টার", "হাসপাতাল রোড, শিববাড়ি, জয়দেবপুর, গাজীপুর", "DEMO-HSM-2201", "01200000160",
            "সকাল ৮টা – রাত ১০টা (শুক্রবার বিকাল ৩টা থেকে)", "approved", homeCollection = true, createdAt = now - 80 * DAY,
        )
        // the lab's own machine ranges (pre-filled in the report form)
        sql.insert("lab_ranges", linkedMapOf("lab_uid" to labId, "test_key" to "cbc.hb", "low" to 11.5, "high" to 16.5))
        sql.insert("lab_ranges", linkedMapOf("lab_uid" to labId, "test_key" to "rbs.glu", "low" to 3.9, "high" to 7.8))
        val labPop = ctx.extraUser("svc-lab-popular", mapOf("name" to "ডেমো পপুলার প্যাথলজির মালিক", "phone" to "01200000161", "place_name" to gzp))
        lab(labPop, "ডেমো পপুলার প্যাথলজি", "স্টেশন রোড, জয়দেবপুর, গাজীপুর", "DEMO-HSM-2202", "01200000161", "সকাল ৭টা – রাত ৯টা", "approved")
        val labTri = ctx.extraUser("svc-lab-sreepur", mapOf("name" to "ডেমো শ্রীপুর ডায়াগনস্টিকের মালিক", "phone" to "01200000162", "place_name" to gzp))
        lab(
            labTri, "ডেমো শ্রীপুর ডায়াগনস্টিক", "মাওনা চৌরাস্তা, শ্রীপুর, গাজীপুর", "DEMO-HSM-2203", "01200000162", "সকাল ৯টা – সন্ধ্যা ৭টা", "approved",
            homeCollection = true, upazilaId = SREEPUR,
        )
        // no price list — patients see "price not known — ask on the phone"
        val labRoyal = ctx.extraUser("svc-lab-royal", mapOf("name" to "ডেমো রয়েল ল্যাবের মালিক", "phone" to "01200000164", "place_name" to gzp))
        lab(labRoyal, "ডেমো রয়েল ল্যাব", "রথখোলা, জয়দেবপুর, গাজীপুর", "DEMO-HSM-2205", "01200000164", "সকাল ৮টা – রাত ৯টা", "approved", createdAt = now - 40 * DAY)

        // tests and prices (labprices.js) — the demo lab's full list, 10 % off, home collection 150 taka;
        // the others half a list with other prices (the royal lab has none)
        fun prices(lab: String, discountPct: Int?, homeFee: Int?, updated: Long, items: List<List<Any?>>) {
            sql.insert("lab_prices", linkedMapOf("lab_uid" to lab, "discount_pct" to discountPct, "home_fee" to homeFee, "updated_at" to sql.ts(updated)))
            items.forEachIndexed { i, it ->
                sql.insert(
                    "lab_price_items",
                    linkedMapOf("lab_uid" to lab, "idx" to i, "code" to it[0], "name" to it[1], "price" to it[2], "tat" to it[3], "enabled" to (it.getOrNull(4) ?: true)),
                )
            }
        }
        fun pr(code: String?, name: String, price: Int, tat: String, on: Boolean = true) = listOf<Any?>(code, name, price, tat, on)
        prices(
            labId, 10, 150, now - 5 * DAY,
            listOf(
                pr("cbc", "CBC (Complete Blood Count)", 400, "একই দিন"),
                pr("esr", "ESR", 150, "একই দিন"),
                pr("rbs", "Random Blood Sugar (RBS)", 100, "১ ঘণ্টা"),
                pr("fbs", "Fasting Blood Sugar (FBS)", 100, "১ ঘণ্টা"),
                pr("2habf", "2 Hours After Breakfast (2HABF)", 100, "১ ঘণ্টা"),
                pr("hba1c", "HbA1c", 1000, "একই দিন"),
                pr("lipid", "Lipid Profile", 1200, "একই দিন"),
                pr("creat", "Serum Creatinine", 400, "একই দিন"),
                pr("alt", "SGPT (ALT)", 400, "একই দিন"),
                pr("uric", "Serum Uric Acid", 450, "একই দিন"),
                pr("tsh", "TSH", 900, "২৪ ঘণ্টা"),
                pr("thyroid", "Thyroid Profile (TSH, FT4, FT3)", 2200, "২৪ ঘণ্টা"),
                pr("urine_re", "Urine R/E", 200, "একই দিন"),
                pr("blood_group", "Blood Grouping & Rh Typing", 150, "৩০ মিনিট"),
                pr("hbsag", "HBsAg", 500, "একই দিন"),
                pr("ecg", "ECG", 400, "৩০ মিনিট"),
                // not on the form — typed by the lab
                pr(null, "Serum Vitamin B12", 1800, "৪৮ ঘণ্টা"),
                // machine broken — not done now
                pr("usg_abd", "USG of Whole Abdomen", 1500, "একই দিন", false),
            ),
        )
        prices(
            labPop, null, null, now - 12 * DAY,
            listOf(
                pr("cbc", "CBC (Complete Blood Count)", 350, "একই দিন"),
                pr("rbs", "Random Blood Sugar (RBS)", 80, "১ ঘণ্টা"),
                pr("fbs", "Fasting Blood Sugar (FBS)", 80, "১ ঘণ্টা"),
                pr("hba1c", "HbA1c", 950, "২৪ ঘণ্টা"),
                pr("lipid", "Lipid Profile", 1100, "২৪ ঘণ্টা"),
                pr("creat", "Serum Creatinine", 350, "একই দিন"),
                pr("urine_re", "Urine R/E", 150, "একই দিন"),
                pr("ecg", "ECG", 350, "৩০ মিনিট"),
                pr("usg_abd", "USG of Whole Abdomen", 1400, "একই দিন"),
            ),
        )
        prices(
            labTri, 5, 100, now - 20 * DAY,
            listOf(
                pr("cbc", "CBC (Complete Blood Count)", 300, "২৪ ঘণ্টা"),
                pr("rbs", "Random Blood Sugar (RBS)", 70, "১ ঘণ্টা"),
                pr("hba1c", "HbA1c", 900, "২ দিন"),
                pr("tsh", "TSH", 850, "২ দিন"),
                pr("urine_re", "Urine R/E", 120, "একই দিন"),
            ),
        )
        val labNew = ctx.extraUser("svc-lab-new", mapOf("name" to "ডেমো নিউ লাইফ ল্যাবের মালিক", "phone" to "01200000163", "place_name" to gzp))
        lab(labNew, "ডেমো নিউ লাইফ ল্যাব", "ছায়াবীথি, জয়দেবপুর, গাজীপুর", "DEMO-HSM-2204", "01200000163", "সকাল ৮টা – রাত ৮টা", "pending", createdAt = now - 2 * DAY)

        // =====================================================================
        // home care (nurses / a doctor)
        // =====================================================================
        val nurseUser = ctx.user("homecare")
        val nurse = nurseUser.uid
        sql.profileDefaults(nurse, nurseUser.name, "01200000180", gzp)
        jdbc.update("UPDATE user_profiles SET gender = 'female' WHERE uid = ?", nurse)
        // with phone verification on, the nurse may change her settings (the profile number counts as verified)
        val nPhone = Phones.normalizeBd(jdbc.queryForList("SELECT phone FROM user_profiles WHERE uid = ?", String::class.java, nurse).firstOrNull())
        if (nPhone != null) {
            sql.upsert("user_state", listOf("uid"), linkedMapOf("uid" to nurse, "verified_phone" to nPhone, "phone_verified_at" to sql.ts(now - 30 * DAY)))
        }
        fun homePro(
            uid: String, type: String, name: String, regNo: String, workplace: String, email: String = "", badgeStatus: String = "approved",
            specialties: List<String> = emptyList(), degrees: String = "", gender: String? = null, home: HomeVisit? = null,
            ratingSum: Int = 0, ratingCount: Int = 0, callsPerDay: Int = 1, recent: List<DemoSql.Recent> = emptyList(),
        ) {
            val have = sql.docs("badge", uid, uid, if (type == "nurse") listOf("cert", "idcard") else listOf("cert"), now - 70 * DAY, docImg)
            val badge = linkedMapOf<String, Any?>(
                "uid" to uid, "type" to type, "reg_no" to regNo, "workplace" to workplace, "name" to name, "email" to email, "status" to badgeStatus,
                "docs_have" to sql.texts(have), "created_at" to sql.ts(now - 70 * DAY),
            )
            if (badgeStatus != "pending") {
                badge["reviewed_at"] = sql.ts(now - 69 * DAY)
                badge["reviewed_by"] = "demo"
            }
            sql.insert("badges", badge)
            if (home == null) return
            sql.insert(
                "providers",
                linkedMapOf(
                    "uid" to uid, "type" to type, "name" to name, "listed" to false, "appointments" to false, "active" to false,
                    "specialties" to sql.texts(specialties), "degrees" to degrees, "gender" to gender, "legacy_chamber" to false,
                    "updated_at" to sql.ts(now - 10 * DAY), "rating_sum" to ratingSum, "rating_count" to ratingCount,
                ),
            )
            providers.saveHome(uid, home, now - 60 * DAY)
            sql.stats("home", uid, now, callsPerDay, 10, 0.6, recent)
        }
        homePro(
            nurse, "nurse", nurseUser.name, "DEMO-BNMC-55001", "শহীদ তাজ উদ্দীন আহমদ মেডিকেল কলেজ হাসপাতাল (ডেমো)", email = nurseUser.email,
            specialties = listOf("general_nursing", "home_care"), degrees = "ডিপ্লোমা ইন নার্সিং অ্যান্ড মিডওয়াইফারি", gender = "female",
            home = HomeVisit(
                enabled = true, radiusKm = 10, upazilaIds = listOf(SADAR, KALIAKAIR),
                services = listOf("injection", "saline", "cannula", "dressing", "bp_sugar", "elderly_care", "newborn_care"),
                phone = "01200000180", schedule = "জরুরি হলে রাতেও আসি", times = listOf(Slot(listOf(0, 1, 2, 3, 4, 6), "09:00", "18:00")),
                noFee = false, fee = "৩০০–৫০০ টাকা (সেবা অনুযায়ী)",
            ),
            ratingSum = 27, ratingCount = 6, callsPerDay = 2,
            recent = listOf(
                DemoSql.Recent(5, "আম্মার স্যালাইন খুব যত্ন করে দিয়েছেন।", now - 12 * DAY, "demo0000000000b1"),
                DemoSql.Recent(4, "সময়মতো এসেছেন, ব্যবহার ভালো।", now - 5 * DAY, "demo0000000000b2"),
            ),
        )
        // the patient called the nurse 5 hours ago → may rate
        sql.insert("home_calls", linkedMapOf("uid" to p, "provider_uid" to nurse, "at" to sql.ts(now - 5 * HOUR), "first_at" to sql.ts(now - 5 * HOUR)))

        val sh = loc(-1.0, 1.0)
        val nShahin = ctx.extraUser(
            "svc-nurse-shahin",
            mapOf("name" to "ডেমো নার্স শাহীন", "phone" to "01200000181", "place_name" to gzp, "lat" to sh.first, "lng" to sh.second, "location_source" to "gps", "gender" to "male"),
        )
        homePro(
            nShahin, "nurse", "ডেমো নার্স শাহীন", "DEMO-BNMC-55002", "ডেমো কমিউনিটি হাসপাতাল",
            specialties = listOf("general_nursing", "icu"), degrees = "বিএসসি ইন নার্সিং", gender = "male",
            home = HomeVisit(
                enabled = true, radiusKm = 5, upazilaIds = emptyList(), services = listOf("injection", "saline", "catheter", "post_op", "dressing"),
                phone = "01200000181", schedule = "", times = listOf(Slot(listOf(5, 6), "08:00", "20:00"), Slot(listOf(0, 1, 2, 3, 4), "17:00", "22:00")),
                noFee = false, fee = "৪০০ টাকা",
            ),
            ratingSum = 7, ratingCount = 2,
        )
        val nMousumi = ctx.extraUser(
            "svc-nurse-mousumi",
            mapOf("name" to "ডেমো নার্স মৌসুমী", "phone" to "01200000182", "place_name" to gzp, "location_source" to "district", "gender" to "female"),
        )
        homePro(
            nMousumi, "nurse", "ডেমো নার্স মৌসুমী", "DEMO-BNMC-55003", "ডেমো শ্রীপুর উপজেলা স্বাস্থ্য কমপ্লেক্স",
            specialties = listOf("midwife", "pediatric_nursing"), degrees = "ডিপ্লোমা ইন মিডওয়াইফারি", gender = "female",
            home = HomeVisit(
                enabled = true, radiusKm = 5, upazilaIds = listOf(SREEPUR, SADAR), services = listOf("newborn_care", "bp_sugar", "injection"),
                phone = "01200000182", schedule = "আগে ফোন করে সময় ঠিক করে নিন", times = emptyList(), noFee = false, fee = "৩০০ টাকা",
            ),
        )
        val rf = loc(1.0, -1.0)
        val dRafiq = ctx.extraUser(
            "svc-doc-rafiq",
            mapOf("name" to "ডা. ডেমো রফিক", "phone" to "01200000183", "place_name" to gzp, "lat" to rf.first, "lng" to rf.second, "location_source" to "gps", "gender" to "male"),
        )
        homePro(
            dRafiq, "doctor", "ডা. ডেমো রফিক", "DEMO-A-10002", "ডেমো জেনারেল হাসপাতাল",
            specialties = listOf("medicine", "general"), degrees = "এমবিবিএস (ডেমো)", gender = "male",
            home = HomeVisit(
                enabled = true, radiusKm = 10, upazilaIds = emptyList(), services = listOf("checkup", "elderly_care", "bedridden", "follow_up"),
                phone = "01200000183", schedule = "সন্ধ্যার পর", times = listOf(Slot(listOf(0, 1, 2, 3, 4, 6), "18:00", "22:00")), noFee = false, fee = "১০০০ টাকা",
            ),
            ratingSum = 9, ratingCount = 2,
        )
        // a nurse's badge waiting for approval (after approval she switches home care on herself)
        val nTania = ctx.extraUser("svc-nurse-tania", mapOf("name" to "ডেমো নার্স তানিয়া", "phone" to "01200000184", "place_name" to gzp, "gender" to "female"))
        homePro(
            nTania, "nurse", "ডেমো নার্স তানিয়া", "DEMO-BNMC-55004", "ডেমো সূর্যের হাসি ক্লিনিক", badgeStatus = "pending",
            email = "nurse-tania@lifolo-demo.test",
        )

        // =====================================================================
        // reports (the owner sees them as admin) — only people who called from the app may report
        // =====================================================================
        fun report(kind: String, path: String, reporter: String, reason: String, ago: Long) {
            sql.insert(
                "mod_reports",
                linkedMapOf(
                    "id" to "${path.replace('/', '_')}__$reporter", "kind" to kind, "target_path" to path, "reporter_uid" to reporter, "reason" to reason,
                    "status" to "open", "created_at" to sql.ts(now - ago),
                ),
            )
        }
        sql.insert("pharmacy_calls", linkedMapOf("uid" to p, "pharmacy_uid" to phModel, "at" to sql.ts(now - 2 * DAY), "first_at" to sql.ts(now - 2 * DAY)))
        report("pharmacy", "pharmacies/$phModel", p, "ফোনে বলেছিল ওষুধ আছে, গিয়ে দেখি নেই। দামও MRP-র বেশি চাইল।", 1 * DAY)
        // the buyer's call to "মমতা" is written by the ambulance part (AmbulanceDemo)
        report("ambulance", "ambulances/demo-svc-amb-momota", buyer, "বলেছিল ২০ মিনিটে আসবে, দেড় ঘণ্টা পর এসেছে। ভাড়াও দ্বিগুণ চেয়েছে।", 2 * DAY)
        sql.insert("home_calls", linkedMapOf("uid" to buyer, "provider_uid" to nShahin, "at" to sql.ts(now - 4 * DAY), "first_at" to sql.ts(now - 4 * DAY)))
        report("homecare", "providers/$nShahin", buyer, "সময় দিয়ে আসেননি, ফোনও ধরেননি।", 3 * DAY)
    }
}
