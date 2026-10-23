package com.lifolo.demo

import com.lifolo.features.Features
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Production ships every feature switch off (FEATURES.md). The demo world must still build — the owner's "ডেমো বানান",
 * deploy `--demo keep`, and the nightly DemoRefresh rebuild — without the switched-off parts (no demo receipts while the
 * `inv_*` switches are off). Before: InvoiceDemo issued receipts anyway → 404 feature_off stopped the last step.
 */
class DemoSwitchesOffIT : DemoTestBase() {
    @Autowired lateinit var features: Features

    @Autowired lateinit var refresh: DemoRefresh

    private var wasAllOn = true

    @AfterEach
    fun switchesBack() {
        features.allOn = wasAllOn
        features.clearCache()
    }

    private fun partial(): Boolean = jdbc.queryForObject("SELECT partial FROM demo_state", Boolean::class.java) ?: true

    @Test
    fun `demo builds and the nightly refresh rebuilds it with every switch off`() {
        wasAllOn = features.allOn
        features.allOn = false
        features.clearCache()
        assertFalse(features.isOn(U("pharmacy"), "inv_pharmacy"))

        owner()
        seedAll()
        assertFalse(partial(), "the demo is complete")
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM invoices", Int::class.java), "no demo receipts while inv_* are off")
        // the demo roles still log in and see their world
        ok(U("patient"), "my_appointments")
        ok(U("pharmacy"), "books_today")

        // a new Dhaka day: wipe + every step again
        assertEquals("rebuilt", refresh.refresh(System.currentTimeMillis() + DAY)["done"])
        assertFalse(partial(), "the rebuilt demo is complete")
    }
}
