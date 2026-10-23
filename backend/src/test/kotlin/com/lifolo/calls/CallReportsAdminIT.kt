package com.lifolo.calls

import com.lifolo.support.IntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** admin_call_reports / admin_call_report_set: admins only, names, open-report count, blocks, status change logged */
class CallReportsAdminIT : IntegrationTest() {
    private fun report(id: String, reporter: String, reported: String, reason: String = "harassment") {
        jdbc.update(
            "INSERT INTO call_reports (id, call_id, reporter_uid, reported_uid, reason, note, call_meta) VALUES (?, ?, ?, ?, ?, 'বাজে কথা', ?::jsonb)",
            id, "call_$id", reporter, reported, reason, """{"ctx":"blood","video":false,"durationSec":42}""",
        )
    }

    @Test
    fun `list, change status, admins only`() {
        user("crOwner", mapOf("name" to "মালিক"), email = "owner@example.com")
        user("crA", mapOf("name" to "রিপোর্টকারী"))
        user("crB", mapOf("name" to "অভিযুক্ত"))
        user("crC", mapOf("name" to "আরেকজন"))
        report("r1", "crA", "crB")
        report("r2", "crC", "crB", "spam")
        jdbc.update("INSERT INTO call_blocks (blocker_uid, blocked_uid) VALUES ('crA', 'crB')")

        assertEquals(403, call("crA", "admin_call_reports").status)
        assertEquals(403, call("crA", "admin_call_report_set", mapOf("id" to "r1", "status" to "resolved")).status)

        val items = call("crOwner", "admin_call_reports").body["items"]
        assertEquals(2, items.size())
        val r1 = items.first { it["id"].textValue() == "r1" }
        assertEquals("রিপোর্টকারী", r1["reporter"]["name"].textValue())
        assertEquals("অভিযুক্ত", r1["reported"]["name"].textValue())
        assertEquals(2, r1["reported"]["openReports"].intValue())
        assertEquals(1, r1["reported"]["blockedBy"].intValue())
        assertEquals(42, r1["call"]["durationSec"].intValue())
        assertEquals("বাজে কথা", r1["note"].textValue())

        assertEquals(400, call("crOwner", "admin_call_report_set", mapOf("id" to "r1", "status" to "gone")).status)
        assertEquals(404, call("crOwner", "admin_call_report_set", mapOf("id" to "nope", "status" to "resolved")).status)
        assertEquals(200, call("crOwner", "admin_call_report_set", mapOf("id" to "r1", "status" to "resolved")).status)
        assertEquals(listOf("r2"), call("crOwner", "admin_call_reports").body["items"].map { it["id"].textValue() })
        assertEquals(listOf("r1"), call("crOwner", "admin_call_reports", mapOf("status" to "resolved")).body["items"].map { it["id"].textValue() })
        assertEquals("callReports/r1", row("SELECT target FROM admin_log WHERE action = 'admin_call_report_set'")["target"])
    }
}
