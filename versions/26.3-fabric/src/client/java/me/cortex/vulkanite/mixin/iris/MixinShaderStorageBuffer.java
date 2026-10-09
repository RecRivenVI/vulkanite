package me.cortex.vulkanite.mixin.iris;

import static org.lwjgl.opengl.GL15C.glDeleteBuffers;
import static org.lwjgl.opengl.GL43C.*;
import static org.lwjgl.vulkan.VK10.*;

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import java.nio.ByteBuffer;
import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.compat.IVGBuffer;
import me.cortex.vulkanite.compat.PackResourceScope;
import me.cortex.vulkanite.lib.memory.VGBuffer;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.buffer.BuiltShaderStorageInfo;
import net.irisshaders.iris.gl.buffer.ShaderStorageBuffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ShaderStorageBuffer.class, remap = false)
public class MixinShaderStorageBuffer implements IVGBuffer {
    @Shadow protected int id;
    @Shadow @Final protected BuiltShaderStorageInfo info;
    @Shadow @Final protected ByteBuffer content;

    @Shadow
    public void bind() {
        throw new AssertionError();
    }

    @Unique
    private final boolean vulkanite$shared = PackResourceScope.current().sharedStorageBuffers();

    @Unique private VGBuffer vkBuffer;

    public VGBuffer getBuffer() {
        return vkBuffer;
    }

    public void setBuffer(VGBuffer buffer) {
        if (vkBuffer != null && buffer != null) {
            throw new IllegalStateException("Override buffer not null");
        }
        this.vkBuffer = buffer;
        if (buffer != null) {
            glDeleteBuffers(id);
            id = vkBuffer.glId;
        }
    }

    @Unique
    private void allocate(long size, boolean initializeContent) {
        var previous = vkBuffer;
        var replacement =
                Vulkanite.getInstance()
                        .getCtx()
                        .memory
                        .createSharedBuffer(
                                size,
                                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        if (previous == null) IrisRenderSystem.deleteBuffers(id);
        else Vulkanite.getInstance().addSyncedCallback(previous::free);
        vkBuffer = replacement;
        id = replacement.glId;
        GlStateManager._glBindBuffer(GL_SHADER_STORAGE_BUFFER, id);
        IrisRenderSystem.clearBufferSubData(
                GL_SHADER_STORAGE_BUFFER, GL_R8, 0, size, GL_RED, GL_BYTE, new int[] {0});
        if (initializeContent && content != null)
            GlStateManager._glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, content);
        bind();
    }

    @Inject(method = "createStatic", at = @At("HEAD"), cancellable = true)
    private void createShared(CallbackInfo ci) {
        if (!vulkanite$shared) return;
        allocate(info.size(), true);
        ci.cancel();
    }

    @Inject(method = "resizeIfRelative", at = @At("HEAD"), cancellable = true)
    private void resizeShared(int width, int height, CallbackInfo ci) {
        if (!vulkanite$shared) return;
        if (info.relative())
            allocate(
                    Math.multiplyExact(
                            Math.multiplyExact(
                                    (long) (width * info.scaleX()),
                                    (long) (height * info.scaleY())),
                            info.size()),
                    false);
        ci.cancel();
    }

    @Redirect(
            method = "destroy",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lnet/irisshaders/iris/gl/IrisRenderSystem;deleteBuffers(I)V"))
    private void redirectDelete(int id) {
        if (vkBuffer != null) {
            Vulkanite.getInstance().addSyncedCallback(vkBuffer::free);
        } else {
            IrisRenderSystem.deleteBuffers(id);
        }
    }
}
