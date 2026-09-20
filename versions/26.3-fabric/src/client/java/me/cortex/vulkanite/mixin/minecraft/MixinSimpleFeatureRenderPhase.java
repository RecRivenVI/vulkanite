package me.cortex.vulkanite.mixin.minecraft;

import me.cortex.vulkanite.client.rendering.interop.DynamicSubmitOrigins;
import net.minecraft.client.renderer.feature.phase.SimpleFeatureRenderPhase;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SimpleFeatureRenderPhase.class)
public abstract class MixinSimpleFeatureRenderPhase {
    @Inject(method = "submit", at = @At("HEAD"))
    private void vulkanite$rememberNode(SubmitNode node, CallbackInfo ci) {
        DynamicSubmitOrigins.remember(node, this);
    }
}
