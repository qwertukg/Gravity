package dev.gravity

fun main(args: Array<String>) {
    val config = try {
        SimulationConfig.parse(args)
    } catch (error: IllegalArgumentException) {
        System.err.println(error.message)
        System.err.println(
            "Usage: gravity [--bodies=N] [--capacity=N] [--grid=WIDTHxHEIGHT] " +
                "[--gravity=F] [--softening=F] [--dt=F] [--collisions=N] " +
                "[--diameter=F] [--pressure-max=F] [--frames=N] " +
                "[--square-test=PIXELS] [--seed=N] [--profile=true|false] " +
                "[--benchmark=calm|projectile] [--warmup-frames=N] " +
                "[--benchmark-frames=N] [--fft-validate=true|false] " +
                "[--adaptive-collisions=true|false]"
        )
        return
    }
    GravityApplication(config).use(GravityApplication::run)
}
