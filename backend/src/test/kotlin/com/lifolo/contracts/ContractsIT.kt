package com.lifolo.contracts

import com.lifolo.common.ApiException
import com.lifolo.support.IntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant

/**
 * The baseline implementations of the cross-package contracts (owned by P2/P3, written by the P6-kernel step).
 * When an owner replaces a baseline, these tests must keep passing (or move to the owner's package with the same cases).
 */
class ContractsIT : IntegrationTest() {
    @Autowired lateinit var badges: Badges

    @Autowired lateinit var assistants: ProviderAssistants

    @Autowired lateinit var chambers: Chambers

    @Autowired lateinit var hospitals: HospitalDirectory

    @Autowired lateinit var suspension: ProviderSuspension

    @Autowired lateinit var prescriptions: Prescriptions

    @Autowired lateinit var home: PatientHome

    @Autowired lateinit var requestEnd: RequestEnd

    private fun provider(uid: String) {
        user(uid)
        jdbc.update("INSERT INTO providers (uid, type, name, appointments, listed, active) VALUES (?, 'doctor', 'ডা. রহিম', TRUE, TRUE, TRUE)", uid)
    }

    @Test
    fun `badges - only an approved badge counts`() {
        user("d1")
        assertNull(badges.approvedBadge("d1"))
        jdbc.update("INSERT INTO badges (uid, type, reg_no, workplace, status) VALUES ('d1', 'doctor', 'A-1', 'X', 'pending')")
        assertNull(badges.approvedBadge("d1"))
        jdbc.update("UPDATE badges SET status = 'approved' WHERE uid = 'd1'")
        assertEquals("doctor", badges.approvedBadge("d1"))
    }

    @Test
    fun `assistants - set keeps order, lower case, lookups both ways`() {
        provider("d2")
        provider("d3")
        assertFalse(assistants.set("nobody", listOf("a@x.com")))
        assertTrue(assistants.set("d2", listOf("Z@x.com", "a@x.com", "z@x.com")))
        assertEquals(listOf("z@x.com", "a@x.com"), assistants.emails("d2"))
        assertTrue(assistants.isAssistant("d2", "a@x.com"))
        assertFalse(assistants.isAssistant("d2", ""))
        assertFalse(assistants.isAssistant("d3", "a@x.com"))
        assistants.set("d3", listOf("a@x.com"))
        assertEquals(listOf("d2", "d3"), assistants.doctorsFor("a@x.com"))
        assistants.set("d2", emptyList())
        assertEquals(emptyList<String>(), assistants.emails("d2"))
    }

    @Test
    fun `chambers - index order, sessions, open days, appointments switch`() {
        provider("d4")
        jdbc.update(
            """
            INSERT INTO provider_chambers (provider_uid, idx, hospital_id, hospital_name, hospital_name_bn, district, room, days, start_time, end_time, appointments)
            VALUES ('d4', 0, 'dghs_1', 'City Hospital', 'সিটি হাসপাতাল', 'ঢাকা', '12', '{6,0}', '17:00', '21:00', TRUE),
                   ('d4', 1, NULL, 'My Clinic', '', 'ঢাকা', '', '{}', NULL, NULL, FALSE)
            """.trimIndent(),
        )
        jdbc.update("INSERT INTO provider_chamber_times (provider_uid, chamber_idx, idx, days, start_time, end_time) VALUES ('d4', 1, 0, '{5}', '09:00', '12:00')")
        val list = chambers.chambersOf("d4")
        assertEquals(listOf(0, 1), list.map { it.index })
        assertEquals("সিটি হাসপাতাল", list[0].hospitalNameBn)
        assertEquals(listOf(ChamberTime(listOf(6, 0), "17:00", "21:00")), list[0].times)
        assertEquals(listOf(ChamberTime(listOf(5), "09:00", "12:00")), list[1].times)
        val saturday = Instant.parse("2026-10-17T00:00:00Z").toEpochMilli()
        assertTrue(chambers.chamberOpenOn(list[0], saturday))
        assertFalse(chambers.chamberOpenOn(list[1], saturday))
        assertTrue(chambers.takesAppointments("d4"))
        assertFalse(chambers.takesAppointments("none"))
        assertEquals(emptyList<Chamber>(), chambers.chambersOf("none"))
    }

    @Test
    fun `hospital directory - DGHS entries and user-added ones`() {
        val dhaka = hospitals.approvedIn("ঢাকা")
        assertTrue(dhaka.isNotEmpty())
        val h = dhaka.first()
        assertTrue(h.id.startsWith("dghs_"))
        assertEquals(h, hospitals.findHospital(h.id))
        assertNull(hospitals.findHospital("dghs_0"))
        assertNull(hospitals.findHospital(null))
        assertNull(hospitals.findHospital("bad id!"))
        jdbc.update("INSERT INTO hospitals (id, name_en, name_bn, type, district, source) VALUES ('h1', 'New Clinic', 'নতুন ক্লিনিক', 'clinic', 'ঢাকা', 'user')")
        assertEquals("নতুন ক্লিনিক", hospitals.findHospital("h1")!!.nameBn)
        assertEquals("h1", hospitals.approvedIn("ঢাকা").last().id)
        jdbc.update("UPDATE hospitals SET status = 'hidden' WHERE id = 'h1'")
        assertNull(hospitals.findHospital("h1"))
    }

