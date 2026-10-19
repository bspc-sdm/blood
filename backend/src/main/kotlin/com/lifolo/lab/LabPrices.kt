package com.lifolo.lab

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lifolo.common.ApiException
import com.lifolo.common.Js
import com.lifolo.common.Lang
import com.lifolo.common.Phones
import com.lifolo.rx.RxRules
import java.text.Normalizer

/** labPrices/{labUid}.items[] */
data class PriceItem(val code: String?, val name: String, val price: Int, val tat: String, val on: Boolean) {
    fun toMap(hidden: Boolean = false): LinkedHashMap<String, Any?> =
        linkedMapOf<String, Any?>("code" to code, "name" to name, "price" to price, "tat" to tat, "on" to on).apply { if (hidden) put("hidden", true) }
}

/** labPrices/{labUid} */
data class PriceList(val items: List<PriceItem>, val discountPct: Int?, val homeFee: Int?, val updatedAt: Long)

/** one test of an estimate / order price snapshot */
data class EstItem(val test: String, val name: String, val price: Int, val tat: String) {
    fun toMap(): LinkedHashMap<String, Any?> = linkedMapOf("test" to test, "name" to name, "price" to price, "tat" to tat)
}

data class Estimate(
    val items: List<EstItem>,
    val missing: List<String>,
    val have: Int,
    val total: Int,
    val subtotal: Int?,
    val discountPct: Int?,
    val estimate: Int?,
    val homeFee: Int?,
    val tat: String,
)

/** labOrders.prices — what the patient was shown when sending (kept even if the lab changes prices later) */
data class PriceSnap(val items: List<EstItem>, val missing: List<String>, val subtotal: Int?, val discountPct: Int?, val estimate: Int, val at: Long) {
    fun toMap(): LinkedHashMap<String, Any?> = linkedMapOf(
        "items" to items.map { it.toMap() }, "missing" to missing, "subtotal" to subtotal, "discountPct" to discountPct,
        "estimate" to estimate, "at" to at,
    )
}

/** labOrders.home — home sample collection */
data class LabHome(
    val status: String,
    val date: Long?,
    val slot: String?,
    val note: String?,
    val fee: Int?,
    val address: String?,
    val phone: String?,
    val requestedAt: Long?,
    val visitDate: Long? = null,
    val time: String? = null,
    val at: Long? = null,
    val confirmNote: String? = null,
    val confirmedAt: Long? = null,
    val declineNote: String? = null,
    val declinedAt: Long? = null,
)

/**
 * labprices.js — the pure parts: price lists (validation, owner/public views, summary, estimate, snapshot),
 * home collection (validation, the lab's visit time, texts for pushes), shortAddress.
 */
object LabPrices {
    const val MAX_ITEMS = 200
    const val MAX_PRICE = 200000
    const val MAX_HOME_FEE = 5000
    const val DAY = 24L * 60 * 60 * 1000
    const val MIN = 60L * 1000
    private const val SIX_HOURS = 6L * 3600 * 1000

    /** slot → [start, end] hour (Dhaka) */
    val SLOTS: Map<String, Pair<Int, Int>> = linkedMapOf("early" to (7 to 9), "morning" to (9 to 12), "afternoon" to (15 to 18))

    private val SLOT_WORDS = mapOf(
        "early" to ("7–9 AM" to "সকাল ৭–৯"),
        "morning" to ("9 AM–12 PM" to "সকাল ৯–১২"),
        "afternoon" to ("3–6 PM" to "বিকেল ৩–৬"),
    )

    private const val BN_DIGITS = "০১২৩৪৫৬৭৮৯"

    private fun bnToAscii(s: String): String = s.map { c -> val i = BN_DIGITS.indexOf(c); if (i >= 0) ('0' + i) else c }.joinToString("")

    private fun err(lang: String?, en: String, bn: String, status: Int = 400) = ApiException(status, Lang.tr(lang, en, bn))

    private val TOINT_STRIP = Regex("[,${RxRules.JS_SPACE}৳]")
    private val DIGITS_1_7 = Regex("^\\d{1,7}$")

