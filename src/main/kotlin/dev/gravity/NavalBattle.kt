package dev.gravity

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The naval battle's start: a pool of liquid along the whole floor of the
 * box, under the downward field. The ships are glued onto it by
 * [NavalBattle.spawnShips].
 */
object NavalScene : BlockScene {
    override val title = "Naval battle"
    override val optionName = "naval-battle"
    override val field = true
    override val liquid = true

    override fun setup(space: ScenarioSpace, seed: Long): BlockSetup = blockSetup(title, space, seed) {
        val diameter = space.contactDiameterPixels
        val rowStep = diameter * sqrt(3f) * 0.5f
        for (row in 0 until rows(space)) {
            val y = 0.5f * diameter + row * rowStep
            var x = 0.5f * diameter + if (row % 2 == 0) 0f else 0.5f * diameter
            while (x <= space.widthPixels - 0.5f * diameter) {
                add(x, y)
                x += diameter
            }
        }
    }

    /** Height of the pool's surface, px. */
    fun surface(space: ScenarioSpace): Float =
        space.contactDiameterPixels * (1f + (rows(space) - 1) * sqrt(3f) * 0.5f)

    /** Rows of the hexagonal pool: as many as POOL_BODIES fill. */
    private fun rows(space: ScenarioSpace): Int =
        POOL_BODIES / maxOf(1, (space.widthPixels / space.contactDiameterPixels).toInt())

    /** Bodies of the pool at most, whatever the screen (the default liquid start has as many). */
    private const val POOL_BODIES = 190_000
}

/** Keys one player holds. */
class NavalControls {
    var left = false
    var right = false
    var up = false
    var down = false
    var fire = false
}

/**
 * Two ships float on the pool of [NavalScene] and shell each other until one
 * is destroyed. Nothing here is animated: a ship is a breakable block of the
 * simulation in the outline of its class (ShipClass), its engine speeds its
 * bodies up, a shell is a ball of heavy bodies fired from each of its guns,
 * and when it hits something it goes off: its bodies fly apart (NavalGpu) and
 * whatever they strike chips, cracks and splashes as any impact would. A ship
 * is destroyed when what is left of its block is under [DESTROYED_FRACTION]
 * of it. Then each player gains experience ([careers]), which evolves their
 * ship into the next class for the next battle.
 *
 * Where the ships and shells are comes from the GPU a step late
 * (GravityPipeline.probeNaval); guns are points of the ship's deck that move
 * with it. Sizes and speeds are authored for a 1440 px tall screen.
 */
