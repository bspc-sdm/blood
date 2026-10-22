package com.lifolo.security

import com.lifolo.auth.WebSession
import com.lifolo.common.Places
import com.lifolo.common.action.ActionPolicy
import com.lifolo.common.action.ActionRegistry
import com.lifolo.support.IntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import kotlin.random.Random

/**
 * Authorization sweep over every registered `/api/community` action (the registry, so a new action is covered the
 * day it is added): who may call it at all.
 *  - guest (no token): only the public actions; everything else 401 — and the public ones never write a row
 *  - a normal user: every `admin_*` → 403, nothing logged; a non-owner admin: the owner-only ones → 403
 *  - the admin web-panel cookie: only `admin_*` + the panel reads; no CSRF header / forged / non-admin cookie → refused
 *  - a caregiver (`asUid`): only the CARE actions, and only with an active link
 *  - robustness: every action with malformed bodies (wrong types, huge numbers, injection strings, long text) as a plain
 *    user, as a user with every professional role and (admin_*) as the owner never answers 5xx or leaks a stack trace
 */
class AuthzSweepIT : IntegrationTest() {
    @Autowired
    lateinit var registry: ActionRegistry

    @Autowired
    lateinit var webSession: WebSession

    private val dist: String get() = Places.DISTRICTS.first { it.en == "Mymensingh" }.bn

    private fun all(): List<String> = registry.names().sorted()

    /** row counts of every application table (Flyway/JobRunr bookkeeping left out) */
    private fun counts(): Map<String, Long> {
        val tables = jdbc.queryForList(
            "SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename NOT LIKE 'flyway%' AND tablename NOT LIKE 'jobrunr%' ORDER BY 1",
            String::class.java,
        )
        return tables.associateWith { t -> jdbc.queryForObject("SELECT count(*) FROM \"$t\"", Long::class.java) ?: 0L }
    }

    // ------------------------------------------------------------------ guest

    @Test
    fun `guest - every non-public action is 401 and changes nothing`() {
        user("victim1", mapOf("name" to "ভিকটিম", "phone" to "01711000001"))
        val before = counts()
        val wrong = ArrayList<String>()
        for (a in all()) {
            if (ActionPolicy.isPublic(a)) continue
            for (body in listOf(emptyMap(), mapOf("id" to "victim1", "uid" to "victim1", "asUid" to "victim1"))) {
                val r = call(null, a, body)
                if (r.status != 401) wrong += "$a → $r"
            }
        }
        assertEquals(emptyList<String>(), wrong)
        assertEquals(before, counts())
        // the legacy blood endpoints and the admin ones outside /api/community
        for (p in listOf("/api/notify-request", "/api/respond", "/api/close-request", "/api/edit-request", "/api/admin/corrections", "/api/admin/resolve")) {
            assertEquals(401, post(p, mapOf("requestId" to "x")).status, p)
        }
        assertEquals(401, get("/api/events").status)
    }

    @Test
    fun `guest - the public actions are reads (no row written whatever the body)`() {
        val before = counts()
        val bodies: List<Map<String, Any?>> = listOf<Map<String, Any?>>(
            emptyMap(),
            mapOf("district" to dist, "type" to "doctor", "lat" to 24.75, "lng" to 90.4, "q" to "napa", "id" to "x1", "labId" to "x1"),
        ) + junkBodies("guest", 6)
        val wrong = ArrayList<String>()
        for ((i, a) in ActionPolicy.PUBLIC_ACTIONS.sorted().withIndex()) {
            for (b in bodies) {
                val r = call(null, a, b, ip = "10.0.${i / 200}.${i % 200}")
                if (r.status >= 500 || r.status == 401) wrong += "$a $b → $r"
            }
        }
        assertEquals(emptyList<String>(), wrong)
        assertEquals(before, counts())
    }

    // ------------------------------------------------------------------ admin gates

