package com.bspc.blood

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await

object AuthRepository {
    private val auth: FirebaseAuth get() = FirebaseAuth.getInstance()

    val currentUid: String? get() = auth.currentUser?.uid
    val currentEmail: String? get() = auth.currentUser?.email

    /** ইমেইলে আসা কোড দিয়ে লগইন (প্রথমবার হলে সার্ভার নতুন অ্যাকাউন্ট খোলে) */
    suspend fun signInWithEmailCode(email: String, code: String) {
        val token = ServerApi.verifyLoginCode(email, code)
        auth.signInWithCustomToken(token).await()
    }

    suspend fun signOut(context: Context) {
        // লগআউটের পর এই ফোনে আর এই অ্যাকাউন্টের নোটিফিকেশন যেন না আসে
        val uid = currentUid
        if (uid != null) {
            runCatching {
                FirebaseFirestore.getInstance().collection("users").document(uid)
                    .set(mapOf("fcmToken" to FieldValue.delete()), SetOptions.merge()).await()
            }
        }
        runCatching { FirebaseMessaging.getInstance().deleteToken().await() }
        auth.signOut()
    }
}

object ProfileRepository {
    private val users get() = FirebaseFirestore.getInstance().collection("users")

    suspend fun load(uid: String): Profile? {
        val snap = users.document(uid).get().await()
        if (!snap.exists()) return null
        return Profile(
            name = snap.getString("name") ?: "",
            phone = snap.getString("phone") ?: "",
            bloodGroup = snap.getString("bloodGroup") ?: "",
            lastDonationMillis = snap.getLong("lastDonationMillis"),
            birthDateMillis = snap.getLong("birthDateMillis"),
            weightKg = snap.getLong("weightKg")?.toInt(),
            lat = snap.getDouble("lat"),
            lng = snap.getDouble("lng"),
            placeName = snap.getString("placeName"),
            locationSource = snap.getString("locationSource"),
            notify = snap.getBoolean("notify") ?: true,
        )
    }

    /** নাম, ফোন, গ্রুপ, শেষ রক্তদান। লোকেশন আলাদাভাবে সেভ হয়। */
    suspend fun saveBasics(uid: String, profile: Profile) {
        val data = hashMapOf<String, Any?>(
            "name" to profile.name,
            "phone" to profile.phone,
            "bloodGroup" to profile.bloodGroup,
            "lastDonationMillis" to profile.lastDonationMillis,
            "birthDateMillis" to profile.birthDateMillis,
            "weightKg" to profile.weightKg,
            "notify" to profile.notify,
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        users.document(uid).set(data, SetOptions.merge()).await()
    }

    suspend fun saveLocation(uid: String, lat: Double, lng: Double, placeName: String, source: String) {
        val rLat = Geo.round(lat)
        val rLng = Geo.round(lng)
        val data = hashMapOf<String, Any?>(
            "lat" to rLat,
            "lng" to rLng,
            "geohash" to Geo.hash(rLat, rLng),
            "placeName" to placeName,
            "locationSource" to source,
            "locationUpdatedAtMillis" to System.currentTimeMillis(),
        )
        users.document(uid).set(data, SetOptions.merge()).await()
    }

    suspend fun saveFcmToken(uid: String, token: String) {
        users.document(uid).set(mapOf("fcmToken" to token), SetOptions.merge()).await()
    }

    suspend fun setNotify(uid: String, on: Boolean) {
        users.document(uid).set(mapOf("notify" to on), SetOptions.merge()).await()
    }

    /** আজকের তারিখকে শেষ রক্তদান হিসেবে সেভ করে, তারিখটা ফেরত দেয় */
    suspend fun markDonatedToday(uid: String): Long {
        val today = Rules.todayUtcMidnight()
        users.document(uid).set(mapOf("lastDonationMillis" to today), SetOptions.merge()).await()
        return today
    }
}

object RequestRepository {
    private val db get() = FirebaseFirestore.getInstance()
    private val requests get() = db.collection("requests")

    private fun fromSnap(s: DocumentSnapshot) = BloodRequest(
        id = s.id,
        requesterUid = s.getString("requesterUid") ?: "",
        requesterName = s.getString("requesterName") ?: "",
        patientGroup = s.getString("patientGroup") ?: "",
        bags = (s.getLong("bags") ?: 1L).toInt(),
        hospital = s.getString("hospital") ?: "",
        placeName = s.getString("placeName") ?: "",
        lat = s.getDouble("lat") ?: 0.0,
        lng = s.getDouble("lng") ?: 0.0,
        neededByMillis = s.getLong("neededByMillis") ?: 0L,
        urgent = s.getBoolean("urgent") ?: false,
        note = s.getString("note") ?: "",
        status = s.getString("status") ?: "open",
        createdAtMillis = s.getLong("createdAtMillis") ?: 0L,
        serverCreatedMillis = s.getTimestamp("createdAt")?.toDate()?.time
            ?: (s.getLong("createdAtMillis") ?: 0L),
        notified = s.contains("notifiedAt"),
        notifiedCount = (s.getLong("notifiedCount") ?: 0L).toInt(),
    )

    private fun responseFromSnap(s: DocumentSnapshot) = DonorResponse(
        donorUid = s.getString("donorUid") ?: s.id,
        donorName = s.getString("donorName") ?: "",
        donorPhone = s.getString("donorPhone") ?: "",
        donorGroup = s.getString("donorGroup") ?: "",
        status = s.getString("status") ?: "accepted",
        createdAtMillis = s.getLong("createdAtMillis") ?: 0L,
    )

