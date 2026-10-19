package com.lifolo.appointments

import com.lifolo.common.Sql
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.stereotype.Component
import java.sql.ResultSet

/**
 * providers/{uid} ↔ `providers` + `provider_chambers` (+ `provider_chamber_times`) + `provider_home_times`.
 * Reads give the Node document shape ([ProviderDoc], chambers through care.js chambersOf).
 */
@Component
class ProviderStore(private val jdbc: JdbcTemplate) {
    fun load(uid: String, forUpdate: Boolean = false): ProviderDoc? {
        val docs = jdbc.query("SELECT * FROM providers WHERE uid = ?" + (if (forUpdate) " FOR UPDATE" else ""), { rs, _ -> row(rs) }, uid)
        return attach(docs).firstOrNull()
    }

    /** providers matching [where] (SQL over `providers p`), with chambers */
    fun loadWhere(where: String, vararg args: Any?): List<ProviderDoc> =
        attach(jdbc.query("SELECT p.* FROM providers p WHERE $where", { rs, _ -> row(rs) }, *args))

    fun exists(uid: String): Boolean = jdbc.queryForList("SELECT 1 FROM providers WHERE uid = ?", uid).isNotEmpty()

    private fun row(rs: ResultSet): ProviderDoc {
        val enabled = rs.getBoolean("home_visit_enabled")
        val radius = rs.getInt("home_radius_km").let { if (rs.wasNull()) null else it }
        val hvSet = rs.getBoolean("home_visit_set") || enabled || radius != null
        val legacy = rs.getBoolean("legacy_chamber").let { if (rs.wasNull()) null else it }
        return ProviderDoc(
            uid = rs.getString("uid"),
            type = rs.getString("type"),
            name = rs.getString("name") ?: "",
            degrees = rs.getString("degrees") ?: "",
            gender = rs.getString("gender") ?: "",
            specialties = texts(rs, "specialties"),
            specialty = rs.getString("specialty"),
            listed = rs.getBoolean("listed"),
            active = rs.getBoolean("active"),
            appointments = rs.getBoolean("appointments"),
            adminSuspended = rs.getBoolean("admin_suspended"),
            suspendReason = rs.getString("suspend_reason"),
            badgeRejected = rs.getBoolean("badge_rejected"),
            listedBefore = rs.getBoolean("listed_before").let { if (rs.wasNull()) null else it },
            activeBefore = rs.getBoolean("active_before").let { if (rs.wasNull()) null else it },
            homeVisit = if (!hvSet) {
                null
            } else {
                HomeVisit(
                    enabled = enabled,
                    full = radius != null || enabled,
                    radiusKm = radius ?: 5,
                    upazilaIds = ints(rs, "home_upazila_ids"),
                    services = texts(rs, "home_services"),
                    phone = rs.getString("home_phone") ?: "",
                    schedule = rs.getString("home_schedule") ?: "",
                    noFee = rs.getBoolean("home_no_fee"),
                    fee = rs.getString("home_fee") ?: "",
                )
            },
            homeVisitBlocked = rs.getBoolean("home_visit_blocked"),
            ratingSum = rs.getInt("rating_sum"),
            ratingCount = rs.getInt("rating_count"),
            chamberMode = legacy,
        )
    }

    private class ChRow(val idx: Int, val ch: Ch)

    /** chambers (care.js chambersOf) and home-visit sessions of the given documents */
    private fun attach(docs: List<ProviderDoc>): List<ProviderDoc> {
        if (docs.isEmpty()) return docs
        val chambers = HashMap<String, MutableList<ChRow>>()
        val times = HashMap<String, MutableMap<Int, MutableList<Slot>>>()
        val home = HashMap<String, MutableList<Slot>>()
        for (chunk in docs.map { it.uid }.chunked(1000)) {
            val ph = chunk.joinToString(",") { "?" }
            val args = chunk.toTypedArray()
            jdbc.query(
                "SELECT provider_uid, chamber_idx, days, start_time, end_time FROM provider_chamber_times WHERE provider_uid IN ($ph) ORDER BY provider_uid, chamber_idx, idx",
                RowCallbackHandler { rs ->
                    times.getOrPut(rs.getString(1)) { HashMap() }.getOrPut(rs.getInt(2)) { ArrayList() }
                        .add(Slot(ints(rs, "days"), rs.getString("start_time"), rs.getString("end_time")))
                },
                *args,
            )
            jdbc.query(
                "SELECT * FROM provider_chambers WHERE provider_uid IN ($ph) ORDER BY provider_uid, idx",
                RowCallbackHandler { rs ->
                    val uid = rs.getString("provider_uid")
                    val idx = rs.getInt("idx")
                    chambers.getOrPut(uid) { ArrayList() }.add(
                        ChRow(
                            idx,
                            Ch(
                                hospitalId = rs.getString("hospital_id"),
                                hospitalName = rs.getString("hospital_name") ?: "",
                                hospitalNameBn = rs.getString("hospital_name_bn") ?: "",
                                district = rs.getString("district"),
                                upazilaId = rs.getInt("upazila_id").let { if (rs.wasNull()) null else it },
                                unionId = rs.getInt("union_id").let { if (rs.wasNull()) null else it },
                                custom = rs.getBoolean("custom"),
                                address = rs.getString("address"),
                                room = rs.getString("room") ?: "",
                                days = ints(rs, "days"),
                                start = rs.getString("start_time") ?: "",
                                end = rs.getString("end_time") ?: "",
                                schedule = rs.getString("schedule") ?: "",
                                noFee = rs.getBoolean("no_fee"),
                                fee = rs.getString("fee") ?: "",
                                appointments = rs.getBoolean("appointments"),
                                serialPhone = rs.getString("serial_phone") ?: "",
                                patientMin = rs.getInt("patient_min").let { if (rs.wasNull()) null else it },
                            ),
                        ),
                    )
                },
                *args,
            )
            jdbc.query(
                "SELECT provider_uid, days, start_time, end_time FROM provider_home_times WHERE provider_uid IN ($ph) ORDER BY provider_uid, idx",
                RowCallbackHandler { rs ->
                    home.getOrPut(rs.getString(1)) { ArrayList() }.add(Slot(ints(rs, "days"), rs.getString("start_time"), rs.getString("end_time")))
                },
                *args,
            )
        }
        return docs.map { d ->
            val rows = chambers[d.uid].orEmpty()
            val list = if (d.chamberMode == true) {
                // care.js chambersOf: an old single-chamber document
                rows.firstOrNull()?.ch?.takeIf { it.hospitalName.isNotEmpty() }?.let {
                    listOf(
                        Ch(
                            hospitalId = null, hospitalName = it.hospitalName, hospitalNameBn = "", district = it.district ?: "",
                            schedule = it.schedule, fee = it.fee, appointments = d.active, legacy = true,
                        ),
                    )
                } ?: emptyList()
            } else {
                rows.map { r ->
                    val t = times[d.uid]?.get(r.idx)
                    if (t.isNullOrEmpty()) r.ch else r.ch.copy(times = t)
                }
            }
            d.copy(chambers = list, homeVisit = d.homeVisit?.copy(times = home[d.uid].orEmpty()))
        }
    }

