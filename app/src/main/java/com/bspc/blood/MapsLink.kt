package com.bspc.blood

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import org.json.JSONObject

/*
 * অ্যাম্বুলেন্স ডাকার জায়গা (২.৩১.০, পারভেজ, ১৯ অক্টোবর ২০২৬) — সার্ভারের lib/ambplace.js আর ওয়েবের place.ts-এর অ্যাপ রূপ (একই নিয়ম)।
 * সব বিনামূল্যে, অ্যাপের ভেতরে ম্যাপ নেই:
 *  - "Google ম্যাপে দেখুন": ফোনের Google Maps অ্যাপে বিন্দুটা (geo: intent, না থাকলে ব্রাউজারে লিংক) — শুধু মিলিয়ে দেখা,
 *    কিছু ফেরত আসে না, কোনো API key নয়।
 *  - "Google ম্যাপ থেকে জায়গা দিন": Google Maps-এ জায়গা চেপে ধরে "শেয়ার" → Lifolo (ShareActivity), বা লিংক লেখার ঘরে পেস্ট।
 *    লম্বা লিংক/সংখ্যা এখানেই পড়া (parse); ছোট লিংক (maps.app.goo.gl) সার্ভারে খোলা (maps_link_resolve)।
 *  - বিন্দু থেকে উপজেলা: জেলার ভেতরে সবচেয়ে কাছের উপজেলা-কেন্দ্র (assets/upazila_centers.json, OCHA ADM3)।
 * ভবিষ্যতে অ্যাপের ভেতরে Google Maps SDK-র ম্যাপ বসানো যায় (Android-এ ম্যাপ দেখানো বিনামূল্যে), কিন্তু Google Cloud-এ বিলিং
 * অ্যাকাউন্ট (কার্ড) লাগে — এখন নেই। তখনও শুধু AmbLocation.point ভরবে; ডাকার বাকি পথ একই।
 */
object MapsLink {
    private const val NUM = "(-?\\d{1,3}(?:\\.\\d+)?)"
    // আগে আসল জায়গা (!3d…!4d…), তারপর q/query/ll…=lat,lng, /search|place/lat,lng, শেষে @lat,lng (ম্যাপের মাঝখান)
    private val PATTERNS = listOf(
        Regex("!3d$NUM!4d$NUM"),
        Regex("[?&](?:q|query|ll|center|destination|daddr|sll)=(?:loc:)?$NUM\\s*,\\s*\\+?$NUM"),
        Regex("/(?:search|place|dir)/$NUM\\s*,\\s*\\+?$NUM"),
        Regex("@$NUM,$NUM"),
        Regex("\\bgeo:$NUM,$NUM"),
        // লিংক ছাড়া শুধু সংখ্যা ("23.8103, 90.4125" — Google Maps-এ জায়গা চেপে ধরলে এভাবে দেখায়)
        Regex("(?:^|[^\\d.])$NUM\\s*,\\s*$NUM(?![\\d.])"),
    )
    private val SHORT = Regex("https?://(?:maps\\.app\\.goo\\.gl|goo\\.gl/maps)/[A-Za-z0-9_\\-/?=&.%]+", RegexOption.IGNORE_CASE)
    /** লেখায় Google Maps-এর লিংক আছে কি না (লেখার ঘরে পেস্ট ধরা) */
    val LINK = Regex("https?://(?:maps\\.app\\.goo\\.gl|goo\\.gl/maps|(?:www\\.|maps\\.)?google\\.com(?:\\.bd)?/maps|maps\\.google\\.com)\\S*", RegexOption.IGNORE_CASE)

    sealed class Parsed {
        data class At(val lat: Double, val lng: Double) : Parsed()
        data class Short(val url: String) : Parsed()
    }

    private fun inBd(lat: Double, lng: Double) = lat in 20.3..26.8 && lng in 87.9..92.8
    private fun r5(v: Double) = Math.round(v * 1e5) / 1e5

    private fun decode(s: String): String {
        var out = s
        repeat(2) { out = runCatching { java.net.URLDecoder.decode(out, "UTF-8") }.getOrDefault(out) }
        return out
    }

    /** শেয়ার/পেস্ট করা লেখা → At (বিন্দু) বা Short (সার্ভারে খুলতে হবে) বা null; বাংলাদেশের বাইরে নয় */
    fun parse(text: String?): Parsed? {
        val s = (text ?: "").take(2000)
        if (s.isBlank()) return null
        val t = decode(s)
        for (re in PATTERNS) {
            val m = re.find(t) ?: continue
            val lat = m.groupValues[1].toDoubleOrNull() ?: continue
            val lng = m.groupValues[2].toDoubleOrNull() ?: continue
            if (inBd(lat, lng)) return Parsed.At(r5(lat), r5(lng))
        }
        return SHORT.find(s)?.let { Parsed.Short(it.value) }
    }

    /** ছোট লিংক → বিন্দু (সার্ভার redirect ধরে খোলে; শুধু Google-এর ঠিকানা) — না পেলে ServerError (বাংলা বার্তা) */
    suspend fun resolve(url: String): Pair<Double, Double> {
        val o = ServerApi.post("/api/community", JSONObject().put("action", "maps_link_resolve").put("url", url))
        return o.getDouble("lat") to o.getDouble("lng")
    }

