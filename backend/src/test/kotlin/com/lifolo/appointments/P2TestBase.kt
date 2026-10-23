package com.lifolo.appointments

import com.fasterxml.jackson.databind.JsonNode
import com.lifolo.common.DhakaTime
import com.lifolo.common.Places
import com.lifolo.support.IntegrationTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDateTime
import java.time.ZoneOffset

/** helpers of the P2 integration tests (the Node tests' seed/call/pushes helpers) */
abstract class P2TestBase : IntegrationTest() {
    @Autowired lateinit var clock: P2Clock

    @Autowired lateinit var providerStore: ProviderStore

    @BeforeEach
    fun resetClock() {
        clock.fixedMs = null
    }

    @AfterEach
    fun unsetClock() {
        clock.fixedMs = null
    }

    /** a 200 answer's body */
    fun ok(uid: String?, action: String, body: Map<String, Any?> = emptyMap(), lang: String? = "bn"): JsonNode {
        val r = call(uid, action, body, lang)
        assertEquals(200, r.status, "$action: $r")
        return r.body
    }

    /** a person with a profile and a phone (device token) */
    fun person(uid: String, name: String = uid, email: String = "$uid@example.com", lang: String = "bn", extra: Map<String, Any?> = emptyMap()) {
        user(uid, mapOf("name" to name, "lang" to lang) + extra, email)
        jdbc.update("INSERT INTO device_tokens (token, uid) VALUES (?, ?) ON CONFLICT DO NOTHING", "tok_$uid", uid)
    }

    fun badge(uid: String, type: String = "doctor", name: String = uid, status: String = "approved") {
        jdbc.update(
            """
            INSERT INTO badges (uid, type, reg_no, workplace, name, email, status) VALUES (?, ?, 'A-1', 'X', ?, '', ?)
            ON CONFLICT (uid) DO UPDATE SET type = EXCLUDED.type, name = EXCLUDED.name, status = EXCLUDED.status
            """.trimIndent(),
            uid, type, name, status,
        )
    }

    /** providers/{uid} with chambers[] */
    fun provider(
        uid: String,
        chambers: List<Ch>,
        type: String = "doctor",
        name: String = uid,
        listed: Boolean = true,
        appointments: Boolean = true,
        specialties: List<String> = listOf("medicine"),
        gender: String? = null,
        assistants: List<String> = emptyList(),
    ) {
        jdbc.update(
            """
            INSERT INTO providers (uid, type, name, listed, appointments, active, specialties, gender, degrees, legacy_chamber)
            VALUES (?, ?, ?, ?, ?, ?, ?::text[], ?, 'MBBS', FALSE)
            """.trimIndent(),
            uid, type, name, listed, appointments, listed && appointments, P2Js.pgTexts(specialties), gender,
        )
        providerStore.saveChambers(uid, chambers)
        assistants.forEachIndexed { i, e -> jdbc.update("INSERT INTO provider_assistants (provider_uid, email, position) VALUES (?, ?, ?)", uid, e, i) }
    }

    /** the bell list of a user (every push writes it): title to body, oldest first */
    fun inbox(uid: String): List<Pair<String, String>> =
        jdbc.query("SELECT title, body FROM inbox_items WHERE uid = ? ORDER BY created_at, id", { rs, _ -> rs.getString(1) to rs.getString(2) }, uid)

    fun pushesTo(uid: String, re: String? = null): List<Pair<String, String>> =
        inbox(uid).filter { re == null || Regex(re).containsMatchIn(it.first) }

    /** support/clock.setDhaka("2026-10-19 23:50") */
    fun dhaka(s: String): Long = LocalDateTime.parse(s.replace(' ', 'T')).toInstant(ZoneOffset.ofHours(6)).toEpochMilli()

    fun today(now: Long = clock.now()): Long = DhakaTime.todayUtcMidnight(now)

    fun district(en: String): String = Places.DISTRICTS.first { it.en == en }.bn

    /** Lifolo Pay: a business's payment numbers have been active for 2 days (patients only ever see matured numbers, P4) */
    fun matureNumbers(bizId: String) {
        jdbc.update("UPDATE pay_methods SET active_since = active_since - interval '2 days' WHERE biz_id = ? AND active_since IS NOT NULL", bizId)
    }

    fun hhmm(ms: Long): String = com.lifolo.admin.kernel.ListingRules.dhakaHHMM(ms)
}
