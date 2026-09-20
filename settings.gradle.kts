pluginManagement {
    includeBuild("gradle/conventions")
    repositories {
        gradlePluginPortal()
        maven("https://maven.neoforged.net/releases")
        maven("https://maven.fabricmc.net/")
    }
}

plugins {
    id("io.github.recrivenvi.conventions")
}

rootProject.name = "Vulkanite"

targets {
    register("26.3-fabric")
}

components {
    tool("foundation_pack")
    tool("automation")
}
