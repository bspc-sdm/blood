package com.lifolo.common

import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.Ordered
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource
import java.net.URI
import java.net.URLDecoder

/**
 * Accepts DATABASE_URL in the usual `postgres://user:pass@host:5432/db?sslmode=…` form (what the VPS scripts
 * write) and turns it into spring.datasource.* — unless SPRING_DATASOURCE_URL is given explicitly.
 */
class DatabaseUrlEnvironmentPostProcessor : EnvironmentPostProcessor, Ordered {
    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        val raw = environment.getProperty("DATABASE_URL")?.trim().orEmpty()
        if (raw.isEmpty() || !environment.getProperty("SPRING_DATASOURCE_URL").isNullOrBlank()) return
        val props = toDatasourceProperties(raw) ?: return
        environment.propertySources.addFirst(MapPropertySource("databaseUrl", props))
    }

    override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE

    companion object {
        fun toDatasourceProperties(raw: String): Map<String, Any>? {
            if (raw.startsWith("jdbc:")) return mapOf("spring.datasource.url" to raw)
            val uri = try {
                URI(raw)
            } catch (e: Exception) {
                return null
            }
            if (uri.scheme != "postgres" && uri.scheme != "postgresql") return null
            val port = if (uri.port > 0) uri.port else 5432
            val query = uri.rawQuery?.let { "?$it" } ?: ""
            val out = mutableMapOf<String, Any>("spring.datasource.url" to "jdbc:postgresql://${uri.host}:$port${uri.rawPath}$query")
            uri.rawUserInfo?.let { info ->
                val parts = info.split(":", limit = 2)
                out["spring.datasource.username"] = URLDecoder.decode(parts[0], Charsets.UTF_8)
                if (parts.size > 1) out["spring.datasource.password"] = URLDecoder.decode(parts[1], Charsets.UTF_8)
            }
            return out
        }
    }
}
