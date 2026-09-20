package me.cortex.vulkanite.mixin.minecraft;

import com.mojang.renderpearl.backend.opengl.GlRenderPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Public draw adapter's current culling rule, as set by RenderPearl. */
@Mixin(GlRenderPipeline.class)
public interface GlRenderPipelineSurfaceAccess {
    @Accessor("cull") boolean vulkanite$cull();
    @Accessor("blendEnabled") boolean[] vulkanite$blendEnabled();
}
