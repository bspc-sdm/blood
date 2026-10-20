package com.lifolo.security

import com.lifolo.common.Phones
import com.lifolo.demo.DemoTestBase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The demo world is shown to real people (lists are shared). Its phone numbers must never ring a real person: every
 * number the demo writes is in 0120000xxxx (no operator issues 012), and the server accepts that range so the demo
 * flows keep working.
 */
class DemoPhonesIT : DemoTestBase() {
    private val operatorNumber = "(^|[^0-9])(\\+?88)?01[3-9][0-9]{8}([^0-9]|$)"
    private val operatorNumberBn = "০১[৩-৯][০-৯]{8}"

    /** "table.column: value" of every text value that holds an operator mobile number */
    private fun operatorNumbers(): Set<String> {
        val cols = jdbc.queryForList(
            """
            SELECT table_name, column_name FROM information_schema.columns
            WHERE table_schema = 'public' AND data_type IN ('text', 'character varying')
              AND table_name NOT LIKE 'flyway%' AND table_name NOT LIKE 'jobrunr%'
            ORDER BY table_name, column_name
            """.trimIndent(),
        )
        val out = HashSet<String>()
        for (c in cols) {
            val t = c["table_name"] as String
            val col = c["column_name"] as String
            jdbc.queryForList(
                "SELECT \"$col\" AS v FROM \"$t\" WHERE \"$col\" ~ ? OR \"$col\" ~ ? LIMIT 20", String::class.java, operatorNumber, operatorNumberBn,
            ).forEach { out += "$t.$col: ${it.take(80)}" }
        }
        return out
    }

    @Test
    fun `the seeded demo world has no real-looking phone numbers`() {
        owner()
        val before = operatorNumbers()
        seedAll()
        assertEquals(emptySet<String>(), operatorNumbers() - before)
        // and its numbers are usable in the flows (contact numbers, rx claim)
        assertNotNull(Phones.normalizeBd("01200000150"))
        assertNotNull(Phones.normalizeBd("+8801200001101"))
        assertNull(Phones.normalizeBd("01212345678"))
        assertNull(Phones.normalizeBd("01100000150"))
    }
}
