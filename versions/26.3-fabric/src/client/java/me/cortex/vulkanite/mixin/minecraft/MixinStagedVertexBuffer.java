package me.cortex.vulkanite.mixin.minecraft;

import com.mojang.blaze3d.vertex.VertexConsumer;
import me.cortex.vulkanite.client.rendering.interop.ProducedDraws;
import me.cortex.vulkanite.client.rendering.interop.ProducedSubmitRanges;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(StagedVertexBuffer.class)
public abstract class MixinStagedVertexBuffer {
    @Inject(method = "getVertexBuilder", at = @At("RETURN"))
    private void vulkanite$trackNormalBuilder(
            StagedVertexBuffer.Draw draw, CallbackInfoReturnable<VertexConsumer> cir) {
        try {
            ProducedSubmitRanges.trackBuilder(draw, cir.getReturnValue());
        } catch (Throwable failure) {
            ProducedDraws.fail(failure);
            ProducedSubmitRanges.end();
        }
    }
}
