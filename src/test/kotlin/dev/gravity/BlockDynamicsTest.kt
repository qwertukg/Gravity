package dev.gravity

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.lwjgl.opengl.GL11.GL_NO_ERROR
import org.lwjgl.opengl.GL11.glGetError

/**
 * Glued blocks move like solid bodies: they bounce by their restitution, slide
 * with Coulomb friction, and exchange momentum by their mass, which grows with
 * their size.
 */
class BlockDynamicsTest {
    private val size = 512
    private val diameter = CollisionConfig.REFERENCE_DIAMETER_PIXELS
    private val restitution = ProjectileConfig().blockRestitution
    private val density = ProjectileConfig().blockDensity

    /** Centre, velocity and angular velocity of a block, px and px/s. */
    private class Pose(val x: Float, val y: Float, val vx: Float, val vy: Float, val spin: Float)

    private class World(val pipeline: GravityPipeline, var active: Int, val size: Int) {
        fun step(times: Int = 1) = repeat(times) { pipeline.step(active) }

        fun block(group: Int): Pose {
            val blocks = GravityPipeline::class.java.getDeclaredField("rigidBlocks")
                .also { it.isAccessible = true }.get(pipeline)
            val buffer = RigidBlocks::class.java.getDeclaredField("groups")
                .also { it.isAccessible = true }.getInt(blocks)
            val state = CollisionGpuAccess.floats(buffer, (group + 1) * GROUP_FLOATS)
            val base = group * GROUP_FLOATS
            return Pose(state[base + 2], state[base + 3], state[base + 4], state[base + 5], state[base + 8])
        }

        /** A loose body by ID: the world never reorders them. */
        fun body(id: Int): FloatArray {
            val all = CollisionGpuAccess.floats(pipeline, "bodies", active * 4)
            return FloatArray(4) { all[id * 4 + it] * size }
        }

        fun setVelocity(from: Int, until: Int, vx: Float, vy: Float) {
            val all = CollisionGpuAccess.floats(pipeline, "bodies", active * 4)
            for (id in from until until) {
                all[id * 4 + 2] = vx / size
                all[id * 4 + 3] = vy / size
            }
            CollisionGpuAccess.upload(pipeline, all)
        }

        /** Gives bodies `from until until` the rigid motion of velocity (vx, vy) and spin [spin] rad/s. */
        fun setMotion(from: Int, until: Int, vx: Float, vy: Float, spin: Float) {
            val all = CollisionGpuAccess.floats(pipeline, "bodies", active * 4)
            val centerX = (from until until).map { all[it * 4] * size }.average().toFloat()
            val centerY = (from until until).map { all[it * 4 + 1] * size }.average().toFloat()
            for (id in from until until) {
                all[id * 4 + 2] = (vx - spin * (all[id * 4 + 1] * size - centerY)) / size
                all[id * 4 + 3] = (vy + spin * (all[id * 4] * size - centerX)) / size
            }
            CollisionGpuAccess.upload(pipeline, all)
        }

        /** Kinetic and potential energy of a block per unit of its mass, under [field] px/s². */
        fun energy(group: Int, field: Float): Float {
            val blocks = GravityPipeline::class.java.getDeclaredField("rigidBlocks")
                .also { it.isAccessible = true }.get(pipeline)
            val buffer = RigidBlocks::class.java.getDeclaredField("groups")
                .also { it.isAccessible = true }.getInt(blocks)
            val state = CollisionGpuAccess.floats(buffer, (group + 1) * GROUP_FLOATS)
            val base = group * GROUP_FLOATS
            // Inertia over mass: the sum of |r0|^2 over the member count.
            val inertia = state[base + 9] / state[base + 10]
            val vx = state[base + 4]
            val vy = state[base + 5]
            val spin = state[base + 8]
            return 0.5f * (vx * vx + vy * vy) + 0.5f * inertia * spin * spin + field * state[base + 3]
        }

        private fun state(group: Int): FloatArray {
            val blocks = GravityPipeline::class.java.getDeclaredField("rigidBlocks")
                .also { it.isAccessible = true }.get(pipeline)
            val buffer = RigidBlocks::class.java.getDeclaredField("groups")
                .also { it.isAccessible = true }.getInt(blocks)
            return CollisionGpuAccess.floats(buffer, (group + 1) * GROUP_FLOATS)
        }

        fun bigCos(group: Int) = state(group)[group * GROUP_FLOATS + 6]
        fun bigSin(group: Int) = state(group)[group * GROUP_FLOATS + 7]

        fun spawnBlock(minX: Float, minY: Float, maxX: Float, maxY: Float): Int {
            val first = active
            active += pipeline.spawnBlock(first, minX, minY, maxX, maxY)
            return first
        }
    }

