package com.lifolo.support

import com.lifolo.features.Features
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Tests of a gated feature (docs/FEATURES.md). The test profile has every key on; to see the off/pilot behaviour:
 *
 * ```
 * @Autowired lateinit var features: Features
 * @AfterEach fun allOnAgain() { features.allOn = true }
 * …
 * features.allOn = false
 * setFeature(jdbc, features, Features.CHAT, "pilot", listOf("u1"))
 * ```
 * (a row in feature_flags + a history row so the version moves; the cache is cleared)
 */
fun setFeature(jdbc: JdbcTemplate, features: Features, key: String, mode: String, pilots: List<String> = emptyList(), demo: Boolean = false) {
    val arr = "{" + pilots.joinToString(",") { "\"$it\"" } + "}"
    jdbc.update(
        """
        INSERT INTO feature_flags (key, mode, pilot_uids, demo) VALUES (?, ?, ?::text[], ?)
        ON CONFLICT (key) DO UPDATE SET mode = EXCLUDED.mode, pilot_uids = EXCLUDED.pilot_uids, demo = EXCLUDED.demo
        """.trimIndent(),
        key, mode, arr, demo,
    )
    jdbc.update("INSERT INTO feature_changes (key, mode, pilot_uids, demo) VALUES (?, ?, ?::text[], ?)", key, mode, arr, demo)
    features.clearCache()
}
