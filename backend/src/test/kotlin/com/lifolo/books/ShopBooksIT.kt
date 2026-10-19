package com.lifolo.books

import com.fasterxml.jackson.databind.JsonNode
import com.lifolo.pharmacy.P4Test
import com.lifolo.pharmacy.PharmacyDeletionStep
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.time.ZoneOffset

/** server/test/shopbooks.test.js (+ the stock-price parts of pharmacyestimate.test.js, privacy-gaps sale names) */
class ShopBooksIT : P4Test() {
    @Autowired
    lateinit var deletion: PharmacyDeletionStep

    private fun bookShop(id: String, names: List<String>? = null) {
        shop(id)
        if (names != null) {
            jdbc.update("INSERT INTO pharmacy_stock (pharmacy_uid, updated_at) VALUES (?, to_timestamp(0))", id)
            jdbc.batchUpdate(
                "INSERT INTO pharmacy_stock_items (pharmacy_uid, key, name, in_stock) VALUES (?, ?, ?, TRUE)",
                names.map { n -> arrayOf<Any?>(id, BooksMath.key(n), n) },
            )
        }
    }

    /** without the approved-pharmacy check of stock_get */
    private fun names(id: String): List<String> =
        jdbc.queryForList("SELECT name FROM pharmacy_stock_items WHERE pharmacy_uid = ? AND in_stock", String::class.java, id)

    private fun prices(id: String): Map<String, Double> =
        jdbc.queryForList("SELECT key, price FROM pharmacy_stock_items WHERE pharmacy_uid = ? AND price IS NOT NULL", id)
            .associate { it["key"] as String to (it["price"] as java.math.BigDecimal).toDouble() }

    private fun keys(id: String): List<String> =
        jdbc.queryForList("SELECT key FROM pharmacy_stock_items WHERE pharmacy_uid = ? AND in_stock ORDER BY key", String::class.java, id)

    private fun item(id: String, name: String): JsonNode? = ok(id, "books_items")["items"].firstOrNull { it["name"].textValue() == name }

    private fun d(v: Any?): Double = (v as? Number)?.toDouble() ?: 0.0

