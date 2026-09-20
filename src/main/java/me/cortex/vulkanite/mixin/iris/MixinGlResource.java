package me.cortex.vulkanite.mixin.iris;

import net.irisshaders.iris.gl.GlResource;
import org.spongepowered.asm.mixin.*;

/** Only the explicit shared-texture constructor may replace its initial GL name. */
@Mixin(value = GlResource.class, remap = false)
public abstract class MixinGlResource {
    @Shadow @Final @Mutable private int id;
    @Shadow protected abstract int getGlId();
    protected void setGlId(int id) { this.id = id; }
}