    @Test
    fun `a normal user gets 403 for every admin action and nothing is logged`() {
        user("plain1", mapOf("name" to "সাধারণ"))
        val admin = all().filter { it.startsWith("admin_") }
        assertTrue(admin.size >= 40, "admin actions: $admin")
        val wrong = ArrayList<String>()
        for (a in admin) {
            for (body in listOf(emptyMap(), mapOf("id" to "x1", "uid" to "plain1", "status" to "approved", "email" to "plain1@example.com", "step" to 0))) {
                val r = call("plain1", a, body)
                if (r.status != 403) wrong += "$a → $r"
            }
        }
        assertEquals(emptyList<String>(), wrong)
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM admin_log", Long::class.java))
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM admin_emails", Long::class.java))
        // an unverified email that matches an owner is not an owner
        identity.add("fakeowner", "owner@example.com", emailVerified = false)
        assertEquals(403, call("fakeowner", "admin_overview").status)
        assertEquals(403, call("fakeowner", "admin_list").status)
        // the HTTP admin endpoints
        assertEquals(403, post("/api/admin/corrections", emptyMap<String, Any>(), mapOf("Authorization" to "Bearer plain1")).status)
        assertEquals(403, post("/api/admin/resolve", mapOf("id" to "x", "approve" to true), mapOf("Authorization" to "Bearer plain1")).status)
    }

    @Test
    fun `an extra admin is not an owner`() {
        user("owner1", emptyMap(), email = "owner@example.com")
        user("adm2", emptyMap(), email = "adm2@example.com")
        jdbc.update("INSERT INTO admin_emails (email) VALUES ('adm2@example.com')")
        assertEquals(200, call("adm2", "admin_overview").status, "extra admins are admins")
        for (a in listOf("admin_list", "admin_add", "admin_remove", "admin_demo_seed", "admin_demo_wipe", "admin_demo_status")) {
            val r = call("adm2", a, mapOf("email" to "x@example.com", "step" to 0))
            assertEquals(403, r.status, "$a: $r")
        }
        // nor may an admin act on the owner's account or on another admin
        user("adm3", emptyMap(), email = "adm3@example.com")
        jdbc.update("INSERT INTO admin_emails (email) VALUES ('adm3@example.com')")
        redis.connectionFactory?.connection?.use { it.serverCommands().flushAll() }
        for (target in listOf("owner1", "adm3")) {
            val r = call("adm2", "admin_user_action", mapOf("uid" to target, "op" to "provider_suspend", "reason" to "x"))
            assertEquals(403, r.status, "$target: $r")
        }
        assertEquals(false, identity.users["owner1"]!!.disabled)
        assertEquals(200, call("owner1", "admin_list").status)
    }

    // ------------------------------------------------------------------ web panel cookie

    private fun cookie(uid: String, email: String): String {
        val now = System.currentTimeMillis()
        return "${WebSession.COOKIE}=${webSession.encode(WebSession.Session(uid, email, now, now))}"
    }

    private fun web(cookie: String, action: String, body: Map<String, Any?> = emptyMap(), csrf: Boolean = true): Res =
        post(
            "/api/community",
            linkedMapOf<String, Any?>("lang" to "en", "action" to action) + body,
            if (csrf) mapOf("Cookie" to cookie, WebSession.CSRF_HEADER to "1") else mapOf("Cookie" to cookie),
        )

