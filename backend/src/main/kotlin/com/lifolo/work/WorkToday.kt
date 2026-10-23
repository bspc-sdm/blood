package com.lifolo.work

import com.lifolo.admin.kernel.ListingRules
import com.lifolo.ambulance.AmbClock
import com.lifolo.ambulance.AmbReqService
import com.lifolo.ambulance.AmbRepo
import com.lifolo.ambulance.AmbRules
import com.lifolo.appointments.Appt
import com.lifolo.appointments.ApptRepo
import com.lifolo.appointments.BadgeLookup
import com.lifolo.appointments.CareRules
import com.lifolo.appointments.ChamberDays
import com.lifolo.appointments.NextSlots
import com.lifolo.appointments.OnlineSwitch
import com.lifolo.appointments.P2Clock
import com.lifolo.appointments.ProviderAssistantsStore
import com.lifolo.appointments.ProviderDoc
import com.lifolo.appointments.ProviderStore
import com.lifolo.appointments.QueueActions
import com.lifolo.appointments.QueueRules
import com.lifolo.blood.BankStock
import com.lifolo.common.ApiException
import com.lifolo.common.DhakaTime
import com.lifolo.common.Lang
import com.lifolo.common.Sql
import com.lifolo.common.action.ActionContext
import com.lifolo.common.action.ActionHandler
import com.lifolo.common.action.ActionModule
import com.lifolo.lab.LabActions
import com.lifolo.lab.LabService
import com.lifolo.lab.LabStore
import com.lifolo.listings.ListingFacts
import com.lifolo.listings.ListingSwitches
import com.lifolo.pharmacy.MedFindService
import com.lifolo.pharmacy.P4Clock
import com.lifolo.pharmacy.RxPharmacyService
import com.lifolo.pharmacy.Shop
import com.lifolo.pharmacy.ShopRepo
import com.lifolo.practice.Practice
import com.lifolo.practice.PracticeGate
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service

/**
 * MYWORK.md — `work_today`: everything the "আমার কাজ" home needs in one call. Per business: open/break/closed state,
 * today's numbers, the open tasks ("এখনই দেখুন", one action each) and the setup checklist. Read-only; every part reuses
 * the code of the screen it summarizes (queue_today, rx_orders_pharmacy, lab_orders, ambreq_driver, med_find_requests),
 * so the numbers here are the numbers on those screens. A switched-off feature adds nothing (online chambers,
 * physio/psychologist).
 */
