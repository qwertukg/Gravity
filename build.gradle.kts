import org.gradle.internal.os.OperatingSystem

plugins {
    kotlin("jvm") version "2.4.10"
    application
}

group = "dev.gravity"
version = "1.0.0"

repositories {
    mavenCentral()
}

val lwjglVersion = "3.4.1"
val lwjglNatives = when {
    OperatingSystem.current().isWindows -> "natives-windows"
    OperatingSystem.current().isMacOsX -> "natives-macos"
    else -> "natives-linux"
}

dependencies {
    implementation(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))
    implementation("org.lwjgl:lwjgl")
    implementation("org.lwjgl:lwjgl-glfw")
    implementation("org.lwjgl:lwjgl-opengl")
    implementation("org.lwjgl:lwjgl-stb")
    implementation("net.java.dev.jna:jna-platform:5.18.1")
    runtimeOnly("org.lwjgl:lwjgl::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-glfw::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-opengl::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-stb::$lwjglNatives")

    testImplementation(kotlin("test"))
    testImplementation("com.github.wendykierp:JTransforms:3.2")
}

kotlin {
    jvmToolchain(18)
}

application {
    mainClass = "dev.gravity.MainKt"
    applicationDefaultJvmArgs = listOf(
        "-Xms512m",
        "-Xmx2g",
        "-XX:+UseZGC"
    )
}

tasks.test {
    useJUnitPlatform()
    exclude("**/CollisionShaderIntegrationTest.class")
}

tasks.register<Test>("gpuTest") {
    description = "Run collision shaders against the reference on a real OpenGL 4.3 GPU (hidden window)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    include("**/CollisionShaderIntegrationTest.class")
    outputs.upToDateWhen { false }
    testLogging.showStandardStreams = true
}

tasks.register<JavaExec>("collisionBenchmark") {
    description = "Compare fixed-substep GPU pipelines without opening a visible window."
    group = "verification"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "dev.gravity.CollisionBenchmark"
    dependsOn(tasks.testClasses)
    jvmArgs("-Xms512m", "-Xmx2g")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
