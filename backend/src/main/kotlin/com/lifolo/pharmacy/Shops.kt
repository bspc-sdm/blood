package com.lifolo.pharmacy

import com.lifolo.admin.kernel.ListingRules
import com.lifolo.common.Js
import com.lifolo.common.Phones
import com.lifolo.common.boolOrNull
import com.lifolo.common.doubleOrNull
import com.lifolo.common.intOrNull
import com.lifolo.common.ms
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet

/** pharmacy.js ASPECTS */
val ASPECTS = listOf("behaviour", "price", "delivery")

/** one `pharmacies/{uid}` row with the fields the Node code reads */
data class Shop(
    val id: String,
    val name: String,
    val licenseNo: String,
    val phone: String,
    val district: String,
    val upazilaId: Int?,
    val address: String,
    val lat: Double?,
    val lng: Double?,
    val open24: Boolean,
    val openAt: String,
    val closeAt: String,
    val closedDays: List<Int>,
    val closedUntil: Long?,
    val discount: Int?,
    val discountMin: Int?,
    val freeDeliveryOver: Int?,
    val discountNote: String,
    val delivery: Boolean,
    val deliveryNote: String,
    val shopCode: String?,
    val onlineEnabled: Boolean,
    val onlineRadiusKm: Int?,
    val onlineFee: String,
    val onlineNote: String,
    val findOff: Boolean,
    val findDay: java.time.LocalDate?,
    val findCount: Int,
    val ratingSum: Int,
    val ratingCount: Int,
    /** aspect → (sum, count) */
    val aspects: Map<String, Pair<Int, Int>>,
    val status: String,
    val statusReason: String,
    val editRejectedReason: String,
    val docsRequestedAt: Long?,
    val docsRequestNote: String,
    val createdAt: Long?,
    val reviewedAt: Long?,
    val suspendedAt: Long?,
) {
    /** the map care.js pharmacyOpenNow reads */
    fun hours(): Map<String, Any?> = mapOf(
        "closedUntil" to closedUntil, "closedDays" to closedDays, "open24" to open24, "openAt" to openAt, "closeAt" to closeAt,
    )

    fun openNow(now: Long): Boolean = ListingRules.openNow(hours(), now)

    /** rxnet.js onlineOf */
    fun online(): Map<String, Any?> = linkedMapOf(
        "enabled" to onlineEnabled, "radiusKm" to (onlineRadiusKm ?: 3), "fee" to onlineFee, "note" to onlineNote,
    )

    val radiusKm: Int get() = onlineRadiusKm ?: 3

    companion object {
        fun of(rs: ResultSet): Shop = Shop(
            id = rs.getString("uid"),
            name = rs.getString("name"),
            licenseNo = rs.getString("license_no"),
            phone = rs.getString("phone"),
            district = rs.getString("district"),
            upazilaId = rs.intOrNull("upazila_id"),
            address = rs.getString("address") ?: "",
            lat = rs.doubleOrNull("lat"),
            lng = rs.doubleOrNull("lng"),
            open24 = rs.getBoolean("open24"),
            openAt = rs.getString("open_at") ?: "",
            closeAt = rs.getString("close_at") ?: "",
            closedDays = (rs.getArray("closed_days")?.array as? Array<*>)?.map { (it as Number).toInt() } ?: emptyList(),
            closedUntil = rs.ms("closed_until"),
            discount = rs.intOrNull("discount"),
            discountMin = rs.intOrNull("discount_min"),
            freeDeliveryOver = rs.intOrNull("free_delivery_over"),
            discountNote = rs.getString("discount_note") ?: "",
            delivery = rs.boolOrNull("delivery") == true,
            deliveryNote = rs.getString("delivery_note") ?: "",
            shopCode = rs.getString("shop_code"),
            onlineEnabled = rs.getBoolean("online_enabled"),
            onlineRadiusKm = rs.intOrNull("online_radius_km"),
            onlineFee = rs.getString("online_fee") ?: "",
            onlineNote = rs.getString("online_note") ?: "",
            findOff = rs.getBoolean("find_off"),
            findDay = rs.getObject("find_day", java.time.LocalDate::class.java),
            findCount = rs.getInt("find_count"),
            ratingSum = rs.getInt("rating_sum"),
            ratingCount = rs.getInt("rating_count"),
            aspects = linkedMapOf(
                "behaviour" to (rs.getInt("aspect_behaviour_sum") to rs.getInt("aspect_behaviour_count")),
                "price" to (rs.getInt("aspect_price_sum") to rs.getInt("aspect_price_count")),
                "delivery" to (rs.getInt("aspect_delivery_sum") to rs.getInt("aspect_delivery_count")),
            ),
            status = rs.getString("status"),
            statusReason = rs.getString("status_reason") ?: "",
            editRejectedReason = rs.getString("edit_rejected_reason") ?: "",
            docsRequestedAt = rs.ms("docs_requested_at"),
            docsRequestNote = rs.getString("docs_request_note") ?: "",
            createdAt = rs.ms("created_at"),
            reviewedAt = rs.ms("reviewed_at"),
            suspendedAt = rs.ms("suspended_at"),
        )

        /** `Math.round(x * 10) / 10` */
        fun round1(x: Double): Double = Js.round(x * 10) / 10

        /** pharmacy.js aspectView: averages of the aspects that have at least one rating */
        fun aspectView(aspects: Map<String, Pair<Int, Int>>?): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            for (k in ASPECTS) {
                val (sum, n) = aspects?.get(k) ?: continue
                if (n > 0) out[k] = linkedMapOf("rating" to round1(sum.toDouble() / n), "count" to n)
            }
            return out
        }
    }

    /** pharmacy.js pharmacyView */
    fun view(mine: Boolean = false, now: Long = System.currentTimeMillis()): LinkedHashMap<String, Any?> {
        val n = ratingCount
        val until = if ((closedUntil ?: 0) > now) closedUntil!! else 0L
        val v = linkedMapOf<String, Any?>(
            "id" to id,
            "name" to name,
            "phone" to Phones.fixPhone(phone),
            "address" to address,
            "district" to district,
            "upazilaId" to upazilaId?.takeIf { it != 0 },
            "openAt" to openAt,
            "closeAt" to closeAt,
            "open24" to open24,
            "openNow" to (until == 0L && openNow(now)),
            "closedUntil" to until,
            "closedDays" to closedDays,
            "delivery" to delivery,
            "deliveryNote" to deliveryNote,
            "discount" to (discount ?: 0),
            "discountMin" to (discountMin ?: 0),
            "freeDeliveryOver" to (freeDeliveryOver ?: 0),
            "discountNote" to discountNote,
            "online" to onlineEnabled,
            "deliveryFee" to onlineFee,
            "rating" to (if (n != 0) round1(ratingSum.toDouble() / n) else 0),
            "ratingCount" to n,
            "aspects" to aspectView(aspects),
        )
        if (mine) {
            v["licenseNo"] = licenseNo
            v["status"] = status
            v["statusReason"] = statusReason
            v["hasLocation"] = lat != null
        }
        return v
    }
}

