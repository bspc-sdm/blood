package com.lifolo.listings

import com.lifolo.admin.kernel.ListingKind
import com.lifolo.admin.kernel.ListingRows
import com.lifolo.admin.kernel.ListingRules
import com.lifolo.common.QuotaService
import com.lifolo.common.Sql
import com.lifolo.common.SqlSet
import com.lifolo.common.action.ActionContext
import com.lifolo.common.action.ActionHandler
import com.lifolo.common.action.ActionModule
import com.lifolo.pharmacy.P4Clock
import com.lifolo.pharmacy.ShopRepo
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The provider quick switches (LISTINGS.md "Provider side"): who flipped what is logged in `listing_switch_log`, and
 * every switch counts against one daily limit per caller.
 */
@Service
class SwitchLog(private val jdbc: JdbcTemplate, private val quotas: QuotaService) {
    companion object {
        /** quick-switch taps per caller per Dhaka day (all switches together) */
        const val PER_DAY = 200
        const val QUOTA = "quickSwitch"
    }

    /** the daily limit (429 like every quota) */
    fun use(ctx: ActionContext) = quotas.use(ctx.lang, ctx.caller.uid ?: ctx.user.id, QUOTA, PER_DAY)

    fun write(kind: String, listingId: String, ownerUid: String?, byUid: String?, role: String, field: String, value: Any?, until: Long? = null) {
        jdbc.update(
            "INSERT INTO listing_switch_log (kind, listing_id, owner_uid, by_uid, role, field, value, until) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            kind, listingId, ownerUid, byUid, role, field, value?.toString(), until?.let { Sql.ts(it) },
        )
    }
}

