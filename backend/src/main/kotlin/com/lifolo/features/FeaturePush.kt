package com.lifolo.features

import com.lifolo.notify.DeliveryResult
import com.lifolo.notify.DeviceMessage
import com.lifolo.notify.PushTransport
import com.lifolo.notify.SseHub
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.stereotype.Component

/**
 * After an admin change: the silent FCM data push `{type: "features_changed", v}` to the phones of the affected
 * accounts (no notification, no bell entry, no category — the app only refreshes its switches), and the SSE event
 * `features_changed {v}` to the open web/desktop streams.
 *
 * Only accounts whose app has called `features` ([FeatureActions], table `feature_clients`) get the push: an older app
 * would show the data message as an empty notification.
 */
@Component
class FeaturePush(
    private val jdbc: JdbcTemplate,
    private val transport: PushTransport,
    private val sse: SseHub,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** in the background (an "on" change reaches every phone); tests set false */
    @Volatile
    var async: Boolean = true

    /** who is affected by a change from [old] to [new]: null = everyone, else these uids (+ every demo account when [demo]) */
    data class Audience(val everyone: Boolean, val uids: Set<String>, val demo: Boolean)

    fun audience(old: FeatureConfig, new: FeatureConfig): Audience {
        if (old.mode == Features.ON || new.mode == Features.ON) return Audience(true, emptySet(), false)
        val uids = HashSet<String>()
        var demo = false
        for (c in listOf(old, new)) {
            if (c.mode != Features.PILOT) continue
            uids += c.pilotUids
            if (c.demo) demo = true
        }
        return Audience(false, uids, demo)
    }

    fun changed(v: Long, a: Audience) {
        val job = Runnable {
            try {
                send(v, a)
            } catch (e: Exception) {
                log.error("features_changed push failed", e)
            }
        }
        if (async) Thread.ofVirtual().name("features-push").start(job) else job.run()
    }

    private fun send(v: Long, a: Audience) {
        val data = mapOf("v" to v)
        // SSE: everyone connected, or the affected accounts' streams
        if (a.everyone || a.demo) {
            sse.publishPrefix(if (a.everyone) "user:" else "user:demo-", EVENT, data)
        }
        if (!a.everyone) for (uid in a.uids) sse.publish("user:$uid", EVENT, data)

        val tokens = ArrayList<Pair<String, String>>()
        val add = RowCallbackHandler { rs -> tokens += rs.getString(1) to rs.getString(2) }
        val base = "SELECT t.token, t.uid FROM device_tokens t JOIN feature_clients f ON f.uid = t.uid"
        when {
            a.everyone -> jdbc.query(base, add)
            else -> {
                if (a.demo) jdbc.query("$base WHERE t.uid LIKE 'demo-%'", add)
                for (chunk in a.uids.filter { !(a.demo && it.startsWith("demo-")) }.chunked(500)) {
                    if (chunk.isEmpty()) continue
                    jdbc.query("$base WHERE t.uid IN (${chunk.joinToString(",") { "?" }})", add, *chunk.toTypedArray())
                }
            }
        }
        val messages = tokens.distinctBy { it.first }.map { (token, uid) ->
            DeviceMessage(token, uid, mapOf("type" to EVENT, "v" to v.toString(), "forUid" to uid))
        }
        for (chunk in messages.chunked(500)) {
            val results = try {
                transport.send(chunk)
            } catch (e: Exception) {
                log.error("fcm send failed", e)
                continue
            }
            results.forEachIndexed { i, r ->
                val m = chunk[i]
                if (r == DeliveryResult.DEAD_TOKEN && m.uid != null) {
                    runCatching { jdbc.update("DELETE FROM device_tokens WHERE token = ? AND uid = ?", m.token, m.uid) }
                }
            }
        }
    }

    companion object {
        const val EVENT = "features_changed"
    }
}
