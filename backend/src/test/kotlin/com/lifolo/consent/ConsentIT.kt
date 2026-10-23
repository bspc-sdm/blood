package com.lifolo.consent

import com.lifolo.appointments.Ch
import com.lifolo.appointments.P2TestBase
import com.lifolo.blood.BloodRules
import com.lifolo.features.Features
import com.lifolo.support.setFeature
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * backend/docs/CONSENT.md: explicit, recorded consent for health data (`health_data`) and for storage abroad
 * (`data_abroad`) behind the `health_consent` switch — status/grant/withdraw, the audit log, the gate on every
 * patient-initiated health data entry (428 `consent_required`), caregivers, text version bumps, emergency paths that
 * are never gated, provider-created records, pilot mode and the switch off.
 *
 * The test profile has every key on except `health_consent` (Features.OUTSIDE_ALL_ON); each test switches it.
 */
class ConsentIT : P2TestBase() {
    @Autowired lateinit var features: Features

    @Autowired lateinit var consentText: ConsentText

    private val day = 24 * 3600 * 1000L
    private val both = listOf(Consents.HEALTH_DATA, Consents.DATA_ABROAD)

    @AfterEach
    fun backToDefaults() {
        consentText.versionOverride = null
        jdbc.update("DELETE FROM feature_flags")
        features.clearCache()
    }

    private fun on(mode: String = Features.ON, pilots: List<String> = emptyList(), demo: Boolean = false) =
        setFeature(jdbc, features, Features.HEALTH_CONSENT, mode, pilots, demo)

    private fun chamber() = Ch(
        hospitalId = "dghs_1", hospitalName = "City Hospital", hospitalNameBn = "সিটি হাসপাতাল", district = district("Dhaka"),
        days = listOf(0, 1, 2, 3, 4, 5, 6), start = "09:00", end = "23:00", noFee = false, fee = "500", appointments = true,
    )

    /** an approved, listed professional with one chamber open every day */
    private fun pro(uid: String, type: String = "doctor", specialties: List<String> = listOf("medicine")) {
        person(uid, uid)
        badge(uid, type, uid)
        provider(uid, listOf(chamber()), type = type, name = uid, specialties = specialties)
    }

    private fun book(patient: String, provider: String, asUid: String? = null) = call(
        patient, "book",
        mapOf("providerUid" to provider, "date" to today() + day, "chamberIndex" to 0, "note" to "সমস্যা") +
            (if (asUid != null) mapOf("asUid" to asUid) else emptyMap()),
    )

    private fun grant(uid: String, scopes: List<String> = both, source: String = "app") =
        ok(uid, "consent_grant", mapOf("scopes" to scopes, "version" to consentText.version, "source" to source))

    private fun rows(uid: String): List<Map<String, Any?>> =
        jdbc.queryForList("SELECT scope, version, action, source FROM consents WHERE uid = ? ORDER BY id", uid)

    private fun assertConsentRequired(r: Res, scopes: List<String> = both, caregiver: Boolean = false, what: String = "") {
        assertEquals(428, r.status, "$what $r")
        assertEquals("consent_required", r.code, what)
        assertEquals(scopes, r.body["scopes"].map { it.textValue() }, what)
        assertEquals(consentText.version, r.body["version"].textValue(), what)
        assertEquals(caregiver, r.body["caregiver"].booleanValue(), what)
    }

