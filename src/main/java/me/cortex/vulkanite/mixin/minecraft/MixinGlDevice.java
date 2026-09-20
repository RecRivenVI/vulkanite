package me.cortex.vulkanite.mixin.minecraft;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.opengl.FrameBufferCache;
import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.lib.memory.SharedGlTexture;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import static org.lwjgl.opengl.GL11C.GL_RGBA8;
import static org.lwjgl.vulkan.VK10.*;

@Mixin(targets = "com.mojang.renderpearl.backend.opengl.GlDevice")
public abstract class MixinGlDevice {
    @Shadow @Final private FrameBufferCache frameBufferCache;

    @Inject(method = "createTexture", at = @At("HEAD"), cancellable = true)
    private void shareTexture(String label, int usage, GpuFormat format, int width, int height,
                              int depthOrLayers, int mipLevels, CallbackInfoReturnable<GpuTexture> cir) {
        if (format != GpuFormat.RGBA8_UNORM || depthOrLayers != 1 || (usage & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0) return;
        var ctx = Vulkanite.INSTANCE.getCtx();
        var image = ctx.memory.createSharedImage(width, height, mipLevels, VK_FORMAT_R8G8B8A8_UNORM,
                GL_RGBA8, VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        ctx.cmd.executeWait(cmd -> cmd.encodeImageTransition(image, VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_ASPECT_COLOR_BIT, VK_REMAINING_MIP_LEVELS));
        cir.setReturnValue(new SharedGlTexture(usage, label == null ? "Vulkanite shared texture" : label,
                format, width, height, mipLevels, frameBufferCache, image));
    }
}
