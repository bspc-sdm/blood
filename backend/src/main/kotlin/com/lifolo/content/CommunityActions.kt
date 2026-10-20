package com.lifolo.content

import com.lifolo.admin.AdminLog
import com.lifolo.admin.kernel.Moderation
import com.lifolo.admin.kernel.VerifyDocs
import com.lifolo.appointments.BadgeLookup
import com.lifolo.appointments.P2Clock
import com.lifolo.appointments.P2Users
import com.lifolo.appointments.PhoneCheck
import com.lifolo.auth.AdminService
import com.lifolo.common.Body
import com.lifolo.common.Ids
import com.lifolo.common.Lang
import com.lifolo.common.QuotaService
import com.lifolo.common.Rules
import com.lifolo.common.Sql
import com.lifolo.common.action.ActionContext
import com.lifolo.common.action.ActionHandler
import com.lifolo.common.action.ActionModule
import com.lifolo.contracts.ContentModeration
import com.lifolo.demo.DemoWorld
import com.lifolo.notify.PushItem
import com.lifolo.notify.PushService
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * community.js — Health Discussion (questions and answers by verified professionals), Health Tips, and `me`
 * (own badge, admin flags, phone verification, unread count). The app cannot read/write these tables itself; a badge
 * can only be set by an admin.
 */
