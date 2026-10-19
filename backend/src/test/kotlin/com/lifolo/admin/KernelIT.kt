package com.lifolo.admin

import com.lifolo.admin.kernel.Catalog
import com.lifolo.admin.kernel.ListingReview
import com.lifolo.admin.kernel.ListingRules
import com.lifolo.admin.kernel.ListingRows
import com.lifolo.admin.kernel.ListingKind
import com.lifolo.admin.kernel.ListingStats
import com.lifolo.admin.kernel.Moderation
import com.lifolo.admin.kernel.Sanctions
import com.lifolo.admin.kernel.VerifyDocs
import com.lifolo.admin.kernel.VerifySpec
import com.lifolo.auth.AuthUser
import com.lifolo.common.ApiException
import com.lifolo.common.Body
import com.lifolo.common.Lang
import com.lifolo.common.action.ActionContext
import com.lifolo.support.IntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.sql.Timestamp

/**
 * The P6 kernel on the real schema: moderation counts, sanctions, verification documents, the admin listing
 * decision (port of the e2efixes "rejected edit restores the approved data" case and the resolve flows),
 * call statistics and the catalog.
 */
class KernelIT : IntegrationTest() {
    @Autowired lateinit var moderation: Moderation

    @Autowired lateinit var sanctions: Sanctions

    @Autowired lateinit var verify: VerifyDocs

    @Autowired lateinit var review: ListingReview

    @Autowired lateinit var stats: ListingStats

    @Autowired lateinit var rows: ListingRows

    @Autowired lateinit var catalog: Catalog

    private val admin = AuthUser("admin1", "owner@example.com", emailVerified = true)

    private val jpeg = "/9j/4AAQSkZJRgABAQ"

    private fun ctx(body: Map<String, Any?>, lang: String = "bn"): ActionContext {
        val req = MockHttpServletRequest()
        req.setAttribute(Lang.ATTR, lang)
        return ActionContext(req, MockHttpServletResponse(), Body.of(mapper.valueToTree(body)), admin, admin, "admin_test")
    }

    private fun inbox(uid: String): List<Map<String, Any?>> =
        jdbc.queryForList("SELECT title, body, data::text AS data FROM inbox_items WHERE uid = ? ORDER BY created_at, id", uid)

    private fun ambulance(id: String, owner: String, vehicle: String, status: String, docs: List<String> = listOf("reg", "fitness", "front")) {
        jdbc.update(
            """
            INSERT INTO ambulances (id, owner_uid, name, phone, vehicle_no, types, district, status, docs_have)
            VALUES (?, ?, 'রহিম অ্যাম্বুলেন্স', '01711000000', ?, '{ac}', 'ঢাকা', ?, ?::text[])
            """.trimIndent(),
            id, owner, vehicle, status, docs.joinToString(",", "{", "}"),
        )
    }

    // ------------------------------------------------------------------ moderation

    @Test
    fun `moderation - report count, open report blocks deleting, edit reopens dismissed reports`() {
        listOf("r1", "r2", "r3").forEach { user(it) }
        val path = "ambulances/a1"
        jdbc.update("INSERT INTO mod_reports (id, target_path, kind, reporter_uid, status) VALUES ('m1', ?, 'ambulance', 'r1', 'open')", path)
        jdbc.update("INSERT INTO mod_reports (id, target_path, kind, reporter_uid, status) VALUES ('m2', ?, 'ambulance', 'r2', 'removed')", path)
        jdbc.update("INSERT INTO mod_reports (id, target_path, kind, reporter_uid, status) VALUES ('m3', ?, 'ambulance', 'r3', 'dismissed')", path)
        // the same reporter may report a provider twice (home care + chamber): Node ids differ
        jdbc.update("INSERT INTO mod_reports (id, target_path, kind, reporter_uid) VALUES ('p1', 'providers/d1', 'homecare', 'r1')")
        jdbc.update("INSERT INTO mod_reports (id, target_path, kind, reporter_uid) VALUES ('p1__provider', 'providers/d1', 'provider', 'r1')")

        assertEquals(2, moderation.reportCount(path))
        assertEquals(0, moderation.reportCount("ambulances/none"))
        val e = assertThrows(ApiException::class.java) { moderation.assertNoOpenReports("bn", path) }
        assertEquals(409, e.status)
        assertEquals("এটার একটা রিপোর্ট খোলা আছে। Lifolo টিম দেখার পর মুছতে পারবেন।", e.message)
        assertEquals(
            "This has an open report. It can be removed after the Lifolo team reviews it.",
            assertThrows(ApiException::class.java) { moderation.assertNoOpenReports("en", path) }.message,
        )
        jdbc.update("UPDATE mod_reports SET status = 'dismissed' WHERE id = 'm1'")
        moderation.assertNoOpenReports("bn", path)

        assertEquals(2, moderation.reopenOnEdit(path))
        assertEquals(
            listOf("open", "edited"),
            row("SELECT status, reopen_reason FROM mod_reports WHERE id = 'm3'").values.toList(),
        )
        assertEquals("removed", row("SELECT status FROM mod_reports WHERE id = 'm2'")["status"])
        assertEquals(0, moderation.reopenOnEdit(path))
    }

