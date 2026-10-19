package com.lifolo.ambulance

import com.fasterxml.jackson.databind.JsonNode
import com.lifolo.common.Places
import com.lifolo.common.Sql
import com.lifolo.support.IntegrationTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired

/** helpers of the ambulance tests (the Node tests' user/amb/at/call helpers; pushes are read from the inbox) */
abstract class AmbTestBase : IntegrationTest() {
    @Autowired lateinit var repo: AmbRepo

    @Autowired lateinit var reqs: AmbReqService

    @Autowired lateinit var ambs: AmbulanceService

    @Autowired lateinit var places: AmbPlaces

    companion object {
        val MYM_D: Places.District = Places.DISTRICTS.first { it.en == "Mymensingh" }
        val MYM: String = MYM_D.bn
        val DHAKA: String = Places.DISTRICTS.first { it.en == "Dhaka" }.bn
        val GAZIPUR: String = Places.DISTRICTS.first { it.en == "Gazipur" }.bn
        const val SADAR = 466
        const val TRISHAL = 463
        const val SAVAR = 365
        const val SEC = 1000L
        const val MIN = 60 * SEC
        const val HOUR = 60 * MIN
        const val DAY = 24 * HOUR
        const val OWNER = "amb-owner-admin"

        /** dx km east, dy km north of the Mymensingh district centre, 5 decimals */
        fun at(dx: Double, dy: Double): Pair<Double, Double> = Pair(
            Math.round((MYM_D.lat + dy / 111) * 1e5) / 1e5,
            Math.round((MYM_D.lng + dx / (111 * Math.cos(MYM_D.lat * Math.PI / 180))) * 1e5) / 1e5,
        )

        fun r2(v: Double) = Math.round(v * 100) / 100.0
    }

    @BeforeEach
    fun resetClock() {
        AmbClock.reset()
    }

    @AfterEach
    fun resetClockAfter() {
        AmbClock.reset()
    }

    fun now() = AmbClock.now()

    /** a logged-in user with a profile (name, lang bn, phone) */
    fun person(uid: String, name: String = "নাম $uid", lang: String = "bn", phone: String = "01811000000", email: String = "$uid@example.com") {
        user(uid, mapOf("name" to name, "lang" to lang, "phone" to phone), email)
    }

    /** an ambulance row as the Node tests write it (owner with a profile) */
    fun amb(
        id: String,
        owner: String,
        name: String = "গাড়ি $id",
        phone: String = "01711000000",
        types: List<String> = listOf("general"),
        district: String = MYM,
        upazilaId: Int? = SADAR,
        status: String = "approved",
        available: Boolean = true,
        availableAt: Long? = now() - HOUR,
        busyAt: Long? = null,
        base: Pair<Double, Double>? = null,
        exactBase: Boolean = false,
        ratingSum: Int = 0,
        ratingCount: Int = 0,
        chart: FareChart? = null,
        remindAt: Long? = null,
        fare: String = "",
        createdAt: Long = now() - 30 * DAY,
    ) {
        person(owner)
        val b = base?.let { if (exactBase) it else Pair(r2(it.first), r2(it.second)) }
        jdbc.update(
            """
            INSERT INTO ambulances (id, owner_uid, name, phone, vehicle_no, types, district, upazila_id, area, fare, lat, lng, available,
              available_at, busy_at, remind_at, rating_sum, rating_count, status, created_at)
            VALUES (?, ?, ?, ?, ?, ?::text[], ?, ?, '', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            id, owner, name, phone, "V-$id", types.joinToString(",", "{", "}"), district, upazilaId, fare, b?.first, b?.second, available,
            availableAt?.let { Sql.ts(it) }, busyAt?.let { Sql.ts(it) }, remindAt?.let { Sql.ts(it) }, ratingSum, ratingCount, status, Sql.ts(createdAt),
        )
        if (chart != null) repo.saveChart(id, chart)
    }

    fun ok(uid: String?, action: String, body: Map<String, Any?> = emptyMap()): JsonNode {
        val r = call(uid, action, body)
        assertEquals(200, r.status, "$action: ${r.body}")
        return r.body
    }

    /** uids pushed to (inbox rows), oldest first */
    fun pushedTo(): List<String> = jdbc.queryForList("SELECT uid FROM inbox_items ORDER BY created_at, id", String::class.java)

    fun pushes(): List<Map<String, Any?>> =
        jdbc.queryForList("SELECT uid, title, body, data::text AS data FROM inbox_items ORDER BY created_at, id")

    fun clearPushes() {
        jdbc.update("DELETE FROM inbox_items")
    }

    /** the request's targets in sentTo order */
    fun sentTo(id: String): List<String> =
        jdbc.queryForList("SELECT owner_uid FROM ambreq_targets WHERE request_id = ? ORDER BY seq", String::class.java, id)

    fun reqRow(id: String): Map<String, Any?> = row("SELECT * FROM ambulance_requests WHERE id = ?", id)

    fun ambRow(id: String): Map<String, Any?> = row("SELECT * FROM ambulances WHERE id = ?", id)

    fun driverList(uid: String): List<JsonNode> = ok(uid, "ambreq_driver")["items"].toList()

    fun ids(r: JsonNode): List<String> = r["items"].map { it["id"].textValue() }

    fun fc(local: Int?, rows: List<FareRow> = emptyList(), oxygen: Int? = null, icu: Int? = null, night: Int? = null) =
        FareChart(local, rows, oxygen, icu, night)

    fun toDhaka(amount: Int) = FareRow(Dest("district", district = DHAKA), amount)

    fun json(v: Any?): JsonNode = mapper.valueToTree(v)
}
