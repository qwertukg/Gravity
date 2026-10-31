package dev.gravity

import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.lwjgl.opengl.GL11.GL_NO_ERROR
import org.lwjgl.opengl.GL11.glGetError

/**
 * The naval battle on the screen it is authored for: ships of every class
 * float upright on the pool with their barrels held at their pivots, barrels
 * keep to their arcs so a ship never shoots itself, engines move ships,
 * salvos fly in arcs, go off where they hit with fire, foam the water and
 * break the ship they hit, a missile climbs, turns over and flies into the
 * enemy on its jets, and a duel ends with a destroyed ship. `NAVAL_VIDEO=1`
 * records the salvos and the missile to build/test-videos.
 */
class NavalBattleIntegrationTest {
    private val width = 3440
    private val height = 1440
    private val defaults = GravityConfig()
    private val space = ScenarioSpace(
        width.toFloat(), height.toFloat(), defaults.gravity.gravitationalConstant, defaults.collisions.contactDiameterPixels,
    )
    private val field = defaults.gravity.uniformFieldAcceleration * height / 1440f
    private val video = System.getenv("NAVAL_VIDEO") == "1"
    /** How far from the enemy's centre (px at 1440) a missile may come down. */
    private val nearMiss = 400f

    @Test
    fun `ships of every class float upright with their guns on`() {
        for (type in ShipClass.entries) {
            battle(List(2) { career(type) }, steps = 480) { _, _, _ -> }.let { battle ->
                report(type.title, 480, battle)
                for (player in battle.players) {
                    val ship = player.ship!!
                    assertEquals(type, player.shipClass)
                    assertTrue(abs(atan2(ship.sin, ship.cos)) < 0.15f, "${type.title} upright: ${atan2(ship.sin, ship.cos)} rad")
                    assertTrue(ship.y > battle.surface - space.scaled(type.height), "${type.title} floats: centre ${ship.y} px")
                    assertEquals(1f, player.integrity, "${type.title} unharmed")
                    for (gun in player.guns) {
                        assertTrue(pivotGap(player, gun) < 1.5f, "${type.title} barrel on its pivot: ${pivotGap(player, gun)} px")
                        val aim = barrelAngle(player, gun)
                        assertTrue(abs(aim - gun.elevation!!) < 0.15f, "${type.title} barrel aims at ${gun.elevation}: $aim rad")
                    }
                }
            }
        }
    }

    @Test
    fun `guns keep to their arcs and never shoot their own ship`() {
        for (type in listOf(ShipClass.FRIGATE, ShipClass.CRUISER)) {
            // All the way down, salvos; all the way up, salvos.
            val battle = battle(List(2) { career(type) }, steps = 1500) { step, battle, _ ->
                battle.wasd.down = step in 60 until 400
                battle.wasd.up = step in 700 until 1100
                // Further apart than the slowest reload (1.5 s).
                battle.wasd.fire = step in listOf(420, 620, 1150, 1350)
                if (step == 419 || step == 1149) {
                    val player = battle.players[0]
                    for ((index, gun) in player.guns.withIndex()) {
                        println("${type.title} gun $index wants ${gun.elevation} aims ${barrelAngle(player, gun)} gap ${pivotGap(player, gun)}")
                        assertTrue(abs(barrelAngle(player, gun) - gun.elevation!!) < 0.12f, "${type.title} gun $index holds its arc")
                    }
                }
            }
            val player = battle.players[0]
            println("${type.title}: fired=${battle.shellsFired} integrity=${player.integrity}")
            assertTrue(battle.shellsFired >= 4 * type.gunCount - 1, "${type.title} fired: ${battle.shellsFired}")
            assertTrue(player.integrity > 0.99f, "${type.title} never hit itself: ${player.integrity}")
        }
    }

    @Test
    fun `an engine moves its ship`() {
        val battle = battle(List(2) { NavalCareer() }, steps = 480) { step, battle, _ ->
            battle.wasd.right = step >= 120
        }
        val left = battle.players[0].ship!!
        val right = battle.players[1].ship!!
        assertTrue(left.velocityX > 50f, "driven: ${left.velocityX} px/s")
        assertTrue(abs(right.velocityX) < 30f, "idle: ${right.velocityX} px/s")
    }