/** pharmacies table reads shared by the pharmacy actions, the medicine finder and the rx order actions */
@Component
class ShopRepo(private val jdbc: JdbcTemplate) {
    fun get(uid: String?, forUpdate: Boolean = false): Shop? {
        if (uid == null) return null
        return jdbc.query("SELECT * FROM pharmacies WHERE uid = ?" + if (forUpdate) " FOR UPDATE" else "", { rs, _ -> Shop.of(rs) }, uid).firstOrNull()
    }

    /**
     * pharmacy.js areaPharmacies: approved pharmacies of the district — when an upazila is given and the district has
     * more than [limit], that upazila's shops first (so near shops are not cut off), then the rest of the district.
     * Firestore returned them in document-id order; kept (byte order of the uid).
     */
    fun area(district: String, upazilaId: Int?, limit: Int = 500): List<Shop> {
        val all = jdbc.query(
            "SELECT * FROM pharmacies WHERE district = ? AND status = 'approved' ORDER BY uid COLLATE \"C\" LIMIT ?",
            { rs, _ -> Shop.of(rs) }, district, limit,
        )
        if (upazilaId == null || all.size < limit) return all
        val local = jdbc.query(
            "SELECT * FROM pharmacies WHERE district = ? AND status = 'approved' AND upazila_id = ? ORDER BY uid COLLATE \"C\" LIMIT ?",
            { rs, _ -> Shop.of(rs) }, district, upazilaId, limit,
        )
        val seen = HashSet<String>()
        return (local + all).filter { seen.add(it.id) }
    }

    fun byIds(ids: Collection<String>): Map<String, Shop> {
        if (ids.isEmpty()) return emptyMap()
        val list = ids.distinct()
        return jdbc.query(
            "SELECT * FROM pharmacies WHERE uid IN (${list.joinToString(",") { "?" }})",
            { rs, _ -> Shop.of(rs) }, *list.toTypedArray(),
        ).associateBy { it.id }
    }
}

/** `Math.round(distanceKm * 10) / 10` */
fun km1(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double =
    Shop.round1(com.lifolo.common.Geo.distanceKm(aLat, aLng, bLat, bLng))
