package com.lifolo.common

import io.sentry.Breadcrumb
import io.sentry.SentryEvent
import io.sentry.protocol.Message
import io.sentry.protocol.Request
import io.sentry.protocol.SentryException
import io.sentry.protocol.User
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SentryScrubTest {
    @Test
    fun `texts lose emails, phones and tokens`() {
        assertEquals("user [email] failed", SentryScrub.text("user a.b@x.com failed"))
        assertEquals("call [phone] or [phone]", SentryScrub.text("call 01712345678 or +8801712345678"))
        assertEquals("Bearer [token]", SentryScrub.text("Bearer abcdef123"))
        assertEquals("t=[token]", SentryScrub.text("t=eyJhbGciOi.eyJzdWIiOiIx.c2lnbmF0dXJl"))
        assertEquals("?token=[filtered]&x=1", SentryScrub.text("?token=s3cr3t&x=1"))
        assertEquals("order 12345 at 10:30", SentryScrub.text("order 12345 at 10:30"))
    }

    @Test
    fun `events lose user, cookies, body and private headers`() {
        val e = SentryEvent()
        e.user = User().apply { email = "a@b.co" }
        e.message = Message().apply { formatted = "x a@b.co" }
        e.request = Request().apply {
            url = "https://lifolo.com/p"
            cookies = "s=1"
            data = mapOf("phone" to "01712345678")
            headers = mapOf("Authorization" to "Bearer x", "User-Agent" to "UA")
        }
        e.exceptions = listOf(SentryException().apply { value = "bad 01812345678" })
        e.breadcrumbs = listOf(Breadcrumb("sent to a@b.co"))
        SentryScrub.event(e)
        assertNull(e.user)
        assertNull(e.request!!.cookies)
        assertNull(e.request!!.data)
        assertEquals(mapOf("User-Agent" to "UA"), e.request!!.headers)
        assertEquals("x [email]", e.message!!.formatted)
        assertEquals("bad [phone]", e.exceptions!![0].value)
        assertEquals("sent to [email]", e.breadcrumbs!![0].message)
    }
}
