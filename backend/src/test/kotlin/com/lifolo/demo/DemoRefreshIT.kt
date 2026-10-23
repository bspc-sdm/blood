package com.lifolo.demo

import com.fasterxml.jackson.databind.JsonNode
import com.lifolo.ambulance.AmbClock
import com.lifolo.features.Features
import com.lifolo.support.setFeature
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

    @Autowired lateinit var features: Features

    private val HOUR = 3_600_000L

    @AfterEach
    fun resetClock() {
        AmbClock.reset()
        features.allOn = true
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
        // what an old seed looks like today: no serial of the demo doctor on today's date
        jdbc.update("DELETE FROM appointments WHERE provider_uid = ? AND visit_date >= current_date - 1", U("doctor"))
        assertEquals(0, queueToday().size(), "no serial today")

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

    @Test
    fun `every switch on in production (ops-features all_on, health_consent too) - the nightly build and demo login work`() {
        owner()
        features.allOn = false
        for (k in Features.KEYS) setFeature(jdbc, features, k, Features.ON)
        seedAll()
        assertEquals("rebuilt", refresh.refresh(System.currentTimeMillis() + DAY)["done"])
        assertFalse(row("SELECT partial FROM demo_state")["partial"] as Boolean)
        // online_chamber is on for the demo doctor: the online chamber is there, so no second build on the next run
        assertTrue((jdbc.queryForObject("SELECT count(*) FROM provider_chambers WHERE provider_uid = ? AND mode = 'online'", Int::class.java, U("doctor")) ?: 0) > 0)
        assertEquals("clock", refresh.refresh(System.currentTimeMillis())["done"])
        assertTrue(queueToday().size() > 0, "today's serials")
        assertEquals(200, verify(mapOf("demo" to "patient")).status, "demo login works")
        assertTrue(ok(U("patient"), "features")["keys"].all { it.booleanValue() })
    }
}
