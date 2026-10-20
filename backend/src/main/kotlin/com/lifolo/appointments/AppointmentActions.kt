package com.lifolo.appointments

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lifolo.admin.kernel.Catalog
import com.lifolo.admin.kernel.ListingRules
import com.lifolo.admin.kernel.Sanctions
import com.lifolo.common.DhakaTime
import com.lifolo.common.Ids
import com.lifolo.common.Js
import com.lifolo.common.Phones
import com.lifolo.common.Places
import com.lifolo.common.QuotaService
import com.lifolo.common.Rules
import com.lifolo.common.Sql
import com.lifolo.common.StoredFiles
import com.lifolo.common.action.ActionContext
import com.lifolo.common.action.ActionHandler
import com.lifolo.common.action.ActionModule
import com.lifolo.contracts.HospitalDirectory
import com.lifolo.demo.DemoWorld
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * appointments.js — doctors/nutritionists/trainers list their chambers, patients request an appointment, the
 * professional confirms (serial number) or declines. No money in the app; once confirmed both see each other's phone.
 * Actions: providers, provider_me, provider_save, book, my_appointments, appointment_update.
 */
@Component
class AppointmentActions(
    private val jdbc: JdbcTemplate,
    private val store: ProviderStore,
    private val appts: ApptRepo,
    private val cancel: ApptCancel,
    private val badges: BadgeLookup,
    private val users: P2Users,
    private val clock: P2Clock,
    private val cache: P2Cache,
    private val quotas: QuotaService,
    private val catalog: Catalog,
    private val hospitals: HospitalDirectory,
    private val sanctions: Sanctions,
    private val phone: PhoneCheck,
    private val files: StoredFiles,
    txm: PlatformTransactionManager,
) : ActionModule {
    private val tx = TransactionTemplate(txm)

    override fun actions(): Map<String, ActionHandler> = mapOf(
        "providers" to ActionHandler(::providers),
        "provider_me" to ActionHandler(::providerMe),
        "provider_save" to ActionHandler(::providerSave),
        "book" to ActionHandler(::book),
        "my_appointments" to ActionHandler(::myAppointments),
        "appointment_update" to ActionHandler(::appointmentUpdate),
    )

    companion object {
        /** who gets "my chamber / home care" */
        val PROVIDER_TYPES = listOf("doctor", "nurse", "nutritionist", "trainer")

        /** chambers and appointments: doctor, nutritionist, fitness trainer; nurses only do home care (16 Oct 2026) */
        val CHAMBER_TYPES = listOf("doctor", "nutritionist", "trainer")

        /** home care: doctors and nurses */
        val HOME_TYPES = listOf("doctor", "nurse")
        const val DAY_MS = 24L * 60 * 60 * 1000

        /** appointments.js provider(d): what the app shows of a provider */
        fun providerOut(p: ProviderDoc): LinkedHashMap<String, Any?> {
            val first = p.chambers.firstOrNull()
            val count = p.ratingCount
            val m = LinkedHashMap<String, Any?>()
            m["uid"] = p.uid
            m["type"] = p.type
            m["photo"] = p.photoThumb
            m["name"] = p.name
            m["specialties"] = p.specialties
            m["degrees"] = p.degrees
            m["gender"] = p.gender
            m["listed"] = CareRules.isListed(p)
            m["appointments"] = CareRules.takesAppointments(p)
            m["chambers"] = p.chambers.map { it.toMap() }
            // the own setting is needed even when off; the phone only while on (then it is public in the list anyway)
            m["homeVisit"] = p.homeVisit?.let { hv ->
                hv.toMap().apply { put("phone", if (hv.enabled && !p.homeVisitBlocked) Phones.fixPhone(hv.phone) else "") }
            }
            m["rating"] = if (count != 0) Js.round(p.ratingSum.toDouble() / count * 10) / 10 else 0
            m["ratingCount"] = count
            // for old app versions
            m["specialty"] = p.specialty?.takeIf { it.isNotEmpty() } ?: p.specialties.joinToString(", ") { it.replace('_', ' ') }
            m["chamber"] = first?.hospitalName ?: ""
            m["district"] = first?.district ?: ""
            m["schedule"] = CareRules.scheduleText(first)
            m["fee"] = first?.fee ?: ""
            m["active"] = CareRules.isListed(p) && CareRules.takesAppointments(p)
            return m
        }
    }

    private fun err400(ctx: ActionContext, e: Err) = ctx.err(400, e.en, e.bn)

    // ------------------------------------------------------------------ providers

    /** the whole list of one type (approved badge of the same type), with the card photo — 2 minutes in the cache */
    fun providerList(type: String): List<ProviderDoc> =
        cache.cached(P2Cache.providersKey(type), P2Cache.TWO_MINUTES, object : TypeReference<List<ProviderDoc>>() {}) {
            val docs = store.loadWhere(
                "p.type = ? AND (p.listed OR p.active) AND EXISTS (SELECT 1 FROM badges b WHERE b.uid = p.uid AND b.status = 'approved' AND b.type = ?) ORDER BY p.uid LIMIT 1300",
                type, type,
            )
            val thumbs = thumbs(docs.map { it.uid })
            docs.map { it.copy(photoThumb = thumbs[it.uid] ?: "") }
        }

    private fun thumbs(uids: List<String>): Map<String, String> {
        val out = HashMap<String, String>()
        val keys = HashMap<String, String>()
        for (chunk in uids.chunked(1000)) {
            jdbc.query(
                "SELECT uid, photo_thumb, photo_thumb_key FROM user_profiles WHERE uid IN (${chunk.joinToString(",") { "?" }}) " +
                    "AND (photo_thumb IS NOT NULL OR photo_thumb_key IS NOT NULL)",
                RowCallbackHandler { rs ->
                    val data = rs.getString(2)
                    if (data != null) out[rs.getString(1)] = data else keys[rs.getString(1)] = rs.getString(3)
                },
                *chunk.toTypedArray(),
            )
        }
        // thumbs on R2 (V602); one that cannot be read shows like no photo
        if (keys.isNotEmpty()) {
            val texts = files.texts(keys.values)
            for ((uid, key) in keys) out[uid] = texts[key] ?: ""
        }
        return out
    }

    /**
     * Doctor list. Filters: district, upazilaId, unionId, hospitalId, specialty; 2.33.0: day ('today' | 'tomorrow'),
     * openNow, maxFee, hospitalQ, gender (care.js chamberPasses); homeVisit: true = only home visitors (old apps).
     */
    fun providers(ctx: ActionContext): Any {
        val b = ctx.body
        val type = b.string("type")
        if (type == null || type !in PROVIDER_TYPES) throw ctx.err(400, "Choose doctor or nurse", "ডাক্তার বা নার্স বেছে নিন")
        val homeVisitTrue = b.isTrue("homeVisit")
        // nurses have no chambers — the old apps' "nurse" list is empty (nurses are in "home care")
        if (type !in CHAMBER_TYPES && !homeVisitTrue) return mapOf("items" to emptyList<Any>())
        val base = ListFilter(
            district = b.string("district")?.takeIf { Places.isDistrict(it) },
            upazilaId = b.int("upazilaId"),
            unionId = b.int("unionId"),
            hospitalId = b.string("hospitalId")?.takeIf { Rules.isValidId(it) },
            specialty = b.string("specialty")?.takeIf { it in CareRules.specialtiesFor(type, catalog) },
        )
        val f = CareRules.pickListFilters(b.node, base)
        val all = providerList(type)
        // old apps (no homeVisit field) book anyone, so they only see people who take appointments
        val oldClient = !b.has("homeVisit")
        val now = clock.now()
        val areaOnly = ListFilter(district = f.district, upazilaId = f.upazilaId, unionId = f.unionId, hospitalId = f.hospitalId)
        val pool = all.filter { x ->
            when {
                x.adminSuspended || !CareRules.isListed(x) || !CareRules.matchesFilter(x, areaOnly, now) -> false
                oldClient && !(CareRules.takesAppointments(x) && x.chambers.any { it.appointments }) -> false
                homeVisitTrue && !(x.homeVisit?.enabled == true && !x.homeVisitBlocked) -> false
                // home care only (no chamber) — not in the chamber list
                !homeVisitTrue && x.chambers.isEmpty() -> false
                else -> true
            }
        }
        // specialty chips: what this area's list really has (before specialty/day/fee filters), most first
        val spCount = LinkedHashMap<String, Int>()
        pool.forEach { d -> d.specialties.forEach { code -> spCount[code] = (spCount[code] ?: 0) + 1 } }
        val facets = linkedMapOf(
            "specialties" to spCount.entries
                .sortedWith(Comparator { a, c -> if (c.value != a.value) c.value - a.value else P2Js.localeCompare(a.key, c.key) })
                .map { linkedMapOf("code" to it.key, "n" to it.value) },
            "gender" to pool.any { it.gender == "male" || it.gender == "female" },
        )
        val docs = pool.filter { CareRules.matchesFilter(it, f, now) }
            .sortedWith(Comparator { p, q -> if (q.ratingCount != p.ratingCount) q.ratingCount - p.ratingCount else P2Js.localeCompare(p.name, q.name) })
        val items = docs.map { d -> providerOut(d).apply { put("matched", CareRules.matchedChambers(d, f, now)) } }
        return linkedMapOf("items" to items.take(150), "facets" to facets)
    }

    /** own provider document (null when none) */
    fun providerMe(ctx: ActionContext): Any = mapOf("provider" to store.load(ctx.user.id)?.let { providerOut(it) })

    // ------------------------------------------------------------------ provider_save

    /** does the document have `chambers[]` (not a legacy or chamber-less document)? */
    private fun hasChambersArray(p: ProviderDoc): Boolean = p.chamberMode == false || (p.chamberMode == null && p.chambers.isNotEmpty())

    /** old app versions (one chamber, free text) */
    private fun legacySave(ctx: ActionContext, uid: String, badge: String): Any {
        val b = ctx.body
        if (badge !in CHAMBER_TYPES) {
            throw ctx.err(409, "Nurses don't have chambers now. Update the app and turn on home care.", "নার্সের চেম্বার আর নেই। অ্যাপ আপডেট করে বাসায় সেবা চালু করুন।", "update_required")
        }
        val chamber = Rules.cleanText(b.string("chamber"), 120) ?: throw ctx.err(400, "Enter your chamber / hospital", "চেম্বার / হাসপাতালের নাম দিন")
        val schedule = Rules.cleanText(b.string("schedule"), 120) ?: throw ctx.err(400, "Enter your days and time", "কোন দিন ও কখন বসেন লিখুন")
        val district = b.string("district")
        if (!Places.isDistrict(district)) throw ctx.err(400, "Choose a district", "জেলা বেছে নিন")
        val cur = store.load(uid)
        // turned off by an admin or badge rejected: an old app cannot switch it on again
        if (cur != null && (cur.adminSuspended || cur.badgeRejected)) {
            throw ctx.err(403, "Your listing is turned off by the Lifolo team", "আপনার তালিকা Lifolo টিম বন্ধ রেখেছে")
        }
        if (cur != null && hasChambersArray(cur)) throw ctx.err(409, "Please update the app to edit your chambers", "চেম্বার বদলাতে অ্যাপ আপডেট করুন")
        val active = b["active"]?.let { !(it.isBoolean && !it.booleanValue()) } ?: true
        val now = clock.now()
        tx.executeWithoutResult {
            jdbc.update(
                """
                INSERT INTO providers (uid, type, name, specialty, active, listed, appointments, legacy_chamber, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, TRUE, ?)
                ON CONFLICT (uid) DO UPDATE SET type = EXCLUDED.type, name = EXCLUDED.name, specialty = EXCLUDED.specialty,
                  active = EXCLUDED.active, listed = EXCLUDED.listed, appointments = EXCLUDED.appointments, legacy_chamber = TRUE,
                  updated_at = EXCLUDED.updated_at
                """.trimIndent(),
                uid, badge, users.name(uid).take(80), b.string("specialty")?.let { ListingRules.jsTrim(it).take(80) } ?: "",
                active, active, active, Sql.ts(now),
            )
            store.saveLegacyChamber(uid, chamber, district!!, schedule, b.string("fee")?.let { ListingRules.jsTrim(it).take(40) } ?: "")
        }
        return mapOf("ok" to true)
    }

    fun providerSave(ctx: ActionContext): Any {
        val uid = ctx.user.id
        // this server's cached lists anew (one's own change shows at once)
        cache.clearProviders()
        val badge = badges.approvedBadge(uid)
        if (badge == null || badge !in PROVIDER_TYPES) {
            throw ctx.err(403, "Only verified professionals can add chambers", "শুধু যাচাই করা পেশাজীবীরা চেম্বার যোগ করতে পারেন")
        }
        val b = ctx.body
        val chNode = b["chambers"]
        if (chNode == null || !chNode.isArray) return legacySave(ctx, uid, badge)
        // app 1.4 sends no days/times — ask to update
        if (chNode.any { c -> Js.truthy(c) && (c as? ObjectNode)?.get("days")?.isArray != true }) {
            throw ctx.err(409, "Please update the app to save your chambers", "চেম্বার সেভ করতে অ্যাপ আপডেট করুন", "update_required")
        }
        val ids = chNode.mapNotNull { c -> Js.string((c as? ObjectNode)?.get("hospitalId")) }.filter { Rules.isValidId(it) }.distinct()
            .take(CareRules.MAX_CHAMBERS)
        val hosp = ids.mapNotNull { hospitals.findHospital(it) }
            .associate { it.id to Place(it.id, it.nameEn, it.nameBn, it.district, it.upazilaId, it.unionId) }
        val specialties = CareRules.pickCodes(b["specialties"], CareRules.specialtiesFor(badge, catalog), 5)
        if (specialties.isEmpty()) throw ctx.err(400, "Choose at least one specialty", "অন্তত একটা বিশেষজ্ঞতা বেছে নিন")
        val hvNode = b["homeVisit"]
        val hvObj = hvNode as? ObjectNode
        val hvOn = hvObj?.get("enabled")?.let { it.isBoolean && it.booleanValue() } ?: false
        // apps up to 2.1 have no separate phone field — ask to update
        if (hvOn && hvObj?.has("phone") != true) {
            throw ctx.err(409, "Please update the app to turn on home care", "বাসায় সেবা চালু করতে অ্যাপ আপডেট করুন", "update_required")
        }
        // no home care for nutritionists / trainers
        if (badge !in HOME_TYPES && hvOn) throw ctx.err(400, "Home care is for doctors and nurses", "বাসায় সেবা শুধু ডাক্তার আর নার্সের")
        val hv = CareRules.parseHomeVisit(if (badge in HOME_TYPES) hvNode else null, badge, catalog)
        if (hv.error != null) throw err400(ctx, hv.error)
        val homeVisit = hv.value!!
        val keepHome = !b.has("homeVisit")
        // home visits only = no chamber needed; nurses have no chambers (dropped if sent)
        val nurse = badge !in CHAMBER_TYPES
        if (nurse && !homeVisit.enabled && !keepHome) throw ctx.err(400, "Turn on home care", "বাসায় সেবা চালু করুন")
        val parsed: List<Ch> = if (nurse || (chNode.size() == 0 && homeVisit.enabled)) {
            emptyList()
        } else {
            val r = CareRules.parseChambers(chNode, hosp, badge != "doctor")
            if (r.error != null) throw err400(ctx, r.error)
            r.value!!
        }
        val gender = b.string("gender")?.takeIf { it == "male" || it == "female" } ?: ""
        if (homeVisit.enabled && hvObj?.get("termsAccepted")?.let { it.isBoolean && it.booleanValue() } != true) {
            throw ctx.err(400, "Please accept the home care terms", "বাসায় সেবার শর্তে সম্মতি দিন")
        }
        if (homeVisit.enabled) {
            // home care was turned off for this email after a report (an earlier account)
            if (sanctions.forEmail(ctx.user.email)["homeCare"] == true) {
                throw ctx.err(
                    400,
                    "Home care was turned off for this email after a report. You can appeal from Profile → Help & appeals.",
                    "রিপোর্টের পর এই ইমেইলে বাসায় সেবা বন্ধ আছে। প্রোফাইলের \"সাহায্য ও আপিল\" থেকে আপিল করতে পারেন।",
                )
            }
            phone.requireVerified(ctx.lang, uid)
            val loc = jdbc.queryForList("SELECT lat, location_source FROM user_profiles WHERE uid = ?", uid).firstOrNull()
            val gps = loc?.get("lat") != null && loc["location_source"] == "gps"
            if (!gps && homeVisit.upazilaIds.isEmpty()) {
                throw ctx.err(400, "Set your current location or choose upazilas for home visits", "বাসায় সেবার জন্য বর্তমান লোকেশন দিন অথবা উপজেলা বেছে নিন")
            }
        }
        val listed = if (nurse) false else b["listed"]?.let { !(it.isBoolean && !it.booleanValue()) } ?: true
        val takes = if (nurse) false else b.isTrue("appointments")
        // the verified (badge) name — a later profile name change does not change the list
        val badgeName = jdbc.queryForList("SELECT name FROM badges WHERE uid = ?", String::class.java, uid).firstOrNull() ?: ""
        val name = badgeName.ifEmpty { users.name(uid) }.take(80)
        val now = clock.now()
        var saved: List<Ch> = parsed
        // read and write in one transaction, so an admin switching it off at the same time is not undone
        tx.executeWithoutResult {
            val cur = store.load(uid, forUpdate = true)
            if (cur != null && cur.adminSuspended) {
                throw ctx.err(
                    403,
                    "Your listing is turned off by the Lifolo team. You can appeal from Profile → Help & appeals.",
                    "Lifolo টিম আপনার তালিকা বন্ধ রেখেছে। প্রোফাইলের \"সাহায্য ও আপিল\" থেকে আপিল করতে পারেন।",
                )
            }
            if (homeVisit.enabled && cur != null && cur.homeVisitBlocked) {
                throw ctx.err(
                    400,
                    "Home care is turned off for your account after a report. You can appeal from Profile → Help & appeals.",
                    "রিপোর্টের পর আপনার বাসায় সেবা বন্ধ আছে। প্রোফাইলের \"সাহায্য ও আপিল\" থেকে আপিল করতে পারেন।",
                )
            }
            // old apps send no "minutes per patient" — keep the earlier one of the same chamber
            val before = cur?.chambers ?: emptyList()
            saved = parsed.mapIndexed { i, c ->
                val sent = chNode[i]
                if (Js.truthy(sent) && (sent as? ObjectNode)?.has("patientMin") == true) {
                    c
                } else {
                    val old = before.firstOrNull { it.key == c.key }
                    if (old?.patientMin != null) c.copy(patientMin = old.patientMin) else c
                }
            }
            jdbc.update(
                """
                INSERT INTO providers (uid, type, name, listed, appointments, active, specialties, degrees, gender, legacy_chamber, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?::text[], ?, ?, FALSE, ?)
                ON CONFLICT (uid) DO UPDATE SET type = EXCLUDED.type, name = EXCLUDED.name, listed = EXCLUDED.listed,
                  appointments = EXCLUDED.appointments, active = EXCLUDED.active, specialties = EXCLUDED.specialties,
                  degrees = EXCLUDED.degrees, gender = EXCLUDED.gender, legacy_chamber = FALSE, updated_at = EXCLUDED.updated_at
                """.trimIndent(),
                uid, badge, name, listed, takes, listed && takes, P2Js.pgTexts(specialties), CareRules.str(b["degrees"], 120),
                gender.ifEmpty { null }, Sql.ts(now),
            )
            store.saveChambers(uid, saved)
            if (!keepHome) store.saveHome(uid, homeVisit, if (homeVisit.enabled) now else null)
        }
        // a chamber removed, days changed, list or appointments off: cancel what no longer holds and tell the patients
        val chambers = saved
        val cancelled = cancel.cancelStale(
            uid,
            { a -> listed && takes && cancel.stillValid(a, chambers) },
            "The doctor changed the chamber or days",
            "ডাক্তার চেম্বার বা বার বদলেছেন",
        )
        return linkedMapOf("ok" to true, "cancelled" to cancelled)
    }

    // ------------------------------------------------------------------ book

    /** a patient's request */
    fun book(ctx: ActionContext): Any {
        val user = ctx.user
        val uid = user.id
        val b = ctx.body
        val providerUid = b.string("providerUid")
        if (!Rules.isValidId(providerUid)) throw ctx.err(400, "Not found", "পাওয়া যায়নি")
        providerUid!!
        if (providerUid == uid) throw ctx.err(400, "You can't book yourself", "নিজের কাছে অ্যাপয়েন্টমেন্ট নেওয়া যায় না")
        // demo: demo patients only demo doctors, real patients only real doctors
        if (!DemoWorld.sameWorld(uid, providerUid)) throw ctx.err(404, "Not taking appointments right now", "এখন অ্যাপয়েন্টমেন্ট নিচ্ছেন না")
        val now = clock.now()
        val today = DhakaTime.todayUtcMidnight(now)
        // the date = start of the day (no second serial/booking on the same day with another number)
        val date = b.int("date")
        if (date == null || date % DAY_MS != 0L || date < today || date > today + 60 * DAY_MS) {
            throw ctx.err(400, "Pick a date within the next 60 days", "আগামী ৬০ দিনের মধ্যে তারিখ দিন")
        }
        val note = Rules.cleanText(b.string("note"), 300) ?: throw ctx.err(400, "Write the reason for the visit", "কী সমস্যা, সংক্ষেপে লিখুন")
        val ps = store.load(providerUid)
        val notTaking = { ctx.err(404, "Not taking appointments right now", "এখন অ্যাপয়েন্টমেন্ট নিচ্ছেন না") }
        // turned off by an admin, or the badge is of another profession: no booking
        if (ps == null || ps.adminSuspended || !CareRules.isListed(ps) || !CareRules.takesAppointments(ps) ||
            badges.approvedBadge(providerUid) != ps.type || ps.type !in CHAMBER_TYPES
        ) {
            throw notTaking()
        }
        val chambers = ps.chambers
        val chamberIndex = b.int("chamberIndex")
        val ch = if (chamberIndex == null) {
            chambers.firstOrNull { it.appointments }
        } else {
            if (chamberIndex in 0 until chambers.size) chambers[chamberIndex.toInt()] else null
        }
        if (ch == null || !ch.appointments) throw notTaking()
        // the doctor changed the chambers while the page was open — the order shifts; match the hospital
        val sentKey = b.string("chamberKey")
        if (!sentKey.isNullOrEmpty() && sentKey != ch.key) {
            throw ctx.err(409, "The doctor just changed their chambers. Please open the page again.", "ডাক্তার এইমাত্র চেম্বারের তথ্য বদলেছেন। পাতাটা আবার খুলুন।")
        }
        if (!CareRules.chamberOpenOn(ch, date)) {
            throw ctx.err(400, "The chamber is closed on that day. Pick one of its days.", "সেদিন চেম্বার বন্ধ। চেম্বারের বারগুলোর একটা বেছে নিন।")
        }
        // today's chamber time over: no serial for today (except chambers past midnight); the serial starts with the
        // first session of the day
        val slotsToday = CareRules.chamberTimesOn(ch, date).sortedBy { it.start }
        val hm = CareRules.dhakaHHMM(now)
        fun over(t: Slot) = t.start.isNotEmpty() && t.end.isNotEmpty() && t.start < t.end && hm >= t.end
        val slot = if (date == today) slotsToday.firstOrNull { !over(it) } else slotsToday.firstOrNull()
        val slotStart = slot?.start?.takeIf { it.isNotEmpty() } ?: ch.start?.takeIf { it.isNotEmpty() } ?: ""
        if (date == today && slotsToday.isNotEmpty() && slotsToday.all { over(it) }) {
            throw ctx.err(400, "Today's chamber time is over. Pick another day.", "আজকের চেম্বারের সময় শেষ। অন্য দিন বেছে নিন।")
        }
        // not more than one open request with the same doctor on the same day
        val dup = jdbc.query(
            "SELECT status, member_id FROM appointments WHERE patient_uid = ? AND provider_uid = ? AND visit_date = ? LIMIT 10",
            { rs, _ -> rs.getString(1) to rs.getString(2) },
            uid, providerUid, Sql.date(DhakaTime.fromUtcMidnightMs(date)),
        )
        val forMember = b.string("memberId")?.takeIf { it.isNotEmpty() }
        if (dup.any { (st, mid) -> (st == "pending" || st == "confirmed") && mid == forMember }) {
            throw ctx.err(409, "You already have a request with this doctor for that day", "সেদিন এই ডাক্তারের কাছে আপনার অনুরোধ আগে থেকেই আছে")
        }
        // a family member (no account of their own)
        var member: Map<String, Any?>? = null
        if (forMember != null) {
            member = jdbc.queryForList("SELECT member_id, name, age, gender FROM user_family_members WHERE uid = ? AND member_id = ?", uid, forMember)
                .firstOrNull() ?: throw ctx.err(404, "Family member not found", "পরিবারের সদস্য পাওয়া যায়নি")
        }
        quotas.use(ctx.lang, uid, "book")
        val name = ((member?.get("name") as? String) ?: users.name(uid)).take(80)
        val id = Ids.newId()
        jdbc.update(
            """
            INSERT INTO appointments (id, patient_uid, provider_uid, patient_name, provider_name, member_id, patient_age, patient_gender,
              by_caretaker, type, chamber, chamber_bn, chamber_key, start_time, room, schedule, visit_date, note, status, provider_note, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending', '', ?)
            """.trimIndent(),
            id, uid, providerUid, name, ps.name, member?.get("member_id"), (member?.get("age") as? Number)?.toInt(),
            (member?.get("gender") as? String) ?: "", user.actingBy, ps.type, ch.hospitalName, ch.hospitalNameBn, ch.key, slotStart,
            ch.room, ch.schedule, Sql.date(DhakaTime.fromUtcMidnightMs(date)), note, Sql.ts(now),
        )
        users.push(providerUid, "New appointment request", "নতুন অ্যাপয়েন্টমেন্টের অনুরোধ", "$name · ${ApptRepo.dateLabel(date)}", mapOf("type" to "appointment", "role" to "provider"))
        return mapOf("id" to id)
    }

    // ------------------------------------------------------------------ my_appointments

    /** own appointments: as a patient and as a doctor */
    fun myAppointments(ctx: ActionContext): Any {
        val uid = ctx.user.id
        // all open ones (pending/confirmed); older ones a few per status — old records never push out new requests
        fun byStatus(field: String): List<Appt> {
            val out = ArrayList<Appt>()
            for ((st, limit) in listOf("pending" to 300, "confirmed" to 300, "declined" to 30, "cancelled" to 30, "expired" to 30, "completed" to 30)) {
                out.addAll(
                    jdbc.query(
                        "SELECT * FROM appointments WHERE $field = ? AND status = ? ORDER BY created_at DESC, id LIMIT ?",
                        { rs, _ -> Appt.of(rs) },
                        uid, st, limit,
                    ),
                )
            }
            return out
        }
        val mine = byStatus("patient_uid")
        // a caregiver acts on the patient side only: a doctor's appointments with their patients (phones, notes) never
        val toMe = if (ctx.user.patientSideOnly) emptyList() else byStatus("provider_uid")
        // confirmed: the other party's phone
        val need = LinkedHashSet<String>()
        mine.forEach { if (it.status == "confirmed") need.add(it.providerUid) }
        toMe.forEach { if (it.status == "confirmed") need.add(it.patientUid) }
        val phones = users.profiles(need).mapValues { it.value.phone }
        fun item(a: Appt, role: String): Map<String, Any?> {
            val other = if (role == "patient") a.providerUid else a.patientUid
            return linkedMapOf(
                "id" to a.id,
                "role" to role,
                "otherName" to (if (role == "patient") a.providerName else a.patientName),
                "type" to a.type,
                "chamber" to a.chamber,
                "chamberBn" to a.chamberBn,
                "room" to a.room,
                "start" to a.start,
                "serial" to (a.serial ?: 0),
                "schedule" to a.schedule,
                "date" to a.date,
                "note" to a.note,
                "status" to a.status,
                "providerNote" to a.providerNote,
                "otherPhone" to (if (a.status == "confirmed") phones[other] ?: "" else ""),
                // a doctor sees their patients together (not the real uid, a short key)
                "otherKey" to (if (role == "provider") Ids.sha256Hex("pat:$uid:$other").take(12) else ""),
                "createdAt" to a.createdAt,
            )
        }
        return mapOf("items" to mine.map { item(it, "patient") } + toMe.map { item(it, "provider") })
    }

    // ------------------------------------------------------------------ appointment_update

    /** confirm / decline / cancel */
    fun appointmentUpdate(ctx: ActionContext): Any {
        val uid = ctx.user.id
        val b = ctx.body
        // body.action is the request name ("appointment_update"); what to do is in body.op
        val id = b.string("id")
        if (!Rules.isValidId(id)) throw ctx.err(400, "Not found", "পাওয়া যায়নি")
        val a = appts.get(id!!) ?: throw ctx.err(404, "Not found", "পাওয়া যায়নি")
        val op = b.string("op")
        // a caregiver acts on the patient side only: never confirms/declines/cancels the doctor's own appointments
        if (ctx.user.patientSideOnly && a.patientUid != uid) throw ctx.err(404, "Not found", "পাওয়া যায়নি")
        val isProvider = a.providerUid == uid
        if (isProvider && (op == "confirm" || op == "decline") && badges.approvedBadge(uid) !in PROVIDER_TYPES) {
            throw ctx.err(403, "Your professional badge is not active", "আপনার পেশাদার ব্যাজ এখন চালু নেই")
        }
        val isPatient = a.patientUid == uid
        val note = b.string("providerNote")?.let { ListingRules.jsTrim(it).take(200) } ?: ""
        val next = when {
            isProvider && a.status == "pending" && op == "confirm" -> "confirmed"
            isProvider && a.status == "pending" && op == "decline" -> "declined"
            (isProvider || isPatient) && (a.status == "pending" || a.status == "confirmed") && op == "cancel" -> "cancelled"
            else -> null
        } ?: throw ctx.err(400, "This can't be changed now", "এখন এটা বদলানো যায় না")
        // confirmed: the next serial number of that chamber on that day (in the same transaction — no two get one number)
        // old appointments have no chamberKey — the hospital id by the chamber name, so one chamber counts as one
        var chamberKey = a.chamberKey
        if (chamberKey.isEmpty() && next == "confirmed") {
            val c = store.load(a.providerUid)?.chambers?.firstOrNull { it.hospitalName == a.chamber }
            chamberKey = c?.hospitalId?.takeIf { it.isNotEmpty() } ?: a.chamber
        }
        var serial: Int? = null
        val now = clock.now()
        tx.executeWithoutResult {
            val cur = jdbc.queryForList("SELECT status FROM appointments WHERE id = ? FOR UPDATE", String::class.java, a.id).firstOrNull()
            if (cur != a.status) throw ctx.err(409, "This changed just now. Refresh.", "এইমাত্র বদলেছে। রিফ্রেশ করুন।")
            val providerNote = if (isProvider && note.isNotEmpty()) note else a.providerNote
            if (next == "confirmed") {
                val key = QueueRules.keyOf(chamberKey)
                val day = Sql.date(DhakaTime.fromUtcMidnightMs(a.date))
                jdbc.update(
                    "INSERT INTO serial_counters (provider_uid, visit_date, queue_key, n) VALUES (?, ?, ?, 0) ON CONFLICT DO NOTHING",
                    a.providerUid, day, key,
                )
                val last = jdbc.queryForObject(
                    "SELECT n FROM serial_counters WHERE provider_uid = ? AND visit_date = ? AND queue_key = ? FOR UPDATE",
                    Int::class.java, a.providerUid, day, key,
                ) ?: 0
                // the doctor's own register number (patients booked by phone too); else the next number
                val own = b.int("serial")?.takeIf { it in 1..999 }?.toInt()
                val s = own ?: (last + 1)
                serial = s
                jdbc.update(
                    "UPDATE serial_counters SET n = ?, at = ? WHERE provider_uid = ? AND visit_date = ? AND queue_key = ?",
                    maxOf(last, s), Sql.ts(now), a.providerUid, day, key,
                )
                jdbc.update(
                    "UPDATE appointments SET status = ?, provider_note = ?, updated_at = ?, serial = ? WHERE id = ?",
                    next, providerNote, Sql.ts(now), s, a.id,
                )
            } else {
                jdbc.update("UPDATE appointments SET status = ?, provider_note = ?, updated_at = ? WHERE id = ?", next, providerNote, Sql.ts(now), a.id)
            }
        }
        val to = if (isProvider) a.patientUid else a.providerUid
        val texts = when (next) {
            "confirmed" -> "Appointment confirmed" to "অ্যাপয়েন্টমেন্ট নিশ্চিত হয়েছে"
            "declined" -> "Appointment not accepted" to "অ্যাপয়েন্টমেন্ট নেওয়া হয়নি"
            else -> "Appointment cancelled" to "অ্যাপয়েন্টমেন্ট বাতিল হয়েছে"
        }
        val s = serial
        users.push(
            to, texts.first, texts.second,
            "${if (isProvider) a.providerName else a.patientName} · ${ApptRepo.dateLabel(a.date)}${if (s != null) " · #$s" else ""}",
            mapOf("type" to "appointment", "role" to (if (isProvider) "patient" else "provider")),
        )
        return mapOf("ok" to true)
    }
}
