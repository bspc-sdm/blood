package com.lifolo.calls

import com.lifolo.common.ApiException
import com.lifolo.common.Lang
import com.lifolo.common.action.ActionContext
import com.lifolo.features.Features
import org.springframework.stereotype.Component

/**
 * Feature switches for calls (owner rule, 23 Oct 2026: every new feature ships OFF and is turned on step by step —
 * scratchpad flags CONTRACT / backend/docs/FEATURES.md). Keys: [CALLS_AUDIO], [CALLS_VIDEO], [APPT_VIDEO], and
 * [CHAT] (the block list is shared with messages).
 *
 * Delegates to the flags package ([Features], admin page "ফিচার সুইচ": modes, pilot list, demo, dependencies). Tests may
 * still pin the keys that are on with [override].
 */
@Component
class CallFeatures(private val features: Features) {
    /** tests: the keys that are on (null = the real feature switches) */
    @Volatile
    var override: Set<String>? = null

    fun isOn(uid: String?, key: String): Boolean {
        val on = override ?: return features.isOn(uid, key)
        if ("*" !in on && key !in on) return false
        // dependencies (contract): video needs audio
        return key != CALLS_VIDEO || isOn(uid, CALLS_AUDIO)
    }

    /** two-party features: on for BOTH people */
    fun bothOn(a: String?, b: String?, key: String): Boolean = isOn(a, key) && isOn(b, key)

    fun require(ctx: ActionContext, key: String) {
        if (!isOn(ctx.user.id, key)) throw off(ctx.lang)
    }

    /** on when any of [keys] is on (call_status / call_end serve both ring calls and appointment rooms) */
    fun requireAny(ctx: ActionContext, vararg keys: String) {
        if (keys.none { isOn(ctx.user.id, it) }) throw off(ctx.lang)
    }

    fun off(lang: String?) = ApiException(404, Lang.tr(lang, "This feature is not available yet", "এই সুবিধা এখনো চালু হয়নি"), "feature_off") // bn-ok

    companion object {
        const val CALLS_AUDIO = "calls_audio"
        const val CALLS_VIDEO = "calls_video"
        const val APPT_VIDEO = "appt_video"
        const val CHAT = "chat"
    }
}
