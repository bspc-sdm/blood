package com.lifolo.admin

import com.lifolo.auth.AuthUser
import com.lifolo.common.Body
import com.lifolo.common.Ids
import com.lifolo.common.Js
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

/**
 * adminweb.js logAdmin: successful admin actions are written to `admin_log` (who, when, what).
 * Only the actions in [LOGGED] are recorded; a failed log write never fails the action.
 */
@Service
class AdminLog(private val jdbc: JdbcTemplate) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun log(action: String, user: AuthUser, body: Body) {
        val fn = LOGGED[action] ?: return
        try {
            val (target, detail) = fn(body)
            jdbc.update(
                "INSERT INTO admin_log (id, at, action, by_email, by_uid, via, target, detail) VALUES (?, now(), ?, ?, ?, ?, ?, ?)",
                Ids.newId(), action, user.email.lowercase(), user.uid, if (user.webSession) "web" else "app",
                target.take(300), detail.take(300),
            )
        } catch (e: Exception) {
            log.error("admin log failed: {}", e.message)
        }
    }

    companion object {
        private fun t(b: Body, k: String) = Js.template(b[k])

        /** "approved"/"rejected"/status + reason, joined with " · " (filter(Boolean)) */
        private fun resolved(b: Body): String = listOf(
            if (Js.truthy(b["status"])) t(b, "status") else if (b.isTrue("approve")) "approved" else "rejected",
            if (Js.truthy(b["reason"])) t(b, "reason") else "",
        ).filter { it.isNotEmpty() }.joinToString(" · ")

        private fun itemsCount(b: Body): Int = b["items"]?.takeIf { it.isArray }?.size() ?: 0

        val LOGGED: Map<String, (Body) -> Pair<String, String>> = mapOf(
            "admin_demo_seed" to { _: Body -> "demo" to "seed" },
            "admin_demo_wipe" to { _: Body -> "demo" to "wipe" },
            "admin_badge_resolve" to { b: Body ->
                "badges/${t(b, "uid")}" to (if (Js.truthy(b["status"])) t(b, "status") else if (b.isTrue("approve")) "approved" else "rejected")
            },
            "admin_pharmacy_resolve" to { b: Body -> "pharmacies/${t(b, "id")}" to resolved(b) },
            "admin_bloodbank_resolve" to { b: Body -> "bloodBanks/${t(b, "id")}" to resolved(b) },
            "admin_emergency_create" to { b: Body ->
                val hosp = if (Js.truthy(b["hospitalName"])) t(b, "hospitalName") else if (Js.truthy(b["hospitalId"])) t(b, "hospitalId") else ""
                "emergencies" to "$hosp, ${if (Js.truthy(b["district"])) t(b, "district") else ""}"
            },
            "admin_emergency_more" to { b: Body -> "emergencies/${t(b, "id")}" to "more" },
            "admin_emergency_group" to { b: Body -> "emergencies/${t(b, "id")}" to "${t(b, "group")} ${if (b.isTrue("closed")) "closed" else "opened"}" },
            "admin_emergency_close" to { b: Body -> "emergencies/${t(b, "id")}" to "closed" },
            "admin_bloodbank_add" to { b: Body -> "bloodBanks" to "add ${if (Js.truthy(b["name"])) t(b, "name") else ""}" },
            "admin_bloodbank_edit" to { b: Body -> "bloodBanks/${t(b, "id")}" to "edit ${if (Js.truthy(b["name"])) t(b, "name") else ""}" },
            "admin_bloodbank_delete" to { b: Body -> "bloodBanks/${t(b, "id")}" to "deleted" },
            "admin_ambulance_resolve" to { b: Body -> "ambulances/${t(b, "id")}" to resolved(b) },
            "admin_hospital_resolve" to { b: Body ->
                "hospitalRequests/${t(b, "id")}" to (t(b, "op") + if (Js.truthy(b["targetId"])) " → ${t(b, "targetId")}" else "")
            },
            "admin_delete_post" to { b: Body -> Js.toStr(b["path"]) to "deleted: ${(if (Js.truthy(b["text"])) Js.toStr(b["text"]) else "").take(120)}" },
            "admin_report_resolve" to { b: Body -> Js.toStr(b["targetPath"]) to (if (b.isTrue("remove")) "removed" else "kept") },
            "admin_lab_resolve" to { b: Body -> "labs/${t(b, "id")}" to resolved(b) },
            "admin_catalog_set" to { b: Body -> "config/catalog/${t(b, "kind")}/${t(b, "type")}" to "${itemsCount(b)} items" },
            "admin_labtests_set" to { b: Body ->
                val test = b.obj("test")
                val code = test?.get("code")?.let { if (Js.truthy(it)) Js.toStr(it) else null } ?: "new"
                val name = test?.get("name")?.let { if (Js.truthy(it)) Js.toStr(it) else null } ?: ""
                "config/labTests/$code" to name
            },
            "admin_ticket_reply" to { b: Body ->
                "tickets/${t(b, "id")}" to (if (b.isTrue("close")) (if (Js.truthy(b["text"])) "replied+closed" else "closed") else "replied")
            },
            "admin_user_section" to { b: Body -> "users/${t(b, "uid")}" to "viewed ${t(b, "section")}" },
            "admin_verify_docs" to { b: Body -> "${t(b, "kind")}/${t(b, "id")}" to "viewed documents" },
            "admin_request_docs" to { b: Body ->
                "${t(b, "kind")}/${t(b, "id")}" to "asked for documents${if (Js.truthy(b["note"])) ": ${t(b, "note")}" else ""}"
            },
            "admin_user_action" to { b: Body ->
                "users/${t(b, "uid")}" to listOf(b["op"], b["reason"]).filter { Js.truthy(it) }.joinToString(" · ") { Js.toStr(it) }
            },
            "admin_call_report_set" to { b: Body -> "callReports/${t(b, "id")}" to t(b, "status") },
            "admin_feature_set" to { b: Body ->
                val pilots = b["pilotUids"]?.takeIf { it.isArray }?.size()
                "features/${t(b, "key")}" to listOfNotNull(
                    t(b, "mode"), pilots?.let { "pilots=$it" }, b.bool("demo")?.let { "demo=$it" },
                ).joinToString(" ")
            },
            "admin_add" to { b: Body -> "config/admins" to "add ${t(b, "email")}" },
            "admin_remove" to { b: Body -> "config/admins" to "remove ${t(b, "email")}" },
            "correction_resolve" to { b: Body -> "correctionRequests/${t(b, "id")}" to (if (b.isTrue("approve")) "approved" else "rejected") },
        )
    }
}
