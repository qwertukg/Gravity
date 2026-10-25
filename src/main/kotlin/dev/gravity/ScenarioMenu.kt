package dev.gravity

/**
 * Column of scenario buttons in the top-right corner, in window pixels: the
 * cosmic scenarios, then, a little apart, the experiments with blocks.
 */
class ScenarioMenu(
    viewportWidth: Int,
    viewportHeight: Int,
    config: PresentationConfig,
) : AutoCloseable {
    private class Button(
        val scenario: MenuScenario,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        fun contains(x: Float, y: Float) = x in left..<right && y in top..<bottom
    }

    private val scenarios: List<MenuScenario> = Scenario.entries + BlockScenario.entries
    private val buttonWidth = scenarios.maxOf { UiLayer.panelWidth(it.title) }
    private val buttonHeight = UiLayer.panelHeight("", config.overlayLineHeightPixels)
    private val buttons = scenarios.mapIndexed { index, scenario ->
        val left = viewportWidth - config.overlayMarginPixels - buttonWidth
        val section = if (scenario is BlockScenario) SECTION_GAP_PIXELS else 0f
        val top = config.overlayMarginPixels + index * (buttonHeight + GAP_PIXELS) + section
        Button(scenario, left, top, left + buttonWidth, top + buttonHeight)
    }
    private val layer = UiLayer(viewportWidth, viewportHeight, MAXIMUM_QUADS)
    private var renderedActive: MenuScenario? = null
    private var renderedHover: MenuScenario? = null
    private var built = false

    /** Scenario under a window-space point (origin top-left), if any. */
    fun scenarioAt(x: Double, y: Double): MenuScenario? =
        buttons.firstOrNull { it.contains(x.toFloat(), y.toFloat()) }?.scenario

    fun render(active: MenuScenario?, cursorX: Double, cursorY: Double) {
        val hover = scenarioAt(cursorX, cursorY)
        if (!built || active != renderedActive || hover != renderedHover) {
            layer.begin()
            for (button in buttons) {
                val color = when (button.scenario) {
                    active -> ACTIVE_COLOR
                    hover -> HOVER_COLOR
                    else -> UiLayer.PANEL_COLOR
                }
                layer.panel(button.left, button.top, button.right, button.bottom, color)
                layer.text(button.scenario.title, button.left, button.top)
            }
            layer.end()
            renderedActive = active
            renderedHover = hover
            built = true
        }
        layer.draw()
    }

    override fun close() = layer.close()

    private companion object {
        const val GAP_PIXELS = 3f
        const val SECTION_GAP_PIXELS = 12f
        const val MAXIMUM_QUADS = 8192
        val HOVER_COLOR = UiLayer.rgba(60, 60, 60, 210)
        val ACTIVE_COLOR = UiLayer.rgba(0, 75, 0, 220)
    }
}
