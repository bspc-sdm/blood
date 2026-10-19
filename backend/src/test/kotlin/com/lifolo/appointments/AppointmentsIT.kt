package com.lifolo.appointments

import com.lifolo.admin.kernel.VerifySpec
import com.lifolo.common.DhakaTime
import com.lifolo.common.Sql
import com.lifolo.contracts.HospitalDirectory
import com.lifolo.contracts.ProviderSuspension
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * appointments.js / apptcancel.js / reminders.js: provider_save (chambers, legacy, home care), book, confirm with
 * serial numbers, my_appointments, cancellations when the doctor's side changes (chambers, badge, suspension),
 * the daily reminders; e2efixes (Bangla digits in the confirmation push), demo-health/demo-rx booking parts.
 */
class AppointmentsIT : P2TestBase() {
    @Autowired lateinit var hospitals: HospitalDirectory

    @Autowired lateinit var suspension: ProviderSuspension

    @Autowired lateinit var reminders: AppointmentReminders

    private val day = 24 * 3600 * 1000L
    private val owner = "owner1"

    private fun ownerUser() {
        person(owner, "মালিক", "owner@example.com")
    }

    /** a confirmed doctor with one chamber open every day 09:00–23:00 */
    private fun doctor(uid: String = "d1", name: String = "ডা. রহিম", key: String = "dghs_1") {
        person(uid, name)
        badge(uid, "doctor", name)
        provider(
            uid,
            listOf(
                Ch(
                    hospitalId = key, hospitalName = "City Hospital", hospitalNameBn = "সিটি হাসপাতাল", district = district("Dhaka"),
                    days = listOf(0, 1, 2, 3, 4, 5, 6), start = "09:00", end = "23:00", noFee = false, fee = "500", appointments = true,
                ),
            ),
            name = name,
        )
    }

    private fun status(id: String) = row("SELECT status FROM appointments WHERE id = ?", id)["status"]