@Service
class WorkToday(
    private val jdbc: JdbcTemplate,
    private val badges: BadgeLookup,
    private val store: ProviderStore,
    private val appts: ApptRepo,
    private val queue: QueueActions,
    private val chamberDays: ChamberDays,
    private val assistants: ProviderAssistantsStore,
    private val onlineSwitch: OnlineSwitch,
    private val gate: PracticeGate,
    private val p2clock: P2Clock,
    private val shops: ShopRepo,
    private val p4clock: P4Clock,
    private val rxPharmacy: RxPharmacyService,
    private val medFind: MedFindService,
    private val labs: LabService,
    private val labActions: LabActions,
    private val labStore: LabStore,
    private val switches: ListingSwitches,
    private val ambRepo: AmbRepo,
    private val ambReqs: AmbReqService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** tasks in the merged "এখনই দেখুন" list */
        const val TOP_TASKS = 5

        /** tasks per business */
        const val BIZ_TASKS = 20

        /** chip order of the home (সব · চেম্বার · ফার্মেসি · ল্যাব · ব্লাড ব্যাংক · অ্যাম্বুলেন্স) */
        val KIND_ORDER = listOf("doctor", "homecare", "pharmacy", "lab", "bloodbank", "ambulance")

        /** lower = more urgent (then newest first) */
        val PRIORITY = mapOf(
            "call" to 0, "queue_next" to 1, "late_ask" to 1, "order_new" to 2, "lab_new" to 2, "lab_report" to 3,
            "appt_request" to 3, "find" to 4, "stock" to 5,
        )
        private const val DAY_MS = 24L * 3600 * 1000
        private val TIME_RE = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")

        /** open / paused (+ until) / closed from a listing's override; [reopens] = are the hours open again at that time */
        fun overrideStatus(open: Boolean?, until: Long?, now: Long, reopens: (Long) -> Boolean): Pair<String, Long?>? {
            if (open == null || until == null || until <= now) return null
            if (open) return "open" to null
            return if (reopens(until)) "paused" to until else "closed" to null
        }
    }

    /** one task row ("এখনই দেখুন") */
    private fun task(kind: String, bizId: String, type: String, id: String, title: String, sub: String, at: Long, action: String, link: String) =
        linkedMapOf<String, Any?>(
            "kind" to kind, "bizId" to bizId, "type" to type, "id" to id, "title" to title, "sub" to sub, "at" to at, "action" to action,
            "link" to link,
        )

    private fun sortTasks(list: List<Map<String, Any?>>): List<Map<String, Any?>> =
        list.sortedWith(compareBy<Map<String, Any?>> { PRIORITY[it["type"]] ?: 9 }.thenByDescending { (it["at"] as? Number)?.toLong() ?: 0L })

    private fun biz(kind: String, id: String, name: String?, role: String = "owner"): LinkedHashMap<String, Any?> = linkedMapOf(
        "kind" to kind, "id" to id, "name" to (name ?: ""), "role" to role, "listingStatus" to null, "status" to null, "pausedUntil" to null,
        "counts" to LinkedHashMap<String, Any?>(), "tasks" to emptyList<Any>(), "setup" to emptyList<Any>(),
    )

    private fun setup(vararg items: Pair<String, Boolean>) = items.map { linkedMapOf("key" to it.first, "done" to it.second) }

    /** a part that fails must not take the whole home down (the screen of that part shows the real error) */
    private fun <T> safe(what: String, f: () -> T): T? = try {
        f()
    } catch (e: ApiException) {
        null
    } catch (e: Exception) {
        log.warn("work_today {} failed: {}", what, e.toString())
        null
    }

    fun today(ctx: ActionContext): Any {
        val now = p2clock.now()
        val out = linkedMapOf<String, Any?>("now" to now, "date" to DhakaTime.todayUtcMidnight(now))
        // a caregiver acts on the patient side only: never a doctor's or shop's work
        if (ctx.user.patientSideOnly || ctx.user.guest) {
            out["roles"] = emptyList<String>()
            out["items"] = emptyList<Any>()
            out["tasks"] = emptyList<Any>()
            out["taskCount"] = 0
            return out
        }
        val uid = ctx.user.id
        val items = ArrayList<LinkedHashMap<String, Any?>>()
        safe("pro") { pro(ctx, uid) }?.let { items.addAll(it) }
        safe("assistant") { assisting(ctx, uid) }?.let { items.addAll(it) }
        safe("pharmacy") { pharmacy(ctx, uid) }?.let { items.add(it) }
        safe("lab") { lab(ctx, uid) }?.let { items.add(it) }
        safe("bloodbank") { bloodBanks(ctx, uid) }?.let { items.addAll(it) }
        safe("ambulance") { ambulance(ctx, uid) }?.let { items.add(it) }
        items.sortBy { KIND_ORDER.indexOf(it["kind"]) }
        @Suppress("UNCHECKED_CAST")
        val all = sortTasks(items.flatMap { it["tasks"] as List<Map<String, Any?>> })
        out["roles"] = items.map { it["kind"] as String }.distinct()
        out["items"] = items
        out["tasks"] = all.take(TOP_TASKS)
        out["taskCount"] = all.size
        return out
    }

    // ------------------------------------------------------------------ doctor (and other chamber professionals)

    private fun bn(ctx: ActionContext) = ctx.lang == "bn"

    private fun num(ctx: ActionContext, n: Any): String = if (bn(ctx)) Lang.bnDigits(n.toString()) else n.toString()

    private fun hasPhoto(uid: String): Boolean = jdbc.queryForList(
        "SELECT 1 FROM user_profiles WHERE uid = ? AND (photo_thumb IS NOT NULL OR photo_thumb_key IS NOT NULL)", uid,
    ).isNotEmpty()

    /** the caller's own chamber (doctor, nutritionist, trainer, physio, psychologist) or home care (nurse) */
    private fun pro(ctx: ActionContext, uid: String): List<LinkedHashMap<String, Any?>> {
        val type = badges.approvedBadge(uid) ?: return emptyList()
        // physio / psychologist while their switch is off: nothing
        if (!gate.typeOn(uid, type)) return emptyList()
        val p = store.load(uid)
        if (type in QueueRules.CHAMBER_TYPES) return listOf(doctor(ctx, uid, type, p, "owner"))
        if (type != "nurse") return emptyList()
        val b = biz("homecare", uid, p?.name?.takeIf { it.isNotEmpty() } ?: ctx.tr("Home care", "বাসায় সেবা")) // bn-ok
        b["proType"] = type
        b["listingStatus"] = "approved"
        b["setup"] = setup("photo" to hasPhoto(uid), "homeVisit" to (p?.homeVisit?.enabled == true))
        return listOf(b)
    }

    /** the doctors whose assistant the caller is (verified email on the doctor's list) */
    private fun assisting(ctx: ActionContext, uid: String): List<LinkedHashMap<String, Any?>> {
        val u = ctx.user
        val email = if (u.emailVerified) u.email.lowercase() else ""
        if (email.isEmpty()) return emptyList()
        return assistants.doctorsFor(email, 10).filter { it != uid }.mapNotNull { d ->
            val type = badges.approvedBadge(d)
            if (type !in Practice.ASSISTED_TYPES || !gate.typeOn(d, type)) return@mapNotNull null
            safe("assistant $d") { doctor(ctx, d, type!!, store.load(d), "assistant") }
        }
    }

    private fun doctor(ctx: ActionContext, doc: String, type: String, p: ProviderDoc?, role: String): LinkedHashMap<String, Any?> {
        val b = biz("doctor", doc, p?.name, role)
        b["proType"] = type
        b["listingStatus"] = "approved"
        val now = p2clock.now()
        val today = DhakaTime.todayUtcMidnight(now)
        // online chambers only while online_chamber is on for the person at the phone and for the doctor
        val onlineOk = onlineSwitch.allowed(ctx.caller.uid ?: ctx.user.uid) && onlineSwitch.allowed(doc)
        val chambers = (p?.chambers ?: emptyList()).withIndex().filter { !it.value.online || onlineOk }
        val keys = chambers.map { it.value.key }.toSet()
        val closed = chamberDays.closedFrom(listOf(doc), now)
        val paused = chamberDays.pausedNow(listOf(doc), now)
        val todays = appts.ofProviderOn(doc, today).filter { a -> (!a.online || onlineOk) && (a.key in keys || p == null) }
        // the queue of each chamber with serials today (what queue_today shows)
        val views = todays.filter { (it.serial ?: 0) > 0 }.map { it.key }.distinct().associateWith { k ->
            queue.chamberView(queue.load(doc, today, k, todays, p), now)
        }
        val tasks = ArrayList<Map<String, Any?>>()
        val link = if (role == "assistant") "/work/rx/patients?doctor=$doc" else "/work/pro"
        var waiting = 0
        val chOut = chambers.map { (i, ch) ->
            val sits = CareRules.chamberOpenOn(ch, today)
            val closedToday = ChamberDays.key(doc, ch.key, today) in closed
            val until = paused["$doc|${ch.key}"]
            val status = when {
                !sits || closedToday -> "closed"
                until != null -> "paused"
                else -> "open"
            }
            val name = if (bn(ctx)) ch.hospitalNameBn.ifEmpty { ch.hospitalName } else ch.hospitalName.ifEmpty { ch.hospitalNameBn }
            val v = views[ch.key]
            if (v != null) {
                @Suppress("UNCHECKED_CAST")
                val counts = v["counts"] as Map<String, Int>
                waiting += counts["waiting"] ?: 0
                @Suppress("UNCHECKED_CAST")
                val q = v["queue"] as Map<String, Any?>
                val next = q["next"] as? Int
                val left = (q["left"] as? Int) ?: 0
                // "ডাকুন": patients wait and the chamber is running (started, or inside its hours) and not closed
                if (next != null && left > 0 && status != "closed" && (q["started"] == true || CareRules.chamberNowOpen(ch, now))) {
                    tasks.add(
                        task(
                            "doctor", doc, "queue_next", ch.key, name,
                            ctx.tr("Next: #$next · ${left} waiting", "পরের সিরিয়াল #${num(ctx, next)} · অপেক্ষায় ${num(ctx, left)} জন"), // bn-ok
                            now, "next", link,
                        ),
                    )
                }
                @Suppress("UNCHECKED_CAST")
                for (r in v["rows"] as List<Map<String, Any?>>) {
                    @Suppress("UNCHECKED_CAST")
                    val late = r["late"] as Map<String, Any?>? ?: continue
                    if (late["status"] != "asked" || (r["state"] != "waiting" && r["state"] != "later")) continue
                    val min = (late["min"] as? Int) ?: 0
                    tasks.add(
                        task(
                            "doctor", doc, "late_ask", r["id"] as String, (r["name"] as? String) ?: "",
                            ctx.tr("#${r["serial"]} · $min min late", "#${num(ctx, r["serial"] ?: 0)} · ${num(ctx, min)} মিনিট দেরি হবে"), // bn-ok
                            (late["at"] as? Long) ?: now, "reply", link,
                        ),
                    )
                }
            }
            linkedMapOf<String, Any?>(
                "chamberIndex" to i, "chamberKey" to ch.key, "name" to ch.hospitalName, "nameBn" to ch.hospitalNameBn, "online" to ch.online,
                "sitsToday" to sits, "closedToday" to closedToday, "sessionNow" to (sits && !closedToday && CareRules.chamberNowOpen(ch, now)),
                "status" to status, "pausedUntil" to (if (status == "paused") until else null),
            )
        }
        b["chambers"] = chOut
        b["status"] = when {
            chOut.any { it["status"] == "open" } -> "open"
            chOut.any { it["status"] == "paused" } -> "paused"
            else -> "closed"
        }
        b["pausedUntil"] = if (b["status"] == "paused") chOut.mapNotNull { it["pausedUntil"] as? Long }.minOrNull() else null
        val counts = linkedMapOf<String, Any?>(
            "today" to todays.count { it.status in QueueRules.ACTIVE && (it.serial ?: 0) > 0 },
            "waiting" to waiting,
        )
        // new requests: only the doctor confirms them (appointment_update), so only the doctor gets the number and the tasks
        if (role == "owner") {
            val pending = appts.ofProviderWithStatus(doc, "pending", 500)
                .filter { it.date >= today && (!it.online || onlineOk) }
                .sortedByDescending { it.createdAt }
            counts["requests"] = pending.size
            pending.take(BIZ_TASKS).forEach { a -> tasks.add(requestTask(ctx, doc, a, today)) }
            b["setup"] = doctorSetup(doc, p, today)
        }
        b["counts"] = counts
        b["tasks"] = sortTasks(tasks).take(BIZ_TASKS)
        return b
    }

    private fun requestTask(ctx: ActionContext, doc: String, a: Appt, today: Long): Map<String, Any?> {
        val chamber = if (bn(ctx)) a.chamberBn.ifEmpty { a.chamber } else a.chamber.ifEmpty { a.chamberBn }
        val day = NextSlots.label(a.date, today, ctx.lang)
        return task("doctor", doc, "appt_request", a.id, a.patientName, listOf(chamber, day).filter { it.isNotEmpty() }.joinToString(" · "), a.createdAt, "accept", "/work/pro")
    }

    private fun doctorSetup(doc: String, p: ProviderDoc?, today: Long): List<Map<String, Any?>> {
        val chs = p?.chambers ?: emptyList()
        return setup(
            "photo" to hasPhoto(doc),
            "chamber" to chs.isNotEmpty(),
            "time" to chs.any { ch -> (0..6).any { CareRules.chamberOpenOn(ch, today + it * DAY_MS) } },
            "address" to chs.any { it.online || !it.address.isNullOrBlank() || (it.upazilaId ?: 0) != 0 || it.hospitalName.isNotBlank() },
            "fee" to chs.any { it.noFee == true || CareRules.feeAmount(it) != null },
        )
    }

    // ------------------------------------------------------------------ pharmacy

    /** pharmacy state: a break (closedUntil) or a closing override that ends while the hours are open → paused */
    fun pharmacyStatus(s: Shop, now: Long): Pair<String, Long?> {
        val pause = s.closedUntil ?: 0
        if (pause > now) return "paused" to pause
        overrideStatus(s.openOverride, s.openOverrideUntil, now) { t -> ListingRules.openNow(s.schedule(), t) }?.let { return it }
        return (if (s.openNow(now)) "open" else "closed") to null
    }

    private fun pharmacy(ctx: ActionContext, uid: String): LinkedHashMap<String, Any?>? {
        val s = shops.get(uid) ?: return null
        val now = p4clock.now()
        val b = biz("pharmacy", uid, s.name)
        b["listingStatus"] = s.status
        val (status, until) = pharmacyStatus(s, now)
        b["status"] = status
        b["pausedUntil"] = until
        val counts = LinkedHashMap<String, Any?>()
        val tasks = ArrayList<Map<String, Any?>>()
        if (s.status == "approved") {
            safe("pharmacy orders") { rxPharmacy.ordersForPharmacy(ctx) }?.let { r ->
                @Suppress("UNCHECKED_CAST")
                val m = r as Map<String, Any?>
                @Suppress("UNCHECKED_CAST")
                val c = m["counts"] as Map<String, Int>
                counts["new"] = c["new"] ?: 0
                counts["active"] = c["active"] ?: 0
                @Suppress("UNCHECKED_CAST")
                for (o in m["items"] as List<Map<String, Any?>>) {
                    if (o["seg"] != "new") continue
                    val online = o["mode"] == "online"
                    val code = (o["code"] as? String) ?: ""
                    tasks.add(
                        task(
                            "pharmacy", uid, "order_new", o["id"] as String, (o["patientName"] as? String) ?: "",
                            listOf(if (online) ctx.tr("Home delivery", "বাসায় পৌঁছে দিন") else ctx.tr("Pick up at the shop", "দোকানে এসে নেবেন"), code) // bn-ok
                                .filter { it.isNotEmpty() }.joinToString(" · "),
                            (o["createdAt"] as? Long) ?: now, if (online) "accept" else "ready", "/work/pharmacy/orders/rx/$code",
                        ),
                    )
                }
            }
        }
        // counter sales today (the books' day totals, the same numbers as বিক্রি)
        val day = jdbc.queryForList(
            "SELECT sales, net FROM shop_days WHERE shop_uid = ? AND day = ?", uid, Sql.date(DhakaTime.day(now)),
        ).firstOrNull()
        counts["salesToday"] = (day?.get("sales") as? Number)?.toInt() ?: 0
        counts["salesTodayTk"] = (day?.get("net") as? Number)?.toDouble()?.let { if (it == Math.floor(it)) it.toLong() else it } ?: 0
        if (s.status == "approved" && !s.findOff) {
            safe("finds") { medFind.requests(ctx) }?.let { r ->
                @Suppress("UNCHECKED_CAST")
                val open = ((r as Map<String, Any?>)["items"] as List<Map<String, Any?>>).filter { it["answer"] == null }
                counts["finds"] = open.size
                open.forEach { f ->
                    tasks.add(
                        task(
                            "pharmacy", uid, "find", f["id"] as String, (f["drug"] as? String) ?: "", (f["area"] as? String) ?: "",
                            (f["createdAt"] as? Long) ?: now, "answer", "/work/pharmacy/finds",
                        ),
                    )
                }
            }
        }
        b["counts"] = counts
        b["tasks"] = sortTasks(tasks).take(BIZ_TASKS)
        b["setup"] = setup(
            "approved" to (s.status == "approved"),
            "hours" to (s.open24 || (TIME_RE.matches(s.openAt) && TIME_RE.matches(s.closeAt))),
            "address" to s.address.isNotBlank(),
            "location" to (s.lat != null && s.lng != null),
        )
        return b
    }

    // ------------------------------------------------------------------ lab

    private fun lab(ctx: ActionContext, uid: String): LinkedHashMap<String, Any?>? {
        val x = labs.lab(uid) ?: return null
        val now = System.currentTimeMillis()
        val b = biz("lab", uid, x["name"] as? String)
        val st = x["status"] as? String
        b["listingStatus"] = st
        val hours = ListingFacts.parseHours(x["hours"] as? String)?.asMap()
        val ov = switches.labOverrides(listOf(uid), now)[uid]
        val byOverride = overrideStatus(ov?.get("open") as? Boolean, ov?.get("until") as? Long, now) { t -> hours == null || ListingRules.openNow(hours, t) }
        // hours not understood (free text) and no override: open (requests are taken at any time)
        val (status, until) = byOverride ?: ((if (hours == null || ListingRules.openNow(hours, now)) "open" else "closed") to null)
        b["status"] = status
        b["pausedUntil"] = until
        val counts = LinkedHashMap<String, Any?>()
        val tasks = ArrayList<Map<String, Any?>>()
        if (st == "approved") {
            safe("lab orders") { labActions.labOrders(ctx) }?.let { r ->
                @Suppress("UNCHECKED_CAST")
                val m = r as Map<String, Any?>
                @Suppress("UNCHECKED_CAST")
                val c = m["counts"] as Map<String, Int>
                counts["new"] = c["new"] ?: 0
                counts["active"] = c["active"] ?: 0
                counts["reported"] = c["reported"] ?: 0
                @Suppress("UNCHECKED_CAST")
                for (o in m["items"] as List<Map<String, Any?>>) {
                    val seg = o["seg"]
                    if (seg != "new" && seg != "active") continue
                    val received = o["status"] == "received"
                    @Suppress("UNCHECKED_CAST")
                    val tests = (o["tests"] as? List<String>) ?: emptyList()
                    val id = o["id"] as String
                    tasks.add(
                        task(
                            "lab", uid, if (received) "lab_report" else "lab_new", id, (o["patientName"] as? String) ?: "",
                            tests.take(3).joinToString(", ") + if (tests.size > 3) " …" else "",
                            (o["createdAt"] as? Long) ?: now, if (received) "report" else "receive", "/work/lab/orders/$id",
                        ),
                    )
                }
            }
        }
        b["counts"] = counts
        b["tasks"] = sortTasks(tasks).take(BIZ_TASKS)
        b["setup"] = setup(
            "approved" to (st == "approved"),
            "hours" to !(x["hours"] as? String).isNullOrBlank(),
            "address" to !(x["address"] as? String).isNullOrBlank(),
            "prices" to (safe("lab prices") { labStore.prices(uid) }?.items?.isNotEmpty() == true),
        )
        return b
    }

    // ------------------------------------------------------------------ blood banks (one business per bank)

    private fun bloodBanks(ctx: ActionContext, uid: String): List<LinkedHashMap<String, Any?>> {
        val now = System.currentTimeMillis()
        val rows = jdbc.queryForList(
            """
            SELECT b.id, b.name, b.status, b.address, b.open24, b.open_at, b.close_at, s.updated_at AS stock_at
            FROM blood_banks b LEFT JOIN blood_bank_stock s ON s.bank_id = b.id
            WHERE b.owner_uid = ? ORDER BY b.created_at, b.id LIMIT 3
            """.trimIndent(),
            uid,
        )
        return rows.map { r ->
            val id = r["id"] as String
            val b = biz("bloodbank", id, r["name"] as? String)
            val st = r["status"] as? String
            b["listingStatus"] = st
            val at = (r["stock_at"] as? java.sql.Timestamp)?.time
            val stale = at != null && now - at > BankStock.STALE_MS
            b["stockUpdatedAt"] = at
            b["stockStale"] = stale
            // "স্টক দিন": never set, or older than 48 h (shown as "পুরনো তথ্য")
            if (st == "approved" && (at == null || stale)) {
                b["tasks"] = listOf(
                    task(
                        "bloodbank", id, "stock", id, (r["name"] as? String) ?: "",
                        if (at == null) ctx.tr("No stock news yet", "স্টকের খবর এখনো দেওয়া হয়নি") else ctx.tr("Old news", "পুরনো তথ্য"), // bn-ok
                        at ?: 0L, "stock", "/work/bloodbank",
                    ),
                )
            }
            val open24 = r["open24"] == true
            b["setup"] = setup(
                "approved" to (st == "approved"),
                "hours" to (open24 || (TIME_RE.matches((r["open_at"] as? String) ?: "") && TIME_RE.matches((r["close_at"] as? String) ?: ""))),
                "address" to !(r["address"] as? String).isNullOrBlank(),
                "stock" to (at != null),
            )
            b
        }
    }

    // ------------------------------------------------------------------ ambulances (one business per owner, vehicles inside)

    private fun ambulance(ctx: ActionContext, uid: String): LinkedHashMap<String, Any?>? {
        val list = ambRepo.ofOwner(uid, AmbRules.MAX_PER_OWNER)
        if (list.isEmpty()) return null
        val now = AmbClock.now()
        val vehicles = list.map { a ->
            val v = AmbRules.view(a, now, true)
            linkedMapOf<String, Any?>(
                "id" to a.id, "name" to a.name, "vehicleNo" to a.vehicleNo, "types" to a.types, "listingStatus" to a.status,
                "available" to (a.status == "approved" && v["available"] == true), "availableUntil" to v["availableUntil"],
            )
        }
        val b = biz("ambulance", uid, list.first().name)
        b["listingStatus"] = when {
            list.any { it.status == "approved" } -> "approved"
            else -> list.first().status
        }
        b["vehicles"] = vehicles
        // খালি আছি / ট্রিপে আছি — no break for ambulances
        b["status"] = if (vehicles.any { it["available"] == true }) "open" else "closed"
        val counts = LinkedHashMap<String, Any?>()
        val tasks = ArrayList<Map<String, Any?>>()
        if (list.any { it.status == "approved" }) {
            safe("ambulance calls") { ambReqs.driverList(ctx.body, uid) }?.let { r ->
                @Suppress("UNCHECKED_CAST")
                val calls = r["items"] as List<Map<String, Any?>>
                val open = calls.filter { it["status"] == "open" }
                counts["calls"] = open.size
                counts["trips"] = calls.size - open.size
                open.forEach { c ->
                    val where = listOf((c["pickup"] as? String) ?: "", (c["district"] as? String) ?: "").filter { it.isNotEmpty() }.joinToString(" · ")
                    tasks.add(
                        task(
                            "ambulance", uid, "call", c["id"] as String, ctx.tr("Ambulance needed", "অ্যাম্বুলেন্স দরকার"), where, // bn-ok
                            (c["createdAt"] as? Long) ?: now, "accept", "/work/ambulance",
                        ),
                    )
                }
            }
        }
        b["counts"] = counts
        b["tasks"] = sortTasks(tasks).take(BIZ_TASKS)
        b["setup"] = setup(
            "approved" to list.all { it.status == "approved" },
            "base" to list.all { it.lat != null },
        )
        return b
    }
}

/** work_today (MYWORK.md) */
@Component
class WorkTodayActions(private val work: WorkToday) : ActionModule {
    override fun actions(): Map<String, ActionHandler> = mapOf(
        "work_today" to ActionHandler { work.today(it) },
    )
}
