package com.lifolo.unit

import com.lifolo.admin.kernel.Catalog
import com.lifolo.admin.kernel.DemoSeed
import com.lifolo.admin.kernel.ListingRules
import com.lifolo.admin.kernel.ListingStats
import com.lifolo.admin.kernel.Moderation
import com.lifolo.admin.kernel.Sanctions
import com.lifolo.admin.kernel.VerifySpec
import com.lifolo.common.Phones
import com.lifolo.contracts.Chamber
import com.lifolo.contracts.ChamberTime
import com.lifolo.contracts.Chambers
import com.lifolo.contracts.Prescriptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Pure kernel rules. Cases are ported from server/test (gaps, e2efixes, ambulance-near); hash/time values were
 * produced by running the Node code (care.js, sanctions.js key, sellerstats.js raterKey, demo near).
 */
class KernelRulesTest {
    @Test
    fun `small name edits skip review, big ones do not (gaps)`() {
        assertTrue(ListingRules.smallNameEdit("Rahim Ambulance", "Rahim Ambulence"))
        assertTrue(ListingRules.smallNameEdit("Rahim Ambulance", "rahim  ambulance"))
        assertTrue(ListingRules.smallNameEdit("রহিম অ্যাম্বুলেন্স", "রহিম এম্বুলেন্স"))
        assertFalse(ListingRules.smallNameEdit("Rahim Ambulance", "Karim Transport"))
        assertFalse(ListingRules.smallNameEdit("Rahim", "Rahim Brothers"))
        assertFalse(ListingRules.smallNameEdit(null, "Rahim"))
    }

    @Test
    fun `vehicle and licence numbers normalise so spelling tricks do not dodge a ban (gaps)`() {
        assertEquals(Phones.normId("DHAKA METRO-CHA 11-2233"), Phones.normId("Dhaka Metro Cha 112233"))
        assertEquals(Phones.normId("ঢাকা মেট্রো-চ ১১-২২৩৩"), Phones.normId("ঢাকামেট্রোচ 11 2233"))
    }

    @Test
    fun `review needed - name typo on an approved listing keeps it listed (gaps)`() {
        val s = mapOf("status" to "approved", "name" to "Rahim Ambulance", "phone" to "01711000000", "vehicleNo" to "X-1")
        val keys = listOf("phone", "vehicleNo")
        assertFalse(ListingRules.reviewNeeded(s, mapOf("name" to "Rahim Ambulence", "phone" to "01711000000", "vehicleNo" to "X-1"), keys).changed)
        assertTrue(ListingRules.reviewNeeded(s, mapOf("name" to "Rahim Ambulance", "phone" to "01811000000", "vehicleNo" to "X-1"), keys).changed)
        // step-by-step renaming is compared with the last approved name
        val s2 = mapOf("status" to "approved", "approvedName" to "Rahim Ambulance", "name" to "Rahim Ambulxx", "phone" to "01711000000", "vehicleNo" to "X-1")
        assertTrue(ListingRules.reviewNeeded(s2, mapOf("name" to "Rahim Ambxxxx", "phone" to "01711000000", "vehicleNo" to "X-1"), keys).changed)
        // rejected listings always go back to review; approvedName falls back to the name of an approved listing
        assertTrue(ListingRules.reviewNeeded(s + ("status" to "rejected"), s, keys).changed)
        assertEquals("Rahim Ambulance", ListingRules.reviewNeeded(s, s, keys).approvedName)
        // Node quirk kept: a key missing on both sides compares '' with null → changed
        assertTrue(ListingRules.reviewNeeded(s, s + ("facilityId" to null), listOf("facilityId")).changed)
    }

    @Test
    fun `keepApproved - the approved snapshot follows small edits, stays during review`() {
        val cur = mapOf("status" to "approved", "name" to "A", "phone" to "1", "lat" to 24.1)
        assertEquals(mapOf("name" to "A", "phone" to "2"), ListingRules.keepApproved(cur, mapOf("phone" to "2"), false))
        assertEquals(mapOf("name" to "A", "phone" to "1"), ListingRules.keepApproved(cur, mapOf("phone" to "2"), true))
        assertNull(ListingRules.keepApproved(cur + ("lastApproved" to mapOf("name" to "A")), mapOf("phone" to "2"), true))
        assertNull(ListingRules.keepApproved(cur + ("status" to "pending"), mapOf("phone" to "2"), false))
    }

