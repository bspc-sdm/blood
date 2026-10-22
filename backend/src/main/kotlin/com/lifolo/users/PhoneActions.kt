package com.lifolo.users

import com.lifolo.common.DhakaTime
import com.lifolo.common.Js
import com.lifolo.common.LifoloProperties
import com.lifolo.common.Phones
import com.lifolo.common.QuotaService
import com.lifolo.common.Sql
import com.lifolo.common.action.ActionContext
import com.lifolo.common.action.ActionHandler
import com.lifolo.common.action.ActionModule
import com.lifolo.common.localDate
import com.lifolo.common.toHex
import com.lifolo.jobs.DailyTask
import com.lifolo.notify.AccountMail
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.time.Duration
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The SMS gateway (phone.js send): GET SMS_URL_TEMPLATE with {to} and {msg}, 10 s timeout, non-2xx = failure */
fun interface SmsSender {
    fun send(to: String, text: String)
}

@Component
class HttpSmsSender(private val props: LifoloProperties) : SmsSender {
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    override fun send(to: String, text: String) {
        val url = props.smsUrlTemplate
            .replaceFirst("{to}", URLEncoder.encode(to, Charsets.UTF_8).replace("+", "%20"))
            .replaceFirst("{msg}", URLEncoder.encode(text, Charsets.UTF_8).replace("+", "%20"))
        val res = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.discarding())
        if (res.statusCode() !in 200..299) throw IllegalStateException("SMS gateway ${res.statusCode()}")
    }
}

/**
 * phone.js — verifying the profile's mobile number with a 6-digit SMS code. Off (every number counts as verified,
 * see appointments.PhoneCheck) while SMS_URL_TEMPLATE is empty. Codes: HMAC only, 10 minutes, 5 tries. Per number:
 * 3 codes a day, 1 minute apart (`rate_counters`, scope `phone`, keyed by an HMAC of the number); per user: quota
 * `phoneCode` (5/day).
 */
