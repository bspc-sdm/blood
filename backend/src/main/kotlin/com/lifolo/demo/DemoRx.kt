package com.lifolo.demo

import com.lifolo.admin.kernel.DemoSeedContext
import com.lifolo.admin.kernel.DemoSeedPart
import com.lifolo.appointments.CareRules
import com.lifolo.appointments.Ch
import com.lifolo.appointments.ProviderStore
import com.lifolo.appointments.QueueRules
import com.lifolo.appointments.Slot
import com.lifolo.common.DhakaTime
import com.lifolo.common.Phones
import com.lifolo.common.Sql
import com.lifolo.contracts.HospitalDirectory
import com.lifolo.contracts.ProviderAssistants
import com.lifolo.contracts.RxDispensing
import com.lifolo.contracts.RxMedicine
import com.lifolo.demo.DemoSql.Companion.DAY
import com.lifolo.demo.DemoSql.Companion.MIN
import com.lifolo.lab.LabCatalog
import com.lifolo.lab.LabPrices
import com.lifolo.lab.LabStore
import com.lifolo.lab.LabTests
import com.lifolo.pharmacy.RxMath
import com.lifolo.pharmacy.StockSnap
import com.lifolo.pharmacy.StockStore
import com.lifolo.rx.RxActions
import com.lifolo.rx.RxRules
import org.springframework.stereotype.Component
import java.security.SecureRandom

/**
 * demo/rx.js — doctors, appointments/serials, the live queue, prescriptions, the assistant, the pharmacy and lab work:
 *  - the demo doctor: approved badge (DEMO-A-10001), two chambers (Shaheed Tajuddin Ahmad Medical College Hospital,
 *    every day 10–13 and 16–01 — the evening session runs past midnight, so "today" can be booked at night too;
 *    Kaliakair UHC on Fridays) and an online (video) chamber every day 13–16 (Fridays 13–14) with a bKash number, the
 *    assistant's email, paid report reviews; online appointments in each fee state (unpaid, claimed, paid);
 *  - six more doctors (one awaiting verification, one with a badge-correction request, one emergency-duty doctor so
 *    someone is "in the chamber now" at any Dhaka hour);
 *  - today's serials (confirmed 1–4, pending, cancelled), the queue at number 2, patient 4 asked for 20 minutes;
 *  - prescriptions (the patient's, a paper patient's, a cancelled one), pharmacy orders, lab orders in every state
 *    with the price estimate of the services part's lists, report reviews.
 * Codes in the guide are fixed ([CODES]).
 */
