package com.lifolo.content

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lifolo.appointments.P2Clock
import com.lifolo.appointments.P2Js
import com.lifolo.auth.AdminService
import com.lifolo.common.Ids
import com.lifolo.common.Lang
import com.lifolo.common.Places
import com.lifolo.common.QuotaService
import com.lifolo.common.Rules
import com.lifolo.common.Sql
import com.lifolo.common.action.ActionContext
import com.lifolo.common.action.ActionHandler
import com.lifolo.common.action.ActionModule
import com.lifolo.contracts.Hospital
import com.lifolo.contracts.HospitalDirectory
import com.lifolo.notify.PushService
import com.lifolo.notify.PushText
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet

/** hospital-match.js — hospital name comparison (duplicates), no database */
object HospitalMatch {
    /** words left out of the comparison */
    private val STOP = setOf(
        "hospital", "hospitals", "clinic", "clinics", "ltd", "limited", "pvt", "private", "the", "and", "of",
        "center", "centre", "bd", "bangladesh", "general", "co",
    )
    private val NON_ALNUM = Regex("[^a-z0-9]+")

    /** comparison form: lower case, signs and common words removed */
    fun normalizeName(name: String?): String =
        (name ?: "").lowercase().replace("&", " and ").replace(NON_ALNUM, " ").split(" ").filter { it.isNotEmpty() && it !in STOP }.joinToString(" ")

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }

    /** how alike two names are (0–1) */
    fun similarity(a: String?, b: String?): Double {
        val x = normalizeName(a)
        val y = normalizeName(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        val lev = 1 - levenshtein(x, y).toDouble() / maxOf(x.length, y.length)
        val tx = x.split(" ").toSet()
        val ty = y.split(" ").toSet()
        val inter = tx.count { it in ty }
        val jac = inter.toDouble() / (tx + ty).size
        return maxOf(lev, jac)
    }

    const val DUP_THRESHOLD = 0.85

    /** nearly the same name at the same place (district, and upazila when both are known) */
    fun isDuplicate(aName: String?, aDistrict: String?, aUpazila: Int?, bName: String?, bDistrict: String?, bUpazila: Int?): Boolean {
        if (aDistrict != bDistrict) return false
        if (aUpazila != null && aUpazila != 0 && bUpazila != null && bUpazila != 0 && aUpazila != bUpazila) return false
        return similarity(aName, bName) >= DUP_THRESHOLD
    }
}

/**
 * hospitals.js + hospital-dir.js: the bundled DGHS facility directory (`data/hospital-dir.json`, ~17k entries) plus
 * hospitals added from the app and approved by an admin (`hospitals`, source 'user'). Only `approved` user rows count
 * (Node: status !== 'removed'; 'hidden' is a port-only status that behaves like removed).
 */
@Component
class HospitalDirectoryStore(private val jdbc: JdbcTemplate, private val mapper: ObjectMapper) : HospitalDirectory {
    /** hospital-dir.json: {districts: {district: [[n, nameEn, nameBn, type, upazilaId, unionId], …]}} */
    private val dir: JsonNode by lazy {
        val res = javaClass.getResourceAsStream("/data/hospital-dir.json") ?: error("missing data/hospital-dir.json")
        mapper.readTree(res)["districts"]
    }

    private val byId: Map<String, Hospital> by lazy {
        val out = HashMap<String, Hospital>()
        dir.properties().forEach { (district, rows) -> rows.forEach { r -> toObj(district, r).let { out[it.id] = it } } }
        out
    }

    /** hospital-dir.js toObj (`r[4] || null`: 0 counts as missing, like JS) */
    private fun toObj(district: String, r: JsonNode): Hospital = Hospital(
        id = "dghs_${r[0].asText()}",
        nameEn = r[1].asText(),
        nameBn = r[2]?.takeIf { it.isTextual }?.textValue() ?: "",
        type = r[3].asText(),
        district = district,
        upazilaId = r[4]?.takeIf { it.isNumber && it.intValue() != 0 }?.intValue(),
        unionId = r[5]?.takeIf { it.isNumber && it.intValue() != 0 }?.intValue(),
        address = "",
        phone = "",
    )