    /** toInt: number or numeric text (Bangla digits, commas, ৳) → whole number; empty → null; invalid → NaN */
    fun toInt(v: JsonNode?): Double? {
        if (v == null || v.isNull || v.isMissingNode) return null
        if (v.isTextual && v.textValue() == "") return null
        if (v.isNumber) return if (Js.isInteger(v)) v.doubleValue() else Double.NaN
        if (!v.isTextual) return Double.NaN
        val t = TOINT_STRIP.replace(bnToAscii(v.textValue()), "")
        if (t.isEmpty()) return null
        return if (DIGITS_1_7.matches(t)) t.toDouble() else Double.NaN
    }

    private val ZW = Regex("[\\u200c\\u200d]")
    private val NOT_LNM = Regex("[^\\p{L}\\p{N}\\p{M}]+")

    /** nameKey: own test names compared with Bangla letters/digits kept (empty never matches) */
    fun nameKey(v: String?): String {
        val s = Normalizer.normalize(v ?: "", Normalizer.Form.NFC).lowercase()
        return NOT_LNM.replace(ZW.replace(bnToAscii(s), ""), " ").trim()
    }

    fun hiddenCodes(list: List<ObjectNode>): Set<String> = list.filter { LabTests.hidden(it) }.map { LabTests.code(it) }.toSet()

    /** visibleItems: switched on and not a hidden template */
    fun visibleItems(x: PriceList?, hidden: Set<String>): List<PriceItem> =
        x?.items.orEmpty().filter { it.on && !(it.code != null && it.code in hidden) }

    data class Cleaned(val items: List<PriceItem>, val kept: List<String>)

    /** cleanItems: the owner's list (template code or own name, price, tat, on); a test only once */
    fun cleanItems(lang: String?, raw: List<JsonNode>, list: List<ObjectNode>): Cleaned {
        val out = ArrayList<PriceItem>()
        val kept = ArrayList<String>()
        val seen = HashSet<String>()
        raw.forEachIndexed { i, r ->
            var code: String? = null
            var name: String
            val asked = Js.string(r?.get("code"))?.takeIf { it.isNotEmpty() }
            val byCode = asked?.let { a -> list.firstOrNull { LabTests.code(it) == a } }
            if (byCode != null && !LabTests.hidden(byCode)) {
                code = LabTests.code(byCode)
                name = LabTests.name(byCode)
            } else {
                name = RxRules.str(r?.get("name"), 80).ifEmpty { if (byCode != null) LabTests.name(byCode) else "" }
                if (asked != null && name.isEmpty()) {
                    throw err(lang, "Row ${i + 1}: this test is not in the list", "সারি ${Lang.bnDigits((i + 1).toString())}: পরীক্ষাটা তালিকায় নেই")
                }
                if (name.isEmpty() || nameKey(name).isEmpty()) {
                    throw err(lang, "Row ${i + 1}: write the test name", "সারি ${Lang.bnDigits((i + 1).toString())}: পরীক্ষার নাম লিখুন")
                }
                val t = LabTests.match(name, list)
                if (t != null) {
                    code = LabTests.code(t)
                    name = LabTests.name(t)
                } else if (asked != null) {
                    kept.add(name)
                }
            }
            val price = toInt(r?.get("price")) ?: throw err(lang, "$name: write the price", "$name: দাম লিখুন")
            if (price.isNaN() || price < 1 || price > MAX_PRICE) throw err(lang, "$name: check the price", "$name: দাম দেখে নিন")
            val k = if (code != null) "c:$code" else "n:${nameKey(name)}"
            if (!seen.add(k)) throw err(lang, "$name is in the list twice", "$name তালিকায় দুবার আছে")
            val on = r?.get("on")?.let { !(it.isBoolean && !it.booleanValue()) } ?: true
            out.add(PriceItem(code, name, price.toInt(), RxRules.str(r?.get("tat"), 30), on))
        }
        return Cleaned(out, kept)
    }