    private fun world(
        field: Float,
        friction: Float = ProjectileConfig().blockFriction,
        blockDensity: Float = density,
        liquid: Boolean = false,
        // One loose body parked in a corner keeps the population non-empty.
        loose: FloatArray = floatArrayOf(2f / size, (size - 2f) / size, 0f, 0f),
        action: World.() -> Unit,
    ) {
        CollisionGpuContext().use {
            val config = GravityConfig(
                particles = ParticleConfig(capacity = 40_000, initialBodies = loose.size / 4, seed = 1, spatialSortInterval = 0),
                gravity = GravityFieldConfig(gridWidth = 128, gridHeight = 128),
                projectiles = ProjectileConfig(blockFriction = friction, blockDensity = blockDensity),
            )
            GravityPipeline(config, size, size, bounded = true).use { pipeline ->
                pipeline.mutualGravity = false
                pipeline.liquid = liquid
                pipeline.fieldAccelerationY = -field
                CollisionGpuAccess.upload(pipeline, loose)
                World(pipeline, loose.size / 4, size).action()
            }
            assertEquals(GL_NO_ERROR, glGetError())
        }
    }

    /** Impact and rebound speed of block [group] falling from rest onto what is below. */
    private fun World.bounce(group: Int, steps: Int): Pair<Float, Float> {
        pipeline.initializeAcceleration(active)
        var impact = 0f
        var rebound = 0f
        var landed = false
        repeat(steps) {
            step()
            val vy = block(group).vy
            if (!landed) {
                if (vy < impact) impact = vy else if (impact < -10f && vy > impact + 10f) landed = true
            }
            if (landed) rebound = maxOf(rebound, vy)
        }
        return -impact to rebound
    }

    private fun assertRestitution(name: String, impact: Float, rebound: Float, tolerance: Float = 0.05f) {
        println("$name: impact %.0f px/s, rebound %.0f px/s, e=%.2f".format(impact, rebound, rebound / impact))
        assertEquals(restitution, rebound / impact, tolerance, "$name: restitution")
    }

    @Test
    fun `a block bounces off the floor and walls by its restitution`() {
        for (height in listOf(100f, 300f)) {
            world(field = 300f) {
                spawnBlock(150f, height, 250f, height + 40f)
                val (impact, rebound) = bounce(0, 360)
                assertRestitution("drop from $height px onto the floor", impact, rebound)
                step(600)
                // At rest the half kick of the step's end, g dt / 2, is all the speed left.
                assertTrue(abs(block(0).vy) < 2f, "comes to rest: ${block(0).vy} px/s")
            }
        }
        world(field = 0f) {
            val first = spawnBlock(300f, 200f, 360f, 260f)
            setVelocity(first, active, 600f, 0f)
            pipeline.initializeAcceleration(active)
            step(60)
            assertRestitution("thrown into a wall", 600f, -block(0).vx)
            assertTrue(abs(block(0).spin) < 0.01f, "a square hit is not spun: ${block(0).spin} rad/s")
        }
    }

    @Test
    fun `a block thrown at an angle or spinning never gains energy and comes to rest`() {
        for ((name, motion) in listOf("thrown at an angle" to floatArrayOf(300f, -200f, 0f), "thrown spinning" to floatArrayOf(0f, -100f, 3f))) {
            world(field = 300f) {
                val first = spawnBlock(200f, 120f, 300f, 150f)
                setMotion(first, active, motion[0], motion[1], motion[2])
                pipeline.initializeAcceleration(active)
                // The block takes its members' motion at its first projection.
                step()
                var previous = energy(0, 300f)
                val start = previous
                var largestGain = 0f
                repeat(600) {
                    step()
                    val now = energy(0, 300f)
                    largestGain = maxOf(largestGain, now - previous)
                    previous = now
                }
                val pose = block(0)
                println("$name: energy per mass %.0f -> %.0f, largest gain in a step %.1f, at rest v=(%.2f, %.2f) w=%.3f"
                    .format(start, previous, largestGain, pose.vx, pose.vy, pose.spin))
                // Steps end half a gravity kick ahead of the drift, which moves
                // energy between speed and height by about v g dt / 2; an
                // impact that added energy would add a sizeable share of it.
                assertTrue(largestGain < 0.01f * start, "$name: an impact never adds energy, gained $largestGain")
                // At rest the half kick of the step's end, g dt / 2, is all the speed left.
                assertTrue(abs(pose.vx) < 2f && abs(pose.vy) < 2f && abs(pose.spin) < 0.02f, "$name: comes to rest")
            }
        }
    }

