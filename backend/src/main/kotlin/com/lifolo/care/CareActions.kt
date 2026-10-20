package com.lifolo.care

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lifolo.appointments.P2Clock
import com.lifolo.appointments.P2Users
import com.lifolo.auth.IdentityException
import com.lifolo.auth.IdentityProvider
import com.lifolo.common.ApiException
import com.lifolo.common.Ids
import com.lifolo.common.Js
import com.lifolo.common.Lang
import com.lifolo.common.Phones
import com.lifolo.common.QuotaService
import com.lifolo.common.Rules
import com.lifolo.common.Sql
import com.lifolo.common.action.ActionContext
import com.lifolo.common.action.ActionHandler
import com.lifolo.common.action.ActionModule
import com.lifolo.demo.DemoWorld
import com.lifolo.notify.PushItem
import com.lifolo.notify.PushService
import com.lifolo.users.AccountService
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * careaccess.js (17 Oct 2026) — caregivers and family members.
 *  - Everyone may add 1–2 caregivers by their Lifolo account email; once they accept, they act for the patient from
 *    their own phone (body.asUid, [CareAccess]) and get the patient's notifications. The patient can remove them.
 *  - Family members without an account (elderly parents, children): up to 6, on the profile (`user_family_members`);
 *    serials can be booked in their name.
 * careLinks/{patientUid}_{caretakerUid} → `care_links`.
 */
