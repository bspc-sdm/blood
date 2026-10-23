package com.lifolo.notify

import com.lifolo.common.ApiErrorHandler
import com.lifolo.common.ClientDisconnect
import com.lifolo.common.SentryScrubber
import io.sentry.Hint
import io.sentry.SentryEvent
import io.sentry.protocol.SentryException
import org.apache.catalina.connector.ClientAbortException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.context.request.async.AsyncRequestNotUsableException
import org.springframework.web.context.request.async.AsyncRequestTimeoutException
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder
import java.io.IOException

/**
 * A client that went away (phone locked, tab closed, network gone) must not become a Sentry error: the stream is
 * completed and removed quietly — the Sentry noise was `AsyncRequestNotUsableException / IOException: Broken pipe at
 * SseHub.pingAll`.
 */
class SseHubDisconnectTest {
    /** an emitter whose socket is gone: every send fails like Tomcat's does after the client closed the connection */
    private class GoneEmitter(private val failure: () -> Exception) : SseEmitter(60_000L) {
        var sends = 0
        var completes = 0

        override fun send(builder: SseEventBuilder) {
            sends++
            throw failure()
        }

        override fun complete() {
            completes++
            super.complete()
        }
    }

    /** a live emitter that records what it got */
    private class LiveEmitter : SseEmitter(60_000L) {
        var sends = 0

        override fun send(builder: SseEventBuilder) {
            sends++
        }
    }

    private fun hubWith(vararg emitters: SseEmitter): SseHub {
        val hub = SseHub(0) // no schedule: the test calls pingAll() itself
        val queue = ArrayDeque(emitters.toList())
        hub.emitterFactory = { queue.removeFirst() }
        return hub
    }

    @Test
    fun `ping to a client that went away (broken pipe) drops it quietly and keeps the others`() {
        val gone = GoneEmitter { AsyncRequestNotUsableException("ServletOutputStream failed to write: java.io.IOException: Broken pipe", IOException("Broken pipe")) }
        val live = LiveEmitter()
        val hub = hubWith(gone, live)
        hub.subscribe("user:a")
        hub.subscribe("user:b")

        assertEquals(1, hub.pingAll()) // no exception escapes
        assertEquals(1, gone.sends)
        assertEquals(1, gone.completes, "the dead emitter is completed")
        assertEquals(0, hub.subscribers("user:a"), "and removed")
        assertEquals(1, hub.subscribers("user:b"), "the live stream stays")
        assertEquals(1L, hub.dropped.get())

        // the next round does not touch the dropped emitter again
        assertEquals(1, hub.pingAll())
        assertEquals(1, gone.sends)
        assertEquals(2, live.sends)
    }

    @Test
    fun `plain IOException, Tomcat ClientAbortException and an already completed emitter are dropped too`() {
        val failures = listOf<() -> Exception>(
            { IOException("Broken pipe") },
            { ClientAbortException(IOException("Connection reset by peer")) },
            { IllegalStateException("ResponseBodyEmitter has already completed") },
        )
        for (f in failures) {
            val gone = GoneEmitter(f)
            val hub = hubWith(gone)
            hub.subscribe("user:x")
            assertEquals(0, hub.publish("user:x", "hello", mapOf("n" to 1)), "publish must not throw for ${f()}")
            assertEquals(0, hub.subscribers("user:x"))
            assertEquals(1, gone.completes)
        }
    }

    @Test
    fun `publish to several streams skips the dead one`() {
        val gone = GoneEmitter { IOException("Broken pipe") }
        val live = LiveEmitter()
        val hub = hubWith(gone, live)
        hub.subscribe("user:same")
        hub.subscribe("user:same")
        assertEquals(1, hub.publish("user:same", "x", mapOf("a" to 1)))
        assertEquals(1, hub.subscribers("user:same"))
    }