    // ------------------------------------------------------------------ listing review

    @Test
    fun `rejecting a pending edit restores the approved data, ratings and online settings stay (e2efixes)`() {
        val id = "ph1"
        user(id, profile = emptyMap())
        jdbc.update(
            """
            INSERT INTO pharmacies (uid, name, license_no, phone, district, address, status, online_enabled, online_fee)
            VALUES (?, 'দোকান', 'DL-1', '01711000000', 'ঢাকা', 'ঠিকানা', 'approved', TRUE, '30')
            """.trimIndent(),
            id,
        )
        // the approved state, then an edit waiting for review (name + discount note)
        val approved = ListingRules.snapshot(rows.load(ListingKind.PHARMACY, id))
        jdbc.update(
            """
            UPDATE pharmacies SET name = 'নতুন নাম', discount_note = 'নতুন শর্ত', status = 'pending', last_approved = ?::jsonb,
              rating_sum = 9, rating_count = 2, aspect_price_sum = 4, aspect_price_count = 1 WHERE uid = ?
            """.trimIndent(),
            mapper.writeValueAsString(approved), id,
        )
        val x = rows.load(ListingKind.PHARMACY, id)!!
        assertEquals(listOf("name", "discountNote"), review.changedFields(x).map { it.field })

        val r = review.resolve(ctx(mapOf("id" to id, "status" to "rejected", "reason" to "মিলছে না")), "pharmacy", "pharmacies", id)
        assertEquals(mapOf("ok" to true, "restored" to true), r)
        val after = row("SELECT * FROM pharmacies WHERE uid = ?", id)
        assertEquals("দোকান", after["name"])
        assertEquals("approved", after["status"])
        assertEquals("দোকান", after["approved_name"])
        assertEquals("মিলছে না", after["edit_rejected_reason"])
        assertNotNull(after["edit_rejected_at"])
        assertNull(after["discount_note"])
        assertEquals("ঠিকানা", after["address"])
        assertEquals(9, after["rating_sum"])
        assertEquals(4, after["aspect_price_sum"])
        assertEquals(true, after["online_enabled"])
        assertEquals("30", after["online_fee"])
        val push = inbox(id).single()
        assertEquals("আপনার ফার্মেসি-এর বদল গ্রহণ হয়নি — আগের তথ্যই তালিকায় আছে", push["title"])
        assertEquals("নতুন নাম · কারণ: মিলছে না", push["body"])
        assertTrue((push["data"] as String).contains("\"services\""))

        // the same status again: nothing changes, not admin-logged
        assertEquals(mapOf("ok" to true, "unchanged" to true), review.resolve(ctx(mapOf("status" to "approved")), "pharmacy", "pharmacies", id))
    }

