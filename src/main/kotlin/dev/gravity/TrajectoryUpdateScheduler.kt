package dev.gravity

internal class TrajectoryUpdateScheduler(
    private val updateIntervalNanos: Long = 1_000_000_000L / 30L,
) {
    private var lastUpdateNanos = 0L
    private var hasUpdated = false

    fun shouldUpdate(nowNanos: Long): Boolean {
        if (hasUpdated &&
            nowNanos - lastUpdateNanos < updateIntervalNanos) {
            return false
        }

        lastUpdateNanos = nowNanos
        hasUpdated = true
        return true
    }

    fun reset() {
        lastUpdateNanos = 0L
        hasUpdated = false
    }
}
