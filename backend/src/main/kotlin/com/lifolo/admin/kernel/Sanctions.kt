package com.lifolo.admin.kernel

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.lifolo.auth.IdentityProvider
import com.lifolo.common.ApiException
import com.lifolo.common.Ids
import com.lifolo.common.Lang
import com.lifolo.common.Phones
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

/**
 * sanctions.js — bans that survive deleting the listing or the account, so a suspended ambulance / pharmacy /
 * blood bank cannot come back by deleting it or by opening a new account with the same email.
 * Rows are keyed by `sha256("{kind}:{value}")[0:40]` (no plain numbers or emails stored), exactly like Node.
 *
 * Listing identities ([listingKeys]): ambulance → vehicle number (`normId`, ≥ 4 chars); pharmacy / blood bank /
 * others → licence number (`normId`, ≥ 3 chars) + phone (mobile in 8801… form, else landline/hotline as dialled).
 *
 * The `listing` map is Node-shaped (see [ListingRows]): `vehicleNo`, `licenseNo`, `phone`, `ownerUid`.
 */
interface Sanctions {
    /**
     * checkListing: before adding (isNew) or editing a listing — was this vehicle/licence/phone (and, when new, the
     * owner's email) banned? → 403 "This was suspended by the Lifolo team earlier. You can appeal from
     * Profile → Help & appeals."
     */
    fun checkListing(lang: String?, kind: String, listing: Map<String, Any?>, ownerEmail: String?, isNew: Boolean)

    /** ban: on suspension — the listing identities + owner email get `listing = true` (+ [info]: kind, reason, …) */
    fun ban(kind: String, listing: Map<String, Any?>, ownerEmail: String?, info: Map<String, Any?>)

    /** unban: appeal granted — removes the listing identities (+ email when given) */
    fun unban(kind: String, listing: Map<String, Any?>, ownerEmail: String?)

    /** keepForEmail: at account deletion, a home-care block survives under the email key (no-op when false) */
    fun keepForEmail(email: String?, homeCare: Boolean)

    /** forEmail: earlier sanctions of this email (new account): {listing, homeCare, kind, reason, at, …info}; {} if none */
    fun forEmail(email: String?): Map<String, Any?>

    /**
     * onStatusChange: admin changed a listing status. → suspended: ban (listing ids + owner's Auth email).
     * suspended → anything else: unban the listing ids; the email ban is lifted (`listing = false`) only when the
     * owner has no other suspended ambulance/pharmacy/blood bank. Never throws (Node logs).
     */
    fun onStatusChange(kind: String, listing: Map<String, Any?>, prev: String?, next: String)

    companion object {
        /** sanctions.js key */
        fun key(kind: String, value: String): String = Ids.sha256Hex("$kind:$value").take(40)

        /** sanctions.js emailKey (trimmed, lower case); null for empty */
        fun emailKey(email: String?): String? {
            val e = (email ?: "").trim().lowercase()
            return if (e.isEmpty()) null else key("email", e)
        }

        /** sanctions.js listingKeys */
        fun listingKeys(kind: String, x: Map<String, Any?>): List<String> {
            val keys = ArrayList<String>()
            if (kind == "ambulance") {
                val veh = Phones.normId(x["vehicleNo"] as? String)
                if (veh.length >= 4) keys.add(key("veh", veh))
                return keys
            }
            val lic = Phones.normId(x["licenseNo"] as? String)
            if (lic.length >= 3) keys.add(key("lic", lic))
            val phone = Phones.normalizeBd(x["phone"] as? String) ?: Phones.contactPhone(x["phone"] as? String)
            if (phone != null) keys.add(key("phone_$kind", phone))
            return keys
        }
    }
}

