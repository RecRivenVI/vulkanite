package me.cortex.vulkanite.compat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Partitions complete source triangles without changing their vertices or material fields. */
public final class EntityRayPartition {
    public static final int PRIMARY_MASK = 1;
    public static final int VIEW_MODEL_MASK = 2;
    public static final int WORLD_MASK = 4;
    public static final int TERRAIN_MASK = PRIMARY_MASK | WORLD_MASK;
    public static final int MAX_GROUPS = 3;
    private static final int TRIANGLE_BYTES = EntityFrame.STRIDE * 3;
    private static final int CLASSIFICATION_FLAGS =
            EntityFrame.CAMERA_HIDDEN
                    | EntityFrame.VIEW_MODEL
                    | EntityFrame.ALPHA_CUTOUT
                    | EntityFrame.ALPHA_BLEND;

    public record Span(int byteOffset, int byteCount) {}

    public record Group(int mask, int triangleCount, boolean opaque, List<Span> spans) {
        public int byteCount() {
            return Math.multiplyExact(triangleCount, TRIANGLE_BYTES);
        }
    }

    private EntityRayPartition() {}

    public static List<Group> partition(ByteBuffer input) {
        var source = input.duplicate().order(ByteOrder.nativeOrder());
        if (source.remaining() % TRIANGLE_BYTES != 0)
            throw new IllegalStateException("Neutral scene contains an incomplete triangle");
        int[] masks = {TERRAIN_MASK, WORLD_MASK, VIEW_MODEL_MASK};
        var builders = new Builder[MAX_GROUPS];
        for (int i = 0; i < builders.length; i++) builders[i] = new Builder(masks[i]);
        for (int offset = source.position(); offset < source.limit(); offset += TRIANGLE_BYTES) {
            int flags = source.getInt(offset + 32);
            for (int vertex = 1; vertex < 3; vertex++) {
                int other = source.getInt(offset + vertex * EntityFrame.STRIDE + 32);
                if ((flags & CLASSIFICATION_FLAGS) != (other & CLASSIFICATION_FLAGS))
                    throw new IllegalStateException("Triangle visibility/coverage flags disagree");
            }
            int group =
                    (flags & EntityFrame.VIEW_MODEL) != 0
                            ? 2
                            : (flags & EntityFrame.CAMERA_HIDDEN) != 0 ? 1 : 0;
            builders[group].append(offset, flags);
        }
        var groups = new ArrayList<Group>(MAX_GROUPS);
        for (var builder : builders)
            if (builder.triangles != 0)
                groups.add(
                        new Group(
                                builder.mask, builder.triangles, builder.opaque, builder.finish()));
        return List.copyOf(groups);
    }

    private static final class Builder {
        final int mask;
        final List<Span> spans = new ArrayList<>();
        int triangles;
        int spanOffset = -1;
        int spanBytes;
        boolean opaque = true;

        Builder(int mask) {
            this.mask = mask;
        }

        void append(int offset, int flags) {
            triangles++;
            opaque &= (flags & (EntityFrame.ALPHA_CUTOUT | EntityFrame.ALPHA_BLEND)) == 0;
            if (spanOffset >= 0 && spanOffset + spanBytes == offset) {
                spanBytes += TRIANGLE_BYTES;
                return;
            }
            if (spanOffset >= 0) spans.add(new Span(spanOffset, spanBytes));
            spanOffset = offset;
            spanBytes = TRIANGLE_BYTES;
        }

        List<Span> finish() {
            if (spanOffset >= 0) spans.add(new Span(spanOffset, spanBytes));
            return List.copyOf(spans);
        }
    }
}
