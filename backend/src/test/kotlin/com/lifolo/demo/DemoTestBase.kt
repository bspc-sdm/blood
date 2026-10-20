package com.lifolo.demo

import com.fasterxml.jackson.databind.JsonNode
import com.lifolo.common.Places
import com.lifolo.support.IntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals

/** helpers of the Node demo tests (the owner, `ok`, guest calls from changing IPs, what is left after a wipe) */
abstract class DemoTestBase : IntegrationTest() {
    val OWNER = "owner-demo-test"
    val DAY = 86_400_000L
    val GZP: String = Places.DISTRICTS.first { it.en == "Gazipur" }.bn

    fun U(k: String) = DemoWorld.uidOf(k)

    fun X(k: String) = DemoWorld.uidOf("x-$k")

    fun owner() = user(OWNER, emptyMap(), email = "owner@example.com")

    private var ip = 0

    /** a call (null uid = guest from a new IP, so the guest limit never trips) */
    fun req(uid: String?, action: String, body: Map<String, Any?> = emptyMap()): Res =
        call(uid, action, body, ip = "10.9.0.${++ip % 250}")

    fun ok(uid: String?, action: String, body: Map<String, Any?> = emptyMap()): JsonNode =
        req(uid, action, body).also { assertEquals(200, it.status, "$action: $it") }.body

    fun verify(body: Map<String, Any?>) = post("/api/auth/verify-code", body)

    fun seedAll(): JsonNode = ok(OWNER, "admin_demo_seed")

    fun seedSteps(): List<Int> {
        val seen = ArrayList<Int>()
        var step: Int? = 0
        while (step != null) {
            val r = ok(OWNER, "admin_demo_seed", mapOf("step" to step))
            seen.add(step)
            step = r["next"]?.takeIf { !it.isNull }?.intValue()
        }
        return seen
    }

    fun wipe() {
        repeat(5) {
            if (ok(OWNER, "admin_demo_wipe")["more"]?.booleanValue() != true) return
        }
    }

    /** "table.column" of every text value that still names a demo account (the admin log is kept on purpose) */
    fun demoLeft(): List<String> {
        val cols = jdbc.queryForList(
            """
            SELECT table_name, column_name FROM information_schema.columns
            WHERE table_schema = 'public' AND data_type IN ('text', 'character varying')
              AND table_name NOT LIKE 'flyway%' AND table_name NOT LIKE 'jobrunr%' AND table_name <> 'admin_log'
            ORDER BY table_name, column_name
            """.trimIndent(),
        )
        val out = ArrayList<String>()
        for (c in cols) {
            val t = c["table_name"] as String
            val col = c["column_name"] as String
            val n = jdbc.queryForObject("SELECT count(*) FROM \"$t\" WHERE \"$col\" LIKE 'demo-%'", Int::class.java) ?: 0
            if (n > 0) out.add("$t.$col")
        }
        if (identity.users.keys.any { DemoWorld.isDemoUid(it) }) out.add("auth users")
        return out
    }
}
