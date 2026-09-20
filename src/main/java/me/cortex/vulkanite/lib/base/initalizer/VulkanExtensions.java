package me.cortex.vulkanite.lib.base.initalizer;

import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkPhysicalDevice;

import java.util.ArrayList;
import java.util.List;

import static me.cortex.vulkanite.lib.other.VUtil._CHECK_;
import static org.lwjgl.vulkan.VK10.*;

/** Driver-sized arrays must not occupy LWJGL's small per-thread MemoryStack. */
public final class VulkanExtensions {
    private VulkanExtensions() {}

    public static List<String> instance() {
        return enumerate((count, properties) ->
                vkEnumerateInstanceExtensionProperties((String) null, count, properties));
    }

    public static List<String> device(VkPhysicalDevice device) {
        return enumerate((count, properties) ->
                vkEnumerateDeviceExtensionProperties(device, (String) null, count, properties));
    }

    private interface Enumerator {
        int enumerate(int[] count, VkExtensionProperties.Buffer properties);
    }

    private static List<String> enumerate(Enumerator enumerator) {
        int[] count = new int[1];
        for (int attempt = 0; attempt < 4; attempt++) {
            _CHECK_(enumerator.enumerate(count, null));
            if (count[0] == 0) return List.of();
            try (var properties = VkExtensionProperties.calloc(count[0])) {
                int status = enumerator.enumerate(count, properties);
                if (status == VK_INCOMPLETE) continue;
                _CHECK_(status);
                var names = new ArrayList<String>(count[0]);
                for (int i = 0; i < count[0]; i++) names.add(properties.get(i).extensionNameString());
                return List.copyOf(names);
            }
        }
        throw new IllegalStateException("Vulkan extension list kept changing during enumeration");
    }
}
