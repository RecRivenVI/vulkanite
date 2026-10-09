package me.cortex.vulkanite.acceleration;

// TLAS manager, ingests blas build requests and manages builds and syncs the tlas

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.KHRBufferDeviceAddress.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.*;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.cmd.VCmdBuff;
import me.cortex.vulkanite.lib.cmd.VCommandPool;
import me.cortex.vulkanite.lib.descriptors.DescriptorSetLayoutBuilder;
import me.cortex.vulkanite.lib.descriptors.DescriptorUpdateBuilder;
import me.cortex.vulkanite.lib.descriptors.VDescriptorPool;
import me.cortex.vulkanite.lib.descriptors.VDescriptorSetLayout;
import me.cortex.vulkanite.lib.memory.VAccelerationStructure;
import me.cortex.vulkanite.lib.memory.VBuffer;
import me.cortex.vulkanite.lib.other.sync.VFence;
import me.cortex.vulkanite.lib.other.sync.VSemaphore;
import org.apache.commons.lang3.tuple.Pair;
import org.joml.Matrix4x3f;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

public class AccelerationTLASManager {
    private final EntityBlasBuilder entityBlasBuilder;
    private final TLASSectionManager buildDataManager = new TLASSectionManager();
    private final VContext context;
    private final Thread renderThread;
    private final int queue;
    private final VCommandPool singleUsePool;

    private final List<VAccelerationStructure> structuresToRelease = new ArrayList<>();
    private final List<VBuffer> rejectedBuffers = new ArrayList<>();

    private VAccelerationStructure currentTLAS;
    private int currentTlasInstanceCount = -1;
    private VBuffer tlasScratch;
    private long tlasScratchSize;
    private Pair<VAccelerationStructure, VBuffer> currentEntityBuild;
    private int lastEntityContentHash;
    private int lastEntityContentBytes = -1;

    private static int vulkanite$entityContentHash(me.cortex.vulkanite.compat.EntityFrame frame) {
        var buf = frame.vertices().duplicate();
        int h = 1;
        while (buf.remaining() >= 4) {
            h = 31 * h + buf.getInt();
        }
        while (buf.hasRemaining()) {
            h = 31 * h + (buf.get() & 0xff);
        }
        return h;
    }

    // Called only after the queue has completed every dispatch that can reference it.
    private void releaseEntities() {
        if (currentEntityBuild != null) {
            currentEntityBuild.getLeft().free();
            currentEntityBuild.getRight().free();
            currentEntityBuild = null;
        }
    }

    public AccelerationTLASManager(VContext context, int queue) {
        this.context = context;
        this.renderThread = Thread.currentThread();
        this.queue = queue;
        this.singleUsePool = context.cmd.createSingleUsePool();
        this.buildDataManager.resizeBindlessSet(0, null);
        this.entityBlasBuilder = new EntityBlasBuilder(context);
    }

    // Returns a sync semaphore to chain in the next command submit
    public void updateSections(List<AccelerationBlasBuilder.BLASBuildResult> results) {
        for (var result : results) {

            buildDataManager.update(result);
        }
    }

    public void reject(AccelerationBlasBuilder.BLASBuildResult result) {
        structuresToRelease.add(result.structure());
        rejectedBuffers.addAll(result.data().geometryBuffers());
    }

    private me.cortex.vulkanite.compat.EntityFrame entityData;

    public void setEntityData(me.cortex.vulkanite.compat.EntityFrame data) {
        this.entityData = data;
    }

    public boolean removeSection(SectionHandle section) {
        return buildDataManager.remove(section);
    }

    // TODO: cleanup, this is very messy
    // FIXME: in the case of no geometry create an empty tlas or something???
    public void buildTLAS(VSemaphore semIn, VSemaphore semOut, VSemaphore[] blocking) {
        long tlasCpuStart = System.nanoTime();
        try {
            buildTLAS0(semIn, semOut, blocking);
        } finally {
            me.cortex.vulkanite.audit.Diagnostics.addCpu(
                    "tlasCpu", System.nanoTime() - tlasCpuStart);
        }
    }

