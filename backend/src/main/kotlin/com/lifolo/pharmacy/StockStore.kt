package com.lifolo.pharmacy

import com.lifolo.common.ApiException
import com.lifolo.common.Lang
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.stereotype.Component
import java.text.Collator
import java.util.Locale

/** one product key of `pharmacyStock/{uid}`: in the names list (in stock) and/or with a patient price (+ MRP) */
data class StockRow(val name: String, val inStock: Boolean, val price: Double?, val mrp: Double?)

/**
 * `pharmacyStock/{uid}` {names[], keys[], prices{key}, mrp{key}, pricesAll, mrpAll, updatedAt} as rows
 * (`pharmacy_stock` + `pharmacy_stock_items`). [names] is the in-stock list sorted like `localeCompare`.
 */
class StockSnap(
    val exists: Boolean,
    val rows: Map<String, StockRow>,
    val pricesAll: Boolean,
    val mrpAll: Boolean,
    val updatedAt: Long,
) {
    val names: List<String> by lazy { rows.values.filter { it.inStock }.map { it.name }.sortedWith(NAME_ORDER) }
    val keys: Set<String> by lazy { rows.filter { it.value.inStock }.keys }
    val prices: Map<String, Double> by lazy { rows.filter { it.value.price != null }.mapValues { it.value.price!! } }
    val mrp: Map<String, Double> by lazy { rows.filter { it.value.mrp != null }.mapValues { it.value.mrp!! } }

    /** `st.get('prices') || null` */
    val pricesOrNull: Map<String, Double>? get() = prices

    companion object {
        val EMPTY = StockSnap(false, emptyMap(), false, false, 0)

        /** `a.localeCompare(b)` (ICU root order; Java's collator is the closest built-in) */
        val NAME_ORDER: Comparator<String> = Collator.getInstance(Locale.ENGLISH).let { c -> Comparator { a, b -> c.compare(a, b) } }
    }
}

/**
 * Reads and writes a shop's stock list. Callers lock the shop first ([lockShop]) so stock_set (rxnet.js) and every
 * shop-books write (shopbooks.js stockWrite) see and change the list one at a time, like the Node transactions.
 */
@Component
class StockStore(private val jdbc: JdbcTemplate) {
    companion object {
        const val MAX_ITEMS = 3000
    }

    /** the shop's row lock (pharmacies/{uid}); false when there is no pharmacy */
    fun lockShop(uid: String): Boolean =
        jdbc.queryForList("SELECT uid FROM pharmacies WHERE uid = ? FOR UPDATE", uid).isNotEmpty()

    fun load(uid: String): StockSnap {
        val head = jdbc.queryForList("SELECT prices_all, mrp_all, updated_at FROM pharmacy_stock WHERE pharmacy_uid = ?", uid).firstOrNull()
            ?: return StockSnap.EMPTY
        val rows = LinkedHashMap<String, StockRow>()
        jdbc.query(
            "SELECT key, name, in_stock, price, mrp FROM pharmacy_stock_items WHERE pharmacy_uid = ?",
            RowCallbackHandler { rs ->
                rows[rs.getString("key")] = StockRow(
                    rs.getString("name"), rs.getBoolean("in_stock"),
                    rs.getBigDecimal("price")?.toDouble(), rs.getBigDecimal("mrp")?.toDouble(),
                )
            },
            uid,
        )
        return StockSnap(
            true, rows, head["prices_all"] as Boolean, head["mrp_all"] as Boolean,
            (head["updated_at"] as java.sql.Timestamp).time,
        )
    }

    /** stock lists of many shops at once (rx options, pharmacy list with a prescription) */
    fun loadAll(uids: Collection<String>): Map<String, StockSnap> {
        if (uids.isEmpty()) return emptyMap()
        val ids = uids.distinct()
        val q = ids.joinToString(",") { "?" }
        val heads = HashMap<String, Pair<Boolean, Boolean>>()
        val times = HashMap<String, Long>()
        jdbc.query(
            "SELECT pharmacy_uid, prices_all, mrp_all, updated_at FROM pharmacy_stock WHERE pharmacy_uid IN ($q)",
            RowCallbackHandler { rs ->
                heads[rs.getString(1)] = rs.getBoolean(2) to rs.getBoolean(3)
                times[rs.getString(1)] = rs.getTimestamp(4).time
            },
            *ids.toTypedArray(),
        )
        val rows = HashMap<String, LinkedHashMap<String, StockRow>>()
        if (heads.isNotEmpty()) {
            val have = heads.keys.toList()
            jdbc.query(
                "SELECT pharmacy_uid, key, name, in_stock, price, mrp FROM pharmacy_stock_items WHERE pharmacy_uid IN (${have.joinToString(",") { "?" }})",
                RowCallbackHandler { rs ->
                    rows.getOrPut(rs.getString(1)) { LinkedHashMap() }[rs.getString(2)] = StockRow(
                        rs.getString(3), rs.getBoolean(4), rs.getBigDecimal(5)?.toDouble(), rs.getBigDecimal(6)?.toDouble(),
                    )
                },
                *have.toTypedArray(),
            )
        }
        return ids.associateWith { id ->
            val h = heads[id] ?: return@associateWith StockSnap.EMPTY
            StockSnap(true, rows[id].orEmpty(), h.first, h.second, times[id] ?: 0)
        }
    }

