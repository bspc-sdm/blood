package com.lifolo.common

import com.lifolo.support.IntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/** Without the R2 env vars nothing changes: base64 stays in the rows, no keys, no trash, the migration refuses. */
class StorageOffIT : IntegrationTest() {
    @Autowired lateinit var files: StoredFiles

    @Test
    fun `R2 off - photos stay base64 in PostgreSQL`() {
        assertFalse(files.enabled)
        user("off1", emptyMap())
        val photo = "/9j/" + "A".repeat(400)
        assertEquals(200, call("off1", "profile_update", mapOf("set" to mapOf("photo" to photo, "photoThumb" to "QQ"))).status)
        val r = row("SELECT photo, photo_thumb, photo_key, photo_thumb_key FROM user_profiles WHERE uid = 'off1'")
        assertEquals(listOf(photo, "QQ", null, null), r.values.toList())
        assertEquals(photo, call("off1", "profile_get").body["profile"]["photo"].textValue())
        assertEquals(200, call("off1", "profile_update", mapOf("set" to mapOf("photo" to null))).status)
        assertNull(row("SELECT photo FROM user_profiles WHERE uid = 'off1'")["photo"])
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM stored_objects", Int::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM stored_objects_trash", Int::class.java))
        assertEquals(0, files.sweep())

        val auth = mapOf("Authorization" to "Bearer test-cron-secret")
        val st = get("/api/cron/storage", auth)
        assertEquals(false, st.body["configured"].booleanValue())
        assertEquals(1, st.body["remaining"]["profileThumb"].intValue())
        assertEquals(400, post("/api/cron/storage/migrate", null, auth).status)
    }
}