    @Test
    fun `approving needs the photos and every check; the approval stores verification and the snapshot`() {
        user("own1", profile = mapOf("lang" to "en"))
        ambulance("amb1", "own1", "DHAKA METRO-CHA 11-2233", "pending", docs = listOf("reg", "front"))
        val checks = VerifySpec.checks("ambulance", null).map { it.id }
        val noDocs = assertThrows(ApiException::class.java) {
            review.resolve(ctx(mapOf("status" to "approved", "checks" to checks)), "ambulance", "ambulances", "amb1")
        }
        assertEquals(400, noDocs.status)
        assertEquals("docs_required", noDocs.code)
        assertEquals("দরকারি কাগজের ছবি নেই — আগে মালিকের কাছে ছবি চান।", noDocs.message)

        jdbc.update("UPDATE ambulances SET docs_have = '{reg,fitness,front}', available = TRUE, available_at = ? WHERE id = 'amb1'", Timestamp(1_000_000L))
        val noTicks = assertThrows(ApiException::class.java) {
            review.resolve(ctx(mapOf("approve" to true, "checks" to checks.drop(1))), "ambulance", "ambulances", "amb1")
        }
        assertEquals("checks_required", noTicks.code)
        assertEquals("ওয়েব অ্যাডমিন প্যানেলে ছবি দেখে সব বিষয় টিক দিয়ে তারপর অনুমোদন দিন।", noTicks.message)

        assertEquals(mapOf("ok" to true), review.resolve(ctx(mapOf("approve" to true, "checks" to checks + "extra")), "ambulance", "ambulances", "amb1"))
        val a = row("SELECT * FROM ambulances WHERE id = 'amb1'")
        assertEquals("approved", a["status"])
        assertEquals("admin1", a["verified_by"])
        assertEquals("owner@example.com", a["verified_by_email"])
        assertEquals(checks, (a["verification_checks"] as java.sql.Array).array.let { (it as Array<*>).map { c -> c.toString() } })
        assertEquals("রহিম অ্যাম্বুলেন্স", a["approved_name"])
        assertEquals(1_000_000L + ListingReview.AMBULANCE_REMIND_AT_MS, (a["remind_at"] as Timestamp).time)
        val snap = mapper.readTree(a["last_approved"].toString())
        assertEquals("DHAKA METRO-CHA 11-2233", snap["vehicleNo"].textValue())
        assertFalse(snap.has("status"))
        assertFalse(snap.has("ownerUid"))
        assertEquals("Your ambulance is now listed", inbox("own1").single()["title"])
        val x = rows.load(ListingKind.AMBULANCE, "amb1")!!
        assertEquals(emptyList<Any>(), review.changedFields(x))
        @Suppress("UNCHECKED_CAST")
        assertEquals(checks, (x["verification"] as Map<String, Any?>)["checks"])

        // rejected (not an edit): reason stored, owner told
        assertEquals(mapOf("ok" to true), review.resolve(ctx(mapOf("status" to "rejected", "reason" to "ভুল")), "ambulance", "ambulances", "amb1"))
        assertEquals("ভুল", row("SELECT status_reason FROM ambulances WHERE id = 'amb1'")["status_reason"])
        assertEquals("Ambulance not approved", inbox("own1").last()["title"])
        assertEquals("রহিম অ্যাম্বুলেন্স · Reason: ভুল", inbox("own1").last()["body"])
        assertNull(row("SELECT remind_at FROM ambulances WHERE id = 'amb1'")["remind_at"])

        // bad id / missing row
        assertEquals(400, assertThrows(ApiException::class.java) { review.resolve(ctx(emptyMap()), "ambulance", "ambulances", "bad id") }.status)
        assertEquals(404, assertThrows(ApiException::class.java) { review.resolve(ctx(emptyMap()), "ambulance", "ambulances", "nope") }.status)
    }

    @Test
    fun `suspending bans vehicle and owner email; reinstating lifts it (sanctions)`() {
        user("own2", profile = emptyMap(), email = "Driver@Example.com")
        ambulance("amb2", "own2", "ঢাকা মেট্রো-ছ ১১-২২৩৩", "approved")
        review.resolve(ctx(mapOf("status" to "suspended", "reason" to "ভুয়া")), "ambulance", "ambulances", "amb2")
        val a = row("SELECT status, status_reason, suspended_at FROM ambulances WHERE id = 'amb2'")
        assertEquals("suspended", a["status"])
        assertEquals("ভুয়া", a["status_reason"])
        assertNotNull(a["suspended_at"])
        assertEquals(
            "আপনার অ্যাম্বুলেন্স বন্ধ করা হয়েছে। প্রোফাইলের \"সাহায্য ও আপিল\" থেকে আপিল করতে পারেন।",
            inbox("own2").single()["title"],
        )
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM sanctions WHERE listing", Int::class.java))

        // the same vehicle written differently, by someone else → refused
        val again = mapOf("vehicleNo" to "ঢাকা মেট্রো ছ 11-2233")
        val e = assertThrows(ApiException::class.java) { sanctions.checkListing("bn", "ambulance", again, "new@example.com", true) }
        assertEquals(403, e.status)
        assertEquals("এটা আগে Lifolo টিম বন্ধ করেছিল। প্রোফাইলের \"সাহায্য ও আপিল\" থেকে আপিল করতে পারেন।", e.message)
        // a new account with the same email adding another vehicle → refused; editing (not new) does not check the email
        val other = mapOf("vehicleNo" to "CTG-11-9999")
        assertThrows(ApiException::class.java) { sanctions.checkListing("en", "ambulance", other, "driver@example.com", true) }
        sanctions.checkListing("en", "ambulance", other, "driver@example.com", false)
        assertEquals(true, sanctions.forEmail("driver@example.com")["listing"])
        assertEquals("ambulance", sanctions.forEmail("driver@example.com")["kind"])

        // appeal granted
        review.resolve(ctx(mapOf("status" to "approved")), "ambulance", "ambulances", "amb2")
        sanctions.checkListing("bn", "ambulance", again, "new@example.com", true)
        sanctions.checkListing("bn", "ambulance", other, "driver@example.com", true)
        assertEquals(false, sanctions.forEmail("driver@example.com")["listing"])
        assertEquals("approved", row("SELECT status FROM ambulances WHERE id = 'amb2'")["status"])
    }

