package com.bspc.blood

/**
 * বাংলাদেশের ৬৪ জেলার নাম ও আনুমানিক কেন্দ্রের স্থানাঙ্ক।
 * উৎস: github.com/nuhil/bangladesh-geocode (MIT License, Copyright (c) 2014 Nuhil Mehdy)
 */
data class District(val bn: String, val en: String, val lat: Double, val lng: Double)

object Districts {
    val ALL: List<District> = listOf(
    District("কক্সবাজার", "Coxsbazar", 21.4432, 91.9738),
    District("কিশোরগঞ্জ", "Kishoreganj", 24.4449, 90.7766),
    District("কুমিল্লা", "Comilla", 23.4683, 91.1788),
    District("কুষ্টিয়া", "Kushtia", 23.9013, 89.1205),
    District("কুড়িগ্রাম", "Kurigram", 25.8054, 89.6362),
    District("খাগড়াছড়ি", "Khagrachhari", 23.1193, 91.9847),
    District("খুলনা", "Khulna", 22.8158, 89.5687),
    District("গাইবান্ধা", "Gaibandha", 25.3288, 89.5281),
    District("গাজীপুর", "Gazipur", 24.0023, 90.4264),
    District("গোপালগঞ্জ", "Gopalganj", 23.0051, 89.8266),
    District("চট্টগ্রাম", "Chattogram", 22.3351, 91.8341),
    District("চাঁদপুর", "Chandpur", 23.2333, 90.6713),
    District("চাঁপাইনবাবগঞ্জ", "Chapainawabganj", 24.5965, 88.2775),
    District("চুয়াডাঙ্গা", "Chuadanga", 23.6402, 88.8418),
    District("জামালপুর", "Jamalpur", 24.9375, 89.9378),
    District("জয়পুরহাট", "Joypurhat", 25.0964, 89.0400),
    District("ঝালকাঠি", "Jhalakathi", 22.6423, 90.2004),
    District("ঝিনাইদহ", "Jhenaidah", 23.5448, 89.1539),
    District("টাঙ্গাইল", "Tangail", 24.2641, 89.9180),
    District("ঠাকুরগাঁও", "Thakurgaon", 26.0337, 88.4617),
    District("ঢাকা", "Dhaka", 23.7115, 90.4111),
    District("দিনাজপুর", "Dinajpur", 25.6217, 88.6355),
    District("নওগাঁ", "Naogaon", 24.8326, 88.9249),
    District("নড়াইল", "Narail", 23.1725, 89.5127),
    District("নরসিংদী", "Narsingdi", 23.9322, 90.7154),
    District("নাটোর", "Natore", 24.4206, 89.0003),
    District("নারায়ণগঞ্জ", "Narayanganj", 23.6337, 90.4965),
    District("নীলফামারী", "Nilphamari", 25.9318, 88.8560),
    District("নেত্রকোণা", "Netrokona", 24.8710, 90.7279),
    District("নোয়াখালী", "Noakhali", 22.8696, 91.0994),
    District("পঞ্চগড়", "Panchagarh", 26.3411, 88.5542),
    District("পটুয়াখালী", "Patuakhali", 22.3596, 90.3299),
    District("পাবনা", "Pabna", 23.9985, 89.2336),
    District("পিরোজপুর", "Pirojpur", 22.5781, 89.9984),
    District("ফরিদপুর", "Faridpur", 23.6071, 89.8429),
    District("ফেনী", "Feni", 23.0232, 91.3841),
    District("বগুড়া", "Bogura", 24.8465, 89.3778),
    District("বরগুনা", "Barguna", 22.1592, 90.1256),
    District("বরিশাল", "Barisal", 22.7004, 90.3732),
    District("বাগেরহাট", "Bagerhat", 22.6516, 89.7859),
    District("বান্দরবান", "Bandarban", 22.1953, 92.2184),
    District("ব্রাহ্মণবাড়িয়া", "Brahmanbaria", 23.9571, 91.1119),
    District("ভোলা", "Bhola", 22.6859, 90.6482),
    District("মাগুরা", "Magura", 23.4873, 89.4200),
    District("মাদারীপুর", "Madaripur", 23.1641, 90.1897),
    District("মানিকগঞ্জ", "Manikganj", 23.8602, 90.0018),
    District("মুন্সিগঞ্জ", "Munshiganj", 23.5436, 90.5354),
    District("মেহেরপুর", "Meherpur", 23.7622, 88.6318),
    District("মৌলভীবাজার", "Moulvibazar", 24.4829, 91.7774),
    District("ময়মনসিংহ", "Mymensingh", 24.7466, 90.4072),
    District("যশোর", "Jashore", 23.1664, 89.2081),
    District("রংপুর", "Rangpur", 25.7558, 89.2445),
    District("রাঙ্গামাটি", "Rangamati", 22.6556, 92.1754),
    District("রাজবাড়ী", "Rajbari", 23.7574, 89.6445),
    District("রাজশাহী", "Rajshahi", 24.3723, 88.5631),
    District("লক্ষ্মীপুর", "Lakshmipur", 22.9425, 90.8412),
    District("লালমনিরহাট", "Lalmonirhat", 25.9165, 89.4532),
    District("শরীয়তপুর", "Shariatpur", 23.2060, 90.3478),
    District("শেরপুর", "Sherpur", 25.0205, 90.0153),
    District("সাতক্ষীরা", "Satkhira", 22.7181, 89.0687),
    District("সিরাজগঞ্জ", "Sirajganj", 24.4534, 89.7007),
    District("সিলেট", "Sylhet", 24.8898, 91.8698),
    District("সুনামগঞ্জ", "Sunamganj", 25.0658, 91.3950),
    District("হবিগঞ্জ", "Habiganj", 24.3749, 91.4155),
    )

