package com.lifolo.unit

import com.fasterxml.jackson.databind.ObjectMapper
import com.lifolo.admin.AdminLog
import com.lifolo.auth.EmailCodes
import com.lifolo.common.ApiErrorHandler
import com.lifolo.common.Body
import com.lifolo.common.ClientIp
import com.lifolo.common.DatabaseUrlEnvironmentPostProcessor
import com.lifolo.common.DhakaTime
import com.lifolo.common.Geo
import com.lifolo.common.I18n
import com.lifolo.common.Js
import com.lifolo.common.LifoloProperties
import com.lifolo.common.Rules
import com.lifolo.demo.DemoWorld
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Pure helpers; expected values were produced by the Node code (geofire-common, rules.js, email-code.js). */
class CommonUnitTest {
    private val mapper = ObjectMapper()

    @Test
    fun `geohash matches geofire-common`() {
        assertEquals("wh2kc3de1w", Geo.hash(24.75, 90.41))
        assertEquals("wh2298cu69", Geo.hash(23.999, 90.42))
        assertEquals("r3gx2f77bn", Geo.hash(-33.8688, 151.2093))
        // geofire uses `val > mid`, so (0,0) is "7zzz…" (standard geohash would say "s000…")
        assertEquals("7zzzzzzzzz", Geo.hash(0.0, 0.0))
        assertEquals("bpbp6mbc6n", Geo.hash(89.9, -179.9))
        assertEquals("wh2kc2bx2k", Geo.hash(24.7466, 90.4072))
        assertNull(Geo.hash(null, 90.0))
        assertNull(Geo.hash(Double.NaN, 90.0))
    }

    @Test
    fun `donor key needs a real group and a geohash`() {
        assertEquals("O+|wh2kc3de1w", Geo.donorKey("O+", "wh2kc3de1w"))
        assertNull(Geo.donorKey("", "wh2kc3de1w"))
        assertNull(Geo.donorKey("unknown", "wh2kc3de1w"))
        assertNull(Geo.donorKey("O+", null))
    }

    @Test
    fun `distance like geofire`() {
        val d = Geo.distanceKm(24.75, 90.40, 23.81, 90.41)
        assertTrue(d > 104 && d < 105, "$d")
    }

    @Test
    fun `dhaka day keys`() {
        // Date.UTC(2026, 9, 18, 18, 30) is already the 19th in Dhaka
        assertEquals("20261019", DhakaTime.dayKey(1792348200000))
        assertEquals("20261018", DhakaTime.dayKey(1792346340000))
        assertEquals(1792368000000, DhakaTime.todayUtcMidnight(1792348200000))
    }

    @Test
    fun `long dates like toLocaleDateString`() {
        assertEquals("১৮ নভেম্বর, ২০২৬", DhakaTime.longDate(1795000000000, english = false))
        assertEquals("18 November 2026", DhakaTime.longDate(1795000000000, english = true))
    }

    @Test
    fun `ids`() {
        assertTrue(Rules.isValidId("abc_DEF-123"))
        assertFalse(Rules.isValidId("bad/id"))
        assertFalse(Rules.isValidId(""))
        assertFalse(Rules.isValidId("x".repeat(65)))
        assertFalse(Rules.isValidId(null))
    }

    @Test
    fun `cleanText like community js`() {
        assertEquals("a\n\nb", Rules.cleanText("  a\r\n\n\n\nb  ", 10))
        assertNull(Rules.cleanText("   ", 10))
        assertNull(Rules.cleanText("x".repeat(11), 10))
        assertNull(Rules.cleanText(5, 10))
    }

    @Test
    fun `i18n exact and patterns`() {
        assertEquals("Please log in", I18n.toEnglish("লগইন দরকার"))
        assertEquals("You can notify again in 3 hour(s)", I18n.toEnglish("3 ঘণ্টা পর আবার জানাতে পারবেন"))
        assertEquals("Your group (B+) can't donate to this patient", I18n.toEnglish("আপনার গ্রুপ (B+) এই রোগীকে দেওয়া যায় না"))
        assertEquals("অচেনা বার্তা", I18n.toEnglish("অচেনা বার্তা"))
    }

    @Test
    fun `error envelope like firebase js handler`() {
        assertEquals(mapOf("error" to "লগইন দরকার"), ApiErrorHandler.body(401, "লগইন দরকার", null, "bn"))
        assertEquals(mapOf("error" to "Please log in"), ApiErrorHandler.body(401, "লগইন দরকার", null, "en"))
        assertEquals(mapOf("error" to "x", "code" to "account_disabled"), ApiErrorHandler.body(403, "x", "account_disabled", null))
        // codes must be [a-z_]+ and never on 500
        assertEquals(mapOf("error" to "x"), ApiErrorHandler.body(403, "x", "Bad-Code", null))
        assertEquals(mapOf("error" to "Server error, please try again"), ApiErrorHandler.body(500, "boom", "x", "en"))
        assertEquals(mapOf("error" to "সার্ভারে সমস্যা হয়েছে, আবার চেষ্টা করুন"), ApiErrorHandler.body(500, "boom", null, null))
    }

