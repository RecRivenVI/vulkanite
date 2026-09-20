package me.cortex.vulkanite.compat;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormatElement;
import me.cortex.vulkanite.acceleration.SectionMeshUpdate;
import net.irisshaders.iris.vertices.NormalHelper;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/** Converts Sodium 0.9 terrain vertices to the 64-byte layered terrain ABI. */
public final class TerrainVertexCompatibility {
    public static final int STRIDE = SectionMeshUpdate.VERTEX_STRIDE;
    public static final int TWO_SIDED = 2;
    public static final int ALPHA_DISCARD = 4;
    public static final int ALPHA_BLEND = 8;
    public static final int LIGHT_FROM_SOURCE = 1;
    public static final int MID_UV_FROM_SOURCE = 1 << 1;
    public static final int MID_UV_DERIVED = 1 << 2;
    public static final int TANGENT_DERIVED = 1 << 3;
    public static final int NORMAL_DERIVED = 1 << 4;
    public static final int MC_ENTITY_FROM_SOURCE = 1 << 5;
    public static final int MID_BLOCK_FROM_SOURCE = 1 << 6;
    public static final int IRIS_NORMAL_FROM_SOURCE = 1 << 7;
    public static final int COMPACT_MATERIAL_BITS = 1 << 8;

    private static int offset(Map<String, VertexFormatElement> elements, String name,
                              GpuFormat expected, int stride, boolean required) {
        var element=elements.get(name);
        if(element==null) {
            if(required) throw new IllegalArgumentException("Sodium terrain source lacks " + name);
            return -1;
        }
        if(element.format()!=expected || element.offset()<0
                || element.offset()+expected.blockSize()>stride)
            throw new IllegalArgumentException("Unsupported Sodium terrain attribute " + element);
        return element.offset();
    }

    public static void addFlags(ByteBuffer vertices,int flags) {
        for (int offset=vertices.position();offset<vertices.limit();offset+=STRIDE) {
            vertices.putInt(offset+48,vertices.getInt(offset+48)|flags);
        }
    }

    public static ByteBuffer convert(ByteBuffer input, VertexFormat format) {
        return convert(input,format,false);
    }

    public static ByteBuffer convert(ByteBuffer input, VertexFormat format, boolean sodiumCompact) {
        int stride = format.getVertexSize();
        if (stride < 20 || input.remaining() % (stride * 4) != 0) {
            throw new IllegalArgumentException("Invalid Sodium terrain quad buffer");
        }
        Map<String, VertexFormatElement> elements = new HashMap<>();
        for (var element : format.getElements()) {
            if (elements.put(element.name(), element)!=null)
                throw new IllegalArgumentException("Duplicate Sodium terrain attribute " + element.name());
        }
        if(offset(elements,"a_Position",GpuFormat.RG32_UINT,stride,true)!=0
                || offset(elements,"a_Color",GpuFormat.RGBA8_UNORM,stride,true)!=8
                || offset(elements,"a_TexCoord",GpuFormat.RG16_UINT,stride,true)!=12
                || offset(elements,"a_LightAndData",GpuFormat.RGBA8_UINT,stride,true)!=16)
            throw new IllegalArgumentException("Sodium terrain base layout has changed");
        if(sodiumCompact && (stride!=20 || elements.size()!=4))
            throw new IllegalArgumentException("Compact material bits were declared for a non-Compact terrain format");
        int mcEntity=offset(elements,"mc_Entity",GpuFormat.R32_UINT,stride,false);
        int midBlock=offset(elements,"at_midBlock",GpuFormat.RGBA8_SNORM,stride,false);
        int midUvSource=offset(elements,"mc_midTexCoord",GpuFormat.RG16_UINT,stride,false);
        int irisNormal=offset(elements,"iris_Normal",GpuFormat.R32_UINT,stride,false);
        int presence=LIGHT_FROM_SOURCE|TANGENT_DERIVED|NORMAL_DERIVED
                |(midUvSource>=0?MID_UV_FROM_SOURCE:MID_UV_DERIVED)
                |(mcEntity>=0?MC_ENTITY_FROM_SOURCE:0)
                |(midBlock>=0?MID_BLOCK_FROM_SOURCE:0)
                |(irisNormal>=0?IRIS_NORMAL_FROM_SOURCE:0)
                |(sodiumCompact?COMPACT_MATERIAL_BITS:0);
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
                    MemoryUtil.memPutInt(dst + 16, light); // Full a_LightAndData, including producer data bits.
                    if (mcEntity>=0) {
                        int block = MemoryUtil.memGetInt(src + mcEntity);
                        MemoryUtil.memPutShort(dst + 32, (short) ((block >>> 1) - 1));
                        MemoryUtil.memPutShort(dst + 34, (short) (block & 1));
                        MemoryUtil.memPutInt(dst + 60, block);
                    }
                    if (midBlock>=0) MemoryUtil.memPutInt(dst + 36, MemoryUtil.memGetInt(src + midBlock));
                    if (midUvSource>=0) MemoryUtil.memPutInt(dst + 20, MemoryUtil.memGetInt(src + midUvSource));
                    if (irisNormal>=0) MemoryUtil.memPutInt(dst + 56, MemoryUtil.memGetInt(src + irisNormal));
                    MemoryUtil.memPutInt(dst + 52, presence);
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
                    for (int axis = 0; midUvSource<0 && axis < 2; axis++) {
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
