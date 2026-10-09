package me.cortex.vulkanite.client.rendering;

import static org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.*;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.memory.VBuffer;

/** Persistently mapped uniform buffers retired by the frame submission fence. */
final class FrameUniformRing implements AutoCloseable {
    private static final int SLOT_COUNT = 8;
    private final VContext context;
    private final Slot[] slots = new Slot[SLOT_COUNT];
    private final Set<Lease> active = Collections.newSetFromMap(new IdentityHashMap<>());
    private int next;
    private boolean closed;
    private boolean closing;

    FrameUniformRing(VContext context) {
        this.context = context;
        try {
            for (int i = 0; i < slots.length; i++) {
                VBuffer buffer = createBuffer();
                try {
                    slots[i] = new Slot(buffer, buffer.map());
                } catch (Throwable mappingFailure) {
                    buffer.free();
                    throw mappingFailure;
                }
            }
        } catch (Throwable failure) {
            for (Slot slot : slots)
                if (slot != null && !slot.buffer.isFreed()) {
                    try {
                        slot.buffer.unmap();
                        slot.buffer.free();
                    } catch (Throwable releaseFailure) {
                        failure.addSuppressed(releaseFailure);
                    }
                }
            throw failure;
        }
    }

    Lease acquire() {
        if (closing) throw new IllegalStateException("Frame uniform ring is closing");
        for (int attempt = 0; attempt < slots.length; attempt++) {
            int index = (next + attempt) % slots.length;
            Slot slot = slots[index];
            if (!slot.available) continue;
            slot.available = false;
            next = (index + 1) % slots.length;
            var lease = new Lease(slot.buffer, slot.address, slot);
            active.add(lease);
            return lease;
        }
        // Do not reintroduce a CPU wait when the GPU is more than eight frames behind.
        // The exceptional spill buffer is retired by the same submission fence.
        VBuffer buffer = createBuffer();
        final long address;
        try {
            address = buffer.map();
        } catch (Throwable mappingFailure) {
            buffer.free();
            throw mappingFailure;
        }
        var lease = new Lease(buffer, address, null);
        active.add(lease);
        return lease;
    }

    private VBuffer createBuffer() {
        return context.memory.createBuffer(
                FrameAbi.BUFFER_SIZE,
                VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
                0,
                VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT);
    }

    @Override
    public void close() {
        if (closed) return;
        closing = true;
        // The caller has already waited for device idle. This also retires spill
        // leases that never reached a submission callback after a failed frame.
        Throwable failure = null;
        for (var lease : active.toArray(Lease[]::new))
            try {
                lease.releaseAfterIdle();
            } catch (Throwable releaseFailure) {
                if (failure == null) failure = releaseFailure;
                else failure.addSuppressed(releaseFailure);
            }
        for (Slot slot : slots) {
            if (!slot.buffer.isFreed()) {
                try {
                    slot.buffer.unmap();
                    slot.buffer.free();
                } catch (Throwable releaseFailure) {
                    if (failure == null) failure = releaseFailure;
                    else failure.addSuppressed(releaseFailure);
                }
            }
        }
        closed = active.isEmpty() && failure == null;
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure != null)
            throw new IllegalStateException("Frame uniform ring close failed", failure);
    }

    final class Lease {
        private final VBuffer buffer;
        private final long address;
        private final Slot slot;
        private State state = State.ACQUIRED;
        private boolean unmapped;
        private Throwable releaseFailure;

        private enum State {
            ACQUIRED,
            SUBMITTED,
            RELEASED
        }

        private Lease(VBuffer buffer, long address, Slot slot) {
            this.buffer = buffer;
            this.address = address;
            this.slot = slot;
        }

        VBuffer buffer() {
            return buffer;
        }

        long address() {
            return address;
        }

        void flush() {
            buffer.flush();
        }

        void markSubmitted() {
            if (state != State.ACQUIRED)
                throw new IllegalStateException("Frame uniform lease is not acquired");
            state = State.SUBMITTED;
        }

        void abortBeforeSubmit() {
            if (state != State.ACQUIRED)
                throw new IllegalStateException("Submitted frame uniform lease cannot be aborted");
            releaseAfterIdle();
        }

        void complete() {
            if (state == State.RELEASED && closed) return;
            if (state != State.SUBMITTED)
                throw new IllegalStateException("Frame uniform lease was not submitted");
            releaseAfterIdle();
        }

        private void releaseAfterIdle() {
            if (state == State.RELEASED) return;
            if (slot != null) slot.available = true;
            else {
                if (releaseFailure != null && buffer.isFreed())
                    throw new IllegalStateException(
                            "Spill buffer release failed after its handle was marked freed",
                            releaseFailure);
                Throwable failure = null;
                if (!unmapped && !buffer.isFreed())
                    try {
                        buffer.unmap();
                        unmapped = true;
                    } catch (Throwable unmapFailure) {
                        failure = unmapFailure;
                    }
                if (!buffer.isFreed())
                    try {
                        buffer.free();
                    } catch (Throwable freeFailure) {
                        if (failure == null) failure = freeFailure;
                        else failure.addSuppressed(freeFailure);
                    }
                if (failure != null) {
                    releaseFailure = failure;
                    if (failure instanceof Error error) throw error;
                    if (failure instanceof RuntimeException runtime) throw runtime;
                    throw new IllegalStateException("Spill lease release failed", failure);
                }
            }
            state = State.RELEASED;
            active.remove(this);
        }
    }

    private static final class Slot {
        private final VBuffer buffer;
        private final long address;
        private boolean available = true;

        private Slot(VBuffer buffer, long address) {
            this.buffer = buffer;
            this.address = address;
        }
    }
}
