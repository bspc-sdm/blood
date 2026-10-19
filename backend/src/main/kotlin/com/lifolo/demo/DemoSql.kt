package com.lifolo.demo

import com.lifolo.admin.kernel.DemoSeed
import com.lifolo.admin.kernel.ListingStats
import com.lifolo.common.DhakaTime
import com.lifolo.common.Geo
import com.lifolo.common.Js
import com.lifolo.common.Places
import com.lifolo.common.Sql
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp
import kotlin.math.max
import kotlin.math.sin

/** a `?::text[]` / `?::smallint[]` / `?::int[]` parameter */
class PgArr(val values: List<Any?>, val type: String) {
    fun literal(): String = values.joinToString(",", "{", "}") { v ->
        when (v) {
            null -> "NULL"
            is Number -> v.toString()
            else -> "\"" + v.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
    }
}

/** a `?::jsonb` parameter */
class PgJson(val json: String)

/**
 * Small SQL helpers for the demo seed parts (the Node `ctx.set(path, data)` writes become rows). Values: String,
 * numbers, Boolean, [Timestamp], [java.sql.Date], [PgArr], [PgJson] or null.
 */
@Component
class DemoSql(val jdbc: JdbcTemplate) {
    fun ts(ms: Long?): Timestamp? = ms?.let { Timestamp(it) }

    fun texts(list: List<String>) = PgArr(list, "text")

    fun ints(list: List<Int>, type: String = "int") = PgArr(list, type)

    private fun param(v: Any?): Pair<String, Any?> = when (v) {
        is PgArr -> "?::${v.type}[]" to v.literal()
        is PgJson -> "?::jsonb" to v.json
        else -> "?" to v
    }

    /** INSERT one row; [conflict] e.g. "(uid) DO NOTHING" or "(uid) DO UPDATE SET …" */
    fun insert(table: String, row: Map<String, Any?>, conflict: String? = null): Int {
        val cols = row.keys.toList()
        val ps = cols.map { param(row[it]) }
        val sql = "INSERT INTO $table (${cols.joinToString(", ")}) VALUES (${ps.joinToString(", ") { it.first }})" +
            (if (conflict != null) " ON CONFLICT $conflict" else "")
        return jdbc.update(sql, *ps.map { it.second }.toTypedArray())
    }

    /** INSERT, or UPDATE the given columns when the key exists (Firestore `set(…, {merge: true})`) */
    fun upsert(table: String, keyCols: List<String>, row: Map<String, Any?>): Int {
        val rest = row.keys.filter { it !in keyCols }
        val action = if (rest.isEmpty()) "DO NOTHING" else "DO UPDATE SET ${rest.joinToString(", ") { "$it = EXCLUDED.$it" }}"
        return insert(table, row, "(${keyCols.joinToString(", ")}) $action")
    }

    /** demo/services.js profile(): name, phone, language and place (district centre, 2 decimals) only when missing */
    fun profileDefaults(uid: String, name: String, phone: String, district: String) {
        val lat = Js.round(DemoSeed.CENTER_LAT * 100) / 100
        val lng = Js.round(DemoSeed.CENTER_LNG * 100) / 100
        jdbc.update("INSERT INTO user_profiles (uid) VALUES (?) ON CONFLICT (uid) DO NOTHING", uid)
        jdbc.update(
            """
            UPDATE user_profiles SET name = coalesce(nullif(name, ''), ?), phone = coalesce(nullif(phone, ''), ?), lang = coalesce(lang, 'bn'),
              lat = CASE WHEN coalesce(place_name, '') = '' THEN ? ELSE lat END,
              lng = CASE WHEN coalesce(place_name, '') = '' THEN ? ELSE lng END,
              geohash = CASE WHEN coalesce(place_name, '') = '' THEN ? ELSE geohash END,
              location_source = CASE WHEN coalesce(place_name, '') = '' THEN 'district' ELSE location_source END,
              place_name = CASE WHEN coalesce(place_name, '') = '' THEN ? ELSE place_name END
            WHERE uid = ?
            """.trimIndent(),
            name, phone, lat, lng, Geo.hash(lat, lng), district, uid,
        )
    }