    /** every gated call: (what, actor, action, body) — set up by [gatedSetup] */
    private fun gatedCalls(): List<Triple<String, String, Map<String, Any?>>> = listOf(
        Triple("add a prescription", "rx_claim", mapOf("code" to "ABCD2345EF", "phone" to "01711000001")),
        Triple("blood group", "profile_update", mapOf("set" to mapOf("bloodGroup" to "A+"))),
        Triple("weight", "profile_update", mapOf("set" to mapOf("weightKg" to 61))),
        Triple("last donation (profile)", "profile_update", mapOf("set" to mapOf("lastDonationMillis" to BloodRules.todayDhakaUtcMidnight() - 200 * day))),
        Triple("last donation (change date)", "donation_date_set", mapOf("millis" to BloodRules.todayDhakaUtcMidnight() - 100 * day, "confirmed" to true)),
        Triple("psychologist appointment", "book", mapOf("providerUid" to "psy", "date" to today() + day, "chamberIndex" to 0, "note" to "ঘুম হয় না")),
        Triple("invite a caregiver", "care_invite", mapOf("email" to "cg2@example.com")),
        Triple("share psychologist appointments with a caregiver", "care_share_psych", mapOf("caretakerUid" to "cg", "share" to true)),
        Triple("online order with a prescription", "rx_send_pharmacy", mapOf("rxId" to "rx1", "pharmacyId" to "ph1", "mode" to "online", "address" to "বাসা ১", "phone" to "01711000001")),
        Triple("lab home collection", "rx_send_lab", mapOf("rxId" to "rx1", "labId" to "lab1", "home" to mapOf("address" to "বাসা ১", "phone" to "01711000001", "day" to 1, "slot" to "morning"))),
        Triple("change a lab home collection", "lab_home_change", mapOf("id" to "lo1", "home" to mapOf("address" to "বাসা ২", "phone" to "01711000001", "day" to 1, "slot" to "morning"))),
    )

    private fun gatedSetup() {
        clock.fixedMs = dhaka("2026-10-19 10:00")
        pro("psy", type = "psychologist", specialties = listOf("psy_trauma"))
        pro("doc")
        person("pt", "রোগী রহিমা", extra = mapOf("phone" to "01711000001", "blood_group" to "O+", "weight_kg" to 60.0))
        person("cg", "কেয়ারগিভার রুমা")
        jdbc.update("INSERT INTO care_links (patient_uid, caretaker_uid, status) VALUES ('pt', 'cg', 'active')")
    }

    // ------------------------------------------------------------------ the switch off

    @Test
    fun `switch off - nothing changes, no status to ask, granting is off, withdrawing still works`() {
        gatedSetup()
        for ((what, action, body) in gatedCalls()) {
            assertNotEquals(428, call("pt", action, body).status, what)
        }
        assertEquals("A+", row("SELECT blood_group FROM user_profiles WHERE uid = 'pt'")["blood_group"])
        val s = ok("pt", "consent_status")
        assertEquals(listOf(false, false), listOf(s["on"].booleanValue(), s["ask"].booleanValue()))
        val g = call("pt", "consent_grant", mapOf("scopes" to both, "version" to consentText.version, "source" to "app"))
        assertEquals(listOf(404, "feature_off"), listOf(g.status, g.code))
        // a consent given while it was on can still be withdrawn after the switch went off
        on()
        grant("pt")
        jdbc.update("DELETE FROM feature_flags")
        features.clearCache()
        assertEquals(listOf("health_data", "data_abroad"), ok("pt", "consent_withdraw", mapOf("scope" to "all", "source" to "web"))["recorded"].map { it.textValue() })
        assertEquals(false, ok(null, "features")["keys"][Features.HEALTH_CONSENT].booleanValue())
    }

    // ------------------------------------------------------------------ status / grant / audit

