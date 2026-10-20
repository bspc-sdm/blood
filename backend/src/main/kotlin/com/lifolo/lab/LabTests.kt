package com.lifolo.lab

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lifolo.common.ApiException
import com.lifolo.common.Js
import com.lifolo.common.Lang
import com.lifolo.common.Rules
import com.lifolo.rx.RxRules
import com.lifolo.rx.eachRow
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * labtests.js — report forms per test: rows (parameters), unit, normal range (may differ for women/men), text-result
 * choices. Tests are kept as the Node JSON objects ({code, name, bn, aliases, params, hidden?}) so `labtests_get`
 * answers byte-for-byte what the app caches. Pure helpers here; [LabCatalog] loads/saves the admin's list.
 */
object LabTests {
    const val MAX_TESTS = 200
    const val MAX_PARAMS = 40
    private val mapper = ObjectMapper()
    private val nf = JsonNodeFactory.instance

    val DEFAULTS: List<ObjectNode> by lazy {
        val res = LabTests::class.java.getResourceAsStream("/data/labtests-defaults.json") ?: error("missing data/labtests-defaults.json")
        (mapper.readTree(res) as ArrayNode).map { it as ObjectNode }
    }

    private val NON_ALNUM = Regex("[^a-z0-9]+")

    /** norm: lower case, only letters/digits, single spaces */
    fun norm(s: String?): String = NON_ALNUM.replace((s ?: "").lowercase(), " ").trim()

    fun code(t: JsonNode): String = t.get("code")?.asText() ?: ""

    fun name(t: JsonNode): String = t.get("name")?.asText() ?: ""

    fun hidden(t: JsonNode): Boolean = t.get("hidden")?.let { it.isBoolean && it.booleanValue() } ?: false

    fun aliases(t: JsonNode): List<String> = t.get("aliases")?.takeIf { it.isArray }?.map { it.asText() } ?: emptyList()

    /** merge: the defaults (a saved test with the same code replaces it), then saved tests that are not defaults */
    fun merge(saved: List<ObjectNode>?): List<ObjectNode> {
        val out = DEFAULTS.map { it.deepCopy() }
        if (saved == null) return out
        val byCode = LinkedHashMap<String, ObjectNode>()
        for (t in saved) byCode[code(t)] = t
        val merged = out.map { d -> byCode[code(d)] ?: d }
        val known = out.map { code(it) }.toSet()
        return merged + saved.filter { code(it) !in known }
    }

    private val PREFIX_RE = Regex("^(s|serum|blood|plasma)\\s+")
    private val SUFFIX_RE = Regex("\\s+(test|level|levels)$")

    /** match: the doctor's test name → template ("S. Creatinine", "CBC with ESR", "Urine R/E"); hidden tests never match */
    fun match(name: String?, list: List<ObjectNode>): ObjectNode? {
        val n = norm(name)
        if (n.isEmpty()) return null
        val tries = listOf(n, PREFIX_RE.replace(n, ""), SUFFIX_RE.replace(n, ""))
        for (t in list) {
            if (hidden(t)) continue
            val keys = (listOf(code(t), name(t)) + aliases(t)).map { norm(it) }
            if (tries.any { it in keys }) return t
        }
        return null
    }

    data class Range(val low: Double?, val high: Double?, val range: String)

    private fun num(n: JsonNode?): Double? = n?.takeIf { it.isNumber }?.doubleValue()

    private fun truthy(n: JsonNode?): Boolean = Js.truthy(n)

    /** rangeFor: [low, high] (null = no limit) and the text */
    fun rangeFor(p: JsonNode, gender: String): Range {
        val r = when {
            gender == "female" && truthy(p.get("f")) -> p.get("f")
            gender == "male" && truthy(p.get("m")) -> p.get("m")
            truthy(p.get("r")) -> p.get("r")
            else -> null
        }
        if (r == null) {
            if (truthy(p.get("m")) && truthy(p.get("f"))) {
                return Range(null, null, "M: ${rangeText(num(p.get("m")?.get(0)), num(p.get("m")?.get(1)))}, F: ${rangeText(num(p.get("f")?.get(0)), num(p.get("f")?.get(1)))}")
            }
            return Range(null, null, Js.string(p.get("text"))?.takeIf { it.isNotEmpty() } ?: "")
        }
        val lo = num(r.get(0))
        val hi = num(r.get(1))
        return Range(lo, hi, rangeText(lo, hi))
    }

    fun rangeText(lo: Double?, hi: Double?): String = when {
        lo != null && hi != null -> "${Js.numberToString(lo)} – ${Js.numberToString(hi)}"
        hi != null -> "< ${Js.numberToString(hi)}"
        lo != null -> "> ${Js.numberToString(lo)}"
        else -> ""
    }