    @Test
    fun `stacked blocks rest, a big one on a small one too`() {
        for ((name, lower, upper) in listOf(
            Triple("big on small", floatArrayOf(236f, 0f, 276f, 30f), floatArrayOf(156f, 31f, 356f, 71f)),
            Triple("small on big", floatArrayOf(156f, 0f, 356f, 40f), floatArrayOf(236f, 41f, 276f, 71f)),
        )) {
            world(field = 300f) {
                spawnBlock(lower[0], lower[1], lower[2], lower[3])
                spawnBlock(upper[0], upper[1], upper[2], upper[3])
                pipeline.initializeAcceleration(active)
                step(60)
                val start = listOf(block(0), block(1))
                var fastest = 0f
                var fastestSpin = 0f
                var tilt = 0.0
                repeat(600) {
                    step()
                    for (group in 0..1) {
                        val pose = block(group)
                        fastest = maxOf(fastest, abs(pose.vx), abs(pose.vy))
                        fastestSpin = maxOf(fastestSpin, abs(pose.spin))
                        tilt = maxOf(tilt, abs(Math.toDegrees(kotlin.math.atan2(bigSin(group).toDouble(), bigCos(group).toDouble()))))
                    }
                }
                val drift = (0..1).maxOf { maxOf(abs(block(it).x - start[it].x), abs(block(it).y - start[it].y)) }
                println("$name: drift %.3f px, tilt up to %.3f°, speed up to %.2f px/s, spin up to %.4f rad/s".format(drift, tilt, fastest, fastestSpin))
                assertTrue(drift < 0.2f, "$name: the stack stays put, drifted $drift px")
                assertTrue(tilt < 0.2, "$name: the stack stays level, tilted $tilt°")
                // Contacts solved side by side leave the stack a little speed
                // that never adds up to a move.
                assertTrue(fastest < 5f && fastestSpin < 0.25f, "$name: the stack stays at rest")
            }
        }
        // A big block off the edge of a small one tips off onto the floor and
        // leans there. The leaning block presses its support sideways; the
        // support may slip while the big one falls. Leaning, the two settle
        // slowly: contacts solved side by side under a load give way a
        // little each pass, about a tenth of a degree a second here.
        world(field = 300f) {
            spawnBlock(236f, 0f, 276f, 30f)
            spawnBlock(200f, 31f, 360f, 61f)
            pipeline.initializeAcceleration(active)
            val start = block(0).x
            step(360)
            val settled = listOf(block(0), block(1))
            step(360)
            val small = block(0)
            val big = block(1)
            val creep = maxOf(abs(small.x - settled[0].x), abs(big.x - settled[1].x), abs(big.y - settled[1].y))
            println("big off the edge: small slipped %.2f px while it fell, both crept %.3f px in 3 s after; big leans at %.1f°".format(
                small.x - start, creep, Math.toDegrees(kotlin.math.atan2(bigSin(1).toDouble(), bigCos(1).toDouble()))))
            assertTrue(abs(small.x - start) < 10f, "the small block is not shoved away")
            assertTrue(creep < 3f, "once fallen, the blocks settle slowly at most")
            assertTrue(abs(big.vx) < 2f && abs(big.vy) < 2f && abs(small.vx) < 2f, "both come to rest")
        }
    }

    @Test
    fun `a block on a few liquid drops on the floor rests on them`() {
        val drops = ArrayList<Float>()
        drops += listOf(2f / size, (size - 2f) / size, 0f, 0f)
        for (k in 0 until 12) drops += listOf((230f + k * 4f) / size, 0.6f / size, 0f, 0f)
        for (blockDensity in listOf(3f, 0.5f)) {
            world(field = 300f, blockDensity = blockDensity, liquid = true, loose = drops.toFloatArray()) {
                spawnBlock(200f, 40f, 300f, 70f)
                pipeline.initializeAcceleration(active)
                step(90)
                val rest = block(0).y
                var highest = rest
                var fastest = 0f
                repeat(240) {
                    step()
                    highest = maxOf(highest, block(0).y)
                    fastest = maxOf(fastest, abs(block(0).vy))
                }
                println("density $blockDensity on drops: rests at %.2f px, rises to %.2f px, speed up to %.1f px/s".format(rest, highest, fastest))
                assertTrue(highest < rest + 0.5f, "density $blockDensity: the drops do not throw the block up")
                assertTrue(fastest < 10f, "density $blockDensity: the block stays at rest, $fastest px/s")
            }
        }
    }

