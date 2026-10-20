package com.lifolo.blood

import com.fasterxml.jackson.databind.ObjectMapper
import com.lifolo.admin.kernel.DemoPoint
import com.lifolo.admin.kernel.DemoSeedContext
import com.lifolo.admin.kernel.DemoSeedPart
import com.lifolo.admin.kernel.ListingRules
import com.lifolo.admin.kernel.Sanctions
import com.lifolo.common.DhakaTime
import com.lifolo.common.Geo
import com.lifolo.common.Places
import com.lifolo.common.Rules
import com.lifolo.common.Sql
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * demo/blood.js — the blood part of the demo world: profiles (donor data, area, notification settings), background
 * donors, blood requests (open/closed, with responses), emergency appeals, blood banks (approved + pending), hospitals
 * (added + requested), a date-correction request and bell notifications. Same field values as the Node seed.
 */
@Component
class BloodDemoSeed(private val jdbc: JdbcTemplate, private val mapper: ObjectMapper) : DemoSeedPart {
    override val step = 1
    override val label = "রক্তদান"

    private data class Person(
        val uid: String,
        val name: String,
        val phone: String,
        val group: String?,
        val fareAfterKm: Int?,
        val lat: Double,
        val lng: Double,
        val geohash: String,
    )

    private data class Spot(val lat: Double, val lng: Double, val geohash: String)

    private class Req(val id: String, val lat: Double, val lng: Double, val status: String, val hospital: String, val neededAtMillis: Long?)

    private fun round2(v: Double) = Math.floor(v * 100 + 0.5) / 100

    /** like the app: coordinates rounded to ≈1 km and their geohash */
    private fun spot(ctx: DemoSeedContext, dx: Double, dy: Double): Spot {
        val p: DemoPoint = ctx.near(dx, dy)
        val lat = round2(p.lat)
        val lng = round2(p.lng)
        return Spot(lat, lng, Geo.hash(lat, lng)!!)
    }

