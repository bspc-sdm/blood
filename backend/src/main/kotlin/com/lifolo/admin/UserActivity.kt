package com.lifolo.admin

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.lifolo.auth.AdminService
import com.lifolo.common.DhakaTime
import com.lifolo.common.Js
import com.lifolo.common.Phones
import com.lifolo.common.Rules
import com.lifolo.common.action.ActionContext
import com.lifolo.common.action.ActionHandler
import com.lifolo.common.action.ActionModule
import com.lifolo.appointments.CareRules
import com.lifolo.appointments.ProviderStore
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp

/**
 * useractivity.js — `admin_user_section`: everything one user did, per part of the app (blood, community, services,
 * care, notifications, appeals, the admins' actions about them). Read only; the dispatcher logs who looked.
 * (Node's collection-group queries could need an index and then said so in `*Note`; SQL needs none, the notes are "".)
 */
@Component
class UserActivity(
    private val jdbc: JdbcTemplate,
    private val admins: AdminService,
    private val providers: ProviderStore,
    private val mapper: ObjectMapper,
) : ActionModule {
    override fun actions(): Map<String, ActionHandler> = mapOf("admin_user_section" to ActionHandler { section(it) })

    companion object {
        val SECTIONS = listOf("blood", "community", "services", "care", "inbox", "support", "admin")
    }

    fun section(ctx: ActionContext): Any {
        if (!admins.isAdmin(ctx.user)) throw ctx.err(403, "Admins only", "শুধু অ্যাডমিন")
        val uid = ctx.body.string("uid")
        val section = ctx.body.string("section")
        if (!Rules.isValidId(uid) || section == null || section !in SECTIONS) throw ctx.err(400, "Not found", "পাওয়া যায়নি")
        val out = linkedMapOf<String, Any?>("section" to section)
        out.putAll(
            when (section) {
                "blood" -> blood(uid!!)
                "community" -> community(uid!!)
                "services" -> services(uid!!)
                "care" -> care(uid!!)
                "inbox" -> inbox(uid!!)
                "support" -> support(uid!!)
                else -> adminLog(uid!!)
            },
        )
        return out
    }

    private fun ms(v: Any?): Long = (v as? Timestamp)?.time ?: 0L

    private fun s(v: Any?): String = (v as? String) ?: ""

    private fun rows(sql: String, vararg args: Any?): List<Map<String, Any?>> = jdbc.queryForList(sql, *args)

    private fun blood(uid: String): Map<String, Any?> {
        val u = rows("SELECT * FROM user_profiles WHERE uid = ?", uid).firstOrNull()
        val profile = u?.let {
            linkedMapOf(
                "bloodGroup" to s(it["blood_group"]), "lastDonationMillis" to it["last_donation_ms"], "notify" to (it["notify"] != false),
                "placeName" to s(it["place_name"]), "available" to true,
                "devices" to (jdbc.queryForObject("SELECT count(*) FROM device_tokens WHERE uid = ?", Int::class.java, uid) ?: 0),
                "openCommitments" to (jdbc.queryForObject("SELECT count(*) FROM user_commitments WHERE uid = ?", Int::class.java, uid) ?: 0),
            )
        }
        val requests = rows(
            "SELECT * FROM blood_requests WHERE requester_uid = ? ORDER BY created_at DESC LIMIT 30", uid,
        ).map {
            linkedMapOf(
                "id" to it["id"], "at" to ms(it["created_at"]), "status" to it["status"], "group" to it["patient_group"],
                "bags" to ((it["bags"] as? Number)?.toInt()?.takeIf { b -> b != 0 } ?: 1), "hospital" to s(it["hospital"]), "place" to s(it["place_name"]),
                "accepted" to (it["accepted_count"] ?: 0), "confirmed" to (it["confirmed_count"] ?: 0), "closedBy" to s(it["closed_by"]),
            )
        }
        val responses = rows(
            """
            SELECT r.request_id, r.created_at, r.updated_at, r.status, r.confirmed, q.hospital, q.patient_group, q.requester_uid
            FROM blood_responses r LEFT JOIN blood_requests q ON q.id = r.request_id
            WHERE r.donor_uid = ? ORDER BY r.created_at DESC LIMIT 30
            """.trimIndent(),
            uid,
        ).map {
            linkedMapOf(
                "requestId" to it["request_id"], "at" to (ms(it["created_at"]).takeIf { a -> a != 0L } ?: ms(it["updated_at"])),
                "status" to it["status"], "confirmed" to (it["confirmed"] == true),
                "hospital" to (it["hospital"] ?: ""), "group" to (it["patient_group"] ?: ""), "requesterUid" to (it["requester_uid"] ?: ""),
            )
        }
        val emergencies = rows(
            """
            SELECT d.emergency_id, d.status, d.coming_at, d.notified_at, e.hospital_name
            FROM emergency_donors d LEFT JOIN emergencies e ON e.id = d.emergency_id WHERE d.uid = ? LIMIT 30
            """.trimIndent(),
            uid,
        ).map {
            linkedMapOf(
                "id" to it["emergency_id"], "status" to it["status"],
                "at" to (ms(it["coming_at"]).takeIf { a -> a != 0L } ?: ms(it["notified_at"])), "hospital" to (it["hospital_name"] ?: ""),
            )
        }.sortedByDescending { it["at"] as Long }
        val corrections = rows(
            "SELECT created_at, status, reason, requested_ms FROM correction_requests WHERE uid = ? ORDER BY created_at DESC LIMIT 20", uid,
        ).map {
            linkedMapOf("at" to ms(it["created_at"]), "status" to it["status"], "reason" to s(it["reason"]), "requestedMillis" to it["requested_ms"])
        }
        return linkedMapOf(
            "profile" to profile, "requests" to requests, "responses" to responses, "responsesNote" to "",
            "emergencies" to emergencies, "emergenciesNote" to "", "corrections" to corrections,
        )
    }

    private fun community(uid: String): Map<String, Any?> {
        val questions = rows("SELECT * FROM questions WHERE uid = ? ORDER BY created_at DESC LIMIT 30", uid).map {
            linkedMapOf(
                "id" to it["id"], "at" to ms(it["created_at"]), "text" to s(it["text"]).take(300), "anonymous" to (it["anonymous"] == true),
                "answers" to (it["answer_count"] ?: 0), "edited" to (it["edited_at"] != null),
            )
        }
        val answers = rows("SELECT * FROM answers WHERE uid = ? ORDER BY created_at DESC LIMIT 30", uid).map {
            linkedMapOf("questionId" to it["question_id"], "at" to ms(it["created_at"]), "text" to s(it["text"]).take(300), "edited" to (it["edited_at"] != null))
        }
        val tips = rows("SELECT * FROM tips WHERE uid = ? ORDER BY created_at DESC LIMIT 30", uid).map {
            linkedMapOf("id" to it["id"], "at" to ms(it["created_at"]), "title" to s(it["title"]), "body" to s(it["body"]).take(200))
        }
        val reportsMade = rows("SELECT * FROM mod_reports WHERE reporter_uid = ? ORDER BY created_at DESC LIMIT 30", uid).map {
            linkedMapOf("at" to ms(it["created_at"]), "kind" to it["kind"], "target" to it["target_path"], "reason" to s(it["reason"]), "status" to it["status"])
        }
        return linkedMapOf("questions" to questions, "answers" to answers, "answersNote" to "", "tips" to tips, "reportsMade" to reportsMade)
    }

    /** a JS number: whole values without ".0" */
    private fun num(d: Double): Number = if (d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong() else d

    private fun rating(sum: Any?, count: Any?): Number {
        val c = (count as? Number)?.toInt() ?: 0
        if (c == 0) return 0
        return num(Js.round(((sum as? Number)?.toDouble() ?: 0.0) / c * 10) / 10)
    }

    private fun listing(it: Map<String, Any?>, id: Any?): LinkedHashMap<String, Any?> = linkedMapOf(
        "id" to id, "name" to it["name"], "status" to it["status"], "phone" to Phones.fixPhone(it["phone"] as? String), "district" to s(it["district"]),
        "statusReason" to s(it["status_reason"]), "verified" to (it["verified_at"] != null), "rating" to rating(it["rating_sum"], it["rating_count"]),
    )

    private fun services(uid: String): Map<String, Any?> {
        val ambulances = rows("SELECT * FROM ambulances WHERE owner_uid = ? ORDER BY created_at LIMIT 10", uid).map {
            listing(it, it["id"]).apply { put("vehicleNo", s(it["vehicle_no"])) }
        }
        val pharmacy = rows("SELECT * FROM pharmacies WHERE uid = ?", uid).firstOrNull()?.let {
            listing(it, it["uid"]).apply { put("licenseNo", s(it["license_no"])) }
        }
        val bloodBanks = rows("SELECT * FROM blood_banks WHERE owner_uid = ? ORDER BY created_at LIMIT 5", uid).map {
            listing(it, it["id"]).apply {
                put("rating", 0)
                put("kind", s(it["kind"]))
            }
        }
        val gone = "(মুছে গেছে)"
        fun call(kind: String, r: Map<String, Any?>) = linkedMapOf(
            "kind" to kind, "at" to ms(r["at"]), "firstAt" to ms(r["first_at"]), "name" to ((r["name"] as? String) ?: gone),
            "rating" to (r["rating"] ?: 0), "comment" to s(r["comment"]),
        )
        val calls = (
            rows(
                "SELECT c.at, c.first_at, c.rating, c.comment, a.name FROM ambulance_calls c LEFT JOIN ambulances a ON a.id = c.ambulance_id WHERE c.uid = ? LIMIT 50", uid,
            ).map { call("ambulance", it) } +
                rows(
                    "SELECT c.at, c.first_at, NULL AS rating, NULL AS comment, p.name FROM pharmacy_calls c LEFT JOIN pharmacies p ON p.uid = c.pharmacy_uid WHERE c.uid = ? LIMIT 50", uid,
                ).map { call("pharmacy", it) } +
                rows(
                    "SELECT c.at, NULL AS first_at, NULL AS rating, NULL AS comment, b.name FROM blood_bank_calls c LEFT JOIN blood_banks b ON b.id = c.bank_id WHERE c.uid = ? LIMIT 50", uid,
                ).map { call("bloodbank", it) } +
                rows(
                    "SELECT c.at, c.first_at, c.rating, c.comment, p.name FROM home_calls c LEFT JOIN providers p ON p.uid = c.provider_uid WHERE c.uid = ? LIMIT 50", uid,
                ).map { call("homecare", it) }
            ).sortedByDescending { it["at"] as Long }.take(80)
        val medFinds = rows(
            """
            SELECT f.*, (SELECT count(*) FROM med_find_targets t WHERE t.find_id = f.id) AS asked,
              (SELECT count(*) FROM med_find_targets t WHERE t.find_id = f.id AND t.have) AS have
            FROM med_finds f WHERE f.uid = ? ORDER BY f.created_at DESC LIMIT 30
            """.trimIndent(),
            uid,
        ).map {
            linkedMapOf(
                "at" to ms(it["created_at"]), "drug" to s(it["drug_name"]), "district" to s(it["district"]), "status" to it["status"],
                "asked" to (it["asked"] as Number).toInt(), "have" to (it["have"] as Number).toInt(),
            )
        }
        return linkedMapOf("ambulances" to ambulances, "pharmacy" to pharmacy, "bloodBanks" to bloodBanks, "calls" to calls, "medFinds" to medFinds)
    }

    private fun care(uid: String): Map<String, Any?> {
        val prov = rows("SELECT * FROM providers WHERE uid = ?", uid).firstOrNull()
        val provider = prov?.let { p ->
            linkedMapOf(
                "name" to s(p["name"]), "type" to s(p["type"]), "listed" to (p["listed"] == true), "suspended" to (p["admin_suspended"] == true),
                "chambers" to (providers.load(uid)?.chambers ?: emptyList()).map { c ->
                    linkedMapOf(
                        "hospital" to if (c.custom) "${c.hospitalName} (নিজে লেখা) · ${c.address ?: ""}" else c.hospitalName,
                        "days" to (c.days ?: emptyList<Int>()), "start" to (c.start ?: ""), "end" to (c.end ?: ""),
                        "times" to CareRules.chamberTimes(c).map { t -> t.toMap() }, "appointments" to c.appointments,
                    )
                },
                "homeVisit" to (p["home_visit_enabled"] == true), "homePhone" to Phones.fixPhone(p["home_phone"] as? String),
            )
        }
        fun appt(role: String, a: Map<String, Any?>) = linkedMapOf(
            "at" to ms(a["created_at"]), "role" to role, "status" to a["status"],
            "date" to ((a["visit_date"] as? java.sql.Date)?.let { DhakaTime.toUtcMidnightMs(it.toLocalDate()) } ?: 0L),
            "chamber" to s(a["chamber"]), "serial" to a["serial"],
            "other" to if (role == "patient") s(a["provider_name"]) else s(a["patient_name"]),
        )
        val appointments = (
            rows("SELECT * FROM appointments WHERE patient_uid = ? ORDER BY created_at DESC LIMIT 50", uid).map { appt("patient", it) } +
                rows("SELECT * FROM appointments WHERE provider_uid = ? ORDER BY created_at DESC LIMIT 50", uid).map { appt("provider", it) }
            ).sortedByDescending { it["at"] as Long }
        val homeCallsReceived = jdbc.queryForObject("SELECT count(*) FROM (SELECT 1 FROM home_calls WHERE provider_uid = ? LIMIT 50) x", Int::class.java, uid) ?: 0
        return linkedMapOf("provider" to provider, "appointments" to appointments, "homeCallsReceived" to homeCallsReceived)
    }

    private fun inbox(uid: String): Map<String, Any?> = linkedMapOf(
        "items" to rows("SELECT created_at, title, body, data FROM inbox_items WHERE uid = ? ORDER BY created_at DESC LIMIT 60", uid).map {
            val data = (it["data"]?.toString())?.let { j -> mapper.readValue(j, object : TypeReference<Map<String, Any?>>() {}) } ?: emptyMap()
            linkedMapOf("at" to ms(it["created_at"]), "title" to s(it["title"]), "body" to s(it["body"]), "type" to ((data["type"] as? String) ?: ""))
        },
    )

    private fun support(uid: String): Map<String, Any?> = linkedMapOf(
        "items" to rows(
            """
            SELECT t.id, t.updated_at, t.status, t.category,
              (SELECT m.text FROM ticket_messages m WHERE m.ticket_id = t.id ORDER BY m.idx LIMIT 1) AS subject,
              (SELECT count(*) FROM ticket_messages m WHERE m.ticket_id = t.id) AS messages
            FROM tickets t WHERE t.uid = ? ORDER BY t.updated_at DESC LIMIT 30
            """.trimIndent(),
            uid,
        ).map {
            linkedMapOf(
                "id" to it["id"], "at" to ms(it["updated_at"]), "status" to it["status"], "category" to it["category"],
                "subject" to s(it["subject"]).take(200), "messages" to (it["messages"] as Number).toInt(),
            )
        },
    )

    /** the admins' actions on this person: the account, the badge and their pharmacy/ambulance/blood bank/lab decisions */
    private fun adminLog(uid: String): Map<String, Any?> {
        val owned = ArrayList<String>()
        rows("SELECT uid FROM pharmacies WHERE uid = ?", uid).forEach { owned.add("pharmacies/${it["uid"]}") }
        rows("SELECT id FROM ambulances WHERE owner_uid = ? LIMIT 20", uid).forEach { owned.add("ambulances/${it["id"]}") }
        rows("SELECT id FROM blood_banks WHERE owner_uid = ? LIMIT 20", uid).forEach { owned.add("bloodBanks/${it["id"]}") }
        rows("SELECT uid FROM labs WHERE uid = ?", uid).forEach { owned.add("labs/${it["uid"]}") }
        val targets = (listOf("users/$uid", "badges/$uid") + owned).distinct()
        return linkedMapOf(
            "items" to rows("SELECT * FROM admin_log WHERE target = ANY(?) ORDER BY at DESC LIMIT 100", targets.toTypedArray()).map {
                linkedMapOf(
                    "at" to ms(it["at"]), "action" to it["action"], "detail" to s(it["detail"]), "by" to s(it["by_email"]),
                    "via" to s(it["via"]), "target" to s(it["target"]),
                )
            },
        )
    }
}
