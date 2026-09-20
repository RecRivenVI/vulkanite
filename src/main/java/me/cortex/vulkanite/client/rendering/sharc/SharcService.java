package me.cortex.vulkanite.client.rendering.sharc;

import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.cmd.VCmdBuff;
import me.cortex.vulkanite.lib.memory.VBuffer;
import me.cortex.vulkanite.lib.pipeline.ComputePipelineBuilder;
import me.cortex.vulkanite.lib.pipeline.VComputePipeline;
import me.cortex.vulkanite.lib.shader.VShader;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.KHRBufferDeviceAddress.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Host-owned SHaRC resources and Resolve pass. Packs produce Update samples and
 * query the previous resolved cache through the addresses written to the camera ABI.
 */
public final class SharcService implements AutoCloseable {
    public static final int CAMERA_ABI_OFFSET = 480;
    private static final int HASH_STRIDE = 4;
    private static final int ACCUMULATION_STRIDE = 16;
    private static final int RESOLVED_STRIDE = 16;
    private static final int PUSH_CONSTANT_SIZE = 96;
    private static final int LOCAL_SIZE = 256;

    private final VContext ctx;
    public final SharcRequest request;
    private final int capacity;
    private final VBuffer hashEntries;
    private final VBuffer accumulation;
    private final VBuffer resolved;
    private final VShader resolveShader;
    private final VComputePipeline resolvePipeline;
    private final Vector3f previousCamera = new Vector3f();
    private boolean hasPreviousCamera;
    private long frameIndex;

