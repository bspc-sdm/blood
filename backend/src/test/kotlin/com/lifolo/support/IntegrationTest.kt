package com.lifolo.support

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lifolo.users.AccountService
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders

/**
 * Base class for HTTP-level tests: the real Spring app (security filter, controllers, Flyway schema) on
 * Testcontainers PostgreSQL 16 + Redis 7. Every test starts from empty tables and an empty Redis.
 * `call(uid, action, body)` mirrors the Node tests' helper (Bearer token = uid, body.lang defaults to "bn").
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestInfra::class)
abstract class IntegrationTest {
    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var mapper: ObjectMapper

    @Autowired
    lateinit var identity: FakeIdentityProvider

    @Autowired
    lateinit var mail: FakeMailSender

    @Autowired
    lateinit var push: RecordingPushTransport

    @Autowired
    lateinit var accounts: AccountService

    @Autowired
    lateinit var redis: StringRedisTemplate

    @BeforeEach
    fun cleanState() {
        val tables = jdbc.queryForList(
            "SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename NOT LIKE 'flyway%' AND tablename NOT LIKE 'jobrunr%'",
            String::class.java,
        )
        if (tables.isNotEmpty()) jdbc.execute("TRUNCATE ${tables.joinToString(", ") { "\"$it\"" }} CASCADE")
        redis.connectionFactory?.connection?.use { it.serverCommands().flushAll() }
        identity.reset()
        mail.sent.clear()
        mail.fail = false
        push.messages.clear()
        push.dead.clear()
        accounts.forgetAll()
    }

    class Res(val status: Int, val body: JsonNode, val response: MockHttpServletResponse) {
        val error: String? get() = body.get("error")?.textValue()
        val code: String? get() = body.get("code")?.textValue()
        override fun toString() = "$status ${body}"
    }

    fun post(path: String, body: Any?, headers: Map<String, String> = emptyMap()): Res {
        val req = MockMvcRequestBuilders.post(path)
            .contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(body))
        headers.forEach { (k, v) -> req.header(k, v) }
        return toRes(mvc.perform(req).andReturn().response)
    }

    fun get(path: String, headers: Map<String, String> = emptyMap()): Res {
        val req = MockMvcRequestBuilders.get(path)
        headers.forEach { (k, v) -> req.header(k, v) }
        return toRes(mvc.perform(req).andReturn().response)
    }

    private fun toRes(r: MockHttpServletResponse): Res {
        val text = r.getContentAsString(Charsets.UTF_8)
        return Res(r.status, if (text.isBlank()) mapper.nullNode() else mapper.readTree(text), r)
    }

    /** POST /api/community as [uid] (null = guest from [ip]) */
    fun call(
        uid: String?,
        action: String,
        body: Map<String, Any?> = emptyMap(),
        lang: String? = "bn",
        ip: String = "5.5.5.5",
        token: String? = uid,
    ): Res {
        val headers = if (token != null) mapOf("Authorization" to "Bearer $token") else mapOf("X-Forwarded-For" to ip)
        val payload = LinkedHashMap<String, Any?>()
        if (lang != null) payload["lang"] = lang
        payload["action"] = action
        payload.putAll(body)
        return post("/api/community", payload, headers)
    }

    /** a logged-in user: Auth account + accounts row (+ profile when [profile] is given) */
    fun user(uid: String, profile: Map<String, Any?>? = null, email: String = "$uid@example.com") {
        identity.add(uid, email)
        jdbc.update("INSERT INTO accounts (uid, email) VALUES (?, ?) ON CONFLICT DO NOTHING", uid, email)
        if (profile != null) {
            jdbc.update("INSERT INTO user_profiles (uid) VALUES (?) ON CONFLICT DO NOTHING", uid)
            for ((k, v) in profile) {
                if (v is List<*>) {
                    jdbc.update("UPDATE user_profiles SET $k = ?::text[] WHERE uid = ?", "{" + v.joinToString(",") { "\"$it\"" } + "}", uid)
                } else {
                    jdbc.update("UPDATE user_profiles SET $k = ? WHERE uid = ?", v, uid)
                }
            }
        }
    }

    fun row(sql: String, vararg args: Any?): Map<String, Any?> = jdbc.queryForList(sql, *args).firstOrNull() ?: emptyMap()
}
