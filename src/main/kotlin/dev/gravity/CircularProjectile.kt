package dev.gravity

import kotlin.math.sqrt

data class PixelOffset(val x: Float, val y: Float)

object CircularProjectile {
    const val DEFAULT_DIAMETER_PIXELS = 50f
    const val VELOCITY_PER_DRAG_PIXEL = 4f

    fun diameterForDigit(digit: Int): Float {
        require(digit in 0..9)
        return if (digit == 0) 100f else digit * 10f
    }

    fun offsets(
        contactDiameterPixels: Float,
        projectileDiameterPixels: Float = DEFAULT_DIAMETER_PIXELS,
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
}
