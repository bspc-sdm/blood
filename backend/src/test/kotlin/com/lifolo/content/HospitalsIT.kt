package com.lifolo.content

import com.lifolo.appointments.P2TestBase
import com.lifolo.common.Places
import com.lifolo.contracts.HospitalDirectory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * hospitals.js (+ emergency.js admin_hospitals): the district list (DGHS directory + approved user hospitals), requests
 * (duplicate of an existing one, merged with a similar pending one), the admin review with pushes to the requesters
 * (demo-blood hospital parts, rules "hospital directory").
 */
class HospitalsIT : P2TestBase() {
    @Autowired lateinit var directory: HospitalDirectory

    private val owner = "owner1"

    @Test
    fun `the directory - DGHS entries of a district, by id, sorted, upazila filter`() {
        val dhaka = directory.approvedIn(district("Dhaka"))
        assertTrue(dhaka.size > 1000)
        assertEquals(dhaka[0].nameEn, directory.findHospital(dhaka[0].id)!!.nameEn)
        assertNull(directory.findHospital("dghs_0"))
        assertTrue(Places.UPAZILA.values.any { it.district == district("Dhaka") && it.en == "Dhanmondi" })
        val gzp = district("Gazipur")
        val items = ok(null, "hospitals", mapOf("district" to gzp))["items"]
        val names = items.map { it["nameEn"].textValue() }
        assertEquals(names.sortedWith(java.text.Collator.getInstance(java.util.Locale.US)), names)
        assertEquals("জেলা বেছে নিন", call(null, "hospitals", mapOf("district" to "x")).error)
        val withUpazila = ok(null, "hospitals", mapOf("district" to gzp, "upazilaId" to 320))["items"]
        assertTrue(withUpazila.size() in 1 until items.size())
        assertTrue(withUpazila.all { it["upazilaId"].isNull || it["upazilaId"].intValue() == 320 })
    }

