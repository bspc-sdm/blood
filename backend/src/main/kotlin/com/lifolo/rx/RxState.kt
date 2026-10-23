package com.lifolo.rx

import com.lifolo.contracts.Prescription
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * The patient's "where is this prescription now" summary (`state` on each rx_mine item and on rx_get for the patient),
 * computed from what already exists: dispensings, pharmacy orders, lab orders, report reviews, the doctor's
 * cancellation, the follow-up date and the refill reminder date. Web and app show the same thing from it.
 * The rules (states, the priority of the one line, when a prescription is "past") are in backend/docs/RX-STATE.md.
 * Pure — unit-tested in RxStateTest; [RxStateLoader] reads the rows for many prescriptions at once.
 */
object RxState {
    /** a prescription with nothing happening this many days after its last activity moves to "past" */
    const val STALE_DAYS = 30L

    /** nothing to track (notes only, free-text medicines): "past" this many days after writing (or when the course ends) */
    const val NOTES_DAYS = 7L

    /** "running out" shows from the refill reminder day (2 days before the medicines end) for this many days */
    const val RUNNING_OUT_DAYS = 7L

    /** open pharmacy orders, the most advanced wins */
    private val ORDER_RANK = mapOf("sent" to 1, "accepted" to 2, "ready" to 3, "out" to 4)

    /** live lab orders: the least advanced decides the tests line */
    private val LAB_RANK = mapOf("sent" to 1, "received" to 2, "reported" to 3)

    data class Order(val status: String, val mode: String, val pharmacyName: String, val createdAt: Long, val updatedAt: Long?) {
        val at: Long get() = updatedAt ?: createdAt
    }

    data class Lab(val status: String, val labName: String, val createdAt: Long, val updatedAt: Long?, val reportedAt: Long?)

    data class Review(val status: String, val at: Long)

    data class Facts(val cancelledAt: Long? = null, val orders: List<Order> = emptyList(), val labs: List<Lab> = emptyList(), val reviews: List<Review> = emptyList())

    private fun tone(primary: String): String = when (primary) {
        "cancelled" -> "muted"
        "running_out", "declined" -> "error"
        "sent", "accepted", "ready", "out", "lab_sent", "lab_received" -> "primary"
        "all_bought", "lab_reported" -> "good"
        "partial", "follow_up" -> "info"
        else -> "neutral" // not_bought, lab_pending, written
    }

