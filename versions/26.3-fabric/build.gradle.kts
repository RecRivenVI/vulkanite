import org.gradle.api.artifacts.ExternalModuleDependency

plugins {
    id("net.fabricmc.fabric-loom")
}

repositories {
    exclusiveContent {
        forRepository {
            maven {
                name = "Modrinth"
                url = uri("https://api.modrinth.com/maven")
            }
        }
        filter { includeGroup("maven.modrinth") }
    }
    mavenCentral()
}

dependencies {
    implementation(target.module("maven.modrinth:fabric-api", "fabric_version"))
    implementation(target.module("maven.modrinth:sodium", "sodium_version"))
    implementation(target.module("maven.modrinth:iris", "iris_version"))

    implementation(platform(libs.lwjgl.bom))
    compileOnly(libs.lwjgl.glfw)
    val meshoptimizer = libs.lwjgl.meshoptimizer.get()
    implementation(meshoptimizer)
    include(meshoptimizer)
    val nativePlatform =
        if (System.getProperty("os.name").lowercase().contains("win")) {
            "natives-windows"
        } else {
            "natives-linux"
        }
    val meshoptimizerNative =
        (libs.lwjgl.meshoptimizer.get().copy() as ExternalModuleDependency).apply {
            artifact { classifier = nativePlatform }
        }
    runtimeOnly(meshoptimizerNative)
    include(meshoptimizerNative)
    implementation(libs.joml)
}

java {
    withSourcesJar()
}
