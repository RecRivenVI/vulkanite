package me.cortex.vulkanite.lib.descriptors;

import static org.lwjgl.vulkan.VK10.vkDestroyDescriptorSetLayout;

import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.lib.base.TrackedResourceObject;
import me.cortex.vulkanite.lib.base.VContext;
import org.lwjgl.system.Pointer;

public final class VDescriptorSetLayout extends TrackedResourceObject implements Pointer {
    private final VContext ctx;
    public final long layout;
    public final int[] types;
    public final int[] counts;

    public VDescriptorSetLayout(VContext ctx, long layout, int[] types, int[] counts) {
        this.ctx = ctx;
        this.layout = layout;
        this.types = types;
        this.counts = counts;
    }

    @Override
    public long address() {
        return layout;
    }

    @Override
    public void free() {
        Vulkanite.getInstance().removePoolByLayout(this);
        free0();
        vkDestroyDescriptorSetLayout(ctx.device, layout, null);
    }
}