    private void buildTLAS0(VSemaphore semIn, VSemaphore semOut, VSemaphore[] blocking) {
        if (Thread.currentThread() != renderThread)
            throw new IllegalStateException("TLAS build must run on the render thread");

        singleUsePool.doReleases();

        // NOTE: renderLink is required to ensure that we are not overriding memory that
        // is actively being used for frames
        // should have a VK_PIPELINE_STAGE_TRANSFER_BIT blocking bit
        try (var stack = stackPush()) {
            // The way the tlas build works is that terrain data is split up into regions,
            // each region is its own geometry input
            // this is done for performance reasons when updating (adding/removing) sections

            VkAccelerationStructureGeometryKHR geometry =
                    VkAccelerationStructureGeometryKHR.calloc(stack);
            int instances = 0;

            var cmd = singleUsePool.createCommandBuffer();
            cmd.begin(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            me.cortex.vulkanite.lib.other.GpuTimestamps.ensure(context.device);
            // Note: TLAS cmd is a different buffer from the RT frame; use a separate slot write
            // only if this command is the same frame submission. We record on this cmd when
            // possible.
            VFence fence = context.sync.createFence();

            Pair<VAccelerationStructure, VBuffer> oldEntityBuild = currentEntityBuild;
            Pair<VAccelerationStructure, VBuffer> entityBuild;
            if (entityData != null) {
                int contentHash = vulkanite$entityContentHash(entityData);
                if (currentEntityBuild != null
                        && contentHash == lastEntityContentHash
                        && entityData.vertices().remaining() == lastEntityContentBytes) {
                    // Byte-identical dynamic geometry: keep the existing BLAS.
                    entityBuild = currentEntityBuild;
                    oldEntityBuild = null;
                    me.cortex.vulkanite.audit.Diagnostics.onBlasReused();
                } else {
                    entityBuild = entityBlasBuilder.buildBlas(entityData, cmd, fence);
                    lastEntityContentHash = contentHash;
                    lastEntityContentBytes = entityData.vertices().remaining();
                }
            } else {
                entityBuild = null;
                lastEntityContentHash = 0;
                lastEntityContentBytes = -1;
            }
            currentEntityBuild = entityBuild;
            entityData = null;

            {
                // TODO: need to sync with respect to updates from gpu memory updates from
                // TLASBuildDataManager
                // OR SOMETHING CAUSE WITH MULTIPLE FRAMES GOING AT ONCE the gpu state of
                // TLASBuildDataManager needs to be synced with
                // the current build phase, and the gpu side needs to be updated accoringly and
                // synced correctly

                vkCmdPipelineBarrier(
                        cmd.buffer,
                        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                        VK_PIPELINE_STAGE_TRANSFER_BIT,
                        0,
                        VkMemoryBarrier.calloc(1, stack)
                                .sType$Default()
                                .srcAccessMask(0)
                                .dstAccessMask(
                                        VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_TRANSFER_READ_BIT),
                        null,
                        null);

                VkAccelerationStructureInstanceKHR extra = null;
                if (entityBuild != null) {
                    extra = VkAccelerationStructureInstanceKHR.calloc(stack);
                    extra.mask(~0)
                            .instanceCustomIndex(0)
                            .instanceShaderBindingTableRecordOffset(1)
                            .accelerationStructureReference(entityBuild.getLeft().deviceAddress);
                    extra.transform().matrix(new Matrix4x3f().getTransposed(stack.mallocFloat(12)));
                    buildDataManager.descUpdateJobs.add(
                            new TLASSectionManager.DescUpdateJob(
                                    0, 0, List.of(entityBuild.getRight())));
                    instances++;
                }
                buildDataManager.setGeometryUpdateMemory(fence, geometry, extra);
                instances += buildDataManager.sectionCount();

                vkCmdPipelineBarrier(
                        cmd.buffer,
                        VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                        0,
                        VkMemoryBarrier.calloc(1, stack)
                                .sType$Default()
                                .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                                .dstAccessMask(VK_ACCESS_SHADER_READ_BIT),
                        null,
                        null);
            }

            int[] instanceCounts = new int[] {instances};
            {
                geometry.sType$Default().geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR).flags(0);

                geometry.geometry().instances().sType$Default().arrayOfPointers(false);
            }

            boolean canUpdate = currentTLAS != null && currentTlasInstanceCount == instances;
            var buildInfo =
                    VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack)
                            .sType$Default()
                            .mode(
                                    canUpdate
                                            ? VK_BUILD_ACCELERATION_STRUCTURE_MODE_UPDATE_KHR
                                            : VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                            .type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                            .flags(
                                    VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR
                                            | VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR)
                            .pGeometries(
                                    VkAccelerationStructureGeometryKHR.create(
                                            geometry.address(), 1))
                            .geometryCount(1);

            VkAccelerationStructureBuildSizesInfoKHR buildSizesInfo =
                    VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();

            vkGetAccelerationStructureBuildSizesKHR(
                    context.device,
                    VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                    buildInfo.get(0), // The reason its a buffer is cause of pain and that
                    // vkCmdBuildAccelerationStructuresKHR requires a buffer of
                    // VkAccelerationStructureBuildGeometryInfoKHR
                    stack.ints(instanceCounts),
                    buildSizesInfo);

            if (!canUpdate && buildSizesInfo.accelerationStructureSize() <= 0)
                throw new IllegalStateException(
                        "Driver returned no storage for a top-level acceleration structure");
            VAccelerationStructure tlas =
                    canUpdate
                            ? currentTLAS
                            : context.memory.createAcceleration(
                                    buildSizesInfo.accelerationStructureSize(),
                                    256,
                                    VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR,
                                    VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR);

            long requiredScratch =
                    canUpdate
                            ? buildSizesInfo.updateScratchSize()
                            : buildSizesInfo.buildScratchSize();
            // A zero-primitive TLAS is legal, but VkBufferCreateInfo.size must be nonzero.
            long scratchAllocation = Math.max(requiredScratch, 256L);
            VBuffer retiredScratch = null;
            if (tlasScratch == null || tlasScratchSize < scratchAllocation) {
                retiredScratch = tlasScratch;
                tlasScratch =
                        context.memory.createBuffer(
                                scratchAllocation,
                                VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR
                                        | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                                256,
                                0);
                tlasScratchSize = scratchAllocation;
            }

            buildInfo
                    .dstAccelerationStructure(tlas.structure)
                    .srcAccelerationStructure(canUpdate ? currentTLAS.structure : 0)
                    .scratchData(
                            VkDeviceOrHostAddressKHR.calloc(stack)
                                    .deviceAddress(tlasScratch.deviceAddress()));

            var buildRanges =
                    VkAccelerationStructureBuildRangeInfoKHR.calloc(instanceCounts.length, stack);
            for (int count : instanceCounts) {
                buildRanges.get().primitiveCount(count);
            }
            buildRanges.rewind();

            vkCmdBuildAccelerationStructuresKHR(cmd.buffer, buildInfo, stack.pointers(buildRanges));

            cmd.end();

            int[] waitingStage = new int[blocking.length + 1];
            VSemaphore[] allBlocking = new VSemaphore[waitingStage.length];
            System.arraycopy(blocking, 0, allBlocking, 0, blocking.length);

            allBlocking[waitingStage.length - 1] = semIn;

            Arrays.fill(
                    waitingStage,
                    VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR
                            | VK_PIPELINE_STAGE_TRANSFER_BIT);
            context.cmd.submit(
                    queue,
                    new VCmdBuff[] {cmd},
                    allBlocking,
                    waitingStage,
                    new VSemaphore[] {semOut},
                    fence);

            VAccelerationStructure oldTLAS = currentTLAS;
            currentTLAS = tlas;
            currentTlasInstanceCount = instances;
            VBuffer capturedRetiredScratch = retiredScratch;

            List<VAccelerationStructure> capturedList = new ArrayList<>(structuresToRelease);
            structuresToRelease.clear();
            var retiredBuffers = new ArrayList<>(rejectedBuffers);
            rejectedBuffers.clear();
            List<Runnable> retirement = new ArrayList<>();
            for (var buffer : retiredBuffers) retirement.add(buffer::free);
            if (capturedRetiredScratch != null) retirement.add(capturedRetiredScratch::free);
            if (oldTLAS != null && oldTLAS != tlas) retirement.add(oldTLAS::free);
            if (oldEntityBuild != null) {
                retirement.add(oldEntityBuild.getLeft()::free);
                retirement.add(oldEntityBuild.getRight()::free);
            }
            retirement.add(cmd::enqueueFree);
            for (var as : capturedList) retirement.add(as::free);
            for (var sem : blocking) retirement.add(sem::free);
            retirement.add(fence::free);
            context.sync.addCallback(
                    fence,
                    () ->
                            me.cortex.vulkanite.lib.other.sync.FenceCallbacks.runAll(
                                    retirement.toArray(Runnable[]::new)));
        }
    }

