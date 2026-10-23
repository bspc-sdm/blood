package com.lifolo.users

import com.lifolo.admin.kernel.AccountDeletionStep
import com.lifolo.support.IntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.sql.Timestamp

/**
 * Port of server/test/deletion.test.js and doctorpatients.test.js — account deletion after 30 days: the request hides
 * the businesses, logging in again restores them, the daily job deletes due accounts; plus the orchestrator order
 * (every package's step, then the platform step, the accounts row and the login).
 */
class DeletionIT : IntegrationTest() {
    @Autowired lateinit var deletion: AccountDeletionService

    @Autowired lateinit var cancel: DeletionService

    @Autowired lateinit var steps: List<AccountDeletionStep>

    private val DAY = 86_400_000L

    private fun shop(uid: String, status: String = "approved") {
        jdbc.update(
            "INSERT INTO pharmacies (uid, name, license_no, phone, district, status) VALUES (?, ?, 'L-1', '01711111111', 'ঢাকা', ?)",
            uid, "ডেমো ফার্মেসি", status,
        )
    }

    private fun ambulance(id: String, owner: String, status: String) {
        jdbc.update(
            "INSERT INTO ambulances (id, owner_uid, name, phone, vehicle_no, district, status) VALUES (?, ?, 'ডেমো অ্যাম্বুলেন্স', '01711111111', ?, 'ঢাকা', ?)",
            id, owner, "DHA-$id", status,
        )
    }

    private fun status(table: String, idCol: String, id: String) = row("SELECT status, hidden_for_deletion FROM $table WHERE $idCol = ?", id)

    @Test
    fun `the request - nothing deleted yet, a date 30 days ahead, businesses hidden, devices dropped`() {
        val u = "owner000001"
        user(u, mapOf("name" to "ডেমো মালিক"))
        jdbc.update("INSERT INTO device_tokens (token, uid) VALUES ('tok-owner-000000000001', ?)", u)
        shop(u)
        ambulance("a1", u, "pending")
        ambulance("a2", u, "approved")

        val before = System.currentTimeMillis()
        val r = call(u, "delete_account")
        assertEquals(200, r.status, r.toString())
        assertTrue(r.body["deleteAt"].longValue() >= before + 29 * DAY)
        assertTrue(identity.revokedCalls.contains(u), "every login revoked")
        assertNotNull(row("SELECT uid FROM user_profiles WHERE uid = ?", u)["uid"], "the profile is still there")
        assertEquals("suspended", status("pharmacies", "uid", u)["status"])
        assertEquals("suspended", status("ambulances", "id", "a1")["status"])
        assertEquals("pending", status("ambulances", "id", "a1")["hidden_for_deletion"])
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM device_tokens WHERE uid = ?", Int::class.java, u), "no notifications")
        // a second request keeps the first date
        val again = call(u, "delete_account")
        assertEquals(r.body["deleteAt"].longValue(), again.body["deleteAt"].longValue())

        // an admin changed a status meanwhile: that one stays
        jdbc.update("UPDATE ambulances SET status = 'rejected' WHERE id = 'a2'")

        // logging in again: the deletion is cancelled, everything as before
        assertTrue(cancel.cancel(u))
        assertNull(row("SELECT delete_at FROM accounts WHERE uid = ?", u)["delete_at"])
        assertEquals("approved", status("pharmacies", "uid", u)["status"])
        assertEquals("pending", status("ambulances", "id", "a1")["status"])
        assertEquals("rejected", status("ambulances", "id", "a2")["status"])
        assertNull(status("ambulances", "id", "a2")["hidden_for_deletion"])
        assertFalse(cancel.cancel(u), "nothing to cancel")
    }

    @Test
    fun `after 30 days the daily job deletes - not before`() {
        val a = "gone0000001"
        val b = "wait0000001"
        for (u in listOf(a, b)) {
            user(u, mapOf("name" to "ডেমো"))
            shop(u)
            assertEquals(200, call(u, "delete_account").status)
        }
        // A's time is over
        jdbc.update("UPDATE accounts SET delete_at = ? WHERE uid = ?", Timestamp(System.currentTimeMillis() - 1000), a)
        assertEquals(1, deletion.runDue(System.currentTimeMillis()))
        assertTrue(row("SELECT uid FROM user_profiles WHERE uid = ?", a).isEmpty())
        assertTrue(row("SELECT uid FROM pharmacies WHERE uid = ?", a).isEmpty())
        assertTrue(row("SELECT uid FROM accounts WHERE uid = ?", a).isEmpty())
        assertFalse(identity.users.containsKey(a), "the login is deleted too")
        assertFalse(row("SELECT uid FROM user_profiles WHERE uid = ?", b).isEmpty(), "not due yet")
        assertTrue(identity.users.containsKey(b))
    }

