package me.cortex.vulkanite.compat;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class EntityVertexCompatibility {
    private EntityVertexCompatibility() {}
    /** Transform current positions before matching motion history in world coordinates. */
    public static void transform(ByteBuffer vertices, org.joml.Matrix4f transform) {
        if (transform == null) return;
        var position = new org.joml.Vector3f();
        for (int base = vertices.position(); base < vertices.limit(); base += EntityFrame.STRIDE) {
            position.set(vertices.getFloat(base), vertices.getFloat(base + 4), vertices.getFloat(base + 8));
            transform.transformPosition(position);
            vertices.putFloat(base, position.x).putFloat(base + 4, position.y).putFloat(base + 8, position.z);
        }
    }
    private static int offset(VertexFormat format, String name, GpuFormat expected, boolean required) {
        for (var element : format.getElements()) {
            if (element.name().equals(name)) {
                if (element.format() != expected) throw new IllegalArgumentException("Unsupported entity attribute: " + element);
                return element.offset();
            }
        }
        if (required) throw new IllegalArgumentException("Missing entity attribute " + name);
        return -1;
    }
    public static void append(ByteBuffer source, VertexFormat format, int texture, ByteBuffer output) {
        var input = source.duplicate().order(ByteOrder.nativeOrder());
        int stride = format.getVertexSize();
        if (input.remaining() % (stride * 4) != 0) throw new IllegalArgumentException("Entity geometry must contain complete quads");
        int p = offset(format, "Position", GpuFormat.RGB32_FLOAT, true);
        int uv = offset(format, "UV0", GpuFormat.RG32_FLOAT, true);
        int c = offset(format, "Color", GpuFormat.RGBA8_UNORM, false);
        int n = offset(format, "Normal", GpuFormat.RGBA8_SNORM, false);
        for (int base = input.position(); base < input.limit(); base += stride) {
            for (int i = 0; i < 3; i++) output.putFloat(input.getFloat(base + p + i * 4));
            output.putInt(c < 0 ? -1 : input.getInt(base + c));
            output.putFloat(input.getFloat(base + uv)).putFloat(input.getFloat(base + uv + 4));
            output.putInt(n < 0 ? 0 : input.getInt(base + n));
            output.putInt(texture);
            for (int i = 0; i < 3; i++) output.putFloat(input.getFloat(base + p + i * 4));
            output.putInt(0); // Filled only when the capture bridge establishes correspondence.
        }
    }
}
