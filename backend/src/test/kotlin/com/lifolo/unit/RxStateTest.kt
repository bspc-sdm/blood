package com.lifolo.unit

import com.lifolo.contracts.Prescription
import com.lifolo.contracts.RxDispensing
import com.lifolo.contracts.RxMedicine
import com.lifolo.rx.RxRules
import com.lifolo.rx.RxState
import com.lifolo.rx.RxState.Facts
import com.lifolo.rx.RxState.Lab
import com.lifolo.rx.RxState.Order
import com.lifolo.rx.RxState.Review
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** RxState (backend/docs/RX-STATE.md): the one line, the tests line, "active"/"past" and the timeline */
class RxStateTest {
    private val day = RxRules.DAY_MS

    /** 2026-10-23 12:00 Dhaka */
    private val now = 1_792_735_200_000L
    private val today = RxRules.dhakaToday(now)

    private fun med(i: Int, name: String, days: Int = 10, dose: String = "1+0+1", form: String = "Tablet") = RxMedicine(i, name, form, "", dose, "", days, "")

    private fun rx(
        meds: List<RxMedicine> = listOf(med(0, "Napa"), med(1, "Seclo")),
        tests: List<String> = emptyList(),
        status: String = "active",
        createdAt: Long = now - 2 * day,
        dispensed: List<RxDispensing> = emptyList(),
        followUp: Long? = null,
        refillAt: Long? = null,
        type: String = "doctor",
        system: String? = "allopathy",
        freeText: Boolean = false,
    ) = Prescription(
        id = "rx1", code = "ABCD2345EF", status = status, createdAt = createdAt, doctorUid = "d", patientUid = "p", rxPatientId = null,
        appointmentId = null, chamberIndex = null, doctor = emptyMap(), patient = emptyMap(), complaints = "", findings = emptyMap(), diagnosis = "",
        medicines = meds, tests = tests, testPlace = "", testPlaceId = null, advice = "", followUpDate = followUp, followUpBookDate = null,
        followUpNote = "", refillAt = refillAt, dispensed = dispensed, doctorType = type, doctorSystem = system, freeText = freeText,
    )

    private fun st(r: Prescription, f: Facts = Facts(), at: Long = now) = RxState.compute(r, f, at)

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.sub(k: String) = this[k] as Map<String, Any?>?

    private fun order(status: String, at: Long = now - day, name: String = "লাইফ ফার্মেসি", mode: String = "shop") = Order(status, mode, name, at - 3600_000, at)

    @Test
    fun `medicines - nothing bought, sent, accepted, on the way, partial, all bought`() {
        st(rx()).let {
            assertEquals("not_bought", it["primary"])
            assertEquals("neutral", it["tone"])
            assertEquals("active", it["phase"])
            assertEquals(mapOf("state" to "not_bought", "bought" to 0, "total" to 2, "pharmacyName" to null, "mode" to null), it["medicine"])
            assertNull(it["lab"])
        }
        assertEquals("sent", st(rx(), Facts(orders = listOf(order("sent"))))["primary"])
        assertEquals("primary", st(rx(), Facts(orders = listOf(order("sent"))))["tone"])
        // two pharmacies: the most advanced order speaks
        val two = st(rx(), Facts(orders = listOf(order("sent"), order("accepted", mode = "online", name = "অনলাইন ফার্মা"))))
        assertEquals("accepted", two["primary"])
        assertEquals("অনলাইন ফার্মা", two.sub("medicine")!!["pharmacyName"])
        assertEquals("online", two.sub("medicine")!!["mode"])
        assertEquals("out", st(rx(), Facts(orders = listOf(order("out", mode = "online"))))["primary"])
        assertEquals("ready", st(rx(), Facts(orders = listOf(order("ready"))))["primary"])

        val some = listOf(RxDispensing("ph", "লাইফ ফার্মেসি", now - day, listOf(0 to 20)))
        st(rx(dispensed = some), Facts(orders = listOf(order("done")))).let {
            assertEquals("partial", it["primary"])
            assertEquals("info", it["tone"])
            assertEquals(1, it.sub("medicine")!!["bought"])
            assertEquals(2, it.sub("medicine")!!["total"])
            assertEquals("active", it["phase"])
        }
        val all = listOf(RxDispensing("ph", "লাইফ ফার্মেসি", now - day, listOf(0 to 20, 1 to 20)))
        st(rx(dispensed = all)).let {
            assertEquals("all_bought", it["primary"])
            assertEquals("good", it["tone"])
            // finished: everything bought, no tests, no follow-up ahead
            assertEquals("past", it["phase"])
        }
        // a delivered online order (no counted medicines) is bought
        assertEquals("all_bought", st(rx(), Facts(orders = listOf(order("delivered", mode = "online"))))["primary"])
    }