    /** ownerView: every row (hidden templates marked), discount, home fee, homeCollection of the lab */
    fun ownerView(x: PriceList?, homeCollection: Boolean, hidden: Set<String>): LinkedHashMap<String, Any?> = linkedMapOf(
        "items" to x?.items.orEmpty().map { it.toMap(it.code != null && it.code in hidden) },
        "discountPct" to x?.discountPct?.takeIf { it != 0 },
        "homeFee" to x?.homeFee,
        "updatedAt" to (x?.updatedAt ?: 0L),
        "homeCollection" to homeCollection,
    )

    /** publicView: switched-on rows only; home fee only when the lab collects from home; null without a list */
    fun publicView(x: PriceList?, homeCollection: Boolean, hidden: Set<String>): LinkedHashMap<String, Any?>? {
        val on = visibleItems(x, hidden)
        if (on.isEmpty()) return null
        return linkedMapOf(
            "items" to on.map { linkedMapOf("code" to it.code, "name" to it.name, "price" to it.price, "tat" to it.tat) },
            "discountPct" to x!!.discountPct?.takeIf { it != 0 },
            "homeFee" to if (homeCollection) x.homeFee else null,
            "updatedAt" to x.updatedAt,
        )
    }

    /** publicSummary: count, lowest price, discount, home fee (the area list) */
    fun publicSummary(x: PriceList?, homeCollection: Boolean, hidden: Set<String>): LinkedHashMap<String, Any?>? {
        val v = publicView(x, homeCollection, hidden) ?: return null
        @Suppress("UNCHECKED_CAST")
        val items = v["items"] as List<Map<String, Any?>>
        return linkedMapOf(
            "count" to items.size, "from" to items.minOf { it["price"] as Int }, "discountPct" to v["discountPct"],
            "homeFee" to v["homeFee"], "updatedAt" to v["updatedAt"],
        )
    }

    /** testKey: the template code, else the name (Bangla kept) */
    private fun testKey(t: String, list: List<ObjectNode>): Triple<ObjectNode?, String, String> {
        val m = LabTests.match(t, list)
        val n = nameKey(t)
        return Triple(m, n, if (m != null) "c:${LabTests.code(m)}" else "n:$n")
    }

    /** countTests: distinct tests (two names of one template count once) */
    fun countTests(tests: List<String>, list: List<ObjectNode>): Int = tests.map { testKey(it, list).third }.toSet().size

    /**
     * estimate: which of the prescription's tests this lab does and the cost. null without a (visible) list;
     * none matched → have 0, subtotal/estimate null.
     */
    fun estimate(tests: List<String>, x: PriceList?, homeCollection: Boolean, list: List<ObjectNode>): Estimate? {
        val hidden = hiddenCodes(list)
        val on = visibleItems(x, hidden)
        if (on.isEmpty()) return null
        val items = ArrayList<EstItem>()
        val missing = ArrayList<String>()
        val seen = HashSet<String>()
        for (t in tests) {
            val (m, n, k) = testKey(t, list)
            if (!seen.add(k)) continue
            val it = on.firstOrNull { i -> (m != null && i.code == LabTests.code(m)) || (i.code == null && n.isNotEmpty() && nameKey(i.name) == n) }
            if (it != null) items.add(EstItem(t, it.name, it.price, it.tat)) else missing.add(t)
        }
        val homeFee = if (homeCollection) x!!.homeFee else null
        val d = x!!.discountPct ?: 0
        if (items.isEmpty()) return Estimate(items, missing, 0, seen.size, null, d.takeIf { it != 0 }, null, homeFee, "")
        val subtotal = items.sumOf { it.price }
        return Estimate(
            items, missing, items.size, seen.size, subtotal, d.takeIf { it != 0 },
            Js.round(subtotal.toDouble() * (100 - d) / 100).toInt(), homeFee,
            items.map { it.tat }.filter { it.isNotEmpty() }.distinct().joinToString(" / "),
        )
    }

    /** snapshot: nothing when not a single test matched */
    fun snapshot(e: Estimate?, now: Long = System.currentTimeMillis()): PriceSnap? {
        if (e == null || e.items.isEmpty() || e.estimate == null) return null
        return PriceSnap(e.items, e.missing, e.subtotal, e.discountPct, e.estimate, now)
    }

    // ------------------------------------------------------------------ home collection

