package dev.gravity

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.lwjgl.opengl.GL11.GL_NO_ERROR
import org.lwjgl.opengl.GL11.glGetError

/**
 * Glued blocks are solid: no other body, loose or glued into another block,
 * gets inside one or through it, and no block gets into a wall. "Inside" is a
 * centre beyond the block's closed outline (GlueShape): nearer a member than
 * the fill that closes the gaps between members.
 */
class BlockPenetrationTest {
    private val size = 512
    private val diameter = CollisionConfig.REFERENCE_DIAMETER_PIXELS

    private class Report(var inside: Int = 0, var depth: Float = 0f, var through: Int = 0, var wall: Float = 0f) {
        /** Where each loose body was in the first block's frame at the last step. */
        var previous: FloatArray? = null
        /** The first block's angle at the last step, radians. */
        var previousAngle = Float.NaN

        override fun toString() =
            "most inside=$inside deepest=%.3f px passed through=$through into a wall=%.3f px".format(depth, wall)
    }

    private fun scenario(
        name: String,
        bodies: FloatArray,
        liquid: Boolean,
        field: Float,
        steps: Int,
        shot: Boolean = false,
        setup: (GravityPipeline, Int) -> Int,
    ): Report {
        val count = bodies.size / 4
        val report = Report()
        shapes.clear()
        CollisionGpuContext().use {
            // Shots follow every body, so it keeps its ID.
            val config = GravityConfig(
                particles = ParticleConfig(
                    capacity = count + 40_000,
                    initialBodies = count,
                    seed = 7235,
                    spatialSortInterval = if (shot) 0 else 2,
                ),
                gravity = GravityFieldConfig(gridWidth = 128, gridHeight = 128),
                // Solid blocks: what breaks is BlockFractureTest's.
                projectiles = ProjectileConfig(blockStrength = Float.POSITIVE_INFINITY),
            )
            GravityPipeline(config, size, size, bounded = true).use { pipeline ->
                pipeline.mutualGravity = false
                pipeline.liquid = liquid
                pipeline.fieldAccelerationY = -field
                CollisionGpuAccess.upload(pipeline, bodies)
                val active = setup(pipeline, count)
                pipeline.initializeAcceleration(active)
                if (shot) report.previous = FloatArray(active * 2) { Float.NaN }
                for (step in 1..steps) {
                    pipeline.step(active)
                    if (shot || step % 5 == 0 || step == steps) measure(pipeline, active, report)
                }
            }
            assertEquals(GL_NO_ERROR, glGetError())
        }
        println("$name: $report")
        // Float rounding of the block pose is all a wall may see.
        assertTrue(report.wall < 1e-3f, "$name: blocks stay out of the walls, ${report.wall} px in")
        return report
    }

