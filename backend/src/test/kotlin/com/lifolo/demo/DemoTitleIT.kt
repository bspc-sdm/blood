package com.lifolo.demo

import com.lifolo.practice.TitleRules
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * backend/docs/PRACTICE.md §6 (title_guard): the demo world (DemoRefresh builds it every day) never has a provider who
 * is not an MBBS/BDS doctor with "ডা."/"Dr." in a name; the allopathic demo doctors keep theirs.
 */
class DemoTitleIT : DemoTestBase() {
    @Autowired lateinit var seeder: DemoSeeder

    @Test
    fun `demo - no non-MBBS demo provider is seeded with a title, the admin list has no demo names`() {
        owner()
        seedAll()
        val rows = jdbc.queryForList(
            """
            SELECT coalesce(b.uid, p.uid) AS uid, coalesce(b.type, p.type) AS type,
                   CASE WHEN b.uid IS NOT NULL THEN b.system ELSE p.system END AS system,
                   p.name AS listing, b.name AS badge, up.name AS profile
            FROM providers p FULL JOIN badges b ON b.uid = p.uid LEFT JOIN user_profiles up ON up.uid = coalesce(b.uid, p.uid)
            WHERE coalesce(b.uid, p.uid) LIKE 'demo-%'
            """.trimIndent(),
        )
        val nonMbbs = rows.filter { !TitleRules.eligible(it["type"] as String?, it["system"] as String?) }
        assertTrue(nonMbbs.size >= 5, "homeopath, unani, ayurveda, physio, psychologist, nurses … ($nonMbbs)")
        for (r in nonMbbs) {
            for (k in listOf("listing", "badge", "profile")) assertTrue(!TitleRules.hasTitle(r[k] as String?), "$k of ${r["uid"]}: ${r[k]}")
        }
        // appointments and prescriptions of the demo world carry the names as copies
        val copies = jdbc.queryForList(
            "SELECT a.provider_name AS n, p.type, p.system FROM appointments a JOIN providers p ON p.uid = a.provider_uid WHERE a.provider_uid LIKE 'demo-%'",
        )
        assertTrue(copies.none { !TitleRules.eligible(it["type"] as String?, it["system"] as String?) && TitleRules.hasTitle(it["n"] as String?) }, copies.toString())
        // the allopathic demo doctor keeps "ডা."
        assertEquals("ডা. ডেমো হাসান", jdbc.queryForObject("SELECT name FROM user_profiles WHERE uid = ?", String::class.java, U("doctor")))
        assertEquals(0, ok(OWNER, "admin_title_names")["items"].count { it["demo"].booleanValue() })
    }

    @Test
    fun `demo - a seed part that writes a title for a non-MBBS demo provider is corrected (demo rows only)`() {
        user("demo-x-tnurse", mapOf("name" to "ডা. ডেমো নার্স"))
        jdbc.update("INSERT INTO badges (uid, type, reg_no, workplace, name, email, status) VALUES ('demo-x-tnurse', 'nurse', 'N', 'X', 'Dr. ডেমো নার্স', '', 'approved')")
        jdbc.update("INSERT INTO providers (uid, type, name) VALUES ('demo-x-tnurse', 'nurse', 'ডাঃ ডেমো নার্স')")
        // a real nurse's stored name is never changed (only shown without the title)
        user("realnurse", mapOf("name" to "ডা. আসল নার্স"))
        jdbc.update("INSERT INTO providers (uid, type, name) VALUES ('realnurse', 'nurse', 'ডা. আসল নার্স')")
        assertEquals(3, seeder.noDemoTitles())
        assertEquals(
            listOf("ডেমো নার্স", "ডেমো নার্স", "ডেমো নার্স"),
            listOf(
                jdbc.queryForObject("SELECT name FROM user_profiles WHERE uid = 'demo-x-tnurse'", String::class.java),
                jdbc.queryForObject("SELECT name FROM badges WHERE uid = 'demo-x-tnurse'", String::class.java),
                jdbc.queryForObject("SELECT name FROM providers WHERE uid = 'demo-x-tnurse'", String::class.java),
            ),
        )
        assertEquals("ডা. আসল নার্স", jdbc.queryForObject("SELECT name FROM providers WHERE uid = 'realnurse'", String::class.java))
    }
}
