package com.lifolo.calls

import com.lifolo.common.ApiException
import com.lifolo.common.Lang
import com.lifolo.common.action.ActionContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Feature switches for calls (owner rule, 23 Oct 2026: every new feature ships OFF and is turned on step by step —
 * scratchpad flags CONTRACT / backend/docs/FEATURES.md). Keys: [CALLS_AUDIO], [CALLS_VIDEO], [APPT_VIDEO], and
 * [CHAT] (the block list is shared with messages).
 *
 * TEMPORARY SHIM with the same signatures as the flags package's `Features.isOn / require / bothOn` (spring-p19):
 * until that lands, a key is on only when it is listed in `lifolo.feature-shim.on` (comma list, `*` = all; empty in
 * production = everything OFF; the test profile sets `*`). On the rebase onto p19, [isOn] delegates to Features.
 */
@Component
class CallFeatures(@Value("\${lifolo.feature-shim.on:}") configured: String) {
    private val listed: Set<String> = configured.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** tests: the keys that are on (null = the configured list) */
    @Volatile
    var override: Set<String>? = null

    fun isOn(uid: String?, key: String): Boolean {
        val on = override ?: listed
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
