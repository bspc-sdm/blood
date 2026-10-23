package com.lifolo.practice

import com.fasterxml.jackson.databind.JsonNode
import com.lifolo.appointments.Ch
import com.lifolo.appointments.P2TestBase
import com.lifolo.features.Features
import com.lifolo.support.setFeature
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders

/**
 * backend/docs/PRACTICE.md §6 — the "ডা." / "Dr." title rule (feature switch `title_guard`): who may carry the title,
 * every title variant, 400 `title_not_allowed` on saving a name, stored names stripped where they are shown (lists,
 * profile, appointments, the prescription header the PDF is drawn from, the public check page, receipts, pushes and
 * bell entries) but never changed in the database, the profession label, the admin list, switch off = as before, and
 * pilot mode.
 */
class TitleGuardIT : P2TestBase() {
    @Autowired lateinit var features: Features

    @Autowired lateinit var hospitals: com.lifolo.contracts.HospitalDirectory

    private val owner = "owner1"
    private val jpeg = "/9j/4AAQSkZJRgABAQ"
    private val day = 24 * 3600 * 1000L
    private val msg = "MBBS/BDS ছাড়া নামের আগে ‘ডা.’ বা ‘Dr.’ লেখা যাবে না"

    @AfterEach
    fun switchesBackOn() {
        features.allOn = true
    }

    private fun chamber() = Ch(
        hospitalId = hospitals.approvedIn(district("Dhaka")).first().id, hospitalName = "City Hospital", hospitalNameBn = "সিটি হাসপাতাল",
        district = district("Dhaka"), days = listOf(0, 1, 2, 3, 4, 5, 6), start = "09:00", end = "23:00", noFee = false, fee = "500",
        appointments = true,
    )

    /** an approved, listed professional with one chamber open every day — names stored as given */
    private fun pro(uid: String, name: String, type: String = "doctor", system: String? = null, specialties: List<String> = listOf("medicine")) {
        person(uid, name)
        badge(uid, type, name)
        provider(uid, listOf(chamber()), type = type, name = name, specialties = specialties)
        if (system != null) {
            jdbc.update("UPDATE badges SET system = ? WHERE uid = ?", system, uid)
            jdbc.update("UPDATE providers SET system = ? WHERE uid = ?", system, uid)
        }
    }

    private fun list(uid: String?, type: String = "doctor"): JsonNode =
        ok(uid, "providers", mapOf("type" to type, "district" to district("Dhaka"), "homeVisit" to false))["items"]

    private fun nameOf(items: JsonNode, uid: String): String = items.first { it["uid"].textValue() == uid }["name"].textValue()

    private fun rename(uid: String, name: String) = call(uid, "profile_update", mapOf("set" to mapOf("name" to name)))

    /** every switch this test needs on, title_guard as given */
    private fun only(mode: String, pilots: List<String> = emptyList(), demo: Boolean = false) {
        features.allOn = false
        for (k in listOf(Features.PRACTICE_SYSTEMS, Features.PRO_PHYSIO, Features.PRO_PSYCHOLOGIST)) setFeature(jdbc, features, k, Features.ON)
        setFeature(jdbc, features, Features.TITLE_GUARD, mode, pilots, demo)
    }

    private fun off() = only(Features.OFF)

    // ------------------------------------------------------------------ the rule (pure)