    /**
     * কাছের জেলাগুলোর নাম (কেন্দ্র ৭০ কিমির মধ্যে, সর্বোচ্চ ৩০টা)।
     * জেলা বড় হয়, তাই ২৫ কিমির মধ্যের রিকোয়েস্ট পাশের জেলায় পড়তে পারে।
     */
    fun near(lat: Double, lng: Double): List<String> =
        ALL.map { it to Geo.distanceKm(lat, lng, it.lat, it.lng) }
            .filter { it.second <= 70.0 }
            .sortedBy { it.second }
            .take(30)
            .map { it.first.bn }
            .ifEmpty { listOf(nearest(lat, lng).bn) }

    fun nearest(lat: Double, lng: Double): District =
        ALL.minBy { Geo.distanceKm(lat, lng, it.lat, it.lng) }

    /**
     * GPS-এর জায়গা আসলে কোন জেলার ভেতরে (জেলার সীমানা দিয়ে)। সীমানার বাইরে পড়লে (নদী/সাগরের ধার) কাছের কেন্দ্র।
     * শুধু কেন্দ্র ধরলে সীমান্তের কাছে ভুল হয় — যেমন উত্তরা গাজীপুর, দোহার ফরিদপুর হয়ে যেত।
     */
    fun at(context: android.content.Context, lat: Double, lng: Double): District {
        val bn = runCatching { DistrictBounds.find(context, lat, lng) }.getOrNull()
        return bn?.let { b -> ALL.firstOrNull { it.bn == b } } ?: nearest(lat, lng)
    }

    /** ডাটাবেসে জেলার নাম বাংলায় থাকে; ইংরেজি ভাষায় ইংরেজি নাম দেখানো */
    fun label(bnName: String?): String {
        if (bnName.isNullOrBlank()) return ""
        if (isBangla) return bnName
        return ALL.firstOrNull { it.bn == bnName }?.en ?: bnName
    }
}

/**
 * জেলার সীমানা: assets/district_bounds.json (প্রায় ২৮০ KB, একবার পড়া)।
 * উৎস: OCHA / HDX — Bangladesh Subnational Administrative Boundaries (COD-AB, ADM2), CC BY-IGO;
 * bd-offline-geocoder 0.1.1 (MIT) থেকে নেওয়া, ২০০ মিটার পর্যন্ত সরল করা।
 */
object DistrictBounds {
    private class Shape(val name: String, val rings: List<DoubleArray>, val minLat: Double, val maxLat: Double, val minLng: Double, val maxLng: Double)

    @Volatile private var shapes: List<Shape>? = null

    private fun load(context: android.content.Context): List<Shape> {
        shapes?.let { return it }
        synchronized(this) {
            shapes?.let { return it }
            val text = context.applicationContext.assets.open("district_bounds.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val d = org.json.JSONObject(text).getJSONArray("d")
            val list = (0 until d.length()).map { i ->
                val e = d.getJSONArray(i)
                val rs = e.getJSONArray(1)
                val rings = (0 until rs.length()).map { k ->
                    val r = rs.getJSONArray(k)
                    DoubleArray(r.length()) { r.getDouble(it) }
                }
                var a = 90.0; var b = -90.0; var c = 180.0; var f = -180.0
                rings.forEach { r -> for (j in r.indices step 2) { a = minOf(a, r[j]); b = maxOf(b, r[j]); c = minOf(c, r[j + 1]); f = maxOf(f, r[j + 1]) } }
                Shape(e.getString(0), rings, a, b, c, f)
            }
            shapes = list
            return list
        }
    }

    /** ring = [lat, lng, lat, lng, …] */
    private fun inside(lat: Double, lng: Double, r: DoubleArray): Boolean {
        var c = false
        val n = r.size / 2
        var j = n - 1
        for (i in 0 until n) {
            val yi = r[2 * i]; val xi = r[2 * i + 1]; val yj = r[2 * j]; val xj = r[2 * j + 1]
            if ((yi > lat) != (yj > lat) && lng < (xj - xi) * (lat - yi) / (yj - yi) + xi) c = !c
            j = i
        }
        return c
    }

    fun find(context: android.content.Context, lat: Double, lng: Double): String? =
        load(context).firstOrNull { s ->
            lat in s.minLat..s.maxLat && lng in s.minLng..s.maxLng && s.rings.any { inside(lat, lng, it) }
        }?.name
}
