package dev.gravity

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

/** Screen and physics constants a scenario needs to place bodies in pixels. */
data class ScenarioSpace(
    val widthPixels: Float,
    val heightPixels: Float,
    val gravitationalConstant: Float,
    val contactDiameterPixels: Float,
) {
    val centerX get() = widthPixels * 0.5f
    val centerY get() = heightPixels * 0.5f

    /** Distances are authored for a 1440 px tall screen and scale with it. */
    fun scaled(pixelsAt1440: Float) = pixelsAt1440 * heightPixels / 1440f

    /**
     * Body counts are authored for a 1440 px tall screen and reference-size
     * bodies. They scale with the screen area and inversely with the body
     * area, so planets and discs keep their size on screen. Larger screens or
     * smaller bodies keep the authored counts (the limit) and are sparser.
     */
    fun bodies(countAt1440: Int): Int {
        val screen = (heightPixels / 1440f) * (heightPixels / 1440f)
        val body = CollisionConfig.REFERENCE_DIAMETER_PIXELS / contactDiameterPixels
        return maxOf(1, (countAt1440 * minOf(1f, screen * body * body)).roundToInt())
    }

    /**
     * Circular speed in px/s around an axisymmetric mass that holds
     * [enclosedFraction] of all bodies within [radiusPixels].
     *
     * Gravity is 2D (force ∝ 1/r), so v² = G·f·H² does not depend on r. The
     * periodic solver drops the k = 0 mode, i.e. it subtracts a uniform
     * background holding all the mass; the part of it inside the orbit
     * counteracts the central attraction.
     */
    fun circularSpeed(enclosedFraction: Float, radiusPixels: Float): Float {
        val background = PI.toFloat() * radiusPixels * radiusPixels /
            (widthPixels * heightPixels)
        val effective = (enclosedFraction - background).coerceAtLeast(0f)
        return sqrt(gravitationalConstant * effective) * heightPixels
    }
}

/** A start offered by the scenario menu. */
sealed interface MenuScenario {
    val title: String
    /** Name for --scenario, e.g. cosmic-web. */
    val optionName: String
}

/**
 * A rectangle of bodies glued into a block (GravityPipeline.spawnBlock), in
 * pixels, with its mass per body and the rigid motion it starts with.
 */
data class ScenarioBlock(
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float,
    val density: Float,
    val velocityX: Float = 0f,
    val velocityY: Float = 0f,
    val spin: Float = 0f,
)

/** Bodies of one scenario, in pixels and px/s; y points up as in physics. */
class ScenarioBuilder(val space: ScenarioSpace, val random: Random) {
    private var data = FloatArray(4 * 1024)
    var count = 0
        private set
    /** Glued blocks, spawned after the loose bodies. */
    val blocks = ArrayList<ScenarioBlock>()

    fun block(
        minX: Float,
        minY: Float,
        maxX: Float,
        maxY: Float,
        density: Float,
        velocityX: Float = 0f,
        velocityY: Float = 0f,
        spin: Float = 0f,
    ) {
        blocks += ScenarioBlock(minX, minY, maxX, maxY, density, velocityX, velocityY, spin)
    }

    /** A packed round ball of loose bodies, like a projectile of [diameter] px. */
    fun ball(diameter: Float, centerX: Float, centerY: Float, velocityX: Float, velocityY: Float) {
        CircularProjectile.offsets(space.contactDiameterPixels, diameter).forEach {
            add(centerX + it.x, centerY + it.y, velocityX, velocityY)
        }
    }

    fun add(x: Float, y: Float, velocityX: Float = 0f, velocityY: Float = 0f) {
        if (data.size < (count + 1) * 4) data = data.copyOf(data.size * 2)
        data[count * 4] = x
        data[count * 4 + 1] = y
        data[count * 4 + 2] = velocityX
        data[count * 4 + 3] = velocityY
        count++
    }

    /** Coordinate [axis] (0 = x, 1 = y) of body [index], in pixels. */
    fun position(index: Int, axis: Int) = data[index * 4 + axis]

