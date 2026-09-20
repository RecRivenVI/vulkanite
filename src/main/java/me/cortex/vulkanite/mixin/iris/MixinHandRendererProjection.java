package me.cortex.vulkanite.mixin.iris;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import me.cortex.vulkanite.client.rendering.interop.ProducedDraws;
import net.irisshaders.iris.pathways.HandRenderer;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Observes Iris's actual hand projection before its own GPU buffer upload. */
@Mixin(HandRenderer.class)
public abstract class MixinHandRendererProjection {
    @WrapOperation(method = "setupGlState", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"))
    private GpuBufferSlice vulkanite$observeHandProjection(ProjectionMatrixBuffer buffer,
                                                            Matrix4f projection,
                                                            Operation<GpuBufferSlice> original) {
        try { ProducedDraws.handProjection(projection); }
        catch (Throwable failure) { ProducedDraws.fail(failure); }
        return original.call(buffer,projection);
    }
}
