package dev.gravity

import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.lwjgl.opengl.GL11.GL_NO_ERROR
import org.lwjgl.opengl.GL11.glGetError

/**
 * The naval battle on the screen it is authored for: ships of every class
 * float upright on the pool, engines move them, shells fly, go off where they
 * hit and break the ship they hit.
 */
class NavalBattleIntegrationTest {
    private val width = 3440
    private val height = 1440
    private val defaults = GravityConfig()
    private val space = ScenarioSpace(
        width.toFloat(), height.toFloat(), defaults.gravity.gravitationalConstant, defaults.collisions.contactDiameterPixels,
    )

    @Test
    fun `ships of every class float upright`() {
        for (type in ShipClass.entries) {
            val careers = List(2) { career(type) }
            battle(careers, steps = 480) { step, battle ->
                if (step % 120 == 119) report(type.title, step, battle)
            }.let { battle ->
                for (player in battle.players) {
                    val ship = player.ship!!
                    assertEquals(type, player.shipClass)
                    assertTrue(abs(atan2(ship.sin, ship.cos)) < 0.15f, "${type.title} upright: ${atan2(ship.sin, ship.cos)} rad")
                    assertTrue(ship.y > NavalScene.surface(space) - space.scaled(type.height), "${type.title} floats: centre ${ship.y} px")
                    assertEquals(1f, player.health, "${type.title} unharmed")
                }
            }
        }
    }

    @Test
    fun `an engine moves its ship`() {
        val battle = battle(List(2) { NavalCareer() }, steps = 480) { step, battle ->
            battle.wasd.right = step >= 120
        }
        val left = battle.players[0].ship!!
        val right = battle.players[1].ship!!
        assertTrue(left.velocityX > 50f, "driven: ${left.velocityX} px/s")
        assertTrue(abs(right.velocityX) < 30f, "idle: ${right.velocityX} px/s")
    }

    @Test
    fun `salvos go off and break the enemy ship`() {
        // Salvos of the right player's cruiser at rising power towards the left ship,
        // then time for the last to land.
        val battle = battle(List(2) { career(ShipClass.CRUISER) }, steps = 2400) { step, battle ->
            val salvo = (step - 240) / 240
            val within = (step - 240) % 240
            battle.arrows.fire = step in 240 until 1920 && within < 100 + 8 * salvo
            if (step % 240 == 239) report("salvo", step, battle)
        }
        val left = battle.players[0]
        println("fired=${battle.shellsFired} detonations=${battle.detonations} left health=${left.health}")
        assertEquals(4 * 7, battle.shellsFired, "salvos fired")
        assertEquals(battle.shellsFired, battle.detonations, "all gone off")
        assertTrue(left.health < 0.97f, "the enemy ship was hit: ${left.health}")
    }

    private fun career(type: ShipClass) = NavalCareer().also { career ->
        while (career.shipClass != type) career.gain(career.shipClass.experienceToEvolve)
    }

    private fun report(label: String, step: Int, battle: NavalBattle) {
        val ships = battle.players.joinToString("  ") { player ->
            val ship = player.ship
            if (ship == null) "-" else String.format(
                Locale.ROOT, "(%.0f, %.0f) v=(%.0f, %.0f) angle=%.3f mass=%.0f health=%.3f",
                ship.x, ship.y, ship.velocityX, ship.velocityY, atan2(ship.sin, ship.cos), ship.mass, player.health,
            )
        }
        println("$label step=$step $ships fired=${battle.shellsFired} detonations=${battle.detonations}")
    }

    private fun battle(careers: List<NavalCareer>, steps: Int, script: (Int, NavalBattle) -> Unit): NavalBattle {
        val setup = NavalScene.setup(space, 20261031L)
        val pool = setup.bodies.size / 4
        val config = defaults.copy(
            particles = defaults.particles.copy(capacity = 400_000, initialBodies = pool, seed = 20261031L),
        )
        lateinit var result: NavalBattle
        CollisionGpuContext().use {
            GravityPipeline(config, width, height, scenarioState = setup.bodies, bounded = true).use { pipeline ->
                pipeline.mutualGravity = false
                pipeline.liquid = true
                val field = config.gravity.uniformFieldAcceleration * height / 1440f
                pipeline.fieldAccelerationY = -field
                val battle = NavalBattle(space, config.collisions.timeStep, config.projectiles.blockDensity, field, careers)
                var active = pool + battle.spawnShips(pipeline, pool)
                pipeline.initializeAcceleration(active)
                repeat(steps) { step ->
                    script(step, battle)
                    active = battle.beforeStep(pipeline, active)
                    pipeline.step(active)
                    battle.afterStep(pipeline, active)
                }
                result = battle
            }
            assertEquals(GL_NO_ERROR, glGetError())
        }
        return result
    }
}
