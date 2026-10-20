package com.lifolo.common

import com.lifolo.rx.RxLabTest
import com.lifolo.support.Minio
import com.lifolo.users.AccountDeletionService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.util.Base64

/**
 * Photos on R2 (MinIO here): every write path stores the bytes in the bucket and only the key in PostgreSQL, every
 * read path answers with the same base64 text as before, deleting rows deletes the objects, and the one-off
 * migration moves old base64 rows only after the SHA-256 of the uploaded object matches.
 */
class R2StorageIT : RxLabTest() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun r2(r: DynamicPropertyRegistry) = Minio.register(r)

        /** canonical base64 of a fake JPEG (stored as bytes) */
        fun jpeg(n: Int, seed: Int): String {
            val b = ByteArray(n) { ((it * 31 + seed * 7) and 0xff).toByte() }
            b[0] = 0xff.toByte()
            b[1] = 0xd8.toByte()
            b[2] = 0xff.toByte()
            return Base64.getEncoder().encodeToString(b)
        }

        const val CRON = "Bearer test-cron-secret"
    }

    @Autowired lateinit var files: StoredFiles

    @Autowired lateinit var storage: ObjectStorage

    @Autowired lateinit var migrator: StorageMigrator

    @Autowired lateinit var txm: PlatformTransactionManager

    @Autowired lateinit var deletion: AccountDeletionService

    @BeforeEach
    fun emptyBucket() = Minio.clear()

    private fun key(sql: String, vararg args: Any?): String = row(sql, *args).values.single() as String

    private fun trash(): Set<String> = jdbc.queryForList("SELECT key FROM stored_objects_trash", String::class.java).toSet()

    private fun meta(key: String) = row("SELECT size, content_type, sha256, encoding FROM stored_objects WHERE key = ?", key)

    private val labBody = mapOf(
        "name" to "সিটি ডায়াগনস্টিক", "upazilaId" to 466, "address" to "চরপাড়া মোড়", "licenseNo" to "DGHS-77",
        "phone" to "01711-000777", "termsAccepted" to true, "hours" to "সকাল ৮টা – রাত ১০টা", "homeCollection" to true,
    )

    @Test
    fun `profile photo and thumb - bytes on R2, key in the row, the same text back, old ones deleted`() {
        assertTrue(files.enabled)
        person("pR", "ছবি")
        val photo = jpeg(900, 1)
        val thumb = "QQ" // accepted by the old check, not canonical base64 → kept as text
        ok("pR", "profile_update", mapOf("set" to mapOf("photo" to photo, "photoThumb" to thumb)))
        val r = row("SELECT photo, photo_thumb, photo_key, photo_thumb_key FROM user_profiles WHERE uid = 'pR'")
        assertNull(r["photo"])
        assertNull(r["photo_thumb"])
        val pk = r["photo_key"] as String
        val tk = r["photo_thumb_key"] as String
        assertTrue(pk.startsWith("profile/") && pk.endsWith(".jpg"), pk)
        assertTrue(tk.startsWith("profile-thumb/") && tk.endsWith(".b64.txt"), tk)
        assertEquals(Base64.getDecoder().decode(photo).toList(), Minio.bytes(pk)!!.toList(), "the real image bytes in the bucket")
        assertEquals("image/jpeg", Minio.contentType(pk))
        assertEquals(thumb, String(Minio.bytes(tk)!!))
        assertEquals(listOf(900, "image/jpeg", B64Blob.sha256(Base64.getDecoder().decode(photo)), "bytes"), meta(pk).values.toList())
        assertEquals("b64text", meta(tk)["encoding"])

        val p = ok("pR", "profile_get")["profile"]
        assertEquals(photo, p["photo"].textValue())
        assertTrue(p["hasPhotoThumb"].booleanValue())
        files.forget(pk)
        assertEquals(photo, ok("pR", "profile_get")["profile"]["photo"].textValue(), "read from R2, not the cache")
        assertEquals(mapOf(tk to thumb), files.texts(listOf(tk)))

        // a new photo: the old object is queued in the same transaction and deleted by the sweep
        val photo2 = jpeg(700, 2)
        ok("pR", "profile_update", mapOf("set" to mapOf("photo" to photo2)))
        val pk2 = key("SELECT photo_key FROM user_profiles WHERE uid = 'pR'")
        assertEquals(setOf(pk), trash())
        assertTrue(meta(pk).isEmpty())
        assertEquals(1, files.sweep())
        assertNull(Minio.bytes(pk))
        assertEquals(setOf(pk2, tk), Minio.keys())
        assertEquals(photo2, ok("pR", "profile_get")["profile"]["photo"].textValue())

        // removing both
        ok("pR", "profile_update", mapOf("set" to mapOf("photo" to null, "photoThumb" to null)))
        val d = row("SELECT photo, photo_thumb, photo_key, photo_thumb_key FROM user_profiles WHERE uid = 'pR'")
        assertTrue(d.values.all { it == null }, d.toString())
        assertEquals(2, files.sweep())
        assertEquals(emptySet<String>(), Minio.keys())
        val gone = ok("pR", "profile_get")["profile"]
        assertTrue(gone["photo"].isNull)
        assertFalse(gone["hasPhotoThumb"].booleanValue())
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM stored_objects", Int::class.java))
    }

    @Test
    fun `verification documents - lab_save uploads, the admin sees the same base64, lab_delete deletes the objects`() {
        person("lab1", "মালিক")
        person("adm", "অ্যাডমিন", email = "owner@example.com")
        val license = jpeg(1200, 3)
        val front = "data:image/jpeg;base64,/9j/4AAQSkZJRgABAQ" // the prefix is removed, the rest is not canonical
        ok("lab1", "lab_save", labBody + mapOf("district" to dist, "docs" to mapOf("license" to license, "front" to front)))
        val rows = jdbc.queryForList("SELECT type, data, storage_key, size FROM verify_docs WHERE kind = 'lab' AND listing_id = 'lab1' ORDER BY type")
        assertEquals(listOf("front", "license"), rows.map { it["type"] })
        assertTrue(rows.all { it["data"] == null && it["storage_key"] != null }, rows.toString())
        assertEquals(listOf(18, license.length), rows.map { it["size"] }, "size stays the base64 length (as before)")
        assertEquals(rows.map { it["storage_key"] as String }.toSet(), Minio.keys())

        val photos = ok("adm", "admin_verify_docs", mapOf("kind" to "lab", "id" to "lab1"))["photos"]
        assertEquals(
            mapOf("license" to license, "front" to "/9j/4AAQSkZJRgABAQ"),
            photos.associate { it["type"].textValue() to it["data"].textValue() },
        )

        // a new licence photo replaces the old object
        val oldLicense = rows.first { it["type"] == "license" }["storage_key"] as String
        ok("lab1", "verify_docs_upload", mapOf("kind" to "lab", "id" to "lab1", "docs" to mapOf("license" to jpeg(400, 4))))
        assertEquals(setOf(oldLicense), trash())
        files.sweep()
        assertFalse(oldLicense in Minio.keys())

        ok("lab1", "lab_delete")
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM verify_docs", Int::class.java))
        assertEquals(2, trash().size)
        assertEquals(2, files.sweep())
        assertEquals(emptySet<String>(), Minio.keys())
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM stored_objects", Int::class.java))
    }

    private fun labOrder(id: String, labUid: String, patient: String) {
        jdbc.update(
            "INSERT INTO lab_orders (id, lab_uid, lab_name, patient_uid, patient_name, tests, status) VALUES (?, ?, 'ল্যাব', ?, 'রোগী', '{CBC}', 'received')",
            id, labUid, patient,
        )
    }

    @Test
    fun `lab report photos - on R2, the report reads the same, a new report and the order's deletion delete them`() {
        person("labR", "ল্যাব")
        lab("labR", "ল্যাব")
        person("patR", "রোগী")
        labOrder("lo1", "labR", "patR")
        val p1 = jpeg(300, 5)
        val p2 = jpeg(240, 6).chunked(76).joinToString("\n") // whitespace allowed by the old check → kept as text
        ok("labR", "lab_report", mapOf("id" to "lo1", "items" to emptyList<Any>(), "photos" to listOf(p1, p2)))
        val keys = jdbc.queryForList("SELECT storage_key FROM lab_report_photos WHERE order_id = 'lo1' AND data IS NULL ORDER BY idx", String::class.java)
        assertEquals(2, keys.size)
        assertTrue(keys[0].endsWith(".jpg") && keys[1].endsWith(".b64.txt"), keys.toString())
        assertEquals(listOf(p1, p2), ok("patR", "lab_order_get", mapOf("id" to "lo1"))["item"]["report"]["photos"].map { it.textValue() })
        keys.forEach { files.forget(it) }
        assertEquals(listOf(p1, p2), ok("labR", "lab_order_get", mapOf("id" to "lo1"))["item"]["report"]["photos"].map { it.textValue() })

        val p3 = jpeg(200, 7)
        ok("labR", "lab_report", mapOf("id" to "lo1", "items" to emptyList<Any>(), "photos" to listOf(p3)))
        assertEquals(keys.toSet(), trash())
        assertEquals(2, files.sweep())
        assertEquals(listOf(p3), ok("patR", "lab_order_get", mapOf("id" to "lo1"))["item"]["report"]["photos"].map { it.textValue() })

        // the order goes (ON DELETE CASCADE) → its photo's object too
        jdbc.update("DELETE FROM lab_orders WHERE id = 'lo1'")
        assertEquals(1, files.sweep())
        assertEquals(emptySet<String>(), Minio.keys())
    }

    @Test
    fun `account deletion deletes the profile photo and the document objects`() {
        val u = "gone0000r2"
        person(u, "মালিক")
        ok(u, "profile_update", mapOf("set" to mapOf("photo" to jpeg(500, 8), "photoThumb" to jpeg(100, 9))))
        ok(u, "lab_save", labBody + mapOf("district" to dist, "docs" to mapOf("license" to jpeg(300, 10), "front" to jpeg(300, 11))))
        assertEquals(4, Minio.keys().size)
        assertEquals(200, call(u, "delete_account").status)
        assertEquals(4, Minio.keys().size, "nothing deleted while the deletion can still be cancelled")
        jdbc.update("UPDATE accounts SET delete_at = ? WHERE uid = ?", Timestamp(System.currentTimeMillis() - 1000), u)
        assertEquals(1, deletion.runDue(System.currentTimeMillis()))
        assertEquals(4, trash().size)
        assertEquals(4, files.sweep())
        assertEquals(emptySet<String>(), Minio.keys())
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM stored_objects", Int::class.java))
    }

    @Test
    fun `demo wipe deletes the demo's objects`() {
        user("owner-demo-r2", emptyMap(), email = "owner@example.com")
        ok("owner-demo-r2", "admin_demo_seed")
        val before = migrator.remaining()
        assertTrue(before.getValue("verifyDocs") > 0, before.toString())
        // the demo is seeded with base64 in the rows; the migration moves them like any other row
        for (i in 1..20) {
            if (migrator.remaining().values.sum() == 0L) break
            assertNotNull(migrator.migrate(500))
        }
        assertEquals(0L, migrator.remaining().values.sum())
        assertTrue(Minio.keys().isNotEmpty())
        for (i in 1..5) {
            if (ok("owner-demo-r2", "admin_demo_wipe")["more"]?.booleanValue() != true) break
        }
        files.sweep(10_000)
        assertEquals(emptySet<String>(), Minio.keys())
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM stored_objects", Int::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM stored_objects_trash", Int::class.java))
    }

    /** base64 rows written before R2 (or while it was off): two profiles, two documents, two report photos */
    private fun oldRows(): Map<String, String> {
        val v = linkedMapOf(
            "p1" to jpeg(800, 20), "t1" to "QUJD", "p2" to "QQ==", "d1" to jpeg(600, 21), "d2" to "/9j/4AAQSkZJRgABAQ",
            "r1" to jpeg(300, 22), "r2" to jpeg(300, 23).chunked(60).joinToString("\r\n"),
        )
        user("old1", mapOf("photo" to v["p1"], "photo_thumb" to v["t1"]))
        user("old2", mapOf("photo" to v["p2"], "photo_thumb" to ""))
        person("adm", "অ্যাডমিন", email = "owner@example.com")
        person("labO", "ল্যাব")
        lab("labO", "ল্যাব", status = "pending")
        for ((t, d) in listOf("license" to v["d1"], "front" to v["d2"])) {
            jdbc.update(
                "INSERT INTO verify_docs (kind, listing_id, type, owner_uid, data, size) VALUES ('lab', 'labO', ?, 'labO', ?, ?)", t, d, d!!.length,
            )
        }
        jdbc.update("UPDATE labs SET docs_have = '{license,front}' WHERE uid = 'labO'")
        person("patO", "রোগী")
        labOrder("loO", "labO", "patO")
        jdbc.update("UPDATE lab_orders SET status = 'reported', reported_at = now() WHERE id = 'loO'")
        jdbc.update("INSERT INTO lab_report_photos (order_id, idx, data) VALUES ('loO', 0, ?), ('loO', 1, ?)", v["r1"], v["r2"])
        return v
    }

    private fun readAll(): List<Any?> {
        val prof = jdbc.queryForList("SELECT uid FROM user_profiles WHERE uid IN ('old1', 'old2') ORDER BY uid", String::class.java).map { uid ->
            val p = ok(uid, "profile_get")["profile"]
            p["photo"].textValue() to p["hasPhotoThumb"].booleanValue()
        }
        val docs = ok("adm", "admin_verify_docs", mapOf("kind" to "lab", "id" to "labO"))["photos"].map { it["data"].textValue() }
        val report = ok("patO", "lab_order_get", mapOf("id" to "loO"))["item"]["report"]["photos"].map { it.textValue() }
        return listOf(prof, docs, report)
    }

    @Test
    fun `migration job - batches, verified, resumable, idempotent, reads unchanged`() {
        val v = oldRows()
        val before = readAll()
        assertEquals(listOf(v["p1"] to true, v["p2"] to false), before[0])

        assertEquals(401, get("/api/cron/storage").status)
        assertEquals(401, post("/api/cron/storage/migrate", null).status)
        val st = get("/api/cron/storage", mapOf("Authorization" to CRON))
        assertEquals(200, st.status, st.toString())
        assertEquals(true, st.body["configured"].booleanValue())
        // the empty thumb of old2 stays as it is (nothing to move)
        assertEquals(mapOf("profilePhoto" to 2, "profileThumb" to 1, "verifyDocs" to 2, "labReportPhotos" to 2), st.body["remaining"].fields().asSequence().associate { it.key to it.value.intValue() })

        val r1 = post("/api/cron/storage/migrate?limit=3", null, mapOf("Authorization" to CRON))
        assertEquals(200, r1.status, r1.toString())
        assertEquals(3, r1.body["migrated"].intValue())
        assertEquals(4, r1.body["remaining"].fields().asSequence().sumOf { it.value.intValue() })
        assertEquals(before, readAll(), "half moved: the answers are the same")

        val r2 = post("/api/cron/storage/migrate?limit=100", null, mapOf("Authorization" to CRON))
        assertEquals(4, r2.body["migrated"].intValue())
        assertEquals(0, r2.body["remaining"].fields().asSequence().sumOf { it.value.intValue() })
        assertEquals(7, r2.body["stored"].intValue())
        assertEquals(0, post("/api/cron/storage/migrate", null, mapOf("Authorization" to CRON)).body["migrated"].intValue(), "nothing left to do")

        // the rows keep only the key; every object matches its recorded SHA-256 and size
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM verify_docs WHERE data IS NOT NULL", Int::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM lab_report_photos WHERE data IS NOT NULL", Int::class.java))
        assertEquals(
            listOf(null, null, "", null),
            row(
                "SELECT p1.photo AS a, p1.photo_thumb AS b, p2.photo_thumb AS c, p2.photo AS d FROM user_profiles p1, user_profiles p2 " +
                    "WHERE p1.uid = 'old1' AND p2.uid = 'old2'",
            ).values.toList(),
        )
        for (m in jdbc.queryForList("SELECT key, sha256, size FROM stored_objects")) {
            val bytes = Minio.bytes(m["key"] as String)!!
            assertEquals(m["sha256"], B64Blob.sha256(bytes))
            assertEquals(m["size"], bytes.size)
        }
        assertEquals(jdbc.queryForList("SELECT key FROM stored_objects", String::class.java).toSet(), Minio.keys())

        listOf("old1", "old2").forEach { u -> jdbc.queryForList("SELECT photo_key FROM user_profiles WHERE uid = ?", String::class.java, u).forEach { files.forget(it) } }
        assertEquals(before, readAll(), "all moved: the answers are the same")
    }

    @Test
    fun `migration keeps the base64 when the uploaded object does not match, or the row changed meanwhile`() {
        val v = oldRows()
        // an object store that corrupts what it gives back
        val corrupt = object : ObjectStorage by storage {
            override fun get(key: String): ByteArray? = storage.get(key)?.also { if (it.isNotEmpty()) it[it.size - 1] = (it.last() + 1).toByte() }
        }
        val bad = StorageMigrator(jdbc, corrupt, files, txm).migrate(100)!!
        assertEquals(listOf(0, 7), listOf(bad.migrated, bad.failed))
        assertEquals(7L, migrator.remaining().values.sum())
        assertEquals(v["p1"], row("SELECT photo FROM user_profiles WHERE uid = 'old1'")["photo"])
        assertEquals(emptySet<String>(), Minio.keys(), "the bad uploads are deleted")
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM stored_objects", Int::class.java))

        // the owner sets a new photo between the upload and the update → the row is left for the next run
        val racing = object : ObjectStorage by storage {
            override fun put(key: String, bytes: ByteArray, contentType: String) {
                storage.put(key, bytes, contentType)
                if (key.startsWith("profile/")) jdbc.update("UPDATE user_profiles SET photo = 'TkVX' WHERE uid = 'old1'")
            }
        }
        val src = StorageMigrator.SOURCES.first { it.name == "profilePhoto" }
        val raced = StorageMigrator(jdbc, racing, files, txm).moveRow(src, mapOf("uid" to "old1", "data" to v["p1"]))
        assertEquals(StorageMigrator.Outcome.CHANGED, raced)
        assertEquals("TkVX", row("SELECT photo FROM user_profiles WHERE uid = 'old1'")["photo"])
        assertEquals(emptySet<String>(), Minio.keys())

        // then the real run moves everything (the new photo included)
        assertEquals(7, migrator.migrate(100)!!.migrated)
        assertEquals("TkVX", ok("old1", "profile_get")["profile"]["photo"].textValue())
    }

    @Test
    fun `an upload in a transaction that rolls back is deleted again`() {
        val tx = TransactionTemplate(txm)
        var k = ""
        tx.executeWithoutResult {
            k = files.put("profile", jpeg(100, 30))
            assertNotNull(Minio.bytes(k))
            it.setRollbackOnly()
        }
        assertNull(Minio.bytes(k))
        assertTrue(meta(k).isEmpty())
    }
}
