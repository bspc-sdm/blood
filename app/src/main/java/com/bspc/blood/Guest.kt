package com.bspc.blood

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/*
 * লগইন ছাড়া ব্যবহার (নতুন নকশা N1/N2, ১৭ অক্টোবর ২০২৬)।
 * অ্যাকাউন্ট ছাড়াই অ্যাপ খোলা, তালিকা দেখা আর ফোন করা যায়। যে কাজে অ্যাকাউন্ট লাগে (সাড়া, বুকিং, প্রশ্ন,
 * রিপোর্ট, যোগ করা…) সেখানে LoginGate লগইন চায়, আর লগইনের পর সেই কাজটাই চালিয়ে দেয়।
 */

/** কোন কাজে লগইন বা ডোনারের তথ্য লাগে, আর সেটা পেলে কী চালাতে হবে */
object LoginGate {
    /** লগইন করা ইউজারের uid; null মানে অতিথি। MainActivity বসায় — বদলালে স্ক্রিন নতুন করে আঁকে */
    val uid = mutableStateOf<String?>(null)
    val isGuest: Boolean get() = uid.value == null

    /** লগইন করা ইউজারের নাম-মোবাইল আছে কি না, আর ডোনারের তথ্য আছে কি না (MainActivity বসায়) */
    val profileReady = mutableStateOf(false)
    val isDonor = mutableStateOf(false)

    /** reason: লগইন পাতার উপরে লেখা; donor: ডোনারের তথ্যও লাগবে; then: সব পেলে যে কাজ চলবে */
    class Pending(val reason: String, val donor: Boolean, val then: () -> Unit)

    val pending = mutableStateOf<Pending?>(null)

    /**
     * এই কাজে অ্যাকাউন্ট লাগে। সব থাকলে সাথে সাথে চলে; নইলে লগইন, নাম-মোবাইল আর (donor হলে) ডোনারের তথ্য
     * চেয়ে নিয়ে তারপর চলে। মাঝপথে ফিরে গেলে কাজটা বাতিল।
     */
    fun require(reason: String, donor: Boolean = false, then: () -> Unit) {
        if (!isGuest && profileReady.value && (!donor || isDonor.value)) then()
        else pending.value = Pending(reason, donor, then)
    }

    /** শুধু লগইন (উপরের "লগইন" বাটন) — পরে কোনো কাজ নেই */
    fun login() {
        pending.value = Pending("", false) {}
    }
}

/** অতিথির এলাকা: শুধু এই ফোনে থাকে, সার্ভারে যায় না। লগইনের পর প্রোফাইলে এলাকা না থাকলে এটাই বসে। */
object GuestArea {
    private const val FILE = "guest_area"

    fun load(context: Context): Profile {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val lat = p.getString("lat", null)?.toDoubleOrNull()
        val lng = p.getString("lng", null)?.toDoubleOrNull()
        return Profile(
            lat = lat,
            lng = lng,
            placeName = p.getString("place", null),
            locationSource = p.getString("source", null),
        )
    }

    fun save(context: Context, lat: Double, lng: Double, place: String, source: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("lat", Geo.round(lat).toString())
            .putString("lng", Geo.round(lng).toString())
            .putString("place", place)
            .putString("source", source)
            .apply()
    }
}

object GuestApi {
    /** সার্ভারের যে কাজগুলো লগইন ছাড়া চলে (server/lib/guest.js-এর তালিকার সাথে মিলিয়ে) */
    val PUBLIC_ACTIONS = setOf(
        "guest_requests", "emergencies_active",
        "questions", "question", "tips",
        "providers", "home_providers", "hospitals",
        "ambulances", "pharmacies", "bloodbanks", "labs",
        // একটা প্যাথলজির পুরো পরীক্ষা ও দামের তালিকা (বিস্তারিত খুললে)
        "lab_prices_public",
        "drug_search", "catalog_get",
        // রসিদের QR যাচাই (lifolo.com/v/<কোড>) — রোগী/ক্রেতার কোনো তথ্য নেই
        "invoice_verify",
        // ফিচার সুইচ (Features.kt) — অতিথিও জানে কোন সুবিধা সবার জন্য চালু
        "features",
    )

    /** আশেপাশের খোলা রিকোয়েস্ট (নাম, ফোন, নোট ছাড়া), দূরত্বসহ */
    suspend fun nearbyRequests(lat: Double, lng: Double, maxKm: Int): List<Pair<BloodRequest, Double>> {
        val o = ServerApi.post(
            "/api/community",
            JSONObject().put("action", "guest_requests").put("lat", lat).put("lng", lng).put("maxKm", maxKm),
        )
        val arr = o.optJSONArray("items") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val x = arr.getJSONObject(i)
            BloodRequest(
                id = x.str("id"),
                patientGroup = x.str("patientGroup"),
                bags = x.optInt("bags", 1),
                hospital = x.str("hospital"),
                placeName = x.str("placeName"),
                neededByMillis = x.optLong("neededByMillis"),
                neededAtMillis = if (x.isNull("neededAtMillis") || !x.has("neededAtMillis")) null else x.optLong("neededAtMillis"),
                urgent = x.optBoolean("urgent"),
                travelFare = x.optBoolean("travelFare"),
                status = x.optString("status", "open"),
                acceptedCount = x.optInt("acceptedCount"),
                confirmedCount = x.optInt("confirmedCount"),
                bagsReceived = x.optInt("bagsReceived"),
            ) to x.optDouble("km", 0.0)
        }
    }
}

/** "রক্ত দিতে চাই": যিনি এখনো ডোনারের তথ্য দেননি (অতিথি বা লগইন করা) — ছোট এক লাইন */
@Composable
fun BecomeDonorCard(onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(R.drawable.ic_volunteer_activism, null, Modifier.size(22.dp), MaterialTheme.colorScheme.onSecondaryContainer)
            Text(
                tr("I want to donate blood", "রক্ত দিতে চাই"),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f).padding(start = 12.dp),
            )
            AppIcon(R.drawable.ic_chevron_right, null, Modifier.size(20.dp), MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}
