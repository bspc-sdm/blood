package com.lifolo.books

import com.lifolo.common.DhakaTime
import com.lifolo.common.Js
import com.lifolo.pharmacy.RxMath
import com.lifolo.pharmacy.StockStore
import java.time.LocalDate
import java.time.ZoneOffset

/** a product discount: `{type: 'pct' | 'amt', v}` */
data class Disc(val type: String, val v: Double) {
    fun toMap(): Map<String, Any?> = linkedMapOf("type" to type, "v" to v)
}

/** `shopItems/{uid}/items/{id}` — everything but the name is optional */
data class ShopItem(
    val id: String,
    val name: String,
    val key: String,
    val generic: String = "",
    val form: String = "",
    val unit: String = "",
    val qty: Double? = null,
    val cost: Double? = null,
    val price: Double? = null,
    val disc: Disc? = null,
    val lowAt: Double? = null,
    val expiry: String? = null,
    val lastSaleAt: Long? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

/**
 * The pure parts of shopbooks.js: numbers, discounts, item view, patient prices, sale pricing (line and bill
 * discounts, profit), day deltas, the P&L report and periods.
 */
object BooksMath {
    const val DAY_MS = 24L * 60 * 60 * 1000
    const val MAX_ITEMS = StockStore.MAX_ITEMS
    const val MAX_LINES = 100
    val UNITS = listOf("piece", "strip", "box", "bottle", "tube", "vial", "sachet", "other")
    val SOURCES = listOf("counter", "rx", "online")
    val PACK_UNITS = listOf("strip", "box")

    /** `Math.round((x + Number.EPSILON) * 100) / 100` */
    fun r2(x: Double): Double = Js.round((x + Math.ulp(1.0)) * 100) / 100

    fun key(name: String?): String = RxMath.key(name)

    /** shopbooks.js idOf: product id from the name */
    fun idOf(name: String): String = "k" + RxMath.sha1Hex(key(name)).take(16)

    /** shopbooks.js str: white space collapsed, trimmed, cut */
    fun str(v: String?, n: Int): String = if (v == null) "" else v.replace(Regex("\\s+"), " ").trim().take(n)

    /** rules.js dhakaDayKey */
    fun dayKey(ms: Long): String = DhakaTime.dayKey(ms)

    /** start of the Dhaka day (ms) */
    fun dayStart(ms: Long): Long = DhakaTime.day(ms).atStartOfDay(DhakaTime.OFFSET).toInstant().toEpochMilli()

    fun dayOfKey(k: String): LocalDate = LocalDate.parse(k, java.time.format.DateTimeFormatter.BASIC_ISO_DATE)

    fun dayKeyOf(d: LocalDate): String = d.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)

    /** last moment of the expiry month (Dhaka) */
    fun expiryEnd(e: String?): Long? {
        if (e == null) return null
        val (y, m) = e.split("-").map { it.toInt() }
        return LocalDate.of(y, m, 1).plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - 6 * 3600 * 1000 - 1
    }

    /** per-unit discount in taka (0 when the price is unknown) */
    fun unitDiscOf(price: Double?, disc: Disc?): Double {
        if (price == null || disc == null) return 0.0
        return r2(minOf(price, if (disc.type == "pct") price * disc.v / 100 else disc.v))
    }

    /** shopbooks.js itemView */
    fun itemView(x: ShopItem, stockKeys: Set<String>, now: Long): LinkedHashMap<String, Any?> {
        val exp = expiryEnd(x.expiry)
        val qty = x.qty
        val lowAt = x.lowAt
        val price = x.price
        return linkedMapOf(
            "id" to x.id,
            "name" to x.name,
            "generic" to x.generic,
            "form" to x.form,
            "unit" to x.unit,
            "qty" to qty,
            "cost" to x.cost,
            "price" to price,
            "disc" to x.disc?.toMap(),
            "salePrice" to price?.let { r2(it - unitDiscOf(it, x.disc)) },
            "lowAt" to lowAt,
            "expiry" to x.expiry,
            "inStock" to (x.key.ifEmpty { key(x.name) } in stockKeys),
            "low" to (qty != null && lowAt != null && qty <= lowAt),
            "expDays" to exp?.let { Math.floorDiv(it - now, DAY_MS) },
            "lastSaleAt" to x.lastSaleAt?.takeIf { it != 0L },
            "updatedAt" to x.updatedAt,
        )
    }

    /** the price patients see (per piece/bottle/…) — null when unknown or a strip/box price */
    fun patientPriceOf(x: ShopItem?): Double? {
        val price = x?.price ?: return null
        if (x.unit in PACK_UNITS) return null
        val v = r2(price - unitDiscOf(price, x.disc))
        return if (v > 0) v else null
    }

    /** the MRP when the shop gives a discount (MRP > sale price), else null */
    fun patientMrpOf(x: ShopItem?): Double? {
        val sale = patientPriceOf(x) ?: return null
        return if (x!!.price!! > sale) r2(x.price!!) else null
    }

    /** shopbooks.js priceChange: the stock-list price of one product (a rename drops the old key's price) */
    fun priceChange(prices: MutableMap<String, StockStore.PriceChange?>, before: ShopItem?, after: ShopItem?): MutableMap<String, StockStore.PriceChange?> {
        val kb = before?.let { it.key.ifEmpty { key(it.name) } }
        val ka = after?.let { it.key.ifEmpty { key(it.name) } }
        if (kb != null && kb != ka) prices[kb] = null
        if (ka != null) prices[ka] = StockStore.PriceChange(patientPriceOf(after), patientMrpOf(after))
        return prices
    }

    /** shopbooks.js stockChange: quantity 0 → off the stock list, up from 0 → on (unknown quantity: no change) */
    fun stockChange(name: String, before: Double?, after: Double?, add: MutableList<String>, remove: MutableList<String>) {
        if (after == null) return
        if (after <= 0 && (before == null || before > 0)) {
            remove.add(name)
        } else if (after > 0 && (before == null || before <= 0)) {
            add.add(name)
        }
    }

    fun newItem(id: String, name: String, now: Long): ShopItem = ShopItem(id = id, name = name, key = key(name), createdAt = now, updatedAt = now)

    // ------------------------------------------------------------------ sales

    data class LineIn(
        val i: Int?,
        val itemId: String,
        val name: String,
        val qty: Double,
        val unitPrice: Double?,
        val unitDisc: Double,
        val unitCost: Double?,
    )

    class SaleLine(
        val itemId: String,
        val name: String,
        val qty: Double,
        val unitPrice: Double?,
        val unitDisc: Double,
        val discount: Double,
        val net: Double,
        val unitCost: Double?,
        val cost: Double?,
        val profit: Double?,
        val i: Int?,
        var noItem: Boolean = false,
        var dec: Double? = null,
    ) {
        fun toMap(): LinkedHashMap<String, Any?> = linkedMapOf<String, Any?>(
            "itemId" to itemId, "name" to name, "qty" to qty, "unitPrice" to unitPrice, "unitDisc" to unitDisc,
            "discount" to discount, "net" to net, "unitCost" to unitCost, "cost" to cost, "profit" to profit,
        ).apply {
            if (i != null) put("i", i)
            if (noItem) put("noItem", true)
            if (dec != null) put("dec", dec)
        }
    }

    data class Totals(
        val gross: Double,
        val discount: Double,
        val billDiscount: Double,
        val net: Double,
        val cost: Double,
        val profit: Double,
        val netCosted: Double,
        val unpriced: Int,
    ) {
        fun toMap(): Map<String, Any?> = linkedMapOf(
            "gross" to gross, "discount" to discount, "billDiscount" to billDiscount, "net" to net, "cost" to cost,
            "profit" to profit, "netCosted" to netCosted, "unpriced" to unpriced,
        )
    }

    class Priced(val lines: List<SaleLine>, val totals: Totals)

    /**
     * shopbooks.js priceSale: lines → totals (discount, net, cost, profit). Unpriced lines count only quantities;
     * lines without a cost are not in the profit (netCosted = net of costed lines — the margin base). The bill
     * discount is shared by the lines' after-discount amounts; rounding cents go from the end where there is room.
     */
    fun priceSale(raw: List<LineIn>, billDisc: Disc?): Priced {
        class L(val l: LineIn, val priced: Boolean, val unitDisc: Double, val gross: Double, val lineDisc: Double, val after: Double)
        val lines = raw.map { l ->
            val priced = l.unitPrice != null
            val gross = if (priced) r2(l.qty * l.unitPrice!!) else 0.0
            val ld = if (priced) r2(minOf(gross, l.qty * l.unitDisc)) else 0.0
            L(l, priced, if (priced) l.unitDisc else 0.0, gross, ld, r2(gross - ld))
        }
        val sumAfter = r2(lines.sumOf { it.after })
        var bd = 0.0
        if (billDisc != null && sumAfter > 0) bd = r2(minOf(sumAfter, if (billDisc.type == "pct") sumAfter * billDisc.v / 100 else billDisc.v))
        val shares = lines.map { l -> if (bd > 0 && l.after > 0) minOf(l.after, r2(bd * l.after / sumAfter)) else 0.0 }.toMutableList()
        var left = r2(bd - shares.sum())
        var i = lines.size - 1
        while (i >= 0 && left != 0.0) {
            val move = if (left > 0) minOf(r2(lines[i].after - shares[i]), left) else -minOf(shares[i], -left)
            shares[i] = r2(shares[i] + move)
            left = r2(left - move)
            i--
        }
        val out = lines.mapIndexed { k, l ->
            val share = shares[k]
            val net = r2(l.after - share)
            val cost = l.l.unitCost?.let { r2(l.l.qty * it) }
            val profit = if (l.priced && cost != null) r2(net - cost) else null
            SaleLine(
                l.l.itemId, l.l.name, l.l.qty, if (l.priced) l.l.unitPrice else null, l.unitDisc, r2(l.lineDisc + share), net,
                l.l.unitCost, cost, profit, l.l.i,
            )
        }
        fun sum(f: (SaleLine) -> Double?) = r2(out.sumOf { f(it) ?: 0.0 })
        val gross = r2(lines.sumOf { it.gross })
        return Priced(
            out,
            Totals(
                gross = gross,
                discount = r2(lines.sumOf { it.lineDisc } + bd),
                billDiscount = bd,
                net = sum { it.net },
                cost = sum { if (it.profit != null) it.cost else 0.0 },
                profit = sum { it.profit },
                netCosted = sum { if (it.profit != null) it.net else 0.0 },
                unpriced = out.count { it.unitPrice == null },
            ),
        )
    }

    /** one product's day totals (`days.items[itemId]`) */
    class DayItem(var name: String, var q: Double = 0.0, var net: Double = 0.0, var profit: Double = 0.0, var neg: Double = 0.0, var nq: Double = 0.0)

    /** what one write adds to `shopBooks/{uid}/days/{day}` (all increments) */
    class DayDelta(
        val sales: Double = 0.0,
        val purchases: Double = 0.0,
        val adjusts: Double = 0.0,
        val gross: Double = 0.0,
        val discount: Double = 0.0,
        val net: Double = 0.0,
        val nc: Double = 0.0,
        val cost: Double = 0.0,
        val profit: Double = 0.0,
        val bought: Double = 0.0,
        val writeOff: Double = 0.0,
        val woq: Double = 0.0,
        val src: Map<String, Pair<Double, Double>> = emptyMap(),
        val items: Map<String, DayItem> = emptyMap(),
    )

    /** shopbooks.js saleDayDelta (sign −1 when a sale is deleted) */
    fun saleDayDelta(source: String, totals: Totals, lines: List<SaleLine>, sign: Int = 1): DayDelta {
        fun s(v: Double?) = sign * (v ?: 0.0)
        val items = LinkedHashMap<String, DayItem>()
        for (l in lines) {
            val cur = items.getOrPut(l.itemId) { DayItem(l.name) }
            cur.q += s(l.qty)
            cur.net += s(l.net)
            if (l.profit != null) cur.profit += s(l.profit)
            if (l.profit != null && l.profit < 0) {
                cur.neg += s(l.profit)
                cur.nq += s(l.qty)
            }
        }
        return DayDelta(
            sales = s(1.0), gross = s(totals.gross), discount = s(totals.discount), net = s(totals.net), cost = s(totals.cost),
            profit = s(totals.profit), nc = s(totals.netCosted), src = mapOf(source to (s(1.0) to s(totals.net))), items = items,
        )
    }

    /** one `days/{day}` document as the report reads it */
    class DayDoc(
        val sales: Double,
        val gross: Double,
        val discount: Double,
        val net: Double,
        val cost: Double,
        val profit: Double,
        val nc: Double,
        val writeOff: Double,
        val bought: Double,
        val purchases: Double,
        val src: Map<String, Pair<Double, Double>>,
        val items: Map<String, DayItem>,
    )

    data class Period(val days: List<String>, val start: Long)

    /** shopbooks.js periodOf: day keys (oldest first) and the start time */
    fun periodOf(period: String, now: Long): Period {
        val today = dayStart(now)
        var n = when (period) {
            "today" -> 1
            "30d" -> 30
            "month" -> dayKey(now).substring(6, 8).toInt()
            else -> 7
        }
        n = n.coerceIn(1, 31)
        val days = (n - 1 downTo 0).map { i -> dayKey(today - i * DAY_MS + 12 * 3600 * 1000) }
        return Period(days, today - (n - 1) * DAY_MS)
    }

    /** shopbooks.js report: the P&L of a period from the day documents and the products */
    fun report(period: String, days: List<String>, dayDocs: List<DayDoc?>, rows: List<ShopItem>, stockKeys: Set<String>, now: Long): Map<String, Any?> {
        val t = DoubleArray(10)
        // sales, gross, discount, net, cost, profit, nc, writeOff, bought, purchases
        val src = LinkedHashMap<String, DoubleArray>()
        SOURCES.forEach { src[it] = DoubleArray(2) }
        val per = LinkedHashMap<String, DayItem>()
        val daily = days.mapIndexed { i, day ->
            val x = dayDocs[i]
            if (x != null) {
                val v = doubleArrayOf(x.sales, x.gross, x.discount, x.net, x.cost, x.profit, x.nc, x.writeOff, x.bought, x.purchases)
                for (k in t.indices) t[k] += v[k]
                for (s in SOURCES) {
                    x.src[s]?.let { (n, net) ->
                        src[s]!![0] += n
                        src[s]!![1] += net
                    }
                }
                for ((id, v2) in x.items) {
                    val c = per.getOrPut(id) { DayItem(v2.name) }
                    c.name = v2.name.ifEmpty { c.name }
                    c.q += v2.q
                    c.net += v2.net
                    c.profit += v2.profit
                    c.neg += v2.neg
                    c.nq += v2.nq
                }
            }
            linkedMapOf<String, Any?>(
                "day" to day, "net" to r2(x?.net ?: 0.0), "profit" to r2(x?.profit ?: 0.0), "sales" to Js.round(x?.sales ?: 0.0).toLong(),
            )
        }
        for (k in t.indices) t[k] = r2(t[k])
        val (sales, gross, discount, net, cost) = listOf(t[0], t[1], t[2], t[3], t[4])
        val profit = t[5]
        val nc = t[6]
        val writeOff = t[7]
        val bought = t[8]
        val purchases = t[9]
        val list = per.entries.map { (id, c) ->
            linkedMapOf<String, Any?>(
                "id" to id, "name" to c.name, "qty" to r2(c.q), "net" to r2(c.net), "profit" to r2(c.profit),
                "loss" to r2(-c.neg), "lossQty" to r2(c.nq),
            )
        }.filter { (it["qty"] as Double) > 0 || (it["net"] as Double) > 0 }
        val items = rows.map { itemView(it, stockKeys, now) }
        fun d(m: Map<String, Any?>, k: String) = m[k] as Double?
        val live = items.filter { (d(it, "qty") ?: 0.0) > 0 }
        fun value(f: String) = r2(live.sumOf { i -> d(i, f)?.let { d(i, "qty")!! * it } ?: 0.0 })
        val quiet = now - 30 * DAY_MS
        val createdOf = rows.associate { it.id to it.createdAt }
        return linkedMapOf(
            "period" to period,
            "from" to days.first(),
            "to" to days.last(),
            "totals" to linkedMapOf(
                "sales" to Js.round(sales).toLong(), "gross" to gross, "discount" to discount, "net" to net,
                "cost" to cost, "profit" to profit, "margin" to if (nc > 0) Js.round(profit / nc * 1000) / 10 else null,
                "uncosted" to r2(net - nc),
                "writeOff" to writeOff, "netProfit" to r2(profit - writeOff), "bought" to bought, "purchases" to Js.round(purchases).toLong(),
            ),
            "bySource" to src.mapValues { (_, v) -> linkedMapOf("n" to Js.round(v[0]).toLong(), "net" to r2(v[1])) },
            "daily" to daily,
            "topSales" to list.sortedByDescending { it["net"] as Double }.take(10),
            "topProfit" to list.filter { (it["profit"] as Double) > 0 }.sortedByDescending { it["profit"] as Double }.take(10),
            "belowCost" to list.filter { (it["loss"] as Double) > 0 }.sortedByDescending { it["loss"] as Double }.take(20),
            "stock" to linkedMapOf(
                "items" to items.size,
                "counted" to items.count { it["qty"] != null },
                "atCost" to value("cost"),
                "atPrice" to value("price"),
                "noCost" to live.count { it["cost"] == null },
                "noPrice" to live.count { it["price"] == null },
            ),
            "low" to items.filter { it["low"] == true }.sortedBy { d(it, "qty") ?: 0.0 }.take(50),
            "expiring" to items.filter { i ->
                val e = i["expDays"] as Long?
                e != null && e <= 60 && (d(i, "qty") == null || d(i, "qty")!! > 0)
            }.sortedBy { it["expDays"] as Long }.take(50),
            "noSale" to live.filter { i -> ((i["lastSaleAt"] as Long?) ?: createdOf[i["id"]] ?: 0L) < quiet }
                .sortedBy { (it["lastSaleAt"] as Long?) ?: 0L }.take(50),
        )
    }
}
