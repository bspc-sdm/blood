package com.lifolo.appointments

import com.lifolo.common.DhakaTime
import com.lifolo.common.Lang
import com.lifolo.common.Sql
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.stereotype.Component

/**
 * LISTINGS.md `nextSlot` of a doctor card: the first day (today .. 13 days ahead, Dhaka) on which a chamber that takes
 * appointments sits and a serial can still be asked for — the same day rules as `book` (today only while a session is not
 * over). A chamber with minutes per patient (`patientMin`) has a capacity per day (session minutes / patientMin); when
 * its pending + confirmed requests reach it, that day is full and the next free day is looked for. Only when every day
 * of the window is full does `nextSlot` point at the first open day with `available: false`.
 */
@Component
class NextSlots(private val jdbc: JdbcTemplate) {
    data class Slot(val date: Long, val chamberIndex: Int, val available: Boolean)

    companion object {
        const val DAYS = 14
        const val DAY_MS = 24L * 3600 * 1000

        private fun minutes(t: String): Int? =
            if (t.length == 5 && t[2] == ':') t.take(2).toIntOrNull()?.let { h -> t.drop(3).toIntOrNull()?.let { h * 60 + it } } else null

        /** serials a chamber takes on [date]: session minutes / patientMin, null = no limit */
        fun capacity(c: Ch, date: Long): Int? {
            val per = c.patientMin?.takeIf { it > 0 } ?: return null
            var total = 0
            for (t in CareRules.chamberTimesOn(c, date)) {
                val s = minutes(t.start) ?: return null
                val e = minutes(t.end) ?: return null
                total += if (e > s) e - s else e + 24 * 60 - s
            }
            return if (total > 0) maxOf(1, total / per) else null
        }

        /** "আজ" / "কাল" / "২৫ অক্টোবর" (en: Today / Tomorrow / 25 Oct) */
        fun label(date: Long, today: Long, lang: String?): String = when ((date - today) / DAY_MS) {
            0L -> Lang.pick(lang, "Today", "আজ") // bn-ok
            1L -> Lang.pick(lang, "Tomorrow", "কাল") // bn-ok
            else -> DhakaTime.shortDate(date, lang == "en")
        }

        /**
         * The pure rule. [visible] = chambers the viewer may book (online ones only while the switch allows), [booked] =
         * pending + confirmed requests of (chamber key, date).
         */
        fun nextSlot(p: ProviderDoc, now: Long, visible: (Ch) -> Boolean, booked: (String, Long) -> Int): Slot? {
            if (!CareRules.takesAppointments(p)) return null
            val today = DhakaTime.todayUtcMidnight(now)
            val hm = CareRules.dhakaHHMM(now)
            var firstFull: Slot? = null
            for (k in 0 until DAYS) {
                val date = today + k * DAY_MS
                for ((i, c) in p.chambers.withIndex()) {
                    if (!c.appointments || !visible(c) || !CareRules.chamberOpenOn(c, date)) continue
                    if (k == 0) {
                        val slots = CareRules.chamberTimesOn(c, date)
                        if (slots.isNotEmpty() && slots.all { it.start.isNotEmpty() && it.end.isNotEmpty() && it.start < it.end && hm >= it.end }) continue
                    }
                    val cap = capacity(c, date)
                    if (cap != null && booked(c.key, date) >= cap) {
                        if (firstFull == null) firstFull = Slot(date, i, false)
                        continue
                    }
                    return Slot(date, i, true)
                }
            }
            return firstFull
        }
    }

    /** pending + confirmed requests per "uid|chamberKey|date" for these doctors in the window (only chambers with a capacity) */
    fun bookedCounts(docs: List<ProviderDoc>, now: Long): Map<String, Int> {
        val uids = docs.filter { d -> d.chambers.any { (it.patientMin ?: 0) > 0 } }.map { it.uid }
        if (uids.isEmpty()) return emptyMap()
        val today = DhakaTime.todayUtcMidnight(now)
        val out = HashMap<String, Int>()
        for (chunk in uids.chunked(500)) {
            jdbc.query(
                """
                SELECT provider_uid, chamber_key, visit_date, count(*) AS n FROM appointments
                WHERE provider_uid IN (${chunk.joinToString(",") { "?" }}) AND status IN ('pending', 'confirmed')
                  AND visit_date >= ? AND visit_date < ?
                GROUP BY provider_uid, chamber_key, visit_date
                """.trimIndent(),
                RowCallbackHandler { rs ->
                    val date = DhakaTime.toUtcMidnightMs(rs.getDate("visit_date").toLocalDate())
                    out["${rs.getString(1)}|${rs.getString(2)}|$date"] = rs.getInt("n")
                },
                *chunk.toTypedArray<Any?>(), Sql.date(DhakaTime.fromUtcMidnightMs(today)), Sql.date(DhakaTime.fromUtcMidnightMs(today + DAYS * DAY_MS)),
            )
        }
        return out
    }
}
