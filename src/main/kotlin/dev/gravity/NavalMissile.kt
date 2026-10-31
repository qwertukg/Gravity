package dev.gravity

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sign
import kotlin.math.sin

/**
 * A ship-launched anti-ship missile flown the way the P-800 Oniks flies, by
 * nothing but jets of gas (naval_thrust.comp): every push is gas bodies thrown
 * out of a nozzle, the missile taking their momentum back.
 *
 * 1. [Phase.EJECTED]: the launch cell's gas generator throws it up out of the
 *    cell (the ship takes the recoil), engines cold.
 * 2. [Phase.BOOST]: the booster fires down along its tail; gas-dynamic jets
 *    at the nose keep it upright.
 * 3. [Phase.TURN]: near the top of its climb it takes the enemy's bearing,
 *    roughly ([aimPoint]), and the nose jets swing it over that way.
 * 4. [Phase.CRUISE]: the sustainer burns along its tail against the drag;
 *    the nose jets raise the nose off the flight path just enough for the
 *    body's lift ([air]) to hold it up and bend the path onto the line to its
 *    aim point: level flight at its altitude, then a dive. It never looks
 *    again, so it comes down short of the ship or beyond it, or where the
 *    ship was.
 *
 * The missile is a block, [length] long, nose along +y of its rest frame,
 * whose lattice's centre of mass lay ([meanX], [meanY]) from its rectangle's
 * centre; speeds and distances are px at 1440 times [scale].
 */
