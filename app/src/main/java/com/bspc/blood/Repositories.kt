package com.bspc.blood

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.auth.FirebaseAuth
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
        // ফোনে রাখা সার্ভারের উত্তর (প্রোফাইল, রিকোয়েস্ট) — পরের মানুষ যেন না দেখেন
        OfflineCache.clear()
        // পুরোনো টোকেন বাতিল (পেছনে; ইন্টারনেট না থাকলে পরে)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { FirebaseMessaging.getInstance().deleteToken().await() }
        }
    }

    /** অ্যাকাউন্ট মোছার পর: ডাটাবেসে কিছু না লিখে শুধু এই ফোন থেকে বের হওয়া */
    suspend fun signOutLocal() {
        runCatching { FirebaseMessaging.getInstance().deleteToken().await() }
        auth.signOut()
        OfflineCache.clear()
    }
}

/**
 * সার্ভারের পড়া উত্তর ফোনে রাখা — আগে Firestore নিজেই রাখত, তাই ইন্টারনেট না থাকলেও শেষ দেখা প্রোফাইল,
 * রিকোয়েস্ট আর তালিকা দেখাত। এখনও তাই: শুধু সার্ভারে পৌঁছানো না গেলে শেষ উত্তর; সার্ভার "না" বললে সেটাই দেখায়।
 * অ্যাকাউন্ট অনুযায়ী আলাদা; লগআউটে মুছে যায়।
 */
object OfflineCache {
    private const val FILE = "server_cache"
    private const val MAX_ENTRIES = 150

    @Volatile private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    fun clear() {
        runCatching { prefs?.edit()?.clear()?.apply() }
    }

    /** /api/community-র পড়ার কাজ; key = কাজ আর তার প্যারামিটার */
    suspend fun read(key: String, body: org.json.JSONObject): org.json.JSONObject {
        val k = "${AuthRepository.currentUid}|$key"
        return try {
            ServerApi.post("/api/community", body).also { put(k, it) }
        } catch (e: Exception) {
            if (e is ServerError || e is LoginRequired || e is kotlinx.coroutines.CancellationException) throw e
            get(k) ?: throw e
        }
    }

    private fun get(k: String): org.json.JSONObject? = runCatching {
        prefs?.getString(k, null)?.substringAfter('\n')?.let { org.json.JSONObject(it) }
    }.getOrNull()

    private fun put(k: String, o: org.json.JSONObject) {
        val p = prefs ?: return
        runCatching {
            val e = p.edit()
            if (p.all.size >= MAX_ENTRIES) {
                // পুরোনো অর্ধেক সরানো
                p.all.entries.sortedBy { (it.value as? String)?.substringBefore('\n')?.toLongOrNull() ?: 0L }
                    .take(MAX_ENTRIES / 2).forEach { e.remove(it.key) }
            }
            e.putString(k, "${System.currentTimeMillis()}\n$o").apply()
        }
    }
}

private fun org.json.JSONObject.has0(k: String) = has(k) && !isNull(k)
private fun org.json.JSONObject.longN(k: String): Long? = if (has0(k)) optLong(k) else null
private fun org.json.JSONObject.dblN(k: String): Double? = if (has0(k)) optDouble(k).takeIf { !it.isNaN() } else null
private fun org.json.JSONObject.strN(k: String): String? = if (has0(k)) optString(k) else null
private fun org.json.JSONObject.strList(k: String): List<String>? =
    optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { a.opt(it) as? String } }

/** প্রোফাইল (users/{uid}) — সার্ভার দিয়ে (profile_get / profile_update); নিজেরটাই, নিয়ম server/lib/clientdata.js-এ */
object ProfileRepository {
    private suspend fun update(set: org.json.JSONObject? = null, commitAdd: String? = null, commitRemove: Collection<String>? = null) {
        val body = org.json.JSONObject().put("action", "profile_update")
        if (set != null) body.put("set", set)
        if (commitAdd != null) body.put("commitAdd", commitAdd)
        if (!commitRemove.isNullOrEmpty()) body.put("commitRemove", org.json.JSONArray(commitRemove))
        ServerApi.post("/api/community", body)
    }

