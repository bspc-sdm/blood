package com.lifolo.ambulance

import com.fasterxml.jackson.databind.ObjectMapper
import com.lifolo.common.Geo
import com.lifolo.common.Js
import com.lifolo.common.Places
import com.lifolo.common.RateLimiter
import com.lifolo.contracts.HospitalDirectory
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.text.Collator
import java.text.Normalizer
import java.time.Duration
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** one HTTP answer for the short-link resolver (status, Location header, body text) */
class MapsResponse(val status: Int, val location: String?, val body: () -> String)

/** GET without following redirects (ambplace.js `fetch(url, {redirect: 'manual'})`) — replaced in tests */
fun interface MapsFetch {
    fun get(url: String): MapsResponse
}

/**
 * ambplace.js — where to pick up ("from Google Maps": links/shared text → a point; short links resolved by
 * following Google redirects) and where to go (hospitals/clinics of the DGHS list + a few big ones, via the
 * [HospitalDirectory] contract). The upazila is found from a point (nearest upazila centre in the district).
 */
@Component
class AmbPlaces(private val dir: HospitalDirectory, private val mapper: ObjectMapper, private val limiter: RateLimiter) {
    /** the outbound fetch (tests set a fake) */
    @Volatile
    internal var fetch: MapsFetch = HttpMapsFetch()

    // ------------------------------------------------------------------ hospitals/clinics (destinations)

    /** the hospital-dir.json district order (Node iterates `Object.entries(DIR.districts)`) */
    private val districtOrder: List<String> by lazy {
        val res = javaClass.getResourceAsStream("/data/hospital-dir.json") ?: error("missing data/hospital-dir.json")
        val keys = res.use { mapper.readTree(it)["districts"].fieldNames().asSequence().toList() }
        keys + Places.DISTRICTS.map { it.bn }.filter { it !in keys }
    }

    private val all: List<Facility> by lazy {
        val out = ArrayList<Facility>()
        for (district in districtOrder) {
            for (h in dir.approvedIn(district)) {
                if (!h.id.startsWith("dghs_") || TYPE_RANK[h.type] == null) continue
                out.add(Facility(h.id, h.nameEn, h.nameBn, h.type, h.district, h.upazilaId))
            }
        }
        out.addAll(EXTRA)
        out
    }

    private val byId: Map<String, Facility> by lazy { all.associateBy { it.id } }

    /** facility(id): a destination hospital ("dghs_123" / "x_bsmmu", not diagnostic), else null */
    fun facility(id: String?): Facility? {
        if (id == null) return null
        EXTRA_BY_ID[id]?.let { return it }
        if (!id.startsWith("dghs_")) return null
        val h = dir.findHospital(id) ?: return null
        if (TYPE_RANK[h.type] == null) return null
        return Facility(h.id, h.nameEn, h.nameBn, h.type, h.district, h.upazilaId)
    }

    private val districtLists = ConcurrentHashMap<String, Map<String, Any?>>()

    /** amb_facilities without q/point: the district list (static data — Node caches it 10 minutes) */
    fun districtFacilities(district: String): Map<String, Any?> =
        districtLists.computeIfAbsent(district) { facilities(district, null, null, null) }

    /**
     * ambplace.js facilities: q empty → the district's list (big ones first; nearest first when a point is given),
     * q → search the whole country (district first, at most 60). `top` = the big hospitals of the country.
     */
    fun facilities(district: String?, q: String?, lat: Double?, lng: Double?): Map<String, Any?> {
        val point = if (lat != null && lng != null && inBd(lat, lng)) lat to lng else null
        fun rank(f: Facility) = TYPE_RANK[f.type] ?: 9
        val query = AmbFare.jsTrim(q ?: "").take(60)
        val collator = Collator.getInstance(Locale.ENGLISH)
        val byName = Comparator<Facility> { a, b -> collator.compare(a.nameEn, b.nameEn) }
        val items: List<Facility> = if (query.isEmpty()) {
            val list = all.filter { it.district == district }
            if (point != null) {
                val km = list.associate { it.id to approxKm(it, point.first, point.second) }
                // inside one upazila the big ones first (distance from the upazila centre — so 3 km steps)
                list.sortedWith(compareBy<Facility> { Math.floor(km.getValue(it.id) / 3) }.thenBy { rank(it) }.then(byName))
            } else {
                list.sortedWith(compareBy<Facility> { rank(it) }.then(byName))
            }
        } else {
            val words = bnNorm(query).split(Regex("[${AmbFare.JS_SPACE}]+")).filter { it.isNotEmpty() }
            all.filter { f ->
                val hay = "${bnNorm(f.nameBn)} ${f.nameEn.lowercase()}"
                words.all { hay.contains(it) }
            }.sortedWith(compareBy<Facility> { if (it.district == district) 0 else 1 }.thenBy { rank(it) }.then(byName)).take(60)
        }
        return linkedMapOf("items" to items.map { row(it) }, "top" to TOP.mapNotNull { byId[it] }.map { row(it) })
    }

