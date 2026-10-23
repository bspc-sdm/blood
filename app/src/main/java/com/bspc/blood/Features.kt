package com.bspc.blood

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * ফিচার সুইচ — অস্থায়ী স্টাব (app-chamber)। আসল Features (flags এজেন্ট, scratchpad/flags/CONTRACT.md) main-এ এলে এই ফাইল
 * সেটা দিয়ে বদলে যাবে: `keys`, `on(key)`, `refresh()` একই। স্টাবে সব বন্ধ (প্রোডাকশনের ডিফল্ট)।
 */
object Features {
    private val state = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val keys: StateFlow<Map<String, Boolean>> = state
    fun on(key: String): Boolean = state.value[key] == true
    suspend fun refresh() {}
}
