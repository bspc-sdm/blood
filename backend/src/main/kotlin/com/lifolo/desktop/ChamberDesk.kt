package com.lifolo.desktop

import com.fasterxml.jackson.databind.JsonNode
import com.lifolo.appointments.Appt
import com.lifolo.appointments.ProviderStore
import com.lifolo.appointments.SerialCounters
import com.lifolo.common.ApiException
import com.lifolo.common.DhakaTime
import com.lifolo.common.Ids
import com.lifolo.common.Js
import com.lifolo.common.Phones
import com.lifolo.common.Sql
import com.lifolo.common.action.ActionContext
import com.lifolo.common.localDate
import com.lifolo.common.ms
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * The chamber's desk: walk-in patients (no app appointment) with a serial from the same counter as the app's confirmed
 * appointments, and the chamber day the desktop keeps offline (appointments + walk-ins, today and the next 7 days).
 */
@Component
class ChamberDesk(
    private val jdbc: JdbcTemplate,
    private val providers: ProviderStore,
) {
    data class Chamber(val key: String, val name: String, val nameBn: String, val fee: String, val noFee: Boolean)

    fun chambers(doctorUid: String): List<Chamber> =
        providers.load(doctorUid)?.chambers.orEmpty().map { Chamber(it.key, it.hospitalName, it.hospitalNameBn, it.fee, it.noFee == true) }

    private val DAY = Regex("^\\d{4}-\\d\\d-\\d\\d$")

    /** "YYYY-MM-DD" or UTC-midnight ms of the Dhaka day; null when missing; [fallback] when not given */
    private fun dayOf(ctx: ActionContext, v: JsonNode?, fallback: Long): Long {
        if (v == null || v.isNull) return DhakaTime.todayUtcMidnight(fallback)
        val t = Js.string(v)
        if (t != null && DAY.matches(t)) {
            return try {
                DhakaTime.toUtcMidnightMs(LocalDate.parse(t))
            } catch (e: Exception) {
                throw ctx.err(400, "Check the date", "তারিখ দেখে নিন")
            }
        }
        val n = Js.integer(v) ?: throw ctx.err(400, "Check the date", "তারিখ দেখে নিন")
        return DhakaTime.todayUtcMidnight(n - 6 * 3600 * 1000L)
    }

    /**
     * walk-in {name, phone?, chamberKey, date?, serial?} of the doctor [doctorUid] recorded at [at] → {id, serial}.
     * `date` defaults to the Dhaka day of [at]; it may be at most 1 day before / 7 days after [at].
     */
    fun walkIn(ctx: ActionContext, doctorUid: String, data: JsonNode, at: Long, deviceId: String?, now: Long): Map<String, Any?> {
        val name = DesktopRules.clean(Js.string(data.get("name")))
        if (name.isEmpty() || name.length > 80) throw ctx.err(400, "Write the patient's name", "রোগীর নাম লিখুন") // bn-ok
        val rawPhone = Js.string(data.get("phone"))?.trim().orEmpty()
        val phone = if (rawPhone.isEmpty()) null else Phones.normalizeBd(rawPhone)?.let { Phones.localBd(it) }
            ?: throw ctx.err(400, "Check the mobile number", "মোবাইল নম্বর দেখে নিন") // bn-ok
        val list = chambers(doctorUid)
        val key = Js.string(data.get("chamberKey")) ?: ""
        if (list.none { it.key == key }) throw ctx.err(400, "Choose the chamber", "চেম্বার বেছে নিন") // bn-ok
        val date = dayOf(ctx, data.get("date"), at)
        val atDay = DhakaTime.todayUtcMidnight(at)
        if (date < atDay - DesktopRules.DAY_MS || date > atDay + 7 * DesktopRules.DAY_MS) throw ctx.err(400, "Check the date", "তারিখ দেখে নিন")
        val own = Js.integer(data.get("serial"))?.takeIf { it in 1..999 }?.toInt()
        val serial = SerialCounters.next(jdbc, doctorUid, date, key, own, now)
        val id = Ids.newId()
        jdbc.update(
            """
            INSERT INTO chamber_walkins (id, doctor_uid, chamber_key, visit_date, name, phone, serial, device_id, created_by, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            id, doctorUid, key, Sql.date(DhakaTime.fromUtcMidnightMs(date)), name, phone, serial, deviceId, ctx.user.id, Sql.ts(at),
        )
        return linkedMapOf("id" to id, "serial" to serial, "date" to date)
    }

    /** the doctor's appointments and walk-ins from today to today + 7 (Dhaka days) */
    fun window(doctorUid: String, now: Long): Map<String, Any?> {
        val today = DhakaTime.todayUtcMidnight(now)
        val from = Sql.date(DhakaTime.fromUtcMidnightMs(today))
        val to = Sql.date(DhakaTime.fromUtcMidnightMs(today + 7 * DesktopRules.DAY_MS))
        val appts = jdbc.query(
            """
            SELECT * FROM appointments WHERE provider_uid = ? AND visit_date BETWEEN ? AND ?
              AND status IN ('pending', 'confirmed', 'completed') ORDER BY visit_date, serial NULLS LAST, created_at, id LIMIT 2000
            """.trimIndent(),
            { rs, _ -> Appt.of(rs) }, doctorUid, from, to,
        ).map {
            linkedMapOf(
                "id" to it.id, "name" to it.patientName, "date" to it.date, "day" to DhakaTime.fromUtcMidnightMs(it.date).toString(),
                "chamberKey" to it.key, "chamber" to it.chamber, "start" to it.start, "serial" to it.serial?.takeIf { s -> s != 0 }, "status" to it.status,
            )
        }
        val walkins = jdbc.query(
            "SELECT * FROM chamber_walkins WHERE doctor_uid = ? AND visit_date BETWEEN ? AND ? ORDER BY visit_date, serial, id LIMIT 2000",
            { rs, _ ->
                // no phone on the desk (I4: data kept on a PC that may be lost)
                linkedMapOf(
                    "id" to rs.getString("id"), "name" to rs.getString("name"),
                    "date" to DhakaTime.toUtcMidnightMs(rs.localDate("visit_date")!!), "day" to rs.localDate("visit_date").toString(),
                    "chamberKey" to rs.getString("chamber_key"),
                    "serial" to rs.getInt("serial"), "createdAt" to rs.ms("created_at"),
                )
            },
            doctorUid, from, to,
        )
        return linkedMapOf(
            "from" to today, "days" to 8, "appointments" to appts, "walkins" to walkins,
        )
    }

    /** an appointment of this doctor (checkin) */
    fun appointmentOf(ctx: ActionContext, doctorUid: String, id: String?): Appt {
        val a = id?.let { jdbc.query("SELECT * FROM appointments WHERE id = ?", { rs, _ -> Appt.of(rs) }, it).firstOrNull() }
        if (a == null || a.providerUid != doctorUid) throw ApiException(404, ctx.tr("Appointment not found", "অ্যাপয়েন্টমেন্ট পাওয়া যায়নি"), "not_found")
        return a
    }
}
