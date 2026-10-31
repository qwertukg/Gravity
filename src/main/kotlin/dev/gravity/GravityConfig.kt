package dev.gravity

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Complete runtime configuration for the application.
 *
 * Compile-time GPU ABI invariants (workgroup width, SSBO element layouts,
 * FFT shared-memory capacity, and six vertices per trajectory step) remain
 * constants next to their shader implementations and are intentionally not
 * exposed as runtime tuning.
 */
data class GravityConfig(
    val particles: ParticleConfig = ParticleConfig(),
    val gravity: GravityFieldConfig = GravityFieldConfig(),
    val collisions: CollisionConfig = CollisionConfig(),
    val trajectory: TrajectoryConfig = TrajectoryConfig(),
    val projectiles: ProjectileConfig = ProjectileConfig(),
    val liquid: LiquidConfig = LiquidConfig(),
    val start: StartConfig = StartConfig(),
    val presentation: PresentationConfig = PresentationConfig(),
    val diagnostics: DiagnosticsConfig = DiagnosticsConfig(),
) {
    init {
        require(particles.initialBodies <= particles.capacity) {
            "initial body count must not exceed capacity"
        }
        require(diagnostics.maxFrames == null || diagnostics.benchmarkScenario == null) {
            "--frames and --benchmark cannot be used together"
        }
        require(projectiles.defaultDiameterPixels >= collisions.contactDiameterPixels) {
            "default projectile diameter must fit at least one body"
        }
        require(projectiles.maximumDiameterPixels >= projectiles.defaultDiameterPixels) {
            "maximum projectile diameter must be at least the default diameter"
        }
        require(
            projectiles.benchmarkDiameterPixels >= collisions.contactDiameterPixels,
        ) {
            "benchmark projectile diameter must fit at least one body"
        }
    }

    companion object {
        /** Cluster pressure ∝ point size^-1.2, measured at point sizes 1 and 3. */
        private const val PRESSURE_SIZE_EXPONENT = 1.2f

        private val supportedKeys = setOf(
            "bodies",
            "capacity",
            "grid",
            "gravity",
            "softening",
            "near-radius-sigma",
            "near-radius-min",
            "near-radius-max",
            "near-exact-bodies",
            "near-home-bodies",
            "near-min-distance-fraction",
            "cic-window-floor",
            "field-gravity",
            "dt",
            "collisions",
            "diameter",
            "collision-neighbor-radius",
            "restitution",
            "friction",
            "coincident-distance-epsilon",
            "adaptive-collisions",
            "collision-cache",
            "collision-skin",
            "sleep",
            "sleep-speed",
            "gpu-budget-ms",
            "sort-interval",
            "adaptive-displacement-fraction",
            "adaptive-penetration-fraction",
            "adaptive-dense-contact-count",
            "adaptive-dense-body-fraction",
            "adaptive-dense-min-bodies",
            "pressure-max",
            "target-gpu-ms",
            "time-scale-min",
            "time-scale-scroll",
            "fps-update-ms",
            "console-report-ms",
            "gpu-smoothing",
            "point-size",
            "overlay-characters",
            "overlay-margin-px",
            "overlay-line-height-px",
            "trajectory-steps",
            "trajectory-max-dt",
            "trajectory-segment-px",
            "trajectory-path-diagonals",
            "trajectory-updates-per-second",
            "trajectory-min-dash-px",
            "projectile-diameter",
            "projectile-drag-scale",
            "projectile-min-drag-px",
            "projectile-size-step",
            "projectile-max-diameter",
            "projectile-plus-bodies",
            "block-density",
            "block-friction",
            "block-restitution",
            "block-strength",
            "scenario",
            "walls",
            "mutual-gravity",
            "field",
            "liquid",
            "liquid-kernel",
            "liquid-iterations",
            "liquid-viscosity",
            "preview-period-px",
            "frames",
            "square-test",
            "disc-test",
            "seed",
            "profile",
            "benchmark",
            "warmup-frames",
            "benchmark-frames",
            "benchmark-projectile-x",
            "benchmark-projectile-y",
            "benchmark-projectile-diameter",
            "benchmark-projectile-vx",
            "benchmark-projectile-vy",
            "fft-validate",
            "profiler-ring",
            "profiler-segments",
            "profile-history",
            "timer-ring",
        )
        val supportedOptionNames: List<String> = supportedKeys.sorted()

        fun parse(
            args: Array<String>,
            env: Map<String, String> = System.getenv(),
        ): GravityConfig {
            val defaults = GravityConfig()
            val values = mutableMapOf<String, String>()
            args.forEach { argument ->
                require(argument.startsWith("--") && '=' in argument) {
                    "Unknown argument: $argument"
                }
                val (key, value) = argument.drop(2).split('=', limit = 2)
                require(key in supportedKeys) { "Unknown option: --$key" }
                values[key] = value
            }

            fun stringValue(
                key: String,
                environmentKey: String? = null,
            ): String? = values[key] ?: environmentKey?.let(env::get)

            fun intValue(
                key: String,
                default: Int,
                environmentKey: String? = null,
            ): Int = stringValue(key, environmentKey)?.let {
                it.toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid integer for --$key: $it")
            } ?: default

            fun longValue(key: String, default: Long?): Long? =
                values[key]?.let {
                    it.toLongOrNull()
                        ?: throw IllegalArgumentException("Invalid long for --$key: $it")
                } ?: default

            fun floatValue(
                key: String,
                default: Float,
                environmentKey: String? = null,
            ): Float = stringValue(key, environmentKey)?.let {
                it.toFloatOrNull()
                    ?: throw IllegalArgumentException("Invalid float for --$key: $it")
            } ?: default

            fun doubleValue(key: String, default: Double): Double =
                values[key]?.let {
                    it.toDoubleOrNull()
                        ?: throw IllegalArgumentException("Invalid number for --$key: $it")
                } ?: default

            fun booleanValue(key: String, default: Boolean): Boolean =
                values[key]?.let {
                    when (it.lowercase()) {
                        "true", "1", "yes", "on" -> true
                        "false", "0", "no", "off" -> false
                        else -> throw IllegalArgumentException(
                            "Invalid boolean for --$key: $it",
                        )
                    }
                } ?: default

            // --point-size is the one body-size knob. Every size-dependent
            // default follows it; options given explicitly still win.
            val pointSize = floatValue(
                "point-size",
                defaults.presentation.bodyPointSizePixels,
            )
            require(pointSize > 0f && pointSize.isFinite()) {
                "Invalid --point-size: $pointSize"
            }
            val sizeScale = pointSize / defaults.presentation.bodyPointSizePixels
            // Counts shrink with body area so clusters keep their screen size.
            fun scaledCount(count: Int) =
                max(1, (count / (sizeScale * sizeScale)).roundToInt())

            val grid = stringValue("grid", "GRAVITY_GRID")
            val (gridWidth, gridHeight) = parseGrid(
                grid,
                defaults.gravity.gridWidth,
                defaults.gravity.gridHeight,
            )
            val capacity = intValue(
                "capacity",
                defaults.particles.capacity,
                "GRAVITY_CAPACITY",
            )
            val benchmarkScenario = values["benchmark"]?.let(BenchmarkScenario::parse)
            val particleConfig = ParticleConfig(
                capacity = capacity,
                initialBodies = min(
                    intValue(
                        "bodies",
                        scaledCount(defaults.particles.initialBodies),
                        "GRAVITY_BODIES",
                    ),
                    capacity,
                ),
                squareTestPixels = values["square-test"]?.let {
                    it.toIntOrNull()
                        ?: throw IllegalArgumentException(
                            "Invalid integer for --square-test: $it",
                        )
                },
                discTestRadiusPixels = values["disc-test"]?.let {
                    it.toIntOrNull()
                        ?: throw IllegalArgumentException(
                            "Invalid integer for --disc-test: $it",
                        )
                },
                seed = longValue("seed", defaults.particles.seed),
                spatialSortInterval = intValue(
                    "sort-interval",
                    defaults.particles.spatialSortInterval,
                ),
            )
            return GravityConfig(
                particles = particleConfig,
                gravity = GravityFieldConfig(
                    gridWidth = gridWidth,
                    gridHeight = gridHeight,
                    gravitationalConstant = floatValue(
                        "gravity",
                        defaults.gravity.gravitationalConstant,
                        "GRAVITY_G",
                    ),
                    softeningCells = floatValue(
                        "softening",
                        defaults.gravity.softeningCells,
                        "GRAVITY_SOFTENING",
                    ),
                    localSearchSigmaMultiplier = floatValue(
                        "near-radius-sigma",
                        defaults.gravity.localSearchSigmaMultiplier,
                    ),
                    minimumNeighborRadiusCells = intValue(
                        "near-radius-min",
                        defaults.gravity.minimumNeighborRadiusCells,
                    ),
                    maximumNeighborRadiusCells = intValue(
                        "near-radius-max",
                        defaults.gravity.maximumNeighborRadiusCells,
                    ),
                    exactNeighborBodyLimit = intValue(
                        "near-exact-bodies",
                        defaults.gravity.exactNeighborBodyLimit,
                    ),
                    maximumHomeBodyInteractions = intValue(
                        "near-home-bodies",
                        defaults.gravity.maximumHomeBodyInteractions,
                    ),
                    minimumDistanceCellFraction = floatValue(
                        "near-min-distance-fraction",
                        defaults.gravity.minimumDistanceCellFraction,
                    ),
                    minimumAssignmentWindowPower = floatValue(
                        "cic-window-floor",
                        defaults.gravity.minimumAssignmentWindowPower,
                    ),
                    uniformFieldAcceleration = floatValue(
                        "field-gravity",
                        defaults.gravity.uniformFieldAcceleration,
                    ),
                ),
                collisions = CollisionConfig(
                    timeStep = floatValue(
                        "dt",
                        defaults.collisions.timeStep,
                        "GRAVITY_DT",
                    ),
                    maximumSubsteps = intValue(
                        "collisions",
                        defaults.collisions.maximumSubsteps,
                        "GRAVITY_COLLISIONS",
                    ),
                    contactDiameterPixels = floatValue(
                        "diameter",
                        defaults.collisions.contactDiameterPixels * sizeScale,
                        "GRAVITY_DIAMETER",
                    ),
                    neighborCellRadius = intValue(
                        "collision-neighbor-radius",
                        defaults.collisions.neighborCellRadius,
                    ),
                    restitutionCoefficient = floatValue(
                        "restitution",
                        defaults.collisions.restitutionCoefficient,
                    ),
                    friction = floatValue(
                        "friction",
                        defaults.collisions.friction,
                    ),
                    coincidentDistanceSquaredEpsilon = floatValue(
                        "coincident-distance-epsilon",
                        defaults.collisions.coincidentDistanceSquaredEpsilon,
                    ),
                    adaptive = booleanValue(
                        "adaptive-collisions",
                        defaults.collisions.adaptive,
                    ),
                    neighborCache = booleanValue(
                        "collision-cache",
                        defaults.collisions.neighborCache,
                    ),
                    neighborSkinPixels = floatValue(
                        "collision-skin",
                        defaults.collisions.neighborSkinPixels * sizeScale,
                    ),
                    sleep = booleanValue("sleep", defaults.collisions.sleep),
                    sleepSpeedPixels = floatValue("sleep-speed", defaults.collisions.sleepSpeedPixels * sizeScale),
                    gpuBudgetMillis = doubleValue(
                        "gpu-budget-ms",
                        defaults.collisions.gpuBudgetMillis,
                    ),
                    allowedDisplacementFraction = floatValue(
                        "adaptive-displacement-fraction",
                        defaults.collisions.allowedDisplacementFraction,
                    ),
                    allowedPenetrationFraction = floatValue(
                        "adaptive-penetration-fraction",
                        defaults.collisions.allowedPenetrationFraction,
                    ),
                    denseContactThreshold = intValue(
                        "adaptive-dense-contact-count",
                        defaults.collisions.denseContactThreshold,
                    ),
                    denseBodyFraction = floatValue(
                        "adaptive-dense-body-fraction",
                        defaults.collisions.denseBodyFraction,
                    ),
                    minimumDenseBodies = intValue(
                        "adaptive-dense-min-bodies",
                        defaults.collisions.minimumDenseBodies,
                    ),
                ),
                trajectory = TrajectoryConfig(
                    steps = intValue("trajectory-steps", defaults.trajectory.steps),
                    maximumTimeStep = floatValue(
                        "trajectory-max-dt",
                        defaults.trajectory.maximumTimeStep,
                    ),
                    targetSegmentPixels = floatValue(
                        "trajectory-segment-px",
                        defaults.trajectory.targetSegmentPixels,
                    ),
                    maximumPathViewportDiagonals = floatValue(
                        "trajectory-path-diagonals",
                        defaults.trajectory.maximumPathViewportDiagonals,
                    ),
                    updatesPerSecond = intValue(
                        "trajectory-updates-per-second",
                        defaults.trajectory.updatesPerSecond,
                    ),
                    minimumDashPixels = floatValue(
                        "trajectory-min-dash-px",
                        defaults.trajectory.minimumDashPixels,
                    ),
                ),
                projectiles = ProjectileConfig(
                    defaultDiameterPixels = floatValue(
                        "projectile-diameter",
                        defaults.projectiles.defaultDiameterPixels,
                    ),
                    velocityPerDragPixel = floatValue(
                        "projectile-drag-scale",
                        defaults.projectiles.velocityPerDragPixel,
                    ),
                    minimumDragPixels = floatValue(
                        "projectile-min-drag-px",
                        defaults.projectiles.minimumDragPixels,
                    ),
                    digitDiameterStepPixels = floatValue(
                        "projectile-size-step",
                        defaults.projectiles.digitDiameterStepPixels,
                    ),
                    maximumDiameterPixels = floatValue(
                        "projectile-max-diameter",
                        defaults.projectiles.maximumDiameterPixels,
                    ),
                    plusKeyBodyCount = intValue(
                        "projectile-plus-bodies",
                        scaledCount(defaults.projectiles.plusKeyBodyCount),
                    ),
                    blockDensity = floatValue(
                        "block-density",
                        defaults.projectiles.blockDensity,
                    ),
                    blockFriction = floatValue(
                        "block-friction",
                        defaults.projectiles.blockFriction,
                    ),
                    blockRestitution = floatValue(
                        "block-restitution",
                        defaults.projectiles.blockRestitution,
                    ),
                    blockStrength = floatValue(
                        "block-strength",
                        defaults.projectiles.blockStrength,
                    ),
                    previewPeriodPixels = floatValue(
                        "preview-period-px",
                        defaults.projectiles.previewPeriodPixels,
                    ),
                    benchmarkOriginX = floatValue(
                        "benchmark-projectile-x",
                        defaults.projectiles.benchmarkOriginX,
                    ),
                    benchmarkOriginY = floatValue(
                        "benchmark-projectile-y",
                        defaults.projectiles.benchmarkOriginY,
                    ),
                    benchmarkDiameterPixels = floatValue(
                        "benchmark-projectile-diameter",
                        defaults.projectiles.benchmarkDiameterPixels,
                    ),
                    benchmarkVelocityX = floatValue(
                        "benchmark-projectile-vx",
                        defaults.projectiles.benchmarkVelocityX,
                    ),
                    benchmarkVelocityY = floatValue(
                        "benchmark-projectile-vy",
                        defaults.projectiles.benchmarkVelocityY,
                    ),
                ),
                liquid = LiquidConfig(
                    kernelRadiusDiameters = floatValue(
                        "liquid-kernel",
                        defaults.liquid.kernelRadiusDiameters,
                    ),
                    iterations = intValue(
                        "liquid-iterations",
                        defaults.liquid.iterations,
                    ),
                    viscosity = floatValue(
                        "liquid-viscosity",
                        defaults.liquid.viscosity,
                    ),
                ),
                start = run {
                    // Benchmarks and diagnostic starts keep the uniform start
                    // they were measured with; explicit options still win.
                    val diagnostic = listOf("benchmark", "frames", "square-test", "disc-test", "fft-validate")
                        .any { it in values } || stringValue("bodies", "GRAVITY_BODIES") != null
                    val startDefaults = if (diagnostic) StartConfig.UNIFORM else defaults.start
                    StartConfig(
                        scenario = values["scenario"]?.let(StartConfig::parseScenario)
                            ?: startDefaults.scenario.takeIf { "scenario" !in values },
                        walls = booleanValue("walls", startDefaults.walls),
                        mutualGravity = booleanValue("mutual-gravity", startDefaults.mutualGravity),
                        field = values["field"]?.let(StartConfig::parseField) ?: startDefaults.field,
                        liquid = booleanValue("liquid", startDefaults.liquid),
                    )
                },
                presentation = PresentationConfig(
                    // Fewer, larger bodies: a column holds 1/s as many layers.
                    // Measured cluster pressure falls as about s^-1.2 (p99 is
                    // 3.65x lower at s = 3), so the colours stay the same.
                    maximumVisualPressure = floatValue(
                        "pressure-max",
                        defaults.presentation.maximumVisualPressure /
                            sizeScale.pow(PRESSURE_SIZE_EXPONENT),
                        "GRAVITY_PRESSURE_MAX",
                    ),
                    targetGpuMillis = doubleValue(
                        "target-gpu-ms",
                        defaults.presentation.targetGpuMillis,
                    ),
                    minimumTimeScale = doubleValue(
                        "time-scale-min",
                        defaults.presentation.minimumTimeScale,
                    ),
                    timeScalePerScroll = doubleValue(
                        "time-scale-scroll",
                        defaults.presentation.timeScalePerScroll,
                    ),
                    fpsUpdateMillis = intValue(
                        "fps-update-ms",
                        defaults.presentation.fpsUpdateMillis,
                    ),
                    consoleReportMillis = intValue(
                        "console-report-ms",
                        defaults.presentation.consoleReportMillis,
                    ),
                    gpuTimeSmoothing = doubleValue(
                        "gpu-smoothing",
                        defaults.presentation.gpuTimeSmoothing,
                    ),
                    bodyPointSizePixels = pointSize,
                    overlayMaximumCharacters = intValue(
                        "overlay-characters",
                        defaults.presentation.overlayMaximumCharacters,
                    ),
                    overlayMarginPixels = floatValue(
                        "overlay-margin-px",
                        defaults.presentation.overlayMarginPixels,
                    ),
                    overlayLineHeightPixels = floatValue(
                        "overlay-line-height-px",
                        defaults.presentation.overlayLineHeightPixels,
                    ),
                ),
                diagnostics = DiagnosticsConfig(
                    maxFrames = values["frames"]?.let {
                        it.toIntOrNull()
                            ?: throw IllegalArgumentException(
                                "Invalid integer for --frames: $it",
                            )
                    },
                    profile = booleanValue("profile", defaults.diagnostics.profile) ||
                        benchmarkScenario != null,
                    benchmarkScenario = benchmarkScenario,
                    benchmarkWarmupFrames = intValue(
                        "warmup-frames",
                        defaults.diagnostics.benchmarkWarmupFrames,
                    ),
                    benchmarkFrames = intValue(
                        "benchmark-frames",
                        defaults.diagnostics.benchmarkFrames,
                    ),
                    validateFft = booleanValue(
                        "fft-validate",
                        defaults.diagnostics.validateFft,
                    ),
                    profilerRingSlots = intValue(
                        "profiler-ring",
                        defaults.diagnostics.profilerRingSlots,
                    ),
                    profilerMaximumSegmentsPerFrame = intValue(
                        "profiler-segments",
                        defaults.diagnostics.profilerMaximumSegmentsPerFrame,
                    ),
                    profilerCompletedFrameCapacity = intValue(
                        "profile-history",
                        defaults.diagnostics.profilerCompletedFrameCapacity,
                    ),
                    timerRingSlots = intValue(
                        "timer-ring",
                        defaults.diagnostics.timerRingSlots,
                    ),
                ),
            )
        }

        private fun parseGrid(
            value: String?,
            defaultWidth: Int,
            defaultHeight: Int,
        ): Pair<Int, Int> = when {
            value == null -> defaultWidth to defaultHeight
            'x' in value.lowercase() -> {
                val parts = value.lowercase().split('x', limit = 2)
                val width = parts[0].toIntOrNull()
                    ?: throw IllegalArgumentException(
                        "Invalid grid width: ${parts[0]}",
                    )
                val height = parts[1].toIntOrNull()
                    ?: throw IllegalArgumentException(
                        "Invalid grid height: ${parts[1]}",
                    )
                width to height
            }
            else -> {
                val side = value.toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid grid: $value")
                side to side
            }
        }
    }
}

