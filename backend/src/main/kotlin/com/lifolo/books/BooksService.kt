package com.lifolo.books

import com.fasterxml.jackson.databind.JsonNode
import com.lifolo.books.BooksMath.MAX_ITEMS
import com.lifolo.books.BooksMath.MAX_LINES
import com.lifolo.books.BooksMath.r2
import com.lifolo.common.Ids
import com.lifolo.common.Js
import com.lifolo.common.Rules
import com.lifolo.auth.AuthUser
import com.lifolo.common.Body
import com.lifolo.common.action.ActionContext
import com.lifolo.common.action.CallCtx
import com.lifolo.contracts.Prescription
import com.lifolo.contracts.Prescriptions
import com.lifolo.contracts.RxDispensing
import com.lifolo.pharmacy.P4Clock
import com.lifolo.pharmacy.RxMath
import com.lifolo.pharmacy.StockSnap
import com.lifolo.pharmacy.StockStore
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * shopbooks.js — the pharmacy's own books: products (all optional but the name), purchases (average cost),
 * counter / prescription / online sales with line and bill discounts, write-offs and stock counts, the day
 * aggregates and the P&L report. Only the pharmacy owner. Every write keeps the stock name list
 * (`pharmacy_stock`) and the day aggregates in step in the same transaction.
 */
