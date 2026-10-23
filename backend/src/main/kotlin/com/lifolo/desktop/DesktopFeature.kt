package com.lifolo.desktop

import com.lifolo.common.ApiException
import com.lifolo.common.Lang
import com.lifolo.common.action.ActionContext
import com.lifolo.demo.DemoWorld
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * The feature switch `desktop` (backend/docs/FEATURES.md, owner rule 23 Oct 2026: every new feature ships OFF).
 *
 * TODO(p19): a shim until the Features service (spring-p19) is on main — then [isOn] becomes
 * `features.isOn(uid, "desktop")` and [require] `features.require(...)`; the settings below move to the admin page.
 * Until then: `lifolo.features.all-on` (FEATURES_ALL_ON), or `lifolo.features.desktop.mode` = off | pilot | on with
 * `…pilot-uids` (comma separated) and `…demo` (demo accounts in pilot). Default: off.
 *
 * Checked against the BUSINESS (bizId: the pharmacy/lab owner, the doctor): pilot = the pilot business owners' desks.
 */
@Component
class DesktopFeature(
    @Value("\${lifolo.features.all-on:false}") allOn: Boolean,
    @Value("\${lifolo.features.desktop.mode:off}") mode: String,
    @Value("\${lifolo.features.desktop.pilot-uids:}") pilotUids: String,
    @Value("\${lifolo.features.desktop.demo:false}") demo: Boolean,
) {
    @Volatile
    var mode: String = if (allOn) ON else mode.trim().lowercase()

    @Volatile
    var pilotUids: Set<String> = pilotUids.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    @Volatile
    var demo: Boolean = demo

    fun isOn(uid: String?): Boolean = when (mode) {
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
