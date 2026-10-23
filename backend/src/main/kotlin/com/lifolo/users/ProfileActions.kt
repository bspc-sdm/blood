package com.lifolo.users

import com.lifolo.common.action.ActionContext
import com.lifolo.common.action.ActionHandler
import com.lifolo.common.action.ActionModule
import com.lifolo.practice.TitleGuard
import org.springframework.stereotype.Component

/** profile_get / profile_update (clientdata.js). Not for guests (not public) nor caregivers (not in CARE_ACTIONS). */
@Component
class ProfileActions(
    private val profiles: ProfileService,
    private val accounts: AccountService,
    private val titles: TitleGuard,
) : ActionModule {
    override fun actions(): Map<String, ActionHandler> = mapOf(
        "profile_get" to ActionHandler(::profileGet),
        "profile_update" to ActionHandler(::profileUpdate),
    )

    private fun needLogin(ctx: ActionContext) {
        if (ctx.user.uid == null) throw ctx.err(401, "Please log in", "লগইন দরকার")
    }

    fun profileGet(ctx: ActionContext): Any {
        needLogin(ctx)
        // account: active / deactivated / deleting, so the apps can show the right screen
        return linkedMapOf("profile" to profiles.get(ctx.user.id), "account" to accounts.state(ctx.user.id))
    }

    fun profileUpdate(ctx: ActionContext): Any {
        needLogin(ctx)
        val u = profiles.parse(ctx.body, ctx.lang) ?: return linkedMapOf("ok" to true, "unchanged" to true)
        // title_guard (backend/docs/PRACTICE.md §6): a provider who is not an MBBS/BDS doctor cannot write "ডা." / "Dr."
        val name = (u.fields["name"] as? ProfileService.Value.Set)?.v as? String
        if (name != null) titles.requireOwnName(ctx.caller.uid ?: ctx.user.uid, ctx.user.id, name, ctx.lang)
        profiles.update(ctx.user.id, u, ctx.lang)
        if ("name" in u.fields) titles.clearCache()
        return mapOf("ok" to true)
    }
}
