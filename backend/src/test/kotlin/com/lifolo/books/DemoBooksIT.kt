package com.lifolo.books

import com.lifolo.admin.kernel.DemoSeedContext
import com.lifolo.admin.kernel.DemoUser
import com.lifolo.pharmacy.P4Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/** the demo part of server/test/shopbooks.test.js ("ডেমো লাইফ ফার্মেসি"-র হিসাব) with a stand-in seed context */
class DemoBooksIT : P4Test() {
    @Autowired
    lateinit var part: DemoBooks

    private val ph = "demo-pharmacy"

    /** services.js: the 24 stock names of the demo pharmacy */
    private val stockNames = listOf(
        "Napa", "Ace", "Seclo", "Losectil", "Maxpro", "Sergel", "Pantonix", "Fexo", "Alatrol", "Tofen", "Montene", "Monas",
        "Ceevit", "Calbo", "Xinc", "Comet", "Rosuva", "Ecosprin", "Bislol", "Amdocal", "Azithrocin", "Cef-3", "Flagyl", "Orsaline-N",
    )

    private fun ctx(now: Long) = object : DemoSeedContext {
        override val nowMs = now
        override fun user(key: String) = DemoUser("demo-$key", "$key@lifolo-demo.test", key)
        override fun extraUser(key: String, profile: Map<String, Any?>): String = throw UnsupportedOperationException()
        override fun track(table: String, key: Map<String, Any?>) = throw UnsupportedOperationException()
    }

    @Test
    fun `demo books - products, purchases, sales and the report; the 24 stock names as before`() {
        shop(ph, mapOf("name" to "ডেমো লাইফ ফার্মেসি", "district" to GAZIPUR, "upazila_id" to 320, "open24" to true))
        ok(ph, "stock_set", mapOf("add" to stockNames))
        assertEquals(3, part.step)
        part.seed(ctx(System.currentTimeMillis()))

        val items = ok(ph, "books_items")["items"]
        assertEquals(json(mapOf("sales" to 7, "net" to 1959, "profit" to 395.15, "writeOff" to 0, "low" to 5, "expiring" to 2, "priced" to true)), ok(ph, "books_today"))
        assertEquals(DemoBooks.ITEMS.size, items.size())
        assertEquals(24, items.count { it["inStock"].booleanValue() })
        assertEquals(listOf("Nexum 20", "Thyrox 50"), items.filter { !it["inStock"].booleanValue() }.map { it["name"].textValue() }.sorted())
        // starting quantities never negative (final − bought + sold)
        val sold = jdbc.queryForList("SELECT item_id, sum(qty) AS q FROM shop_sale_lines WHERE shop_uid = ? GROUP BY item_id", ph)
            .associate { it["item_id"] as String to (it["q"] as java.math.BigDecimal).toDouble() }
        val bought = jdbc.queryForList("SELECT item_id, sum(qty) AS q FROM shop_purchase_lines WHERE shop_uid = ? GROUP BY item_id", ph)
            .associate { it["item_id"] as String to (it["q"] as java.math.BigDecimal).toDouble() }
        for (it in items) {
            val id = it["id"].textValue()
            assertTrue(it["qty"].doubleValue() - (bought[id] ?: 0.0) + (sold[id] ?: 0.0) >= 0, it["name"].textValue())
        }

        val t30 = ok(ph, "books_report", mapOf("period" to "30d"))
        val tot = t30["totals"]
        assertTrue(tot["sales"].intValue() > 250 && tot["net"].doubleValue() > 10000, tot.toString())
        assertTrue(tot["profit"].doubleValue() > 0 && tot["writeOff"].doubleValue() > 0 && tot["bought"].doubleValue() > 0)
        assertTrue(t30["bySource"]["rx"]["n"].intValue() > 0 && t30["bySource"]["online"]["n"].intValue() > 0)
        assertTrue(ok(ph, "books_report", mapOf("period" to "today"))["totals"]["sales"].intValue() >= 7)
        assertEquals(listOf("Flagyl"), t30["belowCost"].map { it["name"].textValue() })
        assertEquals(listOf("Nexum 20", "Thyrox 50", "Cef-3", "Rosuva", "Montene"), t30["low"].map { it["name"].textValue() })
        assertEquals(listOf("Ceevit", "Azithrocin"), t30["expiring"].map { it["name"].textValue() })
        assertEquals(listOf("Tofen", "Xinc"), t30["noSale"].map { it["name"].textValue() }.sorted())
        // the day aggregates match the sales
        val days = t30["daily"].map { it["day"].textValue() }.toSet()
        val sum = ok(ph, "books_sales", mapOf("period" to "30d"))["items"].filter { it["day"].textValue() in days }.sumOf { it["totals"]["net"].doubleValue() }
        assertEquals(Math.round(sum * 100) / 100.0, tot["net"].doubleValue())
        // patient prices for the estimate, with the MRP of discounted products
        assertEquals(true, row("SELECT mrp_all FROM pharmacy_stock WHERE pharmacy_uid = ?", ph)["mrp_all"])
        assertEquals(6.3, (row("SELECT price FROM pharmacy_stock_items WHERE pharmacy_uid = ? AND key = 'seclo'", ph)["price"] as java.math.BigDecimal).toDouble())
        assertEquals(7.0, (row("SELECT mrp FROM pharmacy_stock_items WHERE pharmacy_uid = ? AND key = 'seclo'", ph)["mrp"] as java.math.BigDecimal).toDouble())
        // a demo sale keeps only the prescription code
        val rxSale = ok(ph, "books_sales", mapOf("period" to "7d"))["items"].first { it["source"].textValue() != "counter" }
        assertEquals(listOf("at", "code"), rxSale["rx"].fieldNames().asSequence().toList().sorted())
    }

    @Test
    fun `mulberry32 gives the Node numbers`() {
        val r = DemoBooks.rng(1501)
        val first = List(3) { r() }
        assertTrue(first.all { it in 0.0..1.0 } && first.toSet().size == 3)
        // JS: let a = 1 >>> 0 … first value of mulberry32(1)
        assertEquals(0.6270739405881613, DemoBooks.rng(1)(), 1e-15)
    }
}
