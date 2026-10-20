package com.lifolo.lab

import com.lifolo.common.Ids
import com.lifolo.common.Phones
import com.lifolo.common.StoredFiles
import com.lifolo.common.intOrNull
import com.lifolo.common.stringArray
import com.lifolo.rx.P3Sql
import com.lifolo.rx.eachRow
import com.lifolo.rx.rows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet

/** one row of a lab report (labOrders.report.items[]) — optional keys only when Node stored them */
data class ReportItem(
    val test: String,
    val result: String,
    val unit: String,
    val range: String,
    val flag: String,
    val group: String? = null,
    val code: String? = null,
    val key: String? = null,
    val low: Double? = null,
    val high: Double? = null,
    val long: Boolean = false,
) {
    fun toMap(): LinkedHashMap<String, Any?> = linkedMapOf<String, Any?>(
        "test" to test, "result" to result, "unit" to unit, "range" to range, "flag" to flag,
    ).apply {
        if (group != null) put("group", group)
        if (code != null) put("code", code)
        if (key != null) put("key", key)
        if (low != null) put("low", low)
        if (high != null) put("high", high)
        if (long) put("long", true)
    }
}

data class LabReport(val items: List<ReportItem>, val note: String, val photoCount: Int, val photos: List<String>?, val reportedAt: Long)

/** labOrders/{id} */
data class LabOrder(
    val id: String,
    val rxId: String?,
    val code: String?,
    val labId: String,
    val labName: String,
    val patientUid: String?,
    val patientName: String,
    val patientAge: Int?,
    val patientGender: String,
    val doctorUid: String?,
    val doctorName: String,
    val tests: List<String>,
    val status: String,
    val createdAt: Long,
    val reportedAt: Long?,
    val home: LabHome?,
    val prices: PriceSnap?,
    val report: LabReport?,
)

/** rxReviews/{labOrderId} — the doctor's (paid) look at a lab report */
data class Review(
    val id: String,
    val rxId: String?,
    val doctorUid: String,
    val doctorName: String,
    val patientUid: String?,
    val patientName: String,
    val labName: String,
    val tests: List<String>,
    val status: String,
    val fee: String,
    val noFee: Boolean,
    val payNumber: String,
    val payMethods: List<String>,
    val method: String,
    val trxId: String,
    val rejectNote: String,
    val comment: String,
    val newRxId: String?,
    val createdAt: Long,
    val updatedAt: Long,
) {
    /** rxnet.js reviewView (TrxID only for the doctor's own list) */
    fun view(withSecret: Boolean = false): LinkedHashMap<String, Any?> = linkedMapOf<String, Any?>(
        "id" to id, "rxId" to rxId, "labOrderId" to id, "doctorUid" to doctorUid, "doctorName" to doctorName,
        "patientName" to patientName, "labName" to labName, "tests" to tests, "status" to status, "fee" to fee, "noFee" to noFee,
        "payNumber" to payNumber, "payMethods" to payMethods, "method" to method,
    ).apply {
        if (withSecret) put("trxId", trxId)
        put("rejectNote", rejectNote)
        put("comment", comment)
        put("newRxId", newRxId)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }
}

/**
 * Tables of the lab side: `labs` (read as Node-shaped maps through the kernel's ListingRows elsewhere),
 * `lab_prices` + `lab_price_items`, `lab_ranges`, `lab_orders` (+ price items, report items, photos), `rx_reviews`.
 */
@Service
class LabStore(private val jdbc: JdbcTemplate, private val files: StoredFiles) {
    // ------------------------------------------------------------------ price lists

    fun prices(labUid: String): PriceList? = pricesOf(listOf(labUid))[labUid]

