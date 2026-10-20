package com.lifolo.security

import com.lifolo.blood.BloodTestBase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** blood requests: the requester's contact, the donor list and changes — the requester (and accepted donors) only */
class IdorBloodIT : BloodTestBase() {
    @Test
    fun `contact, donors and changes of a blood request`() {
        user("brOwner", mapOf("name" to "অনুরোধকারী", "phone" to "01711000041"))
        donor("dAcc", "O+")
        donor("dOther", "O+")
        request("br1", "brOwner")
        contact("br1", "গোপন নাম", "01711000042")
        response("br1", "dAcc", "accepted")
        // the requester and an accepted donor see the contact; anyone else does not
        assertEquals("01711000042", call("brOwner", "blood_contact_get", mapOf("requestId" to "br1")).body["contact"]["phone"].asText())
        assertEquals("01711000042", call("dAcc", "blood_contact_get", mapOf("requestId" to "br1")).body["contact"]["phone"].asText())
        val other = call("dOther", "blood_contact_get", mapOf("requestId" to "br1"))
        assertEquals(403, other.status)
        assertFalse(other.body.toString().contains("01711000042"))
        // the donor list (names and phones of donors): the requester only
        assertEquals(200, call("brOwner", "blood_responses_list", mapOf("requestId" to "br1")).status)
        for (u in listOf("dAcc", "dOther")) assertEquals(403, call(u, "blood_responses_list", mapOf("requestId" to "br1")).status, u)
        // the request itself (for donors) has no requester phone, contact name or uid
        val got = call("dOther", "blood_request_get", mapOf("requestId" to "br1")).body.toString()
        assertFalse(got.contains("01711000041") || got.contains("01711000042") || got.contains("গোপন নাম"), got)
        // nobody else closes, edits, confirms donors or deletes it
        assertEquals(403, http("/api/close-request", "dOther", mapOf("requestId" to "br1")).status)
        assertEquals(403, http("/api/edit-request", "dOther", mapOf("requestId" to "br1", "bags" to 3, "note" to "x")).status)
        val confirm = call("dOther", "confirm_donor", mapOf("requestId" to "br1", "donorUid" to "dAcc"))
        assertTrue(confirm.status in 400..404, confirm.toString())
        call("dOther", "request_delete", mapOf("requestId" to "br1"))
        call("dOther", "request_reopen", mapOf("requestId" to "br1"))
        val r = row("SELECT status, bags FROM blood_requests WHERE id = 'br1'")
        assertEquals("open", r["status"])
        assertEquals(2, (r["bags"] as Number).toInt())
        assertEquals(false, row("SELECT confirmed FROM blood_responses WHERE request_id = 'br1' AND donor_uid = 'dAcc'")["confirmed"])
        // a donor can only change their own response
        val upd = call("dOther", "response_update", mapOf("requestId" to "br1", "donorUid" to "dAcc", "status" to "cancelled"))
        assertTrue(upd.status in 400..404, upd.toString())
        assertEquals("accepted", row("SELECT status FROM blood_responses WHERE request_id = 'br1' AND donor_uid = 'dAcc'")["status"])
    }

    @Test
    fun `the shared request page shows no name, phone or note`() {
        user("brOwner", mapOf("name" to "অনুরোধকারী গোপন", "phone" to "01711000041"))
        request("br2", "brOwner", hospital = "ময়মনসিংহ মেডিকেল")
        contact("br2", "যোগাযোগ গোপন", "01711000042")
        val page = page("/r/br2")
        assertEquals(200, page.status)
        val html = page.getContentAsString(Charsets.UTF_8)
        assertTrue(html.contains("ময়মনসিংহ মেডিকেল"))
        for (secret in listOf("01711000041", "01711000042", "secret note", "Requester brOwner", "গোপন", "brOwner")) {
            assertFalse(html.contains(secret), secret)
        }
        // injected markup is escaped
        jdbc.update("UPDATE blood_requests SET hospital = '<script>alert(1)</script>' WHERE id = 'br2'")
        val h2 = page("/r/br2").getContentAsString(Charsets.UTF_8)
        assertFalse(h2.contains("<script>alert(1)</script>"))
        assertTrue(h2.contains("&lt;script&gt;"))
        assertEquals(404, page("/r/bad~id").status)
        assertEquals(404, page("/r/nope404").status)
    }
}