    public VAccelerationStructure getTlas() {
        return currentTLAS;
    }

    // Manages entries in the VkAccelerationStructureInstanceKHR buffer, ment to
    // reuse as much as possible and be very efficient
    private class TLASGeometryManager {
        // Have a global buffer for VkAccelerationStructureInstanceKHR, then use
        // VkAccelerationStructureGeometryInstancesDataKHR.arrayOfPointers
        // Use LibCString.memmove to ensure streaming data is compact
        // Stream this to the gpu per frame (not ideal tbh, could implement a cache of
        // some kind)

        // Needs a gpu buffer for the instance data, this can be reused
        // private VkAccelerationStructureInstanceKHR.Buffer buffer;

        private VkAccelerationStructureInstanceKHR.Buffer instances =
                VkAccelerationStructureInstanceKHR.calloc(30000);
        private int[] instance2pointer = new int[30000];
        private int[] pointer2instance = new int[30000];
        private BitSet free =
                new BitSet(30000); // The reason this is needed is to give non used instance ids
        private int count;
        private final Deque<InstanceUpload> availableUploads = new ArrayDeque<>();
        private final List<InstanceUpload> instanceUploads = new ArrayList<>();

        private final class InstanceUpload {
            final VBuffer buffer;
            final long address;

            InstanceUpload(int capacity) {
                buffer =
                        context.memory.createBuffer(
                                capacity,
                                VK_BUFFER_USAGE_TRANSFER_DST_BIT
                                        | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
                                        | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                                VK_MEMORY_HEAP_DEVICE_LOCAL_BIT
                                        | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
                                0,
                                VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT);
                address = buffer.map();
            }
        }

