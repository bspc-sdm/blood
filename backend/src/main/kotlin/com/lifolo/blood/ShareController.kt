package com.lifolo.blood

import com.lifolo.common.LifoloProperties
import com.lifolo.common.Rules
import com.lifolo.contracts.Prescriptions
import io.nayuki.qrcodegen.QrCode
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.ZoneOffset

/**
 * api/share.js — shared links: `/r/{id}` (a blood request page without name/phone/note; Android App Links open the
 * app), `/rx/{code}` (prescription check for pharmacies), `/shop/{code}` (pharmacy counter poster), the QR SVGs
 * (`?rxqr=`, `?shopqr=`) and `/.well-known/assetlinks.json`. Same HTML as Node (the Vercel rewrites are routes here).
 */
@RestController
class ShareController(
    private val store: BloodStore,
    private val prescriptions: Prescriptions,
    private val props: LifoloProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val jdbc: JdbcTemplate get() = store.jdbc

    @RequestMapping("/api/share")
    fun share(
        @RequestParam(name = "id", required = false) id: String?,
        @RequestParam(name = "rx", required = false) rx: String?,
        @RequestParam(name = "shop", required = false) shop: String?,
        @RequestParam(name = "rxqr", required = false) rxqr: String?,
        @RequestParam(name = "shopqr", required = false) shopqr: String?,
        @RequestParam(name = "assetlinks", required = false) assetlinks: String?,
    ): ResponseEntity<String> {
        if (!assetlinks.isNullOrEmpty()) return assetLinks()
        if (!rxqr.isNullOrEmpty()) return qrSvg(rxqr.uppercase(), RX_CODE_RE, "rx")
        if (!shopqr.isNullOrEmpty()) return qrSvg(shopqr.uppercase(), SHOP_CODE_RE, "shop")
        if (!shop.isNullOrEmpty()) return shopPage(shop)
        if (!rx.isNullOrEmpty()) return rxPage(rx)
        return requestPage(id ?: "")
    }

    @RequestMapping("/r/{id}")
    fun requestLink(@PathVariable("id") id: String) = requestPage(id)

    @RequestMapping("/rx/{code}")
    fun rxLink(@PathVariable("code") code: String) = rxPage(code)

    @RequestMapping("/shop/{code}")
    fun shopLink(@PathVariable("code") code: String) = shopPage(code)

    @RequestMapping("/.well-known/assetlinks.json")
    fun assetLinksLink() = assetLinks()

    private fun html(status: Int, body: String, vararg headers: Pair<String, String>): ResponseEntity<String> {
        val b = ResponseEntity.status(status).header("Content-Type", "text/html; charset=utf-8")
        headers.forEach { (k, v) -> b.header(k, v) }
        return b.body(body)
    }

    private fun assetLinks(): ResponseEntity<String> {
        val certs = CERT_SHA256.joinToString(",") { "\"$it\"" }
        val json = """[{"relation":["delegate_permission/common.handle_all_urls"],"target":{"namespace":"android_app","package_name":"$PACKAGE","sha256_cert_fingerprints":[$certs]}}]"""
        return ResponseEntity.status(200).header("Content-Type", "application/json").body(json)
    }

    private fun qrSvg(code: String, re: Regex, path: String): ResponseEntity<String> {
        if (!re.matches(code)) return ResponseEntity.status(404).body("")
        return ResponseEntity.status(200)
            .header("Content-Type", "image/svg+xml")
            .header("Cache-Control", "public, max-age=86400")
            .body(qrSvgText("${props.publicBaseUrl}/$path/$code"))
    }

    /** the phone camera scanned a pharmacy's QR (/shop/{code}): what to do */
    private fun shopPage(raw: String): ResponseEntity<String> {
        val code = raw.uppercase()
        var name = ""
        if (SHOP_CODE_RE.matches(code)) {
            try {
                val row = jdbc.queryForList("SELECT name, status FROM pharmacies WHERE shop_code = ? LIMIT 1", code).firstOrNull()
                if (row != null && row["status"] == "approved") name = (row["name"] as? String) ?: ""
            } catch (e: Exception) {
                log.error("shop page failed", e)
            }
        }
        val robots = "X-Robots-Tag" to "noindex, nofollow"
        if (name.isEmpty()) {
            return html(404, page("Lifolo", "<div class=\"card\"><h1>পাওয়া যায়নি</h1><p class=\"muted\">এই কোডের কোনো ফার্মেসি Lifolo-তে নেই। / No pharmacy with this code.</p></div>"), robots)
        }
        // the counter poster is this page too (print button)
        val qr = qrSvgText("${props.publicBaseUrl}/shop/$code")
        return html(
            200,
            page(
                "$name · Lifolo",
                """<div class="card" style="text-align:center"><span class="tag" style="background:#CFEDEF;color:#00363C">Lifolo ফার্মেসি</span>
<h1 style="margin-top:10px">${esc(name)}</h1>
<p>প্রেসক্রিপশনের ওষুধ কিনতে Lifolo অ্যাপে এই QR স্ক্যান করুন</p>
<div style="background:#fff;border-radius:16px;padding:12px;max-width:280px;margin:0 auto">$qr</div>
<p>দোকানের কোড: <b style="font-size:30px;letter-spacing:6px">${esc(code)}</b></p>
<button onclick="window.print()" style="font:inherit;padding:10px 18px;border-radius:20px;border:0;background:#1F6F78;color:#fff">প্রিন্ট · Print</button>
<p class="muted">Lifolo অ্যাপে প্রেসক্রিপশন খুলে "ফার্মেসিতে পাঠান" → "দোকানে আছি" → "QR স্ক্যান করুন" চাপুন, অথবা এই কোড লিখুন — প্রেসক্রিপশন এই দোকানে চলে যাবে।<br>Open your prescription in the Lifolo app → "Send to a pharmacy" → "I'm at a pharmacy" → scan this QR or type the code.</p></div>""",
            ),
            robots,
        )
    }

    /** prescription check (/rx/{code}): a pharmacy scans the QR to see whether it is genuine */
    private fun rxPage(code: String): ResponseEntity<String> {
        val robots = "X-Robots-Tag" to "noindex, nofollow"
        val noStore = "Cache-Control" to "no-store"
        val x = try {
            prescriptions.verify(code)
        } catch (e: Exception) {
            log.error("rx verify failed", e)
            null
        }
        if (x == null) {
            return html(404, page("Lifolo", "<div class=\"card\"><h1>পাওয়া যায়নি</h1><p class=\"muted\">এই কোডের কোনো প্রেসক্রিপশন Lifolo-তে নেই। / No prescription with this code.</p></div>"), robots, noStore)
        }
        val created = (x["createdAt"] as? Number)?.toLong() ?: 0L
        val d = Instant.ofEpochMilli(created + 6 * 3600 * 1000).atZone(ZoneOffset.UTC)
        val date = "${d.dayOfMonth}/${d.monthValue}/${d.year}"
        val doctor = x["doctor"] as? Map<*, *> ?: emptyMap<String, Any?>()
        val patient = x["patient"] as? Map<*, *> ?: emptyMap<String, Any?>()
        val medicines = (x["medicines"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
        val tests = (x["tests"] as? List<*>).orEmpty()
        val meds = medicines.joinToString("") { m ->
            val title = listOf(m["form"], m["name"], m["strength"]).filter { truthy(it) }.joinToString(" ") { jsStr(it) }
            "<li>${esc(title)}${if (truthy(m["dose"])) " — ${esc(m["dose"])}" else ""}${if (truthy(m["days"])) " · ${esc(m["days"])} দিন" else ""}</li>"
        }
        val tag = if (x["status"] == "cancelled") {
            "<span class=\"tag\" style=\"background:#777;color:#fff\">বাতিল · Cancelled</span>"
        } else {
            "<span class=\"tag\" style=\"background:#CFEDEF;color:#00363C\">আসল · Genuine</span>"
        }
        val regNo = if (truthy(doctor["regNo"])) " · BMDC ${esc(doctor["regNo"])}" else ""
        val chamber = if (truthy(doctor["chamber"])) " · ${esc(doctor["chamber"])}" else ""
        val age = if (patient["age"] != null) ", ${esc(patient["age"])} বছর" else ""
        val medsBlock = if (meds.isNotEmpty()) "<dt>ওষুধ · Medicines</dt><dd><ol style=\"margin:4px 0 0;padding-left:20px\">$meds</ol></dd>" else ""
        val testsBlock = if (tests.isNotEmpty()) "<dt>পরীক্ষা · Tests</dt><dd>${tests.joinToString(", ") { esc(it) }}</dd>" else ""
        val body = """<div class="card">
$tag
<h1 style="margin-top:10px">${esc(doctor["name"])}</h1>
<div class="muted">${esc(doctor["degrees"])}$regNo$chamber</div>
<dl>
<dt>রোগী · Patient</dt><dd>${esc(patient["name"])}$age</dd>
<dt>তারিখ · Date</dt><dd>${esc(date)}</dd>
$medsBlock
$testsBlock
</dl></div>
<p class="muted" style="margin-top:16px">Lifolo-তে যাচাই করা ডাক্তারের দেওয়া প্রেসক্রিপশন। বাতিল দেখালে ওষুধ দেবেন না।<br>Issued on Lifolo by a verified doctor. Don't dispense if it shows cancelled.</p>"""
        return html(200, page("প্রেসক্রিপশন যাচাই · Lifolo", body), robots, noStore)
    }

    /** a shared blood request: short facts and buttons, no name/phone/note */
    private fun requestPage(id: String): ResponseEntity<String> {
        val notFound = page("Lifolo", "<p>রিকোয়েস্টটা পাওয়া যায়নি। / Request not found.</p>")
        if (!Rules.isValidId(id)) return html(404, notFound)
        val r = try {
            store.request(id)
        } catch (e: Exception) {
            log.error("share load failed", e)
            null
        } ?: return html(404, notFound)

        val active = r.isActive()
        // some bags received/confirmed → what is left
        val bags = if (r.bags == 0) 1 else r.bags
        val left = if (active && r.bagsLeft() > 0) r.bagsLeft() else bags
        val district = r.placeName
        val districtEn = BloodRules.districtLabel(district, "en")
        val whenText = r.neededAtMillis?.takeIf { it != 0L }?.let { "${BloodRules.formatTimeBn(it)} / ${BloodRules.formatTimeEn(it)}" } ?: ""
        val date = BloodRules.utcDmy(r.neededByMillis ?: 0L)
        val intent = "intent://r/${encodeURIComponent(id)}#Intent;scheme=getblood;package=$PACKAGE;S.browser_fallback_url=${encodeURIComponent(APK_URL)};end"
        val title = "${r.patientGroup} রক্ত দরকার / ${r.patientGroup} blood needed"
        val ofTotal = if (left < bags) " · মোট ${esc(r.bags)} ব্যাগের বাকি / of ${esc(r.bags)}" else ""
        val tags = (if (r.urgent && active) "<span class=\"tag\">জরুরি · Urgent</span>" else "") +
            (if (!active) "<span class=\"tag\" style=\"background:#777\">বন্ধ · Closed</span>" else "")
        val statusText = when {
            !active -> "বন্ধ · Closed"
            r.fulfilled -> "ডোনার পাওয়া গেছে · Donors found"
            r.acceptedCount > 0 -> "${esc(r.acceptedCount)} জন রাজি · ${esc(r.acceptedCount)} agreed"
            else -> "<b>এখনো কোনো ডোনার নেই · No donor yet</b>"
        }
        val body = """<div class="card">
<div class="row"><div class="group">${esc(r.patientGroup)}</div>
<div><h1>${esc(left)} ব্যাগ রক্ত দরকার</h1><div class="muted">${esc(left)} bag(s) of ${esc(r.patientGroup)} blood needed$ofTotal</div>
$tags</div></div>
<dl>
<dt>হাসপাতাল · Hospital</dt><dd>${esc(r.hospital)}</dd>
<dt>জেলা · District</dt><dd>${esc(district)}${if (districtEn != district) " · ${esc(districtEn)}" else ""}</dd>
<dt>কবে লাগবে · Needed by</dt><dd>${esc(date)}${if (whenText.isNotEmpty()) " · ${esc(whenText)}" else ""}</dd>
<dt>অবস্থা · Status</dt><dd>$statusText</dd>
<dt>যাতায়াত খরচ · Travel cost</dt><dd>${if (r.travelFare) "দেওয়া হবে · Will be paid" else "দেওয়া হবে না · Not provided"}</dd>
</dl></div>
${if (active) "<a class=\"btn primary\" href=\"${esc(intent)}\">অ্যাপে খুলে সাড়া দিন · Open in app</a>" else ""}
${if (active) "<a class=\"btn secondary\" href=\"/request/${encodeURIComponent(id)}\">ওয়েবে খুলে সাড়া দিন · Open on the web</a>" else ""}
<a class="btn secondary" href="${esc(APK_URL)}">অ্যাপ নামান · Download Lifolo</a>
<p class="muted" style="margin-top:20px">রক্ত কেনাবেচা নিষিদ্ধ। চূড়ান্তভাবে ব্লাড ব্যাংক ক্রসম্যাচ করে ঠিক করবে।<br>Blood can't be bought or sold. The blood bank decides after cross-matching.</p>"""
        return html(200, page(title, body))
    }

    companion object {
        const val PACKAGE = "com.bspc.blood"

        // the app's signing certificate SHA-256 (app/debug.keystore); add the Play Store key here if it differs
        val CERT_SHA256 = listOf("FC:BE:F9:19:39:40:5B:C2:32:72:18:DB:DA:CD:DD:BE:86:47:F6:4B:F9:0A:06:F7:37:AC:42:99:D2:F0:F8:39")
        const val APK_URL = "https://github.com/bspc-sdm/blood/releases/download/latest/roktodan.apk"
        private val RX_CODE_RE = Regex("^[A-HJ-NP-Z2-9]{10}$")
        private val SHOP_CODE_RE = Regex("^[A-HJ-NP-Z2-9]{6}$")

        private fun truthy(v: Any?): Boolean = com.lifolo.admin.kernel.ListingRules.jsTruthy(v)

        /** String(v) like JS for plain values */
        fun jsStr(v: Any?): String = when (v) {
            null -> ""
            is Double -> com.lifolo.common.Js.numberToString(v)
            is Float -> com.lifolo.common.Js.numberToString(v.toDouble())
            else -> v.toString()
        }

        fun esc(v: Any?): String = buildString {
            for (c in jsStr(v)) {
                when (c) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&#39;")
                    else -> append(c)
                }
            }
        }

        /** JS encodeURIComponent */
        fun encodeURIComponent(s: String): String {
            val keep = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"
            val sb = StringBuilder()
            for (b in s.toByteArray(Charsets.UTF_8)) {
                val c = (b.toInt() and 0xff).toChar()
                if (b >= 0 && c in keep) sb.append(c) else sb.append("%").append("%02X".format(b.toInt() and 0xff))
            }
            return sb.toString()
        }

        /** the `qrcode` package's SVG (margin 1, error correction M): one path of horizontal runs */
        fun qrSvgText(text: String): String {
            val qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM)
            val size = qr.size
            val margin = 1
            val total = size + margin * 2
            val path = StringBuilder()
            var moveBy = 0
            var newRow = false
            var lineLength = 0
            for (i in 0 until size * size) {
                val col = i % size
                val row = i / size
                if (col == 0 && !newRow) newRow = true
                val dark = qr.getModule(col, row)
                if (dark) {
                    lineLength++
                    val prevDark = i > 0 && col > 0 && qr.getModule(col - 1, row)
                    if (!prevDark) {
                        path.append(if (newRow) "M${col + margin} ${Js5(0.5 + row + margin)}" else "m$moveBy 0")
                        moveBy = 0
                        newRow = false
                    }
                    val nextDark = col + 1 < size && qr.getModule(col + 1, row)
                    if (!nextDark) {
                        path.append("h$lineLength")
                        lineLength = 0
                    }
                } else {
                    moveBy++
                }
            }
            return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 $total $total\" shape-rendering=\"crispEdges\">" +
                "<path fill=\"#ffffff\" d=\"M0 0h${total}v${total}H0z\"/><path stroke=\"#000000\" d=\"$path\"/></svg>\n"
        }

        private fun Js5(v: Double): String = com.lifolo.common.Js.numberToString(v)

        fun page(title: String, body: String): String = """<!doctype html><html lang="bn"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(title)}</title>
<meta property="og:title" content="${esc(title)}">
<style>
:root{--red:#1F6F78;--bg:#F7FAFA;--card:#EBF0F0;--text:#161D1E;--muted:#3F4949}
@media (prefers-color-scheme:dark){:root{--red:#8AD3DB;--bg:#0F1415;--card:#1B2122;--text:#DEE4E4;--muted:#BEC8C9}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font-family:system-ui,-apple-system,"Noto Sans Bengali",sans-serif}
main{max-width:480px;margin:0 auto;padding:24px 16px}
.logo{display:flex;align-items:center;gap:10px;font-weight:800;font-size:22px;color:var(--red);margin-bottom:24px}
.dot{width:36px;height:36px;border-radius:50%;background:#1F6F78;display:flex;align-items:center;justify-content:center;color:#fff;font-size:18px}
.card{background:var(--card);border-radius:20px;padding:20px}
.group{width:72px;height:72px;border-radius:50%;background:#CFEDEF;color:#00363C;font-weight:800;font-size:26px;display:flex;align-items:center;justify-content:center}
.row{display:flex;gap:16px;align-items:center;margin-bottom:16px}
h1{font-size:22px;margin:0}.muted{color:var(--muted);font-size:14px}
dl{margin:0}dt{color:var(--muted);font-size:13px;margin-top:12px}dd{margin:2px 0 0;font-size:17px}
.tag{display:inline-block;background:#FCE4E2;color:#7A2420;border-radius:6px;padding:2px 8px;font-size:12px;font-weight:700;margin-top:6px}
a.btn{display:block;text-align:center;text-decoration:none;border-radius:999px;padding:14px;font-weight:700;margin-top:14px}
.primary{background:#1F6F78;color:#fff}.secondary{border:1px solid var(--muted);color:var(--text)}
</style></head><body><main>
<div class="logo"><div class="dot">+</div>Lifolo</div>
$body
</main></body></html>"""
    }
}
