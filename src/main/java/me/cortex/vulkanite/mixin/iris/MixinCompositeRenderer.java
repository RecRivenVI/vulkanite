package me.cortex.vulkanite.mixin.iris;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.llamalad7.mixinextras.sugar.Local;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import me.cortex.vulkanite.compat.VulkanitePipelineAccess;
import net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder;
import net.irisshaders.iris.gl.image.GlImage;
import net.irisshaders.iris.gl.texture.TextureAccess;
import net.irisshaders.iris.pipeline.CompositePass;
import net.irisshaders.iris.pipeline.CompositeRenderer;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.pathways.CenterDepthSampler;
import net.irisshaders.iris.shaderpack.programs.ComputeSource;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.shadows.ShadowRenderTargets;
import net.irisshaders.iris.targets.BufferFlipper;
import net.irisshaders.iris.targets.RenderTargets;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;
import java.util.function.Supplier;

/** Inserts one pack-declared Vulkan segment between completed Iris composite passes. */
@Mixin(value = CompositeRenderer.class, remap = false)
public abstract class MixinCompositeRenderer {
    @Shadow @Final private ImmutableList<?> passes;
    @Shadow @Final private CompositePass compositePass;
    @Shadow @Final private WorldRenderingPipeline pipeline;
    @Unique private Set<Integer> vulkanite$finalCurrentAlt = Set.of();

    @Inject(method = "<init>", at = @At("TAIL"))
    private void vulkanite$captureFinalSides(WorldRenderingPipeline pipeline, CompositePass phase,
            PackDirectives directives, ProgramSource[] programs, ComputeSource[][] computes,
            RenderTargets targets, ShaderStorageBufferHolder buffers, TextureAccess noise,
            FrameUpdateNotifier notifier, CenterDepthSampler depth, BufferFlipper flipper,
            Supplier<ShadowRenderTargets> shadows, TextureStage textureStage,
            Object2ObjectMap<String, TextureAccess> customTextures,
            Object2ObjectMap<String, TextureAccess> irisTextures, Set<GlImage> images,
            ImmutableMap<Integer, Boolean> explicitFlips, CustomUniforms customUniforms,
            CallbackInfo ci) {
        vulkanite$finalCurrentAlt = Set.copyOf(flipper.snapshot());
    }

    @Inject(method = "renderAll", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/gl/GLDebug;popGroup()V", ordinal = 0,
            shift = At.Shift.AFTER))
    private void vulkanite$afterComputeOnly(CallbackInfo ci, @Local(index = 4) int passIndex) {
        vulkanite$afterPass(passIndex);
    }

    @Inject(method = "renderAll", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/gl/GLDebug;popGroup()V", ordinal = 1,
            shift = At.Shift.AFTER))
    private void vulkanite$afterRasterPass(CallbackInfo ci, @Local(index = 4) int passIndex) {
        vulkanite$afterPass(passIndex);
    }

    @Unique
    private void vulkanite$afterPass(int index) {
        if (compositePass != CompositePass.COMPOSITE
                || !(pipeline instanceof VulkanitePipelineAccess access)
                || !access.vulkanite$capabilities().needsVulkan()) return;
        if (index < 0 || index >= passes.size())
            throw new IllegalStateException("Iris composite pass index is out of range: " + index);
        var completed = (CompositePassView) passes.get(index);
        Set<Integer> currentAlt = vulkanite$finalCurrentAlt;
        for (int next = index + 1; next < passes.size(); next++) {
            var entrySides = ((CompositePassView) passes.get(next)).vulkanite$readsFromAlt();
            if (entrySides != null) {
                currentAlt = entrySides;
                break;
            }
        }
        access.vulkanite$afterCompositePass(completed.vulkanite$name(), currentAlt);
    }
}
