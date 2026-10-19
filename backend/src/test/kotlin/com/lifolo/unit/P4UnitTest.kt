package com.lifolo.unit

import com.lifolo.books.BooksMath
import com.lifolo.books.Disc
import com.lifolo.books.ShopItem
import com.lifolo.contracts.RxDispensing
import com.lifolo.contracts.RxMedicine
import com.lifolo.pharmacy.PharmacyService
import com.lifolo.pharmacy.RxMath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/** pure P4 helpers (rxnet.js medicine arithmetic, pharmacyestimate.test.js, shopbooks.js numbers) */
class P4UnitTest {
    private fun med(name: String, form: String = "Tablet", dose: String = "", days: Int = 0) = RxMedicine(0, name, form, "", dose, "", days, "")

    @Test
    fun `doses per day, countable forms, quantity needed`() {
        assertEquals(2.0, RxMath.perDay("1+0+1"))
        assertEquals(1.0, RxMath.perDay("½+0+½"))
        assertEquals(3.0, RxMath.perDay(" 1 + 1 + 1 "))
        assertEquals(0.0, RxMath.perDay("1"))
        assertEquals(0.0, RxMath.perDay("1+x"))
        assertEquals(0.0, RxMath.perDay("1+1+1+1+1"))
        assertTrue(RxMath.countable(""))
        assertTrue(RxMath.countable("Capsule"))
        assertTrue(RxMath.countable("tab."))
        assertFalse(RxMath.countable("Syrup"))
        assertEquals(60, RxMath.needQty(med("Comet", dose = "1+0+1", days = 30)))
        assertNull(RxMath.needQty(med("Tusca", form = "Syrup", dose = "1+0+1", days = 30)))
        assertNull(RxMath.needQty(med("Napa")))
        assertEquals(2, RxMath.needQty(med("Half", dose = "½+0+0", days = 3)))
    }

    @Test
    fun `medicine status - bought, left, done`() {
        val meds = listOf(med("Comet", dose = "1+0+1", days = 5), med("Napa"), med("Tusca", form = "Syrup", days = 7))
        val st = RxMath.medStatus(meds, listOf(RxDispensing("s", "দোকান", 1, listOf(0 to 4, 2 to 1)), RxDispensing("s", "দোকান", 2, listOf(0 to 6))))
        assertEquals(listOf(10, null, null), st.map { it.need })
        assertEquals(listOf(10, 0, 1), st.map { it.got })
        assertEquals(listOf(0, null, null), st.map { it.left })
        assertEquals(listOf(true, false, true), st.map { it.done })
    }

    @Test
    fun `estimate - discounts, partly priced, unknown quantity, no prices (pharmacyestimate)`() {
        fun w(name: String, left: Int?, countable: Boolean = true) = RxMath.Wanted(name, RxMath.key(name), left, countable)
        val keys = setOf("comet 500", "napa", "tusca", "seclo 20")
        val prices = mapOf("comet 500" to 7.2, "napa" to 1.2, "tusca" to 85.5)
        val wanted = listOf(w("Comet 500", 10), w("Napa", null), w("Tusca", null, false), w("Seclo 20", 14), w("Zimax", 3))
        assertEquals(mapOf("total" to 158L, "priced" to 2, "of" to 5, "mrp" to null, "discount" to null), RxMath.pharmacyEstimate(wanted, keys, prices))
        assertEquals(mapOf("total" to 107L, "priced" to 2, "of" to 2, "mrp" to null, "discount" to null), RxMath.pharmacyEstimate(listOf(w("Comet 500", 3), w("Tusca", null, false)), keys, prices))
        assertEquals(
            mapOf("total" to 107L, "priced" to 2, "of" to 2, "mrp" to 110L, "discount" to 3L),
            RxMath.pharmacyEstimate(listOf(w("Comet 500", 3), w("Tusca", null, false)), keys, prices, mapOf("comet 500" to 8.0)),
        )
        assertEquals(mapOf("total" to 86L, "priced" to 1, "of" to 1, "mrp" to 86L, "discount" to 0L), RxMath.pharmacyEstimate(listOf(w("Tusca", null, false)), keys, prices, mapOf("tusca" to 50.0)))
        assertNull(RxMath.pharmacyEstimate(listOf(w("Zimax", 3)), keys, mapOf("zimax" to 30.0)))
        assertNull(RxMath.pharmacyEstimate(wanted, keys, emptyMap()))
        assertNull(RxMath.pharmacyEstimate(wanted, keys, null))
        assertNull(RxMath.pharmacyEstimate(listOf(w("constructor", 2), w("toString", 1)), setOf("constructor", "tostring"), emptyMap()))
    }