    @Test
    fun `rules - only an allopathic doctor (MBBS, BDS) may carry the title, every variant is found and stripped`() {
        assertTrue(TitleRules.eligible("doctor", "allopathy"))
        assertTrue(TitleRules.eligible("doctor", null), "an old doctor row = allopathy (dentists too)")
        for ((t, s) in listOf("doctor" to "homeopathy", "doctor" to "unani", "doctor" to "ayurveda", "nurse" to null, "nutritionist" to null, "trainer" to null, "physio" to null, "psychologist" to null, "expert" to null)) {
            assertFalse(TitleRules.eligible(t, s), "$t $s")
        }
        val variants = listOf(
            "ডা. করিম", "ডাঃ করিম", "ডা করিম", "ডাক্তার করিম", "Dr. Karim", "Dr Karim", "Dr: Karim", "DR. Karim", "Doctor Karim",
            "dr.Karim", "ডা.করিম", "ডাঃকরিম", "  Dr .  Karim", "DOCTOR Karim", "ডা: করিম", "doctor. Karim",
        )
        for (v in variants) {
            assertTrue(TitleRules.hasTitle(v), v)
            assertTrue(TitleRules.strip(v) == "করিম" || TitleRules.strip(v) == "Karim", "$v → ${TitleRules.strip(v)}")
        }
        for (n in listOf("ডালিয়া আক্তার", "Drabir Hasan", "Doctorate Ali", "করিম ডা.", "Dr.", "ডা.", "", "Andrew")) {
            assertFalse(TitleRules.hasTitle(n), n)
        }
        assertEquals("করিম", TitleRules.strip("ডা. ডা. করিম"))
        assertEquals("হোমিওপ্যাথিক চিকিৎসক", TitleRules.typeLabel("doctor", "homeopathy", true))
        assertEquals("ইউনানি চিকিৎসক", TitleRules.typeLabel("doctor", "unani", true))
        assertEquals("আয়ুর্বেদিক চিকিৎসক", TitleRules.typeLabel("doctor", "ayurveda", true))
        assertEquals("ডাক্তার", TitleRules.typeLabel("doctor", "allopathy", true))
        assertEquals("ডাক্তার", TitleRules.typeLabel("doctor", "homeopathy", false), "switch off: as before")
        assertEquals("ফিজিওথেরাপিস্ট", TitleRules.typeLabel("physio", null, true))
        assertEquals("ক্লিনিক্যাল সাইকোলজিস্ট / কাউন্সেলর", TitleRules.typeLabel("psychologist", null, true))
    }

    // ------------------------------------------------------------------ saving a name

    @Test
    fun `saving - non-eligible providers cannot write any title variant, MBBS and BDS doctors and other people can`() {
        pro("allo", "Allo")
        pro("dentist", "Dent", specialties = listOf("dental"))
        pro("homeo", "Homeo", system = "homeopathy", specialties = listOf("alt_general"))
        pro("unani", "Unani", system = "unani", specialties = listOf("alt_general"))
        pro("ayur", "Ayur", system = "ayurveda", specialties = listOf("alt_general"))
        pro("nurse", "Nurse", type = "nurse")
        pro("nutri", "Nutri", type = "nutritionist", specialties = emptyList())
        pro("physio", "Physio", type = "physio", specialties = listOf("physio_back_neck"))
        pro("psy", "Psy", type = "psychologist", specialties = listOf("psy_trauma"))
        person("plain")

        for (variant in listOf("ডা. ", "ডাঃ ", "ডা ", "ডাক্তার ", "Dr. ", "Dr ", "Dr:", "DR. ", "Doctor ", "dr.")) {
            for (uid in listOf("homeo", "unani", "ayur", "nurse", "nutri", "physio", "psy")) {
                val r = rename(uid, "${variant}নাম")
                assertEquals(listOf(400, "title_not_allowed"), listOf(r.status, r.code), "$uid '$variant'")
                assertEquals(msg, r.body["error"].textValue())
            }
            for (uid in listOf("allo", "dentist", "plain")) assertEquals(200, rename(uid, "${variant}নাম").status, "$uid '$variant'")
        }
        // a name without a title is fine; nothing was saved by the refused calls
        assertEquals(200, rename("homeo", "আনোয়ার হোসেন").status)
        assertEquals("আনোয়ার হোসেন", row("SELECT name FROM user_profiles WHERE uid = 'homeo'")["name"])
        assertEquals("Nurse", row("SELECT name FROM user_profiles WHERE uid = 'nurse'")["name"])
        // English answers in English
        assertEquals("Only MBBS/BDS doctors may write ‘Dr.’ before the name", call("nurse", "profile_update", mapOf("set" to mapOf("name" to "Dr. N")), lang = "en").body["error"].textValue())
    }

