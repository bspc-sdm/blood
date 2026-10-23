package com.bspc.blood

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * রসিদের PDF — প্রেসক্রিপশনের PDF-এর (RxPdf.kt) মতো করে: হিন্দ শিলিগুড়ি ফন্ট (বাংলা ঠিকভাবে জোড়া লাগে), QR যাচাইয়ের লিংকে।
 * দুই মাপ: A4 (লম্বা হলে পরের পাতা) আর ছোট রসিদ (৮০ মিমি চওড়া, এক পাতা — লম্বায় যত লাগে)।
 * ওপরে দোকান/প্রতিষ্ঠানের নাম-ঠিকানা-ফোন আর ছোট Lifolo; তারপর লাইন, মোট, দেওয়া/বাকি/কীভাবে;
 * নিচে QR আর "lifolo.com/v/<কোড> — রসিদ যাচাই করুন"। বাতিল হলে আড়াআড়ি লাল "বাতিল" ছাপ।
 * অঙ্ক বাংলায় (bn) বা ইংরেজিতে — রসিদের পাতার বাছাই।
 */
object ReceiptPdf {
    private const val A4_W = 595
    private const val A4_H = 842
    /** ৮০ মিমি ≈ ২২৭ পয়েন্ট */
    private const val NARROW_W = 227

    private val TEAL = Color.rgb(0x1F, 0x6F, 0x78)
    private val GREY = Color.rgb(0x55, 0x5F, 0x60)
    private val RED = Color.rgb(0xC6, 0x28, 0x28)

    fun fileName(inv: Invoice, narrow: Boolean): String =
        "receipt-" + inv.no.ifBlank { inv.id }.replace(Regex("[^A-Za-z0-9-]"), "_") + (if (narrow) "-80mm" else "") + ".pdf"

    fun share(context: Context, inv: Invoice, narrow: Boolean, bn: Boolean) {
        val file = write(context, inv, narrow, bn)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, tr("Share receipt", "রসিদ শেয়ার")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** ফোনের প্রিন্টে (Wi-Fi/ব্লুটুথ প্রিন্টার, বা PDF হিসেবে সেভ) — বানানো PDF-টাই যায় */
    fun print(context: Context, inv: Invoice, narrow: Boolean, bn: Boolean) {
        val file = write(context, inv, narrow, bn)
        // প্রিন্ট শুধু Activity থেকে চলে (ডায়ালগের context মোড়ানো থাকতে পারে)
        var a: Context = context
        while (a !is android.app.Activity && a is android.content.ContextWrapper) a = a.baseContext
        val pm = a.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
        val media = if (narrow) PrintAttributes.MediaSize("lifolo_80mm", "80 mm", 3150, 11690) else PrintAttributes.MediaSize.ISO_A4
        val attrs = PrintAttributes.Builder().setMediaSize(media).setMinMargins(PrintAttributes.Margins.NO_MARGINS).build()
        pm.print(tr("Receipt ", "রসিদ ") + inv.no, PdfFileAdapter(file), attrs)
    }

    /** তৈরি PDF ফাইলটা প্রিন্টারে পাঠানো */
    private class PdfFileAdapter(private val file: File) : PrintDocumentAdapter() {
        override fun onLayout(old: PrintAttributes?, new: PrintAttributes?, cancel: CancellationSignal?, cb: LayoutResultCallback, extras: Bundle?) {
            if (cancel?.isCanceled == true) { cb.onLayoutCancelled(); return }
            cb.onLayoutFinished(PrintDocumentInfo.Builder(file.name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), true)
        }

        override fun onWrite(pages: Array<out PageRange>?, dest: ParcelFileDescriptor, cancel: CancellationSignal?, cb: WriteResultCallback) {
            runCatching {
                FileInputStream(file).use { input -> FileOutputStream(dest.fileDescriptor).use { out -> input.copyTo(out) } }
            }.onSuccess { cb.onWriteFinished(arrayOf(PageRange.ALL_PAGES)) }.onFailure { cb.onWriteFailed(it.message) }
        }
    }

    fun write(context: Context, inv: Invoice, narrow: Boolean, bn: Boolean): File {
        val regular = runCatching { ResourcesCompat.getFont(context, R.font.hind_siliguri_regular) }.getOrNull() ?: Typeface.DEFAULT
        val bold = runCatching { ResourcesCompat.getFont(context, R.font.hind_siliguri_bold) }.getOrNull() ?: Typeface.DEFAULT_BOLD
        val doc = PdfDocument()
        if (narrow) {
            // আগে মেপে নেওয়া (কিছু আঁকা হয় না), তারপর সেই লম্বায় এক পাতা
            val h = Sheet(null, NARROW_W, null, 10f).also { draw(context, it, inv, bn, regular, bold, true) }.y
            Sheet(doc, NARROW_W, null, 10f).also { it.start(Math.ceil(h + 14.0).toInt()); draw(context, it, inv, bn, regular, bold, true); it.finish() }
        } else {
            Sheet(doc, A4_W, A4_H, 40f).also { it.start(A4_H); draw(context, it, inv, bn, regular, bold, false); it.finish() }
        }
        val dir = File(context.cacheDir, "rx").apply { mkdirs() }
        val f = File(dir, fileName(inv, narrow))
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        return f
    }

    /** পাতা আর কলম: doc null হলে শুধু মাপা; pageH null হলে এক লম্বা পাতা */
    private class Sheet(val doc: PdfDocument?, val w: Int, val pageH: Int?, val m: Float) {
        var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var y = m
        private var no = 0

        fun start(h: Int) {
            y = m
            if (doc == null) return
            no++
            page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, no).create())
            canvas = page?.canvas
        }

