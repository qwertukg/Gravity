package dev.gravity

object ViewportMapping {
    fun domainX(cursorX: Double, viewportWidth: Int): Float =
        (cursorX / viewportWidth).toFloat().coerceIn(0f, 1f)

    fun domainY(cursorY: Double, viewportWidth: Int, viewportHeight: Int): Float =
        (0.5 + (viewportHeight * 0.5 - cursorY) / viewportWidth)
            .toFloat()
            .coerceIn(0f, 1f)
}