    /**
     * Writes the whole document (Node `t.set(stockRef, {...})`): the in-stock [names], [prices] and [mrp] by key,
     * the two flags. Only rows that changed are touched.
     */
    fun save(uid: String, before: StockSnap, names: Collection<String>, prices: Map<String, Double>, mrp: Map<String, Double>, pricesAll: Boolean, mrpAll: Boolean) {
        val want = LinkedHashMap<String, StockRow>()
        for (n in names) {
            val k = RxMath.key(n)
            if (k !in want) want[k] = StockRow(n, true, prices[k], mrp[k])
        }
        for ((k, p) in prices) {
            if (k in want) continue
            want[k] = StockRow(before.rows[k]?.name ?: k, false, p, mrp[k])
        }
        jdbc.update(
            """
            INSERT INTO pharmacy_stock (pharmacy_uid, prices_all, mrp_all, updated_at) VALUES (?, ?, ?, now())
            ON CONFLICT (pharmacy_uid) DO UPDATE SET prices_all = EXCLUDED.prices_all, mrp_all = EXCLUDED.mrp_all, updated_at = now()
            """.trimIndent(),
            uid, pricesAll, mrpAll,
        )
        val gone = before.rows.keys.filter { it !in want }
        if (gone.isNotEmpty()) {
            jdbc.batchUpdate("DELETE FROM pharmacy_stock_items WHERE pharmacy_uid = ? AND key = ?", gone.map { arrayOf<Any?>(uid, it) })
        }
        val changed = want.filter { (k, r) -> before.rows[k] != r }
        if (changed.isNotEmpty()) {
            jdbc.batchUpdate(
                """
                INSERT INTO pharmacy_stock_items (pharmacy_uid, key, name, in_stock, price, mrp) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (pharmacy_uid, key) DO UPDATE SET name = EXCLUDED.name, in_stock = EXCLUDED.in_stock,
                  price = EXCLUDED.price, mrp = EXCLUDED.mrp
                """.trimIndent(),
                changed.map { (k, r) -> arrayOf<Any?>(uid, k, r.name, r.inStock, r.price?.toBigDecimal(), r.mrp?.toBigDecimal()) },
            )
        }
    }

    /** a price change of one product key: the patient price (null = remove) and its MRP when the shop gives a discount */
    data class PriceChange(val sale: Double?, val mrp: Double?)

    /**
     * shopbooks.js stockWrite: add/remove names, and price changes (null value = price removed); other prices stay.
     * Nothing is written when nothing changed. 400 "Up to 3000 medicines" only when the list grows past the limit.
     */
    fun stockWrite(lang: String?, uid: String, snap: StockSnap, add: List<String>, remove: List<String>, prices: Map<String, PriceChange?>? = null) {
        val cur = snap.names
        val map = LinkedHashMap<String, String>()
        cur.forEach { map[RxMath.key(it)] = it }
        var changed = false
        for (n in add) if (RxMath.key(n) !in map) {
            map[RxMath.key(n)] = n
            changed = true
        }
        for (n in remove) if (map.remove(RxMath.key(n)) != null) changed = true
        val nextPrices = LinkedHashMap(snap.prices)
        val nextMrp = LinkedHashMap(snap.mrp)
        var pChanged = false
        for ((k, v0) in prices.orEmpty()) {
            val v = v0?.sale
            val m = if (v0 != null && v != null) v0.mrp else null
            if (v == null) {
                if (nextPrices.remove(k) != null) pChanged = true
            } else if (nextPrices[k] != v) {
                nextPrices[k] = v
                pChanged = true
            }
            if (m == null) {
                if (nextMrp.remove(k) != null) pChanged = true
            } else if (nextMrp[k] != m) {
                nextMrp[k] = m
                pChanged = true
            }
        }
        if (!changed && !pChanged) return
        val list = if (changed) map.values.sortedWith(StockSnap.NAME_ORDER) else cur
        if (list.size > MAX_ITEMS && list.size > cur.size) {
            throw ApiException(400, Lang.tr(lang, "Up to $MAX_ITEMS medicines", "সর্বোচ্চ ${MAX_ITEMS}টা ওষুধ"))
        }
        val capped = nextPrices.filter { (k, v) -> k.isNotEmpty() && !Regex("^__.*__$").matches(k) && v > 0 }
        val mrpAll = if (snap.exists) snap.mrpAll || snap.prices.isEmpty() else true
        save(uid, snap, list, capped, mrpsFor(nextMrp, capped), snap.pricesAll, mrpAll)
    }

    /** shopbooks.js mrpsFor: MRP only for keys with a price, and only above it */
    fun mrpsFor(mrps: Map<String, Double>, prices: Map<String, Double>): Map<String, Double> =
        mrps.filter { (k, v) -> prices[k]?.let { v > it } == true }
}
