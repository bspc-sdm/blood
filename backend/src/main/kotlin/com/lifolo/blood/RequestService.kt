package com.lifolo.blood

import com.lifolo.common.ApiException
import com.lifolo.common.DhakaTime
import com.lifolo.common.Geo
import com.lifolo.common.I18n
import com.lifolo.common.Ids
import com.lifolo.common.Js
import com.lifolo.common.Phones
import com.lifolo.common.Rules
import com.lifolo.common.Sql
import com.lifolo.common.action.ActionContext
import com.lifolo.demo.DemoWorld
import org.springframework.stereotype.Service

/**
 * Blood requests: create / reopen / delete (requestcreate.js, myrequests.js) and the reads that used to go straight
 * to Firestore (clientdata.js: blood_request_get, blood_requests_mine, blood_requests_near, blood_response_get,
 * blood_responses_list, blood_contact_get, report_request), plus the guest list (guest.js guest_requests).
 */
@Service
class RequestService(
    private val store: BloodStore,
    private val geo: GeoQuery,
    private val ends: RequestEndService,
) {
    private val jdbc get() = store.jdbc

    private fun requestId(ctx: ActionContext): String {
        val id = ctx.body.string("requestId")
        if (!Rules.isValidId(id)) throw ctx.notFound()
        return id!!
    }

    // ---------------------------------------------------------------- create / reopen / delete

    fun create(ctx: ActionContext): Any {
        val b = ctx.body
        val uid = ctx.user.id
        fun err(en: String, bn: String) = ApiException(400, ctx.tr(en, bn))
        val now = System.currentTimeMillis()
        val lat = b.number("lat")?.let { Js.round(it * 100) / 100 }
        val lng = b.number("lng")?.let { Js.round(it * 100) / 100 }
        val hospital = b.string("hospital")?.let { com.lifolo.admin.kernel.ListingRules.jsTrim(it).take(120) } ?: ""
        val patientGroup = jsValue(b["patientGroup"])
        val bags = jsValue(b["bags"])
        val placeName = jsValue(b["placeName"])
        val neededBy = b.int("neededByMillis")
        val neededAt = b.int("neededAtMillis")
        val invalid = BloodRules.validateRequest(patientGroup, bags, hospital, placeName, lat, lng, neededBy, neededAt, now)
        if (invalid != null) throw ApiException(400, ctx.tr(I18n.toEnglish(invalid), invalid))
        // not for a date already past (today or later)
        if (neededBy!! + BloodRules.DAY_MS <= now) throw err("The date has already passed", "তারিখটা পার হয়ে গেছে")
        val phone = Phones.normalizeBd(Js.toStr(b["requesterPhone"])) ?: throw err("Enter a valid mobile number", "সঠিক মোবাইল নম্বর দিন")
        val name = Rules.cleanText(b.string("requesterName"), 80) ?: throw err("Enter your name", "নাম লিখুন")
        val note = b.string("note")?.let { com.lifolo.admin.kernel.ListingRules.jsTrim(it).take(300) } ?: ""
        val sentHash = b.string("geohash")?.takeIf { GEOHASH_RE.matches(it) }
        val geohash = Geo.hash(lat, lng) ?: sentHash!!

        val id = Ids.newId()
        store.inTx {
            val open = store.activeOpenCount(uid, now)
            if (open >= BloodRules.MAX_OPEN_REQUESTS) {
                throw ApiException(
                    429,
                    ctx.tr(
                        "You can keep up to ${BloodRules.MAX_OPEN_REQUESTS} open requests at a time",
                        "একসাথে সর্বোচ্চ ${BloodRules.MAX_OPEN_REQUESTS}টা খোলা রিকোয়েস্ট রাখা যায়",
                    ),
                )
            }
            val used = store.counterUsed(uid, "create", now)
            if (used >= BloodRules.MAX_REQUESTS_PER_DAY) {
                throw ApiException(
                    429,
                    ctx.tr(
                        "Up to ${BloodRules.MAX_REQUESTS_PER_DAY} requests a day. If it's urgent, tell people you know directly.",
                        "দিনে সর্বোচ্চ ${BloodRules.MAX_REQUESTS_PER_DAY}টা রিকোয়েস্ট দেওয়া যায়। জরুরি হলে পরিচিতদের সরাসরি জানান।",
                    ),
                )
            }
            store.counterSet(uid, "create", now, used + 1)
            jdbc.update(
                """
                INSERT INTO blood_requests (id, requester_uid, requester_name, patient_group, bags, hospital, place_name, lat, lng, geohash,
                  needed_by_ms, needed_at_ms, urgent, travel_fare, note, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'open', ?)
                """.trimIndent(),
                id, uid, name, patientGroup, (bags as Number).toInt(), hospital, placeName, lat, lng, geohash,
                neededBy, neededAt, b.isTrue("urgent"), b.isTrue("travelFare"), note, Sql.ts(now),
            )
            jdbc.update("INSERT INTO blood_request_contacts (request_id, name, phone) VALUES (?, ?, ?)", id, name, Phones.localBd(phone))
        }
        return linkedMapOf("ok" to true, "id" to id)
    }

    /** opening again within 1 h of closing by mistake (requester, time not over) */
    fun reopen(ctx: ActionContext): Any {
        val id = requestId(ctx).let { it }
        val uid = ctx.user.id
        val now = System.currentTimeMillis()
        val r = store.inTx<BloodRequest?> {
            val x = store.request(id, forUpdate = true) ?: throw ctx.notFound()
            if (x.requesterUid != uid) throw ctx.err(403, "This is not your request", "এটা আপনার রিকোয়েস্ট না")
            if (x.status == "open") return@inTx null
            // donors marked "donated" at the close become "accepted" again and get their date back
            val cm = jdbc.queryForList(
                "SELECT day, confirmed_before, fulfilled_before FROM blood_request_close_marks WHERE request_id = ?", id,
            ).firstOrNull()
            val marks: List<Triple<String, Long?, Boolean>> = if (cm == null) {
                emptyList()
            } else {
                jdbc.query(
                    "SELECT donor_uid, prev_last_ms, keep_date FROM blood_request_close_mark_donors WHERE request_id = ? ORDER BY donor_uid",
                    { rs, _ -> Triple(rs.getString(1), rs.getLong(2).let { v -> if (rs.wasNull()) null else v }, rs.getBoolean(3)) },
                    id,
                ).filter { Rules.isValidId(it.first) }.take(20)
            }
            val byRequester = x.closedBy == null || x.closedBy == "requester" || x.closedBy == "not_needed"
            val closedAt = x.closedAt ?: 0L
            if (!byRequester || closedAt == 0L || now - closedAt > REOPEN_MS || BloodRules.activeUntil(x.neededByMillis, x.neededAtMillis) <= now) {
                throw ctx.err(400, "This request can no longer be opened again. Create a new one if needed.", "এই রিকোয়েস্ট আর খোলা যায় না। দরকার হলে নতুন রিকোয়েস্ট দিন।")
            }
            if (store.activeOpenCount(uid, now) >= BloodRules.MAX_OPEN_REQUESTS) {
                throw ctx.err(
                    429,
                    "You can keep up to ${BloodRules.MAX_OPEN_REQUESTS} open requests",
                    "একসাথে সর্বোচ্চ ${BloodRules.MAX_OPEN_REQUESTS}টা খোলা রিকোয়েস্ট রাখা যায়",
                )
            }
            val dayMs = (cm?.get("day") as? java.sql.Date)?.toLocalDate()?.let { DhakaTime.toUtcMidnightMs(it) }
            for ((donor, prev, keep) in marks) {
                jdbc.update("UPDATE blood_responses SET status = 'accepted' WHERE request_id = ? AND donor_uid = ? AND status = 'donated'", id, donor)
                // the date set at the close goes back (unless the donor changed it meanwhile)
                if (!keep) {
                    val (exists, last) = store.lastDonation(donor, forUpdate = true)
                    if (exists && last != null && last == dayMs) store.setLastDonation(donor, prev)
                }
            }
            val set = com.lifolo.common.SqlSet()
                .value("status", "open").nul("closed_at").nul("closed_by").ms("reopened_at", now)
                .value("bags_received", x.bagsReceivedBefore ?: 0).nul("bags_received_before")
            if (cm != null) {
                set.value("confirmed_count", (cm["confirmed_before"] as Number).toInt()).value("fulfilled", cm["fulfilled_before"] == true)
            }
            set.update(jdbc, "blood_requests", "id = ?", id)
            if (cm != null) jdbc.update("DELETE FROM blood_request_close_marks WHERE request_id = ?", id)
            x
        }
        if (r != null) ends.notifyEnded(r, "reopened")
        return linkedMapOf("ok" to true)
    }

    /** the requester deletes their request completely (open or closed); accepted donors of an open one are told */
    fun delete(ctx: ActionContext): Any {
        val id = requestId(ctx)
        val r = store.request(id) ?: return linkedMapOf("ok" to true)
        if (r.requesterUid != ctx.user.id) throw ctx.err(403, "This is not your request", "এটা আপনার রিকোয়েস্ট না")
        val willing = if (r.status == "open") store.acceptedDonors(id, 50) else emptyList()
        jdbc.update("DELETE FROM blood_requests WHERE id = ?", id)
        if (willing.isNotEmpty()) ends.notifyEnded(r, "removed", willing)
        return linkedMapOf("ok" to true, "notified" to willing.size)
    }

    // ---------------------------------------------------------------- reads (logged in; demo and real apart)

    fun get(ctx: ActionContext): Any {
        val id = requestId(ctx)
        val r = store.request(id)
        if (r == null || !DemoWorld.sameWorld(ctx.user.id, r.requesterUid)) return linkedMapOf("request" to null)
        return linkedMapOf("request" to r.out())
    }

    fun mine(ctx: ActionContext): Any {
        val items = jdbc.query(
            "SELECT * FROM blood_requests WHERE requester_uid = ? LIMIT 1000", { rs, _ -> BloodRequest.of(rs) }, ctx.user.id,
        ).sortedByDescending { it.createdAt }.map { it.out() }
        return linkedMapOf("items" to items)
    }

    /** open active requests around (not own, view groups, within maxKm ≤ 50); urgent first, then nearest */
    fun near(ctx: ActionContext): Any {
        val b = ctx.body
        val lat = b.number("lat")
        val lng = b.number("lng")
        if (lat == null || lng == null || Math.abs(lat) > 90 || Math.abs(lng) > 180) throw ctx.err(400, "Choose your area", "এলাকা বেছে নিন")
        val maxKm = b.int("maxKm")?.coerceIn(1, 50)?.toInt() ?: Rules.DEFAULT_RADIUS_KM
        val groups = b["groups"]?.takeIf { it.isArray }?.mapNotNull { Js.string(it) }?.filter { it in Rules.BLOOD_GROUPS }
        val uid = ctx.user.id
        val now = System.currentTimeMillis()
        val items = geo.openRequestsNear(lat, lng, maxKm.toDouble(), 500)
            .map { it.item }
            .filter { r -> r.isActive(now) && r.requesterUid != uid && DemoWorld.sameWorld(uid, r.requesterUid) && (groups == null || r.patientGroup in groups) }
            .map { r -> r to Geo.distanceKm(lat, lng, r.lat, r.lng) }
            .filter { it.second <= maxKm }
            .sortedWith(compareByDescending<Pair<BloodRequest, Double>> { if (it.first.urgent) 1 else 0 }.thenBy { it.second })
            .map { (r, km) -> r.out().apply { put("km", km) } }
        return linkedMapOf("items" to items)
    }

    /** the donor's own response only */
    fun responseGet(ctx: ActionContext): Any {
        val id = requestId(ctx)
        return linkedMapOf("response" to store.response(id, ctx.user.id)?.out())
    }

    /** accepted/donated donors with name and phone — the requester only */
    fun responsesList(ctx: ActionContext): Any {
        val id = requestId(ctx)
        val r = store.request(id)
        if (r == null || r.requesterUid != ctx.user.id) throw ctx.err(403, "This is not your request", "এটা আপনার রিকোয়েস্ট না")
        val items = jdbc.query(
            "SELECT * FROM blood_responses WHERE request_id = ? AND status IN ('accepted', 'donated') LIMIT 500",
            { rs, _ -> BloodResponse.of(rs) }, id,
        ).sortedBy { it.createdAt }.map { it.out() }
        return linkedMapOf("items" to items)
    }

    /** the requester's name and phone: the requester, or an accepted/donated donor while the request is open */
    fun contactGet(ctx: ActionContext): Any {
        val id = requestId(ctx)
        val uid = ctx.user.id
        val r = store.request(id)
        fun denied() = ctx.err(403, "You can't see this number", "এই নম্বর দেখার অনুমতি নেই")
        if (r == null) throw denied()
        if (r.requesterUid != uid) {
            val mine = store.response(id, uid)
            if (r.status != "open" || mine == null || mine.status !in setOf("accepted", "donated")) throw denied()
        }
        val c = jdbc.queryForList("SELECT name, phone FROM blood_request_contacts WHERE request_id = ?", id).firstOrNull()
        return linkedMapOf("contact" to c?.let { linkedMapOf("name" to (it["name"] ?: ""), "phone" to (it["phone"] ?: "")) })
    }

    /** one report per user per request */
    fun report(ctx: ActionContext): Any {
        val id = requestId(ctx)
        val n = jdbc.update(
            "INSERT INTO blood_request_reports (request_id, reporter_uid) VALUES (?, ?) ON CONFLICT DO NOTHING", id, ctx.user.id,
        )
        if (n == 0) throw ctx.err(409, "Already reported", "আগেই রিপোর্ট করা হয়েছে")
        return linkedMapOf("ok" to true)
    }

    /**
     * guest.js guest_requests: open requests near lat,lng (inside Bangladesh), maxKm 5–25 (default 10), without name,
     * phone, note or exact place. The search runs from the ~5 km cell centre (as Node, so moving a little does not
     * give new reads); distances are measured from the real point.
     */
    fun guestRequests(ctx: ActionContext): Any {
        val b = ctx.body
        val lat = b.number("lat")
        val lng = b.number("lng")
        if (lat == null || lng == null || lat < 20 || lat > 27 || lng < 88 || lng > 93) throw ctx.err(400, "Choose your area", "এলাকা বেছে নিন")
        val maxKm = b.int("maxKm")?.coerceIn(5, 25)?.toInt() ?: 10
        val cLat = Js.round(lat * 20) / 20
        val cLng = Js.round(lng * 20) / 20
        val now = System.currentTimeMillis()
        val items = geo.openRequestsNear(cLat, cLng, (maxKm + 4).toDouble(), 200)
            .map { it.item }
            .filter { it.isActive(now) }
            .map { it to Geo.distanceKm(lat, lng, it.lat, it.lng) }
            .filter { it.second <= maxKm }
            .map { (r, km) -> r.guestView(km) }
            .sortedWith(compareByDescending<Map<String, Any?>> { if (it["urgent"] == true) 1 else 0 }.thenBy { it["km"] as Double })
            .take(100)
        return linkedMapOf("items" to items)
    }

    companion object {
        private val GEOHASH_RE = Regex("^[0-9bcdefghjkmnpqrstuvwxyz]{1,12}$")
        const val REOPEN_MS = 60L * 60 * 1000

        /** a JSON value as the Node code sees it (string, integral/real number, boolean, else the node) */
        fun jsValue(n: com.fasterxml.jackson.databind.JsonNode?): Any? = when {
            n == null || n.isNull -> null
            n.isTextual -> n.textValue()
            n.isIntegralNumber -> n.longValue()
            n.isNumber -> n.doubleValue()
            n.isBoolean -> n.booleanValue()
            else -> n
        }
    }
}
