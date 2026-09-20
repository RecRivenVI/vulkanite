package me.cortex.vulkanite.client.rendering.reconstruction;

import me.cortex.vulkanite.lib.descriptors.DescriptorUpdateBuilder;
import me.cortex.vulkanite.lib.shader.reflection.ShaderReflection;

/**
 * Supplies renderer-owned inputs to the reconstruction service. The initial
 * implementation is Vulkan ray tracing; an Iris/OpenGL provider can implement
 * the same contract without changing the NGX backend.
 */
public interface ReconstructionInputProvider {
    ReconstructionRequest request();
    String name();
    void validate(ShaderReflection.Set descriptorSet);
    void bind(ReconstructionService service, DescriptorUpdateBuilder update);
}