    @Test
    fun `shutdown closes every open stream (clients reconnect to the new instance)`() {
        val a = LiveEmitter()
        val b = LiveEmitter()
        val hub = hubWith(a, b)
        hub.subscribe("user:a")
        hub.subscribe("user:b")
        hub.closeAll()
        assertEquals(0, hub.subscribers("user:a"))
        assertEquals(0, hub.subscribers("user:b"))
    }

    /** an emitter that keeps the timeout callback so the test can fire it (the container would, after 30 minutes) */
    private class TimeoutEmitter : SseEmitter(60_000L) {
        var onTimeoutCb: Runnable? = null
        var completes = 0

        override fun onTimeout(callback: Runnable) {
            onTimeoutCb = callback
            super.onTimeout(callback)
        }

        override fun complete() {
            completes++
            super.complete()
        }
    }

    @Test
    fun `a stream that reaches its timeout is completed and removed (no AsyncRequestTimeoutException error)`() {
        val e = TimeoutEmitter()
        val hub = hubWith(e)
        hub.subscribe("user:t")
        assertEquals(1, hub.subscribers("user:t"))
        assertNotNull(e.onTimeoutCb)
        e.onTimeoutCb!!.run()
        assertEquals(0, hub.subscribers("user:t"))
        assertEquals(1, e.completes, "completed, so Spring does not raise AsyncRequestTimeoutException")
    }

    @Test
    fun `an async timeout reaching the error handler is quiet (503 only when nothing was sent yet)`() {
        val h = ApiErrorHandler()
        val req = MockHttpServletRequest("GET", "/api/events")
        val fresh = MockHttpServletResponse()
        h.asyncTimeout(AsyncRequestTimeoutException(), req, fresh)
        assertEquals(503, fresh.status)
        val streaming = MockHttpServletResponse()
        streaming.setCommitted(true)
        h.asyncTimeout(AsyncRequestTimeoutException(), req, streaming)
        assertEquals(200, streaming.status)
    }

    @Test
    fun `client disconnects are recognised through the cause chain, real errors are not`() {
        assertTrue(ClientDisconnect.isClientDisconnect(AsyncRequestNotUsableException("Response not usable after response errors.")))
        assertTrue(ClientDisconnect.isClientDisconnect(IOException("Broken pipe")))
        assertTrue(ClientDisconnect.isClientDisconnect(ClientAbortException(IOException("Broken pipe"))))
        assertTrue(ClientDisconnect.isClientDisconnect(IllegalStateException("send failed", IOException("Connection reset by peer"))))
        assertFalse(ClientDisconnect.isClientDisconnect(IOException("No space left on device")))
        assertFalse(ClientDisconnect.isClientDisconnect(IllegalStateException("password=hunter2")))
        assertFalse(ClientDisconnect.isClientDisconnect(null))
    }

    @Test
    fun `Sentry beforeSend drops client disconnects and keeps real errors`() {
        val scrubber = SentryScrubber()
        val gone = SentryEvent(AsyncRequestNotUsableException("ServletOutputStream failed to write", IOException("Broken pipe")))
        assertNull(scrubber.execute(gone, Hint()))
        assertNull(scrubber.execute(SentryEvent(ClientAbortException(IOException("Broken pipe"))), Hint()))
        assertNull(scrubber.execute(SentryEvent(IOException("Broken pipe")), Hint()))

        // only the serialised exception list (no throwable): matched by type / message
        val listed = SentryEvent()
        listed.exceptions = listOf(SentryException().apply { type = "AsyncRequestNotUsableException"; module = "org.springframework.web.context.request.async" })
        assertNull(scrubber.execute(listed, Hint()))
        val pipe = SentryEvent()
        pipe.exceptions = listOf(SentryException().apply { type = "IOException"; value = "Broken pipe" })
        assertNull(scrubber.execute(pipe, Hint()))

        val real = SentryEvent(IllegalStateException("database is down"))
        assertNotNull(scrubber.execute(real, Hint()))
        val disk = SentryEvent()
        disk.exceptions = listOf(SentryException().apply { type = "IOException"; value = "No space left on device" })
        assertNotNull(scrubber.execute(disk, Hint()))
    }
}
