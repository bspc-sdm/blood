package com.lifolo.appointments

import com.fasterxml.jackson.databind.JsonNode
import com.lifolo.appointments.QueueRules.Nums
import com.lifolo.appointments.QueueRules.Q
import com.lifolo.appointments.QueueRules.Stats
import com.lifolo.common.ApiException
import com.lifolo.common.DhakaTime
import com.lifolo.common.Js
import com.lifolo.common.Lang
import com.lifolo.common.Rules
import com.lifolo.common.Sql
import com.lifolo.common.action.ActionContext
import com.lifolo.common.action.ActionHandler
import com.lifolo.common.action.ActionModule
import com.lifolo.notify.PushService
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet

/**
 * queue.js — "serial running" (18 Oct 2026): the doctor or an assistant starts the day's serial, moves to the next
 * patient, picks one, pauses; patients (and caregivers) see the current number, how many are ahead and when to come;
 * "I'll be late" requests with the doctor's reply. Average minutes per patient from the gaps between "next" presses.
 *
 * queues/{providerUid}_{date}_{key} → `queues` (key = md5(chamberKey)[0:10], like the serial counters).
 * Node keeps the patient view 15 s in memory; the port reads it fresh each time (no stale numbers).
 */
@Component
class QueueActions(
    private val jdbc: JdbcTemplate,
    private val store: ProviderStore,
    private val appts: ApptRepo,
    private val badges: BadgeLookup,
    private val assistants: ProviderAssistantsStore,
    private val users: P2Users,
    private val push: PushService,
    private val clock: P2Clock,
    private val onlineSwitch: OnlineSwitch,
    txm: PlatformTransactionManager,
) : ActionModule {
    private val tx = TransactionTemplate(txm)

    override fun actions(): Map<String, ActionHandler> = mapOf(
        "queue_today" to ActionHandler(::queueToday),
        "queue_control" to ActionHandler(::queueControl),
        "appointment_queue" to ActionHandler(::appointmentQueue),
        "appointment_late" to ActionHandler(::appointmentLate),
        "appointment_late_reply" to ActionHandler(::appointmentLateReply),
    )

    private fun err(ctx: ActionContext, en: String, bn: String, status: Int = 400): ApiException = ctx.err(status, en, bn)

    // ------------------------------------------------------------------ storage

    private fun day(date: Long) = Sql.date(DhakaTime.fromUtcMidnightMs(date))

    private fun ms(rs: ResultSet, col: String): Long = rs.getTimestamp(col)?.time ?: 0L

    fun readQ(providerUid: String, date: Long, key: String, forUpdate: Boolean = false): Q =
        jdbc.query(
            "SELECT * FROM queues WHERE provider_uid = ? AND visit_date = ? AND queue_key = ?" + (if (forUpdate) " FOR UPDATE" else ""),
            { rs, _ ->
                Q(
                    started = rs.getBoolean("started"),
                    current = rs.getInt("current"),
                    pos = rs.getInt("pos"),
                    done = ProviderStore.ints(rs, "done"),
                    paused = rs.getBoolean("paused"),
                    pausedAt = ms(rs, "paused_at"),
                    startedAt = ms(rs, "started_at"),
                    lastAt = ms(rs, "last_at"),
                    advances = (rs.getArray("advances")?.array as? Array<*>)?.mapNotNull { (it as? Number)?.toLong() } ?: emptyList(),
                    finished = rs.getBoolean("finished"),
                    exists = true,
                )
            },
            providerUid, day(date), key,
        ).firstOrNull() ?: Q()

    private fun writeQ(providerUid: String, date: Long, key: String, chamberKey: String, q: Q, now: Long) {
        fun ts(v: Long) = if (v != 0L) Sql.ts(v) else null
        jdbc.update(
            """
            INSERT INTO queues (provider_uid, visit_date, queue_key, chamber_key, current, pos, done, advances, started, started_at,
              paused, paused_at, finished, last_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?::int[], ?::bigint[], ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (provider_uid, visit_date, queue_key) DO UPDATE SET chamber_key = EXCLUDED.chamber_key, current = EXCLUDED.current,
              pos = EXCLUDED.pos, done = EXCLUDED.done, advances = EXCLUDED.advances, started = EXCLUDED.started,
              started_at = EXCLUDED.started_at, paused = EXCLUDED.paused, paused_at = EXCLUDED.paused_at, finished = EXCLUDED.finished,
              last_at = EXCLUDED.last_at, updated_at = EXCLUDED.updated_at
            """.trimIndent(),
            providerUid, day(date), key, chamberKey, q.current, q.pos, P2Js.pgInts(q.done), P2Js.pgInts(q.advances), q.started,
            ts(q.startedAt), q.paused, ts(q.pausedAt), q.finished, ts(q.lastAt), Sql.ts(now),
        )
    }

    private fun counter(providerUid: String, date: Long, key: String): Int =
        jdbc.queryForList(
            "SELECT n FROM serial_counters WHERE provider_uid = ? AND visit_date = ? AND queue_key = ?", Int::class.java, providerUid, day(date), key,
        ).firstOrNull() ?: 0

    /** account deletion: the doctor's queues (no patient names, but the doctor's uid) */
    fun deleteFor(uid: String): Int = jdbc.update("DELETE FROM queues WHERE provider_uid = ?", uid)

    // ------------------------------------------------------------------ which day

    /**
     * The doctor/assistant's queue day: today; before 6 am a queue started yesterday and not finished. [wanted] = the
     * day the app shows — yesterday's (even finished) stays, so "next" after the last patient does not start today's.
     */
    fun queueDate(providerUid: String, chamberKey: String, wanted: Long?, now: Long): Long {
        val today = DhakaTime.todayUtcMidnight(now)
        if (wanted == today || !QueueRules.earlyHours(now)) return today
        val y = today - QueueRules.DAY
        val s = readQ(providerUid, y, QueueRules.keyOf(chamberKey))
        if (s.exists && s.started && (wanted == y || !s.finished)) return y
        return today
    }

    /** a patient's appointment day in the queue: today; yesterday's before 6 am when that queue started; else null */
    fun apptQueueDate(a: Appt, now: Long): Long? {
        val today = DhakaTime.todayUtcMidnight(now)
        if (a.date == today) return today
        if (a.date != today - QueueRules.DAY || !QueueRules.earlyHours(now)) return null
        val s = readQ(a.providerUid, a.date, QueueRules.keyOf(a.key))
        return if (s.exists && s.started) a.date else null
    }

    // ------------------------------------------------------------------ loading

    /** one chamber's day: queue, order, average */
    data class L(
        val key: String,
        val date: Long,
        val q: Q,
        val nums: Nums,
        val appts: List<Appt>,
        val stats: Map<String, Stats>,
        val chamber: Ch?,
    )

    private fun chamberFor(chambers: List<Ch>, key: String, chamberName: String?): Ch? =
        chambers.firstOrNull { it.key == key || (chamberName != null && it.hospitalName == chamberName) }

    fun load(providerUid: String, date: Long, chamberKey: String, cacheAppts: List<Appt>? = null, cacheProvider: ProviderDoc? = null): L {
        val key = QueueRules.keyOf(chamberKey)
        val q = readQ(providerUid, date, key)
        val n = counter(providerUid, date, key)
        val provider = cacheProvider ?: store.load(providerUid)
        val stats = store.queueStats(providerUid)
        val all = cacheAppts ?: appts.ofProviderOn(providerUid, date)
        val list = all.filter { it.key == chamberKey }
        val first = list.firstOrNull()
        val chamber = chamberFor(provider?.chambers ?: emptyList(), first?.key ?: chamberKey, first?.chamber)
        return L(key, date, q, QueueRules.numbers(list, n), list, stats, chamber)
    }

    // ------------------------------------------------------------------ views

    private fun queueView(q: Q, nums: Nums): Map<String, Any?> {
        val list = QueueRules.order(q, nums)
        return linkedMapOf(
            "started" to q.started,
            "current" to (if (q.started) q.current else 0),
            "paused" to q.paused,
            "finished" to (q.finished && !(q.started && q.current != 0)),
            "startedAt" to q.startedAt,
            "lastAt" to q.lastAt,
            "next" to list.firstOrNull(),
            "left" to list.size,
            "max" to nums.max,
        )
    }

    private fun lateView(a: Appt): Map<String, Any?>? =
        if (a.lateStatus.isNullOrEmpty()) {
            null
        } else {
            linkedMapOf("min" to (a.lateMin ?: 0), "note" to (a.lateNote ?: ""), "at" to (a.lateAt ?: 0L), "status" to a.lateStatus)
        }

    /** the doctor/assistant's view of one chamber */
    fun chamberView(l: L, now: Long): Map<String, Any?> {
        val rows = l.appts.filter { (it.serial ?: 0) > 0 }
            .sortedWith(compareBy<Appt>({ it.serial }, { it.createdAt }))
            .map { a ->
                val live = a.status in QueueRules.ACTIVE
                val p = if (live) QueueRules.placeOf(l.q, l.nums, a.serial) else QueueRules.QPlace("cancelled", 0)
                linkedMapOf(
                    "id" to a.id,
                    "serial" to a.serial,
                    "name" to a.patientName,
                    "status" to a.status,
                    "state" to (if (live && a.lateStatus == "later" && p.state == "waiting") "later" else p.state),
                    "ahead" to p.ahead,
                    "late" to lateView(a),
                )
            }
        val first = l.appts.firstOrNull()
        val avg = QueueRules.avgFor(l.stats, l.key, l.chamber, now)
        val out = LinkedHashMap<String, Any?>()
        out["chamberKey"] = first?.key ?: ""
        out["key"] = l.key
        // which day's queue (yesterday's past midnight) — the app sends it back with "next"
        out["date"] = l.date
        out["name"] = l.chamber?.hospitalName?.takeIf { it.isNotEmpty() } ?: first?.chamber ?: ""
        out["nameBn"] = l.chamber?.hospitalNameBn?.takeIf { it.isNotEmpty() } ?: first?.chamberBn ?: ""
        out["start"] = first?.start?.takeIf { it.isNotEmpty() } ?: l.chamber?.start ?: ""
        out["avgMin"] = avg.avgMin
        out["avgFrom"] = avg.avgFrom
        out["queue"] = queueView(l.q, l.nums)
        out["rows"] = rows
        out["lateAsked"] = rows.count { r ->
            @Suppress("UNCHECKED_CAST")
            val late = r["late"] as Map<String, Any?>?
            late != null && late["status"] == "asked" && (r["state"] == "waiting" || r["state"] == "later")
        }
        return out
    }

    /** the patient's view of one appointment */
    private fun patientView(a: Appt, l: L, now: Long): Map<String, Any?> {
        val place = QueueRules.placeOf(l.q, l.nums, a.serial)
        val avg = QueueRules.avgFor(l.stats, l.key, l.chamber, now)
        val startMs = QueueRules.atTime(a.date, a.start.ifEmpty { l.chamber?.start ?: "" })
        val eta = QueueRules.etaFor(l.q, place, avg.avgMin, startMs, now)
        return linkedMapOf(
            "live" to true,
            "serial" to a.serial,
            "started" to l.q.started,
            "paused" to l.q.paused,
            "current" to (if (l.q.started) l.q.current else 0),
            "state" to place.state,
            "ahead" to place.ahead,
            "later" to (a.lateStatus == "later"),
            "avgMin" to avg.avgMin,
            "avgFrom" to avg.avgFrom,
            "eta" to eta.eta,
            "arriveBy" to eta.arriveBy,
            "late" to lateView(a),
            "canLate" to (place.state == "waiting" && a.lateStatus != "later" && a.lateCount < QueueRules.MAX_LATE_ASKS),
        )
    }

    // ------------------------------------------------------------------ notifications: 2 before you, once

    fun notifyNear(l: L): Int {
        val q = l.q
        if (!q.started) return 0
        val list = QueueRules.order(q, l.nums)
        val due = ArrayList<Pair<Appt, Int>>()
        for (a in l.appts) {
            val s = a.serial
            if (a.status != "confirmed" || s == null || a.nearPushAt != null) continue
            val i = list.indexOf(s)
            if (i < 0) continue
            val ahead = i + if (q.current != 0) 1 else 0
            if (ahead in 1..QueueRules.NEAR) due.add(a to ahead)
        }
        var sent = 0
        for ((a, ahead) in due) {
            // online_chamber switched off for this patient: no "get ready for the video call" push (FEATURES.md)
            if (a.online && !onlineSwitch.allowed(a.patientUid)) continue
            // read and write together: two requests at once still notify once
            val mine = try {
                jdbc.update(
                    "UPDATE appointments SET near_push_at = ? WHERE id = ? AND near_push_at IS NULL AND status = 'confirmed'",
                    Sql.ts(clock.now()), a.id,
                ) > 0
            } catch (e: Exception) {
                false
            }
            if (!mine) continue
            sent++
            push.pushPatient(
                a.patientUid,
                if (a.online) "$ahead before you — get ready for the video call" else "$ahead before you — please come to the chamber",
                if (a.online) {
                    "আপনার আগে ${Lang.bnDigits(ahead.toString())} জন — অনলাইন ভিডিও কলের জন্য তৈরি থাকুন" // bn-ok
                } else {
                    "আপনার আগে ${Lang.bnDigits(ahead.toString())} জন — চেম্বারে চলে আসুন"
                },
                "${a.providerName} · ${a.chamberBn.ifEmpty { a.chamber }} · #${a.serial}",
                mapOf("type" to "appointment", "role" to "patient", "id" to a.id),
            )
        }
        return sent
    }

    // ------------------------------------------------------------------ who may run the queue

    data class Controller(val doctorUid: String, val role: String)

    /** the doctor/nutritionist/trainer, or an assistant the doctor added (by verified email) */
    fun controller(ctx: ActionContext, doctorUid: JsonNode?): Controller {
        val user = ctx.user
        val uid = user.id
        val d = Js.string(doctorUid)?.takeIf { it.isNotEmpty() } ?: uid
        if (d == uid) {
            if (badges.approvedBadge(uid) !in QueueRules.CHAMBER_TYPES) throw err(ctx, "Only verified professionals", "শুধু যাচাই করা পেশাজীবী", 403)
            return Controller(d, "doctor")
        }
        if (!Rules.isValidId(d)) throw err(ctx, "Not found", "পাওয়া যায়নি", 404)
        val email = if (user.emailVerified) user.email.lowercase() else ""
        if (email.isEmpty() || !store.exists(d) || !assistants.isAssistant(d, email)) {
            throw err(ctx, "You are not this doctor's assistant", "আপনি এই ডাক্তারের সহকারী নন", 403)
        }
        if (badges.approvedBadge(d) != "doctor") throw err(ctx, "This doctor is not verified now", "এই ডাক্তার এখন যাচাই করা নেই", 403)
        return Controller(d, "assistant")
    }

    // ------------------------------------------------------------------ actions

    /** doctor/assistant: today's chambers with serials and where everyone is (body.doctorUid = for which doctor) */
    fun queueToday(ctx: ActionContext): Any {
        val c = controller(ctx, ctx.body["doctorUid"])
        val now = clock.now()
        val date = DhakaTime.todayUtcMidnight(now)
        val today = appts.ofProviderOn(c.doctorUid, date)
        val ps = store.load(c.doctorUid)
        val chambers = ArrayList<Map<String, Any?>>()
        // past midnight: before 6 am yesterday's running queue comes first (that chamber's today waits)
        val carried = HashSet<String>()
        if (QueueRules.earlyHours(now)) {
            val y = date - QueueRules.DAY
            val live = jdbc.query(
                "SELECT chamber_key, started, finished FROM queues WHERE provider_uid = ? AND visit_date = ? ORDER BY queue_key LIMIT 20",
                { rs, _ -> Triple(rs.getString(1), rs.getBoolean(2), rs.getBoolean(3)) },
                c.doctorUid, day(y),
            ).filter { it.second && !it.third }.map { it.first }
            if (live.isNotEmpty()) {
                val yAppts = appts.ofProviderOn(c.doctorUid, y)
                for (k in live.distinct()) {
                    if (yAppts.none { it.key == k && (it.serial ?: 0) > 0 }) continue
                    chambers.add(chamberView(load(c.doctorUid, y, k, yAppts, ps), now))
                    carried.add(k)
                }
            }
        }
        val keys = today.filter { (it.serial ?: 0) > 0 }.map { it.key }.distinct().filter { it !in carried }
        for (k in keys) chambers.add(chamberView(load(c.doctorUid, date, k, today, ps), now))
        return linkedMapOf("role" to c.role, "date" to date, "chambers" to chambers)
    }

    private class Res(val q: Q, val stale: Boolean = false, val stats: Stats? = null)

    /**
     * doctor/assistant: op = start | next | set (appointmentId or serial) | pause | resume.
     * expect = the number the app shows (0 = nobody) — for next/set a queue that moved meanwhile is left alone and the
     * answer says stale: true; date = the day of the chamber shown (queue_today's chamber.date).
     */
    fun queueControl(ctx: ActionContext): Any {
        val b = ctx.body
        val doctorUid = controller(ctx, b["doctorUid"]).doctorUid
        val op = Js.toStr(b["op"])
        if (op !in listOf("start", "next", "set", "pause", "resume")) throw err(ctx, "Unknown action", "অজানা কাজ")
        val chamberKey = b.string("chamberKey") ?: ""
        val date = queueDate(doctorUid, chamberKey, b.int("date"), clock.now())
        val expect = if (op == "next" || op == "set") b.int("expect")?.takeIf { it >= 0 } else null
        val l = load(doctorUid, date, chamberKey)
        if (l.appts.none { it.status in QueueRules.ACTIVE && (it.serial ?: 0) > 0 }) {
            throw err(ctx, "No confirmed serial today", "আজ কোনো নিশ্চিত সিরিয়াল নেই", 404)
        }
        var target: Int? = null
        if (op == "set") {
            val aid = b.string("appointmentId")
            val serial = b.int("serial")
            if (Rules.isValidId(aid)) {
                val a = l.appts.firstOrNull { it.id == aid }
                if (a == null || a.status !in QueueRules.ACTIVE || (a.serial ?: 0) <= 0) throw err(ctx, "This serial is not active", "এই সিরিয়াল চালু নেই", 409)
                target = a.serial
            } else if (serial != null && serial in 1..999) {
                target = serial.toInt()
            } else {
                throw err(ctx, "Choose the serial", "সিরিয়াল বেছে নিন")
            }
        }
        val res = tx.execute { control(ctx, doctorUid, date, chamberKey, op, expect, target, l) }!!
        val after = l.copy(q = res.q, stats = if (res.stats != null) l.stats + (l.key to res.stats) else l.stats)
        if (!res.stale && op != "pause") notifyNear(after)
        val out = linkedMapOf<String, Any?>("chamber" to chamberView(after, clock.now()))
        if (res.stale) out["stale"] = true
        return out
    }

    private fun control(ctx: ActionContext, doctorUid: String, date: Long, chamberKey: String, op: String, expect: Long?, target: Int?, l: L): Res {
        val q = readQ(doctorUid, date, l.key, forUpdate = true)
        val now = clock.now()
        // someone else (doctor/assistant) just moved on — moving again would skip a patient
        if (expect != null && (if (q.started) q.current else 0).toLong() != expect) return Res(q, stale = true)
        // back from a pause: the paused time does not count in the average (move the last change forward)
        fun resumed(x: Q): Q =
            if (x.paused) x.copy(lastAt = (if (x.lastAt != 0L) x.lastAt else now) + maxOf(0L, now - (if (x.pausedAt != 0L) x.pausedAt else now)), paused = false, pausedAt = 0) else x
        if (op == "pause" || op == "resume") {
            if (!q.started) throw err(ctx, "Start the serial first", "আগে সিরিয়াল শুরু করুন", 409)
            var nq = q
            if (op == "pause" && !q.paused) nq = q.copy(paused = true, pausedAt = now)
            if (op == "resume") nq = resumed(q)
            writeQ(doctorUid, date, l.key, chamberKey, nq, now)
            return Res(nq)
        }
        if (op == "start" && q.started) return Res(q) // pressed twice: nothing
        val fresh = op == "start" || !q.started
        val cur = if (fresh) 0 else q.current
        val pos = if (fresh) 0 else q.pos
        val done = LinkedHashSet(q.done)
        if (cur != 0) done.add(cur)
        val n: Int
        var newPos = pos
        if (op == "set") {
            n = target!!
            done.remove(n)
            if (n > pos) newPos = n
        } else {
            val nx = QueueRules.nextAfter(pos, done, cur, l.nums)
            if (nx == null) {
                // everyone seen
                val nq = resumed(q).copy(started = true, current = 0, done = done.sorted(), finished = true, lastAt = now)
                writeQ(doctorUid, date, l.key, chamberKey, nq, now)
                return Res(nq)
            }
            n = nx.n
            if (nx.forward) newPos = n
        }
        // the average: from the previous patient to this one (pause excluded, 1–60 minutes)
        val lastAt = if (q.paused) (if (q.lastAt != 0L) q.lastAt else now) + maxOf(0L, now - (if (q.pausedAt != 0L) q.pausedAt else now)) else q.lastAt
        var stats: Stats? = null
        if (!fresh && cur != 0 && lastAt != 0L) {
            val gap = (now - lastAt).toDouble() / QueueRules.MIN
            if (gap >= QueueRules.MIN_GAP_MIN && gap <= QueueRules.MAX_GAP_MIN) {
                stats = writeGap(doctorUid, l.key, gap, now)
            }
        }
        val nq = q.copy(
            started = true, current = n, pos = newPos, done = done.sorted(), finished = false, paused = false, pausedAt = 0,
            lastAt = now, advances = ((if (fresh) emptyList() else q.advances) + now).takeLast(12),
            startedAt = if (fresh) now else q.startedAt,
        )
        writeQ(doctorUid, date, l.key, chamberKey, nq, now)
        return Res(nq, stats = stats)
    }

    /**
     * providers.queueStats[key] with a new gap. Node merges into the provider document even when there is none yet;
     * here a bare provider row (the badge's type, not listed) is created so "next" never fails.
     */
    private fun writeGap(doctorUid: String, key: String, gap: Double, now: Long): Stats? {
        if (!store.exists(doctorUid)) {
            val type = badges.approvedBadge(doctorUid)?.takeIf { it in QueueRules.CHAMBER_TYPES } ?: return null
            jdbc.update("INSERT INTO providers (uid, type) VALUES (?, ?) ON CONFLICT DO NOTHING", doctorUid, type)
        }
        val old = jdbc.query(
            "SELECT avg_minutes, n, at FROM provider_queue_stats WHERE provider_uid = ? AND queue_key = ? FOR UPDATE",
            { rs, _ -> Stats(rs.getDouble(1), rs.getInt(2), rs.getTimestamp(3).time) },
            doctorUid, key,
        ).firstOrNull()
        val st = QueueRules.addGap(old, gap, now)
        jdbc.update(
            """
            INSERT INTO provider_queue_stats (provider_uid, queue_key, avg_minutes, n, at) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (provider_uid, queue_key) DO UPDATE SET avg_minutes = EXCLUDED.avg_minutes, n = EXCLUDED.n, at = EXCLUDED.at
            """.trimIndent(),
            doctorUid, key, st.avg, st.n, Sql.ts(st.at),
        )
        return st
    }

    /** patient (or caregiver): the current number of today's confirmed serial, people ahead, when to come */
    fun appointmentQueue(ctx: ActionContext): Any {
        val id = ctx.body.string("id")
        if (!Rules.isValidId(id)) throw err(ctx, "Not found", "পাওয়া যায়নি", 404)
        val a = appts.get(id!!)
        if (a == null || a.patientUid != ctx.user.uid) throw err(ctx, "Not found", "পাওয়া যায়নি", 404)
        if (a.status != "confirmed" || (a.serial ?: 0) <= 0) return mapOf("live" to false)
        val now = clock.now()
        val date = apptQueueDate(a, now) ?: return mapOf("live" to false)
        return patientView(a, load(a.providerUid, date, a.key), now)
    }

    /** patient (or caregiver): "I'll be late" — 10/20/30/60 minutes + a short note; before the turn, changeable 3 times */
    fun appointmentLate(ctx: ActionContext): Any {
        val b = ctx.body
        val id = b.string("id")
        if (!Rules.isValidId(id)) throw err(ctx, "Not found", "পাওয়া যায়নি", 404)
        val a = appts.get(id!!)
        if (a == null || a.patientUid != ctx.user.uid) throw err(ctx, "Not found", "পাওয়া যায়নি", 404)
        val now = clock.now()
        val date = if (a.status == "confirmed" && (a.serial ?: 0) > 0) apptQueueDate(a, now) else null
        if (date == null) throw err(ctx, "Only for today's confirmed serial", "শুধু আজকের নিশ্চিত সিরিয়ালে", 409)
        val minutesD = P2Js.number(b["minutes"])
        val minutes = QueueRules.LATE_MINUTES.firstOrNull { it.toDouble() == minutesD } ?: throw err(ctx, "Choose how late", "কত দেরি হবে বেছে নিন")
        if (a.lateStatus == "later") throw err(ctx, "The doctor already moved you to the end", "ডাক্তার আপনাকে আগেই শেষে রেখেছেন", 409)
        if (a.lateCount >= QueueRules.MAX_LATE_ASKS) throw err(ctx, "You can't change it again today", "আজ আর বদলানো যাবে না", 409)
        val l = load(a.providerUid, date, a.key)
        val place = QueueRules.placeOf(l.q, l.nums, a.serial)
        if (place.state != "waiting") throw err(ctx, "Your serial has already passed", "আপনার সিরিয়াল পার হয়ে গেছে", 409)
        val note = Rules.cleanText(b.string("note"), 100)
        jdbc.update(
            "UPDATE appointments SET late_min = ?, late_note = ?, late_at = ?, late_status = 'asked', late_reply_at = NULL, late_count = ? WHERE id = ?",
            minutes, note, Sql.ts(now), a.lateCount + 1, a.id,
        )
        users.push(
            a.providerUid,
            "Patient will be $minutes min late",
            "রোগী ${Lang.bnDigits(minutes.toString())} মিনিট দেরিতে আসবেন",
            "${a.patientName} · #${a.serial}${if (note != null) " · $note" else ""}",
            mapOf("type" to "appointment", "role" to "provider"),
        )
        return mapOf("ok" to true)
    }

    /** doctor/assistant: reply to "late" — 'ok' (the serial stays) or 'later' (seen at the end; others keep their numbers) */
    fun appointmentLateReply(ctx: ActionContext): Any {
        val b = ctx.body
        val id = b.string("id")
        if (!Rules.isValidId(id)) throw err(ctx, "Not found", "পাওয়া যায়নি", 404)
        val a = appts.get(id!!) ?: throw err(ctx, "Not found", "পাওয়া যায়নি", 404)
        controller(ctx, if (a.providerUid == ctx.user.uid) null else com.fasterxml.jackson.databind.node.TextNode(a.providerUid))
        val op = when (b.string("op")) {
            "later" -> "later"
            "ok" -> "ok"
            else -> throw err(ctx, "Choose a reply", "উত্তর বেছে নিন")
        }
        if (a.lateStatus.isNullOrEmpty()) throw err(ctx, "No request for more time", "সময় চাওয়ার অনুরোধ নেই", 409)
        val now = clock.now()
        val date = (if (a.status == "confirmed") apptQueueDate(a, now) else null)
            ?: throw err(ctx, "This changed just now. Refresh.", "এইমাত্র বদলেছে। রিফ্রেশ করুন।", 409)
        val l = load(a.providerUid, date, a.key)
        val place = QueueRules.placeOf(l.q, l.nums, a.serial)
        if (place.state != "waiting") throw err(ctx, "This serial has already passed", "এই সিরিয়াল পার হয়ে গেছে", 409)
        jdbc.update(
            "UPDATE appointments SET late_status = ?, late_reply_at = ?" + (if (op == "later") ", near_push_at = NULL" else "") + " WHERE id = ?",
            op, Sql.ts(now), a.id,
        )
        push.pushPatient(
            a.patientUid,
            if (op == "ok") "Doctor: OK, your serial stays" else "Doctor: come later — you will be seen at the end",
            if (op == "ok") "ডাক্তার: ঠিক আছে, আপনার সিরিয়াল থাকছে" else "ডাক্তার: পরে আসুন — আপনাকে সবার শেষে দেখা হবে",
            "${a.providerName} · #${a.serial}",
            mapOf("type" to "appointment", "role" to "patient", "id" to a.id),
        )
        // moved to the end: the others move up — tell anyone now 2 away
        val after = load(a.providerUid, date, a.key)
        notifyNear(after)
        return linkedMapOf("ok" to true, "chamber" to chamberView(after, clock.now()))
    }
}
