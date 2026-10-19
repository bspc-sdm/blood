package com.lifolo.users

import com.lifolo.common.action.ActionContext
import com.lifolo.common.action.ActionHandler
import com.lifolo.common.action.ActionModule
import org.springframework.stereotype.Component

/** profile_get / profile_update (clientdata.js). Not for guests (not public) nor caregivers (not in CARE_ACTIONS). */
@Component
class ProfileActions(private val profiles: ProfileService) : ActionModule {
    override fun actions(): Map<String, ActionHandler> = mapOf(
        "profile_get" to ActionHandler(::profileGet),
        "profile_update" to ActionHandler(::profileUpdate),
    )

    private fun needLogin(ctx: ActionContext) {
        if (ctx.user.uid == null) throw ctx.err(401, "Please log in", "লগইন দরকার")
    }

    fun profileGet(ctx: ActionContext): Any {
        needLogin(ctx)
        return mapOf("profile" to profiles.get(ctx.user.id))
    }

    fun profileUpdate(ctx: ActionContext): Any {
        needLogin(ctx)
        val u = profiles.parse(ctx.body, ctx.lang) ?: return linkedMapOf("ok" to true, "unchanged" to true)
        profiles.update(ctx.user.id, u, ctx.lang)
        return mapOf("ok" to true)
    }
}