    /** labPrices of several labs (id → list) */
    fun pricesOf(ids: Collection<String>): Map<String, PriceList> {
        if (ids.isEmpty()) return emptyMap()
        val arr = P3Sql.pgArray(ids.distinct())
        val items = HashMap<String, MutableList<PriceItem>>()
        jdbc.eachRow("SELECT * FROM lab_price_items WHERE lab_uid = ANY(?::text[]) ORDER BY lab_uid, idx", arr) { rs ->
            items.getOrPut(rs.getString("lab_uid")) { ArrayList() }.add(
                PriceItem(rs.getString("code"), rs.getString("name"), rs.getInt("price"), rs.getString("tat") ?: "", rs.getBoolean("enabled")),
            )
        }
        val out = LinkedHashMap<String, PriceList>()
        jdbc.eachRow("SELECT * FROM lab_prices WHERE lab_uid = ANY(?::text[])", arr) { rs ->
            val id = rs.getString("lab_uid")
            out[id] = PriceList(items[id].orEmpty(), rs.intOrNull("discount_pct"), rs.intOrNull("home_fee"), rs.getTimestamp("updated_at").time)
        }
        return out
    }

    @Transactional
    fun savePrices(labUid: String, x: PriceList?) {
        jdbc.update("DELETE FROM lab_prices WHERE lab_uid = ?", labUid)
        if (x == null) return
        jdbc.update(
            "INSERT INTO lab_prices (lab_uid, discount_pct, home_fee, updated_at) VALUES (?, ?, ?, ?)",
            labUid, x.discountPct, x.homeFee, P3Sql.ts(x.updatedAt),
        )
        x.items.forEachIndexed { i, it ->
            jdbc.update(
                "INSERT INTO lab_price_items (lab_uid, idx, code, name, price, tat, enabled) VALUES (?, ?, ?, ?, ?, ?, ?)",
                labUid, i, it.code, it.name, it.price, it.tat, it.on,
            )
        }
    }

    // ------------------------------------------------------------------ the lab's own normal ranges

    fun ranges(labUid: String): LinkedHashMap<String, Pair<Double?, Double?>> {
        val out = LinkedHashMap<String, Pair<Double?, Double?>>()
        jdbc.eachRow("SELECT test_key, low, high FROM lab_ranges WHERE lab_uid = ? ORDER BY test_key", labUid) { rs ->
            out[rs.getString("test_key")] = P3Sql.doubleOrNull(rs, "low") to P3Sql.doubleOrNull(rs, "high")
        }
        return out
    }

    @Transactional
    fun saveRanges(labUid: String, ranges: Map<String, Pair<Double?, Double?>>) {
        jdbc.update("DELETE FROM lab_ranges WHERE lab_uid = ?", labUid)
        for ((k, v) in ranges) {
            jdbc.update("INSERT INTO lab_ranges (lab_uid, test_key, low, high) VALUES (?, ?, ?, ?)", labUid, k, v.first, v.second)
        }
    }

    // ------------------------------------------------------------------ lab orders

    private fun home(rs: ResultSet): LabHome? {
        val status = rs.getString("home_status") ?: return null
        return LabHome(
            status = status,
            date = P3Sql.dayMs(rs, "home_date"),
            slot = rs.getString("home_slot"),
            note = rs.getString("home_note"),
            fee = rs.intOrNull("home_fee"),
            address = rs.getString("home_address"),
            phone = rs.getString("home_phone"),
            requestedAt = P3Sql.msOrNull(rs, "home_requested_at"),
            visitDate = P3Sql.dayMs(rs, "home_visit_date"),
            time = rs.getString("home_time"),
            at = P3Sql.msOrNull(rs, "home_at"),
            confirmNote = rs.getString("home_confirm_note"),
            confirmedAt = P3Sql.msOrNull(rs, "home_confirmed_at"),
            declineNote = rs.getString("home_decline_note"),
            declinedAt = P3Sql.msOrNull(rs, "home_declined_at"),
        )
    }

