package dev.gravity

import kotlin.math.min

data class SimulationConfig(
    val capacity: Int = 400_000,
    val initialBodies: Int = 50_000,
    val gridWidth: Int = 512,
    val gridHeight: Int = 256,
    val gravitationalConstant: Float = 0.035f,
    val softeningCells: Float = 0.85f,
    val timeStep: Float = 1f / 120f,
    val collisionIterations: Int = 16,
    val contactDiameterPixels: Float = 1.12838f,
    val maximumVisualPressure: Float = 64f,
    val targetGpuMillis: Double = 13.5,
    val maxFrames: Int? = null,
    val squareTestPixels: Int? = null,
    val seed: Long? = null,
    val profile: Boolean = false,
    val benchmarkScenario: BenchmarkScenario? = null,
    val benchmarkWarmupFrames: Int = 120,
    val benchmarkFrames: Int = 240,
    val validateFft: Boolean = false,
    val adaptiveCollisions: Boolean = false,
) {
    init {
        require(capacity > 0) { "capacity must be positive" }
        require(initialBodies in 1..capacity) { "bodies must be in 1..capacity" }
        require(gridWidth in 128..2048 && gridWidth.countOneBits() == 1) {
            "grid width must be a power of two in 128..2048"
        }
        require(gridHeight in 128..2048 && gridHeight.countOneBits() == 1) {
            "grid height must be a power of two in 128..2048"
        }
        require(gravitationalConstant >= 0f)
        require(softeningCells > 0f)
        require(timeStep > 0f)
        require(collisionIterations in 1..16) { "collision iterations must be in 1..16" }
        require(contactDiameterPixels in 0.5f..4f) {
            "contact diameter must be in 0.5..4 pixels"
        }
        require(maximumVisualPressure > 0f && maximumVisualPressure.isFinite()) {
            "maximum visual pressure must be finite and positive"
        }
        require(maxFrames == null || maxFrames > 0)
        require(squareTestPixels == null || squareTestPixels > 0) {
            "square test size must be positive"
        }
        require(benchmarkWarmupFrames >= 0)
        require(benchmarkFrames > 0)
        require(maxFrames == null || benchmarkScenario == null) {
            "--frames and --benchmark cannot be used together"
        }
    }

    companion object {
        fun parse(args: Array<String>, env: Map<String, String> = System.getenv()): SimulationConfig {
            val defaults = SimulationConfig()
            val values = mutableMapOf<String, String>()
            args.forEach { argument ->
                when {
                    argument.startsWith("--") && '=' in argument -> {
                        val (key, value) = argument.drop(2).split('=', limit = 2)
                        values[key] = value
                    }
                    else -> error("Unknown argument: $argument")
                }
            }

            fun intValue(key: String, environmentKey: String, default: Int): Int =
                values[key]?.toIntOrNull()
                    ?: env[environmentKey]?.toIntOrNull()
                    ?: default

            fun floatValue(key: String, environmentKey: String, default: Float): Float =
                values[key]?.toFloatOrNull()
                    ?: env[environmentKey]?.toFloatOrNull()
                    ?: default

            fun booleanValue(key: String, default: Boolean): Boolean =
                values[key]?.let {
                    when (it.lowercase()) {
                        "true", "1", "yes", "on" -> true
                        "false", "0", "no", "off" -> false
                        else -> throw IllegalArgumentException("Invalid boolean for --$key: $it")
                    }
                } ?: default

            val grid = values["grid"] ?: env["GRAVITY_GRID"]
            val (gridWidth, gridHeight) = when {
                grid == null -> defaults.gridWidth to defaults.gridHeight
                'x' in grid.lowercase() -> {
                    val parts = grid.lowercase().split('x', limit = 2)
                    val width = parts[0].toIntOrNull()
                        ?: throw IllegalArgumentException("Invalid grid width: ${parts[0]}")
                    val height = parts[1].toIntOrNull()
                        ?: throw IllegalArgumentException("Invalid grid height: ${parts[1]}")
                    width to height
                }
                else -> {
                    val side = grid.toIntOrNull()
                        ?: throw IllegalArgumentException("Invalid grid: $grid")
                    side to side
                }
            }
            val capacity = intValue("capacity", "GRAVITY_CAPACITY", defaults.capacity)
            val squareTestPixels = values["square-test"]?.toIntOrNull()
            val benchmarkScenario = values["benchmark"]?.let { BenchmarkScenario.parse(it) }
            return SimulationConfig(
                capacity = capacity,
                initialBodies = min(
                    intValue("bodies", "GRAVITY_BODIES", defaults.initialBodies),
                    capacity,
                ),
                gridWidth = gridWidth,
                gridHeight = gridHeight,
                gravitationalConstant = floatValue(
                    "gravity",
                    "GRAVITY_G",
                    defaults.gravitationalConstant,
                ),
                softeningCells = floatValue(
                    "softening",
                    "GRAVITY_SOFTENING",
                    defaults.softeningCells,
                ),
                timeStep = floatValue("dt", "GRAVITY_DT", defaults.timeStep),
                collisionIterations = intValue(
                    "collisions",
                    "GRAVITY_COLLISIONS",
                    defaults.collisionIterations,
                ),
                contactDiameterPixels = floatValue(
                    "diameter",
                    "GRAVITY_DIAMETER",
                    defaults.contactDiameterPixels,
                ),
                maximumVisualPressure = floatValue(
                    "pressure-max",
                    "GRAVITY_PRESSURE_MAX",
                    defaults.maximumVisualPressure,
                ),
                maxFrames = values["frames"]?.toIntOrNull(),
                squareTestPixels = squareTestPixels,
                seed = values["seed"]?.toLongOrNull()
                    ?: if ("seed" in values) {
                        throw IllegalArgumentException("Invalid seed: ${values["seed"]}")
                    } else {
                        null
                    },
                profile = booleanValue("profile", defaults.profile) ||
                    benchmarkScenario != null,
                benchmarkScenario = benchmarkScenario,
                benchmarkWarmupFrames = values["warmup-frames"]?.toIntOrNull()
                    ?: defaults.benchmarkWarmupFrames,
                benchmarkFrames = values["benchmark-frames"]?.toIntOrNull()
                    ?: defaults.benchmarkFrames,
                validateFft = booleanValue("fft-validate", defaults.validateFft),
                adaptiveCollisions = booleanValue(
                    "adaptive-collisions",
                    defaults.adaptiveCollisions,
                ),
            )
        }
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
                "Invalid benchmark scenario: $value (expected calm or projectile)"
            )
        }
    }
}
