package dev.gravity

import java.util.Locale

class FpsOverlay(
    private val viewportWidth: Int,
    private val viewportHeight: Int,
    private val config: PresentationConfig,
    projectileConfig: ProjectileConfig,
) : AutoCloseable {
    private val controlText =
        "LMB drag: aim / fire\n" +
            "RMB drag: glued rectangular block\n" +
            "1-9: bullet size step ${projectileConfig.digitDiameterStepPixels.toInt()} px\n" +
            "0: bullet size ${projectileConfig.maximumDiameterPixels.toInt()} px\n" +
            "+: bullet of ${projectileConfig.plusKeyBodyCount} bodies\n" +
            "Mouse wheel: time speed\n" +
            "P: pause / resume\n" +
            "S: stop all bodies\n" +
            "B: walls / torus\n" +
            "Arrows: uniform gravity on / off\n" +
            "C: clear all bodies\n" +
            "G: gravity between bodies on / off\n" +
            "R: reset\n" +
            "Esc: exit"
    private val layer = UiLayer(
        viewportWidth,
        viewportHeight,
        config.overlayMaximumCharacters * 16,
    )
    private var renderedFpsBits = Long.MIN_VALUE
    private var renderedActiveBodies = -1
    private var renderedProjectileBodies = -1
    private var renderedTimeScaleBits = Long.MIN_VALUE
    private var renderedFutureSpeedBits = Long.MIN_VALUE
    private var renderedBlockBodies: Int? = -1
    private var renderedBounded = false
    private var renderedField = ""
    private var renderedMutualGravity = true

    fun render(
        fps: Double,
        activeBodies: Int,
        projectileBodies: Int,
        timeScale: Double,
        futureProjectileSpeed: Double?,
        blockBodies: Int?,
        bounded: Boolean,
        field: String,
        mutualGravity: Boolean,
    ) {
        val fpsBits = fps.toRawBits()
        val timeScaleBits = timeScale.toRawBits()
        val futureSpeedBits = futureProjectileSpeed?.toRawBits() ?: NO_SPEED_BITS
        if (fpsBits != renderedFpsBits ||
            activeBodies != renderedActiveBodies ||
            projectileBodies != renderedProjectileBodies ||
            timeScaleBits != renderedTimeScaleBits ||
            futureSpeedBits != renderedFutureSpeedBits ||
            blockBodies != renderedBlockBodies ||
            bounded != renderedBounded ||
            field != renderedField ||
            mutualGravity != renderedMutualGravity
        ) {
            val projectileSpeed = futureProjectileSpeed?.let {
                String.format(Locale.ROOT, "%.1f px/s", it)
            } ?: "-"
            val block = blockBodies?.toString() ?: "-"
            val text = String.format(
                Locale.ROOT,
                "FPS: %.1f\nBodies: %d\nBullet bodies: %d\ndt: %.0f%%\nBullet speed: %s\nBlock bodies: %s\nSpace: %s\nField: %s\nGravity: %s",
                fps,
                activeBodies,
                projectileBodies,
                timeScale * 100.0,
                projectileSpeed,
                block,
                if (bounded) "walls" else "torus",
                field,
                if (mutualGravity) "on" else "off",
            )
            val margin = config.overlayMarginPixels
            val lineHeight = config.overlayLineHeightPixels
            layer.begin()
            // Information block in the top-left corner.
            layer.panel(
                margin,
                margin,
                margin + UiLayer.panelWidth(text),
                margin + UiLayer.panelHeight(text, lineHeight),
            )
            layer.text(text, margin, margin)
            // Controls block in the bottom-left corner.
            val controlsTop = viewportHeight - margin - UiLayer.panelHeight(controlText, lineHeight)
            layer.panel(
                margin,
                controlsTop,
                margin + UiLayer.panelWidth(controlText),
                viewportHeight - margin,
            )
            layer.text(controlText, margin, controlsTop)
            layer.end()
            renderedFpsBits = fpsBits
            renderedActiveBodies = activeBodies
            renderedProjectileBodies = projectileBodies
            renderedTimeScaleBits = timeScaleBits
            renderedFutureSpeedBits = futureSpeedBits
            renderedBlockBodies = blockBodies
            renderedBounded = bounded
            renderedField = field
            renderedMutualGravity = mutualGravity
        }

        layer.draw()
    }

    override fun close() = layer.close()

    private companion object {
        const val NO_SPEED_BITS = Long.MIN_VALUE + 1
    }
}