data class ParticleConfig(
    val capacity: Int = 400_000,
    val initialBodies: Int = 50_000,
    val squareTestPixels: Int? = null,
    /** Radius in px of a centred disc holding the initial bodies at random. */
    val discTestRadiusPixels: Int? = null,
    val seed: Long? = null,
    /**
     * Physics steps between spatial reorderings of the body arrays; 0 disables
     * it. Reordering only permutes body IDs, so every contact and force stays.
     */
    val spatialSortInterval: Int = 2,
) {
    init {
        require(capacity > 0) { "capacity must be positive" }
        require(spatialSortInterval >= 0) { "sort interval must be non-negative" }
        require(initialBodies in 1..capacity) {
            "initial body count must be in 1..capacity"
        }
        require(squareTestPixels == null || squareTestPixels > 0) {
            "square test size must be positive"
        }
        require(discTestRadiusPixels == null || discTestRadiusPixels > 0) {
            "disc test radius must be positive"
        }
        require(squareTestPixels == null || discTestRadiusPixels == null) {
            "--square-test and --disc-test cannot be used together"
        }
    }
}

data class GravityFieldConfig(
    val gridWidth: Int = 512,
    val gridHeight: Int = 256,
    val gravitationalConstant: Float = 0.035f,
    val softeningCells: Float = 0.85f,
    val localSearchSigmaMultiplier: Float = 3f,
    val minimumNeighborRadiusCells: Int = 1,
    val maximumNeighborRadiusCells: Int = 6,
    val exactNeighborBodyLimit: Int = 2,
    val maximumHomeBodyInteractions: Int = 8,
    val minimumDistanceCellFraction: Float = 0.05f,
    val minimumAssignmentWindowPower: Float = 0.08f,
    /** Uniform field strength in px/s² at a 1440 px tall screen (arrow keys). */
    val uniformFieldAcceleration: Float = 300f,
) {
    init {
        require(gridWidth in 128..2048 && gridWidth.countOneBits() == 1) {
            "grid width must be a power of two in 128..2048"
        }
        require(gridHeight in 128..2048 && gridHeight.countOneBits() == 1) {
            "grid height must be a power of two in 128..2048"
        }
        require(gravitationalConstant >= 0f && gravitationalConstant.isFinite())
        require(softeningCells > 0f && softeningCells.isFinite())
        require(localSearchSigmaMultiplier > 0f && localSearchSigmaMultiplier.isFinite())
        require(minimumNeighborRadiusCells >= 1)
        require(maximumNeighborRadiusCells >= minimumNeighborRadiusCells)
        require(exactNeighborBodyLimit >= 1)
        require(maximumHomeBodyInteractions >= 1)
        require(minimumDistanceCellFraction > 0f && minimumDistanceCellFraction.isFinite())
        require(minimumAssignmentWindowPower > 0f && minimumAssignmentWindowPower <= 1f)
        require(uniformFieldAcceleration >= 0f && uniformFieldAcceleration.isFinite())
    }
}

