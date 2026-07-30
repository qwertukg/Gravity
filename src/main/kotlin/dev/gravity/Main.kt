package dev.gravity

fun main(args: Array<String>) {
    val config = try {
        GravityConfig.parse(args)
    } catch (error: IllegalArgumentException) {
        System.err.println(error.message)
        System.err.println("Usage: gravity [--key=value]...")
        System.err.println(
            "Supported keys: " +
                GravityConfig.supportedOptionNames.joinToString(", "),
        )
        return
    }
    GravityApplication(config).use(GravityApplication::run)
}
