package dev.gravity

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sqrt

data class PixelOffset(val x: Float, val y: Float)

object CircularProjectile {
    fun offsets(
        contactDiameterPixels: Float,
        projectileDiameterPixels: Float,
    ): List<PixelOffset> {
        require(contactDiameterPixels > 0f)
        require(projectileDiameterPixels >= contactDiameterPixels)
        val bodyRadius = 0.5f * contactDiameterPixels
        val availableRadius = 0.5f * projectileDiameterPixels - bodyRadius
        val rowStep = contactDiameterPixels * (sqrt(3.0) * 0.5).toFloat()
        val offsets = ArrayList<PixelOffset>()

        var row = 0
        var y = -availableRadius
        while (y <= availableRadius) {
            val rowOffset = if (row and 1 == 0) 0f else 0.5f * contactDiameterPixels
            var x = -availableRadius + rowOffset
            while (x <= availableRadius) {
                if (x * x + y * y <= availableRadius * availableRadius) {
                    offsets += PixelOffset(x, y)
                }
                x += contactDiameterPixels
            }
            y += rowStep
            row++
        }
        return offsets
    }

    /** Smallest diameter, on a [DIAMETER_STEP_PIXELS] grid, holding [bodyCount] bodies. */
    fun diameterForBodyCount(contactDiameterPixels: Float, bodyCount: Int): Float {
        require(contactDiameterPixels > 0f)
        require(bodyCount > 0)
        // Each hexagonally packed body covers d^2 * sqrt(3) / 2 of the disc its
        // centres may occupy; boundary losses keep the true count just below.
        val bodyArea = contactDiameterPixels * contactDiameterPixels * sqrt(3.0) * 0.5
        val estimate = 2.0 * sqrt(bodyCount * bodyArea / PI) + contactDiameterPixels
        var diameter = max(
            contactDiameterPixels.toDouble(),
            estimate - 2.0 * contactDiameterPixels,
        ).toFloat()
        while (diameter > contactDiameterPixels &&
            offsets(contactDiameterPixels, diameter).size >= bodyCount) {
            diameter = max(contactDiameterPixels, diameter - contactDiameterPixels)
        }
        while (offsets(contactDiameterPixels, diameter).size < bodyCount) {
            diameter += DIAMETER_STEP_PIXELS
        }
        return diameter
    }

    private const val DIAMETER_STEP_PIXELS = 0.05f
}
