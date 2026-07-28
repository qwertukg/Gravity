package dev.gravity

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrajectoryUpdateSchedulerTest {
    @Test
    fun `trajectory updates no more than thirty times per second`() {
        val scheduler = TrajectoryUpdateScheduler()
        assertTrue(scheduler.shouldUpdate(nowNanos = 0L))

        for (millis in 1L..33L) {
            assertFalse(
                scheduler.shouldUpdate(nowNanos = millis * 1_000_000L),
            )
        }
        assertTrue(
            scheduler.shouldUpdate(nowNanos = 34_000_000L),
        )
    }

    @Test
    fun `reset allows immediate update for a new drag`() {
        val scheduler = TrajectoryUpdateScheduler()
        assertTrue(scheduler.shouldUpdate(nowNanos = 10L))
        scheduler.reset()
        assertTrue(scheduler.shouldUpdate(nowNanos = 11L))
    }
}
