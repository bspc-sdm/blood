package com.lifolo.practice

import com.fasterxml.jackson.databind.JsonNode
import com.lifolo.admin.kernel.VerifySpec
import com.lifolo.appointments.Ch
import com.lifolo.appointments.P2TestBase
import com.lifolo.features.Features
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * backend/docs/PRACTICE.md §1–3, §5: the system migration default, the `system` filter and labels, re-verification on a
 * system change, the prescription rules (free text, no pharmacy, notes only), psychologist privacy towards caregivers
 * (+ generic pushes) and the feature switches (practice_systems, pro_physio, pro_psychologist).
 */
class PracticeIT : P2TestBase() {
    @Autowired lateinit var features: Features

    @Autowired lateinit var hospitals: com.lifolo.contracts.HospitalDirectory

    /** a hospital of the directory (provider_save takes chambers from the list) */
    private fun hospitalId(): String = hospitals.approvedIn(district("Dhaka")).first().id

    private val day = 24 * 3600 * 1000L
    private val owner = "owner1"
    private val jpeg = "/9j/4AAQSkZJRgABAQ"

    @AfterEach
    fun switchesBackOn() {
        features.allOn = true
    }

    /** a key's row (+ a history row so the version moves), cache cleared — like FeaturesIT */
    private fun flag(key: String, mode: String) {
        jdbc.update(
            "INSERT INTO feature_flags (key, mode) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET mode = EXCLUDED.mode",
            key, mode,
        )
        jdbc.update("INSERT INTO feature_changes (key, mode) VALUES (?, ?)", key, mode)
        features.clearCache()
    }

    private fun chamber(key: String = "dghs_1") = Ch(
        hospitalId = key, hospitalName = "City Hospital", hospitalNameBn = "সিটি হাসপাতাল", district = district("Dhaka"),
        days = listOf(0, 1, 2, 3, 4, 5, 6), start = "09:00", end = "23:00", noFee = false, fee = "500", appointments = true,
    )

    /** an approved, listed professional with one chamber open every day */
    private fun pro(uid: String, type: String = "doctor", system: String? = null, specialties: List<String> = listOf("medicine"), name: String = uid) {
        person(uid, name)
        badge(uid, type, name)
        provider(uid, listOf(chamber()), type = type, name = name, specialties = specialties)
        if (system != null) {
            jdbc.update("UPDATE badges SET system = ? WHERE uid = ?", system, uid)
            jdbc.update("UPDATE providers SET system = ? WHERE uid = ?", system, uid)
        }
    }

    private fun list(uid: String?, body: Map<String, Any?>): JsonNode =
        ok(uid, "providers", mapOf("type" to "doctor", "district" to district("Dhaka"), "homeVisit" to false) + body)

    private fun uids(items: JsonNode): Set<String> = items.map { it["uid"].textValue() }.toSet()

    private fun book(patient: String, provider: String, asUid: String? = null): Res =
        call(
            patient, "book",
            mapOf("providerUid" to provider, "date" to today() + day, "chamberIndex" to 0, "note" to "সমস্যা") +
                (if (asUid != null) mapOf("asUid" to asUid) else emptyMap()),
        )

    // ------------------------------------------------------------------ §1 migration default

