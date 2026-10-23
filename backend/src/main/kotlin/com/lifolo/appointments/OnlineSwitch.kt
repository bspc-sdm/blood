package com.lifolo.appointments

import com.lifolo.common.LifoloProperties
import com.lifolo.demo.DemoWorld
import org.springframework.stereotype.Component

/** may this (demo) doctor have an online chamber? Real doctors always; demo ones only with DEMO_ONLINE_CHAMBERS (R6) */
@Component
class OnlineSwitch(props: LifoloProperties) {
    /** demo online chambers on (tests flip it) */
    @Volatile
    var demoEnabled: Boolean = props.demo.onlineChambers

    fun allowed(providerUid: String): Boolean = demoEnabled || !DemoWorld.isDemoUid(providerUid)
}
