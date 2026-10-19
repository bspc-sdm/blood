package com.lifolo.books

import com.lifolo.common.Sql
import com.lifolo.common.ms
import com.lifolo.pharmacy.RxMath
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.Timestamp

private fun ResultSet.dec(col: String): Double? = getBigDecimal(col)?.toDouble()

private fun Double?.bd(): BigDecimal? = this?.toBigDecimal()

/**
 * Rows of the shop books (V6 tables): products, purchases (+ lines, undo), sales (+ lines), adjustments and the
 * day aggregates. All writes run inside the caller's transaction (BooksService), after the shop lock.
 */
@Component
class BooksStore(private val jdbc: JdbcTemplate) {
    // ------------------------------------------------------------------ products

    private fun item(rs: ResultSet): ShopItem {
        val dt = rs.getString("disc_type")
        val dv = rs.dec("disc_value")
        return ShopItem(
            id = rs.getString("id"),
            name = rs.getString("name"),
            key = rs.getString("key"),
            generic = rs.getString("generic") ?: "",
            form = rs.getString("form") ?: "",
            unit = rs.getString("unit") ?: "",
            qty = rs.dec("qty"),
            cost = rs.dec("cost"),
            price = rs.dec("price"),
            disc = if (dt != null && dv != null) Disc(dt, dv) else null,
            lowAt = rs.dec("low_at"),
            expiry = rs.getString("expiry"),
            lastSaleAt = rs.ms("last_sale_at"),
            createdAt = rs.ms("created_at") ?: 0,
            updatedAt = rs.ms("updated_at") ?: 0,
        )
    }

    fun items(uid: String, limit: Int = BooksMath.MAX_ITEMS): List<ShopItem> =
        jdbc.query("SELECT * FROM shop_items WHERE shop_uid = ? ORDER BY id LIMIT ?", { rs, _ -> item(rs) }, uid, limit)

    fun itemsById(uid: String, ids: Collection<String>, forUpdate: Boolean = true): Map<String, ShopItem> {
        if (ids.isEmpty()) return emptyMap()
        val list = ids.distinct()
        return jdbc.query(
            "SELECT * FROM shop_items WHERE shop_uid = ? AND id IN (${list.joinToString(",") { "?" }}) ORDER BY id" + if (forUpdate) " FOR UPDATE" else "",
            { rs, _ -> item(rs) }, uid, *list.toTypedArray(),
        ).associateBy { it.id }
    }

    fun item(uid: String, id: String, forUpdate: Boolean = true): ShopItem? = itemsById(uid, listOf(id), forUpdate)[id]

    fun itemsWithKey(uid: String, key: String, limit: Int): List<ShopItem> =
        jdbc.query("SELECT * FROM shop_items WHERE shop_uid = ? AND key = ? LIMIT ?", { rs, _ -> item(rs) }, uid, key, limit)

    fun count(uid: String): Int = jdbc.queryForObject("SELECT count(*) FROM shop_items WHERE shop_uid = ?", Int::class.java, uid) ?: 0

    /** `t.set(itemRef, x)` — the whole product */
    fun saveItem(uid: String, x: ShopItem) {
        jdbc.update(
            """
            INSERT INTO shop_items (shop_uid, id, key, name, generic, form, unit, price, cost, disc_type, disc_value, qty, low_at,
              expiry, last_sale_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (shop_uid, id) DO UPDATE SET key = EXCLUDED.key, name = EXCLUDED.name, generic = EXCLUDED.generic,
              form = EXCLUDED.form, unit = EXCLUDED.unit, price = EXCLUDED.price, cost = EXCLUDED.cost, disc_type = EXCLUDED.disc_type,
              disc_value = EXCLUDED.disc_value, qty = EXCLUDED.qty, low_at = EXCLUDED.low_at, expiry = EXCLUDED.expiry,
              last_sale_at = EXCLUDED.last_sale_at, created_at = EXCLUDED.created_at, updated_at = EXCLUDED.updated_at
            """.trimIndent(),
            uid, x.id, x.key.ifEmpty { RxMath.key(x.name) }, x.name, x.generic, x.form, x.unit, x.price.bd(), x.cost.bd(),
            x.disc?.type, x.disc?.v.bd(), x.qty.bd(), x.lowAt.bd(), x.expiry, x.lastSaleAt?.let { Timestamp(it) },
            Timestamp(x.createdAt), Timestamp(x.updatedAt),
        )
    }