internal class NavalMissile(
    val group: Int,
    val owner: NavalBattle.Player,
    private val length: Float,
    private val width: Float,
    private val meanX: Float,
    private val meanY: Float,
    private val diameter: Float,
    private val scale: Float,
    /** Mass of one of its bodies. */
    private val density: Float,
    /** Where along the line it comes down, from -1 (short of the target) to 1 (beyond it). */
    private val scatter: Float,
) {
    enum class Phase { EJECTED, BOOST, TURN, CRUISE }

    var phase = Phase.EJECTED
        private set
    /** Simulated seconds since launch. */
    var age = 0f
        private set
    var reading: BlockReading? = null
        private set
    private var previous: BlockReading? = null
    var initialMass = 0f
        private set
    var originX = 0f
        private set
    var originY = 0f
        private set
    /** Gas owed to each kind of jet: whole bodies are thrown, the rest carries over. */
    private var mainCarry = 0f
    private var sideCarry = 0f
    /** The point it flies at, fixed at the top of its climb: the enemy then, give or take [SCATTER]. */
    var aimPoint: PixelOffset? = null
        private set

    private fun px(pixelsAt1440: Float) = pixelsAt1440 * scale

    /** The missile as the probe saw it. */
    fun see(missile: BlockReading) {
        if (reading == null) {
            initialMass = missile.mass
            originX = missile.shapeX
            originY = missile.shapeY
        }
        previous = reading
        reading = missile
    }

    /** Its nose in the world, px. */
    fun nose(): PixelOffset? = reading?.world(-meanX, 0.5f * length - meanY, originX, originY)

    /** How much its velocity changed between the last two readings, px/s. */
    fun jolt(): Float {
        val now = reading ?: return 0f
        val before = previous ?: return 0f
        return hypot(now.velocityX - before.velocityX, now.velocityY - before.velocityY)
    }

    /**
     * Its jets for the coming step of [dt] s under the downward field
     * [field], flying roughly at [target] (a ship's centre), px: it takes the
     * target's bearing once, as it turns over, and flies at that point, short
     * of or beyond the ship by up to [SCATTER].
     */
    fun jets(dt: Float, field: Float, target: PixelOffset?): List<MissileJet> {
        age += dt
        val missile = reading ?: return emptyList()
        if (phase == Phase.EJECTED && age >= EJECT_SECONDS) phase = Phase.BOOST
        if (phase == Phase.BOOST && age >= EJECT_SECONDS + BOOST_SECONDS) phase = Phase.TURN
        val noseX = -missile.sin
        val noseY = missile.cos
        val mass = missile.mass * density
        val jets = ArrayList<MissileJet>(2)
        var aimX = 0f
        var aimY = 1f
        when (phase) {
            Phase.EJECTED -> return emptyList()
            Phase.BOOST -> jets += tailJet(gas(mass * px(BOOST_ACCELERATION) * dt, BOOSTER_EXHAUST, ::mainCarry), BOOSTER_EXHAUST)
            Phase.TURN -> {
                if (aimPoint == null && target != null) {
                    val side = sign(target.x - missile.x).takeIf { it != 0f } ?: 1f
                    aimPoint = PixelOffset(target.x + side * scatter * px(SCATTER), target.y)
                }
                val side = if (target == null) 1f else sign(target.x - missile.x).takeIf { it != 0f } ?: 1f
                val pitch = hypot(1f, TURN_PITCH)
                aimX = side / pitch
                aimY = TURN_PITCH / pitch
                if (abs(angle(noseX, noseY, aimX, aimY)) < CRUISE_ALIGNMENT) phase = Phase.CRUISE
            }
            Phase.CRUISE -> {
                val goal = aimPoint ?: PixelOffset(missile.x + noseX, missile.y + noseY)
                val dx = goal.x - missile.x
                // Level at its altitude while far, then down onto the ship.
                val dy = if (abs(dx) * DIVE_SLOPE > missile.y - goal.y) 0f else goal.y - missile.y
                val distance = hypot(dx, dy).coerceAtLeast(1f)
                val speed = px(CRUISE_SPEED)
                // The acceleration that brings its velocity onto the line, holding it up against the field.
                val needX = (speed * dx / distance - missile.velocityX) / VELOCITY_RESPONSE_SECONDS
                val needY = (speed * dy / distance - missile.velocityY) / VELOCITY_RESPONSE_SECONDS + field
                val flight = hypot(missile.velocityX, missile.velocityY)
                val pathX = if (flight > 1f) missile.velocityX / flight else noseX
                val pathY = if (flight > 1f) missile.velocityY / flight else noseY
                // Across the path the body's lift does it: the nose rises off the path as much as that takes.
                val across = needY * pathX - needX * pathY
                val attack = (across / (LIFT_PER_PX * flight * flight).coerceAtLeast(1f)).coerceIn(-MAXIMUM_ATTACK, MAXIMUM_ATTACK)
                aimX = pathX * cos(attack) - pathY * sin(attack)
                aimY = pathX * sin(attack) + pathY * cos(attack)
                // Along it the sustainer: what speeding up takes, and the drag.
                val along = needX * pathX + needY * pathY + DRAG_PER_PX * flight * flight
                val axial = (along / cos(attack)).coerceIn(0f, px(MAXIMUM_ACCELERATION))
                jets += tailJet(gas(mass * axial * dt, MAIN_EXHAUST, ::mainCarry), MAIN_EXHAUST)
            }
        }
        steer(missile, noseX, noseY, aimX, aimY, dt)?.let(jets::add)
        return jets.filter { it.wanted > 0 }
    }

    /**
     * The air on it for the coming step of [dt] s, as a change of its
     * velocity (px/s): lift across its flight path, from the angle its nose
     * makes with the path (no further than its body stalls), and drag along
     * the path, more so at an angle. Both act at its centre of mass.
     */
    fun air(dt: Float): PixelOffset {
        val missile = reading ?: return PixelOffset(0f, 0f)
        val flight = hypot(missile.velocityX, missile.velocityY)
        if (flight < 1f) return PixelOffset(0f, 0f)
        val pathX = missile.velocityX / flight
        val pathY = missile.velocityY / flight
        val attack = angle(pathX, pathY, -missile.sin, missile.cos)
        val pressure = flight * flight
        val lift = LIFT_PER_PX * pressure * sin(attack.coerceIn(-STALL_ATTACK, STALL_ATTACK))
        val drag = DRAG_PER_PX * pressure * (1f + ATTACK_DRAG * sin(attack) * sin(attack))
        return PixelOffset((-pathY * lift - pathX * drag) * dt, (pathX * lift - pathY * drag) * dt)
    }

    /**
     * The nose jet that turns the nose towards ([aimX], [aimY]): it brings the
     * spin towards one proportional to the angle left, and the gas it throws
     * sideways at the nose turns the missile about its centre.
     */
    private fun steer(missile: BlockReading, noseX: Float, noseY: Float, aimX: Float, aimY: Float, dt: Float): MissileJet? {
        val error = angle(noseX, noseY, aimX, aimY)
        val spin = (TURN_GAIN * error).coerceIn(-MAXIMUM_SPIN, MAXIMUM_SPIN)
        val inertia = missile.inertia * density
        val angularImpulse = inertia * (spin - missile.spin) * dt / SPIN_RESPONSE_SECONDS
        val arm = 0.5f * length - SIDE_JET_BACK * diameter
        val perBody = arm * GAS_MASS * px(SIDE_EXHAUST)
        sideCarry += abs(angularImpulse) / perBody
        val bodies = floor(sideCarry).toInt().coerceAtMost(MAXIMUM_SIDE_GAS)
        sideCarry = (sideCarry - bodies).coerceAtMost(1f)
        if (bodies == 0) return null
        // Gas thrown towards +x at the nose turns the missile anticlockwise.
        val toward = sign(angularImpulse)
        return MissileJet(
            group, toward * (0.5f * width + SIDE_JET_OUT * diameter) - meanX, arm - meanY, originX, originY,
            toward, 0f, bodies, px(SIDE_EXHAUST), density,
        )
    }

    private fun tailJet(bodies: Int, exhaust: Float) = MissileJet(
        group, -meanX, -0.5f * length - TAIL_JET_OUT * diameter - meanY, originX, originY, 0f, -1f, bodies, px(exhaust), density,
    )

    /** Whole gas bodies whose momentum at [exhaust] makes [impulse], owing the rest. */
    private fun gas(impulse: Float, exhaust: Float, carry: kotlin.reflect.KMutableProperty0<Float>): Int {
        val owed = carry.get() + impulse / (GAS_MASS * px(exhaust))
        val bodies = floor(owed).toInt().coerceAtMost(MAXIMUM_TAIL_GAS)
        carry.set((owed - bodies).coerceAtMost(1f))
        return bodies
    }

    companion object {
        /** Mass of a gas body, as of the fire (NavalBattle.FIRE_MASS). */
        const val GAS_MASS = 0.03f
        /** The gas generator's throw out of the cell, px/s, and how long it coasts before the booster lights, s. */
        const val EJECT_SPEED = 260f
        const val EJECT_SECONDS = 0.15f
        /** The booster: how long it burns, s, its acceleration and exhaust speed. */
        const val BOOST_SECONDS = 0.8f
        const val BOOST_ACCELERATION = 620f
        const val BOOSTER_EXHAUST = 900f
        /** The turn over: the nose aims this much up towards the enemy until within [CRUISE_ALIGNMENT] rad of it. */
        const val TURN_PITCH = 0.2f
        const val CRUISE_ALIGNMENT = 0.15f
        /** The sustainer: cruise speed, its strongest push, exhaust speed, and how fast velocity follows the aim. */
        const val CRUISE_SPEED = 650f
        const val MAXIMUM_ACCELERATION = 1000f
        const val MAIN_EXHAUST = 900f
        const val VELOCITY_RESPONSE_SECONDS = 0.5f
        /** The most the nose turns off the flight path in cruise, rad. */
        const val MAXIMUM_ATTACK = 0.3f
        /**
         * The air: lift (px/s² per (px/s)² and per radian off the path, so
         * about 0.1 rad holds it level at cruise), where the body stalls
         * (rad), drag (px/s² per (px/s)²) and how much more of it at an angle.
         */
        const val LIFT_PER_PX = 0.007f
        const val STALL_ATTACK = 0.45f
        const val DRAG_PER_PX = 0.00035f
        const val ATTACK_DRAG = 4f
        /** How far short of or beyond the enemy it may come down, px. */
        const val SCATTER = 120f
        /** Dives once the target lies this steep below it (rise over run), as steep as its lift can turn. */
        const val DIVE_SLOPE = 0.7f
        /** Nose jets: spin per radian of aim left (1/s), the fastest spin, how fast the spin follows, exhaust speed. */
        const val TURN_GAIN = 5f
        const val MAXIMUM_SPIN = 3.5f
        const val SPIN_RESPONSE_SECONDS = 0.12f
        const val SIDE_EXHAUST = 500f
        /** Gas bodies a jet throws in one step at most. */
        const val MAXIMUM_TAIL_GAS = 24
        const val MAXIMUM_SIDE_GAS = 12
        /** Nozzles: the tail's this many diameters behind it, the nose jets' out to the side and back from the tip. */
        const val TAIL_JET_OUT = 1.5f
        const val SIDE_JET_OUT = 1.2f
        const val SIDE_JET_BACK = 2f
        /** Burns out and blows itself up after this long, s. */
        const val FUEL_SECONDS = 12f

        /** Signed angle from ([fromX], [fromY]) to ([toX], [toY]), rad. */
        fun angle(fromX: Float, fromY: Float, toX: Float, toY: Float) =
            atan2(fromX * toY - fromY * toX, fromX * toX + fromY * toY)
    }
}
