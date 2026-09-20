package me.cortex.vulkanite.compat;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

class EntityGeometrySnapshotTest {
    @Test
    void snapshotsOwnTheirBytesAndRespectSourceWindows() {
        var source = ByteBuffer.allocate(40);
        for (int i = 0; i < source.capacity(); i++) source.put(i, (byte) i);
        source.position(5).limit(29);
        try (var snapshot = new EntityGeometrySnapshot()) {
            assertFalse(snapshot.matches(source));
            snapshot.capture(source);
            assertTrue(snapshot.matches(source));
            assertEquals(5, source.position());
            assertEquals(29, source.limit());
            source.put(10, (byte) 90);
            assertFalse(snapshot.matches(source));
            source.put(10, (byte) 10);
            assertTrue(snapshot.matches(source));
            source.limit(28);
            assertFalse(snapshot.matches(source));
            snapshot.clear();
            assertFalse(snapshot.matches(source));
        }
    }

    @Test
    void equalRollingHashesCannotReuseDifferentGeometry() {
        var first = ByteBuffer.allocate(8).putInt(0).putInt(31).flip();
        var second = ByteBuffer.allocate(8).putInt(1).putInt(0).flip();
        // Both previously produced 31 * (31 + word0) + word1 == 992.
        try (var snapshot = new EntityGeometrySnapshot()) {
            snapshot.capture(first);
            assertFalse(snapshot.matches(second));
            snapshot.capture(second);
            assertTrue(snapshot.matches(second));
        }
    }

    @Test
    void growthAndEmptyFramesDoNotRetainStaleContents() {
        try (var snapshot = new EntityGeometrySnapshot()) {
            snapshot.capture(ByteBuffer.allocate(0));
            assertTrue(snapshot.matches(ByteBuffer.allocate(0)));
            snapshot.capture(ByteBuffer.allocate(1024));
            assertTrue(snapshot.matches(ByteBuffer.allocate(1024)));
            assertFalse(snapshot.matches(ByteBuffer.allocate(0)));
            snapshot.close();
            assertFalse(snapshot.matches(ByteBuffer.allocate(1024)));
        }
    }
}
