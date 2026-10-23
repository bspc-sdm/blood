package com.lifolo.payments

import com.lifolo.features.Features
import org.springframework.stereotype.Component

/**
 * The payment feature switches (PAYMENTS.md "Feature keys", FEATURES.md): everything ships OFF; a key counts as on only
 * when every key it needs is on too (Features.NEEDS). The online chamber fee stays under `online_chamber`.
 *
 * Whose switch (security review P13): creating a request (and a new claim on an open one) checks the BUSINESS — a
 * pilot business's patients can pay whoever they are. When a switch goes off, nothing new starts, but money already
 * sent is never stuck: confirm, reject, refund, ack, report and the lists keep working on existing requests.
 */
@Component
class PayGate(private val features: Features) {
    companion object {
        const val MANUAL = Features.PAY_MANUAL
        const val CHAMBER_ADVANCE = Features.PAY_CHAMBER_ADVANCE
        const val DELIVERY = Features.PAY_DELIVERY
        const val LAB = Features.PAY_LAB
        const val AMBULANCE = Features.PAY_AMBULANCE
        const val DUE = Features.PAY_DUE
        const val BKASH_PGW = Features.PAY_BKASH_PGW
        const val ONLINE_CHAMBER = Features.ONLINE_CHAMBER

        /** the switch of a purpose */
        fun keyOf(purpose: String): String = when (purpose) {
            "chamber_fee" -> ONLINE_CHAMBER
            "booking_advance" -> CHAMBER_ADVANCE
            "delivery" -> DELIVERY
            "lab" -> LAB
            "ambulance_fare" -> AMBULANCE
            else -> DUE
        }
    }

    fun isOn(uid: String?, key: String): Boolean = features.isOn(uid, key)

    fun require(lang: String?, uid: String?, key: String) {
        if (!isOn(uid, key)) throw Features.offError(lang)
    }

    /** any manual payment at all for this person (settings): pay_manual, or the online chamber's fee */
    fun anyOn(uid: String?): Boolean = isOn(uid, MANUAL) || isOn(uid, ONLINE_CHAMBER)

    fun requireAny(lang: String?, uid: String?) {
        if (!anyOn(uid)) throw Features.offError(lang)
    }

    fun purposeOn(uid: String?, purpose: String): Boolean = isOn(uid, keyOf(purpose))

    fun requirePurpose(lang: String?, uid: String?, purpose: String) = require(lang, uid, keyOf(purpose))
}
