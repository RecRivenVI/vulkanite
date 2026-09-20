package me.cortex.vulkanite.compat;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.system.MemoryUtil;

public final class EntityVertexCompatibility {
    private EntityVertexCompatibility() {}

    private static final int[] TRIANGLE_CORNERS = {0, 1, 2, 0, 2, 3};

    private static int offset(
            VertexFormat format, String name, GpuFormat expected, boolean required) {
        int found = -1;
        for (var element : format.getElements()) {
            if (element.name().equals(name)) {
                if (element.format() != expected)
                    throw new IllegalArgumentException("Unsupported entity attribute: " + element);
                if (found >= 0)
                    throw new IllegalArgumentException("Duplicate entity attribute " + name);
                found = element.offset();
            }
        }
        if (required && found < 0)
            throw new IllegalArgumentException("Missing entity attribute " + name);
        if (found >= 0 && found + expected.blockSize() > format.getVertexSize())
            throw new IllegalArgumentException("Entity attribute exceeds vertex stride: " + name);
        return found;
    }

    public record Layout(
            int stride,
            int p,
            int uv,
            int c,
            int n,
            int uv1,
            int uv2,
            int midUv,
            int tangent,
            int irisEntity,
            int mcEntity,
            int midBlock,
            int presence) {}

    public static Layout resolve(VertexFormat format) {
        int stride = format.getVertexSize();
        if (stride <= 0) throw new IllegalArgumentException("Invalid entity vertex stride");
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
        int presence =
                (c >= 0 ? EntityFrame.COLOR_PRESENT : 0)
                        | (n >= 0 ? EntityFrame.NORMAL_PRESENT : 0)
                        | (uv1 >= 0 ? EntityFrame.UV1_PRESENT : 0)
                        | (uv2 >= 0 ? EntityFrame.UV2_PRESENT : 0)
                        | (midUv >= 0 ? EntityFrame.MID_TEXCOORD_PRESENT : 0)
                        | (tangent >= 0 ? EntityFrame.TANGENT_PRESENT : 0)
                        | (irisEntity >= 0 ? EntityFrame.IRIS_ENTITY_PRESENT : 0)
                        | (mcEntity >= 0 ? EntityFrame.MC_ENTITY_PRESENT : 0)
                        | (midBlock >= 0 ? EntityFrame.MID_BLOCK_PRESENT : 0);
        if (irisEntity >= 0 && (irisEntity & 1) != 0)
            throw new IllegalArgumentException("Unaligned Iris entity attribute");
        return new Layout(
                stride,
                p,
                uv,
                c,
                n,
                uv1,
                uv2,
                midUv,
                tangent,
                irisEntity,
                mcEntity,
                midBlock,
                presence);
    }

    /** Writes each of the four physical vertices once, before triangle expansion. */
    public static void appendQuads(
            ByteBuffer source,
            Layout layout,
            int texture,
            int flags,
            int material,
            ByteBuffer output) {
        var input = source.duplicate().order(ByteOrder.nativeOrder());
        int stride = layout.stride();
        if (input.remaining() % Math.multiplyExact(stride, 4) != 0)
            throw new IllegalArgumentException("Entity geometry must contain complete quads");
        int p = layout.p();
        int uv = layout.uv();
        int c = layout.c();
        int n = layout.n();
        int uv1 = layout.uv1();
        int uv2 = layout.uv2();
        int midUv = layout.midUv();
        int tangent = layout.tangent();
        int irisEntity = layout.irisEntity();
        int mcEntity = layout.mcEntity();
        int midBlock = layout.midBlock();
        int presence = layout.presence();
        for (int quad = input.position(); quad < input.limit(); quad += stride * 4) {
            for (int corner = 0; corner < 4; corner++) {
                int base = quad + corner * stride;
                for (int i = 0; i < 3; i++) output.putFloat(input.getFloat(base + p + i * 4));
                output.putInt(c < 0 ? -1 : input.getInt(base + c));
                output.putFloat(input.getFloat(base + uv)).putFloat(input.getFloat(base + uv + 4));
                output.putInt(n < 0 ? 0 : input.getInt(base + n));
                output.putInt(texture);
                output.putInt(flags);
                output.putInt(material);
                output.putInt(uv1 < 0 ? 0 : input.getShort(base + uv1));
                output.putInt(uv1 < 0 ? 0 : input.getShort(base + uv1 + 2));
                output.putInt(uv2 < 0 ? 0 : input.getShort(base + uv2));
                output.putInt(uv2 < 0 ? 0 : input.getShort(base + uv2 + 2));
                output.putFloat(midUv < 0 ? 0 : input.getFloat(base + midUv));
                output.putFloat(midUv < 0 ? 0 : input.getFloat(base + midUv + 4));
                output.putInt(tangent < 0 ? 0 : input.getInt(base + tangent));
                for (int channel = 0; channel < 4; channel++)
                    output.putInt(
                            irisEntity < 0
                                    ? 0
                                    : Short.toUnsignedInt(
                                            input.getShort(base + irisEntity + channel * 2)));
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

    /** Expands backwards so later triangle vertices cannot overwrite unread quad vertices. */
    public static void expandQuads(ByteBuffer output, int start, int quadCount) {
        int end = Math.addExact(start, Math.multiplyExact(quadCount, 6 * EntityFrame.STRIDE));
        if (!output.isDirect() || start < 0 || quadCount < 0 || end > output.limit())
            throw new IllegalArgumentException(
                    "Quad expansion requires sufficient direct output storage");
        long address = MemoryUtil.memAddress0(output);
        for (int quad = quadCount - 1; quad >= 0; quad--) {
            int source = start + quad * 4 * EntityFrame.STRIDE;
            int target = start + quad * 6 * EntityFrame.STRIDE;
            for (int corner = TRIANGLE_CORNERS.length - 1; corner >= 0; corner--) {
                long from = address + source + TRIANGLE_CORNERS[corner] * EntityFrame.STRIDE;
                long to = address + target + corner * EntityFrame.STRIDE;
                if (from != to) MemoryUtil.memCopy(from, to, EntityFrame.STRIDE);
            }
        }
        output.position(end);
    }
}
