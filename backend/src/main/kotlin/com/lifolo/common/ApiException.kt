package com.lifolo.common

/**
 * Node `HttpError(status, message, code)`. The message is already in the request language (built with [Lang.tr])
 * or in Bangla (legacy endpoints) — [ApiErrorHandler] translates Bangla to English with [I18n.toEnglish]
 * when the body has `lang: "en"`, exactly like firebase.js `handler`.
 */
class ApiException(
    val status: Int,
    override val message: String,
    val code: String? = null,
) : RuntimeException(message)