        fun finish() {
            page?.let { doc?.finishPage(it) }
            page = null
            canvas = null
        }

        /** জায়গা না থাকলে পরের পাতা (A4) */
        fun room(h: Float) {
            val ph = pageH ?: return
            if (y + h > ph - m) {
                finish()
                start(ph)
            }
        }

        fun layout(s: String, p: TextPaint, width: Int, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL): StaticLayout =
            StaticLayout.Builder.obtain(s, 0, s.length, p, width.coerceAtLeast(10)).setAlignment(align).build()

        fun drawAt(l: StaticLayout, x: Float, top: Float) {
            val c = canvas ?: return
            c.save()
            c.translate(x, top)
            l.draw(c)
            c.restore()
        }

        fun text(s: String, p: TextPaint, width: Int = (w - 2 * m).toInt(), x: Float = m, gap: Float = 3f, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL) {
            if (s.isBlank()) return
            val l = layout(s, p, width, align)
            room(l.height.toFloat())
            drawAt(l, x, y)
            y += l.height + gap
        }

        /** বাঁয়ে লেখা, ডানে টাকা — একই সারিতে */
        fun row(left: String, right: String, p: TextPaint, rp: TextPaint = p, rightW: Int = ((w - 2 * m) * 0.36f).toInt(), gap: Float = 2f) {
            val full = (w - 2 * m).toInt()
            val ll = layout(left, p, full - rightW - 6)
            val rl = layout(right, rp, rightW, Layout.Alignment.ALIGN_OPPOSITE)
            val h = maxOf(ll.height, rl.height).toFloat()
            room(h)
            drawAt(ll, m, y)
            drawAt(rl, w - m - rightW, y)
            y += h + gap
        }

