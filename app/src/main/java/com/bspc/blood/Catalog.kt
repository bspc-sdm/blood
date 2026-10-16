package com.bspc.blood

import android.content.Context
import org.json.JSONObject

/**
 * বাছাইয়ের তালিকা (বিশেষজ্ঞতা, বাসায় সেবা, অ্যাম্বুলেন্সের ধরন, ব্লাড ব্যাংকের সেবা) — অ্যাডমিন প্যানেল থেকে বদলায়।
 * দিনে একবার সার্ভার থেকে এনে ফোনে রাখা; না পেলে অ্যাপের ভেতরের তালিকা। লুকানোগুলো বাছাইয়ে আসে না, নাম দেখাতে কাজে লাগে।
 */
object Catalog {
    data class Item(val code: String, val en: String, val bn: String, val hidden: Boolean) {
        val label: String get() = if (isBangla) bn else en
    }

    private const val PREFS = "catalog"
    private const val MAX_AGE_MS = 12 * 60 * 60 * 1000L

    @Volatile private var lists: JSONObject? = null

    fun init(context: Context) {
        if (lists != null) return
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("lists", null) ?: return
        lists = runCatching { JSONObject(raw) }.getOrNull()
    }

    suspend fun refresh(context: Context, force: Boolean = false) {
        init(context)
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!force && lists != null && System.currentTimeMillis() - p.getLong("at", 0) < MAX_AGE_MS) return
        val o = ServerApi.post("/api/community", JSONObject().put("action", "catalog_get")).optJSONObject("lists") ?: return
        lists = o
        p.edit().putString("lists", o.toString()).putLong("at", System.currentTimeMillis()).apply()
    }

    private fun raw(kind: String, type: String): List<Item>? {
        val a = lists?.optJSONObject(kind)?.optJSONArray(type) ?: return null
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
            Item(it.optString("code"), it.optString("en"), it.optString("bn"), it.optBoolean("hidden"))
        }.filter { it.code.isNotBlank() }
    }

    /** বাছাইয়ের জন্য কোড (লুকানো বাদ); তালিকা না থাকলে null */
    fun visible(kind: String, type: String = "all"): List<String>? = raw(kind, type)?.filter { !it.hidden }?.map { it.code }?.takeIf { it.isNotEmpty() }

    /** নাম (যেকোনো ধরনের তালিকা থেকে) */
    fun label(kind: String, code: String): String? {
        val k = lists?.optJSONObject(kind) ?: return null
        for (t in k.keys()) raw(kind, t)?.firstOrNull { it.code == code }?.let { return it.label.ifBlank { null } }
        return null
    }
}