    /** uid আগের মতো (ডাকার জায়গা বদলায়নি); সার্ভার লগইন করা মানুষের প্রোফাইলই দেয় */
    @Suppress("UNUSED_PARAMETER")
    suspend fun load(uid: String): Profile? {
        val x = OfflineCache.read("profile", org.json.JSONObject().put("action", "profile_get")).optJSONObject("profile") ?: return null
        // আগের ছবির ছোট সংস্করণ একবার বানিয়ে রাখা (তালিকার কার্ডের জন্য)
        val photo = x.strN("photo")
        if (!photo.isNullOrBlank() && !x.optBoolean("hasPhotoThumb")) {
            Photo.thumbFromAvatar(photo)?.let { t ->
                runCatching { update(org.json.JSONObject().put("photoThumb", t)) }
            }
        }
        return Profile(
            name = x.strN("name") ?: "",
            phone = x.strN("phone") ?: "",
            bloodGroup = x.strN("bloodGroup") ?: "",
            lastDonationMillis = x.longN("lastDonationMillis"),
            birthDateMillis = x.longN("birthDateMillis"),
            weightKg = x.longN("weightKg")?.toInt(),
            lat = x.dblN("lat"),
            lng = x.dblN("lng"),
            placeName = x.strN("placeName"),
            locationSource = x.strN("locationSource"),
            notify = if (x.has0("notify")) x.optBoolean("notify", true) else true,
            maxPushPerDay = x.longN("maxPushPerDay")?.toInt()
                ?.coerceIn(1, Rules.MAX_PUSH_PER_DAY_LIMIT) ?: Rules.DEFAULT_PUSH_PER_DAY,
            notifyGroups = groupList(x.strList("notifyGroups")),
            viewGroups = groupList(x.strList("viewGroups")),
            commitments = x.optJSONObject("commitments")?.keys()?.asSequence()?.toList().orEmpty(),
            photo = photo,
            maxKm = x.longN("maxKm")?.toInt()?.takeIf { it in Rules.RADIUS_CHOICES } ?: Rules.DEFAULT_RADIUS_KM,
            fareAfterKm = x.longN("fareAfterKm")?.toInt(),
        )
    }

    private fun groupList(raw: List<String>?): List<String>? = raw?.filter { it in Rules.BLOOD_GROUPS }

    private fun org.json.JSONObject.putN(k: String, v: Any?): org.json.JSONObject = put(k, v ?: org.json.JSONObject.NULL)

    /** সেটিংস: নোটিফিকেশন চালু/বন্ধ, দিনে কয়টা, কোন গ্রুপের নোটিফিকেশন, তালিকায় কোন গ্রুপ */
    @Suppress("UNUSED_PARAMETER")
    suspend fun saveSettings(uid: String, p: Profile) {
        update(
            org.json.JSONObject()
                .put("notify", p.notify)
                .put("maxPushPerDay", p.maxPushPerDay.coerceIn(1, Rules.MAX_PUSH_PER_DAY_LIMIT))
                .putN("notifyGroups", p.notifyGroups?.let { org.json.JSONArray(it) })
                .putN("viewGroups", p.viewGroups?.let { org.json.JSONArray(it) })
                .put("maxKm", p.maxKm)
                // null = মোছা
                .putN("fareAfterKm", p.fareAfterKm),
        )
    }

