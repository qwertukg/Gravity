package dev.gravity

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
}