    @Test
    fun `migration - every existing doctor (provider, badge, prescription) becomes allopathy, others stay without a system`() {
        val applied = jdbc.queryForList("SELECT success FROM flyway_schema_history WHERE version = '680'", Boolean::class.java)
        assertEquals(listOf(true), applied, "V680 applied")
        person("oldDoc")
        person("oldNurse")
        jdbc.update("INSERT INTO badges (uid, type, reg_no, workplace, status) VALUES ('oldDoc', 'doctor', 'A-1', 'X', 'approved'), ('oldNurse', 'nurse', 'N-1', 'X', 'approved')")
        jdbc.update("INSERT INTO providers (uid, type) VALUES ('oldDoc', 'doctor'), ('oldNurse', 'nurse')")
        jdbc.update("INSERT INTO prescriptions (id, code, doctor_uid, status, created_at) VALUES ('oldRx', 'OLDRXA2345', 'oldDoc', 'active', now())")
        // a row from before the migration has no system; running the migration (re-runnable) fills it in
        jdbc.update("UPDATE providers SET system = NULL")
        jdbc.update("UPDATE badges SET system = NULL")
        jdbc.update("UPDATE prescriptions SET doctor_system = NULL")
        val sql = javaClass.getResourceAsStream("/db/migration/V680__practice_systems.sql")!!.readBytes().toString(Charsets.UTF_8)
        jdbc.execute(sql)
        assertEquals("allopathy", row("SELECT system FROM providers WHERE uid = 'oldDoc'")["system"])
        assertEquals("allopathy", row("SELECT system FROM badges WHERE uid = 'oldDoc'")["system"])
        assertNull(row("SELECT system FROM providers WHERE uid = 'oldNurse'")["system"])
        assertNull(row("SELECT system FROM badges WHERE uid = 'oldNurse'")["system"])
        assertEquals(listOf("doctor", "allopathy", false), row("SELECT doctor_type, doctor_system, free_text FROM prescriptions WHERE id = 'oldRx'").values.toList())
        // the new professions pass the CHECKs; an unknown system does not
        person("newPhysio")
        jdbc.update("INSERT INTO badges (uid, type, reg_no, workplace, status) VALUES ('newPhysio', 'physio', 'B-1', 'X', 'pending')")
        jdbc.update("INSERT INTO providers (uid, type) VALUES ('newPhysio', 'physio')")
        val bad = runCatching { jdbc.update("UPDATE providers SET system = 'magic' WHERE uid = 'oldDoc'") }
        assertTrue(bad.isFailure, "system CHECK")
        // an old row with no system still reads as allopathy
        jdbc.update("UPDATE providers SET system = NULL WHERE uid = 'oldDoc'")
        assertEquals("allopathy", Practice.systemOf("doctor", providerStore.load("oldDoc")!!.system))
        assertNull(Practice.systemOf("nurse", "allopathy"))
    }

    // ------------------------------------------------------------------ §1 filter + labels

    @Test
    fun `providers - all systems by default with their labels, the system filter, system chips, specialties per system`() {
        clock.fixedMs = dhaka("2026-10-19 10:00")
        pro("allo", name = "ডা. এলো")
        pro("homeo", system = "homeopathy", specialties = listOf("alt_general"), name = "হোমিও চিকিৎসক")
        pro("unani", system = "unani", specialties = listOf("alt_digestive"), name = "ইউনানি চিকিৎসক")
        person("pt")
        val all = list("pt", emptyMap())
        assertEquals(setOf("allo", "homeo", "unani"), uids(all["items"]))
        val byUid = all["items"].associateBy { it["uid"].textValue() }
        assertEquals("এলোপ্যাথি", byUid["allo"]!!["systemLabel"].textValue())
        assertEquals(listOf("homeopathy", "হোমিওপ্যাথি", "ডাক্তার"), listOf("system", "systemLabel", "typeLabel").map { byUid["homeo"]!![it].textValue() })
        assertEquals("ইউনানি", byUid["unani"]!!["systemLabel"].textValue())
        val chips = all["facets"]["systems"].associate { it["code"].textValue() to it["n"].intValue() }
        assertEquals(mapOf("allopathy" to 1, "homeopathy" to 1, "unani" to 1, "ayurveda" to 0), chips)
        assertEquals(setOf("homeo"), uids(list("pt", mapOf("system" to "homeopathy"))["items"]))
        assertEquals(setOf("allo"), uids(list("pt", mapOf("system" to "allopathy"))["items"]))
        assertEquals(setOf("allo", "homeo", "unani"), uids(list("pt", mapOf("system" to "magic"))["items"]), "unknown = no filter")
        // the specialty filter knows every system's codes
        assertEquals(setOf("homeo"), uids(list("pt", mapOf("specialty" to "alt_general"))["items"]))
        // provider_me carries the label too
        assertEquals("হোমিওপ্যাথি", ok("homeo", "provider_me")["provider"]["systemLabel"].textValue())

        // provider_save: the system comes from the badge, the specialties from that system's catalog
        val save = { uid: String, sp: List<String> ->
            call(
                uid, "provider_save",
                mapOf(
                    "specialties" to sp, "listed" to true, "appointments" to true, "degrees" to "DHMS",
                    "chambers" to listOf(mapOf("hospitalId" to hospitalId(), "days" to listOf(0, 1, 2, 3, 4, 5, 6), "start" to "09:00", "end" to "23:00", "fee" to "300", "appointments" to true)),
                    "system" to "allopathy",
                ),
            )
        }
        assertEquals("অন্তত একটা বিশেষজ্ঞতা বেছে নিন", save("homeo", listOf("cardiology")).error, "an allopathic specialty is not a homeopath's")
        val r = save("homeo", listOf("alt_skin_hair", "cardiology"))
        assertEquals(200, r.status, r.toString())
        assertEquals(listOf("homeopathy", "{alt_skin_hair}"), row("SELECT system, specialties::text AS sp FROM providers WHERE uid = 'homeo'").values.toList(), "the request cannot change the system")

        // my_appointments shows the provider's system on both sides
        val bk = book("pt", "homeo")
        assertEquals(200, bk.status, bk.toString())
        val mine = ok("pt", "my_appointments")["items"].first { it["id"].textValue() == bk.body["id"].textValue() }
        assertEquals(listOf("homeopathy", "হোমিওপ্যাথি", "false"), listOf(mine["system"].asText(), mine["systemLabel"].asText(), mine["private"].asText()))
        val theirs = ok("homeo", "my_appointments")["items"].first { it["role"].textValue() == "provider" }
        assertEquals("হোমিওপ্যাথি", theirs["systemLabel"].textValue())

        // the catalogs: one list per system and profession
        val lists = ok(null, "catalog_get")["lists"]["specialty"]
        for (t in listOf("doctor_homeopathy", "doctor_unani", "doctor_ayurveda", "physio", "psychologist")) assertTrue(lists[t].size() >= 5, t)
        assertTrue(lists["doctor_homeopathy"].any { it["bn"].textValue() == "সাধারণ" })
        assertEquals(5, lists["physio"].size())
        assertEquals(5, lists["psychologist"].size())
    }

