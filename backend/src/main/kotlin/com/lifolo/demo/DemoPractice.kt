package com.lifolo.demo

import com.lifolo.admin.kernel.DemoSeedContext
import com.lifolo.admin.kernel.DemoSeedPart
import com.lifolo.appointments.CareRules
import com.lifolo.appointments.Ch
import com.lifolo.appointments.HomeVisit
import com.lifolo.appointments.OnlineSwitch
import com.lifolo.appointments.ProviderStore
import com.lifolo.appointments.QueueRules
import com.lifolo.appointments.Slot
import com.lifolo.common.DhakaTime
import com.lifolo.common.Sql
import com.lifolo.contracts.HospitalDirectory
import com.lifolo.demo.DemoSql.Companion.DAY
import com.lifolo.practice.Practice
import org.springframework.stereotype.Component

/**
 * Medical systems + new professionals in the demo world (backend/docs/PRACTICE.md §4), after the doctors part:
 *  - one approved doctor of each non-allopathic system (homeopathy, unani, ayurveda) — no "ডা." in their names;
 *  - one physiotherapist and one psychologist, each with an in-person chamber and an online (video) chamber (online
 *    only where demo online chambers are on — DEMO_ONLINE_CHAMBERS, R6; like the demo doctor's); the physio also visits
 *    homes;
 *  - a few appointments: the physio's patient today, the homeopath's tomorrow, and the demo patient's own request with
 *    the psychologist (private: the demo caregiver does not see it unless the patient shares it).
 * Everything belongs to `demo-x-…` background people or the demo patient, so the wipe removes it with them.
 */
