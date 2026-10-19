package com.lifolo.admin

import com.lifolo.support.IntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * api/community.js admin parts, catalog.js, verify.js actions, adminweb.js and useractivity.js. The Node tests do not
 * cover admin_user_action, admin_stats or admin_log — these cases are new.
 */
class AdminPanelIT : IntegrationTest() {
    private val OWNER = "panelOwner"

    private fun ok(uid: String, action: String, body: Map<String, Any?> = emptyMap(), lang: String = "bn") =
        call(uid, action, body, lang = lang).also { assertEquals(200, it.status, "$action: $it") }.body

    private fun owner() = user(OWNER, emptyMap(), email = "owner@example.com")

    @Test
    fun `extra admins - owner only, at most 20, removing ends the rights`() {
        owner()
        user("adm2", emptyMap(), email = "Second@Example.com")
        user("plain1")
        assertEquals("শুধু মালিক", call("plain1", "admin_list").error)
        assertEquals("Owner only", call("plain1", "admin_add", mapOf("email" to "x@y.com"), lang = "en").error)
        assertEquals("সঠিক ইমেইল দিন", call(OWNER, "admin_add", mapOf("email" to "not-an-email")).error)
        ok(OWNER, "admin_add", mapOf("email" to " Second@Example.com "))
        val list = ok(OWNER, "admin_list")
        assertEquals("owner@example.com", list["owners"][0].textValue())
        assertEquals("second@example.com", list["items"][0]["email"].textValue())
        // the new admin can use admin actions (the cache was cleared), but not the owner's
        ok("adm2", "admin_reports")
        assertEquals(403, call("adm2", "admin_list").status)
        // the log keeps the request text as sent (adminweb.js LOGGED)
        assertEquals("add  Second@Example.com ", row("SELECT detail FROM admin_log WHERE action = 'admin_add'")["detail"])
        ok(OWNER, "admin_remove", mapOf("email" to "second@example.com"))
        assertEquals(403, call("adm2", "admin_reports").status)
        for (i in 0 until 20) jdbc.update("INSERT INTO admin_emails (email) VALUES (?)", "a$i@example.com")
        assertEquals("সর্বোচ্চ ২০ জন অ্যাডমিন", call(OWNER, "admin_add", mapOf("email" to "late@example.com")).error)
    }

    @Test
    fun `catalog - defaults for guests, the admin renames, adds and hides - codes are never deleted`() {
        owner()
        val g = call(null, "catalog_get")
        assertEquals(200, g.status)
        val doctors = g.body["lists"]["specialty"]["doctor"]
        assertTrue(doctors.size() > 5)
        assertEquals(0, g.body["updatedAt"].longValue())
        val first = doctors[0]
        assertEquals("অজানা তালিকা", call(OWNER, "admin_catalog_set", mapOf("kind" to "specialty", "type" to "x", "items" to emptyList<Any>())).error)
        assertEquals(400, call(OWNER, "admin_catalog_set", mapOf("kind" to "specialty", "type" to "doctor", "items" to emptyList<Any>())).status)
        assertEquals(
            "বাংলা আর ইংরেজি দুটো নামই লিখুন",
            call(OWNER, "admin_catalog_set", mapOf("kind" to "specialty", "type" to "doctor", "items" to listOf(mapOf("en" to "X")))).error,
        )
        val res = ok(
            OWNER, "admin_catalog_set",
            mapOf(
                "kind" to "specialty", "type" to "doctor",
                "items" to listOf(
                    mapOf("code" to first["code"].textValue(), "en" to "Renamed", "bn" to "নতুন নাম"),
                    mapOf("en" to "Sleep Medicine", "bn" to "ঘুমের রোগ"),
                    mapOf("en" to "Sleep Medicine", "bn" to "ঘুমের রোগ", "hidden" to true),
                ),
            ),
        )["items"]
        assertEquals("Renamed", res[0]["en"].textValue())
        assertEquals("c_sleep_medicine", res[1]["code"].textValue())
        assertEquals("c_sleep_medicine_2", res[2]["code"].textValue())
        assertTrue(res[2]["hidden"].booleanValue())
        // every other old code is still there, hidden, at the end
        assertEquals(doctors.size() + 2, res.size())
        assertTrue(res.last()["hidden"].booleanValue())
        val after = ok(OWNER, "catalog_get")
        assertTrue(after["updatedAt"].longValue() > 0)
        assertEquals("Renamed", after["lists"]["specialty"]["doctor"][0]["en"].textValue())
        assertEquals("config/catalog/specialty/doctor", row("SELECT target FROM admin_log WHERE action = 'admin_catalog_set'")["target"])
    }

