package me.cortex.vulkanite.lib.descriptors;

import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.memory.VAccelerationStructure;
import me.cortex.vulkanite.lib.memory.VBuffer;
import me.cortex.vulkanite.lib.other.VImageView;
import me.cortex.vulkanite.lib.other.VSampler;
import me.cortex.vulkanite.lib.shader.reflection.ShaderReflection;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.lwjgl.vulkan.VkWriteDescriptorSetAccelerationStructureKHR;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR;
import static org.lwjgl.vulkan.VK10.*;

import java.util.List;
import java.util.ArrayList;

public class DescriptorUpdateBuilder implements AutoCloseable {
    private final VContext ctx;
    private final MemoryStack stack;
    private final VkWriteDescriptorSet.Buffer updates;
    private final VImageView placeholderImageView;
    private ShaderReflection.Set refSet = null;
    private final List<Long> nativeAllocations = new ArrayList<>();
    private int stackBudget;
    private boolean closed;

    public DescriptorUpdateBuilder(VContext ctx, int maxUpdates) {
        this(ctx, maxUpdates, null);
    }

    public DescriptorUpdateBuilder(VContext ctx, int maxUpdates, VImageView placeholderImageView) {
        this.ctx = ctx;
        this.stack = MemoryStack.stackPush();
        this.stackBudget = Math.min(8192, stack.getPointer() / 2);
        try {
            this.updates = VkWriteDescriptorSet.create(
                    calloc(VkWriteDescriptorSet.ALIGNOF, maxUpdates, VkWriteDescriptorSet.SIZEOF), maxUpdates);
        } catch (RuntimeException | Error error) {
            close();
            throw error;
        }
        this.placeholderImageView = placeholderImageView;
    }

    // Chunk/texture descriptor counts are data-dependent. Bound stack use and spill
    // larger batches to explicitly owned native allocations until the synchronous update.
    private long calloc(int alignment, int count, int size) {
        if (closed) throw new IllegalStateException("Descriptor update is closed");
        long bytes = Math.multiplyExact((long) count, size);
        if (count < 0) throw new IllegalArgumentException("Negative descriptor count");
        if (bytes + alignment <= stackBudget) {
            stackBudget -= (int) bytes + alignment;
            return stack.ncalloc(alignment, count, size);
        }
        long address = MemoryUtil.nmemCallocChecked(Math.max(1, count), size);
        try { nativeAllocations.add(address); }
        catch (RuntimeException | Error error) {
            MemoryUtil.nmemFree(address);
            throw error;
        }
        return address;
    }

    private VkDescriptorBufferInfo.Buffer bufferInfos(int count) {
        return VkDescriptorBufferInfo.create(calloc(VkDescriptorBufferInfo.ALIGNOF, count, VkDescriptorBufferInfo.SIZEOF), count);
    }

    private VkDescriptorImageInfo.Buffer imageInfos(int count) {
        return VkDescriptorImageInfo.create(calloc(VkDescriptorImageInfo.ALIGNOF, count, VkDescriptorImageInfo.SIZEOF), count);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        for (long address : nativeAllocations) MemoryUtil.nmemFree(address);
        nativeAllocations.clear();
        stack.pop();
    }

    public DescriptorUpdateBuilder(VContext ctx, ShaderReflection.Set refSet) {
        this(ctx, refSet, null);
    }

    public DescriptorUpdateBuilder(VContext ctx, ShaderReflection.Set refSet, VImageView placeholderImageView) {
        this(ctx, refSet.bindings().size(), placeholderImageView);
        this.refSet = refSet;
    }

    private long viewOrPlaceholder(VImageView v) {
        if (v == null && placeholderImageView == null) return 0;
        return v == null ? placeholderImageView.view : v.view;
    }

    private long set;
    public DescriptorUpdateBuilder set(long set) {
        this.set = set;
        return this;
    }

    public DescriptorUpdateBuilder buffer(int binding, VBuffer buffer) {
        return buffer(binding, buffer, 0, VK_WHOLE_SIZE);
    }
    public DescriptorUpdateBuilder buffer(int binding, VBuffer buffer, long offset, long range) {
        if (refSet != null && refSet.getBindingAt(binding) == null) {
            return this;
        }
        updates.get()
                .sType$Default()
                .dstBinding(binding)
                .dstSet(set)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1)
                .pBufferInfo(bufferInfos(1)
                        .buffer(buffer.buffer())
                        .offset(offset)
                        .range(range));

