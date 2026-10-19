package com.lifolo.common

import io.sentry.Sentry
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
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
        return envelope(e.status, e.message, e.code, Lang.of(req))
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun method(): ResponseEntity<Map<String, Any?>> =
        ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(mapOf("error" to "Method not allowed"))

    @ExceptionHandler(NoResourceFoundException::class)
    fun notFound(): ResponseEntity<Map<String, Any?>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "Not found"))

    @ExceptionHandler(HttpMessageNotReadableException::class, HttpMediaTypeNotSupportedException::class)
    fun badBody(req: HttpServletRequest): ResponseEntity<Map<String, Any?>> =
        envelope(400, Lang.tr(Lang.of(req), "Invalid request", "অনুরোধ ঠিক নেই"), null, Lang.of(req))

    @ExceptionHandler(Exception::class)
    fun other(e: Exception, req: HttpServletRequest): ResponseEntity<Map<String, Any?>> {
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
