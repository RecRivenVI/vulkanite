package me.cortex.vulkanite.lib.pipeline;

import me.cortex.vulkanite.lib.base.TrackedResourceObject;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.other.sync.VFence;

public class VComputePipeline extends TrackedResourceObject {
    private final VContext context;
    private final long pipeline;
    private final long layout;

    public VComputePipeline(VContext context, long layout, long pipeline) {
        this.context = context;
        this.layout = layout;
        this.pipeline = pipeline;
    }

    public long layout() {
        return layout;
    }

    public long pipeline() {
        return pipeline;
    }

    public void bind(me.cortex.vulkanite.lib.cmd.VCmdBuff cmd) {
        org.lwjgl.vulkan.VK10.vkCmdBindPipeline(cmd.buffer, org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
    }

    public void pushConstants(me.cortex.vulkanite.lib.cmd.VCmdBuff cmd, java.nio.ByteBuffer data) {
        org.lwjgl.vulkan.VK10.vkCmdPushConstants(cmd.buffer, layout, org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, data);
    }

    public void dispatch(me.cortex.vulkanite.lib.cmd.VCmdBuff cmd, int x, int y, int z) {
        org.lwjgl.vulkan.VK10.vkCmdDispatch(cmd.buffer, x, y, z);
    }

    @Override
    public void free() {
        free0();
        org.lwjgl.vulkan.VK10.vkDestroyPipeline(context.device, pipeline, null);
        org.lwjgl.vulkan.VK10.vkDestroyPipelineLayout(context.device, layout, null);
    }
}
