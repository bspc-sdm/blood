package com.lifolo.payments

import com.lifolo.auth.AuthUser
import com.lifolo.common.LifoloProperties
import com.lifolo.common.Sql
import com.lifolo.common.toHex
import com.lifolo.demo.DemoWorld
import com.lifolo.notify.AccountMail
import com.lifolo.notify.NotifyCats
import com.lifolo.notify.PushService
import com.lifolo.notify.PushText
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The guards around a business's payment numbers (security review P4, P8): a recent login to change them, demo
 * businesses only with fake numbers (real ones never with them), a freeze ("আমি করিনি") that stops every number at
 * once, and the notice of every change by push AND email, each with the freeze action.
 */
@Component
class PayMethodGuard(
    private val jdbc: JdbcTemplate,
    private val store: PayMethodStore,
    private val push: PushService,
    private val mail: AccountMail,
    props: LifoloProperties,
    @Value("\${lifolo.pay.public-api:https://lifolo.com}") private val publicApi: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val secret: ByteArray = (props.webSessionSecret.ifEmpty { props.emailCodeSecret }.ifEmpty { "lifolo-pay-freeze" } + "|pay-freeze").toByteArray()

    companion object {
        /** a login at most this old may change payment numbers */
        const val REAUTH_MS = 10 * 60 * 1000L

        /** the "আমি করিনি" link in the email works this long */
        const val FREEZE_LINK_MS = 14 * Pay.DAY

        /** the demo world's numbers (no operator issues 012 — Phones.MOBILE) */
        val DEMO_NUMBER = Regex("^0120000[0-9]{4}$")
    }

    /** P4: changing where the money goes needs a sign-in within the last 10 minutes (401 `reauth_required`) */
    fun requireRecentLogin(lang: String?, user: AuthUser) {
        val at = user.authTimeMs
        if (at == null || System.currentTimeMillis() - at > REAUTH_MS) {
            throw Pay.err(lang, 401, "Please log in again to change payment numbers", "পেমেন্ট নম্বর বদলাতে আবার লগইন করুন", "reauth_required") // bn-ok
        }
    }

    /** P8: a demo business only with the fake demo numbers; a real one never with them */
    fun requireWorldNumber(lang: String?, ownerUid: String?, number: String) {
        val demo = DemoWorld.isDemoUid(ownerUid)
        if (demo != DEMO_NUMBER.matches(number)) {
            throw Pay.bad(
                lang,
                if (demo) "Demo accounts use demo numbers (0120000XXXX) only" else "Write a valid mobile number (01XXXXXXXXX)",
                if (demo) "ডেমো অ্যাকাউন্টে শুধু ডেমো নম্বর (0120000XXXX) চলে" else "সঠিক মোবাইল নম্বর লিখুন (01XXXXXXXXX)", // bn-ok
                if (demo) "demo_number" else "number",
            )
        }
    }

    fun requireNotFrozen(lang: String?, kind: String, bizId: String) {
        if (store.frozen(kind, bizId)) {
            throw Pay.err(lang, 409, "Payment numbers are frozen — the Lifolo team will contact you", "পেমেন্ট নম্বর বন্ধ রাখা হয়েছে — লাইফলো টিম যোগাযোগ করবে", "pay_frozen") // bn-ok
        }
    }

    /** "আমি করিনি": every number of the business stops at once (no new request gets one); the admins see it in the flags */
    fun freeze(kind: String, bizId: String, by: String?, via: String, now: Long): Boolean {
        val n = jdbc.update(
            "INSERT INTO pay_freezes (kind, biz_id, frozen_at, frozen_by, via) VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
            kind, bizId, Sql.ts(now), by, via,
        )
        if (n > 0) store.log(kind, bizId, "all", "frozen", null, null, by, now)
        return n > 0
    }

    fun unfreeze(kind: String, bizId: String, by: String?, now: Long): Boolean {
        val n = jdbc.update("DELETE FROM pay_freezes WHERE kind = ? AND biz_id = ?", kind, bizId)
        if (n > 0) store.log(kind, bizId, "all", "unfrozen", null, null, by, now)
        return n > 0
    }

    // ------------------------------------------------------------------ the email link

    private fun sign(payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal(payload.toByteArray()).toHex()
    }

    /** a signed "আমি করিনি" token for (kind, bizId), valid [FREEZE_LINK_MS] */
    fun freezeToken(kind: String, bizId: String, now: Long): String {
        val payload = "$kind|$bizId|${now + FREEZE_LINK_MS}"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray()) + "." + sign(payload)
    }

    /** (kind, bizId) of a valid, unexpired token; else null */
    fun parseToken(token: String?, now: Long): Pair<String, String>? {
        val t = token?.trim()?.takeIf { it.length in 10..400 } ?: return null
        val dot = t.lastIndexOf('.')
        if (dot <= 0) return null
        val payload = runCatching { String(Base64.getUrlDecoder().decode(t.substring(0, dot))) }.getOrNull() ?: return null
        if (!MessageDigest.isEqual(sign(payload).toByteArray(), t.substring(dot + 1).toByteArray())) return null
        val parts = payload.split('|')
        if (parts.size != 3 || parts[0] !in Pay.KINDS) return null
        val exp = parts[2].toLongOrNull() ?: return null
        if (exp < now) return null
        return parts[0] to parts[1]
    }

    fun freezeUrl(kind: String, bizId: String, now: Long): String = "${publicApi.trimEnd('/')}/api/pay/freeze?t=${freezeToken(kind, bizId, now)}"

    // ------------------------------------------------------------------ the notice

    /**
     * Every change of a business's numbers: a push (category account, always on) with the in-app freeze action, and an
     * email to the account's address with the "আমি করিনি" link. Last 3 digits only. Never throws.
     */
    fun notifyChanged(kind: String, bizId: String, ownerUid: String?, changes: List<MethodChange>, now: Long) {
        if (changes.isEmpty() || ownerUid == null) return
        val text = changes.joinToString(" · ") { c ->
            "${c.method}: ${c.oldNo?.let { "…" + it.takeLast(3) } ?: "—"} → ${c.newNo?.let { "…" + it.takeLast(3) } ?: "—"}"
        }
        try {
            push.pushUsers(listOf(ownerUid)) { _, lang ->
                PushText(
                    PayTexts.body("pay_methods_changed", lang), text,
                    mapOf("type" to "pay_methods_changed", "kind" to kind, "bizId" to bizId, "action" to "pay_methods_freeze", "cat" to NotifyCats.ACCOUNT),
                )
            }
        } catch (e: Exception) {
            log.warn("pay methods push failed: {}", e.message)
        }
        try {
            mail.payNumbersChanged(ownerUid, null, text, freezeUrl(kind, bizId, now))
        } catch (e: Exception) {
            log.warn("pay methods email failed: {}", e.message)
        }
    }
}
