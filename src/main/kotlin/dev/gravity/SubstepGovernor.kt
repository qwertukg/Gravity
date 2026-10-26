package dev.gravity

import kotlin.math.floor

/**
 * Keeps a frame within its GPU budget. The collision substeps take most of a
 * step, so while a frame takes longer than [budgetMillis] the next steps run
 * proportionally fewer, down to half of [maximum]; below [RECOVERY] of the
 * budget they come back one at a time. The time measured is a few frames old,
 * so after each change the governor waits [SETTLE_FRAMES] frames for it to
 * show. A budget of 0 keeps every substep.
 */
class SubstepGovernor(private val maximum: Int, private val budgetMillis: Double) {
    private val minimum = (maximum + 1) / 2
    private var settling = 0

    var substeps = maximum
        private set

    /** The substeps of the next step, after a frame measured at [gpuMillis]. */
    fun update(gpuMillis: Double): Int {
        if (budgetMillis <= 0.0 || gpuMillis.isNaN()) return substeps
        if (settling > 0) {
            settling--
            return substeps
        }
        val next = when {
            gpuMillis > budgetMillis -> minOf(substeps - 1, floor(substeps * budgetMillis / gpuMillis).toInt())
            gpuMillis < budgetMillis * RECOVERY -> substeps + 1
            else -> substeps
        }.coerceIn(minimum, maximum)
        if (next != substeps) {
            substeps = next
            settling = SETTLE_FRAMES
        }
        return substeps
    }

    fun reset() {
        substeps = maximum
        settling = 0
    }

    companion object {
        const val RECOVERY = 0.8
        const val SETTLE_FRAMES = 3
    }
}
