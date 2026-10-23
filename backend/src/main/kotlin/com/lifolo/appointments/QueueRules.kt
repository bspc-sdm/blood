package com.lifolo.appointments

import com.lifolo.common.DhakaTime
import java.sql.ResultSet

/** appointments/{id} as the Node code reads it (`date` = UTC-midnight ms of the Dhaka day) */
data class Appt(
    val id: String,
    val patientUid: String,
    val providerUid: String,
    val patientName: String = "",
    val providerName: String = "",
    val memberId: String? = null,
    val type: String = "doctor",
    val chamber: String = "",
    val chamberBn: String = "",
    val chamberKey: String = "",
    val room: String = "",
    val schedule: String = "",
    val start: String = "",
    val date: Long = 0,
    val note: String = "",
    val status: String = "pending",
    val providerNote: String = "",
    val serial: Int? = null,
    val lateStatus: String? = null,
    val lateMin: Int? = null,
    val lateNote: String? = null,
    val lateAt: Long? = null,
    val lateCount: Int = 0,
    val nearPushAt: Long? = null,
    val createdAt: Long = 0,
    /** an online (video) chamber appointment (V620) and its fee: pay state null = nothing to pay */
    val online: Boolean = false,
    val payState: String? = null,
    val payRef: String? = null,
    val payNote: String? = null,
    val bkashNumber: String = "",
    val fee: String = "",
    val noFee: Boolean = false,
    val onlineNote: String = "",
    val payClaimedAt: Long? = null,
) {
    /** queue.js apptKey: chamberKey || chamber || '' */
    val key: String get() = chamberKey.ifEmpty { chamber }

    companion object {
        fun of(rs: ResultSet): Appt = Appt(
            id = rs.getString("id"),
            patientUid = rs.getString("patient_uid"),
            providerUid = rs.getString("provider_uid"),
            patientName = rs.getString("patient_name") ?: "",
            providerName = rs.getString("provider_name") ?: "",
            memberId = rs.getString("member_id"),
            type = rs.getString("type"),
            chamber = rs.getString("chamber") ?: "",
            chamberBn = rs.getString("chamber_bn") ?: "",
            chamberKey = rs.getString("chamber_key") ?: "",
            room = rs.getString("room") ?: "",
            schedule = rs.getString("schedule") ?: "",
            start = rs.getString("start_time") ?: "",
            date = DhakaTime.toUtcMidnightMs(rs.getObject("visit_date", java.time.LocalDate::class.java)),
            note = rs.getString("note") ?: "",
            status = rs.getString("status"),
            providerNote = rs.getString("provider_note") ?: "",
            serial = rs.getInt("serial").let { if (rs.wasNull()) null else it },
            lateStatus = rs.getString("late_status"),
            lateMin = rs.getInt("late_min").let { if (rs.wasNull()) null else it },
            lateNote = rs.getString("late_note"),
            lateAt = rs.getTimestamp("late_at")?.time,
            lateCount = rs.getInt("late_count"),
            nearPushAt = rs.getTimestamp("near_push_at")?.time,
            createdAt = rs.getTimestamp("created_at")?.time ?: 0,
            online = rs.getBoolean("online"),
            payState = rs.getString("pay_state"),
            payRef = rs.getString("pay_ref"),
            payNote = rs.getString("pay_note"),
            bkashNumber = rs.getString("bkash_number") ?: "",
            fee = rs.getString("fee") ?: "",
            noFee = rs.getBoolean("no_fee"),
            onlineNote = rs.getString("online_note") ?: "",
            payClaimedAt = rs.getTimestamp("pay_claimed_at")?.time,
        )
    }
}

/**
 * queue.js — the pure part of the live chamber queue ("serial running"): which serial comes next, where a patient
 * stands, the running average minutes per patient and the arrival estimate.
 */
object QueueRules {
    const val MIN = 60_000L
    const val DEFAULT_MIN = 10
    const val MAX_GAP_MIN = 60
    const val MIN_GAP_MIN = 1
    const val STATS_N = 200
    const val STATS_STALE = 30L * 24 * 3600 * 1000
    const val STATS_MIN_N = 3
    const val NEAR = 2
    val LATE_MINUTES = listOf(10, 20, 30, 60)
    const val MAX_LATE_ASKS = 3
    val CHAMBER_TYPES = listOf("doctor", "nutritionist", "trainer")
    val ACTIVE = listOf("confirmed", "completed")
    const val DAY = 24L * 3600 * 1000
    const val ROLLOVER_H = 6

    /** providers.queueStats[key] */
    data class Stats(val avg: Double, val n: Int, val at: Long)

    /** queues/{providerUid}_{date}_{key}; 0 = not set (Node `|| 0`) */
    data class Q(
        val started: Boolean = false,
        val current: Int = 0,
        val pos: Int = 0,
        val done: List<Int> = emptyList(),
        val paused: Boolean = false,
        val pausedAt: Long = 0,
        val startedAt: Long = 0,
        val lastAt: Long = 0,
        val advances: List<Long> = emptyList(),
        val finished: Boolean = false,
        val exists: Boolean = false,
    )

    /** the serial numbers of the day: all appointments cancelled, "come later", the highest */
    data class Nums(val cancelled: Set<Int>, val later: Set<Int>, val max: Int)

