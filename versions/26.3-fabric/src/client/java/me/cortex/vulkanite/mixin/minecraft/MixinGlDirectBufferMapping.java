package me.cortex.vulkanite.mixin.minecraft;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import me.cortex.vulkanite.client.rendering.interop.PublicIndexedDrawCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A writable mapped buffer can change without another command-encoder callback. */
@Mixin(targets = "com.mojang.renderpearl.backend.opengl.GlBuffer$Direct")
public abstract class MixinGlDirectBufferMapping {
    @Inject(method = "map", at = @At("RETURN"))
    private void vulkanite$writableMapping(
            long offset, long length, boolean read, boolean write, CallbackInfoReturnable<?> cir) {
        if (write) PublicIndexedDrawCapture.bufferWritten((GpuBuffer) (Object) this, true);
    }
}
