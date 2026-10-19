package com.lifolo.admin.kernel

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * catalog.js — the admin-editable pick lists (doctor/nurse/… specialties, home-care services, ambulance types,
 * blood-bank services): `catalog-defaults.json` merged with the saved lists (`catalog_items`). Codes are never
 * deleted, only hidden (someone may have picked them earlier).
 *
 * Node re-reads `config/catalog` at most every 5 minutes before a request; here the merged lists are a Spring
 * Cache entry (`catalog`, Redis, 5 min TTL) evicted on save — same freshness, no per-request hook needed.
 * `catalog_get` / `admin_catalog_set` are P6 actions (later PR); [save] is their storage.
 */
interface Catalog {
    /** catalog.js lists(): {kind: {type: [{code, en, bn, hidden?}]}} */
    fun lists(): Map<String, Map<String, List<Map<String, Any?>>>>

    /** catalog.js codes(kind, type = 'all'): every code of the list, hidden ones too (old choices must still save) */
    fun codes(kind: String, type: String = "all"): List<String> =
        lists()[kind]?.get(type)?.mapNotNull { it["code"] as? String } ?: emptyList()

    /** `config/catalog.updatedAt` (ms), 0 when never saved */
    fun updatedAt(): Long

    /** stores one whole list (order = position) and refreshes the cache */
    fun save(kind: String, type: String, items: List<Map<String, Any?>>)

    companion object {
        /** catalog.js KINDS */
        val KINDS: Map<String, List<String>> = linkedMapOf(
            "specialty" to listOf("doctor", "nurse", "nutritionist", "trainer"),
            "homeService" to listOf("doctor", "nurse"),
            "ambulanceType" to listOf("all"),
            "bloodBankService" to listOf("all"),
        )

        const val MAX_ITEMS = 80

        /** catalog.js makeCode: from the English name, lower case, `_`, numbered when taken */
        fun makeCode(en: String?, taken: Set<String>): String {
            val slug = (en ?: "").lowercase().replace(Regex("[^a-z0-9]+"), "_").replace(Regex("^_|_$"), "").take(30)
            val base = "c_${slug.ifEmpty { "item" }}"
            var code = base
            var i = 2
            while (code in taken) code = "${base}_${i++}"
            return code
        }
    }
}

/** the cached merged lists (separate bean so the cache proxy applies) */
@Component
class CatalogStore(private val jdbc: JdbcTemplate, private val mapper: ObjectMapper) {
    private val defaults: Map<String, Map<String, List<Map<String, Any?>>>> by lazy {
        val res = javaClass.getResourceAsStream("/data/catalog-defaults.json") ?: error("missing data/catalog-defaults.json")
        mapper.readValue(res, object : TypeReference<LinkedHashMap<String, LinkedHashMap<String, List<LinkedHashMap<String, Any?>>>>>() {})
    }

    @Cacheable(cacheNames = ["catalog"], key = "'lists'")
    fun merged(): LinkedHashMap<String, LinkedHashMap<String, ArrayList<LinkedHashMap<String, Any?>>>> = merge(saved())

    @CacheEvict(cacheNames = ["catalog"], allEntries = true)
    fun evict() {
    }

    /** saved lists by kind → type, in position order */
    fun saved(): Map<String, Map<String, List<Map<String, Any?>>>> {
        val out = LinkedHashMap<String, LinkedHashMap<String, ArrayList<Map<String, Any?>>>>()
        jdbc.query(
            "SELECT kind, type, code, en, bn, hidden FROM catalog_items ORDER BY kind, type, position",
            RowCallbackHandler { rs ->
                out.getOrPut(rs.getString("kind")) { LinkedHashMap() }.getOrPut(rs.getString("type")) { ArrayList() }.add(
                    item(rs.getString("code"), rs.getString("en"), rs.getString("bn"), rs.getBoolean("hidden")),
                )
            },
        )
        return out
    }

    /** catalog.js merge: a saved list wins (with its order); default codes it lacks are appended */
    fun merge(saved: Map<String, Map<String, List<Map<String, Any?>>>>?): LinkedHashMap<String, LinkedHashMap<String, ArrayList<LinkedHashMap<String, Any?>>>> {
        val out = LinkedHashMap<String, LinkedHashMap<String, ArrayList<LinkedHashMap<String, Any?>>>>()
        for ((kind, types) in defaults) {
            val t = LinkedHashMap<String, ArrayList<LinkedHashMap<String, Any?>>>()
            for ((type, list) in types) t[type] = ArrayList(list.map { LinkedHashMap(it) })
            out[kind] = t
        }
        for ((kind, types) in Catalog.KINDS) {
            for (type in types) {
                val list = saved?.get(kind)?.get(type) ?: continue
                val seen = list.map { it["code"] }.toSet()
                val base = out.getOrPut(kind) { LinkedHashMap() }[type] ?: ArrayList()
                out.getValue(kind)[type] = ArrayList(
                    list.map { item(it["code"] as String, it["en"] as String, it["bn"] as String, it["hidden"] == true) } +
                        base.filter { it["code"] !in seen },
                )
            }
        }
        return out
    }

    private fun item(code: String, en: String, bn: String, hidden: Boolean): LinkedHashMap<String, Any?> =
        linkedMapOf<String, Any?>("code" to code, "en" to en, "bn" to bn).apply { if (hidden) put("hidden", true) }
}

@Service
class CatalogService(private val store: CatalogStore, private val jdbc: JdbcTemplate) : Catalog {
    override fun lists(): Map<String, Map<String, List<Map<String, Any?>>>> = try {
        store.merged()
    } catch (e: Exception) {
        // Node: a failed load keeps serving the defaults
        store.merge(null)
    }

    override fun updatedAt(): Long =
        jdbc.queryForList("SELECT updated_at FROM catalog_meta", java.sql.Timestamp::class.java).firstOrNull()?.time ?: 0L

    @Transactional
    override fun save(kind: String, type: String, items: List<Map<String, Any?>>) {
        require(Catalog.KINDS[kind]?.contains(type) == true) { "unknown list $kind/$type" }
        jdbc.update("DELETE FROM catalog_items WHERE kind = ? AND type = ?", kind, type)
        items.forEachIndexed { i, it ->
            jdbc.update(
                "INSERT INTO catalog_items (kind, type, code, en, bn, hidden, position) VALUES (?, ?, ?, ?, ?, ?, ?)",
                kind, type, it["code"], it["en"], it["bn"], it["hidden"] == true, i,
            )
        }
        jdbc.update("INSERT INTO catalog_meta (id, updated_at) VALUES (TRUE, now()) ON CONFLICT (id) DO UPDATE SET updated_at = now()")
        store.evict()
    }
}
