package dev.gravity

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The naval battle's overlay: each player's aim as the dashed flight of their
 * first gun's shell, in their colour, and text panels, top centre: keys, ship
 * class, guns and range, what is left of the ship, the guns' elevation and the
 * experience towards the next class; once a ship is destroyed, the winner and
 * what each player gained. Everything else that moves is the simulation.
 */
internal class NavalHud(
    private val viewportWidth: Int,
    private val viewportHeight: Int,
    private val config: PresentationConfig,
) : AutoCloseable {
    private val layer = UiLayer(viewportWidth, viewportHeight, MAXIMUM_QUADS)

    fun render(battle: NavalBattle) {
        val margin = config.overlayMarginPixels
        val lineHeight = config.overlayLineHeightPixels
        val center = 0.5f * viewportWidth
        layer.begin()
        battle.players.forEachIndexed { index, player -> dashes(battle.trajectory(player), PLAYER_COLORS[index]) }
        battle.players.forEachIndexed { index, player ->
            val panel = panel(battle, player)
            val width = UiLayer.panelWidth(panel)
            val left = if (index == 0) center - margin - width else center + margin
            val bottom = margin + UiLayer.panelHeight(panel, lineHeight)
            layer.panel(left, margin, left + width, bottom)
            // A stripe of the player's colour, as their aim is drawn.
            layer.panel(left, bottom, left + width, bottom + STRIPE_PIXELS, PLAYER_COLORS[index])
            layer.text(panel, left, margin)
        }
        if (battle.over) {
            val result = battle.winner?.let { "${it.name} wins!" } ?: "Draw!"
            val gains = battle.players.joinToString("\n") { player ->
                "${player.name}: +${player.gained.roundToInt()} XP" +
                    (player.evolved?.let { " - evolves into a ${it.title}!" } ?: "")
            }
            val banner = "$result\n$gains\nR: next battle"
            val width = BANNER_SCALE * UiLayer.panelWidth(banner)
            val height = BANNER_SCALE * UiLayer.panelHeight(banner, lineHeight)
            val top = 4f * margin + 9f * lineHeight
            layer.panel(center - 0.5f * width, top, center + 0.5f * width, top + height)
            layer.text(banner, center - 0.5f * width, top, BANNER_SCALE)
        }
        layer.end()
        layer.draw()
    }

    private fun panel(battle: NavalBattle, player: NavalBattle.Player): String {
        val type = player.shipClass
        val next = player.career.shipClass.next
        return String.format(
            Locale.ROOT,
            "%s\n%s, range %d px\nShip: %d%%\nGuns: %d of %d, %d deg\n%s\nMissile: %s\n%s",
            player.name,
            type.title,
            battle.range(player).roundToInt(),
            (player.integrity * 100f).roundToInt(),
            player.gunsLeft,
            type.gunCount,
            Math.toDegrees(player.elevation.toDouble()).roundToInt(),
            when {
                player.broken -> "BROKEN"
                player.destroyed -> "DESTROYED"
                player.gunsLeft == 0 -> "No guns"
                player.reload > 0f -> "Loading"
                else -> "Ready"
            },
            when {
                player.missile != null -> "in flight (${player.missile!!.phase.name.lowercase()})"
                player.missileReload > 0f -> "loading, ${ceil(player.missileReload).toInt()} s"
                else -> "ready"
            },
            if (next == null) "Top class" else
                "XP: ${player.career.experience.roundToInt()} / ${player.career.shipClass.experienceToEvolve.roundToInt()} to ${next.title}",
        )
    }

    /** A dashed line along [points] (physics px). */
    private fun dashes(points: List<PixelOffset>, color: Int) {
        var travelled = 0f
        for (index in 1 until points.size) {
            val from = points[index - 1]
            val to = points[index]
            val length = hypot(to.x - from.x, to.y - from.y)
            var start = 0f
            while (start < length) {
                val phase = (travelled + start) % DASH_PERIOD_PIXELS
                val end = minOf(length, start + if (phase < DASH_PIXELS) DASH_PIXELS - phase else DASH_PERIOD_PIXELS - phase)
                if (phase < DASH_PIXELS) {
                    val a = start / length
                    val b = end / length
                    layer.segment(
                        from.x + (to.x - from.x) * a, viewportHeight - (from.y + (to.y - from.y) * a),
                        from.x + (to.x - from.x) * b, viewportHeight - (from.y + (to.y - from.y) * b),
                        LINE_PIXELS, color,
                    )
                }
                start = end
            }
            travelled += length
        }
    }

    override fun close() = layer.close()

    private companion object {
        const val MAXIMUM_QUADS = 16384
        const val BANNER_SCALE = 2f
        const val STRIPE_PIXELS = 3f
        const val DASH_PIXELS = 9f
        const val DASH_PERIOD_PIXELS = 15f
        const val LINE_PIXELS = 2f
        /** WASD amber, arrows cyan. */
        val PLAYER_COLORS = intArrayOf(UiLayer.rgba(255, 186, 60, 210), UiLayer.rgba(90, 220, 255, 210))
    }
}
