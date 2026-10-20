package com.lifolo.appointments

import com.lifolo.contracts.Badges
import com.lifolo.contracts.Chamber
import com.lifolo.contracts.Chambers
import com.lifolo.contracts.ProviderAssistants
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/** community-db.js approvedBadge */
@Component
class BadgeLookup(private val jdbc: JdbcTemplate) : Badges {
    override fun approvedBadge(uid: String): String? =
        jdbc.queryForList("SELECT type FROM badges WHERE uid = ? AND status = 'approved'", String::class.java, uid).firstOrNull()

    /** liveBadges: approved badges of these users → (type, name) */
    fun live(uids: Collection<String?>): Map<String, Pair<String, String>> {
        val ids = uids.filterNotNull().filter { com.lifolo.common.Rules.isValidId(it) }.distinct()
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<String, Pair<String, String>>()
        for (chunk in ids.chunked(1000)) {
            jdbc.query(
                "SELECT uid, type, name FROM badges WHERE status = 'approved' AND uid IN (${chunk.joinToString(",") { "?" }})",
                org.springframework.jdbc.core.RowCallbackHandler { rs -> out[rs.getString(1)] = rs.getString(2) to (rs.getString(3) ?: "") },
                *chunk.toTypedArray(),
            )
        }
        return out
    }
}

/** providers.assistantEmails (`provider_assistants`) */
@Component
class ProviderAssistantsStore(private val jdbc: JdbcTemplate) : ProviderAssistants {
    override fun emails(doctorUid: String): List<String> =
        jdbc.queryForList("SELECT email FROM provider_assistants WHERE provider_uid = ? ORDER BY position, email", String::class.java, doctorUid)

    override fun isAssistant(doctorUid: String, email: String): Boolean =
        email.isNotEmpty() &&
            jdbc.queryForList("SELECT 1 FROM provider_assistants WHERE provider_uid = ? AND email = ?", doctorUid, email).isNotEmpty()

    override fun doctorsFor(email: String, limit: Int): List<String> =
        if (email.isEmpty()) {
            emptyList()
        } else {
            jdbc.queryForList(
                "SELECT provider_uid FROM provider_assistants WHERE email = ? ORDER BY provider_uid LIMIT ?", String::class.java, email, limit,
            )
        }

    @Transactional
    override fun set(doctorUid: String, emails: List<String>): Boolean {
        if (jdbc.queryForList("SELECT 1 FROM providers WHERE uid = ? FOR UPDATE", doctorUid).isEmpty()) return false
        jdbc.update("DELETE FROM provider_assistants WHERE provider_uid = ?", doctorUid)
        emails.map { it.lowercase() }.distinct().forEachIndexed { i, e ->
            jdbc.update("INSERT INTO provider_assistants (provider_uid, email, position) VALUES (?, ?, ?)", doctorUid, e, i)
        }
        return true
    }
}

/** care.js chamber helpers over the stored provider document */
@Component
class ChambersStore(private val store: ProviderStore) : Chambers {
    override fun chambersOf(providerUid: String): List<Chamber> =
        store.load(providerUid)?.chambers?.mapIndexed { i, c -> CareRules.toContract(i, c) } ?: emptyList()

    override fun takesAppointments(providerUid: String): Boolean = store.load(providerUid)?.appointments ?: false
}
