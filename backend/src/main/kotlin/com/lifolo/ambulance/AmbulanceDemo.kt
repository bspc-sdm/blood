package com.lifolo.ambulance

import com.fasterxml.jackson.databind.ObjectMapper
import com.lifolo.admin.kernel.DemoSeedContext
import com.lifolo.admin.kernel.DemoSeedPart
import com.lifolo.admin.kernel.ListingRules
import com.lifolo.admin.kernel.ListingStats
import com.lifolo.admin.kernel.VerifyDocs
import com.lifolo.common.Geo
import com.lifolo.common.Js
import com.lifolo.common.Places
import com.lifolo.common.Sql
import com.lifolo.common.SqlSet
import com.lifolo.demo.DemoWorld
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import kotlin.math.max
import kotlin.math.sin

/**
 * The ambulance part of demo/services.js (step 2 "সেবা"): the demo driver's four cars (AC, non-AC on a trip, a mini
 * whose "available" ends in 30 minutes, a new ICU car awaiting approval) and three other owners' cars in Gazipur,
 * with fare charts (2.30.0), document photos, verification, call statistics, the patient's earlier calls/ratings.
 * Not here: the demo report on "মমতা" (reports are written by P6's `report`/moderation code).
 */
@Component
class AmbulanceDemo(
    private val jdbc: JdbcTemplate,
    private val repo: AmbRepo,
    private val verify: VerifyDocs,
    private val stats: ListingStats,
    private val mapper: ObjectMapper,
) : DemoSeedPart {
    override val step = 2
    override val label = "সেবা"

    private val docImg: String by lazy {
        javaClass.getResourceAsStream("demo-doc.b64")?.use { String(it.readAllBytes()).trim() } ?: error("missing demo-doc.b64")
    }

    private class Car(
        val name: String,
        val phone: String,
        val vehicleNo: String,
        val types: List<String>,
        val area: String,
        val fare: String,
        val status: String,
        val upazilaId: Int = SADAR,
        val at: Pair<Double, Double>? = null,
        val available: Boolean = false,
        val availableAt: Long? = null,
        val busyAt: Long? = null,
        val ratingSum: Int = 0,
        val ratingCount: Int = 0,
        val callsPerDay: Int = 1,
        val createdAt: Long? = null,
        val recent: List<Triple<Int, String, String>> = emptyList(),
    )

    companion object {
        private const val SADAR = 320 // Gazipur Sadar
        private const val KALIAKAIR = 318
        private const val SREEPUR = 321
        private const val HOUR = 3600 * 1000L
        private const val MIN = 60 * 1000L
        private val AMB_CHECKS = listOf("reg_no", "reg_type", "plate", "fitness", "phone", "details")
        private const val TAJ = "শহীদ তাজ উদ্দীন আহমদ মেডিকেল কলেজ হাসপাতাল"
        private const val DMC = "ঢাকা মেডিকেল কলেজ হাসপাতাল"

        private fun district(en: String) = Places.DISTRICTS.first { it.en == en }.bn

        private fun up(id: Int, amount: Int) = FareRow(Dest("upazila", upazilaId = id), amount)
        private fun dist(d: String, amount: Int) = FareRow(Dest("district", district = d), amount)
        private fun hosp(name: String, amount: Int) = FareRow(Dest("hospital", name = name), amount)
        private fun fc(local: Int?, rows: List<FareRow>, oxygen: Int? = null, icu: Int? = null, night: Int? = null) = FareChart(local, rows, oxygen, icu, night)

        /** demo fares close to Gazipur's real ones (own upazila 800–1200, the medical college 1500, Dhaka 4000–8000, ICU more) */
        val DEMO_FARES: Map<String, FareChart> by lazy {
            val dhaka = district("Dhaka")
            val mym = district("Mymensingh")
            mapOf(
                "demo-svc-amb-ac" to fc(1000, listOf(up(KALIAKAIR, 1500), up(SREEPUR, 1800), hosp(TAJ, 1000), dist(dhaka, 4500), hosp(DMC, 5000)), oxygen = 500, night = 500),
                "demo-svc-amb-nonac" to fc(800, listOf(up(KALIAKAIR, 1200), dist(dhaka, 4000)), oxygen = 400),
                "demo-svc-amb-mini" to fc(800, listOf(up(KALIAKAIR, 1200), hosp(TAJ, 800), dist(dhaka, 4000)), night = 300),
                "demo-svc-amb-icu-new" to fc(2500, listOf(dist(dhaka, 7500)), icu = 3500),
                "demo-svc-amb-momota" to fc(900, listOf(up(SADAR, 1500), hosp(TAJ, 1500), dist(dhaka, 5500), dist(mym, 4500)), oxygen = 500, night = 400),
                "demo-svc-amb-icu" to fc(3000, listOf(hosp(TAJ, 3000), dist(dhaka, 8000), hosp(DMC, 8000)), icu = 4000, night = 1000),
                "demo-svc-amb-freezer" to fc(1200, listOf(up(KALIAKAIR, 2000), up(SREEPUR, 2000), dist(dhaka, 6000))),
            )
        }

        /** demo/services.js dailyCalls: 30 days of made-up calls → {days ago: n}, total */
        fun dailyCalls(base: Int, seed: Int): Pair<Map<Int, Int>, Int> {
            val d = LinkedHashMap<Int, Int>()
            var total = 0
            for (i in 0 until 30) {
                val n = max(0, Js.round(base + 2 * sin((i + seed) * 1.3) + ((i * 7 + seed) % 3) - 1).toInt())
                if (n != 0) d[i] = n
                total += n
            }
            return d to total
        }

        private fun at3(p: Pair<Double, Double>) = Js.round(p.first * 1000) / 1000 to Js.round(p.second * 1000) / 1000
    }

    override fun seed(ctx: DemoSeedContext) {
        val now = ctx.nowMs
        val day = ctx.dayMs
        val gzp = district("Gazipur")
        val p = ctx.user("patient").uid
        fun loc(dx: Double, dy: Double) = ctx.near(dx, dy).let { at3(it.lat to it.lng) }

        val amb = ctx.user("ambulance")
        profile(amb.uid, amb.name, "01700000170", gzp)

        fun car(id: String, owner: String, x: Car) {
            val created = x.createdAt ?: (now - 60 * day)
            val base = x.at?.let { AmbRules.point(it.first, it.second) }
            val approved = x.status == "approved"
            val remind = approved && x.available && x.availableAt != null && now - x.availableAt < AmbRules.AVAILABLE_MS
            val data = linkedMapOf<String, Any?>(
                "name" to x.name, "phone" to x.phone, "vehicleNo" to x.vehicleNo, "types" to x.types, "district" to gzp,
                "upazilaId" to x.upazilaId, "area" to x.area, "fare" to x.fare,
            )
            jdbc.update(
                """
                INSERT INTO ambulances (id, owner_uid, name, phone, vehicle_no, types, district, upazila_id, area, fare, lat, lng,
                  available, available_at, busy_at, remind_at, terms_accepted, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?::text[], ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?, ?, ?)
                """.trimIndent(),
                id, owner, x.name, x.phone, x.vehicleNo, x.types.joinToString(",", "{", "}"), gzp, x.upazilaId, x.area, x.fare,
                base?.first, base?.second, x.available, x.availableAt?.let { Sql.ts(it) }, x.busyAt?.let { Sql.ts(it) },
                if (remind) Sql.ts(x.availableAt!! + AmbRules.REMIND_AT_MS) else null, x.status, Sql.ts(created), Sql.ts(created),
            )
            DEMO_FARES[id]?.let { repo.saveChart(id, it) }
            verify.save(null, "ambulance", id, owner, data, mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(mapOf("reg" to docImg, "fitness" to docImg, "front" to docImg)), false)
            if (approved) {
                SqlSet().ms("reviewed_at", created + day).value("reviewed_by", "demo").value("approved_name", x.name)
                    .json("last_approved", ListingRules.json(ListingRules.snapshot(data)))
                    .value("verified_by", "demo").value("verified_by_email", "demo@lifolo-demo.test").ms("verified_at", created + day)
                    .textArray("verification_checks", AMB_CHECKS)
                    .value("rating_sum", x.ratingSum).value("rating_count", x.ratingCount)
                    .update(jdbc, "ambulances", "id = ?", id)
                // call statistics: 30 days of calls, 15 older ones, 80 % distinct callers
                val (d, total) = dailyCalls(x.callsPerDay, id.length)
                var callers = Js.round(total * 0.8).toInt()
                for ((ago, n) in d) {
                    repeat(n) { stats.bumpCall("ambulance", id, now - ago * day, callers-- > 0) }
                }
                repeat(15) { stats.bumpCall("ambulance", id, now - 200 * day, false) }
                for ((rating, comment, rater) in x.recent) stats.addRecentRating("ambulance", id, rater, rating, comment)
            }
        }

        val acId = "demo-svc-amb-ac"
        car(
            acId, amb.uid,
            Car(
                "ডেমো জীবন অ্যাম্বুলেন্স (এসি)", "01700000170", "DEMO-GAZ-CHA-11-0001", listOf("ac", "general"),
                "তাজউদ্দীন মেডিকেল গেট, শিববাড়ি", "শহরের মধ্যে ৮০০ টাকা, ঢাকা ৩০০০ টাকা", "approved", at = loc(0.8, 1.2),
                available = true, availableAt = now - 30 * MIN, ratingSum = 42, ratingCount = 9, callsPerDay = 2,
                recent = listOf(
                    Triple(4, "ভাড়া একটু বেশি, তবে গাড়ি পরিষ্কার", DemoWorld.uidOf("x-rater-a1")),
                    Triple(5, "রাত ২টায় ফোন করেছিলাম, ২০ মিনিটে এসেছে। অনেক ধন্যবাদ।", p),
                ),
            ),
        )
        // the patient called earlier and rated
        jdbc.update(
            "INSERT INTO ambulance_calls (uid, ambulance_id, at, first_at, rating, comment, rated_at) VALUES (?, ?, ?, ?, 5, ?, ?) ON CONFLICT DO NOTHING",
            p, acId, Sql.ts(now - 4 * day), Sql.ts(now - 4 * day), "রাত ২টায় ফোন করেছিলাম, ২০ মিনিটে এসেছে। অনেক ধন্যবাদ।", Sql.ts(now - 3 * day),
        )
        // on a trip now (the driver set "on a trip")
        car(
            "demo-svc-amb-nonac", amb.uid,
            Car(
                "ডেমো জীবন অ্যাম্বুলেন্স (নন-এসি)", "01700000171", "DEMO-GAZ-CHA-11-0002", listOf("general"), "শিববাড়ি, ছায়াবীথি",
                "শহরের মধ্যে ৫০০ টাকা", "approved", at = loc(1.5, 0.5), available = false, availableAt = now - 50 * MIN, busyAt = now - 50 * MIN,
                ratingSum = 8, ratingCount = 2,
            ),
        )
        // "still available?": available 11.5 hours ago — "30 more minutes" on the card, the reminder goes out on the next list read
        car(
            "demo-svc-amb-mini", amb.uid,
            Car(
                "ডেমো জীবন মিনি অ্যাম্বুলেন্স", "01700000176", "DEMO-GAZ-CHA-11-0007", listOf("general"), "চান্দনা চৌরাস্তা, ভোগড়া",
                "শহরের মধ্যে ৪০০ টাকা", "approved", at = loc(-2.5, -1.5), available = true, availableAt = now - 11 * HOUR - 30 * MIN,
                ratingSum = 9, ratingCount = 2,
            ),
        )
        // the demo driver's new ICU car — awaiting approval
        car(
            "demo-svc-amb-icu-new", amb.uid,
            Car(
                "ডেমো জীবন আইসিইউ অ্যাম্বুলেন্স", "01700000172", "DEMO-GAZ-CHA-11-0003", listOf("icu", "ac"), "গাজীপুর থেকে ঢাকা",
                "ঢাকা ৮০০০ টাকা (অক্সিজেন সহ)", "pending", createdAt = now - 6 * HOUR,
            ),
        )
        val momota = ctx.extraUser("svc-amb-momota", mapOf("name" to "ডেমো মমতা অ্যাম্বুলেন্সের মালিক", "phone" to "01700000173", "place_name" to gzp))
        val momotaId = "demo-svc-amb-momota"
        car(
            momotaId, momota,
            Car(
                "ডেমো মমতা অ্যাম্বুলেন্স", "01700000173", "DEMO-GAZ-GA-12-0004", listOf("general"), "মাওনা চৌরাস্তা, শ্রীপুর", "আলোচনা সাপেক্ষে",
                "approved", upazilaId = SREEPUR, at = loc(5.5, 22.0), available = true, availableAt = now - 3 * HOUR, ratingSum = 16, ratingCount = 5,
            ),
        )
        val icuOwner = ctx.extraUser("svc-amb-icu", mapOf("name" to "ডেমো আইসিইউ অ্যাম্বুলেন্স সার্ভিসের মালিক", "phone" to "01700000174", "place_name" to gzp))
        val icuId = "demo-svc-amb-icu"
        car(
            icuId, icuOwner,
            Car(
                "ডেমো আইসিইউ অ্যাম্বুলেন্স সার্ভিস", "01700000174", "DEMO-GAZ-CHA-13-0005", listOf("icu", "ac"), "তাজউদ্দীন মেডিকেল কলেজ হাসপাতাল",
                // "available" more than 12 hours ago → "call to ask"
                "ঢাকা ১০০০০ টাকা", "approved", at = loc(0.5, 0.9), available = true, availableAt = now - 20 * HOUR, ratingSum = 23, ratingCount = 5,
            ),
        )
        val freezerOwner = ctx.extraUser("svc-amb-freezer", mapOf("name" to "ডেমো ফ্রিজার ভ্যানের মালিক", "phone" to "01700000175", "place_name" to gzp))
        // no base location → "about" distances from the district centre
        car(
            "demo-svc-amb-freezer", freezerOwner,
            Car(
                "ডেমো শেষ যাত্রা ফ্রিজার ভ্যান", "01700000175", "DEMO-GAZ-CHA-14-0006", listOf("freezer"), "পুরো গাজীপুর জেলা ও ঢাকা",
                "দূরত্ব অনুযায়ী", "approved", available = true, availableAt = now - 2 * HOUR,
            ),
        )
        // the patient called the ICU service 3 hours ago → can rate now
        jdbc.update(
            "INSERT INTO ambulance_calls (uid, ambulance_id, at, first_at) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
            p, icuId, Sql.ts(now - 3 * HOUR), Sql.ts(now - 3 * HOUR),
        )
        // a buyer called "মমতা" (the call that allows their report)
        val buyer = ctx.extraUser("svc-buyer", mapOf("name" to "ডেমো ক্রেতা সালমা", "phone" to "01700000158", "place_name" to gzp))
        jdbc.update(
            "INSERT INTO ambulance_calls (uid, ambulance_id, at, first_at) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
            buyer, momotaId, Sql.ts(now - 3 * day), Sql.ts(now - 3 * day),
        )
    }

    /** demo/services.js profile(): name, phone, language and place (district centre) when missing */
    private fun profile(uid: String, name: String, phone: String, district: String) {
        val centre = DemoSeedCenter.rounded()
        jdbc.update("INSERT INTO user_profiles (uid) VALUES (?) ON CONFLICT (uid) DO NOTHING", uid)
        jdbc.update(
            """
            UPDATE user_profiles SET name = coalesce(nullif(name, ''), ?), phone = coalesce(nullif(phone, ''), ?), lang = coalesce(lang, 'bn'),
              lat = CASE WHEN coalesce(place_name, '') = '' THEN ? ELSE lat END,
              lng = CASE WHEN coalesce(place_name, '') = '' THEN ? ELSE lng END,
              geohash = CASE WHEN coalesce(place_name, '') = '' THEN ? ELSE geohash END,
              location_source = CASE WHEN coalesce(place_name, '') = '' THEN 'district' ELSE location_source END,
              place_name = CASE WHEN coalesce(place_name, '') = '' THEN ? ELSE place_name END
            WHERE uid = ?
            """.trimIndent(),
            name, phone, centre.first, centre.second, Geo.hash(centre.first, centre.second), district, uid,
        )
    }
}

/** demo/index.js CENTER rounded to 2 decimals (the business accounts' profile place) */
internal object DemoSeedCenter {
    fun rounded(): Pair<Double, Double> =
        Js.round(com.lifolo.admin.kernel.DemoSeed.CENTER_LAT * 100) / 100 to Js.round(com.lifolo.admin.kernel.DemoSeed.CENTER_LNG * 100) / 100
}
