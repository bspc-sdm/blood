package com.lifolo.common

import io.sentry.Sentry
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.async.AsyncRequestNotUsableException
import org.springframework.web.context.request.async.AsyncRequestTimeoutException
import org.springframework.web.servlet.resource.NoResourceFoundException

/**
 * The Node error envelope (firebase.js `handler`):
 *   status != 500 → `{ error: message (English via I18n when lang == "en"), code? }` — code only when it is `[a-z_]+`
 *   status == 500 → `{ error: "Server error, please try again" | "সার্ভারে সমস্যা হয়েছে, আবার চেষ্টা করুন" }`
 */
@RestControllerAdvice
class ApiErrorHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiException::class)
    fun api(e: ApiException, req: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
        if (e.status >= 500) log.error("api error {} {}", e.status, e.message)
        val res = envelope(e.status, e.message, e.code, Lang.of(req))
        if (e.extra.isEmpty() || e.status >= 500) return res
        val body = LinkedHashMap<String, Any?>(res.body ?: emptyMap())
        for ((k, v) in e.extra) if (k != "error" && k != "code") body[k] = v
        return ResponseEntity.status(e.status).body<Map<String, Any?>>(body)
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun method(): ResponseEntity<Map<String, Any?>> =
        ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(mapOf("error" to "Method not allowed"))

    @ExceptionHandler(NoResourceFoundException::class)
    fun notFound(): ResponseEntity<Map<String, Any?>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "Not found"))

    @ExceptionHandler(HttpMessageNotReadableException::class, HttpMediaTypeNotSupportedException::class)
    fun badBody(req: HttpServletRequest): ResponseEntity<Map<String, Any?>> =
        envelope(400, Lang.tr(Lang.of(req), "Invalid request", "অনুরোধ ঠিক নেই"), null, Lang.of(req)) // bn-ok

    /**
     * The client went away while we were writing (SSE stream, slow answer): Spring dispatches the async error here.
     * The response cannot be written any more — nothing to send, nothing to report (debug log only, no Sentry).
     */
    @ExceptionHandler(AsyncRequestNotUsableException::class)
    fun clientGone(e: AsyncRequestNotUsableException, req: HttpServletRequest) {
        log.debug("client disconnected on {}: {}", req.requestURI, e.message)
    }

    /**
     * A long-lived async response (an SSE stream after its 30 minutes) reached its timeout: normal, the client
     * reconnects. Not an error and not Sentry; the stream's response is already committed, so nothing is written.
     */
    @ExceptionHandler(AsyncRequestTimeoutException::class)
    fun asyncTimeout(e: AsyncRequestTimeoutException, req: HttpServletRequest, res: HttpServletResponse) {
        log.debug("async request timed out on {}", req.requestURI)
        if (!res.isCommitted) res.status = HttpStatus.SERVICE_UNAVAILABLE.value()
    }

    @ExceptionHandler(Exception::class)
    fun other(e: Exception, req: HttpServletRequest): ResponseEntity<Map<String, Any?>>? {
        if (ClientDisconnect.isClientDisconnect(e)) {
            // Tomcat's ClientAbortException / "Broken pipe" — the client is gone, the response is unusable
            log.debug("client disconnected on {}: {}", req.requestURI, e.toString())
            return null
        }
        log.error("unhandled error on {}", req.requestURI, e)
        Sentry.captureException(e)
        return envelope(500, "", null, Lang.of(req))
    }

    companion object {
        private val CODE_RE = Regex("^[a-z_]+$")

        fun body(status: Int, message: String, code: String?, lang: String?): Map<String, Any?> {
            val en = lang == "en"
            val out = linkedMapOf<String, Any?>(
                "error" to when {
                    status == 500 -> if (en) "Server error, please try again" else "সার্ভারে সমস্যা হয়েছে, আবার চেষ্টা করুন"
                    en -> I18n.toEnglish(message)
                    else -> message
                },
            )
            if (code != null && status != 500 && CODE_RE.matches(code)) out["code"] = code
            return out
        }

        fun envelope(status: Int, message: String, code: String?, lang: String?): ResponseEntity<Map<String, Any?>> =
            ResponseEntity.status(status).body(body(status, message, code, lang))
    }
}