    @Test
    fun `web panel cookie - admin actions only, CSRF header required, forged and non-admin cookies refused`() {
        user("owner1", emptyMap(), email = "owner@example.com")
        user("plain2", emptyMap(), email = "plain2@example.com")
        val c = cookie("owner1", "owner@example.com")
        assertEquals(200, web(c, "admin_overview").status)
        for (a in ActionPolicy.WEB_PANEL_READS) assertEquals(200, web(c, a).status, a)
        val wrong = ArrayList<String>()
        for (a in all()) {
            if (a.startsWith("admin_") || a in ActionPolicy.WEB_PANEL_READS || a == "web_logout") continue
            val r = web(c, a)
            if (r.status != 403) wrong += "$a → $r"
        }
        assertEquals(emptyList<String>(), wrong)
        // no CSRF header: refused before anything runs
        val noCsrf = web(c, "admin_overview", csrf = false)
        assertEquals(401, noCsrf.status)
        assertEquals("session_expired", noCsrf.code)
        // forged signature / tampered payload
        val forged = c.dropLast(3) + "abc"
        assertEquals(401, web(forged, "admin_overview").status)
        val other = cookie("plain2", "plain2@example.com")
        val tampered = other.substringBefore('.') + "." + c.substringAfter('.')
        assertEquals(401, web(tampered, "admin_overview").status)
        // a correctly signed cookie of someone who is not an admin: 403 and the cookie is cleared
        val notAdmin = web(other, "admin_overview")
        assertEquals(403, notAdmin.status)
        assertTrue(notAdmin.response.getHeader("Set-Cookie")!!.contains("Max-Age=0"))
        // an expired session (idle > 2 h)
        val old = System.currentTimeMillis() - 3 * 3600 * 1000L
        val expired = "${WebSession.COOKIE}=${webSession.encode(WebSession.Session("owner1", "owner@example.com", old, old))}"
        assertEquals(401, web(expired, "admin_overview").status)
        // a disabled admin's cookie does not work (and is cleared)
        user("adm9", emptyMap(), email = "adm9@example.com")
        jdbc.update("INSERT INTO admin_emails (email) VALUES ('adm9@example.com')")
        redis.connectionFactory?.connection?.use { it.serverCommands().flushAll() }
        identity.users["adm9"]!!.disabled = true
        val disabled = web(cookie("adm9", "adm9@example.com"), "admin_stats")
        assertEquals(403, disabled.status, disabled.toString())
        assertTrue(disabled.response.getHeader("Set-Cookie")!!.contains("Max-Age=0"))
        // the same on the corrections endpoints (outside /api/community)
        val panel = mapOf("Cookie" to c, WebSession.CSRF_HEADER to "1")
        assertEquals(200, post("/api/admin/corrections", emptyMap<String, Any>(), panel).status)
        assertEquals(401, post("/api/admin/corrections", emptyMap<String, Any>(), mapOf("Cookie" to c)).status)
        val off = mapOf("Cookie" to cookie("adm9", "adm9@example.com"), WebSession.CSRF_HEADER to "1")
        assertEquals(403, post("/api/admin/corrections", emptyMap<String, Any>(), off).status)
        assertEquals(403, post("/api/admin/corrections", emptyMap<String, Any>(), mapOf("Cookie" to other, WebSession.CSRF_HEADER to "1")).status)
    }

    @Test
    fun `admin session cookie flags - HttpOnly, Secure, SameSite Strict, host-only`() {
        identity.add("owner1", "owner@example.com")
        val send = post("/api/auth/send-code", mapOf("email" to "owner@example.com"))
        assertEquals(200, send.status, send.toString())
        val code = mail.lastCode("owner@example.com")
        val r = post("/api/auth/verify-code", mapOf("email" to "owner@example.com", "code" to code, "web" to true, "rids" to listOf(send.body["rid"].asText())))
        assertEquals(200, r.status, r.toString())
        val sc = r.response.getHeader("Set-Cookie")!!
        assertTrue(sc.startsWith("__Host-lf_admin="), sc)
        for (flag in listOf("HttpOnly", "Secure", "SameSite=Strict", "Path=/")) assertTrue(sc.contains(flag), "$flag in $sc")
        assertTrue(!sc.contains("Domain="), sc)
        assertTrue(r.body["token"] == null, "no Firebase token for the web panel")
    }

    // ------------------------------------------------------------------ caregiver

    @Test
    fun `caregiver - only the CARE actions with asUid, only with an active link`() {
        user("pat1", mapOf("name" to "রোগী"))
        user("cg1", mapOf("name" to "কেয়ারগিভার"))
        user("cg2", mapOf("name" to "অন্য"))
        jdbc.update("INSERT INTO care_links (patient_uid, caretaker_uid, status) VALUES ('pat1', 'cg1', 'active')")
        jdbc.update("INSERT INTO care_links (patient_uid, caretaker_uid, status) VALUES ('pat1', 'cg2', 'invited')")
        val wrong = ArrayList<String>()
        for (a in all()) {
            val r = call("cg1", a, mapOf("asUid" to "pat1"))
            if (a in ActionPolicy.CARE_ACTIONS) {
                if (r.status == 403) wrong += "$a should be allowed for a caregiver → $r"
            } else if (r.status != 403) {
                wrong += "$a → $r"
            }
        }
        assertEquals(emptyList<String>(), wrong)
        // invited only, or no link at all: care_revoked
        for (a in ActionPolicy.CARE_ACTIONS.sorted()) {
            val invited = call("cg2", a, mapOf("asUid" to "pat1"))
            assertEquals(403, invited.status, "$a $invited")
            assertEquals("care_revoked", invited.code, a)
            assertEquals("care_revoked", call("pat1", a, mapOf("asUid" to "cg1")).code, "$a: the patient is not cg1's caregiver")
        }
        // a malformed asUid is refused too
        assertEquals(403, call("cg1", "rx_mine", mapOf("asUid" to "../pat1")).status)
        assertEquals(403, call("cg1", "rx_mine", mapOf("asUid" to listOf("pat1"))).status)
    }