    /** রিকোয়েস্ট তৈরি করে। রক্তপ্রার্থীর ফোন আলাদা গোপন ডকুমেন্টে থাকে। */
    suspend fun create(r: BloodRequest, requesterPhone: String): String {
        val doc = requests.document()
        val lat = Geo.round(r.lat)
        val lng = Geo.round(r.lng)
        val data = hashMapOf<String, Any?>(
            "requesterUid" to r.requesterUid,
            "requesterName" to r.requesterName.take(80),
            "patientGroup" to r.patientGroup,
            "bags" to r.bags,
            "hospital" to r.hospital.take(120),
            "placeName" to r.placeName,
            "lat" to lat,
            "lng" to lng,
            "geohash" to Geo.hash(lat, lng),
            "neededByMillis" to r.neededByMillis,
            "urgent" to r.urgent,
            "note" to r.note.take(300),
            "status" to "open",
            "createdAtMillis" to System.currentTimeMillis(),
            "createdAt" to FieldValue.serverTimestamp(),
        )
        val batch = db.batch()
        batch.set(doc, data)
        batch.set(
            doc.collection("private").document("contact"),
            mapOf("phone" to requesterPhone, "name" to r.requesterName.take(80)),
        )
        batch.commit().await()
        return doc.id
    }

    suspend fun get(id: String): BloodRequest? {
        val s = requests.document(id).get().await()
        return if (s.exists()) fromSnap(s) else null
    }

    suspend fun mine(uid: String): List<BloodRequest> =
        requests.whereEqualTo("requesterUid", uid).get().await()
            .documents.map(::fromSnap)
            .sortedByDescending { it.createdAtMillis }

    suspend fun close(id: String) {
        requests.document(id).update("status", "closed").await()
    }

    /**
     * আশেপাশের খোলা রিকোয়েস্ট। একটাই কোয়েরি: কাছের জেলাগুলোর খোলা রিকোয়েস্ট আনা হয়,
     * তারপর ফোনেই দূরত্ব মেপে ধাপে ধাপে বাছাই (৫ → ১০ → ২৫ কিমি)।
     * donorGroup দিলে শুধু সেই গ্রুপ যাদের দিতে পারে তাদের রিকোয়েস্ট ফেরত দেয়।
     */
    suspend fun nearbyOpen(
        lat: Double,
        lng: Double,
        donorGroup: String?,
        excludeUid: String?,
    ): List<Pair<BloodRequest, Double>> {
        val all = requests
            .whereEqualTo("status", "open")
            .whereIn("placeName", Districts.near(lat, lng))
            .get().await()
            .documents.map(::fromSnap)

        // তারিখ পার হয়ে যাওয়া রিকোয়েস্ট বন্ধ করে দেওয়া, যাতে পরের বার আর পড়তে না হয়
        all.filter { !it.isActive }.forEach { requests.document(it.id).update("status", "closed") }

        val candidates = all
            .filter {
                it.isActive &&
                    it.requesterUid != excludeUid &&
                    (donorGroup == null || BloodCompat.canDonate(donorGroup, it.patientGroup))
            }
            .map { it to Geo.distanceKm(lat, lng, it.lat, it.lng) }

        var result = emptyList<Pair<BloodRequest, Double>>()
        for (radius in Geo.RADII_KM) {
            result = candidates.filter { it.second <= radius }
            if (result.size >= Geo.ENOUGH_RESULTS) break
        }
        return result.sortedWith(
            compareByDescending<Pair<BloodRequest, Double>> { it.first.urgent }.thenBy { it.second }
        )
    }

    // ---- ডোনারের সাড়া ----

    suspend fun myResponse(requestId: String, uid: String): DonorResponse? {
        val s = requests.document(requestId).collection("responses").document(uid).get().await()
        return if (s.exists()) responseFromSnap(s) else null
    }

    suspend fun setResponseStatus(requestId: String, donorUid: String, status: String) {
        requests.document(requestId).collection("responses").document(donorUid)
            .update("status", status).await()
    }

    /** শুধু রাজি থাকা বা রক্ত দেওয়া ডোনার (নিয়ম অনুযায়ী বাকিদের নম্বর রক্তপ্রার্থী দেখতে পারে না) */
    suspend fun responses(requestId: String): List<DonorResponse> =
        requests.document(requestId).collection("responses")
            .whereIn("status", listOf("accepted", "donated"))
            .get().await()
            .documents.map(::responseFromSnap)
            .sortedBy { it.createdAtMillis }

    /** রক্তপ্রার্থীর নাম ও ফোন। শুধু রক্তপ্রার্থী নিজে বা সাড়া দেওয়া ডোনার পড়তে পারে। */
    suspend fun requesterContact(requestId: String): Pair<String, String>? {
        val s = requests.document(requestId).collection("private").document("contact").get().await()
        if (!s.exists()) return null
        return (s.getString("name") ?: "") to (s.getString("phone") ?: "")
    }

    suspend fun report(requestId: String, uid: String) {
        db.collection("reports").add(
            mapOf(
                "requestId" to requestId,
                "reporterUid" to uid,
                "createdAtMillis" to System.currentTimeMillis(),
            )
        ).await()
    }
}

object LocationHelper {
    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    /** আনুমানিক বর্তমান লোকেশন (lat, lng), না পেলে null */
    @SuppressLint("MissingPermission")
    suspend fun current(context: Context): Pair<Double, Double>? {
        if (!hasPermission(context)) return null
        val client = LocationServices.getFusedLocationProviderClient(context)
        val loc = client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null).await()
            ?: client.lastLocation.await()
        return loc?.let { it.latitude to it.longitude }
    }
}
