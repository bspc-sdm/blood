package com.lifolo.contracts

import com.lifolo.common.ApiException
import com.lifolo.common.DhakaTime
import com.lifolo.common.Lang
import com.lifolo.common.Sql
import com.lifolo.demo.DemoWorld
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.LocalDate

/*
 * TODO(P3): baseline implementations of the P3 contracts (RxContracts.kt) over the V5 tables, written by the
 * P6-kernel step so P1/P4 can work in parallel. P3 owns them: replace (delete this file) or keep and extend.
 * Known gap: V5 stores the doctor's chamber only as a name (`doctor_chamber`); Node kept {name, address, phone}.
 */

/** TODO(P3): baseline */
@Component
class PrescriptionsBaseline(private val jdbc: JdbcTemplate) : Prescriptions {
    override fun byCode(lang: String?, code: String?, callerUid: String?): Prescription {
        val c = Prescriptions.normalizeCode(code)
        if (!Prescriptions.CODE_RE.matches(c)) {
            throw ApiException(400, Lang.tr(lang, "Check the code on the prescription", "প্রেসক্রিপশনের কোডটা দেখে লিখুন"))
        }
        val rx = load("code = ?", c)
        if (rx == null || (callerUid != null && !DemoWorld.sameWorld(callerUid, rx.doctorUid))) {
            throw ApiException(404, Lang.tr(lang, "No prescription with this code", "এই কোডের কোনো প্রেসক্রিপশন নেই"))
        }
        return rx
    }

    override fun get(id: String): Prescription? = load("id = ?", id)

    private fun load(where: String, arg: String): Prescription? {
        val rx = jdbc.query("SELECT * FROM prescriptions WHERE $where", { rs, _ -> row(rs) }, arg).firstOrNull() ?: return null
        val meds = jdbc.query(
            "SELECT * FROM prescription_medicines WHERE rx_id = ? ORDER BY idx",
            { rs, _ ->
                RxMedicine(
                    rs.getInt("idx"), rs.getString("name"), rs.getString("form") ?: "", rs.getString("strength") ?: "",
                    rs.getString("dose") ?: "", rs.getString("timing") ?: "", rs.getInt("days"), rs.getString("note") ?: "",
                )
            },
            rx.id,
        )
        val items = HashMap<Long, MutableList<Pair<Int, Int>>>()
        jdbc.query(
            """
            SELECT i.dispensing_id, i.med_idx, i.qty FROM prescription_dispensed_items i
            JOIN prescription_dispensings d ON d.id = i.dispensing_id WHERE d.rx_id = ? ORDER BY i.dispensing_id, i.med_idx
            """.trimIndent(),
            { rs, _ -> items.getOrPut(rs.getLong("dispensing_id")) { ArrayList() }.add(rs.getInt("med_idx") to rs.getInt("qty")) },
            rx.id,
        )
        val dispensed = jdbc.query(
            "SELECT id, pharmacy_uid, pharmacy_name, at FROM prescription_dispensings WHERE rx_id = ? ORDER BY at, id",
            { rs, _ -> RxDispensing(rs.getString("pharmacy_uid"), rs.getString("pharmacy_name"), rs.getTimestamp("at").time, items[rs.getLong("id")].orEmpty()) },
            rx.id,
        )
        return rx.copy(medicines = meds, dispensed = dispensed)
    }

    private fun day(rs: ResultSet, col: String): Long? = rs.getObject(col, LocalDate::class.java)?.let { DhakaTime.toUtcMidnightMs(it) }

