package com.lifolo.common

import jakarta.servlet.http.HttpServletRequest
import org.jobrunr.jobs.annotations.Job
import org.jobrunr.jobs.annotations.Recurring
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-off move of the base64 photos already in PostgreSQL to R2 (V602). Idempotent and resumable: each call moves
 * up to `limit` rows that still have base64 and stops; call again until `remaining` is all 0. Per row:
 * upload → read back → SHA-256 must match → in one transaction insert `stored_objects` and set the key + clear the
 * base64 column, only if the row still holds the same text (a photo changed meanwhile → the upload is deleted and
 * the row is tried again next time). A failed row keeps its base64 and is only counted.
 */
@Service
class StorageMigrator(
    private val jdbc: JdbcTemplate,
    private val storage: ObjectStorage,
    private val files: StoredFiles,
    txm: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(txm)
    private val running = AtomicBoolean(false)

    /** a base64 column and the key column that replaces it */
    data class Source(val name: String, val table: String, val pk: List<String>, val data: String, val key: String, val area: String)

    data class MigrateResult(val migrated: Int, val failed: Int, val changed: Int, val busy: Boolean = false)

    fun status(): Map<String, Any?> = linkedMapOf(
        "configured" to files.enabled,
        "remaining" to remaining(),
        "stored" to (jdbc.queryForObject("SELECT count(*) FROM stored_objects", Long::class.java) ?: 0L),
        "trash" to (jdbc.queryForObject("SELECT count(*) FROM stored_objects_trash", Long::class.java) ?: 0L),
    )

    fun remaining(): Map<String, Long> = SOURCES.associate { s ->
        s.name to (jdbc.queryForObject("SELECT count(*) FROM ${s.table} WHERE ${s.data} IS NOT NULL AND ${s.data} <> ''", Long::class.java) ?: 0L)
    }

    /** moves up to [limit] rows (all sources, in order); null when R2 is not configured */
    fun migrate(limit: Int): MigrateResult? {
        if (!files.enabled) return null
        if (!running.compareAndSet(false, true)) return MigrateResult(0, 0, 0, busy = true)
        try {
            var migrated = 0
            var failed = 0
            var changed = 0
            var budget = limit
            for (s in SOURCES) {
                var cursor: List<Any?>? = null
                while (budget > 0) {
                    val page = page(s, cursor, minOf(budget, PAGE))
                    if (page.isEmpty()) break
                    for (row in page) {
                        budget--
                        when (moveRow(s, row)) {
                            Outcome.MOVED -> migrated++
                            Outcome.FAILED -> failed++
                            Outcome.CHANGED -> changed++
                        }
                    }
                    cursor = s.pk.map { page.last()[it] }
                }
            }
            log.info("R2 migration: {} moved, {} failed, {} changed meanwhile", migrated, failed, changed)
            return MigrateResult(migrated, failed, changed)
        } finally {
            running.set(false)
        }
    }

    private fun page(s: Source, cursor: List<Any?>?, n: Int): List<Map<String, Any?>> {
        val cols = s.pk.joinToString(", ")
        val after = if (cursor == null) "" else " AND ($cols) > (${s.pk.joinToString(", ") { "?" }})"
        return jdbc.queryForList(
            "SELECT $cols, ${s.data} AS data FROM ${s.table} WHERE ${s.data} IS NOT NULL AND ${s.data} <> ''$after ORDER BY $cols LIMIT ?",
            *((cursor ?: emptyList()) + n).toTypedArray(),
        )
    }

    enum class Outcome { MOVED, FAILED, CHANGED }

    fun moveRow(s: Source, row: Map<String, Any?>): Outcome {
        val text = row["data"] as String
        val blob = B64Blob.encode(text)
        val key = files.newKey(s.area, blob)
        try {
            storage.put(key, blob.bytes, blob.contentType)
            val back = storage.get(key)
            if (back == null || B64Blob.sha256(back) != blob.sha256) {
                log.error("R2 migration: {} {} read back differently, kept in the database", s.name, key)
                files.deleteQuietly(key)
                return Outcome.FAILED
            }
        } catch (e: Exception) {
            log.error("R2 migration: upload of a {} failed, kept in the database", s.name, e)
            files.deleteQuietly(key)
            return Outcome.FAILED
        }
        val done = try {
            tx.execute {
                files.insertRow(key, blob)
                val where = s.pk.joinToString(" AND ") { "$it = ?" }
                val n = jdbc.update(
                    "UPDATE ${s.table} SET ${s.data} = NULL, ${s.key} = ? WHERE $where AND ${s.data} = ?",
                    *(listOf<Any?>(key) + s.pk.map { row[it] } + text).toTypedArray(),
                )
                if (n != 1) it.setRollbackOnly()
                n == 1
            } == true
        } catch (e: Exception) {
            log.error("R2 migration: database update of a {} failed", s.name, e)
            files.deleteQuietly(key)
            return Outcome.FAILED
        }
        if (!done) {
            files.deleteQuietly(key)
            return Outcome.CHANGED
        }
        return Outcome.MOVED
    }

    companion object {
        const val PAGE = 50

        val SOURCES = listOf(
            Source("profilePhoto", "user_profiles", listOf("uid"), "photo", "photo_key", "profile"),
            Source("profileThumb", "user_profiles", listOf("uid"), "photo_thumb", "photo_thumb_key", "profile-thumb"),
            Source("verifyDocs", "verify_docs", listOf("kind", "listing_id", "type"), "data", "storage_key", "verify"),
            Source("labReportPhotos", "lab_report_photos", listOf("order_id", "idx"), "data", "storage_key", "lab-report"),
        )
    }
}