    // ------------------------------------------------------------------ §1 re-verification

    @Test
    fun `a system change sends an approved badge back to verification - the new body's certificate, admin review, listing follows`() {
        clock.fixedMs = dhaka("2026-10-19 10:00")
        person(owner, "মালিক", "owner@example.com")
        pro("d1", name = "ডা. রহিম")
        person("pt")
        val bk = book("pt", "d1")
        assertEquals(200, bk.status, bk.toString())
        assertEquals("bad_system", call("d1", "badge_apply", mapOf("type" to "doctor", "system" to "magic", "regNo" to "H-1", "workplace" to "X")).code)
        // the homeopathic board's certificate is required (no online register to check)
        assertEquals("docs_required", call("d1", "badge_apply", mapOf("type" to "doctor", "system" to "homeopathy", "regNo" to "H-1", "workplace" to "X")).code)
        val r = ok("d1", "badge_apply", mapOf("type" to "doctor", "system" to "homeopathy", "regNo" to "H-1", "workplace" to "হোমিও চেম্বার", "docs" to mapOf("cert" to jpeg)))
        assertEquals(true, r["reverify"].booleanValue())
        val b = row("SELECT status, system, reverify_from, reg_no, verified_at FROM badges WHERE uid = 'd1'")
        assertEquals(listOf("pending", "homeopathy", "allopathy", "H-1", null), b.values.toList())
        assertFalse(uids(list("pt", emptyMap())["items"]).contains("d1"), "out of the lists while it is checked again")
        assertEquals("allopathy", row("SELECT system FROM providers WHERE uid = 'd1'")["system"], "the listing changes on approval")
        val me = ok("d1", "me")["badge"]
        assertEquals(listOf("pending", "homeopathy", "allopathy"), listOf(me["status"].asText(), me["system"].asText(), me["reverifyFrom"].asText()))

        // the admin sees the system, the body and the earlier system; the checks are the homeopathic board's
        val item = ok(owner, "admin_badges", mapOf("panel" to true))["items"].first { it["uid"].textValue() == "d1" }
        assertEquals(listOf("homeopathy", "হোমিওপ্যাথি", "বাংলাদেশ হোমিওপ্যাথিক বোর্ড", "DHMS / BHMS", "allopathy"), listOf("system", "systemLabel", "body", "degreeHint", "reverifyFrom").map { item[it].asText() })
        val checks = VerifySpec.checks("badge", mapOf("type" to "doctor", "system" to "homeopathy")).map { it.id }
        assertTrue("cert_body" in checks && "bmdc_found" !in checks)
        assertEquals(checks, item["verify"]["checks"].map { it["id"].textValue() })
        assertEquals(400, call(owner, "admin_badge_resolve", mapOf("uid" to "d1", "approve" to true, "checks" to VerifySpec.checks("badge", mapOf("type" to "doctor")).map { it.id })).status, "BMDC ticks are not enough")
        ok(owner, "admin_badge_resolve", mapOf("uid" to "d1", "approve" to true, "checks" to checks))
        assertEquals(listOf("approved", null), row("SELECT status, reverify_from FROM badges WHERE uid = 'd1'").values.toList())
        val p = row("SELECT system, listed, specialties::text AS sp FROM providers WHERE uid = 'd1'")
        assertEquals(listOf("homeopathy", false, "{}"), p.values.toList(), "new system: chambers and specialties are saved again")
        assertEquals("cancelled", row("SELECT status FROM appointments WHERE id = ?", bk.body["id"].textValue())["status"], "booked under the old system")
        // number/workplace corrections of the approved homeopath keep the system (and use its checks)
        ok("d1", "badge_apply", mapOf("type" to "doctor", "regNo" to "H-2", "workplace" to "Y"))
        assertEquals(listOf("approved", "homeopathy"), row("SELECT status, system FROM badges WHERE uid = 'd1'").values.toList())
        ok(owner, "admin_badge_resolve", mapOf("uid" to "d1", "correction" to true, "approve" to true, "checks" to checks))
        assertEquals("H-2", row("SELECT reg_no FROM badges WHERE uid = 'd1'")["reg_no"])

        // a new application of another profession: physio needs the degree certificate, gets no system
        person("ph1")
        assertEquals("docs_required", call("ph1", "badge_apply", mapOf("type" to "physio", "regNo" to "BPT-1", "workplace" to "X")).code)
        ok("ph1", "badge_apply", mapOf("type" to "physio", "system" to "homeopathy", "regNo" to "BPT-1", "workplace" to "X", "docs" to mapOf("cert" to jpeg)))
        assertEquals(listOf("physio", null), row("SELECT type, system FROM badges WHERE uid = 'ph1'").values.toList())
    }