    fun setVelocity(index: Int, velocityX: Float, velocityY: Float) {
        data[index * 4 + 2] = velocityX
        data[index * 4 + 3] = velocityY
    }

    fun gaussian(): Float {
        val u = random.nextDouble().coerceAtLeast(1e-12)
        return (sqrt(-2.0 * ln(u)) * cos(2.0 * PI * random.nextDouble())).toFloat()
    }

    fun angle() = random.nextFloat() * 2f * PI.toFloat()

    /** Hexagonally packed disc of at least [bodies] bodies, at contact distance. */
    fun packedDisc(
        bodies: Int,
        centerX: Float,
        centerY: Float,
        velocityX: Float = 0f,
        velocityY: Float = 0f,
    ): Float {
        val diameter = CircularProjectile.diameterForBodyCount(space.contactDiameterPixels, bodies)
        CircularProjectile.offsets(space.contactDiameterPixels, diameter).forEach {
            add(centerX + it.x, centerY + it.y, velocityX, velocityY)
        }
        return diameter * 0.5f
    }

    /**
     * Places [bodies] free bodies in the annulus [inner]..[outer] around
     * ([centerX], [centerY]), keeping each candidate with probability
     * [weight] (0..1) of its radius and angle, and calls [place] for it.
     *
     * Candidates come from a jittered hexagonal lattice rather than from
     * independent random points: independent points often start closer than
     * a body diameter, and the collision solver pushes such pairs apart at
     * hundreds of px/s, which sprays a haze of fast bodies over the screen.
     */
    fun scatter(
        bodies: Int,
        centerX: Float,
        centerY: Float,
        inner: Float,
        outer: Float,
        weight: (r: Float, angle: Float) -> Float = { _, _ -> 1f },
        place: (x: Float, y: Float, r: Float, angle: Float) -> Unit,
    ) {
        val diameter = space.contactDiameterPixels
        val spacing = LATTICE_SPACING * diameter
        val jitter = 0.5f * (spacing - diameter)
        val rowStep = spacing * sqrt(3f) * 0.5f
        val rows = (outer / rowStep).toInt() + 1
        val columns = (outer / spacing).toInt() + 1
        val candidates = ArrayList<Long>()
        for (row in -rows..rows) {
            for (column in -columns..columns) {
                val x = (column + if (row and 1 == 0) 0f else 0.5f) * spacing +
                    (random.nextFloat() * 2f - 1f) * jitter
                val y = row * rowStep + (random.nextFloat() * 2f - 1f) * jitter
                if (sqrt(x * x + y * y) in inner..outer) {
                    candidates += (x.toRawBits().toLong() shl 32) or
                        (y.toRawBits().toLong() and 0xffffffffL)
                }
            }
        }
        candidates.shuffle(random)
        var placed = 0
        for (candidate in candidates) {
            if (placed == bodies) break
            val x = Float.fromBits((candidate ushr 32).toInt())
            val y = Float.fromBits(candidate.toInt())
            val r = sqrt(x * x + y * y)
            val angle = atan2(y, x)
            if (random.nextFloat() >= weight(r, angle)) continue
            place(centerX + x, centerY + y, r, angle)
            placed++
        }
        require(placed == bodies) {
            "Only $placed of $bodies bodies fit between radii $inner and $outer"
        }
    }