@Service
class ListingSwitches(
    private val jdbc: JdbcTemplate,
    private val rows: ListingRows,
    private val shops: ShopRepo,
    private val log: SwitchLog,
    private val clock: P4Clock,
) {
    companion object {
        /** the longest "বিরতি" an owner can pick (MYWORK.md) */
        const val MAX_UNTIL_MS = 24 * 3600 * 1000L
    }

    private fun suspended(ctx: ActionContext, kind: ListingKind) = when (kind) {
        ListingKind.PHARMACY -> ctx.err(
            403,
            "Your pharmacy is suspended. You can appeal from Profile → Help & appeals.",
            "আপনার ফার্মেসি বন্ধ করা আছে। প্রোফাইলের \"সাহায্য ও আপিল\" থেকে আপিল করতে পারেন।",
        )
        else -> ctx.err(403, "Your lab is suspended. You can appeal from Profile → Help & appeals.", "আপনার প্যাথলজি বন্ধ করা আছে। প্রোফাইলের \"সাহায্য ও আপিল\" থেকে আপিল করুন।")
    }

    /** `open`: true / false = override until the next scheduled change, null (or absent) = back to the hours */
    private fun openArg(ctx: ActionContext): Boolean? {
        val n = ctx.body["open"]
        if (n == null || n.isNull) return null
        if (!n.isBoolean) throw ctx.err(400, "Choose open or closed", "খোলা না বন্ধ বেছে নিন") // bn-ok
        return n.booleanValue()
    }

    /**
     * MYWORK.md "বিরতি": an optional `until` (ms) for `open: true|false` — the override ends then instead of at the next
     * scheduled change (৩০ মিনিট / ১ ঘণ্টা). now < until ≤ now + 24 h. Absent/null = the next scheduled change.
     */
    private fun untilArg(ctx: ActionContext, open: Boolean?, now: Long): Long? {
        if (ctx.body.isNullish("until")) return null
        val u = ctx.body.int("until")
        if (open == null || u == null || u <= now || u > now + MAX_UNTIL_MS) {
            throw ctx.err(400, "Pick a time within the next 24 hours", "আগামী ২৪ ঘণ্টার মধ্যে সময় দিন") // bn-ok
        }
        return u
    }

    private fun boolArg(ctx: ActionContext, key: String): Boolean =
        ctx.body.bool(key) ?: throw ctx.err(400, "Choose on or off", "চালু না বন্ধ বেছে নিন") // bn-ok

    // ------------------------------------------------------------------ pharmacy

    /** pharmacy_open_override {open: true|false|null} — the owner's shop (the pharmacy id is the owner's uid) */
    @Transactional
    fun pharmacyOpen(ctx: ActionContext): Any {
        val uid = ctx.user.id
        val open = openArg(ctx)
        val s = shops.get(uid, forUpdate = true) ?: throw ctx.notFound()
        if (s.status == "suspended") throw suspended(ctx, ListingKind.PHARMACY)
        val now = clock.now()
        if (open == true && (s.closedUntil ?: 0) > now) {
            throw ctx.err(409, "Your pharmacy is on a break. End the break first.", "ফার্মেসি বিরতিতে আছে। আগে বিরতি শেষ করুন।", "paused") // bn-ok
        }
        val asked = untilArg(ctx, open, now)
        log.use(ctx)
        val until = if (open == null) null else asked ?: ListingFacts.overrideUntil(s.schedule(), now)
        jdbc.update("UPDATE pharmacies SET open_override = ?, open_override_until = ? WHERE uid = ?", open, until?.let { Sql.ts(it) }, uid)
        log.write("pharmacy", uid, uid, ctx.caller.uid, "owner", "open", open, until)
        val after = shops.get(uid)!!
        return linkedMapOf("ok" to true, "openNow" to after.openNow(now), "openOverride" to ListingFacts.activeOverride(open, until, now))
    }

    /** pharmacy_delivery_set {delivery: bool} — no new review (delivery is not a reviewed field) */
    @Transactional
    fun pharmacyDelivery(ctx: ActionContext): Any {
        val uid = ctx.user.id
        val on = boolArg(ctx, "delivery")
        val s = shops.get(uid, forUpdate = true) ?: throw ctx.notFound()
        if (s.status == "suspended") throw suspended(ctx, ListingKind.PHARMACY)
        log.use(ctx)
        setField(ListingKind.PHARMACY, uid, "delivery", "delivery", on)
        log.write("pharmacy", uid, uid, ctx.caller.uid, "owner", "delivery", on)
        return linkedMapOf("ok" to true, "delivery" to on)
    }

    // ------------------------------------------------------------------ lab

    private fun myLab(ctx: ActionContext): Map<String, Any?> {
        val x = rows.load(ListingKind.LAB, ctx.user.id) ?: throw ctx.notFound()
        if (x["status"] == "suspended") throw suspended(ctx, ListingKind.LAB)
        return x
    }

    /** lab_open_override {open: true|false|null}; the end comes from the free-text hours when understood, else midnight */
    @Transactional
    fun labOpen(ctx: ActionContext): Any {
        val uid = ctx.user.id
        val open = openArg(ctx)
        val x = myLab(ctx)
        val now = System.currentTimeMillis()
        val asked = untilArg(ctx, open, now)
        log.use(ctx)
        val hours = ListingFacts.parseHours(x["hours"] as? String)?.asMap()
        val until = if (open == null) null else asked ?: ListingFacts.overrideUntil(hours, now)
        jdbc.update("UPDATE labs SET open_override = ?, open_override_until = ? WHERE uid = ?", open, until?.let { Sql.ts(it) }, uid)
        log.write("lab", uid, uid, ctx.caller.uid, "owner", "open", open, until)
        val v = LinkedHashMap<String, Any?>()
        ListingFacts.putTextHours(v, x["hours"] as? String, now)
        if (open != null) v["openNow"] = open
        return linkedMapOf("ok" to true, "openNow" to v["openNow"], "openOverride" to ListingFacts.activeOverride(open, until, now))
    }

    /** lab_home_collection_set {homeCollection: bool} */
    @Transactional
    fun labHome(ctx: ActionContext): Any {
        val uid = ctx.user.id
        val on = boolArg(ctx, "homeCollection")
        myLab(ctx)
        log.use(ctx)
        setField(ListingKind.LAB, uid, "home_collection", "homeCollection", on)
        log.write("lab", uid, uid, ctx.caller.uid, "owner", "homeCollection", on)
        return linkedMapOf("ok" to true, "homeCollection" to on)
    }

    /** one data field; an approved listing's "last approved" snapshot follows (like an edit that needs no review) */
    private fun setField(kind: ListingKind, uid: String, column: String, node: String, value: Any?) {
        val cur = rows.load(kind, uid) ?: return
        val set = SqlSet().value(column, value).ms("updated_at", System.currentTimeMillis())
        ListingRules.keepApproved(cur, mapOf(node to value), false)?.let { set.json("last_approved", rows.toJson(it)) }
        set.update(jdbc, kind.table, "${kind.idColumn} = ?", uid)
    }

    /** the labs' open-now overrides that still last: uid → open */
    fun labOverrides(ids: Collection<String>, now: Long): Map<String, Map<String, Any?>> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<String, Map<String, Any?>>()
        for (chunk in ids.distinct().chunked(500)) {
            jdbc.query(
                "SELECT uid, open_override, open_override_until FROM labs WHERE open_override IS NOT NULL AND open_override_until > ? " +
                    "AND uid IN (${chunk.joinToString(",") { "?" }})",
                RowCallbackHandler { rs ->
                    ListingFacts.activeOverride(rs.getBoolean(2), rs.getTimestamp(3).time, now)?.let { out[rs.getString(1)] = it }
                },
                *(listOf<Any?>(Sql.ts(now)) + chunk).toTypedArray(),
            )
        }
        return out
    }
}

/** pharmacy_open_override, pharmacy_delivery_set, lab_open_override, lab_home_collection_set (LISTINGS.md) */
@Component
class ListingSwitchActions(private val switches: ListingSwitches) : ActionModule {
    override fun actions(): Map<String, ActionHandler> = mapOf(
        "pharmacy_open_override" to ActionHandler { switches.pharmacyOpen(it) },
        "pharmacy_delivery_set" to ActionHandler { switches.pharmacyDelivery(it) },
        "lab_open_override" to ActionHandler { switches.labOpen(it) },
        "lab_home_collection_set" to ActionHandler { switches.labHome(it) },
    )
}
