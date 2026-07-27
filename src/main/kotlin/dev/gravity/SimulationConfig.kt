package dev.gravity

import kotlin.math.min

data class SimulationConfig(
    val capacity: Int = 4_000_000,
    val initialBodies: Int = 100_000,
    val gridSize: Int = 512,
    val fixedBodyCount: Boolean = false,
    val gravitationalConstant: Float = 0.035f,
    val softeningCells: Float = 0.85f,
    val timeStep: Float = 1f / 120f,
    val targetGpuMillis: Double = 13.5,
    val maxFrames: Int? = null,
) {
    init {
        require(capacity > 0) { "capacity must be positive" }
        require(initialBodies in 1..capacity) { "bodies must be in 1..capacity" }
        require(gridSize in 128..2048 && gridSize.countOneBits() == 1) {
            "grid must be a power of two in 128..2048"
        }
        require(gravitationalConstant > 0f)
        require(softeningCells > 0f)
        require(timeStep > 0f)
        require(maxFrames == null || maxFrames > 0)
    }

    companion object {
        fun parse(args: Array<String>, env: Map<String, String> = System.getenv()): SimulationConfig {
            val values = mutableMapOf<String, String>()
            var fixed = env["GRAVITY_FIXED"]?.toBooleanStrictOrNull() ?: false
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

            val capacity = intValue("capacity", "GRAVITY_CAPACITY", 4_000_000)
            return SimulationConfig(
                capacity = capacity,
                initialBodies = min(intValue("bodies", "GRAVITY_BODIES", 100_000), capacity),
                gridSize = intValue("grid", "GRAVITY_GRID", 512),
                fixedBodyCount = fixed,
                gravitationalConstant = floatValue("gravity", "GRAVITY_G", 0.035f),
                softeningCells = floatValue("softening", "GRAVITY_SOFTENING", 0.85f),
                timeStep = floatValue("dt", "GRAVITY_DT", 1f / 120f),
                maxFrames = values["frames"]?.toIntOrNull(),
            )
        }
    }
}