        return this;
    }

    public DescriptorUpdateBuilder buffer(int binding, int dstArrayElement, List<VBuffer> buffers) {
        if (refSet != null && refSet.getBindingAt(binding) == null) {
            return this;
        }
        var bufInfo = bufferInfos(buffers.size());
        for (int i = 0; i < buffers.size(); i++) {
            bufInfo.get(i)
                    .buffer(buffers.get(i).buffer())
                    .offset(0)
                    .range(VK_WHOLE_SIZE);
        }
        updates.get()
                .sType$Default()
                .dstBinding(binding)
                .dstSet(set)
                .dstArrayElement(dstArrayElement)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(buffers.size())
                .pBufferInfo(bufInfo);

        return this;
    }


    public DescriptorUpdateBuilder uniform(int binding, VBuffer buffer) {
        return uniform(binding, buffer, 0, VK_WHOLE_SIZE);
    }
    public DescriptorUpdateBuilder uniform(int binding, VBuffer buffer, long offset, long range) {
        if (refSet != null && refSet.getBindingAt(binding) == null) {
            return this;
        }
        updates.get()
                .sType$Default()
                .dstBinding(binding)
                .dstSet(set)
                .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                .descriptorCount(1)
                .pBufferInfo(bufferInfos(1)
                        .buffer(buffer.buffer())
                        .offset(offset)
                        .range(range));
        return this;
    }

    public DescriptorUpdateBuilder acceleration(int binding, VAccelerationStructure... structures) {
        if (refSet != null && refSet.getBindingAt(binding) == null) {
            return this;
        }
        var buff = MemoryUtil.memLongBuffer(calloc(Long.BYTES, structures.length, Long.BYTES), structures.length);
        for (var structure : structures) {
            buff.put(structure.structure);
        }
        buff.rewind();
        updates.get()
                .sType$Default()
                .dstBinding(binding)
                .dstSet(set)
                .descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                .descriptorCount(structures.length)
                .pNext(VkWriteDescriptorSetAccelerationStructureKHR.create(calloc(
                        VkWriteDescriptorSetAccelerationStructureKHR.ALIGNOF, 1, VkWriteDescriptorSetAccelerationStructureKHR.SIZEOF))
                        .sType$Default()
                        .pAccelerationStructures(buff));
        return this;
    }

    public DescriptorUpdateBuilder imageStore(int binding, int dstArrayElement, List<VImageView> views) {
        if (refSet != null && refSet.getBindingAt(binding) == null) {
            return this;
        }
        var imgInfo = imageInfos(views.size());
        for (int i = 0; i < views.size(); i++) {
            imgInfo.get(i)
                    .imageLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .imageView(viewOrPlaceholder(views.get(i)));
        }
        updates.get()
                .sType$Default()
                .dstBinding(binding)
                .dstSet(set)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .descriptorCount(views.size())
                .pImageInfo(imgInfo);
        return this;
    }
    public DescriptorUpdateBuilder imageStore(int binding, VImageView view) {
        return imageStore(binding, VK_IMAGE_LAYOUT_GENERAL, view);
    }
    public DescriptorUpdateBuilder imageStore(int binding, int layout, VImageView view) {
        if (refSet != null && refSet.getBindingAt(binding) == null) {
            return this;
        }
        updates.get()
                .sType$Default()
                .dstBinding(binding)
                .dstSet(set)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .descriptorCount(1)
                .pImageInfo(imageInfos(1)
                        .imageLayout(layout)
                        .imageView(viewOrPlaceholder(view)));
        return this;
    }

    public DescriptorUpdateBuilder imageSampler(int binding, VImageView view, VSampler sampler) {
        return imageSampler(binding, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, view, sampler);
    }

    public DescriptorUpdateBuilder imageSampler(int binding, int layout, VImageView view, VSampler sampler) {
        if (refSet != null && refSet.getBindingAt(binding) == null) {
            return this;
        }
        updates.get()
                .sType$Default()
                .dstBinding(binding)
                .dstSet(set)
                .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(1)
                .pImageInfo(imageInfos(1)
                        .imageLayout(layout)
                        .imageView(viewOrPlaceholder(view))
                        .sampler(sampler.sampler));
        return this;
    }

    public void apply() {
        if (closed) throw new IllegalStateException("Descriptor update is closed");
        updates.limit(updates.position());
        updates.rewind();
        try { vkUpdateDescriptorSets(ctx.device, updates, null); }
        finally { close(); }
    }

    public DescriptorUpdateBuilder imageSamplers(int binding, List<VImageView> views, VSampler sampler) {
        return imageSamplers(binding, views, sampler, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    }

    public DescriptorUpdateBuilder imageSamplers(int binding, List<VImageView> views,
                                                  VSampler sampler, int imageLayout) {
        if (refSet != null && refSet.getBindingAt(binding) == null) return this;
        var infos = imageInfos(views.size());
        for (var view : views) infos.get().imageLayout(imageLayout)
                .imageView(view.view).sampler(sampler.sampler);
        infos.flip();
        updates.get().sType$Default().dstSet(set).dstBinding(binding)
                .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(views.size()).pImageInfo(infos);
        return this;
    }

    public DescriptorUpdateBuilder imageSamplers(int binding, List<VImageView> views, List<VSampler> samplers) {
        if (refSet != null && refSet.getBindingAt(binding) == null) return this;
        if (views.size()!=samplers.size()) throw new IllegalArgumentException("Texture/sampler slot counts differ");
        var infos=imageInfos(views.size());
        for(int i=0;i<views.size();i++) infos.get().imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                .imageView(views.get(i).view).sampler(samplers.get(i).sampler);
        infos.flip();
        updates.get().sType$Default().dstSet(set).dstBinding(binding)
                .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(views.size()).pImageInfo(infos);
        return this;
    }
}
