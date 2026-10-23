package com.lifolo.invoices

import com.lifolo.common.ApiException
import com.lifolo.common.Lang
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * The receipt feature switches (scratchpad flags CONTRACT / docs/FEATURES.md): `inv_pharmacy`, `inv_lab`, `inv_chamber`,
 * `inv_ambulance` (one per issuer kind) and `my_expenses` (my_invoices / my_expenses / the patient's receipt push).
 * Everything is OFF unless switched on. A shim until `Features.isOn/require` (spring-p19) is on main: every key follows
 * `lifolo.features-shim-on` (false = off in production, true in the test profile); [forced] overrides a key (tests).
 * When p19 lands, [isOn] delegates to `Features.isOn(uid, key)`.
 */
@Component
class InvoiceFeatures(@Value("\${lifolo.features-shim-on:false}") private val allOn: Boolean) {
    /** key → on/off for everyone (tests switch single keys off) */
    val forced: MutableMap<String, Boolean> = ConcurrentHashMap()

    fun isOn(uid: String?, key: String): Boolean = forced[key] ?: allOn

    fun kindOn(uid: String?, kind: String): Boolean = isOn(uid, keyOf(kind))

    fun require(lang: String?, uid: String?, key: String) {
        if (!isOn(uid, key)) throw off(lang)
    }

    fun requireKind(lang: String?, uid: String?, kind: String) = require(lang, uid, keyOf(kind))

    companion object {
        const val MY_EXPENSES = "my_expenses"

        fun keyOf(kind: String) = "inv_$kind"

        fun off(lang: String?) = ApiException(404, Lang.tr(lang, "This feature is not available yet", "এই সুবিধা এখনো চালু হয়নি"), "feature_off") // bn-ok
    }
}
