package me.cortex.vulkanite.compat;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import me.cortex.vulkanite.client.rendering.FrameSnapshot;
import me.cortex.vulkanite.client.rendering.interop.ProducedDraws;
import me.cortex.vulkanite.client.rendering.interop.ProducedSceneAssembler;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

class EntityVertexCompatibilityTest {
    private static final int[] CORNERS = {0, 1, 2, 0, 2, 3};

    @Test
    void backwardsExpansionPreservesAllFieldsAcrossQuadsAndPrefixes() {
        int prefix = 32, quads = 5;
        var output =
                MemoryUtil.memAlloc(prefix + quads * 6 * EntityFrame.STRIDE)
                        .order(ByteOrder.nativeOrder());
        try {
            for (int i = 0; i < prefix; i++) output.put(i, (byte) 0x5a);
            for (int vertex = 0; vertex < quads * 4; vertex++)
                for (int field = 0; field < EntityFrame.STRIDE / 4; field++)
                    output.putInt(
                            prefix + vertex * EntityFrame.STRIDE + field * 4,
                            vertex * 1000 + field);
            EntityVertexCompatibility.expandQuads(output, prefix, quads);
            assertEquals(output.limit(), output.position());
            for (int i = 0; i < prefix; i++) assertEquals((byte) 0x5a, output.get(i));
            for (int quad = 0; quad < quads; quad++)
                for (int corner = 0; corner < 6; corner++)
                    for (int field = 0; field < EntityFrame.STRIDE / 4; field++)
                        assertEquals(
                                (quad * 4 + CORNERS[corner]) * 1000 + field,
                                output.getInt(
                                        prefix
                                                + (quad * 6 + corner) * EntityFrame.STRIDE
                                                + field * 4));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> EntityVertexCompatibility.expandQuads(output, prefix, quads + 1));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> EntityVertexCompatibility.expandQuads(ByteBuffer.allocate(1000), 0, 1));
        } finally {
            MemoryUtil.memFree(output);
        }
    }

    private static EntityVertexCompatibility.Layout layout() {
        return new EntityVertexCompatibility.Layout(
                64,
                0,
                16,
                12,
                24,
                28,
                32,
                36,
                44,
                48,
                56,
                60,
                EntityFrame.COLOR_PRESENT
                        | EntityFrame.NORMAL_PRESENT
                        | EntityFrame.UV1_PRESENT
                        | EntityFrame.UV2_PRESENT
                        | EntityFrame.MID_TEXCOORD_PRESENT
                        | EntityFrame.TANGENT_PRESENT
                        | EntityFrame.IRIS_ENTITY_PRESENT
                        | EntityFrame.MC_ENTITY_PRESENT
                        | EntityFrame.MID_BLOCK_PRESENT);
    }

    private static ByteBuffer source() {
        var source = ByteBuffer.allocate(16 + 64 * 4).order(ByteOrder.nativeOrder());
        for (int i = 0; i < 4; i++) {
            int base = 16 + i * 64;
            source.putFloat(base, (i % 2) * 0.2f)
                    .putFloat(base + 4, (i / 2) * 0.2f)
                    .putFloat(base + 8, -2);
            source.putInt(base + 12, 0x8090a000 + i);
            source.putFloat(base + 16, i * 0.1f).putFloat(base + 20, i * 0.2f);
            source.putInt(base + 24, 0x007f0000);
            source.putShort(base + 28, (short) -4).putShort(base + 30, (short) 19);
            source.putShort(base + 32, (short) 8).putShort(base + 34, (short) -17);
            source.putFloat(base + 36, 0.3f).putFloat(base + 40, 0.6f);
            source.putInt(base + 44, 0x7f00007f);
            for (int channel = 0; channel < 4; channel++)
                source.putShort(base + 48 + channel * 2, (short) (0xff00 + channel));
            source.putShort(base + 56, (short) -7).putShort(base + 58, (short) 14);
            source.putInt(base + 60, 0xcafebabe);
        }
        source.position(16);
        return source;
    }

    @Test
    void decodeRetainsSignedUnsignedAndOptionalVertexFields() {
        var source = source();
        var output = ByteBuffer.allocate(4 * EntityFrame.STRIDE).order(ByteOrder.nativeOrder());
        EntityVertexCompatibility.appendQuads(
                source.asReadOnlyBuffer(), layout(), 5, EntityFrame.VIEW_MODEL, 70000, output);
        assertEquals(16, source.position());
        assertEquals(output.capacity(), output.position());
        for (int i = 0; i < 4; i++) {
            int base = i * EntityFrame.STRIDE;
            assertEquals(0x8090a000 + i, output.getInt(base + 12));
            assertEquals(5, output.getInt(base + 28));
            assertEquals(EntityFrame.VIEW_MODEL, output.getInt(base + 32));
            assertEquals(70000, output.getInt(base + 36));
            assertEquals(-4, output.getInt(base + 40));
            assertEquals(19, output.getInt(base + 44));
            assertEquals(8, output.getInt(base + 48));
            assertEquals(-17, output.getInt(base + 52));
            for (int channel = 0; channel < 4; channel++)
                assertEquals(0xff00 + channel, output.getInt(base + 68 + channel * 4));
            assertEquals(-7, output.getInt(base + 84));
            assertEquals(14, output.getInt(base + 88));
            assertEquals(0xcafebabe, output.getInt(base + 92));
            assertEquals(layout().presence(), output.getInt(base + 96));
            for (int offset = 100; offset < 112; offset += 4)
                assertEquals(0, output.getInt(base + offset));
        }
    }

    @Test
    void transformingBeforeExpansionIsByteIdenticalForWorldShadowAndHand() throws Exception {
        var method =
                ProducedSceneAssembler.class.getDeclaredMethod(
                        "transform",
                        ByteBuffer.class,
                        ProducedDraws.Draw.class,
                        FrameSnapshot.class);
        method.setAccessible(true);
        var snapshot =
                new FrameSnapshot(
                        new Matrix4f().perspective(1.2f, 16f / 9f, 0.05f, 1000f),
                        new Matrix4f().rotateY(0.3f),
                        new Vector3d(1000, 200, -500),
                        0.5f,
                        2);
        for (var pass : ProducedDraws.Pass.values()) {
            var first = MemoryUtil.memAlloc(6 * EntityFrame.STRIDE).order(ByteOrder.nativeOrder());
            var second = MemoryUtil.memAlloc(6 * EntityFrame.STRIDE).order(ByteOrder.nativeOrder());
            try {
                var draw =
                        new ProducedDraws.Draw(
                                pass,
                                null,
                                null,
                                null,
                                4,
                                new byte[0],
                                null,
                                new Matrix4f().rotateZ(0.2f).scale(2, -0.5f, 1.3f),
                                new Matrix4f().perspective(1f, 16f / 9f, 0.05f, 1000f));
                EntityVertexCompatibility.appendQuads(
                        source(), layout(), 3, EntityFrame.ALPHA_BLEND, 1002, first);
                EntityVertexCompatibility.appendQuads(
                        source(), layout(), 3, EntityFrame.ALPHA_BLEND, 1002, second);
                // Reference ordering transforms the six emitted triangle vertices.
                EntityVertexCompatibility.expandQuads(first, 0, 1);
                method.invoke(
                        null,
                        first.duplicate().clear().order(ByteOrder.nativeOrder()),
                        draw,
                        snapshot);
                var unique =
                        second.duplicate()
                                .clear()
                                .limit(4 * EntityFrame.STRIDE)
                                .order(ByteOrder.nativeOrder());
                method.invoke(null, unique, draw, snapshot);
                EntityVertexCompatibility.expandQuads(second, 0, 1);
                assertEquals(-1, first.clear().mismatch(second.clear()), pass.toString());
            } finally {
                MemoryUtil.memFree(first);
                MemoryUtil.memFree(second);
            }
        }
    }
}
