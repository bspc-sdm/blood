package com.lifolo.common

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * The JSON request body with JavaScript-like accessors. The Node code validates with `typeof x === 'string'`,
 * `Number.isInteger(x)`, `x === true` …; these helpers give the same answers so ported checks behave the same.
 * A missing key and an explicit JSON null are different (`has` vs `isNull`), as in JS (undefined vs null).
 */
class Body(val node: ObjectNode) {
    operator fun get(key: String): JsonNode? = node.get(key)

    fun has(key: String): Boolean = node.has(key)

    /** key present with JSON null */
    fun isNull(key: String): Boolean = node.has(key) && node.get(key).isNull

    /** `b[key] == null` in JS (missing or null) */
    fun isNullish(key: String): Boolean = node.get(key)?.isNull ?: true

    /** `typeof b[key] === 'string' ? b[key] : null` */
    fun string(key: String): String? = Js.string(node.get(key))

    /** `b[key] === true` */
    fun isTrue(key: String): Boolean = node.get(key)?.let { it.isBoolean && it.booleanValue() } ?: false

    /** typeof boolean */
    fun bool(key: String): Boolean? = node.get(key)?.takeIf { it.isBoolean }?.booleanValue()

    /** `Number.isInteger(b[key]) ? b[key] : null` */
    fun int(key: String): Long? = Js.integer(node.get(key))

    /** `typeof b[key] === 'number' && Number.isFinite(b[key])` */
    fun number(key: String): Double? = Js.number(node.get(key))

    fun obj(key: String): ObjectNode? = node.get(key) as? ObjectNode

    val lang: String? get() = string("lang")

    /** Node: `String(req.body?.action || '')` */
    val action: String get() = Js.toStr(node.get("action"))

    companion object {
        fun of(raw: JsonNode?): Body = Body(raw as? ObjectNode ?: JsonNodeFactory.instance.objectNode())
    }
}

/** JavaScript value semantics on Jackson nodes. */
object Js {
    fun string(n: JsonNode?): String? = n?.takeIf { it.isTextual }?.textValue()

    fun number(n: JsonNode?): Double? = n?.takeIf { it.isNumber }?.doubleValue()?.takeIf { it.isFinite() }

    fun isInteger(n: JsonNode?): Boolean {
        if (n == null || !n.isNumber) return false
        if (n.isIntegralNumber) return true
        val d = n.doubleValue()
        return d.isFinite() && d == Math.floor(d)
    }

    fun integer(n: JsonNode?): Long? = if (isInteger(n)) n!!.asDouble().let { if (n.isIntegralNumber) n.longValue() else it.toLong() } else null

    /** JS truthiness of a JSON value */
    fun truthy(n: JsonNode?): Boolean = when {
        n == null || n.isNull || n.isMissingNode -> false
        n.isBoolean -> n.booleanValue()
        n.isNumber -> n.doubleValue().let { it != 0.0 && !it.isNaN() }
        n.isTextual -> n.textValue().isNotEmpty()
        else -> true
    }

    /** `String(v || '')` for primitives */
    fun toStr(n: JsonNode?): String = when {
        !truthy(n) -> ""
        n!!.isTextual -> n.textValue()
        n.isIntegralNumber -> n.asText()
        n.isNumber -> numberToString(n.doubleValue())
        n.isBoolean -> n.booleanValue().toString()
        n.isArray -> n.joinToString(",") { toStr(it) }
        else -> "[object Object]"
    }

    /** JS template-literal / String() of a possibly-missing value: undefined → "undefined", null → "null" */
    fun template(n: JsonNode?): String = when {
        n == null || n.isMissingNode -> "undefined"
        n.isNull -> "null"
        n.isTextual -> n.textValue()
        n.isIntegralNumber -> n.asText()
        n.isNumber -> numberToString(n.doubleValue())
        n.isBoolean -> n.booleanValue().toString()
        n.isArray -> n.joinToString(",") { if (it.isNull) "" else template(it) }
        else -> "[object Object]"
    }

    fun numberToString(d: Double): String =
        if (d.isFinite() && d == Math.floor(d) && Math.abs(d) < 1e21) d.toLong().toString() else d.toString()

    /** JS Math.round (half up towards +∞) */
    fun round(d: Double): Double = Math.floor(d + 0.5)
}