    @Test
    fun `book - checks, family member, caregiver, duplicates, confirm with serial, cancel, my_appointments`() {
        clock.fixedMs = dhaka("2026-10-19 10:00")
        val today = today()
        doctor()
        person("p1", "করিম", extra = mapOf("phone" to "01711000001"))
        jdbc.update("UPDATE user_profiles SET phone = '01700000009' WHERE uid = 'd1'")
        val book = { body: Map<String, Any?> -> call("p1", "book", mapOf("providerUid" to "d1", "date" to today + day, "chamberIndex" to 0, "note" to "জ্বর") + body) }
        assertEquals(400, book(mapOf("providerUid" to "bad id")).status)
        assertEquals("নিজের কাছে অ্যাপয়েন্টমেন্ট নেওয়া যায় না", call("d1", "book", mapOf("providerUid" to "d1", "date" to today, "note" to "x")).error)
        assertEquals("আগামী ৬০ দিনের মধ্যে তারিখ দিন", book(mapOf("date" to today - day)).error)
        assertEquals(400, book(mapOf("date" to today + 61 * day)).status)
        assertEquals(400, book(mapOf("date" to today + 5)).status, "the start of a day")
        assertEquals("কী সমস্যা, সংক্ষেপে লিখুন", book(mapOf("note" to "  ")).error)
        assertEquals(404, book(mapOf("chamberIndex" to 3)).status)
        val moved = book(mapOf("chamberKey" to "dghs_other"))
        assertEquals(409, moved.status)
        assertEquals("ডাক্তার এইমাত্র চেম্বারের তথ্য বদলেছেন। পাতাটা আবার খুলুন।", moved.error)
        // demo and real worlds never meet
        person("demo-pt")
        assertEquals(404, call("demo-pt", "book", mapOf("providerUid" to "d1", "date" to today + day, "note" to "x")).status)

        val id = book(emptyMap())
        assertEquals(200, id.status, id.toString())
        val apptId = id.body["id"].textValue()
        assertEquals(1, pushesTo("d1", "নতুন অ্যাপয়েন্টমেন্টের অনুরোধ").size)
        assertEquals("করিম · ২০/১০/২০২৬", pushesTo("d1").last().second, "Bangla digits for a Bangla reader")
        val dup = book(emptyMap())
        assertEquals(409, dup.status)
        assertEquals("সেদিন এই ডাক্তারের কাছে আপনার অনুরোধ আগে থেকেই আছে", dup.error)

        // a family member (no own account): another request on the same day is fine, in the member's name
        val fam = ok("p1", "family_set", mapOf("family" to listOf(mapOf("name" to "আব্দুল করিম", "relation" to "বাবা", "age" to 70, "gender" to "male"))))
        val memberId = fam["family"][0]["id"].textValue()
        assertEquals(404, book(mapOf("memberId" to "m_nobody1")).status)
        val forDad = ok("p1", "book", mapOf("providerUid" to "d1", "date" to today + day, "chamberIndex" to 0, "note" to "কাশি", "memberId" to memberId))["id"].textValue()
        val dad = row("SELECT patient_name, member_id, patient_age, patient_gender FROM appointments WHERE id = ?", forDad)
        assertEquals("আব্দুল করিম", dad["patient_name"])
        assertEquals(memberId, dad["member_id"])
        assertEquals(70, dad["patient_age"])
        assertEquals("male", dad["patient_gender"])

        // a caregiver books for the patient
        person("cg1", "রুমা")
        jdbc.update("INSERT INTO care_links (patient_uid, caretaker_uid, status) VALUES ('p1', 'cg1', 'active')")
        val byCg = ok("cg1", "book", mapOf("asUid" to "p1", "providerUid" to "d1", "date" to today + 2 * day, "chamberIndex" to 0, "note" to "রুমা নিলেন"))["id"].textValue()
        val cgRow = row("SELECT patient_uid, patient_name, by_caretaker FROM appointments WHERE id = ?", byCg)
        assertEquals(listOf("p1", "করিম", "cg1"), listOf(cgRow["patient_uid"], cgRow["patient_name"], cgRow["by_caretaker"]))

        // only the provider confirms; serials per chamber per day; the doctor's own register number
        assertEquals(400, call("p1", "appointment_update", mapOf("id" to apptId, "op" to "confirm")).status)
        ok("d1", "appointment_update", mapOf("id" to apptId, "op" to "confirm", "providerNote" to " সিরিয়াল এলে ফোন করা হবে "))
        ok("d1", "appointment_update", mapOf("id" to forDad, "op" to "confirm", "serial" to 7))
        val confirmed = row("SELECT serial, provider_note, status FROM appointments WHERE id = ?", apptId)
        assertEquals(1, confirmed["serial"])
        assertEquals("সিরিয়াল এলে ফোন করা হবে", confirmed["provider_note"])
        assertEquals(7, row("SELECT serial FROM appointments WHERE id = ?", forDad)["serial"])
        assertEquals(7, row("SELECT n FROM serial_counters WHERE provider_uid = 'd1'")["n"])
        assertTrue(pushesTo("p1", "অ্যাপয়েন্টমেন্ট নিশ্চিত হয়েছে").last().second.endsWith("#৭"))
        assertEquals("এখন এটা বদলানো যায় না", call("d1", "appointment_update", mapOf("id" to apptId, "op" to "confirm")).error)

        // both see each other's phone once confirmed; the doctor sees a short patient key
        val mine = ok("p1", "my_appointments")["items"]
        val asPatient = mine.first { it["id"].textValue() == apptId }
        assertEquals(listOf("patient", "ডা. রহিম", "01700000009", "confirmed", "1"), listOf(asPatient["role"].textValue(), asPatient["otherName"].textValue(), asPatient["otherPhone"].textValue(), asPatient["status"].textValue(), asPatient["serial"].asText()))
        assertEquals("", asPatient["otherKey"].textValue())
        assertEquals(today + day, asPatient["date"].longValue())
        val asDoctor = ok("d1", "my_appointments")["items"].first { it["id"].textValue() == apptId }
        assertEquals("01711000001", asDoctor["otherPhone"].textValue())
        assertEquals(12, asDoctor["otherKey"].textValue().length)
        assertEquals("", ok("d1", "my_appointments")["items"].first { it["id"].textValue() == byCg }["otherPhone"].textValue(), "pending: no phone")
        assertTrue(ok("cg1", "my_appointments", mapOf("asUid" to "p1"))["items"].any { it["id"].textValue() == byCg })

        // the patient cancels; the doctor is told
        ok("p1", "appointment_update", mapOf("id" to byCg, "op" to "cancel"))
        assertEquals("cancelled", status(byCg))
        assertEquals(1, pushesTo("d1", "অ্যাপয়েন্টমেন্ট বাতিল হয়েছে").size)
        assertEquals(404, call("p1", "appointment_update", mapOf("id" to "nope", "op" to "cancel")).status)
    }