    fun compute(rx: Prescription, f: Facts, now: Long = System.currentTimeMillis(), withTimeline: Boolean = false): LinkedHashMap<String, Any?> {
        val day = RxRules.DAY_MS
        val today = RxRules.dhakaToday(now)
        val cancelled = rx.status == "cancelled"
        val writtenDay = RxRules.dhakaToday(rx.createdAt)

        // ---------- medicines (only an allopathic doctor's list is bought through pharmacies and counted) ----------
        var medicine: LinkedHashMap<String, Any?>? = null
        var medsDone = true
        val lastBuyAt = rx.dispensed.maxOfOrNull { it.at }
        val openOrder = f.orders.filter { it.status in ORDER_RANK }.maxWithOrNull(compareBy<Order>({ ORDER_RANK[it.status] }, { it.at }))
        if (rx.pharmacyOk && rx.medicines.isNotEmpty()) {
            val st = RxRules.medStatus(rx.medicines, rx.dispensed)
            val total = st.size
            val bought = st.count { it.got > 0 }
            // a delivered online order without counted medicines: the pharmacy brought what was asked
            val complete = st.all { it.done } || (rx.dispensed.isEmpty() && f.orders.any { it.status == "delivered" })
            val refill = rx.refillAt
            val runningOut = !complete && bought > 0 && refill != null && today >= refill && today < refill + RUNNING_OUT_DAYS * day
            val lastClosed = f.orders.filter { it.status !in ORDER_RANK }.maxByOrNull { it.at }
            val declined = lastClosed?.status == "cancelled" && (lastBuyAt == null || lastBuyAt < lastClosed.at)
            val state = when {
                openOrder != null -> openOrder.status
                runningOut -> "running_out"
                complete -> "all_bought"
                declined -> "declined"
                bought > 0 -> "partial"
                else -> "not_bought"
            }
            medsDone = complete
            medicine = linkedMapOf(
                "state" to state, "bought" to bought, "total" to total,
                "pharmacyName" to (openOrder ?: lastClosed.takeIf { declined })?.pharmacyName?.takeIf { it.isNotEmpty() },
                "mode" to openOrder?.mode,
            )
        }

        // ---------- tests ----------
        var lab: LinkedHashMap<String, Any?>? = null
        var testsDone = true
        val live = f.labs.filter { it.status in LAB_RANK }
        if (rx.tests.isNotEmpty() || live.isNotEmpty()) {
            val least = live.minByOrNull { LAB_RANK[it.status]!! }
            val state = least?.status ?: "pending"
            testsDone = state == "reported"
            lab = linkedMapOf(
                "state" to state, "orders" to live.size, "reported" to live.count { it.status == "reported" },
                "labName" to least?.labName?.takeIf { it.isNotEmpty() }, "reviewed" to f.reviews.any { it.status == "done" },
            )
        }

        // ---------- follow-up, activity, phase ----------
        val followUp = rx.followUpDate?.takeIf { it != 0L }
        val followUpAhead = !cancelled && followUp != null && followUp >= today
        val maxDays = rx.medicines.maxOfOrNull { it.days } ?: 0
        val courseEnd = writtenDay + maxDays * day
        val lastActivity = listOfNotNull(
            rx.createdAt, lastBuyAt, courseEnd, followUp, rx.refillAt?.let { it + 2 * day },
            f.orders.maxOfOrNull { it.at },
            f.labs.maxOfOrNull { maxOf(it.createdAt, it.updatedAt ?: 0L, it.reportedAt ?: 0L) },
            f.reviews.maxOfOrNull { it.at },
        ).max()
        val waiting = openOrder != null || live.any { it.status != "reported" }
        val nothingTracked = medicine == null && lab == null
        val past = when {
            cancelled -> true
            followUpAhead -> false
            waiting -> false
            nothingTracked -> today >= maxOf(courseEnd, writtenDay + NOTES_DAYS * day)
            medsDone && testsDone -> true
            else -> today > RxRules.dhakaToday(lastActivity) + STALE_DAYS * day
        }
        val primary = when {
            cancelled -> "cancelled"
            medicine != null -> medicine["state"] as String
            lab != null -> "lab_" + lab["state"]
            followUpAhead -> "follow_up"
            else -> "written"
        }
        val out = linkedMapOf<String, Any?>(
            "phase" to if (past) "past" else "active",
            "primary" to primary,
            "tone" to tone(primary),
            "cancelled" to cancelled,
            "cancelledAt" to f.cancelledAt?.takeIf { cancelled },
            "medicine" to medicine,
            "lab" to lab,
            "followUpDate" to followUp?.takeIf { followUpAhead },
            "lastActivityAt" to lastActivity,
        )
        if (withTimeline) out["timeline"] = timeline(rx, f, followUp, now)
        return out
    }

