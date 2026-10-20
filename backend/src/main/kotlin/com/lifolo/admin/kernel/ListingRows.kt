package com.lifolo.admin.kernel

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.lifolo.common.SqlSet
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet

/**
 * The reviewed/verified things of the Node code: the four public listings plus professional badges (verify.js COLL).
 * [path] is the Firestore collection, used in moderation target paths (`ambulances/{id}`) and sanction kinds.
 */
enum class ListingKind(
    val kind: String,
    val table: String,
    val idColumn: String,
    /** owner uid column (pharmacies, labs, badges: the id is the owner's uid) */
    val ownerColumn: String,
    val path: String,
    val labelEn: String,
    val labelBn: String,
) {
    AMBULANCE("ambulance", "ambulances", "id", "owner_uid", "ambulances", "ambulance", "অ্যাম্বুলেন্স"),
    PHARMACY("pharmacy", "pharmacies", "uid", "uid", "pharmacies", "pharmacy", "ফার্মেসি"),
    BLOODBANK("bloodbank", "blood_banks", "id", "owner_uid", "bloodBanks", "blood bank", "ব্লাড ব্যাংক"),
    LAB("lab", "labs", "uid", "uid", "labs", "diagnostic lab", "প্যাথলজি"),
    BADGE("badge", "badges", "uid", "uid", "badges", "badge", "ব্যাজ"),
    ;

    /** listings with the full review flow (listingreview.js); badges only use verification documents */
    val isListing: Boolean get() = this != BADGE

    companion object {
        fun of(kind: String?): ListingKind? = entries.firstOrNull { it.kind == kind }

        /** by table name (ListingReview.resolve gets the table like the Node collection argument) */
        fun ofTable(table: String?): ListingKind? = entries.firstOrNull { it.table == table || it.path == table }
    }
}

/**
 * Loads a listing row as a **Node-shaped map** — the Firestore document the Node code worked on: camelCase field
 * names, times as epoch-ms, `verification {by, byEmail, at, checks}`, `docsHave` as the list of stored document
 * types, `lastApproved` as a map, `ownerUid`. Only the fields the Node listing documents had are included
 * (SQL-only columns such as `hidden_for_deletion`, `is_demo` are left out), so [ListingRules.snapshot] /
 * [ListingRules.changedFields] give the Node results.
 *
 * Also writes a listing's **data fields** back from a snapshot (rejecting a pending edit restores `lastApproved`).
 */
@Component
class ListingRows(private val jdbc: JdbcTemplate, private val mapper: ObjectMapper) {
    enum class Type { TEXT, INT, BOOL, TEXT_ARRAY, SMALLINT_ARRAY }

    /**
     * A listing information field (what the owner enters; compared on review).
     * [missing] = the value written when a restored snapshot lacks the field (Node deletes it): null → NULL,
     * [KEEP] for NOT NULL columns without a default (the old value stays).
     */
    data class Field(val node: String, val column: String, val type: Type, val missing: Any? = null)

    companion object {
        val KEEP = Any()
        private val T = Type.TEXT
        private val I = Type.INT
        private val B = Type.BOOL

        /** the Node `data` objects of ambulance_save, pharmacy_save, bloodbank parse(), lab_save */
        val DATA_FIELDS: Map<ListingKind, List<Field>> = mapOf(
            ListingKind.AMBULANCE to listOf(
                Field("name", "name", T, KEEP), Field("phone", "phone", T, KEEP), Field("vehicleNo", "vehicle_no", T, KEEP),
                Field("types", "types", Type.TEXT_ARRAY, emptyList<String>()), Field("district", "district", T, KEEP),
                Field("upazilaId", "upazila_id", I), Field("area", "area", T, ""), Field("fare", "fare", T, ""),
            ),
            ListingKind.PHARMACY to listOf(
                Field("name", "name", T, KEEP), Field("licenseNo", "license_no", T, KEEP), Field("phone", "phone", T, KEEP),
                Field("district", "district", T, KEEP), Field("upazilaId", "upazila_id", I), Field("address", "address", T, ""),
                Field("open24", "open24", B, false), Field("openAt", "open_at", T), Field("closeAt", "close_at", T),
                Field("closedDays", "closed_days", Type.SMALLINT_ARRAY, emptyList<Int>()), Field("delivery", "delivery", B, false),
                Field("deliveryNote", "delivery_note", T), Field("discount", "discount", I), Field("discountMin", "discount_min", I),
                Field("freeDeliveryOver", "free_delivery_over", I), Field("discountNote", "discount_note", T),
            ),
            ListingKind.BLOODBANK to listOf(
                Field("name", "name", T, KEEP), Field("kind", "kind", T, KEEP), Field("phone", "phone", T, KEEP),
                Field("district", "district", T, KEEP), Field("upazilaId", "upazila_id", I), Field("address", "address", T, ""),
                Field("licenseNo", "license_no", T, ""), Field("open24", "open24", B, false), Field("openAt", "open_at", T),
                Field("closeAt", "close_at", T), Field("services", "services", Type.TEXT_ARRAY, emptyList<String>()),
                Field("closedDays", "closed_days", Type.SMALLINT_ARRAY, emptyList<Int>()),
            ),
            ListingKind.LAB to listOf(
                Field("name", "name", T, KEEP), Field("facilityId", "facility_id", T), Field("district", "district", T, KEEP),
                Field("upazilaId", "upazila_id", I), Field("address", "address", T, ""), Field("licenseNo", "license_no", T, ""),
                Field("phone", "phone", T, ""), Field("hours", "hours", T, ""), Field("homeCollection", "home_collection", B, false),
            ),
            ListingKind.BADGE to listOf(
                Field("type", "type", T, KEEP), Field("regNo", "reg_no", T, KEEP), Field("workplace", "workplace", T, KEEP),
                Field("name", "name", T, ""), Field("email", "email", T, ""),
            ),
        )

        /** state fields of the Node documents (META) that callers read: column → node name, as epoch-ms when a timestamp */
        private val COMMON_META = listOf(
            "status" to "status", "status_reason" to "statusReason", "approved_name" to "approvedName",
            "docs_request_note" to "docsRequestNote", "reviewed_by" to "reviewedBy", "edit_rejected_reason" to "editRejectedReason",
        )
        private val COMMON_TIMES = listOf(
            "docs_sent_at" to "docsSentAt", "docs_requested_at" to "docsRequestedAt", "reviewed_at" to "reviewedAt",
            "edit_rejected_at" to "editRejectedAt", "created_at" to "createdAt", "updated_at" to "updatedAt", "suspended_at" to "suspendedAt",
        )
        private val EXTRA: Map<ListingKind, List<Pair<String, String>>> = mapOf(
            ListingKind.AMBULANCE to listOf(
                "lat" to "lat", "lng" to "lng", "available" to "available", "available_at" to "availableAt", "busy_at" to "busyAt",
                "remind_at" to "remindAt", "reminder_sent_at" to "reminderSentAt", "rating_sum" to "ratingSum",
                "rating_count" to "ratingCount", "overcharge_count" to "overchargeCount",
            ),
            ListingKind.PHARMACY to listOf(
                "lat" to "lat", "lng" to "lng", "closed_until" to "closedUntil", "rating_sum" to "ratingSum", "rating_count" to "ratingCount",
            ),
            ListingKind.BLOODBANK to listOf("added_by_admin" to "addedByAdmin"),
        )
    }

