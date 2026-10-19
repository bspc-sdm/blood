package com.lifolo.auth

import com.lifolo.common.action.ActionHandler
import com.lifolo.common.action.ActionModule
import org.springframework.stereotype.Component

/** `/api/community` actions of the auth package. */
@Component
class AuthActions(private val webSession: WebSession) : ActionModule {
    override fun actions(): Map<String, ActionHandler> = mapOf(
        // community.js web_logout: clear the admin panel cookie
        "web_logout" to ActionHandler { ctx ->
            webSession.end(ctx.response)
            mapOf("ok" to true)
        },
    )
}
