package me.cortex.vulkanite.compat;

import me.cortex.vulkanite.lib.memory.VGImage;
import java.nio.ByteBuffer;
import java.util.List;
import org.lwjgl.system.MemoryUtil;

/** Entity ABI: first 32 bytes unchanged; previous world position f32x3 and flags u32. */
public record EntityFrame(ByteBuffer vertices, List<VGImage> textures) implements AutoCloseable {
    public static final int HISTORY_VALID = 1;
    public static final int CAMERA_HIDDEN = 2;
    public static final int VIEW_MODEL = 4;
    public static final int ALPHA_COVERAGE = 8;
    public static final int PARTICLE = 16;
    public static final int STRIDE = 48;
    public static final int MAX_TEXTURES = 256;
    public int quadCount() { return vertices.remaining() / (STRIDE * 4); }
    @Override public void close() { MemoryUtil.memFree(vertices); }
}
