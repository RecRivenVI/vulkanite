package me.cortex.vulkanite.mixin.iris;

import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap.Entry;
import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.client.rendering.VulkanPipeline;
import me.cortex.vulkanite.client.rendering.BlockAtlasInputs;
import me.cortex.vulkanite.client.rendering.SharedShaderBuffer;
import me.cortex.vulkanite.client.rendering.interop.IrisFrameInputs;
import me.cortex.vulkanite.client.rendering.interop.ProducedDraws;
import me.cortex.vulkanite.client.rendering.interop.ProducedSceneAssembler;
import me.cortex.vulkanite.client.rendering.interop.PublicIndexedDrawCapture;
import me.cortex.vulkanite.client.rendering.interop.DynamicSubmitOrigins;
import me.cortex.vulkanite.client.rendering.interop.ProducedSubmitRanges;
import me.cortex.vulkanite.client.rendering.interop.TextureInputs;
import me.cortex.vulkanite.compat.*;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.memory.VGImage;
import net.irisshaders.iris.gl.buffer.ShaderStorageBuffer;
import net.irisshaders.iris.gl.texture.TextureAccess;
import net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder;
import net.irisshaders.iris.gl.uniform.DynamicUniformHolder;
import net.irisshaders.iris.pipeline.CustomTextureManager;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.targets.RenderTargets;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Mixin(value = IrisRenderingPipeline.class, remap = false)
public class MixinNewWorldRenderingPipeline implements VulkanitePipelineAccess {
    @Unique private PackCapabilities vulkanite$capabilities = PackCapabilities.OPENGL;
    @Override public PackCapabilities vulkanite$capabilities() { return vulkanite$capabilities; }
  
    @Shadow @Final private RenderTargets renderTargets;
    @Shadow @Final private CustomTextureManager customTextureManager;
    @Shadow private ShaderStorageBufferHolder shaderStorageBufferHolder;

    @Unique private RaytracingShaderSet[] rtShaderPasses = null;
    @Unique private VContext ctx;
    @Unique private VulkanPipeline pipeline;
    @Unique private ProducedSceneAssembler vulkanite$sceneAssembler;
    @Unique private boolean vulkanite$frameStarted;
    @Unique private boolean vulkanite$executedThisFrame;