    @Test
    fun `javascript value semantics`() {
        val b = Body.of(mapper.readTree("""{"a":5,"b":5.0,"c":5.5,"d":"x","e":true,"f":null,"action":7}"""))
        assertEquals(5L, b.int("a"))
        assertEquals(5L, b.int("b"))
        assertNull(b.int("c"))
        assertNull(b.int("d"))
        assertEquals("x", b.string("d"))
        assertNull(b.string("a"))
        assertTrue(b.isTrue("e"))
        assertTrue(b.isNull("f"))
        assertTrue(b.isNullish("missing"))
        assertEquals("7", b.action)
        assertEquals("undefined", Js.template(b["missing"]))
        assertEquals("null", Js.template(b["f"]))
        assertEquals(24.75, Js.round(24.75432 * 100) / 100)
        assertEquals(-1.0, Js.round(-1.5))
    }

    @Test
    fun `email codes like email-code js`() {
        assertEquals("parvez@gmail.com", EmailCodes.normalizeEmail("  Parvez@Gmail.com "))
        assertNull(EmailCodes.normalizeEmail("not-an-email"))
        assertNull(EmailCodes.normalizeEmail("a@b"))
        assertNull(EmailCodes.normalizeEmail(""))
        assertNull(EmailCodes.normalizeEmail("x<a@evil.com>.co"))
        assertNull(EmailCodes.normalizeEmail("রক্ত@gmail.com"))
        assertEquals("a+tag@sub.example.co.uk", EmailCodes.normalizeEmail("a+tag@sub.example.co.uk"))
        assertEquals("8058686fbbf0ad842561a0e7ffb1b1c27476bcd772dcb922d786281da18735b7", EmailCodes.docIdFor("a@b.com"))

        val codes = EmailCodes(LifoloProperties(emailCodeSecret = "test-secret-key"), mapper)
        repeat(50) { assertTrue(Regex("^\\d{6}$").matches(codes.newCode())) }
        val h = codes.hashCode("a@b.com", "123456")
        // same HMAC as Node with FIREBASE_SERVICE_ACCOUNT = {"private_key":"test-secret-key"}
        assertEquals("5e880144af5469a45b9bc22fd89e08ffcaeeee6e97a7d5f304d811c406c1fdf1", h)
        assertTrue(EmailCodes.codesMatch(h, codes.hashCode("a@b.com", "123456")))
        assertFalse(EmailCodes.codesMatch(h, codes.hashCode("a@b.com", "123457")))
        assertFalse(EmailCodes.codesMatch(h, codes.hashCode("x@b.com", "123456")))

        // the secret can also come from the service account JSON, like Node
        val fromSa = EmailCodes(LifoloProperties(firebaseServiceAccount = """{"private_key":"test-secret-key"}"""), mapper)
        assertEquals(h, fromSa.hashCode("a@b.com", "123456"))
    }

    @Test
    fun `IPv6 addresses count by their first 4 groups`() {
        assertEquals("2001:db8:85a3:1", ClientIp.key("2001:db8:85a3:1:aaaa:bbbb:cccc:dddd"))
        assertEquals("103.4.5.6", ClientIp.key("103.4.5.6"))
    }

    @Test
    fun `demo world separation`() {
        assertTrue(DemoWorld.sameWorld("demo-a", "demo-b"))
        assertTrue(DemoWorld.sameWorld("real1", "real2"))
        assertFalse(DemoWorld.sameWorld("demo-a", "real"))
        assertEquals("doctor@lifolo-demo.test", DemoWorld.demoEmail("doctor"))
        assertFalse(DemoWorld.ROLES.any { it.key == "admin" })
    }

    @Test
    fun `DATABASE_URL is converted to JDBC`() {
        val p = DatabaseUrlEnvironmentPostProcessor.toDatasourceProperties("postgres://lifolo:p%40ss@127.0.0.1:5433/lifolo?sslmode=disable")!!
        assertEquals("jdbc:postgresql://127.0.0.1:5433/lifolo?sslmode=disable", p["spring.datasource.url"])
        assertEquals("lifolo", p["spring.datasource.username"])
        assertEquals("p@ss", p["spring.datasource.password"])
        assertEquals(5432, DatabaseUrlEnvironmentPostProcessor.toDatasourceProperties("postgresql://h/db")!!["spring.datasource.url"].toString().let { if (it.contains(":5432/")) 5432 else 0 })
        assertNull(DatabaseUrlEnvironmentPostProcessor.toDatasourceProperties("mysql://h/db"))
    }

    @Test
    fun `admin log targets like adminweb js`() {
        fun log(action: String, json: String) = AdminLog.LOGGED[action]!!(Body.of(mapper.readTree(json)))
        assertEquals("pharmacies/p1" to "approved", log("admin_pharmacy_resolve", """{"id":"p1","approve":true}"""))
        assertEquals("ambulances/a1" to "suspended · fake", log("admin_ambulance_resolve", """{"id":"a1","status":"suspended","reason":"fake"}"""))
        assertEquals("badges/undefined" to "rejected", log("admin_badge_resolve", "{}"))
        assertEquals("config/admins" to "add x@y.com", log("admin_add", """{"email":"x@y.com"}"""))
        assertEquals("tickets/t1" to "replied+closed", log("admin_ticket_reply", """{"id":"t1","close":true,"text":"ok"}"""))
        assertEquals("config/catalog/specialty/doctor" to "2 items", log("admin_catalog_set", """{"kind":"specialty","type":"doctor","items":[1,2]}"""))
        assertEquals("users/u1" to "disable · spam", log("admin_user_action", """{"uid":"u1","op":"disable","reason":"spam"}"""))
    }
}
