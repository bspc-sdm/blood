package com.bspc.blood

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.exifinterface.media.ExifInterface
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * প্রোফাইলের ছবি: বাছাই করা ছবিকে মাঝখান থেকে বর্গাকার কেটে ২৫৬×২৫৬ JPEG বানিয়ে base64।
 * (~১৫–৩০ KB, তাই আলাদা স্টোরেজ ছাড়াই প্রোফাইলের সাথে রাখা যায়)
 */
object Photo {
    private const val SIZE = 256

    suspend fun toAvatarBase64(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val src = decode(context, uri) ?: throw IllegalStateException(tr("Couldn't read the photo", "ছবিটা পড়া যায়নি"))
        val side = minOf(src.width, src.height)
        val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(square, SIZE, SIZE, true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * প্রেসক্রিপশনের ছবি: লম্বা দিক সর্বোচ্চ ১৪০০ পিক্সেল, JPEG; base64 ২৮০ KB-র মধ্যে না আসা পর্যন্ত মান কমানো।
     */
    suspend fun toDocumentBase64(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val src = decode(context, uri) ?: throw IllegalStateException(tr("Couldn't read the photo", "ছবিটা পড়া যায়নি"))
        var maxSide = 1400
        var quality = 75
        while (true) {
            val scale = minOf(1f, maxSide.toFloat() / maxOf(src.width, src.height))
            val bmp = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true) else src
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
            val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            if (b64.length <= 280 * 1024 || maxSide <= 700) return@withContext b64
            if (quality > 55) quality -= 10 else maxSide -= 200
        }
        @Suppress("UNREACHABLE_CODE")
        ""
    }

    fun decodeImage(base64: String?): ImageBitmap? = decodeAvatar(base64)

    private fun decode(context: Context, uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= 28) {
            // ImageDecoder ছবির ঘোরানো (EXIF) নিজেই ঠিক করে
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val s = minOf(info.size.width, info.size.height)
                if (s > 1024) decoder.setTargetSampleSize(s / 1024)
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIZE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        // পুরোনো Android-এ ক্যামেরার ছবি কাত হয়ে না থাকে, তাই EXIF দেখে ঘোরানো
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
        val m = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    fun decodeAvatar(base64: String?): ImageBitmap? {
        if (base64.isNullOrBlank()) return null
        return runCatching {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }
}