    @Test
    fun `salvos fly in arcs, go off with fire, foam the water and break the enemy ship`() {
        var hottest = 0
        var foamiest = 0
        // The right cruiser aims its high arc at the left one and fires a salvo
        // every 200 steps, holding its place against the recoil on its engine;
        // then time for the last to land.
        var station = 0f
        val battle = battle(List(2) { career(ShipClass.CRUISER) }, steps = 2800, record = video) { step, battle, probe ->
            aim(battle, battle.players[1])
            battle.players[1].ship?.let { ship ->
                if (station == 0f) station = ship.x
                val wanted = 0.5f * (station - ship.x) - ship.velocityX
                battle.arrows.left = wanted < -10f
                battle.arrows.right = wanted > 10f
            }
            battle.arrows.fire = step in 240 until 1900 && (step - 240) % 200 < 5
            if (step % 50 == 25) {
                val (hot, foam) = probe()
                hottest = maxOf(hottest, hot)
                foamiest = maxOf(foamiest, foam)
            }
            if (step % 240 == 239) report("salvo", step, battle)
        }
        val (left, right) = battle.players
        println("fired=${battle.shellsFired} detonations=${battle.detonations} left integrity=${left.integrity} hot=$hottest foam=$foamiest")
        assertTrue(battle.shellsFired >= 9, "salvos fired: ${battle.shellsFired}")
        assertEquals(battle.shellsFired, battle.detonations, "all gone off")
        assertTrue(left.integrity < 0.9f || left.destroyed, "the enemy ship was hit: ${left.integrity}")
        assertTrue(right.integrity > 0.99f, "the firing ship unharmed: ${right.integrity}")
        assertTrue(hottest > 500, "blasts light fire: $hottest hot bodies")
        assertTrue(foamiest > 500, "blasts foam the water: $foamiest foaming bodies")
    }

    @Test
    fun `a missile climbs, turns over on its jets and flies into the enemy`() {
        var top = 0f
        var last: PixelOffset? = null
        val phases = LinkedHashMap<NavalMissile.Phase, Int>()
        var hottest = 0
        val battle = battle(List(2) { career(ShipClass.DESTROYER) }, steps = 1800, record = video, from = 100) { step, battle, probe ->
            battle.arrows.missile = step in 120 until 125
            battle.players[1].missile?.let { missile ->
                phases.putIfAbsent(missile.phase, step)
                missile.nose()?.let { top = maxOf(top, it.y); last = it }
                if (step % 20 == 0) println(String.format(Locale.ROOT, "missile step=%d phase=%s nose=%s v=(%.0f, %.0f) angle=%.2f",
                    step, missile.phase, missile.nose(), missile.reading?.velocityX, missile.reading?.velocityY,
                    missile.reading?.let { atan2(it.sin, it.cos) }))
            }
            if (step % 40 == 0 && battle.missileBlasts == 0) hottest = maxOf(hottest, probe().first)
        }
        val (left, right) = battle.players
        println("phases=$phases top=$top blasts=${battle.missileBlasts} left=${left.integrity} right=${right.integrity} gas=$hottest")
        assertEquals(1, battle.missilesLaunched)
        assertEquals(listOf(NavalMissile.Phase.EJECTED, NavalMissile.Phase.BOOST, NavalMissile.Phase.TURN, NavalMissile.Phase.CRUISE), phases.keys.toList())
        assertTrue(top > battle.surface + 250f, "climbed: nose up to $top px")
        assertTrue(hottest > 200, "flew on jets of gas: $hottest hot bodies")
        assertEquals(1, battle.missileBlasts, "went off")
        // It flies roughly, not precisely: it goes off near the enemy, not necessarily on it.
        val enemy = left.ship!!
        assertTrue(abs(last!!.x - enemy.x) < space.scaled(nearMiss), "went off near the enemy: at $last, the enemy at ${enemy.x}")
        assertTrue(right.integrity > 0.99f, "the launching ship unharmed: ${right.integrity}")
    }