    private fun baseRow(rs: ResultSet): LabOrder {
        val estimate = rs.intOrNull("price_estimate")
        val reportedAt = P3Sql.msOrNull(rs, "reported_at")
        return LabOrder(
            id = rs.getString("id"),
            rxId = rs.getString("rx_id"),
            code = rs.getString("code"),
            labId = rs.getString("lab_uid"),
            labName = rs.getString("lab_name") ?: "",
            patientUid = rs.getString("patient_uid"),
            patientName = rs.getString("patient_name") ?: "",
            patientAge = rs.intOrNull("patient_age"),
            patientGender = rs.getString("patient_gender") ?: "",
            doctorUid = rs.getString("doctor_uid"),
            doctorName = rs.getString("doctor_name") ?: "",
            tests = rs.stringArray("tests") ?: emptyList(),
            status = rs.getString("status"),
            createdAt = rs.getTimestamp("created_at").time,
            reportedAt = reportedAt,
            home = home(rs),
            prices = estimate?.let {
                PriceSnap(
                    emptyList(), rs.stringArray("price_missing") ?: emptyList(), rs.intOrNull("price_subtotal"),
                    rs.intOrNull("price_discount_pct"), it, P3Sql.msOrNull(rs, "price_at") ?: 0L,
                )
            },
            report = reportedAt?.let { LabReport(emptyList(), rs.getString("report_note") ?: "", 0, null, it) },
        )
    }

    /** lab orders for a WHERE clause, without their child rows (cheap lists) */
    fun ordersBare(where: String, vararg args: Any?): List<LabOrder> = jdbc.rows("SELECT * FROM lab_orders WHERE $where", *args) { baseRow(it) }

    /** lab orders for a WHERE clause, with price items, report items (+ photos when [photos]) */
    fun orders(where: String, vararg args: Any?, photos: Boolean = false): List<LabOrder> = withChildren(ordersBare(where, *args), photos)

    fun order(id: String, photos: Boolean = false): LabOrder? = orders("id = ?", id, photos = photos).firstOrNull()

    /** adds the child rows to [list] (order kept) */
    fun withChildren(list: List<LabOrder>, photos: Boolean = false): List<LabOrder> {
        if (list.isEmpty()) return list
        val arr = P3Sql.pgArray(list.map { it.id })
        val priceItems = HashMap<String, MutableList<EstItem>>()
        jdbc.eachRow("SELECT * FROM lab_order_price_items WHERE order_id = ANY(?::text[]) ORDER BY order_id, idx", arr) { rs ->
            priceItems.getOrPut(rs.getString("order_id")) { ArrayList() }.add(
                EstItem(rs.getString("test"), rs.getString("name") ?: "", rs.getInt("price"), rs.getString("tat") ?: ""),
            )
        }
        val reportItems = HashMap<String, MutableList<ReportItem>>()
        jdbc.eachRow("SELECT * FROM lab_report_items WHERE order_id = ANY(?::text[]) ORDER BY order_id, idx", arr) { rs ->
            reportItems.getOrPut(rs.getString("order_id")) { ArrayList() }.add(
                ReportItem(
                    test = rs.getString("test"), result = rs.getString("result") ?: "", unit = rs.getString("unit") ?: "",
                    range = rs.getString("range_text") ?: "", flag = rs.getString("flag") ?: "", group = rs.getString("group_name"),
                    code = rs.getString("code"), key = rs.getString("param_key"), low = P3Sql.doubleOrNull(rs, "low"),
                    high = P3Sql.doubleOrNull(rs, "high"), long = rs.getBoolean("long_text"),
                ),
            )
        }
        val photoList = HashMap<String, MutableList<String>>()
        val photoCount = HashMap<String, Int>()
        if (photos) {
            // base64 in `data`, or on R2 (`storage_key`, V602) — the same text either way; unreadable → "" like a missing one
            val photoRows = ArrayList<Triple<String, String?, String?>>()
            jdbc.eachRow("SELECT order_id, data, storage_key FROM lab_report_photos WHERE order_id = ANY(?::text[]) ORDER BY order_id, idx", arr) { rs ->
                photoRows += Triple(rs.getString("order_id"), rs.getString("data"), rs.getString("storage_key"))
            }
            val texts = files.texts(photoRows.filter { it.second == null }.mapNotNull { it.third })
            for ((orderId, data, key) in photoRows) {
                photoList.getOrPut(orderId) { ArrayList() }.add(data ?: key?.let { texts[it] } ?: "")
            }
            photoList.forEach { (k, v) -> photoCount[k] = v.size }
        } else {
            jdbc.eachRow("SELECT order_id, count(*) AS n FROM lab_report_photos WHERE order_id = ANY(?::text[]) GROUP BY order_id", arr) { rs ->
                photoCount[rs.getString("order_id")] = rs.getInt("n")
            }
        }
        return list.map { o ->
            o.copy(
                prices = o.prices?.copy(items = priceItems[o.id].orEmpty()),
                report = o.report?.copy(
                    items = reportItems[o.id].orEmpty(), photoCount = photoCount[o.id] ?: 0,
                    photos = if (photos) photoList[o.id].orEmpty() else null,
                ),
            )
        }
    }

