package me.cortex.vulkanite.mixin.iris;

import me.cortex.vulkanite.client.rendering.interop.SampledTextureBridge;
import me.cortex.vulkanite.compat.ActivePack;
import net.irisshaders.iris.pipeline.PipelineManager;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = PipelineManager.class, remap = false)
public abstract class MixinPipelineManager {
    @Inject(method = "preparePipeline", at = @At("RETURN"))
    private void select(CallbackInfoReturnable<WorldRenderingPipeline> cir) {
        ActivePack.select(cir.getReturnValue());
    }

    @Inject(method = "destroyPipeline", at = @At("HEAD"))
    private void deactivate(CallbackInfo ci) {
        ActivePack.select(null);
    }

    @Inject(method = "destroyPipeline", at = @At("RETURN"))
    private void releaseAssets(CallbackInfo ci) {
        SampledTextureBridge.closeAll();
    }
}
