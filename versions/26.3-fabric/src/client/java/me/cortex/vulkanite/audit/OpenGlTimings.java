package me.cortex.vulkanite.audit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL33C;
import org.slf4j.LoggerFactory;

/** Render-thread query leases. Handoff includes Vulkan work, not just API overhead. */
public final class OpenGlTimings implements AutoCloseable {
    private static final int MAX_LEASES = 8;

    interface Driver {
        int counterBits();

        int[] create();

        void timestamp(int query);

        boolean available(int query);

        long result(int query);

        void delete(int[] queries);
    }

    private static final class OpenGlDriver implements Driver {
        public int counterBits() {
            return GL15C.glGetQueryi(GL33C.GL_TIMESTAMP, GL15C.GL_QUERY_COUNTER_BITS);
        }

        public int[] create() {
            int[] ids = new int[3];
            GL15C.glGenQueries(ids);
            return ids;
        }

        public void timestamp(int query) {
            GL33C.glQueryCounter(query, GL33C.GL_TIMESTAMP);
        }

        public boolean available(int query) {
            return GL15C.glGetQueryObjecti(query, GL15C.GL_QUERY_RESULT_AVAILABLE) != 0;
        }

        public long result(int query) {
            return GL33C.glGetQueryObjectui64(query, GL15C.GL_QUERY_RESULT);
        }

        public void delete(int[] queries) {
            GL15C.glDeleteQueries(queries);
        }
    }

    private static final class Lease {
        final int[] queries;
        final long epoch;
        int last = -1;

        Lease(int[] queries, long epoch) {
            this.queries = queries;
            this.epoch = epoch;
        }
    }

    private final Driver driver;
    private final ArrayList<int[]> owned = new ArrayList<>();
    private final ArrayDeque<int[]> available = new ArrayDeque<>();
    private final ArrayDeque<Lease> pending = new ArrayDeque<>();
    private Lease active;
    private long epoch = -1;
    private int countdown;
    private boolean disabled;
    private int counterBits = -1;

    public OpenGlTimings() {
        this(new OpenGlDriver());
    }

    OpenGlTimings(Driver driver) {
        this.driver = driver;
    }

    public void beginScene() {
        if (disabled || (Diagnostics.gpuEpoch() < 0 && active == null && pending.isEmpty())) return;
        try {
            if (active != null) {
                pending.addLast(active);
                active = null;
            }
            collect();
            long current = Diagnostics.gpuEpoch();
            if (current < 0) return;
            if (counterBits < 0) {
                counterBits = driver.counterBits();
                if (counterBits <= 0 || counterBits > Long.SIZE)
                    throw new IllegalStateException("OpenGL does not provide usable timestamps");
            }
            if (epoch != current) {
                epoch = current;
                countdown = 0;
            }
            if (countdown-- > 0) return;
            countdown = Diagnostics.gpuInterval() - 1;
            int[] queries = available.pollFirst();
            if (queries == null) {
                if (owned.size() >= MAX_LEASES) {
                    Diagnostics.onGpuSampleSkipped(current);
                    return;
                }
                queries = driver.create();
                owned.add(queries);
            }
            active = new Lease(queries, current);
            driver.timestamp(queries[0]);
            active.last = 0;
        } catch (RuntimeException failure) {
            disable(failure);
        }
    }

    public void beginHandoff() {
        if (disabled || active == null) return;
        try {
            driver.timestamp(active.queries[1]);
            active.last = 1;
        } catch (RuntimeException failure) {
            disable(failure);
        }
    }

    public void endHandoff() {
        if (disabled || active == null || active.last != 1) return;
        try {
            driver.timestamp(active.queries[2]);
            active.last = 2;
            pending.addLast(active);
            active = null;
        } catch (RuntimeException failure) {
            disable(failure);
        }
    }

    private void collect() {
        for (var iterator = pending.iterator(); iterator.hasNext(); ) {
            var lease = iterator.next();
            // ARB_timer_query: availability of a timestamp implies all earlier timestamps
            // on this context are available. Never request a result before that condition.
            if (!driver.available(lease.queries[lease.last])) continue;
            if (lease.epoch == Diagnostics.gpuEpoch() && lease.last == 2) {
                long start = driver.result(lease.queries[0]);
                long boundary = driver.result(lease.queries[1]);
                long end = driver.result(lease.queries[2]);
                long scene =
                        me.cortex.vulkanite.lib.other.GpuTimestamps.elapsedTicks(
                                start, boundary, counterBits);
                long handoff =
                        me.cortex.vulkanite.lib.other.GpuTimestamps.elapsedTicks(
                                boundary, end, counterBits);
                Diagnostics.onGpuTimestamp(lease.epoch, 4, scene, scene);
                Diagnostics.onGpuTimestamp(lease.epoch, 5, handoff, handoff);
            }
            iterator.remove();
            available.addLast(lease.queries);
        }
    }

    private void disable(RuntimeException failure) {
        Diagnostics.onGpuQuerySkipped(Diagnostics.gpuEpoch(), false);
        disabled = true;
        LoggerFactory.getLogger("Vulkanite/Profiling").warn("OpenGL timing disabled", failure);
    }

    /** Owner has completed GL work before releasing this pipeline's query objects. */
    @Override
    public void close() {
        owned.forEach(driver::delete);
        owned.clear();
        available.clear();
        pending.clear();
        active = null;
        disabled = true;
    }
}