    @Test
    fun `saving - badge applications and corrections take the profile name, a new listing too`() {
        person("n1", "ডা. নার্গিস")
        val r = call("n1", "badge_apply", mapOf("type" to "nurse", "regNo" to "N-1", "workplace" to "X", "docs" to mapOf("cert" to jpeg, "idcard" to jpeg)))
        assertEquals(listOf(400, "title_not_allowed"), listOf(r.status, r.code))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM badges WHERE uid = 'n1'", Int::class.java))
        // a homeopath neither; an allopathic doctor may
        person("h1", "Dr. Homeo")
        assertEquals("title_not_allowed", call("h1", "badge_apply", mapOf("type" to "doctor", "system" to "homeopathy", "regNo" to "H-1", "workplace" to "X", "docs" to mapOf("cert" to jpeg))).code)
        person("d1", "Dr. Allo")
        ok("d1", "badge_apply", mapOf("type" to "doctor", "regNo" to "A-1", "workplace" to "X"))
        // an approved allopathic doctor changing to homeopathy (re-verification) with a titled name: no
        jdbc.update("UPDATE badges SET status = 'approved' WHERE uid = 'd1'")
        assertEquals("title_not_allowed", call("d1", "badge_apply", mapOf("type" to "doctor", "system" to "homeopathy", "regNo" to "H-2", "workplace" to "X", "docs" to mapOf("cert" to jpeg))).code)
        // a correction of an approved nurse badge with a titled profile name: no
        pro("n2", "নার্স দুই", type = "nurse")
        jdbc.update("UPDATE user_profiles SET name = 'ডাঃ নার্স দুই' WHERE uid = 'n2'")
        assertEquals("title_not_allowed", call("n2", "badge_apply", mapOf("type" to "nurse", "regNo" to "N-2", "workplace" to "Y")).code)

        // a new listing (provider_save) of an approved physio whose badge still has a title: refused;
        // an existing listing keeps working (its name is stripped where shown)
        person("ph", "Dr. Physio")
        badge("ph", "physio", "Dr. Physio")
        val save = mapOf(
            "specialties" to listOf("physio_back_neck"), "listed" to true, "appointments" to true,
            "chambers" to listOf(mapOf("hospitalId" to chamber().hospitalId, "days" to listOf(0, 1, 2, 3, 4, 5, 6), "start" to "09:00", "end" to "23:00", "fee" to "300", "appointments" to true)),
        )
        val first = call("ph", "provider_save", save)
        assertEquals(listOf(400, "title_not_allowed"), listOf(first.status, first.code), first.toString())
        pro("ph2", "Dr. Physio Two", type = "physio", specialties = listOf("physio_back_neck"))
        assertEquals(200, call("ph2", "provider_save", save).status)
    }

    // ------------------------------------------------------------------ showing names