@Component
class DemoRx(
    private val sql: DemoSql,
    private val providers: ProviderStore,
    private val assistants: ProviderAssistants,
    private val hospitals: HospitalDirectory,
    private val rxActions: RxActions,
    private val labStore: LabStore,
    private val labCatalog: LabCatalog,
    private val stock: StockStore,
    private val onlineSwitch: com.lifolo.appointments.OnlineSwitch,
) : DemoSeedPart {
    override val step = 4
    override val label = "ডাক্তার ও প্রেসক্রিপশন"

    private val jdbc get() = sql.jdbc
    private val random = SecureRandom()

    companion object {
        /** the codes written in the guide (prescription code rule: 10 characters without I, O, 0, 1) */
        val CODES = mapOf("A" to "DEMXRXA222", "B" to "DEMXRXB333", "C" to "DEMXRXC444", "K" to "DEMXRXK777", "PAPER" to "DEMXPAPER6")
        const val SHOP_CODE = "DEMQKP"
        private const val CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private const val SADAR = 320
        private val ALL_DAYS = listOf(6, 0, 1, 2, 3, 4, 5)
    }

    private fun randCode(): String = "DEMX" + (1..6).joinToString("") { CODE_CHARS[random.nextInt(256) % CODE_CHARS.length].toString() }

    /** a chamber from the hospital list — the way provider_save stores it */
    private fun chamber(
        hospitalId: String,
        room: String = "",
        times: List<Slot>,
        schedule: String = "",
        fee: String = "",
        appointments: Boolean = true,
        serialPhone: String = "",
    ): Ch {
        val h = hospitals.findHospital(hospitalId) ?: throw IllegalStateException("demo rx: hospital $hospitalId missing")
        val days = times.flatMap { it.days }.distinct().sortedBy { it.toString() }
        return Ch(
            hospitalId = h.id, hospitalName = h.nameEn, hospitalNameBn = h.nameBn, district = h.district, upazilaId = h.upazilaId?.takeIf { it != 0 },
            unionId = h.unionId?.takeIf { it != 0 }, room = room, days = days, start = times[0].start, end = times[0].end,
            times = if (times.size > 1) times else null, schedule = schedule, noFee = false, fee = fee, appointments = appointments,
            serialPhone = serialPhone,
        )
    }

    private fun one(days: List<Int>, start: String, end: String) = listOf(Slot(days, start, end))

    /** a Dhaka day (UTC midnight) at this time — ms */
    private fun at(dateMid: Long, hhmm: String): Long = dateMid + (hhmm.substring(0, 2).toLong() * 60 + hhmm.substring(3, 5).toLong()) * MIN - 6 * 3600 * 1000

    private fun nextOpen(ch: Ch, from: Long): Long {
        var d = from
        repeat(21) {
            if (CareRules.chamberOpenOn(ch, d)) return d
            d += DAY
        }
        return from
    }

    private fun prevOpen(ch: Ch, from: Long): Long {
        var d = from
        repeat(21) {
            if (CareRules.chamberOpenOn(ch, d)) return d
            d -= DAY
        }
        return from
    }

    private fun startOn(ch: Ch, d: Long): String =
        CareRules.chamberTimesOn(ch, d).sortedBy { it.start }.firstOrNull()?.start ?: ch.start ?: ""

    private fun date(ms: Long?) = ms?.let { Sql.date(DhakaTime.fromUtcMidnightMs(it)) }

    private class Person(val uid: String?, val name: String, val age: Int, val gender: String, val phone: String = "")

    private class Doc(val uid: String, val name: String, val ch: List<Ch>)

    private class Rx(val id: String, val code: String, val patient: Person, val medicines: List<RxMedicine>)

    private fun med(name: String, form: String, strength: String, dose: String, days: Int, timing: String = "", note: String = "") =
        RxMedicine(0, name, form, strength, dose, timing, days, note)

    override fun seed(ctx: DemoSeedContext) {
        val now = ctx.nowMs
        val today = RxRules.dhakaToday(now)
        val gzp = DemoSql.GAZIPUR
        val doc = ctx.user("doctor")
        val asst = ctx.user("assistant")
        val pat = ctx.user("patient")
        val shopU = ctx.user("pharmacy")
        val labU = ctx.user("lab")

        // ---------------------------------------------------------------
        // what the earlier parts wrote (pharmacy/lab names, the patient's phone)
        // ---------------------------------------------------------------
        /** own profile (when the blood part wrote it: only the empty fields) */
        fun profile(uid: String, name: String, phone: String): Map<String, Any?> {
            jdbc.update("INSERT INTO user_profiles (uid) VALUES (?) ON CONFLICT (uid) DO NOTHING", uid)
            jdbc.update(
                """
                UPDATE user_profiles SET name = coalesce(nullif(name, ''), ?), phone = coalesce(nullif(phone, ''), ?),
                  place_name = coalesce(nullif(place_name, ''), ?), lang = coalesce(lang, 'bn')
                WHERE uid = ?
                """.trimIndent(),
                name, phone, gzp, uid,
            )
            return jdbc.queryForList("SELECT name, phone FROM user_profiles WHERE uid = ?", uid).first()
        }
        val patProfile = profile(pat.uid, pat.name, "01200000101")
        profile(doc.uid, doc.name, "01200000201")
        profile(asst.uid, asst.name, "01200000203")
        val patPhone = Phones.localBd(Phones.normalizeBd(patProfile["phone"] as? String) ?: "8801200000101")

        // pharmacy: when missing an approved listing; else only what prescriptions need (shop code, online sales)
        val shop = jdbc.queryForList("SELECT name, shop_code, online_enabled, online_fee, status FROM pharmacies WHERE uid = ?", shopU.uid).firstOrNull()
        if (shop == null) {
            val loc = ctx.near(0.4, 0.3)
            sql.insert(
                "pharmacies",
                linkedMapOf(
                    "uid" to shopU.uid, "name" to "ডেমো লাইফ ফার্মেসি", "license_no" to "DEMO-DL-1501", "phone" to "01200000150", "district" to gzp,
                    "upazila_id" to SADAR, "address" to "ডেমো ভবন, শিববাড়ি মোড়, জয়দেবপুর, গাজীপুর", "lat" to loc.lat, "lng" to loc.lng, "open24" to true,
                    "open_at" to "", "close_at" to "", "closed_days" to sql.ints(emptyList(), "smallint"), "delivery" to true,
                    "delivery_note" to "শহরের ভেতরে ৩০ টাকা", "discount" to 5, "status" to "approved", "created_at" to sql.ts(now - 90 * DAY),
                    "updated_at" to sql.ts(now - 90 * DAY), "reviewed_at" to sql.ts(now - 89 * DAY),
                ),
            )
        }
        val shopName = (shop?.get("name") as? String) ?: "ডেমো লাইফ ফার্মেসি"
        if (shop == null || shop["shop_code"] == null) jdbc.update("UPDATE pharmacies SET shop_code = ? WHERE uid = ?", SHOP_CODE, shopU.uid)
        var deliveryFee = (shop?.get("online_fee") as? String) ?: ""
        if (shop == null || shop["online_enabled"] != true) {
            jdbc.update(
                "UPDATE pharmacies SET online_enabled = TRUE, online_radius_km = 5, online_fee = ?, online_note = ?, online_updated_at = ? WHERE uid = ?",
                "৩০ টাকা", "সকাল ১০টা – রাত ১০টা", sql.ts(now - 30 * DAY), shopU.uid,
            )
            deliveryFee = "৩০ টাকা"
        }
        if (shop != null && shop["status"] != "approved") jdbc.update("UPDATE pharmacies SET status = 'approved' WHERE uid = ?", shopU.uid)

        // lab
        val lab = jdbc.queryForList("SELECT name, status, home_collection FROM labs WHERE uid = ?", labU.uid).firstOrNull()
        if (lab == null) {
            sql.insert(
                "labs",
                linkedMapOf(
                    "uid" to labU.uid, "name" to "ডেমো সিটি ডায়াগনস্টিক সেন্টার", "facility_id" to null, "district" to gzp, "upazila_id" to SADAR,
                    "address" to "ডেমো টাওয়ার, হাসপাতাল রোড, জয়দেবপুর, গাজীপুর", "license_no" to "DEMO-HSM-2201", "phone" to "01200000160",
                    "hours" to "সকাল ৮টা – রাত ১০টা", "home_collection" to true, "status" to "approved", "created_at" to sql.ts(now - 90 * DAY),
                    "updated_at" to sql.ts(now - 90 * DAY), "reviewed_at" to sql.ts(now - 89 * DAY),
                ),
            )
        } else if (lab["status"] != "approved") {
            jdbc.update("UPDATE labs SET status = 'approved' WHERE uid = ?", labU.uid)
        }
        val labName = (lab?.get("name") as? String) ?: "ডেমো সিটি ডায়াগনস্টিক সেন্টার"

        // ---------------------------------------------------------------
        // doctors
        // ---------------------------------------------------------------
        val docName = doc.name
        val docDegrees = "এমবিবিএস, এফসিপিএস (মেডিসিন) — ডেমো" // like the services part's prescriptions
        val docReg = "DEMO-A-10001"
        // any day morning and evening; the evening session runs past midnight (until 1 am) so tonight's serial can be taken
        val ch1 = chamber(
            "dghs_22839", room = "ডেমো চেম্বার · বহির্বিভাগ ৩১২",
            times = listOf(Slot(ALL_DAYS, "10:00", "13:00"), Slot(ALL_DAYS, "16:00", "01:00")),
            fee = "৫০০ টাকা", serialPhone = "01200000202", schedule = "ডেমো চেম্বার — প্রতিদিন খোলা",
        )
        val ch2 = chamber("dghs_137", room = "ডেমো চেম্বার · রুম ৫", times = one(listOf(5), "14:00", "15:30"), fee = "৪০০ টাকা", schedule = "শুক্রবার শুধু ফলো-আপ (ডেমো)")
        // the online (video) chamber in the gap between the morning and evening sessions (Friday: before Kaliakair)
        val chOnline = Ch(
            hospitalName = Ch.ONLINE_NAME, hospitalNameBn = Ch.ONLINE_NAME_BN, days = ALL_DAYS.sorted(), start = "13:00", end = "16:00",
            times = listOf(Slot(ALL_DAYS.filter { it != 5 }, "13:00", "16:00"), Slot(listOf(5), "13:00", "14:00")),
            schedule = "ভিডিও কলে পরামর্শ (ডেমো)", noFee = false, fee = "৪০০ টাকা", appointments = true, patientMin = 10, // bn-ok
            mode = Ch.ONLINE, bkashNumber = "01200000204", note = "বুকিংয়ের পর ফি বিকাশে পাঠিয়ে \"টাকা পাঠিয়েছি\" চাপুন। রিপোর্টের ছবি তৈরি রাখুন। (ডেমো)", // bn-ok
        )
        // R6: only while the online_chamber switch is on for demo accounts (pilot + "ডেমো অ্যাকাউন্টে চালু", or on)
        val withOnline = onlineSwitch.demoEnabled
        val docChambers = if (withOnline) listOf(ch1, ch2, chOnline) else listOf(ch1, ch2)
        fun verification(t: Long) = linkedMapOf<String, Any?>(
            "verified_by" to "demo-seed", "verified_by_email" to "", "verified_at" to sql.ts(t),
            "verification_checks" to sql.texts(listOf("bmdc_found", "bmdc_name", "bmdc_active", "workplace")),
        )
        sql.upsert(
            "badges", listOf("uid"),
            linkedMapOf<String, Any?>(
                "uid" to doc.uid, "type" to "doctor", "status" to "approved", "reg_no" to docReg,
                "workplace" to "শহীদ তাজ উদ্দীন আহমদ মেডিকেল কলেজ হাসপাতাল (ডেমো)", "name" to docName, "email" to doc.email,
                "docs_have" to sql.texts(emptyList()), "created_at" to sql.ts(now - 120 * DAY), "reviewed_at" to sql.ts(now - 118 * DAY),
                "reviewed_by" to "demo-seed",
            ).apply { putAll(verification(now - 118 * DAY)) },
        )
        sql.upsert(
            "providers", listOf("uid"),
            linkedMapOf(
                "uid" to doc.uid, "type" to "doctor", "name" to docName, "listed" to true, "appointments" to true, "active" to true,
                "specialties" to sql.texts(listOf("medicine", "endocrinology")), "degrees" to docDegrees, "gender" to "male", "legacy_chamber" to false,
                "review_enabled" to true, "review_no_fee" to false, "review_fee" to "৩০০ টাকা", "review_pay_number" to "01200000202",
                "review_pay_methods" to sql.texts(listOf("bkash", "nagad")), "updated_at" to sql.ts(now - 30 * DAY),
            ),
        )
        providers.saveChambers(doc.uid, docChambers)
        assistants.set(doc.uid, listOf(asst.email.lowercase()))

        // other doctors (a fuller directory)
        class Other(
            val key: String, val name: String, val gender: String, val sp: List<String>, val deg: String, val phone: String, val ch: List<Ch>,
            val noAppt: Boolean = false, val pending: Boolean = false, val change: Pair<String, String>? = null,
        )
        val others = listOf(
            Other(
                "rx-doc-nasrin", "ডা. ডেমো নাসরিন আক্তার", "female", listOf("gynecology"), "MBBS, FCPS (Gynae & Obs)", "01200000211",
                listOf(chamber("dghs_22839", room = "ডেমো চেম্বার · গাইনি বহির্বিভাগ", times = one(listOf(6, 1, 3), "15:00", "17:00"), fee = "৮০০ টাকা", serialPhone = "01200000211")),
            ),
            Other(
                "rx-doc-kamrul", "ডা. ডেমো কামরুল ইসলাম", "male", listOf("pediatrics"), "MBBS, DCH (Child Health)", "01200000212",
                listOf(chamber("dghs_22839", room = "ডেমো চেম্বার · শিশু বহির্বিভাগ", times = one(listOf(0, 2, 4), "15:00", "18:00"), fee = "৬০০ টাকা")),
                change = "DEMO-A-55555" to "ডেমো শিশু চেম্বার, শিববাড়ি (সংশোধন)",
            ),
            Other(
                "rx-doc-mahbub", "ডা. ডেমো মাহবুব আলম", "male", listOf("cardiology", "medicine"), "MBBS, MD (Cardiology)", "01200000213",
                listOf(chamber("dghs_147", room = "ডেমো চেম্বার · হৃদরোগ কক্ষ", times = one(listOf(6, 0, 1, 2, 3), "16:00", "20:00"), fee = "১০০০ টাকা", serialPhone = "01200000213")),
            ),
            Other(
                "rx-doc-farzana", "ডা. ডেমো ফারজানা হক", "female", listOf("dermatology"), "MBBS, DDV (Skin & VD)", "01200000214",
                listOf(chamber("dghs_22839", room = "ডেমো চেম্বার · চর্ম বহির্বিভাগ", times = one(listOf(6, 0, 1, 2, 3, 4), "10:00", "12:00"), fee = "৭০০ টাকা", serialPhone = "01200000214", appointments = false)),
                noAppt = true,
            ),
            Other(
                "rx-doc-tanvir", "ডা. ডেমো তানভীর রহমান", "male", listOf("orthopedics"), "MBBS, D-Ortho", "01200000215",
                listOf(chamber("dghs_22839", room = "ডেমো চেম্বার · হাড় ও জোড়া", times = one(listOf(0, 2), "18:00", "21:00"), fee = "৭০০ টাকা")),
                pending = true,
            ),
            // 2.33.0 (doctor list filters): the emergency duty doctor — sits in Dr Hasan's gaps (01–10, 13–16), so at any
            // Dhaka hour someone is "in the chamber now"; fee 300 ("up to ৳300"), Kaliakair, female
            Other(
                "rx-doc-rubina", "ডা. ডেমো রুবিনা ইয়াসমিন", "female", listOf("general"), "MBBS (জরুরি বিভাগ)", "01200000216",
                listOf(
                    chamber(
                        "dghs_137", room = "ডেমো চেম্বার · জরুরি বিভাগ", fee = "৩০০ টাকা", serialPhone = "01200000216", schedule = "জরুরি বিভাগের ডিউটি (ডেমো)",
                        times = listOf(Slot(ALL_DAYS, "01:00", "10:00"), Slot(ALL_DAYS, "13:00", "16:00")),
                    ),
                ),
            ),
        )
        val docs = HashMap<String, Doc>()
        others.forEachIndexed { idx, o ->
            val uid = ctx.extraUser(o.key, mapOf("name" to o.name, "phone" to o.phone, "place_name" to gzp, "lang" to "bn", "gender" to o.gender))
            docs[o.key] = Doc(uid, o.name, o.ch)
            val reg = "DEMO-A-${20000 + idx * 1111}"
            val email = "${o.key.removePrefix("rx-doc-")}.doctor@lifolo-demo.test"
            val created = if (o.pending) now - 2 * DAY else now - (200 - idx * 20) * DAY
            val badge = linkedMapOf<String, Any?>(
                "uid" to uid, "type" to "doctor", "reg_no" to reg, "workplace" to "${o.ch[0].hospitalNameBn} (ডেমো)", "name" to o.name, "email" to email,
                "docs_have" to sql.texts(emptyList()), "created_at" to sql.ts(created),
            )
            if (o.pending) {
                badge["status"] = "pending"
            } else {
                badge["status"] = "approved"
                badge["reviewed_at"] = sql.ts(created + DAY)
                badge["reviewed_by"] = "demo-seed"
                badge.putAll(verification(created + DAY))
            }
            if (o.change != null) {
                badge["change_type"] = "doctor"
                badge["change_reg_no"] = o.change.first
                badge["change_workplace"] = o.change.second
                badge["change_name"] = o.name
                badge["change_at"] = sql.ts(now - 6 * 3600 * 1000)
            }
            sql.insert("badges", badge)
            sql.insert(
                "providers",
                linkedMapOf(
                    "uid" to uid, "type" to "doctor", "name" to o.name, "listed" to true, "appointments" to !o.noAppt, "active" to !o.noAppt,
                    "specialties" to sql.texts(o.sp), "degrees" to o.deg, "gender" to o.gender, "legacy_chamber" to false, "updated_at" to sql.ts(created + DAY),
                ),
            )
            providers.saveChambers(uid, o.ch)
        }

        // ---------------------------------------------------------------
        // patients (background, no login)
        // ---------------------------------------------------------------
        val pats = linkedMapOf(
            "salma" to listOf("ডেমো রোগী সালমা খাতুন", 38, "female", "01200000311"),
            "karim" to listOf("ডেমো রোগী আব্দুল করিম", 58, "male", "01200000312"),
            "nasima" to listOf("ডেমো রোগী নাসিমা বেগম", 45, "female", "01200000313"),
            "habib" to listOf("ডেমো রোগী হাবিবুর রহমান", 33, "male", "01200000314"),
            "shirin" to listOf("ডেমো রোগী শিরিন আক্তার", 29, "female", "01200000315"),
            "fatema" to listOf("ডেমো রোগী ফাতেমা বেগম", 61, "female", "01200000316"),
            "moriom" to listOf("ডেমো রোগী মরিয়ম নেসা", 52, "female", "01200000317"),
            "rashed" to listOf("ডেমো রোগী রাশেদুল ইসলাম", 49, "male", "01200000319"),
            "kamal" to listOf("ডেমো রোগী কামাল হোসেন", 40, "male", "01200000318"),
        )
        val bp = HashMap<String, Person>()
        for ((k, v) in pats) {
            val (name, age, gender, phone) = v
            val uid = ctx.extraUser("rx-pat-$k", mapOf("name" to name, "phone" to phone, "place_name" to gzp, "lang" to "bn", "gender" to gender))
            bp[k] = Person(uid, name as String, age as Int, gender as String, phone as String)
        }
        val me = Person(pat.uid, ((patProfile["name"] as? String)?.takeIf { it.isNotEmpty() } ?: pat.name), 45, "male", patPhone)

        // ---------------------------------------------------------------
        // appointments
        // ---------------------------------------------------------------
        val serialMax = LinkedHashMap<Triple<String, Long, String>, Int>()
        var apptN = 0
        class A(
            val status: String, val note: String, val createdAt: Long, val serial: Int? = null, val providerNote: String = "",
            val cancelledBy: String? = null, val updatedAt: Long? = null, val extra: Map<String, Any?> = emptyMap(),
        )
        fun appt(p: Person, prov: Doc, ch: Ch, day: Long, o: A): String {
            val id = "demo-rx-appt-${(++apptN).toString().padStart(2, '0')}"
            val chamberKey = ch.key
            if (o.serial != null) {
                val sk = Triple(prov.uid, day, QueueRules.keyOf(chamberKey))
                serialMax[sk] = maxOf(serialMax[sk] ?: 0, o.serial)
            }
            val row = linkedMapOf<String, Any?>(
                "id" to id, "patient_uid" to p.uid, "patient_name" to p.name, "provider_uid" to prov.uid, "provider_name" to prov.name,
                "type" to "doctor", "chamber" to ch.hospitalName, "chamber_bn" to ch.hospitalNameBn, "chamber_key" to chamberKey,
                "start_time" to startOn(ch, day), "room" to ch.room, "schedule" to ch.schedule, "visit_date" to date(day), "note" to o.note,
                "status" to o.status, "provider_note" to o.providerNote, "serial" to o.serial, "cancelled_by" to o.cancelledBy,
                "created_at" to sql.ts(o.createdAt),
            )
            if (ch.online) {
                row["online"] = true
                row["pay_state"] = "unpaid"
                row["bkash_number"] = ch.bkashNumber
                row["fee"] = ch.fee
                row["online_note"] = ch.note
            }
            row.putAll(o.extra)
            if (o.status != "pending") row["updated_at"] = sql.ts(o.updatedAt ?: (o.createdAt + 2 * 3600 * 1000))
            sql.insert("appointments", row)
            return id
        }
        val d = Doc(doc.uid, docName, docChambers)
        val todayCh = if (CareRules.chamberOpenOn(ch1, today)) ch1 else ch2
        val todayCi = if (todayCh === ch1) 0 else 1

        // today's serials (demo doctor)
        val tSalma = appt(bp["salma"]!!, d, todayCh, today, A("confirmed", "থাইরয়েডের রিপোর্ট দেখাতে চাই, ক্লান্ত লাগে", now - 2 * DAY, serial = 1))
        val tKarim = appt(bp["karim"]!!, d, todayCh, today, A("confirmed", "প্রেসার বেশি থাকছে, মাথা ভার লাগে", now - 2 * DAY + 3 * 3600 * 1000, serial = 2))
        // the demo patient has nothing with this doctor today — the owner books himself (the patient's whole journey)
        appt(bp["rashed"]!!, d, todayCh, today, A("confirmed", "ডায়াবেটিসের ফলো-আপ, রিপোর্ট দেখাতে চাই", now - DAY, serial = 3, providerNote = "আগের রিপোর্ট সাথে আনবেন"))
        // number 4 asked for time — the doctor/assistant presses "OK" or "come later" (queue.js)
        appt(
            bp["moriom"]!!, d, todayCh, today,
            A(
                "confirmed", "হাঁটুতে ব্যথা, ঘুম কম হয়", now - DAY + 3600 * 1000, serial = 4,
                extra = mapOf("late_min" to 20, "late_note" to "জ্যামে আটকে আছি", "late_at" to sql.ts(now - 5 * MIN), "late_status" to "asked", "late_count" to 1),
            ),
        )
        appt(bp["habib"]!!, d, todayCh, today, A("pending", "তিন দিন ধরে জ্বর আর গা ব্যথা", now - 2 * 3600 * 1000))
        appt(bp["shirin"]!!, d, todayCh, today, A("cancelled", "মাথাব্যথা", now - DAY, updatedAt = now - 5 * 3600 * 1000))
        // tomorrow / later
        val nextCh1 = nextOpen(ch1, today + DAY)
        appt(bp["fatema"]!!, d, ch1, nextCh1, A("pending", "ডায়াবেটিস, পায়ে জ্বালাপোড়া", now - 5 * 3600 * 1000))
        val later = nextOpen(ch1, nextCh1 + DAY)
        appt(bp["nasima"]!!, d, ch1, later, A("confirmed", "রিপোর্ট দেখাতে (আলট্রাসনো)", now - 6 * 3600 * 1000, serial = 1, providerNote = "খালি পেটে আসবেন না"))
        // earlier ones (patient list and prescriptions)
        val d2 = prevOpen(ch1, today - 2 * DAY)
        val aNasima = appt(bp["nasima"]!!, d, ch1, d2, A("confirmed", "পেটে ব্যথা আর গ্যাস, বমি ভাব", d2 - 2 * DAY, serial = 2))
        val dYest = prevOpen(ch1, today - DAY)
        appt(bp["kamal"]!!, d, ch1, dYest, A("declined", "চেকআপ", dYest - DAY, providerNote = "সেদিন সিরিয়াল পূর্ণ, অন্য দিন নিন"))
        val d7 = prevOpen(ch1, today - 7 * DAY)
        val aSalmaOld = appt(bp["salma"]!!, d, ch1, d7, A("completed", "গলা ফোলা, ওজন বাড়ছে", d7 - 2 * DAY, serial = 5))
        val d20 = prevOpen(ch1, today - 20 * DAY)
        val aMe20 = appt(me, d, ch1, d20, A("completed", "সুগার বেশি, প্রেসারও বাড়ছে", d20 - 2 * DAY, serial = 2))
        val d75 = prevOpen(ch1, today - 75 * DAY)
        val aMe75 = appt(me, d, ch1, d75, A("completed", "প্রস্রাব বেশি হয়, খুব পিপাসা লাগে", d75 - 3 * DAY, serial = 4))
        val d10 = prevOpen(ch1, today - 10 * DAY)
        appt(bp["karim"]!!, d, ch1, d10, A("completed", "প্রেসারের ওষুধ শেষ", d10 - DAY, serial = 3))

        // the demo patient with other doctors: confirmed and pending (ahead), unanswered, declined, cancelled
        val mah = docs.getValue("rx-doc-mahbub")
        val mDate = nextOpen(mah.ch[0], today + 3 * DAY)
        appt(me, mah, mah.ch[0], mDate, A("confirmed", "বুকে চাপ লাগে, সিঁড়ি ভাঙলে হাঁপিয়ে যাই", now - 20 * 3600 * 1000, serial = 2, providerNote = "ইসিজি রিপোর্ট সাথে আনবেন"))
        val mOld = prevOpen(mah.ch[0], today - 40 * DAY)
        appt(me, mah, mah.ch[0], mOld, A("expired", "ইসিজি রিপোর্ট দেখাতে", mOld - 3 * DAY, updatedAt = mOld + DAY))
        val kam = docs.getValue("rx-doc-kamrul")
        appt(me, kam, kam.ch[0], nextOpen(kam.ch[0], today + 2 * DAY), A("pending", "ছেলের জ্বর আর কাশি (ছেলের জন্য)", now - 3 * 3600 * 1000))
        val kOld = prevOpen(kam.ch[0], today - 12 * DAY)
        appt(me, kam, kam.ch[0], kOld, A("declined", "ছেলের জ্বর আর কাশি (ছেলের জন্য)", kOld - 2 * DAY, providerNote = "সেদিন সিরিয়াল পূর্ণ, অন্য দিন নিন"))
        val nas = docs.getValue("rx-doc-nasrin")
        val nOld = prevOpen(nas.ch[0], today - 30 * DAY)
        appt(me, nas, nas.ch[0], nOld, A("cancelled", "স্ত্রীর নিয়মিত চেকআপ", nOld - 4 * DAY, cancelledBy = "system", providerNote = "ডাক্তার চেম্বার বা বার বদলেছেন"))
        // a few bookings with other doctors (their lists are not empty)
        appt(bp["shirin"]!!, nas, nas.ch[0], nextOpen(nas.ch[0], today + DAY), A("confirmed", "গর্ভকালীন চেকআপ", now - 8 * 3600 * 1000, serial = 1))
        appt(bp["habib"]!!, mah, mah.ch[0], prevOpen(mah.ch[0], today - 9 * DAY), A("completed", "বুকে ব্যথা", now - 12 * DAY, serial = 2))

        // online (video) chamber: the doctor's paid patient today (serial 1); the demo patient's unpaid and claimed ones
        // later (not today — the tester books today himself)
        if (withOnline) {
            appt(
                bp["kamal"]!!, d, chOnline, today,
                A("confirmed", "ডায়াবেটিসের রিপোর্ট দেখাতে চাই (ভিডিও কলে)", now - DAY, serial = 1, extra = mapOf("pay_state" to "paid", "pay_ref" to "DEMO4K7Q2M", "pay_claimed_at" to sql.ts(now - DAY + 600_000), "pay_paid_at" to sql.ts(now - DAY + 3_600_000), "pay_paid_by" to doc.uid)), // bn-ok
            )
            val onUnpaid = nextOpen(chOnline, today + 2 * DAY)
            appt(me, d, chOnline, onUnpaid, A("confirmed", "সুগারের ওষুধ ঠিক করতে চাই (অনলাইনে)", now - 4 * 3600 * 1000, serial = 1)) // bn-ok
            val onClaimed = nextOpen(chOnline, onUnpaid + 2 * DAY)
            appt(
                me, d, chOnline, onClaimed,
                A("confirmed", "প্রেসারের ফলো-আপ (অনলাইনে)", now - 3 * 3600 * 1000, serial = 1, extra = mapOf("pay_state" to "claimed", "pay_ref" to "DEMO8X2K4P", "pay_claimed_at" to sql.ts(now - 2 * 3600 * 1000))), // bn-ok
            )
        }

        for ((k, n) in serialMax) {
            sql.insert(
                "serial_counters",
                linkedMapOf("provider_uid" to k.first, "visit_date" to date(k.second), "queue_key" to k.third, "n" to n, "at" to sql.ts(now - 3600 * 1000)),
                "(provider_uid, visit_date, queue_key) DO UPDATE SET n = EXCLUDED.n, at = EXCLUDED.at",
            )
        }

        // today's queue is running (queue.js): number 1 done, now number 2; the doctor takes ~8 minutes on average.
        // A serial the owner takes today (number 5) shows "now", "before you" and the expected time.
        val todayKey = todayCh.hospitalId ?: todayCh.hospitalName
        val qKey = QueueRules.keyOf(todayKey)
        sql.insert(
            "queues",
            linkedMapOf(
                "provider_uid" to doc.uid, "visit_date" to date(today), "queue_key" to qKey, "chamber_key" to todayKey, "started" to true, "current" to 2,
                "pos" to 2, "done" to sql.ints(listOf(1)), "paused" to false, "paused_at" to null, "finished" to false,
                "started_at" to sql.ts(now - 16 * MIN), "last_at" to sql.ts(now - 8 * MIN),
                "advances" to PgArr(listOf(now - 16 * MIN, now - 8 * MIN), "bigint"), "updated_at" to sql.ts(now - 8 * MIN),
            ),
            "(provider_uid, visit_date, queue_key) DO NOTHING",
        )
        sql.insert(
            "provider_queue_stats",
            linkedMapOf("provider_uid" to doc.uid, "queue_key" to qKey, "avg_minutes" to 8.0, "n" to 40, "at" to sql.ts(now - 8 * MIN)),
            "(provider_uid, queue_key) DO UPDATE SET avg_minutes = EXCLUDED.avg_minutes, n = EXCLUDED.n, at = EXCLUDED.at",
        )

        // ---------------------------------------------------------------
        // patients the assistant entered (no account in the app)
        // ---------------------------------------------------------------
        fun rxPat(id: String, name: String, age: Int, gender: String, phone: String, createdAt: Long, lastRxAt: Long): String {
            sql.insert(
                "rx_patients",
                linkedMapOf(
                    "id" to id, "doctor_uid" to doc.uid, "name" to name, "age" to age, "gender" to gender,
                    "phone" to Phones.localBd(Phones.normalizeBd(phone)), "phone_key" to (rxActions.phoneKey(phone) ?: ""), "linked_uid" to null,
                    "created_by" to asst.uid, "created_at" to sql.ts(createdAt), "last_rx_at" to sql.ts(lastRxAt.takeIf { it != 0L }),
                ),
            )
            return id
        }
        val p1 = rxPat("demo-rx-pat-jamila", "ডেমো রোগী জমিলা খাতুন", 62, "female", "01200000321", now - 12 * DAY, now - 10 * DAY)
        rxPat("demo-rx-pat-malek", "ডেমো রোগী আব্দুল মালেক", 70, "male", "01200000322", now - 3 * 3600 * 1000, 0)
        val p3 = rxPat("demo-rx-pat-paper", me.name, me.age, me.gender, me.phone, now - 31 * DAY, now - 30 * DAY)

        // ---------------------------------------------------------------
        // prescriptions
        // ---------------------------------------------------------------
        class R(
            val p: Person, val createdAt: Long, val code: String? = null, val status: String = "active", val ci: Int? = 0,
            val appointmentId: String? = null, val rxPatientId: String? = null, val noPatientUid: Boolean = false,
            val complaints: String = "", val findings: Map<String, String> = emptyMap(), val diagnosis: String = "",
            val medicines: List<RxMedicine> = emptyList(), val tests: List<String> = emptyList(), val advice: String = "",
            val followUpDays: Int? = null, val followUpNote: String = "", val dispensed: List<Pair<Long, List<Pair<Int, Int>>>> = emptyList(),
        )
        fun rx(key: String, o: R): Rx {
            val id = "demo-rx-$key"
            val ch = o.ci?.let { docChambers[it] }
            val followUp = o.followUpDays?.let { RxRules.dhakaToday(o.createdAt) + it * DAY }
            val code = o.code ?: randCode()
            val meds = o.medicines.mapIndexed { i, m -> m.copy(i = i) }
            val f = linkedMapOf("bp" to "", "pulse" to "", "temp" to "", "weight" to "", "spo2" to "", "other" to "").apply { putAll(o.findings) }
            val row = linkedMapOf<String, Any?>(
                "id" to id, "code" to code, "status" to o.status, "created_at" to sql.ts(o.createdAt), "doctor_uid" to doc.uid,
                "doctor_name" to docName, "doctor_degrees" to docDegrees, "doctor_specialties" to sql.texts(listOf("medicine", "endocrinology")),
                "doctor_reg_no" to docReg,
                "doctor_chamber" to ch?.let { it.hospitalNameBn.ifEmpty { it.hospitalName } },
                "doctor_chamber_address" to ch?.let { listOf(it.address, it.district).filter { s -> !s.isNullOrEmpty() }.joinToString(", ") },
                "doctor_chamber_phone" to ch?.serialPhone,
                "patient_name" to o.p.name, "patient_age" to o.p.age, "patient_gender" to o.p.gender,
                "patient_uid" to if (o.noPatientUid) null else o.p.uid, "rx_patient_id" to o.rxPatientId, "appointment_id" to o.appointmentId,
                "complaints" to o.complaints, "finding_bp" to f["bp"], "finding_pulse" to f["pulse"], "finding_temp" to f["temp"],
                "finding_weight" to f["weight"], "finding_spo2" to f["spo2"], "finding_other" to f["other"], "diagnosis" to o.diagnosis,
                "tests" to sql.texts(o.tests), "test_place" to (if (o.tests.isNotEmpty()) labName else ""), "test_place_id" to null,
                "advice" to o.advice, "follow_up_date" to date(followUp),
                "follow_up_book_date" to date(if (followUp != null && ch != null && ch.appointments) nextOpen(ch, followUp) else null),
                "follow_up_note" to (if (o.followUpDays != null) o.followUpNote else ""), "chamber_index" to o.ci,
                "cancelled_at" to (if (o.status == "cancelled") sql.ts(o.createdAt + 15 * MIN) else null),
            )
            val dispensed = o.dispensed.map { (atMs, items) -> RxDispensing(shopU.uid, shopName, atMs, items) }
            if (dispensed.isNotEmpty()) row["refill_at"] = date(RxRules.refillDate(meds, dispensed, now))
            sql.insert("prescriptions", row)
            for (m in meds) {
                sql.insert(
                    "prescription_medicines",
                    linkedMapOf(
                        "rx_id" to id, "idx" to m.i, "name" to m.name, "form" to m.form, "strength" to m.strength, "dose" to m.dose,
                        "timing" to m.timing, "days" to m.days, "note" to m.note,
                    ),
                )
            }
            for (x in dispensed) {
                val did = jdbc.queryForObject(
                    "INSERT INTO prescription_dispensings (rx_id, pharmacy_uid, pharmacy_name, at) VALUES (?, ?, ?, ?) RETURNING id",
                    Long::class.java, id, x.by, x.name, sql.ts(x.at),
                )
                for ((i, qty) in x.items) sql.insert("prescription_dispensed_items", linkedMapOf("dispensing_id" to did, "med_idx" to i, "qty" to qty))
            }
            return Rx(id, code, o.p, meds)
        }

        val rxA = rx(
            "a",
            R(
                me, at(d75, "17:45"), code = CODES["A"], appointmentId = aMe75,
                complaints = "বারবার প্রস্রাব, পিপাসা, ক্লান্তি — ২ মাস", findings = mapOf("bp" to "140/90", "pulse" to "84", "weight" to "78 kg"),
                diagnosis = "Type 2 Diabetes Mellitus (newly diagnosed)",
                medicines = listOf(med("Comet", "Tablet", "500 mg", "1+0+1", 30, "after"), med("Secrin", "Tablet", "1 mg", "1+0+0", 30, "before", "নাস্তার ১৫ মিনিট আগে")),
                tests = listOf("HbA1c", "FBS", "Lipid profile"),
                advice = "চিনি ও মিষ্টি বাদ দিন। প্রতিদিন ৩০ মিনিট হাঁটুন। ভাত কম, সবজি বেশি খান।", followUpDays = 30, followUpNote = "রিপোর্ট নিয়ে আসবেন",
                dispensed = listOf(at(d75, "18:30") to listOf(0 to 60, 1 to 30)),
            ),
        )
        rx(
            "c",
            R(
                me, at(d20, "17:20"), code = CODES["C"], status = "cancelled", appointmentId = aMe20,
                diagnosis = "Type 2 DM", medicines = listOf(med("Comet", "Tablet", "500 mg", "1+1+1", 30, "after")), advice = "ভুল মাত্রা — বাতিল করে নতুন দেওয়া হয়েছে",
            ),
        )
        val rxB = rx(
            "b",
            R(
                me, at(d20, "17:35"), code = CODES["B"], appointmentId = aMe20,
                complaints = "সুগার নিয়ন্ত্রণে নেই, মাথা ভার লাগে", findings = mapOf("bp" to "150/95", "pulse" to "88", "weight" to "77 kg", "other" to "RBS 11.2 mmol/L"),
                diagnosis = "Type 2 DM, Dyslipidaemia, Hypertension",
                medicines = listOf(
                    med("Comet", "Tablet", "500 mg", "1+0+1", 30, "after"),
                    med("Rosuva", "Tablet", "10 mg", "0+0+1", 30, "after"),
                    med("Bislol", "Tablet", "2.5 mg", "1+0+0", 30, "before"),
                    med("Napa", "Tablet", "500 mg", "1+1+1", 3, "after", "জ্বর বা ব্যথা থাকলে"),
                ),
                tests = listOf("Lipid profile", "S. Creatinine", "CBC", "ECG"),
                advice = "লবণ কম খান। নিয়মিত প্রেসার আর সুগার মাপুন। ৩০ দিন পর রিপোর্ট নিয়ে আসবেন।", followUpDays = 30, followUpNote = "রিপোর্ট নিয়ে আসবেন",
                dispensed = listOf(at(d20, "18:20") to listOf(0 to 30, 1 to 30)),
            ),
        )
        val rxS1 = rx(
            "salma-old",
            R(
                bp["salma"]!!, at(d7, "17:30"), appointmentId = aSalmaOld,
                complaints = "গলা ফোলা, ওজন বাড়ছে, শীত বেশি লাগে", findings = mapOf("bp" to "120/80", "weight" to "68 kg"), diagnosis = "Hypothyroidism (suspected)",
                medicines = listOf(med("Thyrox", "Tablet", "50 mcg", "1+0+0", 30, "before", "খালি পেটে")), tests = listOf("TSH", "CBC"),
                advice = "রিপোর্ট দেখিয়ে ওষুধের মাত্রা ঠিক হবে।",
            ),
        )
        val rxT1 = rx(
            "salma-today",
            R(
                bp["salma"]!!, now - 50 * MIN, appointmentId = tSalma, ci = todayCi,
                complaints = "ক্লান্তি কমেছে", diagnosis = "Hypothyroidism", medicines = listOf(med("Thyrox", "Tablet", "75 mcg", "1+0+0", 60, "before", "খালি পেটে")),
                tests = listOf("TSH"), advice = "৬ সপ্তাহ পর TSH করাবেন।", followUpDays = 45,
            ),
        )
        val rxK = rx(
            "karim-today",
            R(
                bp["karim"]!!, now - 30 * MIN, code = CODES["K"], appointmentId = tKarim, ci = todayCi,
                complaints = "মাথা ভার, ঘাড়ে ব্যথা", findings = mapOf("bp" to "160/100", "pulse" to "90"), diagnosis = "Essential Hypertension",
                medicines = listOf(med("Amdocal", "Tablet", "5 mg", "0+0+1", 30, "after"), med("Losart", "Tablet", "50 mg", "1+0+0", 30, "after"), med("Ecosprin", "Tablet", "75 mg", "0+1+0", 30, "after")),
                tests = listOf("Lipid profile", "ECG"), advice = "লবণ, গরুর মাংস, ভাজাপোড়া কম। প্রতিদিন প্রেসার মাপুন।", followUpDays = 15,
            ),
        )
        val rxD = rx(
            "nasima",
            R(
                bp["nasima"]!!, at(d2, "18:10"), appointmentId = aNasima,
                complaints = "পেটে ব্যথা, গ্যাস, বমি ভাব — ১ সপ্তাহ", diagnosis = "Acid peptic disease",
                medicines = listOf(med("Seclo", "Capsule", "20 mg", "1+0+1", 14, "before"), med("Motigut", "Tablet", "10 mg", "1+1+1", 7, "before"), med("Antacid Plus", "Suspension", "", "2 চামচ ৩ বার", 7, "after")),
                tests = listOf("USG of whole abdomen"), advice = "ঝাল-তেল কম খাবেন, রাতে দেরি করে খাবেন না।",
            ),
        )
        val rxKarimOld = rx(
            "karim-old",
            R(
                bp["karim"]!!, at(d10, "18:00"),
                diagnosis = "Hypertension", medicines = listOf(med("Amdocal", "Tablet", "5 mg", "0+0+1", 0, "after")), tests = listOf("CBC", "RBS"), advice = "প্রেসারের ওষুধ চলবে।",
            ),
        )
        rx(
            "jamila",
            R(
                Person(null, "ডেমো রোগী জমিলা খাতুন", 62, "female"), now - 10 * DAY, rxPatientId = p1, noPatientUid = true,
                complaints = "হাঁটুতে ব্যথা, সকালে জমে থাকে", diagnosis = "Osteoarthritis knee",
                medicines = listOf(med("Calbo", "Tablet", "500 mg", "0+1+0", 30, "after"), med("Naprosyn", "Tablet", "250 mg", "1+0+1", 7, "after", "ব্যথা থাকলে")),
                advice = "সিঁড়ি কম ভাঙবেন, ওজন কমান।",
            ),
        )
        rx(
            "paper",
            R(
                me, now - 30 * DAY, code = CODES["PAPER"], rxPatientId = p3, noPatientUid = true, ci = 1,
                complaints = "সর্দি-কাশি, হালকা জ্বর", diagnosis = "Acute URTI",
                medicines = listOf(med("Fexo", "Tablet", "120 mg", "0+0+1", 7, "after"), med("Napa", "Tablet", "500 mg", "1+1+1", 3, "after", "জ্বর থাকলে")),
                tests = listOf("RBS"), advice = "গরম পানি খাবেন, বিশ্রাম নিন।",
            ),
        )
        // the prescription of the home-collection request (Fatema — diabetes)
        val rxF = rx(
            "fatema",
            R(
                bp["fatema"]!!, now - 3 * 3600 * 1000, ci = null,
                complaints = "পায়ে জ্বালাপোড়া, পিপাসা বেশি", diagnosis = "Type 2 DM (follow-up)",
                medicines = listOf(med("Comet", "Tablet", "500 mg", "1+0+1", 30, "after")), tests = listOf("HbA1c", "FBS", "Lipid profile"),
                advice = "খালি পেটে নমুনা দেবেন।",
            ),
        )

        // ---------------------------------------------------------------
        // pharmacy: sent prescriptions, online orders, "asked but not given", stock
        // ---------------------------------------------------------------
        fun order(id: String, r: Rx, p: Person, status: String, createdAt: Long, mode: String = "shop", note: String = "", extra: Map<String, Any?> = emptyMap()) {
            val row = linkedMapOf<String, Any?>(
                "id" to id, "rx_id" to r.id, "code" to r.code, "pharmacy_uid" to shopU.uid, "pharmacy_name" to shopName, "patient_uid" to p.uid,
                "patient_name" to r.patient.name, "status" to status, "mode" to mode, "note" to note, "created_at" to sql.ts(createdAt),
            )
            row.putAll(extra)
            sql.insert("rx_orders", row)
        }
        order("demo-rx-order-me-done", rxB, me, "done", at(d20, "18:05"), extra = mapOf("done_at" to sql.ts(at(d20, "18:20")), "missed" to sql.texts(listOf("Bislol"))))
        order("demo-rx-order-nasima", rxD, bp["nasima"]!!, "sent", now - 40 * MIN, note = "বাকিটা পরে নেব")
        order(
            "demo-rx-order-karim-online", rxK, bp["karim"]!!, "sent", now - 20 * MIN, mode = "online", note = "সন্ধ্যার পরে দিলে ভালো",
            extra = mapOf("address" to "ডেমো বাসা ১২, রোড ৩, ছায়াবীথি, জয়দেবপুর, গাজীপুর", "phone" to bp["karim"]!!.phone, "delivery_fee" to deliveryFee),
        )
        order(
            "demo-rx-order-salma-back", rxS1, bp["salma"]!!, "withdrawn", at(d7, "18:00"),
            extra = mapOf("withdraw_reason" to "not_available", "missed" to sql.texts(listOf("Thyrox")), "updated_at" to sql.ts(at(d7, "18:15"))),
        )
        if (jdbc.queryForList("SELECT 1 FROM pharmacy_missed WHERE pharmacy_uid = ? LIMIT 1", shopU.uid).isEmpty()) {
            for ((n, count, ago) in listOf(Triple("Thyrox", 2, 7), Triple("Bislol", 1, 20), Triple("Ecosprin", 1, 33))) {
                sql.insert("pharmacy_missed", linkedMapOf("pharmacy_uid" to shopU.uid, "item_key" to RxMath.missKey(n), "name" to n, "count" to count, "last_at" to sql.ts(now - ago * DAY)))
            }
        }
        if (jdbc.queryForList("SELECT 1 FROM pharmacy_stock WHERE pharmacy_uid = ?", shopU.uid).isEmpty()) {
            val names = listOf(
                "Comet", "Rosuva", "Napa", "Napa Extra", "Seclo", "Motigut", "Amdocal", "Losart", "Secrin", "Fexo",
                "Calbo", "Ace", "Sergel", "Maxpro", "Alatrol", "Montene", "Azithrocin", "Cef-3", "Monas", "Neurobion",
            )
            stock.save(shopU.uid, StockSnap.EMPTY, names, emptyMap(), emptyMap(), false, false)
            jdbc.update("UPDATE pharmacy_stock SET updated_at = ? WHERE pharmacy_uid = ?", sql.ts(now - 3 * DAY), shopU.uid)
        }

        // ---------------------------------------------------------------
        // lab: tests and reports, the doctor's report reviews
        // ---------------------------------------------------------------
        val tests = labCatalog.tests()
        val homeCollection = (lab?.get("home_collection") as? Boolean) ?: true
        val priceX = labStore.prices(labU.uid)
        fun est(t: List<String>) = LabPrices.snapshot(LabPrices.estimate(t, priceX, homeCollection, tests), now)

        /** lab_report rows from the form (unit, range, high/low) */
        fun reportRows(testName: String, gender: String, values: Map<String, String>): List<Map<String, Any?>> {
            val f = LabTests.formFor(testName, gender, emptyMap(), tests)
            val code = f["code"] as? String ?: return emptyList()
            @Suppress("UNCHECKED_CAST")
            val params = f["params"] as List<Map<String, Any?>>
            return params.filter { values[it["key"]] != null }.map { p ->
                val result = values.getValue(p["key"] as String)
                val low = p["low"] as Double?
                val high = p["high"] as Double?
                linkedMapOf(
                    "test" to p["name"], "result" to result, "unit" to (p["unit"] as? String ?: ""),
                    "range" to ((p["range"] as? String)?.takeIf { it.isNotEmpty() } ?: (p["text"] as? String) ?: ""),
                    "flag" to (if (low != null || high != null) LabTests.flagOf(result, low, high) else ""),
                    "group" to f["name"], "code" to code, "key" to p["key"], "low" to low, "high" to high,
                )
            }
        }
        class L(
            val tests: List<String>, val status: String, val createdAt: Long, val report: List<Map<String, Any?>>? = null, val note: String = "",
            val reportedAt: Long? = null, val updatedAt: Long? = null, val home: Map<String, Any?>? = null,
        )
        fun labOrder(id: String, r: Rx, p: Person, o: L) {
            val prices = if (o.status == "sent" || o.status == "received") est(o.tests) else null
            val row = linkedMapOf<String, Any?>(
                "id" to id, "rx_id" to r.id, "code" to r.code, "lab_uid" to labU.uid, "lab_name" to labName, "patient_uid" to p.uid,
                "patient_name" to r.patient.name, "patient_age" to r.patient.age, "patient_gender" to r.patient.gender, "doctor_uid" to doc.uid,
                "doctor_name" to docName, "tests" to sql.texts(o.tests), "status" to o.status, "created_at" to sql.ts(o.createdAt),
                "updated_at" to sql.ts(o.updatedAt),
            )
            if (prices != null) {
                row["price_subtotal"] = prices.subtotal
                row["price_discount_pct"] = prices.discountPct
                row["price_estimate"] = prices.estimate
                row["price_missing"] = sql.texts(prices.missing)
                row["price_at"] = sql.ts(prices.at)
            }
            if (o.home != null) row.putAll(o.home)
            if (o.report != null) {
                row["report_note"] = o.note
                row["reported_at"] = sql.ts(o.reportedAt)
            }
            sql.insert("lab_orders", row)
            prices?.items?.forEachIndexed { i, it ->
                sql.insert("lab_order_price_items", linkedMapOf("order_id" to id, "idx" to i, "test" to it.test, "name" to it.name, "price" to it.price, "tat" to it.tat))
            }
            o.report?.forEachIndexed { i, it ->
                sql.insert(
                    "lab_report_items",
                    linkedMapOf(
                        "order_id" to id, "idx" to i, "test" to it["test"], "result" to it["result"], "unit" to it["unit"], "range_text" to it["range"],
                        "flag" to it["flag"], "group_name" to it["group"], "code" to it["code"], "param_key" to it["key"], "low" to it["low"], "high" to it["high"],
                    ),
                )
            }
        }
        fun review(labOrderId: String, r: Rx, p: Person, tests: List<String>, status: String, createdAt: Long, updatedAt: Long? = null, extra: Map<String, Any?> = emptyMap()) {
            val row = linkedMapOf<String, Any?>(
                "lab_order_id" to labOrderId, "rx_id" to r.id, "doctor_uid" to doc.uid, "doctor_name" to docName, "patient_uid" to p.uid,
                "patient_name" to r.patient.name, "lab_name" to labName, "tests" to sql.texts(tests), "status" to status, "fee" to "৩০০ টাকা",
                "no_fee" to false, "pay_number" to "01200000202", "pay_methods" to sql.texts(listOf("bkash", "nagad")),
                "created_at" to sql.ts(createdAt), "updated_at" to sql.ts(updatedAt ?: createdAt),
            )
            row.putAll(extra)
            sql.insert("rx_reviews", row)
        }

        val l1At = at(d75, "17:00") + 2 * DAY
        val l1Tests = listOf("HbA1c", "FBS", "Lipid profile")
        labOrder(
            "demo-rx-lab-me-old", rxA, me,
            L(
                l1Tests, "reported", at(d75, "19:00"), reportedAt = l1At, note = "খালি পেটে নমুনা নেওয়া হয়েছে",
                report = reportRows("HbA1c", "male", mapOf("hba1c" to "8.4")) + reportRows("FBS", "male", mapOf("glu_f" to "9.1")) +
                    reportRows("Lipid profile", "male", mapOf("chol" to "236", "tg" to "210", "hdl" to "36", "ldl" to "152")),
            ),
        )
        review(
            "demo-rx-lab-me-old", rxA, me, l1Tests, "done", l1At, l1At + DAY,
            mapOf(
                "method" to "bkash", "trx_id" to "DEMO5H2K8M", "paid_at" to sql.ts(l1At + 6 * 3600 * 1000), "done_at" to sql.ts(l1At + DAY),
                "comment" to "সুগার আর কোলেস্টেরল বেশি। ওষুধ চালিয়ে যান, ৩০ দিন পর আবার দেখাবেন।", "new_rx_id" to null,
            ),
        )
        val l2At = at(d20, "17:00") + 4 * DAY
        labOrder(
            "demo-rx-lab-me-lipid", rxB, me,
            L(
                listOf("Lipid profile", "S. Creatinine"), "reported", at(d20, "19:00"), reportedAt = l2At,
                report = reportRows("Lipid profile", "male", mapOf("chol" to "198", "tg" to "160", "hdl" to "41", "ldl" to "118")) +
                    reportRows("S. Creatinine", "male", mapOf("creat" to "1.1")),
            ),
        )
        review("demo-rx-lab-me-lipid", rxB, me, listOf("Lipid profile", "S. Creatinine"), "claimed", l2At, now - 3 * 3600 * 1000, mapOf("method" to "bkash", "trx_id" to "DEMO7K3P9Q"))
        // sample taken — the lab writes the report
        labOrder("demo-rx-lab-me-cbc", rxB, me, L(listOf("CBC"), "received", now - DAY, updatedAt = now - DAY + 3600 * 1000))
        // a new request — the lab presses "sample taken"
        labOrder("demo-rx-lab-salma-new", rxT1, bp["salma"]!!, L(listOf("TSH"), "sent", now - 25 * MIN))
        // an earlier patient's report — the doctor has to look (paid)
        val l5At = at(d7, "17:00") + 2 * DAY
        labOrder(
            "demo-rx-lab-salma-tsh", rxS1, bp["salma"]!!,
            L(
                listOf("TSH", "CBC"), "reported", at(d7, "19:00"), reportedAt = l5At,
                report = reportRows("TSH", "female", mapOf("tsh" to "9.8")) +
                    reportRows("CBC", "female", mapOf("hb" to "10.9", "esr" to "28", "wbc" to "7.2", "neut" to "62", "lymph" to "30", "mono" to "5", "eos" to "3", "baso" to "0", "plt" to "265")),
            ),
        )
        review(
            "demo-rx-lab-salma-tsh", rxS1, bp["salma"]!!, listOf("TSH", "CBC"), "paid", l5At, now - 5 * 3600 * 1000,
            mapOf("method" to "nagad", "trx_id" to "DEMO2N6R4T", "paid_at" to sql.ts(now - 5 * 3600 * 1000)),
        )
        labOrder(
            "demo-rx-lab-karim", rxKarimOld, bp["karim"]!!,
            L(
                listOf("CBC", "RBS"), "reported", at(d10, "19:00"), reportedAt = at(d10, "19:00") + DAY,
                report = reportRows("RBS", "male", mapOf("glu" to "6.8")) + reportRows("CBC", "male", mapOf("hb" to "14.1", "esr" to "12", "wbc" to "8.1", "plt" to "240")),
            ),
        )
        review("demo-rx-lab-karim", rxKarimOld, bp["karim"]!!, listOf("CBC", "RBS"), "unpaid", at(d10, "19:00") + DAY)
        // home collection — tomorrow 7–9 am, the lab presses "confirm the time" (or "can't come home")
        labOrder(
            "demo-rx-lab-fatema-home", rxF, bp["fatema"]!!,
            L(
                listOf("HbA1c", "FBS", "Lipid profile"), "sent", now - 15 * MIN,
                home = mapOf(
                    "home_address" to "ডেমো বাসা ৫, স্কুল রোড, ছায়াবীথি, জয়দেবপুর, গাজীপুর", "home_phone" to bp["fatema"]!!.phone,
                    "home_date" to date(today + DAY), "home_slot" to "early", "home_note" to "খালি পেটে থাকব", "home_fee" to priceX?.homeFee,
                    "home_status" to "requested", "home_requested_at" to sql.ts(now - 15 * MIN),
                ),
            ),
        )
        // cancelled by the lab
        labOrder("demo-rx-lab-nasima-usg", rxD, bp["nasima"]!!, L(listOf("USG of whole abdomen"), "cancelled", at(d2, "19:00"), updatedAt = at(d2, "19:00") + 3 * 3600 * 1000))
    }
}