    @Test
    fun `blocks bounce off each other by their restitution and keep momentum`() {
        world(field = 300f) {
            spawnBlock(100f, 0f, 400f, 30f)
            spawnBlock(200f, 130f, 300f, 170f)
            val (impact, rebound) = bounce(1, 240)
            // The block below is pressed into the floor and gives some back.
            assertRestitution("dropped onto a block", impact, rebound, tolerance = 0.08f)
        }
        // Small into big and big into small: one-dimensional impacts with the
        // restitution, by the masses, which grow with the area.
        for ((name, striker, struck) in listOf(Triple("small into big", 20f, 80f), Triple("big into small", 80f, 20f))) {
            world(field = 0f) {
                spawnBlock(380f - struck / 2, 256f - struck / 2, 380f + struck / 2, 256f + struck / 2)
                val struckMass = (active - 1) * density
                val first = spawnBlock(200f - striker / 2, 256f - striker / 2, 200f + striker / 2, 256f + striker / 2)
                val strikerMass = (active - first) * density
                setVelocity(first, active, 400f, 0f)
                pipeline.initializeAcceleration(active)
                step(45)
                val total = strikerMass + struckMass
                val expectedStruck = strikerMass * 400f * (1f + restitution) / total
                val expectedStriker = 400f * (strikerMass - restitution * struckMass) / total
                println(
                    "$name (masses %.0f, %.0f): struck %.1f px/s (%.1f), striker %.1f px/s (%.1f)"
                        .format(strikerMass, struckMass, block(0).vx, expectedStruck, block(1).vx, expectedStriker),
                )
                assertEquals(expectedStruck, block(0).vx, 0.06f * 400f, "$name: struck block")
                assertEquals(expectedStriker, block(1).vx, 0.06f * 400f, "$name: striker")
                assertEquals(strikerMass * 400f, strikerMass * block(1).vx + struckMass * block(0).vx, 0.01f * strikerMass * 400f, "$name: momentum")
            }
        }
    }

    @Test
    fun `a block slides with Coulomb friction on the floor and on a block`() {
        for (friction in listOf(0f, 0.5f)) {
            val expected = 300f - friction * 300f * 0.5f
            world(field = 300f, friction = friction) {
                val first = spawnBlock(50f, 0f, 150f, 40f)
                pipeline.initializeAcceleration(active)
                step(30)
                setVelocity(first, active, 300f, 0f)
                step(60)
                println("slide on the floor, friction $friction: %.0f px/s after 0.5 s (Coulomb %.0f)".format(block(0).vx, expected))
                assertEquals(expected, block(0).vx, 6f, "slide on the floor, friction $friction")
            }
            world(field = 300f, friction = friction) {
                spawnBlock(20f, 0f, 490f, 30f)
                val first = spawnBlock(50f, 31f, 150f, 71f)
                pipeline.initializeAcceleration(active)
                step(30)
                setVelocity(first, active, 300f, 0f)
                step(60)
                println("slide on a block, friction $friction: %.0f px/s after 0.5 s (Coulomb %.0f), base %.1f px/s".format(block(1).vx, expected, block(0).vx))
                assertEquals(expected, block(1).vx, 6f, "slide on a block, friction $friction")
            }
        }
    }

    @Test
    fun `a loose body bounces off a block, which takes its momentum by its mass`() {
        for (side in listOf(30f, 120f)) {
            world(field = 0f) {
                spawnBlock(256f - side / 2, 256f - side / 2, 256f + side / 2, 256f + side / 2)
                val mass = (active - 1) * density
                val particle = active
                active += pipeline.spawnProjectile(active, 100f, 256f, 300f, 0f, diameter)
                pipeline.initializeAcceleration(active)
                step(120)
                val rebound = -body(particle)[2]
                println("body 300 px/s into a $side px block: rebound %.0f px/s, block %.3f px/s".format(rebound, block(0).vx))
                assertEquals(restitution, rebound / 300f, 0.05f, "loose body restitution")
                assertEquals(300f, body(particle)[2] + mass * block(0).vx, 0.5f, "momentum")
            }
        }
        world(field = 300f) {
            spawnBlock(200f, 0f, 300f, 40f)
            val particle = active
            active += pipeline.spawnProjectile(active, 150f, 0.6f, 20f, 0f, diameter)
            pipeline.initializeAcceleration(active)
            val start = block(0).x
            step(360)
            println("slow body on the floor against a resting block: block moved %.3f px".format(block(0).x - start))
            assertTrue(abs(block(0).x - start) < 0.05f, "a slow body cannot shove a block on the floor")
            assertTrue(body(particle)[0] < 200f, "the body stays outside")
        }
    }

    private companion object {
        /** RigidGroup of rigid_layout.glsl in floats. */
        const val GROUP_FLOATS = 36
    }
}