    private fun userRow(rs: ResultSet): Hospital = Hospital(
        id = rs.getString("id"),
        nameEn = rs.getString("name_en"),
        nameBn = rs.getString("name_bn") ?: "",
        type = rs.getString("type"),
        district = rs.getString("district"),
        upazilaId = rs.getInt("upazila_id").takeUnless { rs.wasNull() || it == 0 },
        unionId = rs.getInt("union_id").takeUnless { rs.wasNull() || it == 0 },
        address = rs.getString("address") ?: "",
        phone = rs.getString("phone") ?: "",
    )

    override fun findHospital(id: String?): Hospital? {
        if (id == null) return null
        if (id.startsWith("dghs_")) return byId[id]
        if (!Rules.isValidId(id)) return null
        return jdbc.query("SELECT * FROM hospitals WHERE id = ? AND status = 'approved'", { rs, _ -> userRow(rs) }, id).firstOrNull()
    }

    /** the district's DGHS entries followed by the user-added ones */
    override fun approvedIn(district: String): List<Hospital> {
        val dghs = dir[district]?.map { toObj(district, it) } ?: emptyList()
        val user = jdbc.query(
            "SELECT * FROM hospitals WHERE district = ? AND source = 'user' AND status = 'approved' ORDER BY created_at, id",
            { rs, _ -> userRow(rs) },
            district,
        )
        return dghs + user
    }
}

/**
 * hospitals.js actions (+ emergency.js admin_hospitals): the hospital list of a district, requests to add a hospital
 * (merged with a similar pending one, or answered with the existing duplicate), and the admin review.
 */
