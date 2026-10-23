package com.lifolo.rx

import com.lifolo.common.ApiException
import com.lifolo.common.DhakaTime
import com.lifolo.common.Ids
import com.lifolo.common.Lang
import com.lifolo.common.Sql
import com.lifolo.common.intOrNull
import com.lifolo.common.localDate
import com.lifolo.common.stringArray
import com.lifolo.contracts.Prescription
import com.lifolo.contracts.Prescriptions
import com.lifolo.contracts.RxDispensing
import com.lifolo.contracts.RxMedicine
import com.lifolo.demo.DemoWorld
import com.lifolo.practice.Practice
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet

/**
 * prescriptions/{id} on PostgreSQL (`prescriptions` + `prescription_medicines`, dispensings read from
 * `prescription_dispensings`): the [Prescriptions] contract (rxByCode, rxView, verify, refill date) and the writes
 * of prescriptions.js. The doctor snapshot is {name, degrees, specialties, regNo, chamber: {name, address, phone} | null}.
 */
@Service
class PrescriptionStore(private val jdbc: JdbcTemplate) : Prescriptions {
    override fun byCode(lang: String?, code: String?, callerUid: String?): Prescription {
        val c = Prescriptions.normalizeCode(code)
        if (!Prescriptions.CODE_RE.matches(c)) {
            throw ApiException(400, Lang.tr(lang, "Check the code on the prescription", "প্রেসক্রিপশনের কোডটা দেখে লিখুন"))
        }
        val rx = findByCode(c)
        if (rx == null || (callerUid != null && !DemoWorld.sameWorld(callerUid, rx.doctorUid))) {
            throw ApiException(404, Lang.tr(lang, "No prescription with this code", "এই কোডের কোনো প্রেসক্রিপশন নেই"))
        }
        return rx
    }

    override fun get(id: String): Prescription? = load("id = ?", id).firstOrNull()

    fun findByCode(code: String): Prescription? = load("code = ?", code).firstOrNull()

    /** prescriptions with medicines and dispensings for a WHERE clause on `prescriptions` (alias-free) */
    fun load(where: String, vararg args: Any?): List<Prescription> {
        val list = jdbc.rows("SELECT * FROM prescriptions WHERE $where", *args) { row(it) }
        if (list.isEmpty()) return list
        val ids = P3Sql.pgArray(list.map { it.id })
        val meds = HashMap<String, MutableList<RxMedicine>>()
        jdbc.eachRow("SELECT * FROM prescription_medicines WHERE rx_id = ANY(?::text[]) ORDER BY rx_id, idx", ids) { rs ->
            meds.getOrPut(rs.getString("rx_id")) { ArrayList() }.add(
                RxMedicine(
                    rs.getInt("idx"), rs.getString("name"), rs.getString("form") ?: "", rs.getString("strength") ?: "",
                    rs.getString("dose") ?: "", rs.getString("timing") ?: "", rs.getInt("days"), rs.getString("note") ?: "",
                ),
            )
        }
        val items = HashMap<Long, MutableList<Pair<Int, Int>>>()
        jdbc.eachRow(
            """
            SELECT i.dispensing_id, i.med_idx, i.qty FROM prescription_dispensed_items i
            JOIN prescription_dispensings d ON d.id = i.dispensing_id WHERE d.rx_id = ANY(?::text[]) ORDER BY i.dispensing_id, i.med_idx
            """.trimIndent(),
            ids,
        ) { rs -> items.getOrPut(rs.getLong("dispensing_id")) { ArrayList() }.add(rs.getInt("med_idx") to rs.getInt("qty")) }
        val dispensed = HashMap<String, MutableList<RxDispensing>>()
        jdbc.eachRow("SELECT id, rx_id, pharmacy_uid, pharmacy_name, at FROM prescription_dispensings WHERE rx_id = ANY(?::text[]) ORDER BY at, id", ids) { rs ->
            dispensed.getOrPut(rs.getString("rx_id")) { ArrayList() }.add(
                RxDispensing(rs.getString("pharmacy_uid"), rs.getString("pharmacy_name") ?: "", rs.getTimestamp("at").time, items[rs.getLong("id")].orEmpty()),
            )
        }
        return list.map { it.copy(medicines = meds[it.id].orEmpty(), dispensed = dispensed[it.id].orEmpty()) }
    }

    private fun day(rs: ResultSet, col: String): Long? = rs.localDate(col)?.let { DhakaTime.toUtcMidnightMs(it) }