    @Test
    fun `status, grant and the append-only audit log`() {
        on()
        person("pt")
        val s = ok("pt", "consent_status")
        assertEquals(listOf(true, true, false), listOf(s["on"].booleanValue(), s["ask"].booleanValue(), s["current"].booleanValue()))
        assertEquals(both, s["needed"].map { it.textValue() })
        assertEquals(listOf("none", "none"), both.map { s["scopes"][it]["state"].textValue() })
        assertEquals(consentText.fileVersion, s["version"].textValue())
        // the text in the caller's language, from the one versioned file
        assertEquals("স্বাস্থ্য তথ্য রাখার আগে আপনার সম্মতি", s["text"]["title"].textValue())
        assertEquals("রাজি আছি", s["text"]["agree"].textValue())
        assertEquals("এখন না", s["text"]["later"].textValue())
        assertEquals(setOf("what", "why", "where", "who", "how_long", "withdraw"), s["text"]["sections"].map { it["key"].textValue() }.toSet())
        assertTrue(s["text"]["sections"].first { it["key"].textValue() == "how_long" }["linkUrl"].textValue().startsWith("https://lifolo.com/privacy#"))
        assertEquals("I agree", ok("pt", "consent_status", lang = "en")["text"]["agree"].textValue())

        // bad requests
        val wrongVersion = call("pt", "consent_grant", mapOf("scopes" to both, "version" to "1999-01-01.1", "source" to "app"))
        assertEquals(listOf(409, "consent_outdated"), listOf(wrongVersion.status, wrongVersion.code))
        assertEquals(consentText.version, wrongVersion.body["version"].textValue())
        assertEquals("bad_scope", call("pt", "consent_grant", mapOf("scopes" to listOf("everything"), "version" to consentText.version, "source" to "app")).code)
        assertEquals("bad_scope", call("pt", "consent_grant", mapOf("scopes" to emptyList<String>(), "version" to consentText.version, "source" to "app")).code)
        assertEquals("bad_source", call("pt", "consent_grant", mapOf("scopes" to both, "version" to consentText.version)).code)
        assertEquals("bad_source", call("pt", "consent_grant", mapOf("scopes" to both, "version" to consentText.version, "source" to "sms")).code)
        assertEquals(0, rows("pt").size)

        // one button, two separate records
        val g = grant("pt", source = "web")
        assertEquals(both, g["recorded"].map { it.textValue() })
        assertEquals(listOf(true, false), listOf(g["current"].booleanValue(), g["ask"].booleanValue()))
        assertTrue(g["grantedAt"].longValue() > 0)
        assertEquals(listOf("granted", "granted"), both.map { g["scopes"][it]["state"].textValue() })
        assertEquals(
            listOf(listOf("health_data", consentText.version, "granted", "web"), listOf("data_abroad", consentText.version, "granted", "web")),
            rows("pt").map { it.values.toList() },
        )
        // granting again records nothing new
        assertEquals(0, grant("pt", source = "desktop")["recorded"].size())
        assertEquals(2, rows("pt").size)
        assertEquals(2, ok("pt", "consent_status")["history"].size())
        // history is never rewritten
        assertTrue(runCatching { jdbc.update("UPDATE consents SET action = 'withdrawn' WHERE uid = 'pt'") }.isFailure)
        // the records go with the account
        jdbc.update("DELETE FROM accounts WHERE uid = 'pt'")
        assertEquals(0, rows("pt").size)
    }

    // ------------------------------------------------------------------ the gate

