package me.cortex.vulkanite.compat;

import java.util.Optional;

/** Iris resources that a Vulkanite shader pack needs shared during pipeline construction. */
public record PackCapabilities(
        int sharedColorTargets,
        boolean sharedCustomTextures,
        boolean sceneGeometry,
        Optional<VulkanPassContract> execution) {
    /** Ordinary Iris rendering keeps its original OpenGL allocations. */
    public static final PackCapabilities OPENGL =
            new PackCapabilities(0, false, false, Optional.empty());

    public PackCapabilities {
        if (sharedColorTargets < 0)
            throw new IllegalArgumentException("Shared color-target prefix must not be negative");
        execution = java.util.Objects.requireNonNull(execution, "execution");
        if (execution.isEmpty()
                && (sharedColorTargets != 0 || sharedCustomTextures || sceneGeometry))
            throw new IllegalArgumentException(
                    "Vulkan resources require a declared composite execution");
        execution.ifPresent(
                pass -> {
                    if (sharedColorTargets <= pass.colorWrite()
                            || pass.colorReads().stream().anyMatch(id -> id >= sharedColorTargets))
                        throw new IllegalArgumentException(
                                "Composite color input/output exceeds the shared target prefix");
                });
    }

    /** `sharedColorTargets` names the contiguous prefix colortex0 through colortexN-1. */
    public boolean needsVulkan() {
        return execution.isPresent();
    }

    public boolean sharedStorageBuffers() {
        return execution.map(pass -> !pass.storageMinimumBytes().isEmpty()).orElse(false);
    }
}