    data class NewOrder(
        val rxId: String,
        val code: String,
        val labId: String,
        val labName: String,
        val patientUid: String?,
        val patientName: String,
        val patientAge: Int?,
        val patientGender: String,
        val doctorUid: String,
        val doctorName: String,
        val tests: List<String>,
        val status: String,
        val createdAt: Long,
        val walkIn: Boolean,
        val byCaretaker: String?,
        val prices: PriceSnap?,
        val home: LabHome?,
    )

    @Transactional
    fun insertOrder(x: NewOrder): String {
        val id = Ids.newId()
        val h = x.home
        val p = x.prices
        jdbc.update(
            """
            INSERT INTO lab_orders (id, rx_id, code, lab_uid, lab_name, patient_uid, patient_name, patient_age, patient_gender, doctor_uid,
              doctor_name, tests, status, walk_in, by_caretaker, created_at, home_status, home_date, home_slot, home_note, home_fee,
              home_address, home_phone, home_requested_at, price_subtotal, price_discount_pct, price_estimate, price_missing, price_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::text[], ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::text[], ?)
            """.trimIndent(),
            id, x.rxId, x.code, x.labId, x.labName, x.patientUid, x.patientName, x.patientAge, x.patientGender, x.doctorUid,
            x.doctorName, P3Sql.pgArray(x.tests), x.status, x.walkIn, x.byCaretaker, P3Sql.ts(x.createdAt),
            h?.status, P3Sql.date(h?.date), h?.slot, h?.note, h?.fee, h?.address, h?.phone, P3Sql.ts(h?.requestedAt),
            p?.subtotal, p?.discountPct, p?.estimate, P3Sql.pgArray(p?.missing.orEmpty()), P3Sql.ts(p?.at),
        )
        p?.items?.forEachIndexed { i, it ->
            jdbc.update(
                "INSERT INTO lab_order_price_items (order_id, idx, test, name, price, tat) VALUES (?, ?, ?, ?, ?, ?)",
                id, i, it.test, it.name, it.price, it.tat,
            )
        }
        return id
    }

    /** replaces the order's home part (lab_home_change; null = removed) */
    fun setHome(id: String, h: LabHome?, now: Long) {
        jdbc.update(
            """
            UPDATE lab_orders SET home_status = ?, home_date = ?, home_slot = ?, home_note = ?, home_fee = ?, home_address = ?, home_phone = ?,
              home_requested_at = ?, home_visit_date = ?, home_time = ?, home_at = ?, home_confirm_note = ?, home_confirmed_at = ?,
              home_decline_note = ?, home_declined_at = ?, updated_at = ? WHERE id = ?
            """.trimIndent(),
            h?.status, P3Sql.date(h?.date), h?.slot, h?.note, h?.fee, h?.address, h?.phone, P3Sql.ts(h?.requestedAt), P3Sql.date(h?.visitDate),
            h?.time, P3Sql.ts(h?.at), h?.confirmNote, P3Sql.ts(h?.confirmedAt), h?.declineNote, P3Sql.ts(h?.declinedAt), P3Sql.ts(now), id,
        )
    }