        public TLASGeometryManager() {
            free.set(0, instance2pointer.length);
        }

        // TODO: make the instances buffer, gpu permenent then stream updates instead of
        // uploading per frame
        public void setGeometryUpdateMemory(
                VFence fence,
                VkAccelerationStructureGeometryKHR struct,
                VkAccelerationStructureInstanceKHR addin) {
            long size = (long) VkAccelerationStructureInstanceKHR.SIZEOF * count;
            int required =
                    Math.toIntExact(
                            Math.max(
                                    VkAccelerationStructureInstanceKHR.SIZEOF,
                                    size
                                            + (addin == null
                                                    ? 0
                                                    : VkAccelerationStructureInstanceKHR.SIZEOF)));
            InstanceUpload upload = acquireInstanceUpload(required);
            long ptr = upload.address;
            if (addin != null) {
                MemoryUtil.memCopy(addin.address(), ptr, VkAccelerationStructureInstanceKHR.SIZEOF);
                ptr += VkAccelerationStructureInstanceKHR.SIZEOF;
            }
            MemoryUtil.memCopy(this.instances.address(0), ptr, size);

            upload.buffer.flush(0, required);

            struct.geometry().instances().data().deviceAddress(upload.buffer.deviceAddress());

            context.sync.addCallback(fence, () -> availableUploads.addLast(upload));
        }