    @Test
    fun `provider suspension baseline - lift and home-care block work, the rest is P2`() {
        provider("d5")
        jdbc.update("UPDATE providers SET home_visit_enabled = TRUE WHERE uid = 'd5'")
        suspension.blockHomeCare("d5")
        val p = row("SELECT home_visit_enabled, home_visit_blocked FROM providers WHERE uid = 'd5'")
        assertEquals(false, p["home_visit_enabled"])
        assertEquals(true, p["home_visit_blocked"])
        assertFalse(suspension.liftProvider("d5"))
        jdbc.update("UPDATE providers SET admin_suspended = TRUE, suspend_reason = 'account', listed = FALSE, listed_before_suspend = TRUE WHERE uid = 'd5'")
        assertTrue(suspension.liftProvider("d5"))
        assertEquals(true, row("SELECT listed FROM providers WHERE uid = 'd5'")["listed"])
        // P2 implemented suspend/cancelStale (more cases in appointments/ProviderSuspensionIT)
        assertTrue(suspension.suspendProvider("d5", "admin"))
        assertEquals(false, row("SELECT listed FROM providers WHERE uid = 'd5'")["listed"])
        assertEquals(0, suspension.cancelStale("d5", "x", "y"))
        // RequestEnd is implemented by P1 (blood.RequestEndService, tested in blood.BloodFlowIT): no response → nothing happens
        requestEnd.donorLeft("r1", "d5")
    }

    @Test
    fun `prescriptions - by code (normalised, same world), view, verify, refill date`() {
        user("doc1")
        user("pat1")
        jdbc.update(
            """
            INSERT INTO prescriptions (id, code, doctor_uid, patient_uid, doctor_name, doctor_degrees, doctor_specialties, doctor_reg_no,
              doctor_chamber, patient_name, patient_age, patient_gender, diagnosis, tests, follow_up_date, chamber_index)
            VALUES ('rx1', 'ABCDEFGH23', 'doc1', 'pat1', 'ডা. রহিম', 'MBBS', '{medicine}', 'A-1', 'সিটি হাসপাতাল', 'করিম', 40, 'male',
              'জ্বর', '{CBC}', '2026-10-25', 0)
            """.trimIndent(),
        )
        jdbc.update("INSERT INTO prescription_medicines (rx_id, idx, name, dose, days) VALUES ('rx1', 0, 'Napa', '1+0+1', 5), ('rx1', 1, 'Seclo', '1+0+0', 0)")
        val dispensing = jdbc.queryForObject(
            "INSERT INTO prescription_dispensings (rx_id, pharmacy_uid, pharmacy_name) VALUES ('rx1', 'ph1', 'দোকান') RETURNING id", Long::class.java,
        )!!
        jdbc.update("INSERT INTO prescription_dispensed_items (dispensing_id, med_idx, qty) VALUES (?, 0, 10)", dispensing)

        val rx = prescriptions.byCode("bn", " abcd-efgh 23", "ph1")
        assertEquals("rx1", rx.id)
        assertEquals(listOf("Napa", "Seclo"), rx.medicines.map { it.name })
        assertEquals(listOf(0 to 10), rx.dispensed.single().items)
        assertEquals(Instant.parse("2026-10-25T00:00:00Z").toEpochMilli(), rx.followUpDate)
        val bad = assertThrows(ApiException::class.java) { prescriptions.byCode("bn", "ABC", "ph1") }
        assertEquals(400, bad.status)
        assertEquals("প্রেসক্রিপশনের কোডটা দেখে লিখুন", bad.message)
        val other = assertThrows(ApiException::class.java) { prescriptions.byCode("en", "ABCDEFGH23", "demo-pharmacy") }
        assertEquals(404, other.status)
        assertEquals("No prescription with this code", other.message)
        assertEquals("এই কোডের কোনো প্রেসক্রিপশন নেই", assertThrows(ApiException::class.java) { prescriptions.byCode("bn", "ZZZZZZZZZZ", null) }.message)

        val view = prescriptions.view(rx)
        assertEquals(true, view["linked"])
        assertEquals("জ্বর", view["diagnosis"])
        assertEquals(0, view["chamberIndex"])
        val v = prescriptions.verify("abcdefgh23")!!
        assertEquals(mapOf("name" to "ডা. রহিম", "degrees" to "MBBS", "regNo" to "A-1", "chamber" to "সিটি হাসপাতাল"), v["doctor"])
        assertEquals(setOf("status", "createdAt", "doctor"), v.keys)
        assertNull(prescriptions.verify("nope"))

        prescriptions.setRefillAt("rx1", Instant.parse("2026-10-28T00:00:00Z").toEpochMilli())
        assertEquals(Instant.parse("2026-10-28T00:00:00Z").toEpochMilli(), prescriptions.get("rx1")!!.refillAt)
        prescriptions.setRefillAt("rx1", null)
        assertNull(prescriptions.get("rx1")!!.refillAt)
        assertNull(prescriptions.get("missing"))
    }

    @Test
    fun `patient home - last lab home address`() {
        user("p9")
        assertNull(home.lastHome("p9"))
        jdbc.update("INSERT INTO user_state (uid, lab_home_address, lab_home_phone) VALUES ('p9', 'বাড়ি ৫', '01711000000')")
        assertEquals(mapOf("address" to "বাড়ি ৫", "phone" to "01711000000"), home.lastHome("p9"))
        assertNotNull(home)
    }
}