    /** lab_report: the report replaces any earlier one */
    @Transactional
    fun saveReport(id: String, items: List<ReportItem>, note: String, photos: List<String>, now: Long) {
        jdbc.update("UPDATE lab_orders SET status = 'reported', report_note = ?, reported_at = ? WHERE id = ?", note, P3Sql.ts(now), id)
        jdbc.update("DELETE FROM lab_report_items WHERE order_id = ?", id)
        jdbc.update("DELETE FROM lab_report_photos WHERE order_id = ?", id)
        items.forEachIndexed { i, r ->
            jdbc.update(
                """
                INSERT INTO lab_report_items (order_id, idx, test, result, unit, range_text, flag, group_name, code, param_key, low, high, long_text)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                id, i, r.test, r.result, r.unit, r.range, r.flag, r.group, r.code, r.key, r.low, r.high, r.long,
            )
        }
        photos.forEachIndexed { i, p ->
            // R2 configured (V602): the bytes there, the key here; the old photos' keys were queued for deletion above
            val key = if (files.enabled && p.isNotEmpty()) files.put("lab-report", p) else null
            jdbc.update("INSERT INTO lab_report_photos (order_id, idx, data, storage_key) VALUES (?, ?, ?, ?)", id, i, if (key == null) p else null, key)
        }
    }

    /**
     * reportHistory: earlier results of the same patient per form cell (`code.key`), newest first, ≤ 5 each.
     * [onlyLab]: the lab itself sees only its own earlier orders.
     */
    fun reportHistory(patientUid: String?, exceptId: String, before: Long, onlyLab: String?): LinkedHashMap<String, MutableList<Map<String, Any?>>> {
        val out = LinkedHashMap<String, MutableList<Map<String, Any?>>>()
        if (patientUid == null) return out
        val list = ordersBare(
            "patient_uid = ? AND id <> ? AND status = 'reported' AND COALESCE(reported_at, to_timestamp(0)) < ? AND (?::text IS NULL OR lab_uid = ?) " +
                "ORDER BY reported_at DESC NULLS LAST, id LIMIT 200",
            patientUid, exceptId, P3Sql.ts(before), onlyLab, onlyLab,
        )
        if (list.isEmpty()) return out
        val items = HashMap<String, MutableList<ReportItem>>()
        jdbc.eachRow(
            "SELECT * FROM lab_report_items WHERE order_id = ANY(?::text[]) AND code IS NOT NULL AND param_key IS NOT NULL AND NOT long_text ORDER BY order_id, idx",
            P3Sql.pgArray(list.map { it.id }),
        ) { rs ->
            items.getOrPut(rs.getString("order_id")) { ArrayList() }.add(
                ReportItem(
                    test = rs.getString("test"), result = rs.getString("result") ?: "", unit = rs.getString("unit") ?: "", range = "",
                    flag = rs.getString("flag") ?: "", code = rs.getString("code"), key = rs.getString("param_key"),
                ),
            )
        }
        for (o in list) {
            for (it in items[o.id].orEmpty()) {
                if (it.code.isNullOrEmpty() || it.key.isNullOrEmpty()) continue
                val l = out.getOrPut("${it.code}.${it.key}") { ArrayList() }
                if (l.size < 5) l.add(linkedMapOf("result" to it.result, "unit" to it.unit, "flag" to it.flag, "at" to (o.reportedAt ?: 0L), "lab" to o.labName))
            }
        }
        return out
    }

    /**
     * stripHomes: removes the patients' home address/phone from a lab's orders (lab removed / account deleted) —
     * open orders only when [openOnly].
     */
    fun stripHomes(labUid: String, openOnly: Boolean = false): Int =
        jdbc.update(
            "UPDATE lab_orders SET home_address = NULL, home_phone = NULL WHERE lab_uid = ? AND (home_address IS NOT NULL OR home_phone IS NOT NULL)" +
                if (openOnly) " AND status IN ('sent', 'received')" else "",
            labUid,
        )

    // ------------------------------------------------------------------ report reviews

    private fun review(rs: ResultSet) = Review(
        id = rs.getString("lab_order_id"),
        rxId = rs.getString("rx_id"),
        doctorUid = rs.getString("doctor_uid"),
        doctorName = rs.getString("doctor_name") ?: "",
        patientUid = rs.getString("patient_uid"),
        patientName = rs.getString("patient_name") ?: "",
        labName = rs.getString("lab_name") ?: "",
        tests = rs.stringArray("tests") ?: emptyList(),
        status = rs.getString("status"),
        fee = rs.getString("fee") ?: "",
        noFee = rs.getBoolean("no_fee"),
        payNumber = rs.getString("pay_number") ?: "",
        payMethods = rs.stringArray("pay_methods") ?: emptyList(),
        method = rs.getString("method") ?: "",
        trxId = rs.getString("trx_id") ?: "",
        rejectNote = rs.getString("reject_note") ?: "",
        comment = rs.getString("comment") ?: "",
        newRxId = rs.getString("new_rx_id")?.takeIf { it.isNotEmpty() },
        createdAt = rs.getTimestamp("created_at").time,
        updatedAt = rs.getTimestamp("updated_at").time,
    )

    fun reviews(where: String, vararg args: Any?): List<Review> = jdbc.rows("SELECT * FROM rx_reviews WHERE $where", *args) { review(it) }

    fun review(id: String): Review? = reviews("lab_order_id = ?", id).firstOrNull()

    /** reviews of several lab orders (id → review) */
    fun reviewsOf(ids: Collection<String>): Map<String, Review> =
        if (ids.isEmpty()) emptyMap() else reviews("lab_order_id = ANY(?::text[])", P3Sql.pgArray(ids.distinct())).associateBy { it.id }

    /** lab_report: `rxReviews/{id}.set({...}, {merge: true})` — payment fields of an earlier request stay */
    fun upsertReview(o: LabOrder, status: String, fee: String, noFee: Boolean, payNumber: String, payMethods: List<String>, now: Long) {
        jdbc.update(
            """
            INSERT INTO rx_reviews (lab_order_id, rx_id, doctor_uid, doctor_name, patient_uid, patient_name, lab_name, tests, status, fee, no_fee,
              pay_number, pay_methods, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::text[], ?, ?, ?, ?, ?::text[], ?, ?)
            ON CONFLICT (lab_order_id) DO UPDATE SET rx_id = EXCLUDED.rx_id, doctor_uid = EXCLUDED.doctor_uid, doctor_name = EXCLUDED.doctor_name,
              patient_uid = EXCLUDED.patient_uid, patient_name = EXCLUDED.patient_name, lab_name = EXCLUDED.lab_name, tests = EXCLUDED.tests,
              status = EXCLUDED.status, fee = EXCLUDED.fee, no_fee = EXCLUDED.no_fee, pay_number = EXCLUDED.pay_number,
              pay_methods = EXCLUDED.pay_methods, created_at = EXCLUDED.created_at, updated_at = EXCLUDED.updated_at
            """.trimIndent(),
            o.id, o.rxId, o.doctorUid, o.doctorName, o.patientUid, o.patientName, o.labName, P3Sql.pgArray(o.tests), status, fee, noFee,
            payNumber, P3Sql.pgArray(payMethods), P3Sql.ts(now), P3Sql.ts(now),
        )
    }

    companion object {
        /** rxnet.js labView: the public card of a lab (Node-shaped listing map from ListingRows) */
        fun labView(id: String, x: Map<String, Any?>, mine: Boolean = false): LinkedHashMap<String, Any?> = linkedMapOf<String, Any?>(
            "id" to id,
            "name" to x["name"],
            "phone" to Phones.fixPhone(x["phone"] as? String),
            "address" to ((x["address"] as? String) ?: ""),
            "district" to x["district"],
            "upazilaId" to (x["upazilaId"] as? Number)?.toInt()?.takeIf { it != 0 },
            "facilityId" to (x["facilityId"] as? String)?.takeIf { it.isNotEmpty() },
            "homeCollection" to (x["homeCollection"] == true),
            "hours" to ((x["hours"] as? String) ?: ""),
        ).apply {
            if (mine) {
                put("licenseNo", x["licenseNo"])
                put("status", x["status"])
                put("statusReason", (x["statusReason"] as? String) ?: "")
            }
        }
    }
}
