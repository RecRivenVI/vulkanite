package me.cortex.vulkanite.mixin.minecraft;

import com.mojang.renderpearl.backend.opengl.GlTexture;
import me.cortex.vulkanite.client.rendering.interop.SampledTextureBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GlTexture.class)
public abstract class MixinGlTexture {
    @Inject(method = "destroyImmediately", at = @At("HEAD"))
    private void releaseView(CallbackInfo ci) {
        SampledTextureBridge.closed((GlTexture) (Object) this);
    }
}
