package dev.gravity

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The naval battle's start: a pool of liquid along the whole floor of the
 * box, under the downward field. The ships are glued onto it by
 * [NavalBattle.spawn]. Their material is weaker than the default blocks'.
 */
object NavalScene : BlockScene {
    override val title = "Naval battle"
    override val optionName = "naval-battle"
    override val field = true
    override val liquid = true
    override val blockStrength = 140f

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
        set(value) {
            if (value && !field) firePressed = true
            field = value
        }
    var missile = false
        set(value) {
            if (value && !field) missilePressed = true
            field = value
        }
    /** Fire or launch was pressed since the battle last looked: a tap between two steps still counts. */
    var firePressed = false
    var missilePressed = false
}

/**
 * Two ships float on the pool of [NavalScene] and fight until one is
 * destroyed. Everything that moves is the simulation:
 * - a ship is a breakable block in the outline of its class (ShipClass); its
 *   engine speeds its bodies up;
 * - each gun, forward, is a barrel, a block of its own held to a pivot on the
 *   deck by a joint that turns it towards its aim (NavalGpu.drive). Each gun
 *   aims only within its own arc, where neither barrel nor shell can strike
 *   the ship ([ShipClass.firingArc]);
 * - a shell is a ball of heavy bodies fired from the barrel's muzzle, the ship
 *   taking the recoil; when it strikes a ship or plunges into the water it
 *   goes off: its bodies fly apart hot, and parked fire bodies burst out as a
 *   fireball of hot gas that rises and cools (naval_fire.comp);
 * - a missile aft flies on jets of gas like the Oniks (NavalMissile) and
 *   blows up as it strikes;
 * - the liquid foams where it is torn apart (naval_foam.comp).
 * A ship is destroyed when it breaks apart or less than half of it is left.
 * Then each player gains experience ([NavalCareer]), which evolves their ship
 * into the next class for the next battle.
 *
 * Where ships, barrels, missiles and shells are comes from the GPU a step late
 * (GravityPipeline.probeNaval). Sizes and speeds are authored for a 1440 px
 * tall screen.
 */
