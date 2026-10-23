package com.lifolo.calls

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Feature switches (owner rule: ships OFF): calls_audio, calls_video, appt_video gate every call action */
class CallsFeaturesIT : CallsTestBase() {
    @Test
    fun `everything off - every call action 404 feature_off, contact_can all false, nothing rings`() {
        for (u in listOf("a", "b", "pat", "doc")) person(u)
        bloodRequest("r1", "a", mapOf("b" to "accepted"))
        appointment("ap1", "pat", "doc", slotOffsetMin = 0, online = true)
        features.override = emptySet()
        val r = start("a", "blood", "r1", "b")
        assertEquals(404, r.status)
        assertEquals("feature_off", r.code)
        assertEquals("এই সুবিধা এখনো চালু হয়নি", r.error)
        for (a in listOf(
            "call_config", "call_accept", "call_decline", "call_cancel", "call_end", "call_status", "call_history", "call_upgrade",
            "call_upgrade_accept", "call_upgrade_decline", "call_block", "call_unblock", "call_blocks", "call_report", "appt_join",
        )) {
            assertEquals("feature_off", call("a", a, mapOf("callId" to "x", "peerUid" to "b", "appointmentId" to "ap1")).code, a)
        }
        val c = ok("a", "contact_can", mapOf("items" to listOf(mapOf("ctx" to "blood", "refId" to "r1", "peerUid" to "b"))))["items"][0]
        assertFalse(c["canCall"].booleanValue())
        assertFalse(c["canVideo"].booleanValue())
        assertFalse(c["canMessage"].booleanValue())
        assertTrue(push.messages.isEmpty())
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM call_sessions", Int::class.java))
    }

    @Test
    fun `audio on, video off - audio calls work, video asks are refused or ring as audio, upgrade off`() {
        person("a")
        person("b")
        bloodRequest("r1", "a", mapOf("b" to "accepted"), confirmed = setOf("b"))
        features.override = setOf(CallFeatures.CALLS_AUDIO, CallFeatures.CHAT)
        assertEquals("feature_off", start("a", "blood", "r1", "b", video = true).code)
        val id = startOk("a", "blood", "r1", "b")
        val acc = ok("b", "call_accept", mapOf("callId" to id, "video" to true))
        assertFalse(acc["video"].booleanValue())
        assertEquals(listOf("microphone"), claims(acc["token"].textValue())["video"]["canPublishSources"].map { it.textValue() })
        assertEquals("feature_off", call("a", "call_upgrade", mapOf("callId" to id)).code)
        val c = ok("a", "contact_can", mapOf("items" to listOf(mapOf("ctx" to "blood", "refId" to "r1", "peerUid" to "b"))))["items"][0]
        assertTrue(c["canCall"].booleanValue())
        assertFalse(c["canVideo"].booleanValue())
        assertTrue(c["canMessage"].booleanValue())
        // video without audio is not on either (dependency)
        features.override = setOf(CallFeatures.CALLS_VIDEO)
        assertFalse(features.isOn("a", CallFeatures.CALLS_VIDEO))
    }

    @Test
    fun `switching a feature off ends what is running at the next check`() {
        person("a")
        person("b")
        bloodRequest("r1", "a", mapOf("b" to "accepted"))
        val id = startOk("a", "blood", "r1", "b")
        ok("b", "call_accept", mapOf("callId" to id))
        assertEquals(0, calls.recheckLive())
        features.override = setOf(CallFeatures.APPT_VIDEO)
        assertEquals(1, calls.recheckLive())
        assertEquals("ended", state(id))
        assertEquals("feature_off", row("SELECT end_reason FROM call_sessions WHERE id = ?", id)["end_reason"])
        assertTrue(roomOps.contains("DeleteRoom c_$id"))
    }
}
