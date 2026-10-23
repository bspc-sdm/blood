package com.bspc.blood

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** সারিতে থাকা একটা মেসেজ (ছবি আলাদা ফাইলে — photoFile) */
data class QueuedChat(
    val clientId: String,
    val uid: String,
    val threadId: String,
    val text: String = "",
    val photoFile: String = "",
    val location: ChatLocation? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val attempts: Int = 0,
    /** আর চেষ্টা হবে না — কেন (বাংলা বার্তা) */
    val failedReason: String? = null,
) {
    /** পর্দায় দেখানোর মেসেজ (সারির) */
    fun asMessage(): ChatMessage = ChatMessage(
        id = "", threadId = threadId, from = uid, text = text, location = location, at = createdAt,
        clientId = clientId, pending = failedReason == null, failedReason = failedReason,
        photoThumb = if (photoFile.isNotBlank()) LOCAL_PHOTO + photoFile else "",
    )

    fun toJson(): JSONObject = JSONObject()
        .put("clientId", clientId).put("uid", uid).put("threadId", threadId).put("text", text).put("photoFile", photoFile)
        .put("createdAt", createdAt).put("attempts", attempts)
        .apply {
            if (location != null) put("lat", location.lat).put("lng", location.lng)
            if (failedReason != null) put("failedReason", failedReason)
        }

    companion object {
        /** photoThumb এভাবে শুরু হলে এই ফোনের ফাইল (এখনো পাঠানো হয়নি) */
        const val LOCAL_PHOTO = "file:"

        fun fromJson(o: JSONObject) = QueuedChat(
            clientId = o.optString("clientId"), uid = o.optString("uid"), threadId = o.optString("threadId"),
            text = o.optString("text"), photoFile = o.optString("photoFile"),
            location = if (o.has("lat") && o.has("lng")) ChatLocation(o.optDouble("lat"), o.optDouble("lng")) else null,
            createdAt = o.optLong("createdAt"), attempts = o.optInt("attempts"),
            failedReason = if (o.has("failedReason")) o.optString("failedReason") else null,
        )
    }
}

/** আবার চেষ্টার হিসাব (টেস্ট করা যায়) */
object ChatRetry {
    /** কত পরে আবার: ২, ৪, ৮ … সেকেন্ড, সর্বোচ্চ ১ মিনিট; অনেক তাড়াতাড়ি (429) হলে অন্তত ২০ সেকেন্ড */
    fun delayMs(attempts: Int, rateLimited: Boolean = false): Long {
        val base = (2_000L shl attempts.coerceIn(0, 5)).coerceAtMost(60_000L)
        return if (rateLimited) maxOf(base, 20_000L) else base
    }

    /** এতবার ব্যর্থ হলে আর নিজে থেকে নয় (ইন্টারনেট ফিরলে বা পর্দা খুললে আবার শুরু) */
    const val MAX_AUTO = 8
}

/**
 * পাঠানোর সারি: প্রতিটা মেসেজ আগে ফোনে লেখা হয় (clientId সহ), তারপর পাঠানো। ইন্টারনেট না থাকলে বা সার্ভারের
 * সাময়িক সমস্যায় পরে আবার — একই clientId, তাই সার্ভারে দুবার হয় না। অ্যাপ বন্ধ হয়ে খুললেও সারি থাকে।
 * বন্ধ থ্রেড / অনুমতি নেই / ব্লক: আর চেষ্টা নয়, মেসেজে "পাঠানো যায়নি" (মুছে ফেলা বা আবার চেষ্টা)।
 */
object ChatQueue {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val _items = MutableStateFlow<List<QueuedChat>>(emptyList())
    val items: StateFlow<List<QueuedChat>> = _items
    private var dir: File? = null
    private var running: Job? = null
    @Volatile private var netRegistered = false

    fun init(context: Context) {
        if (dir != null) return
        synchronized(this) {
            if (dir != null) return
            val d = File(context.applicationContext.filesDir, "chat_queue").apply { mkdirs() }
            dir = d
            _items.value = runCatching {
                val f = File(d, "queue.json")
                if (!f.exists()) emptyList() else JSONArray(f.readText()).let { a -> (0 until a.length()).map { QueuedChat.fromJson(a.getJSONObject(it)) } }
            }.getOrDefault(emptyList())
        }
        registerNetwork(context.applicationContext)
        kick()
    }

    private fun save() {
        val d = dir ?: return
        runCatching {
            val tmp = File(d, "queue.json.tmp")
            tmp.writeText(JSONArray().apply { _items.value.forEach { put(it.toJson()) } }.toString())
            tmp.renameTo(File(d, "queue.json"))
        }
    }

    fun photoFile(name: String): File? = dir?.let { File(it, name) }