    @Test
    fun `changedFields ignores ratings, online settings, demo marker, location and reminders (e2efixes, ambulance-near)`() {
        val base = mapOf("name" to "দোকান", "phone" to "01711000000", "licenseNo" to "DL-1", "status" to "approved")
        val x = base + mapOf(
            "lastApproved" to ListingRules.snapshot(base),
            "aspects" to mapOf("behaviour" to mapOf("sum" to 9, "count" to 2)),
            "online" to mapOf("enabled" to true, "fee" to 30),
            "demo" to true,
        )
        assertEquals(emptyList<Any>(), ListingRules.changedFields(x))
        assertEquals(listOf("phone"), ListingRules.changedFields(x + ("phone" to "01811000000")).map { it.field })

        val a = mapOf(
            "name" to "ক", "phone" to "01711000000", "lat" to 24.75, "lng" to 90.42, "remindAt" to 5, "reminderSentAt" to 4,
            "lastApproved" to mapOf("name" to "ক", "phone" to "01711000000", "lat" to 24.783, "lng" to 90.407),
        )
        assertEquals(emptyList<Any>(), ListingRules.changedFields(a))
        assertEquals(listOf("phone"), ListingRules.changedFields(a + ("phone" to "01811000000")).map { it.field })
        assertEquals(listOf("name", "phone"), ListingRules.snapshot(a).keys.sorted())
        // numbers compare like JSON.stringify (1 == 1.0), null == missing
        val n = mapOf("upazilaId" to 12, "area" to null, "lastApproved" to mapOf("upazilaId" to 12.0))
        assertEquals(emptyList<Any>(), ListingRules.changedFields(n))
        val c = ListingRules.changedFields(n + ("upazilaId" to 13)).single()
        assertEquals(ListingRules.Change("upazilaId", 12.0, 13), c)
    }

    @Test
    fun `care helpers str, pickCodes, pickClosedDays (values from Node)`() {
        assertEquals("ab", ListingRules.str("  abc ", 2))
        assertEquals("", ListingRules.str(5, 10))
        assertEquals("x", ListingRules.str(" x﻿", 10))
        assertEquals(listOf("a", "b"), ListingRules.pickCodes(listOf("a", "x", "a", "b"), listOf("a", "b"), 5))
        assertEquals(emptyList<String>(), ListingRules.pickCodes(null, listOf("a"), 5))
        assertEquals(listOf(0, 1, 2, 3, 4, 5), ListingRules.pickClosedDays(listOf(3, 1, 1, 9, "2", 0, 2, 4, 5, 6)))
        assertEquals(listOf(1, 5), ListingRules.pickClosedDays(listOf(5, 1, 1.5, -1)))
    }

    @Test
    fun `opening hours like pharmacyOpenNow (values from Node)`() {
        val t = Instant.parse("2026-10-19T17:30:00Z").toEpochMilli() // Monday 23:30 in Dhaka
        assertEquals("23:30", ListingRules.dhakaHHMM(t))
        assertEquals(1, ListingRules.dhakaWeekday(t))
        assertTrue(ListingRules.openNow(mapOf("openAt" to "20:00", "closeAt" to "02:00"), t))
        // 00:30 on Tuesday belongs to Monday's shift — Monday is the weekly closed day
        assertFalse(ListingRules.openNow(mapOf("openAt" to "20:00", "closeAt" to "02:00", "closedDays" to listOf(1)), t + 3_600_000))
        assertFalse(ListingRules.openNow(mapOf("openAt" to "09:00", "closeAt" to "17:00"), t))
        assertTrue(ListingRules.openNow(mapOf("open24" to true), t))
        assertFalse(ListingRules.openNow(mapOf("open24" to true, "closedDays" to listOf(1)), t))
        assertFalse(ListingRules.openNow(mapOf("open24" to true, "closedUntil" to t + 1), t))
        assertTrue(ListingRules.openNow(mapOf("openAt" to "bad"), t))
    }

    @Test
    fun `sanction keys are the Node hashes`() {
        assertEquals(
            listOf("8a5a412f5e8e1e847168aea6e64ce5b397991b03"),
            Sanctions.listingKeys("ambulance", mapOf("vehicleNo" to "ঢাকা মেট্রো-ছ ১১-২২৩৩", "phone" to "01711000000")),
        )
        assertEquals(
            listOf("28d2529998f6053ee61da81393dfe6120eb85c43", "0b337c770f59ac6cb8f00bf60faf9b98686b2f4f"),
            Sanctions.listingKeys("pharmacy", mapOf("licenseNo" to "DL-1234", "phone" to "01711000000")),
        )
        assertEquals(listOf("5c28988bb8d85002d048b6888f4fe300458b1e43"), Sanctions.listingKeys("bloodbank", mapOf("phone" to "02-9661064")))
        assertEquals("cf55e975a35d50932890b26e6b352b0031d0349f", Sanctions.emailKey(" Owner@Example.com "))
        assertNull(Sanctions.emailKey("  "))
        // too short to identify anything
        assertEquals(emptyList<String>(), Sanctions.listingKeys("ambulance", mapOf("vehicleNo" to "১২৩")))
    }