    @Unique
    private VGImage[] getCustomTextures() {
        Object2ObjectMap<String, TextureAccess> texturesBinary = customTextureManager.getIrisCustomTextures();
        Object2ObjectMap<String, TextureAccess> texturesPNGs = customTextureManager.getCustomTextureIdMap(TextureStage.GBUFFERS_AND_SHADOW);

        List<Entry<String, TextureAccess>> entryList = new ArrayList<>();
        entryList.addAll(texturesBinary.object2ObjectEntrySet());
        entryList.addAll(texturesPNGs.object2ObjectEntrySet());

        entryList.sort(Comparator.comparing(Entry::getKey));

        return entryList.stream()
                .map(entry -> ((IVGImage) entry.getValue()).getVGImage())
                .toArray(VGImage[]::new);
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void injectRTShader(ProgramSet set, CallbackInfo ci) {
        vulkanite$capabilities = ((IGetRaytracingSource) set).vulkanite$capabilities();
        if (!vulkanite$capabilities.needsVulkan()) return;
        try {
        Vulkanite.getInstance().assertRtAvailable();
        ctx = Vulkanite.getInstance().getCtx();
        var passes = ((IGetRaytracingSource)set).getRaytracingSource();
        if (passes != null) {
            rtShaderPasses = new RaytracingShaderSet[passes.length];
            for (int i = 0; i < passes.length; i++) {
                rtShaderPasses[i] = new RaytracingShaderSet(ctx, passes[i]);
            }
        }
        pipeline = new VulkanPipeline(ctx, Vulkanite.getInstance().getAccelerationManager(), rtShaderPasses,
                set.getPack().getBufferObjects().keySet().toArray(new int[0]), getCustomTextures(),
                new BlockAtlasInputs(TextureInputs::blockAtlasAlbedo, TextureInputs::blockAtlasNormal,
                        TextureInputs::blockAtlasSpecular, TextureInputs::blockAtlasSamplerState,
                        TextureInputs::notifyPbrTexturesChanged),
                vulkanite$capabilities.execution().orElseThrow());
        if (vulkanite$capabilities.sceneGeometry()) {
            vulkanite$sceneAssembler=new ProducedSceneAssembler();
        }
        } catch (RuntimeException | Error failure) {
            // Iris's own constructor try/catch has already finished at this tail hook.
            // Release the fully constructed Iris resources as well as partial RT stages.
            try { ((IrisRenderingPipeline) (Object) this).destroy(); }
            catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    @Inject(method = "finalizeLevelRendering", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/pipeline/FinalPassRenderer;renderFinalPass()V", shift = At.Shift.BEFORE))
    private void vulkanite$requireCompositeExecution(CallbackInfo ci) {
        if (pipeline != null && vulkanite$frameStarted && !vulkanite$executedThisFrame) {
            PublicIndexedDrawCapture.discard();
            ProducedDraws.discard();
            ProducedSubmitRanges.end();
            DynamicSubmitOrigins.end();
            throw new IllegalStateException("The declared Iris composite pass did not execute Vulkan work this frame: "
                    + vulkanite$capabilities.execution().orElseThrow().afterPass());
        }
    }

    @Override
    public void vulkanite$afterCompositePass(String name, Set<Integer> currentAltTargets) {
        if (pipeline == null) return;
        var request = vulkanite$capabilities.execution().orElseThrow();
        if (!request.afterPass().equals(name)) return;
        if (!vulkanite$frameStarted || vulkanite$executedThisFrame)
            throw new IllegalStateException("Duplicate or out-of-frame Vulkan composite execution: " + name);
        vulkanite$executedThisFrame = true;
        var producedDraws = ProducedDraws.finish();
        ProducedSubmitRanges.end();
        DynamicSubmitOrigins.end();
        var indexedDraws = PublicIndexedDrawCapture.finish();
        ShaderStorageBuffer[] buffers = new ShaderStorageBuffer[0];

        if(shaderStorageBufferHolder != null) {
            buffers = ((ShaderStorageBufferHolderAccessor)shaderStorageBufferHolder).getBuffers();
        }

        List<VGImage> outImgs = new ArrayList<>();
        for (int i = 0; i < vulkanite$capabilities.sharedColorTargets(); i++) {
            var target = (IRenderTargetVkGetter) renderTargets.getOrCreate(i);
            var selected = currentAltTargets.contains(i) ? target.getAlt() : target.getMain();
            if (selected == null)
                throw new IllegalStateException("Declared colortex" + i + " has no shared current-side image");
            outImgs.add(selected);
        }

        List<SharedShaderBuffer> sharedBuffers = new ArrayList<>();
        for (var buffer : buffers) {
            if (buffer == null || !request.storageMinimumBytes().containsKey(buffer.getIndex())) continue;
            var shared = ((IVGBuffer) buffer).getBuffer();
            if (shared == null) throw new IllegalStateException("Iris storage buffer was not shared with Vulkan: " + buffer.getIndex());
            sharedBuffers.add(new SharedShaderBuffer(buffer.getIndex(), shared));
        }
        var frameInputs = IrisFrameInputs.snapshot();
        EntityFrame scene = null;
        if (vulkanite$capabilities.sceneGeometry()) {
            if (vulkanite$sceneAssembler == null)
                throw new IllegalStateException("Scene adapter was not initialized for this RT pack");
            scene = vulkanite$sceneAssembler.assemble(producedDraws,indexedDraws,frameInputs);
        }
        pipeline.renderAtCompositeBoundary(outImgs, frameInputs, sharedBuffers, scene);
    }

    @Inject(method = "beginLevelRendering", at = @At("HEAD"))
    private void vulkanite$beginProducedGeometryCapture(CallbackInfo ci) {
        if (pipeline != null) {
            vulkanite$frameStarted = true;
            vulkanite$executedThisFrame = false;
            if (vulkanite$capabilities.sceneGeometry()) {
                DynamicSubmitOrigins.begin();
                ProducedSubmitRanges.begin();
                ProducedDraws.begin();
                PublicIndexedDrawCapture.begin();
            }
        }
    }

    @Inject(method = "destroyShaders", at = @At("TAIL"))
    private void destory(CallbackInfo ci) {
        ProducedDraws.discard();
        PublicIndexedDrawCapture.discard();
        ProducedSubmitRanges.end();
        DynamicSubmitOrigins.end();
        vulkanite$frameStarted = false;
        if (ctx == null && pipeline == null && rtShaderPasses == null) return;
        // Iris may fail a raster shader before our constructor-tail hook runs.
        if (ctx != null) ctx.cmd.waitQueueIdle(0);
        if (rtShaderPasses != null) {
            for (var pass : rtShaderPasses) {
                if (pass != null) pass.delete();
            }
        }
        if (pipeline != null) pipeline.destory();
        if (vulkanite$sceneAssembler != null) {
            vulkanite$sceneAssembler.close();
            vulkanite$sceneAssembler = null;
        }
        rtShaderPasses = null;
        pipeline = null;
    }
}
