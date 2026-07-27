package dev.gravity

fun main(args: Array<String>) {
    val config = try {
        SimulationConfig.parse(args)
    } catch (error: IllegalArgumentException) {
        System.err.println(error.message)
        System.err.println(
            "Usage: gravity [--bodies=N] [--capacity=N] [--grid=256|512|1024] " +
                "[--gravity=F] [--softening=F] [--dt=F] [--frames=N] [--fixed|--adaptive]"
        )
        return
    }
    GravityApplication(config).use(GravityApplication::run)
}
