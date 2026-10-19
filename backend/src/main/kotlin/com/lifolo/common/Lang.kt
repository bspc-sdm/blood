package com.lifolo.common

import jakarta.servlet.http.HttpServletRequest

/**
 * Request language. Node reads only `req.body.lang` ("en" / "bn"). We also accept an `X-Lang` header as a
 * fallback (no current client sends it, so behaviour is unchanged).
 *
 * Two helpers, two defaults — kept on purpose because the Node code has both:
 *  - [tr]   (community.js `tr`)  → Bangla only when lang == "bn", otherwise English (error messages of actions)
 *  - [pick] (rules.js `L`)       → English only when lang == "en", otherwise Bangla (push texts, auth messages)
 */
object Lang {
    const val ATTR = "lifolo.lang"
    const val HEADER = "X-Lang"

    fun resolve(body: Body?, request: HttpServletRequest?): String? =
        body?.lang ?: request?.getHeader(HEADER)?.takeIf { it == "en" || it == "bn" }

    fun of(request: HttpServletRequest?): String? = request?.getAttribute(ATTR) as? String

    fun tr(lang: String?, en: String, bn: String): String = if (lang == "bn") bn else en

    fun pick(lang: String?, en: String, bn: String): String = if (lang == "en") en else bn

    private val BN_DIGITS = charArrayOf('০', '১', '২', '৩', '৪', '৫', '৬', '৭', '৮', '৯')

    /** rules.js bnDigits */
    fun bnDigits(s: String): String = buildString(s.length) {
        for (c in s) append(if (c in '0'..'9') BN_DIGITS[c - '0'] else c)
    }
}