        private InstanceUpload acquireInstanceUpload(int required) {
            for (var iterator = availableUploads.iterator(); iterator.hasNext(); ) {
                var upload = iterator.next();
                if (upload.buffer.size() < required) continue;
                iterator.remove();
                return upload;
            }
            var upload = new InstanceUpload(roundUpPow2(required));
            instanceUploads.add(upload);
            return upload;
        }

        protected void closeInstanceUploads() {
            availableUploads.clear();
            for (var upload : instanceUploads) {
                upload.buffer.unmap();
                upload.buffer.free();
            }
            instanceUploads.clear();
        }

        public int sectionCount() {
            return count;
        }

        protected int alloc() {
            int id = free.nextSetBit(0);

            free.clear(id);

            // Update the map
            instance2pointer[id] = count;
            pointer2instance[count] = id;

            // Increment the count
            count++;

            return id;
        }

        protected void free(int id) {
            free.set(id);

            count--;
            if (instance2pointer[id] == count) {
                // We are at the end of the pointer list, so just decrement and be done
                instance2pointer[id] = -1;
                pointer2instance[count] = -1;
            } else {
                // TODO: CHECK THIS IS CORRECT

                // We need to remove the pointer, and fill it in with the last element in the
                // pointer array, updating the mapping of the moved
                int ptrId = instance2pointer[id];
                instance2pointer[id] = -1;

                // I feel like this should be pointer2instance = pointer2instance
                pointer2instance[ptrId] = pointer2instance[count];

                // move over the ending data to the missing hole point
                MemoryUtil.memCopy(
                        instances.address(count),
                        instances.address(ptrId),
                        VkAccelerationStructureInstanceKHR.SIZEOF);

                instance2pointer[pointer2instance[count]] = ptrId;
            }
        }

        protected void update(int id, VkAccelerationStructureInstanceKHR data) {
            MemoryUtil.memCopy(
                    data.address(),
                    instances.address(instance2pointer[id]),
                    VkAccelerationStructureInstanceKHR.SIZEOF);
        }
    }

    private static int roundUpPow2(int v) {
        v--;
        v |= v >> 1;
        v |= v >> 2;
        v |= v >> 4;
        v |= v >> 8;
        v |= v >> 16;
        v++;
        return v;
    }

    private final class TLASSectionManager extends TLASGeometryManager {
        private final TlasPointerArena arena = new TlasPointerArena(30000);

        public TLASSectionManager() {
            // Allocate index 0 to entity blas
            if (arena.allocate(1) != 0) {
                throw new IllegalStateException();
            }
        }

        private VDescriptorSetLayout geometryBufferSetLayout;
        private VDescriptorPool geometryBufferDescPool;
        private long geometryBufferDescSet = 0;

        private int setCapacity = 0;

        private record DescUpdateJob(int binding, int dstArrayElement, List<VBuffer> buffers) {}

        private record ArenaDeallocJob(int index, int count, List<VBuffer> geometryBuffers) {}

        private final ConcurrentLinkedDeque<DescUpdateJob> descUpdateJobs =
                new ConcurrentLinkedDeque<>();
        private final ConcurrentLinkedDeque<ArenaDeallocJob> arenaDeallocJobs =
                new ConcurrentLinkedDeque<>();
        private final Deque<VDescriptorPool> descPoolsToRelease = new ArrayDeque<>();

