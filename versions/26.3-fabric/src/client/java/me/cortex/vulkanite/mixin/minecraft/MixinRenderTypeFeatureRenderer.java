package me.cortex.vulkanite.mixin.minecraft;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.List;
import me.cortex.vulkanite.client.rendering.interop.ProducedDraws;
import me.cortex.vulkanite.client.rendering.interop.ProducedSubmitRanges;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(RenderTypeFeatureRenderer.class)
public abstract class MixinRenderTypeFeatureRenderer {
    @WrapOperation(
            method = "prepareGroup",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/renderer/feature/RenderTypeFeatureRenderer;buildGroup(Lnet/minecraft/client/renderer/feature/FeatureFrameContext;Ljava/util/List;)V"))
    private void vulkanite$observeNormalSubmits(
            RenderTypeFeatureRenderer<?> renderer,
            FeatureFrameContext context,
            List<? extends SubmitNode> submits,
            Operation<Void> original) {
        if (!ProducedSubmitRanges.enabled()) {
            original.call(renderer, context, submits);
            return;
        }
        try {
            original.call(renderer, context, ProducedSubmitRanges.observeList(submits));
        } finally {
            try {
                ProducedSubmitRanges.endSubmit();
            } catch (Throwable failure) {
                ProducedDraws.fail(failure);
                ProducedSubmitRanges.end();
            }
        }
    }
}
