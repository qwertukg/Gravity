package dev.gravity

import org.lwjgl.opengl.GL11.glFinish
import org.lwjgl.opengl.GL32.GL_SYNC_FLUSH_COMMANDS_BIT
import org.lwjgl.opengl.GL32.GL_SYNC_GPU_COMMANDS_COMPLETE
import org.lwjgl.opengl.GL32.glClientWaitSync
import org.lwjgl.opengl.GL32.glDeleteSync
import org.lwjgl.opengl.GL32.glFenceSync
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.math.sqrt

/**
 * Breaking blocks on the GPU: big blocks struck fast, measured from the
 * moment of the shot. Prints the wall time per step (splitting included), the
 * GPU stages, and what the run left with a hash of the final state, so
 * revisions can be compared for speed and for identical results. At most
 * [FRAMES_IN_FLIGHT] steps are queued, as SwapBuffers holds the application:
 * a CPU racing ahead would only measure how deep the driver queues.
 *
 * Arguments: label, scenes (comma separated, all by default), repetitions.
 */
object BlockBenchmark {
    private const val WIDTH = 3440
    private const val HEIGHT = 1440
    private const val FRAMES_IN_FLIGHT = 2
    private val STEPS = System.getenv("BLOCK_STEPS")?.toInt() ?: 240

    /** BULLET and SLOW_BULLET: the `+` key's bullet of 50,000 bodies, at 3000 and 1500 px/s. */
    private enum class Scene { SHOT, BIG, RAM, SAND, BULLET, SLOW_BULLET }

    @JvmStatic
    fun main(args: Array<String>) {
        val label = args.getOrNull(0) ?: "run"
        val filter = args.getOrNull(1)?.takeIf { it.isNotBlank() && it != "all" }?.split(',')?.map { it.uppercase() }?.toSet()
        val repetitions = args.getOrNull(2)?.toInt() ?: 1
        val output = Path.of("build", "block-benchmark")
        Files.createDirectories(output)
        val lines = ArrayList<String>()
        CollisionGpuContext().use {
            for (scene in Scene.entries) {
                if (filter != null && scene.name !in filter) continue
                repeat(repetitions) { repetition -> lines += run(scene, repetition + 1).also(::println) }
            }
        }
        Files.write(output.resolve("$label.txt"), lines)
    }