    @Test
    fun `confirmation push - Bangla digits for Bangla readers, as before in English (e2efixes)`() {
        clock.fixedMs = dhaka("2026-10-19 10:00")
        val today = today()
        doctor()
        person("p1", "Karim")
        val b = ok("p1", "book", mapOf("providerUid" to "d1", "chamberIndex" to 0, "date" to today + day, "note" to "পরীক্ষা", "chamberKey" to "dghs_1"))
        ok("d1", "appointment_update", mapOf("id" to b["id"].textValue(), "op" to "confirm"))
        val p = pushesTo("p1").last()
        assertFalse(Regex("\\d").containsMatchIn(p.second), p.second)
        assertTrue(Regex("#[০-৯]+").containsMatchIn(p.second))
        // an English reader: as before
        jdbc.update("UPDATE user_profiles SET lang = 'en' WHERE uid = 'p1'")
        val b2 = ok("p1", "book", mapOf("providerUid" to "d1", "chamberIndex" to 0, "date" to today + 7 * day, "note" to "পরীক্ষা", "chamberKey" to "dghs_1"))
        ok("d1", "appointment_update", mapOf("id" to b2["id"].textValue(), "op" to "confirm"))
        val p2 = pushesTo("p1").last()
        assertEquals("Appointment confirmed", p2.first)
        assertTrue(Regex("\\d+/\\d+/\\d{4}").containsMatchIn(p2.second), p2.second)
    }

    @Test
    fun `book - closed day, today's time over, a night chamber still takes today (demo-night)`() {
        person("p1")
        person("d2", "ডা. রাত")
        badge("d2", "doctor", "ডা. রাত")
        provider(
            "d2",
            listOf(
                Ch(hospitalId = "dghs_2", hospitalName = "Day Clinic", district = district("Dhaka"), days = listOf(1), start = "09:00", end = "12:00", noFee = false, appointments = true),
                Ch(hospitalId = "dghs_3", hospitalName = "Night Clinic", district = district("Dhaka"), days = listOf(1), start = "20:00", end = "01:00", noFee = false, appointments = true),
            ),
        )
        clock.fixedMs = dhaka("2026-10-19 23:59") // Monday
        val today = today()
        val closed = call("p1", "book", mapOf("providerUid" to "d2", "date" to today + day, "chamberIndex" to 0, "note" to "x"))
        assertEquals("সেদিন চেম্বার বন্ধ। চেম্বারের বারগুলোর একটা বেছে নিন।", closed.error)
        val over = call("p1", "book", mapOf("providerUid" to "d2", "date" to today, "chamberIndex" to 0, "note" to "x"))
        assertEquals("আজকের চেম্বারের সময় শেষ। অন্য দিন বেছে নিন।", over.error)
        val night = ok("p1", "book", mapOf("providerUid" to "d2", "date" to today, "chamberIndex" to 1, "note" to "রাতের পরীক্ষা"))
        assertEquals("20:00", row("SELECT start_time FROM appointments WHERE id = ?", night["id"].textValue())["start_time"])
        // no chamber index: the first chamber taking appointments
        jdbc.update("UPDATE provider_chambers SET appointments = FALSE WHERE provider_uid = 'd2' AND idx = 0")
        clock.fixedMs = dhaka("2026-10-19 10:00")
        assertEquals("এখন অ্যাপয়েন্টমেন্ট নিচ্ছেন না", call("p1", "book", mapOf("providerUid" to "d2", "date" to today, "chamberIndex" to 0, "note" to "x")).error)
        val first = ok("p1", "book", mapOf("providerUid" to "d2", "date" to today + 7 * day, "note" to "x"))
        assertEquals("Night Clinic", row("SELECT chamber FROM appointments WHERE id = ?", first["id"].textValue())["chamber"])
        // a badge of another profession: no booking
        badge("d2", "nurse", "ডা. রাত")
        assertEquals(404, call("p1", "book", mapOf("providerUid" to "d2", "date" to today + 14 * day, "note" to "x")).status)
    }

