package me.cortex.vulkanite.lib.base;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2;

import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPipelinePropertiesKHR;

public class DeviceProperties {
    // Allocates with a calloc, TODO: add a destroy function for cleanup

    public final VkPhysicalDeviceRayTracingPipelinePropertiesKHR rtPipelineProperties;
    public final float timestampPeriod;
    public final float maxSamplerAnisotropy;
    public final boolean samplerAnisotropySupported;

    public DeviceProperties(VkDevice device) {
        rtPipelineProperties =
                VkPhysicalDeviceRayTracingPipelinePropertiesKHR.calloc().sType$Default();
        try (var stack = stackPush()) {
            var properties =
                    VkPhysicalDeviceProperties2.calloc(stack)
                            .sType$Default()
                            .pNext(rtPipelineProperties);
            vkGetPhysicalDeviceProperties2(device.getPhysicalDevice(), properties);
            timestampPeriod = properties.properties().limits().timestampPeriod();
            maxSamplerAnisotropy = properties.properties().limits().maxSamplerAnisotropy();
            var features = VkPhysicalDeviceFeatures.calloc(stack);
            org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceFeatures(device.getPhysicalDevice(), features);
            samplerAnisotropySupported = features.samplerAnisotropy();
        }
    }
}