    private fun row(rs: ResultSet): Prescription {
        val chamber = rs.getString("doctor_chamber")
        @Suppress("UNCHECKED_CAST")
        val specialties = (rs.getArray("doctor_specialties")?.array as? Array<Any?>)?.map { it.toString() } ?: emptyList()
        @Suppress("UNCHECKED_CAST")
        val tests = (rs.getArray("tests")?.array as? Array<Any?>)?.map { it.toString() } ?: emptyList()
        return Prescription(
            id = rs.getString("id"),
            code = rs.getString("code"),
            status = rs.getString("status"),
            createdAt = rs.getTimestamp("created_at").time,
            doctorUid = rs.getString("doctor_uid"),
            patientUid = rs.getString("patient_uid"),
            rxPatientId = rs.getString("rx_patient_id"),
            appointmentId = rs.getString("appointment_id"),
            chamberIndex = rs.getInt("chamber_index").takeUnless { rs.wasNull() },
            doctor = linkedMapOf(
                "name" to rs.getString("doctor_name"),
                "degrees" to rs.getString("doctor_degrees"),
                "specialties" to specialties,
                "regNo" to rs.getString("doctor_reg_no"),
                "chamber" to chamber?.let { linkedMapOf("name" to it) },
            ),
            patient = linkedMapOf(
                "name" to rs.getString("patient_name"),
                "age" to rs.getInt("patient_age").takeUnless { rs.wasNull() },
                "gender" to (rs.getString("patient_gender") ?: ""),
            ),
            complaints = rs.getString("complaints"),
            findings = linkedMapOf(
                "bp" to (rs.getString("finding_bp") ?: ""), "pulse" to (rs.getString("finding_pulse") ?: ""),
                "temp" to (rs.getString("finding_temp") ?: ""), "weight" to (rs.getString("finding_weight") ?: ""),
                "spo2" to (rs.getString("finding_spo2") ?: ""), "other" to (rs.getString("finding_other") ?: ""),
            ),
            diagnosis = rs.getString("diagnosis"),
            medicines = emptyList(),
            tests = tests,
            testPlace = rs.getString("test_place"),
            testPlaceId = rs.getString("test_place_id"),
            advice = rs.getString("advice"),
            followUpDate = day(rs, "follow_up_date"),
            followUpBookDate = day(rs, "follow_up_book_date"),
            followUpNote = rs.getString("follow_up_note"),
            refillAt = day(rs, "refill_at"),
            dispensed = emptyList(),
        )
    }

    override fun view(rx: Prescription): Map<String, Any?> = linkedMapOf(
        "id" to rx.id,
        "code" to rx.code,
        "status" to rx.status,
        "createdAt" to rx.createdAt,
        "doctorUid" to rx.doctorUid,
        "doctor" to rx.doctor,
        "patient" to rx.patient,
        "complaints" to rx.complaints,
        "findings" to rx.findings,
        "diagnosis" to rx.diagnosis,
        "medicines" to rx.medicines.map {
            linkedMapOf(
                "name" to it.name, "form" to it.form, "strength" to it.strength, "dose" to it.dose,
                "timing" to it.timing, "days" to it.days, "note" to it.note,
            )
        },
        "tests" to rx.tests,
        "testPlace" to rx.testPlace,
        "testPlaceId" to rx.testPlaceId,
        "advice" to rx.advice,
        "followUpDate" to rx.followUpDate,
        "followUpBookDate" to rx.followUpBookDate,
        "followUpNote" to rx.followUpNote,
        "chamberIndex" to rx.chamberIndex,
        "linked" to (rx.patientUid != null),
    )

    override fun verify(code: String?): Map<String, Any?>? {
        val c = (code ?: "").trim().uppercase()
        if (!Prescriptions.CODE_RE.matches(c)) return null
        val rx = load("code = ?", c) ?: return null
        @Suppress("UNCHECKED_CAST")
        val chamber = (rx.doctor["chamber"] as? Map<String, Any?>)?.get("name") as? String ?: ""
        return linkedMapOf(
            "status" to rx.status,
            "createdAt" to rx.createdAt,
            "doctor" to linkedMapOf(
                "name" to (rx.doctor["name"] ?: ""), "degrees" to (rx.doctor["degrees"] ?: ""),
                "regNo" to (rx.doctor["regNo"] ?: ""), "chamber" to chamber,
            ),
            "patient" to linkedMapOf("name" to (rx.patient["name"] ?: ""), "age" to rx.patient["age"]),
            "medicines" to rx.medicines.map {
                linkedMapOf("name" to it.name, "strength" to it.strength, "form" to it.form, "dose" to it.dose, "days" to it.days)
            },
            "tests" to rx.tests,
        )
    }

    override fun setRefillAt(rxId: String, refillAtUtcMidnightMs: Long?) {
        jdbc.update(
            "UPDATE prescriptions SET refill_at = ? WHERE id = ?",
            refillAtUtcMidnightMs?.let { Sql.date(DhakaTime.fromUtcMidnightMs(it)) }, rxId,
        )
    }
}

/** TODO(P3): baseline — `user_state.lab_home_address/_phone` */
@Component
class PatientHomeBaseline(private val jdbc: JdbcTemplate) : PatientHome {
    override fun lastHome(uid: String): Map<String, String>? =
        jdbc.query(
            "SELECT lab_home_address, lab_home_phone FROM user_state WHERE uid = ?",
            { rs, _ ->
                val a = rs.getString("lab_home_address")
                if (a.isNullOrEmpty()) null else linkedMapOf("address" to a, "phone" to (rs.getString("lab_home_phone") ?: ""))
            },
            uid,
        ).firstOrNull()
}
