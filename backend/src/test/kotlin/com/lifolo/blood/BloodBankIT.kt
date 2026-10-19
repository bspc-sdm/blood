package com.lifolo.blood

import com.lifolo.admin.kernel.VerifySpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** bloodbank.js: owner add/edit/delete, the public list, calls and statistics, the admin panel */
class BloodBankIT : BloodTestBase() {
    private val jpeg = "/9j/4AAQSkZJRgABAQ"

    private fun bank(extra: Map<String, Any?> = emptyMap()) = mapOf(
        "termsAccepted" to true, "name" to "জীবন ব্লাড ব্যাংক", "kind" to "private", "phone" to "01712345678", "district" to DIST,
        "address" to "চরপাড়া মোড়", "licenseNo" to "BB-1", "open24" to true, "services" to listOf("whole_blood", "bogus", "platelets", "whole_blood"),
        "docs" to mapOf("front" to jpeg, "license" to jpeg),
    ) + extra

    @Test
    fun `owner adds, edits, sees documents; admin approves; listed by district; calls and statistics`() {
        admin()
        user("own", mapOf("name" to "মালিক"))
        user("p1", emptyMap())
        assertEquals("শর্তে সম্মতি দিন", call("own", "bloodbank_save", bank(mapOf("termsAccepted" to false))).error)
        assertEquals("ব্লাড ব্যাংকের লাইসেন্স নম্বর দিন", call("own", "bloodbank_save", bank(mapOf("licenseNo" to " "))).error)
        assertEquals("খোলার সময় দিন অথবা ২৪ ঘণ্টা বেছে নিন", call("own", "bloodbank_save", bank(mapOf("open24" to false))).error)
        assertEquals("কী কী সেবা দেন বেছে নিন", call("own", "bloodbank_save", bank(mapOf("services" to listOf("bogus")))).error)
        assertEquals("সঠিক ফোন নম্বর দিন", call("own", "bloodbank_save", bank(mapOf("phone" to "999"))).error)
        assertEquals("docs_required", call("own", "bloodbank_save", bank(mapOf("docs" to mapOf("front" to jpeg)))).code)
        val s = call("own", "bloodbank_save", bank())
        assertEquals(200, s.status, s.toString())
        assertEquals(true, s.body["pending"].booleanValue())
        val id = s.body["id"].textValue()
        val x = row("SELECT * FROM blood_banks WHERE id = ?", id)
        assertEquals("pending", x["status"])
        assertEquals("own", x["owner_uid"])
        assertEquals("01712345678", x["phone"])
        assertEquals("", x["open_at"])
        assertEquals(2L, row("SELECT count(*) AS n FROM verify_docs WHERE kind = 'bloodbank' AND listing_id = ?", id)["n"])
        // own list: pending → document state on the card
        val mine = call("own", "bloodbank_mine").body["items"].single()
        assertEquals("pending", mine["status"].textValue())
        assertEquals("BB-1", mine["licenseNo"].textValue())
        assertEquals(listOf("whole_blood", "platelets"), mine["services"].map { it.textValue() })
        assertTrue(mine["docs"].any { it["type"].textValue() == "front" && it["has"].booleanValue() })
        // not listed yet
        assertEquals(0, call(null, "bloodbanks", mapOf("district" to DIST), ip = "6.6.6.6").body["items"].size())
        // the admin panel: pending with documents and checks → approve
        val pend = call(ADMIN, "admin_bloodbanks", mapOf("status" to "pending"))
        val item = pend.body["items"].single()
        assertEquals(id, item["id"].textValue())
        assertEquals("own", item["ownerUid"].textValue())
        assertEquals(false, item["addedByAdmin"].booleanValue())
        assertTrue(item["verify"]["docs"].any { it["type"].textValue() == "front" && it["has"].booleanValue() })
        assertTrue(item.has("duplicates"))
        val checks = VerifySpec.checks("bloodbank", mapOf("kind" to "private")).map { it.id }
        assertEquals(400, call(ADMIN, "admin_bloodbank_resolve", mapOf("id" to id, "status" to "approved", "checks" to listOf("name"))).status)
        val ap = call(ADMIN, "admin_bloodbank_resolve", mapOf("id" to id, "status" to "approved", "checks" to checks))
        assertEquals(200, ap.status, ap.toString())
        val list = call("p1", "bloodbanks", mapOf("district" to DIST))
        assertEquals(id, list.body["items"].single()["id"].textValue())
        assertEquals(true, list.body["items"][0]["openNow"].booleanValue())
        assertFalse(list.body["items"][0].has("licenseNo"))
        assertEquals(1, call("p1", "bloodbanks", mapOf("district" to DIST, "open24" to true)).body["items"].size())
        assertEquals(400, call("p1", "bloodbanks", mapOf("district" to "x")).status)

        // edit: hours only → stays approved; a new phone → review again
        val h = call("own", "bloodbank_save", bank(mapOf("id" to id, "open24" to false, "openAt" to "08:00", "closeAt" to "22:00", "docs" to null)))
        assertEquals(200, h.status, h.toString())
        assertEquals(false, h.body["pending"].booleanValue())
        assertEquals("approved", row("SELECT status FROM blood_banks WHERE id = ?", id)["status"])
        val p = call("own", "bloodbank_save", bank(mapOf("id" to id, "phone" to "01812345678", "docs" to null)))
        assertEquals(true, p.body["pending"].booleanValue())
        assertEquals("pending", row("SELECT status FROM blood_banks WHERE id = ?", id)["status"])
        val changes = call(ADMIN, "admin_bloodbanks", mapOf("status" to "pending")).body["items"][0]["changes"]
        assertTrue(changes.any { it["field"].textValue() == "phone" && it["before"].textValue() == "01712345678" }, changes.toString())
        assertEquals(404, call("p1", "bloodbank_save", bank(mapOf("id" to id))).status)
        assertEquals(404, call("own", "bloodbank_save", bank(mapOf("id" to "../x"))).status)
        assertEquals(200, call(ADMIN, "admin_bloodbank_resolve", mapOf("id" to id, "status" to "approved", "checks" to checks)).status)

        // calls: once per 10 minutes per person, owner's own calls not counted
        assertEquals(200, call("p1", "bloodbank_called", mapOf("id" to id)).status)
        assertEquals(200, call("p1", "bloodbank_called", mapOf("id" to id)).status)
        assertEquals(200, call("own", "bloodbank_called", mapOf("id" to id)).status)
        assertEquals(200, call("p1", "bloodbank_called", mapOf("id" to "nope")).status)
        val st = call("own", "bloodbank_stats").body["items"].single()
        assertEquals(id, st["id"].textValue())
        assertEquals(1, st["calls"]["total"].intValue())
        assertEquals(1, st["callers"].intValue())
        assertEquals(0, st["reports"].intValue())
        assertEquals(30, st["daily"].size())

        // owner delete: blocked while a report is open, then gone with its statistics and documents
        jdbc.update(
            "INSERT INTO mod_reports (id, target_path, kind, reporter_uid, status) VALUES ('m1', ?, 'bloodbank', 'p1', 'open')", "bloodBanks/$id",
        )
        assertEquals(409, call("own", "bloodbank_delete", mapOf("id" to id)).status)
        jdbc.update("UPDATE mod_reports SET status = 'dismissed' WHERE id = 'm1'")
        assertEquals(200, call("p1", "bloodbank_delete", mapOf("id" to id)).status)
        assertEquals(1L, row("SELECT count(*) AS n FROM blood_banks WHERE id = ?", id)["n"])
        assertEquals(200, call("own", "bloodbank_delete", mapOf("id" to id)).status)
        assertEquals(0L, row("SELECT count(*) AS n FROM blood_banks WHERE id = ?", id)["n"])
        assertEquals(0L, row("SELECT count(*) AS n FROM listing_stats WHERE listing_id = ?", id)["n"])
        assertEquals(0L, row("SELECT count(*) AS n FROM verify_docs WHERE listing_id = ?", id)["n"])
    }