    @Test
    fun `provider_save - chambers from the hospital list, minutes per patient kept, changed days cancel future appointments`() {
        clock.fixedMs = dhaka("2026-10-19 10:00")
        val today = today()
        val gzp = district("Gazipur")
        val h = hospitals.approvedIn(gzp).first()
        person("d1", "প্রোফাইলের নাম")
        badge("d1", "doctor", "ডা. যাচাই")
        person("p1", "করিম")
        assertEquals(403, call("p1", "provider_save", mapOf("chambers" to emptyList<Any>())).status)
        val chamber = mapOf("hospitalId" to h.id, "days" to listOf(0, 1, 2, 3, 4, 5, 6), "start" to "09:00", "end" to "23:00", "appointments" to true, "fee" to " 500 ", "room" to "12", "patientMin" to 12)
        assertEquals("অন্তত একটা বিশেষজ্ঞতা বেছে নিন", call("d1", "provider_save", mapOf("chambers" to listOf(chamber))).error)
        val old = call("d1", "provider_save", mapOf("chambers" to listOf(mapOf("hospitalId" to h.id)), "specialties" to listOf("medicine")))
        assertEquals(409, old.status)
        assertEquals("update_required", old.code)
        val saved = ok("d1", "provider_save", mapOf("chambers" to listOf(chamber), "specialties" to listOf("medicine", "bogus"), "appointments" to true, "gender" to "male", "degrees" to "MBBS, FCPS"))
        assertEquals(0, saved["cancelled"].intValue())
        var me = ok("d1", "provider_me")["provider"]
        assertEquals("ডা. যাচাই", me["name"].textValue(), "the badge name")
        assertEquals(listOf("medicine"), me["specialties"].map { it.textValue() })
        assertEquals(true, me["active"].booleanValue())
        val c0 = me["chambers"][0]
        assertEquals(listOf(h.id, h.nameEn, gzp, "500", "12", "12"), listOf(c0["hospitalId"].textValue(), c0["hospitalName"].textValue(), c0["district"].textValue(), c0["fee"].textValue(), c0["room"].textValue(), c0["patientMin"].asText()))
        assertTrue(me["homeVisit"].isNull)

        // a patient books tomorrow; an old app saves without patientMin (kept), then the days change (cancelled + told)
        val tomorrow = today + day
        val id = ok("p1", "book", mapOf("providerUid" to "d1", "date" to tomorrow, "chamberIndex" to 0, "note" to "জ্বর"))["id"].textValue()
        ok("d1", "provider_save", mapOf("chambers" to listOf(chamber - "patientMin"), "specialties" to listOf("medicine"), "appointments" to true))
        me = ok("d1", "provider_me")["provider"]
        assertEquals(12, me["chambers"][0]["patientMin"].intValue(), "the earlier minutes per patient")
        assertEquals("pending", status(id))
        val weekday = CareRules.weekday(tomorrow)
        val r = ok("d1", "provider_save", mapOf("chambers" to listOf(chamber + mapOf("days" to (0..6).filter { it != weekday })), "specialties" to listOf("medicine"), "appointments" to true))
        assertEquals(1, r["cancelled"].intValue())
        val a = row("SELECT status, cancelled_by, provider_note FROM appointments WHERE id = ?", id)
        assertEquals(listOf("cancelled", "system", "ডাক্তার চেম্বার বা বার বদলেছেন"), listOf(a["status"], a["cancelled_by"], a["provider_note"]))
        assertEquals("অ্যাপয়েন্টমেন্ট বাতিল হয়েছে", pushesTo("p1").last().first)
        assertTrue(pushesTo("p1").last().second.endsWith("ডাক্তার চেম্বার বা বার বদলেছেন"))

        // overlapping chambers are refused
        val clash = call("d1", "provider_save", mapOf("chambers" to listOf(chamber, chamber), "specialties" to listOf("medicine")))
        assertEquals(400, clash.status)
        // turned off by the Lifolo team: no switching on again
        jdbc.update("UPDATE providers SET admin_suspended = TRUE WHERE uid = 'd1'")
        assertEquals(403, call("d1", "provider_save", mapOf("chambers" to listOf(chamber), "specialties" to listOf("medicine"))).status)
    }