@Component
class HospitalActions(
    private val jdbc: JdbcTemplate,
    private val directory: HospitalDirectoryStore,
    private val admins: AdminService,
    private val quotas: QuotaService,
    private val push: PushService,
    private val clock: P2Clock,
    txm: PlatformTransactionManager,
) : ActionModule {
    private val tx = TransactionTemplate(txm)

    override fun actions(): Map<String, ActionHandler> = mapOf(
        "hospitals" to ActionHandler(::hospitals),
        "hospital_request" to ActionHandler(::hospitalRequest),
        "admin_hospital_requests" to ActionHandler(::adminHospitalRequests),
        "admin_hospital_resolve" to ActionHandler(::adminHospitalResolve),
        "admin_hospitals" to ActionHandler(::adminHospitals),
    )

    companion object {
        val USER_TYPES = listOf("govt_hospital", "private_hospital", "clinic", "diagnostic", "govt_mch", "govt_specialized", "govt_district", "govt_uhc")
    }

    private fun requireAdmin(ctx: ActionContext) {
        if (!admins.isAdmin(ctx.user)) throw ctx.err(403, "Admins only", "শুধু অ্যাডমিন")
    }

    private fun list(district: String, upazilaId: Long?): List<Hospital> {
        var list = directory.approvedIn(district)
        if (upazilaId != null && upazilaId != 0L) list = list.filter { it.upazilaId == null || it.upazilaId!!.toLong() == upazilaId }
        return list.sortedWith(Comparator { a, b -> P2Js.localeCompare(a.nameEn, b.nameEn) })
    }

    /** approved hospitals of a district (optionally an upazila) */
    fun hospitals(ctx: ActionContext): Any {
        val district = ctx.body.string("district")
        if (!Places.isDistrict(district)) throw ctx.err(400, "Choose a district", "জেলা বেছে নিন")
        return mapOf("items" to list(district!!, ctx.body.int("upazilaId")).map { it.toMap() })
    }

    /** emergency.js admin_hospitals: the district's hospitals for the emergency form */
    fun adminHospitals(ctx: ActionContext): Any {
        requireAdmin(ctx)
        val district = ctx.body.string("district")
        if (!Places.isDistrict(district)) throw ctx.err(400, "Choose a district", "জেলা বেছে নিন")
        return mapOf(
            "items" to list(district!!, null).map { h ->
                linkedMapOf("id" to h.id, "name" to h.nameBn.ifEmpty { h.nameEn }, "nameEn" to h.nameEn)
            },
        )
    }

    private fun validLocation(ctx: ActionContext, district: String?, upazilaId: Long?, unionId: Long?) {
        if (!Places.isDistrict(district)) throw ctx.err(400, "Choose a district", "জেলা বেছে নিন")
        if (upazilaId != null) {
            val u = if (upazilaId in Int.MIN_VALUE..Int.MAX_VALUE) Places.UPAZILA[upazilaId.toInt()] else null
            if (u == null || u.district != district) throw ctx.err(400, "Choose a valid upazila", "সঠিক উপজেলা বেছে নিন")
        }
        if (unionId != null) {
            val n = if (unionId in Int.MIN_VALUE..Int.MAX_VALUE) Places.UNION[unionId.toInt()] else null
            if (n == null || n.upazilaId.toLong() != upazilaId) throw ctx.err(400, "Choose a valid union", "সঠিক ইউনিয়ন বেছে নিন")
        }
    }

    /** anyone: ask for a hospital to be added */
    fun hospitalRequest(ctx: ActionContext): Any {
        val uid = ctx.user.id
        val b = ctx.body
        val nameEn = Rules.cleanText(b.string("nameEn"), 120)
        if (nameEn == null || !Regex("[A-Za-z]").containsMatchIn(nameEn)) {
            throw ctx.err(400, "Enter the hospital name in English", "হাসপাতালের নাম ইংরেজিতে লিখুন")
        }
        val nameBn = b.string("nameBn")?.trim()?.take(120) ?: ""
        val type = b.string("type")
        if (type == null || type !in USER_TYPES) throw ctx.err(400, "Choose the type", "ধরন বেছে নিন")
        val upazilaId = b.int("upazilaId")
        val unionId = b.int("unionId")
        val district = b.string("district")
        validLocation(ctx, district, upazilaId, unionId)
        district!!
        val address = b.string("address")?.trim()?.take(200) ?: ""
        val phone = b.string("phone")?.filter { it in '0'..'9' || it == '+' }?.take(15) ?: ""
        val up = upazilaId?.toInt()

        // 1) approved already?
        val existing = directory.approvedIn(district).firstOrNull { h -> HospitalMatch.isDuplicate(nameEn, district, up, h.nameEn, h.district, h.upazilaId) }
        if (existing != null) return linkedMapOf("ok" to false, "duplicateOf" to existing.toMap())

        // 2) a request for the same hospital is waiting? join it
        val same = jdbc.query(
            "SELECT id, name_en, district, upazila_id FROM hospital_requests WHERE district = ? AND status = 'pending' ORDER BY created_at, id",
            { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getInt(4).let { if (rs.wasNull()) null else it }) },
            district,
        ).firstOrNull { (_, n, u) -> HospitalMatch.isDuplicate(nameEn, district, up, n, district, u) }
        if (same != null) {
            tx.executeWithoutResult {
                val added = jdbc.update("INSERT INTO hospital_request_requesters (request_id, uid) VALUES (?, ?) ON CONFLICT DO NOTHING", same.first, uid)
                if (added > 0) jdbc.update("UPDATE hospital_requests SET count = count + 1 WHERE id = ?", same.first)
            }
            return linkedMapOf("ok" to true, "merged" to true)
        }

        quotas.use(ctx.lang, uid, "hospital")
        val id = Ids.newId()
        tx.executeWithoutResult {
            jdbc.update(
                """
                INSERT INTO hospital_requests (id, name_en, name_bn, type, district, upazila_id, union_id, address, phone, norm_key, count, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 'pending', ?)
                """.trimIndent(),
                id, nameEn, nameBn, type, district, up, unionId?.toInt(), address, phone, HospitalMatch.normalizeName(nameEn), Sql.ts(clock.now()),
            )
            jdbc.update("INSERT INTO hospital_request_requesters (request_id, uid) VALUES (?, ?)", id, uid)
        }
        return mapOf("ok" to true)
    }

    private class Req(
        val id: String, val nameEn: String, val nameBn: String, val type: String, val district: String, val upazilaId: Int?,
        val unionId: Int?, val address: String, val phone: String, val normKey: String, val count: Int, val status: String,
    )

    private fun req(rs: ResultSet) = Req(
        rs.getString("id"), rs.getString("name_en"), rs.getString("name_bn") ?: "", rs.getString("type"), rs.getString("district"),
        rs.getInt("upazila_id").let { if (rs.wasNull() || it == 0) null else it },
        rs.getInt("union_id").let { if (rs.wasNull() || it == 0) null else it },
        rs.getString("address") ?: "", rs.getString("phone") ?: "", rs.getString("norm_key") ?: "", rs.getInt("count"), rs.getString("status"),
    )

    /** admin: pending requests with possible duplicates */
    fun adminHospitalRequests(ctx: ActionContext): Any {
        requireAdmin(ctx)
        val rows = jdbc.query("SELECT * FROM hospital_requests WHERE status = 'pending' ORDER BY created_at, id LIMIT 100", { rs, _ -> req(rs) })
        val byDistrict = HashMap<String, List<Hospital>>()
        return mapOf(
            "items" to rows.map { r ->
                val dups = byDistrict.getOrPut(r.district) { directory.approvedIn(r.district) }
                    .filter { h -> r.district == h.district && HospitalMatch.similarity(r.nameEn, h.nameEn) >= 0.7 }
                    .take(5)
                linkedMapOf(
                    "id" to r.id, "nameEn" to r.nameEn, "nameBn" to r.nameBn, "type" to r.type, "district" to r.district,
                    "upazilaId" to r.upazilaId, "unionId" to r.unionId, "address" to r.address, "phone" to r.phone,
                    "count" to (if (r.count != 0) r.count else 1),
                    "possibleDuplicates" to dups.map { it.toMap() },
                )
            },
        )
    }

    /** admin: approve / reject / merge with an existing hospital */
    fun adminHospitalResolve(ctx: ActionContext): Any {
        requireAdmin(ctx)
        val b = ctx.body
        val id = b.string("id")
        if (!Rules.isValidId(id)) throw ctx.err(400, "Not found", "পাওয়া যায়নি")
        val r = jdbc.query("SELECT * FROM hospital_requests WHERE id = ?", { rs, _ -> req(rs) }, id).firstOrNull()
        if (r == null || r.status != "pending") throw ctx.err(404, "Not found", "পাওয়া যায়নি")
        val requesters = jdbc.queryForList("SELECT uid FROM hospital_request_requesters WHERE request_id = ? ORDER BY uid", String::class.java, r.id)
        // tell those who asked (they may wait for this hospital to save their chamber)
        fun tell(en: String, bn: String) {
            push.pushUsers(requesters) { _, lang ->
                PushText(Lang.pick(lang, en, bn), if (r.nameBn.isNotEmpty() && lang != "en") r.nameBn else r.nameEn.ifEmpty { r.nameBn }, mapOf("type" to "hospital"))
            }
        }
        val now = clock.now()
        when (b.string("op")) {
            "approve" -> {
                val hid = Ids.newId()
                tx.executeWithoutResult {
                    jdbc.update(
                        """
                        INSERT INTO hospitals (id, name_en, name_bn, type, district, upazila_id, union_id, address, phone, norm_key, source, status, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'user', 'approved', ?)
                        """.trimIndent(),
                        hid, r.nameEn, r.nameBn, r.type, r.district, r.upazilaId, r.unionId, r.address, r.phone, r.normKey, Sql.ts(now),
                    )
                    jdbc.update("UPDATE hospital_requests SET status = 'approved', hospital_id = ?, reviewed_at = ? WHERE id = ?", hid, Sql.ts(now), r.id)
                }
                tell("The hospital you asked for is now in the list", "আপনার চাওয়া হাসপাতাল তালিকায় যোগ হয়েছে")
                return linkedMapOf("ok" to true, "hospitalId" to hid)
            }
            "merge" -> {
                val targetId = b.string("targetId")
                if (directory.findHospital(targetId) == null) throw ctx.err(400, "Choose the existing hospital", "আগের হাসপাতালটা বেছে নিন")
                jdbc.update("UPDATE hospital_requests SET status = 'merged', hospital_id = ?, reviewed_at = ? WHERE id = ?", targetId, Sql.ts(now), r.id)
                tell("The hospital you asked for was already in the list — search it again", "আপনার চাওয়া হাসপাতাল আগেই তালিকায় ছিল — আবার খুঁজে দেখুন")
                return mapOf("ok" to true)
            }
            "reject" -> {
                jdbc.update("UPDATE hospital_requests SET status = 'rejected', reviewed_at = ? WHERE id = ?", Sql.ts(now), r.id)
                tell("Hospital request not approved", "হাসপাতাল যোগের অনুরোধ অনুমোদন হয়নি")
                return mapOf("ok" to true)
            }
            else -> throw ctx.err(400, "Unknown action", "অজানা কাজ")
        }
    }

    /** account deletion: this user leaves the requests (the request may be others' too) */
    fun forgetRequester(uid: String) {
        val ids = jdbc.queryForList("SELECT request_id FROM hospital_request_requesters WHERE uid = ?", String::class.java, uid)
        for (rid in ids) {
            jdbc.update("DELETE FROM hospital_request_requesters WHERE request_id = ? AND uid = ?", rid, uid)
        }
    }
}
