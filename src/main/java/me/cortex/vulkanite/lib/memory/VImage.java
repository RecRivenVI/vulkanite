package me.cortex.vulkanite.lib.memory;

import java.lang.ref.Cleaner;

public class VImage {
    protected VmaAllocator.ImageAllocation allocation;
    public final int width;
    public final int height;
    public final int depth;
    public final int mipLayers;
    public final int format;

    public final int dimensions;
    private final java.util.Set<me.cortex.vulkanite.lib.other.VImageView> views = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    public void addView(me.cortex.vulkanite.lib.other.VImageView view) {
        if (allocation == null) throw new IllegalStateException("View of released image");
        views.add(view);
    }
    public void removeView(me.cortex.vulkanite.lib.other.VImageView view) { views.remove(view); }

    VImage(VmaAllocator.ImageAllocation allocation, int dimensions, int width, int height, int depth, int mipLayers, int format) {
        this.allocation = allocation;
        this.width = width;
        this.height = height;
        this.mipLayers = mipLayers;
        this.format = format;
        this.depth = depth;

        // Extents do not determine image type: a 1x1 texture can still be 2D.
        this.dimensions = dimensions;
    }

    public void free() {
        if (allocation == null) return;
        for (var view : java.util.List.copyOf(views)) view.free();
        allocation.free();
        allocation = null;
    }

    public long image() {
        return allocation.image;
    }
}