    /** flagOf: a numeric result against the range → high | low | '' */
    fun flagOf(result: String?, low: Double?, high: Double?): String {
        val s = result ?: ""
        val v = RxRules.jsNumber(s.replace(",", "").trim())
        if (s.trim().isEmpty() || !v.isFinite()) return ""
        if (high != null && v > high) return "high"
        if (low != null && v < low) return "low"
        return ""
    }

    /** formFor: the lab's report form for one test (the lab's own range when it saved one) */
    fun formFor(testName: String, gender: String, labRanges: Map<String, Pair<Double?, Double?>>, list: List<ObjectNode>): Map<String, Any?> {
        val t = match(testName, list) ?: return linkedMapOf("test" to testName, "code" to null, "name" to testName, "params" to emptyList<Any>())
        val c = code(t)
        val params = (t.get("params") as? ArrayNode)?.map { p ->
            val key = p.get("key")?.asText() ?: ""
            val own = labRanges["$c.$key"]
            val rg = if (own != null) Range(own.first, own.second, rangeText(own.first, own.second)) else rangeFor(p, gender)
            linkedMapOf(
                "key" to key, "name" to (p.get("name")?.asText() ?: ""), "unit" to (Js.string(p.get("unit")) ?: ""),
                "low" to rg.low, "high" to rg.high, "range" to rg.range,
                "text" to (Js.string(p.get("text")) ?: ""),
                "options" to (p.get("options")?.takeIf { it.isArray } ?: nf.arrayNode()),
                "long" to (p.get("long")?.let { it.isBoolean && it.booleanValue() } ?: false),
            )
        } ?: emptyList()
        return linkedMapOf("test" to testName, "code" to c, "name" to name(t), "params" to params)
    }

    // ------------------------------------------------------------------ the admin's edit (cleanTest)

    /** `Number(v)` of a JSON value: null/''/missing → null; not finite → NaN */
    private fun numOf(v: JsonNode?): Double? = when {
        v == null || v.isNull || v.isMissingNode -> null
        v.isTextual && v.textValue() == "" -> null
        v.isNumber -> v.doubleValue()
        v.isTextual -> RxRules.jsNumber(v.textValue()).let { if (it.isFinite()) it else Double.NaN }
        v.isBoolean -> if (v.booleanValue()) 1.0 else 0.0
        else -> Double.NaN
    }

    /** pair: [low, high] — null when not a 2-array or both empty; NaN-marker (null + error flag) when invalid */
    private fun pair(v: JsonNode?): Pair<Boolean, List<Double?>?> {
        if (v == null || !v.isArray || v.size() != 2) return true to null
        val a = numOf(v.get(0))
        val b = numOf(v.get(1))
        if (a?.isNaN() == true || b?.isNaN() == true) return false to null
        if (a == null && b == null) return true to null
        if (a != null && b != null && a > b) return false to null
        return true to listOf(a, b)
    }

    private fun numNode(d: Double?): JsonNode =
        when {
            d == null -> nf.nullNode()
            d == Math.floor(d) && Math.abs(d) < 1e15 -> nf.numberNode(d.toLong())
            else -> nf.numberNode(d)
        }

    private fun err(lang: String?, en: String, bn: String) = ApiException(400, Lang.tr(lang, en, bn))

    /** `String(v || '')` */
    private fun s(v: JsonNode?): String = Js.toStr(v)

    /** `String(v)` (null → "null") */
    private fun sAny(v: JsonNode?): String = Js.template(v)

    private val CODE_RE = Regex("^[a-z0-9_]{1,40}$")
    private val KEY_RE = Regex("^[a-z0-9_]{1,30}$")
    private val WS = Regex("\\s+")