    @Test
    fun `provider_save - legacy single chamber, then chambers, then no way back`() {
        person("d1", "ডা. পুরোনো")
        badge("d1", "doctor", "ডা. পুরোনো")
        val legacy = mapOf("chamber" to "My chamber", "schedule" to "Sun 5-9", "district" to district("Dhaka"), "specialty" to " Medicine ", "fee" to "500 taka")
        assertEquals("জেলা বেছে নিন", call("d1", "provider_save", legacy + mapOf("district" to "x")).error)
        ok("d1", "provider_save", legacy)
        val p = ok("d1", "provider_me")["provider"]
        assertEquals("Medicine", p["specialty"].textValue())
        assertEquals("My chamber", p["chamber"].textValue())
        assertEquals("Sun 5-9", p["schedule"].textValue())
        assertEquals("500 taka", p["fee"].textValue())
        assertEquals(true, p["active"].booleanValue())
        val c = p["chambers"][0]
        assertTrue(c["hospitalId"].isNull)
        assertEquals(district("Dhaka"), c["district"].textValue())
        assertFalse(c.has("days"), "the legacy shape has no days")
        assertEquals(true, c["appointments"].booleanValue())
        // the new app saves chambers; the old app can no longer edit them
        val h = hospitals.approvedIn(district("Dhaka")).first()
        ok("d1", "provider_save", mapOf("chambers" to listOf(mapOf("hospitalId" to h.id, "days" to listOf(1), "start" to "17:00", "end" to "21:00")), "specialties" to listOf("medicine")))
        assertEquals("Medicine", ok("d1", "provider_me")["provider"]["specialty"].textValue(), "merge keeps the old field")
        val back = call("d1", "provider_save", legacy)
        assertEquals(409, back.status)
        assertEquals("চেম্বার বদলাতে অ্যাপ আপডেট করুন", back.error)
        // nurses have no chambers in old apps either
        person("n1")
        badge("n1", "nurse")
        assertEquals("update_required", call("n1", "provider_save", legacy).code)
    }

    @Test
    fun `provider_save - a nurse turns on home care (phone, terms, area), blocked after a report`() {
        val gzp = district("Gazipur")
        person("n1", "নার্স নাসরিন")
        badge("n1", "nurse", "নার্স নাসরিন")
        val hv = mapOf("enabled" to true, "services" to listOf("injection", "dressing"), "phone" to "01712-345678", "termsAccepted" to true, "radiusKm" to 5, "upazilaIds" to listOf(320), "fee" to "৩০০")
        fun save(home: Map<String, Any?>?) = call("n1", "provider_save", mapOf("chambers" to emptyList<Any>(), "specialties" to listOf("icu")) + (if (home != null) mapOf("homeVisit" to home) else emptyMap()))
        assertEquals("update_required", save(hv - "phone").code)
        assertEquals("বাসায় সেবার শর্তে সম্মতি দিন", save(hv - "termsAccepted").error)
        assertEquals("বাসায় সেবার জন্য বর্তমান লোকেশন দিন অথবা উপজেলা বেছে নিন", save(hv + mapOf("upazilaIds" to emptyList<Int>())).error)
        assertEquals("বাসায় সেবা চালু করুন", save(hv + mapOf("enabled" to false)).error)
        assertEquals(200, save(hv).status)
        val p = ok("n1", "provider_me")["provider"]
        assertEquals("01712345678", p["homeVisit"]["phone"].textValue())
        assertEquals(listOf("injection", "dressing"), p["homeVisit"]["services"].map { it.textValue() })
        assertEquals(false, p["listed"].booleanValue(), "nurses are not in the chamber list")
        assertEquals(0, p["chambers"].size())
        // listed for patients searching the area
        person("pt")
        assertTrue(ok("pt", "home_providers", mapOf("type" to "nurse", "district" to gzp, "upazilaId" to 320))["items"].any { it["uid"].textValue() == "n1" })
        // a nutritionist has no home care
        person("nu")
        badge("nu", "nutritionist")
        assertEquals("বাসায় সেবা শুধু ডাক্তার আর নার্সের", call("nu", "provider_save", mapOf("chambers" to emptyList<Any>(), "specialties" to listOf("weight_diet"), "homeVisit" to hv)).error)
        // blocked after a report: cannot switch on again
        suspension.blockHomeCare("n1")
        assertEquals("রিপোর্টের পর আপনার বাসায় সেবা বন্ধ আছে। প্রোফাইলের \"সাহায্য ও আপিল\" থেকে আপিল করতে পারেন।", save(hv).error)
        assertEquals("", ok("n1", "provider_me")["provider"]["homeVisit"]["phone"].textValue(), "the phone is not shown while off")
    }