    /** a new product only when its id is still free (loadItems: never over a product made meanwhile) */
    fun insertIfAbsent(uid: String, x: ShopItem): Boolean = jdbc.update(
        """
        INSERT INTO shop_items (shop_uid, id, key, name, generic, form, unit, created_at, updated_at)
        VALUES (?, ?, ?, ?, '', '', '', ?, ?) ON CONFLICT (shop_uid, id) DO NOTHING
        """.trimIndent(),
        uid, x.id, x.key, x.name, Timestamp(x.createdAt), Timestamp(x.updatedAt),
    ) > 0

    fun deleteItem(uid: String, id: String) {
        jdbc.update("DELETE FROM shop_items WHERE shop_uid = ? AND id = ?", uid, id)
    }

    // ------------------------------------------------------------------ day aggregates

    /** shopbooks.js dayAdd: every number is an increment (r2), names are set */
    fun dayAdd(uid: String, day: String, d: BooksMath.DayDelta) {
        val r = BooksMath::r2
        val date = Sql.date(BooksMath.dayOfKey(day))
        jdbc.update(
            """
            INSERT INTO shop_days (shop_uid, day, sales, purchases, adjusts, gross, discount, net, nc, cost, profit, bought, write_off, woq, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (shop_uid, day) DO UPDATE SET sales = shop_days.sales + EXCLUDED.sales,
              purchases = shop_days.purchases + EXCLUDED.purchases, adjusts = shop_days.adjusts + EXCLUDED.adjusts,
              gross = shop_days.gross + EXCLUDED.gross, discount = shop_days.discount + EXCLUDED.discount,
              net = shop_days.net + EXCLUDED.net, nc = shop_days.nc + EXCLUDED.nc, cost = shop_days.cost + EXCLUDED.cost,
              profit = shop_days.profit + EXCLUDED.profit, bought = shop_days.bought + EXCLUDED.bought,
              write_off = shop_days.write_off + EXCLUDED.write_off, woq = shop_days.woq + EXCLUDED.woq, updated_at = now()
            """.trimIndent(),
            uid, date, Math.round(d.sales).toInt(), Math.round(d.purchases).toInt(), Math.round(d.adjusts).toInt(),
            r(d.gross).bd(), r(d.discount).bd(), r(d.net).bd(), r(d.nc).bd(), r(d.cost).bd(), r(d.profit).bd(), r(d.bought).bd(),
            r(d.writeOff).bd(), r(d.woq).bd(),
        )
        for ((source, v) in d.src) {
            jdbc.update(
                """
                INSERT INTO shop_day_sources (shop_uid, day, source, n, net) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (shop_uid, day, source) DO UPDATE SET n = shop_day_sources.n + EXCLUDED.n, net = shop_day_sources.net + EXCLUDED.net
                """.trimIndent(),
                uid, date, source, Math.round(v.first).toInt(), r(v.second).bd(),
            )
        }
        for ((id, v) in d.items) {
            jdbc.update(
                """
                INSERT INTO shop_day_items (shop_uid, day, item_key, name, q, net, profit, neg, nq) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (shop_uid, day, item_key) DO UPDATE SET name = EXCLUDED.name, q = shop_day_items.q + EXCLUDED.q,
                  net = shop_day_items.net + EXCLUDED.net, profit = shop_day_items.profit + EXCLUDED.profit,
                  neg = shop_day_items.neg + EXCLUDED.neg, nq = shop_day_items.nq + EXCLUDED.nq
                """.trimIndent(),
                uid, date, id, v.name, r(v.q).bd(), r(v.net).bd(), r(v.profit).bd(), r(v.neg).bd(), r(v.nq).bd(),
            )
        }
    }

