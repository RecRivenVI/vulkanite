package me.cortex.vulkanite.mixin.minecraft;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import java.util.Collection;
import java.util.HashMap;
import me.cortex.vulkanite.client.rendering.interop.PublicIndexedDrawCapture;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Borrows public RenderPearl draw data after its real uploader and before the backend draw. */
@Mixin(FrontendRenderPass.class)
public abstract class MixinFrontendRenderPass {
    @Shadow @Final private RenderPassBackend backend;
    @Shadow private FrontendRenderPipeline boundPipeline;
    @Shadow @Final protected HashMap<String, Object> uniforms;

    @Inject(method = "drawMultipleIndexed", at = @At("HEAD"))
    private void vulkanite$beginPublicBatch(
            Collection<?> draws,
            GpuBuffer defaultIndex,
            IndexType defaultType,
            Collection<String> requiredUniforms,
            Object state,
            CallbackInfo ci) {
        PublicIndexedDrawCapture.beginBatch();
    }

    @Inject(
            method = "drawMultipleIndexed",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lcom/mojang/renderpearl/backend/api/RenderPassBackend;drawIndexed(IIIII)V"))
    private void vulkanite$observePublicIndexedDraw(
            Collection<?> draws,
            GpuBuffer defaultIndex,
            IndexType defaultType,
            Collection<String> requiredUniforms,
            Object state,
            CallbackInfo ci,
            @Local(index = 9) RenderPass.Draw<?> draw,
            @Local(index = 11) IndexType indexType,
            @Local(index = 12) GpuBuffer indexBuffer) {
        PublicIndexedDrawCapture.observe(
                draw, indexBuffer, indexType, backend, boundPipeline, uniforms);
    }

    @Inject(method = "drawMultipleIndexed", at = @At("RETURN"))
    private void vulkanite$endPublicBatch(
            Collection<?> draws,
            GpuBuffer defaultIndex,
            IndexType defaultType,
            Collection<String> requiredUniforms,
            Object state,
            CallbackInfo ci) {
        PublicIndexedDrawCapture.endBatch();
    }
}
