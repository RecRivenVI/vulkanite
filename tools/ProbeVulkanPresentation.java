import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.nio.*;
import static org.lwjgl.sdl.SDLInit.*;
import static org.lwjgl.sdl.SDLVideo.*;
import static org.lwjgl.sdl.SDLProperties.*;
import static org.lwjgl.sdl.SDLError.*;
import static org.lwjgl.sdl.SDLEvents.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.KHRWin32Surface.*;

/** Isolated Windows SDL/GL + Vulkan WSI probe. Hidden windows; never touches Minecraft. */
public final class ProbeVulkanPresentation {
    private static void check(int result, String operation) {
        if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR) throw new IllegalStateException(operation + ": VkResult=" + result);
    }
    private static void sdl(boolean success, String operation) {
        if (!success) throw new IllegalStateException(operation + ": " + SDL_GetError());
    }
    public static void main(String[] args) {
        sdl(SDL_Init(SDL_INIT_VIDEO), "SDL_Init");
        long producer = 0, context = 0, separateWindow = 0;
        try {
            sdl(SDL_GL_SetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION, 4), "GL major");
            sdl(SDL_GL_SetAttribute(SDL_GL_CONTEXT_MINOR_VERSION, 5), "GL minor");
            sdl(SDL_GL_SetAttribute(SDL_GL_CONTEXT_PROFILE_MASK, SDL_GL_CONTEXT_PROFILE_CORE), "GL profile");
            producer = SDL_CreateWindow("Vulkanite isolated GL producer", 128, 96, SDL_WINDOW_OPENGL | SDL_WINDOW_HIDDEN);
            if (producer == 0) throw new IllegalStateException(SDL_GetError());
            context = SDL_GL_CreateContext(producer);
            if (context == 0) throw new IllegalStateException(SDL_GetError());
            sdl(SDL_GL_MakeCurrent(producer, context), "GL make current");
            GL.createCapabilities();
            org.lwjgl.opengl.GL11C.glClearColor(0.125f, 0.25f, 0.5f, 1);
            org.lwjgl.opengl.GL11C.glClear(org.lwjgl.opengl.GL11C.GL_COLOR_BUFFER_BIT);
            org.lwjgl.opengl.GL11C.glFinish();
            sdl(SDL_GL_SwapWindow(producer), "initial GL swap");
            System.out.println("GL renderer=" + org.lwjgl.opengl.GL11C.glGetString(org.lwjgl.opengl.GL11C.GL_RENDERER));
            boolean same = testWindow(producer, "same GL HWND");
            // A second window tests the alternative where GL only produces offscreen resources.
            separateWindow = SDL_CreateWindow("Vulkanite isolated Vulkan presenter", 128, 96, SDL_WINDOW_HIDDEN);
            if (separateWindow == 0) throw new IllegalStateException(SDL_GetError());
            boolean split = testWindow(separateWindow, "separate HWND, GL context retained");
            sdl(SDL_GL_MakeCurrent(producer, context), "restore producer");
            org.lwjgl.opengl.GL11C.glClear(org.lwjgl.opengl.GL11C.GL_COLOR_BUFFER_BIT);
            org.lwjgl.opengl.GL11C.glFinish();
            if (org.lwjgl.opengl.GL11C.glGetError() != 0) throw new IllegalStateException("GL producer failed after Vulkan presentation");
            sdl(SDL_GL_SwapWindow(producer), "GL swap after Vulkan teardown");
            System.out.println("RESULT sameHwnd=" + same + " separateHwnd=" + split + " glProducerSurvived=true");
            System.out.println("SCOPE hidden-window acquire/clear/submit/present/resize only; no GL-VK image handoff, Streamline, FG or visible-screen validation.");
            if (!same && !split) throw new IllegalStateException("Neither presentation topology passed");
        } finally {
            if (separateWindow != 0) SDL_DestroyWindow(separateWindow);
            if (context != 0) SDL_GL_DestroyContext(context);
            if (producer != 0) SDL_DestroyWindow(producer);
            SDL_Quit();
        }
    }

    private static boolean testWindow(long window, String label) {
        VkInstance instance = null;
        VkDevice device = null;
        long surface = 0, pool = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var app = VkApplicationInfo.calloc(stack).sType$Default().pApplicationName(stack.UTF8("Vulkanite WSI probe")).apiVersion(VK_MAKE_VERSION(1, 2, 0));
            var info = VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(app)
                    .ppEnabledExtensionNames(stack.pointers(stack.UTF8(VK_KHR_SURFACE_EXTENSION_NAME), stack.UTF8(VK_KHR_WIN32_SURFACE_EXTENSION_NAME)));
            PointerBuffer handle = stack.mallocPointer(1);
            check(vkCreateInstance(info, null, handle), "create instance");
            instance = new VkInstance(handle.get(0), info);
            int properties = SDL_GetWindowProperties(window);
            long hwnd = SDL_GetPointerProperty(properties, SDL_PROP_WINDOW_WIN32_HWND_POINTER, 0);
            long hinstance = SDL_GetPointerProperty(properties, SDL_PROP_WINDOW_WIN32_INSTANCE_POINTER, 0);
            if (hwnd == 0 || hinstance == 0) throw new IllegalStateException("SDL did not expose Win32 handles");
            LongBuffer out = stack.mallocLong(1);
            var surfaceInfo = VkWin32SurfaceCreateInfoKHR.calloc(stack).sType$Default().hwnd(hwnd).hinstance(hinstance);
            check(vkCreateWin32SurfaceKHR(instance, surfaceInfo, null, out), "create surface");
            surface = out.get(0);
            IntBuffer count = stack.ints(0);
            check(vkEnumeratePhysicalDevices(instance, count, null), "enumerate devices");
            PointerBuffer devices = stack.mallocPointer(count.get(0));
            check(vkEnumeratePhysicalDevices(instance, count, devices), "enumerate device handles");
            VkPhysicalDevice physical = null;
            int family = -1;
            for (int i = 0; i < devices.capacity() && family < 0; i++) {
                var candidate = new VkPhysicalDevice(devices.get(i), instance);
                vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, null);
                var queues = VkQueueFamilyProperties.calloc(count.get(0), stack);
                vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, queues);
                for (int q = 0; q < queues.capacity(); q++) {
                    IntBuffer supported = stack.ints(0);
                    check(vkGetPhysicalDeviceSurfaceSupportKHR(candidate, q, surface, supported), "surface support");
                    if (supported.get(0) == VK_TRUE && (queues.get(q).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0) {
                        physical = candidate; family = q; break;
                    }
                }
            }
            if (physical == null) throw new IllegalStateException("No graphics/present queue");
            var propertiesVk = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(physical, propertiesVk);
            System.out.println(label + " device=" + propertiesVk.deviceNameString() + " family=" + family);
            var queues = VkDeviceQueueCreateInfo.calloc(1, stack);
            queues.get(0).sType$Default().queueFamilyIndex(family).pQueuePriorities(stack.floats(1));
            var deviceInfo = VkDeviceCreateInfo.calloc(stack).sType$Default().pQueueCreateInfos(queues)
                    .ppEnabledExtensionNames(stack.pointers(stack.UTF8(VK_KHR_SWAPCHAIN_EXTENSION_NAME)));
            check(vkCreateDevice(physical, deviceInfo, null, handle), "create device");
            device = new VkDevice(handle.get(0), physical, deviceInfo);
            vkGetDeviceQueue(device, family, 0, handle);
            var queue = new VkQueue(handle.get(0), device);
            check(vkCreateCommandPool(device, VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                    .queueFamilyIndex(family).flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT), null, out), "command pool");
            pool = out.get(0);
            presentBatch(physical, device, queue, pool, surface, 128, 96);
            sdl(SDL_SetWindowSize(window, 192, 128), "resize");
            SDL_PumpEvents();
            presentBatch(physical, device, queue, pool, surface, 192, 128);
            System.out.println("PASS " + label + " submitted 12 presents across swapchain recreation");
            return true;
        } catch (RuntimeException failure) {
            System.out.println("FAIL " + label + " " + failure);
            return false;
        } finally {
            if (device != null) {
                vkDeviceWaitIdle(device);
                if (pool != 0) vkDestroyCommandPool(device, pool, null);
                vkDestroyDevice(device, null);
            }
            if (surface != 0) vkDestroySurfaceKHR(instance, surface, null);
            if (instance != null) vkDestroyInstance(instance, null);
        }
    }

    private static void presentBatch(VkPhysicalDevice physical, VkDevice device, VkQueue queue,
                                     long pool, long surface, int requestedWidth, int requestedHeight) {
        long swapchain = 0, acquired = 0, finished = 0, fence = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var caps = VkSurfaceCapabilitiesKHR.calloc(stack);
            check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physical, surface, caps), "capabilities");
            if ((caps.supportedUsageFlags() & VK_IMAGE_USAGE_TRANSFER_DST_BIT) == 0) throw new IllegalStateException("Swapchain lacks transfer dst");
            IntBuffer count = stack.ints(0);
            check(vkGetPhysicalDeviceSurfaceFormatsKHR(physical, surface, count, null), "formats");
            var formats = VkSurfaceFormatKHR.calloc(count.get(0), stack);
            check(vkGetPhysicalDeviceSurfaceFormatsKHR(physical, surface, count, formats), "formats list");
            var format = formats.get(0);
            int width = caps.currentExtent().width() == -1 ? Math.clamp(requestedWidth, caps.minImageExtent().width(), caps.maxImageExtent().width()) : caps.currentExtent().width();
            int height = caps.currentExtent().height() == -1 ? Math.clamp(requestedHeight, caps.minImageExtent().height(), caps.maxImageExtent().height()) : caps.currentExtent().height();
            int imagesCount = caps.minImageCount() + 1;
            if (caps.maxImageCount() != 0) imagesCount = Math.min(imagesCount, caps.maxImageCount());
            var info = VkSwapchainCreateInfoKHR.calloc(stack).sType$Default().surface(surface).minImageCount(imagesCount)
                    .imageFormat(format.format() == VK_FORMAT_UNDEFINED ? VK_FORMAT_B8G8R8A8_UNORM : format.format()).imageColorSpace(format.colorSpace())
                    .imageExtent(e -> e.set(width, height)).imageArrayLayers(1).imageUsage(VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                    .imageSharingMode(VK_SHARING_MODE_EXCLUSIVE).preTransform(caps.currentTransform())
                    .compositeAlpha(Integer.lowestOneBit(caps.supportedCompositeAlpha())).presentMode(VK_PRESENT_MODE_FIFO_KHR).clipped(true);
            LongBuffer out = stack.mallocLong(1);
            check(vkCreateSwapchainKHR(device, info, null, out), "create swapchain"); swapchain = out.get(0);
            check(vkGetSwapchainImagesKHR(device, swapchain, count, null), "swap images");
            LongBuffer images = stack.mallocLong(count.get(0));
            check(vkGetSwapchainImagesKHR(device, swapchain, count, images), "swap image handles");
            var semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
            check(vkCreateSemaphore(device, semaphoreInfo, null, out), "acquire semaphore"); acquired = out.get(0);
            check(vkCreateSemaphore(device, semaphoreInfo, null, out), "present semaphore"); finished = out.get(0);
            check(vkCreateFence(device, VkFenceCreateInfo.calloc(stack).sType$Default(), null, out), "fence"); fence = out.get(0);
            PointerBuffer commandHandle = stack.mallocPointer(1);
            check(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), commandHandle), "command");
            var command = new VkCommandBuffer(commandHandle.get(0), device);
            for (int frame = 0; frame < 6; frame++) {
                IntBuffer index = stack.ints(0);
                check(vkAcquireNextImageKHR(device, swapchain, 5_000_000_000L, acquired, 0, index), "acquire");
                check(vkResetCommandBuffer(command, 0), "reset command");
                check(vkBeginCommandBuffer(command, VkCommandBufferBeginInfo.calloc(stack).sType$Default()), "begin command");
                var barrier = VkImageMemoryBarrier.calloc(1, stack);
                barrier.get(0).sType$Default().oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .image(images.get(index.get(0))).dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                        .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1));
                vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, barrier);
                var color = VkClearColorValue.calloc(stack).float32(0, frame / 6f).float32(1, 0.25f).float32(2, 0.5f).float32(3, 1);
                var range = VkImageSubresourceRange.calloc(1, stack);
                range.get(0).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
                vkCmdClearColorImage(command, images.get(index.get(0)), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, color, range);
                barrier.get(0).oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).newLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)
                        .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(0);
                vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, null, null, barrier);
                check(vkEndCommandBuffer(command), "end command");
                var submit = VkSubmitInfo.calloc(stack).sType$Default().waitSemaphoreCount(1).pWaitSemaphores(stack.longs(acquired))
                        .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_TRANSFER_BIT)).pCommandBuffers(stack.pointers(command.address())).pSignalSemaphores(stack.longs(finished));
                check(vkQueueSubmit(queue, submit, fence), "submit");
                var present = VkPresentInfoKHR.calloc(stack).sType$Default().pWaitSemaphores(stack.longs(finished))
                        .swapchainCount(1).pSwapchains(stack.longs(swapchain)).pImageIndices(index);
                check(vkQueuePresentKHR(queue, present), "present");
                check(vkWaitForFences(device, fence, true, 5_000_000_000L), "render fence");
                // Probe deliberately serializes: this is not a production pacing design.
                check(vkQueueWaitIdle(queue), "present queue idle");
                check(vkResetFences(device, fence), "reset fence");
            }
            vkFreeCommandBuffers(device, pool, command);
            System.out.println("swapchain extent=" + width + "x" + height + " images=" + images.capacity());
        } finally {
            vkDeviceWaitIdle(device);
            if (fence != 0) vkDestroyFence(device, fence, null);
            if (finished != 0) vkDestroySemaphore(device, finished, null);
            if (acquired != 0) vkDestroySemaphore(device, acquired, null);
            if (swapchain != 0) vkDestroySwapchainKHR(device, swapchain, null);
        }
    }
}
