package me.cortex.vulkanite.lib.pipeline;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memAlloc;
import static org.lwjgl.system.MemoryUtil.memFree;
import static org.lwjgl.vulkan.VK10.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HexFormat;
import me.cortex.vulkanite.lib.shader.CompilationCache;
import org.lwjgl.vulkan.*;

/** A private cache for one synchronous build. No cross-thread native-handle sharing. */
public final class PipelineCompilationCache implements AutoCloseable {
    private static final int MAX_BYTES = 256 * 1024 * 1024;
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("Vulkanite/Compilation");
    private final VkDevice device;
    private final String key;
    private final long handle;

    private PipelineCompilationCache(VkDevice device, String key, long handle) {
        this.device = device;
        this.key = key;
        this.handle = handle;
    }

    public long handle() {
        return handle;
    }

    public static PipelineCompilationCache open(VkDevice device) {
        try (var stack = stackPush()) {
            var properties = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(device.getPhysicalDevice(), properties);
            byte[] uuid = new byte[VK_UUID_SIZE];
            properties.pipelineCacheUUID().get(uuid);
            String key =
                    CompilationCache.key(
                            "pipeline-cache-v1",
                            Integer.toString(properties.vendorID()),
                            Integer.toString(properties.deviceID()),
                            Integer.toString(properties.driverVersion()),
                            HexFormat.of().formatHex(uuid));
            byte[] bytes = CompilationCache.read("pipeline-v1", key, MAX_BYTES);
            if (bytes != null
                    && !compatibleHeader(bytes, properties.vendorID(), properties.deviceID(), uuid))
                bytes = null;
            ByteBuffer initial = null;
            try {
                if (bytes != null) initial = memAlloc(bytes.length).put(bytes).flip();
                var info =
                        VkPipelineCacheCreateInfo.calloc(stack)
                                .sType$Default()
                                .pInitialData(initial);
                var result = stack.callocLong(1);
                int status = vkCreatePipelineCache(device, info, null, result);
                if (status != VK_SUCCESS && initial != null) {
                    LOG.warn("Driver rejected persistent cache ({}); retrying empty", status);
                    bytes = null;
                    info.pInitialData(null);
                    status = vkCreatePipelineCache(device, info, null, result);
                }
                if (status != VK_SUCCESS) {
                    LOG.warn("Pipeline cache unavailable ({}); compiling without it", status);
                    return new PipelineCompilationCache(device, key, 0);
                }
                return new PipelineCompilationCache(device, key, result.get(0));
            } finally {
                if (initial != null) memFree(initial);
            }
        }
    }

    public static boolean compatibleHeader(byte[] bytes, int vendor, int device, byte[] uuid) {
        if (bytes.length < 32 || uuid.length != VK_UUID_SIZE) return false;
        var header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        return header.getInt(0) == 32
                && header.getInt(4) == VK_PIPELINE_CACHE_HEADER_VERSION_ONE
                && header.getInt(8) == vendor
                && header.getInt(12) == device
                && Arrays.equals(Arrays.copyOfRange(bytes, 16, 32), uuid);
    }

    public void save() {
        if (handle == 0) return;
        try (var stack = stackPush()) {
            var size = stack.callocPointer(1);
            int status = vkGetPipelineCacheData(device, handle, size, null);
            long count = size.get(0);
            if (status != VK_SUCCESS || count < 32 || count > MAX_BYTES) {
                LOG.warn("Skipping pipeline cache snapshot: status={} bytes={}", status, count);
                return;
            }
            var data = memAlloc((int) count);
            try {
                status = vkGetPipelineCacheData(device, handle, size, data);
                if (status != VK_SUCCESS) {
                    LOG.warn("Skipping incomplete pipeline cache snapshot: {}", status);
                    return;
                }
                byte[] bytes = new byte[(int) size.get(0)];
                data.get(bytes);
                CompilationCache.write("pipeline-v1", key, bytes);
            } finally {
                memFree(data);
            }
        }
    }

    @Override
    public void close() {
        if (handle == 0) return;
        vkDestroyPipelineCache(device, handle, null);
    }
}
