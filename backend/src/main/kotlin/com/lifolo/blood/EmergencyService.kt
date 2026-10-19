package com.lifolo.blood

import com.lifolo.auth.AdminService
import com.lifolo.common.Js
import com.lifolo.common.Lang
import com.lifolo.common.Phones
import com.lifolo.common.Places
import com.lifolo.common.Rules
import com.lifolo.common.Sql
import com.lifolo.common.action.ActionContext
import com.lifolo.common.ms
import com.lifolo.common.stringArray
import com.lifolo.contracts.HospitalDirectory
import com.lifolo.demo.DemoWorld
import com.lifolo.notify.PushItem
import com.lifolo.notify.PushService
import com.lifolo.notify.PushText
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.stereotype.Service

/**
 * emergency.js — emergency blood appeals run by the Lifolo team from the web panel. Each wave tells 3 × the bags
 * still needed per group (nearest eligible donors first); "coming" is counted and a full group closes itself;
 * when the appeal ends the "coming" donors are told.
 */
@Service
class EmergencyService(
    private val store: BloodStore,
    private val geo: GeoQuery,
    private val push: PushService,
    private val admins: AdminService,
    private val hospitals: HospitalDirectory,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val jdbc get() = store.jdbc

    data class Emergency(
        val id: String,
        val district: String,
        val districts: List<String>,
        val hospitalId: String?,
        val hospitalName: String,
        val lat: Double,
        val lng: Double,
        val exactLocation: Boolean,
        val radiusKm: Int,
        val contactPhone: String,
        val note: String,
        val status: String,
        val waves: Int,
        val createdAt: Long,
        val createdBy: String,
        val endsAt: Long,
        val closedAt: Long?,
        val demo: Boolean,
        val groups: LinkedHashMap<String, EmergencyRules.Group>,
    ) {
        fun isLive(now: Long = System.currentTimeMillis()) = EmergencyRules.isLive(status, endsAt, now)
    }

    private fun load(where: String, vararg args: Any?): List<Emergency> {
        val list = jdbc.query("SELECT * FROM emergencies WHERE $where", { rs, _ ->
            Emergency(
                id = rs.getString("id"),
                district = rs.getString("district"),
                districts = rs.stringArray("districts") ?: emptyList(),
                hospitalId = rs.getString("hospital_id"),
                hospitalName = rs.getString("hospital_name"),
                lat = rs.getDouble("lat"),
                lng = rs.getDouble("lng"),
                exactLocation = rs.getBoolean("exact_location"),
                radiusKm = rs.getInt("radius_km"),
                contactPhone = rs.getString("contact_phone") ?: "",
                note = rs.getString("note") ?: "",
                status = rs.getString("status"),
                waves = rs.getInt("waves"),
                createdAt = rs.ms("created_at") ?: 0L,
                createdBy = rs.getString("created_by") ?: "",
                endsAt = rs.ms("ends_at") ?: 0L,
                closedAt = rs.ms("closed_at"),
                demo = rs.getBoolean("is_demo"),
                groups = LinkedHashMap(),
            )
        }, *args)
        if (list.isEmpty()) return list
        val byId = list.associateBy { it.id }
        jdbc.query(
            "SELECT * FROM emergency_groups WHERE emergency_id IN (${list.joinToString(",") { "?" }})",
            RowCallbackHandler { rs ->
                val adminClosed = rs.getBoolean("admin_closed").let { if (rs.wasNull()) null else it }
                byId[rs.getString("emergency_id")]?.groups?.put(
                    rs.getString("blood_group"),
                    EmergencyRules.Group(rs.getInt("need"), rs.getInt("coming"), rs.getInt("notified"), rs.getBoolean("closed"), adminClosed),
                )
            },
            *list.map { it.id }.toTypedArray(),
        )
        // groups in blood-group order
        for (e in list) {
            val sorted = e.groups.entries.sortedBy { Rules.BLOOD_GROUPS.indexOf(it.key) }.map { it.key to it.value }
            e.groups.clear()
            sorted.forEach { (k, v) -> e.groups[k] = v }
        }
        return list
    }

    private fun one(id: String, forUpdate: Boolean = false): Emergency? = load("id = ?${if (forUpdate) " FOR UPDATE" else ""}", id).firstOrNull()

    private fun saveGroups(e: Emergency) {
        for ((g, x) in e.groups) {
            jdbc.update(
                "UPDATE emergency_groups SET need = ?, coming = ?, notified = ?, closed = ?, admin_closed = ? WHERE emergency_id = ? AND blood_group = ?",
                x.need, x.coming, x.notified, x.closed, x.adminClosed, e.id, g,
            )
        }
    }

    private fun requireAdmin(ctx: ActionContext) {
        if (!admins.isAdmin(ctx.user)) throw ctx.err(403, "Admins only", "শুধু অ্যাডমিন")
    }

    /** one wave: nearest eligible donors of the open groups who were not told before */
    fun sendWave(id: String): Map<String, Any?> {
        val e = one(id) ?: return linkedMapOf("sent" to 0)
        if (!e.isLive()) return linkedMapOf("sent" to 0)
        val openGroups = e.groups.filter { (_, x) -> !x.closed && x.coming < x.need }.keys
        if (openGroups.isEmpty()) return linkedMapOf("sent" to 0)
        val found = geo.nearDonors(openGroups, e.lat, e.lng, e.radiusKm.toDouble())
        val already = jdbc.queryForList("SELECT uid FROM emergency_donors WHERE emergency_id = ?", String::class.java, id).toHashSet()
        // a real appeal never reaches demo donors and a demo one never real donors
        val users = found.map { it.item }.filter { DemoWorld.isDemoUid(it.uid) == e.demo }
        val now = System.currentTimeMillis()
        val wave = EmergencyRules.pickWave(users, e.lat, e.lng, e.groups, e.radiusKm, already, now)
        if (wave.isEmpty()) return linkedMapOf("sent" to 0)
        // written first, so pressing "notify more" twice never reaches anyone twice
        for (u in wave) {
            jdbc.update(
                "INSERT INTO emergency_donors (emergency_id, uid, blood_group, km, status, notified_at) VALUES (?, ?, ?, ?, 'notified', ?) ON CONFLICT DO NOTHING",
                id, u.uid, u.group, BloodRequest.roundKm1(u.km), Sql.ts(now),
            )
        }
        val perGroup = wave.groupingBy { it.group }.eachCount()
        store.inTx {
            val cur = one(id, forUpdate = true) ?: return@inTx
            perGroup.forEach { (g, n) -> cur.groups[g]?.let { it.notified += n } }
            saveGroups(cur)
            jdbc.update("UPDATE emergencies SET waves = waves + 1, last_wave_at = ? WHERE id = ?", Sql.ts(now), id)
            Unit
        }
        val sent = push.send(
            wave.map { u ->
                PushItem(
                    u.uid,
                    Lang.pick(u.lang, "Emergency: ${u.group} blood needed", "জরুরি: ${u.group} রক্ত দরকার"),
                    Lang.pick(
                        u.lang,
                        "${e.hospitalName} · ${BloodRules.formatKm(u.km, "en")} · Lifolo team appeal — open to say you're coming",
                        "${e.hospitalName} · ${BloodRules.formatKm(u.km, "bn")} · Lifolo টিমের আহ্বান — খুলে \"আসছি\" চাপুন",
                    ),
                    mapOf("type" to "emergency", "emergencyId" to id),
                )
            },
        )
        return linkedMapOf("sent" to sent, "notified" to wave.size)
    }

    /** the app's view (with my own state) */
    private fun view(e: Emergency, me: Donor?, myStatus: String?): Map<String, Any?> {
        val groups = LinkedHashMap<String, Map<String, Any?>>()
        e.groups.forEach { (g, x) -> groups[g] = linkedMapOf("need" to x.need, "coming" to x.coming, "full" to (x.closed || x.coming >= x.need)) }
        val myGroup = me?.bloodGroup?.takeIf { it.isNotEmpty() }
        val g = myGroup?.let { groups[it] }
        val eligible = me != null && BloodRules.isEligibleDonor(me)
        return linkedMapOf(
            "id" to e.id,
            "hospitalName" to e.hospitalName,
            "district" to e.district,
            "contactPhone" to Phones.fixPhone(e.contactPhone),
            "note" to e.note,
            "groups" to groups,
            "createdAt" to e.createdAt,
            "endsAt" to e.endsAt,
            "myGroup" to (myGroup ?: ""),
            "myStatus" to (myStatus ?: ""),
            // my group is needed, not full yet, and I can give now
            "canCome" to (g != null && g["full"] != true && eligible),
            "eligible" to eligible,
        )
    }

    private fun notifyComing(e: Emergency, en: String, bn: String, bodyEn: String = "", bodyBn: String = "") {
        val coming = jdbc.queryForList("SELECT uid FROM emergency_donors WHERE emergency_id = ? AND status = 'coming'", String::class.java, e.id)
        if (coming.isEmpty()) return
        push.pushUsers(coming) { _, lang ->
            PushText(
                Lang.pick(lang, en, bn),
                listOf(e.hospitalName, Lang.pick(lang, bodyEn, bodyBn)).filter { it.isNotEmpty() }.joinToString(" · "),
                mapOf("type" to "emergency", "emergencyId" to e.id),
            )
        }
    }

    private fun notifyEnded(e: Emergency) = notifyComing(
        e,
        "Emergency appeal ended", "জরুরি আহ্বান শেষ",
        "If you haven't left, no need to come. If you donated, tell us in the app. Thank you!",
        "এখনো রওনা না দিলে আর আসার দরকার নেই। রক্ত দিয়ে থাকলে অ্যাপে জানান। ধন্যবাদ!",
    )

    /** ended appeals where I said "coming" but did not say whether I gave (up to 7 days) */
    private fun askDonated(uid: String): List<Map<String, Any?>> {
        val now = System.currentTimeMillis()
        val rows = jdbc.query(
            """
            SELECT e.id, e.hospital_name, e.status, e.closed_at, e.ends_at FROM emergency_donors d JOIN emergencies e ON e.id = d.emergency_id
            WHERE d.uid = ? AND d.status = 'coming' ORDER BY d.coming_at NULLS FIRST, e.id
            """.trimIndent(),
            { rs, _ -> listOf(rs.getString("id"), rs.getString("hospital_name"), rs.getString("status"), rs.ms("closed_at"), rs.ms("ends_at")) },
            uid,
        )
        val ask = ArrayList<Map<String, Any?>>()
        // Node keeps the "coming" map and deletes stale entries (gone or ended > 7 days); here those are skipped
        val live = rows.filter { r ->
            val status = r[2] as String
            val endsAt = r[4] as Long?
            val endedAt = if (status == "closed") (r[3] as Long?) ?: endsAt else endsAt
            EmergencyRules.isLive(status, endsAt, now) || now - (endedAt ?: 0L) <= ASK_DAYS * BloodRules.DAY_MS
        }.take(10)
        for (r in live) {
            val status = r[2] as String
            val endsAt = r[4] as Long?
            if (EmergencyRules.isLive(status, endsAt, now)) continue
            val endedAt = if (status == "closed") (r[3] as Long?) ?: endsAt else endsAt
            ask.add(linkedMapOf("id" to r[0], "hospitalName" to r[1], "endedAt" to (endedAt ?: 0L)))
        }
        return ask
    }

    /** close appeals whose time is over and tell the "coming" donors (daily job; also when someone looks) */
    fun closeExpired(now: Long = System.currentTimeMillis()): Int {
        val due = load("status = 'active' ORDER BY created_at DESC LIMIT 50").filter { it.endsAt <= now }
        var n = 0
        for (d in due) {
            // two requests at once: only one closes and notifies
            val won = store.inTx {
                val cur = one(d.id, forUpdate = true)
                if (cur == null || cur.status != "active") return@inTx false
                jdbc.update(
                    "UPDATE emergencies SET status = 'closed', closed_at = ?, closed_by = 'expired' WHERE id = ?",
                    Sql.ts(if (d.endsAt != 0L) d.endsAt else now), d.id,
                )
                true
            }
            if (!won) continue
            try {
                notifyEnded(d)
            } catch (e: Exception) {
                log.error("emergency end push failed", e)
            }
            n++
        }
        return n
    }

    // ---------------------------------------------------------------- actions

    /** active appeals of my district (or that notified me); guests: the district they chose, never demo ones */
    fun active(ctx: ActionContext): Any {
        val uid = ctx.user.uid
        if (uid == null) {
            val district = ctx.body.string("district") ?: ""
            val items = load("status = 'active' ORDER BY created_at DESC LIMIT 20")
                .filter { it.isLive() && district in it.districts && !it.demo }
                .map { view(it, null, null) }
                .sortedByDescending { it["createdAt"] as Long }
            return linkedMapOf("items" to items, "ask" to emptyList<Any>())
        }
        val all = load("status = 'active' ORDER BY created_at DESC LIMIT 20")
        val me = GeoQuery.loadDonor(jdbc, uid)
        // demo appeals only for demo accounts, real ones only for real accounts
        val live = all.filter { it.isLive() && it.demo == DemoWorld.isDemoUid(uid) }
        // appeals past their time close as soon as anyone looks
        if (all.any { !it.isLive() }) {
            try {
                closeExpired()
            } catch (e: Exception) {
                log.error("emergency close failed", e)
            }
        }
        val ask = askDonated(uid)
        if (live.isEmpty()) return linkedMapOf("items" to emptyList<Any>(), "ask" to ask)
        val mine = HashMap<String, String>()
        jdbc.query(
            "SELECT emergency_id, status FROM emergency_donors WHERE uid = ? AND emergency_id IN (${live.joinToString(",") { "?" }})",
            RowCallbackHandler { rs -> mine[rs.getString(1)] = rs.getString(2) },
            *(listOf<Any>(uid) + live.map { it.id }).toTypedArray(),
        )
        val items = live
            .filter { e -> e.id in mine || (me != null && me.placeName != null && me.placeName in e.districts) }
            .map { view(it, me, mine[it.id]) }
            .sortedByDescending { it["createdAt"] as Long }
        return linkedMapOf("items" to items, "ask" to ask)
    }

    /** a "coming" donor answers whether they gave; giving sets the last donation date (only forward) */
    fun donated(ctx: ActionContext): Any {
        val id = ctx.body.string("id")
        if (!Rules.isValidId(id)) throw ctx.err(400, "Not found", "পাওয়া যায়নি")
        id!!
        val donated = ctx.body.isTrue("donated")
        val uid = ctx.user.id
        store.inTx {
            val e = one(id, forUpdate = true)
            val d = jdbc.queryForList("SELECT status, coming_at FROM emergency_donors WHERE emergency_id = ? AND uid = ? FOR UPDATE", id, uid).firstOrNull()
            val (uExists, last) = store.lastDonation(uid, forUpdate = true)
            // while the appeal runs, "did not give" is "can't come" instead (the count goes down)
            if (!donated && e != null && e.isLive()) throw ctx.err(400, "Tap \"Can't come\" instead", "\"আসতে পারছি না\" চাপুন")
            val status = d?.get("status") as String?
            if (d == null || status !in setOf("coming", "donated", "not_donated")) {
                throw ctx.err(400, "You did not say you were coming to this appeal", "এই আহ্বানে আপনি \"আসছি\" বলেননি")
            }
            // one answer only (pressing "gave" again does not push the date forward)
            if (status != "coming") return@inTx
            val now = System.currentTimeMillis()
            jdbc.update(
                "UPDATE emergency_donors SET status = ?, answered_at = ? WHERE emergency_id = ? AND uid = ?",
                if (donated) "donated" else "not_donated", Sql.ts(now), id, uid,
            )
            if (uExists) {
                // the donation day: the appeal's day (even if told later), not after today
                val comingAt = (d["coming_at"] as? java.sql.Timestamp)?.time ?: 0L
                val created = e?.createdAt ?: 0L
                val base = maxOf(comingAt, created).takeIf { it != 0L } ?: now
                val today = BloodRules.todayDhakaUtcMidnight(minOf(now, base))
                if (donated && (last == null || last < today)) store.setLastDonation(uid, today)
            }
        }
        return linkedMapOf("ok" to true)
    }

    /** donor: coming / can't come */
    fun respond(ctx: ActionContext): Any {
        val id = ctx.body.string("id")
        val op = Js.string(ctx.body["op"])
        if (!Rules.isValidId(id) || op !in setOf("coming", "cancel")) throw ctx.err(400, "Not found", "পাওয়া যায়নি")
        id!!
        val uid = ctx.user.id
        val me = GeoQuery.loadDonor(jdbc, uid)
        val g = me?.bloodGroup
        store.inTx {
            val e = one(id, forUpdate = true)
            if (e == null || !e.isLive()) throw ctx.err(400, "This appeal has ended", "এই আহ্বান শেষ হয়ে গেছে")
            if (e.demo != DemoWorld.isDemoUid(uid)) throw ctx.err(403, "Not available", "এটা আপনার জন্য নয়")
            val d = jdbc.queryForList("SELECT status, blood_group FROM emergency_donors WHERE emergency_id = ? AND uid = ? FOR UPDATE", id, uid).firstOrNull()
            val cur = d?.get("status") as String?
            val now = System.currentTimeMillis()
            if (op == "coming") {
                if (cur == "coming" || cur == "donated") return@inTx
                val x = g?.let { e.groups[it] } ?: throw ctx.err(400, "Your blood group is not needed here", "এখানে আপনার গ্রুপের রক্ত দরকার নেই")
                if (!BloodRules.isEligibleDonor(me, now)) {
                    throw ctx.err(400, "You can't donate right now (age, weight or last donation)", "এখন আপনি রক্ত দিতে পারবেন না (বয়স, ওজন বা শেষ রক্তদানের সময়)")
                }
                if (x.closed || x.coming >= x.need) {
                    throw ctx.err(409, "Enough donors are already coming for your group — thank you!", "আপনার গ্রুপের যথেষ্ট ডোনার আসছেন — ধন্যবাদ!")
                }
                x.coming += 1
                // closes itself when enough (opens again when someone cancels)
                if (x.coming >= x.need) x.closed = true
                if (d == null) {
                    jdbc.update(
                        "INSERT INTO emergency_donors (emergency_id, uid, blood_group, status, coming_at, notified_at) VALUES (?, ?, ?, 'coming', ?, NULL)",
                        id, uid, g, Sql.ts(now),
                    )
                } else {
                    jdbc.update(
                        "UPDATE emergency_donors SET blood_group = ?, status = 'coming', coming_at = ? WHERE emergency_id = ? AND uid = ?",
                        g, Sql.ts(now), id, uid,
                    )
                }
            } else {
                if (cur != "coming") return@inTx
                val x = (d["blood_group"] as String?)?.let { e.groups[it] }
                if (x != null) {
                    x.coming = maxOf(0, x.coming - 1)
                    if (x.adminClosed != true && x.coming < x.need) x.closed = false
                }
                jdbc.update(
                    "UPDATE emergency_donors SET status = 'cancelled', cancelled_at = ? WHERE emergency_id = ? AND uid = ?",
                    Sql.ts(now), id, uid,
                )
            }
            saveGroups(e)
        }
        return linkedMapOf("ok" to true)
    }

    // ---------------------------------------------------------------- admin

    fun adminCreate(ctx: ActionContext): Any {
        requireAdmin(ctx)
        val b = ctx.body
        fun err(en: String, bn: String) = ctx.err(400, en, bn)
        if (!b.isTrue("confirmed")) throw err("Confirm that you have spoken to the hospital blood bank", "হাসপাতালের ব্লাড ব্যাংকের সাথে কথা বলে নিশ্চিত হয়েছেন — টিক দিন")
        val dist = Places.district(b.string("district")) ?: throw err("Choose a district", "জেলা বেছে নিন")
        var hospitalName = Rules.cleanText(b.string("hospitalName"), 120)
        var hospitalId: String? = null
        val hid = b.string("hospitalId")
        if (!hid.isNullOrEmpty()) {
            val h = hospitals.findHospital(hid)
            if (h != null) {
                hospitalId = h.id
                hospitalName = h.nameBn.ifEmpty { h.nameEn }
            }
        }
        if (hospitalName.isNullOrEmpty()) throw err("Choose or write the hospital", "হাসপাতাল বেছে নিন বা লিখুন")
        val groups = EmergencyRules.parseGroups(b["groups"])
        if (groups.isEmpty()) throw err("Enter bags needed for at least one group", "অন্তত একটা গ্রুপে কত ব্যাগ লাগবে দিন")
        val radiusKm = b.int("radiusKm")?.toInt()?.takeIf { it in EmergencyRules.RADII && b.int("radiusKm") == it.toLong() } ?: 25
        val hours = b.int("hours")?.toInt()?.takeIf { it in EmergencyRules.HOURS && b.int("hours") == it.toLong() } ?: 12
        val phone = Phones.normalizeBd(Js.toStr(b["contactPhone"])) ?: throw err("Enter the hospital contact mobile number", "হাসপাতালের যোগাযোগের মোবাইল নম্বর দিন")
        // distances from the hospital when its location is given, else from the district centre
        val bLat = b.number("lat")
        val bLng = b.number("lng")
        val hasLoc = bLat != null && bLng != null && bLat > 20.3 && bLat < 26.8 && bLng > 87.9 && bLng < 92.8
        val lat = if (hasLoc) bLat else dist.lat
        val lng = if (hasLoc) bLng else dist.lng
        val districts = (listOf(dist.bn) + Places.DISTRICTS.filter { BloodRules.distanceKm(lat, lng, it.lat, it.lng) <= radiusKm + 25 }.map { it.bn })
            .distinct().take(30)
        val now = System.currentTimeMillis()
        val id = com.lifolo.common.Ids.newId()
        store.inTx {
            jdbc.update(
                """
                INSERT INTO emergencies (id, district, districts, hospital_id, hospital_name, lat, lng, exact_location, radius_km, contact_phone,
                  note, status, waves, created_at, created_by, ends_at, is_demo)
                VALUES (?, ?, ?::text[], ?, ?, ?, ?, ?, ?, ?, ?, 'active', 0, ?, ?, ?, FALSE)
                """.trimIndent(),
                id, dist.bn, pgTextArray(districts), hospitalId, hospitalName, lat, lng, hasLoc, radiusKm, Phones.localBd(phone),
                Rules.cleanText(b.string("note"), 300) ?: "", Sql.ts(now), ctx.user.email.lowercase(), Sql.ts(now + hours * HOUR),
            )
            for ((g, x) in groups) {
                jdbc.update(
                    "INSERT INTO emergency_groups (emergency_id, blood_group, need, coming, notified, closed) VALUES (?, ?, ?, 0, 0, FALSE)",
                    id, g, x.need,
                )
            }
        }
        val r = sendWave(id)
        return linkedMapOf<String, Any?>("ok" to true, "id" to id).apply { putAll(r) }
    }

    /** tell more donors (the next wave) */
    fun adminMore(ctx: ActionContext): Any {
        requireAdmin(ctx)
        val id = ctx.body.string("id")
        if (!Rules.isValidId(id)) throw ctx.err(400, "Not found", "পাওয়া যায়নি")
        return linkedMapOf<String, Any?>("ok" to true).apply { putAll(sendWave(id!!)) }
    }

    /** close / open one group */
    fun adminGroup(ctx: ActionContext): Any {
        requireAdmin(ctx)
        val id = ctx.body.string("id")
        val group = Js.string(ctx.body["group"])
        if (!Rules.isValidId(id) || group !in Rules.BLOOD_GROUPS) throw ctx.err(400, "Not found", "পাওয়া যায়নি")
        val close = ctx.body.isTrue("closed")
        store.inTx {
            val e = one(id!!, forUpdate = true) ?: throw ctx.notFound()
            val x = e.groups[group!!] ?: throw ctx.err(400, "Not found", "পাওয়া যায়নি")
            x.closed = close || x.coming >= x.need
            x.adminClosed = close
            saveGroups(e)
        }
        return linkedMapOf("ok" to true)
    }

    /** end the whole appeal and tell the "coming" donors */
    fun adminClose(ctx: ActionContext): Any {
        requireAdmin(ctx)
        val id = ctx.body.string("id")
        if (!Rules.isValidId(id)) throw ctx.err(400, "Not found", "পাওয়া যায়নি")
        val e = one(id!!) ?: throw ctx.notFound()
        if (e.status == "closed") return linkedMapOf("ok" to true, "unchanged" to true)
        jdbc.update("UPDATE emergencies SET status = 'closed', closed_at = ? WHERE id = ?", Sql.ts(System.currentTimeMillis()), id)
        notifyEnded(e)
        return linkedMapOf("ok" to true)
    }

    /** the panel list: newest 30 */
    fun adminList(ctx: ActionContext): Any {
        requireAdmin(ctx)
        val now = System.currentTimeMillis()
        return linkedMapOf(
            "items" to load("TRUE ORDER BY created_at DESC LIMIT 30").map { e ->
                linkedMapOf(
                    "id" to e.id,
                    "hospitalName" to e.hospitalName,
                    "district" to e.district,
                    "radiusKm" to e.radiusKm,
                    "groups" to LinkedHashMap(e.groups.mapValues { it.value.toMap() }),
                    "contactPhone" to Phones.fixPhone(e.contactPhone),
                    "note" to e.note,
                    "live" to e.isLive(now),
                    "status" to e.status,
                    "createdAt" to e.createdAt,
                    "endsAt" to e.endsAt,
                    "createdBy" to e.createdBy,
                    "waves" to e.waves,
                    "exactLocation" to e.exactLocation,
                )
            },
        )
    }

    companion object {
        const val HOUR = 3600L * 1000
        const val ASK_DAYS = 7

        /** a PostgreSQL text[] literal */
        fun pgTextArray(list: List<String>): String =
            "{" + list.joinToString(",") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" } + "}"
    }
}