internal class NavalBattle(
    private val space: ScenarioSpace,
    /** Physics time step, s. */
    private val timeStep: Float,
    /** Mass of each ship body. */
    private val shipDensity: Float,
    /** The downward field, px/s². */
    private val fieldAcceleration: Float,
    /** Progress of the left (WASD) and the right (arrows) player. */
    careers: List<NavalCareer>,
) {
    /** One side: its ship (block [group]) and guns, and the keys that drive them. */
    class Player(
        val name: String,
        /** +1: the ship faces right (its bow and guns), -1: left. */
        val facing: Float,
        val controls: NavalControls,
        val career: NavalCareer,
    ) {
        val shipClass = career.shipClass
        var group = -1
        /** The guns' pivots in the ship's rest frame, as glued (px from its centre of mass), x y pairs. */
        var pivots = FloatArray(0)
        /** Each gun's lowest elevation, clearing the ship ahead of it. */
        var minimumElevations = FloatArray(0)
        var ship: ShipReading? = null
        /** Mass and rest-frame origin of the ship at its first reading. */
        var initialMass = 0f
        var shapeX = 0f
        var shapeY = 0f
        /** Share of the ship left, 1 when whole. */
        var health = 1f
        var destroyed = false
        /** Share of the enemy ship destroyed since the battle began. */
        var damageDone = 0f
        /** What the battle's end brought: experience, and the class the ship evolved into. */
        var gained = 0f
        var evolved: ShipClass? = null
        /** Barrel elevation above the deck, rad. */
        var elevation = START_ELEVATION
        /** Seconds the fire key has been held, or null. */
        var charge: Float? = null
        /** Seconds until the guns are loaded. */
        var reload = 0f
        /** Power of the last salvo, 0..1: the aim preview shows it while not charging. */
        var lastPower = 0.5f
        /** Simulated seconds since the player last aimed or charged. */
        var idle = Float.POSITIVE_INFINITY

        val power get() = charge?.let { (it / FULL_CHARGE_SECONDS).coerceIn(0f, 1f) } ?: lastPower
    }

    /** A shell in flight, in [NavalGpu.SHELLS] slot [slot], fired at (launchX, launchY) px/s. */
    private class Shell(
        val slot: Int,
        val launchX: Float,
        val launchY: Float,
        val blastRadius: Float,
        val blastSpeed: Float,
    ) {
        /** Simulated seconds since it was fired. */
        var age = 0f
        var seen = false
        var detonated = false
    }

    val wasd = NavalControls()
    val arrows = NavalControls()
    val players = listOf(
        Player("WASD + Space", 1f, wasd, careers[0]),
        Player("Arrows + Enter", -1f, arrows, careers[1]),
    )
    private val shells = arrayOfNulls<Shell>(NavalGpu.SHELLS)
    /** The player who last touched aim or fire: the aim preview is theirs. */
    private var aiming: Player? = null

    var over = false
        private set
    /** Shells fired and gone off so far. */
    var shellsFired = 0
        private set
    var detonations = 0
        private set
    /** The winner once [over]; null for a draw or while fighting. */
    val winner get() = if (over) players.singleOrNull { !it.destroyed } else null

    private fun px(pixelsAt1440: Float) = space.scaled(pixelsAt1440)

    /** Glues both ships onto the pool from [firstBody]; returns their bodies. */
    fun spawnShips(pipeline: GravityPipeline, firstBody: Int): Int {
        val surface = NavalScene.surface(space)
        var spawned = 0
        for (player in players) {
            val type = player.shipClass
            val width = px(type.length)
            val height = px(type.height)
            val centerX = space.widthPixels * (0.5f - player.facing * SHIP_POSITION)
            val minY = surface + px(2f)
            val mask = type.mask(player.facing)
            val lattice = mask.offsets(space.contactDiameterPixels, width, height)
            if (lattice.isEmpty()) continue
            // The block's centre of mass: the guns' pivots are kept from it.
            val meanX = lattice.sumOf { it.x.toDouble() }.toFloat() / lattice.size
            val meanY = lattice.sumOf { it.y.toDouble() }.toFloat() / lattice.size
            player.pivots = FloatArray(type.guns.size) { index ->
                if (index % 2 == 0) player.facing * px(type.guns[index] - 0.5f * type.length) - meanX
                else px(type.guns[index] - 0.5f * type.height) - meanY
            }
            player.minimumElevations = FloatArray(type.gunCount) { type.minimumElevation(it, 0.5f * type.shellDiameter) }
            player.group = pipeline.nextBlockGroup
            spawned += pipeline.spawnBlock(
                firstBody = firstBody + spawned,
                minX = centerX - 0.5f * width,
                minY = minY,
                maxX = centerX + 0.5f * width,
                maxY = minY + height,
                density = shipDensity,
                mask = mask,
            )
        }
        return spawned
    }

    /**
     * Before a physics step: engines, aiming, charging and firing, for one
     * [timeStep]. Returns the active bodies with the shells fired.
     */
    fun beforeStep(pipeline: GravityPipeline, activeBodies: Int): Int {
        var active = activeBodies
        for (shell in shells) shell?.age = shell.age + timeStep
        for (player in players) {
            val ship = player.ship ?: continue
            val controls = player.controls
            val type = player.shipClass
            player.reload -= timeStep
            player.idle += timeStep
            if (over) {
                player.charge = null
                continue
            }
            val thrust = (if (controls.right) 1f else 0f) - (if (controls.left) 1f else 0f)
            if (thrust != 0f && ship.velocityX * thrust < px(type.maximumSpeed)) {
                pipeline.pushBlock(active, player.group, thrust * px(type.thrust) * timeStep, 0f)
            }
            val turn = (if (controls.up) 1f else 0f) - (if (controls.down) 1f else 0f)
            if (turn != 0f) {
                player.elevation = (player.elevation + turn * AIM_RATE * timeStep).coerceIn(0f, MAXIMUM_ELEVATION)
                touch(player)
            }
            if (controls.fire) {
                player.charge = (player.charge ?: 0f) + timeStep
                touch(player)
            } else player.charge?.let {
                player.lastPower = player.power
                player.charge = null
                touch(player)
                if (player.reload <= 0f) {
                    for (gun in 0 until type.gunCount) active += fire(pipeline, active, player, ship, gun)
                    player.reload = type.reloadSeconds
                }
            }
        }
        return active
    }

    private fun touch(player: Player) {
        player.idle = 0f
        aiming = player
    }

    /** Speed of [player]'s shells at their power, px/s. */
    private fun shotSpeed(player: Player) =
        px(MINIMUM_SHOT_SPEED) + player.power * px(player.shipClass.maximumShotSpeed - MINIMUM_SHOT_SPEED)

    /** How far [player]'s fastest shell flies over level water (at 45°), px. */
    fun range(player: Player): Float {
        val speed = px(player.shipClass.maximumShotSpeed)
        return if (fieldAcceleration > 0f) speed * speed / fieldAcceleration else Float.POSITIVE_INFINITY
    }

    /** Fires [player]'s gun [gun] at its power; returns the bodies of the shell. */
    private fun fire(pipeline: GravityPipeline, activeBodies: Int, player: Player, ship: ShipReading, gun: Int): Int {
        val slot = shells.indexOfFirst { it == null }
        if (slot < 0) return 0
        val type = player.shipClass
        val (muzzleX, muzzleY, directionX, directionY) = muzzle(player, ship, gun)
        val speed = shotSpeed(player)
        val radius = 0.5f * px(type.shellDiameter) + space.contactDiameterPixels
        val velocityX = ship.velocityX + directionX * speed
        val velocityY = ship.velocityY + directionY * speed
        val spawned = pipeline.spawnShell(
            firstBody = activeBodies,
            centerX = muzzleX.coerceIn(radius, space.widthPixels - radius),
            centerY = muzzleY.coerceIn(radius, space.heightPixels - radius),
            velocityX = velocityX,
            velocityY = velocityY,
            diameterPixels = px(type.shellDiameter),
            mass = SHELL_MASS,
            shell = slot,
        )
        if (spawned > 0) {
            shells[slot] = Shell(slot, velocityX, velocityY, 0.5f * px(type.shellDiameter), px(type.blastSpeed))
            shellsFired++
        }
        return spawned
    }

    /** Where [player]'s gun [gun] fires its shell from (px), and the barrel's direction. */
    private fun muzzle(player: Player, ship: ShipReading, gun: Int): FloatArray {
        // The rest frame has moved with the shape's origin since the ship was glued (RigidBlocks).
        val restX = player.pivots[2 * gun] + ship.shapeX - player.shapeX
        val restY = player.pivots[2 * gun + 1] + ship.shapeY - player.shapeY
        val pivotX = ship.x + ship.cos * restX - ship.sin * restY
        val pivotY = ship.y + ship.sin * restX + ship.cos * restY
        val elevation = maxOf(player.elevation, player.minimumElevations[gun])
        val localX = player.facing * cos(elevation)
        val localY = sin(elevation)
        val directionX = ship.cos * localX - ship.sin * localY
        val directionY = ship.sin * localX + ship.cos * localY
        val reach = px(MUZZLE_DISTANCE + 0.5f * player.shipClass.shellDiameter)
        return floatArrayOf(pivotX + directionX * reach, pivotY + directionY * reach, directionX, directionY)
    }

    /**
     * After a step (or a frame without one): reads where ships and shells
     * were, sets off the shells that struck something and finds destroyed
     * ships.
     */
    fun afterStep(pipeline: GravityPipeline, activeBodies: Int) {
        val ships = players.filter { it.group >= 0 }
        val reading = pipeline.probeNaval(activeBodies, ships.map { it.group }.toIntArray()) ?: return
        ships.forEachIndexed { index, player -> update(player, reading.ships[index]) }
        if (!over && players.any { it.destroyed }) finish()
        for ((slot, shell) in shells.withIndex()) {
            shell ?: continue
            val seen = reading.shells[slot]
            if (shell.detonated) {
                // Its bodies are grains now: the slot is free once the probe shows it.
                if (seen == null) shells[slot] = null
                continue
            }
            if (seen == null) {
                // Fired after the probe was taken, or lost.
                if (shell.seen) shells[slot] = null
                continue
            }
            shell.seen = true
            // In flight only the field acts on it: anything else is something it struck,
            // at once (a ship) or a little at a time (the liquid's drag). The reading is
            // a step old, which the age, counted before the step, makes up for.
            val expectedX = shell.launchX
            val expectedY = shell.launchY - fieldAcceleration * shell.age
            val deviation = hypot(seen.velocityX - expectedX, seen.velocityY - expectedY)
            val struck = deviation > maxOf(px(IMPACT_SPEED_CHANGE), IMPACT_SPEED_FRACTION * hypot(expectedX, expectedY))
            if (struck || shell.age > FUSE_SECONDS) {
                pipeline.detonateShell(activeBodies, slot, shell.blastRadius, shell.blastSpeed)
                shell.detonated = true
                detonations++
            }
        }
    }

    private fun update(player: Player, ship: ShipReading) {
        val previous = player.ship
        if (previous == null) {
            player.initialMass = ship.mass
            player.shapeX = ship.shapeX
            player.shapeY = ship.shapeY
        }
        if (player.destroyed) return
        player.ship = ship
        val health = (ship.mass / player.initialMass).coerceIn(0f, 1f)
        // A block only loses mass: more means its index went to a piece of another block.
        val reused = previous != null && ship.mass > previous.mass + 0.5f
        val destroyed = health < DESTROYED_FRACTION || reused || !ship.x.isFinite()
        val left = if (destroyed) 0f else minOf(health, player.health)
        players.filter { it !== player }.forEach { it.damageDone += player.health - left }
        player.health = left
        player.destroyed = destroyed
    }

    /** A ship is destroyed: the battle is over, and each player gains experience. */
    private fun finish() {
        over = true
        val winner = winner
        for (player in players) {
            player.gained = EXPERIENCE_PER_SHIP * player.damageDone + if (player === winner) NavalCareer.WIN_BONUS else 0f
            player.evolved = player.career.gain(player.gained)
        }
    }

    /** The shot the aim preview shows: of the player who last aimed or charged, for a while after; their first gun. */
    fun aim(): ProjectileAim? {
        val player = aiming ?: return null
        val ship = player.ship ?: return null
        if (over || player.idle > AIM_PREVIEW_SECONDS) return null
        val (x, y, directionX, directionY) = muzzle(player, ship, 0)
        val speed = shotSpeed(player)
        return ProjectileAim(x, y, ship.velocityX + directionX * speed, ship.velocityY + directionY * speed, px(player.shipClass.shellDiameter))
    }

    /** A shot to preview: from (x, y), px, at (velocityX, velocityY), px/s. */
    data class ProjectileAim(val x: Float, val y: Float, val velocityX: Float, val velocityY: Float, val diameterPixels: Float)

    companion object {
        /** Ship centres from the middle of the screen, in screen widths. */
        const val SHIP_POSITION = 0.38f
        /** Where a shell leaves the barrel, from the pivot, besides its radius. */
        const val MUZZLE_DISTANCE = 16f
        /** Mass of each shell body: heavier than the liquid and the ships. */
        const val SHELL_MASS = 3f
        const val MINIMUM_SHOT_SPEED = 300f
        /** Holding fire this long gives the full speed. */
        const val FULL_CHARGE_SECONDS = 1.5f
        /** A shell whose velocity is off its flight by more than either struck something. */
        const val IMPACT_SPEED_CHANGE = 60f
        const val IMPACT_SPEED_FRACTION = 0.1f
        /** A shell that hit nothing goes off this long after firing. */
        const val FUSE_SECONDS = 8f
        const val AIM_RATE = 0.9f
        const val MAXIMUM_ELEVATION = 1.45f
        const val START_ELEVATION = (PI / 5).toFloat()
        const val AIM_PREVIEW_SECONDS = 1.5f
        /** A ship with less of its block left is destroyed. */
        const val DESTROYED_FRACTION = 0.05f
        /** Experience for destroying a whole enemy ship. */
        const val EXPERIENCE_PER_SHIP = 100f
    }
}