    // ------------------------------------------------------------------ §1–2 prescriptions

    @Test
    fun `prescriptions - non-allopathic free text and never to a pharmacy; physio and psychologist notes only`() {
        clock.fixedMs = dhaka("2026-10-19 10:00")
        pro("homeo", system = "homeopathy", specialties = listOf("alt_general"), name = "হোমিও চিকিৎসক")
        pro("physio", type = "physio", specialties = listOf("physio_back_neck"), name = "ফিজিও")
        pro("psy", type = "psychologist", specialties = listOf("psy_trauma"), name = "কাউন্সেলর")
        person("pt", extra = mapOf("phone" to "01711000001"))
        fun appt(provider: String): String {
            val r = book("pt", provider)
            assertEquals(200, r.status, r.toString())
            return r.body["id"].textValue()
        }

        // rx_me: what the form allows
        val hm = ok("homeo", "rx_me")
        assertEquals(listOf("হোমিওপ্যাথি", "true", "false", "false", "true"), listOf(hm["systemLabel"].asText(), hm["rxRules"]["freeText"].asText(), hm["rxRules"]["pharmacy"].asText(), hm["rxRules"]["drugCatalog"].asText(), hm["rxRules"]["tests"].asText()))
        val pm = ok("psy", "rx_me")["rxRules"]
        assertEquals(listOf("false", "false", "সেশন নোট ও পরামর্শ"), listOf(pm["medicines"].asText(), pm["tests"].asText(), pm["section"].asText()))
        assertEquals("ওষুধের প্রয়োজনে সাইকিয়াট্রিস্ট (এলোপ্যাথি ডাক্তার) দেখান", pm["notice"].textValue())
        assertEquals("পরামর্শ ও ব্যায়াম", ok("physio", "rx_me")["rxRules"]["section"].textValue())

        // homeopath: free-text lines kept as written (spaces in the dose), tests allowed, marked free text
        val h = ok(
            "homeo", "rx_create",
            mapOf(
                "appointmentId" to appt("homeo"),
                "medicines" to listOf(mapOf("name" to "Arnica 30 — ৪ ফোঁটা আধা কাপ পানিতে", "dose" to "দিনে ৩ বার", "days" to 7), "Nux Vomica 200"),
                "tests" to listOf("CBC"),
            ),
        )
        val hx = ok("pt", "rx_get", mapOf("id" to h["id"].textValue()))["item"]
        assertEquals(listOf("true", "false", "হোমিওপ্যাথি", "homeopathy"), listOf(hx["freeText"].asText(), hx["pharmacy"].asText(), hx["doctor"]["systemLabel"].asText(), hx["doctor"]["system"].asText()))
        assertEquals(listOf("Arnica 30 — ৪ ফোঁটা আধা কাপ পানিতে", "Nux Vomica 200"), hx["medicines"].map { it["name"].textValue() })
        assertEquals("দিনে ৩ বার", hx["medicines"][0]["dose"].textValue())
        assertEquals(listOf("CBC"), hx["tests"].map { it.textValue() })
        assertEquals(listOf("homeopathy", true), row("SELECT doctor_system, free_text FROM prescriptions WHERE id = ?", h["id"].textValue()).values.toList())
        // no pharmacy step for it (server side)
        for ((a, body) in listOf(
            "rx_send_pharmacy" to mapOf("rxId" to h["id"].textValue(), "pharmacyId" to "ph1"),
            "rx_pharmacy_options" to mapOf("rxId" to h["id"].textValue(), "district" to district("Dhaka")),
        )) {
            val r = call("pt", a, body)
            assertEquals(409, r.status, "$a $r")
            assertEquals("rx_no_pharmacy", r.code)
        }
        // the public check page shows the system and the registering body
        val page = get("/rx/${h["code"].textValue()}").response.getContentAsString(Charsets.UTF_8)
        assertTrue(page.contains("হোমিওপ্যাথি") && page.contains("বাংলাদেশ হোমিওপ্যাথিক বোর্ড"), page)

        // physio / psychologist: no medicines, no tests — notes only
        for (w in listOf("physio", "psy")) {
            val id = appt(w)
            val med = call(w, "rx_create", mapOf("appointmentId" to id, "medicines" to listOf(mapOf("name" to "Napa", "dose" to "1+0+1", "days" to 3)), "advice" to "x"))
            assertEquals(listOf(400, "no_medicines"), listOf(med.status, med.code), w)
            val tst = call(w, "rx_create", mapOf("appointmentId" to id, "tests" to listOf("MRI"), "advice" to "x"))
            assertEquals(listOf(400, "no_tests"), listOf(tst.status, tst.code), w)
            val okRx = ok(w, "rx_create", mapOf("appointmentId" to id, "medicines" to emptyList<Any>(), "advice" to "প্রতিদিন ১৫ মিনিট হাঁটুন", "complaints" to "ঘাড় ব্যথা"))
            val v = ok("pt", "rx_get", mapOf("id" to okRx["id"].textValue()))["item"]
            assertEquals(listOf(if (w == "physio") "physio" else "psychologist", "false", "0"), listOf(v["doctorType"].asText(), v["pharmacy"].asText(), v["medicines"].size().toString()))
            assertEquals(if (w == "physio") "ফিজিওথেরাপিস্ট" else "ক্লিনিক্যাল সাইকোলজিস্ট / কাউন্সেলর", v["doctor"]["typeLabel"].textValue())
            assertTrue(v["doctor"]["system"].isNull)
            assertEquals(409, call("pt", "rx_send_pharmacy", mapOf("rxId" to okRx["id"].textValue(), "pharmacyId" to "ph1")).status)
        }
    }

