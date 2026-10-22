package com.lifolo.unit

import com.lifolo.notify.NotifyCats
import com.lifolo.notify.NotifyPrefs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Every push type has exactly one notification category (NotifyCats) */
class NotifyCatsTest {
    /** `"type" to "…"` literals in the server code that are not push types (badge/listing kinds in demo rows) */
    private val notPushTypes = setOf("doctor", "nutritionist", "trainer")

    /** the 21 push types of the apps' notification routing (app NotifyRoute.kt, e2e journey 11) */
    private val appTypes = listOf(
        "request", "response", "request_removed", "emergency", "eligible", "correction", "care", "support", "hospital",
        "badge", "services", "amb_req_mine", "answer", "appointment", "rx", "rx_pharmacy", "rx_lab", "rx_review",
        "med_find", "med_find_mine",
    )

    @Test
    fun `every push type in the server code has a category`() {
        val root = File("src/main/kotlin")
        assertTrue(root.isDirectory, "run from backend/ (Gradle test working directory)")
        val re = Regex("\"type\" to \"([A-Za-z_]+)\"")
        val found = root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }
            .flatMap { f -> re.findAll(f.readText()).map { it.groupValues[1] to f.name } }
            .toList()
        assertTrue(found.size > 30, "the scan found the push calls ($found)")
        val missing = found.filter { (t, _) -> t !in notPushTypes && t !in NotifyCats.BY_TYPE }
        assertEquals(emptyList<Pair<String, String>>(), missing, "push types without a category — add them to NotifyCats.BY_TYPE")
        for (t in appTypes) assertTrue(t in NotifyCats.BY_TYPE, "app push type $t has no category")
    }

    @Test
    fun `categories are the contract ids, each type maps to one of them`() {
        assertEquals(
            listOf("blood_nearby", "blood_emergency", "my_request", "appointments", "rx", "ambulance", "community", "business", "calls"),
            NotifyCats.SETTABLE,
        )
        assertEquals(NotifyCats.SETTABLE.toSet() + "account", NotifyCats.IDS)
        for ((t, c) in NotifyCats.BY_TYPE) assertTrue(c in NotifyCats.IDS, "$t → $c")
        // every settable category is used by some push
        val used = NotifyCats.BY_TYPE.values.toSet() + NotifyCats.AMBULANCE + NotifyCats.BLOOD_NEARBY
        assertTrue(used.containsAll(NotifyCats.SETTABLE), "unused: ${NotifyCats.SETTABLE - used}")
    }

    @Test
    fun `per-site overrides`() {
        fun of(vararg kv: Pair<String, Any?>) = NotifyCats.of(mapOf(*kv))
        // blood: a new nearby request vs. updates for a donor who answered
        assertEquals("blood_nearby", of("type" to "request", "requestId" to "r", "cat" to "blood_nearby"))
        assertEquals("my_request", of("type" to "request", "requestId" to "r"))
        assertEquals("my_request", of("type" to "response", "requestId" to "r"))
        assertEquals("my_request", of("type" to "request_removed"))
        assertEquals("blood_emergency", of("type" to "emergency", "emergencyId" to "e"))
        assertEquals("blood_nearby", of("type" to "eligible"))
        // appointments: updates vs. new bookings for the doctor
        assertEquals("appointments", of("type" to "appointment", "role" to "patient"))
        assertEquals("appointments", of("type" to "appointment", "role" to "provider"))
        assertEquals("business", of("type" to "appointment", "role" to "provider", "cat" to "business"))
        // ambulance: the driver's new call is business, later updates on it and the patient's news are ambulance
        assertEquals("business", of("type" to "services", "kind" to "ambulance", "sub" to "amb_req", "cat" to "business"))
        assertEquals("ambulance", of("type" to "services", "kind" to "ambulance", "sub" to "amb_req"))
        assertEquals("ambulance", of("type" to "amb_req_mine", "reqId" to "a"))
        assertEquals("business", of("type" to "services", "kind" to "ambulance", "ambulanceId" to "x"))
        assertEquals("business", of("type" to "services", "kind" to "pharmacy"))
        // caregiver copy of patient news carries the news' category; an invitation is account
        assertEquals("rx", of("type" to "care", "asUid" to "p", "rxId" to "x", "cat" to "rx"))
        assertEquals("account", of("type" to "care"))
        assertEquals("community", of("type" to "answer", "questionId" to "q"))
        // in-app calls
        assertEquals("calls", of("type" to "call_incoming", "callId" to "c"))
        assertEquals("calls", of("type" to "call_state", "callId" to "c"))
        assertEquals("calls", of("type" to "call_missed", "callId" to "c"))
        assertEquals("appointments", of("type" to "appt_call_ready", "appointmentId" to "a"))
        // unknown type / no type / bad cat → account (always sent)
        assertEquals("account", of("type" to "zzz"))
        assertEquals("account", of())
        assertEquals("rx", of("type" to "rx", "cat" to "nonsense"))
    }

    @Test
    fun `prefs - master switch, categories, account always`() {
        val d = NotifyPrefs.DEFAULT
        assertTrue(NotifyCats.IDS.all { d.allows(it) })
        val off = NotifyPrefs(false, d.cats)
        assertFalse(off.allows("rx"))
        assertTrue(off.allows("account"))
        val rxOff = NotifyPrefs(true, d.cats + ("rx" to false))
        assertFalse(rxOff.allows("rx"))
        assertTrue(rxOff.allows("community"))
    }
}
