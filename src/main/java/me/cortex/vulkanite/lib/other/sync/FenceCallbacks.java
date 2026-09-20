package me.cortex.vulkanite.lib.other.sync;

/** Execute independent fence-retirement actions once, even when one action fails. */
public final class FenceCallbacks {
    private FenceCallbacks() {}

    public static void runAll(Runnable... actions) {
        Throwable failure = null;
        for (var action : actions) try { action.run(); }
        catch (Throwable error) {
            if (failure == null) failure = error;
            else if (failure != error) failure.addSuppressed(error);
        }
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure != null) throw new IllegalStateException("Fence retirement failed", failure);
    }
}