    private fun run(scene: Scene, repetition: Int): String {
        val diameter = CollisionConfig.REFERENCE_DIAMETER_PIXELS
        val loose = if (scene == Scene.SAND) sand(diameter) else floatArrayOf(2f / WIDTH, 1f - 2f / HEIGHT, 0f, 0f)
        val config = GravityConfig(
            particles = ParticleConfig(capacity = 420_000, initialBodies = loose.size / 4, seed = 1,
                spatialSortInterval = if (System.getenv("BLOCK_DUMP") != null) 0 else 2),
            collisions = CollisionConfig(adaptive = false, maximumSubsteps = SUBSTEPS, sleep = System.getenv("BLOCK_SLEEP") != "0"),
            projectiles = if (UNBREAKABLE) ProjectileConfig(blockStrength = Float.POSITIVE_INFINITY) else ProjectileConfig(),
        )
        val profiler = GpuProfiler(STEPS + 1, 512, STEPS + 16)
        return profiler.use {
            GravityPipeline(config, WIDTH, HEIGHT, profiler, loose, bounded = true).use { pipeline ->
                pipeline.mutualGravity = false
                var active = loose.size / 4
                val cx = WIDTH * 0.5f
                val cy = HEIGHT * 0.5f
                when (scene) {
                    Scene.SHOT -> {
                        active += pipeline.spawnBlock(active, cx - 300f, cy - 150f, cx + 300f, cy + 150f, 0.5f)
                        active += pipeline.spawnProjectile(active, if (CLOSE) cx - 352f else cx - 650f, cy, 4000f, 0f, 100f)
                    }
                    Scene.BIG -> {
                        active += pipeline.spawnBlock(active, cx - 450f, cy - 200f, cx + 450f, cy + 200f, 0.5f)
                        active += pipeline.spawnProjectile(active, if (CLOSE) cx - 502f else cx - 800f, cy + 50f, 6000f, 0f, 100f)
                    }
                    Scene.BULLET, Scene.SLOW_BULLET -> {
                        val bullet = CircularProjectile.diameterForBodyCount(diameter, ProjectileConfig().plusKeyBodyCount)
                        val speed = if (scene == Scene.BULLET) 3000f else 1500f
                        active += pipeline.spawnBlock(active, cx - 300f, cy - 150f, cx + 300f, cy + 150f, 0.5f)
                        active += pipeline.spawnProjectile(active, cx - 304f - 0.5f * bullet, cy + 22f, speed, 0f, bullet)
                    }
                    Scene.RAM -> {
                        active += pipeline.spawnBlock(active, cx - 350f, cy - 160f, cx - 30f, cy + 160f, 0.5f, velocityX = 1500f)
                        active += pipeline.spawnBlock(active, cx + 30f, cy - 100f, cx + 350f, cy + 220f, 0.5f, velocityX = -1500f)
                    }
                    Scene.SAND -> {
                        pipeline.fieldAccelerationY = -config.gravity.uniformFieldAcceleration * HEIGHT / 1440f
                        val bottom = if (CLOSE) 62f else 200f
                        when (System.getenv("BLOCK_SAND_MODE")) {
                            // Bounds of what partial neighbour reuse could save: the block at rest, or flying clear of the sand.
                            "rest" -> {
                                pipeline.fieldAccelerationY = 0f
                                active += pipeline.spawnBlock(active, cx - 200f, 600f, cx + 200f, 700f, 0.5f)
                            }
                            "fly" -> {
                                pipeline.fieldAccelerationY = 0f
                                active += pipeline.spawnBlock(active, 300f, 600f, 700f, 700f, 0.5f, velocityX = 1000f)
                            }
                            else -> active += pipeline.spawnBlock(active, cx - 200f, bottom, cx + 200f, bottom + 100f, 0.5f, velocityY = -2500f)
                        }
                    }
                }
                pipeline.initializeAcceleration(active)
                glFinish()
                val start = System.nanoTime()
                val queued = ArrayDeque<Long>()
                val governor = SubstepGovernor(config.collisions.maximumSubsteps, if (GOVERNOR) config.collisions.gpuBudgetMillis else 0.0)
                var substepSum = 0L
                repeat(STEPS) { frame ->
                    while (queued.size >= FRAMES_IN_FLIGHT) {
                        val fence = queued.removeFirst()
                        glClientWaitSync(fence, GL_SYNC_FLUSH_COMMANDS_BIT, Long.MAX_VALUE)
                        glDeleteSync(fence)
                    }
                    profiler.beginFrame(frame.toLong())
                    pipeline.step(active)
                    profiler.endFrame()
                    if (GOVERNOR) {
                        profiler.poll()
                        pipeline.collisionSubsteps = governor.update(profiler.latestFrameMillis)
                    }
                    substepSum += pipeline.collisionSubsteps
                    queued.addLast(glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0))
                    if (PROBE && (frame + 1) % 10 == 0) probe(pipeline, active, frame + 1)
                }
                queued.forEach(::glDeleteSync)
                glFinish()
                val wall = (System.nanoTime() - start) / 1e6 / STEPS
                profiler.poll()
                val summary = profiler.summary()
                check(summary.frames == STEPS && profiler.droppedFrames == 0L) { "Incomplete: ${summary.frames}/$STEPS" }
                if (SERIES) {
                    // The physics step, count and solve per ten steps: where an impact costs.
                    profiler.frames().chunked(10).forEachIndexed { chunk, frames ->
                        fun average(stage: GpuStage) = frames.map { it.milliseconds[stage.ordinal] }.filter { !it.isNaN() }.average()
                        println(String.format(Locale.ROOT, "series %3d step=%6.2f count=%6.2f list=%6.2f solve=%6.2f rigid=%6.2f", chunk * 10,
                            average(GpuStage.PHYSICS_STEP), average(GpuStage.COLLISION_COUNT), average(GpuStage.COLLISION_LIST),
                            average(GpuStage.COLLISION_SOLVE), average(GpuStage.RIGID_PROJECTION)))
                    }
                }
                fun mean(stage: GpuStage) = summary[stage]?.averageMillis ?: 0.0
                fun p95(stage: GpuStage) = summary[stage]?.p95Millis ?: 0.0
                val blocks = CollisionGpuAccess.rigidBlocks(pipeline)
                val sleep = CollisionGpuAccess.field(pipeline, "sleep")!!
                val awakeStamp = CollisionGpuAccess.field(sleep, "stamp") as Int
                val awake = CollisionGpuAccess.ints(CollisionGpuAccess.field(sleep, "bodyAwake") as Int, active).count { it == awakeStamp - 1 }
                println("awake at the end: $awake of $active bodies (stamp $awakeStamp)")
                val bodies = CollisionGpuAccess.floats(pipeline, "bodies", active * 4)
                val materials = CollisionGpuAccess.floats(pipeline, "materials", active * 4)
                check(bodies.all { it.isFinite() }) { "$scene: non-finite bodies" }
                System.getenv("BLOCK_DUMP")?.let { dump ->
                    java.io.DataOutputStream(Files.newOutputStream(Path.of("$dump-${scene.name.lowercase()}-$repetition.bin"))).use { out ->
                        out.writeInt(active)
                        bodies.forEach(out::writeFloat)
                        materials.forEach(out::writeFloat)
                    }
                }
                val grains = (0 until active).count { materials[it * 4 + 2] != 0f && materials[it * 4 + 3] < 0f }
                val pieces = (0 until active).filter { materials[it * 4 + 2] != 0f && materials[it * 4 + 3] >= 0f }
                    .map { materials[it * 4 + 3] }.distinct().size
                var momentumX = 0.0
                var momentumY = 0.0
                var energy = 0.0
                for (body in 0 until active) {
                    val mass = materials[body * 4].toDouble()
                    val vx = bodies[body * 4 + 2].toDouble() * WIDTH
                    val vy = bodies[body * 4 + 3].toDouble() * HEIGHT
                    momentumX += mass * vx
                    momentumY += mass * vy
                    energy += 0.5 * mass * (vx * vx + vy * vy)
                }
                var hash = -0x340d631b7bdddcdbL
                for (array in listOf(bodies, materials)) for (value in array) {
                    hash = (hash xor value.toRawBits().toLong()) * 0x100000001b3L
                }
                String.format(Locale.ROOT,
                    "%-5s run=%d bodies=%d wall=%.3f gpuStep=%.3f gpuStepP95=%.3f drift=%.3f build=%.3f count=%.3f list=%.3f " +
                        "solve=%.3f rigid=%.3f fracture=%.3f split=%.3f splits=%d grains=%d pieces=%d " +
                        "px=%.1f py=%.1f energy=%.4e rebuilds=%d substeps=%.1f hash=%016x",
                    scene.name.lowercase(), repetition, active, wall, mean(GpuStage.PHYSICS_STEP), p95(GpuStage.PHYSICS_STEP),
                    mean(GpuStage.COLLISION_DRIFT), mean(GpuStage.COLLISION_BUILD), mean(GpuStage.COLLISION_COUNT), mean(GpuStage.COLLISION_LIST),
                    mean(GpuStage.COLLISION_SOLVE), mean(GpuStage.RIGID_PROJECTION), mean(GpuStage.RIGID_FRACTURE),
                    blocks.splitNanos / 1e6 / STEPS, blocks.splits, grains, pieces, momentumX, momentumY, energy, CollisionGpuAccess.rebuilds(pipeline),
                    substepSum.toDouble() / STEPS, hash,
                )
            }
        }
    }

    private val PROBE = System.getenv("BLOCK_PROBE") != null
    /** Collision substeps of every step, or at most when governed. */
    private val SUBSTEPS = System.getenv("BLOCK_SUBSTEPS")?.toInt() ?: CollisionConfig().maximumSubsteps
    /** Runs fewer collision substeps while a step takes longer than the budget, as the application does. */
    private val GOVERNOR = System.getenv("BLOCK_GOVERNOR") != null
    /** Prints the GPU time of every ten steps. */
    private val SERIES = System.getenv("BLOCK_SERIES") != null
    /** Blocks that never break: no fracture, no split, for comparing revisions state by state. */
    private val UNBREAKABLE = System.getenv("BLOCK_UNBREAKABLE") != null
    /** Shots start at their target, so a few steps compare whole impacts. */
    private val CLOSE = System.getenv("BLOCK_CLOSE") != null

    /** Diagnostics: where bodies crowd into one collision cell. */
    private fun probe(pipeline: GravityPipeline, active: Int, step: Int) {
        val bodies = CollisionGpuAccess.floats(pipeline, "bodies", active * 4)
        val materials = CollisionGpuAccess.floats(pipeline, "materials", active * 4)
        val gw = ToroidalSpace.collisionGridCells(WIDTH, CollisionConfig.REFERENCE_DIAMETER_PIXELS)
        val gh = ToroidalSpace.collisionGridCells(HEIGHT, CollisionConfig.REFERENCE_DIAMETER_PIXELS)
        val cells = HashMap<Int, MutableList<Int>>()
        for (b in 0 until active) {
            val cx = (bodies[b * 4] * gw).toInt().coerceIn(0, gw - 1)
            val cy = (bodies[b * 4 + 1] * gh).toInt().coerceIn(0, gh - 1)
            cells.getOrPut(cx + cy * gw) { ArrayList() } += b
        }
        val sizes = cells.values.map { it.size }
        val crowded = cells.filter { it.value.size > 4 }
        val worst = cells.maxBy { it.value.size }
        fun kind(b: Int) = when {
            materials[b * 4 + 2] == 0f -> "loose"
            materials[b * 4 + 3] < 0f -> "grain"
            else -> "member"
        }
        val kinds = crowded.values.flatten().groupingBy(::kind).eachCount()
        val speeds = (0 until active).map { kotlin.math.hypot(bodies[it * 4 + 2] * WIDTH.toDouble(), bodies[it * 4 + 3] * HEIGHT.toDouble()) }
        println("speeds step=$step >480px/s: %.1f%%  >100px/s: %.1f%%  >20px/s: %.1f%%  >12: %.1f%%  >6: %.1f%%  >3: %.1f%%  >1: %.1f%%".format(
            100.0 * speeds.count { it > 480 } / active, 100.0 * speeds.count { it > 100 } / active, 100.0 * speeds.count { it > 20 } / active,
            100.0 * speeds.count { it > 12 } / active, 100.0 * speeds.count { it > 6 } / active, 100.0 * speeds.count { it > 3 } / active, 100.0 * speeds.count { it > 1 } / active))
        val w = worst.value
        println("probe step=$step maxCell=${sizes.max()} cells>4=${crowded.size} bodiesIn>4=${crowded.values.sumOf { it.size }} kinds=$kinds " +
            "worst@(${worst.key % gw},${worst.key / gw}) kinds=${w.groupingBy(::kind).eachCount()} " +
            "sumSq=${sizes.sumOf { it.toLong() * it }}")
        // Contacts of the last pass: the solve's work grows with their square in a crowd.
        val counts = CollisionGpuAccess.ints(pipeline, "collisionContactCounts", active).map { it and 0x7fffffff }
        val bounds = intArrayOf(8, 16, 24, 32, 64, 128)
        println("contacts step=$step max=${counts.max()} " + bounds.joinToString(" ") { b -> ">$b: ${counts.count { it > b }}" } +
            " sum=${counts.sumOf { it.toLong() }} sumSq=${counts.sumOf { it.toLong() * it }}")
        val neighborCache = CollisionGpuAccess.field(pipeline, "neighborCache")!!
        val nodes = CollisionGpuAccess.ints(CollisionGpuAccess.field(neighborCache, "nodes") as Int, active * 4)
        val ghosts = (0 until active).count { nodes[it * 4 + 3] < 0 }
        for (k in listOf("loose", "grain", "member")) {
            val ids = (0 until active).filter { kind(it) == k }
            val c = ids.map { counts[it] }
            println("kind=$k step=$step n=${ids.size} contactsMax=${c.maxOrNull()} sum=${c.sumOf { it.toLong() }} >64: ${c.count { it > 64 }} >256: ${c.count { it > 256 }}")
        }
        println("ghosts step=$step ghosts=$ghosts")
        if (System.getenv("BLOCK_FIELD")?.toInt() == step) {
            val px = ((worst.key % gw) + 0.5f) * WIDTH / gw
            val py = ((worst.key / gw) + 0.5f) * HEIGHT / gh
            field(pipeline, active, bodies, materials, px, py, w)
        }
    }

    /** Diagnostics: the shape field of the block next to ([px], [py]), and the bodies of the crowded cell. */
    private fun field(pipeline: GravityPipeline, active: Int, bodies: FloatArray, materials: FloatArray, px: Float, py: Float, crowd: List<Int>) {
        var nearest = -1
        var best = Float.MAX_VALUE
        for (b in 0 until active) {
            if (materials[b * 4 + 2] == 0f || materials[b * 4 + 3] < 0f) continue
            val dx = bodies[b * 4] * WIDTH - px
            val dy = bodies[b * 4 + 1] * HEIGHT - py
            if (dx * dx + dy * dy < best) { best = dx * dx + dy * dy; nearest = b }
        }
        val group = materials[nearest * 4 + 3].toInt()
        val groupsBuffer = CollisionGpuAccess.rigidBlocks(pipeline).groups
        val g = CollisionGpuAccess.floats(groupsBuffer, 256 * 40).copyOfRange(group * 40, group * 40 + 40)
        fun i(k: Int) = g[k].toRawBits()
        val cx = g[2]; val cy = g[3]; val cos = g[6]; val sin = g[7]
        val ox = g[16]; val oy = g[17]; val cell = g[18]
        val first = i(20); val gridW = i(21); val gridH = i(22)
        val shapesOffset = (256 * 160 + 5 * 256 * 260 * 4) / 4
        val all = CollisionGpuAccess.floats(groupsBuffer, shapesOffset + first + 2 * gridW * gridH)
        val values = all.copyOfRange(shapesOffset + first, shapesOffset + first + gridW * gridH)
        val slots = all.copyOfRange(shapesOffset + first + gridW * gridH, shapesOffset + first + 2 * gridW * gridH)
        fun local(x: Float, y: Float): Pair<Float, Float> {
            val dx = x - cx; val dy = y - cy
            return Pair(cos * dx + sin * dy, -sin * dx + cos * dy)
        }
        val (lx, ly) = local(px, py)
        val nx = ((lx - ox) / cell).toInt(); val ny = ((ly - oy) / cell).toInt()
        println("field group=$group cell=$cell crowd at node ($nx,$ny); values in diameters, * = slot of a member (alive or not)")
        for (y in ny + 8 downTo ny - 8) {
            val row = StringBuilder()
            for (x in nx - 12..nx + 12) {
                if (x < 0 || y < 0 || x >= gridW || y >= gridH) { row.append("   ----"); continue }
                val v = values[x + y * gridW] / (2 * cell)
                val slot = slots[x + y * gridW]
                row.append(String.format(Locale.ROOT, "%s%6.2f", if (slot >= 0f) "*" else " ", v))
            }
            println(row)
        }
        val crowdLocal = crowd.take(12).map { local(bodies[it * 4] * WIDTH, bodies[it * 4 + 1] * HEIGHT) }
        println("crowd nodes: " + crowdLocal.joinToString { String.format(Locale.ROOT, "(%.2f,%.2f)", (it.first - ox) / cell - (nx - 12), (it.second - oy) / cell - ny) })
    }

    /** A sand bed along the floor, a little apart so it settles without a spray. */
    private fun sand(diameter: Float): FloatArray {
        val step = 1.02f * diameter
        val rowStep = step * sqrt(3f) * 0.5f
        val values = ArrayList<Float>()
        var row = 0
        var y = 0.5f * diameter
        while (y < 60f) {
            var x = 0.5f * diameter + if (row % 2 == 0) 0f else 0.5f * step
            while (x <= WIDTH - 0.5f * diameter) {
                values += x / WIDTH
                values += y / HEIGHT
                values += 0f
                values += 0f
                x += step
            }
            row++
            y += rowStep
        }
        return values.toFloatArray()
    }
}
