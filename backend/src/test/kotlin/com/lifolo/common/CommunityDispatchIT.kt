package com.lifolo.common

import com.lifolo.auth.AuthUser
import com.lifolo.care.CareAccess
import com.lifolo.common.action.ActionPolicy
import com.lifolo.notify.PushItem
import com.lifolo.notify.PushService
import com.lifolo.support.IntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/** /api/community dispatch rules (community.js + guest.js + careaccess.js) and shared infrastructure. */
class CommunityDispatchIT : IntegrationTest() {
    @Autowired
    lateinit var care: CareAccess

    @Autowired
    lateinit var pushService: PushService

    @Autowired
    lateinit var quotas: QuotaService

    @Test
    fun `unknown action - after login, in the request language`() {
        user("u1")
        val bn = call("u1", "no_such_action")
        assertEquals(400, bn.status)
        assertEquals("অজানা অনুরোধ। অ্যাপ আপডেট করুন।", bn.error)
        assertEquals("Unknown request. Please update the app.", call("u1", "no_such_action", lang = "en").error)
        // no lang → English (community.js tr)
        assertEquals("Unknown request. Please update the app.", call("u1", "no_such_action", lang = null).error)
    }

    @Test
    fun `guests - only public actions, otherwise 401`() {
        for (action in listOf("ask", "me", "report", "book", "request_create", "ambulance_called", "med_find", "admin_overview", "profile_get")) {
            val r = call(null, action, mapOf("text" to "হ্যালো"))
            assertEquals(401, r.status, action)
            assertEquals("লগইন দরকার", r.error)
        }
        assertEquals("Please log in", call(null, "ask", lang = "en").error)
        // a public action passes the login check (not ported yet → unknown)
        assertEquals(400, call(null, "catalog_get").status)
        assertTrue(ActionPolicy.isPublic("guest_requests"))
    }

    @Test
    fun `guests - 240 requests per minute per IP`() {
        var last: IntegrationTest.Res? = null
        repeat(241) { last = call(null, "catalog_get", ip = "9.9.9.9") }
        assertEquals(429, last!!.status)
        assertEquals("অনেক বেশি অনুরোধ। এক মিনিট পর আবার চেষ্টা করুন।", last!!.error)
        assertEquals(400, call(null, "catalog_get", ip = "8.8.8.8").status, "another IP is not limited")
    }

    @Test
    fun `token problems`() {
        // unknown/expired token
        val r = call("ghost", "profile_get", token = "garbage")
        assertEquals(401, r.status)
        assertEquals("লগইনের মেয়াদ শেষ, আবার লগইন করুন", r.error)
        assertEquals("Session expired, please log in again", call("ghost", "profile_get", token = "garbage", lang = "en").error)
        // "Bearer " with an empty token = not logged in
        assertEquals("লগইন দরকার", post("/api/community", mapOf("action" to "profile_get", "lang" to "bn"), mapOf("Authorization" to "Bearer ")).error)

        // disabled account: 403 account_disabled with the reason
        user("dis1")
        identity.users["dis1"]!!.disabled = true
        jdbc.update("UPDATE accounts SET disabled_reason = 'স্প্যাম' WHERE uid = 'dis1'")
        val d = call("dis1", "profile_get", token = identity.jwtFor("dis1"))
        assertEquals(403, d.status)
        assertEquals("account_disabled", d.code)
        assertEquals("আপনার অ্যাকাউন্ট Lifolo টিম বন্ধ করেছে। কারণ: স্প্যাম। আপিল করতে ইমেইল করুন: ekapkbd@gmail.com", d.error)

        // tokens revoked by a deletion request: the date in the message
        user("del1")
        identity.users["del1"]!!.revoked = true
        jdbc.update("UPDATE accounts SET delete_at = to_timestamp(1795000000) WHERE uid = 'del1'")
        val del = call("del1", "profile_get", token = identity.jwtFor("del1"), lang = "en")
        assertEquals(403, del.status)
        assertEquals("account_disabled", del.code)
        assertEquals("Your account will be deleted on 18 November 2026. Log in again before then to keep it.", del.error)
        assertEquals(
            "আপনার অ্যাকাউন্ট ১৮ নভেম্বর, ২০২৬ মুছে যাবে। এর আগে আবার লগইন করলে মোছা বাতিল হবে।",
            call("del1", "profile_get", token = identity.jwtFor("del1")).error,
        )
    }

