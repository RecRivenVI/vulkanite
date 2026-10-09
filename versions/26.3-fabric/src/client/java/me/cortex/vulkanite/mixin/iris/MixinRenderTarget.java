package me.cortex.vulkanite.mixin.iris;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.vulkan.VK10.*;

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.compat.IRenderTargetVkGetter;
import me.cortex.vulkanite.compat.IrisFormatConverter;
import me.cortex.vulkanite.compat.PackResourceScope;
import me.cortex.vulkanite.lib.memory.VGImage;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.targets.RenderTarget;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RenderTarget.class, remap = false)
public abstract class MixinRenderTarget implements IRenderTargetVkGetter {
    @Shadow @Final private InternalTextureFormat internalFormat;
    @Shadow @Final @Mutable private int mainTexture;
    @Shadow @Final @Mutable private int altTexture;
    @Shadow private String name;
    @Shadow private int width;
    @Shadow private int height;
    @Shadow private boolean allowsLinear;

    @Shadow
    protected abstract void setupTexture(
            int texture, int width, int height, boolean linear, boolean alt);

    @Shadow
    protected abstract void requireValid();

    @Shadow
    protected abstract void resizeTexture(int texture, int width, int height, boolean alt);

    @Unique private VGImage vgMainTexture;
    @Unique private VGImage vgAltTexture;

    @Unique
    private VGImage allocateSharedTexture() {
        int format = internalFormat.getGlFormat();
        if (format == GL_RGBA) format = GL_RGBA8;
        var ctx = Vulkanite.getInstance().getCtx();
        var image =
                ctx.memory.createSharedImage(
                        width,
                        height,
                        1,
                        IrisFormatConverter.getVkFormatFromGl(internalFormat),
                        format,
                        VK_IMAGE_USAGE_STORAGE_BIT
                                | VK_IMAGE_USAGE_TRANSFER_DST_BIT
                                | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
                                | VK_IMAGE_USAGE_SAMPLED_BIT,
                        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        ctx.cmd.executeWait(
                cmd ->
                        cmd.encodeImageTransition(
                                image,
                                VK_IMAGE_LAYOUT_UNDEFINED,
                                VK_IMAGE_LAYOUT_GENERAL,
                                VK_IMAGE_ASPECT_COLOR_BIT,
                                VK_REMAINING_MIP_LEVELS));
        return image;
    }

    @Redirect(
            method = "<init>",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lcom/mojang/renderpearl/backend/opengl/GlStateManager;_genTexture()I"))
    private int createSharedTexture() {
        if (!vulkanite$shareThisTarget()) return GlStateManager._genTexture();
        var image = allocateSharedTexture();
        if (vgMainTexture == null) vgMainTexture = image;
        else vgAltTexture = image;
        return image.glId;
    }

    @Unique
    private boolean vulkanite$shareThisTarget() {
        int targetPrefix = PackResourceScope.current().sharedColorTargets();
        if (targetPrefix == 0 || name == null || !name.startsWith("colortex")) return false;
        try {
            int target = Integer.parseInt(name.substring("colortex".length()));
            return target >= 0 && target < targetPrefix;
        } catch (NumberFormatException invalidTargetName) {
            return false;
        }
    }

    @Redirect(
            method = "setupTexture",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/irisshaders/iris/targets/RenderTarget;resizeTexture(IIIZ)V"))
    private void keepSharedStorage(
            RenderTarget target, int texture, int width, int height, boolean alt) {
        // External-memory textures already have immutable storage.
        if (vgMainTexture == null) resizeTexture(texture, width, height, alt);
    }

    @Inject(method = "resize(II)V", at = @At("HEAD"), cancellable = true)
    private void resizeShared(int width, int height, CallbackInfo ci) {
        if (vgMainTexture == null) return;
        requireValid();
        glFinish();
        Vulkanite.getInstance().getCtx().cmd.waitQueueIdle(0);
        vgMainTexture.free();
        vgAltTexture.free();
        this.width = width;
        this.height = height;
        vgMainTexture = allocateSharedTexture();
        vgAltTexture = allocateSharedTexture();
        mainTexture = vgMainTexture.glId;
        altTexture = vgAltTexture.glId;
        setupTexture(mainTexture, width, height, allowsLinear, false);
        setupTexture(altTexture, width, height, allowsLinear, true);
        ci.cancel();
    }

    @Redirect(
            method = "destroy",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lcom/mojang/renderpearl/backend/opengl/GlStateManager;_deleteTexture(I)V"))
    private void destroySharedTexture(int texture) {
        var image = texture == mainTexture ? vgMainTexture : vgAltTexture;
        if (image == null) GlStateManager._deleteTexture(texture);
        else Vulkanite.getInstance().addSyncedCallback(image::free);
    }

    public VGImage getMain() {
        return vgMainTexture;
    }

    public VGImage getAlt() {
        return vgAltTexture;
    }
}
