package com.lifolo.demo

import com.fasterxml.jackson.databind.JsonNode
import com.lifolo.ambulance.AmbClock
import com.lifolo.appointments.P2Clock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.sql.Timestamp

/**
 * The kept demo stays current (Android journeys 2 and 12 failed 3 days after the seed): the demo cars' clock moves with
 * real time, a new Dhaka day builds the demo again.
 */
class DemoRefreshIT : DemoTestBase() {
    @Autowired lateinit var refresh: DemoRefresh

    @Autowired lateinit var p2clock: P2Clock

    private val HOUR = 3_600_000L

    @AfterEach
    fun resetClocks() {
        AmbClock.reset()
        p2clock.fixedMs = null
    }

    private fun cars(): List<JsonNode> {
        val r = ok(U("patient"), "ambulances", mapOf("district" to GZP))
        return r["items"].toList() + (r["busy"]?.toList() ?: emptyList())
    }

    private fun queueToday(): JsonNode = ok(U("doctor"), "queue_today")["chambers"]

    @Test
    fun `no demo - nothing to do`() {
        assertEquals("none", refresh.refresh(System.currentTimeMillis())["done"])
    }

    @Test
    fun `demo cars - their clock moves with real time, so the available ones stay available`() {
        owner()
        seedAll()
        assertTrue(cars().any { it["available"].booleanValue() })
        // a tester marks the non-AC car available after the seed (not pushed past now later)
        ok(U("ambulance"), "ambulance_available", mapOf("id" to "demo-svc-amb-nonac", "available" to true))

        AmbClock.offsetMs = 13 * HOUR
        assertFalse(cars().any { it["available"].booleanValue() }, "13 hours later nothing is available without the refresh")

        val moved = refresh.shiftCars(AmbClock.now())
        assertTrue(moved >= 13 * HOUR - 60_000, "moved $moved")
        val after = cars()
        assertTrue(after.first { it["id"].textValue() == "demo-svc-amb-ac" }["available"].booleanValue(), "AC available again")
        assertTrue(after.first { it["id"].textValue() == "demo-svc-amb-nonac" }["available"].booleanValue(), "the tester's car too")
        assertTrue(after.none { it.path("availableAt").asLong(0) > AmbClock.now() }, "no time in the future")
        assertTrue(refresh.shiftCars(AmbClock.now()) < 60_000, "a second run right after moves (almost) nothing")
    }

    @Test
    fun `a new Dhaka day builds the demo again - today's serials are back`() {
        owner()
        seedAll()
        assertTrue(queueToday().size() > 0, "the seed day has serials")
        p2clock.fixedMs = System.currentTimeMillis() + 2 * DAY
        assertEquals(0, queueToday().size(), "two days later the demo doctor has no serial today without the refresh")
        p2clock.fixedMs = null

        val seededAt = row("SELECT at FROM demo_state")["at"] as Timestamp
        assertEquals("clock", refresh.refresh(System.currentTimeMillis())["done"], "same day: no new build")
        assertEquals("rebuilt", refresh.refresh(System.currentTimeMillis() + DAY)["done"])
        assertTrue((row("SELECT at FROM demo_state")["at"] as Timestamp).time >= seededAt.time)
        assertFalse(row("SELECT partial FROM demo_state")["partial"] as Boolean)
        assertTrue(queueToday().size() > 0, "today's serials after the new build")
        assertEquals(200, verify(mapOf("demo" to "doctor")).status, "demo login works")
    }

    @Test
    fun `a build in progress is left alone, one that stopped long ago is built again`() {
        owner()
        seedAll()
        jdbc.update("UPDATE demo_state SET partial = TRUE")
        val now = System.currentTimeMillis()
        assertEquals("busy", refresh.refresh(now)["done"])
        assertEquals("rebuilt", refresh.refresh(now + 2 * HOUR)["done"])
        assertFalse(row("SELECT partial FROM demo_state")["partial"] as Boolean)
    }
}
