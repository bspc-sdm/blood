package com.lifolo.pharmacy

import com.lifolo.common.Lang
import com.lifolo.common.ms
import com.lifolo.common.stringArray
import com.lifolo.notify.PushService
import com.lifolo.notify.PushText
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.sql.Timestamp

/** one `rxOrders/{id}` — a prescription sent to a pharmacy (shop visit or online delivery) */
data class RxOrder(
    val id: String,
    val rxId: String,
    val code: String,
    val patientUid: String,
    val pharmacyUid: String,
    val pharmacyName: String,
    val patientName: String,
    val mode: String,
    val status: String,
    val address: String?,
    val phone: String?,
    val note: String?,
    val price: String?,
    val eta: String?,
    val deliveryFee: String?,
    val missed: List<String>,
    val createdAt: Long,
) {
    /** closeMarks needs to know whether a delivery contact is stored */
    val hasContact: Boolean get() = !address.isNullOrEmpty() || !phone.isNullOrEmpty()

    companion object {
        fun of(rs: ResultSet) = RxOrder(
            id = rs.getString("id"),
            rxId = rs.getString("rx_id"),
            code = rs.getString("code"),
            patientUid = rs.getString("patient_uid"),
            pharmacyUid = rs.getString("pharmacy_uid"),
            pharmacyName = rs.getString("pharmacy_name") ?: "",
            patientName = rs.getString("patient_name") ?: "",
            mode = rs.getString("mode"),
            status = rs.getString("status"),
            address = rs.getString("address"),
            phone = rs.getString("phone"),
            note = rs.getString("note"),
            price = rs.getString("price"),
            eta = rs.getString("eta"),
            deliveryFee = rs.getString("delivery_fee"),
            missed = rs.stringArray("missed") ?: emptyList(),
            createdAt = rs.ms("created_at") ?: 0,
        )
    }
}

/**
 * rxnet.js contact privacy of online orders: closed orders keep the delivery address/phone 7 days
 * (`purge_contact_at`), the daily job removes them; removing the pharmacy (or its owner's account) strips all at once.
 */
@Component
class RxOrderContacts(private val jdbc: JdbcTemplate) {
    private val hasContact = "(coalesce(address, '') <> '' OR coalesce(phone, '') <> '' OR purge_contact_at IS NOT NULL)"

    /** rxnet.js stripOrderContacts: every order of the shop */
    fun stripOrderContacts(pharmacyId: String): Int = jdbc.update(
        "UPDATE rx_orders SET address = NULL, phone = NULL, purge_contact_at = NULL, contact_removed_at = now() WHERE pharmacy_uid = ? AND $hasContact",
        pharmacyId,
    )

    /** rxnet.js purgeClosedContacts: closed more than 7 days ago (at most 20 pages of 300 per run, like Node) */
    fun purgeClosedContacts(now: Long): Int = jdbc.update(
        """
        UPDATE rx_orders SET address = NULL, phone = NULL, purge_contact_at = NULL, contact_removed_at = ?
        WHERE id IN (SELECT id FROM rx_orders WHERE purge_contact_at <= ? LIMIT 6000)
        """.trimIndent(),
        Timestamp(now), Timestamp(now),
    )

    /** rxnet.js closeMarks as SQL: closedAt, and the purge date when a contact is stored */
    fun closeMarks(o: RxOrder, now: Long): List<Pair<String, Any?>> =
        listOf("closed_at" to Timestamp(now)) + if (o.hasContact) listOf("purge_contact_at" to Timestamp(now + RxMath.CONTACT_KEEP_MS)) else emptyList()
}

/** rxnet.js push(): one person, title (and optional body) in their language; needs a profile */
@Component
class UserPush(private val push: PushService) {
    fun send(uid: String?, en: String, bn: String, body: String, data: Map<String, Any?>) {
        if (uid == null) return
        push.pushUsers(listOf(uid)) { _, lang -> PushText(Lang.pick(lang, en, bn), body, data) }
    }
}
