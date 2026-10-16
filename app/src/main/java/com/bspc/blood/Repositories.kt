package com.bspc.blood

import com.firebase.geofire.GeoFireUtils
import com.firebase.geofire.GeoLocation

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
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
        // লগআউটের পর এই ফোনে আর এই অ্যাকাউন্টের নোটিফিকেশন যেন না আসে — শুধু এই ফোনের টোকেন সরে, অন্য ফোন চলতে থাকে।
        // ইন্টারনেট না থাকলেও লগআউট আটকায় না: সর্বোচ্চ ৪ সেকেন্ড চেষ্টা, তারপর লগআউট
        // (আসা নোটিফিকেশন লগআউট অবস্থায় দেখানো হয় না, তাই টোকেন সরাতে না পারলেও কিছু দেখা যায় না)
        if (currentUid != null) {
            // আলাদা কাজে চালানো, যাতে ধীর ইন্টারনেটে সংযোগের অপেক্ষায় লগআউট আটকে না থাকে
            val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).async {
                runCatching {
                    val token = FirebaseMessaging.getInstance().token.await()
                    ServerApi.post("/api/community", org.json.JSONObject().put("action", "device_unregister").put("token", token))
                }
            }
            kotlinx.coroutines.withTimeoutOrNull(4000) { job.await() }
        }
        auth.signOut()
        // পুরোনো টোকেন বাতিল (পেছনে; ইন্টারনেট না থাকলে পরে)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { FirebaseMessaging.getInstance().deleteToken().await() }
        }
    }

    /** অ্যাকাউন্ট মোছার পর: ডাটাবেসে কিছু না লিখে শুধু এই ফোন থেকে বের হওয়া */
    suspend fun signOutLocal() {
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
            maxPushPerDay = snap.getLong("maxPushPerDay")?.toInt()
                ?.coerceIn(1, Rules.MAX_PUSH_PER_DAY_LIMIT) ?: Rules.DEFAULT_PUSH_PER_DAY,
            notifyGroups = groupList(snap.get("notifyGroups")),
            viewGroups = groupList(snap.get("viewGroups")),
            commitments = (snap.get("commitments") as? Map<*, *>)?.keys?.filterIsInstance<String>().orEmpty(),
            photo = snap.getString("photo"),
            maxKm = snap.getLong("maxKm")?.toInt()?.takeIf { it in Rules.RADIUS_CHOICES } ?: Rules.DEFAULT_RADIUS_KM,
            fareAfterKm = snap.getLong("fareAfterKm")?.toInt(),
        )
    }

    private fun groupList(raw: Any?): List<String>? =
        (raw as? List<*>)?.filterIsInstance<String>()?.filter { it in Rules.BLOOD_GROUPS }

    /** সেটিংস: নোটিফিকেশন চালু/বন্ধ, দিনে কয়টা, কোন গ্রুপের নোটিফিকেশন, তালিকায় কোন গ্রুপ */
    suspend fun saveSettings(uid: String, p: Profile) {
        val data = hashMapOf<String, Any?>(
            "notify" to p.notify,
            "maxPushPerDay" to p.maxPushPerDay.coerceIn(1, Rules.MAX_PUSH_PER_DAY_LIMIT),
            "notifyGroups" to p.notifyGroups,
            "viewGroups" to p.viewGroups,
            "maxKm" to p.maxKm,
            "fareAfterKm" to (p.fareAfterKm ?: FieldValue.delete()),
        )
        users.document(uid).set(data, SetOptions.merge()).await()
    }

    /**
     * নাম, ফোন, গ্রুপ, শেষ রক্তদান। লোকেশন আলাদাভাবে সেভ হয়।
     * includeDonation: শুধু যখন আগে কোনো তারিখ সেভ নেই (প্রথমবার) — পরে তারিখ সার্ভার বা "তারিখ বদলান" দিয়ে বদলায়।
     * নইলে পুরোনো তারিখ পাঠালে নিয়মে আটকে "PERMISSION_DENIED" হতো।
     */
    suspend fun saveBasics(uid: String, profile: Profile, includeDonation: Boolean = true) {
        val data = hashMapOf<String, Any?>(
            "name" to profile.name,
            "phone" to profile.phone,
            "bloodGroup" to profile.bloodGroup,
            "birthDateMillis" to profile.birthDateMillis,
            "weightKg" to profile.weightKg,
            "notify" to profile.notify,
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        if (includeDonation && profile.lastDonationMillis != null) data["lastDonationMillis"] = profile.lastDonationMillis
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

    /** এই ফোনকে অ্যাকাউন্টে যোগ (কয়েকটা ফোনে একসাথে নোটিফিকেশন); সার্ভারে না গেলে পুরোনো উপায়ে */
    suspend fun saveFcmToken(uid: String, token: String) {
        runCatching { ServerApi.post("/api/community", org.json.JSONObject().put("action", "device_register").put("token", token)) }
            // সার্ভারে পৌঁছানো না গেলে পুরোনো উপায়ে; সার্ভার "না" বললে (যেমন অ্যাকাউন্ট বন্ধ) নয়
            .onFailure { e -> if (e !is ServerError) users.document(uid).set(mapOf("fcmToken" to token), SetOptions.merge()).await() }
    }

    suspend fun setNotify(uid: String, on: Boolean) {
        users.document(uid).set(mapOf("notify" to on), SetOptions.merge()).await()
    }

    /** অ্যাপের ভাষা ("en"/"bn"), সার্ভার নোটিফিকেশনের ভাষা ঠিক করতে ব্যবহার করে */
    suspend fun saveLang(uid: String, code: String) {
        users.document(uid).set(mapOf("lang" to code), SetOptions.merge()).await()
    }

    /** প্রোফাইলের ছবি সেভ (null দিলে মুছে যায়) */
    suspend fun savePhoto(uid: String, base64: String?) {
        users.document(uid)
            .set(mapOf("photo" to (base64 ?: FieldValue.delete())), SetOptions.merge())
            .await()
    }

    /** প্রসেসিং তালিকায় একটা রিকোয়েস্ট যোগ (সার্ভারও লেখে; পুরোনো সাড়ার জন্য অ্যাপ নিজে মিলিয়ে নেয়) */
    suspend fun addCommitment(uid: String, requestId: String) {
        users.document(uid)
            .set(mapOf("commitments" to mapOf(requestId to System.currentTimeMillis())), SetOptions.merge())
            .await()
    }

    /** শেষ হয়ে যাওয়া (বন্ধ, বাতিল, রক্ত দেওয়া) রিকোয়েস্ট প্রসেসিং তালিকা থেকে সরানো */
    suspend fun removeCommitments(uid: String, requestIds: Collection<String>) {
        if (requestIds.isEmpty()) return
        val updates = requestIds.associate { FieldPath.of("commitments", it) to FieldValue.delete() }
        val first = updates.entries.first()
        val rest = updates.entries.drop(1).flatMap { listOf(it.key, it.value) }.toTypedArray()
        users.document(uid).update(first.key, first.value, *rest).await()
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
        neededAtMillis = s.getLong("neededAtMillis"),
        urgent = s.getBoolean("urgent") ?: false,
        travelFare = s.getBoolean("travelFare") ?: false,
        note = s.getString("note") ?: "",
        status = s.getString("status") ?: "open",
        createdAtMillis = s.getLong("createdAtMillis") ?: 0L,
        serverCreatedMillis = s.getTimestamp("createdAt")?.toDate()?.time
            ?: (s.getLong("createdAtMillis") ?: 0L),
        notified = s.contains("notifiedAt"),
        notifiedCount = (s.getLong("notifiedCount") ?: 0L).toInt(),
        fulfilled = s.getBoolean("fulfilled") ?: false,
        acceptedCount = (s.getLong("acceptedCount") ?: 0L).toInt().coerceAtLeast(0),
        bagsReceived = (s.getLong("bagsReceived") ?: 0L).toInt().coerceAtLeast(0),
        confirmedCount = (s.getLong("confirmedCount") ?: 0L).toInt().coerceAtLeast(0),
        closedAtMillis = s.getLong("closedAt") ?: 0L,
        closedBy = s.getString("closedBy") ?: "",
        notifyRounds = (s.getLong("notifyRounds") ?: (if (s.contains("notifiedAt")) 1L else 0L)).toInt(),
        lastNotifiedMillis = s.getLong("lastNotifiedAt") ?: (s.getTimestamp("notifiedAt")?.toDate()?.time ?: 0L),
    )

    private fun responseFromSnap(s: DocumentSnapshot) = DonorResponse(
        donorUid = s.getString("donorUid") ?: s.id,
        donorName = s.getString("donorName") ?: "",
        donorPhone = s.getString("donorPhone") ?: "",
        donorGroup = s.getString("donorGroup") ?: "",
        status = s.getString("status") ?: "accepted",
        confirmed = s.getBoolean("confirmed") ?: false,
        distanceKm = s.getLong("distanceKm")?.toInt(),
        fareAfterKm = s.getLong("fareAfterKm")?.toInt(),
        createdAtMillis = s.getLong("createdAtMillis") ?: 0L,
    )

    /** রিকোয়েস্ট তৈরি করে। রক্তপ্রার্থীর ফোন আলাদা গোপন ডকুমেন্টে থাকে। */
    /** নতুন রিকোয়েস্ট সার্ভার দিয়ে তৈরি (সীমা আর যাচাই সার্ভারে) */
    suspend fun create(r: BloodRequest, requesterPhone: String): String {
        val lat = Geo.round(r.lat)
        val lng = Geo.round(r.lng)
        val body = org.json.JSONObject()
            .put("action", "request_create")
            .put("requesterName", r.requesterName.take(80))
            .put("requesterPhone", requesterPhone)
            .put("patientGroup", r.patientGroup)
            .put("bags", r.bags)
            .put("hospital", r.hospital.take(120))
            .put("placeName", r.placeName)
            .put("lat", lat)
            .put("lng", lng)
            .put("geohash", Geo.hash(lat, lng))
            .put("neededByMillis", r.neededByMillis)
            .put("urgent", r.urgent)
            .put("travelFare", r.travelFare)
            .put("note", r.note.take(300))
            .apply { r.neededAtMillis?.let { put("neededAtMillis", it) } }
        return ServerApi.post("/api/community", body).getString("id")
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
     * patientGroups দিলে শুধু ওই রোগীর গ্রুপগুলোর রিকোয়েস্ট ফেরত দেয় (সেটিংসের "তালিকায় কোন গ্রুপ")।
     */
    /** geohash-এর ঘর ধরে কাছের খোলা রিকোয়েস্ট (একই ঘর একবারই) */
    private suspend fun nearbyByGeohash(lat: Double, lng: Double, maxKm: Int): List<BloodRequest> {
        val bounds = GeoFireUtils.getGeoHashQueryBounds(GeoLocation(lat, lng), maxKm * 1000.0)
        val seen = HashSet<String>()
        val out = ArrayList<BloodRequest>()
        for (b in bounds) {
            val snap = requests.orderBy("geoOpen").startAt(b.startHash).endAt(b.endHash).limit(500).get().await()
            for (d in snap.documents) if (seen.add(d.id)) out += fromSnap(d)
        }
        return out.filter { it.status == "open" }
    }

    suspend fun nearbyOpen(
        lat: Double,
        lng: Double,
        patientGroups: Set<String>?,
        excludeUid: String?,
        maxKm: Int,
    ): List<Pair<BloodRequest, Double>> {
        // geohash দিয়ে শুধু আশেপাশের খোলা রিকোয়েস্ট (geoOpen থাকে শুধু খোলা থাকা পর্যন্ত) — জেলা ধরে সব পড়ার চেয়ে অনেক কম পড়া
        val all = runCatching { nearbyByGeohash(lat, lng, maxKm) }.getOrElse {
            requests
                .whereEqualTo("status", "open")
                .whereIn("placeName", Districts.near(lat, lng))
                .get().await()
                .documents.map(::fromSnap)
        }

        // তারিখ পার হয়ে যাওয়া রিকোয়েস্ট এখানে বন্ধ করা হয় না — রাতের কাজ বন্ধ করে রাজি ডোনারদের জানায়

        val candidates = all
            .filter {
                it.isActive &&
                    it.requesterUid != excludeUid &&
                    (patientGroups == null || it.patientGroup in patientGroups)
            }
            .map { it to Geo.distanceKm(lat, lng, it.lat, it.lng) }

        // ডোনার যতদূর যেতে রাজি, ততদূর পর্যন্ত
        return candidates.filter { it.second <= maxKm }.sortedWith(
            compareByDescending<Pair<BloodRequest, Double>> { it.first.urgent }.thenBy { it.second }
        )
    }

    // ---- ডোনারের সাড়া ----

    /**
     * ডোনারের "প্রসেসিং" তালিকা: যেসব খোলা রিকোয়েস্টে এখনো রাজি আছেন।
     * শেষ হয়ে যাওয়াগুলো (বন্ধ, বাতিল, বাদ, রক্ত দেওয়া) প্রোফাইল থেকে সরিয়ে দেয়।
     */
    suspend fun processing(uid: String, requestIds: List<String>): List<BloodRequest> {
        val active = mutableListOf<BloodRequest>()
        val finished = mutableListOf<String>()
        for (id in requestIds) {
            val r = get(id)
            val resp = if (r != null) myResponse(id, uid) else null
            // বন্ধ/সময় শেষ হলেও ৩ দিন রাখা — ডোনার যেন "রক্ত দিয়েছি / দিইনি" জানাতে পারেন
            val endedAt = if (r == null) 0L else if (r.closedAtMillis > 0) r.closedAtMillis else r.activeUntilMillis
            val recentlyEnded = r != null && !r.isActive && System.currentTimeMillis() - endedAt < 3 * DAY_MS
            if (r != null && resp?.status == "accepted" && (r.isActive || recentlyEnded)) active += r else finished += id
        }
        runCatching { ProfileRepository.removeCommitments(uid, finished) }
        return active.sortedBy { it.deadlineMillis }
    }

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
