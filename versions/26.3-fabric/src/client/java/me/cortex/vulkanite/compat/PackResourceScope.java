package me.cortex.vulkanite.compat;

/** Render-thread allocation scope. Always restored, including failed and nested construction. */
public final class PackResourceScope implements AutoCloseable {
    private static final ThreadLocal<PackCapabilities> CURRENT =
            ThreadLocal.withInitial(() -> PackCapabilities.OPENGL);
    private final PackCapabilities previous;

    private PackResourceScope(PackCapabilities capabilities) {
        previous = CURRENT.get();
        CURRENT.set(capabilities);
    }

    public static PackResourceScope enter(PackCapabilities capabilities) {
        return new PackResourceScope(capabilities);
    }

    public static PackCapabilities current() {
        return CURRENT.get();
    }

    @Override
    public void close() {
        CURRENT.set(previous);
    }
}
