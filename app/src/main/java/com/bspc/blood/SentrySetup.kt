package com.bspc.blood

import android.app.Application
import android.content.Context
import android.util.Log
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.android.core.SentryAndroid

/**
 * ভুলের খবর (Sentry, প্রকল্প lifolo-android) — শুধু ক্র্যাশ/ভুল আর ANR; কোনো tracing, session, স্ক্রিনশট, view hierarchy
 * বা ব্যক্তিগত তথ্য নয়। DSN বিল্ডের সময় আসে (BuildConfig.SENTRY_DSN — CI-র SENTRY_DSN_ANDROID সিক্রেট); খালি = বন্ধ।
 * পাঠানোর আগে ইমেইল, ফোন নম্বর আর টোকেন মুছে দেওয়া হয় ([SentryScrub])।
 */
object SentrySetup {
    fun init(context: Context) {
        val dsn = BuildConfig.SENTRY_DSN
        if (dsn.isBlank()) {
            Log.i("Lifolo", "sentry off (no DSN)")
            return
        }
        SentryAndroid.init(context) { o ->
            o.dsn = dsn
            o.environment = if (BuildConfig.VERSION_NAME.endsWith("-next")) "next" else "production"
            o.release = "lifolo-android@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
            o.sampleRate = 1.0
            o.tracesSampleRate = null
            o.isSendDefaultPii = false
            o.isAttachScreenshot = false
            o.isAttachViewHierarchy = false
            o.isAnrEnabled = true
            o.isEnableAutoSessionTracking = false
            o.isEnableUserInteractionBreadcrumbs = false
            o.setBeforeSend { event, _ -> SentryScrub.event(event) }
            o.setBeforeBreadcrumb { crumb, _ -> SentryScrub.crumb(crumb) }
        }
        Log.i("Lifolo", "sentry initialised: enabled=${Sentry.isEnabled()} release=lifolo-android@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}")
    }

    /** সেটিংসে "ভার্সন"-এ ৭ বার চাপলে একটা পরীক্ষার ঘটনা (লুকানো); ফেরত: ঘটনার id বা কেন যায়নি */
    fun sendTest(): String {
        if (!Sentry.isEnabled()) return "Sentry off"
        val id = Sentry.captureMessage("lifolo-android: test event (deploy check)", SentryLevel.INFO)
        Sentry.flush(5000)
        return id.toString()
    }
}

object SentryScrub {
    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val PHONE = Regex("(?<![0-9])(?:\\+?88)?01[3-9][0-9]{8}(?![0-9])|\\+[0-9][0-9 -]{9,16}[0-9]")
    private val JWT = Regex("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}")
    private val BEARER = Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+")
    private val KEYVAL = Regex("(?i)((?:token|secret|password|passwd|code|key|session|cookie|authorization|phone|email)[\"']?\\s*[:=]\\s*[\"']?)[^\\s\"'&,;}]+")

    fun text(s: String?): String? {
        if (s.isNullOrEmpty()) return s
        var t: String = s
        t = JWT.replace(t, "[token]")
        t = BEARER.replace(t, "$1[token]")
        t = KEYVAL.replace(t, "$1[filtered]")
        t = EMAIL.replace(t, "[email]")
        t = PHONE.replace(t, "[phone]")
        return t
    }

    fun event(e: SentryEvent): SentryEvent {
        e.user = null
        e.request = null
        e.message?.let { m ->
            m.formatted = text(m.formatted)
            m.message = text(m.message)
        }
        e.exceptions?.forEach { it.value = text(it.value) }
        e.breadcrumbs?.forEach { crumb(it) }
        return e
    }

    fun crumb(b: Breadcrumb): Breadcrumb {
        b.message = text(b.message)
        b.data.keys.toList().forEach { k -> (b.data[k] as? String)?.let { b.setData(k, text(it) ?: "") } }
        return b
    }
}

/** অ্যাপ চালুর সবচেয়ে শুরুতে (Activity-র আগে) — FCM বা পেছনের কাজের ক্র্যাশও ধরা পড়ে */
class LifoloApp : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching { SentrySetup.init(this) }.onFailure { Log.w("Lifolo", "sentry init failed", it) }
    }
}