    @Test
    fun `rater key and moderation paths are the Node ones`() {
        assertEquals("b7b995bb4505f023", ListingStats.raterKey("ambulance", "amb1", "u1"))
        assertEquals("questions/q1/answers/a1", Moderation.targetPath("answer", "q1", "a1"))
        assertEquals("providers/p1", Moderation.targetPath("homecare", "p1"))
        assertEquals("bloodBanks/b1", Moderation.targetPath("bloodbank", "b1"))
        assertEquals("tips/t1", Moderation.targetPath("tip", "t1"))
    }

    @Test
    fun `verify spec - document lists depend on the listing, photos must be JPEG or PNG`() {
        assertTrue(VerifySpec.docs("bloodbank", mapOf("kind" to "private")).first { it.type == "license" }.required)
        assertFalse(VerifySpec.docs("bloodbank", mapOf("kind" to "govt")).first { it.type == "license" }.required)
        assertEquals(listOf("cert", "idcard"), VerifySpec.docs("badge", mapOf("type" to "nurse")).map { it.type })
        assertEquals(7, VerifySpec.checks("bloodbank", mapOf("kind" to "private")).size)
        assertEquals(4, VerifySpec.checks("bloodbank", mapOf("kind" to "govt")).size)
        val phone = VerifySpec.checks("bloodbank", mapOf("kind" to "govt")).last()
        assertEquals("phone", phone.id)
        assertEquals(listOf("id", "label", "format"), phone.toMap().keys.toList())
        assertEquals(listOf("id", "label", "link", "format"), VerifySpec.checks("badge", mapOf("type" to "doctor"))[0].toMap().keys.toList())

        assertEquals("/9j/4AAQ", VerifySpec.cleanB64("data:image/jpeg;base64,/9j/4A AQ"))
        assertEquals("iVBORw0KGgo=", VerifySpec.cleanB64("iVBORw0KGgo="))
        assertNull(VerifySpec.cleanB64("R0lGODlh")) // GIF
        assertNull(VerifySpec.cleanB64("not base64!"))
        assertNull(VerifySpec.cleanB64(42))
        assertNull(VerifySpec.cleanB64("/9j/" + "A".repeat(VerifySpec.MAX_B64)))
        assertEquals(setOf("a", "b"), VerifySpec.haveTypes(mapOf("docsHave" to mapOf("a" to 1, "b" to 2))))
        assertEquals(setOf("a"), VerifySpec.haveTypes(mapOf("docsHave" to listOf("a"))))
    }

    @Test
    fun `catalog codes, demo places, prescription codes, chamber days`() {
        assertEquals("c_heart_lungs", Catalog.makeCode(" Heart & Lungs! ", emptySet()))
        assertEquals("c_item_2", Catalog.makeCode("দাঁত", setOf("c_item")))

        val p = DemoSeed.near(5.5, 22.0)
        assertEquals(24.1972, p.lat)
        assertEquals(90.47424, p.lng)
        assertEquals(90.38055, DemoSeed.near(-4.0, 0.0).lng)
        assertEquals(23.89089, DemoSeed.near(-1.5, -12.0).lat)

        assertEquals("ABCDEFGH23", Prescriptions.normalizeCode(" abcd-efgh 23 "))
        assertTrue(Prescriptions.CODE_RE.matches("ABCDEFGH23"))
        assertFalse(Prescriptions.CODE_RE.matches("ABCDEFGHI1"))

        val chambers = object : Chambers {
            override fun chambersOf(providerUid: String) = emptyList<Chamber>()
            override fun takesAppointments(providerUid: String) = false
        }
        val monday = Instant.parse("2026-10-19T00:00:00Z").toEpochMilli()
        fun ch(vararg times: ChamberTime) = Chamber(0, null, "H", "", "", null, null, "", "", false, "", true, "", times.toList())
        assertTrue(chambers.chamberOpenOn(ch(ChamberTime(listOf(1, 3), "17:00", "21:00")), monday))
        assertFalse(chambers.chamberOpenOn(ch(ChamberTime(listOf(0, 2), "17:00", "21:00")), monday))
        assertTrue(chambers.chamberOpenOn(ch(ChamberTime(emptyList(), "", "")), monday))
        assertTrue(chambers.chamberOpenOn(ch(), monday))
        assertFalse(chambers.chamberOpenOn(null, monday))
    }
}
