package com.lifolo.appointments

import com.lifolo.common.action.ActionContext
import com.lifolo.demo.DemoWorld
import com.lifolo.features.Features
import org.springframework.stereotype.Component

/**
 * Online chambers are behind the `online_chamber` feature switch (docs/FEATURES.md). Demo accounts may use them exactly
 * when the switch is on for them (pilot with "ডেমো অ্যাকাউন্টে চালু", or on for everyone) — this replaced
 * DEMO_ONLINE_CHAMBERS (R6). Demo and real accounts still never meet (the sameWorld checks of book / pay / report).
 */
@Component
class OnlineSwitch(private val features: Features) {
    /** may [uid] have, book or pay an online chamber? */
    fun allowed(uid: String?): Boolean = features.isOn(uid, Features.ONLINE_CHAMBER)

    /** first line of every online-chamber action: 404 `feature_off` when the switch is off for the caller */
    fun require(ctx: ActionContext) = features.require(ctx, Features.ONLINE_CHAMBER)

    /** the demo world has its online chamber (DemoRx seeds it, DemoRefresh rebuilds when this changes) */
    val demoEnabled: Boolean get() = features.isOn(DemoWorld.uidOf("doctor"), Features.ONLINE_CHAMBER)
}
