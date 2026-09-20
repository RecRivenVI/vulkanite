package me.cortex.vulkanite.mixin.iris;

import com.google.common.collect.ImmutableSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The producer's pass name and entry-side ping-pong state. */
@Mixin(targets = "net.irisshaders.iris.pipeline.CompositeRenderer$Pass", remap = false)
public interface CompositePassView {
    @Accessor("name") String vulkanite$name();
    @Accessor("stageReadsFromAlt") ImmutableSet<Integer> vulkanite$readsFromAlt();
}
