# Vulkanite

Vulkanite adds a pack-controlled Vulkan ray-tracing segment to Minecraft 26.3 Fabric shader packs. It consumes normal Sodium/Iris scene output and supported indexed RenderPearl world draws. Iris keeps the OpenGL window, GUI, composite programs and final display pass. Ordinary OpenGL shader packs continue through their usual path.

Install the Vulkanite mod alongside the matching Minecraft, Sodium and Iris versions, then select a Vulkanite-compatible shader pack in Iris. [Vulkanite Foundation](packs/Vulkanite-Foundation/README.md) is a small path-tracing reference pack with sample and bounce controls plus Iris-managed exposure and display output. The current build does not include DLSS, reconstruction or SHaRC.

The core accepts geometry and textures produced by normal rendering. It does not provide an external scene-mesh publishing API or add gameplay blocks. Visible particles can enter path tracing; particles culled before Iris submits them are not guaranteed to affect off-screen reflections or shadows. Indexed draws require a supported triangle vertex layout and standard transforms; arbitrary vertex-shader deformation is outside the current scene contract.

Build the Fabric mod with JDK 25 using `gradlew build`. The Foundation ZIP must contain `shaders/`, `README.md` and `NOTICE.md` at its root.

Shader authors can refer to the [current pack interface](docs/shader-pack-interface.md).
