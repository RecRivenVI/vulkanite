package me.cortex.vulkanite.lib.base.initalizer;

import static me.cortex.vulkanite.lib.other.VUtil._CHECK_;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import me.cortex.vulkanite.lib.base.VContext;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Struct;
import org.lwjgl.vulkan.*;

public class VInitializer {
    private final VkInstance instance;
    private VkPhysicalDevice physicalDevice;
    private VkDevice device;
    private int queueCount;

    public VInitializer(
            String appName,
            String engineName,
            int major,
            int minor,
            String[] extensions,
            String[] layers) {
        try (MemoryStack stack = stackPush()) {
            VkApplicationInfo appInfo =
                    VkApplicationInfo.calloc(stack)
                            .sType$Default()
                            .apiVersion(VK_MAKE_VERSION(major, minor, 0))
                            .pApplicationName(stack.UTF8(appName))
                            .pEngineName(stack.UTF8(engineName));

            VkInstanceCreateInfo instanceCreateInfo =
                    VkInstanceCreateInfo.calloc(stack)
                            .sType$Default()
                            .pApplicationInfo(appInfo)
                            .ppEnabledExtensionNames(
                                    stack.pointers(
                                            Arrays.stream(extensions)
                                                    .map(stack::UTF8)
                                                    .toArray(ByteBuffer[]::new)))
                            .ppEnabledLayerNames(
                                    stack.pointers(
                                            Arrays.stream(layers)
                                                    .map(stack::UTF8)
                                                    .toArray(ByteBuffer[]::new)));

            PointerBuffer result = stack.pointers(0);
            _CHECK_(vkCreateInstance(instanceCreateInfo, null, result));

            instance = new VkInstance(result.get(0), instanceCreateInfo);
        }
    }

    public void findPhysicalDevice() {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer devices = getPhysicalDevices(stack);
            for (int i = 0; i < devices.capacity(); i++) {
                physicalDevice = new VkPhysicalDevice(devices.get(i), instance);
                break;
            }
        }
    }

    public boolean supportsSamplerAnisotropy() {
        try (MemoryStack stack = stackPush()) {
            var features = VkPhysicalDeviceFeatures.calloc(stack);
            vkGetPhysicalDeviceFeatures(physicalDevice, features);
            return features.samplerAnisotropy();
        }
    }

    // TODO: add nice queue creation system
    public void createDevice(
            List<String> extensions,
            List<String> layers,
            float[] queuePriorities,
            Consumer<VkPhysicalDeviceFeatures> deviceFeatures,
            List<Function<MemoryStack, Struct>> applicators) {
        var deviceExtensions = new HashSet<>(VulkanExtensions.device(physicalDevice));
        for (var extension : extensions) {
            if (!deviceExtensions.contains(extension)) {
                throw new IllegalStateException(
                        "Physical device is missing extension: " + extension);
            }
        }

        try (MemoryStack stack = stackPush()) {

            queueCount = queuePriorities.length;
            var queueCreateInfos =
                    VkDeviceQueueCreateInfo.calloc(1, stack)
                            .sType$Default()
                            .pQueuePriorities(stack.floats(queuePriorities))
                            .queueFamilyIndex(0);

            VkDeviceCreateInfo createInfo =
                    VkDeviceCreateInfo.calloc(stack)
                            .sType$Default()
                            .ppEnabledExtensionNames(
                                    stack.pointers(
                                            extensions.stream()
                                                    .map(stack::UTF8)
                                                    .toArray(ByteBuffer[]::new)))
                            .ppEnabledLayerNames(
                                    stack.pointers(
                                            layers.stream()
                                                    .map(stack::UTF8)
                                                    .toArray(ByteBuffer[]::new)))
                            .pQueueCreateInfos(queueCreateInfos);

            if (deviceFeatures != null) {
                var features = VkPhysicalDeviceFeatures.calloc(stack);
                deviceFeatures.accept(features);
                createInfo.pEnabledFeatures(features);
            } else {
                createInfo.pEnabledFeatures(null);
            }

            long chain = createInfo.address();
            var deviceProperties2 = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
            for (var applicator : applicators) {
                Struct feature = applicator.apply(stack);
                deviceProperties2.pNext(feature.address());
                vkGetPhysicalDeviceFeatures2(physicalDevice, deviceProperties2);
                long next = feature.address();
                MemoryUtil.memPutAddress(chain + 8, next);
                chain = next;
            }

            PointerBuffer pDevice = stack.callocPointer(1);
            _CHECK_(vkCreateDevice(physicalDevice, createInfo, null, pDevice));
            device = new VkDevice(pDevice.get(0), physicalDevice, createInfo);
        }
    }

    private PointerBuffer getPhysicalDevices(MemoryStack stack) {
        int[] res = new int[1];
        _CHECK_(vkEnumeratePhysicalDevices(instance, res, null));
        PointerBuffer devices = stack.callocPointer(res[0]);
        _CHECK_(vkEnumeratePhysicalDevices(instance, res, devices));
        if (res[0] != devices.capacity()) throw new IllegalStateException();
        return devices;
    }

    public VContext createContext() {
        // TODO:FIXME: DONT HARDCODE THE FACT IT HAS DEVICE ADDRESSES
        return new VContext(device, queueCount, true);
    }
}
