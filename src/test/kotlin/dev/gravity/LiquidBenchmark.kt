package dev.gravity

import org.lwjgl.opengl.GL11.glFinish
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.math.sqrt

/**
 * GPU physics benchmark of liquid mode in the interactive start: walls, the
 * downward field, no mutual gravity. Hidden window, no readback in measured
 * frames.
 */
object LiquidBenchmark {
    private const val WIDTH = 3440
    private const val HEIGHT = 1440
    private const val SEED = 20260730L

    @JvmStatic
    fun main(args: Array<String>) {
        val warmup = args.getOrNull(0)?.toInt() ?: 600
        val measured = args.getOrNull(1)?.toInt() ?: 240
        val filter = args.getOrNull(2)?.split(',')?.toSet()
        val repetitions = args.getOrNull(3)?.toInt() ?: 1
        require(warmup >= 0 && measured > 0 && repetitions > 0)
        val output = Path.of("build", "performance-liquid")
        Files.createDirectories(output)
        val diameter = CollisionConfig.REFERENCE_DIAMETER_PIXELS
        CollisionGpuContext().use {
            // web-*: the default start, the cosmic web falling into the box.
            // pool-block: a deep liquid pool with a light block dropped into it.
            for (case in listOf("web-liquid", "web-hard", "pool-block")) {
                if (filter != null && case !in filter) continue
                repeat(repetitions) { repetition ->
                    val state = if (case.startsWith("web")) {
                        Scenario.COSMIC_WEB.bodies(ScenarioSpace(WIDTH.toFloat(), HEIGHT.toFloat(), GravityFieldConfig().gravitationalConstant, diameter), SEED)
                    } else {
                        pool(depthPixels = 70f, diameter = diameter)
                    }
                    val bodies = state.size / 4
                    val config = GravityConfig(
                        particles = ParticleConfig(capacity = 420_000, initialBodies = bodies, seed = SEED),
                    )
                    val profiler = GpuProfiler(measured + 1, 128, measured + 16)
                    profiler.use {
                        GravityPipeline(config, WIDTH, HEIGHT, profiler, state, bounded = true).use { pipeline ->
                            pipeline.mutualGravity = false
                            pipeline.liquid = case != "web-hard"
                            pipeline.fieldAccelerationY = -config.gravity.uniformFieldAcceleration
                            var active = bodies
                            if (case == "pool-block") {
                                active += pipeline.spawnBlock(active, 1500f, 200f, 1900f, 260f)
                            }
                            pipeline.initializeAcceleration(active)
                            repeat(warmup) { pipeline.step(active) }
                            glFinish()
                            repeat(measured) { frame ->
                                profiler.beginFrame(frame.toLong())
                                pipeline.step(active)
                                profiler.endFrame()
                            }
                            glFinish()
                            profiler.poll()
                            val summary = profiler.summary()
                            check(summary.frames == measured && profiler.droppedFrames == 0L) {
                                "Incomplete benchmark: ${summary.frames}/$measured, dropped=${profiler.droppedFrames}"
                            }
                            val cluster = pipeline.measureCluster(active)
                            check(cluster.nonFiniteBodies == 0)
                            fun mean(stage: GpuStage) = summary[stage]?.averageMillis ?: 0.0
                            val result = String.format(
                                Locale.ROOT,
                                "%s run=%d bodies=%d physics=%.3f p95=%.3f liquid=%.3f substeps=%.3f",
                                case, repetition + 1, active,
                                mean(GpuStage.PHYSICS_STEP), summary[GpuStage.PHYSICS_STEP]!!.p95Millis,
                                mean(GpuStage.LIQUID), mean(GpuStage.COLLISION_SUBSTEPS),
                            )
                            println(result)
                            Files.writeString(
                                output.resolve("$case-${repetition + 1}.txt"),
                                result + "\n" + summary.markdown() + "\n" + cluster + "\n",
                            )
                        }
                    }
                }
            }
        }
    }

    /** Hexagonally packed bodies at rest along the whole floor. */
    private fun pool(depthPixels: Float, diameter: Float): FloatArray {
        val rowStep = diameter * sqrt(3f) * 0.5f
        val values = ArrayList<Float>()
        var row = 0
        var y = 0.5f * diameter
        while (y <= depthPixels) {
            var x = 0.5f * diameter + if (row % 2 == 0) 0f else 0.5f * diameter
            while (x <= WIDTH - 0.5f * diameter) {
                values += listOf(x / WIDTH, y / HEIGHT, 0f, 0f)
                x += diameter
            }
            y += rowStep
            row++
        }
        return values.toFloatArray()
    }
}
