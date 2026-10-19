package com.lifolo.rx

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lifolo.common.ApiException
import com.lifolo.common.DhakaTime
import com.lifolo.common.Js
import com.lifolo.common.Lang
import com.lifolo.common.Phones
import com.lifolo.common.toHex
import com.lifolo.contracts.RxDispensing
import com.lifolo.contracts.RxMedicine
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Pure rules of prescriptions.js (parsing, code, phone key, Dhaka day) and the medicine arithmetic of rxnet.js
 * (perDay, countable, needQty, medStatus, refillDate). No Spring — unit-tested directly.
 */
object RxRules {
    const val DAY_MS = 24L * 60 * 60 * 1000
    const val MAX_ASSISTANTS = 5
    const val MAX_MEDS = 25
    const val MAX_TESTS = 25
    private const val CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    /** JS `\s` (Unicode white space) */
    const val JS_SPACE = "\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"
    private val TRIM_RE = Regex("^[$JS_SPACE]+|[$JS_SPACE]+$")
    private val SPACES_RE = Regex("[$JS_SPACE]+")

    val EMAIL_RE = Regex("^[^$JS_SPACE@]{1,64}@[^$JS_SPACE@]{1,190}\\.[^$JS_SPACE@]{2,}$")
    val DOSE_RE = Regex("^(\\d{1,2}(\\.5)?|½)(\\+(\\d{1,2}(\\.5)?|½)){1,3}$")
    val TIMINGS = listOf("before", "after", "")
    private val TEST_PLACE_RE = Regex("^[A-Za-z0-9_-]{1,60}$")
    private val random = SecureRandom()

    /** String.prototype.trim */
    fun jsTrim(s: String): String = TRIM_RE.replace(s, "")

    /** `s.replace(/\s+/g, '')` */
    fun noSpaces(s: String): String = SPACES_RE.replace(s, "")

    /** prescriptions.js / rxnet.js str: `typeof v === 'string' ? v.replace(/\r\n/g,'\n').trim().slice(0, n) : ''` */
    fun str(v: JsonNode?, n: Int): String = Js.string(v)?.let { jsTrim(it.replace("\r\n", "\n")).take(n) } ?: ""

    fun str(v: String?, n: Int): String = v?.let { jsTrim(it.replace("\r\n", "\n")).take(n) } ?: ""

    /** prescriptions.js phoneKey: HMAC of the normalized number (claim matching without storing the number as key) */
    fun phoneKey(phone: String?, secret: String): String? {
        val n = Phones.normalizeBd(phone) ?: return null
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return "p_" + mac.doFinal("rx-phone:$n".toByteArray(Charsets.UTF_8)).toHex().take(40)
    }

    /** prescriptions.js newCode: 10 characters without I, O, 0, 1 */
    fun newCode(): String {
        val b = ByteArray(10)
        random.nextBytes(b)
        return b.joinToString("") { CODE_CHARS[(it.toInt() and 0xff) % CODE_CHARS.length].toString() }
    }

    /** prescriptions.js dhakaToday: UTC midnight of today's Dhaka date */
    fun dhakaToday(now: Long = System.currentTimeMillis()): Long = DhakaTime.todayUtcMidnight(now)

    fun testPlaceId(v: JsonNode?): String? = Js.string(v)?.takeIf { TEST_PLACE_RE.matches(it) }

    private fun err(lang: String?, en: String, bn: String, status: Int = 400) = ApiException(status, Lang.tr(lang, en, bn))

    // ------------------------------------------------------------------ parsing (prescriptions.js)

    data class Patient(val name: String, val age: Int?, val gender: String) {
        fun toMap(): LinkedHashMap<String, Any?> = linkedMapOf("name" to name, "age" to age, "gender" to gender)
    }

    /** parsePatient: name (≤ 80), age 0–130 or empty, gender male|female or '' */
    fun parsePatient(lang: String?, b: JsonNode?): Patient {
        val name = str(b?.get("name"), 80)
        if (name.isEmpty()) throw err(lang, "Write the patient's name", "রোগীর নাম লিখুন")
        val a = b?.get("age")
        val age: Int? = if (a == null || a.isNull || a.isMissingNode || (a.isTextual && a.textValue() == "")) {
            null
        } else {
            val n = Js.integer(a)
            if (n == null || n < 0 || n > 130) throw err(lang, "Enter a valid age", "সঠিক বয়স দিন")
            n.toInt()
        }
        val g = Js.string(b?.get("gender"))
        return Patient(name, age, if (g == "male" || g == "female") g else "")
    }