    @Test
    fun `the pharmacy could not give - declined until something is bought later`() {
        val f = Facts(orders = listOf(order("cancelled", at = now - day)))
        st(rx(), f).let {
            assertEquals("declined", it["primary"])
            assertEquals("error", it["tone"])
            assertEquals("লাইফ ফার্মেসি", it.sub("medicine")!!["pharmacyName"])
        }
        // the patient took it back: just not bought
        assertEquals("not_bought", st(rx(), Facts(orders = listOf(order("withdrawn"))))["primary"])
        // bought elsewhere afterwards
        val later = listOf(RxDispensing("ph2", "অন্য দোকান", now - 3600_000, listOf(0 to 2)))
        assertEquals("partial", st(rx(dispensed = later), f)["primary"])
    }

    @Test
    fun `running out - from the refill day for a week, an open order wins`() {
        val bought = listOf(RxDispensing("ph", "লাইফ ফার্মেসি", now - 9 * day, listOf(0 to 6, 1 to 6)))
        val r = rx(dispensed = bought, refillAt = today)
        assertEquals("running_out", st(r)["primary"])
        assertEquals("error", st(r)["tone"])
        assertEquals("partial", st(rx(dispensed = bought, refillAt = today + day))["primary"])
        assertEquals("partial", st(rx(dispensed = bought, refillAt = today - RxState.RUNNING_OUT_DAYS * day))["primary"])
        assertEquals("sent", st(r, Facts(orders = listOf(order("sent", at = now))))["primary"])
    }

    @Test
    fun `tests - pending, sent, sample taken, report, the least advanced order decides`() {
        val r = rx(meds = emptyList(), tests = listOf("CBC", "HbA1c"))
        st(r).let {
            assertEquals("lab_pending", it["primary"])
            assertNull(it["medicine"])
            assertEquals("pending", it.sub("lab")!!["state"])
        }
        fun lab(s: String) = Lab(s, "পপুলার", now - day, now - 3600_000, if (s == "reported") now - 3600_000 else null)
        assertEquals("lab_sent", st(r, Facts(labs = listOf(lab("sent"))))["primary"])
        assertEquals("lab_received", st(r, Facts(labs = listOf(lab("received"))))["primary"])
        st(r, Facts(labs = listOf(lab("reported"), lab("cancelled")), reviews = listOf(Review("done", now)))).let {
            assertEquals("lab_reported", it["primary"])
            assertEquals("good", it["tone"])
            assertEquals(1, it.sub("lab")!!["orders"])
            assertEquals(true, it.sub("lab")!!["reviewed"])
            assertEquals("past", it["phase"])
        }
        st(r, Facts(labs = listOf(lab("reported"), lab("sent")))).let {
            assertEquals("sent", it.sub("lab")!!["state"])
            assertEquals(1, it.sub("lab")!!["reported"])
            assertEquals("active", it["phase"])
        }
        // a lab that cancelled: the tests are pending again
        assertEquals("lab_pending", st(r, Facts(labs = listOf(lab("cancelled"))))["primary"])
        // medicines and tests: the medicine line is the main one, tests stay in "lab"
        val both = st(rx(tests = listOf("CBC")), Facts(labs = listOf(lab("received"))))
        assertEquals("not_bought", both["primary"])
        assertEquals("received", both.sub("lab")!!["state"])
    }

    @Test
    fun `cancelled by the doctor - past, muted, with the time`() {
        val f = Facts(cancelledAt = now - 3600_000, orders = listOf(order("sent")))
        st(rx(status = "cancelled"), f).let {
            assertEquals("cancelled", it["primary"])
            assertEquals("muted", it["tone"])
            assertEquals("past", it["phase"])
            assertEquals(true, it["cancelled"])
            assertEquals(now - 3600_000, it["cancelledAt"])
        }
    }

