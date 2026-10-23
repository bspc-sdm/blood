package com.bspc.blood

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * মেসেজের ছবি: পাঠানোর আগে ছোট করা (লম্বা দিক সর্বোচ্চ ১৬০০ পিক্সেল, JPEG, ১ MB-র নিচে — সার্ভারের সীমা ১ MB),
 * আর দেখানো (সার্ভারের ছোট ছবি base64; বড় ছবি সাময়িক লিংক থেকে — লিংকের মেয়াদ শেষ হলে মেসেজ আবার আনা হয়)।
 */
object ChatPhotos {
    /** সার্ভারের সীমা ১ MB (base64-এর আগে); একটু কম রাখা */
    const val MAX_BYTES = 900 * 1024
    private const val MAX_SIDE = 1600

    /** বাছাই করা/তোলা ছবি → পাঠানোর JPEG */
    suspend fun compress(context: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        val src = decode(context, uri, MAX_SIDE) ?: throw IllegalStateException(tr("Couldn't read the photo", "ছবিটা পড়া যায়নি"))
        fit(src)
    }

    /** লম্বা দিক আর মান কমিয়ে MAX_BYTES-এর মধ্যে */
    fun fit(src: Bitmap, maxBytes: Int = MAX_BYTES): ByteArray {
        var maxSide = MAX_SIDE
        var quality = 82
        while (true) {
            val scale = minOf(1f, maxSide.toFloat() / maxOf(src.width, src.height))
            val bmp = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true) else src
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
            val bytes = out.toByteArray()
            if (bytes.size <= maxBytes || maxSide <= 480) return bytes
            if (quality > 60) quality -= 10 else maxSide -= 240
        }
    }

    private fun decode(context: Context, uri: Uri, target: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= 28) {
            // ImageDecoder ছবির ঘোরানো (EXIF) নিজেই ঠিক করে
            return runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val s = maxOf(info.size.width, info.size.height)
                    if (s > target * 2) decoder.setTargetSampleSize(s / target)
                }
            }.getOrNull()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= target) sample *= 2
        val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        val degrees = runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)
        if (degrees == 0f) return bmp
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true)
    }

    /** ক্যামেরার ছবি রাখার জায়গা (FileProvider: cache/chat) */
    fun cameraUri(context: Context): Uri {
        val dir = File(context.cacheDir, "chat").apply { mkdirs() }
        val f = File(dir, "camera.jpg")
        return androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", f)
    }

    // ---- দেখানো ----

    private val cache = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    /** সার্ভারের লিংকের মেয়াদ শেষ (403/404/410) — মেসেজ আবার এনে নতুন লিংক */
    class Expired : Exception()

    fun cached(key: String): ImageBitmap? = cache.get(key)

    /** ছোট ছবি (base64), বা এই ফোনের সারিতে থাকা ছবি ("file:<নাম>") */
    fun thumb(s: String): ImageBitmap? {
        if (s.isBlank()) return null
        cache.get(s)?.let { return it }
        val bmp = if (s.startsWith(QueuedChat.LOCAL_PHOTO)) {
            val f = ChatQueue.photoFile(s.removePrefix(QueuedChat.LOCAL_PHOTO)) ?: return null
            runCatching { decodeSampled(f.readBytes(), 640) }.getOrNull()
        } else {
            runCatching {
                val bytes = Base64.decode(s.substringAfter("base64,"), Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }?.asImageBitmap() ?: return null
        cache.put(s, bmp)
        return bmp
    }

    /** বড় ছবি (সাময়িক লিংক — কোনো লগইন টোকেন যায় না); key = মেসেজের id */
    suspend fun full(key: String, url: String, maxSide: Int = 1600): ImageBitmap? = withContext(Dispatchers.IO) {
        cache.get(key)?.let { return@withContext it }
        if (!url.startsWith("https://") && !url.startsWith("http://")) return@withContext null
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            val code = conn.responseCode
            if (code == 403 || code == 404 || code == 410) throw Expired()
            if (code !in 200..299) return@withContext null
            val bytes = conn.inputStream.use { it.readBytes() }
            val bmp = decodeSampled(bytes, maxSide)?.asImageBitmap() ?: return@withContext null
            cache.put(key, bmp)
            bmp
        } catch (e: Expired) {
            throw e
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun decodeSampled(bytes: ByteArray, maxSide: Int): Bitmap? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, b)
        var sample = 1
        while (maxOf(b.outWidth, b.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