    /** লেখা/ছবি/লোকেশন সারিতে — তারপর পাঠানো শুরু */
    fun enqueue(context: Context, threadId: String, text: String = "", photoJpeg: ByteArray? = null, location: ChatLocation? = null): QueuedChat? {
        init(context)
        val uid = AuthRepository.currentUid ?: return null
        val id = ChatLogic.newClientId()
        var photoName = ""
        if (photoJpeg != null) {
            photoName = "$id.jpg"
            runCatching { photoFile(photoName)?.writeBytes(photoJpeg) }.onFailure { return null }
        }
        val q = QueuedChat(id, uid, threadId, text.trim().take(ChatApi.MAX_TEXT), photoName, location)
        synchronized(this) {
            _items.value = _items.value + q
            save()
        }
        kick()
        return q
    }

    /** "আবার চেষ্টা" — ব্যর্থ মেসেজ আবার সারিতে */
    fun retry(clientId: String) {
        synchronized(this) {
            _items.value = _items.value.map { if (it.clientId == clientId) it.copy(failedReason = null, attempts = 0) else it }
            save()
        }
        kick()
    }

    /** সারি থেকে মোছা (ছবির ফাইলসহ) */
    fun remove(clientId: String) {
        synchronized(this) {
            val gone = _items.value.filter { it.clientId == clientId }
            _items.value = _items.value.filter { it.clientId != clientId }
            gone.forEach { q -> if (q.photoFile.isNotBlank()) photoFile(q.photoFile)?.delete() }
            save()
        }
    }

    /** লগআউট: অন্য অ্যাকাউন্টের সারি পাঠানো হয় না (শুধু এই অ্যাকাউন্টেরগুলো থাকে) */
    fun forUid(uid: String?): List<QueuedChat> = _items.value.filter { it.uid == uid }

    /** এখনই পাঠানো শুরু (চলছে থাকলে কিছু নয়) */
    fun kick() {
        if (dir == null) return
        synchronized(this) {
            // চলছে: শেষ হলে আবার একবার (এর মধ্যে নতুন মেসেজ এসে থাকতে পারে)
            if (running?.isActive == true) { again = true; return }
            running = scope.launch {
                do { again = false; drain() } while (again)
            }
        }
    }

    @Volatile private var again = false

    private suspend fun drain() = lock.withLock {
        while (true) {
            val me = AuthRepository.currentUid ?: return@withLock
            val next = _items.value.firstOrNull { it.uid == me && it.failedReason == null } ?: return@withLock
            // একই থ্রেডের আগের ব্যর্থ মেসেজের পরে পাঠালে ক্রম ঠিক থাকে না — তবু পাঠানো হয় (আগেরটা চিরতরে ব্যর্থ)
            val photo = if (next.photoFile.isNotBlank()) {
                runCatching { photoFile(next.photoFile)?.readBytes() }.getOrNull()?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
            } else null
            if (next.photoFile.isNotBlank() && photo == null) {
                fail(next, ChatFailure.OTHER, tr("The photo is missing", "ছবিটা আর পাওয়া যাচ্ছে না"))
                continue
            }
            val r = runCatching { ChatApi.send(next.threadId, next.clientId, next.text.ifBlank { null }, photo, next.location) }
            val sent = r.getOrNull()
            if (sent != null) {
                remove(next.clientId)
                ChatEvents.emit(ChatEvents.Event.Sent(next.threadId, sent.copy(clientId = next.clientId)))
                continue
            }
            val e = r.exceptionOrNull() ?: return@withLock
            if (ChatErrors.retryable(e)) {
                val attempts = next.attempts + 1
                synchronized(this) {
                    _items.value = _items.value.map { if (it.clientId == next.clientId) it.copy(attempts = attempts) else it }
                    save()
                }
                if (ChatErrors.of(e) == ChatFailure.RATE_LIMIT) {
                    ChatEvents.emit(ChatEvents.Event.Delayed(next.threadId, ChatErrors.message(ChatFailure.RATE_LIMIT)))
                }
                if (attempts < ChatRetry.MAX_AUTO) {
                    val wait = ChatRetry.delayMs(attempts, ChatErrors.of(e) == ChatFailure.RATE_LIMIT)
                    scope.launch { delay(wait); kick() }
                }
                return@withLock
            }
            val f = ChatErrors.of(e)
            fail(next, f, ChatErrors.message(f, e.message))
        }
    }

    private fun fail(q: QueuedChat, f: ChatFailure, text: String) {
        synchronized(this) {
            _items.value = _items.value.map { if (it.clientId == q.clientId) it.copy(failedReason = text) else it }
            save()
        }
        ChatEvents.emit(ChatEvents.Event.Failed(q.threadId, q.clientId, f, text))
    }

    /** ইন্টারনেট ফিরলে সারি পাঠানো */
    private fun registerNetwork(context: Context) {
        if (netRegistered) return
        netRegistered = true
        runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
            val req = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
            cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    // ব্যর্থ হয়ে থেমে থাকা (MAX_AUTO) মেসেজও আবার
                    synchronized(this@ChatQueue) {
                        if (_items.value.any { it.failedReason == null && it.attempts > 0 }) {
                            _items.value = _items.value.map { if (it.failedReason == null) it.copy(attempts = 0) else it }
                            save()
                        }
                    }
                    kick()
                }
            })
        }
    }
}
