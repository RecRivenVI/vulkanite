package me.cortex.vulkanite.client.rendering;

import me.cortex.vulkanite.acceleration.AccelerationManager;
import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.compat.IVGBuffer;
import me.cortex.vulkanite.compat.IVGImage;
import me.cortex.vulkanite.compat.RaytracingShaderSet;
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
import me.cortex.vulkanite.lib.pipeline.RaytracePipelineBuilder;
import me.cortex.vulkanite.lib.pipeline.VRaytracePipeline;
import me.cortex.vulkanite.lib.shader.reflection.ShaderReflection;
import me.cortex.vulkanite.mixin.iris.MixinCelestialUniforms;
import me.cortex.vulkanite.mixin.iris.MixinCommonUniforms;
import net.irisshaders.iris.gl.buffer.ShaderStorageBuffer;
import net.irisshaders.iris.pbr.texture.PBRTextureHolder;
import net.irisshaders.iris.pbr.texture.PBRTextureManager;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.CommonUniforms;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
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
    private final VCommandPool singleUsePool;

    private record RtPipeline(VRaytracePipeline pipeline, int commonSet, int geomSet, int customTexSet, int ssboSet) {}
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

    private int fidx;

    private final int maxIrisRenderTargets = 16;

    private boolean supportsEntities;
    private me.cortex.vulkanite.client.rendering.reconstruction.ReconstructionService reconstruction;
    private me.cortex.vulkanite.client.rendering.sharc.SharcService sharc;
    private final Vector3f frameCameraPosition = new Vector3f();

    public VulkanPipeline(VContext ctx, AccelerationManager accelerationManager, RaytracingShaderSet[] passes, int[] ssboIds, VGImage[] customTextures) {
        this.ctx = ctx;
        this.accelerationManager = accelerationManager;
        this.singleUsePool = ctx.cmd.createSingleUsePool();

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
            this.blockAtlasView = new SharedImageViewTracker(ctx, ()->{
                AbstractTexture blockAtlas = Minecraft.getInstance().getTextureManager().getTexture(Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/blocks.png"));
                return ((IVGImage)blockAtlas).getVGImage();
            });
            this.blockAtlasNormalView = new SharedImageViewTracker(ctx, ()->{
                AbstractTexture blockAtlas = Minecraft.getInstance().getTextureManager().getTexture(Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/blocks.png"));
                PBRTextureHolder holder = PBRTextureManager.INSTANCE.getOrLoadHolder(((net.irisshaders.iris.mixinterface.GpuTextureInterface)blockAtlas.getTexture()).iris$getGlId());//((TextureAtlasExtension)blockAtlas).getPBRHolder()
                return ((IVGImage)holder.normalTexture()).getVGImage();
            });
            this.blockAtlasSpecularView = new SharedImageViewTracker(ctx, ()->{
                AbstractTexture blockAtlas = Minecraft.getInstance().getTextureManager().getTexture(Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/blocks.png"));
                PBRTextureHolder holder = PBRTextureManager.INSTANCE.getOrLoadHolder(((net.irisshaders.iris.mixinterface.GpuTextureInterface)blockAtlas.getTexture()).iris$getGlId());//((TextureAtlasExtension)blockAtlas).getPBRHolder()
                return ((IVGImage)holder.specularTexture()).getVGImage();
            });
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

                Vulkanite.INSTANCE.addSyncedCallback(cmd::enqueueFree);
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
            me.cortex.vulkanite.client.rendering.sharc.SharcRequest sharcRequest = null;
            var commonSetExpected = new ShaderReflection.Set(new ShaderReflection.Binding[]{
                new ShaderReflection.Binding("", 0, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 0, false),
                new ShaderReflection.Binding("", 1, VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR, 0, false),
                new ShaderReflection.Binding("", 3, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 0, false),
                new ShaderReflection.Binding("", 4, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 0, false),
                new ShaderReflection.Binding("", 5, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 0, false),
                new ShaderReflection.Binding("", 6, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, maxIrisRenderTargets, false),
                new ShaderReflection.Binding("", 7, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, me.cortex.vulkanite.compat.EntityFrame.MAX_TEXTURES, false),
                new ShaderReflection.Binding("", 8, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 0, false),
                new ShaderReflection.Binding("", 9, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 0, false),
                new ShaderReflection.Binding("", 10, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 0, false),
                new ShaderReflection.Binding("", 11, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 0, false),
                new ShaderReflection.Binding("", 12, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 0, false),
                new ShaderReflection.Binding("", 13, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 0, false),
                new ShaderReflection.Binding("", 14, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 0, false),
                new ShaderReflection.Binding("", 15, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 0, false),
            });

            var geomSetExpected = new ShaderReflection.Set(new ShaderReflection.Binding[]{
               new ShaderReflection.Binding("", 0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, true)
            });

            ArrayList<ShaderReflection.Binding> customTexBindings = new ArrayList<>();
            for (int i = 0; i < customTextureViews.length; i++) {
                customTexBindings.add(new ShaderReflection.Binding("", i, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 0, false));
            }
            var customTexSetExpected = new ShaderReflection.Set(customTexBindings);

            ArrayList<ShaderReflection.Binding> ssboBindings = new ArrayList<>();
            for (int id : ssboIds) {
                ssboBindings.add(new ShaderReflection.Binding("", id, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 0, true));
            }
            var ssboSetExpected = new ShaderReflection.Set(ssboBindings);

            for (int i = 0; i < passes.length; i++) {
                if (passes[i].reconstruction != null) {
                    if (passes.length != 1) throw new IllegalArgumentException("Reconstruction ABI v1 currently requires one ray pass");
                    reconstruction = new me.cortex.vulkanite.client.rendering.reconstruction.ReconstructionService(ctx, passes[i].reconstruction);
                }
                if (passes[i].sharc != null) {
                    if (sharcRequest != null && !sharcRequest.equals(passes[i].sharc))
                        throw new IllegalArgumentException("All ray passes must use the same SHaRC request");
                    sharcRequest = passes[i].sharc;
                }
                var builder = new RaytracePipelineBuilder();
                passes[i].apply(builder);
                var pipe = builder.build(ctx, 1);

                // Validate the layout
                int commonSet = -1;
                int geomSet = -1;
                int customTexSet = -1;
                int ssboSet = -1;

                for (int setIdx = 0; setIdx < pipe.reflection.getNSets(); setIdx++) {
                    var set = pipe.reflection.getSet(setIdx);
                    if (set.validate(commonSetExpected)) {
                        commonSet = setIdx;
                        if (set.getBindingAt(7) != null) {
                            if (!set.getBindingAt(7).name().equals("entityTexturesV4"))
                                throw new IllegalArgumentException("Scene ABI v4 requires entityTexturesV4 at binding 7, 48-byte dynamic vertices with visibility/material flags and 64-byte layered terrain vertices. Update the shader pack together with Vulkanite.");
                            if (passes[i].getRayHitCount() != 2) throw new IllegalArgumentException("Entity ABI v1 requires terrain and entity hit groups");
                            supportsEntities = true;
                        }
                        if (reconstruction != null) passes[i].reconstruction.validate(set);
                        if (reconstruction == null && set.getBindingAt(8)!=null) throw new IllegalArgumentException("Reconstruction images require an explicit pack request");
                    } else if (set.validate(geomSetExpected)) {
                        geomSet = setIdx;
                    } else if (set.validate(customTexSetExpected)) {
                        customTexSet = setIdx;
                    } else if (set.validate(ssboSetExpected)) {
                        ssboSet = setIdx;
                    } else {
                        throw new RuntimeException("Raytracing pipeline " + i + " has an unexpected descriptor set layout at set " + setIdx);
                    }
                }

                raytracePipelines.add(new RtPipeline(pipe, commonSet, geomSet, customTexSet, ssboSet));
            }
            if (supportsEntities && raytracePipelines.stream().anyMatch(p -> p.commonSet < 0 || p.pipeline.reflection.getSet(p.commonSet).getBindingAt(7) == null)) {
                throw new IllegalArgumentException("All ray passes must declare Entity ABI v1 (common binding 7)");
            }
            if (sharcRequest != null && sharcRequest.enabled())
                sharc = new me.cortex.vulkanite.client.rendering.sharc.SharcService(ctx, sharcRequest);

        } catch (Exception e) {
            System.err.println(e.getMessage());

            e.printStackTrace();
            destory();
            throw new RuntimeException(e);
        }
    }

    private VSemaphore previousSemaphore;


    private final EntityCapture capture = new EntityCapture();
    private me.cortex.vulkanite.compat.EntityFrame entityFrame;
    private final List<VImageView> entityViews = new ArrayList<>();
    private boolean destroyed;
    private long renderedFrames;
    private void buildEntities() {
        if (entityFrame != null) { entityFrame.close(); entityFrame = null; }
        entityFrame = supportsEntities ? capture.capture(CapturedRenderingState.INSTANCE.getTickDelta(), Minecraft.getInstance().level) : null;
        accelerationManager.setEntityData(entityFrame);
    }

    public void renderPostShadows(List<VGImage> outImgs, Camera camera, ShaderStorageBuffer[] ssbos, MixinCelestialUniforms celestialUniforms) {
        if (raytracePipelines.isEmpty()) return;
        // Single in-flight frame until per-frame scene descriptors are implemented.
        // Both APIs must have finished with shared textures and last frame's entities.
        glFinish();
        ctx.cmd.waitQueueIdle(0);
        ctx.sync.checkFences();
        if (reconstruction != null) {
            if (reconstruction.request().output() >= outImgs.size()) throw new IllegalArgumentException("Reconstruction output target not allocated by pack");
            var target = outImgs.get(reconstruction.request().output());
            reconstruction.prepare(target.width, target.height);
            int exposureTarget=reconstruction.request().exposureTarget();
            if(exposureTarget>=0) {
                if(exposureTarget>=outImgs.size()) throw new IllegalArgumentException("Exposure target not allocated by pack");
                reconstruction.readExposure(outImgs.get(exposureTarget));
            }
        }
        entityViews.forEach(VImageView::free);
        entityViews.clear();
        buildEntities();
        if (entityFrame != null) for (var image : entityFrame.textures()) entityViews.add(new VImageView(ctx, image));


        this.singleUsePool.doReleases();
        PBRTextureManager.notifyPBRTexturesChanged();
        blockAtlasView.getView();
        blockAtlasNormalView.getView();
        blockAtlasSpecularView.getView();
        for (var view : customTextureViews) view.getView();

        var sharedImages = new LinkedHashSet<VGImage>(outImgs);
        for (var tracker : List.of(blockAtlasView, blockAtlasNormalView, blockAtlasSpecularView))
            if (tracker.getImage() instanceof VGImage image) sharedImages.add(image);
        for (var tracker : customTextureViews)
            if (tracker.getImage() instanceof VGImage image) sharedImages.add(image);
        if (entityFrame != null) sharedImages.addAll(entityFrame.textures());

        var in = ctx.sync.createSharedBinarySemaphore();
        var outImgsGlIds = sharedImages.stream().mapToInt(i -> i.glId).toArray();
        var outImgsGlLayouts = sharedImages.stream().mapToInt(i -> GL_LAYOUT_GENERAL_EXT).toArray();
        in.glSignal(new int[0], outImgsGlIds, outImgsGlLayouts);
        glFlush();

        var tlasLink = ctx.sync.createBinarySemaphore();

        var tlas = accelerationManager.buildTLAS(in, tlasLink);

        if (tlas == null) {
            glFinish();
            tlasLink.free();
            in.free();
            return;
        }

        var out = ctx.sync.createSharedBinarySemaphore();
        VBuffer uboBuffer;
        {
            uboBuffer = ctx.memory.createBuffer(1024,
                    VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
                    0, VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT);
            long ptr = uboBuffer.map();
            MemoryUtil.memSet(ptr, 0, 1024);
            {
                ByteBuffer bb = MemoryUtil.memByteBuffer(ptr, 1024);

                Vector3f tmpv3 = new Vector3f();
                Matrix4f invProjMatrix = new Matrix4f();
                Matrix4f invViewMatrix = new Matrix4f();

                CapturedRenderingState.INSTANCE.getGbufferProjection().invert(invProjMatrix);
                new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferModelView()).translate(camera.position().toVector3f().negate()).invert(invViewMatrix);
                invViewMatrix.getTranslation(frameCameraPosition);

                invProjMatrix.transformProject(-1, -1, 0, 1, tmpv3).get(bb);
                invProjMatrix.transformProject(+1, -1, 0, 1, tmpv3).get(4*Float.BYTES, bb);
                invProjMatrix.transformProject(-1, +1, 0, 1, tmpv3).get(8*Float.BYTES, bb);
                invProjMatrix.transformProject(+1, +1, 0, 1, tmpv3).get(12*Float.BYTES, bb);
                invViewMatrix.get(Float.BYTES * 16, bb);

                celestialUniforms.invokeGetSunPosition().get(Float.BYTES * 32, bb);
                celestialUniforms.invokeGetMoonPosition().get(Float.BYTES * 36, bb);
                int moonPhase=Minecraft.getInstance().gameRenderer.mainCamera().attributeProbe()
                        .getValue(net.minecraft.world.attribute.EnvironmentAttributes.MOON_PHASE,CapturedRenderingState.INSTANCE.getTickDelta()).index();
                double phaseAngle=Math.acos(Math.cos(moonPhase*Math.PI/4.0));
                bb.putFloat(156,(float)((Math.sin(phaseAngle)+(Math.PI-phaseAngle)*Math.cos(phaseAngle))/Math.PI));

                bb.putInt(Float.BYTES * 40, SystemTimeUniforms.COUNTER.getAsInt());

                int flags = MixinCommonUniforms.invokeIsEyeInWater() & 3;
                bb.putInt(Float.BYTES * 41, flags);
                if (reconstruction != null) reconstruction.writeCamera(bb, new Matrix4f(invViewMatrix).invert(),
                        new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferProjection()));
                if (sharc != null) sharc.writeCamera(bb, frameCameraPosition);
                bb.rewind();
            }
            uboBuffer.unmap();
            uboBuffer.flush();

            // Call getView() on shared image view trackers to ensure they are created
            blockAtlasView.getView();
            blockAtlasNormalView.getView();
            blockAtlasSpecularView.getView();
            for (var v : customTextureViews) {
                v.getView();
            }

            //TODO: dont use a single use pool for commands like this...
            var cmd = singleUsePool.createCommandBuffer();
            cmd.begin(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

            {
                // Put barriers on images & transition to the optimal layout
                // These layouts also need to match the descriptor sets
                for (var img : outImgs) {
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
                for (var view : entityViews) {
                    if (view.image == blockAtlasView.getImage()) continue;
                    cmd.encodeImageTransition(view.image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_ASPECT_COLOR_BIT, VK_REMAINING_MIP_LEVELS);
                }
            }

            for (var record : raytracePipelines) {
                var pipeline = record.pipeline;
                pipeline.bind(cmd);
                var layouts = pipeline.reflection.getLayouts(); // Should be cached already
                var sets = new long[layouts.size()];
                if (record.commonSet != -1) {
                    var commonSet = Vulkanite.INSTANCE.getPoolByLayout(layouts.get(record.commonSet)).allocateSet();

                    var updater = new DescriptorUpdateBuilder(ctx, pipeline.reflection.getSet(record.commonSet))
                            .set(commonSet.set)
                            .uniform(0, uboBuffer)
                            .acceleration(1, tlas)
                            .imageSampler(3, blockAtlasView.getView(), sampler)
                            .imageSampler(4,
                                    blockAtlasNormalView.getView() != null ? blockAtlasNormalView.getView()
                                            : placeholderNormalsView,
                                    sampler)
                            .imageSampler(5,
                                    blockAtlasSpecularView.getView() != null ? blockAtlasSpecularView.getView()
                                            : placeholderSpecularView,
                                    sampler);
                    List<VImageView> outImgViewList = new ArrayList<>(outImgs.size());
                    for (int i = 0; i < outImgs.size(); i++) {
                        int index = i;
                        outImgViewList.add(irisRenderTargetViews[i].getView(() -> outImgs.get(index)));
                    }
                    updater.imageStore(6, 0, outImgViewList);
                    if (reconstruction != null) reconstruction.bindInputs(updater);
                    if (supportsEntities) {
                        var views = new ArrayList<>(entityViews);
                        while (views.size() < me.cortex.vulkanite.compat.EntityFrame.MAX_TEXTURES) views.add(placeholderSpecularView);
                        updater.imageSamplers(7, views, sampler);
                    }
                    updater.apply();

                    sets[record.commonSet] = commonSet.set;
                    cmd.addTransientResource(commonSet);
                }
                if (record.geomSet != -1) {
                    sets[record.geomSet] = accelerationManager.getGeometrySet();
                }
                if (record.customTexSet != -1) {
                    var ctexSet = Vulkanite.INSTANCE.getPoolByLayout(layouts.get(record.customTexSet)).allocateSet();

                    var updater = new DescriptorUpdateBuilder(ctx, pipeline.reflection.getSet(record.customTexSet))
                            .set(ctexSet.set);
                    for (int i = 0; i < customTextureViews.length; i++) {
                        updater.imageSampler(i, customTextureViews[i].getView(), ctexSampler);
                    }
                    updater.apply();

                    sets[record.customTexSet] = ctexSet.set;
                    cmd.addTransientResource(ctexSet);
                }
                if (record.ssboSet != -1) {
                    var ssboSet = Vulkanite.INSTANCE.getPoolByLayout(layouts.get(record.ssboSet)).allocateSet();

                    var updater = new DescriptorUpdateBuilder(ctx, pipeline.reflection.getSet(record.ssboSet))
                            .set(ssboSet.set);
                    for (ShaderStorageBuffer ssbo : ssbos) {
                        updater.buffer(ssbo.getIndex(), ((IVGBuffer) ssbo).getBuffer());
                    }
                    updater.apply();

                    sets[record.ssboSet] = ssboSet.set;
                    cmd.addTransientResource(ssboSet);
                }
                pipeline.bindDSet(cmd, sets);
                pipeline.trace(cmd, reconstruction == null ? outImgs.get(0).width : reconstruction.width(),
                        reconstruction == null ? outImgs.get(0).height : reconstruction.height(), 1);

                // Barrier on the output images
                for (var img : outImgs) {
                    cmd.encodeImageTransition(img, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_ASPECT_COLOR_BIT, VK_REMAINING_MIP_LEVELS);
                }
            }

            if (sharc != null) sharc.resolve(cmd, frameCameraPosition);
            if (reconstruction != null) reconstruction.execute(cmd, outImgs.get(reconstruction.request().output()));
            {
                // Transition images back to general layout (for OpenGL)
                for (var view : entityViews) {
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
            ctx.cmd.submit(0, new VCmdBuff[]{cmd}, new VSemaphore[]{tlasLink}, new int[]{VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR}, new VSemaphore[]{out}, fence);


            var semCapture = previousSemaphore;
            previousSemaphore = out;
            ctx.sync.addCallback(fence, ()->{
                tlasLink.free();
                in.free();
                cmd.enqueueFree();
                fence.free();

                uboBuffer.free();
                if (semCapture != null) {
                    semCapture.free();
                }
            });
        }

        out.glWait(new int[0], outImgsGlIds, outImgsGlLayouts);
        glFlush();
        renderedFrames++;
        if (renderedFrames == 1 || renderedFrames == 120 || renderedFrames == 600) {
            org.slf4j.LoggerFactory.getLogger("Vulkanite").info("RT frame {}: entity quads={}, textures={}, output={}x{}",
                    renderedFrames, entityFrame == null ? 0 : entityFrame.quadCount(), entityViews.size(), outImgs.get(0).width, outImgs.get(0).height);
        }

        fidx++;
        fidx %= 10;

    }

    public void destory() {
        if (destroyed) return;
        destroyed = true;
        glFinish();
        vkDeviceWaitIdle(ctx.device);
        if (reconstruction != null) { reconstruction.close(); reconstruction = null; }
        if (sharc != null) { sharc.close(); sharc = null; }
        entityViews.forEach(VImageView::free);
        entityViews.clear();
        if (entityFrame != null) { entityFrame.close(); entityFrame = null; }
        capture.close();

        // Check pending fences first
        // Then destroy the cmd pool (which destroys linked transient resources)
        ctx.sync.checkFences();
        if (singleUsePool != null) {
            singleUsePool.doReleases();
            singleUsePool.free();
        }
        if (previousSemaphore != null) {
            previousSemaphore.free();
        }
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
    }


}