@Service
class SanctionsService(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
    private val identity: IdentityProvider,
) : Sanctions {
    private val log = LoggerFactory.getLogger(javaClass)

    private fun keysOf(kind: String, x: Map<String, Any?>, email: String?): List<String> =
        (Sanctions.listingKeys(kind, x) + listOfNotNull(Sanctions.emailKey(email))).distinct()

    override fun checkListing(lang: String?, kind: String, listing: Map<String, Any?>, ownerEmail: String?, isNew: Boolean) {
        val keys = keysOf(kind, listing, if (isNew) ownerEmail else null)
        if (keys.isEmpty()) return
        val hit = jdbc.queryForList(
            "SELECT 1 FROM sanctions WHERE listing AND key IN (${keys.joinToString(",") { "?" }}) LIMIT 1", *keys.toTypedArray(),
        ).isNotEmpty()
        if (hit) {
            throw ApiException(
                403,
                Lang.tr(
                    lang,
                    "This was suspended by the Lifolo team earlier. You can appeal from Profile → Help & appeals.",
                    "এটা আগে Lifolo টিম বন্ধ করেছিল। প্রোফাইলের \"সাহায্য ও আপিল\" থেকে আপিল করতে পারেন।",
                ),
            )
        }
    }

    override fun ban(kind: String, listing: Map<String, Any?>, ownerEmail: String?, info: Map<String, Any?>) {
        val keys = keysOf(kind, listing, ownerEmail)
        val extra = info.filterKeys { it != "kind" && it != "reason" && it != "listing" && it != "homeCare" && it != "at" }
        for (k in keys) {
            // set({listing: true, ...info, at}, {merge: true})
            jdbc.update(
                """
                INSERT INTO sanctions (key, listing, kind, reason, info, at) VALUES (?, TRUE, ?, ?, ?::jsonb, now())
                ON CONFLICT (key) DO UPDATE SET listing = TRUE,
                  kind = COALESCE(EXCLUDED.kind, sanctions.kind), reason = COALESCE(EXCLUDED.reason, sanctions.reason),
                  info = sanctions.info || EXCLUDED.info, at = now()
                """.trimIndent(),
                k, info["kind"]?.toString(), info["reason"]?.toString(), mapper.writeValueAsString(extra),
            )
        }
    }

    override fun unban(kind: String, listing: Map<String, Any?>, ownerEmail: String?) {
        val keys = keysOf(kind, listing, ownerEmail)
        if (keys.isEmpty()) return
        jdbc.update("DELETE FROM sanctions WHERE key IN (${keys.joinToString(",") { "?" }})", *keys.toTypedArray())
    }

    override fun keepForEmail(email: String?, homeCare: Boolean) {
        val k = Sanctions.emailKey(email) ?: return
        if (!homeCare) return
        jdbc.update(
            "INSERT INTO sanctions (key, home_care, at) VALUES (?, TRUE, now()) ON CONFLICT (key) DO UPDATE SET home_care = TRUE, at = now()", k,
        )
    }

    override fun forEmail(email: String?): Map<String, Any?> {
        val k = Sanctions.emailKey(email) ?: return emptyMap()
        return jdbc.query(
            "SELECT listing, home_care, kind, reason, info, at FROM sanctions WHERE key = ?",
            { rs, _ ->
                val out = LinkedHashMap<String, Any?>()
                rs.getString("info")?.let { out.putAll(mapper.readValue(it, object : TypeReference<Map<String, Any?>>() {})) }
                out["listing"] = rs.getBoolean("listing")
                out["homeCare"] = rs.getBoolean("home_care")
                rs.getString("kind")?.let { out["kind"] = it }
                rs.getString("reason")?.let { out["reason"] = it }
                out["at"] = rs.getTimestamp("at").time
                out
            },
            k,
        ).firstOrNull() ?: emptyMap()
    }

    override fun onStatusChange(kind: String, listing: Map<String, Any?>, prev: String?, next: String) {
        try {
            if (next != "suspended" && prev != "suspended") return
            val owner = listing["ownerUid"] as? String
            val email = owner?.let { runCatching { identity.getUser(it).email }.getOrNull() }
            if (next == "suspended") {
                ban(kind, listing, email, mapOf("kind" to kind))
            } else {
                unban(kind, listing, null)
                if (email != null && owner != null) {
                    val others = jdbc.queryForObject(
                        """
                        SELECT (SELECT count(*) FROM ambulances WHERE owner_uid = ? AND status = 'suspended')
                             + (SELECT count(*) FROM pharmacies WHERE uid = ? AND status = 'suspended')
                             + (SELECT count(*) FROM blood_banks WHERE owner_uid = ? AND status = 'suspended')
                        """.trimIndent(),
                        Int::class.java, owner, owner, owner,
                    ) ?: 0
                    if (others == 0) {
                        val k = Sanctions.emailKey(email)
                        if (k != null) {
                            jdbc.update(
                                "INSERT INTO sanctions (key, listing) VALUES (?, FALSE) ON CONFLICT (key) DO UPDATE SET listing = FALSE", k,
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            log.error("sanction update failed", e)
        }
    }
}
