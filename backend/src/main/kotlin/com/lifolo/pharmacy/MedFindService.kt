package com.lifolo.pharmacy

import com.fasterxml.jackson.databind.json.JsonMapper
import com.lifolo.common.DhakaTime
import com.lifolo.common.Ids
import com.lifolo.common.Js
import com.lifolo.common.Phones
import com.lifolo.common.Places
import com.lifolo.common.QuotaService
import com.lifolo.common.Rules
import com.lifolo.common.Sql
import com.lifolo.common.action.ActionContext
import com.lifolo.common.doubleOrNull
import com.lifolo.common.intOrNull
import com.lifolo.common.ms
import com.lifolo.demo.DemoWorld
import com.lifolo.jobs.DailyTask
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.sql.Timestamp

/** one row of the bundled DGDA drug list (medfind.js list()) */
data class Drug(val i: Int, val name: String, val generic: String, val form: String, val company: String) {
    fun toMap(): Map<String, Any?> = linkedMapOf("i" to i, "name" to name, "generic" to generic, "form" to form, "company" to company)

    fun drug(): Map<String, Any?> = linkedMapOf("name" to name, "generic" to generic, "form" to form, "company" to company)
}

/** medfind.js list() + searchDrugs: `data/drugs.json` {f: forms, c: companies, d: [name, generic, formIdx, companyIdx]} */
object DrugList {
    val rows: List<Drug> by lazy {
        val o = JsonMapper().readTree(DrugList::class.java.getResourceAsStream("/data/drugs.json") ?: error("missing data/drugs.json"))
        val f = o["f"]
        val c = o["c"]
        o["d"].mapIndexed { i, r ->
            Drug(i, r[0].asText(), r[1].asText(), f.get(r[2].asInt())?.asText() ?: "", c.get(r[3].asInt())?.asText() ?: "")
        }
    }
    private val lowerNames by lazy { rows.map { it.name.lowercase() } }
    private val lowerGenerics by lazy { rows.map { it.generic.lowercase() } }

    /** brand starts with the text first (shortest first), then brand contains, then generic */
    fun search(query: String?, limit: Int = 15): List<Drug> {
        val q = (query ?: "").trim().lowercase()
        if (q.length < 2) return emptyList()
        val starts = ArrayList<Drug>()
        val contains = ArrayList<Drug>()
        val generic = ArrayList<Drug>()
        for (i in rows.indices) {
            val n = lowerNames[i]
            if (n.startsWith(q)) {
                starts.add(rows[i])
            } else if (contains.size < limit && n.contains(q)) {
                contains.add(rows[i])
            } else if (generic.size < limit && lowerGenerics[i].contains(q)) {
                generic.add(rows[i])
            }
            if (starts.size >= limit * 3) break
        }
        starts.sortBy { it.name.length }
        val seen = HashSet<String>()
        return (starts + contains + generic).filter { seen.add("${it.name}|${it.generic}|${it.form}|${it.company}") }.take(limit)
    }
}

/**
 * medfind.js — "which shop has it?": a buyer picks a medicine from the list and says how much they need (optional
 * `qty` + `unit`, [MedQty]; older apps send none = "পরিমাণ বলা নেই"), up to 15 nearby open approved pharmacies get a
 * push; a shop that has it answers how many it has (or ignores it); the buyer calls a shop that has it.
 * backend/docs/MEDFIND.md
 */
