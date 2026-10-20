package com.lifolo.common

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.cache.RedisCacheConfiguration
import java.time.Duration

@Configuration
class InfraConfig {
    /** Cache TTLs (Redis). Node equivalents: admin list 10 min (admin.js), catalog 5 min, providers 2 min … */
    @Bean
    fun cacheTtls(): RedisCacheManagerBuilderCustomizer = RedisCacheManagerBuilderCustomizer { builder ->
        builder
            .withCacheConfiguration("admins", RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofMinutes(10)))
            .withCacheConfiguration("catalog", RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofMinutes(5)))
    }

    @Bean
    fun openApi(): OpenAPI = OpenAPI().info(
        Info()
            .title("Lifolo API")
            .version("phase-1")
            .description(
                "Compatibility API of the Node server. Most features are POST /api/community with an `action` field " +
                    "— see backend/docs/API-INVENTORY.md for every action.",
            ),
    )
}