    /** the day documents of [days] (null where nothing happened) */
    fun days(uid: String, days: List<String>): List<BooksMath.DayDoc?> {
        if (days.isEmpty()) return emptyList()
        val dates = days.map { Sql.date(BooksMath.dayOfKey(it)) }
        val q = dates.joinToString(",") { "?" }
        val heads = HashMap<String, MutableMap<String, Any?>>()
        jdbc.query(
            "SELECT * FROM shop_days WHERE shop_uid = ? AND day IN ($q)",
            RowCallbackHandler { rs ->
                heads[BooksMath.dayKeyOf(rs.getObject("day", java.time.LocalDate::class.java))] = linkedMapOf(
                    "sales" to rs.getInt("sales").toDouble(), "purchases" to rs.getInt("purchases").toDouble(),
                    "gross" to rs.dec("gross"), "discount" to rs.dec("discount"), "net" to rs.dec("net"), "nc" to rs.dec("nc"),
                    "cost" to rs.dec("cost"), "profit" to rs.dec("profit"), "bought" to rs.dec("bought"), "writeOff" to rs.dec("write_off"),
                )
            },
            uid, *dates.toTypedArray(),
        )
        val src = HashMap<String, MutableMap<String, Pair<Double, Double>>>()
        jdbc.query(
            "SELECT day, source, n, net FROM shop_day_sources WHERE shop_uid = ? AND day IN ($q)",
            RowCallbackHandler { rs ->
                src.getOrPut(BooksMath.dayKeyOf(rs.getObject("day", java.time.LocalDate::class.java))) { LinkedHashMap() }[rs.getString("source")] =
                    rs.getInt("n").toDouble() to (rs.dec("net") ?: 0.0)
            },
            uid, *dates.toTypedArray(),
        )
        val items = HashMap<String, LinkedHashMap<String, BooksMath.DayItem>>()
        jdbc.query(
            "SELECT * FROM shop_day_items WHERE shop_uid = ? AND day IN ($q) ORDER BY day, item_key",
            RowCallbackHandler { rs ->
                items.getOrPut(BooksMath.dayKeyOf(rs.getObject("day", java.time.LocalDate::class.java))) { LinkedHashMap() }[rs.getString("item_key")] =
                    BooksMath.DayItem(
                        rs.getString("name") ?: "", rs.dec("q") ?: 0.0, rs.dec("net") ?: 0.0, rs.dec("profit") ?: 0.0,
                        rs.dec("neg") ?: 0.0, rs.dec("nq") ?: 0.0,
                    )
            },
            uid, *dates.toTypedArray(),
        )
        return days.map { k ->
            val h = heads[k] ?: return@map null
            fun v(n: String) = (h[n] as Double?) ?: 0.0
            BooksMath.DayDoc(
                v("sales"), v("gross"), v("discount"), v("net"), v("cost"), v("profit"), v("nc"), v("writeOff"), v("bought"), v("purchases"),
                src[k].orEmpty(), items[k].orEmpty(),
            )
        }
    }

    // ------------------------------------------------------------------ purchases

    data class PurchaseLine(val itemId: String, val name: String, val qty: Double, val unitCost: Double?, val expiry: String?, val total: Double?) {
        fun toMap(): Map<String, Any?> = linkedMapOf(
            "itemId" to itemId, "name" to name, "qty" to qty, "unitCost" to unitCost, "expiry" to expiry, "total" to total,
        )
    }

    data class Undo(
        val qty: Double?,
        val cost: Double?,
        val price: Double?,
        val expiry: String?,
        val inStock: Boolean,
        val afterCost: Double? = null,
        val afterPrice: Double? = null,
        val afterExpiry: String? = null,
    )

    data class Purchase(
        val id: String,
        val at: Long,
        val day: String,
        val supplier: String,
        val note: String,
        val lines: List<PurchaseLine>,
        val total: Double,
        val createdAt: Long,
        val undo: Map<String, Undo> = emptyMap(),
    ) {
        fun toMap(): Map<String, Any?> = linkedMapOf(
            "id" to id, "at" to at, "day" to day, "supplier" to supplier, "note" to note, "lines" to lines.map { it.toMap() },
            "total" to total, "createdAt" to createdAt,
        )
    }