    /** cleanTest: validates one test from the admin panel (code kept or made from the name) */
    fun cleanTest(lang: String?, t: JsonNode, taken: Set<String>): ObjectNode {
        val name = Rules.cleanText(s(t.get("name")), 80) ?: throw err(lang, "Write the test name", "পরীক্ষার নাম লিখুন")
        val bn = Rules.cleanText(s(t.get("bn")), 80) ?: ""
        var code = Js.string(t.get("code"))?.takeIf { CODE_RE.matches(it) }
        if (code == null) {
            val base = WS.replace(norm(name), "_").take(30).ifEmpty { "test" }
            var c = base
            var i = 2
            while (c in taken) c = "${base}_${i++}"
            code = c
        }
        val rawAliases = t.get("aliases")
        val aliasList = if (rawAliases != null && rawAliases.isArray) rawAliases.map { sAny(it) } else s(rawAliases).split(",")
        val aliases = aliasList.map { RxRules.jsTrim(it).lowercase().take(60) }.filter { it.isNotEmpty() }.distinct().take(20)
        val raw = t.get("params")?.takeIf { it.isArray }?.toList() ?: emptyList()
        if (raw.isEmpty() || raw.size > MAX_PARAMS) throw err(lang, "1–$MAX_PARAMS rows", "১–${MAX_PARAMS}টা ঘর")
        val keys = HashSet<String>()
        val params = nf.arrayNode()
        raw.forEachIndexed { i, p ->
            val pn = Rules.cleanText(s(p?.get("name")), 60) ?: throw err(lang, "Row ${i + 1}: write the name", "ঘর ${i + 1}: নাম লিখুন")
            var key = Js.string(p.get("key"))?.takeIf { KEY_RE.matches(it) } ?: WS.replace(norm(pn), "_").take(24).ifEmpty { "p${i + 1}" }
            while (key in keys) key = "${key}_${i + 1}"
            keys.add(key)
            val out = nf.objectNode()
            out.put("key", key)
            out.put("name", pn)
            val unit = RxRules.jsTrim(s(p.get("unit"))).take(20)
            if (unit.isNotEmpty()) out.put("unit", unit)
            for (k in listOf("r", "m", "f")) {
                val (ok, v) = pair(p.get(k))
                if (!ok) throw err(lang, "$pn: check the normal range", "$pn: স্বাভাবিক মাত্রা দেখে নিন")
                if (v != null) out.set<JsonNode>(k, nf.arrayNode().add(numNode(v[0])).add(numNode(v[1])))
            }
            val text = RxRules.jsTrim(s(p.get("text"))).take(40)
            if (text.isNotEmpty()) out.put("text", text)
            val rawOpt = p.get("options")
            val optList = if (rawOpt != null && rawOpt.isArray) rawOpt.map { sAny(it) } else s(rawOpt).split(",")
            val options = optList.map { RxRules.jsTrim(it).take(30) }.filter { it.isNotEmpty() }.take(10)
            if (options.isNotEmpty()) out.set<JsonNode>("options", nf.arrayNode().apply { options.forEach { add(it) } })
            if (p.get("long")?.let { it.isBoolean && it.booleanValue() } == true) out.put("long", true)
            params.add(out)
        }
        val o = nf.objectNode()
        o.put("code", code)
        o.put("name", name)
        if (bn.isNotEmpty()) o.put("bn", bn)
        o.set<JsonNode>("aliases", nf.arrayNode().apply { aliases.forEach { add(it) } })
        o.set<JsonNode>("params", params)
        if (t.get("hidden")?.let { it.isBoolean && it.booleanValue() } == true) o.put("hidden", true)
        return o
    }
}

/**
 * config/labTests → `lab_test_templates` (one row per test, `position` keeps the order; the whole merged list is
 * stored like Node). Read on every use — Node kept it 5 min per instance; reading the ~35 rows is cheap and an admin
 * edit is visible on every server at once.
 */
@Service
class LabCatalog(private val jdbc: JdbcTemplate, private val mapper: ObjectMapper) {
    /** load(): the merged list (defaults when nothing was saved) */
    fun tests(): List<ObjectNode> {
        val saved = ArrayList<ObjectNode>()
        try {
            jdbc.eachRow("SELECT code, name, bn, aliases, params::text AS params, hidden FROM lab_test_templates ORDER BY position, code") { rs ->
                val o = mapper.createObjectNode()
                o.put("code", rs.getString("code"))
                o.put("name", rs.getString("name"))
                rs.getString("bn")?.let { o.put("bn", it) }
                val al = mapper.createArrayNode()
                @Suppress("UNCHECKED_CAST")
                ((rs.getArray("aliases")?.array as? Array<Any?>) ?: emptyArray()).forEach { al.add(it.toString()) }
                o.set<JsonNode>("aliases", al)
                o.set<JsonNode>("params", mapper.readTree(rs.getString("params")))
                if (rs.getBoolean("hidden")) o.put("hidden", true)
                saved.add(o)
            }
        } catch (e: Exception) {
            org.slf4j.LoggerFactory.getLogger(javaClass).error("labtests load failed: {}", e.message)
            return LabTests.merge(null)
        }
        return LabTests.merge(saved.ifEmpty { null })
    }

    /** admin_labtests_set: stores the whole list */
    @Transactional
    fun save(list: List<ObjectNode>) {
        jdbc.update("DELETE FROM lab_test_templates")
        list.forEachIndexed { i, t ->
            jdbc.update(
                "INSERT INTO lab_test_templates (code, name, bn, aliases, params, hidden, position, updated_at) VALUES (?, ?, ?, ?::text[], ?::jsonb, ?, ?, now())",
                LabTests.code(t), LabTests.name(t), Js.string(t.get("bn")),
                com.lifolo.rx.P3Sql.pgArray(LabTests.aliases(t)), mapper.writeValueAsString(t.get("params") ?: mapper.createArrayNode()),
                LabTests.hidden(t), i,
            )
        }
    }
}
