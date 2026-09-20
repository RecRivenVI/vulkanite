package me.cortex.vulkanite.compat;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.jupiter.api.Test;

class EntityRayPartitionTest {
    private static final int TRIANGLE_BYTES = EntityFrame.STRIDE * 3;

    private ByteBuffer triangles(int... flags) {
        var buffer =
                ByteBuffer.allocate(TRIANGLE_BYTES * flags.length).order(ByteOrder.nativeOrder());
        for (int triangle = 0; triangle < flags.length; triangle++) {
            for (int vertex = 0; vertex < 3; vertex++) {
                int base = triangle * TRIANGLE_BYTES + vertex * EntityFrame.STRIDE;
                for (int word = 0; word < EntityFrame.STRIDE / 4; word++)
                    buffer.putInt(base + word * 4, triangle * 1000 + vertex * 100 + word);
                buffer.putInt(base + 32, flags[triangle]);
            }
        }
        return buffer;
    }

    @Test
    void viewModelIsExclusiveAndCameraBodyStillParticipatesInWorld() {
        var groups =
                EntityRayPartition.partition(
                        triangles(
                                0,
                                EntityFrame.CAMERA_HIDDEN,
                                EntityFrame.VIEW_MODEL | EntityFrame.CAMERA_HIDDEN));
        assertEquals(
                List.of(5, 4, 2), groups.stream().map(EntityRayPartition.Group::mask).toList());
        assertEquals(0, groups.get(2).mask() & EntityRayPartition.WORLD_MASK);
        assertEquals(0, groups.get(1).mask() & EntityRayPartition.PRIMARY_MASK);
        assertTrue(groups.stream().allMatch(EntityRayPartition.Group::opaque));
    }

    @Test
    void mixedCoverageCannotBePromotedToOpaque() {
        var groups =
                EntityRayPartition.partition(
                        triangles(
                                0,
                                EntityFrame.ALPHA_BLEND,
                                EntityFrame.CAMERA_HIDDEN,
                                EntityFrame.VIEW_MODEL | EntityFrame.ALPHA_CUTOUT));
        assertFalse(groups.get(0).opaque());
        assertTrue(groups.get(1).opaque());
        assertFalse(groups.get(2).opaque());
        assertEquals(2, groups.get(0).triangleCount());
    }

    @Test
    void groupingPreservesEveryVertexFieldAndSourceWindow() {
        var source = triangles(0, EntityFrame.CAMERA_HIDDEN, 0, EntityFrame.VIEW_MODEL, 0);
        source.position(TRIANGLE_BYTES);
        source.limit(4 * TRIANGLE_BYTES);
        var original = source.duplicate();
        var groups = EntityRayPartition.partition(source);
        assertEquals(TRIANGLE_BYTES, source.position());
        assertEquals(4 * TRIANGLE_BYTES, source.limit());
        boolean[] seen = new boolean[5];
        for (var group : groups) {
            int bytes = 0;
            for (var span : group.spans()) {
                var segment = source.duplicate();
                segment.position(span.byteOffset()).limit(span.byteOffset() + span.byteCount());
                var copied = ByteBuffer.allocate(span.byteCount());
                copied.put(segment).flip();
                var expected = original.duplicate();
                expected.position(span.byteOffset()).limit(span.byteOffset() + span.byteCount());
                assertEquals(expected, copied);
                for (int offset = span.byteOffset();
                        offset < span.byteOffset() + span.byteCount();
                        offset += TRIANGLE_BYTES) {
                    assertFalse(seen[offset / TRIANGLE_BYTES]);
                    seen[offset / TRIANGLE_BYTES] = true;
                }
                bytes += span.byteCount();
            }
            assertEquals(group.byteCount(), bytes);
        }
        assertArrayEquals(new boolean[] {false, true, true, true, false}, seen);
    }

    @Test
    void coalescesAdjacentTrianglesButKeepsDisjointSpans() {
        var group =
                EntityRayPartition.partition(triangles(0, 0, EntityFrame.VIEW_MODEL, 0)).getFirst();
        assertEquals(
                List.of(
                        new EntityRayPartition.Span(0, TRIANGLE_BYTES * 2),
                        new EntityRayPartition.Span(TRIANGLE_BYTES * 3, TRIANGLE_BYTES)),
                group.spans());
    }

    @Test
    void rejectsIncompleteTrianglesAndInconsistentVisibilityOrCoverage() {
        assertThrows(
                IllegalStateException.class,
                () -> EntityRayPartition.partition(ByteBuffer.allocate(1)));
        var triangle = triangles(0);
        triangle.putInt(EntityFrame.STRIDE + 32, EntityFrame.VIEW_MODEL);
        assertThrows(IllegalStateException.class, () -> EntityRayPartition.partition(triangle));
        triangle.putInt(EntityFrame.STRIDE + 32, EntityFrame.ALPHA_BLEND);
        assertThrows(IllegalStateException.class, () -> EntityRayPartition.partition(triangle));
        assertTrue(EntityRayPartition.partition(ByteBuffer.allocate(0)).isEmpty());
    }
}
