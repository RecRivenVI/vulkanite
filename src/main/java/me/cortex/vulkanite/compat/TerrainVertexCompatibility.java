package me.cortex.vulkanite.compat;

import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.irisshaders.iris.vertices.NormalHelper;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/** Converts Sodium 0.9 terrain vertices to the 64-byte layered terrain ABI. */
public final class TerrainVertexCompatibility {
    public static final int STRIDE = 64;

    public static ByteBuffer convert(ByteBuffer input, VertexFormat format) {
        int stride = format.getVertexSize();
        if (stride < 20 || input.remaining() % (stride * 4) != 0) {
            throw new IllegalArgumentException("Invalid Sodium terrain quad buffer");
        }
        Map<String, Integer> offsets = new HashMap<>();
        for (var element : format.getElements()) {
            offsets.put(element.name(), element.offset());
        }
        long source = MemoryUtil.memAddress(input);
        int vertices = input.remaining() / stride;
        var output = MemoryUtil.memCalloc(vertices * STRIDE);
        long destination = MemoryUtil.memAddress(output);
        float[][] positions = new float[4][3];
        float[][] uv = new float[4][2];
        var normal = new Vector3f();
        var tangent = new Vector4f();
        try {
            for (int quad = 0; quad < vertices; quad += 4) {
                for (int vertex = 0; vertex < 4; vertex++) {
                    long src = source + (long) (quad + vertex) * stride;
                    long dst = destination + (long) (quad + vertex) * STRIDE;
                    int high = MemoryUtil.memGetInt(src);
                    int low = MemoryUtil.memGetInt(src + 4);
                    for (int axis = 0; axis < 3; axis++) {
                        int position = (((high >>> (axis * 10)) & 1023) << 10) | ((low >>> (axis * 10)) & 1023);
                        positions[vertex][axis] = position * (32.0f / 1048576.0f) - 8.0f;
                        MemoryUtil.memPutShort(dst + axis * 2L, (short) (position >>> 4));
                    }
                    MemoryUtil.memPutInt(dst + 8, MemoryUtil.memGetInt(src + 8));
                    for (int axis = 0; axis < 2; axis++) {
                        int packed = Short.toUnsignedInt(MemoryUtil.memGetShort(src + 12 + axis * 2L));
                        int value = (packed & 32767) - ((packed & 32768) == 0 ? 1 : -1);
                        uv[vertex][axis] = value / 32768.0f;
                        MemoryUtil.memPutShort(dst + 12 + axis * 2L, (short) Math.clamp(value * 2, 0, 65535));
                    }
                    int light = MemoryUtil.memGetInt(src + 16);
                    MemoryUtil.memPutShort(dst + 16, (short) (light & 255));
                    MemoryUtil.memPutShort(dst + 18, (short) ((light >>> 8) & 255));
                    if (offsets.containsKey("mc_Entity")) {
                        int block = MemoryUtil.memGetInt(src + offsets.get("mc_Entity"));
                        MemoryUtil.memPutShort(dst + 32, (short) ((block >>> 1) - 1));
                        MemoryUtil.memPutShort(dst + 34, (short) (block & 1));
                    }
                    if (offsets.containsKey("at_midBlock")) {
                        MemoryUtil.memPutInt(dst + 36, MemoryUtil.memGetInt(src + offsets.get("at_midBlock")));
                    }
                }
                NormalHelper.computeFaceNormalManual(normal,
                        positions[0][0], positions[0][1], positions[0][2],
                        positions[1][0], positions[1][1], positions[1][2],
                        positions[2][0], positions[2][1], positions[2][2],
                        positions[3][0], positions[3][1], positions[3][2]);
                int packedTangent = tangent(tangent, normal, positions, uv, 0, 1, 2);
                if (packedTangent == -1) tangent(tangent, normal, positions, uv, 2, 3, 0);
                for (int vertex = 0; vertex < 4; vertex++) {
                    long dst = destination + (long) (quad + vertex) * STRIDE;
                    for (int axis = 0; axis < 3; axis++) {
                        MemoryUtil.memPutByte(dst + 28 + axis, packNormal(normal.get(axis)));
                        MemoryUtil.memPutByte(dst + 24 + axis, packNormal(tangent.get(axis)));
                    }
                    MemoryUtil.memPutByte(dst + 27, packNormal(tangent.w));
                    for (int axis = 0; axis < 2; axis++) {
                        float midpoint = (uv[0][axis] + uv[1][axis] + uv[2][axis] + uv[3][axis]) * 0.25f;
                        MemoryUtil.memPutShort(dst + 20 + axis * 2L, (short) Math.clamp(Math.round(midpoint * 65536), 0, 65535));
                    }
                }
            }
            return output;
        } catch (Throwable failure) {
            MemoryUtil.memFree(output);
            throw failure;
        }
    }

    private static byte packNormal(float value) { return (byte) (Math.clamp(value, -1.0f, 1.0f) * 127.0f); }
    private static int tangent(Vector4f result, Vector3f normal, float[][] p, float[][] uv, int a, int b, int c) {
        return NormalHelper.computeTangent(result, normal.x, normal.y, normal.z,
                p[a][0], p[a][1], p[a][2], uv[a][0], uv[a][1],
                p[b][0], p[b][1], p[b][2], uv[b][0], uv[b][1],
                p[c][0], p[c][1], p[c][2], uv[c][0], uv[c][1]);
    }
}
