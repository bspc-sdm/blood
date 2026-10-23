package com.lifolo.notify

import com.lifolo.common.DhakaTime
import com.lifolo.common.LifoloProperties
import com.lifolo.demo.DemoWorld
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * `account`-category emails (Oct 2026): deletion requested, deletion cancelled by a login, account deactivated,
 * account reactivated. Bangla first, one short English line at the end, and the site link.
 *
 * They never block or fail the action: sent on a small background pool after the change is saved; a send failure (or
 * a full queue) is only logged. Demo accounts (`demo-…`, public test logins) and accounts without an email get none.
 */
@Service
class AccountMail(
    private val mail: MailSender,
    private val jdbc: JdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val pool = ThreadPoolExecutor(
        1, 2, 30, TimeUnit.SECONDS, ArrayBlockingQueue(500),
        { r -> Thread(r, "account-mail").apply { isDaemon = true } },
        { _, _ -> log.error("account mail queue full, email dropped") },
    )

    @PreDestroy
    fun stop() {
        pool.shutdown()
        runCatching { pool.awaitTermination(5, TimeUnit.SECONDS) }
    }

    /** false = send on the calling thread (tests, so the mail is there when the action returns); failures still logged */
    @Volatile
    var async: Boolean = true

    data class Letter(val subject: String, val lines: List<String>)

    /** the account's email: the one given (the login), else the one on the `accounts` row */
    private fun address(uid: String, email: String?): String? {
        val given = email?.trim()?.takeIf { it.contains('@') }
        if (given != null) return given
        return runCatching {
            jdbc.queryForList("SELECT email FROM accounts WHERE uid = ?", String::class.java, uid).firstOrNull()
        }.getOrNull()?.trim()?.takeIf { it.contains('@') }
    }

    private fun send(uid: String, email: String?, letter: Letter) {
        if (DemoWorld.isDemoUid(uid)) return
        val to = try {
            address(uid, email)
        } catch (e: Exception) {
            null
        } ?: return
        val job = Runnable {
            try {
                mail.send(to, letter.subject, text(letter), html(letter))
            } catch (e: Exception) {
                log.error("account email '{}' to {} failed: {}", letter.subject, uid, e.message)
            }
        }
        try {
            if (async) pool.execute(job) else job.run()
        } catch (e: Exception) {
            log.error("account email not queued: {}", e.message)
        }
    }

    /** delete_account: the date it will be deleted (Asia/Dhaka), what goes, how to cancel */
    fun deletionRequested(uid: String, email: String?, deleteAtMs: Long) = send(uid, email, deletionRequestedLetter(deleteAtMs))

    /** a login within the 30 days cancelled the deletion */
    fun deletionCancelled(uid: String, email: String?) = send(uid, email, deletionCancelledLetter())

    fun deactivated(uid: String, email: String?) = send(uid, email, deactivatedLetter())

    fun reactivated(uid: String, email: String?) = send(uid, email, reactivatedLetter())

    /** Lifolo Pay (security review P4): a business's payment numbers changed — with the "আমি করিনি" freeze link */
    fun payNumbersChanged(uid: String, email: String?, changes: String, freezeUrl: String) = send(uid, email, payNumbersLetter(changes, freezeUrl))

    companion object {
        const val SITE = "https://lifolo.com"

        private fun ifNotYou(): String =
            "আপনি নিজে এটা না করে থাকলে এখনই ইমেইল করুন: ${LifoloProperties.SUPPORT_EMAIL}" // bn-ok

        fun deletionRequestedLetter(deleteAtMs: Long) = Letter(
            "Lifolo: অ্যাকাউন্ট মোছার অনুরোধ পেয়েছি", // bn-ok
            listOf(
                "আপনার Lifolo অ্যাকাউন্ট মোছার অনুরোধ পেয়েছি। অ্যাকাউন্টটা ${DhakaTime.longDate(deleteAtMs, false)} তারিখে পুরোপুরি মুছে যাবে।", // bn-ok
                "যা মুছে যাবে: আপনার প্রোফাইল (নাম, ছবি, ফোন, রক্তের গ্রুপ, এলাকা), পরিবারের সদস্য, রক্তের রিকোয়েস্ট ও সাড়া, প্রশ্ন-উত্তর ও টিপস, অ্যাপয়েন্টমেন্ট, ফার্মেসি ও প্যাথলজির অনুরোধ, রিপোর্ট, নোটিফিকেশন, কেয়ারগিভার সংযোগ, আর আপনার অ্যাম্বুলেন্স, ফার্মেসি, ব্লাড ব্যাংক, প্যাথলজি, চেম্বার ও বাসায় সেবা।", // bn-ok
                "৩০ দিনের মধ্যে লগইন করলে মোছা বাতিল হবে।", // bn-ok
                ifNotYou(),
                SITE,
                "Your Lifolo account will be deleted on ${DhakaTime.longDate(deleteAtMs, true)}. Log in within 30 days to cancel.",
            ),
        )

        fun deletionCancelledLetter() = Letter(
            "Lifolo: অ্যাকাউন্ট মোছা বাতিল হয়েছে", // bn-ok
            listOf(
                "আপনি আবার লগইন করেছেন, তাই মোছা বাতিল হয়েছে। আপনার Lifolo অ্যাকাউন্ট আর সব তথ্য আগের মতো আছে।", // bn-ok
                ifNotYou(),
                SITE,
                "Your Lifolo account deletion was cancelled because you logged in again.",
            ),
        )

        fun deactivatedLetter() = Letter(
            "Lifolo: অ্যাকাউন্ট সাময়িক বন্ধ করা হয়েছে", // bn-ok
            listOf(
                "অ্যাকাউন্ট সাময়িক বন্ধ করা হয়েছে — আবার লগইন করে চালু করতে পারবেন।", // bn-ok
                "বন্ধ থাকার সময় আপনার প্রোফাইল আর তালিকা (ফার্মেসি, প্যাথলজি, অ্যাম্বুলেন্স, ব্লাড ব্যাংক, চেম্বার, বাসায় সেবা) কেউ দেখবে না, রক্তদাতা খোঁজায় আপনি আসবেন না, আর অ্যাকাউন্টের খবর ছাড়া কোনো নোটিফিকেশন আসবে না। কিছুই মোছা হয়নি।", // bn-ok
                ifNotYou(),
                SITE,
                "Your Lifolo account is temporarily deactivated. Log in again to turn it back on. Nothing was deleted.",
            ),
        )

        fun reactivatedLetter() = Letter(
            "Lifolo: অ্যাকাউন্ট আবার চালু হয়েছে", // bn-ok
            listOf(
                "অ্যাকাউন্ট আবার চালু হয়েছে। আপনার প্রোফাইল আর তালিকা আবার আগের মতো দেখা যাবে।", // bn-ok
                ifNotYou(),
                SITE,
                "Your Lifolo account is active again.",
            ),
        )

        fun payNumbersLetter(changes: String, freezeUrl: String) = Letter(
            "Lifolo: আপনার পেমেন্ট নম্বর বদলানো হয়েছে", // bn-ok
            listOf(
                "আপনার প্রতিষ্ঠানের পেমেন্ট নম্বর বদলানো হয়েছে: $changes", // bn-ok
                "নতুন নম্বর ২৪ ঘণ্টা পরে রোগীরা দেখবেন।", // bn-ok
                "আপনি নিজে এটা না করে থাকলে নিচের লিংকে \"আমি করিনি\" চাপুন — সব পেমেন্ট নম্বর সাথে সাথে বন্ধ হবে আর লাইফলো টিম যোগাযোগ করবে:", // bn-ok
                freezeUrl,
                ifNotYou(),
                SITE,
                "Your payment numbers on Lifolo were changed ($changes). If this was not you, open the link above (\"I did not do this\") to freeze them at once.",
            ),
        )

        fun text(l: Letter): String = l.lines.joinToString("\n\n")

        private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        fun html(l: Letter): String = l.lines.joinToString("") { line ->
            when {
                line == SITE -> "<p><a href=\"$SITE\">lifolo.com</a></p>"
                line.startsWith("https://") && !line.contains(' ') -> "<p><a href=\"${esc(line)}\">আমি করিনি — নম্বর বন্ধ করুন</a></p>" // bn-ok
                else -> "<p>${esc(line)}</p>"
            }
        }
    }
}