    /** the hospital's distance from a point (upazila centre, else district centre) — approximate, only for sorting */
    private fun approxKm(f: Facility, lat: Double, lng: Double): Double {
        val c = f.upazilaId?.takeIf { it != 0 }?.let { Places.UPAZILA_CENTER[it] }
            ?: Places.district(f.district)?.let { it.lat to it.lng }
        return if (c != null) Geo.distanceKm(lat, lng, c.first, c.second) else 9999.0
    }

    // ------------------------------------------------------------------ maps links

    /** maps_link_resolve per-user limit (Node: in memory per server, 6/minute and 30/hour) — Redis buckets here */
    fun checkLimit(uid: String): Boolean =
        limiter.tryAcquire("maps:m:$uid", 6, Duration.ofMinutes(1)) && limiter.tryAcquire("maps:h:$uid", 30, Duration.ofHours(1))

    fun resolveShort(url: String): Pair<Double, Double>? = resolveShort(url, fetch, TIMEOUT_MS)

    companion object {
        /** the destination types (no diagnostic centres), big ones first */
        val TYPE_RANK = mapOf(
            "govt_mch" to 0, "govt_specialized" to 1, "govt_district" to 2, "govt_uhc" to 3, "govt_hospital" to 4,
            "private_hospital" to 5, "clinic" to 6,
        )

        /** big hospitals missing from the DGHS list (ministry/university) */
        val EXTRA = listOf(
            Facility(
                "x_bsmmu", "Bangladesh Medical University (BSMMU, PG Hospital), Shahbag",
                "বাংলাদেশ মেডিকেল বিশ্ববিদ্যালয় (বিএসএমএমইউ, পিজি হাসপাতাল), শাহবাগ", "govt_mch", "ঢাকা", 1039,
            ),
            Facility("x_kurmitola", "Kurmitola General Hospital", "কুর্মিটোলা জেনারেল হাসপাতাল", "govt_hospital", "ঢাকা", 1008),
        )
        private val EXTRA_BY_ID = EXTRA.associateBy { it.id }

        /** the country's big hospitals — always after the patient's district list */
        val TOP = listOf(
            "dghs_31", "dghs_7", "x_bsmmu", "dghs_22839", "dghs_52", "dghs_47", "dghs_13562", "x_kurmitola", "dghs_13", "dghs_16775",
            "dghs_6", "dghs_9", "dghs_24589", "dghs_24118", "dghs_392", "dghs_751", "dghs_1554", "dghs_2186", "dghs_1800", "dghs_1612",
            "dghs_1969", "dghs_1105", "dghs_862", "dghs_104",
        )

        /** compact row (less data on phones): [id, nameEn, nameBn, type, district, upazilaId] */
        fun row(f: Facility): List<Any?> = listOf(f.id, f.nameEn, f.nameBn, f.type, f.district, f.upazilaId)

        /** "য়/ড়/ঢ়" in either spelling the same, lower case (app Areas.bnNorm): NFC + lower case (see ambplace.js) */
        fun bnNorm(s: String?): String = Normalizer.normalize(s ?: "", Normalizer.Form.NFC).lowercase()

        // -------------------------------------------------------------- upazila from a point

        /** a centre further than this → no automatic upazila (wrong district / wrong point) */
        const val MAX_CENTER_KM = 30.0

        /** upazila-centers.json "cc": city centres of districts with thanas that have no centre */
        private val CITY_CENTERS: Map<String, List<Pair<Double, Double>>> by lazy {
            val res = AmbPlaces::class.java.getResourceAsStream("/data/upazila-centers.json") ?: error("missing data/upazila-centers.json")
            val cc = res.use { ObjectMapper().readTree(it)["cc"] }
            cc.properties().associate { (k, v) -> k to v.map { it[0].doubleValue() to it[1].doubleValue() } }
        }

        /** districts with an upazila/thana without a centre (city corporation thanas) */
        private val NO_CENTER: Set<String> by lazy {
            Places.UPAZILA.values.filter { Places.UPAZILA_CENTER[it.id] == null }.map { it.district }.toSet()
        }

        /** ambplace.js nearestUpazila: the nearest upazila centre in the district, or null */
        fun nearestUpazila(district: String?, lat: Double?, lng: Double?): Int? {
            if (lat == null || lng == null || !lat.isFinite() || !lng.isFinite()) return null
            var best: Int? = null
            var bestKm = Double.POSITIVE_INFINITY
            for (u in Places.UPAZILA.values) {
                if (u.district != district) continue
                val c = Places.UPAZILA_CENTER[u.id] ?: continue
                val km = Geo.distanceKm(lat, lng, c.first, c.second)
                if (km < bestKm) {
                    bestKm = km
                    best = u.id
                }
            }
            if (best == null || bestKm > MAX_CENTER_KM) return null
            if (district in NO_CENTER) {
                for (c in CITY_CENTERS[district].orEmpty()) if (Geo.distanceKm(lat, lng, c.first, c.second) < bestKm) return null
            }
            return best
        }

        // -------------------------------------------------------------- Google Maps links/text → point

        /** inside Bangladesh (ambreq.js exactPoint bounds) */
        fun inBd(lat: Double, lng: Double): Boolean = lat >= 20.3 && lat <= 26.8 && lng >= 87.9 && lng <= 92.8

        private const val S = "[${AmbFare.JS_SPACE}]"
        private const val NUM = "(-?\\d{1,3}(?:\\.\\d+)?)"

        // the real place (!3d…!4d…) first, then q/query/ll/center/destination=lat,lng, then /search|place/lat,lng, last @lat,lng
        private val PATTERNS = listOf(
            Regex("!3d$NUM!4d$NUM"),
            Regex("[?&](?:q|query|ll|center|destination|daddr|sll)=(?:loc:)?$NUM$S*,$S*\\+?$NUM"),
            Regex("/(?:search|place|dir)/$NUM$S*,$S*\\+?$NUM"),
            Regex("@$NUM,$NUM"),
            Regex("\\bgeo:$NUM,$NUM"),
        )

        // only numbers ("23.8103, 90.4125" — what Google Maps shows when a place is pressed)
        private val PLAIN = Regex("(?:^|[^\\d.])$NUM$S*,$S*$NUM(?![\\d.])")
        private val SHORT_RE = Regex("https?://(?:maps\\.app\\.goo\\.gl|goo\\.gl/maps)/[A-Za-z0-9_\\-/?=&.%]+", RegexOption.IGNORE_CASE)

        private fun round5(v: Double): Double = Js.round(v * 1e5) / 1e5

        /** JS decodeURIComponent (throws on a malformed sequence, never decodes '+') */
        fun decodeURIComponent(s: String): String {
            if (!s.contains('%')) return s
            val out = StringBuilder()
            var i = 0
            while (i < s.length) {
                if (s[i] != '%') {
                    out.append(s[i])
                    i++
                    continue
                }
                val bytes = ByteArrayOutputStream()
                while (i < s.length && s[i] == '%') {
                    if (i + 2 >= s.length) throw IllegalArgumentException("URI malformed")
                    val hex = s.substring(i + 1, i + 3)
                    if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) throw IllegalArgumentException("URI malformed")
                    bytes.write(hex.toInt(16))
                    i += 3
                }
                val dec = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                try {
                    out.append(dec.decode(ByteBuffer.wrap(bytes.toByteArray())))
                } catch (e: CharacterCodingException) {
                    throw IllegalArgumentException("URI malformed")
                }
            }
            return out.toString()
        }

