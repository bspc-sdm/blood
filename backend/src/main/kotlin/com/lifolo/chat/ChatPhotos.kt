package com.lifolo.chat

import com.lifolo.common.ApiException
import com.lifolo.common.Lang
import com.lifolo.common.LifoloProperties
import com.lifolo.common.ObjectStorage
import org.slf4j.LoggerFactory
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream

/**
 * Chat photos (backend/docs/CHAT.md): the sent base64 JPEG/PNG is decoded, turned upright from its EXIF orientation,
 * scaled down and written as a brand-new JPEG — so no EXIF (GPS, camera, time) or any other metadata survives — plus
 * a small inline thumbnail. The full photo is served only through short-lived signed URLs ([url] / [ChatPhotoController]).
 */
@Component
class ChatPhotos(props: LifoloProperties) {
    class Processed(val jpeg: ByteArray, val thumbB64: String, val width: Int, val height: Int)

    class BadPhoto(msg: String) : RuntimeException(msg)

    private val key: ByteArray = props.webSessionSecret.takeIf { it.isNotBlank() }
        ?.let { hmac("lifolo-chat-photo-v1".toByteArray(), it.toByteArray()) }
        // no secret configured (local runs): a per-process key — URLs simply stop working after a restart
        ?: ByteArray(32).also { SecureRandom().nextBytes(it) }

    private val base = props.publicBaseUrl.trimEnd('/')

    // ------------------------------------------------------------------ processing

    fun process(b64: String): Processed {
        val bytes = try {
            Base64.getDecoder().decode(b64.substringAfter("base64,").trim())
        } catch (e: IllegalArgumentException) {
            throw BadPhoto("not base64")
        }
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) throw BadPhoto("size ${bytes.size}")
        val jpeg = bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte()
        val png = bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()
        if (!jpeg && !png) throw BadPhoto("not jpeg/png")
        val orientation = if (jpeg) exifOrientation(bytes) else 1
        val img = try {
            decode(bytes)
        } catch (e: BadPhoto) {
            throw e
        } catch (e: Exception) {
            null
        } ?: throw BadPhoto("unreadable")
        val full = orient(scaleToFit(img, FULL_EDGE), orientation)
        val thumb = scaleToFit(full, THUMB_EDGE)
        return Processed(encode(full, 0.82f), Base64.getEncoder().encodeToString(encode(thumb, 0.6f)), full.width, full.height)
    }

    /** decodes with source subsampling for huge images (bounded memory), into opaque RGB */
    private fun decode(bytes: ByteArray): BufferedImage? {
        val input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes)) ?: return null
        input.use { stream ->
            val readers = ImageIO.getImageReaders(stream)
            if (!readers.hasNext()) return null
            val reader = readers.next()
            try {
                reader.input = stream
                val (w, h) = try {
                    reader.getWidth(0) to reader.getHeight(0)
                } catch (e: Exception) {
                    return null
                }
                if (w <= 0 || h <= 0 || w.toLong() * h > MAX_PIXELS) throw BadPhoto("dimensions ${w}x$h")
                val param = reader.defaultReadParam
                val step = maxOf(1, maxOf(w, h) / (FULL_EDGE * 2))
                if (step > 1) param.setSourceSubsampling(step, step, 0, 0)
                val src = try {
                    reader.read(0, param)
                } catch (e: Exception) {
                    return null
                }
                return rgb(src)
            } finally {
                reader.dispose()
            }
        }
    }

    private fun rgb(src: BufferedImage): BufferedImage {
        if (src.type == BufferedImage.TYPE_INT_RGB) return src
        val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, src.width, src.height)
        g.drawImage(src, 0, 0, null)
        g.dispose()
        return out
    }

    /** halves while far too big, then one bilinear step to fit [edge] (never enlarges) */
    private fun scaleToFit(src: BufferedImage, edge: Int): BufferedImage {
        var img = src
        while (maxOf(img.width, img.height) >= edge * 2) img = resize(img, maxOf(1, img.width / 2), maxOf(1, img.height / 2))
        val long = maxOf(img.width, img.height)
        if (long <= edge) return img
        val f = edge.toDouble() / long
        return resize(img, maxOf(1, Math.round(img.width * f).toInt()), maxOf(1, Math.round(img.height * f).toInt()))
    }

    private fun resize(src: BufferedImage, w: Int, h: Int): BufferedImage {
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(src, 0, 0, w, h, null)
        g.dispose()
        return out
    }

    /** EXIF orientation 1…8 applied to the pixels (the tag itself is not written back) */
    fun orient(src: BufferedImage, o: Int): BufferedImage {
        if (o !in 2..8) return src
        val w = src.width
        val h = src.height
        val swap = o >= 5
        val out = BufferedImage(if (swap) h else w, if (swap) w else h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val (nx, ny) = when (o) {
                    2 -> (w - 1 - x) to y
                    3 -> (w - 1 - x) to (h - 1 - y)
                    4 -> x to (h - 1 - y)
                    5 -> y to x
                    6 -> (h - 1 - y) to x
                    7 -> (h - 1 - y) to (w - 1 - x)
                    else -> y to (w - 1 - x) // 8
                }
                out.setRGB(nx, ny, src.getRGB(x, y))
            }
        }
        return out
    }

    private fun encode(img: BufferedImage, quality: Float): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        try {
            MemoryCacheImageOutputStream(out).use { ios ->
                writer.output = ios
                val p = writer.defaultWriteParam
                p.compressionMode = ImageWriteParam.MODE_EXPLICIT
                p.compressionQuality = quality
                // no metadata argument: only the standard JFIF header is written
                writer.write(null, IIOImage(img, null, null), p)
            }
        } finally {
            writer.dispose()
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ signed URLs

    /** `{base}/api/chat/photo/{id}?e=…&s=…`, valid 60–70 minutes (expiry rounded to 10 min so a page's URLs stay stable) */
    fun url(messageId: String, nowMs: Long): String {
        val exp = ((nowMs / 1000 + URL_TTL_S) / 600 + 1) * 600
        return "$base/api/chat/photo/$messageId?e=$exp&s=${sign(messageId, exp)}"
    }

    fun sign(messageId: String, expSec: Long): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(key, "$messageId:$expSec".toByteArray())).take(32)

    fun verify(messageId: String, expSec: Long?, sig: String?, nowMs: Long): Boolean {
        if (expSec == null || sig == null || expSec * 1000 < nowMs) return false
        if (expSec - nowMs / 1000 > URL_TTL_S + 1200) return false
        return MessageDigest.isEqual(sign(messageId, expSec).toByteArray(), sig.toByteArray())
    }

    companion object {
        const val MAX_BYTES = 1024 * 1024
        const val MAX_PIXELS = 50_000_000L
        const val FULL_EDGE = 1600
        const val THUMB_EDGE = 160
        const val URL_TTL_S = 3600L

        private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

        /** the EXIF orientation of a JPEG (APP1 "Exif" → IFD0 tag 0x0112), 1 when absent or unreadable */
        fun exifOrientation(b: ByteArray): Int {
            try {
                var i = 2
                while (i + 4 <= b.size && b[i] == 0xff.toByte()) {
                    val marker = b[i + 1].toInt() and 0xff
                    if (marker == 0xda || marker == 0xd9) return 1
                    val len = ((b[i + 2].toInt() and 0xff) shl 8) or (b[i + 3].toInt() and 0xff)
                    if (len < 2) return 1
                    val start = i + 4
                    if (marker == 0xe1 && len >= 16 && String(b, start, 6, Charsets.ISO_8859_1) == "Exif\u0000\u0000") {
                        val t = start + 6
                        val le = b[t] == 'I'.code.toByte()
                        fun u16(p: Int) = if (le) (b[p].toInt() and 0xff) or ((b[p + 1].toInt() and 0xff) shl 8) else ((b[p].toInt() and 0xff) shl 8) or (b[p + 1].toInt() and 0xff)
                        fun u32(p: Int) = if (le) u16(p) or (u16(p + 2) shl 16) else (u16(p) shl 16) or u16(p + 2)
                        val ifd = t + u32(t + 4)
                        val n = u16(ifd)
                        for (k in 0 until n) {
                            val e = ifd + 2 + k * 12
                            if (e + 12 > b.size) return 1
                            if (u16(e) == 0x0112) return u16(e + 8).takeIf { it in 1..8 } ?: 1
                        }
                        return 1
                    }
                    i = start + len - 2
                }
            } catch (e: IndexOutOfBoundsException) {
                return 1
            }
            return 1
        }
    }
}

