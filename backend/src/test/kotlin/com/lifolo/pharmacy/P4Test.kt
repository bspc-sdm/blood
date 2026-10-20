package com.lifolo.pharmacy

import com.fasterxml.jackson.databind.JsonNode
import com.lifolo.common.Places
import com.lifolo.support.IntegrationTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.springframework.beans.factory.annotation.Autowired
import java.sql.Timestamp
import java.time.LocalDateTime
import java.time.ZoneOffset

/** helpers shared by the P4 tests (the Node tests' shop(), rxDoc(), ok(), clock.setDhaka()) */
abstract class P4Test : IntegrationTest() {
    @Autowired
    lateinit var clock: P4Clock

    @AfterEach
    fun resetClock() {
        clock.fixed = null
    }

    val MYMENSINGH: String = Places.DISTRICTS.first { it.en == "Mymensingh" }.bn
    val GAZIPUR: String = Places.DISTRICTS.first { it.en == "Gazipur" }.bn

    val HOUR = 3600 * 1000L
    val DAY = 24 * HOUR

    /** support/clock.js setDhaka("2026-10-20 03:00") */
    fun setDhaka(t: String): Long {
        val ms = LocalDateTime.parse(t.replace(" ", "T")).toInstant(ZoneOffset.ofHours(6)).toEpochMilli()
        clock.fixed = ms
        return ms
    }

    fun ok(uid: String?, action: String, body: Map<String, Any?> = emptyMap()): JsonNode {
        val r = call(uid, action, body)
        assertEquals(200, r.status, "$action: ${r.body}")
        return r.body
    }

    /** a user with a profile (pushes need one) */
    fun person(uid: String, name: String = "ব্যবহারকারী $uid", lang: String = "bn") = user(uid, mapOf("name" to name, "lang" to lang))

    /** pharmacies/{id} — column → value overrides (snake_case); lists go to array columns */
    fun shop(id: String, cols: Map<String, Any?> = emptyMap()) {
        person(id, "মালিক $id")
        val row = linkedMapOf<String, Any?>(
            "name" to "দোকান $id", "license_no" to "DL-1", "phone" to "01711111111", "district" to MYMENSINGH, "upazila_id" to 466,
            "address" to "চরপাড়া", "lat" to 24.75, "lng" to 90.4, "open_at" to "08:00", "close_at" to "22:00", "status" to "approved",
        )
        row.putAll(cols)
        val names = row.keys.toList()
        val marks = names.map { c ->
            when {
                c == "closed_days" -> "?::smallint[]"
                row[c] is List<*> -> "?::text[]"
                else -> "?"
            }
        }
        val args = names.map { c ->
            when (val v = row[c]) {
                is List<*> -> v.joinToString(",", "{", "}")
                else -> v
            }
        }
        jdbc.update("INSERT INTO pharmacies (uid, ${names.joinToString(", ")}) VALUES (?, ${marks.joinToString(", ")})", id, *args.toTypedArray())
    }

    data class Med(val name: String, val form: String = "Tablet", val dose: String = "", val days: Int? = null, val strength: String = "", val timing: String = "")

    /** prescriptions/{id} with its medicines (the doctor gets an account) */
    fun rx(
        id: String,
        code: String,
        patientUid: String?,
        meds: List<Med>,
        doctorUid: String = "docX",
        status: String = "active",
        patientName: String = "রোগী",
        tests: List<String> = emptyList(),
    ) {
        user(doctorUid)
        jdbc.update(
            "INSERT INTO prescriptions (id, code, doctor_uid, patient_uid, status, patient_name, tests, created_at) VALUES (?, ?, ?, ?, ?, ?, ?::text[], ?)",
            id, code, doctorUid, patientUid, status, patientName, tests.joinToString(",", "{", "}"), Timestamp(1),
        )
        meds.forEachIndexed { i, m ->
            jdbc.update(
                "INSERT INTO prescription_medicines (rx_id, idx, name, form, strength, dose, timing, days) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id, i, m.name, m.form, m.strength, m.dose, m.timing, m.days,
            )
        }
    }

    /** prescriptions.dispensed[] entry */
    fun dispensed(rxId: String, by: String, at: Long, items: List<Pair<Int, Int>>) {
        val id = jdbc.queryForObject(
            "INSERT INTO prescription_dispensings (rx_id, pharmacy_uid, pharmacy_name, at) VALUES (?, ?, 'দোকান', ?) RETURNING id",
            Long::class.java, rxId, by, Timestamp(at),
        )!!
        items.forEach { (i, q) -> jdbc.update("INSERT INTO prescription_dispensed_items (dispensing_id, med_idx, qty) VALUES (?, ?, ?)", id, i, q) }
    }

    /** rxOrders/{id} */
    fun order(id: String, cols: Map<String, Any?>) {
        val row = linkedMapOf<String, Any?>("mode" to "shop", "code" to "CODE000000", "created_at" to Timestamp(System.currentTimeMillis()))
        row.putAll(cols)
        val names = row.keys.toList()
        val args = names.map { c -> (row[c] as? List<*>)?.joinToString(",", "{", "}") ?: row[c] }
        val marks = names.map { c -> if (row[c] is List<*>) "?::text[]" else "?" }
        jdbc.update("INSERT INTO rx_orders (id, ${names.joinToString(", ")}) VALUES (?, ${marks.joinToString(", ")})", id, *args.toTypedArray())
    }

    fun ts(ms: Long) = Timestamp(ms)

    /** inbox titles of a user (every push writes the inbox) */
    fun inbox(uid: String): List<Map<String, Any?>> =
        jdbc.queryForList("SELECT title, body, data::text AS data FROM inbox_items WHERE uid = ? ORDER BY created_at, id", uid)

    fun lastPush(uid: String): Map<String, Any?>? = inbox(uid).lastOrNull()

    fun ids(r: JsonNode): List<String> = r["items"].map { it["id"].textValue() }

    fun by(r: JsonNode, id: String): JsonNode = r["items"].first { it["id"].textValue() == id }

    fun json(v: Any?): JsonNode = mapper.valueToTree(v)
}
