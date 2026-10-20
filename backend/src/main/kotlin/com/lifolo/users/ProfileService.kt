package com.lifolo.users

import com.fasterxml.jackson.databind.JsonNode
import com.lifolo.common.ApiException
import com.lifolo.common.Geo
import com.lifolo.common.Js
import com.lifolo.common.Lang
import com.lifolo.common.Rules
import com.lifolo.common.Sql
import com.lifolo.common.StoredFiles
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import jakarta.persistence.PersistenceContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Own profile read/update (server/lib/clientdata.js profile_get / profile_update — formerly direct Firestore writes
 * guarded by firestore.rules). Only the fields the app itself writes ([SELF_FIELDS]) can be changed.
 */
@Service
class ProfileService(private val jdbc: JdbcTemplate, private val files: StoredFiles) {
    @PersistenceContext
    private lateinit var em: EntityManager

    // ---------------------------------------------------------------- read

    @Transactional(readOnly = true)
    fun get(uid: String): Map<String, Any?>? {
        val p = em.find(UserProfile::class.java, uid) ?: return null
        return profileOut(p, commitments(uid))
    }

    private fun commitments(uid: String): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        jdbc.query(
            "SELECT request_id, committed_at FROM user_commitments WHERE uid = ? ORDER BY committed_at",
            RowMapper { rs, _ -> rs.getString(1) to rs.getTimestamp(2).time },
            uid,
        ).forEach { (k, v) -> out[k] = v }
        return out
    }

    /** clientdata.js profileOut — never the whole row (no tokens, family, server bookkeeping) */
    fun profileOut(x: UserProfile, commitments: Map<String, Long>): Map<String, Any?> = linkedMapOf(
        "name" to (x.name ?: ""),
        "phone" to (x.phone ?: ""),
        "bloodGroup" to (x.bloodGroup ?: ""),
        "lastDonationMillis" to x.lastDonationMs,
        "birthDateMillis" to x.birthDateMs,
        "weightKg" to x.weightKg,
        "lat" to x.lat,
        "lng" to x.lng,
        "placeName" to x.placeName,
        "locationSource" to x.locationSource,
        "notify" to x.notify,
        "maxPushPerDay" to x.maxPushPerDay,
        "notifyGroups" to x.notifyGroups?.toList(),
        "viewGroups" to x.viewGroups?.toList(),
        "commitments" to commitments,
        "photo" to files.text(x.photo, x.photoKey)?.takeIf { it.isNotEmpty() },
        "hasPhotoThumb" to (!x.photoThumb.isNullOrEmpty() || x.photoThumbKey != null),
        "maxKm" to x.maxKm,
        "fareAfterKm" to x.fareAfterKm,
        "lang" to x.lang,
    )

    // ---------------------------------------------------------------- update

    /** A validated field value: [Del] = remove the field (Firestore FieldValue.delete()) */
    sealed interface Value {
        data class Set(val v: Any?) : Value
        data object Del : Value
        data object Bad : Value
    }

    /** Validated input of profile_update */
    data class Update(val fields: Map<String, Value>, val commitAdd: String?, val commitRemove: List<String>)

    /**
     * Parse and validate `{set, commitAdd, commitRemove}` exactly like clientdata.js (403 for a field the app may not
     * write, 400 for a bad value, lat and lng only together). Returns null when nothing would change.
     */
    fun parse(body: com.lifolo.common.Body, lang: String?): Update? {
        val setNode = body["set"]
        val set = if (setNode == null || setNode.isNull) null else setNode
        if (set != null && !set.isObject) throw ApiException(400, Lang.tr(lang, "Invalid profile data", "প্রোফাইলের তথ্য ঠিক নেই"))
        val out = LinkedHashMap<String, Value>()
        set?.fields()?.forEach { (k, v) ->
            val validator = SELF_FIELDS[k]
                ?: throw ApiException(403, Lang.tr(lang, "This can't be changed from the app", "এটা অ্যাপ থেকে বদলানো যায় না"))
            val value = validator(v)
            if (value == Value.Bad) throw ApiException(400, Lang.tr(lang, "Invalid profile data", "প্রোফাইলের তথ্য ঠিক নেই"))
            out[k] = value
        }
        if (("lat" in out) != ("lng" in out)) throw ApiException(400, Lang.tr(lang, "Invalid profile data", "প্রোফাইলের তথ্য ঠিক নেই"))

        val addNode = body["commitAdd"]
        val removeNode = body["commitRemove"]
        val add: String? = if (addNode == null || addNode.isNull) null else Js.string(addNode) ?: "\u0000invalid"
        val removeOk = removeNode == null || removeNode.isNull || removeNode.isArray
        val remove: List<String?> = if (removeNode != null && removeNode.isArray) removeNode.map { Js.string(it) } else emptyList()
        if ((add != null && !Rules.isValidId(add)) || !removeOk || remove.size > 100 || !remove.all { Rules.isValidId(it) }) {
            throw ApiException(400, Lang.tr(lang, "Not found", "পাওয়া যায়নি"))
        }
        if (out.isEmpty() && add == null && remove.isEmpty()) return null
        return Update(out, add, remove.filterNotNull())
    }

    @Transactional
    fun update(uid: String, u: Update, lang: String?) {
        // serialize writes per user (the accounts row exists for every authenticated uid)
        jdbc.query("SELECT uid FROM accounts WHERE uid = ? FOR UPDATE", RowMapper { rs, _ -> rs.getString(1) }, uid)
        val existing = em.find(UserProfile::class.java, uid, LockModeType.PESSIMISTIC_WRITE)
        val p = existing ?: UserProfile(uid = uid)
        val f = u.fields

        // the last donation date only moves forward once set ("Change date" = donation_date_set for going back)
        val ld = f["lastDonationMillis"]
        if (ld != null) {
            val before = p.lastDonationMs
            val after = (ld as? Value.Set)?.v as Long?
            if (before != null && (after == null || after < before)) {
                throw ApiException(
                    403,
                    Lang.tr(lang, "Use \"Change date\" to move your last donation date back", "শেষ রক্তদানের তারিখ পেছাতে \"তারিখ বদলান\" ব্যবহার করুন"),
                )
            }
        }
        val now = Instant.now()
        for ((k, v) in f) apply(p, k, if (v is Value.Set) v.v else null)
        val hasLat = "lat" in f
        if (hasLat) {
            p.geohash = Geo.hash(p.lat, p.lng)
            p.locationUpdatedAt = now
        }
        if (TOUCH_UPDATED.any { it in f }) p.updatedAt = now
        // donor search key "group|geohash" follows group and location changes
        if ("bloodGroup" in f || hasLat) {
            p.donorGeo = Geo.donorKey(p.bloodGroup, p.geohash ?: Geo.hash(p.lat, p.lng))
        }
        if (existing == null) em.persist(p)
        em.flush()

        // processing list (users.commitments)
        u.commitAdd?.let {
            jdbc.update(
                "INSERT INTO user_commitments (uid, request_id, committed_at) VALUES (?, ?, ?) " +
                    "ON CONFLICT (uid, request_id) DO UPDATE SET committed_at = EXCLUDED.committed_at",
                uid, it, Sql.ts(System.currentTimeMillis()),
            )
        }
        for (id in u.commitRemove.filter { it != u.commitAdd }) {
            jdbc.update("DELETE FROM user_commitments WHERE uid = ? AND request_id = ?", uid, id)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun apply(p: UserProfile, k: String, v: Any?) {
        when (k) {
            "name" -> p.name = v as String?
            "phone" -> p.phone = v as String?
            "bloodGroup" -> p.bloodGroup = v as String?
            "birthDateMillis" -> p.birthDateMs = v as Long?
            "weightKg" -> p.weightKg = v as Double?
            "lastDonationMillis" -> p.lastDonationMs = v as Long?
            "notify" -> p.notify = v as Boolean
            "maxPushPerDay" -> p.maxPushPerDay = (v as Long?)?.toInt()
            "notifyGroups" -> p.notifyGroups = (v as List<String>?)?.toTypedArray()
            "viewGroups" -> p.viewGroups = (v as List<String>?)?.toTypedArray()
            "maxKm" -> p.maxKm = (v as Long?)?.toInt()
            "fareAfterKm" -> p.fareAfterKm = (v as Long?)?.toInt()
            "lang" -> p.lang = v as String?
            "photo" -> setPhoto(v as String?, "profile", { p.photo = it }, { p.photoKey = it })
            "photoThumb" -> setPhoto(v as String?, "profile-thumb", { p.photoThumb = it }, { p.photoThumbKey = it })
            "lat" -> p.lat = v as Double?
            "lng" -> p.lng = v as Double?
            "placeName" -> p.placeName = v as String?
            "locationSource" -> p.locationSource = v as String?
        }
    }

    /**
     * R2 configured → the bytes go to R2 and the row keeps the key (the base64 column is cleared); otherwise the
     * base64 text goes into the column like before. Either way the other column is cleared (the trigger queues a
     * replaced key for deletion).
     */
    private fun setPhoto(v: String?, area: String, setData: (String?) -> Unit, setKey: (String?) -> Unit) {
        if (v != null && v.isNotEmpty() && files.enabled) {
            setKey(files.put(area, v))
            setData(null)
        } else {
            setData(v)
            setKey(null)
        }
    }

    companion object {
        private const val MAX_MILLIS = 4102444800000L // year 2100
        private val B64 = Regex("^[A-Za-z0-9+/=]+$")

        /** fields that change "updatedAt" (the daily geohash maintenance looks at it) */
        private val TOUCH_UPDATED = listOf("name", "phone", "bloodGroup", "birthDateMillis", "weightKg")

        private fun strMax(max: Int): (JsonNode) -> Value = { v ->
            if (v.isTextual && v.textValue().length <= max) Value.Set(v.textValue()) else Value.Bad
        }

        private fun intIn(lo: Long, hi: Long): (JsonNode) -> Value = { v ->
            val n = Js.integer(v)
            if (Js.isInteger(v) && n != null && v.asDouble() >= lo && v.asDouble() <= hi) Value.Set(n) else Value.Bad
        }

        private fun nullOr(f: (JsonNode) -> Value): (JsonNode) -> Value = { v -> if (v.isNull) Value.Set(null) else f(v) }

        private fun deleteOr(f: (JsonNode) -> Value): (JsonNode) -> Value = { v -> if (v.isNull) Value.Del else f(v) }

        private val groupsOrNull: (JsonNode) -> Value = { v ->
            when {
                v.isNull -> Value.Set(null)
                v.isArray && v.size() <= 8 && v.all { it.isTextual && it.textValue() in Rules.BLOOD_GROUPS } ->
                    Value.Set(v.map { it.textValue() }.distinct())
                else -> Value.Bad
            }
        }

        private fun latLng(limit: Double): (JsonNode) -> Value = { v ->
            val d = Js.number(v)
            if (d != null && d >= -limit && d <= limit) Value.Set(Js.round(d * 100) / 100) else Value.Bad
        }

        private fun b64Max(max: Int): (JsonNode) -> Value = { v ->
            if (v.isTextual && v.textValue().length <= max && B64.matches(v.textValue())) Value.Set(v.textValue()) else Value.Bad
        }

        /** clientdata.js SELF_FIELDS: the only profile fields the app may write */
        val SELF_FIELDS: Map<String, (JsonNode) -> Value> = mapOf(
            "name" to strMax(100),
            "phone" to strMax(30),
            "bloodGroup" to { v: JsonNode -> if (v.isTextual && (v.textValue() == "" || v.textValue() in Rules.BLOOD_GROUPS)) Value.Set(v.textValue()) else Value.Bad },
            "birthDateMillis" to nullOr(intIn(-MAX_MILLIS, MAX_MILLIS)),
            "weightKg" to nullOr { v -> Js.number(v)?.takeIf { it in 0.0..500.0 }?.let { Value.Set(it) } ?: Value.Bad },
            "lastDonationMillis" to nullOr(intIn(0, MAX_MILLIS)),
            "notify" to { v: JsonNode -> if (v.isBoolean) Value.Set(v.booleanValue()) else Value.Bad },
            "maxPushPerDay" to { v: JsonNode ->
                Js.integer(v)?.let { Value.Set(it.coerceIn(1L, Rules.MAX_PUSH_PER_DAY_LIMIT.toLong())) } ?: Value.Bad
            },
            "notifyGroups" to groupsOrNull,
            "viewGroups" to groupsOrNull,
            "maxKm" to { v: JsonNode -> Js.number(v)?.takeIf { d -> Rules.RADIUS_CHOICES.any { it.toDouble() == d } }?.let { Value.Set(it.toLong()) } ?: Value.Bad },
            "fareAfterKm" to deleteOr(intIn(0, 1000)),
            "lang" to { v: JsonNode -> if (v.isTextual && (v.textValue() == "en" || v.textValue() == "bn")) Value.Set(v.textValue()) else Value.Bad },
            "photo" to deleteOr(b64Max(600 * 1024)),
            "photoThumb" to deleteOr(b64Max(100 * 1024)),
            "lat" to latLng(90.0),
            "lng" to latLng(180.0),
            "placeName" to strMax(120),
            "locationSource" to strMax(20),
        )
    }
}