    @Test
    fun `a successful token registers the account`() {
        identity.add("fresh1", "Fresh@X.com")
        assertEquals(200, call("fresh1", "profile_get").status)
        assertEquals("fresh@x.com", row("SELECT email FROM accounts WHERE uid = 'fresh1'")["email"])
    }

    @Test
    fun `caregivers act for the patient only on allowed actions with an active link`() {
        user("pat1")
        user("carer1")
        val body = { action: String, asUid: Any? -> Body.of(mapper.valueToTree(mapOf("action" to action, "asUid" to asUid))) }
        val carer = AuthUser(uid = "carer1", email = "carer1@example.com", emailVerified = true)
        // no asUid / own uid → unchanged
        assertEquals(carer, care.actAs(body("rx_mine", null), carer, "en"))
        assertEquals(carer, care.actAs(body("rx_mine", "carer1"), carer, "en"))
        // not a caregiver action
        val e1 = assertThrows(ApiException::class.java) { care.actAs(body("profile_get", "pat1"), carer, "en") }
        assertEquals(403, e1.status)
        assertEquals("A caregiver can't do this for the patient", e1.message)
        // no link (or not active)
        jdbc.update("INSERT INTO care_links (patient_uid, caretaker_uid, status) VALUES ('pat1', 'carer1', 'invited')")
        val e2 = assertThrows(ApiException::class.java) { care.actAs(body("rx_mine", "pat1"), carer, "bn") }
        assertEquals("care_revoked", e2.code)
        assertEquals("আপনি আর এই মানুষের কেয়ারগিভার নন", e2.message)
        // active link
        jdbc.update("UPDATE care_links SET status = 'active'")
        val actor = care.actAs(body("rx_mine", "pat1"), carer, "en")
        assertEquals("pat1", actor.uid)
        assertEquals("carer1", actor.actingBy)
        assertEquals(false, actor.emailVerified)
    }

    @Test
    fun `push writes the inbox even without devices, removes dead tokens`() {
        user("pu1")
        user("pu2")
        jdbc.update("INSERT INTO device_tokens (token, uid) VALUES ('t1', 'pu1'), ('dead', 'pu1')")
        push.dead.add("dead")
        val reached = pushService.send(
            listOf(
                PushItem(uid = "pu1", title = "শিরোনাম", body = "বার্তা", data = mapOf("type" to "answer", "questionId" to "q1")),
                PushItem(uid = "pu2", title = "no device"),
            ),
        )
        assertEquals(1, reached)
        assertEquals(2, (jdbc.queryForObject("SELECT count(*) FROM inbox_items", Long::class.java) ?: 0L).toInt())
        val m = push.messages.first { it.token == "t1" }
        assertEquals("pu1", m.data["forUid"])
        assertEquals("answer", m.data["type"])
        assertEquals("শিরোনাম", m.data["title"])
        assertEquals(listOf("t1"), jdbc.queryForList("SELECT token FROM device_tokens WHERE uid = 'pu1'", String::class.java))
    }

    @Test
    fun `daily quotas`() {
        user("q1")
        repeat(5) { quotas.use("en", "q1", "ask") }
        val e = assertThrows(ApiException::class.java) { quotas.use("en", "q1", "ask") }
        assertEquals(429, e.status)
        assertEquals("Daily limit reached. Try again tomorrow.", e.message)
    }

    @Test
    fun `health, cron and method checks`() {
        val h = get("/api/health")
        assertEquals(200, h.status)
        assertEquals(true, h.body["ok"].booleanValue())
        assertEquals(405, get("/api/community").status)
        val noSecret = get("/api/cron/daily")
        assertEquals(401, noSecret.status)
        assertEquals("অনুমতি নেই", noSecret.error)
        val cron = get("/api/cron/daily", mapOf("Authorization" to "Bearer test-cron-secret"))
        assertEquals(200, cron.status)
        assertEquals(true, cron.body["ok"].booleanValue())
        assertEquals(405, post("/api/cron/daily", emptyMap<String, Any>()).status)
    }

    @Test
    fun `numbers are written like JSON stringify`() {
        user("n1", mapOf("weight_kg" to 60.0, "lat" to 24.0, "lng" to 90.5))
        val p = call("n1", "profile_get").body["profile"]
        assertEquals("60", p["weightKg"].toString())
        assertEquals("24", p["lat"].toString())
        assertEquals("90.5", p["lng"].toString())
    }
}
