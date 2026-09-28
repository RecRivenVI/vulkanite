package me.cortex.vulkanite.client.rendering;

import me.cortex.vulkanite.acceleration.AccelerationManager;
import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.compat.RaytracingShaderSet;
import me.cortex.vulkanite.compat.VulkanPassContract;
import me.cortex.vulkanite.compat.EntityFrame;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.cmd.VCmdBuff;
import me.cortex.vulkanite.lib.cmd.VCommandPool;
import me.cortex.vulkanite.lib.descriptors.DescriptorUpdateBuilder;
import me.cortex.vulkanite.lib.memory.VBuffer;
import me.cortex.vulkanite.lib.memory.VGImage;
import me.cortex.vulkanite.lib.memory.VImage;
import me.cortex.vulkanite.lib.other.VImageView;
import me.cortex.vulkanite.lib.other.VSampler;
import me.cortex.vulkanite.lib.other.sync.VSemaphore;
import me.cortex.vulkanite.lib.other.sync.VGSemaphore;
import me.cortex.vulkanite.lib.pipeline.RaytracePipelineBuilder;
import me.cortex.vulkanite.lib.pipeline.VRaytracePipeline;
import me.cortex.vulkanite.lib.shader.reflection.ShaderReflection;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.util.*;

import static org.lwjgl.opengl.EXTSemaphore.GL_LAYOUT_GENERAL_EXT;
import static org.lwjgl.opengl.GL11C.glFinish;
import static org.lwjgl.opengl.GL11C.glFlush;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.*;
import static org.lwjgl.vulkan.VK10.*;

public class VulkanPipeline {
    private final VContext ctx;
    private final AccelerationManager accelerationManager;
    private final BlockAtlasInputs blockAtlasInputs;
    private final Set<Integer> requiredSsboBindings = new HashSet<>();
    private final VulkanPassContract execution;
    private final VCommandPool singleUsePool;
    private final FrameUniformRing uniformRing;
    private final SemaphoreRing<VGSemaphore> sharedSemaphoreRing;
    private final SemaphoreRing<VSemaphore> internalSemaphoreRing;
    private final List<FrameSubmissionCleanup> quarantinedFrames = new ArrayList<>();
    private final IdentityHashMap<VGImage, VImageView> entityTextureViews = new IdentityHashMap<>();
    private final Map<EntityFrame.SamplerState,VSampler> materialSamplers = new HashMap<>();

    private record RtPipeline(VRaytracePipeline pipeline, int commonSet, int geomSet, int customTexSet, int ssboSet) {}

    /** Cold error-path owner; normal frames transfer their leases to the fence callback. */
    private final class FrameSubmissionCleanup {
        SemaphoreRing.Lease<VGSemaphore> input;
        SemaphoreRing.Lease<VSemaphore> tlas;
        SemaphoreRing.Lease<VGSemaphore> output;
        FrameUniformRing.Lease uniform;
        VCmdBuff command;
        me.cortex.vulkanite.lib.other.sync.VFence fence;
        boolean submissionAttempted;
        boolean callbackInstalled;

        void failed(Throwable failure) {
            if (callbackInstalled) return;
            try {
                glFinish();
                ctx.cmd.waitQueueIdle(0);
            }
            catch (Throwable unsafe) {
                failure.addSuppressed(unsafe);
                quarantinedFrames.add(this); // Retain ownership; never free an in-flight lease.
                var runtime = Vulkanite.getIfInitialized();
                if (runtime != null) runtime.retainUnsafePipeline(VulkanPipeline.this, failure);
                return;
            }
            if (uniform != null) try {
                if (submissionAttempted) uniform.complete();
                else uniform.abortBeforeSubmit();
            } catch (Throwable releaseFailure) { failure.addSuppressed(releaseFailure); }
            if (command != null && !command.isFreed()) try { singleUsePool.releaseNow(command); }
            catch (Throwable releaseFailure) { failure.addSuppressed(releaseFailure); }
            if (fence != null && !fence.isFreed()) try { fence.free(); }
            catch (Throwable releaseFailure) { failure.addSuppressed(releaseFailure); }
            if (input != null) try { input.discardAfterIdle(); }
            catch (Throwable releaseFailure) { failure.addSuppressed(releaseFailure); }
            if (output != null) try { output.discardAfterIdle(); }
            catch (Throwable releaseFailure) { failure.addSuppressed(releaseFailure); }
            if (tlas != null) try { tlas.discardAfterIdle(); }
            catch (Throwable releaseFailure) { failure.addSuppressed(releaseFailure); }
        }
    }
    private ArrayList<RtPipeline> raytracePipelines = new ArrayList<>();

    private final VSampler sampler;
    private final VSampler ctexSampler;

