package me.cortex.vulkanite.compat;

import me.cortex.vulkanite.lib.memory.VGImage;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import org.lwjgl.system.MemoryUtil;

/** Owned or borrowed neutral triangle vertices and their sampled textures. */
public record EntityFrame(ByteBuffer vertices, List<MaterialTextures> textures, boolean ownsVertices) implements AutoCloseable {
    public enum Address { REPEAT, CLAMP_TO_EDGE }
    public enum Filter { NEAREST, LINEAR }
    /** Immutable RenderPearl sampler inputs, detached from the source sampler's lifetime. */
    public record SamplerState(Address addressU, Address addressV, Filter minFilter,
                               Filter magFilter, int maxAnisotropy, float maxLod) {
        public SamplerState {
            Objects.requireNonNull(addressU); Objects.requireNonNull(addressV);
            Objects.requireNonNull(minFilter); Objects.requireNonNull(magFilter);
            if (maxAnisotropy < 1 || !Float.isFinite(maxLod) || maxLod < 0)
                throw new IllegalArgumentException("Invalid material sampler state");
        }
    }
    public record MaterialTextures(VGImage albedo, VGImage normal, VGImage specular,
                                   SamplerState sampler) {
        public MaterialTextures { Objects.requireNonNull(albedo); Objects.requireNonNull(sampler); }
    }
    public static final int CAMERA_HIDDEN = 1;
    public static final int VIEW_MODEL = 2;
    public static final int ALPHA_CUTOUT = 4;
    public static final int PARTICLE = 8;
    public static final int TWO_SIDED = 16;
    public static final int ALPHA_BLEND = 32;
    public static final int STRIDE = 112;
    public static final int UV1_PRESENT = 1;
    public static final int UV2_PRESENT = 1 << 1;
    public static final int MID_TEXCOORD_PRESENT = 1 << 2;
    public static final int TANGENT_PRESENT = 1 << 3;
    public static final int IRIS_ENTITY_PRESENT = 1 << 4;
    public static final int MC_ENTITY_PRESENT = 1 << 5;
    public static final int MID_BLOCK_PRESENT = 1 << 6;
    public static final int IRIS_ENTITY_CONSTANT = 1 << 7;
    public static final int UV1_CONSTANT = 1 << 8;
    public static final int UV2_CONSTANT = 1 << 9;
    public static final int MID_TEXCOORD_CONSTANT = 1 << 10;
    public static final int TANGENT_CONSTANT = 1 << 11;
    public static final int MC_ENTITY_CONSTANT = 1 << 12;
    public static final int MID_BLOCK_CONSTANT = 1 << 13;
    public static final int COLOR_PRESENT = 1 << 14;
    public static final int NORMAL_PRESENT = 1 << 15;
    public static final int COLOR_CONSTANT = 1 << 16;
    public static final int NORMAL_CONSTANT = 1 << 17;
    public static final int MAX_TEXTURES = 256;
    public EntityFrame(ByteBuffer vertices, List<MaterialTextures> textures) { this(vertices, textures, true); }
    public static EntityFrame borrowed(ByteBuffer vertices, List<MaterialTextures> textures) {
        return new EntityFrame(vertices, textures, false);
    }
    public int triangleCount() {
        if (vertices.remaining() % (STRIDE * 3) != 0)
            throw new IllegalStateException("Neutral scene contains an incomplete triangle");
        return vertices.remaining() / (STRIDE * 3);
    }
    @Override public void close() { if (ownsVertices) MemoryUtil.memFree(vertices); }
}