    @Test
    fun `showing - stored titles of non-eligible providers stripped everywhere they are shown, never changed in the database`() {
        clock.fixedMs = dhaka("2026-10-19 10:00")
        pro("allo", "ডা. এলো রহমান")
        pro("homeo", "ডা. হোমিও আনোয়ার", system = "homeopathy", specialties = listOf("alt_general"))
        jdbc.update("UPDATE providers SET degrees = 'DHMS' WHERE uid = 'homeo'")
        // an eligible doctor with exactly the same name as a non-eligible one keeps it (never stripped)
        pro("twin", "Dr. Twin", system = null)
        pro("twinNurse", "Dr. Twin", type = "nurse")
        person("pt", "রোগী", extra = mapOf("phone" to "01711000001"))

        // lists and the provider's own listing
        val items = list("pt")
        assertEquals("ডা. এলো রহমান", nameOf(items, "allo"))
        assertEquals("হোমিও আনোয়ার", nameOf(items, "homeo"))
        assertEquals("Dr. Twin", nameOf(items, "twin"))
        val homeoItem = items.first { it["uid"].textValue() == "homeo" }
        assertEquals(listOf("হোমিওপ্যাথিক চিকিৎসক", "Homeopathic practitioner"), listOf(homeoItem["typeLabel"].textValue(), homeoItem["typeLabelEn"].textValue()))
        assertEquals("ডাক্তার", items.first { it["uid"].textValue() == "allo" }["typeLabel"].textValue())
        assertEquals("হোমিও আনোয়ার", ok("homeo", "provider_me")["provider"]["name"].textValue())

        // appointment: the patient's list, the confirm push and bell entry
        val appt = ok("pt", "book", mapOf("providerUid" to "homeo", "date" to today() + day, "chamberIndex" to 0, "note" to "জ্বর"))["id"].textValue()
        ok("homeo", "appointment_update", mapOf("id" to appt, "op" to "confirm"))
        val mine = ok("pt", "my_appointments")["items"].first { it["id"].textValue() == appt }
        assertFalse(mine.toString().contains("ডা. হোমিও"), mine.toString())
        assertTrue(mine.toString().contains("হোমিও আনোয়ার"), mine.toString())

        // prescription: the header the screen and the PDF are drawn from, the push, the public check page
        val rx = ok("homeo", "rx_create", mapOf("appointmentId" to appt, "medicines" to listOf("Arnica 30"), "advice" to "বিশ্রাম"))
        val item = ok("pt", "rx_get", mapOf("id" to rx["id"].textValue()))["item"]
        assertEquals(listOf("হোমিও আনোয়ার", "DHMS", "হোমিওপ্যাথিক চিকিৎসক"), listOf(item["doctor"]["name"].textValue(), item["doctor"]["degrees"].textValue(), item["doctor"]["typeLabel"].textValue()))
        assertEquals("হোমিও আনোয়ার", ok("pt", "rx_mine")["items"].first()["doctor"]["name"].textValue())
        val pushes = inbox("pt")
        assertTrue(pushes.isNotEmpty())
        assertFalse(pushes.any { it.first.contains("ডা. হোমিও") || it.second.contains("ডা. হোমিও") }, pushes.toString())
        assertTrue(pushes.any { it.first.contains("হোমিও আনোয়ার") || it.second.contains("হোমিও আনোয়ার") }, pushes.toString())
        assertFalse(push.messages.any { it.data.values.any { v -> v.contains("ডা. হোমিও") } }, "phone pushes")
        val page = mvc.perform(MockMvcRequestBuilders.get("/rx/${rx["code"].textValue()}")).andReturn().response.getContentAsString(Charsets.UTF_8)
        assertFalse(page.contains("ডা. হোমিও"), page)
        assertTrue(page.contains("হোমিও আনোয়ার") && page.contains("যাচাই করা পেশাজীবীর"), page)
        assertFalse(page.contains("যাচাই করা ডাক্তারের"), page)

        // a chamber receipt (the issuer's name is a copy) — the public receipt check
        jdbc.update(
            """
            INSERT INTO invoices (id, kind, biz_id, owner_uid, no, series, seq, biz_name, patient_uid, lines, subtotal, total, paid, due, issued_at, verify_code)
            VALUES ('inv1', 'chamber', 'homeo', 'homeo', 'W-000001', 'W', 1, 'ডা. হোমিও আনোয়ার', 'pt', '[]', 500, 500, 500, 0, now(), 'ABCDEFGH23')
            """.trimIndent(),
        )
        assertEquals("হোমিও আনোয়ার", ok(null, "invoice_verify", mapOf("code" to "ABCDEFGH23"))["invoice"]["bizName"].textValue())

        // the database still has every stored name as written (reversible: switch off shows them again)
        assertEquals("ডা. হোমিও আনোয়ার", row("SELECT name FROM providers WHERE uid = 'homeo'")["name"])
        assertEquals("ডা. হোমিও আনোয়ার", row("SELECT provider_name FROM appointments WHERE id = ?", appt)["provider_name"])
        assertEquals("ডা. হোমিও আনোয়ার", row("SELECT doctor_name FROM prescriptions WHERE id = ?", rx["id"].textValue())["doctor_name"])
        // one's own profile shows the stored name (the edit form shows the title, so the provider removes it)
        assertEquals("ডা. হোমিও আনোয়ার", ok("homeo", "profile_get")["profile"]["name"].textValue())

        // switch off: everything as stored again, "ডাক্তার" as before
        off()
        assertEquals("ডা. হোমিও আনোয়ার", nameOf(list("pt"), "homeo"))
        assertEquals("ডাক্তার", list("pt").first { it["uid"].textValue() == "homeo" }["typeLabel"].textValue())
        assertEquals("ডা. হোমিও আনোয়ার", ok("pt", "rx_get", mapOf("id" to rx["id"].textValue()))["item"]["doctor"]["name"].textValue())
        val pageOff = mvc.perform(MockMvcRequestBuilders.get("/rx/${rx["code"].textValue()}")).andReturn().response.getContentAsString(Charsets.UTF_8)
        assertTrue(pageOff.contains("ডা. হোমিও আনোয়ার") && pageOff.contains("যাচাই করা ডাক্তারের"), pageOff)
        assertEquals(200, rename("homeo", "ডা. হোমিও আনোয়ার").status, "saving is not checked either")
    }

