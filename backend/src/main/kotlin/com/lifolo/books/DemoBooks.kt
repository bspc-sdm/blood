package com.lifolo.books

import com.lifolo.admin.kernel.DemoSeedContext
import com.lifolo.admin.kernel.DemoSeedPart
import com.lifolo.pharmacy.StockStore
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.ZoneOffset

/**
 * demo/books.js — the books of "ডেমো লাইফ ফার্মেসি": products (prices, quantities, expiry), purchases, 35 days of
 * counter / prescription / online sales, write-offs and a count, the day aggregates — fixed numbers (same seed every
 * time), so the guide's "today / 7 days / 30 days" figures never change. Runs after the services part, which writes
 * the shop and its 24 stock names; these 24 are products here, plus 2 that ran out (not on the stock list).
 */
@Component
class DemoBooks(private val store: BooksStore, private val stock: StockStore) : DemoSeedPart {
    override val step = 3
    override val label = "দোকানের হিসাব"

    /** [name, generic, form, unit, cost, price, disc, final qty, lowAt, expiry (months | soon5 | soon25), weight, per sale] */
    class Row(
        val name: String,
        val generic: String,
        val form: String,
        val unit: String,
        val cost: Double,
        val price: Double,
        val disc: Disc?,
        val finalQty: Int,
        val lowAt: Int,
        val exp: Any,
        val weight: Int,
        val per: IntArray,
    )