    private final SharedImageViewTracker[] irisRenderTargetViews;
    private final SharedImageViewTracker[] customTextureViews;
    private final SharedImageViewTracker blockAtlasView;
    private final SharedImageViewTracker blockAtlasNormalView;
    private final SharedImageViewTracker blockAtlasSpecularView;

    private final VImage placeholderSpecular;
    private final VImageView placeholderSpecularView;
    private final VImage placeholderNormals;
    private final VImageView placeholderNormalsView;


    private final int maxIrisRenderTargets = 16;

    private boolean supportsEntities;

    public VulkanPipeline(VContext ctx, AccelerationManager accelerationManager, RaytracingShaderSet[] passes,
                          int[] ssboIds, VGImage[] customTextures, BlockAtlasInputs blockAtlasInputs,
                          VulkanPassContract execution) {
        this.ctx = ctx;
        this.accelerationManager = accelerationManager;
        this.blockAtlasInputs = blockAtlasInputs;
        this.execution = java.util.Objects.requireNonNull(execution, "execution");
        Set<Integer> declaredSsboBindings = new HashSet<>();
        for (int binding : ssboIds) declaredSsboBindings.add(binding);
        for (int binding : execution.storageMinimumBytes().keySet())
            if (!declaredSsboBindings.contains(binding))
                throw new IllegalArgumentException("Pack storage binding " + binding
                        + " is not declared as an Iris bufferObject");
        this.singleUsePool = ctx.cmd.createSingleUsePool();
        this.uniformRing = new FrameUniformRing(ctx);
        this.sharedSemaphoreRing = new SemaphoreRing<>(ctx.sync::createSharedBinarySemaphore);
        this.internalSemaphoreRing = new SemaphoreRing<>(ctx.sync::createBinarySemaphore);

        {
            this.customTextureViews = new SharedImageViewTracker[customTextures.length];
            for(int i = 0; i < customTextures.length; i++) {
                int index = i;
                this.customTextureViews[i] = new SharedImageViewTracker(ctx, ()->customTextures[index]);
            }

            this.irisRenderTargetViews = new SharedImageViewTracker[maxIrisRenderTargets];
            for(int i = 0; i < maxIrisRenderTargets; i++) {
                this.irisRenderTargetViews[i] = new SharedImageViewTracker(ctx, null);
            }
            this.blockAtlasView = new SharedImageViewTracker(ctx, blockAtlasInputs.albedo());
            this.blockAtlasNormalView = new SharedImageViewTracker(ctx, blockAtlasInputs.normal());
            this.blockAtlasSpecularView = new SharedImageViewTracker(ctx, blockAtlasInputs.specular());
            this.placeholderSpecular = ctx.memory.createImage2D(4, 4, 1, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            this.placeholderSpecularView = new VImageView(ctx, placeholderSpecular);
            this.placeholderNormals = ctx.memory.createImage2D(4, 4, 1, VK_FORMAT_R32G32B32A32_SFLOAT, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            this.placeholderNormalsView = new VImageView(ctx, placeholderNormals);

            try (var stack = stackPush()) {
                var initZeros = stack.callocInt(4 * 4);
                var initNormals = stack.mallocFloat(4 * 4 * 4);
                for (int i = 0; i < 4 * 4; i++) {
                    initNormals.put(new float[] {0.5f, 0.5f, 1.0f, 1.0f});
                }
                initNormals.rewind();

                var cmd = singleUsePool.createCommandBuffer();
                cmd.begin(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
                cmd.encodeImageTransition(placeholderSpecular, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_ASPECT_COLOR_BIT, 1);
                cmd.encodeImageTransition(placeholderNormals, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_ASPECT_COLOR_BIT, 1);
                cmd.encodeImageUpload(ctx.memory, MemoryUtil.memAddress(initZeros), placeholderSpecular, initZeros.capacity() * 4, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
                cmd.encodeImageUpload(ctx.memory, MemoryUtil.memAddress(initNormals), placeholderNormals, initNormals.capacity() * 4, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
                cmd.encodeImageTransition(placeholderSpecular, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_ASPECT_COLOR_BIT, 1);
                cmd.encodeImageTransition(placeholderNormals, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_ASPECT_COLOR_BIT, 1);
                cmd.end();

                ctx.cmd.submit(0, VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(cmd)));

                Vulkanite.getInstance().addSyncedCallback(cmd::enqueueFree);
            }
        }

        this.sampler = new VSampler(ctx, a->a.magFilter(VK_FILTER_NEAREST)
                .minFilter(VK_FILTER_NEAREST)
                .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .compareOp(VK_COMPARE_OP_NEVER)
                .maxLod(1)
                .borderColor(VK_BORDER_COLOR_INT_OPAQUE_BLACK)
                .maxAnisotropy(1.0f));

        this.ctexSampler = new VSampler(ctx, a->a.magFilter(VK_FILTER_LINEAR)
                .minFilter(VK_FILTER_LINEAR)
                .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .compareOp(VK_COMPARE_OP_NEVER)
                .maxLod(1)
                .borderColor(VK_BORDER_COLOR_INT_OPAQUE_BLACK)
                .maxAnisotropy(1.0f));

        if (passes == null) {
            supportsEntities = false;
            return;
        }

        try {
            var commonSetExpected = new ShaderReflection.Set(new ShaderReflection.Binding[]{
                new ShaderReflection.Binding("", 0, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 0, false),
                new ShaderReflection.Binding("", 1, VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR, 0, false),
                new ShaderReflection.Binding("", 3, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 0, false),
                new ShaderReflection.Binding("", 4, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 0, false),
                new ShaderReflection.Binding("", 5, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 0, false),
                new ShaderReflection.Binding("", 6, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 0, false),
                new ShaderReflection.Binding("", 7, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, me.cortex.vulkanite.compat.EntityFrame.MAX_TEXTURES, false),
                new ShaderReflection.Binding("", 16, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, me.cortex.vulkanite.compat.EntityFrame.MAX_TEXTURES, false),
                new ShaderReflection.Binding("", 17, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, me.cortex.vulkanite.compat.EntityFrame.MAX_TEXTURES, false),
                new ShaderReflection.Binding("", 18, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, execution.colorReads().size(), false),
            });

            var geomSetExpected = new ShaderReflection.Set(new ShaderReflection.Binding[]{
               new ShaderReflection.Binding("", 0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, true)
            });

            ArrayList<ShaderReflection.Binding> customTexBindings = new ArrayList<>();
            for (int i = 0; i < customTextureViews.length; i++) {
                customTexBindings.add(new ShaderReflection.Binding("", i, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 0, false));
            }
            var customTexSetExpected = new ShaderReflection.Set(customTexBindings);

            for (int i = 0; i < passes.length; i++) {
                var builder = new RaytracePipelineBuilder();

                passes[i].apply(builder);
                var pipe = builder.build(ctx, 1);
                // Own the pipeline before validating its ABI so rejection also destroys it.
                raytracePipelines.add(new RtPipeline(pipe, -1, -1, -1, -1));

                // Validate the layout
                int commonSet = -1;
                int geomSet = -1;
                int customTexSet = -1;
                int ssboSet = -1;

                for (int setIdx = 0; setIdx < pipe.reflection.getNSets(); setIdx++) {
                    var set = pipe.reflection.getSet(setIdx);
                    if (set.validate(commonSetExpected)) {
                        commonSet = setIdx;
                        if (set.getBindingAt(0) == null || set.getBindingAt(1) == null || set.getBindingAt(6) == null)
                            throw new IllegalArgumentException("Ray pass requires camera, scene and one declared color output at common bindings 0/1/6");
                        if ((set.getBindingAt(18) != null) != !execution.colorReads().isEmpty())
                            throw new IllegalArgumentException("Ray color input binding 18 must match color.read in the pack contract");
                        if (set.getBindingAt(7) != null) {
                            if (passes[i].getRayHitCount() != 2)
                                throw new IllegalArgumentException("Neutral scene geometry requires terrain and dynamic hit groups");
                            supportsEntities = true;
                        }
                    } else if (set.validate(geomSetExpected)) {
                        geomSet = setIdx;
                    } else if (set.validate(customTexSetExpected)) {
                        customTexSet = setIdx;
                    } else if (isShaderStorageBufferSet(set)) {
                        if (ssboSet != -1)
                            throw new IllegalArgumentException("This single-segment executor supports one SSBO descriptor set per ray pass");
                        ssboSet = setIdx;
                        for (var binding : set.bindings()) {
                            if (!execution.storageMinimumBytes().containsKey(binding.binding()))
                                throw new IllegalArgumentException("Ray pass " + i + " requires storage binding "
                                        + binding.binding() + " (" + binding.name()
                                        + ") without storage.<binding>.minimumBytes in the pack contract");
                            requiredSsboBindings.add(binding.binding());
                        }
                    } else {
                        throw new RuntimeException("Raytracing pipeline " + i + " has an unexpected descriptor set layout at set " + setIdx);
                    }
                }

                raytracePipelines.set(raytracePipelines.size() - 1,
                        new RtPipeline(pipe, commonSet, geomSet, customTexSet, ssboSet));
            }
            if (supportsEntities && raytracePipelines.stream().anyMatch(p -> p.commonSet < 0 || p.pipeline.reflection.getSet(p.commonSet).getBindingAt(7) == null)) {
                throw new IllegalArgumentException("All ray passes must declare the neutral dynamic texture array at common binding 7");
            }
            for (int binding : execution.storageMinimumBytes().keySet())
                if (!requiredSsboBindings.contains(binding))
                    throw new IllegalArgumentException("Pack declares storage binding " + binding
                            + " but no ray pass references it");
        } catch (Exception e) {
            try { destory(); }
            catch (Throwable cleanupFailure) { e.addSuppressed(cleanupFailure); }
            throw new RuntimeException("Failed to initialize Vulkan ray tracing pipeline", e);
        }
    }

    private static boolean isShaderStorageBufferSet(ShaderReflection.Set set) {
        if (set.bindings().isEmpty()) return false;
        for (var binding : set.bindings()) {
            if (binding.descriptorType() != VK_DESCRIPTOR_TYPE_STORAGE_BUFFER || binding.runtimeSized()) {
                return false;
            }
        }
        return true;
    }

    private SemaphoreRing.Lease<VGSemaphore> previousOutputSemaphore;


    private boolean destroyed;
    private Throwable cleanupFailure;
    /** Executes at the pack-declared boundary after one complete Iris composite pass. */
    private static long vulkanite$lastRtFrameNanos;

    public void renderAtCompositeBoundary(List<VGImage> outImgs, FrameSnapshot frameInputs,
                                  List<SharedShaderBuffer> ssbos,
                                  me.cortex.vulkanite.compat.EntityFrame producedScene) {
        long now = System.nanoTime();
        if (vulkanite$lastRtFrameNanos != 0) {
            me.cortex.vulkanite.audit.Diagnostics.recordFrame(now - vulkanite$lastRtFrameNanos);
        }
        vulkanite$lastRtFrameNanos = now;
        long pipelineStart = System.nanoTime();
        try {
            renderAtCompositeBoundary0(outImgs, frameInputs, ssbos, producedScene);
        } finally {
            me.cortex.vulkanite.audit.Diagnostics.addCpu("pipeline", System.nanoTime() - pipelineStart);
        }
    }

    private void renderAtCompositeBoundary0(List<VGImage> outImgs, FrameSnapshot frameInputs,
                                  List<SharedShaderBuffer> ssbos,
                                  me.cortex.vulkanite.compat.EntityFrame producedScene) {
        Vulkanite.getInstance().assertRtAvailable();
        if (raytracePipelines.isEmpty()) return;
        var sharedBuffersByBinding = new HashMap<Integer, SharedShaderBuffer>();
        for (var input : ssbos) if (sharedBuffersByBinding.putIfAbsent(input.binding(), input) != null)
            throw new IllegalStateException("Duplicate Iris storage buffer binding " + input.binding());
        for (int binding : requiredSsboBindings) if (!sharedBuffersByBinding.containsKey(binding))
            throw new IllegalStateException("Required Iris storage buffer binding " + binding
                    + " is missing before Vulkan ray dispatch");
        for (var requirement : execution.storageMinimumBytes().entrySet()) {
            var shared = sharedBuffersByBinding.get(requirement.getKey());
            if (shared == null || shared.buffer().size() < requirement.getValue())
                throw new IllegalStateException("Iris storage binding " + requirement.getKey()
                        + " requires " + requirement.getValue() + " shared bytes before ray dispatch");
        }
        // Completion is tracked per submission. GL/Vulkan image ownership is ordered by
        // the external semaphore pair below; the render hot path never waits for queue idle.
        ctx.sync.checkFences();
        if (!supportsEntities && producedScene != null)
            throw new IllegalStateException("Ray pass does not declare the neutral scene ABI");
        var entityFrame = producedScene;
        accelerationManager.setEntityData(entityFrame);
        var entityViews = new ArrayList<VImageView>();
        var entityNormalViews = new ArrayList<VImageView>();
        var entitySpecularViews = new ArrayList<VImageView>();
        var entitySamplers = new ArrayList<VSampler>();
        var sharedEntityViews = new LinkedHashSet<VImageView>();
        if (entityFrame != null) for (var textures : entityFrame.textures()) {
            var albedoView=entityTextureView(textures.albedo());
            var normalView=textures.normal()!=null?entityTextureView(textures.normal()):placeholderNormalsView;
            var specularView=textures.specular()!=null?entityTextureView(textures.specular()):placeholderSpecularView;
            entityViews.add(albedoView);
            entityNormalViews.add(normalView);
            entitySpecularViews.add(specularView);
            entitySamplers.add(materialSampler(textures.sampler()));
            sharedEntityViews.add(albedoView);
            if(textures.normal()!=null) sharedEntityViews.add(normalView);
            if(textures.specular()!=null) sharedEntityViews.add(specularView);
        }


        this.singleUsePool.doReleases();
        blockAtlasInputs.refresh().run();
        blockAtlasView.getView();
        blockAtlasNormalView.getView();
        blockAtlasSpecularView.getView();
        for (var view : customTextureViews) view.getView();

        var colorImages = new LinkedHashSet<VGImage>();
        colorImages.add(outImgs.get(execution.colorWrite()));
        for (int read : execution.colorReads()) colorImages.add(outImgs.get(read));
        var sharedImages = new LinkedHashSet<VGImage>(colorImages);
        for (var tracker : List.of(blockAtlasView, blockAtlasNormalView, blockAtlasSpecularView))
            if (tracker.getImage() instanceof VGImage image) sharedImages.add(image);
        for (var tracker : customTextureViews)
            if (tracker.getImage() instanceof VGImage image) sharedImages.add(image);
        if (entityFrame != null) for(var textures:entityFrame.textures()) {
            sharedImages.add(textures.albedo());
            if(textures.normal()!=null) sharedImages.add(textures.normal());
            if(textures.specular()!=null) sharedImages.add(textures.specular());
        }

        var cleanup = new FrameSubmissionCleanup();
        try {
        var presentSsbos = ssbos;
        var inputSemaphoreLease = sharedSemaphoreRing.acquire();
        cleanup.input = inputSemaphoreLease;
        var in = inputSemaphoreLease.semaphore();
        var sharedBuffers = presentSsbos.stream().map(SharedShaderBuffer::buffer).toList();
        var sharedBufferGlIds = sharedBuffers.stream().mapToInt(buffer -> buffer.glId).toArray();
        var outImgsGlIds = sharedImages.stream().mapToInt(i -> i.glId).toArray();
        var outImgsGlLayouts = sharedImages.stream().mapToInt(i -> GL_LAYOUT_GENERAL_EXT).toArray();
        in.glSignal(sharedBufferGlIds, outImgsGlIds, outImgsGlLayouts);
        glFlush();

        var tlasLinkLease = internalSemaphoreRing.acquire();
        cleanup.tlas = tlasLinkLease;
        var tlasLink = tlasLinkLease.semaphore();

        var tlas = accelerationManager.buildTLAS(in, tlasLink);
        if (entityFrame != null) entityFrame.close();

        if (tlas == null) {
            tlasLinkLease.complete();
            inputSemaphoreLease.complete();
            return;
        }

        var outputSemaphoreLease = sharedSemaphoreRing.acquire();
        cleanup.output = outputSemaphoreLease;
        var out = outputSemaphoreLease.semaphore();
        VBuffer uboBuffer;
        FrameUniformRing.Lease uniformLease;
        {
            uniformLease = uniformRing.acquire();
            cleanup.uniform = uniformLease;
            uboBuffer = uniformLease.buffer();
            long ptr = uniformLease.address();
            MemoryUtil.memSet(ptr, 0, FrameAbi.BUFFER_SIZE);
            {
                ByteBuffer bb = MemoryUtil.memByteBuffer(ptr, FrameAbi.BUFFER_SIZE);

                Vector3f tmpv3 = new Vector3f();
                Matrix4f invProjMatrix = new Matrix4f();
                Matrix4f invViewMatrix = new Matrix4f();

                frameInputs.gbufferProjection().invert(invProjMatrix);
                var cameraPosition = frameInputs.cameraPosition();
                frameInputs.gbufferModelView().translate(new Vector3f((float)-cameraPosition.x,
                        (float)-cameraPosition.y, (float)-cameraPosition.z)).invert(invViewMatrix);
                invProjMatrix.transformProject(-1, -1, 0, 1, tmpv3).get(bb);
                invProjMatrix.transformProject(+1, -1, 0, 1, tmpv3).get(4*Float.BYTES, bb);
                invProjMatrix.transformProject(-1, +1, 0, 1, tmpv3).get(8*Float.BYTES, bb);
                invProjMatrix.transformProject(+1, +1, 0, 1, tmpv3).get(12*Float.BYTES, bb);
                invViewMatrix.get(Float.BYTES * 16, bb);

                // Celestial and environment values are supplied by the Iris shader frame bridge.

                bb.putInt(128, frameInputs.frameCounter());
                FrameAbi.writeMetadata(bb, FrameAbi.CAMERA, FrameAbi.SOURCE_HOST_ADAPTER);
                bb.rewind();
            }
            uniformLease.flush();

            // Call getView() on shared image view trackers to ensure they are created
            blockAtlasView.getView();
            blockAtlasNormalView.getView();
            blockAtlasSpecularView.getView();
            for (var v : customTextureViews) {
                v.getView();
            }

            //TODO: dont use a single use pool for commands like this...
            var cmd = singleUsePool.createCommandBuffer();
            cleanup.command = cmd;
            cmd.begin(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            me.cortex.vulkanite.lib.other.GpuTimestamps.ensure(ctx.device);
            me.cortex.vulkanite.lib.other.GpuTimestamps.beginFrame(cmd.buffer);

            {
                // Put barriers on images & transition to the optimal layout
                // These layouts also need to match the descriptor sets
                for (var img : colorImages) {
                    cmd.encodeImageTransition(img, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_ASPECT_COLOR_BIT, VK_REMAINING_MIP_LEVELS);
                }
                cmd.encodeImageTransition(blockAtlasView.getImage(), VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_ASPECT_COLOR_BIT, VK_REMAINING_MIP_LEVELS);

                var image = blockAtlasNormalView.getImage();
                if (image != null) cmd.encodeImageTransition(image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_ASPECT_COLOR_BIT, VK_REMAINING_MIP_LEVELS);
                image = blockAtlasSpecularView.getImage();
                if (image != null) cmd.encodeImageTransition(image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_ASPECT_COLOR_BIT, VK_REMAINING_MIP_LEVELS);

                for(SharedImageViewTracker customtexView : customTextureViews) {
                   cmd.encodeImageTransition(customtexView.getImage(), VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_ASPECT_COLOR_BIT, VK_REMAINING_MIP_LEVELS);
                }
                for (var view : sharedEntityViews) {
                    if (view.image == blockAtlasView.getImage()) continue;
                    cmd.encodeImageTransition(view.image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_ASPECT_COLOR_BIT, VK_REMAINING_MIP_LEVELS);
                }
            }


            boolean previousRayPass = false;
            me.cortex.vulkanite.lib.other.GpuTimestamps.write(cmd.buffer, me.cortex.vulkanite.lib.other.GpuTimestamps.SLOT_RAY, false);
            for (var record : raytracePipelines) {
                if (previousRayPass) cmd.encodeRayStorageBarrier();
                var pipeline = record.pipeline;
                pipeline.bind(cmd);
                var layouts = pipeline.reflection.getLayouts(); // Should be cached already
                var sets = new long[layouts.size()];
                if (record.commonSet != -1) {
                    var commonSet = Vulkanite.getInstance().getPoolByLayout(layouts.get(record.commonSet)).allocateSet();

                    try (var updater = new DescriptorUpdateBuilder(ctx, pipeline.reflection.getSet(record.commonSet))) {
                        updater.set(commonSet.set)
                                .uniform(0, uboBuffer)
                                .acceleration(1, tlas);
                        var commonBindings=pipeline.reflection.getSet(record.commonSet);
                        boolean usesAtlas=commonBindings.getBindingAt(3)!=null
                                || commonBindings.getBindingAt(4)!=null
                                || commonBindings.getBindingAt(5)!=null;
                        VSampler atlasSampler=usesAtlas
                                ?materialSampler(blockAtlasInputs.sampler().get()):sampler;
                        if (pipeline.reflection.getSet(record.commonSet).getBindingAt(3) != null)
                            updater.imageSampler(3, blockAtlasView.getView(), atlasSampler);
                        if (pipeline.reflection.getSet(record.commonSet).getBindingAt(4) != null)
                            updater.imageSampler(4,
                                        blockAtlasNormalView.getView() != null ? blockAtlasNormalView.getView()
                                                : placeholderNormalsView,
                                        atlasSampler);
                        if (pipeline.reflection.getSet(record.commonSet).getBindingAt(5) != null)
                            updater.imageSampler(5,
                                        blockAtlasSpecularView.getView() != null ? blockAtlasSpecularView.getView()
                                                : placeholderSpecularView,
                                        atlasSampler);
                        int outputIndex = execution.colorWrite();
                        updater.imageStore(6, irisRenderTargetViews[outputIndex]
                                .getView(() -> outImgs.get(outputIndex)));
                        if (!execution.colorReads().isEmpty()) {
                            List<VImageView> readViews = new ArrayList<>(execution.colorReads().size());
                            for (int readIndex : execution.colorReads())
                                readViews.add(irisRenderTargetViews[readIndex]
                                        .getView(() -> outImgs.get(readIndex)));
                            updater.imageSamplers(18, readViews, sampler, VK_IMAGE_LAYOUT_GENERAL);
                        }
                        if (supportsEntities) {
                            var views = new ArrayList<>(entityViews);
                            var normalViews = new ArrayList<>(entityNormalViews);
                            var specularViews = new ArrayList<>(entitySpecularViews);
                            var samplers = new ArrayList<>(entitySamplers);
                            while (views.size() < me.cortex.vulkanite.compat.EntityFrame.MAX_TEXTURES) views.add(placeholderSpecularView);
                            while (normalViews.size() < me.cortex.vulkanite.compat.EntityFrame.MAX_TEXTURES) normalViews.add(placeholderNormalsView);
                            while (specularViews.size() < me.cortex.vulkanite.compat.EntityFrame.MAX_TEXTURES) specularViews.add(placeholderSpecularView);
                            while (samplers.size() < EntityFrame.MAX_TEXTURES) samplers.add(sampler);
                            updater.imageSamplers(7, views, samplers);
                            if (pipeline.reflection.getSet(record.commonSet).getBindingAt(16) != null)
                                updater.imageSamplers(16, normalViews, samplers);
                            if (pipeline.reflection.getSet(record.commonSet).getBindingAt(17) != null)
                                updater.imageSamplers(17, specularViews, samplers);
                        }
                        updater.apply();
                    }

                    sets[record.commonSet] = commonSet.set;
                    cmd.addTransientResource(commonSet);
                }
                if (record.geomSet != -1) {
                    sets[record.geomSet] = accelerationManager.getGeometrySet();
                }
                if (record.customTexSet != -1) {
                    var ctexSet = Vulkanite.getInstance().getPoolByLayout(layouts.get(record.customTexSet)).allocateSet();

                    try (var updater = new DescriptorUpdateBuilder(ctx, pipeline.reflection.getSet(record.customTexSet))) {
                        updater.set(ctexSet.set);
                        for (int i = 0; i < customTextureViews.length; i++) {
                            updater.imageSampler(i, customTextureViews[i].getView(), ctexSampler);
                        }
                        updater.apply();
                    }

                    sets[record.customTexSet] = ctexSet.set;
                    cmd.addTransientResource(ctexSet);
                }
                if (record.ssboSet != -1) {
                    var ssboSet = Vulkanite.getInstance().getPoolByLayout(layouts.get(record.ssboSet)).allocateSet();

                    try (var updater = new DescriptorUpdateBuilder(ctx, pipeline.reflection.getSet(record.ssboSet))) {
                        updater.set(ssboSet.set);
                        for (var binding : pipeline.reflection.getSet(record.ssboSet).bindings()) {
                            var ssbo = sharedBuffersByBinding.get(binding.binding());
                            updater.buffer(binding.binding(), ssbo.buffer());
                        }
                        updater.apply();
                    }

                    sets[record.ssboSet] = ssboSet.set;
                    cmd.addTransientResource(ssboSet);
                }
                pipeline.bindDSet(cmd, sets);
                var output = outImgs.get(execution.colorWrite());
                pipeline.trace(cmd, output.width, output.height, 1);
                previousRayPass = true;
                me.cortex.vulkanite.lib.other.GpuTimestamps.write(cmd.buffer, me.cortex.vulkanite.lib.other.GpuTimestamps.SLOT_RAY, true);

                // Barrier on the output images
                for (var img : colorImages) {
                    cmd.encodeImageTransition(img, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_ASPECT_COLOR_BIT, VK_REMAINING_MIP_LEVELS);
                }
            }

            {
                // Transition images back to general layout (for OpenGL)
                for (var view : sharedEntityViews) {
                    if (view.image == blockAtlasView.getImage()) continue;
                    cmd.encodeImageTransition(view.image, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_ASPECT_COLOR_BIT, VK_REMAINING_MIP_LEVELS);
                }
                cmd.encodeImageTransition(blockAtlasView.getImage(), VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_ASPECT_COLOR_BIT, VK_REMAINING_MIP_LEVELS);

                var image = blockAtlasNormalView.getImage();
                if (image != null) cmd.encodeImageTransition(image, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_ASPECT_COLOR_BIT, VK_REMAINING_MIP_LEVELS);
                image = blockAtlasSpecularView.getImage();
                if (image != null) cmd.encodeImageTransition(image, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_ASPECT_COLOR_BIT, VK_REMAINING_MIP_LEVELS);

                for(SharedImageViewTracker customtexView : customTextureViews) {
                   cmd.encodeImageTransition(customtexView.getImage(), VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_ASPECT_COLOR_BIT, VK_REMAINING_MIP_LEVELS);
                }
            }

            cmd.end();
            var fence = ctx.sync.createFence();
            cleanup.fence = fence;
            uniformLease.markSubmitted();
            cleanup.submissionAttempted = true;
            ctx.cmd.submit(0, new VCmdBuff[]{cmd}, new VSemaphore[]{tlasLink}, new int[]{VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR}, new VSemaphore[]{out}, fence);


            var previousOutputCapture = previousOutputSemaphore;
            ctx.sync.addCallback(fence, () -> me.cortex.vulkanite.lib.other.sync.FenceCallbacks.runAll(
                    tlasLinkLease::complete,
                    inputSemaphoreLease::complete,
                    cmd::enqueueFree,
                    uniformLease::complete,
                    () -> { if (previousOutputCapture != null) previousOutputCapture.complete(); },
                    me.cortex.vulkanite.lib.other.GpuTimestamps::completeFrame,
                    fence::free));
            cleanup.callbackInstalled = true;
            previousOutputSemaphore = outputSemaphoreLease;
        }

        out.glWait(sharedBufferGlIds, outImgsGlIds, outImgsGlLayouts);
        glFlush();

        } catch (Throwable failure) {
            cleanup.failed(failure);
            throw failure;
        }
    }

    public void destory() {
        if (destroyed) return;
        if (cleanupFailure != null)
            throw new IllegalStateException("Vulkan pipeline is retained after an unsafe cleanup failure", cleanupFailure);
        try {
        glFinish();
        me.cortex.vulkanite.lib.other.VUtil._CHECK_(vkDeviceWaitIdle(ctx.device), "Vulkan pipeline device idle failed");

        // Check pending fences first
        // Then destroy the cmd pool (which destroys linked transient resources)
        ctx.sync.checkFences();
        if (!quarantinedFrames.isEmpty()) {
            var recovery = new IllegalStateException("Recovering frame resources after device idle");
            var pending = List.copyOf(quarantinedFrames);
            quarantinedFrames.clear();
            pending.forEach(frame -> frame.failed(recovery));
            if (!quarantinedFrames.isEmpty()) throw new IllegalStateException(
                    "Frame resources remain quarantined because GPU idle was not confirmed", recovery);
            if (recovery.getSuppressed().length != 0)
                org.slf4j.LoggerFactory.getLogger("Vulkanite").warn("Frame resource recovery was partial", recovery);
        }
        if (singleUsePool != null) {
            singleUsePool.doReleases();
            singleUsePool.free();
        }
        previousOutputSemaphore = null;
        sharedSemaphoreRing.close();
        internalSemaphoreRing.close();
        uniformRing.close();
        entityTextureViews.values().forEach(view -> {
            if (!view.isFreed()) view.free();
        });
        entityTextureViews.clear();
        materialSamplers.values().forEach(VSampler::free);
        materialSamplers.clear();
        // Finally destroy the pipelines
        // (Which destroys the descriptor set layouts & releases the VTypedDescriptorPool)
        for (var pass : raytracePipelines) {
            if (pass != null) {
                pass.pipeline.free();
            }
        }

        for (SharedImageViewTracker customTexView : customTextureViews) {
            if (customTexView != null)
                customTexView.free();
        }

        for (SharedImageViewTracker irisRenderTargetView : irisRenderTargetViews) {
            if (irisRenderTargetView != null)
                irisRenderTargetView.free();
        }

        if (blockAtlasView != null)
            blockAtlasView.free();
        if (blockAtlasNormalView != null)
            blockAtlasNormalView.free();
        if (blockAtlasSpecularView != null)
            blockAtlasSpecularView.free();
        if (placeholderNormalsView != null)
            placeholderNormalsView.free();
        if (placeholderNormals != null)
            placeholderNormals.free();
        if (placeholderSpecularView != null)
            placeholderSpecularView.free();
        if (placeholderSpecular != null)
            placeholderSpecular.free();
        if (sampler != null)
            sampler.free();
        if (ctexSampler != null)
            ctexSampler.free();
        destroyed = true;
        } catch (Throwable failure) {
            cleanupFailure = failure;
            var runtime = Vulkanite.getIfInitialized();
            if (runtime != null) runtime.retainUnsafePipeline(this, failure);
            throw failure;
        }
    }

    private VImageView entityTextureView(VGImage image) {
        var view = entityTextureViews.get(image);
        if (view == null || view.isFreed()) {
            view = new VImageView(ctx, image);
            entityTextureViews.put(image, view);
        }
        return view;
    }

    private VSampler materialSampler(EntityFrame.SamplerState state) {
        var existing=materialSamplers.get(state);
        if(existing!=null) return existing;
        if(materialSamplers.size()>=512)
            throw new IllegalStateException("Too many distinct material sampler states for one shader pack");
        int anisotropy=state.maxAnisotropy();
        if(anisotropy>1 && !ctx.properties.samplerAnisotropySupported)
            throw new IllegalStateException("Material requests anisotropy on a Vulkan device without sampler anisotropy");
        float supportedAnisotropy=Math.min(anisotropy,ctx.properties.maxSamplerAnisotropy);
        int addressU=state.addressU()==EntityFrame.Address.REPEAT
                ? VK_SAMPLER_ADDRESS_MODE_REPEAT:VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        int addressV=state.addressV()==EntityFrame.Address.REPEAT
                ? VK_SAMPLER_ADDRESS_MODE_REPEAT:VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        int minFilter=state.minFilter()==EntityFrame.Filter.LINEAR?VK_FILTER_LINEAR:VK_FILTER_NEAREST;
        int magFilter=state.magFilter()==EntityFrame.Filter.LINEAR?VK_FILTER_LINEAR:VK_FILTER_NEAREST;
        var created=new VSampler(ctx,a->a.magFilter(magFilter).minFilter(minFilter)
                .mipmapMode(VK_SAMPLER_MIPMAP_MODE_LINEAR)
                .addressModeU(addressU).addressModeV(addressV)
                .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .anisotropyEnable(anisotropy>1).maxAnisotropy(supportedAnisotropy)
                .minLod(0).maxLod(state.maxLod())
                .compareEnable(false).borderColor(VK_BORDER_COLOR_INT_OPAQUE_BLACK));
        materialSamplers.put(state,created);
        return created;
    }


}