data class CollisionConfig(
    val timeStep: Float = 1f / 120f,
    val maximumSubsteps: Int = 16,
    /** Contact diameter of a body; a disc of this size has the area of a 1 px point. */
    val contactDiameterPixels: Float = REFERENCE_DIAMETER_PIXELS,
    val neighborCellRadius: Int = 1,
    val restitutionCoefficient: Float = 0f,
    /** Coulomb friction coefficient given to every body; 0 is smooth. */
    val friction: Float = 0f,
    val coincidentDistanceSquaredEpsilon: Float = 1e-12f,
    val adaptive: Boolean = false,
    val neighborCache: Boolean = true,
    val neighborSkinPixels: Float = 0.5f,
    /**
     * Sleeping tiles (SleepTiles): bodies held in place that move slower
     * than [sleepSpeedPixels] (px/s) and have nothing moving within a tile
     * of them are left alone by every pass.
     */
    val sleep: Boolean = true,
    val sleepSpeedPixels: Float = 6f,
    /**
     * GPU time of a frame (ms) above which the application runs fewer
     * collision substeps, down to half (SubstepGovernor); 0 never does.
     */
    val gpuBudgetMillis: Double = 8.5,
    val allowedDisplacementFraction: Float = 0.25f,
    val allowedPenetrationFraction: Float = 0.125f,
    val denseContactThreshold: Int = 4,
    val denseBodyFraction: Float = 0.01f,
    val minimumDenseBodies: Int = 256,
) {
    init {
        require(timeStep > 0f && timeStep.isFinite())
        require(neighborSkinPixels > 0f && neighborSkinPixels.isFinite())
        require(sleepSpeedPixels >= 0f && sleepSpeedPixels.isFinite())
        require(gpuBudgetMillis >= 0.0 && gpuBudgetMillis.isFinite())
        require(maximumSubsteps in 1..16) {
            "collision substeps must be in 1..16"
        }
        require(contactDiameterPixels in 0.5f..MAXIMUM_DIAMETER_PIXELS) {
            "contact diameter must be in 0.5..$MAXIMUM_DIAMETER_PIXELS pixels"
        }
        require(neighborCellRadius in 1..4)
        require(restitutionCoefficient in 0f..1f)
        require(friction >= 0f && friction.isFinite())
        require(
            coincidentDistanceSquaredEpsilon > 0f &&
                coincidentDistanceSquaredEpsilon.isFinite(),
        )
        require(allowedDisplacementFraction in 0f..1f)
        require(allowedPenetrationFraction in 0f..1f)
        require(denseContactThreshold >= 1)
        require(denseBodyFraction in 0f..1f)
        require(minimumDenseBodies >= 1)
    }

    companion object {
        /** 2/√π: the diameter whose disc covers one pixel, at --point-size=1. */
        const val REFERENCE_DIAMETER_PIXELS = 1.12838f
        const val MAXIMUM_DIAMETER_PIXELS = 16f
    }
}

