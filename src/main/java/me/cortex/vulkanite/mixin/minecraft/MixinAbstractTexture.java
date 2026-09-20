package me.cortex.vulkanite.mixin.minecraft;
import com.mojang.renderpearl.api.textures.GpuTexture;
import me.cortex.vulkanite.compat.IVGImage;
import me.cortex.vulkanite.lib.memory.VGImage;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
@Mixin(AbstractTexture.class)
public abstract class MixinAbstractTexture implements IVGImage {
    @Shadow protected GpuTexture texture;
    @Override public void setVGImage(VGImage image) { throw new UnsupportedOperationException("Backing is owned by the GPU texture"); }
    @Override public VGImage getVGImage() { return texture instanceof IVGImage shared ? shared.getVGImage() : null; }
}