    @Test
    fun `sanctions - keepForEmail survives for home care, ban info, unban`() {
        assertEquals(emptyMap<String, Any?>(), sanctions.forEmail("a@b.c"))
        sanctions.keepForEmail("a@b.c", false)
        assertEquals(emptyMap<String, Any?>(), sanctions.forEmail("a@b.c"))
        sanctions.keepForEmail("A@b.c", true)
        assertEquals(true, sanctions.forEmail("a@b.c")["homeCare"])
        assertEquals(false, sanctions.forEmail("a@b.c")["listing"])

        val shop = mapOf("licenseNo" to "DL-77", "phone" to "01811000000")
        sanctions.ban("pharmacy", shop, null, mapOf("kind" to "pharmacy", "reason" to "x", "by" to "admin1"))
        val r = row("SELECT kind, reason, info::text AS info FROM sanctions WHERE key = ?", Sanctions.listingKeys("pharmacy", shop)[0])
        assertEquals("pharmacy", r["kind"])
        assertTrue((r["info"] as String).contains("admin1"))
        assertThrows(ApiException::class.java) { sanctions.checkListing("bn", "pharmacy", mapOf("phone" to "+8801811000000"), null, false) }
        sanctions.unban("pharmacy", shop, null)
        sanctions.checkListing("bn", "pharmacy", shop, null, false)
    }

    // ------------------------------------------------------------------ verify docs

    @Test
    fun `verify docs - validation messages, saving updates docs_have, owner and panel info`() {
        user("own3")
        ambulance("amb3", "own3", "X-12345", "pending", docs = emptyList())
        val x = rows.load(ListingKind.AMBULANCE, "amb3")!!
        val bad = assertThrows(ApiException::class.java) {
            verify.validate("bn", "ambulance", x, mapper.valueToTree(mapOf("reg" to "R0lGODlh")), false)
        }
        assertEquals("\"রেজিস্ট্রেশন সনদ বা স্মার্ট কার্ড\"-এর ছবিটা পড়া যায়নি বা অনেক বড়", bad.message)
        val missing = assertThrows(ApiException::class.java) {
            verify.validate("en", "ambulance", x, mapper.valueToTree(mapOf("reg" to jpeg)), true)
        }
        assertEquals("docs_required", missing.code)
        assertTrue(missing.message.startsWith("Add photos of: ফিটনেস সনদ, গাড়ির ছবি (নম্বর প্লেটসহ)."))
        assertEquals(
            "এই কাগজের ছবি দিন: ফিটনেস সনদ, গাড়ির ছবি (নম্বর প্লেটসহ)। ছবি দেওয়ার জায়গা না দেখলে অ্যাপ হালনাগাদ করুন।",
            assertThrows(ApiException::class.java) { verify.validate("bn", "ambulance", x, mapper.valueToTree(mapOf("reg" to jpeg)), true) }.message,
        )
        assertNull(verify.save("bn", "ambulance", "amb3", "own3", x, null, false))

        assertEquals(listOf("reg", "front"), verify.save("bn", "ambulance", "amb3", "own3", x, mapper.valueToTree(mapOf("reg" to jpeg, "front" to "data:image/png;base64,iVBORw0KGgo=")), false))
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM verify_docs WHERE kind = 'ambulance' AND listing_id = 'amb3'", Int::class.java))
        val y = rows.load(ListingKind.AMBULANCE, "amb3")!!
        assertEquals(setOf("reg", "front"), VerifySpec.haveTypes(y))
        val owner = verify.ownerInfo("ambulance", "amb3", y)
        assertEquals(true, owner["docsMissing"])
        assertEquals(false, owner["docsRequested"])
        verify.save("bn", "ambulance", "amb3", "own3", y, mapper.valueToTree(mapOf("fitness" to jpeg, "reg" to jpeg)), false)
        val z = rows.load(ListingKind.AMBULANCE, "amb3")!!
        assertEquals(setOf("reg", "front", "fitness"), VerifySpec.haveTypes(z))
        assertEquals(false, verify.ownerInfo("ambulance", "amb3", z)["docsMissing"])
        val panel = verify.panelInfo("ambulance", "amb3", z + ("docsRequestedAt" to 5L))
        assertEquals(6, (panel["checks"] as List<*>).size)
        assertEquals(5L, panel["docsRequestedAt"])
        assertNull(panel["verification"])

        verify.delete("ambulance", "amb3")
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM verify_docs", Int::class.java))
        verify.save("bn", "badge", "own3", "own3", mapOf("type" to "nurse"), mapper.valueToTree(mapOf("cert" to jpeg)), true)
        verify.deleteForOwner("own3")
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM verify_docs", Int::class.java))
    }