        private fun decode(s: String?): String {
            var out = s ?: ""
            for (i in 0 until 2) {
                try {
                    out = decodeURIComponent(out.replace('+', ' '))
                } catch (e: IllegalArgumentException) {
                    break
                }
            }
            return out
        }

        private fun pointFrom(text: String, withPlain: Boolean): Pair<Double, Double>? {
            val t = decode(text)
            for (re in if (withPlain) PATTERNS + PLAIN else PATTERNS) {
                val m = re.find(t) ?: continue
                val lat = m.groupValues[1].toDoubleOrNull() ?: continue
                val lng = m.groupValues[2].toDoubleOrNull() ?: continue
                if (lat.isFinite() && lng.isFinite() && inBd(lat, lng)) return round5(lat) to round5(lng)
            }
            return null
        }

        /** parseMapsText result: a point, or a short link the server must open */
        data class Parsed(val point: Pair<Double, Double>? = null, val short: String? = null)

        /** shared or pasted text: a point (long link or numbers), a short link, else null; never outside Bangladesh */
        fun parseMapsText(text: String?): Parsed? {
            val s = (text ?: "").take(2000)
            if (AmbFare.jsTrim(s).isEmpty()) return null
            pointFrom(s, true)?.let { return Parsed(point = it) }
            val short = SHORT_RE.find(s) ?: return null
            return Parsed(short = short.value)
        }

