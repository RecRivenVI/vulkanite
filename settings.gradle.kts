pluginManagement {
    includeBuild("components/configuration")
    includeBuild("components/conventions")
    includeBuild("components/compliance")
    repositories {
        gradlePluginPortal()
        maven("https://maven.neoforged.net/releases")
        maven("https://maven.fabricmc.net/")
    }
}

plugins {
    id("io.github.recrivenvi.configuration")
    id("io.github.recrivenvi.conventions")
    id("io.github.recrivenvi.compliance")
}

rootProject.name = "Vulkanite"

targets {
    register("26.3-fabric")
}

components {
    tool("foundation_pack")
    tool("automation")
}