    private val jpeg = "/9j/4AAQSkZJRgABAQAAAQABAAD"

    @Test
    fun `document photos - the admin asks, the owner sends later, the admin looks`() {
        owner()
        user("shopOwner", mapOf("name" to "দোকানি"))
        jdbc.update("INSERT INTO pharmacies (uid, name, license_no, phone, district, status) VALUES ('shopOwner', 'দোকান', 'L-1', '01711111111', 'ঢাকা', 'pending')")
        assertEquals(403, call("shopOwner", "admin_request_docs", mapOf("kind" to "pharmacy", "id" to "shopOwner")).status)
        assertEquals(400, call(OWNER, "admin_request_docs", mapOf("kind" to "x", "id" to "shopOwner")).status)
        ok(OWNER, "admin_request_docs", mapOf("kind" to "pharmacy", "id" to "shopOwner", "note" to "লাইসেন্স অস্পষ্ট"))
        val p = row("SELECT docs_requested_at, docs_request_note FROM pharmacies WHERE uid = 'shopOwner'")
        assertNotNull(p["docs_requested_at"])
        assertEquals("লাইসেন্স অস্পষ্ট", p["docs_request_note"])
        val inbox = ok("shopOwner", "inbox")["items"][0]
        assertEquals("আপনার ফার্মেসি-এর কাগজের ছবি দিন", inbox["title"].textValue())
        assertEquals("দোকান · লাইসেন্স অস্পষ্ট", inbox["body"].textValue())
        assertEquals("pharmacy", inbox["data"]["kind"].textValue())
        // a blood bank an admin added has no owner
        jdbc.update("INSERT INTO blood_banks (id, name, kind, phone, district, added_by_admin) VALUES ('bb1', 'ব্যাংক', 'govt', '01722222222', 'ঢাকা', TRUE)")
        assertEquals("এটা অ্যাডমিন যোগ করেছেন, কোনো মালিক নেই", call(OWNER, "admin_request_docs", mapOf("kind" to "bloodbank", "id" to "bb1")).error)

        // only the owner sends; a bad photo is refused before the quota
        user("other1")
        assertEquals(404, call("other1", "verify_docs_upload", mapOf("kind" to "pharmacy", "id" to "shopOwner", "docs" to mapOf("license" to jpeg))).status)
        assertEquals(400, call("shopOwner", "verify_docs_upload", mapOf("kind" to "pharmacy", "id" to "shopOwner", "docs" to mapOf("license" to "nope!"))).status)
        val up = ok("shopOwner", "verify_docs_upload", mapOf("kind" to "pharmacy", "id" to "shopOwner", "docs" to mapOf("license" to jpeg)))
        assertEquals(1, up["saved"].intValue())
        assertTrue(up["docsMissing"].booleanValue(), "the front photo is still missing")
        assertFalse(up["docsRequested"].booleanValue())
        val after = row("SELECT docs_requested_at, docs_sent_at, docs_have FROM pharmacies WHERE uid = 'shopOwner'")
        assertNull(after["docs_requested_at"])
        assertNotNull(after["docs_sent_at"])

        val v = ok(OWNER, "admin_verify_docs", mapOf("kind" to "pharmacy", "id" to "shopOwner"))
        assertEquals(listOf("license", "front", "pharmacist"), v["photos"].map { it["type"].textValue() })
        assertEquals(jpeg, v["photos"][0]["data"].textValue())
        assertTrue(v["photos"][1]["data"].isNull)
        assertTrue(v["docs"][0]["has"].booleanValue())
        assertFalse(v["docs"][1]["has"].booleanValue())
        assertTrue(v["checks"].size() >= 7)
        assertEquals("viewed documents", row("SELECT detail FROM admin_log WHERE action = 'admin_verify_docs'")["detail"])
    }

