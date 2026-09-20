package me.cortex.vulkanite.compat;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class EntityVertexCompatibility {
    private EntityVertexCompatibility() {}
    private static final int[] TRIANGLE_CORNERS = {0, 1, 2, 0, 2, 3};
    private static int offset(VertexFormat format, String name, GpuFormat expected, boolean required) {
        int found = -1;
        for (var element : format.getElements()) {
            if (element.name().equals(name)) {
                if (element.format() != expected) throw new IllegalArgumentException("Unsupported entity attribute: " + element);
                if (found >= 0) throw new IllegalArgumentException("Duplicate entity attribute " + name);
                found = element.offset();
            }
        }
        if (required && found < 0) throw new IllegalArgumentException("Missing entity attribute " + name);
        if (found >= 0 && found + expected.blockSize() > format.getVertexSize())
            throw new IllegalArgumentException("Entity attribute exceeds vertex stride: " + name);
        return found;
    }
    public static void append(ByteBuffer source, VertexFormat format, int texture, ByteBuffer output) {
        var input = source.duplicate().order(ByteOrder.nativeOrder());
        int stride = format.getVertexSize();
        if (input.remaining() % (stride * 4) != 0) throw new IllegalArgumentException("Entity geometry must contain complete quads");
        int p = offset(format, "Position", GpuFormat.RGB32_FLOAT, true);
        int uv = offset(format, "UV0", GpuFormat.RG32_FLOAT, true);
        int c = offset(format, "Color", GpuFormat.RGBA8_UNORM, false);
        int n = offset(format, "Normal", GpuFormat.RGBA8_SNORM, false);
        int uv1 = offset(format, "UV1", GpuFormat.RG16_SINT, false);
        int uv2 = offset(format, "UV2", GpuFormat.RG16_SINT, false);
        int midUv = offset(format, "mc_midTexCoord", GpuFormat.RG32_FLOAT, false);
        int tangent = offset(format, "at_tangent", GpuFormat.RGBA8_SNORM, false);
        int irisEntity = offset(format, "iris_Entity", GpuFormat.RGBA16_UINT, false);
        int mcEntity = offset(format, "mc_Entity", GpuFormat.RG16_SINT, false);
        int midBlock = offset(format, "at_midBlock", GpuFormat.RGBA8_SNORM, false);
        int presence = (c >= 0 ? EntityFrame.COLOR_PRESENT : 0)
                | (n >= 0 ? EntityFrame.NORMAL_PRESENT : 0)
                | (uv1 >= 0 ? EntityFrame.UV1_PRESENT : 0)
                | (uv2 >= 0 ? EntityFrame.UV2_PRESENT : 0)
                | (midUv >= 0 ? EntityFrame.MID_TEXCOORD_PRESENT : 0)
                | (tangent >= 0 ? EntityFrame.TANGENT_PRESENT : 0)
                | (irisEntity >= 0 ? EntityFrame.IRIS_ENTITY_PRESENT : 0)
                | (mcEntity >= 0 ? EntityFrame.MC_ENTITY_PRESENT : 0)
                | (midBlock >= 0 ? EntityFrame.MID_BLOCK_PRESENT : 0);
        for (int quad = input.position(); quad < input.limit(); quad += stride * 4) {
            for (int corner : TRIANGLE_CORNERS) {
                int base = quad + corner * stride;
                for (int i = 0; i < 3; i++) output.putFloat(input.getFloat(base + p + i * 4));
                output.putInt(c < 0 ? -1 : input.getInt(base + c));
                output.putFloat(input.getFloat(base + uv)).putFloat(input.getFloat(base + uv + 4));
                output.putInt(n < 0 ? 0 : input.getInt(base + n));
                output.putInt(texture);
                output.putInt(0); // Surface flags are added by the scene adapter.
                output.putInt(0); // Selected material ID retains the full signed int.
                output.putInt(uv1 < 0 ? 0 : input.getShort(base + uv1));
                output.putInt(uv1 < 0 ? 0 : input.getShort(base + uv1 + 2));
                output.putInt(uv2 < 0 ? 0 : input.getShort(base + uv2));
                output.putInt(uv2 < 0 ? 0 : input.getShort(base + uv2 + 2));
                output.putFloat(midUv < 0 ? 0 : input.getFloat(base + midUv));
                output.putFloat(midUv < 0 ? 0 : input.getFloat(base + midUv + 4));
                output.putInt(tangent < 0 ? 0 : input.getInt(base + tangent));
                for (int channel = 0; channel < 4; channel++)
                    output.putInt(irisEntity < 0 ? 0
                            : Short.toUnsignedInt(input.getShort(base + irisEntity + channel * 2)));
                output.putInt(mcEntity < 0 ? 0 : input.getShort(base + mcEntity));
                output.putInt(mcEntity < 0 ? 0 : input.getShort(base + mcEntity + 2));
                output.putInt(midBlock < 0 ? 0 : input.getInt(base + midBlock));
                output.putInt(presence);
                output.putInt(0);
                output.putInt(0);
                output.putInt(0);
            }
        }
    }
}
