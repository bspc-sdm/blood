package com.bspc.blood

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File

/** যাচাইয়ের লিংকের QR */
object Qr {
    fun bitmap(text: String, size: Int): Bitmap? = runCatching {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) Color.BLACK else Color.WHITE }
        Bitmap.createBitmap(px, size, size, Bitmap.Config.RGB_565)
    }.getOrNull()
}

/**
 * প্রেসক্রিপশনের PDF (A4): ওপরে ডাক্তার আর QR, তারপর রোগী, সমস্যা, ℞ ওষুধ, পরীক্ষা, পরামর্শ, আবার দেখানো।
 * বাংলা ঠিকভাবে জোড়া লাগাতে অ্যাপের হিন্দ শিলিগুড়ি ফন্ট; লম্বা হলে পরের পাতায়।
 */
object RxPdf {
    private const val W = 595
    private const val H = 842
    private const val M = 40

    fun share(context: Context, rx: Rx) {
        val file = write(context, rx)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, tr("Share prescription", "প্রেসক্রিপশন শেয়ার")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun write(context: Context, rx: Rx): File {
        val regular = runCatching { ResourcesCompat.getFont(context, R.font.hind_siliguri_regular) }.getOrNull() ?: Typeface.DEFAULT
        val bold = runCatching { ResourcesCompat.getFont(context, R.font.hind_siliguri_bold) }.getOrNull() ?: Typeface.DEFAULT_BOLD
        val doc = PdfDocument()
        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
        var canvas: Canvas = page.canvas
        var y = M.toFloat()

        fun paint(size: Float, b: Boolean = false, color: Int = Color.BLACK) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            typeface = if (b) bold else regular
            this.color = color
        }

        fun newPage() {
            doc.finishPage(page)
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            canvas = page.canvas
            y = M.toFloat()
        }

        fun text(s: String, p: TextPaint, width: Int = W - 2 * M, x: Float = M.toFloat(), gap: Float = 4f) {
            if (s.isBlank()) return
            val layout = StaticLayout.Builder.obtain(s, 0, s.length, p, width).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
            if (y + layout.height > H - M) newPage()
            canvas.save()
            canvas.translate(x, y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height + gap
        }

        fun line() {
            val p = Paint().apply { color = Color.LTGRAY; strokeWidth = 1f }
            canvas.drawLine(M.toFloat(), y, (W - M).toFloat(), y, p)
            y += 8f
        }

        val teal = Color.rgb(0x1F, 0x6F, 0x78)
        val grey = Color.rgb(0x55, 0x5F, 0x60)
        // ওপরে: ডাক্তার (বাঁয়ে), QR (ডানে)
        val qrSize = 90
        Qr.bitmap(rx.verifyUrl, 270)?.let { canvas.drawBitmap(Bitmap.createScaledBitmap(it, qrSize, qrSize, false), (W - M - qrSize).toFloat(), y, null) }
        val headW = W - 2 * M - qrSize - 12
        val top = y
        text(rx.doctorName, paint(18f, true), headW, gap = 2f)
        // পদ্ধতি/পেশা নামের ঠিক নিচে (practice_systems; ফিজিও/সাইকোলজিস্ট) — নাম যেমন লেখা, "ডা." নিজে বসে না
        Practice.rxHeaderTag(rx)?.let { text(it, paint(11f, true, teal), headW, gap = 2f) }
        text(rx.degrees, paint(11f), headW, gap = 2f)
        if (rx.specialties.isNotEmpty()) text(rx.specialties.joinToString(", ") { Specialties.label(it) }, paint(11f, color = teal), headW, gap = 2f)
        if (rx.regNo.isNotBlank()) text(Practice.regLine(rx.doctorType.ifBlank { "doctor" }, rx.system, rx.regNo), paint(10f, color = grey), headW, gap = 2f)
        text(listOf(rx.chamberName, rx.chamberAddress, rx.chamberPhone).filter { it.isNotBlank() }.joinToString(", "), paint(10f, color = grey), headW)
        // QR-এর নিচে কোড: ছোট "কোড", তার নিচে বড় এক-মাপের অক্ষরে "AB23 CD45 EF" — স্ক্যান না হলে দোকান হাতে লেখে
        val cx = (W - M - qrSize / 2).toFloat()
        canvas.drawText(tr("Code", "কোড"), cx, top + qrSize + 10f, paint(7f, color = grey).apply { textAlign = Paint.Align.CENTER })
        canvas.drawText(
            RxCode.grouped(rx.code), cx, top + qrSize + 23f,
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textAlign = Paint.Align.CENTER; letterSpacing = 0.08f },
        )
        y = maxOf(y, top + qrSize + 28f)
        text(tr("Verify: ", "যাচাই: ") + rx.code, paint(8f, color = grey), W - 2 * M)
        if (rx.cancelled) text(tr("CANCELLED", "বাতিল"), paint(14f, true, Color.RED))
        line()
        text(rx.patientLine + "        " + tr("Date: ", "তারিখ: ") + Rules.formatDate(rx.createdAt), paint(12f, true))
        line()
        fun block(title: String, body: String) {
            if (body.isBlank()) return
            text(title, paint(11f, true, teal), gap = 1f)
            text(body, paint(11f))
        }
        val mode = Practice.rxMode(rx)
        block(RxText.complaintsTitle(mode), rx.complaints)
        block(tr("Findings", "পরীক্ষায় পাওয়া"), rx.findings.entries.joinToString(" · ") { RxText.finding(it.key) + ": " + it.value })
        block(tr("Diagnosis", "রোগনির্ণয়"), rx.diagnosis)
        if (rx.medicines.isNotEmpty() && mode != Practice.RxMode.PHYSIO && mode != Practice.RxMode.PSYCH) {
            text("℞", paint(22f, true, teal), gap = 2f)
            rx.medicines.forEachIndexed { i, m ->
                text("${num(i + 1)}. ${m.title}", paint(12f, true), gap = 1f)
                val how = listOf(RxText.howTo(m, mode), m.note).filter { it.isNotBlank() }.joinToString(" — ")
                text(how, paint(11f, color = grey), W - 2 * M - 16, M + 16f, gap = 6f)
            }
        }
        if (rx.tests.isNotEmpty()) {
            block(tr("Tests", "পরীক্ষা"), rx.tests.joinToString("\n") { "• $it" })
            if (rx.testPlace.isNotBlank()) text(tr("Suggested place: ", "করাতে পারেন: ") + rx.testPlace, paint(10f, color = grey))
        }
        block(rx.rules?.section ?: RxText.adviceTitle(mode), rx.advice)
        rx.followUpDate?.let { block(tr("Come again", "আবার দেখাবেন"), Rules.formatDate(it) + if (rx.followUpNote.isNotBlank()) " · " + rx.followUpNote else "") }
        if (rx.rules?.notice != null || mode == Practice.RxMode.PSYCH) text(rx.rules?.notice ?: Practice.psychiatristNote(), paint(11f, true, teal))
        y = maxOf(y, (H - M - 24).toFloat())
        text(tr("Issued on Lifolo. Scan the QR to check it is genuine.", "Lifolo-তে দেওয়া প্রেসক্রিপশন। আসল কি না দেখতে QR স্ক্যান করুন।"), paint(8f, color = grey))
        doc.finishPage(page)

        val dir = File(context.cacheDir, "rx").apply { mkdirs() }
        val f = File(dir, "prescription-${rx.code}.pdf")
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        return f
    }
}
