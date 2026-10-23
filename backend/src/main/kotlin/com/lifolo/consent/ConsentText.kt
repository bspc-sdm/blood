package com.lifolo.consent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component

/**
 * The consent wording (backend/docs/CONSENT.md): ONE versioned file, `resources/consent/health-consent.json`
 * (`version` + `bn` + `en`). The apps never carry their own copy — `consent_status` sends the text in the caller's
 * language — so the lawyer's edits need no app release. A new `version` makes every earlier consent "outdated".
 */
@Component
class ConsentText(mapper: ObjectMapper) {
    private val doc: JsonNode = ClassPathResource(FILE).inputStream.use { mapper.readTree(it) }

    /** the version in the file */
    val fileVersion: String = doc.path("version").textValue()
        ?.takeIf { it.isNotBlank() && it.length <= 40 }
        ?: error("$FILE: missing or bad version")

    /** tests only: pretend the lawyer published a new text (null = the file's version) */
    @Volatile
    var versionOverride: String? = null

    /** the text version people must have agreed to */
    val version: String get() = versionOverride ?: fileVersion

    init {
        for (l in listOf("bn", "en")) {
            val t = doc.path(l)
            for (k in REQUIRED) check(t.path(k).isTextual) { "$FILE: $l.$k missing" }
            for (s in Consents.SCOPES) check(t.path("scopes").path(s).isTextual) { "$FILE: $l.scopes.$s missing" }
            check(t.path("sections").isArray && t.path("sections").size() > 0) { "$FILE: $l.sections missing" }
        }
    }

    /** the whole text in [lang] ("en" → English, anything else Bangla) */
    fun text(lang: String?): JsonNode = doc.path(if (lang == "en") "en" else "bn")

    /** one string of the text in [lang] */
    fun line(lang: String?, key: String): String = text(lang).path(key).asText("")

    companion object {
        const val FILE = "consent/health-consent.json"
        private val REQUIRED = listOf(
            "title", "intro", "agreeNote", "agree", "later", "policyUrl", "required", "caregiver",
            "withdrawTitle", "withdrawBody", "withdrawButton",
        )
    }
}
