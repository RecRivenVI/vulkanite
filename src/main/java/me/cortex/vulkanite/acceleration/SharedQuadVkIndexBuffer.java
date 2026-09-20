package me.cortex.vulkanite.acceleration;

import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.cmd.VCmdBuff;
import me.cortex.vulkanite.lib.memory.VBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkDeviceOrHostAddressConstKHR;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

public class SharedQuadVkIndexBuffer {
    public static final int TYPE = VK_INDEX_TYPE_UINT32;
    // Build-local ownership. The old global buffer could neither grow safely nor synchronize two queues.
    public static VkDeviceOrHostAddressConstKHR getIndexBuffer(VContext context, VCmdBuff cmd, int quadCount) {
        if (quadCount <= 0) throw new IllegalArgumentException("Empty quad index buffer");
        ByteBuffer indices = genQuadIdxs(quadCount);
        try {
            VBuffer buffer = context.memory.createBuffer(indices.remaining(),
                    VK_BUFFER_USAGE_INDEX_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT
                            | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
                            | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            cmd.encodeDataUpload(context.memory, MemoryUtil.memAddress(indices), buffer, 0, indices.remaining());
            cmd.addTransientResource(buffer);
            cmd.encodeBufferBarrier(buffer, 0, indices.remaining(), VK_PIPELINE_STAGE_TRANSFER_BIT,
                    org.lwjgl.vulkan.KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR);
            return VkDeviceOrHostAddressConstKHR.calloc(org.lwjgl.system.MemoryStack.stackGet()).deviceAddress(buffer.deviceAddress());
        } finally {
            MemoryUtil.memFree(indices);
        }
    }
    public static ByteBuffer genQuadIdxs(int quadCount) {
        //short[] idxs = {0, 1, 2, 0, 2, 3};

        int vertexCount = quadCount * 4;
        int indexCount = vertexCount * 3 / 2;
        ByteBuffer buffer = MemoryUtil.memAlloc(indexCount * Integer.BYTES);
        IntBuffer idxs = buffer.asIntBuffer();
        //short[] idxs = new short[indexCount];

        int j = 0;
        for(int i = 0; i < vertexCount; i += 4) {

            idxs.put(j, i);
            idxs.put(j + 1, (i + 1));
            idxs.put(j + 2, (i + 2));
            idxs.put(j + 3, (i));
            idxs.put(j + 4, (i + 2));
            idxs.put(j + 5, (i + 3));

            j += 6;
        }

        return buffer;
    }
}
