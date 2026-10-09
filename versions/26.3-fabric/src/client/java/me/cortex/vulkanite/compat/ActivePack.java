package me.cortex.vulkanite.compat;

/**
 * Published to Sodium workers; a pipeline transition invalidates only capture jobs, not temporal
 * history.
 */
public final class ActivePack {
    public record State(PackCapabilities capabilities, long generation) {}

    private static volatile State state = new State(PackCapabilities.OPENGL, 0);
    private static Object owner;

    public static State state() {
        return state;
    }

    public static void select(Object pipeline) {
        if (owner == pipeline) return;
        owner = pipeline;
        PackCapabilities capabilities =
                pipeline instanceof VulkanitePipelineAccess access
                        ? access.vulkanite$capabilities()
                        : PackCapabilities.OPENGL;
        state = new State(capabilities, state.generation() + 1);
    }
}