    /**
     * Free bodies between [inner] and [outer] on circular orbits around
     * ([centerX], [centerY]) with surface density ∝ exp(-r / [scaleLength]).
     * [centralBodies] lie inside [inner]; [totalBodies] is the whole scenario,
     * which sets the gravity normalisation. [arms] > 0 seeds logarithmic
     * spiral arms of the given density contrast.
     */
    fun rotatingDisc(
        bodies: Int,
        centerX: Float,
        centerY: Float,
        inner: Float,
        outer: Float,
        scaleLength: Float,
        centralBodies: Int,
        totalBodies: Int,
        clockwise: Boolean = false,
        dispersion: Float = 0f,
        velocityX: Float = 0f,
        velocityY: Float = 0f,
        arms: Int = 0,
        armContrast: Float = 0f,
    ) {
        val innerMass = enclosedExponential(inner / scaleLength)
        val outerMass = enclosedExponential(outer / scaleLength)
        val winding = 1f / tan(ARM_PITCH_RADIANS)
        val sign = if (clockwise) -1f else 1f
        val weight = { r: Float, angle: Float ->
            var acceptance = exp(-(r - inner) / scaleLength)
            if (arms > 0) {
                val phase = arms * (angle - sign * winding * ln(r / inner))
                acceptance *= (1f + armContrast * cos(phase)) / (1f + armContrast)
            }
            acceptance
        }
        scatter(bodies, centerX, centerY, inner, outer, weight) { x, y, r, angle ->
            val disc = (enclosedExponential(r / scaleLength) - innerMass) / (outerMass - innerMass)
            val speed = space.circularSpeed((centralBodies + disc * bodies) / totalBodies, r)
            val tangential = sign * speed * (1f + dispersion * gaussian())
            val radial = dispersion * speed * gaussian()
            add(
                x,
                y,
                velocityX + cos(angle) * radial - sin(angle) * tangential,
                velocityY + sin(angle) * radial + cos(angle) * tangential,
            )
        }
    }

    /**
     * Periodic plane waves with random phases on integer wave numbers up to
     * ([maximumX], [maximumY]) cycles per screen, weighted towards large
     * scales by 1/|n|.
     */
    fun waves(modes: Int, maximumX: Int, maximumY: Int): List<Wave> = List(modes) {
        var nx: Int
        var ny: Int
        do {
            nx = random.nextInt(-maximumX, maximumX + 1)
            ny = random.nextInt(-maximumY, maximumY + 1)
        } while (nx == 0 && ny == 0)
        Wave(
            2f * PI.toFloat() * nx / space.widthPixels,
            2f * PI.toFloat() * ny / space.heightPixels,
            angle(),
            1f / sqrt((nx * nx + ny * ny).toFloat()),
        )
    }

    /** Shifts every velocity so the total momentum is zero and nothing drifts. */
    fun removeNetMomentum() {
        var velocityX = 0.0
        var velocityY = 0.0
        for (i in 0 until count) {
            velocityX += data[i * 4 + 2]
            velocityY += data[i * 4 + 3]
        }
        val meanX = (velocityX / count).toFloat()
        val meanY = (velocityY / count).toFloat()
        for (i in 0 until count) {
            data[i * 4 + 2] -= meanX
            data[i * 4 + 3] -= meanY
        }
    }

    /** Positions normalised and wrapped onto the torus, velocities per screen. */
    fun normalized(): FloatArray {
        val result = FloatArray(count * 4)
        for (i in 0 until count) {
            val x = data[i * 4] / space.widthPixels
            val y = data[i * 4 + 1] / space.heightPixels
            result[i * 4] = (x - floor(x)).let { if (it >= 1f) 0f else it }
            result[i * 4 + 1] = (y - floor(y)).let { if (it >= 1f) 0f else it }
            result[i * 4 + 2] = data[i * 4 + 2] / space.widthPixels
            result[i * 4 + 3] = data[i * 4 + 3] / space.heightPixels
        }
        return result
    }

    private companion object {
        /** Lattice spacing in body diameters; the jitter keeps pairs ≥ 1 diameter apart. */
        const val LATTICE_SPACING = 1.3f
        val ARM_PITCH_RADIANS = (20.0 * PI / 180.0).toFloat()
    }
}

/** One periodic plane wave: wave vector in rad/px, phase and amplitude weight. */
data class Wave(val kx: Float, val ky: Float, val phase: Float, val weight: Float) {
    fun sine(x: Float, y: Float) = sin(kx * x + ky * y + phase)
}

/** RMS of a sum of random-phase waves whose terms have amplitude [weight]·|k|^[power]. */
private fun List<Wave>.rms(power: Int): Float = sqrt(sumOf { wave ->
    val k = sqrt(wave.kx * wave.kx + wave.ky * wave.ky)
    var amplitude = wave.weight
    repeat(power) { amplitude *= k }
    amplitude.toDouble() * amplitude / 2.0
}.toFloat())

