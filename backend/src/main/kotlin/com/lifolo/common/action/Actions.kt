package com.lifolo.common.action

import com.lifolo.auth.AuthUser
import com.lifolo.common.ApiException
import com.lifolo.common.Body
import com.lifolo.common.Lang
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component

/**
 * One `/api/community` call. [user] is the actor (the patient when a caregiver acts for them),
 * [caller] the authenticated person (caregiver / admin / web session) — Node passes the actor to actions and
 * logs admin actions with the caller.
 */
class ActionContext(
    val request: HttpServletRequest,
    val response: HttpServletResponse,
    val body: Body,
    val user: AuthUser,
    val caller: AuthUser,
    val action: String,
) : CallCtx {
    override val lang: String? get() = Lang.of(request) ?: body.lang

    /** community.js tr: Bangla only for lang == "bn" */
    fun tr(en: String, bn: String): String = Lang.tr(lang, en, bn)

    /** HttpError with a bilingual message */
    override fun err(status: Int, en: String, bn: String, code: String?): ApiException = ApiException(status, tr(en, bn), code)

    fun notFound(): ApiException = err(404, "Not found", "পাওয়া যায়নি")
}

/**
 * The language + bilingual errors of a call — what services need when the same code runs for an HTTP action and for
 * something else (the desktop sync replaying an action body): [ActionContext], or [LangCtx].
 */
interface CallCtx {
    val lang: String?

    fun err(status: Int, en: String, bn: String, code: String? = null): ApiException
}

/** a [CallCtx] outside an HTTP action */
class LangCtx(override val lang: String?) : CallCtx {
    override fun err(status: Int, en: String, bn: String, code: String?): ApiException = ApiException(status, Lang.tr(lang, en, bn), code)
}

/** An action implementation. Return a Map/data object (serialized as JSON) — null means `{ok: true}`. */
fun interface ActionHandler {
    fun handle(ctx: ActionContext): Any?
}

/**
 * A module's actions. Each package (blood, rx, pharmacy, …) adds one @Component implementing this;
 * the registry picks it up automatically.
 */
interface ActionModule {
    fun actions(): Map<String, ActionHandler>
}

@Component
class ActionRegistry(modules: List<ActionModule>) {
    private val handlers: Map<String, ActionHandler>

    init {
        val map = LinkedHashMap<String, ActionHandler>()
        for (m in modules) {
            for ((name, h) in m.actions()) {
                check(map.put(name, h) == null) { "action '$name' is registered twice (${m.javaClass.simpleName})" }
            }
        }
        handlers = map
    }

    fun find(name: String): ActionHandler? = handlers[name]

    fun names(): Set<String> = handlers.keys
}

/**
 * Who may call what, kept in one place exactly like the Node lists:
 *  - guest.js PUBLIC_ACTIONS (no token; read-only; IP rate limit; responses cached 1 min)
 *  - careaccess.js CARE_ACTIONS (body.asUid = act for a patient)
 *  - community.js webUser: the admin cookie session may only call admin_* + a few read lists
 */
object ActionPolicy {
    val PUBLIC_ACTIONS: Set<String> = setOf(
        "guest_requests",
        "emergencies_active",
        "questions", "question", "tips",
        "providers", "home_providers", "hospitals",
        "ambulances", "pharmacies", "bloodbanks", "labs",
        "lab_prices_public",
        "drug_search", "catalog_get",
        // the public receipt check (lifolo.com/v/<code>): business, no, date, total, paid/due, valid/void — no personal data
        "invoice_verify",
        // feature switches (features/FeatureActions.kt) — answered per caller, never from the guest cache
        "features",
    )

    /** public actions whose guest answer must not be cached (it changes the moment an admin flips a switch) */
    val GUEST_UNCACHED: Set<String> = setOf("features")

    val CARE_ACTIONS: Set<String> = setOf(
        "rx_mine", "rx_get", "rx_claim", "rx_activity", "rx_pharmacy_options", "rx_send_pharmacy", "rx_order_withdraw", "rx_shop_find",
        "rx_send_lab", "rx_lab_options", "labs", "lab_prices_public", "lab_order_get", "review_pay", "labtests_get",
        "pharmacies",
        "lab_home_change", "lab_home_cancel",
        "my_appointments", "book", "appointment_update", "providers", "family_get",
        "appointment_queue", "appointment_late",
        // online chamber fee: the caregiver may say "টাকা পাঠিয়েছি" for the patient
        "appt_pay_claim", "appt_report",
        // receipts (docs/INVOICES.md): the patient's side only
        "invoice_get", "my_invoices", "my_expenses", "invoice_report",
    )

    /**
     * The only actions a deactivated account (account_deactivate) may call; everything else → 423 `account_deactivated`
     * (ActionDispatcher, checked on the authenticated caller).
     */
    val WHILE_DEACTIVATED: Set<String> = setOf("profile_get", "account_reactivate", "delete_account", "notify_prefs_get", "web_logout")

    /** besides admin_* (and web_logout) */
    val WEB_PANEL_READS: Set<String> = setOf("catalog_get", "labtests_get")

    fun isPublic(action: String): Boolean = action in PUBLIC_ACTIONS
}

/**
 * Called around every action (Node: `catalog.load()` before each request; phase 2 adds the catalog cache refresh).
 */
interface ActionHook {
    fun beforeAction(action: String) {}
}