    @Test
    fun `showing - unani, ayurveda labels, physio and psychologist labels unchanged, chat and call names`() {
        pro("unani", "Dr. Unani", system = "unani", specialties = listOf("alt_general"))
        pro("ayur", "ডাঃ আয়ুর", system = "ayurveda", specialties = listOf("alt_general"))
        pro("physio", "ডাক্তার ফিজিও", type = "physio", specialties = listOf("physio_back_neck"))
        person("pt")
        val items = list("pt")
        assertEquals("ইউনানি চিকিৎসক", items.first { it["uid"].textValue() == "unani" }["typeLabel"].textValue())
        assertEquals(listOf("Unani", "আয়ুর"), listOf(nameOf(items, "unani"), nameOf(items, "ayur")))
        assertEquals("আয়ুর্বেদিক চিকিৎসক", items.first { it["uid"].textValue() == "ayur" }["typeLabel"].textValue())
        val physio = list("pt", "physio").first { it["uid"].textValue() == "physio" }
        assertEquals(listOf("ফিজিও", "ফিজিওথেরাপিস্ট"), listOf(physio["name"].textValue(), physio["typeLabel"].textValue()))
        // call/chat first names (lock screen): every title variant left out for a viewer with the rule on
        val calls = ctx.getBean(com.lifolo.calls.CallService::class.java)
        assertEquals("ফিজিও", calls.firstNameFor("ডাক্তার ফিজিও", "pt"))
        assertEquals("Unani", calls.firstNameFor("Dr: Unani", "pt"))
        // SSE and pushes go through the same filter
        assertEquals("ফিজিও · কাল", titleGuard.text("ডাক্তার ফিজিও · কাল", "pt"))
        assertEquals("ডাক্তার ফিজিওথেরাপি বিভাগ", titleGuard.text("ডাক্তার ফিজিওথেরাপি বিভাগ", "pt"), "a longer word is not the name")
        assertEquals("ফিজিও", titleGuard.name("ডাক্তার ফিজিও", "pt"))
    }

    @Autowired lateinit var ctx: org.springframework.context.ApplicationContext

    // ------------------------------------------------------------------ admin list

