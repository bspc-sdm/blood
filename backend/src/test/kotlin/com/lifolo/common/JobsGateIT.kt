package com.lifolo.common

import com.lifolo.support.IntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders

/** POST /api/cron/jobs — the blue/green deploy's JobRunr hand-over (CRON_SECRET only) */
class JobsGateIT : IntegrationTest() {
    private fun jobs(doIt: String, auth: String?) =
        mvc.perform(
            MockMvcRequestBuilders.post("/api/cron/jobs").param("do", doIt).apply {
                if (auth != null) header("Authorization", auth)
            },
        ).andReturn().response

    @Test
    fun `needs the cron secret`() {
        assertEquals(401, jobs("status", null).status)
        assertEquals(401, jobs("stop", "Bearer wrong").status)
    }

    @Test
    fun `tests run without the job server - status says off`() {
        val r = jobs("status", "Bearer test-cron-secret")
        assertEquals(200, r.status)
        val body = mapper.readTree(r.getContentAsString(Charsets.UTF_8))
        assertEquals("off", body["server"].asText())
        assertTrue(body["ok"].asBoolean())
    }

    @Test
    fun `GET is not allowed`() {
        val r = mvc.perform(MockMvcRequestBuilders.get("/api/cron/jobs").header("Authorization", "Bearer test-cron-secret")).andReturn().response
        assertEquals(405, r.status)
    }
}