    data class Next(val n: Int, val forward: Boolean)

    /** a patient's place: state waiting | now | done | passed | finished, ahead = people before (incl. the current one) */
    data class QPlace(val state: String, val ahead: Int)

    fun keyOf(chamberKey: String): String = P2Js.md5Hex(chamberKey).take(10)

    /** before 6 am Dhaka (yesterday's running queue still counts) */
    fun earlyHours(now: Long): Boolean = now + 6 * 3600 * 1000 - DhakaTime.todayUtcMidnight(now) < ROLLOVER_H * 3600 * 1000L

    /** "HH:MM" of a Dhaka day (UTC midnight ms) in ms */
    fun atTime(date: Long, hhmm: String?): Long? {
        if (hhmm == null || !Regex("^\\d{2}:\\d{2}$").matches(hhmm)) return null
        return date + (hhmm.take(2).toInt() * 60 + hhmm.substring(3, 5).toInt()) * MIN - 6 * 3600 * 1000
    }

    fun numbers(appts: List<Appt>, counter: Int = 0): Nums {
        val live = HashSet<Int>()
        val any = LinkedHashSet<Int>()
        val later = LinkedHashSet<Int>()
        var max = counter
        for (a in appts) {
            val s = a.serial ?: continue
            if (s < 1) continue
            any.add(s)
            max = maxOf(max, s)
            if (a.status in ACTIVE) {
                live.add(s)
                if (a.lateStatus == "later") later.add(s)
            }
        }
        return Nums(any.filter { it !in live }.toSet(), later, max)
    }

    fun nextAfter(pos: Int, done: Set<Int>, cur: Int, nums: Nums): Next? {
        for (n in pos + 1..nums.max) {
            if (n !in done && n !in nums.later && n !in nums.cancelled) return Next(n, true)
        }
        val l = nums.later.filter { it !in done && it != cur }.minOrNull()
        return if (l != null && l != 0) Next(l, false) else null
    }

    fun order(q: Q, nums: Nums): List<Int> {
        val done = HashSet(q.done)
        var cur = if (q.started) q.current else 0
        var pos = if (q.started) q.pos else 0
        if (cur != 0) done.add(cur)
        val out = ArrayList<Int>()
        for (i in 0 until 1000) {
            val nx = nextAfter(pos, done, cur, nums) ?: break
            out.add(nx.n)
            done.add(nx.n)
            cur = nx.n
            if (nx.forward) pos = nx.n
        }
        return out
    }

    fun placeOf(q: Q, nums: Nums, serial: Int?): QPlace {
        if (q.started && serial != null && q.current == serial) return QPlace("now", 0)
        if (serial != null && serial in q.done) return QPlace("done", 0)
        val list = order(q, nums)
        val i = if (serial == null) -1 else list.indexOf(serial)
        if (i < 0) return QPlace(if (q.finished) "finished" else "passed", 0)
        return QPlace("waiting", i + if (q.started && q.current != 0) 1 else 0)
    }

    data class Avg(val avgMin: Int, val avgFrom: String)

    /** minutes per patient: the history (enough of it), else the chamber's setting, else 10 */
    fun avgFor(stats: Map<String, Stats>, key: String, chamber: Ch?, now: Long): Avg {
        val st = stats[key]
        if (st != null && st.n >= STATS_MIN_N && now - st.at < STATS_STALE && st.avg > 0) {
            return Avg(maxOf(1, Math.floor(st.avg + 0.5).toInt()), "history")
        }
        val set = chamber?.patientMin?.takeIf { it > 0 }
        return Avg(set ?: DEFAULT_MIN, if (set != null) "setting" else "default")
    }

    /** running average with a new gap (minutes) */
    fun addGap(st: Stats?, gapMin: Double, now: Long): Stats {
        val fresh = st == null || st.n <= 0 || now - st.at >= STATS_STALE
        val n = if (fresh) 1 else minOf(st.n + 1, STATS_N)
        val avg = if (fresh) gapMin else st.avg + (gapMin - st.avg) / n
        return Stats(Math.floor(avg * 100 + 0.5) / 100, n, now)
    }

    private fun round5(ms: Long, up: Boolean): Long {
        val step = 5 * MIN
        return (if (up) Math.ceilDiv(ms, step) else Math.floorDiv(ms, step)) * step
    }

    data class Eta(val eta: Long?, val arriveBy: Long?)

    fun etaFor(q: Q, place: QPlace, avgMin: Int, startMs: Long?, now: Long): Eta {
        if (place.state != "waiting") return Eta(if (place.state == "now") now else null, null)
        val avg = avgMin * MIN
        var eta: Long
        if (!q.started) {
            eta = maxOf(startMs?.takeIf { it != 0L } ?: now, now) + place.ahead * avg
        } else {
            val curEnd = if (q.paused) now + avg else maxOf(now, (if (q.lastAt != 0L) q.lastAt else now) + avg)
            eta = if (q.current != 0) curEnd + (place.ahead - 1) * avg else now + place.ahead * avg
        }
        eta = round5(eta, true)
        val arriveBy = round5(eta - maxOf(20, 2 * avgMin) * MIN, false)
        return Eta(eta, arriveBy)
    }
}
