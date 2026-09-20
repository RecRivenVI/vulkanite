package me.cortex.vulkanite.mixin.minecraft;

import com.llamalad7.mixinextras.sugar.Local;
import me.cortex.vulkanite.client.rendering.interop.ProducedDraws;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.QuadParticleFeatureRenderer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;

/** Records each normal producer layer before another layer can flush its Draw. */
@Mixin(QuadParticleFeatureRenderer.class)
public abstract class MixinQuadParticleFeatureRenderer {
    @Inject(method = "prepareGroup", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/renderer/StagedVertexBuffer;requestIndexCount(Lnet/minecraft/client/renderer/StagedVertexBuffer$Draw;)V"))
    private void vulkanite$rememberParticleLayer(FeatureFrameContext context, List<?> submits,
                                                  boolean translucent, CallbackInfo ci,
                                                  @Local(index = 6) Map<SingleQuadParticle.Layer, AbstractTexture> textures,
                                                  @Local(index = 11) SingleQuadParticle.Layer layer,
                                                  @Local(index = 12) StagedVertexBuffer.Draw draw) {
        var texture = textures.get(layer);
        if (texture == null) {
            ProducedDraws.fail(new IllegalStateException("Normal particle layer has no producer texture"));
            return;
        }
        ProducedDraws.registerParticle(draw, layer, texture);
    }
}
