package com.lifolo.common

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

/**
 * Base64 photos (profile photo/thumb, verification documents, lab report photos) on R2, with only a reference in
 * PostgreSQL (V602__r2_files.sql). The API keeps its shape: clients send base64 and get the same base64 text back.
 *
 *  - [enabled] = R2 configured. Off → callers keep writing base64 into the old column, exactly like before.
 *  - [put] uploads the text (decoded to bytes when it is canonical base64, else the text itself — see [B64Blob])
 *    and inserts its `stored_objects` row; if the surrounding transaction rolls back, the object is deleted again.
 *  - [text] / [texts] read: the old column first (rows not migrated yet, or written while R2 was off), else the key;
 *    results are cached in memory (keys never change their content: a new photo gets a new key).
 *  - [sweep] deletes the R2 objects whose keys the database put into `stored_objects_trash` (triggers).
 */
@Service
class StoredFiles(
    private val storage: ObjectStorage,
    private val jdbc: JdbcTemplate,
    props: LifoloProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val cache = TextCache(props.r2.cacheMb.coerceAtLeast(0).toLong() * 1024 * 1024)

    val enabled: Boolean get() = storage.configured

    /**
     * a new random key `{area}/{uuid}{ext}` (no user data in it); the extension tells how to read it back
     * (`.b64.txt` = the text itself, anything else = bytes → base64), so reads need no database lookup
     */
    fun newKey(area: String, blob: B64Blob.Blob): String = "$area/${UUID.randomUUID()}${B64Blob.extension(blob)}"

    /** uploads [text] to R2 (only when [enabled]) and returns its key */
    fun put(area: String, text: String): String {
        check(enabled) { "R2 is not configured" }
        val blob = B64Blob.encode(text)
        val key = newKey(area, blob)
        storage.put(key, blob.bytes, blob.contentType)
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCompletion(status: Int) {
                    if (status != TransactionSynchronization.STATUS_COMMITTED) {
                        cache.remove(key)
                        deleteQuietly(key)
                    }
                }
            })
        }
        try {
            insertRow(key, blob)
        } catch (e: RuntimeException) {
            deleteQuietly(key)
            throw e
        }
        cache.put(key, text)
        return key
    }

    fun insertRow(key: String, blob: B64Blob.Blob) {
        jdbc.update(
            "INSERT INTO stored_objects (key, size, content_type, sha256, encoding) VALUES (?, ?, ?, ?, ?)",
            key, blob.bytes.size, blob.contentType, blob.sha256, blob.encoding,
        )
    }

    /** the base64 text of a photo column pair: [data] when the row still has it, else the object of [key] */
    fun text(data: String?, key: String?): String? = data ?: key?.let { get(it) }

    /** the texts of many keys (missing/unreadable ones are left out); R2 reads run in parallel */
    fun texts(keys: Collection<String>): Map<String, String> {
        val out = HashMap<String, String>()
        val missing = ArrayList<String>()
        for (k in keys.toSet()) {
            val hit = cache.get(k)
            if (hit != null) out[k] = hit else missing += k
        }
        if (missing.isEmpty()) return out
        if (missing.size == 1) {
            get(missing[0])?.let { out[missing[0]] = it }
            return out
        }
        val limit = Semaphore(PARALLEL_READS)
        Executors.newVirtualThreadPerTaskExecutor().use { ex ->
            val futures = missing.map { k ->
                k to ex.submit(
                    Callable {
                        limit.acquire()
                        try {
                            this@StoredFiles.get(k)
                        } finally {
                            limit.release()
                        }
                    },
                )
            }
            for ((k, f) in futures) f.get()?.let { out[k] = it }
        }
        return out
    }

    /** one object as base64 text (cached); null when it is missing, R2 is off or the read failed (logged) */
    fun get(key: String): String? {
        cache.get(key)?.let { return it }
        if (!enabled) {
            log.error("photo {} is on R2 but R2 is not configured", key)
            return null
        }
        val bytes = try {
            storage.get(key)
        } catch (e: Exception) {
            log.error("R2 read of {} failed", key, e)
            return null
        }
        if (bytes == null) {
            log.error("R2 object {} is missing", key)
            return null
        }
        val text = B64Blob.decode(bytes, B64Blob.encodingOf(key))
        cache.put(key, text)
        return text
    }

    /** deletes the R2 objects of `stored_objects_trash` (keys released by deleted/changed rows); returns how many */
    fun sweep(limit: Int = 500): Int {
        if (!enabled) return 0
        val keys = jdbc.queryForList(
            "SELECT key FROM stored_objects_trash WHERE attempts < ? ORDER BY at LIMIT ?", String::class.java, MAX_ATTEMPTS, limit,
        )
        var done = 0
        for (k in keys) {
            cache.remove(k)
            try {
                // a key in the trash is never used again (keys are random), so deleting after commit is always safe
                storage.delete(k)
                jdbc.update("DELETE FROM stored_objects_trash WHERE key = ?", k)
                done++
            } catch (e: Exception) {
                log.warn("R2 delete of {} failed: {}", k, e.message)
                jdbc.update(
                    "UPDATE stored_objects_trash SET attempts = attempts + 1, last_error = ? WHERE key = ?", (e.message ?: e.javaClass.name).take(500), k,
                )
            }
        }
        return done
    }

    fun deleteQuietly(key: String) {
        try {
            storage.delete(key)
        } catch (e: Exception) {
            log.warn("R2 delete of {} failed: {}", key, e.message)
        }
    }

    fun forget(key: String) = cache.remove(key)

    companion object {
        const val PARALLEL_READS = 16
        const val MAX_ATTEMPTS = 50
    }
}