internal class NavalBattle(
    private val space: ScenarioSpace,
    /** Physics time step, s. */
    val timeStep: Float,
    /** Mass of each ship body. */
    private val shipDensity: Float,
    /** The downward field, px/s². */
    private val fieldAcceleration: Float,
    /** Progress of the left (WASD) and the right (arrows) player. */
    careers: List<NavalCareer>,
) {
    /** One gun: its barrel (block [barrel]) and where it turns. */
    class Gun {
        var barrel = -1
        /** The pivot in the ship's rest frame as glued (px from its centre of mass). */
        var shipPivotX = 0f
        var shipPivotY = 0f
        /** The pivot and the muzzle direction in the barrel's rest frame as glued, and its length (px). */
        var barrelPivotX = 0f
        var barrelPivotY = 0f
        var pointing = 1f
        var length = 0f
        /** Elevations it may aim at towards the bow and towards the stern (ShipClass.firingArc). */
        var forwardArc = BooleanArray(0)
        var aftArc = BooleanArray(0)
        /** The elevation it aims at, the nearest in its arc to the player's; null when none is. */
        var elevation: Float? = null
        var reading: BlockReading? = null
        var initialMass = 0f
        var originX = 0f
        var originY = 0f
        var destroyed = false
    }

    /** One side: its ship (block [group]), guns and missile, and the keys that drive them. */
    class Player(
        val name: String,
        /** +1: the ship faces right (its bow), -1: left. */
        val facing: Float,
        val controls: NavalControls,
        val career: NavalCareer,
    ) {
        val shipClass = career.shipClass
        var group = -1
        val guns = List(shipClass.gunCount) { Gun() }
        /** Centre of mass of the ship's lattice about its picture's centre, px. */
        var meanX = 0f
        var meanY = 0f
        var ship: BlockReading? = null
        /** Mass and rest-frame origin of the ship at its first reading. */
        var initialMass = 0f
        var originX = 0f
        var originY = 0f
        /** What is left of the ship before it is destroyed, 1 when whole, 0 when destroyed. */
        var integrity = 1f
        var destroyed = false
        var broken = false
        /** Share of the enemy ship destroyed since the battle began. */
        var damageDone = 0f
        /** What the battle's end brought: experience, and the class the ship evolved into. */
        var gained = 0f
        var evolved: ShipClass? = null
        /** The elevation asked for above the deck towards the enemy, rad; each gun aims as near as its arc allows. */
        var elevation = START_ELEVATION
        /** Which way the guns aim: towards the enemy, wherever the bow points. */
        var aimFacing = facing
        /** Seconds until the guns, and the missile, are loaded. */
        var reload = 0f
        var missileReload = 0f
        /** Its missile in flight, if any. */
        var missile: NavalMissile? = null

        val gunsLeft get() = guns.count { !it.destroyed }
    }

    /**
     * What goes off: fragments thrown from within [radius] of its centre at up
     * to [speed], [fire] parked fire bodies lit within [fireRadius] and thrown
     * at up to [fireSpeed]; px, px/s.
     */
    internal class Blast(val radius: Float, val speed: Float, val fire: Int, val fireRadius: Float, val fireSpeed: Float)

    /** A shell in flight, in [NavalGpu.SHELLS] slot [slot], fired at (launchX, launchY) px/s. */
    private class Shell(
        val slot: Int,
        val launchX: Float,
        val launchY: Float,
        val blast: Blast,
    ) {
        val radius get() = blast.radius
        /** Simulated seconds since it was fired. */
        var age = 0f
        var seen = false
        var detonated = false
    }

    val wasd = NavalControls()
    val arrows = NavalControls()
    val players = listOf(
        Player("WASD + Space, Shift: missile", 1f, wasd, careers[0]),
        Player("Arrows + Enter, R-Shift: missile", -1f, arrows, careers[1]),
    )
    /** Height of the pool's surface at rest, px. */
    val surface = NavalScene.surface(space)
    private val shells = arrayOfNulls<Shell>(NavalGpu.SHELLS)
    /** Where missiles come down: the same battle scatters them the same way. */
    private val random = Random(RANDOM_SEED)
    /** Physics steps so far: the clock of hot bodies. */
    var steps = 0L
        private set
    /** The blocks the last probe asked for: the next reading is of them. */
    private var probed = IntArray(0)

    var over = false
        private set
    /** Shells fired and gone off so far; missiles launched and gone off. */
    var shellsFired = 0
        private set
    var detonations = 0
        private set
    var missilesLaunched = 0
        private set
    var missileBlasts = 0
        private set
    /** The winner once [over]; null for a draw or while fighting. */
    val winner get() = if (over) players.singleOrNull { !it.destroyed } else null

    private val fireConstants = NavalGpu.FireConstants(
        timeStep = timeStep,
        field = fieldAcceleration,
        lift = px(FIRE_LIFT),
        heatSeconds = FIRE_HEAT_SECONDS,
        drag = FIRE_DRAG,
        lifeSeconds = FIRE_LIFE_SECONDS,
        fragmentCoolSeconds = FRAGMENT_COOL_SECONDS,
        fragmentDrag = FRAGMENT_DRAG / px(1f),
        diameter = space.contactDiameterPixels,
    )
    private val foamConstants = NavalGpu.FoamConstants(
        timeStep = timeStep,
        lifeSeconds = FOAM_SECONDS,
        bulkNeighbours = FOAM_BULK_NEIGHBOURS,
        isolationStart = FOAM_ISOLATION_START,
        isolationFull = FOAM_ISOLATION_FULL,
        trappedAirStart = px(FOAM_TRAPPED_AIR_START),
        trappedAirFull = px(FOAM_TRAPPED_AIR_FULL),
    )
    private val barrelThickness = space.contactDiameterPixels * (1f + (BARREL_ROWS - 1) * sqrt(3f) * 0.5f) + 0.01f

    private fun px(pixelsAt1440: Float) = space.scaled(pixelsAt1440)

    /** Barrel blocks of guns not destroyed, and missiles in flight, for the renderer. */
    fun barrelGroups(): List<Int> =
        players.flatMap { player -> player.guns.filter { !it.destroyed && it.barrel >= 0 }.map { it.barrel } }

    fun missileGroups(): List<Int> = players.mapNotNull { it.missile?.group }

    /** Glues both ships and their barrels onto the pool from [firstBody], and parks the fire; returns their bodies. */
    fun spawn(pipeline: GravityPipeline, firstBody: Int): Int {
        val diameter = space.contactDiameterPixels
        var spawned = 0
        for (player in players) {
            val type = player.shipClass
            val scale = px(1f)
            val width = type.length * scale
            val height = type.height * scale
            val centerX = space.widthPixels * (0.5f - player.facing * SHIP_POSITION)
            val centerY = surface + px(2f) + 0.5f * height
            val mask = type.mask(player.facing)
            val lattice = mask.offsets(diameter, width, height)
            if (lattice.isEmpty()) continue
            player.meanX = lattice.sumOf { it.x.toDouble() }.toFloat() / lattice.size
            player.meanY = lattice.sumOf { it.y.toDouble() }.toFloat() / lattice.size
            player.group = pipeline.nextBlockGroup
            spawned += pipeline.spawnBlock(
                firstBody = firstBody + spawned,
                minX = centerX - 0.5f * width,
                minY = centerY - 0.5f * height,
                maxX = centerX + 0.5f * width,
                maxY = centerY + 0.5f * height,
                density = shipDensity,
                mask = mask,
            )
            // Barrels lie level towards the bow, their pivot a body above the mount.
            val lift = diameter + 0.5f * barrelThickness
            val length = type.barrelLength * scale
            val barrelLattice = RectangularBlock.offsets(diameter, length, barrelThickness)
            val barrelMeanX = barrelLattice.sumOf { it.x.toDouble() }.toFloat() / barrelLattice.size
            val barrelMeanY = barrelLattice.sumOf { it.y.toDouble() }.toFloat() / barrelLattice.size
            player.guns.forEachIndexed { index, gun ->
                val pivotX = player.facing * (type.guns[2 * index] - 0.5f * type.length) * scale
                val pivotY = (type.guns[2 * index + 1] - 0.5f * type.height) * scale + lift
                gun.shipPivotX = pivotX - player.meanX
                gun.shipPivotY = pivotY - player.meanY
                gun.length = length
                gun.pointing = player.facing
                gun.barrelPivotX = -player.facing * 0.5f * length - barrelMeanX
                gun.barrelPivotY = -barrelMeanY
                val muzzleY = centerY + pivotY
                gun.forwardArc = underCeiling(
                    type.firingArc(index, true, lift / scale, 0.5f * barrelThickness / scale, 0.5f * type.shellDiameter), type, muzzleY,
                )
                gun.aftArc = underCeiling(
                    type.firingArc(index, false, lift / scale, 0.5f * barrelThickness / scale, 0.5f * type.shellDiameter), type, muzzleY,
                )
                gun.elevation = aim(gun.forwardArc, player.elevation)
                val barrelCenterX = centerX + pivotX + player.facing * 0.5f * length
                val barrelCenterY = centerY + pivotY
                gun.barrel = pipeline.nextBlockGroup
                spawned += pipeline.spawnBlock(
                    firstBody = firstBody + spawned,
                    minX = barrelCenterX - 0.5f * length,
                    minY = barrelCenterY - 0.5f * barrelThickness,
                    maxX = barrelCenterX + 0.5f * length,
                    maxY = barrelCenterY + 0.5f * barrelThickness,
                    density = BARREL_DENSITY,
                    strength = BARREL_STRENGTH,
                )
            }
        }
        spawned += pipeline.spawnFire(firstBody + spawned, FIRE_BODIES, FIRE_MASS)
        return spawned
    }

    /**
     * [arc] without the elevations whose shells, fired from height [muzzleY],
     * would rise into the ceiling: they would burst there and their fragments
     * rain back onto their own ship.
     */
    private fun underCeiling(arc: BooleanArray, type: ShipClass, muzzleY: Float): BooleanArray {
        val speed = px(type.maximumShotSpeed)
        val room = space.heightPixels - px(CEILING_ROOM) - muzzleY
        return BooleanArray(arc.size) { step ->
            val rise = speed * sin(ShipClass.elevation(step))
            arc[step] && (fieldAcceleration <= 0f || rise * rise / (2f * fieldAcceleration) < room)
        }
    }

    /** The elevation of [arc] nearest to [wanted], or null if the arc is empty. */
    private fun aim(arc: BooleanArray, wanted: Float): Float? {
        var best: Float? = null
        for (step in arc.indices) {
            if (!arc[step]) continue
            val elevation = ShipClass.elevation(step)
            if (best == null || abs(elevation - wanted) < abs(best - wanted)) best = elevation
        }
        return best
    }

    /**
     * Before a physics step: fire, engines, aiming, the guns' joints and
     * salvos, missiles, for one [timeStep]. Returns the active bodies with the
     * shells and missiles fired.
     */
    fun beforeStep(pipeline: GravityPipeline, activeBodies: Int): Int {
        var active = activeBodies
        steps++
        pipeline.burnFire(active, steps, fireConstants)
        for (shell in shells) shell?.age = shell.age + timeStep
        for (player in players) {
            val ship = player.ship ?: continue
            val controls = player.controls
            val type = player.shipClass
            player.reload -= timeStep
            player.missileReload -= timeStep
            if (over) continue
            val thrust = (if (controls.right) 1f else 0f) - (if (controls.left) 1f else 0f)
            if (thrust != 0f && ship.velocityX * thrust < px(type.maximumSpeed)) {
                pipeline.pushBlock(active, player.group, thrust * px(type.thrust) * timeStep, 0f)
            }
            val turn = (if (controls.up) 1f else 0f) - (if (controls.down) 1f else 0f)
            player.elevation = (player.elevation + turn * AIM_RATE * timeStep)
                .coerceIn(ShipClass.MINIMUM_ELEVATION, ShipClass.MAXIMUM_ELEVATION)
            // The guns swing round when the enemy has sailed past.
            players.firstOrNull { it !== player }?.ship?.let { enemy ->
                val ahead = enemy.x - ship.x
                if (ahead * player.aimFacing < -px(TURN_DISTANCE)) player.aimFacing = sign(ahead)
            }
            for (gun in player.guns) {
                gun.elevation = aim(if (player.aimFacing == player.facing) gun.forwardArc else gun.aftArc, player.elevation)
            }
            // A salvo fires on a press, if the guns are loaded.
            if (controls.firePressed && player.reload <= 0f && player.gunsLeft > 0) {
                for (gun in player.guns) active += fire(pipeline, active, player, ship, gun)
                player.reload = type.reloadSeconds
            }
            controls.firePressed = false
            if (controls.missilePressed && player.missile == null && player.missileReload <= 0f) {
                active += launch(pipeline, active, player, ship)
            }
            controls.missilePressed = false
        }
        pipeline.driveBarrels(active, drives(), JOINT_SECONDS, MAXIMUM_TURN, shipDensity, BARREL_DENSITY)
        val jets = players.flatMap { player ->
            val missile = player.missile ?: return@flatMap emptyList()
            val enemy = players.first { it !== player }
            val target = enemy.ship?.takeIf { !enemy.destroyed }?.let { PixelOffset(it.x, it.y) }
            missile.jets(timeStep, fieldAcceleration, target)
        }
        pipeline.fireJets(active, jets, NavalMissile.GAS_MASS, JET_SPREAD, steps)
        for (player in players) {
            val missile = player.missile ?: continue
            val air = missile.air(timeStep)
            if (air.x != 0f || air.y != 0f) pipeline.pushBlock(active, missile.group, air.x, air.y)
        }
        return active
    }

    /** The joints of the guns whose ship and barrel have been seen. */
    private fun drives(): List<BarrelDrive> = players.flatMap { player ->
        if (player.ship == null || player.destroyed) emptyList()
        else player.guns.filter { it.reading != null && !it.destroyed && it.elevation != null }.map { gun ->
            val elevation = gun.elevation!!
            BarrelDrive(
                barrel = gun.barrel, ship = player.group,
                shipPivotX = gun.shipPivotX, shipPivotY = gun.shipPivotY,
                shipOriginX = player.originX, shipOriginY = player.originY,
                barrelPivotX = gun.barrelPivotX, barrelPivotY = gun.barrelPivotY,
                barrelOriginX = gun.originX, barrelOriginY = gun.originY,
                pointingX = gun.pointing, pointingY = 0f,
                aimX = player.aimFacing * cos(elevation), aimY = sin(elevation),
            )
        }
    }

    /** Fires [gun] of [player]'s ship from its muzzle; returns the bodies of the shell. */
    private fun fire(pipeline: GravityPipeline, activeBodies: Int, player: Player, ship: BlockReading, gun: Gun): Int {
        if (gun.destroyed || gun.elevation == null) return 0
        val barrel = gun.reading ?: return 0
        val slot = shells.indexOfFirst { it == null }
        if (slot < 0) return 0
        val type = player.shipClass
        val radius = 0.5f * px(type.shellDiameter)
        val (muzzle, direction) = muzzle(gun, barrel, radius)
        // A ship deformed by hits still never fires into itself.
        if (blocked(player, muzzle, direction, radius)) return 0
        val speed = px(type.maximumShotSpeed)
        val velocityX = barrel.velocityX + direction.x * speed
        val velocityY = barrel.velocityY + direction.y * speed
        val margin = radius + space.contactDiameterPixels
        val spawned = pipeline.spawnShell(
            firstBody = activeBodies,
            centerX = muzzle.x.coerceIn(margin, space.widthPixels - margin),
            centerY = muzzle.y.coerceIn(margin, space.heightPixels - margin),
            velocityX = velocityX,
            velocityY = velocityY,
            diameterPixels = px(type.shellDiameter),
            mass = SHELL_MASS,
            shell = slot,
        )
        if (spawned == 0) return 0
        shells[slot] = Shell(slot, velocityX, velocityY, shellBlast(type))
        shellsFired++
        // The ship takes the shell's momentum back.
        val recoil = spawned * SHELL_MASS * speed / (ship.mass * shipDensity)
        pipeline.pushBlock(activeBodies + spawned, player.group, -direction.x * recoil, -direction.y * recoil)
        return spawned
    }

    /**
     * Throws [player]'s missile up out of its launch cell aft, upright, the
     * ship taking the recoil; returns its bodies.
     */
    private fun launch(pipeline: GravityPipeline, activeBodies: Int, player: Player, ship: BlockReading): Int {
        val type = player.shipClass
        val scale = px(1f)
        val diameter = space.contactDiameterPixels
        val cell = ship.world(
            player.facing * (type.launcherX - 0.5f * type.length) * scale - player.meanX,
            (type.launcherY - 0.5f * type.height) * scale - player.meanY,
            player.originX, player.originY,
        )
        val length = missileLength()
        val missileWidth = missileWidth()
        val centerY = cell.y + diameter + 0.5f * length
        val lattice = RectangularBlock.offsets(diameter, missileWidth, length)
        val group = pipeline.nextBlockGroup
        val density = missileDensity(pipeline, type)
        val speed = px(NavalMissile.EJECT_SPEED)
        val spawned = pipeline.spawnBlock(
            firstBody = activeBodies,
            minX = cell.x - 0.5f * missileWidth,
            minY = centerY - 0.5f * length,
            maxX = cell.x + 0.5f * missileWidth,
            maxY = centerY + 0.5f * length,
            density = density,
            velocityX = ship.velocityX,
            velocityY = ship.velocityY + speed,
            strength = MISSILE_STRENGTH,
        )
        if (spawned == 0) return 0
        player.missile = NavalMissile(
            group, player, length, missileWidth,
            lattice.sumOf { it.x.toDouble() }.toFloat() / lattice.size,
            lattice.sumOf { it.y.toDouble() }.toFloat() / lattice.size,
            diameter, scale, density, scatter = 2f * random.nextFloat() - 1f,
        )
        player.missileReload = MISSILE_RELOAD_SECONDS
        missilesLaunched++
        // The gas generator throws the missile up and the ship down.
        pipeline.pushBlock(activeBodies + spawned, player.group, 0f, -spawned * density * speed / (ship.mass * shipDensity))
        return spawned
    }

    /**
     * Whether a shell of [radius] fired from [muzzle] along [direction] would
     * strike [player]'s own ship: as on a real warship, a gun aimed through its
     * ship does not fire (a firing cut-out).
     */
    private fun blocked(player: Player, muzzle: PixelOffset, direction: PixelOffset, radius: Float): Boolean {
        val reach = px(player.shipClass.length) * CUT_OUT_LENGTHS
        var distance = 0f
        while (distance < reach) {
            if (touches(player, muzzle.x + direction.x * distance, muzzle.y + direction.y * distance, radius)) return true
            distance += space.contactDiameterPixels
        }
        return false
    }

    /** Where a shell of [radius] leaves [gun]'s barrel (px), and the barrel's direction. */
    private fun muzzle(gun: Gun, barrel: BlockReading, radius: Float): Pair<PixelOffset, PixelOffset> {
        val reach = gun.length + radius + space.contactDiameterPixels
        val muzzle = barrel.world(gun.barrelPivotX + gun.pointing * reach, gun.barrelPivotY, gun.originX, gun.originY)
        return muzzle to barrel.turn(gun.pointing, 0f)
    }

    /**
     * After a step (or a frame without one): the liquid's foam, then where
     * ships, barrels, missiles and shells were; sets off the shells and
     * missiles that struck and finds destroyed ships and guns.
     */
    fun afterStep(pipeline: GravityPipeline, activeBodies: Int) {
        pipeline.foamLiquid(activeBodies, foamConstants)
        val asked = (
            players.filter { it.group >= 0 }.map { it.group } +
                players.flatMap { it.guns }.filter { it.barrel >= 0 && !it.destroyed }.map { it.barrel } +
                missileGroups()
            ).toIntArray()
        val reading = pipeline.probeNaval(activeBodies, asked)
        // A reading is of the blocks asked for by the call before.
        val blocks = if (reading == null) emptyMap() else probed.indices.associate { probed[it] to reading.blocks[it] }
        probed = asked
        reading ?: return
        for (player in players) blocks[player.group]?.let { update(pipeline, player, it) }
        for (gun in players.flatMap { it.guns }) if (!gun.destroyed) blocks[gun.barrel]?.let { update(gun, it) }
        for (player in players) {
            val missile = player.missile ?: continue
            blocks[missile.group]?.let(missile::see)
            if (missile.reading != null && struck(missile)) {
                val blast = missileBlast(player.shipClass)
                pipeline.blastBlock(
                    activeBodies, missile.group, blast.radius, blast.speed,
                    fireBodies = blast.fire, fireRadiusPixels = blast.fireRadius, fireSpeed = blast.fireSpeed,
                    fireMass = FIRE_MASS, step = steps,
                )
                player.missile = null
                missileBlasts++
            }
        }
        if (!over && players.any { it.destroyed }) finish()
        for ((slot, shell) in shells.withIndex()) {
            shell ?: continue
            val seen = reading.shells[slot]
            if (shell.detonated) {
                // Its bodies are fragments now: the slot is free once the probe shows it.
                if (seen == null) shells[slot] = null
                continue
            }
            if (seen == null) {
                // Fired after the probe was taken, or lost.
                if (shell.seen) shells[slot] = null
                continue
            }
            shell.seen = true
            if (struck(shell, seen) || shell.age > FUSE_SECONDS) {
                val blast = shell.blast
                pipeline.detonateShell(
                    activeBodies, slot, blast.radius, blast.speed,
                    fireBodies = blast.fire, fireRadiusPixels = blast.fireRadius, fireSpeed = blast.fireSpeed, fireMass = FIRE_MASS,
                    step = steps,
                )
                shell.detonated = true
                detonations++
            }
        }
    }

    /**
     * What a shell of [type] throws when it goes off: its own bodies, from
     * its centre out, and a fireball of parked fire bodies.
     */
    internal fun shellBlast(type: ShipClass) = Blast(
        radius = 0.5f * px(type.shellDiameter),
        speed = px(type.blastSpeed),
        fire = (FIRE_PER_SHELL_PIXEL * type.shellDiameter).toInt(),
        fireRadius = px(FIRE_RADIUS),
        fireSpeed = px(FIRE_SPEED),
    )

    /**
     * What the missile of a ship of [type] throws, against its guns' shells:
     * a fireball of [MISSILE_AREA] times the area, the fire spreading as much
     * faster, and its body as a belt of fragments across its axis
     * ([MISSILE_FRAGMENT_SPEED] of a shell's speed, [missileDensity]). Set off
     * [MISSILE_FUSE] short of the ship, its fragments spread thinner and strike
     * at a slant, many of them missing: it strikes about twice the patch a
     * shell does and breaks about half as much of it.
     */
    internal fun missileBlast(type: ShipClass): Blast {
        val shell = shellBlast(type)
        val wider = sqrt(MISSILE_AREA)
        return Blast(
            radius = 0.5f * missileLength(),
            speed = shell.speed * MISSILE_FRAGMENT_SPEED,
            fire = (shell.fire * MISSILE_AREA).toInt(),
            fireRadius = shell.fireRadius * wider,
            fireSpeed = shell.fireSpeed * wider,
        )
    }

    /** The missile's length and width, px. */
    internal fun missileLength() = px(MISSILE_LENGTH)

    internal fun missileWidth() = space.contactDiameterPixels * MISSILE_COLUMNS + 0.01f

    /** How far off a ship a missile goes off, px. */
    internal fun missileFuse() = px(MISSILE_FUSE)

    /**
     * Mass of a body of the missile of a ship of [type]: such that its
     * fragments carry [MISSILE_ENERGY] times the energy of a shell's of its
     * guns, which is what breaks a hull. Each throws its bodies at up to its
     * blast speed, a shell's the faster the further out (on the mean
     * [SHELL_SPEED_SQUARE] of its square), a missile's all nearly as fast
     * ([MISSILE_SPEED_SQUARE]): naval_detonate.comp.
     */
    internal fun missileDensity(pipeline: GravityPipeline, type: ShipClass): Float {
        val shell = pipeline.projectileBodyCount(px(type.shellDiameter)) * SHELL_MASS * SHELL_SPEED_SQUARE
        val bodies = RectangularBlock.offsets(space.contactDiameterPixels, missileWidth(), missileLength()).size
        return MISSILE_ENERGY * shell / (bodies * MISSILE_FRAGMENT_SPEED * MISSILE_FRAGMENT_SPEED * MISSILE_SPEED_SQUARE)
    }

    /**
     * Whether [shell], seen at [seen], struck something: in flight only the
     * field acts on it, so anything else is a hit. A ship is struck as soon as
     * the shell touches it; the water only by a plunge, its bulk stopping the
     * shell or the shell under its surface, so spray and foam are flown
     * through. The reading is a step old, which the age, counted before the
     * step, makes up for.
     */
    private fun struck(shell: Shell, seen: ShellReading): Boolean {
        val expectedX = shell.launchX
        val expectedY = shell.launchY - fieldAcceleration * shell.age
        val deviation = hypot(seen.velocityX - expectedX, seen.velocityY - expectedY)
        if (deviation > maxOf(px(HARD_IMPACT_CHANGE), HARD_IMPACT_FRACTION * hypot(expectedX, expectedY))) return true
        if (seen.y < surface - 4f * shell.radius) return true
        return deviation > px(SHIP_IMPACT_CHANGE) && players.any { touches(it, seen.x, seen.y, shell.radius) }
    }

    /**
     * Whether [missile] struck something: its nose within [MISSILE_FUSE] of a
     * ship (a proximity fuse; its own ship only once clear of it), in the
     * water, a jolt no jet makes, a piece of it shot away, or its fuel spent.
     */
    private fun struck(missile: NavalMissile): Boolean {
        val nose = missile.nose() ?: return false
        val reading = missile.reading ?: return false
        val armed = missile.age > MISSILE_ARMING_SECONDS
        return players.any { (armed || it !== missile.owner) && touches(it, nose.x, nose.y, missileFuse()) } ||
            nose.y < surface ||
            missile.jolt() > px(MISSILE_JOLT) ||
            reading.mass < MISSILE_INTACT_FRACTION * missile.initialMass ||
            !reading.x.isFinite() ||
            missile.age > NavalMissile.FUEL_SECONDS
    }

    /** Whether a body of [radius] at ([x], [y]) touches what is drawn of [player]'s ship. */
    private fun touches(player: Player, x: Float, y: Float, radius: Float): Boolean {
        val ship = player.ship ?: return false
        if (player.destroyed) return false
        val type = player.shipClass
        val scale = px(1f)
        val reach = radius + 2f * space.contactDiameterPixels
        for (sample in 0..8) {
            val angle = sample * (PI.toFloat() / 4f)
            val distance = if (sample == 8) 0f else reach
            val rest = ship.rest(x + distance * cos(angle), y + distance * sin(angle), player.originX, player.originY)
            // From the rest frame about the centre of mass to the side view from the stern at the keel.
            val pictureX = (rest.x + player.meanX) / scale
            val classX = if (player.facing > 0f) pictureX + 0.5f * type.length else 0.5f * type.length - pictureX
            if (type.covers(classX, (rest.y + player.meanY) / scale + 0.5f * type.height)) return true
        }
        return false
    }

    private fun update(pipeline: GravityPipeline, player: Player, ship: BlockReading) {
        val previous = player.ship
        if (previous == null) {
            player.initialMass = ship.mass
            player.originX = ship.shapeX
            player.originY = ship.shapeY
        }
        if (player.destroyed) return
        player.ship = ship
        val left = ship.mass / player.initialMass
        player.broken = pipeline.largestPiece(player.group) >= BROKEN_FRACTION * player.initialMass
        // A block only loses mass: more means its index went to a piece of another block.
        val reused = previous != null && ship.mass > previous.mass + 0.5f
        val destroyed = left < DESTROYED_FRACTION || player.broken || reused || !ship.x.isFinite()
        val integrity = if (destroyed) 0f
        else minOf(player.integrity, ((left - DESTROYED_FRACTION) / (1f - DESTROYED_FRACTION)).coerceIn(0f, 1f))
        players.filter { it !== player }.forEach { it.damageDone += player.integrity - integrity }
        player.integrity = integrity
        player.destroyed = destroyed
    }

    private fun update(gun: Gun, barrel: BlockReading) {
        val previous = gun.reading
        if (previous == null) {
            gun.initialMass = barrel.mass
            gun.originX = barrel.shapeX
            gun.originY = barrel.shapeY
        }
        gun.reading = barrel
        val reused = previous != null && barrel.mass > previous.mass + 0.5f
        if (barrel.mass < GUN_DESTROYED_FRACTION * gun.initialMass || reused || !barrel.x.isFinite()) gun.destroyed = true
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

    /** How far [player]'s shells fly over level water (at 45°), px. */
    fun range(player: Player): Float {
        val speed = px(player.shipClass.maximumShotSpeed)
        return if (fieldAcceleration > 0f) speed * speed / fieldAcceleration else Float.POSITIVE_INFINITY
    }

    /**
     * Where a shell of [player]'s first gun would fly now, until it meets the
     * water or a wall: the field is all that acts on it in flight.
     */
    fun trajectory(player: Player): List<PixelOffset> {
        if (player.destroyed) return emptyList()
        val gun = player.guns.firstOrNull { !it.destroyed && it.elevation != null } ?: return emptyList()
        val barrel = gun.reading ?: return emptyList()
        val radius = 0.5f * px(player.shipClass.shellDiameter)
        val (muzzle, direction) = muzzle(gun, barrel, radius)
        val speed = px(player.shipClass.maximumShotSpeed)
        var x = muzzle.x
        var y = muzzle.y
        val velocityX = barrel.velocityX + direction.x * speed
        var velocityY = barrel.velocityY + direction.y * speed
        val points = arrayListOf(PixelOffset(x, y))
        val dt = TRAJECTORY_STEP_SECONDS
        repeat((TRAJECTORY_SECONDS / dt).toInt()) {
            velocityY -= fieldAcceleration * dt
            x += velocityX * dt
            y += velocityY * dt
            points += PixelOffset(x, y)
            if (y < surface || x < 0f || x > space.widthPixels || y > space.heightPixels) return points
        }
        return points
    }

    companion object {
        /** Ship centres from the middle of the screen, in screen widths. */
        const val SHIP_POSITION = 0.38f
        /** Rows of bodies across a barrel, and their mass. */
        const val BARREL_ROWS = 3
        const val BARREL_DENSITY = 1f
        /** Barrels are steel: they chip only this many times as fast as the hulls. */
        const val BARREL_STRENGTH = 5f
        /** How fast a joint closes a gap (s) and the fastest a barrel turns (rad/s). */
        const val JOINT_SECONDS = 0.025f
        const val MAXIMUM_TURN = 2.5f
        /** Room kept between the top of a shell's flight and the ceiling, px. */
        const val CEILING_ROOM = 40f
        /** How far along its line of fire, in ship lengths, a gun checks for its own ship. */
        const val CUT_OUT_LENGTHS = 1.2f
        /** Mass of each shell body: heavier than the liquid and the ships. */
        const val SHELL_MASS = 4f
        /** A shell whose velocity is off its flight by more than either struck something hard. */
        const val HARD_IMPACT_CHANGE = 150f
        const val HARD_IMPACT_FRACTION = 0.3f
        /** Next to a ship, a shell slowed this much (px/s) struck it. */
        const val SHIP_IMPACT_CHANGE = 20f
        /** A shell that hit nothing goes off this long after firing. */
        const val FUSE_SECONDS = 8f
        /** The missile: length (px), bodies across, how much harder it chips than a hull. */
        const val MISSILE_LENGTH = 20f
        const val MISSILE_COLUMNS = 2.3f
        const val MISSILE_STRENGTH = 5f
        /** Mean square speed of a blast's fragments over its fastest's, of a shell's and of a missile's (naval_detonate.comp). */
        const val SHELL_SPEED_SQUARE = 0.615f
        const val MISSILE_SPEED_SQUARE = 0.771f
        /** Seconds before another missile is loaded, and before one may strike its own ship. */
        const val MISSILE_RELOAD_SECONDS = 10f
        const val MISSILE_ARMING_SECONDS = 2f
        const val RANDOM_SEED = 20261031L
        /** A missile jolted this much in a step (px/s), or with less than this of it left, struck something. */
        const val MISSILE_JOLT = 220f
        const val MISSILE_INTACT_FRACTION = 0.8f
        /**
         * Its warhead against its ship's shells: its fireball's area, its
         * fragments' energy and speed (below 2200 px/s at most, which a
         * collision substep still catches).
         */
        const val MISSILE_AREA = 2f
        const val MISSILE_ENERGY = 0.7f
        const val MISSILE_FRAGMENT_SPEED = 0.9f
        /**
         * It goes off this far (px) short of a ship, so its fragments strike
         * a wider patch, thinner and at a slant, than a shell's on contact.
         */
        const val MISSILE_FUSE = 26f
        /** Spread of the missiles' jets, rad. */
        const val JET_SPREAD = 0.12f
        /** Fire bodies parked for blasts and jets, their mass, and lit per pixel of a shell's diameter. */
        const val FIRE_BODIES = 16384
        const val FIRE_MASS = 0.03f
        const val FIRE_PER_SHELL_PIXEL = 70f
        /** The fire of a blast: where it is lit (px), how fast it bursts out (px/s). */
        const val FIRE_RADIUS = 10f
        const val FIRE_SPEED = 150f
        /** Its lift while hottest (px/s²), how fast it cools (s), the air's drag (1/s), and how long it burns (s). */
        const val FIRE_LIFT = 350f
        const val FIRE_HEAT_SECONDS = 0.35f
        const val FIRE_DRAG = 4f
        const val FIRE_LIFE_SECONDS = 1.6f
        /** How long a shell's fragments glow. */
        const val FRAGMENT_COOL_SECONDS = 1.5f
        /** The air's drag on them, per px at 1440: from 2400 px/s they keep a tenth of their speed after about 1150 px. */
        const val FRAGMENT_DRAG = 0.002f
        /** Foam: how long it lasts, the neighbours of a body inside the liquid, isolation and trapped air (px/s) that make it. */
        const val FOAM_SECONDS = 1.5f
        const val FOAM_BULK_NEIGHBOURS = 20f
        const val FOAM_ISOLATION_START = 0.5f
        const val FOAM_ISOLATION_FULL = 0.85f
        const val FOAM_TRAPPED_AIR_START = 300f
        const val FOAM_TRAPPED_AIR_FULL = 1500f
        const val AIM_RATE = 0.9f
        const val START_ELEVATION = (PI / 4).toFloat()
        /** The enemy this far (px) behind a ship turns its guns round. */
        const val TURN_DISTANCE = 20f
        /** A ship with less than this of its block left, or a piece this big broken away, is destroyed. */
        const val DESTROYED_FRACTION = 0.5f
        const val BROKEN_FRACTION = 0.15f
        /** A barrel with less than this left is destroyed. */
        const val GUN_DESTROYED_FRACTION = 0.4f
        /** Experience for destroying a whole enemy ship. */
        const val EXPERIENCE_PER_SHIP = 100f
        const val TRAJECTORY_SECONDS = 6f
        const val TRAJECTORY_STEP_SECONDS = 1f / 60f
    }
}