@Service
class MedFindService(
    private val jdbc: JdbcTemplate,
    private val shops: ShopRepo,
    private val quotas: QuotaService,
    private val userPush: UserPush,
    private val clock: P4Clock,
) {
    companion object {
        const val NOTIFY_MAX = 15
        const val OPEN_MS = 6 * 3600 * 1000L
        const val PER_SHOP_DAY = 30
        const val KEEP_AFTER_EXPIRY_MS = 2 * 24 * 3600 * 1000L
    }

    private class Find(
        val id: String,
        val uid: String,
        val district: String,
        val upazilaId: Int?,
        val lat: Double?,
        val lng: Double?,
        val drug: Map<String, Any?>,
        val qty: Int?,
        val unit: String?,
        val status: String,
        val createdAt: Long,
        val expiresAt: Long,
    ) {
        fun expired(now: Long) = status != "open" || now > expiresAt

        fun area(): String = listOfNotNull(upazilaId?.let { Places.UPAZILA[it]?.bn }, district).filter { it.isNotEmpty() }.joinToString(", ")
    }

    private fun find(rs: ResultSet) = Find(
        rs.getString("id"), rs.getString("uid"), rs.getString("district"), rs.intOrNull("upazila_id"), rs.doubleOrNull("lat"),
        rs.doubleOrNull("lng"),
        linkedMapOf(
            "name" to rs.getString("drug_name"), "generic" to (rs.getString("drug_generic") ?: ""),
            "form" to (rs.getString("drug_form") ?: ""), "company" to (rs.getString("drug_company") ?: ""),
        ),
        rs.intOrNull("qty"), rs.getString("unit"),
        rs.getString("status"), rs.ms("created_at") ?: 0, rs.ms("expires_at") ?: 0,
    )

    fun drugSearch(ctx: ActionContext): Any = mapOf("items" to DrugList.search(Js.toStr(ctx.body["q"])).map { it.toMap() })

    @Transactional
    fun medFind(ctx: ActionContext): Any {
        val b = ctx.body
        val uid = ctx.user.id
        val rows = DrugList.rows
        val d = b.int("i")?.takeIf { it >= 0 && it < rows.size }?.let { rows[it.toInt()] }
            ?: throw ctx.err(400, "Choose the medicine from the list", "তালিকা থেকে ওষুধ বেছে নিন")
        val district = b.string("district")
        if (!Places.isDistrict(district)) throw ctx.err(400, "Choose a district", "জেলা বেছে নিন")
        val upazilaId = Places.upazilaIn(district, b.int("upazilaId"))
        // how much: 1–999 in a unit that fits the form (a unit that does not fit → the form's default)
        val qty = b.int("qty")?.takeIf { it in 1L..MedQty.MAX.toLong() }?.toInt()
            ?: throw ctx.err(400, "Quantity must be 1 to 999", "পরিমাণ ১ থেকে ৯৯৯-এর মধ্যে দিন") // bn-ok
        val unit = MedQty.unitFor(d.form, b.string("unit"))
        val lat = b.number("lat")?.let { Js.round(it * 100) / 100 }
        val lng = b.number("lng")?.let { Js.round(it * 100) / 100 }
        // the upazila's shops first; demo finds only reach demo shops and real ones real shops
        val docs = shops.area(district!!, upazilaId).filter { DemoWorld.sameWorld(uid, it.id) }
        val now = clock.now()
        val day = DhakaTime.day(now)
        if (docs.none { it.id != uid }) return linkedMapOf("ok" to false, "noPharmacy" to true)
        data class Cand(val id: String, val upazilaId: Int?, val open: Boolean, val km: Double?)
        var cands = docs
            .filter { it.id != uid && !it.findOff }
            .filter { !(it.findDay == day && it.findCount >= PER_SHOP_DAY) }
            .map { s ->
                Cand(
                    s.id, s.upazilaId?.takeIf { it != 0 }, s.openNow(now),
                    if (lat != null && s.lat != null && s.lng != null) com.lifolo.common.Geo.distanceKm(lat, lng ?: Double.NaN, s.lat, s.lng) else null,
                )
            }
        // only shops open now — no push to a closed shop at 2 a.m.
        cands = cands.filter { it.open }
        if (cands.isEmpty()) return linkedMapOf("ok" to false, "noneOpen" to true)
        if (upazilaId != null) {
            val near = cands.filter { it.upazilaId == upazilaId || (it.km != null && it.km <= 5) }
            if (near.isNotEmpty()) cands = near
        }
        val targets = cands.sortedBy { if (it.km == null || it.km.isNaN()) 99.0 else it.km }.take(NOTIFY_MAX)

        quotas.use(ctx.lang, uid, "medFind")
        val id = Ids.newId()
        jdbc.update(
            """
            INSERT INTO med_finds (id, uid, district, upazila_id, lat, lng, drug_name, drug_generic, drug_form, drug_company, qty, unit, status, created_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'open', ?, ?)
            """.trimIndent(),
            id, uid, district, upazilaId, lat, lng, d.name, d.generic, d.form, d.company, qty, unit,
            Timestamp(now), Timestamp(now + OPEN_MS),
        )
        jdbc.batchUpdate("INSERT INTO med_find_targets (find_id, pharmacy_uid) VALUES (?, ?)", targets.map { arrayOf<Any?>(id, it.id) })
        // the shop's daily count (a new day starts again at 1)
        for (t in targets) {
            jdbc.update(
                "UPDATE pharmacies SET find_count = CASE WHEN find_day = ? THEN find_count + 1 ELSE 1 END, find_day = ? WHERE uid = ?",
                Sql.date(day), Sql.date(day), t.id,
            )
        }
        // no buyer name or phone in the push; the amount in Bangla digits
        val body = "${d.name} — ${MedQty.bn(qty, unit)} লাগবে। আপনার কাছে আছে?" // bn-ok
        for (t in targets) {
            userPush.send(
                t.id, "Someone nearby is looking for a medicine", "কাছে একজন ওষুধ খুঁজছেন", body,
                mapOf("type" to "med_find", "findId" to id),
            )
        }
        return linkedMapOf("ok" to true, "id" to id, "notified" to targets.size)
    }

    @Transactional(readOnly = true)
    fun mine(ctx: ActionContext): Any {
        val uid = ctx.user.id
        val docs = jdbc.query("SELECT * FROM med_finds WHERE uid = ? ORDER BY created_at DESC LIMIT 10", { rs, _ -> find(rs) }, uid)
        data class Target(val shop: String, val have: Boolean?, val haveQty: Int?, val haveUnit: String?, val at: Long?)
        val targets = HashMap<String, MutableList<Target>>()
        if (docs.isNotEmpty()) {
            jdbc.query(
                "SELECT find_id, pharmacy_uid, have, have_qty, have_unit, answered_at FROM med_find_targets WHERE find_id IN (${docs.joinToString(",") { "?" }})",
                { rs, _ ->
                    targets.getOrPut(rs.getString(1)) { ArrayList() }.add(
                        Target(rs.getString(2), rs.getBoolean(3).takeUnless { rs.wasNull() }, rs.intOrNull("have_qty"), rs.getString("have_unit"), rs.ms("answered_at")),
                    )
                },
                *docs.map { it.id }.toTypedArray(),
            )
        }
        val ids = targets.values.flatten().filter { it.have == true }.map { it.shop }.toSet()
        val byId = shops.byIds(ids).filterValues { it.status == "approved" }
        val now = clock.now()
        return mapOf(
            "items" to docs.map { x ->
                val list = targets[x.id].orEmpty()
                // only shops that said "আছে" (with how many) — an ignored request shows nothing; full (≥ what the buyer
                // asked) first, then nearest
                val yes = list.filter { it.have == true && it.shop in byId }.map { a ->
                    val s = byId.getValue(a.shop)
                    val km = if (x.lat != null && x.lng != null && s.lat != null && s.lng != null) km1(x.lat, x.lng, s.lat, s.lng) else null
                    linkedMapOf<String, Any?>(
                        "id" to a.shop, "name" to s.name, "phone" to Phones.fixPhone(s.phone), "address" to s.address,
                        "openNow" to s.openNow(now), "open24" to s.open24, "km" to km, "at" to a.at,
                        "haveQty" to a.haveQty, "haveUnit" to a.haveUnit,
                        "full" to (x.qty != null && a.haveQty != null && a.haveQty >= x.qty),
                    )
                }.sortedWith(
                    compareBy<LinkedHashMap<String, Any?>> { if (it["full"] == true) 0 else 1 }
                        .thenBy { (it["km"] as Number?)?.toDouble() ?: Double.MAX_VALUE }
                        .thenBy { (it["at"] as Long?) ?: 0L },
                )
                linkedMapOf<String, Any?>(
                    "id" to x.id, "drug" to x.drug, "area" to x.area(), "open" to !x.expired(now), "notified" to list.size,
                    "answered" to list.count { it.have == true }, "have" to yes, "createdAt" to x.createdAt,
                    "qty" to x.qty, "unit" to x.unit,
                )
            },
        )
    }

    @Transactional
    fun close(ctx: ActionContext): Any {
        val id = ctx.body.string("id")
        if (!Rules.isValidId(id)) throw ctx.err(400, "Not found", "পাওয়া যায়নি")
        val owner = jdbc.queryForList("SELECT uid FROM med_finds WHERE id = ? FOR UPDATE", String::class.java, id).firstOrNull()
        if (owner == null || owner != ctx.user.uid) throw ctx.notFound()
        jdbc.update("UPDATE med_finds SET status = 'closed', closed_at = ? WHERE id = ?", Timestamp(clock.now()), id)
        return mapOf("ok" to true)
    }

    @Transactional(readOnly = true)
    fun requests(ctx: ActionContext): Any {
        val uid = ctx.user.id
        val shop = shops.get(uid)
        if (shop == null || shop.status != "approved") return linkedMapOf("items" to emptyList<Any>(), "findOff" to (shop != null && shop.findOff))
        val now = clock.now()
        data class Mine(val have: Boolean?, val qty: Int?, val unit: String?)
        val answers = HashMap<String, Mine>()
        // "বাদ দিন" (dismissed_at) hides a request from this shop's own list only
        val docs = jdbc.query(
            """
            SELECT f.*, t.have AS my_have, t.have_qty AS my_have_qty, t.have_unit AS my_have_unit
            FROM med_finds f JOIN med_find_targets t ON t.find_id = f.id
            WHERE t.pharmacy_uid = ? AND t.dismissed_at IS NULL ORDER BY f.created_at DESC LIMIT 100
            """.trimIndent(),
            { rs, _ ->
                val f = find(rs)
                answers[f.id] = Mine(rs.getBoolean("my_have").takeUnless { rs.wasNull() }, rs.intOrNull("my_have_qty"), rs.getString("my_have_unit"))
                f
            },
            uid,
        )
        val items = docs.filter { !it.expired(now) }.sortedByDescending { it.createdAt }.take(30).map { x ->
            val mine = answers[x.id]
            linkedMapOf<String, Any?>(
                "id" to x.id, "drug" to x.drug, "area" to x.area(),
                "km" to if (x.lat != null && x.lng != null && shop.lat != null && shop.lng != null) km1(x.lat, x.lng, shop.lat, shop.lng) else null,
                "answer" to when (mine?.have) {
                    null -> null
                    true -> "yes"
                    false -> "no"
                },
                "createdAt" to x.createdAt,
                // how much the buyer needs and how much I said I have
                "qty" to x.qty, "unit" to x.unit,
                "haveQty" to mine?.takeIf { it.have == true }?.qty, "haveUnit" to mine?.takeIf { it.have == true }?.unit,
            )
        }
        return linkedMapOf("items" to items, "findOff" to shop.findOff)
    }

    /**
     * A shop's answer: `haveQty` (1–9999) = "আছে", that many, in the buyer's unit; again = a new amount. There is no
     * "নেই": a shop that does not have it just leaves it; `dismiss: true` ("বাদ দিন") only hides it from its own list.
     */
    @Transactional
    fun answer(ctx: ActionContext): Any {
        val uid = ctx.user.id
        val b = ctx.body
        val id = b.string("id")
        if (!Rules.isValidId(id)) throw ctx.err(400, "Not found", "পাওয়া যায়নি")
        val dismiss = b.isTrue("dismiss")
        val qty = if (dismiss) {
            null
        } else {
            b.int("haveQty")?.takeIf { it in 1L..MedQty.HAVE_MAX.toLong() }?.toInt()
                ?: throw ctx.err(400, "Write how many you have (1 to 9999)", "কতটা আছে লিখুন (১ থেকে ৯৯৯৯)") // bn-ok
        }
        val shop = shops.get(uid)
        if (shop == null || shop.status != "approved") throw ctx.err(403, "Your pharmacy is not approved", "আপনার ফার্মেসি অনুমোদিত নয়")
        val x = jdbc.query("SELECT * FROM med_finds WHERE id = ? FOR UPDATE", { rs, _ -> find(rs) }, id).firstOrNull()
        val target = x?.let {
            jdbc.queryForList("SELECT have FROM med_find_targets WHERE find_id = ? AND pharmacy_uid = ?", id, uid).firstOrNull()
        }
        if (x == null || target == null) throw ctx.notFound()
        val now = clock.now()
        if (dismiss) {
            jdbc.update("UPDATE med_find_targets SET dismissed_at = ? WHERE find_id = ? AND pharmacy_uid = ?", Timestamp(now), id, uid)
            return mapOf("ok" to true)
        }
        if (x.expired(now)) throw ctx.err(409, "This request has closed", "এই অনুরোধ বন্ধ হয়ে গেছে")
        val prev = target["have"] as Boolean?
        // the buyer's unit (a find stored before V692 has none: the form's default)
        val unit = x.unit ?: MedQty.unitFor(x.drug["form"] as String?, null)
        jdbc.update(
            "UPDATE med_find_targets SET have = TRUE, have_qty = ?, have_unit = ?, answered_at = ? WHERE find_id = ? AND pharmacy_uid = ?",
            qty, unit, Timestamp(now), id, uid,
        )
        // the buyer's push: the old generic text — never a stock number on the lock screen
        if (prev != true) {
            userPush.send(
                x.uid, "Available at ${shop.name}", "${shop.name}-এ আছে", "${x.drug["name"]} — ফোন করে নিশ্চিত হয়ে নিন",
                mapOf("type" to "med_find_mine", "findId" to id),
            )
        }
        return mapOf("ok" to true)
    }

    @Transactional
    fun findSetting(ctx: ActionContext): Any {
        val n = jdbc.update("UPDATE pharmacies SET find_off = ? WHERE uid = ?", ctx.body.isTrue("off"), ctx.user.id)
        if (n == 0) throw ctx.err(404, "Open your pharmacy first", "আগে নিজের ফার্মেসি যোগ করুন")
        return mapOf("ok" to true)
    }

    /** medfind.js dailyCleanup: finds expired more than 2 days ago are deleted (at most 5 × 400 per run) */
    @Transactional
    fun dailyCleanup(now: Long): Int = jdbc.update(
        "DELETE FROM med_finds WHERE id IN (SELECT id FROM med_finds WHERE expires_at < ? LIMIT 2000)",
        Timestamp(now - KEEP_AFTER_EXPIRY_MS),
    )
}

/** daily cron step 5 (`medFindsDeleted`) */
@Component
class MedFindCleanupTask(private val medFind: MedFindService) : DailyTask {
    override val name = "medFindsDeleted"
    override val order = 5

    override fun run(nowMs: Long): Any = medFind.dailyCleanup(nowMs)
}