@Component
class CareActions(
    private val jdbc: JdbcTemplate,
    private val users: P2Users,
    private val identity: IdentityProvider,
    private val accounts: AccountService,
    private val quotas: QuotaService,
    private val push: PushService,
    private val clock: P2Clock,
    txm: PlatformTransactionManager,
) : ActionModule {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(txm)

    override fun actions(): Map<String, ActionHandler> = mapOf(
        "care_list" to ActionHandler(::careList),
        "care_invite" to ActionHandler(::careInvite),
        "care_respond" to ActionHandler(::careRespond),
        "care_remove" to ActionHandler(::careRemove),
        "family_get" to ActionHandler(::familyGet),
        "family_set" to ActionHandler(::familySet),
    )

    companion object {
        const val MAX_CARETAKERS = 2
        const val MAX_FAMILY = 6
        private val EMAIL_RE = Regex("^[^\\s@]{1,64}@[^\\s@]{1,190}\\.[^\\s@]{2,}$")
        private val MEMBER_ID_RE = Regex("^m_[a-z0-9]{6,12}$")
    }

    private fun err(ctx: ActionContext, en: String, bn: String, status: Int = 400): ApiException = ctx.err(status, en, bn)

    private fun links(where: String, uid: String, limit: Int): List<Map<String, Any?>> = jdbc.query(
        "SELECT * FROM care_links WHERE $where = ? ORDER BY created_at, patient_uid, caretaker_uid LIMIT ?",
        { rs, _ ->
            linkedMapOf<String, Any?>(
                "patientUid" to rs.getString("patient_uid"),
                "caretakerUid" to rs.getString("caretaker_uid"),
                "patientName" to (rs.getString("patient_name") ?: ""),
                "caretakerName" to (rs.getString("caretaker_name") ?: ""),
                "caretakerEmail" to (rs.getString("caretaker_email") ?: ""),
                "status" to rs.getString("status"),
                "createdAt" to (rs.getTimestamp("created_at")?.time ?: 0L),
                "acceptedAt" to (rs.getTimestamp("accepted_at")?.time ?: 0L),
            )
        },
        uid, limit,
    )

    /** users/{uid}.family */
    fun family(uid: String): List<Map<String, Any?>> = jdbc.query(
        "SELECT member_id, name, relation, age, gender, phone FROM user_family_members WHERE uid = ? ORDER BY position",
        { rs, _ ->
            linkedMapOf<String, Any?>(
                "id" to rs.getString("member_id"),
                "name" to rs.getString("name"),
                "relation" to (rs.getString("relation") ?: ""),
                "age" to rs.getInt("age").let { if (rs.wasNull()) null else it },
                "gender" to (rs.getString("gender") ?: ""),
                "phone" to (rs.getString("phone") ?: ""),
            )
        },
        uid,
    )

    /** my caregivers, the people I care for, and family members */
    fun careList(ctx: ActionContext): Any {
        val uid = ctx.user.id
        return linkedMapOf(
            "caretakers" to links("patient_uid", uid, 10),
            "caringFor" to links("caretaker_uid", uid, 30),
            "family" to family(uid),
        )
    }

    /** patient: add a caregiver (their Lifolo account email) — active once they accept */
    fun careInvite(ctx: ActionContext): Any {
        val user = ctx.user
        val uid = user.id
        val email = Js.toStr(ctx.body["email"]).trim().lowercase()
        if (!EMAIL_RE.matches(email)) throw err(ctx, "Enter their email", "তাঁর ইমেইল দিন")
        if (email == user.email.lowercase()) throw err(ctx, "You can't add yourself", "নিজেকে যোগ করা যায় না")
        val mine = jdbc.queryForObject("SELECT count(*) FROM care_links WHERE patient_uid = ?", Int::class.java, uid) ?: 0
        if (minOf(mine, 10) >= MAX_CARETAKERS) {
            throw err(ctx, "Up to $MAX_CARETAKERS caregivers. Remove one first.", "সর্বোচ্চ ${MAX_CARETAKERS} জন কেয়ারগিভার। আগে একজনকে সরান।", 409)
        }
        val target = try {
            identity.getUserByEmail(email)
        } catch (e: IdentityException) {
            null
        } catch (e: Exception) {
            null
        } ?: throw err(ctx, "No Lifolo account with this email. Ask them to install Lifolo and log in first.", "এই ইমেইলে কোনো Lifolo অ্যাকাউন্ট নেই। আগে তাঁকে Lifolo খুলে লগইন করতে বলুন।", 404)
        if (target.uid == uid) throw err(ctx, "You can't add yourself", "নিজেকে যোগ করা যায় না")
        // demo and real accounts never link: a demo account is public (one-click login), so a real patient's caregiver
        // must never be one — answered like an unknown address (no hint that the account exists)
        if (!DemoWorld.sameWorld(uid, target.uid)) {
            throw err(ctx, "No Lifolo account with this email. Ask them to install Lifolo and log in first.", "এই ইমেইলে কোনো Lifolo অ্যাকাউন্ট নেই। আগে তাঁকে Lifolo খুলে লগইন করতে বলুন।", 404)
        }
        if (jdbc.queryForList("SELECT 1 FROM care_links WHERE patient_uid = ? AND caretaker_uid = ?", uid, target.uid).isNotEmpty()) {
            throw err(ctx, "Already added", "আগেই যোগ করা আছে", 409)
        }
        quotas.use(ctx.lang, uid, "care")
        val me = users.name(uid)
        val them = users.name(target.uid)
        // the caregiver may never have used the server (FK target)
        accounts.ensure(target.uid, target.email)
        jdbc.update(
            """
            INSERT INTO care_links (patient_uid, caretaker_uid, patient_name, caretaker_name, caretaker_email, status, created_at)
            VALUES (?, ?, ?, ?, ?, 'invited', ?)
            """.trimIndent(),
            uid, target.uid, me.take(80), them.take(80), email, Sql.ts(clock.now()),
        )
        val p = users.profiles(listOf(target.uid))[target.uid]
        if (p != null) {
            try {
                push.send(
                    listOf(
                        PushItem(
                            target.uid,
                            Lang.pick(p.lang, "$me wants you as their caregiver", "$me আপনাকে কেয়ারগিভার করতে চান"),
                            Lang.pick(p.lang, "Open Lifolo → Profile → Caregivers to accept", "Lifolo → প্রোফাইল → কেয়ারগিভার থেকে রাজি হন"),
                            mapOf("type" to "care"),
                        ),
                    ),
                )
            } catch (e: Exception) {
                log.warn("care invite push failed: {}", e.message)
            }
        }
        return mapOf("ok" to true)
    }

    /** caregiver: accept / decline */
    fun careRespond(ctx: ActionContext): Any {
        val uid = ctx.user.id
        val patientUid = ctx.body.string("patientUid")
        if (!Rules.isValidId(patientUid)) throw err(ctx, "Not found", "পাওয়া যায়নি", 404)
        val link = jdbc.queryForList("SELECT caretaker_name FROM care_links WHERE patient_uid = ? AND caretaker_uid = ?", patientUid, uid).firstOrNull()
            ?: throw err(ctx, "Not found", "পাওয়া যায়নি", 404)
        if (ctx.body.isTrue("accept")) {
            jdbc.update(
                "UPDATE care_links SET status = 'active', accepted_at = ?, caretaker_name = ? WHERE patient_uid = ? AND caretaker_uid = ?",
                Sql.ts(clock.now()), users.name(uid).take(80), patientUid, uid,
            )
            // the notice uses the name stored at invitation time (Node reads the link before the update)
            val name = (link["caretaker_name"] as? String) ?: ""
            val p = users.profiles(listOf(patientUid))[patientUid!!]
            if (p != null) {
                try {
                    push.send(
                        listOf(
                            PushItem(
                                patientUid,
                                Lang.pick(p.lang, "$name is now your caregiver", "$name এখন আপনার কেয়ারগিভার"),
                                "",
                                mapOf("type" to "care"),
                            ),
                        ),
                    )
                } catch (e: Exception) {
                    log.warn("care respond push failed: {}", e.message)
                }
            }
        } else {
            jdbc.update("DELETE FROM care_links WHERE patient_uid = ? AND caretaker_uid = ?", patientUid, uid)
        }
        return mapOf("ok" to true)
    }

    /** the patient removes a caregiver, or a caregiver steps back */
    fun careRemove(ctx: ActionContext): Any {
        val uid = ctx.user.id
        val caretakerUid = ctx.body.string("caretakerUid")
        val patientUid = ctx.body.string("patientUid")
        when {
            Rules.isValidId(caretakerUid) -> jdbc.update("DELETE FROM care_links WHERE patient_uid = ? AND caretaker_uid = ?", uid, caretakerUid)
            Rules.isValidId(patientUid) -> jdbc.update("DELETE FROM care_links WHERE patient_uid = ? AND caretaker_uid = ?", patientUid, uid)
            else -> throw err(ctx, "Not found", "পাওয়া যায়নি", 404)
        }
        return mapOf("ok" to true)
    }

    /** family members (without an account of their own) */
    fun familyGet(ctx: ActionContext): Any = mapOf("family" to family(ctx.user.id))

    private fun cleanMember(ctx: ActionContext, m: JsonNode?, i: Int, used: MutableSet<String>): Map<String, Any?> {
        val o = m as? ObjectNode
        val name = Rules.cleanText(Js.toStr(o?.get("name")), 60) ?: throw err(ctx, "Member ${i + 1}: write the name", "সদস্য ${i + 1}: নাম লিখুন")
        val age = Js.integer(o?.get("age"))?.takeIf { it in 0..120 }?.toInt()
        val gender = Js.string(o?.get("gender"))?.takeIf { it == "male" || it == "female" } ?: ""
        val rawPhone = o?.get("phone")
        val hasPhone = Js.truthy(rawPhone)
        val phone = if (hasPhone) Phones.normalizeBd(Js.toStr(rawPhone)) else null
        if (hasPhone && phone == null) throw err(ctx, "$name: check the mobile number", "$name: মোবাইল নম্বর দেখে নিন")
        val sentId = Js.string(o?.get("id"))?.takeIf { MEMBER_ID_RE.matches(it) && it !in used }
        val id = sentId ?: "m_${Ids.randomHex(5)}"
        used.add(id)
        return linkedMapOf(
            "id" to id,
            "name" to name,
            "relation" to Js.toStr(o?.get("relation")).trim().take(30),
            "age" to age,
            "gender" to gender,
            "phone" to (if (phone != null) Phones.localBd(phone) else ""),
        )
    }

    fun familySet(ctx: ActionContext): Any {
        val uid = ctx.user.id
        val raw = ctx.body["family"]?.takeIf { it.isArray } ?: throw err(ctx, "Nothing to save", "সেভ করার কিছু নেই")
        if (raw.size() > MAX_FAMILY) throw err(ctx, "Up to $MAX_FAMILY members", "সর্বোচ্চ ${MAX_FAMILY} জন সদস্য")
        val used = HashSet<String>()
        val family = raw.mapIndexed { i, m -> cleanMember(ctx, m, i, used) }
        tx.executeWithoutResult {
            // users/{uid} set(merge) creates the profile document when there is none
            jdbc.update("INSERT INTO user_profiles (uid) VALUES (?) ON CONFLICT DO NOTHING", uid)
            jdbc.update("DELETE FROM user_family_members WHERE uid = ?", uid)
            family.forEachIndexed { i, m ->
                jdbc.update(
                    "INSERT INTO user_family_members (uid, member_id, position, name, relation, age, gender, phone) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    uid, m["id"], i, m["name"], m["relation"], m["age"], m["gender"], m["phone"],
                )
            }
        }
        return mapOf("family" to family)
    }

    /** account deletion: the links in both directions */
    fun deleteFor(uid: String) {
        jdbc.update("DELETE FROM care_links WHERE patient_uid = ? OR caretaker_uid = ?", uid, uid)
    }
}
