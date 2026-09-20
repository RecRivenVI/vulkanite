package me.cortex.vulkanite.lib.base;

import java.util.concurrent.atomic.AtomicBoolean;

public abstract class TrackedResourceObject {

    private final AtomicBoolean freed = new AtomicBoolean();

    protected void free0() {
        if (!freed.compareAndSet(false, true))
            throw new IllegalStateException("Double release: " + getClass().getName());
    }

    public abstract void free();

    public boolean isFreed() {
        return freed.get();
    }
}
