package com.lifolo.lab

import com.lifolo.common.Rules

/**
 * rxnet.js views of lab orders: labOrderView (who sees the home address), the segments of the lab's work list
 * (labSeg, segCounts, recentFirstOpen) and the doctor's view of a partner lab's report (doctorLabView, labsForDoctor).
 */
object LabViews {
    /** rxReviews statuses in which the doctor sees the results (paid by the patient and confirmed, or "no fee") */
    val DOCTOR_SEES = setOf("paid", "done")

    /** how many prescriptions (newest) and orders per prescription the doctor's history carries */
    const val LAB_HISTORY_RX = 20
    const val LAB_PER_RX = 10

    val LAB_SEGS = listOf("new", "active", "reported", "closed")

    /**
     * labOrderView. [who]: "patient" (patient/caregiver), "lab" (that lab), else nobody special (doctor).
     * The home address/phone only for the patient, and for the lab while the order is open and the visit not declined.
     */
    fun orderView(o: LabOrder, withPhotos: Boolean = false, who: String? = null, now: Long = System.currentTimeMillis()): LinkedHashMap<String, Any?> {
        val open = o.status == "sent" || o.status == "received"
        val contact = who == "patient" || (who == "lab" && open && o.home != null && o.home.status != "declined")
        return linkedMapOf(
            "id" to o.id,
            "rxId" to o.rxId,
            "code" to o.code,
            "labId" to o.labId,
            "labName" to o.labName,
            "patientName" to o.patientName,
            "patientAge" to o.patientAge,
            "patientGender" to o.patientGender,
            "doctorName" to o.doctorName,
            "tests" to o.tests,
            "status" to o.status,
            "createdAt" to o.createdAt,
            "invoiceId" to o.invoiceId,
            "prices" to o.prices?.toMap(),
            "home" to LabPrices.homeView(o.home, contact, now),
            "report" to o.report?.let { r ->
                linkedMapOf<String, Any?>(
                    "items" to r.items.map { it.toMap() },
                    "note" to r.note,
                    "reportedAt" to r.reportedAt,
                    "photoCount" to r.photoCount,
                ).apply { if (withPhotos) put("photos", r.photos.orEmpty()) }
            },
        )
    }

    /** labSeg: new (sent; not when the home visit is confirmed) · active · reported · closed */
    fun labSeg(status: String, homeStatus: String?): String = when (status) {
        "sent" -> if (homeStatus == "confirmed") "active" else "new"
        "received" -> "active"
        "reported" -> "reported"
        else -> "closed"
    }

    fun labSeg(o: LabOrder): String = labSeg(o.status, o.home?.status)

    /** segCounts: every segment, zero included */
    fun segCounts(segs: List<String>, list: List<String>): LinkedHashMap<String, Int> {
        val out = LinkedHashMap<String, Int>()
        segs.forEach { out[it] = 0 }
        list.forEach { out[it] = (out[it] ?: 0) + 1 }
        return out
    }

    /** recentFirstOpen: newest first, at most [max] — open ones get their place first */
    fun <T> recentFirstOpen(list: List<T>, createdAt: (T) -> Long, isOpen: (T) -> Boolean, max: Int): List<T> {
        val sorted = list.sortedByDescending(createdAt)
        val open = sorted.filter(isOpen).take(max)
        val rest = sorted.filterNot(isOpen).take(maxOf(0, max - open.size))
        return (open + rest).sortedByDescending(createdAt)
    }

    /** doctorLabView: status always; the results only when the review is paid/done (no photos, no money details) */
    fun doctorView(o: LabOrder, r: Review?): LinkedHashMap<String, Any?> {
        val review = r?.status
        val open = o.status == "reported" && review in DOCTOR_SEES
        return linkedMapOf(
            "id" to o.id,
            "rxId" to o.rxId,
            "labName" to o.labName,
            "tests" to o.tests,
            "status" to o.status,
            "createdAt" to o.createdAt,
            "reportedAt" to (o.reportedAt ?: 0L),
            "review" to review,
            "open" to open,
            "report" to if (open) orderView(o)["report"] else null,
            "comment" to if (open && review == "done") r!!.comment else "",
        )
    }

    /** the valid, distinct prescription ids the doctor's history looks at */
    fun historyIds(rxIds: List<String?>): List<String> = rxIds.filter { Rules.isValidId(it) }.map { it!! }.distinct().take(LAB_HISTORY_RX)
}