    companion object {
        private fun r(
            name: String, generic: String, form: String, unit: String, cost: Double, price: Double, disc: Disc?, finalQty: Int,
            lowAt: Int, exp: Any, weight: Int, a: Int, b: Int,
        ) = Row(name, generic, form, unit, cost, price, disc, finalQty, lowAt, exp, weight, intArrayOf(a, b))

        private fun pct(v: Double) = Disc("pct", v)
        private fun amt(v: Double) = Disc("amt", v)

        val ITEMS = listOf(
            r("Napa", "Paracetamol 500 mg", "Tablet", "piece", 0.9, 1.2, null, 640, 200, 14, 10, 10, 20),
            r("Ace", "Paracetamol 500 mg", "Tablet", "piece", 0.95, 1.2, null, 380, 100, 18, 6, 10, 20),
            r("Seclo", "Omeprazole 20 mg", "Capsule", "piece", 5.2, 7.0, pct(10.0), 260, 60, 20, 7, 10, 14),
            r("Losectil", "Omeprazole 20 mg", "Capsule", "piece", 4.8, 6.0, null, 120, 40, 16, 3, 10, 14),
            r("Maxpro", "Esomeprazole 20 mg", "Tablet", "piece", 6.2, 8.0, pct(10.0), 150, 40, 22, 5, 10, 14),
            r("Sergel", "Esomeprazole 20 mg", "Capsule", "piece", 5.5, 7.0, pct(5.0), 140, 40, 15, 4, 10, 14),
            r("Pantonix", "Pantoprazole 20 mg", "Tablet", "piece", 5.0, 7.0, null, 90, 30, 19, 2, 10, 14),
            r("Fexo", "Fexofenadine 120 mg", "Tablet", "piece", 7.0, 9.0, amt(0.5), 110, 30, 17, 3, 7, 10),
            r("Alatrol", "Cetirizine 10 mg", "Tablet", "piece", 2.5, 3.5, null, 200, 50, 21, 4, 10, 10),
            r("Tofen", "Ketotifen 1 mg", "Tablet", "piece", 2.6, 3.5, null, 60, 20, 13, 0, 10, 10),
            r("Montene", "Montelukast 10 mg", "Tablet", "piece", 13.0, 16.0, pct(10.0), 8, 15, 12, 3, 10, 15),
            r("Monas", "Montelukast 10 mg", "Tablet", "piece", 13.5, 17.0, null, 70, 20, 11, 2, 10, 15),
            r("Ceevit", "Vitamin C 250 mg", "Tablet", "piece", 1.2, 1.5, null, 150, 50, "soon5", 3, 10, 30),
            r("Calbo", "Calcium 500 mg", "Tablet", "piece", 8.0, 10.5, pct(10.0), 120, 30, 24, 3, 15, 30),
            r("Xinc", "Zinc 20 mg", "Tablet", "piece", 2.5, 3.0, null, 90, 20, 10, 0, 10, 10),
            r("Comet", "Metformin 500 mg", "Tablet", "piece", 3.6, 5.0, pct(10.0), 300, 100, 20, 6, 20, 60),
            r("Rosuva", "Rosuvastatin 10 mg", "Tablet", "piece", 14.0, 18.0, pct(10.0), 6, 20, 15, 3, 10, 30),
            r("Ecosprin", "Aspirin 75 mg", "Tablet", "piece", 0.5, 0.8, null, 280, 60, 23, 4, 28, 30),
            r("Bislol", "Bisoprolol 2.5 mg", "Tablet", "piece", 6.0, 8.0, pct(5.0), 90, 30, 17, 2, 10, 30),
            r("Amdocal", "Amlodipine 5 mg", "Tablet", "piece", 3.8, 5.0, pct(10.0), 160, 40, 19, 4, 10, 30),
            r("Azithrocin", "Azithromycin 500 mg", "Tablet", "piece", 30.0, 40.0, amt(2.0), 24, 10, "soon25", 2, 3, 5),
            r("Cef-3", "Cefixime 200 mg", "Capsule", "piece", 32.0, 40.0, null, 4, 10, 14, 2, 6, 10),
            // the cost went up, the shop did not change the sale price (MRP) — a loss on every sale
            r("Flagyl", "Metronidazole 400 mg", "Tablet", "piece", 2.4, 2.0, null, 130, 40, 18, 3, 10, 21),
            r("Orsaline-N", "Oral rehydration salt", "Sachet", "sachet", 4.2, 5.0, null, 180, 50, 26, 5, 5, 10),
            // ran out (not on the stock list)
            r("Nexum 20", "Esomeprazole 20 mg", "Tablet", "piece", 6.5, 8.0, null, 0, 20, 12, 0, 10, 10),
            r("Thyrox 50", "Levothyroxine 50 mcg", "Tablet", "piece", 1.8, 2.5, null, 0, 30, 12, 0, 30, 30),
        )

        val COUNTER = listOf("", "", "", "ডেমো ক্রেতা রাকিব", "", "ডেমো ক্রেতা মিতু", "")

        /** books.js rng: mulberry32 (fixed seed, the same numbers as Node) */
        fun rng(seed: Int): () -> Double {
            var a = seed
            return {
                a += 0x6d2b79f5
                var t = a
                t = (t xor (t ushr 15)) * (t or 1)
                t = t xor (t + (t xor (t ushr 7)) * (t or 61))
                ((t xor (t ushr 14)).toLong() and 0xffffffffL) / 4294967296.0
            }
        }

        /** "YYYY-MM" of a Dhaka time */
        fun monthAt(ms: Long): String = Instant.ofEpochMilli(ms + 6 * 3600 * 1000).atZone(ZoneOffset.UTC).toLocalDate().toString().substring(0, 7)

        private const val HOUR = 3600 * 1000L
    }

    private class Item(val row: Row, val id: String, val expiry: String) {
        val events = ArrayList<Pair<Long, Double>>()
        var lastSaleAt: Long? = null
    }