data class TrajectoryConfig(
    val steps: Int = 1_024,
    val maximumTimeStep: Float = 1f,
    val targetSegmentPixels: Float = 6f,
    val maximumPathViewportDiagonals: Float = 1f,
    val updatesPerSecond: Int = 30,
    val minimumDashPixels: Float = 3f,
) {
    init {
        require(steps in 1..65_536)
        require(maximumTimeStep > 0f && maximumTimeStep.isFinite())
        require(targetSegmentPixels > 0f && targetSegmentPixels.isFinite())
        require(
            maximumPathViewportDiagonals > 0f &&
                maximumPathViewportDiagonals.isFinite(),
        )
        require(updatesPerSecond in 1..1_000)
        require(minimumDashPixels > 0f && minimumDashPixels.isFinite())
    }
}

data class ProjectileConfig(
    val defaultDiameterPixels: Float = 50f,
    val velocityPerDragPixel: Float = 4f,
    val minimumDragPixels: Float = 2f,
    val digitDiameterStepPixels: Float = 10f,
    val maximumDiameterPixels: Float = 100f,
    val plusKeyBodyCount: Int = 50_000,
    /** Mass of each body of an RMB block; other bodies have unit mass. */
    val blockDensity: Float = 0.5f,
    /**
     * Coulomb friction of RMB blocks, against walls and each other; with a
     * loose body the pair takes the geometric mean with `--friction`.
     */
    val blockFriction: Float = 0.5f,
    /**
     * Restitution of RMB blocks against walls and each other, and the least a
     * loose body gets off a block; slow contacts never bounce.
     */
    val blockRestitution: Float = 0.2f,
    /**
     * Critical impact speed of block material, px/s: a contact approaching
     * faster chips the bodies it strikes off, and what it brings beyond that
     * runs on as cracks. `Infinity` makes blocks unbreakable.
     */
    val blockStrength: Float = 400f,
    val previewPeriodPixels: Float = 6f,
    val benchmarkOriginX: Float = 0.25f,
    val benchmarkOriginY: Float = 0.5f,
    val benchmarkDiameterPixels: Float = 100f,
    val benchmarkVelocityX: Float = 2_000f,
    val benchmarkVelocityY: Float = 0f,
) {
    init {
        require(defaultDiameterPixels > 0f && defaultDiameterPixels.isFinite())
        require(velocityPerDragPixel > 0f && velocityPerDragPixel.isFinite())
        require(minimumDragPixels >= 0f && minimumDragPixels.isFinite())
        require(digitDiameterStepPixels > 0f && digitDiameterStepPixels.isFinite())
        require(maximumDiameterPixels > 0f && maximumDiameterPixels.isFinite())
        require(maximumDiameterPixels >= digitDiameterStepPixels * 9f) {
            "maximum projectile diameter must cover digit 9"
        }
        require(plusKeyBodyCount > 0)
        require(blockDensity in MINIMUM_DENSITY..MAXIMUM_DENSITY) {
            "block density must be in $MINIMUM_DENSITY..$MAXIMUM_DENSITY"
        }
        require(blockFriction >= 0f && blockFriction.isFinite()) { "block friction must be non-negative" }
        require(blockRestitution in 0f..1f) { "block restitution must be in 0..1" }
        require(blockStrength > 0f && !blockStrength.isNaN()) { "block strength must be positive" }
        require(previewPeriodPixels > 0f && previewPeriodPixels.isFinite())
        require(benchmarkOriginX in 0f..1f)
        require(benchmarkOriginY in 0f..1f)
        require(benchmarkDiameterPixels > 0f && benchmarkDiameterPixels.isFinite())
        require(benchmarkVelocityX.isFinite())
        require(benchmarkVelocityY.isFinite())
    }

    fun diameterForDigit(digit: Int): Float {
        require(digit in 0..9)
        return if (digit == 0) {
            maximumDiameterPixels
        } else {
            digit * digitDiameterStepPixels
        }
    }

    companion object {
        /** Body masses are summed per gravity cell in 1/256 units. */
        const val MINIMUM_DENSITY = 0.05f
        const val MAXIMUM_DENSITY = 10f
    }
}

