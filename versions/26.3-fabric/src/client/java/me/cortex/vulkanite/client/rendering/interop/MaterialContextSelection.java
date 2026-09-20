package me.cortex.vulkanite.client.rendering.interop;

import me.cortex.vulkanite.compat.IrisEntityVertexIds;

/** Selects the real submit category before interpreting Iris's three raw IDs. */
public final class MaterialContextSelection {
    public enum Category {
        ITEM,
        BLOCK_ENTITY,
        ENTITY,
        UNKNOWN
    }

    private MaterialContextSelection() {}

    public static int select(
            IrisEntityVertexIds.VertexIds ids, Category category, int producerItemId) {
        if (producerItemId < -1 || producerItemId >= 0xffff)
            throw new IllegalArgumentException(
                    "Iris producer item ID must fit uint16 ABI or be -1");
        if (ids == null) ids = new IrisEntityVertexIds.VertexIds(0xffff, 0xffff, 0xffff);
        return switch (category) {
            case ITEM -> positive(ids.rawItem()) ? ids.rawItem() : Math.max(producerItemId, 0);
            case BLOCK_ENTITY -> ids.rawBlockEntity() == 0xffff ? 0 : ids.rawBlockEntity();
            case ENTITY -> ids.rawEntity() == 0xffff ? 0 : ids.rawEntity();
            case UNKNOWN -> {
                // A cleared zero may be an explicitly mapped ID0. Without a
                // producer category, another channel cannot override it.
                if (ids.rawItem() == 0 || ids.rawBlockEntity() == 0 || ids.rawEntity() == 0)
                    yield 0;
                if (positive(ids.rawItem())) yield ids.rawItem();
                if (positive(ids.rawBlockEntity())) yield ids.rawBlockEntity();
                yield positive(ids.rawEntity()) ? ids.rawEntity() : 0;
            }
        };
    }

    private static boolean positive(int value) {
        return value > 0 && value < 0xffff;
    }
}
