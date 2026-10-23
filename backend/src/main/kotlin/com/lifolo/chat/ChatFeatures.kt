package com.lifolo.chat

import com.lifolo.calls.CallFeatures
import org.springframework.stereotype.Component

/**
 * Feature switches for messages (owner rule 23 Oct 2026, scratchpad flags CONTRACT / backend/docs/FEATURES.md):
 * `chat` (text messages) and `chat_media` (photos + locations, needs `chat`). Two-party: both people must have the
 * feature to open or send.
 *
 * SHIM until spring-p19's `Features.isOn / require / bothOn` is on main: delegates to the calls' shim [CallFeatures]
 * (`lifolo.feature-shim` keys; empty in production = everything OFF, `*` in the test profile), so `contact_can`'s
 * canMessage and the chat actions read the same switch. [override] lets tests switch a key per person.
 * On the rebase onto p19, [isOn] delegates to `Features.isOn` and this class stays the single place chat asks.
 */
@Component
class ChatFeatures(private val shim: CallFeatures) {
    /** tests: (uid, key) → on/off, null = the switch */
    @Volatile
    var override: ((uid: String?, key: String) -> Boolean?)? = null

    fun isOn(uid: String?, key: String): Boolean {
        val needs = if (key == MEDIA) listOf(CHAT, MEDIA) else listOf(key)
        return needs.all { k -> override?.invoke(uid, k) ?: shim.isOn(uid, k) }
    }

    fun bothOn(a: String, b: String, key: String): Boolean = isOn(a, key) && isOn(b, key)

    /** 404 `feature_off` when [key] is off for [uid] */
    fun require(lang: String?, uid: String?, key: String) {
        if (!isOn(uid, key)) throw off(lang)
    }

    fun off(lang: String?) = shim.off(lang)

    companion object {
        const val CHAT = "chat"
        const val MEDIA = "chat_media"
    }
}
