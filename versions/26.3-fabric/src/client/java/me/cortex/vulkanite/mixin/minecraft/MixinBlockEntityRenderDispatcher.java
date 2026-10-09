package me.cortex.vulkanite.mixin.minecraft;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import me.cortex.vulkanite.client.rendering.interop.DynamicSubmitOrigins;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;

/** Tags the existing block-entity submit by its world-local position. */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class MixinBlockEntityRenderDispatcher {
    @WrapMethod(method = "submit")
    private void vulkanite$observeBlockEntitySubmit(
            BlockEntityRenderState state,
            PoseStack pose,
            SubmitNodeCollector collector,
            CameraRenderState camera,
            Operation<Void> original) {
        DynamicSubmitOrigins.pushBlockEntity(state);
        try {
            original.call(state, pose, collector, camera);
        } finally {
            DynamicSubmitOrigins.pop();
        }
    }
}
