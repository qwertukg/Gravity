package dev.gravity

import org.lwjgl.opengl.GL11.glFinish
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/** GPU physics benchmark; no fullscreen, readback, or wait in measured frames. */
object CollisionBenchmark {
    @JvmStatic
    fun main(args: Array<String>) {
        val warmup = args.getOrNull(0)?.toInt() ?: 120
        val measured = args.getOrNull(1)?.toInt() ?: 240
        val filter = args.getOrNull(2)?.split(',')?.toSet()
        val repetitions = args.getOrNull(3)?.toInt() ?: 1
        // "cached" skips the slow reference pipeline when only the default
        // path is being compared between revisions.
        val variants = args.getOrNull(4) ?: "both"
        val sortInterval = args.getOrNull(5)?.toInt()
        require(warmup >= 0 && measured > 0 && repetitions > 0 && variants in setOf("both", "cached"))
        val output = Path.of("build", "performance-lossless")
        Files.createDirectories(output)
        CollisionGpuContext().use {
            for (scenario in listOf("calm-050k", "calm-100k", "calm-400k", "square-400k", "projectile-100k", "projectile-400k", "impact", "galaxy")) {
                if (filter != null && scenario !in filter) continue
                repeat(repetitions) { repetition ->
                    val order = if (repetition % 2 == 0) listOf(false, true) else listOf(true, false)
                    for (cached in order.filter { variants == "both" || it }) {
                        // Real scenarios: dense planets and a packed bulge,
                        // bodies stored in the scenario's shuffled order.
                        val scenarioState = when (scenario) {
                            "impact" -> Scenario.PLANETARY_IMPACT
                            "galaxy" -> Scenario.GALAXY
                            else -> null
                        }?.bodies(ScenarioSpace(3440f, 1440f, GravityFieldConfig().gravitationalConstant, CollisionConfig.REFERENCE_DIAMETER_PIXELS), 20260730)
                        val bodies = scenarioState?.let { it.size / 4 }
                            ?: (scenario.substringAfter('-').removeSuffix("k").toInt() * 1000)
                        var particles = ParticleConfig(420_000, bodies, if (scenario.startsWith("square")) 632 else null, 20260730)
                        if (sortInterval != null) particles = particles.copy(spatialSortInterval = sortInterval)
                        val config = GravityConfig(
                            particles = particles,
                            collisions = CollisionConfig(adaptive = false, neighborCache = cached),
                        )
                        // A hidden compute-only run has no swap-chain pacing.
                        // Reserve all samples rather than forcing waits or
                        // dropping frames when the CPU submits ahead of the GPU.
                        val profiler = GpuProfiler(measured + 1, 128, measured + 16)
                        profiler.use {
                            GravityPipeline(config, 3440, 1440, profiler, scenarioState).use { pipeline ->
                                pipeline.initializeAcceleration(bodies)
                                var active = bodies
                                repeat(warmup) { pipeline.step(active) }
                                if (scenario.startsWith("projectile")) {
                                    active += pipeline.spawnProjectile(active, 3440f * 0.25f, 720f, 2000f, 0f, 100f)
                                }
                                glFinish()
                                repeat(measured) { frame ->
                                    profiler.beginFrame(frame.toLong())
                                    pipeline.step(active)
                                    profiler.endFrame()
                                }
                                glFinish()
                                profiler.poll()
                                val summary = profiler.summary()
                                check(summary.frames == measured && profiler.droppedFrames == 0L) { "Incomplete benchmark: ${summary.frames}/$measured, dropped=${profiler.droppedFrames}" }
                                val stats = summary[GpuStage.PHYSICS_STEP]!!
                                val cluster = pipeline.measureCluster(active)
                                check(cluster.nonFiniteBodies == 0)
                                val result = String.format(Locale.ROOT,
                                    "%s cache=%s run=%d bodies=%d warmup=%d measured=%d mean=%.6f p50=%.6f p95=%.6f rebuilds=%s nonFinite=%d",
                                    scenario, cached, repetition + 1, active, warmup, measured,
                                    stats.averageMillis, stats.medianMillis, stats.p95Millis,
                                    if (cached) CollisionGpuAccess.rebuilds(pipeline).toString() else "n/a", cluster.nonFiniteBodies,
                                )
                                println(result)
                                Files.writeString(output.resolve("$scenario-${if (cached) "cached" else "reference"}-${repetition + 1}.txt"),
                                    result + "\n" + summary.markdown() + "\n" + cluster + "\n")
                            }
                        }
                    }
                }
            }
        }
    }
}
