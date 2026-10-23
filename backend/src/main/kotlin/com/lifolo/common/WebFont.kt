package com.lifolo.common

/**
 * Lifolo's font (Anek Bangla, SIL OFL) for the HTML the server writes itself: share pages (/r, /rx, /shop),
 * the bKash pages and emails. The files are the web build's stable copies (web-vue/public/fonts → lifolo.com/w/fonts/);
 * on another host they 404 and the fallbacks take over. Emails get the stack only (mail apps drop @font-face).
 */
object WebFont {
    /** Font stack: Anek Bangla first, then fonts phones and PCs already have. */
    const val STACK = "'Anek Bangla','Noto Sans Bengali','Nirmala UI',Vrinda,system-ui,-apple-system,'Segoe UI',Roboto,Arial,sans-serif"

    private const val BENGALI = "U+0951-0952,U+0964-0965,U+0980-09FE,U+1CD0-1CF9,U+200C-200D,U+20B9,U+25CC,U+A8F1"
    private const val LATIN = "U+0000-00FF,U+0131,U+0152-0153,U+02BB-02BC,U+02C6,U+02DA,U+02DC,U+0304,U+0308,U+0329," +
        "U+2000-206F,U+20AC,U+2122,U+2191,U+2193,U+2212,U+2215,U+FEFF,U+FFFD"

    private fun face(subset: String, weight: Int, range: String) =
        "@font-face{font-family:'Anek Bangla';font-style:normal;font-weight:$weight;font-display:swap;" +
            "src:url(/w/fonts/anek-bangla-$subset-$weight-normal.woff2) format('woff2');unicode-range:$range}"

    /** @font-face rules (400 and 700, Bengali and Latin) for a page's <style>. */
    val FACES: String = listOf(400, 700).joinToString("") { w -> face("bengali", w, BENGALI) + face("latin", w, LATIN) }

    /** An email body in the font stack (inline style — mail apps keep it). */
    fun mail(html: String): String = "<div style=\"font-family:$STACK;line-height:1.6\">$html</div>"
}