@Component
class DemoPractice(
    private val sql: DemoSql,
    private val providers: ProviderStore,
    private val hospitals: HospitalDirectory,
    private val onlineSwitch: OnlineSwitch,
) : DemoSeedPart {
    override val step = 4

    /** after the doctors & prescriptions part (same step, so the panel's step count does not change) */
    override val order = 10
    override val label = "চিকিৎসা পদ্ধতি ও নতুন পেশাজীবী" // bn-ok

    private val jdbc get() = sql.jdbc

    companion object {
        private val ALL_DAYS = listOf(0, 1, 2, 3, 4, 5, 6)
        const val HOSPITAL = "dghs_22839"

        /** the keys of the background people (uid `demo-x-<key>`) */
        val SYSTEM_DOCTORS = mapOf(Practice.HOMEOPATHY to "pr-hom", Practice.UNANI to "pr-unani", Practice.AYURVEDA to "pr-ayur")
        const val PHYSIO = "pr-physio"
        const val PSYCHOLOGIST = "pr-psy"
    }

    private fun chamber(room: String, times: List<Slot>, fee: String, serialPhone: String): Ch {
        val h = hospitals.findHospital(HOSPITAL) ?: throw IllegalStateException("demo practice: hospital $HOSPITAL missing")
        return Ch(
            hospitalId = h.id, hospitalName = h.nameEn, hospitalNameBn = h.nameBn, district = h.district,
            upazilaId = h.upazilaId?.takeIf { it != 0 }, unionId = h.unionId?.takeIf { it != 0 }, room = room,
            days = times.flatMap { it.days }.distinct().sorted(), start = times[0].start, end = times[0].end,
            times = if (times.size > 1) times else null, noFee = false, fee = fee, appointments = true, serialPhone = serialPhone,
        )
    }

    private fun online(fee: String, bkash: String, note: String) = Ch(
        hospitalName = Ch.ONLINE_NAME, hospitalNameBn = Ch.ONLINE_NAME_BN, days = ALL_DAYS, start = "18:00", end = "21:00",
        schedule = "ভিডিও কলে পরামর্শ (ডেমো)", noFee = false, fee = fee, appointments = true, patientMin = 20, // bn-ok
        mode = Ch.ONLINE, bkashNumber = bkash, note = note,
    )

    private class Pro(
        val key: String,
        val type: String,
        val system: String?,
        val name: String,
        val degrees: String,
        val gender: String,
        val phone: String,
        val reg: String,
        val specialties: List<String>,
        val chambers: List<Ch>,
        val docs: List<String>,
        val checks: List<String>,
        val home: HomeVisit? = null,
    )

    override fun seed(ctx: DemoSeedContext) {
        val now = ctx.nowMs
        val today = DhakaTime.todayUtcMidnight(now)
        val gzp = DemoSql.GAZIPUR
        val withOnline = onlineSwitch.demoEnabled
        val altChecks = listOf("cert_no", "cert_body", "cert_name", "workplace")

        val pros = listOf(
            Pro(
                SYSTEM_DOCTORS.getValue(Practice.HOMEOPATHY), "doctor", Practice.HOMEOPATHY, "ডেমো হোমিও চিকিৎসক আনোয়ার হোসেন", "DHMS (ডেমো)", "male", // bn-ok
                "01200000221", "ডেমো-হোমিও-৪৪১", listOf("alt_general", "alt_skin_hair"), // bn-ok
                listOf(chamber("ডেমো হোমিও চেম্বার · রুম ২", listOf(Slot(ALL_DAYS, "17:00", "21:00")), "২০০ টাকা", "01200000221")), // bn-ok
                listOf("cert"), altChecks,
            ),
            Pro(
                SYSTEM_DOCTORS.getValue(Practice.UNANI), "doctor", Practice.UNANI, "ডেমো ইউনানি চিকিৎসক রফিকুল ইসলাম", "BUMS (ডেমো)", "male", // bn-ok
                "01200000222", "ডেমো-ইউনানি-১০৭", listOf("alt_general", "alt_digestive"), // bn-ok
                listOf(chamber("ডেমো ইউনানি চেম্বার · রুম ৩", listOf(Slot(listOf(6, 0, 1, 2, 3), "10:00", "13:00")), "৩০০ টাকা", "01200000222")), // bn-ok
                listOf("cert"), altChecks,
            ),
            Pro(
                SYSTEM_DOCTORS.getValue(Practice.AYURVEDA), "doctor", Practice.AYURVEDA, "ডেমো আয়ুর্বেদিক চিকিৎসক সুমিতা রায়", "BAMS (ডেমো)", "female", // bn-ok
                "01200000223", "ডেমো-আয়ুর্বেদ-২২", listOf("alt_general", "alt_joint_pain"), // bn-ok
                listOf(chamber("ডেমো আয়ুর্বেদ চেম্বার · রুম ৪", listOf(Slot(listOf(0, 2, 4), "15:00", "19:00")), "৩০০ টাকা", "01200000223")), // bn-ok
                listOf("cert"), altChecks,
            ),
            Pro(
                PHYSIO, Practice.PHYSIO, null, "ডেমো ফিজিওথেরাপিস্ট সাবরিনা ইসলাম", "BPT, MPT (ডেমো)", "female", // bn-ok
                "01200000224", "ডেমো-BPT-৫৫", listOf("physio_back_neck", "physio_stroke"), // bn-ok
                listOfNotNull(
                    chamber("ডেমো ফিজিও সেন্টার · রুম ৭", listOf(Slot(ALL_DAYS, "09:00", "14:00")), "৬০০ টাকা", "01200000224"), // bn-ok
                    if (withOnline) online("৫০০ টাকা", "01200000225", "ভিডিওতে ব্যায়াম দেখিয়ে দেওয়া হবে — ঢিলেঢালা কাপড় পরে থাকুন। (ডেমো)") else null, // bn-ok
                ),
                listOf("cert", "member"), listOf("cert_name", "cert_degree", "workplace"),
                home = HomeVisit(
                    enabled = true, radiusKm = 10, services = listOf("physio_home_exercise", "physio_home_stroke", "physio_home_elderly"),
                    phone = "01200000224", schedule = "বিকেলে বাসায় যাই (ডেমো)", times = listOf(Slot(listOf(6, 1, 3), "16:00", "19:00")), // bn-ok
                    fee = "১০০০ টাকা", // bn-ok
                ),
            ),
            Pro(
                PSYCHOLOGIST, Practice.PSYCHOLOGIST, null, "ডেমো কাউন্সেলর নাদিয়া রহমান", "MS, MPhil (ক্লিনিক্যাল সাইকোলজি) — ডেমো", "female", // bn-ok
                "01200000226", "ডেমো-BCPS-৯", listOf("psy_depression_anxiety", "psy_couple_family"), // bn-ok
                listOfNotNull(
                    chamber("ডেমো কাউন্সেলিং কক্ষ · রুম ৯", listOf(Slot(listOf(6, 0, 1, 2, 3, 4), "15:00", "20:00")), "১০০০ টাকা", "01200000226"), // bn-ok
                    if (withOnline) online("৮০০ টাকা", "01200000227", "শান্ত, আলাদা একটা জায়গা থেকে কলে আসুন। (ডেমো)") else null, // bn-ok
                ),
                listOf("cert", "member"), listOf("cert_name", "cert_degree", "workplace"),
            ),
        )

        val uids = HashMap<String, String>()
        for ((i, p) in pros.withIndex()) {
            val uid = ctx.extraUser(p.key, mapOf("name" to p.name, "phone" to p.phone, "place_name" to gzp, "lang" to "bn", "gender" to p.gender))
            uids[p.key] = uid
            val created = now - (90 - i * 10) * DAY
            sql.insert(
                "badges",
                linkedMapOf<String, Any?>(
                    "uid" to uid, "type" to p.type, "system" to p.system, "reg_no" to p.reg, "workplace" to "${p.chambers[0].hospitalNameBn} (ডেমো)", // bn-ok
                    "name" to p.name, "email" to "", "status" to "approved", "docs_have" to sql.texts(p.docs), "created_at" to sql.ts(created),
                    "reviewed_at" to sql.ts(created + DAY), "reviewed_by" to "demo-seed", "verified_by" to "demo-seed", "verified_by_email" to "",
                    "verified_at" to sql.ts(created + DAY), "verification_checks" to sql.texts(p.checks),
                ),
            )
            sql.insert(
                "providers",
                linkedMapOf(
                    "uid" to uid, "type" to p.type, "system" to p.system, "name" to p.name, "listed" to true, "appointments" to true, "active" to true,
                    "specialties" to sql.texts(p.specialties), "degrees" to p.degrees, "gender" to p.gender, "legacy_chamber" to false,
                    "updated_at" to sql.ts(created + DAY),
                ),
            )
            providers.saveChambers(uid, p.chambers)
            if (p.home != null) providers.saveHome(uid, p.home, created + DAY)
        }
        // the physio's home visits are found near the demo centre (homecare.js: GPS of the professional)
        val near = ctx.near(1.2, -0.8)
        jdbc.update(
            "UPDATE user_profiles SET lat = ?, lng = ?, geohash = ?, location_source = 'gps', place_name = ? WHERE uid = ?",
            near.lat, near.lng, near.geohash, gzp, uids.getValue(PHYSIO),
        )

        // ---------- appointments ----------
        val pat = ctx.user("patient")
        val bgPat = ctx.extraUser("pr-pat-jahid", mapOf("name" to "ডেমো রোগী জাহিদ হাসান", "phone" to "01200000321", "place_name" to gzp, "lang" to "bn", "gender" to "male")) // bn-ok
        val bgPat2 = ctx.extraUser("pr-pat-rokeya", mapOf("name" to "ডেমো রোগী রোকেয়া বেগম", "phone" to "01200000322", "place_name" to gzp, "lang" to "bn", "gender" to "female")) // bn-ok
        val patName = jdbc.queryForList("SELECT name FROM user_profiles WHERE uid = ?", String::class.java, pat.uid).firstOrNull()?.takeIf { it.isNotEmpty() } ?: pat.name
        val byKey = pros.associateBy { it.key }
        var n = 0
        fun nextOpen(ch: Ch, from: Long): Long {
            var d = from
            repeat(21) {
                if (CareRules.chamberOpenOn(ch, d)) return d
                d += DAY
            }
            return from
        }
        fun appt(patientUid: String, patientName: String, key: String, day: Long, status: String, note: String, serial: Int? = null) {
            val p = byKey.getValue(key)
            val ch = p.chambers[0]
            val start = CareRules.chamberTimesOn(ch, day).minByOrNull { it.start }?.start ?: ch.start ?: ""
            sql.insert(
                "appointments",
                linkedMapOf<String, Any?>(
                    "id" to "demo-pr-appt-${++n}", "patient_uid" to patientUid, "patient_name" to patientName, "provider_uid" to uids.getValue(key),
                    "provider_name" to p.name, "type" to p.type, "chamber" to ch.hospitalName, "chamber_bn" to ch.hospitalNameBn, "chamber_key" to ch.key,
                    "start_time" to start, "room" to ch.room, "schedule" to ch.schedule, "visit_date" to Sql.date(DhakaTime.fromUtcMidnightMs(day)),
                    "note" to note, "status" to status, "provider_note" to "", "serial" to serial, "created_at" to sql.ts(now - 5 * 3600 * 1000),
                    "updated_at" to if (status == "pending") null else sql.ts(now - 3 * 3600 * 1000),
                ),
            )
            if (serial != null) {
                sql.insert(
                    "serial_counters",
                    linkedMapOf(
                        "provider_uid" to uids.getValue(key), "visit_date" to Sql.date(DhakaTime.fromUtcMidnightMs(day)),
                        "queue_key" to QueueRules.keyOf(ch.key), "n" to serial, "at" to sql.ts(now - 3600 * 1000),
                    ),
                    "(provider_uid, visit_date, queue_key) DO UPDATE SET n = GREATEST(serial_counters.n, EXCLUDED.n), at = EXCLUDED.at",
                )
            }
        }
        // the physio's patient today (the physio's chamber is open every day)
        appt(bgPat, "ডেমো রোগী জাহিদ হাসান", PHYSIO, today, "confirmed", "কোমর ব্যথা, নিচু হতে পারি না", serial = 1) // bn-ok
        // the homeopath's patient tomorrow
        val hom = SYSTEM_DOCTORS.getValue(Practice.HOMEOPATHY)
        appt(bgPat2, "ডেমো রোগী রোকেয়া বেগম", hom, nextOpen(byKey.getValue(hom).chambers[0], today + DAY), "confirmed", "অনেক দিনের অ্যালার্জি", serial = 1) // bn-ok
        // the demo patient's own request with the psychologist (not today — the tester books today himself)
        val psy = byKey.getValue(PSYCHOLOGIST)
        appt(pat.uid, patName, PSYCHOLOGIST, nextOpen(psy.chambers[0], today + 2 * DAY), "pending", "কয়েক মাস ধরে ঘুম কম, দুশ্চিন্তা হয়") // bn-ok
    }
}