    @Test
    fun `follow-up - keeps it active until the day, then the normal rules`() {
        val all = listOf(RxDispensing("ph", "লাইফ ফার্মেসি", now - day, listOf(0 to 20, 1 to 20)))
        st(rx(dispensed = all, followUp = today + 5 * day)).let {
            assertEquals("active", it["phase"])
            assertEquals(today + 5 * day, it["followUpDate"])
            assertEquals("all_bought", it["primary"])
        }
        assertEquals("active", st(rx(dispensed = all, followUp = today))["phase"])
        st(rx(dispensed = all, followUp = today - day)).let {
            assertEquals("past", it["phase"])
            assertNull(it["followUpDate"])
        }
        // nothing else to show: the follow-up is the line
        val notes = rx(meds = emptyList(), type = "psychologist", system = null, followUp = today + 3 * day)
        assertEquals("follow_up", st(notes)["primary"])
        assertEquals("info", st(notes)["tone"])
    }

    @Test
    fun `nothing to track - notes and free-text medicines move to past after the course or a week`() {
        val notes = rx(meds = emptyList(), type = "psychologist", system = null)
        assertEquals("written", st(notes)["primary"])
        assertEquals("active", st(notes)["phase"])
        assertEquals("past", st(notes.copy(createdAt = now - 8 * day))["phase"])
        val homeo = rx(meds = listOf(med(0, "Arnica 30", days = 14)), system = "homeopathy", freeText = true, createdAt = now - 10 * day)
        assertNull(st(homeo)["medicine"])
        assertEquals("active", st(homeo)["phase"])
        assertEquals("past", st(homeo.copy(createdAt = now - 15 * day))["phase"])
    }

    @Test
    fun `stale - 30 days after the last activity, unless something is still waiting`() {
        val some = listOf(RxDispensing("ph", "লাইফ ফার্মেসি", now - 45 * day, listOf(0 to 2)))
        val old = rx(dispensed = some, createdAt = now - 50 * day)
        // course end (10 days) and the last purchase are long gone
        assertEquals("past", st(old)["phase"])
        assertEquals("partial", st(old)["primary"])
        // an open order keeps it active
        assertEquals("active", st(old, Facts(orders = listOf(order("sent", at = now - 40 * day))))["phase"])
        // a recent purchase too
        val recent = listOf(RxDispensing("ph", "লাইফ ফার্মেসি", now - 20 * day, listOf(0 to 2)))
        assertEquals("active", st(rx(dispensed = recent, createdAt = now - 50 * day))["phase"])
        // tests never sent: stale after 30 days from the course end
        assertEquals("active", st(rx(meds = emptyList(), tests = listOf("CBC"), createdAt = now - 29 * day))["phase"])
        assertEquals("past", st(rx(meds = emptyList(), tests = listOf("CBC"), createdAt = now - 32 * day))["phase"])
    }

    @Test
    fun `timeline - written, sent, accepted, bought, lab, report, reviewed, cancelled, follow-up last`() {
        val t0 = now - 5 * day
        val r = rx(
            tests = listOf("CBC"), createdAt = t0, status = "cancelled", followUp = today + 2 * day,
            dispensed = listOf(RxDispensing("ph", "লাইফ ফার্মেসি", t0 + 2 * day, listOf(0 to 20, 1 to 20))),
        )
        val f = Facts(
            cancelledAt = t0 + 4 * day,
            orders = listOf(Order("done", "online", "লাইফ ফার্মেসি", t0 + day, t0 + 2 * day), Order("cancelled", "shop", "অন্য দোকান", t0 + 3600_000, t0 + 7200_000)),
            labs = listOf(Lab("reported", "পপুলার", t0 + 2 * day, t0 + 3 * day, t0 + 3 * day)),
            reviews = listOf(Review("done", t0 + 3 * day + 3600_000)),
        )
        val tl = RxState.compute(r, f, now, withTimeline = true)["timeline"] as List<*>
        @Suppress("UNCHECKED_CAST")
        val kinds = (tl as List<Map<String, Any?>>).map { it["kind"] }
        assertEquals(
            listOf("written", "pharmacy_sent", "pharmacy_cancelled", "pharmacy_sent", "bought", "lab_sent", "lab_reported", "reviewed", "cancelled"),
            kinds,
        )
        assertEquals(2, tl[4].let { (it as Map<*, *>)["count"] })
        assertEquals("অন্য দোকান", (tl[1] as Map<*, *>)["name"])
        // not cancelled: the follow-up ahead comes last
        val tl2 = RxState.compute(r.copy(status = "active"), f.copy(cancelledAt = null), now, withTimeline = true)["timeline"] as List<*>
        assertEquals(mapOf("kind" to "follow_up", "at" to today + 2 * day, "ahead" to true), tl2.last())
        // the list has no timeline
        assertNull(RxState.compute(r, f, now)["timeline"])
    }
}
