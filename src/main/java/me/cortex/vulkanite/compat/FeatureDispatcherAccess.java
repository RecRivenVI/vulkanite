package me.cortex.vulkanite.compat;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRendererMap;
public interface FeatureDispatcherAccess {
    FeatureFrameContext vulkanite$context();
    FeatureRendererMap vulkanite$renderers();
}