/**
 * GET /api/chat/photo/{messageId}?e=&s= — the full chat photo for a signed URL (no login: the URL itself is the
 * permission; only thread members ever get one, and it expires). Expired / forged → 403, unsent or deleted → 404.
 */
@RestController
class ChatPhotoController(
    private val photos: ChatPhotos,
    private val jdbc: JdbcTemplate,
    private val storage: ObjectStorage,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/api/chat/photo/{id}")
    fun photo(@PathVariable id: String, @RequestParam("e", required = false) e: Long?, @RequestParam("s", required = false) s: String?): ResponseEntity<ByteArray> {
        val nowMs = System.currentTimeMillis()
        if (!ID_RE.matches(id) || !photos.verify(id, e, s, nowMs)) {
            throw ApiException(403, Lang.tr("en", "This link has expired", "লিংকের মেয়াদ শেষ"), "photo_link") // bn-ok
        }
        val row = jdbc.queryForList("SELECT photo_key, photo_data FROM chat_messages WHERE id = ? AND unsent_at IS NULL", id).firstOrNull()
        val bytes: ByteArray? = when {
            row == null -> null
            row["photo_key"] != null -> try {
                storage.get(row["photo_key"] as String)
            } catch (ex: Exception) {
                log.error("chat photo read failed", ex)
                null
            }
            row["photo_data"] != null -> Base64.getDecoder().decode(row["photo_data"] as String)
            else -> null
        }
        if (bytes == null) throw ApiException(404, "Not found")
        val left = maxOf(0L, e!! - nowMs / 1000)
        return ResponseEntity.ok()
            .contentType(MediaType.IMAGE_JPEG)
            .cacheControl(CacheControl.maxAge(left, TimeUnit.SECONDS).cachePrivate())
            .header("X-Content-Type-Options", "nosniff")
            .header("Referrer-Policy", "no-referrer")
            .body(bytes)
    }

    companion object {
        private val ID_RE = Regex("^[A-Za-z0-9]{1,64}$")
    }
}
