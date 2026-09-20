package me.cortex.vulkanite.client;

import me.cortex.vulkanite.acceleration.AccelerationManager;
import me.cortex.vulkanite.acceleration.SectionHandle;
import me.cortex.vulkanite.acceleration.SectionMeshUpdate;
import me.cortex.vulkanite.client.rendering.VulkanPipeline;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.initalizer.VInitializer;
import me.cortex.vulkanite.lib.descriptors.VDescriptorPool;
import me.cortex.vulkanite.lib.descriptors.VDescriptorSetLayout;
import me.cortex.vulkanite.lib.descriptors.VTypedDescriptorPool;
import org.lwjgl.vulkan.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.lwjgl.vulkan.EXTDescriptorIndexing.VK_EXT_DESCRIPTOR_INDEXING_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHR16bitStorage.VK_KHR_16BIT_STORAGE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHR8bitStorage.VK_KHR_8BIT_STORAGE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRBufferDeviceAddress.VK_KHR_BUFFER_DEVICE_ADDRESS_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRDeferredHostOperations.VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalFenceCapabilities.VK_KHR_EXTERNAL_FENCE_CAPABILITIES_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalFenceFd.VK_KHR_EXTERNAL_FENCE_FD_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalFenceWin32.VK_KHR_EXTERNAL_FENCE_WIN32_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalMemory.VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalMemoryCapabilities.VK_KHR_EXTERNAL_MEMORY_CAPABILITIES_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalMemoryFd.VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalMemoryWin32.VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalSemaphore.VK_KHR_EXTERNAL_SEMAPHORE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalSemaphoreCapabilities.VK_KHR_EXTERNAL_SEMAPHORE_CAPABILITIES_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalSemaphoreFd.VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalSemaphoreWin32.VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRGetMemoryRequirements2.VK_KHR_GET_MEMORY_REQUIREMENTS_2_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRGetPhysicalDeviceProperties2.VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRRayQuery.VK_KHR_RAY_QUERY_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRShaderDrawParameters.VK_KHR_SHADER_DRAW_PARAMETERS_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRSpirv14.VK_KHR_SPIRV_1_4_EXTENSION_NAME;

public class Vulkanite {
    public static final boolean IS_WINDOWS = System.getProperty("os.name", "").startsWith("Windows");


    public static boolean IS_ENABLED = true;
    private static Vulkanite instance;

    /** Only resource consumers may initialize Vulkan, never ordinary Minecraft ticks. */
    public static Vulkanite getInstance() {
        if (instance == null) instance = new Vulkanite();
        return instance;
    }

    public static Vulkanite getIfInitialized() { return instance; }

    private final VContext ctx;
    private final ArbitarySyncPointCallback fencedCallback = new ArbitarySyncPointCallback();

    private final AccelerationManager accelerationManager;
    private long activeTerrainGeneration = Long.MIN_VALUE;
    private Throwable terrainFailure;
    private Throwable fatalGpuFailure;
    private final List<VulkanPipeline> quarantinedPipelines = new ArrayList<>();
    private final HashMap<VDescriptorSetLayout, VTypedDescriptorPool> descriptorPools = new HashMap<>();

    public Vulkanite() {
        ctx = createVulkanContext();

        accelerationManager = new AccelerationManager(ctx, 1);
    }

    public void upload(List<SectionMeshUpdate> results) {
        long generation = me.cortex.vulkanite.compat.ActivePack.state().generation();
        ensureTerrainGeneration(generation);
        accelerationManager.chunkBuilds(results, generation);
    }

    public VTypedDescriptorPool getPoolByLayout(VDescriptorSetLayout layout) {
        synchronized (descriptorPools) {
            if (!descriptorPools.containsKey(layout)) {
                descriptorPools.put(layout, new VTypedDescriptorPool(ctx, layout, 0));
            }
            return descriptorPools.get(layout);
        }
    }

    public void removePoolByLayout(VDescriptorSetLayout layout) {
        synchronized (descriptorPools) {
            if (descriptorPools.containsKey(layout)) {
                descriptorPools.get(layout).free();
                descriptorPools.remove(layout);
            }
        }
    }

    public void sectionRemove(SectionHandle section) {
        accelerationManager.sectionRemove(section);
    }

    public void renderTick() {
        ctx.sync.checkFences();
        var state = me.cortex.vulkanite.compat.ActivePack.state();
        ensureTerrainGeneration(state.generation());
        accelerationManager.updateTick(state.generation(), state.capabilities().sceneGeometry());
    }

    private void ensureTerrainGeneration(long generation) {
        if (terrainFailure != null) {
            if (me.cortex.vulkanite.compat.ActivePack.state().capabilities().needsVulkan())
                throw new IllegalStateException("Vulkan terrain runtime failed earlier in this session", terrainFailure);
            activeTerrainGeneration = generation;
            return;
        }
        if (generation == activeTerrainGeneration) return;
        if (activeTerrainGeneration != Long.MIN_VALUE) try { accelerationManager.cleanup(); }
        catch (Throwable failure) {
            terrainFailure = failure;
            activeTerrainGeneration = generation;
            if (me.cortex.vulkanite.compat.ActivePack.state().capabilities().needsVulkan())
                throw new IllegalStateException("Vulkan terrain cleanup failed", failure);
            org.slf4j.LoggerFactory.getLogger("Vulkanite").error(
                    "Vulkan terrain cleanup failed; ordinary OpenGL rendering remains active", failure);
            return;
        }
        activeTerrainGeneration = generation;
    }