/**
 * Collisions are perfectly inelastic, so every scenario eventually condenses.
 * Two things keep structure alive: rotation, because angular momentum
 * survives the collisions, and a dominant central mass, because a light disc
 * around it rarely collides with itself.
 */
enum class Scenario(override val title: String, private val build: ScenarioBuilder.() -> Unit) : MenuScenario {
    TURBULENT_CLOUD("Turbulent cloud", {
        // A rotating cloud stirred by divergence-free eddies (from a random
        // stream function). Mostly held up by its spin, it flattens into a
        // disc that fragments into spiral arms and clumps, which orbit and
        // merge one by one.
        val radius = space.scaled(Scenario.OUTER_RADIUS - 10f)
        val modes = waves(24, 10, 5)
        val eddies = space.scaled(40f) / modes.rms(1)
        scatter(space.bodies(140_000), space.centerX, space.centerY, 0f, radius) { x, y, r, angle ->
            // A uniform disc encloses (r/R)² of the mass.
            val spin = 0.75f * space.circularSpeed((r / radius) * (r / radius), r)
            var velocityX = -sin(angle) * spin
            var velocityY = cos(angle) * spin
            for (wave in modes) {
                // u = ∂ψ/∂y, v = -∂ψ/∂x for ψ = Σ w·cos(k·x + φ).
                val s = wave.weight * wave.sine(x, y) * eddies
                velocityX -= wave.ky * s
                velocityY += wave.kx * s
            }
            add(x, y, velocityX, velocityY)
        }
    }),

    GALAXY("Spiral galaxy", {
        // A heavy packed bulge and a light disc with seeded two-arm structure.
        // The bulge dominates the gravity, so the disc collides rarely and
        // its arms keep winding for many orbits.
        val bulge = packedDisc(space.bodies(120_000), space.centerX, space.centerY)
        val bulgeBodies = count
        rotatingDisc(
            space.bodies(Scenario.MAXIMUM_BODIES) - bulgeBodies, space.centerX, space.centerY,
            inner = bulge + space.scaled(25f),
            outer = space.scaled(Scenario.OUTER_RADIUS),
            scaleLength = space.scaled(180f),
            centralBodies = bulgeBodies,
            totalBodies = space.bodies(Scenario.MAXIMUM_BODIES),
            dispersion = 0.04f,
            arms = 2,
            armContrast = 0.6f,
        )
    }),

    GALAXY_COLLISION("Galaxy collision", {
        // Two disc galaxies with heavy cores on a bound orbit. The one that
        // spins with the orbit throws long tidal tails at the close passage;
        // the other, spinning against it, is only ruffled. Then they merge.
        val coreBodies = space.bodies(60_000)
        val discBodies = space.bodies(30_000)
        val total = 2 * (coreBodies + discBodies)
        val offset = space.scaled(620f)
        // Released at apocentre with little angular momentum: the orbit is a
        // long ellipse along the wide screen axis, so the close fly-by stays
        // in view, and the discs' drag brings the cores back to merge.
        val orbitSpeed = 0.4f * space.circularSpeed(1f, offset)
        for (side in listOf(-1f, 1f)) {
            val centerX = space.centerX + side * offset
            val velocityY = side * 0.5f * orbitSpeed
            val core = packedDisc(coreBodies, centerX, space.centerY, 0f, velocityY)
            rotatingDisc(
                discBodies, centerX, space.centerY,
                inner = core + space.scaled(25f),
                outer = space.scaled(330f),
                scaleLength = space.scaled(120f),
                centralBodies = coreBodies,
                totalBodies = total,
                clockwise = side > 0f,
                dispersion = 0.03f,
                velocityY = velocityY,
            )
        }
    }),