/**
 * Liquid mode (key L): bodies that are not part of a block become a
 * position-based fluid instead of colliding as hard discs.
 */
data class LiquidConfig(
    /** Kernel radius in contact diameters. */
    val kernelRadiusDiameters: Float = 2f,
    /**
     * Density-constraint iterations per physics step. Deep or strongly pressed
     * liquid (a 100k-body pool, a self-gravitating planet) needs about 12 for
     * Jacobi to settle; with fewer the residual keeps stirring it.
     */
    val iterations: Int = 12,
    /** XSPH viscosity per physics step. */
    val viscosity: Float = 0.1f,
) {
    init {
        require(kernelRadiusDiameters in 1.5f..4f) { "liquid kernel must be 1.5..4 diameters" }
        require(iterations in 1..16) { "liquid iterations must be in 1..16" }
        require(viscosity in 0f..1f) { "liquid viscosity must be in 0..1" }
    }
}

/**
 * State the interactive session starts in: a liquid scenario falling under
 * the downward field into a box. Keys B, G, L and the arrows change it at
 * runtime; the scenario buttons and R restart. Benchmarks and diagnostic
 * starts use [UNIFORM] unless these options are given.
 */
data class StartConfig(
    /** Scenario to start in; null is the uniform random start of --bodies. */
    val scenario: MenuScenario? = Scenario.COSMIC_WEB,
    /** Walls at the screen edges instead of the torus (key B). */
    val walls: Boolean = true,
    /** Gravity between bodies (key G). */
    val mutualGravity: Boolean = false,
    /** Uniform field directions (arrow keys). */
    val field: Set<FieldDirection> = setOf(FieldDirection.DOWN),
    /** Liquid mode (key L). */
    val liquid: Boolean = true,
) {
    companion object {
        /** The start benchmarks and diagnostics were measured with. */
        val UNIFORM = StartConfig(
            scenario = null,
            walls = false,
            mutualGravity = true,
            field = emptySet(),
            liquid = false,
        )

        /** A scenario name such as cosmic-web or bullets, or none for the uniform start. */
        fun parseScenario(value: String): MenuScenario? {
            val name = value.lowercase()
            if (name == "none") return null
            val scenarios: List<MenuScenario> = Scenario.entries + BlockScenario.entries + TestScenario.entries + NavalScene
            return scenarios.firstOrNull { it.optionName == name }
                ?: throw IllegalArgumentException(
                    "Unknown --scenario: $value; use none or " +
                        scenarios.joinToString(", ") { it.optionName },
                )
        }

        /** Comma-separated directions such as down or down,left; off for none. */
        fun parseField(value: String): Set<FieldDirection> {
            if (value.lowercase() == "off") return emptySet()
            return value.split(',').map { part ->
                FieldDirection.entries.firstOrNull { it.label == part.trim().lowercase() }
                    ?: throw IllegalArgumentException("Unknown --field direction: $part; use up, down, left, right or off")
            }.toCollection(LinkedHashSet())
        }
    }
}