    // ------------------------------------------------------------------ §2 new professions

    @Test
    fun `physio and psychologist - lists, online chamber, home visits for physios only`() {
        clock.fixedMs = dhaka("2026-10-19 10:00")
        pro("physio", type = "physio", specialties = listOf("physio_back_neck"))
        pro("psy", type = "psychologist", specialties = listOf("psy_trauma"))
        person("pt")
        val dhaka = district("Dhaka")
        assertEquals(setOf("physio"), uids(ok("pt", "providers", mapOf("type" to "physio", "district" to dhaka, "homeVisit" to false))["items"]))
        val ps = ok("pt", "providers", mapOf("type" to "psychologist", "district" to dhaka, "homeVisit" to false))["items"]
        assertEquals(listOf("psy", "ক্লিনিক্যাল সাইকোলজিস্ট / কাউন্সেলর"), listOf(ps[0]["uid"].textValue(), ps[0]["typeLabel"].textValue()))
        assertTrue(ps[0]["system"].isNull)
        // an online (video) chamber besides the place; the specialty from the profession's catalog
        val online = mapOf("mode" to "online", "days" to listOf(0, 1, 2, 3, 4, 5, 6), "start" to "18:00", "end" to "21:00", "fee" to "800", "bkashNumber" to "01711000099", "appointments" to true)
        val place = mapOf("hospitalId" to hospitalId(), "days" to listOf(0, 1, 2, 3, 4, 5, 6), "start" to "09:00", "end" to "13:00", "fee" to "500", "appointments" to true)
        val saved = call("psy", "provider_save", mapOf("specialties" to listOf("psy_child", "medicine"), "listed" to true, "appointments" to true, "chambers" to listOf(place, online)))
        assertEquals(200, saved.status, saved.toString())
        assertEquals("{psy_child}", row("SELECT specialties::text AS s FROM providers WHERE uid = 'psy'")["s"])
        assertEquals(2, ok("psy", "provider_me")["provider"]["chambers"].size())
        // no home visits for psychologists; physios may
        val hv = mapOf("enabled" to true, "phone" to "01711000088", "services" to listOf("physio_home_exercise"), "upazilaIds" to listOf(1), "termsAccepted" to true)
        val noHome = call("psy", "provider_save", mapOf("specialties" to listOf("psy_child"), "chambers" to listOf(place), "homeVisit" to hv))
        assertEquals(listOf(400, "no_home_visit"), listOf(noHome.status, noHome.code))
        val home = call("physio", "provider_save", mapOf("specialties" to listOf("physio_stroke"), "listed" to true, "appointments" to true, "chambers" to listOf(place), "homeVisit" to hv))
        assertEquals(200, home.status, home.toString())
        assertEquals(true, row("SELECT home_visit_enabled FROM providers WHERE uid = 'physio'")["home_visit_enabled"])
        // a physio's home services come from the physio list
        val badSvc = call("physio", "provider_save", mapOf("specialties" to listOf("physio_stroke"), "chambers" to listOf(place), "homeVisit" to hv + ("services" to listOf("injection"))))
        assertEquals(400, badSvc.status, badSvc.toString())
        // booking a physio is an ordinary appointment of type physio
        val bk = book("pt", "physio")
        assertEquals(200, bk.status, bk.toString())
        assertEquals("physio", row("SELECT type FROM appointments WHERE id = ?", bk.body["id"].textValue())["type"])
    }

