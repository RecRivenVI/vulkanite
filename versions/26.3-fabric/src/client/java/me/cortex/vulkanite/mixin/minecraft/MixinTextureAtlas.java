package me.cortex.vulkanite.mixin.minecraft;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.List;
import me.cortex.vulkanite.client.rendering.interop.SampledTextureBridge;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(TextureAtlas.class)
public abstract class MixinTextureAtlas {
    @Shadow private List<TextureAtlasSprite> sprites;

    @WrapMethod(method = "uploadAnimationFrames")
    private void observeAnimation(Operation<Void> original) {
        SampledTextureBridge.animate(
                ((TextureAtlas) (Object) this).getTexture(), sprites, () -> original.call());
    }
}
