package me.cortex.vulkanite.acceleration;

import static org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR;
import static org.lwjgl.vulkan.KHRBufferDeviceAddress.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR;
import static org.lwjgl.vulkan.VK10.*;

import java.util.ArrayList;
import java.util.List;
import me.cortex.vulkanite.compat.EntityFrame;
import me.cortex.vulkanite.compat.EntityRayPartition;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.cmd.VCmdBuff;
import me.cortex.vulkanite.lib.memory.VAccelerationStructure;
import me.cortex.vulkanite.lib.memory.VBuffer;
import me.cortex.vulkanite.lib.other.sync.FenceCallbacks;
import me.cortex.vulkanite.lib.other.sync.VFence;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

public class EntityBlasBuilder {
    private final VContext ctx;

    public EntityBlasBuilder(VContext context) {
        this.ctx = context;
    }

    private record BuildInfo(int triangleCount, long address, boolean opaque) {}

    record Geometry(VAccelerationStructure structure, VBuffer vertices, int mask) {
        void free() {
            FenceCallbacks.runAll(structure::free, vertices::free);
        }
    }

    record Build(List<Geometry> geometries) {
        void free() {
            FenceCallbacks.runAll(
                    geometries.stream().map(g -> (Runnable) g::free).toArray(Runnable[]::new));
        }
    }

    Build buildBlas(EntityFrame frame, VCmdBuff cmd, VFence fence) {
        var groups = EntityRayPartition.partition(frame.vertices());
        var buffers = new ArrayList<VBuffer>();
        var structures = new ArrayList<VAccelerationStructure>();
        var scratchBuffers = new ArrayList<VBuffer>();
        var result = new ArrayList<Geometry>();
        try {
            for (var group : groups) {
                var geometryBuffer =
                        ctx.memory.createBuffer(
                                group.byteCount(),
                                VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR
                                        | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
                                        | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
                                        | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
                                0,
                                VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT);
                buffers.add(geometryBuffer);
                long ptr = geometryBuffer.map();
                try {
                    long source = MemoryUtil.memAddress0(frame.vertices());
                    for (var span : group.spans()) {
                        MemoryUtil.memCopy(source + span.byteOffset(), ptr, span.byteCount());
                        ptr += span.byteCount();
                    }
                } finally {
                    geometryBuffer.unmap();
                }
                geometryBuffer.flush();
                try (var stack = MemoryStack.stackPush()) {
                    int[] counts = new int[1];
                    var geometries =
                            populateBuildStructs(
                                    stack,
                                    List.of(
                                            new BuildInfo(
                                                    group.triangleCount(),
                                                    geometryBuffer.deviceAddress(),
                                                    group.opaque())),
                                    counts);
                    var structure =
                            executeBlasBuild(
                                    ctx,
                                    cmd,
                                    stack,
                                    geometries,
                                    counts,
                                    structures,
                                    scratchBuffers);
                    result.add(new Geometry(structure, geometryBuffer, group.mask()));
                }
            }
            ctx.sync.addCallback(
                    fence,
                    () ->
                            FenceCallbacks.runAll(
                                    scratchBuffers.stream()
                                            .map(b -> (Runnable) b::free)
                                            .toArray(Runnable[]::new)));
            return new Build(List.copyOf(result));
        } catch (RuntimeException | Error failure) {
            var cleanup = new ArrayList<Runnable>();
            structures.forEach(s -> cleanup.add(s::free));
            buffers.forEach(b -> cleanup.add(b::free));
            scratchBuffers.forEach(b -> cleanup.add(b::free));
            try {
                // Commands have not been submitted; no GPU lease exists on these allocations.
                FenceCallbacks.runAll(cleanup.toArray(Runnable[]::new));
            } catch (Throwable error) {
                failure.addSuppressed(error);
            }
            throw failure;
        }
    }

    private VkAccelerationStructureGeometryKHR.Buffer populateBuildStructs(
            MemoryStack stack, List<BuildInfo> geometries, int[] primitiveCounts) {
        var geometryInfos = VkAccelerationStructureGeometryKHR.calloc(geometries.size(), stack);
        int i = 0;
        for (var geometry : geometries) {
            VkDeviceOrHostAddressConstKHR indexData =
                    VkDeviceOrHostAddressConstKHR.calloc(stack).deviceAddress(0);

            VkDeviceOrHostAddressConstKHR vertexData =
                    VkDeviceOrHostAddressConstKHR.calloc(stack).deviceAddress(geometry.address);
            int vertexFormat = VK_FORMAT_R32G32B32_SFLOAT;
            int vertexStride = me.cortex.vulkanite.compat.EntityFrame.STRIDE;

            geometryInfos
                    .get()
                    .sType$Default()
                    .geometry(
                            VkAccelerationStructureGeometryDataKHR.calloc(stack)
                                    .triangles(
                                            VkAccelerationStructureGeometryTrianglesDataKHR.calloc(
                                                            stack)
                                                    .sType$Default()
                                                    .vertexData(vertexData)
                                                    .vertexFormat(vertexFormat)
                                                    .vertexStride(vertexStride)
                                                    .maxVertex(geometry.triangleCount * 3 - 1)
                                                    .indexData(indexData)
                                                    .indexType(VK_INDEX_TYPE_NONE_KHR)))
                    .geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                    .flags(
                            VK_GEOMETRY_NO_DUPLICATE_ANY_HIT_INVOCATION_BIT_KHR
                                    | (geometry.opaque ? VK_GEOMETRY_OPAQUE_BIT_KHR : 0));
            primitiveCounts[i++] = geometry.triangleCount;
        }
        geometryInfos.rewind();
        return geometryInfos;
    }

    private static VAccelerationStructure executeBlasBuild(
            VContext ctx,
            VCmdBuff cmd,
            MemoryStack stack,
            VkAccelerationStructureGeometryKHR.Buffer geometryInfos,
            int[] prims,
            List<VAccelerationStructure> structures,
            List<VBuffer> scratchBuffers) {
        var buildInfos = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
        var buildRanges = VkAccelerationStructureBuildRangeInfoKHR.calloc(prims.length, stack);
        for (int primCount : prims) {
            buildRanges.get().primitiveCount(primCount);
        }

        var bi =
                buildInfos
                        .get()
                        .sType$Default()
                        .type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                        .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR)
                        .pGeometries(geometryInfos)
                        .geometryCount(geometryInfos.remaining());

        VkAccelerationStructureBuildSizesInfoKHR buildSizesInfo =
                VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();

        vkGetAccelerationStructureBuildSizesKHR(
                ctx.device,
                VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                bi,
                prims,
                buildSizesInfo);

        var structure =
                ctx.memory.createAcceleration(
                        buildSizesInfo.accelerationStructureSize(),
                        256,
                        VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR,
                        VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
        structures.add(structure);

        var scratch =
                ctx.memory.createBuffer(
                        buildSizesInfo.buildScratchSize(),
                        VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR
                                | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                        256,
                        0);
        scratchBuffers.add(scratch);

        bi.scratchData(
                VkDeviceOrHostAddressKHR.calloc(stack).deviceAddress(scratch.deviceAddress()));
        bi.dstAccelerationStructure(structure.structure);

        buildInfos.rewind();
        buildRanges.rewind();

        vkCmdBuildAccelerationStructuresKHR(cmd.buffer, buildInfos, stack.pointers(buildRanges));

        vkCmdPipelineBarrier(
                cmd.buffer,
                VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                0,
                VkMemoryBarrier.calloc(1, stack)
                        .sType$Default()
                        .srcAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR)
                        .dstAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR),
                null,
                null);

        return structure;
    }
}
