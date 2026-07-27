package dev.gravity

import java.nio.FloatBuffer
import java.security.SecureRandom
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

data class GalaxyPreset(
    val outerRadius: Float = 0.38f,
    val diskScaleRadius: Float = 0.108f,
    val bulgeScaleRadius: Float = 0.027f,
    val blackHoleMassFraction: Float = 0.015f,
    val bulgeMassFraction: Float = 0.185f,
    val haloMassFraction: Float = 0.35f,
    val haloScaleRadius: Float = 0.16f,
    val bulgeBodyFraction: Float = 0.12f,
    val velocityDispersion: Float = 0.045f,
    val clockwise: Boolean = true,
) {
    val diskMassFraction: Float
        get() = 1f - blackHoleMassFraction - bulgeMassFraction - haloMassFraction

    val liveMassFraction: Float
        get() = 1f - haloMassFraction

    companion object {
        fun fittingViewportHeight(viewportAspect: Float): GalaxyPreset {
            require(viewportAspect >= 1f)
            val radius = 0.5f / viewportAspect
            return GalaxyPreset(
                outerRadius = radius,
                diskScaleRadius = radius * 0.2842f,
                bulgeScaleRadius = radius * 0.0711f,
                haloScaleRadius = radius * 0.62f,
            )
        }
    }
}

data class GalaxyInitialState(
    val massPrefix: DoubleArray,
    val normalizationMass: Float,
    val maximumPhysicalRadius: Float,
    val centralBodyIds: IntArray = intArrayOf(0),
    val galaxySeeds: LongArray = longArrayOf(),
)

object GalaxyInitializer {
    private val seedSource = SecureRandom()

    fun fill(
        bodies: FloatBuffer,
        masses: FloatBuffer,
        count: Int,
        domainAspect: Float,
        gravity: Float,
        preset: GalaxyPreset = GalaxyPreset(),
        seed: Long = seedSource.nextLong(),
    ): GalaxyInitialState {
        require(count >= 3)
        require(domainAspect >= 1f)
        val random = Random(seed)
        val bulgeCount = (count * preset.bulgeBodyFraction).toInt().coerceIn(1, count - 2)
        val diskCount = count - bulgeCount - 1
        val blackHoleWeight = count * preset.blackHoleMassFraction
        val bulgeWeight = count * preset.bulgeMassFraction / bulgeCount
        val diskWeight = count * preset.diskMassFraction / diskCount
        val prefix = DoubleArray(count + 1)

        putBody(bodies, 0.5f, 0.5f, 0f, 0f)
        masses.put(blackHoleWeight)
        prefix[1] = blackHoleWeight.toDouble()

        var maximumRadius = 0f
        for (id in 1 until count) {
            val isBulge = id <= bulgeCount
            val radius = if (isBulge) {
                sampleBulgeRadius(random, preset)
            } else {
                sampleDiskRadius(random, preset)
            }
            // No imposed arms or azimuthal modes: shot noise in this cold,
            // self-gravitating disk is the seed for emergent spiral structure.
            val angle = random.nextFloat() * (2f * PI.toFloat())
            val radialNoise = gaussian(random) * preset.velocityDispersion
            val tangentialNoise = gaussian(random) * preset.velocityDispersion * 0.45f
            val enclosedMass = enclosedMassFraction(radius, preset)
            // The solver uses the 2D logarithmic Green's function (force 1/r).
            // For an axisymmetric system Gauss' law gives v² = G M(<r).
            val circularSpeed = sqrt(gravity * enclosedMass)
            val speed = circularSpeed * (if (isBulge) 0.82f else 1f)
            val radialVelocity = radialNoise * circularSpeed
            val rotationDirection = if (preset.clockwise) -1f else 1f
            val tangentialVelocity = rotationDirection * speed * (1f + tangentialNoise)
            val cosAngle = cos(angle)
            val sinAngle = sin(angle)
            val physicalVx = radialVelocity * cosAngle - tangentialVelocity * sinAngle
            val physicalVy = radialVelocity * sinAngle + tangentialVelocity * cosAngle

            putBody(
                bodies,
                0.5f + radius * cosAngle / domainAspect,
                0.5f + radius * sinAngle,
                physicalVx / domainAspect,
                physicalVy,
            )
            val mass = if (isBulge) bulgeWeight else diskWeight
            masses.put(mass)
            prefix[id + 1] = prefix[id] + mass
            maximumRadius = max(maximumRadius, radius)
        }
        return GalaxyInitialState(
            massPrefix = prefix,
            normalizationMass = count.toFloat(),
            maximumPhysicalRadius = maximumRadius,
            galaxySeeds = longArrayOf(seed),
        )
    }