    @Test
    fun `badge rejected - out of the lists, future appointments cancelled; approved again - back (admin_badge_resolve)`() {
        clock.fixedMs = dhaka("2026-10-19 10:00")
        ownerUser()
        doctor()
        person("p1")
        val id = ok("p1", "book", mapOf("providerUid" to "d1", "date" to today() + day, "chamberIndex" to 0, "note" to "জ্বর"))["id"].textValue()
        assertEquals(403, call("p1", "admin_badge_resolve", mapOf("uid" to "d1", "approve" to false)).status)
        assertEquals("আবেদনটা পাওয়া যায়নি", call(owner, "admin_badge_resolve", mapOf("uid" to "nobody")).error)
        assertEquals("নিজের ব্যাজ অন্য অ্যাডমিন দেখবেন", call(owner, "admin_badge_resolve", mapOf("uid" to owner)).error)
        ok(owner, "admin_badge_resolve", mapOf("uid" to "d1", "approve" to false, "reason" to "BMDC-তে নম্বর পাওয়া যায়নি"))
        val p = row("SELECT listed, active, badge_rejected, listed_before FROM providers WHERE uid = 'd1'")
        assertEquals(listOf(false, false, true, true), listOf(p["listed"], p["active"], p["badge_rejected"], p["listed_before"]))
        assertEquals("cancelled", status(id))
        assertEquals("ডাক্তার/নার্স এখন তালিকায় নেই", row("SELECT provider_note FROM appointments WHERE id = ?", id)["provider_note"])
        val me = ok("d1", "me")["badge"]
        assertEquals("rejected", me["status"].textValue())
        assertEquals("BMDC-তে নম্বর পাওয়া যায়নি", me["statusReason"].textValue())
        assertTrue(me.has("docs") && me.has("docsRequestNote"), me.toString())
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM admin_log WHERE action = 'admin_badge_resolve'", Int::class.java))
        assertFalse(ok("p1", "providers", mapOf("type" to "doctor", "homeVisit" to false))["items"].any { it["uid"].textValue() == "d1" })
        // approval needs every tick in the panel
        assertEquals("checks_required", call(owner, "admin_badge_resolve", mapOf("uid" to "d1", "approve" to true)).code)
        val checks = VerifySpec.checks("badge", mapOf("type" to "doctor")).map { it.id }
        ok(owner, "admin_badge_resolve", mapOf("uid" to "d1", "approve" to true, "checks" to checks))
        val back = row("SELECT listed, active, badge_rejected, listed_before FROM providers WHERE uid = 'd1'")
        assertEquals(listOf(true, true, false, null), listOf(back["listed"], back["active"], back["badge_rejected"], back["listed_before"]))
        val b = row("SELECT status, status_reason, verified_by, verification_checks FROM badges WHERE uid = 'd1'")
        assertEquals("approved", b["status"])
        assertNull(b["status_reason"])
        assertEquals(owner, b["verified_by"])
        assertTrue(ok("p1", "providers", mapOf("type" to "doctor", "homeVisit" to false))["items"].any { it["uid"].textValue() == "d1" })
    }