    // ------------------------------------------------------------------ robustness

    private val keys = listOf(
        "accept", "active", "add", "address", "advice", "again", "ambulanceId", "anonymous", "answerId", "appointmentId", "appointments",
        "approve", "area", "aspects", "at", "available", "bags", "bagsReceived", "before", "billDisc", "body", "caretakerUid", "category",
        "chamber", "chamberIndex", "chamberKey", "chambers", "checks", "clearBase", "close", "closeAt", "closed", "closedDays", "code",
        "comment", "commitAdd", "commitRemove", "complaints", "confirmed", "contactPhone", "correction", "cost", "customer", "date",
        "degrees", "delivery", "deliveryNote", "dest", "diagnosis", "disc", "discount", "discountMin", "discountNote", "discountPct",
        "district", "docs", "doctorUid", "donated", "donatedUids", "donorUid", "email", "emails", "enabled", "eta", "expect", "expiry",
        "facilityId", "family", "fare", "fareChart", "fee", "female", "findings", "followUpDays", "followUpNote", "form",
        "freeDeliveryOver", "fresh", "gender", "generic", "geohash", "gps", "group", "groups", "have", "home", "homeCollection", "homeFee",
        "homeVisit", "hospital", "hospitalId", "hospitalName", "hours", "i", "id", "includeUnknown", "itemId", "items", "k", "kind",
        "labId", "lat", "licenseNo", "lines", "listed", "lng", "lowAt", "maxFare", "maxKm", "medicines", "memberId", "method", "millis",
        "minutes", "missing", "mode", "name", "nameBn", "nameEn", "neededAtMillis", "neededByMillis", "newRxId", "noFee", "note", "off",
        "ok", "on", "op", "open24", "openAt", "openNow", "overcharged", "oxygen", "panel", "path", "patient", "patientGroup", "patientUid",
        "payMethods", "payNumber", "period", "pharmacyId", "phone", "photos", "pickup", "placeId", "placeName", "price", "providerNote",
        "providerUid", "q", "qty", "radiusKm", "ranges", "rating", "reason", "regNo", "remove", "reportedUid", "reqId", "requestId",
        "requesterName", "requesterPhone", "rids", "rx", "rxId", "rxPatientId", "schedule", "section", "serial", "service", "services",
        "set", "source", "specialties", "specialty", "status", "step", "supplier", "targetId", "targetPath", "termsAccepted", "test",
        "testPlace", "testPlaceId", "tests", "text", "time", "title", "token", "travelFare", "trxId", "type", "types", "uid", "unionId",
        "unit", "until", "upazilaId", "url", "urgent", "vehicleNo", "workplace",
    )

    private val junk: List<Any?> = listOf(
        null, true, false, 0, -1, 1.5, 1e300, -1e300, 9007199254740993L, Long.MIN_VALUE, "", " ", "x", "zz-not-there-1",
        "' OR '1'='1' --", "\"; DROP TABLE accounts; --", "%_%", "../../etc/passwd", "a".repeat(5000), "০১৭১১০০০০০১", "\u0000‮",
        "2027-13-45", "-0", "NaN", "Infinity", listOf<Any>(), listOf(null), listOf(1, 2), listOf("x", "y"), listOf(emptyMap<String, Any>()),
        listOf(mapOf("i" to 1e300, "qty" to -1, "name" to 5, "itemId" to "x", "code" to "x", "key" to "x", "low" to "a", "result" to listOf(1))),
        emptyMap<String, Any>(), mapOf("type" to "pct", "v" to 1e300), mapOf("\$gt" to ""), mapOf("kind" to "district", "district" to 5),
    )

