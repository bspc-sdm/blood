package com.lifolo.lab

import com.lifolo.admin.kernel.ListingKind
import com.lifolo.admin.kernel.ListingRows
import com.lifolo.common.stringArray
import com.lifolo.contracts.PatientHome
import com.lifolo.rx.rows
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

/** the doctor's report-review settings (providers.reportReview) */
data class ReviewSettings(val enabled: Boolean, val noFee: Boolean, val fee: String, val payNumber: String, val payMethods: List<String>) {
    fun toMap(): LinkedHashMap<String, Any?> =
        linkedMapOf("enabled" to enabled, "noFee" to noFee, "fee" to fee, "payNumber" to payNumber, "payMethods" to payMethods)
}

/**
 * Lab reads shared by the lab and rx actions: labs as Node-shaped maps, the area list, the doctor's view of partner
 * lab orders, report-review settings, the patient's last home address (the [PatientHome] contract), and clearing the
 * guest answer cache after a price change (Node: memcache.clear('guest:')).
 */
@Service
class LabService(
    private val jdbc: JdbcTemplate,
    private val listing: ListingRows,
    private val store: LabStore,
    private val redis: StringRedisTemplate,
) : PatientHome {
    private val log = LoggerFactory.getLogger(javaClass)

    /** labs/{uid} (Node-shaped), or null */
    fun lab(uid: String): Map<String, Any?>? = listing.load(ListingKind.LAB, uid)

    /**
     * areaLabs: approved labs of a district (document-id order like Firestore, ≤ 500). Node cached this 1 min per
     * instance; reading it fresh is the same query and never stale.
     */
    fun areaLabs(district: String): List<Pair<String, Map<String, Any?>>> =
        jdbc.rows("SELECT * FROM labs WHERE district = ? AND status = 'approved' ORDER BY uid COLLATE \"C\" LIMIT 500", district) { rs ->
            rs.getString("uid") to listing.view(ListingKind.LAB, rs)
        }

    /** onePlace: the lab when approved and in this district, else empty */
    fun onePlace(id: String, district: String?): List<Pair<String, Map<String, Any?>>> {
        val x = lab(id) ?: return emptyList()
        return if (x["status"] == "approved" && x["district"] == district) listOf(id to x) else emptyList()
    }

    /**
     * labsForDoctor: lab orders of this doctor's prescriptions (newest first) — by prescription, then the order's and
     * the review's doctorUid checked again, so another doctor's data never comes.
     */
    fun labsForDoctor(doctorUid: String?, rxIds: List<String?>): List<Map<String, Any?>> {
        val ids = LabViews.historyIds(rxIds)
        if (doctorUid.isNullOrEmpty() || ids.isEmpty()) return emptyList()
        val docs = ids.flatMap { id ->
            store.ordersBare("rx_id = ? AND doctor_uid = ? ORDER BY created_at DESC, id COLLATE \"C\" LIMIT ${LabViews.LAB_PER_RX}", id, doctorUid)
        }
        if (docs.isEmpty()) return emptyList()
        val full = store.withChildren(docs)
        val reviews = store.reviewsOf(full.map { it.id })
        return full.map { o -> LabViews.doctorView(o, reviews[o.id]?.takeIf { it.doctorUid == doctorUid }) }
            .sortedByDescending { it["createdAt"] as Long }
    }

    /** rxnet.js reviewSettings(prov) */
    fun reviewSettings(doctorUid: String?): ReviewSettings {
        if (doctorUid == null) return ReviewSettings(false, false, "", "", emptyList())
        return jdbc.rows("SELECT review_enabled, review_no_fee, review_fee, review_pay_number, review_pay_methods FROM providers WHERE uid = ?", doctorUid) { rs ->
            ReviewSettings(
                rs.getBoolean("review_enabled"), rs.getBoolean("review_no_fee"), rs.getString("review_fee") ?: "",
                rs.getString("review_pay_number") ?: "", rs.stringArray("review_pay_methods") ?: emptyList(),
            )
        }.firstOrNull() ?: ReviewSettings(false, false, "", "", emptyList())
    }

    /** labprices.js rememberHome: the patient's last home address/phone (serverState.labHome) */
    fun rememberHome(uid: String, h: LabHome) {
        try {
            jdbc.update(
                """
                INSERT INTO user_state (uid, lab_home_address, lab_home_phone, lab_home_at) VALUES (?, ?, ?, now())
                ON CONFLICT (uid) DO UPDATE SET lab_home_address = EXCLUDED.lab_home_address, lab_home_phone = EXCLUDED.lab_home_phone,
                  lab_home_at = EXCLUDED.lab_home_at
                """.trimIndent(),
                uid, h.address, h.phone,
            )
        } catch (e: Exception) {
            log.warn("remember lab home failed: {}", e.message)
        }
    }

    /** labprices.js lastHome: {address, phone} or null */
    override fun lastHome(uid: String): Map<String, String>? =
        jdbc.rows("SELECT lab_home_address, lab_home_phone FROM user_state WHERE uid = ?", uid) { rs ->
            val a = rs.getString("lab_home_address")
            if (a.isNullOrEmpty()) null else linkedMapOf("address" to a, "phone" to (rs.getString("lab_home_phone") ?: ""))
        }.firstOrNull()

    /** memcache.clear('guest:') — the one-minute guest answers (lab lists/prices changed) */
    fun clearGuestCache() {
        try {
            val keys = redis.keys("guest:*")
            if (!keys.isNullOrEmpty()) redis.delete(keys)
        } catch (e: Exception) {
            log.warn("guest cache clear failed: {}", e.message)
        }
    }
}