    /**
     * নাম, ফোন, গ্রুপ, শেষ রক্তদান। লোকেশন আলাদাভাবে সেভ হয়।
     * includeDonation: শুধু যখন আগে কোনো তারিখ সেভ নেই (প্রথমবার) — পরে তারিখ সার্ভার বা "তারিখ বদলান" দিয়ে বদলায়।
     * নইলে পুরোনো তারিখ পাঠালে সার্ভার আটকায় (তারিখ শুধু সামনে এগোয়)।
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun saveBasics(uid: String, profile: Profile, includeDonation: Boolean = true) {
        val set = org.json.JSONObject()
            .put("name", profile.name)
            .put("phone", profile.phone)
            .put("bloodGroup", profile.bloodGroup)
            .putN("birthDateMillis", profile.birthDateMillis)
            .putN("weightKg", profile.weightKg)
            .put("notify", profile.notify)
        if (includeDonation && profile.lastDonationMillis != null) set.put("lastDonationMillis", profile.lastDonationMillis)
        // ডোনার খোঁজার ঘর ("গ্রুপ|geohash") সার্ভার নিজেই ঠিক করে
        update(set)
    }

    /** শুধু নাম আর মোবাইল (নতুন নকশা N2-এর প্রথম ধাপ) — ডোনারের তথ্য বা সেটিংসে হাত দেয় না */
    @Suppress("UNUSED_PARAMETER")
    suspend fun saveContact(uid: String, name: String, phone: String) {
        update(org.json.JSONObject().put("name", name).put("phone", phone))
    }

    /** শুধু ডোনারের তথ্য: গ্রুপ, জন্ম তারিখ, ওজন, (প্রথমবার) শেষ রক্তদান — নাম-মোবাইলে হাত দেয় না */
    @Suppress("UNUSED_PARAMETER")
    suspend fun saveDonor(uid: String, profile: Profile, includeDonation: Boolean) {
        val set = org.json.JSONObject()
            .put("bloodGroup", profile.bloodGroup)
            .putN("birthDateMillis", profile.birthDateMillis)
            .putN("weightKg", profile.weightKg)
        if (includeDonation && profile.lastDonationMillis != null) set.put("lastDonationMillis", profile.lastDonationMillis)
        update(set)
    }

    /** লোকেশন (গোল করা); geohash, ডোনার খোঁজার ঘর আর সময় সার্ভার বসায় */
    @Suppress("UNUSED_PARAMETER")
    suspend fun saveLocation(uid: String, lat: Double, lng: Double, placeName: String, source: String) {
        update(
            org.json.JSONObject()
                .put("lat", Geo.round(lat))
                .put("lng", Geo.round(lng))
                .put("placeName", placeName)
                .put("locationSource", source),
        )
    }

    /** এই ফোনকে অ্যাকাউন্টে যোগ (কয়েকটা ফোনে একসাথে নোটিফিকেশন) */
    @Suppress("UNUSED_PARAMETER")
    suspend fun saveFcmToken(uid: String, token: String) {
        // সার্ভারে না গেলে পরে আবার (অ্যাপ খুললে / টোকেন বদলালে) — আগের সরাসরি ডাটাবেসে লেখার পথ আর নেই;
        // আগের মতোই ভুল বাইরে যায় না
        runCatching { ServerApi.post("/api/community", org.json.JSONObject().put("action", "device_register").put("token", token)) }
    }

    /** অ্যাপের ভাষা ("en"/"bn"), সার্ভার নোটিফিকেশনের ভাষা ঠিক করতে ব্যবহার করে */
    @Suppress("UNUSED_PARAMETER")
    suspend fun saveLang(uid: String, code: String) {
        update(org.json.JSONObject().put("lang", code))
    }

    /** প্রোফাইলের ছবি সেভ (null দিলে মুছে যায়) */
    @Suppress("UNUSED_PARAMETER")
    suspend fun savePhoto(uid: String, base64: String?) {
        // ছোট ছবিও সাথে (ডাক্তার/নার্সের তালিকার কার্ডে দেখায়)
        val thumb = Photo.thumbFromAvatar(base64)
        update(org.json.JSONObject().putN("photo", base64).putN("photoThumb", thumb))
    }

    /** প্রসেসিং তালিকায় একটা রিকোয়েস্ট যোগ (সার্ভারও লেখে; পুরোনো সাড়ার জন্য অ্যাপ নিজে মিলিয়ে নেয়) */
    @Suppress("UNUSED_PARAMETER")
    suspend fun addCommitment(uid: String, requestId: String) {
        update(commitAdd = requestId)
    }

