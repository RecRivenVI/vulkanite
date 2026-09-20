package me.cortex.vulkanite.mixin.minecraft;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.opengl.FrameBufferAttachment;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import com.mojang.renderpearl.backend.opengl.GlTextureView;
import java.nio.ByteBuffer;
import java.util.List;
import me.cortex.vulkanite.client.rendering.interop.PublicIndexedDrawCapture;
import me.cortex.vulkanite.client.rendering.interop.SampledTextureBridge;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observe successful writes; never replace RenderPearl allocation or upload behavior. */
@Mixin(targets = "com.mojang.renderpearl.backend.opengl.GlCommandEncoder")
public abstract class MixinGlCommandEncoder {
    @Shadow @Final private List<FrameBufferAttachment> renderPassColorTextures;

    @Inject(method = "submitRenderPass", at = @At("RETURN"))
    private void rendered(CallbackInfo ci) {
        for (var attachment : renderPassColorTextures) {
            if (attachment instanceof GlTexture texture) SampledTextureBridge.rendered(texture);
            else if (attachment instanceof GlTextureView view)
                SampledTextureBridge.rendered(view.texture());
        }
    }

    @Inject(method = "writeToBuffer", at = @At("RETURN"))
    private void vulkanite$bufferWritten(
            GpuBufferSlice destination, ByteBuffer data, CallbackInfo ci) {
        PublicIndexedDrawCapture.bufferWritten(destination.buffer(), false);
    }

    @Inject(method = "copyToBuffer", at = @At("RETURN"))
    private void vulkanite$bufferCopied(
            GpuBufferSlice source, GpuBufferSlice destination, CallbackInfo ci) {
        PublicIndexedDrawCapture.bufferWritten(destination.buffer(), false);
    }

    @Inject(method = "clearColorTexture", at = @At("RETURN"))
    private void cleared(GpuTexture texture, Vector4fc color, CallbackInfo ci) {
        SampledTextureBridge.rendered(texture);
    }

    @Inject(
            method =
                    "clearColorAndDepthTextures(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lorg/joml/Vector4fc;Lcom/mojang/renderpearl/api/textures/GpuTexture;D)V",
            at = @At("RETURN"))
    private void clearedAll(
            GpuTexture texture, Vector4fc color, GpuTexture depth, double value, CallbackInfo ci) {
        SampledTextureBridge.rendered(texture);
    }

    @Inject(
            method =
                    "clearColorAndDepthTextures(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lorg/joml/Vector4fc;Lcom/mojang/renderpearl/api/textures/GpuTexture;DIIIII)V",
            at = @At("RETURN"))
    private void clearedArea(
            GpuTexture texture,
            Vector4fc color,
            GpuTexture depth,
            double value,
            int x,
            int y,
            int width,
            int height,
            int mip,
            CallbackInfo ci) {
        SampledTextureBridge.rendered(texture);
    }

    @Inject(method = "writeToTexture", at = @At("RETURN"))
    private void uploaded(
            GpuTexture destination,
            ByteBuffer source,
            int mip,
            int layer,
            int x,
            int y,
            int width,
            int height,
            CallbackInfo ci) {
        SampledTextureBridge.updated(destination, mip, x, y, width, height);
    }

    @Inject(method = "copyBufferToTexture", at = @At("RETURN"))
    private void uploadedBuffer(
            GpuBufferSlice source,
            int sourceX,
            int sourceY,
            int sourceWidth,
            int sourceHeight,
            GpuTexture destination,
            int x,
            int y,
            int width,
            int height,
            int mip,
            int layer,
            CallbackInfo ci) {
        SampledTextureBridge.updated(destination, mip, x, y, width, height);
    }

    @Inject(method = "copyTextureToTexture", at = @At("RETURN"))
    private void copied(
            GpuTexture source,
            GpuTexture destination,
            int mip,
            int x,
            int y,
            int sourceX,
            int sourceY,
            int width,
            int height,
            CallbackInfo ci) {
        SampledTextureBridge.updated(destination, mip, x, y, width, height);
    }
}
