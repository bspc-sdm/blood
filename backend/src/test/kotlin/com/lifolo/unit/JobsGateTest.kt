package com.lifolo.unit

import com.lifolo.jobs.JobsGateState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** blue/green deploy: only one instance runs the JobRunr jobs (jobs/JobsGate.kt, deploy/deploy-backend.sh) */
class JobsGateTest {
    @Test
    fun `a normal start (reboot, crash restart) starts the jobs`() {
        val g = JobsGateState(hold = false)
        assertTrue(g.mayStart())
        assertEquals("stopped", g.state(running = false))
        assertEquals("running", g.state(running = true))
    }

    @Test
    fun `the new instance of a deploy waits until the deploy releases it`() {
        val g = JobsGateState(hold = true)
        assertFalse(g.mayStart(), "JobRunr's own start at ApplicationReady is held")
        assertEquals("held", g.state(running = false))
        g.release()
        assertTrue(g.mayStart())
    }

    @Test
    fun `the old instance after the hand-over never starts again`() {
        val g = JobsGateState(hold = false)
        g.handOver()
        assertFalse(g.mayStart())
        assertEquals("handed_over", g.state(running = false))
        assertThrows(IllegalStateException::class.java) { g.release() }
    }
}