    /** শেষ হয়ে যাওয়া (বন্ধ, বাতিল, রক্ত দেওয়া) রিকোয়েস্ট প্রসেসিং তালিকা থেকে সরানো */
    @Suppress("UNUSED_PARAMETER")
    suspend fun removeCommitments(uid: String, requestIds: Collection<String>) {
        if (requestIds.isEmpty()) return
        requestIds.toList().chunked(100).forEach { update(commitRemove = it) }
    }

}

/** রক্তের রিকোয়েস্ট — সার্ভার দিয়ে (কে কী দেখতে পারে: server/lib/clientdata.js) */
object RequestRepository {
    private suspend fun read(key: String, body: org.json.JSONObject) = OfflineCache.read(key, body)

    private fun fromJson(x: org.json.JSONObject): BloodRequest {
        val notified = x.has0("notifiedAt")
        val createdAtMillis = x.longN("createdAtMillis") ?: 0L
        return BloodRequest(
            id = x.strN("id") ?: "",
            requesterUid = x.strN("requesterUid") ?: "",
            requesterName = x.strN("requesterName") ?: "",
            patientGroup = x.strN("patientGroup") ?: "",
            bags = (x.longN("bags") ?: 1L).toInt(),
            hospital = x.strN("hospital") ?: "",
            placeName = x.strN("placeName") ?: "",
            lat = x.dblN("lat") ?: 0.0,
            lng = x.dblN("lng") ?: 0.0,
            neededByMillis = x.longN("neededByMillis") ?: 0L,
            neededAtMillis = x.longN("neededAtMillis"),
            urgent = x.optBoolean("urgent", false),
            travelFare = x.optBoolean("travelFare", false),
            note = x.strN("note") ?: "",
            status = x.strN("status") ?: "open",
            createdAtMillis = createdAtMillis,
            serverCreatedMillis = x.longN("createdAt") ?: createdAtMillis,
            notified = notified,
            notifiedCount = (x.longN("notifiedCount") ?: 0L).toInt(),
            fulfilled = x.optBoolean("fulfilled", false),
            acceptedCount = (x.longN("acceptedCount") ?: 0L).toInt().coerceAtLeast(0),
            bagsReceived = (x.longN("bagsReceived") ?: 0L).toInt().coerceAtLeast(0),
            confirmedCount = (x.longN("confirmedCount") ?: 0L).toInt().coerceAtLeast(0),
            closedAtMillis = x.longN("closedAt") ?: 0L,
            closedBy = x.strN("closedBy") ?: "",
            notifyRounds = (x.longN("notifyRounds") ?: (if (notified) 1L else 0L)).toInt(),
            lastNotifiedMillis = x.longN("lastNotifiedAt") ?: (x.longN("notifiedAt") ?: 0L),
        )
    }

    private fun responseFromJson(x: org.json.JSONObject) = DonorResponse(
        donorUid = x.strN("donorUid") ?: "",
        donorName = x.strN("donorName") ?: "",
        donorPhone = x.strN("donorPhone") ?: "",
        donorGroup = x.strN("donorGroup") ?: "",
        status = x.strN("status") ?: "accepted",
        confirmed = x.optBoolean("confirmed", false),
        distanceKm = x.longN("distanceKm")?.toInt(),
        fareAfterKm = x.longN("fareAfterKm")?.toInt(),
        createdAtMillis = x.longN("createdAtMillis") ?: 0L,
    )

    private fun org.json.JSONObject.objs(k: String): List<org.json.JSONObject> =
        optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()

    private fun action(name: String) = org.json.JSONObject().put("action", name)

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

    suspend fun get(id: String): BloodRequest? =
        read("req:$id", action("blood_request_get").put("requestId", id)).optJSONObject("request")?.let(::fromJson)

    @Suppress("UNUSED_PARAMETER")
    suspend fun mine(uid: String): List<BloodRequest> =
        read("mine", action("blood_requests_mine")).objs("items")
            .map(::fromJson)
            .sortedByDescending { it.createdAtMillis }

