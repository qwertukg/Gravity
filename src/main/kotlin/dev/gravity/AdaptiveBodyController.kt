package dev.gravity

import kotlin.math.max
import kotlin.math.min

/**
 * Slow, hysteretic controller. It changes the active prefix only after enough
 * asynchronous GPU samples, so a single shader compilation or OS stall cannot
 * throw away most of the particles.
 */
class AdaptiveBodyController(
    initial: Int,
    private val capacity: Int,
    private val targetMillis: Double,
    minimum: Int = 32_768,
) {
    private val floor = minimum.coerceAtMost(initial).coerceAtMost(capacity)
    var activeBodies: Int = initial
        private set

    private var samples = 0
    private var smoothedMillis = targetMillis
    private var cooldown = 0

    fun record(gpuMillis: Double, allowAdjustment: Boolean = true): Int {
        if (!gpuMillis.isFinite() || gpuMillis <= 0.0) return activeBodies
        smoothedMillis += 0.08 * (gpuMillis - smoothedMillis)
        if (!allowAdjustment) return activeBodies
        samples++
        if (cooldown > 0) {
            cooldown--
            return activeBodies
        }
        if (samples < 45) return activeBodies

        val candidate = when {
            smoothedMillis > targetMillis * 1.08 ->
                (activeBodies * max(0.70, targetMillis / smoothedMillis * 0.94)).toInt()
            smoothedMillis < targetMillis * 0.72 && activeBodies < capacity ->
                (activeBodies * 1.12).toInt()
            else -> activeBodies
        }.coerceIn(floor, capacity)
        if (candidate != activeBodies) {
            activeBodies = candidate
            cooldown = 30
        }
        samples = 0
        return activeBodies
    }

    fun smoothedGpuMillis(): Double = smoothedMillis
}
