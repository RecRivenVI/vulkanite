package me.cortex.vulkanite.lib.other;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import me.cortex.vulkanite.audit.Diagnostics;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.slf4j.LoggerFactory;

/** Query leases owned by one command pool and recycled only after its commands retire. */
public final class GpuTimestamps {
    public static final int SLOT_RAY = 0;
    public static final int SLOT_TLAS = 1;
    public static final int SLOT_DYN_BLAS = 2;
    public static final int SLOT_TERRAIN_BLAS = 3;
    public static final int SLOT_COUNT = 4;
    private static final int MAX_RECORDINGS = 8;

    private final VkDevice device;
    private final ArrayList<VQueryPool> owned = new ArrayList<>();
    private final ArrayDeque<VQueryPool> available = new ArrayDeque<>();
    private final int validBits;
    private final float period;
    private long epoch = -1;
    private int countdown;
    private boolean disabled;

    public GpuTimestamps(VkDevice device, int family) {
        this.device = device;
        try (var stack = stackPush()) {
            var physical = device.getPhysicalDevice();
            var properties = VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(physical, properties);
            period = properties.limits().timestampPeriod();
            var count = stack.ints(0);
            vkGetPhysicalDeviceQueueFamilyProperties(physical, count, null);
            var families = VkQueueFamilyProperties.malloc(count.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(physical, count, families);
            validBits = families.get(family).timestampValidBits();
        }
        if (validBits == 0 || period <= 0) disable("Queue does not support timestamps");
    }

    /**
     * Caller serializes access with the owning command pool. No query pools exist when disabled.
     */
    public Recording acquire() {
        long currentEpoch = Diagnostics.gpuEpoch();
        if (disabled || currentEpoch < 0) return null;
        if (epoch != currentEpoch) {
            epoch = currentEpoch;
            countdown = 0;
        }
        if (countdown-- > 0) return null;
        countdown = Diagnostics.gpuInterval() - 1;
        var pool = available.pollFirst();
        if (pool == null) {
            if (owned.size() == MAX_RECORDINGS) {
                Diagnostics.onGpuSampleSkipped(epoch);
                return null;
            }
            try {
                pool = new VQueryPool(device, SLOT_COUNT * 2, VK_QUERY_TYPE_TIMESTAMP);
                owned.add(pool);
            } catch (RuntimeException | AssertionError failure) {
                Diagnostics.onGpuQuerySkipped(epoch, false);
                disable("Cannot allocate timestamp queries: " + failure.getMessage());
                return null;
            }
        }
        return new Recording(pool, currentEpoch);
    }

    private void disable(String reason) {
        if (!disabled) LoggerFactory.getLogger("Vulkanite/Profiling").warn("{}", reason);
        disabled = true;
    }

    /** Called only after the command pool itself has been destroyed by its GPU-safe owner. */
    public void free() {
        owned.forEach(VQueryPool::free);
        owned.clear();
        available.clear();
    }

    public static long elapsedTicks(long start, long end, int bits) {
        if (bits <= 0 || bits > Long.SIZE) throw new IllegalArgumentException("timestamp bits");
        long delta = end - start;
        return bits == Long.SIZE ? Math.max(0, delta) : delta & (-1L >>> (Long.SIZE - bits));
    }

    public final class Recording {
        private final VQueryPool query;
        private final long sampleEpoch;
        private int started;
        private int finished;

        private Recording(VQueryPool query, long sampleEpoch) {
            this.query = query;
            this.sampleEpoch = sampleEpoch;
        }

        public void begin(VkCommandBuffer command, int slot) {
            if (slot < 0 || slot >= SLOT_COUNT || (started & (1 << slot)) != 0) return;
            if (started == 0) vkCmdResetQueryPool(command, query.pool, 0, SLOT_COUNT * 2);
            vkCmdWriteTimestamp(command, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, query.pool, slot * 2);
            started |= 1 << slot;
        }

        public void end(VkCommandBuffer command, int slot) {
            if (slot < 0
                    || slot >= SLOT_COUNT
                    || (started & (1 << slot)) == 0
                    || (finished & (1 << slot)) != 0) return;
            vkCmdWriteTimestamp(
                    command, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, query.pool, slot * 2 + 1);
            finished |= 1 << slot;
        }

        /** No WAIT flag: diagnostics may skip a result, but may never stall the rendering queue. */
        public void retire(boolean submitted) {
            try {
                if (!submitted || disabled || sampleEpoch != Diagnostics.gpuEpoch()) return;
                try (var stack = stackPush()) {
                    var result = stack.mallocLong(2);
                    for (int slot = 0; slot < SLOT_COUNT; slot++) {
                        if ((finished & (1 << slot)) == 0) continue;
                        int status =
                                vkGetQueryPoolResults(
                                        device,
                                        query.pool,
                                        slot * 2,
                                        2,
                                        result,
                                        Long.BYTES,
                                        VK_QUERY_RESULT_64_BIT);
                        if (status == VK_SUCCESS) {
                            long ticks = elapsedTicks(result.get(0), result.get(1), validBits);
                            Diagnostics.onGpuTimestamp(
                                    sampleEpoch, slot, ticks, Math.round(ticks * (double) period));
                        } else {
                            Diagnostics.onGpuQuerySkipped(sampleEpoch, status == VK_NOT_READY);
                            if (status != VK_NOT_READY) {
                                disable("Timestamp query failed: " + status);
                                break;
                            }
                        }
                    }
                }
            } finally {
                available.addLast(query);
            }
        }
    }
}
