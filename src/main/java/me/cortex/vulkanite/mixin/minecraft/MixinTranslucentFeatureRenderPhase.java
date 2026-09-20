package me.cortex.vulkanite.mixin.minecraft;

import me.cortex.vulkanite.client.rendering.interop.DynamicSubmitOrigins;
import net.minecraft.client.renderer.feature.phase.TranslucentFeatureRenderPhase;
import net.minecraft.client.renderer.feature.submit.TranslucentSubmit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TranslucentFeatureRenderPhase.class)
public abstract class MixinTranslucentFeatureRenderPhase {
    @Inject(method = "submit(Lnet/minecraft/client/renderer/feature/submit/TranslucentSubmit;)V", at = @At("HEAD"))
    private void vulkanite$rememberNode(TranslucentSubmit node, CallbackInfo ci) {
        DynamicSubmitOrigins.remember(node, this);
    }
}