    @Test
    fun `suspended banks cannot be edited or removed by the owner, three per account`() {
        user("own", mapOf("name" to "মালিক"))
        val ids = (1..3).map { i ->
            call("own", "bloodbank_save", bank(mapOf("licenseNo" to "BB-$i", "phone" to "0171234567$i"))).body["id"].textValue()
        }
        assertEquals("একটা অ্যাকাউন্টে সর্বোচ্চ ৩টা ব্লাড ব্যাংক", call("own", "bloodbank_save", bank(mapOf("licenseNo" to "BB-9"))).error)
        jdbc.update("UPDATE blood_banks SET status = 'suspended' WHERE id = ?", ids[0])
        assertEquals(403, call("own", "bloodbank_save", bank(mapOf("id" to ids[0], "docs" to null))).status)
        assertEquals(403, call("own", "bloodbank_delete", mapOf("id" to ids[0])).status)
        assertEquals(3, call("own", "bloodbank_mine").body["items"].size())
    }

    @Test
    fun `admin adds, edits (owner told) and deletes (open reports dismissed, owner told with reason); districts`() {
        admin()
        user("own", mapOf("name" to "মালিক"))
        user("p1", emptyMap())
        val plain = call("p1", "admin_bloodbank_add", bank())
        assertEquals(403, plain.status)
        val add = call(ADMIN, "admin_bloodbank_add", bank(mapOf("kind" to "govt", "licenseNo" to null, "upazilaId" to 999999)))
        assertEquals(200, add.status, add.toString())
        val id = add.body["id"].textValue()
        val x = row("SELECT * FROM blood_banks WHERE id = ?", id)
        assertEquals("approved", x["status"])
        assertNull(x["owner_uid"])
        assertEquals(true, x["added_by_admin"])
        assertNull(x["upazila_id"])
        assertEquals("", x["license_no"])
        assertEquals(1L, row("SELECT count(*) AS n FROM admin_log WHERE action = 'admin_bloodbank_add'")["n"])
        assertEquals(id, call(null, "bloodbanks", mapOf("district" to DIST), ip = "8.1.1.1").body["items"][0]["id"].textValue())

        // owner's bank, approved; admin fixes the hours → owner told, approved data follows
        val own = call("own", "bloodbank_save", bank(mapOf("licenseNo" to "BB-2", "phone" to "01912345678"))).body["id"].textValue()
        jdbc.update("UPDATE blood_banks SET status = 'approved' WHERE id = ?", own)
        val e = call(ADMIN, "admin_bloodbank_edit", bank(mapOf("id" to own, "licenseNo" to "BB-2", "phone" to "01912345678", "name" to "জীবন ব্লাড ব্যাংক ২")))
        assertEquals(200, e.status, e.toString())
        val y = row("SELECT * FROM blood_banks WHERE id = ?", own)
        assertEquals("জীবন ব্লাড ব্যাংক ২", y["approved_name"])
        assertTrue((y["last_approved"].toString()).contains("জীবন ব্লাড ব্যাংক ২"))
        assertTrue(y["edited_by_admin_at"] != null)
        assertTrue(inbox("own").contains("Lifolo টিম আপনার ব্লাড ব্যাংকের তথ্য ঠিক করেছে"))
        assertEquals(400, call(ADMIN, "admin_bloodbank_edit", mapOf("id" to "")).status)
        assertEquals(404, call(ADMIN, "admin_bloodbank_edit", bank(mapOf("id" to "nope"))).status)

        jdbc.update("INSERT INTO mod_reports (id, target_path, kind, reporter_uid, status) VALUES ('m1', ?, 'bloodbank', 'p1', 'open')", "bloodBanks/$own")
        val d = call(ADMIN, "admin_bloodbank_delete", mapOf("id" to own, "reason" to "  বন্ধ হয়ে গেছে "))
        assertEquals(200, d.status, d.toString())
        assertEquals(0L, row("SELECT count(*) AS n FROM blood_banks WHERE id = ?", own)["n"])
        assertEquals("dismissed", row("SELECT status FROM mod_reports WHERE id = 'm1'")["status"])
        val last = jdbc.queryForList("SELECT body FROM inbox_items WHERE uid = 'own' ORDER BY created_at DESC, id LIMIT 1", String::class.java).single()
        assertEquals("জীবন ব্লাড ব্যাংক ২ · কারণ: বন্ধ হয়ে গেছে", last)
        assertEquals(200, call(ADMIN, "admin_bloodbank_delete", mapOf("id" to own)).status)

        val districts = call(ADMIN, "admin_districts").body["items"]
        assertEquals(64, districts.size())
        assertTrue(districts.any { it.textValue() == DIST })
    }
}