    fun fillTwin(
        bodies: FloatBuffer,
        masses: FloatBuffer,
        count: Int,
        domainAspect: Float,
        gravity: Float,
        preset: GalaxyPreset,
        seeds: LongArray = distinctSeeds(),
    ): GalaxyInitialState {
        require(count >= 6 && count % 2 == 0) { "Twin disks require an even body count" }
        require(seeds.size == 2) { "Twin disks require exactly two random seeds" }
        require(seeds[0] != seeds[1]) { "Each disk must use a different random seed" }
        val bodiesPerGalaxy = count / 2
        val leftCenterX = preset.outerRadius
        val rightCenterX = 1f - preset.outerRadius
        val prefix = DoubleArray(count + 1)
        var maximumRadius = 0f

        for (galaxy in 0..1) {
            val localBodies = FloatBuffer.allocate(bodiesPerGalaxy * 4)
            val localMasses = FloatBuffer.allocate(bodiesPerGalaxy)
            val localState = fill(
                bodies = localBodies,
                masses = localMasses,
                count = bodiesPerGalaxy,
                domainAspect = domainAspect,
                gravity = gravity * 0.5f,
                preset = preset.copy(
                    clockwise = if (galaxy == 0) preset.clockwise else !preset.clockwise,
                ),
                seed = seeds[galaxy],
            )
            maximumRadius = max(maximumRadius, localState.maximumPhysicalRadius)
            val centerX = if (galaxy == 0) leftCenterX else rightCenterX
            for (localId in 0 until bodiesPerGalaxy) {
                val source = localId * 4
                putBody(
                    bodies,
                    centerX + localBodies[source] - 0.5f,
                    localBodies[source + 1],
                    localBodies[source + 2],
                    localBodies[source + 3],
                )
                val mass = localMasses[localId]
                masses.put(mass)
                val globalId = galaxy * bodiesPerGalaxy + localId
                prefix[globalId + 1] = prefix[globalId] + mass
            }
        }
        return GalaxyInitialState(
            massPrefix = prefix,
            normalizationMass = count.toFloat(),
            maximumPhysicalRadius = maximumRadius,
            centralBodyIds = intArrayOf(0, bodiesPerGalaxy),
            galaxySeeds = seeds.copyOf(),
        )
    }

    private fun distinctSeeds(): LongArray {
        val first = seedSource.nextLong()
        var second: Long
        do {
            second = seedSource.nextLong()
        } while (second == first)
        return longArrayOf(first, second)
    }

    private fun sampleDiskRadius(random: Random, preset: GalaxyPreset): Float {
        while (true) {
            val u1 = max(random.nextFloat(), 1e-7f)
            val u2 = max(random.nextFloat(), 1e-7f)
            val radius = -preset.diskScaleRadius * ln(u1 * u2)
            if (radius in 0.004f..preset.outerRadius) return radius
        }
    }

    private fun sampleBulgeRadius(random: Random, preset: GalaxyPreset): Float {
        while (true) {
            val root = sqrt(random.nextFloat().coerceAtMost(0.9999f))
            val radius = preset.bulgeScaleRadius * root / (1f - root)
            if (radius in 0.001f..preset.outerRadius) return radius
        }
    }

    private fun enclosedMassFraction(radius: Float, preset: GalaxyPreset): Float {
        val bulge = preset.bulgeMassFraction *
            radius * radius / ((radius + preset.bulgeScaleRadius) * (radius + preset.bulgeScaleRadius))
        val x = radius / preset.diskScaleRadius
        val outerX = preset.outerRadius / preset.diskScaleRadius
        val diskNormalization = 1f - exp(-outerX) * (1f + outerX)
        val disk = preset.diskMassFraction * (1f - exp(-x) * (1f + x)) / diskNormalization
        val halo = preset.haloMassFraction * radius * radius /
            (radius * radius + preset.haloScaleRadius * preset.haloScaleRadius)
        return preset.blackHoleMassFraction + bulge + disk + halo
    }

    private fun gaussian(random: Random): Float {
        val u1 = max(random.nextDouble(), 1e-12)
        val u2 = random.nextDouble()
        return (sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)).toFloat()
    }

    private fun putBody(
        bodies: FloatBuffer,
        x: Float,
        y: Float,
        vx: Float,
        vy: Float,
    ) {
        bodies.put(x)
        bodies.put(y)
        bodies.put(vx)
        bodies.put(vy)
    }
}
