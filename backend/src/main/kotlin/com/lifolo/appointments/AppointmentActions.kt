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
import com.lifolo.features.Features
import com.lifolo.notify.NotifyCats
import com.lifolo.notify.PushService
import com.lifolo.practice.Practice
import com.lifolo.practice.PracticeGate
import com.lifolo.practice.PracticePrivacy
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
    private val onlineSwitch: OnlineSwitch,
    private val payLog: ApptPayLog,
    private val pushService: PushService,
    private val gate: PracticeGate,
    private val privacy: PracticePrivacy,
    private val payMethods: com.lifolo.payments.PayMethodStore,
    private val payGuard: com.lifolo.payments.PayMethodGuard,
    private val payHooks: org.springframework.beans.factory.ObjectProvider<ApptPayHook>,
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
        /** who gets "my chamber / home care" (+ physio, psychologist: backend/docs/PRACTICE.md) */
        val PROVIDER_TYPES = listOf("doctor", "nurse", "nutritionist", "trainer", "physio", "psychologist")

        /** chambers and appointments: doctor, nutritionist, fitness trainer, physio, psychologist; nurses only do home care (16 Oct 2026) */
        val CHAMBER_TYPES = listOf("doctor", "nutritionist", "trainer", "physio", "psychologist")

        /** home care: doctors, nurses and physios (psychologists: no home visits) */
        val HOME_TYPES = listOf("doctor", "nurse", "physio")
        const val DAY_MS = 24L * 60 * 60 * 1000

        /**
         * appointments.js provider(d): what the app shows of a provider. [own] = provider_me (the doctor's own document:
         * an online chamber's bKash number too — the public list never shows it).
         */
        fun providerOut(p: ProviderDoc, own: Boolean = false, systemsOn: Boolean = true): LinkedHashMap<String, Any?> {
            val first = p.chambers.firstOrNull()
            val count = p.ratingCount
            val m = LinkedHashMap<String, Any?>()
            m["uid"] = p.uid
            m["type"] = p.type
            // PRACTICE.md: system + "এলোপ্যাথি"… next to the name, the profession label
            m.putAll(Practice.labels(p.type, p.system, systemsOn))
            m["photo"] = p.photoThumb
            m["name"] = p.name
            m["specialties"] = p.specialties
            m["degrees"] = p.degrees
            m["gender"] = p.gender
            m["listed"] = CareRules.isListed(p)
            m["appointments"] = CareRules.takesAppointments(p)
            m["chambers"] = p.chambers.map { it.toMap(withPrivate = own) }
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
        gate.requireType(ctx, type)
        // PRACTICE.md: `system` filters doctors; off for the viewer = only (allopathic) doctors, no filter
        val systemsOn = gate.systemsOn(ctx)
        val system = if (type == "doctor" && systemsOn) b.string("system")?.takeIf { Practice.isSystem(it) } else null
        val homeVisitTrue = b.isTrue("homeVisit")
        // nurses have no chambers — the old apps' "nurse" list is empty (nurses are in "home care")
        if (type !in CHAMBER_TYPES && !homeVisitTrue) return mapOf("items" to emptyList<Any>())
        val base = ListFilter(
            district = b.string("district")?.takeIf { Places.isDistrict(it) },
            upazilaId = b.int("upazilaId"),
            unionId = b.int("unionId"),
            hospitalId = b.string("hospitalId")?.takeIf { Rules.isValidId(it) },
            specialty = b.string("specialty")?.takeIf { it in CareRules.specialtiesFor(type, catalog, system) },
        )
        // online:true = doctors with an online (video) chamber, wherever they are — the area filters do not apply
        val f = CareRules.pickListFilters(b.node, base).let {
            if (it.online == true) it.copy(district = null, upazilaId = null, unionId = null, hospitalId = null) else it
        }
        val all = providerList(type)
        // online_chamber switch off for the caller: no online list, no online-only doctors (FEATURES.md); an online
        // chamber is shown only while the switch is on for its doctor too
        val onlineOk = onlineSwitch.allowed(if (ctx.caller.guest) null else ctx.caller.uid)
        if (!onlineOk && f.online == true) return linkedMapOf("items" to emptyList<Any>(), "facets" to linkedMapOf("specialties" to emptyList<Any>(), "gender" to false))
        // old apps (no homeVisit field) book anyone, so they only see people who take appointments
        val oldClient = !b.has("homeVisit")
        val now = clock.now()
        val areaOnly = ListFilter(district = f.district, upazilaId = f.upazilaId, unionId = f.unionId, hospitalId = f.hospitalId, online = f.online)
        val pool = all.filter { x ->
            when {
                x.adminSuspended || !CareRules.isListed(x) || !CareRules.matchesFilter(x, areaOnly, now) -> false
                type == "doctor" && !systemsOn && Practice.systemOf(x.type, x.system) != Practice.ALLOPATHY -> false
                system != null && Practice.systemOf(x.type, x.system) != system -> false
                oldClient && !(CareRules.takesAppointments(x) && x.chambers.any { it.appointments }) -> false
                homeVisitTrue && !(x.homeVisit?.enabled == true && !x.homeVisitBlocked) -> false
                // home care only (no chamber) — not in the chamber list
                !homeVisitTrue && x.chambers.isEmpty() -> false
                // only an online chamber, and online chambers are off for the caller or the doctor
                !homeVisitTrue && x.chambers.all { it.online } && (!onlineOk || !onlineSwitch.allowed(x.uid)) -> false
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
        // the system chips (সব / এলোপ্যাথি / …): how many of this area's doctors each system has
        if (type == "doctor" && systemsOn) {
            facets["systems"] = Practice.SYSTEMS.map { sys -> linkedMapOf("code" to sys, "n" to pool.count { Practice.systemOf(it.type, it.system) == sys }) }
        }
        val docs = pool.filter { CareRules.matchesFilter(it, f, now) }
            .sortedWith(Comparator { p, q -> if (q.ratingCount != p.ratingCount) q.ratingCount - p.ratingCount else P2Js.localeCompare(p.name, q.name) })
        val items = docs.map { d -> providerOut(d, systemsOn = systemsOn).apply { put("matched", CareRules.matchedChambers(d, f, now)) } }
        return linkedMapOf("items" to items.take(150), "facets" to facets)
    }

    /** own provider document (null when none) */
    fun providerMe(ctx: ActionContext): Any {
        val p = store.load(ctx.user.id) ?: return mapOf("provider" to null)
        val out = providerOut(p, own = true, systemsOn = gate.systemsOn(ctx))
        // the bKash number new bookings get comes from the payment numbers (PAYMENTS.md, one code path): a number reaches
        // patients only after 24 h active (security review P4) — the matured one in use, and when the newest takes over
        val now = clock.now()
        val (active, pending) = payMethods.chamberBkash(ctx.user.id, now)
        out["chambers"] = p.chambers.map { c ->
            val m = LinkedHashMap(c.toMap(withPrivate = true))
            if (c.online) {
                m["bkashActive"] = active
                m["bkashPendingUntil"] = pending
            }
            m
        }
        return mapOf("provider" to out)
    }

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
                INSERT INTO providers (uid, type, system, name, specialty, active, listed, appointments, legacy_chamber, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?)
                ON CONFLICT (uid) DO UPDATE SET type = EXCLUDED.type, system = EXCLUDED.system, name = EXCLUDED.name, specialty = EXCLUDED.specialty,
                  active = EXCLUDED.active, listed = EXCLUDED.listed, appointments = EXCLUDED.appointments, legacy_chamber = TRUE,
                  updated_at = EXCLUDED.updated_at
                """.trimIndent(),
                uid, badge, Practice.systemOf(badge, badgeSystem(uid)), users.name(uid).take(80), b.string("specialty")?.let { ListingRules.jsTrim(it).take(80) } ?: "",
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
        gate.requireType(ctx, badge)
        // a doctor's system is the verified one (badge_apply); it never comes from this request
        val system = Practice.systemOf(badge, badgeSystem(uid))
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
        val specialties = CareRules.pickCodes(b["specialties"], CareRules.specialtiesFor(badge, catalog, system), 5)
        if (specialties.isEmpty()) throw ctx.err(400, "Choose at least one specialty", "অন্তত একটা বিশেষজ্ঞতা বেছে নিন")
        val hvNode = b["homeVisit"]
        val hvObj = hvNode as? ObjectNode
        val hvOn = hvObj?.get("enabled")?.let { it.isBoolean && it.booleanValue() } ?: false
        // apps up to 2.1 have no separate phone field — ask to update
        if (hvOn && hvObj?.has("phone") != true) {
            throw ctx.err(409, "Please update the app to turn on home care", "বাসায় সেবা চালু করতে অ্যাপ আপডেট করুন", "update_required")
        }
        // no home care for psychologists (PRACTICE.md), nutritionists, trainers
        if (badge == Practice.PSYCHOLOGIST && hvOn) {
            throw ctx.err(400, "Psychologists don't do home visits", "সাইকোলজিস্টদের বাসায় সেবা নেই", "no_home_visit") // bn-ok
        }
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
            val r = CareRules.parseChambers(chNode, hosp, badge != "doctor", allowOnline = badge in Practice.ONLINE_TYPES)
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
        var bkashChanged: Pair<String, String>? = null
        // the online chamber's bKash number is a payment number (PAYMENTS.md, security review P4): the same rules as
        // pay_methods_set — a login of the last 10 minutes, demo numbers only in the demo, never while frozen
        val newBk = parsed.firstOrNull { it.online }?.bkashNumber ?: ""
        val bkChange = newBk.isNotEmpty() && newBk != payMethods.list("chamber", uid).firstOrNull { it.method == "bkash" }?.number
        if (bkChange) {
            payGuard.requireNotFrozen(ctx.lang, "chamber", uid)
            payGuard.requireRecentLogin(ctx.lang, ctx.caller)
            payGuard.requireWorldNumber(ctx.lang, uid, newBk)
        }
        var payChanges: List<com.lifolo.payments.MethodChange> = emptyList()
        // read and write in one transaction, so an admin switching it off at the same time is not undone
        tx.executeWithoutResult {
            val cur = store.load(uid, forUpdate = true)
            // online chambers are behind the online_chamber switch (demo accounts too — R6). One saved while it was on
            // may stay (the doctor can still edit his other chambers), a new one needs the switch.
            if (parsed.any { it.online } && !onlineSwitch.allowed(uid) && cur?.chambers?.any { it.online } != true) {
                throw Features.offError(ctx.lang)
            }
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
                INSERT INTO providers (uid, type, system, name, listed, appointments, active, specialties, degrees, gender, legacy_chamber, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::text[], ?, ?, FALSE, ?)
                ON CONFLICT (uid) DO UPDATE SET type = EXCLUDED.type, system = EXCLUDED.system, name = EXCLUDED.name, listed = EXCLUDED.listed,
                  appointments = EXCLUDED.appointments, active = EXCLUDED.active, specialties = EXCLUDED.specialties,
                  degrees = EXCLUDED.degrees, gender = EXCLUDED.gender, legacy_chamber = FALSE, updated_at = EXCLUDED.updated_at
                """.trimIndent(),
                uid, badge, system, name, listed, takes, listed && takes, P2Js.pgTexts(specialties), CareRules.str(b["degrees"], 120),
                gender.ifEmpty { null }, Sql.ts(now),
            )
            store.saveChambers(uid, saved)
            if (!keepHome) store.saveHome(uid, homeVisit, if (homeVisit.enabled) now else null)
            // open online appointments show the doctor's current note (the fee and bKash number stay as booked — R6)
            saved.firstOrNull { it.online }?.let { oc ->
                jdbc.update(
                    "UPDATE appointments SET online_note = ? WHERE provider_uid = ? AND online AND status IN ('pending', 'confirmed')",
                    oc.note.ifEmpty { null }, uid,
                )
            }
            // R6: a bKash number change is logged (the admins' view); the number itself goes through the payment numbers
            val oldCh = cur?.chambers?.firstOrNull { it.online }
            val oldNo = oldCh?.bkashNumber ?: ""
            val newNo = saved.firstOrNull { it.online }?.bkashNumber ?: ""
            if (oldNo != newNo) {
                jdbc.update(
                    "INSERT INTO bkash_number_log (provider_uid, old_last3, new_last3, by_uid, at) VALUES (?, ?, ?, ?, ?)",
                    uid, oldNo.takeLast(3).ifEmpty { null }, newNo.takeLast(3).ifEmpty { null }, ctx.caller.id, Sql.ts(now),
                )
                bkashChanged = oldNo to newNo
            }
            if (bkChange) payChanges = payMethods.setOne("chamber", uid, uid, "bkash", newBk, "personal", ctx.caller.id, now)
        }
        // P4: push AND email with "আমি করিনি" (the same notice as pay_methods_set)
        if (bkashChanged != null || payChanges.isNotEmpty()) payGuard.notifyChanged("chamber", uid, uid, payChanges, now)
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
        // PRACTICE.md: a switched-off profession, or a non-allopathic doctor while practice_systems is off (not listed)
        gate.requireType(ctx, ps.type)
        if (Practice.freeText(ps.type, ps.system) && !gate.systemsOn(ctx)) throw notTaking()
        // a psychologist: the patient books it (a caregiver only when the patient shares those appointments with them)
        if (privacy.hidden(user, ps.type)) {
            throw ctx.err(403, "The patient books this appointment themselves", "এই অ্যাপয়েন্টমেন্ট রোগী নিজে নেবেন", "private_type") // bn-ok
        }
        val chambers = ps.chambers
        val chamberIndex = b.int("chamberIndex")
        val ch = if (chamberIndex == null) {
            chambers.firstOrNull { it.appointments }
        } else {
            if (chamberIndex in 0 until chambers.size) chambers[chamberIndex.toInt()] else null
        }
        if (ch == null || !ch.appointments) throw notTaking()
        if (ch.online) {
            // the online_chamber switch: on for the caller (else 404 feature_off) and for the doctor
            onlineSwitch.require(ctx)
            if (!onlineSwitch.allowed(providerUid)) throw notTaking()
        }
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
        // online chamber: the fee to send by bKash (no fee / 0 taka → nothing to pay)
        val online = ch.online
        val noFee = online && (ch.noFee == true || CareRules.feeAmount(ch) == 0.0)
        val payState = if (online && !noFee) "unpaid" else null
        jdbc.update(
            """
            INSERT INTO appointments (id, patient_uid, provider_uid, patient_name, provider_name, member_id, patient_age, patient_gender,
              by_caretaker, type, chamber, chamber_bn, chamber_key, start_time, room, schedule, visit_date, note, status, provider_note, created_at,
              online, pay_state, bkash_number, fee, no_fee, online_note)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending', '', ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            id, uid, providerUid, name, ps.name, member?.get("member_id"), (member?.get("age") as? Number)?.toInt(),
            (member?.get("gender") as? String) ?: "", user.actingBy, ps.type, ch.hospitalName, ch.hospitalNameBn, ch.key, slotStart,
            ch.room, ch.schedule, Sql.date(DhakaTime.fromUtcMidnightMs(date)), note, Sql.ts(now),
            online, payState, if (online) payMethods.chamberBkash(providerUid, now).first else null, if (online) (if (noFee) "" else ch.fee) else null,
            noFee, if (online) ch.note.ifEmpty { null } else null,
        )
        users.push(
            providerUid,
            if (online) "New online appointment request" else "New appointment request",
            if (online) "নতুন অনলাইন অ্যাপয়েন্টমেন্টের অনুরোধ" else "নতুন অ্যাপয়েন্টমেন্টের অনুরোধ", // bn-ok
            "$name · ${ApptRepo.dateLabel(date)}",
            mapOf("type" to "appointment", "role" to "provider", "cat" to NotifyCats.BUSINESS) + Practice.privateTag(ps.type),
        )
        // Lifolo Pay: the online fee's request, or the in-person chamber's advance (when the doctor asks one) — never
        // a reason for the booking to fail (an unpaid advance does not cancel it either, M-PAY-34)
        val payRequestId = try {
            payHooks.ifAvailable?.afterBook(id, now)
        } catch (e: Exception) {
            null
        }
        return if (payRequestId != null) mapOf("id" to id, "payRequestId" to payRequestId) else mapOf("id" to id)
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
        // a caregiver: no psychologist appointments unless the patient shares them (PRACTICE.md)
        val mine = byStatus("patient_uid").let { all -> if (privacy.hidesPrivate(ctx.user)) all.filter { !Practice.isPrivate(it.type) } else all }
        // a caregiver acts on the patient side only: a doctor's appointments with their patients (phones, notes) never
        val toMe = if (ctx.user.patientSideOnly) emptyList() else byStatus("provider_uid")
        val systemsOn = gate.systemsOn(ctx)
        val systems = providerSystems((mine + toMe).map { it.providerUid })
        // confirmed: the other party's phone
        val need = LinkedHashSet<String>()
        mine.forEach { if (it.status == "confirmed") need.add(it.providerUid) }
        toMe.forEach { if (it.status == "confirmed") need.add(it.patientUid) }
        val phones = users.profiles(need).mapValues { it.value.phone }
        val receipts = com.lifolo.invoices.InvoiceLinks.live(jdbc, "appointment", (mine + toMe).map { it.id })
        val arrived = HashMap<String, Long>()
        if (toMe.isNotEmpty()) {
            for (chunk in toMe.map { it.id }.chunked(500)) {
                jdbc.query(
                    "SELECT id, arrived_at FROM appointments WHERE arrived_at IS NOT NULL AND id IN (${chunk.joinToString(",") { "?" }})",
                    org.springframework.jdbc.core.RowCallbackHandler { rs -> arrived[rs.getString(1)] = rs.getTimestamp(2).time },
                    *chunk.toTypedArray(),
                )
            }
        }
        fun item(a: Appt, role: String): Map<String, Any?> {
            val other = if (role == "patient") a.providerUid else a.patientUid
            return linkedMapOf<String, Any?>(
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
                "invoiceId" to receipts[a.id],
                // the chamber desk (chamber_checkin): the doctor's side only
                "arrivedAt" to (if (role == "provider") arrived[a.id] else null),
            ).apply {
                putAll(onlineFields(a, role == "patient"))
                // the provider's system / profession labels; private = a psychologist's (generic pushes, caregivers)
                putAll(Practice.labels(a.type, systems[a.providerUid], systemsOn))
                put("private", Practice.isPrivate(a.type))
            }
        }
        return mapOf("items" to mine.map { item(it, "patient") } + toMe.map { item(it, "provider") })
    }

    /** providers.system of these providers (doctors; others null) */
    private fun providerSystems(uids: Collection<String>): Map<String, String?> {
        val ids = uids.distinct()
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<String, String?>()
        for (chunk in ids.chunked(500)) {
            jdbc.query(
                "SELECT uid, system FROM providers WHERE uid IN (${chunk.joinToString(",") { "?" }})",
                RowCallbackHandler { rs -> out[rs.getString(1)] = rs.getString(2) },
                *chunk.toTypedArray(),
            )
        }
        return out
    }

    /** badges.system — the verified medical system of a doctor (null for other professions / no badge) */
    private fun badgeSystem(uid: String): String? =
        jdbc.queryForList("SELECT system FROM badges WHERE uid = ?", String::class.java, uid).firstOrNull()

    /**
     * my_appointments: the online-chamber fields (V620). `payState` null = nothing to pay (in person, or no fee);
     * the doctor's bKash number only to the patient side.
     */
    private fun onlineFields(a: Appt, patientSide: Boolean): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>(
            "online" to a.online,
            "payState" to (if (a.online) a.payState else null),
            "payRef" to (if (a.online) a.payRef else null),
            "payNote" to (if (a.online) a.payNote else null),
            "payClaimedAt" to (if (a.online) a.payClaimedAt else null),
            "fee" to (if (a.online) a.fee else null),
            "noFee" to (if (a.online) a.noFee else null),
            "onlineNote" to (if (a.online) a.onlineNote else null),
            // the doctor's side joined the video call (appt_join) — hides "the doctor did not come" (R8)
            "doctorJoined" to (a.online && a.doctorJoinedAt != null),
        )
        if (patientSide) m["bkashNumber"] = if (a.online && a.payState != null) a.bkashNumber.ifEmpty { null } else null
        return m
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
        // nor a psychologist appointment the patient did not share (PRACTICE.md)
        if (privacy.hidden(ctx.user, a.type)) throw ctx.err(404, "Not found", "পাওয়া যায়নি")
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
        // R8: the doctor turns down an online appointment the patient already paid (or says they paid): a reason is required
        val paidOff = isProvider && a.online && (a.payState == "claimed" || a.payState == "paid") && (next == "cancelled" || next == "declined")
        if (paidOff && note.isEmpty()) {
            throw ctx.err(400, "The patient sent the fee — write the reason", "রোগী ফি পাঠিয়েছেন — বাতিলের কারণ লিখুন", "reason_required") // bn-ok
        }
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
                // the doctor's own register number (patients booked by phone too); else the next number
                val own = b.int("serial")?.takeIf { it in 1..999 }?.toInt()
                val s = SerialCounters.next(jdbc, a.providerUid, a.date, chamberKey, own, now)
                serial = s
                jdbc.update(
                    "UPDATE appointments SET status = ?, provider_note = ?, updated_at = ?, serial = ? WHERE id = ?",
                    next, providerNote, Sql.ts(now), s, a.id,
                )
            } else {
                jdbc.update("UPDATE appointments SET status = ?, provider_note = ?, updated_at = ? WHERE id = ?", next, providerNote, Sql.ts(now), a.id)
            }
        }
        val to = if (isProvider) a.patientUid else a.providerUid
        // online_chamber switched off for the patient: no "online appointment confirmed" push (cancel/decline still go)
        val quiet = next == "confirmed" && a.online && !onlineSwitch.allowed(to)
        val texts = when (next) {
            "confirmed" -> if (a.online) "Online appointment confirmed" to "অনলাইন অ্যাপয়েন্টমেন্ট নিশ্চিত হয়েছে" else "Appointment confirmed" to "অ্যাপয়েন্টমেন্ট নিশ্চিত হয়েছে" // bn-ok
            "declined" -> "Appointment not accepted" to "অ্যাপয়েন্টমেন্ট নেওয়া হয়নি"
            else -> "Appointment cancelled" to "অ্যাপয়েন্টমেন্ট বাতিল হয়েছে"
        }
        val s = serial
        if (!quiet) users.push(
            to, texts.first, texts.second,
            "${if (isProvider) a.providerName else a.patientName} · ${ApptRepo.dateLabel(a.date)}${if (s != null) " · #$s" else ""}",
            mapOf("type" to "appointment", "role" to (if (isProvider) "patient" else "provider")) + Practice.privateTag(a.type),
        )
        if (paidOff) {
            payLog.log(a.id, uid, a.payState, a.payState, a.payRef, "$next: $note")
            payLog.systemReport(a.id, "doctor $next after the fee (${a.payState}): $note")
            pushService.pushPatient(
                a.patientUid, "Ask the doctor for your fee back", "টাকা ফেরতের জন্য ডাক্তারের সাথে যোগাযোগ করুন / সাহায্য ও আপিল", // bn-ok
                "${a.providerName} · ${ApptRepo.dateLabel(a.date)} · $note",
                mapOf("type" to "appointment", "role" to "patient", "id" to a.id, "cat" to NotifyCats.APPOINTMENTS) + Practice.privateTag(a.type),
            )
        }
        return mapOf("ok" to true)
    }
}

/** Lifolo Pay (payments package): after a booking — the online fee's request or the chamber's advance; returns its id */
fun interface ApptPayHook {
    fun afterBook(appointmentId: String, nowMs: Long): String?
}