    @Test
    fun `the daily cron runs the deletion (step 10) and the other platform steps`() {
        val a = "gone0000002"
        user(a, mapOf("name" to "ডেমো"))
        assertEquals(200, call(a, "delete_account").status)
        jdbc.update("UPDATE accounts SET delete_at = ? WHERE uid = ?", Timestamp(System.currentTimeMillis() - 1000), a)
        // an old bell item and an old phone limit (steps 11 and 6)
        user("keep0000001")
        jdbc.update(
            "INSERT INTO inbox_items (id, uid, title, created_at) VALUES ('keep0000001_old', 'keep0000001', 'x', ?)",
            Timestamp(System.currentTimeMillis() - 22 * DAY),
        )
        jdbc.update("INSERT INTO inbox_items (id, uid, title) VALUES ('keep0000001_new', 'keep0000001', 'y')")
        jdbc.update("INSERT INTO rate_counters (key, scope, day, count) VALUES ('h_old', 'phone', DATE '2020-01-01', 1)")
        val r = get("/api/cron/daily", mapOf("Authorization" to "Bearer test-cron-secret"))
        assertEquals(200, r.status, r.toString())
        assertEquals(1, r.body["accountsDeleted"].intValue())
        assertEquals(1, r.body["inboxDeleted"].intValue())
        assertEquals(1, r.body["phoneLimitsDeleted"].intValue())
        for (k in listOf("ok", "closed", "eligibleReminders", "care", "emergenciesClosed", "medFindsDeleted", "rxReminders", "refill", "ambulanceReminders", "ambulanceRequests", "privacy")) {
            assertTrue(r.body.has(k), k)
        }
        assertTrue(row("SELECT uid FROM accounts WHERE uid = ?", a).isEmpty())
        assertEquals(listOf("keep0000001_new"), jdbc.queryForList("SELECT id FROM inbox_items", String::class.java))
    }

    @Test
    fun `the app cannot call the internal helpers`() {
        val u = "nosy0000001"
        user(u)
        for (a in listOf("deletion", "schedule", "cancel", "runDue", "deleteNow")) assertEquals(400, call(u, a).status, a)
        // nor a caregiver for the patient
        user("nosyPatient1")
        assertEquals(403, call(u, "delete_account", mapOf("asUid" to "nosyPatient1")).status)
    }

    @Test
    fun `a doctor deleted - own patient list goes (more than 400), prescriptions stay, other doctors' patients stay`() {
        val d = "docdel00001"
        user(d, mapOf("name" to "ডা. পরীক্ষা"), email = "d@example.com")
        user("otherdoc1")
        user("pat000001")
        for (i in 0 until 450) {
            jdbc.update("INSERT INTO rx_patients (id, doctor_uid, name, phone) VALUES (?, ?, ?, '01711000000')", "p$i", d, "রোগী $i")
        }
        jdbc.update("INSERT INTO rx_patients (id, doctor_uid, name) VALUES ('other1', 'otherdoc1', 'অন্য')")
        jdbc.update(
            "INSERT INTO prescriptions (id, code, doctor_uid, patient_uid, status) VALUES ('rx1', 'AB23CD45EF', ?, 'pat000001', 'active')",
            d,
        )
        deletion.deleteNow(d, "d@example.com")
        assertEquals(listOf("other1"), jdbc.queryForList("SELECT id FROM rx_patients", String::class.java))
        assertFalse(row("SELECT id FROM prescriptions WHERE id = 'rx1'").isEmpty(), "the prescription stays")
    }

    @Test
    fun `every package has a step, run in order, the platform last`() {
        val orders = steps.map { it.order }.sorted()
        // 450: receipts (kept 6 years without personal fields — docs/INVOICES.md); 550: calls; 560: chat
        assertEquals(listOf(100, 200, 300, 400, 450, 455, 500, 550, 560, 600), orders)
    }

    @Test
    fun `deleteNow - the user's own things go, sanctions and others' data stay`() {
        val u = "full0000001"
        user(u, mapOf("name" to "সব"), email = "Full@Example.com")
        user("other00001")
        jdbc.update("INSERT INTO tickets (id, uid, category) VALUES ('t1', ?, 'other')", u)
        jdbc.update("INSERT INTO ticket_messages (ticket_id, idx, sender, text) VALUES ('t1', 0, 'user', 'help me please')")
        jdbc.update("INSERT INTO inbox_items (id, uid, title) VALUES (?, ?, 'x')", "${u}_1", u)
        jdbc.update("INSERT INTO user_state (uid, verified_phone) VALUES (?, '8801711111111')", u)
        jdbc.update("INSERT INTO questions (id, uid, text) VALUES ('q-other', 'other00001', 'প্রশ্ন')")
        jdbc.update(
            "INSERT INTO mod_reports (id, target_path, kind, reporter_uid, status) VALUES ('r1', 'questions/q-other', 'question', ?, 'open')",
            u,
        )
        jdbc.update("INSERT INTO email_codes (email_hash) VALUES (?)", com.lifolo.auth.EmailCodes.docIdFor("full@example.com"))
        jdbc.update("INSERT INTO sanctions (key, listing) VALUES ('s1', TRUE)")
        deletion.deleteNow(u, "Full@Example.com")
        for (t in listOf("tickets", "inbox_items", "user_state", "user_profiles", "mod_reports", "accounts")) {
            val col = if (t == "mod_reports") "reporter_uid" else "uid"
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM $t WHERE $col = ?", Int::class.java, u), t)
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM email_codes", Int::class.java))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM sanctions", Int::class.java), "sanctions survive")
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM questions", Int::class.java))
        assertFalse(identity.users.containsKey(u))
        // a second run (a half-finished earlier one) is harmless
        deletion.deleteNow(u, "Full@Example.com")
    }
}
