package dev.gravity

internal class TrajectoryUpdateScheduler(
    updatesPerSecond: Int,
) {
    private val updateIntervalNanos =
        1_000_000_000L / updatesPerSecond.also { require(it > 0) }
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
