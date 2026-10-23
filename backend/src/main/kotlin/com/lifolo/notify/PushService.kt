package com.lifolo.notify

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.MessagingErrorCode
import com.lifolo.auth.FirebaseHolder
import com.lifolo.common.Lang
import com.lifolo.common.LifoloProperties
import com.lifolo.common.Sql
import com.lifolo.practice.Practice
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.stereotype.Service
import java.util.Random

/** One notification for one person (firebase.js sendPush item). Tokens default to the user's devices. */
data class PushItem(
    val uid: String?,
    val title: String,
    val body: String = "",
    val data: Map<String, Any?> = emptyMap(),
    val tokens: List<String>? = null,
    /** false = phone push only, no bell-list entry (ringing / call state changes) */
    val inbox: Boolean = true,
    /** FCM time to live (seconds); null = the FCM default */
    val ttlSeconds: Long? = null,
    /** FCM collapse key: a newer message with the same key replaces an undelivered older one */
    val collapseKey: String? = null,
)

/** Title/body/data of a notification, built per recipient language */
data class PushText(val title: String, val body: String = "", val data: Map<String, Any?> = emptyMap())

/** One FCM message to one device */
data class DeviceMessage(
    val token: String,
    val uid: String?,
    val data: Map<String, String>,
    val ttlSeconds: Long? = null,
    val collapseKey: String? = null,
)

/** Result per message: delivered, or the token is dead (uninstalled app) and should be removed */
enum class DeliveryResult { OK, DEAD_TOKEN, FAILED }

/** The wire to FCM — replaced by a recording fake in tests */
fun interface PushTransport {
    fun send(messages: List<DeviceMessage>): List<DeliveryResult>
}

@Configuration
class PushConfig {
    @Bean
    fun pushTransport(firebase: FirebaseHolder, props: LifoloProperties): PushTransport {
        when (props.push.mode.trim().lowercase()) {
            "log", "off", "disabled" -> return LoggingPushTransport()
            "fake" -> return FakePushTransport()
        }
        val messaging = firebase.messaging() ?: return LoggingPushTransport()
        return PushTransport { messages ->
            val batch = messaging.sendEach(
                messages.map {
                    Message.builder()
                        .setToken(it.token)
                        .putAllData(it.data)
                        .setAndroidConfig(
                            AndroidConfig.builder().setPriority(AndroidConfig.Priority.HIGH)
                                .apply {
                                    it.ttlSeconds?.let { s -> setTtl(s * 1000) }
                                    it.collapseKey?.let { k -> setCollapseKey(k) }
                                }.build(),
                        )
                        .build()
                },
            )
            batch.responses.map { r ->
                when {
                    r.isSuccessful -> DeliveryResult.OK
                    r.exception?.messagingErrorCode == MessagingErrorCode.UNREGISTERED ||
                        r.exception?.messagingErrorCode == MessagingErrorCode.INVALID_ARGUMENT -> DeliveryResult.DEAD_TOKEN
                    else -> DeliveryResult.FAILED
                }
            }
        }
    }
}

class LoggingPushTransport : PushTransport {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(messages: List<DeviceMessage>): List<DeliveryResult> {
        if (messages.isNotEmpty()) log.info("FCM not configured — {} push message(s) not sent", messages.size)
        return messages.map { DeliveryResult.FAILED }
    }
}

/** PUSH_MODE=fake (e2e only): nothing leaves the server, every message counts as delivered */
class FakePushTransport : PushTransport {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(messages: List<DeviceMessage>): List<DeliveryResult> {
        if (messages.isNotEmpty()) log.info("PUSH_MODE=fake — {} push message(s) counted as delivered, not sent", messages.size)
        return messages.map { DeliveryResult.OK }
    }
}

/**
 * Push notifications + the in-app bell list (firebase.js sendPush/addInbox/tokensOf):
 *  1. every item is written to `inbox_items` (even without a device token — the bell shows it later);
 *  2. data-only FCM messages (the app renders them and drops ones for another logged-in account via `forUid`);
 *  3. dead tokens are removed from `device_tokens`.
 * Returns how many people (or anonymous tokens) were reached.
 */