    private fun row(rs: ResultSet): Prescription {
        val chamber = rs.getString("doctor_chamber")
        return Prescription(
            id = rs.getString("id"),
            code = rs.getString("code"),
            status = rs.getString("status"),
            createdAt = rs.getTimestamp("created_at").time,
            doctorUid = rs.getString("doctor_uid"),
            patientUid = rs.getString("patient_uid"),
            rxPatientId = rs.getString("rx_patient_id"),
            appointmentId = rs.getString("appointment_id"),
            chamberIndex = rs.intOrNull("chamber_index"),
            doctor = linkedMapOf(
                "name" to (rs.getString("doctor_name") ?: ""),
                "degrees" to (rs.getString("doctor_degrees") ?: ""),
                "specialties" to (rs.stringArray("doctor_specialties") ?: emptyList()),
                "regNo" to (rs.getString("doctor_reg_no") ?: ""),
                "chamber" to chamber?.let {
                    linkedMapOf("name" to it, "address" to (rs.getString("doctor_chamber_address") ?: ""), "phone" to (rs.getString("doctor_chamber_phone") ?: ""))
                },
            ),
            patient = linkedMapOf(
                "name" to (rs.getString("patient_name") ?: ""),
                "age" to rs.intOrNull("patient_age"),
                "gender" to (rs.getString("patient_gender") ?: ""),
            ),
            complaints = rs.getString("complaints") ?: "",
            findings = linkedMapOf(
                "bp" to (rs.getString("finding_bp") ?: ""), "pulse" to (rs.getString("finding_pulse") ?: ""),
                "temp" to (rs.getString("finding_temp") ?: ""), "weight" to (rs.getString("finding_weight") ?: ""),
                "spo2" to (rs.getString("finding_spo2") ?: ""), "other" to (rs.getString("finding_other") ?: ""),
            ),
            diagnosis = rs.getString("diagnosis") ?: "",
            medicines = emptyList(),
            tests = rs.stringArray("tests") ?: emptyList(),
            testPlace = rs.getString("test_place") ?: "",
            testPlaceId = rs.getString("test_place_id"),
            advice = rs.getString("advice") ?: "",
            followUpDate = day(rs, "follow_up_date"),
            followUpBookDate = day(rs, "follow_up_book_date"),
            followUpNote = rs.getString("follow_up_note") ?: "",
            refillAt = day(rs, "refill_at"),
            dispensed = emptyList(),
            doctorType = rs.getString("doctor_type") ?: "doctor",
            doctorSystem = rs.getString("doctor_system"),
            freeText = rs.getBoolean("free_text"),
        )
    }

    /** prescriptions.js rxView */
    override fun view(rx: Prescription): Map<String, Any?> = linkedMapOf<String, Any?>(
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
        "medicines" to rx.medicines.map { medicineMap(it) },
        "tests" to rx.tests,
        "testPlace" to rx.testPlace,
        "testPlaceId" to rx.testPlaceId?.takeIf { it.isNotEmpty() },
        "advice" to rx.advice,
        "followUpDate" to rx.followUpDate?.takeIf { it != 0L },
        "followUpBookDate" to rx.followUpBookDate?.takeIf { it != 0L },
        "followUpNote" to rx.followUpNote,
        "chamberIndex" to rx.chamberIndex,
        "linked" to (rx.patientUid != null),
        // PRACTICE.md: the writer's profession/system (the header label), free-text lines, what the form allows
        "doctorType" to rx.doctorType,
        "freeText" to rx.freeText,
        "pharmacy" to rx.pharmacyOk,
        "rxRules" to Practice.rxRules(rx.doctorType, rx.doctorSystem),
    ).apply {
        put("doctor", LinkedHashMap(rx.doctor).apply { putAll(Practice.labels(rx.doctorType, rx.doctorSystem)) })
    }

    fun medicineMap(m: RxMedicine): LinkedHashMap<String, Any?> = linkedMapOf(
        "name" to m.name, "form" to m.form, "strength" to m.strength, "dose" to m.dose, "timing" to m.timing, "days" to m.days, "note" to m.note,
    )

