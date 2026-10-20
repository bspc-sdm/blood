package com.lifolo.blood

import com.lifolo.common.ApiException
import com.lifolo.common.Body
import com.lifolo.common.DhakaTime
import com.lifolo.common.Js
import com.lifolo.common.Lang
import com.lifolo.common.Phones
import com.lifolo.common.Rules
import com.lifolo.common.Sql
import com.lifolo.demo.DemoWorld
import com.lifolo.notify.PushItem
import com.lifolo.notify.PushService
import com.lifolo.notify.PushText
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * The four legacy blood endpoints (api/notify-request.js, api/respond.js, api/close-request.js, api/edit-request.js).
 * Their error messages are Bangla only (the error handler translates them with I18n for `lang: "en"`).
 */
@Service
class RequestFlowService(
    private val store: BloodStore,
    private val geo: GeoQuery,
    private val ends: RequestEndService,
    private val push: PushService,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val jdbc get() = store.jdbc

    private fun requestId(b: Body): String {
        val id = b.string("requestId")
        if (!Rules.isValidId(id)) throw ApiException(400, "requestId ঠিক নেই")
        return id!!
    }

    // ---------------------------------------------------------------- notify-request

    private data class Notify(val r: BloodRequest, val rounds: Int?)

    /** push the nearby matching eligible donors (after a new request; `again` for more donors later) */
    fun notify(uid: String, b: Body): Any {
        val requestId = requestId(b)
        val now = System.currentTimeMillis()
        val again = b.isTrue("again")
        val n = store.inTx<Notify?> {
            val r = store.request(requestId, forUpdate = true) ?: throw ApiException(404, "রিকোয়েস্ট পাওয়া যায়নি")
            if (r.requesterUid != uid) throw ApiException(403, "এটা আপনার রিকোয়েস্ট না")
            if (!r.isActive(now)) throw ApiException(400, "রিকোয়েস্টটা বন্ধ বা মেয়াদ শেষ")
            if (r.notifiedAt != null && !again) return@inTx null
            if ((r.notifyingUntil ?: 0L) > now) throw ApiException(409, "নোটিফিকেশন পাঠানো চলছে, একটু পরে দেখুন")
            BloodRules.validateRequest(r, now)?.let { throw ApiException(400, it) }
            if (again) {
                if (r.notifiedAt == null) throw ApiException(400, "আগে একবার ডোনারদের জানান")
                val rounds = r.notifyRounds?.takeIf { it != 0 } ?: 1
                if (rounds >= MAX_ROUNDS) throw ApiException(429, "একটা রিকোয়েস্টে সর্বোচ্চ ${MAX_ROUNDS} বার জানানো যায়। পরিচিতদের সরাসরি জানান।")
                val last: Long = r.lastNotifiedAt?.takeIf { it != 0L } ?: r.notifiedAt
                if (now - last < AGAIN_GAP_MS) {
                    val h = Math.ceil((AGAIN_GAP_MS - (now - last)) / 3600000.0).toLong()
                    throw ApiException(429, "$h ঘণ্টা পর আবার জানাতে পারবেন")
                }
                if (r.bagsLeft() <= 0) throw ApiException(400, "সব ব্যাগের ডোনার পাওয়া গেছে")
                jdbc.update("UPDATE blood_requests SET notifying_until = ? WHERE id = ?", Sql.ts(now + LOCK_MS), requestId)
                return@inTx Notify(r, rounds)
            }
            // up to 3 requests' notifications a day per person
            val used = store.counterUsed(uid, "request", now)
            if (used >= BloodRules.MAX_REQUESTS_PER_DAY) {
                throw ApiException(429, "দিনে সর্বোচ্চ ${BloodRules.MAX_REQUESTS_PER_DAY}টা রিকোয়েস্ট দেওয়া যায়। জরুরি হলে পরিচিতদের সরাসরি জানান।")
            }
            jdbc.update("UPDATE blood_requests SET notifying_until = ? WHERE id = ?", Sql.ts(now + LOCK_MS), requestId)
            store.counterSet(uid, "request", now, used + 1)
            Notify(r, null)
        }
        if (n == null) {
            return linkedMapOf("ok" to true, "already" to true, "sent" to (store.request(requestId)?.notifiedCount ?: 0))
        }
        val request = n.r
        try {
            // open-request limit (the app blocks it too)
            if (store.activeOpenCount(uid, now) > BloodRules.MAX_OPEN_REQUESTS) {
                throw ApiException(429, "একসাথে সর্বোচ্চ ${BloodRules.MAX_OPEN_REQUESTS}টা খোলা রিকোয়েস্ট রাখা যায়")
            }
            // donors told before are skipped (so new, farther donors come in when notifying again)
            val notifiedBefore = jdbc.queryForList("SELECT uid FROM blood_request_notified WHERE request_id = ? ORDER BY at, uid", String::class.java, requestId)
            val metaExists = notifiedBefore.isNotEmpty()
            val already = LinkedHashSet(notifiedBefore)
            if (again) {
                // whoever responded already (accepted, confirmed, removed) is not told again
                already.addAll(jdbc.queryForList("SELECT donor_uid FROM blood_responses WHERE request_id = ?", String::class.java, requestId))
            }
            // nearby donors step by step (5 → 10 → 25 → 50 km) until enough; only matching groups are read
            val groups = BloodRules.DONORS_FOR[request.patientGroup] ?: emptyList()
            var donors: List<BloodRules.Picked> = emptyList()
            for (radius in BloodRules.RADII_KM) {
                val found = geo.nearDonors(groups, request.lat, request.lng, radius.toDouble())
                // demo requests reach only demo donors and real ones only real donors
                val allUsers = found.map { it.item }.filter { DemoWorld.sameWorld(uid, it.uid) }
                if (again && !metaExists) BloodRules.pickDonors(request, allUsers, now).forEach { already.add(it.donor.uid) }
                donors = BloodRules.pickDonors(request, allUsers.filter { it.uid !in already }, now)
                if (donors.size >= BloodRules.ENOUGH_DONORS) break
            }
            // every donor's own daily push limit (settings, 3 by default)
            val day = DhakaTime.day(now)
            val counts = HashMap<String, Int>()
            for (d in donors) {
                counts[d.donor.uid] = jdbc.query(
                    "SELECT push_day, push_count FROM user_state WHERE uid = ?",
                    { rs, _ -> if (rs.getObject("push_day", java.time.LocalDate::class.java) == day) rs.getInt("push_count") else 0 },
                    d.donor.uid,
                ).firstOrNull() ?: 0
            }
            donors = donors.filter { (counts[it.donor.uid] ?: 0) < BloodRules.pushLimit(it.donor.maxPushPerDay) }

            val sent = push.send(
                donors.map { p ->
                    val lang = p.donor.lang
                    PushItem(
                        p.donor.uid,
                        Lang.pick(
                            lang,
                            "${request.patientGroup} blood needed${if (request.urgent) " (urgent)" else ""}",
                            "${request.patientGroup} রক্ত দরকার${if (request.urgent) " (জরুরি)" else ""}",
                        ),
                        "${Lang.pick(lang, "${request.bags} bag(s)", "${request.bags} ব্যাগ")} · ${request.hospital}, " +
                            "${BloodRules.districtLabel(request.placeName, lang)} · ${BloodRules.formatKm(p.km, lang)}",
                        mapOf("requestId" to requestId, "type" to "request"),
                    )
                },
            )
            store.inTx {
                for (d in donors) {
                    jdbc.update(
                        """
                        INSERT INTO user_state (uid, push_day, push_count) VALUES (?, ?, 1)
                        ON CONFLICT (uid) DO UPDATE SET
                          push_count = CASE WHEN user_state.push_day = EXCLUDED.push_day THEN user_state.push_count + 1 ELSE 1 END,
                          push_day = EXCLUDED.push_day
                        """.trimIndent(),
                        d.donor.uid, Sql.date(day),
                    )
                }
                if (again) {
                    jdbc.update(
                        "UPDATE blood_requests SET notified_count = notified_count + ?, notify_rounds = ?, last_notified_at = ?, notifying_until = NULL WHERE id = ?",
                        sent, (n.rounds ?: 1) + 1, Sql.ts(now), requestId,
                    )
                } else {
                    jdbc.update(
                        "UPDATE blood_requests SET notified_at = now(), notified_count = ?, notify_rounds = 1, last_notified_at = ?, notifying_until = NULL WHERE id = ?",
                        sent, Sql.ts(now), requestId,
                    )
                }
                if (donors.isNotEmpty()) {
                    for (u in already + donors.map { it.donor.uid }) {
                        jdbc.update("INSERT INTO blood_request_notified (request_id, uid, at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING", requestId, u, Sql.ts(now))
                    }
                }
            }
            return linkedMapOf("ok" to true, "sent" to sent)
        } catch (e: Exception) {
            // something failed: open the lock so the app can try again
            runCatching { jdbc.update("UPDATE blood_requests SET notifying_until = NULL WHERE id = ?", requestId) }
            throw e
        }
    }

    // ---------------------------------------------------------------- respond

    private data class Responded(val r: BloodRequest, val donorName: String, val donorGroup: String)

    /** the donor's "I'll donate": checks, creates the response, tells the requester */
    fun respond(uid: String, b: Body): Any {
        val requestId = requestId(b)
        val request = store.inTx<Responded?> {
            val r = store.request(requestId, forUpdate = true) ?: throw ApiException(404, "রিকোয়েস্ট পাওয়া যায়নি")
            val u = GeoQuery.loadDonor(jdbc, uid, forUpdate = true)
            val resp = store.response(requestId, uid, forUpdate = true)
            val now = System.currentTimeMillis()
            if (!r.isActive(now)) throw ApiException(400, "রিকোয়েস্টটা বন্ধ হয়ে গেছে")
            if (r.requesterUid == uid) throw ApiException(400, "নিজের রিকোয়েস্টে সাড়া দেওয়া যায় না")
            // demo and real accounts never answer each other's requests
            if (!DemoWorld.sameWorld(uid, r.requesterUid)) {
                throw ApiException(403, "ডেমো অ্যাকাউন্ট আর আসল অ্যাকাউন্ট একে অপরের রিকোয়েস্টে সাড়া দিতে পারে না")
            }
            val group = u?.bloodGroup
            if (u == null || u.phone.isNullOrEmpty() || group.isNullOrEmpty()) throw ApiException(400, "আগে প্রোফাইল পূরণ করুন")
            if (group == "unknown") throw ApiException(400, "রক্ত দিতে আগে প্রোফাইলে আপনার রক্তের গ্রুপ দিন")
            if (!BloodRules.canDonate(group, r.patientGroup)) throw ApiException(400, "আপনার গ্রুপ ($group) এই রোগীকে দেওয়া যায় না")
            if (!BloodRules.isEligibleDonor(u, now)) {
                throw ApiException(400, "আপনি এখন রক্ত দেওয়ার যোগ্য নন (বয়স ১৮–৬০, ওজন কমপক্ষে ৫০ কেজি, আর শেষ রক্তদানের পর ১২০ দিন লাগে)")
            }
            val existing = resp?.status
            if (existing == "declined") throw ApiException(403, "রক্তপ্রার্থী অন্য ডোনার বেছে নিয়েছেন")
            if (existing == "accepted" || existing == "donated") return@inTx null
            val used = store.counterUsed(uid, "respond", now)
            if (used >= BloodRules.MAX_RESPONSES_PER_DAY) {
                throw ApiException(429, "দিনে সর্বোচ্চ ${BloodRules.MAX_RESPONSES_PER_DAY}টা রিকোয়েস্টে সাড়া দেওয়া যায়। কাল আবার চেষ্টা করুন।")
            }
            jdbc.update("UPDATE blood_requests SET accepted_count = accepted_count + 1 WHERE id = ?", requestId)
            val distance = if (u.lat != null && u.lng != null) BloodRules.jsRound(BloodRules.distanceKm(r.lat, r.lng, u.lat, u.lng)).toInt() else null
            // a new response replaces an old (cancelled) one completely
            jdbc.update(
                """
                INSERT INTO blood_responses (request_id, donor_uid, donor_name, donor_phone, donor_group, status, confirmed, distance_km, fare_after_km, created_at)
                VALUES (?, ?, ?, ?, ?, 'accepted', FALSE, ?, ?, ?)
                ON CONFLICT (request_id, donor_uid) DO UPDATE SET
                  donor_name = EXCLUDED.donor_name, donor_phone = EXCLUDED.donor_phone, donor_group = EXCLUDED.donor_group,
                  status = 'accepted', confirmed = FALSE, confirmed_at = NULL, distance_km = EXCLUDED.distance_km,
                  fare_after_km = EXCLUDED.fare_after_km, cancelled_reason = NULL, removed_by = NULL, removed_at = NULL,
                  created_at = EXCLUDED.created_at, updated_at = NULL
                """.trimIndent(),
                requestId, uid, u.name ?: "", u.phone, group, distance, u.fareAfterKm, Sql.ts(now),
            )
            store.counterSet(uid, "respond", now, used + 1)
            // the donor's "processing" list (the app reads it from the profile)
            store.addCommitment(uid, requestId, now)
            Responded(r, u.name ?: "", group)
        } ?: return linkedMapOf("ok" to true, "already" to true)

        // tell the requester (the response is saved even if this fails)
        try {
            push.pushUsers(listOf(request.r.requesterUid)) { _, lang ->
                PushText(
                    Lang.pick(lang, "A donor is ready to give blood", "একজন ডোনার রাজি হয়েছেন"),
                    Lang.pick(
                        lang,
                        "${request.donorName} (${request.donorGroup}) agreed to donate. Open the app for their number.",
                        "${request.donorName} (${request.donorGroup}) রক্ত দিতে রাজি। অ্যাপ খুলে নম্বর দেখুন।",
                    ),
                    mapOf("requestId" to requestId, "type" to "response"),
                )
            }
        } catch (e: Exception) {
            log.error("requester push failed", e)
        }
        return linkedMapOf("ok" to true)
    }

    // ---------------------------------------------------------------- close-request

    private data class Closed(
        val already: Boolean = false,
        val marked: List<String> = emptyList(),
        val closed: Boolean = false,
        val notNeeded: Boolean = false,
        val r: BloodRequest? = null,
    )

    /** "got the blood": closes (or records a partial), marks the donors who gave, sets their last donation date */
    fun close(uid: String, b: Body): Any {
        val requestId = requestId(b)
        val raw = b["donatedUids"]?.takeIf { it.isArray }?.toList() ?: emptyList()
        val donated = raw.mapNotNull { Js.string(it) }.filter { Rules.isValidId(it) }.distinct().take(20)
        val today = BloodRules.todayDhakaUtcMidnight()
        val bagsReceivedRaw = b.int("bagsReceived")

        val result = store.inTx {
            val r = store.request(requestId, forUpdate = true) ?: throw ApiException(404, "রিকোয়েস্ট পাওয়া যায়নি")
            if (r.requesterUid != uid) throw ApiException(403, "এটা আপনার রিকোয়েস্ট না")
            if (r.status == "closed") return@inTx Closed(already = true)
            val now = System.currentTimeMillis()
            val all = store.activeResponses(requestId, forUpdate = true)
            val donatedBefore = all.count { it.status == "donated" }
            // bags received; fewer than all keeps the request open (only the count and donors are written)
            val bags = if (r.bags == 0) 1 else r.bags
            val got = bagsReceivedRaw?.let { maxOf(0L, minOf(bags.toLong(), it)).toInt() }
            val partial = got != null && got > 0 && got < bags
            val allowed = maxOf((got ?: bags) - donatedBefore, 0)
            val prevReceived = r.bagsReceived
            val markedIds = ArrayList<String>()
            val closeMarks = ArrayList<Triple<String, Long?, Boolean>>()
            for (d in donated) {
                val resp = store.response(requestId, d, forUpdate = true)
                if (resp == null || resp.status != "accepted") continue
                if (markedIds.size >= allowed) continue
                jdbc.update("UPDATE blood_responses SET status = 'donated' WHERE request_id = ? AND donor_uid = ?", requestId, d)
                markedIds.add(d)
                val (exists, last) = store.lastDonation(d, forUpdate = true)
                val setDate = exists && (last == null || last < today)
                if (setDate) store.setLastDonation(d, today)
                closeMarks.add(Triple(d, if (setDate) last else null, !setDate))
            }
            val marked = markedIds.toSet()
            val confirmedCount = all.count { it.status == "donated" || it.confirmed || it.donorUid in marked }
            // undo information for request_reopen (not on the request row everyone reads)
            if (!partial) {
                jdbc.update("DELETE FROM blood_request_close_marks WHERE request_id = ?", requestId)
                jdbc.update(
                    "INSERT INTO blood_request_close_marks (request_id, day, confirmed_before, fulfilled_before, at) VALUES (?, ?, ?, ?, ?)",
                    requestId, Sql.date(DhakaTime.fromUtcMidnightMs(today)), r.confirmedCount, r.fulfilled, Sql.ts(now),
                )
                for ((d, prev, keep) in closeMarks) {
                    jdbc.update(
                        "INSERT INTO blood_request_close_mark_donors (request_id, donor_uid, prev_last_ms, keep_date) VALUES (?, ?, ?, ?)",
                        requestId, d, prev, keep,
                    )
                }
            }
            if (partial) {
                jdbc.update(
                    "UPDATE blood_requests SET confirmed_count = ?, fulfilled = ?, bags_received = ?, updated_at = ? WHERE id = ?",
                    confirmedCount, confirmedCount >= bags, maxOf(got, prevReceived), Sql.ts(now), requestId,
                )
            } else {
                jdbc.update(
                    """
                    UPDATE blood_requests SET confirmed_count = ?, fulfilled = ?, status = 'closed', closed_at = ?, closed_by = ?,
                      bags_received_before = ?, bags_received = ? WHERE id = ?
                    """.trimIndent(),
                    confirmedCount, confirmedCount >= bags, Sql.ts(now), if (got == 0) "not_needed" else "requester",
                    prevReceived, if (got != null && got > 0) got else r.bagsReceived, requestId,
                )
            }
            Closed(marked = markedIds, closed = !partial, notNeeded = got == 0, r = r)
        }
        if (result.already) return linkedMapOf("ok" to true, "already" to true)

        // closed: donors still "accepted" are told not to come; the marked donors are thanked
        if (result.closed && result.r != null) {
            ends.notifyEnded(result.r, if (result.notNeeded) "notNeeded" else "closed")
            push.pushUsers(result.marked) { _, lang ->
                PushText(
                    Lang.pick(lang, "Thank you for donating!", "রক্ত দেওয়ার জন্য ধন্যবাদ!"),
                    Lang.pick(
                        lang,
                        "The requester marked your donation. You can donate again after 120 days.",
                        "রক্তপ্রার্থী আপনার রক্তদান লিখে রেখেছেন। ১২০ দিন পর আবার রক্ত দিতে পারবেন।",
                    ),
                    mapOf("requestId" to requestId, "type" to "request"),
                )
            }
        }
        // the donors who gave: their "accepted" elsewhere is released
        for (d in result.marked) ends.releaseOthers(d, requestId)
        return linkedMapOf("ok" to true, "marked" to result.marked.size, "closed" to result.closed, "notNeeded" to result.notNeeded)
    }

    // ---------------------------------------------------------------- edit-request

    private class Changes {
        var bags: Int? = null
        var hospital: String? = null
        var neededByMillis: Long? = null
        var neededAtMillis: Long? = null
        var travelFare: Boolean? = null
        var urgent: Boolean? = null
        var contactPhone: String? = null
        var note: String? = null
        val isEmpty get() = listOf(bags, hospital, neededByMillis, neededAtMillis, travelFare, urgent, contactPhone, note).all { it == null }
    }

    private fun readChanges(b: Body): Changes {
        val c = Changes()
        if (b.has("bags")) {
            val v = b.int("bags")
            if (v == null || v < 1 || v > 10) throw ApiException(400, "ব্যাগের সংখ্যা ঠিক নেই")
            c.bags = v.toInt()
        }
        if (b.has("hospital")) {
            val h = b.string("hospital")?.let { com.lifolo.admin.kernel.ListingRules.jsTrim(it) } ?: ""
            if (h.isEmpty() || h.length > 120) throw ApiException(400, "হাসপাতালের নাম ঠিক নেই")
            c.hospital = h
        }
        if (b.has("neededByMillis")) {
            val v = b.int("neededByMillis") ?: throw ApiException(400, "তারিখ ঠিক নেই")
            if (v < BloodRules.todayDhakaUtcMidnight()) throw ApiException(400, "আজ বা পরের কোনো তারিখ দিন")
            c.neededByMillis = v
        }
        if (b.has("neededAtMillis")) {
            c.neededAtMillis = b.int("neededAtMillis") ?: throw ApiException(400, "সময় ঠিক নেই")
        }
        if (b.has("travelFare")) {
            c.travelFare = b.bool("travelFare") ?: throw ApiException(400, "তথ্য ঠিক নেই")
        }
        if (b.has("urgent")) {
            c.urgent = b.bool("urgent") ?: throw ApiException(400, "জরুরি ঠিক নেই")
        }
        // the contact number (may be a relative's) — accepted donors see it
        if (b.has("contactPhone")) {
            val p = Phones.normalizeBd(Js.toStr(b["contactPhone"])) ?: throw ApiException(400, "সঠিক মোবাইল নম্বর দিন")
            c.contactPhone = Phones.localBd(p)
        }
        if (b.has("note")) {
            val note = b.string("note")
            if (note == null || note.length > 300) throw ApiException(400, "অতিরিক্ত তথ্য ৩০০ অক্ষরের মধ্যে লিখুন")
            c.note = com.lifolo.admin.kernel.ListingRules.jsTrim(note)
        }
        return c
    }

    /** a short "what changed" for the donors */
    private fun describe(before: BloodRequest, contactPhone: String, c: Changes, lang: String?): List<String> {
        fun L(en: String, bn: String) = Lang.pick(lang, en, bn)
        val parts = ArrayList<String>()
        if (c.hospital != null && c.hospital != before.hospital) parts.add("${L("Hospital", "হাসপাতাল")}: ${c.hospital}")
        if (c.neededByMillis != null && c.neededByMillis != before.neededByMillis) parts.add("${L("Date", "তারিখ")}: ${BloodRules.utcDmy(c.neededByMillis!!)}")
        if (c.neededAtMillis != null && c.neededAtMillis != before.neededAtMillis) {
            val t = c.neededAtMillis!!
            parts.add("${L("Time", "সময়")}: ${if (lang == "en") BloodRules.formatTimeEn(t) else BloodRules.formatTimeBn(t)}")
        }
        if (c.bags != null && c.bags != before.bags) parts.add(L("${c.bags} bag(s)", "${c.bags} ব্যাগ"))
        if (c.urgent == true && !before.urgent) parts.add(L("Now urgent", "এখন জরুরি"))
        if (c.travelFare == true && !before.travelFare) parts.add(L("Travel cost will be paid", "যাতায়াত খরচ দেওয়া হবে"))
        if (c.note != null && c.note != before.note) parts.add(L("Note updated", "অতিরিক্ত তথ্য বদলেছে"))
        if (c.contactPhone != null && c.contactPhone != contactPhone) parts.add("${L("New contact number", "নতুন যোগাযোগ নম্বর")}: ${c.contactPhone}")
        return parts
    }

    private data class Edited(val before: BloodRequest, val oldPhone: String, val standby: List<String>)

    /** the requester edits an open request; accepted donors are told what changed */
    fun edit(uid: String, b: Body): Any {
        val requestId = requestId(b)
        val c = readChanges(b)
        if (c.isEmpty) throw ApiException(400, "কিছু বদলানো হয়নি")
        val now = System.currentTimeMillis()
        val e = store.inTx {
            val r = store.request(requestId, forUpdate = true) ?: throw ApiException(404, "রিকোয়েস্ট পাওয়া যায়নি")
            val oldPhone = jdbc.queryForList("SELECT phone FROM blood_request_contacts WHERE request_id = ? FOR UPDATE", String::class.java, requestId)
                .firstOrNull() ?: ""
            if (r.requesterUid != uid) throw ApiException(403, "এটা আপনার রিকোয়েস্ট না")
            if (!r.isActive(now)) throw ApiException(400, "বন্ধ বা মেয়াদ শেষ হওয়া রিকোয়েস্ট এডিট করা যায় না")
            if (r.editCount >= MAX_EDITS) {
                throw ApiException(429, "একটা রিকোয়েস্ট সর্বোচ্চ ${MAX_EDITS} বার এডিট করা যায়। দরকার হলে বন্ধ করে নতুন রিকোয়েস্ট দিন।")
            }
            val merged = r.copy(
                bags = c.bags ?: r.bags,
                hospital = c.hospital ?: r.hospital,
                neededByMillis = c.neededByMillis ?: r.neededByMillis,
                neededAtMillis = c.neededAtMillis ?: r.neededAtMillis,
                travelFare = c.travelFare ?: r.travelFare,
            )
            BloodRules.validateRequest(merged, now)?.let { throw ApiException(400, it) }
            // a new bag count recounts "donors found" (3 bags is no longer "found")
            val set = com.lifolo.common.SqlSet()
            var standby: List<String> = emptyList()
            if (c.bags != null && c.bags != r.bags) {
                val all = store.activeResponses(requestId)
                val count = all.count { it.status == "donated" || it.confirmed }
                val fulfilled = count >= c.bags!!
                set.value("confirmed_count", count).value("fulfilled", fulfilled)
                if (r.fulfilled && !fulfilled) standby = all.filter { it.status == "accepted" && !it.confirmed }.map { it.donorUid }
            }
            c.bags?.let { set.value("bags", it) }
            c.hospital?.let { set.value("hospital", it) }
            c.neededByMillis?.let { set.value("needed_by_ms", it) }
            c.neededAtMillis?.let { set.value("needed_at_ms", it) }
            c.travelFare?.let { set.value("travel_fare", it) }
            c.urgent?.let { set.value("urgent", it) }
            c.note?.let { set.value("note", it) }
            set.value("edit_count", r.editCount + 1).now("edited_at")
            set.update(jdbc, "blood_requests", "id = ?", requestId)
            if (c.contactPhone != null && c.contactPhone != oldPhone) {
                jdbc.update(
                    "INSERT INTO blood_request_contacts (request_id, phone) VALUES (?, ?) ON CONFLICT (request_id) DO UPDATE SET phone = EXCLUDED.phone",
                    requestId, c.contactPhone,
                )
            }
            Edited(r, oldPhone, standby)
        }

        // donors who heard "enough for now" may be needed again
        if (e.standby.isNotEmpty()) {
            push.pushUsers(e.standby) { _, lang ->
                PushText(
                    Lang.pick(lang, "A donor may be needed again", "আবার ডোনার লাগতে পারে"),
                    Lang.pick(lang, "The requester needs more bags now. They will confirm soon.", "রক্তপ্রার্থীর এখন আরও ব্যাগ লাগবে। শিগগিরই নিশ্চিত করবেন।"),
                    mapOf("requestId" to requestId, "type" to "request"),
                )
            }
        }
        // the accepted donors are told (the edit is saved either way)
        var notified = 0
        if (describe(e.before, e.oldPhone, c, "bn").isNotEmpty()) {
            try {
                val accepted = jdbc.queryForList(
                    "SELECT donor_uid FROM blood_responses WHERE request_id = ? AND status = 'accepted' ORDER BY created_at", String::class.java, requestId,
                )
                notified = push.pushUsers(accepted) { _, lang ->
                    PushText(
                        Lang.pick(lang, "${e.before.patientGroup} blood request updated", "${e.before.patientGroup} রক্তের রিকোয়েস্টে পরিবর্তন"),
                        describe(e.before, e.oldPhone, c, lang).joinToString(" · "),
                        mapOf("requestId" to requestId, "type" to "request"),
                    )
                }
            } catch (ex: Exception) {
                log.error("edit push failed", ex)
            }
        }
        return linkedMapOf("ok" to true, "notified" to notified)
    }

    companion object {
        const val LOCK_MS = 60L * 1000
        const val MAX_ROUNDS = 3
        const val AGAIN_GAP_MS = 6L * 60 * 60 * 1000
        const val MAX_EDITS = 10
    }
}
