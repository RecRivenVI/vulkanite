package me.cortex.vulkanite.lib.other;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;

import me.cortex.vulkanite.audit.Diagnostics;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;

/**
 * Dev-only GPU timestamp rings. Writes are recorded in-flight; results are published asynchronously
 * when the frame fence completes (no extra stalls).
 */
public final class GpuTimestamps {
    public static final int SLOT_RAY = 0;
    public static final int SLOT_TLAS = 1;
    public static final int SLOT_DYN_BLAS = 2;
    public static final int SLOT_TERRAIN_BLAS = 3;
    public static final int SLOT_COUNT = 4;

    private static final int RING = 4;
    private static final int QUERIES = SLOT_COUNT * 2 * RING; // begin/end per slot

    private static VQueryPool pool;
    private static final boolean[] slotArmed = new boolean[SLOT_COUNT];
    private static int ring;

    private GpuTimestamps() {}

    public static synchronized void ensure(VkDevice device) {
        if (pool == null) {
            pool = new VQueryPool(device, QUERIES, VK_QUERY_TYPE_TIMESTAMP);
        }
    }

    public static synchronized void beginFrame(VkCommandBuffer cmd) {
        if (pool == null) return;
        java.util.Arrays.fill(slotArmed, false);
        int base = ring * SLOT_COUNT * 2;
        vkCmdResetQueryPool(cmd, pool.pool, base, SLOT_COUNT * 2);
    }

    public static synchronized void write(VkCommandBuffer cmd, int slot, boolean isEnd) {
        if (pool == null || slot < 0 || slot >= SLOT_COUNT) return;
        if (isEnd && !slotArmed[slot]) return;
        int q = ring * SLOT_COUNT * 2 + slot * 2 + (isEnd ? 1 : 0);
        vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, pool.pool, q);
        if (!isEnd) slotArmed[slot] = true;
    }

    /** Called from fence callback; consumes this ring's results. */
    public static synchronized void completeFrame() {
        if (pool == null) return;
        try {
            int base = ring * SLOT_COUNT * 2;
            long[] ts = pool.getResultsLong(base, SLOT_COUNT * 2, 0);
            // timestamp period converted by caller via device limits — store raw ticks; Diagnostics
            // holds ticks
            long[] span = new long[SLOT_COUNT];
            for (int i = 0; i < SLOT_COUNT; i++) {
                long a = ts[i * 2];
                long b = ts[i * 2 + 1];
                span[i] = (b > a) ? (b - a) : 0;
            }
            Diagnostics.onGpuTimestamps(span);
        } catch (Throwable ignored) {
            // incomplete queries — skip this frame's GPU spans
        }
        ring = (ring + 1) % RING;
    }

    public static synchronized void free() {
        if (pool != null) {
            pool.free();
            pool = null;
        }
    }
}
