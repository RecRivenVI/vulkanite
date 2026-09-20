package me.cortex.vulkanite.mixin.minecraft;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import me.cortex.vulkanite.client.rendering.interop.DynamicSubmitOrigins;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;

/** Preserves the normal producer's item-vs-arm context on submitted nodes. */
@Mixin(ItemStackRenderState.class)
public abstract class MixinItemStackRenderState {
    @WrapMethod(method="submit")
    private void vulkanite$observeItemSubmit(PoseStack pose,SubmitNodeCollector collector,
                                              int light,int overlay,int seed,
                                              Operation<Void> original) {
        boolean pushed=DynamicSubmitOrigins.pushItemSurface();
        try { original.call(pose,collector,light,overlay,seed); }
        finally { if(pushed) DynamicSubmitOrigins.pop(); }
    }
}
