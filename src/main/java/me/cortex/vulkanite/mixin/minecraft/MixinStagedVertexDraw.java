package me.cortex.vulkanite.mixin.minecraft;

import com.mojang.blaze3d.vertex.MeshData;
import me.cortex.vulkanite.client.rendering.interop.ProducedDraws;
import me.cortex.vulkanite.client.rendering.interop.ProducedSubmitRanges;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observes completed producer MeshData before StagedVertexBuffer uploads its slices. */
@Mixin(targets = "net.minecraft.client.renderer.StagedVertexBuffer$Draw")
public abstract class MixinStagedVertexDraw {
    @Inject(method = "append", at = @At("HEAD"))
    private void vulkanite$observeProducedMesh(MeshData mesh, CallbackInfo ci) {
        ProducedSubmitRanges.Summary ranges;
        try { ranges=ProducedSubmitRanges.onMesh(this,mesh.drawState().vertexCount()); }
        catch (Throwable failure) {
            ProducedDraws.fail(failure);
            ranges=new ProducedSubmitRanges.Summary(false,java.util.List.of());
        }
        ProducedDraws.observe(this,mesh,ranges);
    }
}