    // ------------------------------------------------------------------ §2 psychologist privacy

    @Test
    fun `psychologist privacy - caregivers see nothing unless the patient shares, pushes are generic`() {
        clock.fixedMs = dhaka("2026-10-19 10:00")
        pro("psy", type = "psychologist", specialties = listOf("psy_trauma"), name = "কাউন্সেলর নাদিয়া")
        pro("doc", name = "ডা. রহিম")
        person("pt", "রোগী রহিমা")
        person("cg", "কেয়ারগিভার রুমা")
        jdbc.update("INSERT INTO care_links (patient_uid, caretaker_uid, status) VALUES ('pt', 'cg', 'active')")
        val asP = mapOf("asUid" to "pt")

        // a caregiver cannot book a psychologist for the patient
        val byCg = book("cg", "psy", asUid = "pt")
        assertEquals(listOf(403, "private_type"), listOf(byCg.status, byCg.code))
        val psyAppt = book("pt", "psy").body["id"].textValue()
        val docAppt = book("pt", "doc").body["id"].textValue()
        // the psychologist's new-request push is generic
        assertEquals(listOf("অ্যাপয়েন্টমেন্ট আপডেট" to ""), pushesTo("psy"))
        assertTrue(pushesTo("doc").single().first.contains("নতুন অ্যাপয়েন্টমেন্টের অনুরোধ"))

        // the caregiver's view: the doctor's appointment, not the psychologist's
        val cgList = ok("cg", "my_appointments", asP)["items"].map { it["id"].textValue() }
        assertTrue(docAppt in cgList && psyAppt !in cgList, cgList.toString())
        assertTrue(ok("pt", "my_appointments")["items"].first { it["id"].textValue() == psyAppt }["private"].booleanValue())
        for ((a, body) in listOf(
            "appointment_update" to mapOf("id" to psyAppt, "op" to "cancel"),
            "appointment_queue" to mapOf("id" to psyAppt),
            "appointment_late" to mapOf("id" to psyAppt, "minutes" to 10),
            "appt_pay_claim" to mapOf("appointmentId" to psyAppt, "payRef" to "ABC123XYZ9"),
        )) {
            assertEquals(404, call("cg", a, body + asP).status, a)
        }

        // confirm + session notes: the patient's pushes are generic, the caregiver gets none
        ok("psy", "appointment_update", mapOf("id" to psyAppt, "op" to "confirm"))
        val rx = ok("psy", "rx_create", mapOf("appointmentId" to psyAppt, "advice" to "শ্বাসের ব্যায়াম দিনে দুবার", "complaints" to "দুশ্চিন্তা"))
        val toPt = pushesTo("pt")
        assertTrue(toPt.isNotEmpty() && toPt.all { it == ("অ্যাপয়েন্টমেন্ট আপডেট" to "") }, toPt.toString())
        assertFalse(toPt.any { it.first.contains("কাউন্সেলর") || it.second.contains("কাউন্সেলর") })
        assertEquals(0, pushesTo("cg").size, "nothing to the caregiver")
        // a doctor's push still reaches the caregiver, with its text
        ok("doc", "appointment_update", mapOf("id" to docAppt, "op" to "confirm"))
        ok("doc", "rx_create", mapOf("appointmentId" to docAppt, "advice" to "বিশ্রাম"))
        assertTrue(pushesTo("cg").any { it.first.contains("ডা. রহিম") }, pushesTo("cg").toString())

        // notes: hidden from the caregiver
        assertFalse(ok("cg", "rx_mine", asP)["items"].any { it["id"].textValue() == rx["id"].textValue() })
        assertEquals(1, ok("cg", "rx_mine", asP)["items"].size(), "the doctor's prescription only")
        assertEquals(404, call("cg", "rx_get", asP + mapOf("id" to rx["id"].textValue())).status)
        assertEquals(404, call("cg", "rx_activity", asP + mapOf("rxId" to rx["id"].textValue())).status)
        // the patient sees everything
        assertEquals(2, ok("pt", "rx_mine")["items"].size())

        // only the patient can share; then the caregiver sees it
        assertEquals(403, call("cg", "care_share_psych", asP + mapOf("caretakerUid" to "cg", "share" to true)).status)
        assertEquals(404, call("pt", "care_share_psych", mapOf("caretakerUid" to "nobody", "share" to true)).status)
        assertEquals(true, ok("pt", "care_share_psych", mapOf("caretakerUid" to "cg", "share" to true))["sharePsych"].booleanValue())
        assertTrue(ok("pt", "care_list")["caretakers"][0]["sharePsych"].booleanValue())
        assertTrue(ok("cg", "my_appointments", asP)["items"].any { it["id"].textValue() == psyAppt })
        assertEquals(2, ok("cg", "rx_mine", asP)["items"].size())
        assertEquals(200, call("cg", "rx_get", asP + mapOf("id" to rx["id"].textValue())).status)
        // pushes after sharing reach the caregiver, still generic
        push.messages.clear()
        jdbc.update("DELETE FROM inbox_items")
        ok("psy", "appointment_update", mapOf("id" to psyAppt, "op" to "cancel", "providerNote" to "অসুস্থ"))
        ok("psy", "rx_create", mapOf("appointmentId" to psyAppt, "advice" to "পরের সপ্তাহে আবার"))
        assertTrue(pushesTo("cg").isNotEmpty() && pushesTo("cg").all { it == ("অ্যাপয়েন্টমেন্ট আপডেট" to "") }, pushesTo("cg").toString())
        // and off again
        ok("pt", "care_share_psych", mapOf("caretakerUid" to "cg", "share" to false))
        assertFalse(ok("cg", "my_appointments", asP)["items"].any { it["id"].textValue() == psyAppt })
    }

