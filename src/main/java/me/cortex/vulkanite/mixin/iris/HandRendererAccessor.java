package me.cortex.vulkanite.mixin.iris;
import net.irisshaders.iris.pathways.HandRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(value=HandRenderer.class, remap=false)
public interface HandRendererAccessor {
    @Accessor("renderingSolid") boolean vulkanite$isSolid();
    @Accessor("renderingSolid") void vulkanite$setSolid(boolean solid);
}