    /** today's day aggregate */
    private fun dayDoc(id: String): Map<String, Any?> {
        val day = java.sql.Date.valueOf(Instant.ofEpochMilli(System.currentTimeMillis() + 6 * HOUR).atZone(ZoneOffset.UTC).toLocalDate())
        val h = row("SELECT * FROM shop_days WHERE shop_uid = ? AND day = ?", id, day)
        if (h.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, Any?>()
        for (k in listOf("sales", "purchases", "adjusts", "gross", "discount", "net", "nc", "cost", "profit", "bought", "write_off", "woq")) out[k] = d(h[k])
        out["src"] = jdbc.queryForList("SELECT source, n, net FROM shop_day_sources WHERE shop_uid = ? AND day = ?", id, day)
            .associate { it["source"] as String to listOf(d(it["n"]), d(it["net"])) }
        out["items"] = jdbc.queryForList("SELECT * FROM shop_day_items WHERE shop_uid = ? AND day = ?", id, day)
            .associate { it["item_key"] as String to mapOf("neg" to d(it["neg"]), "q" to d(it["q"])) }
        return out
    }

    private fun monthAhead(days: Int): String =
        Instant.ofEpochMilli(System.currentTimeMillis() + days * DAY + 6 * HOUR).toString().substring(0, 7)

    @Test
    fun `products - old stock names become products, only the name is required, the stock list follows`() {
        val a = "shopItemsA1"
        bookShop(a, listOf("Napa", "Seclo"))
        var list = ok(a, "books_items")["items"]
        assertEquals(listOf(listOf("Napa", null, true), listOf("Seclo", null, true)), list.map { listOf(it["name"].textValue(), it["qty"].takeIf { q -> !q.isNull }, it["inStock"].booleanValue()) })
        assertTrue(row("SELECT 1 AS x FROM shop_items WHERE shop_uid = ? AND id = ?", a, BooksMath.idOf("Napa")).isNotEmpty())
        list = ok(a, "books_items")["items"]
        assertEquals(2, list.size())
        assertEquals(json(BooksMath.UNITS), ok(a, "books_items")["units"])

        ok(a, "books_item_save", mapOf("name" to "  Fexo   120 ", "generic" to "Fexofenadine", "form" to "Tablet"))
        assertTrue(names(a).contains("Fexo 120"))
        val c = ok(a, "books_item_save", mapOf("name" to "Comet 500", "qty" to 0, "cost" to 5, "price" to 8, "disc" to mapOf("type" to "pct", "v" to 10), "lowAt" to 5, "unit" to "piece"))["item"]
        assertFalse(c["inStock"].booleanValue())
        assertEquals(7.2, c["salePrice"].doubleValue())
        assertFalse(names(a).contains("Comet 500"))
        val c2 = ok(a, "books_item_save", mapOf("id" to c["id"].textValue(), "name" to "Comet 500", "qty" to 10, "cost" to 5, "price" to 8, "disc" to mapOf("type" to "pct", "v" to 10), "lowAt" to 5, "unit" to "piece"))["item"]
        assertTrue(c2["inStock"].booleanValue())
        assertEquals(10, c2["qty"].intValue())
        assertTrue(names(a).contains("Comet 500"))
        val adj = ok(a, "books_adjusts")["items"]
        assertEquals(listOf(listOf("count", "Comet 500", "0", "10")), adj.map { listOf(it["kind"].textValue(), it["name"].textValue(), it["before"].asText(), it["after"].asText()) })

        assertEquals(409, call(a, "books_item_save", mapOf("name" to "comet  500")).status)
        assertEquals(400, call(a, "books_item_save", mapOf("name" to "X", "price" to -1)).status)
        assertEquals(400, call(a, "books_item_save", mapOf("name" to "X", "disc" to mapOf("type" to "pct", "v" to 120))).status)
        assertEquals(400, call(a, "books_item_save", mapOf("name" to "X", "price" to 5, "disc" to mapOf("type" to "amt", "v" to 6))).status)
        assertEquals(400, call(a, "books_item_save", mapOf("name" to "X", "expiry" to "2027-13")).status)
        assertEquals(400, call(a, "books_item_save", mapOf("name" to "")).status)
        assertEquals("দাম দেখে নিন", call(a, "books_item_save", mapOf("name" to "X", "price" to "abc")).error)
        // Number(String(v).replace(/,/g, '')): "1,250" is 1250
        assertEquals(1250.0, ok(a, "books_item_save", mapOf("name" to "Y", "price" to "1,250"))["item"]["price"].doubleValue())

        val fexo = item(a, "Fexo 120")!!
        ok(a, "books_item_stock", mapOf("id" to fexo["id"].textValue(), "on" to false))
        assertFalse(names(a).contains("Fexo 120"))
        assertFalse(item(a, "Fexo 120")!!["inStock"].booleanValue())
        ok(a, "books_item_stock", mapOf("id" to fexo["id"].textValue(), "on" to true))
        assertTrue(names(a).contains("Fexo 120"))

        val napa = item(a, "Napa")!!
        ok(a, "books_item_save", mapOf("id" to napa["id"].textValue(), "name" to "Napa 500", "price" to 1.2))
        assertEquals(listOf("Napa 500"), names(a).filter { it.startsWith("Napa") })
        ok(a, "stock_set", mapOf("remove" to listOf("Seclo")))
        assertFalse(item(a, "Seclo")!!["inStock"].booleanValue())
        ok(a, "stock_set", mapOf("add" to listOf("Ace")))
        assertTrue(item(a, "Ace") != null)

        ok(a, "books_item_delete", mapOf("id" to item(a, "Napa 500")!!["id"].textValue()))
        assertFalse(names(a).contains("Napa 500"))
        assertNull(item(a, "Napa 500"))
        assertEquals(names(a).map { BooksMath.key(it) }.sorted(), keys(a))
    }

    @Test
    fun `purchases - quantity up, average cost, nearest expiry, new names become products - deleting gives the quantity back`() {
        val a = "shopBuyA001"
        bookShop(a, listOf("Seclo"))
        ok(a, "books_items")
        val seclo = item(a, "Seclo")!!["id"].textValue()
        ok(a, "books_purchase_add", mapOf("supplier" to "রেনাটা ডিপো", "lines" to listOf(mapOf("itemId" to seclo, "qty" to 100, "unitCost" to 4, "expiry" to "2027-06"))))
        var s = item(a, "Seclo")!!
        assertEquals(listOf("100", "4", "2027-06"), listOf(s["qty"].asText(), s["cost"].asText(), s["expiry"].textValue()))
        val p2 = ok(
            a, "books_purchase_add",
            mapOf("lines" to listOf(mapOf("itemId" to seclo, "qty" to 50, "unitCost" to 5.5, "expiry" to "2027-03", "price" to 7), mapOf("name" to "Ace", "qty" to 20, "unitCost" to 1.2, "price" to 2))),
        )
        s = item(a, "Seclo")!!
        assertEquals(listOf(150.0, 4.5, 7.0), listOf(s["qty"].doubleValue(), s["cost"].doubleValue(), s["price"].doubleValue()))
        assertEquals("2027-03", s["expiry"].textValue())
        val ace = item(a, "Ace")!!
        assertEquals(listOf(20.0, 1.2, 2.0), listOf(ace["qty"].doubleValue(), ace["cost"].doubleValue(), ace["price"].doubleValue()))
        assertTrue(ace["inStock"].booleanValue())
        assertTrue(names(a).contains("Ace"))
        assertEquals(299.0, p2["purchase"]["total"].doubleValue())
        assertFalse(p2["purchase"].has("undo"))
        var dd = dayDoc(a)
        assertEquals(listOf(2.0, 699.0), listOf(dd["purchases"], dd["bought"]))
        val list = ok(a, "books_purchases")["items"]
        assertEquals(2, list.size())
        assertEquals(400.0, list.first { it["supplier"].textValue() == "রেনাটা ডিপো" }["total"].doubleValue())
        assertFalse(list[0].has("undo"))

        assertEquals(400, call(a, "books_purchase_add", mapOf("lines" to listOf(mapOf("itemId" to seclo, "qty" to 0)))).status)
        assertEquals(400, call(a, "books_purchase_add", mapOf("at" to System.currentTimeMillis() + 5 * DAY, "lines" to listOf(mapOf("itemId" to seclo, "qty" to 1)))).status)
        assertEquals(400, call(a, "books_purchase_add", mapOf("lines" to (0..100).map { mapOf("name" to "M$it", "qty" to 1) })).status)

        ok(a, "books_purchase_delete", mapOf("id" to p2["id"].textValue()))
        assertEquals(100.0, item(a, "Seclo")!!["qty"].doubleValue())
        assertEquals(0.0, item(a, "Ace")!!["qty"].doubleValue())
        assertFalse(names(a).contains("Ace"))
        dd = dayDoc(a)
        assertEquals(listOf(1.0, 400.0), listOf(dd["purchases"], dd["bought"]))
        assertEquals(404, call(a, "books_purchase_delete", mapOf("id" to p2["id"].textValue())).status)
    }

    @Test
    fun `counter sales - product discounts, bill discount, profit, below cost, unpriced - deleting reverses everything`() {
        val a = "shopSaleA01"
        bookShop(a)
        val comet = ok(a, "books_item_save", mapOf("name" to "Comet 500", "qty" to 10, "cost" to 5, "price" to 8, "disc" to mapOf("type" to "pct", "v" to 10)))["item"]["id"].textValue()
        val seclo = ok(a, "books_item_save", mapOf("name" to "Seclo 20", "qty" to 100, "cost" to 4.5, "price" to 7, "disc" to mapOf("type" to "amt", "v" to 0.5)))["item"]["id"].textValue()
        val ace = ok(a, "books_item_save", mapOf("name" to "Ace", "qty" to 20, "cost" to 1.2, "price" to 2))["item"]["id"].textValue()
        val fexo = ok(a, "books_item_save", mapOf("name" to "Fexo 120"))["item"]["id"].textValue()

        val s1 = ok(a, "books_sale_add", mapOf("lines" to listOf(mapOf("itemId" to comet, "qty" to 4), mapOf("itemId" to seclo, "qty" to 10)), "billDisc" to mapOf("type" to "pct", "v" to 5)))["sale"]
        assertEquals(
            json(mapOf("gross" to 102, "discount" to 12.89, "billDiscount" to 4.69, "net" to 89.11, "cost" to 65, "profit" to 24.11, "netCosted" to 89.11, "unpriced" to 0)),
            s1["totals"],
        )
        assertEquals(
            listOf(listOf("Comet 500", 4.0, 8.0, 0.8, 27.36, 20.0, 7.36), listOf("Seclo 20", 10.0, 7.0, 0.5, 61.75, 45.0, 16.75)),
            s1["lines"].map { l -> listOf(l["name"].textValue()) + listOf("qty", "unitPrice", "unitDisc", "net", "cost", "profit").map { l[it].doubleValue() } },
        )
        assertEquals("counter", s1["source"].textValue())
        val s2 = ok(a, "books_sale_add", mapOf("lines" to listOf(mapOf("itemId" to comet, "qty" to 2, "unitPrice" to 8, "unitDisc" to 0)), "billDisc" to mapOf("type" to "amt", "v" to 1)))["sale"]["totals"]
        assertEquals(listOf(16.0, 1.0, 15.0, 10.0, 5.0), listOf("gross", "discount", "net", "cost", "profit").map { s2[it].doubleValue() })
        assertEquals(-1.0, ok(a, "books_sale_add", mapOf("lines" to listOf(mapOf("itemId" to ace, "qty" to 5, "unitPrice" to 1))))["sale"]["totals"]["profit"].doubleValue())
        val u = ok(a, "books_sale_add", mapOf("lines" to listOf(mapOf("itemId" to fexo, "qty" to 1))))["sale"]
        assertEquals(listOf("0", "1"), listOf(u["totals"]["net"].asText(), u["totals"]["unpriced"].asText()))
        assertTrue(u["lines"][0]["profit"].isNull)

        assertEquals(4.0, item(a, "Comet 500")!!["qty"].doubleValue())
        assertEquals(90.0, item(a, "Seclo 20")!!["qty"].doubleValue())
        assertTrue(item(a, "Comet 500")!!["lastSaleAt"].longValue() > 0)

        val dd = dayDoc(a)
        assertEquals(listOf(4.0, 109.11, 13.89, 81.0, 28.11), listOf(dd["sales"], dd["net"], dd["discount"], dd["cost"], dd["profit"]))
        @Suppress("UNCHECKED_CAST")
        assertEquals(listOf(4.0, 109.11), (dd["src"] as Map<String, Any?>)["counter"])
        @Suppress("UNCHECKED_CAST")
        assertEquals(-1.0, ((dd["items"] as Map<String, Map<String, Double>>)[ace]!!)["neg"])

        val over = ok(a, "books_sale_add", mapOf("lines" to listOf(mapOf("itemId" to comet, "qty" to 9))))
        assertEquals(0.0, item(a, "Comet 500")!!["qty"].doubleValue())
        assertFalse(names(a).contains("Comet 500"))
        val sales = ok(a, "books_sales", mapOf("period" to "today"))["items"]
        val last = sales.first { it["id"] == over["id"] }
        assertEquals(ids(ok(a, "books_sales", mapOf("period" to "today"))), sales.map { it["id"].textValue() })
        assertEquals(9.0, last["lines"][0]["qty"].doubleValue())
        assertEquals(4.0, last["lines"][0]["dec"].doubleValue())
        ok(a, "books_sale_delete", mapOf("id" to last["id"].textValue()))
        assertEquals(4.0, item(a, "Comet 500")!!["qty"].doubleValue())
        assertTrue(names(a).contains("Comet 500"))
        val d2 = dayDoc(a)
        assertEquals(listOf(4.0, 109.11, 28.11), listOf(d2["sales"], d2["net"], d2["profit"]))

        assertEquals(400, call(a, "books_sale_add", mapOf("lines" to emptyList<Any>())).status)
        assertEquals(400, call(a, "books_sale_add", mapOf("lines" to listOf(mapOf("itemId" to comet, "qty" to -2)))).status)
        assertEquals(404, call(a, "books_sale_add", mapOf("lines" to listOf(mapOf("itemId" to "nope000001", "qty" to 1)))).status)
        assertEquals(400, call(a, "books_sale_add", mapOf("lines" to listOf(mapOf("itemId" to comet, "qty" to 1)), "billDisc" to mapOf("type" to "x", "v" to 1))).status)
        assertEquals(404, call(a, "books_sale_delete", mapOf("id" to "../x")).status)
    }

    @Test
    fun `prescription - rx_dispense as before - then into the books once, quantity from the dispensing - online order source`() {
        val a = "shopRxA0001"
        bookShop(a)
        ok(a, "books_item_save", mapOf("name" to "Comet 500", "qty" to 4, "cost" to 5, "price" to 8, "disc" to mapOf("type" to "pct", "v" to 10)))
        ok(a, "books_item_save", mapOf("name" to "Seclo", "qty" to 30, "cost" to 4.5, "price" to 7))
        val med = { n: String -> Med(n, dose = "1+0+1", days = 30, timing = "after") }
        rx("rxBooks01", "H4QW7ZK2MP", null, listOf(med("Comet"), med("Seclo"), med("Napa")), patientName = "রহিমা")

        val before = ok(a, "books_sales", mapOf("period" to "today"))["items"].size()
        val disp = ok(a, "rx_dispense", mapOf("code" to "H4QW7ZK2MP", "items" to listOf(mapOf("i" to 0, "qty" to 6), mapOf("i" to 1, "qty" to 10), mapOf("i" to 2, "qty" to 2))))
        assertEquals(1, disp["item"]["dispensed"].size())
        assertEquals(before, ok(a, "books_sales", mapOf("period" to "today"))["items"].size())
        assertEquals(30.0, item(a, "Seclo")!!["qty"].doubleValue())

        val pv = ok(a, "books_rx_preview", mapOf("code" to "H4QW7ZK2MP"))
        assertFalse(pv["recorded"].booleanValue())
        assertEquals("rx", pv["source"].textValue())
        assertEquals(
            listOf(listOf("Comet", "6", "Comet 500", "8", "0.8", "5"), listOf("Seclo", "10", "Seclo", "7", "0", "4.5"), listOf("Napa", "2", "", "null", "0", "null")),
            pv["lines"].map { l -> listOf("name", "qty", "itemName", "unitPrice", "unitDisc", "unitCost").map { l[it].asText() } },
        )
        val s = ok(
            a, "books_sale_add",
            mapOf(
                "source" to "rx", "rx" to mapOf("code" to "H4QW7ZK2MP", "at" to pv["at"].longValue()),
                "lines" to listOf(mapOf("i" to 0, "itemId" to pv["lines"][0]["itemId"].textValue()), mapOf("i" to 1, "unitPrice" to 6.5, "unitDisc" to 0), mapOf("i" to 2)),
            ),
        )["sale"]
        assertEquals("rx", s["source"].textValue())
        assertEquals(listOf(113.0, 4.8, 108.2, 75.0, 33.2, 1.0), listOf("gross", "discount", "net", "cost", "profit", "unpriced").map { s["totals"][it].doubleValue() })
        assertEquals(listOf("Comet 500", "Seclo", "Napa"), s["lines"].map { it["name"].textValue() })
        assertEquals("H4QW7ZK2MP", s["rx"]["code"].textValue())
        assertEquals(listOf("at", "code", "rxId"), s["rx"].fieldNames().asSequence().toList().sorted())
        assertEquals(0.0, item(a, "Comet 500")!!["qty"].doubleValue())
        assertEquals(20.0, item(a, "Seclo")!!["qty"].doubleValue())
        assertNull(item(a, "Napa"))
        assertEquals(listOf("true", "0", "null"), listOf(s["lines"][2]["noItem"].asText(), s["lines"][2]["dec"].asText(), s["lines"][2]["unitPrice"].asText()))
        assertEquals(409, call(a, "books_sale_add", mapOf("source" to "rx", "rx" to mapOf("code" to "H4QW7ZK2MP", "at" to pv["at"].longValue()), "lines" to emptyList<Any>())).status)
        assertTrue(ok(a, "books_rx_preview", mapOf("code" to "H4QW7ZK2MP"))["recorded"].booleanValue())
        assertEquals(18.0, s["lines"].sumOf { it["qty"].doubleValue() })

        rx("rxBooks02", "M2KD9VW4YA", null, listOf(med("Seclo")), patientName = "সুমাইয়া")
        person("somePatient")
        order("ordBooks2", mapOf("rx_id" to "rxBooks02", "code" to "M2KD9VW4YA", "pharmacy_uid" to a, "patient_uid" to "somePatient", "status" to "out", "mode" to "online", "created_at" to ts(System.currentTimeMillis() - HOUR)))
        ok(a, "rx_dispense", mapOf("code" to "M2KD9VW4YA", "items" to listOf(mapOf("i" to 0, "qty" to 4))))
        val pv2 = ok(a, "books_rx_preview", mapOf("code" to "M2KD9VW4YA"))
        assertEquals("online", pv2["source"].textValue())
        val o = ok(a, "books_sale_add", mapOf("source" to "rx", "rx" to mapOf("code" to "M2KD9VW4YA", "at" to pv2["at"].longValue())))["sale"]
        assertEquals(listOf("online", "28"), listOf(o["source"].textValue(), o["totals"]["net"].asText()))
        @Suppress("UNCHECKED_CAST")
        val src = dayDoc(a)["src"] as Map<String, List<Double>>
        assertEquals(listOf(1.0, 1.0, 28.0), listOf(src["rx"]!![0], src["online"]!![0], src["online"]!![1]))

        val x = "shopRxOther"
        bookShop(x)
        assertEquals(404, call(x, "books_rx_preview", mapOf("code" to "H4QW7ZK2MP")).status)
        assertEquals(404, call(x, "books_sale_add", mapOf("source" to "rx", "rx" to mapOf("code" to "H4QW7ZK2MP", "at" to pv["at"].longValue()))).status)
    }

    @Test
    fun `write-off is a loss at cost - a stock count is not in the P&L`() {
        val a = "shopAdjA001"
        bookShop(a)
        val s = ok(a, "books_item_save", mapOf("name" to "Seclo", "qty" to 30, "cost" to 4.5, "price" to 7))["item"]["id"].textValue()
        val w = ok(a, "books_adjust", mapOf("itemId" to s, "kind" to "writeoff", "qty" to 5, "reason" to "মেয়াদ শেষ"))["adjust"]
        assertEquals(listOf(30.0, 25.0, 22.5), listOf(w["before"].doubleValue(), w["after"].doubleValue(), w["loss"].doubleValue()))
        val c = ok(a, "books_adjust", mapOf("itemId" to s, "kind" to "count", "qty" to 22))["adjust"]
        assertEquals(listOf(25.0, 22.0, -3.0, 0.0), listOf(c["before"].doubleValue(), c["after"].doubleValue(), c["delta"].doubleValue(), c["loss"].doubleValue()))
        assertEquals(22.0, item(a, "Seclo")!!["qty"].doubleValue())
        val dd = dayDoc(a)
        assertEquals(listOf(22.5, 5.0, 2.0), listOf(dd["write_off"], dd["woq"], dd["adjusts"]))
        assertEquals(400, call(a, "books_adjust", mapOf("itemId" to s, "kind" to "writeoff", "qty" to 0)).status)
        assertEquals(404, call(a, "books_adjust", mapOf("itemId" to s, "kind" to "eat", "qty" to 1)).status)
        ok(a, "books_adjust", mapOf("itemId" to s, "kind" to "writeoff", "qty" to 22))
        assertFalse(names(a).contains("Seclo"))
        assertEquals(listOf("count", "writeoff", "writeoff"), ok(a, "books_adjusts")["items"].map { it["kind"].textValue() }.sorted())
    }

    @Test
    fun `report - sales, discounts, cost, profit, margin, losses, stock value, low, expiring, no sale`() {
        val a = "shopRepA001"
        bookShop(a)
        val now = System.currentTimeMillis()
        val comet = ok(a, "books_item_save", mapOf("name" to "Comet 500", "qty" to 10, "cost" to 5, "price" to 8, "disc" to mapOf("type" to "pct", "v" to 10), "lowAt" to 5))["item"]["id"].textValue()
        val seclo = ok(a, "books_item_save", mapOf("name" to "Seclo 20", "qty" to 100, "cost" to 4.5, "price" to 7, "disc" to mapOf("type" to "amt", "v" to 0.5), "expiry" to monthAhead(20)))["item"]["id"].textValue()
        val ace = ok(a, "books_item_save", mapOf("name" to "Ace", "qty" to 20, "cost" to 1.2, "price" to 2))["item"]["id"].textValue()
        val fexo = ok(a, "books_item_save", mapOf("name" to "Fexo 120", "qty" to 6))["item"]["id"].textValue()
        val old = ok(a, "books_item_save", mapOf("name" to "Tofen", "qty" to 8, "cost" to 3, "price" to 4))["item"]["id"].textValue()
        jdbc.update("UPDATE shop_items SET created_at = ? WHERE shop_uid = ? AND id = ?", ts(now - 60 * DAY), a, old)

        ok(a, "books_sale_add", mapOf("lines" to listOf(mapOf("itemId" to comet, "qty" to 4), mapOf("itemId" to seclo, "qty" to 10)), "billDisc" to mapOf("type" to "pct", "v" to 5)))
        ok(a, "books_sale_add", mapOf("lines" to listOf(mapOf("itemId" to comet, "qty" to 2, "unitPrice" to 8, "unitDisc" to 0)), "billDisc" to mapOf("type" to "amt", "v" to 1)))
        ok(a, "books_sale_add", mapOf("lines" to listOf(mapOf("itemId" to ace, "qty" to 5, "unitPrice" to 1))))
        ok(a, "books_sale_add", mapOf("lines" to listOf(mapOf("itemId" to fexo, "qty" to 2, "unitPrice" to 10))))
        ok(a, "books_adjust", mapOf("itemId" to seclo, "kind" to "writeoff", "qty" to 2))
        ok(a, "books_sale_add", mapOf("at" to now - 3 * DAY, "lines" to listOf(mapOf("itemId" to seclo, "qty" to 4, "unitPrice" to 7, "unitDisc" to 0))))

        val t = ok(a, "books_report", mapOf("period" to "today"))
        assertEquals(
            json(
                linkedMapOf(
                    "sales" to 4, "gross" to 143, "discount" to 13.89, "net" to 129.11, "cost" to 81, "profit" to 28.11, "margin" to 25.8,
                    "uncosted" to 20, "writeOff" to 9, "netProfit" to 19.11, "bought" to 0, "purchases" to 0,
                ),
            ),
            t["totals"],
        )
        assertEquals(1, t["daily"].size())
        assertEquals(listOf("Seclo 20 61.75", "Comet 500 42.36", "Fexo 120 20", "Ace 5"), t["topSales"].map { "${it["name"].textValue()} ${it["net"].asText()}" })
        assertEquals(listOf("Seclo 20 16.75", "Comet 500 12.36"), t["topProfit"].map { "${it["name"].textValue()} ${it["profit"].asText()}" })
        assertEquals(listOf("Ace 1 5"), t["belowCost"].map { "${it["name"].textValue()} ${it["loss"].asText()} ${it["lossQty"].asText()}" })
        assertEquals(listOf("4", "129.11", "0"), listOf(t["bySource"]["counter"]["n"].asText(), t["bySource"]["counter"]["net"].asText(), t["bySource"]["rx"]["n"].asText()))
        assertEquals(json(mapOf("items" to 5, "counted" to 5, "atCost" to 440, "atPrice" to 682, "noCost" to 1, "noPrice" to 1)), t["stock"])
        assertEquals(listOf("Comet 500 4"), t["low"].map { "${it["name"].textValue()} ${it["qty"].asText()}" })
        assertEquals(listOf("Seclo 20"), t["expiring"].map { it["name"].textValue() })
        assertTrue(t["expiring"][0]["expDays"].intValue() in 19..60)
        assertEquals(listOf("Tofen"), t["noSale"].map { it["name"].textValue() })

        assertEquals(
            json(mapOf("sales" to 4, "net" to 129.11, "profit" to 28.11, "writeOff" to 9, "low" to 1, "expiring" to 1, "priced" to true)),
            ok(a, "books_today"),
        )
        val w = ok(a, "books_report", mapOf("period" to "7d"))
        assertEquals(7, w["daily"].size())
        assertEquals(listOf("5", "157.11", "38.11", "29.11"), listOf("sales", "net", "profit", "netProfit").map { w["totals"][it].asText() })
        assertEquals(json(mapOf("day" to BooksMath.dayKey(now - 3 * DAY), "net" to 28, "profit" to 10, "sales" to 1)), w["daily"][3])
        assertEquals(30, ok(a, "books_report", mapOf("period" to "30d"))["daily"].size())
        val m = ok(a, "books_report", mapOf("period" to "month"))
        assertEquals(BooksMath.dayKey(now).substring(6).toInt(), m["daily"].size())
        assertEquals(BooksMath.dayKey(now), m["to"].textValue())

        val pd = BooksMath.periodOf("month", java.time.OffsetDateTime.parse("2026-10-31T23:30:00+06:00").toInstant().toEpochMilli())
        assertEquals(31, pd.days.size)
        assertEquals("20261001", pd.days[0])
        assertEquals("20261031", pd.days[30])
    }

    @Test
    fun `only the shop owner - not others, not guests, not without a pharmacy - other shops' rows are untouched`() {
        val a = "shopOwnA001"
        bookShop(a)
        val c = ok(a, "books_item_save", mapOf("name" to "Comet 500", "qty" to 10, "cost" to 5, "price" to 8))["item"]["id"].textValue()
        val sale = ok(a, "books_sale_add", mapOf("lines" to listOf(mapOf("itemId" to c, "qty" to 1))))
        val u = "plainUser01"
        person(u)
        for ((action, body) in listOf(
            "books_items" to emptyMap(), "books_item_save" to mapOf("name" to "X"), "books_sale_add" to mapOf("lines" to listOf(mapOf("itemId" to c, "qty" to 1))),
            "books_report" to emptyMap(), "books_purchase_add" to mapOf("lines" to listOf(mapOf("name" to "X", "qty" to 1))),
            "books_adjust" to mapOf("itemId" to c, "kind" to "writeoff", "qty" to 1),
        )) {
            assertEquals(403, call(u, action, body).status, action)
        }
        assertEquals("আগে আপনার ফার্মেসি যোগ করুন", call(u, "books_today").error)
        assertEquals(401, call(null, "books_items").status)
        assertEquals(403, call(u, "books_items", mapOf("asUid" to a)).status)
        val x = "shopOwnX001"
        bookShop(x)
        assertEquals(0, ok(x, "books_items")["items"].size())
        assertEquals(404, call(x, "books_sale_delete", mapOf("id" to sale["id"].textValue())).status)
        assertEquals(404, call(x, "books_adjust", mapOf("itemId" to c, "kind" to "writeoff", "qty" to 1)).status)
        assertEquals(200, call(x, "books_item_delete", mapOf("id" to c)).status)
        assertTrue(item(a, "Comet 500") != null)
        assertEquals(0, ok(x, "books_report", mapOf("period" to "today"))["totals"]["sales"].intValue())
    }

    @Test
    fun `review fixes - renamed product id, purchase undo, sale dec, newest first, list limit, write-off cap, bill discount cents`() {
        val a = "shopFixA001"
        bookShop(a, listOf("Napa"))
        ok(a, "books_items")
        val napa = item(a, "Napa")!!["id"].textValue()
        ok(a, "books_item_save", mapOf("id" to napa, "name" to "Napa 500", "price" to 1.2, "cost" to 0.8, "qty" to 40))
        ok(a, "stock_set", mapOf("add" to listOf("Napa")))
        val list = ok(a, "books_items")["items"]
        val n5 = list.first { it["name"].textValue() == "Napa 500" }
        assertEquals(listOf(napa, "1.2", "40"), listOf(n5["id"].textValue(), n5["price"].asText(), n5["qty"].asText()))
        assertEquals("Napa 500", row("SELECT name FROM shop_items WHERE shop_uid = ? AND id = ?", a, napa)["name"])
        assertEquals(1, list.count { it["id"].textValue() == napa })

        // 2. undo of a purchase on a product without quantity
        val fexo = ok(a, "books_item_save", mapOf("name" to "Fexo 120", "price" to 10))["item"]
        assertTrue(fexo["qty"].isNull)
        val fid = fexo["id"].textValue()
        val p = ok(a, "books_purchase_add", mapOf("lines" to listOf(mapOf("itemId" to fid, "qty" to 10, "unitCost" to 6, "price" to 12, "expiry" to "2027-05"))))
        var f = item(a, "Fexo 120")!!
        assertEquals(listOf("10", "6", "12", "2027-05"), listOf(f["qty"].asText(), f["cost"].asText(), f["price"].asText(), f["expiry"].textValue()))
        ok(a, "books_purchase_delete", mapOf("id" to p["id"].textValue()))
        f = item(a, "Fexo 120")!!
        assertEquals(listOf("null", "true", "null", "10", "null"), listOf(f["qty"].asText(), f["inStock"].asText(), f["cost"].asText(), f["price"].asText(), f["expiry"].asText()))
        ok(a, "books_item_stock", mapOf("id" to fid, "on" to false))
        val p2 = ok(a, "books_purchase_add", mapOf("lines" to listOf(mapOf("itemId" to fid, "qty" to 5))))
        assertTrue(names(a).contains("Fexo 120"))
        ok(a, "books_purchase_delete", mapOf("id" to p2["id"].textValue()))
        f = item(a, "Fexo 120")!!
        assertEquals(listOf("null", "false"), listOf(f["qty"].asText(), f["inStock"].asText()))
        val p3 = ok(a, "books_purchase_add", mapOf("lines" to listOf(mapOf("itemId" to fid, "qty" to 5, "unitCost" to 6, "price" to 12))))
        ok(a, "books_item_save", mapOf("id" to fid, "name" to "Fexo 120", "price" to 13, "cost" to 6.5))
        ok(a, "books_purchase_delete", mapOf("id" to p3["id"].textValue()))
        f = item(a, "Fexo 120")!!
        assertEquals(listOf("null", "13", "6.5"), listOf(f["qty"].asText(), f["price"].asText(), f["cost"].asText()))

        // 3. over-selling and deleting gives back only what was taken
        val ace = ok(a, "books_item_save", mapOf("name" to "Ace", "qty" to 3, "cost" to 1, "price" to 2))["item"]["id"].textValue()
        val sale = ok(a, "books_sale_add", mapOf("lines" to listOf(mapOf("itemId" to ace, "qty" to 2), mapOf("itemId" to ace, "qty" to 2), mapOf("itemId" to fid, "qty" to 1))))
        assertEquals(listOf(2.0, 1.0, 0.0), sale["sale"]["lines"].map { it["dec"].doubleValue() })
        assertEquals(0.0, item(a, "Ace")!!["qty"].doubleValue())
        ok(a, "books_item_save", mapOf("id" to fid, "name" to "Fexo 120", "qty" to 7, "price" to 13, "cost" to 6.5))
        ok(a, "books_sale_delete", mapOf("id" to sale["id"].textValue()))
        assertEquals(3.0, item(a, "Ace")!!["qty"].doubleValue())
        assertEquals(7.0, item(a, "Fexo 120")!!["qty"].doubleValue())

        // 4. newest first within the limits
        val now = System.currentTimeMillis()
        val today = java.sql.Date.valueOf(Instant.ofEpochMilli(now + 6 * HOUR).atZone(ZoneOffset.UTC).toLocalDate())
        jdbc.batchUpdate(
            "INSERT INTO shop_sales (shop_uid, id, at, day, source) VALUES (?, ?, ?, ?, 'counter')",
            (1..310).map { arrayOf<Any?>(a, "old$it", ts(now - it * 1000L), today) } + listOf(arrayOf<Any?>(a, "zNewest", ts(now + 500), today)),
        )
        jdbc.batchUpdate(
            "INSERT INTO shop_purchases (shop_uid, id, at, day) VALUES (?, ?, ?, ?)",
            (1..210).map { arrayOf<Any?>(a, "old$it", ts(now - it * 1000L), today) } + listOf(arrayOf<Any?>(a, "zNewest", ts(now + 500), today)),
        )
        jdbc.batchUpdate(
            "INSERT INTO shop_adjustments (shop_uid, id, at, day, kind, item_id, name, qty_after, delta) VALUES (?, ?, ?, ?, 'count', 'i', 'n', 0, 0)",
            (1..210).map { arrayOf<Any?>(a, "old$it", ts(now - it * 1000L), today) } + listOf(arrayOf<Any?>(a, "zNewest", ts(now + 500), today)),
        )
        val sl = ok(a, "books_sales", mapOf("period" to "7d"))["items"]
        assertEquals(listOf("300", "zNewest"), listOf(sl.size().toString(), sl[0]["id"].textValue()))
        assertEquals(listOf("200", "zNewest"), ok(a, "books_purchases")["items"].let { listOf(it.size().toString(), it[0]["id"].textValue()) })
        assertEquals(listOf("200", "zNewest"), ok(a, "books_adjusts")["items"].let { listOf(it.size().toString(), it[0]["id"].textValue()) })
        val times = sl.map { it["at"].longValue() }
        assertEquals(times.sortedDescending(), times)

        // 5. a full stock list does not silently drop a new name
        val z = "shopFixFull"
        bookShop(z, (0 until 3000).map { "Med ${it.toString().padStart(4, '0')}" })
        val r = call(z, "books_item_save", mapOf("name" to "Zzz New"))
        assertEquals(400, r.status)
        assertTrue(r.error!!.contains("সর্বোচ্চ"))
        assertEquals(3000, names(z).size)
        jdbc.update("INSERT INTO shop_items (shop_uid, id, key, name) VALUES (?, ?, 'med 0000', 'Med 0000')", z, BooksMath.idOf("Med 0000"))
        ok(z, "books_item_stock", mapOf("id" to BooksMath.idOf("Med 0000"), "on" to false))
        assertEquals(2999, names(z).size)

        // 6. a write-off never loses more than there was
        val sec = ok(a, "books_item_save", mapOf("name" to "Seclo", "qty" to 3, "cost" to 4))["item"]["id"].textValue()
        val before = dayDoc(a)
        val w = ok(a, "books_adjust", mapOf("itemId" to sec, "kind" to "writeoff", "qty" to 5))["adjust"]
        assertEquals(listOf(3.0, 0.0, -3.0, 12.0), listOf("before", "after", "delta", "loss").map { w[it].doubleValue() })
        val after = dayDoc(a)
        assertEquals(listOf(12.0, 3.0), listOf(d(after["write_off"]) - d(before["write_off"]), d(after["woq"]) - d(before["woq"])))
    }

    @Test
    fun `bill discount cents never make a line negative - 100 percent makes every line 0`() {
        val ps = BooksMath.priceSale(listOf(9.43, 8.73, 8.07, 0.13).mapIndexed { k, p -> BooksMath.LineIn(null, "i$k", "M$k", 1.0, p, 0.0, null) }, Disc("amt", 25.98))
        assertTrue(ps.lines.all { it.net >= 0 }, ps.lines.map { it.net }.toString())
        assertEquals(0.38, BooksMath.r2(ps.lines.sumOf { it.net }))
        assertEquals(listOf(25.98, 0.38), listOf(ps.totals.billDiscount, ps.totals.net))
        val all = BooksMath.priceSale(listOf(0.07, 0.07, 0.07).mapIndexed { k, p -> BooksMath.LineIn(null, "i$k", "M$k", 1.0, p, 0.0, null) }, Disc("pct", 100.0))
        assertEquals(listOf(0.0, 0.0, 0.0), all.lines.map { it.net })
    }

    @Test
    fun `patient prices in the stock list follow product saves, purchases, write-offs and deletes`() {
        val a = "estShopA01"
        bookShop(a)
        val c = ok(a, "books_item_save", mapOf("name" to "Comet 500", "price" to 8, "disc" to mapOf("type" to "pct", "v" to 10), "unit" to "piece", "qty" to 50))["item"]["id"].textValue()
        ok(a, "books_item_save", mapOf("name" to "Napa", "qty" to 0, "price" to 1.2))
        ok(a, "books_item_save", mapOf("name" to "Seclo 20"))
        assertEquals(mapOf("comet 500" to 7.2, "napa" to 1.2), prices(a))
        assertEquals(listOf("comet 500", "seclo 20"), keys(a))
        assertEquals(true, row("SELECT mrp_all FROM pharmacy_stock WHERE pharmacy_uid = ?", a)["mrp_all"])
        assertEquals(8.0, (row("SELECT mrp FROM pharmacy_stock_items WHERE pharmacy_uid = ? AND key = 'comet 500'", a)["mrp"] as java.math.BigDecimal).toDouble())

        ok(a, "books_item_save", mapOf("id" to c, "name" to "Comet 500", "price" to 9, "disc" to mapOf("type" to "amt", "v" to 0.5), "unit" to "piece"))
        assertEquals(8.5, prices(a)["comet 500"])
        ok(a, "books_item_save", mapOf("id" to c, "name" to "Comet 500", "price" to 90, "unit" to "strip"))
        assertFalse("comet 500" in prices(a))
        ok(a, "books_item_save", mapOf("id" to c, "name" to "Comet 500", "price" to 9, "unit" to "piece"))
        ok(a, "books_item_save", mapOf("id" to c, "name" to "Comet 850", "price" to 9, "unit" to "piece"))
        assertEquals(mapOf("comet 850" to 9.0, "napa" to 1.2), prices(a))
        assertTrue(keys(a).contains("comet 850") && !keys(a).contains("comet 500"))
        ok(a, "books_item_save", mapOf("id" to c, "name" to "Comet 850", "price" to "", "unit" to "piece"))
        assertFalse("comet 850" in prices(a))
        ok(a, "books_item_save", mapOf("id" to c, "name" to "Comet 850", "price" to 9, "unit" to "piece"))

        val buy = ok(a, "books_purchase_add", mapOf("lines" to listOf(mapOf("itemId" to c, "qty" to 10, "unitCost" to 6, "price" to 10), mapOf("name" to "Tusca", "qty" to 5, "price" to 85))))
        assertEquals(10.0, prices(a)["comet 850"])
        assertEquals(85.0, prices(a)["tusca"])
        ok(a, "books_purchase_delete", mapOf("id" to buy["id"].textValue()))
        assertEquals(9.0, prices(a)["comet 850"])
        assertFalse("tusca" in prices(a))

        ok(a, "books_adjust", mapOf("kind" to "writeoff", "itemId" to c, "qty" to 1000, "reason" to "মেয়াদ শেষ"))
        assertFalse(keys(a).contains("comet 850"))
        assertEquals(9.0, prices(a)["comet 850"])
        ok(a, "stock_set", mapOf("add" to listOf("Zimax"), "remove" to emptyList<String>()))
        assertTrue(keys(a).contains("zimax"))
        assertEquals(1.2, prices(a)["napa"])
        ok(a, "books_item_delete", mapOf("id" to item(a, "Napa")!!["id"].textValue()))
        assertFalse("napa" in prices(a))
    }

    @Test
    fun `account deletion removes the products and the whole books`() {
        val a = "shopDelA001"
        bookShop(a, listOf("Napa"))
        val c = ok(a, "books_item_save", mapOf("name" to "Comet 500", "qty" to 10, "cost" to 5, "price" to 8))["item"]["id"].textValue()
        ok(a, "books_purchase_add", mapOf("lines" to listOf(mapOf("itemId" to c, "qty" to 5, "unitCost" to 5))))
        ok(a, "books_sale_add", mapOf("lines" to listOf(mapOf("itemId" to c, "qty" to 2))))
        ok(a, "books_adjust", mapOf("itemId" to c, "kind" to "writeoff", "qty" to 1))
        ok(a, "books_items")
        fun mine() = listOf("shop_items", "shop_purchases", "shop_sales", "shop_adjustments", "shop_days").sumOf { t ->
            jdbc.queryForObject("SELECT count(*) FROM $t WHERE shop_uid = ?", Int::class.java, a)!!
        } + jdbc.queryForObject("SELECT count(*) FROM pharmacy_stock WHERE pharmacy_uid = ?", Int::class.java, a)!!
        assertTrue(mine() >= 6)
        deletion.deleteNow(a, "$a@example.com")
        assertEquals(0, mine())
    }

    @Test
    fun `prescription sales keep no patient name`() {
        val s = "pgShop00004"
        bookShop(s)
        rx("pgRx4", "PGRXQQ4444", null, listOf(Med("Seclo", dose = "1+0+1", days = 30)), patientName = "রোগী চার")
        ok(s, "rx_dispense", mapOf("code" to "PGRXQQ4444", "items" to listOf(mapOf("i" to 0, "qty" to 10))))
        val pv = ok(s, "books_rx_preview", mapOf("code" to "PGRXQQ4444"))
        val sale = ok(s, "books_sale_add", mapOf("source" to "rx", "rx" to mapOf("code" to "PGRXQQ4444", "at" to pv["at"].longValue())))["sale"]
        assertEquals(listOf("at", "code", "rxId"), sale["rx"].fieldNames().asSequence().toList().sorted())
        assertFalse(sale.toString().contains("রোগী চার"))
        assertEquals(409, call(s, "books_sale_add", mapOf("source" to "rx", "rx" to mapOf("code" to "PGRXQQ4444", "at" to pv["at"].longValue()))).status)
        assertFalse(ok(s, "books_sales", mapOf("period" to "today")).toString().contains("রোগী চার"))
    }
}