    @Test
    fun `users - find by email, phone or id - one user's page - appeal decisions - the log`() {
        owner()
        user("person1", mapOf("name" to "রহিম", "phone" to "01711000111", "blood_group" to "O+", "place_name" to "ঢাকা"), email = "rahim@example.com")
        jdbc.update("INSERT INTO pharmacies (uid, name, license_no, phone, district, status) VALUES ('person1', 'রহিম ফার্মেসি', 'L-7', '01711000111', 'ঢাকা', 'approved')")
        jdbc.update(
            "INSERT INTO providers (uid, type, name, listed, active, appointments) VALUES ('person1', 'doctor', 'রহিম', TRUE, TRUE, TRUE)",
        )
        assertEquals(400, call(OWNER, "admin_user_find", mapOf("q" to "ab")).status)
        assertEquals(listOf("person1"), ok(OWNER, "admin_user_find", mapOf("q" to "Rahim@Example.com"))["items"].map { it["uid"].textValue() })
        val byPhone = ok(OWNER, "admin_user_find", mapOf("q" to "+880 1711-000111"))["items"]
        assertEquals(listOf("person1"), byPhone.map { it["uid"].textValue() })
        assertEquals("রহিম", byPhone[0]["name"].textValue())
        assertEquals("ঢাকা", byPhone[0]["district"].textValue())
        assertEquals(listOf("person1"), ok(OWNER, "admin_user_find", mapOf("q" to "person1"))["items"].map { it["uid"].textValue() })
        assertEquals(0, ok(OWNER, "admin_user_find", mapOf("q" to "nobody-here"))["items"].size())

        val u = ok(OWNER, "admin_user", mapOf("uid" to "person1"))["user"]
        assertEquals("rahim@example.com", u["email"].textValue())
        assertEquals("O+", u["bloodGroup"].textValue())
        assertEquals("রহিম ফার্মেসি", u["pharmacy"]["name"].textValue())
        assertTrue(u["provider"]["listed"].booleanValue())
        assertFalse(u["isAdmin"].booleanValue())
        assertTrue(u["createdAt"].isNumber)
        assertEquals(404, call(OWNER, "admin_user", mapOf("uid" to "ghost")).status)

        // suspend the account: Auth disabled, reason kept, logins end, the doctor unlisted
        ok(OWNER, "admin_user_action", mapOf("uid" to "person1", "op" to "disable", "reason" to "ভুয়া ডাক্তার"))
        assertTrue(identity.users["person1"]!!.disabled)
        assertEquals("ভুয়া ডাক্তার", row("SELECT disabled_reason FROM accounts WHERE uid = 'person1'")["disabled_reason"])
        assertTrue(identity.revokedCalls.contains("person1"))
        assertEquals("account", row("SELECT suspend_reason FROM providers WHERE uid = 'person1'")["suspend_reason"])
        val blocked = call("person1", "me", lang = "en", token = identity.jwtFor("person1"))
        assertEquals(403, blocked.status)
        assertEquals("account_disabled", blocked.code)
        assertTrue(blocked.error!!.contains("Reason: ভুয়া ডাক্তার."))
        ok(OWNER, "admin_user_action", mapOf("uid" to "person1", "op" to "enable"))
        assertFalse(identity.users["person1"]!!.disabled)
        assertNull(row("SELECT disabled_reason FROM accounts WHERE uid = 'person1'")["disabled_reason"])
        assertEquals(true, row("SELECT listed FROM providers WHERE uid = 'person1'")["listed"])
        // nobody acts on the owner; not on your own account; only the owner on an admin
        user("helperAdm", emptyMap(), email = "helper@example.com")
        jdbc.update("INSERT INTO admin_emails (email) VALUES ('helper@example.com')")
        redis.connectionFactory?.connection?.use { it.serverCommands().flushAll() }
        assertEquals("মালিকের অ্যাকাউন্টে এই কাজ করা যায় না", call("helperAdm", "admin_user_action", mapOf("uid" to OWNER, "op" to "disable")).error)
        assertEquals("নিজের অ্যাকাউন্টে এটা করা যায় না", call(OWNER, "admin_user_action", mapOf("uid" to OWNER, "op" to "disable")).error)
        user("helperAdm2", emptyMap(), email = "helper2@example.com")
        jdbc.update("INSERT INTO admin_emails (email) VALUES ('helper2@example.com')")
        redis.connectionFactory?.connection?.use { it.serverCommands().flushAll() }
        assertEquals("অ্যাডমিনের ওপর শুধু মালিক ব্যবস্থা নিতে পারেন", call("helperAdm", "admin_user_action", mapOf("uid" to "helperAdm2", "op" to "disable")).error)
        assertEquals(404, call(OWNER, "admin_user_action", mapOf("uid" to "nobody", "op" to "disable")).status)
        assertEquals("অজানা কাজ", call(OWNER, "admin_user_action", mapOf("uid" to "person1", "op" to "zap")).error)
        // home care ban and lifting it
        ok(OWNER, "admin_user_action", mapOf("uid" to "person1", "op" to "homecare_ban"))
        assertEquals(true, row("SELECT home_visit_blocked FROM providers WHERE uid = 'person1'")["home_visit_blocked"])
        ok(OWNER, "admin_user_action", mapOf("uid" to "person1", "op" to "homevisit_unban"))
        assertEquals(false, row("SELECT home_visit_blocked FROM providers WHERE uid = 'person1'")["home_visit_blocked"])
        // chambers removed
        jdbc.update("INSERT INTO provider_chambers (provider_uid, idx, hospital_name) VALUES ('person1', 0, 'X')")
        ok(OWNER, "admin_user_action", mapOf("uid" to "person1", "op" to "provider_chambers_remove", "reason" to "ভুয়া চেম্বার"))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM provider_chambers WHERE provider_uid = 'person1'", Int::class.java))
        assertEquals("Lifolo টিম আপনার চেম্বার সরিয়ে দিয়েছে", ok("person1", "inbox")["items"][0]["title"].textValue())

        // the log: newest first, cursor
        val log = ok(OWNER, "admin_log")["items"]
        assertEquals("admin_user_action", log[0]["action"].textValue())
        assertEquals("users/person1", log[0]["target"].textValue())
        assertEquals("provider_chambers_remove · ভুয়া চেম্বার", log[0]["detail"].textValue())
        assertEquals("owner@example.com", log[0]["by"].textValue())
        val older = ok(OWNER, "admin_log", mapOf("before" to log[0]["at"].longValue()))["items"]
        assertTrue(older.all { it["at"].longValue() < log[0]["at"].longValue() })
        assertEquals(403, call("person1", "admin_log").status)

        // the user's sections
        val admin = ok(OWNER, "admin_user_section", mapOf("uid" to "person1", "section" to "admin"))
        assertEquals("admin", admin["section"].textValue())
        assertTrue(admin["items"].size() >= 5)
        for (s in listOf("blood", "community", "services", "care", "inbox", "support")) ok(OWNER, "admin_user_section", mapOf("uid" to "person1", "section" to s))
        assertEquals(400, call(OWNER, "admin_user_section", mapOf("uid" to "person1", "section" to "x")).status)
        assertEquals("রহিম ফার্মেসি", ok(OWNER, "admin_user_section", mapOf("uid" to "person1", "section" to "services"))["pharmacy"]["name"].textValue())
    }

