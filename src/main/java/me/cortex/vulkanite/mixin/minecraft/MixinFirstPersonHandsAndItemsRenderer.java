package me.cortex.vulkanite.mixin.minecraft;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import me.cortex.vulkanite.client.rendering.interop.DynamicSubmitOrigins;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

/** Tags nodes from each normal hand submit without submitting or drawing again. */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class MixinFirstPersonHandsAndItemsRenderer {
    @WrapMethod(method = "submitArmWithItem")
    private void vulkanite$observeHandSubmit(PlayerRenderState player,
                                              FirstPersonHandsAndItemsRenderState state,
                                              float delta,float xRot,InteractionHand hand,
                                              float swing,ItemStack item,float height,
                                              PoseStack pose,SubmitNodeCollector collector,int light,
                                              Operation<Void> original) {
        DynamicSubmitOrigins.pushHand();
        try { original.call(player,state,delta,xRot,hand,swing,item,height,pose,collector,light); }
        finally { DynamicSubmitOrigins.pop(); }
    }
}
