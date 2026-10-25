package dev.gravity

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Smoothing kernels of liquid mode in pixels, matching liquid_kernel.glsl:
 * the 2D poly6 kernel for density and the 2D spiky kernel for its gradient.
 * The rest density is that of the hexagonal packing every body settles into,
 * so the liquid keeps the spacing of ordinary bodies and a block lattice
 * reads as liquid of the same volume.
 */
class LiquidKernel(val contactDiameterPixels: Float, kernelRadiusDiameters: Float) {
    val radiusPixels = contactDiameterPixels * kernelRadiusDiameters
    val poly6Coefficient = (4.0 / (PI * radiusPixels.toDouble().pow8())).toFloat()
    val spikyGradientCoefficient = (-30.0 / (PI * radiusPixels.toDouble().pow5())).toFloat()

    fun poly6(distanceSquared: Double): Double {
        val x = radiusPixels.toDouble() * radiusPixels - distanceSquared
        return if (x > 0.0) poly6Coefficient * x * x * x else 0.0
    }

    /** Magnitude of the spiky gradient at [distance]. */
    fun spikyGradient(distance: Double): Double {
        if (distance <= 0.0 || distance >= radiusPixels) return 0.0
        val x = radiusPixels - distance
        return -spikyGradientCoefficient * x * x
    }

    private val lattice: List<Pair<Double, Double>> = run {
        val d = contactDiameterPixels.toDouble()
        val rowStep = d * sqrt(3.0) * 0.5
        val rows = ceil(radiusPixels / rowStep).toInt() + 1
        val columns = ceil(radiusPixels / d).toInt() + 1
        buildList {
            for (row in -rows..rows) {
                val shift = if (row and 1 == 0) 0.0 else 0.5 * d
                for (column in -columns..columns) add(column * d + shift to row * rowStep)
            }
        }
    }

    /** Number density sum of a body inside the hexagonal packing, itself included. */
    val restDensity: Float = lattice.sumOf { (x, y) -> poly6(x * x + y * y) }.toFloat()

    /**
     * Constraint relaxation (the epsilon of position-based fluids): a fixed
     * fraction of the gradient sum of a body inside the packing, so it scales
     * with the body size.
     */
    val relaxation: Float = (
        RELAXATION_FRACTION * lattice.sumOf { (x, y) ->
            val gradient = spikyGradient(sqrt(x * x + y * y)) / restDensity
            gradient * gradient
        }
        ).toFloat()

    private companion object {
        const val RELAXATION_FRACTION = 0.01

        fun Double.pow5() = this * this * this * this * this
        fun Double.pow8() = pow5() * this * this * this
    }
}
