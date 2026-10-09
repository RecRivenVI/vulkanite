package me.cortex.vulkanite.mixin.minecraft;

import me.cortex.vulkanite.compat.SpritePaddingAccess;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(TextureAtlasSprite.class)
public interface TextureAtlasSpriteAccessor extends SpritePaddingAccess {
    @Accessor("padding")
    int vulkanite$padding();
}
