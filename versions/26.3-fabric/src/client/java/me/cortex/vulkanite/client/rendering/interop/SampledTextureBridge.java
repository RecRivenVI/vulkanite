package me.cortex.vulkanite.client.rendering.interop;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL43C.glCopyImageSubData;
import static org.lwjgl.vulkan.VK10.*;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import java.util.IdentityHashMap;
import java.util.List;
import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.compat.SpritePaddingAccess;
import me.cortex.vulkanite.lib.memory.VGImage;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * Read-only Vulkan views of Minecraft-owned textures. Original GL names/storage stay untouched.
 * Copy once on first PT use, then only uploaded rectangles, including atlas animation mips.
 * Attachments refresh only after an actual render/clear. No CPU readback. Render thread only; the
 * existing GL/VK frame semaphore covers these GL copies.
 */
public final class SampledTextureBridge {
    private static final IdentityHashMap<GpuTexture, Entry> IMAGES = new IdentityHashMap<>();
    private static AnimationScope animationScope;

    private static final class AnimationScope {
        final GpuTexture source;
        boolean drawn;

        AnimationScope(GpuTexture source) {
            this.source = source;
        }
    }

    private static final class Entry {
        final VGImage image;
        boolean dirty;
        List<? extends TextureAtlasSprite> atlasSprites;
        List<? extends TextureAtlasSprite> animatedSprites;

        Entry(VGImage image) {
            this.image = image;
        }
    }

    public static VGImage get(GlTexture source) {
        if (source.isClosed()
                || source.getFormat() != GpuFormat.RGBA8_UNORM
                || source.getDepthOrLayers() != 1
                || (source.usage() & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0) return null;
        Entry entry = IMAGES.get(source);
        if (entry == null) {
            var ctx = Vulkanite.getInstance().getCtx();
            var image =
                    ctx.memory.createSharedImage(
                            source.getWidth(0),
                            source.getHeight(0),
                            source.getMipLevels(),
                            VK_FORMAT_R8G8B8A8_UNORM,
                            GL_RGBA8,
                            VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            try {
                ctx.cmd.executeWait(
                        cmd ->
                                cmd.encodeImageTransition(
                                        image,
                                        VK_IMAGE_LAYOUT_UNDEFINED,
                                        VK_IMAGE_LAYOUT_GENERAL,
                                        VK_IMAGE_ASPECT_COLOR_BIT,
                                        VK_REMAINING_MIP_LEVELS));
                copyAll(source, image);
            } catch (Throwable failure) {
                image.free();
                throw failure;
            }
            entry = new Entry(image);
            IMAGES.put(source, entry);
        } else if (entry.dirty) {
            copyAll(source, entry.image);
            entry.dirty = false;
        }
        return entry.image;
    }

    private static void copyAll(GlTexture source, VGImage image) {
        for (int mip = 0; mip < source.getMipLevels(); mip++)
            copy(source, image, mip, 0, 0, source.getWidth(mip), source.getHeight(mip));
    }

    private static void copy(
            GlTexture source, VGImage image, int mip, int x, int y, int width, int height) {
        glCopyImageSubData(
                source.glId(),
                GL_TEXTURE_2D,
                mip,
                x,
                y,
                0,
                image.glId,
                GL_TEXTURE_2D,
                mip,
                x,
                y,
                0,
                width,
                height,
                1);
    }

    public static void updated(GpuTexture source, int mip, int x, int y, int width, int height) {
        Entry entry = IMAGES.get(source);
        if (entry != null) copy((GlTexture) source, entry.image, mip, x, y, width, height);
    }

    public static void rendered(GpuTexture source) {
        Entry entry = IMAGES.get(source);
        if (animationScope != null && animationScope.source == source) {
            animationScope.drawn = true;
            return;
        }
        if (entry != null) entry.dirty = true;
    }

    /**
     * 26.3 draws animations into atlases. Only that bounded draw scope can use sprite rectangles.
     */
    public static void animate(
            GpuTexture source, List<? extends TextureAtlasSprite> sprites, Runnable draw) {
        Entry entry = IMAGES.get(source);
        if (entry == null) {
            draw.run();
            return;
        }
        if (entry.atlasSprites != sprites) {
            entry.atlasSprites = sprites;
            entry.animatedSprites =
                    sprites.stream().filter(TextureAtlasSprite::isAnimated).toList();
        }
        AnimationScope previous = animationScope;
        AnimationScope scope = new AnimationScope(source);
        animationScope = scope;
        try {
            draw.run();
            if (scope.drawn)
                for (var sprite : entry.animatedSprites) {
                    int padding = ((SpritePaddingAccess) sprite).vulkanite$padding();
                    for (int mip = 0; mip < source.getMipLevels(); mip++) {
                        int width = (sprite.contents().width() + padding * 2) >> mip;
                        int height = (sprite.contents().height() + padding * 2) >> mip;
                        if (width > 0 && height > 0)
                            copy(
                                    (GlTexture) source,
                                    entry.image,
                                    mip,
                                    sprite.getX() >> mip,
                                    sprite.getY() >> mip,
                                    width,
                                    height);
                    }
                }
        } catch (RuntimeException | Error failure) {
            entry.dirty = true;
            throw failure;
        } finally {
            animationScope = previous;
        }
    }

    public static void closed(GpuTexture source) {
        Entry entry = IMAGES.remove(source);
        if (entry != null) Vulkanite.getInstance().addSyncedCallback(entry.image::free);
    }

    /** After all Iris dimension pipelines and their views have been destroyed. */
    public static void closeAll() {
        for (var entry : IMAGES.values())
            Vulkanite.getInstance().addSyncedCallback(entry.image::free);
        IMAGES.clear();
    }
}
