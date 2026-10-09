package me.cortex.vulkanite.mixin.iris;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.List;
import me.cortex.vulkanite.client.rendering.interop.SampledTextureBridge;
import net.irisshaders.iris.pbr.loader.AtlasPBRLoader;
import net.irisshaders.iris.pbr.texture.PBRAtlasTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = PBRAtlasTexture.class, remap = false)
public abstract class MixinPBRAtlasTexture {
    @Shadow private List<AtlasPBRLoader.PBRTextureAtlasSprite> sprites;

    @WrapMethod(method = "cycleAnimationFrames")
    private void observeAnimation(Operation<Void> original) {
        SampledTextureBridge.animate(
                ((PBRAtlasTexture) (Object) this).getTexture(), sprites, () -> original.call());
    }
}