    /** "Google ম্যাপে দেখুন": https লিংক (ব্রাউজারেও খোলে) */
    fun webUrl(lat: Double, lng: Double) = "https://www.google.com/maps/search/?api=1&query=$lat,$lng"

    /**
     * ফোনের Google Maps অ্যাপে বিন্দুটা (পিনসহ); অ্যাপ না থাকলে যেকোনো ম্যাপ অ্যাপ, তাও না থাকলে ব্রাউজার।
     * pin = false: শুধু সেখানে ম্যাপ খোলা (জায়গা বাছার জন্য — চেপে ধরে "শেয়ার")।
     */
    fun open(context: Context, lat: Double, lng: Double, pin: Boolean = true) {
        val geo = Uri.parse(if (pin) "geo:$lat,$lng?q=$lat,$lng" else "geo:$lat,$lng?z=15")
        val tries = listOf(
            Intent(Intent.ACTION_VIEW, geo).setPackage("com.google.android.apps.maps"),
            Intent(Intent.ACTION_VIEW, geo),
            Intent(Intent.ACTION_VIEW, Uri.parse(if (pin) webUrl(lat, lng) else "https://www.google.com/maps/@$lat,$lng,15z")),
        )
        for (i in tries) {
            if (runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)) return
        }
    }

    // উপজেলার কেন্দ্র (একবার পড়া)
    private var centers: Map<Int, Pair<Double, Double>>? = null
    private var cityCenters: Map<String, List<Pair<Double, Double>>> = emptyMap()

    private fun load(context: Context) {
        if (centers != null) return
        runCatching {
            val o = JSONObject(context.applicationContext.assets.open("upazila_centers.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
            val c = o.getJSONObject("c")
            centers = c.keys().asSequence().associate { k -> val a = c.getJSONArray(k); k.toInt() to (a.getDouble(0) to a.getDouble(1)) }
            val cc = o.optJSONObject("cc") ?: JSONObject()
            cityCenters = cc.keys().asSequence().associateWith { k ->
                val a = cc.getJSONArray(k)
                (0 until a.length()).map { i -> a.getJSONArray(i).let { it.getDouble(0) to it.getDouble(1) } }
            }
        }.onFailure { centers = emptyMap() }
    }

    /**
     * বিন্দু থেকে জেলার ভেতরের উপজেলা (সবচেয়ে কাছের উপজেলা-কেন্দ্র, ৩০ কিমির ভেতরে); শহরের থানায় (কেন্দ্র নেই) বা না পারলে
     * null — তখন বেছে নেওয়া উপজেলা থাকে। সার্ভারও একই নিয়মে (উপজেলা না পাঠালে)।
     */
    fun nearestUpazila(context: Context, district: String, lat: Double, lng: Double): Int? {
        Areas.ensure(context)
        load(context)
        val c = centers ?: return null
        val ups = Areas.upazilasOf(district)
        var best: Int? = null
        var bestKm = Double.MAX_VALUE
        for (u in ups) {
            val p = c[u.id] ?: continue
            val km = Geo.distanceKm(lat, lng, p.first, p.second)
            if (km < bestKm) { bestKm = km; best = u.id }
        }
        if (best == null || bestKm > 30.0) return null
        if (ups.any { c[it.id] == null } && (cityCenters[district] ?: emptyList()).any { Geo.distanceKm(lat, lng, it.first, it.second) < bestKm }) return null
        return best
    }
}

/**
 * Google Maps থেকে "শেয়ার" → Lifolo (২.৩১.০): শেয়ারের লেখা MainActivity-তে আসে (ShareActivity হয়ে) — এখানে রাখা,
 * "অ্যাম্বুলেন্স ডাকুন"-এর শিট খোলা থাকলে সেটা নেয়, না থাকলে অ্যাম্বুলেন্সের তালিকা খুলে শিট খোলে।
 */
object SharedPlace {
    val text = mutableStateOf<String?>(null)
    /** "অ্যাম্বুলেন্স ডাকুন"-এর শিট এখন খোলা */
    val sheetOpen = mutableStateOf(false)
}

/**
 * শেয়ারের লক্ষ্য (ACTION_SEND text/plain): নিজে কিছু দেখায় না — লেখাটা নিয়ে অ্যাপের নিজের কাজে (task) MainActivity খোলে,
 * যাতে খোলা শিট (আর বাকি অবস্থা) যেমন ছিল তেমন থাকে, আর Google Maps-এর কাজের ভেতরে আরেকটা অ্যাপ না খোলে।
 */
class ShareActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val text = listOfNotNull(intent?.getStringExtra(Intent.EXTRA_SUBJECT), intent?.getStringExtra(Intent.EXTRA_TEXT)).joinToString("\n")
        runCatching { AppPrefs.init(this) }
        // Google ম্যাপের জায়গা ছাড়া অন্য লেখা: শুধু জানানো
        if (MapsLink.parse(text) == null && !MapsLink.LINK.containsMatchIn(text)) {
            android.widget.Toast.makeText(this, tr("Share a place from Google Maps to call an ambulance there.", "অ্যাম্বুলেন্স ডাকতে Google ম্যাপ থেকে জায়গা শেয়ার করুন।"), android.widget.Toast.LENGTH_LONG).show()
        } else {
            startActivity(
                Intent(this, MainActivity::class.java).putExtra(EXTRA_PLACE_TEXT, text.take(2000))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
        finish()
    }

    companion object {
        const val EXTRA_PLACE_TEXT = "lifolo.place_text"
    }
}