    @Test
    fun `rx card - a "discount above X" not reached is paid at MRP`() {
        val m = mapOf("keepsStock" to true, "have" to 1, "total" to 4, "missing" to listOf("Napa"), "estimate" to mapOf("total" to 70L, "priced" to 1, "of" to 4, "mrp" to 80L, "discount" to 10L))
        val card = PharmacyService.rxCard(mapOf("discountMin" to 500), m)
        assertEquals(listOf(80L, 80L, 0, true), listOf(card["payable"], card["mrp"], card["discount"], card["belowMin"]))
        val plain = PharmacyService.rxCard(mapOf("discountMin" to 0), m)
        assertEquals(listOf(70L, 80L, 10L, false), listOf(plain["payable"], plain["mrp"], plain["discount"], plain["belowMin"]))
        assertEquals(listOf("behaviour", "price", "delivery"), PharmacyService.aspectsFor("online"))
        assertEquals(listOf("behaviour", "price"), PharmacyService.aspectsFor("shop"))
        assertEquals(listOf("behaviour"), PharmacyService.aspectsFor(null))
    }

    @Test
    fun `refill reminder - 2 days before the bought medicines run out, tomorrow at the earliest, not for a whole course`() {
        val day = RxMath.DAY_MS
        val now = OffsetDateTime.parse("2026-10-19T11:00:00+06:00").toInstant().toEpochMilli()
        val todayUtc = OffsetDateTime.parse("2026-10-19T00:00:00Z").toInstant().toEpochMilli()
        val meds = listOf(med("Comet", dose = "1+0+1", days = 30), med("Syrup", form = "Syrup", dose = "1+0+1", days = 30))
        // 20 of 60 → 10 days → reminder on day 8
        assertEquals(todayUtc + 8 * day, RxMath.refillDate(meds, listOf(RxDispensing("s", "", now, listOf(0 to 20))), now))
        // 2 pieces → 1 day → tomorrow
        assertEquals(todayUtc + day, RxMath.refillDate(meds, listOf(RxDispensing("s", "", now, listOf(0 to 2))), now))
        // the whole course → none
        assertNull(RxMath.refillDate(meds, listOf(RxDispensing("s", "", now, listOf(0 to 60, 1 to 1))), now))
    }

    @Test
    fun `segments and the missed-list key`() {
        assertEquals("new", RxMath.pharmSeg("sent"))
        assertEquals("closed", RxMath.pharmSeg("withdrawn"))
        assertTrue(RxMath.missKey("Napa  500").matches(Regex("^k_[0-9a-f]{16}$")))
        assertEquals(RxMath.missKey("napa 500"), RxMath.missKey(" Napa  500 "))
        assertEquals(1250.0, RxMath.jsNumber("1250"))
        assertEquals(16.0, RxMath.jsNumber("0x10"))
        assertTrue(RxMath.jsNumber("1d").isNaN())
        assertEquals(0.0, RxMath.jsNumber(" "))
    }

    @Test
    fun `patient prices - discount taken off, strip and box prices not shown, MRP only with a discount`() {
        fun item(price: Double?, disc: Disc? = null, unit: String = "piece") = ShopItem("i", "X", "x", unit = unit, price = price, disc = disc)
        assertEquals(7.2, BooksMath.patientPriceOf(item(8.0, Disc("pct", 10.0))))
        assertEquals(38.0, BooksMath.patientPriceOf(item(40.0, Disc("amt", 2.0), "")))
        assertNull(BooksMath.patientPriceOf(item(120.0, unit = "strip")))
        assertNull(BooksMath.patientPriceOf(item(null)))
        assertNull(BooksMath.patientPriceOf(item(5.0, Disc("pct", 100.0))))
        assertEquals(8.0, BooksMath.patientMrpOf(item(8.0, Disc("pct", 10.0))))
        assertNull(BooksMath.patientMrpOf(item(8.0)))
    }

    @Test
    fun `shop books numbers - r2, ids, expiry end, periods, day keys`() {
        assertEquals(1.01, BooksMath.r2(1.005))
        assertEquals(-1.0, BooksMath.r2(-1.0))
        assertEquals("k" + RxMath.sha1Hex("napa").take(16), BooksMath.idOf("  Napa "))
        assertEquals(OffsetDateTime.parse("2027-04-01T00:00:00+06:00").toInstant().toEpochMilli() - 1, BooksMath.expiryEnd("2027-03"))
        val pd = BooksMath.periodOf("month", OffsetDateTime.parse("2026-10-31T23:30:00+06:00").toInstant().toEpochMilli())
        assertEquals(31, pd.days.size)
        assertEquals("20261001", pd.days[0])
        assertEquals("20261031", pd.days[30])
        assertEquals(OffsetDateTime.parse("2026-10-01T00:00:00+06:00").toInstant().toEpochMilli(), pd.start)
        assertEquals(listOf("20261019"), BooksMath.periodOf("today", OffsetDateTime.parse("2026-10-19T00:10:00+06:00").toInstant().toEpochMilli()).days)
        assertEquals(7, BooksMath.periodOf("whatever", 0).days.size)
    }
}
