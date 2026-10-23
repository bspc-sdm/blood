package com.lifolo.appointments

import com.lifolo.common.DhakaTime
import com.lifolo.common.Sql
import com.lifolo.common.action.ActionContext
import com.lifolo.common.action.ActionHandler
import com.lifolo.common.action.ActionModule
import com.lifolo.listings.SwitchLog
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.stereotype.Component

/**
 * LISTINGS.md "আজ সিরিয়াল চলছে / আজ বন্ধ": a chamber closed on one day (`chamber_day_closed`). A closed day is skipped
 * by `nextSlot`, is not "open now" and cannot be booked (`book` answers like a day the chamber does not sit).
 * Requests already made for that day stay — the doctor/assistant handles them in the queue as before.
 */
@Component
class ChamberDays(private val jdbc: JdbcTemplate) {
    companion object {
        const val DAY_MS = 24L * 3600 * 1000

        /** how far ahead a day can be closed */
        const val AHEAD_DAYS = 60

        fun key(uid: String, chamberKey: String, date: Long) = "$uid|$chamberKey|$date"
    }

    /** closed days of these doctors from today on, as [key] strings */
    fun closedFrom(uids: Collection<String>, now: Long): Set<String> {
        if (uids.isEmpty()) return emptySet()
        val from = Sql.date(DhakaTime.fromUtcMidnightMs(DhakaTime.todayUtcMidnight(now)))
        val out = HashSet<String>()
        for (chunk in uids.distinct().chunked(500)) {
            jdbc.query(
                "SELECT provider_uid, chamber_key, visit_date FROM chamber_day_closed WHERE visit_date >= ? AND provider_uid IN (${chunk.joinToString(",") { "?" }})",
                RowCallbackHandler { rs -> out.add(key(rs.getString(1), rs.getString(2), DhakaTime.toUtcMidnightMs(rs.getDate(3).toLocalDate()))) },
                *(listOf<Any?>(from) + chunk).toTypedArray(),
            )
        }
        return out
    }

    fun isClosed(uid: String, chamberKey: String, date: Long): Boolean = jdbc.queryForList(
        "SELECT 1 FROM chamber_day_closed WHERE provider_uid = ? AND chamber_key = ? AND visit_date = ?",
        uid, chamberKey, Sql.date(DhakaTime.fromUtcMidnightMs(date)),
    ).isNotEmpty()

    fun set(uid: String, chamberKey: String, date: Long, closed: Boolean, by: String?) {
        val d = Sql.date(DhakaTime.fromUtcMidnightMs(date))
        if (closed) {
            jdbc.update(
                "INSERT INTO chamber_day_closed (provider_uid, chamber_key, visit_date, closed_by) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
                uid, chamberKey, d, by,
            )
        } else {
            jdbc.update("DELETE FROM chamber_day_closed WHERE provider_uid = ? AND chamber_key = ? AND visit_date = ?", uid, chamberKey, d)
        }
    }

    /** pending + confirmed requests of one chamber on one day */
    fun requestsOn(uid: String, chamberKey: String, date: Long): Int = jdbc.queryForObject(
        "SELECT count(*) FROM appointments WHERE provider_uid = ? AND chamber_key = ? AND visit_date = ? AND status IN ('pending', 'confirmed')",
        Int::class.java, uid, chamberKey, Sql.date(DhakaTime.fromUtcMidnightMs(date)),
    ) ?: 0
}

/**
 * chamber_day_set {doctorUid?, chamberIndex | chamberKey, date?, closed} and chamber_days {doctorUid?} — the doctor, or an
 * assistant the doctor added (the same check as the queue: QueueActions.controller). Daily switch limit + switch log.
 */