    /** the public /rx/{code} check: status, date, the doctor — nothing about the patient, no medicines/tests */
    override fun verify(code: String?): Map<String, Any?>? {
        val c = (code ?: "").trim().uppercase()
        if (!Prescriptions.CODE_RE.matches(c)) return null
        val rx = findByCode(c) ?: return null
        @Suppress("UNCHECKED_CAST")
        val chamber = (rx.doctor["chamber"] as? Map<String, Any?>)?.get("name") as? String ?: ""
        return linkedMapOf(
            "status" to rx.status,
            "createdAt" to rx.createdAt,
            "doctor" to linkedMapOf<String, Any?>(
                "name" to (rx.doctor["name"] ?: ""), "degrees" to (rx.doctor["degrees"] ?: ""),
                "regNo" to (rx.doctor["regNo"] ?: ""), "chamber" to chamber,
            ).apply { putAll(Practice.labels(rx.doctorType, rx.doctorSystem)) },
        )
    }

    /** rx_dispense (P4): `refillAt: refillDate(next), refillNotifiedAt: FieldValue.delete()` */
    override fun setRefillAt(rxId: String, refillAtUtcMidnightMs: Long?) {
        jdbc.update(
            "UPDATE prescriptions SET refill_at = ?, refill_notified_at = NULL WHERE id = ?",
            refillAtUtcMidnightMs?.let { Sql.date(DhakaTime.fromUtcMidnightMs(it)) }, rxId,
        )
    }

    // ------------------------------------------------------------------ writes (prescriptions.js)

    /** uniqueCode: 5 tries */
    fun uniqueCode(): String {
        repeat(5) {
            val c = RxRules.newCode()
            if (jdbc.queryForList("SELECT 1 FROM prescriptions WHERE code = ?", c).isEmpty()) return c
        }
        throw IllegalStateException("code collision")
    }

    data class NewRx(
        val code: String,
        val createdAt: Long,
        val doctorUid: String,
        val doctorName: String,
        val doctorDegrees: String,
        val doctorSpecialties: List<String>,
        val doctorRegNo: String,
        val chamberName: String?,
        val chamberAddress: String?,
        val chamberPhone: String?,
        val patient: RxRules.Patient,
        val patientUid: String?,
        val rxPatientId: String?,
        val appointmentId: String?,
        val complaints: String,
        val findings: Map<String, Any?>,
        val diagnosis: String,
        val medicines: List<RxMedicine>,
        val tests: List<String>,
        val testPlace: String,
        val testPlaceId: String?,
        val advice: String,
        val followUpDate: Long?,
        val followUpBookDate: Long?,
        val followUpNote: String,
        val chamberIndex: Int?,
        val doctorType: String = "doctor",
        val doctorSystem: String? = Practice.ALLOPATHY,
        val freeText: Boolean = false,
    )

    @Transactional
    fun insert(x: NewRx): String {
        val id = Ids.newId()
        jdbc.update(
            """
            INSERT INTO prescriptions (id, code, status, created_at, doctor_uid, doctor_name, doctor_degrees, doctor_specialties,
              doctor_reg_no, doctor_chamber, doctor_chamber_address, doctor_chamber_phone, patient_name, patient_age, patient_gender,
              patient_uid, rx_patient_id, appointment_id, complaints, finding_bp, finding_pulse, finding_temp, finding_weight,
              finding_spo2, finding_other, diagnosis, tests, test_place, test_place_id, advice, follow_up_date, follow_up_book_date,
              follow_up_note, chamber_index, doctor_type, doctor_system, free_text)
            VALUES (?, ?, 'active', ?, ?, ?, ?, ?::text[], ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::text[], ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            id, x.code, Sql.ts(x.createdAt), x.doctorUid, x.doctorName, x.doctorDegrees, P3Sql.pgArray(x.doctorSpecialties),
            x.doctorRegNo, x.chamberName, x.chamberAddress, x.chamberPhone, x.patient.name, x.patient.age, x.patient.gender,
            x.patientUid, x.rxPatientId, x.appointmentId, x.complaints,
            *listOf("bp", "pulse", "temp", "weight", "spo2", "other").map { x.findings[it]?.toString() ?: "" }.toTypedArray(),
            x.diagnosis, P3Sql.pgArray(x.tests), x.testPlace, x.testPlaceId, x.advice, P3Sql.date(x.followUpDate),
            P3Sql.date(x.followUpBookDate), x.followUpNote, x.chamberIndex, x.doctorType, x.doctorSystem, x.freeText,
        )
        x.medicines.forEachIndexed { idx, m ->
            jdbc.update(
                "INSERT INTO prescription_medicines (rx_id, idx, name, form, strength, dose, timing, days, note) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, idx, m.name, m.form, m.strength, m.dose, m.timing, m.days, m.note,
            )
        }
        return id
    }
}
