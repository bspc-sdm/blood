package com.lifolo.common

import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * /api/health (api/health.js): any method; checks the database. The key `firebase` is kept for old uptime
 * checks (Node checked Firestore). Spring Actuator also exposes /actuator/health for the VPS.
 */
@RestController
class HealthController(private val jdbc: JdbcTemplate) {
    private val log = LoggerFactory.getLogger(javaClass)

    @RequestMapping("/api/health")
    fun health(): ResponseEntity<Map<String, Any>> = try {
        jdbc.queryForObject("SELECT 1", Int::class.java)
        ResponseEntity.ok(linkedMapOf("ok" to true, "firebase" to true, "db" to true))
    } catch (e: Exception) {
        log.error("health check failed", e)
        ResponseEntity.status(500).body(mapOf("ok" to false))
    }
}