    /** [n] bodies: every key gets a random junk value (deterministic per [seed]) */
    private fun junkBodies(seed: String, n: Int): List<Map<String, Any?>> {
        val rnd = Random(seed.hashCode())
        val out = ArrayList<Map<String, Any?>>()
        // every key the same junk value (a few kinds), then mixed ones
        for (i in listOf(0, 1, 3, 4, 5, 6, 10, 13, 14, 18, 25, 29, 30, 31)) out += keys.associateWith { junk[i] }
        repeat(n) { out += keys.associateWith { junk[rnd.nextInt(junk.size)] } }
        return out
    }

    /** POST /api/community as [uid] from its own IP per action (the per-account rate limit counts per IP too) */
    private fun fuzzCall(uid: String, action: String, body: Map<String, Any?>, ipN: Int): Res {
        val payload = linkedMapOf<String, Any?>("lang" to "en", "action" to action)
        payload.putAll(body)
        return post("/api/community", payload, mapOf("Authorization" to "Bearer $uid", "X-Forwarded-For" to "10.9.${ipN / 250}.${ipN % 250}"))
    }

    private fun fuzz(uid: String, actions: List<String>, ipBase: Int): List<String> {
        val bad = ArrayList<String>()
        for ((i, a) in actions.withIndex()) {
            for (b in junkBodies(a, 8)) {
                val r = fuzzCall(uid, a, b, ipBase + i)
                val text = r.body.toString()
                if (r.status >= 500 || text.contains("Exception") || text.contains("at com.lifolo")) {
                    bad += "$uid $a → ${r.status} ${text.take(200)} body=${b.entries.take(3)}"
                    break
                }
            }
        }
        return bad
    }

    private fun seedProfessional(uid: String) {
        user(uid, mapOf("name" to "পেশাজীবী", "phone" to "01711000099", "lang" to "en"))
        jdbc.update("INSERT INTO badges (uid, type, reg_no, workplace, name, status) VALUES (?, 'doctor', 'A-1', 'X', 'ডা. ফাজ', 'approved')", uid)
        jdbc.update("INSERT INTO providers (uid, type, name, degrees, specialties, listed) VALUES (?, 'doctor', 'ডা. ফাজ', 'MBBS', '{medicine}', TRUE)", uid)
        jdbc.update(
            """
            INSERT INTO pharmacies (uid, name, license_no, phone, district, upazila_id, address, lat, lng, open_at, close_at, status)
            VALUES (?, 'ফাজ ফার্মেসি', 'DL-1', '01711111111', ?, 466, 'চরপাড়া', 24.75, 90.4, '08:00', '22:00', 'approved')
            """.trimIndent(),
            uid, dist,
        )
        jdbc.update(
            "INSERT INTO labs (uid, name, district, address, license_no, phone, status) VALUES (?, 'ফাজ ল্যাব', ?, 'চরপাড়া', 'DGHS-1', '01711000498', 'approved')",
            uid, dist,
        )
    }

    @Test
    fun `robustness - malformed bodies never give 5xx (plain user)`() {
        user("fz-user", mapOf("name" to "ফাজ", "lang" to "en"))
        // delete_account would end the session and account_deactivate would answer 423 for the rest of the run
        val actions = all().filter { !it.startsWith("admin_") && it != "delete_account" && it != "account_deactivate" }
        assertEquals(emptyList<String>(), fuzz("fz-user", actions, 0))
    }

    @Test
    fun `robustness - malformed bodies never give 5xx (doctor + pharmacy + lab owner)`() {
        seedProfessional("fz-pro")
        val actions = all().filter { !it.startsWith("admin_") && it != "delete_account" && it != "account_deactivate" }
        assertEquals(emptyList<String>(), fuzz("fz-pro", actions, 300))
    }

    @Test
    fun `robustness - malformed bodies never give 5xx (owner on the admin actions)`() {
        user("fz-owner", mapOf("name" to "মালিক", "lang" to "en"), email = "owner@example.com")
        // seeding/wiping the demo world is not a malformed-input question
        val actions = all().filter { it.startsWith("admin_") && !it.startsWith("admin_demo_") }
        assertEquals(emptyList<String>(), fuzz("fz-owner", actions, 600))
    }
}