    // ------------------------------------------------------------------ stats

    @Test
    fun `call stats - daily counts, callers, ten recent ratings without who`() {
        val day = 86_400_000L
        val now = System.currentTimeMillis()
        stats.bumpCall("ambulance", "a9", now, true)
        stats.bumpCall("ambulance", "a9", now, false)
        stats.bumpCall("ambulance", "a9", now, true)
        stats.bumpCall("ambulance", "a9", now - day, false)
        stats.bumpCall("ambulance", "a9", now - 40 * day, false)
        val s = stats.summary("ambulance", "a9", now)
        @Suppress("UNCHECKED_CAST")
        val calls = s["calls"] as Map<String, Int>
        assertEquals(listOf("7", "30", "90", "total"), calls.keys.toList())
        assertEquals(listOf(4, 4, 5, 5), calls.values.toList())
        assertEquals(2, s["callers"])
        val daily = s["daily"] as List<*>
        assertEquals(30, daily.size)
        assertEquals(3, daily[29])
        assertEquals(1, daily[28])
        assertEquals(0, (stats.summary("pharmacy", "nothing", now)["calls"] as Map<*, *>)["total"])

        (1..11).forEach { stats.addRecentRating("home", "p1", "u$it", (it % 5) + 1, "c$it") }
        var recent = stats.recentComments("home", "p1")
        assertEquals(10, recent.size)
        assertEquals("c11", recent.first()["comment"])
        assertFalse(recent.any { it["comment"] == "c1" })
        assertEquals(listOf("rating", "comment", "at"), recent.first().keys.toList())
        stats.addRecentRating("home", "p1", "u5", 1, "changed")
        recent = stats.recentComments("home", "p1")
        assertEquals(10, recent.size)
        assertEquals("changed", recent.first()["comment"])
        assertEquals(1, recent.count { it["comment"] == "changed" || it["comment"] == "c5" })
        stats.removeRecentRating("home", "p1", "u5")
        assertEquals(9, stats.recentComments("home", "p1").size)
        stats.delete("home", "p1")
        assertEquals(0, stats.recentComments("home", "p1").size)
    }

    // ------------------------------------------------------------------ catalog

    @Test
    fun `catalog - defaults, saved order wins, hidden codes still count`() {
        val types = catalog.codes("ambulanceType")
        assertTrue(types.isNotEmpty())
        assertEquals(0L, catalog.updatedAt())
        val first = catalog.lists().getValue("ambulanceType").getValue("all").first()
        catalog.save(
            "ambulanceType", "all",
            listOf(
                mapOf("code" to "c_boat", "en" to "Boat", "bn" to "নৌকা"),
                mapOf("code" to first["code"], "en" to first["en"], "bn" to first["bn"], "hidden" to true),
            ),
        )
        val list = catalog.lists().getValue("ambulanceType").getValue("all")
        assertEquals("c_boat", list[0]["code"])
        assertEquals(true, list[1]["hidden"])
        assertFalse(list[0].containsKey("hidden"))
        assertEquals(types.size + 1, list.size)
        assertTrue(first["code"] in catalog.codes("ambulanceType"))
        assertTrue(catalog.updatedAt() > 0)
        assertEquals(catalog.lists().getValue("specialty").getValue("doctor").size, catalog.codes("specialty", "doctor").size)
    }
}