    private fun birth(y: Int, m: Int, d: Int): Long = LocalDate.of(y, m, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** ProfileRepository.saveLocation fields (+ donor_geo) as profile columns */
    private fun locationFields(now: Long, loc: Spot, bloodGroup: String?, source: String = "gps"): Map<String, Any?> = linkedMapOf<String, Any?>(
        "lat" to loc.lat, "lng" to loc.lng, "geohash" to loc.geohash, "place_name" to DISTRICT, "location_source" to source,
        "location_updated_at" to Timestamp(now - 3 * Rules.DAY_MS),
    ).apply { Geo.donorKey(bloodGroup, loc.geohash)?.let { put("donor_geo", it) } }

    /** users/{uid} set with merge → upsert of the given profile columns */
    private fun upsertProfile(uid: String, cols: Map<String, Any?>) {
        val names = cols.keys.toList()
        jdbc.update(
            "INSERT INTO user_profiles (uid, ${names.joinToString(", ")}) VALUES (?, ${names.joinToString(", ") { "?" }}) " +
                "ON CONFLICT (uid) DO UPDATE SET ${names.joinToString(", ") { "$it = EXCLUDED.$it" }}",
            *(listOf<Any?>(uid) + names.map { cols[it] }).toTypedArray(),
        )
    }

    override fun seed(ctx: DemoSeedContext) {
        val now = ctx.nowMs
        val day = Rules.DAY_MS
        val today = DhakaTime.todayUtcMidnight(now)
        fun dayOf(n: Int) = today + n * day
        fun daysAgo(n: Int) = today - n * day
        fun dhakaAt(dateUtcMidnight: Long, h: Int, m: Int = 0) = dateUtcMidnight - 6 * HOUR + (h * 60L + m) * 60 * 1000

        // ======================= 1) the demo login users' profiles =======================
        // everyone in the district centre, source "district" (opening the app does not move them by GPS)
        val home = Places.district(DISTRICT)!!.let { d -> round2(d.lat).let { la -> round2(d.lng).let { lo -> Spot(la, lo, Geo.hash(la, lo)!!) } } }
        val phones = linkedMapOf(
            "patient" to "01200001101", "donor" to "01200001102", "doctor" to "01200001103", "assistant" to "01200001104",
            "pharmacy" to "01200001105", "lab" to "01200001106", "ambulance" to "01200001107", "bloodbank" to "01200001108",
            "homecare" to "01200001109", "caregiver" to "01200001110",
        )
        val settings = linkedMapOf<String, Any?>("notify" to true, "max_push_per_day" to Rules.MAX_PUSH_PER_DAY, "notify_groups" to null, "view_groups" to null, "max_km" to 10)
        for ((key, phone) in phones) {
            val u = runCatching { ctx.user(key) }.getOrNull() ?: continue
            upsertProfile(
                u.uid,
                linkedMapOf<String, Any?>("name" to u.name, "phone" to phone, "lang" to "bn", "notify" to true, "updated_at" to Timestamp(now))
                    .apply { putAll(locationFields(now, home, null, "district")) },
            )
        }
        val donorUid = ctx.user("donor").uid
        val caregiverUid = ctx.user("caregiver").uid
        // the donor: O+, eligible (last donation 150 days ago)
        upsertProfile(
            donorUid,
            linkedMapOf<String, Any?>(
                "blood_group" to "O+", "birth_date_ms" to birth(1994, 3, 15), "weight_kg" to 68.0, "last_donation_ms" to daysAgo(150),
                "donor_geo" to Geo.donorKey("O+", home.geohash),
            ).apply { putAll(settings) },
        )
        // the caregiver: B+, gave 35 days ago — not eligible now (shows the next date)
        upsertProfile(
            caregiverUid,
            linkedMapOf<String, Any?>(
                "blood_group" to "B+", "birth_date_ms" to birth(1990, 8, 2), "weight_kg" to 55.0, "last_donation_ms" to daysAgo(35),
                "donor_geo" to Geo.donorKey("B+", home.geohash),
            ).apply { putAll(settings) },
        )

        // ======================= 2) background users (no login) =======================
        var phoneNo = 1
        val people = LinkedHashMap<String, Person>()
        fun person(
            key: String,
            name: String,
            group: String? = null,
            birthMs: Long? = null,
            weight: Double? = null,
            last: Long? = null,
            dx: Double = 0.0,
            dy: Double = 0.0,
            maxKm: Int? = null,
            fareAfterKm: Int? = null,
        ): Person {
            val phone = "012000012${(phoneNo++).toString().padStart(2, '0')}"
            val loc = spot(ctx, dx, dy)
            val profile = linkedMapOf<String, Any?>("name" to name, "phone" to phone, "lang" to "bn", "updated_at" to Timestamp(now))
            profile.putAll(locationFields(now, loc, group))
            if (group != null) {
                profile["blood_group"] = group
                profile["birth_date_ms"] = birthMs
                profile["weight_kg"] = weight
                if (last != null) profile["last_donation_ms"] = last
                profile["notify"] = true
                profile["max_push_per_day"] = Rules.MAX_PUSH_PER_DAY
                profile["max_km"] = maxKm ?: 10
                if (fareAfterKm != null) profile["fare_after_km"] = fareAfterKm
            } else {
                profile["notify"] = true
            }
            val uid = ctx.extraUser(key, profile)
            return Person(uid, name, phone, group, fareAfterKm, loc.lat, loc.lng, loc.geohash).also { people[key] = it }
        }

        // 12 donors: different groups; some eligible, some gave recently / age / weight
        person("sakib", "ডেমো ডোনার সাকিব", "O+", birth(1996, 1, 20), 72.0, daysAgo(200), 1.2, 0.8)
        person("nadia", "ডেমো ডোনার নাদিয়া", "A+", birth(1999, 6, 5), 54.0, null, -0.8, 1.6)
        person("tanvir", "ডেমো ডোনার তানভীর", "B+", birth(1992, 11, 11), 75.0, daysAgo(130), 2.5, -1.1, fareAfterKm = 5)
        person("rumana", "ডেমো ডোনার রুমানা", "O-", birth(1993, 2, 28), 58.0, daysAgo(300), -2.2, -0.6)
        person("imran", "ডেমো ডোনার ইমরান", "AB+", birth(1988, 9, 9), 80.0, daysAgo(400), 3.4, 2.1)
        person("farhana", "ডেমো ডোনার ফারহানা", "A-", birth(1997, 4, 14), 52.0, daysAgo(40), -1.5, 2.9)
        person("jahid", "ডেমো ডোনার জাহিদ", "O+", birth(1991, 12, 1), 70.0, daysAgo(40), 0.4, -2.4)
        person("mitu", "ডেমো ডোনার মিতু", "B-", birth(1998, 7, 21), 51.0, daysAgo(180), 4.6, -0.3)
        person("rafiq", "ডেমো ডোনার রফিক", "O+", birth(1986, 5, 30), 77.0, null, -3.1, 1.0, maxKm = 25)
        person("shapla", "ডেমো ডোনার শাপলা", "AB-", birth(1995, 10, 3), 56.0, daysAgo(60), 1.9, 3.8)
        person("habib", "ডেমো ডোনার হাবিব (৬২ বছর)", "A+", birth(1964, 3, 3), 70.0, daysAgo(500), -4.0, -2.8)
        person("sumi", "ডেমো ডোনার সুমি (৪৫ কেজি)", "B+", birth(2000, 1, 9), 45.0, null, 0.9, 4.4)
        // requesters (not donors) and the blood bank manager
        person("selina", "ডেমো রক্তপ্রার্থী সেলিনা", dx = 0.7, dy = 1.3)
        person("mahmud", "ডেমো রক্তপ্রার্থী মাহমুদ", dx = 1.8, dy = -0.9)
        person("kamal", "ডেমো রক্তপ্রার্থী কামাল", dx = -1.1, dy = -1.7)
        person("ayesha", "ডেমো রক্তপ্রার্থী আয়েশা", dx = -0.5, dy = 2.2)
        person("nurul", "ডেমো রক্তপ্রার্থী নুরুল", dx = 2.9, dy = 1.4)
        person("polash", "ডেমো রক্তপ্রার্থী পলাশ", dx = 5.5, dy = 22.0)
        person("rehana", "ডেমো রক্তপ্রার্থী রেহানা", dx = 1.4, dy = 0.2)
        person("bbowner", "ডেমো ব্লাড ব্যাংক ব্যবস্থাপক", dx = 0.2, dy = -0.5)
        val X = people

        // ======================= 3) blood requests =======================
        fun request(
            id: String,
            byUid: String,
            byName: String,
            byPhone: String,
            group: String,
            bags: Int,
            hospital: String,
            dx: Double,
            dy: Double,
            neededBy: Long,
            createdAt: Long,
            note: String,
            neededAt: Long? = null,
            closed: Boolean = false,
            urgent: Boolean = false,
            travelFare: Boolean = false,
            acceptedCount: Int = 0,
            confirmedCount: Int = 0,
            fulfilled: Boolean = false,
            extra: Map<String, Any?> = emptyMap(),
        ): Req {
            val loc = spot(ctx, dx, dy)
            val bad = BloodRules.validateRequest(group, bags, hospital, DISTRICT, loc.lat, loc.lng, neededBy, neededAt, createdAt)
            check(bad == null) { "demo request $id: $bad" }
            jdbc.update(
                """
                INSERT INTO blood_requests (id, requester_uid, requester_name, patient_group, bags, hospital, place_name, lat, lng, geohash,
                  needed_by_ms, needed_at_ms, urgent, travel_fare, note, status, created_at, accepted_count, confirmed_count, fulfilled)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                id, byUid, byName, group, bags, hospital, DISTRICT, loc.lat, loc.lng, loc.geohash, neededBy, neededAt, urgent, travelFare, note,
                if (closed) "closed" else "open", Timestamp(createdAt), acceptedCount, confirmedCount, fulfilled,
            )
            if (extra.isNotEmpty()) {
                val set = com.lifolo.common.SqlSet()
                for ((k, v) in extra) set.value(k, v)
                set.update(jdbc, "blood_requests", "id = ?", id)
            }
            jdbc.update("INSERT INTO blood_request_contacts (request_id, name, phone) VALUES (?, ?, ?)", id, byName, byPhone)
            return Req(id, loc.lat, loc.lng, if (closed) "closed" else "open", hospital, neededAt)
        }

        /** like api/respond.js; the donor's "processing" list when still accepted on an open request */
        fun respond(req: Req, d: Person, at: Long = now - 2 * HOUR, status: String = "accepted", confirmed: Boolean = false) {
            jdbc.update(
                """
                INSERT INTO blood_responses (request_id, donor_uid, donor_name, donor_phone, donor_group, distance_km, fare_after_km, status,
                  confirmed, confirmed_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                req.id, d.uid, d.name, d.phone, d.group ?: "", BloodRules.jsRound(BloodRules.distanceKm(req.lat, req.lng, d.lat, d.lng)).toInt(),
                d.fareAfterKm, status, confirmed, if (confirmed) Timestamp(at + HOUR) else null, Timestamp(at),
            )
            if (status == "accepted" && req.status == "open") {
                jdbc.update(
                    "INSERT INTO user_commitments (uid, request_id, committed_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                    d.uid, req.id, Timestamp(at),
                )
            }
        }

        val patient = ctx.user("patient")
        val pPhone = phones.getValue("patient")
        val donorName = ctx.user("donor").name
        val donor = Person(donorUid, donorName, phones.getValue("donor"), "O+", null, home.lat, home.lng, home.geohash)

        // the patient's open request: A+, 2 bags — one confirmed, one accepted (waiting); the demo donor (O+) sees it
        val pOpen = request(
            "demo-rq-patient-open", patient.uid, patient.name, pPhone, "A+", 2, "ডেমো জয়দেবপুর জেনারেল হাসপাতাল", 0.6, -0.4,
            dayOf(3), now - 5 * HOUR, "ডেমো রিকোয়েস্ট (আসল নয়) — আমার বাবার পিত্তথলির অপারেশন, সকালে ভর্তি",
            neededAt = dhakaAt(dayOf(3), 11, 0), acceptedCount = 2, confirmedCount = 1,
            // donors were told already (3 rounds — no "notify again", so the demo never reaches real donors)
            extra = mapOf("notified_at" to Timestamp(now), "notified_count" to 7, "notify_rounds" to 3, "last_notified_at" to Timestamp(now - 4 * HOUR)),
        )
        respond(pOpen, X.getValue("sakib"), at = now - 4 * HOUR, confirmed = true)
        respond(pOpen, X.getValue("nadia"), at = now - 3 * HOUR)

        // the patient's history: fulfilled and closed 40 days ago (two donated — so they are not eligible now)
        val pDone = request(
            "demo-rq-patient-done", patient.uid, patient.name, pPhone, "A+", 2, "ডেমো শিববাড়ি জেনারেল হাসপাতাল", -0.3, 0.5,
            daysAgo(40), daysAgo(41) + 10 * HOUR, "ডেমো রিকোয়েস্ট (আসল নয়) — মায়ের রক্তস্বল্পতা",
            closed = true, acceptedCount = 2, confirmedCount = 2, fulfilled = true,
            extra = mapOf(
                "closed_at" to Timestamp(daysAgo(40) + 14 * HOUR), "closed_by" to "requester", "bags_received" to 2, "bags_received_before" to 0,
                "notified_count" to 9, "notify_rounds" to 1, "last_notified_at" to Timestamp(daysAgo(41) + 10 * HOUR),
            ),
        )
        respond(pDone, X.getValue("farhana"), at = daysAgo(41) + 11 * HOUR, status = "donated", confirmed = true)
        respond(pDone, X.getValue("jahid"), at = daysAgo(41) + 12 * HOUR, status = "donated", confirmed = true)
        // another one: closed as "not needed any more" (12 days ago)
        request(
            "demo-rq-patient-notneeded", patient.uid, patient.name, pPhone, "B+", 1, "ডেমো ছায়াবীথি মা ও শিশু ক্লিনিক", 1.0, 1.0,
            daysAgo(12), daysAgo(13) + 9 * HOUR, "ডেমো রিকোয়েস্ট (আসল নয়)", closed = true,
            extra = mapOf(
                "closed_at" to Timestamp(daysAgo(12) + 8 * HOUR), "closed_by" to "not_needed", "bags_received_before" to 0,
                "notified_count" to 4, "notify_rounds" to 1, "last_notified_at" to Timestamp(daysAgo(13) + 9 * HOUR),
            ),
        )

        // others' open requests (nearby, different groups)
        fun by(key: String) = X.getValue(key)
        request(
            "demo-rq-urgent", by("selina").uid, by("selina").name, by("selina").phone, "A+", 1, "ডেমো শিববাড়ি জেনারেল হাসপাতাল", 0.9, 1.4,
            dayOf(2), now - 1 * HOUR, "ডেমো রিকোয়েস্ট (আসল নয়) — সিজারিয়ান অপারেশন, জরুরি", urgent = true,
        )
        request(
            "demo-rq-fare", by("mahmud").uid, by("mahmud").name, by("mahmud").phone, "B+", 2, "ডেমো রাজবাড়ি রোড ক্লিনিক", 1.9, -1.0,
            dayOf(4), now - 7 * HOUR, "ডেমো রিকোয়েস্ট (আসল নয়) — যাতায়াত ভাড়া আর নাস্তা দেওয়া হবে",
            neededAt = dhakaAt(dayOf(4), 10, 30), travelFare = true,
        )
        val rqO = request(
            "demo-rq-oplus", by("kamal").uid, by("kamal").name, by("kamal").phone, "O+", 1, "ডেমো ছায়াবীথি মা ও শিশু ক্লিনিক", -1.2, -1.6,
            dayOf(6), now - 26 * HOUR, "ডেমো রিকোয়েস্ট (আসল নয়) — ডেঙ্গু, প্লাটিলেট কম", acceptedCount = 1,
        )
        respond(rqO, by("rafiq"), at = now - 20 * HOUR)
        request(
            "demo-rq-thal", by("ayesha").uid, by("ayesha").name, by("ayesha").phone, "AB+", 3, "ডেমো থ্যালাসেমিয়া সেন্টার, শিববাড়ি", -0.6, 2.3,
            dayOf(3), now - 30 * HOUR, "ডেমো রিকোয়েস্ট (আসল নয়) — থ্যালাসেমিয়া, প্রতি মাসে রক্ত লাগে",
            extra = mapOf("bags_received" to 1),
        )
        request(
            "demo-rq-oneg", by("nurul").uid, by("nurul").name, by("nurul").phone, "O-", 1, "ডেমো জয়দেবপুর জেনারেল হাসপাতাল", 2.8, 1.3,
            dayOf(2), now - 3 * HOUR, "ডেমো রিকোয়েস্ট (আসল নয়) — O- গ্রুপ দরকার",
        )
        request(
            "demo-rq-far", by("polash").uid, by("polash").name, by("polash").phone, "B+", 1, "ডেমো শ্রীপুর উপজেলা স্বাস্থ্য কমপ্লেক্স", 5.5, 22.0,
            dayOf(5), now - 9 * HOUR, "ডেমো রিকোয়েস্ট (আসল নয়) — ১০ কিমির বেশি দূরে",
        )
        // the demo donor agreed earlier and the requester confirmed them (home "processing", number visible)
        val rqD = request(
            "demo-rq-donor-confirmed", by("rehana").uid, by("rehana").name, by("rehana").phone, "A+", 1, "ডেমো রেলগেট ক্লিনিক, জয়দেবপুর", 1.4, 0.2,
            dayOf(2), now - 10 * HOUR, "ডেমো রিকোয়েস্ট (আসল নয়) — হার্টের অপারেশন",
            neededAt = dhakaAt(dayOf(2), 16, 0), acceptedCount = 1, confirmedCount = 1, fulfilled = true,
        )
        respond(rqD, donor, at = now - 8 * HOUR, confirmed = true)

        // ======================= 4) emergency appeals (Lifolo team) =======================
        val eLoc = spot(ctx, 0.3, 0.2)
        val radiusKm = 25
        val districts = (listOf(DISTRICT) + Places.DISTRICTS.filter { BloodRules.distanceKm(eLoc.lat, eLoc.lng, it.lat, it.lng) <= radiusKm + 25 }.map { it.bn })
            .distinct().take(30)
        fun emergency(
            id: String,
            hospitalName: String,
            lat: Double,
            lng: Double,
            exact: Boolean,
            radius: Int,
            groups: List<List<Any>>,
            contactPhone: String,
            note: String,
            status: String,
            createdAt: Long,
            endsAt: Long,
            closedAt: Long?,
            waves: Int,
            lastWaveAt: Long,
        ) {
            jdbc.update(
                """
                INSERT INTO emergencies (id, district, districts, hospital_id, hospital_name, lat, lng, exact_location, radius_km, contact_phone,
                  note, status, waves, last_wave_at, created_at, created_by, ends_at, closed_at, is_demo)
                VALUES (?, ?, ?::text[], NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'demo@lifolo-demo.test', ?, ?, TRUE)
                """.trimIndent(),
                id, DISTRICT, EmergencyService.pgTextArray(districts), hospitalName, lat, lng, exact, radius, contactPhone, note, status, waves,
                Timestamp(lastWaveAt), Timestamp(createdAt), Timestamp(endsAt), closedAt?.let { Timestamp(it) },
            )
            for (g in groups) {
                jdbc.update(
                    "INSERT INTO emergency_groups (emergency_id, blood_group, need, coming, notified, closed) VALUES (?, ?, ?, ?, ?, ?)",
                    id, g[0], g[1], g[2], g[3], g[4],
                )
            }
            ctx.track("emergencies", mapOf("id" to id))
        }
        fun emDonor(eid: String, uid: String, group: String?, status: String, at: Long) {
            jdbc.update(
                "INSERT INTO emergency_donors (emergency_id, uid, blood_group, km, status, notified_at, coming_at) VALUES (?, ?, ?, 2.1, ?, ?, ?)",
                eid, uid, group, status, Timestamp(at), if (status == "coming") Timestamp(at + 600000) else null,
            )
        }
        val e1 = "demo-em-live"
        emergency(
            e1, "ডেমো তাজউদ্দীন মেডিকেল কলেজ হাসপাতাল (জরুরি বিভাগ)", eLoc.lat, eLoc.lng, true, radiusKm,
            listOf(listOf("O+", 4, 1, 6, false), listOf("A+", 3, 0, 4, false), listOf("B-", 1, 1, 3, true)),
            "01200001310", "ডেমো আহ্বান (আসল নয়) — বাস দুর্ঘটনা, জরুরি বিভাগের ব্লাড ব্যাংকে আসুন", "active",
            now - 2 * HOUR, now + 46 * HOUR, null, 1, now - 2 * HOUR,
        )
        emDonor(e1, by("rafiq").uid, by("rafiq").group, "coming", now - 2 * HOUR)
        emDonor(e1, by("mitu").uid, by("mitu").group, "coming", now - 2 * HOUR)
        emDonor(e1, by("sakib").uid, by("sakib").group, "notified", now - 2 * HOUR)
        emDonor(e1, by("nadia").uid, by("nadia").group, "notified", now - 2 * HOUR)
        // an ended appeal: the demo donor said "coming" — the app asks "did you donate?"
        val e2 = "demo-em-ended"
        emergency(
            e2, "ডেমো সদর হাসপাতাল, গাজীপুর", home.lat, home.lng, false, 10, listOf(listOf("O+", 2, 2, 6, true)),
            "01200001311", "ডেমো আহ্বান (আসল নয়) — শেষ হয়েছে", "closed",
            now - 2 * day - 12 * HOUR, now - 2 * day, now - 2 * day, 2, now - 2 * day - 10 * HOUR,
        )
        emDonor(e2, donorUid, "O+", "coming", now - 2 * day - 11 * HOUR)
        emDonor(e2, by("sakib").uid, by("sakib").group, "coming", now - 2 * day - 11 * HOUR)

        // ======================= 5) blood banks =======================
        val allServices = listOf("whole_blood", "components", "platelets", "screening", "donate_here")
        fun bank(
            id: String,
            ownerUid: String?,
            name: String,
            kind: String,
            phone: String,
            address: String,
            services: List<String>,
            status: String,
            licenseNo: String = "",
            open24: Boolean = false,
            openAt: String = "",
            closeAt: String = "",
            closedDays: List<Int> = emptyList(),
            ageDays: Int = 30,
            docs: Map<String, Long> = emptyMap(),
            calls: List<Int>? = null,
        ) {
            val data = linkedMapOf<String, Any?>(
                "name" to name, "kind" to kind, "phone" to phone, "district" to DISTRICT, "upazilaId" to SADAR, "address" to address,
                "licenseNo" to licenseNo, "open24" to open24, "openAt" to (if (open24) "" else openAt), "closeAt" to (if (open24) "" else closeAt),
                "services" to services, "closedDays" to closedDays, "updatedAt" to now - 20 * day,
            )
            val approved = status == "approved"
            jdbc.update(
                """
                INSERT INTO blood_banks (id, owner_uid, added_by_admin, name, kind, phone, district, upazila_id, address, license_no, open24, open_at,
                  close_at, closed_days, services, status, created_at, updated_at, docs_have, reviewed_at, reviewed_by, approved_name, last_approved)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::smallint[], ?::text[], ?, ?, ?, ?::text[], ?, ?, ?, ?::jsonb)
                """.trimIndent(),
                id, ownerUid, ownerUid == null, name, kind, phone, DISTRICT, SADAR, address, licenseNo, open24, data["openAt"], data["closeAt"],
                "{" + closedDays.joinToString(",") + "}", EmergencyService.pgTextArray(services), status, Timestamp(now - ageDays * day),
                Timestamp(now - 20 * day), EmergencyService.pgTextArray(docs.keys.toList()),
                if (approved) Timestamp(now - 25 * day) else null, if (approved) "demo" else null, if (approved) name else null,
                if (approved) mapper.writeValueAsString(LinkedHashMap(ListingRules.snapshot(data)).apply { put("demo", true) }) else null,
            )
            if (ownerUid == null) ctx.track("blood_banks", mapOf("id" to id))
            for ((t, at) in docs) {
                jdbc.update(
                    "INSERT INTO verify_docs (kind, listing_id, type, owner_uid, data, size, at) VALUES ('bloodbank', ?, ?, ?, ?, ?, ?)",
                    id, t, ownerUid, DEMO_JPEG, DEMO_JPEG.length, Timestamp(at),
                )
            }
            // the owner's statistics: calls of the last days
            if (calls != null) {
                val total = calls.sum()
                jdbc.update("INSERT INTO listing_stats (kind, listing_id, total, callers) VALUES ('bloodbank', ?, ?, ?)", id, total, maxOf(1, total - 3))
                calls.forEachIndexed { i, n ->
                    if (n > 0) {
                        jdbc.update(
                            "INSERT INTO listing_stats_daily (kind, listing_id, day, calls) VALUES ('bloodbank', ?, ?, ?)",
                            id, Sql.date(DhakaTime.day(now - i * day)), n,
                        )
                    }
                }
                ctx.track("listing_stats", mapOf("kind" to "bloodbank", "listing_id" to id))
            }
            // the sanction keys a suspension would write are tracked in advance, so wiping the demo removes them too
            for (k in Sanctions.listingKeys("bloodbank", data)) ctx.track("sanctions", mapOf("key" to k))
        }
        val docTime = now - 3 * HOUR
        val bbUid = ctx.user("bloodbank").uid
        bank(
            "demo-bb-mine", bbUid, "ডেমো জীবনধারা ব্লাড ব্যাংক", "private", "01200001301", "ডেমো ভবন, শিববাড়ি মোড়", allServices, "approved",
            licenseNo = "DEMO-BB-0001", open24 = true, docs = linkedMapOf("front" to docTime, "license" to docTime),
            calls = listOf(3, 5, 2, 0, 4, 6, 1, 2, 0, 3, 2, 5, 1, 0, 2),
        )
        bank(
            "demo-bb-pending", bbUid, "ডেমো রক্তবন্ধু স্বেচ্ছাসেবী সংগঠন", "org", "01200001302", "ডেমো অফিস, জয়দেবপুর বাজার",
            listOf("whole_blood", "donate_here"), "pending", openAt = "10:00", closeAt = "18:00", ageDays = 1, docs = linkedMapOf("front" to docTime),
        )
        bank(
            "demo-bb-govt", by("bbowner").uid, "ডেমো সদর হাসপাতাল ব্লাড ব্যাংক", "govt", "01200001303", "ডেমো সদর হাসপাতাল, ২য় তলা, হাসপাতাল রোড",
            listOf("whole_blood", "screening", "donate_here"), "approved", openAt = "08:00", closeAt = "20:00", closedDays = listOf(5),
            calls = listOf(1, 0, 2, 1),
        )
        bank(
            "demo-bb-club", null, "ডেমো আলোর পথ ব্লাড ডোনার ক্লাব", "org", "01200001304", "ডেমো ক্লাবঘর, ছায়াবীথি",
            listOf("whole_blood", "platelets", "donate_here"), "approved", openAt = "09:00", closeAt = "17:00", ageDays = 60, calls = listOf(0, 1, 0, 0, 2),
        )
        // for the admin's report list: a bad experience after calling the government blood bank
        val mahmud = by("mahmud").uid
        jdbc.update("INSERT INTO blood_bank_calls (uid, bank_id, at) VALUES (?, 'demo-bb-govt', ?)", mahmud, Timestamp(now - 30 * HOUR))
        jdbc.update(
            "INSERT INTO mod_reports (id, target_path, kind, reporter_uid, reason, status, created_at) VALUES (?, 'bloodBanks/demo-bb-govt', 'bloodbank', ?, ?, 'open', ?)",
            "bloodBanks_demo-bb-govt__$mahmud", mahmud, "ডেমো রিপোর্ট (আসল নয়) — সন্ধ্যা ৭টায় গিয়ে বন্ধ পেয়েছি, ফোনও ধরেনি", Timestamp(now - 28 * HOUR),
        )

        // ======================= 6) hospitals: added from the app (approved) + a request (pending) =======================
        jdbc.update(
            """
            INSERT INTO hospitals (id, name_en, name_bn, type, district, upazila_id, union_id, address, phone, norm_key, source, status, created_at)
            VALUES ('demo-hosp-joydebpur', ?, ?, 'private_hospital', ?, ?, NULL, ?, '01200001320', ?, 'user', 'approved', ?)
            """.trimIndent(),
            "Demo Joydebpur General Hospital", "ডেমো জয়দেবপুর জেনারেল হাসপাতাল", DISTRICT, SADAR, "ডেমো রোড, জয়দেবপুর",
            normalizeName("Demo Joydebpur General Hospital"), Timestamp(now - 50 * day),
        )
        ctx.track("hospitals", mapOf("id" to "demo-hosp-joydebpur"))
        jdbc.update(
            """
            INSERT INTO hospital_requests (id, name_en, name_bn, type, district, upazila_id, union_id, address, phone, norm_key, count, status, created_at)
            VALUES ('demo-hreq-chayabithi', ?, ?, 'clinic', ?, ?, NULL, ?, '01200001321', ?, 2, 'pending', ?)
            """.trimIndent(),
            "Demo Chayabithi Mother and Child Clinic", "ডেমো ছায়াবীথি মা ও শিশু ক্লিনিক", DISTRICT, SADAR, "ডেমো গলি, ছায়াবীথি",
            normalizeName("Demo Chayabithi Mother and Child Clinic"), Timestamp(now - 20 * HOUR),
        )
        for (u in listOf(patient.uid, by("kamal").uid)) {
            jdbc.update("INSERT INTO hospital_request_requesters (request_id, uid) VALUES ('demo-hreq-chayabithi', ?)", u)
        }
        ctx.track("hospital_requests", mapOf("id" to "demo-hreq-chayabithi"))

        // ======================= 7) a last-donation-date correction request (old flow — admins see it) =======================
        val farhana = by("farhana")
        jdbc.update(
            """
            INSERT INTO correction_requests (id, uid, name, reason, current_ms, requested_ms, status, created_at)
            VALUES ('demo-corr-farhana', ?, ?, ?, ?, ?, 'pending', ?)
            """.trimIndent(),
            farhana.uid, farhana.name, "ডেমো অনুরোধ (আসল নয়) — রক্তপ্রার্থী ভুল করে আমার নাম দিয়েছিলেন, আসলে ৪ মাস আগে দিয়েছি",
            daysAgo(40), daysAgo(130), Timestamp(now - 6 * HOUR),
        )

        // ======================= 8) bell notifications (the app's notification list) =======================
        fun inbox(uid: String, at: Long, title: String, body: String, data: Map<String, Any?>) {
            val rev = (9999999999999L - at).toString().padStart(13, '0')
            jdbc.update(
                "INSERT INTO inbox_items (id, uid, title, body, data, created_at) VALUES (?, ?, ?, ?, ?::jsonb, ?) ON CONFLICT (id) DO NOTHING",
                "${uid}_${rev}_demo${(at % 1000).toString().padStart(3, '0')}", uid, title, body, mapper.writeValueAsString(data), Timestamp(at),
            )
        }
        inbox(
            patient.uid, now - 3 * HOUR, "একজন ডোনার রাজি হয়েছেন", "${by("nadia").name} (A+) রক্ত দিতে রাজি। অ্যাপ খুলে নম্বর দেখুন।",
            mapOf("requestId" to pOpen.id, "type" to "response"),
        )
        inbox(
            patient.uid, now - 4 * HOUR - 1, "একজন ডোনার রাজি হয়েছেন", "${by("sakib").name} (O+) রক্ত দিতে রাজি। অ্যাপ খুলে নম্বর দেখুন।",
            mapOf("requestId" to pOpen.id, "type" to "response"),
        )
        inbox(
            donorUid, now - 7 * HOUR, "আপনাকে নিশ্চিত করা হয়েছে, অনুগ্রহ করে আসুন", "${rqD.hospital} · ${BloodRules.formatTimeBn(rqD.neededAtMillis!!)}",
            mapOf("requestId" to rqD.id, "type" to "request"),
        )
        inbox(
            donorUid, now - 2 * HOUR - 7, "জরুরি: O+ রক্ত দরকার",
            "ডেমো তাজউদ্দীন মেডিকেল কলেজ হাসপাতাল (জরুরি বিভাগ) · প্রায় ২ কিমি দূরে · Lifolo টিমের আহ্বান — খুলে \"আসছি\" চাপুন",
            mapOf("type" to "emergency", "emergencyId" to e1),
        )
    }

    companion object {
        /** Gazipur, spelled exactly like the database */
        val DISTRICT: String by lazy { Places.DISTRICTS.first { it.en == "Gazipur" }.bn }

        /** Gazipur Sadar upazila (geo.json) */
        const val SADAR = 320
        const val HOUR = 3600L * 1000

        private val STOP = setOf(
            "hospital", "hospitals", "clinic", "clinics", "ltd", "limited", "pvt", "private", "the", "and", "of",
            "center", "centre", "bd", "bangladesh", "general", "co",
        )

        /** hospital-match.js normalizeName */
        fun normalizeName(name: String?): String = (name ?: "").lowercase().replace("&", " and ")
            .replace(Regex("[^a-z0-9]+"), " ").split(" ").filter { it.isNotEmpty() && it !in STOP }.joinToString(" ")

        /** a 360×200 demo JPEG of a blood bank sign ("DEMO … TEST PHOTO - NOT REAL") for the admin's check page */
        const val DEMO_JPEG = "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"
    }
}