    @Transactional
    override fun seed(ctx: DemoSeedContext) {
        val ph = ctx.user("pharmacy").uid
        val now = ctx.nowMs
        val day = BooksMath.DAY_MS
        val today = BooksMath.dayStart(now)
        val rand = rng(1501)
        fun <T> pick(list: List<T>): T = list[Math.floor(rand() * list.size).toInt()]
        fun between(per: IntArray): Int = per[0] + Math.floor(rand() * (per[1] - per[0] + 1)).toInt()

        val items = ITEMS.map { r ->
            val exp = when (r.exp) {
                "soon5" -> monthAt(now + 5 * day)
                "soon25" -> monthAt(now + 25 * day)
                else -> monthAt(now + (r.exp as Int) * 30 * day)
            }
            Item(r, BooksMath.idOf(r.name), exp)
        }
        val byName = items.associateBy { it.row.name }
        val pool = items.flatMap { i -> List(i.row.weight) { i } }

        // ---------- sales: 35 days (today included) ----------
        var n = 0
        for (k in 34 downTo 0) {
            val ds = today - k * day
            val counter = 5 + ((k * 7) % 5)
            val rxN = if (k % 3 == 0) 2 else 1
            val online = if (k % 4 == 1) 1 else 0
            val total = counter + rxN + online
            for (j in 0 until total) {
                val at = if (k == 0) ds + Math.floorDiv((now - ds) * (j + 1), (total + 1).toLong()) else ds + 9 * HOUR + Math.floorDiv(12 * HOUR * j, total.toLong())
                val source = if (j < counter) "counter" else if (j < counter + rxN) "rx" else "online"
                val nLines = if (source == "counter") 1 + Math.floor(rand() * 3).toInt() else 2 + Math.floor(rand() * 2).toInt()
                val chosen = ArrayList<Item>()
                repeat(nLines) {
                    val it = pick(pool)
                    if (it !in chosen) chosen.add(it)
                }
                val lines = chosen.map { it ->
                    val unitDisc = if (source == "counter" && rand() < 0.3) 0.0 else BooksMath.unitDiscOf(it.row.price, it.row.disc)
                    BooksMath.LineIn(null, it.id, it.row.name, between(it.row.per).toDouble(), it.row.price, unitDisc, it.row.cost)
                }
                val big = BooksMath.priceSale(lines, null).totals.net >= 150
                val bill = if (source == "online") Disc("pct", 5.0) else if (source == "counter" && j % 4 == 3 && big) Disc("amt", 5.0) else null
                val priced = BooksMath.priceSale(lines, bill)
                n++
                // no patient name (like real sales) — only the prescription code; the rand() kept so the numbers stay the same
                val rx = if (source != "counter" && rand() >= 0) BooksStore.RxInfo("", "DEMO${n.toString().padStart(6, '0')}", at) else null
                val customer = if (source == "counter") pick(COUNTER) else ""
                for (l in priced.lines) {
                    val it = byName.getValue(l.name)
                    it.events.add(at to -l.qty)
                    it.lastSaleAt = maxOf(it.lastSaleAt ?: 0L, at)
                }
                val sale = BooksStore.Sale(
                    "demo-sale-${n.toString().padStart(4, '0')}", at, BooksMath.dayKey(at), source, priced.lines, priced.totals, bill,
                    rx, customer, at,
                )
                store.insertSale(ph, sale)
                store.dayAdd(ph, sale.day, BooksMath.saleDayDelta(source, priced.totals, priced.lines))
            }
        }

        // ---------- purchases ----------
        val purchases = listOf(
            Triple(28, "ডেমো রেনাটা ডিপো", listOf("Comet" to 300, "Seclo" to 200, "Maxpro" to 150, "Calbo" to 120)),
            Triple(21, "ডেমো স্কয়ার ডিস্ট্রিবিউটর", listOf("Napa" to 500, "Ace" to 300, "Fexo" to 100, "Ecosprin" to 300)),
            Triple(14, "ডেমো বেক্সিমকো ডিপো", listOf("Montene" to 60, "Rosuva" to 90, "Bislol" to 60, "Amdocal" to 120, "Flagyl" to 200)),
            Triple(6, "ডেমো রেনাটা ডিপো", listOf("Seclo" to 140, "Sergel" to 100, "Orsaline-N" to 150, "Azithrocin" to 30)),
            Triple(2, "ডেমো স্কয়ার ডিস্ট্রিবিউটর", listOf("Napa" to 400, "Alatrol" to 100, "Cef-3" to 20)),
        )
        var p = 0
        for ((k, supplier, list) in purchases) {
            val at = today - k * day + 11 * HOUR
            val lines = list.map { (name, qty) ->
                val it = byName.getValue(name)
                it.events.add(at to qty.toDouble())
                BooksStore.PurchaseLine(it.id, name, qty.toDouble(), it.row.cost, it.expiry, Math.round(qty * it.row.cost * 100) / 100.0)
            }
            val total = Math.round(lines.sumOf { it.total!! } * 100) / 100.0
            store.insertPurchase(ph, BooksStore.Purchase("demo-buy-${++p}", at, BooksMath.dayKey(at), supplier, "", lines, total, at))
            store.dayAdd(ph, BooksMath.dayKey(at), BooksMath.DayDelta(purchases = 1.0, bought = total))
        }

        // ---------- write-offs / count ----------
        data class Adj(val k: Int, val kind: String, val name: String, val q: Int, val reason: String)
        val adjusts = listOf(
            Adj(12, "writeoff", "Orsaline-N", 5, "প্যাকেট ভিজে নষ্ট"),
            Adj(4, "writeoff", "Napa", 20, "পাতা ছেঁড়া"),
            Adj(8, "count", "Maxpro", -2, ""),
        )
        for (a in adjusts) byName.getValue(a.name).events.add((today - a.k * day + 20 * HOUR) to (if (a.kind == "writeoff") -a.q else a.q).toDouble())
        // how many there were at a time = the final quantity minus every later change
        fun qtyAt(it: Item, t: Long) = it.row.finalQty - it.events.filter { (e, _) -> e > t }.sumOf { it.second }
        var an = 0
        for (a in adjusts) {
            val it = byName.getValue(a.name)
            val at = today - a.k * day + 20 * HOUR
            val loss = if (a.kind == "writeoff") Math.round(a.q * it.row.cost * 100) / 100.0 else 0.0
            val delta = (if (a.kind == "writeoff") -a.q else a.q).toDouble()
            val after = qtyAt(it, at)
            val before = after - delta
            store.insertAdjust(
                ph,
                BooksStore.Adjust("demo-adj-${++an}", a.kind, it.id, a.name, before, after, delta, it.row.cost, loss, a.reason, at, BooksMath.dayKey(at), at),
            )
            store.dayAdd(
                ph, BooksMath.dayKey(at),
                if (a.kind == "writeoff") BooksMath.DayDelta(adjusts = 1.0, writeOff = loss, woq = a.q.toDouble()) else BooksMath.DayDelta(adjusts = 1.0),
            )
        }

        // ---------- products: the final quantities (opened 90 days ago) ----------
        val shopItems = items.map {
            val r = it.row
            ShopItem(
                it.id, r.name, BooksMath.key(r.name), r.generic, r.form, r.unit, r.finalQty.toDouble(), r.cost, r.price, r.disc,
                r.lowAt.toDouble(), it.expiry, it.lastSaleAt, now - 90 * day, now - day,
            )
        }
        shopItems.forEach { store.saveItem(ph, it) }

        // ---------- patient estimate prices next to the stock names of the services part (what stockWrite would keep) ----------
        val prices = LinkedHashMap<String, Double>()
        val mrp = LinkedHashMap<String, Double>()
        for (x in shopItems) {
            BooksMath.patientPriceOf(x)?.let { prices[x.key] = it }
            BooksMath.patientMrpOf(x)?.let { mrp[x.key] = it }
        }
        val snap = stock.load(ph)
        stock.save(ph, snap, snap.names, prices, stock.mrpsFor(mrp, prices), pricesAll = true, mrpAll = true)
    }
}