/**
 * Admin/ops endpoints (`Authorization: Bearer $CRON_SECRET`, like /api/cron/daily):
 *  - GET  /api/cron/storage → {configured, remaining: {profilePhoto, profileThumb, verifyDocs, labReportPhotos}, stored, trash}
 *  - POST /api/cron/storage/migrate?limit=200 → moves up to `limit` (1–2000) rows to R2, then the same status plus
 *    {migrated, failed, changed}; 409 while another call runs, 400 when R2 is not configured.
 */
@RestController
class StorageController(
    private val migrator: StorageMigrator,
    private val files: StoredFiles,
    private val props: LifoloProperties,
) {
    private fun authorized(req: HttpServletRequest): Boolean {
        val secret = props.cronSecret
        return secret.isNotBlank() && req.getHeader("Authorization") == "Bearer $secret"
    }

    @RequestMapping("/api/cron/storage")
    fun status(req: HttpServletRequest): ResponseEntity<Any> {
        if (req.method != "GET") return ResponseEntity.status(405).body(mapOf("error" to "Method not allowed"))
        if (!authorized(req)) throw ApiException(401, "অনুমতি নেই")
        return ResponseEntity.ok(migrator.status())
    }

    @RequestMapping("/api/cron/storage/migrate")
    fun migrate(req: HttpServletRequest): ResponseEntity<Any> {
        if (req.method != "POST") return ResponseEntity.status(405).body(mapOf("error" to "Method not allowed"))
        if (!authorized(req)) throw ApiException(401, "অনুমতি নেই")
        if (!files.enabled) return ResponseEntity.status(400).body(mapOf("error" to "R2 is not configured"))
        val limit = req.getParameter("limit")?.toIntOrNull()?.coerceIn(1, 2000) ?: 200
        val r = migrator.migrate(limit)!!
        if (r.busy) return ResponseEntity.status(409).body(mapOf("error" to "A migration is already running"))
        val out = linkedMapOf<String, Any?>("ok" to true, "migrated" to r.migrated, "failed" to r.failed, "changed" to r.changed)
        out.putAll(migrator.status())
        files.sweep()
        return ResponseEntity.ok(out)
    }
}

/** every minute: delete the R2 objects whose rows were deleted or replaced (nothing to do while R2 is off) */
@Component
class StorageJobs(private val files: StoredFiles) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Recurring(id = "storage-trash", cron = "* * * * *", zoneId = "UTC")
    @Job(name = "Delete released R2 objects")
    fun sweep() {
        if (!files.enabled) return
        try {
            files.sweep()
        } catch (e: Exception) {
            log.error("R2 trash sweep failed", e)
        }
    }
}
