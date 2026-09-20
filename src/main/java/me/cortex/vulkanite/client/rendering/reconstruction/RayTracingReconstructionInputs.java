package me.cortex.vulkanite.client.rendering.reconstruction;

import me.cortex.vulkanite.lib.descriptors.DescriptorUpdateBuilder;
import me.cortex.vulkanite.lib.shader.reflection.ShaderReflection;

/** Reconstruction ABI v2 producer used by Vulkanite ray-generation shaders. */
public record RayTracingReconstructionInputs(ReconstructionRequest request) implements ReconstructionInputProvider {
    public static RayTracingReconstructionInputs parse(String expandedRaygen) {
        ReconstructionRequest request = ReconstructionRequest.parse(expandedRaygen);
        return request == null ? null : new RayTracingReconstructionInputs(request);
    }

    @Override public String name() { return "vulkan-raytracing"; }

    @Override
    public void validate(ShaderReflection.Set descriptorSet) {
        for (int binding = 8; binding < 16; binding++) {
            if (descriptorSet.getBindingAt(binding) == null)
                throw new IllegalArgumentException("Reconstruction ABI missing binding " + binding);
        }
    }

    @Override
    public void bind(ReconstructionService service, DescriptorUpdateBuilder update) {
        service.bindRayTracingInputs(update);
    }
}