    @Test
    fun `provider suspension - suspend cancels and tells, a report upgrades the reason, lift lists again`() {
        clock.fixedMs = dhaka("2026-10-19 10:00")
        doctor()
        person("p1")
        val id = ok("p1", "book", mapOf("providerUid" to "d1", "date" to today() + day, "chamberIndex" to 0, "note" to "জ্বর"))["id"].textValue()
        jdbc.update("UPDATE providers SET home_visit_enabled = TRUE, home_radius_km = 5 WHERE uid = 'd1'")
        assertTrue(suspension.suspendProvider("d1", "account"))
        val p = row("SELECT admin_suspended, suspend_reason, listed_before_suspend, listed, home_visit_enabled FROM providers WHERE uid = 'd1'")
        assertEquals(listOf(true, "account", true, false, false), listOf(p["admin_suspended"], p["suspend_reason"], p["listed_before_suspend"], p["listed"], p["home_visit_enabled"]))
        assertEquals("cancelled", status(id))
        assertTrue(pushesTo("p1", "অ্যাপয়েন্টমেন্ট বাতিল হয়েছে").last().second.endsWith("ডাক্তার/নার্স এখন পাওয়া যাচ্ছে না"))
        assertFalse(suspension.suspendProvider("d1", "report"))
        assertEquals("report", row("SELECT suspend_reason FROM providers WHERE uid = 'd1'")["suspend_reason"])
        assertFalse(suspension.suspendProvider("nobody", "admin"))
        assertTrue(suspension.liftProvider("d1"))
        assertEquals(true, row("SELECT listed FROM providers WHERE uid = 'd1'")["listed"])
        // the account deletion step suspends too
        assertEquals(0, suspension.cancelStale("d1", "x", "y"))
    }

    @Test
    fun `daily reminders - unanswered requests expire, old confirmed ones complete, today's are announced (cron step 3)`() {
        val now = dhaka("2026-10-19 09:00")
        val today = today(now)
        doctor()
        person("p1", "করিম")
        person("p2", "রহিম")
        fun appt(id: String, patient: String, date: Long, status: String, createdAt: Long, serial: Int? = null) {
            jdbc.update(
                """
                INSERT INTO appointments (id, patient_uid, provider_uid, patient_name, provider_name, type, chamber, chamber_bn, chamber_key, visit_date, status, serial, created_at)
                VALUES (?, ?, 'd1', ?, 'ডা. রহিম', 'doctor', 'City Hospital', 'সিটি হাসপাতাল', 'dghs_1', ?, ?, ?, ?)
                """.trimIndent(),
                id, patient, patient, Sql.date(DhakaTime.fromUtcMidnightMs(date)), status, serial, Sql.ts(createdAt),
            )
        }
        appt("past", "p1", today - day, "pending", now - 3600_000)
        appt("old", "p2", today + 3 * day, "pending", now - 49 * 3600_000L)
        appt("waiting", "p1", today + 2 * day, "pending", now - 13 * 3600_000L)
        appt("fresh", "p2", today + 2 * day, "pending", now - 3600_000)
        appt("done", "p1", today - 4 * day, "confirmed", now - 6 * day, 1)
        appt("recent", "p2", today - 3 * day, "confirmed", now - 5 * day, 1)
        appt("todayA", "p1", today, "confirmed", now - day, 3)
        appt("todayB", "p2", today, "confirmed", now - day, 4)
        val r = reminders.dailyReminders(now)
        assertEquals(mapOf("appointmentsToday" to 2, "waiting" to 1), r.filterKeys { it != "sent" })
        assertEquals(listOf("expired", "expired", "pending", "pending", "completed", "confirmed"), listOf("past", "old", "waiting", "fresh", "done", "recent").map { status(it) })
        assertEquals(1, pushesTo("p1", "অ্যাপয়েন্টমেন্টের অনুরোধে সাড়া আসেনি").size)
        assertEquals("ডা. রহিম · সিটি হাসপাতাল · #৩", pushesTo("p1", "আজ অ্যাপয়েন্টমেন্ট আছে").single().second)
        assertEquals("২টি নিশ্চিত", pushesTo("d1", "আজকের অ্যাপয়েন্টমেন্ট").single().second)
        assertEquals("১ জন রোগী উত্তরের অপেক্ষায়", pushesTo("d1", "অ্যাপয়েন্টমেন্টের অনুরোধ অপেক্ষায়").single().second)
        // the cron endpoint runs it as step "care"
        val cron = get("/api/cron/daily", mapOf("Authorization" to "Bearer test-cron-secret"))
        assertEquals(200, cron.status)
        assertTrue(cron.body["care"].has("appointmentsToday"), cron.toString())
    }
}
