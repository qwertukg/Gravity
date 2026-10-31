package dev.gravity

/**
 * Column of scenario buttons in the top-right corner, in window pixels: the
 * test scenarios, under a heading per group (TestGroup), then the game.
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

    /** A group's heading: text alone, not a button. */
    private class Heading(val title: String, val left: Float, val top: Float)

    private val groups = TestGroup.entries.map { group -> group.title to TestScenario.entries.filter { it.group == group } }
        .filter { it.second.isNotEmpty() } + (GAME_TITLE to listOf(NavalScene))
    private val buttonWidth = groups.flatMap { (title, scenarios) -> scenarios.map { it.title } + title }
        .maxOf { UiLayer.panelWidth(it) }
    private val buttonHeight = UiLayer.panelHeight("", config.overlayLineHeightPixels)
    private val headings = ArrayList<Heading>()
    private val buttons = ArrayList<Button>()

    init {
        val left = viewportWidth - config.overlayMarginPixels - buttonWidth
        var top = config.overlayMarginPixels
        for ((index, entry) in groups.withIndex()) {
            val (title, scenarios) = entry
            if (index > 0) top += SECTION_GAP_PIXELS
            headings += Heading(title, left, top)
            top += buttonHeight + GAP_PIXELS
            for (scenario in scenarios) {
                buttons += Button(scenario, left, top, left + buttonWidth, top + buttonHeight)
                top += buttonHeight + GAP_PIXELS
            }
        }
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
            for (heading in headings) layer.text(heading.title, heading.left, heading.top)
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
        const val GAME_TITLE = "Game"
        val HOVER_COLOR = UiLayer.rgba(60, 60, 60, 210)
        val ACTIVE_COLOR = UiLayer.rgba(0, 75, 0, 220)
    }
}
