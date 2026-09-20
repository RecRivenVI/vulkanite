package me.cortex.vulkanite.acceleration;

/** One producer-owned section lifetime. Equal coordinates do not imply equal identity. */
public final class SectionHandle {
    public record Origin(int x, int y, int z) {}

    private final Origin origin;
    private volatile boolean disposed;

    public SectionHandle(int originX, int originY, int originZ) {
        origin = new Origin(originX, originY, originZ);
    }

    public Origin origin() {
        return origin;
    }

    public boolean isDisposed() {
        return disposed;
    }

    public void dispose() {
        disposed = true;
    }
}