    fun insertPurchase(uid: String, p: Purchase) {
        jdbc.update(
            "INSERT INTO shop_purchases (shop_uid, id, at, day, supplier, note, total, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            uid, p.id, Timestamp(p.at), Sql.date(BooksMath.dayOfKey(p.day)), p.supplier, p.note, p.total.bd(), Timestamp(p.createdAt),
        )
        jdbc.batchUpdate(
            "INSERT INTO shop_purchase_lines (shop_uid, purchase_id, idx, item_id, name, qty, unit_cost, expiry, total) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            p.lines.mapIndexed { i, l -> arrayOf<Any?>(uid, p.id, i, l.itemId, l.name, l.qty.bd(), l.unitCost.bd(), l.expiry, l.total.bd()) },
        )
        jdbc.batchUpdate(
            """
            INSERT INTO shop_purchase_undo (shop_uid, purchase_id, item_id, qty, cost, price, expiry, in_stock, after_cost, after_price, after_expiry)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            p.undo.map { (id, u) ->
                arrayOf<Any?>(uid, p.id, id, u.qty.bd(), u.cost.bd(), u.price.bd(), u.expiry, u.inStock, u.afterCost.bd(), u.afterPrice.bd(), u.afterExpiry)
            },
        )
    }

    private fun purchaseLines(uid: String, ids: List<String>): Map<String, List<PurchaseLine>> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<String, MutableList<PurchaseLine>>()
        jdbc.query(
            "SELECT * FROM shop_purchase_lines WHERE shop_uid = ? AND purchase_id IN (${ids.joinToString(",") { "?" }}) ORDER BY purchase_id, idx",
            RowCallbackHandler { rs ->
                out.getOrPut(rs.getString("purchase_id")) { ArrayList() }.add(
                    PurchaseLine(rs.getString("item_id") ?: "", rs.getString("name"), rs.dec("qty") ?: 0.0, rs.dec("unit_cost"), rs.getString("expiry"), rs.dec("total")),
                )
            },
            uid, *ids.toTypedArray(),
        )
        return out
    }

    private fun purchaseHead(rs: ResultSet) = Purchase(
        rs.getString("id"), rs.getTimestamp("at").time, BooksMath.dayKeyOf(rs.getObject("day", java.time.LocalDate::class.java)),
        rs.getString("supplier"), rs.getString("note"), emptyList(), rs.dec("total") ?: 0.0, rs.ms("created_at") ?: 0,
    )

    fun purchases(uid: String, since: Long, limit: Int): List<Purchase> {
        val heads = jdbc.query(
            "SELECT * FROM shop_purchases WHERE shop_uid = ? AND at >= ? ORDER BY at DESC LIMIT ?",
            { rs, _ -> purchaseHead(rs) }, uid, Timestamp(since), limit,
        )
        val lines = purchaseLines(uid, heads.map { it.id })
        return heads.map { it.copy(lines = lines[it.id].orEmpty()) }
    }

    fun purchase(uid: String, id: String): Purchase? {
        val head = jdbc.query("SELECT * FROM shop_purchases WHERE shop_uid = ? AND id = ? FOR UPDATE", { rs, _ -> purchaseHead(rs) }, uid, id)
            .firstOrNull() ?: return null
        val undo = LinkedHashMap<String, Undo>()
        jdbc.query(
            "SELECT * FROM shop_purchase_undo WHERE shop_uid = ? AND purchase_id = ?",
            RowCallbackHandler { rs ->
                undo[rs.getString("item_id")] = Undo(
                    rs.dec("qty"), rs.dec("cost"), rs.dec("price"), rs.getString("expiry"), rs.getBoolean("in_stock"),
                    rs.dec("after_cost"), rs.dec("after_price"), rs.getString("after_expiry"),
                )
            },
            uid, id,
        )
        return head.copy(lines = purchaseLines(uid, listOf(id))[id].orEmpty(), undo = undo)
    }

    fun deletePurchase(uid: String, id: String) {
        jdbc.update("DELETE FROM shop_purchases WHERE shop_uid = ? AND id = ?", uid, id)
    }

    // ------------------------------------------------------------------ sales

    data class RxInfo(val rxId: String, val code: String, val at: Long) {
        fun toMap(): Map<String, Any?> = linkedMapOf("rxId" to rxId, "code" to code, "at" to at)
    }

    data class Sale(
        val id: String,
        val at: Long,
        val day: String,
        val source: String,
        val lines: List<BooksMath.SaleLine>,
        val totals: BooksMath.Totals,
        val billDisc: Disc?,
        val rx: RxInfo?,
        val customer: String,
        val createdAt: Long,
    ) {
        fun toMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
            "id" to id, "at" to at, "day" to day, "source" to source, "lines" to lines.map { it.toMap() }, "totals" to totals.toMap(),
            "billDisc" to billDisc?.toMap(),
        ).apply {
            if (rx != null) put("rx", rx.toMap())
            put("customer", customer)
            put("createdAt", createdAt)
        }
    }

    fun saleExists(uid: String, id: String): Boolean =
        jdbc.queryForList("SELECT 1 FROM shop_sales WHERE shop_uid = ? AND id = ?", uid, id).isNotEmpty()

    fun insertSale(uid: String, s: Sale) {
        val t = s.totals
        jdbc.update(
            """
            INSERT INTO shop_sales (shop_uid, id, at, day, source, customer, bill_disc_type, bill_disc_value, rx_code, rx_at, rx_id,
              gross, discount, bill_discount, net, cost, profit, net_costed, unpriced, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            uid, s.id, Timestamp(s.at), Sql.date(BooksMath.dayOfKey(s.day)), s.source, s.customer, s.billDisc?.type, s.billDisc?.v.bd(),
            s.rx?.code, s.rx?.at, s.rx?.rxId, t.gross.bd(), t.discount.bd(), t.billDiscount.bd(), t.net.bd(), t.cost.bd(), t.profit.bd(),
            t.netCosted.bd(), t.unpriced, Timestamp(s.createdAt),
        )
        jdbc.batchUpdate(
            """
            INSERT INTO shop_sale_lines (shop_uid, sale_id, idx, item_id, name, qty, unit_price, unit_disc, discount, net, unit_cost, cost, profit,
              med_idx, no_item, dec)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            s.lines.mapIndexed { i, l ->
                arrayOf<Any?>(
                    uid, s.id, i, l.itemId, l.name, l.qty.bd(), l.unitPrice.bd(), l.unitDisc.bd(), l.discount.bd(), l.net.bd(),
                    l.unitCost.bd(), l.cost.bd(), l.profit.bd(), l.i, l.noItem, l.dec.bd(),
                )
            },
        )
    }

    private fun saleHead(rs: ResultSet): Sale {
        val bt = rs.getString("bill_disc_type")
        val bv = rs.dec("bill_disc_value")
        val code = rs.getString("rx_code")
        return Sale(
            id = rs.getString("id"),
            at = rs.getTimestamp("at").time,
            day = BooksMath.dayKeyOf(rs.getObject("day", java.time.LocalDate::class.java)),
            source = rs.getString("source"),
            lines = emptyList(),
            totals = BooksMath.Totals(
                rs.dec("gross") ?: 0.0, rs.dec("discount") ?: 0.0, rs.dec("bill_discount") ?: 0.0, rs.dec("net") ?: 0.0,
                rs.dec("cost") ?: 0.0, rs.dec("profit") ?: 0.0, rs.dec("net_costed") ?: 0.0, rs.getInt("unpriced"),
            ),
            billDisc = if (bt != null && bv != null) Disc(bt, bv) else null,
            rx = if (code != null) RxInfo(rs.getString("rx_id") ?: "", code, rs.getLong("rx_at")) else null,
            customer = rs.getString("customer") ?: "",
            createdAt = rs.ms("created_at") ?: 0,
        )
    }

    private fun saleLines(uid: String, ids: List<String>): Map<String, List<BooksMath.SaleLine>> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<String, MutableList<BooksMath.SaleLine>>()
        jdbc.query(
            "SELECT * FROM shop_sale_lines WHERE shop_uid = ? AND sale_id IN (${ids.joinToString(",") { "?" }}) ORDER BY sale_id, idx",
            RowCallbackHandler { rs ->
                out.getOrPut(rs.getString("sale_id")) { ArrayList() }.add(
                    BooksMath.SaleLine(
                        rs.getString("item_id") ?: "", rs.getString("name"), rs.dec("qty") ?: 0.0, rs.dec("unit_price"),
                        rs.dec("unit_disc") ?: 0.0, rs.dec("discount") ?: 0.0, rs.dec("net") ?: 0.0, rs.dec("unit_cost"),
                        rs.dec("cost"), rs.dec("profit"), rs.getInt("med_idx").takeUnless { rs.wasNull() },
                        rs.getBoolean("no_item"), rs.dec("dec"),
                    ),
                )
            },
            uid, *ids.toTypedArray(),
        )
        return out
    }

    fun sales(uid: String, since: Long, limit: Int): List<Sale> {
        val heads = jdbc.query(
            "SELECT * FROM shop_sales WHERE shop_uid = ? AND at >= ? ORDER BY at DESC LIMIT ?",
            { rs, _ -> saleHead(rs) }, uid, Timestamp(since), limit,
        )
        val lines = saleLines(uid, heads.map { it.id })
        return heads.map { it.copy(lines = lines[it.id].orEmpty()) }
    }

    fun sale(uid: String, id: String): Sale? {
        val head = jdbc.query("SELECT * FROM shop_sales WHERE shop_uid = ? AND id = ? FOR UPDATE", { rs, _ -> saleHead(rs) }, uid, id)
            .firstOrNull() ?: return null
        return head.copy(lines = saleLines(uid, listOf(id))[id].orEmpty())
    }

    fun deleteSale(uid: String, id: String) {
        jdbc.update("DELETE FROM shop_sales WHERE shop_uid = ? AND id = ?", uid, id)
    }

    // ------------------------------------------------------------------ adjustments

    data class Adjust(
        val id: String,
        val kind: String,
        val itemId: String,
        val name: String,
        val before: Double?,
        val after: Double?,
        val delta: Double,
        val unitCost: Double?,
        val loss: Double,
        val reason: String,
        val at: Long,
        val day: String,
        val createdAt: Long,
    ) {
        fun toMap(): Map<String, Any?> = linkedMapOf(
            "id" to id, "kind" to kind, "itemId" to itemId, "name" to name, "before" to before, "after" to after, "delta" to delta,
            "unitCost" to unitCost, "loss" to loss, "reason" to reason, "at" to at, "day" to day, "createdAt" to createdAt,
        )
    }

    fun insertAdjust(uid: String, a: Adjust) {
        jdbc.update(
            """
            INSERT INTO shop_adjustments (shop_uid, id, at, day, kind, item_id, name, qty_before, qty_after, delta, unit_cost, loss, reason, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            uid, a.id, Timestamp(a.at), Sql.date(BooksMath.dayOfKey(a.day)), a.kind, a.itemId, a.name, a.before.bd(), a.after.bd(),
            a.delta.bd(), a.unitCost.bd(), a.loss.bd(), a.reason, Timestamp(a.createdAt),
        )
    }

    fun adjusts(uid: String, since: Long, limit: Int): List<Adjust> = jdbc.query(
        "SELECT * FROM shop_adjustments WHERE shop_uid = ? AND at >= ? ORDER BY at DESC LIMIT ?",
        { rs, _ ->
            Adjust(
                rs.getString("id"), rs.getString("kind"), rs.getString("item_id"), rs.getString("name"), rs.dec("qty_before"),
                rs.dec("qty_after"), rs.dec("delta") ?: 0.0, rs.dec("unit_cost"), rs.dec("loss") ?: 0.0, rs.getString("reason"),
                rs.getTimestamp("at").time, BooksMath.dayKeyOf(rs.getObject("day", java.time.LocalDate::class.java)), rs.ms("created_at") ?: 0,
            )
        },
        uid, Timestamp(since), limit,
    )

    /** shopbooks.js deleteFor: the products and the whole books of a shop (account deletion) */
    fun deleteFor(uid: String) {
        for (t in listOf("shop_sales", "shop_purchases", "shop_adjustments", "shop_days", "shop_items")) {
            jdbc.update("DELETE FROM $t WHERE shop_uid = ?", uid)
        }
    }
}