    @Test
    fun `every gated action needs a current consent, and goes through once it is given`() {
        on()
        gatedSetup()
        for ((what, action, body) in gatedCalls()) {
            val r = call("pt", action, body)
            assertConsentRequired(r, what = what)
            assertEquals("এটা করতে আগে স্বাস্থ্য তথ্য রাখার সম্মতি দিন (অ্যাপ হালনাগাদ করুন, বা lifolo.com-এ লগইন করে দিন)", r.error, what)
        }
        // nothing was written
        assertEquals(listOf("O+", 60.0, null), row("SELECT blood_group, weight_kg, last_donation_ms FROM user_profiles WHERE uid = 'pt'").values.toList())
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM appointments WHERE patient_uid = 'pt'", Int::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM care_email_invites WHERE patient_uid = 'pt'", Int::class.java))
        assertEquals(false, row("SELECT share_psych FROM care_links WHERE patient_uid = 'pt'")["share_psych"])
        assertEquals("Please give consent for your health information first (update the app, or log in at lifolo.com)", call("pt", "rx_claim", mapOf("code" to "ABCD2345EF"), lang = "en").error)

        // not an entry of health data: never asked
        for ((what, action, body) in listOf(
            Triple("name only", "profile_update", mapOf("set" to mapOf("name" to "রহিমা"))),
            Triple("the same blood group and weight again (the form resends them)", "profile_update", mapOf("set" to mapOf("bloodGroup" to "O+", "weightKg" to 60, "name" to "রহিমা খাতুন"))),
            Triple("clearing the blood group", "profile_update", mapOf("set" to mapOf("bloodGroup" to ""))),
            Triple("a doctor (not a psychologist)", "book", mapOf("providerUid" to "doc", "date" to today() + day, "chamberIndex" to 0, "note" to "জ্বর")),
            Triple("stop sharing", "care_share_psych", mapOf("caretakerUid" to "cg", "share" to false)),
            Triple("send to a shop (no delivery)", "rx_send_pharmacy", mapOf("rxId" to "rx1", "pharmacyId" to "ph1", "mode" to "shop")),
            Triple("send tests without home collection", "rx_send_lab", mapOf("rxId" to "rx1", "labId" to "lab1")),
            Triple("reading my prescriptions", "rx_mine", emptyMap()),
            Triple("reading my appointments", "my_appointments", emptyMap()),
        )) {
            val r = call("pt", action, body)
            assertNotEquals(428, r.status, "$what $r")
        }
        assertEquals("রহিমা খাতুন", row("SELECT name FROM user_profiles WHERE uid = 'pt'")["name"])
        // blood group cleared above: setting it again is an entry
        assertConsentRequired(call("pt", "profile_update", mapOf("set" to mapOf("bloodGroup" to "O+"))))

        // consent given: every call passes the gate (and the simple ones do their job)
        grant("pt")
        for ((what, action, body) in gatedCalls()) {
            val r = call("pt", action, body)
            assertNotEquals(428, r.status, "$what $r")
        }
        val p = row("SELECT blood_group, weight_kg, last_donation_ms FROM user_profiles WHERE uid = 'pt'")
        assertEquals(listOf("A+", 61.0), listOf(p["blood_group"], p["weight_kg"]))
        assertEquals(BloodRules.todayDhakaUtcMidnight() - 100 * day, p["last_donation_ms"])
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM appointments a WHERE patient_uid = 'pt' AND provider_uid = 'psy'", Int::class.java))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM care_email_invites WHERE patient_uid = 'pt'", Int::class.java))
        assertEquals(true, row("SELECT share_psych FROM care_links WHERE patient_uid = 'pt'")["share_psych"])
        // the gated list is exactly what CONSENT.md documents
        assertEquals(
            setOf("rx_claim", "profile_update", "donation_date_set", "book", "care_invite", "care_share_psych", "rx_send_pharmacy", "rx_send_lab", "lab_home_change"),
            ConsentGate.GATED,
        )
    }

    @Test
    fun `one scope missing - only that scope is asked for`() {
        on()
        gatedSetup()
        grant("pt", listOf(Consents.HEALTH_DATA))
        assertConsentRequired(call("pt", "rx_claim", mapOf("code" to "ABCD2345EF", "phone" to "01711000001")), listOf(Consents.DATA_ABROAD))
        assertEquals(listOf("data_abroad"), ok("pt", "consent_status")["needed"].map { it.textValue() })
        grant("pt", listOf(Consents.DATA_ABROAD))
        assertNotEquals(428, call("pt", "rx_claim", mapOf("code" to "ABCD2345EF", "phone" to "01711000001")).status)
    }

    // ------------------------------------------------------------------ emergency paths

    @Test
    fun `emergency features are never behind consent`() {
        on()
        person("pt", extra = mapOf("phone" to "01711000001"))
        // a real blood request goes through without any consent
        val req = mapOf(
            "lat" to 24.75, "lng" to 90.40, "patientGroup" to "A+", "bags" to 1, "hospital" to "মমেক", "placeName" to district("Mymensingh"),
            "neededByMillis" to BloodRules.todayDhakaUtcMidnight() + day, "requesterPhone" to "01712345678", "requesterName" to "রহিম", "urgent" to true,
        )
        val c = call("pt", "request_create", req)
        assertEquals(200, c.status, c.toString())
        // every emergency/listing action: never 428, whatever its body
        for (a in ConsentGate.NEVER_GATED) {
            val r = call("pt", a, emptyMap())
            assertNotEquals(428, r.status, "$a $r")
            assertNotEquals("consent_required", r.code, a)
        }
        // the blood HTTP endpoints too
        for (path in listOf("/api/respond", "/api/close-request", "/api/notify-request", "/api/edit-request")) {
            val r = post(path, mapOf("requestId" to c.body["id"].textValue(), "lang" to "bn"), mapOf("Authorization" to "Bearer pt"))
            assertNotEquals(428, r.status, "$path $r")
        }
        // listings for guests
        assertNotEquals(428, call(null, "providers", mapOf("type" to "doctor", "district" to district("Dhaka"))).status)
        assertTrue(ConsentGate.GATED.intersect(ConsentGate.NEVER_GATED).isEmpty())
    }

    // ------------------------------------------------------------------ provider-created records

    @Test
    fun `records a provider makes are saved without the patient's consent; the patient is asked afterwards`() {
        on()
        clock.fixedMs = dhaka("2026-10-19 10:00")
        pro("physio", type = "physio", specialties = listOf("physio_back_neck"))
        person("pt")
        // booking a physio is not a psychologist appointment: no consent needed
        val appt = book("pt", "physio")
        assertEquals(200, appt.status, appt.toString())
        val rx = call("physio", "rx_create", mapOf("appointmentId" to appt.body["id"].textValue(), "medicines" to emptyList<Any>(), "advice" to "প্রতিদিন হাঁটুন"))
        assertEquals(200, rx.status, rx.toString())
        // the patient sees it (reading is never gated) …
        assertTrue(ok("pt", "rx_mine").toString().contains(rx.body["id"].textValue()))
        // … and the app asks for consent when they open their health sections
        assertTrue(ok("pt", "consent_status")["ask"].booleanValue())
        for (a in listOf("rx_create", "lab_report", "rx_patient_add", "rx_dispense", "lab_home_confirm")) assertFalse(a in ConsentGate.GATED, a)
    }

    // ------------------------------------------------------------------ caregivers

    @Test
    fun `a caregiver cannot consent for the patient, and is told the patient has to`() {
        on()
        gatedSetup()
        val asP = mapOf("asUid" to "pt")
        for ((a, body) in listOf(
            "consent_grant" to mapOf("scopes" to both, "version" to consentText.version, "source" to "app"),
            "consent_withdraw" to mapOf("scope" to "all", "source" to "app"),
            "consent_status" to emptyMap(),
        )) {
            val r = call("cg", a, body + asP)
            assertEquals(listOf(403, "consent_self_only"), listOf(r.status, r.code), a)
            assertEquals("রোগীকে নিজে একবার সম্মতি দিতে হবে", r.error, a)
        }
        assertEquals(0, rows("pt").size)
        // a gated action for the patient: 428 with caregiver: true
        for ((a, body) in listOf(
            "rx_claim" to mapOf("code" to "ABCD2345EF", "phone" to "01711000001"),
            "rx_send_pharmacy" to mapOf("rxId" to "rx1", "pharmacyId" to "ph1", "mode" to "online"),
            "rx_send_lab" to mapOf("rxId" to "rx1", "labId" to "lab1", "home" to mapOf("address" to "x")),
        )) {
            val r = call("cg", a, body + asP)
            assertConsentRequired(r, caregiver = true, what = a)
            assertEquals("রোগীকে নিজে একবার সম্মতি দিতে হবে", r.error, a)
        }
        // the caregiver's own consent does not count for the patient
        grant("cg")
        assertConsentRequired(call("cg", "rx_claim", mapOf("code" to "ABCD2345EF", "phone" to "01711000001") + asP), caregiver = true)
        // once the patient consents, the caregiver can act for them again
        grant("pt")
        assertNotEquals(428, call("cg", "rx_claim", mapOf("code" to "ABCD2345EF", "phone" to "01711000001") + asP).status)
    }

    // ------------------------------------------------------------------ withdrawal

    @Test
    fun `withdrawing stops new entries, deletes nothing, and is recorded`() {
        on()
        gatedSetup()
        grant("pt")
        ok("pt", "profile_update", mapOf("set" to mapOf("bloodGroup" to "B+")))
        val w = ok("pt", "consent_withdraw", mapOf("scope" to "all", "source" to "web"))
        assertEquals(both, w["recorded"].map { it.textValue() })
        assertEquals(listOf("withdrawn", "withdrawn"), both.map { w["scopes"][it]["state"].textValue() })
        assertEquals(listOf(true, false), listOf(w["ask"].booleanValue(), w["current"].booleanValue()))
        assertEquals(listOf("granted", "granted", "withdrawn", "withdrawn"), rows("pt").map { it["action"] })
        // existing data stays
        assertEquals("B+", row("SELECT blood_group FROM user_profiles WHERE uid = 'pt'")["blood_group"])
        // new entries need consent again
        assertConsentRequired(call("pt", "profile_update", mapOf("set" to mapOf("bloodGroup" to "AB+"))))
        // withdrawing again: nothing new
        assertTrue(ok("pt", "consent_withdraw", mapOf("scope" to "all", "source" to "web"))["unchanged"].booleanValue())
        assertEquals(4, rows("pt").size)
        assertEquals("bad_scope", call("pt", "consent_withdraw", mapOf("scope" to "x", "source" to "web")).code)
        // one scope
        grant("pt")
        ok("pt", "consent_withdraw", mapOf("scope" to "data_abroad", "source" to "app"))
        assertConsentRequired(call("pt", "profile_update", mapOf("set" to mapOf("bloodGroup" to "AB+"))), listOf(Consents.DATA_ABROAD))
        // possible while the account is paused
        grant("pt")
        jdbc.update("UPDATE accounts SET deactivated_at = now() WHERE uid = 'pt'")
        accounts.forgetAll()
        assertEquals(both, ok("pt", "consent_withdraw", mapOf("scope" to "all", "source" to "app"))["recorded"].map { it.textValue() })
    }

    // ------------------------------------------------------------------ text version

    @Test
    fun `a new text version makes earlier consents outdated - people are asked again`() {
        on()
        gatedSetup()
        val old = consentText.version
        grant("pt")
        assertNotEquals(428, call("pt", "profile_update", mapOf("set" to mapOf("bloodGroup" to "A+"))).status)
        // the lawyer's new text is deployed
        consentText.versionOverride = "2099-01-01.1"
        val s = ok("pt", "consent_status")
        assertEquals(listOf("outdated", "outdated"), both.map { s["scopes"][it]["state"].textValue() })
        assertEquals(listOf(old, old), both.map { s["scopes"][it]["version"].textValue() })
        assertEquals(listOf(true, "2099-01-01.1"), listOf(s["ask"].booleanValue(), s["version"].textValue()))
        val r = call("pt", "profile_update", mapOf("set" to mapOf("bloodGroup" to "B+")))
        assertConsentRequired(r)
        assertEquals("2099-01-01.1", r.body["version"].textValue())
        // a screen still showing the old text cannot record consent
        assertEquals("consent_outdated", call("pt", "consent_grant", mapOf("scopes" to both, "version" to old, "source" to "app")).code)
        grant("pt")
        assertNotEquals(428, call("pt", "profile_update", mapOf("set" to mapOf("bloodGroup" to "B+"))).status)
        assertEquals(listOf(old, old, "2099-01-01.1", "2099-01-01.1"), rows("pt").map { it["version"] })
        // an outdated consent can still be withdrawn
        consentText.versionOverride = "2099-02-01.1"
        assertEquals(both, ok("pt", "consent_withdraw", mapOf("scope" to "all", "source" to "app"))["recorded"].map { it.textValue() })
    }

    // ------------------------------------------------------------------ pilot

    @Test
    fun `pilot mode - only pilot users (and demo accounts when chosen) are asked`() {
        on(Features.PILOT, listOf("pt"))
        gatedSetup()
        person("other", extra = mapOf("blood_group" to "O+"))
        assertConsentRequired(call("pt", "profile_update", mapOf("set" to mapOf("bloodGroup" to "A+"))))
        assertEquals(200, call("other", "profile_update", mapOf("set" to mapOf("bloodGroup" to "A+"))).status)
        assertTrue(ok("pt", "consent_status")["ask"].booleanValue())
        assertFalse(ok("other", "consent_status")["ask"].booleanValue())
        assertEquals("feature_off", call("other", "consent_grant", mapOf("scopes" to both, "version" to consentText.version, "source" to "app")).code)
        assertEquals(true, ok("pt", "features")["keys"][Features.HEALTH_CONSENT].booleanValue())
        assertEquals(false, ok("other", "features")["keys"][Features.HEALTH_CONSENT].booleanValue())
        // demo accounts
        on(Features.PILOT, emptyList(), demo = true)
        person("demo-x-pt", extra = mapOf("blood_group" to "O+"))
        assertConsentRequired(call("demo-x-pt", "profile_update", mapOf("set" to mapOf("bloodGroup" to "A+"))))
        assertEquals(200, call("pt", "profile_update", mapOf("set" to mapOf("bloodGroup" to "B+"))).status)
    }
}
