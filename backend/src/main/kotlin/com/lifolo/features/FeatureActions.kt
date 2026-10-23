package com.lifolo.features

import com.lifolo.common.action.ActionContext
import com.lifolo.common.action.ActionHandler
import com.lifolo.common.action.ActionModule
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * `features` (guest allowed, never cached): which keys are on for the caller → `{keys: {calls_audio: true, …}, v}`.
 * The apps ask on start, login, resume (at most once a minute) and on the `features_changed` push/SSE event.
 */
@Component
class FeatureActions(
    private val features: Features,
    private val jdbc: JdbcTemplate,
) : ActionModule {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun actions(): Map<String, ActionHandler> = mapOf(
        "features" to ActionHandler { mine(it) },
    )

    fun mine(ctx: ActionContext): Any {
        // the person using the app (a caregiver acting for a patient sees their own switches)
        val uid = if (ctx.caller.guest) null else ctx.caller.uid ?: ctx.user.uid
        if (uid != null) remember(uid)
        return mapOf("keys" to features.keysFor(uid), "v" to features.version())
    }

    /** this account's app knows `features` → it may get the silent features_changed push (at most one write a day) */
    private fun remember(uid: String) {
        try {
            jdbc.update(
                """
                INSERT INTO feature_clients (uid, seen_at) SELECT ?, now() WHERE EXISTS (SELECT 1 FROM accounts WHERE uid = ?)
                ON CONFLICT (uid) DO UPDATE SET seen_at = now() WHERE feature_clients.seen_at < now() - interval '1 day'
                """.trimIndent(),
                uid, uid,
            )
        } catch (e: Exception) {
            log.warn("feature client write failed: {}", e.message)
        }
    }
}
