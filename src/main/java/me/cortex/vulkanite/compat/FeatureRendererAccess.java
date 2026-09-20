package me.cortex.vulkanite.compat;
import java.util.List;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
public interface FeatureRendererAccess {
    void vulkanite$buildGroup(FeatureFrameContext context, List<? extends SubmitNode> submits);
}