    @Test
    fun `a missile strikes twice the area of a shell for half the damage`() {
        for (type in listOf(ShipClass.CORVETTE, ShipClass.CRUISER)) {
            // Fracture is chaotic: three of each.
            val shell = List(3) { strike(type, missile = false) }.let { Strike(it.sumOf { s -> s.chipped }, it.sumOf { s -> s.area.toDouble() }.toFloat()) }
            val missile = List(3) { strike(type, missile = true) }.let { Strike(it.sumOf { s -> s.chipped }, it.sumOf { s -> s.area.toDouble() }.toFloat()) }
            val damage = missile.chipped.toFloat() / shell.chipped
            val area = missile.area / shell.area
            println(String.format(Locale.ROOT, "%s shell: chipped=%d area=%.0f  missile: chipped=%d area=%.0f  damage x%.2f area x%.2f",
                type.title, shell.chipped, shell.area, missile.chipped, missile.area, damage, area))
            assertTrue(damage in 0.3f..0.75f, "${type.title}: missile damage x$damage of a shell's")
            assertTrue(area in 1.5f..2.8f, "${type.title}: missile area x$area of a shell's")
        }
    }

    /** What a blast broke off a ship: members chipped into grains, and the area the nearer half of them lay in (px²). */
    private class Strike(val chipped: Int, val area: Float)

    /**
     * Sets off a shell of [type]'s guns touching the left ship's open deck
     * nearest its middle, or its [missile] diving at 40° with its nose its fuse's
     * distance above it, as each goes off there; counts what broke off within
     * four seconds.
     */
    private fun strike(type: ShipClass, missile: Boolean): Strike {
        val at = 300
        // The missile is glued upright and turned over to its dive by spin, 50° in a tenth of a second.
        val turnSteps = 12
        val dive = 50f * PI.toFloat() / 180f
        val slot = NavalGpu.SHELLS - 1
        var members = IntArray(0)
        var restX = FloatArray(0)
        var restY = FloatArray(0)
        var centerX = 0f
        var centerY = 0f
        var missileGroup = -1
        var result = Strike(0, 0f)
        battle(List(2) { career(type) }, steps = at + 480, hook = { step, battle, pipeline, active ->
            val player = battle.players[0]
            when (step) {
                at -> {
                    val ship = player.ship!!
                    val bodies = CollisionGpuAccess.floats(pipeline, "bodies", active * 4)
                    val materials = CollisionGpuAccess.floats(pipeline, "materials", active * 4)
                    members = (0 until active).filter { materials[4 * it + 2] != 0f && materials[4 * it + 3] == player.group.toFloat() }
                        .toIntArray()
                    restX = FloatArray(members.size) { bodies[4 * members[it]] * width }
                    restY = FloatArray(members.size) { bodies[4 * members[it] + 1] * height }
                    // The open deck nearest the middle: the lowest top within a third of the ship's length.
                    val diameter = space.contactDiameterPixels
                    val half = space.scaled(type.length) / 3f
                    val tops = (-half.toInt()..half.toInt() step 4).map { dx ->
                        val x = ship.x + dx
                        x to members.indices.filter { abs(restX[it] - x) < 2f }.maxOf { restY[it] }
                    }
                    val deck = tops.minOf { it.second }
                    val (x, top) = tops.filter { it.second < deck + 2f }.minBy { abs(it.first - ship.x) }
                    centerX = x
                    if (missile) {
                        val length = battle.missileLength()
                        val missileWidth = battle.missileWidth()
                        // Upright it clears the deck; turned over, its nose is the fuse's distance off it.
                        centerY = maxOf(
                            top + diameter + 0.5f * length,
                            top + battle.missileFuse() + 0.5f * length * kotlin.math.cos(dive),
                        )
                        missileGroup = pipeline.nextBlockGroup
                        active + pipeline.spawnBlock(
                            active, centerX - 0.5f * missileWidth, centerY - 0.5f * length, centerX + 0.5f * missileWidth,
                            centerY + 0.5f * length, density = battle.missileDensity(pipeline, type),
                            spin = -dive / (turnSteps * battle.timeStep), strength = NavalBattle.MISSILE_STRENGTH,
                        )
                    } else {
                        val blast = battle.shellBlast(type)
                        centerY = top + diameter + blast.radius
                        active + pipeline.spawnShell(
                            active, centerX, centerY, 0f, 0f, space.scaled(type.shellDiameter), NavalBattle.SHELL_MASS, slot,
                        )
                    }
                }
                at + (if (missile) turnSteps else 1) -> {
                    val blast = if (missile) battle.missileBlast(type) else battle.shellBlast(type)
                    if (missile) pipeline.blastBlock(
                        active, missileGroup, blast.radius, blast.speed, blast.fire, blast.fireRadius, blast.fireSpeed,
                        NavalBattle.FIRE_MASS, battle.steps,
                    ) else pipeline.detonateShell(
                        active, slot, blast.radius, blast.speed, blast.fire, blast.fireRadius, blast.fireSpeed,
                        NavalBattle.FIRE_MASS, battle.steps,
                    )
                    active
                }
                at + 479 -> {
                    val materials = CollisionGpuAccess.floats(pipeline, "materials", active * 4)
                    val chipped = members.indices.filter { materials[4 * members[it] + 3] < 0f }
                    // The radius that holds half of them, from the blast: a long crack now and then hardly moves it.
                    val distances = chipped.map { hypot(restX[it] - centerX, restY[it] - centerY) }.sorted()
                    val reach = distances.getOrElse(distances.size / 2) { 0f }
                    result = Strike(chipped.size, PI.toFloat() * reach * reach)
                    active
                }
                else -> active
            }
        }) { _, _, _ -> }
        return result
    }