    public void fenceTick() {
        fencedCallback.tick();
    }

    public VContext getCtx() {
        return ctx;
    }

    /** An unsafe device-idle failure makes later RT allocations invalid for this session. */
    public void retainUnsafePipeline(VulkanPipeline pipeline, Throwable failure) {
        if (fatalGpuFailure == null) fatalGpuFailure = failure;
        if (!quarantinedPipelines.contains(pipeline)) quarantinedPipelines.add(pipeline);
        org.slf4j.LoggerFactory.getLogger("Vulkanite").error(
                "Vulkan pipeline cleanup could not confirm safe release; RT is disabled for this session", failure);
    }

    public void assertRtAvailable() {
        if (fatalGpuFailure != null)
            throw new IllegalStateException("Vulkan RT is unavailable after an earlier device/cleanup failure", fatalGpuFailure);
        if (terrainFailure != null)
            throw new IllegalStateException("Vulkan RT is unavailable after an earlier terrain cleanup failure", terrainFailure);
    }

    public void addSyncedCallback(Runnable callback) {
        fencedCallback.enqueue(callback);
    }

    public void destroy() {
        // Sodium can recreate section managers while Iris retains the shader pipeline.
        // Descriptor pools belong to that pipeline and are released with its layouts.
        if ((terrainFailure != null || fatalGpuFailure != null)
                && !me.cortex.vulkanite.compat.ActivePack.state().capabilities().needsVulkan()) return;
        try { accelerationManager.cleanup(); }
        catch (Throwable failure) {
            if (terrainFailure == null) terrainFailure = failure;
            if (me.cortex.vulkanite.compat.ActivePack.state().capabilities().needsVulkan())
                throw new IllegalStateException("Vulkan terrain cleanup failed", failure);
            org.slf4j.LoggerFactory.getLogger("Vulkanite").error(
                    "Vulkan terrain cleanup failed during world teardown; OpenGL remains available", failure);
        }
    }

    private static VContext createVulkanContext() {
        var init = new VInitializer("Vulkanite", "Vulkanite", 1, 3,
                new String[]{VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME,
                        VK_KHR_EXTERNAL_MEMORY_CAPABILITIES_EXTENSION_NAME,
                        VK_KHR_EXTERNAL_SEMAPHORE_CAPABILITIES_EXTENSION_NAME,
                        VK_KHR_EXTERNAL_FENCE_CAPABILITIES_EXTENSION_NAME,
                },
                new String[0]);

        //This copies whatever gpu the opengl context is on
        init.findPhysicalDevice();//glGetString(GL_RENDERER).split("/")[0]

        List<String> extensions = new ArrayList<>(List.of(
                VK_KHR_GET_MEMORY_REQUIREMENTS_2_EXTENSION_NAME,
                VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME,
                VK_KHR_EXTERNAL_SEMAPHORE_EXTENSION_NAME,
                VK_EXT_DESCRIPTOR_INDEXING_EXTENSION_NAME,
                VK_KHR_SPIRV_1_4_EXTENSION_NAME,
                VK_KHR_SHADER_DRAW_PARAMETERS_EXTENSION_NAME,

                //VK_KHR_RAY_QUERY_EXTENSION_NAME,

                VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME,
                VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME,

                VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME
        ));
        if (IS_WINDOWS) {
            extensions.addAll(List.of(VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME,
                    VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME,
                    VK_KHR_EXTERNAL_FENCE_WIN32_EXTENSION_NAME));
        } else {
            extensions.addAll(List.of(VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME,
                    VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME,
                    VK_KHR_EXTERNAL_FENCE_FD_EXTENSION_NAME));
        }
        boolean samplerAnisotropy=init.supportsSamplerAnisotropy();
        init.createDevice(extensions,
                List.of(),
                new float[]{1.0f, 0.9f},
                features -> features.shaderInt16(true).shaderInt64(true).multiDrawIndirect(true)
                        .shaderStorageImageExtendedFormats(true).samplerAnisotropy(samplerAnisotropy), List.of(
                        stack-> VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(stack)
                                .sType$Default(),

                        stack-> VkPhysicalDeviceRayTracingPipelineFeaturesKHR.calloc(stack)
                                .sType$Default(),

                        stack-> VkPhysicalDeviceVulkan11Features.calloc(stack)
                                .sType$Default(),

                        stack-> VkPhysicalDeviceVulkan12Features.calloc(stack)
                                .sType$Default()
                ));

        return init.createContext();
    }

    public AccelerationManager getAccelerationManager() {
        return accelerationManager;
    }

}