@Component
class CommunityActions(
    private val jdbc: JdbcTemplate,
    private val badges: BadgeLookup,
    private val users: P2Users,
    private val admins: AdminService,
    private val adminLog: AdminLog,
    private val moderation: Moderation,
    private val verify: VerifyDocs,
    private val phone: PhoneCheck,
    private val quotas: QuotaService,
    private val push: PushService,
    private val clock: P2Clock,
    private val mapper: ObjectMapper,
    txm: PlatformTransactionManager,
) : ActionModule, ContentModeration {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(txm)

    override fun actions(): Map<String, ActionHandler> = mapOf(
        "me" to ActionHandler(::me),
        "questions" to ActionHandler(::questions),
        "question" to ActionHandler(::question),
        "ask" to ActionHandler(::ask),
        "answer" to ActionHandler(::answer),
        "edit_question" to ActionHandler(::editQuestion),
        "edit_answer" to ActionHandler(::editAnswer),
        "delete_question" to ActionHandler(::deleteQuestion),
        "delete_answer" to ActionHandler(::deleteAnswer),
        "tips" to ActionHandler(::tips),
        "post_tip" to ActionHandler(::postTip),
        "edit_tip" to ActionHandler(::editTip),
        "delete_tip" to ActionHandler(::deleteTip),
    )

    private fun notFound400(ctx: ActionContext) = ctx.err(400, "Not found", "পাওয়া যায়নি")

    private fun cursor(ctx: ActionContext): Long? = ctx.body.int("before")?.takeIf { it > 0 }

    private fun logDelete(ctx: ActionContext, path: String, text: String?) {
        adminLog.log("admin_delete_post", ctx.user, Body.of(mapper.valueToTree(mapOf("path" to path, "text" to text))))
    }

    // ------------------------------------------------------------------ me

    /** own badge and whether admin */
    fun me(ctx: ActionContext): Any {
        val user = ctx.user
        val uid = user.id
        val badge = jdbc.queryForList("SELECT * FROM badges WHERE uid = ?", uid).firstOrNull()?.let { s ->
            val status = s["status"] as String
            val requestedAt = (s["docs_requested_at"] as? java.sql.Timestamp)?.time
            val m = linkedMapOf<String, Any?>(
                "type" to s["type"],
                "status" to status,
                "regNo" to (s["reg_no"] ?: ""),
                "workplace" to (s["workplace"] ?: ""),
                // a correction of an approved badge is under review
                "changePending" to (s["change_at"] != null),
                "statusReason" to (s["status_reason"] ?: ""),
            )
            // pending/rejected or asked by an admin: which document photos are missing
            if (status == "pending" || status == "rejected" || requestedAt != null) {
                try {
                    m.putAll(verify.ownerInfo("badge", uid, BadgeActions.listingOf(s)))
                } catch (e: Exception) {
                    log.warn("badge owner info failed: {}", e.message)
                }
                m["docsRequestNote"] = s["docs_request_note"] ?: ""
            }
            m
        }
        val unread = try {
            jdbc.queryForObject(
                """
                SELECT count(*) FROM inbox_items i WHERE i.uid = ?
                  AND i.created_at > COALESCE((SELECT inbox_seen_at FROM user_state s WHERE s.uid = ?), to_timestamp(0))
                """.trimIndent(),
                Int::class.java, uid, uid,
            ) ?: 0
        } catch (e: Exception) {
            0
        }
        return linkedMapOf(
            "badge" to badge,
            "isAdmin" to admins.isAdmin(user),
            "isOwner" to admins.isOwner(user),
            // phone verification on, and the own number verified
            "phoneCheck" to phone.enabled(),
            "phoneVerified" to phone.isVerified(uid),
            "unread" to unread,
        )
    }

    // ------------------------------------------------------------------ Health Discussion

    fun questions(ctx: ActionContext): Any {
        val uid = ctx.user.uid
        val before = cursor(ctx)
        val rows = jdbc.queryForList(
            "SELECT * FROM questions" + (if (before != null) " WHERE created_at < ?" else "") + " ORDER BY created_at DESC, id LIMIT ?",
            *(if (before != null) arrayOf<Any?>(Sql.ts(before), Rules.PAGE) else arrayOf<Any?>(Rules.PAGE)),
        )
        return mapOf(
            "items" to rows.map { x ->
                linkedMapOf(
                    "id" to x["id"],
                    "text" to x["text"],
                    // no name for guests — nobody can collect real names next to health questions
                    "authorName" to (if (x["anonymous"] == true || uid == null) null else x["author_name"]),
                    "mine" to (x["uid"] == uid),
                    "createdAt" to (x["created_at"] as java.sql.Timestamp).time,
                    "answerCount" to x["answer_count"],
                )
            },
        )
    }

    fun question(ctx: ActionContext): Any {
        val uid = ctx.user.uid
        val id = ctx.body.string("id")
        if (!Rules.isValidId(id)) throw notFound400(ctx)
        val x = jdbc.queryForList("SELECT * FROM questions WHERE id = ?", id).firstOrNull()
            ?: throw ctx.err(404, "Question not found", "প্রশ্নটা পাওয়া যায়নি")
        val answers = jdbc.queryForList("SELECT * FROM answers WHERE question_id = ? ORDER BY created_at, id LIMIT 200", id)
        val live = badges.live(answers.map { it["uid"] as String })
        val mine = x["uid"] == uid
        val q = linkedMapOf<String, Any?>(
            "id" to x["id"],
            "text" to x["text"],
            "authorName" to (if (x["anonymous"] == true || uid == null) null else x["author_name"]),
            "mine" to mine,
        )
        // own question: is the name hidden (can be changed later)
        if (mine) q["anonymous"] = x["anonymous"] == true
        q["createdAt"] = (x["created_at"] as java.sql.Timestamp).time
        q["editedAt"] = (x["edited_at"] as? java.sql.Timestamp)?.time ?: 0L
        q["answerCount"] = x["answer_count"]
        return linkedMapOf(
            "question" to q,
            "answers" to answers.map { d ->
                val l = live[d["uid"] as String]
                linkedMapOf(
                    "id" to d["id"],
                    "text" to d["text"],
                    "authorName" to (l?.second?.takeIf { it.isNotEmpty() } ?: d["author_name"]),
                    "badge" to l?.first,
                    "mine" to (d["uid"] == uid),
                    "createdAt" to (d["created_at"] as java.sql.Timestamp).time,
                    "editedAt" to ((d["edited_at"] as? java.sql.Timestamp)?.time ?: 0L),
                )
            },
        )
    }

    fun ask(ctx: ActionContext): Any {
        val uid = ctx.user.id
        val text = Rules.cleanText(ctx.body.string("text"), 1000)
            ?: throw ctx.err(400, "Write your question (up to 1000 characters)", "প্রশ্ন লিখুন (১০০০ অক্ষরের মধ্যে)")
        quotas.use(ctx.lang, uid, "ask")
        val id = Ids.newId()
        jdbc.update(
            "INSERT INTO questions (id, uid, author_name, anonymous, text, answer_count, created_at) VALUES (?, ?, ?, ?, ?, 0, ?)",
            id, uid, users.name(uid).take(80), ctx.body.isTrue("anonymous"), text, Sql.ts(clock.now()),
        )
        return mapOf("id" to id)
    }

    fun answer(ctx: ActionContext): Any {
        val uid = ctx.user.id
        val id = ctx.body.string("id")
        if (!Rules.isValidId(id)) throw notFound400(ctx)
        val badge = badges.approvedBadge(uid)
            ?: throw ctx.err(403, "Only verified health professionals can answer", "শুধু যাচাই করা স্বাস্থ্য পেশাজীবীরা উত্তর দিতে পারেন")
        val text = Rules.cleanText(ctx.body.string("text"), 2000)
            ?: throw ctx.err(400, "Write your answer (up to 2000 characters)", "উত্তর লিখুন (২০০০ অক্ষরের মধ্যে)")
        val asker = jdbc.queryForList("SELECT uid FROM questions WHERE id = ?", String::class.java, id).firstOrNull()
            // a demo professional (public one-click login) never answers a real person's question as "verified"
            ?.takeIf { !DemoWorld.isDemoUid(uid) || DemoWorld.isDemoUid(it) }
            ?: throw ctx.err(404, "Question not found", "প্রশ্নটা পাওয়া যায়নি")
        quotas.use(ctx.lang, uid, "answer")
        val name = users.name(uid).take(80)
        val now = clock.now()
        tx.executeWithoutResult {
            jdbc.update(
                "INSERT INTO answers (id, question_id, uid, author_name, badge, text, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                Ids.newId(), id, uid, name, badge, text, Sql.ts(now),
            )
            jdbc.update("UPDATE questions SET answer_count = answer_count + 1, last_answer_at = ? WHERE id = ?", Sql.ts(now), id)
        }
        // tell the asker
        if (asker != uid) {
            try {
                val p = users.profiles(listOf(asker))[asker]
                if (p != null) {
                    push.send(
                        listOf(
                            PushItem(
                                asker,
                                Lang.pick(p.lang, "Your question has a new answer", "আপনার প্রশ্নের উত্তর এসেছে"),
                                text.take(120),
                                mapOf("type" to "answer", "questionId" to id),
                            ),
                        ),
                    )
                }
            } catch (e: Exception) {
                log.error("answer push failed", e)
            }
        }
        return mapOf("ok" to true)
    }

    /** edit one's own question (also with answers; shows "edited") */
    fun editQuestion(ctx: ActionContext): Any {
        val uid = ctx.user.id
        val id = ctx.body.string("id")
        if (!Rules.isValidId(id)) throw notFound400(ctx)
        val text = Rules.cleanText(ctx.body.string("text"), 1000)
            ?: throw ctx.err(400, "Write your question (up to 1000 characters)", "প্রশ্ন লিখুন (১০০০ অক্ষরের মধ্যে)")
        val s = jdbc.queryForList("SELECT uid, text, anonymous FROM questions WHERE id = ?", id).firstOrNull()
            ?: throw ctx.err(404, "Question not found", "প্রশ্নটা পাওয়া যায়নি")
        if (s["uid"] != uid) throw ctx.err(403, "Not allowed", "অনুমতি নেই")
        // "hide my name" can be switched later (not sent = as before)
        val anonNode = ctx.body.bool("anonymous")
        val anon = if (anonNode != null && anonNode != (s["anonymous"] == true)) anonNode else null
        if (s["text"] == text) {
            if (anon != null) jdbc.update("UPDATE questions SET anonymous = ? WHERE id = ?", anon, id)
            return mapOf("ok" to true)
        }
        jdbc.update(
            "UPDATE questions SET text = ?, edited_at = ?" + (if (anon != null) ", anonymous = ?" else "") + " WHERE id = ?",
            *(if (anon != null) arrayOf<Any?>(text, Sql.ts(clock.now()), anon, id) else arrayOf<Any?>(text, Sql.ts(clock.now()), id)),
        )
        moderation.reopenOnEdit("questions/$id")
        return mapOf("ok" to true)
    }

    fun editAnswer(ctx: ActionContext): Any {
        val uid = ctx.user.id
        val id = ctx.body.string("id")
        val answerId = ctx.body.string("answerId")
        if (!Rules.isValidId(id) || !Rules.isValidId(answerId)) throw notFound400(ctx)
        val text = Rules.cleanText(ctx.body.string("text"), 2000)
            ?: throw ctx.err(400, "Write your answer (up to 2000 characters)", "উত্তর লিখুন (২০০০ অক্ষরের মধ্যে)")
        val s = jdbc.queryForList("SELECT uid, text FROM answers WHERE id = ? AND question_id = ?", answerId, id).firstOrNull()
            ?: throw ctx.err(404, "Not found", "পাওয়া যায়নি")
        if (s["uid"] != uid) throw ctx.err(403, "Not allowed", "অনুমতি নেই")
        // the badge is gone: old answers cannot be rewritten (they can be deleted)
        if (badges.approvedBadge(uid) == null) throw ctx.err(403, "Your professional badge is not active", "আপনার পেশাদার ব্যাজ এখন চালু নেই")
        if (s["text"] == text) return mapOf("ok" to true)
        jdbc.update("UPDATE answers SET text = ?, edited_at = ? WHERE id = ?", text, Sql.ts(clock.now()), answerId)
        moderation.reopenOnEdit("questions/$id/answers/$answerId")
        return mapOf("ok" to true)
    }

    fun deleteQuestion(ctx: ActionContext): Any {
        val uid = ctx.user.id
        val id = ctx.body.string("id")
        if (!Rules.isValidId(id)) throw notFound400(ctx)
        val s = jdbc.queryForList("SELECT uid, text FROM questions WHERE id = ?", id).firstOrNull() ?: return mapOf("ok" to true)
        if (s["uid"] != uid && !admins.isAdmin(ctx.user)) throw ctx.err(403, "Not allowed", "অনুমতি নেই")
        removeQuestion(id!!)
        if (s["uid"] != uid) logDelete(ctx, "questions/$id", s["text"] as? String)
        return mapOf("ok" to true)
    }

    fun deleteAnswer(ctx: ActionContext): Any {
        val uid = ctx.user.id
        val id = ctx.body.string("id")
        val answerId = ctx.body.string("answerId")
        if (!Rules.isValidId(id) || !Rules.isValidId(answerId)) throw notFound400(ctx)
        val s = jdbc.queryForList("SELECT uid, text FROM answers WHERE id = ? AND question_id = ?", answerId, id).firstOrNull()
            ?: return mapOf("ok" to true)
        if (s["uid"] != uid && !admins.isAdmin(ctx.user)) throw ctx.err(403, "Not allowed", "অনুমতি নেই")
        removeAnswer(id!!, answerId!!)
        if (s["uid"] != uid) logDelete(ctx, "questions/$id/answers/$answerId", s["text"] as? String)
        return mapOf("ok" to true)
    }

    // ------------------------------------------------------------------ Health Tips

    fun tips(ctx: ActionContext): Any {
        val uid = ctx.user.uid
        val before = cursor(ctx)
        val rows = jdbc.queryForList(
            "SELECT * FROM tips" + (if (before != null) " WHERE created_at < ?" else "") + " ORDER BY created_at DESC, id LIMIT ?",
            *(if (before != null) arrayOf<Any?>(Sql.ts(before), Rules.PAGE) else arrayOf<Any?>(Rules.PAGE)),
        )
        val live = badges.live(rows.map { it["uid"] as String })
        return mapOf(
            "items" to rows.map { d ->
                val l = live[d["uid"] as String]
                linkedMapOf(
                    "id" to d["id"],
                    "title" to d["title"],
                    "body" to d["body"],
                    "authorName" to (l?.second?.takeIf { it.isNotEmpty() } ?: d["author_name"]),
                    "badge" to l?.first,
                    "mine" to (d["uid"] == uid),
                    "createdAt" to (d["created_at"] as java.sql.Timestamp).time,
                    "editedAt" to ((d["edited_at"] as? java.sql.Timestamp)?.time ?: 0L),
                )
            },
        )
    }

    fun postTip(ctx: ActionContext): Any {
        val uid = ctx.user.id
        val badge = badges.approvedBadge(uid)
            ?: throw ctx.err(403, "Only verified health professionals can post tips", "শুধু যাচাই করা স্বাস্থ্য পেশাজীবীরা টিপস দিতে পারেন")
        val title = Rules.cleanText(ctx.body.string("title"), 120)
        val body = Rules.cleanText(ctx.body.string("body"), 3000)
        if (title == null) throw ctx.err(400, "Add a title (up to 120 characters)", "শিরোনাম দিন (১২০ অক্ষরের মধ্যে)")
        if (body == null) throw ctx.err(400, "Write the tip (up to 3000 characters)", "টিপস লিখুন (৩০০০ অক্ষরের মধ্যে)")
        quotas.use(ctx.lang, uid, "tip")
        val id = Ids.newId()
        jdbc.update(
            "INSERT INTO tips (id, uid, author_name, badge, title, body, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            id, uid, users.name(uid).take(80), badge, title, body, Sql.ts(clock.now()),
        )
        return mapOf("id" to id)
    }

    fun editTip(ctx: ActionContext): Any {
        val uid = ctx.user.id
        val id = ctx.body.string("id")
        if (!Rules.isValidId(id)) throw notFound400(ctx)
        val title = Rules.cleanText(ctx.body.string("title"), 120)
        val body = Rules.cleanText(ctx.body.string("body"), 3000)
        if (title == null) throw ctx.err(400, "Add a title (up to 120 characters)", "শিরোনাম দিন (১২০ অক্ষরের মধ্যে)")
        if (body == null) throw ctx.err(400, "Write the tip (up to 3000 characters)", "টিপস লিখুন (৩০০০ অক্ষরের মধ্যে)")
        val s = jdbc.queryForList("SELECT uid, title, body FROM tips WHERE id = ?", id).firstOrNull()
            ?: throw ctx.err(404, "Not found", "পাওয়া যায়নি")
        if (s["uid"] != uid) throw ctx.err(403, "Not allowed", "অনুমতি নেই")
        if (badges.approvedBadge(uid) == null) throw ctx.err(403, "Your professional badge is not active", "আপনার পেশাদার ব্যাজ এখন চালু নেই")
        if (s["title"] == title && s["body"] == body) return mapOf("ok" to true)
        jdbc.update("UPDATE tips SET title = ?, body = ?, edited_at = ? WHERE id = ?", title, body, Sql.ts(clock.now()), id)
        moderation.reopenOnEdit("tips/$id")
        return mapOf("ok" to true)
    }

    fun deleteTip(ctx: ActionContext): Any {
        val uid = ctx.user.id
        val id = ctx.body.string("id")
        if (!Rules.isValidId(id)) throw notFound400(ctx)
        val s = jdbc.queryForList("SELECT uid, title FROM tips WHERE id = ?", id).firstOrNull() ?: return mapOf("ok" to true)
        if (s["uid"] != uid && !admins.isAdmin(ctx.user)) throw ctx.err(403, "Not allowed", "অনুমতি নেই")
        removeTip(id!!)
        if (s["uid"] != uid) logDelete(ctx, "tips/$id", s["title"] as? String)
        return mapOf("ok" to true)
    }

    // ------------------------------------------------------------------ ContentModeration (moderation.js removeTarget)

    /** a question with all its answers */
    override fun removeQuestion(id: String) {
        jdbc.update("DELETE FROM questions WHERE id = ?", id)
    }

    /** one answer; the question's answer count goes down */
    override fun removeAnswer(questionId: String, answerId: String) {
        tx.executeWithoutResult {
            if (jdbc.update("DELETE FROM answers WHERE id = ? AND question_id = ?", answerId, questionId) > 0) {
                jdbc.update("UPDATE questions SET answer_count = answer_count - 1 WHERE id = ?", questionId)
            }
        }
    }

    override fun removeTip(id: String) {
        jdbc.update("DELETE FROM tips WHERE id = ?", id)
    }

    /** account deletion: answers this user gave on others' questions (the counts go down) */
    fun deleteAnswersBy(uid: String) {
        val list = jdbc.query("SELECT id, question_id FROM answers WHERE uid = ?", { rs, _ -> rs.getString(1) to rs.getString(2) }, uid)
        for ((aid, qid) in list) removeAnswer(qid, aid)
    }
}