    @Test
    fun `a duel ends when a ship breaks or loses half of itself`() {
        // Both cruisers aim the high arc that reaches the other and fire a salvo every 200 steps.
        var ended = -1
        val battle = battle(List(2) { career(ShipClass.CRUISER) }, steps = 7200) { step, battle, _ ->
            for (player in battle.players) {
                aim(battle, player)
                player.controls.fire = step >= 240 && (step - 240) % 200 < 5
            }
            if (battle.over && ended < 0) ended = step
            if (step % 600 == 599) report("duel", step, battle)
        }
        println("ended at step $ended: ${battle.players.map { "${it.name} destroyed=${it.destroyed} broken=${it.broken}" }}")
        assertTrue(battle.over, "a ship was destroyed")
        val loser = battle.players.first { it.destroyed }
        assertEquals(0f, loser.integrity)
        assertTrue(battle.players.all { it.gained > 0f }, "both gained experience")
    }

    /** Holds [player]'s elevation at the high arc that reaches the enemy. */
    private fun aim(battle: NavalBattle, player: NavalBattle.Player) {
        val ship = player.ship ?: return
        val enemy = battle.players.first { it !== player }.ship ?: return
        val speed = space.scaled(player.shipClass.maximumShotSpeed)
        val reach = (abs(enemy.x - ship.x) * field / (speed * speed)).coerceAtMost(1f)
        val elevation = 0.5f * PI.toFloat() - 0.5f * asin(reach)
        player.controls.up = player.elevation < elevation - 0.01f
        player.controls.down = player.elevation > elevation + 0.01f
    }

    private fun career(type: ShipClass) = NavalCareer().also { career ->
        while (career.shipClass != type) career.gain(career.shipClass.experienceToEvolve)
    }

    /** Distance between a gun's pivot on its ship and on its barrel, px. */
    private fun pivotGap(player: NavalBattle.Player, gun: NavalBattle.Gun): Float {
        val ship = player.ship!!.world(gun.shipPivotX, gun.shipPivotY, player.originX, player.originY)
        val barrel = gun.reading!!.world(gun.barrelPivotX, gun.barrelPivotY, gun.originX, gun.originY)
        return hypot(ship.x - barrel.x, ship.y - barrel.y)
    }

    /** Elevation of a barrel above its ship's deck, towards the enemy, rad. */
    private fun barrelAngle(player: NavalBattle.Player, gun: NavalBattle.Gun): Float {
        val ship = player.ship!!
        val pointing = gun.reading!!.turn(gun.pointing, 0f)
        val localX = ship.cos * pointing.x + ship.sin * pointing.y
        val localY = -ship.sin * pointing.x + ship.cos * pointing.y
        return atan2(localY, localX * player.aimFacing)
    }