    SPINNING_PLANET("Spinning planet", {
        // A packed planet spinning faster than its outer layers can hold:
        // the equator sheds spiral streams that wrap into a ring, while the
        // bound core stays behind as a spinning body.
        val radius = packedDisc(space.bodies(170_000), space.centerX, space.centerY)
        // Rigid-like rotation as a multiple of the local circular speed of a
        // uniform disc: bound inside, beyond break-up near the surface.
        for (i in 0 until count) {
            val x = position(i, 0) - space.centerX
            val y = position(i, 1) - space.centerY
            val r = sqrt(x * x + y * y)
            val fraction = (r / radius) * (r / radius)
            val speed = (0.85f + 0.4f * fraction) * space.circularSpeed(fraction, r)
            val inverse = if (r > 0f) 1f / r else 0f
            setVelocity(i, -y * inverse * speed, x * inverse * speed)
        }
    }),

    PLANETARY_IMPACT("Planetary impact", {
        // A grazing giant impact: the impactor clips the planet's limb,
        // splashes a debris disc and partly falls back as a spinning body.
        val distance = space.scaled(800f)
        val speed = space.circularSpeed(1f, distance)
        packedDisc(space.bodies(150_000), space.centerX, space.centerY)
        // Angular momentum chosen so that, after the infall speeds it up,
        // the pericentre lies about at the planet's surface.
        packedDisc(space.bodies(30_000), space.centerX - distance, space.centerY, 0.5f * speed, -0.55f * speed)
    }),

    RINGED_PLANET("Ringed planet", {
        // A heavy planet inside a light ring of free bodies on circular
        // orbits. Self-gravity in the ring raises spiral wakes that keep
        // shearing out, a long-lived pattern like Saturn's rings.
        packedDisc(space.bodies(136_000), space.centerX, space.centerY)
        val planetBodies = count
        rotatingDisc(
            space.bodies(Scenario.MAXIMUM_BODIES) - planetBodies, space.centerX, space.centerY,
            inner = space.scaled(320f),
            outer = space.scaled(Scenario.OUTER_RADIUS),
            scaleLength = space.scaled(10_000f),
            centralBodies = planetBodies,
            totalBodies = space.bodies(Scenario.MAXIMUM_BODIES),
            dispersion = 0.01f,
        )
    }),

    BINARY_STARS("Binary stars", {
        // Two heavy packed stars on a circular orbit inside a light
        // circumbinary disc. The binary's rotating pull drives spiral streams
        // through the disc and pulls material across the gap onto the stars.
        val starBodies = space.bodies(55_000)
        val separation = space.scaled(225f)
        // Each star circles the centre at `separation`, pulled by the other
        // from twice that distance: the same as a central f/2.
        val starSpeed = space.circularSpeed(
            starBodies.toFloat() / space.bodies(190_000) / 2f,
            separation,
        )
        packedDisc(starBodies, space.centerX - separation, space.centerY, 0f, -starSpeed)
        packedDisc(starBodies, space.centerX + separation, space.centerY, 0f, starSpeed)
        val starCount = count
        rotatingDisc(
            space.bodies(190_000) - starCount, space.centerX, space.centerY,
            inner = space.scaled(420f),
            outer = space.scaled(Scenario.OUTER_RADIUS),
            scaleLength = space.scaled(10_000f),
            centralBodies = starCount,
            totalBodies = space.bodies(190_000),
            dispersion = 0.02f,
        )
    }),

    GALAXY_FLYBY("Galaxy flyby", {
        // Two compact galaxies sweep past opposite edges of a disc without
        // touching it, each moving with the disc's rotation where it passes.
        // Their tides pull the disc into a symmetric pair of long arms. The
        // passes cancel each other's kick, so the disc stays in place; on the
        // torus the companions come round again every ~10 s.
        // The core must dominate: a heavy disc would collapse on itself.
        val core = packedDisc(space.bodies(100_000), space.centerX, space.centerY)
        val coreBodies = count
        val discBodies = space.bodies(70_000)
        val companionBodies = space.bodies(14_900)
        val total = coreBodies + discBodies + 2 * companionBodies
        val outer = space.scaled(430f)
        rotatingDisc(
            discBodies, space.centerX, space.centerY,
            inner = core + space.scaled(20f),
            outer = outer,
            scaleLength = space.scaled(10_000f),
            centralBodies = coreBodies,
            totalBodies = total,
            dispersion = 0.02f,
        )
        val speed = 1.3f * space.circularSpeed(1f, outer)
        val lead = space.scaled(1300f)
        val height = space.scaled(590f)
        // The disc turns counter-clockwise: leftwards above, rightwards below.
        for (side in listOf(1f, -1f)) {
            packedDisc(
                companionBodies,
                space.centerX + side * lead,
                space.centerY + side * height,
                -side * speed,
                0f,
            )
        }
    }),

