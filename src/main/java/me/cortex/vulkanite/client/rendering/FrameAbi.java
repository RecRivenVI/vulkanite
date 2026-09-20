package me.cortex.vulkanite.client.rendering;

import java.nio.ByteBuffer;

/**
 * Minimal typed camera input for Vulkan dispatch. Shader-pack uniforms and
 * environment values belong to the pack's own Iris-produced storage buffers.
 */
public final class FrameAbi {
    public static final int VERSION = 3;
    public static final int BUFFER_SIZE = 160;
    public static final int METADATA_OFFSET = 144;

    public static final long CAMERA = 1L;
    public static final int SOURCE_HOST_ADAPTER = 0;

    private FrameAbi() {}

    public static void writeMetadata(ByteBuffer buffer, long fields, int source) {
        buffer.putInt(METADATA_OFFSET, VERSION);
        buffer.putInt(METADATA_OFFSET + 4, (int) fields);
        buffer.putInt(METADATA_OFFSET + 8, (int) (fields >>> 32));
        buffer.putInt(METADATA_OFFSET + 12, source);
    }
}
