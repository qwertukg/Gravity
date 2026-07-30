package dev.gravity

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrajectoryUpdateSchedulerTest {
    private fun defaultScheduler() = TrajectoryUpdateScheduler(
        updatesPerSecond = TrajectoryConfig().updatesPerSecond,
    )

    @Test
    fun `configured update frequency changes the interval`() {
        val scheduler = TrajectoryUpdateScheduler(updatesPerSecond = 60)
        assertTrue(scheduler.shouldUpdate(nowNanos = 0L))
        assertFalse(scheduler.shouldUpdate(nowNanos = 16_000_000L))
        assertTrue(scheduler.shouldUpdate(nowNanos = 17_000_000L))
    }

    @Test
    fun `trajectory updates no more than thirty times per second`() {
        val scheduler = defaultScheduler()
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
        val scheduler = defaultScheduler()
        assertTrue(scheduler.shouldUpdate(nowNanos = 10L))
        scheduler.reset()
        assertTrue(scheduler.shouldUpdate(nowNanos = 11L))
    }
}
