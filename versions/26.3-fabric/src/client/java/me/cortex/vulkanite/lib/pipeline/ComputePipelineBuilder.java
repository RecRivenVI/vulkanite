package me.cortex.vulkanite.lib.pipeline;

import static me.cortex.vulkanite.lib.other.VUtil._CHECK_;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

import java.nio.LongBuffer;
import java.util.*;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.descriptors.VDescriptorSetLayout;
import me.cortex.vulkanite.lib.shader.ShaderModule;
import org.lwjgl.vulkan.*;

public class ComputePipelineBuilder {
    public ComputePipelineBuilder() {}

    Set<VDescriptorSetLayout> layouts = new LinkedHashSet<>();

    public ComputePipelineBuilder addLayout(VDescriptorSetLayout layout) {
        layouts.add(layout);
        return this;
    }

    private ShaderModule compute;

    public ComputePipelineBuilder set(ShaderModule shader) {
        this.compute = shader;
        return this;
    }

    private record PushConstant(int size, int offset) {}

    private List<PushConstant> pushConstants = new ArrayList<>();

    public void addPushConstantRange(int size, int offset) {
        pushConstants.add(new PushConstant(size, offset));
    }

    public VComputePipeline build(VContext context) {
        try (var stack = stackPush()) {

            VkPipelineLayoutCreateInfo layoutCreateInfo =
                    VkPipelineLayoutCreateInfo.calloc(stack).sType$Default();
            {
                // TODO: cleanup and add push constants
                layoutCreateInfo.pSetLayouts(
                        stack.longs(layouts.stream().mapToLong(a -> a.layout).toArray()));
            }

            if (pushConstants.size() > 0) {
                var pushConstantRanges = VkPushConstantRange.calloc(pushConstants.size(), stack);
                for (int i = 0; i < pushConstants.size(); i++) {
                    var pushConstant = pushConstants.get(i);
                    pushConstantRanges
                            .get(i)
                            .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT)
                            .offset(pushConstant.offset)
                            .size(pushConstant.size);
                }
                layoutCreateInfo.pPushConstantRanges(pushConstantRanges);
            }

            LongBuffer pLayout = stack.mallocLong(1);
            _CHECK_(vkCreatePipelineLayout(context.device, layoutCreateInfo, null, pLayout));

            VkPipelineShaderStageCreateInfo shaderStage =
                    VkPipelineShaderStageCreateInfo.calloc(stack);
            compute.setupStruct(stack, shaderStage);
            LongBuffer pPipeline = stack.mallocLong(1);
            var createInfo =
                    VkComputePipelineCreateInfo.calloc(1, stack)
                            .sType$Default()
                            .layout(pLayout.get(0))
                            .stage(shaderStage);
            try (var cache = PipelineCompilationCache.open(context.device)) {
                int status =
                        vkCreateComputePipelines(
                                context.device, cache.handle(), createInfo, null, pPipeline);
                _CHECK_(status);
                cache.save();
            }

            return new VComputePipeline(context, pLayout.get(0), pPipeline.get(0));
        }
    }
}