        fun line(color: Int = Color.LTGRAY, dashed: Boolean = false) {
            room(8f)
            canvas?.drawLine(m, y + 2f, w - m, y + 2f, Paint().apply {
                this.color = color
                strokeWidth = 0.8f
                if (dashed) pathEffect = android.graphics.DashPathEffect(floatArrayOf(3f, 3f), 0f)
            })
            y += 8f
        }
    }

    private fun draw(context: Context, s: Sheet, inv: Invoice, bn: Boolean, regular: Typeface, bold: Typeface, narrow: Boolean) {
        val k = if (narrow) 0.72f else 1f
        fun paint(size: Float, b: Boolean = false, color: Int = Color.BLACK) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size * k
            typeface = if (b) bold else regular
            this.color = color
        }
        fun money(v: Double) = InvoiceText.money(v, bn)
        fun d(t: String) = InvoiceText.digits(t, bn)
        val center = Layout.Alignment.ALIGN_CENTER
        val full = (s.w - 2 * s.m).toInt()

        // ---- ওপরে: ছোট Lifolo (ডানে), নাম-ঠিকানা-ফোন ----
        val logo = paint(8f, true, TEAL)
        s.canvas?.let { c ->
            val drop = runCatching { ResourcesCompat.getDrawable(context.resources, R.drawable.ic_water_drop, null) }.getOrNull()
            val sz = (10 * k).toInt().coerceAtLeast(7)
            val tw = logo.measureText("Lifolo")
            val x = s.w - s.m - tw
            drop?.mutate()?.let { dr ->
                dr.setTint(TEAL)
                dr.setBounds((x - sz - 2).toInt(), s.y.toInt(), (x - 2).toInt(), s.y.toInt() + sz)
                dr.draw(c)
            }
            c.drawText("Lifolo", x, s.y + sz - 1.5f, logo)
        }
        val headW = if (narrow) full else full - 70
        s.text(inv.bizName, paint(if (narrow) 15f else 18f, true), headW, align = if (narrow) center else Layout.Alignment.ALIGN_NORMAL, gap = 1f)
        val contact = listOf(inv.bizAddress, inv.bizPhone.takeIf { it.isNotBlank() }?.let { tr("Phone: ", "ফোন: ") + d(it) }.orEmpty()).filter { it.isNotBlank() }
        if (contact.isNotEmpty()) s.text(contact.joinToString(if (narrow) "\n" else " · "), paint(10f, color = GREY), headW, align = if (narrow) center else Layout.Alignment.ALIGN_NORMAL)
        s.line(TEAL)

        // ---- রসিদের পরিচয় ----
        s.text(tr("RECEIPT", "রসিদ") + " · " + InvoiceKinds.label(inv.kind), paint(12f, true, TEAL), align = if (narrow) center else Layout.Alignment.ALIGN_NORMAL, gap = 2f)
        s.row(tr("No.", "নং") + " " + d(inv.no), d(InvoiceText.dateTime(inv.issuedAt, bn)), paint(10f, true), paint(10f), rightW = (full * 0.55f).toInt())
        // ক্রেতার ফোন কখনো ছাপা হয় না, শুধু নাম (পর্যালোচনা I6)
        if (inv.customerName.isNotBlank()) s.text(tr("Customer: ", "গ্রাহক: ") + inv.customerName, paint(10f))
        if (inv.offline) s.text(tr("Offline receipt — it can be checked once online", "অফলাইন রসিদ — ইন্টারনেট এলে যাচাই হবে") + (inv.deviceId.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), paint(8f, color = GREY))

        // ডেমো: আড়াআড়ি ধূসর "ডেমো" জলছাপ (পর্যালোচনা I9)
        if (inv.demo) {
            s.canvas?.let { c ->
                val st = paint(if (narrow) 48f else 96f, true, Color.GRAY).apply { alpha = 60; textAlign = Paint.Align.CENTER }
                val cy = s.y + (if (narrow) 70f else 200f)
                c.save()
                c.rotate(-30f, s.w / 2f, cy)
                c.drawText(tr("DEMO", "ডেমো"), s.w / 2f, cy, st)
                c.restore()
            }
            s.text(tr("DEMO — not a real receipt", "ডেমো — আসল রসিদ নয়"), paint(10f, true, GREY))
        }

        // বাতিল: আড়াআড়ি বড় লাল ছাপ (প্রথম পাতায়, লাইনের ওপর দিয়ে) আর কারণ
        if (inv.isVoid) {
            s.canvas?.let { c ->
                val st = paint(if (narrow) 44f else 72f, true, RED).apply { alpha = 90; textAlign = Paint.Align.CENTER }
                val cy = s.y + (if (narrow) 50f else 120f)
                c.save()
                c.rotate(-18f, s.w / 2f, cy)
                c.drawText(tr("VOID", "বাতিল"), s.w / 2f, cy, st)
                c.restore()
            }
            s.text(tr("VOID", "বাতিল") + if (inv.voidText.isNotBlank()) " — " + tr("reason: ", "কারণ: ") + inv.voidText else "", paint(11f, true, RED))
        }
        s.line()

        // ---- লাইন ----
        inv.lines.forEachIndexed { i, l ->
            val unit = if (l.unit.isNotBlank()) " " + l.unit else ""
            s.row(d("${i + 1}. ") + l.name, money(l.amount), paint(10.5f), paint(10.5f, true), gap = 0f)
            val sub = d(InvoiceText.qty(l.qty, bn)) + unit + " × " + money(l.price) + if (l.disc > 0.004) " − " + tr("disc ", "ছাড় ") + money(l.disc) else ""
            s.text(sub, paint(9f, color = GREY), x = s.m + 10f, width = full - 10, gap = 4f)
        }
        s.line(dashed = true)

        // ---- মোট ----
        val small = paint(10.5f)
        s.row(tr("Subtotal", "উপমোট"), money(inv.subtotal), small)
        if (inv.disc > 0.004) s.row(tr("Discount", "ছাড়"), "− " + money(inv.disc), small)
        inv.fees.forEach { f -> s.row(f.label, money(f.amount), small) }
        s.row(tr("Total", "মোট"), money(inv.total), paint(13f, true), paint(13f, true), gap = 4f)
        s.row(tr("Paid", "দেওয়া হয়েছে"), money(inv.paid), small)
        if (inv.due > 0.004) s.row(tr("Due", "বাকি"), money(inv.due), paint(11f, true, RED), paint(11f, true, RED))
        inv.payments.forEach { p ->
            s.text(
                InvoiceKinds.method(p.method) + " " + money(p.amount) + (if (p.ref.isNotBlank()) " · " + d(p.ref) else "") +
                    (if (p.at > 0) " · " + InvoiceText.dateTime(p.at, bn) else ""),
                paint(8.5f, color = GREY), gap = 1f,
            )
        }
        s.line()

        // ---- QR আর যাচাই ----
        val link = inv.link
        if (link.isNotBlank()) {
            val q = if (narrow) 84 else 92
            s.room(q + 30f)
            val x = if (narrow) (s.w - q) / 2f else s.m
            Qr.bitmap(link, 270)?.let { b -> s.canvas?.drawBitmap(Bitmap.createScaledBitmap(b, q, q, false), x, s.y, null) }
            val note = "lifolo.com/v/" + inv.verifyCode.ifBlank { link.substringAfterLast('/') } + " — " + tr("check this receipt", "রসিদ যাচাই করুন")
            if (narrow) {
                s.y += q + 4f
                s.text(note, paint(9f, true), align = center)
            } else {
                val top = s.y
                s.y = top + 20f
                s.text(note, paint(10f, true), width = full - q - 14, x = s.m + q + 14f)
                s.text(tr("Scan the QR to see that it is genuine (business, number, date, total, status).", "আসল কি না দেখতে QR স্ক্যান করুন (প্রতিষ্ঠান, নম্বর, তারিখ, মোট, অবস্থা)।"), paint(8.5f, color = GREY), width = full - q - 14, x = s.m + q + 14f)
                s.y = maxOf(s.y, top + q + 6f)
            }
        }
        s.text(tr("Issued on Lifolo. The app takes no money.", "Lifolo-তে দেওয়া রসিদ। অ্যাপ কোনো টাকা নেয় না।"), paint(8f, color = GREY), align = if (narrow) center else Layout.Alignment.ALIGN_NORMAL, gap = 1f)
        // মূসক চালানপত্র নয় (পর্যালোচনা I15)
        s.text(inv.footer.ifBlank { InvoiceText.footerNote() }, paint(8f, true, GREY), align = if (narrow) center else Layout.Alignment.ALIGN_NORMAL)
    }
}