    // ------------------------------------------------------------------ §3 feature switches

    @Test
    fun `switches off - only allopathy, no system choice, no new professions; on - everything`() {
        clock.fixedMs = dhaka("2026-10-19 10:00")
        pro("allo")
        pro("homeo", system = "homeopathy", specialties = listOf("alt_general"))
        pro("physio", type = "physio", specialties = listOf("physio_back_neck"))
        person("pt")
        person("newDoc")
        features.allOn = false
        val dhaka = district("Dhaka")

        // practice_systems off: allopathy only, no labels/filter/chips
        val l = list("pt", mapOf("system" to "homeopathy"))
        assertEquals(setOf("allo"), uids(l["items"]), "the filter is ignored and non-allopathic doctors are not listed")
        assertTrue(l["items"][0]["system"].isNull && l["items"][0]["systemLabel"].isNull)
        assertFalse(l["facets"].has("systems"))
        assertEquals(404, book("pt", "homeo").status)
        // nobody registers as a non-allopathic doctor; allopathy as before
        val off = call("newDoc", "badge_apply", mapOf("type" to "doctor", "system" to "unani", "regNo" to "U-1", "workplace" to "X", "docs" to mapOf("cert" to jpeg)))
        assertEquals(listOf(404, "feature_off"), listOf(off.status, off.code))
        ok("newDoc", "badge_apply", mapOf("type" to "doctor", "regNo" to "A-1", "workplace" to "X"))
        assertEquals("allopathy", row("SELECT system FROM badges WHERE uid = 'newDoc'")["system"])
        // the stored prescription rules stay enforced even while the switch is off
        assertEquals("true", ok("homeo", "rx_me")["rxRules"]["freeText"].asText())

        // pro_physio / pro_psychologist off: their actions answer feature_off
        for ((a, body) in listOf(
            "providers" to mapOf("type" to "physio", "district" to dhaka),
            "home_providers" to mapOf("type" to "physio", "district" to dhaka),
            "badge_apply" to mapOf("type" to "psychologist", "regNo" to "P-1", "workplace" to "X"),
        )) {
            val r = call("pt", a, body)
            assertEquals(listOf(404, "feature_off"), listOf(r.status, r.code), a)
        }
        assertEquals("feature_off", book("pt", "physio").code)
        assertEquals("feature_off", call("physio", "rx_me").code)
        assertEquals("feature_off", call("physio", "provider_save", mapOf("specialties" to listOf("physio_stroke"), "chambers" to emptyList<Any>())).code)

        // on (pilot for the patient, on for the physio): everything back
        flag(Features.PRACTICE_SYSTEMS, "on")
        flag(Features.PRO_PHYSIO, "on")
        assertEquals(setOf("homeo"), uids(list("pt", mapOf("system" to "homeopathy"))["items"]))
        assertEquals(setOf("physio"), uids(ok("pt", "providers", mapOf("type" to "physio", "district" to dhaka, "homeVisit" to false))["items"]))
        assertEquals(200, book("pt", "physio").status)
        assertEquals(true, ok(null, "features")["keys"]["practice_systems"].booleanValue())
        assertEquals(false, ok(null, "features")["keys"]["pro_psychologist"].booleanValue())
    }
}
