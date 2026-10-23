package com.lifolo.desktop

import com.lifolo.common.ApiException
import com.lifolo.common.Lang
import com.lifolo.common.action.ActionContext
import com.lifolo.demo.DemoWorld
import com.lifolo.features.Features
import org.springframework.stereotype.Component

/**
 * The feature switch `desktop` (backend/docs/FEATURES.md, owner rule 23 Oct 2026: every new feature ships OFF).
 *
 * Reads the `desktop` key of [Features] (admin page "ফিচার সুইচ"). Tests may pin it with [mode] (+ [pilotUids], [demo]);
 * null = the real switch.
 *
 * Checked against the BUSINESS (bizId: the pharmacy/lab owner, the doctor): pilot = the pilot business owners' desks.
 */
@Component
class DesktopFeature(private val features: Features) {
    /** tests: off | pilot | on instead of the real switch (null = the switch) */
    @Volatile
    var mode: String? = null

    @Volatile
    var pilotUids: Set<String> = emptySet()

    @Volatile
    var demo: Boolean = false

    fun isOn(uid: String?): Boolean = when (mode ?: return features.isOn(uid, KEY)) {
        ON -> true
        PILOT -> uid != null && (uid in pilotUids || (demo && DemoWorld.isDemoUid(uid)))
        else -> false
    }

    /** 404 `feature_off` unless the switch is on for the business [bizId] */
    fun require(ctx: ActionContext, bizId: String?) {
        if (!isOn(bizId)) throw offError(ctx.lang)
    }

    companion object {
        const val KEY = "desktop"
        const val OFF = "off"
        const val PILOT = "pilot"
        const val ON = "on"

        fun offError(lang: String?) = ApiException(
            404, Lang.tr(lang, "This feature is not available yet", "এই সুবিধা এখনো চালু হয়নি"), "feature_off", // bn-ok
        )
    }
}