        private val SHORT_HOSTS = setOf("maps.app.goo.gl", "goo.gl")
        private val FOLLOW_HOST = Regex("^(?:www\\.|maps\\.|consent\\.)?google\\.com(?:\\.bd)?$")
        private fun isFollowHost(h: String) = h in SHORT_HOSTS || FOLLOW_HOST.matches(h)
        const val MAX_HOPS = 5
        const val TIMEOUT_MS = 6000L
        const val MAX_BODY = 400 * 1024
        private val ARRAY_POINT = Regex("\\[null,null,(-?\\d{1,2}\\.\\d+),(-?\\d{1,3}\\.\\d+)\\]")

        private fun parseUrl(s: String): URI? = try {
            val u = URI(s)
            if (u.scheme == null || u.host == null) null else u
        } catch (e: Exception) {
            null
        }

        private fun host(u: URI) = (u.host ?: "").lowercase()

        private fun isShortUrl(u: URI): Boolean =
            u.scheme.equals("https", true) && (host(u) == "maps.app.goo.gl" || (host(u) == "goo.gl" && (u.rawPath ?: "").startsWith("/maps/")))

        private val pool = Executors.newVirtualThreadPerTaskExecutor()

        private fun follow(start: URI, fetch: MapsFetch): Pair<Double, Double>? {
            var u = start
            for (hop in 0 until MAX_HOPS) {
                val res = fetch.get(u.toString())
                val loc = res.location
                if (res.status in 300..399 && loc != null) {
                    val next = try {
                        u.resolve(URI(loc))
                    } catch (e: Exception) {
                        return null
                    }
                    if (!next.scheme.equals("https", true) || !isFollowHost(host(next))) return null
                    pointFrom(next.toString(), false)?.let { return it }
                    u = next
                    continue
                }
                if (res.status != 200 || !isFollowHost(host(u)) || host(u) in SHORT_HOSTS) return null
                val body = res.body().take(MAX_BODY)
                val arr = ARRAY_POINT.find(body)
                if (arr != null) {
                    val lat = arr.groupValues[1].toDouble()
                    val lng = arr.groupValues[2].toDouble()
                    if (inBd(lat, lng)) return round5(lat) to round5(lng)
                }
                return pointFrom(body, false)
            }
            return null
        }

        /**
         * a short link → point, or null. Redirects are followed by hand, every hop must be a Google address; when the
         * last page's link has no point the page text is searched (at most 400 KB). [timeoutMs] for the whole lookup.
         */
        fun resolveShort(url: String, fetch: MapsFetch, timeoutMs: Long = TIMEOUT_MS): Pair<Double, Double>? {
            val start = parseUrl(url) ?: return null
            if (!isShortUrl(start)) return null
            val task = pool.submit(Callable { follow(start, fetch) })
            return try {
                task.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                task.cancel(true)
                null
            }
        }
    }
}

/** the real fetch: java.net.http, no redirects, Lifolo user agent (ambplace.js resolveShort headers) */
class HttpMapsFetch : MapsFetch {
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofMillis(AmbPlaces.TIMEOUT_MS))
        .build()

    override fun get(url: String): MapsResponse {
        val req = HttpRequest.newBuilder(URI(url))
            .GET()
            .timeout(Duration.ofMillis(AmbPlaces.TIMEOUT_MS))
            .header("user-agent", "Mozilla/5.0 (Linux; Android 14) Lifolo/2.31")
            .header("accept-language", "en")
            .build()
        val res = client.send(req, HttpResponse.BodyHandlers.ofInputStream())
        val location = res.headers().firstValue("location").orElse(null)
        if (res.statusCode() != 200) {
            res.body().close()
            return MapsResponse(res.statusCode(), location) { "" }
        }
        return MapsResponse(res.statusCode(), location) {
            res.body().use { input ->
                val buf = ByteArray(AmbPlaces.MAX_BODY)
                var n = 0
                while (n < buf.size) {
                    val r = input.read(buf, n, buf.size - n)
                    if (r < 0) break
                    n += r
                }
                String(buf, 0, n, Charsets.UTF_8)
            }
        }
    }
}