    /** Foreign bodies inside each block's outline, in the block's own frame, and blocks in walls. */
    private fun measure(pipeline: GravityPipeline, active: Int, report: Report) {
        val bodies = CollisionGpuAccess.floats(pipeline, "bodies", active * 4)
        val rigid = RigidAccess(pipeline)
        val groups = rigid.field("groupCount")
        val tiles = rigid.field("tileCount")
        val members = rigid.ints("members", tiles * RigidBlocks.TILE)
        val rest = rigid.floats("rest", tiles * RigidBlocks.TILE * 2)
        val state = rigid.floats("groups", groups * GROUP_FLOATS)
        val owner = IntArray(active) { -1 }
        var inside = 0
        for (group in 0 until groups) {
            val base = group * GROUP_FLOATS
            val firstTile = state[base + 12].toRawBits()
            val tileCount = state[base + 13].toRawBits()
            val offsets = ArrayList<PixelOffset>()
            for (entry in firstTile * RigidBlocks.TILE until (firstTile + tileCount) * RigidBlocks.TILE) {
                if (members[entry] < 0) continue
                owner[members[entry]] = group
                offsets += PixelOffset(rest[2 * entry], rest[2 * entry + 1])
            }
            val shape = shapes.getOrPut(group) { GlueShape.of(offsets, diameter) }
            val centerX = state[base + 2]
            val centerY = state[base + 3]
            val cos = state[base + 6]
            val sin = state[base + 7]
            // How far the members' discs reach into a wall, at the block's pose.
            for (offset in offsets) {
                val x = centerX + cos * offset.x - sin * offset.y
                val y = centerY + sin * offset.x + cos * offset.y
                val radius = 0.5f * diameter
                report.wall = maxOf(report.wall, radius - x, x + radius - size, radius - y, y + radius - size)
            }
            val halfY = offsets.maxOf { abs(it.y) }
            // A block turning fast swings bodies at its face from one side
            // of its frame to the other between two steps; only a block
            // turning less than a degree a step tells through from round.
            val angle = kotlin.math.atan2(sin, cos)
            val steady = group == 0 && !(abs(angle - report.previousAngle) > MAXIMUM_TURN)
            if (group == 0) report.previousAngle = angle
            for (id in 0 until active) {
                if (owner[id] == group) continue
                val dx = bodies[id * 4] * size - centerX
                val dy = bodies[id * 4 + 1] * size - centerY
                val localX = cos * dx + sin * dy
                val localY = -sin * dx + cos * dy
                report.previous?.takeIf { group == 0 && owner[id] < 0 }?.let { previous ->
                    // From one side of the block's face to the other between
                    // two steps, never past its ends: through it.
                    val beforeX = previous[2 * id]
                    val beforeY = previous[2 * id + 1]
                    if (steady && beforeX * localX < 0f && abs(beforeY) < halfY - diameter && abs(localY) < halfY - diameter) {
                        report.through++
                    }
                    previous[2 * id] = localX
                    previous[2 * id + 1] = localY
                }
                // The shape finds the candidates; the depth is exact: how far
                // the centre is within the fill around the nearest member.
                if (sample(shape, localX, localY) > 0.5f * diameter) continue
                val nearest = offsets.minOf { hypot(it.x - localX, it.y - localY) }
                val depth = GlueShape.FILL_DIAMETERS * diameter - nearest
                if (depth > 0f) {
                    inside++
                    report.depth = max(report.depth, depth)
                }
            }
        }
        report.inside = max(report.inside, inside)
    }

    private val shapes = HashMap<Int, GlueShape>()

    /** Bilinear sample of a shape, as collision_rigid.glsl does; far outside is just far. */
    private fun sample(shape: GlueShape, x: Float, y: Float): Float {
        val u = (x - shape.originX) / shape.cellPixels
        val v = (y - shape.originY) / shape.cellPixels
        if (u < 0f || v < 0f || u >= shape.width - 1 || v >= shape.height - 1) return shape.borderMinimum
        val cx = u.toInt()
        val cy = v.toInt()
        val fx = u - cx
        val fy = v - cy
        fun at(i: Int, j: Int) = shape.values[i + j * shape.width]
        val low = at(cx, cy) + (at(cx + 1, cy) - at(cx, cy)) * fx
        val high = at(cx, cy + 1) + (at(cx + 1, cy + 1) - at(cx, cy + 1)) * fx
        return low + (high - low) * fy
    }


    private fun shot(name: String, bulletDiameter: Float, speed: Float, thickness: Float, steps: Int): Report {
        val parked = floatArrayOf(5f / size, 5f / size, 0f, 0f)
        val report = scenario(name, parked, liquid = false, field = 0f, steps = steps, shot = true) { pipeline, count ->
            val block = pipeline.spawnBlock(count, 300f, 150f, 300f + thickness, 350f)
            count + block + pipeline.spawnProjectile(count + block, 100f, 250f, speed, 0f, bulletDiameter)
        }
        assertEquals(0, report.through, "$name: nothing passes through the block")
        return report
    }

    @Test
    fun `fast bullets neither pass through a block nor get into it`() {
        for (report in listOf(
            shot("bullet 3000 px/s", 30f, 3000f, 20f, 30),
            shot("bullet 6000 px/s", 30f, 6000f, 20f, 30),
            shot("bullet into a 3 px plate", 20f, 3000f, 3f, 30),
            shot("100 px cannonball 4000 px/s", 100f, 4000f, 40f, 60),
        )) {
            // At the impact a body may still be on its way out of the outline.
            assertTrue(report.depth < DYNAMIC_DEPTH * diameter, "deepest ${report.depth} px")
        }
    }

    @Test
    fun `a cannonball presses a block into a block and a wall`() {
        val parked = floatArrayOf(5f / size, 5f / size, 0f, 0f)
        val report = scenario("sandwich", parked, liquid = false, field = 0f, steps = 120) { pipeline, count ->
            var active = count
            active += pipeline.spawnBlock(active, 300f, 180f, 320f, 320f)
            active += pipeline.spawnBlock(active, 470f, 150f, 490f, 350f)
            active + pipeline.spawnProjectile(active, 150f, 250f, 4000f, 0f, 100f)
        }
        // Crushed against the wall, a block pushed out of the other one may
        // still sit on bodies at its far face until the next pass.
        assertTrue(report.depth < CRUSH_DEPTH * diameter, "deepest ${report.depth} px")
    }

