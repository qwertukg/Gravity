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
    runtimeOnly("org.lwjgl:lwjgl::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-glfw::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-opengl::$lwjglNatives")

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
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
