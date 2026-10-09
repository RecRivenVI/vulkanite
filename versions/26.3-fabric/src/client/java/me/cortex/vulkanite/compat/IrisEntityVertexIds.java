package me.cortex.vulkanite.compat;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormatElement;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/** Decodes Iris's per-vertex entity context without assigning material policy. */
public final class IrisEntityVertexIds {
    public static final String ATTRIBUTE_NAME = "iris_Entity";
    private static final int CHANNEL_BYTES = Short.BYTES;

    private IrisEntityVertexIds() {}

    /**
     * Raw channels retain the exact unsigned values stored by Iris. Only 0xffff is an explicit map
     * miss. A raw zero may be a mapped ID zero or a cleared value; callers must use the actual
     * submit kind/presence context to tell those apart.
     */
    public record VertexIds(int rawEntity, int rawBlockEntity, int rawItem) {
        public OptionalInt entityId() {
            return classified(rawEntity);
        }

        public OptionalInt blockEntityId() {
            return classified(rawBlockEntity);
        }

        public OptionalInt itemId() {
            return classified(rawItem);
        }

        private static OptionalInt classified(int raw) {
            return raw == 0xffff ? OptionalInt.empty() : OptionalInt.of(raw);
        }
    }

    /**
     * Reads one vertex from a MeshData byte buffer. vertexOffset is an absolute byte offset from
     * buffer index zero; the source buffer position is unchanged. A missing attribute is reported
     * as Optional.empty(). A present attribute must have the exact RGBA16_UINT format and fit
     * wholly within one vertex.
     */
    public static Optional<VertexIds> decode(
            VertexFormat format, ByteBuffer vertices, int vertexOffset) {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(vertices, "vertices");

        VertexFormatElement idElement = null;
        for (VertexFormatElement element : format.getElements()) {
            if (!element.name().equals(ATTRIBUTE_NAME)) continue;
            if (idElement != null) {
                throw new IllegalArgumentException("Duplicate vertex attribute " + ATTRIBUTE_NAME);
            }
            idElement = element;
        }
        if (idElement == null) return Optional.empty();
        if (idElement.format() != GpuFormat.RGBA16_UINT) {
            throw new IllegalArgumentException(
                    "Unsupported "
                            + ATTRIBUTE_NAME
                            + " format "
                            + idElement.format()
                            + "; expected RGBA16_UINT");
        }

        int stride = format.getVertexSize();
        int attributeOffset = idElement.offset();
        int attributeBytes = idElement.format().blockSize();
        if (stride <= 0
                || attributeOffset < 0
                || (attributeOffset & (CHANNEL_BYTES - 1)) != 0
                || (long) attributeOffset + attributeBytes > stride) {
            throw new IllegalArgumentException(
                    "Malformed "
                            + ATTRIBUTE_NAME
                            + " vertex offset/stride: offset="
                            + attributeOffset
                            + ", attributeBytes="
                            + attributeBytes
                            + ", stride="
                            + stride);
        }
        if (vertexOffset < 0 || (long) vertexOffset + stride > vertices.limit()) {
            throw new IllegalArgumentException(
                    "Vertex at byte offset "
                            + vertexOffset
                            + " exceeds buffer limit "
                            + vertices.limit()
                            + " for stride "
                            + stride);
        }

        int ids = vertexOffset + attributeOffset;
        ByteBuffer input = vertices.duplicate().order(vertices.order());
        return Optional.of(
                new VertexIds(
                        Short.toUnsignedInt(input.getShort(ids)),
                        Short.toUnsignedInt(input.getShort(ids + CHANNEL_BYTES)),
                        Short.toUnsignedInt(input.getShort(ids + CHANNEL_BYTES * 2))));
    }
}