    /** what happened, oldest first: {kind, at, name?, mode?, count?, ahead?}; the follow-up day is a step too (`ahead: true` while to come) */
    fun timeline(rx: Prescription, f: Facts, followUp: Long?, now: Long = System.currentTimeMillis()): List<Map<String, Any?>> {
        val list = ArrayList<LinkedHashMap<String, Any?>>()
        fun add(kind: String, at: Long, name: String? = null, extra: Map<String, Any?> = emptyMap()) {
            list.add(linkedMapOf<String, Any?>("kind" to kind, "at" to at).apply { if (!name.isNullOrEmpty()) put("name", name); putAll(extra) })
        }
        add("written", rx.createdAt)
        for (o in f.orders) {
            add("pharmacy_sent", o.createdAt, o.pharmacyName, mapOf("mode" to o.mode))
            // "done" is the pharmacy giving the medicines — the "bought" step below
            if (o.status in setOf("accepted", "ready", "out", "delivered", "cancelled", "withdrawn")) add("pharmacy_${o.status}", o.at, o.pharmacyName, mapOf("mode" to o.mode))
        }
        for (d in rx.dispensed) add("bought", d.at, d.name, mapOf("count" to d.items.size))
        for (l in f.labs) {
            add("lab_sent", l.createdAt, l.labName)
            when (l.status) {
                "received" -> add("lab_received", l.updatedAt ?: l.createdAt, l.labName)
                "reported" -> add("lab_reported", l.reportedAt ?: l.updatedAt ?: l.createdAt, l.labName)
                "cancelled" -> add("lab_cancelled", l.updatedAt ?: l.createdAt, l.labName)
            }
        }
        for (r in f.reviews) if (r.status == "done") add("reviewed", r.at)
        if (rx.status == "cancelled") add("cancelled", f.cancelledAt ?: rx.createdAt)
        if (followUp != null && rx.status != "cancelled") add("follow_up", followUp, extra = mapOf("ahead" to (followUp >= RxRules.dhakaToday(now))))
        list.sortBy { it["at"] as Long }
        return list
    }
}

/** the rows [RxState] needs, for many prescriptions in a few queries */
@Component
class RxStateLoader(private val jdbc: JdbcTemplate) {
    fun facts(ids: List<String>): Map<String, RxState.Facts> {
        if (ids.isEmpty()) return emptyMap()
        val arr = P3Sql.pgArray(ids)
        val cancelled = HashMap<String, Long>()
        jdbc.eachRow("SELECT id, cancelled_at FROM prescriptions WHERE id = ANY(?::text[]) AND cancelled_at IS NOT NULL", arr) { rs ->
            cancelled[rs.getString("id")] = rs.getTimestamp("cancelled_at").time
        }
        val orders = HashMap<String, MutableList<RxState.Order>>()
        jdbc.eachRow(
            "SELECT rx_id, status, mode, pharmacy_name, created_at, updated_at FROM rx_orders WHERE rx_id = ANY(?::text[]) ORDER BY created_at, id",
            arr,
        ) { rs ->
            orders.getOrPut(rs.getString("rx_id")) { ArrayList() }.add(
                RxState.Order(
                    rs.getString("status"), rs.getString("mode") ?: "shop", rs.getString("pharmacy_name") ?: "",
                    rs.getTimestamp("created_at").time, rs.getTimestamp("updated_at")?.time,
                ),
            )
        }
        val labs = HashMap<String, MutableList<RxState.Lab>>()
        jdbc.eachRow(
            "SELECT rx_id, status, lab_name, created_at, updated_at, reported_at FROM lab_orders WHERE rx_id = ANY(?::text[]) ORDER BY created_at, id",
            arr,
        ) { rs ->
            labs.getOrPut(rs.getString("rx_id")) { ArrayList() }.add(
                RxState.Lab(
                    rs.getString("status"), rs.getString("lab_name") ?: "", rs.getTimestamp("created_at").time,
                    rs.getTimestamp("updated_at")?.time, rs.getTimestamp("reported_at")?.time,
                ),
            )
        }
        val reviews = HashMap<String, MutableList<RxState.Review>>()
        jdbc.eachRow(
            "SELECT o.rx_id, r.status, COALESCE(r.done_at, r.updated_at) AS at FROM rx_reviews r JOIN lab_orders o ON o.id = r.lab_order_id WHERE o.rx_id = ANY(?::text[])",
            arr,
        ) { rs -> reviews.getOrPut(rs.getString("rx_id")) { ArrayList() }.add(RxState.Review(rs.getString("status"), rs.getTimestamp("at").time)) }
        return ids.associateWith { RxState.Facts(cancelled[it], orders[it].orEmpty(), labs[it].orEmpty(), reviews[it].orEmpty()) }
    }
}
