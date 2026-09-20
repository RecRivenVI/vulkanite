package me.cortex.vulkanite.client.rendering;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import me.cortex.vulkanite.lib.other.sync.VSemaphore;

/** Render-thread pool for reusable binary semaphores whose leases retire on frame fences. */
final class SemaphoreRing<T extends VSemaphore> implements AutoCloseable {
    private final Supplier<T> factory;
    private final List<Entry<T>> entries = new ArrayList<>();

    SemaphoreRing(Supplier<T> factory) {
        this.factory = factory;
    }

    Lease<T> acquire() {
        for (var entry : entries) {
            if (!entry.available) continue;
            entry.available = false;
            return new Lease<>(entry);
        }
        var entry = new Entry<>(factory.get());
        entry.available = false;
        entries.add(entry);
        return new Lease<>(entry);
    }

    int size() {
        return entries.size();
    }

    @Override
    public void close() {
        entries.forEach(
                entry -> {
                    if (!entry.semaphore.isFreed()) entry.semaphore.free();
                });
        entries.clear();
    }

    private static final class Entry<T extends VSemaphore> {
        final T semaphore;
        boolean available = true;

        Entry(T semaphore) {
            this.semaphore = semaphore;
        }
    }

    static final class Lease<T extends VSemaphore> {
        private Entry<T> entry;

        private Lease(Entry<T> entry) {
            this.entry = entry;
        }

        T semaphore() {
            if (entry == null) throw new IllegalStateException("Semaphore lease already completed");
            return entry.semaphore;
        }

        void complete() {
            if (entry == null) return;
            entry.available = true;
            entry = null;
        }

        /** The failed frame may discard a semaphore after both GPU queues are idle. */
        void discardAfterIdle() {
            if (entry == null) return;
            entry.available = false;
            if (!entry.semaphore.isFreed()) entry.semaphore.free();
            entry = null;
        }
    }
}
