package me.cortex.vulkanite.compat;

public interface VulkanitePipelineAccess {
    PackCapabilities vulkanite$capabilities();

    void vulkanite$afterCompositePass(String name, java.util.Set<Integer> currentAltTargets);
}
