package me.cortex.vulkanite.client.rendering;

import java.util.Objects;
import me.cortex.vulkanite.lib.memory.VGBuffer;

/** A pack-produced storage buffer made visible to the Vulkan frame. */
public record SharedShaderBuffer(int binding, VGBuffer buffer) {
    public SharedShaderBuffer {
        if (binding < 0) throw new IllegalArgumentException("Negative shader buffer binding");
        Objects.requireNonNull(buffer, "buffer");
    }
}
