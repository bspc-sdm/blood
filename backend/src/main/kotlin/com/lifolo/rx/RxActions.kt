package com.lifolo.rx

import com.lifolo.auth.AuthUser
import com.lifolo.common.Js
import com.lifolo.common.LifoloProperties
import com.lifolo.common.Phones
import com.lifolo.common.QuotaService
import com.lifolo.common.Rules
import com.lifolo.common.action.ActionContext
import com.lifolo.common.action.ActionHandler
import com.lifolo.common.action.ActionModule
import com.lifolo.common.intOrNull
import com.lifolo.common.stringArray
import com.lifolo.contracts.Badges
import com.lifolo.contracts.Chambers
import com.lifolo.contracts.Prescription
import com.lifolo.contracts.Prescriptions
import com.lifolo.contracts.ProviderAssistants
import com.lifolo.demo.DemoWorld
import com.lifolo.lab.LabService
import com.lifolo.lab.LabStore
import com.lifolo.lab.LabViews
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet

/**
 * prescriptions.js (rx_me, rx_team, rx_team_set, rx_assisting, rx_patients, rx_patient_history, rx_patient_add,
 * rx_create, rx_cancel, rx_doctor_list, rx_mine, rx_claim, rx_get) and rxnet.js rx_activity.
 * Only verified doctors write; an assistant (email added by the doctor) enters patients. Prescriptions never change
 * after writing — only cancelled.
 */