@Service
class BooksService(
    private val jdbc: JdbcTemplate,
    private val store: BooksStore,
    private val stock: StockStore,
    private val prescriptions: Prescriptions,
    private val clock: P4Clock,
) {
    private fun notFound(ctx: CallCtx) = ctx.err(404, "Not found", "পাওয়া যায়নি")

    private fun bad(ctx: CallCtx, en: String, bn: String) = ctx.err(400, en, bn)

    private fun myShop(ctx: ActionContext, lock: Boolean): String = myShopOf(ctx, ctx.user, lock)

    /** shopbooks.js myShop (+ the shop lock for writes) */
    fun myShopOf(ctx: CallCtx, user: AuthUser, lock: Boolean): String {
        val uid = user.uid
        if (user.guest || !Rules.isValidId(uid)) throw ctx.err(401, "Please log in", "লগইন করুন")
        val exists = if (lock) stock.lockShop(uid!!) else jdbc.queryForList("SELECT 1 FROM pharmacies WHERE uid = ?", uid).isNotEmpty()
        if (!exists) throw ctx.err(403, "Add your pharmacy first", "আগে আপনার ফার্মেসি যোগ করুন")
        return uid!!
    }

    // ------------------------------------------------------------------ input checks

    /** shopbooks.js numOf: money/quantity — empty = null; negative or too big → 400 */
    private fun numOf(ctx: CallCtx, v: JsonNode?, en: String, bn: String, max: Double = 1e7): Double? {
        if (v == null || v.isNull || (v.isTextual && v.textValue() == "")) return null
        val n = if (v.isNumber) v.doubleValue() else RxMath.jsNumber(Js.template(v).replace(",", ""))
        if (!n.isFinite() || n < 0 || n > max) throw bad(ctx, "Check the $en", "$bn দেখে নিন")
        return r2(n)
    }

    private fun qtyOf(ctx: CallCtx, v: JsonNode?) = numOf(ctx, v, "quantity", "পরিমাণ", 100000.0)

    private fun moneyOf(ctx: CallCtx, v: JsonNode?, en: String = "price", bn: String = "দাম") = numOf(ctx, v, en, bn)

    /** `Number(v)` of any JSON value */
    private fun jsNum(v: JsonNode): Double = if (v.isBoolean) (if (v.booleanValue()) 1.0 else 0.0) else RxMath.jsNumber(Js.template(v))

    /** shopbooks.js discOf: {type: 'pct'|'amt', v} or null */
    private fun discOf(ctx: CallCtx, d: JsonNode?): Disc? {
        if (d == null || d.isNull || (d.isTextual && d.textValue() == "")) return null
        if (d.isContainerNode) {
            val v = d.get("v")
            if (v == null || v.isNull || (v.isTextual && v.textValue() == "") || jsNum(v) == 0.0) return null
        }
        val type = if (d.isObject) Js.string(d.get("type")) else null
        if (!d.isObject || (type != "pct" && type != "amt")) throw bad(ctx, "Check the discount", "ছাড় দেখে নিন")
        val v = numOf(ctx, d.get("v"), "discount", "ছাড়", if (type == "pct") 100.0 else 1e7)
        return if (v != null && v != 0.0) Disc(type!!, v) else null
    }

    private val EXPIRY = Regex("^20\\d\\d-(0[1-9]|1[0-2])$")

    private fun expiryOf(ctx: CallCtx, v: JsonNode?): String? {
        if (v == null || v.isNull || (v.isTextual && v.textValue() == "")) return null
        if (!v.isTextual || !EXPIRY.matches(v.textValue())) throw bad(ctx, "Check the expiry (month/year)", "মেয়াদ (মাস/বছর) দেখে নিন")
        return v.textValue()
    }

    /** shopbooks.js atOf: now when not given; within the last 60 days (a date → noon that day) */
    private fun atOf(ctx: CallCtx, v: JsonNode?, now: Long): Long {
        if (v == null || v.isNull || (v.isTextual && v.textValue() == "")) return now
        var t: Double = if (v.isNumber) v.doubleValue() else Double.NaN
        if (v.isTextual && Regex("^\\d{4}-\\d\\d-\\d\\d$").matches(v.textValue())) {
            t = try {
                LocalDate.parse(v.textValue()).atTime(12, 0).toInstant(com.lifolo.common.DhakaTime.OFFSET).toEpochMilli().toDouble()
            } catch (e: Exception) {
                Double.NaN
            }
        }
        if (!t.isFinite()) throw bad(ctx, "Check the date", "তারিখ দেখে নিন")
        // far outside the window (e.g. 1e300): the same answer as below, without turning it into a calendar day
        if (t > now + BooksMath.DAY_MS || t < now - 61 * BooksMath.DAY_MS) {
            throw bad(ctx, "Date must be within the last 60 days", "তারিখ গত ৬০ দিনের মধ্যে হতে হবে")
        }
        val tl = t.toLong()
        if (BooksMath.dayKey(tl) == BooksMath.dayKey(now)) return minOf(tl, now)
        if (t > now || t < now - 60 * BooksMath.DAY_MS) throw bad(ctx, "Date must be within the last 60 days", "তারিখ গত ৬০ দিনের মধ্যে হতে হবে")
        return tl
    }

    // ------------------------------------------------------------------ products

    /**
     * shopbooks.js loadItems: all products; names on the old stock list that are not products yet become products
     * (never over an existing id — a renamed product keeps the old name's id).
     */
    private fun loadItems(uid: String, snap: StockSnap? = null): Pair<List<ShopItem>, StockSnap> {
        val st = snap ?: stock.load(uid)
        val rows = store.items(uid).toMutableList()
        val have = rows.map { it.key.ifEmpty { BooksMath.key(it.name) } }.toHashSet()
        val ids = rows.map { it.id }.toHashSet()
        val seen = HashSet<String>()
        val missing = st.names.filter { n ->
            val k = BooksMath.key(n)
            if (k in have || k in seen || BooksMath.idOf(n) in ids) return@filter false
            seen.add(k)
            true
        }.take(maxOf(0, MAX_ITEMS - rows.size))
        if (missing.isNotEmpty()) {
            val now = clock.now()
            for (n in missing) {
                val x = BooksMath.newItem(BooksMath.idOf(n), n, now)
                store.insertIfAbsent(uid, x)
            }
            val made = store.itemsById(uid, missing.map { BooksMath.idOf(it) }, forUpdate = false)
            rows.addAll(made.values.filter { it.id !in ids })
        }
        return rows to st
    }

    private val nameOrder = StockSnap.NAME_ORDER

    @Transactional
    fun items(ctx: ActionContext): Any {
        val uid = myShop(ctx, lock = false)
        val (rows, st) = loadItems(uid)
        val now = clock.now()
        return linkedMapOf(
            "items" to rows.map { BooksMath.itemView(it, st.keys, now) }.sortedWith { a, b -> nameOrder.compare(a["name"] as String, b["name"] as String) },
            "units" to BooksMath.UNITS,
        )
    }

    @Transactional
    fun itemSave(ctx: ActionContext): Any {
        val uid = myShop(ctx, lock = true)
        val b = ctx.body
        val name = BooksMath.str(b.string("name"), 100)
        if (name.isEmpty()) throw bad(ctx, "Write the product name", "পণ্যের নাম লিখুন")
        val idNode = b["id"]
        val editId: String? = if (idNode == null || idNode.isNull || (idNode.isTextual && idNode.textValue() == "")) null else Js.string(idNode) ?: "\u0000"
        if (editId != null && !Rules.isValidId(editId)) throw notFound(ctx)
        val price = moneyOf(ctx, b["price"], "sale price", "বিক্রি দাম")
        val disc = discOf(ctx, b["disc"])
        if (disc != null && disc.type == "amt" && price != null && disc.v > price) throw bad(ctx, "Discount is more than the price", "ছাড় দামের চেয়ে বেশি")
        val unit = b.string("unit")?.takeIf { it in BooksMath.UNITS } ?: ""
        val cost = moneyOf(ctx, b["cost"], "cost price", "কেনা দাম")
        val lowAt = qtyOf(ctx, b["lowAt"])
        val expiry = expiryOf(ctx, b["expiry"])
        val generic = BooksMath.str(b.string("generic"), 100)
        val form = BooksMath.str(b.string("form"), 40)
        val key = BooksMath.key(name)
        val qtyGiven = b.has("qty")
        val qty = if (qtyGiven) qtyOf(ctx, b["qty"]) else null
        val id = editId ?: BooksMath.idOf(name)
        if (store.itemsWithKey(uid, key, 2).any { it.id != id }) {
            throw ctx.err(409, "A product with this name is already in your list", "এই নামে আগেই একটা পণ্য আছে")
        }
        if (editId == null && store.count(uid) >= MAX_ITEMS) throw bad(ctx, "Up to $MAX_ITEMS products", "সর্বোচ্চ ${MAX_ITEMS}টা পণ্য")
        val now = clock.now()
        val s = store.item(uid, id)
        val st = stock.load(uid)
        if (editId != null && s == null) throw notFound(ctx)
        if (editId == null && s != null) throw ctx.err(409, "A product with this name is already in your list", "এই নামে আগেই একটা পণ্য আছে")
        val add = ArrayList<String>()
        val remove = ArrayList<String>()
        val next: ShopItem
        if (s == null) {
            next = ShopItem(id, name, key, generic, form, unit, qty, cost, price, disc, lowAt, expiry, null, now, now)
            if (next.qty == null || next.qty > 0) add.add(name)
            store.saveItem(uid, next)
        } else {
            next = s.copy(
                name = name, key = key, generic = generic, form = form, unit = unit, cost = cost, price = price, disc = disc,
                lowAt = lowAt, expiry = expiry, qty = if (qtyGiven) qty else s.qty, updatedAt = now,
            )
            if (s.key != key && s.key in st.keys) {
                remove.add(s.name)
                add.add(name)
            }
            if (qtyGiven && qty != s.qty) {
                BooksMath.stockChange(name, s.qty, qty, add, remove)
                if (qty != null) {
                    store.insertAdjust(
                        uid,
                        BooksStore.Adjust(
                            Ids.newId(), "count", id, name, s.qty, qty, r2(qty - (s.qty ?: 0.0)), s.cost, 0.0, "", now, BooksMath.dayKey(now), now,
                        ),
                    )
                    store.dayAdd(uid, BooksMath.dayKey(now), BooksMath.DayDelta(adjusts = 1.0))
                }
            }
            store.saveItem(uid, next)
        }
        val rem = remove.filter { it !in add }
        stock.stockWrite(ctx.lang, uid, st, add, rem, BooksMath.priceChange(LinkedHashMap(), s, next))
        val keys = HashSet(st.keys)
        add.forEach { keys.add(BooksMath.key(it)) }
        rem.forEach { keys.remove(BooksMath.key(it)) }
        return mapOf("item" to BooksMath.itemView(next, keys, now))
    }

    @Transactional
    fun itemDelete(ctx: ActionContext): Any {
        val uid = myShop(ctx, lock = true)
        val id = ctx.body.string("id")
        if (!Rules.isValidId(id)) throw notFound(ctx)
        val s = store.item(uid, id!!) ?: return mapOf("ok" to true)
        stock.stockWrite(ctx.lang, uid, stock.load(uid), emptyList(), listOf(s.name), BooksMath.priceChange(LinkedHashMap(), s, null))
        store.deleteItem(uid, id)
        return mapOf("ok" to true)
    }

    @Transactional
    fun itemStock(ctx: ActionContext): Any {
        val uid = myShop(ctx, lock = true)
        val id = ctx.body.string("id")
        val on = ctx.body.isTrue("on")
        if (!Rules.isValidId(id)) throw notFound(ctx)
        val s = store.item(uid, id!!) ?: throw notFound(ctx)
        val st = stock.load(uid)
        stock.stockWrite(ctx.lang, uid, st, if (on) listOf(s.name) else emptyList(), if (on) emptyList() else listOf(s.name))
        val keys = HashSet(st.keys)
        if (on) keys.add(BooksMath.key(s.name)) else keys.remove(BooksMath.key(s.name))
        return mapOf("item" to BooksMath.itemView(s, keys, clock.now()))
    }

    // ------------------------------------------------------------------ purchases

    @Transactional
    fun purchaseAdd(ctx: ActionContext): Any {
        val uid = myShop(ctx, lock = true)
        val b = ctx.body
        val now = clock.now()
        val at = atOf(ctx, b["at"], now)
        val raw = (b["lines"]?.takeIf { it.isArray }?.toList() ?: emptyList()).take(MAX_LINES + 1)
        if (raw.size > MAX_LINES) throw bad(ctx, "Up to $MAX_LINES lines", "সর্বোচ্চ ${MAX_LINES}টা লাইন")
        data class In(val id: String, val name: String, val qty: Double, val unitCost: Double?, val expiry: String?, val price: Double?)
        val lines = ArrayList<In>()
        for (l in raw) {
            val qty = qtyOf(ctx, l.get("qty"))
            if (qty == null || qty == 0.0) continue
            val nm = BooksMath.str(Js.string(l.get("name")), 100)
            val itemId = Js.string(l.get("itemId"))
            val id = if (Rules.isValidId(itemId)) itemId!! else if (nm.isNotEmpty()) BooksMath.idOf(nm) else continue
            lines.add(
                In(
                    id, nm, qty, moneyOf(ctx, l.get("unitCost"), "cost price", "কেনা দাম"), expiryOf(ctx, l.get("expiry")),
                    moneyOf(ctx, l.get("price"), "sale price", "বিক্রি দাম"),
                ),
            )
        }
        if (lines.isEmpty()) throw bad(ctx, "Add at least one product with quantity", "অন্তত একটা পণ্য আর পরিমাণ দিন")
        val ids = lines.map { it.id }.distinct()
        val found = store.itemsById(uid, ids)
        val st = stock.load(uid)
        val cur = LinkedHashMap<String, ShopItem?>()
        ids.forEach { cur[it] = found[it] }
        val keys = st.keys
        val add = ArrayList<String>()
        val remove = ArrayList<String>()
        val saved = ArrayList<BooksStore.PurchaseLine>()
        val undo = LinkedHashMap<String, BooksStore.Undo>()
        for (l in lines) {
            var x = cur[l.id]
            if (l.id !in undo) {
                undo[l.id] = if (x != null) {
                    BooksStore.Undo(x.qty, x.cost, x.price, x.expiry, x.key.ifEmpty { BooksMath.key(x.name) } in keys)
                } else {
                    BooksStore.Undo(0.0, null, null, null, false)
                }
            }
            if (x == null) {
                if (l.name.isEmpty()) throw notFound(ctx)
                x = BooksMath.newItem(l.id, l.name, now)
            }
            val before = x.qty
            val have = before ?: 0.0
            val q = r2(have + l.qty)
            val cost = when {
                l.unitCost == null -> x.cost
                x.cost != null && have > 0 -> r2((have * x.cost + l.qty * l.unitCost) / q)
                else -> l.unitCost
            }
            val expiry = if (l.expiry != null) (if (x.expiry != null && have > 0 && x.expiry < l.expiry) x.expiry else l.expiry) else x.expiry
            BooksMath.stockChange(x.name, before, q, add, remove)
            x = x.copy(qty = q, cost = cost, expiry = expiry, price = l.price ?: x.price, updatedAt = now)
            cur[l.id] = x
            saved.add(BooksStore.PurchaseLine(l.id, x.name, l.qty, l.unitCost, l.expiry, l.unitCost?.let { r2(l.qty * it) }))
        }
        val prices = LinkedHashMap<String, StockStore.PriceChange?>()
        for (id in ids) {
            val x = cur[id]!!
            store.saveItem(uid, x)
            undo[id] = undo[id]!!.copy(afterCost = x.cost, afterPrice = x.price, afterExpiry = x.expiry)
            BooksMath.priceChange(prices, null, x)
        }
        stock.stockWrite(ctx.lang, uid, st, add, remove, prices)
        val p = BooksStore.Purchase(
            Ids.newId(), at, BooksMath.dayKey(at), BooksMath.str(b.string("supplier"), 80), BooksMath.str(b.string("note"), 200), saved,
            r2(saved.sumOf { it.total ?: 0.0 }), now, undo,
        )
        store.insertPurchase(uid, p)
        store.dayAdd(uid, p.day, BooksMath.DayDelta(purchases = 1.0, bought = p.total))
        val shown = p.toMap()
        return linkedMapOf("id" to p.id, "purchase" to shown)
    }

    @Transactional(readOnly = true)
    fun purchases(ctx: ActionContext): Any {
        val uid = myShop(ctx, lock = false)
        return mapOf("items" to store.purchases(uid, clock.now() - 90 * BooksMath.DAY_MS, 200).map { it.toMap() })
    }

    @Transactional
    fun purchaseDelete(ctx: ActionContext): Any {
        val uid = myShop(ctx, lock = true)
        val id = ctx.body.string("id")
        if (!Rules.isValidId(id)) throw notFound(ctx)
        val p = store.purchase(uid, id!!) ?: throw notFound(ctx)
        val ids = p.lines.map { it.itemId }.distinct()
        val items = store.itemsById(uid, ids)
        val st = stock.load(uid)
        val add = ArrayList<String>()
        val remove = ArrayList<String>()
        val prices = LinkedHashMap<String, StockStore.PriceChange?>()
        val now = clock.now()
        for (itemId in ids) {
            val x = items[itemId] ?: continue
            val q = p.lines.filter { it.itemId == itemId }.sumOf { it.qty }
            val u = p.undo[itemId]
            if (u == null) {
                if (x.qty == null) continue
                val nq = r2(maxOf(0.0, x.qty - q))
                BooksMath.stockChange(x.name, x.qty, nq, add, remove)
                store.saveItem(uid, x.copy(qty = nq, updatedAt = now))
                continue
            }
            var y = x.copy(updatedAt = now)
            if (u.qty == null) {
                y = y.copy(qty = null)
                if (u.inStock) add.add(x.name) else remove.add(x.name)
            } else if (x.qty != null) {
                val nq = r2(maxOf(0.0, x.qty - q))
                y = y.copy(qty = nq)
                BooksMath.stockChange(x.name, x.qty, nq, add, remove)
            }
            // what this purchase changed goes back — unless it was changed by hand afterwards
            var priceBack = false
            if (x.cost == u.afterCost && x.cost != u.cost) y = y.copy(cost = u.cost)
            if (x.price == u.afterPrice && x.price != u.price) {
                y = y.copy(price = u.price)
                priceBack = true
            }
            if (x.expiry == u.afterExpiry && x.expiry != u.expiry) y = y.copy(expiry = u.expiry)
            store.saveItem(uid, y)
            if (priceBack) BooksMath.priceChange(prices, null, y)
        }
        stock.stockWrite(ctx.lang, uid, st, add, remove, prices)
        store.deletePurchase(uid, id)
        store.dayAdd(uid, p.day, BooksMath.DayDelta(purchases = -1.0, bought = -p.total))
        return mapOf("ok" to true)
    }

    // ------------------------------------------------------------------ sales

    /** shopbooks.js dispensingOf: one of this shop's dispensings (the last one when [at] is null) */
    private fun dispensingOf(rx: Prescription, uid: String, at: Long?): RxDispensing? {
        val list = rx.dispensed.filter { it.by == uid }
        return if (at == null) list.lastOrNull() else list.firstOrNull { it.at == at }
    }

    /** shopbooks.js sourceOf: 'online' when an online order of this shop was completed with this dispensing */
    private fun sourceOf(rxId: String, uid: String, at: Long): String {
        val online = jdbc.queryForList(
            """
            SELECT 1 FROM rx_orders WHERE rx_id = ? AND pharmacy_uid = ? AND mode = 'online' AND status = 'done'
              AND abs(extract(epoch FROM coalesce(done_at, to_timestamp(0))) * 1000 - ?) < 600000 LIMIT 1
            """.trimIndent(),
            rxId, uid, at,
        )
        return if (online.isNotEmpty()) "online" else "rx"
    }

    private fun rxSaleId(rxId: String, at: Long) = "rx_${rxId}_$at"

    /** shopbooks.js matchItem: exact name, else the one product "name + strength" */
    private fun <T> matchItem(name: String, list: List<T>, keyOf: (T) -> String): T? {
        val k = BooksMath.key(name)
        list.firstOrNull { keyOf(it) == k }?.let { return it }
        val near = list.filter { keyOf(it).startsWith("$k ") || k.startsWith("${keyOf(it)} ") }
        return if (near.size == 1) near[0] else null
    }

    /** a recorded sale; [short] = products that had less stock than sold (the stock stopped at 0) */
    class SaleOut(val sale: BooksStore.Sale, val short: List<String>)

    @Transactional
    fun saleAdd(ctx: ActionContext): Any {
        val out = saleCore(ctx, ctx.user, ctx.body)
        return linkedMapOf("id" to out.sale.id, "sale" to out.sale.toMap())
    }

    /** books_sale_add for the HTTP action and for the receipts (InvoiceCreators.pharmacySale) */
    @Transactional
    fun saleCore(ctx: CallCtx, user: AuthUser, b: Body): SaleOut {
        val uid = myShopOf(ctx, user, lock = true)
        val now = clock.now()
        val rxNode = b["rx"]
        val isRx = b.string("source") == "rx" || b.string("source") == "online" || (rxNode != null && rxNode.isObject)
        val billDisc = discOf(ctx, b["billDisc"])
        var at = atOf(ctx, b["at"], now)
        var source = "counter"
        var id = Ids.newId()
        var rxInfo: BooksStore.RxInfo? = null

        class Want(val i: Int?, val name: String, val qty: Double, var itemId: String?, val unitPrice: JsonNode?, val unitDisc: JsonNode?, val given: Boolean) {
            var noItem = false
        }
        val want = ArrayList<Want>()
        if (isRx) {
            val rx = prescriptions.byCode(ctx.lang, Js.toStr(rxNode?.get("code")), null)
            val dAt = Js.integer(rxNode?.get("at"))
            val disp = dispensingOf(rx, uid, dAt)
                ?: throw ctx.err(404, "You haven't given medicines from this prescription", "এই প্রেসক্রিপশন থেকে আপনি ওষুধ দেননি")
            at = disp.at
            source = sourceOf(rx.id, uid, disp.at)
            id = rxSaleId(rx.id, disp.at)
            rxInfo = BooksStore.RxInfo(rx.id, rx.code, disp.at)
            val meds = rx.medicines
            val given = HashMap<Int, JsonNode>()
            for (l in (b["lines"]?.takeIf { it.isArray }?.toList() ?: emptyList()).take(MAX_LINES)) {
                Js.integer(l.get("i"))?.let { given[it.toInt()] = l }
            }
            for ((i, qty) in disp.items) {
                val m = meds.getOrNull(i) ?: continue
                val g = given[i]
                val itemId = Js.string(g?.get("itemId"))?.takeIf { Rules.isValidId(it) }
                want.add(Want(i, m.name, qty.toDouble(), itemId, g?.get("unitPrice"), g?.get("unitDisc"), g != null))
            }
        } else {
            val raw = (b["lines"]?.takeIf { it.isArray }?.toList() ?: emptyList()).take(MAX_LINES + 1)
            if (raw.size > MAX_LINES) throw bad(ctx, "Up to $MAX_LINES lines", "সর্বোচ্চ ${MAX_LINES}টা লাইন")
            for (l in raw) {
                val qty = qtyOf(ctx, l.get("qty"))
                if (qty == null || qty == 0.0) continue
                val nm = BooksMath.str(Js.string(l.get("name")), 100)
                val given = Js.string(l.get("itemId"))
                val itemId = if (Rules.isValidId(given)) given!! else if (nm.isNotEmpty()) BooksMath.idOf(nm) else continue
                want.add(Want(null, nm, qty, itemId, l.get("unitPrice"), l.get("unitDisc"), true))
            }
        }
        if (want.isEmpty()) throw bad(ctx, "Add at least one product with quantity", "অন্তত একটা পণ্য আর পরিমাণ দিন")
        if (isRx && want.any { it.itemId == null }) {
            val (rows, _) = loadItems(uid)
            for (w in want) {
                if (w.itemId != null) continue
                val m = matchItem(w.name, rows) { it.key.ifEmpty { BooksMath.key(it.name) } }
                if (m != null) {
                    w.itemId = m.id
                } else {
                    w.itemId = "x" + BooksMath.idOf(w.name).substring(1)
                    w.noItem = true
                }
            }
        }
        if (isRx && store.saleExists(uid, id)) throw ctx.err(409, "This sale is already in your books", "এই বিক্রি আগেই হিসাবে তোলা হয়েছে")
        val ids = want.filter { !it.noItem }.map { it.itemId!! }.distinct()
        val found = store.itemsById(uid, ids)
        val st = stock.load(uid)
        val cur = LinkedHashMap<String, ShopItem>()
        found.forEach { (k, v) -> cur[k] = v }
        val lines = want.map { w ->
            val x: ShopItem = if (w.noItem) {
                ShopItem(w.itemId!!, w.name, BooksMath.key(w.name))
            } else {
                cur[w.itemId!!] ?: run {
                    if (w.name.isEmpty()) throw notFound(ctx)
                    BooksMath.newItem(w.itemId!!, w.name, now).also { cur[w.itemId!!] = it }
                }
            }
            val unitPrice = if (w.given && w.unitPrice != null) moneyOf(ctx, w.unitPrice) else x.price
            val unitDisc = when {
                unitPrice == null -> 0.0
                w.given && w.unitDisc != null -> minOf(unitPrice, moneyOf(ctx, w.unitDisc, "discount", "ছাড়") ?: 0.0)
                else -> BooksMath.unitDiscOf(unitPrice, x.disc)
            }
            BooksMath.LineIn(w.i, w.itemId!!, x.name, w.qty, unitPrice, unitDisc, x.cost)
        }
        val priced = BooksMath.priceSale(lines, billDisc)
        want.forEachIndexed { k, w -> if (w.noItem) priced.lines[k].noItem = true }
        val add = ArrayList<String>()
        val remove = ArrayList<String>()
        val sold = LinkedHashMap<String, Double>()
        for (l in priced.lines) if (!l.noItem) sold[l.itemId] = (sold[l.itemId] ?: 0.0) + l.qty
        val decOf = LinkedHashMap<String, Double>()
        for ((itemId, q) in sold) {
            val x = cur[itemId]!!
            val before = x.qty
            val nq = before?.let { r2(maxOf(0.0, it - q)) }
            decOf[itemId] = if (before == null) 0.0 else r2(before - nq!!)
            BooksMath.stockChange(x.name, before, nq, add, remove)
            store.saveItem(uid, x.copy(qty = nq, lastSaleAt = maxOf(x.lastSaleAt ?: 0L, at), updatedAt = now))
        }
        val decOf0 = HashMap(decOf)
        for (l in priced.lines) {
            val left = decOf[l.itemId] ?: 0.0
            l.dec = r2(minOf(l.qty, left))
            if (l.itemId in decOf) decOf[l.itemId] = r2(left - l.dec!!)
        }
        stock.stockWrite(ctx.lang, uid, st, add, remove)
        val sale = BooksStore.Sale(
            id, at, BooksMath.dayKey(at), source, priced.lines, priced.totals, billDisc, rxInfo,
            BooksMath.str(b.string("customer") ?: Js.string(b["customer"]?.get("name")), 60), now,
        )
        store.insertSale(uid, sale)
        store.dayAdd(uid, sale.day, BooksMath.saleDayDelta(sale.source, sale.totals, sale.lines))
        val short = sold.filter { (itemId, q) -> cur[itemId]?.qty != null && (decOf0[itemId] ?: q) < q }.keys.toList()
        return SaleOut(sale, short)
    }

    @Transactional
    fun rxPreview(ctx: ActionContext): Any {
        val uid = myShop(ctx, lock = false)
        val b = ctx.body
        val rx = prescriptions.byCode(ctx.lang, Js.toStr(b["code"]), null)
        val disp = dispensingOf(rx, uid, Js.integer(b["at"]))
            ?: throw ctx.err(404, "You haven't given medicines from this prescription", "এই প্রেসক্রিপশন থেকে আপনি ওষুধ দেননি")
        val meds = rx.medicines
        val (rows, st) = loadItems(uid)
        val done = store.saleExists(uid, rxSaleId(rx.id, disp.at))
        val source = sourceOf(rx.id, uid, disp.at)
        val now = clock.now()
        val lines = disp.items.filter { (i, _) -> meds.getOrNull(i) != null }.map { (i, qty) ->
            val m = matchItem(meds[i].name, rows) { it.key.ifEmpty { BooksMath.key(it.name) } }
            val v = m?.let { BooksMath.itemView(it, st.keys, now) }
            val price = v?.get("price") as Double?
            linkedMapOf<String, Any?>(
                "i" to i, "name" to meds[i].name, "qty" to qty, "itemId" to m?.id, "itemName" to (v?.get("name") ?: ""),
                "unitPrice" to price, "unitDisc" to if (price != null) r2(price - (v["salePrice"] as Double)) else 0,
                "unitCost" to v?.get("cost"), "stockQty" to v?.get("qty"),
            )
        }
        return linkedMapOf("code" to rx.code, "at" to disp.at, "source" to source, "recorded" to done, "lines" to lines)
    }

    @Transactional(readOnly = true)
    fun sales(ctx: ActionContext): Any {
        val uid = myShop(ctx, lock = false)
        val p = Js.string(ctx.body["period"])?.takeIf { it.isNotEmpty() } ?: "7d"
        val (_, start) = BooksMath.periodOf(p, clock.now())
        val items = store.sales(uid, start, 300).sortedWith(
            compareByDescending<BooksStore.Sale> { it.at }.thenByDescending { it.createdAt }.thenComparator { a, c -> a.id.compareTo(c.id) },
        )
        return mapOf("items" to items.map { it.toMap() })
    }

    private val SALE_ID = Regex("^[A-Za-z0-9_-]{1,120}$")

    @Transactional
    fun saleDelete(ctx: ActionContext): Any {
        val uid = myShop(ctx, lock = true)
        val id = ctx.body.string("id")
        if (id == null || !SALE_ID.matches(id)) throw notFound(ctx)
        if (!reverseSale(uid, id)) throw notFound(ctx)
        return mapOf("ok" to true)
    }

    /**
     * books_sale_delete without the checks: the stock the sale took goes back, the sale and its day totals go. Also what
     * voiding a pharmacy receipt does (BooksInvoiceHook). false = no such sale. The caller holds the shop lock.
     */
    @Transactional
    fun reverseSale(uid: String, id: String): Boolean {
        val sale = store.sale(uid, id) ?: return false
        val ids = sale.lines.filter { !it.noItem }.map { it.itemId }.distinct()
        val items = store.itemsById(uid, ids)
        val st = stock.load(uid)
        val add = ArrayList<String>()
        val remove = ArrayList<String>()
        val now = clock.now()
        for (itemId in ids) {
            val x = items[itemId] ?: continue
            if (x.qty == null) continue
            // what the sale really took (it stops at 0); old sales without `dec`: the full quantity
            val q = sale.lines.filter { it.itemId == itemId && !it.noItem }.sumOf { it.dec ?: it.qty }
            if (q == 0.0) continue
            val nq = r2(x.qty + q)
            BooksMath.stockChange(x.name, x.qty, nq, add, remove)
            store.saveItem(uid, x.copy(qty = nq, updatedAt = now))
        }
        stock.stockWrite(null, uid, st, add, remove)
        store.deleteSale(uid, id)
        store.dayAdd(uid, sale.day, BooksMath.saleDayDelta(sale.source, sale.totals, sale.lines, -1))
        return true
    }

    /** the shop row lock (writes to the books) */
    fun lockShop(uid: String): Boolean = stock.lockShop(uid)

    // ------------------------------------------------------------------ write-off / count

    @Transactional
    fun adjust(ctx: ActionContext): Any {
        val uid = myShop(ctx, lock = true)
        val b = ctx.body
        val kind = when (b.string("kind")) {
            "count" -> "count"
            "writeoff" -> "writeoff"
            else -> null
        }
        val itemId = b.string("itemId")
        if (kind == null || !Rules.isValidId(itemId)) throw notFound(ctx)
        val q = qtyOf(ctx, b["qty"])
        if (q == null || (kind == "writeoff" && q <= 0)) throw bad(ctx, "Enter the quantity", "পরিমাণ লিখুন")
        val now = clock.now()
        val x = store.item(uid, itemId!!) ?: throw notFound(ctx)
        val st = stock.load(uid)
        val before = x.qty
        val after = if (kind == "count") q else before?.let { r2(maxOf(0.0, it - q)) }
        val gone = if (kind == "writeoff") (if (before == null) q else r2(minOf(q, before))) else 0.0
        val delta = if (kind == "count") r2(q - (before ?: 0.0)) else -gone
        val loss = if (kind == "writeoff" && x.cost != null) r2(gone * x.cost) else 0.0
        val add = ArrayList<String>()
        val remove = ArrayList<String>()
        BooksMath.stockChange(x.name, before, after, add, remove)
        store.saveItem(uid, x.copy(qty = after, updatedAt = now))
        stock.stockWrite(ctx.lang, uid, st, add, remove)
        val a = BooksStore.Adjust(
            Ids.newId(), kind, itemId, x.name, before, after, delta, x.cost, loss, BooksMath.str(b.string("reason"), 120), now, BooksMath.dayKey(now), now,
        )
        store.insertAdjust(uid, a)
        store.dayAdd(
            uid, a.day,
            if (kind == "writeoff") BooksMath.DayDelta(adjusts = 1.0, writeOff = loss, woq = gone) else BooksMath.DayDelta(adjusts = 1.0),
        )
        val shown = a.toMap()
        return linkedMapOf("id" to a.id, "adjust" to shown)
    }

    @Transactional(readOnly = true)
    fun adjusts(ctx: ActionContext): Any {
        val uid = myShop(ctx, lock = false)
        return mapOf("items" to store.adjusts(uid, clock.now() - 90 * BooksMath.DAY_MS, 200).map { it.toMap() })
    }

    // ------------------------------------------------------------------ today / report

    @Transactional(readOnly = true)
    fun today(ctx: ActionContext): Any {
        val uid = myShop(ctx, lock = false)
        val now = clock.now()
        val d = store.days(uid, listOf(BooksMath.dayKey(now))).first()
        var low = 0
        var expiring = 0
        var priced = false
        for (s in store.items(uid)) {
            val v = BooksMath.itemView(s, emptySet(), now)
            if (v["low"] == true) low++
            val e = v["expDays"] as Long?
            val qty = v["qty"] as Double?
            if (e != null && e <= 60 && (qty == null || qty > 0)) expiring++
            if (v["price"] != null) priced = true
        }
        return linkedMapOf(
            "sales" to Js.round(d?.sales ?: 0.0).toLong(), "net" to r2(d?.net ?: 0.0), "profit" to r2(d?.profit ?: 0.0),
            "writeOff" to r2(d?.writeOff ?: 0.0), "low" to low, "expiring" to expiring, "priced" to priced,
        )
    }

    @Transactional
    fun report(ctx: ActionContext): Any {
        val uid = myShop(ctx, lock = false)
        val period = ctx.body.string("period")?.takeIf { it in listOf("today", "7d", "30d", "month") } ?: "30d"
        val now = clock.now()
        val (days, _) = BooksMath.periodOf(period, now)
        val docs = store.days(uid, days)
        val (rows, st) = loadItems(uid)
        return BooksMath.report(period, days, docs, rows, st.keys, now)
    }
}