    PLANETARY_SYSTEM("Planetary system", {
        // A star, two planets on circular orbits and an asteroid belt in the
        // gap between them. In 2D gravity every orbit has the same speed, so
        // the inner planet laps the belt and the belt laps the outer planet;
        // both stir it into waves. Gaps keep the planets clear of tides.
        packedDisc(space.bodies(70_000), space.centerX, space.centerY)
        val starBodies = count
        val beltBodies = space.bodies(15_000)
        val innerPlanet = Triple(space.scaled(270f), space.bodies(6_000), 0f)
        val outerPlanet = Triple(space.scaled(425f), space.bodies(8_000), 2.6f)
        val total = starBodies + innerPlanet.second + beltBodies + outerPlanet.second
        var inside = starBodies
        for ((orbit, bodies, angle) in listOf(innerPlanet, outerPlanet)) {
            val speed = space.circularSpeed(inside.toFloat() / total, orbit)
            packedDisc(
                bodies,
                space.centerX + orbit * cos(angle),
                space.centerY + orbit * sin(angle),
                -sin(angle) * speed,
                cos(angle) * speed,
            )
            inside += bodies + beltBodies
        }
        rotatingDisc(
            beltBodies, space.centerX, space.centerY,
            inner = space.scaled(320f),
            outer = space.scaled(350f),
            scaleLength = space.scaled(10_000f),
            centralBodies = starBodies + innerPlanet.second,
            totalBodies = total,
            dispersion = 0.01f,
        )
    }),

    COSMIC_WEB("Cosmic web", {
        // Zel'dovich initial conditions: a lattice displaced along a random
        // large-scale potential gradient, with growing-mode velocities, folds
        // into sheets, filaments and halos that then merge hierarchically.
        val columns = sqrt(space.bodies(Scenario.MAXIMUM_BODIES) * space.widthPixels / space.heightPixels).roundToInt()
        val rows = space.bodies(Scenario.MAXIMUM_BODIES) / columns
        val modes = waves(48, 10, 5)
        val scale = space.scaled(12f) / modes.rms(0)
        // Slow growth: the web forms over ~10 s and lingers before halos merge.
        val growthRate = 0.5f
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val x0 = (column + 0.5f) * space.widthPixels / columns
                val y0 = (row + 0.5f) * space.heightPixels / rows
                var dx = 0f
                var dy = 0f
                for (wave in modes) {
                    // Unit wave vector times the mode: -∇ of Σ w·cos(k·x + φ)/|k|.
                    val k = sqrt(wave.kx * wave.kx + wave.ky * wave.ky)
                    val s = wave.weight * wave.sine(x0, y0) / k
                    dx += wave.kx * s
                    dy += wave.ky * s
                }
                add(x0 + dx * scale, y0 + dy * scale, growthRate * dx * scale, growthRate * dy * scale)
            }
        }
    });

    override val optionName: String get() = name.lowercase().replace('_', '-')

    fun bodies(space: ScenarioSpace, seed: Long): FloatArray {
        val builder = ScenarioBuilder(space, Random(seed))
        builder.build()
        require(builder.count in 1..MAXIMUM_BODIES) {
            "$title has ${builder.count} bodies; the limit is $MAXIMUM_BODIES"
        }
        builder.removeNetMomentum()
        return builder.normalized()
    }

    companion object {
        const val MAXIMUM_BODIES = 200_000

        /**
         * Structures stay within this radius (at 1440 px height) of the
         * centre. Beyond ~H/3 the periodic images above and below pull a
         * disc out of shape and its orbits are no longer circular.
         */
        const val OUTER_RADIUS = 480f
    }
}

/** Fraction of an infinite exponential disc's mass within x scale lengths. */
private fun enclosedExponential(x: Float): Float = 1f - (1f + x) * exp(-x)