    /** the Node-shaped listing, or null when there is no such row */
    fun load(kind: ListingKind, id: String): Map<String, Any?>? =
        jdbc.query("SELECT * FROM ${kind.table} WHERE ${kind.idColumn} = ?", { rs, _ -> view(kind, rs) }, id).firstOrNull()

    /** builds the Node-shaped map from a `SELECT *` row of [kind]'s table */
    fun view(kind: ListingKind, rs: ResultSet): LinkedHashMap<String, Any?> {
        val md = rs.metaData
        val cols = (1..md.columnCount).map { md.getColumnName(it) }.toHashSet()
        val x = LinkedHashMap<String, Any?>()
        for (f in DATA_FIELDS.getValue(kind)) x[f.node] = read(rs, f.column, f.type)
        x["ownerUid"] = rs.getString(kind.ownerColumn)
        for ((c, n) in COMMON_META) if (c in cols) x[n] = rs.getString(c)
        for ((c, n) in COMMON_TIMES) if (c in cols) x[n] = rs.getTimestamp(c)?.time
        for ((c, n) in EXTRA[kind].orEmpty()) {
            if (c !in cols) continue
            val v = rs.getObject(c)
            x[n] = when (v) {
                is java.sql.Timestamp -> v.time
                is java.time.OffsetDateTime -> v.toInstant().toEpochMilli()
                else -> v
            }
        }
        if ("last_approved" in cols) {
            x["lastApproved"] = rs.getString("last_approved")?.let { mapper.readValue(it, object : TypeReference<LinkedHashMap<String, Any?>>() {}) }
        }
        x["docsHave"] = (rs.getArray("docs_have")?.array as? Array<*>)?.map { it.toString() } ?: emptyList<String>()
        val verifiedAt = rs.getTimestamp("verified_at")
        x["verification"] = if (verifiedAt == null) null else linkedMapOf(
            "by" to rs.getString("verified_by"),
            "byEmail" to (rs.getString("verified_by_email") ?: ""),
            "at" to verifiedAt.time,
            "checks" to ((rs.getArray("verification_checks")?.array as? Array<*>)?.map { it.toString() } ?: emptyList<String>()),
        )
        return x
    }

    private fun read(rs: ResultSet, col: String, type: Type): Any? = when (type) {
        Type.TEXT -> rs.getString(col)
        Type.INT -> rs.getInt(col).let { if (rs.wasNull()) null else it }
        Type.BOOL -> rs.getBoolean(col).let { if (rs.wasNull()) null else it }
        Type.TEXT_ARRAY -> (rs.getArray(col)?.array as? Array<*>)?.map { it.toString() } ?: emptyList<String>()
        Type.SMALLINT_ARRAY -> (rs.getArray(col)?.array as? Array<*>)?.map { (it as Number).toInt() } ?: emptyList<Int>()
    }

    /**
     * Sets every data field of [kind] from [snapshot] (a `lastApproved` map) on [set] — fields missing from the
     * snapshot get their [Field.missing] value (Node: deleted).
     */
    fun restoreData(kind: ListingKind, snapshot: Map<String, Any?>, set: SqlSet) {
        for (f in DATA_FIELDS.getValue(kind)) {
            val v = snapshot[f.node]
            if (v != null) {
                write(set, f, v)
            } else if (f.missing !== KEEP) {
                write(set, f, f.missing)
            }
        }
    }

    private fun write(set: SqlSet, f: Field, v: Any?) {
        when (f.type) {
            Type.TEXT -> set.value(f.column, v?.toString())
            Type.INT -> set.value(f.column, (v as? Number)?.toInt())
            Type.BOOL -> set.value(f.column, v as? Boolean ?: false)
            Type.TEXT_ARRAY -> set.textArray(f.column, (v as? Collection<*>)?.map { it.toString() } ?: emptyList())
            Type.SMALLINT_ARRAY -> set.intArray(f.column, (v as? Collection<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList(), "int2")
        }
    }

    fun toJson(x: Map<String, Any?>?): String? = x?.let { mapper.writeValueAsString(it) }
}
