package me.cortex.vulkanite.mixin.minecraft;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.opengl.GlRenderPipeline;
import me.cortex.vulkanite.client.rendering.interop.GlRenderPipelineLayoutAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Keep immutable copies of the bindings from which RenderPearl built this pipeline's VAO. */
@Mixin(GlRenderPipeline.class)
public abstract class MixinGlRenderPipelineLayout implements GlRenderPipelineLayoutAccess {
    @Unique private List<BackendRenderPipeline.CreateInfo.VertexBuffer> vulkanite$vertexBuffers;
    @Unique private List<BackendRenderPipeline.CreateInfo.AttribBinding> vulkanite$attribBindings;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void vulkanite$captureLayout(CallbackInfo ci,
                                         @Local(index = 2) BackendRenderPipeline.CreateInfo info) {
        vulkanite$vertexBuffers = List.copyOf(info.vertexBuffers());
        vulkanite$attribBindings = List.copyOf(info.attribBindings());
    }

    @Override
    public List<BackendRenderPipeline.CreateInfo.VertexBuffer> vulkanite$vertexBuffers() {
        return vulkanite$vertexBuffers;
    }

    @Override
    public List<BackendRenderPipeline.CreateInfo.AttribBinding> vulkanite$attribBindings() {
        return vulkanite$attribBindings;
    }
}
