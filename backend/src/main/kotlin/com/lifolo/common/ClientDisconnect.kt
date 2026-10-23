package com.lifolo.common

import org.springframework.web.context.request.async.AsyncRequestNotUsableException
import java.io.IOException

/**
 * "The client went away" — a phone closed the app, a tab was closed, the network dropped, or nginx closed the
 * connection while the server was still writing (an SSE stream, a slow download). Nothing is wrong on our side, so these
 * are logged at debug level only and never reach Sentry (SseHub, ApiErrorHandler, SentryScrubber).
 */
object ClientDisconnect {
    private val TYPES = setOf(
        "AsyncRequestNotUsableException", // Spring 6.1+: the response is no longer usable (wrapped IOException)
        "ClientAbortException", // Tomcat: org.apache.catalina.connector.ClientAbortException
        "EofException", // Jetty
    )
    private val MESSAGES = listOf(
        "broken pipe",
        "connection reset by peer",
        "an established connection was aborted",
        "an existing connection was forcibly closed",
        "response not usable after response errors",
    )

    /** true when [t] or any of its causes is a client disconnect */
    fun isClientDisconnect(t: Throwable?): Boolean {
        var cur = t
        var depth = 0
        while (cur != null && depth < 10) {
            if (cur is AsyncRequestNotUsableException) return true
            if (cur.javaClass.simpleName in TYPES) return true
            if (cur is IOException && messageMatches(cur.message)) return true
            if (cur.cause === cur) break
            cur = cur.cause
            depth++
        }
        return false
    }

    /** the same check on what Sentry keeps of an exception: its simple type name and message */
    fun matches(type: String?, message: String?): Boolean =
        (type != null && type.substringAfterLast('.') in TYPES) ||
            ((type == "IOException" || type == "java.io.IOException") && messageMatches(message))

    private fun messageMatches(message: String?): Boolean {
        val m = message?.lowercase() ?: return false
        return MESSAGES.any { m.contains(it) }
    }
}