@Service
class PhoneService(
    private val jdbc: JdbcTemplate,
    private val props: LifoloProperties,
    private val quotas: QuotaService,
    private val sms: SmsSender,
    private val tx: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val random = SecureRandom()

    private fun enabled() = props.smsUrlTemplate.isNotEmpty()

    /** `process.env.CRON_SECRET || process.env.FIREBASE_SERVICE_ACCOUNT || 'phone'` */
    private fun secret(): String = props.cronSecret.ifEmpty { props.firebaseServiceAccount.ifEmpty { "phone" } }

    private fun hmac(key: String, text: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(text.toByteArray(Charsets.UTF_8)).toHex()
    }

    /** phone.js hash(uid, phone, code) */
    fun codeHash(uid: String, phone: String, code: String): String = hmac("phone-v1:${secret()}", "$uid:$phone:$code")

    private fun limitKey(phone: String): String = "h_" + hmac(secret(), "lim:$phone").take(40)

    fun sendCode(ctx: ActionContext): Any {
        if (!enabled()) throw ctx.err(400, "Phone verification is not available yet", "ফোন যাচাই এখনো চালু হয়নি")
        val uid = ctx.user.id
        val phone = Phones.normalizeBd(jdbc.queryForList("SELECT phone FROM user_profiles WHERE uid = ?", String::class.java, uid).firstOrNull())
            ?: throw ctx.err(400, "Add a valid Bangladeshi mobile number in your profile", "প্রোফাইলে সঠিক বাংলাদেশি মোবাইল নম্বর দিন")
        quotas.use(ctx.lang, uid, "phoneCode")
        // the same number: at most 3 a day, 1 minute apart — so many accounts cannot flood someone with SMS
        val key = limitKey(phone)
        tx.executeWithoutResult {
            val now = System.currentTimeMillis()
            val day = DhakaTime.day(now)
            jdbc.update("INSERT INTO rate_counters (key, scope, day, count) VALUES (?, 'phone', ?, 0) ON CONFLICT (key) DO NOTHING", key, Sql.date(day))
            val row = jdbc.query(
                "SELECT day, count, last_at FROM rate_counters WHERE key = ? FOR UPDATE",
                { rs, _ -> Triple(rs.localDate("day"), rs.getInt("count"), rs.getTimestamp("last_at")?.time) },
                key,
            ).first()
            val n = if (row.first == day) row.second else 0
            if (n >= 3 || (row.third != null && now - row.third!! < 60_000)) {
                throw ctx.err(429, "Too many codes for this number. Try again later.", "এই নম্বরে অনেকবার কোড গেছে। পরে চেষ্টা করুন।")
            }
            jdbc.update("UPDATE rate_counters SET day = ?, count = ?, last_at = ? WHERE key = ?", Sql.date(day), n + 1, Sql.ts(now), key)
        }
        val code = random.nextInt(1_000_000).toString().padStart(6, '0')
        jdbc.update(
            """
            INSERT INTO user_state (uid, phone_code_hash, phone_code_phone, phone_code_expires_at, phone_code_tries) VALUES (?, ?, ?, ?, 0)
            ON CONFLICT (uid) DO UPDATE SET phone_code_hash = EXCLUDED.phone_code_hash, phone_code_phone = EXCLUDED.phone_code_phone,
              phone_code_expires_at = EXCLUDED.phone_code_expires_at, phone_code_tries = 0
            """.trimIndent(),
            uid, codeHash(uid, phone, code), phone, Sql.ts(System.currentTimeMillis() + CODE_MS),
        )
        try {
            sms.send(phone, "Lifolo: your code is $code. It expires in 10 minutes.")
        } catch (e: Exception) {
            log.error("sms failed", e)
            throw ctx.err(502, "Couldn't send the SMS. Try again later.", "SMS পাঠানো যায়নি। পরে আবার চেষ্টা করুন।")
        }
        return linkedMapOf("ok" to true, "to" to "0${phone.substring(3, 6)}*****${phone.takeLast(3)}")
    }

    fun verifyCode(ctx: ActionContext): Any {
        val uid = ctx.user.id
        val code = (if (Js.truthy(ctx.body["code"])) Js.toStr(ctx.body["code"]) else "").filter { it in '0'..'9' }
        val result = tx.execute {
            val now = System.currentTimeMillis()
            val st = jdbc.query(
                "SELECT phone_code_hash, phone_code_phone, phone_code_expires_at, phone_code_tries FROM user_state WHERE uid = ? FOR UPDATE",
                { rs, _ -> CodeState(rs.getString(1), rs.getString(2), rs.getTimestamp(3)?.time, rs.getInt(4)) },
                uid,
            ).firstOrNull()
            if (st?.hash == null || st.phone == null || st.expiresAt == null || now > st.expiresAt || st.tries >= 5) return@execute "expired"
            if (code.length != 6 || codeHash(uid, st.phone, code) != st.hash) {
                jdbc.update("UPDATE user_state SET phone_code_tries = phone_code_tries + 1 WHERE uid = ?", uid)
                return@execute "wrong"
            }
            jdbc.update(
                """
                UPDATE user_state SET verified_phone = ?, phone_verified_at = ?, phone_code_hash = NULL, phone_code_phone = NULL,
                  phone_code_expires_at = NULL, phone_code_tries = 0
                WHERE uid = ?
                """.trimIndent(),
                st.phone, Sql.ts(now), uid,
            )
            "ok"
        }
        if (result == "expired") throw ctx.err(400, "The code expired. Send a new one.", "কোডের মেয়াদ শেষ। নতুন কোড নিন।")
        if (result == "wrong") throw ctx.err(400, "Wrong code", "কোড মেলেনি")
        return linkedMapOf("ok" to true)
    }

    private data class CodeState(val hash: String?, val phone: String?, val expiresAt: Long?, val tries: Int)

    /** daily step 6: limit rows of earlier days (≤ 400 per run) */
    fun cleanupLimits(nowMs: Long): Int = jdbc.update(
        "DELETE FROM rate_counters WHERE key IN (SELECT key FROM rate_counters WHERE scope = 'phone' AND day < ? LIMIT 400)",
        Sql.date(DhakaTime.day(nowMs)),
    )

    companion object {
        const val CODE_MS = 10L * 60 * 1000
    }
}

/** `delete_account`, `phone_send_code`, `phone_verify_code` */
@Component
class UsersActions(
    private val deletion: AccountDeletionService,
    private val phone: PhoneService,
    private val deactivation: AccountDeactivationService,
    private val accounts: AccountService,
    private val mail: AccountMail,
) : ActionModule {
    override fun actions(): Map<String, ActionHandler> = mapOf(
        "delete_account" to ActionHandler(::deleteAccount),
        "phone_send_code" to ActionHandler { ctx -> phone.sendCode(ctx) },
        "phone_verify_code" to ActionHandler { ctx -> phone.verifyCode(ctx) },
    )

    /**
     * {ok, deleteAt}. The apps now send `{ack: true}` (the person ticked "I understand"); older apps send nothing — both
     * work, the flag is not required. A deactivated account is reactivated first (deletion supersedes). The first
     * request emails the deletion date; a repeated request keeps the first date and sends nothing.
     */
    fun deleteAccount(ctx: ActionContext): Any {
        val uid = ctx.user.id
        val alreadyPending = accounts.deleteAt(uid) != null
        deactivation.clearForDeletion(uid)
        val r = deletion.schedule(uid)
        if (!alreadyPending) (r["deleteAt"] as? Long)?.let { mail.deletionRequested(uid, ctx.user.email, it) }
        return r
    }
}

/** daily step 6 (`phoneLimitsDeleted`) */
@Component
class PhoneLimitsTask(private val phone: PhoneService) : DailyTask {
    override val name = "phoneLimitsDeleted"
    override val order = 6

    override fun run(nowMs: Long): Any = phone.cleanupLimits(nowMs)
}