    @Test
    fun `a block dropped onto a block stays on top`() {
        val parked = floatArrayOf(5f / size, 500f / size, 0f, 0f)
        val report = scenario("stack", parked, liquid = false, field = 300f, steps = 600) { pipeline, count ->
            var active = count
            active += pipeline.spawnBlock(active, 100f, 0f, 400f, 30f)
            active += pipeline.spawnBlock(active, 200f, 150f, 300f, 190f)
            active
        }
        assertEquals(0, report.inside)
    }

    @Test
    fun `a block thrown at a block bounces off`() {
        val parked = floatArrayOf(5f / size, 500f / size, 0f, 0f)
        val report = scenario("ram", parked, liquid = false, field = 0f, steps = 120) { pipeline, count ->
            var active = count
            active += pipeline.spawnBlock(active, 300f, 200f, 340f, 300f)
            val first = active
            active += pipeline.spawnBlock(active, 150f, 230f, 200f, 270f)
            val state = CollisionGpuAccess.floats(pipeline, "bodies", active * 4)
            for (id in first until active) state[id * 4 + 2] = 1500f / size
            CollisionGpuAccess.upload(pipeline, state)
            active
        }
        assertEquals(0, report.inside)
    }

    @Test
    fun `a heavy block sinks into a pile without bodies getting inside it`() {
        val random = Random(1)
        val pile = FloatArray(60_000 * 4) { i -> if (i % 4 == 0) random.nextFloat() else if (i % 4 == 1) random.nextFloat() * 0.4f else 0f }
        val report = scenario("pile", pile, liquid = false, field = 300f, steps = 600) { pipeline, count ->
            count + pipeline.spawnBlock(count, 200f, 300f, 300f, 340f, density = 3f)
        }
        // Pressed by the heavy block, bodies may reach its outline.
        assertTrue(report.depth < DYNAMIC_DEPTH * diameter, "deepest ${report.depth} px")
    }

    @Test
    fun `liquid does not get inside a sinking or a rising block`() {
        val pool = lattice(maxY = 200f)
        val report = scenario("liquid", pool, liquid = true, field = 300f, steps = 600) { pipeline, count ->
            var active = count
            active += pipeline.spawnBlock(active, 100f, 230f, 180f, 270f, density = 3f)
            active += pipeline.spawnBlock(active, 300f, 230f, 400f, 260f, density = 0.3f)
            active
        }
        assertTrue(report.depth < DYNAMIC_DEPTH * diameter, "deepest ${report.depth} px")
    }

    private fun lattice(maxY: Float): FloatArray {
        val rowStep = diameter * sqrt(3f) * 0.5f
        val values = ArrayList<Float>()
        var row = 0
        var y = 0.5f * diameter
        while (y <= maxY) {
            var x = 0.5f * diameter + if (row % 2 == 0) 0f else 0.5f * diameter
            while (x <= size - 0.5f * diameter) {
                values += listOf(x / size, y / size, 0f, 0f)
                x += diameter
            }
            y += rowStep
            row++
        }
        return values.toFloatArray()
    }

    private class RigidAccess(pipeline: GravityPipeline) {
        private val blocks = GravityPipeline::class.java.getDeclaredField("rigidBlocks")
            .also { it.isAccessible = true }.get(pipeline)

        fun field(name: String) = RigidBlocks::class.java.getDeclaredField(name)
            .also { it.isAccessible = true }.getInt(blocks)

        fun ints(name: String, length: Int) = CollisionGpuAccess.ints(field(name), length)
        fun floats(name: String, length: Int) = CollisionGpuAccess.floats(field(name), length)
    }

    private companion object {
        /** RigidGroup of rigid_layout.glsl in floats. */
        const val GROUP_FLOATS = 36
        /** One degree. */
        const val MAXIMUM_TURN = 0.0175f
        /**
         * Deepest a centre may be found within the outline, in diameters, at
         * impacts and while blocks sink or rise, and under a crush.
         */
        const val DYNAMIC_DEPTH = 0.4f
        const val CRUSH_DEPTH = 0.75f
    }
}
