package me.cortex.vulkanite.mixin.minecraft;

import com.mojang.renderpearl.backend.opengl.GlRenderPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The backend may replace the frontend's compiled pipeline before this draw. */
@Mixin(targets = "com.mojang.renderpearl.backend.opengl.GlRenderPass")
public interface GlRenderPassPipelineAccess {
    @Accessor("pipeline")
    GlRenderPipeline vulkanite$pipeline();
}
