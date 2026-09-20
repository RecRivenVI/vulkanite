package me.cortex.vulkanite.mixin.iris;

import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.compat.IVGImage;
import me.cortex.vulkanite.compat.PackResourceScope;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import me.cortex.vulkanite.lib.memory.VGImage;
import me.cortex.vulkanite.compat.IrisFormatConverter;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.texture.GlTexture;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.shaderpack.texture.TextureFilteringData;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.vulkan.VK10.*;

@Mixin(value = GlTexture.class, remap = false)
public abstract class MixinGlTexture extends MixinGlResource implements IVGImage {
    @Unique private VGImage sharedImage;

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/backend/opengl/GlStateManager;_genTexture()I"))
    private static int redirectGen() {
        return PackResourceScope.current().sharedCustomTextures() ? -1 : GlStateManager._genTexture();
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/gl/texture/GlTexture;getGlId()I", ordinal = 0))
    private int redirectTextureCreation(GlTexture instance, TextureType target, int sizeX, int sizeY, int sizeZ, int internalFormat, int format, int pixelType, byte[] pixels, TextureFilteringData filteringData) {
        if (!PackResourceScope.current().sharedCustomTextures()) return this.getGlId();
        // Before getting the texture id, create the texture that wasn't created earlier

        InternalTextureFormat textureFormat = IrisFormatConverter.findFormatFromGlFormat(internalFormat);
        int vkFormat = IrisFormatConverter.getVkFormatFromGl(textureFormat);

        // Clamp y,z to 1 as per VK spec
        sizeY = Math.max(sizeY, 1);
        sizeZ = Math.max(sizeZ, 1);

        sharedImage = Vulkanite.getInstance().getCtx().memory
            .createSharedImage(
                    switch (target) {
                        case TEXTURE_1D -> 1;
                        case TEXTURE_2D, TEXTURE_RECTANGLE -> 2;
                        case TEXTURE_3D -> 3;
                    },
                    sizeX,
                    sizeY,
                    sizeZ,
                    1,
                    vkFormat,
                    textureFormat.getGlFormat(),
                    VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
        );

        Vulkanite.getInstance().getCtx().cmd.executeWait(cmdbuf -> {
            cmdbuf.encodeImageTransition(sharedImage, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_ASPECT_COLOR_BIT, VK_REMAINING_MIP_LEVELS);
        });

        this.setGlId(sharedImage.glId);

        return sharedImage.glId;
    }

    @Redirect(method="<init>", at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/gl/texture/TextureType;apply(IIIIIIILjava/nio/ByteBuffer;)V"))
    private void redirectUpload(TextureType instance, int glId, int width, int height, int depth, int internalFormat, int format, int pixelType, ByteBuffer data) {
        if (sharedImage == null) {
            instance.apply(glId, width, height, depth, internalFormat, format, pixelType, data);
            return;
        }
        int target = instance.getGlType();

        RenderSystem.assertOnRenderThread();
        IrisRenderSystem.bindTextureForSetup(target, glId);

        switch (instance) {
            case TEXTURE_1D:
                GL30.glTexSubImage1D(target, 0, 0, width, format, pixelType, data);
                break;
            case TEXTURE_2D, TEXTURE_RECTANGLE:
                GL30.glTexSubImage2D(target, 0, 0, 0, width, height, format, pixelType, data);
                break;
            case TEXTURE_3D:
                GL30.glTexSubImage3D(target, 0, 0, 0, 0, width, height, depth, format, pixelType, data);
                break;
        }
    }

    @Inject(method = "destroyInternal", at = @At("HEAD"), cancellable = true)
    private void destroyShared(CallbackInfo ci) {
        if (sharedImage == null) return;
        Vulkanite.getInstance().addSyncedCallback(sharedImage::free);
        sharedImage = null;
        ci.cancel();
    }

    public VGImage getVGImage() {
        return sharedImage;
    }
}