    @Test
    fun `requests - duplicate, merge with a pending one, approve, merge, reject, removed hospitals`() {
        val gzp = district("Gazipur")
        person(owner, "মালিক", "owner@example.com")
        person("u1")
        person("u2")
        person("u3")
        val existing = directory.approvedIn(gzp).first()
        val dup = ok("u1", "hospital_request", mapOf("nameEn" to existing.nameEn, "type" to "clinic", "district" to gzp))
        assertEquals(false, dup["ok"].booleanValue())
        assertEquals(existing.id, dup["duplicateOf"]["id"].textValue())
        assertEquals("হাসপাতালের নাম ইংরেজিতে লিখুন", call("u1", "hospital_request", mapOf("nameEn" to "১২৩", "type" to "clinic", "district" to gzp)).error)
        assertEquals("ধরন বেছে নিন", call("u1", "hospital_request", mapOf("nameEn" to "New Life Clinic", "type" to "x", "district" to gzp)).error)
        assertEquals("সঠিক উপজেলা বেছে নিন", call("u1", "hospital_request", mapOf("nameEn" to "New Life Clinic", "type" to "clinic", "district" to gzp, "upazilaId" to 1)).error)
        assertEquals("সঠিক ইউনিয়ন বেছে নিন", call("u1", "hospital_request", mapOf("nameEn" to "New Life Clinic", "type" to "clinic", "district" to gzp, "unionId" to 5)).error)

        val body = mapOf("nameEn" to "Zeta Unique Care Clinic", "nameBn" to "জেটা ক্লিনিক", "type" to "clinic", "district" to gzp, "upazilaId" to 320, "address" to "Main road", "phone" to "01711-000000")
        assertEquals(mapOf("ok" to true), mapper.convertValue(ok("u1", "hospital_request", body), Map::class.java))
        // a similar pending request: merged (count goes up once per person)
        assertEquals(true, ok("u2", "hospital_request", body + mapOf("nameEn" to "Zeta Unique Care Clinic Ltd"))["merged"].booleanValue())
        ok("u2", "hospital_request", body)
        val req = row("SELECT id, count, phone, norm_key FROM hospital_requests")
        assertEquals(2, req["count"])
        assertEquals("01711000000", req["phone"])
        assertEquals("zeta unique care", req["norm_key"])
        val id = req["id"] as String

        assertEquals(403, call("u1", "admin_hospital_requests").status)
        val pending = ok(owner, "admin_hospital_requests")["items"]
        assertEquals(1, pending.size())
        assertEquals(2, pending[0]["count"].intValue())
        assertEquals(320, pending[0]["upazilaId"].intValue())
        assertTrue(pending[0].has("possibleDuplicates"))

        assertEquals("অজানা কাজ", call(owner, "admin_hospital_resolve", mapOf("id" to id, "op" to "x")).error)
        val approved = ok(owner, "admin_hospital_resolve", mapOf("id" to id, "op" to "approve"))
        val hid = approved["hospitalId"].textValue()
        assertEquals(404, call(owner, "admin_hospital_resolve", mapOf("id" to id, "op" to "approve")).status, "not pending any more")
        assertEquals(1, pushesTo("u1", "আপনার চাওয়া হাসপাতাল তালিকায় যোগ হয়েছে").size)
        assertEquals("জেটা ক্লিনিক", pushesTo("u2").last().second, "the Bangla name for Bangla readers")
        assertEquals("approve", row("SELECT detail FROM admin_log WHERE action = 'admin_hospital_resolve'")["detail"])
        val h = ok(null, "hospitals", mapOf("district" to gzp, "upazilaId" to 320))["items"].first { it["id"].textValue() == hid }
        assertEquals(listOf("Zeta Unique Care Clinic", "জেটা ক্লিনিক", "Main road"), listOf(h["nameEn"].textValue(), h["nameBn"].textValue(), h["address"].textValue()))
        assertEquals("জেটা ক্লিনিক", directory.findHospital(hid)!!.nameBn)
        // now a duplicate of the approved one
        assertEquals(hid, ok("u3", "hospital_request", body)["duplicateOf"]["id"].textValue())
        // the emergency form's list
        val admin = ok(owner, "admin_hospitals", mapOf("district" to gzp))["items"]
        assertEquals("জেটা ক্লিনিক", admin.first { it["id"].textValue() == hid }["name"].textValue())

        // merge into an existing one / reject
        ok("u3", "hospital_request", mapOf("nameEn" to "Omega Health Point", "type" to "diagnostic", "district" to gzp))
        val r2 = row("SELECT id FROM hospital_requests WHERE status = 'pending'")["id"] as String
        assertEquals("আগের হাসপাতালটা বেছে নিন", call(owner, "admin_hospital_resolve", mapOf("id" to r2, "op" to "merge", "targetId" to "nope")).error)
        ok(owner, "admin_hospital_resolve", mapOf("id" to r2, "op" to "merge", "targetId" to existing.id))
        assertEquals(listOf("merged", existing.id), row("SELECT status, hospital_id FROM hospital_requests WHERE id = ?", r2).values.toList())
        ok("u3", "hospital_request", mapOf("nameEn" to "Sigma Eye Point", "type" to "clinic", "district" to gzp))
        val r3 = row("SELECT id FROM hospital_requests WHERE status = 'pending'")["id"] as String
        ok(owner, "admin_hospital_resolve", mapOf("id" to r3, "op" to "reject"))
        assertEquals(1, pushesTo("u3", "হাসপাতাল যোগের অনুরোধ অনুমোদন হয়নি").size)

        // a hospital removed by hand is no longer listed or found (Node: status 'removed')
        jdbc.update("UPDATE hospitals SET status = 'removed' WHERE id = ?", hid)
        assertNull(directory.findHospital(hid))
        assertFalse(ok(owner, "admin_hospitals", mapOf("district" to gzp))["items"].any { it["id"].textValue() == hid })
    }
}
