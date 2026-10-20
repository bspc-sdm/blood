package com.lifolo.unit

import com.lifolo.common.B64Blob
import com.lifolo.common.LifoloProperties
import com.lifolo.common.ObjectStorage
import com.lifolo.common.StoredFiles
import com.lifolo.common.TextCache
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class StoredFilesTest {
    private val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(), 1, 2, 3)
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10)

    @Test
    fun `canonical base64 is stored as the bytes, anything else as the exact text`() {
        val j = Base64.getEncoder().encodeToString(jpeg)
        val b = B64Blob.encode(j)
        assertEquals(listOf("bytes", "image/jpeg", ".jpg"), listOf(b.encoding, b.contentType, B64Blob.extension(b)))
        assertEquals(jpeg.toList(), b.bytes.toList())
        assertEquals(j, B64Blob.decode(b.bytes, b.encoding))
        assertEquals("image/png", B64Blob.encode(Base64.getEncoder().encodeToString(png)).contentType)
        assertEquals("application/octet-stream", B64Blob.encode("QUJD").contentType)

        // accepted by the old checks but not canonical: no padding, whitespace, a data: prefix, not base64 at all
        for (t in listOf("QQ", "QUJD\nRA==", "data:image/jpeg;base64,/9j/", "A", "QQ=x")) {
            val x = B64Blob.encode(t)
            assertEquals(listOf("b64text", "text/plain; charset=utf-8", ".b64.txt"), listOf(x.encoding, x.contentType, B64Blob.extension(x)), t)
            assertEquals(t, B64Blob.decode(x.bytes, x.encoding), t)
        }
        assertEquals("b64text", B64Blob.encodingOf("profile/abc.b64.txt"))
        assertEquals("bytes", B64Blob.encodingOf("profile/abc.jpg"))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", B64Blob.sha256(ByteArray(0)))
        assertEquals(64, b.sha256.length)
    }

    @Test
    fun `R2 endpoint - R2_ENDPOINT first, the dashboard URL with the bucket works too, all four needed`() {
        val base = LifoloProperties.R2(accessKeyId = "k", secretAccessKey = "s", bucket = "lifolo-files")
        assertEquals("https://abc.r2.cloudflarestorage.com", base.copy(endpoint = "https://abc.r2.cloudflarestorage.com/lifolo-files").resolvedEndpoint)
        assertEquals("https://abc.r2.cloudflarestorage.com", base.copy(endpoint = "https://abc.r2.cloudflarestorage.com/", accountId = "zzz").resolvedEndpoint)
        assertEquals("https://zzz.r2.cloudflarestorage.com", base.copy(accountId = "zzz").resolvedEndpoint)
        assertTrue(base.copy(endpoint = "https://abc.r2.cloudflarestorage.com").configured)
        assertEquals(false, base.configured)
        assertEquals(false, base.copy(endpoint = "https://x", bucket = "").configured)
        assertEquals(false, LifoloProperties().r2.configured)
    }

    @Test
    fun `the cache is an LRU bounded by size`() {
        val c = TextCache(100)
        c.put("a", "x".repeat(20))
        c.put("b", "x".repeat(20))
        c.get("a")
        c.put("c", "x".repeat(20))
        c.put("d", "x".repeat(20))
        c.put("e", "x".repeat(20))
        assertEquals(100, c.size())
        c.put("f", "x".repeat(20)) // "b" is the least recently used
        assertNull(c.get("b"))
        assertEquals(20, c.get("a")?.length)
        c.put("big", "x".repeat(26)) // over a quarter of the cache: not cached
        assertNull(c.get("big"))
        c.remove("a")
        assertEquals(80, c.size())
    }

    private class MapStorage : ObjectStorage {
        val map = ConcurrentHashMap<String, ByteArray>()
        val gets = AtomicInteger()
        override val configured = true
        override fun put(key: String, bytes: ByteArray, contentType: String) {
            map[key] = bytes
        }
        override fun get(key: String): ByteArray? {
            gets.incrementAndGet()
            if (key.contains("broken")) throw IllegalStateException("network")
            return map[key]
        }
        override fun delete(key: String) {
            map.remove(key)
        }
    }

    @Test
    fun `texts - parallel reads, cached, missing and failing keys left out`() {
        val s = MapStorage()
        val files = StoredFiles(s, mockk<JdbcTemplate>(relaxed = true), LifoloProperties())
        val want = HashMap<String, String>()
        for (i in 0 until 40) {
            val bytes = jpeg + byteArrayOf(i.toByte())
            s.map["profile-thumb/$i.jpg"] = bytes
            want["profile-thumb/$i.jpg"] = Base64.getEncoder().encodeToString(bytes)
        }
        s.map["x/t.b64.txt"] = "QQ".toByteArray()
        want["x/t.b64.txt"] = "QQ"
        val got = files.texts(want.keys + "x/missing.jpg" + "x/broken.jpg")
        assertEquals(want, got)
        val n = s.gets.get()
        assertEquals(want, files.texts(want.keys))
        assertEquals(n, s.gets.get(), "second read from the cache")
        assertEquals("from the row", files.text("from the row", "x/t.b64.txt"))
        assertEquals("QQ", files.text(null, "x/t.b64.txt"))
        assertNull(files.text(null, null))
        assertTrue(files.enabled)
    }
}