/**
 * How a base64 text is kept as an object. Canonical base64 (what Android and the web send) is stored as the decoded
 * bytes (a real JPEG/PNG in the bucket) and comes back as the identical text; anything else that the old validation
 * accepted (a `data:` prefix, whitespace, missing padding …) is stored as the text itself, so reads always return
 * exactly what was saved.
 */
object B64Blob {
    const val BYTES = "bytes"
    const val TEXT = "b64text"

    class Blob(val bytes: ByteArray, val contentType: String, val encoding: String) {
        val sha256: String = sha256(bytes)
    }

    fun encode(text: String): Blob {
        val decoded = try {
            Base64.getDecoder().decode(text)
        } catch (e: IllegalArgumentException) {
            null
        }
        return if (decoded != null && decoded.isNotEmpty() && Base64.getEncoder().encodeToString(decoded) == text) {
            Blob(decoded, sniff(decoded), BYTES)
        } else {
            Blob(text.toByteArray(Charsets.UTF_8), "text/plain; charset=utf-8", TEXT)
        }
    }

    private const val TEXT_EXT = ".b64.txt"

    fun extension(blob: Blob): String = if (blob.encoding == TEXT) {
        TEXT_EXT
    } else {
        when (blob.contentType) {
            "image/jpeg" -> ".jpg"
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            "image/gif" -> ".gif"
            else -> ".bin"
        }
    }

    fun encodingOf(key: String): String = if (key.endsWith(TEXT_EXT)) TEXT else BYTES

    fun decode(bytes: ByteArray, encoding: String): String =
        if (encoding == TEXT) String(bytes, Charsets.UTF_8) else Base64.getEncoder().encodeToString(bytes)

    fun sniff(b: ByteArray): String = when {
        b.size >= 3 && b[0] == 0xff.toByte() && b[1] == 0xd8.toByte() && b[2] == 0xff.toByte() -> "image/jpeg"
        b.size >= 4 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() -> "image/png"
        b.size >= 12 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "image/webp"
        b.size >= 4 && String(b, 0, 4, Charsets.ISO_8859_1) == "GIF8" -> "image/gif"
        else -> "application/octet-stream"
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/** LRU of base64 texts bounded by total characters (≈ bytes: the texts are ASCII, stored one byte per char) */
class TextCache(private val maxChars: Long) {
    private var size = 0L
    private val map = object : LinkedHashMap<String, String>(256, 0.75f, true) {}

    @Synchronized
    fun get(key: String): String? = map[key]

    @Synchronized
    fun put(key: String, text: String) {
        if (text.length > maxChars / 4) return
        map.put(key, text)?.let { size -= it.length }
        size += text.length
        val it = map.entries.iterator()
        while (size > maxChars && it.hasNext()) {
            val e = it.next()
            size -= e.value.length
            it.remove()
        }
    }

    @Synchronized
    fun remove(key: String) {
        map.remove(key)?.let { size -= it.length }
    }

    @Synchronized
    fun size(): Long = size
}
