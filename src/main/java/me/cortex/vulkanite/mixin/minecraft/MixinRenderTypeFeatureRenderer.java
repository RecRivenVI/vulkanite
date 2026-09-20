package me.cortex.vulkanite.mixin.minecraft;

import com.mojang.blaze3d.vertex.VertexConsumer;
import me.cortex.vulkanite.client.rendering.EntityCapture;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.List;

@Mixin(RenderTypeFeatureRenderer.class)
public abstract class MixinRenderTypeFeatureRenderer implements me.cortex.vulkanite.compat.FeatureRendererAccess {
    @Invoker("buildGroup")
    public abstract void vulkanite$buildGroup(FeatureFrameContext context, List<? extends SubmitNode> submits);

    @Inject(method = "getVertexBuilder", at = @At("HEAD"), cancellable = true)
    private void captureVertices(RenderType type, CallbackInfoReturnable<VertexConsumer> cir) {
        var capture = EntityCapture.ACTIVE.get();
        if (capture != null) cir.setReturnValue(capture.getBuffer(type));
    }
}