        public void resizeBindlessSet(int newSize, VFence fence) {
            if (geometryBufferSetLayout == null) {
                var layoutBuilder =
                        new DescriptorSetLayoutBuilder(
                                VK_DESCRIPTOR_SET_LAYOUT_CREATE_UPDATE_AFTER_BIND_POOL_BIT);
                layoutBuilder.binding(
                        0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 65536, VK_SHADER_STAGE_ALL);
                layoutBuilder.setBindingFlags(
                        0,
                        VK_DESCRIPTOR_BINDING_VARIABLE_DESCRIPTOR_COUNT_BIT
                                | VK_DESCRIPTOR_BINDING_UPDATE_UNUSED_WHILE_PENDING_BIT
                                | VK_DESCRIPTOR_BINDING_PARTIALLY_BOUND_BIT);
                geometryBufferSetLayout = layoutBuilder.build(context);
            }

            if (newSize > setCapacity) {
                int newCapacity = roundUpPow2(Math.max(newSize, 32));
                var newGeometryBufferDescPool =
                        new VDescriptorPool(
                                context,
                                VK_DESCRIPTOR_POOL_CREATE_UPDATE_AFTER_BIND_BIT,
                                1,
                                newCapacity,
                                geometryBufferSetLayout.types);
                newGeometryBufferDescPool.allocateSets(
                        geometryBufferSetLayout, new int[] {newCapacity});
                long newGeometryBufferDescSet = newGeometryBufferDescPool.get(0);

                if (geometryBufferDescSet != 0) {
                    try (var stack = stackPush()) {
                        var setCopy = VkCopyDescriptorSet.calloc(1, stack);
                        setCopy.get(0)
                                .sType$Default()
                                .srcSet(geometryBufferDescSet)
                                .dstSet(newGeometryBufferDescSet)
                                .descriptorCount(setCapacity);
                        vkUpdateDescriptorSets(context.device, null, setCopy);
                    }

                    descPoolsToRelease.add(geometryBufferDescPool);
                }

                geometryBufferDescPool = newGeometryBufferDescPool;
                geometryBufferDescSet = newGeometryBufferDescSet;
                setCapacity = newCapacity;
            }
        }

        @Override
        public void setGeometryUpdateMemory(
                VFence fence,
                VkAccelerationStructureGeometryKHR struct,
                VkAccelerationStructureInstanceKHR addin) {
            super.setGeometryUpdateMemory(fence, struct, addin);
            resizeBindlessSet(arena.maxIndex, fence);

            if (descUpdateJobs.isEmpty()) {
                return;
            }

            try (var dub = new DescriptorUpdateBuilder(context, descUpdateJobs.size())) {
                dub.set(geometryBufferDescSet);
                while (!descUpdateJobs.isEmpty()) {
                    var job = descUpdateJobs.poll();
                    dub.buffer(job.binding, job.dstArrayElement, job.buffers);
                }
                dub.apply();
            }

            // Queue up the arena dealloc jobs to be done after the fence is done
            Vulkanite.getInstance()
                    .addSyncedCallback(
                            () -> {
                                fenceTick();
                            });
        }

        private static final class Holder {
            final int id;
            int geometryIndex = -1;
            List<VBuffer> geometryBuffers;

            final SectionHandle section;
            VAccelerationStructure structure;

            private Holder(int id, SectionHandle section) {
                this.id = id;
                this.section = section;
            }
        }

        Map<SectionHandle.Origin, Holder> tmp = new HashMap<>();

        public void fenceTick() {
            while (!arenaDeallocJobs.isEmpty()) {
                var job = arenaDeallocJobs.poll();
                arena.free(job.index, job.count);
                job.geometryBuffers.forEach(buffer -> buffer.free());
            }
            while (!descPoolsToRelease.isEmpty()) {
                descPoolsToRelease.poll().free();
            }
        }