    /** Dhaka minutes of the day */
    fun dhakaMinutes(now: Long): Long = Math.floorMod(now + SIX_HOURS, DAY) / MIN

    /** cleanHome: the patient's request (null when not asked) */
    fun cleanHome(lang: String?, h: JsonNode?, labHomeCollection: Boolean, now: Long = System.currentTimeMillis()): LabHome? {
        if (h == null || !(h.isObject || h.isArray)) return null
        if (!labHomeCollection) throw err(lang, "This lab doesn't collect samples from home", "এই প্যাথলজি বাসা থেকে নমুনা নেয় না", 409)
        val address = RxRules.str(h.get("address"), 200)
        if (address.isEmpty()) throw err(lang, "Write the home address", "বাসার ঠিকানা লিখুন")
        val ph = Phones.normalizeBd(Js.toStr(h.get("phone"))) ?: throw err(lang, "Enter a mobile number", "মোবাইল নম্বর দিন")
        val dayNode = h.get("day")
        val day = if (dayNode != null && dayNode.isNumber && Js.isInteger(dayNode)) Js.integer(dayNode) else null
        if (day == null || day !in 0L..2L) throw err(lang, "Choose the day", "কবে আসবে বেছে নিন")
        val slot = Js.string(h.get("slot"))?.takeIf { it in SLOTS } ?: throw err(lang, "Choose a time", "কোন সময়ে আসবে বেছে নিন")
        if (day == 0L && dhakaMinutes(now) >= SLOTS.getValue(slot).second * 60) {
            throw err(lang, "This time has passed today — choose another", "আজকের এই সময় পেরিয়ে গেছে — অন্য সময় বেছে নিন")
        }
        return LabHome(
            status = "requested", date = RxRules.dhakaToday(now) + day * DAY, slot = slot, note = RxRules.str(h.get("note"), 120),
            fee = null, address = address, phone = Phones.localBd(ph), requestedAt = now,
        )
    }

    data class Visit(val visitDate: Long, val time: String, val at: Long)

    private val TIME_RE = Regex("^([01]\\d|2[0-3]):([0-5]\\d)$")

    /** cleanVisit: the lab's day (today … +3, UTC midnight) and "HH:MM" (05:00–21:59), not in the past */
    fun cleanVisit(lang: String?, date: JsonNode?, time: JsonNode?, now: Long = System.currentTimeMillis()): Visit {
        val today = RxRules.dhakaToday(now)
        val d = if (date != null && Js.isInteger(date)) Js.integer(date) else null
        val ok = d != null && (d - today) % DAY == 0L && (d - today) / DAY in 0L..3L
        if (!ok) throw err(lang, "Choose the day", "দিন বেছে নিন")
        val m = TIME_RE.matchEntire(Js.toStr(time))
        if (m == null || m.groupValues[1].toInt() < 5 || m.groupValues[1].toInt() > 21) throw err(lang, "Choose the time", "সময় বেছে নিন")
        val mins = m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
        val at = d!! + mins * MIN - SIX_HOURS
        if (at < now - 30 * MIN) throw err(lang, "This time has passed", "এই সময় পেরিয়ে গেছে")
        return Visit(d, "${m.groupValues[1]}:${m.groupValues[2]}", at)
    }