    /** parseMedicines: ≤ 25, each with a name; dose without spaces; days 0–365 (0 = continue) */
    fun parseMedicines(lang: String?, list: JsonNode?): List<RxMedicine> {
        if (list != null && !list.isMissingNode && !list.isArray) throw err(lang, "Check the medicines", "ওষুধগুলো দেখে নিন")
        val out = ArrayList<RxMedicine>()
        val items = list?.takeIf { it.isArray }?.toList().orEmpty().take(MAX_MEDS + 1)
        for (m in items) {
            if (out.size >= MAX_MEDS) throw err(lang, "Up to $MAX_MEDS medicines", "সর্বোচ্চ ${MAX_MEDS}টা ওষুধ")
            val name = str(m?.get("name"), 100)
            if (name.isEmpty()) throw err(lang, "Write the name of each medicine", "প্রতিটা ওষুধের নাম লিখুন")
            val dose = noSpaces(str(m?.get("dose"), 30))
            val doseOk = dose.isEmpty() || DOSE_RE.matches(dose) || dose.length <= 30
            if (!doseOk) throw err(lang, "Check the dose", "খাওয়ার নিয়ম দেখে নিন")
            val d = m?.get("days")
            val days: Long? = if (d == null || d.isNull || d.isMissingNode || (d.isTextual && d.textValue() == "")) 0L else Js.integer(d)
            if (days == null || days < 0 || days > 365) throw err(lang, "Days must be 0–365 (0 = continue)", "দিন ০–৩৬৫ (০ = চলবে)")
            val timing = Js.string(m?.get("timing"))?.takeIf { it in TIMINGS } ?: ""
            out.add(
                RxMedicine(
                    i = out.size, name = name, form = str(m?.get("form"), 40), strength = str(m?.get("strength"), 40),
                    dose = dose, timing = timing, days = days.toInt(), note = str(m?.get("note"), 120),
                ),
            )
        }
        return out
    }

    /** parseTests: unique non-empty names (≤ 80 chars), at most 25 */
    fun parseTests(lang: String?, list: JsonNode?): List<String> {
        if (list != null && !list.isMissingNode && !list.isArray) throw err(lang, "Check the tests", "পরীক্ষাগুলো দেখে নিন")
        val out = ArrayList<String>()
        for (t in list?.takeIf { it.isArray }?.toList().orEmpty()) {
            val s = str(t, 80)
            if (s.isEmpty()) continue
            if (out.size >= MAX_TESTS) throw err(lang, "Up to $MAX_TESTS tests", "সর্বোচ্চ ${MAX_TESTS}টা পরীক্ষা")
            if (s !in out) out.add(s)
        }
        return out
    }

    /** parseFindings: {bp, pulse, temp, weight, spo2, other} */
    fun parseFindings(f: JsonNode?): LinkedHashMap<String, Any?> {
        val o = if (f != null && (f.isObject || f.isArray)) f else JsonNodeFactory.instance.objectNode()
        return linkedMapOf(
            "bp" to str(o.get("bp"), 20), "pulse" to str(o.get("pulse"), 20), "temp" to str(o.get("temp"), 20),
            "weight" to str(o.get("weight"), 20), "spo2" to str(o.get("spo2"), 20), "other" to str(o.get("other"), 300),
        )
    }

    /** `{ ...base, ...(b.patient || {}) }` as a JSON object */
    fun mergePatient(base: Map<String, Any?>, patient: JsonNode?): ObjectNode {
        val o = JsonNodeFactory.instance.objectNode()
        base.forEach { (k, v) ->
            when (v) {
                null -> o.putNull(k)
                is Int -> o.put(k, v)
                is Long -> o.put(k, v)
                else -> o.put(k, v.toString())
            }
        }
        if (patient != null && patient.isObject && Js.truthy(patient)) o.setAll<JsonNode>(patient as ObjectNode)
        return o
    }

    // ------------------------------------------------------------------ medicine arithmetic (rxnet.js)