    /** replaces `chambers[]` (provider_save) */
    fun saveChambers(uid: String, chambers: List<Ch>) {
        jdbc.update("DELETE FROM provider_chambers WHERE provider_uid = ?", uid)
        chambers.forEachIndexed { i, c ->
            jdbc.update(
                """
                INSERT INTO provider_chambers (provider_uid, idx, hospital_id, hospital_name, hospital_name_bn, district, upazila_id, union_id,
                  custom, address, room, days, start_time, end_time, schedule, no_fee, fee, appointments, serial_phone, patient_min)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::smallint[], ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                uid, i, c.hospitalId, c.hospitalName, c.hospitalNameBn, c.district, c.upazilaId, c.unionId,
                c.custom, if (c.custom) c.address else null, c.room, P2Js.pgInts(c.days ?: emptyList()),
                c.start?.takeIf { it.isNotEmpty() }, c.end?.takeIf { it.isNotEmpty() }, c.schedule, c.noFee ?: false, c.fee,
                c.appointments, c.serialPhone, c.patientMin,
            )
            c.times?.forEachIndexed { j, t ->
                jdbc.update(
                    "INSERT INTO provider_chamber_times (provider_uid, chamber_idx, idx, days, start_time, end_time) VALUES (?, ?, ?, ?::smallint[], ?, ?)",
                    uid, i, j, P2Js.pgInts(t.days), t.start, t.end,
                )
            }
        }
    }

    /** legacySave: one free-text chamber (chamber/district/schedule/fee) */
    fun saveLegacyChamber(uid: String, chamber: String, district: String, schedule: String, fee: String) {
        jdbc.update("DELETE FROM provider_chambers WHERE provider_uid = ?", uid)
        jdbc.update(
            "INSERT INTO provider_chambers (provider_uid, idx, hospital_name, district, schedule, fee) VALUES (?, 0, ?, ?, ?, ?)",
            uid, chamber, district, schedule, fee,
        )
    }

    /** the whole `homeVisit` object (provider_save) */
    fun saveHome(uid: String, hv: HomeVisit, termsAtMs: Long?) {
        jdbc.update(
            """
            UPDATE providers SET home_visit_set = TRUE, home_visit_enabled = ?, home_radius_km = ?, home_upazila_ids = ?::int[],
              home_services = ?::text[], home_phone = ?, home_schedule = ?, home_no_fee = ?, home_fee = ?,
              home_visit_terms_at = COALESCE(?, home_visit_terms_at)
            WHERE uid = ?
            """.trimIndent(),
            hv.enabled, hv.radiusKm, P2Js.pgInts(hv.upazilaIds), P2Js.pgTexts(hv.services), hv.phone, hv.schedule, hv.noFee, hv.fee,
            termsAtMs?.let { Sql.ts(it) }, uid,
        )
        jdbc.update("DELETE FROM provider_home_times WHERE provider_uid = ?", uid)
        hv.times.forEachIndexed { j, t ->
            jdbc.update(
                "INSERT INTO provider_home_times (provider_uid, idx, days, start_time, end_time) VALUES (?, ?, ?::smallint[], ?, ?)",
                uid, j, P2Js.pgInts(t.days), t.start, t.end,
            )
        }
    }

    /** providers.queueStats: key → {avg, n, at} */
    fun queueStats(uid: String): Map<String, QueueRules.Stats> {
        val out = HashMap<String, QueueRules.Stats>()
        jdbc.query(
            "SELECT queue_key, avg_minutes, n, at FROM provider_queue_stats WHERE provider_uid = ?",
            RowCallbackHandler { rs -> out[rs.getString(1)] = QueueRules.Stats(rs.getDouble(2), rs.getInt(3), rs.getTimestamp(4).time) },
            uid,
        )
        return out
    }

    /** providers.assistantEmails */
    fun assistantEmails(uid: String): List<String> =
        jdbc.queryForList("SELECT email FROM provider_assistants WHERE provider_uid = ? ORDER BY position, email", String::class.java, uid)

    companion object {
        fun ints(rs: ResultSet, col: String): List<Int> = (rs.getArray(col)?.array as? Array<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList()

        fun texts(rs: ResultSet, col: String): List<String> = (rs.getArray(col)?.array as? Array<*>)?.mapNotNull { it?.toString() } ?: emptyList()
    }
}
