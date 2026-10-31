package dev.gravity

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Text panels of the naval battle, top centre: each player's keys, ship class,
 * guns and range, what is left of their ship, the guns' elevation, the power
 * of the salvo and the experience towards the next class; once a ship is
 * destroyed, the winner and what each player gained. Text only: everything
 * that moves on screen is the simulation.
 */
internal class NavalHud(
    private val viewportWidth: Int,
    viewportHeight: Int,
    private val config: PresentationConfig,
) : AutoCloseable {
    private val layer = UiLayer(viewportWidth, viewportHeight, MAXIMUM_QUADS)
    private var rendered = ""

    fun render(battle: NavalBattle) {
        val panels = battle.players.map { player ->
            val type = player.shipClass
            val career = player.career
            val next = career.shipClass.next
            String.format(
                Locale.ROOT,
                "%s\n%s: %d gun%s, range %d px\nShip: %d%%\nGun: %d deg\nPower: %d%%\n%s\n%s",
                player.name,
                type.title,
                type.gunCount,
                if (type.gunCount == 1) "" else "s",
                battle.range(player).roundToInt(),
                (player.health * 100f).roundToInt(),
                Math.toDegrees(player.elevation.toDouble()).roundToInt(),
                (player.power * 100f).roundToInt(),
                when {
                    player.destroyed -> "DESTROYED"
                    player.charge != null -> "Charging"
                    player.reload > 0f -> "Loading"
                    else -> "Ready"
                },
                if (next == null) "Top class" else
                    "XP: ${career.experience.roundToInt()} / ${career.shipClass.experienceToEvolve.roundToInt()} to ${next.title}",
            )
        }
        val banner = if (battle.over) {
            val result = battle.winner?.let { "${it.name} wins!" } ?: "Draw!"
            val gains = battle.players.joinToString("\n") { player ->
                "${player.name}: +${player.gained.roundToInt()} XP" +
                    (player.evolved?.let { " - evolves into a ${it.title}!" } ?: "")
            }
            "$result\n$gains\nR: next battle"
        } else ""
        val text = panels.joinToString("|") + banner
        if (text != rendered) {
            val margin = config.overlayMarginPixels
            val lineHeight = config.overlayLineHeightPixels
            val center = 0.5f * viewportWidth
            layer.begin()
            panels.forEachIndexed { index, panel ->
                val width = UiLayer.panelWidth(panel)
                val left = if (index == 0) center - margin - width else center + margin
                layer.panel(left, margin, left + width, margin + UiLayer.panelHeight(panel, lineHeight))
                layer.text(panel, left, margin)
            }
            if (banner.isNotEmpty()) {
                val width = BANNER_SCALE * UiLayer.panelWidth(banner)
                val height = BANNER_SCALE * UiLayer.panelHeight(banner, lineHeight)
                val top = 4f * margin + 8f * lineHeight
                layer.panel(center - 0.5f * width, top, center + 0.5f * width, top + height)
                layer.text(banner, center - 0.5f * width, top, BANNER_SCALE)
            }
            layer.end()
            rendered = text
        }
        layer.draw()
    }

    override fun close() = layer.close()

    private companion object {
        const val MAXIMUM_QUADS = 8192
        const val BANNER_SCALE = 2f
    }
}
