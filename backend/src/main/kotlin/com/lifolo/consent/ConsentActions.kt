package com.lifolo.consent

import com.lifolo.common.action.ActionContext
import com.lifolo.common.action.ActionHandler
import com.lifolo.common.action.ActionModule
import com.lifolo.features.Features
import org.springframework.stereotype.Component

/**
 * Explicit consent for health data (backend/docs/CONSENT.md), behind the `health_consent` switch.
 *  - `consent_status` → the caller's state per scope, whether to ask, and the text to show (the caller's language)
 *  - `consent_grant {scopes, version, source}` → one row per scope; the version must be the current text's
 *  - `consent_withdraw {scope, source}` (`scope`: health_data | data_abroad | all) → one row per granted scope
 * Only the person themselves — never a caregiver (CareAccess refuses `consent_*` with `asUid`). Status and withdrawal
 * work even while the switch is off (withdrawing must always be possible); granting needs the switch.
 */
@Component
class ConsentActions(
    private val consents: Consents,
    private val text: ConsentText,
    private val features: Features,
) : ActionModule {
    override fun actions(): Map<String, ActionHandler> = mapOf(
        "consent_status" to ActionHandler(::status),
        "consent_grant" to ActionHandler(::grant),
        "consent_withdraw" to ActionHandler(::withdraw),
    )

    private fun needLogin(ctx: ActionContext): String {
        if (ctx.user.uid == null) throw ctx.err(401, "Please log in", "লগইন দরকার")
        // never on someone else's behalf (CareAccess already refuses asUid; checked again)
        if (ctx.user.actingBy != null) throw ConsentGate.selfOnly(ctx, text)
        return ctx.user.id
    }

    fun status(ctx: ActionContext): Any {
        val uid = needLogin(ctx)
        return statusOf(ctx, uid)
    }

    private fun statusOf(ctx: ActionContext, uid: String): Map<String, Any?> {
        val version = text.version
        val states = consents.states(uid, version)
        val on = features.isOn(uid, Features.HEALTH_CONSENT)
        val needed = states.filterValues { it.state != Consents.GRANTED }.keys.toList()
        val granted = states.values.filter { it.state == Consents.GRANTED }
        return linkedMapOf(
            "on" to on,
            "version" to version,
            "current" to needed.isEmpty(),
            // the apps ask (on opening a health section, or after a 428) only when this is true
            "ask" to (on && needed.isNotEmpty()),
            "needed" to needed,
            "grantedAt" to (if (needed.isEmpty()) granted.mapNotNull { it.at }.maxOrNull() else null),
            "scopes" to states.mapValues { (_, s) ->
                linkedMapOf("state" to s.state, "version" to s.version, "at" to s.at, "source" to s.source)
            },
            "history" to consents.history(uid),
            "text" to text.text(ctx.lang),
        )
    }

    private fun source(ctx: ActionContext): String =
        ctx.body.string("source")?.takeIf { it in Consents.SOURCES }
            ?: throw ctx.err(400, "Unknown source", "অজানা উৎস", "bad_source") // bn-ok

    fun grant(ctx: ActionContext): Any {
        val uid = needLogin(ctx)
        features.require(ctx, Features.HEALTH_CONSENT)
        val raw = ctx.body["scopes"]
        val scopes = if (raw != null && raw.isArray) raw.mapNotNull { if (it.isTextual) it.textValue() else null } else emptyList()
        if (scopes.isEmpty() || scopes.size != raw!!.size() || !scopes.all { it in Consents.SCOPES }) {
            throw ctx.err(400, "Unknown consent", "অজানা সম্মতি", "bad_scope") // bn-ok
        }
        val src = source(ctx)
        // the person must have seen the current text: an older screen (the lawyer changed it meanwhile) → shown again
        if (ctx.body.string("version") != text.version) {
            throw com.lifolo.common.ApiException(
                409,
                ctx.tr("The consent text has changed. Please read it again.", "সম্মতির লেখা বদলেছে। আবার পড়ে দেখুন।"), // bn-ok
                "consent_outdated",
                mapOf("version" to text.version),
            )
        }
        val recorded = consents.grant(uid, scopes.distinct(), text.version, src)
        return statusOf(ctx, uid) + mapOf("ok" to true, "recorded" to recorded)
    }

    fun withdraw(ctx: ActionContext): Any {
        val uid = needLogin(ctx)
        val scope = ctx.body.string("scope")
        val scopes = when (scope) {
            "all" -> Consents.SCOPES
            in Consents.SCOPES -> listOf(scope!!)
            else -> throw ctx.err(400, "Unknown consent", "অজানা সম্মতি", "bad_scope") // bn-ok
        }
        val src = source(ctx)
        // never deletes anything: existing data stays until the person deletes it or the account
        val recorded = consents.withdraw(uid, scopes, text.version, src)
        val out = statusOf(ctx, uid) + mapOf("ok" to true, "recorded" to recorded)
        return if (recorded.isEmpty()) out + mapOf("unchanged" to true) else out
    }
}
