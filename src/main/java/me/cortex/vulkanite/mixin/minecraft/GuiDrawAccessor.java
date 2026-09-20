package me.cortex.vulkanite.mixin.minecraft;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.client.gui.render.GuiRenderer$Draw")
public interface GuiDrawAccessor {
    @Accessor("pipeline") RenderPipeline vulkanite$pipeline();
}