@Service
class PushService(
    private val jdbc: JdbcTemplate,
    private val transport: PushTransport,
    private val mapper: ObjectMapper,
    private val prefs: NotifyPrefsService,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val random = Random()

    /** a user's device tokens, newest 5 */
    fun tokensOf(uid: String): List<String> =
        jdbc.queryForList("SELECT token FROM device_tokens WHERE uid = ? ORDER BY updated_at DESC LIMIT 5", String::class.java, uid).reversed()

    fun addInbox(items: List<PushItem>) {
        val now = System.currentTimeMillis()
        val rev = (9999999999999L - now).toString().padStart(13, '0')
        items.filter { it.uid != null && it.title.isNotEmpty() }.forEachIndexed { k, item ->
            val id = "${item.uid}_${rev}_${k.toString(36)}${Integer.toString(random.nextInt(36 * 36 * 36 * 36), 36).padStart(4, '0')}"
            jdbc.update(
                "INSERT INTO inbox_items (id, uid, title, body, data, created_at) VALUES (?, ?, ?, ?, ?::jsonb, ?) ON CONFLICT (id) DO NOTHING",
                id, item.uid, item.title.take(200), item.body.take(300), mapper.writeValueAsString(item.data), Sql.ts(now),
            )
        }
    }

    /**
     * requestend.js pushUsers: one notification per user, text built in the user's language (profile `lang`).
     * Only users with a profile get it (Node: users/{uid} must exist). Never throws.
     */
    fun pushUsers(uids: Collection<String?>, make: (uid: String, lang: String?) -> PushText?): Int {
        val ids = uids.filterNotNull().filter { it.isNotEmpty() }.distinct()
        if (ids.isEmpty()) return 0
        return try {
            val langs = profileLangs(ids)
            send(ids.filter { it in langs }.mapNotNull { uid -> make(uid, langs[uid])?.let { PushItem(uid, it.title, it.body, it.data) } })
        } catch (e: Exception) {
            log.error("push to users failed", e)
            0
        }
    }

    /**
     * careaccess.js pushPatient: the patient and their active caregivers (caregiver title prefixed with the patient's
     * name, data {type:"care", asUid, rxId?} so tapping opens it as the patient). [body] is English/ASCII; Bangla
     * readers get its digits in Bangla (rules.js bnNumbers). Never throws.
     */
    fun pushPatient(uid: String?, en: String, bn: String, body: String = "", data: Map<String, Any?> = emptyMap()) {
        if (uid == null) return
        try {
            // a psychologist's appointment/notes/receipt (backend/docs/PRACTICE.md): generic text, and caregivers only
            // when the patient shares those with them
            val isPriv = isPrivate(data)
            val tagged = if (isPriv) data + (Practice.PRIVATE_KEY to true) else data
            // a deactivated patient's caregiver links are paused: no copies to the caregivers
            val carers = jdbc.queryForList(
                """
                SELECT caretaker_uid FROM care_links WHERE patient_uid = ? AND status = 'active'
                  AND NOT EXISTS (SELECT 1 FROM accounts a WHERE a.uid = care_links.patient_uid AND a.deactivated_at IS NOT NULL)
                  AND (share_psych OR NOT ?)
                LIMIT 10
                """.trimIndent(),
                String::class.java, uid, isPriv,
            )
            val langs = profileLangs(listOf(uid) + carers)
            val name = jdbc.queryForList("SELECT name FROM user_profiles WHERE uid = ?", String::class.java, uid).firstOrNull() ?: ""
            fun text(lang: String?) = Lang.pick(lang, body, Lang.bnNumbers(body))
            val list = ArrayList<PushItem>()
            if (uid in langs) list.add(PushItem(uid, Lang.pick(langs[uid], en, bn), text(langs[uid]), tagged))
            // the caregiver's copy belongs to the category of the patient's news (rx, appointments …), not "care"
            val cat = NotifyCats.of(data)
            for (c in carers) {
                if (c !in langs) continue
                val extra = if (data["rxId"] != null) mapOf("rxId" to data["rxId"]) else emptyMap()
                val priv = if (isPriv) mapOf(Practice.PRIVATE_KEY to true) else emptyMap()
                list.add(PushItem(c, "$name: ${Lang.pick(langs[c], en, bn)}", text(langs[c]), mapOf("type" to "care", "asUid" to uid) + extra + ("cat" to cat) + priv))
            }
            if (list.isNotEmpty()) send(list)
        } catch (e: Exception) {
            log.error("care push failed: {}", e.message)
        }
    }

    /**
     * Is this push about a psychologist's appointment, session notes or receipt (backend/docs/PRACTICE.md)? Tagged
     * ([Practice.PRIVATE_KEY]) by the sender, or found from the ids in the data (`type: appointment` + `id`, `rxId`,
     * `invoiceId`). Never throws (a failed lookup = not private).
     */
    fun isPrivate(data: Map<String, Any?>): Boolean {
        if (data[Practice.PRIVATE_KEY] == true) return true
        return try {
            val appt = if (data["type"] == "appointment") data["id"] as? String else null
            val rx = data["rxId"] as? String
            val inv = data["invoiceId"] as? String
            (appt != null && jdbc.queryForList("SELECT 1 FROM appointments WHERE id = ? AND type = 'psychologist'", appt).isNotEmpty()) ||
                (rx != null && jdbc.queryForList("SELECT 1 FROM prescriptions WHERE id = ? AND doctor_type = 'psychologist'", rx).isNotEmpty()) ||
                (
                    inv != null && jdbc.queryForList(
                        "SELECT 1 FROM invoices i JOIN providers p ON p.uid = i.biz_id WHERE i.id = ? AND i.kind = 'chamber' AND p.type = 'psychologist'",
                        inv,
                    ).isNotEmpty()
                    )
        } catch (e: Exception) {
            log.warn("private push check failed: {}", e.message)
            false
        }
    }

    /** private items (bell list and phone): the generic "অ্যাপয়েন্টমেন্ট আপডেট", no body. Call screens (no bell entry) stay. */
    private fun masked(items: List<PushItem>): List<PushItem> {
        val priv = items.map { it.inbox && it.title.isNotEmpty() && isPrivate(it.data) }
        if (priv.none { it }) return items
        val langs = try {
            profileLangs(items.filterIndexed { i, _ -> priv[i] }.mapNotNull { it.uid }.distinct())
        } catch (e: Exception) {
            emptyMap()
        }
        return items.mapIndexed { i, it ->
            if (!priv[i]) {
                it
            } else {
                it.copy(
                    title = Lang.pick(it.uid?.let { u -> langs[u] }, Practice.GENERIC_EN, Practice.GENERIC_BN),
                    body = "",
                    data = it.data + (Practice.PRIVATE_KEY to true),
                )
            }
        }
    }

    /** which of [uids] are deactivated (account_deactivate) */
    private fun deactivatedAmong(uids: List<String>): Set<String> {
        val ids = uids.distinct()
        if (ids.isEmpty()) return emptySet()
        val out = HashSet<String>()
        for (chunk in ids.chunked(1000)) {
            out += jdbc.queryForList(
                "SELECT uid FROM accounts WHERE deactivated_at IS NOT NULL AND uid IN (${chunk.joinToString(",") { "?" }})",
                String::class.java, *chunk.toTypedArray(),
            )
        }
        return out
    }

    /** uid → profile lang for users that have a profile */
    private fun profileLangs(uids: List<String>): Map<String, String?> {
        if (uids.isEmpty()) return emptyMap()
        val out = HashMap<String, String?>()
        val params = uids.joinToString(",") { "?" }
        jdbc.query("SELECT uid, lang FROM user_profiles WHERE uid IN ($params)", RowCallbackHandler { rs -> out[rs.getString(1)] = rs.getString(2) }, *uids.toTypedArray())
        return out
    }

    /**
     * The single choke point of every push: each item gets its category (`cat` in the data, NotifyCats), the bell list
     * gets every item, and the phone push goes only when the person's settings allow that category (`all` and the
     * category on; [NotifyCats.ACCOUNT] always). A deactivated account gets only [NotifyCats.ACCOUNT] pushes (the bell
     * list still records the rest). Items without a uid (anonymous tokens) always go.
     */
    fun send(items: List<PushItem>): Int {
        val withCat = masked(items).map { it.copy(data = LinkedHashMap(it.data).apply { put("cat", NotifyCats.of(it.data)) }) }
        try {
            addInbox(withCat.filter { it.inbox })
        } catch (e: Exception) {
            log.error("inbox write failed", e)
        }
        val allowed = try {
            val uids = withCat.mapNotNull { it.uid }
            val settings = prefs.of(uids)
            val off = deactivatedAmong(uids)
            withCat.filter { item ->
                val uid = item.uid
                val cat = item.data["cat"].toString()
                // a deactivated account (account_deactivate) gets only account news on the phone
                uid == null || ((settings[uid] ?: NotifyPrefs.DEFAULT).allows(cat) && (uid !in off || cat == NotifyCats.ACCOUNT))
            }
        } catch (e: Exception) {
            // settings unreadable: better a push too many than a lost emergency
            log.error("notify prefs read failed", e)
            withCat
        }
        val flat = allowed.flatMap { item ->
            val toks = item.tokens ?: item.uid?.let { uid -> tokensOf(uid) } ?: emptyList()
            toks.distinct().map { t -> item to t }
        }
        // the same message once per phone, even when two accounts share the phone
        val seen = HashSet<String>()
        val list = flat.filter { (item, token) -> seen.add("$token|${item.uid ?: ""}|${item.title}|${item.body}") }
        val reached = HashSet<String>()
        var anon = 0
        for (chunk in list.chunked(500)) {
            val messages = chunk.map { (item, token) ->
                val data = LinkedHashMap<String, String>()
                item.data.forEach { (k, v) -> if (v != null) data[k] = v.toString() }
                data["title"] = item.title.take(200)
                data["body"] = item.body.take(1000)
                val uid = item.uid
                if (uid != null) data["forUid"] = uid
                DeviceMessage(token, uid, data, item.ttlSeconds, item.collapseKey)
            }
            val results = try {
                transport.send(messages)
            } catch (e: Exception) {
                log.error("fcm send failed", e)
                messages.map { DeliveryResult.FAILED }
            }
            results.forEachIndexed { i, r ->
                val m = messages[i]
                val uid = m.uid
                if (r == DeliveryResult.OK) {
                    if (uid != null) reached.add(uid) else anon++
                } else if (r == DeliveryResult.DEAD_TOKEN && uid != null) {
                    runCatching { jdbc.update("DELETE FROM device_tokens WHERE token = ? AND uid = ?", m.token, uid) }
                }
            }
        }
        return reached.size + anon
    }
}