@Component
class RxActions(
    private val jdbc: JdbcTemplate,
    private val store: PrescriptionStore,
    private val badges: Badges,
    private val assistants: ProviderAssistants,
    private val chambers: Chambers,
    private val quotas: QuotaService,
    private val push: RxPush,
    private val labs: LabService,
    private val labStore: LabStore,
    private val props: LifoloProperties,
) : ActionModule {
    override fun actions(): Map<String, ActionHandler> = mapOf(
        "rx_me" to ActionHandler(::rxMe),
        "rx_team" to ActionHandler(::rxTeam),
        "rx_team_set" to ActionHandler(::rxTeamSet),
        "rx_assisting" to ActionHandler(::rxAssisting),
        "rx_patients" to ActionHandler(::rxPatients),
        "rx_patient_history" to ActionHandler(::rxPatientHistory),
        "rx_patient_add" to ActionHandler(::rxPatientAdd),
        "rx_create" to ActionHandler(::rxCreate),
        "rx_cancel" to ActionHandler(::rxCancel),
        "rx_doctor_list" to ActionHandler(::rxDoctorList),
        "rx_mine" to ActionHandler(::rxMine),
        "rx_claim" to ActionHandler(::rxClaim),
        "rx_get" to ActionHandler(::rxGet),
        "rx_activity" to ActionHandler(::rxActivity),
    )

    // ------------------------------------------------------------------ helpers

    /** prescriptions.js secret(): the phone-key HMAC key (CRON_SECRET, else the service account, else 'rx') */
    private fun secret(): String = props.cronSecret.ifEmpty { props.firebaseServiceAccount.ifEmpty { "rx" } }

    fun phoneKey(phone: String?): String? = RxRules.phoneKey(phone, secret())

    private fun notFound(ctx: ActionContext) = ctx.err(404, "Not found", "পাওয়া যায়নি")

    private fun requireDoctor(ctx: ActionContext, uid: String) {
        if (badges.approvedBadge(uid) != "doctor") {
            throw ctx.err(403, "Only verified doctors can write prescriptions", "শুধু যাচাই করা ডাক্তার প্রেসক্রিপশন লিখতে পারেন")
        }
    }

    private fun myEmail(u: AuthUser): String = if (u.emailVerified) u.email.lowercase() else ""

    data class Acting(val doctorUid: String, val role: String)

    /** actingFor: the doctor, or an assistant added by the doctor (email) for a verified doctor */
    private fun actingFor(ctx: ActionContext, doctorUid: String?): Acting {
        val uid = ctx.user.id
        val d = doctorUid?.takeIf { it.isNotEmpty() && it != uid } ?: uid
        if (d == uid) {
            requireDoctor(ctx, uid)
            return Acting(d, "doctor")
        }
        if (!Rules.isValidId(d)) throw notFound(ctx)
        val email = myEmail(ctx.user)
        if (email.isEmpty() || !assistants.isAssistant(d, email)) {
            throw ctx.err(403, "You are not this doctor's assistant", "আপনি এই ডাক্তারের সহকারী হিসেবে যোগ করা নেই")
        }
        if (badges.approvedBadge(d) != "doctor") throw ctx.err(403, "This doctor is not verified now", "এই ডাক্তার এখন যাচাই করা নেই")
        return Acting(d, "assistant")
    }

    private data class Provider(val name: String, val degrees: String, val specialties: List<String>)

    private fun provider(uid: String): Provider? =
        jdbc.rows("SELECT name, degrees, specialties FROM providers WHERE uid = ?", uid) { rs ->
            Provider(rs.getString("name") ?: "", rs.getString("degrees") ?: "", rs.stringArray("specialties") ?: emptyList())
        }.firstOrNull()

    private data class RxPatient(
        val id: String,
        val doctorUid: String,
        val name: String,
        val age: Int?,
        val gender: String,
        val phone: String,
        val phoneKey: String,
        val inApp: Boolean,
        val createdAt: Long,
        val lastRxAt: Long,
    ) {
        fun view(): LinkedHashMap<String, Any?> = linkedMapOf(
            "id" to id, "name" to name, "age" to age, "gender" to gender, "phone" to phone, "linked" to inApp,
            "createdAt" to createdAt, "lastRxAt" to lastRxAt,
        )
    }

    private fun rxPatient(rs: ResultSet) = RxPatient(
        rs.getString("id"), rs.getString("doctor_uid"), rs.getString("name") ?: "", rs.intOrNull("age"), rs.getString("gender") ?: "",
        rs.getString("phone") ?: "", rs.getString("phone_key") ?: "", rs.getBoolean("in_app"),
        rs.getTimestamp("created_at")?.time ?: 0L, rs.getTimestamp("last_rx_at")?.time ?: 0L,
    )

    /**
     * rx_patients rows + in_app: at least one of this paper patient's prescriptions was added to an app account by its
     * code (the doctor's "in app" tag). Paper patients are never linked to an account any more — see [rxClaim].
     */
    private val RXP_SQL = "SELECT r.*, EXISTS (SELECT 1 FROM prescriptions x WHERE x.rx_patient_id = r.id AND x.patient_uid IS NOT NULL) AS in_app FROM rx_patients r"

    private fun rxPatientById(id: String): RxPatient? = jdbc.rows("$RXP_SQL WHERE r.id = ?", id) { rxPatient(it) }.firstOrNull()

    private data class Appt(
        val id: String,
        val providerUid: String,
        val patientUid: String?,
        val memberId: String?,
        val patientName: String,
        val date: Long,
        val serial: Int?,
        val status: String,
        val chamber: String,
        val chamberBn: String,
        val memberAge: Int?,
        val memberGender: String?,
        val online: Boolean = false,
        val payState: String? = null,
        val payRef: String? = null,
        val start: String = "",
    )

    private val APPT_SQL = """
        SELECT a.id, a.provider_uid, a.patient_uid, a.member_id, a.patient_name, a.visit_date, a.serial, a.status, a.chamber, a.chamber_bn,
          a.online, a.pay_state, a.pay_ref, a.start_time, m.age AS member_age, m.gender AS member_gender
        FROM appointments a LEFT JOIN user_family_members m ON m.uid = a.patient_uid AND m.member_id = a.member_id
    """.trimIndent()

    private fun appt(rs: ResultSet) = Appt(
        rs.getString("id"), rs.getString("provider_uid"), rs.getString("patient_uid"), rs.getString("member_id"),
        rs.getString("patient_name") ?: "", P3Sql.dayMs(rs, "visit_date") ?: 0L, rs.intOrNull("serial"), rs.getString("status"),
        rs.getString("chamber") ?: "", rs.getString("chamber_bn") ?: "", rs.intOrNull("member_age"), rs.getString("member_gender"),
        rs.getBoolean("online"), rs.getString("pay_state"), rs.getString("pay_ref"), rs.getString("start_time") ?: "",
    )

    private fun views(list: List<Prescription>) = list.sortedByDescending { it.createdAt }.map { store.view(it) }

    // ------------------------------------------------------------------ doctor / assistants

    /** the doctor: own name, degrees and chamber names (to pick a chamber when writing on the web) */
    fun rxMe(ctx: ActionContext): Any {
        val uid = ctx.user.id
        requireDoctor(ctx, uid)
        val p = provider(uid)
        val names = if (p != null) chambers.chambersOf(uid).map { it.hospitalNameBn.ifEmpty { it.hospitalName } } else emptyList()
        return linkedMapOf("name" to (p?.name ?: ""), "degrees" to (p?.degrees ?: ""), "chambers" to names)
    }

    fun rxTeam(ctx: ActionContext): Any {
        val uid = ctx.user.id
        requireDoctor(ctx, uid)
        return linkedMapOf("emails" to assistants.emails(uid))
    }

    fun rxTeamSet(ctx: ActionContext): Any {
        val uid = ctx.user.id
        requireDoctor(ctx, uid)
        val raw = ctx.body["emails"]?.takeIf { it.isArray }?.toList() ?: emptyList()
        val own = myEmail(ctx.user)
        val emails = raw.map { RxRules.jsTrim(Js.toStr(it)).lowercase() }.filter { it.isNotEmpty() }.distinct()
        if (emails.size > RxRules.MAX_ASSISTANTS) {
            throw ctx.err(400, "Up to ${RxRules.MAX_ASSISTANTS} assistants", "সর্বোচ্চ ${RxRules.MAX_ASSISTANTS} জন সহকারী")
        }
        if (emails.any { !RxRules.EMAIL_RE.matches(it) }) throw ctx.err(400, "Check the email addresses", "ইমেইলগুলো দেখে নিন")
        if (own.isNotEmpty() && own in emails) throw ctx.err(400, "You can't add yourself", "নিজেকে সহকারী হিসেবে যোগ করা যায় না")
        if (!assistants.set(uid, emails)) {
            // Node set({assistantEmails}, {merge: true}) also creates the provider document
            jdbc.update("INSERT INTO providers (uid, type) VALUES (?, 'doctor') ON CONFLICT (uid) DO NOTHING", uid)
            assistants.set(uid, emails)
        }
        return linkedMapOf("emails" to emails)
    }

    /** the assistant: doctors I work for */
    fun rxAssisting(ctx: ActionContext): Any {
        val email = myEmail(ctx.user)
        if (email.isEmpty()) return linkedMapOf("items" to emptyList<Any>())
        val items = assistants.doctorsFor(email, 10).filter { badges.approvedBadge(it) == "doctor" }.map { d ->
            val p = provider(d)
            linkedMapOf("doctorUid" to d, "name" to (p?.name ?: ""), "degrees" to (p?.degrees ?: ""))
        }
        return linkedMapOf("items" to items)
    }

    // ------------------------------------------------------------------ patients

    /** doctor/assistant: entered patients + the app's appointments around today */
    fun rxPatients(ctx: ActionContext): Any {
        val (doctorUid, role) = actingFor(ctx, ctx.body.string("doctorUid"))
        val today = RxRules.dhakaToday()
        val ps = jdbc.rows("$RXP_SQL WHERE r.doctor_uid = ? ORDER BY r.id COLLATE \"C\" LIMIT 1000", doctorUid) { rxPatient(it) }
        val patients = ps.sortedByDescending { if (it.lastRxAt != 0L) it.lastRxAt else it.createdAt }.take(300).map { it.view() }
        val appts = jdbc.rows("$APPT_SQL WHERE a.provider_uid = ? ORDER BY a.id COLLATE \"C\"", doctorUid) { appt(it) }
        val appointments = appts
            .filter { it.status in setOf("pending", "confirmed", "completed") && it.date >= today - 2 * RxRules.DAY_MS && it.date <= today + RxRules.DAY_MS }
            .sortedWith(compareBy<Appt> { it.date }.thenBy { it.serial?.takeIf { s -> s != 0 } ?: 999 })
            .map {
                linkedMapOf(
                    "id" to it.id, "name" to it.patientName, "date" to it.date, "serial" to it.serial?.takeIf { s -> s != 0 }, "status" to it.status,
                    "chamber" to it.chamberBn.ifEmpty { it.chamber },
                    "online" to it.online, "payState" to it.payState, "payRef" to it.payRef, "start" to it.start,
                )
            }
        // patients who came through the app (confirmed/completed): one row per patient, the last appointment to write with
        val groups = LinkedHashMap<String, LinkedHashMap<String, Any?>>()
        for (a in appts) {
            if ((a.status != "confirmed" && a.status != "completed") || a.patientUid.isNullOrEmpty()) continue
            val k = "${a.patientUid}|${a.memberId ?: ""}"
            val g = groups.getOrPut(k) { linkedMapOf("appointmentId" to a.id, "name" to a.patientName, "count" to 0, "last" to 0L) }
            g["count"] = (g["count"] as Int) + 1
            if (a.date >= (g["last"] as Long)) {
                g["appointmentId"] = a.id
                g["name"] = a.patientName.ifEmpty { g["name"] as String }
                g["last"] = a.date
            }
        }
        val appPatients = groups.values.sortedByDescending { it["last"] as Long }.take(300)
        return linkedMapOf("role" to role, "patients" to patients, "appointments" to appointments, "appPatients" to appPatients)
    }

    /** the doctor: earlier prescriptions of one patient (entered patient or app appointment) + partner lab orders */
    fun rxPatientHistory(ctx: ActionContext): Any {
        val uid = ctx.user.id
        requireDoctor(ctx, uid)
        val b = ctx.body
        val rxPatientId = b.string("rxPatientId")
        val appointmentId = b.string("appointmentId")
        val items: List<Prescription> = if (Rules.isValidId(rxPatientId)) {
            val p = rxPatientById(rxPatientId!!)
            if (p == null || p.doctorUid != uid) throw ctx.err(404, "Patient not found", "রোগী পাওয়া যায়নি")
            store.load("doctor_uid = ? AND rx_patient_id = ?", uid, p.id)
        } else if (Rules.isValidId(appointmentId)) {
            val a = jdbc.rows("$APPT_SQL WHERE a.id = ?", appointmentId) { appt(it) }.firstOrNull()
            if (a == null || a.providerUid != uid) throw ctx.err(404, "Appointment not found", "অ্যাপয়েন্টমেন্ট পাওয়া যায়নি")
            val name = RxRules.jsTrim(a.patientName)
            // a family member's appointment: other members of the same account are other patients
            store.load("doctor_uid = ? AND (appointment_id = ? OR patient_uid = ?)", uid, a.id, a.patientUid)
                .filter { it.appointmentId == a.id || (!a.patientUid.isNullOrEmpty() && it.patientUid == a.patientUid && RxRules.jsTrim((it.patient["name"] as? String) ?: "") == name) }
        } else {
            throw ctx.err(400, "Choose the patient", "রোগী বেছে নিন")
        }
        val sorted = items.sortedByDescending { it.createdAt }
        return linkedMapOf("items" to sorted.map { store.view(it) }, "labOrders" to labs.labsForDoctor(uid, sorted.map { it.id }))
    }

    /** doctor/assistant: a paper patient (name, age, gender, phone); same phone + name → the existing one */
    fun rxPatientAdd(ctx: ActionContext): Any {
        val (doctorUid) = actingFor(ctx, ctx.body.string("doctorUid"))
        val p = RxRules.parsePatient(ctx.lang, ctx.body.node)
        val phone = Phones.normalizeBd(Js.toStr(ctx.body["phone"])) ?: throw ctx.err(400, "Enter the patient's mobile number", "রোগীর মোবাইল নম্বর দিন")
        val key = phoneKey(phone)!!
        val same = jdbc.rows("$RXP_SQL WHERE r.doctor_uid = ? AND r.phone_key = ? ORDER BY r.id COLLATE \"C\"", doctorUid, key) { rxPatient(it) }
            .firstOrNull { it.name.lowercase() == p.name.lowercase() }
        if (same != null) {
            if (p.age != null || p.gender.isNotEmpty()) {
                jdbc.update(
                    "UPDATE rx_patients SET age = COALESCE(?, age), gender = CASE WHEN ? <> '' THEN ? ELSE gender END WHERE id = ?",
                    p.age, p.gender, p.gender, same.id,
                )
            }
            return linkedMapOf("patient" to rxPatientById(same.id)!!.view(), "existed" to true)
        }
        quotas.use(ctx.lang, ctx.user.id, "rxPatient")
        val id = com.lifolo.common.Ids.newId()
        jdbc.update(
            """
            INSERT INTO rx_patients (id, doctor_uid, name, age, gender, phone, phone_key, linked_uid, created_by, created_at, last_rx_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, NULL)
            """.trimIndent(),
            id, doctorUid, p.name, p.age, p.gender, Phones.localBd(phone), key, ctx.user.id, P3Sql.ts(System.currentTimeMillis()),
        )
        return linkedMapOf("patient" to rxPatientById(id)!!.view(), "existed" to false)
    }

    // ------------------------------------------------------------------ prescriptions

    /** the doctor writes (assistants cannot) */
    fun rxCreate(ctx: ActionContext): Any {
        val uid = ctx.user.id
        requireDoctor(ctx, uid)
        val b = ctx.body
        var patientUid: String? = null
        var rxPatientId: String? = null
        var appointmentId: String? = null
        val base = LinkedHashMap<String, Any?>()
        val aid = b.string("appointmentId")
        val pid = b.string("rxPatientId")
        if (Rules.isValidId(aid)) {
            val a = jdbc.rows("$APPT_SQL WHERE a.id = ?", aid) { appt(it) }.firstOrNull()
            if (a == null || a.providerUid != uid) throw ctx.err(404, "Appointment not found", "অ্যাপয়েন্টমেন্ট পাওয়া যায়নি")
            appointmentId = a.id
            patientUid = a.patientUid
            base["name"] = a.patientName
            if (a.memberId != null && a.memberAge != null) base["age"] = a.memberAge
            if (a.memberId != null && !a.memberGender.isNullOrEmpty()) base["gender"] = a.memberGender
        } else if (Rules.isValidId(pid)) {
            val p = rxPatientById(pid!!)
            if (p == null || p.doctorUid != uid) throw ctx.err(404, "Patient not found", "রোগী পাওয়া যায়নি")
            rxPatientId = p.id
            // a paper patient: the prescription stays with the doctor until the patient adds it by its own code
            // (owner decision 2026-10: claiming one paper prescription never links the next ones to the account)
            base["name"] = p.name
            base["age"] = p.age
            base["gender"] = p.gender
        } else {
            throw ctx.err(400, "Choose the patient", "রোগী বেছে নিন")
        }
        val patient = RxRules.parsePatient(ctx.lang, RxRules.mergePatient(base, b["patient"]))
        val medicines = RxRules.parseMedicines(ctx.lang, b["medicines"])
        val tests = RxRules.parseTests(ctx.lang, b["tests"])
        val advice = RxRules.str(b["advice"], 1000)
        if (medicines.isEmpty() && tests.isEmpty() && advice.isEmpty()) {
            throw ctx.err(400, "Add a medicine, a test or advice", "অন্তত একটা ওষুধ, পরীক্ষা বা পরামর্শ দিন")
        }
        val fd = b.int("followUpDays")
        val followUpDays = if (fd != null && fd > 0 && fd <= 365) fd else 0L

        val prov = provider(uid)
        val chamberList = if (prov != null) chambers.chambersOf(uid) else emptyList()
        val askedCi = b.int("chamberIndex")
        val ci = askedCi?.takeIf { it >= 0 && it < chamberList.size }?.toInt()
        val ch = ci?.let { chamberList[it] }
        val regNo = jdbc.queryForList("SELECT reg_no FROM badges WHERE uid = ?", String::class.java, uid).firstOrNull() ?: ""
        quotas.use(ctx.lang, uid, "rx")
        val now = System.currentTimeMillis()
        // the follow-up day: when the chamber is closed that day, the next open day is offered for booking
        val followUpDate = if (followUpDays > 0) RxRules.dhakaToday(now) + followUpDays * RxRules.DAY_MS else null
        var followUpBookDate: Long? = null
        if (followUpDate != null && ch != null && ch.appointments && chambers.takesAppointments(uid)) {
            var d = followUpDate
            for (i in 0 until 14) {
                if (chambers.chamberOpenOn(ch, d)) {
                    followUpBookDate = d
                    break
                }
                d += RxRules.DAY_MS
            }
        }
        val doctorName = prov?.name ?: ""
        val code = store.uniqueCode()
        val id = store.insert(
            PrescriptionStore.NewRx(
                code = code,
                createdAt = now,
                doctorUid = uid,
                doctorName = doctorName,
                doctorDegrees = prov?.degrees ?: "",
                doctorSpecialties = prov?.specialties ?: emptyList(),
                doctorRegNo = regNo,
                chamberName = ch?.let { it.hospitalNameBn.ifEmpty { it.hospitalName } },
                chamberAddress = ch?.let { listOf(it.district).filter { s -> s.isNotEmpty() }.joinToString(", ") },
                chamberPhone = ch?.serialPhone,
                patient = patient,
                patientUid = patientUid,
                rxPatientId = rxPatientId,
                appointmentId = appointmentId,
                complaints = RxRules.str(b["complaints"], 1000),
                findings = RxRules.parseFindings(b["findings"]),
                diagnosis = RxRules.str(b["diagnosis"], 500),
                medicines = medicines,
                tests = tests,
                testPlace = RxRules.str(b["testPlace"], 200),
                testPlaceId = RxRules.testPlaceId(b["testPlaceId"]),
                advice = advice,
                followUpDate = followUpDate,
                followUpBookDate = followUpBookDate,
                followUpNote = if (followUpDays > 0) RxRules.str(b["followUpNote"], 80) else "",
                chamberIndex = ci,
            ),
        )
        if (rxPatientId != null) jdbc.update("UPDATE rx_patients SET last_rx_at = ? WHERE id = ?", P3Sql.ts(now), rxPatientId)
        if (patientUid != null) {
            push.patient(patientUid, "New prescription from $doctorName", "$doctorName নতুন প্রেসক্রিপশন দিয়েছেন", "", linkedMapOf("type" to "rx", "id" to id, "rxId" to id))
        }
        return linkedMapOf("id" to id, "code" to code)
    }

    fun rxCancel(ctx: ActionContext): Any {
        val id = ctx.body.string("id")
        if (!Rules.isValidId(id)) throw notFound(ctx)
        val s = store.get(id!!)
        if (s == null || s.doctorUid != ctx.user.id) throw notFound(ctx)
        if (s.status != "cancelled") jdbc.update("UPDATE prescriptions SET status = 'cancelled', cancelled_at = now() WHERE id = ?", id)
        return linkedMapOf("ok" to true)
    }

    /** the doctor's recent prescriptions */
    fun rxDoctorList(ctx: ActionContext): Any {
        val uid = ctx.user.id
        requireDoctor(ctx, uid)
        return linkedMapOf("items" to views(store.load("doctor_uid = ? ORDER BY created_at DESC LIMIT 150", uid)))
    }

    /** the patient: my prescriptions */
    fun rxMine(ctx: ActionContext): Any =
        linkedMapOf("items" to views(store.load("patient_uid = ? ORDER BY created_at DESC LIMIT 300", ctx.user.id)))

    /**
     * the patient adds one paper/shared prescription by its code + the phone number given to the assistant (the
     * profile's or a family member's number). Only that prescription comes to the account: earlier and later
     * prescriptions of the same paper patient are added one by one with their own codes (owner decision 2026-10 —
     * before, a claim linked the paper patient and every later prescription followed automatically).
     */
    fun rxClaim(ctx: ActionContext): Any {
        val uid = ctx.user.id
        val code = Prescriptions.normalizeCode(Js.toStr(ctx.body["code"]))
        if (!Prescriptions.CODE_RE.matches(code)) throw ctx.err(400, "Check the code on the prescription", "প্রেসক্রিপশনের কোডটা দেখে লিখুন")
        quotas.use(ctx.lang, uid, "rxClaim")
        val phone = Phones.normalizeBd(Js.toStr(ctx.body["phone"]))
        val own = jdbc.queryForList("SELECT phone FROM user_profiles WHERE uid = ?", String::class.java, uid)
        val family = jdbc.queryForList("SELECT phone FROM user_family_members WHERE uid = ? ORDER BY position", String::class.java, uid)
        val allowed = (own + family).mapNotNull { Phones.normalizeBd(it) }
        fun notMatch() = ctx.err(400, "The code and phone number don't match", "কোড আর ফোন নম্বর মেলেনি")
        if (phone == null || phone !in allowed) {
            throw ctx.err(400, "Use the mobile number in your profile or a family member's number", "প্রোফাইলের বা পরিবারের সদস্যের মোবাইল নম্বরটাই দিন")
        }
        val found = jdbc.rows("SELECT id, patient_uid, rx_patient_id, doctor_uid FROM prescriptions WHERE code = ?", code) { rs ->
            Triple(rs.getString("id"), rs.getString("patient_uid"), rs.getString("rx_patient_id")) to rs.getString("doctor_uid")
        }.firstOrNull() ?: throw notMatch()
        // demo and real worlds never mix: a demo account (public login) must not pull in a real prescription
        if (!DemoWorld.sameWorld(uid, found.second)) throw notMatch()
        val rx = found.first
        if (rx.second == uid) return linkedMapOf("ok" to true, "added" to 0)
        val pid = rx.third ?: throw notMatch()
        val p = rxPatientById(pid)
        if (p == null || p.phoneKey != phoneKey(phone)) throw notMatch()
        if (rx.second != null) {
            throw ctx.err(409, "This prescription is already added to another account", "এই প্রেসক্রিপশন আরেকটা অ্যাকাউন্টে যোগ করা আছে")
        }
        // only if still unclaimed (two accounts at the same moment: the first wins, the second gets 409)
        val added = jdbc.update("UPDATE prescriptions SET patient_uid = ? WHERE id = ? AND patient_uid IS NULL", uid, rx.first)
        if (added == 0) throw ctx.err(409, "This prescription is already added to another account", "এই প্রেসক্রিপশন আরেকটা অ্যাকাউন্টে যোগ করা আছে")
        return linkedMapOf("ok" to true, "added" to added)
    }

    /** one prescription (patient or writing doctor); the doctor also gets its lab orders (history rules) */
    fun rxGet(ctx: ActionContext): Any {
        val uid = ctx.user.id
        val id = ctx.body.string("id")
        if (!Rules.isValidId(id)) throw notFound(ctx)
        val s = store.get(id!!)
        if (s == null || (s.patientUid != uid && s.doctorUid != uid)) throw notFound(ctx)
        // a caregiver: the patient's own prescriptions only, never ones the patient wrote as a doctor
        if (ctx.user.patientSideOnly && s.patientUid != uid) throw notFound(ctx)
        if (s.doctorUid == uid && s.patientUid != uid) {
            return linkedMapOf("item" to store.view(s), "labOrders" to labs.labsForDoctor(uid, listOf(s.id)))
        }
        return linkedMapOf("item" to store.view(s))
    }

    /** the patient: pharmacy/lab requests of this prescription, reports and the doctor's review; next chamber day */
    fun rxActivity(ctx: ActionContext): Any {
        val uid = ctx.user.id
        val rxId = ctx.body.string("rxId")
        if (!Rules.isValidId(rxId)) throw notFound(ctx)
        val s = store.get(rxId!!)
        if (s == null || (s.patientUid != uid && s.doctorUid != uid)) throw notFound(ctx)
        if (ctx.user.patientSideOnly && s.patientUid != uid) throw notFound(ctx)
        val isPatient = s.patientUid == uid
        val orders = jdbc.rows(
            "SELECT id, pharmacy_uid, pharmacy_name, status, reason, created_at, mode, price, eta, delivery_fee, missed FROM rx_orders WHERE rx_id = ? ORDER BY id COLLATE \"C\" LIMIT 50",
            rxId,
        ) { rs ->
            linkedMapOf<String, Any?>(
                "id" to rs.getString("id"), "pharmacyId" to rs.getString("pharmacy_uid"), "pharmacyName" to rs.getString("pharmacy_name"),
                "status" to rs.getString("status"), "reason" to (rs.getString("reason") ?: ""), "createdAt" to rs.getTimestamp("created_at").time,
                "mode" to (rs.getString("mode") ?: "shop"), "price" to (rs.getString("price") ?: ""), "eta" to (rs.getString("eta") ?: ""),
                "deliveryFee" to (rs.getString("delivery_fee") ?: ""), "missed" to (rs.stringArray("missed") ?: emptyList()),
            )
        }
        val labOrders = labStore.orders("rx_id = ? ORDER BY id COLLATE \"C\" LIMIT 50", rxId)
        val reviews = labStore.reviewsOf(labOrders.map { it.id })
        // the next open chamber day (a serial for showing the report)
        var nextOpen: Long? = null
        val ci = s.chamberIndex
        if (ci != null) {
            val ch = chambers.chambersOf(s.doctorUid).getOrNull(ci)
            if (ch != null && ch.appointments && chambers.takesAppointments(s.doctorUid)) {
                var d = RxRules.dhakaToday()
                for (i in 0 until 14) {
                    if (chambers.chamberOpenOn(ch, d)) {
                        nextOpen = d
                        break
                    }
                    d += RxRules.DAY_MS
                }
            }
        }
        val now = System.currentTimeMillis()
        return linkedMapOf(
            "medicines" to if (isPatient) RxRules.medStatus(s.medicines, s.dispensed).map { it.toMap() } else emptyList<Any>(),
            "dispensed" to if (isPatient) s.dispensed.map { linkedMapOf("name" to it.name, "at" to it.at, "items" to it.itemsJson()) } else emptyList<Any>(),
            "orders" to if (isPatient) orders.sortedByDescending { it["createdAt"] as Long } else emptyList<Any>(),
            // the home address/phone by order: whoever sent it (the order's patient), not the prescription owner
            "labOrders" to labOrders.filter { isPatient || reviews[it.id]?.status in LabViews.DOCTOR_SEES }
                .map { LabViews.orderView(it, false, if (it.patientUid == uid) "patient" else null, now) },
            "reviews" to labOrders.mapNotNull { reviews[it.id] }.map { it.view() },
            "nextOpen" to nextOpen,
        )
    }
}
