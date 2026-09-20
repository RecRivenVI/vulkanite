package me.cortex.vulkanite.mixin.minecraft;

import com.mojang.blaze3d.vertex.BufferBuilder;
import me.cortex.vulkanite.client.rendering.interop.BufferBuilderVertexCount;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BufferBuilder.class)
public interface MixinBufferBuilderVertexCount extends BufferBuilderVertexCount {
    @Override
    @Accessor("vertices")
    int vulkanite$vertexCount();
}