data class PresentationConfig(
    val maximumVisualPressure: Float = 64f,
    val targetGpuMillis: Double = 13.5,
    val minimumTimeScale: Double = 0.01,
    val timeScalePerScroll: Double = 0.05,
    val fpsUpdateMillis: Int = 250,
    val consoleReportMillis: Int = 2_000,
    val gpuTimeSmoothing: Double = 0.08,
    val bodyPointSizePixels: Float = 1f,
    val overlayMaximumCharacters: Int = 256,
    val overlayMarginPixels: Float = 8f,
    val overlayLineHeightPixels: Float = 12f,
) {
    init {
        require(maximumVisualPressure > 0f && maximumVisualPressure.isFinite())
        require(targetGpuMillis > 0.0 && targetGpuMillis.isFinite())
        require(minimumTimeScale in 0.0..1.0)
        require(timeScalePerScroll > 0.0 && timeScalePerScroll <= 1.0)
        require(fpsUpdateMillis > 0)
        require(consoleReportMillis > 0)
        require(gpuTimeSmoothing > 0.0 && gpuTimeSmoothing <= 1.0)
        require(bodyPointSizePixels > 0f && bodyPointSizePixels.isFinite())
        require(overlayMaximumCharacters > 0)
        require(overlayMarginPixels >= 0f && overlayMarginPixels.isFinite())
        require(overlayLineHeightPixels > 0f && overlayLineHeightPixels.isFinite())
    }
}

data class DiagnosticsConfig(
    val maxFrames: Int? = null,
    val profile: Boolean = false,
    val benchmarkScenario: BenchmarkScenario? = null,
    val benchmarkWarmupFrames: Int = 120,
    val benchmarkFrames: Int = 240,
    val validateFft: Boolean = false,
    val profilerRingSlots: Int = 16,
    val profilerMaximumSegmentsPerFrame: Int = 256,
    val profilerCompletedFrameCapacity: Int = 8_192,
    val timerRingSlots: Int = 16,
) {
    init {
        require(maxFrames == null || maxFrames > 0)
        require(benchmarkWarmupFrames >= 0)
        require(benchmarkFrames > 0)
        require(profilerRingSlots > 0)
        require(profilerMaximumSegmentsPerFrame > 0)
        require(profilerCompletedFrameCapacity > 0)
        require(timerRingSlots > 0)
    }
}

enum class BenchmarkScenario {
    CALM,
    PROJECTILE;

    companion object {
        fun parse(value: String): BenchmarkScenario = when (value.lowercase()) {
            "calm" -> CALM
            "projectile" -> PROJECTILE
            else -> throw IllegalArgumentException(
                "Invalid benchmark scenario: $value (expected calm or projectile)",
            )
        }
    }
}
