package dev.gravity

internal class SimulationSpeed(
    private val scalePerScroll: Double,
    private val minimumScale: Double,
) {
    init {
        require(scalePerScroll > 0.0)
        require(minimumScale in 0.0..1.0)
    }
    private var stepAccumulator = 0.0

    var scale: Double = 1.0
        private set

    fun scroll(yOffset: Double) {
        scale = (
            scale + yOffset * scalePerScroll
        ).coerceIn(minimumScale, 1.0)
    }

    fun shouldAdvance(paused: Boolean): Boolean {
        if (paused) return false
        stepAccumulator += scale
        if (stepAccumulator + ACCUMULATOR_EPSILON < 1.0) return false
        stepAccumulator = (stepAccumulator - 1.0).coerceAtLeast(0.0)
        return true
    }

    private companion object {
        const val ACCUMULATOR_EPSILON = 1e-12
    }
}
