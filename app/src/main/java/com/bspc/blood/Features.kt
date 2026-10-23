package com.bspc.blood

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * ফিচার সুইচ (backend/docs/FEATURES.md): নতুন সুবিধা বন্ধ অবস্থায় আসে; মালিক অ্যাডমিন পাতা থেকে ধাপে ধাপে চালু করেন —
 * অ্যাপ আপডেট ছাড়াই।
 *
 *  - `Features.on(Features.CHAT)` — এখন চালু? (Compose-এ `featureOn(Features.CHAT)` — বদলালে আবার আঁকে)
 *  - বন্ধ থাকলে সুবিধার সব ঢোকার পথ লুকানো: বোতাম, ট্যাব, মেনুর সারি, সেটিংসের সুইচ
 *  - ডিপ লিংক/নোটিফিকেশন দিয়ে বন্ধ সুবিধায় এলে [soonText] ("এই সুবিধা শিগগির আসছে")
 *  - সার্ভারের 404 `feature_off` ([isFeatureOff]): বার্তা সার্ভারের, দেখালেই হয়; সাথে নতুন করে আনা
 *
 * নতুন করে আনা: অ্যাপ চালু আর লগইন বদলালে ([refresh]), অ্যাপে ফিরে এলে (মিনিটে একবারের বেশি নয়, [refreshSoon]),
 * আর `features_changed` পুশ এলে ([onPush])। শেষ জানা অবস্থা ফোনে থাকে, তাই ইন্টারনেট ছাড়াও চলে।
 */
object Features {
    const val CALLS_AUDIO = "calls_audio"
    const val CALLS_VIDEO = "calls_video"
    const val APPT_VIDEO = "appt_video"
    const val CHAT = "chat"
    const val CHAT_MEDIA = "chat_media"
    const val ONLINE_CHAMBER = "online_chamber"
    const val INV_PHARMACY = "inv_pharmacy"
    const val INV_LAB = "inv_lab"
    const val INV_CHAMBER = "inv_chamber"
    const val INV_AMBULANCE = "inv_ambulance"
    const val MY_EXPENSES = "my_expenses"
    const val DESKTOP = "desktop"
    const val PAY_MANUAL = "pay_manual"
    const val PAY_CHAMBER_ADVANCE = "pay_chamber_advance"
    const val PAY_DELIVERY = "pay_delivery"
    const val PAY_LAB = "pay_lab"
    const val PAY_AMBULANCE = "pay_ambulance"
    const val PAY_DUE = "pay_due"
    const val PAY_BKASH_PGW = "pay_bkash_pgw"
    const val PRACTICE_SYSTEMS = "practice_systems"
    const val PRO_PHYSIO = "pro_physio"
    const val PRO_PSYCHOLOGIST = "pro_psychologist"

    /** স্বাস্থ্য তথ্যের সম্মতি (backend/docs/CONSENT.md) */
    const val HEALTH_CONSENT = "health_consent"

    val KEYS = listOf(
        CALLS_AUDIO, CALLS_VIDEO, APPT_VIDEO, CHAT, CHAT_MEDIA, ONLINE_CHAMBER,
        INV_PHARMACY, INV_LAB, INV_CHAMBER, INV_AMBULANCE, MY_EXPENSES, DESKTOP,
        PAY_MANUAL, PAY_CHAMBER_ADVANCE, PAY_DELIVERY, PAY_LAB, PAY_AMBULANCE, PAY_DUE, PAY_BKASH_PGW,
        PRACTICE_SYSTEMS, PRO_PHYSIO, PRO_PSYCHOLOGIST,
        HEALTH_CONSENT,
    )

    /** সার্ভারের বন্ধ-সুবিধার ভুল (404) */
    const val OFF_CODE = "feature_off"

    /** পুশের data.type */
    const val PUSH_TYPE = "features_changed"

    private const val FILE = "features"
    private const val MIN_GAP_MS = 60_000L

    private val _keys = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /** চাবি → চালু? (সব চাবি না-ও থাকতে পারে — না থাকা মানে বন্ধ) */
    val keys: StateFlow<Map<String, Boolean>> = _keys.asStateFlow()

