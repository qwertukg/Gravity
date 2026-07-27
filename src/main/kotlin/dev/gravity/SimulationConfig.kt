package dev.gravity

import kotlin.math.min

data class SimulationConfig(
    val capacity: Int = 1_000_000,
    val initialBodies: Int = 1_000_000,
    val gridWidth: Int = 1024,
    val gridHeight: Int = 1024,
    val fixedBodyCount: Boolean = true,
    val gravitationalConstant: Float = 0.035f,
    val softeningCells: Float = 0.85f,
    val timeStep: Float = 1f / 120f,
    val targetGpuMillis: Double = 13.5,
    val maxFrames: Int? = null,
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
        require(gravitationalConstant > 0f)
        require(softeningCells > 0f)
        require(timeStep > 0f)
        require(maxFrames == null || maxFrames > 0)
    }

    companion object {
        fun parse(args: Array<String>, env: Map<String, String> = System.getenv()): SimulationConfig {
            val values = mutableMapOf<String, String>()
            var fixed = env["GRAVITY_FIXED"]?.toBooleanStrictOrNull() ?: true
            args.forEach { argument ->
                when {
                    argument == "--fixed" -> fixed = true
                    argument == "--adaptive" -> fixed = false
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

            val grid = values["grid"] ?: env["GRAVITY_GRID"]
            val (gridWidth, gridHeight) = when {
                grid == null -> 1024 to 1024
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
            val capacity = intValue("capacity", "GRAVITY_CAPACITY", 1_000_000)
            return SimulationConfig(
                capacity = capacity,
                initialBodies = min(intValue("bodies", "GRAVITY_BODIES", 1_000_000), capacity),
                gridWidth = gridWidth,
                gridHeight = gridHeight,
                fixedBodyCount = fixed,
                gravitationalConstant = floatValue("gravity", "GRAVITY_G", 0.035f),
                softeningCells = floatValue("softening", "GRAVITY_SOFTENING", 0.85f),
                timeStep = floatValue("dt", "GRAVITY_DT", 1f / 120f),
                maxFrames = values["frames"]?.toIntOrNull(),
            )
        }
    }
}
