package com.lifolo.notify

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.MessagingErrorCode
import com.lifolo.auth.FirebaseHolder
import com.lifolo.common.Sql
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.util.Random

/** One notification for one person (firebase.js sendPush item). Tokens default to the user's devices. */
data class PushItem(
    val uid: String?,
    val title: String,
    val body: String = "",
    val data: Map<String, Any?> = emptyMap(),
    val tokens: List<String>? = null,
)

/** One FCM message to one device */
data class DeviceMessage(val token: String, val uid: String?, val data: Map<String, String>)

/** Result per message: delivered, or the token is dead (uninstalled app) and should be removed */
enum class DeliveryResult { OK, DEAD_TOKEN, FAILED }

/** The wire to FCM — replaced by a recording fake in tests */
fun interface PushTransport {
    fun send(messages: List<DeviceMessage>): List<DeliveryResult>
}

@Configuration
class PushConfig {
    @Bean
    fun pushTransport(firebase: FirebaseHolder): PushTransport {
        val messaging = firebase.messaging() ?: return LoggingPushTransport()
        return PushTransport { messages ->
            val batch = messaging.sendEach(
                messages.map {
                    Message.builder()
                        .setToken(it.token)
                        .putAllData(it.data)
                        .setAndroidConfig(AndroidConfig.builder().setPriority(AndroidConfig.Priority.HIGH).build())
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

    fun send(items: List<PushItem>): Int {
        try {
            addInbox(items)
        } catch (e: Exception) {
            log.error("inbox write failed", e)
        }
        val flat = items.flatMap { item ->
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
                DeviceMessage(token, uid, data)
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
