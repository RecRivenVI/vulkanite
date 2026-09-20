package me.cortex.vulkanite.mixin.minecraft;

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.lib.memory.SharedGlTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(GlTexture.class)
public abstract class MixinGlTexture {
    @Redirect(method = "destroyImmediately", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/backend/opengl/GlStateManager;_deleteTexture(I)V"))
    private void releaseShared(int id) {
        if ((Object)this instanceof SharedGlTexture texture) {
            Vulkanite.INSTANCE.addSyncedCallback(texture.getVGImage()::free);
        } else {
            GlStateManager._deleteTexture(id);
        }
    }
}