    @Test
    fun `admin list - providers who may not carry the title but have it, as stored, whether the switch is on or not`() {
        person(owner, "মালিক", "owner@example.com")
        pro("allo", "ডা. এলো")
        pro("homeo", "ডা. হোমিও", system = "homeopathy", specialties = listOf("alt_general"))
        pro("nurse", "নার্স", type = "nurse")
        jdbc.update("UPDATE user_profiles SET name = 'Dr. Nurse' WHERE uid = 'nurse'")
        pro("clean", "পরিষ্কার নাম", type = "physio", specialties = listOf("physio_back_neck"))
        pro("demo-x-nurse", "ডা. ডেমো নার্স", type = "nurse")
        off()
        for (mode in listOf(Features.OFF, Features.ON)) {
            setFeature(jdbc, features, Features.TITLE_GUARD, mode)
            val r = ok(owner, "admin_title_names")
            val byUid = r["items"].associateBy { it["uid"].textValue() }
            assertEquals(setOf("homeo", "nurse", "demo-x-nurse"), byUid.keys, mode)
            assertEquals(listOf(3, 2), listOf(r["count"].intValue(), r["real"].intValue()))
            assertEquals("‘ডা.’ লেখা নাম", r["title"].textValue())
            assertEquals(listOf("ডা. হোমিও", "হোমিও", "হোমিওপ্যাথিক চিকিৎসক"), listOf("name", "stripped", "typeLabel").map { byUid["homeo"]!![it].textValue() })
            assertEquals(listOf("profile"), byUid["nurse"]!!["where"].map { it.textValue() })
            assertEquals("Dr. Nurse", byUid["nurse"]!!["name"].textValue())
            assertTrue(byUid["demo-x-nurse"]!!["demo"].booleanValue())
        }
        person("pt")
        assertEquals(403, call("pt", "admin_title_names").status)
    }

    // ------------------------------------------------------------------ switch off / pilot

    @Test
    fun `switch off - names, labels and saving exactly as before`() {
        off()
        pro("homeo", "ডা. হোমিও", system = "homeopathy", specialties = listOf("alt_general"))
        pro("nurse", "নার্স", type = "nurse")
        person("pt")
        val items = list("pt")
        assertEquals("ডা. হোমিও", nameOf(items, "homeo"))
        assertEquals(listOf("ডাক্তার", "Doctor"), listOf(items[0]["typeLabel"].textValue(), items[0]["typeLabelEn"].textValue()))
        assertEquals(200, rename("nurse", "ডা. নার্স").status)
        assertFalse(ok("pt", "features")["keys"]["title_guard"].booleanValue())
    }

    @Test
    fun `pilot - pilot viewers see stripped names and labels, pilot providers cannot save a title, everyone else as before`() {
        pro("homeo", "ডা. হোমিও", system = "homeopathy", specialties = listOf("alt_general"))
        pro("nurseP", "নার্স পি", type = "nurse")
        pro("nurseX", "নার্স এক্স", type = "nurse")
        pro("demo-x-nurse", "নার্স ডেমো", type = "nurse")
        person("ptPilot")
        person("ptOther")
        only(Features.PILOT, listOf("ptPilot", "nurseP"), demo = true)

        val pilot = list("ptPilot").first { it["uid"].textValue() == "homeo" }
        assertEquals(listOf("হোমিও", "হোমিওপ্যাথিক চিকিৎসক"), listOf(pilot["name"].textValue(), pilot["typeLabel"].textValue()))
        val other = list("ptOther").first { it["uid"].textValue() == "homeo" }
        assertEquals(listOf("ডা. হোমিও", "ডাক্তার"), listOf(other["name"].textValue(), other["typeLabel"].textValue()))
        // guests and public pages: only when the switch is on for everyone
        assertEquals("ডা. হোমিও", nameOf(list(null), "homeo"))

        assertEquals("title_not_allowed", rename("nurseP", "ডা. নার্স").code)
        assertEquals(200, rename("nurseX", "ডা. নার্স").status)
        assertEquals("title_not_allowed", rename("demo-x-nurse", "Dr. Demo").code, "demo accounts with demo: true")
        assertTrue(ok("ptPilot", "features")["keys"]["title_guard"].booleanValue())
        assertFalse(ok("ptOther", "features")["keys"]["title_guard"].booleanValue())

        // pushes follow the recipient
        assertEquals("হোমিও · কাল", titleGuard.text("ডা. হোমিও · কাল", "ptPilot"))
        assertEquals("ডা. হোমিও · কাল", titleGuard.text("ডা. হোমিও · কাল", "ptOther"))
    }
}
