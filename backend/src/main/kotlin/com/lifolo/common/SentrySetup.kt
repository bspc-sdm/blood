package com.lifolo.common

import io.sentry.Breadcrumb
import io.sentry.Hint
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.SentryOptions
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Sentry (project lifolo-server): errors only, nothing personal. Every event and breadcrumb goes through [SentryScrub]
 * before it leaves the server: user, cookies, request body and most headers are dropped; emails, phone numbers,
 * bearer/JWT tokens and `token=…`-style values in messages, exception texts and breadcrumbs are replaced.
 */
object SentryScrub {
    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    // Bangladeshi mobiles (01XXXXXXXXX, +8801…) and other +international numbers
    private val PHONE = Regex("(?<![0-9])(?:\\+?88)?01[3-9][0-9]{8}(?![0-9])|\\+[0-9][0-9 -]{9,16}[0-9]")
    private val JWT = Regex("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}")
    private val BEARER = Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+")
    private val KEYVAL = Regex("(?i)((?:token|secret|password|passwd|code|key|session|cookie|authorization|phone|email)[\"']?\\s*[:=]\\s*[\"']?)[^\\s\"'&,;}]+")
    private val KEEP_HEADERS = setOf("user-agent", "content-type", "accept-language")

    fun text(s: String?): String? {
        if (s.isNullOrEmpty()) return s
        var t: String = s
        t = JWT.replace(t, "[token]")
        t = BEARER.replace(t, "$1[token]")
        t = KEYVAL.replace(t, "$1[filtered]")
        t = EMAIL.replace(t, "[email]")
        t = PHONE.replace(t, "[phone]")
        return t
    }

    fun event(e: SentryEvent): SentryEvent {
        e.user = null
        e.serverName = null
        e.request?.let { r ->
            r.cookies = null
            r.data = null
            r.headers = r.headers?.filterKeys { it.lowercase() in KEEP_HEADERS }
            r.queryString = text(r.queryString)
            r.url = text(r.url)
        }
        e.message?.let { m ->
            m.formatted = text(m.formatted)
            m.message = text(m.message)
            m.params = m.params?.map { text(it) ?: "" }
        }
        e.exceptions?.forEach { it.value = text(it.value) }
        e.breadcrumbs?.forEach { crumb(it) }
        e.extras?.keys?.toList()?.forEach { k -> (e.getExtra(k) as? String)?.let { e.setExtra(k, text(it) ?: "") } }
        return e
    }

    fun crumb(b: Breadcrumb): Breadcrumb {
        b.message = text(b.message)
        b.data.keys.toList().forEach { k -> (b.data[k] as? String)?.let { b.setData(k, text(it) ?: "") } }
        return b
    }
}

/** picked up by the Sentry Spring Boot starter */
@Component
class SentryScrubber : SentryOptions.BeforeSendCallback {
    override fun execute(event: SentryEvent, hint: Hint): SentryEvent = SentryScrub.event(event)
}

@Component
class SentryCrumbScrubber : SentryOptions.BeforeBreadcrumbCallback {
    override fun execute(breadcrumb: Breadcrumb, hint: Hint): Breadcrumb = SentryScrub.crumb(breadcrumb)
}

/** one log line at start-up ("sentry initialised: enabled=…") — the deploy check greps for it */
@Component
class SentryStartupLog {
    private val log = LoggerFactory.getLogger(SentryStartupLog::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun ready() {
        val o = Sentry.getCurrentScopes().options
        log.info("sentry initialised: enabled={} environment={} release={}", Sentry.isEnabled(), o.environment, o.release)
    }
}

/**
 * POST /api/cron/sentry-test with `Authorization: Bearer $CRON_SECRET` — sends one test event (the "Sentry test"
 * workflow calls it on the VPS through 127.0.0.1). Answers whether Sentry is on and the event id.
 */
@RestController
class SentryTestController(private val props: LifoloProperties) {
    @RequestMapping("/api/cron/sentry-test")
    fun test(req: HttpServletRequest): ResponseEntity<Any> {
        if (req.method != "POST") return ResponseEntity.status(405).body(mapOf("error" to "Method not allowed"))
        val secret = props.cronSecret
        if (secret.isBlank() || req.getHeader("Authorization") != "Bearer $secret") throw ApiException(401, "অনুমতি নেই")
        if (!Sentry.isEnabled()) return ResponseEntity.ok(mapOf("enabled" to false))
        val id = Sentry.captureMessage("lifolo-server: test event (deploy check)", SentryLevel.INFO)
        Sentry.flush(10_000)
        return ResponseEntity.ok(mapOf("enabled" to true, "eventId" to id.toString()))
    }
}
