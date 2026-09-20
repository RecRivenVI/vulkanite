package me.cortex.vulkanite.lib.memory;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.backend.opengl.FrameBufferCache;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import me.cortex.vulkanite.compat.IVGImage;

/** Shared backing for RenderPearl's OpenGL texture ownership. */
public final class SharedGlTexture extends GlTexture implements IVGImage {
    private final VGImage image;
    public SharedGlTexture(int usage, String label, GpuFormat format, int width, int height,
                           int mipLevels, FrameBufferCache cache, VGImage image) {
        super(usage, label, format, width, height, 1, mipLevels, image.glId, cache);
        this.image = image;
    }
    @Override public void setVGImage(VGImage image) { throw new UnsupportedOperationException("Shared texture backing is immutable"); }
    @Override public VGImage getVGImage() { return image; }
}