        public void update(AccelerationBlasBuilder.BLASBuildResult result) {
            var data = result.data();
            if (data.section().isDisposed()) {
                // The next TLAS submission waits for this batch's compaction semaphore.
                structuresToRelease.add(result.structure());
                rejectedBuffers.addAll(data.geometryBuffers());
                return;
            }
            var holder = tmp.get(data.section().origin());
            if (holder != null && holder.section != data.section()) {
                remove(holder.section);
                holder = null;
            }
            if (holder == null) {
                holder = new Holder(alloc(), data.section());
                tmp.put(data.section().origin(), holder);
            }
            if (holder.structure != null) {
                structuresToRelease.add(holder.structure);
            }
            holder.structure = result.structure();

            if (holder.geometryIndex != -1) {
                arenaDeallocJobs.add(
                        new ArenaDeallocJob(
                                holder.geometryIndex,
                                holder.geometryBuffers.size(),
                                holder.geometryBuffers));
            }
            holder.geometryBuffers = data.geometryBuffers();
            holder.geometryIndex = arena.allocate(holder.geometryBuffers.size());

            descUpdateJobs.add(new DescUpdateJob(0, holder.geometryIndex, holder.geometryBuffers));

            try (var stack = stackPush()) {
                var asi =
                        VkAccelerationStructureInstanceKHR.calloc(stack)
                                .mask(~0)
                                .instanceCustomIndex(holder.geometryIndex)
                                .accelerationStructureReference(holder.structure.deviceAddress);
                asi.transform()
                        .matrix(
                                new Matrix4x3f()
                                        .translate(
                                                holder.section.origin().x(),
                                                holder.section.origin().y(),
                                                holder.section.origin().z())
                                        .getTransposed(stack.mallocFloat(12)));
                update(holder.id, asi);
            }
        }

        public boolean remove(SectionHandle section) {
            var holder = tmp.get(section.origin());
            if (holder == null || holder.section != section) return false;
            tmp.remove(section.origin());

            structuresToRelease.add(holder.structure);

            free(holder.id);

            for (var job : descUpdateJobs) {
                if (job.buffers == holder.geometryBuffers) {
                    descUpdateJobs.remove(job);
                }
            }

            if (holder.geometryIndex != -1) {
                arenaDeallocJobs.add(
                        new ArenaDeallocJob(
                                holder.geometryIndex,
                                holder.geometryBuffers.size(),
                                holder.geometryBuffers));
            }
            return true;
        }
    }

    private static final class TlasPointerArena {
        private final BitSet vacant;
        public int maxIndex = 0;

        private TlasPointerArena(int size) {
            size *= 3;
            vacant = new BitSet(size);
            vacant.set(0, size);
        }

        public int allocate(int count) {
            int pos = vacant.nextSetBit(0);
            outer:
            while (pos != -1) {
                for (int offset = 1; offset < count; offset++) {
                    if (!vacant.get(offset + pos)) {
                        pos = vacant.nextSetBit(offset + pos + 1);
                        continue outer;
                    }
                }
                break;
            }
            if (pos == -1) {
                throw new IllegalStateException();
            }
            vacant.clear(pos, pos + count);
            maxIndex = Math.max(maxIndex, pos + count);
            return pos;
        }

        public void free(int pos, int count) {
            vacant.set(pos, pos + count);

            maxIndex = vacant.previousClearBit(maxIndex) + 1;
        }
    }

    public long getGeometrySet() {
        return buildDataManager.geometryBufferDescSet;
    }

    /** Read-only instance count for diagnostics. */
    public int sectionCount() {
        return buildDataManager.sectionCount();
    }

    public VDescriptorSetLayout getGeometryLayout() {
        return buildDataManager.geometryBufferSetLayout;
    }

    // Called for cleaning up any remaining loose resources
    void cleanupTick() {
        rejectedBuffers.forEach(VBuffer::free);
        rejectedBuffers.clear();
        releaseEntities();
        entityData = null;
        singleUsePool.doReleases();
        structuresToRelease.forEach(VAccelerationStructure::free);
        structuresToRelease.clear();
        buildDataManager.fenceTick();
        buildDataManager.descUpdateJobs.clear();
        buildDataManager.closeInstanceUploads();
        if (currentTLAS != null) {
            currentTLAS.free();
            currentTLAS = null;
        }
        currentTlasInstanceCount = -1;
        if (tlasScratch != null) {
            tlasScratch.free();
            tlasScratch = null;
            tlasScratchSize = 0;
        }
        if (buildDataManager.sectionCount() != 0) {
            throw new IllegalStateException("Sections are not empty on cleanup");
        }
    }
}
