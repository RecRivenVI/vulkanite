package me.cortex.vulkanite.mixin.minecraft;

import me.cortex.vulkanite.client.rendering.interop.DynamicSubmitOrigins;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.feature.phase.SimpleFeatureRenderPhase;
import net.minecraft.client.renderer.feature.phase.TranslucentFeatureRenderPhase;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Marks decorative phases from the producer's own phase graph. */
@Mixin(SubmitNodeCollection.class)
public abstract class MixinSubmitNodeCollection {
    @Shadow @Final public SimpleFeatureRenderPhase outline;
    @Shadow @Final public SimpleFeatureRenderPhase alwaysOnTopGizmos;
    @Shadow @Final public SimpleFeatureRenderPhase shadows;
    @Shadow @Final public SimpleFeatureRenderPhase nameTags;
    @Shadow @Final public SimpleFeatureRenderPhase texts;
    @Shadow @Final public SimpleFeatureRenderPhase shapeOutlines;
    @Shadow @Final public SimpleFeatureRenderPhase breakingOverlay;
    @Shadow @Final public SimpleFeatureRenderPhase translucentGizmos;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void vulkanite$classifyDecorations(boolean improvedTransparency,
                                                TranslucentFeatureRenderPhase seeThrough, CallbackInfo ci) {
        DynamicSubmitOrigins.classifyPhase(outline, DynamicSubmitOrigins.Kind.OUTLINE);
        DynamicSubmitOrigins.classifyPhase(shapeOutlines, DynamicSubmitOrigins.Kind.OUTLINE);
        DynamicSubmitOrigins.classifyPhase(alwaysOnTopGizmos, DynamicSubmitOrigins.Kind.GIZMO);
        DynamicSubmitOrigins.classifyPhase(translucentGizmos, DynamicSubmitOrigins.Kind.GIZMO);
        DynamicSubmitOrigins.classifyPhase(shadows, DynamicSubmitOrigins.Kind.SHADOW_PATCH);
        DynamicSubmitOrigins.classifyPhase(nameTags, DynamicSubmitOrigins.Kind.NAME_TAG);
        DynamicSubmitOrigins.classifyPhase(texts, DynamicSubmitOrigins.Kind.TEXT);
        DynamicSubmitOrigins.classifyPhase(breakingOverlay, DynamicSubmitOrigins.Kind.BREAKING_OVERLAY);
    }
}
