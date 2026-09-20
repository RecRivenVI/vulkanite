package me.cortex.vulkanite.compat;

import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;

/** Render-thread owned bytes; never borrows the assembler's next-frame scratch storage. */
public final class EntityGeometrySnapshot implements AutoCloseable {
    private ByteBuffer previous;
    private int length = -1;

    public boolean matches(ByteBuffer input) {
        return previous != null && length == input.remaining() && previous.mismatch(input) == -1;
    }

    public void capture(ByteBuffer input) {
        int required = input.remaining();
        if (previous == null || previous.capacity() < required) {
            var replacement = MemoryUtil.memAlloc(Math.max(1, required));
            if (previous != null) MemoryUtil.memFree(previous);
            previous = replacement;
        }
        length = -1;
        previous.clear().put(input.duplicate()).flip();
        length = required;
    }

    public void clear() {
        length = -1;
    }

    @Override
    public void close() {
        if (previous != null) MemoryUtil.memFree(previous);
        previous = null;
        length = -1;
    }
}