    private fun report(label: String, step: Int, battle: NavalBattle) {
        val ships = battle.players.joinToString("  ") { player ->
            val ship = player.ship
            if (ship == null) "-" else String.format(
                Locale.ROOT, "(%.0f, %.0f) v=(%.0f, %.0f) angle=%.3f mass=%.0f integrity=%.3f broken=%s guns=%d",
                ship.x, ship.y, ship.velocityX, ship.velocityY, atan2(ship.sin, ship.cos), ship.mass, player.integrity,
                player.broken, player.gunsLeft,
            )
        }
        println("$label step=$step $ships fired=${battle.shellsFired} detonations=${battle.detonations}")
    }

    /**
     * Runs a battle of [careers] for [steps], as the application does; [script]
     * gets the step, the battle and a probe counting hot and foaming bodies.
     * With [record], frames from step [from] go to build/test-videos.
     */
    private fun battle(
        careers: List<NavalCareer>,
        steps: Int,
        record: Boolean = false,
        from: Int = 200,
        /** Before each step: may spawn or set off bodies directly; returns the active bodies. */
        hook: (Int, NavalBattle, GravityPipeline, Int) -> Int = { _, _, _, active -> active },
        script: (Int, NavalBattle, () -> Pair<Int, Int>) -> Unit,
    ): NavalBattle {
        val setup = NavalScene.setup(space, 20261031L)
        val pool = setup.bodies.size / 4
        val config = defaults.copy(
            particles = defaults.particles.copy(capacity = 400_000, initialBodies = pool, seed = 20261031L),
            projectiles = defaults.projectiles.copy(blockStrength = NavalScene.blockStrength),
        )
        lateinit var result: NavalBattle
        val name = Thread.currentThread().stackTrace.firstOrNull { it.methodName.contains(' ') }?.methodName?.substringBefore(',')
            ?.replace(' ', '-') ?: "naval"
        CollisionGpuContext().use {
            GravityPipeline(config, width, height, scenarioState = setup.bodies, bounded = true).use { pipeline ->
                pipeline.mutualGravity = false
                pipeline.liquid = true
                pipeline.fieldAccelerationY = -field
                val battle = NavalBattle(space, config.collisions.timeStep, config.projectiles.blockDensity, field, careers)
                var active = pool + battle.spawn(pipeline, pool)
                pipeline.initializeAcceleration(active)
                val recorder = if (record) TestVideo(width, height, config, quality = 0.8f) else null
                val renderer = if (record) NavalRenderer(width, height, config.collisions.contactDiameterPixels) else null
                val hud = if (record) NavalHud(width, height, config.presentation) else null
                val probe = {
                    val materials = CollisionGpuAccess.floats(pipeline, "materials", active * 4)
                    var hot = 0
                    var foam = 0
                    for (body in 0 until active) {
                        val solid = materials[4 * body + 2] != 0f
                        val tag = materials[4 * body + 3]
                        if (solid && tag <= -100f) hot++
                        if (!solid && tag > 0.5f) foam++
                    }
                    hot to foam
                }
                try {
                    repeat(steps) { step ->
                        script(step, battle, probe)
                        active = hook(step, battle, pipeline, active)
                        active = battle.beforeStep(pipeline, active)
                        pipeline.step(active)
                        battle.afterStep(pipeline, active)
                        if (recorder != null && step >= from && step < from + 1200 && step % 4 == 0) {
                            recorder.capture(pipeline, active) { target ->
                                renderer!!.render(pipeline, active, battle, target)
                                hud!!.render(battle)
                            }
                            if (step % 40 == 0) recorder.still(File("build/test-videos/$name-$step.png"))
                        }
                    }
                    recorder?.save(File("build/test-videos/$name.avi"), 30)
                } finally {
                    recorder?.close()
                    renderer?.close()
                    hud?.close()
                }
                result = battle
            }
            assertEquals(GL_NO_ERROR, glGetError())
        }
        return result
    }
}