    /** JS `Number(s)` for a string without surrounding spaces ('' → 0, hex/octal/binary, Infinity, else NaN) */
    fun jsNumber(raw: String): Double {
        val s = jsTrim(raw)
        if (s.isEmpty()) return 0.0
        val lower = s.lowercase()
        try {
            if (lower.startsWith("0x")) return java.lang.Long.parseLong(s.substring(2), 16).toDouble()
            if (lower.startsWith("0o")) return java.lang.Long.parseLong(s.substring(2), 8).toDouble()
            if (lower.startsWith("0b")) return java.lang.Long.parseLong(s.substring(2), 2).toDouble()
        } catch (e: NumberFormatException) {
            return Double.NaN
        }
        return when (s) {
            "Infinity", "+Infinity" -> Double.POSITIVE_INFINITY
            "-Infinity" -> Double.NEGATIVE_INFINITY
            else -> if (Regex("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$").matches(s)) s.toDouble() else Double.NaN
        }
    }

    /** perDay: "1+0+1" → 2, "½+0+½" → 1; not understood → 0 */
    fun perDay(dose: String?): Double {
        val parts = noSpaces(dose ?: "").replace("½", "0.5").split("+")
        if (parts.size < 2 || parts.size > 4) return 0.0
        var sum = 0.0
        for (p in parts) {
            val n = jsNumber(p)
            if (!n.isFinite() || n < 0) return 0.0
            sum += n
        }
        return sum
    }

    /** countable: tablets/capsules (also when no form is written) */
    fun countable(form: String?): Boolean {
        val f = (form ?: "").lowercase()
        return f.isEmpty() || f.contains("tablet") || f.contains("capsule") || f == "tab." || f == "cap."
    }

    /** needQty: pieces needed — known days and dose, countable; else null */
    fun needQty(m: RxMedicine): Int? {
        val d = perDay(m.dose)
        return if (countable(m.form) && d > 0 && m.days > 0) Math.ceil(d * m.days).toInt() else null
    }

    data class MedStatus(
        val i: Int,
        val name: String,
        val form: String,
        val strength: String,
        val dose: String,
        val days: Int,
        val need: Int?,
        val got: Int,
        val left: Int?,
        val done: Boolean,
    ) {
        fun toMap(): LinkedHashMap<String, Any?> = linkedMapOf(
            "i" to i, "name" to name, "form" to form, "strength" to strength, "dose" to dose, "days" to days,
            "need" to need, "got" to got, "left" to left, "done" to done,
        )
    }

    /** medStatus: bought and left per medicine */
    fun medStatus(medicines: List<RxMedicine>, dispensed: List<RxDispensing>): List<MedStatus> {
        val got = HashMap<Int, Int>()
        for (d in dispensed) for ((i, qty) in d.items) got[i] = (got[i] ?: 0) + qty
        return medicines.mapIndexed { i, m ->
            val need = needQty(m)
            val g = got[i] ?: 0
            MedStatus(
                i, m.name, m.form, m.strength, m.dose, m.days, need, g,
                if (need == null) null else maxOf(0, need - g),
                if (need == null) g > 0 && m.days > 0 else g >= need,
            )
        }
    }

    /**
     * refillDate: 2 days before bought (countable, known dose) medicines run out — not when the whole course is
     * bought; always for "continue" medicines. At the earliest tomorrow. UTC midnight of a Dhaka date, or null.
     */
    fun refillDate(medicines: List<RxMedicine>, dispensed: List<RxDispensing>, now: Long = System.currentTimeMillis()): Long? {
        val first = HashMap<Int, Long>()
        for (d in dispensed) for ((i, _) in d.items) if (i !in first) first[i] = d.at
        var best: Long? = null
        for (s in medStatus(medicines, dispensed)) {
            val m = medicines[s.i]
            val pd = perDay(m.dose)
            if (!countable(m.form) || pd <= 0 || s.got <= 0) continue
            if (s.need != null && s.got >= s.need) continue
            val runOut = dhakaToday(first[s.i] ?: now) + Math.floor(s.got / pd).toLong() * DAY_MS
            val at = maxOf(runOut - 2 * DAY_MS, dhakaToday(now) + DAY_MS)
            if (best == null || at < best) best = at
        }
        return best
    }
}