    /**
     * আশেপাশের খোলা রিকোয়েস্ট। সার্ভার geohash দিয়ে (না চললে কাছের জেলাগুলো) খোঁজে, চালু-গুলো রাখে, নিজেরগুলো বাদ দেয়,
     * patientGroups মিলিয়ে (সেটিংসের "তালিকায় কোন গ্রুপ"), ডোনার যতদূর যেতে রাজি ততদূর; জরুরি আগে, তারপর কাছেরটা।
     * তারিখ পার হয়ে যাওয়া রিকোয়েস্ট এখানে বন্ধ করা হয় না — রাতের কাজ বন্ধ করে রাজি ডোনারদের জানায়।
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun nearbyOpen(
        lat: Double,
        lng: Double,
        patientGroups: Set<String>?,
        excludeUid: String?,
        maxKm: Int,
    ): List<Pair<BloodRequest, Double>> {
        val groups = patientGroups?.sorted()
        val body = action("blood_requests_near").put("lat", lat).put("lng", lng).put("maxKm", maxKm)
            .put("groups", groups?.let { org.json.JSONArray(it) } ?: org.json.JSONObject.NULL)
        return read("near:$lat,$lng,$maxKm,${groups?.joinToString(",")}", body).objs("items")
            .map { fromJson(it) to it.optDouble("km", Geo.distanceKm(lat, lng, it.optDouble("lat"), it.optDouble("lng"))) }
            .sortedWith(compareByDescending<Pair<BloodRequest, Double>> { it.first.urgent }.thenBy { it.second })
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

    /** নিজের সাড়া (সার্ভার শুধু নিজেরটা দেয়) */
    suspend fun myResponse(requestId: String, uid: String): DonorResponse? =
        read("resp:$requestId", action("blood_response_get").put("requestId", requestId))
            .optJSONObject("response")?.let(::responseFromJson)?.let { if (it.donorUid.isEmpty()) it.copy(donorUid = uid) else it }

    /** শুধু রাজি থাকা বা রক্ত দেওয়া ডোনার (শুধু রক্তপ্রার্থী দেখতে পারেন; বাকিদের নম্বর নয়) */
    suspend fun responses(requestId: String): List<DonorResponse> =
        read("resps:$requestId", action("blood_responses_list").put("requestId", requestId)).objs("items")
            .map(::responseFromJson)
            .sortedBy { it.createdAtMillis }

    /** রক্তপ্রার্থীর নাম ও ফোন। শুধু রক্তপ্রার্থী নিজে বা রাজি থাকা / রক্ত দেওয়া ডোনার পায়। */
    suspend fun requesterContact(requestId: String): Pair<String, String>? {
        val c = read("contact:$requestId", action("blood_contact_get").put("requestId", requestId)).optJSONObject("contact") ?: return null
        return (c.strN("name") ?: "") to (c.strN("phone") ?: "")
    }

    /** একজন একটা রিকোয়েস্ট একবারই রিপোর্ট করতে পারেন (দ্বিতীয়বার সার্ভার "না" বলে) */
    @Suppress("UNUSED_PARAMETER")
    suspend fun report(requestId: String, uid: String) {
        ServerApi.post("/api/community", action("report_request").put("requestId", requestId))
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

    /** সঠিক লোকেশনের অনুমতি (শুধু গাড়ির জায়গার মতো কাজে চাওয়া হয়) */
    fun hasFine(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * ঠিক জায়গা (গাড়ি যেখানে অপেক্ষা করে): high accuracy, পুরোনো জায়গা নয়, সর্বোচ্চ ২০ সেকেন্ড।
     * ফেরত (lat, lng, accuracy মিটারে) — accuracy জানা না থাকলে Float.MAX_VALUE; না পেলে null।
     * শুধু আনুমানিক লোকেশনের অনুমতি দিলে accuracy খারাপ আসে — যে ডাকে সে বাতিল করে।
     */
    @SuppressLint("MissingPermission")
    suspend fun precise(context: Context): Triple<Double, Double, Float>? {
        if (!hasPermission(context) && !hasFine(context)) return null
        val client = LocationServices.getFusedLocationProviderClient(context)
        val req = com.google.android.gms.location.CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(0)
            .setDurationMillis(20_000)
            .build()
        val loc = client.getCurrentLocation(req, null).await() ?: return null
        return Triple(loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy else Float.MAX_VALUE)
    }
}
