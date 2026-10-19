package com.lifolo.rx

import com.lifolo.common.Lang
import com.lifolo.notify.PushService
import com.lifolo.notify.PushText
import org.springframework.stereotype.Component

/**
 * The two push helpers of prescriptions.js / rxnet.js:
 *  - [one]: `push(uid, en, bn, body, data)` — one person (with a profile), title in their language; the body is plain
 *    text or {en, bn}
 *  - [patient]: careaccess.js pushPatient — the patient and their active caregivers
 * Never throw (Node logged and went on).
 */
@Component
class RxPush(private val push: PushService) {
    fun one(uid: String?, en: String, bn: String, body: String, data: Map<String, Any?>) = one(uid, en, bn, body, body, data)

    fun one(uid: String?, en: String, bn: String, bodyEn: String, bodyBn: String, data: Map<String, Any?>) {
        if (uid.isNullOrEmpty()) return
        push.pushUsers(listOf(uid)) { _, lang -> PushText(Lang.pick(lang, en, bn), Lang.pick(lang, bodyEn, bodyBn), data) }
    }

    fun patient(uid: String?, en: String, bn: String, body: String, data: Map<String, Any?>) {
        if (uid.isNullOrEmpty()) return
        push.pushPatient(uid, en, bn, body, data)
    }
}