@Component
class ChamberDayActions(
    private val days: ChamberDays,
    private val queue: QueueActions,
    private val store: ProviderStore,
    private val appts: AppointmentActions,
    private val clock: P2Clock,
    private val log: SwitchLog,
) : ActionModule {
    override fun actions(): Map<String, ActionHandler> = mapOf(
        "chamber_day_set" to ActionHandler(::set),
        "chamber_days" to ActionHandler(::list),
    )

    private fun chamberOf(ctx: ActionContext, p: ProviderDoc): Pair<Int, Ch> {
        val b = ctx.body
        val key = b.string("chamberKey")?.takeIf { it.isNotEmpty() }
        val idx = b.int("chamberIndex")?.toInt()
        val i = when {
            key != null -> p.chambers.indexOfFirst { it.key == key }
            idx != null -> idx
            else -> -1
        }
        val c = p.chambers.getOrNull(i) ?: throw ctx.err(404, "Chamber not found", "চেম্বার পাওয়া যায়নি") // bn-ok
        // the chamber the client meant moved (the doctor edited the list meanwhile)
        if (key != null && idx != null && idx != i) {
            throw ctx.err(409, "The doctor just changed their chambers. Please open the page again.", "ডাক্তার এইমাত্র চেম্বারের তথ্য বদলেছেন। পাতাটা আবার খুলুন।")
        }
        return i to c
    }

    fun set(ctx: ActionContext): Any {
        val c = queue.controller(ctx, ctx.body["doctorUid"])
        val p = store.load(c.doctorUid) ?: throw ctx.notFound()
        val (i, ch) = chamberOf(ctx, p)
        val closed = ctx.body.bool("closed") ?: throw ctx.err(400, "Choose open or closed", "খোলা না বন্ধ বেছে নিন") // bn-ok
        val now = clock.now()
        val today = DhakaTime.todayUtcMidnight(now)
        val date = ctx.body.int("date") ?: today
        if (date % ChamberDays.DAY_MS != 0L || date < today || date > today + ChamberDays.AHEAD_DAYS * ChamberDays.DAY_MS) {
            throw ctx.err(400, "Pick a date within the next 60 days", "আগামী ৬০ দিনের মধ্যে তারিখ দিন")
        }
        if (closed && !CareRules.chamberOpenOn(ch, date)) {
            throw ctx.err(400, "The chamber is closed on that day. Pick one of its days.", "সেদিন চেম্বার বন্ধ। চেম্বারের বারগুলোর একটা বেছে নিন।")
        }
        log.use(ctx)
        days.set(c.doctorUid, ch.key, date, closed, ctx.caller.uid)
        log.write("chamber", "${c.doctorUid}|${ch.key}", c.doctorUid, ctx.caller.uid, if (c.role == "doctor") "owner" else "assistant", "closedDay", "$date:$closed")
        return linkedMapOf(
            "ok" to true, "chamberIndex" to i, "chamberKey" to ch.key, "date" to date, "closed" to closed,
            // requests already made for that day (pending + confirmed) — the app can remind to tell them
            "requests" to days.requestsOn(c.doctorUid, ch.key, date),
        )
    }

    /** the doctor's chambers with today's state and the closed days ahead, and the public card ("রোগীরা আপনাকে যেভাবে দেখেন") */
    fun list(ctx: ActionContext): Any {
        val c = queue.controller(ctx, ctx.body["doctorUid"])
        val p = store.load(c.doctorUid) ?: return linkedMapOf("items" to emptyList<Any>(), "card" to null)
        val now = clock.now()
        val today = DhakaTime.todayUtcMidnight(now)
        val closed = days.closedFrom(listOf(c.doctorUid), now)
        val items = p.chambers.mapIndexed { i, ch ->
            val mine = closed.filter { it.startsWith("${c.doctorUid}|${ch.key}|") }.map { it.substringAfterLast('|').toLong() }.sorted()
            linkedMapOf(
                "chamberIndex" to i,
                "chamberKey" to ch.key,
                "name" to ch.hospitalName,
                "nameBn" to ch.hospitalNameBn,
                "online" to ch.online,
                "appointments" to ch.appointments,
                "sitsToday" to CareRules.chamberOpenOn(ch, today),
                "closedToday" to (today in mine),
                "closedDates" to mine,
                "requestsToday" to days.requestsOn(c.doctorUid, ch.key, today),
            )
        }
        return linkedMapOf("doctorUid" to c.doctorUid, "role" to c.role, "items" to items, "card" to appts.publicCard(ctx, c.doctorUid))
    }
}
