package com.lifolo.admin.kernel

import com.lifolo.common.ApiException
import com.lifolo.common.Lang
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

/**
 * moderation.js helpers used by the packages that own reportable things (Q&A, tips, providers, listings).
 * Target paths keep the Node form: `questions/{id}`, `questions/{id}/answers/{answerId}`, `tips/{id}`,
 * `providers/{uid}`, `ambulances/{id}`, `pharmacies/{uid}`, `bloodBanks/{id}` ([targetPath] builds them).
 * Report statuses: `open`, `dismissed` (admin kept the thing), `removed` (report upheld).
 * The `report`, `admin_reports`, `admin_report_resolve` actions are P6 (later PR).
 */
interface Moderation {
    /** reportCount: reports the owner sees in their stats — open + removed (dismissed ones do not count) */
    fun reportCount(targetPath: String): Int

    /**
     * assertNoOpenReports: an owner may not delete something while a report on it is open (deleting and re-adding
     * would give a clean record) → 409 "This has an open report. It can be removed after the Lifolo team reviews it."
     */
    fun assertNoOpenReports(lang: String?, targetPath: String)

    /**
     * reopenOnEdit: after the author edits a question/answer/tip, reports the admin had dismissed open again
     * (`reopen_reason = 'edited'`), so "fix after report, then make it bad again" is caught. Returns how many
     * reopened; never throws (Node logs and returns 0).
     */
    fun reopenOnEdit(targetPath: String): Int

    companion object {
        /** moderation.js targetPath(kind, id, answerId) */
        fun targetPath(kind: String, id: String, answerId: String? = null): String = when (kind) {
            "question" -> "questions/$id"
            "answer" -> "questions/$id/answers/$answerId"
            "homecare", "provider" -> "providers/$id"
            "ambulance" -> "ambulances/$id"
            "pharmacy" -> "pharmacies/$id"
            "bloodbank" -> "bloodBanks/$id"
            else -> "tips/$id"
        }
    }
}

@Service
class ModerationService(private val jdbc: JdbcTemplate) : Moderation {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun reportCount(targetPath: String): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM mod_reports WHERE target_path = ? AND status IN ('open', 'removed')", Int::class.java, targetPath,
        ) ?: 0

    override fun assertNoOpenReports(lang: String?, targetPath: String) {
        val open = jdbc.queryForList("SELECT 1 FROM mod_reports WHERE target_path = ? AND status = 'open' LIMIT 1", targetPath).isNotEmpty()
        if (open) {
            throw ApiException(
                409,
                Lang.tr(
                    lang,
                    "This has an open report. It can be removed after the Lifolo team reviews it.",
                    "এটার একটা রিপোর্ট খোলা আছে। Lifolo টিম দেখার পর মুছতে পারবেন।",
                ),
            )
        }
    }

    override fun reopenOnEdit(targetPath: String): Int = try {
        jdbc.update(
            """
            UPDATE mod_reports SET status = 'open', reopened_at = now(), reopen_reason = 'edited'
            WHERE id IN (SELECT id FROM mod_reports WHERE target_path = ? AND status = 'dismissed' LIMIT 100)
            """.trimIndent(),
            targetPath,
        )
    } catch (e: Exception) {
        log.error("report reopen failed: {}", e.message)
        0
    }
}