    public SharcService(VContext ctx, SharcRequest request) {
        this.ctx = ctx;
        this.request = request;
        this.capacity = Integer.getInteger("vulkanite.sharc.capacityPower", request.capacityPower());
        if (capacity < SharcRequest.MIN_CAPACITY_POWER || capacity > SharcRequest.MAX_CAPACITY_POWER)
            throw new IllegalArgumentException("Invalid vulkanite.sharc.capacityPower: " + capacity);
        int entryCount = 1 << capacity;
        int usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR;
        hashEntries = ctx.memory.createBuffer((long) entryCount * HASH_STRIDE, usage, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        accumulation = ctx.memory.createBuffer((long) entryCount * ACCUMULATION_STRIDE, usage, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        resolved = ctx.memory.createBuffer((long) entryCount * RESOLVED_STRIDE, usage, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        resolveShader = VShader.compileLoad(ctx, SharcShaderLibrary.loadExpanded("sharc_resolve.comp"), VK_SHADER_STAGE_COMPUTE_BIT);
        var builder = new ComputePipelineBuilder();
        builder.set(resolveShader.named());
        builder.addPushConstantRange(PUSH_CONSTANT_SIZE, 0);
        resolvePipeline = builder.build(ctx);
        ctx.cmd.executeWait(cmd -> {
            vkCmdFillBuffer(cmd.buffer, hashEntries.buffer(), 0, VK_WHOLE_SIZE, 0);
            vkCmdFillBuffer(cmd.buffer, accumulation.buffer(), 0, VK_WHOLE_SIZE, 0);
            vkCmdFillBuffer(cmd.buffer, resolved.buffer(), 0, VK_WHOLE_SIZE, 0);
            cmd.encodeMemoryBarrier();
        });
        long bytes = hashEntries.size() + accumulation.size() + resolved.size();
        LoggerFactory.getLogger("Vulkanite/SHaRC").info("SHaRC 1.8.3 ready: entries=2^{} memory={} MiB compact-hash=true SH=false",
                capacity, bytes / (1024 * 1024));
    }

    public int entryCount() {
        return 1 << capacity;
    }

    /** Writes the stable pack-facing SHaRC ABI into the existing camera UBO. */
    public void writeCamera(ByteBuffer buffer, Vector3f cameraPosition) {
        putAddress(buffer, CAMERA_ABI_OFFSET, hashEntries.deviceAddress());
        putAddress(buffer, CAMERA_ABI_OFFSET + 8, accumulation.deviceAddress());
        putAddress(buffer, CAMERA_ABI_OFFSET + 16, resolved.deviceAddress());
        buffer.putInt(CAMERA_ABI_OFFSET + 24, entryCount());
        buffer.putInt(CAMERA_ABI_OFFSET + 28, (int) frameIndex);
        buffer.putInt(CAMERA_ABI_OFFSET + 32, Boolean.getBoolean("vulkanite.sharc.shaderAccess.disabled") ? 0 : 1);
        buffer.putFloat(CAMERA_ABI_OFFSET + 48, sceneScale());
        buffer.putFloat(CAMERA_ABI_OFFSET + 52, radianceScale());
        buffer.putFloat(CAMERA_ABI_OFFSET + 56, 2.0f);
        buffer.putFloat(CAMERA_ABI_OFFSET + 60, 0.0f);
    }

    /** Must execute after the RT Update writes and before the next frame queries the cache. */
    public void resolve(VCmdBuff cmd, Vector3f cameraPosition) {
        if (Boolean.getBoolean("vulkanite.sharc.resolve.disabled")) {
            previousCamera.set(cameraPosition);
            hasPreviousCamera = true;
            frameIndex++;
            return;
        }
        cmd.encodeBufferBarrier(hashEntries, 0, VK_WHOLE_SIZE, VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        cmd.encodeBufferBarrier(accumulation, 0, VK_WHOLE_SIZE, VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        cmd.encodeBufferBarrier(resolved, 0, VK_WHOLE_SIZE, VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        resolvePipeline.bind(cmd);
        try (var stack = MemoryStack.stackPush()) {
            ByteBuffer push = stack.calloc(PUSH_CONSTANT_SIZE);
            putAddress(push, 0, hashEntries.deviceAddress());
            putAddress(push, 8, accumulation.deviceAddress());
            putAddress(push, 16, resolved.deviceAddress());
            push.putInt(24, entryCount());
            push.putFloat(28, sceneScale());
            push.putFloat(32, radianceScale());
            push.putFloat(36, 0.0f);
            push.putFloat(48, cameraPosition.x).putFloat(52, cameraPosition.y).putFloat(56, cameraPosition.z);
            Vector3f previous = hasPreviousCamera ? previousCamera : cameraPosition;
            push.putFloat(64, previous.x).putFloat(68, previous.y).putFloat(72, previous.z);
            push.putInt(80, accumulatedFrames());
            push.putInt(84, 8);
            push.putInt(88, staleFrames());
            push.putInt(92, (int) frameIndex);
            resolvePipeline.pushConstants(cmd, push);
        }
        resolvePipeline.dispatch(cmd, (entryCount() + LOCAL_SIZE - 1) / LOCAL_SIZE, 1, 1);
        cmd.encodeBufferBarrier(hashEntries, 0, VK_WHOLE_SIZE, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR);
        cmd.encodeBufferBarrier(accumulation, 0, VK_WHOLE_SIZE, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR);
        cmd.encodeBufferBarrier(resolved, 0, VK_WHOLE_SIZE, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR);
        previousCamera.set(cameraPosition);
        hasPreviousCamera = true;
        frameIndex++;
    }

    private static void putAddress(ByteBuffer buffer, int offset, long address) {
        buffer.putInt(offset, (int) address).putInt(offset + 4, (int) (address >>> 32));
    }

    private static float sceneScale() { return Float.parseFloat(System.getProperty("vulkanite.sharc.sceneScale", "1.0")); }
    private static float radianceScale() { return Float.parseFloat(System.getProperty("vulkanite.sharc.radianceScale", "1000.0")); }
    private static int accumulatedFrames() { return Integer.getInteger("vulkanite.sharc.accumulatedFrames", 64); }
    private static int staleFrames() { return Integer.getInteger("vulkanite.sharc.staleFrames", 128); }

    @Override
    public void close() {
        resolvePipeline.free();
        resolveShader.free();
        resolved.free();
        accumulation.free();
        hashEntries.free();
    }
}