    private fun utcDayOfMonth(ms: Long): Int = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC).dayOfMonth

    private fun utcMonth(ms: Long): Int = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC).monthValue

    private fun bn(n: Int): String = Lang.bnDigits(n.toString())

    /** visitWords: "কাল সকাল ৮টায়" / "tomorrow at 8:00 AM" */
    fun visitWords(date: Long, time: String, now: Long = System.currentTimeMillis()): Pair<String, String> {
        val k = Js.round((date - RxRules.dhakaToday(now)).toDouble() / DAY).toLong()
        val (h, mm) = time.split(":").map { it.toInt() }
        val part = when {
            h < 5 -> "রাত"
            h < 12 -> "সকাল"
            h < 15 -> "দুপুর"
            h < 18 -> "বিকাল"
            h < 20 -> "সন্ধ্যা"
            else -> "রাত"
        }
        val h12 = if (h % 12 == 0) 12 else h % 12
        val dayBn = when (k) {
            0L -> "আজ"
            1L -> "কাল"
            2L -> "পরশু"
            else -> "${bn(utcDayOfMonth(date))} তারিখ"
        }
        val dayEn = when (k) {
            0L -> "today"
            1L -> "tomorrow"
            else -> "on ${utcDayOfMonth(date)}/${utcMonth(date)}"
        }
        val bnText = "$dayBn $part ${bn(h12)}টা" + if (mm != 0) " ${bn(mm)} মিনিটে" else "য়"
        val enText = "$dayEn at $h12:${mm.toString().padStart(2, '0')} ${if (h < 12) "AM" else "PM"}"
        return enText to bnText
    }

    /** homeExpired: the asked day/slot is over and the lab has not answered */
    fun homeExpired(h: LabHome?, now: Long = System.currentTimeMillis()): Boolean {
        if (h == null || h.status != "requested") return false
        val s = SLOTS[h.slot] ?: return false
        val date = h.date ?: return false
        return now >= date + s.second * 60 * MIN - SIX_HOURS
    }

    /** homeView: [contact] = address/phone may be shown */
    fun homeView(h: LabHome?, contact: Boolean, now: Long = System.currentTimeMillis()): LinkedHashMap<String, Any?>? {
        if (h == null) return null
        return linkedMapOf<String, Any?>(
            "status" to if (homeExpired(h, now)) "expired" else h.status,
            "date" to (h.date ?: 0L),
            "slot" to (h.slot ?: ""),
            "note" to (h.note ?: ""),
            "fee" to h.fee,
            "visitDate" to (h.visitDate ?: 0L),
            "time" to (h.time ?: ""),
            "at" to (h.at ?: 0L),
            "confirmNote" to (h.confirmNote ?: ""),
            "declineNote" to (h.declineNote ?: ""),
        ).apply {
            if (contact) {
                put("address", h.address ?: "")
                put("phone", h.phone ?: "")
            }
        }
    }

    private val ADDR_SPLIT = Regex("[,،।\\n]+")
    private val HAS_DIGIT = Regex("[0-9০-৯]")
    private val WORDS = Regex("[${RxRules.JS_SPACE}]+")

    /** shortAddress: the area name without house/road numbers, then "…" */
    fun shortAddress(a: String?): String {
        val parts = ADDR_SPLIT.split(a ?: "").map { RxRules.jsTrim(it) }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return ""
        val area = parts.firstOrNull { !HAS_DIGIT.containsMatchIn(it) } ?: parts[0]
        var out = ""
        for (w in WORDS.split(area)) {
            if (out.isNotEmpty() && "$out $w".length > 18) break
            out = if (out.isNotEmpty()) "$out $w" else w
        }
        return "${out.take(24)}…"
    }

    /** homePushLine: "বাসা থেকে নমুনা · কাল সকাল ৭–৯ · চরপাড়া…" (no phone) */
    fun homePushLine(h: LabHome?, now: Long = System.currentTimeMillis()): Pair<String, String>? {
        if (h == null) return null
        val date = h.date ?: 0L
        val k = Js.round((date - RxRules.dhakaToday(now)).toDouble() / DAY).toLong()
        val dayBn = when (k) {
            0L -> "আজ"
            1L -> "কাল"
            2L -> "পরশু"
            else -> "${bn(utcDayOfMonth(date))} তারিখ"
        }
        val dayEn = when (k) {
            0L -> "today"
            1L -> "tomorrow"
            2L -> "day after tomorrow"
            else -> "${utcDayOfMonth(date)}/${utcMonth(date)}"
        }
        val (slotEn, slotBn) = SLOT_WORDS[h.slot] ?: ("" to "")
        val addr = shortAddress(h.address)
        return listOf("Sample from home", RxRules.jsTrim("$dayEn $slotEn"), addr).filter { it.isNotEmpty() }.joinToString(" · ") to
            listOf("বাসা থেকে নমুনা", RxRules.jsTrim("$dayBn $slotBn"), addr).filter { it.isNotEmpty() }.joinToString(" · ")
    }
}