    /** verify-document photos for a listing/badge: the types and their time (Node `docsHave`) */
    fun docs(kind: String, id: String, owner: String, types: List<String>, at: Long, data: String): List<String> {
        for (t in types) {
            insert(
                "verify_docs",
                linkedMapOf("kind" to kind, "listing_id" to id, "type" to t, "owner_uid" to owner, "data" to data, "size" to data.length, "at" to ts(at)),
                "(kind, listing_id, type) DO UPDATE SET owner_uid = EXCLUDED.owner_uid, data = EXCLUDED.data, size = EXCLUDED.size, at = EXCLUDED.at",
            )
        }
        return types
    }

    /**
     * Call statistics like demo/services.js `dailyCalls` + `{d, total: c.total + extra, callers}` and `recent[]`
     * (`who` as given — Node writes fixed demo keys or sellerstats raterKey).
     */
    fun stats(kind: String, id: String, now: Long, perDay: Int, extra: Int, callersShare: Double, recent: List<Recent> = emptyList()) {
        val (d, total) = dailyCalls(perDay, id.length)
        jdbc.update("DELETE FROM listing_stats WHERE kind = ? AND listing_id = ?", kind, id)
        insert("listing_stats", linkedMapOf("kind" to kind, "listing_id" to id, "total" to total + extra, "callers" to Js.round(total * callersShare).toInt()))
        for ((ago, n) in d) {
            insert(
                "listing_stats_daily",
                linkedMapOf("kind" to kind, "listing_id" to id, "day" to Sql.date(DhakaTime.day(now - ago * DAY)), "calls" to n),
                "(kind, listing_id, day) DO UPDATE SET calls = EXCLUDED.calls",
            )
        }
        for (r in recent) {
            insert(
                "listing_recent_ratings",
                linkedMapOf("kind" to kind, "listing_id" to id, "who" to r.who, "rating" to r.rating, "comment" to r.comment, "at" to ts(r.at)),
                "(kind, listing_id, who) DO NOTHING",
            )
        }
    }

    data class Recent(val rating: Int, val comment: String, val at: Long, val who: String)

    companion object {
        const val DAY = 86_400_000L
        const val HOUR = 3_600_000L
        const val MIN = 60_000L

        val GAZIPUR: String by lazy { district("Gazipur") }

        fun district(en: String) = Places.DISTRICTS.first { it.en == en }.bn

        /** demo/services.js dailyCalls: the last 30 days' calls (fixed numbers) → {days ago: n}, total */
        fun dailyCalls(base: Int, seed: Int): Pair<Map<Int, Int>, Int> {
            val d = LinkedHashMap<Int, Int>()
            var total = 0
            for (i in 0 until 30) {
                val n = max(0, Js.round(base + 2 * sin((i + seed) * 1.3) + ((i * 7 + seed) % 3) - 1).toInt())
                if (n != 0) d[i] = n
                total += n
            }
            return d to total
        }

        /** sellerstats.js raterKey */
        fun raterKey(kind: String, id: String, uid: String) = ListingStats.raterKey(kind, id, uid)

        /** rxnet.js nameKey */
        fun nameKey(n: String?): String = com.lifolo.pharmacy.RxMath.key(n)

        /** rxnet.js missKey */
        fun missKey(n: String): String = com.lifolo.pharmacy.RxMath.missKey(n)

        /** `Math.round(v * 1000) / 1000` */
        fun r3(v: Double) = Js.round(v * 1000) / 1000

        /** `Math.round(v * 100) / 100` */
        fun r2(v: Double) = Js.round(v * 100) / 100

        fun resource(path: String): String =
            DemoSql::class.java.getResourceAsStream(path)?.use { String(it.readAllBytes()).trim() } ?: error("missing $path")
    }
}