    @Test
    fun `stats - cached 30 minutes unless fresh`() {
        owner()
        user("st1", mapOf("blood_group" to "A+"))
        val s = ok(OWNER, "admin_stats")
        assertEquals(30, s["days"].size())
        assertEquals(30, s["series"]["requests"].size())
        assertEquals(30, s["series"]["newUsers"].size())
        assertEquals(1, s["bloodGroups"]["A+"].intValue())
        assertEquals(2, s["totals"]["usersWithProfile"].intValue())
        assertTrue(s["users"]["total"].intValue() >= 2)
        user("st2", mapOf("blood_group" to "A+"))
        assertEquals(1, ok(OWNER, "admin_stats")["bloodGroups"]["A+"].intValue(), "cached")
        assertEquals(2, ok(OWNER, "admin_stats", mapOf("fresh" to true))["bloodGroups"]["A+"].intValue())
        assertEquals(403, call("st1", "admin_stats").status)
    }

    @Test
    fun `overview - every waiting list at once, area names, the owner's admin list`() {
        owner()
        user("ovShop", mapOf("name" to "দোকানি"))
        jdbc.update(
            "INSERT INTO pharmacies (uid, name, license_no, phone, district, upazila_id, status) VALUES ('ovShop', 'অপেক্ষার দোকান', 'L-2', '01711111112', 'গাজীপুর', 320, 'pending')",
        )
        user("ovTicket")
        ok("ovTicket", "ticket_create", mapOf("category" to "other", "text" to "একটা সমস্যা লিখলাম এখানে"))
        val ov = ok(OWNER, "admin_overview")
        assertEquals("owner@example.com", ov["me"]["email"].textValue())
        assertTrue(ov["me"]["isOwner"].booleanValue())
        val shop = ov["pharmacies"]["items"].first { it["name"].textValue() == "অপেক্ষার দোকান" }
        assertEquals("গাজীপুর সদর, গাজীপুর", shop["areaLabel"].textValue())
        assertEquals(1, ov["tickets"]["items"].size())
        for (k in listOf("badges", "ambulances", "bloodBanks", "labs", "hospitalRequests", "reports", "corrections")) {
            assertTrue(ov[k]["items"].isArray, k)
            assertNull(ov[k]["error"], k)
        }
        assertTrue(ov["admins"]["owners"].isArray)
        assertTrue(ov["at"].isNumber)
        user("ovAdmin", emptyMap(), email = "helper@example.com")
        jdbc.update("INSERT INTO admin_emails (email) VALUES ('helper@example.com')")
        redis.connectionFactory?.connection?.use { it.serverCommands().flushAll() }
        val asAdmin = ok("ovAdmin", "admin_overview")
        assertTrue(asAdmin["admins"].isNull, "only the owner sees the admin list")
        assertEquals(401, call(null, "admin_overview").status)
    }
}