    /** সার্ভারের সংস্করণ (প্রতিটা বদলে বাড়ে) */
    @Volatile
    var version: Long = 0L
        private set

    /** কার জন্য আনা (লগইন বদলালে আবার) */
    @Volatile
    private var forUid: String? = null

    @Volatile
    private var lastAt: Long = 0L

    private var appContext: Context? = null
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** চালু? */
    fun on(key: String): Boolean = _keys.value[key] == true

    /** "এই সুবিধা শিগগির আসছে" — বন্ধ সুবিধার ডিপ লিংকে */
    fun soonText(): String = tr("This feature is coming soon", "এই সুবিধা শিগগির আসছে")

    /** সার্ভার বলল সুবিধাটা বন্ধ? */
    fun isFeatureOff(e: Throwable?): Boolean = e is ServerError && e.code == OFF_CODE

    /** MainActivity.onCreate: ফোনে রাখা শেষ অবস্থা (অফলাইনে চলার জন্য) */
    fun init(context: Context) {
        appContext = context.applicationContext
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val saved = p.getString("json", null) ?: return
        runCatching {
            val o = JSONObject(saved)
            forUid = o.optString("uid").ifEmpty { null }
            version = o.optLong("v", 0L)
            _keys.value = parse(o)
        }
    }

    /** সার্ভারের `{keys: {...}, v}` → চাবি → চালু? (শুধু সত্যিকারের true চালু) */
    fun parse(json: JSONObject): Map<String, Boolean> {
        val k = json.optJSONObject("keys") ?: return emptyMap()
        val out = LinkedHashMap<String, Boolean>()
        val it = k.keys()
        while (it.hasNext()) {
            val name = it.next()
            out[name] = k.opt(name) == true
        }
        return out
    }

    /** পরীক্ষা/সার্ভারের উত্তর বসানো (রাখাও হয়) */
    fun set(keys: Map<String, Boolean>, v: Long = version, uid: String? = AuthRepository.currentUid) {
        _keys.value = keys
        version = v
        forUid = uid
        lastAt = System.currentTimeMillis()
        val ctx = appContext ?: return
        val o = JSONObject().put("uid", uid ?: "").put("v", v).put("keys", JSONObject(keys as Map<*, *>))
        runCatching { ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("json", o.toString()).apply() }
    }

    /**
     * সার্ভার থেকে নতুন করে (`features`, অতিথিও পারে)। [force] ছাড়া: একই মানুষের জন্য শেষ আনার ১ মিনিটের মধ্যে আবার নয়।
     * ব্যর্থ হলে আগের অবস্থাই থাকে — কখনো throw করে না।
     */
    suspend fun refresh(force: Boolean = true) {
        mutex.withLock {
            val uid = AuthRepository.currentUid
            if (!force && uid == forUid && System.currentTimeMillis() - lastAt < MIN_GAP_MS) return
            runCatching { ServerApi.post("/api/community", JSONObject().put("action", "features")) }
                .onSuccess { r ->
                    // এর মধ্যে লগইন বদলালে এই উত্তর অন্য কারো — পরের ডাক আনবে
                    if (AuthRepository.currentUid == uid) set(parse(r), r.optLong("v", 0L), uid)
                }
        }
    }

    /** অ্যাপে ফিরে এলে (ON_RESUME): মিনিটে একবারের বেশি নয় */
    fun refreshSoon() {
        scope.launch { refresh(force = false) }
    }

    /** পুশ এলে অ্যাপ বন্ধ থাকলেও (শুধু সার্ভিস চলছে) ফোনে রাখা অবস্থা পড়া */
    fun ensureInit(context: Context) {
        if (appContext == null) init(context)
    }

    /** `features_changed` পুশ বা সার্ভারের `feature_off`: এখনই নতুন করে */
    fun onPush() {
        scope.launch { refresh(force = true) }
    }

    /** লগআউটে: আগের মানুষের পাইলট সুবিধা পরের জনের কাছে না থাকে */
    fun clear() {
        set(emptyMap(), 0L, null)
        lastAt = 0L
    }
}

/** Compose-এ: এই সুবিধা চালু? (বদলালে আবার আঁকে) */
@Composable
fun featureOn(key: String): Boolean {
    val keys by Features.keys.collectAsState()
    return keys[key] == true
}
