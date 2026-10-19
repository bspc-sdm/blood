package com.lifolo.contracts

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lifolo.common.Rules
import com.lifolo.users.DeletionService
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/*
 * TODO(P2): baseline implementations of the P2 contracts (DoctorsContracts.kt), written by the P6-kernel step so
 * P1/P3/P4/P5 can build and test in parallel. They read/write the phase-1 tables directly. P2 owns them: replace
 * them with P2's services (delete this file, or move/keep a class when it is already right) and port the Node tests.
 * Methods marked "not ported" throw — nobody calls them before P6-rest.
 */

/** TODO(P2): baseline — `badges.status = 'approved'` → type */
@Component
class BadgesBaseline(private val jdbc: JdbcTemplate) : Badges {
    override fun approvedBadge(uid: String): String? =
        jdbc.queryForList("SELECT type FROM badges WHERE uid = ? AND status = 'approved'", String::class.java, uid).firstOrNull()
}

/** TODO(P2): baseline over `provider_assistants` */
@Component
class ProviderAssistantsBaseline(private val jdbc: JdbcTemplate) : ProviderAssistants {
    override fun emails(doctorUid: String): List<String> =
        jdbc.queryForList("SELECT email FROM provider_assistants WHERE provider_uid = ? ORDER BY position, email", String::class.java, doctorUid)

    override fun isAssistant(doctorUid: String, email: String): Boolean =
        email.isNotEmpty() &&
            jdbc.queryForList("SELECT 1 FROM provider_assistants WHERE provider_uid = ? AND email = ?", doctorUid, email).isNotEmpty()

    override fun doctorsFor(email: String, limit: Int): List<String> =
        if (email.isEmpty()) {
            emptyList()
        } else {
            jdbc.queryForList(
                "SELECT provider_uid FROM provider_assistants WHERE email = ? ORDER BY provider_uid LIMIT ?", String::class.java, email, limit,
            )
        }

    @Transactional
    override fun set(doctorUid: String, emails: List<String>): Boolean {
        if (jdbc.queryForList("SELECT 1 FROM providers WHERE uid = ? FOR UPDATE", doctorUid).isEmpty()) return false
        jdbc.update("DELETE FROM provider_assistants WHERE provider_uid = ?", doctorUid)
        emails.map { it.lowercase() }.distinct().forEachIndexed { i, e ->
            jdbc.update("INSERT INTO provider_assistants (provider_uid, email, position) VALUES (?, ?, ?)", doctorUid, e, i)
        }
        return true
    }
}

/** TODO(P2): baseline over `provider_chambers` + `provider_chamber_times` (legacy single-chamber documents are not in SQL) */
@Component
class ChambersBaseline(private val jdbc: JdbcTemplate) : Chambers {
    override fun chambersOf(providerUid: String): List<Chamber> {
        val times = HashMap<Int, MutableList<ChamberTime>>()
        jdbc.query(
            "SELECT chamber_idx, days, start_time, end_time FROM provider_chamber_times WHERE provider_uid = ? ORDER BY chamber_idx, idx",
            { rs, _ ->
                times.getOrPut(rs.getInt("chamber_idx")) { ArrayList() }.add(
                    ChamberTime(intArray(rs.getArray("days")), rs.getString("start_time"), rs.getString("end_time")),
                )
            },
            providerUid,
        )
        return jdbc.query(
            "SELECT * FROM provider_chambers WHERE provider_uid = ? ORDER BY idx",
            { rs, _ ->
                val idx = rs.getInt("idx")
                // chamberTimes: times[] when present, else one session from days/start/end
                val own = times[idx].orEmpty()
                val sessions = own.ifEmpty {
                    listOf(ChamberTime(intArray(rs.getArray("days")), rs.getString("start_time") ?: "", rs.getString("end_time") ?: ""))
                }
                Chamber(
                    index = idx,
                    hospitalId = rs.getString("hospital_id"),
                    hospitalName = rs.getString("hospital_name") ?: "",
                    hospitalNameBn = rs.getString("hospital_name_bn") ?: "",
                    district = rs.getString("district") ?: "",
                    upazilaId = rs.getInt("upazila_id").takeUnless { rs.wasNull() },
                    unionId = rs.getInt("union_id").takeUnless { rs.wasNull() },
                    room = rs.getString("room") ?: "",
                    schedule = rs.getString("schedule") ?: "",
                    noFee = rs.getBoolean("no_fee"),
                    fee = rs.getString("fee") ?: "",
                    appointments = rs.getBoolean("appointments"),
                    serialPhone = rs.getString("serial_phone") ?: "",
                    times = sessions,
                )
            },
            providerUid,
        )
    }

    override fun takesAppointments(providerUid: String): Boolean =
        jdbc.queryForList("SELECT appointments FROM providers WHERE uid = ?", Boolean::class.java, providerUid).firstOrNull() ?: false

    private fun intArray(a: java.sql.Array?): List<Int> = (a?.array as? Array<*>)?.map { (it as Number).toInt() } ?: emptyList()
}

/** TODO(P2): baseline — bundled DGHS directory + `hospitals` table */
@Component
class HospitalDirectoryBaseline(private val jdbc: JdbcTemplate, private val mapper: ObjectMapper) : HospitalDirectory {
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

    private fun userRow(rs: java.sql.ResultSet): Hospital = Hospital(
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

    override fun approvedIn(district: String): List<Hospital> {
        val dghs = dir[district]?.map { toObj(district, it) } ?: emptyList()
        val user = jdbc.query(
            "SELECT * FROM hospitals WHERE district = ? AND source = 'user' AND status = 'approved'", { rs, _ -> userRow(rs) }, district,
        )
        return dghs + user
    }
}

/** TODO(P2): baseline — lift and home-care block work; suspend/cancelStale are not ported yet (appointments are P2) */
@Component
class ProviderSuspensionBaseline(private val jdbc: JdbcTemplate, private val deletion: DeletionService) : ProviderSuspension {
    override fun suspendProvider(uid: String, reason: String): Boolean = throw NotImplementedError("TODO(P2): apptcancel.suspendProvider")

    override fun liftProvider(uid: String): Boolean = deletion.liftProvider(uid)

    override fun cancelStale(providerUid: String, reasonEn: String, reasonBn: String): Int =
        throw NotImplementedError("TODO(P2): apptcancel.cancelStale")

    override fun blockHomeCare(uid: String) {
        if (!Rules.isValidId(uid)) return
        jdbc.update("UPDATE providers SET home_visit_enabled = FALSE, home_visit_blocked = TRUE WHERE uid = ?", uid)
    }
}

/** TODO(P2): not ported (questions/answers/tips are P2) */
@Component
class ContentModerationStub : ContentModeration {
    override fun removeQuestion(id: String): Unit = throw NotImplementedError("TODO(P2): remove question")

    override fun removeAnswer(questionId: String, answerId: String): Unit = throw NotImplementedError("TODO(P2): remove answer")

    override fun removeTip(id: String): Unit = throw NotImplementedError("TODO(P2): remove tip")
}
